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
 * <p><b>职责</b>：H03 域事件发件箱 ORM 实体 —— 承载 community 事件的待投递行。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/domain/ArticleDomainOutboxEntity.java}
 * （类声明 {@code :35} · 字段块 {@code :37-49} · getter/setter 块 {@code :51-145}）⇒ <b>逐条照抄</b>。
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
 * <h2>🔴 {@code UNIQUE(aggregate_id, aggregate_version)} 是<b>事件顺序保证</b>，不可省</h2>
 * <p>DDL（{@code V10__create_article_community_revision.sql}）给本表加了
 * {@code UNIQUE (aggregate_id, aggregate_version)}
 * ⇒ ⇒ 同一篇文章的<b>同一个版本号</b>只允许有一条事件行
 * ⇒ ⇒ 重复投递/重复写入会被 PG 拒绝（幂等的<b>数据库级</b>兜底）。
 * <br>⚠️ 应用层已有 event_id 比对，但<b>不能只靠应用层</b>。
 *
 * <h2>{@code payload} 是<b>冻结的 JSON 文本</b>，不是结构化字段</h2>
 * <p>推送时必须<b>逐字节</b>发送落库时的原文（不重新序列化）⇒ 所以列类型是 {@code String}，
 * 写入走 {@code CAST(#{payload} AS jsonb)}。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * public class ArticleDomainOutboxEntity {
 *     private String eventId;
 *     private String aggregateId;
 *     private long aggregateVersion;
 *     private String eventType;
 *     private String payload;
 *     private int status;
 *     private int retryCount;
 *     private OffsetDateTime createdAt;
 *     private OffsetDateTime updatedAt;
 *
 *     public String getEventId() { return eventId; }
 *     public void setEventId(String eventId) { this.eventId = eventId; }
 *     public String getAggregateId() { return aggregateId; }
 *     public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
 *     public long getAggregateVersion() { return aggregateVersion; }
 *     public void setAggregateVersion(long aggregateVersion) { this.aggregateVersion = aggregateVersion; }
 *     public String getEventType() { return eventType; }
 *     public void setEventType(String eventType) { this.eventType = eventType; }
 *     public String getPayload() { return payload; }
 *     public void setPayload(String payload) { this.payload = payload; }
 *     public int getStatus() { return status; }
 *     public void setStatus(int status) { this.status = status; }
 *     public int getRetryCount() { return retryCount; }
 *     public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 *
 *     public ArticleDomainOutboxEntity() { }
 *
 *     public ArticleDomainOutboxEntity(String eventId, String aggregateId, long aggregateVersion,
 *                                      String eventType, String payload, int status, int retryCount,
 *                                      OffsetDateTime createdAt, OffsetDateTime updatedAt) {
 *         this.eventId = eventId;
 *         this.aggregateId = aggregateId;
 *         this.aggregateVersion = aggregateVersion;
 *         this.eventType = eventType;
 *         this.payload = payload;
 *         this.status = status;
 *         this.retryCount = retryCount;
 *         this.createdAt = createdAt;
 *         this.updatedAt = updatedAt;
 *     }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("article_domain_outbox")
public class ArticleDomainOutboxEntity {

    /** 主键（业务侧生成的事件 ID）。 */
    @TableId(type = IdType.INPUT)
    private String eventId;

    /** 聚合根 ID（= article_id）。 */
    private String aggregateId;

    /** 聚合版本号（与 {@code aggregate_id} 组成唯一键 ⇒ 事件顺序保证）。 */
    private long aggregateVersion;

    /** 事件类型（如 {@code community.article.published}）。 */
    private String eventType;

    /** 冻结的 community 事件 JSON 文本（推送时逐字节发原文）。 */
    private String payload;

    /** {@code 0 待发送 / 1 已发送 / 2 发送失败}。 */
    private int status;

    /** 重试次数。 */
    private int retryCount;

    /** 入库时刻。 */
    private OffsetDateTime createdAt;

    /** 最近一次状态变更时刻。 */
    private OffsetDateTime updatedAt;
}
