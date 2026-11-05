package com.rag.hot.messaging;

import com.rag.hot.domain.model.HeavyKeeper;
import com.rag.hot.metrics.HotRpcMetrics;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p><b>全部 Redis keyspace + 分片快照 Lua + 30s 周期快照</b>。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/mq/HotRedisSnapshotStore.java}
 * （1-253 行；类声明 {@code :47-48}；4 个 key 常量 {@code :53-62}；
 * {@code PARTITION_TTL_SECONDS} {@code :68}；{@code PERIODIC_SYNC_MILLIS} {@code :71}；
 * {@code PARTITION_RENAME_SCRIPT} {@code :82-89}；字段与构造器 {@code :91-104}；
 * 两个 key 工厂 {@code :106-112}；{@code flush} {@code :130-183}；
 * {@code flushByPartition} {@code :189-191}；{@code loadInto} {@code :202-231}；
 * {@code periodicSync} {@code :241-248}；{@code redis()} {@code :251-253}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯命名 / 定位</b>改动 ——
 *       <ul>
 *         <li>包名 {@code com.rag.hot.hot.rpc.mq} → {@code com.rag.hot.messaging}（D7 包压平）；</li>
 *         <li>{@code HeavyKeeper} 的 import → {@code com.rag.hot.domain.model}（D7）；</li>
 *         <li>{@code HotRpcMetrics} 的 import → {@code com.rag.hot.metrics}（D7）。</li>
 *       </ul>
 *       <b>方法体与 Lua 脚本逐字照抄</b>（含 {@code EXISTS} 守卫、
 *       {@code EXPIRE 300} 硬编码、两个不同的过期时间、错误不抛的契约）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>4 个 Redis key 的<b>名字逐字未动</b>；</li>
 *         <li><b>Lua 脚本逐字节一致</b>；</li>
 *         <li><b>失败即计数不抛</b>的契约逐条未动。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>为什么 Redis 细节必须原样复刻</b>：本类是最容易在「等价重构」中被改坏的一面。
 * 三处不可统一的地方：
 * <ol>
 *   <li><b>两个不同的过期时间</b>：分片 key {@code hot:articles:p{i}} 的 TTL 是
 *       <b>300s 硬编码</b>（写死在 Lua 里），与配置
 *       {@code Interaction.TTL}（3600）<b>无关</b>；而合并后的大 key {@code hot:articles}
 *       才用 {@code Interaction.TTL}。⇒ 空闲超过 5 分钟的分片 key 会消失，
 *       之后 rebalance 回灌读到空。</li>
 *   <li><b>{@code EXISTS} 守卫</b>（Lua 第一行）：检查的是 <b>{@code KEYS[1] = shadow}</b>
 *       （<b>不是</b>目标分片 key！）⇒ shadow 刚被 pipeline 的 ZADD 写入 ⇒ <b>守卫恒为 1</b>
 *       ⇒ <b>首次快照不会被丢</b>。
 *       ⚠️ 「守卫检查目标分片 key ⇒ 首次快照必丢」是<b>旧注释/旧台账的错误说法</b>，
 *       已由真 Redis 守门测试实测证伪（变异验证：把守卫改成检查 KEYS[2] ⇒ 该用例立刻变红）。</li>
 *   <li><b>shadow → RENAME 的原子性</b>：先写 shadow 再 Lua 原子改名，
 *       避免合并期读到半成品。</li>
 * </ol>
 *
 * <p><b>错误契约（L3）</b>：B 侧的同步失败路径是「{@code redisSyncTotal.Inc("fail")} 后 return，
 * <b>不抛出</b>」。
 * Spring Data Redis 一律抛 {@code DataAccessException} ⇒ 本类<b>必须显式吞掉</b>，
 * 否则会把「快照失败不影响主流程」变成「消费失败触发重试」，直接改变 drop 语义。
 */
@Component
public class HotRedisSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(HotRedisSnapshotStore.class);

    /** 合并后的大 key，{@code GetHotArticles} 的唯一读取源。B 侧 {@code get_hot_articles_logic.go:16}。 */
    public static final String HOT_ARTICLES_KEY = "hot:articles";

    /** 合并期的影子榜 key。B 侧 {@code hot_handler.go:390}。 */
    public static final String MERGE_SHADOW_KEY = "hot:articles:shadow";

    /** 分区快照 key 模板。B 侧 {@code hot_handler.go:208}。 */
    private static final String PARTITION_KEY_FMT = "hot:articles:p%d";

    /** 分区影子榜 key 模板。B 侧 {@code hot_handler.go:209}。 */
    private static final String PARTITION_SHADOW_KEY_FMT = "hot:articles:p%d:shadow";

    /**
     * ⚠️ 分片 key 的 EXPIRE 秒数：<b>300 硬编码</b>，B 侧 {@code hot_handler.go:232} 写死在 Lua 里。
     * <b>不要</b>改成读 {@code Interaction.TTL}（那是给大 key 的）——那是行为变更。
     */
    public static final int PARTITION_TTL_SECONDS = 300;

    /** 周期快照间隔，B 侧 {@code hot_handler.go:245} 硬编码 30s。 */
    private static final int PERIODIC_SYNC_MILLIS = 30_000;

    /**
     * 分片快照改名脚本（B 侧 {@code hot_handler.go:227-234}）。
     *
     * <p>⚠️ {@code EXISTS} 守卫检查的是 <b>KEYS[1] = shadow</b>，<b>不是</b>目标分片 key：
     * shadow 刚被 pipeline 的 ZADD 写入 ⇒ 守卫恒为 1 ⇒ <b>首次快照不会被丢</b>。
     * KEYS[1]=shadow，KEYS[2]=分区 key。
     */
    static final RedisScript<Long> PARTITION_RENAME_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            redis.call('RENAME', KEYS[1], KEYS[2])
            redis.call('EXPIRE', KEYS[2], 300)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final HotPartitionSketchRegistry registry;
    private final HotRpcMetrics metrics;
    private final TaskExecutor taskExecutor;

    /**
     * 构造器注入。
     *
     * @param redis        Redis 模板
     * @param registry     分区 sketch 注册表
     * @param metrics      指标
     * @param taskExecutor 异步执行器（B 侧的裸 goroutine 对位物）
     */
    public HotRedisSnapshotStore(StringRedisTemplate redis,
                                 HotPartitionSketchRegistry registry,
                                 HotRpcMetrics metrics,
                                 @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.redis = redis;
        this.registry = registry;
        this.metrics = metrics;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 分片快照 key。
     *
     * @param partitionId 分区号
     * @return {@code hot:articles:p{i}}
     */
    public static String partitionKey(int partitionId) {
        return String.format(PARTITION_KEY_FMT, partitionId);
    }

    /**
     * 分片影子榜 key。
     *
     * @param partitionId 分区号
     * @return {@code hot:articles:p{i}:shadow}
     */
    public static String partitionShadowKey(int partitionId) {
        return String.format(PARTITION_SHADOW_KEY_FMT, partitionId);
    }

    /**
     * <p><b>把分区 sketch 的 TopK 落到 {@code hot:articles:p{i}}</b>。
     *
     * <p>行为逐条对齐：
     * <ol>
     *   <li>分区不存在 ⇒ 直接 return（B 侧 {@code :199-201}）</li>
     *   <li>{@code TopK()} 为空 ⇒ <b>不写</b>，旧 key 保持陈旧（B 侧 {@code :204-206}）</li>
     *   <li>pipeline：{@code DEL shadow} + {@code ZADD shadow}（B 侧 {@code :217-218}）</li>
     *   <li>Lua：{@code EXISTS} 守卫 + {@code RENAME} + {@code EXPIRE 300}</li>
     *   <li>失败 → {@code redisSyncTotal.Inc("fail")} 后 return，<b>不抛</b></li>
     * </ol>
     *
     * <p>⚠️ 与 B 侧的<b>唯一有意差异</b>：B 侧的 {@code syncToRedis} 自己按 partitionId 去
     * {@code hkMap} 里取 sketch；本方法直接接收 sketch 引用。
     * 原因见 {@link HotPartitionSketchRegistry} 的类注释（B 侧持写锁再取读锁会死锁）。
     *
     * @param partitionId 分区号
     * @param hk          该分区的 sketch（可为 {@code null} ⇒ 直接 return）
     */
    public void flush(int partitionId, HeavyKeeper hk) {
        if (hk == null) {
            return;
        }
        List<HeavyKeeper.Item> items = hk.topK();
        if (items.isEmpty()) {
            // B 侧：TopK 为空则不写，旧 key 保持陈旧
            return;
        }

        String partitionKey = partitionKey(partitionId);
        String shadowKey = partitionShadowKey(partitionId);

        Set<ZSetOperations.TypedTuple<String>> tuples =
                items.stream()
                        .map(it -> (ZSetOperations.TypedTuple<String>) ZSetOperations.TypedTuple
                                .of(it.key(), HeavyKeeper.countToScore(it.count())))
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        try {
            redis.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) {
                    operations.delete(shadowKey);
                    operations.opsForZSet().add(shadowKey, tuples);
                    return null;
                }
            });
        } catch (RuntimeException e) {
            log.error("[Sync] partition {} pipeline failed: {}", partitionId, e.getMessage(), e);
            metrics.redisSyncFail();
            return;
        }
        log.info("[Sync] partition {} pipeline success: items={}", partitionId, items.size());

        try {
            Long result = redis.execute(PARTITION_RENAME_SCRIPT,
                    List.of(shadowKey, partitionKey));
            if (result == null || result == 0L) {
                // result==0 = EXISTS 守卫未命中（shadow 在 pipeline 写完之后、Lua 执行之前消失
                // —— 真实的竞态/外部删除）或真失败；守卫查的是 KEYS[1]=shadow 而非目标分片 key，
                // 所以「首次快照必丢」是旧注释/旧台账的错误说法，已被真 Redis 实测证伪。
                // B 侧同样走 fail 分支。
                log.error("[Sync] partition {} rename failed: result={}", partitionId, result);
                metrics.redisSyncFail();
            } else {
                metrics.redisSyncSuccess();
            }
        } catch (RuntimeException e) {
            log.error("[Sync] partition {} rename failed: {}", partitionId, e.getMessage(), e);
            metrics.redisSyncFail();
        }
    }

    /**
     * <p><b>按 partitionId 自行取 sketch 的形态</b>，供 {@link #periodicSync()} 与消费侧的触发快照使用。
     *
     * @param partitionId 分区号
     */
    public void flushByPartition(int partitionId) {
        flush(partitionId, registry.heavyKeeperFor(partitionId));
    }

    /**
     * <p><b>从 Redis 回灌</b>：{@code ZREVRANGE hot:articles:p{i} 0 -1}，
     * 把<b>合并后的累计分数当权重</b>灌进 sketch。
     *
     * <p>⚠️ <b>这是有损语义，必须保留</b>（B 侧既有行为，非 bug）：
     * sketch 的桶计数与真实发生次数之间<b>没有可逆关系</b>，
     * 回灌得到的是一个<b>新估计</b>而不是原值。
     * 换成「精确恢复」会改变 rebalance 后热度的连续性手感。
     *
     * @param partitionId 分区号
     * @param hk          目标 sketch
     */
    public void loadInto(int partitionId, HeavyKeeper hk) {
        String key = partitionKey(partitionId);
        Set<ZSetOperations.TypedTuple<String>> items;
        try {
            items = redis.opsForZSet().reverseRangeWithScores(key, 0, -1);
        } catch (RuntimeException e) {
            log.error("[Hot] partition {} load failed: {}", partitionId, e.getMessage(), e);
            return;
        }
        if (items == null || items.isEmpty()) {
            log.info("[Hot] loaded 0 articles for partition {}", partitionId);
            return;
        }

        int loaded = 0;
        for (ZSetOperations.TypedTuple<String> item : items) {
            Object member = item.getValue();
            if (!(member instanceof String articleId)) {
                // B 侧 {@code :275-278} 的 `, ok` 保护：非字符串 member 跳过
                continue;
            }
            Double score = item.getScore();
            if (score == null) {
                continue;
            }
            hk.add(articleId, HeavyKeeper.scoreToCount(score));
            loaded++;
        }
        log.info("[Hot] loaded {} articles for partition {}", loaded, partitionId);
    }

    /**
     * <p><b>30s 周期快照</b>：对每个已持有分区起一个异步任务同步。
     *
     * <p>B 侧的「每分区一个 goroutine」在 C 侧映射为 Spring 的 {@link TaskExecutor} 提交
     * ⇒ 单个分区的 Redis 慢不会拖住其他分区，也不会推迟下一轮 30s tick。
     */
    @Scheduled(fixedRate = PERIODIC_SYNC_MILLIS)
    public void periodicSync() {
        for (int pid : registry.partitionIds()) {
            // 先在锁外取 sketch 引用（B 侧同款），再异步落盘
            HeavyKeeper hk = registry.heavyKeeperFor(pid);
            taskExecutor.execute(() -> flush(pid, hk));
        }
    }

    /**
     * 供测试与合并侧复用的 Redis 模板访问（package-private，非生产 API）。
     *
     * @return Redis 模板
     */
    StringRedisTemplate redis() {
        return redis;
    }
}
