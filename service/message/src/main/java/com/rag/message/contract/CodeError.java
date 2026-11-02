package com.rag.message.contract;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>message 域业务错误载体</b> —— 携带<b>业务码 {@code code}</b> + <b>业务文案 {@code msg}</b> 的 unchecked 异常，
 * 同时也是 API / HTTP 路径上的错误 JSON 形状（{@code {"code":…,"msg":…}}）。
 *
 * <p><b>B 出处</b>：{@code service/message/message-common/src/main/java/com/rag/message/message/common/errmsg/MessageGrpcError.java}
 * <pre>{@code
 *   内部类 CodeError 声明（static final，extends RuntimeException）  :78
 *   @JsonAutoDetect（屏蔽 Throwable 继承来的 getter）                :76-77
 *   serialVersionUID                                              :80
 *   字段 private final int code / private final String msg         :82-83
 *   构造器（super(...) 写入 "ErrCode:%d, Errmsg:%s"）               :85-89
 *   getCode() + @JsonProperty("code")                              :94-97
 *   getMsg()  + @JsonProperty("msg")                               :102-105
 *   Jackson 注解的选型理由（B 侧原注释）                            :59-70
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯表达层</b>改动 ——
 *       ① <b>内部类 → 顶层类</b>（B 侧是 {@code MessageGrpcError} 的嵌套类 :78；C 侧按域内文件树拆成
 *       {@code contract/CodeError.java} 顶层类 + {@code contract/GrpcErrorMapper.java} 工具类，
 *       形态依据见 {@code docs/深挖修正记录.md} §三 M-3 落地表）；
 *       ② 包名 {@code com.rag.message.message.common.errmsg} → {@code com.rag.message.contract}（D7 包压平）；
 *       ③ <b>{@code getMessage()} 由「继承」改为「显式覆写</b>」（值不变，见下方专段）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       ① 字段名 / 类型 / 可见性、构造器入参顺序、{@code super(...)} 写入的字符串格式
 *       （{@code String.format("ErrCode:%d, Errmsg:%s", code, msg)}）、两个 getter 的
 *       {@code @JsonProperty} 键名（{@code code} / {@code msg}）逐条未动
 *       ⇒ 序列化出的 JSON 字段集合与 B 侧相同（只有 {@code code} / {@code msg} 两个键）；
 *       ② B 侧内部类是 {@code static final}，C 侧顶层类<b>不</b>加 {@code final}
 *       （上游调用形态要求 {@code public class CodeError extends RuntimeException}，且无子类化行为）⇒ 不影响可观察行为。</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>⚠️ {@code @JsonAutoDetect} 那行注解必须逐字保留</b>（B 侧 :76-77）——
 * 它把 getter 可见性关成 {@code NONE}，屏蔽掉 {@link Throwable} 继承来的
 * {@code getMessage()} / {@code getLocalizedMessage()} / {@code getCause()} / {@code getStackTrace()} /
 * {@code getSuppressed()} ⇒ 序列化结果里<b>不会</b>多出 B 侧不存在的
 * {@code message} / {@code localizedMessage} / {@code cause} / {@code stackTrace} 字段
 * （B 侧原注释 :59-70：Go 的 {@code encoding/json} 只序列化导出字段，{@code Message()} 是方法不是字段）。
 * ⚠️ 只留 {@code @JsonProperty} 而删掉 {@code @JsonAutoDetect} ⇒ JSON 会多出 4~5 个字段 = 改 L1 wire 形状。
 *
 * <p><b>⚠️ {@code getMessage()} 的输出格式是 L1 契约</b>：
 * {@code "ErrCode:%d, Errmsg:%s"}（B 侧构造器 :86）⇒ 便于 grep，两侧格式串一致。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：两个 getter 各自带 {@code @JsonProperty} 语义
 * （B 侧 :94 / :102 是手写 getter + 显式注解），用 {@code @Getter} 需靠 {@code onMethod_} 拼装注解
 * ⇒ 表达层收益低于可读性损失 ⇒ 保留手写 getter（本层 5 个文件均不需要 Lombok ⇒ A13 判据不适用）。
 */
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class CodeError extends RuntimeException {

    /** 序列化版本号 —— 与 B 侧一致（:80）。 */
    private static final long serialVersionUID = 1L;

    /** 业务码 —— B 侧同名私有字段（:82）。 */
    private final int code;

    /** 业务文案 —— B 侧同名私有字段（:83）。 */
    private final String msg;

    /**
     * 构造器 —— 与 B 侧逐字一致（:85-89）。
     *
     * <p>{@code super(...)} 写入的字符串就是 {@link #getMessage()} 的返回值
     * （B 侧 :86 的 {@code String.format("ErrCode:%d, Errmsg:%s", code, msg)}）。
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
     * 取业务码 —— B 侧 {@code getCode()}（:95-97），JSON 键名 {@code code}（:94）。
     *
     * @return 业务码
     */
    @JsonProperty("code")
    public int getCode() {
        return code;
    }

    /**
     * 取业务文案 —— B 侧 {@code getMsg()}（:103-105），JSON 键名 {@code msg}（:102）。
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
     * <b>显式可见</b>（调用方与 review 都能直接看到方法签名，不必去读 {@link Throwable}）。
     *
     * <p><b>语义还一样吗</b>：一样 —— 返回值直接取 {@link Throwable} 持有的原始文本
     * （即构造器传给 {@code super(...)} 的那个字符串，B 侧 :86）⇒ 与 B 侧继承所得的文本<b>逐字节相同</b>；
     * 且格式串在本仓<b>只有一处</b>（构造器里那一处）⇒ 不存在「两处格式串各自漂移」的面。
     *
     * @return 形如 {@code "ErrCode:5202, Errmsg:conversation not found"} 的文本
     */
    @Override
    public String getMessage() {
        return super.getMessage();
    }
}
