package com.rag.favorite.dto.favorite;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>删除收藏的请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/DeleteFavoriteReq.java:20-39}
 * （可变 class ＋ 手写必填校验器）。B 侧原始形态：
 * <pre>
 * type DeleteFavoriteReq struct {
 *     FavoriteId int64 `json:"favoriteId,string"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code DELETE /favorite/v1/item/delete}（<b>需 uid</b>）。
 *
 * <p>⚠️ <b>DELETE 也带 JSON body</b>：B 侧 {@code httpx.Parse} 对所有方法都跑
 * {@code ParseJsonBody}，与 HTTP method 无关（只有 Content-Type 与 Content-Length 参与判定）
 * ⇒ 本 DTO 是<b>请求体</b>，不是 query 参数。
 *
 * <h3>⚠️ {@code favoriteId} 的 {@code ,string} 是「入参方向宽松」</h3>
 * <p>Go 的 {@code ,string} 对 int64 <b>字符串与数字都收</b>，浮点 / 布尔 / 超 int64 一律拒
 * （B 侧 {@code DeleteFavoriteReq.java:34-39} 的 {@code setFavoriteId(Object)}）。
 * <br>⇒ C 侧由共享层 {@link StringInt64#parse(Object, String)} 逐条承接。
 * <br>⇒ ⚠️ <b>入参必须声明成 {@link Object}</b>（同
 * {@code service/favorite/favorite-api/.../dto/DeleteFavoriteFolderReq.java:44-51} 的理由）。
 *
 * <h3>⚠️ C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：把 B 侧的<b>手写必填校验器</b>
 *       （{@code assertGoRequiredFields()} ＋ {@code GoStyleRequired.field}）
 *       换成 <b>Bean Validation</b>（{@code @NotNull}）—— A10 断言要求的形态；
 *       <b>可变 class → immutable record</b>（D5 + A9）；<b>包名压平</b>（D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>必填口径</b>：B 侧 {@code GoStyleRequired.field} <b>只判 null</b>
 *             ⇒ {@code @NotNull} 同样只判 null ⇒ 缺席 / 显式 null 被拒 ⇒ 逐条等价 ✔；</li>
 *         <li><b>{@code ,string} 入参口径</b>：字符串与整数都接受，浮点 / 布尔 / 超 int64 拒
 *             ⇒ 由 {@link StringInt64#parse} 逐条承接 ✔。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code DeleteFavoriteReq.java:25-28} 的 {@code assertGoRequiredFields}
 *       ＋ {@code DeleteFavoriteReq.java:37-39} 的 {@code GoStyleStringInt64.parse}。</li>
 * </ol>
 *
 * @param favoriteId 要删除的收藏 id（必填；JSON 字符串与整数都接受）
 */
public record DeleteFavoriteReq(
        @NotNull Long favoriteId) {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 承接 B 侧的「{@code Object} 型 setter」。
     *
     * @param rawFavoriteId {@code favoriteId} 原始值（字符串或整数都接受）
     * @throws IllegalArgumentException {@code favoriteId} 是浮点 / 布尔 / 非十进制整数 / 超出 int64
     */
    @JsonCreator
    public static DeleteFavoriteReq fromJson(@JsonProperty("favoriteId") Object rawFavoriteId) {
        return new DeleteFavoriteReq(StringInt64.parse(rawFavoriteId, "favoriteId"));
    }
}
