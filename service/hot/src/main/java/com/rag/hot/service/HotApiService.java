package com.rag.hot.service;

import com.rag.proto.article.Article;
import com.rag.proto.article.ArticleStatus;
import com.rag.proto.article.GetArticleResponse;
import com.rag.common.logger.LogOption;
import com.rag.common.logger.LoggerFactoryBean;
import com.rag.common.observability.GoStyleRequestDeadline;
import com.rag.hot.client.ArticleRpcClient;
import com.rag.hot.contract.CodeError;
import com.rag.hot.contract.ErrCodes;
import com.rag.hot.contract.ErrMsg;
import com.rag.hot.dto.article.HotArticleItem;
import com.rag.hot.dto.article.HotArticlesReq;
import com.rag.hot.dto.article.HotArticlesResp;
import com.rag.hot.metrics.HotApiMetrics;
import io.opentelemetry.context.Context;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * <p><b>唯一业务用例的编排 + 并发补全</b> —— 对应 B 侧两个类：
 * {@code service/hot/hot-api/.../api/service/HotArticleQueryService.java}（编排）
 * ＋ {@code .../api/service/HotArticleHydrator.java}（并发补全）。
 *
 * <p><b>B 出处</b>：
 * <pre>
 *   B 侧 hot-api/.../api/service/HotArticleQueryService.java
 *       类声明 + 4 个依赖                                        :62-84
 *       getHotArticles(req) 主干                                :84-118
 *         normalizePagination(req)                             :88
 *         remainingBudgetMs / deadlineNanos                    :102-105
 *         fetchTopK(remainingBudgetMs)                         :107
 *         hydrator.hydrate(hotResp.GetItems(), deadlineNanos)  :109
 *         HotRankPaginator.assignRanks(hydrated)               :110
 *         HotRankPaginator.slice(ranked, page, pageSize)       :112
 *         finally ⇒ metrics.observeRequest(...)                :115-117
 *       fetchTopK(...)（两条 fatal 分支）                       :127-142
 *       fatal(cause) ⇒ HotCodeError + 业务错误日志              :176-197
 *
 *   B 侧 hot-api/.../api/service/HotArticleHydrator.java
 *       HYDRATION_CONCURRENCY = 8                              :69
 *       hydrate(hotItems, deadlineNanos)                       :96-141
 *       buildHotArticleItem(hotItem, deadlineNanos)（4 条 skip） :149-218
 *       remainingBudgetMs(deadlineNanos)                       :261-272
 *       logQuietly(action)                                      :279-285
 *       nonNull(futures)                                        :291-301
 * </pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：五处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>B 侧 2 个类合并为 1 个</b>（映射表 hot.md §1.2 的「1:N · B 侧 3 类 → C 2 类」口径：
 *         编排 + 补全归本类，分页/输入解释归 {@link HotApiSupport}）；</li>
 *         <li><b>域内调用改直连</b>（<b>D2</b>）：B 侧的 {@code HotRpcClient}（自我 gRPC 往返）
 *             ⇒ 本类<b>直接注入</b> {@link HotRankingQueryService} ⇒ <b>不再有</b>
 *             {@code HotRpcClient} 这个类；</li>
 *         <li><b>proto 包名</b>：{@code com.rag.hot.hot.proto} → {@code com.rag.proto.hot}、
 *             {@code com.rag.article.article.proto} → {@code com.rag.proto.article}（D7）；</li>
 *         <li><b>DTO 改 immutable record</b>（D5 + A9）⇒ 补全结果不可就地改 ⇒
 *             rank 由 {@link HotApiSupport#assignRanks} 产出新 record；</li>
 *         <li><b>日志选项</b>：B 侧的 {@code WithArticleID.of(id)} ⇒ 共享层的
 *             {@link LogOption#withArticleId(String)}（共享层 D3 已把两个闭包工厂合并成一个接口）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>主干顺序逐条未动（归一化 → 取 TopK → 补全 → 排名 → 切片 → finally 记指标）；</li>
 *         <li><b>fatal 与 best-effort 的不对称</b>逐条保留（见下方「⚠️ 最核心的一条」专段）；</li>
 *         <li>4 条 skip 分支 + 各自的 metric 逐条未动；</li>
 *         <li>并发窗口 = 8（每请求一个 {@link Semaphore}）+ <b>先 acquire 再 submit</b> 逐条未动。</li>
 *       </ul>
 *       ⚠️ <b>一处传输层差异（已登记 · DRIFT 台账 F-02）</b>：B 侧的 hot-rpc 走 gRPC ⇒ 失败是
 *       {@code StatusRuntimeException}、且带 <b>2s 客户端上界</b>；本仓走直连 ⇒ 失败是
 *       {@code DataAccessException} 等 {@link RuntimeException}（<b>已把 catch 放宽到
 *       {@code RuntimeException}</b> ⇒ fatal 语义不变），但<b>少了一层 2s per-call 上界</b>
 *       ⇒ ⇒ <b>这次 Redis 调用没有任何出向上界</b>（D2 域内直连的必然结果）。
 *       <br>⚠️ <b>HTTP 层的请求预算并不约束它</b>：请求预算约束的是「整个 HTTP 响应何时返回」，
 *       <b>不是</b>「这一次 Redis 调用何时返回」⇒ ⇒ Redis 卡住时两侧的<b>失败形状不同</b>
 *       （双侧活体对拍实测 · DRIFT 台账 §九·M5）：
 *       <ul>
 *         <li>B 侧：{@code HTTP/1.1 200} ＋ {@code {"code":5001,…}}（<b>业务信封</b>）@2011ms
 *             —— 2s 的 gRPC 出向封顶先到期 ⇒ 走 fatal 分支；</li>
 *         <li>C 侧：{@code HTTP/1.1 503} ＋ {@code text/plain} 的 {@code "Request Timeout\n"}
 *             @3014ms，<b>且形状非确定</b>（三候选，含 {@code 200 + application/json}
 *             但 body 是纯文本的「混血」形态 ⇒ 共享层缺陷 F-15）
 *             —— <b>不是</b>业务信封 ⇒ 客户端按信封解会解析失败。</li>
 *       </ul>
 *       ⇒ ⇒ <b>不要</b>把这条读成「还有 HTTP 预算兜底」：<b>没有兜底，只有更晚 ＋ 形状不同</b>。
 *       （F-02 是 D2 的已批准连带后果 ⇒ 不修；恢复域内方向 2s 上界是唯一能<b>同时</b>
 *       消掉时延差与混血形状的修法。）</li>
 *   <li><b>B 出处</b>：见上方逐条行号。</li>
 * </ol>
 *
 * <p><b>⚠️ 本类最核心的一条：fatal 与 best-effort 的不对称</b>：
 * 同一个「下游失败」概念，两层方向<b>相反</b>：
 * <table border="1">
 *   <caption>两层失败语义（不可统一）</caption>
 *   <tr><th>层</th><th>B 侧分支</th><th>结果</th><th>HTTP</th></tr>
 *   <tr><td>hot-rpc</td><td>{@code :65-69}</td><td><b>fatal</b>：整包 5001</td>
 *       <td>200 + {@code {code:5001,data:null}}</td></tr>
 *   <tr><td>article-rpc</td><td>{@code :137-142}（在 Hydrator 里）</td>
 *       <td><b>best-effort</b>：跳过该条，其余照常返回</td><td>200 + 正常信封</td></tr>
 * </table>
 * 把二者统一（例如都抛异常）会让「article-rpc 抖动一次」从「少一条」变成「整包失败」——
 * 这是<b>可用性</b>级别的漂移，不只是错误码。
 */
@Service
public class HotApiService {

    /**
     * B 侧 {@code getHotArticlesLogic.go:29} {@code hydrationConcurrency = 8}。
     */
    public static final int HYDRATION_CONCURRENCY = 8;

    private final HotRankingQueryService rankingQuery;
    private final ArticleRpcClient articleRpcClient;
    private final HotApiMetrics metrics;
    private final LoggerFactoryBean logger;
    private final Executor hydrationExecutor;

    public HotApiService(HotRankingQueryService rankingQuery,
                         ArticleRpcClient articleRpcClient,
                         HotApiMetrics metrics,
                         LoggerFactoryBean logger,
                         @Qualifier("hotHydrationExecutor") Executor hydrationExecutor) {
        this.rankingQuery = rankingQuery;
        this.articleRpcClient = articleRpcClient;
        this.metrics = metrics;
        this.logger = logger;
        this.hydrationExecutor = hydrationExecutor;
    }

    /**
     * 对应 B 侧 {@code GetHotArticles}（B 侧 {@code getHotArticlesLogic.go:84-118}）。
     *
     * @param req 分页入参
     * @return 热门文章列表响应
     * @throws com.rag.common.exception.BusinessException fatal 分支 → 5001（HTTP 仍为 200）
     */
    public HotArticlesResp getHotArticles(HotArticlesReq req) {
        long startedNanos = System.nanoTime();
        boolean success = false;
        try {
            int[] pageAndSize = HotApiSupport.normalize(req);
            int page = pageAndSize[0];
            int pageSize = pageAndSize[1];

            // 剩余预算在**请求线程**上取一次，显式传给下游 ——
            // B 侧是 ctx 被 100 个 goroutine 隐式共享（B 侧 getHotArticlesLogic.go:128）。
            //
            // ⚠️ **取的是「绝对 deadline」而不是「剩余毫秒快照」**：B 侧的 ctx 上挂的是**绝对** deadline，
            // 每个补全在**自己的发起时刻**重新算 min(ctx.deadline, now+2s)；
            // 若这里只传「剩余毫秒的快照」，后发的补全会拿到**全新的 2s**、
            // **不被请求 deadline 卡住** ⇒ 两侧最后一批补全的死亡时刻会不同
            // ⇒ 连带 client breaker 的失败落进不同时间桶 ⇒ 熔断打开时机不同。
            long remainingBudgetMs = GoStyleRequestDeadline.remainingMillis();
            long deadlineNanos = remainingBudgetMs < 0L
                    ? -1L
                    : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingBudgetMs);

            List<com.rag.proto.hot.HotArticleItem> hotItems = fetchTopK();

            List<HotArticleItem> hydrated = hydrate(hotItems, deadlineNanos);
            List<HotArticleItem> ranked = HotApiSupport.assignRanks(hydrated);

            HotArticlesResp resp = HotApiSupport.slice(ranked, page, pageSize);
            success = true;
            return resp;
        } finally {
            metrics.observeRequest(HotApiMetrics.ROUTE, startedNanos, success);
        }
    }

    /**
     * B 侧 {@code :62-75} 的两条 fatal 分支。
     *
     * <p>⚠️ {@code TopK} 是<b>硬编码</b> {@link HotApiSupport#MAX_HOT_ARTICLES}（B 侧 :63），
     * 请求里的 {@code page} / {@code page_size} 影响不到它 ——
     * 窗口永远是 100 条，分页只在这 100 条的过滤结果上切。
     *
     * <p>⚠️ <b>catch 放宽到 {@link RuntimeException}</b>（B 侧只 catch {@code StatusRuntimeException}）：
     * B 侧这一层走 gRPC ⇒ 失败是 status 异常；本仓走<b>直连</b>（D2）⇒ 失败是
     * {@code DataAccessException} 等普通运行时异常 ⇒ 只捕 status 异常会让「Redis 挂了」
     * 逃出 fatal 判定 ⇒ 变成兜底 5001（<b>虽然 wire 形状相同</b>，但
     * {@code hot_api_rpc_error_total{callee="hot_rpc_get_hot_articles"}} 这个指标会丢）
     * ⇒ ⇒ 放宽 catch 才是<b>保持 B 侧指标语义</b>的写法。
     *
     * @return hot-rpc 的 TopK 条目
     */
    private List<com.rag.proto.hot.HotArticleItem> fetchTopK() {
        List<com.rag.proto.hot.HotArticleItem> items;
        try {
            items = rankingQuery.getHotArticles(HotApiSupport.MAX_HOT_ARTICLES);
        } catch (RuntimeException e) {
            metrics.observeRpcError(HotApiMetrics.CALLEE_HOT_RPC);
            throw fatal(e);
        }

        // B 侧 :70-75 —— 防御性分支：响应指针为 nil。
        // protobuf-java 同样不返回 null，因此 null 在 Java 侧只可能来自 mock / 旁路实现。
        // **仍然保留该分支**：它带着一个非空的 metric 副作用
        // （hot_api_article_skip_total{reason="hot_rpc_nil_response"}），
        // 删掉会让「这段防御曾存在过」这一事实从指标侧消失。
        if (items == null) {
            metrics.observeArticleSkip(HotApiMetrics.SKIP_HOT_RPC_NIL_RESPONSE);
            throw fatal(null);
        }
        return items;
    }

    /**
     * 对 hot-rpc 返回的 TopK 做并发补全 —— 对应 B 侧 {@code hydrateArticles}
     * （B 侧 {@code HotArticleHydrator.java:96-141}）。
     *
     * <p><b>并发模型：Go 的 goroutine + channel semaphore 的 Java 等价物</b>：
     * B 侧为 100 个 item <b>各起一个 goroutine</b>，用容量 8 的 channel 做信号量。
     * Java 侧的对应做法：<b>共享线程池 + 每请求一个 {@link Semaphore}(8)</b>，
     * 且<b>先 acquire 再 submit</b>：
     * <pre>
     *   for (i = 0; i &lt; n; i++) {
     *       gate.acquire();                      // ← 在途任务数被本请求的窗口卡住
     *       futures[i] = pool.submit(() -&gt; { try { ... } finally { gate.release(); } });
     *   }
     * </pre>
     * 为什么是「每请求一个 semaphore」而不是「全局 8 线程的池」：
     * B 侧的信号量是<b>每次调用新建</b>的 ⇒ 并发请求之间<b>互不占用</b>，
     * 每个请求都享有完整的 8 并发。
     *
     * <p><b>⚠️ 结果必须按下标保序</b>：B 侧写的是 {@code hydrated[idx] = ...} ——
     * 结果落在<b>输入下标</b>上，与 goroutine 的完成顺序无关。Java 侧若用「先完成的先 add」，
     * 同样 4 条被 skip 的输入会产出不同的 rank 分配 ⇒ 可观察差异。
     *
     * @param hotItems      hot-rpc 的 TopK 条目；元素可为 {@code null}
     * @param deadlineNanos 请求的<b>绝对</b> deadline（{@link System#nanoTime()} 时基）；
     *                      {@code -1} 表示本次请求<b>没有</b> HTTP 超时预算
     * @return 与输入<b>等长</b>的列表，元素为 {@code null} 表示该条被 skip；
     *         rank <b>尚未</b>计算（由 {@link HotApiSupport#assignRanks} 统一做）
     */
    public List<HotArticleItem> hydrate(List<com.rag.proto.hot.HotArticleItem> hotItems, long deadlineNanos) {
        if (hotItems == null || hotItems.isEmpty()) {
            return new ArrayList<>();
        }

        int n = hotItems.size();
        HotArticleItem[] hydrated = new HotArticleItem[n];
        Semaphore gate = new Semaphore(HYDRATION_CONCURRENCY);

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final com.rag.proto.hot.HotArticleItem hotItem = hotItems.get(i);
            try {
                gate.acquire();
            } catch (InterruptedException e) {
                // B 侧对应情形是 ctx 被取消；此处保留中断标志并把剩余位置补 null，
                // 让本请求按「部分补全」结束而不是整体失败。
                Thread.currentThread().interrupt();
                break;
            }
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    hydrated[idx] = buildHotArticleItem(hotItem, deadlineNanos);
                } finally {
                    gate.release();
                }
            }, hydrationExecutor);
        }

        try {
            CompletableFuture.allOf(nonNull(futures)).join();
        } catch (RuntimeException e) {
            // B 侧的 wg.Wait() 不会失败（每个 goroutine 内部自己吞掉了所有错误）。
            // Java 侧理论上到不了这里（任务体不抛），但真到了也不能让整包 500：
            // best-effort 的语义是「补不全也给结果」，不是「补全失败就报错」。
            Thread.currentThread().interrupt();
        }

        List<HotArticleItem> ordered = new ArrayList<>(n);
        for (HotArticleItem card : hydrated) {
            ordered.add(card);
        }
        return ordered;
    }

    /**
     * 单条补全 —— 对应 B 侧 {@code buildHotArticleItem}
     * （B 侧 {@code HotArticleHydrator.java:149-218}）。
     *
     * @param hotItem       hot-rpc 的条目
     * @param deadlineNanos 请求的<b>绝对</b> deadline；{@code -1} = 无预算
     * @return 成功时的卡片；<b>任何</b> skip 分支都返回 {@code null}
     */
    HotArticleItem buildHotArticleItem(com.rag.proto.hot.HotArticleItem hotItem, long deadlineNanos) {
        // 分支 ①：B 侧 :127-130 —— nil item 或空 article_id
        if (hotItem == null || hotItem.getArticleId().isEmpty()) {
            metrics.observeArticleSkip(HotApiMetrics.SKIP_EMPTY_ARTICLE_ID);
            return null;
        }

        GetArticleResponse articleResp;
        try {
            articleResp = articleRpcClient.getPublicArticle(
                    hotItem.getArticleId(), remainingBudgetMs(deadlineNanos));
        } catch (RuntimeException e) {
            // 分支 ②：B 侧 :137-142 —— RPC 报错。skip 该条 + 两条 metric + 业务错误日志。
            // ⚠️ 捕获 **RuntimeException** 而不是只捕 StatusRuntimeException：
            //    B 侧的判据是 `rpcErr != nil`（client 返回的任何 error 都算），
            //    只捕 grpc 状态异常会让「超时 / 被 breaker 拒绝 / 序列化失败」等
            //    非 StatusRuntimeException 的失败直接冒泡，把 best-effort 变成整包 500。
            metrics.observeRpcError(HotApiMetrics.CALLEE_ARTICLE_RPC);
            metrics.observeArticleSkip(HotApiMetrics.SKIP_ARTICLE_RPC_ERROR);
            logQuietly(() -> logger.logBusinessErr(Context.current(),
                    ErrCodes.ERROR_SERVER_COMMON.getCode(), e,
                    LogOption.withArticleId(hotItem.getArticleId())));
            return null;
        }

        // 分支 ③：B 侧 :143-147 —— resp 或 article 为 nil
        if (articleResp == null || !articleResp.hasArticle()) {
            metrics.observeArticleSkip(HotApiMetrics.SKIP_ARTICLE_MISSING);
            logQuietly(() -> logger.logInfo(Context.current(),
                    "skip hot article because article detail is missing",
                    LogOption.withArticleId(hotItem.getArticleId())));
            return null;
        }

        Article article = articleResp.getArticle();
        // 分支 ④：B 侧 :150-154 —— 状态不是 PUBLISHED
        // ⚠️ 这里读的是 article-rpc **公开投影**（public_only=true 的 frozen revision）的状态，
        //    不是可变源行。源行在 REVIEWING 时公开投影仍是 PUBLISHED，
        //    因此改稿中的文章不会从热榜里消失。
        if (article.getStatus() != ArticleStatus.PUBLISHED) {
            metrics.observeArticleSkip(HotApiMetrics.SKIP_ARTICLE_NOT_PUBLISHED);
            logQuietly(() -> logger.logInfo(Context.current(),
                    "skip hot article because article is not published",
                    LogOption.withArticleId(article.getId())));
            return null;
        }

        // 成功：HotScore 取自 hot-rpc（B 侧 :158），其余 11 个字段取自 article 公开投影。
        // ⚠️ ArticleId 取的是 article.GetId()（B 侧 :157），不是 hotItem.GetArticleId()。
        // ⚠️ Go 的类型是 uint32，JSON 写无符号十进制；protobuf-java 把它映射成有符号 int
        // ⇒ 必须转回无符号，否则 ≥2^31 的热度分会写成负数。
        // ⚠️ Go 侧 proto3 repeated 字段在无元素时是 nil slice ⇒ JSON 输出 null 而不是 []；
        //    protobuf-java 永远返回非 null 列表，必须显式还原成 null。
        List<String> secondaryTags = article.getSecondaryTagsCount() == 0
                ? null
                : new ArrayList<>(article.getSecondaryTagsList());
        return new HotArticleItem(
                article.getId(),
                0,
                Integer.toUnsignedLong(hotItem.getHotScore()),
                article.getTitle(),
                article.getBrief(),
                article.getCoverImageUrl(),
                article.getAuthorId(),
                article.getCreateTime(),
                article.getViewCount(),
                article.getLikeCount(),
                article.getCommentCount(),
                article.getManualTypeTag(),
                secondaryTags);
    }

    /**
     * 把请求的<b>绝对</b> deadline 折算成「这一次调用还剩多少毫秒」
     * （B 侧 {@code HotArticleHydrator.remainingBudgetMs}，:261-272）。
     *
     * <p><b>语义还一样吗</b>：一样 ——
     * <ul>
     *   <li>无 deadline（{@code -1}）⇒ 返回 {@code -1}（对应「ctx 上没有 deadline」⇒ 只剩客户端上界）；</li>
     *   <li>预算已耗尽 ⇒ 返回 {@code 0}（对应「ctx 已到期 ⇒ 下游调用立即失败」）；</li>
     *   <li>否则按<b>向下取整</b>的毫秒数折算。</li>
     * </ul>
     *
     * @param deadlineNanos 请求的绝对 deadline（nanoTime 时基）；{@code -1} = 无预算
     * @return 剩余毫秒；{@code -1} = 无预算；{@code 0} = 已耗尽
     */
    static long remainingBudgetMs(long deadlineNanos) {
        if (deadlineNanos < 0L) {
            // 无 HTTP 超时预算 ⇒ 对应「ctx 上没有 deadline」⇒ 只剩客户端上界
            return -1L;
        }
        long remainNanos = deadlineNanos - System.nanoTime();
        if (remainNanos <= 0L) {
            // 预算已耗尽 ⇒ B 侧 ctx 已到期，下游调用**立即失败**
            return 0L;
        }
        return TimeUnit.NANOSECONDS.toMillis(remainNanos);
    }

    /**
     * B 侧 {@code hotcommon.NewErrCode(hotcommon.ErrorServerCommon)} 的等价物
     * （B 侧 {@code HotArticleQueryService.fatal}，:176-197）。
     *
     * <p>⚠️ <b>为什么返回 {@link CodeError} 而不是普通 {@code BusinessException}</b>：
     * B 侧的两条 fatal 分支<b>记的日志对象不同</b>（B 侧 :65-74）——
     * hot-rpc 报错时记的是<b>下游 gRPC error 本身</b>，
     * 而「hot-rpc 返回空响应」时记的是 {@code *CodeError} ⇒
     * <b>只有后一条</b>的日志 {@code error_reason} 是
     * {@code "ErrCode:5001, Errmsg:internal server error"} 整串。
     * ⇒ 该分支必须用 {@link CodeError}（它的 {@code getMessage()} 就是这串）。
     *
     * <p>⚠️ <b>HTTP 信封不受影响</b>：信封走 {@code HotApiExceptionHandler.bizMsgOf}
     * ⇒ 取 {@link CodeError#getBizMsg()} 的<b>纯文案</b>，与 B 侧的逐字段复制一致。
     *
     * @param cause 触发 fatal 的异常（{@code null} = 「hot-rpc 返回空响应」那条分支）
     * @return 携带 5001 + 本域文案的业务异常
     */
    private com.rag.common.exception.BusinessException fatal(Throwable cause) {
        int code = ErrCodes.ERROR_SERVER_COMMON.getCode();
        CodeError err = new CodeError(code, ErrMsg.getMsg(code));
        // B 侧 :67/:73 都会记一条**业务错误日志**；日志失败不得改变响应。
        try {
            if (cause != null) {
                // B 侧 :67 —— 记的是**下游错误本身**（不是 CodeError）。
                logger.logBusinessErr(Context.current(), code, cause);
            } else {
                // B 侧 :72-73 —— 记 CodeError ⇒ error_reason 必须逐字节等于 (*CodeError).Error()
                logger.logBusinessErr(Context.current(), code, err);
            }
        } catch (RuntimeException loggingFailure) {
            // 刻意吞掉
        }
        return err;
    }

    /**
     * 日志失败不得改变响应 —— B 侧的 logger 不会让请求失败
     * （B 侧 {@code HotArticleHydrator.logQuietly}，:279-285）。
     */
    private void logQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException loggingFailure) {
            // 刻意吞掉
        }
    }

    /**
     * 剔除中断后未提交的空槽 —— {@code CompletableFuture.allOf} 不接受 null 元素
     * （B 侧 {@code HotArticleHydrator.nonNull}，:291-301）。
     * 被剔除的位置在 {@code hydrated} 里保持 null，即「该条被 skip」，与 best-effort 语义一致。
     */
    private static CompletableFuture<Void>[] nonNull(CompletableFuture<Void>[] futures) {
        List<CompletableFuture<Void>> live = new ArrayList<>(futures.length);
        for (CompletableFuture<Void> future : futures) {
            if (future != null) {
                live.add(future);
            }
        }
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] result = new CompletableFuture[live.size()];
        return live.toArray(result);
    }
}
