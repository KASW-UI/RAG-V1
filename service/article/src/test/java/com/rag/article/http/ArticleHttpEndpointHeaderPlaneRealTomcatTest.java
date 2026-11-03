package com.rag.article.http;

import com.rag.article.support.HttpRealTomcatTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>U-3 · 逐端点穷举「响应头字节面」</b> —— 7 个端点<b>各自</b>的响应头名单<b>逐条</b>钉死。
 *
 * <h2>① 这个缺口是什么（台账出处）</h2>
 * <p>{@code docs/DRIFT/article/verification-article-service-2026-11-03.md} §11.6.5 <b>U-3</b>：
 * <blockquote>
 * 「<b>HTTP 响应头字节面</b> —— 「有端点」与「端点响应头逐字节相同」是两件事 ……
 * ⚠️ <b>仍存的缺口</b>：对 200/400/401/404 <b>四种响应形态</b>逐条断言了字节面，
 * <b>未</b>对 7 个端点<b>各自</b>穷举所有响应头。」
 * </blockquote>
 * ⇒ 本类补的就是那后半句：<b>形态 ≠ 端点</b> —— 同一个「200 形态」在 7 个端点上各出现一次，
 * 而原先只在 {@code GET /v1/articles} 上验过一次 ⇒ ⇒ <b>另外 6 个端点的响应头从来没被穷举过</b>。
 *
 * <h2>② 🔴 为什么必须是「完整名单」而不是「某几个头在场」</h2>
 * <p>断言「{@code Content-Type} 在、{@code nosniff} 不在」这种<b>存在性</b>断言，
 * <b>看不见</b>「凭空多了一个头」—— 而<b>多一个响应头就是一项字节面差异</b>。
 * ⇒ ⇒ 本类断言的是<b>响应头的完整名单</b>（排序后逐条相等，多一个 / 少一个都红）。
 *
 * <h2>③ 头面分<b>两层</b>判：应用层精确 / 容器层有界</h2>
 * <table border="1">
 *   <caption>两层判据</caption>
 *   <tr><th>层</th><th>判据</th><th>为什么这么分</th></tr>
 *   <tr><td><b>应用层</b><br>（CORS 5 头 ＋ {@code Vary} ＋ {@code Content-Type}
 *       ＋ {@code Traceparent} ＋（错误形态）{@code X-Content-Type-Options}）</td>
 *       <td><b>逐条精确相等</b>（保留 {@code Vary} 的<b>重复次数</b>）</td>
 *       <td>两侧源码都能指到出处 ⇒ ⇒ 是<b>跨侧契约面</b> ⇒ 逐条钉死</td></tr>
 *   <tr><td><b>容器层</b><br>（{@code Date} ＋ {@code Connection} ＋
 *       {@code Content-Length} <b>或</b> {@code Transfer-Encoding}）</td>
 *       <td><b>有界集合</b>：{@code Date} 与 {@code Connection} 必在；
 *           {@code Content-Length} 与 {@code Transfer-Encoding} <b>恰有一个</b>；
 *           <b>没有</b>第四个容器层头</td>
 *       <td>分帧头（{@code Content-Length} vs {@code Transfer-Encoding: chunked}）
 *           由 <b>Tomcat 写 body 的方式</b>决定 ⇒ <b>不是跨侧契约</b>
 *           （B 侧同样是 Tomcat，见 ⑤）⇒ ⇒ 只钉「恰有一个」＋ 各自取值合法性</td></tr>
 * </table>
 * ⇒ ⇒ <b>任何端点上多一个头或少一个头 ⇒ 立刻红</b>（这才是「穷举」）；
 * 而分帧方式的差异<b>不会</b>造成假红。
 *
 * <h2>④ 报文解析：必须跳过 1xx、必须解 chunked（B 侧同款）</h2>
 * <ol>
 *   <li><b>跳过 1xx</b> —— 共享层的断连探针会往 socket 直写
 *       {@code HTTP/1.1 103 Early Hints\r\n\r\n}（28 字节）⇒ 裸报文里会夹着 1xx 块
 *       ⇒ ⇒ 状态行与响应头必须取<b>最终响应块</b>。
 *       <br>B 出处：B 侧 {@code service/favorite/favorite-api/src/test/java/…/FavoriteApiRealTomcatGateTest.java:569}
 *       （「响应里可能夹着共享层写的 {@code 103 Early Hints}（1xx）⇒ ⇒ 解析时要取<b>最后一个</b>状态行」）
 *       ＋ {@code :609-610}（「共享层的 {@code GoStyleClientDisconnectProbe} 会连写 <b>4 个</b>
 *       {@code 103 Early Hints} ⇒ ⇒ 状态码必须取<b>最后一个</b>」）
 *       ⇒ ⇒ <b>两侧都有这 4 个 103</b>（共享层行为，已签字偏离 <b>SD-2026-10-25-04</b>）⇒ <b>不是漂移</b>。</li>
 *   <li><b>解 chunked</b> —— 最终响应可能是 {@code Transfer-Encoding: chunked}
 *       ⇒ ⇒ body 要<b>解块</b>，不能按「最后一个 \r\n\r\n」切。
 *       <br>B 出处：同上 {@code FavoriteApiRealTomcatGateTest.java:613-614}
 *       （「<b>chunked 编码</b>：最终响应是 {@code Transfer-Encoding: chunked} ⇒ ⇒ body 要<b>解块</b>」）
 *       ⇒ ⇒ <b>B 侧自己也是 chunked</b> ⇒ ⇒ 分帧头<b>不是 C 侧漂移</b>。</li>
 *   <li><b>字节 vs 字符</b> —— body 是 UTF-8 ⇒ 先按 ISO-8859-1 逐字节读（1:1 无损）。
 *       <br>B 出处：同上 {@code FavoriteApiRealTomcatGateTest.java:615}（2026-10-29 实测踩出来的三点）。</li>
 * </ol>
 *
 * <h2>⑤ B 出处（判据 ↔ B 侧出处 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ B 侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th></tr>
 *   <tr><td>CORS 5 个头 ＋ {@code Vary}</td>
 *       <td>{@code service/article/article-api/src/main/java/…/config/ArticleGoStyleCorsFilter.java:60-67}
 *           （5 个 {@code setHeader}）＋ {@code :125-131}（{@code setVaryHeaders}：
 *           <b>无条件</b> {@code addHeader("Vary","Origin")}，{@code OPTIONS} 才追加另两条）
 *           ⇒ C 侧同形实现 = {@code service/article/src/main/java/…/config/ArticleCorsFilter.java:156-162}</td></tr>
 *   <tr><td>成功形态的 {@code Content-Type}</td>
 *       <td>B 侧 {@code com.rag.common.response} 的 JSON 写出路径（{@code application/json}）</td></tr>
 *   <tr><td>错误形态的 {@code text/plain} ＋ {@code nosniff}</td>
 *       <td>B 侧 {@code service/common/src/main/java/…/http/GoStyleFormValues.java:295-296}
 *           （{@code http.Error} 的四行：状态码 ＋ Content-Type ＋ nosniff ＋ body）</td></tr>
 *   <tr><td>{@code Traceparent} 响应头</td>
 *       <td>B 侧 {@code service/common/src/main/java/…/http/GoStyleTraceparentFilter.java:263}
 *           （{@code carrier.setHeader(canonicalHeaderName(key), value)} ⇒ 键名被<b>首字母大写</b>）</td></tr>
 *   <tr><td>401 形态<b>无</b> {@code Content-Type}</td>
 *       <td>B 侧鉴权失败只写状态码，body 零字节 ⇒ 连 Content-Type 都没有
 *           （{@code service/article/article-api/src/main/java/…/auth/GoStyleJwtAuthFilter.java} 的 unauthorized 分支）</td></tr>
 *   <tr><td>404 的 body = {@code "404 page not found\n"}（19 字节）</td>
 *       <td>B 侧 {@code service/common/src/main/java/…/http/GoStyleRouterErrorResponses.java:88}
 *           ＋ {@code router/patrouter.go:73,93-97}（{@code http.NotFound} → {@code http.Error}）</td></tr>
 * </table>
 *
 * <h2>⑥ ⚠️ 404 的 <b>3 条 Vary</b>：C 侧比 B 侧多 2 条（<b>已登记 D-15</b>）</h2>
 * <p>本类把 404 的名单钉成 <b>3 条 {@code Vary}</b>（{@code Origin} ＋
 * {@code Access-Control-Request-Method} ＋ {@code Access-Control-Request-Headers}），
 * 而 B 侧只有 <b>1 条</b>（{@code Origin}）⇒ ⇒ <b>这是 C 侧多出来的字节面</b>。
 * <p><b>根因链（逐环可查）</b>：
 * <ol>
 *   <li>404 由 Spring 的<b>静态资源处理器</b>命中后抛出的 {@code NoResourceFoundException} 产出 ——
 *       B 侧同形：{@code service/article/article-api/src/main/java/…/advice/GlobalExceptionHandler.java:280-283}
 *       （{@code @ExceptionHandler(NoResourceFoundException.class)} → {@code GoStyleRouterErrorResponses.notFound}）
 *       ⇒ <b>两侧走的是同一条路径</b>；</li>
 *   <li>静态资源处理器 {@code ResourceHttpRequestHandler} <b>实现了</b> {@code CorsConfigurationSource}
 *       （Spring 6.2.16 {@code :97} / 7.0.10 {@code :98}）
 *       ⇒ {@code AbstractHandlerMapping.hasCorsConfigurationSource} 里
 *       {@code handler instanceof CorsConfigurationSource} <b>为真</b>
 *       （6.2.16 {@code :645-650} / 7.0.10 {@code :712-717}）
 *       ⇒ ⇒ 响应链上<b>必然</b>挂上 {@code CorsInterceptor}；</li>
 *   <li>🔴 <b>唯一的变量是 Spring 版本</b> ——
 *       {@code DefaultCorsProcessor.processRequest} 的 <b>首个分支不同</b>：
 *       <ul>
 *         <li>Spring <b>6.2.16</b>（C 侧 · Boot 3.5）：<b>先无条件</b>写 3 条 {@code Vary}，
 *             <b>之后</b>才判 {@code isCorsRequest} ⇒ ⇒ <b>3 条</b>；</li>
 *         <li>Spring <b>7.0.10</b>（B 侧 · Boot 4.0.8）：{@code config == null} ⇒
 *             <b>提前 return</b>（只打一条 debug 日志）⇒ ⇒ <b>0 条</b>
 *             ⇒ 加上 CORS filter 写的 {@code Vary: Origin} ⇒ B 侧共 <b>1 条</b>。</li>
 *       </ul></li>
 * </ol>
 * ⇒ ⇒ 属 <b>D14（工具链 = Jackson 2 + Boot 3.5）的框架版本副产物</b>
 * ⇒ ⇒ 登记为 <b>签字偏离 D-15</b>，<b>不在本类改码</b>。
 * <br>⚠️ 本类钉 3 条是<b>钉住现状</b>（防回归），<b>不是</b>「认可这 2 条是对的」。
 *
 * <h2>⑦ 可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>给某个端点<b>多加</b>一个响应头 ⇒ 对应端点的名单断言<b>立刻红</b>；</li>
 *   <li>把 {@code ArticleCorsFilter} 的某个 {@code setHeader} 删掉 ⇒ <b>7 个端点全红</b>；</li>
 *   <li>把 401 路径改成带上 {@code Content-Type} ⇒ 401 那条<b>立刻红</b>；</li>
 *   <li>把 {@code Traceparent} 的键名改回小写 ⇒ <b>立刻红</b>（名单里是 {@code Traceparent}）；</li>
 *   <li>把 404 的 {@code Vary} 从 3 条改成 1 条 ⇒ 404 那条<b>立刻红</b>
 *       （⇒ 也就是说：<b>哪天 D-15 被修掉，这条断言会主动报错</b>，提醒同步改台账）。</li>
 * </ul>
 *
 * <h2>⑧ 为什么用<b>裸 socket</b>而不是 {@code TestRestTemplate}</h2>
 * <p>{@code TestRestTemplate} 会把重复头折成集合、且看不到报文原样
 * ⇒ 本类的判据是「字节面」⇒ 必须走 {@link HttpRealTomcatTestBase#rawHttp(String)}
 * （手写 HTTP/1.1 请求、逐字节读回响应报文）。
 * <br>⚠️ multipart 的报文<b>手工拼</b>（不借 {@code RestTemplate} 的 multipart 转换器）
 * ⇒ 7 个端点用<b>同一种</b>观测手段 ⇒ 名单可比。
 */
class ArticleHttpEndpointHeaderPlaneRealTomcatTest extends HttpRealTomcatTestBase {

    /**
     * multipart 边界（手工拼报文用）。
     *
     * <p>⚠️ 刻意用<b>固定</b>字面量（不随机）⇒ 报文可复现 ⇒ 失败时可直接肉眼比对。
     */
    private static final String BOUNDARY = "----RagV1HeaderPlaneBoundary";

    /** B 侧 CORS filter 写的 5 个头（{@code ArticleGoStyleCorsFilter.java:60-67}）。 */
    private static final List<String> CORS_HEADERS = List.of(
            "Access-Control-Allow-Headers",
            "Access-Control-Allow-Methods",
            "Access-Control-Allow-Origin",
            "Access-Control-Expose-Headers",
            "Access-Control-Max-Age");

    /**
     * <b>成功形态（HTTP 200）</b>的完整应用层响应头名单。
     *
     * <p>⚠️ 名单里<b>没有</b> {@code X-Content-Type-Options}
     * ⇒ 成功路径<b>不得</b>带 {@code nosniff}（只有 {@code http.Error} 形状才有）。
     */
    private static final List<String> APP_HEADERS_200 = sorted(
            concat(CORS_HEADERS, List.of("Content-Type", "Traceparent", "Vary")));

    /**
     * <b>错误形态（400）</b>的完整应用层响应头名单。
     *
     * <p>比成功形态多一个 {@code X-Content-Type-Options: nosniff}
     * ⇒ 因为 B 侧的 {@code http.Error} 一定写它。
     */
    private static final List<String> APP_HEADERS_400 = sorted(
            concat(CORS_HEADERS, List.of(
                    "Content-Type", "Traceparent", "Vary", "X-Content-Type-Options")));

    /**
     * <b>401 形态</b>的完整应用层响应头名单。
     *
     * <p>🔴 <b>没有 {@code Content-Type}</b> —— B 侧的鉴权失败只写状态码，
     * body 零字节 ⇒ 连 Content-Type 都没有。
     */
    private static final List<String> APP_HEADERS_401 = sorted(
            concat(CORS_HEADERS, List.of("Traceparent", "Vary")));

    /**
     * <b>404 形态</b>的完整应用层响应头名单 —— <b>{@code Vary} 是 3 条</b>。
     *
     * <p>🔴 其中 2 条（{@code Access-Control-Request-Method} ＋
     * {@code Access-Control-Request-Headers}）是 <b>C 侧多出来的</b>
     * ⇒ 根因见类注释 ⑥ ⇒ 已登记 <b>签字偏离 D-15</b>。
     * <br>⚠️ <b>不要</b>把它「顺手统一」成 1 条 —— 那会让 404 与其它形态的差异消失。
     */
    private static final List<String> APP_HEADERS_404 = sorted(
            concat(CORS_HEADERS, List.of(
                    "Content-Type", "Traceparent", "Vary", "Vary", "Vary", "X-Content-Type-Options")));

    /**
     * <b>容器层</b>响应头里<b>恒在</b>的两个（Tomcat 10.1 自己加的）。
     *
     * <p>⚠️ B 侧同样是 Tomcat（Boot 4）⇒ 这两个头<b>两侧相同</b> ⇒ 归到「容器层」不是放水。
     */
    private static final List<String> CONTAINER_HEADERS_ALWAYS = List.of("Connection", "Date");

    /** 两种互斥的分帧头（<b>恰有一个</b>）。 */
    private static final List<String> FRAMING_HEADERS = List.of("Content-Length", "Transfer-Encoding");

    @BeforeEach
    void seed() {
        truncateArticleTables();
        seedTwoPublishedArticles();
    }

    // ==================================================================
    // ① 7 个端点 × 成功形态 —— 逐端点穷举响应头名单
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：7 个端点**各自**的成功响应头名单逐条钉死（不多不少）")
    void everyEndpointHasExactlyTheExpectedHeaderNames() throws Exception {
        String token = tokenFor(Long.parseLong(SEED_AUTHOR));

        // ── ① POST /v1/article（创建）──
        String created = rawHttp(request("POST", "/v1/article",
                headers(token, "application/json"), "{\"title\":\"t\",\"content\":\"c\"}"));
        assertHeaderPlane("POST /v1/article", created, 200, APP_HEADERS_200);

        // ── ② PUT /v1/article/{id}（更新）──
        String updated = rawHttp(request("PUT", "/v1/article/" + ARTICLE_OLD,
                headers(token, "application/json"), "{\"title\":\"t2\"}"));
        assertHeaderPlane("PUT /v1/article/{id}", updated, 200, APP_HEADERS_200);

        // ── ③ DELETE /v1/article/{id}（删除）──
        //    ⚠️ 刻意删 ARTICLE_NEW（与后面要读的 ARTICLE_OLD 不是同一篇）
        String deleted = rawHttp(request("DELETE", "/v1/article/" + ARTICLE_NEW,
                headers(token, null), ""));
        assertHeaderPlane("DELETE /v1/article/{id}", deleted, 200, APP_HEADERS_200);

        // ── ④ GET /v1/me/article/{id}（作者视角）──
        String mine = rawHttp(request("GET", "/v1/me/article/" + ARTICLE_OLD,
                headers(token, null), ""));
        assertHeaderPlane("GET /v1/me/article/{id}", mine, 200, APP_HEADERS_200);

        // ── ⑤ GET /v1/article/{id}（公开读详情）──
        String detail = rawHttp(request("GET", "/v1/article/" + ARTICLE_OLD,
                headers(null, null), ""));
        assertHeaderPlane("GET /v1/article/{id}", detail, 200, APP_HEADERS_200);

        // ── ⑥ GET /v1/articles（公开列表）──
        String list = rawHttp(request("GET", "/v1/articles", headers(null, null), ""));
        assertHeaderPlane("GET /v1/articles", list, 200, APP_HEADERS_200);

        // ── ⑦ POST /v1/upload（multipart 上传 · 唯一不走信封的端点）──
        String upload = rawHttp(request("POST", "/v1/upload",
                headers(null, "multipart/form-data; boundary=" + BOUNDARY),
                multipartBody("image", "seed.png", "fake-image-bytes")));
        assertHeaderPlane("POST /v1/upload", upload, 200, APP_HEADERS_200);
    }

    // ==================================================================
    // ② 四种响应形态 —— 各自的完整名单（把「形态」那条也变成穷举）
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：200 / 400 / 401 / 404 四种形态的响应头名单**各自**钉死")
    void everyResponseShapeHasExactlyTheExpectedHeaderNames() throws Exception {
        // ── 200（成功信封）──
        String ok = rawHttp(request("GET", "/v1/articles", headers(null, null), ""));
        assertHeaderPlane("200 形态（GET /v1/articles）", ok, 200, APP_HEADERS_200);
        assertFalse(headerNames(finalResponse(ok)).contains("X-Content-Type-Options"),
                "🔴 成功路径**不得**带 nosniff（只有 http.Error 形状才有）；实际报文=" + ok);

        // ── 400（解析失败）──
        // ⚠️ **必须带合法 token**：鉴权 filter（@Order +6）在 body 解析<b>之前</b>
        //    ⇒ 无 token 会先被拦成 401 ⇒ 根本到不了解析失败那条路径。
        String bad = rawHttp(request("POST", "/v1/article",
                headers(tokenFor(Long.parseLong(SEED_AUTHOR)), "application/json"), "{not-json"));
        assertHeaderPlane("400 形态（POST /v1/article 解析失败）", bad, 400, APP_HEADERS_400);
        assertEquals("nosniff", headerValue(finalResponse(bad), "X-Content-Type-Options"),
                "🔴 400 必须带 nosniff（B: http.Error 的四行之一）；实际报文=" + bad);

        // ── 401（鉴权失败）──
        String unauthorized = rawHttp(request("GET", "/v1/me/article/" + ARTICLE_OLD,
                headers(null, null), ""));
        assertHeaderPlane("401 形态（GET /v1/me/article/{id} 无 token）", unauthorized, 401,
                APP_HEADERS_401);
        assertFalse(headerNames(finalResponse(unauthorized)).contains("Content-Type"),
                "🔴 401 不得带 Content-Type（B 侧只写状态码 ⇒ body 零字节、无 Content-Type）；实际报文="
                        + unauthorized);
        assertEquals(0, decodedBody(finalResponse(unauthorized)).length,
                "🔴 401 的 body 必须**零字节**（B 侧 unauthorized 只写状态码）；实际报文=" + unauthorized);

        // ── 404（未注册路径）──
        String notFound = rawHttp(request("GET", "/v1/no-such-route", headers(null, null), ""));
        assertHeaderPlane("404 形态（未注册路径）", notFound, 404, APP_HEADERS_404);
        assertEquals("404 page not found\n", new String(decodedBody(finalResponse(notFound)),
                        StandardCharsets.UTF_8),
                "🔴 404 的 body 必须逐字节是 \"404 page not found\\n\"（19 字节，含尾随换行）"
                        + "（B: GoStyleRouterErrorResponses.java:88）；实际报文=" + notFound);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 断言一个响应的<b>完整响应头名单</b>（应用层精确 + 容器层有界）。
     *
     * @param label        端点标签（进失败信息）
     * @param raw          裸响应报文（可能夹着 1xx 块）
     * @param expectedCode 期望的 HTTP 状态码
     * @param appHeaders   期望的<b>应用层</b>头名单（已排序，含重复项）
     */
    private void assertHeaderPlane(String label, String raw, int expectedCode,
                                   List<String> appHeaders) {
        String block = finalResponse(raw);

        assertEquals(expectedStatusLine(expectedCode), statusLineOf(block),
                "🔴 " + label + " 的状态行必须逐字节是 \"HTTP/1.1 " + expectedCode + " \"；实际报文=" + raw);

        List<String> names = headerNames(block);

        // ── 应用层：逐条精确（保留重复项 ⇒ Vary 的条数也被钉住）──
        List<String> container = new ArrayList<>();
        for (String candidate : CONTAINER_HEADERS_ALWAYS) {
            if (names.contains(candidate)) {
                container.add(candidate);
            }
        }
        for (String candidate : FRAMING_HEADERS) {
            if (names.contains(candidate)) {
                container.add(candidate);
            }
        }
        List<String> actualApp = new ArrayList<>(names);
        actualApp.removeAll(container);

        assertEquals(appHeaders, sorted(actualApp),
                "🔴 " + label + " 的**应用层**响应头名单必须**逐条**一致（多一个 / 少一个都是字节面差异）；"
                        + "期望=" + appHeaders + "；实际=" + sorted(actualApp) + "；实际报文=" + raw);

        // ── 容器层：有界集合（两个恒在 + 分帧头恰有一个 + 没有第四个）──
        assertEquals(sorted(CONTAINER_HEADERS_ALWAYS), sorted(container.stream()
                        .filter(CONTAINER_HEADERS_ALWAYS::contains).toList()),
                "🔴 " + label + " 必须带且只带 " + CONTAINER_HEADERS_ALWAYS + " 这两个恒在的容器层头；实际=" + names);

        long framingCount = FRAMING_HEADERS.stream().filter(names::contains).count();
        assertEquals(1L, framingCount,
                "🔴 " + label + " 的分帧头（Content-Length / Transfer-Encoding）必须**恰有一个**；实际=" + names);

        List<String> unexpected = names.stream()
                .filter(n -> !appHeaders.contains(n))
                .filter(n -> !CONTAINER_HEADERS_ALWAYS.contains(n))
                .filter(n -> !FRAMING_HEADERS.contains(n))
                .distinct()
                .toList();
        assertTrue(unexpected.isEmpty(),
                "🔴 " + label + " 出现了**名单之外**的响应头 " + unexpected + "；实际报文=" + raw);

        // ── 分帧头的取值 + body 长度自洽 ──
        byte[] body = decodedBody(block);
        if (names.contains("Content-Length")) {
            assertEquals(Integer.parseInt(headerValue(block, "Content-Length")), body.length,
                    "🔴 " + label + " 的 Content-Length 必须等于实际 body 字节数；实际报文=" + raw);
        } else {
            assertEquals("chunked", headerValue(block, "Transfer-Encoding"),
                    "🔴 " + label + " 的 Transfer-Encoding 只能是 chunked；实际报文=" + raw);
        }
    }

    /**
     * 取<b>最终响应块</b> —— 跳过前置的 1xx 块。
     *
     * <p>⚠️ 共享层的断连探针会往 socket 直写 {@code HTTP/1.1 103 Early Hints\r\n\r\n}
     * ⇒ 裸报文里 1xx 块排在最终响应<b>之前</b>
     * ⇒ ⇒ 取「<b>第一个</b>非 1xx 块」与 B 侧记载的「取<b>最后一个</b>状态行」<b>等价</b>
     * （B 出处：{@code FavoriteApiRealTomcatGateTest.java:569,609-610}），
     * 且<b>不会</b>被 body 里偶然出现的 {@code HTTP/1.1 } 文本带偏。
     */
    private static String finalResponse(String raw) {
        int pos = 0;
        while (pos < raw.length()) {
            int lineEnd = raw.indexOf("\r\n", pos);
            if (lineEnd < 0) {
                break;
            }
            String line = raw.substring(pos, lineEnd);
            if (line.startsWith("HTTP/1.1 ")) {
                String code = line.substring(9, Math.min(12, line.length()));
                if (!code.startsWith("1")) {
                    return raw.substring(pos);
                }
                int separator = raw.indexOf("\r\n\r\n", pos);
                pos = separator < 0 ? raw.length() : separator + 4;
                continue;
            }
            pos = lineEnd + 2;
        }
        return raw;
    }

    /** 取响应的响应头名（<b>保留重复</b> —— 404 的 {@code Vary} 有 3 条）。 */
    private static List<String> headerNames(String block) {
        List<String> names = new ArrayList<>();
        int separator = block.indexOf("\r\n\r\n");
        String head = separator < 0 ? block : block.substring(0, separator);
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                names.add(line.substring(0, colon));
            }
        }
        return names;
    }

    /** 取响应的某个响应头值（大小写不敏感；取第一条）。 */
    private static String headerValue(String block, String name) {
        int separator = block.indexOf("\r\n\r\n");
        String head = separator < 0 ? block : block.substring(0, separator);
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /**
     * 取 body 的<b>字节</b>（chunked 时<b>解块</b>）。
     *
     * <p>⚠️ 必须解块：chunked 的 body 末尾是 {@code 0\r\n\r\n} ⇒
     * 按「最后一个 \r\n\r\n」切会切到块的终止符 ⇒ body 变空
     * （B 出处：{@code FavoriteApiRealTomcatGateTest.java:613-614}）。
     */
    private static byte[] decodedBody(String block) {
        int separator = block.indexOf("\r\n\r\n");
        if (separator < 0) {
            return new byte[0];
        }
        byte[] raw = block.substring(separator + 4).getBytes(StandardCharsets.ISO_8859_1);
        if (!"chunked".equalsIgnoreCase(String.valueOf(headerValue(block, "Transfer-Encoding")))) {
            return raw;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < raw.length) {
            int lineEnd = indexOfCrlf(raw, pos);
            if (lineEnd < 0) {
                break;
            }
            String sizeLine = new String(raw, pos, lineEnd - pos, StandardCharsets.ISO_8859_1).trim();
            int semicolon = sizeLine.indexOf(';');
            if (semicolon >= 0) {
                sizeLine = sizeLine.substring(0, semicolon).trim();
            }
            int size = Integer.parseInt(sizeLine, 16);
            pos = lineEnd + 2;
            if (size == 0) {
                break;
            }
            out.write(raw, pos, size);
            pos += size + 2;
        }
        return out.toByteArray();
    }

    /** 在字节数组里从 {@code from} 起找下一个 CRLF。 */
    private static int indexOfCrlf(byte[] data, int from) {
        for (int i = from; i + 1 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** 拼一条裸 HTTP/1.1 请求（{@code Connection: close} 必带 —— 否则读不到 EOF）。 */
    private static String request(String method, String path, String[] headers, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: 127.0.0.1\r\n");
        sb.append("Connection: close\r\n");
        for (String header : headers) {
            sb.append(header).append("\r\n");
        }
        // 🔴 **Content-Length 必带**：裸 HTTP/1.1 不带它 ⇒ Tomcat 按「无 body」处理
        //    ⇒ 带 body 的请求会被解析成「空 body」⇒ 400「field "title" is not set」
        //    ⇒ 与被测行为无关的**假红**（本轮第一次跑就踩到了）。
        if (body != null && !body.isEmpty()) {
            sb.append("Content-Length: ").append(body.getBytes(StandardCharsets.ISO_8859_1).length)
                    .append("\r\n");
        }
        sb.append("\r\n").append(body == null ? "" : body);
        return sb.toString();
    }

    /** 请求头数组：{@code Authorization}（可选）＋ {@code Content-Type}（可选）。 */
    private static String[] headers(String token, String contentType) {
        List<String> list = new ArrayList<>();
        if (token != null) {
            list.add("Authorization: Bearer " + token);
        }
        if (contentType != null) {
            list.add("Content-Type: " + contentType);
        }
        return list.toArray(new String[0]);
    }

    /** 手工拼 multipart 请求体（字段名固定 {@code image} —— B 侧契约）。 */
    private static String multipartBody(String fieldName, String fileName, String content) {
        return "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: image/png\r\n"
                + "\r\n"
                + content + "\r\n"
                + "--" + BOUNDARY + "--\r\n";
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(String::compareTo);
        return copy;
    }
}
