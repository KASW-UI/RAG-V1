package com.rag.common.autoconfigure;

import com.rag.common.protection.AdaptiveShedder;
import com.rag.common.protection.ProtectionProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 **REST 侧降载 filter 的行为锁定** —— 对齐 B 侧
 * {@code service/common/src/main/java/com/rag/common/protection/GoStyleCpuSheddingFilter.java:20-34}（RAG 仓）
 * ＋ {@code service/common/src/main/java/com/rag/common/protection/GoStyleAdaptiveShedder.java:7-11}（RAG 仓）。
 *
 * <h2>本类为什么在 2026-11-02（批次 H）才出现</h2>
 *
 * <p>⚠️ **它守的是一个真实漂移**：C 侧的 REST 降载 filter 此前判据是
 * 「<b>只有 CPU 过载</b>」⇒ ⇒ <b>比 B 侧更早丢请求</b>（B 侧 / B 是「CPU 过载 <b>且</b> 在途超动态上限」
 * <b>两个条件同时成立</b>才丢）。
 * <p>⇒ 台账此前把这条记成「D-04 已登记的 L3 降级」⇒ <b>但那是拿 B 侧 比 C，不是拿 B 比 C</b>
 * ⇒ ⇒ B 侧 REST filter 本来就有完整自适应 shedder ⇒ ⇒ <b>C 丢了 B 已有的能力</b>。
 * <p>⇒ ⇒ 本类是那条漂移的**回归守卫**：把判据改回「只有 CPU」⇒ 第 ② 条用例立刻红。
 *
 * <h2>测试资产来源</h2>
 *
 * <p>抄写基准 = **RAG** {@code service/common/src/test/java/com/rag/common/protection/GoStyleCpuSheddingFilterTest.java}
 * （§1.0c「测试资产单独一档」⇒ 形态可直接借、**期望值沿用 RAG** · D13）。
 * ⇒ ③ 那条并发用例的「预热 + 3 个在途 + 只放行第 0 个」的构造法**逐字沿用**。
 * ⇒ ② 那条是本仓**新增**的回归守卫（B 侧不需要，因为 B 侧本来就是对的）。
 *
 * <h2>⚠️ 为什么必须注入 CPU 读数、不能真把 CPU 打满</h2>
 *
 * <p>真把 CPU 压到 900‰ 既慢又不确定；而 {@code systemOverloaded()} 的输入<b>只有 CPU 读数一个</b>
 * ⇒ ⇒ 注入即可精确构造 ⇒ 这是 AGENTS.md §3.3「验证机制实际生效」在**不能真造负载**时的正确做法：
 * <b>注入机制的真实输入，断言机制的真实输出形状</b>。
 */
class CpuSheddingFilterTest {

    private static ProtectionProperties props(long cpuThreshold) {
        ProtectionProperties p = new ProtectionProperties();
        p.getCpuShedding().setCpuThreshold(cpuThreshold);
        return p;
    }

    /** 造一个 filter：CPU 读数可注入（恒定 1000‰ ⇒ {@code systemOverloaded()} 恒真）。 */
    private static ProtectionAutoConfiguration.CpuSheddingFilter filterWithCpu(ProtectionProperties props,
                                                                              int cpuPermille) {
        AdaptiveShedder shedder = new AdaptiveShedder(props.getCpuShedding().getCpuThreshold(),
                props.getCpuShedding().getCpuSource());
        shedder.setCpuPermilleReader(() -> cpuPermille);
        return new ProtectionAutoConfiguration.CpuSheddingFilter(props, shedder);
    }

    // ── ① RT 口径 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 RT 的口径是**浮点**毫秒（B 侧 adaptiveshedder.go:285），不是整型截断")
    void rtUsesFloatMilliseconds() {
        assertThat(ProtectionAutoConfiguration.CpuSheddingFilter.elapsedRtMs(0L, 1_200_000L))
                .as("1.2ms 必须原样保留小数 —— 整型截断会让 RT 系统性少记最多 1ms，"
                        + "⇒ minRt 偏小 ⇒ 在途上限偏低 ⇒ 比 B 侧 更早降载")
                .isEqualTo(1.2D);
        assertThat(ProtectionAutoConfiguration.CpuSheddingFilter.elapsedRtMs(0L, 999_999L))
                .as("0.999999ms 不得被截断成 0")
                .isGreaterThan(0D);
    }

    // ── ② 双条件（本轮漂移的回归守卫）────────────────────────────────────

    @Test
    @DisplayName("🔴 回归守卫：CPU 过载但**无在途** ⇒ **不**降载（双条件判据，不是「只看 CPU」）")
    void cpuOverloadedWithoutInFlightStillPasses() throws Exception {
        ProtectionProperties props = props(900);
        ProtectionAutoConfiguration.CpuSheddingFilter filter = filterWithCpu(props, 1000);

        AtomicInteger downstream = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/x"), response,
                (req, res) -> downstream.incrementAndGet());

        assertThat(downstream.get())
                .as("CPU 过载**且**在途超上限**两个条件同时成立**才丢（B 侧 highThru() = avgFlying > cap && flying > cap）"
                        + "⇒ 无在途 ⇒ 必须放行"
                        + "；⚠️ 若这条红 ⇒ 说明判据退回了「只看 CPU」（= 批次 H 修掉的那条漂移）")
                .isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    // ── ③ CPU 过载 + 在途超上限 ⇒ 裸 503（借 RAG 侧用例，逐字沿用构造法）──────────────────

    @Test
    @DisplayName("🔴 CPU 过载且在途超上限 ⇒ 裸 503 空 body（B 侧 sheddinghandler.go:40 的 w.WriteHeader(503)）")
    void overloadRejectsWithBare503() throws Exception {
        ProtectionProperties props = props(900);
        ProtectionAutoConfiguration.CpuSheddingFilter filter = filterWithCpu(props, 1000);

        // ── 预热：造出「已完成桶里的低 RT」⇒ minRt 变小 ⇒ maxFlight 落到下界 1
        //
        // B 侧 maxFlight = max(maxPass * minRt * windowScale, 1)：
        //   冷启动时 minRt 取默认 1000ms ⇒ maxFlight = 1 * 1000 * 0.01 = 10 ⇒ 上限太松，测不出来。
        //   窗口里有过一个**已完成桶**的低 RT 后 ⇒ minRt 变小 ⇒ maxFlight 落到下界 1
        //   ⇒ overloadFactor(0.1) ⇒ 上限 cap = 0.1 ⇒ 「在途 ≥ 2」即可触发。
        AtomicInteger warm = new AtomicInteger();
        FilterChain fast = (req, res) -> warm.incrementAndGet();
        filter.doFilter(new MockHttpServletRequest("POST", "/x"), new MockHttpServletResponse(), fast);
        assertThat(warm.get()).isEqualTo(1);
        // 桶宽 100ms ⇒ 睡过桶边界，那个桶才变成「已完成桶」（B 侧: IgnoreCurrentBucket）
        Thread.sleep(150L);

        // ── 构造 3 个并发在途，其中**只有一个**先结束
        //
        // B 侧 highThru() = avgFlying > cap && flying > cap（cap = maxFlight * overloadFactor）
        // 而 avgFlying **只在 delta<0 时更新**：avgFlying = avgFlying*0.9 + flying_new*0.1
        // ⇒ 要让 avgFlying > 0.1，必须在 **flying ≥ 3** 时发生一次归零（此时 flying_new = 2）。
        CountDownLatch inChain = new CountDownLatch(3);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseRest = new CountDownLatch(1);

        Thread[] holders = new Thread[3];
        for (int i = 0; i < 3; i++) {
            final CountDownLatch mine = (i == 0) ? releaseFirst : releaseRest;
            holders[i] = new Thread(() -> {
                try {
                    filter.doFilter(new MockHttpServletRequest("POST", "/x"),
                            new MockHttpServletResponse(), (req, res) -> {
                                inChain.countDown();
                                try {
                                    mine.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            holders[i].start();
        }
        assertThat(inChain.await(5, TimeUnit.SECONDS)).as("前置条件：3 个请求必须都在途").isTrue();

        // 只放行第 0 个 ⇒ flying 3→2，avgFlying = 0*0.9 + 2*0.1 = 0.2 > cap(0.1)
        releaseFirst.countDown();
        holders[0].join(5_000);

        // ── 此刻 CPU 恒过载、avgFlying 与 flying 双双越过上限 ⇒ 下一个请求必须被拒
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        AtomicInteger downstream = new AtomicInteger();
        filter.doFilter(new MockHttpServletRequest("POST", "/x"), rejected,
                (req, res) -> downstream.incrementAndGet());

        assertThat(rejected.getStatus())
                .as("CPU 过载 + 在途超上限 ⇒ B 侧 sheddinghandler.go:40 写 503")
                .isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(downstream.get()).as("被拒的请求不得触达下游").isZero();
        assertThat(rejected.getContentAsByteArray().length)
                .as("B 侧是裸 w.WriteHeader(503) ⇒ body 必须为空（不走 envelope）")
                .isZero();

        releaseRest.countDown();
        for (Thread t : holders) {
            t.join(5_000);
        }
    }

    // ── ④ 关闭开关 ⇒ 完全放行 ────────────────────────────────────────────

    @Test
    @DisplayName("🔴 cpuShedding.enabled=false ⇒ 完全放行（连 CPU 都不读）")
    void disabledSheddingAlwaysPasses() throws Exception {
        ProtectionProperties props = props(900);
        props.getCpuShedding().setEnabled(false);
        ProtectionAutoConfiguration.CpuSheddingFilter filter = filterWithCpu(props, 1000);

        AtomicInteger downstream = new AtomicInteger();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/x"), response,
                (req, res) -> downstream.incrementAndGet());

        assertThat(downstream.get()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }
}
