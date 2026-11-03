package com.rag.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.article.domain.entity.ArticleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p><b>职责</b>：文章主表数据访问 —— 对应 B 侧
 * {@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mapper/ArticleMapper.java}。
 *
 * <p><b>B 出处</b>：同上文件（类声明 {@code :29-30} · {@code selectById} {@code :41-56} ·
 * {@code selectByIdUnscoped} {@code :69-72} · {@code incrViewCount} {@code :90-91} ·
 * {@code countAuthorView} {@code :125-136} · {@code selectAuthorView} {@code :190-205}）
 * ⇒ <b>整段照抄</b>（SQL 文本与 {@code @Param} 名逐字一致）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.mapper} → {@code com.rag.article.mapper}（D7 包压平）；</li>
 *         <li><b>jsonb TypeHandler 的落点</b>：B 侧的 {@code JsonbTypeHandler} 与本文件同包；
 *             C 侧同样落在 {@code com.rag.article.mapper}（<b>域内副本</b>，
 *             理由见 {@link JsonbTypeHandler} 的类级说明）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— SQL 文本、参数名、结果映射<b>逐字未改</b>
 *       ⇒ 查询条件与可见性语义完全一致。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>方法划分对应 B 侧的方法划分</b>：软删除的"看得见/看不见"必须<b>显式分成两个方法</b>
 * （{@link #selectById} / {@link #selectByIdUnscoped}），
 * 而不是靠一个全局 {@code @TableLogic} 开关 —— B 侧只有
 * {@code ArticleSyncResultConsumer} 一处需要 {@code Unscoped}，
 * 全局开关会让另外三条路径（Get / Update / Delete / 审核消费者）也看到已删行。
 *
 * <p>⚠️ 公开读路径<b>不在本 Mapper</b>，见 {@link ArticlePublicMapper}：
 * 它的可见性谓词是独立的 SQL 资产（复合 EXISTS + legacy 回退 + revision 标签过滤）。
 */
@Mapper
public interface ArticleMapper extends BaseMapper<ArticleEntity> {

    /**
     * 对应 B 侧 {@code FindOne}：{@code Where("id = ?", id).First(&article)}。
     *
     * <p>⚠️ <b>必须带 {@code deleted_at IS NULL}</b>：B 侧因
     * {@code DeletedAt gorm.DeletedAt} 自动追加该条件。少了它，已逻辑删除的文章
     * 仍能被查到 ⇒ {@code GetArticle} 不再返回 NotFound、
     * {@code DeleteArticleLogic} 的二次删除会重复发 retract 事件
     * （B 侧测试 {@code community_article_test.go:306-309} 专门断言这点）。
     *
     * @return null 表示不存在或已删除（B 侧对应 {@code gorm.ErrRecordNotFound}）
     */
    @Select("SELECT * FROM article WHERE id = #{id} AND deleted_at IS NULL")
    @Results(id = "articleEntityResultMap", value = {
            @Result(column = "secondary_tags", property = "secondaryTags",
                    typeHandler = JsonbTypeHandler.StringListTypeHandler.class),
            @Result(column = "ext_info", property = "extInfo",
                    typeHandler = JsonbTypeHandler.StringMapTypeHandler.class)
    })
    ArticleEntity selectById(@Param("id") String id);

    /**
     * article 表的显式结果映射 —— <b>本接口其余自定义 {@code @Select} 都必须用
     * {@code @ResultMap("articleEntityResultMap")} 引用它</b>（声明在 {@link #selectById} 上）。
     *
     * <p>🔴 <b>为什么不能只靠 {@code @TableName(autoResultMap = true)}</b>：
     * 那张自动结果映射只挂在 <b>MyBatis-Plus 自己生成的方法</b>（{@code selectById} 等）上。
     * 一旦用 {@code @Select} <b>覆盖</b>同名方法，MyBatis 走的就是注解自动映射 ——
     * 它按「Java 类型 + JDBC 类型」去全局 TypeHandler 注册表里查，
     * 而 {@code List<String>} / {@code Map<String,String>} <b>注册表里根本没有</b>
     * ⇒ 查不到就<b>静默跳过该属性</b>：不报错、不抛异常，实体字段恒为 {@code null}。
     *
     * <p><b>后果不是 SQL 失败，而是业务静默早退</b>：{@code ext_info} 整块读不出来，
     * {@code acceptSyncResult} 第 ② 条判据（eventId / versionMs 是否匹配）<b>永远不成立</b>
     * ⇒ 发布流程每次都在这一步 return，文章永远停在 REVIEWING。
     * 这类缺陷「编译通过、装配通过、SQL 全部成功」，只有真正读一次数据才暴露。
     *
     * <p>⚠️ 只列 jsonb 两列；其余列仍走自动映射（MyBatis 的 {@code autoMappingBehavior=PARTIAL}
     * 自动补齐未显式声明的列），所以新增普通列时<b>不需要</b>来这里加一行。
     */
    @Select("SELECT * FROM article WHERE id = #{id}")
    @ResultMap("articleEntityResultMap")
    ArticleEntity selectByIdUnscoped(@Param("id") String id);

    /**
     * 对应 B 侧 {@code IncrViewCount}：
     * {@code Model(&Article{}).Where("id = ?", id).UpdateColumn("view_count", gorm.Expr("view_count + ?", 1))}。
     *
     * <p><b>⚠️ 用 {@code view_count = view_count + 1} 而不是读-改-写</b>：
     * 与其它并发实例共享同一计数，读-改-写会丢更新。
     *
     * <p><b>⚠️ 本方法<b>不</b>带 {@code deleted_at IS NULL}</b>：
     * B 侧的 {@code UpdateColumn} 走 ORM 的 Model 路径，软删除条件在此<b>确实会被追加</b>
     * （ORM 对 Update 系列同样注入软删除条件），但调用方
     * {@code get_article_logic.go:72} 已先 {@code FindOne} 拿到未删的行，
     * 且<b>失败只记日志不中断</b>（best-effort）。
     * 这里显式保留软删除条件以与 B 侧对齐：已删文章不该被计阅读量。
     */
    @Update("UPDATE article SET view_count = view_count + 1 WHERE id = #{id} AND deleted_at IS NULL")
    int incrViewCount(@Param("id") String id);

    /**
     * 作者视角分页列表的<b>计数</b>部分 —— 对应 B 侧 {@code List} 里的 {@code db.Count(&total)}。
     *
     * <p>⚠️ <b>count 与 find 必须用同一套 where 条件</b>：B 侧
     * {@code db := m.Db.WithContext(ctx).Model(&Article{})} 之后链式加 where，
     * 再分别 {@code db.Count} / {@code db.Order(...).Offset().Limit().Find}——
     * 同一个 db 对象承载两段。拆成两个方法时若 where 拼装不一致，
     * total 与实际返回条数会对不上（分页器计算出错误的总页数）。
     * 三条可选条件都是 {@code != ""} 才生效（B 侧:142-150）。
     *
     * <p>二级标签过滤用 {@code secondary_tags @> '["tag"]'}（jsonb 包含算子），
     * 语义与 B 侧 {@code model/article.go:146} 一致。
     *
     * <p>🔴 <b>条件里的参数名必须与 {@code @Param} 一致</b>。
     * 本方法的第二个参数是 {@code @Param("secondaryTagJson")}（为了避开引号转义而声明），
     * 而条件曾写 {@code secondaryTag} ⇒ MyBatis 找不到该参数，每次调用都报
     * {@code BindingException: Parameter 'secondaryTag' not found} ⇒ <b>作者视角 ListArticles 100% 报错</b>。
     * 有一版 79 条测试全绿也看不到：它们全部 mock 了 mapper，从未让 MyBatis 真的解析过这段 script。
     * <br>等价形式：B 侧的判据是 {@code opt.SecondaryTag != ""}（model/article.go:145），
     * Java 侧的对应值——调用点在 {@code ArticleDomainService} 里已经把
     * 空串与 null 都归一为 {@code secondaryTagJson = null}，因此这里只判 null / 空即可。
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM article
            WHERE deleted_at IS NULL
            <if test="manualTypeTag != null and manualTypeTag != ''">
              AND manual_type_tag = #{manualTypeTag}
            </if>
            <if test="secondaryTagJson != null and secondaryTagJson != ''">
              AND secondary_tags @&gt; CAST(#{secondaryTagJson} AS jsonb)
            </if>
            <if test="authorId != null and authorId != ''">
              AND author_id = #{authorId}
            </if>
            </script>
            """)
    long countAuthorView(@Param("manualTypeTag") String manualTypeTag,
                         @Param("secondaryTagJson") String secondaryTagJson,
                         @Param("authorId") String authorId);

    /**
     * 作者视角分页列表的<b>取数</b>部分 —— 对应 B 侧 {@code db.Order(order).Offset(offset).Limit(pageSize).Find}。
     *
     * <p><b>⚠️ 排序字段必须白名单映射</b>：B 侧是一个 switch
     * （{@code model/article.go:163-175}），{@code SortBy} 只认
     * {@code create_time / view_count / like_count}，<b>其余值一律落到默认
     * {@code created_at desc}</b>。<b>注意 B 侧的默认分支不带 desc 后缀拼接逻辑</b>：
     * <pre>
     * order := "created_at desc"
     * if opt.SortBy != "" {
     *     switch opt.SortBy { case "create_time": … case "view_count": … case "like_count": … }
     *     if opt.Desc { order += " desc" } else { order += " asc" }
     * }
     * </pre>
     * ⇒ 🔴 <b>{@code SortBy == ""} 时的方向必须恒为 DESC</b>，因为<b>方向拼接整体位于
     * {@code if opt.SortBy != ""} 之内</b>（B 侧出处 {@code model/article.go:161-176}，<b>消费点</b>）：
     * {@code SortBy} 为空 ⇒ {@code Desc} 标志<b>被完全忽略</b>，恒 {@code created_at desc}。
     * ⇒ SQL 侧对应<b>三分支</b>的 {@code <choose>}：
     * {@code <when test="sortBy == null or sortBy == ''">DESC</when>}（第一分支兜住空排序）
     * → {@code <when test="desc">DESC</when>} → {@code <otherwise>ASC</otherwise>}。
     * ⚠️ <b>不能把它简化成两分支</b>：那样 {@code sortBy == ""} 会落到 {@code <otherwise>ASC}
     * ⇒ 默认调用整页顺序颠倒。
     * {@code SortBy == "unknown"} 落到默认字段 {@code created_at} 且方向随 {@code Desc}
     * —— 这与 B 侧一致（B 侧会拼出 {@code "created_at asc"}/{@code "created_at desc"}，PG 接受）。
     * <br>proto3 里 {@code string sort_by} 与 {@code bool desc} 的默认值分别是 {@code ""} 与 {@code false}，
     * 所以<b>任何不显式设置排序的调用</b>（作者自己的文章列表，最常见形态）在 B 侧都是「最新在前」。
     * <br>⚠️ 对照：<b>公开路径不受这条约束</b>。B 侧的 {@code publicArticleOrder}（{@code article_public.go:132-147}）
     * 是<b>无条件</b>应用方向的（{@code SortBy=="" && !Desc ⇒ ASC}），见 {@link ArticlePublicMapper#selectPublicPage}。
     * 两个 mapper 的方向规则<b>刻意不同</b>，不要"统一"。
     *
     * <p>⚠️ <b>没有二级排序键</b>：B 侧的作者视角 List 不加 id 兜底排序
     * （公开路径 {@code publicArticleOrder} 才加 {@code , article.id ASC/DESC}）。
     * 分页结果在同值行上可能不稳定，这是 B 侧的既有行为，不要"补一个稳定排序"。
     */
    @Select("""
            <script>
            SELECT * FROM article
            WHERE deleted_at IS NULL
            <if test="manualTypeTag != null and manualTypeTag != ''">
              AND manual_type_tag = #{manualTypeTag}
            </if>
            <if test="secondaryTagJson != null and secondaryTagJson != ''">
              AND secondary_tags @&gt; CAST(#{secondaryTagJson} AS jsonb)
            </if>
            <if test="authorId != null and authorId != ''">
              AND author_id = #{authorId}
            </if>
            ORDER BY
            <choose>
              <when test="sortBy == 'create_time'">created_at</when>
              <when test="sortBy == 'view_count'">view_count</when>
              <when test="sortBy == 'like_count'">like_count</when>
              <otherwise>created_at</otherwise>
            </choose>
            <choose>
              <when test="sortBy == null or sortBy == ''">DESC</when>
              <when test="desc">DESC</when>
              <otherwise>ASC</otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @ResultMap("articleEntityResultMap")
    List<ArticleEntity> selectAuthorView(@Param("manualTypeTag") String manualTypeTag,
                                          @Param("secondaryTagJson") String secondaryTagJson,
                                          @Param("authorId") String authorId,
                                          @Param("sortBy") String sortBy,
                                          @Param("desc") boolean desc,
                                          @Param("limit") int limit,
                                          @Param("offset") long offset);

    /**
     * 对应 B 侧 {@code WithArticleTx} 里的 {@code SELECT … FOR UPDATE}。
     *
     * <p><b>⚠️ 必须 {@code Unscoped}</b>：B 侧是
     * {@code tx.Unscoped().Clauses(clause.Locking{Strength: "UPDATE"}).Where("id = ?").First()}
     * （{@code community_revision.go:84-85}）。审核消费者与发布回调都要在
     * 已软删的行上做判定（{@code current.DeletedAt.Valid} 分支），看得到已删行是前提。
     *
     * <p><b>行锁的语义</b>：串行化同一 article 的并发更新。
     * 用在两处：审核消费者入队 outbox、发布回调写 revision + 推进指针。
     * 没有它，两个并发回调会算出相同的 {@code max(revision)+1} 并撞主键。
     */
    @Select("SELECT * FROM article WHERE id = #{id} FOR UPDATE")
    @ResultMap("articleEntityResultMap")
    ArticleEntity selectByIdForUpdate(@Param("id") String id);

    /**
     * 对应 B 侧 {@code DeleteTx}：{@code tx.Delete(&Article{}, "id = ?", id)}。
     *
     * <p>B 侧的 {@code Delete} 因 {@code DeletedAt gorm.DeletedAt} 是<b>软删除</b>：
     * 写 {@code deleted_at = now()} 而不是 DELETE 行。
     *
     * <p>⚠️ <b>不能删 MinIO 对象</b>（B 侧 {@code delete_article_logic.go:64-66} 注释）：
     * 历史已发布 revision 的冻结字节必须仍可被 H03 历史消费者读取，
     * 对象回收需要独立的保留期/确认策略。本方法只改数据库。
     */
    @Update("UPDATE article SET deleted_at = now() WHERE id = #{id} AND deleted_at IS NULL")
    int softDelete(@Param("id") String id);
}
