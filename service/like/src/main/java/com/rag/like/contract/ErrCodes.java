package com.rag.like.contract;

/**
 * <p><b>like 域业务错误码常量集（域内私有 · L1 契约层）</b>。
 *
 * <p><b>B 出处</b>：{@code service/like/like-common/src/main/java/com/rag/like/like/common/errmsg/LikeErrorCode.java:38-67}
 * —— <b>枚举 29 个常量，码值逐字照抄</b>。
 *
 * <h3>⚠️ 为什么落域内 {@code contract/} 而不是共享层</h3>
 * <ol>
 *   <li><b>逐域隔离（AGENTS.md §八「未统一化」）</b>：错误码值 / 文案 / 语言<b>逐域隔离，
 *       禁止提取共享基座</b> ⇒ like 的 29 个码只归 like 域所有。</li>
 *   <li><b>码空间不同</b>：like 域用 {@code 2001 / 5001-5012 / 1001-1011 / 3001}，
 *       与 follow（{@code 5xxx / 51xx / 52xx}）、message（英文文案）<b>都不是同一套</b>
 *       ⇒ 共表即破坏隔离。</li>
 *   <li><b>落点已定</b>：{@code docs/File_Tree/like.md}（由 {@code tools/gen-file-tree.py}
 *       按实际文件系统生成）已把落点生成为 {@code com.rag.like.contract.ErrCodes}。</li>
 * </ol>
 *
 * <h3>⚠️ 与 B 侧「枚举」形态的表达层差异（L4 · 表达层自由，语义等价）</h3>
 * <ul>
 *   <li>B 侧是 {@code enum LikeErrorCode}（每个常量一个枚举项，附大段 javadoc）；
 *       C 侧改为 {@code final class + int 常量} ⇒ <b>码值集合、码值大小、码值含义逐条一致</b>
 *       （下方每个常量的 javadoc 逐条搬自 B 侧对应枚举项）。</li>
 *   <li><b>为什么不用枚举</b>：本仓的惯例是「域内 {@code contract/} 放码值 + 文案查表两个类」
 *       （follow 域 {@code service/follow/.../contract/ErrCodes.java} 先例）
 *       ⇒ 且 A9 断言只针对 {@code dto/} 包 ⇒ 不受影响。
 *       <b>枚举 vs 常量类的可观察差异：无</b>（两者都只是 int 的命名来源）。</li>
 * </ul>
 *
 * <h3>⚠️ 枚举顺序即契约（B 侧 javadoc 明写）</h3>
 * <p>B 侧把「枚举顺序」当成契约的一部分（{@code LikeErrorContractTest#everyCodeMatchesGoOneByOne}
 * 逐条锁死）⇒ 下方常量<b>按 B 侧的声明顺序排列</b>，其中 {@link #CODE_SERVER_BUSY}(1015)
 * <b>排在 5001 之前</b> —— 因为 B 侧源码就把它写在通用错误码分组的开头。
 * <b>不要按数值大小重排</b>。
 *
 * <h3>⚠️ 共 29 个（不是 30）</h3>
 * <p>B 侧设计文档与骨架注释写的「30 个」是笔误，B 侧源码枚举实际 <b>29 个</b>
 * （{@code LikeErrorContractTest#thereAreTwentyNineCodes} 已按源码钉死）⇒ 本类按 29 个落地。
 *
 * @see com.rag.like.contract.ErrMsg 文案查询入口（未知码回落 {@code "FAIL"}）
 * @see com.rag.like.contract.GrpcErrFactory gRPC 码 ⇄ 业务码双向转换
 */
public final class ErrCodes {

    // ============== 通用（B 侧 LikeErrorCode.java:38-40） ==============

    /** B 侧 {@code SUCCESS = 200}：成功响应的 {@code code}，也是 {@link GrpcErrFactory#bizCodeFromError} 收到 {@code null} 时的返回值。 */
    public static final int SUCCESS = 200;

    /** B 侧 {@code ERROR = 500}：同时是 {@link ErrMsg#getMsg(int)} 未知码的兜底文案 {@code "FAIL"}。 */
    public static final int ERROR = 500;

    /**
     * B 侧 {@code CodeServerBusy = 1015}：也是本域 <b>RPC 侧无法归类时的默认兜底码</b>
     * （B 侧 {@code LikeGrpcError#bizCodeFromError} 的非 status / 未知标准码分支）。
     */
    public static final int CODE_SERVER_BUSY = 1015;

    // ============== 系统与基础设施（B 侧 LikeErrorCode.java:41-51） ==============

    /** B 侧 {@code ErrorServerCommon = 5001}：<b>本域最常用的兜底码</b>（HTTP 解析失败 / 非 status 异常都落它）。 */
    public static final int ERROR_SERVER_COMMON = 5001;

    /** B 侧 {@code ErrorDbUpdate = 5002}。 */
    public static final int ERROR_DB_UPDATE = 5002;

    /** B 侧 {@code ErrorDbSelect = 5003}：like 域<b>读路径</b> DB 回源失败的唯一码。 */
    public static final int ERROR_DB_SELECT = 5003;

    /** B 侧 {@code ErrorDbInsert = 5004}。 */
    public static final int ERROR_DB_INSERT = 5004;

    /** B 侧 {@code ErrorDbTransaction = 5005}。 */
    public static final int ERROR_DB_TRANSACTION = 5005;

    /** B 侧 {@code ErrorSnowflakeID = 5006}。 */
    public static final int ERROR_SNOWFLAKE_ID = 5006;

    /** B 侧 {@code ErrorJsonMarshal = 5007}。 */
    public static final int ERROR_JSON_MARSHAL = 5007;

    /** B 侧 {@code ErrorJsonUnmarshal = 5008}。 */
    public static final int ERROR_JSON_UNMARSHAL = 5008;

    /** B 侧 {@code ErrorKafkaPush = 5009}。 */
    public static final int ERROR_KAFKA_PUSH = 5009;

    /** B 侧 {@code ErrorDelayMsg = 5010}。 */
    public static final int ERROR_DELAY_MSG = 5010;

    /** B 侧 {@code ErrorRedisSelect = 5011}。 */
    public static final int ERROR_REDIS_SELECT = 5011;

    /** B 侧 {@code ErrorRedisUpdate = 5012}：<b>写路径</b> Redis 失败（Lua 执行失败）的唯一码。 */
    public static final int ERROR_REDIS_UPDATE = 5012;

    // ============== 用户 / 令牌（B 侧 LikeErrorCode.java:53-62） ==============

    /** B 侧 {@code ErrorUserExist = 1001}。 */
    public static final int ERROR_USER_EXIST = 1001;

    /** B 侧 {@code ErrorLoginWrong = 1002}。 */
    public static final int ERROR_LOGIN_WRONG = 1002;

    /** B 侧 {@code ErrorUserNotExist = 1003}。 */
    public static final int ERROR_USER_NOT_EXIST = 1003;

    /** B 侧 {@code ErrorTokenNotExist = 1004}。 */
    public static final int ERROR_TOKEN_NOT_EXIST = 1004;

    /** B 侧 {@code ErrorTokenTypeWrong = 1005}。 */
    public static final int ERROR_TOKEN_TYPE_WRONG = 1005;

    /**
     * B 侧 {@code ErrorTokenRuntime = 1006}：<b>JWT claim 解析失败的唯一码</b>
     * （claim 缺席 / 类型不对 / 数值不可解析都落它）⇒ 输出 <b>HTTP 200 + {code:1006}</b>。
     */
    public static final int ERROR_TOKEN_RUNTIME = 1006;

    /** B 侧 {@code ErrorTokenRefresh = 1007}。 */
    public static final int ERROR_TOKEN_REFRESH = 1007;

    /** B 侧 {@code ErrorUserNoRight = 1008}。 */
    public static final int ERROR_USER_NO_RIGHT = 1008;

    /** B 侧 {@code ErrorUserNoLogin = 1009}。 */
    public static final int ERROR_USER_NO_LOGIN = 1009;

    /** B 侧 {@code ErrorUserLogined = 1010}。 */
    public static final int ERROR_USER_LOGINED = 1010;

    /** B 侧 {@code ErrorUserBanned = 1011}。 */
    public static final int ERROR_USER_BANNED = 1011;

    // ============== 输入与类型（B 侧 LikeErrorCode.java:63-64） ==============

    /**
     * B 侧 {@code ErrorInputWrong = 2001}：<b>like 域最常返回的码</b>（参数校验失败 / 空 ID 列表 / 非法 uid）。
     */
    public static final int ERROR_INPUT_WRONG = 2001;

    /** B 侧 {@code ErrorTypeTransfer = 2002}。 */
    public static final int ERROR_TYPE_TRANSFER = 2002;

    // ============== Outbox（B 侧 LikeErrorCode.java:65） ==============

    /** B 侧 {@code ErrorBuildOutbox = 3001}。 */
    public static final int ERROR_BUILD_OUTBOX = 3001;

    private ErrCodes() {
        // 常量类，禁止实例化
    }
}
