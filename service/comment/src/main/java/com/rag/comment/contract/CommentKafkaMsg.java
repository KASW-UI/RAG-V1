package com.rag.comment.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * <p><b>评论审计消息 —— 生产侧 wire 契约</b>（L1 跨服务契约层 · 域内私有）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuditMessage.java:36-81}
 * （B 侧注释自记其 Go 出处为 {@code service/comment/rpc/common/types/kqueue.go:3-15} 的
 * {@code kqtypes.CommentKafkaMsg}）。
 *
 * <pre>
 * type CommentKafkaMsg struct {
 *     CommentId  int64  `json:"comment_id"`
 *     TargetType string `json:"target_type"`
 *     TargetId   string `json:"target_id"`
 *     UserId     int64  `json:"user_id"`
 *     RootId     int64  `json:"root_id"`
 *     ParentId   int64  `json:"parent_id"`
 *     Content    string `json:"content"`
 *     Meta       string `json:"meta"`
 *     OwnerId    int64  `json:"owner_id"`
 *     Attribute  int64  `json:"attribute"`
 *     CreateTime int64  `json:"create_time"`
 * }
 * </pre>
 *
 * <h2>⚠️ 本类与 {@code messaging/CommentAuditMessage} 的职责边界（映射表 §7.4 #4 的收口）</h2>
 * <p>B 侧只有<b>一个</b>类（{@code CommentAuditMessage}），<b>生产端与消费端共用</b>。
 * C 侧按 D7 把<b>跨服务契约</b>提到 {@code contract/}（D11 的包压平产物）⇒ 落成两处：
 * <ul>
 *   <li><b>本类（{@code contract/}）= 生产侧 wire 契约</b> —— <b>序列化方向</b>，
 *       即 {@code messaging/CommentAuditPublisher} 写进 Kafka 的那个形状。
 *       它是<b>跨服务</b>的：别的进程（灰度期的 Go comment-mq）按这 11 个字段名解析。</li>
 *   <li><b>{@code messaging/CommentAuditMessage} = 消费侧 DTO</b> —— <b>反序列化方向</b>，
 *       即 {@code messaging/CommentAuditListener} 从 Kafka 读到的那个形状，
 *       供落库 / 缓存失效 / 通知三条链路消费。</li>
 * </ul>
 * ⇒ 两份<b>字段名与顺序逐条相同</b>（否则 wire 就断了），
 * 但<b>各自只承担一个方向</b> ⇒ 不会出现「一份契约两个用途互相污染」。
 * ⚠️ <b>改字段必须同时改两处</b>（字段名是 L1）。
 *
 * <h2>⚠️ 三条把它变成「跨进程契约」而不是 DTO 的细节</h2>
 * <ol>
 *   <li><b>字段顺序是 {@link JsonPropertyOrder} 显式钉死的</b>。
 *       Go 的 {@code encoding/json} 按<b>结构体声明顺序</b>输出，
 *       而 Jackson 默认按字母序 ⇒ 不钉死就会产出
 *       {@code {"attribute":0,"comment_id":…}} 这种与 Go 不同的字节序列。
 *       字节不同不影响 JSON 语义，但会让「按原始字节比对两侧消息」的排查手段失效。</li>
 *   <li><b>没有 {@code omitempty}</b>。Go 侧 11 个字段全部无条件输出，
 *       即使是空字符串的 {@code content}/{@code meta}（默认值）也会出现。
 *       ⇒ Java 侧<b>不要</b>加 {@code JsonInclude.NON_EMPTY}，
 *       否则一条默认消息的字节数就变了。</li>
 *   <li><b>{@code create_time} 是<b>秒</b></b>（B 侧 {@code create_comment_logic.go:114} 的
 *       {@code now.Unix()}），不是毫秒。落库时用 {@code Instant.ofEpochSecond} 还原
 *       （B 侧 {@code InsertCommentTx} 的 {@code time.Unix(msg.CreateTime, 0)}）。</li>
 * </ol>
 *
 * <p><b>它为什么是 11 字段而不是 proto 消息</b>：这条消息走 Kafka 而不是 gRPC，
 * B 侧用的是自己的 {@code kqtypes.CommentKafkaMsg}。wire 契约由本类的 JSON 决定，
 * 与 {@code comment.proto} 无关。
 *
 * <h3>⚠️ 本类<b>不使用 Lombok</b></h3>
 * <p>本类是<b>不可变 record</b>（D5 精神：能不可变就不可变）⇒ record 自带不可变访问器，
 * 无需 {@code @Getter}/{@code @Data} ⇒ A13 判据不适用。
 * ⚠️ <b>record 的反序列化依赖 {@code @JsonProperty} 打在 component 上</b>
 * （Jackson 2.12+ 会把它传播到构造参数 + accessor）⇒
 * <b>不依赖 javac 的 {@code -parameters} 开关</b> ⇒ 编译参数变化时不会静默失效。
 *
 * <h3>🔴 未知字段必须被忽略（逐字照抄 B 侧的显式声明）</h3>
 * <p>B 侧承载这条 wire 的 {@code CommentAuditMessage} 上有
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * （{@code comment-rpc/…/rpc/service/CommentAuditMessage.java:51}）
 * ⇒ 语义等于 Go {@code encoding/json} 的「默认忽略未知字段」。
 * <p>⚠️ 本类是<b>生产侧</b> wire 契约，正常只被序列化；但它同时要能被
 * {@link com.rag.comment.messaging.CommentAuditMessage#from} 之外的反序列化路径读回
 * ⇒ 显式补上这条注解，与 B 侧同形（不靠 Boot 默认值 ⇒ 不留潜伏分歧）。
 */
@JsonPropertyOrder({
        "comment_id", "target_type", "target_id", "user_id", "root_id", "parent_id",
        "content", "meta", "owner_id", "attribute", "create_time"
})
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommentKafkaMsg(

        /** 评论 ID（雪花算法生成）。 */
        @JsonProperty("comment_id") long commentId,

        /** 目标对象类型（文章 / 评论 …）。 */
        @JsonProperty("target_type") String targetType,

        /** 目标对象 ID。 */
        @JsonProperty("target_id") String targetId,

        /** 评论者 ID。 */
        @JsonProperty("user_id") long userId,

        /** 根评论 ID（顶层评论时为 0）。 */
        @JsonProperty("root_id") long rootId,

        /** 父评论 ID（回复时非 0）。 */
        @JsonProperty("parent_id") long parentId,

        /** 评论正文。 */
        @JsonProperty("content") String content,

        /** 附加元信息（JSON 字符串）。 */
        @JsonProperty("meta") String meta,

        /** 目标对象作者 ID。 */
        @JsonProperty("owner_id") long ownerId,

        /** 扩展属性位。 */
        @JsonProperty("attribute") long attribute,

        /** Unix <b>秒</b>（B 侧 {@code now.Unix()}）。 */
        @JsonProperty("create_time") long createTime) {
}
