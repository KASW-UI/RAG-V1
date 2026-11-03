package com.rag.common.web;

import org.springframework.http.HttpStatus;

/**
 * B 侧 {@code http.Error(w, err.Error(), <非 400 的状态码>)} 的载体。
 *
 * <h2>为什么需要它（与 {@link BadRequestException} 的分工）</h2>
 * <p>两者都是「裸状态码 + text/plain」，区别只在<b>状态码</b>：
 * <table border="1">
 *   <caption>两个类型的分工</caption>
 *   <tr><th>类型</th><th>HTTP</th><th>对应 B 侧 分支</th></tr>
 *   <tr>
 *     <td>{@link BadRequestException}</td>
 *     <td>恒 <b>400</b></td>
 *     <td>{@code doHandleError} 的 <b>else</b> 分支（{@code responses.go:156-157}）——
 *         <b>非</b> gRPC 错误</td>
 *   </tr>
 *   <tr>
 *     <td>本类型</td>
 *     <td><b>按 gRPC 码映射</b></td>
 *     <td>{@code doHandleError} 的 <b>IsGrpcError</b> 分支（{@code responses.go:150-153}）</td>
 *   </tr>
 * </table>
 *
 * <h2>⚠️ 状态码不能靠业务码推导</h2>
 * <p>article 域的业务码（1001~1006 / 5001~5006）<b>全部</b>走「200 + 信封」，
 * 与本类型所在的「裸状态码」路径<b>互斥</b>。
 * <p>⇒ 本类型携带的是 <b>HTTP 语义</b>的状态码（500/503/504/429…），
 * 它<b>没有</b>任何 article 业务码 —— 因为 B 侧这条路径压根不产生业务码。
 *
 * <h2>B 出处</h2>
 * <pre>
 * // B 侧：service/article/article-api/src/main/java/com/rag/article/article/api/http/GoStyleHttpErrorException.java:34:150-153
 * } else if errcode.IsGrpcError(err) {
 *     http.Error(w, err.Error(), errcode.CodeFromGrpcError(err))
 * }
 * </pre>
 * 映射表见 {@link GrpcStatusMapper}（B 侧同名类 = {@code GoStyleGrpcStatusMapper}）。
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/article/article-api/src/main/java/com/rag/article/article/api/http/GoStyleHttpErrorException.java}
 * §1.11 第 247 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
public class HttpErrorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    public HttpErrorException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /** HTTP 状态码（B 侧 {@code errcode.CodeFromGrpcError} 的映射结果）。 */
    public HttpStatus getStatus() {
        return status;
    }
}
