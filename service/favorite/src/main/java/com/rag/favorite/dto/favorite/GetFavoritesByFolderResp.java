package com.rag.favorite.dto.favorite;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * <p><b>查询收藏夹下收藏的响应体</b>（信封 {@code data} 的内容）—— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/GetFavoritesByFolderResp.java:21-39}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type GetFavoritesByFolderResp struct {
 *     Favorites []FavoriteItem `json:"favorites"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code GET /favorite/v1/item/list}。
 *
 * <h3>⚠️ {@code favorites} 永远非 null</h3>
 * <p>B 侧 {@code GetFavoritesByFolderResp.java:15-19} 的类注释：Go 的
 * {@code get_favorites_by_folder_logic.go:64} 是
 * {@code favorites := make([]types.FavoriteItem, 0, len(rpcResp.Favorites))}
 * —— <b>无条件 make</b>，空结果也是 <b>{@code []}</b> 而非 {@code null}。
 * <p>⇒ <b>Service 层必须给空集合赋值</b>（B 侧 {@code FavoriteListItemsService.java:73}
 * 的 {@code new ArrayList<>(rpcResp.getFavoritesCount())} 就是那个「无条件 make」）；
 * <br>⇒ DTO 侧<b>不加</b> {@code @JsonInclude(NON_NULL)} ⇒ 空结果输出 {@code "favorites":[]}。
 *
 * <h3>⚠️ C 侧的落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变 class + 无参/单参构造器 + setter → immutable record</b>
 *       （D5 + A9）＋ <b>包名压平</b>（D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON key 名</b>：{@code favorites}（B 侧 {@code @JsonProperty("favorites")}，
 *             B {@code GetFavoritesByFolderResp.java:23}）⇒ 逐字相同 ✔；</li>
 *         <li><b>数组语义</b>：元素类型是 {@link FavoriteItem}（7 字段白名单投影）✔；
 *             <b>空结果必须给空集合</b>（不是 {@code null}）—— 这是 Service 层的责任 ✔；</li>
 *         <li><b>不加 {@code @JsonInclude(NON_NULL)}</b> ⇒ 空集合输出 {@code []}
 *             （B 侧同样不加）✔；</li>
 *         <li>record 的访问器名（{@code favorites()} 而非 {@code getFavorites()}）<b>不进入 wire</b>
 *             ⇒ JSON 输出逐字节相同。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code GetFavoritesByFolderResp.java:23-24}（字段 ＋ 注解）
 *       ＋ {@code GetFavoritesByFolderResp.java:15-19}（「永远非 null」的语义依据）
 *       ＋ {@code FavoriteListItemsService.java:73-80}（无条件 make ＋ 装配）。</li>
 * </ol>
 *
 * @param favorites 收藏列表（<b>永远非 null</b>；空结果为 {@code []}）
 */
public record GetFavoritesByFolderResp(
        @JsonProperty("favorites") List<FavoriteItem> favorites) {
}
