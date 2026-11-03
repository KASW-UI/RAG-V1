package com.rag.article.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.article.client.ArticleObjectStorageClient;
import com.rag.article.client.ContentAuditPort;
import com.rag.article.contract.ArticleSyncEvent;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.mapper.ArticleMapper;
import com.rag.article.metrics.ArticleRpcMetrics;
import com.rag.article.domain.service.ArticleAtomicService;
import com.rag.common.observability.MqConsumer;
import com.rag.common.snowflake.Snowflake;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p><b>职责</b>：审核消费者 —— 消费「待审核文章」消息，判四元组、读正文、跑内容审核、
 * 入队同步 outbox。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mq/ArticleReviewConsumer.java}
 * （类声明 {@code :58-59} · {@code MARKDOWN_IMAGE} {@code :70} · 字段与构造器 {@code :72-94} ·
 * {@code onMessage} {@code :103-132} · {@code consume} {@code :144-151} · {@code toRuntime} {@code :153-155} ·
 * {@code doConsume} {@code :157-223} · {@code matchesReviewTuple} {@code :234-240} ·
 * {@code contentEquals} {@code :242-244} · {@code extValue} {@code :246-248} ·
 * {@code auditArticle} {@code :267-314} · {@code rejectAsAd} {@code :325-333} ·
 * {@code extractImageUrls} {@code :343-359} · {@code buildImageDataUri} {@code :378-399} ·
 * {@code mimeTypeOf} {@code :406-415} · {@code isBlank} {@code :417-419}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：五处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.mq} → {@code com.rag.article.messaging}（D7 包压平）；</li>
 *         <li><b>契约载体</b>：{@code ArticleSyncEventTypes.ArticleReviewMessage} →
 *             {@link ArticleSyncEvent.ArticleReviewMessage}（B 侧的工具类拆成
 *             <b>顶层 record ＋ 嵌套 record</b>，见 {@code contract/ArticleSyncEvent}）；
 *             ⇒ record 的<b>字段访问从 {@code msg.articleId} 变成 {@code msg.articleId()}</b>
 *             （纯语法差异，<b>字段名与 JSON 名一字未改</b>）；</li>
 *         <li><b>proto 包名</b>：{@code com.rag.article.article.proto.ArticleStatus} →
 *             {@code com.rag.proto.article.ArticleStatus}（D7）；</li>
 *         <li><b>雪花 ID</b>：B 侧注入 {@code com.rag.common.spring.SnowflakeBean}
 *             ⇒ 本仓改用共享层的 {@link Snowflake}（{@code Snowflake.nextId()} 静态方法，
 *             状态仍是<b>进程级唯一</b>）；</li>
 *         <li><b>观测 API</b>：B 侧 {@code MqConsumer.traceConsumer(Context.current(), …)}（OTel {@code Context}）
 *             ⇒ 本仓共享层是 {@code MqConsumer.traceConsumer(ObservationRegistry, …)}
 *             （Micrometer Observation + OTel 双载体）⇒ 本类改为<b>注入
 *             {@link ObjectProvider}{@code <ObservationRegistry>}</b>（共享层同一手法），
 *             registry 缺失时<b>不产出观测但错误分类与返回值不变</b>。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>七条分支的「丢 / 重投」方向一字未改</b>（见下表）；</li>
 *         <li>ack 契约方向与 Go 相反这件事<b>原样保留</b>：drop 分支<b>正常 return</b>，
 *             retry 分支<b>抛异常</b>；</li>
 *         <li>「为什么不加进程内重试」这条纪律原样保留（<b>零重试</b>，
 *             错误处理器由 {@code ArticleServiceApplication} 单点定义）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🔴 本类的全部价值在于「哪些分支必须丢、哪些必须重投」</h2>
 * <table border="1">
 *   <caption>Go 的五条出口 → Java 的方向</caption>
 *   <tr><th>Go 分支</th><th>Go 返回</th><th>后果</th><th>Java 侧</th></tr>
 *   <tr><td>JSON 解析失败（:49-52）</td><td>{@code nil}</td><td><b>丢弃</b>，提交位点</td><td>正常 return</td></tr>
 *   <tr><td>文章不存在（:55-57）</td><td>{@code nil}</td><td><b>丢弃</b>，提交位点</td><td>正常 return</td></tr>
 *   <tr><td>四元组不匹配（:62-68）</td><td>{@code nil}</td><td><b>丢弃</b>（过期审核任务）</td><td>正常 return</td></tr>
 *   <tr><td>幂等命中（:85-87, :111-113）</td><td>{@code nil}</td><td><b>丢弃</b>（已入队）</td><td>正常 return</td></tr>
 *   <tr><td>MinIO 读失败（:70-81）</td><td><code>err</code></td><td><b>重投</b></td><td>抛异常</td></tr>
 *   <tr><td>审核 RPC 失败（:89-91）</td><td><code>err</code></td><td><b>重投</b></td><td>抛异常</td></tr>
 *   <tr><td>事务失败（:133-136）</td><td><code>err</code></td><td><b>重投</b></td><td>抛异常</td></tr>
 * </table>
 *
 * <p><b>Java 侧的 ack 契约方向与 Go 相反</b>：Go 是「返回 error ⇒ 不确认」，
 * Spring Kafka 是「listener 正常返回 ⇒ 提交位点；抛异常 ⇒ 不提交」。
 * ⇒ drop 分支<b>正常 return</b>，retry 分支<b>抛异常</b>。
 *
 * <p><b>⚠️ 为什么不加进程内重试</b>：Go 的 kq（go-queue v1.2.2 {@code kq/queue.go:230-238}）
 * 在返回 error 后<b>没有重试循环</b>，消息只是没被确认（{@code ForceCommit=false} ⇒ 不 CommitMessages），
 * 等消费者重启 / rebalance 后自然重投。加 {@code FixedBackOff} 做 N 次进程内重试是<b>新增行为</b>
 * （改变重试节奏与日志量）。故错误处理器为<b>零重试</b>，唯一定义在
 * {@link com.rag.article.ArticleServiceApplication}，并由
 * {@code articleKafkaListenerContainerFactory} 装到两个 listener 的容器上。
 */
@Component
public class ArticleReviewConsumer {

    private static final Logger log = LoggerFactory.getLogger(ArticleReviewConsumer.class);

    /**
     * Go {@code article_consumer.go:209}：{@code regexp.MustCompile(`!\[.*?\]\((.*?)\)`)}。
     *
     * <p>⚠️ <b>逐字照搬</b>：这是非贪婪匹配 {@code ![alt](url)} 的 url 部分。
     * 换成「更严格」的 Markdown 解析器会改变哪些图片被送去审核 ——
     * 漏掉一张图就等于漏掉一次广告检测。
     */
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[.*?]\\((.*?)\\)");

    private final ObjectMapper objectMapper;

    private final ArticleMapper articleMapper;

    private final ArticleAtomicService atomicService;

    private final ArticleObjectStorageClient storage;

    private final ContentAuditPort contentAudit;

    private final ArticleRpcMetrics metrics;

    private final ObjectProvider<ObservationRegistry> observationRegistry;

    /**
     * 构造器 —— 依赖逐条照抄 B 侧。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:80-94}。
     *
     * @param objectMapper       Jackson 2 mapper（解析审核消息）
     * @param articleMapper      article 表的数据访问
     * @param atomicService      事务载体（行锁 + 幂等复查 + 写 outbox）
     * @param storage            对象存储客户端（读正文 / 读图）
     * @param contentAudit       内容审核端口（<b>本仓恒 fail-loud</b>，见 {@code UnavailableContentAuditClient}）
     * @param metrics            指标
     * @param observationRegistry 观测注册表（可能不存在 ⇒ 不产出观测，但错误分类不变）
     */
    public ArticleReviewConsumer(ObjectMapper objectMapper,
                                 ArticleMapper articleMapper,
                                 ArticleAtomicService atomicService,
                                 ArticleObjectStorageClient storage,
                                 ContentAuditPort contentAudit,
                                 ArticleRpcMetrics metrics,
                                 ObjectProvider<ObservationRegistry> observationRegistry) {
        this.objectMapper = objectMapper;
        this.articleMapper = articleMapper;
        this.atomicService = atomicService;
        this.storage = storage;
        this.contentAudit = contentAudit;
        this.metrics = metrics;
        this.observationRegistry = observationRegistry;
    }

    /**
     * 消费一条审核任务。
     *
     * <p>⚠️ <b>topic 用占位符取自配置</b>（{@code article-rpc.kq-consumer-conf.topic}），
     * groupId 取 {@code kq-consumer-conf.group}（Go yaml 是 {@code dataclean}），
     * 两者都是<b>独立配置字段</b>，不要合并成一个。
     *
     * <p>⚠️ 用 <b>{@code ${}} 占位符</b>而不是 SpEL 的 {@code #{@articleRpcProperties.…}}：
     * SpEL 里的 BeanFactory 引用由 {@code KafkaListenerAnnotationBeanPostProcessor} 在
     * <b>Bean 后处理阶段</b>求值，那时 {@code ConfigurationPropertiesScan} 注册的定义
     * 未必可见 ⇒ 实测会抛 {@code EL1058E / NoSuchBeanDefinitionException}。
     * {@code ${}} 走 {@code Environment}，不依赖注册顺序。两条路径读的是同一个 yml 键。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:103-132}。
     *
     * @param record Kafka 消息记录（取 key 做 span 属性）
     * @param value  消息体（JSON）
     */
    @KafkaListener(
            id = "article-review-consumer",
            topics = "${article-rpc.kq-consumer-conf.topic}",
            groupId = "${article-rpc.kq-consumer-conf.group}",
            containerFactory = "articleKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, String value) {
        // 🔴 L-10（Go 出处：article_consumer.go:44-45）：
        //     func (l *ArticleConsumer) Consume(ctx context.Context, key, val string) error {
        //         return observability.TraceConsumer(ctx, "article-rpc", "ArticleConsumer.Consume",
        //             articleMessageAttrs("article_review", key), func(ctx context.Context) error { … })
        //     }
        //    ⇒ 共享层 MqConsumer.traceConsumer 与 Go 的 TraceConsumer **逐项同构**
        //      （SpanKind=CONSUMER · attrs · 错误分档 · finally span.end()）⇒ 纯域侧接线，不动共享层。
        //    ⚠️ messaging.destination 是**逻辑名** "article_review"，**不是** kafka topic
        //      （真源 topic 是 article-creation-topic）⇒ 不可用 record.topic()。
        Map<String, Object> attrs = ArticleRpcMetrics.consumerAttrs(
                ArticleRpcMetrics.DESTINATION_REVIEW, record == null ? null : record.key());
        Throwable outcome = MqConsumer.traceConsumer(observationRegistry.getIfAvailable(),
                ArticleRpcMetrics.TRACER_NAME, ArticleRpcMetrics.CONSUMER_REVIEW, attrs, ctx -> consume(value));
        if (outcome != null) {
            // Go 的 kq：Consume 返回 error ⇒ 不确认位点 ⇒ 重投。
            // 这里必须**抛出**而不是吞掉，否则失败的消息会被当成已处理（位点被提交）。
            throw toRuntime(outcome);
        }
    }

    /**
     * 消费主体；返回非 null 即代表「失败，要重投」。
     *
     * <p>🔴 <b>为什么把异常「转成返回值」而不是直接抛</b>：
     * Go 的 {@code TraceConsumer} 收 {@code fn func(ctx) error}，
     * 在 {@code err != nil} 时 <b>既</b> {@code MarkSystemError}（或 {@code MarkTimeout}）
     * <b>又</b> {@code return err} ⇒ 错误<b>既进 span 又出方法</b>。
     * ⇒ 本实现：lambda 内把异常转成返回值（⇒ 共享层才能标 system error，对齐 Go），
     * lambda 外再<b>原样抛出</b>（⇒ 保持「失败 ⇒ 重投」的语义）。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:144-151}。
     *
     * @param value 消息体
     * @return 失败时的异常；成功返回 {@code null}
     */
    private Throwable consume(String value) {
        try {
            doConsume(value);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /**
     * 把任意 {@link Throwable} 转成 unchecked —— B 侧同款。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:153-155}。
     *
     * @param t 原始异常
     * @return 可抛出的 unchecked 异常
     */
    private RuntimeException toRuntime(Throwable t) {
        return (t instanceof RuntimeException re) ? re : new IllegalStateException(t);
    }

    /**
     * 消费主体（分支体）。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:157-223}。
     *
     * @param value 消息体
     */
    private void doConsume(String value) {
        // ---------- 分支 1：JSON 解析失败 ⇒ 丢弃（Go :49-52）----------
        ArticleSyncEvent.ArticleReviewMessage msg;
        try {
            msg = objectMapper.readValue(value, ArticleSyncEvent.ArticleReviewMessage.class);
        } catch (Exception e) {
            log.warn("drop article review message with invalid json: {}", e.getMessage());
            return;
        }

        ArticleEntity article = articleMapper.selectById(msg.articleId());
        // ---------- 分支 2：文章不存在 ⇒ 丢弃（Go :55-57）----------
        if (article == null) {
            log.info("drop article review for missing article: articleId={}", msg.articleId());
            return;
        }

        // ---------- 分支 3：四元组校验失败 ⇒ 丢弃（Go :62-68）----------
        if (!matchesReviewTuple(article, msg)) {
            log.info("article review skipped because source or status changed: articleId={} authorId={}",
                    msg.articleId(), msg.authorId());
            return;
        }

        // ---------- 分支 4：读正文失败 ⇒ 重投（Go :70-81）----------
        String markdown;
        long start = System.nanoTime();
        try {
            markdown = storage.readString(msg.contentPath());
        } catch (Exception e) {
            metrics.minioError("get");
            log.warn("read minio content failed, will retry: articleId={} err={}",
                    msg.articleId(), e.getMessage());
            throw new IllegalStateException("read minio content failed", e);
        }
        metrics.observeMinio("get", System.nanoTime() - start);

        Map<String, String> extInfo = ArticleSyncEvent.ensureExtInfo(article.getExtInfo());
        // ---------- 分支 5：幂等命中 ⇒ 丢弃（Go :85-87）----------
        if ("pending".equals(extInfo.get(ArticleSyncEvent.EXT_RECO_SYNC_STATE))
                && !isBlank(extInfo.get(ArticleSyncEvent.EXT_LAST_SYNC_EVENT_ID))) {
            log.info("article review redelivery already has a durable sync outbox: articleId={}", msg.articleId());
            return;
        }

        // ---------- 审核（失败 ⇒ 重投，Go :89-91）----------
        auditArticle(article, markdown, msg.authorId());

        // ---------- 被拒 ⇒ 结束（Go :92-95）----------
        if (article.getStatus() == com.rag.proto.article.ArticleStatus.REJECTED_VALUE) {
            log.info("article rejected by security check: articleId={} authorId={}",
                    msg.articleId(), msg.authorId());
            return;
        }

        // ---------- 生成事件 ID（失败 ⇒ 重投，Go :97-101）----------
        String eventId = String.valueOf(Snowflake.nextId());
        long versionMs = System.currentTimeMillis();

        // ---------- 事务：行锁 + 二次幂等 + 写 outbox（失败 ⇒ 重投，Go :104-136）----------
        boolean queued = atomicService.queueSyncOutboxWithLock(
                msg.articleId(), msg.authorId(), msg.contentPath(), msg.reviewNonce(),
                markdown, eventId, versionMs);
        if (queued) {
            log.info("article sync event queued: articleId={} authorId={}", msg.articleId(), msg.authorId());
        }
    }

    /**
     * 对应 Go {@code article_consumer.go:62-64} 的四元组判定。
     *
     * <p>⚠️ <b>四项全等才通过</b>：status / content / author / review_nonce。
     * 少判一项都会让「已过期的审核任务」通过 ——
     * 最典型的后果是 {@code review_nonce}：作者改了标题（对象路径不变）后，
     * 旧任务带着旧 nonce 进来，若不校验 nonce 就会用<b>旧 nonce</b>批准<b>新内容</b>。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:234-240}。
     *
     * @param article 当前文章行
     * @param msg     审核消息
     * @return 四元组是否全等
     */
    static boolean matchesReviewTuple(ArticleEntity article, ArticleSyncEvent.ArticleReviewMessage msg) {
        return article.getStatus() == com.rag.proto.article.ArticleStatus.REVIEWING_VALUE
                && contentEquals(article.getContent(), msg.contentPath())
                && contentEquals(article.getAuthorId(), msg.authorId())
                && contentEquals(extValue(article.getExtInfo(), ArticleSyncEvent.EXT_REVIEW_NONCE),
                        msg.reviewNonce());
    }

    /**
     * 空安全相等。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:242-244}。
     *
     * @param a 左值
     * @param b 右值
     * @return 是否相等
     */
    private static boolean contentEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * 从 ext_info 取值（null 安全）。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:246-248}。
     *
     * @param extInfo ext_info
     * @param key    键
     * @return 值（可能为 null）
     */
    private static String extValue(Map<String, String> extInfo, String key) {
        return extInfo == null ? null : extInfo.get(key);
    }

    // ==================================================================
    // 审核
    // ==================================================================

    /**
     * 对应 Go {@code auditArticle}（{@code article_consumer.go:153-206}）。
     *
     * <h2>⚠️ 三条不可改的语义</h2>
     * <ol>
     *   <li><b>文本不过 ⇒ 拒稿并立即返回</b>，<b>不</b>继续查图（Go :174-182）。</li>
     *   <li><b>拒稿时同步状态写成 {@code (rejected, failed)} 且带原因</b>：
     *       文本广告是 {@code "content_rejected"}，图片广告是 {@code "image_rejected"}（Go :176, :196）。
     *       两者<b>必须可区分</b>——运维据此判断该查文本还是查图。</li>
     *   <li><b>syncReason 取的是 {@code pending_sync_reason}</b>（不是 last_sync_reason，Go :176）。
     *       用错会让拒稿后丢失「本来要同步什么」的信息。</li>
     * </ol>
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:267-314}。
     *
     * @param article  当前文章行（会被就地改成 REJECTED）
     * @param markdown 正文（MinIO 里的 markdown 原文）
     * @param authorId 作者 ID
     */
    void auditArticle(ArticleEntity article, String markdown, String authorId) {
        ContentAuditPort.TextAuditResult text = contentAudit.sanitizeText(markdown);
        // 🔴 Go article_consumer.go:171-173：先判「调用成功了吗」，再判「是否广告」。
        //     if !result.Success { return fmt.Errorf("content security service error: %s", result.ErrorMessage) }
        // ≡ 调用本身没报错、但业务结果是失败 ⇒ 抛错**重投**，不是判为「不是广告」。
        // ⚠️ 把这条分支删掉等于把未审核内容当广告放行。
        if (!text.isSuccess()) {
            throw new IllegalStateException(
                    "content security service error: " + text.getErrorMessage());
        }
        if (text.isAd()) {
            rejectAsAd(article, "content_rejected");
            return;
        }

        List<String> imageUrls = extractImageUrls(markdown);
        // ⚠️ Go :185-187：cover_image_url 也要审，<b>追加在正文图片之后</b>
        if (article.getCoverImageUrl() != null && !article.getCoverImageUrl().isEmpty()) {
            imageUrls.add(article.getCoverImageUrl());
        }
        for (String imageUrl : imageUrls) {
            // ⚠️ 必须走完整的 auditImage 链路（Go :188-190 调 l.auditImage(ctx, imgURL)）：
            //    URL → 去掉首段得到对象名 → 从 MinIO 读字节 → 拼成 dataURI → 才送审。
            //    直接把 URL 传给 detectImageAd 会让下游收到无法解析的输入
            //    （下游期望的是 data:<mime>;base64,<...>），且等于跳过了「图必须真实存在」这道检查。
            boolean isAd;
            try {
                String dataUri = buildImageDataUri(imageUrl);
                ContentAuditPort.ImageAuditResult image = contentAudit.detectImageAd(dataUri);
                // 🔴 Go article_consumer.go:263-265：if !resp.GetSuccess() { return false, 0, fmt.Errorf(...) }
                // ≡ 调用成功但业务失败 ⇒ 抛错**整篇重投**，不是跳过这张图继续审下一张。
                if (!image.isSuccess()) {
                    throw new IllegalStateException("image moderation service error");
                }
                isAd = image.isAd();
            } catch (Exception e) {
                // Go :190-192: 审核图片失败 ⇒ return err（重投），不是判定为「没问题」
                log.warn("audit image failed, will retry: articleId={} image={} err={}",
                        article.getId(), imageUrl, e.getMessage());
                throw new IllegalStateException("audit image failed: " + imageUrl, e);
            }
            if (isAd) {
                rejectAsAd(article, "image_rejected");
                return;
            }
        }
    }

    /**
     * 拒稿 —— 对应 Go 的三处重复代码（{@code :174-182} 与 {@code :194-202}）。
     *
     * <p>⚠️ <b>拒稿时写的是 {@code versionMs = time.Now().UnixMilli()}（当前时间），
     * 不是事件的版本号</b>（Go :176/:196）。这两者在正常路径上数值接近，
     * 但语义不同：这里记的是「何时被拒」，而事件版本号记的是「哪一轮审核」。
     * ⚠️ 同时 {@code eventId} 传空串（Go 的第 5 个参数是 {@code ""}），
     * 于是 {@code last_sync_event_id} 被清空 —— 让后续重新提交时幂等判据重新可用。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:325-333}。
     *
     * @param article 文章行
     * @param reason  拒稿原因（{@code content_rejected} / {@code image_rejected}）
     */
    private void rejectAsAd(ArticleEntity article, String reason) {
        Map<String, String> extInfo = ArticleSyncEvent.ensureExtInfo(article.getExtInfo());
        article.setExtInfo(extInfo);
        article.setStatus(com.rag.proto.article.ArticleStatus.REJECTED_VALUE);
        ArticleSyncEvent.setSyncState(extInfo, "rejected", "failed",
                extInfo.get(ArticleSyncEvent.EXT_PENDING_SYNC_REASON), "",
                System.currentTimeMillis(), reason);
        articleMapper.updateById(article);
    }

    /**
     * 对应 Go {@code extractImageUrls}（{@code article_consumer.go:208-218}）。
     *
     * <p>⚠️ <b>正则逐字照搬</b>（{@code !\[.*?\]\((.*?)\)}）。
     * <br>⚠️ Go 返回的是 {@code make([]string, 0, len(matches))} —— <b>非 nil 的空切片</b>，
     * 所以后面 {@code imageURLs = append(imageURLs, coverURL)} 是在<b>可写</b>的切片上追加。
     * 本实现返回 {@code new ArrayList<>()}，语义相同。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:343-359}。
     *
     * @param content 正文
     * @return 图片 URL 列表（非 null）
     */
    static List<String> extractImageUrls(String content) {
        List<String> urls = new ArrayList<>();
        if (content == null) {
            return urls;
        }
        Matcher matcher = MARKDOWN_IMAGE.matcher(content);
        while (matcher.find()) {
            // 🔴 判据是 groupCount() >= 1，**不是 > 1**：本正则只有**一个**捕获组
            //    （就是图片 URL 那个）。写成 > 1 时条件恒假，urls 永远为空 ——
            //    结果是**所有正文图片都不会被送审**，广告图直接放行。
            //    这类缺陷不会让任何测试变红，只会让「图片审核」整条链路静默消失。
            if (matcher.groupCount() >= 1) {
                urls.add(matcher.group(1));
            }
        }
        return urls;
    }

    /**
     * 对应 Go {@code auditImage}（{@code article_consumer.go:220-268}）。
     *
     * <h2>⚠️ 四条不可改的语义</h2>
     * <ol>
     *   <li><b>URL → 对象名的解析</b>：{@code url.Parse} → 取 {@code u.Path} 去首斜杠 →
     *       按第一个 {@code /} 切成两段，<b>取第二段</b>作对象名（{@code :224-230}）。
     *       即 {@code /articles/images/123.png} ⇒ {@code images/123.png}。
     *       <b>段数不足 2 ⇒ 返回错误</b>（该图判为审核失败 ⇒ 整篇重投）。</li>
     *   <li><b>mimeType 只认三个后缀</b>：{@code .png → image/png}、{@code .webp → image/webp}、
     *       <b>其余一律 {@code image/jpeg}</b>（{@code :244-250}）。
     *       ⚠️ 默认值是 <b>jpeg 而不是 octet-stream</b>，这会影响下游解码器的选择。</li>
     *   <li><b>dataURI 形式</b>：{@code data:<mime>;base64,<b64>}（标准 base64，<b>带 padding</b>）。</li>
     *   <li><b>封面图也走这条路径</b>：{@code auditArticle} 把它塞进同一个 imageURLs 列表，
     *       所以它同样要能被解析成对象名。</li>
     * </ol>
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:378-399}。
     *
     * @param imageUrl 图片 URL
     * @return data URI
     * @throws Exception 解析或读取失败
     */
    String buildImageDataUri(String imageUrl) throws Exception {
        URI uri = URI.create(imageUrl);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/", 2);
        if (parts.length < 2) {
            throw new IllegalArgumentException("invalid image path: " + path);
        }
        String objectName = parts[1];

        byte[] data;
        long start = System.nanoTime();
        try (InputStream in = storage.get(objectName)) {
            data = storage.readAll(in);
        }
        metrics.observeMinio("get", System.nanoTime() - start);

        String b64 = Base64.getEncoder().encodeToString(data);
        return "data:" + mimeTypeOf(objectName) + ";base64," + b64;
    }

    /**
     * 对应 Go {@code article_consumer.go:244-250} 的三段后缀判断。
     *
     * <p>⚠️ 默认返回 {@code image/jpeg}（Go 的初值），<b>不是</b> octet-stream。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:406-415}。
     *
     * @param objectName 对象名
     * @return mime 类型
     */
    static String mimeTypeOf(String objectName) {
        String lower = objectName == null ? "" : objectName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    /**
     * 空串判断。
     *
     * <p><b>B 出处</b>：{@code .../mq/ArticleReviewConsumer.java:417-419}。
     *
     * @param value 待判值
     * @return 是否为空
     */
    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
