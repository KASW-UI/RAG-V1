package com.rag.task;

import com.rag.task.config.TaskRewardProperties;
import com.rag.task.config.TaskSinkProperties;
import com.rag.task.messaging.TaskKafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

/**
 * <p><b>task 域的唯一启动入口</b> —— 一个进程内同时起 Tomcat（HTTP 面）与 gRPC server（业务面），
 * 外加 5 个 Kafka listener 与 2 个 1 秒刷盘 ticker。</p>
 *
 * <p><b>抄写基准</b>：B 侧<b>两个</b>入口类
 * {@code service/task/task-api/.../TaskApiApplication.java} ＋
 * {@code service/task/task-rpc/.../TaskRpcApplication.java}（D1：api + rpc 合并为单一应用）。</p>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>D1 合并</b> —— B 侧是<b>两个</b>进程、<b>两个</b>入口类、
 *       <b>两份</b> {@code application.yml} ⇒ 本仓合并为<b>一个</b>入口、<b>一份</b> yml。
 *       具体合并口径：
 *     <ul>
 *       <li><b>包扫描</b>：B 侧 api 扫 {@code com.rag.task.task.api} + {@code com.rag.common}；
 *           本仓<b>只扫 {@code com.rag.task}</b>（{@code @SpringBootApplication} 的默认包 = 本类所在包）
 *           ⇒ ⇒ <b>刻意不扫 {@code com.rag.common}</b>：共享层在本仓是 <b>starter</b>（D3），
 *           由 {@code AutoConfiguration.imports} 自动装配 ⇒ 再扫一遍会<b>双注册</b>。</li>
 *       <li><b>{@code @Import} 清单</b>：B 侧 task-rpc 显式 {@code @Import} 了 8 个共享层类
 *           （{@code LoggerFactoryBean} / {@code RpcServerInterceptorFactory} /
 *           {@code ObservabilityProperties} / {@code ObservabilityLifecycle} /
 *           {@code DeadlineQueryTimeoutInjector} / {@code SqlTimeoutStatusTranslator} /
 *           {@code Jackson2ObjectMapperConfiguration} / {@code GoStyleProtectionConfiguration}）
 *           ⇒ 本仓<b>全部由共享层的 auto-config 提供</b> ⇒ ⇒ <b>本类的 {@code @Import} 清单为空</b>（D3）。</li>
 *       <li><b>gRPC 拦截器链</b>：B 侧 task-rpc 自己写了 {@code rpcInterceptorConfigurer}
 *           （把 {@code ServerInterceptor} 挂到 gRPC server 上）⇒ 本仓由共享层的
 *           {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer} 承担
 *           （devh starter 只认 {@code GrpcServerConfigurer}，域内再来一份会挂 2 条链）
 *           ⇒ ⇒ <b>本类不写这条 bean</b>。</li>
 *       <li><b>Kafka 错误契约</b>：B 侧的 {@code noCommitErrorHandler} ＋
 *           {@code kafkaListenerContainerFactory} 是<b>域内契约</b>（不是共享层能力）
 *           ⇒ <b>本类保留</b>，方法体逐条照抄。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 6 个「不写就静默失效 / 直接起不来」的装配点
 *       （{@code @MapperScan} / {@code @EnableScheduling} / Kafka starter / Flyway 模块 /
 *       {@code @EnableConfigurationProperties} / gRPC 拦截器链）逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/TaskRpcApplication.java:79-116（类声明 + @Import 清单）
 * B 出处：…/TaskRpcApplication.java:130-139（rpcInterceptorConfigurer ⇒ 共享层承担）
 * B 出处：…/TaskRpcApplication.java:185-193（kafkaListenerContainerFactory）
 * B 出处：…/TaskRpcApplication.java:202-216（noCommitErrorHandler）
 * B 出处：…/TaskRpcApplication.java:218-220（main）
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/TaskApiApplication.java（api 侧入口）
 *       </pre></li>
 * </ol>
 *
 * <h2>🔴 六个「不写就静默失效 / 直接起不来」的装配点</h2>
 * <ol>
 *   <li><b>{@code @MapperScan}</b> — 缺它两个 Mapper 全部注册不上，
 *       报 {@code No qualifying bean of type '…mapper.TaskProgressMapper'}，上下文直接起不来。</li>
 *   <li><b>{@code @EnableScheduling}</b> — 两个 sink 的 1 秒刷盘 ticker 靠它。
 *       缺它 {@code @Scheduled} 静默不跑，delta 永远堆积不落库。</li>
 *   <li><b>{@code spring-kafka}</b>（pom）— 缺它则没有 {@code KafkaTemplate} /
 *       {@code ConsumerFactory} ⇒ <b>组件起不来</b>。</li>
 *   <li><b>Flyway</b>（pom + {@code spring.flyway.*}）— 缺它两张表永不创建
 *       ⇒ 第一次真正查库才炸。{@code TaskSchemaGate} 是第二道防线。</li>
 *   <li><b>{@code @EnableConfigurationProperties}</b> — 三个 Properties 类<b>刻意不带
 *       {@code @Component}</b>（若同时带 {@code @Component} 又被扫描到 ⇒ 同一个类型注册
 *       <b>两个</b> bean ⇒ 构造器注入 {@code NoUniqueBeanDefinitionException}）
 *       ⇒ ⇒ 必须在本类<b>显式列举</b>。⚠️ 这一条<b>不可用
 *       {@code @ConfigurationPropertiesScan("com.rag.task.config")} 代替：
 *       {@code TaskKafkaTopics} 在 D7 包压平后落在 {@code messaging/} ⇒ 扫 {@code config/}
 *       会<b>漏掉它</b> ⇒ 它不是 bean ⇒ 注入失败 ⇒ <b>上下文直接起不来</b>
 *       （like 域踩过这个坑，见 {@code LikeServiceApplication} 的类注释）。</li>
 *   <li><b>gRPC 拦截器链</b> — 共享层的 {@code seaGrpcServerConfigurer} 承担
 *       （devh starter <b>不会</b>自动把用户提供的 {@code ServerInterceptor} 挂到 server 上；
 *       缺它超时 / deadline 传播 / gRPC span <b>全部静默消失</b>，而 Bean 断言照样是绿的）。</li>
 * </ol>
 *
 * <p>⚠️ <b>本模块同时起 Tomcat 与 gRPC</b>（D1）：HTTP 面 = {@code server.port}，
 * gRPC 面 = {@code grpc.server.port} ⇒ ⇒ Nacos 自动注册会取到 <b>web 端口</b> ⇒
 * ⇒ <b>必须</b>关掉自动注册（{@code spring.cloud.nacos.discovery.register-enabled: false}），
 * 由 {@link com.rag.task.wiring.NacosRegistration} 用 <b>gRPC 端口</b>显式注册。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties({TaskKafkaTopics.class, TaskSinkProperties.class,
        TaskRewardProperties.class})
@MapperScan("com.rag.task.mapper")
@EnableScheduling
@EnableDiscoveryClient
public class TaskServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceApplication.class);

    /**
     * 覆盖 Spring Boot 自动配置的 Kafka 监听容器工厂，装上本域的错误契约。
     *
     * <p><b>同名 Bean 会替换自动配置的那个</b>（{@code @ConditionalOnMissingBean} 语义），
     * 所以这里必须自己把 {@code consumerFactory} 装回去。</p>
     *
     * <p>⚠️ <b>自建 factory ⇒ {@code spring.kafka.listener.*} 全族键失效</b>：
     * Boot 的 {@code ConcurrentKafkaListenerContainerFactoryConfigurer} <b>不会</b>参与
     * ⇒ {@code spring.kafka.listener.ack-mode} 这条 yaml 键<b>没有任何消费点</b>
     * （AGENTS.md §3.3 同型：配置里没写 ≠ 没开，写了也可能没接）⇒
     * ⇒ ack mode 只能在这里显式设置。</p>
     *
     * <p><b>B 出处</b>：{@code service/task/task-rpc/.../TaskRpcApplication.java:185-193}。</p>
     *
     * @param consumerFactory 全局消费者工厂
     * @param taskNoCommitErrorHandler 本域的错误处理器
     * @return listener 容器工厂
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            CommonErrorHandler taskNoCommitErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(taskNoCommitErrorHandler);
        // 🔴 ack mode 必须**显式**设成 RECORD（与 B 侧 yaml `spring.kafka.listener.ack-mode: record`
        //    的声明一致）：RECORD = listener 正常返回后才提交当前记录的位点。
        //    ⚠️ 不写这一行会落 Boot 默认的 BATCH ⇒ 把位点推到整个 poll 批次的末尾。
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /**
     * 只记日志、不提交位点的错误处理器。
     *
     * <p>⚠️ 不用默认 recoverer：默认 {@code DefaultErrorHandler} 的行为是
     * 在重试耗尽后<b>提交该条位点</b>（等价于丢弃消息）。
     * Go 侧这些失败消息是"不确认、等重投"的（kq 的 errorHandler 默认只打日志）。</p>
     *
     * <p><b>B 出处</b>：{@code service/task/task-rpc/.../TaskRpcApplication.java:202-216}。</p>
     *
     * @return 本域的 Kafka 错误处理器
     */
    @Bean
    public CommonErrorHandler taskNoCommitErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, ex) -> log.error("task consumer failed (offset not committed, will redeliver): "
                        + "topic={} partition={} offset={} key={} err={}",
                        record.topic(), record.partition(), record.offset(), record.key(),
                        ex.getMessage()),
                // ⚠️ 不做进程内重试：Go kq 没有重试循环，重试次数必须为 0，
                //    否则是新增行为。用 maxAttempts=1 表达"第一次失败就直接交给 errorHandler"。
                new FixedBackOff(1000L, 1L));
        handler.setAckAfterHandle(false);
        handler.setCommitRecovered(false);
        // ⚠️ 关键：不因失败而 seek 到当前位点（那等于把失败消息标记为已处理 ⇒ 丢消息）
        handler.setSeekAfterError(false);
        return handler;
    }

    /**
     * 进程入口 —— 对应 B 侧两个 {@code main}（api 的与 rpc 的）合并后的唯一入口。
     *
     * <p><b>B 出处</b>：{@code service/task/task-rpc/.../TaskRpcApplication.java:218-220}
     * ＋ {@code service/task/task-api/.../TaskApiApplication.java}。</p>
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(TaskServiceApplication.class, args);
    }
}
