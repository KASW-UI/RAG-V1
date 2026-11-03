package com.rag.article.advice;

import com.rag.article.contract.ErrCodes;
import com.rag.article.contract.ErrMsg;
import com.rag.common.contract.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * <p><b>成功路径的信封包装</b> —— 对应 Go 各 handler 里<b>手写</b>的那一行
 * {@code httpx.OkJson(w, &response.Response{Code: code, Msg: errmsg.GetErrMsg(code), Data: resp})}。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       ① 类的<b>包路径与类名</b>（{@code advice/GlobalResponseAdvice} → {@code advice/ArticleApiResponseAdvice}，
 *       D7 包压平 + 域名前缀）；
 *       ② 信封类型由 B 侧的 {@code com.rag.common.response.Response} 改为共享层的
 *       {@link ApiResponse}（D3 上收 · <b>三个 JSON key 逐字不变</b>：{@code code} / {@code msg} / {@code data}）；
 *       ③ 错误码表由 B 侧的 {@code ErrorCode} 改为本域的 {@link ErrCodes} / {@link ErrMsg}；
 *       ④ 补 B 出处行号。</li>
 *   <li><b>语义还一样吗</b>：一样 —— <b>五条放行分支</b>、包封条件、
 *       「只包一层」的契约全部逐条照抄 ⇒ <b>响应 body 逐字节相同</b>。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/article/article-api/src/main/java/com/rag/article/article/api/advice/GlobalResponseAdvice.java:61-62（类声明）
 * B 出处：…/advice/GlobalResponseAdvice.java:64-67（supports）
 * B 出处：…/advice/GlobalResponseAdvice.java:76-112（五条放行分支）
 * B 出处：…/advice/GlobalResponseAdvice.java:113（包封）
 *       </pre></li>
 * </ol>
 *
 * <h2>⚠️ 必须放行的分支（少一条就会产生假绿 / 假红）</h2>
 * <ol>
 *   <li><b>{@code body == null}</b>：{@code @ExceptionHandler} 返回的无 body 响应
 *       （本模块的 401 空 body、404/405、text/plain 400）若被包成
 *       {@code {"code":200,"msg":"OK","data":null}}，就等于凭空造出一个业务码，
 *       把「鉴权失败」伪装成「成功但无数据」。AGENTS.md §3.3 的 DRIFT-FRAMEWORK-001
 *       就是同款形态：{@code HttpEntityMethodProcessor} 会查 advice chain，
 *       连 {@code @ExceptionHandler} 的 {@code ResponseEntity} 也在链上。</li>
 *   <li><b>{@code body instanceof ApiResponse}</b>：错误路径的 envelope 已经成型，
 *       再包一层会出现 {@code {"code":200,"msg":"OK","data":{"code":1002,…}}}。</li>
 *   <li><b>{@code body instanceof ResponseEntity}</b>：<b>这一条是真实契约，不是防御</b> ——
 *       {@code POST /v1/upload} 在 Go 侧<b>不走信封</b>（{@code UploadImageHandler} 调
 *       {@code httpx.OkJsonCtx(ctx, w, resp)} 直接写裸结构体，而另外 6 个 handler
 *       显式包了信封）。</li>
 * </ol>
 *
 * <h2>⚠️ 只包 JSON</h2>
 * <p>Go 的 {@code httpx.OkJson}（{@code httpx/responses.go:114-119}）无条件
 * {@code w.Header().Set(ContentType, header.ContentTypeJson)}。
 * 本类的 {@code supports} 因此返回 {@code true}（不挑媒体类型），
 * 但对 {@code byte[]} / {@code String} 这类已被容器当成非 JSON 的 body 不做包装 ——
 * 它们在本模块只出现在 error handler 路径上，已被上面第 1 条的 null 判断拦掉。
 */
@RestControllerAdvice
public class ArticleApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        // ── 放行 1：空 body（401 / 404 / 405 / 裸 500） ──
        if (body == null) {
            return null;
        }
        // ── 放行 2：已经是 envelope ──
        if (body instanceof ApiResponse) {
            return body;
        }
        // ── 放行 3：控制器方法声明返回 ResponseEntity ──
        //    ⚠️ POST /v1/upload 的裸响应走这里。Go 侧 UploadImageHandler 调
        //    httpx.OkJsonCtx(ctx, w, resp) 直接写裸结构体，而另外 6 个 handler 显式包了信封。
        //    ⚠️ **必须看 returnType，不能看 body**：
        //    HttpEntityMethodProcessor 会先把 ResponseEntity **拆开**再交给 advice，
        //    到这里 body 已经是 UploadImageResp 本身，`body instanceof ResponseEntity` 永远为假。
        //    唯一可靠的判据是「这个 handler 方法自己声明了要返回 ResponseEntity」。
        if (ResponseEntity.class.isAssignableFrom(returnType.getParameterType())) {
            return body;
        }
        // ── 放行 4：裸 payload 形态 ──
        //    ⚠️ 这一条是**实测踩出来的**，不是预防性代码：
        //    ArticleApiExceptionHandler 的 400 text/plain 分支返回 ResponseEntity<byte[]>，
        //    同样会被 HttpEntityMethodProcessor 拆开，byte[] 落到这里。
        //    若不拦，它会被包成 {"code":200,"msg":"OK","data":[B@1234]}
        //    ——「解析失败」凭空变成「成功」+ Base64 乱码，且后续序列化再抛异常变成 500。
        //    （B 侧第一次实现漏了这条，5 条断言 400 text/plain 的用例全部失败。）
        if (body instanceof byte[] || body instanceof String || body instanceof Resource) {
            return body;
        }
        // ── 放行 5：非 JSON 的选定媒体类型 ──
        //    Go 侧 httpx.OkJson（httpx/responses.go:114-119）无条件写
        //    Content-Type: application/json；text/plain 的 body 一律来自
        //    Go 的 http.Error（裸状态码 + 裸文案），不属于信封语义。
        if (selectedContentType != null
                && !MediaType.APPLICATION_JSON.isCompatibleWith(selectedContentType)) {
            return body;
        }
        int success = ErrCodes.SUCCESS.getCode();
        return new ApiResponse(success, ErrMsg.getMsg(success), body);
    }
}
