package com.rag.common.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/**
 * B 侧 路由层两种<b>框架级</b>错误响应的字节级复刻：<b>404</b> 与 <b>405</b>。
 *
 * <p>放在 {@code service/common} 是因为它们<b>不是任何业务域的行为</b>：
 * 二者都由 B 侧的路由层产出，形状只取决于「路由有没有匹配上」，
 * 与该服务注册了哪些业务端点无关。复制到每个 api 模块，
 * 早晚会出现「message 的 404 有 body、like 的没有」这类静默分叉。
 *
 * <h2>⚠️ 404 与 405 的形状<b>完全相反</b>，不要「顺手统一」</h2>
 *
 * <pre>
 *   404：  Content-Type: text/plain; charset=utf-8
 *          X-Content-Type-Options: nosniff
 *          Content-Length: 20
 *          body = "404 page not found\n"        ← 有 body
 *
 *   405：  Allow: POST
 *          （无 body、无 Content-Type）          ← 裸状态码
 * </pre>
 *
 * <p>⚠️ 上面是 <b>B 侧</b>的形态。真 Tomcat 会把 Content-Type <b>归一化</b>
 * ⇒ RAG 侧线上实际是 {@code text/plain;charset=utf-8}（<b>分号后无空格</b>）
 * ⇒ 按 RFC 9110 两者<b>语义相同</b> ⇒ 详见 {@link #TEXT_PLAIN_UTF8} 的注释。
 *
 * <p>两条路径在 B 侧<b>根本不进任何路由的 chain</b>：
 * {@code service/common/src/main/java/com/rag/common/http/GoStyleRouterErrorResponses.java:33:104-118} 的 {@code bindRoute} 只把 chain（native middlewares + auth + 用户中间件）
 * 挂到 {@code router.Handle(method, path, handle)} 上，即<b>只对匹配上的路由生效</b>；
 * 而 {@code router/patrouter.go:59-83} 的 {@code ServeHTTP} 在「路径无匹配」与「方法不匹配」时
 * 直接走 404 / 405 分支，<b>不经过 auth、也不经过 MaxBytes / Breaker / Shedding</b>。
 * ⇒ 「404 不需要鉴权」「405 不需要鉴权」是 B 的结构性事实，不是漏配。
 *
 * <h2>Evidence（B 侧）</h2>
 *
 * <p><b>404</b> —— {@code router/patrouter.go:73,93-97}：
 * <pre>
 *   pr.handleNotFound(w, r) → http.NotFound(w, r)
 *                          → http.NotFoundHandler() → http.Error(w, "404 page not found", 404)
 * </pre>
 * {@code net/http.Error} 的四行就是全部语义来源：
 * <pre>
 *   h.Del("Content-Length")
 *   h.Set("Content-Type", "text/plain; charset=utf-8")
 *   h.Set("X-Content-Type-Options", "nosniff")
 *   w.WriteHeader(code); fmt.Fprintln(w, error)     // ⇒ body 带一个 \n
 * </pre>
 *
 * <p><b>405</b> —— {@code router/patrouter.go:79-82}：
 * <pre>
 *   w.Header().Set(allowHeader, allows)             // allowHeader = "Allow"（:15）
 *   w.WriteHeader(http.StatusMethodNotAllowed)      // ← 没有 fmt.Fprintln，没有 Error()
 * </pre>
 * ⇒ <b>裸状态码</b>：既无 body 也无 {@code Content-Type}。
 * 与本项目「业务错误恒 200 + envelope」「保护层裸状态码」是<b>第三种</b>形状，不要混。
 *
 * <h2>⚠️ {@code Allow} 头的<b>取值顺序在 B 侧不确定</b>（本类不修，也不该修）</h2>
 *
 * <p>{@code patrouter.go:101-117} 的 {@code methodsAllowed} 是
 * ⇒ 当同一路径注册了 ≥2 个方法时，B 侧 发出的 {@code Allow} 顺序是<b>不确定</b>的。
 * <p>本类<b>原样透传</b>调用方给的顺序，<b>刻意不排序</b>：排序＝新增行为＝漂移
 * （它会让两侧在「B 侧 随机、RAG 确定」时出现稳定可测的差异，而 B 侧 自己不稳定）。
 * <p>⚠️ 当前 like-api / message-api 的路由<b>全是 POST</b> ⇒ {@code Allow} 只有一个值，
 * 顺序问题不可观测。**一旦有模块给同一路径注册第二个方法，就必须先决定这件事怎么对齐。**
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/http/GoStyleRouterErrorResponses.java}
 * §1.11 第 239 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
public final class RouterErrorResponses {

    private RouterErrorResponses() {
    }

    /**
     * {@code http.NotFound} 的响应体 —— {@code http.Error} 用 {@code fmt.Fprintln} 写出，
     * 所以<b>带一个尾随换行</b>，全长 {@code 19} 字节
     * （{@code "404 page not found"} = 18 字节 ＋ {@code "\n"} = 1 字节）。
     *
     * <p>🔴 <b>2026-10-29（R3 轮）订正</b>：本 javadoc 原写「全长 {@code 20} 字节」⇒ <b>错</b>
     * ⇒ 是 {@code GoStyleRouterErrorResponsesContractGateTest#notFoundMatchesGoByteShape}
     * 按字节数组实测抓出来的（{@code hasSize(19)}）⇒ ⇒ <b>字节数必须实测，不能手数</b>。
     */
    private static final byte[] NOT_FOUND_BODY =
            "404 page not found\n".getBytes(StandardCharsets.UTF_8);

    /**
     * {@code net/http.Error} 写的是 {@code "text/plain; charset=utf-8"}（<b>分号后有空格</b>）。
     * <p>⚠️ 这里<b>不</b>用 {@code MediaType.TEXT_PLAIN}：那会产出 {@code text/plain}（无 charset），
     * 而 {@code StringHttpMessageConverter} 是否补 charset 取决于
     * {@code writeAcceptCharset}（默认 {@code false}）⇒ **不可靠**。
     *
     * <h3>🔴 订正（2026-10-25 · 第二十五轮 · global T-6）：「直接写原始字符串就逐字节对上」是<b>错的</b></h3>
     * <p>本方法<b>一直</b>写的是原始字符串，但真 Tomcat 会把它<b>归一化</b> ⇒ 线上其实是
     * {@code text/plain;charset=utf-8}（<b>无空格</b>）⇒ <b>并没有</b>逐字节对上 B 侧。
     * <pre>
     * org/apache/catalina/connector/Response.java（11.0.24）setContentType(String)
     *     String[] parsedType = MediaTypeCache.parse(type);
     *     if (parsedType[1] == null) { coyoteResponse.setContentTypeNoCharset(parsedType[0]); }
     *     else {                                  // 有 charset ⇒ **拆开存**
     *         coyoteResponse.setContentTypeNoCharset(parsedType[0]);
     *         coyoteResponse.setCharsetHolder(CharsetHolder.getInstance(parsedType[1]));
     *     }
     * org/apache/coyote/Http11OutputBuffer.writeHeaders()
     *     headerBuffer.put(contentType); headerBuffer.put(SEMICOLON); headerBuffer.put(CHARSET);
     *     ⇒ 线上恒为 type/subtype;charset=xxx（**分号后没有空格**）
     * </pre>
     * ⇒ ⇒ <b>写原始字符串的唯一作用</b>是：<b>保证 charset 参数存在</b>（不会被退化成裸
     * {@code text/plain}）—— 这才是它相对 {@code MediaType.TEXT_PLAIN} 的真正优势。
     * ⇒ ⇒ 「那个空格」在 Tomcat 11 上<b>应用层拿不到</b>（{@code setContentType} /
     * {@code setHeader} / {@code addHeader} 三条路都汇到同一处归一化）
     * ⇒ ⇒ 按 RFC 9110，参数分隔符两旁的 OWS <b>不显著</b> ⇒ 两者<b>语义相同</b>
     * ⇒ ⇒ 仓库既有先例：{@code LikeApiRouterErrorRealTomcatTest} 对本 404 就是<b>刻意解析后比对</b>
     * （去空格），注释明写「比原始字符串会得到一个『必然红、且红了也不代表有错』的断言（已实测）」。
     */
    private static final String TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8";

    /**
     * 404 —— 对应 B 侧 {@code http.NotFound(w, r)}。
     *
     * @param resourcePath 仅用于日志；<b>不进入响应</b>（B 的 404 body 是固定的，不含路径）
     */
    public static ResponseEntity<byte[]> notFound(String resourcePath) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN_UTF8)
                .header("X-Content-Type-Options", "nosniff")
                .body(NOT_FOUND_BODY);
    }

    /**
     * 405 —— 对应 B 侧 {@code router/patrouter.go:79-82}。
     *
     * @param supportedMethods 该路径已注册的方法；<b>顺序原样透传</b>（见类 javadoc 的顺序警告）
     */
    public static ResponseEntity<Void> methodNotAllowed(java.util.Collection<String> supportedMethods) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ALLOW, String.join(", ", supportedMethods));
        // ⚠️ 刻意返回 ResponseEntity<Void>（body == null）：容器不会补 Content-Type，
        // 与 B 侧「只 WriteHeader、不写 body」的形状一致。
        // 若改成 body 非空，Spring 会先选出一个 MediaType，响应头就会比 B 侧 多一项（DRIFT-024 同款教训）。
        return new ResponseEntity<>(null, headers, HttpStatus.METHOD_NOT_ALLOWED);
    }
}