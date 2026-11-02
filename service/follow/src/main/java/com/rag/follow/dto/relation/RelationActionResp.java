package com.rag.follow.dto.relation;

/**
 * <p><b>动作类响应体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/dto/RelationActionResp.java}
 * （可变 class + 静态工厂）。B 侧原始形态：
 * <pre>
 * type RelationActionResp struct {
 *     Success bool `json:"success"`
 * }
 * </pre>
 *
 * <p><b>使用它的 4 个端点</b>：{@code /follow/v1/follow}、{@code /follow/v1/unfollow}、
 * {@code /follow/v1/block}、{@code /follow/v1/unblock}。
 *
 * <h3>⚠️ {@code success} 永远是 {@code true}，而且这是 B 侧的既定行为</h3>
 * <p>B 侧 4 个 logic 的成功出口<b>全都</b>是
 * {@code return &types.RelationActionResp{Success: true}, nil}，
 * 而<b>所有失败路径都是 {@code return nil, err}</b> ⇒
 * 失败时响应是 {@code nil}，根本走不到序列化。
 * ⇒ 所以本 DTO <b>只存在「true」这一个取值</b>，
 * <b>不需要也不应该</b>加 {@code false} 分支（B 侧没有这个状态）。
 *
 * <p>换句话说，「关注失败」在两侧都是靠<b>envelope 的 code</b> 表达的
 * （{@code {code:5201,...}} 之类），而不是 {@code data.success=false}。
 * 若本类加一个可传 {@code false} 的构造器，就是凭空造出一个 B 侧不存在的响应形状。
 *
 * <p><b>⚠️ 为什么这个类存在、而不是直接返回 {@code Map.of("success", true)}</b>：
 * B 侧是一个具名 struct ⇒ 保留同名 DTO 让「B 侧 struct ↔ C 侧 DTO」的对应关系
 * 在 review 时可逐条核对（L1 的可审性）。
 *
 * <p><b>⚠️ 表达层差异（L4 · 语义等价）</b>：B 侧是「可变 class + {@code static ok()} 工厂」，
 * C 侧改为 record ⇒ <b>JSON 键（{@code "success"}）、键序、取值域（恒 true）逐条一致</b>；
 * 差异只在「可变 class → immutable record」这一形态变化（D5）。
 *
 * @param success 恒为 {@code true}（B 侧只在成功路径构造本响应）
 */
public record RelationActionResp(
        boolean success
) {

    /**
     * B 侧 4 个 logic 成功出口的唯一构造方式（恒为 {@code true}）。
     *
     * @return {@code {"success":true}}
     */
    public static RelationActionResp ok() {
        return new RelationActionResp(true);
    }
}
