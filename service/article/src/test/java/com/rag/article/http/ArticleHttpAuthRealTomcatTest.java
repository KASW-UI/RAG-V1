package com.rag.article.http;

import com.rag.article.support.HttpRealTomcatTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>article 域 HTTP 层的「鉴权边界」契约</b> —— 真 Tomcat ＋ 真 Spring 上下文 ＋ 真 HTTP 请求。
 *
 * <h2>① 真触发理由</h2>
 * <p>「哪些端点要 JWT」这条边界由<b>三个</b>东西共同决定，缺一不可：
 * <ol>
 *   <li>{@code ArticleJwtAuthFilter}（Servlet Filter 段，验签）；
 *       ⚠️ 它<b>绝不写响应</b>（只验签、把结果放进 request attribute）⇒
 *       <b>「401 是谁写的」这件事本身</b>就是契约；</li>
 *   <li>{@code JwtAuthInterceptor}（HandlerInterceptor 段，消费验签结果、失败原样重抛）；</li>
 *   <li>{@code WebMvcConfig#addInterceptors}（只挂 {@code /v1/**}，
 *       <b>刻意没有</b> {@code excludePathPatterns} ⇒ public 端点也进拦截器，
 *       由拦截器<b>内部</b>的受保护路由表放行）。</li>
 * </ol>
 * <p>⇒ 只测「有没有 401」不够 ⇒ 本类同时断言 <b>body 零字节</b>、<b>没有 Content-Type</b>、
 * <b>没有 WWW-Authenticate</b>（Go 侧 {@code unauthorized()} 只 {@code WriteHeader(401)}）。
 *
 * <h2>② B 出处（逐条）</h2>
 * <table border="1">
 *   <caption>断言 ← B 侧出处</caption>
 *   <tr><th>断言</th><th>B 侧出处</th></tr>
 *   <tr>
 *     <td>受保护路由<b>恰好 4 个</b>：POST /v1/article · PUT /v1/article/{id} ·
 *         DELETE /v1/article/{id} · GET /v1/me/article/{id}</td>
 *     <td>B 侧 {@code …/auth/ArticleJwtProtectedRoutes.java:73-77}（受保护路由表）</td>
 *   </tr>
 *   <tr>
 *     <td>公开路由 3 个：GET /v1/article/{id} · GET /v1/articles · POST /v1/upload</td>
 *     <td>同表的对偶（第二/三段 {@code AddRoutes} <b>不带</b> {@code rest.WithJwt}）</td>
 *   </tr>
 *   <tr>
 *     <td>401 = <b>零字节 body</b>、无 Content-Type</td>
 *     <td>B 侧 {@code …/advice/GlobalExceptionHandler.java:121-125}
 *         （{@code ResponseEntity.status(401).build()}，注释引 Go
 *         {@code rest/handler/authhandler.go:106-122}「只 WriteHeader(401)」）</td>
 *   </tr>
 *   <tr>
 *     <td>验签失败<b>不区分原因</b>（缺头 / 空 token / 签名错 / 过期 ⇒ 同一个 401）</td>
 *     <td>B 侧 {@code …/auth/ArticleJwtSignatureVerifier.java:88-119}
 *         （四条失败分支都走同一个 {@code unauthorized()}）</td>
 *   </tr>
 *   <tr>
 *     <td>Bearer 前缀<b>大小写不敏感</b></td>
 *     <td>B 侧共享层 {@code com.rag.common.security.BearerTokenExtractor}
 *         （Go: {@code strings.ToUpper(tok[0:7]) == "BEARER "}）</td>
 *   </tr>
 * </table>
 *
 * <h2>③ 可证伪性</h2>
 * <ul>
 *   <li>把 {@code JwtAuthInterceptor} 的「守卫 1（{@code handler instanceof HandlerMethod}）」删掉
 *       ⇒ 未注册路径会被遮蔽成 401 ⇒ 本类的「public 路由免鉴权」与
 *       {@code ArticleHttpRoutingRealTomcatTest} 的 404 用例<b>同时红</b>；</li>
 *   <li>把 {@code ArticleJwtAuthFilter} 改成「自己写 401」⇒
 *       body 会带上 Content-Type ⇒ 本类的「零字节 + 无 Content-Type」断言<b>立刻红</b>；</li>
 *   <li>把受保护路由表扩成「只按路径匹配」（漏掉 method）⇒
 *       {@code POST /v1/article/{id}} 会变成 401 ⇒
 *       {@code ArticleHttpRoutingRealTomcatTest} 的 404 用例<b>立刻红</b>；</li>
 *   <li>把 {@code BearerTokenExtractor} 改成大小写敏感 ⇒
 *       {@code bearerPrefixIsCaseInsensitive} <b>立刻红</b>。</li>
 * </ul>
 */
class ArticleHttpAuthRealTomcatTest extends HttpRealTomcatTestBase {

    @BeforeEach
    void seed() {
        truncateArticleTables();
        seedTwoPublishedArticles();
    }

    // ==================================================================
    // ① 4 个受保护端点：无 token ⇒ 401 + 零字节 body
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：4 个受保护端点无 token ⇒ 401 + **零字节** body（无 Content-Type / 无 WWW-Authenticate）")
    void protectedRoutesWithoutTokenReturn401WithEmptyBody() {
        assertProtected401("POST", "/v1/article", "{\"title\":\"t\",\"content\":\"c\"}");
        assertProtected401("PUT", "/v1/article/" + ARTICLE_OLD, "{}");
        assertProtected401("DELETE", "/v1/article/" + ARTICLE_OLD, null);
        assertProtected401("GET", "/v1/me/article/" + ARTICLE_OLD, null);
    }

    @Test
    @DisplayName("🔴 真 Tomcat：签名不对 / token 过期 ⇒ 同样 401 + 零字节 body（Go 侧不区分失败原因）")
    void protectedRouteWithBadTokenReturns401WithEmptyBody() {
        assertProtected401("GET", "/v1/me/article/" + ARTICLE_OLD, null,
                bearer(tokenSignedWithWrongSecret(Long.parseLong(SEED_AUTHOR))));
        assertProtected401("GET", "/v1/me/article/" + ARTICLE_OLD, null,
                bearer(expiredTokenFor(Long.parseLong(SEED_AUTHOR))));
    }

    // ==================================================================
    // ② 3 个公开端点：免鉴权
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：3 个公开端点免鉴权（GET /v1/article/{id} · GET /v1/articles · POST /v1/upload）")
    void publicRoutesDoNotRequireToken() {
        ResponseEntity<String> detail = rest.getForEntity("/v1/article/" + ARTICLE_OLD, String.class);
        assertEquals(200, detail.getStatusCode().value(),
                "🔴 公开读详情不得要求 token（B: 第二段 AddRoutes 无 WithJwt）；实际="
                        + detail.getStatusCode());
        assertTrue(detail.getBody() != null && detail.getBody().contains("\"code\":200"),
                "场景有效性：公开读必须真的走到业务层（body 含 code:200）；实际=" + detail.getBody());

        ResponseEntity<String> list = rest.getForEntity("/v1/articles", String.class);
        assertEquals(200, list.getStatusCode().value(), "公开列表不得要求 token");
        assertTrue(list.getBody() != null && list.getBody().contains("\"code\":200"),
                "场景有效性：公开列表必须真的走到业务层；实际=" + list.getBody());

        ResponseEntity<String> upload = uploadWithoutToken();
        assertEquals(200, upload.getStatusCode().value(),
                "🔴 POST /v1/upload 是**公开**端点（B: 第三段 AddRoutes 无 WithJwt）"
                        + "——「上传图片要登录」是凭直觉会加上去、但 B 侧没有的规则");
    }

    @Test
    @DisplayName("真 Tomcat：Bearer 前缀大小写不敏感（B: strings.ToUpper(tok[0:7]) == \"BEARER \"）")
    void bearerPrefixIsCaseInsensitive() {
        String token = tokenFor(Long.parseLong(SEED_AUTHOR));
        for (String prefix : new String[]{"Bearer ", "bearer ", "BEARER "}) {
            ResponseEntity<String> response = rest.exchange(
                    "/v1/me/article/" + ARTICLE_OLD,
                    HttpMethod.GET,
                    new HttpEntity<>(authHeadersWithRaw(prefix + token)),
                    String.class);
            assertEquals(200, response.getStatusCode().value(),
                    "场景有效性：前缀 \"" + prefix.trim() + "\" 必须被接受（不得 401）；实际="
                            + response.getStatusCode());
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 对一条受保护路由发「无 token / 坏 token」的请求，并断言 401 的<b>字节面</b>。
     *
     * @param tokenHeader {@code Authorization} 头（{@code null} = 完全不带）
     */
    private void assertProtected401(String method, String path, String body, String tokenHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tokenHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, tokenHeader);
        }
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response =
                rest.exchange(path, HttpMethod.valueOf(method), entity, String.class);

        assertEquals(401, response.getStatusCode().value(),
                "🔴 " + method + " " + path + " 无有效 token ⇒ 必须是 401"
                        + "（B: authhandler.go:106 unauthorized()）");
        byte[] raw = response.getBody() == null
                ? new byte[0]
                : response.getBody().getBytes(StandardCharsets.UTF_8);
        assertEquals(0, raw.length,
                "🔴 401 的 body 必须是**零字节**（Go 侧只 WriteHeader(401)，不写任何字节）；"
                        + "实际=" + raw.length + " 字节，body=" + response.getBody());
        assertNull(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                "🔴 401 不得带 Content-Type（B 侧只写状态码；多一项就是漂移）；实际="
                        + response.getHeaders());
        assertNull(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE),
                "🔴 401 不得带 WWW-Authenticate（B 侧没有这一层）；实际=" + response.getHeaders());
    }

    private void assertProtected401(String method, String path, String body) {
        assertProtected401(method, path, body, null);
    }

    private ResponseEntity<String> uploadWithoutToken() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        ByteArrayResource file = new ByteArrayResource("fake-image-bytes".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "seed.png";
            }
        };
        form.add("image", file);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange("/v1/upload", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    /** 造一个「{@code Authorization} 头原样传入」的请求头（用于验 Bearer 前缀大小写）。 */
    private HttpHeaders authHeadersWithRaw(String authorization) {
        HttpHeaders headers = jsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        return headers;
    }
}
