package com.rag.comment.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.rag.common.communityfact.CanonicalHash;
import com.rag.common.communityfact.Event;
import com.rag.comment.domain.entity.CommentDomainFactOutboxEntity;
import com.rag.comment.domain.service.CommentDomainFactService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 冻结信封合法性校验 —— 对应 Go 端
 * {@code service/comment/rpc/internal/model/fact_delivery.go:20-48} 的
 * {@code validCommentDelivery} 与 {@code :129-134} 的 {@code decodeCommentDelivery}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentDeliveryValidator.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.comment.comment.rpc.service} 压平为 {@code com.rag.comment.service}（D7）；</li>
 *       <li>实体 import 由 {@code com.rag.comment.comment.rpc.domain.CommentDomainFactOutboxEntity}
 *           改为 {@code com.rag.comment.domain.entity.CommentDomainFactOutboxEntity}（D7 包压平）；</li>
 *       <li>共享层 1:N 拆分 ⇒ {@code Wire.Event} → 顶层 {@link Event}、
 *           {@code Wire.canonicalHash} → {@link CanonicalHash#canonicalHash(byte[])}；</li>
 *       <li>{@code CommentDomainFactService} 在 C 侧落 {@code com.rag.comment.domain.service}
 *           ⇒ 本类<b>新增</b>该 import（B 侧两者同包，无需 import）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。严格解码矩阵（禁未知字段 / 禁标量强转 / 禁尾部 JSON）、
 *       5 组判据的<b>顺序</b>、载荷 24 个字段、JCS 哈希对账逐字未改。</li>
 *   <li><b>B 出处</b>：见上；逐方法行号见各方法 javadoc。</li>
 * </ol>
 *
 * <h2>它是「事实能不能发出去」的唯一闸门</h2>
 * <p>校验失败的处理不是「跳过这条」，而是
 * <b>把 delivery_status 置为 BLOCKED(3) 并返回 {@code ErrFrozenEnvelopeBlocked}</b>
 * （fact_delivery.go:78-90）—— 这条事实从此不再被调度器领走，等人工迁移处理。
 *
 * <p>理由（Go 注释，:86-88）：信封是<b>事务内冻结</b>的，若它与源行对不上，
 * 说明有人在冻结之后改动过事实，继续发只会让 DC 收到一条来源不可信的事实。
 * 「先发出去再说」在这里是错的。
 *
 * <h2>🔴 严格解码必须由<b>本类自己</b>的 mapper 承担（2026-10-24 修复）</h2>
 *
 * <p>Go 的 {@code communityfact.StrictDecode} 有<b>两条</b>硬规则
 * （{@code service/common/communityfact/wire.go:121-131}）：
 * <ol>
 *   <li>{@code decoder.DisallowUnknownFields()} ⇒ <b>多一个字段就解码失败</b>；</li>
 *   <li>Go 的 {@code encoding/json} 对 {@code {"schema_version":"1"}}（字符串→整数）<b>报错</b>。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么不用共享层的 {@code Wire.strictDecode} / {@code Wire.MAPPER}</b>：
 * {@code Wire.MAPPER}（{@code common/…/communityfact/Wire.java:88-89}）只配了
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}，<b>没有禁 String→int 强转</b>
 * ⇒ {@code "schema_version":"1"} 会被读成 {@code 1} 并通过校验。
 * <br>⚠️ <b>为什么不改共享层的 {@code Wire.MAPPER}</b>：它在 {@code common/} 下，
 * 是 like / message / article / favorite / follow / task 多个域共用的
 * （且 {@code common/**} 属 {@code global} 收口域写权，域侧不可写）
 * ⇒ 改它会波及多域、且有合并冲突风险。
 * <br>⇒ <b>本类自带一个严格 mapper</b>：严格性只在本域的冻结信封/载荷上生效，边界清晰。
 *
 * <p>⚠️ 另有一处历史错误已修：{@code CommentFact} 上曾挂
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}，
 * 它会<b>覆盖</b> mapper 级的严格性 ⇒ 严格性被静默取消。
 * 现已移除（见 {@code CommentDomainFactService.CommentFact} 的类注释）。
 *
 * <h2>⚠️ 与 like 域那份<b>不是</b>同一份规则</h2>
 * <p>like 域的 {@code LikeDeliveryValidator} 校验的是 {@code likeFact}，
 * 本类校验的是 {@code commentFact} ⇒ <b>字段集合与判据都不同</b>。
 * 尤其：comment 域<b>有</b>「载荷 aggregate_id 必须等于信封 aggregate_id」
 * 与「载荷 comment_id 必须等于 aggregate_id」两条判据
 * （B 出处：{@code CommentDeliveryValidator.java:222 / :225}），
 * 而 like 域<b>没有</b>这两条（like 的载荷 aggregate_id 与信封 aggregate_id 本来就不相等）。
 * ⇒ 抄 like 那份会把这两条判据丢掉 ⇒ 判据集合与 B 侧不同源。
 */
@Service
public class CommentDeliveryValidator {

    /**
     * 本域专用的<b>严格</b> mapper —— 对齐 Go {@code communityfact.StrictDecode}。
     *
     * <h3>规则一：未知字段即非法</h3>
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES = true}（Go 的 {@code DisallowUnknownFields}）。
     *
     * <h3>规则二：标量类型必须精确匹配</h3>
     * <p>Go 的 {@code encoding/json} 对<b>类型不匹配</b>一律报错（{@code json: cannot unmarshal number
     * into Go struct field … of type string}），而 Jackson 2 默认会<b>静默强转</b>。
     * ⇒ 这里把 Go 报错、Jackson 放行的六种组合全部改成 {@code Fail}：
     * <table border="1">
     *   <caption>强转禁令矩阵（每一条的「Go 行为」列都可在 Go 侧复现）</caption>
     *   <tr><th>目标类型</th><th>输入形态</th><th>Go</th><th>Jackson 2 默认</th></tr>
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
     *
     * <p>⚠️ 与域内 Web 层那个共享 Bean 的配置<b>方向相反</b>是<b>刻意的</b>：
     * 那个对齐的是 Go {@code encoding/json} 的<b>宽松</b>语义（Kafka 审计消息：忽略未知字段）；
     * 这里对齐的是 Go {@code StrictDecode} 的<b>严格</b>语义（冻结信封：未知字段即非法）。
     * 两者服务不同的真源规则，混用必然错一边。
     *
     * <p>⚠️ <b>收紧严格性会同时影响权威读路径</b>，这是<b>符合 Go 的</b>：
     * Go 侧权威读同样走 {@code decodeCommentDelivery}（{@code StrictDecode}）+
     * {@code validCommentDelivery}，任一失败即 {@code ErrAuthorityUnavailable}
     * （{@code fact_authority.go:18-19 / :152-154}）⇒ 两侧的「拒绝口径」完全一致。
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
     * 那个的宽松配置是为 <b>Kafka 审计消息</b>对齐 Go 的；这里处理的是<b>已经由 Go 冻结过</b>的事实载荷，
     * 判定非法载荷要靠<b>严格</b>解码（禁止未知字段 + 禁止 string→int 强转）。
     */
    private final ObjectMapper objectMapper;

    public CommentDeliveryValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 对应 Go {@code fact_delivery.go:18} {@code const commentFactProducer = "rtw.comment-rpc"}。 */
    private static final String COMMENT_FACT_PRODUCER = CommentDomainFactService.PRODUCER;

    /** 对应 Go {@code domain_fact.go:99} 的 schema 版本标识。 */
    private static final String COMMENT_SCHEMA_VERSION = CommentDomainFactService.SCHEMA_VERSION;

    /**
     * 对应 Go {@code decodeCommentDelivery}：strict decode 信封。
     *
     * <p>用本类自带的 {@link #STRICT_MAPPER}（禁止未知字段 + 禁止 string→int 强转），
     * 并保留 Go 的「禁止尾部多余 JSON」规则（{@code wire.go:121-131}）。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDeliveryValidator.java:140-149}。
     */
    public Event decodeEnvelope(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("decode comment delivery envelope: empty envelope");
        }
        try {
            return strictDecode(new StringReader(raw), Event.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("decode comment delivery envelope: " + e.getMessage(), e);
        }
    }

    /**
     * 对应 Go {@code validCommentDelivery(row, event)}（fact_delivery.go:20-48），逐条照抄。
     *
     * <p><b>14 个判据分四组，顺序即短路顺序</b>：
     * <ol>
     *   <li><b>行自身完整性</b>：aggregate_id / fact_version / delivery_envelope 三者非空；</li>
     *   <li><b>信封 ⇔ 行的身份一致</b>：event_id / event_type / aggregate_id / aggregate_version
     *       四项必须两两相等；schema_version 必须为 1；producer 必须是 {@code rtw.comment-rpc}；</li>
     *   <li><b>信封自身合法性</b>：operation_id 非空；payload 是合法 JSON；
     *       occurred_at 能按 RFC3339Nano 解析；</li>
     *   <li><b>载荷 ⇔ 信封交叉校验</b>（fact_delivery.go:33-41，最关键的一段）：
     *       <ul>
     *         <li>payload.schema_version 必须等于 {@code rtw.community-fact.v1}；</li>
     *         <li>payload.event_id / event_type / producer / operation_id 必须等于信封同名值；</li>
     *         <li>payload.aggregate_id 必须等于信封的；</li>
     *         <li>⚠️ <b>payload.aggregate_version 必须为 null</b> ——
     *             「版本号只存在于信封，不存在于载荷」是防伪造的关键；
     *             若载荷里也带一个版本号，两处不一致时无法判定哪个是真的；</li>
     *         <li>payload.comment_id 必须等于 aggregate_id；</li>
     *         <li>payload.subject_ref / target_type / target_id 必须非空；</li>
     *         <li>payload.occurred_at 必须与信封 occurred_at <b>是同一时刻</b>
     *             （Go 用 {@code .Equal()} 比较瞬时，不是字符串相等 —— 两种时区写法也算相等）；</li>
     *         <li>payload 的规范化哈希必须等于行 payload 列的规范化哈希
     *             （<b>JCS 规范化后</b>比，因为 jsonb 列会重排键序与空白）。</li>
     *       </ul></li>
     * </ol>
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDeliveryValidator.java:180-260}。
     *
     * @return 载荷与信封是否构成一对可投递的冻结事实
     */
    public boolean isValidDelivery(CommentDomainFactOutboxEntity row, Event event) {
        if (row == null || event == null) {
            return false;
        }
        // --- ① 行自身完整性
        if (row.getAggregateId() == null || row.getFactVersion() == null
                || row.getDeliveryEnvelope() == null) {
            return false;
        }
        // --- ② 信封 ⇔ 行的身份一致
        if (!equals(event.eventId, row.getEventId())
                || !equals(event.eventType, row.getEventType())
                || event.schemaVersion != 1
                || !COMMENT_FACT_PRODUCER.equals(event.producer)
                || !equals(event.aggregateId, row.getAggregateId())
                || event.aggregateVersion != row.getFactVersion().longValue()) {
            return false;
        }
        if (event.operationId == null || event.operationId.isEmpty()) {
            return false;
        }
        // --- ③ 信封自身合法性
        Instant occurred = parseGoRfc3339(event.occurredAt);
        if (occurred == null) {
            return false;
        }
        CommentDomainFactService.CommentFact payload;
        try {
            payload = decodePayload(event);
        } catch (Exception e) {
            return false;
        }
        if (payload == null) {
            return false;
        }

        // --- ④ 载荷 ⇔ 信封交叉校验
        if (!COMMENT_SCHEMA_VERSION.equals(payload.schemaVersion)
                || !equals(payload.eventId, event.eventId)
                || !equals(payload.eventType, event.eventType)
                || !equals(payload.producer, event.producer)
                || !equals(payload.aggregateId, event.aggregateId)
                // ⚠️ 载荷里不得带版本号（版本号的唯一权威在信封）
                || payload.aggregateVersion != null
                || !equals(payload.operationId, event.operationId)
                || !equals(payload.commentId, event.aggregateId)
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

        // 🔴 2026-10-24 新增：Go 的 commentFact 里 EventTime / OccurredAt / AvailableAt
        //    三者都是 time.Time（domain_fact.go:77-79）⇒ encoding/json 解码**必须**是 RFC3339，
        //    否则 StrictDecode 直接失败 ⇒ 该行 BLOCKED。
        //    RAG 侧此前用 String 承载（为了线上字节一致），却**没有校验格式**
        //    ⇒ 畸形时间串（如 "not-a-time"）在 RAG 侧通过校验并被投递。
        //    ⇒ 这里补齐三个字段的 RFC3339 校验，与 Go 对齐。
        if (parseGoRfc3339(payload.eventTime) == null
                || parseGoRfc3339(payload.availableAt) == null) {
            return false;
        }

        // --- ⑤ 规范化哈希比对（jsonb 会重排键序 ⇒ 必须 JCS 规范化后比）
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
     * 严格解码信封内的 payload（与 Go 的 {@code StrictDecode(…event.Payload, &payload)} 同规则）。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDeliveryValidator.java:263-270}。
     */
    public CommentDomainFactService.CommentFact decodePayload(Event event) {
        try {
            String raw = objectMapper.writeValueAsString(event.payload);
            return strictDecode(new StringReader(raw), CommentDomainFactService.CommentFact.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 对应 Go {@code communityfact.StrictDecode}（{@code wire.go:121-131}）：
     * 禁止未知字段 + 禁止尾部多余 JSON。
     *
     * <p>⚠️ 用本类自带的 {@link #STRICT_MAPPER}（不是共享层的 {@code Wire.MAPPER}）：
     * 后者没禁 string→int 强转 ⇒ {@code "schema_version":"1"} 会被当成 1 放过去。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDeliveryValidator.java:279-287}。
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
     *   <li>{@code "2026-10-24T06:00:00Z"} ✅</li>
     *   <li>{@code "2026-10-24T06:00:00.123456789Z"} ✅</li>
     *   <li>{@code "2026-10-24T06:00:00+08:00"} ✅（等价于同一瞬时）</li>
     *   <li>{@code "2026-10-24 06:00:00"} ❌（缺 T 与偏移）</li>
     *   <li>{@code "not-a-time"} ❌</li>
     * </ul>
     *
     * <p>🔴 用 {@link DateTimeFormatter#ISO_OFFSET_DATE_TIME} 而不是 {@code Instant.parse}：
     * 前者接受 {@code Z} 与 {@code ±hh:mm} 两种偏移（Go 的 RFC3339 也接受两者），
     * 且要求偏移必须存在（Go 同样要求）⇒ 与 Go 的接受集合一致。
     *
     * <p>B 出处：{@code comment-rpc/…/rpc/service/CommentDeliveryValidator.java:307-316}。
     *
     * @return 解析成功返回瞬时；失败返回 {@code null}（调用方据此判非法）
     */
    private static Instant parseGoRfc3339(String value) {
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
