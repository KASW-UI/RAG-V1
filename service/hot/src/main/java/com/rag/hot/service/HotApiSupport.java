package com.rag.hot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.exception.BusinessException;
import com.rag.common.web.JsonBodyConverter;
import com.rag.common.web.JsonBodyConverterConfig;
import com.rag.hot.contract.ErrCodes;
import com.rag.hot.contract.ErrMsg;
import com.rag.hot.dto.article.HotArticleItem;
import com.rag.hot.dto.article.HotArticlesReq;
import com.rag.hot.dto.article.HotArticlesResp;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p><b>hot 域的无状态辅助集</b> —— 分页数值契约 + query / body 的输入解释规则。
 *
 * <p><b>B 出处（3 个 B 侧类 → 本仓 1 个辅助类 + 1 个编排类 · 映射表 hot.md §1.2）</b>：
 * <pre>
 *   B 侧 hot-api/.../service/HotRankPaginator.java   ⇒ 本类的分页/排名段
 *       4 个阈值常量（1/20/50/100 + "rolling"）           :47-62
 *       normalize(HotArticlesReq)                       :76-94
 *       paginate(total, page, pageSize)                 :104-120
 *       assignRanks(List)                               :139-149
 *       slice(List, page, pageSize)                     :157-167
 *
 *   B 侧 hot-api/.../form/GoFormValues.java            ⇒ 本类的输入解释段
 *       MAX_FORM_PARAM_COUNT = 2048                     :126
 *       raw(String)                                     :152-157
 *       int32(raw, fieldName, defaultValue)             :169-186
 *       firstFormValue(request, name)                   :200-216
 *       requireFormValueCountWithinLimit(request)       :224-245
 *       requireParsableJsonBody(request)                :267-312
 *       parseFailure(cause)                             :321-326
 * </pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：四处<b>纯表达层</b>改动 ——
 *       ① <b>两个 B 侧类合并成一个</b>（映射表 hot.md §1.2 的「1:N · B 侧 3 类 → C 2 类」口径：
 *       B 的 {@code HotRankPaginator} + {@code GoFormValues} 合并为本类的两个段，
 *       编排那一只归 {@link HotApiService}）；
 *       ② <b>可变 POJO → immutable record</b>（D5 + A9）⇒ {@link #assignRanks} 改成
 *       「<b>产出新 record</b>」而不是「就地 setRank」；
 *       ③ <b>form 值上限闸门（2048）不再在本类</b> —— 改由共享层
 *       {@code com.rag.common.web.ValueLimitInterceptor} 承担（见下方「⚠️ 上限闸门」专段）；
 *       ④ JSON 语法检查用的 {@code ObjectMapper} 改成<b>本类私有实例</b>（B 侧同款，
 *       见 {@link #JSON_SYNTAX_CHECKER}）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>4 个阈值常量、{@code normalize} / {@code paginate} / {@code assignRanks} / {@code slice}
 *             的<b>分支与边界</b>逐条未动（含 int32 乘法溢出 ⇒ 空页）；</li>
 *         <li>query 解析的<b>三条规则</b>（空值丢弃 / 同名多值取首值 / base-10 int32）逐条未动；</li>
 *         <li>JSON body 的<b>拒绝路径</b>（畸形 / 顶层非对象 ⇒ 5001）逐条未动。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方逐条行号。</li>
 * </ol>
 *
 * <p><b>⚠️ 上限闸门（2048）为什么不在本类</b>：
 * B 侧的 {@code requireFormValueCountWithinLimit} 数的是 {@code request.getParameterMap()} 里的
 * <b>非空值</b>；本仓的共享层 {@code ValueLimitInterceptor} 数的是<b>原始 query string</b> 里的
 * 非空值，并额外覆盖 B 侧 {@code net/url} 的三条解析错误 ⇒ 二者判据**不完全相同**，
 * 但<b>拒绝形状完全一致</b>（hot 域配 {@code rag.http.go-form-limit.mode: envelope}
 * ⇒ 200 + {code:5001, msg:"internal server error", data:null}）
 * ⇒ ⇒ 为避免「同一请求被两个口径各拒一次」，本类<b>不重复实现</b>，闸门归共享层
 * （共享层的写权面在 {@code common/**}，本域只读 —— AGENTS.md §五.3 规则 3）。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：全是静态常量与静态方法，
 * {@code @UtilityClass} 在本仓被明令禁止 ⇒ 不引入（A13 判据不适用）。
 */
public final class HotApiSupport {

    /** B 侧 {@code getHotArticlesLogic.go:25} {@code defaultPage = int32(1)}。 */
    public static final int DEFAULT_PAGE = 1;

    /** B 侧 {@code :26} {@code defaultPageSize = int32(20)}。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** B 侧 {@code :27} {@code maxPageSize = int32(50)}。 */
    public static final int MAX_PAGE_SIZE = 50;

    /** B 侧 {@code :28} {@code maxHotArticles = int32(100)}，即向 hot-rpc 请求的 TopK。 */
    public static final int MAX_HOT_ARTICLES = 100;

    /** B 侧 {@code :30} {@code rollingScope = "rolling"} —— 编译期常量，恒定值。 */
    public static final String ROLLING_SCOPE = "rolling";

    /** B 侧 {@code httpx/util.go:59-61} 的数组记法后缀：{@code ?page[]=5} 会被剥掉 {@code []}。 */
    private static final String ARRAY_SUFFIX = "[]";

    /**
     * 只做 JSON <b>语法与顶层类型</b>判定用的 mapper。
     *
     * <p>刻意<b>不</b>复用 Spring 运行时那个 mapper：本方法只回答「这个 body 是不是一个
     * 合法的 JSON 对象」，与「序列化成什么形状」无关 ⇒ 任何序列化定制都不影响判定结果。
     */
    private static final ObjectMapper JSON_SYNTAX_CHECKER = new ObjectMapper();

    private HotApiSupport() {
        // 工具类，禁止 new
    }

    // ==================== 输入解释规则（B 侧 GoFormValues） ====================

    /**
     * B 侧 {@code GoFormValues.raw}（:152-157）的「空值丢弃」规则。
     *
     * @param queryValue 原始字符串（可能为 {@code null} 或 {@code ""}）
     * @return {@code null} 表示「这个 key 在 B 侧<b>不存在</b>」→ 调用方走 tag 默认值
     */
    public static String raw(String queryValue) {
        if (queryValue == null || queryValue.isEmpty()) {
            return null;
        }
        return queryValue;
    }

    /**
     * B 侧 {@code strconv.ParseInt(str, 10, 32)} 对应的 {@code int32} form 字段解析
     * （B 侧 {@code GoFormValues.int32}，:169-186）。
     *
     * <p><b>语义还一样吗</b>：一样 ——
     * <ul>
     *   <li>key 缺席或值为空 ⇒ 返回 {@code defaultValue}（B 侧 {@code raw} 的空值丢弃规则）；</li>
     *   <li>否则用 {@link Integer#parseInt(String, int)}（<b>base 10</b>、32 位，越界即失败）
     *       ⇒ 与 B 侧 {@code strconv.ParseInt(str, 10, 32)} 逐条一致；</li>
     *   <li>解析失败 ⇒ 抛 {@link BusinessException}（5001 + {@code "internal server error"}）
     *       ⇒ 由 advice 渲染成 <b>200 + 信封</b>（B 侧同款，不是 400）。</li>
     * </ul>
     *
     * @param rawValue    原始字符串
     * @param fieldName   键名（只用于日志可读性，<b>不进 wire</b>）
     * @param defaultValue 键缺席或值为空时的默认值
     * @return 解析后的值
     */
    public static int parseInt32(String rawValue, String fieldName, int defaultValue) {
        String v = raw(rawValue);
        if (v == null) {
            return defaultValue;
        }
        try {
            // B 侧：strconv.ParseInt(str, 10, 32) —— base 10、32 位，越界即 error
            return Integer.parseInt(v, 10);
        } catch (NumberFormatException e) {
            // ⚠️ msg 必须**恰好**是 GetErrMsg(5001) = "internal server error"。
            // B 侧 parse 错误不是 *CodeError ⇒ errors.As 取不到 ⇒ msg 走 GetErrMsg(code) 兜底，
            // 响应里不会出现任何解析细节。把字段名/原值拼进 msg 会造成 L1 字节级漂移，
            // 因此细节只挂到 cause 上（日志可见，wire 不可见）。
            throw parseFailure(new NumberFormatException(
                    "unmarshal field \"" + fieldName + "\": strconv.ParseInt: parsing \""
                            + v + "\": invalid syntax"));
        }
    }

    /**
     * B 侧 {@code httpx/util.go:44-45 + :59-61} 与 {@code unmarshaler.go:835-846}：
     * <b>同名多值取首值</b>，空值丢弃，{@code []} 数组记法后缀等价于无后缀
     * （B 侧 {@code GoFormValues.firstFormValue}，:200-216）。
     *
     * <p>⚠️ <b>不能用 {@code @RequestParam String}</b>：Spring 在同名多值时会把
     * {@code String[]} 用 {@code ","} 拼成 {@code "5,3"} ⇒ 后续 {@code parseInt} 失败 ⇒ 整包 5001，
     * 而 B 侧是「取第 0 个 ⇒ page=5」。
     *
     * @param request 当前请求
     * @param name    键名（{@code "page"} / {@code "page_size"}）
     * @return 首个非空值；不存在时返回 {@code null}（⇒ 调用方走默认值）
     */
    public static String firstFormValue(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) {
            // B 侧：httpx/util.go:59-61 —— "?page[]=5" 剥掉 [] 后缀后归到 key "page"
            values = request.getParameterValues(name + ARRAY_SUFFIX);
        }
        if (values == null) {
            return null;
        }
        for (String v : values) {
            // B 侧：httpx/util.go:44-45 —— 空值被整个丢弃（?page= 与 ?page 都落这里）
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /**
     * B 侧 {@code httpx/requests.go:66-68} 的 {@code ParseJsonBody}：本端点的 JSON body
     * <b>只用于「能不能解析」这一个判定</b>，解析出来的字段<b>整体被忽略</b>
     * （B 侧 {@code GoFormValues.requireParsableJsonBody}，:267-312）。
     *
     * <p>⇒ 需要复刻的只有 B 侧的<b>拒绝路径</b>：
     * <ul>
     *   <li>{@code ContentLength > 0} 且 {@code Content-Type} 含 {@code application/json}
     *       才检查；否则 B 侧根本不读 body；</li>
     *   <li>body <b>不是合法 JSON</b> ⇒ 5001；</li>
     *   <li>body 合法但<b>顶层不是对象</b>（{@code null} / 数组 / 数字 / 字符串）⇒ 5001；</li>
     *   <li>body 是<b>对象</b> ⇒ 通过（字段被忽略）。</li>
     * </ul>
     *
     * <p>⚠️ <b>不</b>开 {@code FAIL_ON_TRAILING_TOKENS}：B 侧只读一个 JSON 值、<b>不</b>拒绝尾随内容
     * ⇒ {@code {"a":1} garbage} 在 B 侧是<b>通过</b>的。
     *
     * <p>⚠️ <b>8 MiB 上限</b>：共享层 {@link JsonBodyConverter#goLimitedBody(InputStream)}
     * 只读前 8 MiB ⇒ 一个 &gt; 8 MiB 的 JSON body 会被<b>静默截断</b> ⇒ 解析器拿到半个文档
     * ⇒ 判定失败 ⇒ 5001 ⇒ 与 B 侧同形。
     *
     * @param request 当前请求
     */
    public static void requireParsableJsonBody(HttpServletRequest request) {
        // 判据调用共享层的唯一真源，不在本地抄一份（复制品必然与实现漂移）。
        if (!JsonBodyConverterConfig.goReadsBody(request)) {
            return;
        }
        JsonNode node;
        try (InputStream in = JsonBodyConverter.goLimitedBody(request.getInputStream())) {
            // 流式读与 B 侧同形（Go 的 json.Decoder 只读它需要的那个 JSON 值）。
            node = JSON_SYNTAX_CHECKER.readTree(in);
        } catch (IOException e) {
            throw parseFailure(new IllegalArgumentException("read json body failed: " + e.getMessage(), e));
        } catch (Exception e) {
            // 这一支同时覆盖「被 8 MiB 截断后的半个文档」⇒ 与 B 侧同一条拒绝路径。
            throw parseFailure(new IllegalArgumentException("json body parse failed: " + e.getMessage(), e));
        }
        if (node == null || !node.isObject()) {
            throw parseFailure(new IllegalStateException("json body must be an object"));
        }
    }

    /**
     * 构造 B 语义的解析失败（B 侧 {@code GoFormValues.parseFailure}，:321-326）。
     *
     * <p>⚠️ 这类错误<b>不是</b> {@code *hotcommon.CodeError} ⇒ B 侧的
     * {@code SetErrorHandler} 取不到 code ⇒ 恒 {@code 5001 + "internal server error"}，HTTP <b>200</b>。
     *
     * @param cause 细节（只进日志，不进 wire）
     * @return 携带 5001 + 本域文案的业务异常
     */
    private static BusinessException parseFailure(Throwable cause) {
        int code = ErrCodes.ERROR_SERVER_COMMON.getCode();
        BusinessException failure = new BusinessException(code, ErrMsg.getMsg(code));
        failure.initCause(cause);
        return failure;
    }

    // ==================== 分页数值契约（B 侧 HotRankPaginator） ====================

    /**
     * B 侧 {@code normalizePagination(req)}（:76-94）的等价物。
     *
     * <p><b>语义还一样吗</b>：一样 ——
     * <ul>
     *   <li>{@code page <= 0 → 1}、{@code pageSize <= 0 → 20}；</li>
     *   <li>{@code pageSize > 50 → 50}；</li>
     *   <li>入参为 {@code null} 时按默认值处理（B 侧函数体里有 {@code if req != nil} 分支）。</li>
     * </ul>
     *
     * @param req 分页入参（可为 {@code null}）
     * @return 长度为 2 的数组 {@code [page, pageSize]}
     */
    public static int[] normalize(HotArticlesReq req) {
        int page = DEFAULT_PAGE;
        int pageSize = DEFAULT_PAGE_SIZE;

        if (req != null) {
            if (req.page() > 0) {
                page = req.page();
            }
            if (req.pageSize() > 0) {
                pageSize = req.pageSize();
            }
        }

        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }

        return new int[]{page, pageSize};
    }

    /**
     * B 侧 {@code paginate(total, page, pageSize)}（:104-120）。
     *
     * <p>⚠️ {@code start} 用 <b>int32 乘法</b>：{@code (page - 1) * pageSize}，
     * 溢出后为负 ⇒ 空页。<b>不要</b>把参数提升成 {@code long}（那会悄悄改掉这个可观察行为）。
     *
     * @param total    过滤后的条目数
     * @param page     页码（从 1 起）
     * @param pageSize 每页条数
     * @return 长度为 2 的数组 {@code [start, end]}；越界时为 {@code [0, 0]}
     */
    public static int[] paginate(long total, int page, int pageSize) {
        if (total <= 0) {
            return new int[]{0, 0};
        }

        int start = (page - 1) * pageSize;
        if (start < 0 || start >= total) {
            return new int[]{0, 0};
        }

        int end = start + pageSize;
        if (end > total) {
            end = (int) total;
        }

        return new int[]{start, end};
    }

    /**
     * B 侧 {@code hydrateArticles} 末段的 rank 重算 + 「过滤 nil 后按下标保序」
     * （B 侧 {@code HotRankPaginator.assignRanks}，:139-149）。
     *
     * <p>⚠️ <b>rank 是过滤后的稠密序号，且跨页连续</b>：rank 在<b>分页之前</b>按
     * 「过滤后的下标 + 1」重算 ⇒ 第 2 页第一条的 rank 是 <b>2</b> 而不是 1。
     *
     * <p><b>⚠️ record 是不可变的</b>（D5 + A9）⇒ 本方法<b>不</b>能就地 {@code setRank}
     * ⇒ 改为<b>产出新的 record</b>（其余 12 个分量原样搬运）⇒
     * <b>可观察结果逐条相同</b>（B 侧是就地改字段，改的是同一批值）。
     *
     * @param hydrated 补全后的列表；元素为 {@code null} 表示该条被 skip
     * @return 稠密 rank 后的列表；入参全为 {@code null} 时返回<b>空列表</b>（不是 {@code null}）
     */
    public static List<HotArticleItem> assignRanks(List<HotArticleItem> hydrated) {
        List<HotArticleItem> result = new ArrayList<>(hydrated.size());
        for (HotArticleItem item : hydrated) {
            if (item == null) {
                continue;
            }
            result.add(withRank(item, result.size() + 1));
        }
        return result;
    }

    /**
     * 产出带 rank 的新 record（其余分量原样搬运）。
     *
     * @param item 原卡片
     * @param rank 新的稠密序号
     * @return 带指定 rank 的新卡片
     */
    private static HotArticleItem withRank(HotArticleItem item, int rank) {
        return new HotArticleItem(item.articleId(), rank, item.hotScore(), item.title(), item.brief(),
                item.coverImageUrl(), item.authorId(), item.createTime(), item.viewCount(),
                item.likeCount(), item.commentCount(), item.manualTypeTag(), item.secondaryTags());
    }

    /**
     * 切片并组装响应 —— 对应 B 侧 {@code getHotArticlesLogic.go:78-88} 的主干尾部
     * （B 侧 {@code HotRankPaginator.slice}，:157-167）。
     *
     * <p>⚠️ 返回的 {@code items} <b>永不为 null</b>：B 侧的切片在空页时是非 nil 的空切片
     * ⇒ JSON 是 {@code []}。
     *
     * @param ranked   已分配 rank 的列表
     * @param page     页码
     * @param pageSize 每页条数
     * @return 组装好的响应
     */
    public static HotArticlesResp slice(List<HotArticleItem> ranked, int page, int pageSize) {
        long total = ranked.size();
        int[] range = paginate(total, page, pageSize);
        List<HotArticleItem> paged;
        if (range[0] == range[1]) {
            paged = Collections.emptyList();
        } else {
            paged = new ArrayList<>(ranked.subList(range[0], range[1]));
        }
        return new HotArticlesResp(paged, total, page, pageSize, ROLLING_SCOPE);
    }
}
