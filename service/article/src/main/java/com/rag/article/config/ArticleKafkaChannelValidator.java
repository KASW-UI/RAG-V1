package com.rag.article.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <p><b>职责</b>：🔴 校验「四个 Kafka 通道各自的 {@code brokers}」与全局
 * {@code spring.kafka.bootstrap-servers} 是否一致 —— <b>不一致就启动失败</b>，而不是静默忽略。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/config/ArticleKafkaChannelValidator.java}
 * （类声明 {@code :98-99} · 字段与构造器 {@code :103-109} · {@code validateOnStartup} {@code :118-136} ·
 * {@code channels} {@code :139-147} · {@code describeMismatch} {@code :149-162} ·
 * {@code normalize} {@code :174-185} · {@code Channel} record {@code :188}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>一处 API 回改（Boot 4 → Boot 3.5 · D14）</b> ——
 *       B 侧 import 的是 {@code org.springframework.boot.kafka.autoconfigure.KafkaProperties}
 *       （<b>Boot 4 专有</b>包路径），本仓改用 Boot 3.5 的
 *       {@link org.springframework.boot.autoconfigure.kafka.KafkaProperties}；
 *       另：<b>包名</b> {@code com.rag.article.article.rpc.config} → {@code com.rag.article.config}（D7 包压平）。
 *       ⇒ <b>判据逻辑、四个通道的清单、报错文案逐字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>两侧 {@code getBootstrapServers()} 都返回 {@code List<String>} ⇒ 归一化与比较口径一致；</li>
 *         <li>「通道 brokers 为空 ⇒ 跳过」「全局为空 ⇒ 跳过」两条边界逐字保留
 *             （空列表在 Go 侧会让客户端起不来；本仓回落到全局 ⇒ 该边界记在签字偏离里）；</li>
 *         <li>{@code normalize} <b>不 trim</b>（逐字比较）这条也保留。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>Go 出处（逐层：类型声明点 → 字段声明点 → 消费点）</h2>
 * <pre>
 * 类型声明点  rpc/internal/config/config.go:19-22
 *              KqPusherConf / KqConsumerConf / ArticleSyncPusherConf / ArticleSyncResultConsumerConf
 *              四个通道的类型都是 kq.KqConf
 *
 * 字段声明点  go-queue@v1.2.2/kq/config.go:12
 *              Brokers []string          ← 就在 KqConf 里，四个通道各有一份
 *
 * 消费点(生产) go-queue@v1.2.2/kq/pusher.go:44-47
 *              kafka.Writer{Addr: kafka.TCP(addrs...)}   ← addrs 就是各通道自己的 Brokers
 * 消费点(消费) go-queue@v1.2.2/kq/queue.go:121
 *              kafka.ReaderConfig{Brokers: c.Brokers}     ← 同理
 *
 * 仓内调用点(生产) rpc/internal/svc/service_context.go:65,66
 * 仓内调用点(消费) rpc/article.go:58,60
 * </pre>
 * ⇒ Go 侧<b>改某个通道的 {@code brokers} 会切 broker</b>（每个通道一个独立的客户端）。
 *
 * <h3>为什么只有四个通道，{@code HotEventPusherConf} 不在其中</h3>
 * Go 侧第五个通道 {@code HotEventPusherConf} 也建了 Pusher，但<b>全模块零调用点</b>
 * ⇒ 它是 Go 侧的<b>死配置</b> ⇒ 本仓<b>不迁移</b>它 ⇒ 自然不在本校验的四个通道里。
 *
 * <h3>Go 侧 yaml 的实际取值（语义等价论证的另一半）</h3>
 * <pre>
 * rpc/etc/article.yaml:29-30   KqPusherConf.Brokers            = [kafka:9092]
 * rpc/etc/article.yaml:46-47   KqConsumerConf.Brokers          = [kafka:9092]
 * rpc/etc/article.yaml:56-57   ArticleSyncPusherConf.Brokers   = [kafka:9092]
 * rpc/etc/article.yaml:62-63   ArticleSyncResultConsumerConf.Brokers = [kafka:9092]
 * </pre>
 * ⇒ Go 侧四个通道连的是<b>同一个</b> broker ⇒ 与本仓「四通道共用全局单例」的实际效果一致。
 *
 * <h2>⚠️ 为什么是「启动失败」而不是「继续跑」</h2>
 * <b>静默忽略</b>是最坏的一种：运维改了通道的 broker，看到「服务正常起来了」，
 * 于是以为流量已经切走 —— <b>实际上所有通道还连在老 broker 上</b>。
 * ⇒ 本校验把它变成<b>响亮的失败</b>：配置一旦分叉，<b>启动即失败</b>，并说清原因与出路。
 */
@Component
public class ArticleKafkaChannelValidator {

    private static final Logger log = LoggerFactory.getLogger(ArticleKafkaChannelValidator.class);

    private final ArticleRpcProperties properties;

    private final List<String> globalBrokers;

    /**
     * 构造器 —— 取全局 broker 列表做比对基准。
     *
     * <p><b>B 出处</b>：{@code .../config/ArticleKafkaChannelValidator.java:106-109}。
     *
     * @param properties      本域的通道配置
     * @param kafkaProperties Spring Boot 的全局 Kafka 配置（{@code spring.kafka.*}）
     */
    public ArticleKafkaChannelValidator(ArticleRpcProperties properties, KafkaProperties kafkaProperties) {
        this.properties = properties;
        this.globalBrokers = normalize(kafkaProperties.getBootstrapServers());
    }

    /**
     * 启动期校验 —— <b>抛异常即启动失败</b>。
     *
     * <p>⚠️ 放在 {@code @PostConstruct}（Bean 初始化期）⇒ 早于容器开始收流量
     * （同 {@code ArticleSchemaGate} 那条纪律：校验必须发生在<b>对外服务之前</b>）。
     *
     * <p><b>B 出处</b>：{@code .../config/ArticleKafkaChannelValidator.java:118-136}。
     */
    @PostConstruct
    public void validateOnStartup() {
        for (Channel channel : channels()) {
            List<String> channelBrokers = normalize(Arrays.asList(channel.conf.getBrokers()));
            if (channelBrokers.isEmpty()) {
                // 该通道没配 brokers ⇒ Go 侧会用空列表（客户端起不来）⇒ 不是本实现的分叉点，跳过
                continue;
            }
            if (globalBrokers.isEmpty()) {
                // 全局没配 ⇒ 无从比较 ⇒ 跳过（Boot 自己会用默认值）
                continue;
            }
            if (!channelBrokers.equals(globalBrokers)) {
                throw new IllegalStateException(describeMismatch(channel, channelBrokers));
            }
        }
        log.info("Kafka 通道校验通过：四个通道的 brokers 与全局 spring.kafka.bootstrap-servers 一致（{}）",
                globalBrokers);
    }

    /**
     * 四个通道（Go 侧各自独立 Brokers 的那四个）。
     *
     * <p><b>B 出处</b>：{@code .../config/ArticleKafkaChannelValidator.java:139-147}。
     */
    private List<Channel> channels() {
        return List.of(
                new Channel("kq-pusher-conf（生产 article-creation-topic）", properties.getKqPusherConf()),
                new Channel("kq-consumer-conf（消费 article-creation-topic）", properties.getKqConsumerConf()),
                new Channel("article-sync-pusher-conf（生产 article-sync-events）",
                        properties.getArticleSyncPusherConf()),
                new Channel("article-sync-result-consumer-conf（消费 article-sync-results）",
                        properties.getArticleSyncResultConsumerConf()));
    }

    /**
     * 分叉时的报错文案 —— 逐字照抄 B 侧（说清「为什么这个值不生效」与「两条出路」）。
     *
     * <p><b>B 出处</b>：{@code .../config/ArticleKafkaChannelValidator.java:149-162}。
     *
     * @param channel        出问题的通道
     * @param channelBrokers 该通道的 brokers
     * @return 报错文案
     */
    private String describeMismatch(Channel channel, List<String> channelBrokers) {
        return "article-rpc 的 Kafka 通道「" + channel.label + "」的 brokers=[" + channelBrokers
                + "] 与全局 spring.kafka.bootstrap-servers=[" + globalBrokers + "] 不一致，"
                + "但该值**不会生效**。\n"
                + "  原因：Go 侧每个通道各自用自己的 Brokers —— 字段声明点 go-queue@v1.2.2/kq/config.go:12，"
                + "消费点 kq/pusher.go:44-47（生产）/ kq/queue.go:121（消费），"
                + "仓内调用点 internal/svc/service_context.go:65,66 / service/article/rpc/article.go:58,60；"
                + "而 Spring Kafka 的 KafkaTemplate / ConsumerFactory 是**全局单例**，"
                + "本模块的四个通道共用 spring.kafka.bootstrap-servers。\n"
                + "  出路：① 把该通道的 brokers 改成与全局一致（当前 yml 四个通道都写成 "
                + "\"${KAFKA_BROKERS:kafka:9092}\"，本就同源）；"
                + "② 或改造本模块的 Kafka 装配，给该通道单独建 ProducerFactory / ConsumerFactory。\n"
                + "  ⚠️ 静默忽略更危险（运维以为已切走 broker，实际还连在老 broker 上）⇒ 这里选择启动失败。";
    }

    /**
     * 归一化：只丢 {@code null} 与空串，<b>不做 trim</b>。
     *
     * <p>⚠️ <b>为什么不 trim</b>：Go 侧 {@code kafka.TCP(addrs...)} 对地址<b>逐字使用</b>，
     * 带空格的地址在 Go 侧是「连不上」而不是「被清洗」⇒ 这里也逐字比较。
     *
     * <p><b>B 出处</b>：{@code .../config/ArticleKafkaChannelValidator.java:174-185}。
     *
     * @param raw 原始列表
     * @return 去掉 null 与空串后的列表
     */
    private static List<String> normalize(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String s : raw) {
            if (s != null && !s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 一个通道（标签 + 配置）。
     *
     * <p><b>B 出处</b>：{@code .../config/ArticleKafkaChannelValidator.java:188}。
     *
     * @param label 通道的可读标签
     * @param conf  通道的配置
     */
    private record Channel(String label, ArticleRpcProperties.KafkaConf conf) {
    }
}
