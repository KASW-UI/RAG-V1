package com.rag.follow.service;

import com.rag.common.exception.BusinessException;
import com.rag.follow.domain.service.FollowDomainService;
import com.rag.follow.dto.relation.RelationActionReq;
import com.rag.follow.dto.relation.RelationActionResp;
import com.rag.follow.metrics.FollowApiMetrics;
import com.rag.proto.follow.BaseResp;
import com.rag.proto.follow.RelationReq;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <p><b>follow 域 HTTP 层「写用例组」</b> —— 4 个动作端点（follow / unfollow / block / unblock）的编排。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/service/}
 * 下的 4 个类（{@code FollowService} · {@code UnfollowService} · {@code BlockService} · {@code UnblockService}）
 * —— 4 个方法体<b>逐条照抄</b>。
 *
 * <h2>⚠️ L4 落点变化：域内直连（决策 D2）</h2>
 * <p>B 侧 api 进程经 gRPC 调 rpc 进程（{@code FollowRpcClient}）；C 侧按 <b>D1 + D7</b> 把 api + rpc
 * <b>合并成一个进程</b> ⇒ <b>域内调用直连</b> ⇒ <b>不</b>再有 {@code api/client/FollowRpcClient}。
 *
 * <p><b>⚠️ 缺口 #5 的处置：本类是「编排」，规则唯一落在 {@link FollowDomainService}</b>
 * （Mapping_Table §6.3 缺口 #5 指出「api 层 8 个用例与 rpc 层 8 个 gRPC 用例方法名集合完全相同
 * ⇒ 业务规则可能落两处」）。
 * ⇒ 本类<b>只做三件事</b>：① 解析身份（委托 {@link FollowApiSupport}）② 构造 proto 请求并直连
 * ③ 把领域结果投影成 DTO。
 * ⇒ <b>校验、闸门、错误码全部在 {@link FollowDomainService} 内</b>
 * （validate → ensureUserExists → ExistsAnyBlock → CreateFollow 的顺序、5201/5202/5101 的产出点）
 * ⇒ ⇒ <b>本类不重复实现任何业务规则</b>，避免双写。
 *
 * <p><b>语义等价论证</b>：
 * <ul>
 *   <li>B 侧 api 层的 4 个 logic 的主体是「取 userID → 调 rpc → 判 code → 返回 success:true」；
 *       C 侧的领域服务<b>仍然抛同一类型的</b> {@code StatusRuntimeException}（io.grpc）
 *       ⇒ ⇒ <b>错误映射逐字保留</b>，不需要任何改写。</li>
 *   <li>⚠️ <b>唯一不可达的一支</b>：B 侧 api 层捕获「客户端熔断打开」
 *       （{@code GoStyleCircuitOpenException}）并映射成 1015；C 侧 api → 领域是<b>同进程直连</b>
 *       ⇒ <b>没有客户端熔断</b> ⇒ 该分支不可达 ⇒ <b>不保留</b>
 *       （保留一个永远不命中的 catch = §3.4.1 D-2「零消费者的代码」），
 *       <b>登记为本域的 L4 后果</b>。</li>
 * </ul>
 *
 * <p><b>⚠️ 4 个动作端点「逐字同质」</b>：请求形状相同、响应形状相同、成功出口相同
 * （{@code RelationActionResp.ok()} ⇒ {@code success} 恒 true），
 * 只有<b>调用的领域方法</b>与 <b>{@code route} 常量</b>不同。
 * ⇒ <b>⚠️ 4 个 route 字面量必须逐条落在对应方法上</b>（Mapping_Table §6.2 第 9 条的硬约束）
 * ⇒ 合并成一个类后，<b>每个方法仍需自带 route 常量</b>，否则埋点 label 会串。
 */
@Service
public class FollowRelationService {

    private static final Logger log = LoggerFactory.getLogger(FollowRelationService.class);

    /** B 侧 {@code followlogic.go} 的 {@code const route = "/follow/v1/follow"}。 */
    static final String ROUTE_FOLLOW = "/follow/v1/follow";

    /** B 侧 {@code unfollowlogic.go} 的 {@code const route = "/follow/v1/unfollow"}。 */
    static final String ROUTE_UNFOLLOW = "/follow/v1/unfollow";

    /** B 侧 {@code blocklogic.go} 的 {@code const route = "/follow/v1/block"}。 */
    static final String ROUTE_BLOCK = "/follow/v1/block";

    /** B 侧 {@code unblocklogic.go} 的 {@code const route = "/follow/v1/unblock"}。 */
    static final String ROUTE_UNBLOCK = "/follow/v1/unblock";

    private final FollowDomainService domainService;
    private final FollowApiSupport support;
    private final FollowApiMetrics metrics;

    public FollowRelationService(FollowDomainService domainService,
                                 FollowApiSupport support,
                                 FollowApiMetrics metrics) {
        this.domainService = domainService;
        this.support = support;
        this.metrics = metrics;
    }

    // ============== 4 个动作端点 ==============

    /**
     * 关注用户 —— 对应 B 侧 {@code FollowService#follow}（{@code followlogic.go}）。
     *
     * <p><b>⚠️ 本端点没有归属校验</b>：{@code target_id} 可以是任意值（自己的 id 也行）。
     * B 侧的自关注 / 重复关注 / 被拉黑等判定<b>全部在领域层</b>
     * （5201 自关系 / 重复关注静默成功 / 5202 已拉黑）。
     * api 层提前加校验会把业务码换成 5003/5001 ⇒ 那是<b>新增行为</b>。
     *
     * <p><b>⚠️ 重复关注返回 SUCCESS</b>（不是 5006 already exists）——
     * 那是 {@code uk_follow_pair} + {@code ON CONFLICT DO NOTHING} 的组合语义，在领域层。
     */
    public RelationActionResp follow(RelationActionReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_FOLLOW, () -> {
            long userId = support.requireIdentity(ROUTE_FOLLOW, httpRequest);
            RelationReq rpcReq = RelationReq.newBuilder()
                    .setUserId(userId)
                    .setTargetId(req.targetId())
                    .build();
            BaseResp resp;
            try {
                resp = domainService.follow(rpcReq);
            } catch (StatusRuntimeException e) {
                // Go: code := codeFromRPCError(rpcErr) → BizCodeFromError（文案先于 gRPC code）
                BusinessException mapped = support.rpcError(e);
                log.warn("follow rpc failed, userId={}, code={}", userId, mapped.getCode(), e);
                throw mapped;
            }
            support.requireRpcSuccess(resp.getCode(), resp.getMsg());
            return RelationActionResp.ok();
        });
    }

    /**
     * 取关 —— 对应 B 侧 {@code UnfollowService#unfollow}（{@code unfollowlogic.go}）。
     *
     * <p><b>⚠️ 取关「不存在的关系」不是错误</b>：领域层<b>不</b>校验用户存在性、
     * 删 0 行也照样 SUCCESS（见 {@link FollowDomainService} 的类注释）。
     * api 层不预判、不补偿。
     */
    public RelationActionResp unfollow(RelationActionReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_UNFOLLOW, () -> {
            long userId = support.requireIdentity(ROUTE_UNFOLLOW, httpRequest);
            RelationReq rpcReq = RelationReq.newBuilder()
                    .setUserId(userId)
                    .setTargetId(req.targetId())
                    .build();
            BaseResp resp;
            try {
                resp = domainService.unfollow(rpcReq);
            } catch (StatusRuntimeException e) {
                BusinessException mapped = support.rpcError(e);
                log.warn("unfollow rpc failed, userId={}, code={}", userId, mapped.getCode(), e);
                throw mapped;
            }
            support.requireRpcSuccess(resp.getCode(), resp.getMsg());
            return RelationActionResp.ok();
        });
    }

    /**
     * 拉黑 —— 对应 B 侧 {@code BlockService#block}（{@code blocklogic.go}）。
     *
     * <p><b>⚠️ Block 不检查「是否已被对方拉黑」</b>（在领域层）⇒
     * A 拉黑 B 时即使 B 已拉黑 A，两个 block 行都会存在。B 侧的既定行为，照搬。
     */
    public RelationActionResp block(RelationActionReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_BLOCK, () -> {
            long userId = support.requireIdentity(ROUTE_BLOCK, httpRequest);
            RelationReq rpcReq = RelationReq.newBuilder()
                    .setUserId(userId)
                    .setTargetId(req.targetId())
                    .build();
            BaseResp resp;
            try {
                resp = domainService.block(rpcReq);
            } catch (StatusRuntimeException e) {
                BusinessException mapped = support.rpcError(e);
                log.warn("block rpc failed, userId={}, code={}", userId, mapped.getCode(), e);
                throw mapped;
            }
            support.requireRpcSuccess(resp.getCode(), resp.getMsg());
            return RelationActionResp.ok();
        });
    }

    /**
     * 解拉黑 —— 对应 B 侧 {@code UnblockService#unblock}（{@code unblocklogic.go}）。
     *
     * <p><b>⚠️ 同样不校验用户存在性</b>（与 unfollow 同款，在领域层）。
     */
    public RelationActionResp unblock(RelationActionReq req, HttpServletRequest httpRequest) {
        return invoke(ROUTE_UNBLOCK, () -> {
            long userId = support.requireIdentity(ROUTE_UNBLOCK, httpRequest);
            RelationReq rpcReq = RelationReq.newBuilder()
                    .setUserId(userId)
                    .setTargetId(req.targetId())
                    .build();
            BaseResp resp;
            try {
                resp = domainService.unblock(rpcReq);
            } catch (StatusRuntimeException e) {
                BusinessException mapped = support.rpcError(e);
                log.warn("unblock rpc failed, userId={}, code={}", userId, mapped.getCode(), e);
                throw mapped;
            }
            support.requireRpcSuccess(resp.getCode(), resp.getMsg());
            return RelationActionResp.ok();
        });
    }

    /**
     * 包裹一次 logic 调用，落地 B 侧 {@code defer metrics.ObserveRequest(route, started, err)} 的计时与成败计数。
     *
     * <p><b>⚠️ fail 判定与 B 侧的具名返回值等价</b>：B 侧判的是 logic 方法<b>返回的 err 是否为 nil</b>，
     * 所以「返回了业务失败码」也是 {@code result="fail"}。
     * 本实现用 try/finally + 布尔标志表达同一时序：
     * 成功时置 {@code false}，任何异常路径（业务异常 / 领域异常 / NPE）都保持 {@code true}。
     *
     * <p><b>⚠️ reject 埋点在身份解析失败时补一条</b>（{@code user_id_missing}），
     * 顺序与 B 侧一致：<b>先</b>记 reject，<b>后</b>返回 error。
     */
    private <T> T invoke(String route, java.util.function.Supplier<T> body) {
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
