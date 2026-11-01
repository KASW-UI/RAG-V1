package com.rag.common.protection;

/**
 * 熔断打开时抛出的异常 —— <b>刻意不是</b> {@code StatusRuntimeException}。
 *
 * <p><b>为什么这个类型的选择本身就是语义</b>：
 * Go 侧熔断打开时 {@code breaker.DoWithAcceptableCtx} 返回的是
 * {@code breaker.ErrServiceUnavailable = errors.New("circuit breaker is open")}
 * —— 一个<b>普通 error</b>，不是 gRPC status。
 * 随后 {@code shared.RPCError} 走 {@code BizCodeFromError}：
 * <pre>
 * st, ok := status.FromError(err)
 * if !ok { return CodeServerBusy }          // ← 普通 error ⇒ ok=false ⇒ 1015
 * </pre>
 * ⇒ 客户端拿到的是 <b>code=1015、msg="service busy"（查表）</b>，
 * <b>而不是</b> "circuit breaker is open"。
 *
 * <p>本模块 RAG 侧的映射链是对齐的：
 * {@code MessageGrpcError#extractStatus} 只认 {@code StatusException} /
 * {@code StatusRuntimeException}，对本异常返回 {@code null}
 * ⇒ {@code bizCodeFromError} 落到末尾的 {@code CODE_SERVER_BUSY}（1015）；
 * msg 同样因「取不到 description」而回退到码表查得 "service busy"。
 *
 * <p>⚠️ <b>若图省事改成抛 {@code StatusRuntimeException(Status.UNKNOWN)}</b>，
 * {@code extractStatus} 就会返回 UNKNOWN status ⇒
 * {@code mapStatusCodeToBizCode(UNKNOWN)} 落 default 分支<b>碰巧也是</b> 1015，
 * 但 description 会变成 "circuit breaker is open" ⇒ <b>msg 与 Go 不同</b>。
 * 所以保持普通异常是有意为之，不是偷懒。
 */
public class ServiceBusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServiceBusyException(String message) {
        super(message);
    }
}