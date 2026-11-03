package com.rag.task.support;

import com.rag.task.TaskServiceApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <p><b>task 域真上下文的共享底座</b> —— 真 Spring 上下文 + 真 PG + 真 Redis + 真 Tomcat
 * ＋ 真 HTTP 请求。</p>
 *
 * <p>⚠️ <b>本仓不用 MockMvc 断言 {@code Content-Type} / {@code nosniff} / 状态行</b>
 * （AGENTS.md §八 检查清单「HTTP 断言走真 Tomcat」）⇒ HTTP 面的判据一律走
 * {@link org.springframework.boot.test.web.client.TestRestTemplate} 打真端口。</p>
 *
 * <h2>① 为什么 {@code grpc.server.port=0} 是必需的</h2>
 * <p>devh 的 gRPC server 是 {@code SmartLifecycle} ⇒ 上下文一启动就 bind 端口
 * ⇒ 固定端口在「多个上下文同时存活」时必然 {@code BindException}。
 * <p>⚠️ 本基类与可能存在的其他上下文会同时存活 ⇒ 必须 0（交给 OS 分配）。</p>
 *
 * <h2>② 为什么 Kafka 指向不可达地址（而不是起真 Kafka 容器）</h2>
 * <p>本基座要验的是 <b>装配层</b>（listener 真进 registry、HTTP wire 形状、Mapper 语义），
 * <b>不是</b> Kafka 的端到端消费。
 * <p>⇒ Kafka 指向 {@code 127.0.0.1:1} ⇒ listener 容器照常注册（这正是要验的），
 * 但不会真消费 ⇒ <b>不引入 broker 依赖的抖动</b>。
 * <p>⚠️ {@code spring.kafka.producer.properties.max.block.ms=2000} 同样是必需的：
 * broker 不可达时 {@code KafkaTemplate.send()} 会阻塞到 {@code max.block.ms}（默认 60s）。</p>
 *
 * <h2>③ 为什么关掉 CPU 自适应降载</h2>
 * <p>降载命中时 {@code CpuSheddingFilter} 走 {@code response.setStatus(503)} 并<b>直接 return</b>
 * ⇒ <b>裸 503、无 body、无信封</b> ⇒ 会把本类要验的判据「抢先盖掉」
 * ⇒ ⇒ 机器一忙就<b>红在错的地方</b>（判据与被测路径无关）。
 * <p>⚠️ 这是<b>测试专用</b>覆盖，<b>不改生产默认值</b>（生产默认 {@code true} 对齐 Go 侧
 * {@code rest/config.go:22} {@code Middlewares.Shedding bool json:",default=true"}）。</p>
 *
 * <h2>④ 为什么必须提供真 PG / 真 Redis</h2>
 * <ul>
 *   <li><b>PG</b>：本域两张表的 DDL 与三条 upsert（{@code DO NOTHING} / {@code GREATEST} /
 *       {@code COALESCE}）在 H2 上<b>方言不同</b> ⇒ H2 上「跑绿」是假绿；</li>
 *   <li><b>Redis</b>：SETNX 闸门、pipeline 结果形状、「Redis 读失败 ⇒ 回源 DB」三条路径
 *       都只能靠真 Redis 触发。</li>
 * </ul>
 */
@SpringBootTest(
        classes = {TaskServiceApplication.class, NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // ── Nacos 不触网 ──
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "logging.level.com.alibaba.nacos=WARN",
                // ── 追踪不外发 ──
                "management.otlp.tracing.export.enabled=false",
                // ── gRPC 端口交给 OS（devh 的 gRPC server 是 SmartLifecycle ⇒ 启动即 bind）──
                "grpc.server.port=0",
                // ── Kafka 不可达：本底座只验装配层，不验端到端消费（见 ②）──
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                "spring.kafka.producer.properties.max.block.ms=2000",
                // ── 关掉 CPU 自适应降载（见 ③）：否则会抢先返回裸 503，把判据盖掉 ──
                "rag.protection.cpu-shedding.enabled=false"
        })
public abstract class TaskRealInfraTestBase {

    /**
     * 把真 PG / 真 Redis 的连接信息注入 Spring 环境。
     *
     * <p>⚠️ 本仓主 {@code application.yml} 里 {@code spring.datasource.password: ${POSTGRES_PASSWORD}}
     * 是<b>裸占位符</b>（无默认值，fail-closed 纪律）⇒ 必须由外部注入，
     * 否则占位符解析失败 ⇒ 上下文起不来。</p>
     */
    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
        registry.add("POSTGRES_PASSWORD", () -> "password");
    }
}
