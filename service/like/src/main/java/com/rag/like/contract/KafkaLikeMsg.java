package com.rag.like.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * <p><b>点赞动作事件</b> —— B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeEventContracts.java:59-104}
 * 的内嵌类 {@code KafkaLikeMsg}（B 侧 1 个文件装 3 个 payload ⇒ C 侧拆 3 个文件，D7 域内 {@code contract/} 包）。
 *
 * <p><b>这是 L1 外部契约</b>：Java like 与尚未迁移的 Go like-mq 在灰度期消费同一个 topic
 * {@code like_promotion_topic}，字段名必须逐字相同。
 *
 * <h3>⚠️ 三处易错点</h3>
 * <ol>
 *   <li>{@link #state} 携带的是 <b>action 值 1~4</b>（like/unlike/dislike/undislike），
 *       而 {@code like_record.state} 存的是 <b>0/1/2</b>（无/赞/踩）。
 *       两套编码由消费侧的 {@code applyLikeMessage} switch 转换。</li>
 *   <li>{@link #msgId} 是雪花 ID 的<b>字符串</b>形式，消费侧要 {@code ParseInt} 成 operationID 用。</li>
 *   <li>{@link #createdAt} 是 <b>Unix 秒</b>（不是毫秒），
 *       与 Redis ZSet score 里那个毫秒级游标是两回事。</li>
 * </ol>
 *
 * <p>{@link JsonPropertyOrder} 显式固定字段顺序：Go 的 {@code encoding/json} 按 struct 字段声明序输出，
 * 这里保持一致，便于灰度期逐行 diff payload。
 *
 * <h3>⚠️ 这里<b>没有</b> {@code @JsonIgnoreProperties}，是刻意的</h3>
 * <p>B 侧原先挂着 {@code @JsonIgnoreProperties(ignoreUnknown = false)} 并被台账记为漂移
 * （DRIFT-LIKE-007），<b>该结论已实测证伪</b> —— 那个值就是 Jackson 的默认值，注解不含任何信息；
 * 真正决定行为的是全局 {@code ObjectMapper} 的 {@code .disable(FAIL_ON_UNKNOWN_PROPERTIES)}。
 * ⇒ 实际行为本就是「忽略未知字段」＝与 Go 一致 ⇒ 不加这个注解。
 *
 * <h3>⚠️ 本类<b>不使用 Lombok</b></h3>
 * <p>B 侧是<b>可变 POJO</b>（public 字段 + 无参构造 + 全参构造）⇒ 它是 <b>Kafka 载荷</b>，
 * 不是 HTTP 出参 ⇒ <b>不受 A9 断言约束</b>（A9 只管 {@code dto/} 包）。
 * ⚠️ 保留可变形态是<b>刻意</b>的：Jackson 需要无参构造 + 字段写入来反序列化，
 * 改 immutable record 会改变「字段可写」这一可观察形状。
 * ⇒ 故本类不引入 Lombok（{@code @Data} 会生成 setter + equals/hashCode，
 * 后者对本域的载荷比对语义未验证 ⇒ 不引入）。
 */
@JsonPropertyOrder({"msg_id", "user_id", "target_type", "target_id", "author_id", "state", "is_first", "created_at"})
public class KafkaLikeMsg {

    /** 雪花 ID 的<b>字符串</b>形式，消费侧要 {@code ParseInt} 成 operationID 用。 */
    @JsonProperty("msg_id")
    public String msgId;

    /** 点赞者 ID。 */
    @JsonProperty("user_id")
    public long userId;

    /** 目标对象类型。 */
    @JsonProperty("target_type")
    public String targetType;

    /** 目标对象 ID。 */
    @JsonProperty("target_id")
    public String targetId;

    /** 被点赞内容作者 ID。 */
    @JsonProperty("author_id")
    public long authorId;

    /** action 值：1 like / 2 unlike / 3 dislike / 4 undislike。 */
    @JsonProperty("state")
    public int state;

    /** 仅当 Lua 返回 2（首次点赞）时为 true，消费侧据此建 first_like outbox。 */
    @JsonProperty("is_first")
    public boolean isFirst;

    /** Unix <b>秒</b>。 */
    @JsonProperty("created_at")
    public long createdAt;

    /** Jackson 反序列化用的无参构造器 —— B 侧逐字保留。 */
    public KafkaLikeMsg() {
    }

    /**
     * 全参构造器 —— B 侧逐字保留（参数顺序即 B 侧字段声明序）。
     *
     * @param msgId     消息 id（雪花 ID 字符串）
     * @param userId    点赞者 id
     * @param targetType 目标类型
     * @param targetId  目标 id
     * @param authorId  作者 id
     * @param state     action 值 1~4
     * @param isFirst   是否首次点赞
     * @param createdAt Unix 秒
     */
    public KafkaLikeMsg(String msgId, long userId, String targetType, String targetId, long authorId,
                        int state, boolean isFirst, long createdAt) {
        this.msgId = msgId;
        this.userId = userId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.authorId = authorId;
        this.state = state;
        this.isFirst = isFirst;
        this.createdAt = createdAt;
    }

    /**
     * Go 消费侧写死到 {@code likeMessage.Topic} 的字面量。
     *
     * <p>它不是配置里的真 topic（真 topic 是 {@code like_promotion_topic}）⇒ 它随 inbox 行落库，
     * 属于可查询的事实，不能随手改。
     */
    public static final String INBOX_TOPIC = "like-topic";

    /**
     * Go 消费侧写死到 {@code likeMessage.Consumer} 的字面量。
     *
     * <p>同上：随 inbox 行落库，属于可查询的事实。
     */
    public static final String INBOX_CONSUMER = "like_update_service";
}
