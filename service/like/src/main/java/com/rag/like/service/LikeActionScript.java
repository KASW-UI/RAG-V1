package com.rag.like.service;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 点赞写操作的 Lua 脚本 —— 逐字对应 B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeActionScript.java}
 * （该文件的 Lua 原文转抄自 Go 端
 * {@code service/like/rpc/internal/logic/likeactionlogic.go:56-122} 的 {@code const LikeActionLua}）。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：无。包名由 {@code com.rag.like.like.rpc.service} 压平为
 *       {@code com.rag.like.service}（D7）；{@link LikeRedisKeys} 的同包引用去掉了 import。</li>
 *   <li><b>语义还一样吗</b>：一样。Lua 源码、KEYS/ARGV 顺序、返回值协议（0/1/2）逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>为什么必须用 Lua，不能拆成多条 Redis 命令</h2>
 *
 * <p>这段逻辑要读「当前状态」再决定改哪几个 key（点赞数 / 点踩数 / 状态 / 两个列表 / 作者总数，
 * 共 6 个 key），中间不允许被打断。拆成多条命令会出现：
 * 「已 HINCRBY 点赞数、还没 ZREM 列表」的中间态，被并发的 {@code GetLikeCount} 读到。
 * Go 的 {@code EvalCtx} 保证了这一点，Java 的 {@code RedisTemplate.execute(RedisScript, …)}
 * 是同一个 Redis 端原子执行，语义一致。
 *
 * <h2>返回值协议（Go 侧靠它分三支，改动即破坏调用方）</h2>
 *
 * <table border="1">
 *   <caption>返回值与调用方分支</caption>
 *   <tr><th>返回</th><th>含义</th><th>Go 侧后果</th></tr>
 *   <tr><td>0</td><td>幂等：当前状态 == 本次 action</td>
 *       <td>不推 Kafka、{@code isFirst=false}，但<b>仍会回查并返回最新计数</b></td></tr>
 *   <tr><td>1</td><td>状态发生了变更</td><td>推 Kafka、{@code isFirst=false}</td></tr>
 *   <tr><td>2</td><td>首次点赞（1 且原状态为 0）</td><td>推 Kafka、{@code isFirst=true}
 *       ⇒ MQ 侧据此建 {@code first_like:*} outbox 事件</td></tr>
 * </table>
 *
 * <p><b>特别注意返回值 0 分支</b>：Go 拿到 0 之后<b>不会</b>短路返回，
 * 仍然执行 {@code Hget} 读取最终计数、仍然跑 article 分支判定
 * （虽然那些分支都带 {@code resInt != 0} 前置）。本实现在
 * {@code domain/service/LikeDomainService#likeAction} 里严格保留这个「不短路」的结构。
 *
 * <h2>KEYS / ARGV 顺序</h2>
 * <pre>
 * KEYS[1]=like_count:&lt;type&gt;          KEYS[4]=target_liker_list:&lt;type&gt;:&lt;id&gt;
 * KEYS[2]=like_state:&lt;type&gt;:&lt;id&gt;     KEYS[5]=dislike_count:&lt;type&gt;
 * KEYS[3]=user_like_list:&lt;uid&gt;:&lt;type&gt; KEYS[6]=user_total_like:&lt;authorId&gt;
 *
 * ARGV[1]=target_id  ARGV[2]=user_id  ARGV[3]=action_type  ARGV[4]=score
 * </pre>
 * 全部由 {@link LikeRedisKeys} 构造，顺序不可调换。
 */
public final class LikeActionScript {

    /** Go 侧的返回值：幂等未变更。 */
    public static final long RESULT_IDEMPOTENT = 0L;

    /** Go 侧的返回值：状态已变更。 */
    public static final long RESULT_CHANGED = 1L;

    /** Go 侧的返回值：首次点赞。 */
    public static final long RESULT_FIRST_LIKE = 2L;

    /**
     * 脚本原文。
     *
     * <p>逐字保留 Go 版本，包括几处「看起来可以更严谨」的写法：
     * <ul>
     *   <li>{@code tonumber(redis.call("HGET", KEYS[2], user_id) or "0")} — 字段不存在时按 0 处理；</li>
     *   <li>计数递减前的 {@code if curr > 0 then} 守卫 — 防止把 0 减成 -1；</li>
     *   <li>{@code user_total_like} 的 INCR <b>没有</b>正数守卫，DECR 有 — 这是 Go 的现状，
     *       改成对称就是行为变更。</li>
     * </ul>
     */
    public static final String LUA = """
            local target_id = ARGV[1]
            local user_id = ARGV[2]
            local action_type = tonumber(ARGV[3])
            local score = ARGV[4]

            -- KEYS[1]=like_count, KEYS[2]=like_state, KEYS[3]=user_like_list,
            -- KEYS[4]=target_liker_list, KEYS[5]=dislike_count, KEYS[6]=user_total_like

            local current_state = tonumber(redis.call("HGET", KEYS[2], user_id) or "0")
            if current_state == action_type then
                return 0
            end

            redis.call("HSET", KEYS[2], user_id, action_type)

            if action_type == 1 then
                redis.call("HINCRBY", KEYS[1], target_id, 1)
                redis.call("INCR", KEYS[6])
                redis.call("ZADD", KEYS[3], score, target_id)
                redis.call("ZADD", KEYS[4], score, user_id)
                if current_state == 3 then
                    local curr_dislike = tonumber(redis.call("HGET", KEYS[5], target_id) or "0")
                    if curr_dislike > 0 then
                        redis.call("HINCRBY", KEYS[5], target_id, -1)
                    end
                end

            elseif action_type == 2 then
                local curr_like = tonumber(redis.call("HGET", KEYS[1], target_id) or "0")
                if curr_like > 0 then
                    redis.call("HINCRBY", KEYS[1], target_id, -1)
                    local curr_total = tonumber(redis.call("GET", KEYS[6]) or "0")
                    if curr_total > 0 then
                        redis.call("DECR", KEYS[6])
                    end
                end
                redis.call("ZREM", KEYS[3], target_id)
                redis.call("ZREM", KEYS[4], user_id)

            elseif action_type == 3 then
                redis.call("HINCRBY", KEYS[5], target_id, 1)
                if current_state == 1 then
                    local curr_like = tonumber(redis.call("HGET", KEYS[1], target_id) or "0")
                    if curr_like > 0 then
                        redis.call("HINCRBY", KEYS[1], target_id, -1)
                        local curr_total = tonumber(redis.call("GET", KEYS[6]) or "0")
                        if curr_total > 0 then
                            redis.call("DECR", KEYS[6])
                        end
                    end
                    redis.call("ZREM", KEYS[3], target_id)
                    redis.call("ZREM", KEYS[4], user_id)
                end

            elseif action_type == 4 then
                local curr_dislike = tonumber(redis.call("HGET", KEYS[5], target_id) or "0")
                if curr_dislike > 0 then
                    redis.call("HINCRBY", KEYS[5], target_id, -1)
                end
            end
            if action_type == 1 and current_state == 0 then
                return 2
            end

            return 1
            """;

    /**
     * 预编译脚本实例。
     *
     * <p>用 {@code DefaultRedisScript} 而不是每次 {@code RedisScript.of(...)}：
     * Spring Data Redis 会用脚本 SHA 走 {@code EVALSHA}，首次失败再回退 {@code EVAL}。
     * 脚本内容<b>必须恒定</b> —— 改一个字会让所有已缓存 SHA 的客户端在回退窗口内行为异常。
     */
    public static final RedisScript<Long> INSTANCE = new DefaultRedisScript<>(LUA, Long.class);

    private LikeActionScript() {
        // 常量容器，禁止实例化
    }

    /**
     * 按 Go {@code EvalCtx(ctx, Lua, []string{6 keys}, 4 args)} 的顺序组装 ARGV。
     *
     * <p>返回数组而非 List：{@code RedisOperations#execute} 的签名是
     * {@code execute(RedisScript<T>, List<K> keys, Object... args)} —— varargs，
     * 必须以数组形式展开。四个值<b>全部序列化为字符串</b>，与 Go 的
     * {@code fmt.Sprintf("%d", …)} 一致；Lua 里 {@code action_type} 自己 {@code tonumber}。
     */
    public static String[] args(String targetId, long userId, int actionType, long score) {
        return new String[]{
                targetId,
                String.valueOf(userId),
                String.valueOf(actionType),
                String.valueOf(score)};
    }
}
