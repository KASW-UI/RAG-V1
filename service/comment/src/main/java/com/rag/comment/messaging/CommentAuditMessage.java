package com.rag.comment.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * <p><b>评论审计消息 —— 消费侧 DTO</b>（反序列化方向）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuditMessage.java:36-81}
 *
 * <h2>⚠️ 与 {@code contract/CommentKafkaMsg} 的职责边界（映射表 §7.4 #4 的收口）</h2>
 * <p>B 侧只有<b>一个</b>类（{@code CommentAuditMessage}），<b>生产端与消费端共用</b>。
 * C 侧按 D7 把<b>跨服务契约</b>提到 {@code contract/}（D11 的包压平产物）⇒ 落成两处：
 * <ul>
 *   <li><b>{@code contract/CommentKafkaMsg} = 生产侧 wire 契约</b> —— 序列化方向，
 *       即 {@link CommentAuditPublisher} 写进 Kafka 的那个形状（跨服务，别的进程按它解析）。</li>
 *   <li><b>本类 = 消费侧 DTO</b> —— 反序列化方向，即 {@link CommentAuditListener}
 *       从 Kafka 读到的那个形状，供落库 / 缓存失效 / 通知三条链路消费。</li>
 * </ul>
 * ⇒ 两份<b>字段名与顺序逐条相同</b>（否则 wire 就断了），但<b>各自只承担一个方向</b>。
 * ⚠️ <b>改字段必须同时改两处</b>（字段名是 L1）。
 *
 * <h2>⚠️ 为什么消费侧仍保留一份 DTO，而不是直接反序列化成 {@code contract/CommentKafkaMsg}</h2>
 * <ul>
 *   <li><b>方向语义清晰</b>：本类只被消费侧引用 ⇒ 从类型上就能看出「谁在读 Kafka」，
 *       不会出现生产端不小心依赖消费侧类型。</li>
 *   <li><b>可演进性</b>：消费侧未来若需要「落库用字段」与「wire 字段」分离
 *       （例如新增一个只在库里有意义的列），改本类即可，<b>不动 wire 契约</b>。</li>
 * </ul>
 * ⇒ 代价是两份 11 字段定义 ⇒ <b>由 {@code CommentAuditMessageContractTest} 逐字段钉死</b>。
 *
 * <h2>⚠️ 本类<b>不使用 Lombok</b></h2>
 * <p>本类是<b>不可变 record</b>（D5 精神）⇒ record 自带不可变访问器，无需 Lombok ⇒ A13 判据不适用。
 *
 * <h3>🔴 未知字段必须被忽略（逐字照抄 B 侧的显式声明）</h3>
 * <p>B 侧 {@code CommentAuditMessage} 上有 {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * （{@code comment-rpc/…/rpc/service/CommentAuditMessage.java:51}）—— 那是<b>显式声明</b>，
 * 语义等于 Go {@code encoding/json} 的「默认忽略未知字段」。
 * <p>⚠️ C 侧若<b>只靠</b> Boot 的默认值（{@code FAIL_ON_UNKNOWN_PROPERTIES=false}），
 * 当前行为确实一样，但那是<b>隐式依赖框架默认值</b> ⇒ 一旦全局打开
 * {@code spring.jackson.deserialization.fail-on-unknown-properties=true}（加固时很常见），
 * C 侧就会在<b>收到未来版本新增字段</b>的旧消息时解析失败，而 B 侧不会 ⇒ ⇒ 那是**潜伏的分歧**。
 * ⇒ ⇒ 本类<b>显式补上</b>这条注解，与 B 侧同形。
 */
@JsonPropertyOrder({
        "comment_id", "target_type", "target_id", "user_id", "root_id", "parent_id",
        "content", "meta", "owner_id", "attribute", "create_time"
})
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommentAuditMessage(

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

    /**
     * 由生产侧 wire 契约转成本消费侧 DTO。
     *
     * <p>⚠️ <b>字段名与顺序逐条照抄</b> —— 任何一处不一致都会让「消费侧收到的字段」与
     * 「生产侧写出的字段」对不上 ⇒ wire 断裂。转录点集中在这里，便于测试逐字段钉死。
     *
     * @param wire 生产侧 wire 契约
     * @return 消费侧 DTO
     */
    public static CommentAuditMessage from(com.rag.comment.contract.CommentKafkaMsg wire) {
        return new CommentAuditMessage(
                wire.commentId(),
                wire.targetType(),
                wire.targetId(),
                wire.userId(),
                wire.rootId(),
                wire.parentId(),
                wire.content(),
                wire.meta(),
                wire.ownerId(),
                wire.attribute(),
                wire.createTime());
    }

    /**
     * 还原成生产侧 wire 契约 —— 供「回放 / 重投」方向的调用方使用。
     *
     * @return 生产侧 wire 契约
     */
    public com.rag.comment.contract.CommentKafkaMsg toWire() {
        return new com.rag.comment.contract.CommentKafkaMsg(
                commentId,
                targetType,
                targetId,
                userId,
                rootId,
                parentId,
                content,
                meta,
                ownerId,
                attribute,
                createTime);
    }
}
