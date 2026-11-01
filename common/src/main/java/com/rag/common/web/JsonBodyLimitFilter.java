package com.rag.common.web;

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
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 🔴 DRIFT-002（已修）：<b>JSON body 超过 8 MiB ⇒ 400</b>。
 *
 * <h2>B 侧证据（这是一个<b>框架常量</b>，不在任何 yaml / tag 里）</h2>
 * <pre>
 *   rest/httpx/requests.go:20-21
 *       maxMemory  = 32 &lt;&lt; 20 // 32MB
 *       maxBodyLen = 8  &lt;&lt; 20 // 8MB        ← 行为定义，但**不可配置**
 *
 *   rest/httpx/requests.go:117-124
 *       func ParseJsonBody(r *http.Request, v any) error {
 *           if withJsonBody(r) {
 *               reader := io.LimitReader(r.Body, maxBodyLen)   // ← 静默截断，不是显式拒绝
 *               return mapping.UnmarshalJsonReader(reader, v)
 *           }
 *           return mapping.UnmarshalJsonMap(nil, v)
 *       }
 * </pre>
 *
 * ⇒ 任何 &gt; 8 MiB 的 JSON body 被 {@code LimitReader} <b>静默截断在 8 MiB</b>，
 * 随后 JSON 解析器拿到半个文档 ⇒ {@code unexpected EOF}
 * ⇒ handler 的 {@code httpx.ErrorCtx} ⇒ <b>400 + text/plain + nosniff</b>，
 * <b>永远不会调用 article-rpc</b>。
 *
 * <h3>为什么这条容易漏（AGENTS.md §3.1「否定式断言 / 表面配置对法」）</h3>
 * <p>{@code maxBodyLen} 既不是 {@code RestConf} 的字段，也不在任何 {@code json:",default=…"} tag 里，
 * <b>逐字段对 yaml 永远看不见它</b>。而 {@code MaxBytes} 是 <b>10 MiB</b>
 * （article-api.yaml 显式配置 + {@code routes.go} 的 {@code rest.WithMaxBytes}），
 * 两者<b>不是同一个东西</b>：
 * <pre>
 *   &lt; 8 MiB         两侧都解析成功（行为一致）
 *   8 – 10 MiB       B 侧 恒 400（截断）；Java 上游实现恒进业务   ← 这条 2 MiB 宽的带
 *   &gt; 10 MiB        两侧都被 MaxBytes 拦成裸 413
 * </pre>
 * ⇒ 上游实现只对齐了 {@code MaxBytes}，漏了这层，产出 DRIFT-002。
 *
 * <h3>为什么是「显式拒绝」而不是复刻「静默截断」</h3>
 * <p>两者的<b>可观察契约完全相同</b>：HTTP 400 + text/plain + nosniff + 业务逻辑不执行。
 * 差别只在 body 文案（B 侧 那句来自被截断后的解析器，不是任何自定义消息）。
 * 显式拒绝避免了「把 servlet 输入流截断到与 Content-Length 不符」这类容器层面的怪异行为，
 * 且文案差异已按 OBSERVABLE DIFFERENCE（低危）登记。
 *
 * <h3>触发条件与 B 的 {@code withJsonBody} 逐条一致</h3>
 * <pre>
 *   httpx/requests.go:153-155
 *       func withJsonBody(r *http.Request) bool {
 *           return r.ContentLength &gt; 0 &amp;&amp; strings.Contains(r.Header.Get(ContentType), ApplicationJson)
 *       }
 * </pre>
 * ⇒ 只对「有 Content-Length 且为正」+「Content-Type 含 application/json」的请求生效。
 * <b>chunked（无 Content-Length）在 B 侧根本不会读 body</b>，
 * Java 侧由 {@code service/article/article-api/src/main/java/com/rag/article/article/api/config/GoStyleJsonBodyLimitFilter.java:68ReadsBody} 已经同样处理（返回 null → 零值 DTO）。
 *
 * <h3>{@code @Order} 的真实作用是「在 Filter 之间排序」</h3>
 * 必须<b>在 {@code GoStyleMaxBytesFilter}(+8) 之后</b>：先让 10 MiB 的闸门按 B 的链序
 * （MaxBytes 在本层<b>之外</b>）拒绝，超出 MaxBytes 的请求得到<b>裸 413</b>而不是 400。
 * 取 {@code HIGHEST_PRECEDENCE + 9}。
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/article/article-api/src/main/java/com/rag/article/article/api/config/GoStyleJsonBodyLimitFilter.java}
 * §1.11 第 251 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Order(Ordered.HIGHEST_PRECEDENCE + 9)
public class JsonBodyLimitFilter implements Filter {

    /**
     * B 侧 {@code rest/httpx/requests.go:21} 的 {@code maxBodyLen = 8 << 20}。
     * <b>不可配置</b>（B 侧它是常量），故这里也不做成 property ——
     * 做成可配置等于凭空多出 B 侧 没有的开关。
     */
    private static final long MAX_JSON_BODY_BYTES = 8L * 1024 * 1024;

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        if (goReadsJsonBody(request) && request.getContentLengthLong() > MAX_JSON_BODY_BYTES) {
            // 与 B 的 http.Error(400) 同形：text/plain + nosniff + 文案 + "\n"
            writeGoStyleBadRequest(response, "request entity too large");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 直接写 B 侧 {@code http.Error} 的形状。
     *
     * <p>⚠️ Content-Type 用 {@link HttpServletResponse#setHeader} 而<b>不是</b>
     * {@link HttpServletResponse#setContentType}：后者会被 Tomcat 规范化并<b>重写</b>成
     * {@code text/plain;charset=utf-8}（分号后无空格），而 B 侧 写的是
     * {@code text/plain; charset=utf-8}（有空格）。这是 DRIFT-005。
     * <p>⚠️ 头必须在 {@code setStatus}（提交响应）<b>之前</b>写完，否则被静默忽略。
     */
    private static void writeGoStyleBadRequest(HttpServletResponse response, String message) throws IOException {
        response.setHeader("Content-Type", "text/plain; charset=utf-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.getOutputStream().write((message + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * B 侧 {@code httpx.ParseJsonBody} 的 {@code withJsonBody} 判据。
     *
     * <p>⚠️ <b>2026-10-25（第十六轮 · global X-9）：这里不再抄一份判据</b>，
     * 改调共享层唯一真源
     * {@code com.rag.common.web.JsonBodyConverterConfig#goReadsBody(HttpServletRequest)}。
     *
     * <p>为什么必须收口（X-9 的核心风险）：
     * <pre>
     *   Content-Type: Application/JSON
     *     ├─ 本类（闸门）      ⇒ 「不是 JSON body」⇒ 不读
     *     └─ 共享转换器（解析器）⇒ 「是 JSON body」⇒ 读
     * </pre>
     * ⇒ 同一个请求<b>闸门说不读、解析器说要读</b> ⇒ RAG 内部自相矛盾。
     * ⇒ 判据一旦只剩一份实现，这个矛盾就<b>结构上</b>不可能再发生。
     *
     * <p>⚠️ 取值口径也一并收口：唯一真源内部取
     * {@code request.getContentType()}（<b>原始</b>头值 ⇒ 大小写敏感 ⇒ 与 B 侧 同形）
     * + {@code request.getContentLengthLong()}（<b>int64</b> ⇒ 与 B 的
     * {@code r.ContentLength} 同形；本地原先用的 {@code getContentLength()} 是 int 版，
     * > 2 GiB 时 Tomcat 返回 -1 ⇒ 会与 B 侧 判成相反）。
     */
    private static boolean goReadsJsonBody(HttpServletRequest request) {
        return JsonBodyConverterConfig.goReadsBody(request);
    }
}
