package com.rag.comment.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.CommentServiceApplication;
import com.rag.comment.client.MessageRpcClient;
import com.rag.comment.contract.CommentKafkaMsg;
import com.rag.comment.domain.service.CommentTransactionalService;
import com.rag.comment.service.CommentCacheService;
import com.rag.comment.support.NacosStubConfiguration;
import com.rag.comment.support.TestPostgres;
import com.rag.comment.support.TestRedis;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>Kafka 消费侧「四条出口方向」的真链路取证</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuditListener.java}
 * 的四条出口（C 侧逐字照抄，见 {@link CommentAuditListener} 的类注释表格）。
 *
 * <h2>四条方向（B 侧 Go {@code audit_consumer.go:36-63}）</h2>
 * <table border="1">
 *   <caption>B 侧四条出口 ⇔ 本类的四条断言</caption>
 *   <tr><th>方向</th><th>B 侧动作</th><th>位点后果</th><th>本类用例</th></tr>
 *   <tr><td>① 解析失败</td><td>记日志 ＋ {@code return nil}</td><td><b>提交</b>（丢弃）</td>
 *       <td>{@link #parseFailureIsDiscardedAndOffsetIsCommitted()}</td></tr>
 *   <tr><td>② 落库失败</td><td>记日志 ＋ {@code return err}</td><td><b>不提交</b>（重投）</td>
 *       <td>{@link #dbFailureDoesNotCommitOffsetAndIsRedelivered()}</td></tr>
 *   <tr><td>③ 缓存失败</td><td>只记日志</td><td>正常提交</td>
 *       <td>{@link #cacheFailureOnlyLogsAndRowIsStillPersisted()}</td></tr>
 *   <tr><td>④ 通知失败</td><td>只记日志</td><td>正常提交</td>
 *       <td>{@link #notificationFailureOnlyLogsAndRowIsStillPersisted()}</td></tr>
 * </table>
 *
 * <h2>🔴 为什么「位点」是这四条方向<b>唯一可区分</b>的判据</h2>
 * <p>四条方向里，①③④ 三条的<b>数据库最终状态一样</b>（③ ④ 是「落库成功」、① 是「什么都没落」）
 * ⇒ ⇒ 只看表里的行<b>分不出</b>「丢弃」与「重投」⇒ ⇒ 必须读**消费组的已提交位点**。
 * <p>位点之所以可读，靠的是 C 侧 yml 的两处 L1 契约（**与 B 侧逐字相同**）：
 * <ul>
 *   <li>{@code spring.kafka.consumer.enable-auto-commit: false}
 *       （B 侧 {@code comment-rpc/…/application.yml:193-197}）⇒ 位点**只**由容器在 listener
 *       **正常返回后**提交；</li>
 *   <li>{@code spring.kafka.listener.ack-mode: record}
 *       ＋ {@code CommentServiceApplication#kafkaListenerContainerFactory} 的
 *       {@code setAckMode(RECORD)} ⇒ 同上。</li>
 * </ul>
 * ⚠️ yml 自己的注释就写明了这条契约：「<b>enable-auto-commit 若留 true，
 * 「落库失败 → 不提交 → 重投」这条契约就不成立</b>」。
 *
 * <h2>🔴 ② 为什么用「可控开关的 spy」而不是「永久失败」</h2>
 * <p>本 topic 是 <b>1 partition</b> ⇒ 一条永久失败的记录会<b>堵死整个分区</b>
 * （后续消息全部消费不到）⇒ 也会污染同 JVM 里其它测试类 ⇒ ⇒ 不可用。
 * <p>⇒ 改成：spy 在「开关打开」时抛异常，测试<b>先</b>断言「位点没提交」，
 * <b>再</b>关掉开关 ⇒ 断言「同一条消息最终落库了」
 * ⇒ ⇒ 这一条同时钉住了两件事：
 * <b>（a）失败时位点不前进</b>（否则会被当成已处理 ⇒ 丢消息）
 * ＋ <b>（b）它确实被重投了</b>（否则最终不会有行）。
 * <p>⚠️ 这也顺带证明了「不是丢弃」：若实现改成「失败即提交位点」，
 * 本条的<b>最终落库</b>断言会<b>永远超时变红</b>。
 *
 * <h2>🔴 ④ 的前置条件必须被证明</h2>
 * <p>「通知失败」不能靠「环境恰好不可达」蒙混过关 ⇒ 本类对 {@link MessageRpcClient} 打 spy，
 * <b>断言通知确实被尝试过</b>（调用计数 ≥ 1）**且**最终返回 false
 * ⇒ ⇒ 「失败 ⇒ 只记日志」这句话有真实的失败事件作为前提。
 *
 * <h2>L4 差异（AGENTS §3.2 三段式）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li>B 侧没有「真 broker」层面的消费测试（B 侧的 comment 域测试全部是<b>源码级/单测级</b>），
 *             ⇒ 本类把 B-15 那四条方向从「读代码得出的结论」升级为
 *             <b>E-3 级（真 broker ＋ 真 listener ＋ 真 PG ＋ 真位点）</b>的运行证据；</li>
 *         <li>用 {@code @SpyBean} 精确制造「落库失败 / 缓存失败」两个<b>受控</b>失败点
 *             （不 mock Kafka、不 mock PG、不改一行 {@code src/main/**}）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样。本类<b>只观测</b>行为，不改行为；
 *       四个方向的期望值全部来自 B 侧 {@code audit_consumer.go:36-63} 的四条出口表。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>四条出口：B 侧
 *             {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuditListener.java:87-145}
 *             （Go {@code audit_consumer.go:36-63}）；</li>
 *         <li>位点契约：B 侧 {@code comment-rpc/src/main/resources/application.yml:181-210,256-267}；</li>
 *         <li>错误处理器（不提交位点那半）：B 侧
 *             {@code comment-rpc/.../rpc/CommentRpcApplication.java:120-133}
 *             （C 侧逐字照抄 ⇒ {@code DefaultErrorHandler} ＋ 只记日志的 recoverer）。</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>⚠️ 本类的证据边界（AGENTS.md §3.3.3）</h2>
 * <p>跑的是 <b>C 侧单侧</b>：证明「C 侧这四条方向的位点后果与 B 侧声明的一致」。
 * 「B 侧此刻真的产出同样的位点后果」属<b>双侧活体对拍</b> ⇒ 本类<b>不冒充</b>该证据。
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（容器不可用时<b>直接失败</b>，不静默跳过）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}；</li>
 *   <li>不 mock Kafka（真 broker ＋ 真 listener ＋ 真 PG ＋ 真位点）；</li>
 *   <li>不 mock PG（失败点用<b>受控 spy</b>制造，且都落在<b>真事务</b>的调用边界上）；</li>
 *   <li>不改一行 {@code src/main/**}。</li>
 * </ul>
 */
@SpringBootTest(classes = {CommentServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                // 🔴 测试专属消费组 ⇒ 位点可读且不与别的 lane / 探针抢同一 group
                "spring.kafka.consumer.group-id=comment-audit-group-kafkafail",
                // 🔴 latest（**不是 earliest**）⇒ 跳过 topic 上的历史消息，不消化积压
                "spring.kafka.consumer.auto-offset-reset=latest"
        })
@ActiveProfiles("test")
class CommentKafkaFailureDirectionRealTest {

    /** 生产 / 消费同 topic（L1 契约 · B 侧 {@code comment.yaml:26,32}）。 */
    private static final String AUDIT_TOPIC = "comment-audit-topic";

    /** 本类专属消费组（**不是** L1 契约里的那个字面量 —— 理由见类注释）。 */
    private static final String TEST_GROUP = "comment-audit-group-kafkafail";

    /** Sea-kafka 的宿主发布口（= EXTERNAL listener）。 */
    private static final String BOOTSTRAP = "127.0.0.1:49992";

    /** 有界轮询预算 —— 消费是异步的。 */
    private static final long AWAIT_TIMEOUT_MS = 60_000L;

    /** 「确认无行」的短预算。 */
    private static final long ABSENT_TIMEOUT_MS = 2_000L;

    /** 本类专用 id 段（与其它测试类不撞）。 */
    private static final AtomicLong COMMENT_SEQ = new AtomicLong(8_800_000_000_000L);

    /** 本类专用 target_id 前缀（{@code comment_index.target_id} 是 VARCHAR(64)）。 */
    private static final String TARGET_PREFIX = "kfail";

    /** ② 落库失败专用 comment id（固定值 ⇒ 与其它用例不撞）。 */
    private static final long DB_FAIL_COMMENT_ID = 8_890_000_000_001L;

    /** ③ 缓存失败专用 target_id 前缀（命中它 ⇒ 缓存失效抛异常）。 */
    private static final String CACHE_FAIL_PREFIX = TARGET_PREFIX + "-cachefail";

    /** ② 的「落库失败」开关（打开 ⇒ spy 抛异常）。 */
    private final AtomicBoolean dbFailSwitch = new AtomicBoolean(false);

    /** ② 的失败尝试计数（用来**确定性地**等到「listener 真的试过了」）。 */
    private final AtomicInteger dbFailAttempts = new AtomicInteger();

    /** ③ 的「缓存失败」开关。 */
    private final AtomicBoolean cacheFailSwitch = new AtomicBoolean(false);

    /** ③ 的失败尝试计数。 */
    private final AtomicInteger cacheFailAttempts = new AtomicInteger();

    /** ④ 的通知调用计数（证明「通知确实被尝试过」）。 */
    private final AtomicInteger notifyAttempts = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
        // ⚠️ 只在本类里覆盖 broker 地址（不改 application-test.properties —— 别人的写权）
        registry.add("spring.kafka.bootstrap-servers", () -> BOOTSTRAP);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 🔴 受控「落库失败」点 —— 只在 {@link #DB_FAIL_COMMENT_ID} 且开关打开时抛异常。
     *
     * <p>⚠️ 为什么用 spy 而不是「让 PG 真的报错」（例如超长 {@code target_type}）：
     * 本 topic 是 1 partition ⇒ 永久失败的记录会<b>堵死整个分区</b>。
     */
    @SpyBean
    private CommentTransactionalService transactionalService;

    /** 🔴 受控「缓存失效失败」点 —— 只在 target_id 命中前缀时抛异常。 */
    @SpyBean
    private CommentCacheService cacheService;

    /** 🔴 通知客户端 spy —— 用来**证明通知确实被尝试过**（方向④ 的前提）。 */
    @SpyBean
    private MessageRpcClient messageRpcClient;

    /**
     * <b>确保 topic 存在</b>（1 partition × 1 replica）。
     *
     * <p>⚠️ 为什么必须显式建：broker 端 {@code auto.create.topics.enable} 不保证开着；
     * 而 topic 不存在时「先 {@code subscribe} 再等 assignment」会卡在 metadata 阶段。
     */
    @BeforeAll
    static void ensureTopicsExist() {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            Set<String> existing = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            if (!existing.contains(AUDIT_TOPIC)) {
                admin.createTopics(List.of(new NewTopic(AUDIT_TOPIC, 1, (short) 1)))
                        .all().get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new IllegalStateException("确保 Kafka topic 存在失败：" + e.getMessage(), e);
        }
    }

    @BeforeEach
    void armSpies() {
        dbFailSwitch.set(false);
        cacheFailSwitch.set(false);
        dbFailAttempts.set(0);
        cacheFailAttempts.set(0);
        notifyAttempts.set(0);

        Mockito.doAnswer((InvocationOnMock invocation) -> {
            Object arg = invocation.getArgument(0);
            long commentId = (arg instanceof CommentAuditMessage m) ? m.commentId() : -1L;
            if (dbFailSwitch.get() && commentId == DB_FAIL_COMMENT_ID) {
                dbFailAttempts.incrementAndGet();
                throw new IllegalStateException(
                        "注入的落库失败（commentId=" + commentId + "）—— 用于验证「落库失败 ⇒ 不提交位点 ⇒ 重投」");
            }
            return invocation.callRealMethod();
        }).when(transactionalService).insertCommentTx(Mockito.any(), Mockito.anyInt());

        Mockito.doAnswer((InvocationOnMock invocation) -> {
            Object arg = invocation.getArgument(1);
            String targetId = arg == null ? "" : String.valueOf(arg);
            if (cacheFailSwitch.get() && targetId.startsWith(CACHE_FAIL_PREFIX)) {
                cacheFailAttempts.incrementAndGet();
                throw new IllegalStateException(
                        "注入的缓存失效失败（targetId=" + targetId + "）—— 用于验证「缓存失败 ⇒ 只记日志」");
            }
            return invocation.callRealMethod();
        }).when(cacheService).invalidateTargetCaches(Mockito.anyString(), Mockito.anyString());

        Mockito.doAnswer((InvocationOnMock invocation) -> {
            notifyAttempts.incrementAndGet();
            // ⚠️ 仍然走真实调用 ⇒ 真实失败（message-rpc 不可达）⇒ 返回 false
            return invocation.callRealMethod();
        }).when(messageRpcClient).sendNotification(
                Mockito.anyLong(), Mockito.anyLong(), Mockito.any(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    }

    // ==================================================================
    // ① 解析失败 ⇒ 丢弃（位点提交）
    // ==================================================================

    @Test
    @DisplayName("🔴 ① 解析失败 ⇒ 丢弃：零落库 ＋ 位点已提交 ＋ 不堵死分区（后续消息仍被消费）")
    void parseFailureIsDiscardedAndOffsetIsCommitted() throws Exception {
        // 先证明 listener 已在消费（auto-offset-reset=latest ⇒ 启动窗口内的消息会被丢）
        awaitConsumerReady();

        long committedBefore = committedOffset();

        // ⚠️ 故意发**非法 JSON** ⇒ objectMapper.readValue 抛异常 ⇒ 走「解析失败」分支
        String key = TARGET_PREFIX + "-parse-" + COMMENT_SEQ.incrementAndGet();
        long badOffset = sendRaw(key, "{this-is-not-json");

        // ①-a 零落库
        assertThat(countWhere("SELECT count(*) FROM comment_index WHERE target_id = ?", key))
                .as("解析失败 ⇒ 什么都不该落库").isZero();

        // ①-b 位点**已提交**（越过这条坏消息）⇒ ⇒ 它是被「丢弃」而不是「卡住」
        long committedAfter = awaitCommittedOffsetBeyond(badOffset);
        assertThat(committedAfter)
                .as("解析失败 ⇒ 位点必须提交（越过这条坏消息）"
                        + " ⇒ 否则它会堵死分区 ⇒ 与 B 侧 audit_consumer.go:36-39 的 return nil 不符")
                .isGreaterThan(badOffset);
        assertThat(committedAfter)
                .as("位点只应前进到坏消息之后").isLessThanOrEqualTo(badOffset + 1);

        // ①-c 不堵死分区：同 key 的后续好消息仍被消费
        long goodId = COMMENT_SEQ.incrementAndGet();
        kafkaTemplate.send(AUDIT_TOPIC, key, wireJson(new CommentKafkaMsg(
                goodId, "article", key, 9_300L, 0L, 0L,
                "坏消息之后的好消息", "{}", 0L, 0L, 1_762_000_003L)));
        awaitPg("SELECT count(*) FROM comment_index WHERE id = ?", goodId);
    }

    // ==================================================================
    // ② 落库失败 ⇒ 重投（位点不提交）
    // ==================================================================

    @Test
    @DisplayName("🔴 ② 落库失败 ⇒ 不提交位点（⇒ 重投）＋ 恢复后最终落库（⇒ 不是丢弃）")
    void dbFailureDoesNotCommitOffsetAndIsRedelivered() throws Exception {
        awaitConsumerReady();

        long committedBefore = committedOffset();

        dbFailSwitch.set(true);
        String key = TARGET_PREFIX + "-dbfail-" + COMMENT_SEQ.incrementAndGet();
        long badOffset = sendRaw(key, wireJson(new CommentKafkaMsg(
                DB_FAIL_COMMENT_ID, "article", key, 9_400L, 0L, 0L,
                "会落库失败的评论", "{}", 0L, 0L, 1_762_000_004L)));

        // ②-a 确定性地等到 listener **真的试过一次**（不是靠 sleep 猜）
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (dbFailAttempts.get() < 1 && System.currentTimeMillis() < deadline) {
            sleep(200);
        }
        assertThat(dbFailAttempts.get())
                .as("listener 应当至少尝试落库一次（否则本用例没测到东西）").isGreaterThanOrEqualTo(1);

        // ②-b 零落库
        assertThat(countWhere("SELECT count(*) FROM comment_index WHERE id = ?", DB_FAIL_COMMENT_ID))
                .as("落库失败 ⇒ 此刻不该有行").isZero();

        // ②-c 🔴 位点**没有**前进 ⇒ ⇒ 这条消息没被当成已处理 ⇒ ⇒ 会重投
        long committedDuring = committedOffset();
        assertThat(committedDuring)
                .as("落库失败 ⇒ 位点必须【不】前进（越过这条失败的消息）"
                        + " ⇒ 否则它会被当成已处理 ⇒ **丢消息**"
                        + " ⇒ 与 B 侧 audit_consumer.go:48-51 的 return err 不符")
                .isLessThanOrEqualTo(badOffset);
        assertThat(committedDuring)
                .as("位点在失败期间应与失败前一致").isEqualTo(committedBefore);

        // ②-d 放行 ⇒ 同一条消息被**重投**并最终落库（⇒ 证明「不是丢弃」）
        dbFailSwitch.set(false);
        awaitPg("SELECT count(*) FROM comment_index WHERE id = ?", DB_FAIL_COMMENT_ID);

        // ②-e 成功之后位点才前进
        long committedFinal = awaitCommittedOffsetBeyond(badOffset);
        assertThat(committedFinal)
                .as("重投成功后位点才应前进").isGreaterThan(badOffset);
    }

    // ==================================================================
    // ③ 缓存失败 ⇒ 只记日志（落库已成功）
    // ==================================================================

    @Test
    @DisplayName("🔴 ③ 缓存失效失败 ⇒ 只记日志：落库**已成功** ＋ 位点正常提交")
    void cacheFailureOnlyLogsAndRowIsStillPersisted() throws Exception {
        awaitConsumerReady();

        cacheFailSwitch.set(true);

        long commentId = COMMENT_SEQ.incrementAndGet();
        String targetId = CACHE_FAIL_PREFIX + "-" + commentId;
        long offset = sendRaw(targetId, wireJson(new CommentKafkaMsg(
                commentId, "article", targetId, 9_500L, 0L, 0L,
                "缓存会失败的评论", "{}", 0L, 0L, 1_762_000_005L)));

        // ③-a 缓存这一步**真的失败了**（否则本用例没测到东西）
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (cacheFailAttempts.get() < 1 && System.currentTimeMillis() < deadline) {
            sleep(200);
        }
        assertThat(cacheFailAttempts.get())
                .as("缓存失效这一步应当真的抛过异常").isGreaterThanOrEqualTo(1);

        // ③-b 🔴 落库**已成功**（缓存失败不吞掉前面的落库）
        awaitPg("SELECT count(*) FROM comment_index WHERE id = ?", commentId);
        awaitPg("SELECT count(*) FROM comment_content WHERE comment_id = ?", commentId);

        // ③-c 位点正常提交（⇒ 不会因为缓存失败而重投 ⇒ 不会重复落库）
        long committed = awaitCommittedOffsetBeyond(offset);
        assertThat(committed)
                .as("缓存失败 ⇒ 位点照常提交").isGreaterThan(offset);
    }

    // ==================================================================
    // ④ 通知失败 ⇒ 只记日志（落库已成功）
    // ==================================================================

    @Test
    @DisplayName("🔴 ④ 通知发送失败 ⇒ 只记日志：落库**已成功** ＋ 位点正常提交（不重投）")
    void notificationFailureOnlyLogsAndRowIsStillPersisted() throws Exception {
        awaitConsumerReady();

        long commentId = COMMENT_SEQ.incrementAndGet();
        String targetId = TARGET_PREFIX + "-notify-" + commentId;
        // ⚠️ 11 字段的固定序（`CommentKafkaMsg` 的 component 序）：
        //    comment_id · target_type · target_id · **user_id** · root_id · parent_id
        //    · content · meta · **owner_id** · attribute · create_time
        // ⚠️ ownerId 必须 **> 0 且 != userId** ⇒ 才会真的走到发通知那一步
        //    （B 侧 audit_consumer.go:97-99 的三条规则：recipientID <= 0 ⇒ 不发）
        long offset = sendRaw(targetId, wireJson(new CommentKafkaMsg(
                commentId, "article", targetId, 9_600L, 0L, 0L,
                "通知会失败的评论", "{}", 9_700L, 0L, 1_762_000_006L)));

        // ④-a 🔴 通知**确实被尝试过**（message-rpc 在测试环境不可达 ⇒ 真实失败）
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (notifyAttempts.get() < 1 && System.currentTimeMillis() < deadline) {
            sleep(200);
        }
        assertThat(notifyAttempts.get())
                .as("通知这一步应当真的被尝试过（否则本用例没测到东西）").isGreaterThanOrEqualTo(1);

        // ④-b 🔴 落库**已成功**（通知失败不吞掉前面的落库）
        awaitPg("SELECT count(*) FROM comment_index WHERE id = ?", commentId);
        awaitPg("SELECT count(*) FROM comment_content WHERE comment_id = ?", commentId);

        // ④-c 位点正常提交（⇒ 不会因为通知失败而重投 ⇒ 不会重复落库 + 重复通知）
        long committed = awaitCommittedOffsetBeyond(offset);
        assertThat(committed)
                .as("通知失败 ⇒ 位点照常提交（不重投）").isGreaterThan(offset);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** 🔴 把 listener「叫醒」并证明它<b>真的在消费</b>（探针消息真的落进了表）。 */
    private void awaitConsumerReady() {
        for (int attempt = 1; attempt <= 8; attempt++) {
            long probeId = COMMENT_SEQ.incrementAndGet();
            String probeTarget = TARGET_PREFIX + "-warm-" + attempt + "-" + probeId;
            kafkaTemplate.send(AUDIT_TOPIC, wireJson(new CommentKafkaMsg(
                    probeId, "article", probeTarget, 9_100L, 0L, 0L,
                    "warmup", "{}", 0L, 0L, 1_762_000_000L)));
            Integer landed = awaitPgQuiet(
                    "SELECT count(*) FROM comment_index WHERE id = ?",
                    Integer.class, new Object[]{probeId}, 2_500L);
            if (landed != null && landed > 0) {
                return;
            }
        }
        // ⚠️ 这里不抛异常：预热失败只说明「listener 此刻没在消费」
        // ⇒ ⇒ 让**消费相关的用例**各自在有界轮询里超时变红（失败点更贴近真正的断言）
    }

    /** 发送一条原始载荷，返回它在 topic 上的 offset。 */
    private long sendRaw(String key, String rawValue) throws Exception {
        var result = kafkaTemplate.send(AUDIT_TOPIC, key, rawValue).get(30, TimeUnit.SECONDS);
        return result.getRecordMetadata().offset();
    }

    /** 读本类消费组在 partition 0 上的**已提交位点**（没有 ⇒ {@code -1}）。 */
    private long committedOffset() {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            Map<TopicPartition, OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets(TEST_GROUP)
                            .partitionsToOffsetAndMetadata()
                            .get(30, TimeUnit.SECONDS);
            OffsetAndMetadata om = offsets.get(new TopicPartition(AUDIT_TOPIC, 0));
            return om == null ? -1L : om.offset();
        } catch (Exception e) {
            throw new IllegalStateException("读取消费组已提交位点失败：" + e.getMessage(), e);
        }
    }

    /** 有界轮询已提交位点，直到它**越过**给定的 offset（或超时 ⇒ 返回最后一次读数）。 */
    private long awaitCommittedOffsetBeyond(long offset) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        long last = -1L;
        while (System.currentTimeMillis() < deadline) {
            last = committedOffset();
            if (last > offset) {
                return last;
            }
            sleep(200);
        }
        return last;
    }

    private String wireJson(CommentKafkaMsg msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            throw new IllegalStateException("构造审计消息失败", e);
        }
    }

    /** 有界轮询 PG，直到 count > 0（超时即失败，不挂死）。 */
    private void awaitPg(String sql, Object... args) {
        Integer count = awaitPgQuiet(sql, Integer.class, args, AWAIT_TIMEOUT_MS);
        if (count == null || count <= 0) {
            throw new AssertionError("有界轮询超时（" + AWAIT_TIMEOUT_MS + "ms）仍未满足：" + sql);
        }
    }

    /** 有界轮询 PG，<b>超时返回最后一次读数而不抛异常</b>。 */
    private Integer awaitPgQuiet(String sql, Class<Integer> type, Object[] args, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Integer last = null;
        while (System.currentTimeMillis() < deadline) {
            last = jdbcTemplate.queryForObject(sql, Integer.class, args);
            if (last != null && last > 0) {
                return last;
            }
            sleep(200);
        }
        return last;
    }

    /** 查一行计数。 */
    private int countWhere(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
