package com.rag.comment.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.contract.CommentKafkaMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>评论审计消息的 JSON 契约</b> —— 对应 Go
 * {@code service/comment/rpc/common/types/kqueue.go:3-15} 的 {@code CommentKafkaMsg}。
 *
 * <p><b>为什么必须逐字节断言</b>：这条消息走 Kafka，是 comment 域自己的生产端与消费端之间的契约。
 * 字段顺序、空串是否输出、{@code create_time} 是秒还是毫秒 —— 任何一条变了，
 * 灰度期就会出现「Java 写的消息 Go 读不出来」或反之，而<b>JSON 语义仍然合法</b>，
 * 靠「能反序列化」这种断言<b>永远发现不了</b>。
 *
 * <h2>🔴 C 侧为什么有<b>两份</b> 11 字段定义</h2>
 * <p>B 侧只有<b>一个</b>类（{@code CommentAuditMessage}），生产端与消费端共用。
 * C 侧按 D7 把<b>跨服务契约</b>提到 {@code contract/}（D11 的包压平产物）⇒ 落成两处：
 * <ul>
 *   <li>{@link CommentKafkaMsg}（{@code contract/}）= <b>生产侧 wire 契约</b>（序列化方向）；</li>
 *   <li>{@link CommentAuditMessage}（本包）= <b>消费侧 DTO</b>（反序列化方向）。</li>
 * </ul>
 * ⇒ 代价是两份 11 字段定义 ⇒ <b>本类同时断言两者的序列化结果逐字节相同</b>
 * （{@link #productionWireAndConsumerDtoSerializeIdentically}）——
 * 这是「拆成两份」这个 L4 改动<b>唯一</b>的护栏。
 *
 * <h3>L4 差异（AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.comment.comment.rpc.service} 压平为
 *           {@code com.rag.comment.messaging} + {@code com.rag.comment.contract}（D7 · D11）；</li>
 *       <li>B 侧的<b>可变 POJO</b>（无参构造 + setter）拆成两份<b>不可变 record</b>
 *           ⇒ 构造方式是 {@code new CommentAuditMessage(...)} 而非 {@code setXxx}；</li>
 *       <li>本类新增了「两份契约序列化结果必须相同」这条断言（B 侧只有一份，天然相等）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。两份 record 的 {@code @JsonProperty} 名与
 *       {@code @JsonPropertyOrder} 顺序<b>逐条相同</b> ⇒ wire 字节与 B 侧一致
 *       （见 {@link #jsonFieldOrderMatchesGoStructOrder} 的逐字节断言）。
 *       ⚠️ 「容忍未知字段」这一条也逐条一致：B 侧靠类级
 *       {@code @JsonIgnoreProperties(ignoreUnknown = true)} 实现，
 *       C 侧两份 record <b>各自</b>挂了同一个注解
 *       （见 {@link #unknownFieldsAreIgnoredEvenWithStrictMapper}）。</li>
 *   <li><b>B 出处</b>：
 *     <ul>
 *       <li>断言来源：B 侧
 *           {@code comment-rpc/src/test/java/com/rag/comment/comment/rpc/service/CommentAuditMessageContractTest.java:37-80}；</li>
 *       <li>被测实现：B 侧
 *           {@code comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentAuditMessage.java:47-86}；</li>
 *       <li>Go 语义来源：B 侧自记其 Go 出处为
 *           {@code service/comment/rpc/common/types/kqueue.go:3-15}。</li>
 *     </ul></li>
 * </ol>
 */
class CommentAuditMessageContractTest {

    /** 裸 mapper —— 用于<b>逐字节</b>序列化断言（与 B 侧原测试用的是同一个）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** B 侧期望的字节序列（逐字抄自 B 侧 {@code CommentAuditMessageContractTest.java:41-43}）。 */
    private static final String EXPECTED_JSON =
            "{\"comment_id\":123,\"target_type\":\"article\",\"target_id\":\"a1\","
                    + "\"user_id\":7,\"root_id\":0,\"parent_id\":0,\"content\":\"hi\",\"meta\":\"\","
                    + "\"owner_id\":5,\"attribute\":0,\"create_time\":1700000000}";

    /** 消费侧 DTO 的样例（11 个字段与 B 侧 {@code sample()} 逐条相同）。 */
    private static CommentAuditMessage sample() {
        return new CommentAuditMessage(
                123L, "article", "a1", 7L, 0L, 0L, "hi", "", 5L, 0L, 1700000000L);
    }

    /** 生产侧 wire 契约的样例（11 个字段与消费侧逐条相同）。 */
    private static CommentKafkaMsg sampleWire() {
        return new CommentKafkaMsg(
                123L, "article", "a1", 7L, 0L, 0L, "hi", "", 5L, 0L, 1700000000L);
    }

    @Test
    @DisplayName("🔴 字段顺序与 Go 的 encoding/json 声明顺序逐条一致")
    void jsonFieldOrderMatchesGoStructOrder() throws Exception {
        // Go: json.Marshal(CommentKafkaMsg) 按结构体声明顺序输出
        assertThat(MAPPER.writeValueAsString(sample())).isEqualTo(EXPECTED_JSON);
    }

    @Test
    @DisplayName("🔴 11 个字段无条件输出：没有 omitempty，空串也要出现")
    void noFieldIsOmitted() throws Exception {
        String json = MAPPER.writeValueAsString(sample());
        // 逐个点名，确认 meta/content 为空串时字段仍在（Go 侧没有 omitempty）
        assertThat(json).contains("\"meta\":\"\"");
        assertThat(json).contains("\"content\":\"hi\"");
        assertThat(json).contains("\"attribute\":0");
        assertThat(json).contains("\"root_id\":0");
        assertThat(json).contains("\"parent_id\":0");
        // 共 11 个键
        assertThat(MAPPER.readTree(json).size()).isEqualTo(11);
    }

    @Test
    @DisplayName("🔴 create_time 是 Unix 秒，不是毫秒（Go now.Unix()）")
    void createTimeIsInSeconds() {
        // 1700000000 秒 = 2023-11-14T22:13:20Z；若被当毫秒解释就是 1970-01-20
        long epochSecond = sample().createTime();
        assertThat(Instant.ofEpochSecond(epochSecond).toEpochMilli())
                .isNotEqualTo(epochSecond);
        assertThat(Instant.ofEpochSecond(epochSecond))
                .isEqualTo(Instant.parse("2023-11-14T22:13:20Z"));
    }

    @Test
    @DisplayName("🔴 反序列化容忍未知字段（Go 的 encoding/json 默认忽略）")
    void unknownFieldsAreIgnored() throws Exception {
        String json = MAPPER.writeValueAsString(sample())
                + ",\"some_future_field\":\"x\"";
        // ⚠️ 用**裸 mapper**（与 B 侧原测试同一个）⇒ 断言的是「类上的
        //    @JsonIgnoreProperties(ignoreUnknown = true) 让行为与 mapper 配置无关」
        //    这一 B 侧保证本身，而不是某个 mapper 的默认值。
        CommentAuditMessage parsed = MAPPER.readValue(json, CommentAuditMessage.class);
        assertThat(parsed.commentId()).isEqualTo(123L);
        assertThat(parsed.ownerId()).isEqualTo(5L);
    }

    /**
     * 🔴 <b>生产侧 wire 契约与消费侧 DTO 的序列化结果必须<b>逐字节相同</b></b>。
     *
     * <p>这是 C 侧把「一个类」拆成「两个 record」这个 L4 改动<b>唯一</b>的护栏：
     * 两份定义若有任何一处字段名 / 顺序 / 数量不同，wire 就断了，
     * 而<b>JSON 语义仍然合法</b> ⇒ 靠「能反序列化」的断言永远发现不了。
     *
     * <p>B 出处：B 侧只有一份类（{@code CommentAuditMessage.java:47-86}）⇒ 天然相等；
     * C 侧两份的字段清单逐条抄自它。
     */
    @Test
    @DisplayName("🔴 生产侧 wire 契约与消费侧 DTO 序列化必须逐字节相同")
    void productionWireAndConsumerDtoSerializeIdentically() throws Exception {
        String wireJson = MAPPER.writeValueAsString(sampleWire());
        String dtoJson = MAPPER.writeValueAsString(sample());
        assertThat(wireJson)
                .as("两份契约的字节必须完全一致（否则 wire 断了而 JSON 仍合法 ⇒ 静默故障）")
                .isEqualTo(dtoJson);
        assertThat(wireJson)
                .as("且必须等于 B 侧的期望字节序列")
                .isEqualTo(EXPECTED_JSON);
    }

    /**
     * 🔴 <b>{@code CommentAuditMessage.from(wire)} 必须逐字段无损转录</b>。
     *
     * <p>B 出处：B 侧生产端与消费端<b>共用同一个类</b> ⇒ 不存在转录点
     * ⇒ C 侧这个 {@code from} 工厂方法是<b>新引入</b>的转录点，
     * 字段漏抄 / 顺序错位都会在这里发生 ⇒ 必须逐字段钉。
     */
    @Test
    @DisplayName("🔴 from(wire) 必须逐字段无损转录 11 个字段")
    void fromWirePreservesAllElevenFields() {
        CommentAuditMessage dto = CommentAuditMessage.from(sampleWire());

        assertThat(dto.commentId()).isEqualTo(123L);
        assertThat(dto.targetType()).isEqualTo("article");
        assertThat(dto.targetId()).isEqualTo("a1");
        assertThat(dto.userId()).isEqualTo(7L);
        assertThat(dto.rootId()).isEqualTo(0L);
        assertThat(dto.parentId()).isEqualTo(0L);
        assertThat(dto.content()).isEqualTo("hi");
        assertThat(dto.meta()).isEqualTo("");
        assertThat(dto.ownerId()).isEqualTo(5L);
        assertThat(dto.attribute()).isEqualTo(0L);
        assertThat(dto.createTime()).isEqualTo(1700000000L);

        // 反向转录（toWire）也必须等价 ⇒ 往返无损
        assertThat(dto.toWire()).isEqualTo(sampleWire());
    }

    /**
     * 🔴 <b>类级 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 让「容忍未知字段」与 mapper 配置无关</b>。
     *
     * <p><b>B 出处</b>：B 侧 {@code comment-rpc/…/rpc/service/CommentAuditMessage.java:51}
     * 挂着 {@code @JsonIgnoreProperties(ignoreUnknown = true)}
     * ⇒ B 侧<b>无论</b>用哪种 mapper，未知字段都被忽略。
     *
     * <p><b>为什么这条必须单独钉</b>：C 侧把「一个类」拆成两份 record
     * （{@code messaging/} 消费侧 + {@code contract/} 生产侧）
     * ⇒ <b>两份都得各自挂这个注解</b>，否则「消费侧容忍、生产侧不容忍」的裂缝
     * 会<b>静默</b>存在（生产侧只序列化不反序列化 ⇒ 只靠消费侧的测试永远照不出来）。
     *
     * <p>⚠️ 本条用<b>显式打开</b> {@code FAIL_ON_UNKNOWN_PROPERTIES} 的 mapper 来钉：
     * 它是 Jackson 的<b>默认</b>值 ⇒ 若类级注解失效（被删/被挪/被覆盖），
     * 本条会立刻红；而 B 侧的保证恰恰是「无论 mapper 怎么配都容忍」。
     *
     * <p>⇒ 本条同时是 <b>C 侧两份 record 的对称性护栏</b>：
     * 生产侧 {@code contract/CommentKafkaMsg} 也必须挂同一个注解。
     */
    @Test
    @DisplayName("🔴 未知字段必须被容忍，且与 mapper 的 FAIL_ON_UNKNOWN_PROPERTIES 配置无关")
    void unknownFieldsAreIgnoredEvenWithStrictMapper() throws Exception {
        String json = EXPECTED_JSON.substring(0, EXPECTED_JSON.length() - 1)
                + ",\"some_future_field\":\"x\"}";
        ObjectMapper strict = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        CommentAuditMessage parsed = strict.readValue(json, CommentAuditMessage.class);
        assertThat(parsed.commentId()).isEqualTo(123L);
        assertThat(parsed.ownerId()).isEqualTo(5L);

        CommentKafkaMsg wire = strict.readValue(json, CommentKafkaMsg.class);
        assertThat(wire.commentId()).isEqualTo(123L);
        assertThat(wire.ownerId()).isEqualTo(5L);
    }
}
