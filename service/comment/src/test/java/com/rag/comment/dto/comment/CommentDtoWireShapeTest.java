package com.rag.comment.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>comment 域 16 个 DTO 的「线上 wire 形状」逐条断言</b>（纯单元，<b>不起 Spring 上下文</b>）。
 *
 * <h2>🔴 为什么这一层必须单独钉</h2>
 * <p>DTO 是 <b>L1 契约</b>（字段名 / 类型 / 顺序），是 comment 域对外的 HTTP 出入参形状。
 * 任何一处字段名改了（{@code user_id} → {@code userId}）、或顺序变了，
 * 线上就是「客户端解析不出来」；而 <b>JSON 语义仍然合法</b>，
 * 靠「能反序列化」这种断言<b>永远发现不了</b>（Jackson 默认按字母序输出，
 * 而 Go 的 {@code encoding/json} 按<b>结构体声明顺序</b>输出 ⇒ 顺序天然会分叉）。
 *
 * <h2>抄写基准（B 侧 · 唯一 oracle）</h2>
 * <p>B 侧 {@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/dto/}
 * 下的 <b>16 个类</b>，每个字段一个 {@code @JsonProperty("snake_case")}。
 * ⇒ 本类的 16 条期望 key 清单<b>逐条抄自那 16 个类</b>（共 <b>63 个字段</b>），
 * 期望 JSON 逐字节抄自 B 侧 Go 结构体的声明顺序。
 *
 * <h2>⚠️ 本类钉的是 <b>C 侧 record</b>，B 侧是 <b>可变 POJO</b></h2>
 * <p>B 侧 16 个 DTO 是「可变 class + 手写 getter/setter」，
 * C 侧按 D5 / A9 落成<b>不可变 record</b> ⇒ 访问器形态从 {@code getXxx()} 变成 {@code xxx()}，
 * 但<b>wire 形状（字段名 / 顺序 / 类型）必须逐条不变</b> ⇒ 这正是本类要证明的。
 *
 * <h3>L4 差异（AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.comment.comment.api.dto} 压平为
 *           {@code com.rag.comment.dto.comment}（D7 · D11 的 {@code dto/<业务对象>/} 子包）；</li>
 *       <li>DTO 形态由「可变 POJO」改为「不可变 record」（D5 / A9）⇒
 *           B 侧的 {@code assertGoRequiredFields} 手写校验器换成 Bean Validation
 *           （{@code @NotNull}），<b>校验语义</b>由本域 HTTP 层测试覆盖，
 *           本类只管<b>形状</b>。</li>
 *       <li>本类用<b>反射读 record component 上的 {@code @JsonProperty}</b>，
 *           而不是「读源码字符串比对」⇒ 断言的是<b>运行期真实元数据</b>
 *           （改注解位置/删注解 ⇒ 立即红）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。16 个 DTO 的 <b>63 个字段</b>的 JSON 名、
 *       Java 类型、<b>声明顺序</b>与 B 侧逐条一致（由
 *       {@link #everyDtoDeclaresTheSameJsonPropertyNamesInTheSameOrder} 逐字段断言，
 *       由 {@link #everyDtoSerializesToTheExactBsideByteSequence} 逐字节断言）。</li>
 *   <li><b>B 出处</b>：
 *     <ul>
 *       <li>字段清单来源：B 侧 {@code comment-api/src/main/java/com/rag/comment/comment/api/dto/}
 *           下的 16 个类（{@code CommentItem} / {@code CreateCommentReq} / {@code CreateCommentResp} /
 *           {@code DeleteCommentReq} / {@code DeleteCommentResp} / {@code GetCommentReq} /
 *           {@code GetCommentResp} / {@code LikeCommentReq} / {@code LikeCommentResp} /
 *           {@code ManageCommentReq} / {@code ManageCommentResp} / {@code ReportCommentReq} /
 *           {@code ReportCommentResp} / {@code SubjectInfo} / {@code UpdateSubjectStateReq} /
 *           {@code UpdateSubjectStateResp}）；</li>
 *       <li>Go 语义来源：B 侧各 DTO 的类 javadoc 自记其 Go 出处为
 *           {@code comment-api/.../types.go} 的 {@code types.*} 结构体
 *           （字段顺序 = Go {@code encoding/json} 的输出顺序）。</li>
 *     </ul></li>
 * </ol>
 */
class CommentDtoWireShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 一条 DTO 的 wire 期望。
     *
     * @param type         DTO 类型
     * @param jsonKeys     期望的 JSON 键清单（<b>按 wire 输出顺序</b>，逐条抄自 B 侧）
     * @param sample       用于序列化/往返的样例实例
     * @param expectedJson 期望的<b>逐字节</b> JSON
     */
    private record Fixture(Class<?> type, List<String> jsonKeys, Object sample, String expectedJson) {
    }

    private static final CommentItem SAMPLE_ITEM = new CommentItem(
            1L, 2L, "c", 3L, 4L, 5L, 6L, 7L, 8L, 9,
            "2026-11-03 10:00:00", "{}", List.of());

    private static final SubjectInfo SAMPLE_SUBJECT = new SubjectInfo(
            "article", "a1", 61L, 62L, 1, 63L, 64L);

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture(CommentItem.class,
                    List.of("id", "user_id", "content", "root_id", "parent_id", "like_count",
                            "dislike_count", "reply_count", "attribute", "state", "created_at",
                            "meta", "children"),
                    SAMPLE_ITEM,
                    "{\"id\":1,\"user_id\":2,\"content\":\"c\",\"root_id\":3,\"parent_id\":4,"
                            + "\"like_count\":5,\"dislike_count\":6,\"reply_count\":7,\"attribute\":8,"
                            + "\"state\":9,\"created_at\":\"2026-11-03 10:00:00\",\"meta\":\"{}\","
                            + "\"children\":[]}"),

            new Fixture(CreateCommentReq.class,
                    List.of("target_type", "target_id", "root_id", "parent_id", "content", "meta"),
                    new CreateCommentReq("article", "a1", 0L, 0L, "hi", "{}"),
                    "{\"target_type\":\"article\",\"target_id\":\"a1\",\"root_id\":0,\"parent_id\":0,"
                            + "\"content\":\"hi\",\"meta\":\"{}\"}"),

            new Fixture(CreateCommentResp.class,
                    List.of("id", "created_at", "subject_count"),
                    new CreateCommentResp(11L, "2026-11-03 10:00:00", 12L),
                    "{\"id\":11,\"created_at\":\"2026-11-03 10:00:00\",\"subject_count\":12}"),

            new Fixture(DeleteCommentReq.class,
                    List.of("comment_id", "target_type", "target_id"),
                    new DeleteCommentReq(21L, "article", "a1"),
                    "{\"comment_id\":21,\"target_type\":\"article\",\"target_id\":\"a1\"}"),

            new Fixture(DeleteCommentResp.class,
                    List.of("success", "subject_total_count"),
                    new DeleteCommentResp(true, 22L),
                    "{\"success\":true,\"subject_total_count\":22}"),

            new Fixture(GetCommentReq.class,
                    List.of("target_type", "target_id", "sort_type", "root_id", "page", "page_size"),
                    new GetCommentReq("article", "a1", 1L, 0L, 2L, 20L),
                    "{\"target_type\":\"article\",\"target_id\":\"a1\",\"sort_type\":1,\"root_id\":0,"
                            + "\"page\":2,\"page_size\":20}"),

            new Fixture(GetCommentResp.class,
                    List.of("comment", "subject"),
                    new GetCommentResp(List.of(SAMPLE_ITEM), SAMPLE_SUBJECT),
                    "{\"comment\":[{\"id\":1,\"user_id\":2,\"content\":\"c\",\"root_id\":3,"
                            + "\"parent_id\":4,\"like_count\":5,\"dislike_count\":6,\"reply_count\":7,"
                            + "\"attribute\":8,\"state\":9,\"created_at\":\"2026-11-03 10:00:00\","
                            + "\"meta\":\"{}\",\"children\":[]}],"
                            + "\"subject\":{\"target_type\":\"article\",\"target_id\":\"a1\","
                            + "\"total_count\":61,\"root_count\":62,\"state\":1,\"attribute\":63,"
                            + "\"owner_id\":64}}"),

            new Fixture(LikeCommentReq.class,
                    List.of("target_type", "target_id", "comment_id", "action_type"),
                    new LikeCommentReq("article", "a1", 31L, 1),
                    "{\"target_type\":\"article\",\"target_id\":\"a1\",\"comment_id\":31,\"action_type\":1}"),

            new Fixture(LikeCommentResp.class,
                    List.of("success", "like_count"),
                    new LikeCommentResp(true, 32L),
                    "{\"success\":true,\"like_count\":32}"),

            new Fixture(ManageCommentReq.class,
                    List.of("comment_id", "target_type", "target_id", "action_type"),
                    new ManageCommentReq(41L, "article", "a1", 2),
                    "{\"comment_id\":41,\"target_type\":\"article\",\"target_id\":\"a1\",\"action_type\":2}"),

            new Fixture(ManageCommentResp.class,
                    List.of("success"),
                    new ManageCommentResp(true),
                    "{\"success\":true}"),

            new Fixture(ReportCommentReq.class,
                    List.of("comment_id", "target_type", "target_id", "reason", "detail"),
                    new ReportCommentReq(51L, "article", "a1", 3, "detail"),
                    "{\"comment_id\":51,\"target_type\":\"article\",\"target_id\":\"a1\","
                            + "\"reason\":3,\"detail\":\"detail\"}"),

            new Fixture(ReportCommentResp.class,
                    List.of("success"),
                    new ReportCommentResp(true),
                    "{\"success\":true}"),

            new Fixture(SubjectInfo.class,
                    List.of("target_type", "target_id", "total_count", "root_count", "state",
                            "attribute", "owner_id"),
                    SAMPLE_SUBJECT,
                    "{\"target_type\":\"article\",\"target_id\":\"a1\",\"total_count\":61,"
                            + "\"root_count\":62,\"state\":1,\"attribute\":63,\"owner_id\":64}"),

            new Fixture(UpdateSubjectStateReq.class,
                    List.of("target_type", "target_id", "state"),
                    new UpdateSubjectStateReq("article", "a1", 2),
                    "{\"target_type\":\"article\",\"target_id\":\"a1\",\"state\":2}"),

            new Fixture(UpdateSubjectStateResp.class,
                    List.of("success"),
                    new UpdateSubjectStateResp(true),
                    "{\"success\":true}"));

    // ==================================================================
    // 1. 字段名（运行期真实元数据）
    // ==================================================================

    @Test
    @DisplayName("🔴 16 个 DTO 的 @JsonProperty 名与顺序必须与 B 侧逐条一致（63 个字段）")
    void everyDtoDeclaresTheSameJsonPropertyNamesInTheSameOrder() {
        assertThat(FIXTURES)
                .as("必须覆盖 B 侧 api/dto 下的全部 16 个 DTO")
                .hasSize(16);

        int totalFields = 0;
        for (Fixture f : FIXTURES) {
            RecordComponent[] components = f.type().getRecordComponents();
            assertThat(components)
                    .as("%s 必须是 record（A9）", f.type().getSimpleName())
                    .isNotNull();

            List<String> actual = Arrays.stream(components)
                    .map(c -> {
                        // ⚠️ 必须从**访问器方法**上读，不能从 RecordComponent 上读：
                        //    @JsonProperty 的 @Target 是 {ANNOTATION_TYPE, FIELD, METHOD, PARAMETER}，
                        //    **不含 RECORD_COMPONENT** ⇒ javac 把它传播到「字段 / 访问器方法 / 构造参数」，
                        //    但 RecordComponent 本身不持有它（getAnnotation 返回 null）。
                        //    ⇒ 访问器方法上的那份**正是 Jackson 序列化 record 时实际读的那份**
                        //    ⇒ 断言它 ⇒ 断的就是真实生效点。
                        JsonProperty p = c.getAccessor().getAnnotation(JsonProperty.class);
                        assertThat(p)
                                .as("%s#%s 的访问器 %s() 上必须有 @JsonProperty",
                                        f.type().getSimpleName(), c.getName(), c.getAccessor().getName())
                                .isNotNull();
                        return p.value();
                    })
                    .toList();

            assertThat(actual)
                    .as("%s 的 JSON 字段名与顺序必须与 B 侧逐条一致", f.type().getSimpleName())
                    .isEqualTo(f.jsonKeys());
            totalFields += components.length;
        }
        assertThat(totalFields)
                .as("B 侧 16 个 DTO 合计 63 个字段（逐个 @JsonProperty 统计）")
                .isEqualTo(63);
    }

    // ==================================================================
    // 2. 逐字节序列化
    // ==================================================================

    @Test
    @DisplayName("🔴 16 个 DTO 的序列化结果必须与 B 侧逐字节相同（含字段顺序）")
    void everyDtoSerializesToTheExactBsideByteSequence() throws Exception {
        for (Fixture f : FIXTURES) {
            assertThat(MAPPER.writeValueAsString(f.sample()))
                    .as("%s 的 wire 字节必须与 B 侧逐字节相同（Go 按 struct 声明序输出）",
                            f.type().getSimpleName())
                    .isEqualTo(f.expectedJson());
        }
    }

    // ==================================================================
    // 3. 往返（反序列化后字段值不得漂移）
    // ==================================================================

    @Test
    @DisplayName("🔴 16 个 DTO 必须能按 snake_case 键往返反序列化且值不漂移")
    void everyDtoRoundTripsThroughItsSnakeCaseKeys() throws Exception {
        for (Fixture f : FIXTURES) {
            Object parsed = MAPPER.readValue(f.expectedJson(), f.type());
            assertThat(parsed)
                    .as("%s 的往返必须等值", f.type().getSimpleName())
                    .isEqualTo(f.sample());
            assertThat(MAPPER.writeValueAsString(parsed))
                    .as("%s 往返后字节仍须相同", f.type().getSimpleName())
                    .isEqualTo(f.expectedJson());
        }
    }

    // ==================================================================
    // 4. A9：不可变 record
    // ==================================================================

    @Test
    @DisplayName("🔴 16 个 DTO 都必须是 record 且**没有 setter**（A9）")
    void everyDtoIsAnImmutableRecordWithoutSetters() {
        for (Fixture f : FIXTURES) {
            assertThat(f.type().isRecord())
                    .as("%s 必须是 record", f.type().getSimpleName())
                    .isTrue();
            assertThat(f.type().getMethods())
                    .as("%s 不得有 setter", f.type().getSimpleName())
                    .filteredOn(m -> m.getName().startsWith("set"))
                    .isEmpty();
        }
    }

    // ==================================================================
    // 5. CommentItem.children 的 wire 形状
    // ==================================================================

    /**
     * 🔴 <b>{@code children} 在 wire 上恒为 {@code []}，绝不是 {@code null}</b>。
     *
     * <p>B 出处：B 侧 {@code comment-api/.../dto/CommentItem.java} 的
     * {@code private List<CommentItem> children = new ArrayList<>();}
     * ⇒ 序列化出来恒为 {@code "children":[]}，且 B 侧<b>没有任何代码往里放东西</b>
     * ⇒ 「回复不会出现在 children 里」是<b>既有行为</b>，不是待补的功能。
     *
     * <p>⚠️ C 侧 {@link CommentItem} 的紧凑构造器把 {@code null} 归一成空列表
     * ⇒ 与 B 侧的 {@code new ArrayList<>()} 逐条等价。
     */
    @Test
    @DisplayName("🔴 CommentItem.children 恒为 []（不是 null），且 B 侧同样恒为空")
    void commentItemChildrenIsAlwaysAnEmptyArrayOnTheWire() throws Exception {
        CommentItem withNullChildren = new CommentItem(
                1L, 2L, "c", 3L, 4L, 5L, 6L, 7L, 8L, 9,
                "2026-11-03 10:00:00", "{}", null);

        assertThat(withNullChildren.children()).isEmpty();
        assertThat(MAPPER.writeValueAsString(withNullChildren))
                .as("children 必须是 [] 而不是 null（null 会让客户端拿到 null 而非数组）")
                .contains("\"children\":[]");
        assertThat(MAPPER.writeValueAsString(withNullChildren))
                .doesNotContain("\"children\":null");
    }
}
