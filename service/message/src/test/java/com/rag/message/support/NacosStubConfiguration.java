package com.rag.message.support;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.naming.NamingService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * <p><b>Nacos 桩</b> —— 让 message 域的测试能起真实 Spring 上下文，而<b>不连 Nacos</b>。
 *
 * <h2>⚠️ 为什么 message 域<b>必须</b>有它（与 users/admin 相比多一条理由）</h2>
 * <p>message 域是<b>本仓第一个带 {@code @GrpcClient} 的域</b>
 * （{@code client/UserRpcClient} 的 {@code @GrpcClient("user-rpc")} ＋
 * {@code client/AdminRpcClient} 的 {@code @GrpcClient("admin-rpc")}）
 * ⇒ ⇒ devh 的 {@code GrpcClientBeanPostProcessor} 会<b>立刻</b>为这两个通道建
 * {@code ManagedChannel} ⇒ ⇒ 建通道要过
 * {@code net/devh/boot/grpc/client/nameresolver/DiscoveryClientResolverFactory#newNameResolver}
 * ⇒ ⇒ 它<b>要一个 {@code DiscoveryClient}</b> ⇒ ⇒ 而 {@code NacosDiscoveryClient} 的构造器
 * 会调 {@code nacosServiceManager.getNamingService()} ⇒ ⇒ <b>不桩就会真的去连 Nacos</b>。
 *
 * <h2>⚠️ 为什么桩 bean <b>要改名 + {@code @Primary}</b></h2>
 * <p>SCA 2025.0.0.0 的 {@code NacosServiceAutoConfiguration#nacosServiceManager()} 会创建
 * <b>同名</b>的 {@code NacosServiceManager} bean（gating =
 * {@code @ConditionalOnDiscoveryEnabled} + {@code @ConditionalOnNacosDiscoveryEnabled}
 * ⇒ 只要<b>不禁用</b> nacos discovery 就会存在）
 * ⇒ ⇒ 若桩还用默认 bean 名 {@code nacosServiceManager} ⇒ <b>BeanDefinitionOverrideException</b>
 * ⇒ ⇒ 上下文起不来。
 * <p>⇒ 所以：桩的 bean 名改成 {@code testNacosServiceManager} + 标 {@link Primary}
 * ⇒ ⇒ 类型注入（域内 {@code wiring/NacosRegistration}、SCA 的
 * {@code nacosServiceRegistry(...)} / {@code nacosServiceDiscovery(...)} / 通道的 name resolver）
 * <b>都拿到桩</b> ⇒ ⇒ 真 bean 存在但<b>没人用</b> ⇒ ⇒ <b>不触网</b>。
 *
 * <h2>⚠️ 为什么它是<b>顶层类</b>而不是测试类的嵌套类</h2>
 * <p><b>Boot 3.4 起，嵌套 {@code @TestConfiguration} 默认<b>不再被自动发现</b></b>
 * —— 开关是 {@code spring.test.nested-test-configuration}，默认值是 {@code INHERIT}
 * ⇒ ⇒ 嵌套写法在本仓（Boot 3.5）会<b>静默不注册</b> ⇒ 表现为
 * 「No qualifying bean of type 'NacosServiceManager'」⇒ 上下文起不来。
 * <p>⇒ 本类改成<b>顶层 {@code @TestConfiguration}</b>，并在测试类的
 * {@code classes = {...}} 里<b>显式点名</b> ⇒ 不依赖任何默认值。
 *
 * <p>⚠️ 本类只服务于<b>测试</b>：它<b>不</b>改变生产代码路径
 * （生产环境下 {@code wiring/NacosRegistration} 用的是真 {@code NacosServiceManager}）。
 */
@TestConfiguration(proxyBeanMethods = false)
public class NacosStubConfiguration {

    /**
     * <p>桩 {@link NacosServiceManager} —— <b>bean 名刻意与自动配置的真 bean 错开</b>
     * （真 bean 名 = {@code nacosServiceManager}，见本类 javadoc 的「改名 + @Primary」段）。
     */
    @Bean
    @Primary
    public NacosServiceManager testNacosServiceManager() {
        NacosServiceManager manager = Mockito.mock(NacosServiceManager.class);
        Mockito.when(manager.getNamingService()).thenReturn(Mockito.mock(NamingService.class));
        return manager;
    }

    /**
     * <p>把桩里的 {@link NamingService} mock <b>暴露出来</b>（同一个实例，域内注册与断言看的是同一个）。
     *
     * <p>用途：断言「<b>Nacos 上到底注册了哪个端口</b>」——
     * 自动注册会注册 <b>web 端口</b>，域内 {@code wiring/NacosRegistration} 注册 <b>gRPC 端口</b>
     * ⇒ ⇒ 靠「桩上被调了几次 + 端口是哪个」就能<b>真触发</b>地判定
     * {@code spring.cloud.nacos.discovery.register-enabled: false} 有没有生效。
     */
    @Bean
    public NacosNamingServiceSpy testNacosNamingServiceSpy(NacosServiceManager manager) {
        return new NacosNamingServiceSpy(manager.getNamingService());
    }

    /**
     * <p>桩 {@link NamingService} 的持有者 —— 暴露 mock 本身供测试断言调用记录。
     *
     * @param namingService 桩里的 {@link NamingService} mock（<b>与域内注册用的是同一个实例</b>）
     */
    public record NacosNamingServiceSpy(NamingService namingService) {
    }
}
