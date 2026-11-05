package com.rag.favorite.interceptor;

import com.rag.common.web.GetFormValuesFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 🔴 <b>favorite 域的 Go query 判据闸门</b> —— 逐字复刻 go-zero {@code rest/httpx} 的
 * {@code GetFormValues} ＋ Go 标准库 {@code net/url} 的 {@code parseQuery}，
 * 并把「拒成什么形状」交给<b>本域</b>（favorite 的形状 = HTTP 200 + {@code {code:5003}}）。
 *
 * <h2>🔴 它守的是什么漂移（2026-10-29 · G3 阶段 1 实测发现）</h2>
 * <p><b>Go 侧 7/7 端点都吃这四条规则</b>，因为 7 个 handler 的第一行都是
 * {@code httpx.Parse(r, &req)}（真源 {@code service/favorite/api/internal/handler/favorite/} 下
 * {@code create_favorite_folder_handler.go:18} · {@code create_favorite_handler.go:18} ·
 * {@code delete_favorite_folder_handler.go:18} · {@code delete_favorite_handler.go:18} ·
 * {@code get_favorite_folders_handler.go:18} · {@code get_favorites_by_folder_handler.go:18} ·
 * {@code update_favorite_folder_name_handler.go:18}）
 * ⇒ ⇒ {@code httpx.Parse}（{@code rest/httpx/requests.go:50-68}）
 * → {@code ParseForm}（{@code :85-92}）→ {@code GetFormValues}（{@code rest/httpx/util.go:22-65}）
 * ⇒ ⇒ <b>每个请求</b>都要过一遍 Go 的 query 解析规则。
 * <p>⇒ <b>修之前的 RAG 侧只覆盖 1/7 端点</b>：域内那套 Go 口径判据挂在
 * {@code FavoriteApiSupport#goFormFirstValue} 上，而它<b>只被 form 通道那一个端点</b>调用
 * （{@code FavoriteController.java:197}，即 {@code GET /favorite/v1/item/list}）
 * ⇒ ⇒ <b>另外 6 个 JSON body 端点从不计数</b>
 * ⇒ ⇒ 同一个输入下：<b>Go 拒（200 + 5003）/ RAG 放行进业务层</b> ⇒ ⇒ 可观察的行为漂移。
 *
 * <h2>Go 的四条规则（真源 · 消费点，不是声明点）</h2>
 * <pre>
 *   ① net/url/url.go:1019-1022  parseQuery —— **先**判段数
 *        if !urlParamsWithinMax(strings.Count(query, "&amp;") + 1) {
 *            return errors.New("number of URL query parameters exceeded limit")
 *        }
 *      ⇒ 段数 = {@code Count(rawQuery, "&amp;") + 1} ⇒ 放行 10000 段、第 10001 段拒
 *      ⇒ ⚠️ 它在**解析之前**就 return ⇒ ⇒ 一个参数都不解析 ⇒ ⇒ 顺序**可观察**
 *
 *   ② net/url/url.go:1026-1029  段循环里的分号规则
 *        if strings.Contains(key, ";") { err = fmt.Errorf("invalid semicolon separator in query"); continue }
 *      ⇒ rawQuery 任一处含 {@code ';'} 即拒 ⇒ ⚠️ 该赋值**无条件覆盖**前一个错误
 *      ⇒ ⚠️ 判的是**原始**串 ⇒ {@code %3B}（已转义）**不**触发
 *
 *   ③ net/url/url.go:1035-1046  非法转义
 *        key, err1 := QueryUnescape(key)   // 失败 ⇒ err = err1（**只在 err 仍为 nil 时**）
 *        value, err1 := QueryUnescape(value)
 *      ⇒ {@code ?a=%zz} ⇒ {@code invalid URL escape "%zz"}
 *      ⇒ ⇒ 沿 {@code rest/httpx/util.go:23-25} 传播到 {@code httpx.Parse} ⇒ 解析失败
 *
 *   ④ go-zero@v1.10.2/rest/httpx/util.go:33-54  go-zero 自己的计数
 *        var n int
 *        for name, values := range r.Form {
 *            for _, v := range values {
 *                if len(v) == 0 { continue }          // ← 空值不计数
 *                if n &lt; maxFormParamCount { filtered = append(filtered, v); n++ }
 *                else { return nil, fmt.Errorf("too many form values, error: %s", r.Form.Encode()) }
 *            }
 *        }
 *      ⇒ {@code maxFormParamCount = 2048}（{@code util.go:15}）⇒ 放行 2048 个**非空值**、
 *        第 2049 个报错 ⇒ ⚠️ 计数器 {@code n} **跨所有 key 共享**
 * </pre>
 *
 * <h2>🔴 为什么判据取自共享层、本类只负责「渲染形状」</h2>
 * <p>本类<b>不自己抄这四条规则</b>，而是调共享层的<b>判据</b>
 * {@link GetFormValuesFilter#goParseViolation(String)}（只读调用，不改共享层）
 * ⇒ ⇒ 理由有三条：
 * <ol>
 *   <li><b>根因治理</b>：漂移的根因就是「<b>域内手写 Go 规则 ⇒ 只抄到一半</b>」
 *       （原先域内只手写了 ①②，漏了 ③④，且漏在**覆盖面**上 ⇒ 6/7 端点裸奔）
 *       ⇒ ⇒ 判据只有一份（共享层那份已被其它域在真 Tomcat 上验过）⇒ ⇒ 不会再抄漏。</li>
 *   <li><b>共享层的判据是「判据」不是「闸门」</b>：它只回答「按 Go 的语义该不该拒、原文是什么」，
 *       <b>拒成什么形状由各域自己决定</b>
 *       ⇒ ⇒ favorite 正好是「<b>形状由域自己定</b>」的那一档（7 个 handler 各自捕获
 *       {@code httpx.Parse} 的错 ⇒ 200 + 5003）⇒ ⇒ 本类正好是它的目标用户。</li>
 *   <li><b>不能开共享层那道闸门</b>：共享层的 {@code ValueLimitInterceptor}
 *       在 {@code ENVELOPE} 模式下抛 {@code LimitExceededException}
 *       ⇒ ⇒ 落到各域的兜底 handler ⇒ 渲染的是 <b>5001</b>（"系统内部错误"）
 *       ⇒ ⇒ 而 favorite 要的是 <b>5003</b>（"参数错误"）⇒ ⇒ 形状不对
 *       ⇒ ⇒ 且 favorite 的 yaml <b>不得</b>声明该属性（共享守门
 *       {@code tools/difftest/check-tomcat-limits.py} 的
 *       {@code FORBIDDEN_FORM_LIMIT_MODE_MODULES} 明确列了 {@code service/favorite/favorite-api}
 *       ⇒ ⇒ <b>必须不存在</b>）⇒ ⇒ 本域自装闸门是<b>唯一</b>正确形态。</li>
 * </ol>
 *
 * <h2>🔴 拒绝形状 = 本域的形状</h2>
 * <p>抛 {@link IllegalArgumentException} ⇒ 落到本域
 * {@code FavoriteApiExceptionHandler#handleIllegalArgument} ⇒ {@code invalidParam()}
 * ⇒ ⇒ <b>HTTP 200 + {"code":5003,"msg":"参数错误","data":null}</b>
 * ⇒ ⇒ 与 Go 的 {@code httpx.OkJsonCtx(r.Context(), w, &amp;response.Response{Code: ErrorInvalidParam})}
 * <b>逐字同形</b>（Go 侧 {@code get_favorites_by_folder_handler.go:18-24}）
 * ⇒ ⇒ ⚠️ Go 的原文（{@code too many form values, error: …} 等）<b>不进 wire</b>
 * （Go 侧同理：{@code httpx.OkJsonCtx} 只取 code/msg）。
 *
 * <h2>🔴 次序 = Go 的次序</h2>
 * <p>Go 的 {@code httpx.Parse} 在 <b>handler 内部</b> ⇒ ⇒ 解析<b>在鉴权之后</b>
 * ⇒ ⇒ 本拦截器注册在 {@code order = 1}（鉴权拦截器 {@code FavoriteJwtAuthInterceptor}
 * 是 {@code order = 0}）⇒ ⇒ <b>无 token ⇒ 401 优先</b>（Go 同）⇒ ⇒ 不制造「先解析后鉴权」的偏离。
 * <br>⚠️ 共享层那道闸门排的是 {@code HIGHEST_PRECEDENCE}（先于鉴权）⇒ ⇒ 那条偏离已登记在共享层台账，
 * <b>本域不复制</b>。
 *
 * <h2>为什么用原始 query 串（{@code getQueryString()}）而不是 {@code getParameterMap()}</h2>
 * <ul>
 *   <li>不触发 Tomcat 的 query 解析 ⇒ ⇒ 不与容器上限（运行期 10001）纠缠
 *       ⇒ ⇒ 计数口径与 Go <b>逐字一致</b>；</li>
 *   <li>Go 的 {@code r.Form} 对本域 = <b>只有 query</b>：7 个端点里 6 个是 JSON body
 *       （{@code net/http} 的 {@code parsePostForm} 只在 {@code application/x-www-form-urlencoded}
 *       时读 body ⇒ JSON body 不进 {@code r.Form}），1 个是 GET ⇒ ⇒ 口径等价；</li>
 *   <li>共享层的判据同样只数 query，理由与本类一致（见该类 javadoc「🔴 为什么只统计 query string」）。</li>
 * </ul>
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li><b>类名</b>：B 侧 {@code FavoriteGoQueryLimitInterceptor}
 *           ⇒ C 侧 {@code QueryLimitInterceptor}（<b>D7 包压平</b>：域内包名不再带域前缀），
 *           并从 {@code interceptor/} 的<b>域前缀</b>形态改为 C 侧 {@code File_Tree} 钉死的名字。</li>
 *       <li><b>判据的落点</b>：B 侧调 {@code com.rag.common.http.GoStyleFormValues#goParseViolation}
 *           ⇒ C 侧调共享层的 {@code com.rag.common.web.GetFormValuesFilter#goParseViolation}
 *           （<b>同一份判据的共享层落点</b>，语义逐字相同）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 四条规则的判据、判定顺序、拒绝形状、拦截器次序逐条未改。</li>
 *   <li><b>B 出处</b>：B 侧
 *       {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/interceptor/FavoriteGoQueryLimitInterceptor.java:91-131}。</li>
 * </ol>
 *
 * <p><b>证据强度</b>：L1 真源逐行（go-zero v1.10.2 {@code rest/httpx/util.go} ＋ {@code requests.go}
 * ＋ Go 1.25 {@code net/url/url.go}）＋ 本域真 Tomcat 门禁（真容器 + 真 JWT + 真 HTTP 客户端）。
 */
@Component
public class QueryLimitInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 🔴 只读调用共享层的「判据」⇒ 它只回答「该不该拒」⇒ 形状由本域渲染（见类注释）。
        // ⚠️ 判据内部已按 Go 的次序排好（段数 → 解析错误 → 非空值计数）⇒ 本类不重排。
        String violation = GetFormValuesFilter.goParseViolation(request.getQueryString());
        if (violation != null) {
            // 🔴 抛 IllegalArgumentException（不是 BusinessException）⇒
            //    落到本域 FavoriteApiExceptionHandler#handleIllegalArgument ⇒ 200 + 5003
            //    ⇒ ⚠️ 不能把 Go 原文塞进信封 msg（那是共享层的教训，见 ValueLimitInterceptor 的类注释）
            throw new IllegalArgumentException(violation);
        }
        return true;
    }
}
