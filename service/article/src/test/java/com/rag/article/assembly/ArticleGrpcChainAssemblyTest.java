package com.rag.article.assembly;

import com.rag.article.support.AssemblyTestBase;
import com.rag.common.protection.RpcLayer;
import com.rag.common.protection.RpcProtectionChain;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.serverfactory.GrpcServerLifecycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <p><b>A3 · gRPC 拦截链<b>真挂到 server 上</b>的运行期证据</b>。
 *
 * <h2>🔴 为什么「读注解」证明不了这条判据</h2>
 * <p>AGENTS.md §3.3.3 明列：「<b>不得凭注解名称断定机制已启用</b>」，
 * 且 devh starter 的机制是<b>运行期动态装配</b>：
 * <pre>
 * AbstractGrpcServerFactory#configure(builder)
 *    for (GrpcServerConfigurer c : serverConfigurers) { c.accept(builder); }
 *      └─ seaGrpcServerConfigurer: server -&gt; { server.intercept(chain); }   ← 挂载点
 * </pre>
 * ⇒ 「{@code @Bean seaGrpcServerConfigurer} 存在」只说明<b>配置类被注册了</b>；
 * 它<b>不代表</b> {@code server.intercept(...)} 真被调过 ——
 * 而这正是 AGENTS.md §3.3.3 说的「<b>四类静默失效</b>」的温床。
 * <p>⇒ ⇒ 本类<b>真启动</b>服务，然后从<b>运行中的 server 对象</b>里读出它的
 * <b>interceptor 登记表</b>，并<b>逐层读出链名</b>。
 *
 * <h2>B 出处（逐条 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ 两侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th><th>C 侧</th></tr>
 *   <tr><td>gRPC 拦截链的挂载点（{@code server.intercept(...)}）</td>
 *       <td>{@code article-rpc/src/main/java/com/rag/article/article/rpc/ArticleRpcApplication.java:123-132}
 *           （B 侧 {@code rpcInterceptorConfigurer}）</td>
 *       <td>共享层 {@code common/src/main/java/com/rag/common/autoconfigure/ProtectionAutoConfiguration.java:230-243}
 *           （{@code seaGrpcServerConfigurer}）</td></tr>
 *   <tr><td>链序 {@code [recover, stat, prometheus, breaker, shedding]}（+ 业务层）</td>
 *       <td>Go {@code zrpc/server.go:144-163}（B 侧 javadoc 转录）</td>
 *       <td>{@code common/src/main/java/com/rag/common/protection/RpcProtectionChain.java:78-108}</td></tr>
 *   <tr><td>「域内不得再来一份挂载点」（否则挂 2 条链）</td>
 *       <td>—</td>
 *       <td>{@code ArticleServiceApplication.java:48-52}（本仓<b>不写</b>这条 bean）</td></tr>
 * </table>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把共享层 {@code seaGrpcServerConfigurer} 的 {@code server.intercept(chain)} 去掉
 *       ⇒ 用例 ① <b>立刻红</b>（运行中 server 的 interceptor 登记表变空）；</li>
 *   <li>把链的 {@code @Order} 改乱 ⇒ 用例 ① 的<b>链序断言</b>立刻红；</li>
 *   <li>在 article 域内再加一份 {@code GrpcServerConfigurer}
 *       ⇒ 用例 ① 会看到<b>两条</b>链 ⇒ 断言「恰好一条生产链」立刻红。</li>
 * </ul>
 */
class ArticleGrpcChainAssemblyTest extends AssemblyTestBase {

    /** 生产链的期望层序（最外 → 最内）。 */
    private static final List<String> EXPECTED_LAYERS =
            List.of("recover", "stat", "prometheus", "breaker", "shedding", "business");

    @Autowired
    private ApplicationContext context;

    // ==================================================================
    // ① A3 · 运行中的 gRPC server 的 interceptor 登记表非空 + 链序正确
    // ==================================================================

    @Test
    @DisplayName("🔴 A3 运行期证据：真启动的 gRPC server 的 interceptor 登记表非空，"
            + "且其中挂着生产链（层序 recover→stat→prometheus→breaker→shedding→business）")
    void grpcInterceptorChainIsReallyAttachedToTheRunningServer() throws Exception {
        // ① 从**容器**里取出 devh 的生命周期 bean（它持有**正在运行的** server）
        GrpcServerLifecycle lifecycle = context.getBean(GrpcServerLifecycle.class);
        assertNotNull(lifecycle, "容器里必须有 devh 的 GrpcServerLifecycle");

        // ② 反射读出那个**运行中的** Server 实例
        Field serverField = ReflectionUtils.findField(GrpcServerLifecycle.class, "server");
        assertNotNull(serverField, "devh 3.1.0 的 GrpcServerLifecycle 必须有 server 字段");
        ReflectionUtils.makeAccessible(serverField);
        Server server = (Server) ReflectionUtils.getField(serverField, lifecycle);
        assertNotNull(server, "生命周期 bean 必须已经创建了 server（= 真启动过）");

        // 🔴 场景有效性断言①：**真启动**的证据 —— 绑到了真实端口
        assertTrue(server.getPort() > 0,
                "gRPC server 必须绑到真实端口（grpc.server.port=0 ⇒ OS 分配 ⇒ 端口必然 > 0）；实际 = "
                        + server.getPort());

        // ③ 读出 interceptor 登记表（grpc-core 1.63.0 的 ServerImpl#interceptors，private final）
        ServerInterceptor[] interceptors = readInterceptors(server);
        assertNotNull(interceptors, "读不到 server 的 interceptor 登记表（grpc-core 版本变了？）");
        assertTrue(interceptors.length > 0,
                "🔴 A3：运行中的 gRPC server 的 interceptor 登记表不得为空"
                        + " ⇒ 说明 GrpcServerConfigurer 真调了 server.intercept(...)");

        // ④ 🔴 最锋利的一条：挂上去的那个拦截器**必须是由共享层的 RpcProtectionChain 造出来的**
        //   ⇒ 它<b>只可能</b>来自 seaGrpcServerConfigurer 里的 server.intercept(chain)
        //   ⇒ ⇒ 这就是「GrpcServerConfigurer 真调了 server.intercept(...)」的运行期证据。
        //   ⚠️ **判据不能用 `getDeclaringClass()`** —— 实测（2026-11-03，JDK 21）：
        //   **匿名类的 `getDeclaringClass()` 返回 `null`，不是外层类**
        //   （`Class#getDeclaringClass` 只对「具名成员类」返回外层类；匿名类返 null）
        //   ⇒ ⇒ 用 `getDeclaringClass() == RpcProtectionChain.class` 判**恒 false** ⇒ 是一条**假判据**。
        //   ⇒ 改用**类名前缀**判定：匿名类的二进制名是 `RpcProtectionChain$N`（N 递增）。
        int chainCount = 0;
        for (ServerInterceptor interceptor : interceptors) {
            if (interceptor.getClass().getName().startsWith(RpcProtectionChain.class.getName() + "$")) {
                chainCount++;
            }
        }
        assertEquals(1, chainCount,
                "🔴 同一进程里只能挂一条生产链（域内若再加一份 GrpcServerConfigurer ⇒ 会挂 2 条）；"
                        + "实际 interceptors = " + java.util.Arrays.toString(interceptors)
                        + "；容器里的 ServerInterceptor Bean = " + describeInterceptorBeans());

        // ⑤ 保护链的**层序**（行为语义：recover 最外层、shedding 最内层、业务观测层最里）
        //   ⚠️ 只取 RpcLayer 那一组来 compose —— 因为**整条**链的最外层是 devh 的
        //   GrpcRequestScope（它不是 RpcLayer），整条 compose 出来的链 layerNames() 读不到层名。
        List<ServerInterceptor> layerBeans = new ArrayList<>();
        context.getBeanProvider(ServerInterceptor.class).orderedStream()
                .filter(bean -> bean instanceof RpcLayer)
                .forEach(layerBeans::add);
        assertEquals(EXPECTED_LAYERS, RpcProtectionChain.layerNames(
                        RpcProtectionChain.compose(layerBeans)),
                "🔴 A3：保护链的层序必须逐层正确"
                        + "（顺序是行为语义：recover 必须最外层、shedding 必须最内层）");
    }

    // ==================================================================
    // ② 真调用：证明这个 server 真的在服务（不是「建了对象但没人用」）
    // ==================================================================

    @Test
    @DisplayName("🔴 A3 运行期证据：真起动的 gRPC server **真的在监听端口**"
            + "（用真 socket 连一次，不是「建了对象但没人用」）")
    void runningGrpcServerActuallyListens() throws Exception {
        GrpcServerLifecycle lifecycle = context.getBean(GrpcServerLifecycle.class);
        Field serverField = ReflectionUtils.findField(GrpcServerLifecycle.class, "server");
        assertNotNull(serverField);
        ReflectionUtils.makeAccessible(serverField);
        Server server = (Server) ReflectionUtils.getField(serverField, lifecycle);
        assertNotNull(server);

        // 🔴 真 socket 连一次 ⇒ 证明「server 真的在收连接」（不是只建了个对象）
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", server.getPort()), 5000);
            assertTrue(socket.isConnected(), "gRPC server 必须真的在监听端口");
            assertTrue(socket.getLocalPort() > 0);
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 描述容器里全部 {@link ServerInterceptor} Bean（名字 + 实现类 + 排序值）——
     * 用于在断言失败时直接看出「谁排在了最外层」。
     */
    private String describeInterceptorBeans() {
        StringBuilder sb = new StringBuilder();
        // ⚠️ 用**同一套** orderedStream（共享层的 seaGrpcServerConfigurer 用的就是它）
        //   ⇒ 这里打印出来的顺序 = 真正被 compose 的顺序
        context.getBeanProvider(ServerInterceptor.class).orderedStream()
                .forEach(bean -> sb.append(bean.getClass().getSimpleName())
                        .append("(isRpcLayer=").append(bean instanceof RpcLayer).append(") "));
        return sb.toString();
    }

    /**
     * 读出 {@code io.grpc.internal.ServerImpl} 的 interceptor 登记表。
     *
     * <p>⚠️ grpc-core 1.63.0 里 {@code ServerImpl#interceptors} 是 {@code private final ServerInterceptor[]}，
     * <b>没有公开的读取入口</b> ⇒ 只能反射。
     * ⇒ 这里<b>按类型</b>找字段（不写死字段名）⇒ 版本漂移时给出明确报错而不是静默返回空。
     */
    private static ServerInterceptor[] readInterceptors(Server server) {
        Class<?> type = server.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() == ServerInterceptor[].class) {
                    ReflectionUtils.makeAccessible(field);
                    return (ServerInterceptor[]) ReflectionUtils.getField(field, server);
                }
            }
            type = type.getSuperclass();
        }
        fail("读不到 server 的 interceptor 登记表（grpc-core 版本漂移？server 类型 = "
                + server.getClass().getName() + "）");
        return null;
    }
}
