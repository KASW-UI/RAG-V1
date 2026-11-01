package com.rag.common.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §3.3 真触发测试 —— {@link Float64Deserializer} 的逐条 Go 判据。
 *
 * <p><b>Go 出处</b>：go-zero {@code core/mapping/unmarshaler.go:684-687}
 * （{@code case reflect.Float32: fValue, err := v.Float64()} ⇒ {@code 1e400} 返
 * {@code (+Inf, ErrRange)} ⇒ 整个请求 5001）。
 *
 * <p><b>⚠️ 本测试钉住的是「Jackson 2 的 {@code getText()} 对数字返回原始字面量」</b>
 * —— 这是 RAG（Jackson 3）版依赖、而本仓必须重新验证的机制（§3.3.1「配置里没写 ≠ 没开」
 * 的同款：API 存在 ≠ 行为相同）。
 */
class Float64DeserializerTest {

    static class Req {
        @JsonDeserialize(using = Float64Deserializer.class)
        public Double weight;
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("普通小数照常接受（Go: 1.5）")
    void plainFloatIsAccepted() throws Exception {
        assertThat(MAPPER.readValue("{\"weight\":1.5}", Req.class).weight).isEqualTo(1.5d);
    }

    @Test
    @DisplayName("1e39 接受（f64 合法）")
    void bigButFiniteIsAccepted() throws Exception {
        assertThat(MAPPER.readValue("{\"weight\":1e39}", Req.class).weight).isEqualTo(1e39d);
    }

    @Test
    @DisplayName("1e400 被拒（Go: json.Number(\"1e400\").Float64() ⇒ (+Inf, ErrRange)）")
    void overflowIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"weight\":1e400}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("-1e400 同样被拒")
    void negativeOverflowIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"weight\":-1e400}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("字符串被拒（Go: type mismatch）")
    void stringIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"weight\":\"1.5\"}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("布尔被拒（Go: type mismatch）")
    void booleanIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"weight\":true}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("显式 null 合法（Go: ,optional）")
    void explicitNullIsAllowed() throws Exception {
        assertThat(MAPPER.readValue("{\"weight\":null}", Req.class).weight).isNull();
    }

    @Test
    @DisplayName("字段缺席保持 null")
    void absentFieldStaysNull() throws Exception {
        assertThat(MAPPER.readValue("{}", Req.class).weight).isNull();
    }

    @Test
    @DisplayName("⚠️ 关键机制断言：Jackson 2 的 getText() 对数字返回**原始字面量**（1e400 不是 Infinity）")
    void rawLiteralIsPreservedForNumbers() throws Exception {
        // 若 getText() 返回的是规范化后的值（如 1e400 → "Infinity"），
        // 则本类会走进「无法解析为 float64」而不是「value out of range」分支 ⇒ 行为与 Go 不同。
        assertThatThrownBy(() -> MAPPER.readValue("{\"weight\":1e400}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class)
                .hasMessageContaining("value out of range");

        // 尾零也要保住：1.50 必须落成 1.5（Go: json.Number 的字面量规范化后再转 float64）
        assertThat(MAPPER.readValue("{\"weight\":1.50}", Req.class).weight).isEqualTo(1.5d);
    }
}
