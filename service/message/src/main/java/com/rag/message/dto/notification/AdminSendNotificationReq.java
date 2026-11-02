package com.rag.message.dto.notification;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.rag.common.web.MapStringValueDeserializer;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * <p><b>职责</b>：管理员发通知请求 —— 对应 Go {@code types.go} 的 {@code AdminSendNotificationReq}。
 *
 * <pre>
 * Go:
 *   type AdminSendNotificationReq struct {
 *       Broadcast bool              `json:"broadcast,optional"`
 *       TargetId  int64             `json:"target_id,string,optional"`
 *       Kind      int32             `json:"kind,optional"`
 *       Title     string            `json:"title"`
 *       Content   string            `json:"content"`
 *       Extra     map[string]string `json:"extra,optional"`
 *   }
 * </pre>
 *
 * <p><b>{@code title} / {@code content} 必填</b>（无 {@code ,optional}）；
 * {@code target_id} 与 {@code kind} 可选。
 *
 * <p><b>⚠️ {@code extra} 的「显式 null」<b>不</b>报错</b>（B 侧 2026-10-22 修正，DRIFT-MSGAPI-003）：
 * Go 侧 tag 是 {@code json:"extra,optional"} ⇒ {@code core/mapping/utils.go:376} 的
 * {@code parseOption} 置 {@code Optional = true} ⇒
 * {@code core/mapping/unmarshaler.go:857-862} 命中
 * <pre>
 *   if mapValue == nil {
 *       if optional { return nil }                      ← 本字段走这条，null 放行
 *       return fmt.Errorf("field %q mustn't be nil", key)
 *   }
 * </pre>
 * ⇒ C 侧<b>刻意不</b>对 {@code extra} 加任何必填约束。
 *
 * <p><b>⚠️ {@code extra} 的<b>元素</b>口径与字段本身不同</b>（B 侧 DRIFT-MSGAPI-006 / -007）：
 * <ul>
 *   <li><b>元素</b>收数字（{@code {"extra":{"k":1}}}）⇒ Go <b>接受</b>并字符串化
 *       ⇒ C 侧挂 {@code contentUsing = MapStringValueDeserializer.class}；</li>
 *   <li><b>元素</b>显式 null（{@code {"extra":{"k":null}}}）⇒ Go <b>拒绝</b>
 *       ⇒ 同一个反序列化器覆写 {@code getNullValue} 承担。</li>
 * </ul>
 *
 * <p><b>{@code target_id} 的越界检查不在这里</b>：Go 的
 * {@code sendadminnotificationlogic.go} 在<b>身份解析之后</b>才做
 * {@code if !req.Broadcast && req.TargetId <= 0 → ErrorInvalidParam(5003)}，
 * 且计入 {@code message_api_reject_total{reason="target_missing"}}。
 * 顺序不可颠倒：身份缺失（5004）先于参数非法（5003）。
 *
 * <p><b>{@code kind} 的语义</b>：0 表示「按 broadcast 推断」
 * （{@code shared.AdminNotificationKind}：broadcast ⇒ ADMIN_BROADCAST，否则 ADMIN_NOTICE）；
 * {@code >0} 则按客户端给的原值下发。见 {@code MessageApiSupport#adminNotificationKind}。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       B 侧手写 {@code assertGoRequiredFields()} → Bean Validation {@code @NotNull}（D-01 降级）；
 *       B 侧「{@code Object} 型 setter」→ <b>静态 {@code @JsonCreator} 工厂</b>；
 *       B 侧 {@code GoStyleStringInt64} → 共享层 {@code com.rag.common.web.StringInt64}（D3）；
 *       B 侧 {@code GoStyleMapStringValueDeserializer}（Jackson 3 基类）
 *       → 共享层 {@code com.rag.common.web.MapStringValueDeserializer}（Jackson 2 基类 · D14）；
 *       包名 {@code api.dto} → {@code dto.notification}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 必填口径 {@code GoStyleRequired.field} <b>只判 null</b>
 *       ⇒ {@code @NotNull}（<b>不是 {@code @NotBlank}</b>；{@code extra} <b>不加</b>约束）；
 *       {@code ,string} 入参口径由 {@code StringInt64.parse} 逐条承接；
 *       {@code extra} 的元素级口径由 {@code MapStringValueDeserializer} 逐条承接
 *       （数字收下并保留原始字面量、显式 null 拒绝、字段级 null 放行）。
 *       ⚠️ <b>实测（jackson-databind 2.19.4 探针）</b>：{@code contentUsing} 挂在
 *       record 组件上、且该 record 另有静态 {@code @JsonCreator} 工厂时，
 *       内容反序列化器<b>仍然生效</b>（{@code {"extra":{"k":1}}} ⇒ {@code {"k":"1"}}；
 *       {@code {"extra":{"k":null}}} ⇒ 拒绝；{@code "extra":null} ⇒ 放行）。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/AdminSendNotificationReq.java:63-154}
 *       （必填断言在 {@code :123-128}；{@code ,string} setter 在 {@code :79-83}；
 *       {@code extra} 的 {@code contentUsing} 在 {@code :117-121}）</li>
 * </ol>
 *
 * @param broadcast 是否广播（可选，缺席 = false）
 * @param targetId  定向目标 id（可选；JSON 字符串与整数都接受；
 *                  <b>越界判定在 message-rpc 侧</b>，不在本层）
 * @param kind      通知类型（可选；0 = 按 broadcast 推断）
 * @param title     标题（必填；空串是合法值）
 * @param content   正文（必填；空串是合法值）
 * @param extra     扩展信息（可选；<b>显式 null 合法</b>；元素收数字并字符串化、元素 null 拒绝）
 */
public record AdminSendNotificationReq(
        @JsonProperty("broadcast") boolean broadcast,
        @JsonProperty("target_id") Long targetId,
        @JsonProperty("kind") int kind,
        @NotNull @JsonProperty("title") String title,
        @NotNull @JsonProperty("content") String content,
        @JsonProperty("extra")
        @JsonDeserialize(contentUsing = MapStringValueDeserializer.class)
        Map<String, String> extra) {

    /**
     * 唯一的反序列化入口 —— 承接 B 侧的 {@code Object} 型 setter。
     *
     * <p><b>为什么入参声明成 {@code Object}</b>：{@code target_id} 带 {@code ,string}
     * ⇒ <b>同时接受 JSON 字符串和 JSON 数字</b> ⇒ 只有「不声明具体目标类型、让 Jackson 原样递出」
     * 才能表达这两条都通；随后由 {@link StringInt64} 完全接管判定。
     *
     * <p>⚠️ {@code extra} 的元素级口径挂在<b>记录组件</b>的 {@code contentUsing} 上
     * （不是挂在本工厂的参数上）⇒ record 的规范构造器与本工厂并存时，内容反序列化器仍生效。
     *
     * @param rawTargetId {@code target_id} 的原始 JSON 值
     * @param broadcast   {@code broadcast}（原样透传）
     * @param kind        {@code kind}（原样透传）
     * @param title       {@code title}（原样透传）
     * @param content     {@code content}（原样透传）
     * @param extra       {@code extra}（原样透传；元素级口径由组件上的 {@code contentUsing} 承担）
     * @return 解析后的 DTO
     */
    @JsonCreator
    public static AdminSendNotificationReq of(
            @JsonProperty("broadcast") boolean broadcast,
            @JsonProperty("target_id") Object rawTargetId,
            @JsonProperty("kind") int kind,
            @JsonProperty("title") String title,
            @JsonProperty("content") String content,
            @JsonProperty("extra") Map<String, String> extra) {
        return new AdminSendNotificationReq(
                broadcast,
                StringInt64.parse(rawTargetId, "target_id"),
                kind,
                title,
                content,
                extra);
    }
}
