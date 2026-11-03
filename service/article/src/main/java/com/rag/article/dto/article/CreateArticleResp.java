package com.rag.article.dto.article;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>职责</b>：创建文章的响应体（信封 {@code data} 字段的内容）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/CreateArticleResp.java}
 * （类声明 {@code :8} · 字段 {@code :18-19}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变类 + 无参/单参构造器 + setter → immutable record</b>
 *       （D5 + A9）＋ <b>包名压平</b>（D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON key 名</b>：{@code article_id}（B 侧 {@code @JsonProperty("article_id")}，
 *             B {@code CreateArticleResp.java:18}）⇒ 逐字相同；</li>
 *         <li><b>无 {@code omitempty}</b> ⇒ 空值时<b>键保留</b>（值为 {@code null}）⇒ 与 B 侧相同；</li>
 *         <li>record 的访问器名（{@code articleId()} 而非 {@code getArticleId()}）<b>不进入 wire</b>
 *             ⇒ JSON 输出逐字节相同。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 本字段在两条<b>失败</b>路径上也会被填</h2>
 * <p>Go {@code create_article_logic.go:60-69} 对 {@code FailedPrecondition} 与
 * {@code DeadlineExceeded} 返回<b>带 article_id 的 resp</b>，同时给 {@code code = 1004 / 1003}。
 * ⇒ 信封形状是 {@code {"code":1003,"msg":"文章处理中，请稍后查看","data":{"article_id":"…"}}}。
 * <br>⇒ <b>这不是笔误</b>：文章已落库、只是发布流水线没跑完，客户端<b>需要</b>那个 id 才能继续轮询/跳转。
 * <br>⇒ 把这四条统一成「{@code data = null}」就是 <b>L2 语义破坏</b>。
 * <br>（实现侧落在主会话负责的 {@code ArticleApiService} 的失败信封分支。）
 */
public record CreateArticleResp(

        @JsonProperty("article_id")
        String articleId) {
}
