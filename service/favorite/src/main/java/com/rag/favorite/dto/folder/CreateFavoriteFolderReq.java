package com.rag.favorite.dto.folder;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>新建收藏夹的请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/CreateFavoriteFolderReq.java:33-49}
 * （可变 class ＋ 手写必填校验器）。B 侧原始形态：
 * <pre>
 * type CreateFavoriteFolderReq struct {
 *     Name string `json:"name"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /favorite/v1/folder/create}（<b>需 uid</b>）。
 *
 * <h3>⚠️ 必填判定：空串是<b>合法值</b></h3>
 * <p>Go 的 tag <b>没有</b> {@code ,optional} ⇒ 必填
 * （go-zero 的 {@code core/mapping} 只认 {@code ,optional}，{@code ,omitempty} 不算）。
 * <p>B 侧实测（{@code CreateFavoriteFolderReq.java:18-25}）：
 * <pre>
 *   {"name":"x"}   → OK
 *   {"name":""}    → OK          ← 空串是合法值（json 通道不判空串）
 *   {}             → field "name" is not set      ⇒ 5003
 *   {"name":null}  → field "name" mustn't be nil ⇒ 5003
 *   {"name":123}   → type mismatch for field "name", expect "string", actual "number" ⇒ 5003
 * </pre>
 *
 * <p>⚠️ <b>缺席 vs 显式 null</b>：Go 对这两种情况给出<b>不同</b>的文案
 * （{@code is not set} / {@code mustn't be nil}），但在本域<b>落到同一个出口</b>
 * —— handler 把任何 {@code httpx.Parse} 错误都写成 {@code {code:5003, msg:"参数错误"}}。
 * ⇒ 线上可观察的响应<b>完全一致</b>，故此处按 like-api 的既有做法用
 * {@code @NotNull} 统一判 null，不再单独维护 presence 标志。
 *
 * <h3>⚠️ C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：把 B 侧的<b>手写必填校验器</b>
 *       （{@code assertGoRequiredFields()} ＋ {@code GoStyleRequired.field}）
 *       换成 <b>Bean Validation</b>（{@code @NotNull}）—— A10 断言要求的形态；
 *       <b>可变 class → immutable record</b>（D5 + A9）；<b>包名压平</b>（D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>必填口径</b>：B 侧 {@code GoStyleRequired.field} <b>只判 null</b>（空串合法）
 *             ⇒ {@code @NotNull}（<b>不是</b> {@code @NotBlank}）同样只判 null
 *             ⇒ 缺席 / 显式 null 被拒、<b>空串放行</b> ⇒ 逐条等价 ✔；</li>
 *         <li><b>类型口径</b>：B 侧 JSON 数字进 string 字段是「type mismatch」（拒）
 *             ⇒ 由共享层的 Jackson 强转补强拒绝
 *             （{@code common/src/main/java/com/rag/common/web/Coercion.java:174-178}）✔。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code CreateFavoriteFolderReq.java:38-41} 的 {@code assertGoRequiredFields}
 *       ＋ {@code GoStyleRequired.field("name", name)}。</li>
 * </ol>
 *
 * @param name 收藏夹名（必填；<b>空串是合法值</b>）
 */
public record CreateFavoriteFolderReq(
        @NotNull String name) {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 显式转发。
     *
     * <p>⚠️ <b>{@code name} 的入参声明成 {@link String}</b>：B 侧这个字段<b>没有</b>
     * {@code ,string} ⇒ JSON 数字是「type mismatch」（B 侧拒绝）
     * ⇒ 由共享层的 Jackson 强转补强拒绝。
     *
     * @param name {@code name} 原始值
     */
    @JsonCreator
    public static CreateFavoriteFolderReq fromJson(@JsonProperty("name") String name) {
        return new CreateFavoriteFolderReq(name);
    }
}
