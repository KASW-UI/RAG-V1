package com.rag.common.protection;

import io.grpc.Status;

/**
 * <b>zrpc 服务端熔断的「失败判据」</b> —— 对齐 B 侧 {@code serverSideAcceptable}。
 *
 * <h3>B 出处（消费点，不是声明点）</h3>
 * <pre>
 *   zrpc/internal/serverinterceptors/breakerinterceptor.go:53-58
 *       func serverSideAcceptable(err error) bool {
 *           if errorx.In(err, context.DeadlineExceeded, breaker.ErrServiceUnavailable) {
 *               return false              // ← ① 深查（errors.Is 语义）⇒ 算失败
 *           }
 *           return codes.Acceptable(err)  // ← ② 浅查（status.Code）⇒ 6 码白名单
 *       }
 *
 *   zrpc/internal/codes/accept.go:9-17
 *       func Acceptable(err error) bool {
 *           switch status.Code(err) {
 *           case codes.DeadlineExceeded, codes.Internal, codes.Unavailable, codes.DataLoss,
 *                codes.Unimplemented, codes.ResourceExhausted:
 *               return false              // ← 这 6 个码算失败
 *           default:
 *               return true
 *           }
 *       }
 * </pre>
 *
 * <h3>🔴 B 的两层判据，在本仓被证明<b>可压成一层</b>（逐条论证）</h3>
 * <p>B 的 ① 是<b>深查</b>（{@code errorx.In} → {@code errors.Is}），② 是<b>浅查</b>
 * （{@code codes.Acceptable} → {@code status.Code(err)}，只断言最外层）。
 * <p>本类的结论是：<b>② 单层就够，① 在本仓不可达且可证冗余</b>，理由三条：
 *
 * <ol>
 *   <li><b>① 的两个目标在 message 域都已被 ② 覆盖</b>：
 *       <ul>
 *         <li><b>ctx 超时</b>：B 侧 err 是 {@code context.DeadlineExceeded}（<b>非</b> status），
 *             靠 ① 命中才算失败；Java 侧 ctx 超时最终体现为
 *             {@code close(DEADLINE_EXCEEDED)} ⇒ ② 直接命中。<b>结论相同</b>。</li>
 *         <li><b>下游熔断打开</b>（{@code breaker.ErrServiceUnavailable}）：
 *             B 侧靠 ① 命中；Java 侧它不是 gRPC status ⇒ 被 rpc 层的
 *             {@code invoke()} 统一转成 {@code INTERNAL} ⇒ ② 命中。<b>结论相同</b>。</li>
 *       </ul>
 *   </li>
 *   <li><b>① 的「handler 自己造 DEADLINE_EXCEEDED」这一支在本域不存在</b>：
 *       实测 message-rpc 的业务代码<b>一次都没有</b>显式使用 {@code DEADLINE_EXCEEDED}
 *       （只有 {@code SqlTimeoutStatusTranslator} 把 PG 的 57014 翻成它 —— 那是<b>真超时</b>，
 *       两侧都该判失败，一致）。
 *       ⇒ 在本域，{@code DEADLINE_EXCEEDED} ⇔ 「ctx / DB 超时」，与 B 的
 *       {@code errors.Is(err, context.DeadlineExceeded)} <b>完全等价</b>。</li>
 *   <li><b>grpc-java 的拦截器 API 根本拿不到 Throwable</b>：
 *       {@code ServerCall.Listener} <b>没有</b> {@code onError(Throwable)} 方法
 *       （那是 {@code StreamObserver} 的方法）；
 *       拦截器能观察到的唯一错误信号是 {@code ServerCall#close(Status, Metadata)}。
 *       ⇒ <b>想做深查也没有输入</b>。</li>
 * </ol>
 *
 * <p>⇒ 本类只保留 ②（6 码白名单）。
 * ⚠️ <b>若将来某个 handler 真的自己造 {@code DEADLINE_EXCEEDED} status</b>
 * （即上面第 2 条的前提被打破），本类的判定会与 B 侧 分岔
 * （B 侧 判 Pass、RAG 判 Fail）⇒ 那时必须改判据，而不是改白名单。
 *
 * <h3>⚠️ 业务错误不算失败（这是熔断器能用的前提）</h3>
 * <p>message-rpc 的业务错误走 {@code StatusRuntimeException(INVALID_ARGUMENT / NOT_FOUND / PERMISSION_DENIED)}
 * （{@code MessageRpcSupport#grpcRuntimeError}）⇒ 这三个码<b>都不在</b> 6 码白名单里
 * ⇒ <b>业务错误不参与熔断</b>，与 B 侧 一致（B 侧同一个 6 码白名单）。
 * <p>⚠️ 反过来说：若把判据改成「非 OK 即失败」，{@code NOT_FOUND}（会话不存在 5202）这类
 * 天天发生的业务错误会大量计成失败 ⇒ 熔断误开 ⇒ 打掉正常流量。
 */
final class RpcFailurePolicy {

    private RpcFailurePolicy() {
    }

    /**
     * 这个状态<b>是否算作熔断失败</b>。
     *
     * <p>对应 B 侧 {@code serverSideAcceptable(err) == false}。
     */
    static boolean isFailure(Status.Code code) {
        return !isAcceptable(code);
    }

    /**
     * B 侧 {@code codes.Acceptable}：这 6 个码<b>不可接受</b>（⇒ 算失败），其余可接受。
     *
     * <p>⚠️ <b>一个码都不能多、也不能少</b>（{@code accept.go:9-17}）。
     * 特别地：{@code UNKNOWN} 归为<b>可接受</b> —— B 的 {@code status.Code(err)}
     * 对非 status 错误返回 {@code codes.Unknown}，而 Unknown 不在失败名单里。
     */
    static boolean isAcceptable(Status.Code code) {
        if (code == null) {
            return true;
        }
        switch (code) {
            case DEADLINE_EXCEEDED, INTERNAL, UNAVAILABLE, DATA_LOSS,
                 UNIMPLEMENTED, RESOURCE_EXHAUSTED:
                return false;
            default:
                return true;   // 含 OK / UNKNOWN / NOT_FOUND / PERMISSION_DENIED …
        }
    }
}
