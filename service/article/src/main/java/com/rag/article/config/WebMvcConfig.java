package com.rag.article.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.rag.article.interceptor.JwtAuthInterceptor;
import com.rag.common.web.Coercion;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>职责</b>：article 域的 <b>Spring MVC 装配点</b> —— 挂鉴权拦截器 + 注册 go-zero 对齐的
 * Jackson 解析规则。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/config/WebMvcConfig.java}
 * （类声明 {@code :60-61} · 字段与构造器 {@code :63-67} · {@code goStyleCoercionCustomizer} {@code :69-72} ·
 * {@code addInterceptors} {@code :74-78}）
 * ＋ 规则实现类 {@code service/article/article-api/src/main/java/com/rag/article/article/api/config/ArticleGoStyleCoercion.java}
 * （{@code :50-67}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>API 回改（Boot 4 → Boot 3.5 · D14）</b> ——
 *       <ul>
 *         <li>B 侧的扩展点是 {@code org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer}
 *             （<b>Boot 4 / Jackson 3 专有</b>）⇒ 本仓改用 Boot 3.5 的
 *             {@link Jackson2ObjectMapperBuilderCustomizer}；</li>
 *         <li>B 侧的规则实现类 {@code ArticleGoStyleCoercion} 是<b>本域私有的 Jackson 3 副本</b>
 *             ⇒ 本仓把它<b>就地并入本类的私有方法</b>（{@link #applyArticleDomainCoercions(ObjectMapper)}），
 *             与本仓已落地域（{@code service/message/.../config/WebMvcConfig.java}）
 *             <b>同一手法</b> ⇒ <b>不新增文件</b>；</li>
 *         <li>共享的两条规则改用共享层 {@link Coercion}
 *             （{@code com.rag.common.web.Coercion} · D3 上收 · Jackson 2 写法）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>拦截器注册的<b>路径模式</b>逐字一致（{@code /v1/**}）；</li>
 *         <li>Jackson 侧：共享 2 条 ＋ article 2 条 = B 侧 {@code ArticleGoStyleCoercion}
 *             的<b>同一套</b>（共享 2 条由 B 侧 {@code GoStyleCoercion} 提供 + article 补强 2 条）
 *             ⇒ 「哪些输入被拒 / 被接受」的判定口径不变。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>拦截器只挂 {@code /v1/**}，真正的判据在拦截器内部</h2>
 * <p>Go 侧三段 {@code AddRoutes} 的鉴权差异（第一段带 {@code rest.WithJwt}，
 * 后两段不带）由 {@link JwtAuthInterceptor} 内部的受保护路由表
 * （method + path，B 侧载体 = {@code ArticleJwtProtectedRoutes}）承担。
 * 这里只做一件粗筛：把 {@code /v1/**} 交给它，{@code /actuator/**} 等路径不进入鉴权逻辑。
 *
 * <p>⚠️ <b>不要</b>用 {@code addPathPatterns("/v1/article/**")} 之类的宽前缀：
 * 那会把公开的 {@code GET /v1/article/:id}（public 读详情）也纳入鉴权，
 * 凭空要求它带 token —— 而 Go 侧它是明确公开的（第二段 {@code AddRoutes} 无 {@code WithJwt}）。
 *
 * <p>⚠️ <b>public 路径白名单不在本类</b>：B 侧的 {@code WebMvcConfig} <b>没有任何</b>
 * {@code excludePathPatterns} ⇒ public 段的判定<b>整段</b>在拦截器内部
 * （B 侧 {@code ArticleJwtProtectedRoutes} 的 method + path 表）⇒
 * ⇒ 本类照搬：<b>不新增 excludePathPatterns</b>（新增会把 B 侧「进拦截器再放行」
 * 变成「不进拦截器」⇒ 改动的是<b>拦截器是否被调用</b>这条可观察行为）。
 *
 * <h2>关于 Jackson 强转规则</h2>
 * <p>共享的两条（number/bool 不进 String 字段 · float 不进整数字段）由共享层
 * {@link Coercion} 提供（{@code JsonBodyConverterAutoConfiguration} 已施加 ⇒ <b>幂等</b>）；
 * article 域自己的两条补强（无 {@code ,string} 的整数字段拒绝 JSON 字符串）落在本类。
 *
 * <p><b>⚠️ 为什么必须叠加、而不是直接用共享规则</b>：
 * 共享层 {@code Coercion} 刻意保留了一条豁免 ——「<b>String → 整数字段保持接受</b>」
 * （users 域按真源逐字段实测得出：users 的 uid 带 {@code json:"uid,string"}）。
 * <b>article 域没有这个前提</b>：Go {@code service/article/api/internal/types/types.go}
 * （全文 86 行）<b>没有一个 {@code ,string} option</b>
 * ⇒ {@code {"status":"2"}} 在 Go 侧<b>必然</b>走 {@code processFieldPrimitive} 的
 * {@code typeKind != valueKind} 分支 ⇒ {@code newTypeMismatchError} ⇒ HTTP 400 text/plain。
 * ⇒ 若不叠加这条规则，Spring 的默认绑定会<b>接受</b> {@code {"status":"2"}} ⇒
 * 一个 Go 侧拒绝、RAG 侧接受的请求。
 *
 * <p><b>为什么不改共享的 {@code Coercion}</b>：那条豁免对 users 域成立且已被验证；
 * 改共享层会连带把 user-api / admin-api 的已验证行为退回去
 * ⇒ ⇒ <b>补强要局部叠加，不要污染共享层</b>。
 *
 * <p><b>只动 MVC 这一个 mapper</b>：gRPC 用 protobuf marshaller，不走 Jackson。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** B 侧的 {@code rest.WithPrefix("/v1")}（{@code routes.go}）—— 唯一的前缀。 */
    private static final String API_PREFIX = "/v1/**";

    private final JwtAuthInterceptor jwtAuthInterceptor;

    /**
     * 构造器注入鉴权拦截器。
     *
     * <p><b>B 出处</b>：{@code .../api/config/WebMvcConfig.java:63-67}。
     *
     * @param jwtAuthInterceptor article 域的 JWT 鉴权拦截器
     */
    public WebMvcConfig(JwtAuthInterceptor jwtAuthInterceptor) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
    }

    /**
     * E-3 根因 R1：把 go-zero 风格的解析规则施加到<b>本模块 MVC</b> 的 Jackson 2 mapper 上。
     *
     * <p><b>为什么是 {@code postConfigurer} 而不是改 builder</b>：
     * Boot 3.5 的 {@code Jackson2ObjectMapperBuilder} <b>没有</b> coercion API
     * （只有 {@code featuresToDisable} / {@code featuresToEnable} 等）⇒ builder 路线走不通；
     * 但 {@code Jackson2ObjectMapperBuilder#postConfigurer(Consumer<ObjectMapper>)} <b>存在</b>
     * ⇒ ⇒ 可以在 builder 构建完之后对 mapper 施加规则。
     *
     * <p>⚠️ <b>共享层也已施加共享那两条</b>
     * （{@code JsonBodyConverterAutoConfiguration#seaCoercionMapperCustomizer} → 同样调
     * {@code Coercion::applyTo}）⇒ ⇒ 共享部分<b>幂等</b>（同一份规则施加两次 = 施加一次）
     * ⇒ ⇒ 本 bean 的额外作用是「article 域自己的 2 条补强」。
     *
     * <p><b>B 出处</b>：{@code .../api/config/WebMvcConfig.java:69-72}
     * ＋ {@code .../api/config/ArticleGoStyleCoercion.java:55-67}。
     *
     * @return Jackson 2 mapper 的自定义器
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer goStyleCoercionCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            // 共享的两条：number/bool 不进 String 字段 · float 不进整数字段
            Coercion.applyTo(mapper);
            // article 域自己的 2 条补强（B 侧 ArticleGoStyleCoercion）
            applyArticleDomainCoercions(mapper);
        });
    }

    /**
     * <b>article 域自己的 2 条解析规则</b>（B 侧
     * {@code service/article/article-api/src/main/java/com/rag/article/article/api/config/ArticleGoStyleCoercion.java}
     * 整段照抄，只做 Jackson 3 → Jackson 2 的 API 回改）。
     *
     * <p><b>规则 1：无 {@code ,string} 的整数字段<b>拒绝</b> JSON 字符串</b>
     * <p>Go 出处（消费点）：{@code core/mapping/unmarshaler.go:643-662} 的
     * {@code processFieldPrimitive}：{@code typeKind != valueKind} ⇒ {@code type mismatch}。
     * ⚠️ <b>为什么 Jackson 默认会放行</b>：Jackson 2/3 默认允许标量强转，
     * {@code {"status":"2"}} 会安静地变成 2 ⇒ <b>Go 侧拒绝、RAG 侧接受</b>的请求。
     *
     * <p><b>规则 2：无 {@code ,string} 的浮点字段<b>拒绝</b> JSON 字符串</b>
     * <p>同一条 {@code processFieldPrimitive} 路径（{@code LogicalType.Float} 覆盖
     * {@code float / double / Float / Double}）。
     *
     * <p>⚠️ <b>只加这两条，不多加</b>：Boolean/String 的补强<b>不需要</b> ——
     * 本模块 JSON 入参里<b>没有任何 boolean 字段</b>（{@code desc} 走 query string，
     * 由 {@code GetFormValuesFilter} 按 Go 的 {@code convertTypeFromString} 处理，与 Jackson 无关）。
     * 为不存在的字段类型加规则，等于给后人留一个「这里还管别的」的错觉。
     *
     * <p><b>B 出处</b>：{@code .../api/config/ArticleGoStyleCoercion.java:58-66}。
     *
     * @param mapper 本模块 MVC 使用的 Jackson 2 mapper
     */
    private static void applyArticleDomainCoercions(ObjectMapper mapper) {
        // 规则 1：JSON 字符串不进整数字段
        mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        // 规则 2：JSON 字符串不进浮点字段
        mapper.coercionConfigFor(LogicalType.Float)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    }

    /**
     * 注册鉴权拦截器 —— 只挂 {@code /v1/**}。
     *
     * <p><b>B 出处</b>：{@code .../api/config/WebMvcConfig.java:74-78}。
     *
     * @param registry Spring MVC 的拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns(API_PREFIX);
    }
}
