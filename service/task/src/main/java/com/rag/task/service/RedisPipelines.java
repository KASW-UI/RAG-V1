package com.rag.task.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * <p><b>Redis 批量（pipeline）助手</b> —— 对应 Go 侧到处出现的
 * {@code pipe := rdb.Pipeline(); pipe.XXX(...); pipe.Exec(ctx)} 两行式写法。</p>
 *
 * <h2>🔴 Go 的 {@code Pipeline()} 不是事务</h2>
 * <p>本类<b>绝不</b>在回调里调 {@code operations.exec()}：
 * <ul>
 *   <li><b>Go 出处</b>：go-redis v9.20.0 {@code redis.go:1508-1513}
 *       {@code Client.Pipeline()} 的 {@code exec} 是 {@code pipelineExecer(c.processPipelineHook)}
 *       —— <b>不包 MULTI/EXEC</b>；而 {@code redis.go:1521-1528} 的
 *       {@code Client.TxPipeline()} 才调 {@code wrapMultiExec(ctx, cmds)}。
 *       源码注释原文（{@code pipeline.go:18-20}）：
 *       <i>"Pay attention, that Pipeline is not a transaction"</i>。
 *       Go 侧两个 sink 用的都是 {@code c.rdb.Pipeline()}
 *       （{@code like_sink.go:167}、{@code like_sink.go:210}）⇒ <b>Go 不开事务</b>。</li>
 *   <li><b>Spring 出处</b>：spring-data-redis 3.5.9 的
 *       {@code LettuceConnection#openPipeline()} <b>只置标志位</b>、<b>不发 MULTI</b>；
 *       {@code exec()} 在 pipeline 模式下直接向服务端发 {@code EXEC}
 *       ⇒ 服务端回 {@code ERR EXEC without MULTI}。</li>
 *   <li><b>框架明文契约</b>：{@code RedisOperations#executePipelined(SessionCallback)} 的 javadoc 写着
 *       <i>"the callback <b>cannot</b> return a non-null value as it gets overwritten by the pipeline"</i>、
 *       <i>"SessionCallback#execute(RedisOperations) itself <b>must return null</b>"</i>
 *       —— 在回调里调 {@code exec()} 本身就违反契约。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么不用低层 {@code RedisStringCommands}</h2>
 * <p>直觉写法是 {@code redis.executePipelined((RedisCallback<Object>) conn ->
 * conn.stringCommands().setNX(...))}，在本仓解析出的
 * <b>Spring Data Redis 3.5.9 下编译不过</b>：
 * 低层 {@code RedisStringCommands} 只有 {@code setNX(byte[], byte[])}，
 * <b>没有带 TTL 的重载</b>（带 TTL 的版本只在高层的
 * {@code ValueOperations.setIfAbsent(K, V, Duration)} 上），
 * 而 {@code hSet} 还要手工把 String map 转成 {@code Map<byte[],byte[]>}。
 * 高层 API 两边都覆盖。
 *
 * <h2>⚠️ 为什么用 {@link SessionCallback} 而不是 lambda</h2>
 * <p>{@code SessionCallback#execute} 是<b>泛型方法</b>
 * {@code <K,V> T execute(RedisOperations<K,V>)}，
 * <b>不能作为 lambda 的目标类型</b>（编译器报「lambda 表达式的函数描述符无效」）。
 * 这里用匿名类实现并做一次受控的 {@code (RedisOperations) operations} 转型 ——
 * 转型是安全的：调用方 {@code StringRedisTemplate} 的 K/V 恒为 {@code String}。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.service} → {@code com.rag.task.service}（D7）；</li>
 *         <li><b>Spring Data Redis 4.0.7 → 3.5.9 的 API 面核对</b>（D14）：
 *             {@code SessionCallback#execute} 的泛型签名<b>两版一致</b>
 *             （javap 实测 spring-data-redis-3.5.9）⇒ <b>匿名类写法逐字沿用</b>；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 「抛 vs 吞」的两种语义、结果按序返回、
 *       {@code toBooleans} 的事务模式防呆，逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/RedisPipelines.java:65-69（类声明）
 * B 出处：…/service/RedisPipelines.java:90-103（exec：出错抛出去）
 * B 出处：…/service/RedisPipelines.java:118-137（execQuietly：吞错）
 * B 出处：…/service/RedisPipelines.java:149-161（toBooleans + 事务模式防呆）
 *       </pre></li>
 * </ol>
 */
public final class RedisPipelines {

    private static final Logger log = LoggerFactory.getLogger(RedisPipelines.class);

    private RedisPipelines() {
        // 纯静态工具，禁止实例化
    }

    /**
     * 在<b>一个</b> pipeline 里执行一组命令，返回<b>与命令同序</b>的结果列表。
     *
     * <p>对应 Go {@code pipe.XXX(...)} + {@code _, err := pipe.Exec(ctx)}，
     * 并保留 Go 侧的关键用法：<b>exec 之后按下标读每条命令的结果</b>
     * （{@code like_sink.go:200-205} 的 {@code it.cmd.Result()}）。
     *
     * <p>⚠️ <b>出错会抛出去</b> —— 与 Go 的 {@code return err} 同构。
     * Go 出处：{@code like_sink.go:177-181}（SETNX 闸门）、{@code like_sink.go:223-225}
     * （lazyInit 的 HSET）、{@code article_sink.go:244-248}、{@code article_sink.go:308-310}。
     * 这些点出错都会<b>中止后续步骤</b>（Go {@code flushOnce} 的
     * {@code like_sink.go:134-142}），所以这里必须抛，不能吞。
     *
     * @param body 命令序列（会被包成一批一次发出 = Go 的 pipeline）
     */
    public static List<Object> exec(StringRedisTemplate redis,
                                    Consumer<RedisOperations<String, String>> body) {
        return redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                body.accept((RedisOperations) operations);
                // ⚠️ 必须返回 null：框架契约（RedisOperations#executePipelined 的 javadoc）。
                //    这里**绝不能**再调 operations.exec() —— pipeline 模式没发过 MULTI，
                //    发了就是 ERR EXEC without MULTI（见类注释）。
                return null;
            }
        });
    }

    /**
     * 执行一组命令并<b>吞掉错误</b> —— 对应 Go {@code _, _ = pipe.Exec(ctx)}
     * （{@code get_task_logic.go:73-76}，注释写着「回填失败不影响返回」）。
     *
     * <p>⚠️ 与 {@link #exec} 的区别<b>不是可有可无的风格差异</b>，而是行为差异：
     * Go 的回填路径明确吞错（继续往下走），而 lazyInit / flushRedis 路径明确抛错
     * （整个 batch 停手）。用错会导致两侧最终态分叉，详见各调用点的注释。
     *
     * <p>⚠️ 命令本身仍然<b>全部发出并执行</b>（pipeline 已一次性刷给服务端），
     * 吞掉的只是「读结果时的第一个错误」—— 与 Go 的 {@code pipe.Exec(ctx)} 语义一致。
     *
     * @param body 命令序列
     */
    public static void execQuietly(StringRedisTemplate redis,
                                   Consumer<RedisOperations<String, String>> body) {
        try {
            redis.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    body.accept((RedisOperations) operations);
                    return null;   // ⚠️ 同上：框架契约要求返回 null
                }
            });
        } catch (RuntimeException e) {
            // ⚠️ Go 这里完全不记日志（`_, _ = pipe.Exec(ctx)`）。
            //    这里补一条 WARN 是**表达层**的选择：吞掉异常会让调用方的 catch 再也收不到信号，
            //    没有日志就等于把故障彻底静音了 —— 那正是本次事故难查的原因。
            //    不影响任何对外可见行为。
            log.warn("redis pipeline failed (ignored, same as Go `_, _ = pipe.Exec(ctx)`): err={}",
                    e.getMessage());
        }
    }

    /**
     * 把 {@code List<Object>} 的结果转成按序的布尔列表（用于批量 SETNX 的闸门判定）。
     *
     * <p>⚠️ <b>依赖 pipeline（非事务）模式</b>：这种模式下每条命令的结果是
     * <b>单个 {@code Boolean}</b>（真 Redis 实测：{@code [true, true, true]}，
     * 元素类型 {@code java.lang.Boolean}）；若是 MULTI/EXEC 事务模式，
     * 每条结果是 {@code [value, ok]} 这样的 {@code List}，本方法会<b>静默返回全 false</b>。
     * ⇒ 事务模式下「全部已存在」与「本批全部新建」会被混淆，且不会有任何报错。
     * <p>这正是「绿测试可能是错误答案的守卫」的另一个变体，故在此显式留痕。
     */
    public static List<Boolean> toBooleans(List<Object> raw) {
        List<Boolean> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof List) {
                throw new IllegalStateException(
                        "redis pipeline 结果出现 List 元素 ⇒ 当前是 MULTI/EXEC 事务模式，"
                                + "而 Go 的 Pipeline() 不是事务（go-redis redis.go:1508 vs TxPipeline :1521）"
                                + "；toBooleans 会把事务结果误读成全 false。");
            }
            out.add(Boolean.TRUE.equals(o));
        }
        return out;
    }
}
