package com.rag.like.service;

import java.time.Instant;

/**
 * 推送 key 的生成 —— 对齐 Go {@code go-queue@v1.2.2/kq/pusher.go:123-125}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeKafkaPushKey.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：无。包名由 {@code com.rag.like.like.rpc.service} 压平为
 *       {@code com.rag.like.service}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样。产出的是「自 Unix epoch 起的纳秒数的十进制字符串」，
 *       与 Go 的 {@code strconv.FormatInt(time.Now().UnixNano(), 10)} 逐字同形。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>为什么需要这一类</h2>
 *
 * <p>Go 侧「无 key 推送」的 API 其实<b>内部就是带 key 的</b>：
 * <pre>
 * // Push sends a message to the Kafka topic.            ← 注释说「不带 key」，实际带
 * func (p *Pusher) Push(ctx context.Context, v string) error {
 *     return p.PushWithKey(ctx, strconv.FormatInt(time.Now().UnixNano(), 10), v)
 * }
 * </pre>
 * ⇒ <b>凡走 {@code Push} 的路径，key 一律是「当前 Unix 纳秒」的十进制字符串</b>。
 * 本模块有两条路径走 {@code Push}：{@code KafkaPusher}（like_promotion_topic）与
 * {@code HotEventPusher}（article-hot-events）。
 *
 * <h2>⚠️ 不要用 {@code System.nanoTime()} 代替</h2>
 *
 * <p>{@code System.nanoTime()} 返回的是<b>任意单调计时器</b>的值，与墙钟（Unix epoch）<b>没有</b>任何
 * 关系；Go 的 {@code time.Now().UnixNano()} 是<b>自 1970-01-01 起的墙上纳秒</b>。
 * 两者数值域完全不同 ⇒ 灰度期逐条 diff 消息元数据会全部对不上。
 *
 * <h2>⚠️ 本 key 不影响分区选择</h2>
 *
 * <p>Go 的 writer 配的是 {@code Balancer: &kafka.LeastBytes{}}（{@code pusher.go:47}），
 * <b>按各分区累计字节数选分区，不看 key</b>。因此 key 的作用只是「让消费端能读到非空 key」
 * 以及保证<b>同一批消息彼此不同</b>，不承载业务路由语义。
 *
 * <p>⚠️ Go 侧那条「无 key」路径在本模块的其余两处是<b>真带 key</b>的，不要顺手改成统一策略：
 * <ul>
 *   <li>{@code likeactionlogic.go:317} {@code TaskUserPusher.PushWithKey(ctx, strconv.FormatInt(in.UserId, 10), …)} ⇒ key = <b>UserId</b></li>
 *   <li>{@code likeactionlogic.go:340} {@code TaskArticlePusher.PushWithKey(ctx, in.TargetId, …)} ⇒ key = <b>TargetId</b></li>
 * </ul>
 */
public final class LikeKafkaPushKey {

    private LikeKafkaPushKey() {
        // 工具类，禁止 new
    }

    /**
     * 等价于 Go {@code strconv.FormatInt(time.Now().UnixNano(), 10)}。
     *
     * @return 自 Unix epoch 起的纳秒数的十进制字符串
     */
    public static String unixNano() {
        Instant now = Instant.now();
        return String.valueOf(now.getEpochSecond() * 1_000_000_000L + now.getNano());
    }
}
