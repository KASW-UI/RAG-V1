package com.rag.hot.support;

import com.rag.hot.HotServiceApplication;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p><b>真 Kafka 容器 + 真 Redis + 真 Spring 上下文</b> —— hot 域「MQ 消息链路」测试的公共底座。
 *
 * <h2>🔴 为什么必须跑<b>真 Kafka</b>（不能用 mock consumer）</h2>
 * <p>AGENTS.md §3.3：「<b>纯 Mockito 单测不算真触发</b>」。而 MQ 组件里最要命的恰恰是
 * <b>只有在真 broker 上才显形</b>的东西：
 * <ul>
 *   <li><b>ack 契约方向</b>：B 侧（sarama）是「handler 返回 error ⇒ 仍然 {@code MarkMessage}」
 *       ⇒ <b>坏消息静默丢弃、位点照常推进、永不重试</b>；
 *       Spring Kafka 恰好相反（「listener 正常返回 ⇒ 提交位点；抛异常 ⇒ <b>重试 9 次</b>后跳过」）
 *       ⇒ ⇒ 方向写反在 mock 上<b>看不出来</b>（mock 只看「方法被调用了没」）。</li>
 *   <li><b>位点提交 / 重投节奏</b>：由容器工厂的 {@code AckMode.RECORD} ＋
 *       {@code FixedBackOff(0,0)} ＋ {@code setAckAfterHandle(true)} 共同决定
 *       ⇒ 只有真容器里跑才知道「失败消息到底被投递几次」「位点停在哪」。</li>
 *   <li><b>topic / group 的真实绑定</b>：{@code @KafkaListener} 的 {@code ${...}} 占位符
 *       是否真的解析到 yml 里的 topic 名与 group id，只有真 broker 才会暴露。</li>
 *   <li><b>分区分配回调</b>：{@code ConsumerRebalanceListener} 只有在<b>真的 join 了消费组</b>
 *       之后才会触发 ⇒ ⇒ 「{@code onPartitionsAssigned} 建 sketch」这条链路的真触发点。</li>
 * </ul>
 *
 * <h2>🔴 为什么<b>不</b>覆盖 {@code auto-offset-reset}</h2>
 * <p>B 侧的 L1 语义是 {@code sarama.OffsetNewest} ⇒ C 侧 yml 写的是
 * {@code spring.kafka.consumer.auto-offset-reset: latest}（<b>新 group 从最新位点开始、不回放历史</b>）。
 * <p>⇒ 本底座<b>刻意不覆盖</b>它 ⇒ ⇒ 测的就是<b>生产取值本身</b>。
 * <p>⇒ 代价是「发布消息」必须发生在<b>消费组已经拿到分区之后</b>，否则消息会被 {@code latest}
 * 跳过 ⇒ ⇒ 本底座提供 {@link #awaitGroupStable(String)}：等消费组进入 {@code STABLE}
 * 之后再额外等 1s（等首次 fetch 把位点钉到日志末尾）⇒ ⇒ 之后发布的消息一定被消费。
 *
 * <h2>为什么 topic 是 <b>3 分区</b></h2>
 * <p>{@code hot-rpc.interaction.partition-count: 3}（B 侧 {@code Interaction.PartitionCount}）
 * ⇒ 合并只读 {@code hot:articles:p0..p2} ⇒ <b>topic 的实际分区数必须等于它</b>
 * （否则多出来的分片永远不参与合并）⇒ ⇒ 测试 topic 建 3 分区 ⇒ 与生产配置一致。
 *
 * <h2>为什么 Kafka 容器是<b>单例</b></h2>
 * <p>本域有多个 {@code @SpringBootTest} 上下文 ⇒ 每个类各起一个容器 ⇒ 慢 3 倍
 * 且机器上同时跑多个 broker ⇒ ⇒ 单例：<b>整个 JVM 起一次</b>，各测试类共用同一个 broker。
 * <p>⚠️ <b>共用 broker ⇒ 数据必须互不冲突</b> ⇒ 各测试类用<b>唯一 articleId</b>
 * （见各测试类的 {@code AUTHOR} 前缀）。
 *
 * <h2>为什么 Redis 也是<b>真容器</b></h2>
 * <p>分区分配回调 {@code onPartitionsAssigned} 会走
 * {@code HotRedisSnapshotStore#loadInto}（真 Redis I/O）⇒ Redis 不可达时
 * rebalance 回调会抛异常 ⇒ 消费组起不来 ⇒ ⇒ Redis 必须真。
 *
 * <h2>为什么 {@code grpc.client.article-rpc.address} 要指向进程内 gRPC</h2>
 * <p>devh 的 {@code @GrpcClient} stub 在 <b>Bean 初始化期</b>就会建 channel
 * ⇒ 默认的 {@code discovery:///article-rpc} 会被 Nacos 解析（不可达）⇒ 上下文起不来
 * ⇒ ⇒ 指向 {@code in-process:article-rpc-test}（{@link ArticleRpcInProcessServer} 起的真 gRPC server）。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧 topic / group / 分区数：{@code service/hot/hot-rpc/src/main/resources/application.yml}
 *       （{@code hot-rpc.kafka.topic} / {@code spring.kafka.consumer.group-id} /
 *       {@code hot-rpc.interaction.partition-count}）；</li>
 *   <li>B 侧 ack / 丢弃语义：{@code service/hot/hot-rpc/.../mq/HotEventConsumer.java}
 *       （消费循环无条件 {@code sess.MarkMessage(msg, "")}）；</li>
 *   <li>B 侧分区生命周期：{@code .../mq/HotPartitionSketchRegistry.java}（{@code OnPartitionsAssigned}）。</li>
 * </ul>
 */
@SpringBootTest(
        classes = {HotServiceApplication.class, NacosStubConfiguration.class,
                ThrowingKafkaListenerConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // ── Nacos 不触网 ──
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "logging.level.com.alibaba.nacos=WARN",
                // ── 追踪不外发 ──
                "management.otlp.tracing.export.enabled=false",
                // ⚠️ 共享层的 OTLP exporter 也**关掉**（端点为空 ⇒ 按 B 侧口径不装 exporter）：
                //    本域的 application.yml 里 otel 段是**真配了**的（生产要导出到 jaeger）⇒
                //    ⇒ 测试环境若不关 ⇒ 每次跑测试都去连 jaeger:4317（不可达）⇒ 日志噪音。
                //    ⇒ 「配了 ⇒ 真的装上了 exporter」由 HotOtelExporterWiringTest 单独钉。
                "otel.exporter.otlp.endpoint=",
                // ── gRPC 端口交给 OS（devh 的 gRPC server 是 SmartLifecycle ⇒ 启动即 bind）──
                "grpc.server.port=0",
                // ⚠️ **不覆盖 spring.kafka.consumer.auto-offset-reset** ⇒ 保留生产的 latest
                //    （见类注释「为什么不覆盖 auto-offset-reset」）。
                // ⚠️ producer 封顶：broker 不可达时 send() 会阻塞到 max.block.ms（默认 60s）
                "spring.kafka.producer.properties.max.block.ms=5000",
                // ── 关掉 CPU 自适应降载：本底座不验 HTTP 面，留着只会抢判据 ──
                "rag.protection.cpu-shedding.enabled=false"
        })
public abstract class HotKafkaTestBase {

    /**
     * 🔴 <b>真 Kafka</b>（进程内单例容器）。
     *
     * <p>⚠️ 固定用 {@code apache/kafka:3.7.2}：本机 Docker Hub 直连不通，
     * 而该镜像<b>本地已有</b>（Testcontainers 默认拉取策略是「本地有就不拉」）。
     */
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.2"));

    /** 真 topic 名 —— <b>逐字取自 application.yml</b>（B 侧 yaml 同名，L1 契约）。 */
    public static final String TOPIC = "article-hot-events";

    /** 真消费组 id —— <b>逐字取自 application.yml</b>（B 侧 yaml 同名，L1 契约）。 */
    public static final String GROUP = "article-hot-group";

    /** topic 分区数 —— 与 {@code hot-rpc.interaction.partition-count} 一致（3）。 */
    public static final int PARTITION_COUNT = 3;

    /** 探针 topic：{@link ThrowingKafkaListenerConfiguration} 用它验「零重试」。 */
    public static final String PROBE_TOPIC = ThrowingKafkaListenerConfiguration.TOPIC;

    /** 探针消费组 id。 */
    public static final String PROBE_GROUP = ThrowingKafkaListenerConfiguration.GROUP;

    static {
        KAFKA.start();
        createTopics();
    }

    /**
     * <b>显式建 topic</b> —— 不依赖 broker 的 {@code auto.create.topics.enable}。
     *
     * <p>⚠️ <b>分区数 = {@value #PARTITION_COUNT}</b>：必须等于
     * {@code hot-rpc.interaction.partition-count}（3）⇒ 否则合并只读 p0..p2 时
     * 多出来的分片永远不参与合并（与生产配置不一致）。
     */
    private static void createTopics() {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers()))) {
            List<NewTopic> topics = List.of(
                    new NewTopic(TOPIC, PARTITION_COUNT, (short) 1),
                    new NewTopic(PROBE_TOPIC, 1, (short) 1));
            await(admin.createTopics(topics).all(), 60,
                    "创建 Kafka topic 失败：" + TOPIC + " / " + PROBE_TOPIC);
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
     * 把容器连接信息注入 Spring 环境。
     *
     * @param registry Spring 的动态属性注册表
     */
    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", HotKafkaTestBase::bootstrapServers);
        TestRedis.register(registry);
        // ⚠️ devh 在 Bean 初始化期建 channel ⇒ discovery:// 解析不了 ⇒ 上下文起不来
        registry.add("grpc.client.article-rpc.address",
                () -> "in-process:" + ArticleRpcInProcessServer.IN_PROCESS_NAME);
    }

    // ==================================================================
    // 基础设施访问器
    // ==================================================================

    /** 真 broker 的 bootstrap 地址（宿主机映射口）。 */
    public static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /**
     * 投递一条消息到<b>指定分区</b>（同步等待 ack ⇒ 返回即代表 broker 已收下）。
     *
     * <p>⚠️ <b>必须能指定分区</b>：断言要「消息进了哪个分区的 sketch」⇒
     * 若靠 key 的哈希去猜分区，断言就依赖 hash 实现细节 ⇒ 不稳。
     *
     * @param topic     目标 topic
     * @param partition 分区号
     * @param key       消息 key
     * @param value     消息体
     */
    public static void publish(String topic, int partition, String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"))) {
            producer.send(new ProducerRecord<>(topic, partition, key, value))
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("投递消息到 " + topic + " 被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("投递消息到 " + topic + " 失败", e);
        }
    }

    /**
     * 投递一条消息到真 Kafka（分区由 key 哈希决定）。
     *
     * @param topic 目标 topic
     * @param key   消息 key
     * @param value 消息体
     */
    public static void publish(String topic, String key, String value) {
        publish(topic, -1, key, value);
    }

    /**
     * <p>取某个消费组在某个 topic 上的<b>已提交位点</b>（证明消息真的被消费并提交了位点）。
     *
     * @param group 消费组
     * @param topic topic
     * @return 该 topic 上所有分区的<b>最大</b>已提交位点（没有则 -1）
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
     * <p>取某个消费组在某个 topic 上<b>每个分区</b>的已提交位点。
     *
     * <p>⚠️ 必须<b>按分区</b>读：断言要证明「<b>某个指定分区</b>上的那条消息被消费了」
     * ⇒ 用「所有分区的最大值」会<b>被别的分区污染</b>（别的分区推进了位点，
     * 本分区没推进也会看起来「推进了」）⇒ ⇒ 那是<b>假绿</b>。
     *
     * @param group 消费组
     * @param topic topic
     * @return 分区号 → 已提交位点（没有该分区则不出现）
     */
    public static Map<Integer, Long> committedOffsets(String group, String topic) {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers()))) {
            var offsets = await(admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata(), 30,
                    "读取消费组 " + group + " 的已提交位点失败");
            Map<Integer, Long> out = new HashMap<>();
            for (var entry : offsets.entrySet()) {
                if (entry.getKey().topic().equals(topic) && entry.getValue() != null) {
                    out.put(entry.getKey().partition(), entry.getValue().offset());
                }
            }
            return out;
        }
    }

    /**
     * <p><b>等消费组进入 {@code STABLE}</b>（已拿到分区、rebalance 结束），
     * 之后再等 1s 让首次 fetch 把位点钉到日志末尾（{@code auto-offset-reset=latest}）。
     *
     * <p>⚠️ 这是<b>唯一</b>的「等一下」：若在消费组 join 之前发布消息，
     * {@code latest} 会把位点钉在消息<b>之后</b> ⇒ 消息被跳过 ⇒ 用例假红。
     *
     * @param group 消费组
     * @throws IllegalStateException 超时未进入 STABLE
     */
    public static void awaitGroupStable(String group) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers()))) {
                var described = await(admin.describeConsumerGroups(List.of(group))
                        .all(), 30, "读取消费组 " + group + " 状态失败");
                var desc = described.get(group);
                if (desc != null
                        && org.apache.kafka.common.ConsumerGroupState.STABLE.equals(desc.state())
                        && !desc.members().isEmpty()) {
                    // ⚠️ 等首次 fetch 把位点钉到日志末尾（latest 语义）
                    sleep(1_000);
                    return;
                }
            } catch (RuntimeException e) {
                last = e;
            }
            sleep(200);
        }
        throw new IllegalStateException("消费组 " + group + " 未在 60s 内进入 Stable 状态"
                + (last == null ? "" : "（最后一次错误：" + last.getMessage() + "）"));
    }

    /**
     * 把某个 topic 从头读一遍（用于断言「到底往 Kafka 推了什么」）。
     *
     * <p>⚠️ 用<b>一次性随机 group</b> ＋ {@code auto.offset.reset=earliest}
     * ⇒ 每次调用都能读到该 topic 的<b>全部历史消息</b>（不依赖消费组位点）。
     *
     * @param topic   目标 topic
     * @param timeout 收集时长
     * @return 读到的全部消息（按到达顺序）
     */
    public static List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> drain(
            String topic, Duration timeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "hot-test-drain-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> out = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            // ⚠️ **第一轮的结果绝不能丢** —— 实测（article 域 2026-11-03）：消息常常就在这一轮里返回
            var firstRound = consumer.poll(Duration.ofMillis(500));
            firstRound.forEach(out::add);
            int emptyRounds = 0;
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(300));
                if (records.isEmpty()) {
                    emptyRounds++;
                    // ⚠️ 只有在**已经拿到分区分配**之后，连续两轮空才认为「读完了」
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
     * 等一个 Kafka 的 future 完成（把受检异常包成 unchecked，方便测试里直接调用）。
     *
     * @param future  Kafka 的 future
     * @param seconds 超时秒数
     * @param message 超时/失败时的文案
     * @param <T>     结果类型
     * @return future 的结果
     */
    private static <T> T await(java.util.concurrent.Future<T> future, int seconds, String message) {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message + "（被中断）", e);
        } catch (Exception e) {
            throw new IllegalStateException(message, e);
        }
    }

    /**
     * 睡一会儿（毫秒）。
     *
     * @param millis 毫秒数
     */
    protected static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}
