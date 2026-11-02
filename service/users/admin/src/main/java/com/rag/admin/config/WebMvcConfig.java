package com.rag.admin.config;

import com.rag.admin.interceptor.AdminJwtAuthInterceptor;
import com.rag.common.web.Coercion;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>职责</b>：admin 域的 Spring MVC 装配 —— 把 {@link AdminJwtAuthInterceptor} 挂到
 * {@code /admin/v1/**}，并把 B 侧的解析层强转规则挂到本域的 MVC mapper 上。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>两处 API 回改（Boot 4 → Boot 3.5 · D14）</b>：
 *     <ul>
 *       <li>B 侧的 {@code org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer}
 *           （Boot 4 / Jackson 3 专有）⇒ 本仓改用 Boot 3.5 的
 *           {@link Jackson2ObjectMapperBuilderCustomizer}；</li>
 *       <li>B 侧的 {@code com.rag.users.common.http.GoStyleCoercion}（本域私有的 Jackson 3 副本，
 *           且<b>随 D5 已删</b>）⇒ 本仓改用共享层 {@link Coercion}
 *           （{@code com.rag.common.web.Coercion} · D3 上收 · Jackson 2 写法）。</li>
 *     </ul>
 *     <b>规则本身逐条未动</b>：仍然是「关掉标量强转 + 关掉浮点截断」这两条（{@code Coercion} 的
 *     {@code applyTextualCoercions} 与 {@code ACCEPT_FLOAT_AS_INT}）。</li>
 *   <li><b>语义还一样吗</b>：一样。
 *     <ul>
 *       <li>B 侧走 {@code JsonMapper.Builder#withCoercionConfig}（Jackson 3 只能在 builder 上配）；
 *           本仓走 {@code Jackson2ObjectMapperBuilder#postConfigurer} → {@code ObjectMapper#coercionConfigFor}
 *           （Spring 6.2 的 builder <b>没有</b> coercion API ⇒ builder 路线走不通）
 *           ⇒ ⇒ <b>两条路径最终都落到同一份规则实现</b>（{@code Coercion} 的唯一点）⇒ 规则不可能分叉。</li>
 *       <li>⚠️ <b>共享层也已施加同一条规则</b>（{@code JsonBodyConverterAutoConfiguration#seaCoercionMapperCustomizer}
 *           → 同样调 {@code Coercion::applyTo}）⇒ ⇒ 两处<b>幂等</b>（同一份规则施加两次 = 施加一次）
 *           ⇒ ⇒ 本 bean 的作用是「域侧自持一份，不依赖共享层的装配顺序」。</li>
 *     </ul></li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/config/WebMvcConfig.java}。</li>
 * </ol>
 *
 * <p>路径规则：
 * <ul>
 *   <li>{@link AdminJwtAuthInterceptor} 拦截 {@code /admin/v1/**}</li>
 *   <li>JWT 鉴权细节（public/protected 区分、blacklist 检查）在
 *       {@code AdminJwtAuthInterceptor#preHandle} 内部按 path 匹配完成
 *       （与 B 侧 rest.AddRoutes 块区分对齐）</li>
 *   <li>⚠️ 拦截器覆盖集合必须与 {@code AdminJwtAuthPaths.isProtected} 覆盖<b>同一集合</b>
 *       ⇒ 少覆盖 ⇒ 拿不到 Filter 的验签结果（落兜底 ⇒ 验签退回 3s 预算之内）；
 *       多覆盖 ⇒ 只会多做一次无用验签，<b>不构成鉴权绕过</b>。</li>
 * </ul>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminJwtAuthInterceptor jwtAuthInterceptor;

    public WebMvcConfig(AdminJwtAuthInterceptor jwtAuthInterceptor) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
    }

    /**
     * E-3 根因 R1：把强转规则施加到 <b>本模块 MVC</b> 的 Jackson 2 mapper 上。
     *
     * <p><b>为什么是 {@code postConfigurer} 而不是改 converter 上的 ObjectMapper</b>：
     * <ul>
     *   <li>Boot 3.5 的 {@code Jackson2ObjectMapperBuilder} <b>没有</b> coercion API
     *       （只有 {@code featuresToDisable} / {@code featuresToEnable} 等）⇒ builder 路线走不通；</li>
     *   <li>但 {@code Jackson2ObjectMapperBuilder#postConfigurer(Consumer<ObjectMapper>)} <b>存在</b>
     *       ⇒ ⇒ 可以在 builder 构建完之后对 mapper 施加规则。</li>
     * </ul>
     *
     * <p><b>只动 MVC 这一个 mapper</b>：gRPC 用 protobuf marshaller、
     * MyBatis 与 {@code JsonTextTypeHandler} 各持自己的 Jackson 2 mapper，
     * 都与 go-zero 的 http 解析层无关，不该被这两条规则波及。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer goStyleCoercionCustomizer() {
        // R1 的规则是**全局**的 —— Jackson 的类级 coercion 配置实测无效，
        // 详见共享层 Coercion 类注释的「第三个坑」。
        // 因此 B 侧走 form 通道的 GetUserListReq.keyword 由 DTO 自己字符串化，不在这里豁免。
        return builder -> builder.postConfigurer(Coercion::applyTo);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/admin/v1/**");
    }
}
