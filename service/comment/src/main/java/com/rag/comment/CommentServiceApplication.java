package com.rag.comment;

import com.rag.comment.config.CommentApiProperties;
import com.rag.comment.config.CommentRpcProperties;
import com.rag.comment.messaging.CommentKafkaTopics;
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
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <p><b>comment 域的唯一启动入口</b> —— 一个进程内同时起 Tomcat（HTTP 面）
 * 与 gRPC server（业务面）＋ Kafka 审计消费。
 *
 * <p><b>B 出处</b>：<b>B 侧两个入口合并</b> ——
 * ① {@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/CommentApiApplication.java:68-79}
 * （类声明 {@code :68-74} ＋ {@code main} {@code :76-78}）
 * ＋ ② {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/CommentRpcApplication.java:53-137}
 * （类声明 {@code :53-77} ＋ {@code rpcInterceptorConfigurer} {@code :91-100}
 * ＋ {@code kafkaListenerContainerFactory} {@code :120-133} ＋ {@code main} {@code :135-137}）
 * ⇒ <b>两段合并，逐条抄</b>。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>D1 合并</b> —— B 侧是<b>两个</b>进程、<b>两个</b>入口类
 *       ⇒ 本仓合并为<b>一个</b>。具体合并口径：
 *     <ul>
 *       <li><b>包扫描</b>：B 侧 api 扫 {@code com.rag.comment.comment.api} + {@code com.rag.common}；
 *           本仓<b>只扫 {@code com.rag.comment}</b>（{@code @SpringBootApplication} 的默认包 = 本类所在包）
 *           ⇒ ⇒ <b>刻意不扫 {@code com.rag.common}</b>：共享层在本仓是 <b>starter</b>（D3），
 *           由 {@code AutoConfiguration.imports} 自动装配 ⇒ 再扫一遍会<b>双注册</b>。</li>
 *       <li><b>{@code @Import} 清单</b>：B 侧 comment-rpc 显式 {@code @Import} 了 9 个共享层类
 *           （{@code SnowflakeBean} / {@code SnowflakeProperties} / {@code LoggerFactoryBean} /
 *           {@code RpcServerInterceptorFactory} / {@code ObservabilityProperties} /
 *           {@code ObservabilityLifecycle} / {@code DeadlineQueryTimeoutInjector} /
 *           {@code SqlTimeoutStatusTranslator} / {@code Jackson2ObjectMapperConfiguration} /
 *           {@code GoStyleProtectionConfiguration}）
 *           ⇒ 本仓<b>全部由共享层的 auto-config 提供</b> ⇒ ⇒ <b>本类的 {@code @Import} 清单为空</b>（D3）。</li>
 *       <li><b>gRPC 拦截器链</b>：B 侧 comment-rpc 自己写了 {@code rpcInterceptorConfigurer}
 *           （把 {@code ServerInterceptor} 挂到 gRPC server 上）⇒ 本仓由共享层的
 *           {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer} 承担
 *           （devh starter 只认 {@code GrpcServerConfigurer}，域内再来一份会挂 2 条链）
 *           ⇒ ⇒ <b>本类不写这条 bean</b>。</li>
 *       <li><b>Kafka 错误契约</b>：B 侧的 {@code kafkaListenerContainerFactory}
 *           是<b>域内契约</b>（不是共享层能力）⇒ <b>本类保留</b>，方法体逐条照抄。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *     <ul>
 *       <li>{@code @EnableDiscoveryClient}（Nacos 自动发现）保留；</li>
 *       <li>{@code @EnableConfigurationProperties} <b>显式列举</b>三个配置类 ⇒
 *           绑定 {@link CommentApiProperties}（{@code comment-api.*}）、
 *           {@link CommentRpcProperties}（{@code comment.*}）与
 *           {@link CommentKafkaTopics}（{@code comment.kafka.*}）
 *           —— <b>三段前缀都保留</b>（B 侧就是两份 yml 各有一份）⇒ ⇒ <b>配置键逐字不变</b>；</li>
 *       <li>{@code @MapperScan("com.rag.comment.mapper")} ⇒ 注册 7 个 Mapper
 *           （B 侧 comment-rpc 同款；缺它它们不会成为 bean，
 *           报错是 {@code No qualifying bean of type '…mapper.SubjectMapper'}）；</li>
 *       <li>{@code @EnableScheduling} 保留（B 侧 comment 域当前无 {@code @Scheduled} 方法 ⇒ 无消费点；
 *           与本仓 like / article 两个合并入口同款）。</li>
 *     </ul></li>
 *   <li><b>B 出处</b>：见上两个文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🔴 {@code @EnableConfigurationProperties} 必须<b>显式列举</b>三个类</h2>
 * <p>B 侧 comment-rpc 用的是
 * {@code @ConfigurationPropertiesScan(basePackages = "com.rag.comment.comment.rpc.config")}
 * ⇒ 该包下的<b>所有</b> {@code @ConfigurationProperties} 类都被注册
 * ⇒ 其中就包括 {@code com.rag.comment.comment.rpc.config.CommentKafkaTopics}。
 * <p>⚠️ <b>D7 包压平把 {@code CommentKafkaTopics} 从 {@code config/} 移到了 {@code messaging/}</b>
 * ⇒ ⇒ 若 C 侧照抄「扫 {@code config} 包」的写法，{@code CommentKafkaTopics} 就<b>不在扫描范围内</b>
 * ⇒ ⇒ 它<b>不是 bean</b> ⇒ 依赖它的 Bean 注入失败
 * ⇒ ⇒ <b>应用直接起不来</b>（{@code No qualifying bean of type 'com.rag.comment.messaging.CommentKafkaTopics'}）。
 * <p>⇒ ⇒ 本类采用<b>显式列举</b>（与 like 域同款）：把三个 {@code @ConfigurationProperties} 类
 * 都写进 {@code @EnableConfigurationProperties} ⇒ <b>不受包压平影响</b>。
 * <p>⚠️ 这条缺陷<b>编译期与单测都发现不了</b>（只有「起真实 Spring 上下文」才会暴露）
 * ⇒ 由真上下文启动测试钉住。
 *
 * <h2>⚠️ 本模块同时起 Tomcat 与 gRPC</h2>
 * <p>D1 合并后：HTTP 面 = {@code server.port}，gRPC 面 = {@code grpc.server.port}
 * ⇒ ⇒ Nacos 自动注册会取到 <b>web 端口</b> ⇒ ⇒ <b>必须</b>关掉自动注册
 * （{@code spring.cloud.nacos.discovery.register-enabled: false}），
 * 由 {@link com.rag.comment.wiring.NacosRegistration} 用 <b>gRPC 端口</b>显式注册。
 *
 * <h2>⚠️ gRPC server 由 devh starter 自动装配</h2>
 * <p>{@code grpc/CommentGrpcService} 上的 {@code @GrpcService} 由 starter 扫描注册
 * ⇒ <b>本类不手写任何 gRPC 注册代码</b>。
 */
@SpringBootApplication
@EnableConfigurationProperties({CommentApiProperties.class, CommentRpcProperties.class,
        CommentKafkaTopics.class})
@MapperScan("com.rag.comment.mapper")
@EnableScheduling
@EnableDiscoveryClient
public class CommentServiceApplication {

    private static final Logger LOG = LoggerFactory.getLogger(CommentServiceApplication.class);

    /**
     * 覆盖自动配置的 Kafka 监听容器工厂，装上本域的 drop/retry 契约 ——
     * 对应 B 侧 {@code CommentRpcApplication.java:102-133}。
     *
     * <p><b>同名 Bean 会替换自动配置的那个</b>（{@code @ConditionalOnMissingBean} 语义），
     * 所以这里必须自己把 {@code consumerFactory} 装回去。
     *
     * <p>两处关键设置：
     * <ol>
     *   <li>{@code AckMode.RECORD} —— listener 正常返回后才提交位点。
     *       这是「落库失败 → 不提交 → 重投」契约的载体
     *       （B 侧：{@code Consume} 返回 error ⇒ kq 不确认）。</li>
     *   <li>{@link com.rag.comment.messaging.CommentAuditListener#onMessage} 自己决定
     *       「返回 null 还是抛异常」，错误处理器只<b>记日志</b>。</li>
     * </ol>
     *
     * <p>⚠️ 不用默认 recoverer：默认会 seek 到当前位点，
     * 等于把失败消息标记成已处理 ⇒ <b>丢消息</b>。那是<b>新增的丢消息路径</b>，与 B 侧不符。
     *
     * <p>⚠️ <b>并发度不在这里设</b>：B 侧这个工厂<b>没有</b> {@code setConcurrency} ——
     * 并发度由 listener 上的 {@code @KafkaListener(concurrency = "${comment.kafka.audit-concurrency:8}")}
     * 承担 ⇒ <b>逐字照抄 B 侧</b> ⇒ 这里不引入 B 侧没有的第二个并发度来源。
     *
     * <p>⚠️ <b>刻意不抄 like / article 域的 {@code FixedBackOff(0, 0)}</b>：
     * 那两个域的 B 侧语义是 {@code ForceCommit=true}（失败也提交位点、消息丢弃），
     * 而本域的 B 侧语义是<b>「落库失败 ⇒ 不提交 ⇒ 重投」</b> ⇒ ⇒ 两者方向相反，不可照抄。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                (record, exception) ->
                        LOG.error("comment audit kafka message failed, will NOT commit offset: {}",
                                exception.getMessage(), exception)));
        return factory;
    }

    /**
     * 进程入口 —— 对应 B 侧两个 {@code main}（api 的与 rpc 的）合并后的唯一入口。
     *
     * <p><b>B 出处</b>：B 侧 {@code comment-rpc/.../CommentRpcApplication.java:135-137}
     * ＋ {@code comment-api/.../CommentApiApplication.java:76-78}。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(CommentServiceApplication.class, args);
    }
}
