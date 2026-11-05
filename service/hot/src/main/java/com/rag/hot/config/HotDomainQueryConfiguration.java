package com.rag.hot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * <p><b>域内查询方向的执行器</b> —— 给「hot-api → hot-rpc 域内直连」这条方向提供
 * <b>可超时的执行载体</b>。</p>
 *
 * <h2>⚠️ 为什么需要它（背景）</h2>
 * <p>B 侧这条方向走 gRPC ⇒ 客户端出向 deadline（{@code cap-millis} = 2000ms）⇒
 * <b>Redis 卡住时 2s 就失败</b> ⇒ hot-api 侧收到失败 ⇒ 走 fatal ⇒ <b>HTTP 200 + 5001 业务信封</b>。</p>
 * <p>本仓 D2 改成<b>域内直连</b>（同进程调用）⇒ <b>没有 gRPC ⇒ 没有任何出向上界</b>
 * ⇒ Redis 要等到自己的 socket timeout（3s）才抛，而 HTTP 层的请求预算也是 3s
 * ⇒ ⇒ <b>两者同时到点</b> ⇒ 落进共享层的「HTTP 预算 × 业务失败」竞态（F-15）
 * ⇒ ⇒ 客户端可能拿到 <b>503 + 纯文本</b>（<b>不是</b>业务信封）。</p>
 * <p>⇒ ⇒ 本执行器让域内调用<b>可以被 2s 上界打断</b> ⇒ ⇒ 失败形状回到与 B 侧一致。</p>
 *
 * <h2>⚠️ 为什么用虚拟线程的 {@link SimpleAsyncTaskExecutor}</h2>
 * <ul>
 *   <li><b>生态优先</b>（AGENTS.md §八）：Spring 自带 {@code SimpleAsyncTaskExecutor} 的
 *       虚拟线程模式（{@code setVirtualThreads(true)}）⇒ <b>不手写</b>线程池、
 *       <b>不</b>手写生命周期（它没有 {@code close()}，虚拟线程是 daemon ⇒ JVM 退出不受阻）。</li>
 *   <li><b>不能排队</b>（关键）：普通线程池在负载高时会<b>排队</b> ⇒ 排队时间会算进超时预算
 *       ⇒ ⇒ 语义偏离 B 侧（gRPC 是<b>直发</b>，客户端侧不排队）。
 *       虚拟线程<b>不排队</b> ⇒ ⇒ 每个请求的域内调用<b>立即开始</b> ⇒ 与 B 侧同形。</li>
 *   <li><b>不能用 {@code hotHydrationExecutor}</b>：它的拒绝策略是 {@code CallerRunsPolicy}
 *       ⇒ 池满时<b>调用线程自己跑</b> ⇒ ⇒ <b>超时永远等不到</b> ⇒ 上界形同虚设。</li>
 * </ul>
 *
 * <h2>⚠️ 超时后原线程还在跑吗</h2>
 * <p><b>会</b>（Redis 读继续到它自己的 socket timeout）⇒ ⇒ 这与 B 侧<b>完全同形</b>：
 * B 侧 gRPC deadline 到点后客户端放弃，<b>hot-rpc 服务端那边照样在跑</b>。
 * ⇒ 两者都是「客户端先放弃、后台继续」。</p>
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧 {@code service/hot/hot-api/src/main/resources/application.yml:154-171}
 *       （{@code rag.protection.outbound-deadline.cap-millis: 2000}，
 *       注释明写「Go 出处：go-zero@v1.10.2/zrpc/config.go:31 {@code Timeout int64 json:",default=2000"}」）；</li>
 *   <li>B 侧 {@code service/common/.../observability/HttpMiddleware.java}（出向封顶的施加点）。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class HotDomainQueryConfiguration {

    /** 域内查询执行器的 bean 名（注入点用它）。 */
    public static final String DOMAIN_QUERY_EXECUTOR_BEAN_NAME = "hotDomainQueryExecutor";

    /**
     * 域内查询的执行器（<b>虚拟线程</b> ⇒ 不排队 ⇒ 与 gRPC 的「直发」同形）。
     *
     * @return 执行器
     */
    @Bean(DOMAIN_QUERY_EXECUTOR_BEAN_NAME)
    public SimpleAsyncTaskExecutor hotDomainQueryExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("hot-domain-query-");
        // ⚠️ 虚拟线程：Spring Framework 6.1+ 的 API（Boot 3.5 = Spring 6.2 ⇒ 可用）
        executor.setVirtualThreads(true);
        // ⚠️ 并发上限：默认 -1（不限制）⇒ 与「虚拟线程 + 不排队」的意图一致
        executor.setConcurrencyLimit(-1);
        return executor;
    }
}
