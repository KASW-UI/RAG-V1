package com.rag.user.wiring;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <p><b>🔴 Nacos 注册面守门</b> —— 注册名、group、端口三样都必须是对的。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   B: service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/bootstrap/NacosRegistration.java
 *   B 侧同形态测试：service/users/user/user-rpc/src/test/java/.../UserRpcNacosGroupTest.java
 * </pre>
 *
 * <h2>本类守的 4 条</h2>
 * <ol>
 *   <li><b>🔴 注册名 = {@code user-rpc}（L1 契约）</b>：B 侧硬编码的就是这个名，
 *       且它是<b>活的</b> —— B 侧 {@code docker-compose.yaml:80} 的 nacos 健康检查查的正是
 *       {@code serviceName=user-rpc}；B 侧 {@code admin-api/application.yml:128} 的
 *       {@code grpc.client.user-rpc.address: discovery:///user-rpc} 也指着这个名字。
 *       ⇒ ⇒ 合并成单进程**不能**把它改成 {@code user-service}。</li>
 *   <li><b>🔴 group 来自配置</b>（B 侧 {@code DRIFT-G1-001}）：写死 {@code USER_GROUP} ⇒
 *       {@code NACOS_GROUP} 环境变量<b>完全无效</b> ⇒ 而 yml 里写的是
 *       {@code group: ${NACOS_GROUP:USER_GROUP}}（读配置）⇒ ⇒ 部署改 group ⇒
 *       「注册到 A 组、调用方去 B 组找」⇒ 恒 "No servers found" ⇒ 所有业务码塌成 1015。</li>
 *   <li><b>默认行为零变化</b>：不配 group 时解析结果仍是 {@code USER_GROUP}（= 修前的写死值）。</li>
 *   <li><b>yml 里那一行还在</b>：删掉 {@code application.yml} 里的
 *       {@code spring.cloud.nacos.discovery.group} ⇒ 属性消失 ⇒ 又退回「写死」形态。</li>
 * </ol>
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>它证明的是「<b>配置 → 代码 → Nacos 客户端调用</b>」这一段（装配层）。
 * 「<b>真 Nacos 是否真的按这个 group 收下实例</b>」属于真启动 A/B 实测的范畴
 * ⇒ ⇒ 本类<b>不冒充</b>该证据，只守装配层。
 *
 * <p>⚠️ <b>只 mock 最外层的 {@link NamingService}</b>（Nacos 客户端的真实边界），
 * <b>不 mock 被测类本身</b> ⇒ 否则就变成「自己 new 一个、自己传值、自己断言」的假绿。
 */
class UserNacosRegistrationWiringTest {

    /** 注册名（L1 · 与 B 侧逐字一致）。 */
    private static final String SERVICE_NAME = "user-rpc";

    /** 修前写死的 group —— 默认行为必须与它逐字相同。 */
    private static final String LEGACY_HARDCODED_GROUP = "USER_GROUP";

    // ────────────────────────────────────────────────────────────────────
    // 1. 注册名 + group + 端口
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 注册名 = user-rpc（L1：B 侧 docker-compose 健康检查与 admin-api 寻址都指着它）")
    void registrationUsesBServiceName() {
        NamingService namingService = mock(NamingService.class);
        NacosServiceManager manager = mock(NacosServiceManager.class);
        when(manager.getNamingService()).thenReturn(namingService);

        new ApplicationContextRunner()
                .withBean(NacosServiceManager.class, () -> manager)
                .withBean(GrpcServerProperties.class, () -> {
                    GrpcServerProperties props = new GrpcServerProperties();
                    props.setPort(8081);
                    return props;
                })
                .withBean(NacosRegistration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(NacosRegistration.class).register();

                    verify(namingService).registerInstance(eq(SERVICE_NAME), any(String.class), any(Instance.class));
                });
    }

    @Test
    @DisplayName("🔴 端口 = gRPC server 端口（不是 HTTP 端口）")
    void registrationUsesGrpcPort() {
        NamingService namingService = mock(NamingService.class);
        NacosServiceManager manager = mock(NacosServiceManager.class);
        when(manager.getNamingService()).thenReturn(namingService);

        new ApplicationContextRunner()
                .withBean(NacosServiceManager.class, () -> manager)
                .withBean(GrpcServerProperties.class, () -> {
                    GrpcServerProperties props = new GrpcServerProperties();
                    props.setPort(8081);
                    return props;
                })
                .withBean(NacosRegistration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(NacosRegistration.class).register();

                    verify(namingService).registerInstance(eq(SERVICE_NAME), any(String.class),
                            org.mockito.ArgumentMatchers.argThat(
                                    (Instance i) -> i.getPort() == 8081));
                });
    }

    // ────────────────────────────────────────────────────────────────────
    // 2. group 来自配置（B 侧 DRIFT-G1-001）
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

                    verify(namingService).registerInstance(eq(SERVICE_NAME), eq("G1_GROUP"), any(Instance.class));
                });
    }

    // ────────────────────────────────────────────────────────────────────
    // 3. 默认行为零变化
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
    // 4. yml 里那一行还在
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ application.yml 里仍声明了 group（删掉 ⇒ NACOS_GROUP 又失效）")
    void applicationYamlStillDeclaresGroup() {
        List<PropertySource<?>> sources = loadApplicationYaml();
        assertThat(sources).isNotEmpty();

        Object raw = sources.get(0).getProperty("spring.cloud.nacos.discovery.group");
        assertThat(raw)
                .as("user 域的 application.yml 必须保留 spring.cloud.nacos.discovery.group，"
                        + "它同时是「NACOS_GROUP 环境变量」的入口和「默认 group」的来源")
                .isNotNull();
        assertThat(String.valueOf(raw)).contains("NACOS_GROUP");
    }

    @Test
    @DisplayName("✅ 自动注册被关掉 ⇒ 全进程恰好 1 个 Nacos 注册项（映射表锁决策：2 个/子域 → 1 个/子域）")
    void autoRegistrationIsDisabled() {
        List<PropertySource<?>> sources = loadApplicationYaml();
        assertThat(sources).isNotEmpty();

        Object raw = sources.get(0).getProperty("spring.cloud.nacos.discovery.register-enabled");
        assertThat(raw)
                .as("不关掉自动注册 ⇒ 合并进程会再冒出一个 user-service@HTTP 端口 的条目 ⇒ 变成 2 个 ⇒ 违反锁决策")
                .isNotNull();
        assertThat(Boolean.parseBoolean(String.valueOf(raw))).isFalse();
    }

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    private static List<PropertySource<?>> loadApplicationYaml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("user-application", new ClassPathResource("application.yml"));
        } catch (IOException e) {
            throw new IllegalStateException("读不到 classpath:application.yml", e);
        }
    }
}
