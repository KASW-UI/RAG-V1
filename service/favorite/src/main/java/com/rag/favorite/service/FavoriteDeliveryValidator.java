package com.rag.favorite.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.communityfact.Event;
import com.rag.favorite.domain.entity.FavoriteFactOutboxEntity;
import com.rag.favorite.domain.model.FavoriteAuthorityFact;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * 🔴 冻结信封的 12 条交叉校验 —— 对应 Go
 * {@code service/favorite/rpc/internal/model/favorite_delivery.go:94-116} 的
 * {@code validFavoriteDelivery}。
 *
 * <h2>为什么必须独立且被<b>两条路径</b>共用</h2>
 * <p>它被两处调用：
 * <ol>
 *   <li><b>投递路径</b> —— {@code DispatchFavoriteFactOnce} 在发出去<b>之前</b>校验
 *       （favorite_delivery.go:155）；不通过就把该行标成 {@code status=3 blocked}
 *       并<b>提交</b>。</li>
 *   <li><b>权威读路径</b> —— {@code authoritativeFavoriteRow} 的第 2 道校验
 *       （favorite_authority.go:132）。</li>
 * </ol>
 * 两处<b>各写一份</b>的后果：投递侧放宽了而权威侧没放宽 ⇒ DC 收到的与下游能读到的不是同一批事实；
 * 反之则投递侧会拒绝一批本来能读的事实。
 * ⇒ 抽成独立 Bean 是「保证两处校验不漂移」的结构性手段
 * （同款判据：like 域的 {@code LikeDeliveryValidator}）。
 *
 * <h2>🔴 两条调用点的解码严格度<b>不同</b>，不要合并</h2>
 * <table border="1">
 *   <caption>Go 侧两个调用点的解码方式</caption>
 *   <tr><th>路径</th><th>Go 行号</th><th>解码</th><th>失败后果</th></tr>
 *   <tr><td>投递</td><td>{@code favorite_delivery.go:155}</td>
 *       <td>{@code json.Unmarshal}（<b>宽松</b>，忽略未知字段）</td>
 *       <td>标 {@code status=3 blocked} + 提交</td></tr>
 *   <tr><td>权威读</td><td>{@code favorite_authority.go:132}</td>
 *       <td>{@code strictFavoriteJSON}（<b>严格</b>，拒未知字段 + 拒尾随）</td>
 *       <td>判「事实不可用」⇒ HTTP 404</td></tr>
 * </table>
 * <p>⇒ 本类暴露 {@link #decodeLenient} 与 {@link #decodeStrict} 两个入口，
 * 分别对应上面两行。<b>把两者统一成一个会改变可观察行为</b>：
 * 统一成宽松 ⇒ 权威读会开始接受带未知字段的信封（放宽了安全边界）；
 * 统一成严格 ⇒ 投递侧会把本可 blocked 的行判成别的错误。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.favorite.favorite.rpc.service} 压平为
 *           {@code com.rag.favorite.service}（D7）；</li>
 *       <li>实体 / 模型的 import 由 {@code com.rag.favorite.favorite.rpc.domain} 改为
 *           {@code com.rag.favorite.domain.entity} / {@code com.rag.favorite.domain.model}（D7）；</li>
 *       <li>共享层 {@code Wire} 1:N 拆分 ⇒ {@code Wire.Event} → 顶层 {@link Event}、
 *           {@code Wire.SubjectRef} → 顶层
 *           {@link com.rag.common.communityfact.SubjectRef}。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。12 条判据的<b>顺序</b>（短路顺序）、宽松 / 严格两条解码入口的
 *       <b>严格度差异</b>、{@code parseAuthorityID} 的三条规则、
 *       {@code sameFavoriteRevision} 的「一侧有另一侧无 ⇒ 不匹配」逐字未改。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/service/FavoriteDeliveryValidator.java:51-486}
 *       （类声明 :51 · {@code FrozenEvent} :66-86 · {@code AuthorityPayload} :99-125 ·
 *       {@code FavoriteSubjectRefV1} :128-136 · {@code FavoriteSubjectRefV2} :139-145 ·
 *       {@code SubjectResult} :149-159 · {@code LENIENT_JSON} :169-170 ·
 *       {@code decodeLenient} :178-187 · {@code decodeStrict} :193-206 · {@code toWireEvent} :209-221 ·
 *       {@code validDelivery} :248-316 · {@code parseAuthorityId} :349-382 ·
 *       {@code sameFavoriteRevision} :390-395 · {@code subjectV1} :405-453 ·
 *       {@code toUtcInstant} :458-460 · {@code parseRfc3339Nano} :469-482 · {@code STRICT_JSON} :485）。</li>
 * </ol>
 */
@Component
public class FavoriteDeliveryValidator {

    /** Go {@code favorite_authority.go:20}：{@code const maxExactJSONInteger int64 = 9007199254740991}（2^53-1）。 */
    public static final long MAX_EXACT_JSON_INTEGER = 9007199254740991L;

    /**
     * 🔴 冻结信封的解码目标 —— 对应 Go {@code FavoriteWireEvent}（favorite_delivery.go:26-36）。
     *
     * <p><b>为什么不用共享层的 {@code Event}</b>：{@code Event.payload} 声明成
     * {@code Object}，Jackson 会把它反序列化成 {@code LinkedHashMap}；
     * 而 Go 的 {@code json.RawMessage} 要求<b>保留原始 JSON</b>，
     * 否则「再序列化回去」就得不到同一份字节，JCS hash 会变。
     * ⇒ 本类用一个 {@link JsonNode} 字段保住原始 JSON 结构，
     * 需要对外输出时（权威响应）再转成 {@link Event}。
     */
    public static final class FrozenEvent {

        @JsonProperty("event_id")
        public String eventId;
        @JsonProperty("event_type")
        public String eventType;
        @JsonProperty("schema_version")
        public int schemaVersion;
        @JsonProperty("producer")
        public String producer;
        @JsonProperty("aggregate_id")
        public String aggregateId;
        @JsonProperty("aggregate_version")
        public long aggregateVersion;
        @JsonProperty("operation_id")
        public String operationId;
        @JsonProperty("occurred_at")
        public String occurredAt;
        @JsonProperty("payload")
        public JsonNode payload;
    }

    /**
     * 🔴 对应 Go {@code authorityPayload}（favorite_authority.go:45-58）。
     *
     * <p>{@code subject / favoriteId / folderId} 声明成 {@link JsonNode} 而不是 String ——
     * Go 侧是 {@code json.RawMessage}，因为这三个位置<b>既可能是字符串也可能是裸数字</b>，
     * 必须保留原始形态才能执行 {@link #parseAuthorityId} 的 2^53 规则。
     *
     * <p>⚠️ 本类<b>刻意不</b>加 {@code @JsonIgnoreProperties}：
     * 未知字段由 {@code STRICT_JSON} 的 {@code FAIL_ON_UNKNOWN_PROPERTIES} 拒绝，
     * 这正是 {@code strictFavoriteJSON} 的语义。
     */
    public static final class AuthorityPayload {

        @JsonProperty("schema_version")
        public int schemaVersion;
        @JsonProperty("event_id")
        public String eventId;
        @JsonProperty("subject_ref")
        public JsonNode subject;
        @JsonProperty("target_type")
        public String targetType;
        @JsonProperty("target_id")
        public String targetId;
        @JsonProperty("target_revision")
        public String targetRevision;
        @JsonProperty("operation")
        public String operation;
        @JsonProperty("source_ref")
        public String sourceRef;
        @JsonProperty("event_time")
        public String eventTime;
        @JsonProperty("available_at")
        public String availableAt;
        @JsonProperty("favorite_id")
        public JsonNode favoriteId;
        @JsonProperty("folder_id")
        public JsonNode folderId;
    }

    /** Go {@code FavoriteSubjectRef}（favorite_fact.go:78-82）：v1 三字段的严格解码目标。 */
    public static final class FavoriteSubjectRefV1 {

        @JsonProperty("authority_id")
        public String authorityId;
        @JsonProperty("tenant_id")
        public String tenantId;
        @JsonProperty("subject_id")
        public String subjectId;
    }

    /** Go {@code FavoriteSubjectRefV2}（favorite_fact.go:86-89）：v2 两字段（<b>没有</b> tenant_id）。 */
    public static final class FavoriteSubjectRefV2 {

        @JsonProperty("issuer")
        public String issuer;
        @JsonProperty("subject_id")
        public String subjectId;
    }

    /** Go 的 {@code (T, bool)} 多返回值包装（{@code favoriteSubjectV1} 的返回，
     *     {@code service/favorite/rpc/internal/model/favorite_authority.go:105}）。 */
    public static final class SubjectResult {

        /** 归一化后的 v1 三元组；{@code ok=false} 时为 {@code null}。 */
        public final FavoriteAuthorityFact.SubjectRefV1 value;
        public final boolean ok;

        public SubjectResult(FavoriteAuthorityFact.SubjectRefV1 value, boolean ok) {
            this.value = value;
            this.ok = ok;
        }
    }

    /**
     * 宽松 mapper —— 对应 Go 的 {@code json.Unmarshal}（投递路径，favorite_delivery.go:155）。
     *
     * <p>⚠️ <b>刻意关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}</b>：
     * Go 的 {@code encoding/json} 默认忽略未知字段，一条带多余字段的 payload
     * 在 Go 侧会被<b>正常解析</b>（然后由 12 条交叉校验去判断它是否可信）；
     * Java 若在解析阶段就抛异常，两侧的分流点就不一样了。
     */
    private static final ObjectMapper LENIENT_JSON =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * 🔴 对应 Go {@code favorite_delivery.go:155} 的
     * {@code json.Unmarshal([]byte(row.Payload), &event)}。
     *
     * @return 解码结果；解析失败返回 {@code null}（Go 侧是 {@code err != nil}）
     */
    public static FrozenEvent decodeLenient(String rawPayload) {
        if (rawPayload == null || rawPayload.isEmpty()) {
            return null;
        }
        try {
            return LENIENT_JSON.readValue(rawPayload, FrozenEvent.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 🔴 对应 Go {@code strictFavoriteJSON}（favorite_authority.go:60-70）——
     * 禁未知字段 + 禁尾随 JSON。
     */
    public static <T> T decodeStrict(String rawJson, Class<T> type) {
        if (rawJson == null) {
            throw new IllegalArgumentException("strictFavoriteJSON: null input");
        }
        try (com.fasterxml.jackson.core.JsonParser parser = STRICT_JSON.getFactory().createParser(rawJson)) {
            T result = parser.readValueAs(type);
            FavoriteRpcSupport.rejectTrailingJson(parser);
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("strictFavoriteJSON: " + e.getMessage(), e);
        }
    }

    /** 把 {@link FrozenEvent} 转成共享层的 {@link Event}（权威响应体的 {@code event} 字段）。 */
    public static Event toWireEvent(FrozenEvent source) {
        Event target = new Event();
        target.eventId = source.eventId;
        target.eventType = source.eventType;
        target.schemaVersion = source.schemaVersion;
        target.producer = source.producer;
        target.aggregateId = source.aggregateId;
        target.aggregateVersion = source.aggregateVersion;
        target.operationId = source.operationId;
        target.occurredAt = source.occurredAt;
        target.payload = source.payload;
        return target;
    }

    /**
     * 🔴 对应 Go {@code validFavoriteDelivery(row, event)}（favorite_delivery.go:94-116）。
     *
     * <p>12 条校验逐条对应：
     * <ol>
     *   <li>{@code event.EventID != row.EventID}</li>
     *   <li>{@code event.Producer != "rtw.community.favorite"}</li>
     *   <li>{@code event.EventType} ∈ {"rtw.favorite.assert", "rtw.favorite.retract"}</li>
     *   <li>{@code event.SchemaVersion} ∈ {1, 2}</li>
     *   <li>{@code event.AggregateID != fmt.Sprint(row.FavoriteID)}</li>
     *   <li>{@code event.AggregateVersion != row.AggregateVersion}</li>
     *   <li>{@code event.OperationID != row.EventID}</li>
     *   <li>payload 是非空 JSON 对象（Go 还额外要求首字节是 '{'）</li>
     *   <li>{@code time.Parse(time.RFC3339Nano, event.OccurredAt)} 成功</li>
     *   <li>payload strict-decode 成 {@code authorityPayload}（12 字段，禁未知字段）</li>
     *   <li>{@code parseAuthorityID(payload.FavoriteID)} == {@code row.FavoriteID} 且
     *       {@code parseAuthorityID(payload.FolderID)} &gt; 0</li>
     *   <li>{@code favoriteSubjectV1(schema, payload.Subject)} 成功、
     *       {@code payload.SchemaVersion == event.SchemaVersion}、
     *       {@code payload.EventID == event.EventID}</li>
     * </ol>
     *
     * <p>⚠️ <b>本方法不抛异常</b>：它是一个<b>谓词</b>（Go 返回 {@code bool}），
     * 两条调用点各自决定「不通过」的后果。
     */
    public boolean validDelivery(FavoriteFactOutboxEntity row, FrozenEvent event) {
        if (row == null || event == null) {
            return false;
        }
        if (row.getFavoriteId() == null || row.getAggregateVersion() == null || row.getEventId() == null) {
            return false;
        }
        String favoriteId = FavoriteFactEnvelopeFactory.decimal(row.getFavoriteId());
        long aggregateVersion = row.getAggregateVersion();

        // ① event_id 与行主键一致
        if (!row.getEventId().equals(event.eventId)) {
            return false;
        }
        // ② producer 固定
        if (!FavoriteFactEnvelopeFactory.PRODUCER.equals(event.producer)) {
            return false;
        }
        // ③ event_type ∈ {assert, retract}
        if (!"rtw.favorite.assert".equals(event.eventType)
                && !"rtw.favorite.retract".equals(event.eventType)) {
            return false;
        }
        // ④ schema_version ∈ {1, 2}
        if (event.schemaVersion != 1 && event.schemaVersion != 2) {
            return false;
        }
        // ⑤ 🔴 aggregate_id 与行内 favorite_id 逐字一致（strconv.FormatInt 语义）
        if (!favoriteId.equals(event.aggregateId)) {
            return false;
        }
        // ⑥ aggregate_version 与行内一致
        if (event.aggregateVersion != aggregateVersion) {
            return false;
        }
        // ⑦ operation_id == event_id（幂等键三处同源）
        if (!row.getEventId().equals(event.operationId)) {
            return false;
        }
        // ⑧ payload 必须是非空 JSON 对象
        if (event.payload == null || !event.payload.isObject() || event.payload.isEmpty()) {
            return false;
        }
        // ⑨ occurred_at 必须可解析
        if (parseRfc3339Nano(event.occurredAt) == null) {
            return false;
        }
        // ⑩ payload strict-decode（禁未知字段 + 禁尾随 JSON）
        AuthorityPayload payload;
        try {
            payload = decodeStrict(event.payload.toString(), AuthorityPayload.class);
        } catch (RuntimeException e) {
            return false;
        }
        if (payload == null) {
            return false;
        }
        // ⑪ favorite_id / folder_id 可解析，且 favorite_id 与行一致
        long payloadFavoriteId = parseAuthorityId(payload.favoriteId);
        long payloadFolderId = parseAuthorityId(payload.folderId);
        if (payloadFavoriteId != row.getFavoriteId() || payloadFolderId <= 0) {
            return false;
        }
        // ⑫ subject 形状与 schema 匹配，且 schema/event_id 自洽
        if (!subjectV1(event.schemaVersion, payload.subject).ok) {
            return false;
        }
        return payload.schemaVersion == event.schemaVersion && row.getEventId().equals(payload.eventId);
    }

    // ==================== 供权威读路径复用的原语 ====================

    /**
     * 🔴 对应 Go {@code parseAuthorityID}（favorite_authority.go:74-94）。
     *
     * <pre>
     * func parseAuthorityID(raw json.RawMessage) (int64, bool) {
     *     if len(raw) == 0 { return 0, false }
     *     var text string
     *     if raw[0] == '"' { json.Unmarshal(raw, &amp;text) } else { text = string(raw) }
     *     id, err := strconv.ParseInt(text, 10, 64)
     *     if err != nil || id &lt;= 0 || strconv.FormatInt(id, 10) != text { return 0, false }
     *     if raw[0] != '"' &amp;&amp; id &gt; maxExactJSONInteger { return 0, false }
     *     return id, true
     * }
     * </pre>
     *
     * <p>三条规则，缺一不可：
     * <ol>
     *   <li><b>规范十进制</b> —— {@code FormatInt(id,10) == text}，因此拒绝前导零、
     *       {@code +1}、{@code 1_0}、带空格的 {@code " 1"} 等。
     *       ⚠️ {@code Long.parseLong} 会接受 {@code +1}，所以必须再做一次往返比较。</li>
     *   <li><b>必须 &gt; 0</b>。</li>
     *   <li>🔴 <b>裸 JSON 数字额外要求 {@code <= 2^53-1}</b>。
     *       理由（favorite_fact.go:102-103）：生产雪花 ID 超过 2^53，
     *       任何经过 JSON 数字的路径都会丢精度 ⇒ <b>不可信的裸数字必须拒绝</b>，
     *       只接受显式的十进制字符串。</li>
     * </ol>
     *
     * @return 合规的 ID；不合规时返回 {@code 0}（Go 的第二返回值 {@code false}）
     */
    public static long parseAuthorityId(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            return 0L;
        }
        final String text;
        final boolean wasQuoted;
        if (raw.isTextual()) {
            text = raw.textValue();
            wasQuoted = true;
        } else if (raw.isNumber()) {
            text = raw.asText();
            wasQuoted = false;
        } else {
            // Go：非字符串时 text = string(raw)，ParseInt 对 true/{}/[] 一律失败（service/favorite/rpc/internal/model/favorite_authority.go:83-86）
            return 0L;
        }
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        final long id;
        try {
            id = Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
        // Go: strconv.FormatInt(id, 10) != text ⇒ 非规范十进制（service/favorite/rpc/internal/model/favorite_authority.go:87）
        if (id <= 0 || !Long.toString(id, 10).equals(text)) {
            return 0L;
        }
        if (!wasQuoted && id > MAX_EXACT_JSON_INTEGER) {
            return 0L;
        }
        return id;
    }

    /**
     * 🔴 对应 Go {@code sameFavoriteRevision}（favorite_authority.go:96-101）：
     * {@code if left == nil || right == nil { return left == nil && right == nil }}。
     *
     * <p><b>「一侧有另一侧无」也是不匹配</b> —— 这是「null 与空串不可混用」的落地处。
     */
    public static boolean sameFavoriteRevision(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.equals(right);
    }

    /**
     * 🔴 对应 Go {@code favoriteSubjectV1(schema, raw)}（favorite_authority.go:105-124）。
     *
     * <p>把 v1（三字段）与 v2（两字段）两种<b>源自有</b>形状归一化成同一个三元组，
     * 供内部业务比对使用。
     * ⚠️ 它<b>不改写</b>源 EventSpec，也<b>不影响</b>它的 JCS hash
     * （Go 注释：{@code "It never changes the source EventSpec or its JCS hash."}）。
     */
    public static SubjectResult subjectV1(int schema, JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return new SubjectResult(null, false);
        }
        final String authorityId;
        final String tenantId;
        final String subjectId;
        try {
            if (schema == 1) {
                FavoriteSubjectRefV1 legacy = decodeStrict(raw.toString(), FavoriteSubjectRefV1.class);
                if (legacy == null
                        || !"rtw.identity".equals(legacy.authorityId)
                        || !"platform".equals(legacy.tenantId)) {
                    return new SubjectResult(null, false);
                }
                authorityId = legacy.authorityId;
                tenantId = legacy.tenantId;
                subjectId = legacy.subjectId;
            } else if (schema == 2) {
                FavoriteSubjectRefV2 v2 = decodeStrict(raw.toString(), FavoriteSubjectRefV2.class);
                if (v2 == null || !"rtw.identity".equals(v2.issuer)) {
                    return new SubjectResult(null, false);
                }
                // Go: service/favorite/rpc/internal/model/favorite_authority.go:118 —— 归一化成 v1 三元组
                authorityId = v2.issuer;
                tenantId = "platform";
                subjectId = v2.subjectId;
            } else {
                return new SubjectResult(null, false);
            }
        } catch (RuntimeException e) {
            return new SubjectResult(null, false);
        }

        // Go: service/favorite/rpc/internal/model/favorite_authority.go:122-123 —— subject_id 必须是规范正整数
        if (subjectId == null || subjectId.isEmpty()) {
            return new SubjectResult(null, false);
        }
        final long uid;
        try {
            uid = Long.parseLong(subjectId);
        } catch (NumberFormatException e) {
            return new SubjectResult(null, false);
        }
        if (uid <= 0 || !Long.toString(uid, 10).equals(subjectId)) {
            return new SubjectResult(null, false);
        }
        return new SubjectResult(new FavoriteAuthorityFact.SubjectRefV1(authorityId, tenantId, subjectId), true);
    }

    /** 保留给权威读路径：把 {@code timestamptz} 列还原成 Go 侧的 UTC 时刻
     *     （Go 的 DSN 固定 {@code TimeZone=Asia/Shanghai}，
     *     {@code service/favorite/rpc/internal/model/db.go:21}）。 */
    static Instant toUtcInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * Go {@code time.Parse(time.RFC3339Nano, s)} 的等价物。
     *
     * <p>⚠️ 用 {@code OffsetDateTime.parse} 而非 {@code Instant.parse}：前者的格式更严格，
     * 更接近 Go 的 RFC3339Nano；后者会接受缺分钟的写法而 Go 不会。
     * 写入端由 {@link FavoriteFactEnvelopeFactory#rfc3339Nano} 保证格式一致。
     */
    private static Instant parseRfc3339Nano(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (java.time.format.DateTimeParseException primary) {
            try {
                return Instant.parse(value);
            } catch (java.time.format.DateTimeParseException secondary) {
                return null;
            }
        }
    }

    /** 复用 {@code FavoriteRpcSupport} 的严格 mapper（保持单一实例）。 */
    private static final ObjectMapper STRICT_JSON = FavoriteRpcSupport.STRICT_JSON;
}
