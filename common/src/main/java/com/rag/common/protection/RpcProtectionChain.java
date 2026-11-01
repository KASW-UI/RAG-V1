package com.rag.common.protection;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>gRPC 服务端拦截链的装配与观测</b>（链序的唯一计算点 + 只读观测入口）。
 *
 * <p>RAG 出处：{@code service/common/protection/GoStyleRpcProtectionChain.java}
 * （抄写基准 = RAG，L2 业务语义层整段抄 · D13）。
 *
 * <p><b>⚠️ L4 idiomatization：链序由「{@code @Order} + 显式嵌套」表达，不靠「多次
 * {@code server.intercept(...)} 的隐式嵌套方向」。</b>
 *
 * <p><b>Go 出处（消费点，不是声明点）</b>：
 * <pre>
 *   zrpc/server.go:144-163 setupUnaryInterceptors —— 按顺序 AddUnaryInterceptors：
 *       Trace → Recover → Stat → Prometheus → Breaker → Shedding → Timeout
 *   zrpc/internal/server.go:58 —— AddUnaryInterceptors 是 **append 到尾部**
 *       ⇒ grpc-go 的 ChainUnaryInterceptor 里「**先注册的最外层**」
 *       ⇒ 最终执行序（由外到内）：Trace → Recover → Stat → Prometheus
 *                                  → Breaker → Shedding → Timeout → handler
 * </pre>
 *
 * <p><b>⚠️ 链序是行为语义，不是排版</b>：
 * <ul>
 *   <li><b>recover 必须在最外层</b> —— 否则内层的 panic 兜不住；</li>
 *   <li><b>breaker 必须在 shedding 的外层</b> —— Go 侧 shedding 的
 *       {@code RESOURCE_EXHAUSTED} 会冒泡上来被 breaker 记成失败
 *       ⇒ <b>持续降载最终会把熔断器打开</b>；</li>
 *   <li><b>prometheus 必须在 breaker / shedding 的外层</b> —— 否则被拒绝的请求在指标里
 *       <b>整个消失</b>（Go 侧这两类请求照样被记一笔）；</li>
 *   <li><b>stat 在 prometheus 的外层</b> —— 量到的耗时<b>含</b>降载 / 熔断开销
 *       （Go 的 Stat 层同样在外面）。</li>
 * </ul>
 *
 * <p><b>⚠️ 与 B 侧的一处形态差（L4）</b>：B 侧由 {@code GoStyleRpcProtectionChain.build(business, props)}
 * <b>手工</b>拼链（每层一个静态工厂方法）；C 侧改为「各层各自是 {@code ServerInterceptor} Bean +
 * {@code @Order} 定序 + 本类<b>显式嵌套</b>」⇒
 * <ul>
 *   <li>✅ 好处：域侧可以再加自己的拦截器而不必改共享层；</li>
 *   <li>✅ 好处：<b>不依赖「谁更外层」这种容易记反的 API 细节</b>
 *       （grpc-api 1.63.0 的 {@code ServerBuilder#intercept} 只有单参重载，多次调用时
 *       <b>最后 add 的在最外层</b>）；</li>
 *   <li>⚠️ 代价：链序落在 {@code @Order} 上 ⇒ 必须由 {@link #layerNames(ServerInterceptor)}
 *       <b>真的把链搭出来断言</b>（否则就是「看着对」）⇒ ⇒ 本类提供该入口。</li>
 * </ul>
 */
public final class RpcProtectionChain {

    private RpcProtectionChain() {
    }

    /**
     * 把一组拦截器串成一条链：<b>列表第 0 个在最外层</b>。
     *
     * <p>⚠️ <b>为什么用显式嵌套而不是「多次 {@code server.intercept(...)}」</b>：
     * grpc-api 1.63.0 的 {@code ServerBuilder#intercept} 只有单参重载，多次调用时
     * <b>最后 add 的在最外层</b> ⇒ 链序就押在一个容易记反的 API 事实上 ⇒
     * ⇒ 本方法把顺序<b>写进代码结构</b>。
     *
     * @param ordered 已按「外 → 内」排好序的拦截器（Spring 的 {@code orderedStream()} 即满足）
     * @return 合成后的链；入参为空时返回 {@code null}
     */
    public static ServerInterceptor compose(List<ServerInterceptor> ordered) {
        if (ordered == null || ordered.isEmpty()) {
            return null;
        }
        ServerInterceptor chain = null;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            chain = wrap(ordered.get(i), chain);
        }
        return chain;
    }

    /**
     * 观测<b>已构造出的链</b>的层名（由外到内）。
     *
     * <p>⚠️ 只读：<b>不改链、不改配置</b> ⇒ 零行为改动。
     * ⇒ 守门可据此<b>逐层</b>断言链序（而不是只断言第一层）。
     *
     * @param chain {@link #compose} 产出的链
     * @return 层名列表（由外到内；<b>不是</b> {@link RpcLayer} 的层不在列表里）
     */
    public static List<String> layerNames(ServerInterceptor chain) {
        List<String> names = new ArrayList<>();
        ServerInterceptor current = chain;
        while (current != null) {
            if (current instanceof RpcLayerChainNode node) {
                names.add(node.layerName());
                current = node.inner();
                continue;
            }
            if (current instanceof RpcLayer layer) {
                // ⚠️ **最内层是「原样返回」的那一层**（compose 里 inner == null 时不包壳）
                //    ⇒ 它只有 RpcLayer、没有 RpcLayerChainNode（拿不到 inner()）
                //    ⇒ 若只认 RpcLayerChainNode，遍历会**在最内层断掉** ⇒ 业务层名字丢失。
                names.add(layer.layerName());
            }
            break;
        }
        return List.copyOf(names);
    }

    /** 把 {@code outer} 串在 {@code inner} 的外层（{@code inner == null} 时原样返回）。 */
    private static ServerInterceptor wrap(ServerInterceptor outer, ServerInterceptor inner) {
        if (inner == null) {
            return outer;
        }
        if (outer instanceof RpcLayer layered) {
            // ⚠️ 带层名的层 ⇒ 包出来的壳也带层名 ⇒ 否则 layerNames() 只能读到最外层
            final ServerInterceptor delegate = inner;
            return new RpcLayerChainNode() {
                @Override
                public String layerName() {
                    return layered.layerName();
                }

                @Override
                public ServerInterceptor inner() {
                    return delegate;
                }

                @Override
                public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                        ServerCall<ReqT, RespT> call,
                        Metadata headers,
                        ServerCallHandler<ReqT, RespT> next) {
                    ServerCallHandler<ReqT, RespT> delegateToInner =
                            new ServerCallHandler<ReqT, RespT>() {
                                @Override
                                public ServerCall.Listener<ReqT> startCall(ServerCall<ReqT, RespT> c, Metadata h) {
                                    // ⚠️ 必须调 inner.interceptCall(...)，**不能**把 next 直接透传
                                    //    ⇒ 透传 ⇒ inner 永远不被调用 ⇒ 整条内层链静默消失（零报错）
                                    return delegate.interceptCall(c, h, next);
                                }
                            };
                    return layered.interceptCall(call, headers, delegateToInner);
                }
            };
        }
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                ServerCallHandler<ReqT, RespT> delegate = new ServerCallHandler<ReqT, RespT>() {
                    @Override
                    public ServerCall.Listener<ReqT> startCall(ServerCall<ReqT, RespT> c, Metadata h) {
                        // ⚠️ 必须调 inner.interceptCall(...)，**不能**把 next 直接透传
                        return inner.interceptCall(c, h, next);
                    }
                };
                return outer.interceptCall(call, headers, delegate);
            }
        };
    }
}
