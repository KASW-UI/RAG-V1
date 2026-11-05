package com.rag.hot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>hot 域的<b>业务</b>配置绑定点</b> —— 只承载三段业务参数：
 * {@code heavy-keeper} / {@code interaction} / {@code kafka}。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/config/HotRpcProperties.java}
 * （1-221 行；类声明 {@code :37-39}；三个顶层字段 {@code :41-43}；
 * 手写 accessor {@code :45-67}；内部类 {@code HeavyKeeper} {@code :75-116}、
 * {@code Interaction} {@code :127-168}、{@code Weight} {@code :176-207}、
 * {@code Kafka} {@code :210-220}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.hot.hot.rpc.config} → {@code com.rag.hot.config}（D7 包压平）；</li>
 *         <li><b>Lombok 化</b>：{@code @Getter} / {@code @Setter} 替掉 B 侧全部手写 accessor
 *             （展开块见下方）；</li>
 *         <li><b>去掉 {@code @Component}</b>（见下方「装配方式」小节）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>配置前缀 {@code hot-rpc} 与三段段名（{@code heavy-keeper} / {@code interaction} /
 *             {@code kafka}）<b>逐字未动</b> ⇒ {@code application.yml} 的键名<b>一字不改</b>（L1）；</li>
 *         <li>四个内部类的<b>字段名与默认值</b>逐条照抄（B 侧 {@code :77-84} / {@code :128-135} /
 *             {@code :179-182} / {@code :211}）⇒ 绑定结果逐条一致；</li>
 *         <li>{@code Weight#getCode()} 这个<b>死字段</b>逐字保留（理由见该字段的 javadoc）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>装配方式（⚠️ 与 B 侧不同的一处，语义等价）</b>：
 * B 侧靠类上的 {@code @Component} + {@code @ConfigurationProperties} 注册；
 * 本仓由应用入口的 {@code @ConfigurationPropertiesScan("com.rag.hot.config")} 统一注册
 * ⇒ ⇒ 本类<b>刻意不带 {@code @Component}</b>：若同时带 {@code @Component} 又被扫描扫到
 * ⇒ ⇒ 同一个类型注册<b>两个</b> bean ⇒ 构造器注入时 {@code NoUniqueBeanDefinitionException}
 * （message 域的 {@code MessageApiProperties} 类注释里有同一条纪律的记录）。
 *
 * <p><b>配置边界：本类只承载「业务语义」，基础设施交给 Spring 原生属性</b>：
 * <table border="1">
 *   <caption>B 侧 Go config.go → C 侧落点</caption>
 *   <tr><th>Go</th><th>C 侧</th></tr>
 *   <tr><td>{@code zrpc.RpcServerConf}（ListenOn）</td><td>{@code grpc.server.port}（Spring/devh 原生）</td></tr>
 *   <tr><td>{@code RedisConf{Host,Pass}}</td><td>{@code spring.data.redis.*}（Spring Data Redis 原生）</td></tr>
 *   <tr><td>{@code KqConsumerConf{Brokers}}</td><td>{@code spring.kafka.bootstrap-servers}（原生）</td></tr>
 *   <tr><td>{@code KqConsumerConf.Group}</td><td>{@code spring.kafka.consumer.group-id}（原生）</td></tr>
 *   <tr><td>{@code KqConsumerConf.Topic}</td><td>{@link Kafka#getTopic()}（本类）</td></tr>
 *   <tr><td>{@code HeavyKeeper}</td><td>{@link HeavyKeeper}（本类）</td></tr>
 *   <tr><td>{@code Interaction}</td><td>{@link Interaction}（本类）</td></tr>
 * </table>
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * @ConfigurationProperties(prefix = "hot-rpc")
 * public class HotRpcProperties {
 *
 *     private HeavyKeeper heavyKeeper = new HeavyKeeper();
 *     private Interaction interaction = new Interaction();
 *     private Kafka kafka = new Kafka();
 *
 *     public HeavyKeeper getHeavyKeeper() { return heavyKeeper; }
 *     public void setHeavyKeeper(HeavyKeeper heavyKeeper) { this.heavyKeeper = heavyKeeper; }
 *     public Interaction getInteraction() { return interaction; }
 *     public void setInteraction(Interaction interaction) { this.interaction = interaction; }
 *     public Kafka getKafka() { return kafka; }
 *     public void setKafka(Kafka kafka) { this.kafka = kafka; }
 *
 *     public static class HeavyKeeper {
 *         private int width = 1000;
 *         private int depth = 5;
 *         private double decay = 0.9;
 *         private int topK = 100;
 *
 *         public int getWidth() { return width; }
 *         public void setWidth(int width) { this.width = width; }
 *         public int getDepth() { return depth; }
 *         public void setDepth(int depth) { this.depth = depth; }
 *         public double getDecay() { return decay; }
 *         public void setDecay(double decay) { this.decay = decay; }
 *         public int getTopK() { return topK; }
 *         public void setTopK(int topK) { this.topK = topK; }
 *     }
 *
 *     public static class Interaction {
 *         private long syncEvery = 100L;
 *         private long ttlSeconds = 3600L;
 *         private int partitionCount = 3;
 *         private List<Weight> weights = new ArrayList<>();
 *
 *         public long getSyncEvery() { return syncEvery; }
 *         public void setSyncEvery(long syncEvery) { this.syncEvery = syncEvery; }
 *         public long getTtlSeconds() { return ttlSeconds; }
 *         public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
 *         public int getPartitionCount() { return partitionCount; }
 *         public void setPartitionCount(int partitionCount) { this.partitionCount = partitionCount; }
 *         public List<Weight> getWeights() { return weights; }
 *         public void setWeights(List<Weight> weights) { this.weights = weights; }
 *     }
 *
 *     public static class Weight {
 *         private int code;
 *         private String name;
 *         private int weight;
 *
 *         public int getCode() { return code; }
 *         public void setCode(int code) { this.code = code; }
 *         public String getName() { return name; }
 *         public void setName(String name) { this.name = name; }
 *         public int getWeight() { return weight; }
 *         public void setWeight(int weight) { this.weight = weight; }
 *     }
 *
 *     public static class Kafka {
 *         private String topic = "article-hot-events";
 *
 *         public String getTopic() { return topic; }
 *         public void setTopic(String topic) { this.topic = topic; }
 *     }
 * }
 * }</pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hot-rpc")
public class HotRpcProperties {

    /** sketch 的算法参数（桶宽 / 行数 / 衰减 / Top-K）。 */
    @NestedConfigurationProperty
    private HeavyKeeper heavyKeeper = new HeavyKeeper();

    /** 互动与合并的业务参数（快照节奏 / 大 key TTL / 分片数 / 权重表）。 */
    @NestedConfigurationProperty
    private Interaction interaction = new Interaction();

    /** 消费 topic 名。 */
    @NestedConfigurationProperty
    private Kafka kafka = new Kafka();

    /**
     * <p><b>sketch 的算法参数</b> —— 对应 B 侧 Go {@code heavykeeper.Config}
     * （{@code etc/hot*.yaml} 的 {@code HeavyKeeper} 段）。
     *
     * <p>默认值取自 B 侧 {@code heavykeeper.DefaultConfig()}，与三份 yaml 完全一致
     * （Width=1000 / Depth=5 / Decay=0.9 / TopK=100）。
     */
    @Getter
    @Setter
    public static class HeavyKeeper {

        /** 桶数/行宽。 */
        private int width = 1000;

        /** 行数 = 哈希函数个数。 */
        private int depth = 5;

        /** 冲突时让位的概率基数 {@code decay^count}。 */
        private double decay = 0.9;

        /** 保留的 Top-K 条目数。 */
        private int topK = 100;
    }

    /**
     * <p><b>互动与合并的业务参数</b> —— 对应 B 侧 Go {@code config.InteractionConfig}。
     *
     * <p>⚠️ <b>{@link #getPartitionCount()} 与 Kafka topic 的<b>实际分区数必须相等</b></b>：
     * B 侧 {@code mergePartitions} 只读 {@code p0..p{N-1}}（{@code hot_handler.go:361-363}）。
     * topic 分区数 &gt; {@code partitionCount} 时，多出来的分片快照照写不误但
     * <b>永远不参与合并</b> ⇒ 那些文章不会出现在 {@code hot:articles}。
     * <b>B 侧同样有该问题，此处按「不擅自修复 B 侧行为」原样保留</b>，只在配置注释留痕。
     */
    @Getter
    @Setter
    public static class Interaction {

        /** 全局计数器阈值：每 N 条事件触发一次分区快照。B 侧 {@code Interaction.SyncEvery}（100）。 */
        private long syncEvery = 100L;

        /** 合并后大 key {@code hot:articles} 的 TTL 秒数。B 侧 {@code Interaction.TTL}（3600）。 */
        private long ttlSeconds = 3600L;

        /** 参与合并的分片数。B 侧 {@code Interaction.PartitionCount}（3）。 */
        private int partitionCount = 3;

        /** 事件类型 → 权重。B 侧 {@code Interaction.Weights}。 */
        private List<Weight> weights = new ArrayList<>();
    }

    /**
     * <p><b>单个事件类型的权重项</b> —— 对应 B 侧 Go {@code config.IndexWeight}。
     *
     * <p>⚠️ <b>{@link #getCode()} 在 B 侧<b>零读取</b>（{@code hot.go:47-50} 只按 {@code Name} 建 map），
     * 但字段必须保留 —— B 侧 {@code hot2/hot3.yaml} 与 {@code hot.yaml} 的<b>唯一</b>结构差异
     * 就是它的缺失（实测 diff 确认）⇒ 迁移必须保留该字段，否则配置结构与 B 侧不一致。
     */
    @Getter
    @Setter
    public static class Weight {

        /** B 侧 {@code IndexWeight.Code}。保留但不参与分发。 */
        private int code;

        /** 事件类型名（{@code like} / {@code comment} / {@code coin} / {@code share}）。分发键。 */
        private String name;

        /** 该类型的权重。 */
        private int weight;
    }

    /** <p><b>消费 topic 名</b> —— 对应 B 侧 Go {@code KqConsumerConf.Topic}（三份 yaml 均为 {@code article-hot-events}）。 */
    @Getter
    @Setter
    public static class Kafka {

        /** Kafka topic 名。 */
        private String topic = "article-hot-events";
    }
}
