package com.rag.like.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.snowflake.Snowflake;
import com.rag.like.client.MessageRpcClient;
import com.rag.like.config.LikeRpcProperties;
import com.rag.like.contract.KafkaLikeMsg;
import com.rag.like.exception.LikeExceptions;
import com.rag.like.mapper.LikeRecordMapper;
import com.rag.like.messaging.LikeKafkaTopics;
import com.rag.like.messaging.LikeTaskProgressPublisher;
import com.rag.like.metrics.LikeRpcMetrics;
import com.rag.like.service.LikeActionScript;
import com.rag.like.service.LikeCursorIdGenerator;
import com.rag.like.service.LikeKafkaPushKey;
import com.rag.like.service.LikeRedisKeys;
import com.rag.proto.like.ActionType;
import com.rag.proto.like.GetLikeCountReq;
import com.rag.proto.like.GetLikeCountResp;
import com.rag.proto.like.GetLikeStateReq;
import com.rag.proto.like.GetLikeStateResp;
import com.rag.proto.like.GetTargetLikerListReq;
import com.rag.proto.like.GetTargetLikerListResp;
import com.rag.proto.like.GetUserLikeListReq;
import com.rag.proto.like.GetUserLikeListResp;
import com.rag.proto.like.GetUserTotalLikeReq;
import com.rag.proto.like.GetUserTotalLikeResp;
import com.rag.proto.like.LikeActionReq;
import com.rag.proto.like.LikeActionResp;
import com.rag.proto.like.LikeCountItem;
import com.rag.proto.like.LikeItem;
import com.rag.proto.like.LikeRecordItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * like 域 6 个 gRPC 用例的业务实现 —— 逐个对应 Go 端
 * {@code service/like/rpc/internal/logic/*.go} 的 6 个 logic。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeDomainService.java}
 * （B 侧 1221 行 · L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.like.like.rpc.service} 移到 {@code com.rag.like.domain.service}
 *           （D7：领域服务归 {@code domain/service/}）；</li>
 *       <li>proto 包名由 {@code com.rag.like.like.proto} 改为 {@code com.rag.proto.like}（D7）；</li>
 *       <li>协作者的 import 随各自落点调整（{@code LikeKafkaTopics} → {@code messaging/}、
 *           {@code LikeTaskProgressPublisher} → {@code messaging/}、
 *           {@code LikeRpcProperties} → {@code config/}、
 *           {@code LikeRpcMetrics} → {@code metrics/}、
 *           {@code MessageRpcClient} → {@code client/}、
 *           {@code LikeRedisKeys} / {@code LikeCursorIdGenerator} / {@code LikeActionScript} /
 *           {@code LikeKafkaPushKey} → {@code service/}）；</li>
 *       <li>B 侧的 {@code LikeSpans} 静态方法<b>并入 {@link LikeRpcMetrics}</b>
 *           （映射表第 209 行）⇒ 调用点由 {@code LikeSpans.xxx} 改为 {@code LikeRpcMetrics.xxx}，
 *           <b>属性名与写入时机逐字未改</b>。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。Redis 降级 / DB 致命的<b>不对称</b>、6 个用例的执行顺序、
 *       span 属性的写入位置（参数校验<b>之前</b>）、Lua 返回值 0 的<b>不短路</b>结构逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 本类最重要的一条语义：Redis 读失败<b>不是</b>致命错误</h2>
 *
 * <p>Go 侧全部 6 个 logic 的 Redis 读都是这个形状：
 * <pre>
 * vals, err := redis.HmgetCtx(...)
 * if err != nil {
 *     span.RecordError(err)
 *     metrics...("redis_error").Add(n)
 *     logger.LogBusinessErr(ctx, errmsg.ErrorRedisSelect, err)
 * }
 * // ← 注意：没有 return，继续往下走
 * </pre>
 * go-zero 的 {@code HmgetCtx} 失败时返回 <b>(nil, err)</b>，而后续代码用
 * {@code if len(vals) > i} 保护下标，于是 <b>全部 id 落进 missingIds → 回源 DB</b>。
 * Go 源码自己的注释把这条设计意图写得很清楚（{@code getlikecountlogic.go:139-142}）：
 * <pre>
 * 这意味着程序会继续往下走，vals 是空的…所有的 ID 都会被丢进 missingIds，然后自动降级去查询 DB！
 * 这是一种极其优秀的"缓存容灾降级"策略，保证了 Redis 宕机时，业务依然能（勉强）运作！
 * </pre>
 *
 * <p>⚠️ <b>Spring Data Redis 是抛异常的</b>
 * （{@code RedisConnectionFailureException} / {@code QueryTimeoutException} 等）。
 * 如果直接 {@code opsForHash().multiGet(...)} 而不包 try/catch，
 * Redis 一抖动就会把整个 RPC 变成 5003 —— 与 Go 完全相反，且<b>正是那种「看起来更地道的 Spring 写法」</b>
 * 会引入的漂移（AGENTS.md §26 判据：HIGH-RISK DRIFT）。
 *
 * <p>本类的 {@link #hmgetLenient} / {@link #hgetLenient} / {@link #zrevrangeLenient} 就是这个适配层：
 * <b>失败 ⇒ 返回空结果 ⇒ 上层自然走 DB 回源</b>。
 *
 * <h2>⚠️ 与之对称的一条：DB 读失败<b>是</b>致命错误</h2>
 *
 * <p>同一段代码里 {@code GetBatchLikeCount} 失败却直接
 * {@code return nil, errmsg.NewGrpcErr(errmsg.ErrorDbSelect, "DB查询失败")}。
 * <b>Redis 降级、DB 致命</b>这个不对称是 Go 的刻意设计，不要「统一风格」抹平它。
 *
 * <h2>⚠️ 写路径的 Redis 失败也是致命的</h2>
 *
 * <p>{@code likeAction} 的 Lua 执行失败 ⇒ {@code errmsg.NewGrpcErr(ErrorRedisUpdate, "Redis更新失败")}。
 * 写没有回源可退：Lua 要么原子生效要么没生效，不能「假装成功」。
 */
@Service
public class LikeDomainService {

    private static final Logger LOG = LoggerFactory.getLogger(LikeDomainService.class);

    /**
     * Go {@code getuserlikelistlogic.go:42} / {@code gettargetlikerlistlogic.go:45}：
     * {@code if limit <= 0 || limit > 100 { limit = 10 }}。
     */
    private static final int PAGE_SIZE_DEFAULT = 10;
    private static final int PAGE_SIZE_MAX = 100;

    /** Go {@code strings.EqualFold(in.TargetType, "article")} —— 文章专属副作用的判定目标类型。 */
    private static final String TARGET_TYPE_ARTICLE = "article";

    /** Go {@code getActionString} 的未知分支返回值，仅用于 metrics label 与 span 属性。 */
    private static final String ACTION_UNKNOWN = "unknown";

    private final StringRedisTemplate redis;
    private final LikeRecordMapper recordMapper;
    private final LikeRpcProperties properties;
    private final LikeRpcMetrics metrics;
    private final LikeKafkaTopics topics;
    private final KafkaLikeMsgPublisher likeMsgPublisher;
    private final LikeTaskProgressPublisher taskProgressPublisher;
    private final MessageRpcClient messageRpcClient;
    private final ObjectMapper objectMapper;

    public LikeDomainService(StringRedisTemplate redis,
                             LikeRecordMapper recordMapper,
                             LikeRpcProperties properties,
                             LikeRpcMetrics metrics,
                             LikeKafkaTopics topics,
                             @Qualifier("likeMsgPublisher") KafkaLikeMsgPublisher likeMsgPublisher,
                             LikeTaskProgressPublisher taskProgressPublisher,
                             MessageRpcClient messageRpcClient,
                             ObjectMapper objectMapper) {
        this.redis = redis;
        this.recordMapper = recordMapper;
        this.properties = properties;
        this.metrics = metrics;
        this.topics = topics;
        this.likeMsgPublisher = likeMsgPublisher;
        this.taskProgressPublisher = taskProgressPublisher;
        this.messageRpcClient = messageRpcClient;
        this.objectMapper = objectMapper;
    }

    // ==================================================================================
    // OP-01  GetLikeCount —— 对应 Go internal/logic/getlikecountlogic.go
    // ==================================================================================

    /**
     * 查询某类作品的一批点赞 / 点踩数。
     *
     * <p>流程：Redis 批量读 → 命中部分直接用；缺失部分查 DB → <b>回填 Redis</b>。
     */
    public GetLikeCountResp getLikeCount(GetLikeCountReq request) {
        GetLikeCountResp.Builder resp = GetLikeCountResp.newBuilder();

        // Go: getlikecountlogic.go:37-41 —— span.SetAttributes(query.target_type, query.batch_size)
        //     ⚠️ 位置在「空 ID」校验**之前** ⇒ 非法请求也带这两个属性（顺序是行为的一部分）。
        LikeRpcMetrics.queryTargetType(request.getTargetType());
        LikeRpcMetrics.queryBatchSize(request.getTargetIdsCount());

        // Go: if len(in.TargetIds) == 0 { return nil, NewGrpcErr(ErrorInputWrong, "输入需要查询的ID为空") }
        if (request.getTargetIdsCount() == 0) {
            throw LikeExceptions.inputWrong("输入需要查询的ID为空");
        }

        List<String> ids = request.getTargetIdsList();
        String likeCountKey = LikeRedisKeys.likeCountHash(request.getTargetType());
        String dislikeCountKey = LikeRedisKeys.dislikeCountHash(request.getTargetType());

        // 两次 hmget 各自独立容错：Redis 挂了 ⇒ 两个都空 ⇒ 全部落 missingIds ⇒ 全量回源 DB
        List<String> likeVals = hmgetLenient(likeCountKey, ids);
        List<String> dislikeVals = hmgetLenient(dislikeCountKey, ids);

        List<String> missingIds = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            // Go: if len(likeVals) > i { likeStr = likeVals[i] } —— 越界时保持零值 ""
            String likeStr = i < likeVals.size() ? likeVals.get(i) : "";
            String dislikeStr = i < dislikeVals.size() ? dislikeVals.get(i) : "";

            // Go: if likeStr == "" { missingIds = append(...); continue }
            // ⚠️ 判定只看 like：Redis 里存了 like 却没有 dislike 是常态（没点踩过）
            if (likeStr.isEmpty()) {
                missingIds.add(id);
                continue;
            }
            // Go: if dislikeStr == "" { dislikeStr = "0" }
            if (dislikeStr.isEmpty()) {
                dislikeStr = "0";
            }
            resp.putCounts(id, LikeCountItem.newBuilder()
                    .setLikeCount(parseLongOrZero(likeStr))
                    .setDislikeCount(parseLongOrZero(dislikeStr))
                    .build());
        }

        int hits = ids.size() - missingIds.size();
        int misses = missingIds.size();
        // Go: getlikecountlogic.go:90-93 —— span.SetAttributes(cache.hits, cache.misses)
        //     ⚠️ 位置在「DB 回源」**之前** ⇒ 回源失败时这两个属性也已落到 span 上。
        LikeRpcMetrics.cacheHits(hits);
        LikeRpcMetrics.cacheMisses(misses);
        if (hits > 0) {
            metrics.cache(request.getTargetType(), "hit", hits);
        }

        if (misses > 0) {
            metrics.cache(request.getTargetType(), "miss", misses);
            // Go: return nil, errmsg.NewGrpcErr(errmsg.ErrorDbSelect, "DB查询失败") —— DB 失败即致命
            Map<String, Map<Integer, Long>> dbCounts =
                    loadStateCounts(request.getTargetType(), missingIds);

            for (String id : missingIds) {
                long likeCount = 0L;
                long dislikeCount = 0L;
                Map<Integer, Long> byState = dbCounts.get(id);
                if (byState != null) {
                    // Go: likeCount = counts[1]; dislikeCount = counts[2]
                    likeCount = byState.getOrDefault(1, 0L);
                    dislikeCount = byState.getOrDefault(2, 0L);
                }
                resp.putCounts(id, LikeCountItem.newBuilder()
                        .setLikeCount(likeCount)
                        .setDislikeCount(dislikeCount)
                        .build());

                // Go: 回填 Redis；写失败只记日志，不影响本次响应
                hsetLenient(likeCountKey, id, String.valueOf(likeCount));
                hsetLenient(dislikeCountKey, id, String.valueOf(dislikeCount));
            }
        }

        return resp.build();
    }

    /**
     * 对应 Go {@code GetBatchLikeCount} 的结果整形：
     * {@code map[target_id]map[state]count}。
     *
     * <p>DB 查询失败时抛 5003（致命）—— 这是本域「Redis 降级 / DB 致命」不对称中的 DB 那一半。
     */
    private Map<String, Map<Integer, Long>> loadStateCounts(String targetType, List<String> targetIds) {
        List<LikeRecordMapper.StateCountRow> rows;
        try {
            rows = recordMapper.selectStateCountsBatch(targetType, targetIds);
        } catch (Exception e) {
            throw LikeExceptions.dbSelect("DB查询失败");
        }
        Map<String, Map<Integer, Long>> result = new HashMap<>();
        for (LikeRecordMapper.StateCountRow row : rows) {
            result.computeIfAbsent(row.getTargetId(), k -> new HashMap<>())
                    .put(row.getState(), row.getCnt());
        }
        return result;
    }

    // ==================================================================================
    // OP-02  GetLikeState —— 对应 Go internal/logic/getlikestatelogic.go
    // ==================================================================================

    /**
     * 查询某用户对一批作品的状态。
     *
     * <p>⚠️ <b>防穿透行为</b>：DB 回源时查不到的 id 取 Go map 的<b>零值 0</b> 并<b>写回 Redis</b>
     * （Go 注释：「否则如果多次连续发 Redis 中不存在的数据, 一直调用 SQL, 会导致很大的问题」）。
     * 照搬：把「查不到」也当作 {@code state=0} 缓存起来。
     */
    public GetLikeStateResp getLikeState(GetLikeStateReq request) {
        GetLikeStateResp.Builder resp = GetLikeStateResp.newBuilder();

        // Go: getlikestatelogic.go:37-42 —— span.SetAttributes(query.user_id, query.target_type, query.batch_size)
        //     ⚠️ 位置在「空 ID」校验**之前** ⇒ 非法请求也带这三个属性。
        LikeRpcMetrics.queryUserId(request.getUserId());
        LikeRpcMetrics.queryTargetType(request.getTargetType());
        LikeRpcMetrics.queryBatchSize(request.getTargetIdsCount());

        // Go: if len(in.TargetIds) == 0 { ... "输入需要查询的ID为空" }
        if (request.getTargetIdsCount() == 0) {
            throw LikeExceptions.inputWrong("输入需要查询的ID为空");
        }

        List<String> ids = request.getTargetIdsList();
        String field = String.valueOf(request.getUserId());
        List<String> missingIds = new ArrayList<>();

        for (String id : ids) {
            String stateKey = LikeRedisKeys.likeStateHash(request.getTargetType(), id);
            // Go（getlikestatelogic.go:51-59）:
            //   stateStr, err := HgetCtx(...);
            //   if err != nil { span.RecordError(err); LikeQueryCacheCount{…, "redis_error"}.Add(1); log }
            //   if stateStr == "" { missingIds = append(…); continue }   ← 这一段**不打点**
            // ⚠️ 2026-10-23 修正 DRIFT-LIKE-009：此前直接用 hgetLenient（异常被吞成 ""），
            //    导致 redis_error 一次都不打 —— Go 侧每次读失败都会 +1。
            HgetResult state = hgetLenientWithError(stateKey, field);
            if (state.error()) {
                metrics.cache(request.getTargetType(), "redis_error", 1);
            }
            if (state.value().isEmpty()) {
                missingIds.add(id);
                continue;
            }
            resp.putStates(id, (int) parseLongOrZero(state.value()));
        }

        int hits = ids.size() - missingIds.size();
        int misses = missingIds.size();
        // Go: getlikestatelogic.go:69-72 —— span.SetAttributes(cache.hits, cache.misses)
        //     ⚠️ 位置在「DB 回源」**之前** ⇒ 回源失败时这两个属性也已落到 span 上。
        LikeRpcMetrics.cacheHits(hits);
        LikeRpcMetrics.cacheMisses(misses);
        if (hits > 0) {
            metrics.cache(request.getTargetType(), "hit", hits);
        }

        if (!missingIds.isEmpty()) {
            metrics.cache(request.getTargetType(), "miss", missingIds.size());

            Map<String, Integer> dbStates;
            try {
                dbStates = loadUserStates(request.getUserId(), request.getTargetType(), missingIds);
            } catch (LikeExceptions.Rpc e) {
                throw e;
            } catch (Exception e) {
                throw LikeExceptions.dbSelect("DB查询失败");
            }

            for (String id : missingIds) {
                // Go: state := dbRespMap[id] —— 查不到即 map 零值 0，并写回 Redis 防穿透
                int state = dbStates.getOrDefault(id, 0);
                resp.putStates(id, state);
                hsetLenient(LikeRedisKeys.likeStateHash(request.getTargetType(), id), field, String.valueOf(state));
            }
        }

        return resp.build();
    }

    private Map<String, Integer> loadUserStates(long userId, String targetType, List<String> targetIds) {
        List<LikeRecordMapper.TargetStateRow> rows =
                recordMapper.selectUserStatesBatch(userId, targetType, targetIds);
        Map<String, Integer> result = new HashMap<>();
        for (LikeRecordMapper.TargetStateRow row : rows) {
            result.put(row.getTargetId(), row.getState());
        }
        return result;
    }

    // ==================================================================================
    // OP-03  GetUserTotalLike —— 对应 Go internal/logic/getusertotallikelogic.go
    // ==================================================================================

    /**
     * 查询某作者的累计获赞数。
     *
     * <p>本域<b>唯一</b>一个用 String + TTL 的缓存（其余都是 Hash/ZSet 且多数无 TTL）。
     *
     * <p>⚠️ 两个细节：
     * <ul>
     *   <li><b>命中后要续期</b>：Go {@code ExpireCtx(ctx, redisKey, ttl)} 把 TTL 重置，
     *       所以热门作者的计数不会被固定窗口截断。</li>
     *   <li>Redis 出错时 Go 走的是 {@code if err != nil {log} else if val != "" {return}}，
     *       错误分支<b>不 return</b> ⇒ 落到 miss 分支查 DB。<b>Redis 故障被当成 miss</b>。</li>
     * </ul>
     */
    public GetUserTotalLikeResp getUserTotalLike(GetUserTotalLikeReq request) {
        long userId = request.getUserId();
        // Go: getusertotallikelogic.go:34-37 —— span.SetAttributes(query.user_id)
        //     ⚠️ 位置在「userId <= 0」校验**之前** ⇒ 非法请求也带这个属性。
        LikeRpcMetrics.queryUserId(userId);
        // Go: if in.UserId <= 0 { ... "非法的用户ID" }
        if (userId <= 0) {
            throw LikeExceptions.illegalUserId("非法的用户ID");
        }

        String key = LikeRedisKeys.userTotalLikeString(userId);
        String cached = getLenient(key);

        if (!cached.isEmpty()) {
            // Go: getusertotallikelogic.go:52 —— span.SetAttributes(cache.status, "hit")
            LikeRpcMetrics.cacheStatusHit();
            metrics.cache("user_total", "hit");
            long count = parseLongOrZero(cached);
            // Go: _ = ExpireCtx(ctx, redisKey, int(ttl)) —— 续期，失败也忽略
            expireLenient(key, properties.getRedis().getCacheTtl());
            return GetUserTotalLikeResp.newBuilder().setTotalLikeCount(count).build();
        }

        // Go: getusertotallikelogic.go:64 —— span.SetAttributes(cache.status, "miss")
        //     ⚠️ Go 的结构是「Redis 出错 ⇒ 落到 miss 分支」⇒ 本处与 Go 同形。
        LikeRpcMetrics.cacheStatusMiss();
        metrics.cache("user_total", "miss");

        final long totalCount;
        try {
            totalCount = recordMapper.countActiveLikeByAuthor(userId);
        } catch (Exception e) {
            // Go: return nil, errmsg.NewGrpcErr(errmsg.ErrorDbSelect, "DB查询失败")
            throw LikeExceptions.dbSelect("DB查询失败");
        }

        // Go: SetexCtx(...) 失败只记日志，不影响返回值
        setexLenient(key, String.valueOf(totalCount), properties.getRedis().getCacheTtl());
        return GetUserTotalLikeResp.newBuilder().setTotalLikeCount(totalCount).build();
    }

    // ==================================================================================
    // OP-04  GetUserLikeList —— 对应 Go internal/logic/getuserlikelistlogic.go
    // ==================================================================================

    /**
     * 某用户给出的点赞列表（游标分页）。
     *
     * <p><b>⚠️ (1) cursor 同时当两种刻度用（真实存在，保留）</b>：Go 把同一个 cursor 既当作 ZSet 的
     * {@code maxScore}（{@code maxScore = cursor - 1}，{@code getuserlikelistlogic.go:48-52}），
     * 又当作 DB 的 {@code id < cursor} 上界（{@code internal/model/like.go:126}）。
     * 而 cursor 是 {@code score} 本身（{@code timestamp << 22}），不是 {@code like_record.id}。
     * 两套刻度不一致 ⇒ 翻页在某些数据分布下会跳/重。<b>这是 Go 的现状，不修</b>。
     *
     * <p><b>⚠️ (2) 关于「回源写秒 / 写路径写毫秒」——2026-10-23 撤销，这是错误断言 A</b>
     * <br>原文论证「GORM 取 {@code data.Unix()} ⇒ 回源产出秒」<b>前提不成立</b>，该路径在 Go 侧<b>从未被执行</b>：
     * <ol>
     *   <li>{@code UserLikeListResult.CreateTime}（{@code internal/model/like.go:114-119}）无 gorm tag，
     *       gorm 的 {@code toDBName("CreateTime")} 推出 DBName = {@code create_time}；</li>
     *   <li>而 SQL 取的是 {@code created_at}（{@code like.go:130} {@code Select("id, target_type,target_id, created_at")}）;</li>
     *   <li>两者不匹配 ⇒ {@code Scan} 后 {@code CreateTime} <b>恒为 0</b>
     *       ⇒ {@code getuserlikelistlogic.go:94-96} 的 {@code if timestamp == 0 { continue }} <b>每行命中</b>
     *       ⇒ 该分支<b>从未</b>执行 ZADD、<b>从未</b>向 list 追加任何元素 ⇒ <b>回源路径整体是死代码</b>
     *       （这正是 DRIFT-LIKE-001 的真实成因：Go 侧该功能冷缓存下恒返回空列表）。</li>
     * </ol>
     * ⇒ 「秒 vs 毫秒不一致」这段行为<b>不存在</b>，无从对齐，也就谈不上「原样保留」。
     * <br>可对齐的是 Go 的<b>命中分支</b>（{@code :70-77} {@code realTimestamp := score >> 22}，
     * score 来自 {@code UnixMilli << 22}）⇒ {@code pb.LikeRecordItem.timestamp} 单位是<b>毫秒</b>。
     * <br>⇒ Java 的回源分支已改为产出<b>毫秒</b>（见 {@code epochMillis}），
     * 与命中分支、写路径三者<b>共用同一刻度</b>；否则同一条记录冷/热缓存下 timestamp 会差 1000 倍。
     * ⚠️ 这条与 DRIFT-LIKE-001 的签字不冲突：签字的是「Go 空列表是缺陷、Java 返回真实数据」，
     * <b>没有</b>签字「Java 应当沿用 Go 那条死路径的秒刻度」。
     */
    public GetUserLikeListResp getUserLikeList(GetUserLikeListReq request) {
        // Go: getuserlikelistlogic.go:34-40 —— span.SetAttributes(query.user_id, query.target_type,
        //     query.cursor, query.page_size)
        //     ⚠️ query.page_size 取的是**原始入参** in.PageSize（不是归一化后的 limit）⇒ 这里用 getPageSize() 原值。
        LikeRpcMetrics.queryUserId(request.getUserId());
        LikeRpcMetrics.queryTargetType(request.getTargetType());
        LikeRpcMetrics.queryCursor(request.getCursor());
        LikeRpcMetrics.queryPageSize(request.getPageSize());
        int limit = normalizePageSize(request.getPageSize());
        int fetchLimit = limit + 1;

        String redisKey = LikeRedisKeys.userLikeListZSet(request.getUserId(), request.getTargetType());
        long maxScore = request.getCursor() > 0 ? request.getCursor() - 1 : Long.MAX_VALUE;

        // Go: res, err := ZrevrangebyscoreWithScoresAndLimitCtx(...); if err != nil { log } —— 不 return
        List<ZSetOperations.TypedTuple<String>> res = zrevrangeLenient(redisKey, maxScore, fetchLimit);

        List<LikeRecordItem> list = new ArrayList<>();
        long nextCursor = 0L;
        boolean isEnd = true;

        if (!res.isEmpty()) {
            List<ZSetOperations.TypedTuple<String>> effective = res;
            if (effective.size() > limit) {
                isEnd = false;
                effective = effective.subList(0, limit);
            }
            for (ZSetOperations.TypedTuple<String> pair : effective) {
                long score = toLongBits(pair.getScore());
                // Go: realTimestamp := score >> 22
                list.add(LikeRecordItem.newBuilder()
                        .setTargetId(pair.getValue())
                        // Go: TargetType: in.TargetType —— 注意用的是**请求里的**类型，不是库里存的
                        .setTargetType(request.getTargetType())
                        .setTimestamp(LikeCursorIdGenerator.timestampOf(score))
                        .build());
                nextCursor = score;
            }
        }

        if (!list.isEmpty()) {
            // Go: getuserlikelistlogic.go:80 / gettargetlikerlistlogic.go:86
            //     —— span.SetAttributes(cache.status, "hit")
            LikeRpcMetrics.cacheStatusHit();
            metrics.cache(request.getTargetType(), "hit");
        }

        if (list.isEmpty()) {
            // Go: getuserlikelistlogic.go:84 / gettargetlikerlistlogic.go:90
            //     —— span.SetAttributes(cache.status, "miss")
            LikeRpcMetrics.cacheStatusMiss();
            metrics.cache(request.getTargetType(), "miss");

            final List<LikeRecordMapper.UserLikeRow> dbResults;
            try {
                dbResults = recordMapper.selectUserLikeList(request.getUserId(), request.getTargetType(),
                        request.getCursor(), fetchLimit);
            } catch (Exception e) {
                throw LikeExceptions.dbSelect("DB查询失败");
            }

            if (dbResults.size() > limit) {
                isEnd = false;
            }

            for (int i = 0; i < dbResults.size(); i++) {
                LikeRecordMapper.UserLikeRow row = dbResults.get(i);
                // ⚠️ 2026-10-23 修正（原注释「Go 给的是 Unix 秒」是错的，且由此引入了一个自洽性缺陷）。
                //   真源 getuserlikelistlogic.go:70-77 的**命中分支**是：
                //       realTimestamp := score >> 22   where score = UnixMilli << 22
                //   ⇒ pb.LikeRecordItem.timestamp 的单位是**毫秒**，不是秒。
                //   另一侧 getuserlikelistlogic.go:93-96 的回源分支写 score = timestamp << 22，
                //   与命中分支共用同一个刻度 ⇒ 回源产出的 timestamp 也必须毫秒。
                //   此前这里用 epochSeconds(秒) ⇒ 写进 ZSet 的 score 比写路径小 1000 倍，
                //   同一条记录在 cache-hit 下与 cache-miss 下会返回**差 1000 倍**的 timestamp。
                long timestamp = epochMillis(row.getCreatedAt());
                if (timestamp == 0L) {
                    // Go: 脏数据（缺时间戳）→ 记日志并跳过，**且不计入返回的 limit 条数**
                    LOG.warn("like list dirty data without timestamp: id={} type={} target={}",
                            row.getId(), row.getTargetType(), row.getTargetId());
                    continue;
                }
                long score = LikeCursorIdGenerator.scoreOf(timestamp);
                zaddLenient(redisKey, score, row.getTargetId());
                if (i < limit) {
                    list.add(LikeRecordItem.newBuilder()
                            .setTargetId(row.getTargetId())
                            // Go: TargetType: r.TargetType —— 这条走的是**库里存的**类型
                            .setTargetType(row.getTargetType())
                            .setTimestamp(timestamp)
                            .build());
                    nextCursor = score;
                }
            }

            if (!dbResults.isEmpty()) {
                // Go: if len(dbResults) > 0 { ExpireCtx(redisKey, 3600*24*7) }
                expireLenient(redisKey, properties.getRedis().getListTtl());
            }
        }

        if (isEnd) {
            // Go: if isEnd { nextCursor = 0 } —— 到头时把游标清零，客户端据此停止翻页
            nextCursor = 0L;
        }

        return GetUserLikeListResp.newBuilder()
                .addAllList(list)
                .setIsEnd(isEnd)
                .setNextCursor(nextCursor)
                .build();
    }

    // ==================================================================================
    // OP-05  GetTargetLikerList —— 对应 Go internal/logic/gettargetlikerlistlogic.go
    // ==================================================================================

    /**
     * 某作品的点赞人列表（游标分页）。
     *
     * <p>与 {@link #getUserLikeList} 同构，差异仅在：member 是 user_id（需 parse 成 long）、
     * 返回类型是 {@link LikeItem}（无 target_type 字段）。同样的「秒/毫秒刻度不一致」见该方法注释。
     */
    public GetTargetLikerListResp getTargetLikerList(GetTargetLikerListReq request) {
        // Go: gettargetlikerlistlogic.go:35-42 —— span.SetAttributes(query.target_type, query.target_id,
        //     query.cursor, query.page_size)
        //     ⚠️ query.page_size 取的是**原始入参** in.PageSize（不是归一化后的 limit）⇒ 这里用 getPageSize() 原值。
        LikeRpcMetrics.queryTargetType(request.getTargetType());
        LikeRpcMetrics.queryTargetId(request.getTargetId());
        LikeRpcMetrics.queryCursor(request.getCursor());
        LikeRpcMetrics.queryPageSize(request.getPageSize());
        int limit = normalizePageSize(request.getPageSize());
        int fetchLimit = limit + 1;

        String redisKey = LikeRedisKeys.targetLikerListZSet(request.getTargetType(), request.getTargetId());
        long maxScore = request.getCursor() > 0 ? request.getCursor() - 1 : Long.MAX_VALUE;

        List<ZSetOperations.TypedTuple<String>> res = zrevrangeLenient(redisKey, maxScore, fetchLimit);

        List<LikeItem> list = new ArrayList<>();
        long nextCursor = 0L;
        boolean isEnd = true;

        if (!res.isEmpty()) {
            List<ZSetOperations.TypedTuple<String>> effective = res;
            if (effective.size() > limit) {
                isEnd = false;
                effective = effective.subList(0, limit);
            }
            for (ZSetOperations.TypedTuple<String> pair : effective) {
                long score = toLongBits(pair.getScore());
                list.add(LikeItem.newBuilder()
                        // Go: userId, _ := strconv.ParseInt(pair.Key, 10, 64) —— 解析失败取 0
                        .setUserId(parseLongOrZero(pair.getValue()))
                        .setTimestamp(LikeCursorIdGenerator.timestampOf(score))
                        .build());
                nextCursor = score;
            }
        }

        if (!list.isEmpty()) {
            // Go: getuserlikelistlogic.go:80 / gettargetlikerlistlogic.go:86
            //     —— span.SetAttributes(cache.status, "hit")
            LikeRpcMetrics.cacheStatusHit();
            metrics.cache(request.getTargetType(), "hit");
        }

        if (list.isEmpty()) {
            // Go: getuserlikelistlogic.go:84 / gettargetlikerlistlogic.go:90
            //     —— span.SetAttributes(cache.status, "miss")
            LikeRpcMetrics.cacheStatusMiss();
            metrics.cache(request.getTargetType(), "miss");

            final List<LikeRecordMapper.TargetLikerRow> dbResults;
            try {
                dbResults = recordMapper.selectTargetLikerList(request.getTargetType(), request.getTargetId(),
                        request.getCursor(), fetchLimit);
            } catch (Exception e) {
                throw LikeExceptions.dbSelect("DB查询失败");
            }

            if (dbResults.size() > limit) {
                isEnd = false;
            }

            for (int i = 0; i < dbResults.size(); i++) {
                LikeRecordMapper.TargetLikerRow row = dbResults.get(i);
                // ⚠️ 同 getUserLikeList：Go 的 TargetLikerListResult.CreateTime 同样因 gorm
                // DBName(create_time) 与 Select 的 created_at 不匹配而恒 0 ⇒ 回源分支是死代码；
                // 可对齐的是命中分支 gettargetlikerlistlogic.go 的 score >> 22（毫秒）。
                long timestamp = epochMillis(row.getCreatedAt());
                if (timestamp == 0L) {
                    LOG.warn("target liker list dirty data without timestamp: id={} userId={}",
                            row.getId(), row.getUserId());
                    continue;
                }
                long score = LikeCursorIdGenerator.scoreOf(timestamp);
                zaddLenient(redisKey, score, String.valueOf(row.getUserId()));
                if (i < limit) {
                    list.add(LikeItem.newBuilder()
                            .setUserId(row.getUserId())
                            .setTimestamp(timestamp)
                            .build());
                    nextCursor = score;
                }
            }

            if (!dbResults.isEmpty()) {
                // Go: ExpireCtx(redisKey, 86400*7)
                expireLenient(redisKey, properties.getRedis().getListTtl());
            }
        }

        if (isEnd) {
            nextCursor = 0L;
        }

        return GetTargetLikerListResp.newBuilder()
                .addAllList(list)
                .setIsEnd(isEnd)
                .setNextCursor(nextCursor)
                .build();
    }

    // ==================================================================================
    // OP-06  LikeAction —— 对应 Go internal/logic/likeactionlogic.go
    // ==================================================================================

    /**
     * 执行一次点赞 / 取消点赞 / 点踩 / 取消点踩。
     *
     * <p>本方法是全模块语义密度最高的一段，按 Go 的执行顺序严格对齐：
     * <pre>
     * 1. 参数校验（target_id / target_type 非空，user_id &gt; 0）
     * 2. 生成游标 ID，执行 Lua（6 个 key 原子改）
     * 3. 按 Lua 返回值分三支：0 幂等（不发 Kafka）/ 1 变更 / 2 首次点赞（isFirst=true）
     * 4. HGET 回读最终点赞数与点踩数
     * 5. 仅当 resInt != 0 且 action == LIKE 且 targetType 是 article 时：
     *    a. 用 max(Redis, DB) 修正点赞数
     *    b. 推任务进度（用户路 + 文章路）
     *    c. 若 author_id &gt; 0 且 user_id != author_id，给作者发通知
     * </pre>
     *
     * <p><b>⚠️ 第 4 步无条件执行</b>：Go 在 {@code resInt == 0}（幂等）时<b>不会短路</b>，
     * 仍然 HGET 两次并返回真实计数。所以「重复点赞」返回的是当前计数，而不是「什么都没做」。
     */
    public LikeActionResp likeAction(LikeActionReq request) {
        String actionStr = actionName(request.getActionTypeValue());

        // Go: likeactionlogic.go:161-167 —— span.SetAttributes(like.user_id, like.target_type,
        //     like.target_id, like.action_type)
        //     ⚠️ 位置在「参数异常」校验**之前** ⇒ 非法请求也带这四个属性。
        //     ⚠️ like.action_type 取 Go getActionString 的同一套字面量（like/unlike/dislike/undislike/unknown）。
        LikeRpcMetrics.likeUserId(request.getUserId());
        LikeRpcMetrics.likeTargetType(request.getTargetType());
        LikeRpcMetrics.likeTargetId(request.getTargetId());
        LikeRpcMetrics.likeActionType(actionStr);

        // Go: if in.TargetId == "" || in.TargetType == "" || in.UserId <= 0 { ... "参数异常" }
        if (request.getTargetId().isEmpty()
                || request.getTargetType().isEmpty()
                || request.getUserId() <= 0) {
            metrics.action(request.getTargetType(), actionStr, "invalid_param");
            throw LikeExceptions.invalidActionParam("参数异常");
        }

        String countKey = LikeRedisKeys.likeCountHash(request.getTargetType());
        String stateKey = LikeRedisKeys.likeStateHash(request.getTargetType(), request.getTargetId());
        String userListKey = LikeRedisKeys.userLikeListZSet(request.getUserId(), request.getTargetType());
        String targetListKey = LikeRedisKeys.targetLikerListZSet(request.getTargetType(), request.getTargetId());
        String dislikeCountKey = LikeRedisKeys.dislikeCountHash(request.getTargetType());
        String authorTotalKey = LikeRedisKeys.userTotalLikeString(request.getAuthorId());

        long score = LikeCursorIdGenerator.nextId();

        // ⚠️ Lua 执行失败是**致命**的：写路径没有回源可退
        final Long raw;
        try {
            raw = redis.execute(
                    LikeActionScript.INSTANCE,
                    List.of(countKey, stateKey, userListKey, targetListKey, dislikeCountKey, authorTotalKey),
                    LikeActionScript.args(request.getTargetId(), request.getUserId(),
                            request.getActionTypeValue(), score));
        } catch (Exception e) {
            metrics.action(request.getTargetType(), actionStr, "redis_error");
            throw LikeExceptions.redisUpdate("Redis更新失败");
        }
        long resultCode = raw == null ? 0L : raw;

        if (resultCode == LikeActionScript.RESULT_IDEMPOTENT) {
            metrics.action(request.getTargetType(), actionStr, "idempotent");
        } else {
            boolean isFirst = resultCode == LikeActionScript.RESULT_FIRST_LIKE;
            // Go: 雪花 ID 失败只记指标，**继续往下发消息**（msgId=0 → 消费侧会判为非法并丢弃）
            String msgId;
            try {
                msgId = String.valueOf(Snowflake.nextId());
            } catch (Exception e) {
                metrics.action(request.getTargetType(), actionStr, "snowflake_error");
                msgId = "0";
            }

            KafkaLikeMsg msg = new KafkaLikeMsg(
                    msgId, request.getUserId(), request.getTargetType(), request.getTargetId(),
                    request.getAuthorId(), request.getActionTypeValue(), isFirst,
                    // Go: CreatedAt: time.Now().Unix() —— Unix 秒
                    Instant.now().getEpochSecond());

            // ⚠️ Kafka 推送失败**不影响** RPC 返回值（Go 只打日志）
            // Go likeactionlogic.go:234-250 是三条**独立**指标分支：
            //   :236 json_error（Marshal 失败） / :245 kafka_error（Push 失败） / :248 success
            // 此前 Java 只有一个 boolean，把 json_error 并进了 kafka_error（DRIFT-LIKE-009 第 4 项）。
            switch (likeMsgPublisher.publish(msg)) {
                case SUCCESS -> metrics.action(request.getTargetType(), actionStr, "success");
                case JSON_ERROR -> metrics.action(request.getTargetType(), actionStr, "json_error");
                case KAFKA_ERROR -> metrics.action(request.getTargetType(), actionStr, "kafka_error");
            }
        }

        // ---- 以下无条件执行（Go 在 resInt==0 时也不短路）----
        long finalLikeCount = hgetCountLenient(countKey, request.getTargetId(), request.getTargetType(), actionStr);
        long finalDislikeCount = hgetCountLenient(dislikeCountKey, request.getTargetId(), request.getTargetType(), actionStr);

        boolean likeOnArticle = resultCode != 0
                && request.getActionType() == ActionType.ACTION_LIKE
                // Go: strings.EqualFold(in.TargetType, "article") —— 忽略大小写
                && TARGET_TYPE_ARTICLE.equalsIgnoreCase(request.getTargetType());

        if (likeOnArticle) {
            // Go: finallikeCount = l.currentArticleLikeTotal(targetType, targetId, finallikeCount)
            finalLikeCount = currentArticleLikeTotal(request.getTargetType(), request.getTargetId(), finalLikeCount);

            // Go: l.syncTaskProgress(in, userListKey, finallikeCount)
            // ⚠️ Go 的 currentUserLikeTotal 返回的是 **(int64, error)** 二元组
            //   ⇒ 这里必须把**失败原因一起**带下去，交给 LikeTaskProgressPublisher
            //     按 Go 的 `if err != nil / else if total > 0` 结构分流：
            //     双失败 ⇒ 记业务错误 5011（ErrorRedisSelect）+ **不推 user 路**；路 2 照走。
            //   ⚠️ 不能在这里分流（写成「有错就不调 publish」）⇒ 那会连 **路 2 一起跳过**，
            //     而 Go 的 syncTaskProgress 在路 1 之后**没有 return** ⇒ 制造新漂移。
            LikeTaskProgressPublisher.UserLikeTotal userLikesGiven =
                    currentUserLikeTotal(request.getUserId(), request.getTargetType(), userListKey);
            taskProgressPublisher.publish(request.getUserId(), request.getAuthorId(),
                    request.getTargetId(), userLikesGiven, finalLikeCount);
        }

        if (likeOnArticle
                && request.getAuthorId() > 0
                && request.getUserId() != request.getAuthorId()) {
            // Go: notifyArticleLiked(in) —— 失败只记日志
            messageRpcClient.sendArticleLiked(request.getAuthorId(), request.getUserId(),
                    request.getTargetType(), request.getTargetId());
        }

        return LikeActionResp.newBuilder()
                // Go: 无论成功与否都返回 Success: true —— 错误是通过 gRPC error 表达的，不是这个字段
                .setSuccess(true)
                .setMessage("action success")
                .setLikeCount(finalLikeCount)
                .setDislikeCount(finalDislikeCount)
                .build();
    }

    /**
     * 对应 Go {@code currentArticleLikeTotal}：{@code max(redisTotal, dbTotal)}。
     *
     * <p>DB 出错时返回 redisTotal（不抛）—— 缓存已经有值，降级用它。
     */
    private long currentArticleLikeTotal(String targetType, String targetId, long redisTotal) {
        long dbTotal;
        try {
            dbTotal = recordMapper.countActiveLikeByTarget(targetType, targetId);
        } catch (Exception e) {
            return redisTotal;
        }
        return dbTotal > redisTotal ? dbTotal : redisTotal;
    }

    /**
     * 对应 Go {@code currentUserLikeTotal} —— 返回 Go 的 {@code (int64, error)} <b>二元组</b>。
     *
     * <p>⚠️ Go 这里的错误处理有个容易看错的分支：
     * <pre>
     * total, err := ZcardCtx(...)
     * if err != nil { total = 0 }
     * dbTotal, dbErr := GetUserActiveLikeCount(...)
     * if dbErr != nil {
     *     if err == nil { return int64(total), nil }   // 只有 Redis 成功、DB 失败才返回 total
     *     return 0, dbErr                                // 两者都失败才返回错误
     * }
     * if dbTotal > int64(total) { return dbTotal, nil }
     * return int64(total), nil
     * </pre>
     * 即：Redis 与 DB **同时**失败才算失败，且此时返回 {@code 0} + error。
     * 调用方 {@code syncTaskProgress} 只在出错时记日志（业务码 {@code ErrorRedisSelect}=5011），
     * <b>不影响点赞结果</b>（Go 侧该分支既不抛、也不中断路 2 的 article 推送）。
     *
     * <p>🔴 <b>2026-10-29 G2 修 · global E60</b>：改前本方法双失败时只
     * {@code LOG.warn(...)} + {@code return 0L} ⇒ <b>error 被吞掉</b>（Go 的 {@code dbErr} 没传出去），
     * 双失败<b>没有 5011 业务错误记录</b>。
     * ⇒ 现改为返回 {@link LikeTaskProgressPublisher.UserLikeTotal}，把失败原因传给调用方。
     *
     * <p>⚠️ <b>不用异常代替 error</b>：Go 侧这里是<b>返回</b> error 而非 panic；
     * 若改成抛异常 ⇒ 双失败会让整个点赞 RPC 失败 ⇒ 比 Go 更严 ⇒ 制造新漂移。
     */
    private LikeTaskProgressPublisher.UserLikeTotal currentUserLikeTotal(long userId, String targetType, String userListKey) {
        long total = 0L;
        boolean redisOk = true;
        try {
            Long card = redis.opsForZSet().zCard(userListKey);
            total = card == null ? 0L : card;
        } catch (Exception e) {
            redisOk = false;
            total = 0L;
        }

        long dbTotal;
        try {
            dbTotal = recordMapper.countActiveLikeByUserAndType(userId, targetType);
        } catch (Exception e) {
            if (redisOk) {
                // Go: if err == nil { return int64(total), nil }
                // ⇒ Redis 成功、DB 失败 ⇒ 降级用 Redis 值，**不算失败**（error 为 nil）
                return LikeTaskProgressPublisher.UserLikeTotal.ok(total);
            }
            // Go: return 0, dbErr ⇒ 双失败 ⇒ error **必须传出去**，
            // 由 LikeTaskProgressPublisher 记业务错误 5011（ErrorRedisSelect）并跳过 user 路推送。
            return LikeTaskProgressPublisher.UserLikeTotal.failed(e);
        }

        // Go: if dbTotal > int64(total) { return dbTotal, nil }; return int64(total), nil
        return LikeTaskProgressPublisher.UserLikeTotal.ok(dbTotal > total ? dbTotal : total);
    }

    // ==================================================================================
    // Redis 适配层（错误语义的关键，全部 private，语义见方法注释）
    // ==================================================================================

    /**
     * {@code HMGET} 的容错封装。
     *
     * <p>Go：出错时返回 {@code (nil, err)}，调用方按「全空」处理 ⇒ 全部当 miss。
     * <p>Java：出错抛异常 ⇒ <b>必须捕获并返回空 List</b>，否则整个 RPC 变 5003。
     *
     * <p>⚠️ 返回<b>空 List</b>而不是等长的空串 List：Go 的 nil slice 让
     * {@code if len(vals) > i} 全部不成立；等长空串虽然结果一样，但会让
     * 「Redis 挂了」与「Redis 里确实没有」在调试时无法区分。
     */
    private List<String> hmgetLenient(String key, List<String> fields) {
        try {
            List<Object> raw = redis.opsForHash().multiGet(key, new ArrayList<>(fields));
            List<String> result = new ArrayList<>(fields.size());
            if (raw != null) {
                for (Object o : raw) {
                    // Go: Hmget 对不存在的 field 返回 ""；Lettuce 返回 null —— 归一化成 ""
                    result.add(o == null ? "" : String.valueOf(o));
                }
            }
            return result;
        } catch (Exception e) {
            metrics.cache(fields.isEmpty() ? "unknown" : keyFieldType(key), "redis_error", fields.size());
            LOG.warn("redis hmget failed, degrade to full miss: key={} err={}", key, e.getMessage());
            return List.of();
        }
    }

    /** {@code HGET} 的容错封装：出错返回 {@code ""}（= miss）。 */
    private String hgetLenient(String key, String field) {
        try {
            Object v = redis.opsForHash().get(key, field);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            LOG.warn("redis hget failed, degrade to miss: key={} field={} err={}", key, field, e.getMessage());
            return "";
        }
    }

    /**
     * 能区分「读失败」与「字段不存在」的 HGET —— 仅供 {@code GetLikeState} 使用。
     *
     * <p>之所以需要它：Go 侧 {@code getlikestatelogic.go:53-56} <b>只</b>在
     * {@code err != nil} 时打 {@code redis_error}，而紧邻的 {@code if stateStr == ""}
     * 分支（字段不存在）<b>不打点</b>。这与 {@code LikeAction} 的形状<b>不同</b> ——
     * 后者（{@code likeactionlogic.go:254-266}）是 {@code err == nil && str != ""} 的 else，
     * 两种情况都打点。
     *
     * <p>{@link #hgetLenient(String, String)} 把异常吞成 {@code ""}，
     * 会让这两条路径糊在一起，因而多打一批 Go 侧不存在的点。
     */
    private HgetResult hgetLenientWithError(String key, String field) {
        try {
            Object v = redis.opsForHash().get(key, field);
            return new HgetResult(v == null ? "" : String.valueOf(v), false);
        } catch (Exception e) {
            LOG.warn("redis hget failed, degrade to miss: key={} field={} err={}", key, field, e.getMessage());
            return new HgetResult("", true);
        }
    }

    /** {@link #hgetLenientWithError} 的返回体。 */
    private record HgetResult(String value, boolean error) {
    }

    /** {@code HSET} 的容错封装：出错只记日志（回填失败不影响本次响应）。 */
    private void hsetLenient(String key, String field, String value) {
        try {
            redis.opsForHash().put(key, field, value);
        } catch (Exception e) {
            LOG.warn("redis hset failed (ignored): key={} field={} err={}", key, field, e.getMessage());
        }
    }

    /** {@code GET} 的容错封装：出错返回 {@code ""}。 */
    private String getLenient(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? "" : v;
        } catch (Exception e) {
            metrics.cache("user_total", "redis_error");
            LOG.warn("redis get failed, degrade to miss: key={} err={}", key, e.getMessage());
            return "";
        }
    }

    /** {@code SETEX} 的容错封装：出错只记日志。 */
    private void setexLenient(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            LOG.warn("redis setex failed (ignored): key={} err={}", key, e.getMessage());
        }
    }

    /** {@code EXPIRE} 的容错封装：出错只记日志。 */
    private void expireLenient(String key, Duration ttl) {
        try {
            redis.expire(key, ttl);
        } catch (Exception e) {
            LOG.warn("redis expire failed (ignored): key={} err={}", key, e.getMessage());
        }
    }

    /**
     * {@code ZREVRANGEBYSCORE … WITHSCORES LIMIT 0 n} 的容错封装。
     *
     * <p>Spring Data Redis 4.x 里带分数的变体叫 {@code reverseRangeByScoreWithScores}
     * （{@code reverseRangeByScore} 只返回 member，不含 score —— 那样就拿不到游标了）。
     *
     * <p>出错返回<b>空 List</b>而不是 null：上层判据统一写成 {@code res.isEmpty()}，
     * 免得 null 判空漏一处。
     */
    private List<ZSetOperations.TypedTuple<String>> zrevrangeLenient(String key, long maxScore, int count) {
        try {
            // Go: ZrevrangebyscoreWithScoresAndLimitCtx(ctx, key, 0, maxScore, 0, fetchLimit)
            Set<ZSetOperations.TypedTuple<String>> result =
                    redis.opsForZSet().reverseRangeByScoreWithScores(key, 0D, (double) maxScore, 0L, count);
            return result == null ? List.of() : new ArrayList<>(result);
        } catch (Exception e) {
            metrics.cache(keyFieldType(key), "redis_error");
            LOG.warn("redis zrevrangebyscore failed, degrade to miss: key={} err={}", key, e.getMessage());
            return List.of();
        }
    }

    /** {@code ZADD} 的容错封装：出错只记日志（Go 同样只记日志 + 计 redis_error）。 */
    private void zaddLenient(String key, double score, String member) {
        try {
            redis.opsForZSet().add(key, member, score);
        } catch (Exception e) {
            metrics.cache(keyFieldType(key), "redis_error");
            LOG.warn("redis zadd failed (ignored): key={} member={} err={}", key, member, e.getMessage());
        }
    }

    /**
     * 读取点赞数 / 点踩数并解析。
     *
     * <p>Go 的两处细节（{@code internal/logic/likeactionlogic.go:254-266} 与 {@code :268-282}）：
     * <ul>
     *   <li>命中条件是 {@code err == nil && str != ""}，<b>else 分支</b>（即
     *       <b>读出错 <i>或</i> 字段不存在</b>）会：{@code span.RecordError(err)} +
     *       {@code LikeActionCount.WithLabelValues(in.TargetType, actionStr, "redis_read_error").Inc()}
     *       + 记一条 {@code ErrorRedisSelect} 日志；</li>
     *   <li>解析失败 ⇒ 同样保持 0，记 {@code ErrorTypeTransfer} 日志（<b>不打点</b>）。</li>
     * </ul>
     * <b>两种情况都不影响响应</b>：返回 0，而不是报错。
     *
     * <p>⚠️ 2026-10-23 修正 DRIFT-LIKE-009 的两处 label 漂移：
     * <ol>
     *   <li>第 1 个 label 此前传的是<b>字面量</b> {@code "like_count"} / {@code "dislike_count"}，
     *       Go 侧传的是<b>真实</b> {@code in.TargetType} ⇒ 同一指标被拆成两条时间序列；</li>
     *   <li>第 2 个 label 此前恒为 {@code "n/a"}，Go 侧传的是真实 {@code actionStr}。</li>
     * </ol>
     * ⇒ 现在三个 label 与 Go 逐条一致：{@code (targetType, actionStr, "redis_read_error")}。
     * ⚠️ Go 的两次 Hget（like / dislike）<b>用同一组 label</b>，字段缺失时各 {@code +1}；
     * 改动后 Java 也是这个形状。
     */
    private long hgetCountLenient(String hashKey, String field, String metricTargetType, String actionStr) {
        String raw;
        try {
            Object v = redis.opsForHash().get(hashKey, field);
            raw = v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            metrics.action(metricTargetType, actionStr, "redis_read_error");
            LOG.warn("redis read count failed: key={} field={} err={}", hashKey, field, e.getMessage());
            return 0L;
        }
        if (raw.isEmpty()) {
            // Go: err == nil && str == "" 也落 else 分支 ⇒ 同样打点 + 记日志
            metrics.action(metricTargetType, actionStr, "redis_read_error");
            LOG.warn("redis count field missing: key={} field={}", hashKey, field);
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            // Go: strconv.ParseInt 出错被忽略，计数保持 0（该分支不打点）
            LOG.warn("redis count value is not a number: key={} field={} raw={}", hashKey, field, raw);
            return 0L;
        }
    }

    // ==================================================================================
    // 工具方法
    // ==================================================================================

    /**
     * 对应 Go {@code strconv.ParseInt(s, 10, 64)} <b>忽略 error</b> 的写法。
     *
     * <p>Go 侧所有解析都是 {@code x, _ := strconv.ParseInt(...)}，出错即取 0。
     * 这里显式返回 0 而不是抛异常，避免把「脏数据」升级成「请求失败」。
     *
     * <p>⚠️ 2026-10-23 修正（DRIFT-LIKE-010）：原实现写 {@code Long.parseLong(raw.trim())}，
     * 即<b>容忍首尾空白</b>；Go 的 {@code strconv.ParseInt} <b>不忽略任何空白</b>
     * （{@code ParseInt(" 12 ", 10, 64)} 返回 {@code ErrSyntax} ⇒ 取 0）。
     * ⇒ 已去掉 {@code .trim()}，让「带空白的 id」在两侧都退化成 0。
     */
    private static long parseLongOrZero(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Go {@code limit <= 0 || limit > 100 ⇒ 10}。 */
    private static int normalizePageSize(int pageSize) {
        return (pageSize <= 0 || pageSize > PAGE_SIZE_MAX) ? PAGE_SIZE_DEFAULT : pageSize;
    }

    /** 对应 Go {@code getActionString}：仅用于 metrics label 与日志。 */
    private static String actionName(int actionTypeValue) {
        return switch (actionTypeValue) {
            case 1 -> "like";
            case 2 -> "unlike";
            case 3 -> "dislike";
            case 4 -> "undislike";
            default -> ACTION_UNKNOWN;
        };
    }

    /**
     * ZSet 的 score 在 Java 侧是 {@code double}，而游标是 {@code long} 位的整数。
     *
     * <p>⚠️ Go 侧 score 本身就是 {@code float64}（Redis 的 ZSet score 就是 double），
     * 只是因为 {@code timestamp << 22} 的值远小于 2^53 才没丢精度。这里同样直接转 longBits。
     */
    private static long toLongBits(Double score) {
        return score == null ? 0L : (long) (double) score;
    }

    /**
     * {@code OffsetDateTime} → Unix <b>毫秒</b>。
     *
     * <p>⚠️ 2026-10-23 修正：原方法叫 {@code epochSeconds}、返回<b>秒</b>，注释称
     * 「GORM 取的是 {@code data.Unix()} ⇒ 秒」。<b>该推理链的前提不成立</b>：
     * {@code UserLikeListResult.CreateTime}（{@code internal/model/like.go:114-119}）
     * 的 gorm DBName 是 {@code create_time}（{@code toDBName("CreateTime")}），
     * 而 SQL 取的是 {@code created_at}（{@code like.go:130}）⇒ <b>字段根本匹配不上</b>，
     * {@code CreateTime} 恒为 0 ⇒ {@code getuserlikelistlogic.go:94-96} 的
     * {@code if timestamp == 0 { continue }} <b>每行都命中</b>
     * ⇒ Go 侧这条回源分支<b>从未执行过 ZADD、从未产出过任何元素</b>（DRIFT-LIKE-001 的真实成因）。
     *
     * <p>⇒ 「Go 回源产出秒」这段行为<b>不存在</b>，无从对齐；可对齐的是
     * Go 的<b>命中分支</b>（{@code getuserlikelistlogic.go:70-77}）：
     * {@code realTimestamp := score >> 22}，而 score 来自 {@code UnixMilli << 22}
     * ⇒ {@code pb.LikeRecordItem.timestamp} 的单位是<b>毫秒</b>。
     *
     * <p>⇒ 本方法改为毫秒，使 Java 的回源分支与命中分支（以及 Redis ZSet 里写路径写入的
     * score）<b>共用同一刻度</b>；否则同一条记录在冷缓存/热缓存下会返回差 1000 倍的 timestamp。
     */
    private static long epochMillis(OffsetDateTime value) {
        return value == null ? 0L : value.toInstant().toEpochMilli();
    }

    /**
     * 从 key 前缀推断 metrics 的 {@code target_type} label。
     *
     * <p>Go 的 metrics label 是 {@code in.TargetType}（请求里的值），而回填/读取时手里只有 key。
     * 为避免「同一个逻辑用两套 label 值」（会让 Prometheus 查询漏数据），
     * 这里从 key 的第二段反解 —— 对 {@code like_count:article} 得到 {@code "article"}，
     * 与 Go 的 label 完全一致。
     */
    private static String keyFieldType(String key) {
        int idx = key.indexOf(':');
        if (idx < 0 || idx + 1 >= key.length()) {
            return "unknown";
        }
        String rest = key.substring(idx + 1);
        int second = rest.indexOf(':');
        return second < 0 ? rest : rest.substring(0, second);
    }

    // ==================================================================================
    // Kafka 消息发布（独立成 Bean 以便 LikeAction 的 @Transactional/代理边界干净）
    // ==================================================================================

    /**
     * 点赞事件发布器。
     *
     * <p><b>为什么要独立成 Bean</b>：本域没有 @Transactional 的自调用问题（点赞路径无事务），
     * 抽出纯粹是为了让 {@link LikeDomainService} 的构造依赖保持在「读缓存 / 读库 / 发消息」三类里可辨认。
     *
     * <p><b>⚠️ 失败语义</b>：Go {@code KafkaPusher.Push} 出错只 {@code LogBusinessErr(ErrorKafkaPush)}，
     * <b>不返回错误</b>。本类返回枚举仅供 metrics 打点用，调用方<b>不</b>据此改变行为。
     */
    @org.springframework.stereotype.Component("likeMsgPublisher")
    public static class KafkaLikeMsgPublisher {

        private final org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;
        private final LikeKafkaTopics topics;
        private final ObjectMapper objectMapper;

        public KafkaLikeMsgPublisher(org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate,
                                     LikeKafkaTopics topics,
                                     ObjectMapper objectMapper) {
            this.kafkaTemplate = kafkaTemplate;
            this.topics = topics;
            this.objectMapper = objectMapper;
        }

        /**
         * 推送结果 —— 对应 Go {@code likeactionlogic.go:234-250} 的<b>三条独立</b>指标分支。
         *
         * <p>⚠️ 此前 {@code publish} 只返回 {@code boolean}，把「序列化失败」与「发送失败」
         * 合并成一个 {@code kafka_error}，而 Go 是分开的（DRIFT-LIKE-009 第 4 项）。
         */
        public enum PublishOutcome {
            /** Go {@code :248} {@code success}。 */
            SUCCESS,
            /** Go {@code :236} {@code json_error} —— {@code json.Marshal} 失败，消息<b>根本没进 Kafka</b>。 */
            JSON_ERROR,
            /** Go {@code :245} {@code kafka_error} —— 序列化成功但交给 Kafka 客户端失败。 */
            KAFKA_ERROR
        }

        /**
         * @return 与 Go 三条指标分支一一对应的结果
         */
        public PublishOutcome publish(KafkaLikeMsg msg) {
            final String payload;
            try {
                payload = objectMapper.writeValueAsString(msg);
            } catch (Exception e) {
                LOG.warn("marshal like msg failed: {}", e.getMessage());
                return PublishOutcome.JSON_ERROR;
            }
            try {
                // Go: KafkaPusher.Push(ctx, string(msgBytes))
                // ⚠️ 2026-10-23 修正错误断言：原文写「**不带 key**」是错的 ——
                //    Go 的 Push 内部即 PushWithKey(ctx, strconv.FormatInt(time.Now().UnixNano(), 10), v)
                //    （go-queue@v1.2.2/kq/pusher.go:123-125）⇒ **带 key**。
                //    另：Go 的 Push 默认挂 ChunkExecutor ⇒ 只入队即返回，写失败在 executor 里 logx.Error。
                //    Java 侧同样是异步入队，但**必须**自己挂回调，否则异步失败连日志都没有。
                final String pushKey = LikeKafkaPushKey.unixNano();
                kafkaTemplate.send(topics.getLikeTopic(), pushKey, payload)
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                LOG.error("push like kafka msg failed (async): topic={} key={} err={}",
                                        topics.getLikeTopic(), pushKey, error.getMessage());
                            }
                        });
                return PublishOutcome.SUCCESS;
            } catch (Exception e) {
                // Go: LogBusinessErr(..., ErrorKafkaPush, "Kafka消息发送失败, 丢失消息: %s")
                LOG.warn("push like kafka msg failed, message dropped: {} err={}", payload, e.getMessage());
                return PublishOutcome.KAFKA_ERROR;
            }
        }
    }
}
