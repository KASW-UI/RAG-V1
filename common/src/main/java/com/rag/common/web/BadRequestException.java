package com.rag.common.web;

/**
 * 承载「B 侧 {@code httpx.ErrorCtx} 会写出的那种错误」—— 即<b>裸状态码 + text/plain</b>，
 * 而不是本模块惯用的 {@code 200 + 信封}。
 *
 * <h2>为什么要单独一个类型（而不是复用 {@code BusinessException}）</h2>
 *
 * <p>本模块的失败分两类，<b>形状互不相同</b>，靠类型区分比靠 code 区分可靠：
 * <table border="1">
 *   <caption>两类失败的 wire 形状</caption>
 *   <tr><th>来源</th><th>B 出处</th><th>Java 侧</th><th>HTTP</th><th>body</th></tr>
 *   <tr>
 *     <td>业务判定失败（gRPC code → 业务码）</td>
 *     <td>{@code httpx.OkJson(w, &response.Response{Code: code, ...})}（各 handler 显式构造）</td>
 *     <td>{@code BusinessException}</td>
 *     <td><b>200</b></td>
 *     <td>{@code {"code":…,"msg":…,"data":null}}</td>
 *   </tr>
 *   <tr>
 *     <td>解析层失败 / 上传时下游失败</td>
 *     <td>{@code httpx.ErrorCtx(ctx, w, err)}（article-api <b>没有</b> error handler）</td>
 *     <td>本类型</td>
 *     <td><b>400</b></td>
 *     <td>裸文案 + {@code "\n"}，text/plain + nosniff</td>
 *   </tr>
 * </table>
 *
 * <p>⚠️ <b>不能用 code 分流</b>：本模块的 {@code ErrorCode} 里 1001~1006 与 5001~5006
 * <b>全部</b>走 envelope，没有任何业务码走 text/plain；
 * 反过来 400 这条路径上也不会出现任何 article 业务码。
 * 按 code 分流在当前码表下恰好也能工作，但那是<b>巧合</b> ——
 * 一旦有人加一个「HTTP 语义的码」，按 code 分流会立刻塌掉。
 * 层次由<b>类型</b>承载，与 {@code com.rag.common.exception.AuthenticationException}
 * 相对 {@code BusinessException} 是同一条纪律。
 *
 * <h2>调用点（两个，不要更多）</h2>
 * <ol>
 *   <li>{@code GlobalExceptionHandler} —— 把 Spring 的解析异常转成本类型；</li>
 *   <li>{@code ArticleUploadService} —— B 侧 {@code UploadImageHandler} 把
 *       {@code r.FormFile} 错误与 {@code UploadFile} RPC 错误<b>都</b>交给
 *       {@code httpx.ErrorCtx}，所以这两条在 B 侧 里是<b>同一个</b> 400。</li>
 * </ol>
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/article/article-api/src/main/java/com/rag/article/article/api/http/GoStyleBadRequestException.java}
 * §1.11 第 246 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
public class BadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}