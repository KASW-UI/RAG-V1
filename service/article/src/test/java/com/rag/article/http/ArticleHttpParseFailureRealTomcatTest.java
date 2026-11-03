package com.rag.article.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>article 域 HTTP 层的「解析失败 + query 真值表」契约</b> —— 真 Tomcat ＋ 真 Spring 上下文
 * ＋ 真 HTTP 请求。
 *
 * <h2>① 真触发理由</h2>
 * <p>本类验的每一条都<b>只有在真容器上</b>才成立：
 * <ol>
 *   <li><b>「解析失败 ⇒ 400 + text/plain + nosniff」</b>
 *       ⇒ 取决于 {@code ArticleApiExceptionHandler} 产出的 {@code ResponseEntity<byte[]>}
 *       与 {@code ArticleApiResponseAdvice}「放行 4（byte[]）」的<b>相互作用</b>
 *       —— 少放行一次，body 就会变成 {@code {"code":200,"msg":"OK","data":[B@…]}}
 *       且后续序列化再抛异常变成 500（B 侧 {@code GlobalResponseAdvice.java:94-104}
 *       的注释明写「第一次实现漏了这条，5 条断言 400 text/plain 的用例全部失败」）；</li>
 *   <li><b>「{@code nosniff}」</b> ⇒ 由共享层 {@code PlainTextErrors#text} 写死
 *       ⇒ 只有真 Tomcat 才能观测到响应头；</li>
 *   <li><b>query 真值表</b> ⇒ 取决于 {@code FormValues} 与
 *       {@code ListArticlesReq#of} 的组合（空值先丢、再取首、bool 真值表 1/0、int 走 base-10）；</li>
 *   <li><b>必填文案 / 类型不符文案</b> ⇒ 取决于 Jackson {@code @JsonCreator} 抛出的
 *       {@code BadRequestException} 被包成 {@code HttpMessageNotReadableException} 后
 *       取<b>最内层</b> cause 的那条逻辑（{@code rootMessage}）。</li>
 * </ol>
 *
 * <h2>② B 出处（逐条）</h2>
 * <table border="1">
 *   <caption>断言 ← B 侧出处</caption>
 *   <tr><th>断言</th><th>B 侧出处</th></tr>
 *   <tr>
 *     <td>解析失败 = 400 + {@code text/plain; charset=utf-8} + {@code X-Content-Type-Options: nosniff}
 *         + {@code err.Error() + "\n"}</td>
 *     <td>B 侧 {@code …/advice/GlobalExceptionHandler.java:237-255}（{@code handleParseFailure}）
 *         ＋ 其类注释 {@code :34-56}（article-api <b>没有</b> {@code httpx.SetErrorHandler}
 *         ⇒ 落 {@code http.Error} ⇒ 400 text/plain + nosniff）</td>
 *   </tr>
 *   <tr>
 *     <td>必填文案 = {@code field "title" is not set} / {@code field "content" is not set}</td>
 *     <td>B 侧 {@code …/advice/GlobalExceptionHandler.java:263-267}（{@code handleGoRequiredField}）
 *         ＋ {@code …/dto/CreateArticleReq.java:108-119}（必填校验，title 先判）</td>
 *   </tr>
 *   <tr>
 *     <td>类型不符文案 = {@code type mismatch for field "title", expect "string", actual "number"}</td>
 *     <td>B 侧 {@code …/dto/GoStyleStringFields.java:62-78}（{@code parse} 逐字照抄）</td>
 *   </tr>
 *   <tr>
 *     <td>空 body ⇒ 与「title 缺席」同一条文案</td>
 *     <td>B 侧 {@code …/controller/ArticleController.java:123-129}
 *         （{@code GoStyleRequest.normalize} 补零值 DTO ⇒ title 缺席）</td>
 *   </tr>
 *   <tr>
 *     <td>非 JSON 的 Content-Type ⇒ <b>丢弃整个 body</b> ⇒ 等价于空 body</td>
 *     <td>B 侧共享层 {@code GoStyleJsonBodyConverter}（Go {@code httpx/requests.go:117-124}
 *         的 {@code ParseJsonBody}：{@code withJsonBody} 为假 ⇒ {@code UnmarshalJsonMap(nil, v)}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ?page=&page=2} ⇒ 取 {@code 2}（<b>先丢空再取首</b>）</td>
 *     <td>B 侧 {@code …/form/GoFormValues.java:133-144}（{@code first}）
 *         ＋ 其 javadoc 引的 Go {@code httpx/util.go:44-45}（空值先被丢掉）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code desc} 真值表 = {@code 1/0/true/false}；其它 ⇒ 400</td>
 *     <td>B 侧 {@code …/form/GoFormValues.java:154-168}（{@code bool}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code page=abc} ⇒ 400 + {@code strconv.ParseInt: parsing "abc": invalid syntax (field "page")}</td>
 *     <td>B 侧 {@code …/form/GoFormValues.java:177-193}（{@code int32}）</td>
 *   </tr>
 *   <tr>
 *     <td>字符串参数<b>不 trim</b>（原样透传）</td>
 *     <td>B 侧 {@code …/form/GoFormValues.java:201-204}（{@code text}：{@code return str, nil}）</td>
 *   </tr>
 *   <tr>
 *     <td>multipart 字段名固定 = {@code image}</td>
 *     <td>B 侧 {@code …/service/ArticleUploadService.java:17}（{@code FORM_FIELD = "image"}）</td>
 *   </tr>
 * </table>
 *
 * <h2>③ 可证伪性</h2>
 * <ul>
 *   <li>把 {@code ArticleApiExceptionHandler#handleParseFailure} 映成「200 + 信封」
 *       ⇒ 本类<b>每一条</b> 400 用例都会红（状态码 + Content-Type 同时变）；</li>
 *   <li>删掉 {@code ArticleApiResponseAdvice} 的「放行 4（byte[]）」
 *       ⇒ 400 用例会变成 200 + Base64 乱码 ⇒ <b>立刻红</b>；</li>
 *   <li>把 {@code FormValues#first} 改成「先取首再过滤空」
 *       ⇒ {@code queryMultiValuePageTakesFirstNonEmptyValue} <b>立刻红</b>
 *       （{@code ?page=&page=2} 会退回默认 1）；</li>
 *   <li>把 {@code FormValues#bool} 改成 Spring 的 {@code Boolean.valueOf} 语义
 *       ⇒ {@code desc=1} 会变成 400 ⇒ <b>立刻红</b>；</li>
 *   <li>给 {@code FormValues#text} 加 {@code .trim()}
 *       ⇒ {@code queryStringValueIsNotTrimmed} <b>立刻红</b>（total 会从 0 变成 2）；</li>
 *   <li>把 multipart 字段名从 {@code image} 改成别的
 *       ⇒ {@code uploadFieldNameIsFixedToImage} <b>立刻红</b>。</li>
 * </ul>
 */
class ArticleHttpParseFailureRealTomcatTest extends HttpRealTomcatTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void seed() {
        truncateArticleTables();
        seedTwoPublishedArticles();
    }

    // ==================================================================
    // ① 必填 / 类型不符
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：空 body ⇒ 400 + text/plain + nosniff + \"field \\\"title\\\" is not set\"")
    void emptyBodyOnCreateIs400PlainTextWithGoRequiredText() {
        ResponseEntity<String> response = postCreateWithToken("");

        assertEquals(400, response.getStatusCode().value(),
                "🔴 空 body 必须是 400（B: UnmarshalJsonMap(nil,v) ⇒ title 缺席 ⇒ 必填失败）");
        assertPlainTextWithNosniff(response,
                "解析失败必须是 text/plain + nosniff（B: http.Error 的四行）");
        assertEquals("field \"title\" is not set\n", response.getBody(),
                "🔴 文案必须逐字等于 B 侧的 field \"title\" is not set（含尾随换行）");

        // ── 场景有效性 ①：同一个端点、**不带 token** ⇒ 401（证明请求确实到达了这条受保护路由，
        //    且上面的 400 来自「参数解析/必填」而不是来自鉴权层）──
        ResponseEntity<String> noToken = postCreate("", null);
        assertEquals(401, noToken.getStatusCode().value(),
                "场景有效性：不带 token 必须先被鉴权层拦成 401；实际=" + noToken.getStatusCode());

        // ── 场景有效性 ②：body 换成「有 title、缺 content」⇒ 文案**必须换一条**
        //    （证明解析器在区分「哪个字段缺席」，而不是恒回同一条 400）──
        ResponseEntity<String> otherMissing = postCreateWithToken("{\"title\":\"t\"}");
        assertEquals("field \"content\" is not set\n", otherMissing.getBody(),
                "场景有效性：换一个缺席字段必须得到另一条文案；实际=" + otherMissing.getBody());
    }

    @Test
    @DisplayName("🔴 真 Tomcat：content 缺席 ⇒ 400 + \"field \\\"content\\\" is not set\"")
    void missingContentFieldIs400WithContentNotSet() {
        ResponseEntity<String> response = postCreateWithToken("{\"title\":\"t\"}");

        assertEquals(400, response.getStatusCode().value(), "content 缺席必须是 400");
        assertPlainTextWithNosniff(response, "必填失败必须是 text/plain + nosniff");
        assertEquals("field \"content\" is not set\n", response.getBody(),
                "🔴 文案必须逐字等于 field \"content\" is not set");
    }

    @Test
    @DisplayName("🔴 真 Tomcat：title 类型不符 ⇒ 400 + \"type mismatch for field \\\"title\\\", expect \\\"string\\\", actual \\\"number\\\"\"")
    void typeMismatchOnTitleIs400WithGoTypeMismatchText() {
        ResponseEntity<String> response = postCreateWithToken("{\"title\":123,\"content\":\"c\"}");

        assertEquals(400, response.getStatusCode().value(),
                "🔴 类型不符必须是 400（B: processFieldPrimitive ⇒ type mismatch）");
        assertPlainTextWithNosniff(response, "类型不符必须是 text/plain + nosniff");
        assertEquals("type mismatch for field \"title\", expect \"string\", actual \"number\"\n",
                response.getBody(),
                "🔴 文案必须逐字等于 Go 的 newTypeMismatchError 三段式");
    }

    @Test
    @DisplayName("真 Tomcat：畸形 JSON ⇒ 400 + text/plain + nosniff（**不是** 200 + 5001 信封）")
    void malformedJsonIs400PlainTextNotEnvelope() {
        ResponseEntity<String> response = postCreateWithToken("{");

        assertEquals(400, response.getStatusCode().value(),
                "🔴 畸形 JSON 必须是 400（B 侧 article-api 没有 httpx.SetErrorHandler "
                        + "⇒ 落 http.Error ⇒ 400；**不是** message 域那套 200 + {code:5001}）");
        assertPlainTextWithNosniff(response, "解析失败必须是 text/plain + nosniff");
        assertFalse(response.getBody().contains("\"code\""),
                "🔴 解析失败的 body 里不得出现信封的 code 键；实际=" + response.getBody());
        assertFalse(response.getBody().contains("\"data\""),
                "🔴 解析失败的 body 里不得出现信封的 data 键；实际=" + response.getBody());
    }

    @Test
    @DisplayName("真 Tomcat：Content-Type 非 JSON ⇒ **丢弃整个 body** ⇒ 等价于空 body（400 + title is not set）")
    void nonJsonContentTypeDropsBodyLikeGo() {
        ResponseEntity<String> response = postCreate(
                "{\"title\":\"t\",\"content\":\"c\"}",
                bearer(tokenFor(Long.parseLong(SEED_AUTHOR))),
                MediaType.TEXT_PLAIN);

        assertEquals(400, response.getStatusCode().value(),
                "🔴 非 JSON 的 Content-Type 不得被当 JSON 读（B: ParseJsonBody 的 withJsonBody 判据）");
        assertPlainTextWithNosniff(response, "解析失败必须是 text/plain + nosniff");
        assertEquals("field \"title\" is not set\n", response.getBody(),
                "🔴 body 被丢弃 ⇒ 与空 body 同形（title 缺席）");
    }

    @Test
    @DisplayName("📌 真 Tomcat：显式 null 的必填文案**折叠**为「is not set」（已登记偏离 D-05，B 侧是 mustn't be nil）")
    void explicitNullRequiredFieldFoldsIntoAbsentWording() {
        // ⚠️ **这是已签字的偏离，不是待修的漂移**：
        //    B 侧区分两条文案 ——「缺席」= field "title" is not set /
        //    「显式 null」= field "title" mustn't be nil
        //    （B 出处：…/advice/GlobalExceptionHandler.java:263-267
        //      + users-common 的 GoRequiredFieldException.java:22 / :28）
        //    C 侧用 Bean Validation 的 @NotNull（A10 禁用手写校验器）
        //    ⇒ record + Jackson 绑定下「缺席」与「显式 null」**都落成 null** ⇒ 无法区分
        //    ⇒ **折叠为同一条文案**。
        //    登记：docs/DRIFT/article/verification-article-service-2026-11-03.md 的 D-05
        //    （标记 INTENTIONALLY_CHANGED）⇒ 本条**钉住已登记的现状**，不是把它锁成"正确"。
        ResponseEntity<String> response = postCreateWithToken("{\"title\":null,\"content\":\"c\"}");

        assertEquals(400, response.getStatusCode().value(),
                "显式 null 的必填字段仍必须是 400（状态码与 B 侧一致）");
        assertPlainTextWithNosniff(response, "必填失败必须是 text/plain + nosniff");
        assertEquals("field \"title\" is not set\n", response.getBody(),
                "📌 已登记偏离 D-05：C 侧把「显式 null」折叠成「is not set」；"
                        + "B 侧这里会是 field \"title\" mustn't be nil ⇒ 两侧 body 文案不同（状态码相同）");
    }

    // ==================================================================
    // ② query 真值表
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：?page=&page=2 ⇒ 取 2（先丢空、再取首）；?page= ⇒ 默认 1")
    void queryMultiValuePageTakesFirstNonEmptyValue() throws Exception {
        JsonNode both = MAPPER.readTree(
                rest.getForEntity("/v1/articles?page=&page=2", String.class).getBody());
        JsonNode onlyEmpty = MAPPER.readTree(
                rest.getForEntity("/v1/articles?page=", String.class).getBody());

        assertEquals(200, both.get("code").asInt(),
                "场景有效性：必须真的走到列表分支；实际=" + both);
        assertEquals(2, both.get("data").get("page").asInt(),
                "🔴 ?page=&page=2 必须取到 2（B: util.go:44-45 先丢空值，unmarshaler.go:835-846 再取首）");
        assertEquals(1, onlyEmpty.get("data").get("page").asInt(),
                "🔴 ?page=（空值）必须等价于「缺席」⇒ 走默认值 1");
        assertNotEquals(both.get("data").get("page").asInt(),
                onlyEmpty.get("data").get("page").asInt(),
                "场景有效性：两个查询的结果必须不同，否则「空值被丢掉」这条根本没被验到");
    }

    @Test
    @DisplayName("🔴 真 Tomcat：desc=1 ⇒ 倒序；desc=0 ⇒ 正序；desc=yes ⇒ 400 + type mismatch")
    void queryDescTruthTable() throws Exception {
        JsonNode desc = MAPPER.readTree(
                rest.getForEntity("/v1/articles?desc=1", String.class).getBody());
        JsonNode asc = MAPPER.readTree(
                rest.getForEntity("/v1/articles?desc=0", String.class).getBody());

        assertEquals(200, desc.get("code").asInt(), "desc=1 必须是 200");
        assertEquals(200, asc.get("code").asInt(), "desc=0 必须是 200");
        assertEquals(ARTICLE_NEW, desc.get("data").get("articles").get(0).get("id").asText(),
                "🔴 desc=1 必须是倒序（较新的排前面）；实际="
                        + desc.get("data").get("articles").get(0).get("id"));
        assertEquals(ARTICLE_OLD, asc.get("data").get("articles").get(0).get("id").asText(),
                "🔴 desc=0 必须是正序（较老的排前面）；实际="
                        + asc.get("data").get("articles").get(0).get("id"));

        ResponseEntity<String> bad = rest.getForEntity("/v1/articles?desc=yes", String.class);
        assertEquals(400, bad.getStatusCode().value(),
                "🔴 desc=yes 必须是 400（B: convertTypeFromString 的 errTypeMismatch）");
        assertPlainTextWithNosniff(bad, "类型不符必须是 text/plain + nosniff");
        assertEquals("type mismatch for field \"desc\"\n", bad.getBody(),
                "🔴 文案必须逐字等于 type mismatch for field \"desc\"");
    }

    @Test
    @DisplayName("🔴 真 Tomcat：?page=abc ⇒ 400 + \"strconv.ParseInt: parsing \\\"abc\\\": invalid syntax (field \\\"page\\\")\"")
    void queryPageNonNumericIs400WithStrconvText() {
        ResponseEntity<String> response = rest.getForEntity("/v1/articles?page=abc", String.class);

        assertEquals(400, response.getStatusCode().value(),
                "🔴 page=abc 必须是 400（B: strconv.ParseInt 失败）");
        assertPlainTextWithNosniff(response, "解析失败必须是 text/plain + nosniff");
        assertEquals("strconv.ParseInt: parsing \"abc\": invalid syntax (field \"page\")\n",
                response.getBody(),
                "🔴 文案必须逐字等于 Go 的 strconv.NumError 形态");
    }

    @Test
    @DisplayName("🔴 真 Tomcat：query 的字符串值**不 trim**（带空白的标签匹配不上 ⇒ total=0）")
    void queryStringValueIsNotTrimmed() throws Exception {
        JsonNode exact = MAPPER.readTree(
                rest.getForEntity("/v1/articles?manual_type_tag=http-tag", String.class).getBody());
        JsonNode padded = MAPPER.readTree(
                rest.getForEntity("/v1/articles?manual_type_tag=%20http-tag", String.class).getBody());

        assertEquals(200, exact.get("code").asInt(), "场景有效性：精确匹配必须 200");
        assertEquals(2, exact.get("data").get("total").asInt(),
                "场景有效性：两篇种子文章的分类都是 http-tag ⇒ total 必须为 2；实际="
                        + exact.get("data").get("total"));
        assertEquals(200, padded.get("code").asInt(),
                "场景有效性：带空白的值**不得**报错（B 侧字符串原样透传）");
        assertEquals(0, padded.get("data").get("total").asInt(),
                "🔴 带空白的标签不得被 trim 后匹配上（B: GoFormValues.text 原样透传，不 trim）");
        assertNotEquals(exact.get("data").get("total").asInt(),
                padded.get("data").get("total").asInt(),
                "场景有效性：两个查询的 total 必须不同，否则「不 trim」这条没被验到");
    }

    // ==================================================================
    // ③ multipart 字段名
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：multipart 字段名固定为 image（换成别的 ⇒ 400 text/plain）")
    void uploadFieldNameIsFixedToImage() {
        ResponseEntity<String> wrong = uploadWithFieldName("file");
        assertEquals(400, wrong.getStatusCode().value(),
                "🔴 字段名不是 image ⇒ 必须 400（B: r.FormFile(\"image\") 取不到 ⇒ httpx.ErrorCtx）");
        assertPlainTextWithNosniff(wrong, "解析失败必须是 text/plain + nosniff");

        ResponseEntity<String> right = uploadWithFieldName("image");
        assertEquals(200, right.getStatusCode().value(),
                "场景有效性：字段名是 image 时必须**不是** 400（说明字段名这条真的被验到了）；"
                        + "实际=" + right.getStatusCode() + " body=" + right.getBody());
        assertTrue(right.getBody() != null && right.getBody().contains("image_url"),
                "场景有效性：必须是 upload 的裸响应；实际=" + right.getBody());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 发一次 {@code POST /v1/article}，<b>带合法 token</b>。
     *
     * <p>⚠️ <b>必须带 token</b>：{@code POST /v1/article} 是受保护路由
     * ⇒ 鉴权层在<b>参数解析之前</b>就抛 401 ⇒ 不带 token 时看到的会是 401，
     * <b>不是</b>本类要验的 400。
     */
    private ResponseEntity<String> postCreate(String body, String tokenHeader) {
        return postCreate(body, tokenHeader, MediaType.APPLICATION_JSON);
    }

    private ResponseEntity<String> postCreate(String body, String tokenHeader, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        if (tokenHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, tokenHeader);
        }
        return rest.exchange("/v1/article", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    /** 发一次 {@code POST /v1/article}（带合法 token）。 */
    private ResponseEntity<String> postCreateWithToken(String body) {
        return postCreate(body, bearer(tokenFor(Long.parseLong(SEED_AUTHOR))));
    }

    /** 发一次 multipart 上传，表单字段名可指定。 */
    private ResponseEntity<String> uploadWithFieldName(String fieldName) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        ByteArrayResource file = new ByteArrayResource("fake-image-bytes".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "seed.png";
            }
        };
        form.add(fieldName, file);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange("/v1/upload", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    /**
     * 断言「400 + text/plain;charset=utf-8 + nosniff」这组字节面。
     *
     * <p>⚠️ 真 Tomcat 会把 {@code text/plain; charset=utf-8} <b>归一化</b>成
     * {@code text/plain;charset=utf-8}（分号后无空格）⇒ 断言前先去掉空格。
     * ⚠️ 归一化<b>只去空格</b>，媒体类型本身写错仍然会红。
     */
    private void assertPlainTextWithNosniff(ResponseEntity<String> response, String message) {
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        assertEquals("text/plain;charset=utf-8", normalizeContentType(contentType), message);
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"),
                message + "（B: http.Error 写 X-Content-Type-Options: nosniff）");
    }
}
