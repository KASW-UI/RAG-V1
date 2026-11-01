package com.rag.common.redis;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.connection.RedisClusterConnection;

/**
 * <b>把「请求预算」接到 {@link RedisConnectionFactory} 上（域侧零改动的那一环）。</b>
 *
 * <h2>装配链</h2>
 * <pre>
 * RedisTemplate / StringRedisTemplate（各域注入的那两个 Bean）
 *   └─ getConnection() ──▶ DeadlineConnectionFactory（本类）
 *          └─ delegate.getConnection() ──▶ 真实连接
 *          └─ DeadlineConnection.wrap(...) ──▶ 预算感知代理
 * </pre>
 * ⇒ ⇒ **各域的注入点、调用点一行都不用改** ⇒ 命令照样发出去，只是
 * <b>「请求预算已耗尽」时不再发出去</b>。
 *
 * <h2>为什么不包装集群 / 哨兵连接</h2>
 * <p>本仓未使用 Redis 集群 / 哨兵 ⇒ ⇒ 这两个方法<b>直接透传</b>（不包装 ⇒ 也不误伤）。
 */
public class DeadlineConnectionFactory implements RedisConnectionFactory {

    private final RedisConnectionFactory delegate;
    private final boolean enabled;

    public DeadlineConnectionFactory(RedisConnectionFactory delegate, boolean enabled) {
        this.delegate = delegate;
        this.enabled = enabled;
    }

    /** 包装前的真实 factory（供守门断言「确实是包出来的」）。 */
    public RedisConnectionFactory delegate() {
        return delegate;
    }

    @Override
    public RedisConnection getConnection() {
        // 🔴 E66（对齐 B 的检查位置 · 2026-10-30 G4 第六次订正）：
        //   预算检查**必须在建连之前** ⇒ ⇒ 与 B 的检查位置逐条对齐。
        //   B 出处：go-redis v9.20.0 service/common/src/main/java/com/rag/common/redis/GoStyleRedisDeadlineConnectionFactory.java:43:1098-1103
        //     func (p *ConnPool) waitTurn(ctx context.Context) error {
        //         // Fast path: check context first
        //         select { case <-ctx.Done(): return ctx.Err(); default: }
        //   ⇒ waitTurn 由 pool.go:837 的 getConn 调用 ⇒ 而 getConn 由 pool.Get(ctx) 调用
        //   ⇒ ⇒ B 的顺序是「**先查 ctx → 再取/拨连接 → 再写**」
        //   ⇒ ⇒ 而拨号在 pool.go:591 dialConn(ctx, …)（**晚于** waitTurn）。
        //   语义等价论证：本方法在 delegate.getConnection()（= 拨号）**之前**查预算
        //   ⇒ ⇒ 预算已耗尽时**连都不拨** ⇒ ⇒ 与 B 侧「ctx 已 Done ⇒ 不取连接、不拨号」同形；
        //   ⇒ ⇒ 修正前是「先拨了连接、再在命令下发前拦」⇒ ⇒ 资源层比 B 侧 多一次拨号。
        //   ⚠️ 为什么**必须同时保留**命令处的检查（DeadlineConnection）：
        //   事务绑定场景下 RedisConnectionUtils 直接返回**已绑定的那条连接**
        //   ⇒ ⇒ 不再走本方法 ⇒ ⇒ 若只有本处检查，事务内的第 2 条命令就漏检
        //   ⇒ ⇒ 而 B 侧**每条命令**都过一次 waitTurn ⇒ ⇒ 命令处的检查是「每命令一次」那一份。
        if (enabled) {
            Deadline.assertNotExhausted("getConnection");
        }
        RedisConnection connection = delegate.getConnection();
        return enabled ? DeadlineConnection.wrap(connection) : connection;
    }

    @Override
    public RedisClusterConnection getClusterConnection() {
        // ⚠️ 不包装：集群面本仓未使用（包装器只保证单机连接的语义）
        return delegate.getClusterConnection();
    }

    @Override
    public RedisSentinelConnection getSentinelConnection() {
        return delegate.getSentinelConnection();
    }

    @Override
    public boolean getConvertPipelineAndTxResults() {
        return delegate.getConvertPipelineAndTxResults();
    }

    /**
     * 异常翻译：**原样委托**给真实 factory。
     *
     * <p>⚠️ <b>为什么必须委托而不是自己实现</b>：{@code RedisConnectionFactory} 继承了
     * {@code PersistenceExceptionTranslator} ⇒ 真实实现（Lettuce）会走
     * {@code ExceptionTranslationStrategy} 把底层异常翻成 {@code DataAccessException}
     * ⇒ ⇒ 若本层自己实现 ⇒ 域侧看到的异常类型会<b>与生产不一致</b>
     * ⇒ ⇒ 委托 ⇒ 翻译行为**逐字保持生产形态**。
     *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/redis/GoStyleRedisDeadlineConnectionFactory.java}
 * （映射表 §1.12 第 280 行 · 1:1 · RAG 侧类名 {@code GoStyleRedisDeadlineConnectionFactory}）
 * <p><b>⚠️ 落点差异</b>：<b>去 {@code Redis} 前缀</b> —— 包名已含 {@code redis}，类名再带前缀即冗余（命名债 P4-④ 同款处置）
 * ⇒ ⇒ **调用点要同步改 import 与类名**。
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
    @Override
    public org.springframework.dao.DataAccessException translateExceptionIfPossible(RuntimeException ex) {
        return delegate.translateExceptionIfPossible(ex);
    }
}
