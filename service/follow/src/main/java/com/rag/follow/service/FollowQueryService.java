package com.rag.follow.service;

import com.rag.common.exception.BusinessException;
import com.rag.follow.domain.service.FollowDomainService;
import com.rag.follow.dto.list.FollowListReq;
import com.rag.follow.dto.list.FollowUserListResp;
import com.rag.follow.dto.recommend.RecommendListResp;
import com.rag.follow.metrics.FollowApiMetrics;
import com.rag.proto.follow.ListReq;
import com.rag.proto.follow.RecommendResp;
import com.rag.proto.follow.UserListResp;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * <p><b>follow 域 HTTP 层「读用例组」</b> —— 4 个列表端点（follow/list · follower/list · block/list · recommendations）的编排。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/service/}
 * 下的 4 个类（{@code GetFollowListService} · {@code GetFollowerListService} ·
 * {@code GetBlockListService} · {@code GetRecommendationsService}）
 * —— 4 个方法体<b>逐条照抄</b>。
 *
 * <h2>★ 本类的拆分理由（Mapping_Table §1.3）</h2>
 * <p>B 侧 8 个单端点 Service 在 C 侧<b>没有</b>合并成 1 个，而是劈成「4 写 / 4 读」两组。
 * <b>拆分理由</b>：2 个读用例带<b>专属业务规则</b>（{@code forbidden_scope} 私密性约束 ＋
 * proto→DTO 列表转换）⇒《完全spring化的文件树》§5.4 判据 ⑤「合并后职责不模糊」<b>失败</b> ⇒ 不合并成 1 个。
 *
 * <h3>⚠️🔴 本类内部<b>四条规则不对称</b> —— 填实现时的硬约束（Mapping_Table §6.2 第 10 条）</h3>
 * <table border="1">
 *   <caption>4 个 list 端点的归属校验对照</caption>
 *   <tr><th>端点</th><th>{@code resolveUserId}（取值归一化）</th><th>{@code requireSelfScope}（归属校验）</th><th>违规码</th></tr>
 *   <tr><td>{@code /follow/list}</td><td>✔</td><td><b>✘ 刻意没有</b></td><td>—</td></tr>
 *   <tr><td>{@code /follower/list}</td><td>✔</td><td><b>✘ 刻意没有</b></td><td>—</td></tr>
 *   <tr><td>{@code /block/list}</td><td>✔</td><td>✔</td><td>5005</td></tr>
 *   <tr><td>{@code /recommendations}</td><td>✔</td><td>✔</td><td>5005</td></tr>
 * </table>
 * <p>⇒ <b>关注列表与粉丝列表是公开可查的，黑名单与推荐是私有的。</b>
 * <p>这个不对称<b>看起来像遗漏、实际是 B 侧既定行为</b>，必须原样保留：
 * 若给 4 个端点统一加上校验，就是把「可查他人关注列表」变成 5005 ⇒
 * 是<b>新增的拒绝行为</b>（会打破已有客户端）。
 * ⇒ ⇒ <b>每个方法必须靠 javadoc 显式标注「此处故意没有 scope 检查」</b>，
 * 否则后人「顺手统一」即漂移。
 *
 * <h2>⚠️ L4 落点变化：域内直连（决策 D2）</h2>
 * <p>B 侧 api 进程经 gRPC 调 rpc 进程；C 侧 api + rpc <b>合并成一个进程</b>（D1 + D7）
 * ⇒ <b>域内调用直连</b> ⇒ <b>不</b>再有 {@code api/client/FollowRpcClient}。
 *
 * <p><b>⚠️ 缺口 #5 的处置：本类是「编排」，规则唯一落在 {@link FollowDomainService}</b>
 * ⇒ 分页归一化（offset 夹紧 / limit 补默认 / limit 截断）、用户存在性校验、
 * 推荐算法全部在领域层 ⇒ ⇒ <b>本类不重复实现任何业务规则</b>。
 *
 * <p><b>⚠️ 分页参数原样透传</b>：B 侧 api <b>不</b>做 offset/limit 归一化，
 * {@code default=20 / max-limit=100} 的夹取在 rpc 侧的 {@code ListConf} 里做 ⇒
 * C 侧同样原样透传给领域层（领域层是同一个进程内的直连调用）。
 */
@Service
public class FollowQueryService {

    private static final Logger log = LoggerFactory.getLogger(FollowQueryService.class);

    /** B 侧 {@code getfollowlistlogic.go} 的 {@code const route = "/follow/v1/follow/list"}。 */
    static final String ROUTE_FOLLOW_LIST = "/follow/v1/follow/list";

    /** B 侧 {@code getfollowerlistlogic.go} 的 {@code const route = "/follow/v1/follower/list"}。 */
    static final String ROUTE_FOLLOWER_LIST = "/follow/v1/follower/list";

    /** B 侧 {@code getblocklistlogic.go} 的 {@code const route = "/follow/v1/block/list"}。 */
    static final String ROUTE_BLOCK_LIST = "/follow/v1/block/list";

    /** B 侧 {@code getrecommendationslogic.go} 的 {@code const route = "/follow/v1/recommendations"}。 */
    static final String ROUTE_RECOMMENDATIONS = "/follow/v1/recommendations";

    private final FollowDomainService domainService;
    private final FollowApiSupport support;
    private final FollowApiMetrics metrics;

    public FollowQueryService(FollowDomainService domainService,
                              FollowApiSupport support,
                              FollowApiMetrics metrics) {
        this.domainService = domainService;
        this.support = support;
        this.metrics = metrics;
    }

    // ============== 4 个列表端点 ==============

    /**
     * 关注列表 —— 对应 B 侧 {@code GetFollowListService#getFollowList}。
     *
     * <h3>⚠️ 本端点<b>没有</b>归属校验 —— 关注列表是公开可查的</h3>
     * <p>B 侧 {@code getfollowlistlogic.go} 算完 {@code queryUserID} <b>直接</b>发给 rpc，
     * 中间<b>没有</b> {@code if queryUserID != currentUserID → ErrorForbidden} 那一段。
     * ⇒ 任意登录用户都能传 {@code user_id} 查任意人的关注列表。
     * <p>这是 B 侧既定行为，<b>必须原样保留</b>：加上校验会把「查得到」变成 5005，
     * 是<b>新增的拒绝行为</b>。
     *
     * <p>⚠️ <b>此处故意没有 scope 检查</b>（与 {@code getBlockList} / {@code getRecommendations} 不同）。
     */
    public FollowUserListResp getFollowList(FollowListReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_FOLLOW_LIST, () -> {
            long currentUserId = support.requireIdentity(ROUTE_FOLLOW_LIST, httpRequest);

            // ⚠️ 用 resolveUserId（取值归一化），**不是** requireSelfScope（归属校验）—— 见方法 javadoc
            long queryUserId = support.resolveUserId(currentUserId, req.userId());

            ListReq rpcReq = ListReq.newBuilder()
                    .setUserId(queryUserId)
                    .setOffset(req.offset())
                    .setLimit(req.limit())
                    .build();
            UserListResp resp;
            try {
                resp = domainService.getFollowList(rpcReq);
            } catch (StatusRuntimeException e) {
                BusinessException mapped = support.rpcError(e);
                log.warn("getFollowList rpc failed, currentUserId={}, queryUserId={}, code={}",
                        currentUserId, queryUserId, mapped.getCode(), e);
                throw mapped;
            }
            return support.toUserListResp(resp);
        });
    }

    /**
     * 粉丝列表 —— 对应 B 侧 {@code GetFollowerListService#getFollowerList}。
     *
     * <h3>⚠️ 同样<b>没有</b>归属校验 —— 粉丝列表也是公开可查的</h3>
     * <p>B 侧 {@code getfollowerlistlogic.go} 与关注列表同款：算完 {@code queryUserID} 直接发 rpc。
     * 与黑名单 / 推荐的「私有」两个端点相对。
     *
     * <p>⚠️ <b>方向差异只在领域方法名上</b>：本端点取的是「谁关注了我」，
     * 而 {@link #getFollowList} 取的是「我关注了谁」。
     * 两者的 proto 请求/响应类型<b>完全相同</b>，但<b>不能因此共用同一个方法</b>
     * —— route 常量与领域方法都不同，埋点 label 会串。
     *
     * <p>⚠️ <b>此处故意没有 scope 检查</b>。
     */
    public FollowUserListResp getFollowerList(FollowListReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_FOLLOWER_LIST, () -> {
            long currentUserId = support.requireIdentity(ROUTE_FOLLOWER_LIST, httpRequest);

            // ⚠️ 无归属校验，与 B 侧一致（见方法 javadoc）
            long queryUserId = support.resolveUserId(currentUserId, req.userId());

            ListReq rpcReq = ListReq.newBuilder()
                    .setUserId(queryUserId)
                    .setOffset(req.offset())
                    .setLimit(req.limit())
                    .build();
            UserListResp resp;
            try {
                resp = domainService.getFollowerList(rpcReq);
            } catch (StatusRuntimeException e) {
                BusinessException mapped = support.rpcError(e);
                log.warn("getFollowerList rpc failed, currentUserId={}, queryUserId={}, code={}",
                        currentUserId, queryUserId, mapped.getCode(), e);
                throw mapped;
            }
            return support.toUserListResp(resp);
        });
    }

    /**
     * 黑名单列表 —— 对应 B 侧 {@code GetBlockListService#getBlockList}。
     *
     * <h3>⚠️ 本端点<b>有</b>归属校验 —— 黑名单是私有的</h3>
     * <p>这是本域 4 个 list 端点里<b>两个</b>做归属校验的之一
     * （另一个是 {@link #getRecommendations}）。
     * <p>违规时的出口：<b>HTTP 200</b> + {@code {"code":5005,"msg":"forbidden","data":null}}
     * —— <b>不是 403</b>（B 侧注册了错误处理器 ⇒ 一切业务错误恒 200）。
     */
    public FollowUserListResp getBlockList(FollowListReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_BLOCK_LIST, () -> {
            long currentUserId = support.requireIdentity(ROUTE_BLOCK_LIST, httpRequest);

            // ⚠️ 归属校验（5005 + forbidden_scope 埋点），与 getFollowList 的关键差异
            long queryUserId = support.requireSelfScope(ROUTE_BLOCK_LIST, currentUserId, req.userId());

            ListReq rpcReq = ListReq.newBuilder()
                    .setUserId(queryUserId)
                    .setOffset(req.offset())
                    .setLimit(req.limit())
                    .build();
            UserListResp resp;
            try {
                resp = domainService.getBlockList(rpcReq);
            } catch (StatusRuntimeException e) {
                BusinessException mapped = support.rpcError(e);
                log.warn("getBlockList rpc failed, currentUserId={}, queryUserId={}, code={}",
                        currentUserId, queryUserId, mapped.getCode(), e);
                throw mapped;
            }
            return support.toUserListResp(resp);
        });
    }

    /**
     * 推荐列表 —— 对应 B 侧 {@code GetRecommendationsService#getRecommendations}。
     *
     * <h3>⚠️ 本端点<b>有</b>归属校验 —— 推荐是私有的</h3>
     * <p>本域 4 个 list 端点里做归属校验的两个之一（另一个是 {@link #getBlockList}）。
     * 理由与 B 侧一致：推荐结果基于「我的关注图」计算，替他算没有意义且泄漏社交图。
     * <p>违规出口：<b>HTTP 200</b> + {@code {"code":5005,"msg":"forbidden","data":null}}。
     *
     * <p><b>⚠️ 出参 {@code target_id} 是 JSON 字符串</b>（B 侧 tag 带 {@code ,string}），
     * 而 {@code user_ids} 是 JSON 数字 —— 详见 {@code RecommendUser} 的注释。
     * <p><b>⚠️ 空结果输出 {@code "users":[]}</b>（恒非 null）。
     *
     * <p><b>推荐算法本身（层数 / 扇出 / 互相关度）全部在领域层</b>，
     * api 层只做身份、归属校验与字段搬运。
     */
    public RecommendListResp getRecommendations(FollowListReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_RECOMMENDATIONS, () -> {
            long currentUserId = support.requireIdentity(ROUTE_RECOMMENDATIONS, httpRequest);

            // ⚠️ 归属校验（5005 + forbidden_scope 埋点）
            long queryUserId = support.requireSelfScope(ROUTE_RECOMMENDATIONS, currentUserId, req.userId());

            ListReq rpcReq = ListReq.newBuilder()
                    .setUserId(queryUserId)
                    .setOffset(req.offset())
                    .setLimit(req.limit())
                    .build();
            RecommendResp resp;
            try {
                resp = domainService.getRecommendations(rpcReq);
            } catch (StatusRuntimeException e) {
                BusinessException mapped = support.rpcError(e);
                log.warn("getRecommendations rpc failed, userId={}, code={}",
                        queryUserId, mapped.getCode(), e);
                throw mapped;
            }
            return support.toRecommendListResp(resp);
        });
    }

    /**
     * 包裹一次 logic 调用，落地 B 侧 {@code defer metrics.ObserveRequest(route, started, err)} 的计时与成败计数。
     *
     * <p><b>⚠️ fail 判定与 B 侧的具名返回值等价</b>：B 侧判的是 logic 方法<b>返回的 err 是否为 nil</b>，
     * 所以「返回了业务失败码」也是 {@code result="fail"}。
     * 本实现用 try/finally + 布尔标志表达同一时序。
     */
    private <T> T invoke(String route, Supplier<T> body) {
        long started = System.nanoTime();
        boolean failed = true;
        try {
            T result = body.get();
            failed = false;
            return result;
        } finally {
            metrics.observeRequest(route, failed, System.nanoTime() - started);
        }
    }
}
