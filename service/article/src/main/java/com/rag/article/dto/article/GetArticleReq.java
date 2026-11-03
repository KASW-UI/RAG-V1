package com.rag.article.dto.article;

/**
 * <p><b>职责</b>：读单篇文章的入参 —— <b>不来自 JSON body</b>，由 path + query 两处拼成。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/GetArticleReq.java}
 * （类声明 {@code :30} · 构造器 {@code :35-38}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变类 → immutable record</b>（D5 + A9）＋ <b>包名压平</b>（D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>两个字段<b>都不是 JSON</b>（path + query）⇒ 本类<b>不是</b> Jackson 的绑定目标
 *             ⇒ <b>无 JSON key 契约</b>；</li>
 *         <li>构造顺序与 B 侧一致（{@code articleId} 在前、{@code incrView} 在后）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 本类被<b>两个不同鉴权级别</b>的端点复用</h2>
 * <pre>
 *   GET /v1/article/:id       （public，无 JWT）→ incrView 来自 query，可为 true
 *   GET /v1/me/article/:id   （JWT）        → incrView **恒为 false**
 * </pre>
 * <p>差异在「作者读」那条路径里<b>不</b>读 {@code incrView}，而是固定传 {@code false}
 * ⇒ 即 {@code GET /v1/me/article/x?incr_view=true} <b>不会</b>加阅读量
 * （B 侧 {@code GetArticleReq.java:21-28}）。
 *
 * <h2>⚠️ 为什么 {@code incr_view} 要走 Go 风格的取值规则</h2>
 * <p>它有<b>副作用</b>（域服务侧据此 {@code +1} 阅读量），且 Go 的真值表是
 * {@code 1/0} 而非 Spring 默认的 {@code true/false}
 * ⇒ {@code ?incr_view=1} 在 Go 是 {@code true}，在 Spring 默认绑定是 <b>400</b>
 * ⇒ 必须走 {@code com.rag.common.web.FormValues#bool}（共享层 · D-10 已收口 2026-11-03）。
 */
public record GetArticleReq(

        String articleId,

        boolean incrView) {
}
