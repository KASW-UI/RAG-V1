package com.rag.common.persistence;

import com.rag.common.observability.ObservabilityProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.time.Duration;

/**
 * <p><b>把连接池的「取连接超时」收到内层预算</b>（B 侧 gRPC 中间件那一层的超时）。
 *
 * <h2>为什么需要它（B 出处 · 逐层对齐）</h2>
 * <pre>
 *   B 侧 admin-api（HTTP，rest.RestConf.Timeout = 3s）
 *      → gRPC admin-rpc（zrpc.RpcServerConf.Timeout = 2s）   ← **内层**
 *         → SQL
 * </pre>
 * ⇒ B 侧的 DB 调用<b>整体</b>被 gRPC 那一层的 2s 兜住
 * ⇒ ⇒ <b>「等一个连接」也在那 2s 之内</b>：等不到 ⇒ ctx 到期 ⇒ {@code context.DeadlineExceeded}
 * ⇒ ⇒ api logic 的 {@code default} 分支 ⇒ <b>1015「服务繁忙」</b>。
 *
 * <p>⇒ RAG-V1 的 D2「域内直连」把 DB 调用搬进了 HTTP 边界 ⇒ <b>内层缺位</b>
 * ⇒ ⇒ PG 不可用时，{@code HikariPool.getConnection()} 会<b>干等</b>：
 * 池里没有可用连接、housekeeper 又建不出新连接 ⇒ {@code borrow} 一直等
 * ⇒ 等到 HTTP 预算（3s）到点 ⇒ 中间件写 <b>503 + 裸文本 "Request Timeout"</b>
 * ⇒ ⇒ 与 B 侧的 <b>200 + 信封 1015</b> 不一致。
 *
 * <h2>⚠️ 为什么「连接获取超时」必须也收进来（否则只修好一半）</h2>
 * <p>{@link DeadlineQueryTimeoutInjector} 只对<b>已经拿到的连接</b>上的语句设 query timeout
 * ⇒ ⇒ <b>卡在取连接阶段时它根本不会被触发</b>（Statement 还没 prepare）
 * ⇒ ⇒ 必须**同时**把连接池自己的 {@code connectionTimeout} 收到同一个内层预算。
 *
 * <h2>⚠️ 为什么「只收紧、不放宽」</h2>
 * <p>本类只在 {@code 当前值 > 内层预算} 时才改 ⇒ ⇒ 域里<b>显式配得更短</b>的值不受影响
 * （不会被本类放宽）⇒ ⇒ 本类是「收紧」而不是「覆盖」。
 *
 * <h2>⚠️ 为什么改的是 {@code HikariConfig} 而不是别的旋钮</h2>
 * <ul>
 *   <li>Hikari 的 {@code connectionTimeout} 是<b>取连接</b>的等待上限（默认 30s）
 *       ⇒ 它就是 B 侧「ctx 掐住 DB 调用」在连接池这一层的等价物；</li>
 *   <li>⚠️ <b>必须在池启动前改</b>：Hikari 的配置在池启动时<b>封存</b>
 *       （{@code HikariDataSource} 的类注释：「Once the pool is started, the configuration is
 *       "sealed"」）⇒ ⇒ 本类作为 {@link BeanPostProcessor} 在 DataSource bean 初始化后、
 *       <b>第一次 getConnection() 之前</b>改 ⇒ 必然生效。</li>
 * </ul>
 *
 * <p><b>三段式留痕（AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧靠 ctx 掐住整个 DB 调用；C 侧把这一层折成
 *       「连接获取超时」＋「SQL query timeout」两个旋钮（生态给不了 ctx 取消）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 两者的可观察面都是「DB 调用在 2s 处失败」，
 *       且失败被翻成 {@code DEADLINE_EXCEEDED} ⇒ 落 api logic 的 {@code default} ⇒ 1015。</li>
 *   <li><b>B 出处</b>：{@code service/common/.../persistence/DeadlineQueryTimeoutInjector.java}
 *       的同款意图 ＋ B 侧 {@code zrpc.RpcServerConf.Timeout}（= {@code observability.rpc-timeout}）。</li>
 * </ol>
 */
public class DeadlineBoundedConnectionTimeout implements BeanPostProcessor {

    /**
     * Hikari 的下限：{@code connectionTimeout} 小于它会被 {@code setConnectionTimeout} 直接拒收
     * （{@code HikariConfig#setConnectionTimeout} 抛 {@code IllegalArgumentException}）。
     */
    static final long HIKARI_SOFT_TIMEOUT_FLOOR_MS = 250L;

    private final ObjectProvider<ObservabilityProperties> observability;

    public DeadlineBoundedConnectionTimeout(ObjectProvider<ObservabilityProperties> observability) {
        this.observability = observability;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof HikariDataSource hikari)) {
            return bean;
        }
        long innerMillis = innerBudgetMillis();
        if (innerMillis <= 0L) {
            // 无内层预算（两项超时都未配置）⇒ 不动连接池（与本类引入前逐字一致）。
            return bean;
        }
        long target = Math.max(HIKARI_SOFT_TIMEOUT_FLOOR_MS, innerMillis);
        long current = hikari.getConnectionTimeout();
        if (current > target) {
            hikari.setConnectionTimeout(target);
        }
        // 🔴 **再包一层翻译**：把「取连接等超时」翻成 DEADLINE_EXCEEDED
        //    ⇒ ⇒ 它以 StatusRuntimeException 的形态离开持久层 ⇒ 域层放行 ⇒ api 层落 1015。
        //    ⚠️ 必须在**池启动前**完成（见类头「为什么改的是 HikariConfig」那段）。
        return new DeadlineTranslatingDataSource(hikari);
    }

    /**
     * 内层预算（毫秒）；无内层预算时返回 {@code 0}。
     *
     * <p>⚠️ <b>为什么不在构造时算好</b>：{@link ObservabilityProperties} 是
     * {@code @ConfigurationProperties} bean，它可能在 DataSource bean <b>之后</b>才完成绑定
     * ⇒ ⇒ 这里在 {@code postProcessAfterInitialization} 里<b>现取</b>（{@code getIfAvailable()}
     * 会按需把那个 bean 初始化出来）⇒ ⇒ 拿到的一定是<b>已绑定</b>的值。
     */
    private long innerBudgetMillis() {
        ObservabilityProperties properties = observability.getIfAvailable();
        if (properties == null) {
            return 0L;
        }
        Duration inner = properties.resolveInnerBudget();
        if (inner == null || inner.isZero() || inner.isNegative()) {
            return 0L;
        }
        long millis = inner.toMillis();
        return millis <= 0L ? 0L : millis;
    }
}
