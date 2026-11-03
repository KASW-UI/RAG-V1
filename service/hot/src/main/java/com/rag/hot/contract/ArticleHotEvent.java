package com.rag.hot.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>Kafka 消息体</b>（{@code article-hot-events} topic 的 value）—— <b>只有 2 个字段</b>。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/mq/ArticleHotEvent.java}
 * <pre>{@code
 *   @JsonIgnoreProperties(ignoreUnknown = true)      :22
 *   record ArticleHotEvent(articleId, type)           :23-25
 *     @JsonProperty("article_id") String articleId    :24
 *     @JsonProperty("type")      String type         :25
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯命名 / 定位</b>改动 ——
 *       ① 包名 {@code com.rag.hot.hot.rpc.mq} → {@code com.rag.hot.contract}
 *       （D7 包压平 + 映射表 hot.md §1.5「载荷落域内 {@code contract/} 包」）；
 *       ② <b>注解写法的等价替换</b>：B 侧在 record 组件上用的是
 *       {@code @com.fasterxml.jackson.annotation.JsonProperty(...)} 全限定名，
 *       C 侧改为 {@code import} 后写短名 ⇒ <b>字节码完全相同</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— record 的两个组件名 / 类型 / JSON 键名、
 *       {@code @JsonIgnoreProperties(ignoreUnknown = true)} 逐条未动。</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>⚠️ {@code ignoreUnknown = true} 是硬要求，不是风格问题</b>：
 * B 侧的压测生产端实测发送的是 <b>4 个字段</b>（多 {@code user_id} / {@code timestamp}）
 * ⇒ 若 Java 侧严格拒绝未知字段，现有生产端发的事件会被<b>全部 drop</b>，热榜永远不更新。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：它是 DTO 层的 immutable record（D5 + A9）⇒
 * Lombok 被明令禁止用于 DTO 层 ⇒ 保持手写 record。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleHotEvent(
        @JsonProperty("article_id") String articleId,
        @JsonProperty("type") String type) {
}
