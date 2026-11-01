package com.rag.common.autoconfigure;

import com.rag.common.logger.LoggerFactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * <b>共享日志门面的装配点</b> —— 产出 {@link LoggerFactoryBean}。
 *
 * <p><b>Go 出处</b>：{@code service/common/logger/logger.go:63-70}
 * （{@code func Init(svcName string)} ⇒ 服务名的<b>唯一</b>来源，且<b>空名 panic</b>）。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/spring/LoggerFactoryBean.java}
 * 整段抄（L2 · D13）＋ <b>装配形态回改</b>（L4 · D3）。
 *
 * <h2>⚠️ 为什么从 {@code @Component} 改成 auto-config 里的 {@code @Bean}</h2>
 * <p>RAG 侧本类是 {@code @Component}，靠各 api 模块的 {@code scanBasePackages} 扫
 * {@code com.rag.common} 注册 ⇒ <b>rpc 模块不扫</b>（会把 servlet 类拖进来）⇒
 * rpc 模块只能靠 {@code @Import} 清单显式带上 ⇒ <b>两种注册方式并存</b>。
 * <p>本仓 D1 已把 api 与 rpc <b>合并成一个进程</b>，D3 又把共享层做成 <b>真 starter</b>
 * ⇒ ⇒ 消费方<b>零 {@code @Import} 清单</b>（A5 硬要求）⇒ ⇒ 本类改由本 auto-config 产出
 * ⇒ ⇒ <b>10 个域一次全挂上</b>，不需要任何域侧代码。
 *
 * <h2>⚠️ serviceName 仍是各自独立的</h2>
 * <p>Bean 的 serviceName 由 Spring 构造注入；{@code GoStyleLogger.init(...)} 设置的
 * 全局 serviceName 与它互不干扰 —— 委托只复用<b>算法</b>，不共享 serviceName 状态。
 */
@AutoConfiguration
public class LoggerAutoConfiguration {

    /**
     * 产出共享日志门面。
     *
     * @param serviceName 从 {@code spring.application.name} 注入的服务名
     */
    @Bean
    @ConditionalOnMissingBean
    public LoggerFactoryBean loggerFactoryBean(
            @Value("${spring.application.name:unknown-service}") String serviceName) {
        return new LoggerFactoryBean(serviceName);
    }
}
