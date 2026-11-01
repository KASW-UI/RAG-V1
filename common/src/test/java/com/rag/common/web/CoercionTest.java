package com.rag.common.web;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §3.3「机制可证」的真触发测试 —— {@link Coercion} 的两条规则<b>真的作用在 Jackson 上</b>。
 *
 * <p><b>为什么必须是「真触发」而不是 Mockito 桩</b>（AGENTS.md §3.3）：
 * 本类的全部价值在于「配置施加到 mapper 之后，Jackson 的解析行为变了」。
 * ⇒ 若只断言「{@code Coercion.applyTo} 被调用过」，那只证明代码写成了这样，
 * **证不了 Jackson 真的拒了** ⇒ ⇒ 这里用<b>真实</b> {@code JsonMapper} +
 * <b>真实</b> {@code readValue}，断言<b>可观察的解析结果</b>。
 *
 * <p><b>B 出处</b>：B 侧 {@code service/users/users-common/src/main/java/com/rag/users/common/http/GoStyleCoercion.java:18-32}
 * （RAG 仓，真进程 18889 实测，见
 * {@link Coercion} 类注释里逐条的 B 侧 判定表）。
 *
 * <p><b>⚠️ 这条测试同时是「Jackson 2 ↔ Jackson 3 行为一致」的证据</b>：
 * RAG 侧这些规则配在 Jackson 3 上；本仓配在 Jackson 2 上 ⇒ 本测试的期望值
 * <b>两侧共用同一份</b> ⇒ 若哪天有人改判据（放宽/收紧），两侧都会红。
 */
class CoercionTest {

    /** B 侧的请求 DTO 形状：String 字段 + int64 字段。 */
    static class Req {
        public String username;
        public Long uid;
    }

    /**
     * <b>真实</b>施加了 {@link Coercion} 两条规则的 mapper。
     */
    private static JsonMapper goStyleMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        Coercion.applyTo(builder);
        return builder.build();
    }

    private static final JsonMapper MAPPER = goStyleMapper();

    @Test
    @DisplayName("规则 1：数字进 String 字段被拒（B 侧: type mismatch for field \"username\"）")
    void numberIntoStringIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"username\":1}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("规则 1：浮点进 String 字段被拒")
    void floatIntoStringIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"username\":1.5}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("规则 1：布尔进 String 字段被拒")
    void booleanIntoStringIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"username\":true}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("规则 1：数组进 String 字段被拒（Jackson 默认即 Fail）")
    void arrayIntoStringIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"username\":[]}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("规则 1：正常字符串照常接受（不能把规则做成「一刀切全拒」）")
    void plainStringIsAccepted() throws Exception {
        Req req = MAPPER.readValue("{\"username\":\"alice\"}", Req.class);
        assertThat(req.username).isEqualTo("alice");
    }

    @Test
    @DisplayName("规则 2：浮点 1.0 进 Long 被拒（B 侧: strconv.ParseInt(\"1.0\") 失败）")
    void floatOneZeroIntoLongIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"uid\":1.0}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("规则 2：浮点 1.5 进 Long 被拒")
    void floatOneFiveIntoLongIsRejected() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"uid\":1.5}", Req.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("规则 2：整数进 Long 照常接受（B 侧: {\"uid\":1} → 200）")
    void integerIntoLongIsAccepted() throws Exception {
        Req req = MAPPER.readValue("{\"uid\":1}", Req.class);
        assertThat(req.uid).isEqualTo(1L);
    }

    @Test
    @DisplayName("刻意不动的一条：字符串进 Long 保持接受（B 侧: 先字符串化再 ParseInt）")
    void stringIntoLongIsStillAccepted() throws Exception {
        Req req = MAPPER.readValue("{\"uid\":\"1\"}", Req.class);
        assertThat(req.uid).isEqualTo(1L);
    }

    @Test
    @DisplayName("对照：不施加 Coercion 的裸 mapper 是宽松的（证明上面的拒是真规则带来的，不是 Jackson 默认）")
    void bareMapperIsLenient() throws Exception {
        JsonMapper bare = JsonMapper.builder().build();
        Req req = bare.readValue("{\"username\":1,\"uid\":1.5}", Req.class);
        assertThat(req.username).isEqualTo("1");
        assertThat(req.uid).isEqualTo(1L);
    }
}
