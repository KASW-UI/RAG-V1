package com.rag.article.support;

import com.rag.article.ArticleServiceApplication;
import com.rag.article.client.ArticleObjectStorageClient;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p><b>真 Kafka 容器 + 真 PostgreSQL + 真 Spring 上下文</b> —— 本仓 article 域
 * 「MQ 消息链路」测试的公共底座。
 *
 * <h2>🔴 为什么必须跑<b>真 Kafka</b>（不能用 mock consumer）</h2>
 * <p>AGENTS.md §3.3 明确：「<b>纯 Mockito 单测不算真触发</b>」，
 * 而 MQ 组件里最要命的恰恰是<b>只有在真 broker 上才显形</b>的东西：
 * <ul>
 *   <li><b>ack 契约方向</b>：Go 是「handler 返回 error ⇒ 不确认位点」，
 *       Spring Kafka 恰好相反（「listener 正常返回 ⇒ 提交位点；抛异常 ⇒ 不提交」）。
 *       ⇒ 两条消费者各有<b>五条「必须丢」分支</b>与<b>三条「必须重投」分支</b>；
 *       任何一条方向写反，在 mock 上<b>看不出来</b>（mock 只看「方法被调用了没」）。</li>
 *   <li><b>位点提交 / 重投节奏</b>：由容器工厂的 {@code AckMode.RECORD} ＋
 *       {@code FixedBackOff(0,0)} 共同决定 ⇒ 只有真容器里跑才知道「失败消息到底被投递几次」。</li>
 *   <li><b>topic / group 的真实绑定</b>：{@code @KafkaListener} 的
 *       {@code ${...}} 占位符是否真的解析到 yml 里的 topic 名，
 *       只有真 broker 才会因「订阅了不存在的 topic」而暴露。</li>
 * </ul>
 * ⇒ ⇒ 本基类起 <b>apache/kafka:3.7.2</b>（本机镜像已就位），
 * 用<b>真实 topic 名</b>（取自 {@code application.yml}）与<b>真实 group id</b>投递。
 *
 * <h2>为什么 Kafka 容器是<b>单例</b>（而不是每个测试类一个）</h2>
 * <p>本域有多个 {@code @SpringBootTest} 上下文 ⇒ 每个类各起一个容器 ⇒ 慢 3 倍
 * 且机器上同时跑多个 broker（本机 24GB，JVM 多）。
 * ⇒ ⇒ 单例：<b>整个 JVM 起一次</b>，各测试类共用同一个 broker。
 * <p>⚠️ <b>共用 broker ⇒ 数据必须互不冲突</b> ⇒ 各测试类用<b>唯一 uid</b>
 * （带类名前缀的号段，见各测试类的 {@code AUTHOR} 常量）。
 *
 * <h2>⚠️ 为什么 MinIO（对象存储）被 mock 掉 —— 这是本基类<b>唯一</b>的桩</h2>
 * <p>本机<b>没有 MinIO 镜像</b>（只有 kafka / postgres）⇒ 而审核消费者的分支顺序是
 * <b>「先读正文（MinIO）→ 再跑内容审核」</b>（B 侧 {@code ArticleReviewConsumer.java:181-203}）
 * ⇒ 不给一个能读出正文的存储实现，就<b>永远走不到审核分支</b>，
 * 「审核恒 fail-loud ⇒ 恒不写 outbox」这条判据就<b>测不到</b>（会被 MinIO 的失败提前短路）。
 * <p>⇒ 故用 {@link MockitoBean} 顶掉 {@link ArticleObjectStorageClient}。
 * <p>⚠️ <b>被 mock 的只是「远端对象存储」这一个外部依赖</b>；
 * <b>被测机制（Kafka listener / 容器工厂 / 错误处理器 / 消费者业务分支）
 * 全部是真的</b> ⇒ 这不是「纯 Mockito 单测」。
 * <p>⚠️ <b>未 mock 的是 {@code ContentAuditPort}</b>：容器里注册的是<b>生产装配</b>的
 * {@code UnavailableContentAuditClient}（由域内 {@code .imports} 自动装配）⇒
 * 「审核恒 fail-loud」测的是<b>真装配</b>，不是替身。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧真 Kafka 守门测试
 *       {@code service/article/article-rpc/src/test/java/com/rag/article/article/rpc/mq/ArticleKafkaForceCommitGateTest.java:148-215}
 *       （容器镜像 {@code apache/kafka:3.7.2} · 四个通道 brokers 必须与全局同值 · topic 显式创建）；</li>
 *   <li>B 侧消费者分支测试
 *       {@code .../mq/ArticleReviewConsumerTest.java:110-226}（五条丢 / 三条重投的方向判据）；</li>
 *   <li>B 侧事件契约 {@code .../mq/ArticleSyncEventTypes.java:39-140}。</li>
 * </ul>
 *
 * <h2>可证伪性</h2>
 * <ul>
 *   <li>把 {@code ArticleReviewConsumer#onMessage} 的「丢」分支改成抛异常 ⇒
 *       「四元组不齐 ⇒ 丢弃」用例立刻红（消息会被重投 / 日志形态变化）；</li>
 *   <li>把容器的 {@code AckMode} 改成 {@code BATCH} 或把 {@code FixedBackOff} 改成
 *       {@code (0,9)} ⇒ 「失败消息只投递一次」用例立刻红；</li>
 *   <li>把 {@code article-rpc.kq-consumer-conf.topic} 改掉 ⇒
 *       「消费者真的订阅了真 topic」立刻红（消息投不出去 / 消费组无位点推进）。</li>
 * </ul>
 */
@SpringBootTest(
        classes = {ArticleServiceApplication.class, NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                // ⚠️ gRPC 端口交由 OS 分配（固定端口会与并行跑的其它 worker 撞 BindException）
                "grpc.server.port=0",
                // ⚠️ 消费者必须从最早开始消费（与 B 侧 KqConsumerConf.Offset=first 同构）
                "spring.kafka.consumer.auto-offset-reset=earliest",
                // ⚠️ producer 封顶：broker 不可达时 send() 会阻塞到 max.block.ms（默认 60s）
                "spring.kafka.producer.properties.max.block.ms=5000",
                // ⚠️ relay 的轮询间隔压到 300ms ⇒ @Scheduled 机制可在秒级被观测到
                "article-rpc.article-sync-outbox.poll-interval-ms=300",
                "article-rpc.minio.endpoint=127.0.0.1:1"
        })
public abstract class KafkaTestBase {

    /**
     * 🔴 <b>真 Kafka</b>（进程内单例容器）。
     *
     * <p>⚠️ 固定用 {@code apache/kafka:3.7.2}：本机 Docker Hub 直连不通，
     * 而该镜像<b>本地已有</b>（Testcontainers 默认拉取策略是「本地有就不拉」）。
     *
     * <p>⚠️ <b>不钉宿主口</b>：宿主口只被本类自己用
     * （{@link #bootstrapServers()} 永远回读真实映射口）⇒ 不存在「回话的不是我的容器」的风险。
     */
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.2"));

    /**
     * 真实 topic 名 —— <b>逐字取自 {@code application.yml}</b>（B 侧 yaml 同名，L1 契约）。
     *
     * <p>⚠️ <b>不自造 topic 名</b>：自造 ⇒ 测的是「另一个 topic 上的消费者」，
     * 而生产环境用的是 {@code article-creation-topic}（B 侧 {@code article.yaml:49}）。
     */
    public static final String TOPIC_REVIEW = "article-creation-topic";

    /** 同步事件出站 topic（B 侧 {@code article.yaml:56-57}）。 */
    public static final String TOPIC_SYNC_EVENTS = "article-sync-events";

    /** 同步结果入站 topic（B 侧 {@code article.yaml:62-63}）。 */
    public static final String TOPIC_SYNC_RESULTS = "article-sync-results";

    /** 审核消费者的真实 group id（B 侧 yaml 是 {@code dataclean}）。 */
    public static final String GROUP_REVIEW = "dataclean";

    /** 同步结果消费者的真实 group id（B 侧 yaml 是 {@code article-sync-result}）。 */
    public static final String GROUP_SYNC_RESULT = "article-sync-result";

    static {
        KAFKA.start();
        createTopics();
    }

    /**
     * <b>显式建 topic</b> —— 不依赖 broker 的 {@code auto.create.topics.enable}（不同镜像/配置不一样）。
     *
     * <p>⚠️ 单分区：生产的 topic 分区数由运维决定，本测试只需要「消息能按序进去」⇒ 1 分区足够，
     * 且单分区让「位点推进」这类断言更直观。
     */
    private static void createTopics() {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers()))) {
            List<NewTopic> topics = List.of(
                    new NewTopic(TOPIC_REVIEW, 1, (short) 1),
                    new NewTopic(TOPIC_SYNC_EVENTS, 1, (short) 1),
                    new NewTopic(TOPIC_SYNC_RESULTS, 1, (short) 1));
            await(admin.createTopics(topics).all(), 60,
                    "创建 Kafka topic 失败：" + TOPIC_REVIEW + " / " + TOPIC_SYNC_EVENTS
                            + " / " + TOPIC_SYNC_RESULTS);
        } catch (Exception e) {
            // topic 已存在（同一 JVM 重跑）⇒ 不致命
            boolean exists = e.getClass().getSimpleName().contains("TopicExists")
                    || (e.getCause() != null
                    && e.getCause().getClass().getSimpleName().contains("TopicExists"));
            if (!exists) {
                throw e;
            }
        }
    }

    /**
     * 本机 MinIO <b>没有镜像</b> ⇒ 审核消费者的「读正文」这一步必须被顶掉，
     * 否则永远走不到「内容审核」分支（见类级说明）。
     *
     * <p>⚠️ <b>只 mock 存储客户端</b>；{@code ContentAuditPort} 保持<b>生产装配</b>。
     */
    @MockitoBean
    protected ArticleObjectStorageClient articleObjectStorageClient;

    /**
     * 把容器连接信息与本域必须的配置注入 Spring 环境。
     *
     * <p>⚠️ <b>四个 Kafka 通道的 brokers 必须与全局 {@code spring.kafka.bootstrap-servers} 同值</b> ——
     * {@code ArticleKafkaChannelValidator} 在启动时比对二者，<b>不一致就启动失败</b>。
     */
    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaTestBase::bootstrapServers);
        registry.add("article-rpc.kq-pusher-conf.brokers", KafkaTestBase::bootstrapServers);
        registry.add("article-rpc.kq-consumer-conf.brokers", KafkaTestBase::bootstrapServers);
        registry.add("article-rpc.article-sync-pusher-conf.brokers", KafkaTestBase::bootstrapServers);
        registry.add("article-rpc.article-sync-result-consumer-conf.brokers",
                KafkaTestBase::bootstrapServers);
        TestPostgres.register(registry);
        // 🔴 裸占位符必须能被解析（application.yml:35 / :221 / :305）
        registry.add("POSTGRES_PASSWORD", () -> "password");
        registry.add("USER_JWT_SECRET", () -> "test-only-article-jwt-secret-at-least-32-bytes-long");
        // ⚠️ devh 在 Bean 初始化期建 channel ⇒ discovery:// 解析不了 ⇒ 上下文起不来
        registry.add("grpc.client.message-rpc.address", () -> "static://127.0.0.1:1");
    }

    // ==================================================================
    // 基础设施访问器
    // ==================================================================

    /** 真 broker 的 bootstrap 地址（宿主机映射口）。 */
    public static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /**
     * 投递一条消息到真 Kafka（同步等待 ack ⇒ 返回即代表 broker 已收下）。
     *
     * @param topic 目标 topic
     * @param key   消息 key（relay 用 {@code aggregate_id} ⇒ 同文章事件分区内有序）
     * @param value 消息体
     */
    public static void publish(String topic, String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"))) {
            producer.send(new ProducerRecord<>(topic, key, value))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("投递消息到 " + topic + " 被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("投递消息到 " + topic + " 失败", e);
        }
    }

    /**
     * 把某个 topic 从头读一遍（用于断言「relay 到底往 Kafka 推了什么」）。
     *
     * <p>⚠️ 用<b>一次性随机 group</b> ＋ {@code auto.offset.reset=earliest}
     * ⇒ 每次调用都能读到该 topic 的<b>全部历史消息</b>（不依赖消费组位点）。
     *
     * @param topic   目标 topic
     * @param timeout 收集时长
     * @return 读到的全部消息（按到达顺序）
     */
    public static List<ConsumerRecord<String, String>> drain(String topic, Duration timeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "article-test-drain-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            // ⚠️ 必须先 poll 一次让 group 完成 join，否则 subscribe 后的第一次 poll 可能什么都不返回
            // 🔴 **但这一轮的结果绝不能丢** —— 实测（2026-11-03）：消息**常常就在这一轮里**返回
            //   ⇒ 丢掉它 ⇒ 后面再轮询时位点已过 ⇒ **读到空列表** ⇒ 一条「relay 没投递」的假结论。
            //   ⇒ ⇒ 旧实现「poll 完丢弃 + 下一轮空就 break」有**两处**缺陷：
            //     ① 第一轮的消息被丢；② 第一轮空就 break —— 而此时 group 可能**还没拿到分区分配**
            //        ⇒ 同样读到空列表。
            var firstRound = consumer.poll(Duration.ofMillis(500));
            firstRound.forEach(out::add);
            int emptyRounds = 0;
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(300));
                if (records.isEmpty()) {
                    emptyRounds++;
                    // ⚠️ 只有在**已经拿到分区分配**之后，连续两轮空才认为「读完了」
                    //   ⇒ 否则会误判成「topic 上没有消息」
                    if (emptyRounds >= 2 && !consumer.assignment().isEmpty()) {
                        break;
                    }
                    continue;
                }
                emptyRounds = 0;
                records.forEach(out::add);
            }
        }
        return out;
    }

    /**
     * 取某个消费组在某个 topic 上的<b>已提交位点</b>（证明消息真的被消费并提交了位点）。
     *
     * @param group 消费组
     * @param topic topic
     * @return 该 topic 的已提交位点（没有则 -1）
     */
    public static long committedOffset(String group, String topic) {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers()))) {
            var offsets = await(admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata(), 30,
                    "读取消费组 " + group + " 的已提交位点失败");
            long best = -1L;
            for (var entry : offsets.entrySet()) {
                if (entry.getKey().topic().equals(topic) && entry.getValue() != null) {
                    best = Math.max(best, entry.getValue().offset());
                }
            }
            return best;
        }
    }

    /**
     * 等一个 Kafka 的 future 完成（把受检异常包成 unchecked，方便测试里直接调用）。
     *
     * @param future    Kafka 的 future
     * @param seconds   超时秒数
     * @param message   超时/失败时的文案
     * @param <T>       结果类型
     * @return future 的结果
     */
    private static <T> T await(java.util.concurrent.Future<T> future, int seconds, String message) {
        try {
            return future.get(seconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message + "（被中断）", e);
        } catch (Exception e) {
            throw new IllegalStateException(message, e);
        }
    }

    /**
     * 取某个类的 logger（用于挂 logback 的 ListAppender 断言运行期日志）。
     *
     * @param type 类
     * @return logger
     */
    protected static ch.qos.logback.classic.Logger loggerOf(Class<?> type) {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
    }
}
