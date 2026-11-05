package com.rag.task.messaging;

import com.rag.common.observability.MqConsumer;
import com.rag.task.config.TaskSinkProperties;
import com.rag.task.domain.entity.TaskProgressEntity;
import com.rag.task.mapper.UserLikeCountMapper;
import com.rag.task.mapper.UserLikeCountMapper.UserLikeCountRow;
import com.rag.task.service.RedisPipelines;
import com.rag.task.service.TaskProgressWriter;
import com.rag.task.service.TaskRedisKeys;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <p><b>用户点赞任务进度落库</b> —— 消费点赞进度事件、聚合用户计数、按 1 秒节拍 flush 到 Redis + PG。</p>
 *
 * <h2>数据流</h2>
 * <pre>
 * task.logs.filtered.User  ──Consume(key=uid, val=十进制计数)──▶ delta[uid] = max(旧值, 新值)
 *                                                                          │
 *                          每 1 秒 flushOnce：                             ▼
 *   1) lazyInitTaskIfNeeded  SETNX task:init:&lt;uid&gt;:1902 → HSET + PG 插 DO NOTHING
 *   2) flushRedis            SET like:total:&lt;uid&gt; EX 90d
 *                            → total &gt;= 5 ? completeLikeGT5 : updateDoingTaskProgress
 *   3) flushPostgres         user_like_count 批量 ON CONFLICT + GREATEST
 * </pre>
 *
 * <h2>🔴 三条必须原样保留的行为</h2>
 * <ol>
 *   <li><b>delta 取 max 且在内存聚合</b>（{@code if !ok || total > current}）——
 *       所以乱序消息<b>不会</b>把计数写小。该性质贯穿 Consume 与 PG 侧的 GREATEST。</li>
 *   <li><b>单条失败不阻断整批</b>：{@code completeLikeGT5} / {@code updateDoingTaskProgress}
 *       的调用点都是 {@code _ = ...} ⇒ 逐条捕获、继续下一条。</li>
 *   <li><b>swap 之后失败会丢这一批</b>：{@code flushOnce} 先 {@code swap()} 取走 delta，
 *       再依次三步；任一步返回 error 都<b>提前 return</b>，
 *       而 loop 用 {@code _ = c.flushOnce(ctx)} <b>忽略</b>该 error
 *       ⇒ 该批 delta 直接消失，没有重放也没有落盘。
 *       这是 Go 既有的数据丢失窗口，<b>不修</b>。</li>
 * </ol>
 *
 * <h2>⚠️ 任务常量不来自 {@code TaskDefs}</h2>
 * <p>本类用 {@code like_sink.go:31-34} 自己的常量（taskID=1902 / target=5），
 * 与 {@code TaskDefs.ALL_TASK_DEFS} 是<b>两套独立真相</b>，理由见 {@code TaskDefs} 类注释。</p>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.mq} → {@code com.rag.task.messaging}（D7）；</li>
 *         <li><b>类名加域前缀</b>：B 侧 {@code UserLikeProgressSink} → C 侧
 *             {@code TaskUserLikeProgressSink}（映射表 §1.2 已登记「去掉原前缀 / 补域前缀」）；</li>
 *         <li><b>观测 API</b>：B 侧 {@code MqConsumer.traceConsumer(Context.root(), …)}
 *             （OTel {@code Context}）⇒ 本仓共享层是
 *             {@code MqConsumer.traceConsumer(ObservationRegistry, …)} ⇒ <b>纯接线替换</b>
 *             （与 article / like 域同款）；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— delta 取 max、单条失败不阻断整批、
 *       swap 后失败丢批、SETNX 闸门、三步刷盘顺序、奖励分值 5、
 *       HSET 字段名与取值逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mq/UserLikeProgressSink.java:63-102（类声明 + 构造 + 常量）
 * B 出处：…/mq/UserLikeProgressSink.java:116-146（listener：key 解析失败抛异常）
 * B 出处：…/mq/UserLikeProgressSink.java:148-178（consumeBody：uid<=0 / total<=0 正常返回；解析失败保默认值 1）
 * B 出处：…/mq/UserLikeProgressSink.java:189-214（flushTick + flushOnShutdown）
 * B 出处：…/mq/UserLikeProgressSink.java:222-255（flushOnce + swap）
 * B 出处：…/mq/UserLikeProgressSink.java:272-316（lazyInitTaskIfNeeded）
 * B 出处：…/mq/UserLikeProgressSink.java:335-364（pipelineSetNx + pipelineHSet）
 * B 出处：…/mq/UserLikeProgressSink.java:384-423（flushRedis + advanceProgress）
 * B 出处：…/mq/UserLikeProgressSink.java:431-490（updateDoing / completeLike / invalidateTaskCache）
 * B 出处：…/mq/UserLikeProgressSink.java:499-546（flushPostgres + chunked + parseKey）
 *       </pre></li>
 * </ol>
 */
@Component
public class TaskUserLikeProgressSink {

    private static final Logger log = LoggerFactory.getLogger(TaskUserLikeProgressSink.class);

    /** OTEL span 的 destination —— Go {@code like_sink.go:149} 写死的逻辑名，<b>不是</b> topic 名。 */
    private static final String SPAN_DESTINATION = "user_like_sink";

    private final StringRedisTemplate redis;
    private final TaskProgressWriter progressWriter;
    private final UserLikeCountMapper userLikeCountMapper;
    private final TaskRewardPublisher rewardPublisher;
    private final TaskSinkProperties properties;

    /**
     * 观测注册表 —— 可能不存在 ⇒ 不产出观测，但错误分类不变。
     *
     * <p>⚠️ 用 {@link ObjectProvider} 而不是直接注入：
     * {@code ObservationRegistry} 只在 {@code spring-boot-actuator} 在场时才有该 bean，
     * 直接注入会让「没装 actuator」的部署直接起不来。</p>
     */
    private final ObjectProvider<ObservationRegistry> observationRegistry;

    private final long taskId;
    private final long target;
    private final Duration ttl;

    /**
     * 内存 delta 聚合 —— 对应 Go {@code delta map[int64]int64} + {@code mu sync.Mutex}。
     *
     * <p>Go 用 {@code mu.Lock()} 保护读-改-写；这里用 {@link ConcurrentHashMap#merge}
     * 让"取 max"这一步原子化，语义等价。</p>
     */
    private final Map<Long, Long> delta = new ConcurrentHashMap<>();

    public TaskUserLikeProgressSink(StringRedisTemplate redis,
                                     TaskProgressWriter progressWriter,
                                     UserLikeCountMapper userLikeCountMapper,
                                     TaskRewardPublisher rewardPublisher,
                                     TaskSinkProperties properties,
                                     ObjectProvider<ObservationRegistry> observationRegistry) {
        this.redis = redis;
        this.progressWriter = progressWriter;
        this.userLikeCountMapper = userLikeCountMapper;
        this.rewardPublisher = rewardPublisher;
        this.properties = properties;
        this.observationRegistry = observationRegistry;
        this.taskId = properties.getLike().getTaskId();
        this.target = properties.getLike().getTarget();
        this.ttl = properties.getLike().getInitTtl();
    }

    // ==================== 消费 ====================

    /**
     * 消费一条用户点赞进度 —— 对应 Go {@code like_sink.go:81-108} 的 {@code Consume}，
     * 整体包在观测 span 里（span 名与属性见 {@link TaskTraceAttributes}）。
     *
     * <p>⚠️ <b>key 解析失败必须抛异常</b>：Go 是 {@code return err}
     * ⇒ kq <b>不提交位点</b>（{@code go-queue/kq/queue.go:235-238}）；
     * Java 抛异常 ⇒ 位点不提交。方向一致。
     * <p>⚠️ 反之 {@code uid <= 0} 与 {@code total <= 0} 在 Go 里是 {@code return nil}
     * ⇒ <b>照常提交位点</b>（丢弃该条）。这里正常返回，不抛。</p>
     */
    @KafkaListener(id = "task-user-progress-sink",
            topics = "${task.kafka.out-user-topic}",
            groupId = "${task.kafka.group-filter-user}",
            // ⚠️ kq 硬编码 Offset: "latest"（initUserFilter.go:47）
            //
            // 🔴 2026-10-24 修正：必须是**点分**的 `auto.offset.reset`。
            //    `@KafkaListener(properties=...)` 不走 Boot 松散绑定，原样传给 Kafka；
            //    连字符形式 `auto-offset-reset` **不是 Kafka 的合法配置名 ⇒ 被直接忽略**
            //    （实测：consumer config dump 里 `auto.offset.reset = latest`，
            //      且 Kafka 打出 "These configurations '[auto-offset-reset]' were supplied but are not used yet."）
            //    ⇒ 本处写的 latest 此前**从未生效**；它「看起来对」只是因为
            //      全局默认恰好也是 latest ⇒ **属于碰巧对，不是配置生效**。
            //    ⇒ 显式写成点分形式后，即使全局默认将来改成 earliest，本处也仍是 latest。
            properties = "auto.offset.reset=latest",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record) {
        String key = record.key() == null ? "" : record.key();
        String value = record.value();

        Throwable err = MqConsumer.traceConsumer(
                observationRegistry.getIfAvailable(), "task-rpc", "LikeSinkConsumer.Consume",
                TaskTraceAttributes.message(SPAN_DESTINATION, key),
                ctx -> {
                    consumeBody(key, value);
                    return null;
                });

        if (err != null) {
            throw asRuntime(err);
        }
    }

    private void consumeBody(String key, String value) {
        // Go: strconv.ParseInt(key, 10, 63) —— ⚠️ bitSize 63 ⇒ 可表示范围 [-2^62, 2^62-1]，
        //     比 int64 窄一半。Java 的 long 是 64 位 ⇒ 需显式补这道范围检查，
        //     否则 2^62~2^63-1 的 key 在两侧会得到相反结论（Go 丢弃 / Java 接受）。
        long userId = parseKey(key);

        // Go: if userID <= 0 { return nil } ⇒ 正常返回，提交位点
        if (userId <= 0L) {
            return;
        }

        // Go: total := int64(1)
        //     if val != "" { if n, err := ParseInt(val,10,64); err == nil { total = n } }
        // ⚠️ 解析失败**保留默认值 1**（不是丢弃）—— 这是本 sink 最容易被写错的一处
        long total = 1L;
        if (value != null && !value.isEmpty()) {
            try {
                total = Long.parseLong(value);
            } catch (NumberFormatException e) {
                total = 1L;
            }
        }

        // Go: if total <= 0 { return nil }
        if (total <= 0L) {
            return;
        }

        // Go: if current, ok := c.delta[userID]; !ok || total > current { c.delta[userID] = total }
        delta.merge(userId, total, Math::max);
    }

    // ==================== 定时刷盘 ====================

    /**
     * 每秒刷一次 —— 对应 Go {@code like_sink.go:110-126} 的
     * {@code ticker := time.NewTicker(c.flushEvery)}。
     *
     * <p>⚠️ Go 的 loop 忽略 flushOnce 的返回值（{@code _ = c.flushOnce(ctx)}），
     * 这里同样<b>不向上抛</b>：定时任务抛异常只会让后续 tick 被跳过。</p>
     */
    @Scheduled(fixedRateString = "${task.definitions.like.flush-every-ms:1000}")
    public void flushTick() {
        try {
            flushOnce();
        } catch (Exception e) {
            log.error("user like progress sink flush failed (delta for this batch is dropped, "
                    + "same as Go's swap-then-ignore behaviour)", e);
        }
    }

    /**
     * 容器关闭前的最后一次刷盘 —— 对应 Go {@code like_sink.go:117-119} 的
     * {@code case <-ctx.Done(): _ = c.flushOnce(context.Background()); return}。
     *
     * <p>⚠️ Go 用 {@code context.Background()}（不是被取消的 ctx），
     * 刻意让最后一次刷盘不受关闭信号影响；Spring 的 {@link PreDestroy} 时机上等价。</p>
     */
    @PreDestroy
    public void flushOnShutdown() {
        try {
            flushOnce();
            log.info("user like progress sink final flush done, remaining delta={}", delta.size());
        } catch (Exception e) {
            log.error("user like progress sink final flush failed", e);
        }
    }

    /**
     * 刷一批 —— 对应 Go {@code like_sink.go:128-145} 的 {@code flushOnce}。
     *
     * <p>⚠️ 顺序不可换：{@code lazyInit → flushRedis → flushPostgres}，
     * 且<b>任一步失败即整体返回</b>（丢掉这一批 delta，见类注释第 3 条）。</p>
     */
    void flushOnce() {
        Map<Long, Long> batch = swap();
        if (batch.isEmpty()) {
            return;   // Go: if len(batch) == 0 { return nil }
        }

        Throwable err = MqConsumer.traceConsumer(
                observationRegistry.getIfAvailable(), "task-rpc", "LikeSinkConsumer.Flush",
                TaskTraceAttributes.flush(SPAN_DESTINATION, batch.size()),
                ctx -> {
                    lazyInitTaskIfNeeded(batch);
                    flushRedis(batch);
                    flushPostgres(batch);
                    return null;
                });

        if (err != null) {
            throw asRuntime(err);
        }
    }

    /**
     * Go {@code swap()}：取走当前 delta 并换成一张空表。
     *
     * <p>⚠️ 与 Go 一样是"整体换新表"，所以 swap 之后本批失败即丢失，不重放。</p>
     */
    private Map<Long, Long> swap() {
        if (delta.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> batch = new HashMap<>(delta);
        delta.clear();
        return batch;
    }

    // ==================== 第一步：懒初始化 ====================

    /**
     * 对应 Go {@code like_sink.go:165-241} 的 {@code lazyInitTaskIfNeeded}。
     *
     * <p>两步，缺一不可：
     * <ol>
     *   <li>对每个 uid 批量 {@code SETNX task:init:<uid>:1902 "1" EX 90d}，
     *       <b>只有返回 true 的</b>算「新用户」；</li>
     *   <li>对新用户写进度 Hash（doing/0/target/createAt/updateAt），并在 PG 插一条 DO NOTHING。</li>
     * </ol>
     *
     * <p>⚠️ SETNX 是<b>每人每任务一次</b>的闸门：已存在的 uid 既不会被 HSET 重置，
     * 也不会触发 PG 插入。所以「90 天后重新 init」是 Go 的既定行为，不改。</p>
     */
    private void lazyInitTaskIfNeeded(Map<Long, Long> batch) {
        List<Long> uids = new ArrayList<>(batch.keySet());

        // ---- 步骤 1：SETNX 闸门（按 redisPipeMax 分块执行 pipeline）----
        List<Boolean> setNxResults = new ArrayList<>(uids.size());
        for (List<Long> chunk : chunked(uids, properties.getLike().getRedisPipeMax())) {
            setNxResults.addAll(pipelineSetNx(chunk));
        }

        // Go: newUsers 只收 `err == nil && ok` 的
        List<Long> newUsers = new ArrayList<>();
        for (int i = 0; i < uids.size(); i++) {
            if (i < setNxResults.size() && Boolean.TRUE.equals(setNxResults.get(i))) {
                newUsers.add(uids.get(i));
            }
        }
        if (newUsers.isEmpty()) {
            return;   // Go: if len(newUsers) == 0 { return nil }
        }

        // ---- 步骤 2a：HSET 进度 Hash ----
        // ⚠️ 字段名 status/progress/target/createAt/updateAt 是 Go 写死的；
        //    Redis 协议里 value 都是字符串，所以 progress/target 写 "0"/"5"。
        long now = Instant.now().getEpochSecond();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", "doing");
        fields.put("progress", "0");
        fields.put("target", String.valueOf(target));
        fields.put("createAt", String.valueOf(now));
        fields.put("updateAt", String.valueOf(now));
        pipelineHSet(newUsers, uid -> TaskRedisKeys.likeTaskProgress(uid, taskId), fields);

        // ---- 步骤 2b：PG 插一条初始行（DO NOTHING）----
        List<TaskProgressEntity> records = new ArrayList<>(newUsers.size());
        for (Long uid : newUsers) {
            TaskProgressEntity e = new TaskProgressEntity();
            e.setUserId(uid);
            e.setTaskId(taskId);
            e.setStatus("doing");
            e.setProgress(0L);
            e.setTarget(target);
            records.add(e);
        }
        progressWriter.lazyInit(records);
    }

    /**
     * 批量 {@code SETNX ... "1" EX ttl}，返回与入参<b>同序</b>的布尔结果。
     *
     * <p>⚠️ 用 {@code executePipelined(SessionCallback)} 而不是低层
     * {@code RedisStringCommands}：低层接口<b>只有 {@code setNX(byte[],byte[])}，没有带 TTL 的重载</b>，
     * 而带 TTL 的版本只在高层的 {@code ValueOperations.setIfAbsent(K, V, Duration)} 上。
     * SessionCallback 恰好两边都满足：一条连接里发一批命令（= Go 的 pipeline），
     * 且 {@code executePipelined} <b>自己</b>返回<b>与命令同序</b>的结果列表 ——
     * 而 Go 正是靠 {@code it.cmd.Result()} 在 {@code pipe.Exec} 之后按下标配对的。</p>
     *
     * <p>⚠️ <b>结果元素是单个 {@code Boolean}，不是事务模式下的 {@code [value, ok]} 数组</b>
     * （真 Redis 实测：{@code [true, true, true]}，元素类型 {@code java.lang.Boolean}）——
     * 因为 Go 的 {@code Pipeline()} 不是事务。
     * {@link RedisPipelines#toBooleans} 依赖这一点，已在那边加了防呆。</p>
     */
    private List<Boolean> pipelineSetNx(List<Long> chunk) {
        return RedisPipelines.toBooleans(RedisPipelines.exec(redis, ops -> {
            for (Long uid : chunk) {
                ops.opsForValue().setIfAbsent(TaskRedisKeys.likeTaskInit(uid, taskId), "1", ttl);
            }
        }));
    }

    /**
     * 批量 HSET 同一组字段到各自的进度 Hash。
     *
     * <p>⚠️ <b>出错必须抛出，不能吞</b>：Go 出处 {@code like_sink.go:210-225} ——
     * {@code pipe2 := c.rdb.Pipeline()} … {@code if _, err := pipe2.Exec(ctx); err != nil { return err }}。
     * 而 Go 的 {@code flushOnce}（{@code like_sink.go:134-142}）在
     * {@code lazyInitTaskIfNeeded} 出错时<b>直接 return</b>，后面的
     * {@code flushRedis} / {@code flushPostgres} <b>都不会执行</b>。</p>
     *
     * <p>⇒ 若这里吞掉错误，C 侧会继续往下写 PG 初始行，最终变成
     * <b>「PG 有初始行、Redis 进度 Hash 缺失」</b>，与 Go 的最终态分叉。
     * B 侧 Kafka 端到端对拍时发现并修正（此前误用 {@code execQuietly}）。</p>
     */
    private void pipelineHSet(List<Long> chunk,
                              java.util.function.LongFunction<String> keyBuilder,
                              Map<String, String> fields) {
        RedisPipelines.exec(redis, ops -> {
            for (Long uid : chunk) {
                ops.opsForHash().putAll(keyBuilder.apply(uid), fields);
            }
        });
    }

    // ==================== 第二步：Redis 计数 + 进度推进 ====================

    /**
     * 对应 Go {@code like_sink.go:256-293} 的 {@code flushRedis}。
     *
     * <p>⚠️ Go 的结构是「<b>每个分块 Exec 成功之后</b>，立刻跑一遍整批的进度推进」：
     * <pre>
     * _, err := pipe.Exec(ctx)
     * if err != nil { …; return err }            // ← 该分块后续处理被跳过
     * for uid, total := range batch { … }         // ← 遍历的是**整批**，不是刚 exec 的分块
     * </pre>
     * 外层 exec 被调用 n 次、内层循环被重复执行 n 次。因为推进操作幂等
     * （SETNX 闸门 + max 合并），重复执行不改变最终结果，
     * 但会<b>多做</b> DB 写与 XADD。此处按 Go 原样保留，不"优化"。</p>
     *
     * <p>⚠️ Go 的分块条件是 {@code if n > c.redisPipeMax}（严格大于），
     * 而 lazyInit 用的是 {@code size >= c.redisPipeMax}。两处都照抄。</p>
     */
    private void flushRedis(Map<Long, Long> batch) {
        int max = properties.getLike().getRedisPipeMax();
        int n = 0;
        for (Map.Entry<Long, Long> e : batch.entrySet()) {
            pipelineSetLikeTotal(e.getKey(), e.getValue());
            n++;
            if (n > max) {
                advanceProgress(batch);
                n = 0;
            }
        }
        if (n > 0) {
            advanceProgress(batch);
        }
    }

    /** 单条 {@code SET like:total:<uid> = total EX 90d}。 */
    private void pipelineSetLikeTotal(Long uid, Long total) {
        redis.opsForValue().set(TaskRedisKeys.userLikeTotal(uid), String.valueOf(total), ttl);
    }

    /** 对整批做进度推进 —— 对应 Go {@code flushRedis} 里 exec 成功后的那个 for 循环。 */
    private void advanceProgress(Map<Long, Long> batch) {
        for (Map.Entry<Long, Long> e : batch.entrySet()) {
            long uid = e.getKey();
            long total = e.getValue();
            try {
                if (total >= target) {
                    completeLike(uid, total);
                } else {
                    updateDoingTaskProgress(uid, total);
                }
            } catch (Exception ex) {
                // ⚠️ Go 是 `_ = c.completeLikeGT5(...)` / `_ = c.updateDoingTaskProgress(...)`
                //    ⇒ 单条失败只影响该条，继续下一条。这里必须同样吞掉。
                log.warn("advance user like task progress failed (ignored, same as Go): "
                        + "uid={} total={} err={}", uid, total, ex.getMessage());
            }
        }
    }

    /**
     * 推进"进行中"的进度 —— 对应 Go {@code like_sink.go:295-326}。
     *
     * <p>顺序：<b>先写 PG，再删缓存</b>。反过来会在两者之间崩溃时留下一个
     * 已经过期的缓存值（读路径会返回旧值直到 TTL 到期）。</p>
     */
    private void updateDoingTaskProgress(long uid, long total) {
        // ⚠️ 夹取在 TaskProgressWriter#updateDoing 内（Go 在 like_sink.go:296-301）
        progressWriter.updateDoing(uid, taskId, target, total);
        invalidateTaskCache(uid);
    }

    /**
     * 标记完成 —— 对应 Go {@code like_sink.go:336-364} 的 {@code completeLikeGT5}。
     *
     * <p>四步，<b>顺序即契约</b>：
     * <ol>
     *   <li>{@code SETNX task:done:<uid>:1902 "1" EX 90d} —— 每人每任务只过一次；
     *       <b>返回 false（已存在）就整体返回</b>，不重发奖励、不重写 PG；</li>
     *   <li>HSET 进度 Hash 为 done（status/doneAt/progress/updateAt）；</li>
     *   <li>{@code XADD stream:task:reward}（见 {@link TaskRewardPublisher}）；</li>
     *   <li>PG 标记完成，最后删缓存。</li>
     * </ol>
     *
     * <p>⚠️ Go 在第 2 步<b>检查</b> HSET 的 error，而文章路<b>忽略</b>它 ——
     * 两处不同，两个 sink 各自保留。</p>
     *
     * @param total 当前累计数；⚠️ Go 签名里有它，但写进 Hash 的 progress 是
     *              <b>target</b>（源码注释「你也可以写成 5 或 likeTotal，看你语义」）。
     *              本方法<b>不使用</b>该参数，保持 Go 的实际写入值。
     */
    private void completeLike(long uid, long total) {
        Boolean setNx = redis.opsForValue()
                .setIfAbsent(TaskRedisKeys.likeTaskDone(uid, taskId), "1", ttl);
        // Go: ok, err := SetNX(...); if err != nil || !ok { return err }
        //      ⚠️ !ok 时返回 nil（无错误）⇒ 上层视为成功，但后续三步全部跳过
        if (!Boolean.TRUE.equals(setNx)) {
            return;
        }

        long now = Instant.now().getEpochSecond();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", "done");
        fields.put("doneAt", String.valueOf(now));
        fields.put("progress", String.valueOf(target));
        fields.put("updateAt", String.valueOf(now));
        redis.opsForHash().putAll(TaskRedisKeys.likeTaskProgress(uid, taskId), fields);

        // ⚠️ Go: if err := c.rewardProduct.Enqueue(ctx, ev); err != nil { return err }
        //    ⇒ 奖励发不出去 ⇒ **后面的 PG 标记与缓存失效都不执行**
        rewardPublisher.enqueue(String.valueOf(uid), String.valueOf(taskId), 5L);

        progressWriter.markDone(uid, taskId, target);
        invalidateTaskCache(uid);
    }

    /**
     * 删除 {@code task:progress:<uid>} 缓存 —— 对应 Go {@code like_sink.go:328-334}。
     *
     * <p>⚠️ 这正是 {@code GetTask} 读的 key，而本 sink 写的 Hash 是另一个 key
     * （{@code task:progress:<uid>:1902}），见 {@link TaskRedisKeys} 类注释。</p>
     */
    private void invalidateTaskCache(long uid) {
        redis.delete(TaskRedisKeys.userProgressCache(uid));
    }

    // ==================== 第三步：Postgres 计数 ====================

    /**
     * 对应 Go {@code like_sink.go:387-422} 的 {@code flushPostgres} + {@code upsertChunk}。
     *
     * <p>⚠️ 按 {@code pgBatchMax}（2000）分块，<b>任一块失败即整体返回</b>。</p>
     */
    private void flushPostgres(Map<Long, Long> batch) {
        List<UserLikeCountRow> rows = new ArrayList<>(batch.size());
        for (Map.Entry<Long, Long> e : batch.entrySet()) {
            rows.add(new UserLikeCountRow(String.valueOf(e.getKey()), e.getValue()));
        }
        for (List<UserLikeCountRow> chunk : chunked(rows, properties.getLike().getPgBatchMax())) {
            userLikeCountMapper.batchUpsertGreatest(chunk);
        }
    }

    /** 仅用于测试断言 delta 聚合行为。 */
    Map<Long, Long> snapshotDelta() {
        return new HashMap<>(delta);
    }

    private static <T> List<List<T>> chunked(List<T> src, int size) {
        List<List<T>> out = new ArrayList<>();
        if (size <= 0) {
            return out;
        }
        for (int i = 0; i < src.size(); i += size) {
            out.add(src.subList(i, Math.min(src.size(), i + size)));
        }
        return out;
    }

    private static RuntimeException asRuntime(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        return new IllegalStateException(t);
    }

    /** Go {@code strconv.ParseInt(key, 10, 63)}，失败即返回 error。 */
    private static long parseKey(String key) {
        long v;
        try {
            v = Long.parseLong(key);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid key: " + key, e);
        }
        // ⚠️ Go 的 bitSize 是 63 ⇒ 可表示范围 [-2^62, 2^62-1]
        final long bound = 1L << 62;
        if (v < -bound || v >= bound) {
            throw new IllegalArgumentException("key out of int63 range: " + key);
        }
        return v;
    }
}
