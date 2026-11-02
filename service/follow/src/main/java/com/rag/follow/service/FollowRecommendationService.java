package com.rag.follow.service;

import com.rag.common.logger.LogOption;
import com.rag.common.logger.LoggerFactoryBean;
import com.rag.follow.contract.ErrCodes;
import com.rag.follow.mapper.BlockRelationMapper;
import com.rag.follow.mapper.FollowRelationMapper;
import com.rag.follow.mapper.GoStyleLimitOffset;
import com.rag.follow.metrics.FollowRpcMetrics;
import com.rag.proto.follow.RecommendResp;
import io.grpc.Status;
import io.opentelemetry.context.Context;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p><b>二度人脉推荐算法</b> —— 逐条照抄 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/service/FollowRecommendationService.java}
 * （B 侧 269 行）。
 *
 * <h2>算法一句话</h2>
 * <p>从「我直关的人」出发做 BFS，最多展开到 {@code depth} 层；
 * 沿途遇到<b>没被我关注、没被我拉黑、没拉黑我</b>的人就累加权重
 * （越近权重越大），最后按「分数降序 + id 升序」排序后取分页窗口。
 *
 * <h2>⚠️ 六条不能改的语义（逐条对应 B 侧行号）</h2>
 * <ol>
 *   <li><b>直关为空 ⇒ 提前返回空</b>（B 侧 102-107）：连 blockedSet / blockerSet 都不查，
 *       省掉两次 DB 查询。<b>返回的是 {@code successRecommendResp(null)}</b>，
 *       且<b>上报 {@code recommendation_size = 0}</b>。提前 return 的位置很关键：
 *       它让「没有任何关注」成为最便宜的失败路径。</li>
 *   <li><b>过滤是三重的，且顺序固定</b>（B 侧 138-147）：
 *       {@code == userId} → {@code blockedSet} → {@code blockerSet} → {@code directSet}。
 *       前两条是<b>跳过</b>，第四条（已关注）也是跳过 ⇒ <b>推荐结果里永远不含我已关注的人</b>。</li>
 *   <li><b>{@code expanded} 集合防环</b>（B 侧 124-128、152-154）：
 *       它初始化为 <b>{@code {userId} ∪ 第一层}</b>（不是空集！），
 *       所以「我」和「我直关的人」都不会再被展开一层。
 *       <b>漏掉 {@code expanded.add(userId)} 这一行</b>会让算法绕回自己，
 *       把我自己的 id 放进推荐列表。</li>
 *   <li><b>只有 {@code currentDepth < depth} 才加入 nextLayer</b>（B 侧 152）：
 *       最后一层只计分、不再展开。少这个判断 ⇒ 多一轮无用查询。</li>
 *   <li><b>下一层要先排序再展开</b>（B 侧 158-160，用 {@link FollowRpcSupport#sortedKeys}）：
 *       B 侧遍历集合顺序随机，用排序钉成升序。
 *       ⇒ 本类必须用 {@link FollowRpcSupport#sortedKeys}，否则<b>同一份数据在不同 JVM 上
 *       可能给出不同推荐</b>（分数累加顺序不定）。</li>
 *   <li><b>offset 越界 ⇒ 返回空而不是报错</b>（B 侧 179-183）：同样上报 size=0。</li>
 * </ol>
 *
 * <h2>⚠️ 排序的第二关键字不可省</h2>
 * <p>B 侧的排序（B 侧 174-176）是<b>不稳定排序</b>，
 * 所以它显式加了 {@code if Score == Score2 { return TargetId &lt; TargetId2 }}。
 * 没有这个次关键字，分数相同的候选之间的相对顺序就是<b>不确定</b>的。
 * C 侧 {@link List#sort} 同样是 TimSort（不稳定），
 * 但本类传的是 {@link Comparator} 且显式带了 id 次关键字 ⇒ 与 B 侧逐条一致。
 *
 * <h2>⚠️ L4 表达层差异</h2>
 * <ul>
 *   <li><b>包名</b>：B 侧 {@code ...follow.rpc.service} ⇒ C 侧 {@code com.rag.follow.service}（D7 压平）。</li>
 *   <li><b>mapper / metrics 的包</b>：B 侧 {@code ...follow.rpc.mapper} / {@code ...follow.rpc.metrics}
 *       ⇒ C 侧 {@code com.rag.follow.mapper} / {@code com.rag.follow.metrics}（域内压平）。</li>
 *   <li><b>错误码落点</b>：B 侧 {@code FollowErrorCode.ERROR_DB_SELECT.getCode()}
 *       ⇒ C 侧 {@link ErrCodes#ERROR_DB_SELECT}（码值一致）。</li>
 * </ul>
 */
@Service
public class FollowRecommendationService {

    /** 候选的中间表示 —— 对应 B 侧 {@code FollowRecommendationService.java:69-70} 的 record。 */
    private record Candidate(long targetId, int score) {
    }

    private final FollowRelationMapper followRelationMapper;
    private final BlockRelationMapper blockRelationMapper;
    private final FollowRpcSupport support;
    private final FollowRpcMetrics metrics;
    private final LoggerFactoryBean logger;

    public FollowRecommendationService(FollowRelationMapper followRelationMapper,
                                       BlockRelationMapper blockRelationMapper,
                                       FollowRpcSupport support,
                                       FollowRpcMetrics metrics,
                                       LoggerFactoryBean logger) {
        this.followRelationMapper = followRelationMapper;
        this.blockRelationMapper = blockRelationMapper;
        this.support = support;
        this.metrics = metrics;
        this.logger = logger;
    }

    /**
     * 对应 B 侧 {@code FollowRecommendationService.java:100-197}（归一化与存在性校验两步在调用方）。
     *
     * @param userId 已通过存在性校验的调用方 id
     * @param offset 已归一化（{@code >= 0}）
     * @param limit  已归一化并截断（{@code > 0}）
     * @param fanout 已 clamp（每层每源的扇出上限）
     * @param depth  已 clamp（最大展开层数）
     * @return 推荐列表；<b>推不出推荐时返回 {@code null}</b>（对应 B 侧的字面量 nil）
     */
    public List<RecommendResp.RecommendUser> recommend(long userId, int offset, int limit, int fanout, int depth) {
        // ---- 第 1 层：直关（B 侧 102-107）----
        List<Long> directFollows = listAllFollowTargets(userId, fanout, "get_recommendations", "list_direct_follows");
        if (directFollows.isEmpty()) {
            // B 侧在这里 return successRecommendResp(nil) —— 连 blocked/blocker 都不查。
            metrics.observeRecommendationSize(0);
            return null;
        }

        // ---- 双向屏蔽集合（B 侧 110-112）----
        Set<Long> blockedSet = support.toSet(readAllBlockTargets(userId));
        Set<Long> blockerSet = support.toSet(readBlockerUsers(userId));
        Set<Long> directSet = support.toSet(directFollows);

        // ---- 过滤出「可以拿去做下一跳起点」的第一层（B 侧 115-122）----
        Set<Long> seenLayer = new HashSet<>();
        List<Long> currentLayer = new ArrayList<>();
        for (long followId : directFollows) {
            if (blockedSet.contains(followId) || blockerSet.contains(followId) || !seenLayer.add(followId)) {
                continue;
            }
            currentLayer.add(followId);
        }

        // ⚠️ expanded 初值 = {userId} ∪ currentLayer，**不是空集**（B 侧 124-128）。
        //    漏掉 expanded.add(userId) ⇒ 算法会绕回自己，把自己推荐给自己。
        Set<Long> expanded = new HashSet<>();
        expanded.add(userId);
        expanded.addAll(currentLayer);

        // ---- BFS 逐层展开（B 侧 131-161）----
        Map<Long, Integer> scores = new HashMap<>();
        for (int currentDepth = 2; currentDepth <= depth && !currentLayer.isEmpty(); currentDepth++) {
            Set<Long> nextLayerSet = new HashSet<>();
            for (long sourceUserId : currentLayer) {
                List<Long> targets = listAllFollowTargets(sourceUserId, fanout,
                        "get_recommendations", "list_layer_follow_targets");

                for (long candidateId : targets) {
                    if (candidateId == userId) {
                        continue;
                    }
                    if (blockedSet.contains(candidateId) || blockerSet.contains(candidateId)) {
                        continue;
                    }
                    if (directSet.contains(candidateId)) {
                        continue;
                    }

                    scores.merge(candidateId, support.scoreWeight(depth, currentDepth), Integer::sum);

                    // 最后一层只计分，不再展开（B 侧 152）
                    if (currentDepth < depth && !expanded.contains(candidateId)) {
                        nextLayerSet.add(candidateId);
                    }
                }
            }

            // ⚠️ 必须升序：B 侧用排序钉死顺序，保证遍历确定性（见类注释第 5 条）
            currentLayer = support.sortedKeys(nextLayerSet);
            expanded.addAll(currentLayer);
        }

        // ---- 候选筛选 + 排序（B 侧 164-176）----
        List<Candidate> candidates = new ArrayList<>(scores.size());
        for (Map.Entry<Long, Integer> entry : scores.entrySet()) {
            // ⚠️ B 侧的 `if score <= 0 { continue }` 是纯防御（权重恒 >= 10），
            //    但 Integer 溢出理论上能让分数变负 —— 照原样保留，不删。
            if (entry.getValue() <= 0) {
                continue;
            }
            candidates.add(new Candidate(entry.getKey(), entry.getValue()));
        }

        candidates.sort(Comparator
                .comparingInt(Candidate::score).reversed()
                .thenComparingLong(Candidate::targetId));

        // ---- 分页窗口（B 侧 179-196）----
        int start = offset;
        if (start >= candidates.size()) {
            metrics.observeRecommendationSize(0);
            return null;
        }

        int end = Math.min(start + limit, candidates.size());

        List<RecommendResp.RecommendUser> users = new ArrayList<>(end - start);
        for (Candidate candidate : candidates.subList(start, end)) {
            users.add(RecommendResp.RecommendUser.newBuilder()
                    .setTargetId(candidate.targetId())
                    .setMutualScore(candidate.score())
                    .build());
        }

        metrics.observeRecommendationSize(users.size());
        return users;
    }

    // =============================================================================
    // DB 读取 + 错误映射（每处的 type 标签逐条对应 B 侧）
    // =============================================================================

    /**
     * 对应 B 侧 {@code FollowRecommendationService.java:210-220}（取某个源用户的全部关注目标）。
     *
     * <p><b>⚠️ 用 {@link GoStyleLimitOffset} 暴露分片子句的生成</b>：
     * LIMIT/OFFSET 是条件拼接的，MyBatis 的 {@code #{}} 绑定
     * 生成不了 {@code LIMIT} 语法，必须走 {@code ${}} 插值。
     *
     * <p><b>⚠️ 捕获 {@link RuntimeException} 而非 {@code DataAccessException}</b>：
     * B 侧写的是「任何错误都算错」；收窄会漏掉 MyBatis 的裸
     * {@code PersistenceException} 与超时翻译器翻出的 DEADLINE_EXCEEDED
     * ⇒ 两者都会把 5007 降级成 5001。
     */
    private List<Long> listAllFollowTargets(long requesterId, int fanout, String action, String type) {
        try {
            return followRelationMapper.listAllFollowTargets(
                    requesterId, GoStyleLimitOffset.limitOffset(fanout, 0));
        } catch (RuntimeException e) {
            metrics.observeDbError(action, type);
            logDbSelectErr(e, requesterId);
            throw FollowRpcSupport.rpcError(Status.Code.INTERNAL, ErrCodes.ERROR_DB_SELECT);
        }
    }

    /**
     * 对应 B 侧 {@code FollowRecommendationService.java:242-245} 的四处 DB 失败日志的同一形状。
     *
     * <p><b>⚠️ 记的是 {@code requesterId}（发起推荐的那个人），不是当前层的源用户</b>：
     * B 侧的 {@code userID} 变量在整个用例里始终是「请求方」，
     * BFS 内层的 {@code sourceUserID} 只用于查询、不进日志字段。照搬。
     *
     * <p><b>⚠️ 日志带原始 DB 异常，发给客户端的只有 {@code "database query failed"}</b> ——
     * 内部细节绝不外泄。这条分界不能模糊。
     */
    private void logDbSelectErr(RuntimeException e, long requesterId) {
        LogOption[] opts = {FollowRpcSupport.userLogOptionOf(requesterId)};
        logger.logBusinessErr(Context.current(), ErrCodes.ERROR_DB_SELECT, e, opts);
    }

    /** 对应 B 侧 {@code FollowRecommendationService.java:248-257}（我拉黑了谁的全集，无 LIMIT）。 */
    private List<Long> readAllBlockTargets(long userId) {
        try {
            return blockRelationMapper.listAllBlockTargets(userId);
        } catch (RuntimeException e) {
            metrics.observeDbError("get_recommendations", "list_block_targets");
            logDbSelectErr(e, userId);
            throw FollowRpcSupport.rpcError(Status.Code.INTERNAL, ErrCodes.ERROR_DB_SELECT);
        }
    }

    /** 对应 B 侧 {@code FollowRecommendationService.java:260-269}（谁拉黑了我，无 LIMIT）。 */
    private List<Long> readBlockerUsers(long userId) {
        try {
            return blockRelationMapper.listBlockerUsers(userId);
        } catch (RuntimeException e) {
            metrics.observeDbError("get_recommendations", "list_blocker_users");
            logDbSelectErr(e, userId);
            throw FollowRpcSupport.rpcError(Status.Code.INTERNAL, ErrCodes.ERROR_DB_SELECT);
        }
    }
}
