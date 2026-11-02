package com.rag.follow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.follow.FollowServiceApplication;
import com.rag.follow.support.FakeUserRpcServer;
import com.rag.follow.support.FollowTestSupport;
import com.rag.follow.support.NacosStubConfiguration;
import com.rag.follow.support.TestPostgres;
import com.rag.follow.support.TestRedis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>follow 域「并发请求」场景的真触发测试</b>（真 Tomcat · 真 PostgreSQL · 真 Redis · 真 user-rpc gRPC）。
 *
 * <h2>🔴 为什么必须有这一类（verification.md §九「对拍必须覆盖」里的「并发请求」）</h2>
 * <p>本域的写路径有<b>三处并发敏感点</b>，而它们<b>只在并发下</b>才暴露：
 * <ol>
 *   <li><b>唯一索引 + {@code ON CONFLICT DO NOTHING}</b>：重复关注必须<b>恰好落 1 行</b>且<b>不报错</b>；</li>
 *   <li><b>拉黑事务</b>（{@code FollowBlockTransactionService#createBlockAndCleanup}）：
 *       「插 block 行 + 删双向 follow 行」是<b>一笔事务</b> ⇒ 并发拉黑同一对时，
 *       block 行仍必须<b>恰好 1 行</b>，且双向 follow 行<b>必须被清干净</b>；</li>
 *   <li><b>错误码不能泄漏</b>：并发下若把 PG 的 {@code DuplicateKeyException} 翻成 5008，
 *       客户端就会看到「有的成功有的失败」⇒ 与 B 侧「幂等不报错」不等价。</li>
 * </ol>
 *
 * <h2>⚠️ 期望值的 B 侧出处（不是推断）</h2>
 * <p>B 侧 {@code follow-rpc/.../mapper/FollowRelationMapper.java:38-47} 与
 * {@code BlockRelationMapper.java:43-51} 的 SQL 与 C 侧<b>逐字一致</b>：
 * <pre>
 * INSERT INTO follow_relation (user_id, target_id, create_time, update_time)
 * VALUES (?, ?, ?, ?) ON CONFLICT (user_id, target_id) DO NOTHING
 * </pre>
 * ⇒ B 侧 javadoc 自述「重复关注<b>不报错</b>，返回 0 行受影响，Go 侧同样 {@code Error == nil}
 * ⇒ logic 层照常返回 SUCCESS」⇒ ⇒ **并发下的期望 = 恰好 1 行 + 全部 SUCCESS**。
 *
 * <h2>🔴 本类带<b>变异证据</b>（否则前两条就是空洞断言）</h2>
 * <p>第 3 条把 {@code uk_follow_pair} 唯一索引<b>真的删掉</b> ⇒ {@code ON CONFLICT} 的冲突目标
 * 不存在 ⇒ PG 直接报「there is no unique or exclusion constraint matching the ON CONFLICT
 * specification」⇒ 领域层翻成 <b>5008</b> ⇒ ⇒ <b>并发下必然出现非 200 的业务码</b>。
 * ⇒ ⇒ 由此证明「并发 + 有索引 ⇒ 全部 SUCCESS + 恰好 1 行」这条判据<b>真的在判并发语义</b>，
 * 而不是「跑完没报错」。
 *
 * <h2>测试基础设施</h2>
 * <ul>
 *   <li>真 PG（Testcontainers {@code postgres:16-alpine}）· 真 Redis（{@code redis:7-alpine}）· 真 Tomcat；</li>
 *   <li>出向 user-rpc 走<b>真 gRPC</b>（{@link FakeUserRpcServer}）⇒ 存在性校验是真的；</li>
 *   <li>并发用 {@link ExecutorService} + {@link CountDownLatch} 让所有请求<b>同时起跑</b>
 *       （不是「依次发 N 次」—— 那测不到并发）；</li>
 *   <li>⚠️ 所有并发调用都有<b>超时上限</b> ⇒ 免得挂死时整轮卡住。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {FollowServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "grpc.server.port=-1"
        })
@ActiveProfiles("test")
class FollowConcurrencyRealPgTest {

    /** uid 序号器：与其它测试类共用同一个库 ⇒ uid 必须全局唯一。 */
    private static final AtomicLong UID_SEQ = new AtomicLong(7_300_000_000_000L);

    /** 并发度：8 ⇒ 足以让唯一索引与事务真正撞上，又不至于打爆连接池。 */
    private static final int CONCURRENCY = 8;

    /** 单轮并发等待上限（秒）。 */
    private static final int TIMEOUT_SECONDS = 60;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
        registry.add("grpc.client.user-rpc.address", FakeUserRpcServer::address);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================================================================
    // 1. 并发重复关注
    // ==================================================================

    @Test
    @DisplayName("🔴 并发重复关注 ⇒ 恰好 1 行 + 全部 SUCCESS（B 侧：ON CONFLICT DO NOTHING ⇒ 不报错）")
    void concurrentFollowKeepsExactlyOneRowAndAllSucceed() throws Exception {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();

        List<Integer> codes = fireConcurrently("/follow/v1/follow", userId, targetId);

        assertThat(codes)
                .as("并发重复关注：每个响应都必须是业务成功（code=200），"
                        + "出现 5008 就说明 DuplicateKeyException 泄漏成了 DB 失败")
                .containsOnly(200);

        assertThat(followRowCount(userId, targetId))
                .as("并发重复关注：follow_relation 必须恰好 1 行（唯一索引 + ON CONFLICT 的物理效果）")
                .isEqualTo(1);
    }

    // ==================================================================
    // 2. 并发拉黑（事务：插 block + 删双向 follow）
    // ==================================================================

    @Test
    @DisplayName("🔴 并发拉黑 ⇒ block 恰好 1 行 + 双向 follow 行被清干净 + 全部 SUCCESS")
    void concurrentBlockKeepsOneRowAndCleansBothDirections() throws Exception {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();

        // 前置：双向都已有关注 ⇒ 拉黑事务必须把它们清掉
        jdbcTemplate.update("""
                INSERT INTO follow_relation (user_id, target_id, create_time, update_time)
                VALUES (?, ?, now(), now()), (?, ?, now(), now())
                """, userId, targetId, targetId, userId);
        assertThat(followRowCount(userId, targetId)).isEqualTo(1);
        assertThat(followRowCount(targetId, userId)).isEqualTo(1);

        List<Integer> codes = fireConcurrently("/follow/v1/block", userId, targetId);

        assertThat(codes)
                .as("并发拉黑：每个响应都必须是业务成功（code=200）")
                .containsOnly(200);

        assertThat(blockRowCount(userId, targetId))
                .as("并发拉黑：block_relation 必须恰好 1 行")
                .isEqualTo(1);

        assertThat(followRowCount(userId, targetId))
                .as("并发拉黑：事务内的双向清理必须把关注行清干净")
                .isZero();
        assertThat(followRowCount(targetId, userId))
                .as("并发拉黑：事务内的双向清理必须把反向关注行也清干净")
                .isZero();
    }

    // ==================================================================
    // 3. 变异证据（证明前两条不是空洞断言）
    // ==================================================================

    @Test
    @DisplayName("🔴 变异证据：删掉 uk_follow_pair ⇒ 并发关注不再静默成功（必然出现 5008）")
    void concurrentFollowWithoutUniqueIndexLeaksDatabaseError() throws Exception {
        long userId = UID_SEQ.incrementAndGet();
        long targetId = UID_SEQ.incrementAndGet();

        // 变异：删掉唯一索引 ⇒ ON CONFLICT 的冲突目标不存在 ⇒ PG 直接报错
        jdbcTemplate.execute("DROP INDEX IF EXISTS uk_follow_pair");
        try {
            List<Integer> codes = fireConcurrently("/follow/v1/follow", userId, targetId);

            assertThat(codes)
                    .as("没有唯一索引 ⇒ 并发关注必然不再「全部成功」"
                            + "（否则说明上面两条根本没在判并发语义）")
                    .contains(5008);

            assertThat(codes)
                    .as("5008 是领域层对 DB 写入失败的映射（ERROR_DB_UPDATE）")
                    .allMatch(code -> code == 5008);
        } finally {
            // 复原（不留下变异 ⇒ 不污染其它测试类）
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_follow_pair "
                    + "ON follow_relation (user_id, target_id)");
        }

        // 复原后幂等性回来 ⇒ 证明变异确实由「索引在不在」决定
        List<Integer> afterRestore = fireConcurrently("/follow/v1/follow", userId, targetId);
        assertThat(afterRestore).containsOnly(200);
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 让 {@link #CONCURRENCY} 个请求<b>同时起跑</b>并收集各自的业务码。
     *
     * <p>⚠️ 用 {@link CountDownLatch} 做「同时起跑」闸门 —— 若改成「依次发 N 次」，
     * 就<b>根本撞不上</b>唯一索引 ⇒ 测的就不是并发了。
     */
    private List<Integer> fireConcurrently(String path, long userId, long targetId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(CONCURRENCY);
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                futures.add(pool.submit((Callable<Integer>) () -> {
                    try {
                        startGate.await();
                        return businessCode(path, userId, targetId);
                    } finally {
                        doneGate.countDown();
                    }
                }));
            }
            // 全部线程就位后一起放行
            startGate.countDown();
            assertThat(doneGate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("并发请求必须在 %d 秒内全部返回（否则说明有死锁/超时）", TIMEOUT_SECONDS)
                    .isTrue();

            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : futures) {
                codes.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return codes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 发一个请求并取回业务码（HTTP 恒 200 ⇒ 只看 body 里的 code）。 */
    private int businessCode(String path, long userId, long targetId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", FollowTestSupport.bearer(userId));
        HttpEntity<String> body = new HttpEntity<>("{\"target_id\":" + targetId + "}", headers);

        ResponseEntity<String> resp = rest.exchange(path, HttpMethod.POST, body, String.class);
        assertThat(resp.getStatusCode().value())
                .as("业务失败也应是 HTTP 200（B 侧注册了错误处理器）")
                .isEqualTo(200);
        try {
            JsonNode parsed = MAPPER.readTree(resp.getBody());
            return parsed.get("code").asInt();
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON: [" + resp.getBody() + "]", e);
        }
    }

    private int followRowCount(long userId, long targetId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM follow_relation WHERE user_id = ? AND target_id = ?",
                Integer.class, userId, targetId);
        return n == null ? 0 : n;
    }

    private int blockRowCount(long userId, long targetId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM block_relation WHERE user_id = ? AND target_id = ?",
                Integer.class, userId, targetId);
        return n == null ? 0 : n;
    }
}
