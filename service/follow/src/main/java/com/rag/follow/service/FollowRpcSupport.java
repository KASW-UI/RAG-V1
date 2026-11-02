package com.rag.follow.service;

import com.rag.common.logger.LogOption;
import com.rag.follow.contract.ErrCodes;
import com.rag.follow.contract.ErrMsg;
import com.rag.follow.contract.GrpcErrorMapper;
import com.rag.proto.follow.BaseResp;
import com.rag.proto.follow.ListReq;
import com.rag.proto.follow.RecommendResp;
import com.rag.proto.follow.RelationReq;
import com.rag.proto.follow.UserListResp;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * <p><b>follow 域 gRPC 侧的跨用例共享业务工具</b> —— 逐条照抄 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/service/FollowRpcSupport.java}
 * （B 侧 348 行，本类逐方法对应，行号见各方法 javadoc）。
 *
 * <h2>B 出处与语义等价论证</h2>
 * <table border="1">
 *   <caption>方法 ↔ B 侧方法</caption>
 *   <tr><th>本类方法</th><th>B 侧行号</th><th>语义</th></tr>
 *   <tr><td>{@link #rpcError}</td><td>B 侧 89-92</td><td>业务错误的<b>唯一构造入口</b>（unchecked 桥）</td></tr>
 *   <tr><td>{@link #userLogOptionOf}</td><td>B 侧 108-110</td><td>日志选项工厂</td></tr>
 *   <tr><td>{@link #validateRelationReq}</td><td>B 侧 137-146</td><td>关系请求校验（5003 / 5201）</td></tr>
 *   <tr><td>{@link #NormalizedListReq}</td><td>B 侧 156-157</td><td>归一化结果载体</td></tr>
 *   <tr><td>{@link #normalizeListReq}</td><td>B 侧 182-202</td><td>列表请求三段归一化</td></tr>
 *   <tr><td>{@link #successBaseResp}</td><td>B 侧 215-220</td><td>成功响应工厂</td></tr>
 *   <tr><td>{@link #successUserListResp}</td><td>B 侧 229-235</td><td>成功列表响应工厂</td></tr>
 *   <tr><td>{@link #successRecommendResp}</td><td>B 侧 247-255</td><td>成功推荐响应工厂</td></tr>
 *   <tr><td>{@link #toSet}</td><td>B 侧 268-270</td><td>切片 → 去重集合</td></tr>
 *   <tr><td>{@link #sortedKeys}</td><td>B 侧 282-284</td><td>集合 → <b>升序</b>切片</td></tr>
 *   <tr><td>{@link #scoreWeight}</td><td>B 侧 304-313</td><td>推荐打分权重</td></tr>
 *   <tr><td>{@link #clampRecommendDepth}</td><td>B 侧 322-330</td><td>深度夹取</td></tr>
 *   <tr><td>{@link #clampFanout}</td><td>B 侧 339-347</td><td>扇出夹取</td></tr>
 * </table>
 *
 * <h2>⚠️ L4 表达层差异（三处，均不改变可观察行为）</h2>
 * <ol>
 *   <li><b>包名</b>：B 侧 {@code com.rag.follow.follow.rpc.service} ⇒ C 侧 {@code com.rag.follow.service}
 *       （D7 压平；映射表 {@code docs/Mapping_Table/follow/follow.md} 第 149 行）。</li>
 *   <li><b>错误契约的落点</b>：B 侧引用 {@code com.rag.follow.follow.common.errmsg.*}（B 侧自建的 follow-common 模块）
 *       ⇒ C 侧落域内 {@code com.rag.follow.contract.*}（{@link ErrCodes} / {@link ErrMsg} / {@link GrpcErrorMapper}）
 *       ⇒ <b>码值、文案、gRPC 码映射逐条一致</b>。</li>
 *   <li><b>日志选项的落点</b>：B 侧用 {@code com.rag.common.logger.WithUserID.of(...)}
 *       ⇒ C 侧共享层把它收成了 {@link LogOption#withUserId(String)} 静态工厂
 *       （本仓 {@code common/.../logger/LogOption.java} 的 {@code WithUserID} / {@code WithArticleID} 合并说明）
 *       ⇒ <b>产出同一个闭包</b>，行为一致。</li>
 * </ol>
 *
 * <h2>⚠️ 本类<b>刻意不持有</b> gRPC stub</h2>
 * <p>B 侧把 {@code ensureUserExists} 与这些纯函数同放在 helper 里，但前者依赖下游 stub
 * ⇒ C 侧把它拆到 {@link FollowUserExistenceService}（独立 Bean），
 * 让本类保持「无出网依赖」⇒ 可被纯单测覆盖，不必 mock stub。拆分只改依赖归属，不改行为。
 *
 * <h2>⚠️ 校验失败一律抛异常而非返回错误对象</h2>
 * <p>B 侧在「发错误的那一刻」就把「gRPC 码 + 业务文案」定死，响应对象一律是 nil
 * ⇒ C 侧改为抛 unchecked 异常，出口只有一处（{@code FollowGrpcService} 的 {@code onError}）。
 * 这条链路依赖 description 文案承载业务码 ⇒ <b>不能</b>改成「返回 Result 再由入口转换」
 * （会多出「可能忘了转换」的分支，且文案会丢）。
 */
@Component
public class FollowRpcSupport {

    // =============================================================================
    // 错误构造桥接
    // =============================================================================

    /**
     * <p><b>业务错误的<b>唯一构造入口</b></b> —— 把 {@link GrpcErrorMapper} 的映射转成 unchecked 异常抛出。
     *
     * <h3>为什么需要这层桥（checked → unchecked）</h3>
     * <p>B 出处：B 侧 {@code FollowRpcSupport.java:89-92}，
     * 其内部调用的 {@code FollowGrpcError.grpcError(...)} 返回 {@link StatusException}，
     * 而 grpc-java 里它是 <b>checked</b> 异常（{@code public class StatusException extends Exception}）
     * ⇒ 直接 throw 会带来两个真实问题：
     * <ol>
     *   <li><b>指标把失败记成 success</b>：{@code FollowDomainService} 里
     *       {@code catch (RuntimeException e)} <b>抓不到</b> checked 的 {@code StatusException}
     *       ⇒ finally 里的 {@code failure} 保持 null
     *       ⇒ {@code ObserveRPC} / {@code ObserveRelation} 记成成功。
     *       这是<b>指标口径级别的静默错误</b>：响应确实是错的，只是仪表盘反了，测试很难发现。</li>
     *   <li>被迫在十餘个方法签名上加 {@code throws StatusException}，与本仓其它 service 的惯例不符，
     *       也让 {@code Supplier} 型 lambda 不可用。</li>
     * </ol>
     *
     * <h3>为什么用 {@link StatusRuntimeException} 而不是自造异常类</h3>
     * <p>{@link StatusRuntimeException} 是 grpc-java <b>自己提供的 unchecked 兄弟</b>，
     * 两者持有<b>同一个 {@link Status}</b> ⇒ 上 wire 的字节<b>完全相同</b>
     * ⇒ 它只是「同样的错误，unchecked 的投递方式」，不是新增机制。
     *
     * <h3>⚠️ 业务码的映射仍然只有一个真源</h3>
     * <p>本方法先向 {@link GrpcErrorMapper} 要 {@code Status}（含 description = 业务文案），
     * 再只做 {@code asRuntimeException()} 的包装 ——
     * <b>不复制任何 {@code bizCode → msg} 的映射表</b>。
     * 若改成在这里自己 {@code Status.fromCode(...).withDescription(...)}，
     * 就多出第二张映射表，{@code contract/} 改文案时这里会静默不同步。
     *
     * <h3>⚠️ 对业务码反查（{@code bizCodeFromError}）没有影响</h3>
     * <p>{@link GrpcErrorMapper#bizCodeFromError} 的第①级判定同时认
     * {@link StatusException} 与 {@link StatusRuntimeException}，
     * 且第①级<b>原样保留 description</b> ⇒ 日志里记的业务码与 B 侧完全一致。
     */
    public static StatusRuntimeException rpcError(Status.Code grpcCode, int bizCode) {
        StatusException checked = GrpcErrorMapper.grpcError(grpcCode, bizCode);
        return checked.getStatus().asRuntimeException();
    }

    // =============================================================================
    // 日志选项 —— userLogOption
    // =============================================================================

    /**
     * 对应 B 侧 {@code FollowRpcSupport.java:108-110} 的 {@code userLogOptionOf}：
     * 把用户 id 包成日志选项（写进日志的 {@code user_id} 字段）。
     *
     * <p>B 侧用 {@code strconv.FormatInt(userID, 10)} 而不是 {@code fmt.Sprint}，说明<b>不传负数</b>；
     * C 侧 {@code String.valueOf(long)} 语义相同。
     *
     * <p>做成 <b>static</b>：它不需要本类的任何状态，
     * 而 {@link FollowUserExistenceService} 的日志方法要在没有注入本类的地方构造它。
     */
    public static LogOption userLogOptionOf(long userId) {
        return LogOption.withUserId(String.valueOf(userId));
    }

    // =============================================================================
    // 校验与归一化
    // =============================================================================

    /**
     * 对应 B 侧 {@code FollowRpcSupport.java:137-146} 的关系请求校验：
     * <pre>
     * if in.GetUserId() &lt;= 0 || in.GetTargetId() &lt;= 0 ⇒ 5003（INVALID_ARGUMENT）
     * if in.GetUserId() == in.GetTargetId()            ⇒ 5201（INVALID_ARGUMENT）
     * </pre>
     *
     * <p><b>⚠️ 两条错误的 gRPC 码相同（INVALID_ARGUMENT），业务码不同（5003 / 5201）</b>。
     * 而 {@link GrpcErrorMapper} 的文案反查表能把 5201 还原出来
     * （{@code "self relation is not allowed"}）⇒ 下游拿到的仍是 5201 而不是被兜底成 5003
     * ⇒ <b>这条链路依赖 {@code contract/} 的文案反查表</b>，别绕过它自己造错误。
     *
     * <p><b>⚠️ {@code in == nil} 分支在 C 侧不单独实现</b>：
     * protoc 生成的请求对象永远非 null（gRPC runtime 保证），
     * 且 {@code getUserId()} 在未设置时返回 0 ⇒ 会被 {@code <= 0} 捕获并给出同一个 5003。
     * 行为等价，不需要也不应该为此加一个不可达的分支。
     */
    public void validateRelationReq(RelationReq in) {
        if (in.getUserId() <= 0 || in.getTargetId() <= 0) {
            throw rpcError(Status.Code.INVALID_ARGUMENT, ErrCodes.ERROR_INVALID_PARAM);
        }
        if (in.getUserId() == in.getTargetId()) {
            throw rpcError(Status.Code.INVALID_ARGUMENT, ErrCodes.ERROR_SELF_RELATION);
        }
    }

    /**
     * {@link #normalizeListReq} 的返回值 —— 对应 B 侧 {@code FollowRpcSupport.java:156-157} 的 record。
     *
     * <p>⚠️ B 侧（Go 侧原始形态）出错时返回 {@code (0, 0, 0, err)}；C 侧改为抛异常，
     * 调用点拿不到「三个 0」。这是本模块唯一的表达层差异，语义一致
     * （B 侧调用点同样在 {@code if err != nil} 处直接 return，不使用那三个 0）。
     */
    public record NormalizedListReq(long userId, int offset, int limit) {
    }

    /**
     * 对应 B 侧 {@code FollowRpcSupport.java:182-202} 的列表请求归一化：
     * <pre>
     * if in.GetUserId() &lt;= 0          ⇒ 5003（INVALID_ARGUMENT）
     * offset := in.GetOffset(); if offset &lt; 0 { offset = 0 }
     * limit  := in.GetLimit();  if limit  &lt;= 0 { limit = defaultLimit }
     * if maxLimit &gt; 0 &amp;&amp; limit &gt; maxLimit { limit = maxLimit }
     * </pre>
     *
     * <p><b>⚠️ 三段归一化的顺序不可调换</b>：
     * <ol>
     *   <li>先夹 <b>offset</b>：负数归零（不是报错）。</li>
     *   <li>再补 <b>limit</b>：{@code <= 0} 换成 {@code defaultLimit}。</li>
     *   <li>最后<b>截断</b>：{@code limit > maxLimit} 压到 {@code maxLimit}。</li>
     * </ol>
     * 若把截断放到补默认值之前，{@code defaultLimit > maxLimit} 的配置会让 limit 卡在越界值上。
     *
     * <p><b>⚠️ {@code maxLimit <= 0} 时整条截断短路</b>（Go 的 {@code maxLimit > 0 &&} 前置条件）：
     * 意味着「配 0 = 不限制」。这是 B 侧的实际行为，照搬，不加校验。
     *
     * <p>三个列表方法传 {@code (List.DefaultLimit, List.MaxLimit)} = (20, 100)，
     * {@code GetRecommendations} 传 {@code (Recommend.DefaultLimit, Recommend.MaxLimit)} = (20, <b>50</b>)。
     */
    public NormalizedListReq normalizeListReq(int defaultLimit, int maxLimit, ListReq in) {
        if (in.getUserId() <= 0) {
            throw rpcError(Status.Code.INVALID_ARGUMENT, ErrCodes.ERROR_INVALID_PARAM);
        }

        int offset = in.getOffset();
        if (offset < 0) {
            offset = 0;
        }

        int limit = in.getLimit();
        if (limit <= 0) {
            limit = defaultLimit;
        }
        if (maxLimit > 0 && limit > maxLimit) {
            limit = maxLimit;
        }

        return new NormalizedListReq(in.getUserId(), offset, limit);
    }

    // =============================================================================
    // 响应工厂 —— successBaseResp / successUserListResp / successRecommendResp
    // =============================================================================

    /**
     * 对应 B 侧 {@code FollowRpcSupport.java:215-220}：
     * {@code BaseResp{Code: SUCCESS, Msg: GetErrMsg(SUCCESS)}} ⇒ {@code code=200, msg="OK"}。
     *
     * <p>⚠️ <b>成功也走 body 而不是空响应</b>：proto 里 {@code BaseResp} 是有字段的，
     * B 侧明确填了 200 / "OK"，C 侧必须同样填，不能返回 {@code null} 或默认实例。
     */
    public BaseResp successBaseResp() {
        return BaseResp.newBuilder()
                .setCode(ErrCodes.SUCCESS)
                .setMsg(ErrMsg.getMsg(ErrCodes.SUCCESS))
                .build();
    }

    /**
     * 对应 B 侧 {@code FollowRpcSupport.java:229-235}。
     *
     * <p>⚠️ <b>空列表与 nil 在 wire 上不可区分</b>：proto3 的 repeated 字段为空时
     * 序列化出来就是「字段缺失」，B 侧的 nil 切片与 C 侧的 {@code addAll(emptyList)}
     * 产生<b>逐字节相同</b>的编码。因此这里统一用空列表，不必区分。
     */
    public UserListResp successUserListResp(List<Long> userIds) {
        return UserListResp.newBuilder()
                .setCode(ErrCodes.SUCCESS)
                .setMsg(ErrMsg.getMsg(ErrCodes.SUCCESS))
                .addAllUserIds(userIds)
                .build();
    }

    /**
     * 对应 B 侧 {@code FollowRpcSupport.java:247-255}。
     *
     * <p>⚠️ B 侧在两条「推不出推荐」的路径上用的是<b>字面量 {@code nil}</b>
     * ⇒ 对应本方法的 {@code null} 入参 —— 这里刻意<b>不</b>用空列表代替，
     * 因为两者的语义要说清楚：{@code null} 是 nil（proto 不写字段），
     * 与 {@link #successUserListResp} 里「B 侧传的是非 nil 空切片」不同。
     * <b>wire 上二者仍然一致</b>，区别只在代码可读性。
     */
    public RecommendResp successRecommendResp(List<RecommendResp.RecommendUser> users) {
        RecommendResp.Builder builder = RecommendResp.newBuilder()
                .setCode(ErrCodes.SUCCESS)
                .setMsg(ErrMsg.getMsg(ErrCodes.SUCCESS));
        if (users != null && !users.isEmpty()) {
            builder.addAllUsers(users);
        }
        return builder.build();
    }

    // =============================================================================
    // 推荐算法纯函数 —— toSet / sortedKeys / scoreWeight / clamp*
    // =============================================================================

    /**
     * 对应 B 侧 {@code FollowRpcSupport.java:268-270}：切片 → 去重集合。
     *
     * <p>⚠️ <b>输入本身可能已含重复</b>：{@code ListAllFollowTargets} 走的是
     * {@code SELECT target_id}，若历史数据里存在同一 target 的多行
     * （唯一键保证不了历史导入数据），去重是必需的。
     */
    public Set<Long> toSet(List<Long> userIds) {
        return new java.util.HashSet<>(userIds);
    }

    /**
     * 对应 B 侧 {@code FollowRpcSupport.java:282-284}：集合 → <b>升序</b>切片。
     *
     * <p><b>⚠️ 排序不是可有可无的整理，是确定性来源</b>：
     * B 侧的集合遍历顺序随机，这段代码用排序把它钉成升序，
     * 从而让 BFS 的「本层节点遍历顺序」确定 ⇒ 分数累加顺序确定 ⇒ 最终排序确定。
     * C 侧的 {@code HashSet} 同样无序 ⇒ <b>必须</b>排序，
     * 否则同一份数据在不同 JVM 上可能给出不同的推荐结果。
     * {@link TreeSet} 是这份升序契约的直接实现。
     */
    public List<Long> sortedKeys(Set<Long> values) {
        return new ArrayList<>(new TreeSet<>(values));
    }

    /**
     * 对应 B 侧 {@code FollowRpcSupport.java:304-313}：
     * <pre>
     * if maxDepth &lt; 2 { maxDepth = 3 }
     * if depth    &lt; 2 { depth    = 2 }
     * weight := (maxDepth - depth + 1) * 10
     * if weight &lt; 10 { weight = 10 }
     * </pre>
     *
     * <p><b>⚠️ 两处「看起来多余」的下界修正各有来由，不能删</b>：
     * <ul>
     *   <li>{@code maxDepth < 2 ⇒ 3}：深度为 0/1 时权重会算出负数或 0。</li>
     *   <li>{@code depth < 2 ⇒ 2}：BFS 的 {@code currentDepth} 从 2 起，
     *       这条修正实际不可达，但它保证该函数是<b>纯函数</b> —— 任意入参都无负权重。</li>
     * </ul>
     * 权重恒 &gt;= 10 ⇒ <b>不存在 0 分候选</b>，所以推荐服务里那处
     * {@code if score <= 0 { continue }} 是纯防御性代码（C 侧同样保留）。
     */
    public int scoreWeight(int maxDepth, int depth) {
        int effectiveMaxDepth = (maxDepth < 2) ? 3 : maxDepth;
        int effectiveDepth = (depth < 2) ? 2 : depth;

        int weight = (effectiveMaxDepth - effectiveDepth + 1) * 10;
        if (weight < 10) {
            weight = 10;
        }
        return weight;
    }

    /**
     * 对应 B 侧 {@code FollowRpcSupport.java:322-330}：
     * {@code depth < 2 ⇒ 3}（<b>注意是 3 不是 2</b>）、{@code depth > 5 ⇒ 5}、否则原值。
     *
     * <p>⚠️ 「小于 2 时回到 3」看着像手滑，实则是 B 侧的实际行为：
     * 配 {@code MaxDepth: 1} 的结果是 {@code 3} 而不是 {@code 2}。照搬。
     */
    public int clampRecommendDepth(int depth) {
        if (depth < 2) {
            return 3;
        }
        if (depth > 5) {
            return 5;
        }
        return depth;
    }

    /**
     * 对应 B 侧 {@code FollowRpcSupport.java:339-347}：
     * {@code fanout <= 0 ⇒ 50}、{@code fanout > 200 ⇒ 200}、否则原值。
     *
     * <p>⚠️ 上界 200 是<b>硬编码魔数</b>，B 侧 yaml 里没有对应字段 ——
     * 不新增配置项（AGENTS.md §八「不新增 B 侧不存在的开关」）。
     */
    public int clampFanout(int fanout) {
        if (fanout <= 0) {
            return 50;
        }
        if (fanout > 200) {
            return 200;
        }
        return fanout;
    }
}
