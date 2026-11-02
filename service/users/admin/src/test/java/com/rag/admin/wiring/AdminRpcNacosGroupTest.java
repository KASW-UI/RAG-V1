package com.rag.admin.wiring;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import net.devh.boot.grpc.server.config.GrpcServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔴 DRIFT-G1-001 守门 —— admin-rpc 注册到 Nacos 的 group 必须<b>来自配置</b>，
 * 不能是写死的常量。
 *
 * <h2>B 出处（本类的行为基线）</h2>
 * <p><b>绝对路径</b>：{@code /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcNacosGroupTest.java}
 * <pre>
 *   B: RAG  service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcNacosGroupTest.java
 *      :66-96    ① 配置被真正消费（group = spring.cloud.nacos.discovery.group）
 *      :102-120  ② 默认行为零变化（不配 ⇒ 仍是 USER_GROUP）
 *      :126-138  ③ application.yml 里那一行还在
 * </pre>
 *
 * <p>🔴 <b>本类由 B 侧同名类移植，期望值沿用 B 侧（D13）</b> ——
 * group 的三个期望值（{@code G1_GROUP} / {@code USER_GROUP} / yml 里含 {@code NACOS_GROUP}）
 * <b>一字未改</b>。
 *
 * <h2>Go 出处</h2>
 * <pre>
 *   service/user/admin/rpc/etc/admin.yaml:9-12
 *       Etcd:
 *         Hosts: [etcd:2379]
 *         Key: admin.rpc           ← 服务发现键是<b>配置项</b>，部署时改 yaml 就换命名空间
 * </pre>
 *
 * <h2>修前</h2>
 * <p>{@code NacosRegistration} 把 group <b>写死</b>成 {@code "USER_GROUP"}
 * ⇒ {@code NACOS_GROUP} 这个环境变量对 admin-rpc <b>完全无效</b>；
 * 而 admin-api 的 {@code application.yml} 写的是 {@code group: ${NACOS_GROUP:USER_GROUP}}（<b>读配置</b>）
 * ⇒ ⇒ 部署一旦设了不同的 {@code NACOS_GROUP} ⇒ <b>rpc 注册到 A 组、api 去 B 组找</b>
 * ⇒ ⇒ <b>admin-api 直接进程退出</b>（真启动实测：{@code No servers found for admin-rpc}）。
 * <p>⇒ 同族教训（{@code DRIFT-FOLLOW-004} 修的是 {@code NACOS_ADDR/PORT} 那一维）：
 * <b>「配置项存在」≠「配置项被消费」</b>。
 *
 * <h2>本测试守什么</h2>
 * <ol>
 *   <li><b>配置被真正消费</b>：{@code spring.cloud.nacos.discovery.group=G1_GROUP} ⇒
 *       注册调用里出现的 group <b>必须</b>是 {@code G1_GROUP}
 *       ⇒ 这一条用<b>真实的 {@link NacosRegistration} ＋ <b>真实的 Spring {@code @Value} 解析</b>
 *       ⇒ 断言的是「{@code NamingService.registerInstance} 的第 2 个实参」；
 *       ⚠️ 只 mock 最外层的 {@code NamingService}（Nacos 客户端的真实边界），
 *       <b>不用 mock 被测类本身</b> ⇒ 否则就变成「自己 new 一个、自己传值、自己断言」的假绿。</li>
 *   <li><b>默认行为零变化</b>：不配该属性时解析结果仍是 {@code USER_GROUP}
 *       （= 修前的写死值）⇒ 本次修复<b>不改变</b>默认部署的行为。</li>
 *   <li><b>yml 里那一行还在</b>：删掉 {@code application.yml} 里的
 *       {@code group: ${NACOS_GROUP:USER_GROUP}} ⇒ 属性消失 ⇒ 又退回「写死」形态
 *       ⇒ 本条守住这个「配置项被删 ⇒ 环境变量失效」的回归。</li>
 * </ol>
 *
 * <h2>⚠️ 本测试的证据边界</h2>
 * <p>它证明的是「<b>配置 → 代码 → Nacos 客户端调用</b>」这一段（装配层）。
 * <b>真 Nacos 是否真的按这个 group 收下实例</b>，由 G1 lane 的<b>真启动 A/B 实验</b>覆盖
 * （真 Nacos + 真进程 + 真 HTTP 请求）⇒ 两者合起来才是完整证据链。
 *
 * <h2>⚠️ 移植说明（C 侧 ↔ B 侧的差异，逐条登记）</h2>
 * <ol>
 *   <li><b>包名</b>（D7）：{@code com.rag.users.admin.rpc} → {@code com.rag.admin.wiring}；
 *       B 侧 {@code com.rag.users.admin.rpc.bootstrap.NacosRegistration}
 *       → C 侧 {@code com.rag.admin.wiring.NacosRegistration}（<b>同包 ⇒ 无需 import</b>）。</li>
 *   <li><b>🔴 注册用的服务名：B 侧 {@code admin-rpc} ⇒ C 侧 {@code admin-rpc}（逐字一致）</b>
 *       —— <b>2026-11-02 修正</b>：本类初版曾按「D1 已签字的偏离」写成 {@code admin-service}，
 *       该前提<b>已被推翻</b>（message 域按 B 侧逐字寻址 {@code discovery:///admin-rpc}
 *       ⇒ 注册名不叫 {@code admin-rpc} ⇒ <b>寻址落空、链路断</b>）：
 *       <ul>
 *         <li>B 出处：B 侧 {@code admin-rpc/.../bootstrap/NacosRegistration.java:88}
 *             写死 {@code String serviceName = "admin-rpc";}；</li>
 *         <li>C 侧落点：{@code wiring/NacosRegistration#register()} 里的
 *             {@code private static final String SERVICE_NAME = "admin-rpc";}
 *             （<b>与 B 侧逐字一致</b>）；</li>
 *         <li>⚠️ <b>不要把 {@code spring.application.name} 混进来</b>：它是<b>进程名</b>
 *             （Prometheus 的 {@code application} 标签）= 本仓 artifactId 惯例
 *             {@code admin-service}（兄弟域 user 同款：app name={@code user-service}
 *             ＋ 注册名={@code user-rpc}）⇒ <b>两个轴刻意分叉</b>，
 *             登记在 {@code docs/Intentional_Deviations/admin/_signed-deviations.md}
 *             的 {@code DEV-ADMIN-APPNAME-003}；</li>
 *         <li>⚠️ <b>本类的语义目标是「group 来自配置」，不是「服务名叫什么」</b>
 *             ⇒ 服务名只作为 {@code registerInstance} 第 1 个实参的<b>伴随值</b>出现；
 *             但既然它已被断言，<b>就必须是 B 侧逐字值</b>（AGENTS.md §3.3.2：
 *             以「B 侧: …」形式写的断言若前提已变 ⇒ 必须同步修正，
 *             否则就是「拿没人校过的镜子照自己」）。</li>
 *       </ul></li>
 *   <li><b>group 的三个期望值逐字沿用 B 侧</b>：{@code G1_GROUP} / {@code USER_GROUP}
 *       （= 修前写死值）/ yml 里那一行必须含 {@code NACOS_GROUP}
 *       ⇒ C 侧 {@code application.yml:41} 是 {@code group: ${NACOS_GROUP:USER_GROUP}} ✓。</li>
 *   <li><b>yml 读取路径</b>：B 侧读的是 admin-rpc 模块自己的 {@code application.yml}；
 *       C 侧 D1 把两份 yml 合并成一份（{@code service/users/admin/src/main/resources/application.yml}）
 *       ⇒ {@code ClassPathResource("application.yml")} 命中的是<b>同一份</b>文件
 *       （本仓 {@code common} / {@code users-common} 两个 jar 内<b>没有</b> {@code application.yml}
 *       ⇒ classpath 上只有这一份 ⇒ 读到的就是 admin 域那份）。</li>
 * </ol>
 */
class AdminRpcNacosGroupTest {

    /**
     * <b>注册用的服务名</b>。
     *
     * <p>🔴 <b>B 侧逐字值 = {@code "admin-rpc"}，C 侧必须逐字相同</b>
     * （B 出处：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/bootstrap/NacosRegistration.java:88}
     * ⇒ {@code String serviceName = "admin-rpc";}）。
     *
     * <p>⚠️ <b>2026-11-02 修正</b>：本类初版此处写的是 {@code "admin-service"}，
     * 理由是「D1 已签字的偏离」⇒ <b>该前提已被推翻</b>：message 域的 gRPC 客户端
     * 按 B 侧逐字寻址 {@code discovery:///admin-rpc} ⇒ 注册名一旦不是 {@code admin-rpc}
     * ⇒ <b>寻址落空</b> ⇒ 跨域链路断在服务发现这一步。
     *
     * <p>⚠️ <b>不要与 {@code spring.application.name} 混淆</b>：后者是<b>进程名</b>
     * （Prometheus 的 {@code application} 标签）= 本仓 artifactId 惯例
     * {@code admin-service} ⇒ <b>两个轴刻意分叉</b>，不是同一个值。
     *
     * <p>⚠️ <b>本类的断言目标是「group 来自配置」</b>，服务名只是伴随值
     * ⇒ 但既然它已被断言，就必须钉在 B 侧逐字值上。
     */
    private static final String SERVICE_NAME = "admin-rpc";

    /** 修前写死的 group —— 默认行为必须与它逐字相同。 */
    private static final String LEGACY_HARDCODED_GROUP = "USER_GROUP";

    // ────────────────────────────────────────────────────────────────────
    // 1. 配置被真正消费
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 DRIFT-G1-001：注册用的 group = 配置的 group（不再是写死的常量）")
    void registrationUsesConfiguredGroup() {
        NamingService namingService = mock(NamingService.class);
        NacosServiceManager manager = mock(NacosServiceManager.class);
        when(manager.getNamingService()).thenReturn(namingService);

        new ApplicationContextRunner()
                .withPropertyValues("spring.cloud.nacos.discovery.group=G1_GROUP")
                .withBean(NacosServiceManager.class, () -> manager)
                .withBean(GrpcServerProperties.class, GrpcServerProperties::new)
                .withBean(NacosRegistration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(NacosRegistration.class).register();

                    verify(namingService).registerInstance(
                            eq(SERVICE_NAME), eq("G1_GROUP"), any(Instance.class));
                });
    }

    // ────────────────────────────────────────────────────────────────────
    // 2. 默认行为零变化
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 默认行为零变化：不配 group 时仍是 USER_GROUP（= 修前的写死值）")
    void defaultGroupIsUnchanged() {
        NamingService namingService = mock(NamingService.class);
        NacosServiceManager manager = mock(NacosServiceManager.class);
        when(manager.getNamingService()).thenReturn(namingService);

        new ApplicationContextRunner()
                .withBean(NacosServiceManager.class, () -> manager)
                .withBean(GrpcServerProperties.class, GrpcServerProperties::new)
                .withBean(NacosRegistration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(NacosRegistration.class).register();

                    verify(namingService).registerInstance(
                            eq(SERVICE_NAME), eq(LEGACY_HARDCODED_GROUP), any(Instance.class));
                });
    }

    // ────────────────────────────────────────────────────────────────────
    // 3. yml 里那一行还在
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ application.yml 里仍声明了 group（删掉 ⇒ NACOS_GROUP 又失效）")
    void applicationYmlStillDeclaresGroup() {
        List<PropertySource<?>> sources = loadApplicationYml();
        assertThat(sources).isNotEmpty();

        Object raw = sources.get(0).getProperty("spring.cloud.nacos.discovery.group");
        assertThat(raw)
                .as("admin-rpc 的 application.yml 必须保留 spring.cloud.nacos.discovery.group，"
                        + "它同时是「NACOS_GROUP 环境变量」的入口和「默认 group」的来源")
                .isNotNull();
        assertThat(String.valueOf(raw)).contains("NACOS_GROUP");
    }

    // ────────────────────────────────────────────────────────────────────
    // 4. 注册名（2026-11-02 新增覆盖口）
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 注册名默认逐字等于 B 侧的 `admin-rpc`（不配置 ⇒ 行为零变化）")
    void defaultServiceNameIsBsideLiteral() {
        NamingService namingService = mock(NamingService.class);
        NacosServiceManager manager = mock(NacosServiceManager.class);
        when(manager.getNamingService()).thenReturn(namingService);

        new ApplicationContextRunner()
                .withBean(NacosServiceManager.class, () -> manager)
                .withBean(GrpcServerProperties.class, GrpcServerProperties::new)
                .withBean(NacosRegistration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(NacosRegistration.class).register();

                    // ⚠️ 这条断言就是「跨域寻址键」的守门：
                    //   message 域按 B 侧逐字寻址 `discovery:///admin-rpc`
                    //   ⇒ 注册名一旦漂回 `admin-service`，寻址就落空（业务码塌 1015）。
                    verify(namingService).registerInstance(
                            eq("admin-rpc"), eq(LEGACY_HARDCODED_GROUP), any(Instance.class));
                });
    }

    @Test
    @DisplayName("✅ 注册名可被覆盖（E-4 对拍环境：B/C 共用一个 Nacos ⇒ 必须错开服务名）")
    void serviceNameCanBeOverriddenForParityIsolation() {
        NamingService namingService = mock(NamingService.class);
        NacosServiceManager manager = mock(NacosServiceManager.class);
        when(manager.getNamingService()).thenReturn(namingService);

        new ApplicationContextRunner()
                .withPropertyValues("spring.cloud.nacos.discovery.service=admin-service-parity")
                .withBean(NacosServiceManager.class, () -> manager)
                .withBean(GrpcServerProperties.class, GrpcServerProperties::new)
                .withBean(NacosRegistration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(NacosRegistration.class).register();

                    // ⚠️ 这条证明「覆盖口是真的被消费的」（AGENTS.md §3.3：
                    //   「配置项存在」≠「配置项被消费」⇒ 必须断言到 registerInstance 的实参）。
                    verify(namingService).registerInstance(
                            eq("admin-service-parity"), eq(LEGACY_HARDCODED_GROUP), any(Instance.class));
                    // 且**不再**注册成默认名（否则同服务名下会有两个实例 ⇒ 对拍可能打到错误进程）
                    verify(namingService, never()).registerInstance(
                            eq("admin-rpc"), anyString(), any(Instance.class));
                });
    }

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    private static List<PropertySource<?>> loadApplicationYml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("admin-rpc-application", new ClassPathResource("application.yml"));
        } catch (IOException e) {
            throw new IllegalStateException("读不到 classpath:application.yml", e);
        }
    }
}
