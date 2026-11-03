package com.rag.article.dto.article;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>职责</b>：更新文章的响应体（信封 {@code data} 字段的内容）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/UpdateArticleResp.java}
 * （类声明 {@code :8} · 字段 {@code :16}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变类 + 无参/单参构造器 + setter → immutable record</b>
 *       （D5 + A9）＋ <b>包名压平</b>（D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON key 名</b>：{@code success}（B 侧无 {@code @JsonProperty} ⇒ 取字段名，
 *             B {@code UpdateArticleResp.java:16}）⇒ 逐字相同；</li>
 *         <li>record 的访问器名（{@code success()} 而非 {@code isSuccess()}）<b>不进入 wire</b>
 *             ⇒ JSON 输出逐字节相同。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ {@code success} 直接取域服务的返回值，<b>不是</b>无条件 {@code true}</h2>
 * <p>Go {@code update_article_logic.go:108-110}：
 * <pre>return &amp;types.UpdateArticleResp{Success: rpcResp.GetSuccess()}, errmsg.Success</pre>
 * ⇒ 「{@code code=200} 但 {@code success=false}」是<b>合法且可能</b>的响应，<b>不要改成 true</b>
 * （B 侧 {@code UpdateArticleResp.java:10-15}）。
 */
public record UpdateArticleResp(

        @JsonProperty("success")
        boolean success) {
}
