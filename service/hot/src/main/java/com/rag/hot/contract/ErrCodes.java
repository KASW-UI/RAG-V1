package com.rag.hot.contract;

/**
 * <p><b>hot 域业务错误码枚举</b> —— <b>11 个码值的唯一真源</b>（L1 契约 · D16：B 侧 RAG 仓为唯一判定基准）。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-common/src/main/java/com/rag/hot/hot/common/errmsg/HotErrorCode.java}
 * <pre>{@code
 *   枚举声明 + 11 个常量（常量名 / 码值 / 声明顺序逐条照抄）   :36-73
 *     SUCCESS(200)                      :41    ERROR(500)                       :44
 *     CODE_SERVER_BUSY(1015)            :47    ERROR_SERVER_COMMON(5001)        :52
 *     ERROR_NOT_FOUND(5002)             :56    ERROR_INVALID_PARAM(5003)        :60
 *     ERROR_UNAUTHORIZED(5004)          :64    ERROR_FORBIDDEN(5005)            :68
 *     ERROR_ALREADY_EXIST(5006)         :72    ERROR_DB_SELECT(5007)            :70
 *     ERROR_DB_UPDATE(5008)             :73
 *   码值字段 private final int code      :75
 *   构造器（码值落字段）                   :77-79
 *   getCode()                           :82-84
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯命名 / 定位</b>改动 ——
 *       ① 类型名 {@code HotErrorCode} → {@code ErrCodes}（本仓域内契约包的既定类名）；
 *       ② 包名 {@code com.rag.hot.hot.common.errmsg} → {@code com.rag.hot.contract}
 *       （D7 包压平 · AGENTS.md §2.1：域内错误契约落 {@code contract/} 包）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 枚举常量名、码值、声明顺序、{@code getCode()} 的返回口径逐条未动
 *       ⇒ 调用点 {@code ErrCodes.ERROR_SERVER_COMMON.getCode()} 取值 == B 侧
 *       {@code HotErrorCode.ERROR_SERVER_COMMON.getCode()}。</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>⚠️ 逐域隔离纪律（AGENTS.md §八「未统一化」）</b>：错误契约<b>逐域隔离</b>，禁止跨域统一 ——
 * 本域的文案是<b>英文</b>（{@code "service busy"} / {@code "internal server error"} …），
 * 而 article / comment / like / user 域的同号码是<b>中文</b>且<b>语义不同</b>：
 * <pre>
 *   码值  hot（本类）              users / article
 *   5002  record not found          更新数据库失败
 *   5003  invalid parameter         查询数据库失败
 *   5004  unauthorized              数据库插入失败 / 文件上传失败
 * </pre>
 * ⇒ 三方两两冲突，<b>不存在「取一个通用码表」的选项</b> ⇒ 本枚举 11 个码值<b>一个字都不能改</b>
 * （改码值 = 改客户端可见行为 = L1 变更 ⇒ 走决策流程）。
 *
 * <p><b>⚠️ 文案不在本类</b>：文案（{@code msg}）同属 L1 契约，但归 {@link ErrMsg} ——
 * 本类只放码值，避免「同一个码在两处各有一份」的漂移面（AGENTS.md §八「L2 已归位」）。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：B 侧 {@code HotErrorCode} 是手写 enum（码值字段 + 手写
 * {@code getCode()}），本类为<b>逐字照抄</b>；Lombok 对 enum 常量与手写 getter 无可消除的样板
 * （{@code @Getter} 也不能用在 enum 上产出常量访问器）⇒ 本层不引入 Lombok（A13 判据不适用）。
 */
public enum ErrCodes {

    /** RPC 成功回执的业务码（B 侧 {@code HotErrorCode.SUCCESS}，:41）。 */
    SUCCESS(200),

    /** 通用失败回执；同时是 {@link ErrMsg#getMsg(int)} 未知码的回落文案来源
     * （B 侧 {@code HotErrorCode.ERROR}，:44）。 */
    ERROR(500),

    /** 服务繁忙 / 兜底码（B 侧 {@code HotErrorCode.CODE_SERVER_BUSY}，:47；
     * 消费点见 {@link GrpcErrorMapper} 的兜底分支）。 */
    CODE_SERVER_BUSY(1015),

    /** 系统内部错误 —— hot-api 的错误兜底码（B 侧 {@code HotErrorCode.ERROR_SERVER_COMMON}，:52）。 */
    ERROR_SERVER_COMMON(5001),

    /** 记录不存在（B 侧 {@code HotErrorCode.ERROR_NOT_FOUND}，:56）。 */
    ERROR_NOT_FOUND(5002),

    /** 参数非法（B 侧 {@code HotErrorCode.ERROR_INVALID_PARAM}，:60）。 */
    ERROR_INVALID_PARAM(5003),

    /** 未认证（B 侧 {@code HotErrorCode.ERROR_UNAUTHORIZED}，:64）。 */
    ERROR_UNAUTHORIZED(5004),

    /** 无权限（B 侧 {@code HotErrorCode.ERROR_FORBIDDEN}，:68）。 */
    ERROR_FORBIDDEN(5005),

    /** 记录已存在（B 侧 {@code HotErrorCode.ERROR_ALREADY_EXIST}，:72）。 */
    ERROR_ALREADY_EXIST(5006),

    /** 数据库查询失败（B 侧 {@code HotErrorCode.ERROR_DB_SELECT}，:70）。 */
    ERROR_DB_SELECT(5007),

    /** 数据库更新失败（B 侧 {@code HotErrorCode.ERROR_DB_UPDATE}，:73）。 */
    ERROR_DB_UPDATE(5008);

    /** 码值 —— B 侧同名私有字段（:75）。 */
    private final int code;

    /**
     * 构造器 —— 与 B 侧逐字一致（:77-79）。
     *
     * @param code 业务码
     */
    ErrCodes(int code) {
        this.code = code;
    }

    /**
     * 取业务码 —— 与 B 侧 {@code getCode()} 逐字一致（:82-84）。
     *
     * <p>该值会被写进响应信封的 {@code code} 字段、也会传给 logger 的 {@code bizCode} 参数
     * ⇒ <b>是 L1 契约数值，不可改</b>。
     *
     * @return 业务码
     */
    public int getCode() {
        return code;
    }
}
