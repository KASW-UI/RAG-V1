package com.rag.admin.advice;

import com.rag.common.contract.ApiResponse;
import com.rag.users.common.ErrCodes;
import com.rag.users.common.ErrMsg;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * <p><b>职责</b>：admin 域 HTTP 面的<b>响应信封</b> —— 把任意 Controller 返回值包成
 * {@code {code, msg, data}}（成功时 {@code code=200} / {@code msg="OK"}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅两处<b>纯机械</b>改动 ——
 *       ① 类名 {@code GlobalResponseAdvice} → {@code AdminApiResponseAdvice}（映射表 admin.md §0.5 已批准的改名）；
 *       ② 信封载体 {@code com.rag.common.response.Response} → {@link ApiResponse}
 *       （共享层 D3 上提 · <b>三个 JSON 键与字段顺序逐条未动</b>：{@code code} → {@code msg} → {@code data}）。
 *       <b>方法体逐字照抄。</b></li>
 *   <li><b>语义还一样吗</b>：一样。见下面的两条幂等保护与「非 JSON 不包信封」两条不变式。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/advice/GlobalResponseAdvice.java}
 *       （58 行 · 本类按 §1.0c 档 ③「L1 契约层以 B 侧为准」整段抄）。</li>
 * </ol>
 *
 * <p><b>⚠️ 不得给 {@link ApiResponse} 加 {@code @JsonInclude}</b>：
 * B 侧信封三字段<b>都没有</b> {@code omitempty} ⇒ {@code data} 为 null 时<b>仍然出现</b>
 * （{@code "data":null}）⇒ ⇒ 加了 {@code @JsonInclude(NON_NULL)} 就会让 {@code data} 键消失
 * ⇒ ⇒ <b>可观察形状不等价</b>。（{@code ApiResponse} 是共享层 L1 契约类，本域只读。）
 *
 * <p><b>与 {@code AdminApiExceptionHandler} 的幂等保护</b>：
 * {@code AdminApiExceptionHandler} 的 {@code @ExceptionHandler} 返回
 * {@code ResponseEntity<ApiResponse>}，而 Spring 的 {@code HttpEntityMethodProcessor}
 * 继承 {@code writeWithMessageConverters} 并会查询 advice chain，所以
 * {@code ResponseBodyAdvice} 对 {@code ResponseEntity} <b>生效</b>。
 * 若无条件再包一层，错误响应顶层 code 会恒为 200，客户端的失败分支全部失效。
 * ⇒ ⇒ 「已经是信封的就不再包」这条判断是<b>必须</b>的，不是优化。
 */
@RestControllerAdvice
public class AdminApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // 已经是 envelope 的不再包；null body 也不能包（否则会把框架级错误状态码的
        // 空响应体篡改成 {"code":200,...}）。
        if (body == null || body instanceof ApiResponse) {
            return body;
        }
        // 【DRIFT-006】非 JSON 响应必须原样透传，不能包 envelope。
        //
        // B 出处：go-zero rest/httpx/responses.go 的 doHandleError 在 handler == nil 时走
        //   http.Error(w, err.Error(), http.StatusBadRequest) —— 产出的是
        //   Content-Type: text/plain; charset=utf-8 + 裸文本，**没有 {code,msg,data} 结构**。
        //   envelope 只由 httpx.OkJson 产出，而它固定写 Content-Type: application/json
        //   （同文件 doWriteJson）。
        //
        // 因此判定条件是「这次写出的到底是不是 JSON」，而不是「body 是不是字符串」——
        // 否则 405/415 这类同样走 text/plain 的分支会被漏判。
        //
        // ⚠️ 本域的 400 + text/plain 分支（AdminApiExceptionHandler 的
        //   handleUnreadableBody / handleGoRequiredField / handleBeanValidation）
        //   返回的是 ResponseEntity<byte[]> ⇒ 选中的是 ByteArrayHttpMessageConverter
        //   ＋ 显式 text/plain ⇒ 两条判据都不满足「是 JSON」⇒ ⇒ 原样透传 ✔
        if (selectedContentType != null
                && !MediaType.APPLICATION_JSON.isCompatibleWith(selectedContentType)) {
            return body;
        }
        return new ApiResponse(ErrCodes.SUCCESS, ErrMsg.getMsg(ErrCodes.SUCCESS), body);
    }
}
