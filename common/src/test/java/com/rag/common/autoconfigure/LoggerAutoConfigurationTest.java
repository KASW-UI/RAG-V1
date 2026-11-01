package com.rag.common.autoconfigure;

import com.rag.common.logger.LoggerFactoryBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>共享日志门面真的被 auto-config 装配出来</b>（不需要域侧任何代码）。
 *
 * <p>⚠️ <b>抄写基准 = RAG</b>：{@code service/common/spring/LoggerFactoryBean.java}（L2 · D13）
 * ＋ <b>装配形态由 {@code @Component} + 组件扫描回改为 auto-config 的 {@code @Bean}</b>（L4 · D3）。
 */
class LoggerAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoggerAutoConfiguration.class));

    @Test
    @DisplayName("auto-config 真的产出 LoggerFactoryBean（消费方零装配代码）")
    void loggerFactoryBeanIsRegistered() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(LoggerFactoryBean.class);
            LoggerFactoryBean bean = context.getBean(LoggerFactoryBean.class);
            assertThat(bean.getServiceName()).isEqualTo("unknown-service");
        });
    }

    @Test
    @DisplayName("serviceName 取自 spring.application.name")
    void serviceNameComesFromApplicationName() {
        runner.withPropertyValues("spring.application.name=article-service")
                .run(context -> {
                    LoggerFactoryBean bean = context.getBean(LoggerFactoryBean.class);
                    assertThat(bean.getServiceName()).isEqualTo("article-service");
                });
    }

    @Test
    @DisplayName("域侧自定义的 Bean 优先（@ConditionalOnMissingBean）")
    void userDefinedBeanWins() {
        runner.withBean(LoggerFactoryBean.class, () -> new LoggerFactoryBean("domain-override"))
                .run(context -> {
                    LoggerFactoryBean bean = context.getBean(LoggerFactoryBean.class);
                    assertThat(bean.getServiceName()).isEqualTo("domain-override");
                });
    }

    @Test
    @DisplayName("空 serviceName ⇒ 构造期直接抛 IllegalStateException（对齐 Go 的 Init 守卫）")
    void emptyServiceNameIsRejected() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new LoggerFactoryBean("")))
                .hasMessageContaining("spring.application.name");
    }
}
