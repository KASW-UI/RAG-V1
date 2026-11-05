package com.rag.hot.service;

import com.rag.common.exception.BusinessException;
import com.rag.hot.dto.article.HotArticleItem;
import com.rag.hot.dto.article.HotArticlesReq;
import com.rag.hot.dto.article.HotArticlesResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>hot 域的输入解释与分页数值契约测试</b>。
 *
 * <p><b>B 出处</b>：
 * <ul>
 *   <li>{@code service/hot/hot-api/.../service/HotRankPaginator.java}
 *       （阈值常量 {@code :47-62} · {@code normalize} {@code :76-94} ·
 *       {@code paginate} {@code :104-120} · {@code assignRanks} {@code :139-149} ·
 *       {@code slice} {@code :157-167}）；</li>
 *   <li>{@code service/hot/hot-api/.../form/GoFormValues.java}
 *       （{@code raw} {@code :152-157} · {@code int32} {@code :169-186} ·
 *       {@code firstFormValue} {@code :200-216}）。</li>
 * </ul>
 *
 * <p><b>⚠️ 最有价值的一条是 {@link #paginateKeepsInt32OverflowBehavior()}</b>：
 * B 侧的 {@code (page - 1) * pageSize} 是 <b>int32 乘法</b> ⇒ 溢出后可能落回「看似合法」的区间
 * ⇒ ⇒ 若有人把参数提升成 {@code long}（「顺手修个溢出 bug」），
 * <b>可观察结果会变</b>（空页 ⇒ 有页）⇒ 本用例就是那颗钉子。
 */
class HotApiSupportContractTest {

    // ==================================================================
    // ① 分页归一（normalize）
    // ==================================================================

    @Test
    @DisplayName("normalize：page<=0 → 1，pageSize<=0 → 20，pageSize>50 → 50")
    void normalizeClampsToBsideThresholds() {
        assertArrayEquals(new int[]{1, 20}, HotApiSupport.normalize(null));
        assertArrayEquals(new int[]{1, 20}, HotApiSupport.normalize(new HotArticlesReq(0, 0)));
        assertArrayEquals(new int[]{1, 20}, HotApiSupport.normalize(new HotArticlesReq(-5, -9)));
        // page>0 原样保留；只有 pageSize 被夹到 50
        assertArrayEquals(new int[]{3, 50}, HotApiSupport.normalize(new HotArticlesReq(3, 999)));
        assertArrayEquals(new int[]{7, 30}, HotApiSupport.normalize(new HotArticlesReq(7, 30)));
    }

    @Test
    @DisplayName("normalize：四个阈值常量逐条钉死")
    void thresholdsArePinned() {
        assertEquals(1, HotApiSupport.DEFAULT_PAGE);
        assertEquals(20, HotApiSupport.DEFAULT_PAGE_SIZE);
        assertEquals(50, HotApiSupport.MAX_PAGE_SIZE);
        assertEquals(100, HotApiSupport.MAX_HOT_ARTICLES);
        assertEquals("rolling", HotApiSupport.ROLLING_SCOPE);
    }

    // ==================================================================
    // ② 分页切片（paginate）
    // ==================================================================

    @Test
    @DisplayName("paginate：正常区间")
    void paginateNormalRange() {
        assertArrayEquals(new int[]{0, 0}, HotApiSupport.paginate(0, 1, 20));
        assertArrayEquals(new int[]{0, 0}, HotApiSupport.paginate(-1, 1, 20));
        assertArrayEquals(new int[]{0, 10}, HotApiSupport.paginate(10, 1, 20));
        assertArrayEquals(new int[]{20, 40}, HotApiSupport.paginate(100, 2, 20));
        assertArrayEquals(new int[]{0, 20}, HotApiSupport.paginate(100, 1, 20));
        // 末页不足一整页
        assertArrayEquals(new int[]{80, 100}, HotApiSupport.paginate(100, 5, 20));
        // 越界页 ⇒ 空页
        assertArrayEquals(new int[]{0, 0}, HotApiSupport.paginate(100, 6, 20));
    }

    @Test
    @DisplayName("paginate：start 必须保持 int32 乘法（B 侧溢出行为）—— 提升成 long 会红")
    void paginateKeepsInt32OverflowBehavior() {
        // (1073741825 - 1) * 4 = 4294967296 = 2^32 ⇒ int32 回绕成 **0**
        // ⇒ int32 口径：start=0 ⇒ 命中 [0,4) ⇒ **有页**
        // ⇒ long 口径：start=4294967296 ≥ total ⇒ **空页**
        // ⇒ 两个口径的可观察结果不同 ⇒ 这条用例就是那颗钉子。
        assertArrayEquals(new int[]{0, 4}, HotApiSupport.paginate(100, 1073741825, 4));
    }

    // ==================================================================
    // ③ rank 分配与切片组装（assignRanks / slice）
    // ==================================================================

    @Test
    @DisplayName("assignRanks：过滤 null 后 rank 是稠密序号，且跨页连续")
    void assignRanksIsDenseAndSkipsNulls() {
        HotArticleItem a = item("a");
        HotArticleItem b = item("b");
        HotArticleItem c = item("c");

        List<HotArticleItem> ranked = HotApiSupport.assignRanks(
                Arrays.asList(a, null, b, null, c));

        assertEquals(3, ranked.size());
        assertEquals(1, ranked.get(0).rank());
        assertEquals(2, ranked.get(1).rank());
        assertEquals(3, ranked.get(2).rank());
        assertEquals("a", ranked.get(0).articleId());
        assertEquals("b", ranked.get(1).articleId());
        assertEquals("c", ranked.get(2).articleId());
    }

    @Test
    @DisplayName("assignRanks：全为 null ⇒ 返回空列表（不是 null）")
    void assignRanksOnAllNullReturnsEmptyList() {
        List<HotArticleItem> ranked =
                HotApiSupport.assignRanks(Arrays.asList(null, null));
        assertTrue(ranked.isEmpty());
    }

    @Test
    @DisplayName("slice：items 永不为 null（空页也是 []）；total 是过滤后的长度")
    void sliceNeverReturnsNullItems() {
        List<HotArticleItem> ranked = HotApiSupport.assignRanks(
                Arrays.asList(item("a"), item("b"), item("c")));

        HotArticlesResp page1 = HotApiSupport.slice(ranked, 1, 2);
        assertEquals(2, page1.items().size());
        assertEquals(3, page1.total());
        assertEquals(1, page1.page());
        assertEquals(2, page1.pageSize());
        assertEquals("rolling", page1.scope());

        HotArticlesResp page2 = HotApiSupport.slice(ranked, 2, 2);
        assertEquals(1, page2.items().size());
        assertEquals("c", page2.items().get(0).articleId());
        // rank 跨页连续：第 2 页第一条的 rank 是 3 而不是 1
        assertEquals(3, page2.items().get(0).rank());

        HotArticlesResp empty = HotApiSupport.slice(ranked, 9, 2);
        assertEquals(0, empty.items().size());
        assertEquals(3, empty.total());
    }

    // ==================================================================
    // ④ query 解析（raw / parseInt32 / firstFormValue）
    // ==================================================================

    @Test
    @DisplayName("raw：空串与 null 都算「键不存在」")
    void rawDropsEmptyValues() {
        assertNull(HotApiSupport.raw(null));
        assertNull(HotApiSupport.raw(""));
        assertEquals("5", HotApiSupport.raw("5"));
    }

    @Test
    @DisplayName("parseInt32：缺省 / 合法值 / 非法值 / 越界值")
    void parseInt32FollowsGoParseInt() {
        assertEquals(1, HotApiSupport.parseInt32(null, "page", 1));
        assertEquals(1, HotApiSupport.parseInt32("", "page", 1));
        assertEquals(5, HotApiSupport.parseInt32("5", "page", 1));
        // base 10：前导 0 合法，"0x10" 非法
        assertEquals(10, HotApiSupport.parseInt32("010", "page", 1));

        BusinessException notANumber =
                assertThrows(BusinessException.class, () -> HotApiSupport.parseInt32("abc", "page", 1));
        assertEquals(5001, notANumber.getCode());
        assertEquals("internal server error", notANumber.getMessage());

        // int32 越界 ⇒ 失败（与 B 侧 strconv.ParseInt(str, 10, 32) 一致）
        assertThrows(BusinessException.class,
                () -> HotApiSupport.parseInt32("2147483648", "page", 1));
        assertThrows(BusinessException.class,
                () -> HotApiSupport.parseInt32("-2147483649", "page", 1));
        // int32 边界值合法
        assertEquals(Integer.MAX_VALUE, HotApiSupport.parseInt32("2147483647", "page", 1));
        assertEquals(Integer.MIN_VALUE, HotApiSupport.parseInt32("-2147483648", "page", 1));
    }

    @Test
    @DisplayName("firstFormValue：同名多值取首值、空值丢弃、[] 数组记法等价")
    void firstFormValueTakesFirstNonEmpty() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("page", "5", "3");
        request.addParameter("page_size", "");
        assertEquals("5", HotApiSupport.firstFormValue(request, "page"));
        // 空值被整个丢弃 ⇒ 键「不存在」
        assertNull(HotApiSupport.firstFormValue(request, "page_size"));
        assertNull(HotApiSupport.firstFormValue(request, "absent"));

        MockHttpServletRequest arrayNotation = new MockHttpServletRequest();
        arrayNotation.addParameter("page[]", "7");
        assertEquals("7", HotApiSupport.firstFormValue(arrayNotation, "page"));
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 断言两个 int 数组逐元素相等（{@code assertArrayEquals} 的重载在 int[] 上可用，
     * 但显式写出来让失败信息更可读）。
     *
     * @param expected 期望
     * @param actual   实际
     */
    private static void assertArrayEquals(int[] expected, int[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }

    /**
     * 造一张最小卡片。
     *
     * @param id 文章 id
     * @return 卡片
     */
    private static HotArticleItem item(String id) {
        return new HotArticleItem(id, 0, 1L, "t", "b", "c", "a", 1L, 0, 0, 0, "m",
                Collections.emptyList());
    }
}
