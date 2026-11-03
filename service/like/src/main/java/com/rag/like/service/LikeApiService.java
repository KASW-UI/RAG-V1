package com.rag.like.service;

import com.rag.like.domain.service.LikeDomainService;
import com.rag.like.dto.action.LikeActionReq;
import com.rag.like.dto.action.LikeActionResp;
import com.rag.like.dto.count.GetLikeCountReq;
import com.rag.like.dto.count.GetLikeCountResp;
import com.rag.like.dto.count.GetUserTotalLikeReq;
import com.rag.like.dto.count.GetUserTotalLikeResp;
import com.rag.like.dto.count.LikeCountItem;
import com.rag.like.dto.list.GetTargetLikerListReq;
import com.rag.like.dto.list.GetTargetLikerListResp;
import com.rag.like.dto.list.GetUserLikeListReq;
import com.rag.like.dto.list.GetUserLikeListResp;
import com.rag.like.dto.list.LikeItem;
import com.rag.like.dto.list.LikeRecordItem;
import com.rag.like.dto.state.GetLikeStateReq;
import com.rag.like.dto.state.GetLikeStateResp;
import com.rag.proto.like.ActionType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p><b>like 域 6 个 HTTP 用例</b> —— 对应 B 侧
 * {@code like-api/src/main/java/com/rag/like/like/api/service/} 下的 6 个用例类
 * （{@code LikeCountService} / {@code LikeStateService} / {@code TargetLikerListService} /
 * {@code UserLikeListService} / {@code UserTotalLikeService} / {@code LikeActionService}）。
 *
 * <p><b>抄写基准</b>：B 侧 6 个类（映射表第 113 行：**N:1 · 用例内聚**，D5 判据 5 条全中）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li><b>6 个类合 1 个</b>（D5：6 个用例类同层同质，不是职责边界 ⇒ 合并）。</li>
 *       <li><b>不再经过自我 gRPC</b>（<b>D2</b>）：B 侧是
 *           {@code api 用例 → LikeRpcClient → gRPC → like-rpc 进程 → LikeDomainService}；
 *           C 侧改为<b>域内直连</b> {@link LikeDomainService} ⇒ 少一层序列化往返。</li>
 *       <li>请求/响应的<b>出参类型</b>从 protobuf 换成 DTO record（<b>A9</b>：DTO 必须 immutable record）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。6 个用例的判据、鉴权不对称（2 个公开读 / 4 个要 uid）、
 *       {@code target_ids} 的 null 元素语义、错误码与文案逐字未改。
 *       <b>唯一需要额外论证的是错误映射</b>：B 侧 api 收到的是 gRPC status 异常，
 *       C 侧直连后收到的是领域异常 ⇒ 映射点见 {@link LikeApiSupport#rpcError(Throwable)} 的
 *       {@code LikeExceptions.Rpc} 分支（与 B 侧 status 分支逐字等价）。</li>
 *   <li><b>B 出处</b>：见上 6 个类。</li>
 * </ol>
 *
 * <h2>⚠️ 鉴权不对称是 B 侧显式行为，不能抹平</h2>
 * <table border="1">
 *   <caption>6 个端点的 uid 需求</caption>
 *   <tr><th>端点</th><th>要 uid？</th><th>B 侧出处</th></tr>
 *   <tr><td>{@code getlikecount}</td><td><b>否</b>（公开读）</td>
 *       <td>{@code LikeCountService} 里<b>没有</b> {@code support.userId(...)} 调用</td></tr>
 *   <tr><td>{@code gettargetlikerlist}</td><td><b>否</b>（公开读）</td>
 *       <td>{@code TargetLikerListService} 里<b>没有</b> {@code support.userId(...)} 调用</td></tr>
 *   <tr><td>{@code getlikestate}</td><td>是</td><td>{@code LikeStateService} 第 1 行</td></tr>
 *   <tr><td>{@code getuserlikelist}</td><td>是</td><td>{@code UserLikeListService}</td></tr>
 *   <tr><td>{@code getusertotallike}</td><td>是</td><td>{@code UserTotalLikeService}</td></tr>
 *   <tr><td>{@code likeaction}</td><td>是</td><td>{@code LikeActionService}</td></tr>
 * </table>
 * ⇒ 4 个要 uid 的端点：claim 解析失败 ⇒ <b>1006</b>（不是 401）⇒ 由
 * {@link LikeApiSupport#userId(HttpServletRequest)} 判定。
 *
 * <h2>⚠️ 「要 uid」是<b>用例级</b>判定，不是拦截器判定</h2>
 * <p>拦截器（{@code interceptor/LikeJwtAuthInterceptor}）只负责<b>验签</b>，
 * <b>不</b>判断「这个端点要不要 uid」⇒ 否则 2 个公开读端点会被误伤成 1006。
 */
@Service
public class LikeApiService {

    private final LikeDomainService domainService;
    private final LikeApiSupport support;

    public LikeApiService(LikeDomainService domainService, LikeApiSupport support) {
        this.domainService = domainService;
        this.support = support;
    }

    /**
     * 查询某类作品的一批点赞 / 点踩数 —— 对应 B 侧 {@code LikeCountService#getLikeCount}。
     *
     * <p>⚠️ <b>公开读</b>：不取 uid。
     */
    public GetLikeCountResp getLikeCount(GetLikeCountReq req) {
        com.rag.proto.like.GetLikeCountReq rpcReq = com.rag.proto.like.GetLikeCountReq.newBuilder()
                .setTargetType(req.targetType())
                .addAllTargetIds(LikeApiSupport.normalizeTargetIds(req.targetIds()))
                .build();
        com.rag.proto.like.GetLikeCountResp rpcResp;
        try {
            rpcResp = domainService.getLikeCount(rpcReq);
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }
        Map<String, LikeCountItem> counts = new LinkedHashMap<>();
        for (Map.Entry<String, com.rag.proto.like.LikeCountItem> entry : rpcResp.getCountsMap().entrySet()) {
            counts.put(entry.getKey(), new LikeCountItem(
                    entry.getValue().getLikeCount(),
                    entry.getValue().getDislikeCount()));
        }
        return new GetLikeCountResp(counts);
    }

    /**
     * 查询某用户对一批作品的状态 —— 对应 B 侧 {@code LikeStateService#getLikeState}。
     */
    public GetLikeStateResp getLikeState(GetLikeStateReq req, HttpServletRequest httpRequest) {
        long uid = support.userId(httpRequest);
        List<String> ids = req.targetIds() == null ? List.of() : req.targetIds();
        com.rag.proto.like.GetLikeStateReq rpcReq = com.rag.proto.like.GetLikeStateReq.newBuilder()
                .setTargetType(req.targetType())
                .setUserId(uid)
                .addAllTargetIds(LikeApiSupport.normalizeTargetIds(ids))
                .build();
        try {
            Map<String, Integer> states = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry
                    : domainService.getLikeState(rpcReq).getStatesMap().entrySet()) {
                states.put(entry.getKey(), entry.getValue());
            }
            return new GetLikeStateResp(states);
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }
    }

    /**
     * 查询某作品的点赞人列表 —— 对应 B 侧 {@code TargetLikerListService#getTargetLikerList}。
     *
     * <p>⚠️ <b>公开读</b>：不取 uid。
     */
    public GetTargetLikerListResp getTargetLikerList(GetTargetLikerListReq req) {
        com.rag.proto.like.GetTargetLikerListReq rpcReq =
                com.rag.proto.like.GetTargetLikerListReq.newBuilder()
                        .setTargetType(req.targetType())
                        .setTargetId(req.targetId())
                        .setCursor(req.cursor())
                        .setPageSize(req.pageSize())
                        .build();
        com.rag.proto.like.GetTargetLikerListResp rpcResp;
        try {
            rpcResp = domainService.getTargetLikerList(rpcReq);
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }
        List<LikeItem> list = new ArrayList<>(rpcResp.getListCount());
        for (com.rag.proto.like.LikeItem item : rpcResp.getListList()) {
            list.add(new LikeItem(item.getUserId(), item.getTimestamp()));
        }
        return new GetTargetLikerListResp(list, rpcResp.getIsEnd(), rpcResp.getNextCursor());
    }

    /**
     * 查询某用户给出的点赞列表 —— 对应 B 侧 {@code UserLikeListService#getUserLikeList}。
     */
    public GetUserLikeListResp getUserLikeList(GetUserLikeListReq req, HttpServletRequest httpRequest) {
        long uid = support.userId(httpRequest);
        com.rag.proto.like.GetUserLikeListReq rpcReq = com.rag.proto.like.GetUserLikeListReq.newBuilder()
                .setTargetType(req.targetType())
                .setUserId(uid)
                .setCursor(req.cursor())
                .setPageSize(req.pageSize())
                .build();
        try {
            com.rag.proto.like.GetUserLikeListResp rpcResp = domainService.getUserLikeList(rpcReq);
            List<LikeRecordItem> list = new ArrayList<>(rpcResp.getListCount());
            for (com.rag.proto.like.LikeRecordItem item : rpcResp.getListList()) {
                list.add(new LikeRecordItem(
                        item.getTargetType(), item.getTargetId(), item.getTimestamp()));
            }
            return new GetUserLikeListResp(list, rpcResp.getIsEnd(), rpcResp.getNextCursor());
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }
    }

    /**
     * 查询某作者的累计获赞数 —— 对应 B 侧 {@code UserTotalLikeService#getUserTotalLike}。
     */
    public GetUserTotalLikeResp getUserTotalLike(GetUserTotalLikeReq req, HttpServletRequest httpRequest) {
        long uid = support.userId(httpRequest);
        com.rag.proto.like.GetUserTotalLikeReq rpcReq = com.rag.proto.like.GetUserTotalLikeReq.newBuilder()
                .setUserId(uid)
                .build();
        try {
            return new GetUserTotalLikeResp(
                    domainService.getUserTotalLike(rpcReq).getTotalLikeCount());
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }
    }

    /**
     * 执行一次点赞 / 取消点赞 / 点踩 / 取消点踩 —— 对应 B 侧 {@code LikeActionService#likeAction}。
     *
     * <p>⚠️ <b>{@code success} 恒为 true</b>：B 侧 api 层写的是
     * {@code new LikeActionResp(true, rpcResp.getMessage(), …)} ——
     * <b>不读</b> proto 里的 {@code success} 字段 ⇒ 业务成败看 envelope 的 {@code code}，
     * 不看这个布尔。照抄。
     */
    public LikeActionResp likeAction(LikeActionReq req, HttpServletRequest httpRequest) {
        long uid = support.userId(httpRequest);
        com.rag.proto.like.LikeActionReq rpcReq = com.rag.proto.like.LikeActionReq.newBuilder()
                .setTargetType(req.targetType())
                .setTargetId(req.targetId())
                .setUserId(uid)
                .setActionType(toActionType(req.actionType()))
                .setAuthorId(req.authorId())
                .setWeight(req.weight() == null ? 0f : (float) req.weight().doubleValue())
                .setClientIp(req.clientIp() == null ? "" : req.clientIp())
                .setPromotionId(req.promotionId() == null ? "" : req.promotionId())
                .build();
        try {
            com.rag.proto.like.LikeActionResp rpcResp = domainService.likeAction(rpcReq);
            return new LikeActionResp(true, rpcResp.getMessage(),
                    rpcResp.getLikeCount(), rpcResp.getDislikeCount());
        } catch (RuntimeException e) {
            throw support.rpcError(e);
        }
    }

    /**
     * B 侧 {@code LikeActionService#toActionType} 的逐字等价物。
     *
     * <p>⚠️ <b>{@code ActionType.forNumber} 对越界值返回 {@code null}</b> ⇒ 落
     * {@code ACTION_UNSPECIFIED}（0）⇒ 领域层按「参数异常」处理（1006/2001 口径见
     * {@code domain/service/LikeDomainService#likeAction}）。
     */
    private static ActionType toActionType(Long raw) {
        ActionType actionType = raw == null ? null : ActionType.forNumber(raw.intValue());
        return actionType == null ? ActionType.ACTION_UNSPECIFIED : actionType;
    }
}
