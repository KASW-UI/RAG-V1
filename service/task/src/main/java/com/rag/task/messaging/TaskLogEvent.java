package com.rag.task.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p><b>原始日志事件</b> —— 对应 Go 端 {@code internal/Init/initGoKa.go:23-32} 的 {@code LogEvent}。</p>
 *
 * <h2>⚠️ 这是 <b>L1 事件 schema</b>：7 个字段全部保留，包括本模块<b>不消费</b>的那些</h2>
 * <p>Go 的 {@code process} 只用到 {@code UserID} 与 {@code ArticleID}（两个 emit 的 key），
 * 但它把整条 raw 消息<b>原样透传</b>到下游 topic，value 里带着全部字段。
 * 少声明字段不会丢数据（value 是 raw 字符串），但会让"这份 schema 长什么样"无处可查，
 * 而下游的消费者可能依赖这些字段。
 *
 * <h2>🔴 解析语义必须「逐字段独立」（这是本类存在的理由）</h2>
 * <p>Go {@code process}（{@code initGoKa.go:60-70}）：
 * <pre>
 * var logEvent LogEvent
 * if err := json.Unmarshal(raw, &amp;logEvent); err != nil {
 *     log.Println(err)          // ← 只记日志，**不 return**
 * }
 * ctx.Emit(goka.Stream(rawTopicTaskUser), logEvent.UserID, raw)
 * ctx.Emit(goka.Stream(rawTopicTaskArticle), logEvent.ArticleID, raw)
 * </pre>
 *
 * <p>⇒ <b>一个字段类型错不会阻断其余字段</b>：
 * {@code {"user_id":123,"article_id":"9"}} 在 Go 侧会因 user_id 报错，
 * 但 {@code article_id} 已经解出来 ⇒ 文章路仍以 key="9" 正常 emit。
 * 用 Jackson 直接反序列化成 record 会<b>整体失败</b>（或被静默强转），
 * 那就与 Go 相反了。判定逻辑见 {@link TaskGoCompatibleJson}。
 *
 * <h2>⚠️ 非法 JSON 时两个 key 都是空串</h2>
 * <p>Go 的 {@code LogEvent} 保持零值 ⇒ 两条 emit 都用空 key 发出去。
 * 下游：用户路的空 key 会让 sink 的 {@code ParseInt("")} 失败（⇒ 不确认位点），
 * 文章路的空 key 会让 sink 直接 return（⇒ 确认位点）。
 * Java 侧保持同样的行为，不要"优化"成提前 return。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code mq} → {@code messaging}（D7）；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 7 个字段的 JSON key、
 *       「逐字段独立」的判定、非法 JSON 时全零值的处理，逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mq/TaskLogEvent.java:58-66（record 声明 + 7 个字段）
 * B 出处：…/mq/TaskLogEvent.java:85-116（parseLenient：逐字段独立 + 全零值回落）
 *       </pre></li>
 * </ol>
 */
public record TaskLogEvent(
        String ts,
        String service,
        String eventId,
        String userId,
        String traceId,
        String articleId,
        String msg) {

    private static final Logger log = LoggerFactory.getLogger(TaskLogEvent.class);

    /**
     * 按 Go {@code encoding/json} 的语义做<b>尽力解析</b>。
     *
     * <p>与 Go 的差别只有一处，且不影响可观察行为：
     * Go 的一次 {@code Unmarshal} 只返回<b>第一个</b>错误，
     * 而本方法把每个类型不符的字段各记一条 warn。
     * 判定结果（哪些字段被填充）与 Go 完全一致。
     *
     * @param raw 消息原文；{@code null} 或非法 JSON 时返回全零值事件
     *             （对应 Go：结构体保持零值）
     */
    public static TaskLogEvent parseLenient(ObjectMapper mapper, String raw) {
        JsonNode node = TaskGoCompatibleJson.readTree(mapper, raw);
        if (node == null) {
            if (raw != null && !raw.isEmpty()) {
                log.warn("task raw log is not valid json, emit with empty keys: raw={}", abbreviate(raw));
            }
            return new TaskLogEvent("", "", "", "", "", "", "");
        }

        TaskGoCompatibleJson.Text ts = TaskGoCompatibleJson.text(node, "ts");
        TaskGoCompatibleJson.Text service = TaskGoCompatibleJson.text(node, "service");
        TaskGoCompatibleJson.Text eventId = TaskGoCompatibleJson.text(node, "event_id");
        TaskGoCompatibleJson.Text userId = TaskGoCompatibleJson.text(node, "user_id");
        TaskGoCompatibleJson.Text traceId = TaskGoCompatibleJson.text(node, "trace_id");
        TaskGoCompatibleJson.Text articleId = TaskGoCompatibleJson.text(node, "article_id");
        TaskGoCompatibleJson.Text msg = TaskGoCompatibleJson.text(node, "msg");

        // ⚠️ Go 只打一行 log；这里逐字段打，是为了让"哪个字段类型错了"可见。
        //    判定本身与 Go 一致：类型不符 ⇒ 该字段保持零值，其余字段照常。
        if (userId.typeError()) {
            log.warn("task raw log user_id is not a string, treated as empty (Go behaves the same)");
        }
        if (articleId.typeError()) {
            log.warn("task raw log article_id is not a string, treated as empty (Go behaves the same)");
        }
        if (ts.typeError() || service.typeError() || eventId.typeError()
                || traceId.typeError() || msg.typeError()) {
            log.warn("task raw log has non-string value in a string field, treated as empty");
        }

        return new TaskLogEvent(ts.value(), service.value(), eventId.value(),
                userId.value(), traceId.value(), articleId.value(), msg.value());
    }

    private static String abbreviate(String raw) {
        return raw.length() <= 256 ? raw : raw.substring(0, 256) + "…";
    }
}
