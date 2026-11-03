package com.rag.task.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.util.Assert;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * <p><b>让 {@code @RequestBody} 一律按 JSON 读原始 body</b> —— 对齐 Go 的裸 decoder。</p>
 *
 * <h2>Go 出处</h2>
 * {@code service/task/task-api/src/main/java/com/rag/task/task/api/internal/handler/task/gettaskhandler.go:18-24}：
 * <pre>
 * var req types.GetTaskReq
 * decoder := json.NewDecoder(r.Body)   // ← 裸读 body，没有任何 Content-Type 判据
 * decoder.UseNumber()
 * if err := decoder.Decode(&amp;req); err != nil {
 *     httpx.ErrorCtx(r.Context(), w, err)
 *     return
 * }
 * </pre>
 * ⇒ Go 对 body 的唯一要求是「它是一段 JSON」。**Content-Type 是什么，Go 一概不看。**
 *
 * <h2>为什么 Spring 默认做不到</h2>
 * <p>Spring MVC 取 body 的路径是
 * {@code RequestResponseBodyMethodProcessor#resolveArgument} →
 * {@code AbstractMessageConverterMethodArgumentResolver#readWithMessageConverters} →
 * {@code createInputMessage(webRequest)} →
 * {@code new ServletServerHttpRequest(servletRequest)} →
 * {@code inputMessage.getBody()}。
 *
 * <p>最后一环有问题。spring-web **6.2.16** 的
 * {@code org/springframework/http/server/ServletServerHttpRequest.java}：
 * <pre>
 * public InputStream getBody() throws IOException {
 *     if (isFormPost(this.servletRequest) &amp;&amp; this.servletRequest.getQueryString() == null) {
 *         return getBodyFromServletRequestParameters(this.servletRequest);  // ← 不读原始 body
 *     }
 *     else {
 *         return this.servletRequest.getInputStream();
 *     }
 * }
 * </pre>
 * 而 {@code isFormPost} 判的是
 * {@code contentType.contains("application/x-www-form-urlencoded") &amp;&amp; POST}，
 * {@code getBodyFromServletRequestParameters} 做的是
 * <pre>
 * Map&lt;String, String[]&gt; form = request.getParameterMap();   // ← 触发 Tomcat 表单解析，body 被消费
 * writer.write(URLEncoder.encode(entry.getKey(), charset));    // ← % 的来源
 * ...
 * </pre>
 * ⇒ 对 {@code Content-Type: application/x-www-form-urlencoded} 的 POST，
 * <b>Spring 根本不把原始 body 交给 Jackson</b>：它把 body 当表单解析成参数，
 * 再把参数 URL 编码后拼成 {@code key=value} 交下去。
 *
 * <h3>🔴 为什么「在转换器里绕开」是<b>来不及</b>的</h3>
 * 因为 <b>body 在转换器被调用之前就已经被换掉了</b>。spring-webmvc **6.2.16** 的
 * {@code AbstractMessageConverterMethodArgumentResolver} 在构造
 * {@code EmptyBodyCheckingHttpInputMessage} 时就消费了一次 body ⇒
 * 转换器拿到的 {@code HttpInputMessage} <b>不是</b> {@code ServletServerHttpRequest}
 * ⇒ 转换器层拿不到原始 request，也拿不到原始 body。
 * ⇒ 修法必须落在 <b>造出 {@code HttpInputMessage} 的那一环</b>。
 *
 * <h2>本类的修法</h2>
 * 覆盖 {@code createInputMessage(NativeWebRequest)}（父类
 * {@code AbstractMessageConverterMethodArgumentResolver}，protected），
 * 返回一个 {@code getBody()} 直读原始输入流的 {@code ServletServerHttpRequest} 子类。
 *
 * <h3>语义等价论证</h3>
 * <ol>
 *   <li><b>非 form POST 的请求行为完全不变</b>：Spring 自己的
 *       {@code getBody()} 在 {@code isFormPost} 为假时走的也是
 *       {@code servletRequest.getInputStream()}，与本类逐字相同 ⇒ 无差异。</li>
 *   <li><b>form POST 的请求：</b>本类让 body 原样流到 Jackson；
 *       Go 侧 {@code json.NewDecoder(r.Body)} 也是原样读同一段字节 ⇒ 两侧解析的是<b>同一段字节</b>。</li>
 *   <li><b>副作用面</b>：不再触发 Tomcat 的表单参数解析 ⇒
 *       {@code request.getParameter*()} 对这类请求返回空。
 *       本域<b>唯一</b>的端点（{@code routes.go:15-25}）不读任何 form 参数，
 *       且入参全部来自 JSON body ⇒ 无影响。</li>
 * </ol>
 *
 * <p>⚠️ <b>本类只改「body 从哪来」，其余一律沿用 Spring</b>：
 * 空 body 检查、{@code @RequestBody} 必填校验、异常翻译（{@code HttpMessageNotReadableException}）、
 * {@code RequestBodyAdvice} 链，全部是父类的原逻辑 ⇒ 不引入新的错误形态。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>Spring 7 → Spring 6.2 的 API 面回改</b>（D14）：
 *             B 侧 {@code super(converters, null, advice)} 用的是 Spring 7 的三参构造；
 *             本仓 Spring 6.2.16 的三参构造
 *             {@code (List, ContentNegotiationManager, List<Object>)} <b>形状相同</b> ⇒ 逐字沿用；</li>
 *         <li><b>Spring 6.2 的读侧不使用内容协商器</b>：{@code contentNegotiationManager}
 *             只在<b>写侧</b>（{@code writeWithMessageConverters}）被读，
 *             而本处理器<b>只作为参数解析器</b>使用（Spring 的默认返回值处理器是
 *             {@code HttpEntityMethodProcessor}）⇒ 传 {@code null} <b>无副作用</b>；</li>
 *         <li>包名压平（{@code …/task/api/config} → {@code com.rag.task.config}，D7）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 覆盖点（{@code createInputMessage}）、
 *       子类的唯一改动（{@code getBody()}）与 B 侧逐字相同。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/config/GoStyleRawBodyArgumentProcessor.java:100（类声明）
 * B 出处：…/config/GoStyleRawBodyArgumentProcessor.java:108-115（构造器）
 * B 出处：…/config/GoStyleRawBodyArgumentProcessor.java:117-122（createInputMessage 覆写）
 * B 出处：…/config/GoStyleRawBodyArgumentProcessor.java:128-145（RawBodyServletServerHttpRequest.getBody）
 *       </pre></li>
 * </ol>
 */
public class GoStyleRawBodyArgumentProcessor extends RequestResponseBodyMethodProcessor {

    /**
     * @param converters              与 Spring 默认装配给默认处理器的是<b>同一个</b>转换器列表
     * @param requestResponseBodyAdvice Spring 的 {@code RequestBodyAdvice} 链，允许为 {@code null}
     *                                  （本域无任何 {@code RequestBodyAdvice} 实现 ⇒ 传 {@code null}；
     *                                  装配处有断言兜底，见 {@link TaskApiRawBodyConfig}）
     */
    public GoStyleRawBodyArgumentProcessor(List<HttpMessageConverter<?>> converters,
                                           List<Object> requestResponseBodyAdvice) {
        // ⚠️ 走 (converters, manager, advice) 三参构造 —— 与 Spring 6.2.16 给默认处理器用的
        //    `new RequestResponseBodyMethodProcessor(getMessageConverters(), contentNegotiationManager, advice)`
        //    形状一致（javap 实测：spring-webmvc-6.2.16）。
        //    ⚠️ manager 传 null 在本仓是安全的：该字段只在**写侧**被读，
        //    而本处理器只作为**参数解析器**使用（Spring 的默认返回值处理器是 HttpEntityMethodProcessor）。
        super(converters, (org.springframework.web.accept.ContentNegotiationManager) null,
                requestResponseBodyAdvice);
    }

    @Override
    protected ServletServerHttpRequest createInputMessage(NativeWebRequest webRequest) {
        HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
        Assert.state(servletRequest != null, "No HttpServletRequest");
        return new RawBodyServletServerHttpRequest(servletRequest);
    }

    /**
     * 只把「body 从哪来」换成原始输入流，<b>其余行为全部沿用 {@code ServletServerHttpRequest}</b>
     * （headers、其它方法一个没动）。
     */
    static final class RawBodyServletServerHttpRequest extends ServletServerHttpRequest {

        RawBodyServletServerHttpRequest(HttpServletRequest servletRequest) {
            super(servletRequest);
        }

        /**
         * ⚠️ 刻意<b>不</b>复刻 Spring 的 {@code isFormPost} 判据 ——
         * 那个分支存在的唯一目的就是「从已解析的表单参数里重建 body」，
         * 而本服务要的是<b>原始字节</b>（Go 的 {@code json.NewDecoder(r.Body)} 语义）。
         * <p>唯一被改掉的行为面就是「form POST 不再触发 Tomcat 表单解析」，
         * 理由见本类 javadoc 的「语义等价论证」第 3 条。
         */
        @Override
        public InputStream getBody() throws IOException {
            return getServletRequest().getInputStream();
        }
    }
}
