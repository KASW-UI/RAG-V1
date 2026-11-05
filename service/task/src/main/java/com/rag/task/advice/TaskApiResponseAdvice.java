package com.rag.task.advice;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * <p><b>成功路径的「不包装」守卫</b> —— 本域<b>刻意不</b>给成功响应加信封。
 *
 * <h2>🔴 为什么 B 侧没有信封（这是 L1 契约，不是遗漏）</h2>
 * <p>Go 的 {@code api/task.go} 全文 37 行，<b>没有调用 {@code httpx.SetOkHandler}</b>。
 * 于是在 go-zero v1.10.2 {@code rest/httpx/responses.go:56-64}：
 * <pre>
 *   func OkJsonCtx(ctx, w, v) {
 *       ...
 *       if handlerCtx != nil { v = handlerCtx(ctx, v) }   // ← handlerCtx 为 nil，不包装
 *       WriteJsonCtx(ctx, w, http.StatusOK, v)          // :63 直接写原始对象
 *   }
 * </pre>
 * ⇒ 成功响应体就是 {@code {"tasks":[...]}}，<b>没有</b> {@code {code,msg,data}}。
 * <p>对比：{@code like/api/likecenter.go:41-47} <b>注册了</b> okHandler（返回信封），
 * {@code hot/api/hotcenter.go:53}、{@code message/api/messagecenter.go:53} 同理。
 * <b>把那几个域的 ResponseAdvice 照抄到本模块就是漂移</b>（AGENTS.md §3.1
 * 「不要拿另一个域的实测结论当对照基准」）。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li>B 侧<b>没有这个类</b>（{@code task-api} 无任何 ResponseAdvice）
 *             ⇒ C 侧本类是<b>新增的「否定式守卫」</b>，<b>不是</b> B 侧能力的翻译；</li>
 *         <li>它的<b>唯一职责</b>是：把「本域成功响应<b>不</b>包信封」这条决定
 *             <b>钉在代码里</b>，并让任何「顺手加信封」的改动在本类处立刻可见
 *             （而不是等到联调才发现 wire 形状变了）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>一样</b> —— 本类<b>不改变任何响应字节</b>：
 *       {@code beforeBodyWrite} 原样返回 body ⇒ 与「没有这个类」<b>逐字节相同</b>。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/advice/  ← 该包下**只有** GlobalExceptionHandler
 * B 出处：service/task/task-api/src/main/resources/application.yml      ← 无 okHandler / 无 envelope 相关配置
 * B 出处（对照）:service/like/task-api/src/main/java/com/rag/like/like/api/advice/GlobalResponseAdvice.java
 *                 ← like 域**有**信封 ⇒ 那是**另一个域**的做法，本域**不继承**
 *       </pre></li>
 * </ol>
 *
 * <h2>⚠️ 为什么「返回原样」而不是「干脆不实现这个接口」</h2>
 * <p>本类<b>实现</b> {@link ResponseBodyAdvice} 并原样返回 body ⇒ 效果等价于「不存在」，
 * 但<b>多了一个可读的落点</b>：
 * <ul>
 *   <li>后人想在 task 域加信封时，<b>必须先改这个类</b> ⇒ 改动出现在 diff 里，
 *       而不是悄悄出现在某个 {@code @RestControllerAdvice} 里；</li>
 *   <li>本类的 javadoc 里写着「B 侧没有 {@code httpx.SetOkHandler}」这条<b>证据</b>
 *       ⇒ 复核人不必去 B 仓翻。</li>
 * </ul>
 * <p>⚠️ <b>本类绝不能包装 body</b>：一旦包上，{@code {"tasks":[...]}} 会变成
 * {@code {"code":200,"msg":"OK","data":{"tasks":[...]}}} ⇒ 客户端按 {@code tasks} 取值会拿到
 * {@code undefined} ⇒ <b>整条 HTTP 契约断链</b>。
 */
@RestControllerAdvice
public class TaskApiResponseAdvice implements ResponseBodyAdvice<Object> {

    /**
     * 本域<b>认领</b>所有响应（{@code true}）—— 但 {@link #beforeBodyWrite} 原样返回。
     *
     * <p>⚠️ 写 {@code true} 而不是 {@code false}：{@code false} 会让本类<b>彻底不被调用</b>，
     * 那就退化成「一个没人读的类」⇒ 反而丢掉了「本域刻意不包信封」这条可读的落点。
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /**
     * <b>原样返回 body</b> —— 本域<b>不</b>加任何信封。
     *
     * <p>⚠️ 本方法<b>绝不能</b>改成「包一层」：理由见类注释。
     *
     * @param body                  容器即将写出的 body
     * @param returnType            handler 方法的返回类型
     * @param selectedContentType   选定的媒体类型
     * @param selectedConverterType 选定的转换器
     * @param request               当前请求
     * @param response              当前响应
     * @return <b>原样的 body</b>（{@code null} 也原样返回）
     */
    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        // 🔴 刻意**不包装**：B 侧 task-api 未注册 httpx.SetOkHandler
        //    ⇒ 成功响应就是 {"tasks":[...]}（详见类注释）。
        return body;
    }
}
