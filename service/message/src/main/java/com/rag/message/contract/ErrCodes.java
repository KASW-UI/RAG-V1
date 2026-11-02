package com.rag.message.contract;

/**
 * <p><b>message 域业务错误码枚举</b> —— <b>20 个码值的唯一真源</b>（L1 契约 · D16：B 侧 RAG 仓为唯一判定基准）。
 *
 * <p><b>B 出处</b>：{@code service/message/message-common/src/main/java/com/rag/message/message/common/errmsg/MessageErrorCode.java}
 * <pre>{@code
 *   枚举声明 + 20 个常量（常量名 / 码值 / 声明顺序逐条照抄）   :25-90
 *     SUCCESS(200)                      :28    ERROR(500)                       :31
 *     CODE_SERVER_BUSY(1015)            :35    ERROR_SERVER_COMMON(5001)        :38
 *     ERROR_NOT_FOUND(5002)             :41    ERROR_INVALID_PARAM(5003)        :45
 *     ERROR_UNAUTHORIZED(5004)          :49    ERROR_FORBIDDEN(5005)            :53
 *     ERROR_ALREADY_EXIST(5006)         :57    ERROR_DB_SELECT(5007)            :60
 *     ERROR_DB_UPDATE(5008)             :63    ERROR_USER_NOT_FOUND(5101)       :66
 *     ERROR_ADMIN_NOT_FOUND(5102)       :69    ERROR_SELF_CHAT(5201)            :72
 *     ERROR_CONVERSATION_MISS(5202)     :75    ERROR_NOTIFICATION_MISS(5203)    :78
 *     ERROR_CHAT_PENDING(5204)          :81    ERROR_ADMIN_ONLY_INIT(5205)      :84
 *     ERROR_FIRST_MSG_TOO_LONG(5206)    :87    ERROR_UNSUPPORTED_ROLE(5207)     :90
 *   码值字段 private final int code      :92
 *   构造器（码值落字段）                   :94-96
 *   getCode()                           :103-105
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅两处<b>纯命名 / 定位</b>改动 ——
 *       ① 类型名 {@code MessageErrorCode} → {@code ErrCodes}（本仓域内契约包的既定类名；
 *       形态依据见 {@code docs/深挖修正记录.md} §三 M-3 落地表「message ⇒
 *       {@code CodeError + ErrCodes + ErrMsg + GrpcErrorMapper}」）；
 *       ② 包名 {@code com.rag.message.message.common.errmsg} → {@code com.rag.message.contract}
 *       （D7 包压平 · AGENTS.md §2.1：域内错误契约落 {@code contract/} 包）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 枚举常量名、码值、声明顺序、{@code getCode()} 的返回口径逐条未动
 *       ⇒ 调用点 {@code ErrCodes.ERROR_SELF_CHAT.getCode()} 取值 == B 侧
 *       {@code MessageErrorCode.ERROR_SELF_CHAT.getCode()}；
 *       B 侧靠 enum 天然提供的 {@code values()} 可枚举面在 C 侧同样由 enum 提供（无合成差异）。</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>⚠️ 逐域隔离纪律（AGENTS.md §八「未统一化」）</b>：错误契约<b>逐域隔离</b>，禁止跨域统一 ——
 * 同语义跨域<b>码值不同</b>是既有事实（本域 {@code ERROR_DB_UPDATE} = 5008，见上表 :63；
 * users 域同名语义 = 5002，见 {@code service/users/common/src/main/java/com/rag/users/common/ErrCodes.java:76}）
 * ⇒ 本枚举 20 个码值<b>一个字都不能改</b>（改码值 = 改客户端可见行为 = L1 变更 ⇒ 走决策流程）。
 *
 * <p><b>⚠️ 文案不在本类</b>：文案（{@code msg}）同属 L1 契约，但归 {@link ErrMsg} ——
 * 本类只放码值，避免「同一个码在两处各有一份」的漂移面（AGENTS.md §八「L2 已归位」）。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：B 侧 {@code MessageErrorCode} 是手写 enum（码值字段 + 手写
 * {@code getCode()}），本类为<b>逐字照抄</b>；Lombok 对 enum 常量与手写 getter 无可消除的样板
 * （{@code @Getter} 也不能用在 enum 上产出常量访问器）⇒ 本层不引入 Lombok（A13 判据不适用）。
 */
public enum ErrCodes {

    /** RPC 成功回执的业务码（B 侧 {@code MessageErrorCode.SUCCESS}，:28）。 */
    SUCCESS(200),

    /** 通用失败回执（不作为细粒度错误码使用）；同时是 {@link ErrMsg#getMsg(int)} 未知码的回落文案来源
     * （B 侧 {@code MessageErrorCode.ERROR}，:31）。 */
    ERROR(500),

    /** 服务繁忙 / 兜底码 —— 错误无法识别为业务码或 gRPC status 时的最终回落
     * （B 侧 {@code MessageErrorCode.CODE_SERVER_BUSY}，:35；消费点见
     * {@code GrpcErrorMapper.bizCodeFromError} 的兜底分支）。 */
    CODE_SERVER_BUSY(1015),

    /** 系统内部错误 —— gRPC code {@code INTERNAL} 的默认映射目标
     * （B 侧 {@code MessageErrorCode.ERROR_SERVER_COMMON}，:38）。 */
    ERROR_SERVER_COMMON(5001),

    /** 记录不存在 —— gRPC code {@code NOT_FOUND} 的默认映射目标
     * （B 侧 {@code MessageErrorCode.ERROR_NOT_FOUND}，:41）。 */
    ERROR_NOT_FOUND(5002),

    /** 参数错误 —— gRPC code {@code INVALID_ARGUMENT} 的默认映射目标
     * （B 侧 {@code MessageErrorCode.ERROR_INVALID_PARAM}，:45）。 */
    ERROR_INVALID_PARAM(5003),

    /** 未鉴权 —— gRPC code {@code UNAUTHENTICATED} 的默认映射目标
     * （B 侧 {@code MessageErrorCode.ERROR_UNAUTHORIZED}，:49）。 */
    ERROR_UNAUTHORIZED(5004),

    /** 无权限 —— gRPC code {@code PERMISSION_DENIED} 的默认映射目标
     * （B 侧 {@code MessageErrorCode.ERROR_FORBIDDEN}，:53）。 */
    ERROR_FORBIDDEN(5005),

    /** 记录已存在 —— gRPC code {@code ALREADY_EXISTS} 的默认映射目标
     * （B 侧 {@code MessageErrorCode.ERROR_ALREADY_EXIST}，:57）。 */
    ERROR_ALREADY_EXIST(5006),

    /** 数据库查询失败（B 侧 {@code MessageErrorCode.ERROR_DB_SELECT}，:60）。 */
    ERROR_DB_SELECT(5007),

    /** 数据库更新失败（B 侧 {@code MessageErrorCode.ERROR_DB_UPDATE}，:63）。 */
    ERROR_DB_UPDATE(5008),

    /** 用户不存在（B 侧 {@code MessageErrorCode.ERROR_USER_NOT_FOUND}，:66）。 */
    ERROR_USER_NOT_FOUND(5101),

    /** 管理员不存在（B 侧 {@code MessageErrorCode.ERROR_ADMIN_NOT_FOUND}，:69）。 */
    ERROR_ADMIN_NOT_FOUND(5102),

    /** 不允许和自己发起会话（B 侧 {@code MessageErrorCode.ERROR_SELF_CHAT}，:72）。 */
    ERROR_SELF_CHAT(5201),

    /** 会话不存在（B 侧 {@code MessageErrorCode.ERROR_CONVERSATION_MISS}，:75）。 */
    ERROR_CONVERSATION_MISS(5202),

    /** 通知不存在（B 侧 {@code MessageErrorCode.ERROR_NOTIFICATION_MISS}，:78）。 */
    ERROR_NOTIFICATION_MISS(5203),

    /** 会话处于 PENDING，等待对方回复（B 侧 {@code MessageErrorCode.ERROR_CHAT_PENDING}，:81）。 */
    ERROR_CHAT_PENDING(5204),

    /** 只有管理员能初始化该会话（B 侧 {@code MessageErrorCode.ERROR_ADMIN_ONLY_INIT}，:84）。 */
    ERROR_ADMIN_ONLY_INIT(5205),

    /** 首条私聊消息不能超过 50 字符（B 侧 {@code MessageErrorCode.ERROR_FIRST_MSG_TOO_LONG}，:87）。 */
    ERROR_FIRST_MSG_TOO_LONG(5206),

    /** 不支持的发送者角色（B 侧 {@code MessageErrorCode.ERROR_UNSUPPORTED_ROLE}，:90）。 */
    ERROR_UNSUPPORTED_ROLE(5207);

    /** 码值 —— B 侧同名私有字段（:92）。 */
    private final int code;

    /**
     * 构造器 —— 与 B 侧逐字一致（:94-96）。
     *
     * @param code 业务码
     */
    ErrCodes(int code) {
        this.code = code;
    }

    /**
     * 取业务码 —— 与 B 侧 {@code getCode()} 逐字一致（:103-105）。
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
