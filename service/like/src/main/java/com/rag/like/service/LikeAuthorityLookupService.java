package com.rag.like.service;

import com.rag.common.communityfact.AuthorityFact;
import com.rag.common.communityfact.CanonicalHash;
import com.rag.common.communityfact.DeliveryStatus;
import com.rag.common.communityfact.Event;
import com.rag.common.communityfact.SubjectRef;
import com.rag.common.communityfact.TechnicalReceipt;
import com.rag.common.communityfact.Wire;
import com.rag.like.domain.entity.LikeDomainFactOutboxEntity;
import com.rag.like.domain.entity.LikeRecordEntity;
import com.rag.like.mapper.LikeDomainFactOutboxMapper;
import com.rag.like.mapper.LikeRecordMapper;
import io.opentelemetry.context.Context;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 权威读 —— 对应 Go 端 {@code service/like/rpc/internal/model/fact_authority.go:12-146} 的
 * <b>全部五个</b>函数：{@code deliveredLikeAuthority} / {@code validLikeRevision} /
 * {@code AuthoritativeFact} / {@code validLikeTransition} / {@code sameLikeRevision} /
 * {@code subjectIDFromRef}。
 *
 * <p>它们同属一条「链路重放校验」，拆开反而会割裂语义，故合成一个类
 * （这不是「为了对应 Go 文件」——Go 侧它们本来就是同一个文件的一组私有函数）。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeAuthorityLookupService.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.like.like.rpc.service} 压平为 {@code com.rag.like.service}（D7）；</li>
 *       <li>实体/Mapper 的 import 由 {@code com.rag.like.like.rpc.domain} / {@code ...rpc.mapper}
 *           改为 {@code com.rag.like.domain.entity} / {@code com.rag.like.mapper}（D7）；</li>
 *       <li>共享层 {@code Wire} 1:N 拆分 ⇒ {@code Wire.AuthorityFact} → {@link AuthorityFact}、
 *           {@code Wire.SubjectRef} → {@link SubjectRef}、{@code Wire.TechnicalReceipt} → {@link TechnicalReceipt}、
 *           {@code Wire.Event} → {@link Event}、{@code Wire.DeliveryStatus} → {@link DeliveryStatus}、
 *           {@code Wire.canonicalHash} → {@link CanonicalHash#canonicalHash(byte[])}、
 *           {@code Wire.validHash} → {@link CanonicalHash#validHash(String)}；</li>
 *       <li>{@code LikeEventContracts.FACT_PRODUCER} / {@code FACT_EVENT_TYPE}
 *           → {@link LikeDeliveryValidator#FACT_PRODUCER} / {@link LikeDeliveryValidator#FACT_EVENT_TYPE}
 *           （常量落点调整，值逐字未改）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。12 道校验的<b>顺序</b>、404/503 的分流、Go 零值时间的判据、
 *       指针语义的 {@code sameLikeRevision} 逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>它回答的问题：DC 拿到的这条事实，是不是真的来自本域</h2>
 *
 * <p>DC 侧只拿到一条冻结信封。它要判断「可以信任这条事实吗」时，回查本端点，
 * 本端点必须逐项重放出与该事件对应的源库状态，<b>任何一项对不上就回答「不可用」</b>
 * （HTTP 404）。它<b>只出具已有本地 DC 技术回执的事实</b> ——
 * 还没投递成功的事实不出具（否则等于替 DC 背书一条尚未被 DC 接收的事件）。
 *
 * <h2>⚠️ 「不可用」与「读失败」是两种不同的 HTTP 语义</h2>
 *
 * <p>Go 用两个不同的返回值：
 * <ul>
 *   <li>{@code communityfact.ErrAuthorityUnavailable} ⇒ HTTP <b>404</b>
 *       （「这条事实不可信 / 不存在」）；</li>
 *   <li>其它 error（DB 读失败）⇒ HTTP <b>503</b>（「源库读失败，请重试」）。</li>
 * </ul>
 * <p>⇒ Java 侧必须抛 {@link Wire#ERR_AUTHORITY_UNAVAILABLE}
 * （= {@code AuthorityHandler.AuthorityUnavailableException}）来表达 404，
 * <b>其它异常一律原样上抛</b>（⇒ 503）⇒ 两者语义不可混同。
 *
 * <h2>🔴 为什么本类<b>不</b> implements {@code AuthorityHandler.AuthorityLookup}</h2>
 *
 * <p>B 侧实测（2026-10-29）：<b>like-rpc 的 classpath 上没有 servlet-api</b>
 * ⇒ 共享层的 {@code com.rag.common.communityfact.AuthorityHandler}
 * （{@code import jakarta.servlet.http.HttpServlet}）与
 * {@code com.rag.common.spring.AuthorityHandlerFactory}
 * （{@code import org.springframework.boot.web.servlet.ServletRegistrationBean}）
 * <b>在本模块里根本不可加载</b>（{@code NoClassDefFoundError}）。
 *
 * <p>⇒ 若本类 implements 那个接口 ⇒ <b>like-rpc 的 Spring 上下文起不来</b>
 * ⇒ 而 like-rpc 是「纯 gRPC 进程」，刻意不引 web starter
 * （引了会让 4 个未显式声明 {@code webEnvironment} 的 {@code @SpringBootTest} 默认起 Tomcat
 * ⇒ 端口冲突面）。
 *
 * <p>⇒ 因此本类<b>只依赖零 servlet 依赖的 {@link Wire}</b>，
 * 签名与 Go 的 {@code AuthoritativeFact(ctx, producer, eventID)} <b>逐字一致</b>
 * ⇒ HTTP 适配（把本方法接到 {@code AuthorityLookup} 上）属于
 * <b>独立进程模块</b>的职责：那个模块带 web starter，
 * 用方法引用即可（{@code lookupService::lookup}），<b>不需要本类知道 HTTP 的存在</b>。
 *
 * <h2>🔴 12 道校验的顺序即短路顺序（与 Go 逐条对应）</h2>
 *
 * <p>本类的断言链与 Go 的 {@code AuthoritativeFact} 一一对应，见各段注释里的 {@code 文件:行号}。
 * ⚠️ <b>任何一道都不能少</b>：少一道 = 权威读会对「本域没产生过的事实」背书。
 */
@Service
public class LikeAuthorityLookupService {

    /** Go {@code fact_delivery.go:17} {@code const likeFactProducer = "rtw.like-mq"}。 */
    private static final String PRODUCER = LikeDeliveryValidator.FACT_PRODUCER;

    /** Go {@code fact_authority.go:30} 的事件类型判据。 */
    private static final String EVENT_TYPE_INTERACTION = LikeDeliveryValidator.FACT_EVENT_TYPE;

    /** Go {@code fact_authority.go:32} {@code payload.EventID != "rtw.like." + payload.OperationID}。 */
    private static final String EVENT_ID_PREFIX = LikeDeliveryValidator.FACT_EVENT_ID_PREFIX;

    /** Go {@code fact_authority.go:54} 的 eventID 长度上限。 */
    private static final int MAX_EVENT_ID_LENGTH = 128;

    private final LikeDomainFactOutboxMapper outboxMapper;
    private final LikeRecordMapper recordMapper;
    private final LikeDeliveryValidator validator;

    public LikeAuthorityLookupService(LikeDomainFactOutboxMapper outboxMapper,
                                       LikeRecordMapper recordMapper,
                                       LikeDeliveryValidator validator) {
        this.outboxMapper = outboxMapper;
        this.recordMapper = recordMapper;
        this.validator = validator;
    }

    /**
     * 对应 Go {@code AuthoritativeFact(ctx, producer, eventID)}（{@code fact_authority.go:53-119}）。
     *
     * <p>🔴 <b>签名与 Go 逐字一致</b>（{@code (ctx, producer, eventID)} → {@code AuthorityFact}）
     * ⇒ 未来的独立进程模块用<b>方法引用</b>（{@code lookupService::lookup}）接到
     * {@code AuthorityLookup} 上即可，<b>不需要本类知道 HTTP 的存在</b>。
     *
     * @throws com.rag.common.communityfact.Wire#ERR_AUTHORITY_UNAVAILABLE 任何一项对不上 ⇒ 404
     *         （其它异常一律原样上抛 ⇒ 503「源库读失败，请重试」）
     */
    public AuthorityFact lookup(Context ctx, String producer, String eventId) {
        // --- 入参守卫（fact_authority.go:54）
        if (!PRODUCER.equals(producer)
                || eventId == null || eventId.isEmpty() || eventId.length() > MAX_EVENT_ID_LENGTH) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        // --- ① 按 event_id 取那一行（fact_authority.go:57-64）
        // ⚠️ Go 侧 DB 出错 ⇒ 返回原始 error（⇒ 503）；查不到 ⇒ ErrAuthorityUnavailable（⇒ 404）
        LikeDomainFactOutboxEntity requested = outboxMapper.selectByEventId(eventId);
        if (requested == null) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        // --- ② 该行本身必须构成一条「已投递且自洽」的冻结事实（fact_authority.go:64-68）
        Delivered delivered = deliveredLikeAuthority(requested);
        if (delivered == null
                || requested.getAggregateId() == null
                || requested.getFactVersion() == null) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }
        AuthorityFact authority = delivered.authority;
        LikeDeliveryValidator.LikeFact payload = delivered.payload;

        // --- ③ 按 aggregate_id 取全部行，按 (fact_version, event_id) 排序（fact_authority.go:69-73）
        List<LikeDomainFactOutboxEntity> rows =
                outboxMapper.selectByAggregateIdOrderByVersion(requested.getAggregateId());

        // --- ④ 逐行重放（fact_authority.go:74-102）—— 12 道校验的主体
        int state = 0;
        String previous = "";
        boolean found = false;
        for (int index = 0; index < rows.size(); index++) {
            LikeDomainFactOutboxEntity candidate = rows.get(index);

            // ④-1 版本必须连续且从 1 开始（fact_authority.go:78-80）
            if (candidate.getDeliveryEnvelope() == null
                    || candidate.getFactVersion() == null
                    || candidate.getFactVersion() != (long) (index + 1)) {
                throw Wire.ERR_AUTHORITY_UNAVAILABLE;
            }

            // ④-2 信封必须能严格解码且与行自洽（fact_authority.go:81-85）
            Event event;
            try {
                event = validator.decodeEnvelope(candidate.getDeliveryEnvelope());
            } catch (RuntimeException e) {
                throw Wire.ERR_AUTHORITY_UNAVAILABLE;
            }
            if (!validator.isValidDelivery(candidate, event)) {
                throw Wire.ERR_AUTHORITY_UNAVAILABLE;
            }

            // ④-3 载荷必须能严格解码，且与「上一行重放出来的状态」衔接（fact_authority.go:86-93）
            LikeDeliveryValidator.LikeFact current = validator.decodePayload(event);
            if (current == null
                    || current.oldState != state
                    || !eq(current.subjectRef, payload.subjectRef)
                    || !eq(current.targetType, payload.targetType)
                    || !eq(current.targetId, payload.targetId)
                    || !sameLikeRevision(current.targetRevision, payload.targetRevision)
                    || !validLikeTransition(current.operation, current.oldState, current.newState)) {
                throw Wire.ERR_AUTHORITY_UNAVAILABLE;
            }

            // ④-4 找到被问的那一行，并记下它的前驱（fact_authority.go:94-99）
            if (eq(candidate.getEventId(), eventId)) {
                found = true;
                if (current.oldState != 0) {
                    authority.predecessorEventId = previous;
                }
            }
            state = current.newState;
            previous = candidate.getEventId();
        }

        // --- ⑤ 被问的那一行必须在重放里出现过；有前驱的事实必须有前驱（fact_authority.go:102-105）
        if (!found || (payload.oldState != 0 && isBlank(authority.predecessorEventId))) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        // --- ⑥ 源库状态核对（fact_authority.go:106-117）
        long uid = subjectIdFromRef(authority.subjectRef);
        LikeRecordEntity record = recordMapper.selectOneByUserAndTarget(uid, payload.targetType, payload.targetId);
        if (record == null) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }
        long operationId;
        try {
            operationId = Long.parseLong(payload.operationId);
        } catch (NumberFormatException e) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }
        if (operationId <= 0
                || record.getState() != state
                || record.getLastOperationId() < operationId
                || record.getUserId() != uid) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        return authority;
    }

    /**
     * 对应 Go {@code deliveredLikeAuthority(row)}（{@code fact_authority.go:12-44}）。
     *
     * <p>判据分三组，顺序即短路顺序：
     * <ol>
     *   <li><b>行自身的技术回执完整性</b>（{@code :13-15}）：已投递 / 有投递时间 /
     *       有接收时间 / 有回执 ID / 位点 ≥ 1 / 输入哈希合法 / 信封非空；</li>
     *   <li><b>信封 ⇔ 载荷 ⇔ 行 的交叉校验</b>（{@code :19-34}）：严格解码 + 交付合法性 +
     *       载荷各项恒等式；</li>
     *   <li><b>哈希对账</b>（{@code :35-38}）：信封原始字节的规范化哈希必须等于行的
     *       {@code technical_input_hash}。</li>
     * </ol>
     *
     * @return 校验通过时返回「权威事实 + 载荷」；任何一项不过 ⇒ {@code null}
     */
    private Delivered deliveredLikeAuthority(LikeDomainFactOutboxEntity row) {
        // --- ① 行自身的技术回执完整性（Go fact_authority.go:13-15）
        if (row.getDeliveryStatus() != DeliveryStatus.DELIVERY_ACCEPTED.code()
                || row.getDeliveredAt() == null
                || row.getTechnicalReceivedAt() == null
                || isBlank(row.getTechnicalReceiptId())
                || row.getTechnicalOffset() < 1
                || !CanonicalHash.validHash(row.getTechnicalInputHash())
                || row.getDeliveryEnvelope() == null) {
            return null;
        }

        // --- ② 信封严格解码 + 交付合法性（Go fact_authority.go:19-22）
        Event event;
        try {
            event = validator.decodeEnvelope(row.getDeliveryEnvelope());
        } catch (RuntimeException e) {
            return null;
        }
        if (!validator.isValidDelivery(row, event)) {
            return null;
        }

        // --- ②-2 载荷严格解码（Go fact_authority.go:24-25）
        LikeDeliveryValidator.LikeFact payload = validator.decodePayload(event);
        if (payload == null) {
            return null;
        }

        // --- ②-3 载荷各项恒等式（Go fact_authority.go:27-33）
        Wire.Result<SubjectRef> subjectResult = Wire.parseRTWSubject(payload.subjectRef);
        if (!subjectResult.isOk()
                || !validLikeRevision(payload.targetRevision, payload.revisionStatus)
                || !eq(payload.sourceRef, payload.eventId)
                || isZeroTime(payload.eventTime)
                || isZeroTime(payload.availableAt)
                || !EVENT_TYPE_INTERACTION.equals(payload.eventType)
                || !eq(payload.aggregateId, payload.targetType + "/" + payload.targetId)
                || !eq(payload.eventId, EVENT_ID_PREFIX + payload.operationId)
                || !eq(event.operationId, payload.operationId)) {
            return null;
        }

        // --- ③ 哈希对账（Go fact_authority.go:35-38）
        // ⚠️ 算的是**信封列的原始字节**，不是重新序列化的字节
        //   （Go: CanonicalHash([]byte(*row.DeliveryEnvelope))）
        String hash;
        try {
            hash = CanonicalHash.canonicalHash(row.getDeliveryEnvelope().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        if (!eq(hash, row.getTechnicalInputHash())) {
            return null;
        }

        AuthorityFact authority = new AuthorityFact();
        authority.event = event;
        authority.subjectRef = subjectResult.value;
        authority.sourceEventHash = hash;

        TechnicalReceipt receipt = new TechnicalReceipt();
        receipt.eventId = row.getEventId();
        receipt.producer = event.producer;
        receipt.technicalStatus = "accepted";
        receipt.receiptId = row.getTechnicalReceiptId();
        receipt.inputHash = row.getTechnicalInputHash();
        receipt.offset = row.getTechnicalOffset();
        // Go: row.TechnicalReceivedAt.UTC().Format(time.RFC3339Nano)
        // ⇒ 共享层的 Wire.validateReceipt 用 Instant.parse 解析它 ⇒ ISO-8601 instant 形态。
        receipt.receivedAt = row.getTechnicalReceivedAt()
                .withOffsetSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
        authority.technicalReceipt = receipt;

        return new Delivered(authority, payload);
    }

    /**
     * 对应 Go {@code validLikeRevision(revision, status)}（{@code fact_authority.go:46-51}）。
     *
     * <p>Go：{@code revision == nil} ⇒ 要求 {@code status == "unknown"}；
     * 否则要求 revision 非空 <b>且</b> {@code status == "resolved"}。
     */
    private static boolean validLikeRevision(String revision, String status) {
        if (revision == null) {
            return "unknown".equals(status);
        }
        return !revision.isEmpty() && "resolved".equals(status);
    }

    /**
     * 对应 Go {@code validLikeTransition(operation, oldState, newState)}（{@code fact_authority.go:121-134}）。
     *
     * <p>⚠️ <b>状态取值域</b>：{@code newState} 用的是 {@code like_record.state} 的取值域
     * （0 无 / 1 已点赞 / 2 已点踩），<b>不是</b> Kafka 消息里的 action 编码（1~4）。
     */
    private static boolean validLikeTransition(String operation, int oldState, int newState) {
        if (operation == null) {
            return false;
        }
        switch (operation) {
            case "like":
                return newState == 1 && oldState != 1;
            case "unlike":
                return oldState == 1 && newState == 0;
            case "dislike":
                return newState == 2 && oldState != 2;
            case "undislike":
                return oldState == 2 && newState == 0;
            default:
                return false;
        }
    }

    /**
     * 对应 Go {@code sameLikeRevision(left, right)}（{@code fact_authority.go:136-141}）。
     *
     * <p>Go 是<b>指针比较</b>：两个都是 nil ⇒ 相等；只有一个是 nil ⇒ 不等；
     * 都不是 nil ⇒ 比<b>字符串内容</b>。
     */
    private static boolean sameLikeRevision(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.equals(right);
    }

    /**
     * 对应 Go {@code subjectIDFromRef(subject)}（{@code fact_authority.go:143-146}）。
     *
     * <p>Go：{@code id, _ := strconv.ParseInt(subject.SubjectID, 10, 64); return id}
     * ⇒ <b>解析失败返回 0</b>（不是报错）⇒ 后续「uid 对不上源库」自然把它判成不可用。
     */
    private static long subjectIdFromRef(SubjectRef subject) {
        if (subject == null || subject.subjectId == null) {
            return 0L;
        }
        try {
            return Long.parseLong(subject.subjectId);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 「时间字段是零值」的判据 —— Go 用 {@code time.Time.IsZero()}。
     *
     * <p>🔴 <b>Go 的零值是「公元 1 年 1 月 1 日 00:00:00 UTC」</b>（{@code time.Time{}} 的零结构），
     * <b>不是</b> epoch（1970）⇒ 不能用 {@code Instant.EPOCH} 比。
     */
    private static final Instant GO_ZERO_TIME =
            LocalDateTime.of(1, 1, 1, 0, 0, 0).toInstant(ZoneOffset.UTC);

    private static boolean isZeroTime(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        Instant parsed = LikeDeliveryValidator.parseGoRfc3339(value);
        return parsed == null || GO_ZERO_TIME.equals(parsed);
    }

    private static boolean eq(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    /** {@code deliveredLikeAuthority} 的返回体（Go 的多返回值）。 */
    private static final class Delivered {
        private final AuthorityFact authority;
        private final LikeDeliveryValidator.LikeFact payload;

        private Delivered(AuthorityFact authority, LikeDeliveryValidator.LikeFact payload) {
            this.authority = authority;
            this.payload = payload;
        }
    }
}
