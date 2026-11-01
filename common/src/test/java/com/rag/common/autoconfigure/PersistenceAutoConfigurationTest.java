package com.rag.common.autoconfigure;

import com.rag.common.persistence.DeadlineQueryTimeoutInjector;
import com.rag.common.persistence.SqlTimeoutStatusTranslator;
import org.apache.ibatis.plugin.Interceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>SQL 超时链路真的挂上了</b>（DRIFT-COMMON-V02 的修法验证）。
 *
 * <p>⚠️ <b>为什么必须有这条测试</b>：
 * 两个拦截器类<b>早就实现好了</b>（批 1 落地）⇒ 但**没有任何注册点**
 * ⇒ MyBatis-Plus 收不到 ⇒ <b>整条链静默失效</b>（SQL 不带超时、pg 的 57014 不翻译），
 * 且<b>编译期不报错、启动期不报错</b>。
 * <br>⇒ ⇒ 修法 = 新增 {@link PersistenceAutoConfiguration}（共享层 1 份），
 * 本测试断言它<b>真的</b>让 MP 的收集点看见这两个拦截器。
 *
 * <p>⚠️ <b>为什么用「与 MP 完全同形的注入点」取证</b>：
 * MyBatis-Plus 3.5.17 的 {@code MybatisPlusAutoConfiguration} 构造器形参是
 * {@code ObjectProvider<Interceptor[]>}，且<b>构造器里就</b> {@code getIfAvailable()}
 * （{@code javap -c} 实测）⇒ ⇒ 本测试用<b>同一个</b> {@code ObjectProvider<Interceptor[]>}
 * 注入点 ⇒ 取到的就是 MP 会取到的那一份 ⇒ <b>不是「Bean 存在」这种弱证据</b>。
 */
class PersistenceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PersistenceAutoConfiguration.class));

    @Test
    @DisplayName("🔴 V02：两个 MyBatis 拦截器真的注册成 Bean（此前零注册点 ⇒ 整条链静默失效）")
    void bothInterceptorsAreRegisteredAsBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DeadlineQueryTimeoutInjector.class);
            assertThat(context).hasSingleBean(SqlTimeoutStatusTranslator.class);
            // ⚠️ 两个都是 org.apache.ibatis.plugin.Interceptor ⇒ MP 按类型收 ⇒ 必须是 2 个
            assertThat(context.getBeansOfType(Interceptor.class)).hasSize(2);
        });
    }

    @Test
    @DisplayName("🔴 V02：与 MP 完全相同的注入点（ObjectProvider<Interceptor[]>）真的拿到这两个拦截器")
    void mybatisPlusCollectionPointReallySeesThem() {
        runner.withUserConfiguration(CollectorConfiguration.class).run(context -> {
            InterceptorCollector collector = context.getBean(InterceptorCollector.class);
            assertThat(collector.interceptors)
                    .as("MP 的构造器就是靠这个注入点收集拦截器的 ⇒ 空数组 ⇒ 拦截器全部落空")
                    .isNotNull()
                    .hasSize(2);
            assertThat(Arrays.stream(collector.interceptors).map(o -> o.getClass().getSimpleName()))
                    .containsExactlyInAnyOrder("DeadlineQueryTimeoutInjector", "SqlTimeoutStatusTranslator");
        });
    }

    @Test
    @DisplayName("🔴 V02：域内已有同类型 Bean 时共享层让位（不产生第二份）")
    void backsOffWhenDomainAlreadyDefinesOne() {
        runner.withBean(DeadlineQueryTimeoutInjector.class, DeadlineQueryTimeoutInjector::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(DeadlineQueryTimeoutInjector.class);
                    // 另一个仍然由共享层提供
                    assertThat(context).hasSingleBean(SqlTimeoutStatusTranslator.class);
                });
    }

    /**
     * 与 {@code MybatisPlusAutoConfiguration} 构造器<b>完全同形</b>的收集点。
     */
    static class InterceptorCollector {

        final Interceptor[] interceptors;

        InterceptorCollector(ObjectProvider<Interceptor[]> provider) {
            this.interceptors = provider.getIfAvailable();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CollectorConfiguration {

        @Bean
        InterceptorCollector interceptorCollector(ObjectProvider<Interceptor[]> provider) {
            return new InterceptorCollector(provider);
        }
    }
}
