package com.rag.article.service;

import com.rag.article.contract.ErrCodes;
import com.rag.article.contract.ErrMsg;
import com.rag.article.domain.service.ArticlePublicDomainService;
import com.rag.article.dto.article.Article;
import com.rag.article.interceptor.JwtAuthInterceptor;
import com.rag.common.exception.BusinessException;
import com.rag.proto.article.ArticleStatus;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <p><b>API 层的公共支撑</b> —— 身份解析、作者昵称补全、proto → DTO 投影、文案查表。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       ① B 侧 {@code ArticleApiSupport} ＋ {@code ArticleAuthorResolver} <b>两个类</b>
 *       合并为本类（映射表 §1.2 已批准的 N:1）；
 *       ② 包名压平（{@code com.rag.article.article.api.service} → {@code com.rag.article.service}，D7）；
 *       ③ 错误码类型由 B 侧的 {@code ErrorCode} 改为本域的 {@link ErrCodes} / {@link ErrMsg}；
 *       ④ 补 B 出处行号。</li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价，但有一处必须登记的偏离</b>（见下方「作者昵称」段）——
 *       身份解析（claim → uid）的 4 条规则、proto → DTO 的 16 个字段、
 *       {@code ext_info} 的<b>码点序</b>排序、{@code secondary_tags} 空 ⇒ {@code null}、
 *       昵称补全的 4 条回落规则、{@code fallbackAuthorName} 的两条文案，全部逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/article/article-api/src/main/java/com/rag/article/article/api/service/ArticleApiSupport.java:15-22（userId）
 * B 出处：…/service/ArticleApiSupport.java:23-36（resolveUserIdClaim）
 * B 出处：…/service/ArticleApiSupport.java:37-42（trimToNull）
 * B 出处：…/service/ArticleApiSupport.java:124-131（enrichArticleAuthor）
 * B 出处：…/service/ArticleApiSupport.java:132-152（enrichArticleAuthors）
 * B 出处：…/service/ArticleApiSupport.java:153-160（fallbackAuthorName）
 * B 出处：…/service/ArticleApiSupport.java:161-170（cloneStringMap + CODE_POINT_ORDER）
 * B 出处：…/service/ArticleApiSupport.java:196-217（toArticle）
 * B 出处：…/service/ArticleApiSupport.java:218-223（cloneStringList）
 * B 出处：…/service/ArticleApiSupport.java:224-226（isPublished）
 * B 出处：…/service/ArticleApiSupport.java:228-230（msg）
 * B 出处：…/service/ArticleAuthorResolver.java:18-61（resolveAuthorName 的 4 条回落）
 *       </pre></li>
 * </ol>
 *
 * <h2>🔴 作者昵称补全：<b>恒为回落值</b>（缺口 G-2 未闭合 ⇒ 已登记偏离）</h2>
 * <p>B 侧 {@code ArticleAuthorResolver} 通过 <b>跨域 gRPC</b>（{@code user-rpc} 的
 * {@code GetUser}）取真实昵称。而 <b>RAG-V1 不迁移 {@code users} 域的 {@code UserRpcClient}
 * 到本域</b>（映射表 §1.2 缺口 G-2）⇒ ⇒ 本类<b>没有跨域出口</b>。
 * <p>⇒ <b>处置</b>：保留 B 侧 {@code resolveAuthorName} 的<b>全部 4 条回落规则</b>，
 * 只把「RPC 成功 ⇒ 真实昵称」这一支替换成<b>它自己的失败分支</b>：
 * <pre>
 *   B: trim → 空 ⇒ "未知作者"
 *      parseInt64 失败 或 uid ≤ 0 ⇒ "用户 <id>"
 *      RPC 成功 ⇒ resp.User.Username（trim 后非空）
 *      RPC 失败 / resp 未找到 / 用户名为空 ⇒ "用户 <id>"
 *
 *   C: trim → 空 ⇒ "未知作者"
 *      parseInt64 失败 或 uid ≤ 0 ⇒ "用户 <id>"
 *      （本无 RPC 支）⇒ "用户 <id>"
 * </pre>
 * <p>⇒ <b>可观察差异</b>：当 <b>B 侧的 user-rpc 调用成功</b>时，B 返回真实昵称、C 返回
 * {@code "用户 <id>"}。<b>其余全部一致</b>（键存在性、空值语义、两条回落文案、
 * 「同作者只调一次」的缓存语义都保留）。
 * <p>⇒ <b>刻意不选「干脆不输出 author_name」</b>：那会让 {@code author_name} 键在两侧
 * 一边永远缺席、一边永远存在 ⇒ 差异面比本方案<b>大</b>（本方案只有「值」不同，没有「键是否存在」不同）。
 *
 * <h2>🔴 {@code ext_info} 必须按<b>码点序</b>排序（不是 {@code String.compareTo}）</h2>
 * <p>B 侧用 Go 的 {@code strings.Compare}（<b>按 Unicode 码点</b>）⇒ Java 的
 * {@code String.compareTo} 是 <b>UTF-16 code unit</b> 比较 ⇒ 对<b>增补平面</b>字符
 * （如 U+1F600）两者顺序<b>相反</b> ⇒ ⇒ 必须自己写码点比较器。
 * <p>B 出处：{@code …/service/ArticleApiSupport.java:161-170}（{@code CODE_POINT_ORDER}）。
 */
public final class ArticleApiSupport {

    private ArticleApiSupport() {
    }

    // ============== 身份解析（helper.go:17-39 extractCurrentUserID） ==============

    /**
     * 从 request 的 attribute 取 claim 并解析成 uid。
     *
     * <p>⚠️ <b>失败不是 401，而是业务码 1005（HTTP 200 + 信封）</b>：
     * Go 侧 {@code handler.Authorize} 只验签名与 exp，<b>不校验 claim 取值</b>；
     * 取值判定在 logic 层（{@code helper.go:17-39}）⇒ 失败返回 {@code ErrorUnauthorized}(1005)
     * ⇒ 最终输出 <b>HTTP 200 + {code:1005,msg:"请先登录"}</b>。
     *
     * <p>B 出处：{@code …/service/ArticleApiSupport.java:15-22}。
     *
     * @throws BusinessException claim 缺席 / 非数字 / 非正数（码 1005）
     */
    public static String userId(HttpServletRequest request) {
        Object claim = request.getAttribute(JwtAuthInterceptor.CLAIM_ATTRIBUTE);
        String resolved = resolveUserIdClaim(claim);
        if (resolved == null) {
            throw new BusinessException(ErrCodes.ERROR_UNAUTHORIZED.getCode(),
                    ErrMsg.getMsg(ErrCodes.ERROR_UNAUTHORIZED.getCode()));
        }
        return resolved;
    }

    /**
     * claim → uid 的字符串形态。
     *
     * <p>B 出处：{@code …/service/ArticleApiSupport.java:23-36}。
     */
    static String resolveUserIdClaim(Object claim) {
        if (claim == null) {
            return null;
        }
        if (claim instanceof byte[] bytes) {
            return trimToNull(new String(bytes, StandardCharsets.UTF_8));
        }
        // 其余一律走 Go 的 default 分支语义：fmt.Sprintf("%v", uid) + TrimSpace
        String rendered = String.valueOf(claim);
        if ("<nil>".equals(rendered)) {
            return null;
        }
        return trimToNull(rendered);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ============== 作者昵称补全（helper.go:41-103） ==============

    /**
     * 单篇文章的昵称补全。
     *
     * <p>B 出处：{@code …/service/ArticleApiSupport.java:124-131}。
     *
     * @return 补全后的文章（record 不可变 ⇒ 返回<b>新实例</b>）
     */
    public static Article enrichArticleAuthor(Article article) {
        if (article == null) {
            return null;
        }
        return article.withAuthorName(resolveAuthorName(article.authorId()));
    }

    /**
     * 列表的昵称补全（<b>同作者只解析一次</b>）。
     *
     * <p>B 出处：{@code …/service/ArticleApiSupport.java:132-152}。
     *
     * @return 补全后的列表（⚠️ <b>空列表保持 {@code null}</b>，不是 {@code []}）
     */
    public static List<Article> enrichArticleAuthors(List<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return articles;
        }
        Map<String, String> cache = new LinkedHashMap<>();
        List<Article> out = new ArrayList<>(articles.size());
        for (Article article : articles) {
            String authorId = trimToNull(article.authorId());
            if (authorId == null) {
                out.add(article.withAuthorName(fallbackAuthorName("")));
                continue;
            }
            String cached = cache.get(authorId);
            if (cached != null) {
                out.add(article.withAuthorName(cached));
                continue;
            }
            String authorName = resolveAuthorName(authorId);
            cache.put(authorId, authorName);
            out.add(article.withAuthorName(authorName));
        }
        return out;
    }

    /**
     * 昵称解析 —— <b>恒为回落值</b>（缺口 G-2：B 侧的 user-rpc 跨域出口本域没有）。
     *
     * <p>B 出处：{@code …/service/ArticleAuthorResolver.java:18-61}（4 条回落规则逐条保留，
     * 仅「RPC 成功 ⇒ 真实昵称」那一支替换为其失败分支）。
     */
    static String resolveAuthorName(String authorId) {
        String trimmed = authorId == null ? null : authorId.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return fallbackAuthorName("");
        }
        // Go: strconv.ParseInt(authorID, 10, 64)；err != nil || uid <= 0 → fallback
        // ⚠️ 「<= 0」这条不能只靠「能否解析」覆盖：authorId = "0" / "-5" 都能解析成功。
        long uid;
        try {
            uid = Long.parseLong(trimmed, 10);
        } catch (NumberFormatException e) {
            return fallbackAuthorName(trimmed);
        }
        if (uid <= 0L) {
            return fallbackAuthorName(trimmed);
        }
        // ⚠️ B 侧此处会调 user-rpc 取真实昵称；本域**无该跨域出口**（缺口 G-2）
        //    ⇒ 走 B 侧同一函数的**失败分支** ⇒ "用户 <id>"。
        return fallbackAuthorName(trimmed);
    }

    /**
     * 昵称回落文案 —— <b>两条</b>，逐字照抄。
     *
     * <p>B 出处：{@code …/service/ArticleApiSupport.java:153-160}。
     */
    public static String fallbackAuthorName(String authorId) {
        String trimmed = trimToNull(authorId);
        if (trimmed != null) {
            return "用户 " + trimmed;
        }
        return "未知作者";
    }

    // ============== proto → DTO 映射 ==============

    /**
     * proto 的 {@code Article} → DTO 的 {@code Article}（<b>16 个字段逐条</b>）。
     *
     * <p>B 出处：{@code …/service/ArticleApiSupport.java:196-217}。
     */
    public static Article toArticle(com.rag.proto.article.Article in) {
        return new Article(
                in.getId(),
                in.getTitle(),
                in.getBrief(),
                // Go: Content: res.Article.MarkdownContent
                in.getMarkdownContent(),
                in.getCoverImageUrl(),
                in.getManualTypeTag(),
                cloneStringList(in.getSecondaryTagsList()),
                in.getAuthorId(),
                null,
                in.getCreateTime(),
                in.getUpdateTime(),
                // Go: int32(res.Article.Status) —— 数值，不是 "PUBLISHED"
                in.getStatusValue(),
                in.getViewCount(),
                in.getLikeCount(),
                in.getCommentCount(),
                in.getShareCount(),
                cloneStringMap(in.getExtInfoMap()));
    }

    /**
     * 公开读视图 → DTO 的 {@code Article}（<b>字段集与 proto 分支一致</b>）。
     *
     * <p>⚠️ B 侧的公开读走的是 RPC（拿 proto 的 {@code Article}）⇒ 本类用
     * {@code ArticlePublicDomainService.PublicArticleView}（D2 域内直连）
     * ⇒ 字段名一一对应（{@code markdown} ↔ {@code markdown_content}、
     * {@code createTimeMs} ↔ {@code create_time}）。
     */
    public static Article toArticle(ArticlePublicDomainService.PublicArticleView view) {
        return new Article(
                view.id,
                view.title,
                view.brief,
                view.markdown,
                view.coverImageUrl,
                view.manualTypeTag,
                cloneStringList(view.secondaryTags),
                view.authorId,
                null,
                view.createTimeMs,
                view.updateTimeMs,
                view.status,
                view.viewCount,
                view.likeCount,
                view.commentCount,
                view.shareCount,
                cloneStringMap(view.extInfo));
    }

    /**
     * 字符串列表的克隆 —— <b>空 ⇒ {@code null}</b>（不是 {@code []}）。
     *
     * <p>B 出处：{@code …/service/ArticleApiSupport.java:218-223}。
     */
    public static List<String> cloneStringList(List<String> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return List.copyOf(source);
    }

    /**
     * 字符串 map 的克隆 —— <b>空 ⇒ {@code null}</b>，且 key 按<b>码点序</b>。
     *
     * <p>B 出处：{@code …/service/ArticleApiSupport.java:161-170}。
     */
    public static Map<String, String> cloneStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        // ⚠️ 必须用 {@link #CODE_POINT_ORDER}，不能用 TreeMap 的自然序（String.compareTo）。
        TreeMap<String, String> sorted = new TreeMap<>(CODE_POINT_ORDER);
        sorted.putAll(source);
        return sorted;
    }

    /**
     * Go {@code strings.Compare} 的等价比较器（<b>按 Unicode 码点</b>）。
     *
     * <p>⚠️ {@code String.compareTo} 是 <b>UTF-16 code unit</b> 比较 ⇒ 对增补平面字符顺序<b>相反</b>
     * ⇒ ⇒ 必须自己写。
     */
    private static final java.util.Comparator<String> CODE_POINT_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = a.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        // 一方是另一方的前缀 ⇒ 短者在前（与 strings.Compare 一致）
        return Integer.compare(a.length() - i, b.length() - j);
    };

    /**
     * 是否已发布 —— 判定的是<b>数值</b> {@code 2}。
     *
     * <p>B 出处：{@code …/service/ArticleApiSupport.java:224-226}。
     */
    public static boolean isPublished(int status) {
        return status == ArticleStatus.PUBLISHED_VALUE;
    }

    // ============== 文案查表 ==============

    /**
     * 错误码 → 文案（本域错误契约，<b>逐域隔离</b>）。
     *
     * <p>B 出处：{@code …/service/ArticleApiSupport.java:228-230}。
     */
    public static String msg(ErrCodes code) {
        return ErrMsg.getMsg(code.getCode());
    }
}
