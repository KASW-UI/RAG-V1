package com.rag.hot.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>HeavyKeeper 的数值契约测试</b> —— 三组判据：① {@code uint32} 无符号语义；
 * ② FNV 指纹与列号（<b>差分实现</b>）；③ Top-K 的进 / 出 / 排序。
 *
 * <p><b>⚠️ 为什么哈希那一组要用「差分实现」而不是抄一份期望值</b>：
 * 生产实现用 {@code long} + {@link Long#remainderUnsigned(long, long)}；
 * 本测试用 {@link BigInteger} 写一份<b>完全独立</b>的 FNV-1a 64（全程无符号，
 * 模 2^64）⇒ ⇒ 若生产实现被改成「有符号取模 + 补正」或「{@code floorMod}」或
 * 「{@code & 0xFFFF...} 掩码」三种错法中的一种，<b>本测试立刻转红</b>
 * ⇒ ⇒ 这是一个<b>有牙齿</b>的判据，而不是「再抄一遍同样的算式」。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/domain/HeavyKeeper.java}
 * （B 侧注释里记录过：修正前 <b>173/405</b> 个 key 的列号与 B 侧不同，
 * 修正后 <b>2025/2025</b> 个列号逐条一致 ⇒ 本测试锁的是<b>修正后</b>的那套语义）。
 */
class HeavyKeeperContractTest {

    /** 生产的默认口径（B 侧 {@code etc/hot*.yaml} 的 HeavyKeeper 段）。 */
    private static final int WIDTH = 1000;
    private static final int DEPTH = 5;
    private static final double DECAY = 0.9;
    private static final int TOP_K = 100;

    // ==================================================================
    // ① uint32 无符号语义
    // ==================================================================

    @Test
    @DisplayName("countToScore：负的 int 必须渲染成 uint32 的无符号十进制")
    void countToScoreRendersUnsigned() {
        // B 侧：uint32(0xFFFFFFFF) = 4294967295
        assertEquals(4294967295.0d, HeavyKeeper.countToScore(-1));
        assertEquals(4294967294.0d, HeavyKeeper.countToScore(-2));
        assertEquals(2147483648.0d, HeavyKeeper.countToScore(Integer.MIN_VALUE));
        assertEquals(0.0d, HeavyKeeper.countToScore(0));
    }

    @Test
    @DisplayName("scoreToCount：向零截断（不是四舍五入、不是 floor）")
    void scoreToCountTruncatesTowardZero() {
        assertEquals(3, HeavyKeeper.scoreToCount(3.9d));
        assertEquals(3, HeavyKeeper.scoreToCount(3.0d));
        assertEquals(-3, HeavyKeeper.scoreToCount(-3.9d));
        assertEquals(0, HeavyKeeper.scoreToCount(0.4d));
        // uint32 满量程：4294967295.0 截断后落回 int 的位模式 -1
        assertEquals(-1, HeavyKeeper.scoreToCount(4294967295.0d));
    }

    @Test
    @DisplayName("add：uint32 溢出必须按模 2^32 回绕，且 TopK 读到的是无符号值")
    void addWrapsLikeUint32() {
        HeavyKeeper hk = new HeavyKeeper(WIDTH, DEPTH, DECAY, TOP_K);
        hk.add("k", Integer.MAX_VALUE);   // 2147483647
        hk.add("k", 2);                   // +2 ⇒ 2147483649 ⇒ int 位模式 = -2147483647

        List<HeavyKeeper.Item> top = hk.topK();
        assertEquals(1, top.size());
        assertEquals("k", top.get(0).key());
        assertEquals(-2147483647, top.get(0).count());
        // 无符号视图 = 2147483649
        assertEquals(2147483649.0d, HeavyKeeper.countToScore(top.get(0).count()));
    }

    // ==================================================================
    // ② 指纹与列号（差分实现）
    // ==================================================================

    @Test
    @DisplayName("fingerprint：FNV-1a 32 的已知常量（空串 = offset basis）")
    void fingerprintMatchesKnownConstant() throws Exception {
        Method m = HeavyKeeper.class.getDeclaredMethod("fingerprint", String.class);
        m.setAccessible(true);
        // FNV-1a 32 offset basis = 0x811C9DC5（= 2166136261）
        assertEquals(0x811C9DC5, (int) m.invoke(new HeavyKeeper(WIDTH, DEPTH, DECAY, TOP_K), ""));
        // FNV-1a 32("a") = 0xE40C292C
        assertEquals(0xE40C292C, (int) m.invoke(new HeavyKeeper(WIDTH, DEPTH, DECAY, TOP_K), "a"));
        // FNV-1a 32("foobar") = 0xBF9CF968
        assertEquals(0xBF9CF968, (int) m.invoke(new HeavyKeeper(WIDTH, DEPTH, DECAY, TOP_K), "foobar"));
    }

    @Test
    @DisplayName("hash：列号必须等于「BigInteger 无符号 FNV-1a 64 mod width」")
    void hashMatchesIndependentUnsignedImplementation() throws Exception {
        HeavyKeeper hk = new HeavyKeeper(WIDTH, DEPTH, DECAY, TOP_K);
        Method m = HeavyKeeper.class.getDeclaredMethod("hash", String.class, int.class);
        m.setAccessible(true);

        String[] keys = {"", "a", "1", "article-900200001", "中文", "emoji-🚀", "x".repeat(300)};
        for (String key : keys) {
            for (int seed = 0; seed < DEPTH; seed++) {
                int expected = independentFnv1a64Column(key, seed, WIDTH);
                int actual = (int) m.invoke(hk, key, seed);
                assertEquals(expected, actual,
                        "列号不一致：key=" + key + " seed=" + seed);
            }
        }
    }

    @Test
    @DisplayName("hash：无符号取模 ⇒ 列号恒在 [0, width)（负数取模会越界）")
    void hashStaysInRange() throws Exception {
        HeavyKeeper hk = new HeavyKeeper(WIDTH, DEPTH, DECAY, TOP_K);
        Method m = HeavyKeeper.class.getDeclaredMethod("hash", String.class, int.class);
        m.setAccessible(true);
        for (String key : new String[]{"", "a", "中文", "🚀", "z".repeat(64)}) {
            for (int seed = 0; seed < DEPTH; seed++) {
                int col = (int) m.invoke(hk, key, seed);
                assertTrue(col >= 0 && col < WIDTH, "列号越界：" + col);
            }
        }
    }

    // ==================================================================
    // ③ Top-K 的进 / 出 / 排序
    // ==================================================================

    @Test
    @DisplayName("Top-K：未满时全收；满了之后只保留分数最高的")
    void topKEvictsLowest() {
        HeavyKeeper hk = new HeavyKeeper(WIDTH, DEPTH, DECAY, 2);
        hk.add("a", 10);
        hk.add("b", 20);
        assertEquals(2, hk.topKSize());

        hk.add("c", 30);
        // 容量 2 ⇒ 「c」(30) 进、「a」(10) 出
        List<HeavyKeeper.Item> top = hk.topK();
        assertEquals(2, top.size());
        assertEquals("c", top.get(0).key());
        assertEquals(30, top.get(0).count());
        assertEquals("b", top.get(1).key());

        // 再来一个更低的 ⇒ 不该挤掉任何一条
        hk.add("d", 1);
        assertEquals(2, hk.topKSize());
        assertNotEquals("d", hk.topK().get(0).key());
    }

    @Test
    @DisplayName("Top-K：按 count 降序（并列项次序不承诺）")
    void topKIsSortedDescending() {
        HeavyKeeper hk = new HeavyKeeper(WIDTH, DEPTH, DECAY, TOP_K);
        hk.add("low", 1);
        hk.add("high", 100);
        hk.add("mid", 50);

        List<HeavyKeeper.Item> top = hk.topK();
        assertEquals(3, top.size());
        assertEquals("high", top.get(0).key());
        assertEquals("mid", top.get(1).key());
        assertEquals("low", top.get(2).key());
    }

    @Test
    @DisplayName("构造器：width 非法必须当场炸（不能等到 add 时越界）")
    void constructorRejectsNonPositiveWidth() {
        try {
            new HeavyKeeper(0, DEPTH, DECAY, TOP_K);
            org.junit.jupiter.api.Assertions.fail("width=0 应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("width must be positive"));
        }
    }

    // ==================================================================
    // 差分实现（BigInteger · 全程无符号 · 与生产实现无共用算式）
    // ==================================================================

    /**
     * 独立的 FNV-1a 64 → 列号（<b>无符号</b>）。
     *
     * <p>与生产实现的差别只有一处，但那一处正是全部难点：
     * 生产用 {@code long}（有符号）＋ {@link Long#remainderUnsigned(long, long)}；
     * 本实现用 {@link BigInteger}（天然无符号）＋ {@code mod}。
     * ⇒ 若生产把「无符号余数」改成「有符号取模 + 补正」或 {@code floorMod}，本实现给出的
     * 期望值<b>不变</b>（它本来就是对的）⇒ ⇒ 生产侧会被判红。
     *
     * @param key   条目键
     * @param seed  行号（哈希种子）
     * @param width 桶数
     * @return 列号
     */
    private static int independentFnv1a64Column(String key, int seed, int width) {
        BigInteger modulus = BigInteger.ONE.shiftLeft(64);
        BigInteger h = new BigInteger("14695981039346656037");
        BigInteger prime = new BigInteger("1099511628211");
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h = h.xor(BigInteger.valueOf(b & 0xFF)).multiply(prime).mod(modulus);
        }
        h = h.xor(BigInteger.valueOf(seed & 0xFF)).multiply(prime).mod(modulus);
        return h.mod(BigInteger.valueOf(width)).intValue();
    }
}
