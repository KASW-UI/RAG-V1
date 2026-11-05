package com.rag.favorite.service;

import com.rag.common.communityfact.AuthorityFact;
import com.rag.common.communityfact.CanonicalHash;
import com.rag.common.communityfact.DeliveryStatus;
import com.rag.common.communityfact.TechnicalReceipt;
import com.rag.favorite.domain.entity.FavoriteFactOutboxEntity;
import com.rag.favorite.domain.entity.FavoriteItemEntity;
import com.rag.favorite.domain.model.FavoriteAuthorityFact;
import com.rag.favorite.exception.FavoriteExceptions;
import com.rag.favorite.mapper.FavoriteFactOutboxMapper;
import com.rag.favorite.mapper.FavoriteItemMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 🔴 权威读的 17 道校验 —— 对应 Go
 * {@code service/favorite/rpc/internal/model/favorite_authority.go:126-282} 的
 * {@code authoritativeFavoriteRow} 与 {@code authoritativeFavoriteFact}。
 *
 * <p><b>它回答什么</b>：给定 {@code (producer, event_id)}，能否证明「这条事实
 * 确实由 RTW 冻结发出、确实被 DC 技术接受、且内容与当时的业务行一致」？
 *
 * <p>Go 的注释说得很清楚（favorite_authority.go:24-26）：
 * <pre>
 * // FavoriteAuthorityFact is source-owned evidence for a downstream binder. A DC
 * // batch alone cannot authorize a subject: the immutable RTW outbox and the
 * // source-recorded DC technical receipt must both agree with its input hash.
 * </pre>
 * ⇒ <b>DC 的批次本身不足以授权</b>：RTW 侧的不可变 outbox 与 DC 回执必须就
 * {@code input_hash} 达成一致。这就是本类 17 道校验的意义。
 *
 * <h2>两个公开入口</h2>
 * <ul>
 *   <li>{@link #authoritativeFactV1} —— {@code AuthoritativeFavoriteFact}，期望 schema 1。
 *       响应体的 {@code subject_ref} 是<b>三字段</b>（见 {@link FavoriteAuthorityFact}）。</li>
 *   <li>{@link #authoritativeFactV2} —— {@code AuthoritativeFavoriteFactV2}，期望 schema 2。
 *       响应体的 {@code subject_ref} 是<b>两字段</b>（可直接用共享层的 {@link AuthorityFact}）。</li>
 * </ul>
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.favorite.favorite.rpc.service} 压平为
 *           {@code com.rag.favorite.service}（D7）；</li>
 *       <li>实体 / 模型 / Mapper / 异常的 import 由 {@code com.rag.favorite.favorite.rpc.*} 改为
 *           {@code com.rag.favorite.domain.entity} / {@code com.rag.favorite.domain.model} /
 *           {@code com.rag.favorite.mapper} / {@code com.rag.favorite.exception}（D7）；</li>
 *       <li>共享层 {@code Wire} 1:N 拆分 ⇒ {@code Wire.AuthorityFact} → 顶层 {@link AuthorityFact}、
 *           {@code Wire.TechnicalReceipt} → 顶层 {@link TechnicalReceipt}、
 *           {@code Wire.DeliveryStatus} → 顶层 {@link DeliveryStatus}、
 *           {@code Wire.canonicalHash} → {@link CanonicalHash#canonicalHash(byte[])}；</li>
 *       <li>构造器改用 Lombok 的 {@code @RequiredArgsConstructor}（D18）⇒ 展开见下。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。17 道行校验的<b>顺序</b>、v1 / v2 两条路径的分流、
 *       「前驱必须先于撤回」（offset 递增）、前驱与撤回的
 *       subject / target / folder / source_ref / revision 五项一致性逐条未改。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/service/FavoriteAuthorityLookupService.java:44-384}
 *       （类声明 :44 · {@code RECEIPT_HASH} :49 · 字段与构造器 :51-61 ·
 *       {@code AuthoritativeRow} :64-74 · {@code authoritativeFactV1} :84-86 ·
 *       {@code authoritativeFactV2} :100-107 · {@code resolve} :141-233 ·
 *       {@code authoritativeRow} :239-335 · {@code isVersion2} :339-341 ·
 *       {@code parseSubjectId} :343-349 · {@code unavailable} :351-361 ·
 *       {@code FavoriteItemSnapshot} :364-366 · {@code liveRow} :376-383）。</li>
 * </ol>
 *
 * <h2>⚠️ 日志：<b>不用</b> {@code @Slf4j}，也<b>不用</b> {@code GoStyleLogger}</h2>
 * B 侧本类<b>就是</b> {@code LoggerFactory.getLogger(FavoriteAuthorityLookupService.class)}
 * （B 侧 {@code :46}），只调用 {@code LOG.atWarn()} 一处，<b>没有</b>任何
 * {@code logBusinessErr} / {@code call_chain} 语义的调用点
 * ⇒ ⇒ 换成 {@code GoStyleLogger} 属于<b>凭空新增</b>日志字段（表达层变更且无 B 侧依据）
 * ⇒ ⇒ <b>照抄普通 slf4j</b>（同 {@code ArticleDomainService} 的先例）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * private final FavoriteFactOutboxMapper outboxMapper;
 * private final FavoriteItemMapper favoriteItemMapper;
 * private final FavoriteDeliveryValidator deliveryValidator;
 *
 * public FavoriteAuthorityLookupService(FavoriteFactOutboxMapper outboxMapper,
 *                                      FavoriteItemMapper favoriteItemMapper,
 *                                      FavoriteDeliveryValidator deliveryValidator) {
 *     this.outboxMapper = outboxMapper;
 *     this.favoriteItemMapper = favoriteItemMapper;
 *     this.deliveryValidator = deliveryValidator;
 * }
 * }</pre>
 *
 * <p>⚠️ 字段声明顺序 = B 侧构造器的参数顺序 ⇒ Lombok 生成的构造器签名逐条一致。
 */
@Service
@RequiredArgsConstructor
public class FavoriteAuthorityLookupService {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteAuthorityLookupService.class);

    /** Go {@code favorite_delivery.go:20} {@code favoriteReceiptHash = ^[a-f0-9]{64}$}。 */
    private static final Pattern RECEIPT_HASH = Pattern.compile("^[a-f0-9]{64}$");

    /**
     * ⚠️ <b>字段声明顺序 = B 侧构造器的参数顺序</b> ⇒ Lombok 的
     * {@code @RequiredArgsConstructor} 生成的构造器签名与 B 侧逐条一致
     * （展开块见类 javadoc）。
     */
    private final FavoriteFactOutboxMapper outboxMapper;
    private final FavoriteItemMapper favoriteItemMapper;
    private final FavoriteDeliveryValidator deliveryValidator;

    /**
     * {@code authoritativeRow} 的产物：权威事实 + 它解析出的 payload。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * final FavoriteAuthorityFact.FactV1 fact;
     * final FavoriteDeliveryValidator.AuthorityPayload payload;
     *
     * AuthoritativeRow(FavoriteAuthorityFact.FactV1 fact,
     *                  FavoriteDeliveryValidator.AuthorityPayload payload) {
     *     this.fact = fact;
     *     this.payload = payload;
     * }
     * }</pre>
     */
    @RequiredArgsConstructor
    private static final class AuthoritativeRow {

        final FavoriteAuthorityFact.FactV1 fact;
        final FavoriteDeliveryValidator.AuthorityPayload payload;
    }

    /**
     * 🔴 对应 Go {@code AuthoritativeFavoriteFact}（favorite_authority.go:191-193）——
     * 期望 {@code schemaVersion == 1}。
     *
     * <p>⚠️ 与 v2 的区别<b>不只是</b>期望值不同：响应体的 {@code subject_ref} 形状也不同
     * （v1 三字段 / v2 两字段）。Go 的 v1 端点返回 {@code FavoriteAuthorityFact}，
     * v2 端点返回 {@code FavoriteAuthorityFactV2}。
     */
    public FavoriteAuthorityFact.FactV1 authoritativeFactV1(String producer, String eventId) {
        return resolve(producer, eventId, 1).fact;
    }

    /**
     * 🔴 对应 Go {@code AuthoritativeFavoriteFactV2}（favorite_authority.go:195-204）——
     * 期望 {@code schemaVersion == 2}。
     *
     * <p>Go 的实现（:200-203）把 v1 的结果<b>压扁</b>成 v2 形状：
     * {@code SubjectRefV2{Issuer: fact.SubjectRef.AuthorityID, SubjectID: fact.SubjectRef.SubjectID}}。
     *
     * <p>🔴 <b>返回类型是 {@code AuthorityFact} 而不是 {@code FactV1}</b> ——
     * 这是<b>编译器级的约束</b>：Go 的 v2 入口返回的<b>就是</b>两字段形状，
     * 若这里还返回 {@code FactV1}（三字段），v2 端点就会把 v1 的形状发给下游 ⇒ wire 破坏。
     * ⇒ 类型本身把「必须压扁」这件事钉死，不需要靠注释提醒。
     */
    public AuthorityFact authoritativeFactV2(String producer, String eventId) {
        // Go 侧两个入口共用 authoritativeFavoriteFact(ctx, producer, eventID, expectedSchema)
        // ⇒ 这里同样复用同一个 resolve，只把 schema 期望值改成 2。
        //
        // ⚠️ resolve 之后还有 Go 的那一步【压扁】—— 以前漏了这一步，
        //    v2 端点因此把 v1 的三字段 subject_ref 发给了下游 binder。
        return FavoriteAuthorityFact.toV2(resolve(producer, eventId, 2).fact);
    }

    /**
     * 🔴 对应 Go {@code authoritativeFavoriteFact(ctx, producer, eventID, expectedSchema)}
     * （favorite_authority.go:206-282）。
     *
     * <p>步骤逐条对应：
     * <ol>
     *   <li><b>入口校验</b>（:208-211）：{@code producer != favoriteProducer} /
     *       {@code eventID == ""} / {@code len > 128} /
     *       {@code strings.ContainsAny(eventID, "/\\?&#")} ⇒ {@code ErrFavoriteFactUnavailable}。
     *       🔴 最后一条防的是<b>路径穿越</b>：event_id 会进 URL 的 path 段。</li>
     *   <li><b>查行</b>（:213-219）：不存在或不是恰好 1 行 ⇒ 不可用。</li>
     *   <li><b>17 道行校验</b>（{@code authoritativeFavoriteRow}，:126-177）。</li>
     *   <li><b>schema 必须匹配</b>（:221-223）。</li>
     *   <li><b>v1 时</b>（:224-251）：独立核对活着的业务行；
     *       若业务行已不在，则必须有<b>恰好一条</b> v2 墓碑。</li>
     *   <li><b>v2 时</b>（:253-280）：活业务行必须<b>不存在</b>，
     *       且 v1 前驱必须存在、两者的 subject/target/folder/source_ref/revision 全部一致、
     *       且前驱的 DC offset < 撤回的 offset。</li>
     * </ol>
     *
     * @throws FavoriteExceptions.FactUnavailableException 任一校验不通过
     *
     * <p><b>🔴 调用方必须先绑定查询预算</b>（Go: {@code http.go:44} / {@code :80} 的
     * {@code context.WithTimeout(r.Context(), 3*time.Second)}）：
     * Go 的 ctx 覆盖<b>整次 lookup</b>（含下面 17 道校验里的多次查询），
     * 由它派生出的每条 SQL 都带同一个 deadline。
     * ⇒ Java 侧由 {@code FavoriteAuthorityEndpointConfig} 在<b>调用点</b>用
     * {@code DeadlineState.bindToCurrent(...)} 绑定（与 Go 的绑定点一致），
     * 再由已注册的 {@code DeadlineQueryTimeoutInjector} 把它转成每条 SQL 的
     * {@code setQueryTimeout}。
     * ⚠️ <b>本方法不自带超时</b>：没绑 deadline 时 SQL 不设超时（与引入该机制前逐字一致）。
     */
    private AuthoritativeRow resolve(String producer, String eventId, int expectedSchema) {
        // Go: service/favorite/rpc/internal/model/favorite_authority.go:208-211
        if (!FavoriteFactEnvelopeFactory.PRODUCER.equals(producer)
                || eventId == null || eventId.isEmpty()
                || eventId.length() > 128
                || eventId.indexOf('/') >= 0 || eventId.indexOf('\\') >= 0
                || eventId.indexOf('?') >= 0 || eventId.indexOf('#') >= 0
                || eventId.indexOf('&') >= 0) {
            throw new FavoriteExceptions.FactUnavailableException("invalid producer or event id");
        }

        // Go: service/favorite/rpc/internal/model/favorite_authority.go:213-219
        FavoriteFactOutboxEntity row = outboxMapper.selectByEventId(eventId);
        if (row == null) {
            throw new FavoriteExceptions.FactUnavailableException("no such event");
        }

        // Go: service/favorite/rpc/internal/model/favorite_authority.go:220-223
        AuthoritativeRow resolved = authoritativeRow(row);
        if (resolved.fact.event.schemaVersion != expectedSchema) {
            throw new FavoriteExceptions.FactUnavailableException("schema version mismatch");
        }

        if (!isVersion2(row)) {
            // ═══ v1（assert）路径 ═══
            // Go: service/favorite/rpc/internal/model/favorite_authority.go:224-240 —— 收藏还在时，独立核对业务行（不信只信冻结事件）
            long liveCount = outboxMapper.countLiveItemsByFavoriteId(row.getFavoriteId());
            if (liveCount == 1L) {
                FavoriteDeliveryValidator.AuthorityPayload payload = resolved.payload;
                long uid = parseSubjectId(resolved.fact.subjectRef.subjectId);
                long folderId = FavoriteDeliveryValidator.parseAuthorityId(payload.folderId);
                FavoriteItemSnapshot live = liveRow(row.getFavoriteId());
                if (live == null
                        || live.userId != uid
                        || live.folderId != folderId
                        || !live.targetType.equals(payload.targetType)
                        || !live.targetId.equals(payload.targetId)
                        || !FavoriteDeliveryValidator.sameFavoriteRevision(live.targetRevision, payload.targetRevision)) {
                    throw new FavoriteExceptions.FactUnavailableException(
                            "live favorite row disagrees with the frozen assert");
                }
            } else {
                // Go: service/favorite/rpc/internal/model/favorite_authority.go:241-250 —— 收藏已删 ⇒ 必须恰好一条 v2 墓碑
                if (outboxMapper.countRetractsForAuthority(row.getFavoriteId()) != 1L) {
                    throw new FavoriteExceptions.FactUnavailableException(
                            "favorite is gone but the retract tombstone is missing or duplicated");
                }
            }
            return resolved;
        }

        // ═══ v2（retract）路径 ═══
        // Go: service/favorite/rpc/internal/model/favorite_authority.go:253-260 —— 活业务行必须已不存在
        if (outboxMapper.countLiveItemsByFavoriteId(row.getFavoriteId()) != 0L) {
            throw new FavoriteExceptions.FactUnavailableException("retract but favorite still exists");
        }

        // Go: service/favorite/rpc/internal/model/favorite_authority.go:261-269 —— v1 前驱必须存在
        FavoriteFactOutboxEntity predecessorRow = outboxMapper.selectAssertPredecessor(
                resolved.fact.predecessorEventId, row.getFavoriteId());
        if (predecessorRow == null) {
            throw new FavoriteExceptions.FactUnavailableException("assert predecessor is missing");
        }

        // Go: service/favorite/rpc/internal/model/favorite_authority.go:270-280 —— 前驱自身也必须通过全部 17 道校验
        AuthoritativeRow prior = authoritativeRow(predecessorRow);
        if (prior.fact.event.schemaVersion != resolved.fact.event.schemaVersion) {
            throw new FavoriteExceptions.FactUnavailableException("predecessor schema mismatch");
        }
        if (prior.fact.technicalReceipt.offset >= resolved.fact.technicalReceipt.offset) {
            // Go: service/favorite/rpc/internal/model/favorite_authority.go:274 —— prior.TechnicalReceipt.Offset >= fact.TechnicalReceipt.Offset
            throw new FavoriteExceptions.FactUnavailableException(
                    "retract offset is not after the assert offset");
        }
        if (!prior.fact.subjectRef.equals(resolved.fact.subjectRef)) {
            throw new FavoriteExceptions.FactUnavailableException("predecessor subject mismatch");
        }
        if (!prior.payload.targetType.equals(resolved.payload.targetType)
                || !prior.payload.targetId.equals(resolved.payload.targetId)
                || !prior.payload.sourceRef.equals(resolved.payload.sourceRef)) {
            throw new FavoriteExceptions.FactUnavailableException("predecessor target mismatch");
        }
        long priorFolderId = FavoriteDeliveryValidator.parseAuthorityId(prior.payload.folderId);
        long folderId = FavoriteDeliveryValidator.parseAuthorityId(resolved.payload.folderId);
        if (priorFolderId <= 0 || priorFolderId != folderId) {
            throw new FavoriteExceptions.FactUnavailableException("predecessor folder mismatch");
        }
        if (!FavoriteDeliveryValidator.sameFavoriteRevision(
                prior.payload.targetRevision, resolved.payload.targetRevision)) {
            throw new FavoriteExceptions.FactUnavailableException("predecessor revision mismatch");
        }
        return resolved;
    }

    /**
     * 🔴 对应 Go {@code authoritativeFavoriteRow(row)}（favorite_authority.go:126-177）——
     * 本域<b>最长的单个判定函数</b>，17 项。
     */
    private AuthoritativeRow authoritativeRow(FavoriteFactOutboxEntity row) {
        // ① 投递元数据必须齐全（Go: service/favorite/rpc/internal/model/favorite_authority.go:127-129）
        if (row.getStatus() != DeliveryStatus.DELIVERY_ACCEPTED.code()
                || row.getDeliveredAt() == null
                || row.getTechnicalReceivedAt() == null
                || row.getTechnicalReceiptId() == null || row.getTechnicalReceiptId().isEmpty()
                || row.getTechnicalOffset() < 1L
                || row.getTechnicalInputHash() == null
                || !RECEIPT_HASH.matcher(row.getTechnicalInputHash()).matches()) {
            throw unavailable(row, "technical receipt is incomplete");
        }

        // ② 信封必须 strict-decode 且通过 12 条交叉校验（Go: service/favorite/rpc/internal/model/favorite_authority.go:131-133）
        FavoriteDeliveryValidator.FrozenEvent event;
        try {
            event = FavoriteDeliveryValidator.decodeStrict(row.getPayload(), FavoriteDeliveryValidator.FrozenEvent.class);
        } catch (RuntimeException e) {
            throw unavailable(row, "frozen envelope is not strict-decodable");
        }
        if (event == null || !deliveryValidator.validDelivery(row, event)) {
            throw unavailable(row, "frozen envelope does not match its outbox row");
        }

        // ③ payload（Go: service/favorite/rpc/internal/model/favorite_authority.go:135-138）
        FavoriteDeliveryValidator.AuthorityPayload payload;
        try {
            payload = FavoriteDeliveryValidator.decodeStrict(event.payload.toString(),
                    FavoriteDeliveryValidator.AuthorityPayload.class);
        } catch (RuntimeException e) {
            throw unavailable(row, "frozen payload is not strict-decodable");
        }
        long favoriteId = FavoriteDeliveryValidator.parseAuthorityId(payload.favoriteId);
        long folderId = FavoriteDeliveryValidator.parseAuthorityId(payload.folderId);
        FavoriteDeliveryValidator.SubjectResult subject =
                FavoriteDeliveryValidator.subjectV1(event.schemaVersion, payload.subject);

        // ④ payload 与事件/行的自洽（Go: service/favorite/rpc/internal/model/favorite_authority.go:139-148）
        if (favoriteId != row.getFavoriteId()
                || folderId <= 0
                || !subject.ok
                || payload.schemaVersion != event.schemaVersion
                || !event.eventId.equals(payload.eventId)
                || payload.targetType == null || payload.targetType.isEmpty()
                || payload.targetId == null || payload.targetId.isEmpty()
                || !("rtw.favorite/" + row.getFavoriteId()).equals(payload.sourceRef)
                || !event.occurredAt.equals(payload.eventTime)
                || !event.occurredAt.equals(payload.availableAt)) {
            throw unavailable(row, "frozen payload disagrees with the frozen event");
        }

        // ⑤ operation 与 aggregate_version 匹配（Go: service/favorite/rpc/internal/model/favorite_authority.go:149-156）
        String operation = isVersion2(row) ? "retract" : "assert";
        if (!operation.equals(payload.operation)
                || !("rtw.favorite." + operation).equals(event.eventType)
                || !event.eventId.equals("favorite." + row.getFavoriteId() + ".v" + row.getAggregateVersion())) {
            throw unavailable(row, "operation does not match the aggregate version");
        }

        // ⑥ available_at 必须是可解析时间（Go: service/favorite/rpc/internal/model/favorite_authority.go:157-159）
        try {
            Instant.parse(payload.availableAt);
        } catch (RuntimeException e) {
            throw unavailable(row, "available_at is not a valid instant");
        }

        // ⑦🔴 JCS hash 必须与 DC 回执的 input_hash 一致（Go: service/favorite/rpc/internal/model/favorite_authority.go:160-166）
        //    这是「RTW 不可变 outbox」与「DC 技术回执」互相印证的核心。
        String hash;
        try {
            hash = CanonicalHash.canonicalHash(row.getPayload().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw unavailable(row, "JCS hash could not be computed");
        }
        if (!hash.equals(row.getTechnicalInputHash())) {
            throw unavailable(row, "payload hash does not match the DC input hash");
        }

        // ⑧ 组装回执（Go: service/favorite/rpc/internal/model/favorite_authority.go:167-176）
        TechnicalReceipt receipt = new TechnicalReceipt();
        receipt.eventId = row.getEventId();
        receipt.producer = event.producer;
        receipt.technicalStatus = "accepted";
        receipt.receiptId = row.getTechnicalReceiptId();
        receipt.inputHash = row.getTechnicalInputHash();
        receipt.offset = row.getTechnicalOffset();
        receipt.receivedAt = FavoriteFactEnvelopeFactory.rfc3339Nano(row.getTechnicalReceivedAt().toInstant());

        FavoriteAuthorityFact.SubjectRefV1 subjectRef = subject.value;
        String predecessorEventId = isVersion2(row)
                ? "favorite." + row.getFavoriteId() + ".v1"
                : null;                                   // Go: :173-175 —— assert 不带这个字段

        FavoriteAuthorityFact.FactV1 fact = new FavoriteAuthorityFact.FactV1(
                FavoriteDeliveryValidator.toWireEvent(event), subjectRef, predecessorEventId,
                receipt, hash);
        return new AuthoritativeRow(fact, payload);
    }

    // ==================== 小工具 ====================

    private static boolean isVersion2(FavoriteFactOutboxEntity row) {
        return row.getAggregateVersion() != null && row.getAggregateVersion() == 2L;
    }

    private static long parseSubjectId(String subjectId) {
        try {
            return subjectId == null ? 0L : Long.parseLong(subjectId);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static FavoriteExceptions.FactUnavailableException unavailable(
            FavoriteFactOutboxEntity row, String reason) {
        LOG.atWarn()
                .addKeyValue("event", "favorite.authority.lookup_failed")
                .addKeyValue("event_id", row == null ? null : row.getEventId())
                .addKeyValue("outcome", "rejected")
                .addKeyValue("error_code", "SOURCE_READ_FAILED")
                .addKeyValue("reason", reason)
                .log("favorite authority lookup rejected");
        return new FavoriteExceptions.FactUnavailableException(reason);
    }

    /** 活业务行的最小快照（对应 Go 的 {@code FavoriteItem} 局部变量，favorite_authority.go:228）。 */
    private record FavoriteItemSnapshot(long userId, long folderId, String targetType,
                                        String targetId, String targetRevision) {
    }

    /**
     * 对应 Go {@code favorite_authority.go:229}：
     * {@code query := m.conn.Where("favorite_id = ?", row.FavoriteID).Limit(1).Find(&item)}。
     *
     * <p>⚠️ <b>无 {@code FOR UPDATE}</b>：权威读是纯读路径，不加锁。
     * ⇒ 这里读到的是一个<b>快照</b>，可能与随后的其它判断来自不同时刻 ——
     * 这与 Go 一致（Go 同样是三个独立查询）。
     */
    private FavoriteItemSnapshot liveRow(long favoriteId) {
        FavoriteItemEntity entity = favoriteItemMapper.findByFavoriteId(favoriteId);
        if (entity == null) {
            return null;
        }
        return new FavoriteItemSnapshot(entity.getUserId(), entity.getFolderId(),
                entity.getTargetType(), entity.getTargetId(), entity.getTargetRevision());
    }
}
