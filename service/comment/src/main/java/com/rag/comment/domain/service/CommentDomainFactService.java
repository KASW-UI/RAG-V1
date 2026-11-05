package com.rag.comment.domain.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rag.comment.domain.entity.CommentFactStreamEntity;
import com.rag.comment.mapper.CommentDomainFactOutboxMapper;
import com.rag.comment.mapper.CommentFactStreamMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 事实追加 —— 对应 Go 端 {@code service/comment/rpc/internal/model/domain_fact.go:55-146} 的
 * {@code commentFact} + {@code appendCommentFact} + {@code subjectRef} + {@code newCommentInteractionID}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentDomainFactService.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.comment.comment.rpc.service} 移到 {@code com.rag.comment.domain.service}
 *           （D7：领域服务归 {@code domain/service/}）；</li>
 *       <li>实体/Mapper 的 import 由 {@code com.rag.comment.comment.rpc.domain} /
 *           {@code ...rpc.mapper} 改为 {@code com.rag.comment.domain.entity} /
 *           {@code com.rag.comment.mapper}（D7）；</li>
 *       <li>其余（4 个常量、{@code subjectRef}、{@code newCommentInteractionId}、
 *           {@code append}、{@code normalizeEventTimeToUtc}、{@link CommentFact} 24 个字段、
 *           {@link DateTimes}）<b>逐字未改</b>。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。6 步执行顺序、默认值填充、legacy 守卫、
 *       原子取号与范围校验、信封九字段、载荷 24 字段的顺序与 omitempty 逐字未改。</li>
 *   <li><b>B 出处</b>：见上；逐方法行号见各方法 javadoc。</li>
 * </ol>
 *
 * <h2>⚠️ 本类必须在<b>业务事务内</b>被调用</h2>
 * <p>Go 的三个事务方法（{@code InsertCommentTx} / {@code DeleteCommentTx} / {@code LikeCommentTx}）
 * 都把 {@code appendCommentFact(tx, fact)} 作为<b>最后一步</b>放在同一个 {@code Transaction(...)} 闭包里。
 * 这不是风格选择，而是 outbox 模式的全部意义：
 * 「业务数据变了」与「对应的下游事实已经冻结并分配了版本号」要么都成功，要么都不发生。
 * 若本类被移到事务外调用，会出现「事实已发但业务回滚」或反之，
 * DC 侧就会看到一条<b>与源库状态不符</b>的事实流。
 *
 * <h2>⚠️ legacy 守卫会<b>让整个业务事务失败</b>，这是有意的</h2>
 * <p>Go domain_fact.go:103-114 的注释：
 * <pre>
 * // A legacy row on this aggregate has no trusted fact-stream version. A
 * // migration/reconciliation must resolve it before any newer fact can be
 * // emitted; otherwise DC would see an apparently complete but false stream.
 * </pre>
 * ⇒ 检测到未版本化的历史事实时<b>直接返回错误</b>，而不是补号或跳过。
 * 「跳过」会让 DC 收到一条看起来完整、实则缺环的事实流 —— 那比失败危险得多。
 *
 * <h2>⚠️ 本域存在<b>两个时间基准</b>，不要统一</h2>
 * <p>① <b>fact 时间戳无条件归一化到 UTC</b>（{@link #normalizeEventTimeToUtc}，Go {@code domain_fact.go:95-96}）；
 * ② <b>wire 时间 formatter 用本地时区</b>（B 侧 {@code CommentDomainService.java:78-79} 的 {@code WIRE_TIME}）。
 * 两者<b>并存</b>是 B 侧的既定形状 ⇒ 本类只负责 ①，不碰 ②。
 */
@Service
public class CommentDomainFactService {

    private static final Logger LOG = LoggerFactory.getLogger(CommentDomainFactService.class);

    /** 对应 Go {@code domain_fact.go:99} {@code fact.SchemaVersion = "rtw.community-fact.v1"}。 */
    public static final String SCHEMA_VERSION = "rtw.community-fact.v1";

    /** 对应 Go {@code domain_fact.go:100} {@code fact.Producer = "rtw.comment-rpc"}（也是 fact_delivery.go:18 的常量）。 */
    public static final String PRODUCER = "rtw.comment-rpc";

    /** 对应 Go {@code domain_fact.go:101} {@code fact.RevisionStatus = "unknown"}。 */
    public static final String REVISION_STATUS_UNKNOWN = "unknown";

    /** 对应 Go {@code domain_fact.go:102} {@code fact.SearchEvidence = false}。 */
    public static final boolean SEARCH_EVIDENCE = false;

    /** Go {@code domain_fact.go:121} 的上界：JSON 安全整数 2^53-1。 */
    private static final long MAX_DC_VERSION = 9007199254740991L;

    private final CommentDomainFactOutboxMapper outboxMapper;
    private final CommentFactStreamMapper factStreamMapper;
    private final ObjectMapper objectMapper;

    public CommentDomainFactService(CommentDomainFactOutboxMapper outboxMapper,
                                    CommentFactStreamMapper factStreamMapper,
                                    ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.factStreamMapper = factStreamMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 对应 Go {@code subjectRef(uid)}（domain_fact.go:82-87）：
     * {@code "rtw.identity/platform/" + strconv.FormatInt(uid, 10)}。
     *
     * <p>⚠️ {@code uid <= 0} 是<b>错误</b>，不是「返回空串」——
     * 它会让调用方的整个事务失败（InsertCommentTx 第一步就是它，comment.go:29-31）。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDomainFactService.java:82-87}。
     */
    public static String subjectRef(long uid) {
        if (uid <= 0) {
            throw new IllegalArgumentException("invalid comment subject uid: " + uid);
        }
        return "rtw.identity/platform/" + uid;
    }

    /**
     * 对应 Go {@code newCommentInteractionID()}：{@code "rtw.comment.interaction." + uuid.NewString()}。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDomainFactService.java:90-92}。
     */
    public static String newCommentInteractionId() {
        return "rtw.comment.interaction." + UUID.randomUUID();
    }

    /**
     * 对应 Go {@code appendCommentFact(tx, fact)}（domain_fact.go:89-142），逐段照抄。
     *
     * <p>执行顺序（顺序本身是语义）：
     * <ol>
     *   <li>补全默认值（EventTime / OccurredAt / AvailableAt / SchemaVersion / Producer /
     *       RevisionStatus / SearchEvidence）—— Go 在 :93-102 一段做完；</li>
     *   <li><b>legacy 守卫</b>（:107-114）：本 aggregate 若存在无信封的遗留事实 ⇒ 抛错；</li>
     *   <li><b>原子取号</b>（:116-120）：INSERT … ON CONFLICT … RETURNING；</li>
     *   <li>范围校验（:121-123）：版本号必须在 [1, 2^53-1]；</li>
     *   <li>序列化 payload（:124）与信封（:128-133）；</li>
     *   <li>追加 outbox 行（:137-141）。</li>
     * </ol>
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDomainFactService.java:111-169}。
     *
     * @param fact 已填好事件身份与业务字段的事实；本方法补默认值并冻结
     * @return 分配到的 DC 版本号（调用方一般不需要，但测试要断言）
     */
    public long append(CommentFact fact) {
        // --- ① 必填校验（Go :90-92）
        if (isBlank(fact.eventId) || isBlank(fact.targetType) || isBlank(fact.targetId)
                || isBlank(fact.subjectRef)) {
            throw new IllegalArgumentException("incomplete comment fact");
        }
        // --- ② 默认值（Go :93-102）
        // Go 用 time.Now().UTC()；Java 显式转 UTC，避免落到 JVM 默认时区上。
        //
        // ⚠️ Go 在 :95-96 是**两步**：
        //     if fact.EventTime.IsZero() { fact.EventTime = time.Now().UTC() }
        //     fact.EventTime = fact.EventTime.UTC()          ← ← ← **无条件归一化到 UTC**
        // RAG 此前只做了第一步（null ⇒ now），**漏了第二步** ⇒ 调用方传 +08:00 的
        // eventTime 时，Go 的 payload/envelope 是 UTC（"…T10:00:00Z"），RAG 是原样的
        // "…T18:00:00+08:00" ⇒ **信封字节漂移**。
        // ⇒ 见 docs/DRIFT/comment/rpc.md 的 D3。
        fact.eventTime = normalizeEventTimeToUtc(fact.eventTime);
        fact.occurredAt = fact.eventTime;
        fact.availableAt = DateTimes.toRfc3339Nano(Instant.now());
        fact.schemaVersion = SCHEMA_VERSION;
        fact.producer = PRODUCER;
        fact.revisionStatus = REVISION_STATUS_UNKNOWN;
        fact.searchEvidence = SEARCH_EVIDENCE;

        // --- ③ legacy 守卫（Go :107-114）
        long legacy = outboxMapper.countLegacyUnversioned(fact.aggregateId);
        if (legacy > 0) {
            throw new IllegalStateException(
                    "comment aggregate " + fact.aggregateId + " has unversioned legacy facts");
        }

        // --- ④ 原子取号（Go :116-120）
        CommentFactStreamEntity stream = factStreamMapper.bumpVersion(fact.aggregateId);
        if (stream == null) {
            throw new IllegalStateException("comment fact version allocation returned no row");
        }
        long version = stream.getVersion();
        if (version < 1 || version > MAX_DC_VERSION) {
            throw new IllegalStateException("comment fact version out of DC range");
        }

        // --- ⑤ 序列化（Go :124-133）
        String payloadJson;
        String envelopeJson;
        try {
            payloadJson = objectMapper.writeValueAsString(fact);
            ObjectNodeHolder envelope = buildEnvelope(fact, version, payloadJson);
            envelopeJson = objectMapper.writeValueAsString(envelope.node);
        } catch (Exception e) {
            throw new IllegalStateException("marshal comment fact failed: " + e.getMessage(), e);
        }

        // --- ⑥ 追加 outbox 行（Go :137-141）
        outboxMapper.appendFact(fact.eventId, fact.eventType, payloadJson,
                fact.aggregateId, version, envelopeJson);
        LOG.debug("comment fact appended: event_id={} aggregate={} version={}",
                fact.eventId, fact.aggregateId, version);
        return version;
    }

    /**
     * 对应 Go {@code domain_fact.go:95-96} 的两步：
     * <pre>
     *   if fact.EventTime.IsZero() { fact.EventTime = time.Now().UTC() }
     *   fact.EventTime = fact.EventTime.UTC()
     * </pre>
     *
     * <p>⚠️ Go 的 {@code EventTime} 是 {@code time.Time}（结构体）⇒ <b>不可能非法</b>；
     * RAG 侧用 {@code String} 承载 ⇒ 可能拿到各种形态。
     *
     * <p><b>本方法的映射规则</b>：
     * <table border="1">
     *   <caption>String ⇄ time.Time 的对应</caption>
     *   <tr><th>入参</th><th>Go 的对应</th><th>本方法</th></tr>
     *   <tr><td>{@code null} / 空白</td><td>{@code IsZero()} ⇒ now</td><td>now（UTC）</td></tr>
     *   <tr><td>Go 的零值时刻（0001-01-01T00:00:00Z）</td><td>{@code IsZero()} ⇒ now</td><td>now（UTC）</td></tr>
     *   <tr><td>带偏移的 RFC3339（{@code +08:00}）</td><td>{@code .UTC()} ⇒ 转成 UTC</td><td>转成 UTC（{@code Z}）</td></tr>
     *   <tr><td>已是 UTC（{@code Z}）</td><td>{@code .UTC()} ⇒ 不变</td><td>不变</td></tr>
     *   <tr><td><b>无法解析</b></td><td><b>不可能发生</b></td><td><b>原样保留</b>（见下）</td></tr>
     * </table>
     *
     * <p>⚠️ <b>无法解析时为什么不抛异常</b>：Go 侧根本没有「非法时间」这种状态，
     * 抛异常等于<b>新增一个 Go 没有的失败模式</b>（会回滚事务）。
     * ⇒ 这里原样保留，让下游 {@code CommentDeliveryValidator} 的 RFC3339 校验
     * 把它判成 <b>BLOCKED</b> ⇒ 与 Go 的「拒发」语义一致，
     * 且<b>不改变事务边界</b>。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDomainFactService.java:198-215}。
     */
    static String normalizeEventTimeToUtc(String raw) {
        if (raw == null || raw.isBlank()) {
            return DateTimes.toRfc3339Nano(Instant.now());
        }
        OffsetDateTime parsed;
        try {
            parsed = OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (RuntimeException e) {
            // 非法形态：Go 不可能出现 ⇒ 原样保留，交给投递侧校验 BLOCKED
            return raw;
        }
        Instant instant = parsed.toInstant();
        // Go 的 time.Time.IsZero()：零值时刻（0001-01-01T00:00:00Z）
        if (instant.equals(GO_ZERO_TIME)) {
            return DateTimes.toRfc3339Nano(Instant.now());
        }
        return DateTimes.toRfc3339Nano(instant);
    }

    /** Go {@code time.Time} 的零值时刻（{@code IsZero()} 为 true 的那一个）。 */
    private static final Instant GO_ZERO_TIME = Instant.parse("0001-01-01T00:00:00Z");

    /**
     * 对应 Go {@code commentDeliveryEnvelope}（domain_fact.go:43-53）九字段信封。
     *
     * <p>⚠️ {@code payload} 必须是<b>原始 JSON 对象</b>而不是字符串：
     * Go 的 {@code Payload json.RawMessage} 会把原始字节原样嵌进去。
     * 这里用 {@code readTree(payloadJson)} 得到的 {@code JsonNode} 达成同样效果 ——
     * 差异只可能在空白，而校验侧走的是 JCS 规范化哈希，不受空白影响。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDomainFactService.java:228-240}。
     */
    private ObjectNodeHolder buildEnvelope(CommentFact fact, long version, String payloadJson) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("event_id", fact.eventId);
        node.put("event_type", fact.eventType);
        node.put("schema_version", 1);
        node.put("producer", fact.producer);
        node.put("aggregate_id", fact.aggregateId);
        node.put("aggregate_version", version);
        node.put("operation_id", fact.operationId);
        node.put("occurred_at", fact.occurredAt);
        node.set("payload", objectMapper.readTree(payloadJson));
        return new ObjectNodeHolder(node);
    }

    /** 仅为让 {@link #buildEnvelope} 的返回类型可读。 */
    private record ObjectNodeHolder(ObjectNode node) {
    }

    // ====================================================================================
    // commentFact —— 逐字对应 Go domain_fact.go:55-80
    // ====================================================================================

    /**
     * 评论事实载荷 —— 对应 Go {@code commentFact} 结构体。
     *
     * <p><b>⚠️ 字段顺序由 {@link JsonPropertyOrder} 钉死为 Go 的声明顺序</b>
     * （Go {@code encoding/json} 按结构体声明顺序输出）。
     *
     * <p><b>⚠️ 三组 {@code omitempty} 必须逐字保留</b>：
     * <ul>
     *   <li>{@code operator_ref} / {@code parent_comment_id} / {@code old_state} / {@code new_state}
     *       带 {@code omitempty} ⇒ 不填时<b>整个字段不出现</b>；</li>
     *   <li>{@code aggregate_version} 与 {@code target_revision} <b>没有</b> {@code omitempty}
     *       ⇒ 不填时输出 {@code null}。</li>
     * </ul>
     * 这组差异不是洁癖：{@code validCommentDelivery}（fact_delivery.go:36）要断言
     * {@code payload.AggregateVersion == null}（信封才有版本号，载荷里必须没有），
     * 而 strictDecode 遇到「字段缺失」与「字段为 null」是两种输入。
     *
     * <p>🔴 <b>2026-10-24 移除 {@code @JsonIgnoreProperties(ignoreUnknown = true)}</b>：
     * Go 的 {@code communityfact.StrictDecode} 用的是
     * {@code decoder.DisallowUnknownFields()}（{@code wire.go:121-131}）
     * ⇒ <b>载荷里多一个字段 ⇒ 解码失败 ⇒ 该行 BLOCKED</b>。
     * <br>而本类上的类级 {@code @JsonIgnoreProperties} 会<b>覆盖</b> mapper 级的
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=true} ⇒ 严格性被静默取消
     * ⇒ 被篡改 / 来自更高 schema 版本的载荷在 RAG 侧<b>照发</b>，
     * 而 Go 源站会判定为非法。
     * <br>⚠️ 序列化方向不受影响（{@code ignoreUnknown} 只作用于反序列化），
     * ⇒ 线上字节不变。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDomainFactService.java:285-371}。
     */
    @JsonPropertyOrder({
            "schema_version", "event_id", "event_type", "producer", "aggregate_id", "aggregate_version",
            "operation_id", "subject_ref", "operator_ref", "target_type", "target_id", "target_revision",
            "revision_status", "operation", "source_ref", "comment_id", "parent_comment_id",
            "old_state", "new_state", "visibility_state", "search_evidence",
            "event_time", "occurred_at", "available_at"
    })
    public static class CommentFact {

        @JsonProperty("schema_version")
        public String schemaVersion;

        @JsonProperty("event_id")
        public String eventId;

        @JsonProperty("event_type")
        public String eventType;

        @JsonProperty("producer")
        public String producer;

        @JsonProperty("aggregate_id")
        public String aggregateId;

        /** 无 omitempty ⇒ 未填时输出 {@code null}（Go 侧是 {@code *int64} 且未填即 nil）。 */
        @JsonProperty("aggregate_version")
        public Long aggregateVersion;

        @JsonProperty("operation_id")
        public String operationId;

        @JsonProperty("subject_ref")
        public String subjectRef;

        @JsonProperty("operator_ref")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public String operatorRef;

        @JsonProperty("target_type")
        public String targetType;

        @JsonProperty("target_id")
        public String targetId;

        /** 无 omitempty ⇒ 未填时输出 {@code null}。 */
        @JsonProperty("target_revision")
        public String targetRevision;

        @JsonProperty("revision_status")
        public String revisionStatus;

        @JsonProperty("operation")
        public String operation;

        @JsonProperty("source_ref")
        public String sourceRef;

        @JsonProperty("comment_id")
        public String commentId;

        @JsonProperty("parent_comment_id")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public String parentCommentId;

        @JsonProperty("old_state")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Integer oldState;

        @JsonProperty("new_state")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public Integer newState;

        @JsonProperty("visibility_state")
        public int visibilityState;

        @JsonProperty("search_evidence")
        public boolean searchEvidence;

        // ⚠️ 这三个字段在 Go 侧是 time.Time，其 encoding/json 序列化结果是
        //    **RFC3339Nano 字符串**（time.Time.MarshalJSON），不是数字也不是对象。
        //    这里用 String 承载而不是 OffsetDateTime，有两个原因：
        //      ① 与 Go 的线上字节一致；
        //      ② 共享层 {@code Wire.strictDecode} 内部的 ObjectMapper **没有注册 JavaTimeModule**
        //         （{@code common/…/communityfact/Wire.java:88-89} 只 configure 了
        //         FAIL_ON_UNKNOWN_PROPERTIES），
        //         用 OffsetDateTime 会让载荷在权威读/投递校验时反序列化直接失败。
        @JsonProperty("event_time")
        public String eventTime;

        @JsonProperty("occurred_at")
        public String occurredAt;

        @JsonProperty("available_at")
        public String availableAt;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * 时间格式工具（供事实链路与领域层共用）。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDomainFactService.java:378-402}。
     */
    public static final class DateTimes {

        private DateTimes() {
        }

        /**
         * 对应 Go {@code time.Format(time.RFC3339Nano)}。
         *
         * <p>⚠️ Go 的 {@code RFC3339Nano} <b>会去掉末尾的 0</b>（"2006-01-02T15:04:05.999999999"），
         * Java 的 {@code Instant#toString} 也是同样行为 ⇒ 天然一致。
         */
        public static String toRfc3339Nano(Instant instant) {
            return instant.toString();
        }

        /** 把 Instant 落到 {@link OffsetDateTime}，统一用 UTC 偏移。 */
        public static OffsetDateTime toOffsetDateTime(Instant instant) {
            return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
        }

        /** 把 {@link OffsetDateTime} 还原成 Instant。 */
        public static Instant toInstant(OffsetDateTime offsetDateTime) {
            return offsetDateTime == null ? null : offsetDateTime.toInstant();
        }
    }
}
