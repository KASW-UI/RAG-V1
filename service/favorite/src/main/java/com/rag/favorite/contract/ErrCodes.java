package com.rag.favorite.contract;

/**
 * <p><b>favorite 域业务错误码常量集（域内私有 · L1 契约层）</b>。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-common/src/main/java/com/rag/favorite/favorite/common/errmsg/FavoriteErrorCode.java:37-119}
 * —— <b>枚举 24 个常量，码值逐字照抄</b>。
 *
 * <h3>⚠️ 为什么落域内 {@code contract/} 而不是共享层</h3>
 * <ol>
 *   <li><b>逐域隔离（AGENTS.md §八「未统一化」）</b>：错误码值 / 文案 / 语言<b>逐域隔离，
 *       禁止提取共享基座</b> ⇒ favorite 的 24 个码只归 favorite 域所有。</li>
 *   <li><b>码空间与文案都不同</b>：本域 {@code 5002} = 英文 {@code "not found"}，
 *       与 like 域（{@code 5002} = 「更新数据库失败」）、
 *       hot 域（{@code 5002} = {@code "record not found"}）<b>都不是同一套</b>
 *       ⇒ 共表即破坏隔离。</li>
 *   <li><b>落点已定</b>：{@code docs/File_Tree/favorite.md}（由 {@code tools/gen-file-tree.py}
 *       按实际文件系统生成）已把落点生成为 {@code com.rag.favorite.contract.ErrCodes}。</li>
 * </ol>
 *
 * <h3>⚠️ 与 B 侧「枚举」形态的表达层差异（L4 · 表达层自由，语义等价）</h3>
 * <ul>
 *   <li>B 侧是 {@code enum FavoriteErrorCode}（每个常量一个枚举项，附大段 javadoc）；
 *       C 侧改为 {@code final class + int 常量} ⇒ <b>码值集合、码值大小、码值含义逐条一致</b>
 *       （下方每个常量的 javadoc 逐条搬自 B 侧对应枚举项）。</li>
 *   <li><b>为什么不用枚举</b>：本仓的惯例是「域内 {@code contract/} 放码值 + 文案查表两个类」
 *       （like 域 {@code service/like/.../contract/ErrCodes.java} 先例）
 *       ⇒ 且 A9 断言只针对 {@code dto/} 包 ⇒ 不受影响。
 *       <b>枚举 vs 常量类的可观察差异：无</b>（两者都只是 int 的命名来源）。</li>
 * </ul>
 *
 * <h3>⚠️ 枚举顺序即契约（B 侧 javadoc 明写）</h3>
 * <p>B 侧把「枚举顺序」当成契约的一部分（{@code FavoriteErrorCodeContractTest#declarationOrderMatchesGoSourceOrder}
 * 逐条锁死）⇒ 下方常量<b>按 B 侧的声明顺序排列</b>，其中 {@link #CODE_SERVER_BUSY}(1015)
 * <b>排在 5001 之前</b> —— 因为 B 侧源码就把它写在通用错误码分组的开头。
 * <b>不要按数值大小重排</b>。
 *
 * <h3>⚠️ 共 24 个</h3>
 * <p>B 侧枚举实际 <b>24 个</b>（Go {@code errmsg.go:9-40} 逐行计数，
 * 由 {@code FavoriteErrorCodeContractTest#thereAreExactlyTwentyFourCodes} 按源码钉死）
 * ⇒ 本类按 24 个落地。
 *
 * @see com.rag.favorite.contract.ErrMsg 文案查询入口（未知码回落 {@code "FAIL"}）
 * @see com.rag.favorite.contract.GrpcErrorMapper gRPC 码 ⇄ 业务码双向转换
 */
public final class ErrCodes {

    // ============== 通用（B 侧 FavoriteErrorCode.java:39-43） ==============

    /** B 侧 {@code SUCCESS = 200}：成功响应的 {@code code}，也是 {@link GrpcErrorMapper#bizCodeFromError} 收到 {@code null} 时的返回值。 */
    public static final int SUCCESS = 200;

    /** B 侧 {@code ERROR = 500}：同时是 {@link ErrMsg#getMsg(int)} 未知码的兜底文案 {@code "FAIL"}。 */
    public static final int ERROR = 500;

    // ============== 通用错误码（B 侧 FavoriteErrorCode.java:45-47） ==============

    /**
     * B 侧 {@code CODE_SERVER_BUSY = 1015}。
     *
     * <p><b>排在 5001 之前</b>，因为 B 侧源码就写在通用错误码分组的开头。
     * 同时是本域「无法归类」时的默认兜底码（非 status 异常 / 未知标准 gRPC 码）。
     */
    public static final int CODE_SERVER_BUSY = 1015;

    // ============== 通用错误码 5001-5099（B 侧 FavoriteErrorCode.java:49-67） ==============

    /** B 侧 {@code ERROR_SERVER_COMMON = 5001}：favorite-api 的 logic 在下游 RPC 失败时返回它。 */
    public static final int ERROR_SERVER_COMMON = 5001;

    /** B 侧 {@code ERROR_NOT_FOUND = 5002}：🔴 文案是英文 {@code "not found"}，不是中文「记录不存在」。 */
    public static final int ERROR_NOT_FOUND = 5002;

    /** B 侧 {@code ERROR_INVALID_PARAM = 5003}：favorite 域最常返回的码：入参解析失败。 */
    public static final int ERROR_INVALID_PARAM = 5003;

    /** B 侧 {@code ERROR_UNAUTHORIZED = 5004}：文案含「或」字：{@code "未登录或登录已失效"}。 */
    public static final int ERROR_UNAUTHORIZED = 5004;

    /** B 侧 {@code ERROR_FORBIDDEN = 5005}：收藏归属校验失败时返回（他人收藏夹）。 */
    public static final int ERROR_FORBIDDEN = 5005;

    /** B 侧 {@code ERROR_ALREADY_EXIST = 5006}：收藏夹重名 / 收藏重复。 */
    public static final int ERROR_ALREADY_EXIST = 5006;

    /** B 侧 {@code ERROR_DB_SELECT = 5007}：读路径 DB 回源失败的唯一码。 */
    public static final int ERROR_DB_SELECT = 5007;

    /** B 侧 {@code ERROR_DB_UPDATE = 5008}：写路径 DB 失败的唯一码。 */
    public static final int ERROR_DB_UPDATE = 5008;

    /** B 侧 {@code ERROR_GENERATE_ID = 5009}：雪花 ID 生成失败。 */
    public static final int ERROR_GENERATE_ID = 5009;

    // ============== 用户模块 5100-5199（B 侧 FavoriteErrorCode.java:69-79） ==============

    /** B 侧 {@code ERROR_USER_NOT_FOUND = 5101}。 */
    public static final int ERROR_USER_NOT_FOUND = 5101;

    /** B 侧 {@code ERROR_USER_EXISTS = 5102}。 */
    public static final int ERROR_USER_EXISTS = 5102;

    /** B 侧 {@code ERROR_PASSWORD_INVALID = 5103}。 */
    public static final int ERROR_PASSWORD_INVALID = 5103;

    /** B 侧 {@code ERROR_TOKEN_INVALID = 5104}。 */
    public static final int ERROR_TOKEN_INVALID = 5104;

    /** B 侧 {@code ERROR_TOKEN_EXPIRED = 5105}。 */
    public static final int ERROR_TOKEN_EXPIRED = 5105;

    // ============== 收藏夹模块 5200-5299（B 侧 FavoriteErrorCode.java:81-87） ==============

    /** B 侧 {@code ERROR_FAVORITE_FOLDER_NOT_FOUND = 5201}。 */
    public static final int ERROR_FAVORITE_FOLDER_NOT_FOUND = 5201;

    /** B 侧 {@code ERROR_FAVORITE_FOLDER_NAME_EMPTY = 5202}。 */
    public static final int ERROR_FAVORITE_FOLDER_NAME_EMPTY = 5202;

    /** B 侧 {@code ERROR_FAVORITE_FOLDER_NAME_EXISTS = 5203}。 */
    public static final int ERROR_FAVORITE_FOLDER_NAME_EXISTS = 5203;

    // ============== 收藏模块 5300-5399（B 侧 FavoriteErrorCode.java:89-108） ==============

    /** B 侧 {@code ERROR_FAVORITE_NOT_FOUND = 5301}。 */
    public static final int ERROR_FAVORITE_NOT_FOUND = 5301;

    /** B 侧 {@code ERROR_FAVORITE_ALREADY_EXIST = 5302}。 */
    public static final int ERROR_FAVORITE_ALREADY_EXIST = 5302;

    /** B 侧 {@code ERROR_FAVORITE_TARGET_EMPTY = 5303}。 */
    public static final int ERROR_FAVORITE_TARGET_EMPTY = 5303;

    /**
     * B 侧 {@code ERROR_FAVORITE_HISTORY_BLOCKED = 5304}。
     *
     * <p><b>这个码决定了整个组件的形状</b>：它<b>不是</b>标准 gRPC 码，
     * B 侧靠 {@code GRPCError(codes.FailedPrecondition, 5304)} 发出，
     * wire 上只有 {@code grpc-status: FAILED_PRECONDITION} + description {@code "收藏暂不可撤回"}。
     * 而 {@code FAILED_PRECONDITION} <b>不在</b> B 侧 {@code BizCodeFromError} 的 switch 白名单里
     * ⇒ 还原 5304 <b>只能</b>靠 description 的文案反查。
     */
    public static final int ERROR_FAVORITE_HISTORY_BLOCKED = 5304;

    private ErrCodes() {
        // 工具类，禁止 new
    }
}
