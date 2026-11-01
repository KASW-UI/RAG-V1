package com.rag.common.protection;

import io.grpc.ServerInterceptor;

/**
 * <p><b>链上的一个节点</b> —— 在 {@link RpcLayer} 之外，额外暴露<b>内层</b>，
 * 供 {@link RpcProtectionChain#layerNames(ServerInterceptor)} 逐层下行。
 *
 * <p>⚠️ <b>为什么与 {@link RpcLayer} 分开</b>：
 * 各层自己只知道自己<b>叫什么</b>（{@code layerName()}），
 * <b>不知道</b>自己的内层是谁（内层是装配时由 {@link RpcProtectionChain} 决定的）
 * ⇒ ⇒ 「内层」这条信息只能由<b>包装壳</b>持有 ⇒ ⇒ 拆成两个接口。
 */
public interface RpcLayerChainNode extends RpcLayer {

    /** 本节点包裹的<b>内层</b>拦截器。 */
    ServerInterceptor inner();
}
