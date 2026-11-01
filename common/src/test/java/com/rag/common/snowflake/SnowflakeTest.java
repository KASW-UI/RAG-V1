package com.rag.common.snowflake;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3 真触发测试 —— {@link Snowflake} 的<b>位布局</b>与<b>单调性</b>。
 *
 * <p><b>Go 出处</b>：{@code github.com/bwmarrin/snowflake@v0.3.0/snowflake.go:135-160}
 * ＋ go-zero 侧 {@code service/common/snowflake/snowflake.go} 的 epoch 常量
 * （{@code 1288834974657} = Twitter 2010-11-04T01:42:54.657Z）。
 *
 * <p><b>⚠️ 为什么位布局必须被测试钉住</b>：它不是「内部实现」——
 * <b>下游按位段解 ID</b>（like 域的游标编码依赖 {@code id >> 22}）
 * ⇒ 位段一动 ⇒ 上游解出来的值全错 ⇒ 而且<b>不会编译报错</b>。
 */
class SnowflakeTest {

    private static final long SEQUENCE_MASK = 0xFFF;      // 12 位
    private static final long WORKER_ID_MASK = 0x3FF;     // 10 位
    private static final long TIMESTAMP_SHIFT = 22;       // 12 + 10

    @Test
    @DisplayName("位布局：workerId 占 bit 12..21，sequence 占 bit 0..11")
    void workerIdAndSequenceBitLayout() {
        Snowflake.State state = new Snowflake.State();
        long id = Snowflake.nextIdInternal(1023L, state);

        assertThat((id >> 12) & WORKER_ID_MASK).isEqualTo(1023L);
        assertThat(id & SEQUENCE_MASK).isBetween(0L, SEQUENCE_MASK);
        assertThat(id & SEQUENCE_MASK).isLessThanOrEqualTo(SEQUENCE_MASK);
    }

    @Test
    @DisplayName("位布局：workerId = 0 时该段全 0（不污染其它段）")
    void zeroWorkerIdDoesNotPolluteOtherSegments() {
        Snowflake.State state = new Snowflake.State();
        long id = Snowflake.nextIdInternal(0L, state);
        assertThat((id >> 12) & WORKER_ID_MASK).isZero();
    }

    @Test
    @DisplayName("位布局：时间戳段 = id >> 22（下游游标编码依赖这一条）")
    void timestampSegmentIsShiftedBy22() {
        Snowflake.State state = new Snowflake.State();
        long id = Snowflake.nextIdInternal(0L, state);
        long timestampPart = id >> TIMESTAMP_SHIFT;
        // 距 epoch 的毫秒数 ⇒ 必须为正、且不能超过 41 位能表示的量级
        assertThat(timestampPart).isPositive();
        assertThat(timestampPart).isLessThan(1L << 41);
    }

    @Test
    @DisplayName("epoch 常量 = Twitter 2010-11-04T01:42:54.657Z（Go 侧同值）")
    void epochConstantMatchesGo() {
        assertThat(Snowflake.EPOCH).isEqualTo(1288834974657L);
    }

    @Test
    @DisplayName("同一毫秒内连续取号 ⇒ 严格递增且不重复")
    void nextIdIsStrictlyIncreasing() {
        long prev = Snowflake.nextId();
        for (int i = 0; i < 500; i++) {
            long next = Snowflake.nextId();
            assertThat(next).isGreaterThan(prev);
            prev = next;
        }
    }

    @Test
    @DisplayName("位段常量：41 + 10 + 12")
    void bitSegmentConstants() {
        assertThat(Snowflake.WORKER_ID_BITS).isEqualTo(10L);
        assertThat(Snowflake.SEQUENCE_BITS).isEqualTo(12L);
        assertThat(Snowflake.MAX_WORKER_ID).isEqualTo(1023L);
    }
}
