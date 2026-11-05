package com.rag.comment;

import com.rag.comment.config.CommentApiProperties;
import com.rag.comment.config.CommentRpcProperties;
import com.rag.comment.messaging.CommentKafkaTopics;
import com.rag.comment.support.NacosStubConfiguration;
import com.rag.comment.support.TestPostgres;
import com.rag.comment.support.TestRedis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>comment 域应用上下文能真正起来</b>（真 PG ＋ 真 Redis ＋ 真 Flyway ＋ 真 Tomcat）。
 *
 * <h2>🔴 为什么这一层值得单独钉</h2>
 * <p>「<b>编译通过</b>」与「<b>应用起得来</b>」之间隔着一整层「装配」：
 * <ul>
 *   <li>{@code @ConfigurationProperties} 类<b>没被注册</b> ⇒ 依赖它的 bean 注入失败；</li>
 *   <li>Flyway 段放错层级 ⇒ 账本撞号 ⇒ <b>V1..V8 整条被跳过</b>；</li>
 *   <li>迁移脚本里有一行语法错 ⇒ 启动即失败。</li>
 * </ul>
 * ⇒ ⇒ 这些缺陷<b>编译期与单测都发现不了</b> ⇒ 必须有一条「真起一次上下文」的测试。
 *
 * <h2>本类顺带钉住的三件事</h2>
 * <ol>
 *   <li><b>{@code CommentKafkaTopics} 必须是 bean</b> —— ⚠️ 这是本域<b>最容易踩的装配坑</b>：
 *       D7 包压平把它从 B 侧的 {@code rpc/config/} 移到了 {@code messaging/}，
 *       而 B 侧的 {@code @ConfigurationPropertiesScan} 扫的是 {@code config} 包
 *       ⇒ ⇒ 照抄会<b>漏注册</b> ⇒ ⇒ <b>应用直接起不来</b>
 *       ⇒ C 侧改用<b>显式列举</b>（{@code @EnableConfigurationProperties}）⇒ 本测试守住它。</li>
 *   <li><b>Flyway 真的跑了</b>：7 张表都在（⇒ 迁移脚本 ＋ 独立账本表名 ＋ baseline 都生效）。</li>
 *   <li><b>fail-closed 的 JWT 密钥真的被读到</b>（{@code CommentApiProperties} 的校验没炸）。</li>
 * </ol>
 */
@SpringBootTest(classes = {CommentServiceApplication.class, NacosStubConfiguration.class})
@ActiveProfiles("test")
class CommentServiceApplicationTests {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CommentKafkaTopics commentKafkaTopics;

    @Autowired
    private CommentApiProperties commentApiProperties;

    @Autowired
    private CommentRpcProperties commentRpcProperties;

    @Test
    @DisplayName("🔴 应用上下文能起来（真 PG ＋ 真 Redis ＋ 真 Flyway ＋ 真 Tomcat）")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("🔴 CommentKafkaTopics 必须是 bean（D7 包压平后最容易漏注册的那个）")
    void kafkaTopicsIsRegistered() {
        assertThat(commentKafkaTopics)
                .as("D7 把 CommentKafkaTopics 从 config/ 移到了 messaging/ ⇒ 必须显式注册，否则应用起不来")
                .isNotNull();
        assertThat(commentKafkaTopics.getAuditTopic()).isEqualTo("comment-audit-topic");
        assertThat(commentKafkaTopics.getAuditConcurrency()).isEqualTo(8);
    }

    @Test
    @DisplayName("🔴 两个 @ConfigurationProperties 类都绑上了（fail-closed 密钥没炸 ⇒ 绑上了）")
    void configurationPropertiesAreBound() {
        assertThat(commentApiProperties.getUserAuth().getAccessSecret())
                .as("test profile 自带密钥 ⇒ fail-closed 校验通过")
                .isNotBlank();
        assertThat(commentApiProperties.getUserAuth().getAccessExpire()).isEqualTo(31536000L);
        // ⚠️ 敏感词默认词库是 L1（逐字一致）
        assertThat(commentRpcProperties.getSensitiveWords())
                .containsExactlyInAnyOrder("傻逼", "我草");
        assertThat(commentRpcProperties.getKafka().getAuditTopic()).isEqualTo("comment-audit-topic");
        assertThat(commentRpcProperties.getKafka().getAuditConcurrency()).isEqualTo(8);
        assertThat(commentRpcProperties.getFact().isDispatchEnabled()).isFalse();
        assertThat(commentRpcProperties.getAuthority().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("🔴 Flyway 真跑过：7 张表都在（迁移脚本 ＋ 独立账本 ＋ baseline 都生效）")
    void migrationsRan() {
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        for (String table : new String[]{"subject", "comment_index", "comment_content", "comment_like",
                "report_record", "comment_domain_fact_outbox", "comment_fact_stream"}) {
            Integer count = jdbc.queryForObject("""
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
        //    这里断言的是**端点真的被注册进了 registry**，且 topic / group / 并发度逐条对上 B 侧。
        var registry = context.getBean(KafkaListenerEndpointRegistry.class);
        var containers = registry.getListenerContainers();
        assertThat(containers).as("comment 域必须恰好有 1 个 @KafkaListener 端点").hasSize(1);

        var container = containers.iterator().next();
        assertThat(container).isInstanceOf(ConcurrentMessageListenerContainer.class);

        var props = ((ConcurrentMessageListenerContainer) container).getContainerProperties();
        assertThat(props.getTopics()).containsExactly("comment-audit-topic");
        assertThat(props.getGroupId()).isEqualTo("comment-audit-group");
        assertThat(((ConcurrentMessageListenerContainer) container).getConcurrency())
                .as("B 侧 comment.kafka.audit-concurrency = 8 ⇒ 并发度必须是 8；"
                        + "并发度写在 @KafkaListener 上（不是 spring.kafka.listener.concurrency）"
                        + "⇒ 读到 8 本身就证明了**域内自建容器工厂**被用上了")
                .isEqualTo(8);
        // ⚠️ 不断言 listener id —— C 侧的 @KafkaListener **没有显式 id**
        //    （与 B 侧逐字一致）⇒ id 由框架自动生成，断言它等于「拿人肉读出来的字符串」
        //    只会把测试绑死在一个与业务无关的框架实现细节上。
    }

    @Test
    @DisplayName("🔴 Kafka 自动配置真的装配了（ConsumerFactory 存在 ⇒ KafkaAutoConfiguration 生效）")
    void kafkaAutoConfigurationIsActive() {
        // ⇒ 这是「Boot 3.5 下只引底层 spring-kafka 也能拿到自动配置」的运行证据。
        assertThat(context.getBean(ConsumerFactory.class)).isNotNull();
        assertThat(context.getBean(KafkaTemplate.class)).isNotNull();
        assertThat(context.getBean(ConcurrentKafkaListenerContainerFactory.class)).isNotNull();
    }
}
