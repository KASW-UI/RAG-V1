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
 * <p><b>职责</b>：source 域指针表 ORM 实体 —— 记录每篇文章当前指向哪个已发布 revision。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/domain/ArticlePublicationEntity.java}
 * （类声明 {@code :34} · 字段块 {@code :36-45} · getter/setter 块 {@code :47-101}）⇒ <b>逐条照抄</b>。
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
 * <h2>L2 语义：指针推进是「发布」这件事的唯一权威落点</h2>
 * <ul>
 *   <li>{@code current_revision} 指向 {@code article_revision.revision_id}；</li>
 *   <li>{@code state} ∈ {@code published} / {@code retracted}（DDL 有 CHECK 约束）；</li>
 *   <li>{@code pointer_version} 每次推进 <b>+1</b>，是 H03 事件顺序保证的一部分；</li>
 *   <li>撤回（retract）只改指针<b>不改</b> revision（revision 由 PG 触发器保证不可变）。</li>
 * </ul>
 * ⚠️ 公开读路径<b>不</b>用本实体，而是用 {@code ArticlePublicMapper} 内部的窄投影
 * （{@code ArticlePublicationRow}）—— 读路径依赖哪几列在类型上可见。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * public class ArticlePublicationEntity {
 *     private String articleId;
 *     private String currentRevision;
 *     private long pointerVersion;
 *     private String state;
 *     private String lastEventId;
 *     private OffsetDateTime updatedAt;
 *
 *     public String getArticleId() { return articleId; }
 *     public void setArticleId(String articleId) { this.articleId = articleId; }
 *     public String getCurrentRevision() { return currentRevision; }
 *     public void setCurrentRevision(String currentRevision) { this.currentRevision = currentRevision; }
 *     public long getPointerVersion() { return pointerVersion; }
 *     public void setPointerVersion(long pointerVersion) { this.pointerVersion = pointerVersion; }
 *     public String getState() { return state; }
 *     public void setState(String state) { this.state = state; }
 *     public String getLastEventId() { return lastEventId; }
 *     public void setLastEventId(String lastEventId) { this.lastEventId = lastEventId; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 *
 *     public ArticlePublicationEntity() { }
 *
 *     public ArticlePublicationEntity(String articleId, String currentRevision, long pointerVersion,
 *                                     String state, String lastEventId, OffsetDateTime updatedAt) {
 *         this.articleId = articleId;
 *         this.currentRevision = currentRevision;
 *         this.pointerVersion = pointerVersion;
 *         this.state = state;
 *         this.lastEventId = lastEventId;
 *         this.updatedAt = updatedAt;
 *     }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("article_publication")
public class ArticlePublicationEntity {

    /** 主键（B 侧 {@code ArticleID string `gorm:"primaryKey;type:varchar(32)"`}）。 */
    @TableId(type = IdType.INPUT)
    private String articleId;

    /** 当前指向的 revision_id；撤回后仍指向旧 revision（但 {@code state} 已是 retracted）。 */
    private String currentRevision;

    /** 指针版本号，每次推进 +1（H03 事件顺序保证的一部分）。 */
    private long pointerVersion;

    /** {@code published} / {@code retracted}（DDL 有 CHECK 约束）。 */
    private String state;

    /** 最近一次推进指针的事件 ID。 */
    private String lastEventId;

    /** 指针最近一次推进的时刻。 */
    private OffsetDateTime updatedAt;
}
