package com.rag.article.dto.article;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * <p><b>职责</b>：文章列表的响应体（信封 {@code data} 字段的内容）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/ListArticlesResp.java}
 * （类声明 {@code :41} · {@code @JsonPropertyOrder} {@code :40} · 四个字段 {@code :43-50}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变类 + 无参构造器 + setter → immutable record</b>
 *       （D5 + A9）＋ <b>包名压平</b>（D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON key 名</b>：{@code articles} / {@code total} / {@code page} / {@code page_size}
 *             ⇒ 逐字相同；</li>
 *         <li><b>字段顺序</b>：{@code articles → total → page → page_size} = Go 的
 *             {@code encoding/json} struct <b>声明序</b>（B 侧 {@code ListArticlesResp.java:33-38}）。
 *             ⇒ C 侧是 <b>immutable record</b> ⇒ Jackson 2 默认即按<b>声明序</b>输出 ⇒ <b>天然一致</b>
 *             ⇒ ⇒ {@link JsonPropertyOrder} <b>保留</b>，作用是<b>显式钉死</b>
 *             （组件被重排时字节序会静默漂移），<b>不是</b>「修正默认字母序」
 *             —— 那是 B 侧 Jackson 3 + 可变 class 的形态。</li>
 *         <li>record 的访问器名<b>不进入 wire</b> ⇒ JSON 输出逐字节相同。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ {@code articles} 必须允许为 {@code null}</h2>
 * <p>Go 侧 {@code list_articles_logic.go:62-69} 声明的是
 * <pre>var articles []types.Article   // nil slice</pre>
 * 并只对<b>通过公开状态校验</b>的行 {@code append}。
 * 当下游返回空列表 ⇒ {@code articles} 仍是 nil ⇒ 输出 {@code "articles":null}。
 *
 * <p>⇒ 本类<b>不</b>用 {@code List.of()} 预初始化 ⇒ <b>空 ⇒ {@code null}</b>，<b>不是</b> {@code []}
 * （客户端 {@code if (data.articles)} 在 JS 侧对 {@code []} 为真、对 {@code null} 为假）
 * （B 侧 {@code ListArticlesResp.java:21-31}）。
 */
@JsonPropertyOrder({"articles", "total", "page", "page_size"})
public record ListArticlesResp(

        @JsonProperty("articles")
        List<Article> articles,

        @JsonProperty("total")
        long total,

        @JsonProperty("page")
        int page,

        @JsonProperty("page_size")
        int pageSize) {
}
