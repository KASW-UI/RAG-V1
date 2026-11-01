package com.rag.common.observability;

/**
 * gRPC 调用结果（resp + 错误），对应 Go 版 {@code (any, error)}。
 *
 * <p><b>Go 出处</b>：{@code service/common/observability/rpc.go} 的 {@code (any, error)} 返回对。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java}
 * 的嵌套类 {@code RpcResult}（L2 · D13）＋ <b>从嵌套类提到顶层 + 改写成 record</b>（L4 · D11 / 生态优先）。
 *
 * <p>⚠️ <b>为什么改写成 record</b>：两个字段都是<b>只读</b>的，且 B 侧没有第三个构造器
 * ⇒ ⇒ record 的「不可变 + 自动 equals/hashCode/toString」不改变任何可观察行为
 * ⇒ ⇒ 只是表达层收敛（L4 · D4）。
 * <p>⚠️ <b>访问器名保持不变</b>（{@link #response()} / {@link #error()}）⇒ 调用方零改动。
 *
 * @param <RespT> 响应类型
 * @param response 业务响应（出错时为 {@code null}）
 * @param error    错误（成功时为 {@code null}）
 */
public record RpcResult<RespT>(RespT response, Throwable error) {
}
