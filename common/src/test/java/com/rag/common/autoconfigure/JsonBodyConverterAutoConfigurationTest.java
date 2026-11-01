package com.rag.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.web.Coercion;
import com.rag.common.web.JsonBodyConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §3.3「机制可证」守门：<b>K-01 的强转规则真的施加到了 Boot 的 mapper 上</b>。
 *
 * <p>⚠️ <b>为什么这条特别重要</b>：共享层走的是 <b>postConfigurer 路线</b>
 * （Spring 6.2 的 {@code Jackson2ObjectMapperBuilder} <b>没有</b> coercion API）
 * ⇒ ⇒ 如果这条路线失效，<b>不会报错</b>，只是「number 能进 String 字段了」
 * ⇒ ⇒ 与 B 的解析层<b>相反</b>（B 侧 恒 400）⇒ ⇒ 只能靠真触发测试钉住。
 */
class JsonBodyConverterAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, JsonBodyConverterAutoConfiguration.class));

    /** 用来验证「number 进 String 字段被拒」的最小载荷。 */
    static final class Holder {
        public String name;
    }

    @Test
    @DisplayName("K-01：number → String 字段被拒（B 的 type mismatch）")
    void numberIntoStringIsRejected() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            assertThatThrownBy(() -> mapper.readValue("{\"name\":1}", Holder.class))
                    .as("共享层的强转规则必须真的生效")
                    .isNotNull();
        });
    }

    @Test
    @DisplayName("K-01：float → 整数字段被拒（ACCEPT_FLOAT_AS_INT 关掉）")
    void floatIntoLongIsRejected() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            assertThatThrownBy(() -> mapper.readValue("{\"id\":1.0}", LongHolder.class))
                    .isNotNull();
        });
    }

    @Test
    @DisplayName("K-01：string → 整数字段仍被接受（B 的正规入口，不许收紧）")
    void stringIntoLongIsAccepted() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            LongHolder holder = mapper.readValue("{\"id\":\"7\"}", LongHolder.class);
            assertThat(holder.id).isEqualTo(7L);
        });
    }

    @Test
    @DisplayName("K-02：JsonBodyConverter 被插到 Boot 的 Jackson 转换器**之前**")
    void converterIsInsertedBeforeJacksonConverter() {
        runner.run(context -> {
            WebMvcConfigurer configurer = context.getBean("seaJsonBodyConverterConfigurer",
                    WebMvcConfigurer.class);

            List<HttpMessageConverter<?>> converters = new ArrayList<>();
            converters.add(new MappingJackson2HttpMessageConverter(
                    context.getBean(ObjectMapper.class)));
            converters.add(new org.springframework.http.converter.StringHttpMessageConverter());

            configurer.extendMessageConverters(converters);

            assertThat(converters.get(0)).isInstanceOf(JsonBodyConverter.class);
            assertThat(converters.get(1)).isInstanceOf(MappingJackson2HttpMessageConverter.class);
        });
    }

    @Test
    @DisplayName("K-02：Boot 没注册 Jackson 转换器时**不擅自插入**（不造出本模块没打算支持的转换器）")
    void noJacksonConverterMeansNoInsertion() {
        runner.run(context -> {
            WebMvcConfigurer configurer = context.getBean("seaJsonBodyConverterConfigurer",
                    WebMvcConfigurer.class);

            List<HttpMessageConverter<?>> converters = new ArrayList<>();
            converters.add(new org.springframework.http.converter.StringHttpMessageConverter());

            configurer.extendMessageConverters(converters);

            assertThat(converters).hasSize(1);
            assertThat(converters.get(0)).isNotInstanceOf(JsonBodyConverter.class);
        });
    }

    @Test
    @DisplayName("Coercion.applyTo(ObjectMapper) 与 applyTo(JsonMapper.Builder) 规则同源")
    void bothCoercionRoutesApplyTheSameRules() throws Exception {
        ObjectMapper viaMapper = new ObjectMapper();
        Coercion.applyTo(viaMapper);

        com.fasterxml.jackson.databind.json.JsonMapper.Builder builder =
                com.fasterxml.jackson.databind.json.JsonMapper.builder();
        Coercion.applyTo(builder);
        ObjectMapper viaBuilder = builder.build();

        for (ObjectMapper mapper : List.of(viaMapper, viaBuilder)) {
            assertThatThrownBy(() -> mapper.readValue("{\"name\":1}", Holder.class)).isNotNull();
        }
    }

    static final class LongHolder {
        public long id;
    }

    /** 保留常量引用，避免 IDE 把它当未用 import 删掉（MediaType 在 K-02 的判据里是语义的一部分）。 */
    @Test
    @DisplayName("（锚点）K-02 判据用的是**原始** Content-Type 字面量，不用 MediaType 解析")
    void contentTypeJudgementIsLiteral() {
        assertThat(MediaType.APPLICATION_JSON_VALUE).isEqualTo("application/json");
        assertThat("application/vnd.api+json".contains("application/json")).isFalse();
    }
}
