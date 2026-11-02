package com.rag.follow.dto.list;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringInt64;

/**
 * <p><b>列表类请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/dto/FollowListReq.java}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type FollowListReq struct {
 *     UserId int64 `json:"user_id,string,optional"`
 *     Offset int32 `json:"offset,optional"`
 *     Limit  int32 `json:"limit,optional"`
 * }
 * </pre>
 *
 * <p><b>使用它的 4 个端点</b>：{@code /follow/v1/follow/list}、{@code /follow/v1/follower/list}、
 * {@code /follow/v1/block/list}、{@code /follow/v1/recommendations}。
 *
 * <h3>⚠️ 三个字段全部 {@code ,optional} ⇒ 永不做必填校验</h3>
 * <p>缺席即零值（B 侧的零值 struct：user_id=0 / offset=0 / limit=0）⇒
 * C 侧<b>三个分量都不加 {@code @NotNull} / {@code @NotBlank}</b>。
 * <p>⚠️ <b>这是与 {@code RelationActionReq} 的关键差异</b>：那边 {@code target_id} 必填、
 * 这边三个全可选 ⇒ 两个 DTO 的控制器侧 {@code @Valid} 行为**不同**（见控制器 javadoc）。
 *
 * <h3>⚠️ {@code user_id} 带 {@code ,string} ⇒ 字符串与数字都接受</h3>
 * <p>根因与逐条对照见共享层 {@link StringInt64} 的类注释
 * （B 侧 {@code json.Number} 的 {@code reflect.Kind()} 是 {@code string} ⇒ go-zero 的类型判定放行数字）。
 * ⇒ 由 {@link StringInt64#parse} 完全接管判定，入参声明 {@code Object}。
 *
 * <h3>⚠️ {@code offset} / {@code limit} <b>没有</b> {@code ,string} ⇒ JSON 字符串被拒</h3>
 * <p>B 侧 go-zero 的 {@code core/mapping/utils.go:376} 只在出现 {@code stringOption} 时把
 * {@code opts.FromString} 置 true ⇒ {@code offset}/{@code limit} 走
 * {@code processFieldNotFromString} ⇒ 类型不匹配 ⇒ <b>解析失败</b>。
 * <p>⇒ C 侧这两个分量声明为 {@code int}，并由<b>域内</b> Jackson 强转补强
 * （{@code LogicalType.Integer + String → Fail}）拒绝 JSON 字符串。
 * ⚠️ <b>共享层 {@code Coercion} 刻意保留「String → 整数字段接受」</b>
 * （users 域的 {@code uid} 带 {@code ,string}，需要这条）⇒
 * ⇒ <b>follow 域必须自己叠加一条补强</b>，否则 {@code {"offset":"10"}} 会被静默接受
 * （B 侧拒 / C 侧收 ⇒ 多放行不会报错 ⇒ 最难发现的一类漂移）。
 *
 * <p><b>⚠️ {@code user_id} 的语义不是「查谁」，而是「请求谁的视角」</b>：
 * 4 个端点里有 2 个（{@code block/list} 与 {@code recommendations}）会把它与当前登录用户比对，
 * 不一致就返回 {@code ErrorForbidden}（5005）；另外 2 个（{@code follow/list}、
 * {@code follower/list}）<b>不比对</b>，即任意登录用户可查任意人的关注/粉丝列表。
 * 这个不对称是 B 侧 {@code resolveUserID} + 各 logic 自带分支的结果，
 * 详见 {@code FollowApiSupport#resolveUserId} 与 4 个读用例的注释。
 *
 * <p><b>⚠️ 访问器命名</b>：record 的分量访问器是 {@code userId()}（无 get 前缀）⇒
 * 与 B 侧的 {@code userId()} 命名<b>恰好一致</b>；同时 record 没有 setter ⇒
 * 彻底切断了 B 侧那条「getter 返回 Long / setter 接受 Object ⇒ Jackson 属性内省歧义」的路。
 *
 * @param userId 请求的用户视角（可选；缺席 / 显式 null / 非正数时由服务层归一化成当前登录用户）
 * @param offset 分页偏移（可选；负数在 rpc 侧被夹成 0）
 * @param limit  分页上限（可选；{@code <= 0} 在 rpc 侧被换成默认值，再被截断到上限）
 */
public record FollowListReq(
        Long userId,
        int offset,
        int limit
) {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— {@code user_id} 的入参是 {@code Object}。
     *
     * <p>⚠️ {@code offset} / {@code limit} <b>不在</b>本工厂里手工解析 ⇒
     * 它们走 Jackson 对 {@code int} 分量的常规绑定 ⇒
     * 配合<b>域内</b> Jackson 强转补强（{@code LogicalType.Integer + String → Fail}）
     * ⇒ {@code {"offset":"10"}} 被拒（与 B 侧同形）。
     *
     * @param rawUserId JSON 原始值：<b>字符串或整数都接受</b>
     * @param offset    JSON 数字（B 侧无 {@code ,string} ⇒ 字符串被拒）
     * @param limit     JSON 数字（同上）
     * @throws IllegalArgumentException {@code user_id} 是浮点 / 布尔 / 非十进制整数 / 超出 int64
     */
    @JsonCreator
    public static FollowListReq fromJson(@JsonProperty("user_id") Object rawUserId,
                                         @JsonProperty("offset") int offset,
                                         @JsonProperty("limit") int limit) {
        return new FollowListReq(StringInt64.parse(rawUserId, "user_id"), offset, limit);
    }
}
