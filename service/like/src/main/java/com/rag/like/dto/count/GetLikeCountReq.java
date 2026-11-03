package com.rag.like.dto.count;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.rag.common.web.RawStringListDeserializer;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * <p><b>批量点赞 / 点踩数查询请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GetLikeCountReq.java:35-91}
 * （可变 class ＋ 手写必填校验）。B 侧原始形态：
 * <pre>
 * type GetLikeCountReq struct {
 *     TargetType string   `json:"target_type"`
 *     TargetIds  []string `json:"target_ids"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/getlikecount}（<b>无 uid</b> —— 公开读）。
 *
 * <h3>⚠️ 两个字段都必填（B 侧 DRIFT-LIKEAPI-002 · 2026-10-22 修正）</h3>
 * <p>本类<b>曾经</b>把 {@code target_ids} 当作「Slice 不计必填」，那份判断是错的，
 * 且被两条绿测试以「Go: Slice 永不计必填」的措辞锁死 ⇒ 现已更正。
 *
 * <h4>Slice 到底必不必填 —— 只看 tag 会得出错误结论</h4>
 * <p>直觉是「必填只约束标量，Slice/Map 缺席就填空值放行」。B 侧的实际控制流不是这样：
 * <pre>
 * // core/mapping/unmarshaler.go:944-950（processNamedFieldWithoutValue）
 * case reflect.Array, reflect.Map, reflect.Slice:
 *     if !opts.optional() {
 *         return u.processFieldNotFromString(fieldType, value, valueWithParent{
 *             value: emptyMap,            ← 喂进去的是一个**空 map**，不是「保持 nil」
 *         }, opts, fullName)
 *     }
 * ...
 * default:                             ← String / Int 等标量才落到这里
 *     if !opts.optional() { return newInitError(fullName) }   ← field %q is not set
 * </pre>
 * Slice 分支<b>不是 {@code return nil}</b>，而是把 {@code emptyMap} 交给
 * {@code processFieldNotFromString}，后者落到 {@code default:} →
 * {@code processFieldPrimitive}，此时
 * <b>{@code typeKind(Slice) != valueKind(Map)}</b> → {@code newTypeMismatchError}
 * ⇒ 报的是 {@code type mismatch for field "target_ids"}。
 *
 * <p><b>⇒ 结论：无 tag 的 Slice 与标量一样必填</b>，只是<b>错误文案不同</b>
 * （标量是 {@code field "x" is not set}，Slice 是 {@code type mismatch}）。
 * 显式 {@code null} 走另一条：{@code unmarshaler.go:856-862} 的
 * {@code if mapValue == nil { if optional {return nil}; return mustn't be nil }}。
 *
 * <p><b>对客户端的可观察后果</b>：{@code {"target_type":"article"}} 在两侧都是
 * <b>HTTP 200 + {code:5001}</b>，且<b>都不会发出这次 RPC</b>。
 * 反过来，「必填」= 键必须在，{@code target_ids: []} 是<b>合法</b>输入，
 * 它会一路走到 like 域的 2001「输入需要查询的ID为空」。
 *
 * <h3>⚠️ C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：把 B 侧的<b>手写必填校验器</b>换成 <b>Bean Validation</b>
 *       （{@code @NotNull}）—— 即 A10 断言要求的形态。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       B 侧的 {@code GoStyleRequired.field("target_ids", targetIds)} <b>只判 null</b>
 *       ⇒ {@code @NotNull}（<b>不是</b> {@code @NotBlank}）同样只判 null
 *       ⇒ 缺席 / 显式 null 被拒、空数组放行 ⇒ 逐条等价 ✔
 *       （⚠️ 「空数组放行」正是 B 侧的语义：{@code []} ≠ 缺席）。</li>
 *   <li><b>B 出处</b>：{@code GetLikeCountReq.java} 的 {@code assertGoRequiredFields}。</li>
 * </ol>
 *
 * <h3>⚠️ {@code target_ids} 的元素语义（null 元素是 B 侧的真实语义）</h3>
 * <p>{@link RawStringListDeserializer} 逐条对齐 B 侧的
 * {@code core/mapping/unmarshaler.go:156-183}：<b>数组里的 null 元素被保留为 null</b>
 * （不是被过滤掉）⇒ 后续由 {@code service/LikeApiSupport} 的归一化处理
 * （全 null ⇒ 等价空列表；部分 null ⇒ 该位变空串、<b>长度不变</b>）。
 *
 * @param targetType 目标对象类型（必填）
 * @param targetIds  目标 id 列表（必填；可为空数组；元素可为 null）
 */
public record GetLikeCountReq(
        @NotNull String targetType,
        @NotNull @JsonDeserialize(using = RawStringListDeserializer.class) List<String> targetIds) {

    /**
     * Jackson 的<b>唯一</b>反序列化入口。
     *
     * <p>⚠️ <b>为什么需要它</b>：record 的规范构造器参数类型 = 分量类型
     * （{@code List<String>}）⇒ 绑定器会直接调 {@link RawStringListDeserializer}。
     * ⇒ 本工厂只做「显式转发」，<b>不</b>改写任何值 ——
     * <b>特别是 {@code null} 元素必须原样保留</b>（见类注释）。
     *
     * <p>⚠️ <b>为什么 {@code @JsonDeserialize} 在<b>工厂参数</b>上也要写一遍</b>
     * （组件上也有同一个注解，<b>两处都留</b>）：
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
     * ⇒ ⚠️ 「注解写对了但没生效」的形态正是 AGENTS.md §3.3 点名的
     * （<b>配置/注解存在 ≠ 机制生效</b>）⇒ 本类的<b>结果</b>由
     * {@code LikeDtoWireShapeTest} 钉住，本类的<b>机制</b>由
     * {@code LikeJsonCreatorAnnotationScopeTest} 钉住（**两者缺一不可**）。
     *
     * @param targetType {@code target_type} 原始值
     * @param targetIds  {@code target_ids} 原始值（由 {@link RawStringListDeserializer} 逐条判定）
     */
    @JsonCreator
    public static GetLikeCountReq fromJson(@JsonProperty("target_type") String targetType,
                                           @JsonProperty("target_ids")
                                           @JsonDeserialize(using = RawStringListDeserializer.class)
                                           List<String> targetIds) {
        return new GetLikeCountReq(targetType, targetIds);
    }
}
