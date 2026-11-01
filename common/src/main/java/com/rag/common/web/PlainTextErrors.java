package com.rag.common.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/**
 * Go {@code http.Error} 的形状 —— 本模块「解析层失败」与「上传失败」两条路径的
 * <b>唯一</b>响应形态。
 *
 * <h2>为什么需要它：article-api <b>没有</b>注册 error handler</h2>
 *
 * <p>这是本组件与 message / like / hot 三个 api 的<b>决定性差异</b>，先把它钉死：
 * <pre>
 *   service/message/api/messagecenter.go:53   httpx.SetErrorHandler(func(err error) (int, any) {
 *       ... return http.StatusOK, &response.Response{Code: code, ...} })   ← 有
 *   service/like/api/likecenter.go:48          httpx.SetErrorHandler(...)                  ← 有
 *   service/hot/api/hotcenter.go:53            httpx.SetErrorHandler(...)                  ← 有
 *
 *   service/article/api/article.go             （全文 42 行，**一个字都没有**）             ← 无
 * </pre>
 *
 * <p>后果直接落在 {@code httpx/responses.go:142-159} 的 {@code doHandleError}：
 * <pre>
 *   handler == nil  ⇒  http.Error(w, err.Error(), http.StatusBadRequest)
 * </pre>
 * 而 Go 的 {@code http.Error}（net/http/server.go）做四件事：
 * <pre>
 *   h.Del("Content-Length")
 *   h.Set("Content-Type", "text/plain; charset=utf-8")
 *   h.Set("X-Content-Type-Options", "nosniff")
 *   w.WriteHeader(code); fmt.Fprintln(w, error)      ← 注意 Fprintln：补一个 "\n"
 * </pre>
 *
 * ⇒ <b>article-api 的解析失败是「400 + text/plain + nosniff + 裸文案」，
 * 不是 200 + 信封。</b>
 * 这也是 message-api 的 {@code GlobalExceptionHandler} 把
 * {@code HttpMessageNotReadableException} 映成 {@code 200 + {code:5001}} 的原因
 * —— 那个映射对 message 正确，<b>照搬到 article 就是漂移</b>。
 * AGENTS.md §3.1「不要拿另一个域的实测结论当对照基准」的又一个实例。
 *
 * <p>⚠️ 与 {@code com.rag.common.http.RouterErrorResponses} 的关系：后者给的是
 * <b>404 / 405</b>（go-zero router 自己写的，跟 error handler 无关），本类给的是
 * <b>handler 内部</b>抛出来的错误。两者形状相同（都是裸 text/plain）但来源不同，
 * 且 404/405 是<b>所有</b> api 模块共享的，所以留在共享实现里；
 * 本类是 article-api 私有的（因为只有本模块缺 error handler）。
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/article/article-api/src/main/java/com/rag/article/article/api/http/GoStylePlainTextErrors.java}
 * §1.11 第 248 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
public final class PlainTextErrors {

    /** Go {@code http.Error} 的 Content-Type。 */
    private static final String TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8";

    private PlainTextErrors() {
    }

    /**
     * Go {@code http.Error(w, err.Error(), 400)} 的逐字等价物。
     *
     * @param message {@code err.Error()}。末尾的 {@code "\n"} 由本方法补（Go 用 {@code Fprintln}），
     *                调用方<b>不要</b>自己加。
     */
    public static ResponseEntity<byte[]> badRequest(String message) {
        return text(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 任意状态的 {@code http.Error} 形状。
     *
     * <p>⚠️ <b>不止 400 在用</b>：Go {@code rest/httpx/responses.go:150-153} 的
     * {@code IsGrpcError} 分支会按 gRPC 码映射出 500 / 503 / 504 / 429 / 404 等，
     * 由 {@link HttpErrorException} 携带状态码进来。
     */
    public static ResponseEntity<byte[]> text(HttpStatus status, String message) {
        byte[] body = ((message == null ? "" : message) + "\n").getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN_UTF8)
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /**
     * ⚠️ <b>刻意不提供</b>「envelope 版本」的便捷方法。
     *
     * <p>本模块<b>所有</b>需要 envelope 的错误都走
     * {@code BusinessException} → {@code GlobalExceptionHandler}，
     * 那里已经能产出 {@link com.rag.common.response.Response}。
     * 在这里再提供一个「text/plain 或 envelope」的入口，等于把
     * 「什么时候用哪种形状」这个决定从<b>唯一一处</b>
     * （没有 error handler ⇒ 一律 text/plain）散成两处，
     * 是「同一语义两处实现」的温床。
     */
    public static String contentType() {
        return TEXT_PLAIN_UTF8;
    }
}