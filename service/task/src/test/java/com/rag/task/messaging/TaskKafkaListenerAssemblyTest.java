package com.rag.task.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.rag.task.support.TaskRealInfraTestBase;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * <p><b>Kafka 消费面的<b>装配层</b>断言</b> —— 真 Spring 上下文，真
 * {@link KafkaListenerEndpointRegistry}。</p>
 *
 * <h2>🔴 为什么「listener 真进 registry」是本域的硬要求</h2>
 * <p>AGENTS.md §3.3.3：<b>不得凭注解名称断定机制已启用</b>；
 * §三 Kafka 补充（D14 订正）：「用 {@code @KafkaListener} 必配<b>装配层</b>测试断言
 * listener 真进 registry，<b>不得</b>拿「编译通过 / 能启动」当「注解已生效」证据」。
 * <p>⇒ 本类断言的<b>不是</b>「类上有 {@code @KafkaListener}」，
 * 而是<b>容器真的被创建了</b>、<b>topic / group 真的绑对了</b>。</p>
 *
 * <h2>🔴 本类顺带钉住三条「很容易写错、且错了不报错」的绑定</h2>
 * <ol>
 *   <li><b>文章 sink 的 group 是 {@code task.rpc.consumer101}</b>（{@code group-kafka-filter}）
 *       —— <b>不是</b> {@code task.rpc.consumer91}（{@code group-filter-article}，Go 侧无读取点）。
 *       绑错 ⇒ 文章任务进度从头消费或永远收不到消息，而<b>两侧都不报错</b>。</li>
 *   <li><b>ack mode 是 RECORD</b> —— 本域自建 {@code kafkaListenerContainerFactory}
 *       ⇒ Boot 的 configurer <b>不参与</b> ⇒ {@code spring.kafka.listener.ack-mode} 是<b>死键</b>
 *       ⇒ ack mode 只可能来自 {@code TaskServiceApplication} 里的显式设置。</li>
 *   <li><b>六个 listener 一个不少</b> —— 少一个 ⇒ 那条链路静默无流量。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么本类<b>不</b>起真 Kafka 容器</h2>
 * <p>本类验的是<b>装配层</b>（容器被创建、绑定正确），不是端到端消费。
 * <p>⇒ Kafka 指向不可达地址（见 {@code TaskRealInfraTestBase} ②）⇒ 容器照常注册，
 * 但不引入 broker 的抖动。<b>端到端消费</b>的验证留给对拍装置（{@code tools/difftest/}），
 * 不在本仓单测里做。</p>
 */
class TaskKafkaListenerAssemblyTest extends TaskRealInfraTestBase {

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    /** 期望的 (listener id → topic, group) —— 逐条对应 {@code application.yml} 的 {@code task.kafka.*}。 */
    private static final Map<String, List<String>> EXPECTED = Map.of(
            "task-raw-diagnostic", List.of("task.logs.raw", "task.rpc.consumer31"),
            "task-goka-router", List.of("task.logs.raw", "task.rpc.consumer41"),
            "task-goka-user-count", List.of("task.logs.raw.User", "task.rpc.consumer61"),
            "task-goka-article-count", List.of("task.logs.raw.Article", "task.rpc.consumer71"),
            "task-user-progress-sink", List.of("task.logs.filtered.User", "task.rpc.consumer81"),
            "task-article-progress-sink", List.of("task.logs.filtered.Article", "task.rpc.consumer101"));

    @Test
    @DisplayName("① 六个 listener 全部真进了 registry（少一个 ⇒ 那条链路静默无流量）")
    void allSixListenersAreRegistered() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();

        assertThat(containers).hasSize(EXPECTED.size());
        assertThat(containers.stream()
                .map(c -> c.getContainerProperties() instanceof ContainerProperties cp ? cp : null)
                .filter(java.util.Objects::nonNull)
                .count())
                .isEqualTo(EXPECTED.size());
        assertThat(registry.getListenerContainerIds()).containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
    }

    @Test
    @DisplayName("② 每个 listener 的 topic / group 都绑对了（错绑不报错，只是没流量）")
    void everyListenerBindsTheRightTopicAndGroup() {
        for (Map.Entry<String, List<String>> expected : EXPECTED.entrySet()) {
            MessageListenerContainer container = registry.getListenerContainer(expected.getKey());
            assertThat(container).as("listener %s 必须真的注册了", expected.getKey()).isNotNull();

            ContainerProperties props = container.getContainerProperties();
            assertThat(props.getTopics()).as("listener %s 的 topic", expected.getKey())
                    .containsExactly(expected.getValue().get(0));
            assertThat(props.getGroupId()).as("listener %s 的 group", expected.getKey())
                    .isEqualTo(expected.getValue().get(1));
        }
    }

    @Test
    @DisplayName("③ 🔴 ack mode = RECORD（自建 factory ⇒ yaml 的 spring.kafka.listener.ack-mode 是死键）")
    void ackModeIsRecordNotBatch() {
        for (String id : EXPECTED.keySet()) {
            MessageListenerContainer container = registry.getListenerContainer(id);
            assertThat(container).isNotNull();
            assertThat(container.getContainerProperties().getAckMode())
                    .as("listener %s 的 ack mode", id)
                    .isEqualTo(ContainerProperties.AckMode.RECORD);
        }
    }

    @Test
    @DisplayName("④ 🔴 文章 sink 用的是 group-kafka-filter（consumer101），不是 consumer91")
    void articleSinkUsesTheKafkaFilterGroup() {
        MessageListenerContainer container = registry.getListenerContainer("task-article-progress-sink");

        assertThat(container).isNotNull();
        assertThat(container.getContainerProperties().getGroupId()).isEqualTo("task.rpc.consumer101");
        assertThat(container.getContainerProperties().getGroupId())
                .as("Go 侧 GroupFilterArticle（consumer91）全仓无读取点，换过去等于换了一条链路")
                .isNotEqualTo("task.rpc.consumer91");
    }

    @Test
    @DisplayName("⑤ 消费并发度 = 1（Go 的 Init/*.go 每个 kq.NewQueue 硬编码 Consumers: 1, Processors: 1）")
    void concurrencyIsOne() {
        for (String id : EXPECTED.keySet()) {
            MessageListenerContainer container = registry.getListenerContainer(id);
            assertThat(container).isNotNull();
            // ⚠️ getConcurrency() 只在 ConcurrentMessageListenerContainer 上（不在 ContainerProperties）
            assertThat(container).isInstanceOf(ConcurrentMessageListenerContainer.class);
            assertThat(((ConcurrentMessageListenerContainer) container).getConcurrency())
                    .as("listener %s 的并发度", id)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("⑥ 汇总视图：topic → listener 映射与 yml 的 task.kafka.* 一致")
    void topicToListenerMappingMatchesYaml() {
        String mapping = registry.getListenerContainers().stream()
                .map(c -> c.getContainerProperties().getTopics()[0] + " <- "
                        + c.getContainerProperties().getGroupId())
                .sorted()
                .collect(Collectors.joining("\n"));

        assertThat(mapping).contains(
                "task.logs.raw <- task.rpc.consumer31",
                "task.logs.raw <- task.rpc.consumer41",
                "task.logs.raw.User <- task.rpc.consumer61",
                "task.logs.raw.Article <- task.rpc.consumer71",
                "task.logs.filtered.User <- task.rpc.consumer81",
                "task.logs.filtered.Article <- task.rpc.consumer101");
    }
}
