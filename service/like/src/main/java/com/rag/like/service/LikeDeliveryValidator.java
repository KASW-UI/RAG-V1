package com.rag.like.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.rag.common.communityfact.CanonicalHash;
import com.rag.common.communityfact.Event;
import com.rag.like.domain.entity.LikeDomainFactOutboxEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 冻结信封合法性校验 —— 对应 Go 端
 * {@code service/like/rpc/internal/model/fact_delivery.go:23-50} 的 {@code validLikeDelivery}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeDeliveryValidator.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.like.like.rpc.service} 压平为 {@code com.rag.like.service}（D7）；</li>
 *       <li>实体/Mapper 的 import 由 {@code com.rag.like.like.rpc.domain} 改为
 *           {@code com.rag.like.domain.entity}（D7 包压平）；</li>
 *       <li>共享层的 {@code Wire} 1:N 拆分 ⇒ {@code Wire.canonicalHash} →
 *           {@link CanonicalHash#canonicalHash(byte[])}、{@code Wire.validHash} →
 *           {@link CanonicalHash#validHash(String)}、{@code Wire.Event} → 顶层 {@link Event}；</li>
 *       <li>本类<b>额外承载</b>社区事实 wire 契约的 4 个字面量常量
 *           （B 侧它们在 {@code LikeEventContracts.java} 里，见下方「常量落点说明」）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。严格解码矩阵（禁未知字段 / 禁标量强转 / 禁尾部 JSON）、
 *       5 组判据的<b>顺序</b>、载荷 19 个字段、JCS 哈希对账逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>常量落点说明（4 个社区事实 wire 字面量）</h2>
 * <p>B 侧把本域 8 个 wire 字面量集中在 {@code like-rpc/.../mq/LikeEventContracts.java} 一个文件里；
 * C 侧 {@code contract/} 包的 7 个文件是<b>映射表锁定的 69 个文件</b>之一一集合
 * （{@code docs/Mapping_Table/like/like.md} §7.1「C 列零遗漏（硬要求）」= 69/69）
 * ⇒ <b>不能新增第 70 个文件</b> ⇒ 常量按语义下沉：
 * <ul>
 *   <li>{@code contract/KafkaLikeMsg} ⇒ {@code INBOX_TOPIC} / {@code INBOX_CONSUMER}；</li>
 *   <li>{@code contract/ArticleHotEvent} ⇒ {@code EVENT_TYPE_LIKE} / {@code OUTBOX_EVENT_TYPE_ARTICLE_HOT_LIKE}；</li>
 *   <li><b>本类</b> ⇒ {@code FACT_PRODUCER} / {@code FACT_SCHEMA_VERSION} /
 *       {@code FACT_EVENT_TYPE} / {@code FACT_EVENT_ID_PREFIX}
 *       （本类已承载 {@link LikeFact} 载荷定型与全部校验判据 ⇒ 事实 wire 契约的权威落点）。</li>
 * </ul>
 * ⇒ <b>每个常量的值逐字未改</b>，只是承载类不同 ⇒ 行为等价（登记见
 * {@code docs/DRIFT/like/verification-like-2026-11-03.md}）。
 *
 * <h2>它是「事实能不能发出去」的唯一闸门</h2>
 * <p>校验失败的处理不是「跳过这条」，而是
 * <b>把 {@code delivery_status} 置为 BLOCKED(3) 并返回 {@code ErrFrozenEnvelopeBlocked}</b>
 * （Go {@code fact_delivery.go:78-88}）—— 这条事实从此不再被调度器领走，等人工迁移处理。
 *
 * <p>理由（Go {@code fact_delivery.go:85-87} 的措辞）：
 * 信封是<b>事务内冻结</b>的，若它与源行对不上，说明有人在冻结之后改动过事实，
 * 继续发只会让 DC 收到一条来源不可信的事实 ⇒「先发出去再说」在这里是错的。
 *
 * <h2>🔴 严格解码必须由<b>本类自己</b>的 mapper 承担</h2>
 * <p>Go 的 {@code communityfact.StrictDecode}（{@code service/common/communityfact/wire.go:121-131}）有两条硬规则：
 * <ol>
 *   <li>{@code decoder.DisallowUnknownFields()} ⇒ <b>多一个字段就解码失败</b>；</li>
 *   <li>Go 的 {@code encoding/json} 对类型不匹配<b>一律报错</b>
 *       （{@code json: cannot unmarshal number into Go struct field … of type string}）。</li>
 * </ol>
 * <p>⚠️ <b>为什么不用共享层的 {@code Wire.strictDecode} / {@code Wire.MAPPER}</b>：
 * {@code Wire.MAPPER} 只配了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * <b>没有禁标量强转</b> ⇒ {@code "schema_version":"1"} 会被读成 {@code 1} 并通过校验。
 * <br>⚠️ <b>为什么不改共享层的 {@code Wire.MAPPER}</b>：它在 {@code service/common} 下，
 * 是 like / comment / message / article / favorite / follow / task 多个域共用的
 * ⇒ 改它会波及多域（且 {@code common/**} 属 {@code global} 收口域写权，域侧不可写）
 * ⇒ 严格性只在本域的冻结信封/载荷上生效，边界清晰。
 *
 * <h2>⚠️ 与 comment 域那份<b>不是</b>同一份规则</h2>
 * <p>comment 域的 {@code CommentDeliveryValidator} 校验的是 {@code commentFact}，
 * like 域校验的是 {@code likeFact}（Go {@code domain_fact.go:54-74}）⇒
 * <b>字段集合与判据都不同</b>（见下方 {@link LikeFact}）。
 * ⚠️ 尤其：<b>like 的载荷 {@code aggregate_id} 与信封的 {@code aggregate_id} 本来就不相等</b>
 * （载荷 = {@code targetType + "/" + targetID}，信封 = {@code like-state/{user}/{type}/{id}}，
 * Go {@code domain_fact.go:91-96}）⇒ <b>没有</b>「载荷 aggregate_id 必须等于信封 aggregate_id」这条判据
 * —— 照抄 comment 那份会引入一条 Go 没有的判据 ⇒ <b>把合法事实判成 BLOCKED</b>。
 */
@Service
public class LikeDeliveryValidator {

    // ==================== 社区事实 wire 契约常量（B 出处：like-rpc/.../mq/LikeEventContracts.java:178-187）====================

    /** 对应 Go {@code fact_delivery.go:17} {@code const likeFactProducer = "rtw.like-mq"}。 */
    public static final String FACT_PRODUCER = "rtw.like-mq";

    /** 对应 Go {@code domain_fact.go:90} 的 {@code SchemaVersion: "rtw.community-fact.v1"}。 */
    public static final String FACT_SCHEMA_VERSION = "rtw.community-fact.v1";

    /** 对应 Go {@code domain_fact.go:90} 的 {@code EventType: "community.target.interaction"}。 */
    public static final String FACT_EVENT_TYPE = "community.target.interaction";

    /**
     * 对应 Go {@code domain_fact.go} 的 {@code eventID := "rtw.like." + p.Inbox.MsgId} 前缀。
     *
     * <p>B 出处：{@code like-rpc/.../mq/LikeEventContracts.java:178}。
     */
    public static final String FACT_EVENT_ID_PREFIX = "rtw.like.";

    /**
     * 本域专用的<b>严格</b> mapper —— 对齐 Go {@code communityfact.StrictDecode}。
     *
     * <h3>规则一：未知字段即非法</h3>
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES = true}（Go 的 {@code DisallowUnknownFields}）。
     *
     * <h3>规则二：标量类型必须精确匹配</h3>
     * <p>Go 的 {@code encoding/json} 对<b>类型不匹配</b>一律报错，而 Jackson 默认会<b>静默强转</b>。
     * ⇒ 这里把 Go 报错、Jackson 放行的组合全部改成 {@code Fail}：
     * <table border="1">
     *   <caption>强转禁令矩阵</caption>
     *   <tr><th>目标类型</th><th>输入形态</th><th>Go</th><th>Jackson 默认</th></tr>
     *   <tr><td>整数</td><td>字符串</td><td>报错</td><td>放行（读成 1）</td></tr>
     *   <tr><td>整数</td><td>浮点数</td><td>报错</td><td>放行（截断）</td></tr>
     *   <tr><td>浮点</td><td>字符串</td><td>报错</td><td>放行</td></tr>
     *   <tr><td>字符串</td><td>数字</td><td>报错</td><td>放行（读成 "1"）</td></tr>
     *   <tr><td>字符串</td><td>布尔</td><td>报错</td><td>放行（读成 "true"）</td></tr>
     *   <tr><td>布尔</td><td>数字</td><td>报错</td><td>放行（0→false）</td></tr>
     * </table>
     *
     * <h3>规则三：禁止尾部多余 JSON</h3>
     * <p>见 {@link #strictDecode}（Go {@code wire.go:127-129}）。
     */
    private static final ObjectMapper STRICT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // 整数：字符串 / 浮点数一律 Fail（Go 的 encoding/json 对此报错）
            .withCoercionConfig(LogicalType.Integer, cfg -> cfg
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            // 浮点：字符串一律 Fail
            .withCoercionConfig(LogicalType.Float, cfg -> cfg
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail))
            // 字符串：数字 / 浮点 / 布尔一律 Fail
            .withCoercionConfig(LogicalType.Textual, cfg -> cfg
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            // 布尔：字符串 / 数字一律 Fail
            .withCoercionConfig(LogicalType.Boolean, cfg -> cfg
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
            .build();

    /**
     * 只负责「把信封里的 payload 对象重新序列化」。
     *
     * <p>刻意与 {@code config/WebMvcConfig} 那个域内 Jackson 补强分开用 ——
     * 那个的宽松配置是为 <b>Kafka 审计消息</b>对齐 Go 的；这里处理的是<b>已由 Go 冻结过</b>的事实载荷，
     * 判定非法载荷要靠<b>严格</b>解码（禁止未知字段 + 禁止标量强转）。
     */
    private final ObjectMapper objectMapper;

    public LikeDeliveryValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 对应 Go {@code fact_delivery.go:77} 的 strict decode 信封。
     *
     * <p>用本类自带的 {@link #STRICT_MAPPER}（禁止未知字段 + 禁止标量强转），
     * 并保留 Go 的「禁止尾部多余 JSON」规则（{@code wire.go:127-129}）。
     */
    public Event decodeEnvelope(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("decode like delivery envelope: empty envelope");
        }
        try {
            return strictDecode(new StringReader(raw), Event.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("decode like delivery envelope: " + e.getMessage(), e);
        }
    }

    /**
     * 对应 Go {@code validLikeDelivery(row, event)}（{@code fact_delivery.go:23-50}），逐条照抄。
     *
     * <p><b>判据分四组，顺序即短路顺序</b>：
     * <ol>
     *   <li><b>行自身完整性</b>：{@code aggregate_id} / {@code fact_version} / {@code delivery_envelope} 非空；</li>
     *   <li><b>信封 ⇔ 行的身份一致</b>：{@code event_id} / {@code event_type} / {@code aggregate_id} /
     *       {@code aggregate_version} 四项两两相等；{@code schema_version} 必须为整数 1；
     *       {@code producer} 必须是 {@code rtw.like-mq}；{@code operation_id} 非空；</li>
     *   <li><b>信封自身合法性</b>：{@code occurred_at} 能按 RFC3339Nano 解析；</li>
     *   <li><b>载荷 ⇔ 信封交叉校验</b>（{@code fact_delivery.go:35-49}，最关键的一段）：
     *       <ul>
     *         <li>{@code payload.schema_version} 必须等于 {@code rtw.community-fact.v1}；</li>
     *         <li>{@code payload.event_id} / {@code event_type} / {@code producer} / {@code operation_id}
     *             必须等于信封同名值；</li>
     *         <li>⚠️ <b>{@code payload.aggregate_version} 必须为 null</b> ——
     *             「版本号只存在于信封，不存在于载荷」是防伪造的关键；</li>
     *         <li>{@code payload.subject_ref} / {@code target_type} / {@code target_id} 必须非空；</li>
     *         <li>{@code payload.occurred_at} 必须与信封 {@code occurred_at} <b>是同一时刻</b>
     *             （Go 用 {@code .Equal()} 比较瞬时，不是字符串相等）；</li>
     *         <li>⚠️ <b>载荷里没有 {@code aggregate_id ⇔ 信封 aggregate_id} 这条判据</b>
     *             —— like 的这两个值<b>本来就不相等</b>（见类注释）；</li>
     *         <li>载荷的规范化哈希必须等于行 {@code payload} 列的规范化哈希
     *             （<b>JCS 规范化后</b>比，因为 jsonb 列会重排键序与空白）。</li>
     *       </ul></li>
     * </ol>
     *
     * @return 载荷与信封是否构成一对可投递的冻结事实
     */
    public boolean isValidDelivery(LikeDomainFactOutboxEntity row, Event event) {
        if (row == null || event == null) {
            return false;
        }
        // --- ① 行自身完整性（Go fact_delivery.go:24）
        if (row.getAggregateId() == null || row.getFactVersion() == null
                || row.getDeliveryEnvelope() == null) {
            return false;
        }
        // --- ② 信封 ⇔ 行的身份一致（Go fact_delivery.go:25-28）
        if (!equals(event.eventId, row.getEventId())
                || !equals(event.eventType, row.getEventType())
                || event.schemaVersion != 1
                || !FACT_PRODUCER.equals(event.producer)
                || !equals(event.aggregateId, row.getAggregateId())
                || event.aggregateVersion != row.getFactVersion().longValue()
                || event.operationId == null || event.operationId.isEmpty()) {
            return false;
        }
        // --- ③ 信封自身合法性（Go fact_delivery.go:31-34：occurred_at 必须能按 RFC3339Nano 解析）
        Instant occurred = parseGoRfc3339(event.occurredAt);
        if (occurred == null) {
            return false;
        }
        LikeFact payload;
        try {
            payload = decodePayload(event);
        } catch (RuntimeException e) {
            return false;
        }
        if (payload == null) {
            return false;
        }

        // --- ④ 载荷 ⇔ 信封交叉校验（Go fact_delivery.go:35-49）
        if (!FACT_SCHEMA_VERSION.equals(payload.schemaVersion)
                || !equals(payload.eventId, event.eventId)
                || !equals(payload.eventType, event.eventType)
                || !equals(payload.producer, event.producer)
                // ⚠️ 载荷里不得带版本号（版本号的唯一权威在信封）
                || payload.aggregateVersion != null
                || !equals(payload.operationId, event.operationId)
                || isBlank(payload.subjectRef)
                || isBlank(payload.targetType)
                || isBlank(payload.targetId)) {
            return false;
        }
        // payload.occurred_at 是 RFC3339 字符串（Go time.Time 的 JSON 形态）；
        // 比较的是**瞬时**而非字符串 —— 两种时区写法（Z vs +08:00）也算相等（Go 的 .Equal()）。
        if (isBlank(payload.occurredAt)) {
            return false;
        }
        Instant payloadOccurred = parseGoRfc3339(payload.occurredAt);
        if (payloadOccurred == null || !payloadOccurred.equals(occurred)) {
            return false;
        }

        // 🔴 Go 的 likeFact 里 EventTime / OccurredAt / AvailableAt 三者都是 time.Time
        //    （domain_fact.go:71-73）⇒ encoding/json 解码**必须**是 RFC3339，
        //    否则 StrictDecode 直接失败 ⇒ 该行 BLOCKED。
        //    Java 侧用 String 承载（为了线上字节一致）⇒ 必须**显式**校验格式，
        //    否则畸形时间串（如 "not-a-time"）会通过校验并被投递。
        if (parseGoRfc3339(payload.eventTime) == null
                || parseGoRfc3339(payload.availableAt) == null) {
            return false;
        }

        // --- ⑤ 规范化哈希比对（jsonb 会重排键序 ⇒ 必须 JCS 规范化后比）
        //     Go: fact_delivery.go:44-49
        try {
            String payloadHash = CanonicalHash.canonicalHash(objectMapperBytes(event));
            String rowHash = CanonicalHash.canonicalHash(
                    row.getPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return payloadHash.equals(rowHash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 对应 Go {@code likeFact}（{@code domain_fact.go:54-74}）的<b>定型</b>载荷。
     *
     * <p>🔴 <b>字段必须与 Go 逐条一致（19 个，一个不多一个不少）</b>：
     * <ul>
     *   <li><b>多一个</b> ⇒ Go 的 {@code DisallowUnknownFields} 会让<b>Go 自己</b>解码失败
     *       ⇒ 我们的校验器若缺这个字段，就会放行 Go 会 BLOCKED 的信封 ⇒ 判据集合与 Go 不同源；</li>
     *   <li><b>少一个</b> ⇒ RAG 自己写出的载荷（{@code domain/service/LikeDomainFactService} 用 ObjectNode 拼的
     *       19 个字段）会被本类<b>判成非法</b> ⇒ 合法事实被 BLOCKED ⇒ 功能自毁。</li>
     * </ul>
     * <p>⇒ 两边都必须对齐 ⇒ 本类的字段清单就是 Go {@code likeFact} 的<b>逐字翻译</b>。
     *
     * <p>⚠️ <b>三个时间字段用 {@code String}</b> 而非 {@code Instant}：
     * 线上字节必须与 Go 的 RFC3339Nano 形态一致（{@code Instant} 序列化会丢精度与时区形态），
     * 所以<b>承载</b>用 String、<b>校验</b>用 {@link #parseGoRfc3339}（等价于 Go {@code time.Time.UnmarshalJSON}）。
     */
    public static class LikeFact {

        @com.fasterxml.jackson.annotation.JsonProperty("schema_version")
        public String schemaVersion;
        @com.fasterxml.jackson.annotation.JsonProperty("event_id")
        public String eventId;
        @com.fasterxml.jackson.annotation.JsonProperty("event_type")
        public String eventType;
        @com.fasterxml.jackson.annotation.JsonProperty("producer")
        public String producer;
        @com.fasterxml.jackson.annotation.JsonProperty("aggregate_id")
        public String aggregateId;
        /** Go 是 {@code *int64} ⇒ 恒为 null（Go 从不赋值）。 */
        @com.fasterxml.jackson.annotation.JsonProperty("aggregate_version")
        public Long aggregateVersion;
        @com.fasterxml.jackson.annotation.JsonProperty("operation_id")
        public String operationId;
        @com.fasterxml.jackson.annotation.JsonProperty("subject_ref")
        public String subjectRef;
        @com.fasterxml.jackson.annotation.JsonProperty("target_type")
        public String targetType;
        @com.fasterxml.jackson.annotation.JsonProperty("target_id")
        public String targetId;
        /** Go 是 {@code *string} ⇒ 恒为 null（Go 从不赋值）。 */
        @com.fasterxml.jackson.annotation.JsonProperty("target_revision")
        public String targetRevision;
        @com.fasterxml.jackson.annotation.JsonProperty("revision_status")
        public String revisionStatus;
        @com.fasterxml.jackson.annotation.JsonProperty("operation")
        public String operation;
        @com.fasterxml.jackson.annotation.JsonProperty("source_ref")
        public String sourceRef;
        @com.fasterxml.jackson.annotation.JsonProperty("old_state")
        public int oldState;
        @com.fasterxml.jackson.annotation.JsonProperty("new_state")
        public int newState;
        /** Go 是 {@code time.Time} ⇒ 必须 RFC3339（见类注释）。 */
        @com.fasterxml.jackson.annotation.JsonProperty("event_time")
        public String eventTime;
        /** Go 是 {@code time.Time} ⇒ 必须 RFC3339。 */
        @com.fasterxml.jackson.annotation.JsonProperty("occurred_at")
        public String occurredAt;
        /** Go 是 {@code time.Time} ⇒ 必须 RFC3339。 */
        @com.fasterxml.jackson.annotation.JsonProperty("available_at")
        public String availableAt;

        public LikeFact() {
            // Jackson 需要无参构造
        }
    }

    /** 严格解码信封内的 payload（与 Go 的 {@code StrictDecode(…event.Payload, &payload)} 同规则）。 */
    public LikeFact decodePayload(Event event) {
        try {
            String raw = objectMapper.writeValueAsString(event.payload);
            return strictDecode(new StringReader(raw), LikeFact.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 对应 Go 的 {@code communityfact.StrictDecode}（{@code wire.go:121-131}）：
     * 禁止未知字段 + 禁止尾部多余 JSON。
     *
     * <p>⚠️ 用本类自带的 {@link #STRICT_MAPPER}（不是共享层的 {@code Wire.MAPPER}）：
     * 后者没禁标量强转 ⇒ {@code "schema_version":"1"} 会被当成 1 放过去。
     */
    private static <T> T strictDecode(StringReader reader, Class<T> valueType) throws IOException {
        try (JsonParser parser = STRICT_MAPPER.getFactory().createParser(reader)) {
            T result = parser.readValueAs(valueType);
            if (parser.nextToken() != null) {
                throw new IOException("trailing JSON");
            }
            return result;
        }
    }

    /**
     * 对应 Go 的 {@code time.Time.UnmarshalJSON}：<b>只接受 RFC3339</b>。
     *
     * <p>Go 的 RFC3339 布局是 {@code "2006-01-02T15:04:05Z07:00"}（可带小数秒）⇒
     * <ul>
     *   <li>{@code "2026-10-25T06:00:00Z"} ✅</li>
     *   <li>{@code "2026-10-25T06:00:00.123456789Z"} ✅</li>
     *   <li>{@code "2026-10-25T06:00:00+08:00"} ✅（等价于同一瞬时）</li>
     *   <li>{@code "2026-10-25 06:00:00"} ❌（缺 T 与偏移）</li>
     *   <li>{@code "not-a-time"} ❌</li>
     * </ul>
     *
     * 🔴 用 {@link DateTimeFormatter#ISO_OFFSET_DATE_TIME} 而不是 {@code Instant.parse}：
     * 前者接受 {@code Z} 与 {@code ±hh:mm} 两种偏移（Go 的 RFC3339 也接受两者），
     * 且要求偏移必须存在（Go 同样要求）⇒ 与 Go 的接受集合一致。
     *
     * @return 解析成功返回瞬时；失败返回 {@code null}（调用方据此判非法）
     */
    static Instant parseGoRfc3339(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] objectMapperBytes(Event event) throws Exception {
        // 信封 payload 是 Object；重新序列化后与行 payload 做 JCS 规范化比对。
        // jsonb 规范化过的文本与信封内嵌文本只在空白/键序上不同，JCS 后相等。
        return objectMapper.writeValueAsBytes(event.payload);
    }

    private static boolean equals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
