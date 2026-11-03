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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>article 域 HTTP 层的「信封 / 裸响应 / 键序」契约</b> —— 真 Tomcat ＋ 真 Spring 上下文
 * ＋ 真 HTTP 请求。
 *
 * <h2>① 真触发理由（为什么不能用 MockMvc / 纯 Mockito）</h2>
 * <p>本类验的四件事<b>全都</b>只有在<b>真实 MVC 管线</b>上才成立：
 * <ol>
 *   <li><b>信封只包一层</b> ⇒ 取决于 {@code ResponseBodyAdvice} 与
 *       {@code HttpEntityMethodProcessor} 的<b>交互顺序</b>
 *       （{@code HttpEntityMethodProcessor} 会先把 {@code ResponseEntity} <b>拆开</b>再交给 advice
 *       ⇒ 「放行 3 必须看 {@code returnType} 不能看 body」这条只有真管线能验）；</li>
 *   <li><b>{@code POST /v1/upload} 不走信封</b> ⇒ 同上（放行 3）；</li>
 *   <li><b>业务错误是 HTTP 200 + 信封</b> ⇒ 取决于
 *       {@code @ExceptionHandler} 产出 {@code ResponseEntity<ApiResponse>}
 *       之后<b>会不会</b>再被 advice 包一层（放行 2）；</li>
 *   <li><b>JSON 键序</b> ⇒ 取决于容器里<b>那个</b> {@code ObjectMapper}
 *       （共享层与域内各施加了一次 coercion 规则）—— 单测里 new 一个 mapper 验的是假的东西。</li>
 * </ol>
 *
 * <h2>② B 出处（逐条 · B 侧是唯一 oracle）</h2>
 * <table border="1">
 *   <caption>断言 ← B 侧出处</caption>
 *   <tr><th>断言</th><th>B 侧出处</th></tr>
 *   <tr>
 *     <td>成功路径 = {@code {code,msg,data}} 三键（且键序 code→msg→data）</td>
 *     <td>{@code service/article/article-api/…/advice/GlobalResponseAdvice.java:61-62,113}
 *         （类声明 + {@code new Response(SUCCESS, msg, body)}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code POST /v1/upload} <b>裸</b> {@code {"image_url":…}}（唯一不走信封的端点）</td>
 *     <td>{@code …/advice/GlobalResponseAdvice.java:48-51}（放行 3 的注释）+ B 侧
 *         {@code …/service/ArticleUploadService.java} 类注释「本端点是本模块唯一<b>不走信封</b>的路径」</td>
 *   </tr>
 *   <tr>
 *     <td>业务错误 = HTTP 200 + 信封（{@code data:null}）</td>
 *     <td>{@code …/advice/GlobalExceptionHandler.java:139-143}（{@code handleBusiness}）</td>
 *   </tr>
 *   <tr>
 *     <td>「请先登录」= 1005</td>
 *     <td>B 侧 {@code …/service/ArticleApiSupport.java:15-22}（{@code userId} 失败 ⇒
 *         {@code ErrorUnauthorized}）+ {@code …/advice/GlobalExceptionHandler.java:139-143}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code Article} 的 17 个键按 Go struct 声明序</td>
 *     <td>B 侧 {@code …/dto/Article.java:88-92}（{@code @JsonPropertyOrder}）+
 *         B 侧测试 {@code …/dto/ArticleApiJsonFieldOrderTest.java:26-33}（期望顺序常量）</td>
 *   </tr>
 *   <tr>
 *     <td>列表响应键序 = {@code articles,total,page,page_size}</td>
 *     <td>B 侧测试 {@code …/dto/ArticleApiJsonFieldOrderTest.java:35}
 *         （{@code GO_LIST_RESP_ORDER}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code secondary_tags} / {@code ext_info} 为空 ⇒ <b>键保留、值为 null</b></td>
 *     <td>B 侧 {@code …/service/ArticleApiSupport.java:218-226}
 *         （{@code cloneStringList} / {@code cloneStringMap}：空 ⇒ nil ⇒ JSON {@code null}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code author_name} 空 ⇒ <b>键消失</b>（{@code NON_EMPTY}）</td>
 *     <td>B 侧 {@code …/dto/Article.java} 的 {@code author_name,omitempty}（Go tag）</td>
 *   </tr>
 * </table>
 *
 * <h2>③ 可证伪性（被测对象写错了会不会红）</h2>
 * <ul>
 *   <li>把 {@code ArticleApiResponseAdvice} 的「放行 3」删掉 ⇒
 *       {@code uploadSuccessIsBareImageUrlWithoutEnvelope} <b>立刻红</b>
 *       （body 会变成 {@code {"code":200,"msg":"OK","data":{"image_url":…}}}）；</li>
 *   <li>把「放行 1（body==null）」删掉 ⇒ 401 会被包成 {@code {"code":200,…}}
 *       ⇒ 本类的业务错误用例与 {@code ArticleHttpAuthRealTomcatTest} 的 401 用例<b>同时红</b>；</li>
 *   <li>把「放行 2（已是 envelope）」删掉 ⇒ 业务错误会变成
 *       {@code {"code":200,"msg":"OK","data":{"code":1005,…}}} ⇒ <b>立刻红</b>；</li>
 *   <li>去掉 {@code Article} 上的 {@code @JsonPropertyOrder} ⇒
 *       {@code articleJsonKeyOrderMatchesGoDeclarationOrder} <b>立刻红</b>（Jackson 默认字母序）；</li>
 *   <li>把 {@code cloneStringList} / {@code cloneStringMap} 改成「空 ⇒ 空集合」
 *       ⇒ {@code nullSecondaryTagsAndExtInfoKeepTheirKeys} <b>立刻红</b>
 *       （会变成 {@code "secondary_tags":[]} 而不是 {@code null}）；</li>
 *   <li>把 {@code @JsonInclude(NON_EMPTY)} 从 {@code author_name} 上摘掉
 *       ⇒ 该条<b>不会</b>在本类变红（原因见「未覆盖缺口」一节）—— 这是本类的<b>已知盲区</b>。</li>
 * </ul>
 *
 * <h2>④ ⚠️ 与 B 侧测试的<b>形态</b>差异（不是行为差异）</h2>
 * <p>B 侧 {@code ArticleApiMvcPipelineTest} 用的是 <b>MockMvc</b>（{@code webEnvironment = MOCK}）
 * ⇒ 它<b>验不到</b> {@code Content-Type} / {@code nosniff} / 状态行这些<b>容器层</b>事实。
 * <p>⇒ 本仓按 AGENTS.md §八「HTTP 断言走真 Tomcat」改成
 * {@code webEnvironment = RANDOM_PORT} ＋ {@code TestRestTemplate}
 * ⇒ ⇒ <b>被验的 L1 契约逐条相同</b>（同一批 B 侧出处），<b>只是观测手段更严</b>。
 */
class ArticleHttpEnvelopeRealTomcatTest extends HttpRealTomcatTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Go {@code service/article/api/internal/types/types.go:6-24} 的 {@code Article} 声明序。
     *
     * <p>⚠️ 与 B 侧测试 {@code …/dto/ArticleApiJsonFieldOrderTest.java:26-33} 的常量<b>逐字相同</b>
     * ⇒ 两边若有分歧 ⇒ 一定有一边漂了。
     */
    private static final List<String> GO_ARTICLE_ORDER = List.of(
            "id", "title", "brief", "content", "cover_image_url", "manual_type_tag",
            "secondary_tags", "author_id", "author_name", "create_time", "update_time",
            "status", "view_count", "like_count", "comment_count", "share_count", "ext_info");

    /** Go {@code types.go:67-72} 的 {@code ListArticlesResp} 声明序。 */
    private static final List<String> GO_LIST_RESP_ORDER =
            List.of("articles", "total", "page", "page_size");

    /** 信封的三个键（B 侧 {@code com.rag.common.response.Response} 的 struct tag 顺序）。 */
    private static final List<String> ENVELOPE_ORDER = List.of("code", "msg", "data");

    @BeforeEach
    void seed() {
        truncateArticleTables();
        seedTwoPublishedArticles();
    }

    // ==================================================================
    // ① 信封形状
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：成功路径是 {code,msg,data} 三键（键序 code→msg→data），且只包一层")
    void listArticlesSuccessIsThreeKeyEnvelope() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/v1/articles", String.class);

        assertEquals(200, response.getStatusCode().value(),
                "成功路径的 HTTP 状态码必须是 200（B 侧 httpx.OkJson）");
        JsonNode root = MAPPER.readTree(response.getBody());
        assertTrue(root.isObject(), "响应体必须是 JSON 对象：" + response.getBody());
        assertEquals(ENVELOPE_ORDER, keyOrder(root),
                "🔴 信封必须是且只是 {code,msg,data} 三键，键序 code→msg→data"
                        + "（B: com.rag.common.response.Response 的 struct tag 顺序）；"
                        + "实际=" + keyOrder(root));
        assertEquals(200, root.get("code").asInt(), "成功码必须是 200");
        assertEquals("OK", root.get("msg").asText(), "成功文案必须是 OK");

        JsonNode data = root.get("data");
        assertNotNull(data, "data 键必须存在（B 侧三个字段都没有 omitempty ⇒ null 时也要出现）");
        assertEquals(GO_LIST_RESP_ORDER, keyOrder(data),
                "🔴 列表 data 的键序必须是 articles,total,page,page_size"
                        + "（B: types.go:67-72）；实际=" + keyOrder(data));

        // ── 场景有效性：证明「真的走到了业务层」，而不是撞上了某个错误分支 ──
        assertTrue(data.get("total").asInt() >= 2,
                "场景有效性：种子了两篇已发布文章 ⇒ total 必须 ≥2；实际=" + data.get("total"));
        assertTrue(data.get("articles").isArray() && data.get("articles").size() >= 2,
                "场景有效性：articles 必须列出两篇种子文章；实际=" + data.get("articles"));
        List<String> ids = new ArrayList<>();
        data.get("articles").forEach(node -> ids.add(node.get("id").asText()));
        assertTrue(ids.contains(ARTICLE_OLD) && ids.contains(ARTICLE_NEW),
                "场景有效性：响应里必须能看到两个种子 id；实际=" + ids);
    }

    @Test
    @DisplayName("🔴 真 Tomcat：POST /v1/upload 是全域唯一不走信封的端点（裸 {\"image_url\":…}）")
    void uploadSuccessIsBareImageUrlWithoutEnvelope() throws Exception {
        ResponseEntity<String> response = uploadWithFieldName("image");

        assertEquals(200, response.getStatusCode().value(),
                "上传成功必须是 HTTP 200（B: httpx.OkJsonCtx 裸写结构体）");
        JsonNode root = MAPPER.readTree(response.getBody());
        assertTrue(root.isObject(), "响应体必须是 JSON 对象：" + response.getBody());

        assertEquals(List.of("image_url"), keyOrder(root),
                "🔴 upload 的 body 必须**只有** image_url 一个键（不许出现 code/msg/data）；"
                        + "实际=" + keyOrder(root));
        assertEquals("http://minio.invalid/articles/images/seed.png",
                root.get("image_url").asText(),
                "场景有效性：image_url 必须是对象存储返回的那个 URL"
                        + "（证明请求真的走完了 controller→service→对象存储边界）");
        assertFalse(root.has("code"), "upload 的响应里不得出现 code 键（那说明被包了信封）");
        assertFalse(root.has("data"), "upload 的响应里不得出现 data 键（那说明被包了信封）");
    }

    @Test
    @DisplayName("🔴 真 Tomcat：业务错误是 HTTP 200 + 信封（code=1005，data=null），**不是** 401")
    void businessErrorIsHttp200WithEnvelope() throws Exception {
        // ⚠️ 刻意签一个「签名合法但**没有 userId claim**」的 token：
        //    Go 侧 handler.Authorize 只验签名与 exp，**不校验 claim 取值**
        //    ⇒ 「验签过了但取不到 uid」是**业务失败**（1005），不是鉴权失败（401）。
        ResponseEntity<String> response = rest.exchange(
                "/v1/me/article/" + ARTICLE_OLD,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tokenWithoutUserIdClaim())),
                String.class);

        assertEquals(200, response.getStatusCode().value(),
                "🔴 业务错误必须是 HTTP 200（B: GlobalExceptionHandler.handleBusiness）—— "
                        + "若这里是 401，说明把 claim 取值判定挪到了鉴权层");
        JsonNode root = MAPPER.readTree(response.getBody());
        assertEquals(ENVELOPE_ORDER, keyOrder(root),
                "业务错误也必须是 {code,msg,data} 三键；实际=" + keyOrder(root));
        assertEquals(1005, root.get("code").asInt(),
                "🔴 「请先登录」必须是 1005（B: ErrorCode.ERROR_UNAUTHORIZED）");
        assertEquals("请先登录", root.get("msg").asText(), "1005 的文案必须是「请先登录」");
        assertTrue(root.get("data").isNull(),
                "🔴 业务错误的 data 必须是 null（B: handleBusiness 产出 Response{code,msg,nil}）");
    }

    // ==================================================================
    // ② 字段顺序契约
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：Article 的 17 个键按 Go struct 声明序输出")
    void articleJsonKeyOrderMatchesGoDeclarationOrder() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/v1/articles", String.class);
        JsonNode root = MAPPER.readTree(response.getBody());
        JsonNode articles = root.get("data").get("articles");

        assertTrue(articles.isArray() && !articles.isEmpty(),
                "场景有效性：列表必须有行才能验键序；实际=" + articles);
        JsonNode article = articles.get(0);
        assertEquals(GO_ARTICLE_ORDER, keyOrder(article),
                "🔴 Article 的 JSON 键序必须与 Go struct 声明序一致"
                        + "（Jackson 默认字母序 ⇒ 只能靠 @JsonPropertyOrder 固定；"
                        + "B: types.go:6-24 / dto/Article.java:88-92）；实际=" + keyOrder(article));
    }

    @Test
    @DisplayName("🔴 真 Tomcat：secondary_tags / ext_info 为空 ⇒ 键保留、值为 null（作者读路径）")
    void nullSecondaryTagsAndExtInfoKeepTheirKeys() throws Exception {
        ResponseEntity<String> response = rest.exchange(
                "/v1/me/article/" + ARTICLE_OLD,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tokenFor(Long.parseLong(SEED_AUTHOR)))),
                String.class);

        assertEquals(200, response.getStatusCode().value(),
                "作者读自己的文章必须是 HTTP 200；实际 body=" + response.getBody());
        JsonNode root = MAPPER.readTree(response.getBody());
        assertEquals(200, root.get("code").asInt(),
                "场景有效性：必须真的读到了文章（code=200），否则下面验的是错误分支；实际="
                        + response.getBody());

        // ⚠️ 读单篇的 data 里**再嵌一层** {"article":{…}}
        //    （B: GetArticleResp 只有一个字段 article —— B 侧 …/dto/GetArticleResp.java:10）
        JsonNode article = root.get("data").get("article");
        assertNotNull(article,
                "🔴 data.article 必须存在（B: GetArticleResp{article}）；实际=" + response.getBody());
        assertEquals(ARTICLE_OLD, article.get("id").asText(),
                "场景有效性：必须是种子文章 " + ARTICLE_OLD + "；实际=" + article.get("id"));

        assertTrue(article.has("secondary_tags"),
                "🔴 secondary_tags 为空时**键必须保留**（Go 侧该字段没有 omitempty）");
        assertTrue(article.get("secondary_tags").isNull(),
                "🔴 secondary_tags 为空时值必须是 null（不是 []、也不是键消失）；实际="
                        + article.get("secondary_tags"));
        assertTrue(article.has("ext_info"),
                "🔴 ext_info 为空时**键必须保留**（Go 侧该字段没有 omitempty）");
        assertTrue(article.get("ext_info").isNull(),
                "🔴 ext_info 为空时值必须是 null（不是 {}、也不是键消失）；实际="
                        + article.get("ext_info"));

        // ── 场景有效性：author_name 在 HTTP 上恒非空（解析器的回落文案都不是空串）──
        assertTrue(article.has("author_name"),
                "author_name 键必须存在（B: AuthorName string `json:\"author_name,omitempty\"`）");
        assertEquals("用户 " + SEED_AUTHOR, article.get("author_name").asText(),
                "场景有效性：author_name 应是回落文案「用户 <id>」"
                        + "（本域无 user-rpc 跨域出口 ⇒ 恒走回落分支）");
    }

    // ==================================================================
    // ③ 「upload 是唯一不走信封的端点」—— 逐端点证明
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Tomcat：7 个端点里**只有** POST /v1/upload 不走信封（其余 6 个全是 {code,msg,data}）")
    void everyEndpointExceptUploadIsEnveloped() throws Exception {
        String token = tokenFor(Long.parseLong(SEED_AUTHOR));

        // ── ① POST /v1/article（创建）──
        ResponseEntity<String> created = rest.exchange("/v1/article", HttpMethod.POST,
                new HttpEntity<>("{\"title\":\"t\",\"content\":\"c\"}", authHeaders(token)),
                String.class);
        assertEquals(200, created.getStatusCode().value(),
                "🔴 创建路径必须是 HTTP 200（B: 业务错误也是 200 + 信封）；实际="
                        + created.getStatusCode() + " body=" + created.getBody());
        assertEquals(ENVELOPE_ORDER, keyOrder(MAPPER.readTree(created.getBody())),
                "🔴 POST /v1/article 必须走信封；实际键序="
                        + keyOrder(MAPPER.readTree(created.getBody())));

        // ── ② PUT /v1/article/{id}（更新）──
        ResponseEntity<String> updated = rest.exchange("/v1/article/" + ARTICLE_OLD, HttpMethod.PUT,
                new HttpEntity<>("{\"title\":\"t2\"}", authHeaders(token)), String.class);
        assertEquals(200, updated.getStatusCode().value(),
                "🔴 更新路径必须是 HTTP 200；实际=" + updated.getStatusCode()
                        + " body=" + updated.getBody());
        assertEquals(ENVELOPE_ORDER, keyOrder(MAPPER.readTree(updated.getBody())),
                "🔴 PUT /v1/article/{id} 必须走信封；实际键序="
                        + keyOrder(MAPPER.readTree(updated.getBody())));

        // ── ③ DELETE /v1/article/{id}（删除）──
        ResponseEntity<String> deleted = rest.exchange("/v1/article/" + ARTICLE_NEW, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)), String.class);
        assertEquals(200, deleted.getStatusCode().value(),
                "🔴 删除路径必须是 HTTP 200；实际=" + deleted.getStatusCode()
                        + " body=" + deleted.getBody());
        assertEquals(ENVELOPE_ORDER, keyOrder(MAPPER.readTree(deleted.getBody())),
                "🔴 DELETE /v1/article/{id} 必须走信封；实际键序="
                        + keyOrder(MAPPER.readTree(deleted.getBody())));

        // ── ④ GET /v1/article/{id}（公开读详情）──
        ResponseEntity<String> detail = rest.getForEntity("/v1/article/" + ARTICLE_OLD, String.class);
        assertEquals(200, detail.getStatusCode().value(),
                "🔴 公开读详情必须是 HTTP 200；实际=" + detail.getStatusCode());
        assertEquals(ENVELOPE_ORDER, keyOrder(MAPPER.readTree(detail.getBody())),
                "🔴 GET /v1/article/{id} 必须走信封；实际键序="
                        + keyOrder(MAPPER.readTree(detail.getBody())));

        // ── ⑤ 场景有效性：④ 真的读到了文章（而不是撞上错误分支）──
        JsonNode detailData = MAPPER.readTree(detail.getBody()).get("data");
        assertNotNull(detailData, "场景有效性：公开读详情必须有 data；实际=" + detail.getBody());
        assertEquals(ARTICLE_OLD, detailData.get("article").get("id").asText(),
                "场景有效性：必须读到种子文章；实际=" + detail.getBody());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 发一次 multipart 上传，<b>表单字段名可指定</b>（用于钉死字段名契约）。 */
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

    /** 取 JSON 对象的键序（Jackson 保留插入序 ⇒ 读出来的就是序列化顺序）。 */
    private static List<String> keyOrder(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
