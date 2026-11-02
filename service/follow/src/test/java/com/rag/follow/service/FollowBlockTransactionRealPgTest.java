package com.rag.follow.service;

import com.rag.follow.FollowServiceApplication;
import com.rag.follow.support.FakeUserRpcServer;
import com.rag.follow.support.NacosStubConfiguration;
import com.rag.follow.support.TestPostgres;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>🔴 P0-1 · 「插 block + 双向删 follow」是<b>一笔事务</b> —— 真 PG + 真约束冲突 + 变异证据</b>。
 *
 * <h2>被测对象</h2>
 * <p>{@link FollowBlockTransactionService#createBlockAndCleanup(long, long)}：
 * <pre>
 *   B 侧（B 出处：follow-rpc/.../FollowBlockTransactionService.java:70-77）：
 *     tx.Clauses(clause.OnConflict{...DoNothing: true}).Create(&amp;BlockRelation{...})
 *     tx.Where("(user_id=? AND target_id=?) OR (user_id=? AND target_id=?)").Delete(&amp;FollowRelation{})
 *   ⇒ 两句在**同一个 GORM 事务**里 ⇒ 第二句失败 ⇒ 整体回滚。
 * </pre>
 *
 * <h2>🔴 为什么这条必须有<b>运行期</b>证据（AGENTS.md §3.3）</h2>
 * <p>{@code @Transactional} 靠 <b>AOP 代理</b>生效 ⇒ 「类上有注解」<b>不是</b>证据：
 * 若调用是从容器外部进来（代理拦得住）就有效；若是 self-invocation 就<b>静默失效</b>
 * （代码看着有事务、运行时是 auto-commit、异常不回滚、<b>且不报任何错</b>、测试全绿）。
 * ⇒ 本类用<b>真 PG + 真 DB 约束冲突</b>实测。
 *
 * <h2>🔴 为什么不用 Mockito 桩注入失败</h2>
 * <p>桩掉 mapper 方法 ⇒ <b>连真实 SQL 都不发</b> ⇒ 证不了「事务是否真的把写回滚了」。
 * ⇒ 本类用<b>数据库自己的 UNIQUE 约束</b>制造中途失败：一个 {@code BEFORE DELETE} 触发器
 * 在删除时插入一行<b>违反 UNIQUE</b> 的探针 ⇒ PG 抛 <b>23505 unique_violation</b>
 * ⇒ Spring 翻译成真实的 {@link DuplicateKeyException} ⇒ 回滚与否只能由真实事务语义决定。
 *
 * <h2>🔴 判据为什么非空洞（同条件对照 · AGENTS.md §3.3.3）</h2>
 * <p>「回滚了」单独看是空洞断言（万一第二条压根没执行、或第一条压根没写呢？）。
 * ⇒ 本类同时测<b>同一条序列的成功路径</b>：同样的前置数据、同样两条语句、只是不注入失败
 * ⇒ 断言 block 行落库、<b>双向</b>关注行都被删掉
 * ⇒ ⇒ 「第二条会真的删」与「失败时第一条不留痕」同时被证实 ⇒ 事务边界真的存在。
 *
 * <h2>⚠️ 变异证据（在交付时单独跑，见交付报告）</h2>
 * <p>临时摘掉 {@code @Transactional(rollbackFor = Exception.class)} ⇒ 本类的
 * 「block 行必须不存在」判据必须<b>真的变红</b> ⇒ 才算「不是假绿」。
 */
@SpringBootTest(classes = {FollowServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "grpc.server.port=-1"
        })
@ActiveProfiles("test")
class FollowBlockTransactionRealPgTest {

    /** uid 序号器：与其它测试类共用同一个库 ⇒ uid 必须全局唯一。 */
    private static final AtomicLong UID_SEQ = new AtomicLong(8_200_000_000_000L);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        registry.add("grpc.client.user-rpc.address", FakeUserRpcServer::address);
    }

    @Autowired
    private FollowBlockTransactionService txService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================================================================
    // ① 失败注入 ⇒ 真 DB 约束冲突 ⇒ 真回滚
    // ==================================================================

    @Test
    @DisplayName("🔴 P0-1：第二条语句真失败（UNIQUE 冲突）⇒ 第一条语句的 block 行被回滚（不留痕）")
    void failedCleanupRollsBackTheBlockInsert() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();
        insertFollow(userId, targetId);

        installFailingDeleteTrigger();
        try {
            // 🔴 真失败：DELETE 触发器插入违反 UNIQUE 的探针 ⇒ PG 23505 ⇒ DuplicateKeyException
            assertThatThrownBy(() -> txService.createBlockAndCleanup(userId, targetId))
                    .as("第二条语句必须真的失败（真实 DB 约束冲突），否则本用例是空洞的")
                    .isInstanceOf(DuplicateKeyException.class)
                    .isInstanceOf(DataAccessException.class);

            // 🔴 判定 1：第一条语句（INSERT block）必须**不留痕** —— 这才是「回滚」的运行期证据
            assertThat(blockCount(userId, targetId))
                    .as("block 行必须被回滚（事务生效）；若这里还是 1，说明 @Transactional 没起作用")
                    .isZero();

            // 判定 2：第二条语句没有生效，关注行还在
            assertThat(followCount(userId, targetId))
                    .as("DELETE 失败 ⇒ 关注行必须原样还在")
                    .isEqualTo(1);

            // 判定 3：触发器那次「失败的 INSERT」也被回滚 ⇒ 探针表仍只有预置的那 1 行
            assertThat(probeRowCount())
                    .as("触发器里的探针 INSERT 与本事务同生共死 ⇒ 失败后不得留下残行")
                    .isEqualTo(1);
        } finally {
            removeFailingDeleteTrigger();
        }
    }

    // ==================================================================
    // ② 同序列成功路径（判据非空洞的对照组）
    // ==================================================================

    @Test
    @DisplayName("✅ P0-1 对照组：同一条序列成功 ⇒ block 行落库 + 双向关注行都被删掉")
    void successfulCleanupCommitsBothStatements() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();
        insertFollow(userId, targetId);   // 我 → 对方
        insertFollow(targetId, userId);   // 对方 → 我（双向都必须被清）

        txService.createBlockAndCleanup(userId, targetId);

        assertThat(blockCount(userId, targetId))
                .as("第一条语句必须真的落库（否则上面的「回滚」判据就是空洞的）")
                .isEqualTo(1);
        assertThat(followCount(userId, targetId))
                .as("第二条语句必须真的删掉我→对方")
                .isZero();
        assertThat(followCount(targetId, userId))
                .as("第二条语句必须真的删掉对方→我（**双向**删除，四个占位符的顺序不可调换）")
                .isZero();
    }

    @Test
    @DisplayName("✅ P0-1 对照组：重复拉黑幂等（第二条走 ON CONFLICT DO NOTHING，0 行、不报错）")
    void repeatedBlockIsIdempotent() {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();
        insertFollow(userId, targetId);

        txService.createBlockAndCleanup(userId, targetId);
        txService.createBlockAndCleanup(userId, targetId);

        assertThat(blockCount(userId, targetId))
                .as("重复拉黑必须是 1 行（ON CONFLICT DO NOTHING），不是 2 行、也不是报错")
                .isEqualTo(1);
        assertThat(followCount(userId, targetId)).isZero();
    }

    // ==================================================================
    // ③ 装配层证据：事务 advisor 真的包住了这个 bean
    // ==================================================================

    @Test
    @DisplayName("🔴 装配层：事务服务 bean 真的被事务 advisor 包住（否则上面三条全是假绿）")
    void transactionServiceIsProxiedByTransactionAdvisor() {
        // 「Bean 存在」不等于「机制生效」（AGENTS.md §3.3.3）⇒ 断言的是**代理形态**本身。
        assertThat(AopUtils.isAopProxy(txService))
                .as("事务服务必须被 Spring AOP 包住（否则 @Transactional 不生效）")
                .isTrue();
        assertThat(((Advised) txService).getAdvisors())
                .as("advisor 里必须有事务 advisor")
                .anySatisfy(advisor ->
                        assertThat(advisor.getClass().getName()).contains("Transaction"));
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private void insertFollow(long userId, long targetId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO follow_relation (user_id, target_id, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?)",
                userId, targetId, Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));
    }

    private int blockCount(long userId, long targetId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM block_relation WHERE user_id = ? AND target_id = ?",
                Integer.class, userId, targetId);
        return n == null ? 0 : n;
    }

    private int followCount(long userId, long targetId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM follow_relation WHERE user_id = ? AND target_id = ?",
                Integer.class, userId, targetId);
        return n == null ? 0 : n;
    }

    private int probeRowCount() {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM follow_tx_probe", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * 装一个「让 DELETE 真失败」的触发器。
     *
     * <p>⚠️ 失败形态 = <b>真实 UNIQUE 约束冲突</b>（不是 Mockito 桩）：
     * 触发器在 DELETE 之前插入一行 {@code marker=1}，而探针表的 {@code marker} 列有 UNIQUE
     * ⇒ PG 抛 {@code 23505 unique_violation} ⇒ Spring 翻译成 {@link DuplicateKeyException}。
     */
    private void installFailingDeleteTrigger() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS follow_tx_probe "
                + "(id BIGSERIAL PRIMARY KEY, marker BIGINT NOT NULL UNIQUE)");
        jdbcTemplate.execute("DELETE FROM follow_tx_probe");
        jdbcTemplate.execute("INSERT INTO follow_tx_probe (marker) VALUES (1)");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION follow_tx_fail_delete() RETURNS trigger AS $fn$
                BEGIN
                    INSERT INTO follow_tx_probe (marker) VALUES (1);
                    RETURN OLD;
                END;
                $fn$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS follow_tx_fail_delete_trg ON follow_relation");
        jdbcTemplate.execute("CREATE TRIGGER follow_tx_fail_delete_trg BEFORE DELETE ON follow_relation "
                + "FOR EACH ROW EXECUTE FUNCTION follow_tx_fail_delete()");
    }

    private void removeFailingDeleteTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS follow_tx_fail_delete_trg ON follow_relation");
    }

    @AfterEach
    void cleanupTrigger() {
        // 兜底：即使某个用例在断言处炸了，也不把触发器留给后面的测试类。
        removeFailingDeleteTrigger();
    }
}
