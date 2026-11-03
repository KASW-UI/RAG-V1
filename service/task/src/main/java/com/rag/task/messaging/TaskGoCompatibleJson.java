package com.rag.task.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <p><b>Go {@code encoding/json} 的类型判定还原工具</b>。</p>
 *
 * <h2>为什么不能直接让 Jackson 反序列化成 record</h2>
 * <p>Go 侧三处 {@code json.Unmarshal}（{@code initGoKa.go:64,87}、{@code article_sink.go:131}）
 * 都把目标当成<b>强类型结构体</b>，于是对每条消息都有 Go 侧的判定：
 * <table border="1">
 *   <caption>Go encoding/json 对类型不符的反应</caption>
 *   <tr><th>消息里的值</th><th>目标字段类型</th><th>Go</th></tr>
 *   <tr><td>数字</td><td>string</td><td><b>报错</b>（该字段保持零值 ""）</td></tr>
 *   <tr><td>字符串</td><td>int64</td><td><b>报错</b>（该字段保持零值 0）</td></tr>
 *   <tr><td>小数</td><td>int64</td><td><b>报错</b></td></tr>
 *   <tr><td>数字</td><td>bool</td><td><b>报错</b></td></tr>
 *   <tr><td>布尔</td><td>string</td><td><b>报错</b></td></tr>
 *   <tr><td>未知字段</td><td>—</td><td><b>忽略</b></td></tr>
 *   <tr><td>{@code null}</td><td>任意</td><td><b>当零值处理，不报错</b></td></tr>
 *   <tr><td>缺字段</td><td>任意</td><td><b>零值，不报错</b></td></tr>
 * </table>
 *
 * <p>Jackson 2 默认会做<b>静默标量强转</b>（{@code {"cur":"5"}} → {@code 5}、
 * {@code {"user_id":123}} → {@code "123"}），
 * 于是同一条消息在两侧得到<b>相反</b>的判定：
 * Go 侧丢弃、Java 侧当成正常数据处理。
 *
 * <h2>本类的做法</h2>
 * <p>先 {@code readTree} 得到 schema-free 的 {@link JsonNode}（<b>永不因类型不符而抛</b>），
 * 再逐字段按上表判定。这样才能还原 Go 的两处关键行为：
 * <ul>
 *   <li><b>逐字段独立</b>：Go 一次 {@code Unmarshal} 里某个字段报错，
 *       <b>其它已解码成功的字段依然保留</b>
 *       （{@code initGoKa.go:64-66} 打印错误后继续用 {@code logEvent.UserID}，
 *       而 {@code logEvent} 已被部分填充）。这正是 {@link TaskLogEvent} 用它的原因。</li>
 *   <li><b>null / 缺字段 = 零值且不算错误</b>。</li>
 * </ul>
 *
 * <p>⚠️ 本类只负责<b>类型判定</b>；字段缺失的业务含义由调用方决定。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>类名</b>：{@code mq/GoJson} → {@code messaging/TaskGoCompatibleJson}
 *             （D7 包压平 + 域前缀，映射表 §1.2 已登记「去掉原前缀」）；</li>
 *         <li><b>可见性</b>：B 侧是包级私有（{@code final class}），本类是
 *             {@code public} —— <b>只放宽了可见性，行为零变化</b>；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 两条判定的规则表、null/缺字段的零值语义、
 *       {@code readTree} 失败返回 {@code null} 的处理，逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mq/GoJson.java:26-28（类声明）
 * B 出处：…/mq/GoJson.java:48-50（Text 记录）
 * B 出处：…/mq/GoJson.java:60-62（Number 记录）
 * B 出处：…/mq/GoJson.java:71-80（text 判定规则）
 * B 出处：…/mq/GoJson.java:91-103（number 判定规则：小数判为错误）
 * B 出处：…/mq/GoJson.java:108-110（nodeOf）
 * B 出处：…/mq/GoJson.java:119-127（readTree：非法 JSON ⇒ null）
 *       </pre></li>
 * </ol>
 */
public final class TaskGoCompatibleJson {

    private TaskGoCompatibleJson() {
        // 纯静态工具，禁止实例化
    }

    /**
     * 文本字段的判定结果。
     *
     * @param value     文本值；字段缺失 / null / 类型不符时都是零值 {@code ""}
     * @param typeError 目标字段是 string 而消息里给了非字符串时为 {@code true}
     *                  （对应 Go {@code json.Unmarshal} 返回 error）
     */
    public record Text(String value, boolean typeError) {
    }

    /**
     * 整数字段的判定结果。
     *
     * @param value     整数值；字段缺失 / null / 类型不符时都是 {@code 0}
     * @param typeError 目标字段是 int64 而消息里给了非整型数时为 {@code true}
     */
    public record Number(long value, boolean typeError) {
    }

    /**
     * 取文本字段，按 Go {@code encoding/json} 的规则判定。
     *
     * <p>规则：缺失 / null ⇒ {@code ("", false)}；textual ⇒ 值；
     * 其余（数字 / 布尔 / 数组 / 对象）⇒ {@code ("", true)}。
     */
    public static Text text(JsonNode parent, String field) {
        JsonNode node = nodeOf(parent, field);
        if (node == null || node.isNull()) {
            return new Text("", false);
        }
        if (node.isTextual()) {
            return new Text(node.textValue(), false);
        }
        return new Text("", true);
    }

    /**
     * 取整数字段，按 Go {@code encoding/json} 的规则判定。
     *
     * <p>规则：缺失 / null ⇒ {@code (0, false)}；<b>整型</b> ⇒ 值；
     * 其余（含<b>小数</b>、字符串、布尔、数组、对象）⇒ {@code (0, true)}。
     *
     * <p>⚠️ 小数必须判为错误：Go 把 {@code 3.7} 写进 {@code int64} 字段会报错，
     * 而 Jackson 默认会截断成 3。
     */
    public static Number number(JsonNode parent, String field) {
        JsonNode node = nodeOf(parent, field);
        if (node == null || node.isNull()) {
            return new Number(0L, false);
        }
        if (node.isIntegralNumber()) {
            return new Number(node.asLong(), false);
        }
        return new Number(0L, true);
    }

    /** 字段缺失时返回 null；存在则返回节点。 */
    private static JsonNode nodeOf(JsonNode parent, String field) {
        return parent == null ? null : parent.get(field);
    }

    /**
     * 解析成 {@link JsonNode}。
     *
     * @return 解析结果；JSON 非法时返回 {@code null}
     *         （对应 Go {@code json.Unmarshal} 返回 error）
     */
    public static JsonNode readTree(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
