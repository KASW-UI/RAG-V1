package com.rag.article.dto.upload;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>职责</b>：图片上传的响应体 —— <b>全域唯一不走信封</b>的响应。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/UploadImageResp.java}
 * （类声明 {@code :8} · 字段 {@code :25-26}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变类 + 无参/单参构造器 + setter → immutable record</b>
 *       （D5 + A9）＋ <b>包名压平</b>（{@code ...api.dto} → {@code com.rag.article.dto.upload}，D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON key 名</b>：{@code image_url}（B 侧 {@code @JsonProperty("image_url")}，
 *             B {@code UploadImageResp.java:25}）⇒ 逐字相同；</li>
 *         <li>record 的访问器名（{@code imageUrl()} 而非 {@code getImageUrl()}）<b>不进入 wire</b>
 *             ⇒ JSON 输出逐字节相同。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ <b>本响应不走信封</b>：输出的是 <b>裸</b> {@code {"image_url":"…"}}</h2>
 * <p>Go {@code UploadImageHandler} 调的是 {@code httpx.OkJsonCtx(r.Context(), w, resp)}，
 * 直接写裸结构体；而<b>另外 6 个 handler</b> 调的是
 * {@code httpx.OkJson(w, &amp;response.Response{...})}（显式包了信封）。
 *
 * <p>根因：article-api <b>没有</b> {@code httpx.SetOkHandler} ⇒ {@code OkJson} 不会自动包；
 * 6 个 handler 是<b>手写</b>包的信封。
 * ⇒ 成功时输出的是 {@code {"image_url":"…"}}，<b>不是</b> {@code {"code":200,"msg":"OK","data":{…}}}。
 *
 * <p>这属于 L1 跨服务契约（客户端按 {@code data.image_url} 与 {@code image_url} 两种形状
 * 各写了一处都会错）⇒ 实现侧靠 response advice 对 {@code ResponseEntity} 放行来保住它
 * （B 侧 {@code UploadImageResp.java:10-24}）。
 */
public record UploadImageResp(

        @JsonProperty("image_url")
        String imageUrl) {
}
