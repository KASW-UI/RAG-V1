package com.rag.comment.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事实载荷与冻结信封的 JSON 契约（C 侧移植） —— 对应 Go
 * {@code service/comment/rpc/internal/model/domain_fact.go:43-80}。
 *
 * <p><b>这是本域最容易被「顺手优化」破坏的地方</b>：
 * {@code aggregate_version} 与 {@code target_revision} <b>没有</b> omitempty（不填时输出 null），
 * 而 {@code operator_ref} / {@code parent_comment_id} / {@code old_state} / {@code new_state}
 * <b>有</b> omitempty（不填时字段整个消失）。两组规则相反，
 * 而 {@code validCommentDelivery}（fact_delivery.go:36）要断言
 * {@code payload.aggregate_version == null} —— 把 omitempty 加上去会让「版本号缺失」
 * 与「版本号为空」变成同一种输入，伪造者就能用一个不带版本号的载荷通过校验。
 *
 * <h2>1. 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>落点</b>：B 侧在 {@code com.rag.comment.comment.rpc.service}
 *       ⇒ C 侧按 D7（包压平 + 领域服务归 {@code domain/service/}）落在
 *       {@code com.rag.comment.domain.service}。</li>
 *   <li><b>序列化器</b>：用<b>普通 {@code new ObjectMapper()}</b>（Jackson 2 · 2.19.4）
 *       —— 与 B 侧同款（{@code CommentFactPayloadContractTest.java:24}）。
 *       ⚠️ <b>刻意不用</b>容器里注入了 {@code JavaTimeModule} 的那个 bean：
 *       本类断言的是「载荷 JSON 的字段集合与键序」，
 *       用容器 bean 会把 {@code JavaTimeModule} 的行为混进被测面。</li>
 *   <li><b>断言库</b>：从 B 侧的 AssertJ 保持为 AssertJ（<b>逐条未改</b>）。</li>
 *   <li><b>时间字段形态</b>：C 侧 {@code event_time} / {@code occurred_at} / {@code available_at}
 *       是 {@code String}（B 侧同款）⇒ 赋值 {@code "2026-10-23T10:00:00Z"} 即可，
 *       序列化出来就是<b>文本</b>（Go {@code time.Time} 的 JSON 形态）。</li>
 * </ul>
 *
 * <h2>2. 语义还一样吗</h2>
 * <p>一样。B 侧 6 条用例（无 omitempty ⇒ null / 有 omitempty ⇒ 消失 / {@code old_state=0} /
 * 固定常量 / 字段顺序 / {@code subjectRef} 守卫）<b>逐条保留</b>，
 * 判据与期望值<b>逐字未改</b>。
 * <p>⚠️ <b>字段顺序这条在 C 侧是「声明序」而不是「字母序」</b>：
 * Go 的 {@code encoding/json} 按<b>结构体声明顺序</b>输出 ⇒ C 侧用
 * {@code @JsonPropertyOrder} 把 24 个字段钉成同一个顺序
 * （{@code domain/service/CommentDomainFactService.java:319-325}）。
 * <b>不要</b>把断言改成字母序 —— B 侧 Java（Jackson 3 默认）跑出来是字母序，
 * 那是<b>它自己的默认值泄漏</b>，不是 Go 的形态（已登记为台账 D-14）。
 *
 * <h2>3. B 出处（逐条读原文取证）</h2>
 * <ul>
 *   <li><b>oracle 测试类</b>：{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/service/CommentFactPayloadContractTest.java}
 *       （B 侧 150 行 · 本类的逐条 oracle）</li>
 *   <li><b>oracle 实现类</b>：{@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentDomainFactService.java}
 *       —— 常量 {@code :49} / {@code :52} / {@code :55} / {@code :58}；
 *       {@code @JsonPropertyOrder} {@code :278}；{@code class CommentFact} {@code :285}；
 *       {@code operator_ref}（NON_EMPTY）{@code :312-314}；{@code parent_comment_id}（NON_EMPTY）{@code :338-340}；
 *       {@code old_state} / {@code new_state}（NON_NULL）{@code :342-348}；
 *       {@code subjectRef} 守卫 {@code :82-87}</li>
 *   <li><b>C 侧对应消费点</b>：{@code service/comment/src/main/java/com/rag/comment/domain/service/CommentDomainFactService.java}
 *       —— 常量 {@code :76} / {@code :79} / {@code :82} / {@code :85}；
 *       {@code @JsonPropertyOrder} {@code :319-325}；{@code class CommentFact} {@code :326}；
 *       {@code operatorRef} {@code :353-355}；{@code parentCommentId} {@code :379-381}；
 *       {@code oldState} / {@code newState} {@code :383-389}；{@code subjectRef} {@code :111-116}</li>
 * </ul>
 */
class CommentFactPayloadContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Go {@code domain_fact.go:99} 的 schema 版本。 */
    private static final String EXPECTED_SCHEMA_VERSION = "rtw.community-fact.v1";
    /** Go {@code domain_fact.go:100} 的 producer。 */
    private static final String EXPECTED_PRODUCER = "rtw.comment-rpc";
    /** Go {@code domain_fact.go:101} 的 revision_status。 */
    private static final String EXPECTED_REVISION_STATUS = "unknown";
    /** 固定时间戳（B 侧同款：{@code CommentFactPayloadContractTest.java:44}）。 */
    private static final String FIXED_TIME = "2026-10-23T10:00:00Z";

    private static CommentDomainFactService.CommentFact createdFact() {
        CommentDomainFactService.CommentFact f = new CommentDomainFactService.CommentFact();
        f.eventId = "rtw.comment.123.created";
        f.eventType = "community.comment.created";
        f.producer = CommentDomainFactService.PRODUCER;
        f.aggregateId = "123";
        f.operationId = "rtw.comment.123.created";
        f.subjectRef = "rtw.identity/platform/7";
        f.targetType = "article";
        f.targetId = "a1";
        f.revisionStatus = CommentDomainFactService.REVISION_STATUS_UNKNOWN;
        f.operation = "create";
        f.sourceRef = "rtw.comment/123";
        f.commentId = "123";
        f.visibilityState = 0;
        f.searchEvidence = CommentDomainFactService.SEARCH_EVIDENCE;
        f.schemaVersion = CommentDomainFactService.SCHEMA_VERSION;
        // Go 的 time.Time ⇒ JSON 是 RFC3339Nano 字符串
        f.eventTime = FIXED_TIME;
        f.occurredAt = f.eventTime;
        f.availableAt = f.eventTime;
        return f;
    }

    private static ObjectNode serialize(CommentDomainFactService.CommentFact fact) throws Exception {
        return (ObjectNode) MAPPER.readTree(MAPPER.writeValueAsString(fact));
    }

    private static List<String> fieldNames(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ====================================================================================
    // ① 无 omitempty 的字段未填时输出 null（不是字段消失）
    // ====================================================================================

    @Test
    @DisplayName("🔴 无 omitempty 的字段在未填时必须输出 null（Go 里是 nil 指针）"
            + "（C 侧消费点 domain/service/CommentDomainFactService.java:344-345 + :364-365 · B 侧 …:303-304 + :323-324）")
    void nullableFieldsAreEmittedAsNull() throws Exception {
        ObjectNode node = serialize(createdFact());
        // aggregate_version 未填 ⇒ 输出 null，而不是字段消失
        assertThat(node.has("aggregate_version")).isTrue();
        assertThat(node.get("aggregate_version").isNull()).isTrue();
        // target_revision 同理
        assertThat(node.has("target_revision")).isTrue();
        assertThat(node.get("target_revision").isNull()).isTrue();
    }

    // ====================================================================================
    // ② 有 omitempty 的字段未填时整个字段消失
    // ====================================================================================

    @Test
    @DisplayName("🔴 有 omitempty 的字段在未填时必须整个消失"
            + "（C 侧消费点 domain/service/CommentDomainFactService.java:353-355 + :379-381 + :383-389 · B 侧 …:312-314 + :338-348）")
    void omitemptyFieldsDisappear() throws Exception {
        ObjectNode node = serialize(createdFact());
        // 根评论：没有 parent_comment_id / old_state / new_state / operator_ref
        assertThat(node.has("parent_comment_id")).isFalse();
        assertThat(node.has("old_state")).isFalse();
        assertThat(node.has("new_state")).isFalse();
        assertThat(node.has("operator_ref")).isFalse();
    }

    // ====================================================================================
    // ③ old_state=0 必须被输出（0 是有效值，不是缺失）
    // ====================================================================================

    @Test
    @DisplayName("⚠️ old_state=0 必须被输出（它是 omitempty 字段但 0 是有效值）"
            + "（C 侧消费点 domain/service/CommentDomainFactService.java:383-385 + :387-389 · B 侧 …:342-348）")
    void zeroOldStateIsARealValueNotAbsence() throws Exception {
        CommentDomainFactService.CommentFact f = createdFact();
        f.operation = "like";
        f.oldState = 0;
        f.newState = 1;
        ObjectNode node = serialize(f);
        // Go: `old_state *int32 json:"old_state,omitempty"` —— 非 nil 指针即输出（含 0）
        assertThat(node.has("old_state")).isTrue();
        assertThat(node.get("old_state").asInt()).isZero();
        assertThat(node.get("new_state").asInt()).isEqualTo(1);
    }

    // ====================================================================================
    // ④ 固定常量 + 时间形态
    // ====================================================================================

    @Test
    @DisplayName("schema_version / producer / revision_status / search_evidence 取 Go 的固定值，event_time 是文本"
            + "（C 侧消费点 domain/service/CommentDomainFactService.java:76 + :79 + :82 + :85 + :405-406 · B 侧 …:49-58 + :363-364）")
    void fixedConstantsMatchGo() throws Exception {
        ObjectNode node = serialize(createdFact());
        assertThat(node.get("schema_version").asText()).isEqualTo(EXPECTED_SCHEMA_VERSION);
        assertThat(node.get("producer").asText()).isEqualTo(EXPECTED_PRODUCER);
        assertThat(node.get("revision_status").asText()).isEqualTo(EXPECTED_REVISION_STATUS);
        assertThat(node.get("search_evidence").asBoolean()).isFalse();
        // ⚠️ 时间必须是 RFC3339 字符串（Go time.Time 的 JSON 形态），不是数字/数组
        assertThat(node.get("event_time").isTextual()).isTrue();
        assertThat(node.get("event_time").asText()).isEqualTo(FIXED_TIME);
    }

    // ====================================================================================
    // ⑤ 字段顺序 == Go 结构体声明顺序
    // ====================================================================================

    @Test
    @DisplayName("字段顺序与 Go 结构体声明顺序一致（created 20 个字段 / deleted 22 个字段）"
            + "（C 侧消费点 domain/service/CommentDomainFactService.java:319-325 · B 侧 …:278-284）")
    void fieldOrderMatchesGoDeclarationOrder() throws Exception {
        // created 事实没有 operator_ref（omitempty），因此它不出现在输出里
        assertThat(fieldNames(serialize(createdFact()))).containsExactly(
                "schema_version", "event_id", "event_type", "producer", "aggregate_id",
                "aggregate_version", "operation_id", "subject_ref",
                "target_type", "target_id", "target_revision", "revision_status", "operation",
                "source_ref", "comment_id",
                "visibility_state", "search_evidence", "event_time", "occurred_at", "available_at");

        // deleted 事实带 operator_ref：它必须落在 subject_ref 之后、target_type 之前
        CommentDomainFactService.CommentFact deleted = createdFact();
        deleted.operation = "retract";
        deleted.operatorRef = "rtw.identity/platform/9";
        deleted.parentCommentId = "11";
        assertThat(fieldNames(serialize(deleted))).containsExactly(
                "schema_version", "event_id", "event_type", "producer", "aggregate_id",
                "aggregate_version", "operation_id", "subject_ref", "operator_ref",
                "target_type", "target_id", "target_revision", "revision_status", "operation",
                "source_ref", "comment_id", "parent_comment_id",
                "visibility_state", "search_evidence", "event_time", "occurred_at", "available_at");
    }

    // ====================================================================================
    // ⑥ subjectRef 格式与守卫
    // ====================================================================================

    @Test
    @DisplayName("subject_ref 必须是 rtw.identity/platform/{uid}，uid ≤ 0 直接失败"
            + "（C 侧消费点 domain/service/CommentDomainFactService.java:111-116 · B 侧 …:82-87）")
    void subjectRefFormatAndGuard() {
        assertThat(CommentDomainFactService.subjectRef(7L)).isEqualTo("rtw.identity/platform/7");
        assertThatThrownBy(() -> CommentDomainFactService.subjectRef(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommentDomainFactService.subjectRef(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ====================================================================================
    // ⑦ 互动事实 ID 前缀固定（权威读靠它识别互动事件）
    // ====================================================================================

    @Test
    @DisplayName("互动事实 ID 前缀固定为 rtw.comment.interaction. 且每次生成都不同"
            + "（C 侧消费点 domain/service/CommentDomainFactService.java:123-125 · B 侧 …:90-92）")
    void interactionIdPrefix() {
        String id = CommentDomainFactService.newCommentInteractionId();
        assertThat(id).startsWith("rtw.comment.interaction.");
        assertThat(CommentDomainFactService.newCommentInteractionId()).isNotEqualTo(id);
    }
}
