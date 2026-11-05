package com.rag.comment.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.client.MessageRpcClient;
import com.rag.comment.domain.entity.CommentIndexEntity;
import com.rag.comment.domain.service.CommentTransactionalService;
import com.rag.comment.mapper.CommentIndexMapper;
import com.rag.comment.metrics.CommentRpcMetrics;
import com.rag.comment.service.CommentCacheService;
import com.rag.comment.service.CommentSensitiveFilter;
import com.rag.common.observability.MqConsumer;
import com.rag.proto.message.NotificationKind;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p><b>评论审计消费者</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuditListener.java:1-275}
 * （B 侧的语义来源注释写的是 Go 端
 * {@code service/comment/rpc/internal/mqs/audit_consumer.go:33-132} 的
 * {@code AuditConsumer.Consume} + {@code notifyComment} + {@code previewComment}）。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧为基准整段抄 · D13 / D16）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.comment.comment.rpc.service} 移到 {@code com.rag.comment.messaging}（D7 · D10）；</li>
 *       <li>协作者的落点按 D7 调整：
 *           {@code CommentTransactionalService} → {@code com.rag.comment.domain.service}、
 *           {@code CommentCacheService} / {@code CommentSensitiveFilter} → {@code com.rag.comment.service}、
 *           {@code MessageRpcClient} → {@code com.rag.comment.client}、
 *           {@code CommentIndexMapper} → {@code com.rag.comment.mapper}、
 *           {@code CommentIndexEntity} → {@code com.rag.comment.domain.entity}；</li>
 *       <li><b>观测包装的入参形态</b>：B 侧调共享层
 *           {@code MqConsumer.traceConsumer(Context.current(), …)}（otel {@code Context}）；
 *           C 侧共享层的签名是
 *           {@code traceConsumer(ObservationRegistry, tracerName, spanName, attrs, fn)}
 *           （Micrometer Observation 形态）⇒ 本类改为<b>注入 {@link ObservationRegistry}</b>
 *           并把 lambda 的入参类型换成 {@code Observation.Context}。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。四条出口的方向、敏感词判定只改 {@code status}、
 *       {@code previewComment} 按 rune 截断、通知的三条规则逐字未改。</li>
 *   <li><b>B 出处</b>：B 侧 {@code comment-rpc/.../service/CommentAuditListener.java:1-275}。</li>
 * </ol>
 *
 * <h2>⚠️ 四条出口的方向<b>就是</b>本类最重要的语义</h2>
 * <table border="1">
 *   <caption>B 侧 audit_consumer.go:36-51 的四条出口</caption>
 *   <tr><th>情形</th><th>B 侧动作</th><th>位点后果</th></tr>
 *   <tr><td>JSON 解析失败</td><td>记日志 + {@code return nil}</td><td><b>提交</b>（丢弃该消息）</td></tr>
 *   <tr><td>落库失败</td><td>记日志 + {@code return err}</td><td><b>不提交</b>（重投）</td></tr>
 *   <tr><td>缓存失效失败</td><td>只记日志</td><td>正常提交</td></tr>
 *   <tr><td>通知发送失败</td><td>只记日志</td><td>正常提交</td></tr>
 * </table>
 * <b>方向反了就是数据丢失或重复</b>：把解析失败改成 throw ⇒ 坏消息无限重投打满；
 * 把落库失败改成 return ⇒ 消息被确认但评论<b>永远不落库</b>。
 *
 * <p>⚠️ 解析失败「丢弃」是<b>有意的</b>：坏消息重投一万次也不会变好，
 * 而它会<b>堵住该分区的后续消息</b>。
 *
 * <p><b>⚠️ 「落库失败重投」依赖两个前提</b>：{@code ack-mode: record}
 * + listener 抛异常时<b>不</b>提交位点。见 {@code application.yml} 与
 * {@code CommentServiceApplication#kafkaListenerContainerFactory}。
 */
@Component
public class CommentAuditListener {

    private static final Logger LOG = LoggerFactory.getLogger(CommentAuditListener.class);

    /** B 侧 {@code audit_consumer.go:81} {@code messagepb.NotificationKind_ARTICLE_COMMENT}。 */
    private static final String TITLE_ARTICLE = "文章收到新评论";

    /** B 侧 {@code audit_consumer.go:89} {@code messagepb.NotificationKind_COMMENT_REPLY}。 */
    private static final String TITLE_REPLY = "评论收到新回复";

    /** B 侧 {@code audit_consumer.go:122-132} 的截断长度：40 个 rune。 */
    private static final int PREVIEW_RUNE_LIMIT = 40;

    /**
     * B 侧 {@code audit_consumer.go:34} 传给 {@code commentMessageAttrs} 的那个<b>逻辑名</b> ——
     * 即 span 上 {@code messaging.destination} 的取值。
     *
     * <p>⚠️ <b>它不是 Kafka topic 名</b>：B 侧的真 topic 是 {@code comment-audit-topic}
     * （{@code service/comment/rpc/etc/comment.yaml:26,32}），而 B 侧写进 span 的是这个
     * {@code "comment_audit"} ⇒ 逐字对齐（理由见 {@link #onMessage} 里的注释块）。
     */
    static final String AUDIT_SPAN_DESTINATION = "comment_audit";

    private final ObjectMapper objectMapper;
    private final CommentTransactionalService transactionalService;
    private final CommentCacheService cacheService;
    private final CommentSensitiveFilter sensitiveFilter;
    private final MessageRpcClient messageRpcClient;
    private final CommentIndexMapper commentIndexMapper;
    private final ObservationRegistry observationRegistry;

    public CommentAuditListener(ObjectMapper objectMapper,
                                CommentTransactionalService transactionalService,
                                CommentCacheService cacheService,
                                CommentSensitiveFilter sensitiveFilter,
                                MessageRpcClient messageRpcClient,
                                CommentIndexMapper commentIndexMapper,
                                ObservationRegistry observationRegistry) {
        this.objectMapper = objectMapper;
        this.transactionalService = transactionalService;
        this.cacheService = cacheService;
        this.sensitiveFilter = sensitiveFilter;
        this.messageRpcClient = messageRpcClient;
        this.commentIndexMapper = commentIndexMapper;
        this.observationRegistry = observationRegistry;
    }

    /**
     * 消费一条待审计的评论消息 —— 对应 B 侧 {@code CommentAuditListener.java:87-145}。
     *
     * <p>对应 B 侧 {@code kq.Queue} 注册的 {@code AuditConsumer.Consume}。
     *
     * <p>⚠️ <b>topic 用 {@code ${}} 占位符而不是 {@code #{@bean}}</b>：
     * SpEL 取 {@code @ConfigurationProperties} Bean 会在解析阶段抛 EL1058E。
     * 并发度对应 B 侧 {@code KqConsumerConf.Consumers: 8}。
     *
     * @param key   Kafka 消息的 key（写入 span 的 {@code messaging.message.key}）
     * @param value Kafka 的消息体（UTF-8 JSON）
     */
    @KafkaListener(
            topics = "${comment.kafka.audit-topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "${comment.kafka.audit-concurrency:8}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(String key, String value) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("messaging.system", "kafka");
        // 🔴 **取值口径 = B 侧的字面量 `"comment_audit"`，不是真 topic 名**。
        //
        // 【B 侧出处（消费点，不是声明点）】
        //   service/comment/rpc/internal/mqs/audit_consumer.go:34
        //       observability.TraceConsumer(ctx, "comment-rpc", "AuditConsumer.Consume",
        //           commentMessageAttrs("comment_audit", key), func(ctx context.Context) error {...})
        //   service/comment/rpc/internal/mqs/audit_consumer.go:70-77
        //       func commentMessageAttrs(topic, key string) []attribute.KeyValue {
        //           return []attribute.KeyValue{
        //               attribute.String("messaging.system", "kafka"),
        //               attribute.String("messaging.destination", topic),   // ← "comment_audit"
        //               attribute.String("messaging.operation", "consume"),
        //               attribute.String("messaging.message.key", key),
        //           }
        //       }
        //
        // 【为什么不是真 topic 名】
        //   B 侧的真 topic 是 `comment-audit-topic`（service/comment/rpc/etc/comment.yaml:26,32），
        //   但 B 侧传给 span 的是**另一个写死的逻辑名** `"comment_audit"` ⇒
        //   ⇒ **span 属性的取值属于可观察行为**（Jaeger 里的 span 结构）⇒ 必须逐字对齐 B 侧，
        //     不能「顺手改成更合理的真 topic 名」（那会与 B 侧分叉）。
        //   ⇒ 同款做法在仓内已有先例：task 域的 `TaskTraceAttributes`（同样是「逻辑名 ≠ topic 名」）。
        //
        // 【修之前的形态（真漂移）】
        //   原写法 `attrs.put("messaging.destination", "${comment.kafka.audit-topic}")` 是
        //   **Java 字面量** ⇒ `${...}` 只在 `@Value` / yaml 绑定时才会被解析，
        //   放在普通字符串里**永远不会被求值** ⇒ span 上真的带着这串占位符文本
        //   ⇒ 既不是 B 侧的 `"comment_audit"`，也不是真 topic 名 ⇒ 两侧遥测不可比。
        attrs.put("messaging.destination", AUDIT_SPAN_DESTINATION);
        attrs.put("messaging.operation", "consume");
        attrs.put("messaging.message.key", key == null ? "" : key);

        Throwable outcome = MqConsumer.traceConsumer(
                observationRegistry,
                CommentRpcMetrics.TRACER_NAME,
                "AuditConsumer.Consume",
                attrs,
                ctx -> consume(value));
        if (outcome != null) {
            // B 侧的 kq：Consume 返回 error ⇒ 不确认位点 ⇒ 重投。
            // 这里必须**抛出**而不是吞掉，否则失败的消息会被当成已处理。
            if (outcome instanceof RuntimeException re) {
                throw re;
            }
            if (outcome instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(outcome);
        }
    }

    /** 消费主体；返回非 null 即代表「失败，要重投」。 */
    private Throwable consume(String value) {
        // --- ① 解析失败 ⇒ 丢弃（B 侧 :36-39 return nil）
        CommentAuditMessage msg;
        try {
            msg = objectMapper.readValue(value, CommentAuditMessage.class);
        } catch (Exception e) {
            LOG.error("parse comment kafka message failed: {}", e.getMessage(), e);
            return null;   // ← 关键：返回 null 而不是抛异常
        }

        // --- ② 敏感词判定（B 侧 :41-46）：只改 status，**不改内容**
        boolean isSensitive = sensitiveFilter.match(msg.content());
        int status = 0;
        if (isSensitive) {
            LOG.info("comment {} matched sensitive word \"{}\"", msg.commentId(),
                    sensitiveFilter.firstHit(msg.content()));
            status = 1;
        }

        // --- ③ 落库（B 侧 :48-51）：失败 ⇒ 重投
        try {
            transactionalService.insertCommentTx(msg, status);
        } catch (RuntimeException e) {
            LOG.error("persist comment {} failed: {}", msg.commentId(), e.getMessage(), e);
            return e;
        }

        // --- ④ 缓存失效（B 侧 :52-59）：失败只记日志
        try {
            cacheService.invalidateTargetCaches(msg.targetType(), msg.targetId());
        } catch (RuntimeException e) {
            LOG.error("invalidate comment target cache failed, comment {}: {}",
                    msg.commentId(), e.getMessage());
        }
        if (msg.parentId() != 0) {
            try {
                cacheService.deleteCommentIndexCache(msg.parentId());
            } catch (RuntimeException e) {
                LOG.error("invalidate parent comment cache failed, parent {}: {}",
                        msg.parentId(), e.getMessage());
            }
        }

        // --- ⑤ 只有「正常」评论才发通知（B 侧 :61-63）
        if (status == 0) {
            notifyComment(msg);
        }

        LOG.info("comment pipeline finished, id={}, status={}", msg.commentId(), status);
        return null;
    }

    /**
     * 对应 B 侧 {@code notifyComment}（audit_consumer.go:79-120）——
     * 本方法的实现见下方 {@code notifyComment} 方法体（B 侧 {@code CommentAuditListener.java:219-251}）。
     *
     * <p>三条规则：
     * <ol>
     *   <li><b>有父评论 ⇒ 收件人是父评论作者</b>，且 kind 变成「评论收到新回复」；</li>
     *   <li><b>查父评论失败就直接放弃通知</b>（B 侧 :87-90 {@code return}），
     *       不回退到通知 up 主 —— 否则会通知错的人；</li>
     *   <li><b>{@code recipientID <= 0 || recipientID == msg.UserId} ⇒ 不发</b>
     *       （B 侧 :97-99）—— 防止「自己评论自己收到通知」。</li>
     * </ol>
     *
     * <p>发送失败<b>只记日志</b>（B 侧 :117-119）：此时评论已落库、缓存已失效，
     * 抛错会导致整条消息重投 ⇒ 重复落库 + 重复通知。
     */
    private void notifyComment(CommentAuditMessage msg) {
        long recipientId = msg.ownerId();
        NotificationKind kind = NotificationKind.ARTICLE_COMMENT;
        String title = TITLE_ARTICLE;
        String content = "你的文章收到了一条新评论：" + previewComment(msg.content());

        if (msg.parentId() != 0) {
            CommentIndexEntity parent;
            try {
                parent = commentIndexMapper.findOneById(msg.parentId());
            } catch (RuntimeException e) {
                LOG.error("query parent comment {} for notification failed: {}",
                        msg.parentId(), e.getMessage());
                return;
            }
            if (parent == null) {
                // B 侧的 First 返回 ErrRecordNotFound ⇒ 同样直接 return（:87-90）
                LOG.error("query parent comment {} for notification failed: not found", msg.parentId());
                return;
            }
            recipientId = parent.getUserId();
            kind = NotificationKind.COMMENT_REPLY;
            title = TITLE_REPLY;
            content = "你的评论收到了新的回复：" + previewComment(msg.content());
        }

        if (recipientId <= 0 || recipientId == msg.userId()) {
            return;
        }

        messageRpcClient.sendNotification(recipientId, msg.userId(), kind, title, content,
                msg.targetType(), msg.targetId(), msg.commentId(), msg.rootId(), msg.parentId());
    }

    /**
     * 对应 B 侧 {@code previewComment}（audit_consumer.go:122-132）：
     * 空 ⇒ {@code "(no content)"}；≤40 rune ⇒ 原样；否则前 40 个 rune + {@code "..."}。
     *
     * <p>⚠️ <b>按 rune 而不是 char 截断</b>：Go 的 {@code utf8.RuneCountInString} 数的是码点，
     * Java 的 {@code String.length()} 数的是 UTF-16 code unit。
     * 含 emoji / 代理对的评论在 Java 侧会少截一半字符 ⇒ 用 {@code codePointCount}。
     */
    static String previewComment(String content) {
        if (content == null) {
            return "(no content)";
        }
        String trimmed = content.strip();
        if (trimmed.isEmpty()) {
            return "(no content)";
        }
        int runeCount = trimmed.codePointCount(0, trimmed.length());
        if (runeCount <= PREVIEW_RUNE_LIMIT) {
            return trimmed;
        }
        int endIndex = trimmed.offsetByCodePoints(0, PREVIEW_RUNE_LIMIT);
        return trimmed.substring(0, endIndex) + "...";
    }
}
