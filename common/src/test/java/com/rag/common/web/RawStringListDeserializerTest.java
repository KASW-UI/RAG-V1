package com.rag.common.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §3.3 真触发测试 —— {@link RawStringListDeserializer} 的 go-zero {@code json.Number} 语义。
 *
 * <p><b>Go 出处</b>：go-zero {@code core/jsonx/json.go:74-77}（{@code decoder.UseNumber()}）
 * ＋ {@code core/mapping/unmarshaler.go:228-229}（{@code fillSliceValue} 的
 * {@code case fmt.Stringer} ⇒ 数字被字符串化接受）
 * ＋ {@code core/mapping/utils.go}（{@code convertTypeFromString} 的
 * {@code case reflect.String: return str, nil} ⇒ 原样返回，不解析不校验）。
 *
 * <p><b>⚠️ 本测试钉住的是「原始字面量」这一条</b>：{@code 1.50} 必须落成
 * {@code "1.50"}、{@code 1e3} 必须落成 {@code "1e3"} —— 若 Jackson 2 的
 * {@code getText()} 给的是规范化值（{@code "1.5"} / {@code "1000.0"}），
 * 则与 Go 的 {@code json.Number.String()} **不同形** ⇒ 幂等键/目标 id 会漂。
 */
class RawStringListDeserializerTest {

    static class Req {
        @JsonProperty("target_ids")
        @JsonDeserialize(using = RawStringListDeserializer.class)
        public List<String> targetIds;
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("整数数组被字符串化（Go: [1,2] ⇒ [\"1\",\"2\"]）")
    void integersAreStringified() throws Exception {
        assertThat(MAPPER.readValue("{\"target_ids\":[1,2]}", Req.class).targetIds)
                .containsExactly("1", "2");
    }

    @Test
    @DisplayName("⚠️ 尾零必须保住：1.50 ⇒ \"1.50\"（不是 \"1.5\"）")
    void trailingZeroIsPreserved() throws Exception {
        assertThat(MAPPER.readValue("{\"target_ids\":[1.50]}", Req.class).targetIds)
                .containsExactly("1.50");
    }

    @Test
    @DisplayName("⚠️ 指数记数法必须保住：1e3 ⇒ \"1e3\"（不是 \"1000.0\"，也不是 \"1E+3\"）")
    void exponentNotationIsPreserved() throws Exception {
        assertThat(MAPPER.readValue("{\"target_ids\":[1e3]}", Req.class).targetIds)
                .containsExactly("1e3");
    }

    @Test
    @DisplayName("负指数也保住：-2.5e-7 ⇒ \"-2.5e-7\"")
    void negativeExponentIsPreserved() throws Exception {
        assertThat(MAPPER.readValue("{\"target_ids\":[-2.5e-7]}", Req.class).targetIds)
                .containsExactly("-2.5e-7");
    }

    @Test
    @DisplayName("⚠️ 超长整数不溢出（原样字面量，Go: convertTypeFromString 不校验）")
    void hugeIntegerIsNotOverflowed() throws Exception {
        assertThat(MAPPER.readValue("{\"target_ids\":[99999999999999999999]}", Req.class).targetIds)
                .containsExactly("99999999999999999999");
    }

    @Test
    @DisplayName("null 元素必须是 Java null（不是字符串 \"null\"）—— 承担当 DRIFT-LIKEAPI-013")
    void nullElementBecomesJavaNull() throws Exception {
        List<String> ids = MAPPER.readValue("{\"target_ids\":[null]}", Req.class).targetIds;
        assertThat(ids).hasSize(1);
        assertThat(ids.get(0)).isNull();
    }

    @Test
    @DisplayName("⚠️ 整个字段为 null ⇒ 走 getNullValue ⇒ 返回 null（**不是** RAG 类注释说的「空列表」）")
    void wholeFieldNullYieldsNullNotEmptyList() throws Exception {
        // ⚠️ **实测发现**：RAG 的 `GoStyleRawStringListDeserializer` 类注释写
        //   「整个字段显式 null ⇒ 记为空列表（与『缺席』同形）」——
        //   但 **Jackson 对「字段值为 null」不会走 deserialize，而是走 getNullValue**
        //   ⇒ 而本类（与 RAG 同一实现）**没有**覆写 getNullValue
        //   ⇒ ⇒ 实际返回 **null**，不是空列表。
        // ⇒ 这在 **RAG 侧同样成立**（同一个类 + 同一套 Jackson 机制）
        // ⇒ ⇒ **不是漂移**，是 RAG 的「注释与实现不符」。
        // ⇒ ⇒ 本仓照抄**行为**（L2 以 RAG 为基准 · D13）⇒ 保持 null；
        //     只把类注释订正，不改行为。
        assertThat(MAPPER.readValue("{\"target_ids\":null}", Req.class).targetIds).isNull();
    }

    @Test
    @DisplayName("整个字段缺席 ⇒ 也是 null（与显式 null 同形）")
    void absentFieldYieldsNull() throws Exception {
        assertThat(MAPPER.readValue("{}", Req.class).targetIds).isNull();
    }

    @Test
    @DisplayName("字符串元素照常接受")
    void stringElementsAreAccepted() throws Exception {
        assertThat(MAPPER.readValue("{\"target_ids\":[\"abc\"]}", Req.class).targetIds)
                .containsExactly("abc");
    }

    @Test
    @DisplayName("布尔元素被拒（Go: errTypeMismatch）")
    void booleanElementIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"target_ids\":[true]}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("对象元素被拒")
    void objectElementIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"target_ids\":[{\"a\":1}]}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("字段不是数组 ⇒ 抛错")
    void nonArrayIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"target_ids\":\"x\"}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }
}
