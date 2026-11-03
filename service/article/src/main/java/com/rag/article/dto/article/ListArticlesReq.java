package com.rag.article.dto.article;

import com.rag.article.web.ArticleQueryValues;

/**
 * <p><b>职责</b>：文章列表的入参 —— <b>8 个 query 参数</b>（4 个筛选 + 4 个分页/排序）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/ListArticlesReq.java}
 * （类声明 {@code :53} · 四个默认值常量 {@code :56-62} · 八个字段 {@code :64-71}
 * ＋ 构造器里的解析 {@code :85-92}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>可变类 → immutable record</b>（D5 + A9）；</li>
 *         <li>B 侧把「8 个原始字符串 → 解析」放在<b>构造器</b>里 ⇒ record 的规范构造器只能收
 *             <b>已解析</b>的分量 ⇒ 解析搬进<b>静态工厂 {@link #of}</b>；</li>
 *         <li><b>包名压平</b>（D11）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>解析规则（<b>空值丢弃</b> / {@code 1,0} 真值表 / base-10 整数 / 字符串原样透传）
 *             与默认值（page=1、page_size=20、sort_by=create_time、desc=true）<b>逐条不变</b>；</li>
 *         <li>本类<b>不是</b> Jackson 的绑定目标 ⇒ <b>无 JSON key 契约</b>（全部来自 query）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 依赖 {@code com.rag.article.web.ArticleQueryValues}（域内副本 ⇒ <b>待收口</b>）</h2>
 * <p>共享层 {@code common/web/GetFormValuesFilter} 只有<b>上限闸门</b>（{@code goParseViolation} /
 * {@code writeGoStyleBadRequest}），<b>没有</b> {@code first} / {@code bool} / {@code int32} 这套
 * <b>取值真值表</b> ⇒ 本域自己落了一份（对应 B 侧 {@code article-api/…/form/GoFormValues.java}）⇒
 * <b>待收口</b>（global lane）。
 *
 * <h2>⚠️ 空值 ≠ 缺席：{@code ?page=} 在 Go 里走「缺席」分支</h2>
 * <p>因为 Go 的 {@code GetFormValues}（{@code httpx/util.go:44-45}）把空值整个丢掉。
 * {@code ?page=0} 则是<b>显式 0</b>，会原样传给下游（不分页、不回落成 1）。
 *
 * <h2>⚠️ 本类不做任何「值域」判断</h2>
 * <p>{@code page=-1} / {@code page_size=99999} / {@code sort_by=未知列} 在 Go 侧
 * <b>一律原样透传</b>给下游 ⇒ 本层提前拦会与下游重复且可能不一致
 * （B 侧 {@code ListArticlesReq.java:48-51}）。
 */
public record ListArticlesReq(

        String manualTypeTag,

        String secondaryTag,

        String relatedGameId,

        String authorId,

        int page,

        int pageSize,

        String sortBy,

        boolean desc) {

    /** Go {@code form:"page,default=1"}。 */
    public static final int DEFAULT_PAGE = 1;

    /** Go {@code form:"page_size,default=20"}。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Go {@code form:"sort_by,default=create_time"}。 */
    public static final String DEFAULT_SORT_BY = "create_time";

    /** Go {@code form:"desc,default=true"}。 */
    public static final boolean DEFAULT_DESC = true;

    /**
     * 从 8 个<b>原始 query 字符串</b>构造 —— 对应 B 侧构造器
     * {@code ListArticlesReq.java:77-93} 的逐条照抄。
     *
     * <p>⚠️ 全部形参都可能是 {@code null}（<b>缺席</b>）或 {@code ""}（<b>空值</b>），
     * 两者在 Go 侧<b>等价</b>（空值被整个丢掉）⇒ 由 {@link ArticleQueryValues#raw} 统一处理。
     *
     * <p>⚠️ <b>「默认值只在 key 缺席时填」</b>：key 存在（哪怕值是任意串）就按值解析，
     * 解析失败（如 {@code page=abc}）是 <b>400</b> 而<b>不是</b>回落默认值
     * （B 侧 {@code ListArticlesReq.java:22-36}）。
     *
     * @param rawManualTypeTag 原始 {@code manual_type_tag}
     * @param rawSecondaryTag  原始 {@code secondary_tag}
     * @param rawRelatedGameId 原始 {@code related_game_id}
     * @param rawAuthorId      原始 {@code author_id}
     * @param rawPage          原始 {@code page}
     * @param rawPageSize      原始 {@code page_size}
     * @param rawSortBy        原始 {@code sort_by}
     * @param rawDesc          原始 {@code desc}
     * @return 解析后的入参
     */
    public static ListArticlesReq of(String rawManualTypeTag,
                                     String rawSecondaryTag,
                                     String rawRelatedGameId,
                                     String rawAuthorId,
                                     String rawPage,
                                     String rawPageSize,
                                     String rawSortBy,
                                     String rawDesc) {
        return new ListArticlesReq(
                ArticleQueryValues.text(rawManualTypeTag, ""),
                ArticleQueryValues.text(rawSecondaryTag, ""),
                ArticleQueryValues.text(rawRelatedGameId, ""),
                ArticleQueryValues.text(rawAuthorId, ""),
                ArticleQueryValues.int32(rawPage, "page", DEFAULT_PAGE),
                ArticleQueryValues.int32(rawPageSize, "page_size", DEFAULT_PAGE_SIZE),
                ArticleQueryValues.text(rawSortBy, DEFAULT_SORT_BY),
                ArticleQueryValues.bool(rawDesc, "desc", DEFAULT_DESC));
    }
}
