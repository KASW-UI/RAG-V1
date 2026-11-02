package com.rag.user.advice;

import com.rag.common.contract.ApiResponse;
import com.rag.user.contract.ErrCodes;
import com.rag.user.contract.ErrMsg;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * <p><b>全局响应包装</b> —— {@code @RestControllerAdvice} ＋ {@code ResponseBodyAdvice}。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/advice/GlobalResponseAdvice.java}
 * （逐条照抄；仅信封类由 B 的 {@code com.rag.common.response.Response} 改为共享层的
 * {@code com.rag.common.contract.ApiResponse} —— 键名 code/msg/data 与顺序逐条一致）。
 *
 * <p><b>框架职责</b>：
 * <ul>
 *   <li>Spring {@code ResponseBodyAdvice} 在 Controller 返回值写入 response body 之前拦截；</li>
 *   <li>自动把任意 Controller 返回值包成 {@code {code, msg, data}}（统一响应格式）；</li>
 *   <li>与 {@link UserApiExceptionHandler} 配合：异常路径走业务码 ＋ HTTP 状态码，正常路径走 {@code data} 字段。</li>
 * </ul>
 *
 * <p><b>L1 wire 契约冻结</b>：成功路径 {@code {code: 200, msg: "OK", data: <controller 返回值>}}；
 * 失败路径 {@code {code: <业务码>, msg: <提示语>, data: null}}（由 {@link UserApiExceptionHandler} 直接产出）。
 * 两者都<b>只包一层</b>。
 *
 * <p><b>⚠️ 为什么必须在 beforeBodyWrite 里判空并放行 {@link ApiResponse}</b>：
 * {@code UserApiExceptionHandler} 的所有 {@code @ExceptionHandler} 返回
 * {@code ResponseEntity<ApiResponse>}。Spring 的 {@code HttpEntityMethodProcessor}
 * <b>并没有覆写</b> {@code writeWithMessageConverters}，而是继承
 * {@code AbstractMessageConverterMethodProcessor} 的实现；后者会查询 advice chain，
 * 因此 {@code ResponseBodyAdvice} 对 {@code ResponseEntity} 同样生效。
 * 若这里无条件再包一层，错误响应会变成
 * {@code {"code":200,"msg":"OK","data":{"code":1003,...}}}——
 * 顶层 code 恒为 200，所有按 {@code code != 200} 判定失败的客户端会全部失效。
 */
@RestControllerAdvice
public class UserApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 拦截所有 Controller 返回值；Controller 自己抛异常由 UserApiExceptionHandler 处理
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // 已经是 envelope 的（@ExceptionHandler 返回的 ResponseEntity 的 body）不再包；
        // null body（如框架级错误直接返回无 body 的 ResponseEntity）也不能包，
        // 否则会把 400/405/415 的空响应体篡改成 {"code":200,...}。
        if (body == null || body instanceof ApiResponse) {
            return body;
        }
        // 【DRIFT-006】非 JSON 响应必须原样透传，不能包 envelope。
        //
        // Go 出处：go-zero rest/httpx/responses.go 的 doHandleError 在 handler == nil 时走
        //   http.Error(w, err.Error(), http.StatusBadRequest) —— 产出的是
        //   Content-Type: text/plain; charset=utf-8 + 裸文本，**没有 {code,msg,data} 结构**。
        //   envelope 只由 httpx.OkJson 产出，而它固定写 Content-Type: application/json。
        //
        // 因此判定条件是「这次写出的到底是不是 JSON」，而不是「body 是不是字符串」——
        // 否则 405/415 这类同样走 text/plain 的分支会被漏判。
        if (selectedContentType != null
                && !MediaType.APPLICATION_JSON.isCompatibleWith(selectedContentType)) {
            return body;
        }
        return new ApiResponse(ErrCodes.SUCCESS, ErrMsg.getMsg(ErrCodes.SUCCESS), body);
    }
}
