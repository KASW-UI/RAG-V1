package com.rag.hot.observability;

import com.rag.common.observability.GoStyleOtelAgent;
import com.rag.common.observability.GoStyleOtelConfig;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>「追踪导出真的装上了吗」</b>的守门测试。</p>
 *
 * <h2>🔴 为什么要有这条</h2>
 * <p>本域的 {@code application.yml} 此前<b>整段没有 {@code otel:} 配置</b>
 * ⇒ 共享层 {@code GoStyleOtelConfig} 读到的端点是 {@code null}
 * ⇒ 按 B 侧的 {@code if len(c.Endpoint) > 0} 口径<b>不装 exporter</b>
 * ⇒ ⇒ 本域的 span <b>一条都导不出去</b>（跨域链路在 Jaeger 侧断链）。
 * ⇒ 而且这<b>不是本域独有</b>：{@code comment} / {@code favorite} / {@code message} / {@code task}
 * 四域<b>同样零 otel 段</b> ⇒ 共 5 个域。</p>
 *
 * <h2>本类钉住的是「<b>配置在</b> ＋ <b>机制生效</b>」两件事</h2>
 * <ol>
 *   <li><b>配置在</b>：把本域<b>真实</b>的 {@code application.yml} 读进 Spring 的
 *       {@code Environment} ⇒ 再交给共享层 {@code GoStyleOtelConfig#from(env)}
 *       ⇒ 断言端点<b>真的被读到了</b>（不是「yml 里写了就算」）。</li>
 *   <li><b>机制生效</b>：把读到的配置交给共享层 {@code GoStyleOtelAgent#buildProvider}
 *       ⇒ 断言 provider 上<b>真的挂了</b> {@code BatchSpanProcessor}
 *       （= 真的有 exporter ⇒ span 真的会被导出）。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么用反射读 {@code SpanProcessor}</h2>
 * <p>OTel Java SDK <b>没有公开的 API</b> 能问「这个 provider 挂了几个 processor」
 * ⇒ ⇒ 只能反射读 {@code SdkTracerProvider#sharedState} 里的 processor 字段。
 * ⚠️ <b>该字段名随 OTel 版本变</b> ⇒ ⇒ 本用例对 OTel 升级<b>敏感</b>
 * ⇒ ⇒ 升级后若本用例红，<b>不是</b>业务坏了，而是<b>判据要跟着改</b>（会明确报出来）。</p>
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧 {@code service/hot/hot-api/src/main/resources/application.yml:209-218}
 *       （{@code otel.exporter.otlp.endpoint} / {@code protocol: grpc} /
 *       {@code otel.resource.attributes.service.name} / {@code otel.traces.sampler: always_on}）；</li>
 *   <li>B 侧 {@code service/hot/hot-rpc/src/main/resources/application.yml:335-337}
 *       （{@code management.otlp.tracing.endpoint}）；</li>
 *   <li>共享层 {@code common/observability/GoStyleOtelConfig}（照抄 B 侧 {@code Telemetry.*}）·
 *       {@code common/observability/GoStyleOtelAgent}（照抄 B 侧 {@code startAgent}）。</li>
 * </ul>
 */
class HotOtelExporterWiringTest {

    /** 本域 {@code application.yml} 的真实路径（读的是<b>真配置</b>，不是副本）。 */
    private static final String APPLICATION_YML = "src/main/resources/application.yml";

    @Test
    @DisplayName("① 配置在：本域 yml 的 otel 段存在 ⇒ 共享层真的读到了端点")
    void otelSectionIsPresentAndReadBySharedLayer() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("hot-application-yml", new FileSystemResource(APPLICATION_YML));
        assertThat(sources).as("本域的 application.yml 必须能被 yaml loader 读到").isNotEmpty();
        sources.forEach(s -> env.getPropertySources().addFirst(s));

        GoStyleOtelConfig config = GoStyleOtelConfig.from(env);

        assertThat(config.endpoint())
                .as("otel.exporter.otlp.endpoint 必须被读到（为空 ⇒ 共享层不装 exporter ⇒ span 导不出去）")
                .isNotNull()
                .contains("jaeger");
        assertThat(config.serviceName())
                .as("otel.resource.attributes.service.name / otel.service.name 必须被读到")
                .isNotBlank();
        assertThat(config.samplerRatio())
                .as("otel.traces.sampler: always_on ⇒ 采样比例必须是 1.0（B 侧 Go Sampler: 1.0）")
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("② 机制生效：端点非空 ⇒ provider 真的挂了 BatchSpanProcessor（span 真的会被导出）")
    void exporterIsReallyInstalledWhenEndpointIsPresent() throws Exception {
        GoStyleOtelConfig config = new GoStyleOtelConfig(
                "hot-service", "http://127.0.0.1:4317", "grpc", 1.0d);

        // ⚠️ 用 buildProvider 而不是 install：install 会调 GlobalOpenTelemetry.set
        //    ⇒ 全局**只能 set 一次** ⇒ 测试里用 install 会互相打架。
        SdkTracerProvider provider = GoStyleOtelAgent.buildProvider(config);

        String processors = describeSpanProcessors(provider);
        assertThat(processors)
                .as("端点非空时必须挂上 BatchSpanProcessor（= 真有 exporter ⇒ span 真会被导出）")
                .contains("BatchSpanProcessor");
    }

    @Test
    @DisplayName("③ 反向对照：端点为空 ⇒ 不装任何 processor（span 无处可去）")
    void noExporterWhenEndpointIsBlank() throws Exception {
        GoStyleOtelConfig config = new GoStyleOtelConfig("hot-service", null, "grpc", 1.0d);

        SdkTracerProvider provider = GoStyleOtelAgent.buildProvider(config);

        String processors = describeSpanProcessors(provider);
        assertThat(processors)
                .as("端点为空 ⇒ 共享层按 B 侧口径不装 exporter")
                .doesNotContain("BatchSpanProcessor");
    }

    /**
     * 描述 provider 上挂着的 {@code SpanProcessor}（用反射，因为 OTel SDK 没公开 API）。
     *
     * @param provider provider
     * @return 描述文本
     * @throws Exception 反射失败
     */
    private static String describeSpanProcessors(SdkTracerProvider provider) throws Exception {
        Field shared = SdkTracerProvider.class.getDeclaredField("sharedState");
        shared.setAccessible(true);
        Object sharedState = shared.get(provider);
        for (Field f : sharedState.getClass().getDeclaredFields()) {
            if (f.getType().getName().contains("SpanProcessor")) {
                f.setAccessible(true);
                return f.getName() + "=" + String.valueOf(f.get(sharedState));
            }
        }
        return "sharedState=" + sharedState.getClass().getSimpleName() + " (no SpanProcessor field)";
    }
}
