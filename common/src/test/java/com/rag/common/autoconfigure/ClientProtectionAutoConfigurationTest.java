package com.rag.common.autoconfigure;

import com.rag.common.protection.ClientCircuitGateInterceptor;
import com.rag.common.protection.OutboundDeadlineInterceptor;
import io.grpc.ClientInterceptor;
import net.devh.boot.grpc.client.interceptor.AnnotationGlobalClientInterceptorConfigurer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>客户端拦截器真的进了 devh 的收集器</b>——
 * 不是「Bean 存在」。
 *
 * <p>⚠️ <b>为什么「Bean 存在」不算证据</b>（AGENTS.md §3.3）：
 * devh 的 {@code AnnotationGlobalClientInterceptorConfigurer} 是用
 * {@code applicationContext.getBeansWithAnnotation(GrpcGlobalClientInterceptor.class)}
 * 收集拦截器的 ⇒ ⇒ <b>漏了那个类上注解 ⇒ Bean 在、registry 空 ⇒ 熔断代码一次都不会执行</b>
 * ⇒ ⇒ 本测试<b>直接调用 devh 自己的收集器</b> ⇒ ⇒ 断言它真的把两个拦截器收进去了。
 */
class ClientProtectionAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ClientProtectionAutoConfiguration.class));

    @Test
    @DisplayName("devh 的真实收集器真的收到了出向熔断 + 出向 deadline 两个拦截器")
    void devhCollectorReallyPicksUpBothInterceptors() {
        runner.run(context -> {
            // ⚠️ 用 **devh 自己的**收集器，而不是「容器里有没有这个 Bean」
            AnnotationGlobalClientInterceptorConfigurer configurer =
                    new AnnotationGlobalClientInterceptorConfigurer(context.getSourceApplicationContext());
            List<ClientInterceptor> collected = new ArrayList<>();
            configurer.configureClientInterceptors(collected);

            assertThat(collected)
                    .hasAtLeastOneElementOfType(ClientCircuitGateInterceptor.class)
                    .hasAtLeastOneElementOfType(OutboundDeadlineInterceptor.class);
        });
    }

    @Test
    @DisplayName("两个客户端拦截器都由共享层 1 份产出（域内 0 重复 ⇒ 不会双层拦截）")
    void singleDefinitionEach() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ClientCircuitGateInterceptor.class);
            assertThat(context).hasSingleBean(OutboundDeadlineInterceptor.class);
        });
    }
}
