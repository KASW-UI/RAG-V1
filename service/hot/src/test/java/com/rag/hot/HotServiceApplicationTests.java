package com.rag.hot;

import com.rag.hot.config.HotApiProperties;
import com.rag.hot.config.HotRpcProperties;
import com.rag.hot.controller.HotArticleController;
import com.rag.hot.grpc.HotGrpcService;
import com.rag.hot.messaging.HotEventConsumer;
import com.rag.hot.messaging.HotMergeScheduler;
import com.rag.hot.messaging.HotPartitionSketchRegistry;
import com.rag.hot.messaging.HotRedisSnapshotStore;
import com.rag.hot.support.HotKafkaTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>hot 域合并进程（D1）的<b>装配面</b>冒烟测试</b> —— 证明「api + rpc 合并成一个进程」
 * 之后，该在的组件<b>都在</b>、该配的<b>都配上了</b>。
 *
 * <h2>为什么它复用 {@link HotKafkaTestBase} 的上下文</h2>
 * <p>两个测试类的 {@code @SpringBootTest} 配置<b>完全一致</b>
 * （同一份 {@code classes} + 同一份 {@code properties} + 同一个
 * {@code @DynamicPropertySource}）⇒ ⇒ Spring 的上下文缓存判定为<b>同一个上下文</b>
 * ⇒ ⇒ 整个 hot 域只起 <b>1 个</b> Spring 上下文（而不是 2 个）⇒ 省掉一次
 * Tomcat / Redis / Kafka 装配的启动时间。
 *
 * <h2>它验的是「装配」，不是「行为」</h2>
 * <p>行为面（消费 / 分区 / 快照 / 合并 / HTTP）由
 * {@code HotEventConsumerRealKafkaTest} 与其它真栈测试覆盖；
 * 本类只钉住<b>「合并后这个进程里该有的东西有没有」</b>：
 * <ol>
 *   <li><b>四个技术角色包的主 bean 都在</b>（消息面 4 个 + gRPC + HTTP 入口 + 两份配置）；</li>
 *   <li><b>A4 的运行期证据</b>：{@code grpc.client.article-rpc.negotiation-type} 真的是
 *       {@code plaintext}（devh 默认是 TLS ⇒ 不配就会静默变 TLS）；</li>
 *   <li><b>listener 容器工厂的名字与引用一致</b>
 *       （{@code @KafkaListener(containerFactory = "hotKafkaListenerContainerFactory")}
 *       引用的那个 bean 真的存在）。</li>
 * </ol>
 *
 * <p>⚠️ <b>「Bean 存在」≠「机制生效」</b>（§3.3）—— 本类只覆盖「存在」这一层；
 * 「生效」的证据在 {@code HotEventConsumerRealKafkaTest}（容器 running + 真消费）。
 */
class HotServiceApplicationTests extends HotKafkaTestBase {

    /** Spring 上下文。 */
    @Autowired
    private ApplicationContext context;

    /** Spring 环境（读配置值）。 */
    @Autowired
    private Environment environment;

    /** listener 端点注册表。 */
    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    @DisplayName("合并进程启动成功：消息面 4 个 bean + gRPC + HTTP 入口 + 两份配置都在")
    void mergedProcessHasAllDomainBeans() {
        // ── 消息面（B 侧 hot-rpc 的 mq/ 5 件套中的 4 个行为类）──
        assertThat(context.getBean(HotEventConsumer.class)).isNotNull();
        assertThat(context.getBean(HotPartitionSketchRegistry.class)).isNotNull();
        assertThat(context.getBean(HotRedisSnapshotStore.class)).isNotNull();
        assertThat(context.getBean(HotMergeScheduler.class)).isNotNull();
        // ── gRPC 面 ──
        assertThat(context.getBean(HotGrpcService.class)).isNotNull();
        // ── HTTP 面 ──
        assertThat(context.getBean(HotArticleController.class)).isNotNull();
        // ── 两份配置绑定点（api 段 + rpc 段）──
        assertThat(context.getBean(HotApiProperties.class)).isNotNull();
        assertThat(context.getBean(HotRpcProperties.class)).isNotNull();
    }

    @Test
    @DisplayName("A4 运行期证据：article-rpc 的 negotiation-type 是 plaintext（devh 默认 TLS）")
    void articleRpcNegotiationIsPlaintext() {
        assertThat(environment.getProperty("grpc.client.article-rpc.negotiation-type"))
                .isEqualTo("plaintext");
    }

    @Test
    @DisplayName("listener 用的容器工厂 bean 真的存在，且容器已注册")
    void listenerContainerFactoryBeanExists() {
        assertThat(context.getBean("hotKafkaListenerContainerFactory"))
                .isInstanceOf(ConcurrentKafkaListenerContainerFactory.class);
        assertThat(listenerRegistry.getListenerContainers()).isNotEmpty();
    }

    @Test
    @DisplayName("出向 gRPC 的上界只有一个来源：域侧 hot-api.article-rpc.timeout = 2000ms")
    void outboundDeadlineCapHasSingleSource() {
        // 🔴 共享层的 cap **刻意未配置**（默认 0 = 不启用）⇒ 上界只由域侧承载
        //    ⇒ 若有人同时配了两处 ⇒ 就会出现「两个上界来源」⇒ 行为不可预期。
        String shared = environment.getProperty("rag.protection.outbound-deadline.cap-millis");
        assertThat(shared).as("共享层的 cap-millis 必须保持未配置（单一来源纪律）").isNull();

        assertThat(context.getBean(HotApiProperties.class).getArticleRpc().getTimeout())
                .isEqualTo(2000);
    }
}
