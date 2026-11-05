package com.rag.comment.domain.service;

import com.rag.comment.client.ArticleRpcClient;
import com.rag.comment.contract.CommentKafkaMsg;
import com.rag.comment.domain.entity.CommentContentEntity;
import com.rag.comment.domain.entity.CommentIndexEntity;
import com.rag.comment.domain.entity.SubjectEntity;
import com.rag.comment.exception.CommentExceptions;
import com.rag.comment.mapper.CommentIndexMapper;
import com.rag.comment.mapper.ReportRecordMapper;
import com.rag.comment.mapper.SubjectMapper;
import com.rag.comment.metrics.CommentRpcMetrics;
import com.rag.comment.service.CommentCacheService;
import com.rag.comment.messaging.CommentAuditPublisher;
import com.rag.proto.comment.CommentItem;
import com.rag.proto.comment.CommentState;
import com.rag.proto.comment.CreateCommentReq;
import com.rag.proto.comment.CreateCommentResp;
import com.rag.proto.comment.DeleteCommentReq;
import com.rag.proto.comment.DeleteCommentResp;
import com.rag.proto.comment.GetCommentReq;
import com.rag.proto.comment.GetCommentResp;
import com.rag.proto.comment.LikeCommentReq;
import com.rag.proto.comment.LikeCommentResp;
import com.rag.proto.comment.ManageCommentReq;
import com.rag.proto.comment.ManageCommentResp;
import com.rag.proto.comment.ManageType;
import com.rag.proto.comment.ReportCommentReq;
import com.rag.proto.comment.ReportCommentResp;
import com.rag.proto.comment.SubjectInfo;
import com.rag.proto.comment.SubjectState;
import com.rag.proto.comment.UpdateSubjectStateReq;
import com.rag.proto.comment.UpdateSubjectStateResp;
import com.rag.common.snowflake.Snowflake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>七个 RPC 用例</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentDomainService.java:63-883}
 * （B 侧对应 Go {@code service/comment/rpc/internal/logic/} 的七个 logic 文件
 * {@code create} / {@code get} / {@code delete} / {@code like} / {@code report} /
 * {@code manage} / {@code update_subject_state} ＋ {@code subject_fallback.go}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 · 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.service.CommentDomainService}
 * ⇒ C 侧 {@code com.rag.comment.domain.service.CommentDomainService}
 * （映射表 §1.4：「7 个 logic 合 1 个 DomainService」）。
 *
 * <p><b>本类刻意不含 {@code @Transactional}</b>：B 侧 7 个 logic 里<b>没有一个</b>开事务，
 * 事务边界全在 {@link CommentTransactionalService} 的三个方法上。
 * 把事务加到这里会让 {@code GetComment} 这类只读路径也进事务
 * —— 改变隔离级别、延长快照、并可被写事务阻塞。
 *
 * <h2>⚠️ 三处「Go 返回了 0 却是有意为之」的字段不能补</h2>
 * <ol>
 *   <li><b>{@code CreateCommentResp.subject_count}</b> 恒为 0
 *       （B 侧 create_comment_logic.go:139）。因为落库发生在 Kafka 消费侧，
 *       此时还没有任何计数可读。补上真实计数会<b>新增</b>一个响应字段值。</li>
 *   <li><b>{@code GetCommentResp.subject.owner_id}</b> 恒为 0
 *       （B 侧 get_comment_logic.go:152-159 逐字段赋值，<b>没有</b> owner_id）。
 *       proto 里有这个字段，但 Go 不填。填上等于把 up 主 ID 暴露给所有读者。</li>
 *   <li><b>{@code LikeCommentResp.like_count}</b> 恒为 0
 *       （B 侧 like_comment_logic.go:94-96 只设 {@code Success: true}）。</li>
 * </ol>
 */
@Service
public class CommentDomainService {

    private static final Logger LOG = LoggerFactory.getLogger(CommentDomainService.class);

    /** Go {@code data_model.go:73} {@code ReplySortTime = "time"}。 */
    public static final String SORT_TIME = "time";

    /** Go {@code data_model.go:74} {@code ReplySortHot = "hot"}。 */
    public static final String SORT_HOT = "hot";

    /** Go {@code get_comment_logic.go:139} 的时间格式 {@code "2006-01-02 15:04:05"}。 */
    private static final DateTimeFormatter WIRE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 落库时间列渲染用的时区。
     *
     * <p>{@code comment_index.created_at} 是 TIMESTAMPTZ，B 侧的 DSN
     * （comment.yaml:9）显式钉了 {@code TimeZone=Asia/Shanghai}，
     * 因此 {@code indexRow.CreatedAt.Format(...)} 渲染的是 Asia/Shanghai 墙钟时间。
     * C 侧 JDBC URL 带同一个参数，但<b>不依赖它</b>：显式按 Asia/Shanghai 格式化，
     * 免得有人改了连接串就把 wire 上的时间整体平移。
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 分页默认/上限（Go get_comment_logic.go:60-66）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final SubjectMapper subjectMapper;
    private final CommentIndexMapper commentIndexMapper;
    private final ReportRecordMapper reportRecordMapper;
    private final CommentCacheService cacheService;
    private final CommentTransactionalService transactionalService;
    private final CommentAuditPublisher auditPublisher;
    private final ArticleRpcClient articleRpcClient;
    private final CommentRpcMetrics metrics;

    public CommentDomainService(SubjectMapper subjectMapper,
                                CommentIndexMapper commentIndexMapper,
                                ReportRecordMapper reportRecordMapper,
                                CommentCacheService cacheService,
                                CommentTransactionalService transactionalService,
                                CommentAuditPublisher auditPublisher,
                                ArticleRpcClient articleRpcClient,
                                CommentRpcMetrics metrics) {
        this.subjectMapper = subjectMapper;
        this.commentIndexMapper = commentIndexMapper;
        this.reportRecordMapper = reportRecordMapper;
        this.cacheService = cacheService;
        this.transactionalService = transactionalService;
        this.auditPublisher = auditPublisher;
        this.articleRpcClient = articleRpcClient;
        this.metrics = metrics;
    }

    // ====================================================================================
    // ① CreateComment —— Go create_comment_logic.go:37-157
    // ====================================================================================

    /**
     * 对应 Go {@code CreateComment}。
     *
     * <p><b>⚠️ 本方法不写库</b>：它只做校验、解析 ownerId、生成雪花 ID、把消息推给 Kafka。
     * 真正的落库在 {@code messaging/CommentAuditListener}
     * （B 侧是 {@code mqs/audit_consumer.go}）里。
     * 这就是「评论创建走 Kafka 而非同步写」的架构，C 侧照搬。
     */
    public CreateCommentResp createComment(CreateCommentReq req) {
        // 🔴 B 侧 create_comment_logic.go:41-42 的手写 span（tracer "comment-rpc"）
        //    ＋ :56-60 的 5 个 audit.* 属性 ⇒ 逐字对齐。
        return CommentRpcMetrics.traced(CommentRpcMetrics.TRACER_NAME, CommentRpcMetrics.CREATE, span -> {
            span.setAttribute(CommentRpcMetrics.ATTR_OPERATOR_ID, req.getUserId());
            span.setAttribute(CommentRpcMetrics.ATTR_TARGET_TYPE, req.getTargetType());
            span.setAttribute(CommentRpcMetrics.ATTR_TARGET_ID, req.getTargetId());
            span.setAttribute(CommentRpcMetrics.ATTR_ROOT_ID, req.getRootId());
            span.setAttribute(CommentRpcMetrics.ATTR_PARENT_ID, req.getParentId());
        }, () -> doCreateComment(req));
    }

    /**
     * {@link #createComment} 的业务体（B 侧 {@code create_comment_logic.go:37-157}）。
     *
     * <p>⚠️ <b>为什么拆成两个方法</b>：B 侧的 span 是<b>包住整个 logic 体</b>的
     * （{@code defer span.End()} 在 logic 末尾）⇒ C 侧必须让 span 的生命周期覆盖业务体
     * ⇒ 业务体单独成一个私有方法，公开方法只负责「开 span → 设属性 → 跑业务体」。
     */
    private CreateCommentResp doCreateComment(CreateCommentReq req) {
        long start = System.nanoTime();
        String result = "ok";
        try {
            // --- ① 入参校验（B 侧 :63-75）
            if (isBlank(req.getTargetId()) || isBlank(req.getTargetType())) {
                result = "biz_fail";
                LOG.error("business error [bizFail] target type and id cannot be empty");
                throw CommentExceptions.targetEmpty();
            }
            if (req.getContent().isEmpty()) {
                result = "biz_fail";
                LOG.error("business error [bizFail] comment content cannot be empty");
                throw CommentExceptions.contentEmpty();
            }

            // --- ② 解析 ownerId（B 侧 :77-91 → resolveOwnerID :143-156）
            long ownerId;
            try {
                ownerId = resolveOwnerId(req.getTargetType(), req.getTargetId());
            } catch (CommentExceptions.SubjectNotFound e) {
                result = "biz_fail";
                LOG.error("business error [bizFail] subject not found");
                throw CommentExceptions.subjectNotExist();
            } catch (RuntimeException e) {
                result = "sys_fail";
                LOG.error("business error [sysFail] resolve owner id failed: {}", e.getMessage(), e);
                throw CommentExceptions.dbSelectForOwner();
            }

            // --- ③ 雪花 ID（B 侧 :93-100）
            // ⚠️ 用静态的 Snowflake.nextId()（与 like 域同款）：共享层的生成状态是
            //    **唯一静态**的（SnowflakeAutoConfiguration 的 javadoc 明写）⇒
            //    注入实例与静态调用等价，而静态调用不依赖容器里是否已注册该 Bean。
            long commentId;
            try {
                commentId = Snowflake.nextId();
            } catch (RuntimeException e) {
                result = "sys_fail";
                LOG.error("snowflake id generation failed", e);
                throw CommentExceptions.snowflakeFailed();
            }

            // --- ④ 构造并推送审计消息（B 侧 :102-132）
            Instant now = Instant.now();
            CommentKafkaMsg msg = new CommentKafkaMsg(
                    commentId,
                    req.getTargetType(),
                    req.getTargetId(),
                    req.getUserId(),
                    req.getRootId(),
                    req.getParentId(),
                    req.getContent(),
                    req.getMeta(),
                    ownerId,
                    0L,
                    now.getEpochSecond());
            try {
                auditPublisher.publish(msg);
            } catch (CommentAuditPublisher.SerializationFailed e) {
                result = "sys_fail";
                throw CommentExceptions.jsonMarshalFailed();
            } catch (CommentAuditPublisher.PublishFailed e) {
                result = "sys_fail";
                throw CommentExceptions.kafkaPushFailed();
            }

            // --- ⑤ 响应（B 侧 :136-140）
            // ⚠️ subject_count 恒 0（落库在 Kafka 消费侧，此刻无可读计数）
            // ⚠️ created_at 用 JVM 默认时区格式化，对齐 B 侧的 time.Now().Format(time.DateTime)
            //    （那是**进程本地时间**，不是 PG session 时区 —— 与下面 getComment 的取值来源不同）
            return CreateCommentResp.newBuilder()
                    .setId(commentId)
                    .setCreatedAt(LocalDateTime.now().format(WIRE_TIME))
                    .setSubjectCount(0L)
                    .build();
        } finally {
            metrics.observeRpc("CreateComment", start, result);
        }
    }

    /**
     * 对应 B 侧 {@code resolveOwnerID}（create_comment_logic.go:143-156）。
     *
     * <p><b>⚠️ 一处必须照搬的 B 侧写法</b>：
     * <pre>
     * subject, fallbackErr := resolveSubjectFallback(ctx, l.svcCtx.ArticleRpc, targetType, targetID)
     * if fallbackErr != nil {
     *     return 0, err          // ← 返回**原始 err**，不是 fallbackErr
     * }
     * </pre>
     * 回落失败时返回的是<b>最初的「本地没有该评论区」</b>，
     * 而不是 article-rpc 的错误 ⇒ 上层发 1012 而不是 5003。
     * 「顺手改成返回 fallbackErr」会让新建评论区的可用性依赖变成 5003，是可观察差异。
     */
    private long resolveOwnerId(String targetType, String targetId) {
        Long ownerId = subjectMapper.selectOwnerId(targetType, targetId);
        if (ownerId != null) {
            return ownerId;
        }
        // 本地没有 ⇒ 走 article 回落
        Long fallback = resolveSubjectFallback(targetType, targetId);
        if (fallback == null) {
            // 抛 SubjectNotFound，让上层发 1012（不是 5003）
            throw new CommentExceptions.SubjectNotFound();
        }
        return fallback;
    }

    /**
     * 对应 B 侧 {@code resolveSubjectFallback}（subject_fallback.go:12-38）。
     *
     * <p><b>三个判定都要保留</b>：
     * <ol>
     *   <li>{@code targetType} 必须大小写不敏感地等于 {@code "article"}
     *       （B 侧用 {@code strings.EqualFold(strings.TrimSpace(targetType), "article")}）；</li>
     *   <li>article-rpc 必须真的返回 article；</li>
     *   <li>{@code authorId} 必须是正整数（解析失败或 ≤ 0 都算失败）。</li>
     * </ol>
     * 失败一律返回 {@code null}（B 侧返回同一个 {@code ErrorSubjectNotFound}，不区分原因）。
     */
    private Long resolveSubjectFallback(String targetType, String targetId) {
        if (targetType == null || !targetType.strip().equalsIgnoreCase("article")) {
            return null;
        }
        return articleRpcClient.fetchAuthorId(targetId);
    }

    // ====================================================================================
    // ② GetComment —— Go get_comment_logic.go:32-178
    // ====================================================================================

    /**
     * 对应 B 侧 {@code GetComment}。
     *
     * <p><b>⚠️ 排序默认值是「反直觉」的，必须照搬</b>（B 侧 get_comment_logic.go:78-81）：
     * <pre>
     * sortType := model.ReplySortTime          // 先假设时间序
     * if in.SortType == 0 { sortType = model.ReplySortHot }   // 0（proto 默认）⇒ 改成热度序
     * </pre>
     * ⇒ {@code sort_type} 传 0 得到的是<b>热度序</b>，传非 0 才是时间序。
     *
     * <p><b>⚠️ 只查正常状态的开关恒为 false</b>（:98 {@code OnlyNormal: false}）⇒
     * <b>已删除 / 审核中的评论也会出现在列表里</b>。这是 B 侧的现状，不修正。
     *
     * <h3>⚠️ 已知的内容/统计配对风险（刻意保留 B 侧的行为）</h3>
     * <p>B 侧按下标配对 index 行与 content 行，只防越界（:123-125）：
     * <pre>
     * for idx := range indexRows {
     *     if idx &gt;= len(contentRows) { break }
     *     indexRow := indexRows[idx]
     *     contentRow := contentRows[idx]
     * </pre>
     * 若某条评论缺 {@code comment_content} 行，content 列表整体前移 ⇒ 内容与统计串位。
     * 触发前提是 DB 数据已损坏（缺正文行），B 侧同样会错。
     * <b>不修正</b>：修正会改变已有部署的可观察输出，且这属于数据一致性问题而非 RPC 语义问题。
     */
    public GetCommentResp getComment(GetCommentReq req) {
        // 🔴 B 侧 get_comment_logic.go:52-53 的手写 span —— 🔴 **tracer 名带点**（"comment.rpc"）
        //    ＋ :70-75 的 6 个**无前缀**属性（target_id / root_id / page / page_size / target_type / sort_type）。
        //    ⚠️ page / page_size 记的是**原始入参**（B 侧 :72-73 用 in.Page / in.PageSize，不是归一后的值）⇒ 此处照搬。
        //    ⚠️ B 侧的这 4 个错误分支**额外**显式 SetStatus(Error) ⇒ 用 tracedWithErrorStatus。
        return CommentRpcMetrics.tracedWithErrorStatus(CommentRpcMetrics.TRACER_NAME_LIST,
                CommentRpcMetrics.GET_COMMENT, span -> {
                    span.setAttribute(CommentRpcMetrics.ATTR_LIST_TARGET_ID, req.getTargetId());
                    span.setAttribute(CommentRpcMetrics.ATTR_LIST_ROOT_ID, req.getRootId());
                    span.setAttribute(CommentRpcMetrics.ATTR_LIST_PAGE, req.getPage());
                    span.setAttribute(CommentRpcMetrics.ATTR_LIST_PAGE_SIZE, req.getPageSize());
                    span.setAttribute(CommentRpcMetrics.ATTR_LIST_TARGET_TYPE, req.getTargetType());
                    span.setAttribute(CommentRpcMetrics.ATTR_LIST_SORT_TYPE, req.getSortType());
                }, () -> doGetComment(req));
    }

    /**
     * {@link #getComment} 的业务体（B 侧 {@code get_comment_logic.go:32-178}）。
     *
     * <p>⚠️ <b>为什么拆成两个方法</b>：见 {@link #doCreateComment} 的同名说明（span 必须包住整个 logic 体）。
     */
    private GetCommentResp doGetComment(GetCommentReq req) {
        long start = System.nanoTime();
        String result = "ok";
        try {
            // --- ① 分页归一（B 侧 :56-67）
            int page = (int) req.getPage();
            if (page <= 0) {
                page = 1;
            }
            int pageSize = (int) req.getPageSize();
            if (pageSize <= 0) {
                pageSize = DEFAULT_PAGE_SIZE;
            }
            if (pageSize > MAX_PAGE_SIZE) {
                pageSize = MAX_PAGE_SIZE;
            }
            int offset = (page - 1) * pageSize;

            // --- ② 排序（B 侧 :78-81，注意 0 ⇒ hot）
            String sort = req.getSortType() == 0 ? SORT_HOT : SORT_TIME;

            // --- ③ 载入评论区（B 侧 :83-88 → loadSubject :163-178）
            SubjectEntity subject = loadSubject(req.getTargetType(), req.getTargetId());

            // --- ④ 分页 id（B 侧 :91-104）
            List<Long> ids = cacheService.getReplyIdsPageCache(
                    req.getTargetType(), req.getTargetId(), req.getRootId(), sort, offset, pageSize, false);

            // --- ⑤ 批量取 index + content（B 侧 :106-144）
            List<CommentItem> items = new ArrayList<>();
            if (ids != null && !ids.isEmpty()) {
                List<CommentIndexEntity> indexRows = cacheService.getCommentIndexCache(ids);
                List<CommentContentEntity> contentRows = cacheService.batchGetContentCache(ids);
                for (int idx = 0; idx < indexRows.size(); idx++) {
                    if (idx >= contentRows.size()) {
                        break;      // B 侧 :123-125 的越界保护
                    }
                    CommentIndexEntity indexRow = indexRows.get(idx);
                    CommentContentEntity contentRow = contentRows.get(idx);
                    items.add(CommentItem.newBuilder()
                            .setId(indexRow.getId())
                            .setUserId(indexRow.getUserId())
                            .setContent(contentRow.getContent())
                            .setRootId(indexRow.getRootId())
                            .setParentId(indexRow.getParentId())
                            .setLikeCount(indexRow.getLikeCount())
                            .setDislikeCount(indexRow.getDislikeCount())
                            .setReplyCount(indexRow.getReplyCount())
                            .setAttribute(indexRow.getAttribute())
                            .setState(toCommentState(indexRow.getState()))
                            .setCreatedAt(formatBusinessTime(indexRow.getCreatedAt()))
                            .setMeta(contentRow.getMeta() == null ? "" : contentRow.getMeta())
                            // B 侧 :141  Children: nil —— proto3 默认值，不必显式设置
                            .build());
                }
            }
            metrics.setListSize(items.size());

            // --- ⑥ 响应（B 侧 :150-160）
            // ⚠️ owner_id 不设置：B 侧逐字段赋值时没有它（见类级说明）
            return GetCommentResp.newBuilder()
                    .addAllComment(items)
                    .setSubject(SubjectInfo.newBuilder()
                            .setTargetType(subject.getTargetType())
                            .setTargetId(subject.getTargetId())
                            .setTotalCount(subject.getTotalCount())
                            .setRootCount(subject.getRootCount())
                            .setState(toSubjectState(subject.getState()))
                            .setAttribute(subject.getAttribute())
                            .build())
                    .build();
        } catch (RuntimeException e) {
            result = "sys_fail";
            throw e;
        } finally {
            // ⚠️ B 侧的 GetComment 只有 {ok, sys_fail} 两个 result 值（:35-38）——
            //    它从不构造业务错误。不要在这里统一发 biz_fail。
            metrics.observeRpc("GetComment", start, result);
        }
    }

    /**
     * 对应 B 侧 {@code loadSubject}（get_comment_logic.go:163-178）。
     *
     * <p><b>⚠️ 两处 B 侧的写法要照搬</b>：
     * <ol>
     *   <li>本地查不到时走 article 回落；<b>回落也失败时返回的是原始的
     *       {@code ErrorSubjectNotFound}</b>（B 侧 :174 {@code return model.Subject{}, err}，
     *       不是 {@code fallbackErr}）⇒ 上层发 1012 而不是 5003。</li>
     *   <li>回落成功后<b>把计数为 0 的 subject 缓存 5 分钟</b>（B 侧 :176
     *       {@code SetSubjectCache(ctx, targetID, &subject, 5*time.Minute)}）。
     *       这意味着「新建出来的评论区，5 分钟内 total_count 会显示 0」——
     *       缓存失效路径是靠 {@code InvalidateTargetCaches} 主动清的，
     *       而 article 回落这条路径<b>不</b>失效它。</li>
     * </ol>
     */
    private SubjectEntity loadSubject(String targetType, String targetId) {
        try {
            return cacheService.getSubjectWithCache(targetType, targetId);
        } catch (CommentExceptions.SubjectNotFound e) {
            Long fallbackOwner = resolveSubjectFallback(targetType, targetId);
            if (fallbackOwner == null) {
                throw e;     // 返回原始 err ⇒ 1012
            }
            SubjectEntity fallback = new SubjectEntity();
            fallback.setTargetType(targetType);
            fallback.setTargetId(targetId);
            fallback.setTotalCount(0L);
            fallback.setRootCount(0L);
            fallback.setState(0);
            fallback.setAttribute(0L);
            fallback.setOwnerId(fallbackOwner);
            // B 侧 :176 —— 注意这里是**主动写缓存**，即使它此刻还没有任何评论
            try {
                cacheService.writeSubjectCache(targetId, fallback, Duration.ofMinutes(5));
            } catch (RuntimeException cacheErr) {
                LOG.warn("set fallback subject cache failed for target {}: {}", targetId, cacheErr.getMessage());
            }
            return fallback;
        }
    }

    // ====================================================================================
    // ③ DeleteComment —— Go delete_comment_logic.go:33-136
    // ====================================================================================

    /** 对应 B 侧 {@code DeleteComment}：事务外预检 + 事务内删除 + 缓存失效。 */
    public DeleteCommentResp deleteComment(DeleteCommentReq req) {
        // 🔴 B 侧 delete_comment_logic.go:37-38 的手写 span（tracer "comment-rpc"）
        //    ＋ :52-55 的 4 个 audit.* 属性 ⇒ 逐字对齐。
        return CommentRpcMetrics.traced(CommentRpcMetrics.TRACER_NAME, CommentRpcMetrics.DELETE, span -> {
            span.setAttribute(CommentRpcMetrics.ATTR_OPERATOR_ID, req.getUserId());
            span.setAttribute(CommentRpcMetrics.ATTR_COMMENT_ID, req.getCommentId());
            span.setAttribute(CommentRpcMetrics.ATTR_TARGET_TYPE, req.getTargetType());
            span.setAttribute(CommentRpcMetrics.ATTR_TARGET_ID, req.getTargetId());
        }, () -> doDeleteComment(req));
    }

    /**
     * {@link #deleteComment} 的业务体（B 侧 {@code delete_comment_logic.go:33-136}）。
     *
     * <p>⚠️ <b>为什么拆成两个方法</b>：见 {@link #doCreateComment} 的同名说明。
     */
    private DeleteCommentResp doDeleteComment(DeleteCommentReq req) {
        long start = System.nanoTime();
        String result = "ok";
        try {
            // --- ① 入参校验（B 侧 :58-63）
            if (req.getCommentId() == 0L || isBlank(req.getTargetType()) || isBlank(req.getTargetId())) {
                result = "biz_fail";
                LOG.error("business error [bizFail] comment id and target cannot be empty");
                throw CommentExceptions.deleteTargetEmpty();
            }

            // --- ② 预检评论（B 侧 :65-79）
            CommentIndexEntity comment;
            try {
                comment = commentIndexMapper.findOneById(req.getCommentId());
            } catch (RuntimeException e) {
                result = "sys_fail";
                throw CommentExceptions.dbUpdateOnDelete();
            }
            if (comment == null) {
                result = "biz_fail";
                throw CommentExceptions.commentNotExist();
            }

            // --- ③ 预检评论区（B 侧 :81-95）
            SubjectEntity subject;
            try {
                subject = subjectMapper.findOneByTarget(req.getTargetType(), req.getTargetId());
            } catch (RuntimeException e) {
                result = "sys_fail";
                throw CommentExceptions.dbUpdateOnDelete();
            }
            if (subject == null) {
                result = "biz_fail";
                throw CommentExceptions.deleteSubjectNotExist();
            }

            // --- ④ 事务外鉴权预检（B 侧 :97-102）：本人 或 up 主
            if (req.getUserId() != comment.getUserId() && req.getUserId() != subject.getOwnerId()) {
                result = "biz_fail";
                LOG.error("business error [bizFail] user {} has no right", req.getUserId());
                throw CommentExceptions.deleteForbidden();
            }

            // --- ⑤ 事务内删除（B 侧 :104-121；重复删除在事务内幂等）
            long remainCount;
            try {
                remainCount = transactionalService.deleteCommentTx(
                        req.getCommentId(), req.getUserId(), req.getTargetType(), req.getTargetId());
            } catch (CommentExceptions.CommentNotFound e) {
                result = "biz_fail";
                throw CommentExceptions.commentNotExist();
            } catch (CommentExceptions.CommentForbidden e) {
                result = "biz_fail";
                throw CommentExceptions.deleteForbidden();
            } catch (RuntimeException e) {
                result = "sys_fail";
                throw CommentExceptions.dbUpdateOnDelete();
            }

            // --- ⑥ 缓存失效（B 侧 :122-127）：失败只记日志，**不阻断**
            try {
                cacheService.invalidateTargetCaches(req.getTargetType(), req.getTargetId());
            } catch (RuntimeException e) {
                LOG.error("invalidate comment target cache failed after delete, comment {}: {}",
                        req.getCommentId(), e.getMessage());
            }
            try {
                cacheService.deleteCommentIndexCache(req.getCommentId(), comment.getParentId());
            } catch (RuntimeException e) {
                LOG.error("invalidate comment item cache failed after delete, comment {}: {}",
                        req.getCommentId(), e.getMessage());
            }

            return DeleteCommentResp.newBuilder()
                    .setSuccess(true)
                    .setSubjectTotalCount(remainCount)
                    .build();
        } finally {
            metrics.observeRpc("DeleteComment", start, result);
        }
    }

    // ====================================================================================
    // ④ LikeComment —— Go like_comment_logic.go:31-98
    // ====================================================================================

    /**
     * 对应 B 侧 {@code LikeComment}。
     *
     * <p>🔴 <b>刻意不加 span</b>：B 侧的 {@code like_comment_logic.go} <b>没有任何 otel / span 代码</b>
     * （实测 {@code tracer.Start} 命中数 = 0）⇒ 本方法<b>不</b>套 {@link CommentRpcMetrics} 的 traced。
     * <b>这不是遗漏，是对齐</b>：B 侧没有的，C 侧也不该凭空加
     * ⇒ 否则 trace 里的 comment span 数会从 6 变 7，与真源不符。
     * 该「6 而非 7」的事实由 {@code CommentSpanWiringTest} 双向钉死。
     */
    public LikeCommentResp likeComment(LikeCommentReq req) {
        long start = System.nanoTime();
        String result = "ok";
        try {
            // --- ① 入参校验（B 侧 :45-50）
            if (req.getCommentId() == 0L) {
                result = "biz_fail";
                LOG.error("business error [bizFail] comment id cannot be empty");
                throw CommentExceptions.likeCommentIdEmpty();
            }

            // --- ② 取 up 主 ID（B 侧 :52-65）
            // ⚠️ B 侧的兜底分支「日志记 5004、线上发 2001」—— 照搬，不修
            Long ownerId;
            try {
                ownerId = subjectMapper.selectOwnerId(req.getTargetType(), req.getTargetId());
            } catch (RuntimeException e) {
                result = "sys_fail";
                LOG.error("business error [sysFail] db error: {}", e.getMessage());
                throw CommentExceptions.likeOwnerLookupFailed();
            }
            if (ownerId == null) {
                result = "biz_fail";
                LOG.error("business error [bizFail] subject not found");
                throw CommentExceptions.likeSubjectNotExist();
            }

            // --- ③ 事务内互动（B 侧 :67-88）
            try {
                transactionalService.likeCommentTx(req.getUserId(), req.getCommentId(),
                        req.getTargetType(), req.getTargetId(), req.getActionType(), ownerId);
            } catch (CommentExceptions.CommentNotFound e) {
                result = "biz_fail";
                throw CommentExceptions.likeCommentNotExist();
            } catch (RuntimeException e) {
                result = "sys_fail";
                throw CommentExceptions.dbUpdateOnLike();
            }

            // --- ④ 缓存失效（B 侧 :89-91）：失败只记日志
            try {
                cacheService.deleteCommentIndexCache(req.getCommentId());
            } catch (RuntimeException e) {
                LOG.error("invalidate comment cache failed after like, comment {}: {}",
                        req.getCommentId(), e.getMessage());
            }

            // ⚠️ like_count 不设置：B 侧 :94-96 只设 Success（见类级说明）
            return LikeCommentResp.newBuilder().setSuccess(true).build();
        } finally {
            metrics.observeRpc("LikeComment", start, result);
        }
    }

    // ====================================================================================
    // ⑤ ReportComment —— Go report_comment_logic.go:33-101
    // ====================================================================================

    /**
     * 对应 B 侧 {@code ReportComment}。
     *
     * <p><b>⚠️ 重复举报被静默吞掉但仍返回成功</b>：{@code InsertReport} 的
     * {@code ON CONFLICT DO NOTHING} 命中 {@code idx_user_comment_report} 时不报错，
     * 接口照样回 {@code success = true}（B 侧 :97-99）。这是 B 侧的现状。
     */
    public ReportCommentResp reportComment(ReportCommentReq req) {
        // 🔴 B 侧 report_comment_logic.go:37-38 的手写 span（tracer "comment-rpc"）
        //    ＋ :53-57 的 5 个 audit.* 属性 ⇒ 逐字对齐。
        //    ⚠️ audit.reason 记的是**枚举的 proto 值名**（B 侧 :57 in.Reason.String()）⇒ 用 enumName 对齐
        //    （proto3 未知枚举值在 B 侧会写成数字字符串，Java 的 name() 会写成 UNRECOGNIZED）。
        return CommentRpcMetrics.traced(CommentRpcMetrics.TRACER_NAME, CommentRpcMetrics.REPORT, span -> {
            span.setAttribute(CommentRpcMetrics.ATTR_OPERATOR_ID, req.getUserId());
            span.setAttribute(CommentRpcMetrics.ATTR_COMMENT_ID, req.getCommentId());
            span.setAttribute(CommentRpcMetrics.ATTR_TARGET_TYPE, req.getTargetType());
            span.setAttribute(CommentRpcMetrics.ATTR_TARGET_ID, req.getTargetId());
            span.setAttribute(CommentRpcMetrics.ATTR_REASON,
                    CommentRpcMetrics.enumName(req.getReason(), req.getReasonValue()));
        }, () -> doReportComment(req));
    }

    /**
     * {@link #reportComment} 的业务体（B 侧 {@code report_comment_logic.go:33-101}）。
     *
     * <p>⚠️ <b>为什么拆成两个方法</b>：见 {@link #doCreateComment} 的同名说明。
     */
    private ReportCommentResp doReportComment(ReportCommentReq req) {
        long start = System.nanoTime();
        String result = "ok";
        try {
            if (req.getCommentId() == 0L) {
                result = "biz_fail";
                LOG.error("business error [bizFail] comment id cannot be empty");
                throw CommentExceptions.reportCommentIdEmpty();
            }
            try {
                reportRecordMapper.insertIgnoreConflict(
                        req.getUserId(), req.getCommentId(),
                        req.getTargetType(), req.getTargetId(),
                        // ⚠️ 用 getReasonValue() 而不是 getReason().getNumber()：
                        //    proto3 的未知枚举值在生成物里是 UNRECOGNIZED，
                        //    它的 getNumber() 返回 null ⇒ NPE。
                        req.getReasonValue(),
                        req.getDetail());
            } catch (RuntimeException e) {
                result = "sys_fail";
                LOG.error("business error [sysFail] insert report failed: {}", e.getMessage(), e);
                throw CommentExceptions.dbInsertOnReport();
            }
            return ReportCommentResp.newBuilder().setSuccess(true).build();
        } finally {
            metrics.observeRpc("ReportComment", start, result);
        }
    }

    // ====================================================================================
    // ⑥ ManageComment —— Go manage_comment_logic.go:33-109
    // ====================================================================================

    /**
     * 对应 B 侧 {@code ManageComment}：置顶 / 取消置顶 / 精选 / 取消精选。
     *
     * <p><b>⚠️ 没有鉴权</b>：B 侧这个用例<b>只校验 commentId 非空</b>，
     * 没有任何「调用者是不是 up 主」的判断（manage_comment_logic.go:59-86）。
     * 与 {@code UpdateSubjectState} 形成鲜明对比（后者有 {@code in.UserId != subject.OwnerId} 检查）。
     * <b>照搬</b>，不补鉴权 —— 补上会改变可观察行为（原本能成功的调用会变成 1008）。
     */
    public ManageCommentResp manageComment(ManageCommentReq req) {
        // 🔴 B 侧 manage_comment_logic.go:37-38 的手写 span（tracer "comment-rpc"）
        //    ＋ :52-56 的 5 个 audit.* 属性 ⇒ 逐字对齐。
        //    ⚠️ audit.action_type 记的是**枚举的 proto 值名**（B 侧 :54 in.ActionType.String()）⇒ 用 enumName 对齐。
        return CommentRpcMetrics.traced(CommentRpcMetrics.TRACER_NAME, CommentRpcMetrics.MANAGE, span -> {
            span.setAttribute(CommentRpcMetrics.ATTR_OPERATOR_ID, req.getUserId());
            span.setAttribute(CommentRpcMetrics.ATTR_COMMENT_ID, req.getCommentId());
            span.setAttribute(CommentRpcMetrics.ATTR_ACTION_TYPE,
                    CommentRpcMetrics.enumName(req.getActionType(), req.getActionTypeValue()));
            span.setAttribute(CommentRpcMetrics.ATTR_TARGET_TYPE, req.getTargetType());
            span.setAttribute(CommentRpcMetrics.ATTR_TARGET_ID, req.getTargetId());
        }, () -> doManageComment(req));
    }

    /**
     * {@link #manageComment} 的业务体（B 侧 {@code manage_comment_logic.go:33-109}）。
     *
     * <p>⚠️ <b>为什么拆成两个方法</b>：见 {@link #doCreateComment} 的同名说明。
     */
    private ManageCommentResp doManageComment(ManageCommentReq req) {
        long start = System.nanoTime();
        String result = "ok";
        try {
            if (req.getCommentId() == 0L) {
                result = "biz_fail";
                LOG.error("business error [bizFail] comment id cannot be empty");
                throw CommentExceptions.manageCommentIdEmpty();
            }

            // 位偏移（B 侧 :66-86）：置顶=第 2 位、精选=第 3 位
            long mask;
            boolean set;
            ManageType actionType = req.getActionType();
            if (actionType == ManageType.MANAGE_PIN) {
                mask = 1L << 2;
                set = true;
            } else if (actionType == ManageType.MANAGE_UNPIN) {
                mask = 1L << 2;
                set = false;
            } else if (actionType == ManageType.MANAGE_FEATURE) {
                mask = 1L << 3;
                set = true;
            } else if (actionType == ManageType.MANAGE_UNFEATURE) {
                mask = 1L << 3;
                set = false;
            } else {
                // 含 UNKNOWN(0) 与 UNRECOGNIZED：B 侧的 default 分支同样把它们拦成 2001
                result = "biz_fail";
                LOG.error("business error [bizFail] unknown manage action {}", actionType);
                throw CommentExceptions.unknownManageAction();
            }

            int affected;
            try {
                affected = set
                        ? commentIndexMapper.setAttributeBits(req.getCommentId(), mask)
                        : commentIndexMapper.clearAttributeBits(req.getCommentId(), mask);
            } catch (RuntimeException e) {
                result = "sys_fail";
                throw CommentExceptions.dbUpdateOnManage();
            }
            if (affected == 0) {
                result = "biz_fail";
                throw CommentExceptions.manageCommentNotExist();
            }
            return ManageCommentResp.newBuilder().setSuccess(true).build();
        } finally {
            metrics.observeRpc("ManageComment", start, result);
        }
    }

    // ====================================================================================
    // ⑦ UpdateSubjectState —— Go update_subject_state_logic.go:33-115
    // ====================================================================================

    /**
     * 对应 B 侧 {@code UpdateSubjectState}：只有 up 主能改评论区状态。
     *
     * <p>⚠️ 鉴权只有「是不是 up 主」一条（B 侧 :85），
     * <b>没有 admin 分支</b> —— 尽管 proto 注释写着「只有up主或管理员才有权关闭评论区」
     * （update_subject_state_logic.go:33 原文：「只有up主或管理员才有权关闭评论区」）。
     * <b>注释与实现不一致，照实现</b>。
     */
    public UpdateSubjectStateResp updateSubjectState(UpdateSubjectStateReq req) {
        // 🔴 B 侧 update_subject_state_logic.go:37-38 的手写 span（tracer "comment-rpc"）
        //    ＋ :53-56 的 4 个 audit.* 属性 ⇒ 逐字对齐。
        //    ⚠️ audit.target_state 记的是**枚举的编号**（B 侧 :56 int64(in.State)）⇒ 用 getStateValue()（不是名字）。
        return CommentRpcMetrics.traced(CommentRpcMetrics.TRACER_NAME,
                CommentRpcMetrics.UPDATE_SUBJECT_STATE, span -> {
                    span.setAttribute(CommentRpcMetrics.ATTR_OPERATOR_ID, req.getUserId());
                    span.setAttribute(CommentRpcMetrics.ATTR_TARGET_TYPE, req.getTargetType());
                    span.setAttribute(CommentRpcMetrics.ATTR_TARGET_ID, req.getTargetId());
                    span.setAttribute(CommentRpcMetrics.ATTR_TARGET_STATE, (long) req.getStateValue());
                }, () -> doUpdateSubjectState(req));
    }

    /**
     * {@link #updateSubjectState} 的业务体（B 侧 {@code update_subject_state_logic.go:33-115}）。
     *
     * <p>⚠️ <b>为什么拆成两个方法</b>：见 {@link #doCreateComment} 的同名说明。
     */
    private UpdateSubjectStateResp doUpdateSubjectState(UpdateSubjectStateReq req) {
        long start = System.nanoTime();
        String result = "ok";
        try {
            if (isBlank(req.getTargetType()) || isBlank(req.getTargetId())) {
                result = "biz_fail";
                LOG.error("business error [bizFail] target type and id cannot be empty");
                throw CommentExceptions.subjectTargetEmpty();
            }

            SubjectEntity subject;
            try {
                subject = subjectMapper.findOneByTarget(req.getTargetType(), req.getTargetId());
            } catch (RuntimeException e) {
                result = "sys_fail";
                throw CommentExceptions.dbUpdateOnDelete();
            }
            if (subject == null) {
                result = "biz_fail";
                throw CommentExceptions.updateSubjectNotExist();
            }

            if (req.getUserId() != subject.getOwnerId()) {
                result = "biz_fail";
                LOG.error("business error [bizFail] user {} is not the owner", req.getUserId());
                throw CommentExceptions.subjectStateForbidden();
            }

            int affected;
            try {
                // ⚠️ 用 getStateValue()：proto3 未知枚举值在生成物里是 UNRECOGNIZED，
                //    getState().getNumber() 会 NPE。B 侧是 int32(in.State) 原样透传。
                affected = subjectMapper.updateState(req.getTargetType(), req.getTargetId(), req.getStateValue());
            } catch (RuntimeException e) {
                result = "sys_fail";
                throw CommentExceptions.dbUpdateOnSubjectState();
            }
            if (affected == 0) {
                result = "biz_fail";
                throw CommentExceptions.updateSubjectNotExist();
            }
            return UpdateSubjectStateResp.newBuilder().setSuccess(true).build();
        } finally {
            metrics.observeRpc("UpdateSubjectState", start, result);
        }
    }

    // ====================================================================================
    // 工具
    // ====================================================================================

    /**
     * 把 DB 的 int32 state 转成 proto 枚举。
     *
     * <p>⚠️ <b>平台限制</b>：B 侧是 {@code pb.CommentState(subject.State)} 的直接数值转换，
     * proto3 下未知值会原样上线。protobuf-java 的枚举无法表达未知数值 ⇒ 落回默认值(0)。
     * 本域的 state 取值域是 0/1/2/3（全部已知），实际不触发；
     * 但 {@code UpdateSubjectState} 会把客户端传来的任意数值写进 subject.state，
     * 那条路径上可能命中。与 {@code contract/GrpcErrFactory} 处理 gRPC 业务码的限制同源。
     */
    private static CommentState toCommentState(int state) {
        CommentState resolved = CommentState.forNumber(state);
        return resolved == null ? CommentState.STATE_NORMAL : resolved;
    }

    /** 同 {@link #toCommentState}。subject.state 取值域 0/1/2。 */
    private static SubjectState toSubjectState(int state) {
        SubjectState resolved = SubjectState.forNumber(state);
        return resolved == null ? SubjectState.SUBJECT_OPEN : resolved;
    }

    /**
     * 把落库时间渲染成 wire 上的 {@code "2006-01-02 15:04:05"}。
     *
     * <p>见 {@link #BUSINESS_ZONE} 的说明：显式按 Asia/Shanghai 渲染，对齐 B 侧
     * DSN 里的 {@code TimeZone=Asia/Shanghai}。
     */
    private static String formatBusinessTime(java.time.OffsetDateTime createdAt) {
        if (createdAt == null) {
            return "";
        }
        return createdAt.atZoneSameInstant(BUSINESS_ZONE).format(WIRE_TIME);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
