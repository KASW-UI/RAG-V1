package com.rag.common.web;

import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🔴 <b>B 侧 风格的 query 解析与 form 值上限判据</b> —— 对应 B 侧
 * {@code service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java:91-98} 的
 * {@code GetFormValues} ＋ 其转述的标准库 {@code net/url} 的 {@code parseQuery}。
 *
 * <p><b>本类是「判据」，不是「闸门」</b>：它只回答一个问题 ——
 * 「这个 query 按 B 的语义<b>该不该被拒</b>、被拒时的 B 侧 原文是什么」，
 * <b>由谁把这个结论变成 wire 上的响应，由各域自己决定</b>（见下方「🔴 两种拒绝形状」）。
 *
 * <h2>🆕 为什么它住在 {@code service/common}（2026-10-25 第十四轮，从 article-api 迁上来）</h2>
 * <p>原先它是 {@code article-api} 自己的 {@code @Component}（当时是<b>刻意不抽到共享层</b>的：
 * 「{@code service/common} 被 16 个组件依赖，与其它域的工作树并发修改会冲突（AGENTS.md §5.3）」）。
 * <br>⇒ 但 §5.3 的<b>单写权</b>规则后来改成了「<b>{@code service/common/**} 只有 {@code align/global} 能写</b>」
 * ⇒ 共享层反而成了这条规则的<b>唯一合法落点</b>。
 * <br>⇒ 迁移的<b>直接动因</b>：<b>{@code DRIFT-HOTAPI-030}</b> / <b>X-6</b>
 * （这条闸门只在 article 一家生效，<b>其余 9 个 api 模块没有</b>
 * ⇒ 同一个请求在 10 个域里 10 种行为）。
 *
 * <h2>🔴🔴 两种拒绝形状 —— <b>按域而异</b>，不是按实现随意选（2026-10-25 取证结论）</h2>
 * <p>B 的 {@code httpx.Parse} 失败后经 {@code httpx.ErrorCtx} → {@code httpx.SetErrorHandler} 出口，
 * 而<b>「该 api 有没有注册 {@code SetErrorHandler}」决定了 wire 形状</b>：
 * <table border="1">
 *   <caption>B 侧逐域取证（{@code service/…/api/…service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java:34} / {@code commentservice.go}）</caption>
 *   <tr><th>域</th><th>B 侧</th><th>wire 形状</th></tr>
 *   <tr><td>hot / follow / like / message / comment</td><td><b>注册了</b> {@code SetErrorHandler}</td>
 *       <td><b>HTTP 200</b> + {@code {"code":5001,"msg":"&lt;该域文案&gt;","data":null}}</td></tr>
 *   <tr><td>article / user / admin</td><td><b>没注册</b></td>
 *       <td><b>HTTP 400</b> + {@code text/plain} + {@code nosniff} + B 的原文 body</td></tr>
 *   <tr><td>favorite</td><td><b>没注册</b>，但 <b>7 个 handler 各自捕获</b> {@code httpx.Parse} 的错<br>
 *       ⇒ <b>拒绝形状由域自己定</b>（<b>不</b>走共享层的闸门）</td>
 *       <td><b>HTTP 200</b> + {@code {"code":5003,"msg":"参数错误","data":null}}<br>
 *           （= {@code httpx.OkJsonCtx} + {@code favoritecommon.ErrorInvalidParam}）</td></tr>
 * </table>
 * <br>⚠️ <b>task 域不在上表的任何一行里</b>：它 B 侧<b>不解析</b> form/query ⇒
 * {@code r.Form} 从不被求值 ⇒ 两道上限<b>都不可达</b> ⇒ <b>闸门根本不开</b>
 * ⇒ ⇒ 它<b>既不会</b>收到 400 + text/plain，<b>也不会</b>收到 5001
 * ⇒ ⇒ 「没注册 {@code SetErrorHandler}」<b>推不出</b>「它走 400 形状」——
 * ⇒ ⇒ <b>「装不装闸门」与「拒成什么形状」是两条独立的轴</b>
 * ⇒ ⇒ 见 {@link LimitMode} 的逐轴判据与 {@link LimitMode#SEMANTIC_REACHABILITY}。
 * <br>⚠️ <b>各域 5001 的文案还不一样</b>：hot / follow / message = {@code "internal server error"}；
 * like / comment = {@code "系统内部错误"} ⇒ <b>共享层不得硬编码这条文案</b>
 * ⇒ ⇒ <b>本类只产出「B 的原文」（{@link #goParseViolation}），
 * 拒绝成什么形状由各域自己的错误出口决定</b>。
 *
 * <h3>🔴 本表管的是「拒成什么形状」，不是「开不开」</h3>
 * <p>闸门的「<b>开不开</b>」由<b>语义可达性</b>决定（B 侧该域是否真的解析 form/query）
 * ⇒ 见 {@link LimitMode#SEMANTIC_REACHABILITY} 的逐域取证表。
 * <p>⇒ 两条轴<b>必须分开看</b>：本表里的「task」说的是
 * 「若它拒绝，B 的形状是 raw」（task 确实没注册 SetErrorHandler）
 * ⇒ 但 task 的闸门<b>根本不开</b>（B 侧不解析 form/query ⇒ 两道上限不可达）
 * ⇒ ⇒ 两者不矛盾。
 *
 * <h3>🔴 它与 Tomcat 容器上限的关系（本条最容易被忽略的一层）</h3>
 * <p>Tomcat 自己也有 {@code maxParameterCount}（{@code Connector.maxParameterCount}，
 * 读 {@code tomcat/util/http/Parameters.java:79} 的 javadoc：<b>{@code -1} = 不限</b>）。
 * <br>⚠️ <b>关键：Tomcat 只在应用真的调用 {@code getParameter*} 时才解析 query 并计数</b>
 * （{@code Parameters.processParameters} 是<b>惰性</b>的）⇒
 * <b>没读参数的端点，Tomcat 的上限根本不会触发</b> ⇒ 这就是「同一个请求在不同域表现不同」的第二层原因。
 * <br>⚠️ <b>两条上限的语义不同</b>：
 * <table>
 *   <tr><td></td><td>B 侧（{@code httpx/util.go}）</td><td>Tomcat（{@code Parameters.addParameter}）</td></tr>
 *   <tr><td>数什么</td><td><b>非空值</b>（{@code util.go:44-45} 空值 {@code continue}）</td><td><b>参数总数</b>（含空值）</td></tr>
 *   <tr><td>上限</td><td>2048</td><td>容器配置</td></tr>
 * </table>
 * ⇒ 所以 {@code service/common} 里还配了一个
 * {@link GoStyleTomcatParameterLimitCustomizer}，把容器上限抬到 <b>10001</b>
 * （= B 的 {@code net/url} 段数上限 10000 <b>+1</b>）⇒ <b>让应用层有机会先按 B 的语义判定</b>
 * ⇒ 详见该类的 javadoc。
 *
 * <h2>为什么叫这个名字</h2>
 * B 侧这些行为<b>全在同一个函数里</b>（{@code service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java:82:22-65} 的 {@code GetFormValues}）：
 * <ol>
 *   <li>{@code r.ParseForm()} ⇒ 触发 B 侧 标准库 {@code net/url} 的解析（<b>含它的三条错误</b>）；</li>
 *   <li>然后才是框架层自己的 {@code maxFormParamCount = 2048} 计数（B 侧承载者 = {@code service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java:91-96}）。</li>
 * </ol>
 * ⇒ 本类按<b>同样的顺序</b>复刻这两步 —— 顺序本身就是<b>可观察</b>的。
 *
 * <h2>B 出处（逐行，消费点）</h2>
 * <pre>
 * B 出处：service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java:91:10-16
 *   const (
 *       // most servers and clients have a limit of 8192 bytes (8 KB)
 *       // one parameter at least take 4 chars, for example `?a=b&amp;c=d`
 *       maxFormParamCount = 2048
 *   )
 *
 * B 出处：service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java:98:22-65  GetFormValues（下面摘录的是其中 :35-54 的双层循环）
 *   for name, values := range r.Form {
 *       for _, v := range values {
 *           if len(v) == 0 { continue }              // ← 空值不计数
 *           if n &lt; maxFormParamCount { filtered = append(filtered, v); n++ }
 *           else { return nil, fmt.Errorf("too many form values, error: %s", r.Form.Encode()) }
 *       }
 *   }
 * </pre>
 *
 * <p><b>调用链</b>：B 侧 handler 里的 {@code httpx.Parse(r, &amp;req)}
 * （{@code service/article/api/internal/handler/article/*.go:21}）
 * → {@code httpx.ParseForm} → {@code httpx.GetFormValues} ⇒ 上面那个上限生效。
 * <br>⇒ 超限时 {@code GetFormValues} 返回 error → handler 调 {@code httpx.ErrorCtx}
 * → {@code httpx/responses.go:142-159} 的 {@code doHandleError}：
 * 本模块未注册 {@code SetErrorHandler} ⇒ {@code handler == nil} ⇒
 * 该 error <b>不是</b> gRPC error ⇒ 走 {@code else} 分支 ⇒
 * {@code http.Error(w, err.Error(), http.StatusBadRequest)}
 * ⇒ <b>HTTP 400</b>，body = {@code "too many form values, error: " + r.Form.Encode()} + {@code "\n"}。
 *
 * <h2>⚠️ 为什么这条不是「无害的防御性增强」</h2>
 * 它在 B 侧是一条 <b>DoS 闸门</b>（注释明说是为了 8 KB query 上限）。
 * 没有它 ⇒ 客户端可以发任意多个 query 参数，服务端<b>全部解析</b>并绑定。
 * <br>⚠️ Tomcat 自己也有 {@code maxParameterCount}（默认 10000），
 * 但那是<b>另一个阈值</b> ⇒ 在 <b>2049 ~ 10000</b> 这段区间里两侧行为不同。
 *
 * <h2>🔴 真容器实测：默认配置下这条闸门<b>不可达</b>（2026-10-24）</h2>
 * Tomcat 自己有请求行/请求头上限（Boot 4 的 {@code server.max-http-request-header-size}，默认 <b>8 KB</b>），
 * 而 2049 个<b>最短</b>的非空 query 值（{@code a=1&} × 2049 ≈ 8196 字节）<b>本身就超了</b>
 * ⇒ Tomcat 在<b>连接器层</b>就以 <b>400</b> 拒掉，请求<b>根本到不了</b>应用层
 * （实测 body 是 Tomcat 的 <b>HTML 错误页</b>，不是 B 的 text/plain）。
 * <br>⇒ 所以这条闸门的价值是：<b>让上限不依赖容器配置</b> ——
 * 部署把 {@code server.max-http-request-header-size} 调高（长 header 场景常见）时，
 * RAG 仍然按 B 的 2048 判据拒绝，<b>且给出 B 侧 逐字相同的 body</b>（实测已验证）。
 * <br>⚠️ Boot 4 里属性名是 {@code server.max-http-request-header-size}；
 * 旧的 {@code server.max-http-header-size} <b>已不存在</b> ⇒ 写它会被<b>静默忽略</b>
 * （实测踩过：写了它，请求仍被 Tomcat 以 400 拒掉，闸门根本没执行）。
 *
 * <h2>⚠️ 为什么只统计 <b>query string</b>，不碰 body</h2>
 * B 侧 {@code r.Form} 是「query + body（仅 urlencoded / multipart 字段）」的合并结果
 * （{@code service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java:137:23-31}：{@code r.ParseForm()} ＋ {@code r.ParseMultipartForm(maxMemory)}）。
 * <br>⇒ 本判据只数 query，<b>理论上</b>会漏掉 body 里的表单字段。实测这条漏口<b>不可达</b>：
 * <ul>
 *   <li><b>multipart</b>：Tomcat 的 {@code Connector.maxPartCount} 默认 <b>50</b>
 *       （{@code catalina/connector/Connector.java:253}）⇒ multipart 表单字段 <b>≤ 50</b>
 *       ⇒ 远低于 2048 ⇒ <b>数不数都一样</b>；</li>
 *   <li><b>urlencoded</b>：全仓 <b>没有任何 api 端点</b>接受 {@code application/x-www-form-urlencoded}
 *       请求体（实测：{@code grep} 只命中 content-type 协商类测试与 {@code task-api} 的
 *       {@code GoStyleRawBodyArgumentProcessor}，后者处理的是「Spring 把 form body 解析成参数」
 *       这条<b>读 body</b>的路径，不是 form 值计数）；</li>
 * </ul>
 * ⇒ 且<b>不能</b>用 {@code request.getParameterMap()} 来补齐 body：
 * 它会让容器提前解析 multipart body（{@code parseParts}），<b>改变 body 的消费时机</b>
 * ⇒ 会打断 {@code task-api} 的 {@code GoStyleRawBodyArgumentProcessor}（它要读<b>原始</b> body）
 * ⇒ ⇒ 走 query string 更安全，且上面的证据说明这不丢任何实际语义。
 *
 * <h2>⚠️ 它和「容器上限」必须<b>配合</b>才有意义</h2>
 * 单靠本判据不够：<b>Tomcat 的上限会先于它触发</b>（只要应用读了 query 参数）
 * ⇒ Tomcat 拒的是 <b>400 + HTML</b>，而 B 侧 拒的是 <b>400 + {@code text/plain} 的原文 body</b>
 * ⇒ ⇒ 必须同时把容器上限抬起来 ⇒ {@link GoStyleTomcatParameterLimitCustomizer}（同包）。
 *
 * <h2>⚠️ B 侧 标准库 {@code net/url} 的三条行为 —— 本类 <b>已覆盖</b>（2026-10-24）</h2>
 * <pre>
 * B 侧 1.25 标准库 net/url/url.go:1019-1054  parseQuery
 *   if !urlParamsWithinMax(strings.Count(query, "&amp;") + 1) {
 *       return errors.New("number of URL query parameters exceeded limit")
 *   }                                              // ← ⚠️ **在解析之前**就 return
 *   for query != "" {
 *       key, query, _ = strings.Cut(query, "&amp;")
 *       if strings.Contains(key, ";") {
 *           err = fmt.Errorf("invalid semicolon separator in query")
 *           continue
 *       }
 *       …
 *       key, err1 := QueryUnescape(key)           // 失败 ⇒ err = err1
 *       value, err1 = QueryUnescape(value)
 *   }
 * </pre>
 * ⇒ B 侧这三条**都返 400**（body = 对应文案 + {@code "\n"}）：
 * <ul>
 *   <li>{@code number of URL query parameters exceeded limit}（&gt; 10000 <b>段</b>，<b>最先</b>发生）；</li>
 *   <li>{@code invalid semicolon separator in query}；</li>
 *   <li>{@code invalid URL escape "%zz"}（文案由 B 的 {@code strconv.Quote} 产生）。</li>
 * </ul>
 * ⚠️ <b>顺序可观察</b>：B 侧 里 10000 段的上限<b>先于</b> 框架层的 2048 计数
 * ⇒ 10001 段时 B 侧 报的是前者，不是 {@code too many form values}。
 * <br>⚠️ <b>修之前的 RAG 现状</b>（2026-10-24 真容器实测）：
 * <table>
 *   <tr><td>场景</td><td>B 侧</td><td>RAG（修前）</td></tr>
 *   <tr><td>{@code ?a=1;b=2}</td><td>400 + 分号文案</td><td>🔴 <b>200</b>（请求被接受）</td></tr>
 *   <tr><td>{@code ?a=%zz}</td><td>400 + 转义文案</td><td>🔴 <b>500 空 body</b></td></tr>
 *   <tr><td>10001 段</td><td>400 + 段数文案</td><td>🟡 400，但 body 是 <b>2048</b> 那条（顺序反了）</td></tr>
 * </table>
 *
 * <h2>🔴 本类只出「判据」，不出「响应」</h2>
 * <p>拒绝成什么形状（400 原文 / 200 信封）由<b>各域自己</b>决定（见类 javadoc 的「两种拒绝形状」）⇒
 * 本类只暴露两个公开入口：
 * <ul>
 *   <li>{@link #goParseViolation(String)} —— 「按 B 的语义该不该拒、原文是什么」；</li>
 *   <li>{@link #writeGoStyleBadRequest(HttpServletResponse, String)} —— B 的
 *       {@code http.Error} 形状（<b>只有「没注册 SetErrorHandler」的域</b>用）。</li>
 * </ul>
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java}
 * §1.12 第 266 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
public final class GetFormValuesFilter {

    /**
     * B 侧 {@code rest/httpx/util.go:15} 的 {@code maxFormParamCount}，<b>不可配置</b>
     * （B 侧它是常量 ⇒ 做成 property 等于凭空多出 B 侧 没有的开关）。
     */
    public static final int MAX_FORM_PARAM_COUNT = 2048;

    /**
     * B 侧 标准库 {@code net/url/url.go:1001} 的 {@code defaultMaxParams = 10000}。
     *
     * <p>⚠️ 它数的是 <b>{@code &} 分隔的段数</b>（{@code strings.Count(query, "&") + 1}），
     * <b>不是</b> distinct key 数 —— 10001 个 {@code a=1} 也是 10001 段 ⇒ 超限。
     */
    public static final int MAX_URL_QUERY_PARAMS = 10000;

    /** B 侧 {@code url.go} 的 {@code upperhex}（大写十六进制）。 */
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private GetFormValuesFilter() {
    }

    /**
     * 按 B 的语义判定「这个 query 该不该被拒」，并给出<b>B 的原文</b>。
     *
     * <p>判定顺序与 B 侧 完全一致（<b>顺序本身是可观察的</b>）：
     * <ol>
     *   <li>{@code net/url} 的<b>段数上限</b>（{@code url.go:1020-1022}）—— 它在<b>解析之前</b>就 return；</li>
     *   <li>{@code net/url} 的<b>分段解析错误</b>（{@code url.go:1023-1054}）——
     *       B 的 {@code ParseForm} 把 error 原样往上抛（{@code httpx/util.go:23-25}）；</li>
     *   <li>B 侧 的 <b>2048 非空值计数</b>（{@code service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java:231:44-53}）。</li>
     * </ol>
     *
     * <h2>🔴 消费者清单（改本方法的规则或次序 ⇒ 必须同步通知下列域）</h2>
     * <p><b>本方法不是「只有共享层闸门在用」</b> —— 它已被<b>域内闸门直接消费</b>，
     * 而域内闸门的拒绝形状会直接变成<b>该域对外的可观察行为</b>：
     * <ul>
     *   <li><b>{@code favorite-api}</b> ——
     *       {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/interceptor/FavoriteGoQueryLimitInterceptor.java}
     *       <b>只读调用</b>本方法（不复制规则）⇒ ⇒ 本方法的规则/次序 <b>就是</b>
     *       favorite-api 7 个端点<b>全部 query 解析失败路径</b>的可观察行为
     *       （非空 query 值 &gt; 2048 ⇒ 200+5003 · {@code %zz} ⇒ 200+5003 ·
     *       10001 段 ⇒ 200+5003 · 含分号 ⇒ 200+5003）。
     *       ⚠️ <b>域侧回归网</b>（改动本方法会让它们红 ⇒ 可当守门用）：
     *       {@code FavoriteApiRealTomcatGateTest}（真 Tomcat · 16/16）＋
     *       {@code FavoriteGoQueryLimitInterceptorTest}。</li>
     * </ul>
     * <p>⇒ ⇒ <b>动本方法前</b>：① 先跑 favorite-api 的上述两条守门；② 改完必须通知
     * <b>favorite 域（G3）</b>同步复验 ⇒ 因为该域的可观察行为<b>承重于本方法</b>。
     * <p>⚠️ <b>反过来说</b>：本方法<b>只读不写</b>，域侧调用方<b>不得</b>改写它的返回值语义
     * ⇒ 域侧只允许「<b>判定</b>本方法的结论」并<b>自行决定拒绝形状</b>。
     *
     * @param query 原始 query string（{@code request.getQueryString()}）；{@code null} / 空串 ⇒ 不拒绝
     * @return {@code null} = 按 B 的语义<b>应当放行</b>；
     *         否则 = B 的原文（{@code http.Error} 的 body 内容，<b>不含</b>尾随换行）
     */
    public static String goParseViolation(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        // ① B 的 net/url **先**做段数上限（url.go:1020-1022）—— 它在解析**之前**就 return
        if (countQuerySegments(query) > MAX_URL_QUERY_PARAMS) {
            return "number of URL query parameters exceeded limit";
        }
        // ② 再按 B 的分段规则解析（url.go:1023-1054）；错误**要上报**
        //    （B 的 ParseForm 把 net/url 的 error 返回，util.go:23-25 原样往上抛）
        GoQueryParse parsed = parseGoQuery(query);
        if (parsed.error != null) {
            return parsed.error;
        }
        // ③ 最后才做 B 侧的 2048 计数（service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java:271:44-53）
        int nonEmpty = countNonEmptyValues(parsed.form);
        if (nonEmpty > MAX_FORM_PARAM_COUNT) {
            return "too many form values, error: " + encodeGoValues(parsed.form);
        }
        return null;
    }

    /**
     * B 的 {@code net/http.Error} 形状 —— <b>只给「没注册 {@code SetErrorHandler}」的域用</b>
     * （article / user / admin；task 也没注册，但它 B 侧不解析 form/query
     * ⇒ 闸门根本不开 ⇒ 用不到这个形状；favorite 也没注册，但它 <b>自己捕获</b>解析错
     * ⇒ 拒绝形状由域自己定 ⇒ <b>也用不到这个形状</b>）。
     *
     * <p>B 出处：{@code service/common/src/main/java/com/rag/common/http/GoStyleFormValues.java:285} 的 {@code Error()}：
     * <pre>
     *   h.Del("Content-Length")
     *   h.Set("Content-Type", "text/plain; charset=utf-8")
     *   h.Set("X-Content-Type-Options", "nosniff")
     *   w.WriteHeader(code); fmt.Fprintln(w, error)     // ⇒ body 带一个 \n
     * </pre>
     */
    public static void writeGoStyleBadRequest(HttpServletResponse response, String message)
            throws IOException {
        response.setHeader("Content-Type", "text/plain; charset=utf-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.getOutputStream().write((message + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * B 侧 {@code strings.Count(query, "&amp;") + 1}（{@code url.go:1020}）—— <b>段数</b>。
     *
     * <p>⚠️ B 的判据是 {@code params &lt;= defaultMaxParams}（{@code url.go:1003}）
     * ⇒ 恰好 10000 段<b>不</b>超限，10001 段才超。
     */
    private static int countQuerySegments(String query) {
        int count = 1;
        for (int i = 0; i < query.length(); i++) {
            if (query.charAt(i) == '&') {
                count++;
            }
        }
        return count;
    }

    /**
     * B 侧 {@code net/url.parseQuery}（{@code url.go:1023-1054}）的解析结果。
     *
     * @param form  已成功解析的键值（B 侧出错时已解析的部分仍在 {@code m} 里，但**没人用**）
     * @param error B 侧会返回的那个 error 文案（{@code null} = 无错）
     */
    private static final class GoQueryParse {
        final Map<String, List<String>> form;
        final String error;

        GoQueryParse(Map<String, List<String>> form, String error) {
            this.form = form;
            this.error = error;
        }
    }

    /**
     * B 侧 {@code net/url.parseQuery}（{@code url.go:1019-1054}）的分段规则。
     *
     * <p>逐条对齐：
     * <ul>
     *   <li>按 {@code &} 切分（B 侧 1.17+ <b>不</b>把 {@code ;} 当分隔符）；</li>
     *   <li>段内含 {@code ;} ⇒ B 侧 记错误并<b>跳过该段</b>（{@code url.go:1026-1029}）
     *       —— ⚠️ B 侧 是<b>无条件赋值</b>，所以<b>最后一个</b>分号段会覆盖前面的错误；</li>
     *   <li>key 为空 ⇒ 跳过（{@code url.go:1030-1032}）；</li>
     *   <li>按<b>第一个</b> {@code =} 切 key/value（{@code url.go:1033}）；</li>
     *   <li>key 与 value 各自 {@code QueryUnescape}（{@code +} ⇒ 空格，{@code %XX} ⇒ 字节）；</li>
     *   <li>解码失败 ⇒ B 侧 记错误并跳过该段（{@code url.go:1034-1045}）
     *       —— ⚠️ B 侧 是 {@code if err == nil { err = err1 }} ⇒ <b>第一个</b>转义错误胜出。</li>
     * </ul>
     */
    private static GoQueryParse parseGoQuery(String query) {
        Map<String, List<String>> form = new LinkedHashMap<>();
        String error = null;
        int start = 0;
        while (start <= query.length()) {
            int amp = query.indexOf('&', start);
            String part = (amp < 0) ? query.substring(start) : query.substring(start, amp);
            start = (amp < 0) ? query.length() + 1 : amp + 1;

            if (part.indexOf(';') >= 0) {
                // B 侧 url.go:1026-1029 —— 无条件赋值（会覆盖前面的错误）
                error = "invalid semicolon separator in query";
                continue;
            }
            if (part.isEmpty()) {
                continue;                       // B 侧: key == "" ⇒ 跳过
            }
            int eq = part.indexOf('=');
            String rawKey = (eq < 0) ? part : part.substring(0, eq);
            String rawValue = (eq < 0) ? "" : part.substring(eq + 1);

            String key = queryUnescape(rawKey);
            if (key == null) {
                if (error == null) {
                    error = escapeErrorMessage(rawKey);
                }
                continue;
            }
            String value = queryUnescape(rawValue);
            if (value == null) {
                if (error == null) {
                    error = escapeErrorMessage(rawValue);
                }
                continue;
            }
            form.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return new GoQueryParse(form, error);
    }

    /** B 侧 {@code GetFormValues} 的 {@code if len(v) == 0 { continue }} —— <b>空值不计数</b>。 */
    private static int countNonEmptyValues(Map<String, List<String>> form) {
        int n = 0;
        for (List<String> values : form.values()) {
            for (String v : values) {
                if (!v.isEmpty()) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * B 侧 {@code net/url.Values.Encode}（{@code url.go:1055-1073}）：<b>按 key 排序</b>后逐个转义。
     *
     * <p>⚠️ 排序用<b>码点序</b>（= UTF-8 字节序），不是 {@link String#compareTo}
     * （UTF-16 码元序）—— 与 {@code DRIFT-ARTICLE-API-007} 同一个坑。
     */
    private static String encodeGoValues(Map<String, List<String>> form) {
        List<String> keys = new ArrayList<>(form.keySet());
        keys.sort(GetFormValuesFilter::compareByCodePoint);
        StringBuilder buf = new StringBuilder();
        for (String k : keys) {
            String keyEscaped = queryEscape(k);
            for (String v : form.get(k)) {
                if (buf.length() > 0) {
                    buf.append('&');
                }
                buf.append(keyEscaped).append('=').append(queryEscape(v));
            }
        }
        return buf.toString();
    }

    /**
     * B 侧 {@code net/url.QueryEscape}（{@code url.go:282-284} → {@code shouldEscape} 的
     * {@code encodeQueryComponent} 分支，{@code url.go:158-161}）。
     *
     * <p>规则：{@code A-Z a-z 0-9 - _ . ~} <b>不转义</b>；空格 ⇒ {@code +}；其余 ⇒ {@code %XX}（<b>大写</b>）。
     * <br>⚠️ 不能用 {@code java.net.URLEncoder}：它保留 {@code *}、把 {@code ~} 转成 {@code %7E} ⇒ 与 B 侧 不同。
     */
    private static String queryEscape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else if (c == ' ') {
                out.append('+');
            } else {
                out.append('%').append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
            }
        }
        return out.toString();
    }

    /**
     * B 侧 {@code net/url.QueryUnescape}（{@code url.go:282-284} → {@code unescape}）。
     *
     * <p>⚠️ 按<b>字节</b>解码（不是 char）：{@code %E4%B8%AD} 是 3 个字节的「中」，
     * 若按 char 解会得到 3 个 U+00E4/U+00B8/U+00AD ⇒ 再编成 UTF-8 就是 6 个字节 ⇒ 与 B 侧 不同。
     * <br>返回 {@code null} 表示解码失败（B 侧是 error ⇒ 跳过该段）。
     */
    private static String queryUnescape(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    return null;
                }
                int hi = hexValue(s.charAt(i + 1));
                int lo = hexValue(s.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    return null;
                }
                out.write((hi << 4) | lo);
                i += 3;
            } else if (c == '+') {
                out.write(' ');
                i++;
            } else {
                // B 侧 按字节处理 ⇒ 非 ASCII 原样写成 UTF-8 字节
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(encoded, 0, encoded.length);
                i++;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static int hexValue(byte b) {
        int c = b & 0xFF;
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    // ==================================================================
    // B 的 EscapeError 文案：invalid URL escape "<那一小截>"
    // ==================================================================

    /**
     * B 的 {@code net/url.EscapeError}（{@code net/url/url.go:91-96}）。
     *
     * <pre>
     * type EscapeError string
     * func (e EscapeError) Error() string {
     *     return "invalid URL escape " + strconv.Quote(string(e))
     * }
     * </pre>
     *
     * <p>⚠️ <b>按字节</b>处理（B 的 {@code unescape} 扫的是字节）：
     * ① 找<b>第一个</b>非法转义的位置；
     * ② 截取「{@code %} + 最多 2 个字节」（不足则到串尾）——
     *    ⚠️ 这个切片<b>可能截断一个多字节字符</b>（B 侧 也是这样）⇒ 必须按字节做。
     */
    private static String escapeErrorMessage(String raw) {
        byte[] b = raw.getBytes(StandardCharsets.UTF_8);
        int i = 0;
        while (i < b.length) {
            if (b[i] == '%') {
                if (i + 2 >= b.length || hexValue(b[i + 1]) < 0 || hexValue(b[i + 2]) < 0) {
                    int end = Math.min(b.length, i + 3);
                    byte[] snippet = new byte[end - i];
                    System.arraycopy(b, i, snippet, 0, snippet.length);
                    return "invalid URL escape " + goQuote(snippet);
                }
                i += 3;
            } else {
                i++;
            }
        }
        // ⚠️ 理论上到不了这里（queryUnescape 返回 null 必然意味着存在非法转义）
        return "invalid URL escape " + goQuote(new byte[]{'%'});
    }

    /**
     * B 的 {@code strconv.Quote}（{@code strconv/quote.go:23-24,31-56,68-115}）。
     *
     * <p>逐条对齐：
     * <ul>
     *   <li>首尾加 {@code "}；</li>
     *   <li>{@code "} 与 {@code \} ⇒ 前加 {@code \}；</li>
     *   <li>可打印（B 的 {@code IsPrint}：L/M/N/P/S 类 + ASCII 空格）⇒ 原样；</li>
     *   <li>控制字符 ⇒ {@code \a \b \f \n \r \t \v}；其余 {@code < 0x20} 或 {@code 0x7f}
     *       ⇒ {@code \xNN}（<b>小写</b>十六进制）；</li>
     *   <li>非 BMP ⇒ {@code \}{@code uNNNN}（小写）；</li>
     *   <li><b>非法 UTF-8 字节</b> ⇒ {@code \xNN}（小写）—— B 的
     *       {@code quote.go:46-51}；</li>
     * </ul>
     */
    private static String goQuote(byte[] s) {
        StringBuilder out = new StringBuilder(s.length + 2);
        out.append('"');
        int i = 0;
        while (i < s.length) {
            int[] decoded = decodeRune(s, i);
            int r = decoded[0];
            int width = decoded[1];
            i += width;
            if (width == 1 && r == 0xFFFD && (s[i - 1] & 0xFF) >= 0x80) {
                // B 侧 quote.go:46-51 —— 非法 UTF-8 字节 ⇒ \xNN
                out.append("\\x").append(lowerHex(s[i - 1] >> 4)).append(lowerHex(s[i - 1] & 0xF));
                continue;
            }
            if (r == '"' || r == '\\') {
                out.append('\\').append((char) r);
                continue;
            }
            if (isGoPrintable(r)) {
                out.appendCodePoint(r);
                continue;
            }
            switch (r) {
                // ⚠️ Java **没有** \a / \v 这两个转义（那是 C/B 的）⇒ 用码点常量
                case 0x07 -> out.append("\\a");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case 0x0B -> out.append("\\v");
                default -> {
                    if (r < 0x20 || r == 0x7f) {
                        out.append("\\x").append(lowerHex(r >> 4)).append(lowerHex(r & 0xF));
                    } else if (r < 0x10000) {
                        out.append("\\u");
                        for (int sh = 12; sh >= 0; sh -= 4) {
                            out.append(lowerHex((r >> sh) & 0xF));
                        }
                    } else {
                        out.append("\\U");
                        for (int sh = 28; sh >= 0; sh -= 4) {
                            out.append(lowerHex((r >> sh) & 0xF));
                        }
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /** B 的 {@code strconv.lowerhex}（<b>小写</b>十六进制）。 */
    private static char lowerHex(int nibble) {
        return "0123456789abcdef".charAt(nibble & 0xF);
    }

    /**
     * B 的 {@code utf8.DecodeRuneInString}（{@code unicode/utf8/utf8.go}）。
     *
     * @return {@code [码点, 字节数]}；非法序列 ⇒ {@code [0xFFFD, 1]}
     */
    private static int[] decodeRune(byte[] b, int i) {
        int c0 = b[i] & 0xFF;
        if (c0 < 0x80) {
            return new int[]{c0, 1};
        }
        int len;
        int cp;
        if (c0 < 0xC2) {
            return new int[]{0xFFFD, 1};           // 续字节 / 过长编码的起始字节
        } else if (c0 < 0xE0) {
            len = 2;
            cp = c0 & 0x1F;
        } else if (c0 < 0xF0) {
            len = 3;
            cp = c0 & 0x0F;
        } else if (c0 < 0xF5) {
            len = 4;
            cp = c0 & 0x07;
        } else {
            return new int[]{0xFFFD, 1};
        }
        if (i + len > b.length) {
            return new int[]{0xFFFD, 1};
        }
        for (int k = 1; k < len; k++) {
            int cc = b[i + k] & 0xFF;
            if ((cc & 0xC0) != 0x80) {
                return new int[]{0xFFFD, 1};
            }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (len == 3 && cp < 0x800) {
            return new int[]{0xFFFD, 1};           // 过长编码
        }
        if (len == 4 && cp < 0x10000) {
            return new int[]{0xFFFD, 1};           // 过长编码
        }
        if (cp >= 0xD800 && cp <= 0xDFFF) {
            return new int[]{0xFFFD, 1};           // 代理区
        }
        if (cp > 0x10FFFF) {
            return new int[]{0xFFFD, 1};
        }
        return new int[]{cp, len};
    }

    /**
     * B 的 {@code strconv.IsPrint}（{@code strconv/quote.go:522-563}；
     * ASCII / Latin-1 段的判据 = {@code quote.go:524-533}）。
     *
     * <p>B 的判据逐条：
     * <ul>
     *   <li>ASCII 段 {@code 0x20..0x7E} ⇒ 可打印；</li>
     *   <li>Latin-1 补充段 {@code 0xA1..0xFF} ⇒ 可打印，
     *       <b>唯一例外是软连字符 U+00AD</b>（B 侧 源码的注释叫它 "the bizarre soft hyphen"）；</li>
     *   <li>{@code > 0xFF} ⇒ unicode 的 L/M/N/P/S 五类 <b>加</b> ASCII 空格
     *       （<b>不含</b>其它 Unicode 空格，如 U+00A0、U+3000）。</li>
     * </ul>
     *
     * <p>⚠️ <b>Latin-1 补充段在 HTTP 入口是「结构性不可达」的</b>：
     * Tomcat 在<b>连接器层</b>就拒掉了 query 里任何 {@code >= 0x80} 的字节
     * （HTTP/1.1：{@code Http11InputBuffer} 的 {@code isQueryRelaxed} 检查；
     * HTTP/2：{@code StreamProcessor} 的同名检查）⇒ {@code goQuote} 的输入恒为纯 ASCII。
     * <br>此处仍按 B 侧 逐条实现（而不是就 ASCII 取近似），理由与逐行证据 ⇒
     * 本域台账 {@code docs/DRIFT/article/api.md}。
     */
    private static boolean isGoPrintable(int r) {
        if (r <= 0xFF) {
            // B 侧 strconv/quote.go:524-533（IsPrint 的 Latin-1 快路径）
            if (r >= 0x20 && r <= 0x7E) {
                return true;
            }
            if (r >= 0xA1 && r <= 0xFF) {
                return r != 0xAD;          // ← 软连字符是唯一例外
            }
            return false;
        }
        switch (Character.getType(r)) {
            case Character.UPPERCASE_LETTER:
            case Character.LOWERCASE_LETTER:
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            case Character.NON_SPACING_MARK:
            case Character.COMBINING_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.DECIMAL_DIGIT_NUMBER:
            case Character.LETTER_NUMBER:
            case Character.OTHER_NUMBER:
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.MATH_SYMBOL:
            case Character.CURRENCY_SYMBOL:
            case Character.MODIFIER_SYMBOL:
            case Character.OTHER_SYMBOL:
                return true;
            default:
                return false;
        }
    }

    /** 码点序（= UTF-8 字节序），与 B 的字符串比较一致。 */
    private static int compareByCodePoint(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    // ⚠️ 刻意<b>不</b>再保留第二份 {@code http.Error} 形状的实现：
    // 两份实现一旦漂移，就会出现「有的域返 B 的 body、有的域返别的」
    // ⇒ 与本条要消除的「同一个请求 10 个域 10 种行为」同源。
}
