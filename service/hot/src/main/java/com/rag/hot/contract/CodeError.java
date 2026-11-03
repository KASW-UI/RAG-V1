package com.rag.hot.contract;

import com.rag.common.exception.BusinessException;

/**
 * <p><b>hot 域业务错误载体</b> —— 携带<b>业务码 {@code code}</b> + <b>业务文案 {@code msg}</b> 的 unchecked 异常。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/exception/HotCodeError.java}
 * <pre>{@code
 *   类声明 extends BusinessException                        :57
 *   字段 private final String bizMsg（业务文案，给 HTTP 信封用） :60
 *   构造器 super(code,msg) + this.bizMsg = msg             :66-69
 *   getBizMsg() —— 纯文案，给 HTTP 信封用                    :77-79
 *   getMessage() 覆写 → "ErrCode:%d, Errmsg:%s"（给日志用）    :87-90
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯命名 / 定位</b>改动 ——
 *       ① 类名 {@code HotCodeError} → {@code CodeError}、包名
 *       {@code com.rag.hot.hot.api.exception} → {@code com.rag.hot.contract}（D7 包压平 +
 *       映射表 hot.md §1.1「异常载体下沉到契约包」）；</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       ① 基类 {@link BusinessException}（B 侧同款，跨域共享层）逐条未动；
 *       ② 字段名 / 类型 / 可见性、构造器入参顺序逐条未动；
 *       ③ {@code getBizMsg()} 与 {@code getMessage()} 的<b>两个面</b>逐条未动。</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么 {@code getMessage()} 被覆写成「ErrCode:…, Errmsg:…」整串</b>：
 * B 侧的业务错误日志取的是 {@code err.getMessage()} ⇒ 「hot-rpc 返回空响应」这一条
 * {@code business_error} 的 {@code error_reason} 字段是
 * <pre>
 *   ErrCode:5001, Errmsg:internal server error
 * </pre>
 * ⇒ 本类覆写 {@code getMessage()} 就是为了<b>让日志这一面与 B 侧逐字节一致</b>。
 *
 * <p><b>⚠️ 为什么「HTTP 信封」不能跟着变</b>：B 侧的信封是<b>逐字段复制</b>
 * （B 侧 {@code hot-api/.../api/advice/GlobalExceptionHandler.java:98-100}）⇒ 信封的 {@code msg}
 * 必须是<b>纯文案</b> {@code "internal server error"}，
 * <b>不能</b>是 {@code "ErrCode:5001, Errmsg:internal server error"}。
 * ⇒ 故本类把两个面<b>分开</b>：
 * <ul>
 *   <li>{@link #getBizMsg()} ⇒ 纯文案，<b>给 HTTP 信封</b>；</li>
 *   <li>{@link #getMessage()} ⇒ B 侧的 {@code Error()} 整串，<b>给日志</b>。</li>
 * </ul>
 * ⚠️ {@code HotApiExceptionHandler} 必须用 {@link #getBizMsg()} 取信封文案；
 * 用 {@code getMessage()} 会把 {@code "ErrCode:5001, Errmsg:..."} 写进响应体 ⇒ <b>制造新漂移</b>。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：两个方法各自承载<b>不同的语义面</b>（纯文案 vs 日志整串），
 * {@code @Getter} 无法表达 ⇒ 保留手写方法（A13 判据不适用）。
 */
public class CodeError extends BusinessException {

    /** 序列化版本号。 */
    private static final long serialVersionUID = 1L;

    /** 业务文案（Go 的 {@code CodeError.Msg}）—— 给 HTTP 信封用（B 侧 :60）。 */
    private final String bizMsg;

    /**
     * 构造器 —— 与 B 侧逐字一致（B 侧 :66-69）。
     *
     * @param code 业务码
     * @param msg  业务文案，通常是 {@code ErrMsg.getMsg(code)}
     */
    public CodeError(int code, String msg) {
        super(code, msg);
        this.bizMsg = msg;
    }

    /**
     * 取<b>业务文案</b>（纯文案）—— 与 B 侧 {@code getBizMsg()} 逐字一致（B 侧 :77-79）。
     *
     * <p>⚠️ <b>不要</b>用 {@link #getMessage()} 填信封：那个方法是日志用的整串，
     * 会把 {@code "ErrCode:5001, Errmsg:internal server error"} 写进响应体。
     *
     * @return 纯业务文案
     */
    public String getBizMsg() {
        return bizMsg;
    }

    /**
     * 覆写 {@code Throwable.getMessage()} ⇒ 返回 B 侧 {@code (*CodeError).Error()} 的逐字节形态
     * （B 侧 :87-90）。
     *
     * <p>这是本类存在的<b>唯一</b>理由：让日志的 {@code error_reason} 字段与 B 侧一致。
     * ⚠️ 该串<b>只</b>进日志；HTTP 信封走 {@link #getBizMsg()}。
     *
     * @return 形如 {@code "ErrCode:5001, Errmsg:internal server error"} 的文本
     */
    @Override
    public String getMessage() {
        return "ErrCode:" + getCode() + ", Errmsg:" + bizMsg;
    }
}
