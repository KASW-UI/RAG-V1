package com.rag.follow.contract;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>follow 域业务错误载体（L1 契约层）</b> —— 对应 B 侧
 * {@code service/follow/follow-common/src/main/java/com/rag/follow/follow/common/errmsg/FollowCodeError.java}。
 *
 * <p><b>B 出处（Go 侧原始形态）</b>：
 * <pre>
 * type CodeError struct {
 *     Code int    `json:"code"`
 *     Msg  string `json:"msg"`
 * }
 * func (e *CodeError) Error() string {
 *     return fmt.Sprintf("ErrCode:%d, Errmsg:%s", e.Code, e.Msg)
 * }
 * func NewErrCode(code int) error             { return &amp;CodeError{Code: code, Msg: GetErrMsg(code)} }
 * func NewErrCodeMsg(code int, msg string) error { return &amp;CodeError{Code: code, Msg: msg} }
 * </pre>
 *
 * <p><b>为什么是 {@link RuntimeException} 而不是 checked exception</b>：
 * B 侧（Go）的 {@code error} 在语言层面没有 checked/unchecked 之分，Service 层返回 error、上层判定。
 * Java 侧对等物是 unchecked 异常 ⇒ 业务判定处显式构造并抛出，由 api 层的异常处理器统一转换。
 * 用 checked 会把每个调用点都拖进 {@code throws}，而 B 侧根本没有这个成本。
 *
 * <p><b>⚠️ Jackson 注解不是装饰，是行为对齐</b>：B 侧（Go 的 {@code encoding/json}）只序列化导出字段，
 * {@code Error()} 是方法不是字段，<b>不会</b>出现在 JSON 里。
 * Java {@code Throwable} 继承的 {@code getMessage()} / {@code getCause()} / {@code getStackTrace()}
 * 默认都是可见 getter ⇒ 会额外吐出 B 侧根本不存在的 {@code message} / {@code cause} / {@code stackTrace} 字段。
 * ⇒ 这里用 {@link JsonAutoDetect} 把继承来的 getter 全部关掉，只留 {@code code} / {@code msg}。
 *
 * <p><b>⚠️ 写 api 层异常处理器时注意</b>：B 侧取业务码用的是 {@code errors.As(err, &codeErr)}
 * （<b>会</b>沿 cause 链查找）⇒ Java 对应物必须遍历 {@link Throwable#getCause()} 链，不能只判顶层。
 * 这与 {@link GrpcErrorMapper#bizCodeFromError} 的<b>直接类型断言</b>（只判顶层）恰好相反，切勿写混。
 *
 * <p><b>⚠️ 表达层差异（L4 · 语义等价）</b>：B 侧本类是「错误值对象 + 工厂函数」，
 * C 侧改为「unchecked 异常 + 静态工厂」⇒ <b>构造参数顺序、{@code getCode()} / {@code getMsg()}
 * 的返回值、{@code getMessage()} 的文本格式逐条一致</b>。
 * ⇒ 差异只在「异常 vs 值对象」这一表达层选择（Java 侧无返回值风格的多返回 ⇒ 用异常表达失败）。
 */
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class CodeError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 对应 B 侧字段 {@code Code int `json:"code"`}。 */
    private final int code;

    /** 对应 B 侧字段 {@code Msg string `json:"msg"`}。 */
    private final String msg;

    public CodeError(int code, String msg) {
        // getMessage() 必须与 B 侧的 Error() 逐字相同，运维 grep 日志依赖这个格式
        super(String.format("ErrCode:%d, Errmsg:%s", code, msg));
        this.code = code;
        this.msg = msg;
    }

    /** 对应 B 侧 {@code func NewErrCode(code int) error}：msg 走查表（未知码回退 {@code "FAIL"}）。 */
    public static CodeError newCode(int code) {
        return new CodeError(code, ErrMsg.getMsg(code));
    }

    /** 对应 B 侧 {@code func NewErrCodeMsg(code int, msg string) error}：msg 用传入值，<b>不</b>走查表。 */
    public static CodeError newCodeMsg(int code, String msg) {
        return new CodeError(code, msg);
    }

    @JsonProperty("code")
    public int getCode() {
        return code;
    }

    @JsonProperty("msg")
    public String getMsg() {
        return msg;
    }
}
