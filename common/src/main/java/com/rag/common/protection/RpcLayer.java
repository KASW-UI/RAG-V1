package com.rag.common.protection;

import io.grpc.ServerInterceptor;

/**
 * <p><b>gRPC 服务端拦截链上的一层</b> —— 自报层名，可被<b>只读</b>查询入口观测。
 *
 * <p>RAG 出处：{@code service/common/protection/GoStyleRpcProtectionChain.java} 的嵌套接口
 * {@code Layered}（抄写基准 = RAG，L2 业务语义层整段抄 · D13）。
 *
 * <p><b>⚠️ 为什么需要它</b>：链序<b>是行为语义</b>（见 {@link RpcProtectionChain}），
 * 而「链序对不对」必须是<b>可断言</b>的 ⇒ ⇒ 不能只靠「装配代码里那几个 {@code @Order} 看着对」。
 * <br>⇒ 本接口让每一层自报层名 ⇒ ⇒ 守门可以<b>真的把链搭出来</b>、逐层读出名字、
 * 断言顺序是 {@code [recover, stat, prometheus, breaker, shedding]}。
 *
 * <p><b>⚠️ 与 B 侧接口的一处形态差</b>：B 侧的 {@code Layered} 同时声明了
 * {@code layerName()} 与 {@code inner()}，且由<b>包装壳</b>（匿名类）实现；
 * C 侧把它拆成两个接口：
 * <ul>
 *   <li>{@code RpcLayer}（本接口）—— 只声明 {@code layerName()} ⇒ <b>各层自己</b>实现，
 *       不必知道自己的内层是谁（内层由装配时决定）；</li>
 *   <li>{@link RpcLayerChainNode} —— 声明 {@code inner()} ⇒ 由
 *       {@link RpcProtectionChain} 的<b>包装壳</b>实现 ⇒ 供逐层下行读层名。</li>
 * </ul>
 */
public interface RpcLayer extends ServerInterceptor {

    /** 本层的名字（{@code recover} / {@code stat} / {@code prometheus} / {@code breaker} / {@code shedding}）。 */
    String layerName();
}
