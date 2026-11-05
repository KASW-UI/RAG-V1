package com.rag.comment.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <p><b>comment 域 Kafka topic 绑定</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/config/CommentKafkaTopics.java:1-40}
 * （绑定前缀 {@code comment.kafka}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平 · 映射表 §0.5）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.config.CommentKafkaTopics}
 * ⇒ C 侧 {@code com.rag.comment.messaging.CommentKafkaTopics}。
 *
 * <h3>🔴 本类必须被<b>显式</b>注册进 {@code @EnableConfigurationProperties}</h3>
 * <p>B 侧用的是 {@code @ConfigurationPropertiesScan(basePackages = "com.rag.comment.comment.rpc.config")}
 * ⇒ 该包下的<b>所有</b> {@code @ConfigurationProperties} 类都被注册
 * ⇒ 其中就包括 {@code CommentKafkaTopics}。
 * <p>⚠️ <b>D7 包压平把 {@code CommentKafkaTopics} 从 {@code config/} 移到了 {@code messaging/}</b>
 * ⇒ ⇒ 若 C 侧照抄「扫 {@code config} 包」的写法，{@code CommentKafkaTopics} 就<b>不在扫描范围内</b>
 * ⇒ ⇒ 它<b>不是 bean</b> ⇒ 依赖它的 Bean 注入失败
 * ⇒ ⇒ <b>应用直接起不来</b>。
 * <p>⇒ ⇒ 本仓采用<b>显式列举</b>（与 like 域同款）：把三个 {@code @ConfigurationProperties} 类
 * 都写进 {@code @EnableConfigurationProperties} ⇒ <b>不受包压平影响</b>。
 * <p>⚠️ 这条缺陷<b>编译期与单测都发现不了</b>（只有「起真实 Spring 上下文」才会暴露）
 * ⇒ 由真上下文启动测试钉住。
 *
 * <h3>⚠️ 与 {@code config/CommentRpcProperties.Kafka} 绑定同一段 yaml</h3>
 * <p>B 侧<b>确实</b>有两处绑定同一前缀的类（详见 {@code config/CommentRpcProperties} 的类注释）
 * ⇒ C 侧逐字保留这个双份结构，不做「统一成一份」的改动。
 */
@ConfigurationProperties(prefix = "comment.kafka")
public class CommentKafkaTopics {

    /** 审计 topic（B 侧 {@code comment-audit-topic}）。 */
    private String auditTopic = "comment-audit-topic";

    /** 消费并发度（B 侧 {@code Go 侧 KqConsumerConf.Consumers: 8}）。 */
    private int auditConcurrency = 8;

    /**
     * 审计消费组 id —— B 侧 {@code comment-audit-group}。
     *
     * <p>⚠️ <b>这是 B 侧的写死值</b>（B 侧 {@code CommentKafkaTopics#auditGroupId()} 直接返回字面量，
     * <b>不读配置</b>）⇒ C 侧逐字保留。
     */
    public String auditGroupId() {
        return "comment-audit-group";
    }

    public String getAuditTopic() {
        return auditTopic;
    }

    public void setAuditTopic(String auditTopic) {
        this.auditTopic = auditTopic;
    }

    public int getAuditConcurrency() {
        return auditConcurrency;
    }

    public void setAuditConcurrency(int auditConcurrency) {
        this.auditConcurrency = auditConcurrency;
    }
}
