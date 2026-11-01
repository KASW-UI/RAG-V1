package com.rag.common.redis;

import org.springframework.data.redis.connection.DefaultedRedisConnection;
import org.springframework.data.redis.connection.RedisConnection;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * <b>把「请求预算耗尽 ⇒ 不再下发 Redis 命令」这一层包在 {@link RedisConnection} 上。</b>
 *
 * <h2>为什么落在「连接代理」这一层（而不是 AOP 切 {@code RedisTemplate.execute}）</h2>
 * <ul>
 *   <li><b>AOP 切 {@code execute(...)} 不成立</b>：{@code DefaultValueOperations} 等
 *       持有的是 {@code RedisTemplate} <b>本体</b>（不是代理）⇒ 内部 {@code template.execute(...)}
 *       是<b>自调用</b> ⇒ <b>绕过 Spring AOP 代理</b> ⇒ ⇒ 切面<b>根本不会触发</b>。</li>
 *   <li><b>连接代理成立</b>：{@code RedisTemplate.execute(RedisCallback)} 先
 *       {@code factory.getConnection()} ⇒ 拿到的是<b>我们的代理</b>
 *       ⇒ 回调里的 {@code connection.set(...)} 等命令<b>逐条</b>经过代理 ⇒
 *       ⇒ <b>域侧零改动</b>即生效（这正是「形态①」要的效果）。</li>
 * </ul>
 *
 * <h2>拦截哪些方法（白名单式判定，避免误伤连接生命周期）</h2>
 * <p>判定规则 = <b>方法的声明类是不是 {@link DefaultedRedisConnection}</b>
 * ⇒ 该接口上全是<b>命令</b>（javap 实测 274 个 default 方法，{@code RedisConnection} 自己只有
 * 8 个生命周期/访问器方法）⇒ ⇒
 * <ul>
 *   <li><b>命令</b>（声明在 {@code DefaultedRedisConnection}）⇒ <b>拦截</b>（查预算）；</li>
 *   <li><b>生命周期 / 访问器</b>（{@code close} / {@code isClosed} / {@code isQueueing} /
 *       {@code isPipelined} / {@code openPipeline} / {@code closePipeline} /
 *       {@code getNativeConnection} / {@code getSentinelConnection} / {@code getRedisCommands}）
 *       ⇒ <b>不拦截</b>（否则会破坏事务回滚与 pipeline 收尾）。</li>
 * </ul>
 *
 * <h2>⚠️ 两层检查的分工（2026-10-30 G4 第六次订正 · 实证）</h2>
 * <p>预算检查<b>有两处</b>，缺一不可，<b>各自对应上游的一处</b>：
 * <ul>
 *   <li><b>①「取连接 / 拨号」前</b> ⇒ {@link DeadlineConnectionFactory#getConnection()}
 *       ⇒ 对应上游的 {@code waitTurn}（<b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/redis/GoStyleRedisDeadlineConnection.java}（RAG 仓））
 *       ⇒ ⇒ <b>预算耗尽 ⇒ 连都不拨</b>（与上游同形）。</li>
 *   <li><b>②「命令下发」前</b> ⇒ <b>本类</b> ⇒ 对应上游的「<b>每条命令</b>都过一次 waitTurn」
 *       ⇒ ⇒ <b>事务绑定场景的兜底</b>：事务内复用同一条连接 ⇒ 不再走 ① ⇒ ⇒
 *       ② 保证<b>每条命令</b>仍被查一次（与上游每命令一次同形）。</li>
 * </ul>
 * <p>⚠️ <b>只留 ① 会漏（事务内第 2 条命令）</b>；<b>只留 ② 会多（为已死的请求先拨一次连接）</b>
 * ⇒ ⇒ 两处并存 ⇒ ⇒ <b>写行为与资源行为都与上游同形</b>。
 *
 * <h2>⚠️ 已知不覆盖的面（诚实边界）</h2>
 * <ul>
 *   <li><b>{@code connection.getRedisCommands().xxx(...)} 直接调底层命令 ⇒ 绕过检查</b>
 *       （本仓各域的实际写法是 {@code connection.set(...)} 这类<b>上层</b>调用 ⇒ 命中）。
 *       <p>⚠️ <b>实测细节（SDR 4.0.7）</b>：{@code DefaultedRedisConnection.get(byte[])} 的默认实现
 *       是 {@code stringCommands().get(key)} ⇒ ⇒ <b>外层</b> {@code get(byte[])} 声明在
 *       {@code DefaultedRedisConnection} ⇒ <b>命中本类的拦截判据</b>（预算检查在派发前触发）⇒
 *       ⇒ 但<b>内层</b>那个 {@code stringCommands()} 上的命令是<b>直接下发</b>的（不经过代理）⇒
 *       ⇒ 若业务<b>直接</b>调 {@code connection.stringCommands().get(...)} ⇒ ⇒ 绕过检查。</li>
 *   <li><b>pipeline</b>：命令在 {@code closePipeline()} 时才真正下发 ⇒ 而
 *       {@code closePipeline} <b>不拦截</b> ⇒ ⇒ pipeline 内的命令<b>不</b>受预算约束。</li>
 *   <li><b>集群 / 哨兵连接</b>（{@code getClusterConnection} / {@code getSentinelConnection}）
 *       ⇒ <b>不包装</b>（本仓未使用集群模式）。</li>
 * </ul>
 */
final class DeadlineConnection {

    private DeadlineConnection() {
    }

    /**
     * 把一个真实的 {@link RedisConnection} 包成「预算感知」的代理。
     */
    static RedisConnection wrap(RedisConnection delegate) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (isCommand(method)) {
                // 🔴 预算耗尽 ⇒ **在下发命令之前**失败 ⇒ 不给一个已死的请求继续挂 Redis
                Deadline.assertNotExhausted(method.getName());
            }
            return invoke(method, delegate, args);
        };
        return (RedisConnection) Proxy.newProxyInstance(
                RedisConnection.class.getClassLoader(),
                new Class<?>[]{RedisConnection.class},
                handler);
    }

    /**
     * 该方法是不是「命令」（⇒ 该不该做预算检查）。
     */
    private static boolean isCommand(Method method) {
        return method.getDeclaringClass() == DefaultedRedisConnection.class;
    }

    /**
     * 反射调用，并<b>把被包装的异常原样抛出</b>。
     *
     * <p>⚠️ <b>为什么必须解包 {@link InvocationTargetException}</b>：不解包的话，
     * 域侧 {@code catch (RedisSystemException e)} 这类<b>按类型</b>的分支会全部失配
     * ⇒ ⇒ 异常语义被代理改写 ⇒ **这是代理最常见的副作用**，必须显式处理。
     *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/redis/GoStyleRedisDeadlineConnection.java}
 * （映射表 §1.12 第 280 行 · 1:1 · RAG 侧类名 {@code GoStyleRedisDeadlineConnection}）
 * <p><b>⚠️ 落点差异</b>：<b>去 {@code Redis} 前缀</b> —— 包名已含 {@code redis}，类名再带前缀即冗余（命名债 P4-④ 同款处置）
 * ⇒ ⇒ **调用点要同步改 import 与类名**。
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
    private static Object invoke(Method method, Object delegate, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("redis command failed: " + cause, cause);
        }
    }
}
