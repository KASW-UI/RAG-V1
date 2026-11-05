package com.rag.hot.config;

import com.rag.hot.messaging.HotEventConsumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p><b>Kafka 监听容器的<b>两项容器级语义</b></b>：分区分配回调 与 错误处理策略。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/config/HotKafkaConfig.java}
 * （1-156 行；类声明 {@code :45-46}；{@code hotKafkaListenerContainerFactory} {@code :88-108}；
 * {@code zeroRetryErrorHandler} {@code :116-120}；{@code rebalanceListener} {@code :126-148}；
 * {@code ownedPartitions} {@code :150-155}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>一处 API 回改（Boot 4 → Boot 3.5 · D14）</b> ＋ 包名压平：
 *       <ul>
 *         <li>B 侧的 configurer import 是 {@code org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer}
 *             （<b>Boot 4</b> 拆模块后的路径）⇒ 本仓改用 Boot 3.5 的
 *             {@link ConcurrentKafkaListenerContainerFactoryConfigurer}
 *             （{@code org.springframework.boot.autoconfigure.kafka. ConcurrentKafkaListenerContainerFactoryConfigurer}，
 *             已用 {@code unzip -l spring-boot-autoconfigure-3.5.11.jar} 核到该 class 存在）；</li>
 *         <li>包名 {@code com.rag.hot.hot.rpc.config} → {@code com.rag.hot.config}（D7）；</li>
 *         <li>{@code com.rag.hot.hot.rpc.mq.HotEventConsumer} → {@link HotEventConsumer}（D7 包压平）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>零重试错误处理</b>（{@code FixedBackOff(0,0)} + {@code setAckAfterHandle(true)}）逐字未动；</li>
 *         <li><b>分区分配回调</b>的「只处理本 topic 的分区」过滤逐字未动；</li>
 *         <li>{@code @Lazy} 解循环依赖的<b>理由与时序论证</b>逐字保留（见下方长注释）。</li>
 *       </ul>
 *       ⚠️ <b>Boot 4 → 3.5 的 configurer 是否行为等价</b>：两个版本的
 *       {@code ConcurrentKafkaListenerContainerFactoryConfigurer#configure} 都只读
 *       {@code spring.kafka.listener.*} 与 {@code spring.kafka.consumer.*} 两族键
 *       ⇒ ⇒ <b>键名与语义逐条相同</b> ⇒ 本类的「零重试 + 分区回调」两项<b>不依赖</b> configurer 的细节
 *       ⇒ ⇒ 行为等价。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 本类承载的是 L3「MQ 失败处理」语义，不是配置糖</h2>
 * 缺任何一项都会让 B 侧的行为发生实质改变。
 * <ol>
 *   <li><b>分区分配回调：B 侧的 Setup / Cleanup</b>
 *       B 侧的 {@code HotHandler} 实现了 sarama 的 {@code ConsumerGroupHandler}：
 *       {@code Setup → OnPartitionsAssigned}、{@code Cleanup → OnPartitionsRevoked}，
 *       且<b>只处理与自身 topic 相同的 claim</b>。
 *       Spring Kafka 的 {@code @KafkaListener} 没有直接的 assign/revoke 钩子，
 *       须通过容器的 {@link ConsumerRebalanceListener} 提供 —— 这是 Spring 官方的对应机制。</li>
 *   <li><b>零重试错误处理：drop 语义的最后一道防线</b>
 *       B 侧的消费循环<b>无条件</b> {@code sess.MarkMessage(msg, "")}，
 *       即使 {@code consumeMessage} 返回错误 ⇒ 坏消息静默丢弃、位点照常推进、<b>永不重试</b>。
 *       而 Spring Kafka 的默认 {@code DefaultErrorHandler} 是 {@code FixedBackOff(0L, 9)}
 *       ——<b>重试 9 次后</b>才跳过。这条链路上任何一条坏消息都会<b>卡住分区 9 轮</b>。
 *       ⇒ 这里显式改成 {@code FixedBackOff(0L, 0L)}（零间隔零重试，失败即跳过并提交位点）
 *       ⇒ 与 B 侧的无条件 MarkMessage 对齐。
 *       注意这只是<b>兜底</b>：{@link HotEventConsumer} 本身已保证 listener 永不外抛，
 *       所以正常情况下根本走不到这里。</li>
 * </ol>
 */
@Configuration
public class HotKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(HotKafkaConfig.class);

    /**
     * <p><b>专用容器工厂</b>：注入零重试错误处理 + 分区重平衡回调。
     *
     * <p>用具名 bean 而非覆盖 Boot 的默认 {@code kafkaListenerContainerFactory}，
     * 避免影响上下文里其它可能的 listener；由
     * {@code @KafkaListener(containerFactory = "hotKafkaListenerContainerFactory")} 显式引用。
     *
     * <h2>⚠️ 为什么 {@code consumer} 参数上必须有 {@link Lazy}</h2>
     * <p>本方法第 4 个参数注入 {@link HotEventConsumer}，是为了给 rebalance 回调一个接收方
     * （{@code consumer.onPartitionsAssigned / onPartitionsRevoked}）。
     * 而 {@code HotEventConsumer} 上的
     * {@code @KafkaListener(containerFactory = "hotKafkaListenerContainerFactory")}
     * 又让 Spring 在创建该 Bean 时去解析<b>本工厂</b> ⇒
     * <b>工厂 → consumer → 工厂</b> 的循环依赖。
     *
     * <p>不加 {@code @Lazy} 时的实测报错：
     * <pre>
     * BeanCreationException: hotKafkaListenerContainerFactory
     *   └─ parameter 3 → hotEventConsumer
     *        └─ BeanCurrentlyInCreationException: Requested bean is currently in creation
     * </pre>
     *
     * <p><b>{@code @Lazy} 在这里安全，且不改变任何可观察行为</b>，依据是<b>时序</b>：
     * {@link ConsumerRebalanceListener} 的两个回调<b>只在 consumer 容器已经启动、
     * 并加入消费组拿到分区之后</b>才可能被触发；
     * 那时 {@code HotEventConsumer} 必然已经创建完毕 ⇒ 延迟解析拿到的是真实 Bean，
     * 既不会 NPE，也不会改变回调触发时机与次数。
     *
     * <p>📌 <b>不要改成「让 factory 不依赖 consumer」</b>（例如把分区回调整体搬走）：
     * 那会改变 {@code HotEventConsumer} 的职责边界，而它正是 B 侧
     * {@code HotHandler} 的对位物 —— 分区回调留在 consumer 上，与 B 侧的归属一致。
     *
     * @param configurer      Boot 3.5 的 Kafka 容器工厂配置器（读 {@code spring.kafka.listener.*}）
     * @param consumerFactory Spring Kafka 的消费者工厂
     * @param props           hot 域的 rpc 段配置（提供 topic 名）
     * @param consumer        消费器（延迟解析，解循环依赖）
     * @return 专用容器工厂
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> hotKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            HotRpcProperties props,
            @Lazy HotEventConsumer consumer) {
        // Boot 的 ConcurrentKafkaListenerContainerFactoryConfigurer.configure 签名就是
        // <Object,Object>，这也是 Spring Boot 默认工厂的形状；listener 方法签名里的
        // String 由 MessagingMessageListenerAdapter 按方法参数类型处理。
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);

        // ① 零重试：对齐 B 侧的无条件 MarkMessage（drop 而非 retry）
        factory.setCommonErrorHandler(zeroRetryErrorHandler());

        // ② 分区回调：对齐 B 侧的 Setup / Cleanup
        factory.getContainerProperties().setConsumerRebalanceListener(rebalanceListener(props, consumer));

        return factory;
    }

    /**
     * <p><b>{@link DefaultErrorHandler} + {@code FixedBackOff(0, 0)}</b>：一旦进入错误处理，
     * 立即放弃该记录。
     *
     * <p>{@code setAckAfterHandle(true)} 保证「处理完（含放弃）」后提交位点，
     * 使坏消息的位点<b>照常推进</b>——这正是 B 侧的无条件 MarkMessage。
     *
     * @return 零重试错误处理器
     */
    static DefaultErrorHandler zeroRetryErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(0L, 0L));
        handler.setAckAfterHandle(true);
        return handler;
    }

    /**
     * <p><b>分区重平衡回调</b>：对应 B 侧 {@code Setup} / {@code Cleanup} ——
     * 只对本服务的 topic 生效（B 侧 {@code hot_handler.go:84-88,93-97} 的
     * {@code if topic == h.topic}）。
     *
     * @param props    hot 域的 rpc 段配置（提供 topic 名）
     * @param consumer 消费器（接收分区事件）
     * @return 重平衡监听器
     */
    static ConsumerRebalanceListener rebalanceListener(HotRpcProperties props, HotEventConsumer consumer) {
        String topic = props.getKafka().getTopic();
        return new ConsumerRebalanceListener() {

            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                List<Integer> pids = ownedPartitions(partitions, topic);
                if (!pids.isEmpty()) {
                    log.info("[Hot] partitions revoked: {}", pids);
                    consumer.onPartitionsRevoked(pids);
                }
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                List<Integer> pids = ownedPartitions(partitions, topic);
                if (!pids.isEmpty()) {
                    log.info("[Hot] partitions assigned: {}", pids);
                    consumer.onPartitionsAssigned(pids);
                }
            }
        };
    }

    /**
     * 过滤出「属于本服务 topic」的分区号 —— 对应 B 侧的 {@code if topic == h.topic} 守卫。
     *
     * @param partitions 容器分配/撤销的分区集合
     * @param topic      本服务的 topic 名
     * @return 属于本 topic 的分区号列表
     */
    private static List<Integer> ownedPartitions(Collection<TopicPartition> partitions, String topic) {
        return partitions.stream()
                .filter(tp -> tp.topic().equals(topic))
                .map(TopicPartition::partition)
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
