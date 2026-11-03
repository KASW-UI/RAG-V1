package com.rag.article.dto.article;

/**
 * <p><b>职责</b>：删除文章的入参 —— <b>携带两个 id</b>。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/DeleteArticleReq.java}
 * （类声明 {@code :22} · 两个 final 字段 {@code :24-25} · 构造器 {@code :27-30}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变类 → immutable record</b>（D5 + A9）＋ <b>包名压平</b>（D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>两个 id 的<b>来源与顺序</b>不变（{@code articleId} 来自 URL、{@code operatorId} 来自 JWT）；</li>
 *         <li>本类<b>不是</b> Jackson 的绑定目标 ⇒ <b>无 JSON key 契约</b>。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ Go 侧是零必填校验</h2>
 * <p>Go {@code types.go} 的 {@code DeleteArticleReq} 只有一个 {@code path:"id"} 字段，
 * 且它只在<b>路由命中</b>时才会被读 ⇒ 本类<b>不</b>实现必填校验接口，B 侧亦然
 * （B 侧 {@code DeleteArticleReq.java:13-14}）。
 *
 * <h2>⚠️ 两个 id 都要带，理由不同</h2>
 * <ul>
 *   <li>{@code operatorId}（当前登录用户）⇒ 用于<b>鉴权</b>：Go 侧先用它做一次
 *       「读 + 比对 {@code author_id}」的鉴权；</li>
 *   <li>再把它作为 {@code operator_id} 下发给域服务 ⇒ 让对端记录「谁删的」（审计），
 *       并可能用于管理员豁免。</li>
 * </ul>
 * ⇒ <b>只带其中一个就是丢行为</b>（B 侧 {@code DeleteArticleReq.java:16-21}）。
 */
public record DeleteArticleReq(

        String articleId,

        String operatorId) {
}
