package com.rag.common.observability;

/**
 * <p>请求级 deadline 的<b>服务端载体</b>（ThreadLocal 绝对到期时刻，{@link System#nanoTime()} 域）。
 *
 * <p><b>⚠️ 与 {@link GoStyleRequestDeadline} 的关系（两个载体，必须绑同一个绝对时刻）</b>：
 * <ul>
 *   <li><b>本类</b>：<b>入向</b>（服务端 SQL 超时用）⇒ {@code common/persistence/DeadlineQueryTimeoutInjector}
 *       读 {@link #remainingMillis()} 把预算落到每条 SQL 的 {@code setQueryTimeout}；</li>
 *   <li><b>{@link GoStyleRequestDeadline}</b>：<b>出向</b>（gRPC stub 的 deadline）⇒
 *       {@link GoStyleRequestDeadline#applyTo} 给 stub 套上剩余预算。</li>
 * </ul>
 * ⇒ ⚠️ <b>「出向已对齐」推不出「入向也对齐」</b> —— 本类就是<b>入向</b>那一侧。
 *
 * <p><b>⚠️ 落点差异（1:1 · 从嵌套类提到顶层类）</b>：RAG 侧本类是
 * {@code RpcInterceptor.DeadlineState}（{@code RpcInterceptor.java} 的<b>嵌套</b>类型，1182 行大类里的一个静态类）
 * ⇒ ⇒ RAG-V1 把它<b>独立成顶层类</b>（本文件）。
 * <p><b>为什么必须独立</b>：① {@code common/persistence/DeadlineQueryTimeoutInjector}（批 1）
 * 硬依赖 {@link #remainingMillis()} ⇒ 若随批 3 的 {@code ObservabilityCustomizer} 一起落，
 * 批 1 无法编译；② 本类<b>零外部依赖</b>（纯 ThreadLocal 载体，不碰 otel / tracer / span）
 * ⇒ ⇒ 独立出来<b>不引入任何新耦合</b>；③ 与 {@link GoStyleRequestDeadline}（K-03 保留项 · 同包）
 * 构成「入向 / 出向」两个载体 ⇒ 同包更利于对齐。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java}
 * 的嵌套类 {@code DeadlineState}（188 行）
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
public final class DeadlineState {
    private DeadlineState() {}

    /**
     * 当前线程的 deadline 过期时刻（<b>单调钟</b>纳秒，{@link System#nanoTime()} 域）；
     * 未设置时 {@link #isExpired()} 返回 false。
     *
     * <p>🔴 <b>为什么存「单调钟纳秒」而不是「墙钟 epoch 毫秒」</b>（DRIFT-COMMON-005 修复）：
     * <ul>
     *   <li><b>B 出处（逐条可核）</b>：标准库 {@code context.WithDeadline}
     *       （{@code $service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java:1004:625} · {@code :632}）把 deadline 存成
     *       {@code time.Time}（字段见 {@code $GOROOT/src/context/context.go:666}）⇒ <b>纳秒精度</b>；
     *       而 {@code time.Time} <b>自带单调时钟读数</b>（{@code $GOROOT/src/time/time.go:152}
     *       的 {@code ext int64} + {@code :164} 的 {@code hasMonotonic} 标志位）；
     *       {@code time.Until(t)}（{@code $GOROOT/src/time/time.go:1233}）在有单调读数时走
     *       {@code subMono(t.ext, runtimeNano()-startNano)} ⇒ <b>纯单调</b>
     *       ⇒ <b>NTP 校正 / {@code setSystemTime} / 虚拟机快照恢复都影响不到它</b>。</li>
     *   <li><b>旧实现（已修）的两个问题</b>：
     *       <ul>
     *         <li><b>时基</b>：存的是 {@code System.currentTimeMillis()} 的 epoch 毫秒 ⇒ 墙钟
     *             可被 NTP 校正 / 改系统时间 / 快照恢复<b>向前或向后跳</b>
     *             ⇒ <b>倒跳</b>时 {@link #isExpired()} 长时间为 false（<b>超时不触发</b>）、
     *             <b>前跳</b>时提前判过期；</li>
     *         <li><b>精度</b>：毫秒粒度，且是在<b>创建时</b>就截断（{@code timeout.toMillis()}）
     *             ⇒ 有效 deadline 时刻本身最多差 &lt; 1ms。</li>
     *       </ul></li>
     *   <li><b>本实现</b>：把「剩余预算」在<b>绑定瞬间</b>换算成单调钟纳秒存下
     *       ⇒ ① 绑定之后<b>不受任何墙钟跳变影响</b>；② <b>纳秒精度</b>（不再截到毫秒）。</li>
     * </ul>
     *
     * <p>⚠️ <b>与 {@code GoStyleRequestDeadline} 的关系</b>：那是<b>另一个载体</b>
     * （出向 stub / api 线程的 HTTP 预算），它本来就用 {@code System.nanoTime()} + 纳秒
     * ⇒ ⇒ <b>「出向已对齐」推不出「入向也对齐」</b> —— 本条就是<b>入向</b>那一侧。
     */
    private static final ThreadLocal<Long> DEADLINE_NANOS = new ThreadLocal<>();

    /**
     * 墙钟读数（<b>仅用于兼容入口</b> {@link #bindToCurrent(long)} 把 epoch 毫秒换算成预算）。
     *
     * <p>⚠️ <b>为什么是包级可见的可变字段</b>：本条修复的<b>唯一可观察行为</b>是
     * 「墙钟跳变后 deadline 判定不受影响」⇒ ⇒ 而墙钟<b>改不了</b>（改系统时间要 root、
     * 且会污染整台机器）⇒ ⇒ 只能<b>注入</b> ⇒ ⇒ 守门用它模拟一次 NTP 前跳/后跳。
     *
     * <p>🔴 <b>只允许测试改</b>：<b>包级私有</b> ⇒ 外部模块（16 个消费方）碰不到；
     * 守门用 {@code try/finally} 复原 ⇒ ⇒ 不给生产留后门。
     */
    static java.util.function.LongSupplier wallClockMillis = System::currentTimeMillis;

    /**
     * 单调钟读数 —— 与 {@link #wallClockMillis} 同款（<b>包级私有 · 只允许测试改</b>），
     * 用途是让守门<b>确定性地推进时间</b>（不必 {@code sleep}，也不受 CI 负载影响）。
     */
    static java.util.function.LongSupplier monotonicNanos = System::nanoTime;

    /*
     * ⚠️ 2026-10-25（{@code align/global} 收口提交）：原 {@code markExpired(long)}
     * 已<b>删除</b>，此处保留说明以留痕。
     *
     * <p>删除理由（三条，均为实测）：
     * <ol>
     *   <li><b>无任何生产调用方</b> —— 全仓 grep 实证：仅 5 处 javadoc 引用，无一处真实调用；</li>
     *   <li><b>永远不生效</b> —— 它只做 {@code DEADLINE.set(...)}，
     *       而 {@code DEADLINE} 是 {@code ThreadLocal}；唯一 conceivable 的用法
     *       （设在超时调度线程上）对 handler 线程<b>不可见</b>；</li>
     *   <li><b>不做作用域清理</b> —— {@link #bindToCurrent(long)} 返回的 {@link Scope}
     *       会在 try-with-resources 结束时 {@code remove()}，避免线程池复用串味。</li>
     * </ol>
     *
     * <p><b>为什么它一直留到今天</b>：{@code service/users/user/user-rpc} 的
     * {@code UserDomainService} javadoc 误以它描述 deadline 的来源，
     * 删除会让那个跨域文件的 javadoc 链接悬空，而<b>跨域文件的改动不由 common 域决定</b>
     * ⇒ 这是跨域单点 ⇒ 归 {@code global} 收口域（明细见
     * {@code docs/DRIFT/global/cycle-deps.md}）。
     * ⇒ <b>两步走完（同一提交内）</b>：① 改正 users 侧假断言 → ② 删除本方法。
     *
     * <p>实际生效路径：{@link #newUnaryServerInterceptor} 的 {@code onHalfClose}
     * 在<b>真正跑 handler 的线程</b>上 {@link #bindToCurrent(long)}（M7 修复）。
     *
     * <p>对应 B 侧版 ctx 触发 cancel 后，handler ctx 立即 Done() 的语义。
     */

    /** 当前线程是否已过 deadline；false 包括"未设置"和"还有时间"。 */
    public static boolean isExpired() {
        Long d = DEADLINE_NANOS.get();
        // ⚠️ 用**减法**而不是 `now >= d`：单调钟会回绕（292 年）⇒ 差值法仍正确
        return d != null && monotonicNanos.getAsLong() - d >= 0;
    }

    /** 还剩多少毫秒到 deadline；未设置时返回 Long.MAX_VALUE。 */
    public static long remainingMillis() {
        Long d = DEADLINE_NANOS.get();
        if (d == null) return Long.MAX_VALUE;
        long remainNanos = d - monotonicNanos.getAsLong();
        if (remainNanos <= 0) return 0L;
        // 🔴 **下取整**（对齐 B 侧：time.Until 返回 Duration，转毫秒即截断）
        return remainNanos / 1_000_000L;
    }

    /**
     * 业务代码用 try-with-resources 绑定 deadline 到当前线程；结束后自动清除（避免泄漏到下个任务）。
     *
     * <pre>{@code
     * try (DeadlineState.Scope ignored = DeadlineState.bindToCurrent(deadlineMs)) {
     *     // 业务循环
     *     while (moreWork) {
     *         if (DeadlineState.isExpired()) {
     *             throw new TimeoutException("rpc deadline reached");
     *         }
     *     }
     * }
     * }</pre>
     *
     * <p>对应 B 侧版 handler 在循环里 {@code select { case <-ctx.Done(): ... }}。
     *
     * <p>🔴 <b>兼容入口（DRIFT-COMMON-005 修复后的形态）</b>：入参仍是<b>墙钟 epoch 毫秒</b>
     * （既有跨域调用方都这么传：{@code favorite-rpc/.../FavoriteAuthorityEndpointConfig.java:276}
     * ＋ comment-rpc / favorite-rpc 的测试）⇒ ⇒ <b>签名与语义都不变</b>。
     * <p>本方法在<b>绑定瞬间</b>把「墙钟 epoch 毫秒」换算成「剩余预算」，
     * 再以<b>单调钟纳秒</b>存下 ⇒ ⇒
     * <ul>
     *   <li><b>到期时刻</b>仍是调用方给的那个墙钟时刻（语义不变）；</li>
     *   <li>但<b>到期判定</b>不再受绑定之后的墙钟跳变影响（这才是修复点）。</li>
     * </ul>
     * <p>⚠️ <b>新代码请用 {@link #bindRemainingNanos(long)}</b> —— 它是<b>纳秒精度</b>、
     * 且完全不依赖墙钟（生产路径已全部改用它）。
     */
    public static Scope bindToCurrent(long epochMillis) {
        long remainingMillis = epochMillis - wallClockMillis.getAsLong();
        return bindRemainingNanos(saturatingToNanos(remainingMillis));
    }

    /**
     * 绑定「从现在起还剩 N 纳秒」的预算（<b>单调钟 + 纳秒精度</b>）—— <b>生产路径用这个</b>。
     *
     * <p>⚠️ 与 {@link #bindToCurrent(long)} 的差别：① <b>纳秒</b>（不再被截到毫秒）；
     * ② <b>完全不读墙钟</b> ⇒ ⇒ 预算来源由调用方给（客户端 deadline / 服务端 timeout），
     * 载体内部不做任何墙钟换算。
     *
     * @param remainingNanos 剩余预算（纳秒）；{@link Long#MAX_VALUE} 表示「无期限」
     *                       （与既有 {@code Long.MAX_VALUE} 哨兵同形）
     */
    public static Scope bindRemainingNanos(long remainingNanos) {
        long now = monotonicNanos.getAsLong();
        // 🔴 饱和加法：now + remainingNanos 溢出 ⇒ 会变成「负数」⇒ 误判为已过期
        DEADLINE_NANOS.set(
                (remainingNanos == Long.MAX_VALUE || remainingNanos > Long.MAX_VALUE - now)
                        ? Long.MAX_VALUE
                        : now + remainingNanos);
        return () -> DEADLINE_NANOS.remove();
    }

    /**
     * 绑定一个<b>绝对</b>到期时刻（单调钟纳秒）—— <b>与出向载体绑同一个时刻时用这个</b>。
     *
     * <p>🔴 <b>为什么需要它</b>：服务端有<b>两个</b> deadline 载体
     * （本类 = SQL 超时用；{@link GoStyleRequestDeadline} = 出向 gRPC 用）
     * ⇒ 它们必须指向<b>同一个绝对时刻</b>，否则两者会差出「各算各的 now」的那点纳秒
     * ⇒ ⇒ 调用方<b>算一次绝对时刻</b>、两个载体共用。
     *
     * <p>⚠️ 时基必须与本类的 {@link #monotonicNanos} 一致（= {@code System::nanoTime}）
     * ⇒ 调用方用 {@code System.nanoTime()} 计算即可。
     */
    public static Scope bindAbsoluteNanos(long absoluteNanos) {
        DEADLINE_NANOS.set(absoluteNanos);
        return () -> DEADLINE_NANOS.remove();
    }

    /**
     * 毫秒 → 纳秒的<b>饱和</b>换算（负数原样保留 ⇒ 仍表示「已过期」）。
     */
    private static long saturatingToNanos(long millis) {
        if (millis <= 0) {
            return millis;
        }
        if (millis > Long.MAX_VALUE / 1_000_000L) {
            return Long.MAX_VALUE;
        }
        return millis * 1_000_000L;
    }

    /** try-with-resources 析构接口；业务方通常不直接调用。 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
