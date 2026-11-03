package com.rag.article.http;

import com.rag.article.support.HttpRealTomcatTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>CORS 的真 Tomcat 契约测试</b> —— 对应 C 侧 {@code config/ArticleCorsFilter}。
 *
 * <h2>为什么必须是真 Tomcat（而不是 MockMvc）</h2>
 * <p>本类要断言的是 <b>响应头的字节面</b>（{@code Access-Control-*} 的取值、{@code Vary} 的<b>条数</b>）
 * 与 <b>状态行的字节面</b>（{@code HTTP/1.1 204 }，Tomcat 10.1 不发 reason phrase）。
 * <p>⇒ {@code MockMvc} 不经过真实的 Filter 链 ⇒ <b>根本不会调用</b>本 filter
 * ⇒ 用 MockMvc 断言 CORS = <b>恒假绿</b>（AGENTS.md §3.3）。
 *
 * <h2>B 出处（逐条）</h2>
 * <ul>
 *   <li><b>B 侧载体</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/config/ArticleGoStyleCorsFilter.java:77-146}</li>
 *   <li><b>B 侧测试</b>：{@code …/api/ArticleApiRouteAuthBoundaryTest.java:249-261}
 *       （「CORS：Go rest.WithCors() 无参 ⇒ 通配 + OPTIONS 恒 204」·
 *       {@code optionsIsAlwaysNoContent}：3 条路径全 204 + 空 body + {@code Access-Control-Allow-Origin: *}）</li>
 *   <li><b>Go 侧</b>：{@code rest/internal/cors/handlers.go:10-27}（七个响应头）·
 *       {@code :44-48}（notAllowed 被 CORS 接管 ⇒ 非 OPTIONS 的方法不匹配是 404）·
 *       {@code :61-66}（{@code OPTIONS} 短路写 204，<b>不调 next</b>）·
 *       {@code :117-123}（{@code Vary} 用 {@code header.Add} 追加）</li>
 * </ul>
 *
 * <h2>可证伪性</h2>
 * <p>本类的每条断言都落在<b>真实响应报文</b>上（裸 socket 逐字节）⇒
 * ① 若本 filter 的 {@code @Order} 从 {@code +1} 改成 {@code +7}（排在 {@code HttpMiddleware} 之后）
 * ⇒ {@code optionsWithoutTokenIsNotUnauthorized} 会红（预检会先被鉴权拦成 401）；
 * ② 若 {@code OPTIONS} 分支改成 {@code chain.doFilter(...)} 而非短路
 * ⇒ {@code optionsIsAlwaysNoContent} 会红（会落 404/405 而非 204）；
 * ③ 若给 {@code "*"} 分支补上 {@code Access-Control-Allow-Credentials}
 * ⇒ {@code allowCredentialsHeaderIsAbsent} 会红。
 */
public class ArticleHttpCorsRealTomcatTest extends HttpRealTomcatTestBase {

    /** Go {@code rest/internal/cors/handlers.go:10-27} 的七个响应头 + 取值（逐字）。 */
    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    private static final String MAX_AGE = "Access-Control-Max-Age";
    private static final String ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";

    private static final String METHODS_VALUE = "GET, HEAD, POST, PATCH, PUT, DELETE";
    private static final String ALLOW_HEADERS_VALUE =
            "Content-Type, Origin, X-CSRF-Token, Authorization, AccessToken, Token, Range";
    private static final String EXPOSE_HEADERS_VALUE =
            "Content-Length, Access-Control-Allow-Origin, Access-Control-Allow-Headers";
    private static final String MAX_AGE_VALUE = "86400";

    // ============== 1. OPTIONS 一律 204 ==============

    @Test
    @DisplayName("OPTIONS 任意路径（含未注册）⇒ 204 空 body + CORS 头（Go cors.Middleware 短路）")
    void optionsIsAlwaysNoContent() throws IOException {
        // B 出处：…/api/ArticleApiRouteAuthBoundaryTest.java:249-261（optionsIsAlwaysNoContent）
        for (String path : new String[]{"/v1/article", "/v1/upload", "/v1/nonexistent"}) {
            String raw = rawHttp("OPTIONS " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Origin: http://example.com\r\nConnection: close\r\n\r\n");

            // ⚠️ Tomcat 10.1 不发 reason phrase ⇒ 状态行是 "HTTP/1.1 204 "（码后有空格、无短语）
            assertEquals("HTTP/1.1 204 ", statusLineOf(raw), "OPTIONS " + path + " 的状态行");
            assertEquals("", rawBodyOf(raw), "OPTIONS " + path + " 的 body 必须是 0 字节");
            assertEquals("*", headerValue(raw, ALLOW_ORIGIN), "OPTIONS " + path + " 的 " + ALLOW_ORIGIN);
        }
    }

    @Test
    @DisplayName("OPTIONS 无 token ⇒ 仍 204（不 401）—— 短路发生在鉴权之前")
    void optionsWithoutTokenIsNotUnauthorized() throws IOException {
        // B 出处：Go rest/server.go:355-357（corsRouter 包在 router 外面 ⇒ 在鉴权链之前）
        //        ⇒ B 侧 ArticleApiRouteAuthBoundaryTest 的 OPTIONS 断言**不带任何 Authorization 头**却仍是 204
        String raw = rawHttp("OPTIONS /v1/article HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Origin: http://example.com\r\nConnection: close\r\n\r\n");
        assertEquals("HTTP/1.1 204 ", statusLineOf(raw));
        assertFalse(statusLineOf(raw).contains("401"), "OPTIONS 预检绝不能被鉴权拦成 401");
    }

    // ============== 2. 七个响应头逐条 ==============

    @Test
    @DisplayName("七个 CORS 响应头的取值逐条与 Go 一致")
    void corsHeadersMatchGoExactly() throws IOException {
        // B 出处：…/api/config/ArticleGoStyleCorsFilter.java:60-67 + :139-145
        String raw = rawHttp("GET /v1/articles HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Origin: http://example.com\r\nConnection: close\r\n\r\n");

        assertEquals("*", headerValue(raw, ALLOW_ORIGIN));
        assertEquals(METHODS_VALUE, headerValue(raw, ALLOW_METHODS));
        assertEquals(ALLOW_HEADERS_VALUE, headerValue(raw, ALLOW_HEADERS));
        assertEquals(EXPOSE_HEADERS_VALUE, headerValue(raw, EXPOSE_HEADERS));
        assertEquals(MAX_AGE_VALUE, headerValue(raw, MAX_AGE));
    }

    @Test
    @DisplayName("Access-Control-Allow-Credentials 刻意不存在（Go 的 \"*\" 分支条件写入 ⇒ 不写）")
    void allowCredentialsHeaderIsAbsent() throws IOException {
        // B 出处：…/api/config/ArticleGoStyleCorsFilter.java:60-67（class javadoc「刻意不写」段）
        String raw = rawHttp("GET /v1/articles HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Origin: http://example.com\r\nConnection: close\r\n\r\n");
        assertEquals(-1, raw.indexOf(ALLOW_CREDENTIALS),
                "Go 的 \"*\" 分支不写 Allow-Credentials ⇒ C 侧也不写（凭空多一个响应头就是多一项差异）");
    }

    // ============== 3. Vary 的条数 ==============

    @Test
    @DisplayName("非 OPTIONS ⇒ Vary 只有 Origin 一条；OPTIONS ⇒ 三条（Go 用 header.Add 追加）")
    void varyCarriesOneHeaderForNormalAndThreeForOptions() throws IOException {
        // B 出处：…/api/config/ArticleGoStyleCorsFilter.java:125-131 + Go handlers.go:117-123
        String normal = rawHttp("GET /v1/articles HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Origin: http://example.com\r\nConnection: close\r\n\r\n");
        assertEquals(List.of("Origin"), headerValues(normal, "Vary"),
                "非 OPTIONS 只追加 Origin 一条");

        String options = rawHttp("OPTIONS /v1/article HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Origin: http://example.com\r\nConnection: close\r\n\r\n");
        assertEquals(List.of("Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers"),
                headerValues(options, "Vary"),
                "OPTIONS 追加三条（Go 的 setVaryHeaders 对 OPTIONS 多加两条）");
    }

    // ============== 4. 与「方法不匹配 ⇒ 404 空 body」的共存 ==============

    @Test
    @DisplayName("非 OPTIONS 的方法不匹配 ⇒ 仍是 404 空 body（加 CORS 后不得改这条规则）")
    void methodMismatchIsStillNotFoundWithEmptyBody() throws IOException {
        // B 出处：…/api/advice/GlobalExceptionHandler.java:338-343（Go 的 notAllowed 被 CORS 接管
        //        ⇒ 非 OPTIONS 仍是 404 + 空 body；CORS handler 只对 OPTIONS 短路）
        String raw = rawHttp("PATCH /v1/article/900101001 HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Origin: http://example.com\r\nConnection: close\r\n\r\n");
        assertEquals("HTTP/1.1 404 ", statusLineOf(raw));
        assertEquals("", rawBodyOf(raw));
        // ⚠️ 但 CORS 头**仍然要有**（Go 的 corsRouter 包在 router 外面 ⇒ 404 也带 CORS 头）
        assertEquals("*", headerValue(raw, ALLOW_ORIGIN), "Go 的 404 同样带 CORS 头");
    }

    @Test
    @DisplayName("普通 GET ⇒ 响应带 Access-Control-Allow-Origin（跨域前端依赖）")
    void normalGetCarriesAllowOriginHeader() throws IOException {
        String raw = rawHttp("GET /v1/articles HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Origin: http://example.com\r\nConnection: close\r\n\r\n");
        assertTrue(headerValue(raw, ALLOW_ORIGIN) != null, "非 OPTIONS 的响应也必须带 " + ALLOW_ORIGIN);
        assertEquals("*", headerValue(raw, ALLOW_ORIGIN));
    }

    // ============== 工具 ==============

    /** 取响应报文里某个头的**第一个**值（大小写不敏感）。 */
    private static String headerValue(String rawResponse, String name) {
        List<String> values = headerValues(rawResponse, name);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * 取响应报文里某个头的**全部**值（保留出现顺序）。
     *
     * <p>⚠️ Go 用 {@code header.Add}（追加）⇒ 多个 {@code Vary} 是<b>多行</b>而非逗号合并
     * ⇒ 必须按「行」拆，不能按逗号拆。
     */
    private static List<String> headerValues(String rawResponse, String name) {
        List<String> out = new ArrayList<>();
        int separator = rawResponse.indexOf("\r\n\r\n");
        String head = separator < 0 ? rawResponse : rawResponse.substring(0, separator);
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                out.add(line.substring(colon + 1).trim());
            }
        }
        return out;
    }
}
