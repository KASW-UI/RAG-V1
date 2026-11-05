package com.rag.comment.contract;

/**
 * <p><b>comment 域业务错误码常量集（域内私有 · L1 契约层）</b>。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-common/src/main/java/com/rag/comment/comment/common/errmsg/CommentErrorCode.java:29-133}
 * —— <b>枚举 27 个常量，码值逐字照抄</b>。
 *
 * <h3>⚠️ 为什么落域内 {@code contract/} 而不是共享层</h3>
 * <ol>
 *   <li><b>逐域隔离（AGENTS.md §八「未统一化」）</b>：错误码值 / 文案 / 语言<b>逐域隔离，
 *       禁止提取共享基座</b> ⇒ comment 的 27 个码只归 comment 域所有。</li>
 *   <li><b>码空间不同</b>：comment 域用 {@code 2001 / 5001-5010 / 1001-1013}，
 *       与 like（多 {@code 3001} / Redis 段）、favorite（{@code 51xx / 52xx}）<b>都不是同一套</b>
 *       ⇒ 共表即破坏隔离。B 侧原注释明写：<b>禁止 REUSE 任何既有 ErrorCode</b>
 *       （与 hot 域的 5002/5003/5004 语义完全不同）。</li>
 *   <li><b>落点已定</b>：{@code docs/File_Tree/comment.md}（由 {@code tools/gen-file-tree.py}
 *       按实际文件系统生成）已把落点生成为 {@code com.rag.comment.contract.ErrCodes}。</li>
 * </ol>
 *
 * <h3>⚠️ 与 B 侧「枚举」形态的表达层差异（L4 · 表达层自由，语义等价）</h3>
 * <ul>
 *   <li>B 侧是 {@code enum CommentErrorCode}（每个常量一个枚举项，附大段 javadoc）；
 *       C 侧改为 {@code final class + int 常量} ⇒ <b>码值集合、码值大小、码值含义逐条一致</b>
 *       （下方每个常量的 javadoc 逐条搬自 B 侧对应枚举项）。</li>
 *   <li><b>为什么不用枚举</b>：本仓的惯例是「域内 {@code contract/} 放码值 + 文案查表两个类」
 *       （like 域 {@code service/like/.../contract/ErrCodes.java} 先例）
 *       ⇒ 且 A9 断言只针对 {@code dto/} 包 ⇒ 不受影响。
 *       <b>枚举 vs 常量类的可观察差异：无</b>（两者都只是 int 的命名来源）。</li>
 * </ul>
 *
 * <h3>⚠️ 枚举顺序即契约（B 侧 javadoc 明写）</h3>
 * <p>B 侧把「枚举顺序」当成契约的一部分（{@code CommentErrorCodeContractTest#everyCodeMatchesGoOneByOne}
 * 逐条锁死）⇒ 下方常量<b>按 B 侧的声明顺序排列</b>，其中 {@link #CODE_SERVER_BUSY}(1015)
 * <b>排在 5001 之前</b> —— 因为 B 侧源码就把它写在通用错误码分组的开头。
 * <b>不要按数值大小重排</b>。
 *
 * <h3>⚠️ 共 27 个（不是 28 / 29）</h3>
 * <p>B 侧设计文档与骨架注释写的「28 / 29 个」一律以 B 侧源码为准
 * （{@code CommentErrorCodeContractTest#thereAreTwentySevenCodes} 已按源码钉死）⇒ 本类按 27 个落地。
 *
 * @see com.rag.comment.contract.ErrMsg 文案查询入口（未知码回落 {@code "FAIL"}）
 * @see com.rag.comment.contract.GrpcErrFactory gRPC 码 ⇄ 业务码双向转换
 */
public final class ErrCodes {

    // ============== 通用（B 侧 CommentErrorCode.java:38-40） ==============

    /** B 侧 {@code Success = 200}：成功响应的 {@code code}，也是 {@link GrpcErrFactory#bizCodeFromError} 收到 {@code null} 时的返回值。 */
    public static final int SUCCESS = 200;

    /** B 侧 {@code Error = 500}：同时是 {@link ErrMsg#getMsg(int)} 未知码的兜底文案 {@code "FAIL"}。 */
    public static final int ERROR = 500;

    /**
     * B 侧 {@code CodeServerBusy = 1015}：也是本域 <b>RPC 侧无法归类时的默认兜底码</b>
     * （B 侧 {@code CommentGrpcError#bizCodeFromError} 的非 status 分支）。
     */
    public static final int CODE_SERVER_BUSY = 1015;

    // ============== 系统与基础设施（B 侧 CommentErrorCode.java:41-50） ==============

    /**
     * B 侧 {@code ErrorServerCommon = 5001}：comment-api 全部用例的<b>兜底码</b>
     * （B 侧 {@code createcommentlogic.go:56-58} 等 6 处在非 status 错误时走这里）。
     */
    public static final int ERROR_SERVER_COMMON = 5001;

    /** B 侧 {@code ErrorDbUpdate = 5002}：comment-rpc <b>写路径</b> DB 失败（管理 / 点赞 / 删除 / 改主体状态）。 */
    public static final int ERROR_DB_UPDATE = 5002;

    /** B 侧 {@code ErrorDbSelect = 5003}：comment-rpc <b>读路径</b> DB 回源失败。 */
    public static final int ERROR_DB_SELECT = 5003;

    /** B 侧 {@code ErrorDbInsert = 5004}：举报落库失败（B 侧 {@code report_comment_logic.go:91}）。 */
    public static final int ERROR_DB_INSERT = 5004;

    /** B 侧 {@code ErrorDbTransaction = 5005}。 */
    public static final int ERROR_DB_TRANSACTION = 5005;

    /** B 侧 {@code ErrorSnowflakeID = 5006}：建评论时雪花算法生成 ID 失败（B 侧 {@code create_comment_logic.go:98}）。 */
    public static final int ERROR_SNOWFLAKE_ID = 5006;

    /** B 侧 {@code ErrorJsonMarshal = 5007}：事件载荷序列化失败（B 侧 {@code create_comment_logic.go:122}）。 */
    public static final int ERROR_JSON_MARSHAL = 5007;

    /** B 侧 {@code ErrorJsonUnmarshal = 5008}。 */
    public static final int ERROR_JSON_UNMARSHAL = 5008;

    /** B 侧 {@code ErrorKafkaPush = 5009}：事实投递事件入 Kafka 失败（B 侧 {@code create_comment_logic.go:130}）。 */
    public static final int ERROR_KAFKA_PUSH = 5009;

    /** B 侧 {@code ErrorDelayMsg = 5010}。 */
    public static final int ERROR_DELAY_MSG = 5010;

    // ============== 用户 / 令牌（B 侧 CommentErrorCode.java:51-61） ==============

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
     * B 侧 {@code ErrorTokenRuntime = 1006}：comment-api 的 JWT 拦截器校验失败时使用
     * （B 侧 {@code api/internal/logic/comment/*} 的 {@code errmsg.ErrorTokenRuntime} 调用点）。
     */
    public static final int ERROR_TOKEN_RUNTIME = 1006;

    /** B 侧 {@code ErrorTokenRefresh = 1007}。 */
    public static final int ERROR_TOKEN_REFRESH = 1007;

    /**
     * B 侧 {@code ErrorUserNoRight = 1008}：<b>comment 域高频码</b> —— 非 up 主无法评论
     * （B 侧 {@code update_subject_state_logic.go:88}）、无权删除
     * （B 侧 {@code delete_comment_logic.go:100} 与 {@code :113}）。
     */
    public static final int ERROR_USER_NO_RIGHT = 1008;

    /** B 侧 {@code ErrorUserNoLogin = 1009}。 */
    public static final int ERROR_USER_NO_LOGIN = 1009;

    /** B 侧 {@code ErrorUserLogined = 1010}。 */
    public static final int ERROR_USER_LOGINED = 1010;

    /** B 侧 {@code ErrorUserBanned = 1011}。 */
    public static final int ERROR_USER_BANNED = 1011;

    // ============== comment 专属（B 侧 CommentErrorCode.java:62-63） ==============

    /**
     * B 侧 {@code ErrorSubjectNotExist = 1012}：评论区不存在。<b>本域出现频次最高的码之一</b>，
     * 覆盖建评论 / 点赞 / 删除 / 改主体状态四条链路。
     */
    public static final int ERROR_SUBJECT_NOT_EXIST = 1012;

    /**
     * B 侧 {@code ErrorCommentNotExist = 1013}：评论不存在。
     *
     * <p>⚠️ <b>切勿与 {@link #ERROR_SUBJECT_NOT_EXIST} 合并</b>：前者是评论行缺失，
     * 后者是评论区（subject）缺失，B 侧两者是独立文案与独立码。
     */
    public static final int ERROR_COMMENT_NOT_EXIST = 1013;

    // ============== 输入校验（B 侧 CommentErrorCode.java:64） ==============

    /**
     * B 侧 {@code ErrorInputWrong = 2001}：comment 域最高频的码 —— 所有入参非空校验分支
     * （B 侧 {@code create_comment_logic.go:66,73}、{@code delete_comment_logic.go:61}、
     * {@code like_comment_logic.go:48}、{@code manage_comment_logic.go:62,84}、
     * {@code report_comment_logic.go:64}、{@code update_subject_state_logic.go:63}）。
     */
    public static final int ERROR_INPUT_WRONG = 2001;

    private ErrCodes() {
        // 常量类，禁止 new
    }
}
