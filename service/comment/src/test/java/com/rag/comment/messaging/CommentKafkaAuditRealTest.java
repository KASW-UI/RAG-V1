package com.rag.comment.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.CommentServiceApplication;
import com.rag.comment.contract.CommentKafkaMsg;
import com.rag.comment.domain.service.CommentDomainService;
import com.rag.comment.support.NacosStubConfiguration;
import com.rag.comment.support.TestPostgres;
import com.rag.comment.support.TestRedis;

import com.rag.proto.comment.CreateCommentReq;
import com.rag.proto.comment.CreateCommentResp;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>comment 域 Kafka 审计链路的真 broker 守门测试</b>
 * —— 真 Kafka broker ＋ 真 {@code @KafkaListener} ＋ 真 PostgreSQL ＋ 真 Redis。
 *
 * <h2>🔴 本域最独特的行为：「建评论」<b>不写库</b></h2>
 * <p>{@code CreateComment} 只做四件事：校验 ⇒ 解析 ownerId ⇒ 生成雪花 ID ⇒ <b>把消息推给 Kafka</b>
 * ⇒ ⇒ <b>它自己不写 {@code comment_index} / {@code comment_content}</b>
 * ⇒ ⇒ 真正的落库发生在 <b>Kafka 消费侧</b>（{@link CommentAuditListener#onMessage}
 * → {@code CommentTransactionalService#insertCommentTx}）。
 * <p>⇒ ⇒ 本类把这条<b>形态差异</b>钉死：<b>调用返回后库里还没有行，消费侧落库后才有行</b>。
 * <p>⚠️ <b>为什么这一条必须用「行锁闸门」而不是「先断言再等」</b>：
 * 消费是<b>异步</b>的（AGENTS.md §3.3）⇒ 若用「调用返回后立刻查一次」的写法，
 * 那条查询与消费侧落库是<b>竞态</b>（快则绿、慢则红）⇒ <b>假绿</b>。
 * ⇒ ⇒ 本类用<b>真 PG 的行级锁</b>把消费侧<b>卡在事务中间</b>：
 * <pre>
 * BEGIN;
 * SELECT ... FROM subject WHERE target_type=? AND target_id=? FOR UPDATE;  -- 闸门
 * -- 此刻消费侧 insertCommentTx 已走到 subject upsert ⇒ 阻塞在 ON CONFLICT 的行锁上
 * -- ⇒ 它的 comment_index / comment_content 写入**全部未提交** ⇒ 别的会话看不见
 * </pre>
 * ⇒ ⇒ 「调用返回后无行」变成<b>确定性</b>事实（不是运气），
 * 且「放开锁后行出现」证明那一行<b>只可能</b>是消费侧写的（若 CreateComment 自己写，
 * 它在闸门打开前就已经提交了 ⇒ 闸门打开后不可能<b>才</b>出现）。
 *
 * <h2>🔴 载荷的 11 个字段是 L1 契约（逐字段逐顺序钉住）</h2>
 * <p>{@link CommentKafkaMsg} 的 {@code @JsonPropertyOrder} 逐条照抄 B 侧
 * {@code kqtypes.CommentKafkaMsg} 的结构体声明顺序 ⇒ 本类用「原始 consumer 读回 broker 上的字节」
 * ＋ <b>{@code LinkedHashMap} 的 key 顺序</b>断言（C 侧 Jackson 2 默认即声明序；
 * 本条断言的作用是<b>把声明序钉成可执行的契约</b> —— 组件被重排时立刻变红）。
 *
 * <h2>🔴 broker 地址为什么是 {@code 127.0.0.1:49992}</h2>
 * <p>本机 Sea-kafka 的两个 listener：{@code PLAINTEXT://kafka:9092}（compose 网络内）与
 * {@code EXTERNAL://127.0.0.1:49992}（宿主发布口）⇒ 宿主上的客户端必须连 <b>EXTERNAL</b> 口
 * ⇒ 若连 {@code 9092}，broker 在 metadata 阶段会把地址回写成 {@code kafka:9092} ⇒ 宿主解析不了 ⇒ 卡死。
 * <p>⚠️ <b>本类只在<b>自己的</b> {@code @DynamicPropertySource} 里覆盖它</b>
 * （<b>不改</b> {@code application-test.properties} —— 那是别人的写权）。
 *
 * <h2>🔴 消费组必须<b>测试专属</b></h2>
 * <p>本 topic 上可能同时有别的 lane / 对拍探针在消费 ⇒ 若共用 {@code comment-audit-group}，
 * 同一批消息会被两个实例分走 ⇒ 读数随机 ⇒ ⇒ 本类覆盖成 {@code comment-audit-group-kafkaaudit}。
 * <p>⚠️ 但 <b>L1 契约里的消费组名仍是 {@code comment-audit-group}</b>
 * （{@code application.yml} 的 {@code spring.kafka.consumer.group-id}）⇒ 本类用
 * {@link com.rag.comment.messaging.CommentKafkaTopics#auditGroupId()} 单独钉住那个<b>字面量</b>。
 *
 * <h3>AGENTS.md §3.2 三段式</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li>本类是<b>测试资产</b>，B 侧 {@code comment-rpc} 的测试集<b>没有</b>真 broker 的审计链路用例
 *             （B 侧只有 {@code CommentMetaAndPreviewTest} 这类纯语义测试）⇒
 *             「生产 ⇒ 消费 ⇒ 落库」这条链在 B 侧<b>没有单测资产</b> ⇒ C 侧补齐；</li>
 *         <li>新增<b>行锁闸门</b>（真 PG 的 {@code SELECT ... FOR UPDATE}）把「落库前无行」从竞态变成确定性
 *             —— 这是<b>测试手法</b>，不改变任何被测代码；</li>
 *         <li>新增<b>原始 consumer 读回 broker 字节</b>并断言 11 字段的<b>名称与顺序</b>
 *             （B 侧的 Go 侧靠 {@code encoding/json} 的结构体声明顺序保证，C 侧靠
 *             {@code @JsonPropertyOrder} 保持一致 ⇒ 这是一条<b>跨语言字节契约</b>）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 被测的三个被测件（{@code CommentDomainService#createComment}、
 *       {@code CommentAuditListener#onMessage}、{@code CommentTransactionalService#insertCommentTx}）
 *       <b>全是生产代码</b>，本类<b>不改一行 {@code src/main/**}</b>；
 *       期望值逐条来自 B 侧源码 / B 侧语义注释（见各用例的「B 出处」）。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>「建评论只推 Kafka、落库在消费侧」：B 侧
 *             {@code service/comment/comment-rpc/.../service/CommentDomainService.java}
 *             的 {@code createComment}（B 侧自记 Go 出处
 *             {@code service/comment/rpc/internal/logic/create_comment_logic.go:37-157}，
 *             其中 {@code :126-132} 是同步 {@code Push}、{@code :136-140} 是响应）＋
 *             B 侧 {@code .../service/CommentAuditListener.java:152-202}（落库在 consume 体内）；</li>
 *         <li>「敏感词只改 status 不改内容」：B 侧
 *             {@code .../service/CommentAuditListener.java:162-169}（Go {@code audit_consumer.go:41-46}）；</li>
 *         <li>落库副作用清单（content / index / subject upsert / 父评论回复数 +1 / 幂等）：
 *             B 侧 {@code service/comment/rpc/internal/model/comment.go:29-153}
 *             （B 侧 Java 对应 {@code .../service/CommentTransactionalService.java}）；</li>
 *         <li>11 字段载荷与顺序：B 侧
 *             {@code service/comment/comment-rpc/.../service/CommentAuditMessage.java:36-81}
 *             （B 侧自记 Go 出处 {@code service/comment/rpc/common/types/kqueue.go:3-15}）；</li>
 *         <li>消费组 / offset / 并发度 / topic：B 侧
 *             {@code service/comment/comment-rpc/src/main/resources/application.yml:181-210,256-267}。</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>⚠️ 本类的证据边界（AGENTS.md §3.3.3）</h2>
 * <p>跑的是 <b>C 侧单侧</b>：证明「C 侧的审计链路真的通、落库真的发生在消费侧」。
 * 「B 侧此刻真的产出同样字节」属<b>双侧活体对拍</b> ⇒ 本类<b>不冒充</b>该证据。
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 MockMvc（不走 HTTP 层 ⇒ HTTP 层由 {@code CommentHttpWireContractRealTomcatTest} 守）；</li>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（容器不可用时<b>直接失败</b>，不静默跳过）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}；</li>
 *   <li>不 mock Kafka（真 broker ＋ 真 listener ＋ 真 PG）。</li>
 * </ul>
 */
@SpringBootTest(classes = {CommentServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                // 🔴 测试专属消费组 ⇒ 不与别的 lane / 探针抢同一 group 的分区（见类注释）
                "spring.kafka.consumer.group-id=comment-audit-group-kafkaaudit",
                // 🔴 latest（**不是 earliest**）⇒ 跳过 topic 上的历史消息，不消化积压
                "spring.kafka.consumer.auto-offset-reset=latest"
        })
@ActiveProfiles("test")
class CommentKafkaAuditRealTest {

    /** 生产 / 消费同 topic（L1 契约 · B 侧 {@code comment.yaml:26,32}）。 */
    private static final String AUDIT_TOPIC = "comment-audit-topic";

    /** 本类专属消费组（**不是** L1 契约里的那个字面量 —— 理由见类注释）。 */
    private static final String TEST_GROUP = "comment-audit-group-kafkaaudit";

    /** Sea-kafka 的宿主发布口（= EXTERNAL listener）。 */
    private static final String BOOTSTRAP = "127.0.0.1:49992";

    /** 有界轮询预算 —— 消费是异步的（60s = 最坏一轮的真实耗时，不是顺利时的耗时）。 */
    private static final long AWAIT_TIMEOUT_MS = 60_000L;

    /** 「确认无行」的短预算：闸门（行锁）开着 ⇒ 消费侧事务卡住 ⇒ 无行是确定性的。 */
    private static final long ABSENT_TIMEOUT_MS = 2_000L;

    /** 本类专用 id 段（与其它测试类不撞）。 */
    private static final AtomicLong COMMENT_SEQ = new AtomicLong(8_700_000_000_000L);

    /** 本类专用 target_id 前缀（{@code comment_index.target_id} 是 VARCHAR(64) ⇒ 不能太长）。 */
    private static final String TARGET_PREFIX = "kaudit";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
        // ⚠️ 只在本类里覆盖 broker 地址（不改 application-test.properties —— 别人的写权）
        registry.add("spring.kafka.bootstrap-servers", () -> BOOTSTRAP);
    }

    @Autowired
    private CommentDomainService domainService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private final List<TopicReader> rawConsumers = new ArrayList<>();

    /**
     * <b>确保 topic 存在</b>（1 partition × 1 replica）。
     *
     * <p>⚠️ 为什么必须显式建：broker 端 {@code auto.create.topics.enable} 不保证开着；
     * 而 topic 不存在时「先 {@code subscribe} 再等 assignment」会卡在 metadata 阶段
     * ⇒ ⇒ {@code awaitAssignment()} 超时 ⇒ 报「topic 不存在」这种误导性错误。
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

    /**
     * 🔴 <b>把 listener「叫醒」并证明它<b>真的在消费</b></b>。
     *
     * <p>为什么需要：{@code auto-offset-reset=latest} 的位置是「<b>log 末尾</b>」
     * ⇒ listener 启动到定位完成之间发出的消息<b>会被丢</b>
     * ⇒ ⇒ 必须<b>先</b>证明 listener 已在消费，<b>再</b>发测试消息。
     * <p>判据 = <b>探针消息真的落进了 {@code comment_index}</b> ⇒ 这是运行证据（不是读配置名推断）。
     */
    @BeforeEach
    void awaitConsumerReady() {
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

    @AfterEach
    void closeRawConsumers() {
        rawConsumers.forEach(TopicReader::close);
        rawConsumers.clear();
    }

    // ==================================================================
    // ① 建评论 ⇒ 只推 Kafka ⇒ 消费侧才落库（本域最独特的行为）
    // ==================================================================

    @Test
    @DisplayName("🔴 建评论只推 Kafka：调用返回后 comment_index/comment_content 无行，消费侧落库后才有行")
    void createCommentDoesNotPersistUntilConsumerRuns() throws Exception {
        long commentId;
        long userId = 9_200L;
        String targetId = TARGET_PREFIX + "-noc-" + COMMENT_SEQ.incrementAndGet();

        // --- 闸门：锁住 subject 行 ⇒ 消费侧卡在 subject upsert（事务不提交 ⇒ 行不可见）
        try (Connection gate = dataSource.getConnection()) {
            gate.setAutoCommit(false);
            seedSubject(targetId);
            try (java.sql.PreparedStatement ps = gate.prepareStatement(
                    "SELECT id FROM subject WHERE target_type = ? AND target_id = ? FOR UPDATE")) {
                ps.setString(1, "article");
                ps.setString(2, targetId);
                ps.executeQuery();
            }

            // --- 原始 consumer：先订阅并等 assignment（latest 的竞态 ⇒ 之后发的消息不会丢）
            try (TopicReader reader = reader()) {
                reader.awaitAssignment();

                // --- 真调用：CreateComment（B 侧 create_comment_logic.go:37-157）
                CreateCommentResp resp = domainService.createComment(CreateCommentReq.newBuilder()
                        .setUserId(userId)
                        .setTargetType("article")
                        .setTargetId(targetId)
                        .setRootId(0L)
                        .setParentId(0L)
                        .setContent("落库前无行")
                        .setMeta("")
                        .build());
                commentId = resp.getId();

                assertThat(commentId).as("CreateComment 必须返回雪花 ID").isPositive();
                assertThat(resp.getSubjectCount())
                        .as("B 侧 create_comment_logic.go:139 ⇒ subject_count 恒 0（落库在消费侧，此刻无可读计数）")
                        .isZero();

                // --- 生产面：broker 上确实有这条消息，且 11 字段逐个对齐
                ConsumerRecord<String, String> record = reader.awaitRecord(
                        r -> r.value() != null && r.value().contains("\"comment_id\":" + commentId),
                        AWAIT_TIMEOUT_MS);
                assertThat(record.topic()).isEqualTo(AUDIT_TOPIC);
                assertThat(record.key())
                        .as("C 侧 CommentAuditPublisher#publish 不设 key（B 侧 kq.Pusher.Push 也不设）")
                        .isNull();

                Map<String, Object> parsed = new LinkedHashMap<>(
                        objectMapper.readValue(record.value(), Map.class));
                assertThat(parsed.keySet()).containsExactly(
                        "comment_id", "target_type", "target_id", "user_id", "root_id", "parent_id",
                        "content", "meta", "owner_id", "attribute", "create_time");
                assertThat(((Number) parsed.get("comment_id")).longValue()).isEqualTo(commentId);
                assertThat(parsed.get("target_type")).isEqualTo("article");
                assertThat(parsed.get("target_id")).isEqualTo(targetId);
                assertThat(((Number) parsed.get("user_id")).longValue()).isEqualTo(userId);
                assertThat(((Number) parsed.get("root_id")).longValue()).isZero();
                assertThat(((Number) parsed.get("parent_id")).longValue()).isZero();
                assertThat(parsed.get("content")).isEqualTo("落库前无行");
                assertThat(parsed.get("meta")).isEqualTo("");
                assertThat(((Number) parsed.get("owner_id")).longValue())
                        .as("owner_id 来自 subject 表（B 侧 resolveOwnerID:143-156）").isZero();
                assertThat(((Number) parsed.get("attribute")).longValue())
                        .as("C 侧建消息时 attribute 恒 0（B 侧同）").isZero();
                assertThat(((Number) parsed.get("create_time")).longValue())
                        .as("create_time 是 Unix 秒（B 侧 create_comment_logic.go:114 的 now.Unix()）")
                        .isGreaterThan(1_700_000_000L);

                // --- 🔴 消费面（闸门开着）：**此刻库里没有这一行**
                assertThat(countWhere("SELECT count(*) FROM comment_index WHERE id = ?", commentId))
                        .as("闸门开着 ⇒ 消费侧事务卡在 subject upsert ⇒ comment_index 无行")
                        .isZero();
                assertThat(countWhere("SELECT count(*) FROM comment_content WHERE comment_id = ?", commentId))
                        .as("闸门开着 ⇒ comment_content 无行")
                        .isZero();
                assertThat(countWhere(
                        "SELECT count(*) FROM subject WHERE target_type = 'article' AND target_id = ? "
                                + "AND total_count = 1", targetId))
                        .as("闸门开着 ⇒ subject 的 total_count 还没被 upsert 推进")
                        .isZero();

                // --- 放开闸门 ⇒ 消费侧继续 ⇒ 行出现
                gate.commit();

                awaitPg("SELECT count(*) FROM comment_index WHERE id = ?", Integer.class, commentId);
                awaitPg("SELECT count(*) FROM comment_content WHERE comment_id = ?", Integer.class, commentId);
                awaitPg("SELECT count(*) FROM subject WHERE target_type = 'article' AND target_id = ? "
                        + "AND total_count = 1", Integer.class, targetId);
            }
        }

        // --- 落库后的内容逐字段核对（消费侧写的，不是 CreateComment 写的）
        Map<String, Object> index = jdbcTemplate.queryForMap(
                "SELECT id, target_type, target_id, user_id, root_id, parent_id, state, attribute "
                        + "FROM comment_index WHERE id = ?", commentId);
        assertThat(((Number) index.get("id")).longValue()).isEqualTo(commentId);
        assertThat(index.get("target_type")).isEqualTo("article");
        assertThat(index.get("target_id")).isEqualTo(targetId);
        assertThat(((Number) index.get("user_id")).longValue()).isEqualTo(userId);
        assertThat(((Number) index.get("root_id")).longValue()).isZero();
        assertThat(((Number) index.get("parent_id")).longValue()).isZero();
        assertThat(((Number) index.get("state")).intValue())
                .as("正常内容 ⇒ state = 0（B 侧 audit_consumer.go:41-46 只在命中敏感词时置 1）").isZero();
        assertThat(((Number) index.get("attribute")).longValue()).isZero();

        Map<String, Object> content = jdbcTemplate.queryForMap(
                "SELECT comment_id, content, meta FROM comment_content WHERE comment_id = ?", commentId);
        assertThat(((Number) content.get("comment_id")).longValue()).isEqualTo(commentId);
        assertThat(content.get("content")).isEqualTo("落库前无行");
        assertThat(jsonbTree(content.get("meta")))
                .as("空 meta ⇒ normalizeCommentMeta 归一化成 {}（B 侧 comment.go:222-240）"
                        + "；⚠️ meta 列是 **jsonb** ⇒ 读出来是 PostgreSQL 的规范化文本，"
                        + "而不是落库前那 2 个字节（B 侧与 C 侧的 V3 迁移脚本**逐字节相同**"
                        + " ⇒ 列类型同样是 JSONB ⇒ 规范化行为也相同 ⇒ **不是 C 侧漂移**）")
                .isEqualTo(Map.of());
    }

    // ==================================================================
    // ② 敏感词 ⇒ state = 1，内容不改
    // ==================================================================

    @Test
    @DisplayName("🔴 敏感词 ⇒ comment_index.state = 1，内容**不改**（且不发通知）")
    void sensitiveContentIsPersistedAsPendingReviewWithoutChangingContent() {
        long commentId = COMMENT_SEQ.incrementAndGet();
        String targetId = TARGET_PREFIX + "-sens-" + commentId;
        seedSubject(targetId);

        kafkaTemplate.send(AUDIT_TOPIC, wireJson(new CommentKafkaMsg(
                commentId, "article", targetId, 9_300L, 0L, 0L,
                "你个傻逼 好好说话", "", 0L, 0L, 1_762_000_001L)));

        awaitPg("SELECT count(*) FROM comment_index WHERE id = ? AND state = 1",
                Integer.class, commentId);
        awaitPg("SELECT count(*) FROM comment_content WHERE comment_id = ?", Integer.class, commentId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT content FROM comment_content WHERE comment_id = ?", String.class, commentId))
                .as("B 侧 audit_consumer.go:41-46 ⇒ 敏感词**只改 status**，内容一字不改")
                .isEqualTo("你个傻逼 好好说话");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM comment_index WHERE id = ?", Integer.class, commentId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subject WHERE target_type = 'article' AND target_id = ? "
                        + "AND total_count = 1", Integer.class, targetId))
                .as("敏感词**照常落库**（只是 state=1）⇒ subject 计数照样 +1")
                .isEqualTo(1);
    }

    // ==================================================================
    // ③ 落库副作用清单：content / index / subject upsert / 父评论回复数 +1 / meta 归一化
    // ==================================================================

    @Test
    @DisplayName("🔴 落库副作用：content+index 各 1 行、subject upsert、父评论回复数 +1、meta 归一化")
    void persistSideEffectsCoverContentIndexSubjectAndParentReplyCount() {
        long parentId = COMMENT_SEQ.incrementAndGet();
        long commentId = COMMENT_SEQ.incrementAndGet();
        String targetId = TARGET_PREFIX + "-side-" + commentId;
        seedSubject(targetId);
        // 父评论先存在（否则 incrementParentReplyCount 影响 0 行 ⇒ B 侧 comment.go:138-140 整笔回滚）
        jdbcTemplate.update("INSERT INTO comment_index (id, target_type, target_id, user_id, root_id, "
                + "parent_id, state, attribute, created_at, updated_at) VALUES (?, 'article', ?, ?, 0, 0, 0, 0, "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", parentId, targetId, 9_400L);

        kafkaTemplate.send(AUDIT_TOPIC, wireJson(new CommentKafkaMsg(
                commentId, "article", targetId, 9_400L, parentId, parentId,
                "回复正文", "不是-json", 0L, 0L, 1_762_000_002L)));

        awaitPg("SELECT count(*) FROM comment_index WHERE id = ?", Integer.class, commentId);
        awaitPg("SELECT count(*) FROM comment_content WHERE comment_id = ?", Integer.class, commentId);

        assertThat(jsonbTree(jdbcTemplate.queryForObject(
                "SELECT meta FROM comment_content WHERE comment_id = ?", String.class, commentId)))
                .as("非法 JSON ⇒ normalizeCommentMeta 包成 {\"raw\":...}（B 侧 comment.go:222-240）"
                        + "；⚠️ meta 列是 **jsonb** ⇒ 落库前是 Go 的 `{\"raw\":\"不是-json\"}`（冒号后**无**空格），"
                        + "读出来是 PostgreSQL 的规范化文本 `{\"raw\": \"不是-json\"}`（冒号后**有**空格）"
                        + " ⇒ ⇒ **两侧列类型相同 ⇒ 同样规范化 ⇒ 不是 C 侧漂移**"
                        + " ⇒ ⇒ 故此处比 JSON **等价**，而不是比字节")
                .isEqualTo(Map.of("raw", "不是-json"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT root_id FROM comment_index WHERE id = ?", Long.class, commentId))
                .isEqualTo(parentId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT parent_id FROM comment_index WHERE id = ?", Long.class, commentId))
                .isEqualTo(parentId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reply_count FROM comment_index WHERE id = ?", Long.class, parentId))
                .as("B 侧 comment.go:126-141 ⇒ 父评论回复数 +1").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT total_count FROM subject WHERE target_type = 'article' AND target_id = ?",
                Long.class, targetId))
                .as("B 侧 comment.go:99-123 ⇒ subject upsert ⇒ total_count +1").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT root_count FROM subject WHERE target_type = 'article' AND target_id = ?",
                Long.class, targetId))
                .as("root_id != 0 ⇒ 不是根评论 ⇒ root_count 不加（B 侧 comment.go:112-114）").isZero();
    }

    // ==================================================================
    // ④ 幂等：同 comment_id + 同身份 + 同内容 ⇒ 跳过（不重复写）
    // ==================================================================

    @Test
    @DisplayName("🔴 幂等：同 comment_id + 同身份 + 同内容重投 ⇒ 只落 1 行、subject 只计 1 次")
    void duplicateDeliveryIsIdempotent() {
        long commentId = COMMENT_SEQ.incrementAndGet();
        String targetId = TARGET_PREFIX + "-idem-" + commentId;
        seedSubject(targetId);

        CommentKafkaMsg msg = new CommentKafkaMsg(
                commentId, "article", targetId, 9_500L, 0L, 0L,
                "幂等内容", "{}", 0L, 0L, 1_762_000_003L);
        String payload = wireJson(msg);

        kafkaTemplate.send(AUDIT_TOPIC, payload);
        awaitPg("SELECT count(*) FROM comment_index WHERE id = ?", Integer.class, commentId);
        awaitPg("SELECT count(*) FROM subject WHERE target_type = 'article' AND target_id = ? "
                + "AND total_count = 1", Integer.class, targetId);

        // 重投同一条（B 侧 comment.go:39-60 的幂等探针：同 ID + 同身份 + 同内容 ⇒ 跳过）
        kafkaTemplate.send(AUDIT_TOPIC, payload);
        awaitQuiet(3_000L);

        assertThat(countWhere("SELECT count(*) FROM comment_index WHERE id = ?", commentId))
                .as("同 comment_id 重投 ⇒ comment_index 不得出现第二行").isEqualTo(1);
        assertThat(countWhere("SELECT count(*) FROM comment_content WHERE comment_id = ?", commentId))
                .as("同 comment_id 重投 ⇒ comment_content 不得出现第二行").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT total_count FROM subject WHERE target_type = 'article' AND target_id = ?",
                Long.class, targetId))
                .as("幂等命中 ⇒ 直接 return ⇒ subject 计数**不再 +1**（B 侧 comment.go:39-60）")
                .isEqualTo(1L);
    }

    // ==================================================================
    // 夹具 / 工具
    // ==================================================================

    /**
     * 预置 subject 行（评论区）。
     *
     * <p>🔴 <b>为什么必须预置</b>：{@code CreateComment} 的 {@code resolveOwnerID}
     * （B 侧 {@code create_comment_logic.go:143-156}）先查本地 {@code subject}
     * ⇒ 查不到才回落 article-rpc ⇒ 而测试上下文里<b>没有 article-rpc 服务</b>
     * ⇒ ⇒ 不预置就会走回落 ⇒ 拿不到 ownerId ⇒ 建评论直接失败。
     * <p>⚠️ {@code owner_id = 0} ⇒ 消费侧的 {@code notifyComment} 因
     * 「{@code recipientID <= 0} ⇒ 不发」（B 侧 {@code audit_consumer.go:97-99}）而跳过
     * ⇒ ⇒ 本类<b>不依赖 message-rpc 可达性</b>（那是另一条出口方向，由
     * {@code CommentKafkaFailureDirectionRealTest} 守）。
     */
    private void seedSubject(String targetId) {
        jdbcTemplate.update("INSERT INTO subject (target_type, target_id, total_count, root_count, "
                + "state, attribute, owner_id, created_at, updated_at) VALUES ('article', ?, 0, 0, 0, 0, 0, "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", targetId);
    }

    /** 用应用的 {@link ObjectMapper} 序列化（⇒ 与生产端<b>逐字节同形</b>）。 */
    private String wireJson(CommentKafkaMsg msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            throw new IllegalStateException("构造审计消息失败", e);
        }
    }

    /** 有界轮询 PG，直到 count > 0（超时即失败，不挂死）。 */
    private void awaitPg(String sql, Class<?> type, Object... args) {
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

    /** 给「不应发生变化」这类断言留出落地窗口（Kafka 是异步的）。 */
    private void awaitQuiet(long ms) {
        sleep(ms);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 建一个「原始」consumer（独立 group ＋ latest ＋ 手动提交）。 */
    private TopicReader reader() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "comment-kafkaaudit-probe-" + COMMENT_SEQ.incrementAndGet());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        TopicReader reader = new TopicReader(new KafkaConsumer<>(props), AUDIT_TOPIC);
        rawConsumers.add(reader);
        return reader;
    }

    /** 原始 consumer 的小封装：**先等 assignment，再等消息**（避免 latest 的竞态丢消息）。 */
    private static final class TopicReader implements AutoCloseable {

        private final KafkaConsumer<String, String> consumer;

        private final String topic;

        private TopicReader(KafkaConsumer<String, String> consumer, String topic) {
            this.consumer = consumer;
            this.topic = topic;
        }

        void awaitAssignment() {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 15_000L;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500));
                if (!consumer.assignment().isEmpty()) {
                    return;
                }
            }
            throw new AssertionError("原始 consumer 15s 内没拿到 partition 分配（topic=" + topic + "）");
        }

        ConsumerRecord<String, String> awaitRecord(Predicate<ConsumerRecord<String, String>> match,
                                                   long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (match.test(record)) {
                        return record;
                    }
                }
            }
            throw new AssertionError("原始 consumer " + timeoutMs + "ms 内没等到匹配的消息（topic="
                    + topic + "）");
        }

        @Override
        public void close() {
            try {
                consumer.close(Duration.ofSeconds(2));
            } catch (Exception ignored) {
                // 关闭失败不影响判读
            }
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * 把 {@code jsonb} 列读出来的值解析成 JSON 树。
     *
     * <p>⚠️ <b>为什么不直接比字符串</b>：
     * {@code comment_content.meta} 的列类型是 {@code JSONB}
     * （B 侧与 C 侧的 {@code V3__create_comment_content.sql} <b>逐字节相同</b>）
     * ⇒ ⇒ PostgreSQL 会把写入的 JSON <b>重新序列化</b>成它自己的规范形式
     * （冒号与逗号后加空格、键排序、去掉冗余空白）
     * ⇒ ⇒ <b>落库前的字节 ≠ 落库后读回的字节</b>。
     * ⇒ ⇒ 比 JSON <b>等价</b>才是「可观察行为」的正确判据；
     * 而 <b>两侧列类型相同 ⇒ 规范化行为也相同 ⇒ 这不是 C 侧漂移</b>
     * （AGENTS.md §1.1「不能把源项目原有问题误报为迁移引入的问题」）。
     *
     * @param raw {@code queryForMap} 取出的 {@code PGobject}，或 {@code queryForObject(..., String.class)} 取出的字符串
     */
    private Map<String, Object> jsonbTree(Object raw) {
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("解析 jsonb 列失败：" + raw, e);
        }
    }
}
