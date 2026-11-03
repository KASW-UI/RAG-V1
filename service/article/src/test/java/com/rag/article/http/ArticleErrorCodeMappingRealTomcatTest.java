package com.rag.article.http;

import com.rag.article.support.HttpRealTomcatTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>真触发</b>：<b>领域层异常 → gRPC code → 业务码 → HTTP 响应</b>这条<b>完整映射链</b>，
 * 在真 Tomcat 上逐条走一遍。
 *
 * <h2>🔴 为什么这条链必须端到端验</h2>
 * <p>映射链是<b>三段拼起来</b>的：
 * <pre>
 *   领域层抛 ArticleExceptions.Rpc(code)  →  API 层 statusCodeOf() 取 code
 *        →  API 层按 code 分支挑 ErrCodes  →  advice 把它包成 {code,msg,data} 信封（HTTP 200）
 * </pre>
 * ⇒ 任何<b>一段</b>单独看都可能是对的，链却可能是断的：
 * <ul>
 *   <li>领域层抛的是 {@code Raw}（⇒ UNKNOWN）而 API 层按 {@code NOT_FOUND} 分支处理
 *       ⇒ 码值整体错一位；</li>
 *   <li>API 层挑对了 {@code ErrCodes}，但 advice 把信封的 HTTP 状态写成 500
 *       ⇒ 客户端按 HTTP 判定成败 ⇒ 语义漂移；</li>
 *   <li>分支表少一条（如漏了 {@code PERMISSION_DENIED}）⇒ 落到 default ⇒ 变成 1015「服务繁忙」
 *       ⇒ **业务错误被误报成服务故障**（这正是 Go 侧刻意逐条枚举的原因）。</li>
 * </ul>
 * ⇒ 只能<b>端到端</b>走真 HTTP 才验得到 ⇒ 本类用<b>真 Tomcat + 真 PG</b>，
 * 并让领域层<b>真的</b>失败（改表名让真 SQL 报错 ⇒ 真 DataAccessException）。
 *
 * <h2>B 出处（逐条）</h2>
 * <ul>
 *   <li>B 侧 API 层逐条枚举：{@code …/api/service/ArticleGetService.java:25-53}
 *       （公开读 3 分支）· {@code …/api/service/ArticleGetAuthorService.java:24-55}
 *       （作者读 4 分支）—— C 侧对应 {@code service/ArticleApiService.java} 的
 *       {@code getArticle} / {@code getAuthorArticle} 两个 catch 块；</li>
 *   <li>B 侧 {@code …/api/service/ArticleApiSupport.java} 与
 *       {@code …/api/advice/GlobalExceptionHandler.java:139-143}（信封 = HTTP 200）；</li>
 *   <li>Go 侧：{@code get_article_logic.go:129-135}（公开读 DB 失败 ⇒
 *       {@code status.Error(codes.Internal, "read publication failed")}）·
 *       {@code get_article_logic.go:59-62}（作者读 FindOne 失败 ⇒ <b>原样返回</b>
 *       ⇒ 不翻译 ⇒ 落 UNKNOWN）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code getAuthorArticle} 的 {@code PERMISSION_DENIED} 分支删掉
 *       ⇒ ② <b>立刻红</b>（变成 1015「服务繁忙」）；</li>
 *   <li>把 {@code statusCodeOf} 改成恒返回 {@code UNKNOWN} ⇒ ①③ <b>立刻红</b>；</li>
 *   <li>把 {@code handleBusiness} 的 HTTP 状态从 200 改成 500 ⇒ 四条 <b>全部红</b>；</li>
 *   <li>把 {@code readPublication} 的包装去掉 ⇒ ③ <b>立刻红</b>（变成 1015 而不是 5001）。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>复用基类的种子数据（{@code 900101001} / {@code 900101002}，作者 {@code 900101001}）。
 */
class ArticleErrorCodeMappingRealTomcatTest extends HttpRealTomcatTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void reset() {
        truncateArticleTables();
        seedTwoPublishedArticles();
    }

    // ==================================================================
    // ① NOT_FOUND → 1002「文章不存在」
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：公开读**不存在的文章** ⇒ 领域层 NOT_FOUND ⇒ 业务码 1002「文章不存在」（HTTP 200 + 信封）")
    void publicReadOfMissingArticleMapsNotFoundTo1002() {
        ResponseEntity<String> response = rest.getForEntity("/v1/article/no-such-article-id", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "业务错误必须是 HTTP 200 + 信封（Go: httpx.OkJsonCtx 形状），不是 4xx");
        JsonNode body = parse(response);
        assertEquals(1002, body.path("code").asInt(),
                "NOT_FOUND 必须映射到 1002「文章不存在」");
        assertEquals("文章不存在", body.path("msg").asText());
        assertTrue(body.has("data"), "信封必须带 data 键（此处为 null）");
    }

    // ==================================================================
    // ② PERMISSION_DENIED → 1006「只能编辑或删除自己的文章」
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：作者读**别人的文章** ⇒ 领域层 PERMISSION_DENIED ⇒ 业务码 1006（不是 1015「服务繁忙」）")
    void authorReadOfOtherAuthorsArticleMapsPermissionDeniedTo1006() {
        String otherToken = tokenFor(Long.parseLong(OTHER_AUTHOR));

        ResponseEntity<String> response = rest.exchange(
                "/v1/me/article/" + ARTICLE_OLD, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(authHeaders(otherToken)), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "业务错误 = HTTP 200 + 信封");
        JsonNode body = parse(response);
        assertEquals(1006, body.path("code").asInt(),
                "🔴 PERMISSION_DENIED 必须映射到 1006；"
                        + "若得到 1015 ⇒ 说明该分支漏了（业务错误被误报成服务故障）");
        assertEquals("只能编辑或删除自己的文章", body.path("msg").asText());
    }

    // ==================================================================
    // ③ INTERNAL → 5001「系统内部错误」
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：公开读的**真 DB 失败** ⇒ 领域层 INTERNAL ⇒ 业务码 5001（不是 1015）")
    void publicReadDbFailureMapsInternalTo5001() {
        // 让公开读用到的表**真的不存在** ⇒ 真 SQL 错误（42P01）⇒ 真 DataAccessException
        jdbcTemplate.execute("ALTER TABLE article_publication RENAME TO article_publication_hidden");
        try {
            ResponseEntity<String> response = rest.getForEntity("/v1/article/" + ARTICLE_OLD, String.class);

            assertEquals(HttpStatus.OK, response.getStatusCode(), "业务错误 = HTTP 200 + 信封");
            JsonNode body = parse(response);
            assertEquals(5001, body.path("code").asInt(),
                    "🔴 公开读的 DB 失败必须映射到 5001「系统内部错误」"
                            + "（Go: codes.Internal ⇒ 业务码 5001）；"
                            + "若得到 1015 ⇒ 说明异常翻译丢了（裸 UNKNOWN 落 default）");
            assertEquals("系统内部错误", body.path("msg").asText());
        } finally {
            jdbcTemplate.execute("ALTER TABLE article_publication_hidden RENAME TO article_publication");
        }
    }

    // ==================================================================
    // ④ UNKNOWN（default 分支）→ 1015「服务繁忙」
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：作者读的**真 DB 失败** ⇒ 领域层原样抛出（不翻译）⇒ UNKNOWN ⇒ 落 default ⇒ 业务码 1015")
    void authorReadDbFailureFallsThroughToDefaultBranch1015() {
        // ⚠️ 这里坏的是**主表** article ⇒ 作者视角的第一条 SQL 就失败
        //    ⇒ 领域层按 Go get_article_logic.go:59-62「原样返回」⇒ 不翻译成 INTERNAL
        //    ⇒ API 层 statusCodeOf 看到的是裸 DataAccessException ⇒ UNKNOWN ⇒ default 分支
        jdbcTemplate.execute("ALTER TABLE article RENAME TO article_hidden");
        try {
            String token = tokenFor(Long.parseLong(SEED_AUTHOR));
            ResponseEntity<String> response = rest.exchange(
                    "/v1/me/article/" + ARTICLE_OLD, org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token)), String.class);

            assertEquals(HttpStatus.OK, response.getStatusCode(), "业务错误 = HTTP 200 + 信封");
            JsonNode body = parse(response);
            assertEquals(1015, body.path("code").asInt(),
                    "🔴 未被分支表覆盖的失败必须落 default = 1015「服务繁忙」"
                            + "（Go 侧 PermissionDenied 落 default 是**刻意**的）；"
                            + "若得到 5001 ⇒ 说明有人把裸异常也翻译成了 INTERNAL ⇒ 与 Go 不一致");
            assertEquals("服务繁忙", body.path("msg").asText());
        } finally {
            jdbcTemplate.execute("ALTER TABLE article_hidden RENAME TO article");
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private JsonNode parse(ResponseEntity<String> response) {
        assertNotNull(response.getBody(), "响应体不得为 null");
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON：" + response.getBody(), e);
        }
    }
}
