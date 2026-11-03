package com.rag.article.support;

import com.rag.article.ArticleServiceApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <p><b>真 PostgreSQL + 真 Spring 上下文的共享基类</b> —— 本仓 article 域所有
 * 「真触发」集成测试的公共底座。
 *
 * <h2>为什么必须有这个基类（而不是每个测试类各写一份 {@code @SpringBootTest}）</h2>
 * <p>Spring 的 {@code ContextCache} 用 {@code MergedContextConfiguration} 作 key，
 * 而 {@code @DynamicPropertySource} 的方法集合是 key 的一部分
 * （{@code DynamicPropertiesContextCustomizer#equals} 比的是 {@code Set<Method>}）。
 * <p>⇒ 如果每个测试类<b>各自声明</b>一个 {@code @DynamicPropertySource} 方法，
 * 方法所属的类不同 ⇒ {@code Method} 不同 ⇒ <b>每个测试类都会新建一个 Spring 上下文</b>
 * ⇒ 7 个集成测试类 = 7 次完整装配（每次都要连 PG、跑 Flyway、起 gRPC server）。
 * <p>⇒ ⇒ 把 {@code @DynamicBootTest} 与 {@code @DynamicPropertySource} <b>统一放在本基类</b>：
 * 所有子类<b>继承</b>同一组方法 ⇒ {@code Set<Method>} 相同 ⇒ <b>共享同一个上下文</b>。
 *
 * <h2>⚠️ 子类<b>不得</b>再声明自己的 {@code @DynamicPropertySource}</h2>
 * <p>一旦子类新增方法，{@code Set<Method>} 变大 ⇒ key 变化 ⇒ 该子类<b>单独</b>起一个上下文
 * （功能上仍正确，只是慢）。需要额外属性时，请在本基类里加，并说明理由。
 *
 * <h2>为什么这里必须覆盖这几项配置</h2>
 * <ol>
 *   <li>{@code USER_JWT_SECRET} —— 生产 {@code application.yml:305} 是<b>裸占位符</b>
 *       {@code ${USER_JWT_SECRET}}，而 {@code ArticleApiProperties#verifyAccessSecret}
 *       是 fail-closed 守卫 ⇒ 缺它上下文起不来。</li>
 *   <li>{@code POSTGRES_PASSWORD} —— 同款裸占位符（{@code application.yml:35} 与 {@code :221}）。
 *       ⚠️ {@code spring.datasource.password} 被 {@link TestPostgres#register} 覆盖了，
 *       但 {@code article-rpc.postgres.password} <b>没有</b> ⇒ 它必须能解析。</li>
 *   <li>{@code grpc.client.message-rpc.address} —— 生产值是
 *       {@code discovery:///message-rpc}（{@code application.yml:199}），
 *       devh 的 {@code @GrpcClient} 在 <b>Bean 初始化期</b>建 channel ⇒ 不覆盖则上下文起不来。</li>
 *   <li>{@code grpc.server.port=0} —— devh 的 gRPC server 是 {@code SmartLifecycle}，
 *       上下文一启动就 bind 端口；Spring 的上下文缓存会让<b>多个上下文同时存活</b>
 *       ⇒ 固定端口必然 {@code BindException} ⇒ 让 OS 分配临时端口。</li>
 *   <li>{@code spring.kafka.bootstrap-servers=127.0.0.1:1} + <b>四个通道的 brokers 同步</b> ——
 *       ⚠️ {@code ArticleKafkaChannelValidator} 在启动时比对「四个通道的 brokers」与
 *       「全局 bootstrap-servers」，<b>不一致就启动失败</b> ⇒ 只改全局会直接起不来。</li>
 *   <li>{@code article-rpc.minio.endpoint=127.0.0.1:1} —— 让 MinIO <b>快速</b>失败
 *       （B 侧语义是「MakeBucket 失败只 warn 不 panic」，但 DNS 解析 {@code minio} 会拖慢启动）。</li>
 * </ol>
 *
 * <h2>为什么 {@code NacosStubConfiguration} 要显式点名</h2>
 * <p>Boot 3.4 起嵌套 {@code @TestConfiguration} 默认不被发现 ⇒ 必须顶层 + 在
 * {@code classes = {...}} 里<b>显式点名</b>（该类自己的 javadoc 有详述）。
 *
 * <h2>数据隔离纪律</h2>
 * <p>单例 PG 容器被<b>所有</b>测试类共用 ⇒ 各测试类必须用<b>互不重叠</b>的 uid 段
 * （本仓约定：{@code 9xx_000_000 + 序号}，每个测试类一个号段）。
 * ⚠️ 另有一条：{@code article_revision} 上有 {@code BEFORE UPDATE OR DELETE} 触发器
 * ⇒ <b>清理只能用 TRUNCATE，不能用 DELETE}。
 */
@SpringBootTest(
        classes = {ArticleServiceApplication.class, NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                "grpc.server.port=0",
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                // ⚠️ 必须给 producer 封顶：KafkaTemplate.send() 在 broker 不可达时会阻塞到
                //    max.block.ms（默认 60s）⇒ ArticleSyncOutboxRelay 的定时任务会挂住 60s。
                "spring.kafka.producer.properties.max.block.ms=2000",
                "article-rpc.kq-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.kq-consumer-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-result-consumer-conf.brokers=127.0.0.1:1",
                "article-rpc.minio.endpoint=127.0.0.1:1"
        })
public abstract class ArticleRealPgTestBase {

    /**
     * 注入真实连接信息与三个<b>必须</b>显式提供的属性。
     *
     * <p>⚠️ 本方法被<b>所有</b>子类继承 ⇒ {@code Set<Method>} 相同 ⇒ 上下文共享。
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        // 🔴 裸占位符必须能被解析（application.yml:35 / :221 / :305）
        registry.add("POSTGRES_PASSWORD", () -> "password");
        registry.add("USER_JWT_SECRET", () -> "test-only-article-jwt-secret-at-least-32-bytes-long");
        // ⚠️ devh 在 Bean 初始化期建 channel ⇒ discovery:// 解析不了 ⇒ 上下文起不来
        registry.add("grpc.client.message-rpc.address", () -> "static://127.0.0.1:1");
    }
}
