package com.rag.like.contract;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>like 域业务错误载体</b> —— 携带<b>业务码 {@code code}</b> + <b>业务文案 {@code msg}</b> 的 unchecked 异常。
 *
 * <p><b>B 出处</b>：{@code service/like/like-common/src/main/java/com/rag/like/like/common/errmsg/CodeError.java:22-64}
 * <pre>{@code
 *   @JsonAutoDetect（屏蔽 Throwable 继承来的 getter）        :40-41
 *   serialVersionUID                                          :44
 *   字段 private final int code / private final String msg     :46-52
 *   构造器（super(...) 写入 "ErrCode:%d, Errmsg:%s"）           :54-59
 *   newCode(int) / newCodeMsg(int, String) 两个静态工厂         :61-70
 *   getCode() + @JsonProperty("code")                          :72-76
 *   getMsg()  + @JsonProperty("msg")                           :78-82
 * }</pre>
 *
 * <h3>⚠️ {@code @JsonAutoDetect} 那行注解必须逐字保留</h3>
 * <p>它把 getter 可见性关成 {@code NONE}，屏蔽掉 {@link Throwable} 继承来的
 * {@code getMessage()} / {@code getLocalizedMessage()} / {@code getCause()} / {@code getStackTrace()} /
 * {@code getSuppressed()} ⇒ 序列化结果里<b>不会</b>多出 B 侧不存在的
 * {@code message} / {@code localizedMessage} / {@code cause} / {@code stackTrace} 字段
 * （B 侧原注释：Go 的 {@code encoding/json} 只序列化导出字段，{@code Message()} 是方法不是字段）。
 * ⚠️ 只留 {@code @JsonProperty} 而删掉 {@code @JsonAutoDetect} ⇒ JSON 会多出 4~5 个字段 = <b>改 L1 wire 形状</b>。
 *
 * <h3>⚠️ {@code getMessage()} 的输出格式是 L1 契约</h3>
 * <p>{@code "ErrCode:%d, Errmsg:%s"}（B 侧构造器）⇒ 便于全链路 grep，两侧格式串一致。
 *
 * <h3>⚠️ 与 {@link com.rag.like.exception.LikeExceptions.Rpc} 的分工</h3>
 * <ul>
 *   <li>本类 = <b>HTTP 侧</b>的业务错误载体（B 侧 api 层的 {@code errmsg.CodeError}）
 *       ⇒ 被 {@code advice/LikeApiExceptionHandler} 折叠成 <b>HTTP 200 + {code,msg,data:null}</b>。</li>
 *   <li>{@code LikeExceptions.Rpc} = <b>gRPC 侧</b>的领域异常（B 侧 rpc 层的 {@code errmsg.NewGrpcErr}）
 *       ⇒ 被 {@code grpc/LikeGrpcService} 转成 {@link GrpcErrFactory#grpcError}。</li>
 * </ul>
 * ⇒ 两侧<b>不共用同一个类</b>，因为它们的<b>出口形状不同</b>（B 侧也是两个不同的东西）。
 *
 * <h3>⚠️ 本类<b>不使用 Lombok</b></h3>
 * <p>两个 getter 各自带 {@code @JsonProperty} 语义（B 侧是手写 getter + 显式注解），
 * 用 {@code @Getter} 需靠 {@code onMethod_} 拼装注解 ⇒ 表达层收益低于可读性损失 ⇒ 保留手写 getter
 * （本层 7 个文件均不需要 Lombok ⇒ A13 判据不适用）。
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
     * 对应 B 侧 {@code func NewErrCodeMsg(code int, msg string) error}：<b>msg 用传入值</b>。
     *
     * @param code 业务码
     * @param msg  业务文案（<b>原样透传，不查表</b>）
     * @return 异常实例
     */
    public static CodeError newCodeMsg(int code, String msg) {
        return new CodeError(code, msg);
    }

    /**
     * 取业务码 —— B 侧 {@code getCode()}，JSON 键名 {@code code}。
     *
     * @return 业务码
     */
    @JsonProperty("code")
    public int getCode() {
        return code;
    }

    /**
     * 取业务文案 —— B 侧 {@code getMsg()}，JSON 键名 {@code msg}。
     *
     * @return 业务文案
     */
    @JsonProperty("msg")
    public String getMsg() {
        return msg;
    }

    /**
     * 取错误文本 —— <b>显式覆写</b>（B 侧不覆写，靠 {@link Throwable} 继承）。
     *
     * <p><b>为什么加这一层</b>：让「错误文本 = {@code "ErrCode:%d, Errmsg:%s"}」这条 L1 契约在类表面
     * <b>显式可见</b>。返回值直接取 {@link Throwable} 持有的原始文本 ⇒ 与 B 侧继承所得的文本
     * <b>逐字节相同</b>；且格式串在本仓<b>只有一处</b>（构造器里那一处）⇒ 不存在两处各自漂移的面。
     *
     * @return 形如 {@code "ErrCode:5003, Errmsg:DB查询失败"} 的文本
     */
    @Override
    public String getMessage() {
        return super.getMessage();
    }
}
