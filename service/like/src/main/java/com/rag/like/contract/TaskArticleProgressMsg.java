package com.rag.like.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * <p><b>任务进度事件</b> —— B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeEventContracts.java:145-167}
 * 的内嵌类 {@code TaskArticleProgressMsg}（B 侧 1 个文件装 3 个 payload ⇒ C 侧拆 3 个文件，D7 域内 {@code contract/} 包）。
 *
 * <p>推给推广结算系统：topic {@code task.logs.filtered.User}（用户路）与
 * {@code task.logs.filtered.Article}（文章路）—— 由 {@code messaging/LikeTaskProgressPublisher} 发出。
 *
 * <h3>⚠️ 两处易错点</h3>
 * <ol>
 *   <li>{@link #userId} 是 <b>{@code string}</b>，且存的是<b>作者</b> ID（不是点赞者）；</li>
 *   <li>{@link #cur} 是该文章<b>当前点赞数</b>。</li>
 * </ol>
 *
 * <p>{@link JsonPropertyOrder} 显式固定字段顺序（与 Go struct 声明序一致）。
 *
 * <h3>⚠️ 本类<b>不使用 Lombok</b></h3>
 * <p>与 {@link KafkaLikeMsg} 同理：Kafka 载荷需要「无参构造 + 字段可写」供 Jackson 反序列化
 * ⇒ 保留 B 侧的可变 POJO 形态（<b>不受 A9 约束</b> —— A9 只管 {@code dto/} 包）。
 */
@JsonPropertyOrder({"user_id", "cur"})
public class TaskArticleProgressMsg {

    /** <b>string</b>，存的是<b>作者</b> ID。 */
    @JsonProperty("user_id")
    public String userId;

    /** 该文章当前点赞数。 */
    @JsonProperty("cur")
    public long cur;

    /** Jackson 反序列化用的无参构造器 —— B 侧逐字保留。 */
    public TaskArticleProgressMsg() {
    }

    /**
     * 全参构造器 —— B 侧逐字保留。
     *
     * @param userId 作者 id（<b>字符串</b>形式）
     * @param cur    该文章当前点赞数
     */
    public TaskArticleProgressMsg(String userId, long cur) {
        this.userId = userId;
        this.cur = cur;
    }
}
