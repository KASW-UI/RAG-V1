package com.rag.hot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>🔴 装配层门禁：容器上的 error handler 就是「零重试」的那个</b>。</p>
 *
 * <h2>⚠️ 为什么这条断言不能被「行为断言」替代</h2>
 * <p>本域<b>已有</b>行为断言（{@code HotEventConsumerRealKafkaTest#containerDoesNotRetryFailedRecord}）：
 * 真 broker 上发一条必抛的消息 ⇒ 断言「投递次数 = 1」。</p>
 * <p>⇒ 那条钉的是<b>结果</b>（失败记录只被投递一次），<b>但它不区分</b>「零重试是怎么来的」：
 * <ul>
 *   <li>是本域<b>显式</b>装了零重试 handler ⇒ 还是</li>
 *   <li>恰好别的机制（提交位点、ack 模式、listener 返回值）让投递次数看起来是 1。</li>
 * </ul>
 * <p>⇒ ⇒ 两者<b>互补</b>：行为断言钉「观测到的次数」，本断言钉「<b>容器真正会用的那个实例</b>
 * 的四个关键开关」⇒ ⇒ 两者都绿才算「零重试这件事真的成立」。</p>
 *
 * <h2>⚠️ 为什么读的是<b>工厂私有字段</b>，而不是「新建一个 handler」</h2>
 * <p>本断言读的是 {@code hotKafkaListenerContainerFactory} 这个 <b>生产 bean</b> 上
 * <b>容器真正会用的那个 handler 实例</b>（工厂的私有字段 {@code commonErrorHandler}）⇒
 * ⇒ 「工厂方法写得对」与「真的接到了容器上」<b>两件事都被覆盖</b>；
 * 若改为「new 一个 handler 出来测」⇒ ⇒ 那只证明「工厂方法写得对」，
 * <b>漏掉「忘了 setCommonErrorHandler」这种最常见的错</b> ⇒ ⇒ 另配一条
 * {@link #containerHandlerComesFromProductionFactoryMethod()} 把这两件事对上。</p>
 *
 * <h2>⚠️ 反射路径是<b>按 C 侧实际依赖版本</b>核实的（不是照抄 B 侧）</h2>
 * <p>B 侧是 <b>spring-kafka 4.0.7</b>，C 侧是 <b>spring-kafka 3.3.13</b>（Boot 3.5 管理的版本）
 * ⇒ ⇒ 内部字段<b>不能假设同名</b> ⇒ ⇒ 本类的四条反射路径已用 {@code javap} 在
 * <b>3.3.13</b> 的 jar 上逐条核实：</p>
 * <ul>
 *   <li>{@code org.springframework.kafka.config.AbstractKafkaListenerContainerFactory}
 *       → 字段 {@code private CommonErrorHandler commonErrorHandler} ✔</li>
 *   <li>{@code org.springframework.kafka.listener.DefaultErrorHandler}
 *       → 方法 {@code public boolean isAckAfterHandle()} ✔
 *       （字段 {@code ackAfterHandle} 在本类上）</li>
 *   <li>{@code org.springframework.kafka.listener.FailedRecordProcessor}（{@code DefaultErrorHandler}
 *       的祖先）→ 字段 {@code private final FailedRecordTracker failureTracker} ✔</li>
 *   <li>{@code org.springframework.kafka.listener.FailedRecordTracker}
 *       → 字段 {@code private final boolean noRetries} ✔ ＋ {@code private final BackOff backOff} ✔</li>
 * </ul>
 * <p>⇒ ⇒ <b>断言自带「读不到就报明确错」</b>（而不是 NPE 或静默通过）⇒
 * 将来 Spring Kafka 升级改了内部结构 ⇒ ⇒ 本用例会<b>显式红</b>并提示「断言需重做」
 * ⇒ ⇒ 而**不会**退化成假绿。</p>
 *
 * <h2>⚠️ {@code noRetries} 的含义（读的是<b>消费点</b>上的字段）</h2>
 * <p>{@code FailedRecordTracker.noRetries} 由 {@code backOff.start().nextBackOff() == STOP} 决定
 * ⇒ {@code FixedBackOff(0, 0)} 的第一次 {@code nextBackOff()} 即返回 {@code STOP}
 * ⇒ ⇒ {@code noRetries = true} ⇒ ⇒ 「第一次失败就放弃」⇒
 * ⇒ 与 Go 侧「消费循环无条件 {@code MarkMessage} ⇒ 坏消息丢弃、位点照常推进、<b>永不重试</b>」同形。</p>
 *
 * <h2>B 出处（逐行）</h2>
 * <ul>
 *   <li>B 侧 {@code service/hot/hot-rpc/src/test/java/com/rag/hot/hot/rpc/mq/HotKafkaConsumerGateTest.java}
 *       —— 第 4 节「装配层断言：容器上的 error handler 就是零重试的那个」
 *       （{@code containerErrorHandlerIsZeroRetry}，:328-368）</li>
 *   <li>B 侧 {@code service/hot/hot-rpc/.../config/HotKafkaConfig.java} —— 零重试 handler 的生产点</li>
 *   <li>B 侧 {@code service/hot/hot-rpc/.../mq/hot_handler.go:108} —— 无条件 {@code MarkMessage}
 *       （「处理完就提交位点、绝不重试」的 Go 侧语义）</li>
 * </ul>
 */
class HotKafkaErrorHandlerAssemblyGateTest extends com.rag.hot.support.HotKafkaTestBase {

    /**
     * <b>生产 bean</b>（不是「随便 new 一个工厂」）。
     *
     * <p>⚠️ 必须<b>按 bean 名</b>注入：容器里还有 Boot 默认的
     * {@code kafkaListenerContainerFactory} ⇒ 按类型注入会<b>歧义</b>。
     */
    @Autowired
    @Qualifier("hotKafkaListenerContainerFactory")
    private ConcurrentKafkaListenerContainerFactory<Object, Object> factory;

    @Test
    @DisplayName("🔴 容器上的 error handler 就是零重试的 DefaultErrorHandler（noRetries=true / maxAttempts=0 / ackAfterHandle=true）")
    void containerErrorHandlerIsZeroRetry() {
        CommonErrorHandler handler =
                (CommonErrorHandler) ReflectionTestUtils.getField(factory, "commonErrorHandler");

        assertThat(handler)
                .as("工厂上没有 commonErrorHandler ⇒ 容器会用默认的 FixedBackOff(0,9) 重试")
                .isNotNull();
        assertThat(handler)
                .as("容器上的 error handler 不是 DefaultErrorHandler")
                .isInstanceOf(DefaultErrorHandler.class);

        DefaultErrorHandler defaultErrorHandler = (DefaultErrorHandler) handler;

        assertThat(defaultErrorHandler.isAckAfterHandle())
                .as("ackAfterHandle=false ⇒ 处理完（含放弃）不提交位点，"
                        + "与 Go 的无条件 MarkMessage 相反")
                .isTrue();

        Object tracker = ReflectionTestUtils.getField(defaultErrorHandler, "failureTracker");
        assertThat(tracker)
                .as("读不到 failureTracker ⇒ Spring Kafka 内部结构变了，断言需重做")
                .isNotNull();

        Object noRetries = ReflectionTestUtils.getField(tracker, "noRetries");
        assertThat(noRetries)
                .as("noRetries != true ⇒ 该 handler 会重试（Spring Kafka 默认 FixedBackOff(0,9)），"
                        + "坏消息会把分区卡住 9 轮，而 Go 侧不存在重试")
                .isEqualTo(Boolean.TRUE);

        Object backOff = ReflectionTestUtils.getField(tracker, "backOff");
        assertThat(backOff)
                .as("backOff 不是 FixedBackOff")
                .isInstanceOf(FixedBackOff.class);
        assertThat(((FixedBackOff) backOff).getMaxAttempts())
                .as("maxAttempts != 0 ⇒ 不是零重试")
                .isZero();
    }

    @Test
    @DisplayName("🔴 容器上那个 handler **就是**生产工厂方法造出来的那个（防「工厂方法对、但没接上」）")
    void containerHandlerComesFromProductionFactoryMethod() {
        DefaultErrorHandler produced = HotKafkaConfig.zeroRetryErrorHandler();
        CommonErrorHandler installed =
                (CommonErrorHandler) ReflectionTestUtils.getField(factory, "commonErrorHandler");

        assertThat(installed)
                .as("容器上没有 handler ⇒ 工厂方法写得再对也没接上")
                .isNotNull();

        // ⚠️ **逐项比语义指纹，不比对象身份**：
        //    容器上的实例是 Spring 在**启动期**用工厂方法造的那一个 ⇒ 身份可能相同也可能不同
        //    （取决于 Spring 是否缓存了 bean 方法的返回值）⇒ ⇒ 比「四项开关」才稳。
        assertThat(((DefaultErrorHandler) installed).isAckAfterHandle())
                .as("装到容器上的 handler 的 ackAfterHandle 与生产工厂方法产出的不一致")
                .isEqualTo(produced.isAckAfterHandle());

        Object installedTracker = ReflectionTestUtils.getField(installed, "failureTracker");
        Object producedTracker = ReflectionTestUtils.getField(produced, "failureTracker");
        assertThat(ReflectionTestUtils.getField(installedTracker, "noRetries"))
                .as("装到容器上的 handler 与生产工厂方法产出的 noRetries 不一致 ⇒ 装的不是它")
                .isEqualTo(ReflectionTestUtils.getField(producedTracker, "noRetries"));

        Object installedBackOff = ReflectionTestUtils.getField(installedTracker, "backOff");
        Object producedBackOff = ReflectionTestUtils.getField(producedTracker, "backOff");
        assertThat(((FixedBackOff) installedBackOff).getMaxAttempts())
                .as("装到容器上的 handler 与生产工厂方法产出的 maxAttempts 不一致 ⇒ 装的不是它")
                .isEqualTo(((FixedBackOff) producedBackOff).getMaxAttempts());
    }
}
