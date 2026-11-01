package com.rag.common.protection;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * CPU 降载的<b>观测面</b>：把 Go {@code core/stat/usage.go:51} {@code CpuUsage()}
 * （整机千分比）暴露成 Actuator 健康指标，供 D-04 的降载决策消费。
 *
 * <h3>Go 出处</h3>
 * <pre>
 *   core/stat/usage.go:51          func CpuUsage() int64 { return atomic.LoadInt64(&cpuUsage) }
 *   core/load/sheddingstat.go:59   c := stat.CpuUsage()          // 降载统计日志里的 cpu
 *   core/load/adaptiveshedder.go:41-43
 *       systemOverloadChecker = func(cpuThreshold int64) bool {
 *           return stat.CpuUsage() &gt;= cpuThreshold
 *       }
 * </pre>
 *
 * <p>Go 的 {@code stat.CpuUsage()} 在 Linux 下读 {@code /proc/stat} 的<b>整机</b> CPU，
 * 返回 <b>千分比</b>（0..1000，900 = 90%）。
 *
 * <p>⚠️ <b>不可用时 fail-open</b>（读不到 ⇒ 视作未过载 ⇒ 不降载），
 * 与 Go 侧 {@code systemOverloadChecker} 返回 false 的分支一致。
 *
 * <p><b>「不可用 ⇒ -1」这条哨兵值是自定的</b>（Go 侧没有这个值）：Go 的
 * {@code core/stat/usage.go:22} 把 {@code cpuUsage} 初值定为 <b>0</b>，此后只被
 * {@code core/stat/usage.go:35-39} 的 250ms 移动平均（β=0.95 ⇒ 有效窗口 ≈5s）覆盖 ⇒
 * <b>Go 侧永远不会返回负数</b> ⇒ ⇒ 两边的<b>行为等价</b>（都判「未过载」），但
 * <b>哨兵值不等价</b>（Go=0 / 本实现=-1）⇒ ⇒ 依赖该值的上层必须走
 * {@code < 0 ⇒ fail-open} 这一支。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ D4 生态缺口（G-1 · D-04）：为什么是「健康指标」而不是「降载器」</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>resilience4j 无 CPU 降载等价物</b> ⇒ 若不自研就没有这个能力 ⇒ 自研 1 类。
 * <br>但 <b>形态</b>被生态改写了：Go 的 {@code adaptive shedder} 是
 * <b>「CPU 过载 + 在途超动态上限」两个条件同时成立才丢请求</b>
 * （{@code core/load/adaptiveshedder.go}）⇒ 单看 CPU 会误丢请求。
 * <br>⇒ 本仓把 <b>CPU 判定</b>与 <b>丢弃动作</b>分开：
 * <ul>
 *   <li><b>CPU 判定</b> ⇒ 本类（Actuator {@link HealthIndicator}）⇒ <b>生态形态</b>；</li>
 *   <li><b>丢弃动作</b> ⇒ 由 {@code ProtectionAutoConfiguration} 注册的降载 Filter 消费本指标
 *       ⇒ 拒绝响应形状（裸状态码 + 空 body + 无 Content-Type）必须保留（D-04）。</li>
 * </ul>
 * ⇒ ⚠️ <b>「在途超动态上限」这第二个条件在本仓没有等价物</b>（生态无此能力）
 * ⇒ ⇒ 本类只提供 CPU 这<b>一半</b>判据 ⇒ <b>该缺口已登记为 L3 降级</b>（见
 * {@code docs/L3-降级清单.md} D-04）⇒ <b>不得反向"修回"</b>。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/protection/GoStyleCpuReader.java}
 * 的采样口径整段抄（L2 · D13）＋ <b>形态由「内部工具类」改为 Actuator 健康指标</b>（L4 · D4 / G-1）。
 */
public class CpuThresholdHealthIndicator implements HealthIndicator {

    /** 不可用（对应 Go 侧读不到 /proc/stat 的情形）。 */
    public static final int UNAVAILABLE = -1;

    private static final int PERMILLE = 1000;

    private static final OperatingSystemMXBean OS_BEAN = ManagementFactory.getOperatingSystemMXBean();

    private final ProtectionProperties.CpuShedding config;

    public CpuThresholdHealthIndicator(ProtectionProperties props) {
        this.config = props.getCpuShedding();
    }

    /**
     * 整机 CPU 千分比（Go {@code stat.CpuUsage()} 口径），不可用时 {@link #UNAVAILABLE}。
     */
    public static int systemCpuPermille() {
        double load = systemCpuLoad();
        return load < 0D ? UNAVAILABLE : (int) Math.round(load * PERMILLE);
    }

    /** 仅本 JVM 进程的 CPU 千分比（偏离 Go，需显式配置）。 */
    public static int processCpuPermille() {
        double load = processCpuLoad();
        return load < 0D ? UNAVAILABLE : (int) Math.round(load * PERMILLE);
    }

    /** 当前生效的 CPU 采样（按配置的 {@code cpuSource} 选口径）。 */
    public int currentCpuPermille() {
        ProtectionProperties.CpuSource source = config.getCpuSource();
        return source == ProtectionProperties.CpuSource.PROCESS
                ? processCpuPermille()
                : systemCpuPermille();
    }

    /**
     * <b>过载判定</b>：{@code cpu >= threshold}。
     *
     * <p>⚠️ <b>不可用（-1）⇒ 恒判未过载</b>（fail-open）—— 这是 Go 侧
     * {@code systemOverloadChecker} 返回 false 的等价物。
     */
    public boolean isOverloaded() {
        if (!config.isEnabled()) {
            return false;
        }
        int cpu = currentCpuPermille();
        if (cpu < 0) {
            // fail-open：读不到 CPU ⇒ 视作未过载（与 Go 一致）
            return false;
        }
        return cpu >= config.getCpuThreshold();
    }

    @Override
    public Health health() {
        int cpu = currentCpuPermille();
        Health.Builder builder = cpu < 0
                // ⚠️ 不可用 ⇒ **UP**（fail-open：读不到 ⇒ 不降载 ⇒ 不该把服务判成不健康）
                ? Health.up()
                : Health.up();
        builder.withDetail("cpuPermille", cpu);
        builder.withDetail("cpuThreshold", config.getCpuThreshold());
        builder.withDetail("cpuSource", config.getCpuSource().name());
        builder.withDetail("sheddingEnabled", config.isEnabled());
        if (cpu >= 0 && cpu >= config.getCpuThreshold()) {
            // ⚠️ 只把<b>指标面</b>标成 DOWN；<b>是否真的丢请求</b>由降载 Filter 的判据决定
            // ⇒ 两者都在 ⇒ 避免「健康检查红 ⇒ 编排器把整个实例摘掉」这种放大效应
            builder.down();
        }
        return builder.build();
    }

    /**
     * ⚠️ {@code getSystemCpuLoad()} <b>不在</b> {@link OperatingSystemMXBean} 上，
     * 而在 {@code com.sun.management.OperatingSystemMXBean} 这个 JDK 扩展接口上。
     * 若运行时不提供该扩展（非 HotSpot 系），返回 {@code -1} ⇒ 上层 fail-open。
     */
    private static double systemCpuLoad() {
        if (OS_BEAN instanceof com.sun.management.OperatingSystemMXBean sun) {
            return sun.getSystemCpuLoad();
        }
        return -1D;
    }

    private static double processCpuLoad() {
        if (OS_BEAN instanceof com.sun.management.OperatingSystemMXBean sun) {
            return sun.getProcessCpuLoad();
        }
        return -1D;
    }
}
