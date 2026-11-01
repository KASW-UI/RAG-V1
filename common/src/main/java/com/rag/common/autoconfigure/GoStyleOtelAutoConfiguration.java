package com.rag.common.autoconfigure;

import com.rag.common.observability.GoStyleOtelAgent;
import com.rag.common.observability.GoStyleOtelConfig;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 🔴 **全局 OpenTelemetry 的装配点** —— 对应 Go 侧
 * {@code rest/server.go:49}（{@code c.SetUp()}）→
 * {@code core/service/serviceconf.go:67}（{@code trace.StartAgent(sc.Telemetry)}）。
 *
 * <h2>🔴🔴 为什么装配点必须是 <b>BeanFactoryPostProcessor</b>，而不是普通 Bean</h2>
 * <p>OTel Java 的 {@code GlobalOpenTelemetry.set(...)} 有两条硬约束：
 * <ol>
 *   <li><b>只能调一次</b>；</li>
 *   <li>🔴 <b>先调 {@code get()} 会让后续 {@code set()} 抛
 *       {@code IllegalStateException}</b>（{@code get()} 会先把全局置成 no-op 占位）。</li>
 * </ol>
 * 而本仓有<b>构造器里就调 {@code get()} 的地方</b>
 * （例如 {@code common/web/TraceparentFilter} 的 {@code tracer()}、
 * {@code common/observability/GoStyleOtelAgent#tracer}）
 * ⇒ ⇒ <b>普通 Bean 装配会被构造器抢跑</b>（bean 创建顺序不确定）⇒ 全局永远装不上。
 * ⇒ ⇒ {@code BeanFactoryPostProcessor} 在<b>所有单例创建之前</b>执行 ⇒ 唯一安全的位置。
 *
 * <h2>🔴🔴 为什么用 {@code AutoConfiguration.imports}，而不是组件扫描</h2>
 * <p>本仓的共享层是 <b>starter</b>（D3）⇒ 消费方<b>零装配代码</b> ⇒
 * ⇒ 必须走 Spring Boot 的<b>自动配置清单</b>
 * （{@code META-INF/spring/…AutoConfiguration.imports}）⇒
 * <b>不依赖组件扫描</b> ⇒ 只要进程依赖了 {@code common} 且是 Spring Boot 应用，就自动生效
 * ⇒ ⇒ <b>零域模块改动</b>。
 *
 * <h2>装配失败<b>不阻断启动</b></h2>
 * <p>照 Go 的 {@code core/trace/agent.go:49-51}（{@code startAgent} 失败只 {@code logx.Error}）
 * ⇒ 见 {@link GoStyleOtelAgent#install}。
 *
 * <h2>抄写基准</h2>
 * <p>RAG {@code service/common/src/main/java/com/rag/common/observability/GoStyleOtelAutoConfiguration.java}
 * 整段抄（L2 · D13）＋ <b>包名回改</b>（{@code com.rag.common.observability} →
 * {@code com.rag.common.autoconfigure}，与本仓其余 13 个 auto-config 同包，L4 · D11/D3）。
 */
@AutoConfiguration
public class GoStyleOtelAutoConfiguration {

    /**
     * 🔴 <b>必须是 {@code static}</b>：静态 {@code @Bean} 方法不需要先实例化配置类本身
     * ⇒ 能在最早期被取到 ⇒ 不会与「构造器里调 {@code GlobalOpenTelemetry.get()}」抢跑。
     */
    @Bean
    static BeanFactoryPostProcessor goStyleOtelGlobalRegistrar() {
        return new OtelGlobalRegistrar();
    }

    /**
     * 真正的装配器：<b>同时</b>实现 {@link BeanFactoryPostProcessor} 与 {@link EnvironmentAware}。
     *
     * <p>⚠️ 为什么 {@code EnvironmentAware} 而不是从 {@code ConfigurableListableBeanFactory}
     * 上取 {@code getEnvironment()}：<b>后者不存在</b>（{@code getEnvironment()} 在
     * {@code ConfigurableApplicationContext} 上，不在 BeanFactory 上）⇒ 编译期就会暴露。
     */
    static final class OtelGlobalRegistrar implements BeanFactoryPostProcessor, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            GoStyleOtelAgent.install(GoStyleOtelConfig.from(environment));
        }
    }
}
