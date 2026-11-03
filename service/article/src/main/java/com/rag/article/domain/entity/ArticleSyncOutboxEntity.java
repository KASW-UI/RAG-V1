package com.rag.article.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * <p><b>职责</b>：文章同步事件发件箱 ORM 实体 —— 审核通过后的「文章同步事件」待投递行。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/domain/ArticleSyncOutboxEntity.java}
 * （类声明 {@code :22} · 字段块 {@code :24-36} · getter/setter 块 {@code :38-139}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.domain} → {@code com.rag.article.domain.entity}（D7 包压平）；</li>
 *         <li><b>Lombok 化</b>：getter/setter 样板由 {@code @Getter @Setter @NoArgsConstructor @AllArgsConstructor}
 *             生成（<b>展开块见下</b> ⇒ D18）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 字段集合 / 名称 / 类型 / 可见性逐条一致；
 *       B 侧<b>没有</b> {@code equals}/{@code hashCode}/{@code toString} 重写 ⇒ 本类也<b>不引入</b>。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🔴 {@code event_key} 唯一键 = 幂等保证</h2>
 * <p>DDL（{@code V9__create_article_sync_outbox.sql}）建了
 * {@code uk_article_sync_outbox_event_key} 唯一索引
 * ⇒ ⇒ {@code article:op:eventId} 三元组唯一，<b>阻止同一逻辑事件重复入队</b>。
 *
 * <h2>语义：审核消费者在事务内写 pending 行，relay 定时捞取推送 Kafka</h2>
 * <ul>
 *   <li>{@code status}：{@code 0 待发送 / 1 已发送 / 2 发送失败}；</li>
 *   <li>失败 ⇒ {@code status = 2} ＋ {@code retry_count + 1} ＋ 记 {@code last_error}；</li>
 *   <li>{@code payload} 是<b>冻结的 ArticleSyncEvent JSON</b>（推送时<b>不重新序列化</b>）。</li>
 * </ul>
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * public class ArticleSyncOutboxEntity {
 *     private String eventId;
 *     private String eventKey;
 *     private String eventType;
 *     private String aggregateId;
 *     private String payload;
 *     private int status;
 *     private int retryCount;
 *     private String lastError;
 *     private OffsetDateTime createdAt;
 *     private OffsetDateTime updatedAt;
 *
 *     public String getEventId() { return eventId; }
 *     public void setEventId(String eventId) { this.eventId = eventId; }
 *     public String getEventKey() { return eventKey; }
 *     public void setEventKey(String eventKey) { this.eventKey = eventKey; }
 *     public String getEventType() { return eventType; }
 *     public void setEventType(String eventType) { this.eventType = eventType; }
 *     public String getAggregateId() { return aggregateId; }
 *     public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
 *     public String getPayload() { return payload; }
 *     public void setPayload(String payload) { this.payload = payload; }
 *     public int getStatus() { return status; }
 *     public void setStatus(int status) { this.status = status; }
 *     public int getRetryCount() { return retryCount; }
 *     public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
 *     public String getLastError() { return lastError; }
 *     public void setLastError(String lastError) { this.lastError = lastError; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 *
 *     public ArticleSyncOutboxEntity() { }
 *
 *     public ArticleSyncOutboxEntity(String eventId, String eventKey, String eventType, String aggregateId,
 *                                    String payload, int status, int retryCount, String lastError,
 *                                    OffsetDateTime createdAt, OffsetDateTime updatedAt) {
 *         this.eventId = eventId;
 *         this.eventKey = eventKey;
 *         this.eventType = eventType;
 *         this.aggregateId = aggregateId;
 *         this.payload = payload;
 *         this.status = status;
 *         this.retryCount = retryCount;
 *         this.lastError = lastError;
 *         this.createdAt = createdAt;
 *         this.updatedAt = updatedAt;
 *     }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("article_sync_outbox")
public class ArticleSyncOutboxEntity {

    /** 主键（业务侧生成的事件 ID）。 */
    @TableId(type = IdType.INPUT)
    private String eventId;

    /** 唯一键（{@code article:op:eventId}）⇒ 阻止同一逻辑事件重复入队。 */
    private String eventKey;

    /** 事件类型。 */
    private String eventType;

    /** 聚合根 ID（= article_id）。 */
    private String aggregateId;

    /** 冻结的 ArticleSyncEvent JSON 文本（推送时不重新序列化）。 */
    private String payload;

    /** {@code 0 待发送 / 1 已发送 / 2 发送失败}。 */
    private int status;

    /** 重试次数（relay 捞取条件含 {@code retry_count < maxRetry}）。 */
    private int retryCount;

    /** 最近一次失败原因。 */
    private String lastError;

    /** 入库时刻。 */
    private OffsetDateTime createdAt;

    /** 最近一次状态变更时刻。 */
    private OffsetDateTime updatedAt;
}
