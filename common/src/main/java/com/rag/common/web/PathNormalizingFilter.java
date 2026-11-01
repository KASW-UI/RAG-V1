package com.rag.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;

/**
 * 🔴 <b>共享层</b>的<b>路径规范化</b> Filter —— 对齐 B 的 {@code path.Clean}。
 *
 * <h2>B 侧证据（真源 = B 侧 {@code service/common/src/main/java/com/rag/common/http/GoStylePathNormalizingFilter.java:20-21} · 注册与匹配<b>两侧</b>都做）</h2>
 * <pre>
 * rest/router/patrouter.go:48   cleanPath := path.Clean(reqPath)     // 注册时（写进路由树）
 * rest/router/patrouter.go:60   reqPath  := path.Clean(r.URL.Path)   // 匹配时（清洗请求路径）
 * </pre>
 * ⇒ 路由表里存的是<b>已 Clean 的模板</b>，匹配时也把请求路径 Clean 一次
 * ⇒ ⇒ <b>路径变体命中同一条路由</b>。
 *
 * <h3>🟢 B 侧活体实测（L4 · 2026-10-27 · 真框架 {@code rest} server + curl）</h3>
 * <pre>
 *   PROBE /v1/x        =&gt; 200
 *   PROBE /v1/x/       =&gt; 200   ← 尾斜杠
 *   PROBE /v1//x       =&gt; 200   ← 重复斜杠
 *   PROBE /v1/x/../x   =&gt; 200   ← ..
 *   PROBE /v1/./x      =&gt; 200   ← .
 * </pre>
 * ⇒ ⇒ 这不是「读源码推的」，是<b>两侧同时跑的探针</b>（AGENTS.md §3.3）。
 *
 * <h2>🔴 为什么必须落在共享层（而不是每域各写一份）</h2>
 * <p>本类原先只存在于 {@code service/article/article-api}（域内副本）
 * ⇒ ⇒ <b>其余 9 个 api 模块没有这一层</b>
 * ⇒ ⇒ 尾斜杠等路径变体在 B 侧是 <b>200</b>、在 RAG 侧是 <b>404</b> ⇒ 行为分叉。
 * <p>⇒ ⇒ 归一语义必须<b>只有一份</b>（否则 10 个 api 会有 10 份可能各自漂移的实现）
 * ⇒ ⇒ 落点 = 共享层 {@code service/common/http/}（A3 轴的落点面）。
 *
 * <h3>⚠️ 与鉴权的一致性（为什么覆写 {@code getRequestURI()}）</h3>
 * <p>B 侧的 JWT 是<b>按路由组</b>施加的（{@code rest.WithJwt(secret)} 是 {@code RouteOption}，
 * 不是一个「路径 → 是否鉴权」的查表）⇒ ⇒ 请求路径被 Clean 后<b>匹配到哪条路由</b>，
 * 就适用<b>哪一组</b>的中间件。
 * ⇒ ⇒ <b>清洗后的路径匹配到受保护路由 ⇒ 仍需 JWT</b>
 * ⇒ ⇒ 本 Filter 覆写 {@code getRequestURI()} ⇒ 域自己的鉴权拦截器看到的也是<b>同一个清洗后路径</b>
 * ⇒ ⇒ 与 B 的「先 Clean 再匹配、再按命中的路由组决定鉴权」<b>同序</b>。
 * <p>⚠️ 若本 Filter 缺席 ⇒ RAG 在路径变体上直接 <b>404</b>（压根进不到鉴权）
 * ⇒ ⇒ 与 B 的 401/200 <b>分叉</b>（且方向相反：B 侧 会鉴权，RAG 直接 404）。
 *
 * <h3>为什么在 Filter 层做（而不是 HandlerInterceptor）</h3>
 * <p>路由匹配发生在 DispatcherServlet <b>内部</b>，Interceptor 跑在匹配<b>之后</b>
 * ⇒ 那里再改路径已经来不及（会变成 404）。
 * <p>Filter 在 DispatcherServlet 之外 ⇒ 是唯一能影响匹配结果的位置。
 *
 * <h3>⚠️ {@code @Order}：必须在<b>所有</b>会读路径的 Filter 之前</h3>
 * <p>取 {@link Ordered#HIGHEST_PRECEDENCE} + 2 ⇒ 排在共享层 {@code TraceparentFilter}(+3) <b>之前</b>
 * （它要读 {@code getRequestURI()} 取 span 名）。
 * <p>⚠️ <b>不短路</b>（只包装 request 后继续下传）⇒ 本 Filter <b>自己不产生响应</b>
 * ⇒ ⇒ 与 traceparent filter 的相对位置<b>不影响任何响应头</b>。
 *
 * <h3>🔴 为什么本类<b>不是</b> {@code @Component}</h3>
 * <p>本类由 {@link GoStylePathNormalizingAutoConfiguration} 以<b>唯一来源</b>注册
 * ⇒ ⇒ 结构上不可能出现「同一层被注册两次」。
 * <p>（{@code @ConditionalOnMissingBean} 只用在「是否已有域内副本」这一个过渡判据上，
 * 不用于防重复 —— 那是它的<b>唯一</b>用途，见该类的 javadoc。）
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/http/GoStylePathNormalizingFilter.java}
 * §1.11 第 252 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class PathNormalizingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String uri = request.getRequestURI();
        String cleaned = goPathClean(uri);
        if (cleaned.equals(uri)) {
            // 绝大多数请求不需要包装，省一次对象分配
            chain.doFilter(request, resp);
            return;
        }
        chain.doFilter(new CleanedPathRequest(request, cleaned), resp);
    }

    /**
     * B 侧 {@code path.Clean} 的<b>字节级逐字移植</b>（{@code path/path.go} 的 {@code Clean}）。
     *
     * <p>⚠️ <b>本实现是按 B 的「写入位置 + 回退」模型重写的</b>，不是按「路径段列表」模型
     * ⇒ ⇒ 两者在<b>两处</b>会给出不同结果，而那两处正是旧版移植（article-api 的域内副本）错的地方：
     * <table border="1">
     *   <caption>旧版（按路径段）与 B 的差异</caption>
     *   <tr><th>输入</th><th>B 侧</th><th>旧版</th><th>成因</th></tr>
     *   <tr><td>{@code "/"}</td><td>{@code "/"}</td><td>{@code ""}</td>
     *       <td>B 的 rooted 路径<b>先写入一个 '/'</b>；旧版用「段列表」建模 ⇒ 根路径成了空串</td></tr>
     *   <tr><td>{@code ".."} / {@code "../a"}</td><td>{@code ".."} / {@code "../a"}</td>
     *       <td>{@code "."} / {@code "./a"}</td>
     *       <td>B 侧 在<b>不可回退且非 rooted</b> 时原样追加<b>两个</b>点；旧版只追加一个</td></tr>
     * </table>
     * ⇒ ⇒ 现实现按 B 的字节级算法重写 ⇒ <b>32 条基准逐条一致</b>
     * （基准由 B 侧 1.25.11 的 {@code path.Clean} 生成，见
     * {@code GoStylePathCleanParityTest}）。
     *
     * <p>B 侧 原文（{@code $GOROOT/src/path/path.go}）：
     * <pre>
     *   func Clean(path string) string {
     *       if path == "" { return "." }
     *       rooted := path[0] == '/'
     *       n := len(path)
     *       // Invariants:
     *       //   reading from path; r is index of next byte to process.
     *       //   writing to buf; w is index of next byte to write.
     *       //   dotdot is index in buf where .. must stop, either because
     *       //       it is the leading slash or it is a leading ../../.. prefix.
     *       out := lazybuf{s: path}
     *       r, dotdot := 0, 0
     *       if rooted {
     *           out.append('/')
     *           r, dotdot = 1, 1
     *       }
     *       for r &lt; n {
     *           switch {
     *           case path[r] == '/':
     *               r++
     *           case path[r] == '.' &amp;&amp; (r+1 == n || path[r+1] == '/'):
     *               r++
     *           case path[r] == '.' &amp;&amp; path[r+1] == '.' &amp;&amp; (r+2 == n || path[r+2] == '/'):
     *               r += 2
     *               switch {
     *               case out.w &gt; dotdot:
     *                   out.w--
     *                   for out.w &gt; dotdot &amp;&amp; out.index(out.w) != '/' {
     *                       out.w--
     *                   }
     *               case !rooted:
     *                   if out.w &gt; 0 { out.append('/') }
     *                   out.append('.')
     *                   out.append('.')
     *                   dotdot = out.w
     *               }
     *           default:
     *               if rooted &amp;&amp; out.w != 1 || !rooted &amp;&amp; out.w != 0 {
     *                   out.append('/')
     *               }
     *               for ; r &lt; n &amp;&amp; path[r] != '/'; r++ {
     *                   out.append(path[r])
     *               }
     *           }
     *       }
     *       if out.w == 0 { return "." }
     *       return out.string()
     *   }
     * </pre>
     *
     * @param path 待清洗的路径（通常是 {@code getRequestURI()}）
     * @return B 侧 {@code path.Clean} 的结果
     */
    public static String goPathClean(String path) {
        if (path == null || path.isEmpty()) {
            return ".";
        }
        final boolean rooted = path.charAt(0) == '/';
        final int n = path.length();
        // out = B 的 lazybuf（写入位置 = out.length()）
        final StringBuilder out = new StringBuilder();
        int r = 0;
        int dotdot = 0;
        if (rooted) {
            out.append('/');
            r = 1;
            dotdot = 1;
        }

        while (r < n) {
            final char c = path.charAt(r);
            if (c == '/') {
                // 空路径段
                r++;
            } else if (c == '.' && (r + 1 == n || path.charAt(r + 1) == '/')) {
                // "." 段
                r++;
            } else if (c == '.' && r + 1 < n && path.charAt(r + 1) == '.'
                    && (r + 2 == n || path.charAt(r + 2) == '/')) {
                // ".." 段：回退到上一个 "/"
                r += 2;
                if (out.length() > dotdot) {
                    // 可回退：去掉最后一个路径段（B 侧: out.w-- 后回退到 '/'）
                    int w = out.length() - 1;
                    while (w > dotdot && out.charAt(w) != '/') {
                        w--;
                    }
                    out.setLength(w);
                } else if (!rooted) {
                    // 不可回退且非 rooted：原样保留 ".."
                    if (out.length() > 0) {
                        out.append('/');
                    }
                    out.append('.');
                    out.append('.');
                    dotdot = out.length();
                }
            } else {
                // 真实路径段
                if ((rooted && out.length() != 1) || (!rooted && out.length() != 0)) {
                    out.append('/');
                }
                while (r < n && path.charAt(r) != '/') {
                    out.append(path.charAt(r));
                    r++;
                }
            }
        }

        if (out.length() == 0) {
            return ".";
        }
        return out.toString();
    }

    /**
     * 只覆写「路径」相关的三个 getter。
     *
     * <p>⚠️ <b>不覆写</b> {@code getRequestURL()}：B 的 {@code r.URL.Path} 只影响路由匹配，
     * 访问日志 / 审计若要看到<b>原始</b> URL 应当读原始值。
     * 本 Filter 的目的是「让路由匹配与 B 侧 一致」，不是「篡改请求」。
     */
    private static final class CleanedPathRequest extends HttpServletRequestWrapper {

        private final String cleanedUri;

        CleanedPathRequest(HttpServletRequest request, String cleanedUri) {
            super(request);
            this.cleanedUri = cleanedUri;
        }

        @Override
        public String getRequestURI() {
            return cleanedUri;
        }

        @Override
        public String getServletPath() {
            return cleanedUri;
        }

        @Override
        public String getPathInfo() {
            return null;
        }
    }
}
