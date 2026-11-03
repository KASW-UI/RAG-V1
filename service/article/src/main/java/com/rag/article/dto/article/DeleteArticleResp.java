package com.rag.article.dto.article;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>职责</b>：删除文章的响应体（信封 {@code data} 字段的内容）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/DeleteArticleResp.java}
 * （类声明 {@code :8} · 字段 {@code :16}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变类 + 无参/单参构造器 + setter → immutable record</b>
 *       （D5 + A9）＋ <b>包名压平</b>（D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON key 名</b>：{@code success}（B 侧无 {@code @JsonProperty} ⇒ 取字段名，
 *             B {@code DeleteArticleResp.java:16}）⇒ 逐字相同；</li>
 *         <li>record 的访问器名（{@code success()} 而非 {@code isSuccess()}）<b>不进入 wire</b>
 *             ⇒ JSON 输出逐字节相同。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 恒为 {@code true}</h2>
 * <p>Go {@code delete_article_logic.go:87-89} 只在删除 RPC <b>返回 nil error</b> 时才构造本响应
 * ⇒ {@code true} 是唯一的值；<b>不存在</b>「200 + {@code success=false}」的响应
 * （对比 {@link UpdateArticleResp}，那个取域服务的返回值，<b>可能是 false</b>）
 * （B 侧 {@code DeleteArticleResp.java:10-15}）。
 */
public record DeleteArticleResp(

        @JsonProperty("success")
        boolean success) {
}
