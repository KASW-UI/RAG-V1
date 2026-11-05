package com.rag.favorite.config;

/**
 * <p><b>favorite 域的 Prometheus label 取值常量</b> —— 逐条对应 Go
 * {@code service/favorite/rpc/internal/logic/helper.go:26-40} 的 const 块。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/config/FavoriteRpcLabels.java:10-95}
 *
 * <p><b>L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.favorite.favorite.rpc.config.FavoriteRpcLabels}
 * ⇒ C 侧 {@code com.rag.favorite.config.FavoriteRpcLabels}。
 *
 * <p><b>为什么要单点定义</b>：Go 把它们放在 {@code helper.go} 的 const 块里，
 * 7 个 logic 共 30+ 处调用点复用同一批字符串。Java 侧若散落在各 service 里，
 * 必然出现「同一个 action 在不同调用点拼成两个值」的漂移 —— 而 label 一旦拼错，
 * 对应的告警就<b>静默失效</b>（没有异常，只是不再上报那个维度）。
 *
 * <p>⚠️ <b>这些字符串是运维契约</b>：<b>不许</b>「顺手改成驼峰」。
 */
public final class FavoriteRpcLabels {

    private FavoriteRpcLabels() {
        // 常量类，禁止实例化
    }

    // ===== module（helper.go:27,33）=====

    /** {@code favorite_folder} —— 收藏夹侧的 4 个用例。 */
    public static final String MODULE_FOLDER = "favorite_folder";

    /** {@code favorite_item} —— 收藏条目侧的 3 个用例。 */
    public static final String MODULE_ITEM = "favorite_item";

    // ===== action（helper.go:28-32,34-36）=====

    /** {@code "create"}。 */
    public static final String ACTION_CREATE = "create";

    /** {@code "delete"}。 */
    public static final String ACTION_DELETE = "delete";

    /** {@code "list"}。 */
    public static final String ACTION_LIST = "list";

    /** {@code "update_name"}。 */
    public static final String ACTION_UPDATE_NAME = "update_name";

    /** {@code "list_by_folder"}。 */
    public static final String ACTION_LIST_BY_FOLDER = "list_by_folder";

    // ===== result（helper.go:38-39）=====

    /** {@code "success"}。 */
    public static final String RESULT_SUCCESS = "success";

    /** {@code "fail"}。 */
    public static final String RESULT_FAIL = "fail";

    // ===== 依赖 module（helper.go:101,137）=====

    /**
     * {@code "user_get"} —— ensureUserExists 的依赖调用 label（helper.go:101,110,114,118,122）。
     *
     * <p>⚠️ Go 把它当 <b>module</b> 位置传（{@code ObserveOp("dependency", "user_get", result)}），
     * 也就是说 favorite 的 ops 指标里 module 维度会出现第三个值 {@code dependency}，
     * 而不是只有 folder/item 两个。本类保留该值以对齐告警规则。
     */
    public static final String MODULE_DEPENDENCY = "dependency";

    /** {@code "user_get"} —— 见 {@link #MODULE_DEPENDENCY}。 */
    public static final String ACTION_USER_GET = "user_get";

    /**
     * {@code "article_get"} —— resolveArticleSnapshot 的依赖调用 label（helper.go:140,149,154,164,172）。
     *
     * <p>⚠️ <b>与 {@link #ACTION_USER_GET} 是不同值，不可混用</b>：
     * {@code user_get} 是 action 位置，而 {@code article_get} 在 Go 里被当作 action 传，
     * module 位置同样是 {@code dependency}。
     */
    public static final String ACTION_ARTICLE_GET = "article_get";

    // ===== DB error 的 action / type（helper.go:89,130,163 等）=====

    /** {@code "select"}。 */
    public static final String DB_ACTION_SELECT = "select";

    /** {@code "insert"}。 */
    public static final String DB_ACTION_INSERT = "insert";

    /** {@code "update"}。 */
    public static final String DB_ACTION_UPDATE = "update";

    /** {@code "delete"}。 */
    public static final String DB_ACTION_DELETE = "delete";

    /** {@code "db"} —— 普通 DB 错误。 */
    public static final String DB_TYPE_DB = "db";

    /**
     * {@code "duplicate"} —— 唯一键冲突（PG SQLSTATE 23505）。
     *
     * <p>Go 侧出现 5 次：{@code create_favorite_folder_logic.go:106,159}、
     * {@code create_favorite_logic.go:158}、{@code update_favorite_folder_name_logic.go:115}。
     * 它与 {@link #DB_TYPE_DB} 是<b>不同维度</b>：前者说明是业务可解释的重复，后者是真正的故障。
     */
    public static final String DB_TYPE_DUPLICATE = "duplicate";
}
