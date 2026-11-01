package com.rag.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 🔴🔴 **T-6**：让 <b>JSON 响应</b>的 {@code Content-Type} 与 B 侧 一致地带上 {@code charset=utf-8}。
 *
 * <h2>B 出处（真源，逐行）</h2>
 * <pre>
 * B 出处：service/common/src/test/java/com/rag/common/http/GoStyleJsonContentTypeCharsetTomcatTest.java:42:175-193（doWriteJson）
 *     w.Header().Set(ContentType, header.ContentTypeJson)   // ← 无条件覆盖
 *
 * B 出处：service/common/src/test/java/com/rag/common/http/GoStyleJsonContentTypeCharsetTomcatTest.java:51:16-17
 *     ContentType     = "Content-Type"
 *     ContentTypeJson = "application/json; charset=utf-8"     // ← 分号后**有空格**
 * </pre>
 *
 * <h2>🔴 RAG 侧修前的形态（真 Tomcat 实测）</h2>
 * <pre>
 * 修前：Content-Type: application/json          ← **连 charset 都没有**
 * 修后：Content-Type: application/json;charset=utf-8
 * B 侧  ：Content-Type: application/json; charset=utf-8
 * </pre>
 * <h2>⚠️ 为什么本类挂在 <b>ResponseBodyAdvice</b> 而不是转换器上</h2>
 * <ul>
 *   <li>共享层里那个 JSON 转换器（{@link GoStyleJsonBodyConverter}）<b>不是所有 api 都启用</b>：
 *       {@code task-api} 明确把它排除在组件扫描之外
 *       （{@code TaskApiApplication} 的 {@code @ComponentScan} exclude）⇒
 *       挂在它上面 ⇒ <b>task-api 不生效</b> ⇒ 不满足「改 1 处 ⇒ 10 个 api 全部生效」。</li>
 *   <li>Advice 是<b>按「响应体是不是 JSON」判定</b>的 ⇒ <b>与用哪个转换器无关</b>
 *       ⇒ 无论最终是共享的转换器、Boot 的转换器、还是 task-api 自己那个 ⇒ 都生效。</li>
 * </ul>
 *
 * <h2>🔴🔴 写的值 = <b>Spring 自己会写的值</b>（不是 B 的字面量）</h2>
 * <p>本类写 <b>{@code application/json;charset=utf-8}（无空格）</b>，
 * 也就是 {@code MediaType.toString()} 对 {@code application/json + UTF-8} 的输出。
 * ⇒ 理由见 {@link #GO_JSON_CONTENT_TYPE} 的 javadoc（<b>MockMvc 路径零变化</b>是关键）。
 *
 * <h2>⚠️⚠️ 但「分号后那个空格」在 Tomcat 11 上<b>拿不到</b>（实测坐实）</h2>
 * <pre>
 * org/apache/catalina/connector/Response.java（11.0.24）setContentType(String)
 *     String[] parsedType = MediaTypeCache.parse(type);
 *     if (parsedType == null) { coyoteResponse.setContentTypeNoCharset(type); return; }
 *     if (parsedType[1] == null) {          // 没有 charset
 *         coyoteResponse.setContentTypeNoCharset(parsedType[0]);
 *     } else {                               // 有 charset ⇒ **拆开存**
 *         coyoteResponse.setContentTypeNoCharset(parsedType[0]);
 *         coyoteResponse.setCharsetHolder(CharsetHolder.getInstance(parsedType[1]));
 *     }
 *
 * org/apache/coyote/Http11OutputBuffer.writeHeaders()
 *     headerBuffer.put(contentType);
 *     headerBuffer.put(Constants.SEMICOLON);    // ← ";"  **后面没有空格**
 *     headerBuffer.put(Constants.CHARSET);      // ← "charset="
 * </pre>
 * ⇒ 容器把 Content-Type <b>拆成 (type/subtype, charset) 两份存</b>，写出去时<b>重新拼</b>
 * ⇒ 线上恒为 <b>{@code type/subtype;charset=xxx}</b>（<b>无空格</b>）
 * ⇒ ⇒ <b>应用层无法产出 B 侧 那个带空格的形态</b>（{@code setContentType} /
 *      {@code setHeader} / {@code addHeader} 三条路都汇到同一处归一化）。
 * <p>⇒ 这条「差一个空格」的残余**属框架限制**（与 T-7「状态行缺 reason phrase」同族），
 * **已登记**在 global 台账，不在本类的职责内。
 * <p>⇒ ⚠️ **顺带订正两条旧结论**：
 * <ol>
 *   <li>{@code RouterErrorResponses} 的类注释写着「直接写原始字符串是唯一能保证逐字节对上的做法」
 *       ⇒ <b>该结论已被实测证伪</b>（容器照样归一化）⇒ 那条注释已改写；</li>
 *   <li>🔴 <b>本类自己也踩过同一个坑</b>：初版写的是 B 的<b>带空格</b>字面量
 *       ⇒ 真 Tomcat 路径没事（照样归一化），但 <b>MockMvc 路径没有容器归一化</b>
 *       ⇒ 各域 {@code *MvcPipelineTest} 钉的「Spring 自己那个形态」就<b>被共享层改动打红</b>
 *       ⇒ 实测先打红的是 favorite-api（该域对 global <b>零写权</b> ⇒ 修法只能落在本类）⇒ 已改为无空格形态。</li>
 * </ol>
 *
 * <h2>判定规则（为什么不用「看 Content-Type」而是「看转换器」）</h2>
 * <p>两条取<b>或</b>：
 * <ol>
 *   <li><b>选中的是 Jackson 转换器</b> ⇒ 响应体就是 JSON ⇒ 应当带 charset；</li>
 *   <li><b>选中的 Content-Type 是 JSON</b> ⇒ 即使转换器不是 Jackson，body 仍是 JSON。</li>
 * </ol>
 * ⇒ 404 / 405 那条 {@code ResponseEntity<byte[]>}（{@code ByteArrayHttpMessageConverter}
 * ＋ 显式 {@code text/plain}）<b>两条都不满足</b> ⇒ <b>不会被误伤</b>。
 *
 * @see GoStyleJsonContentTypeCharsetTomcatTest 真 Tomcat 守门（裸 socket 逐字节比对）
 * <p>⚠️ <b>2026-10-30 · G4 lane：本类属 api 侧 rest 中间件 ⇒ 已挂 （D1 合并后已移除，见类注释）</b>
 * ⇒ ⇒ 此前「rpc 域不注册本类」的依据是<b>域侧不扫 {@code com.rag.common} 这道约定</b>（不是本类的条件）
 * ⇒ ⇒ 现由共享层自己兜住 ⇒ ⇒ B 出处与语义等价论证见该注解。
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/http/GoStyleJsonContentTypeAdvice.java}
 * §1.12 第 269 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
/**
 * <p><b>⚠️ D1 落地差异（去 {@code @ConditionalOnApiSide}）</b>：RAG 侧本类挂了
 * {@code @ConditionalOnApiSide}（{@code rag.side=api} ⇒ 只在 api 侧注册）⇒
 * ⇒ 但 RAG-V1 <b>D1 把 api + rpc 合并为单一进程</b> ⇒ ⇒ <b>不存在「api 侧」这个维度</b>
 * ⇒ ⇒ 该条件注解在本仓<b>没有对应物</b>（映射表 §1.12 第 282 行：<b>「不是缺口」</b>）
 * ⇒ ⇒ 本类<b>无条件注册</b>（由 {@code common/autoconfigure/WebAutoConfiguration} 统一装配）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestControllerAdvice
public class JsonContentTypeAdvice implements ResponseBodyAdvice<Object> {

    /**
     * 本类写入的 {@code Content-Type} —— <b>与 Spring 转换器自己会写的值逐字节相同</b>。
     *
     * <p>🔴🔴 <b>为什么不是 B 的字面量</b>（B 的是 {@code "application/json; charset=utf-8"}，<b>分号后有空格</b>）：
     * <ol>
     *   <li><b>真 Tomcat 路径</b>：Tomcat 11 把 Content-Type 拆成 {@code (type/subtype, charset)} 两份存、
     *       写出去时重新拼（{@code ";"} 后面<b>没有</b>空格）⇒ <b>写什么都会被归一化成同一个值</b>
     *       ⇒ 这里写哪个字面量都无所谓。</li>
     *   <li><b>MockMvc 路径</b>（各域的 {@code *MvcPipelineTest} 走这条）：<b>没有容器归一化</b>
     *       ⇒ 写什么就<b>原样</b>出现在响应头里。
     *       ⇒ 若这里写 B 的<b>带空格</b>字面量 ⇒ 各域 MockMvc 守门钉的
     *       {@code application/json;charset=utf-8}（Spring 转换器自己产出的形态）就<b>会红</b>
     *       ⇒ 🔴 <b>实测踩过</b>：favorite-api 的 {@code FavoriteApiMvcPipelineTest.responseContentTypeMatchesGoConstant}
     *       就是被这个带空格字面量打红的 ⇒ 而 favorite 域对 global <b>零写权</b> ⇒ 修法必须落在本类。
     *       ⇒ 写<b>无空格</b>形态 ⇒ MockMvc 路径<b>零变化</b> ⇒ 别的域的守门不会被共享层改动打到。</li>
     * </ol>
     * ⇒ 结论：<b>写「各域 MockMvc 路径观测到的那个值」</b> —— 即
     * {@code MediaType.parseMediaType("application/json; charset=utf-8").toString()}
     * （Spring **保留原样的大小写** ⇒ 小写 {@code utf-8}）⇒ <b>两条路径都收敛到同一个值</b>。
     *
     * <p>⚠️ <b>别拿 {@code new MediaType(APPLICATION_JSON, UTF_8).toString()} 当依据</b> ——
     * 那条路走的是 {@code Charset.name()} ⇒ 产出 {@code application/json;charset=UTF-8}（<b>大写</b> UTF-8）
     * ⇒ 与各域实际产出的<b>小写</b>不一致 ⇒ 拿它当期望值会把<b>正确</b>的实现判成红。
     * ⇒ 守门（{@code GoStyleJsonContentTypeCharsetTomcatTest} 的第 4 条）用的就是
     * {@code MediaType.parseMediaType(...).toString()} 那一支。
     *
     * <p>⚠️ 与 B 的「差一个空格」属<b>框架限制</b>（与 T-7「状态行缺 reason phrase」同族）
     * ⇒ 已登记在 global 台账，不在本类的职责内。
     */
    public static final String GO_JSON_CONTENT_TYPE = "application/json;charset=utf-8";

    @Override
    public boolean supports(MethodParameter returnType,
                             Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body == null) {
            return body;
        }
        if (!writesJson(selectedConverterType) && !isJsonContentType(selectedContentType)) {
            return body;
        }
        // ⚠️ 用 set(String, String) 而不是 setContentType(MediaType)：
        //    后者会把值**解析成 MediaType 再序列化** ⇒ 与本类直接写的字符串**可能**不一致
        //    （本类写的正是 MediaType.toString() 的形态 ⇒ 两者等价，但显式写字符串更可控）。
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, GO_JSON_CONTENT_TYPE);
        return body;
    }

    /** 选中的转换器是不是 Jackson 系 ⇒ 是则响应体就是 JSON。 */
    private static boolean writesJson(Class<? extends HttpMessageConverter<?>> converterType) {
        return converterType != null
                && AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    /** 选中的 Content-Type 是不是 JSON。 */
    private static boolean isJsonContentType(MediaType contentType) {
        return contentType != null
                && MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
    }
}
