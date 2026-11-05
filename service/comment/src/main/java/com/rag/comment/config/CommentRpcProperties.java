package com.rag.comment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * <p><b>comment-rpc 侧配置</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/config/CommentRpcProperties.java:1-160}
 * （绑定前缀 {@code comment}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.config.CommentRpcProperties}
 * ⇒ C 侧 {@code com.rag.comment.config.CommentRpcProperties}。
 *
 * <h3>⚠️ Kafka 段为何同时存在于本类与 {@code messaging/CommentKafkaTopics}</h3>
 * <p>B 侧<b>确实</b>有两处：{@code CommentRpcProperties.Kafka}（前缀 {@code comment.kafka}）
 * 与 {@code CommentKafkaTopics}（前缀 {@code comment.kafka}）—— <b>两者绑定同一段 yaml</b>。
 * B 侧的生产端 {@code CommentAuditPublisher} 用的是<b>前者</b>（{@code props.getKafka().getAuditTopic()}），
 * 而消费端 {@code CommentAuditListener} 用的是 yaml 占位符 {@code ${comment.kafka.audit-topic}}。
 * <p>⇒ C 侧<b>逐字保留这个双份结构</b>（映射表 §1.3 第 157 行：1 个 B 侧 config 类 → 2 个
 * {@code @ConfigurationProperties}），不做「统一成一份」的改动 ——
 * 那会改掉 B 侧的绑定行为。
 *
 * <h3>⚠️ 敏感词词库默认值逐字一致</h3>
 * <p>默认值 {@code ["傻逼","我草"]} 与 B 侧逐字相同
 * （B 侧 {@code application.yml:272-274} + {@code CommentSensitiveFilter.java:19-21} 自记
 * 「默认值与上游逐字一致」）⇒ 词库内容属<b>业务语义</b>，不是配置。
 */
@ConfigurationProperties(prefix = "comment")
public class CommentRpcProperties {

    /** Kafka 段（与 {@code messaging/CommentKafkaTopics} 绑定同一段 yaml —— 见类注释）。 */
    private final Kafka kafka = new Kafka();

    /** 敏感词词库（默认值逐字照抄 B 侧）。 */
    private List<String> sensitiveWords = List.of("傻逼", "我草");

    /** 事实投递段。 */
    private final Fact fact = new Fact();

    /** 权威读端点段。 */
    private final Authority authority = new Authority();

    public List<String> getSensitiveWords() {
        return sensitiveWords;
    }

    public void setSensitiveWords(List<String> sensitiveWords) {
        this.sensitiveWords = sensitiveWords;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public Fact getFact() {
        return fact;
    }

    public Authority getAuthority() {
        return authority;
    }

    /** Kafka 段（生产端读这一份）。 */
    public static class Kafka {

        /** 审计 topic（B 侧 {@code comment-audit-topic}）。 */
        private String auditTopic = "comment-audit-topic";

        /** 消费并发度（B 侧 {@code Go 侧 KqConsumerConf.Consumers: 8}）。 */
        private int auditConcurrency = 8;

        /** 同步推送超时（B 侧 {@code publish-timeout}）。 */
        private Duration publishTimeout = Duration.ofSeconds(10);

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

        public Duration getPublishTimeout() {
            return publishTimeout;
        }

        public void setPublishTimeout(Duration publishTimeout) {
            this.publishTimeout = publishTimeout;
        }
    }

    /**
     * 事实投递段。
     *
     * <p>⚠️ {@code dispatchEnabled} 默认 <b>false</b> ⇒ 默认<b>不装配</b>调度器。
     */
    public static class Fact {

        /** 是否启用事实投递调度器（B 侧默认 false）。 */
        private boolean dispatchEnabled = false;

        /** 数据中心端点。 */
        private String dcEndpoint = "";

        /** 数据中心令牌。 */
        private String dcToken = "";

        /** 数据中心调用超时。 */
        private Duration dcTimeout = Duration.ofSeconds(8);

        /** 单次模式（B 侧 {@code -once}；遗留子项 P6-a 待拍板）。 */
        private boolean once = false;

        public boolean isDispatchEnabled() {
            return dispatchEnabled;
        }

        public void setDispatchEnabled(boolean dispatchEnabled) {
            this.dispatchEnabled = dispatchEnabled;
        }

        public String getDcEndpoint() {
            return dcEndpoint;
        }

        public void setDcEndpoint(String dcEndpoint) {
            this.dcEndpoint = dcEndpoint;
        }

        public String getDcToken() {
            return dcToken;
        }

        public void setDcToken(String dcToken) {
            this.dcToken = dcToken;
        }

        public Duration getDcTimeout() {
            return dcTimeout;
        }

        public void setDcTimeout(Duration dcTimeout) {
            this.dcTimeout = dcTimeout;
        }

        public boolean isOnce() {
            return once;
        }

        public void setOnce(boolean once) {
            this.once = once;
        }
    }

    /**
     * 权威读端点段。
     *
     * <p>⚠️ {@code enabled} 默认 <b>false</b> ⇒ 默认<b>不装配</b>权威读端点
     * （B 侧 {@code CommentAuthorityEndpointConfig.java:42}）。
     */
    public static class Authority {

        /** 是否启用权威读端点（B 侧默认 false）。 */
        private boolean enabled = false;

        /** 端点令牌。 */
        private String token = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }
}
