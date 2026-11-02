package com.rag.follow.service;

import com.rag.follow.FollowServiceApplication;
import com.rag.follow.contract.ErrCodes;
import com.rag.follow.contract.GrpcErrorMapper;
import com.rag.follow.domain.service.FollowDomainService;
import com.rag.follow.support.FakeUserRpcServer;
import com.rag.follow.support.NacosStubConfiguration;
import com.rag.follow.support.TestPostgres;

import com.rag.proto.follow.ListReq;
import com.rag.proto.follow.RecommendResp;
import com.rag.proto.follow.RelationReq;
import com.rag.proto.follow.UserListResp;

import io.grpc.StatusRuntimeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.OffsetDateTime;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>follow 域 8 个 gRPC 用例的业务语义（真 PostgreSQL + 真 user-rpc gRPC）</b>。
 *
 * <h2>为什么不能只测 HTTP 层</h2>
 * <p>HTTP 层会把领域层的错误折成 envelope（{@code {code,msg,data}}）⇒ 领域层产出的
 * <b>业务码</b>就<b>被遮住</b>了 ⇒ 「5201 自关系 / 5202 已拉黑 / 5101 用户不存在 /
 * 5008 写失败」这些 L2 语义在 HTTP 层只表现为「code 不是 200」。
 * ⇒ 本类直接在<b>领域层</b>调 8 个用例，并用
 * {@link GrpcErrorMapper#bizCodeFromError} 把抛出的 {@link StatusRuntimeException}
 * <b>反查回业务码</b> ⇒ 断言的是精确码值。
 *
 * <h2>真触发手段</h2>
 * <ul>
 *   <li><b>真 PG</b>：两张表 + 唯一索引 + ON CONFLICT 全部是真 DDL/真 SQL；</li>
 *   <li><b>真 user-rpc</b>（{@link FakeUserRpcServer}）：存在性校验走<b>真 gRPC</b>
 *       ⇒ 5101 是「下游回 NOT_FOUND ⇒ 折叠成 5101」这条真实路径的产物。</li>
 * </ul>
 */
@SpringBootTest(classes = {FollowServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "grpc.server.port=-1"
        })
@ActiveProfiles("test")
class FollowDomainServiceRealPgTest {

    private static final AtomicLong UID_SEQ = new AtomicLong(8_300_000_000_000L);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        registry.add("grpc.client.user-rpc.address", FakeUserRpcServer::address);
    }

    @Autowired
    private FollowDomainService domainService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================================================================
    // 1. follow：幂等（uk_follow_pair 是物理载体）+ 三道闸门
    // ==================================================================

    @Test
    @DisplayName("🔴 follow 成功 ⇒ follow_relation 落一行（真 PG）")
    void followInsertsOneRow() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();

        assertThatCode(() -> domainService.follow(relation(userId, targetId))).doesNotThrowAnyException();

        assertThat(followCount(userId, targetId)).isEqualTo(1);
        Integer withTimestamp = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM follow_relation WHERE user_id = ? AND target_id = ? "
                        + "AND create_time IS NOT NULL AND update_time IS NOT NULL",
                Integer.class, userId, targetId);
        assertThat(withTimestamp)
                .as("时间戳由应用进程写入（B 侧 GORM 的 autoCreateTime/autoUpdateTime tag）⇒ 不得为 null")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 重复关注 ⇒ 仍是 SUCCESS + **只有 1 行**（ON CONFLICT DO NOTHING，不是 5006）")
    void repeatedFollowIsSilentSuccess() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();

        domainService.follow(relation(userId, targetId));
        assertThatCode(() -> domainService.follow(relation(userId, targetId)))
                .as("重复关注必须静默成功（B 侧：ON CONFLICT DO NOTHING ⇒ 0 行、不报错）")
                .doesNotThrowAnyException();

        assertThat(followCount(userId, targetId))
                .as("重复关注不得产生第二行")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 uk_follow_pair 是幂等性的物理载体：删掉它 ⇒ ON CONFLICT 报错 ⇒ 写路径变 5008")
    void uniqueIndexIsThePhysicalCarrierOfFollowIdempotency() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();

        // 前置：有唯一索引 ⇒ 重复关注静默成功
        domainService.follow(relation(userId, targetId));
        assertThatCode(() -> domainService.follow(relation(userId, targetId))).doesNotThrowAnyException();

        // 变异：删掉 uk_follow_pair ⇒ PG 报「there is no unique or exclusion constraint
        //      matching the ON CONFLICT specification」⇒ 领域层把它翻成 5008（ERROR_DB_UPDATE）
        jdbcTemplate.execute("DROP INDEX IF EXISTS uk_follow_pair");
        try {
            assertThatThrownBy(() -> domainService.follow(relation(userId, targetId)))
                    .as("没有唯一索引 ⇒ ON CONFLICT 目标不存在 ⇒ 写路径必须失败（5008），"
                            + "而不是「静默成功」")
                    .isInstanceOf(StatusRuntimeException.class)
                    .satisfies(t -> assertThat(GrpcErrorMapper.bizCodeFromError(t))
                            .isEqualTo(ErrCodes.ERROR_DB_UPDATE));
        } finally {
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_follow_pair "
                    + "ON follow_relation (user_id, target_id)");
        }

        // 复原后幂等性回来
        assertThatCode(() -> domainService.follow(relation(userId, targetId))).doesNotThrowAnyException();
        assertThat(indexExists("uk_follow_pair")).as("唯一索引必须复原").isTrue();
    }

    @Test
    @DisplayName("🔴 自关注 ⇒ 5201（self relation is not allowed）")
    void selfFollowIsRejectedWith5201() {
        long userId = UID_SEQ.incrementAndGet();

        assertThatThrownBy(() -> domainService.follow(relation(userId, userId)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> {
                    assertThat(GrpcErrorMapper.bizCodeFromError(t)).isEqualTo(ErrCodes.ERROR_SELF_RELATION);
                    assertThat(((StatusRuntimeException) t).getStatus().getDescription())
                            .as("description 必须保留业务文案（它是业务码的载体）")
                            .isEqualTo("self relation is not allowed");
                });
        assertThat(followCount(userId, userId)).isZero();
    }

    @Test
    @DisplayName("🔴 已被拉黑 ⇒ 5202（blocked relation exists）；**双向**都算（对方拉黑我也算）")
    void blockedRelationIsRejectedWith5202() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();

        // 对方拉黑我 ⇒ block 行是 (targetId → userId)
        //   ⇒ ExistsAnyBlock 是**双向**判定（(u,t) 或 (t,u)）⇒ 我关注他必须被拒
        insertBlock(targetId, userId);

        assertThatThrownBy(() -> domainService.follow(relation(userId, targetId)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> {
                    assertThat(GrpcErrorMapper.bizCodeFromError(t)).isEqualTo(ErrCodes.ERROR_RELATION_BLOCKED);
                    assertThat(((StatusRuntimeException) t).getStatus().getCode())
                            .isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED);
                    assertThat(((StatusRuntimeException) t).getStatus().getDescription())
                            .isEqualTo("blocked relation exists");
                });
        assertThat(followCount(userId, targetId)).as("被拒 ⇒ 不得留下关注行").isZero();
    }

    @Test
    @DisplayName("🔴 目标用户不存在 ⇒ 5101（user not found）—— 真 user-rpc 回 found=false")
    void unknownTargetUserIs5101() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();
        FakeUserRpcServer.markNotFound(targetId);
        try {
            assertThatThrownBy(() -> domainService.follow(relation(userId, targetId)))
                    .isInstanceOf(StatusRuntimeException.class)
                    .satisfies(t -> {
                        assertThat(GrpcErrorMapper.bizCodeFromError(t)).isEqualTo(ErrCodes.ERROR_USER_NOT_FOUND);
                        assertThat(((StatusRuntimeException) t).getStatus().getDescription())
                                .isEqualTo("user not found");
                    });
        } finally {
            FakeUserRpcServer.markFound(targetId);
        }
        assertThat(followCount(userId, targetId)).isZero();
    }

    // ==================================================================
    // 2. unfollow / block / unblock：删除类**不**校验用户存在性
    // ==================================================================

    @Test
    @DisplayName("🔴 取关一个从未关注过的人 ⇒ SUCCESS（删 0 行**不是**错误），且**不**校验用户存在性")
    void unfollowNonExistentRelationIsSuccess() {
        long userId = UID_SEQ.incrementAndGet();
        long ghost = UID_SEQ.incrementAndGet();
        FakeUserRpcServer.markNotFound(ghost);
        try {
            assertThatCode(() -> domainService.unfollow(relation(userId, ghost)))
                    .as("删除类操作不校验存在性（B 侧既定行为，照搬）")
                    .doesNotThrowAnyException();
        } finally {
            FakeUserRpcServer.markFound(ghost);
        }
    }

    @Test
    @DisplayName("🔴 拉黑 ⇒ block 行落库 + **双向**关注行被清掉（真 PG）")
    void blockCreatesBlockRowAndCleansBothDirections() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();
        insertFollow(userId, targetId);
        insertFollow(targetId, userId);

        assertThatCode(() -> domainService.block(relation(userId, targetId))).doesNotThrowAnyException();

        assertThat(blockCount(userId, targetId)).isEqualTo(1);
        assertThat(followCount(userId, targetId)).as("我→对方 必须被清").isZero();
        assertThat(followCount(targetId, userId)).as("对方→我 也必须被清（双向）").isZero();
    }

    @Test
    @DisplayName("🔴 解一个不存在的拉黑 ⇒ SUCCESS（删 0 行**不是**错误）")
    void unblockNonExistentRelationIsSuccess() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();

        assertThatCode(() -> domainService.unblock(relation(userId, targetId))).doesNotThrowAnyException();
    }

    // ==================================================================
    // 3. 三个列表 + 推荐
    // ==================================================================

    @Test
    @DisplayName("🔴 三个列表：过滤列与投影列各不相同（follow=list(target_id) / follower=list(user_id) / block=list(target_id)）")
    void threeListsUseDifferentFilterAndProjectionColumns() {
        long me = UID_SEQ.incrementAndGet();
        long friend = UID_SEQ.incrementAndGet();
        long other = UID_SEQ.incrementAndGet();

        insertFollow(me, friend);      // 我 → 朋友
        insertFollow(other, me);       // 别人 → 我
        insertBlock(me, other);        // 我拉黑别人

        UserListResp follows = domainService.getFollowList(listReq(me));
        assertThat(follows.getCode()).isEqualTo(ErrCodes.SUCCESS);
        assertThat(follows.getUserIdsList()).containsExactly(friend);

        UserListResp followers = domainService.getFollowerList(listReq(me));
        assertThat(followers.getUserIdsList()).containsExactly(other);

        UserListResp blocks = domainService.getBlockList(listReq(me));
        assertThat(blocks.getUserIdsList()).containsExactly(other);
    }

    @Test
    @DisplayName("🔴 空列表 ⇒ proto 里 user_ids 为空（**长度 0**）⇒ api 层据此输出 JSON null")
    void emptyListHasZeroUserIdsOnTheWire() {
        long me = UID_SEQ.incrementAndGet();

        UserListResp resp = domainService.getFollowList(listReq(me));

        assertThat(resp.getUserIdsCount())
                .as("proto3 的 repeated 为空 ⇒ 字段不上 wire ⇒ api 层反序列化得 nil ⇒ JSON null")
                .isZero();
        assertThat(resp.getUserIdsList()).isEmpty();
    }

    @Test
    @DisplayName("🔴 推荐：二度人脉（BFS 2 层）⇒ 目标被推荐、分数 = 20、自己不在结果里")
    void recommendationFindsSecondDegreeMutuals() {
        long me = UID_SEQ.incrementAndGet();
        long friend = UID_SEQ.incrementAndGet();
        long stranger = UID_SEQ.incrementAndGet();

        insertFollow(me, friend);
        insertFollow(friend, stranger);

        RecommendResp resp = domainService.getRecommendations(listReq(me));

        assertThat(resp.getCode()).isEqualTo(ErrCodes.SUCCESS);
        assertThat(resp.getUsersCount()).isEqualTo(1);
        assertThat(resp.getUsers(0).getTargetId()).isEqualTo(stranger);
        assertThat(resp.getUsers(0).getMutualScore()).isEqualTo(20);
    }

    @Test
    @DisplayName("🔴 推荐：直关为空 ⇒ 提前返回空（**不**查 blocked/blocker 集合）")
    void recommendationIsEmptyWhenNoDirectFollows() {
        long me = UID_SEQ.incrementAndGet();

        RecommendResp resp = domainService.getRecommendations(listReq(me));

        assertThat(resp.getCode()).isEqualTo(ErrCodes.SUCCESS);
        assertThat(resp.getUsersCount()).isZero();
    }

    // ==================================================================
    // 4. DDL 形状（Flyway 真跑出来的）
    // ==================================================================

    @Test
    @DisplayName("🔴 DDL：两张表建成，BIGSERIAL 主键 + TIMESTAMPTZ 列 + 3 个索引/表（含 1 个复合唯一索引）")
    void ddlShapeMatchesGoAutoMigrate() {
        // 表存在
        assertThat(tableExists("follow_relation")).isTrue();
        assertThat(tableExists("block_relation")).isTrue();

        // 列型：BIGSERIAL 主键（default nextval）、bigint NOT NULL、timestamptz 可空
        assertThat(columnType("follow_relation", "id")).isEqualTo("bigint");
        assertThat(columnDefault("follow_relation", "id")).startsWith("nextval");
        assertThat(columnType("follow_relation", "user_id")).isEqualTo("bigint");
        assertThat(columnNullable("follow_relation", "user_id")).isFalse();
        assertThat(columnType("follow_relation", "target_id")).isEqualTo("bigint");
        assertThat(columnNullable("follow_relation", "target_id")).isFalse();
        assertThat(columnType("follow_relation", "create_time")).isEqualTo("timestamp with time zone");
        assertThat(columnType("follow_relation", "update_time")).isEqualTo("timestamp with time zone");
        assertThat(columnNullable("follow_relation", "create_time")).isTrue();

        // 索引：每表 2 个单列 + 1 个复合唯一
        assertThat(indexExists("idx_follow_relation_user_id")).isTrue();
        assertThat(indexExists("idx_follow_relation_target_id")).isTrue();
        assertThat(indexExists("uk_follow_pair")).isTrue();
        assertThat(indexExists("idx_block_relation_user_id")).isTrue();
        assertThat(indexExists("idx_block_relation_target_id")).isTrue();
        assertThat(indexExists("uk_block_pair")).isTrue();
        assertThat(indexIsUnique("uk_follow_pair")).isTrue();
        assertThat(indexIsUnique("uk_block_pair")).isTrue();
        assertThat(indexColumns("uk_follow_pair")).contains("(user_id, target_id)");
        assertThat(indexColumns("uk_block_pair")).contains("(user_id, target_id)");
        assertThat(indexColumns("uk_follow_pair")).contains("USING btree");
    }

    /** 复合唯一索引的列定义（用 {@code pg_get_indexdef} 取，逐字可读）。 */
    private String indexColumns(String index) {
        String def = jdbcTemplate.queryForObject(
                "SELECT pg_get_indexdef(c.oid) FROM pg_class c "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE c.relname = ? AND n.nspname = 'public'", String.class, index);
        assertThat(def).as("索引 %s 必须存在", index).isNotNull();
        return def.substring(def.indexOf(" USING "));
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private static RelationReq relation(long userId, long targetId) {
        return RelationReq.newBuilder().setUserId(userId).setTargetId(targetId).build();
    }

    private static ListReq listReq(long userId) {
        return ListReq.newBuilder().setUserId(userId).setOffset(0).setLimit(0).build();
    }

    private void insertFollow(long userId, long targetId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO follow_relation (user_id, target_id, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?)",
                userId, targetId, Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));
    }

    private void insertBlock(long userId, long targetId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO block_relation (user_id, target_id, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?)",
                userId, targetId, Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));
    }

    private int followCount(long userId, long targetId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM follow_relation WHERE user_id = ? AND target_id = ?",
                Integer.class, userId, targetId);
        return n == null ? 0 : n;
    }

    private int blockCount(long userId, long targetId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM block_relation WHERE user_id = ? AND target_id = ?",
                Integer.class, userId, targetId);
        return n == null ? 0 : n;
    }

    private boolean tableExists(String table) {
        Boolean b = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?)",
                Boolean.class, table);
        return Boolean.TRUE.equals(b);
    }

    private String columnType(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    private String columnDefault(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    private boolean columnNullable(String table, String column) {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                String.class, table, column);
        return "YES".equals(nullable);
    }

    private boolean indexExists(String index) {
        Boolean b = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?)",
                Boolean.class, index);
        return Boolean.TRUE.equals(b);
    }

    private boolean indexIsUnique(String index) {
        Boolean b = jdbcTemplate.queryForObject(
                "SELECT indisunique FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                        + "WHERE c.relname = ?", Boolean.class, index);
        return Boolean.TRUE.equals(b);
    }


    @AfterEach
    void cleanupFakeUserRpcState() {
        // 本类用到 markNotFound ⇒ 用完必须复原，否则会污染同 JVM 里的其它测试类。
        FakeUserRpcServer.markFound(0L);
    }
}
