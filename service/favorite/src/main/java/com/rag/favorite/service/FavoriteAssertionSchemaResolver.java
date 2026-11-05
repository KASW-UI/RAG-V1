package com.rag.favorite.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rag.common.communityfact.CanonicalHash;
import com.rag.favorite.domain.entity.FavoriteFactOutboxEntity;
import com.rag.favorite.domain.entity.FavoriteItemEntity;
import com.rag.favorite.domain.entity.FavoriteLegacyFactMarkerEntity;
import com.rag.favorite.exception.FavoriteExceptions;
import com.rag.favorite.mapper.FavoriteFactOutboxMapper;
import com.rag.favorite.mapper.FavoriteLegacyFactMarkerMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 🔴 断言版本解析 —— 对应 Go
 * {@code service/favorite/rpc/internal/model/favorite_fact.go:212-265} 的
 * {@code favoriteAssertionSchema(tx, item)}。
 *
 * <p>这是本域<b>最难的一段</b>：54 行、两个分支，回答一个问题 ——
 * <blockquote>
 * 「这条收藏的 assert 事实用的是什么 schema？<b>或者</b>，它是否是一条已被
 * 迁移负责人逐字段签字的 pre-Outbox 旧数据（legacy）？」
 * </blockquote>
 *
 * <h2>为什么必须独立</h2>
 * <p>它被<b>两条事务路径</b>共用：{@code DeleteFavoriteByFavoriteId}（:316）与
 * {@code DeleteFolderCascade}（:358）。各写一份的后果是「删单条能过、删整个文件夹卡住」
 * 或反之 —— 这正是 Go 的 acceptance 资产
 * {@code FAVORITE_FACT_ACCEPTANCE.md:16} 专门覆盖的场景。
 *
 * <h2>两个分支</h2>
 * <h3>分支 A：找到 v1 断言（{@code favorite_fact.go:214,245-264}）</h3>
 * <p>strict-decode 信封并逐条校验 11 项，任何不符 ⇒ {@code ErrFavoriteFactMigrationBlocked}。
 * 通过则返回<b>该事件自己的 schema</b>（1 或 2）——
 * 🔴 撤回<b>永远继承</b>建立时冻结的 schema，与当前进程的开关无关
 * （Go {@code internal/model/favorite.go:16-18} 的注释明确说了这一点）。
 *
 * <h3>分支 B：没有 v1 断言 ⇒ legacy（{@code favorite_fact.go:216-243}）</h3>
 * <p>必须找到 marker 并逐条比对 7 个锚点 + hash 正则 + approval_ref 非空，
 * 且<b>不存在</b>孤儿 v2 撤回；任一不符 ⇒ {@code ErrFavoriteFactMigrationBlocked}。
 * 通过则返回 {@code schema = 0} 表示「无事实可继承」（legacy 分支不产撤回事件）。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.favorite.favorite.rpc.service} 压平为
 *           {@code com.rag.favorite.service}（D7）；</li>
 *       <li>实体 / Mapper / 异常的 import 由 {@code com.rag.favorite.favorite.rpc.*} 改为
 *           {@code com.rag.favorite.domain.entity} / {@code com.rag.favorite.mapper} /
 *           {@code com.rag.favorite.exception}（D7）；</li>
 *       <li>共享层 {@code Wire} 1:N 拆分 ⇒ {@code Wire.canonicalHash} →
 *           {@link CanonicalHash#canonicalHash(byte[])}；</li>
 *       <li>构造器改用 Lombok 的 {@code @RequiredArgsConstructor}（D18）⇒ 展开见下。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。两个分支的<b>判据顺序</b>（短路顺序）、
 *       legacy 分支的 7 个锚点 + hash 正则 + 孤儿撤回检查、
 *       「撤回继承冻结 schema」逐字未改。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/service/FavoriteAssertionSchemaResolver.java:47-245}
 *       （类声明 :47 · {@code SCHEMA_LEGACY} :52 · {@code RECEIPT_HASH} :55-56 ·
 *       字段与构造器 :58-71 · {@code resolve} :83-92 · {@code resolveFromFrozenAssert} :97-152 ·
 *       {@code resolveLegacy} :157-215 · {@code blocked} :217-222 ·
 *       {@code blockedLegacy} :224-229 · {@code jcsHash} :232-238）。</li>
 * </ol>
 *
 * <h2>⚠️ 日志：<b>不用</b> {@code @Slf4j}，也<b>不用</b> {@code GoStyleLogger}</h2>
 * 本仓的日志契约走 {@code common.logger.GoStyleLogger}（带 B 侧 {@code call_chain} 字段 · A8），
 * <b>禁止</b> Lombok 的 {@code @Slf4j}（D18 / §1.0e 明列）。
 * <br>⚠️ 但本类<b>刻意</b>用<b>普通 slf4j</b>（{@code LoggerFactory.getLogger}）而不是
 * {@code GoStyleLogger}：B 侧本类<b>就是</b>
 * {@code LoggerFactory.getLogger(FavoriteAssertionSchemaResolver.class)}
 * （B 侧 {@code :49}），只调用 {@code LOG.warn(...)} 两处，<b>没有</b>任何
 * {@code logBusinessErr} / {@code call_chain} 语义的调用点
 * ⇒ ⇒ 换成 {@code GoStyleLogger} 属于<b>凭空新增</b>日志字段（表达层变更且无 B 侧依据）
 * ⇒ ⇒ <b>照抄普通 slf4j</b>，这是「零无语义差异」的最小改法。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * private final FavoriteFactOutboxMapper outboxMapper;
 * private final FavoriteLegacyFactMarkerMapper markerMapper;
 * private final FavoriteDeliveryValidator deliveryValidator;
 * private final FavoriteFactEnvelopeFactory envelopeFactory;
 *
 * public FavoriteAssertionSchemaResolver(FavoriteFactOutboxMapper outboxMapper,
 *                                        FavoriteLegacyFactMarkerMapper markerMapper,
 *                                        FavoriteDeliveryValidator deliveryValidator,
 *                                        FavoriteFactEnvelopeFactory envelopeFactory) {
 *     this.outboxMapper = outboxMapper;
 *     this.markerMapper = markerMapper;
 *     this.deliveryValidator = deliveryValidator;
 *     this.envelopeFactory = envelopeFactory;
 * }
 * }</pre>
 *
 * <p>⚠️ 字段声明顺序 = B 侧构造器的参数顺序 ⇒ Lombok 生成的构造器签名逐条一致。
 */
@Component
@RequiredArgsConstructor
public class FavoriteAssertionSchemaResolver {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteAssertionSchemaResolver.class);

    /** 分支 B 的返回值标记：legacy 行<b>没有</b> schema 可继承（Go 返回 {@code schema = 0}）。 */
    public static final int SCHEMA_LEGACY = 0;

    /** Go {@code favorite_delivery.go:20} {@code favoriteReceiptHash = ^[a-f0-9]{64}$}。 */
    private static final java.util.regex.Pattern RECEIPT_HASH =
            java.util.regex.Pattern.compile("^[a-f0-9]{64}$");

    /**
     * ⚠️ <b>字段声明顺序 = B 侧构造器的参数顺序</b> ⇒ Lombok 的
     * {@code @RequiredArgsConstructor} 生成的构造器签名与 B 侧逐条一致
     * （展开块见类 javadoc）。
     */
    private final FavoriteFactOutboxMapper outboxMapper;
    private final FavoriteLegacyFactMarkerMapper markerMapper;
    private final FavoriteDeliveryValidator deliveryValidator;
    private final FavoriteFactEnvelopeFactory envelopeFactory;

    /**
     * 对应 Go {@code favoriteAssertionSchema(tx, item)}（favorite_fact.go:212-265）。
     *
     * <p>⚠️ <b>必须在事务内调用</b>：两个分支都依赖
     * {@code SELECT ... FOR UPDATE} 建立的行锁
     * （assert 行 / marker 行），脱离事务会立刻失效。
     *
     * @return 冻结事件的 schema（1 或 2），或 {@link #SCHEMA_LEGACY} 表示 legacy 行
     * @throws FavoriteExceptions.FactMigrationBlockedException 任一校验不通过
     */
    public int resolve(FavoriteItemEntity item) {
        long favoriteId = item.getFavoriteId();

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:214-215 —— 找 v1 断言（FOR UPDATE）
        FavoriteFactOutboxEntity row = outboxMapper.lockAssertByFavoriteId(favoriteId);
        if (row == null) {
            return resolveLegacy(item);
        }
        return resolveFromFrozenAssert(row, item);
    }

    /**
     * 分支 A —— Go {@code favorite_fact.go:245-264} 的 11 项交叉校验。
     */
    private int resolveFromFrozenAssert(FavoriteFactOutboxEntity row, FavoriteItemEntity item) {
        // Go: service/favorite/rpc/internal/model/favorite_fact.go:246-251
        //   if strictFavoriteJSON([]byte(row.Payload), &event) != nil ||
        //      event.EventID != row.EventID || event.AggregateVersion != 1 ||
        //      event.EventType != "rtw.favorite.assert" ||
        //      event.AggregateID != strconv.FormatInt(item.FavoriteId, 10) ||
        //      (event.SchemaVersion != 1 && event.SchemaVersion != 2) ||
        //      !validFavoriteDelivery(row, event) { → ErrFavoriteFactMigrationBlocked }
        FavoriteDeliveryValidator.FrozenEvent event;
        try {
            event = FavoriteDeliveryValidator.decodeStrict(row.getPayload(), FavoriteDeliveryValidator.FrozenEvent.class);
        } catch (RuntimeException e) {
            throw blocked(row, item, "assert envelope is not strict-decodable", e);
        }
        if (event == null
                || !row.getEventId().equals(event.eventId)
                || event.aggregateVersion != 1L
                || !"rtw.favorite.assert".equals(event.eventType)
                || !FavoriteFactEnvelopeFactory.decimal(item.getFavoriteId()).equals(event.aggregateId)
                || (event.schemaVersion != 1 && event.schemaVersion != 2)
                || !deliveryValidator.validDelivery(row, event)) {
            throw blocked(row, item, "assert envelope does not match its outbox row", null);
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:253-263
        //   var payload authorityPayload
        //   if strictFavoriteJSON(event.Payload, &payload) != nil { → blocked }
        //   subject, ok := favoriteSubjectV1(event.SchemaVersion, payload.Subject)
        //   folderID, folderOK := parseAuthorityID(payload.FolderID)
        //   if !ok || !folderOK || subject.SubjectID != strconv.FormatInt(item.UserId, 10) ||
        //      folderID != item.FolderId || payload.TargetType != item.TargetType ||
        //      payload.TargetID != item.TargetId ||
        //      !sameFavoriteRevision(payload.TargetRevision, item.TargetRevision) { → blocked }
        FavoriteDeliveryValidator.AuthorityPayload payload;
        try {
            payload = FavoriteDeliveryValidator.decodeStrict(event.payload.toString(),
                    FavoriteDeliveryValidator.AuthorityPayload.class);
        } catch (RuntimeException e) {
            throw blocked(row, item, "assert payload is not strict-decodable", e);
        }
        FavoriteDeliveryValidator.SubjectResult subject =
                FavoriteDeliveryValidator.subjectV1(event.schemaVersion, payload.subject);
        long folderId = FavoriteDeliveryValidator.parseAuthorityId(payload.folderId);

        if (!subject.ok
                || folderId != item.getFolderId()
                || !FavoriteFactEnvelopeFactory.decimal(item.getUserId()).equals(subject.value.subjectId)
                || !item.getTargetType().equals(payload.targetType)
                || !item.getTargetId().equals(payload.targetId)
                || !FavoriteDeliveryValidator.sameFavoriteRevision(payload.targetRevision, item.getTargetRevision())) {
            throw blocked(row, item, "assert payload disagrees with the live business row", null);
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:264 —— return event.SchemaVersion, false, nil
        return event.schemaVersion;
    }

    /**
     * 分支 B —— Go {@code favorite_fact.go:216-243}：legacy 标记的 7 项 + hash + 孤儿检查。
     */
    private int resolveLegacy(FavoriteItemEntity item) {
        long favoriteId = item.getFavoriteId();

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:220-226
        //   var marker FavoriteLegacyFactMarker
        //   if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
        //        Where("favorite_id = ?", item.FavoriteId).Take(&marker).Error; err != nil {
        //       if errors.Is(err, gorm.ErrRecordNotFound) { return 0, false, ErrFavoriteFactMigrationBlocked }
        //       return 0, false, err
        //   }
        // ⚠️ 「表不存在」时这里抛出的<b>不是</b> NotFound ⇒ Go 返回普通 DB error
        //   ⇒ logic 输出 Internal/5008 而不是 FailedPrecondition/5304。
        //   本实现保留该结构：DB 异常自然冒泡成普通错误，NotFound 才转 blocked。
        FavoriteLegacyFactMarkerEntity marker = markerMapper.lockByFavoriteId(favoriteId);
        if (marker == null) {
            throw blockedLegacy(item, "no legacy marker approved for this row", null);
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:227-234
        //   snapshotHash, hashErr := favoriteLegacySnapshotHash(item)
        //   if hashErr != nil || marker.FavoriteID != item.FavoriteId || marker.UserID != item.UserId ||
        //      marker.FolderID != item.FolderId || marker.TargetType != item.TargetType ||
        //      marker.TargetID != item.TargetId ||
        //      !sameFavoriteRevision(marker.TargetRevision, item.TargetRevision) ||
        //      !favoriteReceiptHash.MatchString(marker.SourceSnapshotSHA256) ||
        //      marker.SourceSnapshotSHA256 != snapshotHash || marker.ApprovalRef == "" { → blocked }
        String snapshotHash;
        try {
            snapshotHash = envelopeFactory.legacySnapshotHash(item);
        } catch (RuntimeException e) {
            throw blockedLegacy(item, "legacy snapshot hash could not be computed", e);
        }
        if (marker.getFavoriteId() != favoriteId
                || !item.getUserId().equals(marker.getUserId())
                || !item.getFolderId().equals(marker.getFolderId())
                || !item.getTargetType().equals(marker.getTargetType())
                || !item.getTargetId().equals(marker.getTargetId())
                || !FavoriteDeliveryValidator.sameFavoriteRevision(
                        marker.getTargetRevision(), item.getTargetRevision())
                || marker.getSourceSnapshotSha256() == null
                || !RECEIPT_HASH.matcher(marker.getSourceSnapshotSha256()).matches()
                || !snapshotHash.equals(marker.getSourceSnapshotSha256())
                || marker.getApprovalRef() == null
                || marker.getApprovalRef().isEmpty()) {
            throw blockedLegacy(item, "legacy marker anchors do not match the live business row", null);
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:235-242 —— 已有 v2 撤回 ⇒ 这条收藏撤过一次，标记不能让它再撤一次
        //   var orphanCount int64
        //   tx.Model(&FavoriteFactOutbox{}).
        //      Where("favorite_id = ? AND aggregate_version = 2", item.FavoriteId).Count(&orphanCount)
        //   if orphanCount != 0 { → blocked }
        if (outboxMapper.countRetractsByFavoriteId(favoriteId) != 0L) {
            throw blockedLegacy(item, "an orphan retract already exists for this favorite", null);
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:243 —— return 0, true, nil（legacy，无需产撤回事实）
        return SCHEMA_LEGACY;
    }

    /**
     * ⚠️ <b>B 侧既有缺陷，本实现照抄</b>：{@code cause} 参数被接收但<b>没有</b>挂到
     * 抛出的异常上（B 侧 {@code FavoriteAssertionSchemaResolver.java:217-222} 同款）。
     * ⇒ 解码失败时<b>丢掉了原始异常</b>，排障只能靠 {@code reason} 文案。
     * <br>⚠️ <b>不要"顺手修"</b>：挂上 cause 会改变 {@code getCause()}（可观察）
     * ⇒ 属 AGENTS.md §1.0「B 侧已有的差异一律继承」的范畴。
     */
    private FavoriteExceptions.FactMigrationBlockedException blocked(
            FavoriteFactOutboxEntity row, FavoriteItemEntity item, String reason, Throwable cause) {
        LOG.warn("favorite retract blocked: favorite_id={} event_id={} reason={}",
                item.getFavoriteId(), row.getEventId(), reason);
        return new FavoriteExceptions.FactMigrationBlockedException();
    }

    /**
     * ⚠️ <b>B 侧既有缺陷，本实现照抄</b>：{@code cause} 参数被接收但<b>没有</b>挂到
     * 抛出的异常上（B 侧 {@code FavoriteAssertionSchemaResolver.java:224-229} 同款）。
     */
    private FavoriteExceptions.FactMigrationBlockedException blockedLegacy(
            FavoriteItemEntity item, String reason, Throwable cause) {
        LOG.warn("favorite legacy retract blocked: favorite_id={} reason={}",
                item.getFavoriteId(), reason);
        return new FavoriteExceptions.FactMigrationBlockedException();
    }

    /** 供权威读路径复用的 JCS hash（Go {@code favoriteJCSHash}）。 */
    static String jcsHash(String raw) {
        try {
            return CanonicalHash.canonicalHash(raw.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("compute favorite fact JCS hash", e);
        }
    }

    /** 保留类型引用：权威读路径会用到的 payload 节点类型。 */
    @SuppressWarnings("unused")
    private static Class<?> payloadNodeType() {
        return JsonNode.class;
    }
}
