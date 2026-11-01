package com.rag.common.logger;

import org.springframework.beans.factory.annotation.Value;

/**
 * <p><b>{@code Logger} 的 Spring 适配</b> —— 把 {@code spring.application.name} 注入的
 * 服务名绑到一个<b>实例</b>上，并提供实例方法形态的三个日志入口。
 *
 * <p>RAG 出处：{@code service/common/spring/LoggerFactoryBean.java}
 * （抄写基准 = RAG，L2 业务语义层整段抄 · D13）。
 *
 * <p><b>Go 出处</b>：{@code service/common/logger/logger.go:63-70}
 * （{@code func Init(svcName string)} ⇒ 服务名的<b>唯一</b>来源，且<b>空名 panic</b>）
 * ＋ {@code service/common/logger/logger.go:98}（{@code func (l *Logger) logBusinessErr(...)}）。
 *
 * <p><b>⚠️ 为什么要这一层（B 侧的重构理由，逐条保留）</b>
 * B 侧此前存在<b>两份逐条同构</b>的日志实现（静态门面 ＋ 包私有 {@code doLogXxx}
 * 与本类的公开实例方法）⇒ 真实代价不是「重复」本身，而是<b>改一处忘一处</b>：
 * {@code call_chain} 过滤修过一次，若当时只改了一份，静态入口与 Bean 入口的
 * {@code call_chain} 就会<b>静默不一致</b>。
 * ⇒ ⇒ 本类<b>只做委托</b>，算法全部落在 {@link GoStyleLogger} 这一份实现上。
 *
 * <p><b>⚠️ L4 形态差（RAG → 本仓）</b>：RAG 侧本类是 {@code @Component}（靠
 * {@code scanBasePackages} 扫 {@code com.rag.common} 注册）；
 * 本仓的共享层是 <b>starter</b>（D3）⇒ 消费方<b>不再扫</b> {@code com.rag.common}
 * ⇒ ⇒ {@code @Component} 在本仓<b>永远扫不到</b> ⇒ 改由 auto-config 的
 * {@code @Bean} 产出 ⇒ 消费方<b>零装配代码</b>。
 *
 * <p><b>⚠️ serviceName 仍是各自独立的</b>（决策 3，不因委托而改变）：
 * Bean 的 serviceName 由 Spring 构造注入；{@code GoStyleLogger.init(...)} 设置的
 * 全局 serviceName 与它互不干扰 —— 委托只复用<b>算法</b>，不共享 serviceName 状态。
 *
 * <p><b>字段语义</b>与 Go 版 {@code logger.LogBusinessErr} 1:1 对齐：
 * service / event_id / trace_id / error_code / file_line / call_path / call_chain /
 * error_reason；可选 user_id / article_id（空字符串字段消失而非空串）。
 */
public class LoggerFactoryBean {

    /**
     * Spring 注入的 serviceName；与全局静态 serviceName 完全独立。
     */
    private final String serviceName;

    /**
     * 算法委托实例。serviceName 在构造时固定，与静态入口无关。
     */
    private final GoStyleLogger delegate;

    /**
     * Spring 构造注入 serviceName；不允许为空（与 {@code GoStyleLogger.init} 行为对齐）。
     *
     * @param serviceName 从 {@code spring.application.name} 注入的服务名；为空时抛 IllegalStateException
     */
    public LoggerFactoryBean(
            @Value("${spring.application.name:unknown-service}") String serviceName
    ) {
        if (serviceName == null || serviceName.isEmpty()) {
            throw new IllegalStateException("spring.application.name must not be empty");
        }
        this.serviceName = serviceName;
        this.delegate = new GoStyleLogger(serviceName);
    }

    public String getServiceName() {
        return serviceName;
    }

    /**
     * 业务错误日志，字段集合与静态入口一致
     * （service / event_id / trace_id / error_code / file_line / call_path / call_chain /
     * error_reason + 可选 user_id / article_id）。
     */
    public void logBusinessErr(io.opentelemetry.context.Context otelCtx,
                               int code,
                               Throwable err,
                               LogOption... opts) {
        delegate.doLogBusinessErr(otelCtx, code, err, opts);
    }

    /**
     * 信息日志。{@link FatalError} 与静态入口行为完全一致。
     */
    public void logInfo(io.opentelemetry.context.Context otelCtx,
                        String msg,
                        LogOption... opts) {
        delegate.doLogInfo(otelCtx, msg, opts);
    }

    /**
     * 致命日志：打出 {@code fatal_error} 后抛 {@link FatalError}，
     * <b>不</b>调 {@code System.exit}（见 AGENTS.md §3.4.1 A-1 已签字偏离）。
     */
    public void logFatal(io.opentelemetry.context.Context otelCtx,
                         Throwable err,
                         LogOption... opts) {
        delegate.doLogFatal(otelCtx, err, opts);
    }
}
