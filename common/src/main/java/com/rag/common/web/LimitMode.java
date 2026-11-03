package com.rag.common.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🔴 本域的 <b>form 值上限闸门</b>用哪种拒绝形状 —— 对齐 B 侧「该 api 有没有注册
 * {@code httpx.SetErrorHandler}」这一事实。
 *
 * <h2>🟢 闸门「开 / 关」的<b>唯一判据</b>：<b>语义可达性</b>（G2 反馈单 FB-02）</h2>
 * <p><b>B 侧该域的 handler 是否真的解析 form / query</b>：
 * <ul>
 *   <li><b>解析了</b>（handler 调 {@code httpx.Parse}）
 *       ⇒ ⇒ {@code httpx.Parse} → {@code ParseForm} → {@code GetFormValues}（{@code httpx/util.go:15-48}）
 *       ⇒ ⇒ {@code r.ParseForm()} 会触发 B 的 {@code net/url} <b>10000 段</b>上限
 *       ⇒ ⇒ {@code GetFormValues} 会数<b>非空值</b>，超过 {@code maxFormParamCount = 2048} 即报错
 *       ⇒ ⇒ <b>两道上限都可达</b> ⇒ 闸门<b>必须开</b>；</li>
 *   <li><b>没解析</b>（handler 直接 {@code json.NewDecoder(r.Body)}）
 *       ⇒ ⇒ {@code r.Form} <b>从不被求值</b> ⇒ 两道上限<b>都不可达</b>
 *       ⇒ ⇒ <b>闸门必须关</b>（{@link Mode#OFF}）⇒ RAG 与 B 侧 同为「不因 query 而拒」。</li>
 * </ul>
 *
 * <h3>🔴 为什么这条判据必须是「语义可达性」，而<b>不能</b>是「写权范围」</h3>
 * <p>旧版本类的 javadoc 把「{@code off} 的依据」写成「<b>favorite 域不在 global 的写权范围内</b>」
 * ⇒ ⇒ 那是一条<b>与 B 侧 行为无关</b>的理由 ⇒ ⇒ 后果：
 * <ul>
 *   <li>① 判据无法回答「下一个域该开还是该关」⇒ ⇒ 下一个<b>裸 decoder</b> 域会照抄别人的声明；</li>
 *   <li>② 漏声明的域会<b>静默落到缺省 {@code off}</b>
 *       ⇒ ⇒ 而缺省 {@code off} 只对「B 侧不解析」的域才是对的
 *       ⇒ ⇒ 对「B 侧解析」的域，缺省 {@code off} <b>就是一条漂移</b>。</li>
 * </ul>
 * ⇒ ⇒ 现把判据改成「语义可达性」，并把 <b>10 个域的逐域取证落成代码</b>
 * （{@link #SEMANTIC_REACHABILITY}）⇒ ⇒ 判据<b>只有一份</b>，且<b>可被机器核对</b>。
 *
 * <h2>⚠️ 两条轴必须分开看（这是本类最容易混淆的地方）</h2>
 * <table border="1">
 *   <caption>「开不开」与「拒成什么样」是<b>两条独立的轴</b></caption>
 *   <tr><th>轴</th><th>判据</th><th>取值</th></tr>
 *   <tr><td><b>① 开 / 关</b></td>
 *       <td><b>第一判据</b>：B 侧 handler 是否<b>自己捕获</b> {@code httpx.Parse} 的错<br>
 *           ⇒ <b>捕获 ⇒ 拒绝形状由域自己定 ⇒ 共享层不装闸门 ⇒ {@link Mode#OFF}</b>；<br>
 *           <b>第二判据</b>（只在没捕获时）：B 侧该域是否真的解析 form/query（<b>语义可达性</b>）</td>
 *       <td>{@link Mode#OFF} ⇔ 域自捕获（形状由域定）<b>或</b> 上限不可达；<br>
 *           {@link Mode#RAW} / {@link Mode#ENVELOPE} ⇔ 没捕获<b>且</b>可达</td></tr>
 *   <tr><td><b>② 拒成什么形状</b></td>
 *       <td><b>第一判据</b>：B 侧 handler 是否<b>自己捕获</b> {@code httpx.Parse} 的错<br>
 *           <b>第二判据</b>（只在没捕获时）：B 侧该 api 有没有注册 {@code httpx.SetErrorHandler}</td>
 *       <td>自捕获 ⇒ <b>域自定义信封</b>（favorite = 200 + {code:5003,…}）；<br>
 *           没捕获 + 没注册 ⇒ {@link Mode#RAW}（400 + text/plain + B 侧 原文）；<br>
 *           没捕获 + 注册了 ⇒ {@link Mode#ENVELOPE}（200 + {code:5001,…}）</td></tr>
 * </table>
 * <p>⚠️ <b>轴 ② 的逐域事实（B 侧 {@code service/…/api/…service/common/src/main/java/com/rag/common/http/GoStyleFormLimitMode.java:53} / {@code commentservice.go}）</b>：
 * <table border="1">
 *   <tr><th>模式</th><th>域</th><th>wire 形状</th></tr>
 *   <tr><td>{@link #RAW}</td><td>article / user / admin<br>（<b>没注册</b>）</td>
 *       <td><b>HTTP 400</b> + {@code text/plain; charset=utf-8} + {@code nosniff}<br>
 *           + body = B 侧 原文 + {@code "\n"}</td></tr>
 *   <tr><td>{@link #ENVELOPE}</td><td>hot / follow / like / message / comment<br>（<b>注册了</b>）</td>
 *       <td><b>HTTP 200</b> + {@code {"code":5001,"msg":"&lt;该域文案&gt;","data":null}}</td></tr>
 *   <tr><td><b>域自定义</b><br>⇒ 共享层不装 ⇒ {@link #OFF}</td>
 *       <td>favorite<br>（<b>handler 自己捕获</b> {@code httpx.Parse} 的错）</td>
 *       <td><b>HTTP 200</b> + {@code {"code":5003,"msg":"参数错误","data":null}}<br>
 *           （= {@code httpx.OkJsonCtx} + {@code favoritecommon.ErrorInvalidParam}）</td></tr>
 * </table>
 * <p>⚠️ <b>「域自捕获」为什么必须排在第一判据</b>：它<b>推翻</b>了「没注册 {@code SetErrorHandler}
 * ⇒ 就是 400 + text/plain」这条推理 ⇒ ⇒ favorite <b>没注册</b> {@code SetErrorHandler}
 * （实测：全仓 5 个注册点 = like / comment / follow / message / hot）⇒ <b>但</b>它的 7 个 handler
 * <b>各自捕获</b> {@code httpx.Parse} 的错 ⇒ ⇒ 形状是 <b>200 + 5003</b>（既不是 RAW 也不是 ENVELOPE）。
 *
 * <h2>⚠️ 为什么 5001 的文案<b>不能</b>写在共享层</h2>
 * <p>各域 5001 的文案<b>不一样</b>（B 侧逐域取证）：
 * <ul>
 *   <li>hot / follow / message = {@code "internal server error"}；</li>
 *   <li>like / comment = {@code "系统内部错误"}。</li>
 * </ul>
 * ⇒ ⇒ 共享层<b>只抛异常</b>，由<b>各域自己的兜底 handler</b>去查自己的表渲染 ⇒
 * <b>零文案复制、零 per-domain 硬编码</b>。
 * <p>（实测：9 个 api 模块<b>全部</b>有 {@code @ExceptionHandler(Exception.class)} 兜底，
 * 且都渲染成 {@code 200 + {code:5001, 该域查表文案, data:null}}。）
 *
 * <h2>🔴 「缺省」为什么是 {@link #OFF}</h2>
 * <p>{@link #OFF} 是<b>缺省值</b> ⇒ 域没声明时<b>不装闸门</b>。
 * <p>⚠️ 但缺省只对「B 侧<b>不</b>解析」的域才是<b>正确</b>的 ⇒ ⇒ 对「B 侧解析」的域，
 * 缺省 {@code off} <b>就是漂移</b> ⇒ ⇒ 这正是本类要有一张<b>逐域表</b>的原因
 * （漏声明 ⇒ 没人提醒 ⇒ 静默漂移）。
 * <p>⇒ 缺省值本身仍取 {@link #OFF}：它是「<b>未取证</b>」时的<b>安全侧</b>（不装闸门 ⇒ 不误拒）
 * ⇒ ⇒ 而「误拒」比「漏拒」更危险（会挡掉 B 侧 能受理的请求）⇒ ⇒ 这个取舍是有意的。
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/http/GoStyleFormLimitMode.java}
 * §1.12 第 267 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
public final class LimitMode {

    /**
     * 模式属性的 {@code @Value} key —— 值域 {@code off} | {@code raw} | {@code envelope}。
     *
     * <h2>🔴 YAML 里写 {@code off} <b>必须加引号</b>：{@code mode: "off"}</h2>
     *
     * <p>⚠️ <b>YAML 1.1 会把未加引号的 {@code off} 解析成布尔 {@code false}</b>
     * （SnakeYAML 沿用 YAML 1.1 的布尔字面量表：{@code y|Y|yes|Yes|YES|n|N|no|No|NO|
     * true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF}）
     * ⇒ ⇒ 域侧按文档写 {@code mode: off} 时，{@code @Value} 实际拿到的是字符串 {@code "false"}
     * ⇒ ⇒ 而 {@code "false"} <b>不在</b> {@link #parse} 的原合法值域里 ⇒ ⇒
     * <b>构造期就抛 {@link IllegalArgumentException} ⇒ 进程起不来</b>
     * （G2 反馈单 FB-05 的 L4 实测：报错信息里的值是 {@code false} 而不是 {@code off}）。
     * <p>⇒ ⇒ <b>两种正确写法</b>：① {@code mode: "off"}（<b>加引号</b>，推荐）② {@code mode: false}
     * ⇒ ⇒ 本类<b>同时接受这两种</b>（见 {@link #parse}）⇒ ⇒ 域侧<b>按文档写也能用</b>。
     *
     * <p>⚠️ <b>为什么不是「把 {@code off} 从合法值里删掉」</b>：{@code off} 是本类的
     * <b>文档化合法取值</b>（{@link Mode#OFF} 的语义）⇒ 删掉它等于「文档与实现不符」⇒
     * ⇒ 正确方向是<b>让实现接受文档里那个值的实际编码形式</b>，而不是改文档迁就实现。
     */
    public static final String PROPERTY = "rag.http.go-form-limit.mode";

    /** 本域的闸门形状。 */
    public enum Mode {
        /**
         * 不装闸门（<b>但容器上限照抬</b> —— 见下方「🔴 2026-10-27 两轴解耦」）。
         *
         * <p>🔴🔴 <b>2026-10-27：{@code OFF} 只管「装不装闸门」，不管「抬不抬容器上限」</b>
         * <ul>
         *   <li><b>轴 ①（拒绝形状）</b>⇒ 由本枚举管 ⇒ {@code OFF} = <b>共享层不装</b>闸门；</li>
         *   <li><b>轴 ②（判定权归谁）</b>⇒ <b>与本枚举无关</b> ⇒ 容器上限
         *       <b>一律</b>由 B 侧 {@code GoStyleTomcatParameterLimitCustomizer} 抬到 10001
         *       （= B 侧 {@code net/url} 段数上限 10000 + 1）；
         *       C 侧对应 = {@link com.rag.common.autoconfigure.WebAutoConfiguration#TOMCAT_MAX_PARAMETER_COUNT}。</li>
         * </ul>
         * ⇒ ⇒ <b>旧版「{@code OFF} ⇒ 不改容器上限」是错的</b>：它把两件独立的事耦合在了同一个
         * {@code mode} 上 ⇒ ⇒ 造成「OFF 域停在 yaml 的 2048、其余域 10001」的<b>运行期分叉</b>
         * ⇒ ⇒ 而 B 的段数上限是 <b>10000</b> ⇒ Tomcat 停在 2048 会比 B 侧 <b>更早</b>地拒
         * ⇒ ⇒ 两种 OFF 情形都被误伤：
         * <ul>
         *   <li><b>上限不可达</b>（B 侧 {@code r.Form} 从不求值）⇒ 容器不该比 B 侧 更早拒；</li>
         *   <li><b>域自捕获</b>（favorite）⇒ 域内已有 B 侧 口径的应用层判据（数非空值）
         *       ⇒ 判定权<b>应该</b>交还应用层 ⇒ 容器上限<b>应该</b>抬到 10001
         *       ⇒ 否则「Tomcat 总参数值数 vs B 侧 非空值数」的<b>差 1</b>闭不上。</li>
         * </ul>
         *
         * <p>🔴 <b>两种语义完全不同的情形都归它</b>（用 {@code goSideHandlesParseError} 分流）：
         * <ul>
         *   <li><b>上限不可达</b>（B 侧 handler <b>不</b>解析 form/query）
         *       ⇒ ⇒ RAG 与 B 侧 同为「不因 query 而拒」；</li>
         *   <li><b>域自捕获</b>（B 侧 handler <b>自己捕获</b> {@code httpx.Parse} 的错）
         *       ⇒ ⇒ 拒绝形状由<b>域自己</b>定（favorite = 200 + {code:5003,…}）
         *       ⇒ ⇒ <b>共享层绝不能装闸门</b>：装了就会<b>抢先</b>于域自己的捕获
         *       ⇒ ⇒ 形状会变成 400 + text/plain 或 200 + {code:5001} ⇒ ⇒ <b>与 B 侧 不符</b>。</li>
         * </ul>
         * ⚠️ 两种情形虽然都落在 {@code OFF}，但<b>原因不同</b> ⇒ ⇒ 这正是
         * {@link DomainReachability#goSideHandlesParseError()} 这个字段存在的原因
         * （只有它能把两者分开）。
         */
        OFF,
        /** 拒绝成 B 的 {@code http.Error} 形状：<b>400 + text/plain + B 侧 原文</b>。 */
        RAW,
        /** 拒绝成业务信封：<b>抛异常</b> ⇒ 由各域自己的兜底 handler 渲染 200 + {code:5001,…}。 */
        ENVELOPE
    }

    /**
     * 某个 api 域在 B 侧的 <b>form/query 语义可达性</b> —— 闸门「开不开」的<b>唯一判据</b>。
     *
     * @param module            该域的模块路径（相对仓库根，{@code service/…}）
     * @param goSideParsesForm  B 侧该域的 handler 是否<b>真的</b>调 {@code httpx.Parse}（⇒ 解析 form/query）
     * @param goSideHandlesParseError  B 侧该域的 handler 是否<b>自己捕获</b> {@code httpx.Parse} 的错
     *                                  （⇒ 自己捕获 ⇒ 拒绝形状由<b>域自己</b>定 ⇒ 共享层<b>不装</b>闸门）
     * @param semanticMode      由上一行推出的<b>语义</b>模式（<b>共享层该装成什么</b> ⇒ 不是「该域实际该拒成什么形状」）
     * @param goEvidence        B 侧证据（文件:行号 / 命中数）
     */
    public record DomainReachability(
            String module,
            boolean goSideParsesForm,
            boolean goSideHandlesParseError,
            Mode semanticMode,
            String goEvidence) {

        /**
         * 语义模式与「是否解析」是否自洽。
         *
         * <p>🔴 <b>两条自洽规则（按「域是否自己捕获 {@code httpx.Parse} 的错」分流）</b>：
         * <ul>
         *   <li><b>域自己捕获</b>（{@code goSideHandlesParseError == true}）
         *       ⇒ ⇒ 拒绝形状由<b>域自己</b>定（favorite = {@code 200 + {code:5003, msg:"参数错误"}}）
         *       ⇒ ⇒ <b>共享层不装闸门</b> ⇒ 语义模式必须是 {@link Mode#OFF}
         *       ⇒ ⇒ ⚠️ 与「B 侧不解析」的域<b>同样是 OFF，但原因不同</b>
         *       （前者「上限不可达」· 后者「形状由域自定」⇒ 这正是本字段存在的原因）；</li>
         *   <li><b>域不自己捕获</b> ⇒ ⇒ 拒绝形状由<b>共享层</b>决定
         *       ⇒ ⇒ <b>B 侧解析 ⇒ 闸门必须开</b>（{@code OFF} ⇔ 不解析）。</li>
         * </ul>
         */
        public boolean selfConsistent() {
            if (goSideHandlesParseError) {
                // 域自捕获 ⇒ 共享层不参与 ⇒ 语义模式必须是 OFF
                return semanticMode == Mode.OFF;
            }
            return (semanticMode == Mode.OFF) == !goSideParsesForm;
        }
    }

    /**
     * 🔴 <b>10 个 api 域的语义可达性逐域取证表</b>（闸门「开不开」的<b>唯一判据源</b>）。
     *
     * <p>⚠️ <b>这张表是「目标态」，不是「现状快照」</b>：
     * <ul>
     *   <li>域自己的 yaml 里声明的 {@code mode} 是<b>现状</b>；</li>
     *   <li>本表的 {@code semanticMode} 是<b>应当</b>（由 B 侧事实推出）；</li>
     *   <li>两者不一致 ⇒ <b>就是一条待修的漂移</b>（由各域自己改 yaml 修，共享层只记账）。</li>
     * </ul>
     * ⇒ ⇒ 这就是「下一个裸 decoder 域不会重复踩」的机制：
     * <b>新增域时照着这张表填，而不是照抄邻居</b>。
     */
    public static final List<DomainReachability> SEMANTIC_REACHABILITY = List.of(
            new DomainReachability("service/article/article-api", true, false, Mode.RAW,
                    "article/api/internal/handler/**：httpx.Parse 命中 6 处；"
                            + "未注册 httpx.SetErrorHandler ⇒ 400 + text/plain + B 侧 原文"),
            new DomainReachability("service/comment/comment-api", true, false, Mode.ENVELOPE,
                    "comment/api/internal/handler/**：调 httpx.Parse；"
                            + "comment/api/commentservice.go:47 注册了 httpx.SetErrorHandler ⇒ 业务信封"),
            new DomainReachability("service/favorite/favorite-api", true, true, Mode.OFF,
                    "favorite/api/internal/handler/favorite/**：httpx.Parse 命中 7 处；"
                            + "🟢 **7 个 handler 各自捕获 httpx.Parse 的错**"
                            + "（如 get_favorites_by_folder_handler.go:18-22 ⇒ httpx.OkJsonCtx"
                            + " + {Code: favoritecommon.ErrorInvalidParam(=5003), Msg:\"参数错误\", Data:nil}）"
                            + " ⇒ **HTTP 200 + 域自定义业务码信封**（既不是 RAW 的 400+text/plain，"
                            + "也不是 ENVELOPE 的 5001）⇒ 拒绝形状由**域自己**定 ⇒ **共享层不装闸门**"
                            + "（RAG 侧域内**也已自装**：favorite-api 的 FavoriteApiSupport#goFormFirstValue"
                            + " 逐字复刻 B 的 2048「非空值」判据 + #parameterMap 把容器层超限也映射到"
                            + " 同一出口 ⇒ 已与 B 侧 **逐字同形**，见 tools/api-envelope-probe/favorite）"),
            new DomainReachability("service/follow/follow-api", true, false, Mode.ENVELOPE,
                    "follow/api/internal/handler/**：调 httpx.Parse；"
                            + "follow/api/followcenter.go:53 注册了 httpx.SetErrorHandler ⇒ 业务信封"),
            new DomainReachability("service/hot/hot-api", true, false, Mode.ENVELOPE,
                    "hot/api/internal/handler/**：调 httpx.Parse；"
                            + "hot/api/hotcenter.go:53 注册了 httpx.SetErrorHandler ⇒ 业务信封"),
            new DomainReachability("service/like/like-api", true, false, Mode.ENVELOPE,
                    "like/api/internal/handler/**：调 httpx.Parse；"
                            + "like/api/likecenter.go:48 注册了 httpx.SetErrorHandler ⇒ 业务信封"),
            new DomainReachability("service/message/message-api", true, false, Mode.ENVELOPE,
                    "message/api/internal/handler/**：调 httpx.Parse；"
                            + "message/api/messagecenter.go:53 注册了 httpx.SetErrorHandler ⇒ 业务信封"),
            new DomainReachability("service/task/task-api", false, false, Mode.OFF,
                    "task/api/**：httpx.Parse / ParseForm / GetFormValues 命中数 = 0"
                            + "（gettaskhandler.go:19 用 json.NewDecoder(r.Body) + UseNumber，"
                            + "只 import 了 httpx 但只用 httpx.ErrorCtx）"
                            + " ⇒ r.Form 从不被求值 ⇒ 两道上限不可达 ⇒ 语义答案是 off"),
            new DomainReachability("service/users/user/user-api", true, false, Mode.RAW,
                    "user/api/internal/handler/user/**：httpx.Parse 命中 8 处；"
                            + "未注册 httpx.SetErrorHandler ⇒ 400 + text/plain + B 侧 原文"),
            new DomainReachability("service/users/admin/admin-api", true, false, Mode.RAW,
                    "admin/api/internal/handler/admin/**：httpx.Parse 命中 13 处；"
                            + "未注册 httpx.SetErrorHandler ⇒ 400 + text/plain + B 侧 原文"));

    /**
     * 🔴 <b>已登记的偏离</b>：某域的<b>实际模式</b>与本表的<b>语义答案</b><b>不一致</b>，且<b>刻意不改</b>。
     *
     * <p>⚠️ 这里只登记<b>刻意保留</b>的偏离；<b>待修</b>的偏离（各域自己改 yaml）不在这里。
     *
     * <h2>🔴 当前是<b>空表</b>（2026-10-27 · G3 反馈单「轴 ② 判据」订正）</h2>
     * <p>此前这里登记着 favorite 一条，内容是「实际 off vs 语义答案 raw ⇒ 刻意保留的偏离」⇒
     * ⇒ <b>三个前提全错</b>，本轮<b>删除</b>：
     * <ul>
     *   <li><b>① 模式前提错</b>：favorite 的语义答案**就是** {@link Mode#OFF}
     *       （7 个 handler <b>各自捕获</b> {@code httpx.Parse} 的错 ⇒ 拒绝形状由域自己定
     *       ⇒ ⇒ 共享层**不能**装闸门）⇒ ⇒ 「实际 off vs 语义 raw」<b>不成立</b>
     *       ⇒ ⇒ 它<b>不是</b>「模式偏离」⇒ ⇒ 不该登记在这里；</li>
     *   <li><b>② B 侧形状写错</b>：原登记写「B 侧：&gt;2048 个非空 query 值 ⇒ <b>400 + text/plain</b>」
     *       ⇒ ⇒ <b>错</b>：B 侧 favorite 自捕获 ⇒ 落 <b>200 + {code:5003,msg:"参数错误",data:null}</b>
     *       ⇒ ⇒ 真源：{@code service/common/src/main/java/com/rag/common/http/GoStyleFormLimitMode.java:263:16}（ErrorInvalidParam = 5003）
     *       + {@code rest/httpx/responses.go:56-63}（{@code OkJsonCtx} ⇒ HTTP 200）；</li>
     *   <li><b>③ RAG 侧形状写错</b>：原登记写「RAG(OFF)：<b>400 + Tomcat HTML 错误页</b>」
     *       ⇒ ⇒ <b>错</b>：favorite-api 域内已把容器层的超限<b>映射</b>成 B 的那一个出口
     *       ⇒ ⇒ 实测是 <b>200 + {code:5003,…}</b>（真 HTTP 对拍，见
     *       {@code tools/api-envelope-probe/favorite} 与
     *       {@code FavoriteApiRealTomcatGateTest}）⇒ ⇒ <b>与 B 侧 逐字同形</b>。</li>
     * </ul>
     * <p>⚠️ <b>残留的那条真漂移没有被销账</b>（只是<b>不归本表</b>）：
     * favorite 的 form 上限<b>边界口径</b>仍与 B 侧 不同 ——
     * <b>Tomcat 数「总参数值个数（含空值）」，B 侧 数「非空值个数」</b>
     * ⇒ ⇒ 总「值」数 2049~10000 且非空值 ≤2048 时，<b>B 侧 放行 / RAG 拒</b>
     * ⇒ ⇒ 它<b>不是</b>「模式偏离」，而是<b>容器上限口径</b>问题
     * ⇒ ⇒ 归 <b>DRIFT-FAV-API-001</b>（域内，favorite 域自己）+ <b>DRIFT-HOTAPI-030</b>（跨域，收口在 global）。
     *
     * <p>⚠️ <b>为什么不是「删了登记 = 这条漂移不存在了」</b>：本表只承载「<b>模式</b>偏离」
     * ⇒ ⇒ 「favorite 的模式 = OFF 是<b>语义正确</b>的」与「favorite 的<b>边界口径</b>仍有漂移」
     * 是<b>两件不同的事</b> ⇒ ⇒ 后者由上面两条 DRIFT 条目承载。
     *
     * @param module     模块路径
     * @param actualMode 该域<b>实际</b>生效的模式（= 缺省值）
     * @param reason     为什么<b>刻意</b>不改
     * @param observable 可观察差异（写清楚，别只写「不一致」）
     */
    public record RegisteredDeviation(String module, Mode actualMode, String reason, String observable) {
    }

    /**
     * 🔴 刻意保留的偏离（<b>只登记，不改</b>）—— <b>当前为空</b>。
     *
     * <p>⚠️ 空表的含义是「<b>目前没有</b>『实际模式 ≠ 语义答案』且<b>刻意不改</b>的域」，
     * <b>不是</b>「没有漂移」：
     * <ul>
     *   <li><b>待修</b>的漂移（实际 ≠ 语义，但<b>各域自己</b>改 yaml 修）<b>不在</b>本表
     *       ⇒ 例：task 域 yaml 声明 {@code raw}，而语义答案是 {@link Mode#OFF}；</li>
     *   <li><b>边界口径</b>类漂移（与「模式」无关）也不在本表
     *       ⇒ 例：favorite 的「Tomcat 总值 vs B 侧 非空值」（DRIFT-FAV-API-001 / DRIFT-HOTAPI-030）。</li>
     * </ul>
     * ⚠️ <b>下一个 agent 要新增登记时</b>：必须先<b>自己逐行核对前提</b>（两侧确实不同）
     * ⇒ ⇒ 再登记 ⇒ ⇒ 且「observable」必须写<b>可观察差异</b>（状态码 / 响应体形状），
     * 不能只写「不一致」。
     */
    public static final List<RegisteredDeviation> REGISTERED_DEVIATIONS = List.of();

    private LimitMode() {
    }

    /**
     * 把属性值解析成 {@link Mode}。
     *
     * <p>🔴 <b>非法值一律抛异常，不静默兜底</b>（§3.3 同款：不报错的失效）。
     * <p>⚠️ 静默兜底的后果是「以为装上了、其实没装」或「以为走信封、其实走了 400 原文」
     * ⇒ 两者都是<b>不可见的行为漂移</b> ⇒ 宁可启动失败。
     *
     * <h2>🟢 唯一的别名：{@code "false"}（YAML 1.1 里 {@code off} 的布尔形态）</h2>
     *
     * <p>{@code "false"} <b>不是</b>「非法值兜底」，而是<b>精确匹配</b>：
     * YAML 1.1（SnakeYAML）把未加引号的 {@code off}（以及 {@code no} / {@code n}）
     * 解析成布尔 {@code false} ⇒ ⇒ {@code "false"} 是
     * <b>文档化合法取值 {@code off} 的「唯一实际编码形式」</b>
     * ⇒ ⇒ 接受它 = 让实现<b>对齐文档</b>，而不是放宽合法性。
     *
     * <p>⚠️ <b>为什么不放宽成「任意非法值都当 OFF」</b>：那会<b>重新引入</b>
     * 「静默兜底 = 以为装上了其实没装」（本方法 javadoc 明确反对的那种失效）
     * ⇒ ⇒ 例：把 {@code mode: raw} 误写成 {@code mode: OFF1} ⇒ 若兜底成 OFF
     * ⇒ ⇒ 该域会<b>静默失去闸门</b>（且「多记 413」方向的错误更难察觉）⇒ ⇒ 仍然必须炸。
     *
     * @param raw 属性原值（{@code null} / 空串 ⇒ {@link Mode#OFF}）
     * @throws IllegalArgumentException 值不在 {@code off|false|raw|envelope} 内
     */
    public static Mode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Mode.OFF;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        switch (v) {
            case "off":
                return Mode.OFF;
            case "false":
                // YAML 1.1：未加引号的 off / no / n ⇒ 布尔 false（G2 反馈单 FB-05 的 L4 实测）
                // ⇒ 域侧按文档写 `mode: off` 时拿到的就是它 ⇒ 必须与 "off" 同义
                return Mode.OFF;
            case "raw":
                return Mode.RAW;
            case "envelope":
                return Mode.ENVELOPE;
            default:
                throw new IllegalArgumentException(
                        "非法的 " + PROPERTY + " 值：" + raw + "（只允许 off | false | raw | envelope；"
                                + "注意 YAML 里写 off 必须加引号：mode: \"off\"，否则会被 YAML 1.1 解析成布尔 false）");
        }
    }

    /** 某个模块路径的语义可达性记录（找不到 ⇒ {@code null}）。 */
    public static DomainReachability reachabilityOf(String module) {
        for (DomainReachability r : SEMANTIC_REACHABILITY) {
            if (r.module().equals(module)) {
                return r;
            }
        }
        return null;
    }

    /** 某个模块路径的<b>语义模式</b>（找不到 ⇒ {@code null} ⇒ 调用方必须自己处理「未知域」）。 */
    public static Mode semanticModeOf(String module) {
        DomainReachability r = reachabilityOf(module);
        return r == null ? null : r.semanticMode();
    }

    /** 某个模块路径是否登记了「刻意保留的偏离」。 */
    public static boolean hasRegisteredDeviation(String module) {
        for (RegisteredDeviation d : REGISTERED_DEVIATIONS) {
            if (d.module().equals(module)) {
                return true;
            }
        }
        return false;
    }

    /** 模块路径 → 语义可达性（便于按域查；{@link List} 保持声明顺序）。 */
    public static Map<String, DomainReachability> reachabilityIndex() {
        Map<String, DomainReachability> index = new LinkedHashMap<>();
        for (DomainReachability r : SEMANTIC_REACHABILITY) {
            index.put(r.module(), r);
        }
        return Map.copyOf(index);
    }
}
