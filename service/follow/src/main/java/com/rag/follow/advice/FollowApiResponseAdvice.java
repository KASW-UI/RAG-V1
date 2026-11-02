package com.rag.follow.advice;

import com.rag.common.contract.ApiResponse;
import com.rag.follow.contract.ErrCodes;
import com.rag.follow.contract.ErrMsg;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * <p><b>全局响应包装</b> —— 对应 B 侧
 * {@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/advice/GlobalResponseAdvice.java:56-77}
 * （逐条照抄；仅信封类由 B 的 {@code com.rag.common.response.Response} 改为共享层的
 * {@link ApiResponse} —— 键名 code/msg/data 与顺序逐条一致）。
 *
 * <h2>B 出处（逐字，已读）</h2>
 * <pre>
 * httpx.SetOkHandler(func(ctx context.Context, v interface{}) interface{} {
 *     return &amp;response.Response{
 *         Code: followcommon.Success,             // 200
 *         Msg:  followcommon.GetErrMsg(200),     // "OK"
 *         Data: v,
 *     }
 * })
 * </pre>
 *
 * <p><b>L1 wire 契约</b>：成功路径恒为
 * {@code HTTP 200 + {"code":200,"msg":"OK","data":<controller 返回值>}}，且 <b>只包一层</b>。
 * 失败路径由 {@link FollowApiExceptionHandler} 直接产出完整 envelope，<b>不经过本类</b>。
 *
 * <h3>⚠️ 两条必须判空放行的分支（少一条就会产生假绿 / 假红）</h3>
 * <ol>
 *   <li><b>{@code body == null}</b>：{@code @ExceptionHandler} 返回的无 body 响应
 *       （本域的 401 空 body、415、405）若被包成 {@code {"code":200,"msg":"OK","data":null}}，
 *       就等于凭空造出一个业务码，把「鉴权失败」伪装成「成功但无数据」。</li>
 *   <li><b>{@code body instanceof ApiResponse}</b>：错误路径的 envelope 已经成型，
 *       再包一层会变成 {@code {"code":200,"msg":"OK","data":{"code":5005,...}}} ——
 *       <b>顶层 code 恒 200，所有按 {@code code != 200} 判失败的客户端全部失效</b>。</li>
 * </ol>
 *
 * <p><b>⚠️ 黑名单那条 401 不会经过这里</b>：{@code FollowBlacklistInterceptor}
 * 直接写响应并返回 {@code false}，不进 handler / advice 链 ——
 * 因为 B 侧在那条路径上输出的是<b>两字段</b>对象（没有 {@code data}），
 * 被本类包一层就变成三字段了。详见该拦截器的类注释。
 *
 * <p><b>非 JSON 响应透传</b>：B 侧的 envelope 只由 {@code httpx.OkJson} 产出
 * （固定写 {@code Content-Type: application/json}）；框架级错误走的是别的分支。
 * 判定条件因此是「这次写出的到底是不是 JSON」，而不是「body 是不是 String」。
 *
 * <p><b>框架职责</b>：Spring 的 {@link ResponseBodyAdvice} 在 Controller 返回值写入 response body
 * <b>之前</b>拦截 ⇒ 自动把任意返回值包成 {@code {code, msg, data}}。
 * ⚠️ Spring 的 {@code HttpEntityMethodProcessor} <b>并没有覆写</b>
 * {@code writeWithMessageConverters}，而是继承
 * {@code AbstractMessageConverterMethodProcessor} 的实现；后者会查询 advice chain
 * ⇒ ⇒ {@code ResponseBodyAdvice} 对 {@code ResponseEntity} <b>同样生效</b>
 * ⇒ ⇒ 「{@code body instanceof ApiResponse} 就放行」这一条<b>不是可有可无</b>。
 */
@RestControllerAdvice
public class FollowApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 拦截所有 Controller 返回值；Controller 自己抛异常由 FollowApiExceptionHandler 处理
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
        //    判定条件是「这次写出的到底是不是 JSON」，而不是「body 是不是字符串」。
        if (selectedContentType != null
                && !MediaType.APPLICATION_JSON.isCompatibleWith(selectedContentType)) {
            return body;
        }
        return new ApiResponse(ErrCodes.SUCCESS, ErrMsg.getMsg(ErrCodes.SUCCESS), body);
    }
}
