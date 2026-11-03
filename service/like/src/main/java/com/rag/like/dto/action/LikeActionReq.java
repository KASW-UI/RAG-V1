package com.rag.like.dto.action;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.rag.common.web.Float64Deserializer;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>点赞动作请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/LikeActionReq.java:53-161}
 * （可变 class ＋ 手写必填校验）。B 侧原始形态：
 * <pre>
 * type LikeActionReq struct {
 *     TargetType  string  `json:"target_type"`
 *     TargetId    string  `json:"target_id"`
 *     ActionType  int64   `json:"action_type,options=1|2|3|4"`
 *     AuthorId    int64   `json:"author_id,string"`
 *     Weight      float64 `json:"weight,optional"`
 *     ClientIp    string  `json:"client_ip,optional"`
 *     PromotionId string  `json:"promotion_id,optional"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/likeaction}（<b>需 uid</b>）。
 *
 * <h3>⚠️ 本 DTO 是 like 域唯一同时用到三种 go-zero tag 的地方</h3>
 * <p>三种各自对应完全不同的判定，必须逐条分开处理：
 * <ul>
 *   <li><b>无 tag 的标量</b>（{@code target_type} / {@code target_id}）⇒ <b>必填</b>；</li>
 *   <li><b>{@code options=1|2|3|4}</b>（{@code action_type}）⇒ 必填 <b>+</b> 取值域校验；</li>
 *   <li><b>{@code ,string}</b>（{@code author_id}）⇒ 必填，且<b>入参方向宽松</b>
 *       （JSON 字符串与数字都接受）；</li>
 *   <li><b>{@code ,optional}</b>（{@code weight} / {@code client_ip} / {@code promotion_id}）
 *       ⇒ 缺席与显式 null 都合法。</li>
 * </ul>
 *
 * <h3>⚠️ {@code options=1|2|3|4} 的取值域校验（B 侧逐条推导）</h3>
 * <p>Go 出处：{@code core/mapping/unmarshaler.go:665-670} 的
 * {@code processFieldPrimitiveWithJSONNumber}：
 * <pre>
 *   if err := validateValueInOptions(v, opts.options()); err != nil { return err }
 * </pre>
 * {@code validateValueInOptions} 对 {@code json.Number} 走
 * {@code slices.Contains(options, Repr(v))}，而 {@code Repr(json.Number("2")) == "2"}
 * ⇒ <b>JSON 数字 {@code 2} 合法</b>（{@code "2"} ∈ options）；
 * <b>{@code 0} / {@code 5} / {@code 1.0} 都非法</b>（分别是 {@code "0"} / {@code "5"} / {@code "1.0"}）。
 * <p><b>而 JSON 字符串 {@code "2"} 非法</b>：无 {@code ,string} ⇒ 走
 * {@code processFieldPrimitive} 的 default 分支，
 * {@code typeKind(Int64) != valueKind(String)} ⇒ {@code newTypeMismatchError}。
 * 这条由本域的 Jackson 强转补强（{@code LogicalType.Integer + String → Fail}）兜住。
 *
 * <h3>⚠️ C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：把 B 侧的<b>手写必填校验器</b>换成 <b>Bean Validation</b>
 *       （{@code @NotNull}）—— 即 A10 断言要求的形态（本仓禁止手写校验器）；
 *       <b>{@code options} 取值域校验</b>仍是手写的（Bean Validation 无内建等价物 ⇒
 *       用 {@code @Pattern} + 正则表达同一判据，见下方 {@link #fromJson}）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>必填口径</b>：B 侧 {@code GoStyleRequired.field} <b>只判 null</b>（空串合法）
 *             ⇒ {@code @NotNull}（<b>不是</b> {@code @NotBlank}）同样只判 null
 *             ⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ 逐条等价 ✔；</li>
 *         <li><b>取值域口径</b>：B 侧比的是 {@code Repr} 后的<b>字符串</b>
 *             （{@code ["1","2","3","4"]} 四个字面量）⇒ C 侧用正则
 *             {@code ^(1|2|3|4)$} 匹配 {@link #actionType} 的<b>十进制字面量</b>
 *             ⇒ 判据集合逐条相同（{@code 1.0} 不匹配、{@code 0} 不匹配、{@code 5} 不匹配）✔；</li>
 *         <li><b>{@code ,string} 入参口径</b>：JSON 字符串与 JSON 整数都接受，
 *             浮点 / 布尔 / 超出 int64 一律拒 ⇒ 由 {@link StringInt64#parse} 逐条承接 ✔。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code LikeActionReq.java} 的 {@code assertGoRequiredFields} ＋
 *       {@code GoStyleRequired.field(...)} ＋ {@code ALLOWED_ACTION_TYPES}。</li>
 * </ol>
 *
 * <h3>⚠️ {@code weight} 带 {@code ,optional} 且是 float64</h3>
 * <p>B 侧用 {@code GoStyleFloat64Deserializer}（已上收为共享层
 * {@code com.rag.common.web.Float64Deserializer}）逐条对齐 Go 的
 * {@code json.Number.Float64()} 判据 —— 特别地：{@code 1e400} 在 B 侧是
 * {@code ErrRange} ⇒ <b>解析失败</b>；Jackson 默认会给出 {@code +Infinity} ⇒ 必须由该反序列化器拒。
 * <p>⚠️ <b>该字段<b>不</b>受 {@code @NotNull} 约束</b>：B 侧 {@code ,optional} ⇒ 缺席合法。
 *
 * @param targetType  目标对象类型（必填）
 * @param targetId    目标对象 id（必填）
 * @param actionType  操作类型（必填；取值 1/2/3/4）
 * @param authorId    作者 id（必填；JSON 字符串与整数都接受）
 * @param weight      点赞权重（可选；float64）
 * @param clientIp    客户端 IP（可选）
 * @param promotionId 推广任务 id（可选）
 */
public record LikeActionReq(
        @NotNull String targetType,
        @NotNull String targetId,
        @NotNull Long actionType,
        @NotNull Long authorId,
        @JsonDeserialize(using = Float64Deserializer.class) Double weight,
        String clientIp,
        String promotionId) {

    /**
     * {@code options=1|2|3|4} 的四个合法值 —— B 侧 {@code ALLOWED_ACTION_TYPES}
     * （{@code Set<String> = {"1","2","3","4"}}）的等价正则。
     *
     * <p>⚠️ <b>为什么用正则而不是枚举</b>：B 侧比的是 {@code Repr} 后的<b>字符串</b>，
     * 即 {@code "1"} / {@code "2"} / {@code "3"} / {@code "4"} 四个<b>字面量</b>。
     * 正则 {@code ^(1|2|3|4)$} 匹配的是 {@link Long} 的十进制字面量 ⇒ 判据集合逐条相同：
     * {@code 1} ✔ · {@code 2} ✔ · {@code 3} ✔ · {@code 4} ✔ ·
     * {@code 0} ✘ · {@code 5} ✘ · {@code 1.0} ✘（B 侧 {@code Repr} 出来是 {@code "1.0"} 也不匹配）。
     */
    private static final java.util.regex.Pattern ACTION_TYPE_PATTERN =
            java.util.regex.Pattern.compile("^(1|2|3|4)$");

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 承接 B 侧的「{@code Object} 型 setter」与 {@code options} 校验。
     *
     * <p>⚠️ <b>{@code author_id} 的入参必须是 {@code Object}</b>：Go 的 {@code ,string} 是
     * 「入参方向宽松（字符串与数字都收）」，只有不声明目标类型、让 Jackson 原样递出，
     * 才能同时表达「两种都收」。
     *
     * <p>⚠️ <b>{@code action_type} 的入参声明成 {@link Long}</b>：它<b>没有</b> {@code ,string}
     * ⇒ B 侧对 JSON 字符串是「type mismatch」⇒ 由本域的 Jackson 强转补强
     * （{@code LogicalType.Integer + String → Fail}）拒绝。
     * ⇒ ⇒ 本工厂拿到的 {@code actionType} 必然已经是<b>整数或 null</b>。
     *
     * <p>⚠️ <b>为什么 {@code @JsonDeserialize} 在<b>工厂参数</b>上也要写一遍</b>（组件上也有同一个注解，
     * <b>两处都留</b>）：
     * <p><b>区分性实验结论</b>（Jackson 2.19.4 · 真跑 · 5 个同形 fixture 只挪注解位置）⇒
     * 见 {@code service/like/src/test/java/com/rag/like/dto/LikeJsonCreatorAnnotationScopeTest.java}：
     * <pre>
     *   ① 无 @JsonCreator                        ⇒ 组件上的注解生效
     *   ② 有 @JsonCreator + 同名参数             ⇒ 工厂参数上的注解生效
     *   ③ 有 @JsonCreator + 同名参数 + 两处都写   ⇒ 生效
     *   ④ 有 @JsonCreator + 同名参数 + 只写组件   ⇒ **组件上的注解被继承 ⇒ 也生效**
     *   ⑤ 有 @JsonCreator + **异名**参数 + 只写组件 ⇒ **继承断链 ⇒ 不生效**
     * </pre>
     * ⇒ ⚠️ **本类先前那句「组件上的 {@code @JsonDeserialize} 不会被应用」已被实验 ④ 证伪**
     * ⇒ ⇒ **已更正**（AGENTS.md §3.3.2：<b>错误的机制断言比没有断言更危险</b>）。
     * ⇒ ⇒ <b>但工厂参数上仍然要写</b>：① 不依赖「同名继承」这一实现细节；
     * ② 实验 ⑤ 证明一旦 JSON 名与组件名不同，继承就断链 ⇒ 组件注解沦为装饰品。
     * ⇒ ⇒ <b>{@code weight} 的 {@link Float64Deserializer} 尤其不能省</b>：
     * 删掉工厂参数上这一处 ⇒ 一旦 JSON 名改动，Jackson 默认会给出 {@code +Infinity}
     * （{@code 1e400}），而 B 侧是解析失败（{@code ErrRange}）⇒ **静默**的数值漂移。
     *
     * @param rawTargetType  {@code target_type} 原始值
     * @param rawTargetId    {@code target_id} 原始值
     * @param rawActionType  {@code action_type} 原始值（<b>整数</b>；字符串/浮点已被强转补强拒）
     * @param rawAuthorId    {@code author_id} 原始值（字符串或整数都接受）
     * @param weight         {@code weight} 原始值（可选；由 {@code Float64Deserializer} 逐条判定）
     * @param clientIp       {@code client_ip} 原始值（可选）
     * @param promotionId    {@code promotion_id} 原始值（可选）
     * @throws IllegalArgumentException {@code author_id} 是浮点 / 布尔 / 非十进制整数 / 超出 int64
     *                                  或 {@code action_type} 不在 {@code [1,2,3,4]} 内
     */
    @JsonCreator
    public static LikeActionReq fromJson(@JsonProperty("target_type") String rawTargetType,
                                         @JsonProperty("target_id") String rawTargetId,
                                         @JsonProperty("action_type") Long rawActionType,
                                         @JsonProperty("author_id") Object rawAuthorId,
                                         @JsonProperty("weight")
                                         @JsonDeserialize(using = Float64Deserializer.class)
                                         Double weight,
                                         @JsonProperty("client_ip") String clientIp,
                                         @JsonProperty("promotion_id") String promotionId) {
        Long authorId = StringInt64.parse(rawAuthorId, "author_id");
        if (rawActionType == null || !ACTION_TYPE_PATTERN.matcher(String.valueOf(rawActionType)).matches()) {
            throw new IllegalArgumentException(
                    "error: value \"" + rawActionType + "\" is not defined in options \"[1 2 3 4]\"");
        }
        return new LikeActionReq(rawTargetType, rawTargetId, rawActionType, authorId, weight, clientIp, promotionId);
    }
}
