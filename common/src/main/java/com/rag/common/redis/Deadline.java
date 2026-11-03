package com.rag.common.redis;

import com.rag.common.observability.GoStyleRequestDeadline;
import org.springframework.dao.QueryTimeoutException;

/**
 * <b>共享层的「Redis 期限感知」入口（G1 反馈单 2/3 · 2026-10-30 · G4 lane）。</b>
 *
 * <h2>⚠️ 结论订正（2026-10-30 15:2x · G4 本层自查）：<b>E66 是真漂移</b> ⇒ 本类的作用是「把 RAG 拉回与 B 侧 同形」，<b>不是</b>「比 B 侧 更严」</h2>
 * <p><b>B 侧的事实（逐行读过）—— 两条<b>独立</b>的机制，结论相反，必须分开写</b>：
 * <ul>
 *   <li><b>①「派发前」B 侧 <b>是</b>查 deadline 的</b> ⇒ ⇒ <b>B 的 Redis 写受请求 deadline 约束</b>
 *       ⇒ ⇒ <b>E66 是真漂移</b>（RAG 侧不查 ⇒ 漂移）：
 *       <ul>
 *         <li>{@code service/common/src/main/java/com/rag/common/redis/GoStyleRedisDeadline.java:15:32-40}（RAG 仓）构造客户端时
 *             <b>没有设</b> {@code ContextTimeoutEnabled}（默认 {@code false}）
 *             ⇒ 但那<b>只影响 socket 层</b>（见 ②），<b>不影响</b>派发前的检查。</li>
 *         <li>{@code go-redis/v9@v9.20.0/internal/pool/pool.go:1098-1104}
 *             {@code ConnPool.waitTurn} 的 <b>fast path 无条件先查 {@code ctx.Done()}</b>
 *             ⇒ {@code ctx.Err()} ⇒ <b>命令根本不下发</b>。</li>
 *         <li>调用链（逐段读过）：{@code logoutlogic.go:38}
 *             {@code BizRedis.SetexCtx(l.ctx, …)} → {@code core/stores/redis/redis.go:1517-1523}
 *             {@code conn.Set(ctx, …)} → {@code string_commands.go:451-469} {@code cmdable.Set}
 *             → {@code redis.go:1434-1438} {@code Client.Process} → {@code redis.go:212-214}
 *             {@code processHook} → {@code redis.go:830} {@code baseClient.process}
 *             → {@code redis.go:961} {@code withConn} → {@code redis.go:383-400} {@code getConn}
 *             → {@code redis.go:402-403} {@code _getConn} → {@code pool.go:802-804} {@code ConnPool.Get}
 *             → {@code pool.go:807} {@code getConn} → {@code pool.go:837} {@code waitTurn}
 *             ⇒ ⇒ <b>命中 ①</b>。</li>
 *       </ul></li>
 *   <li><b>②「socket 层」B 侧 <b>不</b>查请求 deadline</b> ⇒ ⇒ <b>那一层 RAG 与 B 侧 同形</b>：
 *       <ul>
 *         <li>{@code go-redis/v9@v9.20.0/redis.go:1024-1032} 的 {@code baseClient.context(ctx)}
 *             在 {@code ContextTimeoutEnabled=false} 时<b>把 ctx 换成 {@code context.Background()}</b>
 *             ⇒ {@code redis.go:968}/{@code :985} 的
 *             {@code cn.WithWriter/WithReader(c.context(ctx), …)}
 *             <b>拿到的 ctx 已无 deadline</b>
 *             ⇒ {@code internal/pool/conn.go:961-991} 的 {@code cn.deadline(ctx, timeout)}
 *             只能用<b>固定的 {@code ReadTimeout} = 3s</b>（{@code options.go:364-379}）。</li>
 *         <li>⇒ ⇒ RAG 侧的 {@code spring.data.redis.timeout: 3s} 与之<b>同形</b>
 *             ⇒ ⇒ <b>「固定 3s socket 超时」这一轴不是漂移</b>。</li>
 *       </ul></li>
 * </ul>
 * <p>⚠️ <b>本类此前的 javadoc 写「B 的 Redis 命令 I/O 本来就不受请求 deadline 约束 ⇒ E66 不是漂移」
 * —— 那句话<b>只看了 ②、漏了 ① ⇒ 是错的</b>（2026-10-30 15:2x 由 G4 本层自查订正，
 * 见 {@code docs/DRIFT/global/vs-common.md} §4.19.5）。⇒ ⇒ <b>正确结论：E66 是真漂移</b>，
 * 本能力面正是<b>补上 B 侧 已有的 ①</b>（⇒ ⇒ 这<b>不是</b>「比 B 侧 更严」，
 * 而是<b>把 RAG 拉回与 B 侧 同形</b>）。</b></p>
 *
 * <h2>那本类为什么存在</h2>
 * <p>让 <b>RAG 的 Redis 调用在「下发命令之前」就感知请求预算</b> ⇒ 这正是 B 侧 已有的 ①
 * ⇒ ⇒ 本类是<b>补齐漂移</b>（同形化），<b>不是</b>凭空新增约束
 * ⇒ 具体做到哪一步，必须写清楚：
 * <ul>
 *   <li><b>本类交付的</b>：请求预算<b>已耗尽</b> ⇒ <b>不再下发新的 Redis 命令</b>
 *       （抛 {@link QueryTimeoutException}）⇒ ⇒ <b>不把一个已经死掉的请求
 *       继续挂在 Redis 上</b>。</li>
 *   <li><b>本类<b>不</b>交付的</b>：<b>「命令级 socket 超时随剩余预算收紧」</b>
 *       ⇒ ⇒ <b>本轮刻意不落</b>（<b>不是「技术上不可达」</b> —— 那句断言<b>是错的</b>，
 *       2026-10-30 订正）：
 *       <ul>
 *         <li><b>路线<b>是可达的</b></b>（本层实测，不是推测）：lettuce 的
 *             {@code io.lettuce.core.TimeoutOptions$TimeoutSource}（<b>public</b> abstract；
 *             抽象方法 {@code long getTimeout(RedisCommand<?, ?, ?>)} ＋ {@code getTimeUnit()}）
 *             ＋ {@code TimeoutOptions$Builder.timeoutSource(TimeoutSource)}
 *             ＋ {@code ClientOptions$Builder.timeoutOptions(TimeoutOptions)}
 *             ⇒ 再用 Boot 4 的 {@code LettuceClientOptionsBuilderCustomizer} 注入即可
 *             ⇒ ⇒ <b>「Lettuce 逐命令动态返回剩余 deadline」这条路走得通</b>。
 *             ⚠️ <b>customizer 的位置是关键</b>：实测 Boot 4.0.8
 *             {@code LettuceConnectionConfiguration.createClientOptions} 的字节码 ——
 *             offset 143 {@code TimeoutOptions.enabled()}（<b>硬编码无参</b>）→ offset 146
 *             {@code timeoutOptions(...)} → offset 150+ 才 apply customizer
 *             ⇒ ⇒ <b>customizer 在硬编码之后 ⇒ 可覆盖</b>。</li>
 *         <li><b>但落它会把 RAG 推到「比 B 侧 更严」的一侧</b>：B 的 socket 层<b>不</b>收紧
 *             （固定 3s，见上面 ②）⇒ ⇒ 收紧 = <b>新造一条有意偏离</b>（需签字）
 *             ⇒ ⇒ 本轮<b>不落</b>（登记为「待立项」，见台账 §4.19.5）。</li>
 *       </ul></li>
 *   <li><b>「等待不超过剩余预算」的严格模式</b> ⇒ 需要把命令丢到<b>另一个线程</b>再
 *       {@code Future.get(remaining)} ⇒ ⚠️ 与 <b>事务绑定</b>（{@code RedisConnectionUtils}
 *       的 ThreadLocal 事务连接在调用线程上）和<b>线程亲和</b>冲突
 *       ⇒ ⇒ <b>本轮不落</b>，登记为「待立项」（见台账 §4.19.4 ⑥ 一）。</li>
 * </ul>
 *
 * <h2>与 B 的差异方向</h2>
 * <p>①（派发前）：<b>本类落码后与 B 侧 同形</b>（B 侧 查、RAG 也查）⇒ <b>不产生新的有意偏离</b>。
 * <p>②（socket 层固定超时）：<b>本类不动</b> ⇒ 仍与 B 侧 同形（两侧都是固定 3s）⇒ <b>不产生新的有意偏离</b>。
 * <p>③（<b>检查点的位置</b> · 2026-10-30 16:2x 订正）：<b>仍差一段 ⇒ 「完全同形」不成立</b> ——
 *     B 的 ctx 检查点<b>更靠后</b>（客户端层：{@code waitTurn} 的 fast path 先查 {@code ctx.Done()}
 *     ⇒ ctx 已 Done ⇒ <b>命令根本不下发</b>；且拨号也绑 ctx ⇒ 拨号期间过期 ⇒ 拨号被取消 ⇒ 也不写），
 *     而本类的检查点<b>更靠前</b>（连接代理 ⇒ 在<b>取连接之前</b>）⇒ ⇒ ⇒
 *     <b>残余方向 = RAG 更宽</b>：deadline 在<b>「取连接 / 拨号窗口」</b>内过期 ⇒
 *     <b>B 侧 不写（拨号被 ctx 取消）／RAG 照写（Lettuce 取连接不受 ctx 约束）</b>
 *     ⇒ ⇒ ⇒ <b>「派发前检查」这一层已同形，但「完全同形」不成立</b>
 *     ⇒ ⇒ 逐条证据见 {@code docs/DRIFT/global/vs-common.md} §4.19.7 第三节。
 * <p>⚠️ <b>由此带来的登记变化</b>：本层此前按「Redis 比 B 侧 更严」登记的那条有意偏离候选
 * ⇒ ⇒ <b>前提已被订正（E66 是真漂移）⇒ 该登记作废</b> ⇒ 改按「补齐漂移」处理。
 * ⇒ ⇒ 订正全过程（证据链 ＋ 对转派单口径的影响）见
 * {@code docs/DRIFT/global/vs-common.md} §4.19.5。
 */
public final class Deadline {

    /**
     * 「没有预算」的哨兵（= {@link GoStyleRequestDeadline#remainingNanos()} 在载体缺席时的返回值）。
     */
    public static final long NO_BUDGET = -1L;

    private Deadline() {
    }

    /**
     * 当前线程的<b>请求剩余预算</b>（纳秒）；<b>没有载体时返回 {@link #NO_BUDGET}</b>。
     */
    public static long remainingNanos() {
        return GoStyleRequestDeadline.remainingNanos();
    }

    /**
     * 请求预算是否<b>已耗尽</b>。
     *
     * <p>⚠️ <b>「没有载体」不算耗尽</b>（返回 {@code false}）——
     * 那对应 B 侧「ctx 没有 deadline」⇒ ⇒ 行为**原样放行**（不凭空加约束）。
     */
    public static boolean isExhausted() {
        long remaining = remainingNanos();
        return remaining >= 0L && remaining <= 0L;
    }

    /**
     * 预算耗尽 ⇒ <b>抛异常，让调用方在「下发命令之前」就失败</b>。
     *
     * <p>⚠️ <b>为什么抛 {@link QueryTimeoutException} 而不是自定义异常</b>：
     * 它是 Spring 的<b>标准</b>超时异常（{@code org.springframework.dao}）⇒
     * 域侧现有的 {@code catch (Exception e)} 逻辑<b>不用改一行</b>就能接住。
     *
     * <p>🔴 <b>「对外错误面」必须分两层说</b>
     * （2026-10-30 16:2x 订正 ⇒ 原写「错误码 / 错误文案与 B 侧保持一致」是<b>过强断言</b>）：
     * <ul>
     *   <li><b>① 客户端可见码 = 1015（DEADLINE_EXCEEDED）⇒ 与 B 侧一致</b>：
     *       由 {@code RpcInterceptor} 的<b>首次</b> {@code close(...)} 决定
     *       （预算已耗尽 ⇒ 超时调度器立即 fire ⇒ 首次 close 即 DEADLINE_EXCEEDED）⇒ ⇒
     *       <b>B 侧也拿 1015</b> —— B 的 {@code codes.Internal}
     *       （{@code service/common/src/main/java/com/rag/common/redis/GoStyleRedisDeadline.java:141:41}）在 ctx 已 Done 时<b>基本到不了客户端</b>：
     *       {@code service/common/observability/rpc.go:59-72} 的 select 里，
     *       <b>timeout 分支不拿锁</b> ⇒ ctx 已过期时<b>超时分支先就绪</b>
     *       ⇒ ⇒ <b>两侧客户端可见码基本一致 ⇒ 不是 wire 级漂移</b>。
     *       <p>⚠️ <b>「基本」不等于「必然」</b> —— 本层用<b>真源拦截器实跑探针</b>
     *       （{@code sea-try-go/service/common/observability} 真模块）测得：deadline 场景下
     *       B 侧有 <b>5/20000（≈0.025%）</b> 的窗口会让 {@code done} 分支也赢
     *       （handler goroutine 抢到空锁 + {@code waitTurn} fast path 秒失败 ⇒ 先 {@code close(done)}）
     *       ⇒ ⇒ 客户端会拿到 5001 ⇒ ⇒ <b>逐条见 §4.19.10</b>。</li>
     *   <li><b>② handler 级业务码 = 域侧 {@code catch (Exception e)} 映射的 {@code Status.INTERNAL}（5001）</b>
     *       ＋ 文案「注销失败,Redis写入异常」⇒ ⇒ 那正是 B 的 handler 级码
     *       （{@code logoutlogic.go:41}）；⚠️ <b>但带域侧抢先 {@code checkDeadline()} 的路径
     *       根本走不到这里</b>（域侧检查先触发 ⇒ handler 级是 1015）⇒ ⇒ ⇒
     *       <b>该路径的 handler 级业务码与 B 的 5001 不同</b>
     *       ⇒ ⇒ <b>完全同形需域侧去掉那处抢先检查 ⇒ 归 G1（users 域写权）</b>，
     *       且<b>必须与本类的共享层代理同一个提交进主干</b>
     *       （无代理而先删 ⇒ 两道闸门都没了 ⇒ RAG 写、B 侧 不写 ⇒ 数据面回归）
     *       ⇒ ⇒ 客户端可见码由<b>拦截器层</b>决定，不是由 handler 内部返回什么码决定 ——
     *       B 侧 {@code service/common/src/main/java/com/rag/common/redis/GoStyleRedisDeadline.java:160:59-72} 的 select，
     *       本层 {@code RpcInterceptor.java:377-378}「只有第一次 close 负责打标」
     *       ⇒ ⇒ 逐条证据见 {@code docs/DRIFT/global/vs-common.md} §4.19.10 第二节。</li>
     * </ul>
     *
     * @param operation 被拦下的操作名（进消息，便于排查）
     *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/redis/GoStyleRedisDeadline.java}
 * （映射表 §1.12 第 280 行 · 1:1 · RAG 侧类名 {@code GoStyleRedisDeadline}）
 * <p><b>⚠️ 落点差异</b>：<b>去 {@code Redis} 前缀</b> —— 包名已含 {@code redis}，类名再带前缀即冗余（命名债 P4-④ 同款处置）
 * ⇒ ⇒ **调用点要同步改 import 与类名**。
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
    public static void assertNotExhausted(String operation) {
        long remaining = remainingNanos();
        if (remaining >= 0L && remaining <= 0L) {
            throw new QueryTimeoutException(
                    "redis command skipped: request deadline exhausted (op=" + operation + ")");
        }
    }
}
