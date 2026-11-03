package com.rag.task.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rag.task.dto.task.GetTaskReq;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <p><b>{@code GetTaskReq} 的两段归一化（真 Jackson 2 mapper，无 Spring 上下文）</b>。</p>
 *
 * <h2>🔴 为什么组件类型是 {@code Object} —— 本类是那条决策的<b>证据</b></h2>
 * <p>Go 侧 {@code UserIDParam.UnmarshalJSON} 拿到的是<b>原始 JSON token</b>，
 * 字符串与数字两条路都要试（{@code api/internal/types/types.go:16-36}）。
 * <p>若 DTO 的组件声明成 {@code String}，Jackson 会先做<b>静默标量强转</b>：
 * {@code {"id":true}} 被转成 {@code "true"} ⇒ <b>永远不会抛</b>
 * {@code BadIdTypeException} ⇒ Go 侧那条 400 分支在 C 侧<b>不可达</b> ⇒ 方向相反的漂移。
 * <p>⇒ 本类用<b>真 Jackson</b>（非 mock）证明：声明成 {@code Object} 时，
 * 布尔/数组/对象三类输入<b>真的</b>抛 {@code BadIdTypeException}。</p>
 *
 * <h2>🔴 为什么「数字」要保留字面量文本</h2>
 * <p>Go 侧存的是 json.Number 的<b>字面量文本</b>（{@code types.go:31}），
 * 浮点写法则会把 {@code "1.5"} 原样存下，交给第二段的 {@code ParseInt} 拒掉。
 * <p>⇒ 本类断言 {@code {"id":1.5}} 得到的是 <b>{@code user_id invalid}</b>（第二段失败），
 * <b>不是</b> {@code id must be a string or integer}（第一段失败）——
 * <b>两段的报错边界不能糊</b>（糊了就是状态码相同、文案不同的漂移）。</p>
 */
class GetTaskReqNormalizationTest {

    /**
     * 用<b>真 Jackson</b>（不是 mock）—— 本类验的就是「Jackson 会不会静默强转」这件事
     * ⇒ 用 mock 的 mapper 验它等于没验。
     *
     * <p>⚠️ <b>必须显式关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}</b>：
     * 裸 {@code new ObjectMapper()} 的默认值是<b>开</b>，而 Spring Boot 的
     * {@code JacksonAutoConfiguration} 默认<b>关</b>它 ⇒ 真实运行时未知字段被忽略
     * （Go 的 {@code encoding/json} 也是默认忽略 ⇒ 两侧同形）。
     * <p>⇒ 用裸 mapper 会让「未知字段被忽略」这条用例<b>假红</b>（测的是夹具，不是被测对象）。
     * <p>（真 Tomcat 那条 {@code TaskApiWireContractRealTomcatTest} 的 ④-c 用例
     * 用的是<b>真 Boot mapper</b> ⇒ 它才是这条契约的权威断言，本类只作补充。）</p>
     */
    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    // ==================================================================
    // ① 第一段：反序列化时归一化
    // ==================================================================

    @Test
    @DisplayName("① 字符串 id ⇒ 保留（去空格）")
    void stringIdIsTrimmed() throws Exception {
        GetTaskReq req = mapper.readValue("{\"id\":\"  42  \"}", GetTaskReq.class);
        assertThat(req.idText()).isEqualTo("42");
        assertThat(req.userIdOrThrow()).isEqualTo(42L);
    }

    @Test
    @DisplayName("①-b 数字 id ⇒ 归一化成十进制文本（Go 的 json.Number 字面量）")
    void numericIdBecomesDecimalText() throws Exception {
        assertThat(mapper.readValue("{\"id\":123}", GetTaskReq.class).idText()).isEqualTo("123");
        assertThat(mapper.readValue("{\"id\":123}", GetTaskReq.class).userIdOrThrow()).isEqualTo(123L);
    }

    @Test
    @DisplayName("①-c 浮点 id ⇒ 文本里带 '.' ⇒ 第二段 ParseInt 必然失败 ⇒ user_id invalid")
    void fractionalIdKeepsFractionalText() throws Exception {
        GetTaskReq req = mapper.readValue("{\"id\":1.5}", GetTaskReq.class);
        assertThat(req.idText()).isEqualTo("1.5");
        assertThatThrownBy(req::userIdOrThrow)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("user_id invalid");
    }

    @Test
    @DisplayName("①-d 1.0 也带 '.' ⇒ 同样被第二段拒掉（不是「小数部分非零才拒」）")
    void onePointZeroIsAlsoRejected() throws Exception {
        GetTaskReq req = mapper.readValue("{\"id\":1.0}", GetTaskReq.class);
        assertThat(req.idText()).isEqualTo("1.0");
        assertThatThrownBy(req::userIdOrThrow).hasMessage("user_id invalid");
    }

    @Test
    @DisplayName("①-e null / 缺字段 ⇒ 归一化成空串（Go 的零值）⇒ 第二段 user_id invalid")
    void nullAndMissingIdBecomeEmpty() throws Exception {
        assertThat(mapper.readValue("{\"id\":null}", GetTaskReq.class).idText()).isEmpty();
        assertThat(mapper.readValue("{}", GetTaskReq.class).idText()).isEmpty();
        assertThatThrownBy(() -> mapper.readValue("{\"id\":null}", GetTaskReq.class).userIdOrThrow())
                .hasMessage("user_id invalid");
    }

    @Test
    @DisplayName("①-f 布尔 / 数组 / 对象 id ⇒ 第一段就抛「id must be a string or integer」")
    void nonScalarIdIsRejectedAtUnmarshalTime() {
        for (String body : new String[]{"{\"id\":true}", "{\"id\":false}", "{\"id\":[1]}",
                "{\"id\":{\"a\":1}}"}) {
            assertThatThrownBy(() -> mapper.readValue(body, GetTaskReq.class))
                    .as("body=%s", body)
                    .hasRootCauseInstanceOf(GetTaskReq.BadIdTypeException.class)
                    .rootCause()
                    .hasMessage("id must be a string or integer");
        }
    }

    @Test
    @DisplayName("①-g 未知字段被忽略（Go 的 encoding/json 默认行为）")
    void unknownFieldsAreIgnored() throws Exception {
        GetTaskReq req = mapper.readValue("{\"id\":\"1\",\"zzz\":{\"a\":1}}", GetTaskReq.class);
        assertThat(req.userIdOrThrow()).isEqualTo(1L);
    }

    // ==================================================================
    // ② 第二段：取值时校验
    // ==================================================================

    @Test
    @DisplayName("② 三条失败路径共用同一个文案「user_id invalid」")
    void allValidationFailuresShareTheSameText() {
        assertThatThrownBy(() -> new GetTaskReq("").userIdOrThrow())
                .hasMessage("user_id invalid");
        assertThatThrownBy(() -> new GetTaskReq("0").userIdOrThrow())
                .hasMessage("user_id invalid");
        assertThatThrownBy(() -> new GetTaskReq("-1").userIdOrThrow())
                .hasMessage("user_id invalid");
        assertThatThrownBy(() -> new GetTaskReq("abc").userIdOrThrow())
                .hasMessage("user_id invalid");
    }

    @Test
    @DisplayName("②-b 「+12」被接受（Go 的 strconv.ParseInt 接受可选 '+'）")
    void leadingPlusIsAccepted() {
        assertThat(new GetTaskReq("+12").userIdOrThrow()).isEqualTo(12L);
    }

    @Test
    @DisplayName("②-c 超出 long 范围 ⇒ user_id invalid（ParseInt 溢出）")
    void overflowIsRejected() {
        assertThatThrownBy(() -> new GetTaskReq("99999999999999999999").userIdOrThrow())
                .hasMessage("user_id invalid");
    }
}
