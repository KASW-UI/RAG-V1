package com.rag.common.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §3.3 真触发测试 —— {@link MapStringValueDeserializer} 的 map 元素级判据。
 *
 * <p><b>B 出处</b>：B 侧 {@code service/message/message-api/src/main/java/com/rag/message/message/api/config/GoStyleMapStringValueDeserializer.java:85:385-417}
 * （RAG 仓，{@code generateMap} 的 default 分支）：{@code case json.Number → setValueFromString(...)}
 * ⇒ 数字收下并字符串化；{@code case bool} 与 {@code default(nil)} 一律 type mismatch。
 *
 * <p><b>⚠️ 本测试钉住两件最容易漏的事</b>：
 * <ol>
 *   <li><b>map 元素的 null 走的是 {@code getNullValue} 而不是 {@code deserialize}</b>
 *       ⇒ 只覆写 {@code deserialize} 的话 {@code {"k":null}} 会被**静默放行**
 *       （DRIFT-MSGAPI-007）；</li>
 *   <li><b>数字要保留原始字面量</b>：{@code 1.0} ⇒ {@code "1.0"}、{@code 1e2} ⇒ {@code "1e2"}。</li>
 * </ol>
 */
class MapStringValueDeserializerTest {

    static class Req {
        @JsonDeserialize(contentUsing = MapStringValueDeserializer.class)
        public Map<String, String> extra;
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("字符串元素原样接受")
    void stringValueIsAccepted() throws Exception {
        assertThat(MAPPER.readValue("{\"extra\":{\"k\":\"v\"}}", Req.class).extra)
                .containsEntry("k", "v");
    }

    @Test
    @DisplayName("⚠️ 数字元素保留原始字面量：1.0 ⇒ \"1.0\"（不是 \"1\"）")
    void floatValueKeepsRawLiteral() throws Exception {
        assertThat(MAPPER.readValue("{\"extra\":{\"k\":1.0}}", Req.class).extra)
                .containsEntry("k", "1.0");
    }

    @Test
    @DisplayName("⚠️ 指数记数法元素保留原始字面量：1e2 ⇒ \"1e2\"")
    void exponentValueKeepsRawLiteral() throws Exception {
        assertThat(MAPPER.readValue("{\"extra\":{\"k\":1e2}}", Req.class).extra)
                .containsEntry("k", "1e2");
    }

    @Test
    @DisplayName("整数元素字符串化")
    void integerValueIsStringified() throws Exception {
        assertThat(MAPPER.readValue("{\"extra\":{\"k\":7}}", Req.class).extra)
                .containsEntry("k", "7");
    }

    @Test
    @DisplayName("⚠️ map 元素为显式 null ⇒ 拒绝（B 侧: generateMap 的 default 分支 ⇒ errTypeMismatch）")
    void nullMapValueIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"extra\":{\"k\":null}}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    @Test
    @DisplayName("布尔元素被拒")
    void booleanValueIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"extra\":{\"k\":true}}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    @Test
    @DisplayName("⚠️ 整个字段为 null 不受影响（B 侧: ,optional ⇒ 接受）—— 别把元素级规则误挂到字段级")
    void wholeFieldNullIsAccepted() throws Exception {
        assertThat(MAPPER.readValue("{\"extra\":null}", Req.class).extra).isNull();
    }
}
