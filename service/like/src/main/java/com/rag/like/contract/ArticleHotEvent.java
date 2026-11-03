package com.rag.like.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * <p><b>文章热点事件</b> —— B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeEventContracts.java:109-140}
 * 的内嵌类 {@code ArticleHotEvent}（B 侧 1 个文件装 3 个 payload ⇒ C 侧拆 3 个文件，D7 域内 {@code contract/} 包）。
 *
 * <p><b>这是 L1 外部契约</b>：由 {@code messaging/LikeHotEventOutboxRelay} 从
 * {@code like_outbox_event} 捞出后推入 {@code article-hot-events}（热点排名系统）。
 *
 * <h3>⚠️ 两处易错点</h3>
 * <ol>
 *   <li>{@link #userId} 是 <b>{@code string}</b>（<b>不是</b> int64），
 *       Go 侧用 {@code fmt.Sprintf("%d", userID)} 转出来的；</li>
 *   <li>{@link #timestamp} 是 <b>Unix 秒</b>（不是毫秒）。</li>
 * </ol>
 *
 * <p>{@link JsonPropertyOrder} 显式固定字段顺序（与 Go struct 声明序一致）。
 *
 * <h3>⚠️ 本类<b>不使用 Lombok</b></h3>
 * <p>与 {@link KafkaLikeMsg} 同理：Kafka 载荷需要「无参构造 + 字段可写」供 Jackson 反序列化
 * ⇒ 保留 B 侧的可变 POJO 形态（<b>不受 A9 约束</b> —— A9 只管 {@code dto/} 包）。
 */
@JsonPropertyOrder({"article_id", "type", "user_id", "timestamp", "is_first"})
public class ArticleHotEvent {

    /** 文章 ID（= {@code like_record.target_id}）。 */
    @JsonProperty("article_id")
    public String articleId;

    /** Go 侧固定字面量 {@code "like"}。 */
    @JsonProperty("type")
    public String type;

    /** <b>string</b>，不是数字。 */
    @JsonProperty("user_id")
    public String userId;

    /** Unix <b>秒</b>。 */
    @JsonProperty("timestamp")
    public long timestamp;

    /** Go 侧固定 {@code true}（只有首次点赞才会产生该事件）。 */
    @JsonProperty("is_first")
    public boolean isFirst;

    /** Jackson 反序列化用的无参构造器 —— B 侧逐字保留。 */
    public ArticleHotEvent() {
    }

    /**
     * 全参构造器 —— B 侧逐字保留。
     *
     * @param articleId 文章 id
     * @param type      事件类型（固定 {@code "like"}）
     * @param userId    点赞者 id（<b>字符串</b>形式）
     * @param timestamp Unix 秒
     * @param isFirst   是否首次点赞
     */
    public ArticleHotEvent(String articleId, String type, String userId, long timestamp, boolean isFirst) {
        this.articleId = articleId;
        this.type = type;
        this.userId = userId;
        this.timestamp = timestamp;
        this.isFirst = isFirst;
    }

    /**
     * Go {@code buildFirstLikeOutbox} 里的 {@code Type: "like"} —— 本消息 {@code type} 字段的固定值。
     *
     * <p>B 出处：{@code like-rpc/.../mq/LikeEventContracts.java:172}。
     */
    public static final String EVENT_TYPE_LIKE = "like";

    /**
     * Go {@code buildFirstLikeOutbox} 里的 {@code EventType: "article_hot_like"} ——
     * {@code like_outbox_event} 行的 {@code event_type} 列取值。
     *
     * <p>B 出处：{@code like-rpc/.../mq/LikeEventContracts.java:175}。
     */
    public static final String OUTBOX_EVENT_TYPE_ARTICLE_HOT_LIKE = "article_hot_like";
}
