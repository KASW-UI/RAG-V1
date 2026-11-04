package com.rag.hot.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.hot.support.ArticleRpcInProcessServer;
import com.rag.hot.support.HotRealStackTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>hot 域 HTTP 面的「真 Tomcat」测试</b> —— 真 Spring 上下文 + 真 Tomcat
 * ＋ 真 Redis ＋ <b>真进程内 gRPC</b>（article-rpc）。
 *
 * <p><b>⚠️ 本仓不用 MockMvc</b>（AGENTS.md §八 检查清单「HTTP 断言走真 Tomcat」）：
 * {@code Content-Type} / {@code nosniff} / 状态行都是<b>字节面</b>事实 ⇒ 必须真发一条 HTTP 请求。
 *
 * <p><b>B 出处</b>：
 * <ul>
 *   <li>路由与端点：B 侧 {@code service/hot/hot-api/.../api/controller/HotArticleController.java:53-97}；</li>
 *   <li>成功信封：B 侧 {@code .../api/advice/GlobalResponseAdvice.java:51-72}；</li>
 *   <li>失败信封与裸状态码：B 侧 {@code .../api/advice/GlobalExceptionHandler.java}；</li>
 *   <li>错误码表：B 侧 {@code service/hot/hot-common/.../errmsg/HotErrorCode.java} + {@code HotErrorMessages.java}。</li>
 * </ul>
 *
 * <p><b>本类锁定的可观察事实</b>（每条都带 B 出处）：
 * <ol>
 *   <li>成功路径恒为 <b>HTTP 200</b> + {@code {"code":200,"msg":"OK","data":{…}}}（三键顺序固定）；</li>
 *   <li>失败路径恒为 <b>HTTP 200</b> + {@code {"code":5001,"msg":"internal server error","data":null}}；</li>
 *   <li>404 / 405 是<b>裸状态码</b>（<b>没有</b>信封）；</li>
 *   <li>{@code items} 永不为 {@code null}（空页也是 {@code []}）；</li>
 *   <li>{@code secondary_tags} 在无标签时是 {@code null}（<b>不是</b> {@code []}）；</li>
 *   <li>{@code hot_score} 是<b>无符号</b>十进制（{@code uint32}）；</li>
 *   <li>rank 是<b>过滤后的稠密序号</b>且<b>跨页连续</b>；</li>
 *   <li>4 条 skip 分支各自打<b>自己的</b>指标。</li>
 * </ol>
 */
class HotHttpRealTomcatTest extends HotRealStackTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** B 侧合并后的结果 key（{@code GetHotArticles} 的唯一读取源）。 */
    private static final String HOT_ARTICLES_KEY = "hot:articles";

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void seed() {
        ArticleRpcInProcessServer.resetToSeed();
        redis.delete(HOT_ARTICLES_KEY);
    }

    // ==================================================================
    // ① 成功路径：信封形状 + 键序 + 补全 + rank + 4 条 skip
    // ==================================================================

    @Test
    @DisplayName("GET /hot/v1/articles：200 + {code:200,msg:OK,data:…}，补全后 rank 稠密且跨页连续")
    void successEnvelopeAndHydration() throws Exception {
        // 合并结果的 6 个成员（B 侧的 mergePartitions 产出 hot:articles）：
        //   900200006 score 4294967295  → PUBLISHED  ⇒ 出现，hot_score 是无符号十进制
        //   900200001 score 100          → PUBLISHED  ⇒ 出现
        //   900200003 score 80           → PUBLISHED  ⇒ 出现
        //   900200002 score 90           → DRAFT      ⇒ skip（article_not_published）
        //   900200004 score 70           → 响应无 article ⇒ skip（article_missing）
        //   900200005 score 60           → 服务端 NOT_FOUND ⇒ skip（article_rpc_error）
        //   ""         score 50          → 空 article_id ⇒ skip（empty_article_id）
        redis.opsForZSet().add(HOT_ARTICLES_KEY, "900200006", 4294967295.0d);
        redis.opsForZSet().add(HOT_ARTICLES_KEY, "900200001", 100.0d);
        redis.opsForZSet().add(HOT_ARTICLES_KEY, "900200002", 90.0d);
        redis.opsForZSet().add(HOT_ARTICLES_KEY, "900200003", 80.0d);
        redis.opsForZSet().add(HOT_ARTICLES_KEY, "900200004", 70.0d);
        redis.opsForZSet().add(HOT_ARTICLES_KEY, "900200005", 60.0d);
        redis.opsForZSet().add(HOT_ARTICLES_KEY, "", 50.0d);

        long skippedBefore = skipCount("article_not_published");
        long rpcErrorBefore = skipCount("article_rpc_error");
        long missingBefore = skipCount("article_missing");
        long emptyBefore = skipCount("empty_article_id");

        ResponseEntity<String> response = rest.getForEntity("/hot/v1/articles", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/json;charset=utf-8", normalizeContentType(response.getHeaders().getFirst("Content-Type")));

        String body = response.getBody();
        assertNotNull(body);
        // ① 信封三键与顺序（字节面）
        assertTrue(body.startsWith("{\"code\":200,\"msg\":\"OK\",\"data\":{"),
                "信封形状/键序不对：" + body);

        JsonNode root = MAPPER.readTree(body);
        assertEquals(200, root.get("code").asInt());
        assertEquals("OK", root.get("msg").asText());

        JsonNode data = root.get("data");
        assertEquals(3, data.get("total").asInt(), "total = 过滤后的长度");
        assertEquals(1, data.get("page").asInt());
        assertEquals(20, data.get("page_size").asInt());
        assertEquals("rolling", data.get("scope").asText());

        JsonNode items = data.get("items");
        assertEquals(3, items.size());

        // ② rank 稠密、跨页连续；hot_score 无符号；secondary_tags 是 null
        assertEquals(1, items.get(0).get("rank").asInt());
        assertEquals(2, items.get(1).get("rank").asInt());
        assertEquals(3, items.get(2).get("rank").asInt());

        assertEquals("900200006", items.get(0).get("article_id").asText());
        assertEquals(4294967295L, items.get(0).get("hot_score").asLong(),
                "hot_score 必须是 uint32 的无符号十进制");
        assertEquals("900200001", items.get(1).get("article_id").asText());
        assertEquals(100L, items.get(1).get("hot_score").asLong());
        assertEquals("900200003", items.get(2).get("article_id").asText());
        assertEquals(80L, items.get(2).get("hot_score").asLong());

        // article_id 字段取的是 article 公开投影的 id（不是 hot item 的 id）
        assertEquals("900200001", items.get(1).get("article_id").asText());
        assertEquals("title-900200001", items.get(1).get("title").asText());

        // ③ 13 个字段的键序（字节面 L1）
        assertTrue(items.get(1).toString().startsWith(
                        "{\"article_id\":\"900200001\",\"rank\":2,\"hot_score\":100,\"title\":"),
                "卡片的键序不对：" + items.get(1));

        // ④ secondary_tags 无元素 ⇒ null（不是 []）
        assertTrue(items.get(1).has("secondary_tags"), "secondary_tags 键必须存在");
        assertTrue(items.get(1).get("secondary_tags").isNull(),
                "secondary_tags 无元素时必须是 null（不是 []）：" + items.get(1));

        // ⑤ 4 条 skip 分支各自打自己的指标
        assertEquals(skippedBefore + 1, skipCount("article_not_published"));
        assertEquals(rpcErrorBefore + 1, skipCount("article_rpc_error"));
        assertEquals(missingBefore + 1, skipCount("article_missing"));
        assertEquals(emptyBefore + 1, skipCount("empty_article_id"));
    }

    @Test
    @DisplayName("GET /hot/v1/articles：Redis 里没有 hot:articles ⇒ 空 items（不是 null），total=0")
    void emptyRankingYieldsEmptyItems() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/hot/v1/articles", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode root = MAPPER.readTree(response.getBody());
        assertEquals(200, root.get("code").asInt());
        assertEquals(0, root.get("data").get("total").asInt());
        assertTrue(root.get("data").get("items").isArray(), "items 必须是数组（空页也是 []）");
        assertEquals(0, root.get("data").get("items").size());
    }

    @Test
    @DisplayName("GET /hot/v1/articles?page=2&page_size=1：rank 跨页连续（第 2 页第一条 rank=2）")
    void rankIsContinuousAcrossPages() throws Exception {
        redis.opsForZSet().add(HOT_ARTICLES_KEY, "900200006", 4294967295.0d);
        redis.opsForZSet().add(HOT_ARTICLES_KEY, "900200001", 100.0d);
        redis.opsForZSet().add(HOT_ARTICLES_KEY, "900200003", 80.0d);

        ResponseEntity<String> response =
                rest.getForEntity("/hot/v1/articles?page=2&page_size=1", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode data = MAPPER.readTree(response.getBody()).get("data");
        assertEquals(3, data.get("total").asInt());
        assertEquals(2, data.get("page").asInt());
        assertEquals(1, data.get("page_size").asInt());
        assertEquals(1, data.get("items").size());
        // ⚠️ rank 在分页**之前**按过滤后的下标重算 ⇒ 第 2 页第一条的 rank 是 2 而不是 1
        assertEquals(2, data.get("items").get(0).get("rank").asInt());
        assertEquals("900200001", data.get("items").get(0).get("article_id").asText());
    }

    // ==================================================================
    // ② 失败路径：恒 HTTP 200 + 信封
    // ==================================================================

    @Test
    @DisplayName("GET /hot/v1/articles?page=abc ⇒ 200 + {code:5001,msg:internal server error,data:null}")
    void parseFailureYieldsBusinessEnvelope() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/hot/v1/articles?page=abc", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "业务失败也是 HTTP 200（B 侧同款）");
        String body = response.getBody();
        assertNotNull(body);
        assertEquals("{\"code\":5001,\"msg\":\"internal server error\",\"data\":null}", body);
    }

    @Test
    @DisplayName("GET /hot/v1/articles?page_size=2147483648 ⇒ 同样 5001（int32 越界）")
    void int32OverflowIsBusinessFailure() throws Exception {
        ResponseEntity<String> response =
                rest.getForEntity("/hot/v1/articles?page_size=2147483648", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"code\":5001,\"msg\":\"internal server error\",\"data\":null}", response.getBody());
    }

    @Test
    @DisplayName("GET /hot/v1/articles?page=5&page=3 ⇒ 取首值（page=5），不是 Spring 的 \"5,3\"")
    void duplicateParamTakesFirstValue() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/hot/v1/articles?page=5&page=3", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode data = MAPPER.readTree(response.getBody()).get("data");
        assertEquals(5, data.get("page").asInt());
    }

    // ==================================================================
    // ②-bis form 值个数上限（共享层闸门）
    // ==================================================================

    /**
     * 🔴 <b>form 值个数上限 2048 的端到端守门</b>。
     *
     * <h2>为什么必须有这条</h2>
     * <p>本域的 2048 闸门<b>不在域内</b>（B 侧域内自持 ⇒ C 侧上收到共享层
     * {@code common/web/ValueLimitInterceptor}）⇒ ⇒ 域内<b>没有任何测试</b>守着它
     * ⇒ ⇒ 共享层若改了判据口径（阈值 / 计数方式 / 拒绝形状），
     * hot 域<b>一个测试都不会红</b>。</p>
     *
     * <h2>本用例钉住的可观察事实</h2>
     * <ol>
     *   <li><b>2048 个非空值 ⇒ 放行</b>（正常 200 + 业务信封）；</li>
     *   <li><b>2049 个非空值 ⇒ 拒绝</b>，且形状是 <b>HTTP 200</b> +
     *       {@code {"code":5001,"msg":"internal server error","data":null}}
     *       （<b>不是</b> 400、<b>不是</b> 裸响应）⇒ 与 B 侧逐条一致
     *       （B 侧域内 {@code requireFormValueCountWithinLimit} 抛
     *       {@code BusinessException(5001, "internal server error")}）。</li>
     * </ol>
     *
     * <h2>⚠️ 为什么用裸 socket 而不是 TestRestTemplate</h2>
     * <p>请求行约 20 KB ⇒ 必须确认它<b>没被 Tomcat 的请求头上限先拒</b>
     * （本域已把 {@code server.max-http-request-header-size} 抬到 1028KB，
     * 对齐 B 侧 Go 的 {@code DefaultMaxHeaderBytes(1&lt;&lt;20)}）
     * ⇒ ⇒ 若那条配置被回退，本用例会变成「拿到 Tomcat 的拒绝」⇒ <b>正好起到守门作用</b>。</p>
     *
     * <h2>B 出处</h2>
     * <p>B 侧 {@code service/hot/hot-api/.../api/form/GoFormValues.java:224-245}
     * （{@code MAX_FORM_PARAM_COUNT = 2048} + {@code requireFormValueCountWithinLimit}），
     * 调用点 {@code .../api/controller/HotArticleController.java:88}。</p>
     */
    @Test
    @DisplayName("GET 带 2049 个非空 query 值 ⇒ 200 + {code:5001,…}（form 值上限闸门）")
    void tooManyFormValuesYieldsBusinessEnvelope() throws Exception {
        String raw = rawHttp("GET /hot/v1/articles?" + queryWithNonEmptyValues(2049)
                + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");

        assertTrue(statusLineOf(raw).startsWith("HTTP/1.1 200"),
                "超限也必须是 HTTP 200（B 侧 go-zero 的 SetErrorHandler 同款）；实际状态行："
                        + statusLineOf(raw));
        JsonNode body = MAPPER.readTree(decodedBodyOf(raw));
        assertEquals(5001, body.get("code").asInt(), "超限必须是 5001 业务码");
        assertEquals("internal server error", body.get("msg").asText());
        assertTrue(body.get("data").isNull(), "失败信封的 data 必须是 null");
    }

    @Test
    @DisplayName("GET 带 2048 个非空 query 值 ⇒ **放行**（阈值是「超过 2048」才拒）")
    void exactly2048FormValuesIsAccepted() throws Exception {
        String raw = rawHttp("GET /hot/v1/articles?" + queryWithNonEmptyValues(2048)
                + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");

        assertTrue(statusLineOf(raw).startsWith("HTTP/1.1 200"),
                "2048 个值必须放行；实际状态行：" + statusLineOf(raw));
        JsonNode body = MAPPER.readTree(decodedBodyOf(raw));
        assertEquals(200, body.get("code").asInt(),
                "放行时必须是正常业务信封（code=200），实际：" + decodedBodyOf(raw));
    }

    /**
     * 造 n 个<b>非空</b>的 query 值（{@code a0=v0&a1=v1&…}）。
     *
     * <p>⚠️ 必须**非空**：共享层的判据数的是<b>非空值</b>（B 侧 {@code net/url} 的
     * 「空值丢弃」规则）⇒ 空值不计数。</p>
     *
     * @param n 值个数
     * @return query string（不带前导 '?'）
     */
    private static String queryWithNonEmptyValues(int n) {
        StringBuilder sb = new StringBuilder(n * 10);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append('a').append(i).append("=v").append(i);
        }
        return sb.toString();
    }

    // ==================================================================
    // ③ 裸状态码（404 / 405）—— 无信封
    // ==================================================================

    @Test
    @DisplayName("GET /hot/v1/nope ⇒ 404 且**没有**信封")
    void unknownPathIsBare404() throws Exception {
        String raw = rawHttp("GET /hot/v1/nope HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Connection: close\r\n\r\n");

        // ⚠️ Tomcat 10.1 **不发 reason phrase** ⇒ 状态行是 "HTTP/1.1 404 "（码后有空格、无短语）。
        //    B 侧（Go net/http）会发 "HTTP/1.1 404 Not Found" ⇒ 两侧**不逐字节相同**
        //    ⇒ 属**框架限制**（共享层 JsonContentTypeAdvice 的注释同款，已登记在 global 台账）
        //    ⇒ 按 RFC 9110 客户端**必须忽略** reason phrase ⇒ 语义等价。
        assertEquals("HTTP/1.1 404 ", statusLineOf(raw));
        String body = rawBodyOf(raw);
        assertFalse(body.contains("\"code\""), "404 必须没有信封：" + body);
    }

    @Test
    @DisplayName("POST /hot/v1/articles ⇒ 405 且**没有**信封")
    void wrongMethodIsBare405() throws Exception {
        String raw = rawHttp("POST /hot/v1/articles HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n");

        // ⚠️ 同上：Tomcat 10.1 不发 reason phrase。
        assertEquals("HTTP/1.1 405 ", statusLineOf(raw));
        String body = rawBodyOf(raw);
        assertFalse(body.contains("\"code\""), "405 必须没有信封：" + body);
    }

    // ==================================================================
    // ④ 免鉴权
    // ==================================================================

    @Test
    @DisplayName("GET /hot/v1/articles：不带任何 token 也能拿到 200（本域免鉴权）")
    void endpointIsPublic() {
        ResponseEntity<String> response = rest.getForEntity("/hot/v1/articles", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getHeaders().getFirst("WWW-Authenticate") == null,
                "本域不得出现鉴权挑战头");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 读某个 skip reason 的累计计数。
     *
     * @param reason 原因标签
     * @return 计数（meter 不存在时返回 0）
     */
    private long skipCount(String reason) {
        var counter = meterRegistry.find("hot_api_article_skip_total").tag("reason", reason).counter();
        return counter == null ? 0L : (long) counter.count();
    }
}
