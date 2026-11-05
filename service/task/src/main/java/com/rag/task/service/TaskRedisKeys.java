package com.rag.task.service;

import java.time.Duration;

/**
 * <p><b>task 域 Redis key 的唯一构造入口</b> —— 对应 Go 端散落在
 * {@code get_task_logic.go} / {@code sink/like_sink.go} / {@code sink/article_sink.go} /
 * {@code reward/type.go} 里的 {@code fmt.Sprintf} 与字符串拼接。</p>
 *
 * <h2>⚠️ 为什么不直接照抄各处的拼接</h2>
 * <p>Go 侧同一个业务概念在<b>读路径</b>（{@code buildUserProgressKey}）与
 * <b>写路径</b>（{@code like_sink.go:214,334}）各拼一遍，字符串必须逐字相同，
 * 却没有共享定义。改错一个字符的表现是「进度写进去了但读不出来」，
 * 而且 <b>Redis 不报错、只会静默 miss</b>。
 *
 * <h2>🔴 本类最重要的发现：读路径与写路径的 key <b>本来就不一致</b></h2>
 * <table border="1">
 *   <caption>同一个「用户任务进度缓存」的两套拼法</caption>
 *   <tr><th>用途</th><th>Go 出处</th><th>key</th></tr>
 *   <tr><td>{@code GetTask} <b>读</b></td><td>{@code get_task_logic.go:106-108}</td>
 *       <td>{@code task:progress:<uid>}</td></tr>
 *   <tr><td>sink <b>失效</b></td><td>{@code like_sink.go:332-334}</td>
 *       <td>{@code task:progress:<uid>}</td></tr>
 *   <tr><td>点赞任务 <b>写</b></td><td>{@code like_sink.go:214}</td>
 *       <td>{@code task:progress:<uid>:<taskId>}</td></tr>
 *   <tr><td>文章任务 <b>写</b></td><td>{@code article_sink.go:295-298}</td>
 *       <td>{@code task:progress:<taskId>:<uid>:<articleId>}</td></tr>
 * </table>
 *
 * <p><b>后果（必须原样保留）</b>：两个 sink 写的永远是<b>另外两个</b> key，
 * 而 {@code GetTask} 读 / 两个 sink 删的都是 {@code task:progress:<uid>} 这一个。
 * ⇒ {@code GetTask} 的缓存<b>永远不会被 sink 写满</b>，
 * 除非外部（其它服务）恰好写过那个 key 之外，<b>每次调用都会 miss 并回源查库</b>。
 *
 * <p>⚠️ <b>不要"顺手修正"</b>：把写路径改成与读路径一致，会让缓存第一次真的生效，
 * 于是「测试环境观察不到的问题」在灰度时才暴露（读写 key 一旦统一，
 * 缓存内容与 DB 回填内容必须严格等价，否则会读到过期快照）。
 * 这是<b>行为变更</b>，不是等价迁移 —— 必须先在 Go 侧确认意图。
 *
 * <h2>⚠️ 同类不一致还有两处，也不要统一</h2>
 * <ul>
 *   <li>幂等标记：点赞路是 {@code task:init:<uid>:<taskId>}（<b>init 单数</b>、uid 在前），
 *       文章路是 {@code task:inits:<taskId>:<uid>:<articleId>}（<b>inits 复数</b>、taskId 在前）；</li>
 *   <li>done 标记：点赞路是 {@code task:done:<uid>:<taskId>}，
 *       文章路是 {@code task:done:<taskId>:<uid>}（<b>前两段顺序相反</b>）。</li>
 * </ul>
 * 这些 key 可能已被别的服务/脚本按现有形状读过，统一同样属于行为变更。
 *
 * <h2>TTL 也在这里集中，理由同上</h2>
 * <p>Go 侧 TTL 散落在各常量块，且<b>同一语义在不同任务下取值不同</b>
 * （点赞 90d、文章 done 120d=90+30、文章 init 仅 24h）。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.service} → {@code com.rag.task.service}（D7）；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 10 个 key 的拼法与 5 个 TTL 逐字照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/TaskRedisKeys.java:53-54（类声明）
 * B 出处：…/service/TaskRedisKeys.java:68-70（userProgressCache）
 * B 出处：…/service/TaskRedisKeys.java:81-83（likeTaskProgress）
 * B 出处：…/service/TaskRedisKeys.java:92-94（likeTaskInit）
 * B 出处：…/service/TaskRedisKeys.java:103-105（likeTaskDone）
 * B 出处：…/service/TaskRedisKeys.java:113-115（userLikeTotal）
 * B 出处：…/service/TaskRedisKeys.java:127-129（articleTaskInit）
 * B 出处：…/service/TaskRedisKeys.java:137-139（articleTaskProgress）
 * B 出处：…/service/TaskRedisKeys.java:149-151（articleTaskDone）
 * B 出处：…/service/TaskRedisKeys.java:157-159（articleLikeCount）
 * B 出处：…/service/TaskRedisKeys.java:172-174（rewardStream）
 * B 出处：…/service/TaskRedisKeys.java:182（PROGRESS_CACHE_TTL = 15m）
 * B 出处：…/service/TaskRedisKeys.java:193（EMPTY_PROGRESS_CACHE_TTL = 30s）
 * B 出处：…/service/TaskRedisKeys.java:196（LIKE_TASK_TTL = 90d）
 * B 出处：…/service/TaskRedisKeys.java:199（ARTICLE_INIT_TTL = 24h）
 * B 出处：…/service/TaskRedisKeys.java:203（ARTICLE_DONE_TTL = 120d）
 *       </pre></li>
 * </ol>
 */
public final class TaskRedisKeys {

    private TaskRedisKeys() {
        // 纯静态工具，禁止实例化
    }

    // ==================== 读路径 / 缓存失效 ====================

    /**
     * {@code GetTask} 读的进度缓存 Hash —— Go {@code buildUserProgressKey}：
     * {@code "task:progress:" + strconv.FormatInt(uid, 10)}。
     *
     * <p>结构：{@code Hash<taskId, "status|progress|target">}（打包格式见 {@code TaskDomainService}）。
     * TTL：回填时 15 分钟（{@code taskProgressTTL}）；DB 无行时 30 秒（但那是对不存在的 key 设值，等于 no-op）。
     */
    public static String userProgressCache(long uid) {
        return "task:progress:" + uid;
    }

    // ==================== 点赞任务（taskId=1902） ====================

    /**
     * 点赞路的进度 Hash —— Go {@code like_sink.go:214}：
     * {@code "task:progress:" + uid + ":" + taskID}。
     *
     * <p>⚠️ 与 {@link #userProgressCache} <b>不是同一个 key</b>（多了 {@code :taskID}），
     * 理由见类级那张表。字段：status / progress / target / doneAt / createAt / updateAt。
     */
    public static String likeTaskProgress(long uid, long taskId) {
        return "task:progress:" + uid + ":" + taskId;
    }

    /**
     * 点赞路的首次初始化幂等标记 —— Go {@code like_sink.go:186}：
     * {@code "task:init:" + uid + ":" + taskID}。
     *
     * <p>{@code SETNX ... "1"}，TTL 90 天。返回 true 才算「新用户」⇒ 触发 HSET + DB DO NOTHING 插入。
     * ⚠️ 是 {@code init} <b>单数</b>，与文章路的 {@code inits} 不同。
     */
    public static String likeTaskInit(long uid, long taskId) {
        return "task:init:" + uid + ":" + taskId;
    }

    /**
     * 点赞路的完成幂等标记 —— Go {@code like_sink.go:338}：
     * {@code "task:done:" + uid + ":" + taskID}。
     *
     * <p>{@code SETNX ... "1"}，TTL 90 天。⚠️ <b>uid 在前</b>，与文章路相反。
     * 返回 false（已存在）⇒ 直接返回，<b>不重发奖励、不重写 DB</b>。
     */
    public static String likeTaskDone(long uid, long taskId) {
        return "task:done:" + uid + ":" + taskId;
    }

    /**
     * 用户累计点赞数缓存 String —— Go {@code like_sink.go:284}：
     * {@code "like:total:" + uid}。
     *
     * <p>{@code SET ... EX} 90 天。⚠️ <b>无 TTL 续期</b>：每轮 flush 都无条件重写并重设 90 天。
     */
    public static String userLikeTotal(long uid) {
        return "like:total:" + uid;
    }

    // ==================== 文章任务（taskId=190） ====================

    /**
     * 文章路的首次初始化幂等标记 —— Go {@code article_sink.go:255-258}：
     * {@code "task:inits:" + articleTaskID + ":" + userID + ":" + articleID}。
     *
     * <p>⚠️ 三处与点赞路不同：{@code inits} <b>复数</b>、<b>taskId 在最前</b>、<b>带 articleId</b>。
     * TTL 仅 24 小时（{@code articleInitTTL}）—— 比点赞路短 90 天，
     * 意味着同一个 (task,user,article) 组合每 24 天可能被重新判定为「新」并再插一条 DO NOTHING。
     */
    public static String articleTaskInit(long taskId, long uid, long articleId) {
        return "task:inits:" + taskId + ":" + uid + ":" + articleId;
    }

    /**
     * 文章路的进度 Hash —— Go {@code article_sink.go:295-298}、{@code 436-439}：
     * {@code "task:progress:" + articleTaskID + ":" + userID + ":" + articleID}。
     *
     * <p>⚠️ taskId 在最前，且带 articleId。
     */
    public static String articleTaskProgress(long taskId, long uid, long articleId) {
        return "task:progress:" + taskId + ":" + uid + ":" + articleId;
    }

    /**
     * 文章路的完成幂等标记 —— Go {@code article_sink.go:427}：
     * {@code "task:done:" + articleTaskID + ":" + userID}。
     *
     * <p>⚠️ <b>按 userID 而不是 articleID</b>：Go 源码注释写明
     * 「每个人只能完成一次这个任务，所以选择 userID，而不是 articleID」。
     * TTL 120 天（= 90d + 30d，{@code articleDoneTTL}）。
     */
    public static String articleTaskDone(long taskId, long uid) {
        return "task:done:" + taskId + ":" + uid;
    }

    /**
     * 文章获赞数缓存 String —— Go {@code article_sink.go:368-369}：
     * {@code "article:like:cnt:" + articleID}。TTL 120 天。
     */
    public static String articleLikeCount(long articleId) {
        return "article:like:cnt:" + articleId;
    }

    // ==================== 奖励事件流 ====================

    /**
     * 奖励事件 Redis Stream —— Go {@code internal/reward/type.go:9}
     * {@code const StreamKey = "stream:task:reward"}。
     *
     * <p>由两个 sink 的完成分支 {@code XADD}。
     * ⚠️ <b>本模块只写不读</b>：消费方 {@code reward.Worker} 在 Go 侧<b>没有被启动</b>
     * （{@code task.go:35} 的调用被注释、{@code initReward.go} 整个文件是注释），
     * 因为它依赖的 {@code PointsClient} 在 {@code service_context.go:18-19} 就被注释掉了。
     */
    public static String rewardStream() {
        return "stream:task:reward";
    }

    // ==================== TTL 集中 ====================

    /**
     * {@code GetTask} 回填进度缓存的 TTL —— Go {@code taskProgressTTL()}：
     * {@code 15 * time.Minute}。
     */
    public static final Duration PROGRESS_CACHE_TTL = Duration.ofMinutes(15);

    /**
     * {@code GetTask} 在「DB 查不到任何行」时对同一个 key 设的 TTL —— Go
     * {@code get_task_logic.go:78} {@code Expire(ctx, pkey, 30*time.Second)}。
     *
     * <p>⚠️ 此刻该 key <b>并不存在</b>（HGETALL miss ⇒ 没有任何 HSET），
     * 所以这条 EXPIRE 实际上什么都不做：它不是「空结果缓存」。
     * ⇒ 没有行时<b>每次调用都会再查一次库</b>。
     * <b>不要</b>把它改写成「写一个空标记 key 以防穿透」—— 那是新增行为。
     */
    public static final Duration EMPTY_PROGRESS_CACHE_TTL = Duration.ofSeconds(30);

    /** 点赞路的 init / done / like:total TTL —— Go {@code like_sink.go:188,284,339}：{@code 90*24h}。 */
    public static final Duration LIKE_TASK_TTL = Duration.ofDays(90);

    /** 文章路的 init TTL —— Go {@code article_sink.go:23} {@code articleInitTTL = 24h}。 */
    public static final Duration ARTICLE_INIT_TTL = Duration.ofHours(24);

    /** 文章路的 done / like:cnt TTL —— Go {@code article_sink.go:22}
     *  {@code articleDoneTTL = 90*24h + 30*24h = 120 天}。 */
    public static final Duration ARTICLE_DONE_TTL = Duration.ofDays(120);
}
