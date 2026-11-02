package com.rag.user.config;

import com.rag.user.interceptor.JwtAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>Spring MVC 装配点</b> —— 把 {@link JwtAuthInterceptor} 注册到 {@code /user/v1/**}。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/config/WebMvcConfig.java}
 * （{@code registry.addInterceptor(jwtAuthInterceptor).addPathPatterns("/user/v1/**")}）。
 *
 * <p><b>L1 契约：拦截路径前缀 {@code /user/v1/**} 逐条一致</b>（B 出处 {@code WebMvcConfig.java:57-58}）。
 *
 * <p><b>语义等价论证</b>：
 * <ul>
 *   <li>B 侧用 go-zero 的「注册时挂不挂中间件」表达 public/protected；
 *       C 侧用「路径前缀 ＋ 白名单」表达 ⇒ <b>表达层差异，语义等价</b>
 *       （public 集合 = {@code /user/v1/register} ＋ {@code /user/v1/login}，逐条一致，
 *       由 {@code JwtAuthPaths} 承载）。</li>
 *   <li>public / protected 的判定<b>不</b>在本类，而在 {@code JwtAuthPaths}
 *       ⇒ 与 B 侧 {@code JwtAuthPaths} 的「判据只有一份」纪律一致。</li>
 * </ul>
 *
 * <p><b>⚠️ 为什么本类<b>不</b>注册 Jackson 强转 customizer</b>：
 * B 侧 {@code WebMvcConfig} 用 {@code JsonMapperBuilderCustomizer}（Boot 4 / Jackson 3）施加
 * go-zero 风格强转规则；但 C 侧的共享层 {@code JsonBodyConverterAutoConfiguration}
 * <b>已经</b>用 {@code Jackson2ObjectMapperBuilderCustomizer#postConfigurer} 全局施加了同两条规则
 * （{@code com.rag.common.web.Coercion}）⇒ ⇒ 域内再注册一次 = <b>同一条规则施加两遍</b>
 * ⇒ ⇒ 本类<b>刻意不注册</b>（不是「忘了」，是共享层已收口）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;

    public WebMvcConfig(JwtAuthInterceptor jwtAuthInterceptor) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/user/v1/**");
    }
}
