package com.rag.favorite.dto.folder;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>重命名收藏夹的请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/UpdateFavoriteFolderNameReq.java:27-58}
 * （可变 class ＋ 手写必填校验器）。B 侧原始形态：
 * <pre>
 * type UpdateFavoriteFolderNameReq struct {
 *     FolderId int64  `json:"folderId,string"`
 *     Name     string `json:"name"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /favorite/v1/folder/update}（<b>需 uid</b>）。
 *
 * <h3>⚠️ 两个字段都必填（都没有 {@code ,optional}）</h3>
 * <p>B 侧实测（{@code UpdateFavoriteFolderNameReq.java:19-25}）：
 * <pre>
 *   {"folderId":"1","name":"x"} → OK
 *   {"folderId":1,"name":"x"}   → OK      ← ,string 收数字
 *   {"name":"x"}                → field "folderId" is not set ⇒ 5003
 *   {"folderId":"1"}            → field "name" is not set      ⇒ 5003
 *   {"folderId":"1","name":""}  → OK        ← 空串是合法值
 * </pre>
 *
 * <h3>⚠️ {@code folderId} 的 {@code ,string} 是「入参方向宽松」</h3>
 * <p>JSON <b>字符串与数字都收</b>，浮点 / 布尔 / 超 int64 一律拒
 * （B 侧 {@code UpdateFavoriteFolderNameReq.java:45-50}）。
 * <br>⇒ C 侧由共享层 {@link StringInt64#parse(Object, String)} 逐条承接
 * （该类就是 B 侧 {@code GoStyleStringInt64} 的上收副本 · 方法体逐条等价）。
 * <br>⇒ ⚠️ <b>入参必须声明成 {@link Object}</b>（同
 * {@code service/favorite/favorite-api/.../dto/DeleteFavoriteFolderReq.java:44-51} 的理由）。
 *
 * <h3>⚠️ {@code name} 的空串是合法值</h3>
 * <p>B 侧 {@code GoStyleRequired.field} <b>只判 null</b> ⇒ {@code {"folderId":"1","name":""}}
 * 在 B 侧是 <b>OK</b>（实测见上）⇒ C 侧用 {@code @NotNull}（<b>不是</b> {@code @NotBlank}）逐条对齐。
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
 *         <li><b>必填口径</b>：B 侧 {@code GoStyleRequired.field} <b>只判 null</b>（空串合法）
 *             ⇒ {@code @NotNull} 同样只判 null ⇒ 缺席 / 显式 null 被拒、<b>空串放行</b>
 *             ⇒ 逐条等价 ✔；</li>
 *         <li><b>{@code ,string} 入参口径</b>：字符串与整数都接受，浮点 / 布尔 / 超 int64 拒
 *             ⇒ 由 {@link StringInt64#parse} 逐条承接 ✔；</li>
 *         <li><b>{@code name} 的类型口径</b>：JSON 数字进 string 字段是「type mismatch」（B 侧拒绝）
 *             ⇒ 由共享层的 Jackson 强转补强拒绝
 *             （{@code common/src/main/java/com/rag/common/web/Coercion.java:174-178}）✔。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code UpdateFavoriteFolderNameReq.java:35-39} 的 {@code assertGoRequiredFields}
 *       ＋ {@code UpdateFavoriteFolderNameReq.java:48-50} 的 {@code GoStyleStringInt64.parse}。</li>
 * </ol>
 *
 * @param folderId 收藏夹 id（必填；JSON 字符串与整数都接受）
 * @param name     新名字（必填；<b>空串是合法值</b>）
 */
public record UpdateFavoriteFolderNameReq(
        @NotNull Long folderId,
        @NotNull String name) {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 承接 B 侧的「{@code Object} 型 setter」。
     *
     * <p>⚠️ <b>{@code folderId} 的入参必须是 {@link Object}</b>：Go 的 {@code ,string} 是
     * 「入参方向宽松（字符串与数字都收）」，只有不声明目标类型、让 Jackson 原样递出，
     * 才能同时表达「两种都收」。
     *
     * <p>⚠️ <b>{@code name} 的入参声明成 {@link String}</b>：B 侧这个字段<b>没有</b>
     * {@code ,string} ⇒ JSON 数字是「type mismatch」（B 侧拒绝）
     * ⇒ 由共享层的 Jackson 强转补强拒绝。
     *
     * @param rawFolderId {@code folderId} 原始值（字符串或整数都接受）
     * @param name        {@code name} 原始值
     * @throws IllegalArgumentException {@code folderId} 是浮点 / 布尔 / 非十进制整数 / 超出 int64
     */
    @JsonCreator
    public static UpdateFavoriteFolderNameReq fromJson(@JsonProperty("folderId") Object rawFolderId,
                                                       @JsonProperty("name") String name) {
        return new UpdateFavoriteFolderNameReq(
                StringInt64.parse(rawFolderId, "folderId"),
                name);
    }
}
