package com.rag.comment.service;

import com.rag.common.communityfact.AuthorityFact;
import com.rag.common.communityfact.AuthorityHandler;
import com.rag.common.communityfact.CanonicalHash;
import com.rag.common.communityfact.DeliveryStatus;
import com.rag.common.communityfact.Event;
import com.rag.common.communityfact.SubjectRef;
import com.rag.common.communityfact.TechnicalReceipt;
import com.rag.common.communityfact.Wire;
import com.rag.comment.domain.entity.CommentDomainFactOutboxEntity;
import com.rag.comment.domain.entity.CommentIndexEntity;
import com.rag.comment.domain.entity.CommentLikeEntity;
import com.rag.comment.domain.service.CommentDomainFactService;
import com.rag.comment.mapper.CommentContentMapper;
import com.rag.comment.mapper.CommentDomainFactOutboxMapper;
import com.rag.comment.mapper.CommentIndexMapper;
import com.rag.comment.mapper.CommentLikeMapper;
import io.opentelemetry.context.Context;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 权威读 —— 对应 Go 端 {@code service/comment/rpc/internal/model/fact_authority.go:12-214} 的
 * <b>全部六个</b>函数：{@code deliveredCommentAuthority} / {@code validCommentRevision} /
 * {@code AuthoritativeFact} / {@code authoritativeCommentInteraction} /
 * {@code validReactionTransition} / {@code sameStringPointer} / {@code subjectID}。
 *
 * <p>它们同属一条「链路重放校验」，拆开反而会割裂语义，故合成一个类
 * （这不是「为了对应 Go 文件」——Go 侧它们本来就是同一个文件的一组私有函数）。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuthorityLookupService.java:1-429}
 * （L2 业务语义层以 B 侧为基准整段抄 · D13/D16）。
 *
 * <h2>它回答的问题：DC 拿到的这条事实，是不是真的来自本域</h2>
 * <p>DC 侧只拿到一条冻结信封。它要判断「可以信任这条事实吗」时，回查本端点，
 * 本端点必须逐项重放出与该事件对应的源库状态，<b>任何一项对不上就回答「不可用」</b>
 * （HTTP 404）。它<b>只出具已有本地 DC 技术回执的事实</b> ——
 * 还没投递成功的事实不出具（否则等于替 DC 背书一条尚未被 DC 接收的事件）。
 *
 * <h2>⚠️ 「不可用」的表达方式</h2>
 * <p>Go 用 {@code communityfact.ErrAuthorityUnavailable} ⇒ HTTP 404；
 * Java 侧必须抛 {@link AuthorityHandler.AuthorityUnavailableException}
 * （= {@code Wire.ERR_AUTHORITY_UNAVAILABLE}），否则会被当成 503
 * （「源库读失败，请重试」），语义完全不同。
 * ⚠️ <b>其它异常一律原样上抛</b>（⇒ 503）⇒ 两者语义不可混同。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.comment.comment.rpc.service} 压平为 {@code com.rag.comment.service}（D7）；</li>
 *       <li>实体 / Mapper 的 import 由 {@code com.rag.comment.comment.rpc.domain} /
 *           {@code ...rpc.mapper} 改为 {@code com.rag.comment.domain.entity} /
 *           {@code com.rag.comment.mapper}（D7）；</li>
 *       <li>{@code CommentDomainFactService} 的落点由 {@code ...rpc.service} 改为
 *           {@code com.rag.comment.domain.service}（映射表
 *           {@code docs/Mapping_Table/comment/comment.md:185} 1:1 落点）——
 *           它的 {@code PRODUCER} 常量与 {@code CommentFact} 载荷类型随之改包名；</li>
 *       <li>🔴 <b>共享层 {@code Wire} 1:N 拆分</b>（映射表 §1.2 决策锁 L-7）⇒
 *           {@code Wire.AuthorityFact} → {@link AuthorityFact}、
 *           {@code Wire.SubjectRef} → {@link SubjectRef}、
 *           {@code Wire.TechnicalReceipt} → {@link TechnicalReceipt}、
 *           {@code Wire.Event} → {@link Event}、
 *           {@code Wire.DeliveryStatus} → {@link DeliveryStatus}、
 *           {@code Wire.canonicalHash} → {@link CanonicalHash#canonicalHash(byte[])}、
 *           {@code Wire.validHash} → {@link CanonicalHash#validHash(String)}；
 *           而 {@code Wire.ERR_AUTHORITY_UNAVAILABLE} 与 {@code Wire.parseRTWSubject}
 *           仍留在 {@link Wire}（门面类只保留包级函数与哨兵）；
 *           <b>值与语义逐字未改</b>。</li>
 *       <li>去掉 B 侧未使用的 {@code import java.time.Instant}（零行为影响）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。三类事件分支（created / deleted / interaction）的
 *       <b>校验顺序、判定条件、404/503 的分流</b>逐条未改；
 *       Go 零值时间的判据（{@code "0001-01-01T00:00:00"} 前缀）与
 *       「同为空 / 都非空且相等」的指针语义也逐字未改。</li>
 *   <li><b>B 出处</b>：见上（逐条行号见各方法的 javadoc）。</li>
 * </ol>
 *
 * <h2>🔴 与 like 域同名类的<b>两处刻意不同</b>（不要抄 like）</h2>
 * <ol>
 *   <li><b>like 域不 implements {@code AuthorityLookup}</b>（like-rpc 的 classpath 上没有 servlet-api）
 *       ⇒ 本类<b>实现</b>它（comment 域是 api+rpc 合并进程，带 spring-boot-starter-web），
 *       端点装配由 {@code wiring/CommentAuthorityEndpointConfig} 负责；</li>
 *   <li><b>零值时间的判据不同</b>：like 域按「解析后与公元 1 年比较」，
 *       comment 域按 B 侧原样的<b>字面量前缀</b>判定 ⇒ 不可跨域统一。</li>
 * </ol>
 *
 * <p>⚠️ <b>编译期依赖提醒</b>：{@code io.opentelemetry.context.Context} 由共享层的
 * {@code AuthorityLookup} 接口签名决定（<b>不可替换</b>），而 {@code opentelemetry-api}
 * 是<b>经 {@code common} 传递</b>进来的 ⇒ 构建本模块须走 reactor
 * （{@code mvn -pl service/comment -am compile}）。
 * 若单独 {@code -pl service/comment} 且本机 {@code ~/.m2} 里的 {@code com.rag:common}
 * 是旧版本 ⇒ 该 jar 不带 otel ⇒ 本文件会报「程序包 io.opentelemetry.context 不存在」。
 */
@Service
public class CommentAuthorityLookupService implements AuthorityHandler.AuthorityLookup {

    private final CommentDomainFactOutboxMapper outboxMapper;
    private final CommentIndexMapper commentIndexMapper;
    private final CommentContentMapper commentContentMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final CommentDeliveryValidator validator;

    /**
     * 构造器 —— 与 B 侧<b>同参序</b>。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:52-62}。
     *
     * @param outboxMapper       事实 outbox 数据访问
     * @param commentIndexMapper 评论索引数据访问
     * @param commentContentMapper 评论正文数据访问
     * @param commentLikeMapper  评论点赞数据访问
     * @param validator          冻结信封 / 载荷校验器（本包内协作者）
     */
    public CommentAuthorityLookupService(CommentDomainFactOutboxMapper outboxMapper,
                                         CommentIndexMapper commentIndexMapper,
                                         CommentContentMapper commentContentMapper,
                                         CommentLikeMapper commentLikeMapper,
                                         CommentDeliveryValidator validator) {
        this.outboxMapper = outboxMapper;
        this.commentIndexMapper = commentIndexMapper;
        this.commentContentMapper = commentContentMapper;
        this.commentLikeMapper = commentLikeMapper;
        this.validator = validator;
    }

    /**
     * 对应 Go {@code AuthoritativeFact(ctx, producer, eventID)}。
     *
     * <p>🔴 <b>签名与 Go 逐字一致</b>（{@code (ctx, producer, eventID)} → {@code AuthorityFact}）
     * ⇒ 装配点（{@code wiring/CommentAuthorityEndpointConfig}）用<b>方法引用</b>
     * （{@code lookupService::lookup}）接到共享层的 {@code AuthorityLookup} 上即可。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:64-119}。
     *
     * @throws AuthorityHandler.AuthorityUnavailableException 任何一项对不上 ⇒ 404
     *         （其它异常一律原样上抛 ⇒ 503「源库读失败，请重试」）
     */
    @Override
    public AuthorityFact lookup(Context ctx, String producer, String eventId) {
        // --- 入参守卫（fact_authority.go:52-54）
        if (!CommentDomainFactService.PRODUCER.equals(producer)
                || eventId == null || eventId.isEmpty() || eventId.length() > 128) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        CommentDomainFactOutboxEntity row = outboxMapper.findByEventId(eventId);
        if (row == null) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        DeliveredAuthority delivered = deliveredCommentAuthority(row);
        if (delivered == null) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }
        AuthorityFact authority = delivered.authority;
        CommentDomainFactService.CommentFact payload = delivered.payload;

        // comment_id 必须是正整数且等于其十进制字面量（fact_authority.go:67-70）
        long commentId;
        try {
            commentId = Long.parseLong(payload.commentId);
        } catch (NumberFormatException e) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }
        if (commentId <= 0 || !String.valueOf(commentId).equals(payload.commentId)) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        CommentIndexEntity comment = commentIndexMapper.findOneByIdPlain(commentId);
        if (comment == null
                || !eq(comment.getTargetType(), payload.targetType)
                || !eq(comment.getTargetId(), payload.targetId)
                || (!CommentDomainFactOutboxMapper.EVENT_TYPE_INTERACTION.equals(payload.eventType)
                    && !String.valueOf(comment.getUserId())
                            .equals(stripSubjectPrefix(payload.subjectRef)))) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        return switch (payload.eventType) {
            case CommentDomainFactOutboxMapper.EVENT_TYPE_CREATED ->
                    authoritativeCreated(authority, payload, eventId, comment, commentId);
            case CommentDomainFactOutboxMapper.EVENT_TYPE_DELETED ->
                    authoritativeDeleted(authority, payload, eventId, comment, commentId);
            case CommentDomainFactOutboxMapper.EVENT_TYPE_INTERACTION ->
                    authoritativeInteraction(authority, payload, row.getFactVersion());
            default -> throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        };
    }

    /**
     * 对应 Go {@code fact_authority.go:82-104} 的 {@code "community.comment.created"} 分支。
     *
     * <p>校验：operation 必须是 {@code create}；source_ref 必须是 {@code rtw.comment/<id>}；
     * event_id 必须是 {@code rtw.comment.<id>.created}；正文行<b>恰好 1 条</b>；
     * 若评论已删除（state==2）则对应的 retract 事实<b>恰好 1 条</b>。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:121-150}。
     */
    private AuthorityFact authoritativeCreated(AuthorityFact authority,
                                               CommentDomainFactService.CommentFact payload,
                                               String eventId,
                                               CommentIndexEntity comment,
                                               long commentId) {
        String expectedSourceRef = "rtw.comment/" + payload.commentId;
        if (!"create".equals(payload.operation)
                || !eq(expectedSourceRef, payload.sourceRef)
                || !eventId.equals("rtw.comment." + payload.commentId + ".created")) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }
        long contentCount = commentContentMapper.countByCommentId(commentId);
        if (contentCount != 1) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }
        if (comment.getState() == 2) {
            long retractCount = outboxMapper.countByEventId("rtw.comment." + payload.commentId + ".deleted");
            if (retractCount != 1) {
                throw Wire.ERR_AUTHORITY_UNAVAILABLE;
            }
        }
        return authority;
    }

    /**
     * 对应 Go {@code fact_authority.go:105-124} 的 {@code "community.comment.deleted"} 分支。
     *
     * <p>额外校验：评论状态必须是 2（已删除）；必须有<b>已成功投递</b>的前序 created 事实，
     * 且前序的 DC offset < 本条的 offset（否则「删除」早于「创建」到达 DC），
     * 且两条的 subject / target / source_ref / target_revision 全部一致。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:152-192}。
     */
    private AuthorityFact authoritativeDeleted(AuthorityFact authority,
                                               CommentDomainFactService.CommentFact payload,
                                               String eventId,
                                               CommentIndexEntity comment,
                                               long commentId) {
        String expectedSourceRef = "rtw.comment/" + payload.commentId;
        if (!"retract".equals(payload.operation)
                || !eq(expectedSourceRef, payload.sourceRef)
                || !eventId.equals("rtw.comment." + payload.commentId + ".deleted")
                || comment.getState() != 2) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        String predecessorId = "rtw.comment." + payload.commentId + ".created";
        CommentDomainFactOutboxEntity predecessorRow = outboxMapper.findByEventId(predecessorId);
        if (predecessorRow == null) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }
        DeliveredAuthority prior = deliveredCommentAuthority(predecessorRow);
        if (prior == null) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }
        if (!(prior.authority.technicalReceipt.offset < authority.technicalReceipt.offset)
                || !eq(prior.authority.subjectRef.issuer, authority.subjectRef.issuer)
                || !eq(prior.authority.subjectRef.subjectId, authority.subjectRef.subjectId)
                || !eq(prior.payload.targetType, payload.targetType)
                || !eq(prior.payload.targetId, payload.targetId)
                || !eq(prior.payload.sourceRef, payload.sourceRef)
                || !sameStringPointer(prior.payload.targetRevision, payload.targetRevision)) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }
        authority.predecessorEventId = predecessorId;
        return authority;
    }

    /**
     * 对应 Go {@code authoritativeCommentInteraction}（fact_authority.go:132-187）。
     *
     * <p>它做的事是<b>把整条互动链从头重放一遍</b>：
     * 按 {@code fact_version, event_id} 顺序取出该用户对该评论的全部互动事实，
     * 要求每条的 {@code old_state} 恰好等于上一条的 {@code new_state}
     * （链首的期望值是 0），且每一跳的 {@code operation} 与状态转移自洽。
     * 重放出的最终状态还必须与 {@code comment_like} 当前行<b>完全一致</b>。
     *
     * <p>⇒ 任何一条历史事实被篡改/漏发/乱序，链子都会在这里断掉 ⇒ 404。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:194-274}。
     */
    private AuthorityFact authoritativeInteraction(AuthorityFact authority,
                                                   CommentDomainFactService.CommentFact payload,
                                                   Long requestedVersion) {
        if (requestedVersion == null || payload.oldState == null || payload.newState == null
                || !eq(payload.sourceRef, payload.eventId)
                || payload.eventId == null
                || !payload.eventId.startsWith("rtw.comment.interaction.")) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        List<CommentDomainFactOutboxEntity> rows = outboxMapper.selectInteractionStream(
                CommentDomainFactOutboxMapper.EVENT_TYPE_INTERACTION,
                payload.subjectRef, payload.commentId);
        if (rows == null) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        int state = 0;
        String previous = "";
        boolean found = false;
        for (CommentDomainFactOutboxEntity candidate : rows) {
            if (candidate.getDeliveryEnvelope() == null || candidate.getFactVersion() == null) {
                throw Wire.ERR_AUTHORITY_UNAVAILABLE;
            }
            Event event;
            try {
                event = validator.decodeEnvelope(candidate.getDeliveryEnvelope());
            } catch (RuntimeException e) {
                throw Wire.ERR_AUTHORITY_UNAVAILABLE;
            }
            if (!validator.isValidDelivery(candidate, event)) {
                throw Wire.ERR_AUTHORITY_UNAVAILABLE;
            }
            CommentDomainFactService.CommentFact current = validator.decodePayload(event);
            if (current == null || current.oldState == null || current.newState == null
                    || current.oldState != state
                    || !eq(current.subjectRef, payload.subjectRef)
                    || !eq(current.commentId, payload.commentId)
                    || !eq(current.targetType, payload.targetType)
                    || !eq(current.targetId, payload.targetId)
                    || !sameStringPointer(current.targetRevision, payload.targetRevision)
                    || !validReactionTransition(current.operation, current.oldState, current.newState)) {
                throw Wire.ERR_AUTHORITY_UNAVAILABLE;
            }
            if (candidate.getEventId().equals(payload.eventId)) {
                found = true;
                // 只有「非链首的那一跳」才有前驱事件
                if (current.oldState != 0) {
                    authority.predecessorEventId = previous;
                }
            }
            state = current.newState;
            previous = candidate.getEventId();
        }

        if (!found || (payload.oldState != 0 && (authority.predecessorEventId == null
                || authority.predecessorEventId.isEmpty()))) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }

        // payload.commentId 在 lookup() 入口已校验为正整数字面量，这里安全地转成 long
        CommentLikeEntity record = commentLikeMapper.findOneByUserAndComment(
                subjectId(authority), Long.parseLong(payload.commentId));
        if (record == null || record.getState() != state
                || !eq(record.getTargetType(), payload.targetType)
                || !eq(record.getTargetId(), payload.targetId)) {
            throw Wire.ERR_AUTHORITY_UNAVAILABLE;
        }
        return authority;
    }

    // ====================================================================================
    // 已投递权威事实的解析
    // ====================================================================================

    /**
     * 对应 Go {@code deliveredCommentAuthority(row)}（fact_authority.go:12-40）。
     *
     * <p><b>「已投递」的 7 个条件缺一不可</b>：
     * {@code delivery_status == ACCEPTED} 且 {@code delivered_at} 非空
     * 且 {@code technical_received_at} 非空 且 {@code technical_receipt_id} 非空
     * 且 {@code technical_offset >= 1} 且 {@code technical_input_hash} 是合法 sha256
     * 且 {@code delivery_envelope} 非空；
     * 再叠加：信封可解码、{@code validCommentDelivery} 通过、
     * {@code subject_ref} 可解析成 RTW 主体、{@code validCommentRevision} 通过、
     * {@code source_ref} 非空、{@code event_time}/{@code available_at} 非零值、
     * 以及<b>信封的规范化哈希必须等于落库的 technical_input_hash</b>。
     *
     * <p>最后一条是关键：它证明「我们投递给 DC 的字节」与「我们记账的字节」是同一份。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:280-357}。
     *
     * @param row outbox 行
     * @return null 表示不满足 ⇒ 调用方 404
     */
    private DeliveredAuthority deliveredCommentAuthority(CommentDomainFactOutboxEntity row) {
        if (row.getDeliveryStatus() != DeliveryStatus.DELIVERY_ACCEPTED.code()
                || row.getDeliveredAt() == null
                || row.getTechnicalReceivedAt() == null
                || row.getTechnicalReceiptId() == null || row.getTechnicalReceiptId().isEmpty()
                || row.getTechnicalOffset() < 1
                || !CanonicalHash.validHash(row.getTechnicalInputHash())
                || row.getDeliveryEnvelope() == null) {
            return null;
        }
        Event event;
        try {
            event = validator.decodeEnvelope(row.getDeliveryEnvelope());
        } catch (RuntimeException e) {
            return null;
        }
        if (!validator.isValidDelivery(row, event)) {
            return null;
        }
        CommentDomainFactService.CommentFact payload = validator.decodePayload(event);
        if (payload == null) {
            return null;
        }
        Wire.Result<SubjectRef> subject = Wire.parseRTWSubject(payload.subjectRef);
        if (!subject.isOk()
                || !validCommentRevision(payload.targetRevision, payload.revisionStatus)
                || isBlank(payload.sourceRef)
                // Go 用 payload.EventTime.IsZero() / AvailableAt.IsZero()：
                // 零值时间序列化成 "0001-01-01T00:00:00Z"。等价判定 = 该字面量或空。
                || isGoZeroTime(payload.eventTime)
                || isGoZeroTime(payload.availableAt)) {
            return null;
        }

        String hash;
        try {
            hash = CanonicalHash.canonicalHash(row.getDeliveryEnvelope().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        if (!hash.equals(row.getTechnicalInputHash())) {
            return null;
        }

        AuthorityFact authority = new AuthorityFact();
        authority.event = event;
        authority.subjectRef = subject.get();
        authority.technicalReceipt = new TechnicalReceipt();
        authority.technicalReceipt.eventId = row.getEventId();
        authority.technicalReceipt.producer = event.producer;
        authority.technicalReceipt.technicalStatus = "accepted";
        authority.technicalReceipt.receiptId = row.getTechnicalReceiptId();
        authority.technicalReceipt.inputHash = row.getTechnicalInputHash();
        authority.technicalReceipt.offset = row.getTechnicalOffset();
        authority.technicalReceipt.receivedAt = row.getTechnicalReceivedAt()
                .withOffsetSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
        authority.sourceEventHash = hash;

        return new DeliveredAuthority(authority, payload);
    }

    /**
     * 对应 Go {@code validCommentRevision}（fact_authority.go:42-47）：
     * {@code revision == nil ⇒ status 必须 "unknown"；否则 status 必须 "resolved"}。
     *
     * <p>comment 域的每条事实都不带 {@code target_revision}（Go 侧从未赋值）⇒
     * 本域恒走第一条分支。这是「事实自洽」检查，不是业务规则。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:359-371}。
     */
    private static boolean validCommentRevision(String revision, String status) {
        if (revision == null) {
            return "unknown".equals(status);
        }
        return !revision.isEmpty() && "resolved".equals(status);
    }

    /**
     * 对应 Go {@code validReactionTransition}（fact_authority.go:189-202）：
     * 四种操作的合法状态转移。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:373-385}。
     */
    private static boolean validReactionTransition(String operation, int oldState, int newState) {
        return switch (operation == null ? "" : operation) {
            case "like" -> newState == 1 && oldState != 1;
            case "unlike" -> oldState == 1 && newState == 0;
            case "dislike" -> newState == 2 && oldState != 2;
            case "undislike" -> oldState == 2 && newState == 0;
            default -> false;
        };
    }

    /**
     * 对应 Go {@code sameStringPointer}：两个可空字符串「同为空」或「都非空且相等」。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:387-393}。
     */
    private static boolean sameStringPointer(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.equals(right);
    }

    /**
     * Go {@code time.Time} 零值的 JSON 形态（0001-01-01T00:00:00Z），或空串。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:395-398}。
     */
    private static boolean isGoZeroTime(String wireTime) {
        return isBlank(wireTime) || wireTime.startsWith("0001-01-01T00:00:00");
    }

    /**
     * 对应 Go {@code subjectID(subject)}：从 {@code SubjectRef.SubjectID} 取整数（解析失败取 0）。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:400-407}。
     */
    private static long subjectId(AuthorityFact authority) {
        try {
            return Long.parseLong(authority.subjectRef.subjectId);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * 对应 Go {@code strings.TrimPrefix(payload.SubjectRef, "rtw.identity/platform/")}。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:409-415}。
     */
    private static String stripSubjectPrefix(String subjectRef) {
        final String prefix = "rtw.identity/platform/";
        return subjectRef != null && subjectRef.startsWith(prefix)
                ? subjectRef.substring(prefix.length())
                : subjectRef;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * {@code deliveredCommentAuthority} 的返回值对（authority + payload）。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentAuthorityLookupService.java:425-428}。
     */
    private record DeliveredAuthority(AuthorityFact authority,
                                      CommentDomainFactService.CommentFact payload) {
    }
}
