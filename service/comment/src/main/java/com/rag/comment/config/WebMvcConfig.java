package com.rag.comment.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.rag.comment.interceptor.CommentJwtAuthInterceptor;
import com.rag.common.web.Coercion;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>comment 域 Web MVC 配置</b> —— 对应 B 侧
 * {@code comment-api/…/api/config/WebMvcConfig.java:15-45}。
 *
 * <p><b>⚠️ L4 落点变化（D1 + 域内隔离）</b>：
 * B 侧 {@code com.rag.comment.comment.api.config.WebMvcConfig}
 * ⇒ C 侧 {@code com.rag.comment.config.WebMvcConfig}。
 *
 * <h2>🔴 拦截器注册的路径前缀是 <b>L1 契约</b></h2>
 * <p>{@code /comment/v1/**} —— 与 B 侧逐字一致。
 * ⇒ 7 条端点<b>全部</b>要 JWT。
 * <p>⚠️ <b>public 路径集 = 空集</b>：B 侧的 {@code CommentJwtAuthInterceptor}
 * <b>没有</b> blacklist 中间件，也没有 public 前缀放行分支 ⇒ 7/7 全部要鉴权。
 * ⇒ ⇒ 本类<b>不</b>调 {@code excludePathPatterns}（调了就是放行未鉴权路径 = 改 L1 行为）。
 *
 * <h3>⚠️ Jackson 强转（{@code CommentGoStyleCoercion}）的落点（D3 · 收进共享层）</h3>
 * <p>B 侧的 {@code comment-api/…/api/config/CommentGoStyleCoercion.java}
 * ⇒ C 侧收进共享层：{@code com.rag.common.web.Coercion} ＋
 * {@code com.rag.common.autoconfigure.JsonBodyConverterAutoConfiguration}
 * ⇒ <b>本域零文件</b>（与 like / article 域同款）。
 * <p>⚠️ <b>但 Boot 3.5 上的注册方式不同</b>：Boot 3.5 的 builder <b>没有</b> {@code withCoercionConfig}
 * ⇒ 走 {@code postConfigurer} 路线（与 like 域 {@code WebMvcConfig} 同款）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 受保护路径前缀（B 侧 {@code COMMENT_V1_PATTERN}，逐字一致）。 */
    private static final String COMMENT_V1_PATTERN = "/comment/v1/**";

    private final CommentJwtAuthInterceptor commentJwtAuthInterceptor;

    public WebMvcConfig(CommentJwtAuthInterceptor commentJwtAuthInterceptor) {
        this.commentJwtAuthInterceptor = commentJwtAuthInterceptor;
    }

    /**
     * Jackson 强转定制 —— B 侧 {@code CommentGoStyleCoercion.applyTo(builder)} 的等价物。
     *
     * <p>⚠️ <b>Boot 3.5 上 builder 没有 {@code withCoercionConfig}</b>
     * ⇒ 走 {@code postConfigurer} 路线（与 like 域同款）。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer commentGoStyleCoercionCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            // ① 共享层的两条通用规则（Textual ← 数字/布尔 = Fail；ACCEPT_FLOAT_AS_INT = false）
            Coercion.applyTo(mapper);
            // ② B 侧 CommentGoStyleCoercion 的第 1 条：Integer ← String = Fail
            mapper.coercionConfigFor(LogicalType.Integer)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            // ③ B 侧 CommentGoStyleCoercion 的第 2 条：Float ← String = Fail
            mapper.coercionConfigFor(LogicalType.Float)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            // ④ B 侧 CommentGoStyleCoercion 的第 3 条：关闭「尾部多余 token」检查
            //    （Go 的 encoding/json 解码完一个值后<b>忽略</b>尾随内容 ⇒ 对齐 Go）
            mapper.disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(commentJwtAuthInterceptor)
                .addPathPatterns(COMMENT_V1_PATTERN);
        // ⚠️ **不调 excludePathPatterns** —— B 侧的 public 路径集是<b>空集</b>
        //    （7/7 端点全部要 JWT）⇒ 加任何排除项都是放行未鉴权路径。
    }
}
