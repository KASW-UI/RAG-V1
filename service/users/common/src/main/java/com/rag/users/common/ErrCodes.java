package com.rag.users.common;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * <p><b>users 域错误码<b>常量集</b></b> —— 逐条对应 B 侧 {@code ErrorCode} 枚举的 31 个 {@code code} 值。
 *
 * <h2>为什么拆成两个类（B 的 1 个 enum → C 的 2 个类）</h2>
 * <p>B 侧 {@code com.rag.users.common.errmsg.ErrorCode} 用<b>一个 enum 同时承载「码值」与「文案」</b>
 * （每个枚举常量带 {@code int code} + {@code String msg}）。C 侧按本仓的域内契约形态拆开：
 * <ul>
 *   <li>本类 {@link ErrCodes} = <b>码值集</b>（只放码值 + 文案查找所需的码空间）</li>
 *   <li>{@link ErrMsg} = <b>code → msg 映射</b>（含 {@code getMsg(int)} 的回落语义）</li>
 * </ul>
 * <p>依据：[`docs/深挖修正记录.md`](../../../../../../docs/深挖修正记录.md) §三「M-3 · 错误契约形态」
 * 的落地表 ——「常量 + {@code GetErrMsg}」形态 ⇒ {@code ErrCodes + ErrMsg}。
 *
 * <h2>⚠️ 硬约束：错误契约<b>逐域隔离</b>，不可跨域统一</h2>
 * <p>B 侧实测（8 个域各不相同）：
 * <pre>
 *   同一语义的码值在各域<b>不同</b>：
 *     ErrorDbUpdate   favorite=5008  comment/like/user=5002  article=5002
 *     ErrorDbSelect   favorite=5007  comment/like/user=5003
 *     「用户不存在」段   favorite/follow/message=5101+   comment/like/user=1001+
 *
 *   同一语义的<b>文案与语言</b>在各域<b>不同</b>：
 *     CodeServerBusy   article/comment/favorite/like/user = "服务繁忙"（中文）
 *                       follow/hot/message                 = "service busy"（英文）
 * </pre>
 * <p>⇒ <b>禁止</b>为了「统一」而把各域的错误码/文案合并成一个共享基座 —— 那会改掉 8 个域的可见行为（L1）。
 * <p>⇒ 本包的值必须<b>逐条照抄 B 侧</b> {@code ErrorCode} 的码值（含文案、含语言）。
 *
 * <h2>B 出处（本次迁移的行为基线）</h2>
 * <pre>
 *   B: RAG  service/users/users-common/src/main/java/com/rag/users/common/errmsg/ErrorCode.java:8-38
 *      —— 31 个枚举常量，逐个 {@code (code, msg)}；
 *         第 58-60 行 {@code getCode()}、第 69-71 行 {@code getMsg()}、
 *         第 88-90 行 {@code of(int)}（找不到 ⇒ 回落 {@code ERROR}）、第 93-95 行 {@code getMsg(int)}。
 * </pre>
 *
 * <h2>语义等价论证（对 B 逐条）</h2>
 * <ol>
 *   <li><b>码值集合逐条一致</b>：31 个常量与 B 的 31 个枚举常量一一对应，<b>码值与常量名</b>都逐字照抄
 *       ⇒ 调用点 {@code ErrCodes.ERROR_USER_NOT_EXIST} 与 B 的 {@code ErrorCode.ERROR_USER_NOT_EXIST.getCode()}
 *       取值相同。</li>
 *   <li><b>B 的 {@code getCode()} 在 C 侧不需要</b>：B 用 enum 是因为「一个枚举常量同时带码与文案」；
 *       C 把两者拆开后，码值就是常量本身 ⇒ <b>调用形态从 {@code X.getCode()} 变成 {@code X}</b>
 *       （<b>表达层差异</b>，取值逐条相同）。</li>
 *   <li><b>B 的 {@code of(int)} 与 {@code getMsg(int)} 的回落语义</b>（找不到 ⇒ {@code ERROR} ⇒ 文案 {@code "FAIL"}）
 *       ⇒ 落在 {@link ErrMsg#getMsg(int)}，<b>本类不重复实现</b>（避免两份回落逻辑分叉）。</li>
 *   <li><b>本类额外提供 {@link #codes()}</b>：B 的 {@code CODE_MAP} 由 {@code values()} 构建，
 *       C 无 enum ⇒ 需要一份「全部码值」的可枚举视图供 {@link ErrMsg} 构建映射与自检使用
 *       ⇒ <b>只增不改</b>，不改变任何调用点的取值。</li>
 * </ol>
 *
 * <p>⚠️ <b>文案不在本类</b> —— 文案是 L1 契约，但归属于 {@link ErrMsg}；本类只放码值，
 * 避免「同一个码在两个类里各有一份文案」的漂移面（AGENTS.md §八「未统一化 / L2 已归位」）。
 */
public final class ErrCodes {

    /** 成功（{@code ErrorCode.SUCCESS}）。 */
    public static final int SUCCESS = 200;

    /** 通用失败（{@code ErrorCode.ERROR}）—— 同时是 {@link ErrMsg#getMsg(int)} 未知码的回落文案来源。 */
    public static final int ERROR = 500;

    /** 服务繁忙（{@code ErrorCode.CODE_SERVER_BUSY}）。 */
    public static final int CODE_SERVER_BUSY = 1015;

    /** 系统内部错误（{@code ErrorCode.ERROR_SERVER_COMMON}）。 */
    public static final int ERROR_SERVER_COMMON = 5001;

    /** 更新数据库失败（{@code ErrorCode.ERROR_DB_UPDATE}）。 */
    public static final int ERROR_DB_UPDATE = 5002;

    /** 查询数据库失败（{@code ErrorCode.ERROR_DB_SELECT}）。 */
    public static final int ERROR_DB_SELECT = 5003;

    /** 数据库插入失败（{@code ErrorCode.ERROR_DB_INSERT}）。 */
    public static final int ERROR_DB_INSERT = 5004;

    /** 事务操作失败（{@code ErrorCode.ERROR_DB_TRANSACTION}）。 */
    public static final int ERROR_DB_TRANSACTION = 5005;

    /** 雪花 ID 生成失败（{@code ErrorCode.ERROR_SNOWFLAKE_ID}）。 */
    public static final int ERROR_SNOWFLAKE_ID = 5006;

    /** JSON 序列化失败（{@code ErrorCode.ERROR_JSON_MARSHAL}）。 */
    public static final int ERROR_JSON_MARSHAL = 5007;

    /** JSON 反序列化失败（{@code ErrorCode.ERROR_JSON_UNMARSHAL}）。 */
    public static final int ERROR_JSON_UNMARSHAL = 5008;

    /** Kafka 消息发送失败（{@code ErrorCode.ERROR_KAFKA_PUSH}）。 */
    public static final int ERROR_KAFKA_PUSH = 5009;

    /** 延时消息发送失败（{@code ErrorCode.ERROR_DELAY_MSG}）。 */
    public static final int ERROR_DELAY_MSG = 5010;

    /** 用户名已存在（{@code ErrorCode.ERROR_USER_EXIST}）。 */
    public static final int ERROR_USER_EXIST = 1001;

    /** 用户名或密码错误（{@code ErrorCode.ERROR_LOGIN_WRONG}）。 */
    public static final int ERROR_LOGIN_WRONG = 1002;

    /** 用户不存在（{@code ErrorCode.ERROR_USER_NOT_EXIST}）。 */
    public static final int ERROR_USER_NOT_EXIST = 1003;

    /** TOKEN 不存在（{@code ErrorCode.ERROR_TOKEN_NOT_EXIST}）。 */
    public static final int ERROR_TOKEN_NOT_EXIST = 1004;

    /** TOKEN 格式错误（{@code ErrorCode.ERROR_TOKEN_TYPE_WRONG}）。 */
    public static final int ERROR_TOKEN_TYPE_WRONG = 1005;

    /** TOKEN 已过期（{@code ErrorCode.ERROR_TOKEN_RUNTIME}）。 */
    public static final int ERROR_TOKEN_RUNTIME = 1006;

    /** TOKEN 刷新失败（{@code ErrorCode.ERROR_TOKEN_REFRESH}）。 */
    public static final int ERROR_TOKEN_REFRESH = 1007;

    /** 权限不足（{@code ErrorCode.ERROR_USER_NO_RIGHT}）。 */
    public static final int ERROR_USER_NO_RIGHT = 1008;

    /** 未登录（{@code ErrorCode.ERROR_USER_NO_LOGIN}）。 */
    public static final int ERROR_USER_NO_LOGIN = 1009;

    /** 已登录（{@code ErrorCode.ERROR_USER_LOGINED}）。 */
    public static final int ERROR_USER_LOGINED = 1010;

    /** 用户已被封禁（{@code ErrorCode.ERROR_USER_BANNED}）。 */
    public static final int ERROR_USER_BANNED = 1011;

    /** Redis 更新失败（{@code ErrorCode.ERROR_REDIS_UPDATE}）。 */
    public static final int ERROR_REDIS_UPDATE = 1012;

    /** 管理员邀请码错误（{@code ErrorCode.ERROR_ADMIN_INVITE_CODE_WRONG}）。 */
    public static final int ERROR_ADMIN_INVITE_CODE_WRONG = 1016;

    /** 请求参数错误（{@code ErrorCode.ERROR_REQUEST_PARAM}）。 */
    public static final int ERROR_REQUEST_PARAM = 1017;

    /** 头像历史不存在（{@code ErrorCode.ERROR_AVATAR_HISTORY_NOT_EXIST}）。 */
    public static final int ERROR_AVATAR_HISTORY_NOT_EXIST = 1018;

    /** 积分余量不足（{@code ErrorCode.ERROR_POINTS_INSUFFICIENT}）。 */
    public static final int ERROR_POINTS_INSUFFICIENT = 3001;

    /** 重试次数超限（{@code ErrorCode.ERROR_POINTS_RETRY_EXCEEDED}）。 */
    public static final int ERROR_POINTS_RETRY_EXCEEDED = 3002;

    /** 积分处理超时（{@code ErrorCode.ERROR_POINTS_TIMEOUT}）。 */
    public static final int ERROR_POINTS_TIMEOUT = 3003;

    /**
     * 全部码值（不可变视图，迭代顺序 = 声明顺序 = B 侧 {@code values()} 顺序）。
     *
     * <p>B 出处：{@code ErrorCode.java:76-85} 的 {@code CODE_MAP} 由 {@code values()} 构建 ⇒
     * 「全部码值」是 B 的既有可枚举面；C 侧无 enum ⇒ 以本视图提供等价能力（<b>只增不改</b>）。
     */
    private static final Set<Integer> CODES = Collections.unmodifiableSet(new LinkedHashSet<>(java.util.List.of(
            SUCCESS, ERROR,
            CODE_SERVER_BUSY,
            ERROR_SERVER_COMMON, ERROR_DB_UPDATE, ERROR_DB_SELECT, ERROR_DB_INSERT, ERROR_DB_TRANSACTION,
            ERROR_SNOWFLAKE_ID, ERROR_JSON_MARSHAL, ERROR_JSON_UNMARSHAL, ERROR_KAFKA_PUSH, ERROR_DELAY_MSG,
            ERROR_USER_EXIST, ERROR_LOGIN_WRONG, ERROR_USER_NOT_EXIST,
            ERROR_TOKEN_NOT_EXIST, ERROR_TOKEN_TYPE_WRONG, ERROR_TOKEN_RUNTIME, ERROR_TOKEN_REFRESH,
            ERROR_USER_NO_RIGHT, ERROR_USER_NO_LOGIN, ERROR_USER_LOGINED, ERROR_USER_BANNED,
            ERROR_REDIS_UPDATE, ERROR_ADMIN_INVITE_CODE_WRONG,
            ERROR_REQUEST_PARAM, ERROR_AVATAR_HISTORY_NOT_EXIST,
            ERROR_POINTS_INSUFFICIENT, ERROR_POINTS_RETRY_EXCEEDED, ERROR_POINTS_TIMEOUT
    )));

    private ErrCodes() {
    }

    /**
     * 返回全部码值的只读视图。
     *
     * @return 不可变 {@link Set}，迭代顺序与 B 侧 {@code ErrorCode.values()} 一致
     */
    public static Set<Integer> codes() {
        return CODES;
    }
}
