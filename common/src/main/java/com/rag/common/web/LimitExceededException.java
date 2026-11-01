package com.rag.common.web;

/**
 * 🔴 「按 Go 的语义，这个 query 该被拒」 —— 由共享层的闸门抛出，
 * <b>由各域自己的兜底 handler 渲染成该域的错误出口</b>。
 *
 * <h2>🔴 为什么它是「普通」RuntimeException，而不是 {@code BusinessException}</h2>
 * <pre>
 * go-zero@v1.10.2/rest/httpx/responses.go:142-173  doHandleError
 *   code, body := handler(err)
 *   // handler = SetErrorHandler 注册的那个：
 *   //   code = ErrorServerCommon(5001)（因为 err 不是 *CodeError，取不到 code）
 *   //   msg  = GetErrMsg(code)                       ← **从该域的错误码表查**
 * </pre>
 * <p>⇒ Go 的「解析失败」这条路径<b>不会</b>把错误原文放进信封 ⇒ 只带 code + 查表文案。
 * <br>⇒ RAG 侧要对齐，就<b>不能</b>把 Go 原文塞进 {@code msg}。
 *
 * <p>⚠️ 若本类继承 {@code com.rag.common.exception.BusinessException}，各域的
 * {@code @ExceptionHandler(BusinessException.class)} 会优先命中 ⇒ 信封的 msg 会变成
 * {@code getMessage()}（= Go 原文）⇒ <b>like / comment 会变成「系统内部错误」之外的乱码</b>
 * ⇒ ⇒ <b>刻意继承 {@link RuntimeException}</b> ⇒ 让它落到各域的
 * {@code @ExceptionHandler(Exception.class)} 兜底 ⇒ 那里才是「查自己的表、渲染 200 + 5001」。
 *
 * <h2>⚠️ 哪些域会收到它</h2>
 * <p>只有 {@link LimitMode#ENVELOPE} 模式的域
 * （hot / follow / like / message / comment —— 即 Go 侧<b>注册了</b> {@code SetErrorHandler} 的那些）。
 * <br>其余<b>装闸门</b>的域（article / user / admin）走
 * {@link LimitMode#RAW} ⇒ 共享层<b>直接写</b> Go 的 {@code http.Error} 形状。
 * <br>⚠️ <b>两个域收不到它</b>：{@code task}（Go 侧不解析 form/query ⇒ 闸门不开）
 * 与 {@code favorite}（<b>域自己捕获</b>解析错 ⇒ 拒绝形状由域自己定 ⇒ 共享层不装闸门）
 * ⇒ ⇒ 它们的超限由<b>域内自己</b>落到 Go 的解析失败出口（favorite = 200 + {code:5003,…}）。
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/http/GoStyleFormLimitExceededException.java}
 * §1.12 第 267 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
public class LimitExceededException extends RuntimeException {

    /**
     * Go 的原文（如 {@code "too many form values, error: a=1&a=1…"}）。
     *
     * <p>⚠️ 它<b>不会</b>出现在 wire 上（Go 侧同理：{@code SetErrorHandler} 只取 code/msg）⇒
     * 它的用途是<b>日志与排障</b>，不是「把 Go 的原文发给客户端」。
     */
    private final String goMessage;

    public LimitExceededException(String goMessage) {
        super(goMessage);
        this.goMessage = goMessage;
    }

    /** Go 的原文（不进 wire）。 */
    public String getGoMessage() {
        return goMessage;
    }
}
