package com.rag.like;

import com.rag.like.config.LikeApiProperties;
import com.rag.like.config.LikeRpcProperties;
import com.rag.like.messaging.LikeKafkaTopics;
import com.rag.like.messaging.LikeUpdateKafkaListener;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <p><b>like 域应用入口</b> —— 对应 B 侧<b>两个</b>入口
 * {@code like-api/.../LikeApiApplication.java} ＋ {@code like-rpc/.../LikeRpcApplication.java}。
 *
 * <p><b>抄写基准</b>：B 侧两个类（D1：api + rpc 合并为单一 Spring Boot 应用）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li><b>两个入口合并成一个</b>（D1）—— B 侧 api 与 rpc 是<b>两个进程</b>（两个 main 类、
 *           两套 {@code @Import} 清单、两份 {@code application.yml}）；C 侧是<b>单进程</b>。</li>
 *       <li>B 侧 {@code @Import} 清单里的 9 个类（{@code SnowflakeBean} / {@code LoggerFactoryBean} /
 *           {@code RpcServerInterceptorFactory} / {@code ObservabilityProperties} /
 *           {@code ObservabilityLifecycle} / {@code DeadlineQueryTimeoutInjector} /
 *           {@code SqlTimeoutStatusTranslator} / {@code Jackson2ObjectMapperConfiguration} /
 *           {@code GoStyleProtectionConfiguration}）⇒ <b>全部由共享层的
 *           {@code @AutoConfiguration} 承担</b>（D3 · starter 化）⇒ <b>本类不再需要
 *           {@code @Import} 清单</b>（D3 的核心收益之一）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。Kafka 消费并发度 8、错误处理器（{@code ForceCommit=true} 语义）、
 *       Mapper 扫描范围、配置属性绑定逐字未改。</li>
 *   <li><b>B 出处</b>：见上两个类。</li>
 * </ol>
 *
 * <h2>⚠️ Kafka 消费容器工厂必须<b>显式</b>建</h2>
 * <p>Boot 的 {@code ConcurrentKafkaListenerContainerFactoryConfigurer} <b>不会</b>参与
 * （我们自己 {@code new} 了这个 factory）⇒ {@code spring.kafka.listener.concurrency}
 * 这条 yaml 键<b>没有任何消费点</b>（AGENTS.md §3.3 同型：配置里没写 ≠ 没开，写了也可能没接）。
 * <p>⇒ ⇒ 并发度只能在这里 {@code setConcurrency(likeKafkaTopics.getConsumers())}
 * （Go 侧 {@code mq.yaml Kafka.Consumers: 8}）⇒ <b>必须显式设置</b>，否则默认 1 ⇒ 消费吞吐差 8 倍。
 *
 * <h2>🔴 {@code LikeKafkaTopics} 必须<b>显式</b>注册（2026-11-03 真上下文测试抓出）</h2>
 * <p><b>B 出处</b>：B 侧 {@code LikeRpcApplication} 用的是
 * {@code @ConfigurationPropertiesScan(basePackages = "com.rag.like.like.rpc.config")}
 * ⇒ 该包下的<b>所有</b> {@code @ConfigurationProperties} 类都被注册
 * ⇒ 其中就包括 {@code com.rag.like.like.rpc.config.LikeKafkaTopics}。
 * <p>⚠️ <b>D7 包压平把 {@code LikeKafkaTopics} 从 {@code config/} 移到了 {@code messaging/}</b>
 * ⇒ ⇒ 若 C 侧照抄「扫 {@code config} 包」的写法，{@code LikeKafkaTopics} 就<b>不在扫描范围内</b>
 * ⇒ ⇒ 它<b>不是 bean</b> ⇒ {@code LikeDomainService} 注入失败
 * ⇒ ⇒ <b>应用直接起不来</b>（{@code No qualifying bean of type 'com.rag.like.messaging.LikeKafkaTopics'}）。
 * <p>⇒ ⇒ 本仓采用<b>显式列举</b>（与 follow 域同款）：把三个 {@code @ConfigurationProperties} 类
 * 都写进 {@code @EnableConfigurationProperties} ⇒ <b>不受包压平影响</b>。
 * <p>⚠️ 这条缺陷<b>编译期与单测都发现不了</b>（只有「起真实 Spring 上下文」才会暴露）
 * ⇒ 由 {@code LikeHttpWireContractRealTomcatTest} 的真上下文启动钉住。
 */
@SpringBootApplication
@EnableConfigurationProperties({LikeApiProperties.class, LikeRpcProperties.class,
        LikeKafkaTopics.class})
@EnableDiscoveryClient
@MapperScan("com.rag.like.mapper")
@EnableScheduling
public class LikeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LikeServiceApplication.class, args);
    }

    /**
     * Kafka 监听容器工厂 —— 逐条照抄 B 侧 {@code LikeRpcApplication} 的同名 Bean。
     *
     * <p>⚠️ <b>三个设置缺一不可</b>：
     * <ol>
     *   <li>{@code setConsumerFactory} —— 没有它，容器拿不到 consumer；</li>
     *   <li>{@code setCommonErrorHandler(LikeUpdateKafkaListener.goAlignedErrorHandler())}
     *       —— 对齐 Go 的 {@code ForceCommit=true}（失败也提交位点、不重投）；</li>
     *   <li>{@code setConcurrency(likeKafkaTopics.getConsumers())}
     *       —— Go 侧 {@code mq.yaml Kafka.Consumers: 8}（8 个消费协程）。</li>
     * </ol>
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            LikeKafkaTopics likeKafkaTopics) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(LikeUpdateKafkaListener.goAlignedErrorHandler());
        factory.setConcurrency(likeKafkaTopics.getConsumers());
        return factory;
    }
}
