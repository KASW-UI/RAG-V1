package com.rag.favorite.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * <p><b>favorite 域的观测契约</b> —— <b>Micrometer 指标</b> ＋ <b>手写 otel span</b>。
 *
 * <p><b>B 出处（两个类合并 · 映射表 §1.8）</b>：
 * <ul>
 *   <li>指标：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/metrics/FavoriteRpcMetrics.java}
 *       （B 侧 :19-131）</li>
 *   <li>span：{@code service/favorite/favorite-common/src/main/java/com/rag/favorite/favorite/common/span/FavoriteSpans.java}
 *       （B 侧 :30-418）—— B 侧把它单独放在 {@code favorite-common} 的 {@code span/} 包，
 *       C 侧按映射表 §1.8「并回 {@code FavoriteRpcMetrics}」合并到本类。</li>
 * </ul>
 *
 * <p><b>L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.favorite.favorite.rpc.metrics.FavoriteRpcMetrics}
 * ⇒ C 侧 {@code com.rag.favorite.metrics.FavoriteRpcMetrics}。
 *
 * <h2>一、指标：4 个 {@code Counter}</h2>
 *
 * <table border="1">
 *   <caption>Go ↔ Micrometer 对照</caption>
 *   <tr><th>Go（metrics.go）</th><th>Micrometer 名</th><th>labels</th></tr>
 *   <tr><td>{@code FavoriteRequestCounterMetric}（:14-19）</td>
 *       <td>{@code favorite_request_total}</td><td>{@code module / action / result}</td></tr>
 *   <tr><td>{@code FavoriteRequestSecondsCounterMetric}（:22-27）</td>
 *       <td>{@code favorite_request_seconds_counter}</td><td>{@code module / action}</td></tr>
 *   <tr><td>{@code FavoriteOpsCounterMetric}（:30-35）</td>
 *       <td>{@code favorite_ops_total}</td><td>{@code module / action / result}</td></tr>
 *   <tr><td>{@code FavoriteDBErrorCounterMetric}（:38-43）</td>
 *       <td>{@code favorite_db_error_total}</td><td>{@code module / action / type}</td></tr>
 * </table>
 *
 * <p>⚠️ <b>Go 的 {@code InitMetrics}（:46-54）不迁移</b>：它先
 * {@code prometheus.Unregister(collectors.NewGoCollector())} 再注册自己的 4 个。
 * 「注销 JVM collector」是 Go client 的行为，Micrometer 无对应物 ⇒ 不实现。
 *
 * <h3>🔴 三处必须保留的怪癖（修正即指标契约变更）</h3>
 *
 * <p><b>① {@code favorite_request_seconds_counter} 是 Counter 不是 Histogram。</b>
 * Go 用 {@code CounterVec} + {@code Add(time.Since(started).Seconds())}（:63）。
 * Micrometer 的 {@code Counter} 在 Prometheus 侧会<b>自动追加 {@code _total}</b>
 * ⇒ 输出名变成 {@code favorite_request_seconds_counter_total}。
 * <b>这是已知的指标面差异，不改</b>：把它换成 Timer 会让「累计耗时」的语义变成「分布」，
 * 那是<b>另一个</b>指标而不是同一个指标的修正。
 *
 * <p><b>② 🔴 Go 侧<b>没有</b>「错误被记成 success」这回事 —— 2026-10-25 实测证伪。</b>
 * 每个 logic 头部是 {@code defer func(){ metrics.ObserveRPC(module, action, started, err) }}()}，
 * 读的是<b>命名返回值</b> {@code err}；而错误分支写成
 * {@code return nil, favoritecommon.GRPCError(...)} ——
 * ⚠️ <b>Go 的 return 语句会把返回值赋给对应的命名返回值</b>
 * ⇒ {@code err} <b>非 nil</b> ⇒ {@code ObserveRPC} 记的是 <b>{@code fail}</b>，不是 {@code success}。
 * ⇒ <b>Go 与 RAG 在这一点上本来就一致</b>（都按真实结果记 success/fail），
 * <b>不存在「有意变更」，也不需要签字</b>。
 * <p>⚠️ <b>真正需要守的不是「Go 的缺陷」，而是「RAG 侧别漏设」</b>：
 * Java 没有「命名返回值 + defer」这套组合，RAG 侧靠 try/catch/finally +
 * 每个失败分支显式 {@code result = RESULT_FAIL} 来达成同一语义
 * ⇒ <b>新增失败分支时漏设 RESULT_FAIL，指标就会把失败记成 success</b>。
 *
 * <p><b>③ 两处 {@code module} label：B 侧是对的，本实现与 B 侧一致</b>：
 * {@code create_favorite_logic.go:89} 与 {@code get_favorites_by_folder_logic.go:68}
 * 这两处都紧跟在 <b>{@code FindFolderByFolderId}（收藏夹查询）</b>的失败分支之后
 * ⇒ 记 {@code folderModule} <b>正确</b>；
 * 而<b>收藏条目</b>的读失败分支（{@code get_favorites_by_folder_logic.go:84}、
 * {@code create_favorite_logic.go:130}）记 {@code itemModule}。
 *
 * <h2>二、span：16 个手写 span（api 7 + rpc 7 + 依赖 2）</h2>
 *
 * <p>Go 真源在 favorite 域<b>手写了 16 个 span</b>：<b>api 侧 7 个</b> ＋ <b>rpc 侧 7 个</b> ＋
 * <b>rpc 侧 2 个依赖 span</b>，共用 <b>7 个属性 key</b>。
 *
 * <h3>🔴 属性名全集只有 7 个（B 侧是<b>硬编码字面量</b>，没有集中常量表）</h3>
 * <p>{@code biz.user_id}(Int64) · {@code biz.folder_id}(Int64) · {@code biz.favorite_id}(Int64) ·
 * {@code biz.folder_name}(String) · {@code biz.target_id}(String) ·
 * {@code biz.target_type}(String) · {@code biz.article_id}(String)
 * <br>⚠️ <b>注意本域用 {@code biz.*} 前缀</b>（不是共享层的 {@code sea.*}）。
 *
 * <h3>🔴 三处「B 侧的取值陷阱」必须逐字复刻，<b>不许顺手修正</b></h3>
 * <ol>
 *   <li><b>{@code biz.folder_name} 记的是<b>未 trim 的原值</b></b>
 *       （api 侧 {@code req.Name} / rpc 侧 {@code in.GetName()}），
 *       而真正落库用的是 {@code normalizeFolderName(...)}（trim 后）
 *       ⇒ ⇒ <b>span 记的值 ≠ 落库的值</b>。</li>
 *   <li><b>{@code biz.target_type} 记的是<b>未规范化（原样大小写）的原值</b></b>
 *       （{@code in.GetTargetType()}），而落库用 {@code normalizeTargetType(...)}（lower + trim）
 *       ⇒ ⇒ 同上。</li>
 *   <li><b>{@code biz.target_id} 两端都已 trim</b>
 *       （api 侧 {@code strings.TrimSpace(req.TargetId)} / rpc 侧 {@code strings.TrimSpace(in.GetTargetId())}）
 *       ⇒ ⇒ <b>只有它被 trim</b>，与 ① ② <b>不对称</b>。</li>
 * </ol>
 *
 * <h3>🔴 三类「B 侧记不记这个错误」的判据（本域<b>不是</b>「有异常就记」）</h3>
 * <ol>
 *   <li><b>api 侧 7 个 span</b>：B 侧的 {@code SetAttributes} 在<b>鉴权成功之后</b>
 *       （{@code extractUserID} 失败 ⇒ 直接 return，<b>不 RecordError</b>）；
 *       鉴权失败之后<b>任何</b>出错都记
 *       ⇒ ⇒ 「未鉴权请求」这一条：<b>span 存在、但无任何属性、且不记错</b>。</li>
 *   <li><b>rpc 侧 7 个 {@code FavoriteRPC.*} span</b>：{@code SetAttributes} <b>紧跟 Start</b>，
 *       <b>先于任何入参校验</b> ⇒ <b>非法入参的 span 也带完整属性</b>；
 *       且<b>任何</b>出错都记（含 {@code InvalidArgument} / {@code AlreadyExists} /
 *       用户不存在 / 名称重复这类<b>纯业务失败</b>）⇒ <b>不按业务码过滤</b>。</li>
 *   <li><b>rpc 侧 2 个 {@code Dependency.*} span</b>：<b>只有下游 RPC 调用失败才记</b>
 *       （{@code helper.go:100} / {@code helper.go:139}）；
 *       <b>业务性失败不记</b> —— {@code helper.go:109-111}（resp 为空 / 未找到）、
 *       {@code helper.go:113-115}（uid 不符 / status 缺失）、
 *       {@code helper.go:117-119}（status ≠ 0 ⇒ 禁止）三处<b>都没有 RecordError</b>。</li>
 * </ol>
 *
 * <h3>🔴 span 状态恒为 {@code UNSET}（<b>本类不提供任何 setStatus 入口</b>）</h3>
 * <p>B 侧 favorite 域 <b>{@code span.SetStatus(...)} 零调用</b>
 * ⇒ 错误状态<b>只</b>由 {@code span.RecordError(err)} 表达。
 * <br>🔴 <b>已实测的 SDK 事实</b>：Java 的 {@code span.recordException(e)} <b>只加 exception event、不改 span status</b>
 * ⇒ ⇒ <b>与 B 侧的 {@code span.RecordError(err)} 语义一致</b>
 * ⇒ ⇒ <b>本域 span 的 status 必须保持 {@code UNSET}</b>
 * ⇒ ⇒ <b>任何 {@code setStatus(ERROR)} 都是引入 B 侧不存在的状态</b> ⇒ 本类<b>刻意不提供</b>该入口。
 *
 * <h3>🔴 与共享层的关系（<b>不需要改 {@code common/}</b>）</h3>
 * <p>本类直接用 {@link GlobalOpenTelemetry#getTracer(String)}，与共享层
 * {@code GoStyleOtelAgent.tracer(name)} 是<b>同一个 API</b>
 * ⇒ <b>favorite 域内即可完成</b>，不需要改共享模块。
 * <br>⇒ 子 span 会<b>正确挂到共享层的 SERVER span 之下</b>：
 * 共享层 {@code RpcInterceptor} 有 {@code span.makeCurrent()} ⇒ 当前上下文里有 SERVER span ⇒
 * {@code tracer.spanBuilder(...).startSpan()}（隐式取 {@code Context.current()}）自动成为它的子 span
 * ⇔ B 侧 {@code tracer.Start(l.ctx, …)} 用的 {@code l.ctx} 也带着 SERVER span ⇒ <b>同构</b>。
 */
@Component
public class FavoriteRpcMetrics {

    // ══════════════════════════ tracer 名（B 侧硬编码在调用点） ══════════════════════════

    /**
     * B 出处：api 侧 7 个 logic 全部写死 {@code otel.Tracer("favorite-api")}。
     */
    public static final String TRACER_NAME_API = "favorite-api";

    /** B 出处：rpc 侧 7 个 logic ＋ helper.go 的 2 个依赖 span 全部写死 {@code otel.Tracer("favorite-rpc")}。 */
    public static final String TRACER_NAME_RPC = "favorite-rpc";

    // ══════════════════════════ api 侧 7 个 span 名 ══════════════════════════

    /** Go {@code api/internal/logic/favorite/create_favorite_folder_logic.go:33}。 */
    public static final String API_CREATE_FAVORITE_FOLDER = "FavoriteAPI.CreateFavoriteFolder";

    /** Go {@code api/internal/logic/favorite/delete_favorite_folder_logic.go:33}。 */
    public static final String API_DELETE_FAVORITE_FOLDER = "FavoriteAPI.DeleteFavoriteFolder";

    /** Go {@code api/internal/logic/favorite/create_favorite_logic.go:34}。 */
    public static final String API_CREATE_FAVORITE = "FavoriteAPI.CreateFavorite";

    /** Go {@code api/internal/logic/favorite/update_favorite_folder_name_logic.go:33}。 */
    public static final String API_UPDATE_FAVORITE_FOLDER_NAME = "FavoriteAPI.UpdateFavoriteFolderName";

    /** Go {@code api/internal/logic/favorite/get_favorites_by_folder_logic.go:33}。 */
    public static final String API_GET_FAVORITES_BY_FOLDER = "FavoriteAPI.GetFavoritesByFolder";

    /** Go {@code api/internal/logic/favorite/get_favorite_folders_logic.go:33}。 */
    public static final String API_GET_FAVORITE_FOLDERS = "FavoriteAPI.GetFavoriteFolders";

    /** Go {@code api/internal/logic/favorite/delete_favorite_logic.go:33}。 */
    public static final String API_DELETE_FAVORITE = "FavoriteAPI.DeleteFavorite";

    // ══════════════════════════ rpc 侧 7 个 span 名 ══════════════════════════

    /** Go {@code rpc/internal/logic/create_favorite_folder_logic.go:43}。 */
    public static final String RPC_CREATE_FAVORITE_FOLDER = "FavoriteRPC.CreateFavoriteFolder";

    /** Go {@code rpc/internal/logic/delete_favorite_folder_logic.go:42}。 */
    public static final String RPC_DELETE_FAVORITE_FOLDER = "FavoriteRPC.DeleteFavoriteFolder";

    /** Go {@code rpc/internal/logic/create_favorite_logic.go:44}。 */
    public static final String RPC_CREATE_FAVORITE = "FavoriteRPC.CreateFavorite";

    /** Go {@code rpc/internal/logic/update_favorite_folder_name_logic.go:42}。 */
    public static final String RPC_UPDATE_FAVORITE_FOLDER_NAME = "FavoriteRPC.UpdateFavoriteFolderName";

    /** Go {@code rpc/internal/logic/get_favorites_by_folder_logic.go:42}。 */
    public static final String RPC_GET_FAVORITES_BY_FOLDER = "FavoriteRPC.GetFavoritesByFolder";

    /** Go {@code rpc/internal/logic/get_favorite_folders_logic.go:40}。 */
    public static final String RPC_GET_FAVORITE_FOLDERS = "FavoriteRPC.GetFavoriteFolders";

    /** Go {@code rpc/internal/logic/delete_favorite_logic.go:42}。 */
    public static final String RPC_DELETE_FAVORITE = "FavoriteRPC.DeleteFavorite";

    // ══════════════════════════ rpc 侧 2 个依赖 span 名 ══════════════════════════

    /** Go {@code rpc/internal/logic/helper.go:93}（{@code ensureUserExists}）。 */
    public static final String DEPENDENCY_USER_GET_USER = "Dependency.User.GetUser";

    /** Go {@code rpc/internal/logic/helper.go:127}（{@code resolveArticleSnapshot}）。 */
    public static final String DEPENDENCY_ARTICLE_GET_ARTICLE = "Dependency.Article.GetArticle";

    // ══════════════════════════ 7 个属性 key（B 侧硬编码字面量） ══════════════════════════

    /** {@code biz.user_id}（Int64）—— 16 个 span 里 15 个用（除 {@code Dependency.Article.GetArticle}）。 */
    public static final String ATTR_USER_ID = "biz.user_id";

    /** {@code biz.folder_id}（Int64）—— api 侧 3 个 ＋ rpc 侧 3 个。 */
    public static final String ATTR_FOLDER_ID = "biz.folder_id";

    /** {@code biz.favorite_id}（Int64）—— api 侧 1 个 ＋ rpc 侧 1 个。 */
    public static final String ATTR_FAVORITE_ID = "biz.favorite_id";

    /**
     * 🔴 {@code biz.folder_name}（String）—— <b>记的是未 trim 的原值</b>
     * （见类注释「取值陷阱」①）⇒ <b>不要</b>在这里做 trim。
     */
    public static final String ATTR_FOLDER_NAME = "biz.folder_name";

    /**
     * 🔴 {@code biz.target_id}（String）—— <b>两端都已 trim</b>
     * （见类注释「取值陷阱」③）⇒ 调用点负责 trim。
     */
    public static final String ATTR_TARGET_ID = "biz.target_id";

    /**
     * 🔴 {@code biz.target_type}（String）—— <b>记的是未规范化的原值</b>
     * （见类注释「取值陷阱」②）⇒ <b>不要</b>做 lower / trim。
     */
    public static final String ATTR_TARGET_TYPE = "biz.target_type";

    /** {@code biz.article_id}（String）—— 只给 {@code Dependency.Article.GetArticle} 用（值 = trim 后的目标 ID）。 */
    public static final String ATTR_ARTICLE_ID = "biz.article_id";

    private final MeterRegistry registry;

    public FavoriteRpcMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ══════════════════════════ 一、指标 ══════════════════════════

    /**
     * 对应 Go {@code ObserveRPC(module, action, started, err)}（metrics.go:56-64）。
     *
     * <p>一次调用记两个指标：请求计数（带 result）+ 累计耗时秒数（不带 result）。
     *
     * @param module {@link com.rag.favorite.config.FavoriteRpcLabels#MODULE_FOLDER} /
     *               {@link com.rag.favorite.config.FavoriteRpcLabels#MODULE_ITEM}
     * @param action 见 {@link com.rag.favorite.config.FavoriteRpcLabels}
     * @param started 逻辑开始时刻
     * @param result {@link com.rag.favorite.config.FavoriteRpcLabels#RESULT_SUCCESS} /
     *               {@link com.rag.favorite.config.FavoriteRpcLabels#RESULT_FAIL}
     */
    public void observeRpc(String module, String action, Instant started, String result) {
        Counter.builder("favorite_request_total")
                .description("Total favorite RPC requests")
                .tags("module", module, "action", action, "result", result)
                .register(registry)
                .increment();

        // ⚠️ 怪癖 ①：B 侧用 CounterVec 累加秒数，不是 Histogram。名字保留 B 侧的字面量。
        Counter.builder("favorite_request_seconds_counter")
                .description("Accumulated favorite RPC seconds")
                .tags("module", module, "action", action)
                .register(registry)
                .increment(Duration.between(started, Instant.now()).toNanos() / 1_000_000_000.0d);
    }

    /**
     * 对应 Go {@code ObserveOp(module, action, result)}（metrics.go:66-68）。
     *
     * <p>⚠️ <b>label 取值不做白名单过滤</b>：B 侧直接把 module/action/result 塞进 label。
     * 本实现照搬 —— 加白名单会让 {@code dependency} 这类值消失，改变告警可见性。
     */
    public void observeOp(String module, String action, String result) {
        Counter.builder("favorite_ops_total")
                .description("Total favorite business operations")
                .tags("module", module, "action", action, "result", result)
                .register(registry)
                .increment();
    }

    /**
     * 对应 Go {@code ObserveDBError(module, action, typ)}（metrics.go:70-72）。
     *
     * @param type {@link com.rag.favorite.config.FavoriteRpcLabels#DB_TYPE_DB} /
     *             {@link com.rag.favorite.config.FavoriteRpcLabels#DB_TYPE_DUPLICATE}
     */
    public void observeDbError(String module, String action, String type) {
        Counter.builder("favorite_db_error_total")
                .description("Total favorite database errors")
                .tags("module", module, "action", action, "type", type)
                .register(registry)
                .increment();
    }

    // ══════════════════════════ 二、span 入口 ══════════════════════════

    /**
     * 在 api 侧的<b>一个手写 span</b> 里执行 {@code body}。
     *
     * <h2>🔴 本入口的「不记错」判据（与 rpc 侧不同）</h2>
     * <p>B 侧 api 的 {@code SetAttributes} 在<b>鉴权成功之后</b>：
     * {@code extractUserID(ctx)} 失败 ⇒ 直接 return（<b>不 RecordError</b>）⇒
     * ⇒ <b>未鉴权请求的 span 存在、但一个属性都没有、且不记错</b>。
     * <br>本类把「取 userId」放在<b>属性阶段</b>：它在 B 侧的位置就是
     * 「{@code Start} 之后、{@code SetAttributes} 之前」⇒
     * <ul>
     *   <li>取 userId <b>抛异常</b> ⇒ 视为<b>鉴权失败</b> ⇒ <b>不记错、不设任何属性</b>，
     *       但 <b>span 仍然存在并结束</b>（B 侧的 {@code defer span.End()} 一样会跑）；</li>
     *   <li>取 userId 成功 ⇒ 设属性 ⇒ 进入业务阶段 ⇒ <b>业务阶段的任何异常都记</b>
     *       （B 侧：鉴权成功后 {@code RecordError} 逐点分布 ⇒ 存在性一致）。</li>
     * </ul>
     *
     * <p>⚠️ <b>状态恒为 {@code UNSET}</b>：B 侧本域 {@code SetStatus} 零调用
     * ⇒ 本方法<b>不</b>调 {@code setStatus}。
     *
     * @param spanName   本域 7 个 api span 名常量之一
     * @param userId     取当前请求的 userId（对应 B 侧的 {@code extractUserID(ctx)}）——<b>可能抛异常</b>
     * @param attributes 设属性；B 侧在鉴权成功之后才 {@code SetAttributes}
     * @param body       业务体；抛出的异常按「鉴权成功后一律记」处理后原样上抛
     */
    public static <T> T tracedApi(String spanName,
                                  LongSupplier userId,
                                  BiConsumer<Span, Long> attributes,
                                  Function<Long, T> body) {
        Tracer tracer = GlobalOpenTelemetry.getTracer(TRACER_NAME_API);
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            long uid;
            try {
                uid = userId.getAsLong();
            } catch (RuntimeException | Error e) {
                // 🔴 B 侧: extractUserID 失败 ⇒ 直接 return，**不 RecordError**
                //    ⇒ 不记错、不设任何属性；但 span 照样 End（B 侧的 defer）。
                throw e;
            }
            attributes.accept(span, uid);
            try {
                return body.apply(uid);
            } catch (RuntimeException | Error e) {
                // B 侧: 鉴权成功之后的每一个错误分支都有 span.RecordError(...)
                // ⇒ Java 的 recordException 与之语义一致（只加事件、不改状态）
                span.recordException(e);
                throw e;
            }
        } finally {
            span.end();
        }
    }

    /**
     * 在 rpc 侧的<b>一个手写 span</b> 里执行 {@code body}。
     *
     * <h2>🔴 本入口的判据（与 api 侧不同）</h2>
     * <p>B 侧 rpc 的 {@code SetAttributes} <b>紧跟 {@code Start}、先于任何入参校验</b>
     * ⇒ <b>非法入参的 span 也带完整属性</b>；
     * 且<b>任何</b>出错都记（含 {@code InvalidArgument} / {@code AlreadyExists} /
     * 用户不存在 / 名称重复这类<b>纯业务失败</b>）⇒ <b>不按业务码过滤</b>。
     * <br>⇒ 本方法的属性阶段放在业务阶段<b>之前</b>（proto getter 不会抛，故属性阶段实际不会失败）。
     *
     * <p>⚠️ <b>状态恒为 {@code UNSET}</b>（见类注释）。
     *
     * @param spanName   本域 7 个 rpc span 名常量之一
     * @param attributes 设属性；B 侧在 {@code Start} 之后立刻设
     * @param body       业务体；抛出的异常<b>一律</b>记后原样上抛
     */
    public static <T> T tracedRpc(String spanName,
                                  Consumer<Span> attributes,
                                  Supplier<T> body) {
        Tracer tracer = GlobalOpenTelemetry.getTracer(TRACER_NAME_RPC);
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            attributes.accept(span);
            try {
                return body.get();
            } catch (RuntimeException | Error e) {
                span.recordException(e);
                throw e;
            }
        } finally {
            span.end();
        }
    }

    /**
     * 下游 RPC 调用 —— 对应 B 侧的 {@code svcCtx.XxxRpc.Yyy(depCtx, …)} 那一行。
     *
     * <p>⚠️ 它<b>只包住那一次调用</b>：调用失败 ⇒ 记错（B 侧的 {@code span.RecordError(err)}），
     * 调用成功之后的业务判定失败 ⇒ <b>不记</b>（B 侧那三处业务分支都没有 {@code RecordError}）。
     */
    @FunctionalInterface
    public interface DependencyCall<T> {
        T call();
    }

    /**
     * 在<b>依赖 span</b>里执行「一次下游调用 ＋ 调用后的业务判定」。
     *
     * <h2>🔴 本入口的判据（与 api / rpc 两个入口都不同）</h2>
     * <p>B 侧的两个依赖 span（{@code helper.go:93} / {@code helper.go:127}）<b>只有下游 RPC 调用失败才记错</b>
     * ⇒ 本方法的 {@code call} 阶段失败 ⇒ 记错 ＋ 交给 {@code errorMapper} 翻译；
     * {@code after} 阶段失败 ⇒ <b>不记错</b>。
     *
     * @param spanName    本域 2 个依赖 span 名常量之一
     * @param attributes  设属性
     * @param call        下游调用；抛出的异常 ⇒ <b>记错</b> ＋ 交给 {@code errorMapper} 翻译
     * @param errorMapper 把「下游调用失败」翻译成业务异常（B 侧的 {@code GRPCError(...)} 那一支）
     * @param after       调用成功后的业务判定；抛出的异常 ⇒ <b>不记错</b>
     */
    public static <T, R> R tracedDependency(String spanName,
                                             Consumer<Span> attributes,
                                             DependencyCall<T> call,
                                             Function<Throwable, RuntimeException> errorMapper,
                                             Function<T, R> after) {
        Tracer tracer = GlobalOpenTelemetry.getTracer(TRACER_NAME_RPC);
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            attributes.accept(span);
            T result;
            try {
                result = call.call();
            } catch (RuntimeException | Error e) {
                // 🔴 B 侧: span.RecordError(err) 只落在「下游 RPC 调用失败」这一支
                span.recordException(e);
                throw errorMapper.apply(e);
            }
            // B 侧: 调用成功之后的业务性失败分支**不** RecordError
            return after.apply(result);
        } finally {
            span.end();
        }
    }
}
