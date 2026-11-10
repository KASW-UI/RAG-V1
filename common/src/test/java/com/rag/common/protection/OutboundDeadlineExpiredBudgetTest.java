package com.rag.common.protection;

import com.rag.common.observability.GoStyleRequestDeadline;
import io.grpc.CallOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>DRIFT-COMMON-V18 的收口守门</b>：出向 deadline 的 <b>「请求载体预算恰好为 0」</b>这条分支。
 *
 * <h2>这条分支为什么高危</h2>
 *
 * <p>{@code OutboundDeadlineInterceptor#effectiveCallOptions} 里那道守卫是
 * <b>{@code if (requestRemaining >= 0L)}</b>（C 侧 {@code :259} ＝ B 侧 {@code GoStyleOutboundDeadlineInterceptor.java:253}，<b>逐字同形</b>）。
 * <ul>
 *   <li><b>保持 {@code >= 0L}</b>：剩余预算<b>恰好为 0</b>（= 已到期）⇒ 预算压成 0 ⇒
 *       命中「<b>立即失败</b>」分支（{@code budgetNanos <= 0L} ⇒ {@code withDeadlineAfter(1L, NANOSECONDS)}）⇒
 *       ⇒ 出向调用<b>当场超时</b>（与 B 侧 ctx 已到期同形）；</li>
 *   <li><b>改成 {@code > 0L}</b>：剩余为 0 时守卫<b>不成立</b> ⇒ 预算仍是 {@code Long.MAX_VALUE}
 *       ⇒ ⇒ 走「三项都没有 ⇒ 原样返回」分支 ⇒ ⇒ 出向调用<b>静默退回「挂满」</b>
 *       （<b>无限超时</b>）⇒ ⇒ <b>语义与 B 侧相反</b>。</li>
 * </ul>
 *
 * <p>⚠️ <b>只差一个字符</b> ⇒ ⇒ 必须有网把它锁住（2026-11-11 · `global` 收口域 · favorite 域上报：
 * 「这条关键分支无网」）。
 *
 * <h2>三条断言</h2>
 *
 * <ol>
 *   <li><b>对照组</b>：<b>没有</b>请求载体（{@code remainingNanos() == -1}）⇒ 守卫不成立
 *       ⇒ 原样返回 {@code CallOptions}（<b>不给这次调用凭空加约束</b>，B 侧
 *       {@code WithTimeout} 只在 {@code c.Timeout > 0} 时才加）；</li>
 *   <li><b>主断言</b>：请求载体预算<b>恰好为 0</b>（已到期）⇒ 必须走「<b>立即失败</b>」分支
 *       ⇒ 结果<b>带一个已过期的 deadline</b>（<b>不是</b>原样返回）；</li>
 *   <li><b>邻接断言</b>：请求载体预算<b>为正</b> ⇒ deadline 取该剩余值（证明上面那条不是「碰巧非空」）。</li>
 * </ol>
 *
 * <p>⚠️ <b>变异证据</b>：把 {@code >= 0L} 改成 {@code > 0L} ⇒ 第 ② 条断言<b>当场转红</b>
 * （结果退回原样 {@code CallOptions} ⇒ {@code getDeadline()} 为 {@code null}）
 * ⇒ ⇒ 判据<b>不是恒真</b>（已实测：变异红 / 还原绿）。
 *
 * <h2>三段式（AGENTS.md §3.2）</h2>
 *
 * <p><b>① 比 B 侧多做了什么</b>：<b>没有</b> —— 本类只<b>测</b>既有行为，<b>不改任何生产代码</b>
 * （本条是<b>守门缺口</b>，不是行为漂移：B 侧 {@code :253} 与 C 侧 {@code :259} <b>逐字同形</b>）。
 * <p><b>② 语义还一样吗</b>：<b>一样</b> —— 本测试锁住的正是 B 侧的语义（已到期 ⇒ 立即失败，不静默挂满）。
 * <p><b>③ B 出处</b>：RAG {@code service/common/src/main/java/com/rag/common/protection/GoStyleOutboundDeadlineInterceptor.java:253}
 * （{@code if (requestRemaining >= 0L)}；L2 业务语义层以 RAG 为基准 · D13/D16）。
 */
class OutboundDeadlineExpiredBudgetTest {

    /**
     * 构造一个<b>已到期</b>的请求载体 ⇒ {@code remainingNanos()} 恰好为 0。
     *
     * <p>⚠️ {@code GoStyleRequestDeadline#remainingNanos} 的实现是
     * {@code Math.max(0L, deadlineNanos - System.nanoTime())} ⇒ ⇒ 传一个**过去**的绝对时刻
     * ⇒ 返回值**恰好为 0**（不是负数）⇒ ⇒ 正好落在 {@code >= 0L} 与 {@code > 0L} 的分水岭上。
     */
    private static GoStyleRequestDeadline.Scope expiredCarrier() {
        return GoStyleRequestDeadline.bindAbsoluteNanos(System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(50L));
    }

    @Test
    @DisplayName("出向 deadline · 对照组：请求载体**不存在**（remaining = -1）⇒ 守卫不成立 ⇒ 原样返回 CallOptions")
    void noCarrierKeepsCallOptionsUntouched() {
        OutboundDeadlineInterceptor interceptor = new OutboundDeadlineInterceptor(true, 0L);
        CallOptions options = CallOptions.DEFAULT;

        // ⇒ 前提：没有载体 ⇒ remaining = -1
        assertThat(GoStyleRequestDeadline.remainingNanos()).isEqualTo(-1L);

        CallOptions out = interceptor.effectiveCallOptions(options, "svc");
        assertThat(out).isSameAs(options);
        assertThat(out.getDeadline()).isNull();
    }

    @Test
    @DisplayName("出向 deadline · 主断言：请求载体预算**恰好为 0**（已到期）⇒ 必须「立即失败」而不是「静默退回挂满」")
    void expiredCarrierBudgetMustFailImmediately() {
        OutboundDeadlineInterceptor interceptor = new OutboundDeadlineInterceptor(true, 0L);
        CallOptions options = CallOptions.DEFAULT;

        try (GoStyleRequestDeadline.Scope scope = expiredCarrier()) {
            // ⇒ 前提：remaining **恰好为 0**（这正是 `>= 0L` 与 `> 0L` 的分水岭）
            assertThat(GoStyleRequestDeadline.remainingNanos()).isZero();

            CallOptions out = interceptor.effectiveCallOptions(options, "svc");

            // ⇒ 必须**已经设了 deadline** ⇒ 若返回原样对象 ⇒ 就是「静默退回挂满」⇒ 判红
            assertThat(out.getDeadline())
                    .as("请求载体预算已耗尽 ⇒ 出向调用必须被判「立即失败」（带一个已过期的 deadline），"
                            + "而不是原样返回 CallOptions（那会让调用静默挂满 ⇒ 与 B 侧语义相反）")
                    .isNotNull();
            assertThat(out.getDeadline().timeRemaining(TimeUnit.NANOSECONDS))
                    .as("「立即失败」形态 ⇒ deadline 必须在过去")
                    .isLessThanOrEqualTo(0L);
        }
    }

    @Test
    @DisplayName("出向 deadline · 邻接断言：请求载体预算**为正** ⇒ deadline 取该剩余值（证明主断言不是「碰巧非空」）")
    void positiveCarrierBudgetIsApplied() {
        OutboundDeadlineInterceptor interceptor = new OutboundDeadlineInterceptor(true, 0L);
        CallOptions options = CallOptions.DEFAULT;
        long budgetNanos = TimeUnit.SECONDS.toNanos(30L);

        try (GoStyleRequestDeadline.Scope scope =
                     GoStyleRequestDeadline.bindAbsoluteNanos(System.nanoTime() + budgetNanos)) {
            assertThat(GoStyleRequestDeadline.remainingNanos()).isGreaterThan(0L);

            CallOptions out = interceptor.effectiveCallOptions(options, "svc");
            assertThat(out.getDeadline()).isNotNull();
            assertThat(out.getDeadline().timeRemaining(TimeUnit.MILLISECONDS))
                    .isGreaterThan(0L)
                    .isLessThanOrEqualTo(TimeUnit.NANOSECONDS.toMillis(budgetNanos));
        }
    }
}
