package com.rag.comment.dto.comment;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>建评论请求</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/dto/CreateCommentReq.java:29-77}
 * （可变 class ＋ 手写必填校验 {@code assertGoRequiredFields}）。
 * B 侧原始形态：
 * <pre>
 * type CreateCommentReq struct {
 *     TargetType string `json:"target_type"`
 *     TargetId   string `json:"target_id"`
 *     RootId     int64  `json:"root_id"`
 *     ParentId   int64  `json:"parent_id"`
 *     Content    string `json:"content"`
 *     Meta       string `json:"meta"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /comment/v1/comment/create}（<b>需 uid</b>）。
 *
 * <h3>⚠️ 六个字段全部必填，且整数字段用包装类型</h3>
 * <p>B 侧判据：tag 里没有 {@code ,optional} ⇒ go-zero 视其为必填
 * （{@code core/mapping/utils.go:23 optionalOption} 只有 {@code ,optional} 会置
 * {@code Optional=true}；{@code unmarshaler.go:968} 的 {@code default:} 分支在
 * {@code !opts.optional()} 时抛 {@code field %q is not set}）。
 * ⇒ 缺字段 / 显式 {@code null} 都是解析失败 → 200 + 5001。
 *
 * <p><b>为什么 int64 必须是 {@link Long} 而不是 {@code long}</b>：Java 基本类型无法区分
 * 「body 里没给这个字段」与「客户端显式给了 0」，而 Go 侧两者行为不同 ——
 * 前者报错、后者是合法值。用包装类型让「缺席」停在 {@code null}，
 * 由 {@code @NotNull} 判出来。
 *
 * <p><b>无 {@code ,string}</b>：这六个字段在 wire 上都是 JSON 原生类型
 * （字符串 / 数字），不是字符串化的数字。
 *
 * <h3>⚠️ C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：把 B 侧的<b>手写必填校验器</b>
 *       （{@code GoStyleRequiredFields#assertGoRequiredFields}）换成 <b>Bean Validation</b>
 *       （{@code @NotNull}）—— 即 A10 断言要求的形态（本仓禁止手写校验器）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       B 侧 {@code GoStyleRequired.field} <b>只判 null</b>（空串合法）
 *       ⇒ {@code @NotNull}（<b>不是</b> {@code @NotBlank}）同样只判 null
 *       ⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ 逐条等价 ✔。</li>
 *   <li><b>B 出处</b>：{@code CreateCommentReq.java} 的 {@code assertGoRequiredFields}
 *       ＋ {@code GoStyleRequired.field(...)} 六条调用。</li>
 * </ol>
 *
 * @param targetType 目标对象类型（必填）
 * @param targetId   目标对象 id（必填）
 * @param rootId     根评论 id（必填；顶层评论为 0）
 * @param parentId   父评论 id（必填；顶层评论为 0）
 * @param content    评论正文（必填）
 * @param meta       附加元信息（必填）
 */
public record CreateCommentReq(
        @NotNull @JsonProperty("target_type") String targetType,
        @NotNull @JsonProperty("target_id") String targetId,
        @NotNull @JsonProperty("root_id") Long rootId,
        @NotNull @JsonProperty("parent_id") Long parentId,
        @NotNull @JsonProperty("content") String content,
        @NotNull @JsonProperty("meta") String meta) {
}
