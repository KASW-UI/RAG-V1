package com.rag.hot.messaging;

import com.rag.hot.config.HotRpcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * <p><b>分布式锁 + 跨分片求和合并</b>。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/mq/HotMergeScheduler.java}
 * （1-346 行；类声明 {@code :53-54}；{@code MERGE_LOCK_KEY} {@code :59}；
 * {@code LOCK_TTL_SECONDS} {@code :63}；{@code RENEW_INTERVAL_MILLIS} {@code :66}；
 * {@code PERIODIC_MERGE_MILLIS} {@code :69}；{@code PER_PARTITION_LIMIT} {@code :72}；
 * {@code MERGED_LIMIT} {@code :75}；三个 Lua 脚本 {@code :78-106}；
 * 字段与构造器 {@code :108-127}；{@code resolveInstanceId} {@code :130-141}；
 * {@code periodicMerge} {@code :148-158}；{@code acquireLockWithWatchDog} {@code :161-177}；
 * {@code watchDog} {@code :189-214}；{@code releaseLock} {@code :217-227}；
 * {@code mergePartitions} {@code :239-290}；{@code collect} {@code :294-316}；
 * {@code writeShadow} {@code :319-337}；两个观测方法 {@code :340-346}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯命名 / 定位</b>改动 ——
 *       <ul>
 *         <li>包名 {@code com.rag.hot.hot.rpc.mq} → {@code com.rag.hot.messaging}（D7 包压平）；</li>
 *         <li>{@code HotRpcProperties} 的 import → {@code com.rag.hot.config}（D7）。</li>
 *       </ul>
 *       <b>方法体与三个 Lua 脚本逐字照抄</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>4 个 Redis key 的名字与 TTL 逐条未动；</li>
 *         <li><b>锁协议</b>（{@code SETNX} 抢锁 → 3s 看门狗 compare-and-{@code EXPIRE} 续期
 *             → 合并完成后 compare-and-{@code DEL} 释放）逐条未动；</li>
 *         <li><b>抢不到锁 ⇒ 本轮跳过</b>（不排队、不退避）逐条未动；</li>
 *         <li><b>两处 B 侧既有缺陷照抄</b>（见下方「⚠️ 照抄的两处 B 侧既有缺陷」）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>L1 契约：Redis keyspace</h2>
 * <pre>
 * hot:merge:lock       分布式锁，value=instanceID，TTL 10s
 * hot:articles:p{i}    分片快照（TTL 300s 硬编码，见 HotRedisSnapshotStore）
 * hot:articles:shadow  合并期影子榜
 * hot:articles         合并结果（TTL = Interaction.TTL，默认 3600s）
 * </pre>
 *
 * <h2>⚠️ 照抄的两处 B 侧既有缺陷（按「不擅自修复 B 侧行为」原样保留）</h2>
 * <ol>
 *   <li><b>Redis 脚本报错被误报成「lock lost」</b>：B 侧 {@code :408} 是 {@code result, _ := ....Int()}，
 *       丢弃了 Redis 返回的错误 ⇒ 脚本<b>执行失败</b>（而非真丢锁）也会打印
 *       {@code [Merge] lock lost, abort}，把排障指向错误方向。
 *       看门狗 {@code :311} 是同款 {@code result, _}。本实现<b>原样保留</b>。</li>
 *   <li><b>读取阶段错误被完全忽略</b>：B 侧 {@code :364} 的 {@code pipe.Exec(ctx)} 连返回值都没接，
 *       {@code :368} 的 {@code cmd.Result()} 也忽略 error ⇒ 读失败时该分片被当作空参与求和。
 *       本实现同样不抛，只记日志。</li>
 * </ol>
 */
@Component
public class HotMergeScheduler {

    private static final Logger log = LoggerFactory.getLogger(HotMergeScheduler.class);

    /** 对应 B 侧 {@code mergeLockKey}。 */
    public static final String MERGE_LOCK_KEY = "hot:merge:lock";

    /** 对应 B 侧 {@code lockTTL = 10 * time.Second}。包可见（真 Redis 守门测试要引用它断言续期 TTL）。 */
    static final long LOCK_TTL_SECONDS = 10L;

    /** 对应 B 侧 {@code renewInterval = 3 * time.Second}。 */
    private static final long RENEW_INTERVAL_MILLIS = 3_000L;

    /** 对应 B 侧 {@code periodicMerge} 的 10s ticker。 */
    private static final int PERIODIC_MERGE_MILLIS = 10_000;

    /** 每片最多读取条数，对应 B 侧 {@code ZRevRangeWithScores(..., 0, 999)}。 */
    private static final int PER_PARTITION_LIMIT = 999;

    /** 合并结果截断长度，对应 B 侧 {@code merged[:100]}。 */
    private static final int MERGED_LIMIT = 100;

    /** 看门狗续期脚本。KEYS[1]=lock，ARGV[1]=instanceID，ARGV[2]=TTL 秒。 */
    static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call("GET", KEYS[1]) == ARGV[1] then
                return redis.call("EXPIRE", KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    /** 锁释放脚本。 */
    static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call("GET", KEYS[1]) == ARGV[1] then
                return redis.call("DEL", KEYS[1])
            end
            return 0
            """, Long.class);

    /**
     * 合并改名脚本。
     *
     * <p>⚠️ 锁 key 在 Lua 里是<b>字面量</b> {@code "hot:merge:lock"}，不是 {@code KEYS[1]} —— 与 B 侧逐字一致。
     * KEYS[1]=shadow，KEYS[2]={@code hot:articles}，ARGV[1]=instanceID，ARGV[2]=TTL 秒。
     */
    static final RedisScript<Long> MERGE_RENAME_SCRIPT = new DefaultRedisScript<>("""
            if redis.call("GET", "hot:merge:lock") ~= ARGV[1] then
                return 0
            end
            redis.call('RENAME', KEYS[1], KEYS[2])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final HotRpcProperties props;
    private final TaskExecutor taskExecutor;

    /** 对应 B 侧 {@code h.instanceID = hostname-pid}。 */
    private final String instanceId = resolveInstanceId();

    /** 看门狗世代号：释放锁时自增，让旧看门狗立刻退出（对应 B 侧的 {@code lockCancel}）。 */
    private final AtomicLong lockGeneration = new AtomicLong();

    /** 对应 B 侧 {@code h.lockCancel != nil}。 */
    private volatile boolean lockHeld;

    /**
     * 构造器注入。
     *
     * @param redis        Redis 模板
     * @param props        hot 域的 rpc 段配置（提供分片数与大 key TTL）
     * @param taskExecutor 异步执行器
     */
    public HotMergeScheduler(StringRedisTemplate redis,
                             HotRpcProperties props,
                             @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.redis = redis;
        this.props = props;
        this.taskExecutor = taskExecutor;
    }

    /** 对应 B 侧 {@code hot_handler.go:61-68}：{@code hostname-pid}。 */
    private static String resolveInstanceId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            hostname = "unknown";
        }
        if (hostname == null || hostname.isEmpty()) {
            hostname = "unknown";
        }
        return hostname + "-" + ProcessHandle.current().pid();
    }

    /**
     * <p><b>10s 周期合并</b>。
     *
     * <p>抢不到锁直接跳过本轮（B 侧的 {@code continue}），不做退避、不排队。
     */
    @Scheduled(fixedRate = PERIODIC_MERGE_MILLIS)
    public void periodicMerge() {
        if (!acquireLockWithWatchDog()) {
            return;
        }
        try {
            mergePartitions();
        } finally {
            releaseLock();
        }
    }

    /**
     * 对应 B 侧 {@code acquireLockWithWatchDog}。
     *
     * @return 是否抢到锁
     */
    boolean acquireLockWithWatchDog() {
        Boolean ok;
        try {
            ok = redis.opsForValue().setIfAbsent(MERGE_LOCK_KEY, instanceId,
                    Duration.ofSeconds(LOCK_TTL_SECONDS));
        } catch (RuntimeException e) {
            log.warn("[Lock] acquire failed: {}", e.getMessage());
            return false;
        }
        if (!Boolean.TRUE.equals(ok)) {
            return false;
        }
        long generation = lockGeneration.incrementAndGet();
        lockHeld = true;
        taskExecutor.execute(() -> watchDog(generation));
        return true;
    }

    /**
     * <p><b>看门狗</b>：每 3s 用 compare-and-{@code EXPIRE} 续期，返回 0 即认为丢锁并停止。
     *
     * <p>⚠️ <b>照抄 B 侧的 {@code result, _}</b>：Redis 脚本执行失败也会让 {@code result} 落到 0
     * ⇒ 被报成「lost lock」。这是 B 侧既有缺陷，原样如此。
     *
     * <p>用「循环 + sleep」而非 {@code @Scheduled}：看门狗的生命周期<b>绑定在锁的持有期</b>上，
     * 不是固定频率任务 —— 固定频率调度会在没持锁时白白访问 Redis。
     *
     * @param generation 本代看门狗的世代号
     */
    void watchDog(long generation) {
        while (lockHeld && lockGeneration.get() == generation) {
            try {
                Thread.sleep(RENEW_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!(lockHeld && lockGeneration.get() == generation)) {
                return;
            }
            long result;
            try {
                Long r = redis.execute(RENEW_SCRIPT, List.of(MERGE_LOCK_KEY),
                        instanceId, String.valueOf(LOCK_TTL_SECONDS));
                result = (r == null) ? 0L : r;
            } catch (RuntimeException e) {
                // B 侧的 `result, _` 同样把 Redis 错误压成 0
                result = 0L;
            }
            if (result == 0L) {
                log.error("[Lock] {} lost lock", instanceId);
                return;
            }
        }
    }

    /** 对应 B 侧 {@code releaseLock}。 */
    void releaseLock() {
        lockHeld = false;
        lockGeneration.incrementAndGet(); // 让本代看门狗退出（B 侧的 lockCancel()）

        try {
            // B 侧连返回值都整个丢弃
            redis.execute(RELEASE_SCRIPT, List.of(MERGE_LOCK_KEY), instanceId);
        } catch (RuntimeException e) {
            log.warn("[Lock] release failed: {}", e.getMessage());
        }
    }

    /**
     * <p><b>跨分片求和合并</b>：读 {@code PartitionCount} 个分片各 top-1000 →
     * 按 member 求和 → 降序 → 截断 100 → 写 shadow →
     * CAS 改名到 {@code hot:articles} 并设 TTL。
     *
     * <p><b>有损语义（必须保留）</b>：全局 Top-K 是「每片 Top-100 之和再取 100」。
     * 一个在每个分片都排 101 名的文章<b>永远不会出现</b>；而各片 Top-100 的分数
     * 又被当作精确值参与求和。这是<b>有损 sketch 的二次有损</b>，不是精确全局 Top-K。
     * 换成 Redis ZADD 精确计数 = 行为变更。
     */
    void mergePartitions() {
        int partitionCount = props.getInteraction().getPartitionCount();

        Map<String, Double> scoreMap = new HashMap<>();
        try {
            // 对应 B 侧 {@code :359-364} 的一次 pipeline：N 个 ZREVRANGE，Exec 错误被忽略
            List<Object> raw = redis.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) {
                    for (int i = 0; i < partitionCount; i++) {
                        operations.opsForZSet().reverseRangeWithScores(
                                HotRedisSnapshotStore.partitionKey(i), 0, PER_PARTITION_LIMIT);
                    }
                    return null;
                }
            });
            if (raw != null) {
                for (Object cmdResult : raw) {
                    // B 侧 {@code :368} 忽略 cmd.Result() 的 error ⇒ 失败分片按空处理
                    collect(scoreMap, cmdResult);
                }
            }
        } catch (RuntimeException e) {
            // B 侧 {@code :364} 的 pipe.Exec(ctx) 连返回值都没接，错误被完全忽略
            log.warn("[Merge] read pipeline failed: {}", e.getMessage());
        }

        List<Map.Entry<String, Double>> merged = new ArrayList<>(scoreMap.entrySet());
        merged.sort((a, b) -> Double.compare(b.getValue(), a.getValue())); // B 侧的 sort.Slice，非稳定
        if (merged.size() > MERGED_LIMIT) {
            merged = merged.subList(0, MERGED_LIMIT);
        }
        if (merged.isEmpty()) {
            // B 侧 {@code :386-388}：合并结果为空则直接返回，连 shadow 都不写
            return;
        }

        writeShadow(merged);

        try {
            Long result = redis.execute(MERGE_RENAME_SCRIPT,
                    List.of(HotRedisSnapshotStore.MERGE_SHADOW_KEY, HotRedisSnapshotStore.HOT_ARTICLES_KEY),
                    instanceId, String.valueOf(props.getInteraction().getTtlSeconds()));
            // ⚠️ B 侧 {@code :408} 是 `result, _ :=` —— Redis 错误被丢弃，此处照抄
            if (result == null || result == 0L) {
                log.error("[Merge] lock lost, abort");
            }
        } catch (RuntimeException e) {
            // 同上：B 侧的 result==0 分支会打印 "lock lost, abort"
            log.error("[Merge] lock lost, abort ({})", e.getMessage());
        }
    }

    /**
     * 把 pipeline 返回的单片结果累加进 {@code scoreMap}（B 侧 {@code :367-372}）。
     *
     * @param scoreMap   累加目标
     * @param cmdResult  单片的 pipeline 结果
     */
    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Double> scoreMap, Object cmdResult) {
        if (!(cmdResult instanceof Set<?> set)) {
            return;
        }
        for (Object o : set) {
            if (!(o instanceof ZSetOperations.TypedTuple<?> tuple)) {
                continue;
            }
            Object member = tuple.getValue();
            // B 侧 {@code :370} 是裸断言 `z.Member.(string)`（member 非字符串会 panic）。
            // 此处改为跳过：go-redis 读 ZSet 成员恒为 string，该断言在实践中不会失败，
            // 而 B 侧的 panic 发生在 periodicMerge goroutine、不在 sarama 的 recover 范围内 ⇒ 直接崩进程。
            // 跳过是同一「防御意图」下唯一可用的 Java 表达（与 loadFromRedis 的 `, ok` 保护同款）。
            if (!(member instanceof String articleId)) {
                continue;
            }
            Double score = tuple.getScore();
            if (score == null) {
                continue;
            }
            scoreMap.merge(articleId, score, Double::sum);
        }
    }

    /**
     * 对应 B 侧 {@code :395-398}：{@code DEL shadow} + {@code ZADD shadow}，错误同样被忽略。
     *
     * @param merged 合并后的条目
     */
    private void writeShadow(List<Map.Entry<String, Double>> merged) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                merged.stream()
                        .map(e -> (ZSetOperations.TypedTuple<String>) ZSetOperations.TypedTuple
                                .of(e.getKey(), e.getValue()))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        try {
            redis.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) {
                    operations.delete(HotRedisSnapshotStore.MERGE_SHADOW_KEY);
                    operations.opsForZSet().add(HotRedisSnapshotStore.MERGE_SHADOW_KEY, tuples);
                    return null;
                }
            });
        } catch (RuntimeException e) {
            log.warn("[Merge] write pipeline failed: {}", e.getMessage());
        }
    }

    /**
     * 当前实例 ID（测试观测用；B 侧的 {@code instanceID} 是锁的 value）。
     *
     * @return 实例 ID
     */
    String instanceId() {
        return instanceId;
    }

    /**
     * 是否持锁（测试观测用）。
     *
     * @return 是否持锁
     */
    boolean lockHeld() {
        return lockHeld;
    }
}
