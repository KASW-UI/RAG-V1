package com.rag.common.observability;

/**
 * gRPC 服务端业务调用处理器（Java 版与 Go 版 {@code grpc.UnaryHandler} 等价）。
 *
 * <p><b>Go 出处</b>：{@code service/common/observability/rpc.go} 的 handler 形态。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java}
 * 的嵌套接口 {@code RpcCallHandler}（L2 · D13）＋ <b>从嵌套类提到顶层</b>（L4 · D11 包压平）。
 */
@FunctionalInterface
public interface RpcCallHandler<ReqT, RespT> {

    /**
     * 执行一次业务调用。
     *
     * @param req 请求
     * @return 响应
     * @throws Exception 业务异常（由 {@link RpcHandleRunner} 按 gRPC 码语义分类）
     */
    RespT handle(ReqT req) throws Exception;
}
