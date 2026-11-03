package com.rag.article.support;

import com.rag.article.ArticleServiceApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <p><b>真 PostgreSQL + 真 Spring 上下文（含真 Tomcat 与真 gRPC server）</b> ——
 * 本仓 article 域「装配 / 机制门禁」测试的公共底座。
 *
 * <h2>🔴 这一组测试要回答的问题：<b>「机制真的生效了吗」</b></h2>
 * <p>AGENTS.md §3.3 明确：<b>「Bean 存在」≠「机制生效」</b>，
 * 且守门脚本 {@code tools/difftest/check-arch-invariants.py}（A1–A13）
 * <b>只做源码文本级扫描</b> ⇒ 它<b>证不了</b>任何运行期事实。
 * <p>⇒ ⇒ 本基座提供的上下文里跑的是<b>生产装配本身</b>：
 * <ul>
 *   <li>生产的 {@code ArticleServiceApplication}（含它自己的
 *       {@code @ConfigurationPropertiesScan} / {@code @MapperScan} / {@code @EnableScheduling}）；</li>
 *   <li>生产的域内 {@code .imports} 自动装配（{@code ContentAuditAutoConfiguration}）；</li>
 *   <li>生产的 gRPC server（devh starter ＋ 共享层的 {@code GrpcServerConfigurer}）；</li>
 *   <li>生产的 Flyway 迁移（真 PG ⇒ 真 DDL ⇒ 真触发器）；</li>
 *   <li>生产的 Bean Validation（{@code spring-boot-starter-validation}）。</li>
 * </ul>
 * ⇒ ⇒ 断言落在<b>运行期可观测的事实</b>上（容器里的 Bean、真跑的 server、
 * 真抓的 Prometheus 文本、真抛的异常），而<b>不是</b>「类上有这个注解」。
 *
 * <h2>⚠️ 与 {@code ArticleRealPgTestBase} 的区别（为什么另起一个基座）</h2>
 * <p>{@code ArticleRealPgTestBase} 用 {@code webEnvironment = NONE}（不起 Tomcat）
 * 且 Kafka 指向死地址；本基座用 {@code RANDOM_PORT}（<b>起真 Tomcat</b>），
 * 因为 A10（Bean Validation）那条判据要求<b>真的提交一个缺 title 的 body</b>
 * ⇒ 需要一条真实的 HTTP 通道。
 * <p>⚠️ 两个基座的 {@code @DynamicPropertySource} 方法集合不同 ⇒
 * Spring 的 {@code ContextCache} 会把它们视作<b>两个不同的上下文</b> ⇒ 各自独立。
 *
 * <h2>Kafka 为什么指向死地址</h2>
 * <p>本组测试<b>不测 MQ</b>（那是 {@code KafkaTestBase} 的职责）⇒ 不必为它再起一个 broker。
 * ⚠️ 但<b>四个通道的 brokers 必须与全局同值</b>：{@code ArticleKafkaChannelValidator}
 * 在启动时比对二者，<b>分叉就启动失败**。
 * <p>⚠️ producer 必须封顶 {@code max.block.ms}：broker 不可达时
 * {@code KafkaTemplate.send()} 会阻塞到 {@code max.block.ms}（默认 60s）
 * ⇒ 而 relay 的定时任务会挂住 60s。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧 {@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/ArticleRpcApplication.java:123-132}
 *       （gRPC 拦截链挂载点 ⇒ C 侧由共享层 {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer} 承担）；</li>
 *   <li>B 侧 {@code .../bootstrap/ArticleSchemaGate.java:112-168}（缺触发器 ⇒ 拒绝启动）；</li>
 *   <li>B 侧 {@code .../client/ContentSecurityAutoConfiguration.java:42-49} ＋
 *       模块内 {@code META-INF/spring/…AutoConfiguration.imports}（域内自动配置的注册机制）。</li>
 * </ul>
 *
 * <h2>可证伪性</h2>
 * <ul>
 *   <li>把域内 {@code .imports} 文件删掉 ⇒ 「审核端口兜底 Bean 在容器里」立刻红；</li>
 *   <li>把 {@code seaGrpcServerConfigurer} 的 {@code server.intercept(chain)} 去掉
 *       ⇒ 「运行中的 gRPC server 的 interceptor 登记表非空」立刻红；</li>
 *   <li>把 {@code CreateArticleReq} 的 {@code @NotNull(message=…)} 去掉
 *       ⇒ 「缺 title 的 body 被拒」立刻红。</li>
 * </ul>
 */
@SpringBootTest(
        classes = {ArticleServiceApplication.class, NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                // ⚠️ gRPC 端口交由 OS 分配（固定端口会与并行跑的其它 worker 撞 BindException）
                "grpc.server.port=0",
                // ⚠️ Kafka 指向死地址（MQ 链路由 KafkaTestBase 单独测）
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                // ⚠️ 必须给 producer 封顶：broker 不可达时 KafkaTemplate.send() 会阻塞 60s
                "spring.kafka.producer.properties.max.block.ms=2000",
                "article-rpc.kq-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.kq-consumer-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-result-consumer-conf.brokers=127.0.0.1:1",
                "article-rpc.minio.endpoint=127.0.0.1:1"
        })
public abstract class AssemblyTestBase {

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
