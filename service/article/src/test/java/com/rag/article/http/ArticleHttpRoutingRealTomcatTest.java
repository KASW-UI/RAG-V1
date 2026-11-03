package com.rag.article.http;

import com.rag.article.support.HttpRealTomcatTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>article 域 HTTP 层的「路由形状 + 字节面」契约</b> —— 真 Tomcat ＋ 真 Spring 上下文
 * ＋ 真 HTTP 请求（状态行 / 响应头 / body 长度逐字节）。
 *
 * <h2>① 真触发理由</h2>
 * <p>本类验的三件事<b>全都</b>是<b>容器层</b>的事实，MockMvc 验不到：
 * <ol>
 *   <li><b>「方法不匹配 ⇒ 404 + 空 body」（不是 405！）</b>
 *       ⇒ 取决于 {@code ArticleApiExceptionHandler#handleMethodNotSupported}
 *       把 {@code HttpRequestMethodNotSupportedException} 映成<b>裸 404</b>
 *       （而不是共享层的 {@code RouterErrorResponses#methodNotAllowed} = 405 + Allow）；
 *       ⚠️ 这一条<b>不能</b>照抄 message / like / hot 三域的 405 —— 那三个域没开
 *       {@code rest.WithCors()}，本域开了 ⇒ B 侧走 CORS handler ⇒ 404；</li>
 *   <li><b>「未注册路径 ⇒ 404 + \"404 page not found\\n\" + text/plain + nosniff」</b>
 *       ⇒ 取决于 {@code NoResourceFoundException} → {@code RouterErrorResponses#notFound}；</li>
 *   <li><b>状态行 / 响应头 / body 长度</b> ⇒ 只有真 Tomcat 才产出这组字节。</li>
 * </ol>
 *
 * <h2>② B 出处（逐条）</h2>
 * <table border="1">
 *   <caption>断言 ← B 侧出处</caption>
 *   <tr><th>断言</th><th>B 侧出处</th></tr>
 *   <tr>
 *     <td>方法不匹配 ⇒ <b>404</b>（不是 405）、<b>空 body</b>、无 {@code Allow}、无 {@code Content-Type}</td>
 *     <td>B 侧 {@code …/advice/GlobalExceptionHandler.java:338-343}
 *         （{@code handleMethodNotSupported}）+ 其 javadoc 的三段证据链
 *         （Go {@code rest/router/patrouter.go:71-82} → {@code rest/server.go:148-153}
 *         {@code rest.WithCors()} → {@code rest/internal/cors/handlers.go:44-48}）</td>
 *   </tr>
 *   <tr>
 *     <td>未注册路径 ⇒ 404 + {@code "404 page not found\n"} + text/plain + nosniff</td>
 *     <td>B 侧 {@code …/advice/GlobalExceptionHandler.java:280-284}
 *         （{@code handleNoResourceFound}）+ Go {@code rest/router/patrouter.go:73,93-97}
 *         （{@code http.NotFound} ⇒ {@code http.Error}）</td>
 *   </tr>
 *   <tr>
 *     <td>未注册路径 <b>不</b>进鉴权链 ⇒ 恒 404（与有没有 token 无关）</td>
 *     <td>B 侧 {@code …/interceptor/JwtAuthInterceptor.java:126-128}（守卫 1：
 *         {@code handler instanceof HandlerMethod}）+ Go {@code rest/engine.go:104-118}</td>
 *   </tr>
 *   <tr>
 *     <td>401 = 裸状态码（body 零字节、无 Content-Type）</td>
 *     <td>B 侧 {@code …/advice/GlobalExceptionHandler.java:121-125} + Go
 *         {@code rest/handler/authhandler.go:106-122}</td>
 *   </tr>
 * </table>
 *
 * <h2>③ 可证伪性</h2>
 * <ul>
 *   <li>把 {@code handleMethodNotSupported} 改回共享层的 405 实现
 *       ⇒ {@code methodMismatchOnRegisteredPathReturns404WithEmptyBody} <b>立刻红</b>；</li>
 *   <li>删掉 {@code JwtAuthInterceptor} 的「守卫 1」
 *       ⇒ 未注册路径会被遮蔽成 401 ⇒ {@code unregisteredPathIs404Not401} <b>立刻红</b>；</li>
 *   <li>把受保护路由表从「method + path」退化成「只看 path」
 *       ⇒ {@code POST /v1/article/{id}} 会变成 401 ⇒ 本类的 404 用例<b>立刻红</b>；</li>
 *   <li>把 {@code ArticleApiResponseAdvice} 的「放行 1（body == null）」删掉
 *       ⇒ 404 / 401 会被包成 {@code {"code":200,…}}
 *       ⇒ 本类的「空 body」断言<b>立刻红</b>。</li>
 * </ul>
 */
class ArticleHttpRoutingRealTomcatTest extends HttpRealTomcatTestBase {

    @BeforeEach
    void seed() {
        truncateArticleTables();
        seedTwoPublishedArticles();
    }

    // ==================================================================
    // ① 方法不匹配 ⇒ 404 + 空 body
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：方法不匹配 ⇒ **404** + 空 body（**不是 405**！因 article-api 唯一用 rest.WithCors()）")
    void methodMismatchOnRegisteredPathReturns404WithEmptyBody() {
        // /v1/article/{id} 注册的是 GET / PUT / DELETE ⇒ POST 是「路径命中、方法不对」
        assertMethodMismatchIs404EmptyBody(HttpMethod.POST, "/v1/article/" + ARTICLE_OLD);
        // /v1/articles 注册的是 GET
        assertMethodMismatchIs404EmptyBody(HttpMethod.POST, "/v1/articles");
        // /v1/upload 注册的是 POST
        assertMethodMismatchIs404EmptyBody(HttpMethod.GET, "/v1/upload");

        // ── 场景有效性：同一个路径、**正确的方法** ⇒ 不得是 404 ──
        ResponseEntity<String> get = rest.getForEntity("/v1/articles", String.class);
        assertNotEquals(404, get.getStatusCode().value(),
                "场景有效性：GET /v1/articles 是已注册路由 ⇒ 不得 404；实际=" + get.getStatusCode());
        ResponseEntity<String> put = rest.exchange("/v1/article/" + ARTICLE_OLD, HttpMethod.PUT,
                new HttpEntity<>("{}", authHeaders(tokenFor(Long.parseLong(SEED_AUTHOR)))),
                String.class);
        assertNotEquals(404, put.getStatusCode().value(),
                "场景有效性：PUT /v1/article/{id} 是已注册路由 ⇒ 不得 404；实际="
                        + put.getStatusCode());
    }

    // ==================================================================
    // ② 未注册路径 ⇒ 404 + Go 的 router body
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：未注册路径 ⇒ 404 + \"404 page not found\\n\" + text/plain + nosniff")
    void unregisteredPathUsesGoRouterShape() {
        ResponseEntity<String> response = rest.getForEntity("/v1/nope", String.class);

        assertEquals(404, response.getStatusCode().value(), "未注册路径必须是 404");
        assertEquals("404 page not found\n", response.getBody(),
                "🔴 body 必须是 Go http.NotFound 的字面量 + 换行（19 字节）");
        assertEquals("text/plain;charset=utf-8",
                normalizeContentType(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)),
                "🔴 404 必须是 text/plain; charset=utf-8");
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"),
                "🔴 404 必须带 nosniff（B: http.Error 的四行之一）");
    }

    @Test
    @DisplayName("🔴 真 Tomcat：未注册路径 + **无 token** ⇒ 仍是 404（路由匹配先于鉴权）")
    void unregisteredPathIs404Not401() {
        ResponseEntity<String> response = rest.exchange("/v1/nope", HttpMethod.POST,
                new HttpEntity<>("{}", jsonHeaders()), String.class);

        assertEquals(404, response.getStatusCode().value(),
                "🔴 未注册路径必须恒 404（与有没有 token 无关）—— "
                        + "若这里是 401，说明 JwtAuthInterceptor 的「守卫 1」丢了");
        assertEquals("404 page not found\n", response.getBody(),
                "🔴 未注册路径的 body 必须是 Go 的 404 page not found");
    }

    // ==================================================================
    // ③ 字节面：状态行 / 响应头 / body 长度
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：状态行与响应头的**字节面**（200 / 400 / 401 / 404 逐条）")
    void statusLineAndHeaderByteSurface() throws Exception {
        // ── ① 200：公开列表 ──
        String ok = rawHttp("GET /v1/articles HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Connection: close\r\n\r\n");
        assertEquals(expectedStatusLine(200), statusLineOf(ok),
                "🔴 成功路径的状态行必须逐字节是 \"HTTP/1.1 200 \""
                        + "（Tomcat 10.1 不发 reason phrase ⇒ T-7 框架限制）；实际=" + statusLineOf(ok));
        assertTrue(ok.contains("Content-Type: " + GO_JSON_CONTENT_TYPE),
                "🔴 成功路径的 Content-Type 必须是 " + GO_JSON_CONTENT_TYPE
                        + "（共享层 JsonContentTypeAdvice 钉死 + 容器归一化）；实际报文=" + ok);
        assertFalse(ok.contains("X-Content-Type-Options"),
                "🔴 成功路径**不得**带 nosniff（只有 http.Error 形状才有）；实际报文=" + ok);

        // ── ② 400：解析失败（text/plain + nosniff + 文案）──
        // ⚠️ 刻意用 **Content-Length: 0 的空 body**（不是畸形 JSON）：
        //    B 侧 httpx.Parse 的判据是 `r.ContentLength > 0 && Content-Type 含 application/json`
        //    ⇒ 长度 0 ⇒ **整个 body 被丢弃** ⇒ 等价于「零值 DTO」⇒ title 缺席
        //    ⇒ 落到 B 侧的必填文案（畸形 JSON 则是 Jackson 的措辞，两者都是 400 text/plain）。
        String bad = rawHttp("POST /v1/article HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Content-Type: application/json\r\n"
                + "Authorization: " + bearer(tokenFor(Long.parseLong(SEED_AUTHOR))) + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n");
        assertEquals(expectedStatusLine(400), statusLineOf(bad),
                "🔴 解析失败的状态行必须逐字节是 \"HTTP/1.1 400 \"；实际=" + statusLineOf(bad));
        assertTrue(bad.contains("Content-Type: text/plain;charset=utf-8"),
                "🔴 解析失败的 Content-Type 必须是 text/plain;charset=utf-8；实际报文=" + bad);
        assertTrue(bad.contains("X-Content-Type-Options: nosniff"),
                "🔴 解析失败必须带 nosniff；实际报文=" + bad);
        assertTrue(rawBodyOf(bad).startsWith("field \"title\" is not set"),
                "🔴 解析失败的 body 必须是 B 侧的必填文案；实际=" + rawBodyOf(bad));

        // ── ③ 401：鉴权失败（**零字节** body、无 Content-Type、无 nosniff）──
        String unauthorized = rawHttp("GET /v1/me/article/" + ARTICLE_OLD + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Connection: close\r\n\r\n");
        assertEquals(expectedStatusLine(401), statusLineOf(unauthorized),
                "🔴 401 的状态行必须逐字节是 \"HTTP/1.1 401 \"；实际=" + statusLineOf(unauthorized));
        assertEquals("", rawBodyOf(unauthorized),
                "🔴 401 的 body 必须是**零字节**（Go 侧只 WriteHeader(401)）；实际=" + rawBodyOf(unauthorized));
        assertFalse(unauthorized.contains("Content-Type:"),
                "🔴 401 不得带 Content-Type（B 侧只写状态码）；实际报文=" + unauthorized);
        assertFalse(unauthorized.contains("X-Content-Type-Options"),
                "🔴 401 不得带 nosniff（B 侧 unauthorized() 不走 http.Error）；实际报文=" + unauthorized);

        // ── ④ 404：未注册路径（text/plain + nosniff + Go 的字面量）──
        String notFound = rawHttp("GET /v1/nope HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Connection: close\r\n\r\n");
        assertEquals(expectedStatusLine(404), statusLineOf(notFound),
                "🔴 404 的状态行必须逐字节是 \"HTTP/1.1 404 \"；实际=" + statusLineOf(notFound));
        assertTrue(notFound.contains("Content-Type: text/plain;charset=utf-8"),
                "🔴 404 的 Content-Type 必须是 text/plain;charset=utf-8；实际报文=" + notFound);
        assertTrue(notFound.contains("X-Content-Type-Options: nosniff"),
                "🔴 404 必须带 nosniff；实际报文=" + notFound);
        assertEquals("404 page not found\n", rawBodyOf(notFound),
                "🔴 404 的 body 必须是 Go 的字面量 + 换行；实际=" + rawBodyOf(notFound));
    }

    @Test
    @DisplayName("真 Tomcat：方法不匹配的 404 是**裸状态码**（无 Content-Type / 无 Allow）")
    void methodMismatchHasNoContentTypeAndNoAllowHeader() {
        ResponseEntity<String> response = rest.exchange(
                "/v1/article/" + ARTICLE_OLD, HttpMethod.POST,
                new HttpEntity<>("{}", jsonHeaders()), String.class);

        assertEquals(404, response.getStatusCode().value(), "方法不匹配必须是 404");
        byte[] raw = response.getBody() == null
                ? new byte[0]
                : response.getBody().getBytes(StandardCharsets.UTF_8);
        assertEquals(0, raw.length,
                "🔴 方法不匹配的 body 必须是零字节（B 侧走 CORS handler ⇒ 裸 WriteHeader(404)）；"
                        + "实际=" + response.getBody());
        assertNull(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                "🔴 方法不匹配不得带 Content-Type；实际=" + response.getHeaders());
        assertNull(response.getHeaders().getFirst(HttpHeaders.ALLOW),
                "🔴 方法不匹配不得带 Allow（B 侧 CORS handler 不输出它）；实际=" + response.getHeaders());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 断言「路径命中、方法不对 ⇒ 404 + 零字节 body + 无 Content-Type / 无 Allow」。 */
    private void assertMethodMismatchIs404EmptyBody(HttpMethod method, String path) {
        ResponseEntity<String> response = rest.exchange(path, method,
                new HttpEntity<>("{}", jsonHeaders()), String.class);

        assertEquals(404, response.getStatusCode().value(),
                "🔴 " + method + " " + path + "（路径已注册、方法不对）⇒ 必须是 404，"
                        + "**不是 405**（B 侧 article-api 唯一用了 rest.WithCors()）");
        byte[] raw = response.getBody() == null
                ? new byte[0]
                : response.getBody().getBytes(StandardCharsets.UTF_8);
        assertEquals(0, raw.length,
                "🔴 方法不匹配的 body 必须是零字节；实际=" + response.getBody());
        assertNull(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                "🔴 方法不匹配不得带 Content-Type；实际=" + response.getHeaders());
        assertNull(response.getHeaders().getFirst(HttpHeaders.ALLOW),
                "🔴 方法不匹配不得带 Allow；实际=" + response.getHeaders());
    }
}
