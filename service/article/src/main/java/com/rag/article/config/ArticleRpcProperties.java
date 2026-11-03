package com.rag.article.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <p><b>职责</b>：article 域的 <b>rpc 侧</b>配置聚合点 —— Spring Boot {@code @ConfigurationProperties}
 * 绑定到 {@code article-rpc.*}。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/config/ArticleRpcProperties.java}
 * （类声明 {@code :49-50} · 字段块 {@code :52-77} · getter/setter 块 {@code :79-149} ·
 * {@code Postgres} {@code :152-199} · {@code KafkaConf} {@code :210-287} ·
 * {@code RpcClientConf} {@code :298-319} · {@code ArticleSyncOutbox} {@code :322-350} ·
 * {@code Minio} {@code :360-433}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.config} → {@code com.rag.article.config}（D7 包压平）；</li>
 *         <li><b>Lombok 化</b>：getter/setter 样板由 {@code @Getter @Setter} 生成
 *             （<b>展开块见下</b> ⇒ D18 要求「原本的完整代码」必须同文件可见）。</li>
 *       </ul>
 *       ⇒ <b>字段名、类型、分组结构、默认值一字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>配置前缀 {@code article-rpc} <b>逐字未动</b> ⇒ {@code application.yml} 的
 *             {@code article-rpc.*} 键名不变（L1）；</li>
 *         <li>Lombok {@code @Getter} 对 {@code boolean useSsl} 生成的访问器名是
 *             {@code isUseSsl()}（与 B 侧手写的 {@code isUseSsl()} <b>同名</b>）⇒ 调用点零改动；</li>
 *         <li>B 侧<b>没有</b> {@code equals}/{@code hashCode}/{@code toString} 重写
 *             ⇒ 本类也<b>不引入</b>（{@code @Getter @Setter} 不生成它们）⇒ 保持引用相等的默认语义。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 三条「配置里没有 ≠ 没开」的判据（AGENTS.md §3.3.1）</h2>
 * <ol>
 *   <li><b>{@code rpc-timeout} 不是本类字段</b>。B 侧 yaml 写的是 {@code Timeout: 70000}，
 *       对应 {@code zrpc.RpcServerConf.Timeout}；Java 侧由共享层的
 *       {@code ObservabilityProperties}（承载 {@code observability.rpc-timeout}）承担，
 *       <b>不是</b> {@code grpc.server.timeout}（devh starter 的那个是 channel 级，语义不同）。</li>
 *   <li><b>{@code MinIO.UseSSL=false}</b> 与 {@code PublicBaseURL} 的空值回落都参与可观察行为：
 *       {@code ArticleObjectStorageClient#buildPublicFileURL} 在 {@code PublicBaseURL} 为空时
 *       按 {@code UseSSL} 选 scheme 拼 {@code scheme://Endpoint}（Go {@code upload_file_logic.go:93-108}）。</li>
 *   <li><b>{@code ArticleSyncOutbox.PollIntervalMs <= 0} / {@code BatchSize <= 0}</b>
 *       在 Go 侧有兜底（{@code article.go:52-60}：1000ms / 100）。本类<b>不提供兜底默认值</b>，
 *       兜底逻辑留在 {@code ArticleSyncOutboxRelay}，以免出现「配置已生效」的假象。</li>
 * </ol>
 *
 * <h2>为什么 {@code HotEventPusherConf} 不迁移</h2>
 * Go 侧 {@code svc.NewServiceContext} 建了这个 pusher，但<b>全模块零调用点</b>
 * ⇒ 它属于 Go 侧的<b>死配置</b>，照搬会凭空多出一个 Kafka topic 绑定
 * ⇒ 与映射表 article.md §1.4「HotEventPusher | 零调用点，不迁移」一致。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * @ConfigurationProperties(prefix = "article-rpc")
 * public class ArticleRpcProperties {
 *     private Postgres postgres = new Postgres();
 *     private KafkaConf kqPusherConf = new KafkaConf();
 *     private KafkaConf kqConsumerConf = new KafkaConf();
 *     private KafkaConf articleSyncPusherConf = new KafkaConf();
 *     private KafkaConf articleSyncResultConsumerConf = new KafkaConf();
 *     private RpcClientConf security = new RpcClientConf();
 *     private RpcClientConf messageRpc = new RpcClientConf();
 *     private ArticleSyncOutbox articleSyncOutbox = new ArticleSyncOutbox();
 *     private Minio minio = new Minio();
 *
 *     public Postgres getPostgres() { return postgres; }
 *     public void setPostgres(Postgres postgres) { this.postgres = postgres; }
 *     public KafkaConf getKqPusherConf() { return kqPusherConf; }
 *     public void setKqPusherConf(KafkaConf kqPusherConf) { this.kqPusherConf = kqPusherConf; }
 *     public KafkaConf getKqConsumerConf() { return kqConsumerConf; }
 *     public void setKqConsumerConf(KafkaConf kqConsumerConf) { this.kqConsumerConf = kqConsumerConf; }
 *     public KafkaConf getArticleSyncPusherConf() { return articleSyncPusherConf; }
 *     public void setArticleSyncPusherConf(KafkaConf v) { this.articleSyncPusherConf = v; }
 *     public KafkaConf getArticleSyncResultConsumerConf() { return articleSyncResultConsumerConf; }
 *     public void setArticleSyncResultConsumerConf(KafkaConf v) { this.articleSyncResultConsumerConf = v; }
 *     public RpcClientConf getSecurity() { return security; }
 *     public void setSecurity(RpcClientConf security) { this.security = security; }
 *     public RpcClientConf getMessageRpc() { return messageRpc; }
 *     public void setMessageRpc(RpcClientConf messageRpc) { this.messageRpc = messageRpc; }
 *     public ArticleSyncOutbox getArticleSyncOutbox() { return articleSyncOutbox; }
 *     public void setArticleSyncOutbox(ArticleSyncOutbox v) { this.articleSyncOutbox = v; }
 *     public Minio getMinio() { return minio; }
 *     public void setMinio(Minio minio) { this.minio = minio; }
 * }
 * }</pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "article-rpc")
public class ArticleRpcProperties {

    /** 对应 Go {@code config.Postgres}。 */
    private Postgres postgres = new Postgres();

    /** 对应 Go {@code config.KqPusherConf}（审核事件 push）。 */
    private KafkaConf kqPusherConf = new KafkaConf();

    /** 对应 Go {@code config.KqConsumerConf}（审核消费者）。 */
    private KafkaConf kqConsumerConf = new KafkaConf();

    /** 对应 Go {@code config.ArticleSyncPusherConf}。 */
    private KafkaConf articleSyncPusherConf = new KafkaConf();

    /** 对应 Go {@code config.ArticleSyncResultConsumerConf}。 */
    private KafkaConf articleSyncResultConsumerConf = new KafkaConf();

    /** 对应 Go {@code config.SecurityConf}（zrpc.RpcClientConf）。 */
    private RpcClientConf security = new RpcClientConf();

    /** 对应 Go {@code config.MessageRpc}。 */
    private RpcClientConf messageRpc = new RpcClientConf();

    /** 对应 Go {@code config.ArticleSyncOutbox} 匿名结构体。 */
    private ArticleSyncOutbox articleSyncOutbox = new ArticleSyncOutbox();

    /** 对应 Go {@code config.MinIO}。 */
    private Minio minio = new Minio();

    /**
     * 对应 Go {@code config.Postgres}（{@code internal/config/config.go:8-14}）。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class Postgres {
     *     private String host;
     *     private String port;
     *     private String user;
     *     private String password;
     *     private String dbname;
     *
     *     public String getHost() { return host; }
     *     public void setHost(String host) { this.host = host; }
     *     public String getPort() { return port; }
     *     public void setPort(String port) { this.port = port; }
     *     public String getUser() { return user; }
     *     public void setUser(String user) { this.user = user; }
     *     public String getPassword() { return password; }
     *     public void setPassword(String password) { this.password = password; }
     *     public String getDbname() { return dbname; }
     *     public void setDbname(String dbname) { this.dbname = dbname; }
     * }
     * }</pre>
     */
    @Getter
    @Setter
    public static class Postgres {

        private String host;

        private String port;

        private String user;

        private String password;

        /** Go 侧字段名是 {@code DBName}，绑定时按 relaxed 命名匹配 {@code dbname}。 */
        private String dbname;
    }

    /**
     * 对应 Go 的 {@code kq.KqConf}。
     *
     * <p>Go 的 KqConf 还含 {@code Offset} / {@code MinCapacity} / {@code MaxCapacity} / {@code StatsDuration} 等
     * 消费/生产调优字段。Java 侧这些由 Spring Kafka 的
     * {@code spring.kafka.consumer.*} / {@code spring.kafka.producer.*} 自动配置承担，
     * 而 {@code Offset: first} 是<b>唯一参与可观察行为</b>的那个（决定审核消费者从哪开始消费），
     * 它在 {@code application.yml} 的 {@code spring.kafka.consumer.auto-offset-reset: earliest} 上。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class KafkaConf {
     *     private String name;
     *     private String group;
     *     private String topic;
     *     private String offset;
     *     private String[] brokers = new String[0];
     *
     *     public String getName() { return name; }
     *     public void setName(String name) { this.name = name; }
     *     public String getGroup() { return group; }
     *     public void setGroup(String group) { this.group = group; }
     *     public String getTopic() { return topic; }
     *     public void setTopic(String topic) { this.topic = topic; }
     *     public String getOffset() { return offset; }
     *     public void setOffset(String offset) { this.offset = offset; }
     *     public String[] getBrokers() { return brokers; }
     *     public void setBrokers(String[] brokers) { this.brokers = brokers; }
     * }
     * }</pre>
     */
    @Getter
    @Setter
    public static class KafkaConf {

        private String name;

        private String group;

        private String topic;

        private String offset;

        /**
         * 🔴 <b>本字段在 Java 侧<b>没有消费点</b> —— 保留它只是为了让配置面与 Go 一一对应</b>。
         *
         * <h3>Go 出处（声明点 + 消费点，AGENTS.md §3.3.1）</h3>
         * <pre>
         * 字段声明点  go-queue@v1.2.2/kq/config.go:12      Brokers []string
         * 消费点(生产) go-queue@v1.2.2/kq/pusher.go:44-47   kafka.Writer{Addr: kafka.TCP(addrs...)}
         * 消费点(消费) go-queue@v1.2.2/kq/queue.go:121       kafka.ReaderConfig{Brokers: c.Brokers}
         * 仓内调用点   rpc/internal/svc/service_context.go:65,66 （生产）
         *              rpc/article.go:58,60                      （消费）
         * </pre>
         * ⇒ Go 侧<b>每个通道一个独立客户端</b>：改某个通道的 {@code brokers} 会切 broker。
         *
         * <h3>Java 侧为什么没有消费点</h3>
         * Spring Kafka 的 {@code KafkaTemplate} / {@code ProducerFactory} /
         * {@code ConsumerFactory} 是<b>全局单例</b>，统一吃 {@code spring.kafka.bootstrap-servers}。
         * ⇒ 四个通道在本模块里<b>共用同一个 broker 列表</b>，本字段改了对实际连接<b>没有任何影响</b>。
         *
         * <h3>⚠️ 为什么不直接删掉这个字段</h3>
         * 删掉会让配置面与 Go 失配（Go 有、Java 没有 ⇒ 迁过去的运维脚本会「配了但报未知键」），
         * 且丢掉了「Go 在这里有独立能力」这条信息。
         * ⇒ 保留字段 + 由 {@link ArticleKafkaChannelValidator} 在启动时校验
         * <b>它与全局是否一致</b>：分叉就<b>启动失败</b>，
         * 而不是让运维以为流量已切走、实际还连在老 broker 上。
         */
        private String[] brokers = new String[0];
    }

    /**
     * 对应 Go 的 {@code zrpc.RpcClientConf}（仅取本域真正用到的字段）。
     *
     * <p>Go 侧靠 {@code Etcd.Key}（{@code security.rpc} / {@code message.rpc}）寻址；
     * Java 侧用 devh starter + Nacos 的 {@code discovery://<name>}，等价。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class RpcClientConf {
     *     private String key;
     *     private long timeout;
     *
     *     public String getKey() { return key; }
     *     public void setKey(String key) { this.key = key; }
     *     public long getTimeout() { return timeout; }
     *     public void setTimeout(long timeout) { this.timeout = timeout; }
     * }
     * }</pre>
     */
    @Getter
    @Setter
    public static class RpcClientConf {

        /** 对应 Go {@code Etcd.Key}，Java 侧映射为服务发现名。 */
        private String key;

        /** 毫秒；Go 侧 {@code zrpc.RpcClientConf.Timeout}。 */
        private long timeout;
    }

    /**
     * 对应 Go {@code ArticleSyncOutbox struct { PollIntervalMs, BatchSize, MaxRetry int }}。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class ArticleSyncOutbox {
     *     private int pollIntervalMs = 1000;
     *     private int batchSize = 100;
     *     private int maxRetry = 10;
     *
     *     public int getPollIntervalMs() { return pollIntervalMs; }
     *     public void setPollIntervalMs(int v) { this.pollIntervalMs = v; }
     *     public int getBatchSize() { return batchSize; }
     *     public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
     *     public int getMaxRetry() { return maxRetry; }
     *     public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }
     * }
     * }</pre>
     */
    @Getter
    @Setter
    public static class ArticleSyncOutbox {

        private int pollIntervalMs = 1000;

        private int batchSize = 100;

        private int maxRetry = 10;
    }

    /**
     * 对应 Go {@code config.MinIO}（{@code internal/config/config.go:30-39}）。
     *
     * <p>⚠️ {@code imagePath} / {@code articlePath} <b>带尾斜杠</b>（yaml 里是
     * {@code "articles/images/"} / {@code "articles/content/"}），Go 是直接字符串拼接
     * （{@code fmt.Sprintf("%s%d%s", ImagePath, id, ext)}），不做规范化。
     * 本类保持原样，拼接语义见 {@code ArticleObjectStorageClient}。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class Minio {
     *     private String endpoint;
     *     private String publicBaseUrl;
     *     private String accessKeyId;
     *     private String secretAccessKey;
     *     private boolean useSsl;
     *     private String bucketName;
     *     private String imagePath;
     *     private String articlePath;
     *
     *     public String getEndpoint() { return endpoint; }
     *     public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
     *     public String getPublicBaseUrl() { return publicBaseUrl; }
     *     public void setPublicBaseUrl(String v) { this.publicBaseUrl = v; }
     *     public String getAccessKeyId() { return accessKeyId; }
     *     public void setAccessKeyId(String v) { this.accessKeyId = v; }
     *     public String getSecretAccessKey() { return secretAccessKey; }
     *     public void setSecretAccessKey(String v) { this.secretAccessKey = v; }
     *     public boolean isUseSsl() { return useSsl; }
     *     public void setUseSsl(boolean useSsl) { this.useSsl = useSsl; }
     *     public String getBucketName() { return bucketName; }
     *     public void setBucketName(String v) { this.bucketName = v; }
     *     public String getImagePath() { return imagePath; }
     *     public void setImagePath(String imagePath) { this.imagePath = imagePath; }
     *     public String getArticlePath() { return articlePath; }
     *     public void setArticlePath(String articlePath) { this.articlePath = articlePath; }
     * }
     * }</pre>
     */
    @Getter
    @Setter
    public static class Minio {

        private String endpoint;

        private String publicBaseUrl;

        private String accessKeyId;

        private String secretAccessKey;

        private boolean useSsl;

        private String bucketName;

        private String imagePath;

        private String articlePath;
    }
}
