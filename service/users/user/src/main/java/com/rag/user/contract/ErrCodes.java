package com.rag.user.contract;

/**
 * <p><b>user 子域错误码常量集（域内私有视图）</b>。
 *
 * <h2>为什么本类是「视图」而不是「另一份码表」</h2>
 * <p><b>B 侧真源</b>：{@code service/users/users-common/src/main/java/com/rag/users/common/errmsg/ErrorCode.java}
 * —— <b>user-api / user-rpc / admin-api / admin-rpc 四方共用同一份</b>（31 个枚举常量，码值与文案同体）。
 *
 * <p><b>C 侧落点</b>：码值与文案的<b>唯一真源</b>在共享层
 * {@code com.rag.users.common.ErrCodes} ＋ {@code com.rag.users.common.ErrMsg}
 * （由 {@code users-common} lane 于 2026-11-02 落地，31 条逐条对拍过 B 侧）。
 *
 * <p>⚠️ <b>本类刻意只做「别名转发」，不复制任何码值/文案</b>：
 * <ul>
 *   <li><b>若在这里再抄一份数字</b> ⇒ 就多了一个可能与共享层漂移的副本 ⇒ 而
 *       {@code docs/L3-降级清单.md} 与 {@code docs/DRIFT/_INDEX.md} 都强调「同一码只存一处」；
 *       共享层那份的 {@code ErrContractParityTest} 也就<b>测不到</b>本类的副本 ⇒ 一旦漂移是静默的。</li>
 *   <li><b>转发</b> ⇒ 本类的常量值<b>永远等于</b>共享层的值 ⇒ 不存在副本 ⇒ 漂移面为零。</li>
 * </ul>
 *
 * <p><b>为什么还要保留这个「视图」类</b>（而不是全仓直接 import 共享层）：
 * ① <b>域内归属</b> —— 本仓的约定是「错误契约落域内 {@code contract/} 包」
 * （见 {@code docs/File_Tree/users/user.md} 与 {@code docs/Mapping_Table/users/users-common.md} §4）；
 * ② <b>只暴露本子域实际用到的码</b> ⇒ 读本类的人一眼能看出 user 子域会返回哪些业务码；
 * ③ <b>给 admin 子域一个可抄的先例</b>（{@code docs/Mapping_Table/users/_shared.md} F-2 至今未决）
 * ⇒ <b>答案就是「照本类的样子做转发，不要复制码表」</b>。
 *
 * <p><b>⚠️ 逐域隔离纪律（AGENTS.md §八）</b>：本域的错误码<b>不得</b>与其他域统一
 * （favorite 域的「查库失败」是 5007、user 域是 5003 ⇒ 同语义不同码值是<b>既有事实</b>）。
 *
 * @see com.rag.users.common.ErrCodes 真源（31 条）
 * @see com.rag.user.contract.ErrMsg 文案查询入口
 */
public final class ErrCodes {

    // ============== 本子域实际使用到的码（值全部转发自 com.rag.users.common.ErrCodes） ==============

    /** 成功（B：{@code ErrorCode.SUCCESS} = 200）。 */
    public static final int SUCCESS = com.rag.users.common.ErrCodes.SUCCESS;

    /** 通用失败（B：{@code ErrorCode.ERROR} = 500）—— 也是未知码的回落文案来源。 */
    public static final int ERROR = com.rag.users.common.ErrCodes.ERROR;

    /** 服务繁忙（B：{@code ErrorCode.CODE_SERVER_BUSY} = 1015）。 */
    public static final int CODE_SERVER_BUSY = com.rag.users.common.ErrCodes.CODE_SERVER_BUSY;

    /** 系统内部错误（B：{@code ErrorCode.ERROR_SERVER_COMMON} = 5001）。 */
    public static final int ERROR_SERVER_COMMON = com.rag.users.common.ErrCodes.ERROR_SERVER_COMMON;

    /** 更新数据库失败（B：{@code ErrorCode.ERROR_DB_UPDATE} = 5002）。 */
    public static final int ERROR_DB_UPDATE = com.rag.users.common.ErrCodes.ERROR_DB_UPDATE;

    /** 查询数据库失败（B：{@code ErrorCode.ERROR_DB_SELECT} = 5003）。 */
    public static final int ERROR_DB_SELECT = com.rag.users.common.ErrCodes.ERROR_DB_SELECT;

    /** 数据库插入失败（B：{@code ErrorCode.ERROR_DB_INSERT} = 5004）。 */
    public static final int ERROR_DB_INSERT = com.rag.users.common.ErrCodes.ERROR_DB_INSERT;

    /** 雪花 ID 生成失败（B：{@code ErrorCode.ERROR_SNOWFLAKE_ID} = 5006）。 */
    public static final int ERROR_SNOWFLAKE_ID = com.rag.users.common.ErrCodes.ERROR_SNOWFLAKE_ID;

    /** 用户名已存在（B：{@code ErrorCode.ERROR_USER_EXIST} = 1001）。 */
    public static final int ERROR_USER_EXIST = com.rag.users.common.ErrCodes.ERROR_USER_EXIST;

    /** 用户名或密码错误（B：{@code ErrorCode.ERROR_LOGIN_WRONG} = 1002）。 */
    public static final int ERROR_LOGIN_WRONG = com.rag.users.common.ErrCodes.ERROR_LOGIN_WRONG;

    /** 用户不存在（B：{@code ErrorCode.ERROR_USER_NOT_EXIST} = 1003）。 */
    public static final int ERROR_USER_NOT_EXIST = com.rag.users.common.ErrCodes.ERROR_USER_NOT_EXIST;

    /** TOKEN 不存在（B：{@code ErrorCode.ERROR_TOKEN_NOT_EXIST} = 1004）。 */
    public static final int ERROR_TOKEN_NOT_EXIST = com.rag.users.common.ErrCodes.ERROR_TOKEN_NOT_EXIST;

    /** TOKEN 已过期 / 运行时错误（B：{@code ErrorCode.ERROR_TOKEN_RUNTIME} = 1006）。 */
    public static final int ERROR_TOKEN_RUNTIME = com.rag.users.common.ErrCodes.ERROR_TOKEN_RUNTIME;

    /** 用户已被封禁（B：{@code ErrorCode.ERROR_USER_BANNED} = 1011）。 */
    public static final int ERROR_USER_BANNED = com.rag.users.common.ErrCodes.ERROR_USER_BANNED;

    /** 请求参数错误（B：{@code ErrorCode.ERROR_REQUEST_PARAM} = 1017）。 */
    public static final int ERROR_REQUEST_PARAM = com.rag.users.common.ErrCodes.ERROR_REQUEST_PARAM;

    /** 头像历史不存在（B：{@code ErrorCode.ERROR_AVATAR_HISTORY_NOT_EXIST} = 1018）。 */
    public static final int ERROR_AVATAR_HISTORY_NOT_EXIST = com.rag.users.common.ErrCodes.ERROR_AVATAR_HISTORY_NOT_EXIST;

    private ErrCodes() {
    }
}
