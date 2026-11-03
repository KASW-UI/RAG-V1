package com.rag.like.support;

import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;

/**
 * <p><b>捕获 devh 真 netty gRPC server 的「实际绑定端口」与「实际 server 实现类」</b>。
 *
 * <h2>🔴 为什么必须监听事件，而不是读配置</h2>
 * <p>devh 的 {@code GrpcServerProperties#port} 文档写明「设为 {@code 0} 时随机选一个可用端口」，
 * 但 <b>{@code GrpcServerLifecycle#createAndStartGrpcServer()} 发布事件时传的是
 * {@code this.factory.getPort()}</b> ⇒ <b>事件里的 {@code getPort()} 在 port=0 时返回 0</b>，
 * <b>不是实际绑定的端口</b>。
 * <p>⇒ ⇒ 真正的端口只能从 <b>server 对象本身</b>取：
 * {@code event.getServer().getPort()}（{@code io.grpc.Server#getPort()} 返回实际监听端口，
 * 未绑定时返回 -1）。
 * <p>⚠️ <b>若误用 {@code event.getPort()}</b> ⇒ 拿到 0 ⇒ 客户端会去连 0 号端口 ⇒
 * 「连接被拒」会被误读成「server 没起来」⇒ <b>假红</b>。
 *
 * <h2>为什么它是<b>顶层 {@code @TestConfiguration}</b>（与 {@link NacosStubConfiguration} 同款）</h2>
 * <p>Boot 3.4 起嵌套 {@code @TestConfiguration} 默认<b>不再被自动发现</b>
 * ⇒ ⇒ 必须顶层 ＋ 在 {@code @SpringBootTest(classes = {...})} 里<b>显式点名</b>。
 *
 * <h2>监听器为什么能在 server 启动<b>之前</b>被注册</h2>
 * <p>{@code AbstractApplicationContext#refresh()} 的顺序是
 * {@code registerListeners()} → {@code finishBeanFactoryInitialization()} → {@code finishRefresh()}。
 * <ul>
 *   <li>{@code registerListeners()} 扫的是 <b>bean 名字</b>（{@code getBeanNamesForType}，
 *       {@code allowEagerInit=false} ⇒ 用 {@code @Bean} 方法的声明返回类型判定）⇒ 名字被记下；</li>
 *   <li>监听 bean 的<b>实例</b>在事件发布那一刻才被解析 ⇒ <b>必然早于第一次事件</b>。</li>
 * </ul>
 * ⇒ ⇒ 声明返回类型写成具体的 {@link GrpcServerPortCapture}（而不是裸 {@code ApplicationListener}）
 * ⇒ Spring 的泛型解析拿得到 {@code GrpcServerStartedEvent} 这个事件类型。
 */
@TestConfiguration(proxyBeanMethods = false)
public class GrpcServerPortCapture implements ApplicationListener<GrpcServerStartedEvent> {

    /** 实际绑定端口（-1 = 尚未捕获）。 */
    private static final AtomicInteger PORT = new AtomicInteger(-1);

    /** 实际 server 实现类的全名（null = 尚未捕获）。 */
    private static final AtomicReference<String> SERVER_CLASS = new AtomicReference<>();

    /** server 的 code source（jar / 目录路径），用来证明它来自 netty 传输层实现。 */
    private static final AtomicReference<String> CODE_SOURCE = new AtomicReference<>();

    // ⚠️ **本类刻意不声明 `@Bean` 方法**：
    //   它被 `@SpringBootTest(classes = {...})` **显式点名** ⇒ 本身就是**配置类 bean**
    //   （bean 名 = 类名 `grpcServerPortCapture`）
    //   ⇒ 若再声明一个同名的 `@Bean` 方法 ⇒ 触发
    //   `BeanDefinitionOverrideException: Invalid bean definition with name 'grpcServerPortCapture'`
    //   （配置类 bean 名与 @Bean 方法名撞车 ⇒ 上下文**起不来** —— 实测踩过）。
    //   ⇒ ⇒ 监听器身份直接由**配置类本体**承担（配置类 bean 也是普通单例 bean）。

    @Override
    public void onApplicationEvent(GrpcServerStartedEvent event) {
        // ⚠️ 取 server 自己的端口，不是 event.getPort()（后者在 port=0 时返回 0）。
        PORT.set(event.getServer().getPort());
        SERVER_CLASS.set(event.getServer().getClass().getName());
        CODE_SOURCE.set(codeSourceOf(event.getServer().getClass()));
    }

    private static String codeSourceOf(Class<?> type) {
        try {
            URI uri = type.getProtectionDomain().getCodeSource().getLocation().toURI();
            return uri.toString();
        } catch (Exception e) {
            return "unknown:" + e;
        }
    }

    /** 实际绑定端口。 */
    public static int port() {
        return PORT.get();
    }

    /** 实际 server 实现类全名。 */
    public static String serverClassName() {
        return SERVER_CLASS.get();
    }

    /** server 实现类的 code source（jar / 目录路径）。 */
    public static String codeSource() {
        return CODE_SOURCE.get();
    }
}
