package com.rag.like.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * like 域 Kafka 主题 —— 对应 Go 端各处 {@code PusherConf} / {@code KqConf} 的 Topic 字段。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code like-rpc/src/main/java/com/rag/like/like/rpc/config/LikeKafkaTopics.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：落点由 {@code com.rag.like.like.rpc.config} 移到
 *       {@code com.rag.like.messaging}（D7 + 映射表第 164 行：
 *       「{@code like-rpc/.../config/LikeKafkaTopics.java} → {@code messaging/LikeKafkaTopics.java}」）。</li>
 *   <li><b>语义还一样吗</b>：一样。四个 topic 的字面量、两个 pusher 开关的粒度、
 *       {@code consumers} 的默认值逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <p>⚠️ <b>「生产与消费同一个 topic」</b>是 Go 的现状，不是笔误：
 * <ul>
 *   <li>生产者：{@code like.yaml} 的 {@code KafkaConf.Topic: like_promotion_topic}
 *       （{@code LikeAction} 推 {@code KafkaLikeMsg}）；</li>
 *   <li>消费者：{@code mq.yaml} 的 {@code Kafka.Topic: like_promotion_topic}
 *       （group=like-update-group，offset=first）。</li>
 * </ul>
 * 两个 topic 值一致，很多写法（尤其 MyBatis/配置生成工具）会误判成配置冲突而"修正"其中一个，
 * 这里显式说明以防被改。
 *
 * <p><b>四个主题分属三条互不相干的下游链路</b>，不要合并：
 * <table border="1">
 *   <caption>主题归属</caption>
 *   <tr><th>主题</th><th>Go 出处</th><th>用途</th></tr>
 *   <tr><td>{@code like_promotion_topic}</td><td>{@code KafkaConf.Topic} + {@code mq.yaml Kafka.Topic}</td>
 *       <td>本域自产自消：写路径推事件、消费路径落库</td></tr>
 *   <tr><td>{@code article-hot-events}</td><td>{@code mq.yaml HotEventPusherConf.Topic}</td>
 *       <td>outbox relay 推给<b>热点排名系统</b></td></tr>
 *   <tr><td>{@code task.logs.filtered.User}</td><td>{@code TaskUserPusherConf.Topic}</td>
 *       <td>推给<b>任务/推广结算系统</b>（可选，未配置则整段跳过）</td></tr>
 *   <tr><td>{@code task.logs.filtered.Article}</td><td>{@code TaskArticlePusherConf.Topic}</td>
 *       <td>同上，另一路</td></tr>
 * </table>
 */
@ConfigurationProperties(prefix = "like.kafka")
public class LikeKafkaTopics {

    /** Go {@code KafkaConf.Topic}，同时是 Go {@code mq.yaml} 的消费 Topic。 */
    private String likeTopic = "like_promotion_topic";

    /** Go {@code TaskUserPusherConf.Topic}。 */
    private String taskUserTopic = "task.logs.filtered.User";

    /** Go {@code TaskArticlePusherConf.Topic}。 */
    private String taskArticleTopic = "task.logs.filtered.Article";

    /** Go {@code mq.yaml HotEventPusherConf.Topic}。 */
    private String hotEventTopic = "article-hot-events";

    /**
     * 消费并发度 —— 对应 Go {@code service/like/rpc/internal/mq/etc/mq.yaml} 的
     * {@code Kafka.Consumers: 8}。
     *
     * <p>Go 侧消费点（{@code go-queue@v1.2.2/kq/queue.go:249}）：
     * {@code for i := 0; i < q.c.Consumers; i++ { … }} ⇒ <b>8 个消费协程</b>；
     * {@code kq/config.go:18} 的默认值同样是 8。
     *
     * <p>⚠️ <b>本属性必须在 {@code kafkaListenerContainerFactory} 上调
     * {@code setConcurrency(...)} 才生效</b>；写成 {@code spring.kafka.listener.concurrency}
     * 是<b>无效</b>的 —— 因为应用自己 {@code new} 了这个 factory，
     * Boot 的 {@code ConcurrentKafkaListenerContainerFactoryConfigurer} <b>不会</b>参与，
     * 那条 yaml 键没有任何消费点（AGENTS.md §3.3 同型：配置里没写 ≠ 没开，写了也可能没接）。
     */
    private int consumers = 8;

    /**
     * <b>用户路</b> TaskPusher 是否启用 —— 对应 Go {@code newOptionalPusher(c.TaskUserPusherConf)}。
     *
     * <p>Go 判空条件（{@code internal/svc/servicecontext.go:50-54}）：
     * {@code if len(conf.Brokers) == 0 || conf.Topic == "" { return nil }}，
     * 即 <b>Brokers 与 Topic 两者任一为空即禁用</b>。
     */
    private boolean taskUserPusherEnabled = true;

    /**
     * <b>文章路</b> TaskPusher 是否启用 —— 对应 Go {@code newOptionalPusher(c.TaskArticlePusherConf)}。
     *
     * <p>Go 侧这是<b>另一个独立判空</b>（{@code servicecontext.go:43-44} 两个字段各判一次），
     * 因此两路可以<b>分别</b>启用/禁用。
     * ⚠️ 此前本模块把两路合并成一个 {@code taskPushersEnabled}，导致「只开一路」无法表达 ——
     * 那是 Go 侧<b>不存在</b>的能力，属实现侧多做了限制（DRIFT-LIKE-012）。
     */
    private boolean taskArticlePusherEnabled = true;

    public int getConsumers() {
        return consumers;
    }

    public void setConsumers(int consumers) {
        this.consumers = consumers;
    }

    public boolean isTaskUserPusherEnabled() {
        return taskUserPusherEnabled;
    }

    public void setTaskUserPusherEnabled(boolean taskUserPusherEnabled) {
        this.taskUserPusherEnabled = taskUserPusherEnabled;
    }

    public boolean isTaskArticlePusherEnabled() {
        return taskArticlePusherEnabled;
    }

    public void setTaskArticlePusherEnabled(boolean taskArticlePusherEnabled) {
        this.taskArticlePusherEnabled = taskArticlePusherEnabled;
    }

    /**
     * ⚠️ <b>已废弃（2026-10-23）</b> —— 改用 {@link #taskUserPusherEnabled} 与
     * {@link #taskArticlePusherEnabled} 两个独立开关。
     *
     * <p>原实现把 Go 侧两个独立判空合并成这一个开关，丢掉了「只启用一路」的能力
     * （Go 出处：{@code internal/svc/servicecontext.go:43-44} 两个字段各判一次）。
     *
     * <p>⚠️ <b>这里不做「旧键回退」兼容</b>：两个细分开关的默认值都是 {@code true}，
     * 而 Spring 的绑定<b>无法区分「未配置」与「显式配置成 true」</b> ——
     * 任何回退逻辑都会把「显式只开一路」误判成「都没配」而退回旧开关。
     * ⇒ 已把 {@code application.yml} 里的键直接换成两个新键，
     * 让配置面与 Go 的两个 {@code KqConf} 一一对应，不留静默失效的中间态。
     *
     * <p>本字段与其访问器仅保留，避免旧调用点编译失败；<b>不再参与任何判定</b>。
     */
    @Deprecated
    private boolean taskPushersEnabled = true;

    public String getLikeTopic() {
        return likeTopic;
    }

    public void setLikeTopic(String likeTopic) {
        this.likeTopic = likeTopic;
    }

    public String getTaskUserTopic() {
        return taskUserTopic;
    }

    public void setTaskUserTopic(String taskUserTopic) {
        this.taskUserTopic = taskUserTopic;
    }

    public String getTaskArticleTopic() {
        return taskArticleTopic;
    }

    public void setTaskArticleTopic(String taskArticleTopic) {
        this.taskArticleTopic = taskArticleTopic;
    }

    public String getHotEventTopic() {
        return hotEventTopic;
    }

    public void setHotEventTopic(String hotEventTopic) {
        this.hotEventTopic = hotEventTopic;
    }

    /**
     * @deprecated 已废弃，见字段注释。<b>不参与判定</b>，改用两个细分开关。
     */
    @Deprecated
    public boolean isTaskPushersEnabled() {
        return taskPushersEnabled;
    }

    /**
     * @deprecated 已废弃，见字段注释。<b>不参与判定</b>，改用两个细分开关。
     */
    @Deprecated
    public void setTaskPushersEnabled(boolean taskPushersEnabled) {
        this.taskPushersEnabled = taskPushersEnabled;
    }
}
