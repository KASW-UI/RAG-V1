package com.rag.article.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rag.article.mapper.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * <p><b>职责</b>：文章主表 ORM 实体 —— 对应 B 侧
 * {@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/domain/ArticleEntity.java}。
 *
 * <p><b>B 出处</b>：同上文件（类声明 {@code :47} · 字段块 {@code :49-141} · getter/setter 块 {@code :143-278}）
 * ⇒ <b>逐条照抄</b>（字段名 / 列名 / 类型 / 注解 / 语义注释逐条对应）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.domain} → {@code com.rag.article.domain.entity}（D7 包压平）；</li>
 *         <li><b>Lombok 化</b>：getter/setter 样板由 {@code @Getter @Setter @NoArgsConstructor @AllArgsConstructor}
 *             生成（<b>展开块见下</b> ⇒ D18 要求「原本的完整代码」必须同文件可见）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>字段集合、名称、类型、可见性<b>逐条一致</b>；</li>
 *         <li>B 侧<b>没有</b> {@code equals}/{@code hashCode}/{@code toString} 重写
 *             ⇒ 本类也<b>不引入</b>（{@code @Getter @Setter} 不生成它们）
 *             ⇒ 保持「引用相等」的默认语义，<b>不</b>悄悄改成值语义（那会让
 *             {@code Set<ArticleEntity>} 的去重行为与 B 侧不同）；</li>
 *         <li>B 侧只有<b>隐式无参构造器</b> ⇒ 本类用 {@code @NoArgsConstructor} 保留它，
 *             并补 {@code @AllArgsConstructor}（MyBatis-Plus 的反射构造路径需要它，
 *             属于<b>新增能力</b>而非<b>语义改动</b>）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>🔴 {@code autoResultMap = true} 不是可选项</b>：MyBatis-Plus <b>只有</b>在
 * {@code @TableName(autoResultMap = true)} 时才会把 {@code @TableField(typeHandler = ...)}
 * 应用到<b>自动生成的 SELECT 结果映射</b>上。少了它，读 jsonb 列拿到的是 {@code PGobject}，
 * 映射成 {@code List}/{@code Map} 直接失败。
 * 这与 AGENTS.md §3.3「断言 Bean 存在 ≠ 断言机制生效」同源——
 * <b>typeHandler 写在字段上 ≠ 它在 SELECT 上生效</b>。
 *
 * <h2>L3 语义：三列各自承载不同的业务含义，不可互相替代</h2>
 * <ul>
 *   <li>{@link #content} 存的是 <b>MinIO 对象 key</b>，不是 Markdown 正文。
 *       作者详情路径用它去 MinIO 取字节；公开路径<b>不用它</b>（用冻结的 revision.markdown）。
 *       这正是 B 侧 {@code get_article_logic.go:137-155} 里 revision/legacy 分叉的原因。</li>
 *   <li>{@link #status} 对应 proto 的 {@code ArticleStatus} 枚举数值
 *       （0 UNSPECIFIED / 1 DRAFT / 2 PUBLISHED / 3 REVIEWING / 4 REJECTED / 5 DELETED）。
 *       ⚠️ <b>Go 的 DDL 注释只列到 4</b>，proto 里的 DELETED(5) 没有落库路径：
 *       删除走的是软删除（{@link #deletedAt}），不是把 status 置 5。照搬 DDL 注释，别被它误导。</li>
 *   <li>{@link #extInfo} 是全部同步状态机的载体（publish_stage / reco_sync_state /
 *       review_nonce / published_once …），键名见 {@code contract.ArticleSyncEvent}。
 *       它是 <b>string→string</b> 的扁平 map，版本号等数值也<b>存成字符串</b>
 *       （B 侧 {@code strconv.FormatInt}），Java 侧保持 String，不要改成 Long。</li>
 * </ul>
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * public class ArticleEntity {
 *     private String id;
 *     private String title;
 *     private String brief;
 *     private String content;
 *     private String coverImageUrl;
 *     private String manualTypeTag;
 *     private List<String> secondaryTags;
 *     private String authorId;
 *     private int status;
 *     private int viewCount;
 *     private int likeCount;
 *     private int commentCount;
 *     private int shareCount;
 *     private Map<String, String> extInfo;
 *     private OffsetDateTime createdAt;
 *     private OffsetDateTime updatedAt;
 *     private OffsetDateTime deletedAt;
 *
 *     public String getId() { return id; }
 *     public void setId(String id) { this.id = id; }
 *     public String getTitle() { return title; }
 *     public void setTitle(String title) { this.title = title; }
 *     public String getBrief() { return brief; }
 *     public void setBrief(String brief) { this.brief = brief; }
 *     public String getContent() { return content; }
 *     public void setContent(String content) { this.content = content; }
 *     public String getCoverImageUrl() { return coverImageUrl; }
 *     public void setCoverImageUrl(String coverImageUrl) { this.coverImageUrl = coverImageUrl; }
 *     public String getManualTypeTag() { return manualTypeTag; }
 *     public void setManualTypeTag(String manualTypeTag) { this.manualTypeTag = manualTypeTag; }
 *     public List<String> getSecondaryTags() { return secondaryTags; }
 *     public void setSecondaryTags(List<String> secondaryTags) { this.secondaryTags = secondaryTags; }
 *     public String getAuthorId() { return authorId; }
 *     public void setAuthorId(String authorId) { this.authorId = authorId; }
 *     public int getStatus() { return status; }
 *     public void setStatus(int status) { this.status = status; }
 *     public int getViewCount() { return viewCount; }
 *     public void setViewCount(int viewCount) { this.viewCount = viewCount; }
 *     public int getLikeCount() { return likeCount; }
 *     public void setLikeCount(int likeCount) { this.likeCount = likeCount; }
 *     public int getCommentCount() { return commentCount; }
 *     public void setCommentCount(int commentCount) { this.commentCount = commentCount; }
 *     public int getShareCount() { return shareCount; }
 *     public void setShareCount(int shareCount) { this.shareCount = shareCount; }
 *     public Map<String, String> getExtInfo() { return extInfo; }
 *     public void setExtInfo(Map<String, String> extInfo) { this.extInfo = extInfo; }
 *     public OffsetDateTime getCreatedAt() { return createdAt; }
 *     public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
 *     public OffsetDateTime getUpdatedAt() { return updatedAt; }
 *     public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
 *     public OffsetDateTime getDeletedAt() { return deletedAt; }
 *     public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
 *
 *     public ArticleEntity() { }
 *
 *     public ArticleEntity(String id, String title, String brief, String content, String coverImageUrl,
 *                          String manualTypeTag, List<String> secondaryTags, String authorId, int status,
 *                          int viewCount, int likeCount, int commentCount, int shareCount,
 *                          Map<String, String> extInfo, OffsetDateTime createdAt,
 *                          OffsetDateTime updatedAt, OffsetDateTime deletedAt) {
 *         this.id = id;
 *         this.title = title;
 *         this.brief = brief;
 *         this.content = content;
 *         this.coverImageUrl = coverImageUrl;
 *         this.manualTypeTag = manualTypeTag;
 *         this.secondaryTags = secondaryTags;
 *         this.authorId = authorId;
 *         this.status = status;
 *         this.viewCount = viewCount;
 *         this.likeCount = likeCount;
 *         this.commentCount = commentCount;
 *         this.shareCount = shareCount;
 *         this.extInfo = extInfo;
 *         this.createdAt = createdAt;
 *         this.updatedAt = updatedAt;
 *         this.deletedAt = deletedAt;
 *     }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "article", autoResultMap = true)
public class ArticleEntity {

    /** 对应 B 侧 {@code ID string `gorm:"primaryKey;type:varchar(32)"`}。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 对应 B 侧 {@code Title string `gorm:"type:varchar(255);not null"`}。 */
    private String title;

    /** 对应 B 侧 {@code Brief string `gorm:"type:varchar(512)"}。 */
    private String brief;

    /**
     * 对应 B 侧 {@code Content string `gorm:"type:text"`}。
     *
     * <p><b>存的是 MinIO 对象 key，不是 Markdown 正文</b>（见类级说明）。
     * ⚠️ B 侧的列注释写「Markdown正文」，但 create/update logic 写进去的是
     * {@code objectName}（{@code articles/content/<id>.md}），取值也一律走 MinIO。
     */
    private String content;

    /** 对应 B 侧 {@code CoverImageURL string `gorm:"type:varchar(255)"}。 */
    private String coverImageUrl;

    /** 对应 B 侧 {@code ManualTypeTag string `gorm:"type:varchar(64);index"`}。 */
    private String manualTypeTag;

    /**
     * 对应 B 侧 {@code SecondaryTags StringArray `gorm:"type:jsonb"`}。
     *
     * <p>⚠️ B 侧的 {@code StringArray.Scan} 在 {@code value == nil} 时置 <b>nil</b>（不是空切片）。
     * 本实现同样保持 null，这样 {@code update_article_logic.go:184} 的
     * {@code len(in.SecondaryTags) > 0} 与之保持同一语义。
     */
    @TableField(typeHandler = JsonbTypeHandler.StringListTypeHandler.class)
    private List<String> secondaryTags;

    /** 对应 B 侧 {@code AuthorID string `gorm:"type:varchar(32);index"`}（注意是 string 不是 int64）。 */
    private String authorId;

    /**
     * 对应 B 侧 {@code Status int32 `gorm:"type:smallint;default:0"`}。
     *
     * <p>取值 = proto {@code ArticleStatus} 的枚举数值。用 primitive int 而非包装类型，
     * 与 B 侧的 {@code int32} 非指针语义一致（proto 的 {@code optional ArticleStatus}
     * 是否"设置过"由 <b>请求对象</b>表达，不落在实体上）。
     */
    private int status;

    /** 对应 B 侧 {@code ViewCount int32 `gorm:"default:0"`}。 */
    private int viewCount;

    /** 对应 B 侧 {@code LikeCount int32 `gorm:"default:0"`}。 */
    private int likeCount;

    /** 对应 B 侧 {@code CommentCount int32 `gorm:"default:0"`}。 */
    private int commentCount;

    /** 对应 B 侧 {@code ShareCount int32 `gorm:"default:0"`}。 */
    private int shareCount;

    /**
     * 对应 B 侧 {@code ExtInfo JSONMap `gorm:"type:jsonb"`}。
     *
     * <p>⚠️ <b>值一律是 String</b>：版本号 {@code last_sync_version_ms} 在 B 侧是
     * {@code strconv.FormatInt(versionMs, 10)} 存成字符串的，
     * {@code published_once} 是 {@code "true"} 字符串而非布尔。
     * 把这个字段声明成 {@code Map<String, Object>} 会让「比较
     * {@code ExtLastSyncVersion != strconv.FormatInt(...)}」这类判据失效。
     */
    @TableField(typeHandler = JsonbTypeHandler.StringMapTypeHandler.class)
    private Map<String, String> extInfo;

    /**
     * 对应 B 侧 {@code CreatedAt time.Time `gorm:"autoCreateTime"`}。
     *
     * <p>用 {@link OffsetDateTime} 而非 LocalDateTime：本域三张表的时间列都是
     * <b>timestamptz</b>，LocalDateTime 丢时区信息。
     * 响应里要写 {@code article.CreatedAt.UnixMilli()}（B 侧是绝对时刻），
     * 用 OffsetDateTime 才能一字对应。
     */
    private OffsetDateTime createdAt;

    /**
     * 对应 B 侧 {@code UpdatedAt time.Time `gorm:"autoUpdateTime"`}。
     *
     * <p>🔴 <b>{@code fill = INSERT_UPDATE} 不是可选项</b>，它是 {@code autoUpdateTime} 的 Java 对应物。
     * MyBatis-Plus 没有「B 侧那个 tag」，自动填充必须<b>显式声明</b>，
     * 且<b>必须同时存在 {@code config.ArticleMetaObjectHandler}</b> ——
     * 只加注解而没有 handler，{@code fill} 同样<b>静默空转</b>。
     *
     * <p><b>不加会怎样（静默失效，不是报错）</b>：本域 8 处写 article 表全部走
     * {@code articleMapper.updateById(entity)}，而实体里的 {@code updatedAt} 是
     * <b>从库里读出来的陈旧值</b>；{@code updateById} 的默认
     * {@code updateStrategy = NOT_NULL} 会把这个陈旧值<b>原样写回</b>
     * ⇒ {@code updated_at} <b>永远停在建行那一刻</b>。
     *
     * <p>⚠️ <b>刻意不加到 {@code createdAt} 上</b>：B 侧 {@code autoCreateTime} 与
     * {@code autoUpdateTime} 是两个独立 tag，且插入路径由调用方显式给值。
     * 给 {@code createdAt} 加 {@code fill = INSERT}
     * 会让 handler 在<b>插入时也覆盖</b>调用方的显式赋值 —— 那才是真的引入漂移。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    /**
     * 对应 B 侧 {@code DeletedAt gorm.DeletedAt `gorm:"index"`}（软删除）。
     *
     * <p>⚠️ <b>关键行为点</b>：B 侧的软删除让默认查询自动带 {@code deleted_at IS NULL}，
     * 而 {@code Unscoped()} 才看得到已删除行。有两个显式入口：
     * <ul>
     *   <li>{@code FindOne}（默认）—— 已删文章查不到 ⇒ 二次删除返回 NotFound，
     *       {@code GetArticle} 也返回 NotFound（B 侧测试
     *       {@code community_article_test.go:303-305} 断言"旧路由不再返回已删文章"）。</li>
     *   <li>{@code FindOneUnscoped} —— <b>只有</b> {@code ArticleSyncResultConsumer}
     *       用它，让迟到的同步结果仍能找到已删文章（{@code handleDeleteResult}）。</li>
     * </ul>
     * Java 侧不引入 MyBatis-Plus 的 {@code @TableLogic}（那会让"哪些查询要 Unscoped"
     * 变成全局开关），而是<b>在 Mapper 上把两种语义写成两个明确方法</b>——
     * 与 B 侧的方法划分一致，也让"哪条路径能看到已删行"可被 review。
     */
    private OffsetDateTime deletedAt;
}
