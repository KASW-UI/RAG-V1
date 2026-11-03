package com.rag.like.service;

/**
 * like 域 Redis key 构造 —— 对应 Go 端散落在 6 个 logic 里的 {@code fmt.Sprintf} 拼键。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeRedisKeys.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：无。包名由 {@code com.rag.like.like.rpc.service} 压平为
 *       {@code com.rag.like.service}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样。6 个 key 的字面量与分隔符逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <p><b>为什么抽出来</b>：Go 侧同一套 key 在<b>写</b>路径（{@code likeactionlogic.go}）和
 * <b>读</b>路径（{@code getlikecountlogic.go} 等）各拼一遍，两边字符串必须逐字相同，
 * 却没有任何共享定义 —— 改错一个字符的表现是「点赞写入的计数读不出来」，
 * 而 Redis 里不会报错、只会静默 miss，非常难查。
 * 这里把 6 个 key 收敛成唯一入口，读写两侧共用。
 *
 * <p>⚠️ 这些 key 的字面量属于 <b>L1 外部契约</b>：
 * <ul>
 *   <li>Go like-rpc 与 Java like-rpc 灰度期连同一个 Redis；</li>
 *   <li>Java like-api 未来也要读同一批 key（如果它做本地缓存的话）。</li>
 * </ul>
 * 改分隔符（{@code :}）或大小写都会导致存量数据读不出来。
 */
public final class LikeRedisKeys {

    private LikeRedisKeys() {
        // 纯静态工具，禁止实例化
    }

    /**
     * 某类作品的点赞数 Hash —— Go {@code fmt.Sprintf("like_count:%s", in.TargetType)}。
     *
     * <p>结构：{@code Hash<target_id, 点赞数>}。
     * <b>无 TTL</b>（Go 侧对它只做 HINCRBY / HSET，从不 EXPIRE）。
     */
    public static String likeCountHash(String targetType) {
        return "like_count:" + targetType;
    }

    /**
     * 某类作品的点踩数 Hash —— Go {@code fmt.Sprintf("dislike_count:%s", in.TargetType)}。
     *
     * <p>结构：{@code Hash<target_id, 点踩数>}。同样<b>无 TTL</b>。
     */
    public static String dislikeCountHash(String targetType) {
        return "dislike_count:" + targetType;
    }

    /**
     * 单个作品的「某用户对其状态」 Hash —— Go {@code fmt.Sprintf("like_state:%s:%s", in.TargetType, in.TargetId)}。
     *
     * <p>结构：{@code Hash<user_id, state>}。无 TTL。
     *
     * <p>⚠️ 注意 state 取值 <b>0/1/2</b>（无/赞/踩），与消息里的 action 1~4 不是一套编码。
     */
    public static String likeStateHash(String targetType, String targetId) {
        return "like_state:" + targetType + ":" + targetId;
    }

    /**
     * 某用户的作品点赞列表 ZSet —— Go {@code fmt.Sprintf("user_like_list:%d:%s", in.UserId, in.TargetType)}。
     *
     * <p>结构：{@code ZSet<member=target_id, score=打包游标>}。
     * 有 TTL：仅在 DB 回源后 {@code Expire(..., 7 天)}（Go 硬编码常量，见 {@code like.list-cache-ttl}）。
     */
    public static String userLikeListZSet(long userId, String targetType) {
        return "user_like_list:" + userId + ":" + targetType;
    }

    /**
     * 某作品的点赞人列表 ZSet —— Go {@code fmt.Sprintf("target_liker_list:%s:%s", in.TargetType, in.TargetId)}。
     *
     * <p>结构：{@code ZSet<member=user_id, score=打包游标>}。同样 7 天 TTL。
     */
    public static String targetLikerListZSet(String targetType, String targetId) {
        return "target_liker_list:" + targetType + ":" + targetId;
    }

    /**
     * 某作者的总获赞数 String —— Go {@code fmt.Sprintf("user_total_like:%d", in.AuthorId)}。
     *
     * <p>结构：{@code String}。是本域<b>唯一</b>用 {@code SETEX} 写 TTL 的 key
     * （{@code GetUserTotalLike} 回源后写、命中后 {@code EXPIRE} 续期）。
     */
    public static String userTotalLikeString(long authorId) {
        return "user_total_like:" + authorId;
    }
}
