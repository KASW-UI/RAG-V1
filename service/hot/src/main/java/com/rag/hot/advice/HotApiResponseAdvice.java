package com.rag.hot.advice;

import com.rag.common.contract.ApiResponse;
import com.rag.hot.contract.ErrCodes;
import com.rag.hot.contract.ErrMsg;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * <p><b>职责</b>：hot 域 HTTP 面的<b>成功信封包装</b> —— 把 controller 的返回值统一包成
 * {@code {"code":200,"msg":"OK","data":<返回值>}}。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/advice/GlobalResponseAdvice.java}
 * <pre>{@code
 *   @RestControllerAdvice + implements ResponseBodyAdvice<Object>   :51-52
 *   supports(...) ⇒ 恒 true                                        :54-57
 *   beforeBodyWrite(...)                                           :59-72
 *     body == null || body instanceof Response ⇒ 原样返回           :63-65
 *     selectedContentType 非 JSON ⇒ 原样返回                        :66-69
 *     否则 new Response(success, GetErrMsg(success), body)          :70-71
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯改名/换包</b> ——
 *       <ul>
 *         <li>类名 {@code GlobalResponseAdvice} → {@code HotApiResponseAdvice}
 *             （映射表 hot.md §0.5「{@code advice/} 直接压平」已批准的改名）；</li>
 *         <li>信封载体 {@code com.rag.common.response.Response} → {@link ApiResponse}
 *             （共享层 D3 上提；L1 三键 {@code code/msg/data} 与字段顺序<b>不变</b>）；</li>
 *         <li>错误码取值 {@code HotErrorCode.SUCCESS.getCode()} → {@link ErrCodes#SUCCESS}
 *             ＋ {@code HotErrorMessages.getMsg(200)} → {@link ErrMsg#getMsg(int)}
 *             （本仓错误契约形态；hot 域的码表在 {@code contract/}，<b>逐域隔离</b>）。</li>
 *       </ul>
 *       <b>方法体逐字照抄</b>（两条判空放行分支 + 非 JSON 透传判据）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>成功路径恒为 {@code HTTP 200 + {"code":200,"msg":"OK","data":…}}，且<b>只包一层</b>；</li>
 *         <li>失败路径<b>不经过本类</b>：{@link HotApiExceptionHandler} 直接产出完整 envelope
 *             （且失败时 {@code body instanceof ApiResponse} ⇒ 本类原样放行，不会包第二层）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>⚠️ 两条必须判空放行的分支（少一条就会产生假绿 / 假红 · B 侧注释逐条照抄）</b>：
 * <ol>
 *   <li><b>{@code body == null}</b>：{@code @ExceptionHandler} 返回的无 body 响应
 *       （本域的 404 / 405 裸状态码）若被包成 {@code {"code":200,"msg":"OK","data":null}}，
 *       等于凭空造出一个业务码，把「路由不存在」伪装成「成功但无数据」。</li>
 *   <li><b>{@code body instanceof ApiResponse}</b>：错误路径的信封已成型，
 *       再包一层会变成 {@code {"code":200,"msg":"OK","data":{"code":5001,…}}} ——
 *       <b>顶层 code 恒 200</b>，所有按 {@code code != 200} 判失败的客户端全部失效。</li>
 * </ol>
 *
 * <p><b>非 JSON 响应透传</b>：B 侧的信封只由 {@code httpx.OkJsonCtx} 产出（固定
 * {@code Content-Type: application/json}）；框架级错误走别的分支 ⇒
 * 判定条件是「这次写出的到底是不是 JSON」，而不是「body 是不是 String」。
 */
@RestControllerAdvice
public class HotApiResponseAdvice implements ResponseBodyAdvice<Object> {

    /**
     * 全部返回值都交给本类处理 —— B 侧逐字照抄。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalResponseAdvice.java:54-57}。
     *
     * @param returnType    controller 方法的返回类型
     * @param converterType 选中的消息转换器类型
     * @return 恒为 {@code true}（B 侧不排除任何类型）
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /**
     * 包信封 —— B 侧逐字照抄（含两条判空放行与 JSON 判据）。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalResponseAdvice.java:59-72}。
     *
     * @param body                   controller 的返回值（可能为 {@code null}、也可能是已成型信封）
     * @param returnType             controller 方法的返回类型
     * @param selectedContentType    本次响应选中的 Content-Type
     * @param selectedConverterType  选中的消息转换器类型
     * @param request                服务端请求
     * @param response               服务端响应
     * @return 包装后的信封；或原样返回 {@code body}（判空放行 / 非 JSON 透传）
     */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // 判空放行：null body 与已成型信封都原样返回（B 侧 :63-65）
        if (body == null || body instanceof ApiResponse) {
            return body;
        }
        // 非 JSON 响应透传（B 侧 :66-69）
        if (selectedContentType != null
                && !MediaType.APPLICATION_JSON.isCompatibleWith(selectedContentType)) {
            return body;
        }
        int success = ErrCodes.SUCCESS.getCode();
        return new ApiResponse(success, ErrMsg.getMsg(success), body);
    }
}
