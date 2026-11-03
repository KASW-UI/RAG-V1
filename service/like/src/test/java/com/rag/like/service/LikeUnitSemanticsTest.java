package com.rag.like.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.common.communityfact.Event;
import com.rag.like.service.LikeActionScript;
import com.rag.like.service.LikeApiSupport;
import com.rag.like.service.LikeCursorIdGenerator;
import com.rag.like.service.LikeDeliveryValidator;
import com.rag.like.service.LikeMessageDigest;
import com.rag.like.service.GoCompatibleJson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>like 域「纯逻辑协作者」的逐条断言</b>（纯单元，<b>不起 Spring 上下文</b>）。
 *
 * <h2>🔴 这些协作者为什么必须单独钉</h2>
 * <p>它们各自承载一条<b>不能被集成测试顺带覆盖</b>的语义：
 * <table border="1">
 *   <caption>协作者 → 被钉住的不变量</caption>
 *   <tr><th>协作者</th><th>不变量</th></tr>
 *   <tr><td>{@code LikeApiSupport#normalizeTargetIds}</td>
 *       <td>Go 的 {@code null} 元素语义：<b>长度不变</b>、全 null ⇒ 空列表</td></tr>
 *   <tr><td>{@code LikeCursorIdGenerator}</td>
 *       <td>游标布局 {@code (unixMilli<<22)|(nodeId<<12)|seq}；
 *           <b>位移量 22 = 12 + 10</b>（写成 24 ⇒ 时间戳反解全错且几乎发现不了）</td></tr>
 *   <tr><td>{@code LikeActionScript}</td>
 *       <td>Lua 原文与 6 个 KEYS / 4 个 ARGV 的<b>顺序</b></td></tr>
 *   <tr><td>{@code LikeMessageDigest}</td>
 *       <td>消息语义哈希的<b>字段顺序</b>（Go struct 声明顺序）</td></tr>
 *   <tr><td>{@code GoCompatibleJson}</td>
 *       <td>Go {@code encoding/json} 的 HTML 转义表（{@code < > &} ⇒ {@code \u003c \u003e \u0026}）</td></tr>
 *   <tr><td>{@code LikeDeliveryValidator}</td>
 *       <td>4 个事实契约常量 + 严格解码（禁未知字段 / 禁标量强转）</td></tr>
 * </table>
 * ⇒ ⇒ 这些都是「写错了、跑绿、且事后极难发现」的那一类 ⇒ 逐条钉死。
 *
 * <h2>抄写基准（B 侧 · 唯一 oracle）</h2>
 * <ul>
 *   <li>{@code LikeApiSupport} ← B 侧 {@code like-api/.../service/GoStyleTargetIds.java}；</li>
 *   <li>{@code LikeCursorIdGenerator} ← B 侧 {@code like-rpc/.../service/LikeCursorIdGenerator.java}；</li>
 *   <li>{@code LikeActionScript} ← B 侧 {@code like-rpc/.../service/LikeActionScript.java}；</li>
 *   <li>{@code GoCompatibleJson} ← B 侧 {@code like-rpc/.../service/GoCompatibleJson.java}。</li>
 * </ul>
 */
class LikeUnitSemanticsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================================================================
    // 1. target_ids 的 null 元素语义（GAP-4）
    // ==================================================================

    @Test
    @DisplayName("🔴 target_ids 缺席 / 空数组 ⇒ 空列表")
    void targetIdsAbsentOrEmptyBecomesEmptyList() {
        assertThat(LikeApiSupport.normalizeTargetIds(null)).isEmpty();
        assertThat(LikeApiSupport.normalizeTargetIds(List.of())).isEmpty();
    }

    @Test
    @DisplayName("🔴 target_ids 全是 null ⇒ 空列表（Go 侧 slice 保持 nil）")
    void allNullTargetIdsBecomesEmptyList() {
        List<String> allNull = Arrays.asList(null, null);
        assertThat(LikeApiSupport.normalizeTargetIds(allNull))
                .as("Go：value.Set 从未置位 ⇒ slice 保持 nil ⇒ 等价于空列表")
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 target_ids 部分 null ⇒ 该位变空串，**长度不变**（不是过滤！）")
    void partialNullTargetIdsKeepsLength() {
        List<String> mixed = Arrays.asList("x", null, "y");
        List<String> normalized = LikeApiSupport.normalizeTargetIds(mixed);

        assertThat(normalized)
                .as("Go 的 continue 不是过滤：长度必须仍是 3，否则分页的 limit 边界与 offset 全部错位")
                .hasSize(3);
        assertThat(normalized).containsExactly("x", "", "y");
    }

    @Test
    @DisplayName("🔴 normalizeTargetIds 的产物永远不含 null 元素（protobuf addAll 的硬要求）")
    void normalizedTargetIdsNeverContainNull() {
        List<String> normalized = LikeApiSupport.normalizeTargetIds(Arrays.asList(null, "a", null));
        assertThat(normalized).doesNotContainNull();
    }

    // ==================================================================
    // 2. 游标 ID 生成器
    // ==================================================================

    @Test
    @DisplayName("🔴 游标反解出来的时间戳必须落在生成时刻附近（位移量 22 不是 24）")
    void cursorTimestampRoundTrip() {
        long before = System.currentTimeMillis();
        long id = LikeCursorIdGenerator.nextId();
        long after = System.currentTimeMillis();

        long decoded = LikeCursorIdGenerator.timestampOf(id);
        assertThat(decoded)
                .as("位移量必须是 22（= 12 + 10）：写成 24 ⇒ 反解出的时间戳会差出好几天，且不会报错")
                .isBetween(before, after);
    }

    @Test
    @DisplayName("🔴 scoreOf / timestampOf 互为逆运算（DB 回源与读路径共用同一常量）")
    void scoreRoundTrip() {
        long millis = 1_762_000_000_000L;
        assertThat(LikeCursorIdGenerator.timestampOf(LikeCursorIdGenerator.scoreOf(millis)))
                .isEqualTo(millis);
    }

    @Test
    @DisplayName("🔴 同一毫秒内生成的多个游标必须互不相同（sequence 递增）")
    void cursorIdsAreUniqueWithinSameMillisecond() {
        long first = LikeCursorIdGenerator.nextId();
        long second = LikeCursorIdGenerator.nextId();
        assertThat(second).isNotEqualTo(first);
        assertThat(second).isGreaterThan(first);
    }

    @Test
    @DisplayName("🔴 游标 ID 落在 long 的正半区（22 位时间戳左移后不得溢出成负数）")
    void cursorIdStaysPositive() {
        long id = LikeCursorIdGenerator.nextId();
        assertThat(id).isPositive();
    }

    // ==================================================================
    // 3. Redis Lua 脚本
    // ==================================================================

    @Test
    @DisplayName("🔴 Lua 的 ARGV 顺序必须是 target_id / user_id / action_type / score")
    void actionScriptArgOrder() {
        String[] args = LikeActionScript.args("t-1", 42L, 1, 123456L);
        assertThat(args).containsExactly("t-1", "42", "1", "123456");
    }

    @Test
    @DisplayName("🔴 Lua 脚本原文保留 3 个返回码（0 幂等 / 1 变更 / 2 首次点赞）")
    void actionScriptReturnCodes() {
        assertThat(LikeActionScript.RESULT_IDEMPOTENT).isEqualTo(0L);
        assertThat(LikeActionScript.RESULT_CHANGED).isEqualTo(1L);
        assertThat(LikeActionScript.RESULT_FIRST_LIKE).isEqualTo(2L);
        assertThat(LikeActionScript.LUA)
                .as("脚本原文逐字照抄 B 侧：KEYS[1..6] 与三个返回码都在")
                .contains("KEYS[1]", "KEYS[2]", "KEYS[3]", "KEYS[4]", "KEYS[5]", "KEYS[6]")
                .contains("return 0", "return 1", "return 2");
    }

    // ==================================================================
    // 4. 消息语义哈希
    // ==================================================================

    @Test
    @DisplayName("🔴 消息哈希是 64 位小写十六进制，且同输入同输出")
    void messageDigestIsStable() {
        LikeMessageDigest digest = new LikeMessageDigest(MAPPER);
        String a = digest.hash(42L, "article", "a-1", 7L, 1, 1_762_000_000L, true);
        String b = digest.hash(42L, "article", "a-1", 7L, 1, 1_762_000_000L, true);

        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("🔴 消息哈希对任一字段变化都敏感（7 个字段逐个改）")
    void messageDigestIsSensitiveToEveryField() {
        LikeMessageDigest digest = new LikeMessageDigest(MAPPER);
        String base = digest.hash(42L, "article", "a-1", 7L, 1, 1_762_000_000L, true);

        assertThat(digest.hash(43L, "article", "a-1", 7L, 1, 1_762_000_000L, true)).isNotEqualTo(base);
        assertThat(digest.hash(42L, "video", "a-1", 7L, 1, 1_762_000_000L, true)).isNotEqualTo(base);
        assertThat(digest.hash(42L, "article", "a-2", 7L, 1, 1_762_000_000L, true)).isNotEqualTo(base);
        assertThat(digest.hash(42L, "article", "a-1", 8L, 1, 1_762_000_000L, true)).isNotEqualTo(base);
        assertThat(digest.hash(42L, "article", "a-1", 7L, 2, 1_762_000_000L, true)).isNotEqualTo(base);
        assertThat(digest.hash(42L, "article", "a-1", 7L, 1, 1_762_000_001L, true)).isNotEqualTo(base);
        assertThat(digest.hash(42L, "article", "a-1", 7L, 1, 1_762_000_000L, false)).isNotEqualTo(base);
    }

    // ==================================================================
    // 5. Go 兼容 JSON
    // ==================================================================

    @Test
    @DisplayName("🔴 Go 的 HTML 转义表必须生效（< > & ⇒ \\u003c \\u003e \\u0026）")
    void goCompatibleJsonEscapesHtml() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("k", "<a>&</a>");

        assertThat(GoCompatibleJson.write(node))
                .as("Go 的 encoding/json 默认转义 < > & —— 两边的字节必须一致，否则哈希会漂")
                .isEqualTo("{\"k\":\"\\u003ca\\u003e\\u0026\\u003c/a\\u003e\"}");
    }

    @Test
    @DisplayName("🔴 Sha256 输出 64 位小写十六进制")
    void sha256IsLowercaseHex() {
        assertThat(GoCompatibleJson.Sha256.hex(""))
                .as("空串的 SHA-256 是常量 e3b0c442…")
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(GoCompatibleJson.Sha256.hex("abc")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("🔴 rfc3339Nano 输出 Go 的 RFC3339Nano 形态（纳秒精度 + Z）")
    void rfc3339NanoShape() {
        String out = GoCompatibleJson.rfc3339Nano(Instant.ofEpochSecond(1_762_000_000L, 123_456_789L));
        assertThat(out).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+Z");
    }

    // ==================================================================
    // 6. 事实投递校验器
    // ==================================================================

    @Test
    @DisplayName("🔴 4 个事实契约常量逐字正确（B 侧 LikeEventContracts 的字面量）")
    void factContractConstants() {
        assertThat(LikeDeliveryValidator.FACT_PRODUCER).isEqualTo("rtw.like-mq");
        assertThat(LikeDeliveryValidator.FACT_SCHEMA_VERSION).isEqualTo("rtw.community-fact.v1");
        assertThat(LikeDeliveryValidator.FACT_EVENT_TYPE).isEqualTo("community.target.interaction");
        assertThat(LikeDeliveryValidator.FACT_EVENT_ID_PREFIX).isEqualTo("rtw.like.");
    }

    @Test
    @DisplayName("🔴 信封严格解码：未知字段必须被拒（Go 的 StrictDecode）")
    void strictEnvelopeRejectsUnknownField() {
        String raw = """
                {"event_id":"rtw.like.1","event_type":"community.target.interaction",
                 "schema_version":1,"producer":"rtw.like-mq","aggregate_id":"agg-1",
                 "aggregate_version":3,"operation_id":"op-1",
                 "occurred_at":"2026-11-03T10:00:00Z","payload":{},"unknown_field":"x"}
                """;
        LikeDeliveryValidator validator = new LikeDeliveryValidator(MAPPER);

        assertThatThrownBy(() -> validator.decodeEnvelope(raw))
                .as("Go 的 StrictDecode 禁止未知字段 ⇒ 放过去就等于放过伪造载荷")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("🔴 信封严格解码：标量强转必须被拒（\"schema_version\":\"1\" 不等于 1）")
    void strictEnvelopeRejectsScalarCoercion() {
        String raw = """
                {"event_id":"rtw.like.1","event_type":"community.target.interaction",
                 "schema_version":"1","producer":"rtw.like-mq","aggregate_id":"agg-1",
                 "aggregate_version":3,"operation_id":"op-1",
                 "occurred_at":"2026-11-03T10:00:00Z","payload":{}}
                """;
        LikeDeliveryValidator validator = new LikeDeliveryValidator(MAPPER);

        assertThatThrownBy(() -> validator.decodeEnvelope(raw))
                .as("Go 的 encoding/json 对字符串→整数报错 ⇒ 放过去等于接受伪造信封")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("🔴 合法信封可解码，载荷 19 个字段齐全")
    void validEnvelopeDecodes() {
        String raw = """
                {"event_id":"rtw.like.1","event_type":"community.target.interaction",
                 "schema_version":1,"producer":"rtw.like-mq","aggregate_id":"agg-1",
                 "aggregate_version":3,"operation_id":"op-1",
                 "occurred_at":"2026-11-03T10:00:00.123456789Z",
                 "payload":{"schema_version":"rtw.community-fact.v1","event_id":"rtw.like.1",
                   "event_type":"community.target.interaction","producer":"rtw.like-mq",
                   "aggregate_id":"agg-1","aggregate_version":null,"operation_id":"op-1",
                   "subject_ref":"user:42","target_type":"article","target_id":"a-1",
                   "target_revision":null,"revision_status":null,"operation":"like",
                   "source_ref":"like_record","old_state":0,"new_state":1,
                   "event_time":"2026-11-03T10:00:00.123456789Z",
                   "occurred_at":"2026-11-03T10:00:00.123456789Z",
                   "available_at":"2026-11-03T10:00:00.123456789Z"}}
                """;
        LikeDeliveryValidator validator = new LikeDeliveryValidator(MAPPER);

        Event event = validator.decodeEnvelope(raw);
        assertThat(event.eventId).isEqualTo("rtw.like.1");
        assertThat(event.eventType).isEqualTo("community.target.interaction");
        assertThat(event.schemaVersion).isEqualTo(1);
        assertThat(event.producer).isEqualTo("rtw.like-mq");
        assertThat(event.aggregateId).isEqualTo("agg-1");
        assertThat(event.aggregateVersion).isEqualTo(3L);
        assertThat(event.operationId).isEqualTo("op-1");

        LikeDeliveryValidator.LikeFact fact = validator.decodePayload(event);
        assertThat(fact).isNotNull();
        assertThat(fact.schemaVersion).isEqualTo("rtw.community-fact.v1");
        assertThat(fact.subjectRef).isEqualTo("user:42");
        assertThat(fact.targetType).isEqualTo("article");
        assertThat(fact.targetId).isEqualTo("a-1");
        assertThat(fact.operation).isEqualTo("like");
        assertThat(fact.oldState).isZero();
        assertThat(fact.newState).isEqualTo(1);
        assertThat(fact.aggregateVersion)
                .as("Go 是 *int64 且从不赋值 ⇒ 载荷里必须恒为 null（版本号只存在于信封）")
                .isNull();
    }

    @Test
    @DisplayName("🔴 空信封必须被拒（不是返回 null）")
    void emptyEnvelopeRejected() {
        LikeDeliveryValidator validator = new LikeDeliveryValidator(MAPPER);
        assertThatThrownBy(() -> validator.decodeEnvelope(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.decodeEnvelope(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
