package com.rag.favorite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rag.favorite.controller.FavoriteController;
import com.rag.favorite.domain.service.FavoriteDomainService;
import com.rag.favorite.grpc.FavoriteGrpcService;
import com.rag.favorite.service.FavoriteApiService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 🔴 favorite 域的**上下文冒烟测试** —— 证明**合并后的这一个进程真的能起起来**。
 *
 * <h2>为什么必须存在这一条（而不是让它留成空壳）</h2>
 * <p>AGENTS.md 的纪律是「<b>0 空壳</b>」⇒ 一个只有类名、没有断言的测试文件
 * **不提供任何证据**，却占着「有测试」的名额。
 * <p>⇒ 而本域恰恰需要一条**「上下文能起来」**的证据，因为它是 **api + rpc 合并进程**（D1 + D7）⇒
 * ⇒ 合并的典型失败面就是「某个面的 Bean 在合并后装配不上」⇒
 * ⇒ **2026-11-04 真启动实测就抓到过两类**：
 * <ul>
 *   <li>🔴 共享层 `ClientDisconnectProbe` 那类「<b>配置里没写 ⇒ 机制没开</b>」的静默失效
 *       （配套台账 §五·5.7 的 6 条）；</li>
 *   <li>🔴 手写的 {@code wiring/FavoriteNacosRegistration} 依赖
 *       {@code NacosServiceManager} ⇒ Nacos 不可达时它 NPE ⇒ <b>上下文直接起不来</b>
 *       （B 侧同款缺口 ⇒ 继承 ⇒ 本域不修 ⇒ 测试侧用
 *       {@link com.rag.favorite.support.NacosStubConfiguration} 绕开）。</li>
 * </ul>
 * ⇒ ⇒ 所以这条用例把「上下文起得来 + 两个面的入口 Bean 都在」钉成**可执行断言**。
 *
 * <h2>⚠️ 本类<b>不</b>验业务行为</h2>
 * <p>业务行为由 {@code http/FavoriteApiRealTomcatGateTest}（真 Tomcat）与
 * {@code mapper/*}（真 PG）负责 ⇒ ⇒ 本类只管「**装得上**」。
 *
 * <p>⚠️ <b>DB 刻意不可达</b>：本类不碰业务表 ⇒ 池不建连、Flyway 不跑
 * ⇒ ⇒ 断言与数据库状态无关。
 */
@SpringBootTest(
        classes = {FavoriteServiceApplication.class,
                com.rag.favorite.support.NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "grpc.client.user-rpc.address=static://127.0.0.1:65535",
                "grpc.client.user-rpc.negotiation-type=plaintext",
                "grpc.client.article-rpc.address=static://127.0.0.1:65535",
                "grpc.client.article-rpc.negotiation-type=plaintext",
                "grpc.server.port=0",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                // 🔴 C 侧 FavoriteApiProperties 的密钥守卫是 fail-closed 的（缺值 ⇒ 启动失败）
                "favorite-api.user-auth.access-secret=test-only-favorite-jwt-secret-at-least-32-bytes",
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/first_db",
                "spring.datasource.username=admin",
                "spring.datasource.password=password",
                "spring.datasource.hikari.initialization-fail-timeout=-1",
                "spring.datasource.hikari.connection-timeout=1000",
                "spring.datasource.hikari.minimum-idle=0",
                "spring.datasource.hikari.maximum-pool-size=1",
                "spring.flyway.enabled=false",
                // 事实链路的三个后台组件关掉（它们会自己往 PG 上写语句 ⇒ 污染读数）
                "favorite.fact.dispatch.enabled=false",
                "favorite.fact.authority.enabled=false",
                "favorite.fact.legacy-preflight.enabled=false"
        })
class FavoriteServiceApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("🔴 合并进程（api + rpc）能起来 ⇒ 且两个面的入口 Bean 都在")
    void mergedApplicationContextLoadsWithBothFaces() {
        // ① api 面
        assertNotNull(context.getBean(FavoriteController.class),
                "api 面的 Controller 必须在容器里（7 个 HTTP 端点的入口）");
        assertNotNull(context.getBean(FavoriteApiService.class),
                "api 面的 Service 必须在容器里（B 侧 7 个 api Service 合并为 1 个 · D2）");
        // ② rpc 面
        assertNotNull(context.getBean(FavoriteGrpcService.class),
                "rpc 面的 gRPC Service 必须在容器里（7 个 rpc 方法的入口）");
        assertNotNull(context.getBean(FavoriteDomainService.class),
                "领域服务必须在容器里（D2 域内直连 ⇒ api 面与 rpc 面共用它）");
        // ③ 合并的证据：两个面**在同一个容器里**
        assertTrue(context.containsBean("favoriteServiceApplication"),
                "合并进程的入口 Bean 必须在容器里 ⇒ 证明确实是**一个**进程而不是两个");
    }

    @Test
    @DisplayName("🔴 没有 @Import 清单 ⇒ 全部装配来自共享层的 @AutoConfiguration")
    void noManualImportList() {
        // ⚠️ 这是 **A5 断言的域侧同款**：D3 规定「共享能力进 common 且用 @AutoConfiguration，
        //    **不写 @Import 清单**」⇒ ⇒ 域侧若出现 @Import ⇒ 装配面就跑偏了。
        // ⇒ 这里读**入口类的注解**（不是读整个包）⇒ 精确且不误伤域内其它类的 @Import。
        boolean hasImport = java.util.Arrays.stream(
                        FavoriteServiceApplication.class.getAnnotations())
                .anyMatch(a -> a.annotationType().getName().equals("org.springframework.context.annotation.Import"));
        assertTrue(!hasImport,
                "入口类上不得有 @Import ⇒ 装配必须全部来自共享层的 @AutoConfiguration（D3）");
    }

    @Test
    @DisplayName("🔴 出向 gRPC 客户端硬上界在容器里生效（capMillis=2000 · 来自生产 yaml）")
    void outboundDeadlineCapIsBoundFromProductionYaml() {
        com.rag.common.protection.ProtectionProperties props =
                context.getBean(com.rag.common.protection.ProtectionProperties.class);
        assertEquals(2000L, props.getOutboundDeadline().getCapMillis(),
                "生产 yaml 的 rag.protection.outbound-deadline.cap-millis 必须是 2000"
                        + "（= B 侧 zrpc 默认；不配 ⇒ 出向零期限 ⇒ 下游不可达时永久挂起）");
        assertNotNull(context.getBean(com.rag.common.protection.OutboundDeadlineInterceptor.class),
                "共享层的出向 deadline 拦截器必须在容器里 ⇒ 它才会装到**每个**出向调用上");
    }
}
