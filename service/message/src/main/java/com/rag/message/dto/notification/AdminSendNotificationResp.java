package com.rag.message.dto.notification;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>职责</b>：管理员发通知响应 —— 对应 Go {@code types.go} 的 {@code AdminSendNotificationResp}。
 *
 * <p><b>⚠️ 本类的 JSON 形态是【签字偏离】，不是「与 Go 一致」</b>
 * （B 侧 2026-10-23 更正，{@code SD-2026-10-23-03}）。
 *
 * <p>B 侧真源 {@code service/message/api/internal/types/types.go:21-23}：
 * <pre>
 *   type AdminSendNotificationResp struct {
 *       Affected int64 `json:"affected"`      // ← **没有** ,string
 *   }
 * </pre>
 * ⇒ Go 输出 JSON <b>数字</b>：{@code {"affected":3}}；
 * B 侧 Java DTO 加了 {@link JsonFormat.Shape#STRING} ⇒ 输出<b>字符串</b>：{@code {"affected":"3"}}。
 *
 * <p><b>为什么保留</b>：用户 2026-10-23 逐条拍板「保留 RAG 的字符串形态，不改 wire 契约」。
 * 日后要改回数字<b>须重新签字</b>，不能以「proto 里写着 int64」为由单方面改回 ——
 * 那会把已签字的偏离变成未受控的漂移。
 *
 * <p><b>🔴 B 侧注释曾写着一句错误的「Go: …」</b>：原文写的是
 * {@code Affected int64 `json:"affected,string"`}，比真源多一个 {@code ,string} ——
 * 而<b>那句错的描述正是当年加 {@code @JsonFormat(STRING)} 的理由</b>
 * （AGENTS.md §3.3.2 的典型样本：以断言口气写的注释比没有注释更危险，
 * 因为 review 时会拿它当依据）。⇒ B 侧已改为「先陈述真源事实，再陈述签字偏离」两段式。
 *
 * <p><b>语义</b>：{@code affected} = 实际写入的通知条数。
 * 广播时等于目标用户总数（message-rpc 按
 * {@code message.broadcast.page-size=200} 分批插库），定向时为 0 或 1
 * （被 message-rpc 的存在性 / 状态校验拒掉时是 0，B 侧同样返回 0 而非错误）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       包名 {@code api.dto} → {@code dto.notification}（D7 + D11）。
 *       字段集合、类型、注解逐条照抄 B 侧 —— <b>包括</b> {@code @JsonFormat(shape = STRING)}。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 输出仍是 JSON <b>字符串</b>
 *       （proto 侧 {@code int64 affected = 3;} 无 {@code ,string}，但 HTTP 出参形态以 B 侧 Java DTO
 *       为准 ⇒ 已签字偏离照抄，<b>不要「修正」</b>）。</li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/AdminSendNotificationResp.java:41-53}
 *       （{@code @JsonFormat} 在 {@code :43}）</li>
 * </ol>
 *
 * @param affected 实际写入的通知条数（<b>序列化为字符串</b> · 已签字偏离 {@code SD-2026-10-23-03}）
 */
public record AdminSendNotificationResp(
        @JsonProperty("affected") @JsonFormat(shape = JsonFormat.Shape.STRING) long affected) {
}
