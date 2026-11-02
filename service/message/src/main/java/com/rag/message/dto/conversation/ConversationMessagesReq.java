package com.rag.message.dto.conversation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>职责</b>：会话消息列表请求 —— 对应 Go {@code types.go} 的 {@code ConversationMessagesReq}。
 *
 * <pre>
 * Go:
 *   type ConversationMessagesReq struct {
 *       ConversationId int64 `json:"conversation_id,string"`            // 无 ,optional ⇒ 必填
 *       Offset         int32 `json:"offset,optional"`
 *       Limit          int32 `json:"limit,optional"`
 *   }
 * </pre>
 *
 * <p><b>{@code conversation_id} 带 {@code ,string}</b> ⇒ Go 侧解析层把这一字段标记为
 * {@code FromString}（{@code core/mapping/utils.go:376 parseOption} 的
 * {@code case option == stringOption: fieldOpts.FromString = true}），
 * 于是 {@code core/mapping/unmarshaler.go:889 processNamedFieldWithValueFromString} 第一行就是
 * <pre>
 *   valueKind := reflect.TypeOf(mapValue).Kind()
 *   if valueKind != reflect.String { return fmt.Errorf("the value in map is not string, but %s", valueKind) }
 * </pre>
 * <p>⇒ <b>JSON 字符串<b>与</b> JSON 数字都被接受</b>：这不是笔误，根因是
 * {@code encoding/json} 里 {@code type Number string} —— {@code json.Number} 的
 * {@code reflect.Kind()} 返回 {@code string} 而不是 {@code number}
 * （实测 {@code reflect.TypeOf(json.Number("123")).Kind() = string}）。
 * 于是上面那行 {@code if valueKind != reflect.String} 对 JSON 数字<b>放行</b>，
 * 再由 {@code fillPrimitive}（{@code unmarshaler.go:1115-1125}）里
 * <b>{@code case string} 与 {@code case json.Number} 两条分支</b>共同走
 * {@code strconv.ParseInt}。
 *
 * <p><b>为什么访问器叫 {@code conversationId()} 而不是 {@code getConversationId()}</b>：
 * 若同时存在返回 {@code Long} 的 getter 与接受 {@code Object} 的 setter，
 * Jackson 属性内省会看到 getter/setter 类型不一致，存在被字段优先解析的歧义。
 * 请求 DTO 从不需要序列化，用非 getter 命名彻底切断这条歧义路径。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       B 侧手写 {@code assertGoRequiredFields()} → Bean Validation {@code @NotNull}（D-01 降级）；
 *       B 侧「{@code Object} 型 setter」→ <b>静态 {@code @JsonCreator} 工厂</b>
 *       （record 没有 setter ⇒ 工厂方法是「唯一反序列化入口」的等价落点，
 *       顺带保留 B 侧「请求 DTO 从不需要序列化 ⇒ 访问器不用 getter 命名」的取舍）；
 *       B 侧 {@code GoStyleStringInt64} → 共享层 {@code com.rag.common.web.StringInt64}（D3）；
 *       包名 {@code api.dto} → {@code dto.conversation}（D7 + D11）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 必填口径 {@code GoStyleRequired.field} <b>只判 null</b>
 *       ⇒ {@code @NotNull}（<b>不是 {@code @NotBlank}</b>）；
 *       {@code ,string} 入参「字符串与整数都接受、浮点 / 布尔 / 超界一律拒」由
 *       {@code StringInt64.parse} 逐条承接；{@code offset} / {@code limit} 仍是原始 {@code int}
 *       （缺席 = 0，归一化在 message-rpc 侧）。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/ConversationMessagesReq.java:51-96}
 *       （必填断言在 {@code :69-74}；{@code ,string} setter 在 {@code :57-67}）</li>
 * </ol>
 *
 * @param conversationId 会话 id（必填；JSON 字符串与整数都接受）
 * @param offset         偏移量（可选，缺席 = 0）
 * @param limit          每页条数（可选，缺席 = 0；归一化在 message-rpc 侧）
 */
public record ConversationMessagesReq(
        @NotNull @JsonProperty("conversation_id") Long conversationId,
        @JsonProperty("offset") int offset,
        @JsonProperty("limit") int limit) {

    /**
     * 唯一的反序列化入口 —— 承接 B 侧的 {@code Object} 型 setter。
     *
     * <p><b>为什么入参声明成 {@code Object}</b>：本字段<b>同时接受 JSON 字符串和 JSON 数字</b>
     * ⇒ 只有「不声明具体目标类型、让 Jackson 原样递出」才能表达这两条都通；
     * 随后由 {@link StringInt64} 完全接管判定。
     *
     * <p><b>实测（jackson-databind 2.19.4 探针）</b>：静态 {@code @JsonCreator} 工厂与 record 的
     * 规范构造器<b>共存不冲突</b>；{@code Object} 入参绑定产出
     * {@code Integer / Long / BigInteger / String / Double / Boolean / null}
     * ⇒ {@code StringInt64.parse} 的判定与 B 侧 {@code GoStyleStringInt64.parse} 逐条同形
     * （B 侧「上一版用 String 入参 + Textual=Integer/Fail 规则，把合法的 JSON 数字一起拒了」
     * 的 DRIFT-MSGAPI-001 在 C 侧<b>不会复现</b>）。
     *
     * @param rawConversationId {@code conversation_id} 的原始 JSON 值
     * @param offset            {@code offset}（原样透传）
     * @param limit             {@code limit}（原样透传）
     * @return 解析后的 DTO
     */
    @JsonCreator
    public static ConversationMessagesReq of(
            @JsonProperty("conversation_id") Object rawConversationId,
            @JsonProperty("offset") int offset,
            @JsonProperty("limit") int limit) {
        return new ConversationMessagesReq(
                StringInt64.parse(rawConversationId, "conversation_id"),
                offset,
                limit);
    }
}
