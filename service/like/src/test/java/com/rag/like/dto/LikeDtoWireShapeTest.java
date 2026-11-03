package com.rag.like.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.like.dto.action.LikeActionReq;
import com.rag.like.dto.count.GetLikeCountReq;
import com.rag.like.dto.state.GetLikeStateReq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>like 域 DTO 的「线上 wire 形状」逐条断言</b>（纯单元，<b>不起 Spring 上下文</b>）。
 *
 * <h2>🔴 为什么这一层必须单独钉</h2>
 * <p>DTO 是 <b>L1 契约</b>（字段名 / 类型 / 必填 / 强转口径），而且 Jackson 在 record + 显式
 * {@code @JsonCreator} 静态工厂下有一处<b>极易踩空</b>的继承规则：
 * <blockquote>
 * 组件上的 {@code @JsonDeserialize} <b>会不会被 creator 参数继承</b>，
 * 取决于<b>两者的 JSON 名是否同名</b> ⇒ 同名则继承（生效）、异名则断链（不生效）。
 * </blockquote>
 * ⚠️ 这条规则<b>不报错、不告警</b>：反序列化照常成功，只是走的是默认反序列化器
 * ⇒ 「注解明明写了却没生效」在集成测试里往往<b>看不出来</b>
 * （只有当默认行为与 B 侧不同才会暴露，而那种输入通常很少被测到）。
 * ⇒ ⇒ 本类把「B 侧接受 / 拒绝的每一种输入形态」逐条列出来（**结果面**）。
 *
 * <p>⚠️ <b>本类只钉「结果」，不钉「机制」</b> —— 机制面由
 * {@link LikeJsonCreatorAnnotationScopeTest} 用<b>区分性</b>实验单独钉
 * （5 个同形 fixture 只挪注解位置 ⇒ 断言「哪一处真正生效」）。
 * ⇒ 两者的分工：<b>结果面</b>归本类，<b>机制面</b>归那个类。
 *
 * <h2>抄写基准（B 侧 · 唯一 oracle）</h2>
 * <ul>
 *   <li>{@code target_ids}：B 侧 {@code like-api/.../dto/GoStyleRawStringListDeserializer.java}
 *       （共享层已上收为 {@code common/web/RawStringListDeserializer.java}）；</li>
 *   <li>{@code author_id}：B 侧 {@code ,string} int64 ⇒ {@code common/web/StringInt64.java}；</li>
 *   <li>{@code weight}：B 侧 {@code like-api/.../dto/GoStyleFloat64Deserializer.java}
 *       （共享层已上收为 {@code common/web/Float64Deserializer.java}）。</li>
 * </ul>
 *
 * <h2>⚠️ 本类用的是<b>裸</b> {@link ObjectMapper}（不是应用的 mapper）</h2>
 * <p>原因：应用 mapper 上还挂着共享层的<b>全局强转补强</b>
 * （{@code Textual ← Integer = Fail} 等），那是<b>另一层</b>语义（由 HTTP 层真触发测试覆盖）。
 * ⇒ 本类只验「<b>DTO 自己挂的反序列化器</b>是否真的生效」⇒ 必须用裸 mapper（否则两套规则混在一起，分不清谁在起作用）。
 */
class LikeDtoWireShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================================================================
    // 1. target_ids（RawStringListDeserializer）
    // ==================================================================

    @Test
    @DisplayName("🔴 target_ids 的元素是数字时必须保留**原始字面量**（1.50 → \"1.50\"，不是 \"1.5\"）")
    void targetIdsKeepNumberLiteral() throws Exception {
        GetLikeCountReq req = MAPPER.readValue(
                "{\"target_type\":\"a\",\"target_ids\":[1.50]}", GetLikeCountReq.class);
        assertThat(req.targetIds()).containsExactly("1.50");

        GetLikeCountReq exp = MAPPER.readValue(
                "{\"target_type\":\"a\",\"target_ids\":[1e3]}", GetLikeCountReq.class);
        assertThat(exp.targetIds())
                .as("Go 侧 json.Number 的字面量是 \"1e3\"；走 Double 会变成 \"1000.0\" ⇒ 与 B 侧不同")
                .containsExactly("1e3");
    }

    @Test
    @DisplayName("🔴 target_ids 的 null 元素必须原样保留为 Java null（不是字符串 \"null\"）")
    void targetIdsKeepNullElements() throws Exception {
        GetLikeCountReq req = MAPPER.readValue(
                "{\"target_type\":\"a\",\"target_ids\":[\"x\",null,\"y\"]}", GetLikeCountReq.class);
        assertThat(req.targetIds()).containsExactly("x", null, "y");
    }

    @Test
    @DisplayName("🔴 target_ids 的 bool / object / array 元素必须被拒（B 侧是 errTypeMismatch）")
    void targetIdsRejectNonScalarElements() {
        for (String body : new String[]{
                "{\"target_type\":\"a\",\"target_ids\":[true]}",
                "{\"target_type\":\"a\",\"target_ids\":[{\"k\":1}]}",
                "{\"target_type\":\"a\",\"target_ids\":[[1]]}"}) {
            assertThatThrownBy(() -> MAPPER.readValue(body, GetLikeCountReq.class))
                    .as("body = %s", body)
                    .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
        }
    }

    @Test
    @DisplayName("🔴 target_ids 必须是 JSON 数组（不是对象、不是字符串）")
    void targetIdsMustBeArray() {
        assertThatThrownBy(() -> MAPPER.readValue(
                "{\"target_type\":\"a\",\"target_ids\":\"x\"}", GetLikeCountReq.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("🔴 GetLikeStateReq 的 target_ids 走同一套反序列化器")
    void likeStateReqUsesSameDeserializer() {
        assertThatThrownBy(() -> MAPPER.readValue(
                "{\"target_type\":\"a\",\"target_ids\":[true]}", GetLikeStateReq.class))
                .as("两个 DTO 都要挂上工厂参数上的 @JsonDeserialize")
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    // ==================================================================
    // 2. author_id（,string int64）
    // ==================================================================

    @Test
    @DisplayName("🔴 author_id 接受字符串与整数两种形态（B 侧的 ,string 是「入参宽松」）")
    void authorIdAcceptsStringAndInteger() throws Exception {
        assertThat(MAPPER.readValue(
                "{\"target_type\":\"a\",\"target_id\":\"b\",\"action_type\":1,\"author_id\":\"123\"}",
                LikeActionReq.class).authorId()).isEqualTo(123L);

        assertThat(MAPPER.readValue(
                "{\"target_type\":\"a\",\"target_id\":\"b\",\"action_type\":1,\"author_id\":123}",
                LikeActionReq.class).authorId()).isEqualTo(123L);
    }

    @Test
    @DisplayName("🔴 author_id 拒绝浮点 / 布尔 / 非十进制 / 超 int64（B 侧 ParseInt 的判据）")
    void authorIdRejectsNonInt64() {
        for (String body : new String[]{
                "{\"target_type\":\"a\",\"target_id\":\"b\",\"action_type\":1,\"author_id\":1.5}",
                "{\"target_type\":\"a\",\"target_id\":\"b\",\"action_type\":1,\"author_id\":true}",
                "{\"target_type\":\"a\",\"target_id\":\"b\",\"action_type\":1,\"author_id\":\"1e2\"}",
                "{\"target_type\":\"a\",\"target_id\":\"b\",\"action_type\":1,\"author_id\":\"abc\"}",
                "{\"target_type\":\"a\",\"target_id\":\"b\",\"action_type\":1,"
                        + "\"author_id\":99999999999999999999}"}) {
            assertThatThrownBy(() -> MAPPER.readValue(body, LikeActionReq.class))
                    .as("body = %s", body)
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================================================================
    // 3. action_type（options = 1|2|3|4）
    // ==================================================================

    @Test
    @DisplayName("🔴 action_type 只接受 1/2/3/4（0、5、1.0 全部拒绝）")
    void actionTypeMustBeOneOfFour() throws Exception {
        for (long ok : new long[]{1, 2, 3, 4}) {
            LikeActionReq req = MAPPER.readValue(
                    "{\"target_type\":\"a\",\"target_id\":\"b\",\"action_type\":" + ok
                            + ",\"author_id\":1}", LikeActionReq.class);
            assertThat(req.actionType()).isEqualTo(ok);
        }
        for (String bad : new String[]{"0", "5", "-1"}) {
            assertThatThrownBy(() -> MAPPER.readValue(
                    "{\"target_type\":\"a\",\"target_id\":\"b\",\"action_type\":" + bad
                            + ",\"author_id\":1}", LikeActionReq.class))
                    .as("action_type = %s 必须被拒（B 侧的 options 校验）", bad)
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================================================================
    // 4. weight（Float64Deserializer）
    // ==================================================================

    @Test
    @DisplayName("🔴 weight 的 1e400 必须被拒（B 侧是 ErrRange ⇒ 解析失败；Jackson 默认会给 +Infinity）")
    void weightRejectsOutOfRangeFloat() {
        assertThatThrownBy(() -> MAPPER.readValue(
                "{\"target_type\":\"a\",\"target_id\":\"b\",\"action_type\":1,\"author_id\":1,"
                        + "\"weight\":1e400}", LikeActionReq.class))
                .as("Float64Deserializer 必须真的挂在工厂参数上（否则默认 Double 会给出 +Infinity）")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("🔴 weight 缺席是合法的（B 侧 ,optional）")
    void weightMayBeAbsent() throws Exception {
        LikeActionReq req = MAPPER.readValue(
                "{\"target_type\":\"a\",\"target_id\":\"b\",\"action_type\":1,\"author_id\":1}",
                LikeActionReq.class);
        assertThat(req.weight()).isNull();
    }

    // ==================================================================
    // 5. A9：DTO 必须是 immutable record（无 setter）
    // ==================================================================

    @Test
    @DisplayName("🔴 三个入参 DTO 都必须是 record 且**没有 setter**（A9）")
    void requestDtosAreImmutableRecords() {
        for (Class<?> type : new Class<?>[]{GetLikeCountReq.class, GetLikeStateReq.class,
                LikeActionReq.class}) {
            assertThat(type.isRecord()).as("%s 必须是 record", type.getSimpleName()).isTrue();
            assertThat(type.getMethods())
                    .as("%s 不得有 setter", type.getSimpleName())
                    .filteredOn(m -> m.getName().startsWith("set"))
                    .isEmpty();
        }
    }
}
