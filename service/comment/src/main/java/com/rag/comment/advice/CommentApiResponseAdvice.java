package com.rag.comment.advice;

import com.rag.comment.contract.ErrCodes;
import com.rag.comment.contract.ErrMsg;
import com.rag.common.contract.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * <p><b>comment 域成功响应包装</b> —— 对应 B 侧
 * {@code comment-api/…/api/advice/CommentGlobalResponseAdvice.java:17-49}。
 *
 * <p><b>⚠️ L4 落点变化（D1 + 域内隔离）</b>：
 * B 侧 {@code com.rag.comment.comment.api.advice.CommentGlobalResponseAdvice}
 * ⇒ C 侧 {@code com.rag.comment.advice.CommentApiResponseAdvice}
 * ⇒ 类名去掉 {@code Global} 前缀（**域内隔离**，AGENTS.md §八「未统一化」：
 * 10 个域各有自己的 advice，不得共用一个）。
 *
 * <h3>⚠️ 响应信封形状是 <b>L1 契约</b>：{@code {code,msg,data}}</h3>
 * <p>B 侧用共享层的 {@code com.rag.common.response.Response}
 * ⇒ C 侧用共享层的 {@link ApiResponse}（<b>形状同构</b>：{@code code} / {@code msg} / {@code data}）
 * ⇒ <b>不得</b>用 {@code ProblemDetail} 替换（客户端按 {@code {code,msg,data}} 解析）。
 *
 * <h3>⚠️ 三条必须逐字保留的分支</h3>
 * <ol>
 *   <li><b>{@code body == null} ⇒ 原样返回</b>（不包信封）⇒ 响应体为空。</li>
 *   <li><b>{@code body instanceof ApiResponse} ⇒ 原样返回</b> ——
 *       已经是信封的（异常处理器造的）不二次包装。</li>
 *   <li><b>选中 ContentType 与 JSON 不兼容 ⇒ 原样返回</b>
 *       （非 JSON 响应不套 JSON 信封）。</li>
 * </ol>
 *
 * <h3>⚠️ 成功码取自本域码表</h3>
 * <p>{@link ErrCodes#SUCCESS}（200）＋ {@link ErrMsg#getMsg(int)}（"OK"）⇒
 * <b>逐域隔离</b>：本域的码表与 like / hot / message 域<b>各不相同</b>。
 */
@RestControllerAdvice
public class CommentApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // ⚠️ ① null body ⇒ 原样返回（B 侧同款）
        if (body == null) {
            return body;
        }
        // ⚠️ ② 已是信封 ⇒ 原样返回，不二次包装（B 侧同款）
        if (body instanceof ApiResponse) {
            return body;
        }
        // ⚠️ ③ 非 JSON 响应 ⇒ 原样返回（B 侧同款）
        if (selectedContentType != null
                && !MediaType.APPLICATION_JSON.isCompatibleWith(selectedContentType)) {
            return body;
        }
        int success = ErrCodes.SUCCESS;
        return new ApiResponse(success, ErrMsg.getMsg(success), body);
    }
}
