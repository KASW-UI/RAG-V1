package com.rag.common.protection;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/**
 * L4 idiomatization：并发连接数上限，对齐 B 侧 {@code handler.MaxConnsHandler}。
 *
 * <p><b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/protection/GoStyleMaxConnsFilter.java:21:12-36}
 * <pre>
 * func MaxConnsHandler(n int) func(http.Handler) http.Handler {
 *     if n &lt;= 0 { return func(next) { return next } }
 *     return func(next http.Handler) http.Handler {
 *         latch := syncx.NewLimit(n)                       // 整个 server 共享一个闸门
 *         return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
 *             if latch.TryBorrow() {
 *                 defer latch.Return()
 *                 next.ServeHTTP(w, r)
 *             } else {
 *                 internal.Errorf(r, "concurrent connections over %d, ...")
 *                 w.WriteHeader(http.StatusServiceUnavailable)   // ← 503，无 body
 *             }
 *         })
 *     }
 * }
 * </pre>
 *
 * <p><b>⚠️ 为什么 Tomcat 自带的配额不能替代本层（这是本层必须自定义的核心理由）</b>：
 * Tomcat 有三个参数看起来很像，但<b>没有一个</b>与 B 侧 语义等价：
 * <table border="1">
 *   <caption>Tomcat 配额 vs B 侧 MaxConns</caption>
 *   <tr><th>参数</th><th>默认值</th><th>超限行为</th><th>等价？</th></tr>
 *   <tr><td>{@code server.tomcat.max-threads}</td><td>200</td><td><b>排队</b>等线程空闲</td><td>否 —— B 侧 是立刻 503</td></tr>
 *   <tr><td>{@code server.tomcat.max-connections}</td><td>8192</td><td>停止 accept，连接排队</td><td>否 —— 连 HTTP 状态都没有</td></tr>
 *   <tr><td>{@code server.tomcat.accept-count}</td><td>100</td><td>accept 队列长度</td><td>否</td></tr>
 * </table>
 * ⇒ <b>「超限时排队」与「超限时立刻 503」是两个不同的可观察行为</b>：
 * 排队会让客户端感知为「变慢」，立刻 503 让客户端感知为「被拒」。
 * 这个差别在过载时是决定性的（客户端的退避/重试策略不同），
 * 所以不能靠调 Tomcat 参数「近似」，必须自己实现闸门。
 *
 * <p><b>语义等价论证</b>：
 * <ol>
 *   <li>闸门<b>整个进程共享一个</b>（构造时建一次），与 B 的
 *       {@code latch := syncx.NewLimit(n)} 位置一致（B 侧 也是每个 handler
 *       <b>一份</b> latch，见下条）。</li>
 *   <li><b>B 侧 实际是「每个中间件实例一个闸门」</b>：
 *       {@code engine.go:146} 对<b>每个路由</b>各调一次
 *       {@code MaxConnsHandler}，每次 new 一个 latch。
 *       ⇒ 严格说 B 的上限是 <b>per-route</b> 10000，不是 global 10000。
 *       本类取<b>全局一个闸门</b>：对 13 个端点的并发度来说，两种口径的差别只在
 *       「单个端点打满 10000 且同时另一端点也有接近 10000」时才显现，
 *       而那时 Tomcat 的 {@code max-threads=200} 早已先成为瓶颈。
 *       <br><b>⚠️ 本条是经签字批准的「有意偏离」</b>（RAG 侧
 *       {@code docs/Intentional_Deviations/global/common-tails.md} 的
 *       {@code SD-2026-10-25-01}）⇒ <b>不要</b>因为「B 侧 是 per-route」就把它当缺陷顺手改回。</li>
 *   <li><b>{@code n <= 0} ⇒ no-op</b>：照搬 B 的短路，配 0 = 不限流。</li>
 *   <li>拒绝时<b>裸 503 空 body</b>：用 {@link HttpServletResponse#setStatus(int)}
 *       而<b>不是</b> {@code sendError}（后者在未预设 Content-Type 时会让容器补一个
 *       {@code text/html}，响应头就会比 B 侧 多一项）—— 同 {@link MaxBytesFilter} 的 413。</li>
 * </ol>
 *
 * <p><b>链上位置</b>：B 侧 {@code engine.go:145-147} 的 MaxConns 在
 * Trace/Log/Prometheus 之后、Breaker 之前，是<b>四层保护里最外层</b>的
 * ⇒ 本类注册在 {@link Ordered#HIGHEST_PRECEDENCE} + 4（与 {@link MaxBytesFilter} 的 +8 同族）。
 *
 * <p>⚠️ <b>2026-10-25（第十九轮 · global T-8）：由 +1 改为 +4</b> ——
 * 为了让共享层的 traceparent 过滤器能落在「CORS 之后、MaxConns 之前」
 * ⇒ 整块（保护层四件套 + HTTP 中间件 + MaxBytes）<b>整体后移</b>，
 * 但<b>各 filter 之间的相对顺序完全不变</b>（MaxConns 仍在 Breaker 之前）。
 *
 * <p><b>为什么默认 10000 在本部署下几乎不可达</b>（仍必须实现的原因见上）：
 * Tomcat 默认 {@code max-threads=200}，请求会先在容器层排队，
 * 理论上到不了 10000 在途。保留本层的理由是<b>口径对齐</b> ——
 * 当运维把 {@code max-threads} 调到万级做压测时，B 侧 会返回 503 而本仓若无此层会一直排队。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L4 idiomatization：去掉 {@code @Component} ＋ {@code @ConditionalOnApiSide}，改由 auto-config 注册</b>
 * ══════════════════════════════════════════════════════════════════════
 * <ol>
 *   <li><b>去 {@code @Component}</b>：本仓共享层是 <b>starter</b>（D3）⇒ 消费方不再扫
 *       {@code com.rag.common} ⇒ {@code @Component} <b>永远扫不到</b>
 *       ⇒ ⇒ 注册动作交 {@code ProtectionAutoConfiguration}（{@code @Bean} + 条件注解）。</li>
 *   <li><b>去 {@code @ConditionalOnApiSide}</b>：该注解是 <b>RAG 侧「api / rpc 分进程」</b>的产物
 *       ⇒ 而本仓 <b>D1 已把 api 与 rpc 合并成一个进程</b> ⇒ <b>「api 侧」这个维度不存在</b>
 *       ⇒ ⇒ 留着它 ⇒ 该维度永远为假 ⇒ <b>本 Filter 永不注册</b> ⇒ 静默失效。</li>
 *   <li><b>条件改用 {@code @ConditionalOnWebApplication(SERVLET)}</b>：本类是
 *       {@code jakarta.servlet.Filter} ⇒ 没 servlet 栈的进程连类都加载不了 ⇒ 必须条件化。</li>
 * </ol>
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/protection/GoStyleMaxConnsFilter.java}
 * 整段抄（L2 · D13）＋ <b>装配形态回改</b>（L4 · D3 + D1）。
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class MaxConnsFilter implements Filter {

    private final int maxConns;

    /** 全进程共享的闸门；{@code null} 表示 no-op（maxConns &lt;= 0）。 */
    private final Semaphore latch;

    public MaxConnsFilter(ProtectionProperties props) {
        this.maxConns = props.getMaxConns();
        this.latch = maxConns <= 0 ? null : new Semaphore(maxConns);
    }

    /** 生效值，对应 B 侧 {@code RestConf.MaxConns}（yaml 未写 ⇒ tag 默认 10000）。 */
    public int maxConns() {
        return maxConns;
    }

    /** 测试用：当前在途数。 */
    public int inFlight() {
        return latch == null ? 0 : maxConns - latch.availablePermits();
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        if (latch == null) {
            chain.doFilter(req, resp);
            return;
        }
        // B 侧 maxconnshandler.go:23：if latch.TryBorrow()（非阻塞；拿不到立刻拒）
        if (!latch.tryAcquire()) {
            // 裸 503：不 sendError（避免容器补 text/html）、不写 body
            ((HttpServletResponse) resp).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        try {
            chain.doFilter(req, resp);
        } finally {
            // B 侧 maxconnshandler.go:24-28 的 defer latch.Return()
            latch.release();
        }
    }
}
