package com.rag.like.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.logger.LoggerFactoryBean;
import com.rag.like.contract.ErrCodes;
import com.rag.like.contract.TaskArticleProgressMsg;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 推广任务进度推送 —— 1:1 对应 Go 端
 * {@code service/like/rpc/internal/logic/likeactionlogic.go:311-343} 的 {@code syncTaskProgress}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeTaskProgressPublisher.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.like.like.rpc.service} 移到 {@code com.rag.like.messaging}（D7）；</li>
 *       <li>{@code com.rag.common.spring.LoggerFactoryBean} → {@code com.rag.common.logger.LoggerFactoryBean}
 *           （D3：共享层 1:N 拆分）；</li>
 *       <li>{@code LikeEventContracts.TaskArticleProgressMsg} → {@link TaskArticleProgressMsg}（1:N 拆分）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。两路的判空粒度、错误分流位置、吞异常语义逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 两个 pusher 在 Go 里是<b>可为 nil 的可选依赖</b></h2>
 *
 * <p>Go {@code servicecontext.go:50-56}：
 * <pre>
 * func newOptionalPusher(conf kq.KqConf) *kq.Pusher {
 *     if len(conf.Brokers) == 0 || conf.Topic == "" { return nil }
 *     return kq.NewPusher(conf.Brokers, conf.Topic)
 * }
 * </pre>
 * 随后 {@code syncTaskProgress} 里是 {@code if l.svcCtx.TaskUserPusher != nil { ... }} 这种
 * <b>运行期判空</b>。所以「没配 broker/topic ⇒ 这一整段推送被跳过」是 Go 的既定行为。
 *
 * <p>⇒ Java 侧<b>不能</b>用 {@code @ConditionalOnProperty} 把这个 Bean 做成「不存在」：
 * 那会让构造注入失败、整个上下文起不来。正确做法是保留 Bean、把
 * {@link LikeKafkaTopics#isTaskUserPusherEnabled()} 透传下来，让本类走与 Go 相同的「跳过」分支。
 *
 * <h2>失败语义</h2>
 *
 * <p>Go 侧两次 push 失败都只 {@code LogBusinessErr(... ErrorKafkaPush ...)}，不影响点赞 RPC 返回值。
 * 本实现同样<b>吞掉异常</b>。
 *
 * <h2>🆕 路 1 的「双失败」分支（2026-10-29 G2 修 · global E60）</h2>
 *
 * <p>Go {@code likeactionlogic.go:311-320} 的路 1 是三段式，<b>不是</b>「拿到值就推」：
 * <pre>
 * totalLikesGiven, err := l.currentUserLikeTotal(in.UserId, in.TargetType, userListKey)
 * if err != nil {
 *     logger.LogBusinessErr(l.ctx, errmsg.ErrorRedisSelect,
 *         fmt.Errorf("sync task user progress failed: %w", err))   // ← 记业务错误 5011，<b>不推</b>
 * } else if totalLikesGiven > 0 {
 *     l.svcCtx.TaskUserPusher.PushWithKey(...)                      // ← 只有无错且 &gt;0 才推
 * }
 * </pre>
 * 而 {@code currentUserLikeTotal}（{@code :345-364}）在 <b>Redis 与 DB 双失败</b>时
 * <b>返回 {@code (0, dbErr)}</b> —— <b>error 必须传出去</b>，由本方法决定「记业务错误 + 不推」。
 *
 * <p>⚠️ <b>改前 RAG 把 error 吞了</b>：{@code currentUserLikeTotal} 双失败时只 {@code return 0L}
 * ⇒ 双失败<b>既没有 5011 业务错误记录</b>，也只能靠 {@code userLikesGiven > 0} 这个巧合守卫
 * 才没把 {@code 0} 推出去 —— 守卫一旦被改动就会变成「把 0 当进度推给下游」。
 * ⇒ 修法 = 把 Go 的<b>显式 error 分支</b>搬回来。
 *
 * <p>⚠️ <b>为什么错误分流必须在本类内部、不能放在调用方</b>：Go 的 {@code err != nil} 分支
 * <b>只跳过路 1 的推送</b>，<b>路 2（article 路）照走</b>（Go 的 {@code syncTaskProgress} 在路 1 之后
 * <b>没有 return</b>）⇒ 若把分流提到调用方并写成「有错就不调 publish」，
 * <b>路 2 会被一起跳过</b> ⇒ 制造一条 Go 没有的新漂移。
 *
 * <h2>推送内容</h2>
 * <ul>
 *   <li>user 路：{@code PushWithKey(userId, totalLikesGiven)} —— <b>裸字符串</b>，key=userId、value=计数，
 *       <b>不是 JSON</b>；</li>
 *   <li>article 路：{@code PushWithKey(targetId, json(TaskArticleProgressMsg))}。</li>
 * </ul>
 */
@Component
public class LikeTaskProgressPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(LikeTaskProgressPublisher.class);

    /**
     * 对应 Go {@code currentUserLikeTotal} 的 {@code (int64, error)} <b>二元返回</b>。
     *
     * <p>Go 用「返回值 + error」表达「这个数能不能信」；Java 没有多返回值 ⇒ 用一个载体把
     * <b>值</b>与<b>失败原因</b>一起传下去，让消费方（{@link #publish}）能照 Go 的
     * {@code if err != nil / else if total > 0} 结构分流。
     *
     * <p>⚠️ <b>不要用「抛异常」代替</b>：Go 侧 {@code currentUserLikeTotal} <b>不抛</b>，
     * error 是一路返回到 {@code syncTaskProgress} 的普通返回值 ⇒ 抛异常会让「双失败」
     * 变成「整个点赞 RPC 失败」，比 Go 更严 ⇒ <b>制造新漂移</b>。
     *
     * @param total 计数（{@link #failed(Throwable)} 时恒为 0，与 Go 的 {@code return 0, dbErr} 一致）
     * @param error 失败原因；{@code null} = 无失败（Go 的 {@code err == nil}）
     */
    public record UserLikeTotal(long total, Throwable error) {

        /** Go：{@code return int64(total), nil} / {@code return dbTotal, nil}。 */
        public static UserLikeTotal ok(long total) {
            return new UserLikeTotal(total, null);
        }

        /** Go：{@code return 0, dbErr}（Redis 与 DB 双失败）。 */
        public static UserLikeTotal failed(Throwable error) {
            return new UserLikeTotal(0L, error);
        }
    }

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final LikeKafkaTopics topics;
    private final ObjectMapper objectMapper;
    private final LoggerFactoryBean logger;

    public LikeTaskProgressPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                     LikeKafkaTopics topics,
                                     ObjectMapper objectMapper,
                                     LoggerFactoryBean logger) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.objectMapper = objectMapper;
        this.logger = logger;
    }

    /**
     * 对应 Go {@code syncTaskProgress(in, userListKey, articleLikes)}。
     *
     * @param userId        点赞者（user 路的 key）
     * @param authorId      作者；Go 侧 {@code in.AuthorId <= 0} 时<b>直接 return</b>，不推 article 路
     * @param targetId      目标（article 路的 key）
     * @param userTotal     Go {@code currentUserLikeTotal} 的 {@code (int64, error)} 二元返回
     *                      （= max(ZCARD, DB count) + 失败原因）
     * @param articleLikes  该目标的赞总数（= max(Redis Hash, DB count)）
     */
    public void publish(long userId, long authorId, String targetId,
                        UserLikeTotal userTotal, long articleLikes) {

        // ---------- 路 1：用户进度 ----------
        // Go: if l.svcCtx.TaskUserPusher != nil { … } else 整段跳过
        // ⚠️ 2026-10-23 修正：Go 的两路是**各自独立判空**
        // （servicecontext.go:43-44 分别 newOptionalPusher(TaskUserPusherConf / TaskArticlePusherConf)），
        // 此前这里两路共用一个 isTaskPushersEnabled()，丢失了粒度（DRIFT-LIKE-012）。
        if (topics.isTaskUserPusherEnabled()) {
            final Throwable userTotalError = userTotal.error();
            if (userTotalError != null) {
                // Go: logger.LogBusinessErr(l.ctx, errmsg.ErrorRedisSelect,
                //          fmt.Errorf("sync task user progress failed: %w", err))
                // ⇒ 记业务错误 5011（Go service/like/common/errmsg/errmsg.go:51 ErrorRedisSelect = 5011），
                //   **不推送**（Go 的 if/else-if 结构：err 分支下 totalLikesGiven 不参与推送）。
                // ⚠️ 用 IllegalStateException 包装是为了复刻 Go 的 %w 包裹语义
                //   ⇒ error_reason 字段形态与 Go 一致（"sync task user progress failed: …"）。
                logger.logBusinessErr(Context.current(),
                        ErrCodes.ERROR_REDIS_SELECT,
                        new IllegalStateException("sync task user progress failed: "
                                + userTotalError.getMessage(), userTotalError));
            } else if (userTotal.total() > 0) {
                // Go: totalLikesGiven > 0 才推（0 不推，避免给下游发无意义的零进度）
                try {
                    kafkaTemplate.send(topics.getTaskUserTopic(),
                            String.valueOf(userId),
                            String.valueOf(userTotal.total()));
                } catch (Exception e) {
                    // Go: LogBusinessErr(..., ErrorKafkaPush, "push task user progress failed")
                    LOG.warn("push task user progress failed: userId={} err={}", userId, e.getMessage());
                }
            }
        }

        // ---------- 路 2：文章进度 ----------
        // Go: if l.svcCtx.TaskArticlePusher == nil || in.AuthorId <= 0 || articleLikes <= 0 { return }
        // ⚠️ 路 1 的错误**不短路**路 2（Go 的 syncTaskProgress 在路 1 之后没有 return）。
        if (!topics.isTaskArticlePusherEnabled() || authorId <= 0 || articleLikes <= 0) {
            return;
        }

        final String payload;
        try {
            payload = objectMapper.writeValueAsString(
                    new TaskArticleProgressMsg(String.valueOf(authorId), articleLikes));
        } catch (Exception e) {
            // Go: LogBusinessErr(..., ErrorJsonMarshal, "marshal task article progress failed"); return
            LOG.warn("marshal task article progress failed: authorId={} err={}", authorId, e.getMessage());
            return;
        }

        try {
            kafkaTemplate.send(topics.getTaskArticleTopic(), targetId, payload);
        } catch (Exception e) {
            // Go: LogBusinessErr(..., ErrorKafkaPush, "push task article progress failed")
            LOG.warn("push task article progress failed: targetId={} err={}", targetId, e.getMessage());
        }
    }
}
