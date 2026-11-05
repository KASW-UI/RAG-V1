package com.rag.hot.messaging;

import com.rag.hot.domain.model.HeavyKeeper;
import com.rag.hot.support.HotKafkaTestBase;
import com.rag.hot.support.ThrowingKafkaListenerConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <p><b>真 Kafka 消费链路测试</b> —— hot 域 MQ 面的真触发点。
 *
 * <h2>🔴 本类验的是<b>四件在 mock 上验不到的事</b>（§3.3）</h2>
 * <ol>
 *   <li><b>listener 真进 registry</b> —— {@code @KafkaListener} 的
 *       {@code ${...}} 占位符是否真的解析到 yml 里的 topic / group，
 *       以及容器是否真的 {@code running}（Boot 的 {@code @EnableKafka} 装配是否生效）。</li>
 *   <li><b>分区分配回调真触发</b> —— {@code ConsumerRebalanceListener#onPartitionsAssigned}
 *       ⇒ {@code registry.assign} ⇒ 每个分区一个 sketch。这是 B 侧
 *       {@code OnPartitionsAssigned} 的对位物。</li>
 *   <li><b>真消费进指标 + sketch</b> —— 一条合法事件走完
 *       「反序列化 → 校验 → <b>先计数</b> → 查分区 → {@code hk.add}」全链路。</li>
 *   <li><b>坏消息零重试 drop</b> —— B 侧的无条件 {@code MarkMessage} 语义。</li>
 * </ol>
 *
 * <h2>⚠️ 断言的「先后顺序」是判据的一部分</h2>
 * <p>凡是「某条消息<b>没</b>被处理」的断言（未知 type / 坏 payload），
 * 都必须<b>先等到位点推进</b>（证明这条消息<b>确实被投递过</b>），
 * <b>再</b>断言「没进指标 / 没进 sketch」。
 * <p>⇒ 否则断言在「消息还没到」的时刻就成立 ⇒ ⇒ <b>假绿</b>。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧 {@code service/hot/hot-rpc/.../mq/HotEventConsumer.java}
 *       （校验顺序 / 先计数后查分区 / 无条件 MarkMessage）；</li>
 *   <li>B 侧 {@code .../mq/HotPartitionSketchRegistry.java}（{@code OnPartitionsAssigned} 建 sketch）；</li>
 *   <li>B 侧 {@code service/hot/hot-rpc/src/main/resources/application.yml}
 *       （topic / group / partition-count / ack-mode / auto-offset-reset）。</li>
 * </ul>
 */
class HotEventConsumerRealKafkaTest extends HotKafkaTestBase {

    /** 本测试类的 articleId 前缀（保证同一 JVM 内多类之间不串数据）。 */
    private static final String AUTHOR = "hot-kafka";

    /** 等待上限（消费是异步的）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** 指标注册表（读 {@code article_hot_kafka_consume_total}）。 */
    @Autowired
    private MeterRegistry meterRegistry;

    /** 真实的 listener 端点注册表 —— 证明 listener 真进了 registry。 */
    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    /** 真实的分区 sketch 注册表。 */
    @Autowired
    private HotPartitionSketchRegistry sketchRegistry;

    /**
     * 生产那个 {@code ConsumerFactory}（Spring Boot 装配出来的那个）。
     *
     * <p>⚠️ <b>必须是容器里这一个</b>：它承载的正是 yml 里的
     * {@code spring.kafka.consumer.properties.partition.assignment.strategy}
     * ⇒ ⇒ 用它建消费者才验得到「生产配置真的进了 ConsumerConfig」。</p>
     */
    @Autowired
    private ConsumerFactory<Object, Object> consumerFactory;

    // ==================================================================
    // ① listener 真进 registry
    // ==================================================================

    @Test
    @DisplayName("listener 真进 KafkaListenerEndpointRegistry，且容器在跑、消费组是 B 侧那个")
    void listenerReallyRegisteredAndRunning() {
        Collection<MessageListenerContainer> containers = listenerRegistry.getListenerContainers();
        assertThat(containers).isNotEmpty();

        MessageListenerContainer hot = containers.stream()
                .filter(c -> Arrays.asList(c.getContainerProperties().getTopics()).contains(TOPIC))
                .findFirst()
                .orElseGet(() -> {
                    fail("registry 里没有订阅 " + TOPIC + " 的容器；现有容器："
                            + containers.stream()
                            .map(c -> Arrays.toString(c.getContainerProperties().getTopics()))
                            .toList());
                    return null;
                });

        // 容器真的起来了（不是「Bean 存在」而是「容器 running」）
        awaitTrue("容器 " + TOPIC + " 进入 running",
                () -> hot.isRunning());

        // 🔴 L1 契约：消费组必须是 B 侧那个 group（不是随便一个 group）
        assertThat(hot.getContainerProperties().getGroupId()).isEqualTo(GROUP);
    }

    // ==================================================================
    // ② 分区分配回调真触发 ⇒ 每个分区一个 sketch
    // ==================================================================

    @Test
    @DisplayName("分区分配回调真触发：3 个分区各有一个 sketch")
    void partitionAssignmentReallyBuildsSketches() {
        awaitGroupStable(GROUP);

        awaitTrue("分区 sketch 数量达到 " + PARTITION_COUNT,
                () -> sketchRegistry.size() == PARTITION_COUNT);

        for (int pid = 0; pid < PARTITION_COUNT; pid++) {
            assertThat(sketchRegistry.heavyKeeperFor(pid))
                    .as("分区 %d 的 sketch（null ⇒ 说明分区没被 assign）", pid)
                    .isNotNull();
        }
    }

    // ==================================================================
    // ③ 真消费进指标 + sketch
    // ==================================================================

    @Test
    @DisplayName("合法事件真被消费：指标 +1，且 articleId 真进了该分区的 sketch（权重 1）")
    void validEventReallyConsumedIntoMetricAndSketch() {
        awaitGroupStable(GROUP);
        String articleId = AUTHOR + "-valid-like";
        double before = consumeCount("like");

        publish(TOPIC, 0, articleId, event(articleId, "like"));

        awaitTrue("like 类型的消费计数增加", () -> consumeCount("like") > before);

        HeavyKeeper hk = awaitSketchContains(0, articleId);
        HeavyKeeper.Item item = topKItem(hk, articleId);
        // 🔴 like 的权重 = 1（B 侧 Interaction.Weights）⇒ 计数必须是 1
        assertThat(item.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("权重按 Name 分发：comment 的权重是 2 ⇒ sketch 计数是 2")
    void weightIsDispatchedByEventTypeName() {
        awaitGroupStable(GROUP);
        String articleId = AUTHOR + "-weight-comment";
        double before = consumeCount("comment");

        publish(TOPIC, 1, articleId, event(articleId, "comment"));

        awaitTrue("comment 类型的消费计数增加", () -> consumeCount("comment") > before);

        HeavyKeeper hk = awaitSketchContains(1, articleId);
        HeavyKeeper.Item item = topKItem(hk, articleId);
        // 🔴 comment 的权重 = 2（B 侧 Interaction.Weights）⇒ 计数必须是 2
        assertThat(item.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("未知 type ⇒ drop：位点照常推进，但既不进指标也不进 sketch")
    void unknownTypeIsDroppedButOffsetStillAdvances() {
        awaitGroupStable(GROUP);
        String articleId = AUTHOR + "-unknown-type";
        double before = consumeCount("bogus-type");

        publish(TOPIC, 2, articleId, event(articleId, "bogus-type"));

        // ⚠️ **先等位点推进** —— 证明这条消息**确实被投递过**（否则下面的断言是假绿）
        awaitTrue("分区 2 的位点推进（消息确实被消费过了）",
                () -> committedOffsets(GROUP, TOPIC).getOrDefault(2, -1L) >= 1L);

        // 权重校验没过 ⇒ B 侧在 Inc 之前就返回 error ⇒ 指标里没有这个 type
        assertThat(consumeCount("bogus-type")).isEqualTo(before);
        assertThat(meterRegistry.find("article_hot_kafka_consume_total")
                .tag("type", "bogus-type").counter()).isNull();

        // 也没进 sketch
        HeavyKeeper hk = sketchRegistry.heavyKeeperFor(2);
        assertThat(hk).isNotNull();
        assertThat(topKItemOrNull(hk, articleId)).isNull();
    }

    // ==================================================================
    // ④ 坏消息零重试 drop
    // ==================================================================

    @Test
    @DisplayName("坏 payload ⇒ drop 且不卡分区：位点照常推进，后续消息照常消费")
    void badPayloadIsDroppedAndDoesNotBlockPartition() {
        awaitGroupStable(GROUP);
        String badId = AUTHOR + "-bad-payload";
        String goodId = AUTHOR + "-after-bad-payload";
        double beforeLike = consumeCount("like");

        publish(TOPIC, 2, badId, "{ this is not json");
        publish(TOPIC, 2, goodId, event(goodId, "like"));

        // 🔴 关键：坏消息**之后**的那条好消息仍被消费 ⇒ 分区没被卡住
        awaitTrue("坏消息之后的好消息仍被消费", () -> consumeCount("like") > beforeLike);

        HeavyKeeper hk = awaitSketchContains(2, goodId);
        assertThat(topKItemOrNull(hk, badId)).isNull();

        // 位点也照常推进（drop ≠ 卡住）
        awaitTrue("分区 2 的位点推进过坏消息",
                () -> committedOffsets(GROUP, TOPIC).getOrDefault(2, -1L) >= 2L);
    }

    @Test
    @DisplayName("容器的错误处理是零重试：失败记录只被投递 1 次（Boot 默认会是 10 次）")
    void containerDoesNotRetryFailedRecord() {
        awaitGroupStable(PROBE_GROUP);
        ThrowingKafkaListenerConfiguration.reset();

        publish(PROBE_TOPIC, 0, "probe", "boom");

        // ⚠️ 位点推进 ⇒ 错误处理器**已经处理完**这条记录（重试也用完了）
        //    ⇒ 此时的投递次数是**最终值**
        awaitTrue("探针 topic 的位点推进",
                () -> committedOffsets(PROBE_GROUP, PROBE_TOPIC).getOrDefault(0, -1L) >= 1L);

        // 🔴 零重试 ⇒ 只投递 1 次
        assertThat(ThrowingKafkaListenerConfiguration.deliveries())
                .as("失败记录的投递次数（零重试 ⇒ 1；Boot 默认 FixedBackOff(0,9) ⇒ 10）")
                .isEqualTo(1);
    }

    // ==================================================================
    // ⑤ 生产端多发字段（ignoreUnknown 的硬要求）
    // ==================================================================

    @Test
    @DisplayName("生产端发 4 个字段（多 user_id/timestamp）⇒ 真消费成功，不被当坏消息丢掉")
    void multiFieldPayloadIsNotDropped() {
        awaitGroupStable(GROUP);
        String articleId = AUTHOR + "-multi-field";
        double before = consumeCount("like");

        // 生产端实测发送的就是 4 个字段（article_id / type / user_id / timestamp）
        // ⇒ 载荷契约上「只有 2 个字段」的是**消费端**，不是**生产端**
        // ⇒ 若 @JsonIgnoreProperties(ignoreUnknown = true) 被去掉 ⇒ 这条会被 drop ⇒ 热榜永不更新
        publish(TOPIC, 0, articleId,
                "{\"article_id\":\"" + articleId + "\",\"type\":\"like\","
                        + "\"user_id\":\"u9\",\"timestamp\":123456}");

        awaitTrue("多字段消息被计数", () -> consumeCount("like") > before);

        HeavyKeeper hk = awaitSketchContains(0, articleId);
        assertThat(topKItem(hk, articleId).count()).isEqualTo(1);
    }

    // ==================================================================
    // ⑥ 分区分配策略真的落到了 consumer（不能只信 yml）
    // ==================================================================

    /**
     * 🔴 <b>RoundRobinAssignor 真的落到了 consumer</b>。
     *
     * <h2>为什么必须真跑一遍</h2>
     * <p>AGENTS.md §3.3.1：「配置里写了」≠「生效了」。本域的 yml 写了
     * {@code spring.kafka.consumer.properties.partition.assignment.strategy: RoundRobinAssignor}
     * ⇒ 若该键被 Boot 3.5 忽略 ⇒ <b>静默退回默认的 RangeAssignor</b> ⇒
     * ⇒ 分区归属改变 ⇒ ⇒ <b>每个分区对应的草图布局改变 ⇒ 热榜分布改变</b>
     * ⇒ ⇒ 纯读 yml 的断言<b>永远绿</b>。</p>
     *
     * <h2>判据为什么这么设计</h2>
     * <p>3 分区 2 消费者：RoundRobin 分成 <b>[0,2] / [1]</b>；Range 会分成 <b>[0,1] / [2]</b>
     * ⇒ ⇒ 两者结果<b>不同</b> ⇒ 断言对分配策略<b>敏感</b>。</p>
     * <p>且带一个 <b>RangeAssignor 对照组</b> ⇒ 证明本用例不是恒真：
     * 若两种策略结果相同 ⇒ 用例是假绿。</p>
     *
     * <h2>为什么用独立消费组</h2>
     * <p>探针消费者用<b>独立 group</b> ⇒ 不会抢生产组（{@code article-hot-group}）的分区
     * ⇒ 不会干扰上面那些用例。</p>
     *
     * <h2>B 出处</h2>
     * <p>B 侧 {@code .../mq/HotKafkaConsumerGateTest.java:297-322}（含 RangeAssignor 对照组）
     * ⇒ 本用例为 1:1 移植。</p>
     */
    @Test
    @DisplayName("RoundRobinAssignor 真的落到了 consumer（3 分区 2 消费者 ⇒ [0,2]/[1]；Range 会是 [0,1]/[2]）")
    void productionConsumerFactoryReallyUsesRoundRobinAssignor() {
        Map<String, Set<Integer>> roundRobin = assignmentOf("hot-rr-probe-group", null);

        assertEquals(Set.of(0, 2), roundRobin.get("c0"),
                "第一个消费者拿到的分区不是 [0,2]（实际 " + roundRobin.get("c0") + "）"
                        + " ⇒ RoundRobinAssignor 没落到 consumer，或被改成了别的分配策略");
        assertEquals(Set.of(1), roundRobin.get("c1"),
                "第二个消费者拿到的分区不是 [1]（实际 " + roundRobin.get("c1") + "）");

        // ── 对照组：换成 RangeAssignor，分配必须变成 [0,1]/[2] ──
        // 这一步证明本用例**对分配策略敏感**：若两种策略结果相同，断言就是假绿。
        Map<String, Set<Integer>> range = assignmentOf("hot-range-probe-group",
                RangeAssignor.class.getName());

        assertEquals(Set.of(0, 1), range.get("c0"),
                "RangeAssignor 对照组应得 [0,1]（实际 " + range.get("c0") + "）");
        assertEquals(Set.of(2), range.get("c1"),
                "RangeAssignor 对照组应得 [2]（实际 " + range.get("c1") + "）");
    }

    /**
     * 用<b>生产那个 ConsumerFactory</b> 建两个消费者，看它们各自拿到哪些分区。
     *
     * <p>⚠️ 用的就是 Spring 容器里那个 {@code ConsumerFactory} bean ⇒
     * 它承载的正是 yml 里的 {@code partition.assignment.strategy}
     * ⇒ ⇒ 本方法验的是「<b>生产配置</b>真的进了 ConsumerConfig」。</p>
     *
     * @param group            消费组（用独立组 ⇒ 不抢生产组的分区）
     * @param assignorOverride 分配策略覆盖（{@code null} ⇒ 用生产配置）
     * @return 消费者序号（"c0"/"c1"）→ 分区号集合
     */
    private Map<String, Set<Integer>> assignmentOf(String group, String assignorOverride) {
        List<Consumer<Object, Object>> consumers = new ArrayList<>();
        Map<String, Set<Integer>> result = new LinkedHashMap<>();
        try {
            for (int i = 0; i < 2; i++) {
                Properties override = null;
                if (assignorOverride != null) {
                    override = new Properties();
                    override.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, assignorOverride);
                }
                Consumer<Object, Object> consumer = consumerFactory.createConsumer(
                        group, "probe-" + group + "-" + i, null, override);
                consumer.subscribe(List.of(TOPIC));
                consumers.add(consumer);
            }

            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                int total = consumers.stream().mapToInt(c -> c.assignment().size()).sum();
                boolean allAssigned = consumers.stream().allMatch(c -> !c.assignment().isEmpty());
                if (allAssigned && total == PARTITION_COUNT) {
                    break;
                }
                for (Consumer<Object, Object> consumer : consumers) {
                    consumer.poll(Duration.ofMillis(200));
                }
            }

            for (int i = 0; i < consumers.size(); i++) {
                Set<Integer> partitions = new TreeSet<>();
                for (TopicPartition tp : consumers.get(i).assignment()) {
                    partitions.add(tp.partition());
                }
                result.put("c" + i, partitions);
            }
            return result;
        } finally {
            for (Consumer<Object, Object> consumer : consumers) {
                try {
                    consumer.close(Duration.ofSeconds(5));
                } catch (RuntimeException ignored) {
                    // 关闭失败不影响断言
                }
            }
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 读 {@code article_hot_kafka_consume_total{type=...}} 的计数值。
     *
     * @param type 事件类型
     * @return 计数值（指标还没出现时返回 0）
     */
    private double consumeCount(String type) {
        Counter counter = meterRegistry.find("article_hot_kafka_consume_total")
                .tag("type", type).counter();
        return counter == null ? 0d : counter.count();
    }

    /**
     * 构造一条合法事件的消息体。
     *
     * @param articleId 文章 id
     * @param type      事件类型
     * @return JSON 消息体
     */
    private static String event(String articleId, String type) {
        return "{\"article_id\":\"" + articleId + "\",\"type\":\"" + type + "\"}";
    }

    /**
     * 等某个分区的 sketch 里出现指定的 articleId。
     *
     * @param partitionId 分区号
     * @param articleId   文章 id
     * @return 该分区的 sketch
     */
    private HeavyKeeper awaitSketchContains(int partitionId, String articleId) {
        awaitTrue("分区 " + partitionId + " 的 sketch 里出现 " + articleId, () -> {
            HeavyKeeper hk = sketchRegistry.heavyKeeperFor(partitionId);
            return hk != null && topKItemOrNull(hk, articleId) != null;
        });
        return sketchRegistry.heavyKeeperFor(partitionId);
    }

    /**
     * 从 sketch 的 Top-K 里取指定条目。
     *
     * @param hk        sketch
     * @param articleId 文章 id
     * @return 条目
     */
    private static HeavyKeeper.Item topKItem(HeavyKeeper hk, String articleId) {
        HeavyKeeper.Item item = topKItemOrNull(hk, articleId);
        assertThat(item).as("sketch 里的条目 %s", articleId).isNotNull();
        return item;
    }

    /**
     * 从 sketch 的 Top-K 里取指定条目（不存在返回 {@code null}）。
     *
     * @param hk        sketch
     * @param articleId 文章 id
     * @return 条目或 {@code null}
     */
    private static HeavyKeeper.Item topKItemOrNull(HeavyKeeper hk, String articleId) {
        if (hk == null) {
            return null;
        }
        return hk.topK().stream()
                .filter(i -> articleId.equals(i.key()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 等一个条件成立（超时即失败）。
     *
     * @param what 条件描述（失败时进报告）
     * @param cond 条件
     */
    private static void awaitTrue(String what, BooleanSupplier cond) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            sleep(100);
        }
        fail("等待超时（" + TIMEOUT.toSeconds() + "s）：" + what);
    }
}
