package com.rag.follow.domain.service;

import com.rag.common.logger.LogOption;
import com.rag.common.logger.LoggerFactoryBean;
import com.rag.follow.config.FollowRpcProperties;
import com.rag.follow.contract.ErrCodes;
import com.rag.follow.contract.GrpcErrorMapper;
import com.rag.follow.mapper.BlockRelationMapper;
import com.rag.follow.mapper.FollowRelationMapper;
import com.rag.follow.mapper.GoStyleLimitOffset;
import com.rag.follow.metrics.FollowRpcMetrics;
import com.rag.follow.service.FollowBlockTransactionService;
import com.rag.follow.service.FollowRecommendationService;
import com.rag.follow.service.FollowRpcSupport;
import com.rag.follow.service.FollowUserExistenceService;
import com.rag.proto.follow.BaseResp;
import com.rag.proto.follow.ListReq;
import com.rag.proto.follow.RecommendResp;
import com.rag.proto.follow.RelationReq;
import com.rag.proto.follow.UserListResp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.context.Context;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * <p><b>follow 域 8 个 gRPC 用例的业务编排</b> —— 逐条照抄 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/service/FollowDomainService.java}
 * （B 侧 507 行，本类逐方法对应，行号见各方法 javadoc）。
 *
 * <h2>⚠️ L4 表达层差异（包压平 · D7）</h2>
 * <table border="1">
 *   <tr><th>B 侧</th><th>C 侧</th><th>依据</th></tr>
 *   <tr><td>{@code com.rag.follow.follow.rpc.service.FollowDomainService}</td>
 *       <td>{@code com.rag.follow.domain.service.FollowDomainService}</td>
 *       <td>映射表 {@code docs/Mapping_Table/follow/follow.md} 第 150 / 151 行
 *           —— 「rpc 用例层落到域内 {@code domain/service/}」</td></tr>
 *   <tr><td>{@code com.rag.follow.follow.rpc.service.FollowRpcSupport} 等</td>
 *       <td>{@code com.rag.follow.service.*}</td>
 *       <td>映射表第 149 / 157 行</td></tr>
 *   <tr><td>{@code com.rag.follow.follow.common.errmsg.*}</td>
 *       <td>{@code com.rag.follow.contract.*}</td>
 *       <td>域内错误契约（码值 / 文案逐条一致）</td></tr>
 * </table>
 *
 * <h2>⚠️ 三条最容易被「统一风格」抹平的语义</h2>
 * <ol>
 *   <li><b>Unfollow / Unblock 不校验用户存在性</b>。
 *       两个创建操作（Follow / Block）会先 {@code ensureUserExists(targetId)}，
 *       两个删除操作<b>不</b>。所以「关注一个不存在的人」被拒，
 *       而「取消关注一个不存在的人」<b>照样成功</b>（删 0 行）。
 *       ⚠️ 这在直觉上像 bug，但它是 B 侧的实际行为 ⇒ 照搬，不要补。</li>
 *   <li><b>只有 Follow 做拉黑检查</b>（{@code ERROR_RELATION_BLOCKED} / 5202）。
 *       Unfollow / Block / Unblock / 三个列表 / 推荐<b>都没有</b>这一步。</li>
 *   <li><b>指标口径</b>：4 个写方法上报 <b>两个</b>指标（{@code observeRpc} + {@code observeRelation}），
 *       4 个读方法只上报 {@code observeRpc}。B 侧每个用例的 defer 明确区分，
 *       这里用 try/catch/finally 复刻。</li>
 * </ol>
 *
 * <h2>指标与日志的对应实现（命名返回值 + defer ⇒ try/catch/finally）</h2>
 * <p>B 侧用「命名返回值 + defer」实现：defer 里读 {@code err} 判断成功/失败。
 * Java 没有命名返回值，等价形态是 <b>{@code catch 里赋值 + finally 上报}</b>：
 * 正常返回时 {@code failure} 保持 null，异常时先记下再重抛。语义与 B 侧一致。
 *
 * <p>⚠️ <b>method 标签首字母大写、action 标签全小写</b>：
 * {@code observeRpc} 用 {@code "Follow" / "Unfollow" / "Block" / "Unblock" /
 * "GetFollowList" / "GetBlockList" / "GetFollowerList" / "GetRecommendations"}；
 * {@code observeRelation} 用 {@code "follow" / "unfollow" / "block" / "unblock"}。
 * ⇒ <b>两个标签的取值集合不同，别抄串</b>。
 */
@Service
public class FollowDomainService {

    private final FollowRelationMapper followRelationMapper;
    private final BlockRelationMapper blockRelationMapper;
    private final FollowRpcSupport support;
    private final FollowUserExistenceService userExistence;
    private final FollowBlockTransactionService blockTransaction;
    private final FollowRecommendationService recommendationService;
    private final FollowRpcProperties properties;
    private final FollowRpcMetrics metrics;
    private final LoggerFactoryBean logger;

    public FollowDomainService(FollowRelationMapper followRelationMapper,
                                BlockRelationMapper blockRelationMapper,
                                FollowRpcSupport support,
                                FollowUserExistenceService userExistence,
                                FollowBlockTransactionService blockTransaction,
                                FollowRecommendationService recommendationService,
                                FollowRpcProperties properties,
                                FollowRpcMetrics metrics,
                                LoggerFactoryBean logger) {
        this.followRelationMapper = followRelationMapper;
        this.blockRelationMapper = blockRelationMapper;
        this.support = support;
        this.userExistence = userExistence;
        this.blockTransaction = blockTransaction;
        this.recommendationService = recommendationService;
        this.properties = properties;
        this.metrics = metrics;
        this.logger = logger;
    }

    // =============================================================================
    // 4 个写方法
    // =============================================================================

    /**
     * 对应 B 侧 {@code FollowDomainService.java:114-156} 的 {@code follow}。
     *
     * <p>执行顺序<b>严格照 B 侧</b>，每一步都是一道闸门：
     * <ol>
     *   <li>validateRelationReq → 5003 / 5201</li>
     *   <li>ensureUserExists(<b>targetId</b>) → 5101 / 5001</li>
     *   <li>ExistsAnyBlock → DB 错 5007；<b>已拉黑 ⇒ 5202</b></li>
     *   <li>CreateFollow（ON CONFLICT DO NOTHING）→ DB 错 5008</li>
     * </ol>
     *
     * <p><b>⚠️ 重复关注返回 SUCCESS</b>（第 4 步插入 0 行、无异常），
     * 这是唯一键 + {@code DO NOTHING} 的组合语义，
     * <b>不是</b> 5006 already exists。
     */
    public BaseResp follow(RelationReq in) {
        long startedAtNanos = System.nanoTime();
        Throwable failure = null;
        try {
            long userId = in.getUserId();
            long targetId = in.getTargetId();

            try {
                support.validateRelationReq(in);
            } catch (RuntimeException e) {
                logBusinessErr(e, userId);
                throw e;
            }

            try {
                userExistence.ensureUserExists(targetId);
            } catch (RuntimeException e) {
                logBusinessErr(e, userId);
                throw e;
            }

            long blockCount = dbSelect("follow", "exists_any_block",
                    () -> blockRelationMapper.countAnyBlock(userId, targetId), userId);
            if (blockCount > 0) {
                StatusRuntimeException denied = FollowRpcSupport.rpcError(
                        Status.Code.PERMISSION_DENIED, ErrCodes.ERROR_RELATION_BLOCKED);
                logBusinessErr(denied, ErrCodes.ERROR_RELATION_BLOCKED, userId);
                throw denied;
            }

            dbUpdate("follow", "create_follow",
                    () -> insertFollow(userId, targetId), userId);

            logInfo(userId, "follow relation updated");
            return support.successBaseResp();
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            metrics.observeRpc("Follow", startedAtNanos, failure);
            metrics.observeRelation("follow", failure);
        }
    }

    /**
     * 对应 B 侧 {@code FollowDomainService.java:163-189} 的 {@code unfollow}。
     *
     * <p><b>⚠️ 不调 ensureUserExists</b>（见类注释第 1 条）；删 0 行照样 SUCCESS。
     */
    public BaseResp unfollow(RelationReq in) {
        long startedAtNanos = System.nanoTime();
        Throwable failure = null;
        try {
            long userId = in.getUserId();
            long targetId = in.getTargetId();

            try {
                support.validateRelationReq(in);
            } catch (RuntimeException e) {
                logBusinessErr(e, userId);
                throw e;
            }

            dbUpdate("unfollow", "delete_follow",
                    () -> followRelationMapper.deleteByPair(userId, targetId), userId);

            logInfo(userId, "follow relation removed");
            return support.successBaseResp();
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            metrics.observeRpc("Unfollow", startedAtNanos, failure);
            metrics.observeRelation("unfollow", failure);
        }
    }

    /**
     * 对应 B 侧 {@code FollowDomainService.java:198-231} 的 {@code block}。
     *
     * <p><b>⚠️ Block 不检查「是否已被对方拉黑」</b>——它直接建立拉黑关系。
     * 也就是说 A 拉黑 B 时，即使 B 已经拉黑了 A，两个 block 行都会存在。
     * 这是 B 侧的实际行为（{@code ExistsAnyBlock} 只在 Follow 路径调用），照搬。</p>
     *
     * <p><b>⚠️ 事务边界在 {@link FollowBlockTransactionService} 里</b>（独立 Bean）：
     * 异常先逃出 {@code @Transactional} 方法边界 ⇒ Spring 先完成回滚
     * ⇒ 才回到这里被翻译成 5008。<b>顺序不能倒</b>。
     */
    public BaseResp block(RelationReq in) {
        long startedAtNanos = System.nanoTime();
        Throwable failure = null;
        try {
            long userId = in.getUserId();
            long targetId = in.getTargetId();

            try {
                support.validateRelationReq(in);
            } catch (RuntimeException e) {
                logBusinessErr(e, userId);
                throw e;
            }

            try {
                userExistence.ensureUserExists(targetId);
            } catch (RuntimeException e) {
                logBusinessErr(e, userId);
                throw e;
            }

            dbUpdate("block", "create_block_and_cleanup",
                    () -> blockTransaction.createBlockAndCleanup(userId, targetId), userId);

            logInfo(userId, "block relation updated");
            return support.successBaseResp();
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            metrics.observeRpc("Block", startedAtNanos, failure);
            metrics.observeRelation("block", failure);
        }
    }

    /**
     * 对应 B 侧 {@code FollowDomainService.java:238-264} 的 {@code unblock}。
     *
     * <p><b>⚠️ 不调 ensureUserExists</b>；解一个不存在的拉黑照样 SUCCESS。</p>
     */
    public BaseResp unblock(RelationReq in) {
        long startedAtNanos = System.nanoTime();
        Throwable failure = null;
        try {
            long userId = in.getUserId();
            long targetId = in.getTargetId();

            try {
                support.validateRelationReq(in);
            } catch (RuntimeException e) {
                logBusinessErr(e, userId);
                throw e;
            }

            dbUpdate("unblock", "delete_block",
                    () -> blockRelationMapper.deleteByPair(userId, targetId), userId);

            logInfo(userId, "block relation removed");
            return support.successBaseResp();
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            metrics.observeRpc("Unblock", startedAtNanos, failure);
            metrics.observeRelation("unblock", failure);
        }
    }

    // =============================================================================
    // 3 个列表方法（结构同构，只有归一化参数与 SQL 不同）
    // =============================================================================

    /** 对应 B 侧 {@code FollowDomainService.java:271-291} 的 {@code getFollowList}。 */
    public UserListResp getFollowList(ListReq in) {
        long startedAtNanos = System.nanoTime();
        Throwable failure = null;
        try {
            FollowRpcSupport.NormalizedListReq req = normalizeList(in);
            ensureSelf(req.userId());

            List<Long> userIds = dbSelect("get_follow_list", "list_follow_targets",
                    () -> followRelationMapper.listFollowTargets(
                            req.userId(), GoStyleLimitOffset.limitOffset(req.limit(), req.offset())),
                    req.userId());

            logInfo(req.userId(), "follow list loaded");
            return support.successUserListResp(userIds);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            metrics.observeRpc("GetFollowList", startedAtNanos, failure);
        }
    }

    /** 对应 B 侧 {@code FollowDomainService.java:294-314} 的 {@code getBlockList}。 */
    public UserListResp getBlockList(ListReq in) {
        long startedAtNanos = System.nanoTime();
        Throwable failure = null;
        try {
            FollowRpcSupport.NormalizedListReq req = normalizeList(in);
            ensureSelf(req.userId());

            List<Long> userIds = dbSelect("get_block_list", "list_block_targets",
                    () -> blockRelationMapper.listBlockTargets(
                            req.userId(), GoStyleLimitOffset.limitOffset(req.limit(), req.offset())),
                    req.userId());

            logInfo(req.userId(), "block list loaded");
            return support.successUserListResp(userIds);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            metrics.observeRpc("GetBlockList", startedAtNanos, failure);
        }
    }

    /** 对应 B 侧 {@code FollowDomainService.java:317-338} 的 {@code getFollowerList}。 */
    public UserListResp getFollowerList(ListReq in) {
        long startedAtNanos = System.nanoTime();
        Throwable failure = null;
        try {
            FollowRpcSupport.NormalizedListReq req = normalizeList(in);
            ensureSelf(req.userId());

            // ⚠️ 过滤列是 target_id、投影列是 user_id —— 与另两个列表不同，别抄错
            List<Long> userIds = dbSelect("get_follower_list", "list_follower_users",
                    () -> followRelationMapper.listFollowerUsers(
                            req.userId(), GoStyleLimitOffset.limitOffset(req.limit(), req.offset())),
                    req.userId());

            logInfo(req.userId(), "follower list loaded");
            return support.successUserListResp(userIds);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            metrics.observeRpc("GetFollowerList", startedAtNanos, failure);
        }
    }

    // =============================================================================
    // 推荐
    // =============================================================================

    /** 对应 B 侧 {@code FollowDomainService.java:345-377} 的 {@code getRecommendations}。 */
    public RecommendResp getRecommendations(ListReq in) {
        long startedAtNanos = System.nanoTime();
        Throwable failure = null;
        try {
            // ⚠️ 用 Recommend 的 (20, 50)，不是 List 的 (20, 100)
            FollowRpcSupport.NormalizedListReq req = normalizeRecommendList(in);
            ensureSelf(req.userId());

            int fanout = support.clampFanout(properties.getRecommend().getMaxFanout());
            int depth = support.clampRecommendDepth(properties.getRecommend().getMaxDepth());

            // ⚠️ 此处**不**再包一层 catch 记日志：推荐算法内部的 4 个 DB 失败点
            //    已在 FollowRecommendationService 逐点记录
            //    LogBusinessErr(ERROR_DB_SELECT, dbErr, userLogOption(userID)) 并上报指标。
            //    再包一层会打两条日志，与 B 侧的「每个失败点一条」不符。
            List<RecommendResp.RecommendUser> users = recommendationService.recommend(
                    req.userId(), req.offset(), req.limit(), fanout, depth);

            if (users == null || users.isEmpty()) {
                // B 侧在这两条路径上传 successRecommendResp(nil) 且**不**打 LogInfo
                // —— 只更新 gauge。
                return support.successRecommendResp(null);
            }

            logInfo(req.userId(), "recommendations generated");
            return support.successRecommendResp(users);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            metrics.observeRpc("GetRecommendations", startedAtNanos, failure);
        }
    }

    // =============================================================================
    // 内部：归一化 / 存在性 / DB 错误映射 / 日志
    // =============================================================================

    /** 对应 3 个列表用例开头的归一化调用（B 侧 {@code FollowDomainService.java:384-394}）。 */
    private FollowRpcSupport.NormalizedListReq normalizeList(ListReq in) {
        try {
            return support.normalizeListReq(
                    properties.getList().getDefaultLimit(), properties.getList().getMaxLimit(), in);
        } catch (RuntimeException e) {
            // ⚠️ B 侧这条日志**不带** userLogOption（三个列表用例都是裸调用），
            //    因为此时 userId 可能 <= 0，不适合当「用户」写进日志字段。逐条照搬。
            logBusinessErr(e, GrpcErrorMapper.bizCodeFromError(e), null);
            throw e;
        }
    }

    /** 推荐专用的归一化（默认/上限来自 RecommendConf 而非 ListConf）—— B 侧 397-406。 */
    private FollowRpcSupport.NormalizedListReq normalizeRecommendList(ListReq in) {
        try {
            return support.normalizeListReq(
                    properties.getRecommend().getDefaultLimit(),
                    properties.getRecommend().getMaxLimit(), in);
        } catch (RuntimeException e) {
            logBusinessErr(e, GrpcErrorMapper.bizCodeFromError(e), null);
            throw e;
        }
    }

    /** 三个列表与推荐的存在性校验 —— 注意是 <b>自己</b>，不是 target（B 侧 409-416）。 */
    private void ensureSelf(long userId) {
        try {
            userExistence.ensureUserExists(userId);
        } catch (RuntimeException e) {
            logBusinessErr(e, userId);
            throw e;
        }
    }

    /** 对应 B 侧 {@code FollowDomainService.java:419-422}：时间戳由应用进程产生（B 侧自述的 GORM tag 填充）。 */
    private int insertFollow(long userId, long targetId) {
        OffsetDateTime now = OffsetDateTime.now();
        return followRelationMapper.insertIgnoreConflict(userId, targetId, now, now);
    }

    /**
     * DB 查询失败 ⇒ 5007（{@code ERROR_DB_SELECT}）—— 对应 B 侧 {@code FollowDomainService.java:452-461}。
     *
     * <p>⚠️ 日志记的是<b>原始 DB 异常</b>（含 SQL / 连接信息），而<b>发给客户端的只有
     * {@code "database query failed"}</b> —— 内部细节绝不外泄。这条分界不能模糊。
     *
     * <p><b>⚠️ 捕获范围是 {@link RuntimeException} 而非 {@code DataAccessException}</b>：
     * B 侧写的是「任何错误都算错」 —— 覆盖<b>任何</b>错误。
     * 收窄到 Spring 的 DB 异常层级会漏掉两类真实路径，它们都会把 5007 降级成 5001：
     * <ol>
     *   <li>{@code org.apache.ibatis.exceptions.PersistenceException} 且 cause 不是
     *       {@code SQLException} 时，MyBatis-Plus 不会包装成 {@code DataAccessException}；</li>
     *   <li>{@code com.rag.common.persistence.SqlTimeoutStatusTranslator} 把 pg 的
     *       {@code 57014 query_canceled} 翻成 DEADLINE_EXCEEDED 时抛的那一类，
     *       同样<b>不是</b> {@code DataAccessException}。</li>
     * </ol>
     *
     * <p><b>安全性</b>：{@code query} 的函数体<b>只有</b> mapper 调用，
     * 不会抛本域自己的 {@code rpcError} ⇒ 不会把自己造的 5201/5101 误翻成 5007。
     */
    private <T> T dbSelect(String action, String type, Supplier<T> query, Long userId) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            metrics.observeDbError(action, type);
            logBusinessErr(e, ErrCodes.ERROR_DB_SELECT, userId);
            throw FollowRpcSupport.rpcError(Status.Code.INTERNAL, ErrCodes.ERROR_DB_SELECT);
        }
    }

    /**
     * DB 写入失败 ⇒ 5008（{@code ERROR_DB_UPDATE}）—— 对应 B 侧 {@code FollowDomainService.java:471-480}。
     * 与 {@link #dbSelect} 同构，只差业务码，捕获范围同样放宽到 {@link RuntimeException}。
     *
     * <p>⚠️ 这里覆盖的不只是 {@code createBlockAndCleanup}（其事务由
     * {@code FollowBlockTransactionService} 负责）：异常先逃出 {@code @Transactional} 方法边界
     * ⇒ Spring 先完成回滚 ⇒ 才回到这里被翻译成 5007/5008。<b>顺序不能倒</b>。
     */
    private void dbUpdate(String action, String type, Runnable write, Long userId) {
        try {
            write.run();
        } catch (RuntimeException e) {
            metrics.observeDbError(action, type);
            logBusinessErr(e, ErrCodes.ERROR_DB_UPDATE, userId);
            throw FollowRpcSupport.rpcError(Status.Code.INTERNAL, ErrCodes.ERROR_DB_UPDATE);
        }
    }

    // ---- 日志（对应 B 侧的业务错误 / 信息日志出口）----

    /**
     * 对应 B 侧 {@code FollowDomainService.java:490-492}：
     * 业务码从异常里反查（{@link GrpcErrorMapper#bizCodeFromError}）。
     *
     * <p>{@code Context.current()} 等价于 B 侧的 {@code l.ctx}：gRPC server interceptor 已把
     * OTel span 放进当前线程的 Context。
     */
    private void logBusinessErr(Throwable err, long userId) {
        logBusinessErr(err, GrpcErrorMapper.bizCodeFromError(err), userId);
    }

    /**
     * @param userId 传 {@code null} 表示<b>不带</b> user_log 字段
     *                （对应 B 侧里裸调业务错误日志的那几处）
     */
    private void logBusinessErr(Throwable err, int bizCode, Long userId) {
        LogOption[] opts = userId == null ? new LogOption[0]
                : new LogOption[]{FollowRpcSupport.userLogOptionOf(userId)};
        logger.logBusinessErr(Context.current(), bizCode, err, opts);
    }

    /** 对应 B 侧 {@code FollowDomainService.java:505-507} 的信息日志出口。 */
    private void logInfo(long userId, String message) {
        logger.logInfo(Context.current(), message, FollowRpcSupport.userLogOptionOf(userId));
    }
}
