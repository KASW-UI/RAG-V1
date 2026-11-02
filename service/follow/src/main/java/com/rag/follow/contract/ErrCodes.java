package com.rag.follow.contract;

/**
 * <p><b>follow 域业务错误码常量集（域内私有 · L1 契约层）</b>。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-common/src/main/java/com/rag/follow/follow/common/errmsg/FollowErrorCode.java:42}
 * 起 —— <b>枚举 16 个常量，码值逐字照抄</b>。
 *
 * <p><b>⚠️ 为什么落域内 {@code contract/} 而不是 {@code service/users/common/}</b>：
 * <ol>
 *   <li><b>逐域隔离（AGENTS.md §八）</b>：错误码值 / 文案 / 语言<b>逐域隔离，禁止提取共享基座</b>
 *       ⇒ follow 的 16 个码只归 follow 域所有。</li>
 *   <li><b>文案形态根本不同</b>：follow 域整表<b>英文</b>（{@code "internal server error"} /
 *       {@code "invalid parameter"} / {@code "user not found"} …），
 *       与 users 域的中文表、与 favorite 域的「同码不同义」形态都不同 ⇒ 共表即破坏隔离。</li>
 *   <li><b>users-common 现状</b>：该模块只有 users 域自己的 31 条码（码值 5001/5002/5003 …），
 *       <b>不含 follow 的任何一条</b>（5201/5202/5101/5203/5204 全无）⇒ 落进去等于新建跨域共享表。</li>
 *   <li><b>落点已定</b>：{@code docs/File_Tree/follow.md}（由 {@code tools/gen-file-tree.py}
 *       按实际文件系统生成）已把落点生成为 {@code com.rag.follow.contract.ErrCodes}。</li>
 * </ol>
 *
 * <p><b>⚠️ 与 B 侧「枚举」形态的表达层差异（L4 · 表达层自由，语义等价）</b>：
 * <ul>
 *   <li>B 侧是 {@code enum FollowErrorCode}（每个常量一个枚举项，附大段 javadoc）；
 *       C 侧改为 {@code final class + int 常量} ⇒ <b>码值集合、码值大小、码值含义逐条一致</b>
 *       （下方每个常量的 javadoc 逐条搬自 B 侧对应枚举项）。</li>
 *   <li><b>为什么不用枚举</b>：本仓的惯例是「域内 {@code contract/} 放码值 + 文案查表两个类」
 *       （users 域 {@code service/users/user/.../contract/ErrCodes.java} 先例）
 *       ⇒ 且 A9 断言只针对 {@code dto/} 包 ⇒ 不受影响。
 *       <b>枚举 vs 常量类的可观察差异：无</b>（两者都只是 int 的命名来源）。</li>
 * </ol>
 *
 * <p><b>⚠️ 枚举顺序即契约（B 侧 javadoc 明写）</b>：B 侧
 * {@code FollowErrorCodeContractTest#declarationOrderMatchesGoSourceOrder} 钉死了声明顺序
 * ⇒ 下方常量<b>按 B 侧的声明顺序排列</b>，其中 {@code CODE_SERVER_BUSY(1015)} <b>排在 5001 之前</b>
 * —— 因为 B 侧源码就把它写在通用错误码分组的开头。<b>不要按数值大小重排</b>。
 *
 * <p><b>⚠️ 其中 4 个在 follow 域内没有调用点</b>：{@link #ERROR_NOT_FOUND}(5002)、
 * {@link #ERROR_ALREADY_EXIST}(5006)、{@link #ERROR_FOLLOW_NOT_FOUND}(5203)、
 * {@link #ERROR_BLOCK_NOT_FOUND}(5204) ⇒ <b>仍必须保留</b>：
 * ① 码表是跨服务契约的一部分，未知码要回落 {@code "FAIL"}，删掉会让「本域未用到的码」变成回落而非命中；
 * ② {@link GrpcErrorMapper#bizCodeFromError} 的 gRPC 码兜底分支会产出 5002 / 5006
 * ⇒ 删掉常量会让回落文案无处可查。
 *
 * @see com.rag.follow.contract.ErrMsg 文案查询入口（未知码回落 {@code "FAIL"}）
 * @see com.rag.follow.contract.GrpcErrorMapper gRPC 码 ⇄ 业务码双向转换
 */
public final class ErrCodes {

    // ============== 通用（B 侧 FollowErrorCode.java:44-45） ==============

    /** B 侧 {@code SUCCESS = 200}：rpc 成功响应的 {@code BaseResp.code}，也是 {@code GrpcErrorMapper#bizCodeFromError} 收到 {@code null} 时的返回值。 */
    public static final int SUCCESS = 200;

    /** B 侧 {@code ERROR = 500}：同时是 {@code ErrMsg.getMsg(int)} 未知码的兜底文案 {@code "FAIL"}。 */
    public static final int ERROR = 500;

    // ============== 通用错误码 5001-5099（B 侧 FollowErrorCode.java:47-58） ==============

    /**
     * B 侧 {@code CODE_SERVER_BUSY = 1015}。
     *
     * <p>⚠️ <b>排在 5001 之前</b>，因为 B 侧源码就写在通用错误码分组的开头（B 侧声明顺序即契约）。
     */
    public static final int CODE_SERVER_BUSY = 1015;

    /** B 侧 {@code ERROR_SERVER_COMMON = 5001}：DB 故障 / 用户查询失败的兜底码，也是 api 侧未识别异常时的默认码。 */
    public static final int ERROR_SERVER_COMMON = 5001;

    /** B 侧 {@code ERROR_NOT_FOUND = 5002}：⚠️ follow 域内无调用点，但会被 {@link GrpcErrorMapper} 的 {@code NOT_FOUND} 分支产出。 */
    public static final int ERROR_NOT_FOUND = 5002;

    /** B 侧 {@code ERROR_INVALID_PARAM = 5003}：入参校验失败（rpc 侧 {@code helper.go} 的两处校验）。 */
    public static final int ERROR_INVALID_PARAM = 5003;

    /** B 侧 {@code ERROR_UNAUTHORIZED = 5004}：ctx 里取不到 userId 时由 api 层产出（每个 logic 的身份解析出口）。 */
    public static final int ERROR_UNAUTHORIZED = 5004;

    /** B 侧 {@code ERROR_FORBIDDEN = 5005}：查询他人数据被拒（{@code getblocklistlogic.go} 与 {@code getrecommendationslogic.go} 各一处）。 */
    public static final int ERROR_FORBIDDEN = 5005;

    /** B 侧 {@code ERROR_ALREADY_EXIST = 5006}：⚠️ follow 域内无调用点，但会被 {@link GrpcErrorMapper} 的 {@code ALREADY_EXISTS} 分支产出。 */
    public static final int ERROR_ALREADY_EXIST = 5006;

    /** B 侧 {@code ERROR_DB_SELECT = 5007}：读路径 DB 回源失败。 */
    public static final int ERROR_DB_SELECT = 5007;

    /** B 侧 {@code ERROR_DB_UPDATE = 5008}：写路径 DB 失败。 */
    public static final int ERROR_DB_UPDATE = 5008;

    // ============== 用户模块 5100-5199（B 侧 FollowErrorCode.java:60） ==============

    /** B 侧 {@code ERROR_USER_NOT_FOUND = 5101}：目标用户不存在（rpc 侧 {@code helper.go} 的存在性校验）。 */
    public static final int ERROR_USER_NOT_FOUND = 5101;

    // ============== 关注域 5200-5299（B 侧 FollowErrorCode.java:62-65） ==============

    /** B 侧 {@code ERROR_SELF_RELATION = 5201}：自己关注 / 拉黑自己。 */
    public static final int ERROR_SELF_RELATION = 5201;

    /** B 侧 {@code ERROR_RELATION_BLOCKED = 5202}：存在拉黑关系时拒绝关注（{@code followlogic.go} 的第三道闸门）。 */
    public static final int ERROR_RELATION_BLOCKED = 5202;

    /** B 侧 {@code ERROR_FOLLOW_NOT_FOUND = 5203}：⚠️ follow 域内无调用点。 */
    public static final int ERROR_FOLLOW_NOT_FOUND = 5203;

    /** B 侧 {@code ERROR_BLOCK_NOT_FOUND = 5204}：⚠️ follow 域内无调用点。 */
    public static final int ERROR_BLOCK_NOT_FOUND = 5204;

    private ErrCodes() {
        // 常量容器，禁止 new
    }
}
