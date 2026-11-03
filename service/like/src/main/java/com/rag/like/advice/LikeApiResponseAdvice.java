package com.rag.like.advice;

import com.rag.common.contract.ApiResponse;
import com.rag.like.contract.ErrCodes;
import com.rag.like.contract.ErrMsg;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * <p><b>成功出口的响应信封包装</b> —— 对应 B 侧
 * {@code like-api/src/main/java/com/rag/like/like/api/advice/GlobalResponseAdvice.java}。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧用的是 like 域自己的 {@code Response} 类；
 *       C 侧改用<b>共享层</b>的 {@link ApiResponse}（字段 {@code code / msg / data} 逐条同形）
 *       ⇒ 属 D3「共享能力收口到 common」的落点，不是新增行为。</li>
 *   <li><b>语义还一样吗</b>：一样。包 {@code {code,msg,data}}、成功码 200、文案查表得 "OK"、
 *       非 JSON 原样透传、{@code null} body 放行逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 三条不能改的语义</h2>
 * <ol>
 *   <li><b>已经是 envelope 的不再包</b>：{@code @ExceptionHandler} 返回的
 *       {@code ResponseEntity} 的 body 本身就是 envelope ⇒ 再包一层会变成
 *       {@code {code:5001,msg:"系统内部错误",data:{code:5001,...}}}。</li>
 *   <li><b>{@code null} body 必须放行</b>：鉴权失败是 <b>401 + 空 body</b>；
 *       被包成 {@code {code:200,...}} 就把「401 无 body」篡改成「200 有 body」。</li>
 *   <li><b>非 JSON 响应原样透传</b>：判定条件是「这次写出的到底是不是 JSON」，
 *       而不是「body 是不是字符串」。</li>
 * </ol>
 */
@RestControllerAdvice
public class LikeApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 拦截所有 Controller 返回值；Controller 自己抛异常由 LikeApiExceptionHandler 处理
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // ⚠️ 已经是 envelope 的（@ExceptionHandler 返回的 ResponseEntity 的 body）不再包；
        //    null body（如 401 空 body / 415 / 405）也不能包，否则会把空响应篡改成 {"code":200,...}。
        if (body == null || body instanceof ApiResponse) {
            return body;
        }
        // ⚠️ 非 JSON 响应必须原样透传（不能包 envelope）：
        //    envelope 只由「成功出口」产出，框架级错误走的是别的分支。
        if (selectedContentType != null
                && !MediaType.APPLICATION_JSON.isCompatibleWith(selectedContentType)) {
            return body;
        }
        // Go: new Response(success=200, LikeErrorMessages.getMsg(200), body)
        // ⇒ code=200、msg 查表得 "OK"、data=body
        return new ApiResponse(ErrCodes.SUCCESS, ErrMsg.getMsg(ErrCodes.SUCCESS), body);
    }
}
