package com.rag.hot.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.hot.config.HotRpcProperties;
import com.rag.hot.domain.model.HeavyKeeper;
import com.rag.hot.metrics.HotRpcMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <p><b>消费入口的「指标计数次序」与「drop 契约」</b> —— 纯单测（不连 Kafka / Redis）。</p>
 *
 * <h2>🔴 为什么这组判据要有</h2>
 * <p>B 侧 {@code HotEventConsumerDropTest} 有这组判据，C 侧<b>此前一条都没有</b> ⇒
 * 两个具体的守护网缺口：</p>
 * <ol>
 *   <li><b>「先计数、后查分区」的次序</b> —— 指标 {@code article_hot_kafka_consume_total}
 *       的自增<b>早于</b>分区 sketch 查找 ⇒ 「分区未持有」时<b>照样计数</b>。
 *       ⚠️ 这条在<b>真 Kafka 上不可达</b>（分区总是会被分配）⇒ 只能靠纯单测守住。
 *       ⇒ 若次序被调换 ⇒ 指标口径静默改变（分片未持有的消息不再计数）而<b>无任何测试变红</b>。</li>
 *   <li><b>{@code ignoreUnknown = true} 是硬要求</b> —— 生产端实测发的是 <b>4 个字段</b>
 *       （多 {@code user_id} / {@code timestamp}）⇒ 若这个注解被去掉 ⇒
 *       <b>现有生产端发的事件会被全部 drop ⇒ 热榜永远不更新</b>。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么用纯单测而不是真 Kafka</h2>
 * <p>本类的两条核心判据在真 Kafka 上<b>不可达</b>（分区已被分配 ⇒ 「未持有」分支走不到）；
 * 而真 Kafka 路径上的同款判据由 {@link HotEventConsumerRealKafkaTest} 负责
 * ⇒ 两者分工明确，<b>不是重复</b>。</p>
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧 {@code service/hot/hot-rpc/src/test/java/com/rag/hot/hot/rpc/mq/HotEventConsumerDropTest.java}
 *       （1:1 移植其中 4 条）；</li>
 *   <li>B 侧主代码 {@code .../mq/HotEventConsumer.java}（校验顺序 / 先计数后查分区 /
 *       无条件 MarkMessage）；</li>
 *   <li>B 侧主代码 {@code .../mq/ArticleHotEvent.java:10-15}（{@code ignoreUnknown} 的硬要求出处）。</li>
 * </ul>
 */
class HotEventConsumerContractTest {

    private static final String TOPIC = "article-hot-events";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MeterRegistry meterRegistry;
    private HotRpcProperties props;
    private HotRpcMetrics metrics;
    private HotPartitionSketchRegistry sketches;
    private HotEventConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        props = new HotRpcProperties();
        props.getInteraction().setSyncEvery(100L);
        props.getKafka().setTopic(TOPIC);
        props.getInteraction().setWeights(List.of(
                newWeight(1, "like", 1),
                newWeight(2, "comment", 2),
                newWeight(3, "coin", 3),
                newWeight(4, "share", 4)));
        metrics = new HotRpcMetrics(meterRegistry);
        sketches = new HotPartitionSketchRegistry(props);
        // store 只作为「快照触发」的观测点存在 ⇒ 用 mock 顶替，避免为了测 drop 而连真 Redis
        HotRedisSnapshotStore store = org.mockito.Mockito.mock(HotRedisSnapshotStore.class);
        consumer = new HotEventConsumer(MAPPER, props, metrics, sketches, store, Runnable::run,
                ObservationRegistry.NOOP);
    }

    // ==================================================================
    // ① 指标计数次序
    // ==================================================================

    @Test
    @DisplayName("分区未持有时仍然计数：指标自增早于分区查找（真 Kafka 上不可达，只能靠本条守住）")
    void metricIsIncrementedEvenWhenPartitionIsNotAssigned() {
        // 没有调用 onPartitionsAssigned ⇒ 分区表为空
        assertThat(sketches.size()).isZero();

        send(7, "{\"article_id\":\"a1\",\"type\":\"like\"}");

        assertThat(consumeTotal("like")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("分区已持有且 type 合法：计数 + 入分区草图")
    void validEventCountsAndEntersSketch() {
        consumer.onPartitionsAssigned(List.of(0));
        assertThat(sketches.size()).isEqualTo(1);

        send(0, "{\"article_id\":\"a1\",\"type\":\"coin\"}");

        assertThat(consumeTotal("coin")).isEqualTo(1.0d);
        HeavyKeeper hk = sketches.heavyKeeperFor(0);
        assertThat(hk).isNotNull();
        assertThat(hk.topK()).extracting(HeavyKeeper.Item::key).contains("a1");
    }

    // ==================================================================
    // ② ignoreUnknown（硬要求）
    // ==================================================================

    @Test
    @DisplayName("生产端多发字段（user_id/timestamp）不影响解析：ignoreUnknown 是硬要求")
    void unknownFieldsAreIgnored() {
        consumer.onPartitionsAssigned(List.of(0));

        // 生产端实测发送的就是 4 个字段
        assertThatCode(() -> send(0,
                "{\"article_id\":\"a1\",\"type\":\"like\",\"user_id\":\"u9\",\"timestamp\":123456}"))
                .doesNotThrowAnyException();

        assertThat(consumeTotal("like")).isEqualTo(1.0d);
        assertThat(sketches.heavyKeeperFor(0).topK()).extracting(HeavyKeeper.Item::key).contains("a1");
    }

    // ==================================================================
    // ③ drop 契约（返回值语义）
    // ==================================================================

    @Test
    @DisplayName("三类坏消息必须返回非 null error：否则 span 会被误标成成功")
    void badMessagesReturnErrorForSpanMarking() {
        // 反序列化失败 / 缺 article_id / 权重缺失 三者都返回 error
        // ⇒ error 先进观测层把 span 标成 system error，再被丢弃
        // ⇒ 若 Java 侧在进观测层之前就吞掉，span 会被误标成成功 ⇒ 与 B 侧不一致
        assertThat(consumer.consumeMessage(0, "{not-json")).isNotNull();
        assertThat(consumer.consumeMessage(0, "{\"type\":\"like\"}")).isNotNull();
        assertThat(consumer.consumeMessage(0, "{\"article_id\":\"a1\",\"type\":\"nope\"}"))
                .isNotNull();
    }

    @Test
    @DisplayName("正常路径与「分区未持有」都返回 null error（不标成 system error）")
    void healthyPathsReturnNullError() {
        consumer.onPartitionsAssigned(List.of(0));

        assertThat(consumer.consumeMessage(0, "{\"article_id\":\"a1\",\"type\":\"like\"}")).isNull();
        // 分区未持有：静默返回 ⇒ 不是错误
        assertThat(consumer.consumeMessage(9, "{\"article_id\":\"a2\",\"type\":\"like\"}")).isNull();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static HotRpcProperties.Weight newWeight(int code, String name, int weight) {
        HotRpcProperties.Weight w = new HotRpcProperties.Weight();
        w.setCode(code);
        w.setName(name);
        w.setWeight(weight);
        return w;
    }

    private void send(int partition, String payload) {
        consumer.onMessage(new ConsumerRecord<>(TOPIC, partition, 0L, "key", payload));
    }

    private double consumeTotal(String type) {
        var counter = meterRegistry.find("article_hot_kafka_consume_total")
                .tag("type", type).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
