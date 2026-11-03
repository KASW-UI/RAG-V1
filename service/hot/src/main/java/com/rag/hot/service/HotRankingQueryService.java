package com.rag.hot.service;

import com.rag.hot.domain.model.HeavyKeeper;
import com.rag.hot.messaging.HotRedisSnapshotStore;
import com.rag.proto.hot.HotArticleItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <p><b>{@code GetHotArticles} 的唯一业务实现</b> —— 对应 B 侧
 * {@code service/hot/hot-rpc/.../rpc/service/HotRankingQueryService.java}。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/service/HotRankingQueryService.java}
 * <pre>{@code
 *   类声明 + 依赖 StringRedisTemplate                       :38-52
 *   DEFAULT_TOP_K = 20 / MAX_TOP_K = 100                    :43 / :46
 *   getHotArticles(int topK)                                 :59-92
 *     k <= 0 → 20 ; k > 100 → 100                           :60-66
 *     ZREVRANGE hot:articles 0 k-1 WITHSCORES                :68-69
 *     key 不存在 → 返回空 items、不报错                       :70-72
 *     member 非字符串 → 跳过                                  :77-81
 *     HotScore = uint32(z.Score) 向零截断                    :86-89
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯表达层</b>改动 ——
 *       ① 包名 {@code com.rag.hot.hot.rpc.service} → {@code com.rag.hot.service}（D7 包压平）；
 *       ② proto 包名 {@code com.rag.hot.hot.proto} → {@code com.rag.proto.hot}（D7 proto 集中）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 逐条对照：
 *       <ul>
 *         <li><b>TopK 归一</b>：proto 字段是 {@code int32}，负数是合法输入 ⇒ 走 {@code <= 0} 分支；</li>
 *         <li><b>读取</b>：{@code ZREVRANGE hot:articles 0 k-1 WITHSCORES}；</li>
 *         <li><b>key 不存在 → 返回空 items、不报错</b>：Java 侧 Spring Data Redis 对不存在的 key
 *             返回<b>空集合且不抛异常</b>，语义天然一致；</li>
 *         <li><b>其它 Redis 错误 → 原样向上抛</b>：⚠️ 这里<b>不能</b>吞掉
 *             —— B 侧返回的是<b>裸 error</b>，经 gRPC {@code status.FromError} 后落到
 *             {@code codes.Unknown} ⇒ 由 {@code HotGrpcService} 映射为 {@code Status.UNKNOWN}，
 *             <b>不是</b>业务码、<b>不是</b> INTERNAL；</li>
 *         <li><b>member 非字符串 → 跳过</b>；</li>
 *         <li><b>{@code HotScore = uint32(z.Score)} 向零截断</b> —— <b>不能</b> {@code Math.round}。
 *             Redis score 是 float64，截断后与原排序可能出现并列。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方逐条行号。</li>
 * </ol>
 *
 * <p><b>⚠️ 本类同时服务两条调用路径（D1 + D2 的合并后果）</b>：
 * <ul>
 *   <li><b>gRPC 面</b>：{@code HotGrpcService} → 本类（跨域调用方）；</li>
 *   <li><b>HTTP 面</b>：{@code HotApiService} → 本类（<b>D2 域内直连</b>，B 侧此处是 gRPC 往返）。</li>
 * </ul>
 * ⇒ ⇒ 两条路径<b>共用同一份实现</b> ⇒ 读到的永远是同一份 Redis 数据 ⇒ 行为一致。
 */
@Service
public class HotRankingQueryService {

    private static final Logger LOG = LoggerFactory.getLogger(HotRankingQueryService.class);

    /** B 侧 {@code defaultTopK = 20}。 */
    public static final int DEFAULT_TOP_K = 20;

    /** B 侧 {@code maxTopK = 100}。 */
    public static final int MAX_TOP_K = 100;

    private final StringRedisTemplate redis;

    public HotRankingQueryService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 取热度 TopK。
     *
     * @param topK 请求里的 {@code top_k}（proto int32，可为负）
     * @return 按热度降序的条目；key 不存在时返回空列表
     * @throws org.springframework.dao.DataAccessException Redis 访问失败（非 key 不存在）时向上抛
     */
    public List<HotArticleItem> getHotArticles(int topK) {
        long k = topK;
        if (k <= 0) {
            k = DEFAULT_TOP_K;
        }
        if (k > MAX_TOP_K) {
            k = MAX_TOP_K;
        }

        Set<ZSetOperations.TypedTuple<String>> results =
                redis.opsForZSet().reverseRangeWithScores(HotRedisSnapshotStore.HOT_ARTICLES_KEY, 0, k - 1);
        if (results == null || results.isEmpty()) {
            return new ArrayList<>();
        }

        List<HotArticleItem> items = new ArrayList<>(results.size());
        for (ZSetOperations.TypedTuple<String> z : results) {
            Object member = z.getValue();
            if (!(member instanceof String articleId)) {
                // B 侧 :55-59：非字符串 member → Slowf 后跳过
                LOG.warn("redis member is not string: {}", member);
                continue;
            }
            Double score = z.getScore();
            if (score == null) {
                continue;
            }
            items.add(HotArticleItem.newBuilder()
                    .setArticleId(articleId)
                    .setHotScore(HeavyKeeper.scoreToCount(score))
                    .build());
        }
        return items;
    }
}
