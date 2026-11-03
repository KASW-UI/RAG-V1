package com.rag.like.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 消费消息的语义哈希 —— 1:1 对应 Go 端
 * {@code service/like/rpc/internal/model/like_message_hash.go} 的 {@code likeMessageHash}：
 *
 * <pre>
 * payload, _ := json.Marshal(struct {
 *     UserID     int64  `json:"user_id"`
 *     TargetType string `json:"target_type"`
 *     TargetID   string `json:"target_id"`
 *     AuthorID   int64  `json:"author_id"`
 *     Action     int32  `json:"action"`
 *     OccurredAt int64  `json:"occurred_at"`
 *     IsFirst    bool   `json:"is_first"`
 * }{ … })
 * sum := sha256.Sum256(payload)
 * return hex.EncodeToString(sum[:])
 * </pre>
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeMessageDigest.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：无。包名由 {@code com.rag.like.like.rpc.service} 压平为
 *       {@code com.rag.like.service}（D7）；{@link GoCompatibleJson} 的同包引用去掉 import。</li>
 *   <li><b>语义还一样吗</b>：一样。字段 put 顺序、Go 转义规则、SHA-256 小写 hex 输出逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 这里必须跨语言字节一致，否则灰度期会误判</h2>
 *
 * <p>{@code payload_hash} 会随 inbox 行<b>持久化</b>。同一条 msg_id 再次投递时，
 * {@code ProcessLikeMessageBatch} 比对哈希：不等就报
 * {@code "like message %s reused with conflicting payload"} 并让整条消息重试。
 *
 * <p>⇒ Go like-mq 写入的哈希，会被 Java like-rpc 读取并比对（反之亦然）。
 * <b>两边必须算出完全相同的字节序列</b>，否则正常重投会被当成「msg_id 被复用」而卡死。
 *
 * <p>两个必须显式对齐的点：
 * <ol>
 *   <li><b>字段顺序</b>：Go 的 {@code encoding/json} 按 struct 字段声明序输出，
 *       而 Jackson 的 {@code ObjectNode} 按插入序 —— 这里显式按 Go 顺序 put。</li>
 *   <li><b>HTML 转义</b>：Go 的 {@code encoding/json} <b>默认开启</b> HTML 转义，
 *       会把 {@code <} {@code >} {@code &} 写成 {@code \u003c} {@code \u003e} {@code \u0026}；
 *       Jackson 默认<b>不</b>转义。target_type / target_id 是外部输入，
 *       含这三个字符时两边哈希就不同了。</li>
 * </ol>
 */
@Component
public class LikeMessageDigest {

    private final ObjectMapper objectMapper;

    public LikeMessageDigest(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 计算消息语义哈希。
     *
     * @param userId     {@code record.user_id}
     * @param targetType {@code record.target_type}
     * @param targetId   {@code record.target_id}
     * @param authorId   {@code record.author_id}
     * @param action     {@code record.state}（消息里的 action：1~4）
     * @param occurredAt 消息的 {@code created_at}（Unix 秒）
     * @param isFirst    消息里的 {@code is_first}
     * @return 64 位小写十六进制 SHA-256
     */
    public String hash(long userId, String targetType, String targetId,
                       long authorId, int action, long occurredAt, boolean isFirst) {
        ObjectNode node = objectMapper.createObjectNode();
        // ⚠️ put 顺序 = Go struct 字段声明顺序，不可调整
        node.put("user_id", userId);
        node.put("target_type", targetType);
        node.put("target_id", targetId);
        node.put("author_id", authorId);
        node.put("action", action);
        node.put("occurred_at", occurredAt);
        node.put("is_first", isFirst);

        String json = GoCompatibleJson.write(node);
        return GoCompatibleJson.Sha256.hex(json);
    }
}
