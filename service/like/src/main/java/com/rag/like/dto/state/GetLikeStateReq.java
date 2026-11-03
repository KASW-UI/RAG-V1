package com.rag.like.dto.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.rag.common.web.RawStringListDeserializer;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * <p><b>某用户对一批目标的点赞状态查询请求体</b> —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GetLikeStateReq.java:30-72}
 * （可变 class ＋ 手写必填校验）。B 侧原始形态：
 * <pre>
 * type GetLikeStateReq struct {
 *     TargetType string   `json:"target_type"`
 *     TargetIds  []string `json:"target_ids"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /like/v1/getlikestate}（<b>需 uid</b>）。
 *
 * <h3>⚠️ 两个字段都必填（B 侧 DRIFT-LIKEAPI-002 · 2026-10-22 修正）</h3>
 * <p>本类<b>曾经</b>认为「Slice 不计必填」，该判断已被独立复核（活体探针）推翻；
 * 完整推导见 {@link GetLikeCountReq} 类注释中
 * {@code unmarshaler.go:944-950 → processFieldNotFromString(emptyMap)
 * → typeKind(Slice) != valueKind(Map) → newTypeMismatchError} 这一段。
 *
 * <p>可观察后果：{@code {"target_type":"article"}} 两侧都是
 * <b>HTTP 200 + {code:5001}</b> 且<b>不发出 RPC</b>；
 * {@code target_ids: []} 是合法输入，会一路走到领域层的 2001 分支。
 *
 * <p><b>⚠️ 本 DTO 关联的是「有 uid」的端点</b>：B 侧 {@code getlikestatelogic.go:37-49}
 * 会从 ctx 取 {@code userId}，取不到返回 <b>1006</b>。但那属于<b>业务层</b>的判定
 * （{@code service/LikeApiSupport#userId}），<b>不</b>是 DTO 的必填校验 —— 两者不要混。
 *
 * @param targetType 目标对象类型（必填）
 * @param targetIds  目标 id 列表（必填；可为空数组；元素可为 null）
 */
public record GetLikeStateReq(
        @NotNull String targetType,
        @NotNull @JsonDeserialize(using = RawStringListDeserializer.class) List<String> targetIds) {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 显式转发，<b>不</b>改写任何值。
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
     * ⇒ ⇒ 删掉工厂参数上这一处 ⇒ 一旦 JSON 名改动，{@code target_ids} 就会**静默**改用默认反序列化器
     * （接住 bool ⇒ 与 B 侧的 {@code errTypeMismatch} 不符）。
     *
     * @param targetType {@code target_type} 原始值
     * @param targetIds  {@code target_ids} 原始值（由 {@link RawStringListDeserializer} 逐条判定）
     */
    @JsonCreator
    public static GetLikeStateReq fromJson(@JsonProperty("target_type") String targetType,
                                           @JsonProperty("target_ids")
                                           @JsonDeserialize(using = RawStringListDeserializer.class)
                                           List<String> targetIds) {
        return new GetLikeStateReq(targetType, targetIds);
    }
}
