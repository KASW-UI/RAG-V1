package com.rag.common.observability;

import io.grpc.stub.AbstractStub;

import java.util.concurrent.TimeUnit;

/**
 * 请求级 deadline 的<b>载体</b>：把 {@link HttpMiddleware} 的 HTTP 超时预算，
 * 显式带到 gRPC 调用点上 —— 即 B 侧 <b>ctx 传播</b>的 Java 等价物。
 *
 * <h2>为什么需要它（B 侧的机制，逐句对齐）</h2>
 *
 * <pre>
 *   rest/handler/timeouthandler.go:68-71
 *       ctx, cancelCtx := context.WithTimeout(r.Context(), h.dt)
 *       r = r.WithContext(ctx)                 // ← 关键：deadline 被**传进业务**
 *   rest/handler/timeouthandler.go:79-89
 *       go func() { h.handler.ServeHTTP(tw, r); close(done) }()
 * </pre>
 *
 * <p>⇒ B 的「超时后不 kill goroutine」是**假象**：handler 函数还在跑，
 * 但它手上的 {@code ctx} 已经到期，于是所有 <b>ctx-aware</b> 的下游 I/O
 * （gRPC / DB）**立即中止**，goroutine 随之迅速结束。
 * <p>Java 没有隐式 ctx。超时分支只能 {@code Future.cancel(true)}。
 * ⚠️ <b>本段曾写「grpc-java 的阻塞 stub 不可中断，调用会一直挂到下游自己结束」——
 * 该说法已被实测推翻</b>（{@code DeadlineCancellationSemanticsTest} 三条对照）：
 * 阻塞调用<b>是可以被线程中断打断的</b>，而且中断会沿传输传导到服务端 handler。
 * <p><b>真实差异只在 status code 上</b>，而且它比「挂死」严重得多：
 * <table border="1">
 *   <caption>同一个「下游超时」事件的两条路径</caption>
 *   <tr><th>路径</th><th>客户端看到</th><th>在 codes.Acceptable 失败名单里？</th><th>breaker 会</th></tr>
 *   <tr><td>B 侧 ctx 到期</td><td>{@code DEADLINE_EXCEEDED}</td><td><b>是</b></td><td>计为失败</td></tr>
 *   <tr><td>Java {@code Future.cancel(true)}</td><td>{@code CANCELLED}</td><td><b>否</b></td><td>计为<b>成功</b></td></tr>
 * </table>
 * ⇒ 不补 deadline，下游变慢对 client breaker <b>完全不可见</b>（被当成成功），
 * 熔断器永远不会因超时而打开 —— 这才是要补的实质。
 *
 * <h2>⚠️ 用 ThreadLocal 而不是显式传参，是一次<b>有意识的取舍</b></h2>
 *
 * <p>显式传参（每个 {@code rpcClient.method(req, deadline)}）在类型上更安全，
 * 但它要改 B 侧<b>每个</b> logic 的函数签名，而 B 侧 那边恰恰是靠 ctx「隐形」传递的 ——
 * 强行显式化会让两边的调用形状对不上，也让「6 份逐字重复的 logic 模板」这一观察失效。
 * <p>ThreadLocal 的代价是<b>它对「谁在跑这段代码」有假设</b>，这个假设必须写死并被测住：
 * <ul>
 *   <li><b>必须在 worker 线程上 set</b>（不是容器线程）—— 业务跑在 worker 上，
 *       容器线程的 ThreadLocal 对它<b>不可见</b>。这是最容易写错的一处。</li>
 *   <li><b>必须在 worker 的 finally 里 clear</b> —— worker 来自线程池，
 *       残留会把下一个请求的 deadline 污染成上一个的（§3.3：静默串味）。</li>
 *   <li><b>无超时路径不 set</b> —— 对应 B 侧「没装 TimeoutHandler 就没有 ctx deadline」。</li>
 * </ul>
 *
 * <h2>⚠️ 与 client breaker 的关系（用户 2026-10-22 拍板）</h2>
 *
 * <p>deadline 生效后，gRPC 调用超时会真的返回 {@code DEADLINE_EXCEEDED}。
 * 而 {@code com.rag.common.protection.GoStyleGrpcClientBreakerInterceptor#isAcceptable}
 * 把 {@code DEADLINE_EXCEEDED} 列为<b>失败</b>（对齐 B 侧 {@code zrpc/internal/codes/accept.go}）
 * ⇒ <b>超时会计入 breaker、并可能触发熔断</b>。
 * <p>这是<b>有意接受的</b>：在补 deadline 之前，该码<b>永远不可能出现</b>
 * （调用不会被 deadline 打断），所以这条判据是「按 B 侧 写好但从未被触发」。
 * 现在它开始真实生效，语义与 B 侧 一致 —— <b>不是新增偏离</b>。
 */
public final class GoStyleRequestDeadline {

    private static final ThreadLocal<Long> DEADLINE_NANOS = new ThreadLocal<>();

    private GoStyleRequestDeadline() {
    }

    /**
     * 在<b>当前线程</b>（必须是跑业务的那个 worker）设定绝对 deadline。
     *
     * @param deadlineNanos {@link System#nanoTime()} 时基下的绝对到期时刻
     *                      （由调用方按 {@code start + timeout} 算好传入）
     */
    public static void begin(long deadlineNanos) {
        DEADLINE_NANOS.set(deadlineNanos);
    }

    /** 清空。必须与 {@link #begin(long)} 成对，否则线程池复用会串味。 */
    public static void clear() {
        DEADLINE_NANOS.remove();
    }

    /** 当前线程是否处在有 deadline 的请求里。 */
    public static boolean isPresent() {
        return DEADLINE_NANOS.get() != null;
    }

    /** 剩余毫秒数；无 deadline 时返回 -1。 */
    public static long remainingMillis() {
        Long d = DEADLINE_NANOS.get();
        if (d == null) {
            return -1L;
        }
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(d - System.nanoTime()));
    }

    /**
     * 剩余<b>纳秒</b>数；无 deadline 时返回 -1；已耗尽时返回 0。
     *
     * <p>⚠️ <b>为什么要有纳秒版</b>：{@link #remainingMillis()} 会把亚毫秒部分截掉
     * ⇒ 出向上界的计算若走毫秒版，会比 B 侧（纳秒）<b>宽最多 1ms</b>
     * ⇒ 而 B 的 ctx deadline 是<b>纳秒精度</b>（grpc-go 走 wire 的 {@code grpc-timeout}）
     * ⇒ ⇒ 对拍时会出现「差不到 1ms」的不可解释差异。
     *
     * <p>B 出处：{@code service/common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java:106:25}（RAG 仓）
     * （{@code context.WithTimeout(ctx, t)} ⇒ 纳秒精度 ⇒ min 语义）。
     */
    public static long remainingNanos() {
        Long d = DEADLINE_NANOS.get();
        if (d == null) {
            return -1L;
        }
        return Math.max(0L, d - System.nanoTime());
    }

    /**
     * 在<b>当前线程</b>绑定一个<b>绝对</b> deadline 时刻，返回<b>作用域</b>（try-with-resources）。
     *
     * <p>⚠️ <b>为什么必须是作用域而不是 {@link #begin(long)} + {@link #clear()}</b>：
     * <ul>
     *   <li>{@code begin/clear} 是<b>「设置 / 清空」</b>语义 ⇒ <b>嵌套时会互相踩</b>：
     *       内层 {@code begin} 覆盖外层，外层 {@code clear} 把内层的也清了 ⇒
     *       <b>请求之间</b>会串味（线程池复用 ⇒ 上一个请求的 deadline 漏到下一个）。</li>
     *   <li>本方法保存<b>原值</b>并在 {@link Scope#close()} 时<b>原样还原</b>
     *       ⇒ 嵌套安全 ⇒ 且「忘了 clear」不再可能（编译器保证配对）。</li>
     * </ul>
     *
     * <p>⚠️ <b>与 {@link DeadlineState} 的关系</b>（B 侧 = {@code RpcInterceptor.DeadlineState}）：
     * 那是<b>另一个载体</b>
     * （服务端 SQL 超时用），本类是<b>出向 gRPC</b> 用 ⇒ 两者必须绑<b>同一个绝对时刻</b>
     * ⇒ 故本方法收<b>绝对</b>纳秒时刻（调用方算好一次、两个载体共用）。
     *
     * <p>B 出处（ctx 传播 = 同一个 ctx 派生 ⇒ 同一个 deadline）：
     * <pre>
     *   B 出处：service/common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java:135:34
     *       ctx, cancel := context.WithTimeout(ctx, t)
     *   B 出处：service/common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java:137:53
     *       resp, err = handler(ctx, req)      // ← handler 拿到的 ctx 已带 deadline
     *   B 出处：service/common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java:139:25
     *       ctx, cancel := context.WithTimeout(ctx, t)   // ← 出向调用再 min 一次
     * </pre>
     */
    public static Scope bindAbsoluteNanos(long absoluteNanos) {
        Long previous = DEADLINE_NANOS.get();
        DEADLINE_NANOS.set(absoluteNanos);
        return () -> {
            if (previous == null) {
                DEADLINE_NANOS.remove();
            } else {
                DEADLINE_NANOS.set(previous);
            }
        };
    }

    /**
     * 作用域（try-with-resources 析构接口）。
     *
     * <p>⚠️ {@link AutoCloseable#close()} 的受检异常被<b>收窄</b>掉 ⇒
     * 用在 try-with-resources 里<b>不会</b>被迫写 catch。
     */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        /**
         * <b>空作用域</b>（什么也不绑）—— 用于「本层<b>没有预算</b> ⇒ 不给载体赋值」的分支。
         *
         * <p>⚠️ <b>为什么需要它</b>：B 侧 ctx <b>没有 deadline</b> 时，出向调用**也不该有** deadline
         * ⇒ 若此时给载体绑一个「292 年后到期」的值 ⇒ 出向就会**凭空多出一个 deadline**
         * ⇒ ⇒ 与 B 的形态<b>反向</b>（B 侧 是「没有 ⇒ 没有」）。
         *
         * <p>⚠️ 载体<b>没绑</b>时 {@link #remainingNanos()} 返回 {@code -1}
         * ⇒ 出向拦截器据此<b>不注入</b>任何约束（见
         * {@code GoStyleOutboundDeadlineInterceptor#effectiveCallOptions}）。
         */
        Scope NOOP = () -> { };

        @Override
        void close();
    }

    /**
     * 给 stub 套上当前线程的剩余预算；<b>没有预算时原样返回</b>。
     *
     * <p>⚠️ <b>预算已耗尽时给 1 纳秒</b>（而不是原样返回）：
     * B 侧 ctx 已到期时下游调用会<b>立刻失败</b>；若这里原样返回，
     * Java 就会发起一个「没有 deadline 的慢调用」—— 那正是要修的那个漂移。
     *
     * @param stub 任意 grpc blocking stub
     * @return 带 deadline 的新 stub（grpc 的 {@code withDeadlineAfter} 每次返回新实例，
     *         调用方<b>不需要</b>缓存；但也<b>不要</b>把它缓存在字段上，
     *         那会把「第一次调用时的预算」固化下来）
     *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 * <p><b>⚠️ K-03 保留项（映射表 §1.6 第 184 行 · 1:1）</b>：本类<b>不是</b> D4「生态吸收」的对象
 * —— Java 的 {@code Future.cancel(true)} 对阻塞 IO 无效 ⇒ 必须走服务端取消语义
 * ⇒ ⇒ 落点保留 {@code GoStyle*} 前缀（命名债 P4-④ 已登记：它是<b>语义标记</b>而非噪声）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧 {@code GoStyleRequestDeadline} 引用
 * {@code RpcInterceptor.DeadlineState}（服务端 SQL 超时载体，批 3 才落）
 * ⇒ ⇒ 本仓该引用<b>暂缺</b>，已在 javadoc 中标注 ⇒ 批 3 落地 {@code ObservabilityCustomizer}
 * 时须把两者的「同一个绝对时刻」约束一并兑现。
 */
    public static <T extends AbstractStub<T>> T applyTo(T stub) {
        Long d = DEADLINE_NANOS.get();
        if (d == null) {
            return stub;
        }
        long remainingNanos = d - System.nanoTime();
        if (remainingNanos <= 0L) {
            return stub.withDeadlineAfter(1L, TimeUnit.NANOSECONDS);
        }
        return stub.withDeadlineAfter(remainingNanos, TimeUnit.NANOSECONDS);
    }
}