package com.rag.favorite.dto.folder;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>删除收藏夹的请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/DeleteFavoriteFolderReq.java:30-51}
 * （可变 class ＋ 手写必填校验器）。B 侧原始形态：
 * <pre>
 * type DeleteFavoriteFolderReq struct {
 *     FolderId int64 `json:"folderId,string"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code DELETE /favorite/v1/folder/delete}（<b>需 uid</b>）。
 *
 * <p>⚠️ <b>DELETE 也带 JSON body</b>：B 侧 {@code httpx.Parse} 对所有方法都跑
 * {@code ParseJsonBody}，与 HTTP method 无关（只有 Content-Type 与 Content-Length 参与判定）
 * ⇒ 本 DTO 是<b>请求体</b>，不是 query 参数。
 *
 * <h3>⚠️ {@code ,string} 的入参方向是宽松的</h3>
 * <p>JSON <b>字符串与数字都收</b>。B 侧实测（{@code DeleteFavoriteFolderReq.java:19-26}）：
 * <pre>
 *   {"folderId":"9"}  → OK
 *   {"folderId":9}    → OK      ← 数字也收！
 *   {"folderId":"abc"}→ strconv.ParseInt: parsing "abc": invalid syntax ⇒ 5003
 *   {"folderId":1.5}  → strconv.ParseInt: parsing "1.5": invalid syntax ⇒ 5003
 *   {"folderId":null} → field "folderId" mustn't be nil ⇒ 5003
 *   {}                → field "folderId" is not set ⇒ 5003
 * </pre>
 * <br>⇒ C 侧由共享层 {@link StringInt64#parse(Object, String)} 逐条承接
 * （该类就是 B 侧 {@code GoStyleStringInt64} 的上收副本 · 方法体逐条等价）。
 *
 * <h3>⚠️ C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：把 B 侧的<b>手写必填校验器</b>
 *       （{@code assertGoRequiredFields()} ＋ {@code GoStyleRequired.field}）
 *       换成 <b>Bean Validation</b>（{@code @NotNull}）—— A10 断言要求的形态；
 *       <b>可变 class → immutable record</b>（D5 + A9）；<b>包名压平</b>（D11）；
 *       <b>B 侧的 {@code GoStyleStringInt64} → 共享层 {@link StringInt64}</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>必填口径</b>：B 侧 {@code GoStyleRequired.field} <b>只判 null</b>
 *             ⇒ {@code @NotNull} 同样只判 null ⇒ 逐条等价 ✔；</li>
 *         <li><b>{@code ,string} 入参口径</b>：字符串与整数都接受，浮点 / 布尔 / 超 int64 拒
 *             ⇒ 由 {@link StringInt64#parse} 逐条承接 ✔。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code DeleteFavoriteFolderReq.java:35-38} 的 {@code assertGoRequiredFields}
 *       ＋ {@code DeleteFavoriteFolderReq.java:44-51} 的 {@code GoStyleStringInt64.parse}
 *       （<b>「入参必须是 Object」的注释就在这里</b> · {@code :44-48}）。</li>
 * </ol>
 *
 * @param folderId 要删除的收藏夹 id（必填；JSON 字符串与整数都接受）
 */
public record DeleteFavoriteFolderReq(
        @NotNull Long folderId) {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 承接 B 侧的「{@code Object} 型 setter」。
     *
     * <p>⚠️ <b>入参必须是 {@link Object}</b>：Go 的 {@code ,string} 是「字符串与数字都收」，
     * 只有不声明目标类型、让 Jackson 原样递出，才能同时表达「两种都收」。
     *
     * @param rawFolderId {@code folderId} 原始值（字符串或整数都接受）
     * @throws IllegalArgumentException {@code folderId} 是浮点 / 布尔 / 非十进制整数 / 超出 int64
     */
    @JsonCreator
    public static DeleteFavoriteFolderReq fromJson(@JsonProperty("folderId") Object rawFolderId) {
        return new DeleteFavoriteFolderReq(StringInt64.parse(rawFolderId, "folderId"));
    }
}
