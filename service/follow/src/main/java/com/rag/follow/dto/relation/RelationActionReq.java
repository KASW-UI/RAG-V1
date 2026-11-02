package com.rag.follow.dto.relation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>关注 / 取关 / 拉黑 / 解拉黑四个动作的请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/dto/RelationActionReq.java}
 * （可变 class ＋ 手写必填校验）。B 侧原始形态：
 * <pre>
 * type RelationActionReq struct {
 *     TargetId int64 `json:"target_id,string"`     // ← 注意：没有 ,optional
 * }
 * </pre>
 *
 * <p><b>使用它的 4 个端点</b>：{@code /follow/v1/follow}、{@code /follow/v1/unfollow}、
 * {@code /follow/v1/block}、{@code /follow/v1/unblock}。四者的请求形状<b>完全相同</b>
 * （B 侧也共用同一个 struct），但<b>响应不同</b>：动作类返回
 * {@link RelationActionResp}，列表类返回 {@code FollowUserListResp} / {@code RecommendListResp}。
 *
 * <h3>⚠️ {@code target_id} <b>没有</b> {@code ,optional} ⇒ 必填</h3>
 * <p>B 侧 go-zero 的 {@code core/mapping/unmarshaler.go:918-969} 对标量字段在 {@code !opts.optional()}
 * 时走 {@code newInitError} ⇒ {@code field "target_id" is not set} ⇒ 解析失败 ⇒
 * <b>HTTP 200 + {code:5001}</b>（本服务注册了错误处理器 ⇒ 解析失败也是 200 + envelope，<b>不是</b> 400）。
 * <p>缺席与显式 {@code null} 在 B 侧是两条不同文本，但本服务把两者都折叠成
 * {@code {code:5001}}（msg 恒为 {@code "internal server error"}）⇒ 文本差异不可观测。
 *
 * <p><b>C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：把 B 侧的<b>手写必填校验器</b>换成 <b>Bean Validation</b>
 *       （{@code @NotNull}）—— 即 A10 断言要求的形态（本仓禁止手写校验器）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— B 侧的 {@code GoStyleRequired.field("target_id", targetId)}
 *       <b>只判 null</b>（空串合法）⇒ {@code @NotNull}（<b>不是</b> {@code @NotBlank}）
 *       同样只判 null ⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ 逐条等价 ✔。
 *       违规时两侧都产出「解析失败」⇒ 都被异常处理器折叠成 {@code {code:5001}} ✔。</li>
 *   <li><b>B 出处</b>：{@code RelationActionReq.java} 的 {@code assertGoRequiredFields} ＋
 *       {@code GoStyleRequired.field("target_id", targetId)}。</li>
 * </ol>
 *
 * <h3>⚠️ {@code target_id} 带 {@code ,string} ⇒ 字符串与数字都接受</h3>
 * <p>B 侧 {@code ,string} 的语义是「<b>入参宽松</b>：JSON 字符串与 JSON 数字都接受」
 * （根因与逐条对照见共享层 {@link StringInt64} 的类注释）。
 * ⇒ C 侧用一个<b>静态工厂 + {@link JsonCreator}</b>接管解析，入参声明为 {@link Object}：
 * <ul>
 *   <li>{@code "123"} → 123 ✔ · {@code 123} → 123 ✔ · {@code 1.5} → 拒 ✔
 *       · {@code "1.5"} → 拒 ✔ · {@code true} → 拒 ✔ · 超 int64 → 拒 ✔</li>
 *   <li>⚠️ <b>入参必须是 {@code Object} 而不是 {@code Long}</b>：
 *       声明成 {@code Long} 时 Jackson 会自己做类型强转 ⇒ {@code 1.5} 会被<b>截断</b>成 1
 *       ⇒ 那正是 B 侧明确拒绝的输入 ⇒ 是实打实的行为漂移。</li>
 * </ul>
 * ⇒ 解析后的 record 分量类型是 {@link Long}（业务层拿到的就是数值），
 * <b>但 Jackson 的入口是 {@code Object}</b> ⇒ 两个职责分离。
 *
 * <p><b>⚠️ 本层不校验 {@code target_id > 0}</b>：B 侧同样不校验 ——
 * 4 个 logic 都是「拿到 userID 就直接透传给 rpc」，
 * {@code target_id <= 0} 的判定在 rpc 侧（参数校验 / 自关系校验）。
 * api 层提前拒会把业务码从 rpc 的那个换成 5003/5001 ⇒ 是新增行为。
 *
 * @param targetId 目标用户 id（必填；可正可负可零 —— B 侧不校验取值）
 */
public record RelationActionReq(
        @NotNull Long targetId
) {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 入参 {@code Object}，由 {@link StringInt64} 判定。
     *
     * <p>⚠️ <b>静态工厂而不是规范构造器</b>：record 的规范构造器参数类型 = 分量类型（{@code Long}），
     * 而 {@code Long} 会让 Jackson 自己做强转 ⇒ 必须另开一个入参为 {@code Object} 的
     * {@link JsonCreator} 工厂来接管。
     *
     * @param raw JSON 原始值：<b>字符串或整数都接受</b>（B 侧两者等价）
     * @throws IllegalArgumentException 浮点 / 布尔 / 非十进制整数 / 超出 int64
     */
    @JsonCreator
    public static RelationActionReq fromJson(@JsonProperty("target_id") Object raw) {
        return new RelationActionReq(StringInt64.parse(raw, "target_id"));
    }
}
