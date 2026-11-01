package com.rag.common.protection;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;

/**
 * L4 idiomatization：请求体大小上限，对齐 B 侧 {@code handler.MaxBytesHandler}。
 *
 * <p><b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/protection/GoStyleMaxBytesFilter.java:20:10-25}
 * <pre>
 * func MaxBytesHandler(n int64) func(http.Handler) http.Handler {
 *     if n &lt;= 0 { return func(next http.Handler) http.Handler { return next } }
 *     return func(next http.Handler) http.Handler {
 *         return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
 *             if r.ContentLength &gt; n {
 *                 internal.Errorf(r, "request entity too large, ...")
 *                 w.WriteHeader(http.StatusRequestEntityTooLarge)   // ← 413，无 body
 *             } else { next.ServeHTTP(w, r) }
 *         })
 *     }
 * }
 * </pre>
 *
 * <p><b>语义等价论证（三条，逐条对着 B 侧 源码）</b>：
 * <ol>
 *   <li><b>413 + 空 body + 无 Content-Type</b>：B 侧是裸 {@code w.WriteHeader(413)}，
 *       不调 {@code httpx.Error} ⇒ 既没有 envelope 也没有
 *       {@code Content-Type: application/json}。因此本类用
 *       {@link HttpServletResponse#setStatus(int)} 而<b>不是</b>
 *       {@code sendError(int)}：后者在未预设 Content-Type 时会让容器补一个
 *       {@code text/html}，响应头就会比 B 侧 多一项。
 *       （同项目 DRIFT-024 的教训：body 对了不代表响应对，响应头也是可比对面。）</li>
 *   <li><b>只判 Content-Length 头，不判实际读取字节数</b> —— 这是 B 的真实语义，
 *       也是最容易被「顺手改好」而引入漂移的地方：<b>chunked
 *       （Transfer-Encoding: chunked，无 Content-Length）的请求在 B 侧会绕过这道限制</b>，
 *       因为 {@code r.ContentLength} 为 {@code -1}，{@code -1 > n} 恒为 false。
 *       Servlet 侧 {@link HttpServletRequest#getContentLengthLong()} 对 chunked
 *       同样返回 {@code -1}，判据 {@code -1 > maxBytes} 也为 false ⇒ 两边一致放行。
 *       <b>刻意</b>不做「兜底读取计数」之类的修正：修正它＝新增行为＝漂移。
 *       真要堵 chunked，得回 B 侧改。</li>
 *   <li><b>{@code n <= 0} ⇒ no-op</b>：照搬 B 的 {@code if n <= 0} 短路，
 *       配成 0 就是「不限体」，而不是「全拒」。</li>
 * </ol>
 *
 * <p><b>链上位置（必须与 B 侧 一致，否则可观察）</b>：
 * B 侧 {@code rest/engine.go:105-112} 先调 {@code buildChainWithNativeMiddlewares}
 * （本层在其内，见 {@code engine.go:164}），<b>再</b>调 {@code appendAuthHandler}；
 * 而 {@code chain.Append} 的语义是「加在请求流的最后」（{@code rest/chain/chain.go:38-43}）
 * ⇒ <b>本层在 JWT 鉴权之外（更外层）</b>。
 * <p>可观察后果：<b>往受保护端点发一个 2 MiB 的无 token 请求，B 侧 返回 413 而不是 401</b>。
 *
 * <h2>⚠️「在鉴权之外」靠的是<b>它是 Filter</b>，不是 {@link Order} 的数值</h2>
 *
 * <p><b>2026-10-22 变异测试实测得出的更正</b>：把本类的 {@code @Order} 改成
 * {@link Ordered#LOWEST_PRECEDENCE}（链尾）后，message-api 与 like-api 的
 * 「超限 → 413 而非 401」测试<b>依然全绿</b>。
 * <p>原因：Servlet 的<b>所有</b> {@code Filter} 都包在 {@code DispatcherServlet} <b>外面</b>，
 * 而 {@code HandlerInterceptor}（JWT 鉴权）跑在 {@code DispatcherServlet} <b>内部</b>。
 * ⇒ Filter 与 Interceptor 的先后是<b>容器结构</b>决定的，与两者的 order 数值<b>无关</b>；
 * 把 Filter 挪到 order 链尾，它仍然包着 servlet，仍然先于任何拦截器。
 * <p>⇒ {@code @Order} 在本类的<b>真实作用</b>是<b>在 Filter 之间排序</b>。
 * <p>⇒ 真正锁死「413 压过 401」这条语义的变异是<b>让本层失效</b>（摘掉 Bean 注册），
 * 此时请求会走到鉴权并返回 401 —— 已实测。
 * 本文件保留 {@code HIGHEST_PRECEDENCE + 8} 是为了<b>与 B 的 native middleware 同序</b>
 * （B 侧 MaxBytes 也排在 trace/logging 等 native middleware 之内），
 * <b>不是</b>为了压过拦截器。
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
 *       ⇒ ⇒ 留着它 ⇒ 该维度永远为假 ⇒ <b>本 Filter 永不注册</b> ⇒ 静默失效。
 *       （映射表 §1.12 已把这条判为「不是缺口」。）</li>
 *   <li><b>条件改用 {@code @ConditionalOnWebApplication(SERVLET)}</b>：本类是
 *       {@code jakarta.servlet.Filter} ⇒ 没 servlet 栈的进程连类都加载不了 ⇒ 必须条件化。</li>
 * </ol>
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/protection/GoStyleMaxBytesFilter.java}
 * 整段抄（L2 · D13）＋ <b>装配形态回改</b>（L4 · D3 + D1）。
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Order(Ordered.HIGHEST_PRECEDENCE + 8)
public class MaxBytesFilter implements Filter {

    private final long maxBytes;

    public MaxBytesFilter(ProtectionProperties props) {
        this.maxBytes = props.getMaxBytes();
    }

    /** 生效值，对应 B 侧 {@code RestConf.MaxBytes}（yaml 未写 ⇒ tag 默认 1048576）。 */
    public long maxBytes() {
        return maxBytes;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        if (maxBytes <= 0) {
            chain.doFilter(request, response);
            return;
        }

        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBytes) {
            // 只 setStatus：不 sendError（避免容器补 text/html）、不写 body、不显式 flush，
            // 三者共同保证「裸 413」与 B 的 w.WriteHeader(413) 等价。
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }

        chain.doFilter(request, response);
    }
}
