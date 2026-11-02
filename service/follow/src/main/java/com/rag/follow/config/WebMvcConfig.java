package com.rag.follow.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.rag.follow.interceptor.FollowBlacklistInterceptor;
import com.rag.follow.interceptor.FollowJwtAuthInterceptor;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>Spring MVC 装配点</b> —— 挂两条鉴权路径 ＋ 注册本域那条 Jackson 强转补强。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/config/WebMvcConfig.java:51-80}
 * （拦截器注册顺序与 Jackson 规则逐条照抄）。
 *
 * <h2>⚠️ 拦截器注册顺序 = B 侧的 chain 顺序（逐字对照）</h2>
 * <pre>
 *   B 出处：B 侧 {@code rest/engine.go:101-116} bindRoute
 *     chn := ng.buildChainWithNativeMiddlewares(...)   // native 中间件
 *     chn  = ng.appendAuthHandler(fr, chn, verifier)  // ← rest.WithJwt 插在这里
 *     for _, mw := range ng.middlewares { chn = chn.Append(mw) }  // ← 用户中间件（黑名单）
 *     handle := chn.ThenFunc(route.Handler)
 *
 *   B 侧 {@code rest/chain/chain.go} 的 Then 是 m0(m1(...mN(h)))，索引 0 最外层、先执行
 *   ⇒ 真实顺序：… native … → Authorize(JWT) → CheckBlacklist → handler
 * </pre>
 * <p>Spring 的 {@link InterceptorRegistry#addInterceptor} 同理「先注册先执行」，
 * 所以本类<b>必须先注册 JWT、再注册黑名单</b>。顺序反了会怎样：
 * 一个<b>签名无效但已在黑名单中</b>的 token，B 侧是 {@code Authorize} 先失败 → 401 空 body、
 * <b>黑名单从不查询</b>；反过来则会对畸形 token 产生一次多余的 Redis 读，
 * 且最终响应仍是 401 空 body（只是多了一次下游依赖调用，压测下可观测）。
 *
 * <p><b>路径集合</b>：{@code /follow/v1/**} 覆盖 B 侧全部 8 个端点，
 * <b>没有 exclude、没有 public 路径</b> —— B 侧的
 * {@code routes.go:16-64} 是一段 {@code AddRoutes} 同时挂
 * {@code rest.WithJwt} 与 {@code CheckBlacklistMiddleware}。
 *
 * <h2>⚠️ 本域自己叠加第 3 条 Jackson 强转规则（共享层那两条之外）</h2>
 * <p>B 出处：B 侧 {@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/config/FollowGoStyleCoercion.java:74-81}
 * —— <b>follow 域独有</b>的一条补强：
 * <b>{@code LogicalType.Integer + CoercionInputShape.String → Fail}</b>。
 * <p><b>为什么必须在本域叠加、而不是改共享层</b>：共享层 {@code com.rag.common.web.Coercion}
 * 那两条规则（第 1 条「数字/布尔不进 String 字段」、第 2 条「浮点不进整数字段」）是<b>全仓共用</b>的；
 * 而 B 侧 users-common 的共享规则<b>刻意保留</b>了「String → 整数字段保持接受」
 * （users 域的 uid 带 {@code ,string}，需要这条）⇒ 那条豁免对 users 成立、对 follow <b>不成立</b>
 * ⇒ ⇒ 改共享规则会连带把 users/admin 域已验证过的行为改坏 ⇒ 本域<b>只叠加自己那一条</b>。
 *
 * <h3>⚠️ 这条规则到底挡什么（B 侧推导 + C 侧实测口径）</h3>
 * <pre>
 *   body: {"offset":"10"}     // offset 是 int32 json:"offset,optional"，无 ,string
 *   ⇒ B 侧 opts.FromString == false ⇒ 走 processFieldNotFromString → processFieldPrimitive
 *      ⇒ typeKind(Int32) != valueKind(String) ⇒ newTypeMismatchError ⇒ 解析失败
 *   ⇒ 最终输出 200 + {code:5001}
 * </pre>
 * <p>⚠️ <b>而 Jackson 默认会放行</b>（默认允许标量强转，{@code {"offset":"10"}} 会安静地变成 10）
 * ⇒ 这是「不看 B 侧源码也会自然写成这样」的<b>高危漂移</b>（多放行不报错 ⇒ 最难发现的一类）。
 * <p>本域受这条规则影响的字段<b>恰好是两个</b>：{@code FollowListReq.offset} 与
 * {@code FollowListReq.limit}。⚠️ <b>它不会误伤 {@code ,string} 字段</b>：
 * {@code user_id} / {@code target_id} 的 Jackson 入参声明类型是 {@code Object}
 * ⇒ <b>没有</b>目标 {@code LogicalType} ⇒ 这条规则查不到它们
 * ⇒ 数字与字符串都原样递出，由 {@code com.rag.common.web.StringInt64} 逐条判定
 * ⇒ 两侧各走各的判据，<b>互不干扰</b>。
 *
 * <h3>⚠️ 为什么走 {@code postConfigurer} 而不是 builder 的 coercion API</h3>
 * <p>共享层 {@code com.rag.common.web.Coercion} 的类注释已实测记录：
 * Boot 3.5（Spring 6.2）的 {@code Jackson2ObjectMapperBuilder} <b>没有</b> coercion API
 * ⇒ 只能走 {@code postConfigurer(Consumer<ObjectMapper>)}，在 builder 构建完之后
 * 对 mapper 就地 {@code setCoercion}（{@code coercionConfigFor(...)} 返回的是<b>活对象</b>）。
 * ⇒ ⇒ 本类与共享层走<b>同一条路线</b>，不会分叉。
 *
 * <p><b>⚠️ 刻意不注册「共享那两条」的 customizer</b>：共享层的
 * {@code JsonBodyConverterAutoConfiguration#seaCoercionMapperCustomizer} 已经全局施加了
 * ⇒ 域内再注册一次 = <b>同一条规则施加两遍</b>（不是「忘了」，是共享层已收口）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** B 侧唯一一段路由组的前缀（{@code rest.WithPrefix("/follow/v1")}）。 */
    private static final String FOLLOW_PREFIX = "/follow/v1/**";

    private final FollowJwtAuthInterceptor followJwtAuthInterceptor;
    private final FollowBlacklistInterceptor followBlacklistInterceptor;

    public WebMvcConfig(FollowJwtAuthInterceptor followJwtAuthInterceptor,
                        FollowBlacklistInterceptor followBlacklistInterceptor) {
        this.followJwtAuthInterceptor = followJwtAuthInterceptor;
        this.followBlacklistInterceptor = followBlacklistInterceptor;
    }

    /**
     * 注册本域<b>独有</b>的那条 Jackson 强转补强（共享层那两条之外的第 3 条）。
     *
     * <p>⚠️ <b>bean 名字不能叫 {@code seaCoercionMapperCustomizer}</b>：
     * 共享层 {@code JsonBodyConverterAutoConfiguration} 用
     * {@code @ConditionalOnMissingBean(name = "seaCoercionMapperCustomizer")} 判「是否已有」
     * ⇒ 同名会让共享层那条规则整条消失（那两条是 users 域等已验证过的行为）。
     *
     * @return customizer：把 {@code LogicalType.Integer} 的 {@code String → Integer} 强转改成 {@code Fail}
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer followIntegerStringCoercionCustomizer() {
        // 理由见类注释「本域自己叠加第 3 条 Jackson 强转规则」
        return builder -> builder.postConfigurer(mapper ->
                mapper.coercionConfigFor(LogicalType.Integer)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ⚠️ 顺序即语义：JWT 在外、黑名单在内（见类注释的 B 侧 chain 逐行对照）。
        registry.addInterceptor(followJwtAuthInterceptor)
                .addPathPatterns(FOLLOW_PREFIX);

        registry.addInterceptor(followBlacklistInterceptor)
                .addPathPatterns(FOLLOW_PREFIX);
    }
}
