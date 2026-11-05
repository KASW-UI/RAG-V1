package com.rag.task.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.rag.task.TaskServiceApplication;
import com.rag.task.service.TaskRedisKeys;
import com.rag.task.support.NacosStubConfiguration;
import com.rag.task.support.TestPostgres;
import com.rag.task.support.TestRedis;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <p><b>🔴 task 域 Kafka 消费面「端到端真跑」（真 broker ＋ 真 PG ＋ 真 Redis）</b> ——
 * 补 `docs/DRIFT/task/verification-task-service-2026-11-03.md` §七·7-1 **U-1**。</p>
 *
 * <h2>🔴 为什么要单独一个类（而不是塞进默认回归）</h2>
 * <p>本类需要<b>宿主上的真 Kafka broker</b>（容器 {@code Sea-kafka} · EXTERNAL 发布口
 * {@code 127.0.0.1:49992}）⇒ ① 没有 broker 的机器上<b>必然红</b>（不是「跳过」）
 * ⇒ AGENTS.md §五.8 规则 ③ <b>禁止</b> {@code @EnabledIfDockerAvailable} 把它变绿
 * ⇒ 只能按规则 ④「排除项用<b>前缀/类别匹配</b>」把它从<b>默认回归</b>里排除；
 * ② 它会<b>写真实数据</b>（Kafka 消息 ＋ Redis key）⇒ 不该由<b>别人的</b>回归顺手触发。
 * ⇒ 故：类级打 {@code @Tag("external-env")} ＋ 本域 pom 的
 * {@code <excludedGroups>${task.test.excludedGroups}</excludedGroups>}（默认 = {@code external-env}）
 * ＋ {@code external-env} profile 把它改成 {@code __none__} 并只跑这一组。</p>
 *
 * <h2>🔴 为什么要覆盖 topic（而不是用生产 topic 名）</h2>
 * <p>生产 topic（{@code task.logs.raw} / {@code task.logs.raw.User} / …）<b>与 like 域共享</b>
 * （L1 跨服务契约）⇒ ① 三个 goka 消费者用的是 {@code auto.offset.reset=earliest}
 * ⇒ 用生产 topic 会<b>重放整段历史</b>（计数器从 1 变成 N ⇒ 断言不确定）；
 * ② 往生产 topic 投消息会<b>污染 like 域</b>的流量。
 * ⇒ 故本类把 5 个 topic 属性覆盖成<b>本次运行唯一</b>的名字 ⇒ <b>零历史、零污染、零重放</b>。
 * <p>📌 <b>分工</b>：「生产 topic / group 的<b>字面值</b>」由
 * {@code messaging/TaskKafkaListenerAssemblyTest}（真进 registry ＋ 逐条比对）负责；
 * 本类负责「<b>消息真被消费 → 真落 Redis → 真落 PG</b>」这条<b>机制链</b>。
 * ⇒ 两者合起来才覆盖「L1 字面值 ＋ 机制生效」。</p>
 *
 * <h2>🔴 为什么必须<b>先等分区分配完</b>再投消息</h2>
 * <p>两个 sink 的 listener 用的是 {@code auto.offset.reset=latest}
 * ⇒ 若在 consumer <b>加入 group 之前</b>就投消息，那条消息会被<b>直接跳过</b>（位点从队尾起）
 * ⇒ 测试会<b>假绿</b>（什么都没消费，但断言只看「最终状态」⇒ 其实会红，只是红得莫名其妙）。
 * ⇒ 故：{@link #awaitPartitionAssignment()} 先等 <b>6 个 listener 容器全部拿到分区</b>，
 * 再投消息。</p>
 *
 * <h2>本类验的是什么链（B 侧同形）</h2>
 * <pre>
 * 投一条 raw 日志 JSON 到 in-topic
 *   └─ TaskRawLogRouter（task.logs.raw）
 *        ├─ emit → raw-user-topic（key = user_id）
 *        │    └─ TaskUserCountConsumer → 计数器 INCR → emit → out-user-topic（key, cur）
 *        │         └─ TaskUserLikeProgressSink → SETNX 闸门 → Redis Hash + PG → 进度推进
 *        └─ emit → raw-article-topic（key = article_id）
 *             └─ TaskArticleCountConsumer → 计数器 INCR → emit → out-article-topic（key, TaskArticleProgressEvent）
 *                  └─ TaskArticleLikeProgressSink → SETNX 闸门 → Redis Hash + PG → 进度推进
 * </pre>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> —— ① 测试专用 topic 名（隔离手段，机制同形）；
 *       ② 用 Testcontainers 起真 PG / 真 Redis（B 侧连宿主 PG / Redis）；
 *       ③ 用 {@code KafkaTemplate} 代替 Go 的 goka 生产端（载荷形状不变）；</li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 消费顺序、载荷形状、计数器命名空间、Redis key、
 *       PG 行形状均与 B 侧一致（每条断言都指向 B 侧的同一份行为）；</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mq/TaskRawLogRouter.java:105-116（router 的 route）
 * B 出处：…/mq/TaskUserCountConsumer.java:89-118（用户路计数 + emit）
 * B 出处：…/mq/TaskArticleCountConsumer.java:87-127（文章路计数 + emit TaskArticleProgressEvent）
 * B 出处：…/mq/UserLikeProgressSink.java:232-262（flushOnce：lazyInit → flushRedis → flushPostgres）
 * B 出处：…/mq/UserLikeProgressSink.java:431-435（updateDoingTaskProgress）
 * B 出处：…/mq/ArticleLikeProgressSink.java:375-382（文章路 SETNX 闸门）
 *       </pre></li>
 * </ol>
 */
@Tag("external-env")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = {TaskServiceApplication.class, NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // ---------- 真 Kafka（宿主 Sea-kafka · EXTERNAL 发布口）----------
                "spring.kafka.bootstrap-servers=127.0.0.1:49992",
                // ⚠️ broker 不可达时 KafkaTemplate.send() 会阻塞到 max.block.ms（默认 60s）
                "spring.kafka.producer.properties.max.block.ms=5000",
                // ---------- gRPC 端口交给 OS（本类不验 gRPC，但 devh 的 server 是 SmartLifecycle ⇒ 启动即 bind）----------
                "grpc.server.port=0",
                // ---------- 追踪不外发 ----------
                "management.otlp.tracing.export.enabled=false",
                // ---------- 关掉 CPU 自适应降载（否则会把 503 抢先盖掉判据）----------
                "rag.protection.cpu-shedding.enabled=false"
        })
class TaskKafkaConsumeRealTest {

    /** 宿主 Sea-kafka 的 EXTERNAL 发布口。 */
    private static final String BROKER = "127.0.0.1:49992";

    /** 本次运行唯一的 topic 前缀 ⇒ 零历史、零跨域污染。 */
    private static final String RUN_ID = "taskreal" + Long.toHexString(System.nanoTime());

    private static final String IN_TOPIC = RUN_ID + ".raw";
    private static final String RAW_USER_TOPIC = RUN_ID + ".raw.User";
    private static final String RAW_ARTICLE_TOPIC = RUN_ID + ".raw.Article";
    private static final String OUT_USER_TOPIC = RUN_ID + ".filtered.User";
    private static final String OUT_ARTICLE_TOPIC = RUN_ID + ".filtered.Article";

    /** 用户路任务常量（B 侧 `like_sink.go:31-34`）。 */
    private static final long LIKE_TASK_ID = 1902L;
    private static final long LIKE_TARGET = 5L;

    /** 文章路任务常量（B 侧 `article_sink.go:18-31`）。 */
    private static final long ARTICLE_TASK_ID = 190L;
    private static final long ARTICLE_TARGET = 3L;

    // ⚠️ **每个用例必须用自己独立的 uid / articleId**（980 号段 · 域内其它用例用 880/905 ⇒ 不撞）：
    // 三个用例都往**同一条 raw 日志链**投消息，而路由会把**每一条**都转发到 raw-article-topic
    // ⇒ ⇒ 共用同一对 id 时，文章路计数器会被**累加**（实测读到 3 而不是 1）
    // ⇒ ⇒ 断言必须按**各自唯一的 id**过滤。
    /** 用例 ① 的用户路 uid / articleId。 */
    private static final long USER_ID_ONE = 980_000_001L;
    private static final long ARTICLE_ID_ONE = 980_000_002L;

    /** 用例 ② 的用户路 uid / articleId。 */
    private static final long USER_ID_TWO = 980_000_003L;
    private static final long ARTICLE_ID_TWO = 980_000_004L;

    /** 用例 ③ 的用户路 uid / articleId。 */
    private static final long USER_ID_THREE = 980_000_005L;
    private static final long ARTICLE_ID_THREE = 980_000_006L;

    /** 轮询预算（秒）—— 链路有 1s 定时刷盘 ⇒ 30s 足够，60s 是余量。 */
    private static final long AWAIT_SECONDS = 60L;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private TaskKafkaTopics topics;

    /**
     * 把 5 个 topic 覆盖成本次运行唯一的名字 ＋ 注入真 PG / 真 Redis 的连接信息。
     *
     * <p>⚠️ 必须在<b>上下文 refresh 之前</b>跑（{@code @DynamicPropertySource} 的时机）⇒
     * listener 订阅的就是这些唯一 topic ⇒ 不会有历史可重放。</p>
     */
    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
        registry.add("POSTGRES_PASSWORD", () -> "password");
        registry.add("task.kafka.in-topic", () -> IN_TOPIC);
        registry.add("task.kafka.raw-user-topic", () -> RAW_USER_TOPIC);
        registry.add("task.kafka.raw-article-topic", () -> RAW_ARTICLE_TOPIC);
        registry.add("task.kafka.out-user-topic", () -> OUT_USER_TOPIC);
        registry.add("task.kafka.out-article-topic", () -> OUT_ARTICLE_TOPIC);
    }

    /**
     * 🔴 <b>必须在 Spring 上下文起来之前</b>把 5 个 topic 建出来。
     *
     * <p>⚠️ 理由：JUnit5 的执行顺序是「<b>扩展回调</b>（{@code SpringExtension.beforeAll} ⇒ 上下文 refresh）
     * → <b>用户 {@code @BeforeAll}</b>」⇒ 若在 {@code @BeforeAll} 里建 topic，
     * listener <b>已经</b>订阅了尚不存在的 topic ⇒ 只能靠 broker 的 auto-create 兜底
     * （分区数/配置不受控）⇒ ⇒ 改用<b>静态块</b>（类加载时即执行，早于上下文 refresh）。</p>
     */
    static {
        try {
            createTopics();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void createTopics() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        try (Admin admin = Admin.create(props)) {
            List<NewTopic> topicsToCreate = List.of(
                    new NewTopic(IN_TOPIC, 1, (short) 1),
                    new NewTopic(RAW_USER_TOPIC, 1, (short) 1),
                    new NewTopic(RAW_ARTICLE_TOPIC, 1, (short) 1),
                    new NewTopic(OUT_USER_TOPIC, 1, (short) 1),
                    new NewTopic(OUT_ARTICLE_TOPIC, 1, (short) 1));
            admin.createTopics(topicsToCreate).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            // TopicExistsException 是幂等的（上一轮跑过）⇒ 只在「不是已存在」时才失败
            if (!String.valueOf(e.getMessage()).contains("TopicExistsException")) {
                throw e;
            }
        }
    }

    /**
     * 等<b>指定</b>的 listener 拿到分区。
     *
     * <p>⚠️ <b>为什么按用例收紧，而不是统一等 6 个</b>：统一等 6 个会让「停掉任意一个 listener」
     * 这一变异<b>把所有用例</b>都打红 ⇒ 判据<b>变异面过大</b>（分不清是谁坏了）。
     * ⇒ 每个用例只等<b>自己那条链上</b>的 listener ⇒ 停掉用户路 sink 时，
     * 用户路用例红、<b>文章路用例仍绿</b> ⇒ 变异面精确。</p>
     *
     * <p>⚠️ 两个 sink 用的是 {@code auto.offset.reset=latest}
     * ⇒ 若在 consumer 加入 group 之前就投消息，那条消息会被<b>直接跳过</b>（位点从队尾起）
     * ⇒ ⇒ 必须先等它们拿到分区（位点定在队尾），再投消息。</p>
     */
    private void awaitPartitionAssignment(String... listenerIds) throws Exception {
        List<String> expected = List.of(listenerIds);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Set<String> assigned = registry.getListenerContainers().stream()
                    .filter(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty())
                    .map(c -> c.getListenerId())
                    .collect(java.util.stream.Collectors.toSet());
            if (assigned.containsAll(expected)) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("listener " + expected + " 未在 " + AWAIT_SECONDS + "s 内拿到分区");
    }

    @Test
    @DisplayName("① 🔴 用户路端到端：raw 日志 → router → 计数 → 用户 sink → Redis ＋ PG 真落库")
    void userPathIsConsumedAndPersisted() throws Exception {
        // 本链：router → 用户路计数 → 用户路 sink（**不含**文章路的三个）
        awaitPartitionAssignment("task-goka-router", "task-goka-user-count", "task-user-progress-sink");
        produceRawLog(USER_ID_ONE, ARTICLE_ID_ONE);

        // ---- Redis：计数器的 group 命名空间 + SETNX 闸门 + like:total ----
        String counterKey = TaskCountStore.COUNTER_KEY_PREFIX + topics.getGroupGokaUser() + ":" + USER_ID_ONE;
        awaitValue(counterKey, "1");
        awaitValue(TaskRedisKeys.likeTaskInit(USER_ID_ONE, LIKE_TASK_ID), "1");
        awaitValue(TaskRedisKeys.userLikeTotal(USER_ID_ONE), "1");

        Map<Object, Object> progress = awaitHash(TaskRedisKeys.likeTaskProgress(USER_ID_ONE, LIKE_TASK_ID));
        assertThat(progress.get("status")).as("Redis 进度 Hash 的 status").isEqualTo("doing");
        assertThat(progress.get("target")).as("Redis 进度 Hash 的 target（点赞任务 = 5）")
                .isEqualTo(String.valueOf(LIKE_TARGET));

        // ---- PG：task_progress 真落库 ----
        awaitProgressRow(USER_ID_ONE, LIKE_TASK_ID, "doing", 1L, LIKE_TARGET);
    }

    @Test
    @DisplayName("② 🔴 文章路端到端：raw 日志 → router → 计数 → 文章 sink → Redis ＋ PG 真落库")
    void articlePathIsConsumedAndPersisted() throws Exception {
        // 本链：router → 文章路计数 → 文章路 sink（**不含**用户路的三个）
        awaitPartitionAssignment("task-goka-router", "task-goka-article-count", "task-article-progress-sink");
        produceRawLog(USER_ID_TWO, ARTICLE_ID_TWO);

        String counterKey = TaskCountStore.COUNTER_KEY_PREFIX + topics.getGroupGokaArticle() + ":" + ARTICLE_ID_TWO;
        awaitValue(counterKey, "1");
        awaitValue(TaskRedisKeys.articleTaskInit(ARTICLE_TASK_ID, USER_ID_TWO, ARTICLE_ID_TWO), "1");
        awaitValue(TaskRedisKeys.articleLikeCount(ARTICLE_ID_TWO), "1");

        Map<Object, Object> progress =
                awaitHash(TaskRedisKeys.articleTaskProgress(ARTICLE_TASK_ID, USER_ID_TWO, ARTICLE_ID_TWO));
        assertThat(progress.get("status")).as("文章路 Redis 进度 Hash 的 status").isEqualTo("doing");
        assertThat(progress.get("target")).as("文章路 Redis 进度 Hash 的 target（文章获赞任务 = 3）")
                .isEqualTo(String.valueOf(ARTICLE_TARGET));

        awaitProgressRow(USER_ID_TWO, ARTICLE_TASK_ID, "doing", 1L, ARTICLE_TARGET);
    }

    @Test
    @DisplayName("③ 🔴 6 个 listener 的位点都真提交了（含「什么都不做」的诊断消费者）")
    void allSixListenersCommittedOffsets() throws Exception {
        // 位点面要覆盖**全部 6 个** listener（含「什么都不做」的诊断消费者）
        awaitPartitionAssignment("task-goka-router", "task-goka-user-count", "task-goka-article-count",
                "task-raw-diagnostic", "task-user-progress-sink", "task-article-progress-sink");
        produceRawLog(USER_ID_THREE, ARTICLE_ID_THREE);
        // 等整条链跑完（用户路的 PG 行出现 ⇒ 位点早已提交）
        awaitProgressRow(USER_ID_THREE, LIKE_TASK_ID, "doing", 1L, LIKE_TARGET);

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        try (Admin admin = Admin.create(props)) {
            Map<String, List<String>> groups = Map.of(
                    topics.getGroupGoka(), List.of(IN_TOPIC),
                    topics.getGroupKafkaRaw(), List.of(IN_TOPIC),
                    topics.getGroupGokaUser(), List.of(RAW_USER_TOPIC),
                    topics.getGroupGokaArticle(), List.of(RAW_ARTICLE_TOPIC),
                    topics.getGroupFilterUser(), List.of(OUT_USER_TOPIC),
                    topics.getGroupKafkaFilter(), List.of(OUT_ARTICLE_TOPIC));
            for (Map.Entry<String, List<String>> e : groups.entrySet()) {
                Map<TopicPartition, OffsetAndMetadata> offsets =
                        admin.listConsumerGroupOffsets(e.getKey())
                                .partitionsToOffsetAndMetadata().get(30, TimeUnit.SECONDS);
                long committed = offsets.values().stream()
                        .mapToLong(OffsetAndMetadata::offset).max().orElse(-1L);
                assertThat(committed)
                        .as("group %s 必须已提交位点（证明消息真被消费）", e.getKey())
                        .isGreaterThanOrEqualTo(1L);
            }
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 投一条 raw 日志 JSON 到 in-topic —— 形状与 B 侧 {@code TaskLogEvent} 的 7 个字段一致。
     *
     * <p>⚠️ key 留空：B 侧的 goka 是按 <b>value 里的 user_id / article_id</b> 分流的，
     * 不是按 key ⇒ 这里必须把两个 id 放进 value。</p>
     */
    private void produceRawLog(long userId, long articleId) throws Exception {
        String payload = "{"
                + "\"ts\":\"2026-11-04T00:00:00Z\","
                + "\"service\":\"task-real-test\","
                + "\"event_id\":\"evt-" + userId + "-" + articleId + "\","
                + "\"user_id\":\"" + userId + "\","
                + "\"trace_id\":\"trace-" + userId + "\","
                + "\"article_id\":\"" + articleId + "\","
                + "\"msg\":\"task real kafka consume test\""
                + "}";
        kafkaTemplate.send(IN_TOPIC, "", payload).get(30, TimeUnit.SECONDS);
    }

    /** 轮询等某个 Redis 字符串 key 变成期望值。 */
    private void awaitValue(String key, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        String actual = null;
        while (System.nanoTime() < deadline) {
            actual = redis.opsForValue().get(key);
            if (expected.equals(actual)) {
                return;
            }
            Thread.sleep(300L);
        }
        throw new AssertionError("Redis key " + key + " 在 " + AWAIT_SECONDS
                + "s 内未变成 " + expected + "（实际 = " + actual + "）");
    }

    /** 轮询等某个 Redis Hash 出现（且非空）。 */
    private Map<Object, Object> awaitHash(String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        Map<Object, Object> actual = Map.of();
        while (System.nanoTime() < deadline) {
            Map<Object, Object> entries = redis.opsForHash().entries(key);
            if (!entries.isEmpty()) {
                return entries;
            }
            Thread.sleep(300L);
        }
        throw new AssertionError("Redis hash " + key + " 在 " + AWAIT_SECONDS + "s 内未出现");
    }

    /** 轮询等 PG 的 {@code task_progress} 出现目标行（并逐列比对）。 */
    private void awaitProgressRow(long userId, long taskId, String status, long progress, long target)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        String last = null;
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT user_id, task_id, status, progress, target FROM task_progress "
                            + "WHERE user_id = ? AND task_id = ?",
                    userId, taskId);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                last = String.valueOf(row);
                assertThat(row.get("status")).as("task_progress.status").isEqualTo(status);
                assertThat(((Number) row.get("progress")).longValue()).as("task_progress.progress")
                        .isEqualTo(progress);
                assertThat(((Number) row.get("target")).longValue()).as("task_progress.target")
                        .isEqualTo(target);
                return;
            }
            Thread.sleep(300L);
        }
        throw new AssertionError("PG task_progress 在 " + AWAIT_SECONDS + "s 内未出现 (uid="
                + userId + ", task_id=" + taskId + ")，最后一次读数 = " + last);
    }
}
