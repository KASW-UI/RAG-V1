package com.rag.favorite.dto.favorite;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>新增收藏的请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/CreateFavoriteReq.java:39-105}
 * （可变 class ＋ 手写必填校验器）。B 侧原始形态：
 * <pre>
 * type CreateFavoriteReq struct {
 *     FolderId   int64  `json:"folderId,string"`
 *     TargetId   string `json:"targetId"`
 *     TargetType string `json:"targetType"`
 *     Title      string `json:"title,optional"`
 *     Cover      string `json:"cover,optional"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /favorite/v1/item/create}（<b>需 uid</b> · uid 来自 JWT claim，
 * <b>不</b>来自 body ⇒ 本 DTO <b>没有</b> userId 字段）。
 *
 * <h3>⚠️ 必填 / 可选的分界（B 侧逐条）</h3>
 * <ul>
 *   <li><b>必填</b>：{@code folderId}、{@code targetId}、{@code targetType}
 *       （tag 里没有 {@code ,optional}）；</li>
 *   <li><b>可选</b>：{@code title}、{@code cover}（tag 里有 {@code ,optional}）
 *       ⇒ Go 出处 {@code core/mapping/unmarshaler.go:856-862} 的
 *       {@code if mapValue == nil { if opts.optional() { return nil } ... }}
 *       ⇒ <b>缺席与显式 null 都合法</b>。</li>
 * </ul>
 * <p>B 侧实测（{@code CreateFavoriteReq.java:30-37}）：
 * <pre>
 *   {"folderId":"1","targetId":"a","targetType":"article"}              → OK
 *   {"folderId":"1","targetId":"a"}                                     → field "targetType" is not set ⇒ 5003
 *   {"folderId":"1","targetType":"article"}                             → field "targetId" is not set   ⇒ 5003
 *   {"folderId":"1","targetId":123,"targetType":"article"}              → type mismatch for field "targetId" ⇒ 5003
 *   {"folderId":"1","targetId":"a","targetType":"article","title":null} → OK（optional 显式 null 合法）
 * </pre>
 *
 * <h3>⚠️ {@code folderId} 的 {@code ,string} 是「入参方向宽松」</h3>
 * <p>Go 的 {@code ,string} 对 int64 <b>字符串与数字都收</b>，浮点 / 布尔 / 超 int64 一律拒
 * （B 侧 {@code CreateFavoriteReq.java:68-73} 的 {@code setFolderId(Object)} 注释与
 * {@code service/users/users-common/.../GoStyleStringInt64.java:83-125}）。
 * <br>⇒ C 侧由共享层 {@link StringInt64#parse(Object, String)} 逐条承接
 * （该类就是 B 侧 {@code GoStyleStringInt64} 的上收副本 · 方法体逐条等价）。
 * <br>⇒ ⚠️ <b>入参必须声明成 {@link Object}</b>：只有不声明目标类型、让 Jackson 原样递出，
 * 才能同时表达「两种都收」（B 侧同一理由 · {@code CreateFavoriteReq.java:68-73}）。
 *
 * <h3>⚠️ C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：把 B 侧的<b>手写必填校验器</b>
 *       （{@code assertGoRequiredFields()} ＋ {@code GoStyleRequired.field}）
 *       换成 <b>Bean Validation</b>（{@code @NotNull}）—— 即 A10 断言要求的形态
 *       （本仓禁止手写校验器）；<b>可变 class → immutable record</b>（D5 + A9）；
 *       <b>包名压平</b>（D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>必填口径</b>：B 侧 {@code GoStyleRequired.field} <b>只判 null</b>（空串合法）
 *             ⇒ {@code @NotNull}（<b>不是</b> {@code @NotBlank}）同样只判 null
 *             ⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ 逐条等价 ✔；</li>
 *         <li><b>{@code ,string} 入参口径</b>：JSON 字符串与 JSON 整数都接受，
 *             浮点 / 布尔 / 超出 int64 一律拒 ⇒ 由 {@link StringInt64#parse} 逐条承接 ✔；</li>
 *         <li><b>{@code ,optional} 口径</b>：{@code title} / {@code cover} <b>不加</b>
 *             {@code @NotNull} ⇒ 缺席与显式 null 都合法 ⇒ 与 B 侧逐条等价 ✔。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code CreateFavoriteReq.java:56-62} 的 {@code assertGoRequiredFields}
 *       ＋ {@code GoStyleRequired.field("folderId"/"targetId"/"targetType", …)}
 *       ＋ {@code CreateFavoriteReq.java:71-73} 的 {@code GoStyleStringInt64.parse}。</li>
 * </ol>
 *
 * @param folderId   所属收藏夹 id（必填；JSON 字符串与整数都接受）
 * @param targetId   被收藏对象 id（必填；<b>原样透传</b>，api 层不规范化）
 * @param targetType 被收藏对象类型（必填；<b>原样透传</b>，api 层不 lower / 不 trim）
 * @param title      标题（可选；缺席与显式 null 都合法）
 * @param cover      封面（可选；缺席与显式 null 都合法）
 */
public record CreateFavoriteReq(
        @NotNull Long folderId,
        @NotNull String targetId,
        @NotNull String targetType,
        String title,
        String cover) {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 承接 B 侧的「{@code Object} 型 setter」。
     *
     * <p>⚠️ <b>{@code folderId} 的入参必须是 {@link Object}</b>：Go 的 {@code ,string} 是
     * 「入参方向宽松（字符串与数字都收）」，只有不声明目标类型、让 Jackson 原样递出，
     * 才能同时表达「两种都收」。
     *
     * <p>⚠️ <b>{@code targetId} / {@code targetType} 的入参声明成 {@link String}</b>：
     * B 侧这两个字段<b>没有</b> {@code ,string} ⇒ JSON 数字是「type mismatch」（B 侧拒绝）
     * ⇒ 由共享层的 Jackson 强转补强拒绝（{@code LogicalType.Textual + Integer/Float/Boolean → Fail}
     * · {@code common/src/main/java/com/rag/common/web/Coercion.java:174-178}）。
     *
     * @param rawFolderId {@code folderId} 原始值（字符串或整数都接受）
     * @param targetId    {@code targetId} 原始值
     * @param targetType  {@code targetType} 原始值
     * @param title       {@code title} 原始值（可选）
     * @param cover       {@code cover} 原始值（可选）
     * @throws IllegalArgumentException {@code folderId} 是浮点 / 布尔 / 非十进制整数 / 超出 int64
     */
    @JsonCreator
    public static CreateFavoriteReq fromJson(@JsonProperty("folderId") Object rawFolderId,
                                             @JsonProperty("targetId") String targetId,
                                             @JsonProperty("targetType") String targetType,
                                             @JsonProperty("title") String title,
                                             @JsonProperty("cover") String cover) {
        return new CreateFavoriteReq(
                StringInt64.parse(rawFolderId, "folderId"),
                targetId,
                targetType,
                title,
                cover);
    }
}
