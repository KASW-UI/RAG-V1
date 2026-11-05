package com.rag.favorite.dto.favorite;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>删除收藏的响应体</b>（信封 {@code data} 的内容）—— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/DeleteFavoriteResp.java:15-32}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type DeleteFavoriteResp struct {
 *     Success bool `json:"success"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code DELETE /favorite/v1/item/delete}。
 *
 * <h3>⚠️⚠️ {@code success} 是「HTTP 成功」标志，<b>恒为 true</b>，绝不是业务结果标志</h3>
 * <p>B 侧 {@code DeleteFavoriteResp.java:10-13} 的类注释：Go 的
 * {@code delete_favorite_logic.go:57-62} 在 {@code rpcResp == nil || !rpcResp.Success}
 * 时直接返回 5001，<b>不会走到这里</b> ⇒ 走到就意味着删除已成功。
 * <p>⇒ <b>业务成败的判据是信封的 {@code code}</b>，<b>不是</b> 这个 {@code success}。
 * <p>⚠️ 同款「语义反转」在本项目已栽过跟头（users 域曾把 {@code success} 当成
 * 「点赞是否真的生效」的业务结果，导致失败路径被写成 {@code success:false} + HTTP 200）。
 *
 * <h3>⚠️ C 侧的落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变 class + 无参/单参构造器 + setter → immutable record</b>
 *       （D5 + A9）＋ <b>包名压平</b>（D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON key 名</b>：{@code success}（B 侧<b>无</b> {@code @JsonProperty}
 *             ⇒ 取字段名 {@code Success} 的小写形式 ⇒ 逐字相同）✔；</li>
 *         <li><b>值的类型</b>：B 侧是<b>原始 {@code boolean}</b>（不是 {@code Boolean}）
 *             ⇒ 缺席时是 {@code false}、<b>键一定出现</b> ⇒ 本仓用 {@code boolean}
 *             （非包装类型）逐条对齐 ✔；</li>
 *         <li>record 的访问器名（{@code success()} 而非 {@code isSuccess()}）<b>不进入 wire</b>
 *             ⇒ JSON 输出逐字节相同。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code DeleteFavoriteResp.java:15-17}（类声明 ＋ 字段）
 *       ＋ {@code DeleteFavoriteResp.java:10-13}（「恒为 true」的语义依据）。</li>
 * </ol>
 *
 * @param success 恒为 {@code true}（B 侧硬编码）；业务成败看信封的 {@code code}
 */
public record DeleteFavoriteResp(
        @JsonProperty("success") boolean success) {
}
