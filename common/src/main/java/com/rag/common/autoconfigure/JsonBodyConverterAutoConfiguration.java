package com.rag.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.web.Coercion;
import com.rag.common.web.JsonBodyConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * <p><b>Jackson 侧的共享装配点</b> —— 把「K-01 强转规则」与「K-02 请求体判据」两条
 * <b>已落地的类</b>接进 Spring MVC 的真实管线。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ 为什么必须有本类（RAG 侧是「各域 api 模块各写一份」）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p>RAG 侧 {@code GoStyleJsonBodyConverterConfig} 是 {@code @Configuration} + {@code @ComponentScan}
 * 形态 ⇒ 每个 api 模块各有一份实例 ⇒ <b>10 份重复</b>，改一处要改 10 处。
 * <p>本仓的共享层是 <b>starter</b>（D3）⇒ ⇒ 收进 <b>一个</b> auto-config ⇒
 * 消费方<b>零装配代码</b> ⇒ 改一处即 10 个域全生效。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ D14 工具链回退带来的两处 API 变化（Boot 4 → Boot 3.5）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <table border="1">
 *   <tr><th>项</th><th>RAG（Boot 4 / Spring 7 / Jackson 3）</th><th>本仓（Boot 3.5 / Spring 6.2 / Jackson 2）</th></tr>
 *   <tr><td>Boot 的 Jackson 转换器类型</td>
 *       <td>{@code org.springframework.http.converter.json.JacksonJsonHttpMessageConverter}</td>
 *       <td><b>{@code MappingJackson2HttpMessageConverter}</b>（Spring 7 才引入前者）</td></tr>
 *   <tr><td>注入的 mapper 类型</td>
 *       <td>{@code tools.jackson.databind.json.JsonMapper}</td>
 *       <td><b>{@code com.fasterxml.jackson.databind.ObjectMapper}</b>（Boot 3.5 bean 声明类型；运行时实例才是 {@code JsonMapper}）</td></tr>
 *   <tr><td>coercion 的施加位置</td>
 *       <td>{@code JsonMapper.Builder#withCoercionConfig}</td>
 *       <td><b>{@code Jackson2ObjectMapperBuilder#postConfigurer} → {@code ObjectMapper#coercionConfigFor}</b>
 *           （Spring 6.2 的 builder <b>无</b> coercion API ⇒ builder 路线走不通）</td></tr>
 * </table>
 *
 * <p><b>⚠️ 「插在 Jackson 转换器之前」而不是 {@code add(0, …)}</b>（照抄 RAG 的理由）：
 * 本转换器认领 {@code * / *} ⇒ 若 {@code add(0, …)} 会盖过
 * {@code ByteArrayHttpMessageConverter} / {@code StringHttpMessageConverter}
 * ⇒ 后来者随手加一个 {@code @RequestBody String} 就会静默改变行为
 * ⇒ ⇒ 只插到<b>第一个</b> Jackson 转换器的位置。
 *
 * <p><b>⚠️ 找不到 Jackson 转换器时<b>不擅自插入</b></b>：那会造出一个
 * 「本模块根本没打算支持 JSON」的转换器。
 *
 * <p><b>抄写基准</b>：RAG
 * {@code service/common/src/main/java/com/rag/common/http/GoStyleJsonBodyConverterConfig.java}
 * ＋ {@code service/users/users-common/src/main/java/com/rag/users/common/http/GoStyleCoercion.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）＋ <b>Boot 4 → 3.5 的 API 回改</b>（L4 · D14）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({JsonBodyConverter.class, MappingJackson2HttpMessageConverter.class})
public class JsonBodyConverterAutoConfiguration {

    /**
     * 把 K-01 的两条强转规则施加到 <b>Boot 自己的</b> {@link ObjectMapper} 上。
     *
     * <p>⚠️ <b>用 {@code postConfigurer} 而不是「自己 new 一个 mapper」</b>：
     * 替换 mapper ⇒ 各域 {@code spring.jackson.*} 的绑定全部失效
     * ⇒ 那是「换掉整个 mapper」，不是「加两条规则」。
     *
     * <p>⚠️ <b>用 {@code @ConditionalOnMissingBean(name = …)} 而不是按类型判</b>：
     * {@link Jackson2ObjectMapperBuilderCustomizer} 是<b>多例</b>的（各域可以有自己的）
     * ⇒ 按类型判会把消费方<b>任何一个</b> customizer 都当成「已经有了」⇒ 本条规则被跳过
     * ⇒ ⇒ 按<b>bean 名字</b>判才准。
     */
    @Bean
    @ConditionalOnMissingBean(name = "seaCoercionMapperCustomizer")
    public Jackson2ObjectMapperBuilderCustomizer seaCoercionMapperCustomizer() {
        return builder -> builder.postConfigurer(Coercion::applyTo);
    }

    /**
     * 把 {@link JsonBodyConverter} 插到 Boot 的 Jackson 转换器<b>之前</b>。
     *
     * <p>⚠️ <b>注入 {@link ObjectMapper}（不是自己 new）</b>：
     * 各域的 {@code ,string} / {@code options=1|2|3|4} 等语义都在<b>Boot 配置好的那个</b> mapper 上
     * ⇒ 自己 new 一个 ⇒ 那些配置全部失效。
     */
    @Bean
    @ConditionalOnMissingBean(name = "seaJsonBodyConverterConfigurer")
    public WebMvcConfigurer seaJsonBodyConverterConfigurer(ObjectMapper objectMapper) {
        return new WebMvcConfigurer() {
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                int insertAt = -1;
                for (int i = 0; i < converters.size(); i++) {
                    if (converters.get(i) instanceof MappingJackson2HttpMessageConverter) {
                        insertAt = i;
                        break;
                    }
                }
                if (insertAt < 0) {
                    // Boot 没有注册 Jackson 转换器 ⇒ 不擅自插入
                    return;
                }
                converters.add(insertAt, new JsonBodyConverter(objectMapper));
            }
        };
    }
}
