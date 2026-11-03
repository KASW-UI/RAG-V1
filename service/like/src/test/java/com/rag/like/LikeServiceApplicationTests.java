package com.rag.like;

import com.rag.like.config.LikeApiProperties;
import com.rag.like.config.LikeRpcProperties;
import com.rag.like.messaging.LikeKafkaTopics;
import com.rag.like.support.NacosStubConfiguration;
import com.rag.like.support.TestPostgres;
import com.rag.like.support.TestRedis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>like 域应用上下文能真正起来</b>（真 PG ＋ 真 Redis ＋ 真 Flyway ＋ 真 Tomcat）。
 *
 * <h2>🔴 为什么这一层值得单独钉</h2>
 * <p>「<b>编译通过</b>」与「<b>应用起得来</b>」之间隔着一整层「装配」：
 * <ul>
 *   <li>{@code @ConfigurationProperties} 类<b>没被注册</b> ⇒ 依赖它的 bean 注入失败；</li>
 *   <li>Flyway 段放错层级 ⇒ <b>4 项静默失效</b>；</li>
 *   <li>迁移脚本里有一行语法错 ⇒ 启动即失败。</li>
 * </ul>
 * ⇒ ⇒ 这些缺陷<b>编译期与单测都发现不了</b> ⇒ 必须有一条「真起一次上下文」的测试。
 *
 * <h2>本类顺带钉住的三件事</h2>
 * <ol>
 *   <li><b>{@code LikeKafkaTopics} 必须是 bean</b>（2026-11-03 本轮真上下文测试抓出过一个真实缺陷：
 *       D7 包压平把它从 {@code config/} 移到了 {@code messaging/}，而 B 侧的
 *       {@code @ConfigurationPropertiesScan} 扫的是 {@code config} 包 ⇒ 照抄会漏注册
 *       ⇒ <b>应用直接起不来</b>）；</li>
 *   <li><b>Flyway 真的跑了</b>：5 张表都在（⇒ 迁移脚本 + 账本表名 + baseline-version 都生效）；</li>
 *   <li><b>fail-closed 的 JWT 密钥真的被读到</b>（{@code LikeApiProperties} 的 {@code @PostConstruct} 没炸）。</li>
 * </ol>
 */
@SpringBootTest(classes = {LikeServiceApplication.class, NacosStubConfiguration.class})
@ActiveProfiles("test")
class LikeServiceApplicationTests {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private LikeKafkaTopics likeKafkaTopics;

    @Autowired
    private LikeApiProperties likeApiProperties;

    @Autowired
    private LikeRpcProperties likeRpcProperties;

    @Test
    @DisplayName("🔴 应用上下文能起来（真 PG ＋ 真 Redis ＋ 真 Flyway ＋ 真 Tomcat）")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("🔴 LikeKafkaTopics 必须是 bean（D7 包压平后最容易漏注册的那个）")
    void kafkaTopicsIsRegistered() {
        assertThat(likeKafkaTopics)
                .as("D7 把 LikeKafkaTopics 从 config/ 移到了 messaging/ ⇒ 必须显式注册，否则应用起不来")
                .isNotNull();
        assertThat(likeKafkaTopics.getLikeTopic()).isEqualTo("like_promotion_topic");
        assertThat(likeKafkaTopics.getConsumers()).isEqualTo(8);
    }

    @Test
    @DisplayName("🔴 两个 @ConfigurationProperties 类都绑上了（fail-closed 密钥没炸 ⇒ 绑上了）")
    void configurationPropertiesAreBound() {
        assertThat(likeApiProperties.getUserAuth().getAccessSecret())
                .as("test profile 自带密钥 ⇒ @PostConstruct 的 fail-closed 校验通过")
                .isNotBlank();
        assertThat(likeApiProperties.getUserAuth().getAccessExpire()).isEqualTo(31536000L);
        assertThat(likeRpcProperties.getRedis().getCacheTtl().getSeconds()).isEqualTo(3600L);
        assertThat(likeRpcProperties.getFact().isDispatchEnabled()).isFalse();
    }

    @Test
    @DisplayName("🔴 Flyway 真跑过：5 张表都在（迁移脚本 + 独立账本 + baseline-version: 0 都生效）")
    void migrationsRan() {
        for (String table : new String[]{"like_record", "like_consume_inbox", "like_outbox_event",
                "like_domain_fact_outbox", "like_fact_stream"}) {
            Integer count = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class)
                    .queryForObject("""
                            SELECT count(*) FROM information_schema.tables
                            WHERE table_schema = 'public' AND table_name = ?
                            """, Integer.class, table);
            assertThat(count).as("表 %s 必须存在", table).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("🔴 @KafkaListener 真进了 KafkaListenerEndpointRegistry（AGENTS §3.3 装配层断言）")
    void kafkaListenerIsRegistered() {
        // ⚠️ 「类上有 @KafkaListener 注解」**不构成**「机制生效」的证据（AGENTS §3.3）。
        //    这里断言的是**端点真的被注册进了 registry**，且 id / topic / group / 并发度逐条对上 B 侧。
        var registry = context.getBean(KafkaListenerEndpointRegistry.class);
        var containers = registry.getListenerContainers();
        assertThat(containers).as("like 域必须恰好有 1 个 @KafkaListener 端点").hasSize(1);

        var container = registry.getListenerContainer("like-update-consumer");
        assertThat(container).as("listener id 必须是 like-update-consumer").isNotNull();

        assertThat(container.getListenerId()).isEqualTo("like-update-consumer");
        assertThat(container).isInstanceOf(ConcurrentMessageListenerContainer.class);

        var props = ((ConcurrentMessageListenerContainer) container).getContainerProperties();
        assertThat(props.getTopics()).containsExactly("like_promotion_topic");
        assertThat(props.getGroupId()).isEqualTo("like-update-group");
        assertThat(((ConcurrentMessageListenerContainer) container).getConcurrency())
                .as("B 侧 mq.yaml Kafka.Consumers: 8 ⇒ 并发度必须是 8；"
                        + "Boot 默认工厂只会读 spring.kafka.listener.concurrency（本域未配 ⇒ 1）"
                        + "⇒ 并发度 = 8 本身就证明了**域内自建容器工厂**被用上了")
                .isEqualTo(8);
    }

    @Test
    @DisplayName("🔴 Kafka 自动配置真的装配了（ConsumerFactory 存在 ⇒ KafkaAutoConfiguration 生效）")
    void kafkaAutoConfigurationIsActive() {
        // ⇒ 这是「Boot 3.5 下只引底层 spring-kafka 也能拿到自动配置」的运行证据。
        assertThat(context.getBean(org.springframework.kafka.core.ConsumerFactory.class)).isNotNull();
        assertThat(context.getBean(org.springframework.kafka.core.KafkaTemplate.class)).isNotNull();
        assertThat(context.getBean(
                org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class))
                .isNotNull();
    }
}
