package com.rag.favorite.http;

import com.rag.favorite.interceptor.QueryLimitInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 🔴 favorite 域的 <b>Go query 判据闸门</b>（{@link QueryLimitInterceptor}）的判别用例。
 *
 * <p><b>为什么要有这一层（真 Tomcat 门禁之外的第二道网）</b>：
 * 真 Tomcat 门禁（{@code FavoriteApiRealTomcatGateTest}）要起容器、慢；
 * 而本类测的是<b>判据本身</b>（Go 的四条规则逐条）⇒ ⇒ 快、且能把「哪条规则错了」定位到行。
 * <p>⚠️ 两层都必须有：只有真 Tomcat 那层 ⇒ 判据错了会被容器的其它行为掩盖；
 * 只有本层 ⇒ 「拦截器有没有真挂进 MVC 管线」没被证明。
 *
 * <p><b>Go 出处（真源逐行 · 消费点）</b>：
 * <ul>
 *   <li>{@code go-zero@v1.10.2/rest/httpx/util.go:15} {@code maxFormParamCount = 2048}；</li>
 *   <li>{@code :22-25} {@code GetFormValues} → {@code r.ParseForm()}（错误原样往上抛）；</li>
 *   <li>{@code :33-54} 非空值计数（{@code if len(v) == 0 { continue } } ＋ 计数器跨 key 共享）；</li>
 *   <li>{@code go-zero@v1.10.2/rest/httpx/requests.go:50-68} {@code httpx.Parse} → {@code ParseForm}
 *       ⇒ ⇒ <b>每个请求</b>都过一遍这四条规则；</li>
 *   <li>Go 1.25 {@code net/url/url.go:1019-1054} {@code parseQuery}（段数 / 分号 / 非法转义 +
 *       <b>它们的先后次序</b>）。</li>
 * </ul>
 *
 * <p>⚠️ <b>本类不测「拒成什么形状」</b> —— 那是本域 {@code FavoriteApiExceptionHandler} 的职责
 * （200 + 5003），由真 Tomcat 门禁端到端覆盖。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>类名</b>：B 侧 {@code FavoriteGoQueryLimitInterceptor}
 *             ⇒ C 侧 {@code QueryLimitInterceptor}（D7 包压平 · 域内不再带域前缀）；</li>
 *         <li><b>判据的落点</b>：B 侧调 {@code com.rag.common.http.GoStyleFormValues#goParseViolation}
 *             ⇒ C 侧调共享层的 {@code com.rag.common.web.GetFormValuesFilter#goParseViolation}
 *             （<b>同一份判据的共享层落点</b>，四条规则的语义与拒绝文案逐字相同）。</li>
 *         <li><b>包名</b>：测试类由 {@code com.rag.favorite.favorite.api}
 *             ⇒ {@code com.rag.favorite.http}（D7）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 八条用例的判据与口径逐条未改
 *       （段数 10000/10001 · 分号 · 非法转义 · 非空值 2048/2049 · 空值不计数 ·
 *       两条上限的先后 · 计数器跨 key 共享 · 空 query 放行 · 异常类型）。</li>
 *   <li><b>B 出处</b>：B 侧
 *       {@code service/favorite/favorite-api/src/test/java/com/rag/favorite/favorite/api/FavoriteGoQueryLimitInterceptorTest.java:16-175}
 *       ＋ 被测源 B 侧
 *       {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/interceptor/FavoriteGoQueryLimitInterceptor.java:91-131}
 *       （C 侧落点：{@code service/favorite/src/main/java/com/rag/favorite/interceptor/QueryLimitInterceptor.java:130-146}）。</li>
 * </ol>
 */
class FavoriteQueryLimitInterceptorTest {

    private final QueryLimitInterceptor interceptor = new QueryLimitInterceptor();

    /** 只 stub 一个 getter ⇒ 判据逻辑全部真跑（不是「mock 出来的假绿」）。 */
    private static HttpServletRequest requestWithQuery(String query) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getQueryString()).thenReturn(query);
        return request;
    }

    private void assertPasses(String query) {
        assertDoesNotThrow(() -> interceptor.preHandle(requestWithQuery(query), mock(HttpServletResponse.class), null),
                "query=[" + preview(query) + "] 必须放行（Go 的语义下不该被拒）");
    }

    private void assertRejects(String query, String expectedFragment) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> interceptor.preHandle(requestWithQuery(query), mock(HttpServletResponse.class), null),
                "query=[" + preview(query) + "] 必须被拒（Go 的语义下 httpx.Parse 会失败）");
        assertTrue(e.getMessage() != null && e.getMessage().contains(expectedFragment),
                "拒绝原因必须是 Go 的原文片段 [" + expectedFragment + "]；实际=[" + e.getMessage() + "]");
    }

    @Test
    @DisplayName("🔴 规则①：Go 的 net/url 段数上限 —— 10000 段放行 / 第 10001 段拒")
    void segmentLimitMatchesGoExactly() {
        assertPasses(repeatSegments(10000));
        assertRejects(repeatSegments(10001), "number of URL query parameters exceeded limit");
    }

    @Test
    @DisplayName("🔴 规则②：Go 的分号规则 —— rawQuery 任一处含 ';' 即拒（%3B 不触发）")
    void semicolonIsRejectedButEscapedSemicolonIsNot() {
        assertRejects("a=1;b=2", "invalid semicolon separator in query");
        // ⚠️ Go 判的是**原始**串 ⇒ 已转义的 %3B 不触发
        assertPasses("a=1%3Bb=2");
    }

    @Test
    @DisplayName("🔴 规则③：Go 的非法转义 —— ?a=%zz 必拒（Tomcat 是宽容的 ⇒ 不查这一条就会漏）")
    void invalidPercentEscapeIsRejected() {
        assertRejects("a=%zz", "invalid URL escape");
    }

    @Test
    @DisplayName("🔴 规则④：Go 的 maxFormParamCount=2048 —— 2048 个非空值放行 / 第 2049 个拒")
    void nonEmptyValueLimitMatchesGoExactly() {
        assertPasses(repeatNonEmptyValues(2048));
        assertRejects(repeatNonEmptyValues(2049), "too many form values");
    }

    @Test
    @DisplayName("🔴 规则④：Go 的空值不计数（util.go:44 `if len(v) == 0 { continue }`）⇒ 2049 个值里只有 1 个非空 ⇒ 放行")
    void emptyValuesAreNotCounted() {
        // 总「值」数 = 2049（> 2048）但非空值数 = 1 ⇒ Go 放行
        assertPasses("folderId=7" + "&folderId=".repeat(2048));
    }

    @Test
    @DisplayName("🔴 两条上限的**先后**可观察：10000 个非空值 ⇒ 段数那关过了（= 10000 不超）⇒ 被 2048 非空值计数拒（不是段数文案）")
    void segmentLimitIsCheckedBeforeValueCount() {
        // ⚠️ 这条是「两套夹具不能混用」的判据：若段数夹具用非空值 ⇒ 拒它的其实是 2048 计数
        //    ⇒ ⇒ 断言**文案**（"too many form values" vs "number of URL query parameters exceeded limit"）
        //    就能区分是哪条规则拒的。
        assertRejects(repeatNonEmptyValues(10000), "too many form values");
    }

    @Test
    @DisplayName("🔴 规则④：计数器跨所有 key 共享（util.go:33 `var n int` 在 key 循环之外）")
    void counterIsSharedAcrossKeys() {
        // 1024 个 key × 2 个非空值 = 2048 ⇒ 放行；再加 1 个 ⇒ 2049 ⇒ 拒
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1024; i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append("k").append(i).append("=1&k").append(i).append("=2");
        }
        assertPasses(sb.toString());
        assertRejects(sb + "&extra=1", "too many form values");
    }

    @Test
    @DisplayName("🔴 规则的**先后次序**可观察：段数超限先于分号（Go net/url/url.go:1020-1022 在解析之前就 return）")
    void segmentLimitIsCheckedBeforeParsing() {
        // 同时踩「段数超限」与「分号」⇒ Go 报的是**段数**那条
        assertRejects(repeatSegments(10001) + ";a=1", "number of URL query parameters exceeded limit");
    }

    @Test
    @DisplayName("🔴 空 query / 无 query 放行（Go: parseQuery 对空串走 `Count(\"\", \"&\") + 1 = 1`）")
    void emptyQueryPasses() {
        assertPasses(null);
        assertPasses("");
    }

    /** N 段 query（每段一个<b>空值</b> ⇒ 非空值数 = 0 ⇒ 只量「段数」那一维，不被 2048 计数干扰）。 */
    private static String repeatSegments(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append("k").append(i).append('=');
        }
        return sb.toString();
    }

    /** N 个非空值（同一个 key ⇒ Go 侧是同一个 name 的 N 个 value）。 */
    private static String repeatNonEmptyValues(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append("folderId=").append(i);
        }
        return sb.toString();
    }

    /** 断言消息里用的 query 预览（超长时截断）。 */
    private static String preview(String query) {
        if (query == null) {
            return "null";
        }
        return query.length() <= 80 ? query : query.substring(0, 80) + "…(" + query.length() + " chars)";
    }

    @Test
    @DisplayName("🧪 判据异常的**类型**必须是 IllegalArgumentException（本域兜底 handler 据此渲染 200+5003）")
    void violationTypeIsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> interceptor.preHandle(requestWithQuery("a=%zz"), mock(HttpServletResponse.class), null));
        assertEquals(IllegalArgumentException.class, e.getClass());
    }
}
