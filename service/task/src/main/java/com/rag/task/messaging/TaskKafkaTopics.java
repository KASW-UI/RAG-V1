package com.rag.task.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <p><b>task 域 Kafka topic 与 consumer group</b> —— 1:1 对应 Go 端
 * {@code service/task/rpc/etc/task.yaml} 的 {@code Kafka.*} 段。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> —
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.config} → {@code com.rag.task.messaging}
 *             （D7 包压平：Kafka 相关的落点归 {@code messaging/}）；</li>
 *         <li>配置前缀与<b>键名逐字未动</b>（{@code task.kafka.*}）；</li>
 *         <li>补 B 出处行号与逐条语义留痕。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 5 个 topic + 7 个 consumer group 的
 *       键名与默认值逐字照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/config/TaskKafkaTopics.java:38-39（类声明）
 * B 出处：…/config/TaskKafkaTopics.java:42-54（5 个 topic）
 * B 出处：…/config/TaskKafkaTopics.java:57-75（7 个 consumer group）
 *       </pre></li>
 * </ol>
 *
 * <h2>⚠️ 为什么 topic 名属于 L1 外部契约</h2>
 * <p>{@code task.logs.filtered.User} / {@code task.logs.filtered.Article}
 * <b>不是本模块私有的</b>：Go 与 Java 的 like-rpc 都在生产它们
 * （Go {@code likeactionlogic.go:317,340}；Java
 * {@code com.rag.like.like.rpc.service.LikeTaskProgressPublisher}）。
 * 改任何一个字符 ⇒ 点赞域推过来的进度任务域一条都收不到，
 * 而两侧<b>都不会报错</b>（只是消费者静默地没有流量）。
 *
 * <h2>⚠️ 六个 consumer group 的来源各不相同，别凭直觉重排</h2>
 * <table border="1">
 *   <caption>group ↔ Go 字段 ↔ 消费者</caption>
 *   <tr><th>本类字段</th><th>Go 字段</th><th>消费者</th></tr>
 *   <tr><td>{@link #getGroupKafkaRaw}</td><td>{@code GroupKafkaRaw}</td>
 *       <td>diagnostic no-op（initKafkaRaw.go）</td></tr>
 *   <tr><td>{@link #getGroupGoka}</td><td>{@code GroupGoKa}</td><td>goka 路由组</td></tr>
 *   <tr><td>{@link #getGroupGokaUser}</td><td>{@code GroupGoKaUser}</td><td>goka 用户计数</td></tr>
 *   <tr><td>{@link #getGroupGokaArticle}</td><td>{@code GroupGoKaArticle}</td><td>goka 文章计数</td></tr>
 *   <tr><td>{@link #getGroupFilterUser}</td><td>{@code GroupFilterUser}</td><td>用户点赞 sink</td></tr>
 *   <tr><td>{@link #getGroupKafkaFilter}</td><td>{@code GroupKafkaFilter}</td><td>文章 sink</td></tr>
 * </table>
 *
 * <h2>⚠️ 文章 sink 用的是 {@code GroupKafkaFilter}，<b>不是</b> {@code GroupFilterArticle}</h2>
 * <p>Go {@code initArticleFilter.go:30} 读的是 {@code group := svcCtx.Config.Kafka.GroupKafkaFilter}，
 * 于是线上文章 sink 的 group 是 {@code task.rpc.consumer101}；
 * 而 yaml 里那个 {@code GroupFilterArticle: task.rpc.consumer91}
 * <b>全仓没有任何读取点</b>。
 * 按「哪个字段名看起来更贴切」去改 group 是最容易引入的漂移 ——
 * 换 group 会让文章任务进度从头消费（重跑历史）或永远收不到新消息。
 */
@ConfigurationProperties(prefix = "task.kafka")
public class TaskKafkaTopics {

    /** Go {@code Kafka.InTopic}：goka 路由组的输入 + kq 诊断消费者的输入。 */
    private String inTopic = "task.logs.raw";

    /** Go {@code Kafka.RawUserTopic}。 */
    private String rawUserTopic = "task.logs.raw.User";

    /** Go {@code Kafka.RawArticleTopic}。 */
    private String rawArticleTopic = "task.logs.raw.Article";

    /** Go {@code Kafka.OutUserTopic}：用户路最终 topic（goka 输出 + sink 输入 + like-rpc 生产）。 */
    private String outUserTopic = "task.logs.filtered.User";

    /** Go {@code Kafka.OutArticleTopic}：文章路最终 topic（同上）。 */
    private String outArticleTopic = "task.logs.filtered.Article";

    /** Go {@code Kafka.GroupKafkaRaw}。 */
    private String groupKafkaRaw = "task.rpc.consumer31";

    /** Go {@code Kafka.GroupGoKa}。 */
    private String groupGoka = "task.rpc.consumer41";

    /** Go {@code Kafka.GroupGoKaUser}。 */
    private String groupGokaUser = "task.rpc.consumer61";

    /** Go {@code Kafka.GroupGoKaArticle}。 */
    private String groupGokaArticle = "task.rpc.consumer71";

    /** Go {@code Kafka.GroupFilterUser}。 */
    private String groupFilterUser = "task.rpc.consumer81";

    /** Go {@code Kafka.GroupKafkaFilter} —— <b>文章 sink 用的就是它</b>。 */
    private String groupKafkaFilter = "task.rpc.consumer101";

    /** Go {@code Kafka.GroupFilterArticle} —— <b>Go 侧无任何读取点</b>，仅为配置面完整而保留。 */
    private String groupFilterArticle = "task.rpc.consumer91";

    public String getInTopic() {
        return inTopic;
    }

    public void setInTopic(String inTopic) {
        this.inTopic = inTopic;
    }

    public String getRawUserTopic() {
        return rawUserTopic;
    }

    public void setRawUserTopic(String rawUserTopic) {
        this.rawUserTopic = rawUserTopic;
    }

    public String getRawArticleTopic() {
        return rawArticleTopic;
    }

    public void setRawArticleTopic(String rawArticleTopic) {
        this.rawArticleTopic = rawArticleTopic;
    }

    public String getOutUserTopic() {
        return outUserTopic;
    }

    public void setOutUserTopic(String outUserTopic) {
        this.outUserTopic = outUserTopic;
    }

    public String getOutArticleTopic() {
        return outArticleTopic;
    }

    public void setOutArticleTopic(String outArticleTopic) {
        this.outArticleTopic = outArticleTopic;
    }

    public String getGroupKafkaRaw() {
        return groupKafkaRaw;
    }

    public void setGroupKafkaRaw(String groupKafkaRaw) {
        this.groupKafkaRaw = groupKafkaRaw;
    }

    public String getGroupGoka() {
        return groupGoka;
    }

    public void setGroupGoka(String groupGoka) {
        this.groupGoka = groupGoka;
    }

    public String getGroupGokaUser() {
        return groupGokaUser;
    }

    public void setGroupGokaUser(String groupGokaUser) {
        this.groupGokaUser = groupGokaUser;
    }

    public String getGroupGokaArticle() {
        return groupGokaArticle;
    }

    public void setGroupGokaArticle(String groupGokaArticle) {
        this.groupGokaArticle = groupGokaArticle;
    }

    public String getGroupFilterUser() {
        return groupFilterUser;
    }

    public void setGroupFilterUser(String groupFilterUser) {
        this.groupFilterUser = groupFilterUser;
    }

    public String getGroupKafkaFilter() {
        return groupKafkaFilter;
    }

    public void setGroupKafkaFilter(String groupKafkaFilter) {
        this.groupKafkaFilter = groupKafkaFilter;
    }

    public String getGroupFilterArticle() {
        return groupFilterArticle;
    }

    public void setGroupFilterArticle(String groupFilterArticle) {
        this.groupFilterArticle = groupFilterArticle;
    }
}
