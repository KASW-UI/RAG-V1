package com.rag.article.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rag.common.persistence.JsonbValueTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <p><b>职责</b>：不可变的已批准文章版本 ORM 实体 —— 对应 B 侧
 * {@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/domain/ArticleRevisionEntity.java}。
 *
 * <p><b>B 出处</b>：同上文件（类声明 {@code :35} · 字段块 {@code :37-97} · getter/setter 块 {@code :99-211}）
 * ⇒ <b>逐条照抄</b>。
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
 *       B 侧<b>没有</b> {@code equals}/{@code hashCode}/{@code toString} 重写 ⇒ 本类也<b>不引入</b>
 *       （保持引用相等的默认语义，不悄悄改成值语义）。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🔴 本表的不可变性由 PG 触发器强制，不由本类保证</h2>
 * {@code article_revision_immutable} 触发器（BEFORE UPDATE OR DELETE）会拒绝任何修改。
 * 因此：
 * <ul>
 *   <li><b>本实体没有 update 路径</b>，也不提供 {@code BaseMapper} 的 update 方法给业务用；
 *       {@code ArticleRevisionMapper} 只暴露 insert 与 select。</li>
 *   <li><b>复合主键是 {@code (article_id, revision)}</b>，而业务唯一标识是
 *       {@code revision_id}（格式 {@code "<articleID>:r<N>"}）。两者不能混用：
 *       游标分页用复合主键，按业务查找用 revision_id。</li>
 *   <li>{@code sync_event_id} 有 UNIQUE 约束 —— 保证「一次同步结果事件最多产出一个 revision」，
 *       这是发布幂等的<b>数据库级</b>兜底（应用层已有 event_id 比对，但不能只靠应用层）。</li>
 * </ul>
 *
 * <h2>L2 语义：读者看到的字节来自这里，而不是 article.content</h2>
 * B 侧 {@code get_article_logic.go:137-139} 里公开详情直接返回 {@code revision.Markdown}，
 * <b>完全不读 MinIO</b>（B 侧测试 {@code article_public_test.go:173-178} 专门断言了这一点：
 * 把 MinioClient 置 nil 仍能返回正文）。而 {@code markdown} 与 {@code content_sha256}
 * 是<b>同一次审核冻结下来的</b>（{@code article_sync_result_consumer.go:114-123}）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * public class ArticleRevisionEntity {
 *     private String articleId;
 *     private Long revision;
 *     private String revisionId;
 *     private String authorId;
 *     private String sourceObject;
 *     private String contentSha256;
 *     private String title;
 *     private String brief;
 *     private String coverImageUrl;
 *     private String manualTypeTag;
 *     private List<String> secondaryTags;
 *     private String markdown;
 *     private OffsetDateTime publishedAt;
 *     private String syncEventId;
 *
 *     public String getArticleId() { return articleId; }
 *     public void setArticleId(String articleId) { this.articleId = articleId; }
 *     public Long getRevision() { return revision; }
 *     public void setRevision(Long revision) { this.revision = revision; }
 *     public String getRevisionId() { return revisionId; }
 *     public void setRevisionId(String revisionId) { this.revisionId = revisionId; }
 *     public String getAuthorId() { return authorId; }
 *     public void setAuthorId(String authorId) { this.authorId = authorId; }
 *     public String getSourceObject() { return sourceObject; }
 *     public void setSourceObject(String sourceObject) { this.sourceObject = sourceObject; }
 *     public String getContentSha256() { return contentSha256; }
 *     public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
 *     public String getTitle() { return title; }
 *     public void setTitle(String title) { this.title = title; }
 *     public String getBrief() { return brief; }
 *     public void setBrief(String brief) { this.brief = brief; }
 *     public String getCoverImageUrl() { return coverImageUrl; }
 *     public void setCoverImageUrl(String coverImageUrl) { this.coverImageUrl = coverImageUrl; }
 *     public String getManualTypeTag() { return manualTypeTag; }
 *     public void setManualTypeTag(String manualTypeTag) { this.manualTypeTag = manualTypeTag; }
 *     public List<String> getSecondaryTags() { return secondaryTags; }
 *     public void setSecondaryTags(List<String> secondaryTags) { this.secondaryTags = secondaryTags; }
 *     public String getMarkdown() { return markdown; }
 *     public void setMarkdown(String markdown) { this.markdown = markdown; }
 *     public OffsetDateTime getPublishedAt() { return publishedAt; }
 *     public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }
 *     public String getSyncEventId() { return syncEventId; }
 *     public void setSyncEventId(String syncEventId) { this.syncEventId = syncEventId; }
 *
 *     public ArticleRevisionEntity() { }
 *
 *     public ArticleRevisionEntity(String articleId, Long revision, String revisionId, String authorId,
 *                                  String sourceObject, String contentSha256, String title, String brief,
 *                                  String coverImageUrl, String manualTypeTag, List<String> secondaryTags,
 *                                  String markdown, OffsetDateTime publishedAt, String syncEventId) {
 *         this.articleId = articleId;
 *         this.revision = revision;
 *         this.revisionId = revisionId;
 *         this.authorId = authorId;
 *         this.sourceObject = sourceObject;
 *         this.contentSha256 = contentSha256;
 *         this.title = title;
 *         this.brief = brief;
 *         this.coverImageUrl = coverImageUrl;
 *         this.manualTypeTag = manualTypeTag;
 *         this.secondaryTags = secondaryTags;
 *         this.markdown = markdown;
 *         this.publishedAt = publishedAt;
 *         this.syncEventId = syncEventId;
 *     }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "article_revision", autoResultMap = true)
public class ArticleRevisionEntity {

    /** 复合主键之一（B 侧 {@code ArticleID string `gorm:"primaryKey;type:varchar(32)"`}）。 */
    private String articleId;

    /**
     * 复合主键之二（B 侧 {@code Revision int64 `gorm:"primaryKey"`}）。
     *
     * <p>DDL 有 {@code CHECK (revision > 0)}；新版本号 = {@code COALESCE(MAX(revision),0) + 1}
     * （{@code article_sync_result_consumer.go:108-111}）。
     */
    private Long revision;

    /**
     * 对应 B 侧 {@code RevisionID string `gorm:"type:varchar(96);not null;uniqueIndex"`}。
     *
     * <p>格式：{@code fmt.Sprintf("%s:r%d", article.ID, highest+1)}（B 侧:117）。
     */
    private String revisionId;

    /** 对应 B 侧 {@code AuthorID string `gorm:"type:varchar(32);not null"`}。 */
    private String authorId;

    /**
     * 对应 B 侧 {@code SourceObject string `gorm:"type:text;not null"`}。
     *
     * <p>存的是<b>审核那一刻</b>的 MinIO 对象 key（{@code article.Content} 的快照），
     * 指向冻结字节的物理位置。对象本身不被删除（B 侧删除路径刻意保留对象，
     * 见 {@code delete_article_logic.go:64-66} 的注释）。
     */
    private String sourceObject;

    /** 对应 B 侧 {@code ContentSHA256 string `gorm:"type:char(64);not null"`}：{@code sha256(markdown)} 的 hex。 */
    private String contentSha256;

    /** 对应 B 侧 {@code Title string `gorm:"type:varchar(255);not null"`}。审核通过时的标题快照。 */
    private String title;

    /** 对应 B 侧 {@code Brief string `gorm:"type:varchar(512)"}。 */
    private String brief;

    /** 对应 B 侧 {@code CoverImageURL string `gorm:"type:varchar(255)"}。 */
    private String coverImageUrl;

    /** 对应 B 侧 {@code ManualTypeTag string `gorm:"type:varchar(64)"}。 */
    private String manualTypeTag;

    /** 对应 B 侧 {@code SecondaryTags StringArray `gorm:"type:jsonb"`}。 */
    @TableField(typeHandler = JsonbValueTypeHandler.StringListTypeHandler.class)
    private List<String> secondaryTags;

    /** 对应 B 侧 {@code Markdown string `gorm:"type:text;not null"`}：<b>读者实际读到的字节</b>。 */
    private String markdown;

    /**
     * 对应 B 侧 {@code PublishedAt time.Time `gorm:"not null"`}。
     *
     * <p>B 侧写的是 {@code time.Now().UTC()}（{@code article_sync_result_consumer.go:113}），
     * 且公开详情响应的 {@code UpdateTime} 取的正是它的 {@code UnixMilli()}
     * （{@code get_article_logic.go:185}）—— 即<b>发布时间</b>而非文章源行的更新时间。
     */
    private OffsetDateTime publishedAt;

    /** 对应 B 侧 {@code SyncEventID string `gorm:"type:varchar(64);not null;uniqueIndex"`}。 */
    private String syncEventId;
}
