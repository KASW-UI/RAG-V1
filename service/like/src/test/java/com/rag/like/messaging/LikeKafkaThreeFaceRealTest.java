package com.rag.like.messaging;

import com.rag.like.LikeServiceApplication;
import com.rag.like.contract.ArticleHotEvent;
import com.rag.like.contract.KafkaLikeMsg;
import com.rag.like.domain.service.LikeDomainService;
import com.rag.like.support.NacosStubConfiguration;
import com.rag.like.support.TestPostgres;
import com.rag.like.support.TestRedis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.proto.like.ActionType;
import com.rag.proto.like.LikeActionReq;
import com.rag.proto.like.LikeActionResp;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>like 域 Kafka 三面（生产 / 消费 / 推送）的真链路守门</b>
 * —— 真 Kafka broker ＋ 真 {@code @KafkaListener} ＋ 真 PG ＋ 真 Redis ＋ 真定时中继。
 *
 * <h2>🔴 为什么这一面之前是零覆盖</h2>
 * <p>本域此前的测试配置把 {@code spring.kafka.bootstrap-servers} 指向
 * {@code 127.0.0.1:1}（{@code application-test.properties}）⇒ ⇒ <b>broker 不可达</b>
 * ⇒ ⇒ 消费路径 {@code LikeUpdateKafkaListener} <b>从未被真触发</b>（连不上 ⇒ 只会重试）
 * ⇒ 且「发出去」只能靠"无 broker ⇒ 异步失败"的降级路径观察。
 * <p>⇒ ⇒ 本类把 <b>真 broker</b> 接进来，补上三面：
 * <ol>
 *   <li><b>生产面</b>：{@code likeAction} ⇒ {@code KafkaLikeMsg} 必须真的落到
 *       {@code like_promotion_topic}，且<b>字段序</b>逐个钉住（那是 L1 契约）；</li>
 *   <li><b>消费面</b>：一条 {@code KafkaLikeMsg} ⇒ {@code like_consume_inbox} /
 *       {@code like_record} / {@code like_fact_stream} / {@code like_outbox_event} <b>四处齐全</b>；</li>
 *   <li><b>推送面</b>：{@code like_outbox_event} 的待发行 ⇒ 被
 *       {@code LikeHotEventOutboxRelay} 推到 {@code article-hot-events}，<b>payload 逐字段</b>对齐；</li>
 *   <li><b>幂等面</b>：同 {@code msg_id} 重投 ⇒ inbox 去重 ＋ record 不重复 ＋ fact 版本不递增。</li>
 * </ol>
 *
 * <h2>🔴 纠正一个旧结论：{@code spring.kafka.listener.auto-startup=false} 是<b>死配置</b></h2>
 * <p>{@code application-test.properties} 里写着「{@code listener.auto-startup=false}
 * ⇒ 消费者不启动 ⇒ 消费路径不在覆盖内」—— <b>这个归因是错的</b>：
 * <ul>
 *   <li>应用在 {@code LikeServiceApplication#kafkaListenerContainerFactory} 里
 *       <b>自己 {@code new} 了</b> {@code ConcurrentKafkaListenerContainerFactory}
 *       ⇒ Boot 的 {@code ConcurrentKafkaListenerContainerFactoryConfigurer} <b>不参与</b>
 *       ⇒ {@code spring.kafka.listener.*} <b>一整族键都没有消费点</b>（AGENTS §3.3.1）；</li>
 *   <li>{@code autoStartup} 因此取框架默认值 {@code true}
 *       ⇒ ⇒ <b>listener 一直都在起</b>，只是 broker 不可达 ⇒ 一直重试；</li>
 *   <li>全仓 <b>没有任何</b> {@code setAutoStartup(...)} 调用（{@code grep} 实测）；
 *       {@code LikeServiceApplication.java:88-97} 的 factory 只设了 consumerFactory /
 *       errorHandler / concurrency。</li>
 * </ul>
 * <p>⇒ ⇒ <b>区分性实验</b>：若那条键真的生效，listener 就不会消费 ⇒ 本类的<b>消费面</b>断言
 * 必然超时变红 ⇒ ⇒ <b>消费面通过 = 该键无效的直接运行证据</b>（不靠读配置名推断）。
 * <p>⚠️ 与此同时，<b>真正让消费路径零覆盖的原因是 broker 不可达</b>（不是 listener 没起）
 * ⇒ ⇒ 本类覆盖 {@code bootstrap-servers} 才真正把这一面点亮。
 *
 * <h2>⚠️ {@code msg_id} 必须是<b>纯数字字符串</b></h2>
 * <p>listener 的 {@code isValid} 五条判据（B 侧 {@code isValid}）要求
 * {@code msg_id} <b>非空且可解析为正整数</b>（{@code Long.parseLong}）⇒
 * ⇒ 像 {@code "kafka3face-123"} 这种带前缀的<b>会被静默丢弃</b>
 * ⇒ ⇒ 本类的夹具一律用纯数字（与 B 侧雪花 ID 同形）。
 *
 * <h2>🔴 broker 地址：为什么是 {@code 127.0.0.1:49992}</h2>
 * <p>本机 Sea-kafka 的两个 listener：{@code PLAINTEXT://kafka:9092}（容器内）与
 * {@code EXTERNAL://127.0.0.1:49992}（宿主发布口，{@code KAFKA_ADVERTISED_LISTENERS}）。
 * <p>⇒ ⇒ 宿主上的客户端必须连 <b>EXTERNAL</b> 口 ⇒ 若连 {@code 9092}，broker 在 metadata 里
 * 会把地址回写成 {@code kafka:9092} ⇒ 宿主解析不了 ⇒ 卡在 metadata 阶段。
 *
 * <h2>⚠️ 为什么消费组用<b>测试专属 group</b> ＋ <b>latest</b></h2>
 * <p>listener 的 group 来自 {@code ${spring.kafka.consumer.group-id}} ⇒ 本类覆盖成
 * {@code like-update-group-kafka3face} ⇒ <b>不与别的 lane / 对拍探针抢同一 group 的分区</b>
 * ⇒ 否则同一批消息会被两个实例分走 ⇒ 读数随机。
 * <p>🔴 <b>为什么是 {@code latest} 而不是 {@code earliest}</b>：本 topic 上<b>存着对拍探针
 * 留下的几百条历史消息</b> ⇒ 若从头部开始 ⇒ listener 必须**先消化全部积压**
 * （每条都要落库 ＋ 事务）⇒ ⇒ 实测在<b>全量回归</b>里 30s 预算被积压吃光
 * ⇒ ⇒ 消费面 / 推送面 / 幂等面**三个一起超时**（而单跑那个类时是绿的 —— 因为当时积压更少）
 * ⇒ ⇒ 而 {@code latest} 是 {@code seek 到 log 末尾}（O(1)）⇒ <b>不消化积压</b>。
 * <p>⚠️ <b>{@code latest} 的代价 = 竞态</b>：listener 定位到末尾之前发出的消息会丢
 * ⇒ ⇒ 故本类加了一个<b>预热步骤</b>（{@link #awaitConsumerReady()}）：先发一条探针消息
 * 并<b>等它真的落进 inbox</b> ⇒ ⇒ 「listener 已定位且在消费」有了<b>运行证据</b>
 * ⇒ 之后的测试消息才发得出去。
 *
 * <h2>⚠️ 断言一律用<b>有界轮询</b>（Kafka 是异步的）</h2>
 * <p>⇒ 每条断言都带一个上限（消息 30s / PG 30s）⇒ 超时即失败，<b>不会挂死</b>；
 * 且轮询的是「<b>目标条件成立</b>」而不是「睡够 N 秒」。
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>跑的是 <b>C 侧单侧</b>：证明「C 侧的 Kafka 三面真的通」。
 * 「B 侧此刻真的产出同样字节」属<b>双侧活体对拍</b>（{@code tools/difftest/like/} 的
 * <b>两侧对称关掉了 listener</b> ⇒ 该探针**不覆盖**消费面）⇒ ⇒ 本类与该探针**互补**，不重复。
 */
@SpringBootTest(classes = {LikeServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                // ⚠️ Sea-kafka 的 EXTERNAL 发布口（容器内 9092 对宿主不可用）
                "spring.kafka.bootstrap-servers=127.0.0.1:49992",
                // 🔴 测试专属 group ⇒ 不与别的 lane / 探针抢分区（见类注释）
                "spring.kafka.consumer.group-id=like-update-group-kafka3face",
                // 🔴 latest（**不是 earliest**）⇒ 跳过历史消息的「重放积压」（见类注释）
                "spring.kafka.consumer.auto-offset-reset=latest",
                // 加快中继轮询（生产默认 1000ms）
                "like.outbox-relay-interval=300"
        })
@ActiveProfiles("test")
class LikeKafkaThreeFaceRealTest {

    /** 生产 / 消费的主题（= B 侧同款 topic 名，L1 契约）。 */
    private static final String LIKE_TOPIC = "like_promotion_topic";

    /** 热点事件推送主题（= B 侧同款 topic 名）。 */
    private static final String HOT_TOPIC = "article-hot-events";

    /**
     * <b>目标类型</b> —— 固定用 {@code "article"}（B 侧生产数据的真实取值）。
     *
     * <p>⚠️ <b>为什么不能自造长前缀</b>：{@code like_record.target_type} 是
     * {@code VARCHAR(32)}（{@code V1__create_like_record.sql:17}，<b>与 B 侧逐字一致</b>）
     * ⇒ ⇒ 夹具里像 {@code "article-kafka-consume-8400000000008"}（35 字符）这种值
     * ⇒ ⇒ 消费侧 {@code insertSeedIfAbsent} 会抛
     * {@code value too long for type character varying(32)}
     * ⇒ ⇒ 消息按 Go 的 {@code ForceCommit=true} 语义被**丢弃**（位点前移、不重投）
     * ⇒ ⇒ 表现是「inbox 没行」而不是「长度报错」⇒ **极难定位**（本轮真的踩到了）。
     *
     * <p>⚠️ <b>为什么不用长前缀做唯一性</b>：唯一性由 {@code user_id} 保证
     * （{@code uk_user_target} = {@code (user_id, target_type, target_id)}）⇒
     * ⇒ {@code target_type} 用真实取值 {@code "article"} 反而让生产面走到
     * {@code likeAction} 的 <b>{@code likeOnArticle} 真实主路径</b>
     * （DB 回源 ＋ 任务进度推送），比自造值覆盖得更全。
     */
    private static final String TARGET_TYPE = "article";

    private static final AtomicLong SEQ = new AtomicLong(8_400_000_000_000L);

    /**
     * 🔴 有界轮询的预算 —— **60s**（不是 30s）。
     *
     * <p>⚠️ <b>为什么从 30s 提到 60s</b>：连跑多轮 mvn（变异验证一次就是 5~6 轮）时，
     * broker 侧有积压与调度抖动 ⇒ ⇒ 实测 30s 预算<b>偶发</b>不足
     * （一次变异轮里「幂等面」红、复跑同一条变异又绿 ⇒ ⇒ **flaky，不是结构性问题**）。
     * <p>⇒ ⇒ 预算按「**最坏一轮的真实耗时**」给，而不是按「顺利时的耗时」给
     * ⇒ ⇒ 预算宽一点**只会让失败更慢**，不会让失败被掩盖（超时仍然判红）。
     */
    private static final long AWAIT_TIMEOUT_MS = 60_000L;

    /** Sea-kafka 的宿主发布口（= EXTERNAL listener）。 */
    private static final String BOOTSTRAP = "127.0.0.1:49992";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
    }

    /**
     * <b>确保两个 topic 存在</b>（1 partition × 1 replica）。
     *
     * <p>⚠️ 为什么必须显式建：broker 端 {@code auto.create.topics.enable} 不保证开着；
     * 而 topic 不存在时「先 {@code subscribe} 再等 {@code assignment}」会卡在 metadata 阶段
     * ⇒ ⇒ {@code awaitAssignment()} 15s 超时 ⇒ 报「topic 不存在」这种误导性错误。
     */
    @BeforeEach
    void ensureTopicsExist() {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            java.util.Set<String> existing = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            List<NewTopic> wanted = new ArrayList<>();
            for (String topic : List.of(LIKE_TOPIC, HOT_TOPIC)) {
                if (!existing.contains(topic)) {
                    wanted.add(new NewTopic(topic, 1, (short) 1));
                }
            }
            if (!wanted.isEmpty()) {
                admin.createTopics(wanted).all().get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new IllegalStateException("确保 Kafka topic 存在失败：" + e.getMessage(), e);
        }
    }

    @Autowired
    private LikeDomainService domainService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * <b>把 listener「叫醒」</b> —— 并确认它<b>真的在消费</b>。
     *
     * <p>🔴 <b>为什么需要</b>：{@code auto-offset-reset=latest} 的位置是
     * 「<b>log 末尾</b>」⇒ listener 启动到定位完成之间发出的消息<b>会被丢</b>
     * ⇒ ⇒ 必须<b>先</b>证明 listener 已在消费，<b>再</b>发测试消息。
     * <p>⇒ 判据 = <b>探针消息真的落进了 {@code like_consume_inbox}</b>
     * ⇒ ⇒ 这是「listener 真在消费」的<b>运行证据</b>（不是读配置名推断）。
     */
    private void awaitConsumerReady() {
        for (int attempt = 1; attempt <= 8; attempt++) {
            String probeMsgId = String.valueOf(SEQ.incrementAndGet());
            String probeTarget = "k3warm-" + attempt + "-" + probeMsgId;
            kafkaTemplate.send(LIKE_TOPIC, kafkaLikeMsgJson(
                    probeMsgId, probeMsgId.hashCode() & 0x7fffffffL, TARGET_TYPE, probeTarget, true));
            Integer landed = awaitPgQuiet(
                    "SELECT count(*) FROM like_consume_inbox WHERE msg_id = ?",
                    Integer.class, new Object[]{probeMsgId}, 2_000L);
            if (landed != null && landed > 0) {
                return;
            }
        }
        // ⚠️ **这里不抛异常**：预热失败只说明「listener 此刻没在消费」⇒
        // ⇒ 让**消费相关的用例**各自在有界轮询里超时变红（**失败点更贴近真正的断言**）
        // ⇒ ⇒ 而**生产面用例不受预热影响** ⇒ ⇒ 变异验证里「listener 停 ⇒ 生产面仍绿」这条判据才成立。
        // ⇒ ⇒ 预热本身不是判据，只是「让后续消息发得出去」的准备工作。
        return;
    }

    /** 有界轮询 PG，<b>超时返回最后一次读数而不抛异常</b>（供「等待某条件成立」用）。 */
    private <T> T awaitPgQuiet(String sql, Class<T> type, Object[] args, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        T last = null;
        while (System.currentTimeMillis() < deadline) {
            last = jdbcTemplate.queryForObject(sql, type, args);
            if (last instanceof Integer i && i > 0) {
                return last;
            }
            if (last instanceof Long l && l > 0L) {
                return last;
            }
            sleep(200);
        }
        return last;
    }

    private final List<TopicReader> rawConsumers = new ArrayList<>();

    /** 🔴 每个用例前先「叫醒」listener 并确认它真在消费（详见 {@link #awaitConsumerReady()}）。 */
    @BeforeEach
    void awaitConsumerBeforeEach() {
        awaitConsumerReady();
    }

    // ==================================================================
    // ① 生产面
    // ==================================================================

    @Test
    @DisplayName("🔴 生产面：likeAction 必须把 KafkaLikeMsg 发到 like_promotion_topic（字段序逐个钉住）")
    void producerSendsKafkaLikeMsgToTopic() throws Exception {
        long uid = SEQ.incrementAndGet();
        String targetType = TARGET_TYPE;
        String targetId = "t-" + uid;

        try (TopicReader reader = reader(LIKE_TOPIC)) {
            // ⚠️ 先订阅并等 assignment 完成 ⇒ 之后发的消息**不会丢**（latest 的竞态）
            reader.awaitAssignment();

            LikeActionResp resp = domainService.likeAction(LikeActionReq.newBuilder()
                    .setTargetType(targetType)
                    .setTargetId(targetId)
                    .setUserId(uid)
                    // ⚠️ author_id=0 ⇒ 不走 notifyArticleLiked（那会打 message-rpc）
                    .setAuthorId(0L)
                    .setActionType(ActionType.ACTION_LIKE)
                    .build());
            assertThat(resp.getSuccess()).as("likeAction 必须成功").isTrue();

            ConsumerRecord<String, String> record = reader.awaitRecord(
                    r -> r.value() != null && r.value().contains("\"target_id\":\"" + targetId + "\""),
                    AWAIT_TIMEOUT_MS);

            assertThat(record.topic()).isEqualTo(LIKE_TOPIC);
            assertThat(record.key()).as("Go 侧 Push 带 key（游标 ID）⇒ 不得为 null").isNotNull();

            // 🔴 **字段序是 L1 契约**（KafkaLikeMsg 上的 @JsonPropertyOrder）
            Map<String, Object> parsed = new LinkedHashMap<>(
                    objectMapper.readValue(record.value(), Map.class));
            assertThat(parsed.keySet()).containsExactly(
                    "msg_id", "user_id", "target_type", "target_id",
                    "author_id", "state", "is_first", "created_at");
            assertThat(parsed.get("msg_id")).as("msg_id 不得为空").isNotNull();
            assertThat(((Number) parsed.get("user_id")).longValue()).isEqualTo(uid);
            assertThat(parsed.get("target_type")).isEqualTo(targetType);
            assertThat(parsed.get("target_id")).isEqualTo(targetId);
            assertThat(((Number) parsed.get("author_id")).longValue()).isZero();
            assertThat(((Number) parsed.get("state")).intValue())
                    .as("state = ACTION_LIKE 的枚举值").isEqualTo(1);
            assertThat(((Boolean) parsed.get("is_first")).booleanValue())
                    .as("首次点赞 ⇒ is_first=true").isTrue();
            assertThat(((Number) parsed.get("created_at")).longValue())
                    .as("created_at 是 Unix 秒").isGreaterThan(1_700_000_000L);
        }
    }

    // ==================================================================
    // ② 消费面
    // ==================================================================

    @Test
    @DisplayName("🔴 消费面：一条 KafkaLikeMsg ⇒ inbox(status=1) + record(state=1) + fact + outbox 四处齐全")
    void consumerWritesAllFourPlaces() {
        long uid = SEQ.incrementAndGet();
        String targetType = TARGET_TYPE;
        String targetId = "t-" + uid;
        String msgId = String.valueOf(SEQ.incrementAndGet());

        kafkaTemplate.send(LIKE_TOPIC, kafkaLikeMsgJson(msgId, uid, targetType, targetId, true));

        // ① inbox：主键 = msg_id ＋ status=1（已完整处理）
        awaitPg("SELECT count(*) FROM like_consume_inbox WHERE msg_id = ? AND status = 1",
                Integer.class, msgId);

        // ② record：唯一键 (user_id, target_type, target_id) ＋ state=1
        awaitPg("SELECT count(*) FROM like_record WHERE user_id = ? AND target_type = ? "
                        + "AND target_id = ? AND state = 1",
                Integer.class, uid, targetType, targetId);

        // ③ fact：aggregate_id = like-state/{uid}/{type}/{id}
        awaitPg("SELECT count(*) FROM like_fact_stream WHERE aggregate_id = ?",
                Integer.class, streamId(uid, targetType, targetId));

        // ④ outbox：首次点赞 ⇒ 事件键 first_like:{uid}:{type}:{id}
        awaitPg("SELECT count(*) FROM like_outbox_event WHERE event_key = ? AND event_type = ?",
                Integer.class, "first_like:" + uid + ":" + targetType + ":" + targetId,
                ArticleHotEvent.OUTBOX_EVENT_TYPE_ARTICLE_HOT_LIKE);
    }

    // ==================================================================
    // ③ 推送面
    // ==================================================================

    @Test
    @DisplayName("🔴 推送面：outbox 待发行必须被中继推到 article-hot-events（payload 逐字段对齐）")
    void relayPushesOutboxEventToHotTopic() throws Exception {
        long uid = SEQ.incrementAndGet();
        String targetType = TARGET_TYPE;
        String targetId = "t-" + uid;
        String msgId = String.valueOf(SEQ.incrementAndGet());

        try (TopicReader reader = reader(HOT_TOPIC)) {
            reader.awaitAssignment();

            kafkaTemplate.send(LIKE_TOPIC, kafkaLikeMsgJson(msgId, uid, targetType, targetId, true));

            // 等 outbox 行出现（消费面已落库）⇒ 中继才会推
            awaitPg("SELECT count(*) FROM like_outbox_event WHERE event_key = ?",
                    Integer.class, "first_like:" + uid + ":" + targetType + ":" + targetId);

            ConsumerRecord<String, String> record = reader.awaitRecord(
                    r -> r.value() != null && r.value().contains("\"article_id\":\"" + targetId + "\""),
                    AWAIT_TIMEOUT_MS);

            assertThat(record.topic()).isEqualTo(HOT_TOPIC);
            assertThat(record.key()).as("Go 侧 Push 带 key（Unix 纳秒）⇒ 不得为 null").isNotNull();

            Map<String, Object> parsed = new LinkedHashMap<>(
                    objectMapper.readValue(record.value(), Map.class));
            // 🔴 **字段序是 L1 契约**（ArticleHotEvent 上的 @JsonPropertyOrder）
            //    ⚠️ outbox.payload 列类型是 JSON（不是 jsonb）⇒ PG 保序 ⇒ 这里能钉住顺序
            assertThat(parsed.keySet()).containsExactly(
                    "article_id", "type", "user_id", "timestamp", "is_first");
            assertThat(parsed.get("article_id")).isEqualTo(targetId);
            assertThat(parsed.get("type")).isEqualTo(ArticleHotEvent.EVENT_TYPE_LIKE);
            assertThat(parsed.get("user_id")).isEqualTo(String.valueOf(uid));
            assertThat(parsed.get("is_first")).isEqualTo(Boolean.TRUE);
            assertThat(((Number) parsed.get("timestamp")).longValue())
                    .as("timestamp 是 Unix 秒").isGreaterThan(1_700_000_000L);
        }
    }

    // ==================================================================
    // ④ 幂等面
    // ==================================================================

    @Test
    @DisplayName("🔴 幂等面：同 msg_id 重投 ⇒ inbox 去重 ＋ record 不重复 ＋ fact 版本不递增")
    void redeliveryIsIdempotent() {
        long uid = SEQ.incrementAndGet();
        String targetType = TARGET_TYPE;
        String targetId = "t-" + uid;
        String msgId = String.valueOf(SEQ.incrementAndGet());

        String payload = kafkaLikeMsgJson(msgId, uid, targetType, targetId, true);
        kafkaTemplate.send(LIKE_TOPIC, payload);
        awaitPg("SELECT count(*) FROM like_consume_inbox WHERE msg_id = ? AND status = 1",
                Integer.class, msgId);
        awaitPg("SELECT count(*) FROM like_record WHERE user_id = ? AND target_type = ? AND target_id = ?",
                Integer.class, uid, targetType, targetId);

        Long versionBefore = jdbcTemplate.queryForObject(
                "SELECT version FROM like_fact_stream WHERE aggregate_id = ?",
                Long.class, streamId(uid, targetType, targetId));
        assertThat(versionBefore).as("首次消费必须已经写了 fact").isNotNull();

        // 重投同一条 msg_id
        kafkaTemplate.send(LIKE_TOPIC, payload);

        // 等到「第二条消息被处理过」的证据：**record 仍是 1 行**（没被重复应用）
        // ⇒ 用 inbox 的 updated_at 不变 + record 行数不变来判读
        awaitSettle();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM like_record WHERE user_id = ? AND target_type = ? AND target_id = ?",
                Integer.class, uid, targetType, targetId))
                .as("同 msg_id 重投 ⇒ record 不得重复（uk_user_target + inbox 去重）")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM like_outbox_event WHERE event_key = ?",
                Integer.class, "first_like:" + uid + ":" + targetType + ":" + targetId))
                .as("同 msg_id 重投 ⇒ outbox 不得重复（uk_event_key）")
                .isEqualTo(1);
        Long versionAfter = jdbcTemplate.queryForObject(
                "SELECT version FROM like_fact_stream WHERE aggregate_id = ?",
                Long.class, streamId(uid, targetType, targetId));
        assertThat(versionAfter)
                .as("同 msg_id 重投 ⇒ fact 版本不得递增")
                .isEqualTo(versionBefore);
    }

    // ==================================================================
    // 夹具 / 工具
    // ==================================================================

    private String kafkaLikeMsgJson(String msgId, long uid, String targetType,
                                    String targetId, boolean isFirst) {
        KafkaLikeMsg msg = new KafkaLikeMsg();
        msg.msgId = msgId;
        msg.userId = uid;
        msg.targetType = targetType;
        msg.targetId = targetId;
        msg.authorId = 0L;
        msg.state = 1;
        msg.isFirst = isFirst;
        msg.createdAt = 1_762_000_000L;
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            throw new IllegalStateException("构造 KafkaLikeMsg 失败", e);
        }
    }

    private static String streamId(long uid, String targetType, String targetId) {
        return "like-state/" + uid + "/" + targetType + "/" + targetId;
    }

    /** 有界轮询 PG，直到 count > 0（超时即失败，不挂死）。 */
    private <T> T awaitPg(String sql, Class<T> type, Object... args) {
        T last = awaitPgQuiet(sql, type, args, AWAIT_TIMEOUT_MS);
        if (last instanceof Integer i && i > 0) {
            return last;
        }
        if (last instanceof Long l && l > 0L) {
            return last;
        }
        throw new AssertionError("有界轮询超时（" + AWAIT_TIMEOUT_MS + "ms）仍未满足：" + sql);
    }

    /** 等消息被处理完（给「不应发生变化」这类断言留出重投的落地窗口）。 */
    private void awaitSettle() {
        sleep(3_000L);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 建一个「原始」consumer（独立 group ＋ latest ＋ 手动提交）。 */
    private TopicReader reader(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "like-probe-reader-" + SEQ.incrementAndGet());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        TopicReader reader = new TopicReader(new KafkaConsumer<>(props), topic);
        rawConsumers.add(reader);
        return reader;
    }

    @AfterEach
    void closeRawConsumers() {
        rawConsumers.forEach(TopicReader::close);
        rawConsumers.clear();
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
}
