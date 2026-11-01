package com.rag.common.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §3.3「机制可证」守门：<b>MQ 消费端包装真的产出观测、真的按 Go 的口径分类错误</b>。
 *
 * <p>⚠️ <b>Go 出处</b>：{@code service/common/observability/mq.go} 的 {@code TraceConsumer}。
 *
 * <p>⚠️ <b>抄写基准 = RAG</b>：{@code MqConsumer} 整段抄（L2 · D13）。
 *
 * <p>⚠️ <b>panic 语义是行为的一部分</b>：Go 的 {@code mq.go:30} 是裸的
 * {@code err := fn(consumeCtx)}，<b>没有 recover</b> ⇒ panic 继续向上传播
 * ⇒ ⇒ 本类<b>刻意不 catch</b> ⇒ 用例 {@link #panicPropagates()} 把这条钉住。
 */
class MqConsumerTest {

    @Test
    @DisplayName("成功 ⇒ 无 error 标记 + 写 duration")
    void successHasNoErrorKind() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);

        Throwable err = MqConsumer.traceConsumer(registry, "mq", "process.order",
                Map.of("order.id", "12345"), ctx -> null);

        assertThat(err).isNull();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getLowCardinalityKeyValue("sea.error.kind")).isNull();
        assertThat(captured.get().getHighCardinalityKeyValue("sea.duration_ms")).isNotNull();
    }

    @Test
    @DisplayName("业务异常 ⇒ system 分类")
    void businessErrorBecomesSystemError() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);

        Throwable err = MqConsumer.traceConsumer(registry, "mq", "process.order",
                Map.of(), ctx -> new IllegalStateException("consume failed"));

        assertThat(err).isInstanceOf(IllegalStateException.class);
        assertThat(captured.get().getLowCardinalityKeyValue("sea.error.kind").getValue())
                .isEqualTo("system");
    }

    @Test
    @DisplayName("超时异常 ⇒ timeout 分类（带 sea.timeout）")
    void timeoutErrorBecomesTimeout() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);

        Throwable err = MqConsumer.traceConsumer(registry, "mq", "process.order",
                Map.of(), ctx -> new TimeoutException("too slow"));

        assertThat(err).isInstanceOf(TimeoutException.class);
        assertThat(captured.get().getLowCardinalityKeyValue("sea.error.kind").getValue())
                .isEqualTo("timeout");
        assertThat(captured.get().getLowCardinalityKeyValue("sea.timeout").getValue())
                .isEqualTo("true");
    }

    @Test
    @DisplayName("panic 不被吞掉（照抄 Go 的裸调用语义）")
    void panicPropagates() {
        ObservationRegistry registry = registry(new AtomicReference<>());

        assertThatThrownBy(() -> MqConsumer.traceConsumer(registry, "mq", "process.order",
                Map.of(), ctx -> { throw new IllegalStateException("panic!"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("panic!");
    }

    @Test
    @DisplayName("caller 的 attrs 进观测（值为 null 的条目被跳过）")
    void callerAttributesAreWritten() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);

        // ⚠️ Map.of 不允许 null 值 ⇒ 用 HashMap 才能构造「值为 null 的条目」
        Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("order.id", "12345");
        attrs.put("skipped", null);

        MqConsumer.traceConsumer(registry, "mq", "process.order", attrs, ctx -> null);

        assertThat(captured.get().getHighCardinalityKeyValue("order.id").getValue())
                .isEqualTo("12345");
        assertThat(captured.get().getHighCardinalityKeyValue("skipped")).isNull();
    }

    @Test
    @DisplayName("默认名：tracer → mq-consumer，span → message.consume")
    void defaultNamesArePreserved() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        AtomicReference<String> nameRef = new AtomicReference<>();
        AtomicReference<String> contextualRef = new AtomicReference<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                captured.set(context);
                nameRef.set(context.getName());
                contextualRef.set(context.getContextualName());
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });

        MqConsumer.traceConsumer(registry, null, null, Map.of(), ctx -> null);

        assertThat(nameRef.get()).isEqualTo(MqConsumer.DEFAULT_OBSERVATION_NAME);
        assertThat(contextualRef.get()).isEqualTo(MqConsumer.DEFAULT_SPAN_NAME);
    }

    @Test
    @DisplayName("「这是一次消费」的语义被 mq.kind=consumer 承载（Micrometer 无 span kind 概念）")
    void consumerKindIsCarriedByKeyValue() {
        AtomicReference<Observation.Context> captured = new AtomicReference<>();
        ObservationRegistry registry = registry(captured);

        MqConsumer.traceConsumer(registry, "mq", "process.order", Map.of(), ctx -> null);

        assertThat(captured.get().getLowCardinalityKeyValue(MqConsumer.ATTR_MQ_KIND).getValue())
                .isEqualTo("consumer");
    }

    @Test
    @DisplayName("registry = null ⇒ 不产出观测，但错误分类与返回值不变")
    void nullRegistryStillClassifies() {
        Throwable err = MqConsumer.traceConsumer(null, "mq", "process.order",
                Map.of(), ctx -> new IllegalStateException("x"));

        assertThat(err).isInstanceOf(IllegalStateException.class);
    }

    private static ObservationRegistry registry(AtomicReference<Observation.Context> captured) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStop(Observation.Context context) {
                captured.set(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        return registry;
    }
}
