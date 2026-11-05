package com.rag.favorite.service;

import com.rag.common.communityfact.CanonicalHash;
import com.rag.common.communityfact.DeliveryStatus;
import com.rag.favorite.domain.entity.FavoriteFactOutboxEntity;
import com.rag.favorite.domain.entity.FavoriteItemEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 🔴 冻结信封的<b>单点构造</b> —— 对应 Go
 * {@code service/favorite/rpc/internal/model/favorite_fact.go} 的
 * {@code favoriteOutbox}（:135-162）、{@code favoriteOutboxV2}（:164-198）、
 * {@code newFavoriteOutbox}（:200-210）与
 * {@code favoriteLegacySnapshotHash}（:44-58）。
 *
 * <h2>为什么必须单点构造</h2>
 *
 * <p>Go 的注释说得很清楚（favorite_fact.go:178-179）：
 * <pre>
 * // The outer v2 schema is explicit as well. There is still exactly one
 * // frozen event for each aggregate version and one DC idempotency key.
 * </pre>
 *
 * <p>v1 与 v2 的 payload <b>只有 {@code subject_ref} 不同</b>，其余 11 字段逐字相同。
 * 若在两个事务里各写一遍 JSON 构造，任何一侧被后人改动就会让
 * 「同一条收藏的 assert 与 retract 用不同形状」⇒ {@code validFavoriteDelivery} 失败
 * ⇒ 事实被判 blocked。
 *
 * <h2>🔴 业务 ID 一律编码成十进制<b>字符串</b></h2>
 *
 * <p>Go 的注释（favorite_fact.go:102-103）解释了原因：
 * <pre>
 * // Business IDs are decimal strings: DC's JCS input hash rejects JSON
 * // integers above 2^53, while production Snowflake IDs exceed that range.
 * </pre>
 *
 * <p>⇒ {@code payload.favorite_id} / {@code payload.folder_id} / {@code payload.subject_ref.subject_id} /
 * {@code envelope.aggregate_id} <b>四个位置</b>都必须是 {@code strconv.FormatInt(id, 10)}。
 *
 * <p>⚠️ <b>不能用「让 JSON 序列化器自己渲染 long」</b>（docs/FileTree/favorite/rpc.md 的 U5）：
 * 那样会让 {@code aggregate_id} 与 {@code payload.favorite_id} 走两条不同的渲染路径，
 * 而 {@code validFavoriteDelivery} 要逐字比对它们
 * （favorite_delivery.go:98 的 {@code event.AggregateID != fmt.Sprint(row.FavoriteID)}）。
 * 本类<b>显式</b>调 {@link #decimal(long)} 构造全部四个位置，不依赖任何默认渲染。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.favorite.favorite.rpc.service} 压平为
 *           {@code com.rag.favorite.service}（D7）；</li>
 *       <li>实体的 import 由 {@code com.rag.favorite.favorite.rpc.domain} 改为
 *           {@code com.rag.favorite.domain.entity}（D7）；</li>
 *       <li>共享层 {@code Wire} 1:N 拆分 ⇒ {@code Wire.DeliveryStatus} → 顶层
 *           {@link DeliveryStatus}、{@code Wire.canonicalHash} →
 *           {@link CanonicalHash#canonicalHash(byte[])}。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。payload / 信封的<b>字段顺序</b>（{@code LinkedHashMap}
 *       的插入序 = Go struct 声明序）、两个 schema 分支的逐条差异、
 *       {@code RFC3339Nano} 的「去尾随零」渲染规则逐字未改。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/service/FavoriteFactEnvelopeFactory.java:53-346}
 *       （类声明 :53 · {@code assertBuildable} :82-94 · {@code build} :108-119 ·
 *       {@code buildV1} :122-157 · {@code buildV2} :160-195 · {@code subjectRefV1} :201-207 ·
 *       {@code subjectRefV2} :217-222 · {@code eventId} :231-233 · {@code outboxRow} :236-246 ·
 *       {@code legacySnapshotHash} :263-273 · {@code decimal} :280-282 ·
 *       {@code rfc3339Nano} :305-319 · {@code serialize} :326-332 · {@code jcsHash} :335-341）。</li>
 * </ol>
 */
@Component
public class FavoriteFactEnvelopeFactory {

    /** Go {@code favorite_authority.go:19}：{@code const favoriteProducer = "rtw.community.favorite"}。 */
    public static final String PRODUCER = "rtw.community.favorite";

    /** Go {@code favorite_fact.go:78-82} {@code FavoriteSubjectRef.AuthorityID}。 */
    private static final String SUBJECT_AUTHORITY_ID = "rtw.identity";

    /** Go {@code favorite_fact.go:80} {@code FavoriteSubjectRef.TenantID}。 */
    private static final String SUBJECT_TENANT_ID = "platform";

    // ===== Go favorite_fact.go:135-140 / 164-169 的 6 条前置断言 =====

    /**
     * 🔴 对应 Go {@code favoriteOutbox} / {@code favoriteOutboxV2} 的前置断言
     * （favorite_fact.go:136-139）：
     * <pre>
     * if item.FavoriteId &lt;= 0 || item.FolderId &lt;= 0 || item.UserId &lt;= 0 ||
     *    item.TargetType == "" || item.TargetId == "" ||
     *    (version != 1 &amp;&amp; version != 2) || (operation != "assert" &amp;&amp; operation != "retract") ||
     *    (item.TargetRevision != nil &amp;&amp; (item.TargetType != "article" || *item.TargetRevision == "")) {
     *     return FavoriteFactOutbox{}, errors.New("invalid favorite fact")
     * }
     * </pre>
     *
     * <p>这 6 条是<b>应用层</b>的第二道防线（第一道是 DDL 的 CHECK）。
     * 任何一条不满足都必须<b>抛异常让事务回滚</b> —— Go 侧是返回 error，
     * 而返回 error 会让 {@code m.conn.Transaction(...)} 回滚。
     */
    private static void assertBuildable(FavoriteItemEntity item, long version, String operation) {
        if (item == null
                || item.getFavoriteId() == null || item.getFavoriteId() <= 0
                || item.getFolderId() == null || item.getFolderId() <= 0
                || item.getUserId() == null || item.getUserId() <= 0
                || isBlank(item.getTargetType()) || isBlank(item.getTargetId())
                || (version != 1 && version != 2)
                || (!"assert".equals(operation) && !"retract".equals(operation))
                || (item.getTargetRevision() != null
                    && (!"article".equals(item.getTargetType()) || item.getTargetRevision().isEmpty()))) {
            throw new IllegalArgumentException("invalid favorite fact");
        }
    }

    /**
     * 🔴 对应 Go {@code newFavoriteOutbox}（favorite_fact.go:200-210）的分派：
     * <pre>
     * switch schema {
     * case 1: return favoriteOutbox(item, version, operation, now)
     * case 2: return favoriteOutboxV2(item, version, operation, now)
     * default: return FavoriteFactOutbox{}, errors.New("unsupported favorite fact schema")
     * }
     * </pre>
     *
     * @param schema 1 = v1（{@code subject_ref} 三字段）；2 = v2（两字段，无 tenant）
     */
    public FavoriteFactOutboxEntity build(FavoriteItemEntity item, long version, String operation,
                                          Instant now, int schema) {
        assertBuildable(item, version, operation);
        switch (schema) {
            case 1:
                return buildV1(item, version, operation, now);
            case 2:
                return buildV2(item, version, operation, now);
            default:
                throw new IllegalArgumentException("unsupported favorite fact schema");
        }
    }

    /** 对应 Go {@code favoriteOutbox}（favorite_fact.go:135-162）。 */
    private FavoriteFactOutboxEntity buildV1(FavoriteItemEntity item, long version, String operation, Instant now) {
        String eventId = eventId(item.getFavoriteId(), version);
        String at = rfc3339Nano(now);
        String favoriteId = decimal(item.getFavoriteId());
        String userId = decimal(item.getUserId());
        String folderId = decimal(item.getFolderId());

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:147-154 —— payload 的字段顺序逐条照抄 struct 声明顺序
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1);
        payload.put("event_id", eventId);
        payload.put("subject_ref", subjectRefV1(userId));
        payload.put("target_type", item.getTargetType());
        payload.put("target_id", item.getTargetId());
        payload.put("target_revision", item.getTargetRevision());   // null 时写 JSON null
        payload.put("operation", operation);
        payload.put("source_ref", "rtw.favorite/" + favoriteId);
        payload.put("event_time", at);
        payload.put("available_at", at);
        payload.put("favorite_id", favoriteId);
        payload.put("folder_id", folderId);

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:143-155 —— 外层信封
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", eventId);
        event.put("event_type", "rtw.favorite." + operation);
        event.put("schema_version", 1);
        event.put("producer", PRODUCER);
        event.put("aggregate_id", favoriteId);
        event.put("aggregate_version", version);
        event.put("operation_id", eventId);
        event.put("occurred_at", at);
        event.put("payload", payload);

        return outboxRow(eventId, item.getFavoriteId(), version, event);
    }

    /** 对应 Go {@code favoriteOutboxV2}（favorite_fact.go:164-198）。 */
    private FavoriteFactOutboxEntity buildV2(FavoriteItemEntity item, long version, String operation, Instant now) {
        String eventId = eventId(item.getFavoriteId(), version);
        String at = rfc3339Nano(now);
        String favoriteId = decimal(item.getFavoriteId());
        String userId = decimal(item.getUserId());
        String folderId = decimal(item.getFolderId());

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:172-177 —— 与 v1 逐字相同，唯一差别是 subject_ref
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 2);
        payload.put("event_id", eventId);
        payload.put("subject_ref", subjectRefV2(userId));
        payload.put("target_type", item.getTargetType());
        payload.put("target_id", item.getTargetId());
        payload.put("target_revision", item.getTargetRevision());
        payload.put("operation", operation);
        payload.put("source_ref", "rtw.favorite/" + favoriteId);
        payload.put("event_time", at);
        payload.put("available_at", at);
        payload.put("favorite_id", favoriteId);
        payload.put("folder_id", folderId);

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:180-191 —— 外层 schema_version 也是 2（v1 写的是 1）
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", eventId);
        event.put("event_type", "rtw.favorite." + operation);
        event.put("schema_version", 2);
        event.put("producer", PRODUCER);
        event.put("aggregate_id", favoriteId);
        event.put("aggregate_version", version);
        event.put("operation_id", eventId);
        event.put("occurred_at", at);
        event.put("payload", payload);

        return outboxRow(eventId, item.getFavoriteId(), version, event);
    }

    /**
     * Go {@code FavoriteSubjectRef}（favorite_fact.go:149）：
     * {@code FavoriteSubjectRef{"rtw.identity", "platform", strconv.FormatInt(item.UserId, 10)}}。
     */
    private static Map<String, Object> subjectRefV1(String userId) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("authority_id", SUBJECT_AUTHORITY_ID);
        ref.put("tenant_id", SUBJECT_TENANT_ID);
        ref.put("subject_id", userId);
        return ref;
    }

    /**
     * Go {@code FavoriteSubjectRefV2}（favorite_fact.go:173）：
     * {@code FavoriteSubjectRefV2{Issuer: "rtw.identity", SubjectID: strconv.FormatInt(item.UserId, 10)}}。
     *
     * <p>⚠️ <b>刻意没有 {@code tenant_id}</b>（favorite_fact.go:84-85 的注释：
     * "The legacy fixed platform slot is deliberately absent from the v2 EventSpec."）。
     * 多写一个字段会让 {@code strictFavoriteJSON} 因<b>未知字段</b>拒绝整条事实。
     */
    private static Map<String, Object> subjectRefV2(String userId) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("issuer", SUBJECT_AUTHORITY_ID);
        ref.put("subject_id", userId);
        return ref;
    }

    /**
     * Go {@code eventID := fmt.Sprintf("favorite.%d.v%d", item.FavoriteId, version)}
     * （favorite_fact.go:141 / 170）。
     *
     * <p>⚠️ 这个串同时是：outbox 主键、DC 的 {@code Idempotency-Key}、
     * {@code operation_id}。改格式会让所有历史事件无法被权威读定位。
     */
    private static String eventId(long favoriteId, long version) {
        return "favorite." + favoriteId + ".v" + version;
    }

    /** Go {@code favorite_fact.go:160-161} / {@code 196-197} 的返回体。 */
    private static FavoriteFactOutboxEntity outboxRow(String eventId, long favoriteId, long version,
                                                      Map<String, Object> event) {
        FavoriteFactOutboxEntity row = new FavoriteFactOutboxEntity();
        row.setEventId(eventId);
        row.setFavoriteId(favoriteId);
        row.setAggregateVersion(version);
        row.setPayload(serialize(event));
        row.setStatus(DeliveryStatus.DELIVERY_PENDING.code());   // Go: Status: FavoriteFactPending
        row.setRetryCount(0);
        return row;
    }

    /**
     * 🔴 对应 Go {@code favoriteLegacySnapshotHash}（favorite_fact.go:44-58）。
     *
     * <p>它算的是「这条业务行的不可变快照」的 JCS+SHA-256，作为
     * legacy 标记的 {@code source_snapshot_sha256}，供
     * {@code FavoriteAssertionSchemaResolver} 逐条比对。
     *
     * <p>⚠️ <b>刻意不含 title / cover</b>（Go 注释：{@code "Mutable title/cover are
     * intentionally outside this key."}）—— 纳入哈希会让每次改名都要重批标记。
     *
     * <p>⚠️ <b>ID 必须是十进制字符串</b>（Go 注释：{@code "IDs are strings so the JCS digest
     * remains exact for large Snowflake values."}）。
     *
     * @param targetRevision 可空；null 写成 JSON {@code null}（Go 的 {@code *string}）
     */
    public String legacySnapshotHash(FavoriteItemEntity item) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        // 字段顺序逐条照抄 Go 的匿名 struct（favorite_fact.go:45-53）
        snapshot.put("favorite_id", decimal(item.getFavoriteId()));
        snapshot.put("user_id", decimal(item.getUserId()));
        snapshot.put("folder_id", decimal(item.getFolderId()));
        snapshot.put("target_type", item.getTargetType());
        snapshot.put("target_id", item.getTargetId());
        snapshot.put("target_revision", item.getTargetRevision());
        return jcsHash(serialize(snapshot));
    }

    // ==================== 序列化与格式化原语 ====================

    /**
     * {@code strconv.FormatInt(id, 10)} 的等价物 —— 本域<b>所有</b>业务 ID 的唯一渲染入口。
     */
    public static String decimal(long id) {
        return Long.toString(id, 10);
    }

    /**
     * 🔴 Go {@code now.UTC().Format(time.RFC3339Nano)} 的<b>逐字等价物</b>。
     *
     * <p>为什么不能直接用 {@code instant.toString()} 或 {@code ISO_INSTANT}：
     * Go 的 {@code RFC3339Nano} 版式是 {@code "2006-01-02T15:04:05.999999999Z07:00"}，
     * 其中 {@code 9} 的语义是「<b>去掉尾随零</b>，且全零时<b>整个小数部分不输出</b>」：
     * <pre>
     * 500ms      → 2026-10-23T10:00:00.5Z        （Java 的 Instant.toString 会给 .500）
     * 整秒        → 2026-10-23T10:00:00Z          （Java 的 Instant.toString 也给这个）
     * </pre>
     *
     * <p>⚠️ 这个差异<b>不会</b>被 JCS 归一化掉（JCS 只处理 JSON 结构，不重写字符串内容），
     * 而 {@code authoritativeFavoriteRow} 会做<b>逐字</b>比较
     * （favorite_authority.go:146：
     * {@code payload.EventTime != event.OccurredAt || payload.AvailableAt != event.OccurredAt}）——
     * 好在那个比较是<b>同一份 payload 内部</b>的三处自洽比较，所以只要本方法
     * 对三个时间戳<b>产出同一格式</b>即可。
     * <p>但格式仍必须与 Go 一致：{@code input_hash} 是跨进程（DC）比对的，
     * 而 {@code received_at} 会被回写进 {@code favorite_fact_outbox.technical_received_at}
     * 再被权威读重新格式化输出给下游。
     */
    public static String rfc3339Nano(Instant instant) {
        String base = RFC3339_SECONDS.format(instant);
        int nano = instant.getNano();
        if (nano == 0) {
            return base + "Z";
        }
        StringBuilder fraction = new StringBuilder(9);
        String digits = String.format("%09d", nano);
        int end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') {
            end--;
        }
        fraction.append(digits, 0, end);
        return base + "." + fraction + "Z";
    }

    private static final DateTimeFormatter RFC3339_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** {@code json.Marshal} 的等价物；失败按 Go 侧的「构造失败 ⇒ 事务回滚」处理
     *     （{@code service/favorite/rpc/internal/model/favorite_fact.go:156}）。 */
    private static String serialize(Map<String, Object> value) {
        try {
            return FavoriteRpcSupport.STRICT_JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("marshal favorite fact envelope", e);
        }
    }

    /** Go {@code favoriteJCSHash}（favorite_authority.go:179-186）的等价物
     *  ⇒ 复用共享层的 {@link CanonicalHash#canonicalHash(byte[])}。 */
    private static String jcsHash(String raw) {
        try {
            return CanonicalHash.canonicalHash(raw.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("compute favorite fact JCS hash", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
