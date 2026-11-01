package com.rag.common.autoconfigure;

import com.rag.common.protection.ClientCircuitGateInterceptor;
import com.rag.common.protection.OutboundDeadlineInterceptor;
import com.rag.common.protection.ProtectionProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * <p><b>gRPC 客户端侧的共享装配点</b>：出向<b>熔断</b> ＋ 出向 <b>deadline 传播</b>。
 *
 * <p>RAG 出处：{@code service/common/protection/GoStyleGrpcClientBreakerConfiguration.java}
 * ＋ {@code GoStyleOutboundDeadlineConfiguration.java}
 * （抄写基准 = RAG，L2 业务语义层整段抄 · D13）。
 *
 * <p><b>⚠️ 为什么用「自动配置」而不是 RAG 的「{@code @Configuration} + {@code @Import}」</b>
 * RAG 侧靠 {@code GoStyleProtectionConfiguration} 的 {@code @Import} 把两个配置类带进
 * 10 个 rpc 组件 ⇒ 本仓 D3（starter 化）要求<b>消费方零 {@code @Import}</b>
 * （守门 A5 也会拦）⇒ ⇒ 本类登记进 {@code AutoConfiguration.imports}
 * ⇒ ⇒ 10 个域一次全挂上。
 *
 * <p><b>⚠️ {@code @ConditionalOnClass} 为什么用 {@code name = ...}（字符串形式）</b>
 * 本类的 {@code @Bean} 方法返回类型引用了 {@code io.grpc.ClientInterceptor}
 * ⇒ 若 grpc 不在 classpath，<b>加载本类就会失败</b> ⇒ 条件必须写成字符串形式，
 * 让 Spring 用 ASM 读元数据、<b>在加载本类之前</b>完成判定。
 *
 * <p><b>⚠️ 「Bean 存在 ≠ 机制生效」</b>（AGENTS.md §3.3）：devh 认的是<b>类上的
 * {@code @GrpcGlobalClientInterceptor} 注解</b>，真正的收集发生在 starter 的
 * {@code GlobalClientInterceptorRegistry} ⇒ ⇒ 锁定用例断言的是「它在 registry 里」，
 * 而<b>不是</b>「容器里有个 Bean」。
 */
@AutoConfiguration
@AutoConfigureAfter(ProtectionAutoConfiguration.class)
@ConditionalOnClass(name = {
        "net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor",
        "io.grpc.ManagedChannel"
})
@EnableConfigurationProperties(ProtectionProperties.class)
public class ClientProtectionAutoConfiguration {

    /**
     * 出向熔断拦截器（Go：{@code zrpc/internal/client.go:112-113}）。
     *
     * <p>⚠️ <b>隔离 key = (下游服务, 方法)</b> ⇒ 两个下游服务调用<b>同名方法</b>时
     * <b>不共用</b>熔断器（否则 A 服务的失败会拖开 B 服务的熔断器 ⇒ 跨服务污染）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ClientCircuitGateInterceptor clientCircuitGateInterceptor(
            ProtectionProperties properties,
            ObjectProvider<CircuitBreakerRegistry> registryProvider) {
        return new ClientCircuitGateInterceptor(
                properties.getBreaker().isEnabled(), registryProvider.getIfAvailable());
    }

    /**
     * 出向 deadline 传播拦截器（Go：{@code zrpc/internal/clientinterceptors/timeoutinterceptor.go:25}）。
     *
     * <p>⚠️ <b>客户端硬上界默认不启用</b>（{@code capMillis = 0}）——
     * Go 的 2000ms 是<b>默认值</b>而<b>各域可覆盖</b> ⇒ 写成全局默认会让某些域比 Go 严几十倍
     * ⇒ ⇒ <b>逐域启用</b>登记在 L3 清单（域侧 yml 是各域 lane 的写权面）。
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboundDeadlineInterceptor outboundDeadlineInterceptor(ProtectionProperties properties) {
        ProtectionProperties.OutboundDeadline cfg = properties.getOutboundDeadline();
        Map<String, Long> byService = cfg.getCapMillisByService();
        return new OutboundDeadlineInterceptor(
                cfg.isEnabled(), cfg.getCapMillis(),
                byService == null ? Map.of() : byService);
    }
}
