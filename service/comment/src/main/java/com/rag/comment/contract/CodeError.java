package com.rag.comment.contract;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>comment 域业务错误载体</b> —— 携带<b>业务码 {@code code}</b> + <b>业务文案 {@code msg}</b> 的 unchecked 异常。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-common/src/main/java/com/rag/comment/comment/common/errmsg/CommentCodeError.java:24-100}
 * <pre>{@code
 *   @JsonAutoDetect（屏蔽 Throwable 继承来的 getter）        :43-44
 *   serialVersionUID                                          :47
 *   字段 private final int code / private final String msg     :49-55
 *   构造器（super(...) 写入 "ErrCode:%d, Errmsg:%s"）           :57-62
 *   newCode(int) / newCodeMsg(int, String) 两个静态工厂         :64-72
 *   findIn(Throwable)：沿 cause 链回溯（对应 Go errors.As）      :74-88
 *   getCode() + @JsonProperty("code")                          :90-94
 *   getMsg()  + @JsonProperty("msg")                           :96-100
 * }</pre>
 *
 * <h3>⚠️ {@code @JsonAutoDetect} 那行注解必须逐字保留</h3>
 * <p>它把 getter 可见性关成 {@code NONE}，屏蔽掉 {@link Throwable} 继承来的
 * {@code getMessage()} / {@code getLocalizedMessage()} / {@code getCause()} / {@code getStackTrace()} /
 * {@code getSuppressed()} ⇒ 序列化结果里<b>不会</b>多出 B 侧不存在的
 * {@code message} / {@code localizedMessage} / {@code cause} / {@code stackTrace} 字段
 * （B 侧原注释：Go 的 {@code encoding/json} 只序列化导出字段，{@code Error()} 是方法不是字段）。
 * ⚠️ 只留 {@code @JsonProperty} 而删掉 {@code @JsonAutoDetect} ⇒ JSON 会多出 4~5 个字段 = <b>改 L1 wire 形状</b>。
 *
 * <h3>⚠️ {@code getMessage()} 的输出格式是 L1 契约</h3>
 * <p>{@code "ErrCode:%d, Errmsg:%s"}（B 侧构造器）⇒ 便于全链路 grep，两侧格式串一致。
 *
 * <h3>⚠️ API 层必须沿 cause 链回溯，不能只 catch 最外层</h3>
 * <p>B 侧 HTTP 出口做的是 {@code errors.As(err, &e)} —— 它<b>会沿 {@code Unwrap()} 链一路回溯</b>，
 * 所以被业务代码 {@code fmt.Errorf("...: %w", err)} 包过一层的 {@code *CodeError}
 * 仍能被还原成原始 code/msg；匹配不到才回退 {@code ErrorServerCommon}(5001) + 「系统内部错误」。
 * ⇒ Java 侧对应的就是 {@link #findIn(Throwable)} —— <b>不要写成单纯的
 * {@code catch (CodeError e)}</b>，那会把「包过一层的业务错误」误降级成 5001，
 * 让客户端拿到 5001 而不是真实业务码（可观察行为变了）。
 *
 * <h3>⚠️ 与 {@link com.rag.comment.exception.CommentExceptions.Rpc} 的分工</h3>
 * <ul>
 *   <li>本类 = <b>HTTP 侧</b>的业务错误载体（B 侧 api 层的 {@code errmsg.CodeError}）
 *       ⇒ 被 {@code advice/CommentApiExceptionHandler} 折叠成 <b>HTTP 200 + {code,msg,data:null}</b>。</li>
 *   <li>{@code CommentExceptions.Rpc} = <b>gRPC 侧</b>的领域异常（B 侧 rpc 层的 {@code errmsg.NewGrpcErr}）
 *       ⇒ 被 {@code grpc/CommentGrpcService} 转成 {@link GrpcErrFactory#grpcError}。</li>
 * </ul>
 * ⇒ 两侧<b>不共用同一个类</b>，因为它们的<b>出口形状不同</b>（B 侧也是两个不同的东西）。
 *
 * <h3>⚠️ 本类<b>不使用 Lombok</b></h3>
 * <p>两个 getter 各自带 {@code @JsonProperty} 语义（B 侧是手写 getter + 显式注解），
 * 用 {@code @Getter} 需靠 {@code onMethod_} 拼装注解 ⇒ 表达层收益低于可读性损失 ⇒ 保留手写 getter。
 */
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class CodeError extends RuntimeException {

    /** 序列化版本号 —— 与 B 侧一致。 */
    private static final long serialVersionUID = 1L;

    /** 业务码 —— B 侧同名私有字段。 */
    private final int code;

    /** 业务文案 —— B 侧同名私有字段。 */
    private final String msg;

    /**
     * 构造器 —— 与 B 侧逐字一致。
     *
     * <p>{@code super(...)} 写入的字符串就是 {@link #getMessage()} 的返回值
     * （B 侧的 {@code String.format("ErrCode:%d, Errmsg:%s", code, msg)}）。
     *
     * @param code 业务码
     * @param msg  业务文案
     */
    public CodeError(int code, String msg) {
        super(String.format("ErrCode:%d, Errmsg:%s", code, msg));
        this.code = code;
        this.msg = msg;
    }

    /**
     * 对应 B 侧 {@code func NewErrCode(code int) error}：<b>msg 走查表</b>。
     *
     * @param code 业务码
     * @return 异常实例（msg 取 {@link ErrMsg#getMsg(int)}）
     */
    public static CodeError newCode(int code) {
        return new CodeError(code, ErrMsg.getMsg(code));
    }

    /**
     * 对应 B 侧 {@code func NewErrCodeMsg(code int, msg string) error}：<b>msg 用传入值，不查表</b>。
     *
     * @param code 业务码
     * @param msg  业务文案（原文）
     * @return 异常实例
     */
    public static CodeError newCodeMsg(int code, String msg) {
        return new CodeError(code, msg);
    }

    /**
     * 对应 B 侧 {@code errors.As(err, &e)}：沿 {@code getCause()} 链回溯找第一个
     * {@link CodeError}，找不到返回 {@code null}（调用方据此回退 5001）。
     *
     * <p><b>循环安全的说明</b>：{@code Throwable.initCause} 禁止自引用，
     * 正常构造的 cause 链必然终止，故不设深度上限也不会死循环；
     * 手工 {@code override getCause()} 造环属病态构造，不在防御范围。
     *
     * @param err 任意异常（可为 null）
     * @return 命中的 {@link CodeError}；未命中返回 {@code null}
     */
    public static CodeError findIn(Throwable err) {
        for (Throwable cur = err; cur != null; cur = cur.getCause()) {
            if (cur instanceof CodeError codeError) {
                return codeError;
            }
        }
        return null;
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
