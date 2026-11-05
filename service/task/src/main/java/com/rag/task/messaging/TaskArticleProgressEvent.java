package com.rag.task.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <p><b>文章进度事件</b> —— <b>两个方向共用同一份契约</b>，对应 Go 侧两处<b>字段完全相同</b>的类型：</p>
 * <ul>
 *   <li>写：{@code internal/Init/initGoKa.go:34-37} 的 {@code TaskArticleEvent}
 *       （goka 计数后 emit 到 {@code task.logs.filtered.Article}）</li>
 *   <li>读：{@code internal/sink/article_sink.go:71-74} 的 {@code articleVal}</li>
 *   <li>还有<b>跨仓库的生产者</b>：Go 与 Java 的 like-rpc 用
 *       {@code types.TaskArticleProgressMsg}（{@code user_id} / {@code cur}，
 *       与本类型逐字段相同）往同一 topic 推。</li>
 * </ul>
 *
 * <h2>⚠️ JSON 形状是 L1 契约</h2>
 * <p>Go 的两个结构体都是：
 * <pre>
 * UserID string `json:"user_id"`
 * Cur    int64  `json:"cur"`
 * </pre>
 * ⇒ 序列化结果形如 {@code {"user_id":"1902","cur":3}}：
 * <ul>
 *   <li>字段名就是 tag，<b>不可</b>改成 camelCase；</li>
 *   <li>{@code user_id} 是<b>字符串</b>（存的是 int64 的字符串形式），不是数字；</li>
 *   <li>{@code cur} 是<b>数字</b>；</li>
 *   <li>字段顺序 = 结构体声明顺序（Go 的 encoding/json 按声明顺序输出），
 *       Java 侧保持 record 声明顺序与之相同。</li>
 * </ul>
 *
 * <h2>⚠️ 与 like-rpc 的 Java 实现共用一个类会引入隐式耦合</h2>
 * <p>Java 的 like-rpc 有自己的 {@code LikeEventContracts.TaskArticleProgressMsg}，
 * 形状相同但<b>分属不同模块</b>。这里不共用：task 域不引 like 域的 artifact，
 * 共用会让 task 域对 like 域产生编译期依赖，而 Go 侧两域只共享 topic 名。</p>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.task.task.rpc.mq} → {@code com.rag.task.messaging}
 *             （D7 包压平）；</li>
 *         <li><b>类型判定工具改名</b>：B 侧的 {@code GoJson} 在 C 侧叫
 *             {@link TaskGoCompatibleJson}（映射表 §1.2 已登记「去掉原前缀」）
 *             ⇒ <b>只改名字，不改任何判定规则</b>；</li>
 *         <li>补 B 出处行号。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— {@code @JsonProperty("user_id")}、
 *       {@code parse} 的三段判据（类型错 ⇒ 丢弃 / {@code cur==0 ⇒ 补 1} /
 *       {@code user_id} 解析失败 ⇒ 静默变 0 ⇒ {@code ok=false}）、{@code toJson} 的字段顺序，
 *       全部照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/mq/TaskArticleProgressEvent.java:50-52（record 声明 + @JsonProperty）
 * B 出处：…/mq/TaskArticleProgressEvent.java:61-62（Parsed 记录）
 * B 出处：…/mq/TaskArticleProgressEvent.java:88-113（parse = Go article_sink.go:126-139 的 parseVal）
 * B 出处：…/mq/TaskArticleProgressEvent.java:123-131（toJson）
 * B 出处：…/mq/TaskArticleProgressEvent.java:134-140（parseLongOrZero = Go 的 ParseInt 且丢弃 error）
 *       </pre></li>
 * </ol>
 *
 * @param userId JSON 字段名是 <b>{@code user_id}</b>（下划线），<b>不是</b> {@code userId}。
 *                ⚠️ 这一行 {@code @JsonProperty} 是<b>必须有</b>的：Java record 的组件名默认
 *                直接当 JSON 字段名，不加注解会输出 {@code {"userId":"…","cur":N}}。
 *                <p>后果不是"不好看"而是<b>整条文章路断链</b>：
 *                本模块自己的 {@link TaskArticleLikeProgressSink} 按 {@code user_id}
 *                取值 ⇒ 取不到 ⇒ {@code ok=false} ⇒ 消息被丢弃；
 *                跨仓库的 Java like-rpc（{@code LikeEventContracts}）同样按 {@code user_id} 反序列化。
 *                <p>⚠️ 这个缺陷<b>被测试抓到过</b>（B 侧 {@code TaskEventParsingTest#serializationShapeMatchesGo}）：
 *                编译通过、所有消费侧测试都绿，只有真正断言线上的字节形状时才暴露。
 */
public record TaskArticleProgressEvent(
        @JsonProperty("user_id") String userId,
        @JsonProperty("cur") long cur) {

    /**
     * 解析结果 —— 对应 Go {@code parseVal} 的三返回值。
     *
     * @param userId 作者 ID（Go：{@code strconv.ParseInt(v.UserID, 10, 64)}，<b>错误被丢弃</b> ⇒ 非法值变 0）
     * @param cur    获赞数（Go 有 {@code if v.Cur == 0 { v.Cur = 1 }} 的补 1 逻辑）
     * @param ok     Go 返回的第三个值 {@code UserID != 0}；false ⇒ 调用方丢弃该消息
     */
    public record Parsed(long userId, long cur, boolean ok) {
    }

    /**
     * 对应 Go {@code article_sink.go:126-139} 的 {@code parseVal}。
     *
     * <p>Go 原文：
     * <pre>
     * if val == "" { return 0, 0, false }
     * var v articleVal
     * if err := json.Unmarshal([]byte(val), &amp;v); err == nil {
     *     if v.Cur == 0 { v.Cur = 1 }
     *     UserID, _ := strconv.ParseInt(v.UserID, 10, 64)
     *     return UserID, v.Cur, UserID != 0
     * }
     * return 0, 0, false
     * </pre>
     *
     * <p>⚠️ 三个容易写错的点：
     * <ol>
     *   <li><b>解析失败 = 直接丢弃</b>，不是"用零值继续"；</li>
     *   <li>{@code cur} 为 0（含缺字段 / null）时<b>补成 1</b>；</li>
     *   <li>{@code user_id} 的 {@code ParseInt} 错误被<b>静默丢弃</b>，
     *       解析不出数字就变 0，而 0 会让 ok=false ⇒ 整条丢弃。</li>
     * </ol>
     * 类型判定（{@code cur} 必须是整型数、{@code user_id} 必须是字符串）见
     * {@link TaskGoCompatibleJson}。
     */
    public static Parsed parse(ObjectMapper mapper, String val) {
        if (val == null || val.isEmpty()) {
            return new Parsed(0L, 0L, false);
        }

        JsonNode node = TaskGoCompatibleJson.readTree(mapper, val);

        if (node == null) {
            // Go: json.Unmarshal 失败 ⇒ return 0, 0, false（丢弃）
            return new Parsed(0L, 0L, false);
        }

        // ⚠️ 顺序要紧：先判类型错误（等价于 Unmarshal 返回 error），再取值
        TaskGoCompatibleJson.Number curField = TaskGoCompatibleJson.number(node, "cur");
        TaskGoCompatibleJson.Text userIdField = TaskGoCompatibleJson.text(node, "user_id");
        if (curField.typeError() || userIdField.typeError()) {
            return new Parsed(0L, 0L, false);
        }

        long cur = curField.value();
        if (cur == 0L) {
            cur = 1L;               // Go: if v.Cur == 0 { v.Cur = 1 }
        }
        long userId = parseLongOrZero(userIdField.value());
        return new Parsed(userId, cur, userId != 0L);
    }

    /**
     * 序列化为 Go {@code json.Marshal} 的等价结果 —— 供 goka 计数组 emit 用。
     *
     * <p>⚠️ Go 的 {@code json.Marshal} <b>开启</b> HTML 转义（{@code <} → {@code \u003c}）。
     * 本类的两个字段是 {@code user_id}（字符串）与 {@code cur}（数字），
     * 实践中不含可转义字符，故<b>不</b>额外处理 —— 若将来加入任意文本字段，
     * 必须重新取证（见 {@code com.rag.like.like.rpc.service.GoCompatibleJson} 同款问题）。
     */
    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            // ⚠️ Go 侧 json.Marshal 失败会 log 并把 nil 传给 ctx.Emit（initGoKa.go:104-105）。
            //    这里抛异常让调用方走与 Go 等价的"这条消息处理失败"分支。
            throw new IllegalStateException("marshal TaskArticleProgressEvent failed", e);
        }
    }

    /** Go {@code strconv.ParseInt(s, 10, 64)} 且丢弃 error（{@code UserID, _ := ...}）。 */
    private static long parseLongOrZero(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
