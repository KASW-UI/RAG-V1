package com.rag.task.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.rag.task.support.TaskRealInfraTestBase;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * <p><b>task-api 的 wire 契约（真 Tomcat）</b> —— 真 Spring 上下文 ＋ 真 Tomcat ＋ 真 HTTP 请求。</p>
 *
 * <h2>🔴 本类钉住的是本域<b>最危险的一条耦合</b></h2>
 * <p>{@code config/TaskApiJsonBodyConfig} 靠 <b>同名 bean</b>
 * （{@code @Configuration("seaJsonBodyConverterConfigurer")}）让共享层的
 * {@code JsonBodyConverterAutoConfiguration} <b>退让</b> ——
 * 因为共享层那个转换器在 Content-Type 不含 {@code application/json} 时会
 * <b>丢弃整个 body</b>（复刻 go-zero {@code httpx.Parse} 的 {@code withJsonBody} 判据），
 * 而 task 域走的是<b>裸 decoder</b>（任何 Content-Type 都<b>要读</b>）。
 * <p>⇒ 若共享层哪天改了 bean 名 ⇒ 本域的 auto-config <b>不再退让</b>
 * ⇒ ⇒ <b>两个转换器同时注册</b> ⇒ 共享层那个排在前面 ⇒ 非 JSON 的 body 被丢弃
 * ⇒ ⇒ {@code {"id":"1"}} 变成 {@code 400}（而不是 {@code 200}）。
 * <p>⇒ ⇒ 本类第 ② 条用例（{@code Content-Type: text/plain} ⇒ 200）就是这条耦合的
 * <b>唯一防线</b>：它<b>真发请求</b>，不查 bean、不查注解。</p>
 *
 * <h2>🔴 形状类判据逐条对应 B 侧</h2>
 * <table border="1">
 *   <caption>判据 ↔ B 出处</caption>
 *   <tr><th>用例</th><th>B 出处</th></tr>
 *   <tr><td>成功响应<b>无信封</b>（body 直接是 {@code {"tasks":[...]}}）</td>
 *       <td>B 侧未调 {@code httpx.SetOkHandler} ⇒ {@code OkJsonCtx} 写原始对象</td></tr>
 *   <tr><td>{@code {"id":true}} ⇒ 400 {@code id must be a string or integer}</td>
 *       <td>{@code api/internal/types/types.go:35}</td></tr>
 *   <tr><td>{@code {"id":1.5}} ⇒ 400 {@code user_id invalid}</td>
 *       <td>{@code api/internal/types/types.go:45-47}（{@code strconv.ParseInt} 失败）</td></tr>
 *   <tr><td>缺 {@code id} / {@code id:"0"} ⇒ 400 {@code user_id invalid}</td>
 *       <td>{@code api/internal/types/types.go:38-50}</td></tr>
 *   <tr><td>方法不匹配 ⇒ <b>405</b> ＋ {@code Allow} 头 ＋ <b>无 body</b></td>
 *       <td>go-zero {@code router/patrouter.go:79-82}（本域<b>零 CORS</b>）</td></tr>
 *   <tr><td>路径不存在 ⇒ 404 ＋ {@code 404 page not found\n} ＋ text/plain ＋ nosniff</td>
 *       <td>go-zero {@code router/patrouter.go:73} → {@code http.NotFound}</td></tr>
 * </table>
 *
 * <h2>⚠️ 为什么<b>不</b>用 MockMvc</h2>
 * <p>AGENTS.md §八 检查清单：「HTTP 断言走真 Tomcat —— 断言 {@code Content-Type} /
 * {@code nosniff} / 状态行时不用 MockMvc」。
 * <p>⚠️ {@code TestRestTemplate} 对 4xx/5xx <b>不抛异常</b>（它用
 * {@code NoOpResponseErrorHandler}）⇒ 本类可以直接拿到 4xx 的 body 逐字断言。</p>
 */
class TaskApiWireContractRealTomcatTest extends TaskRealInfraTestBase {

    @Autowired
    private TestRestTemplate rest;

    private static final String PATH = "/taskcenter/v1/task/get";

    // ==================================================================
    // ① 成功路径：无信封 + 两条任务定义 + 顺序
    // ==================================================================

    @Test
    @DisplayName("① application/json + 合法 id ⇒ 200，body 无信封，tasks 顺序与 TaskDefs 一致")
    void jsonBodyReturnsBareResponse() {
        ResponseEntity<String> resp = post(MediaType.APPLICATION_JSON, "{\"id\":\"1\"}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // ⚠️ 无信封：body 的第一个字符就是 '{'，且**没有** "code"/"msg"/"data" 三件套
        assertThat(resp.getBody())
                .startsWith("{\"tasks\":[")
                .doesNotContain("\"code\"")
                .doesNotContain("\"msg\"")
                .doesNotContain("\"data\"");
        // 两条定义，顺序 = TaskDefs.ALL_TASK_DEFS（190 在前、1902 在后）
        // ⚠️ 键序 = TaskInfo 的 record 组件序 = Go 的结构体声明序（name/desc/task_id/…）
        assertThat(resp.getBody()).containsSubsequence(
                "\"name\":\"文章获赞任务\"", "\"taskId\":190",
                "\"name\":\"点赞任务\"", "\"taskId\":1902");
        // required_progress 取自任务定义（3 / 5），**不是** DB 的 target
        assertThat(resp.getBody()).contains("\"requiredProgress\":3");
        assertThat(resp.getBody()).contains("\"requiredProgress\":5");
        // content-type 是 JSON（写侧未被本域的转换器抢走）
        assertThat(resp.getHeaders().getContentType()).isNotNull();
        assertThat(resp.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
    }

    // ==================================================================
    // ② 🔴 最危险的一条：Content-Type 无关
    // ==================================================================

    @Test
    @DisplayName("② 🔴 text/plain 的 JSON body 仍被读取 ⇒ 200（证明共享层转换器已退让）")
    void nonJsonContentTypeIsStillParsed() {
        ResponseEntity<String> resp = post(MediaType.TEXT_PLAIN, "{\"id\":\"1\"}");

        // Go 侧是裸 json.NewDecoder(r.Body) ⇒ 任何 Content-Type 都读 body ⇒ 200
        assertThat(resp.getStatusCode())
                .as("共享层的 JsonBodyConverter 若没退让，本条会变成 400")
                .isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).startsWith("{\"tasks\":[");
    }

    @Test
    @DisplayName("②-b 完全不写 Content-Type ⇒ 仍被读取 ⇒ 200")
    void missingContentTypeIsStillParsed() {
        HttpHeaders headers = new HttpHeaders();
        // ⚠️ 刻意**不设** Content-Type ⇒ 容器会当成 application/octet-stream
        ResponseEntity<String> resp = rest.exchange(PATH, HttpMethod.POST,
                new HttpEntity<>("{\"id\":\"1\"}", headers), String.class);

        assertThat(resp.getStatusCode())
                .as("Go 侧不判 Content-Type ⇒ 不写也必须能读")
                .isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).startsWith("{\"tasks\":[");
    }

    // ==================================================================
    // ③ 请求体判据：两段归一化 + 两条文案
    // ==================================================================

    @Test
    @DisplayName("③ {\"id\":true} ⇒ 400 + 「id must be a string or integer」")
    void booleanIdIsRejectedWithGoText() {
        ResponseEntity<String> resp = post(MediaType.APPLICATION_JSON, "{\"id\":true}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // ⚠️ 尾随 "\n" 是 Go http.Error → Fprintln 的逐字等价物（共享层 PlainTextErrors 补）
        assertThat(resp.getBody()).isEqualTo("id must be a string or integer\n");
        assertThat(resp.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("③-b {\"id\":1.5} ⇒ 400 + 「user_id invalid」（字面量保留 ⇒ ParseInt 失败）")
    void fractionalIdIsRejectedWithGoText() {
        ResponseEntity<String> resp = post(MediaType.APPLICATION_JSON, "{\"id\":1.5}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isEqualTo("user_id invalid\n");
    }

    @Test
    @DisplayName("③-c {\"id\":1.0} ⇒ 400 + 「user_id invalid」（1.0 也不是合法整数）")
    void floatWithZeroFractionIsRejected() {
        ResponseEntity<String> resp = post(MediaType.APPLICATION_JSON, "{\"id\":1.0}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isEqualTo("user_id invalid\n");
    }

    @Test
    @DisplayName("③-d 缺 id / id 为空串 / id<=0 ⇒ 400 + 「user_id invalid」")
    void missingOrNonPositiveIdIsRejected() {
        for (String body : new String[]{"{}", "{\"id\":\"\"}", "{\"id\":\"0\"}", "{\"id\":0}",
                "{\"id\":\"-1\"}", "{\"id\":null}"}) {
            ResponseEntity<String> resp = post(MediaType.APPLICATION_JSON, body);
            assertThat(resp.getStatusCode()).as("body=%s", body).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).as("body=%s", body).isEqualTo("user_id invalid\n");
        }
    }

    // ==================================================================
    // ④ 归一化：TrimSpace + 数字字面量 + 未知字段
    // ==================================================================

    @Test
    @DisplayName("④ id 前后空格被 TrimSpace ⇒ 200")
    void idIsTrimmed() {
        ResponseEntity<String> resp = post(MediaType.APPLICATION_JSON, "{\"id\":\"  42  \"}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).startsWith("{\"tasks\":[");
    }

    @Test
    @DisplayName("④-b 纯数字 id（不是字符串）⇒ 200（Go 的 UnmarshalJSON 先试 json.Number）")
    void numericIdIsAccepted() {
        ResponseEntity<String> resp = post(MediaType.APPLICATION_JSON, "{\"id\":7}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).startsWith("{\"tasks\":[");
    }

    @Test
    @DisplayName("④-c 未知字段被忽略 ⇒ 200（Go 的 encoding/json 默认忽略）")
    void unknownFieldsAreIgnored() {
        ResponseEntity<String> resp = post(MediaType.APPLICATION_JSON, "{\"id\":\"1\",\"zzz\":{\"a\":1}}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).startsWith("{\"tasks\":[");
    }

    @Test
    @DisplayName("④-d 空 body ⇒ 400（Go 是 io.EOF ⇒ 解析失败 ⇒ 400）")
    void emptyBodyIsRejected() {
        ResponseEntity<String> resp = post(MediaType.APPLICATION_JSON, "");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ==================================================================
    // ⑤ 路由层：405 / 404
    // ==================================================================

    @Test
    @DisplayName("⑤ GET 同一路径 ⇒ 405 + Allow 头 + 无 body（本域零 CORS ⇒ 走 405 分支）")
    void wrongMethodYields405WithAllowHeader() {
        ResponseEntity<String> resp = rest.exchange(PATH, HttpMethod.GET,
                new HttpEntity<>(null), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.ALLOW)).contains("POST");
        // ⚠️ go-zero 的 405 只 WriteHeader、不写 body ⇒ body 必须为空
        assertThat(resp.getBody()).isNullOrEmpty();
        // ⚠️ 没有 body ⇒ 容器不补 Content-Type
        assertThat(resp.getHeaders().getContentType()).isNull();
    }

    @Test
    @DisplayName("⑤-b 不存在的路径 ⇒ 404 + 「404 page not found\\n」+ text/plain + nosniff")
    void unknownPathYields404() {
        ResponseEntity<String> resp = rest.exchange("/taskcenter/v1/task/nope",
                HttpMethod.POST, new HttpEntity<>("{\"id\":\"1\"}"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isEqualTo("404 page not found\n");
        assertThat(resp.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(resp.getHeaders().getContentType()).isNotNull();
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("text/plain");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private ResponseEntity<String> post(MediaType contentType, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        return rest.exchange(PATH, HttpMethod.POST,
                new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers), String.class);
    }
}
