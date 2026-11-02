package com.rag.message.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.rag.common.contract.ApiResponse;
import com.rag.message.advice.MessageApiExceptionHandler;
import com.rag.message.dto.common.PageReq;
import com.rag.message.dto.notification.AdminSendNotificationReq;
import com.rag.message.support.NacosStubConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>🔴 JSON 解析面（请求体契约）的可执行断言</b> —— 把「规则写在代码里」变成「规则生效且可回归」。
 *
 * <h2>为什么必须有这一类</h2>
 * <p>{@code WebMvcConfig#applyMessageDomainCoercions} 的 6 组规则**一直只以 javadoc 里的
 * 「实测读数」形式存在**（{@code WebMvcConfig.java:148-158}）⇒ 那段读数<b>不可回归</b>：
 * 改了规则、改了 DTO 的 {@code @JsonProperty}、换了 Jackson 版本，**都不会有人红**。
 * <p>⚠️ <b>而且那段读数本身有一处与 C 侧对不上</b>：它记的第 2 组输入是
 * <b>{@code {"unreadOnly":"true"}}</b>（<b>Go 侧的字段名</b>），
 * 而 <b>C 侧</b> {@link PageReq} 的 JSON 属性名是 <b>{@code unread_only}</b>（snake_case · 逐字照抄 B 侧）
 * ⇒ ⇒ <b>{@code {"unreadOnly":"true"}} 在 C 侧根本命中不了那个布尔字段</b>
 * （Spring Boot 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=false} ⇒ 它会被<b>静默忽略</b>），
 * ⇒ ⇒ 那条 javadoc 读数<b>不能</b>作为 C 侧「补强 2 生效」的证据。
 * <p>⇒ 本类用 <b>C 侧真实的 JSON 属性名</b>重跑这 6 组，并把断言<b>固化成可回归的测试</b>。
 *
 * <h2>本类验的是哪条链路</h2>
 * <pre>
 *   ① 生产 {@link ObjectMapper}（Spring 容器里那一个）
 *      └─ 装配来源：{@code JacksonAutoConfiguration} 把容器里所有
 *         {@code Jackson2ObjectMapperBuilderCustomizer} bean 依次作用到 builder 上
 *         ⇒ {@code WebMvcConfig#goStyleCoercionCustomizer}（共享 2 条 + 本域 4 条）**在链里**
 *      ⇒ ⇒ 本类断言的是<b>生产路径</b>，不是「另抄一份规则跑一遍」
 *         （后者 = 「拿没人校过的镜子照自己」· AGENTS.md §3.1 明确禁止）
 *   ② 解析失败 ⇒ {@link HttpMessageNotReadableException}
 *      ⇒ {@code MessageApiExceptionHandler#handleUnreadableBody}
 *      ⇒ <b>HTTP 200 + {code:5001}</b>（B 侧 go-zero {@code httpx.SetErrorHandler} 同款形状）
 * </pre>
 *
 * <h2>B 出处（规则真源 · 逐条照抄）</h2>
 * <ul>
 *   <li>B 侧规则实现：{@code service/message/message-api/src/main/java/com/rag/message/message/api/config/MessageGoStyleCoercion.java}
 *       （120 行 · C 侧 {@code WebMvcConfig#applyMessageDomainCoercions} 是它的 Jackson 3 → Jackson 2 API 回改版）</li>
 *   <li>B 侧 Go 消费点：{@code core/mapping/unmarshaler.go:643-662}
 *       （{@code processFieldPrimitive}：{@code typeKind != valueKind} ⇒ {@code type mismatch}）
 *       ＋ {@code core/mapping/unmarshaler.go:854-863}
 *       （{@code processNamedFieldWithValue}：{@code mapValue == nil && opts.optional()} ⇒ 接受 null）</li>
 * </ul>
 *
 * <h2>⚠️ 本类**不使用** Lombok / {@code @Disabled} / {@code @EnabledIfDockerAvailable}</h2>
 */
@SpringBootTest(
        classes = {com.rag.message.MessageServiceApplication.class, NacosStubConfiguration.class})
class MessageCoercionContractTest {

    /** gRPC 端口 —— 取一个真实空闲端口（与 {@code MessageServiceApplicationTests} 同款理由）。 */
    static final int GRPC_PORT;

    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            GRPC_PORT = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("取空闲端口失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", com.rag.message.support.TestPostgres::jdbcUrl);
        registry.add("spring.datasource.username", com.rag.message.support.TestPostgres::username);
        registry.add("spring.datasource.password", com.rag.message.support.TestPostgres::password);
        // 两把 JWT 密钥（message-api 的 yml 里是裸占位符 ⇒ 不给值上下文就起不来）
        registry.add("USER_JWT_SECRET", () -> "message-boot-test-user-secret-0123456789");
        registry.add("ADMIN_JWT_SECRET", () -> "message-boot-test-admin-secret-0123456789");
        registry.add("grpc.server.port", () -> GRPC_PORT);
        registry.add("spring.cloud.nacos.discovery.server-addr", () -> "127.0.0.1:1");
    }

    /** **生产** {@link ObjectMapper} —— 容器里那一个（不是另 new 一个）。 */
    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MessageApiExceptionHandler exceptionHandler;

    // ==================================================================
    // ① 补强 1：JSON 字符串不进整数字段
    // ==================================================================

    @Test
    @DisplayName("🔴 补强 1：{\"offset\":\"10\"} 必须被拒（JSON 字符串 → 整数字段 · Go 侧 type mismatch）")
    void jsonStringMustNotEnterIntegerField() throws Exception {
        assertThatThrownBy(() -> mapper.readValue("{\"offset\":\"10\"}", PageReq.class))
                .as("Go: unmarshaler.go:643-662 processFieldPrimitive ⇒ typeKind != valueKind ⇒ type mismatch；"
                        + "Jackson 默认会安静地把 \"10\" 变成 10 ⇒ 本域必须显式 Fail")
                .isInstanceOf(MismatchedInputException.class);

        // 对照：同类型的**数字**必须照常接受（证明这条规则不是「整条消息都拒」）
        PageReq ok = mapper.readValue("{\"offset\":10}", PageReq.class);
        assertThat(ok.offset()).isEqualTo(10);
    }

    // ==================================================================
    // ② 补强 2：JSON 字符串不进布尔字段
    // ==================================================================

    @Test
    @DisplayName("🔴 补强 2：{\"unread_only\":\"true\"} 必须被拒（JSON 字符串 → 布尔字段）")
    void jsonStringMustNotEnterBooleanField() throws Exception {
        assertThatThrownBy(() -> mapper.readValue("{\"unread_only\":\"true\"}", PageReq.class))
                .as("Go: 同一条 processFieldPrimitive 路径 ⇒ type mismatch；"
                        + "⚠️ C 侧的 JSON 属性名是 unread_only（snake_case · 逐字照抄 B 侧）—— "
                        + "WebMvcConfig javadoc 里记的 {\"unreadOnly\":\"true\"} 是 Go 侧命名，"
                        + "在 C 侧命中不了字段（未知属性被静默忽略）⇒ 那条读数不能作证据")
                .isInstanceOf(MismatchedInputException.class);

        // 对照：真正的布尔必须照常接受
        PageReq ok = mapper.readValue("{\"unread_only\":true}", PageReq.class);
        assertThat(ok.unreadOnly()).isTrue();
    }

    // ==================================================================
    // ③ 补强 3：JSON 数字也不进布尔字段（投放事故级）
    // ==================================================================

    @Test
    @DisplayName("🔴 补强 3：{\"broadcast\":1} 必须被拒（JSON 数字 → 布尔字段 · 否则单播变全站广播）")
    void jsonNumberMustNotEnterBooleanField() throws Exception {
        assertThatThrownBy(() -> mapper.readValue("{\"broadcast\":1}", AdminSendNotificationReq.class))
                .as("Go: encoding/json 对 number → bool 报错（cannot unmarshal number into bool）；"
                        + "Jackson 默认接受并静默转换（1 ⇒ true）⇒ broadcast 决定「定向」还是「全量广播」，"
                        + "静默转换 = 一条定向通知被投成全站广播")
                .isInstanceOf(InvalidFormatException.class);

        // 对照：真正的布尔必须照常接受
        AdminSendNotificationReq ok = mapper.readValue(
                "{\"broadcast\":true,\"title\":\"t\",\"content\":\"c\"}", AdminSendNotificationReq.class);
        assertThat(ok.broadcast()).isTrue();
    }

    // ==================================================================
    // ④ 共享规则 1：JSON 数字不进 String 字段
    // ==================================================================

    @Test
    @DisplayName("🔴 共享规则 1：{\"title\":123} 必须被拒（JSON 数字 → String 字段）")
    void jsonNumberMustNotEnterStringField() throws Exception {
        assertThatThrownBy(() -> mapper.readValue(
                "{\"broadcast\":false,\"title\":123,\"content\":\"c\"}", AdminSendNotificationReq.class))
                .as("共享层 Coercion 第 1 条（number/bool 不进 String 字段）生效")
                .isInstanceOf(InvalidFormatException.class);
    }

    // ==================================================================
    // ⑤ 补强 4：显式 null ⇒ 零值（Go 的 ,optional 字段接受 null）
    // ==================================================================

    @Test
    @DisplayName("🔴 补强 4：{\"offset\":null} 必须被接受且 offset=0（Go 的 ,optional 接受 null）")
    void explicitNullMustBecomeZeroValue() throws Exception {
        PageReq page = mapper.readValue("{\"offset\":null}", PageReq.class);
        assertThat(page.offset())
                .as("Go: unmarshaler.go:854-863 ⇒ mapValue == nil && opts.optional() ⇒ 接受并取零值；"
                        + "Jackson 的 FAIL_ON_NULL_FOR_PRIMITIVES 默认开启 ⇒ 必须显式关掉")
                .isZero();
    }

    // ==================================================================
    // ⑥ 正常组合必须照常接受
    // ==================================================================

    @Test
    @DisplayName("🔴 正常组合 {\"offset\":10,\"unread_only\":true} 必须被接受且取值正确")
    void normalCombinationMustBeAccepted() throws Exception {
        PageReq page = mapper.readValue("{\"offset\":10,\"unread_only\":true}", PageReq.class);
        assertThat(page.offset()).isEqualTo(10);
        assertThat(page.unreadOnly()).isTrue();
    }

    // ==================================================================
    // ⑦ 解析失败 ⇒ HTTP 200 + {code:5001}（B 侧 go-zero 的错误信封形状）
    // ==================================================================

    @Test
    @DisplayName("🔴 解析失败 ⇒ MessageApiExceptionHandler 吐 HTTP 200 + {code:5001}（B 侧 go-zero 同款形状）")
    void unreadableBodyMustBecomeHttp200WithCode5001() {
        HttpMessageNotReadableException cause;
        try {
            mapper.readValue("{\"offset\":\"10\"}", PageReq.class);
            throw new AssertionError("预期解析失败，但正常返回了");
        } catch (IOException e) {
            // Spring 6 的两参构造器：只把 cause 存起来，不依赖请求上下文 ⇒ 本类不需要真请求
            cause = new HttpMessageNotReadableException("JSON parse error", e);
        }

        ResponseEntity<ApiResponse> resp = exceptionHandler.handleUnreadableBody(cause);
        assertThat(resp.getStatusCode().value())
                .as("B 侧 go-zero httpx.SetErrorHandler：解析失败也是 HTTP 200 + 业务码信封")
                .isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode())
                .as("业务码必须是 5001（ERROR_SERVER_COMMON）")
                .isEqualTo(5001);
    }
}
