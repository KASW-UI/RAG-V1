package com.rag.hot.support;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.naming.NamingService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * <p><b>Nacos 桩</b> —— 让 hot 域的测试能起真实 Spring 上下文，而<b>不连 Nacos</b>。
 *
 * <h2>为什么需要它</h2>
 * <p>域内 {@code wiring/NacosRegistration} 的构造器依赖 {@link NacosServiceManager}，
 * 而真实实现会连 Nacos（{@code server-addr} 默认 {@code nacos:8848}）⇒ 启动期就会尝试建连。
 * <p>本桩把 {@code getNamingService()} 指向一个<b>空操作</b>的 {@link NamingService} mock
 * ⇒ 注册调用是空操作 ⇒ <b>不碰网络</b>，但域内那个 bean <b>照常被创建</b>
 * （⇒ 它的构造契约仍被真实校验）。
 *
 * <h2>⚠️ 为什么它是<b>顶层类</b>而不是测试类的嵌套类</h2>
 * <p><b>Boot 3.4 起，嵌套 {@code @TestConfiguration} 默认<b>不再被自动发现</b></b>
 * —— 开关是 {@code spring.test.nested-test-configuration}，默认值是 {@code INHERIT}
 * （含义 = 「继承父上下文的嵌套配置」，= <b>本类不被注册</b>）
 * ⇒ ⇒ 嵌套写法在本仓（Boot 3.5）会<b>静默不注册</b> ⇒ 表现为
 * 「No qualifying bean of type 'NacosServiceManager'」⇒ 上下文起不来。
 * <p>⇒ 本类改成<b>顶层 {@code @TestConfiguration}</b>，并在各测试类的
 * {@code classes = {...}} 里<b>显式点名</b> ⇒ 不依赖任何默认值。
 *
 * <h2>⚠️ 为什么桩 bean <b>要改名 + @Primary</b></h2>
 * <p>SCA 2025.0.0.0 的 {@code NacosServiceAutoConfiguration#nacosServiceManager()} 会创建
 * <b>同名</b>的 {@code NacosServiceManager} bean ⇒ 若桩还用默认 bean 名
 * ⇒ <b>BeanDefinitionOverrideException</b> ⇒ 上下文起不来。
 * <p>⇒ 所以：桩的 bean 名改成 {@code testNacosServiceManager} + 标 {@link Primary}
 * ⇒ ⇒ 类型注入（域内 {@code wiring/NacosRegistration}、SCA 的
 * {@code nacosServiceRegistry(...)} / {@code nacosServiceDiscovery(...)}）<b>都拿到桩</b>
 * ⇒ ⇒ 真 bean 存在但<b>没人用</b> ⇒ ⇒ <b>不触网</b>。
 *
 * <p>⚠️ 本类只服务于<b>测试</b>：它<b>不</b>改变生产代码路径。
 */
@TestConfiguration(proxyBeanMethods = false)
public class NacosStubConfiguration {

    /**
     * <p>桩 {@link NacosServiceManager} —— <b>bean 名刻意与自动配置的真 bean 错开</b>。
     *
     * @return 桩 bean
     */
    @Bean
    @Primary
    public NacosServiceManager testNacosServiceManager() {
        NacosServiceManager manager = Mockito.mock(NacosServiceManager.class);
        Mockito.when(manager.getNamingService()).thenReturn(Mockito.mock(NamingService.class));
        return manager;
    }
}
