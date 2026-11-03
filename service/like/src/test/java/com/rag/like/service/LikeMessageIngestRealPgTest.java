package com.rag.like.service;

import com.rag.like.LikeServiceApplication;
import com.rag.like.support.NacosStubConfiguration;
import com.rag.like.support.TestPostgres;
import com.rag.like.support.TestRedis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>like 域「MQ 消费落库」链路的事务与幂等断言</b>（真 PostgreSQL 16 · 真 MyBatis-Plus）。
 *
 * <h2>🔴 为什么这一层是本域<b>最不能省</b>的一段</h2>
 * <p>{@code LikeMessageIngestService#processBatch} 承载的是 <b>L3 语义</b>（AGENTS.md 决策记录）：
 * <blockquote>
 * <b>inbox + record + fact 三处写入必须落在<b>同一个事务</b>里</b>
 * ⇒ 任一失败 ⇒ 整批回滚 ⇒ 消息位点不提交 ⇒ 稍后重投。
 * </blockquote>
 * ⇒ ⇒ 这条语义<b>无法</b>由「单元测试 + mock mapper」证明（mock 的 mapper 不参与真实事务）
 * ⇒ ⇒ 必须打<b>真 PG</b>、让<b>真的</b>{@code @Transactional} 生效。
 *
 * <h2>本类钉住的 5 条不变量</h2>
 * <table border="1">
 *   <caption>不变量</caption>
 *   <tr><th>#</th><th>不变量</th><th>B 出处</th></tr>
 *   <tr><td>①</td><td>首次点赞 ⇒ inbox(status=1) + record(state=1) + fact + outbox 四处齐全</td>
 *       <td>Go {@code applyLikeMessage}</td></tr>
 *   <tr><td>②</td><td>重复投递同一 {@code msg_id} ⇒ <b>幂等跳过</b>（不重复落库）</td>
 *       <td>Go {@code ON CONFLICT DO NOTHING} + {@code if prior.Status == 1 { continue }}</td></tr>
 *   <tr><td>③</td><td>同一 {@code msg_id} 配<b>不同</b>载荷 ⇒ <b>报错</b>（不是静默覆盖）</td>
 *       <td>Go {@code reused with conflicting payload}</td></tr>
 *   <tr><td>④</td><td>乱序消息（{@code operation_id} 不大于已记录值）⇒ 标记完成但<b>不回放</b>状态</td>
 *       <td>Go {@code if operationID <= current.LastOperationID}</td></tr>
 *   <tr><td>⑤</td><td>批内任一条失败 ⇒ <b>整批回滚</b>（前面已成功的也回滚）</td>
 *       <td>Go 的单事务 {@code ProcessLikeMessageBatch}</td></tr>
 * </table>
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧的事务与幂等行为与 B 侧源码记录的期望一致」。
 * 「B 侧此刻真的同样回滚」属于<b>双侧活体对拍</b>（本类不冒充该证据）。
 */
@SpringBootTest(classes = {LikeServiceApplication.class, NacosStubConfiguration.class})
@ActiveProfiles("test")
class LikeMessageIngestRealPgTest {

    /** uid / operation_id 序号器（与其它测试类共用同一个库 ⇒ 必须全局唯一）。 */
    private static final AtomicLong SEQ = new AtomicLong(8_300_000_000_000L);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
    }

    @Autowired
    private LikeMessageIngestService ingestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================================================================
    // 夹具
    // ==================================================================

    private static LikeMessageIngestService.LikeProcessPayload payload(
            String msgId, long userId, String targetType, String targetId, int action, boolean isFirst) {
        LikeMessageIngestService.LikeProcessPayload p = new LikeMessageIngestService.LikeProcessPayload();
        p.msgId = msgId;
        p.userId = userId;
        p.targetType = targetType;
        p.targetId = targetId;
        p.authorId = userId + 1;
        p.action = action;
        p.occurredAt = 1_762_000_000L;
        p.isFirst = isFirst;
        return p;
    }

    private Integer countRows(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    /**
     * 事实流的 aggregate_id —— 逐字对齐 B 侧 {@code domain_fact.go} 的
     * {@code streamId = "like-state/" + userId + "/" + targetType + "/" + targetId}。
     *
     * <p>⚠️ <b>注意它与「行内 aggregate_id」和「payload.aggregate_id」是两回事</b>：
     * 行内 = 状态流 ID（本方法）；payload 内的 = {@code targetType + "/" + targetId}（目标 ID）
     * ⇒ 两者<b>刻意不同</b>（防伪造）⇒ 测试里别把两者混用。
     */
    private static String streamId(long uid, String targetType, String targetId) {
        return "like-state/" + uid + "/" + targetType + "/" + targetId;
    }

    // ==================================================================
    // ① 首次点赞：inbox + record + fact + outbox 四处齐全
    // ==================================================================

    @Test
    @DisplayName("🔴 首次点赞 ⇒ inbox(status=1) + record(state=1) + fact + outbox 四处齐全")
    void firstLikeWritesAllFourPlaces() {
        long uid = SEQ.incrementAndGet();
        String targetType = "article-first-" + uid;
        String targetId = "t-" + uid;
        String msgId = String.valueOf(SEQ.incrementAndGet());

        LikeMessageIngestService.LikeProcessPayload p = payload(msgId, uid, targetType, targetId, 1, true);
        p.firstLikeOutbox = ingestService.buildFirstLikeOutbox(p);
        assertThat(p.firstLikeOutbox)
                .as("action==1 且 isFirst ⇒ 必须造出热点事件草稿")
                .isNotNull();

        ingestService.processBatch(List.of(p));

        // inbox：status 必须从 0 变成 1（已完整处理）
        Integer inboxStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM like_consume_inbox WHERE msg_id = ?", Integer.class, msgId);
        assertThat(inboxStatus).as("inbox 必须被标记为已完成").isEqualTo(1);

        // record：state=1（点赞）+ last_operation_id = operation id
        Integer state = jdbcTemplate.queryForObject("""
                SELECT state FROM like_record WHERE user_id = ? AND target_type = ? AND target_id = ?
                """, Integer.class, uid, targetType, targetId);
        assertThat(state).as("首次点赞 ⇒ state 必须是 1（点赞）").isEqualTo(1);

        Long lastOp = jdbcTemplate.queryForObject("""
                SELECT last_operation_id FROM like_record
                WHERE user_id = ? AND target_type = ? AND target_id = ?
                """, Long.class, uid, targetType, targetId);
        assertThat(lastOp).as("last_operation_id 必须推进到本次 operation id").isEqualTo(Long.parseLong(msgId));

        // fact：事实流的版本号必须被分配（fact_version = 1）
        Long factVersion = jdbcTemplate.queryForObject("""
                SELECT fact_version FROM like_domain_fact_outbox
                WHERE aggregate_id = ?
                """, Long.class, streamId(uid, targetType, targetId));
        assertThat(factVersion)
                .as("事实必须被追加（fact_version=1）—— 没有它，DC 侧拿不到这条状态变更")
                .isEqualTo(1);

        // 事实流的版本号权威表也必须被推进
        Long streamVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM like_fact_stream WHERE aggregate_id = ?",
                Long.class, streamId(uid, targetType, targetId));
        assertThat(streamVersion).isEqualTo(1);

        // outbox：「首次点赞」的热点事件必须进发件箱
        Integer outbox = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM like_outbox_event WHERE event_key = ?",
                Integer.class, "first_like:" + uid + ":" + targetType + ":" + targetId);
        assertThat(outbox).as("首次点赞的热点事件必须进 like_outbox_event").isEqualTo(1);
    }

    // ==================================================================
    // ② 幂等：重复投递同一 msg_id
    // ==================================================================

    @Test
    @DisplayName("🔴 重复投递同一 msg_id ⇒ 幂等跳过（不重复落库、不重复发事实）")
    void duplicateMessageIsIdempotent() {
        long uid = SEQ.incrementAndGet();
        String targetType = "article-dup-" + uid;
        String targetId = "t-" + uid;
        String msgId = String.valueOf(SEQ.incrementAndGet());

        LikeMessageIngestService.LikeProcessPayload p = payload(msgId, uid, targetType, targetId, 1, true);
        p.firstLikeOutbox = ingestService.buildFirstLikeOutbox(p);

        ingestService.processBatch(List.of(p));
        ingestService.processBatch(List.of(p));   // ← 重投

        Integer records = countRows("""
                SELECT count(*) FROM like_record WHERE user_id = ? AND target_type = ? AND target_id = ?
                """, uid, targetType, targetId);
        assertThat(records).as("重投不得产生第二行").isEqualTo(1);

        Integer facts = countRows("""
                SELECT count(*) FROM like_domain_fact_outbox WHERE aggregate_id = ?
                """, streamId(uid, targetType, targetId));
        assertThat(facts)
                .as("重投不得追加第二条事实（否则状态流版本号会漂）")
                .isEqualTo(1);

        Integer outbox = countRows(
                "SELECT count(*) FROM like_outbox_event WHERE event_key = ?",
                "first_like:" + uid + ":" + targetType + ":" + targetId);
        assertThat(outbox).as("重投不得重复写热点事件").isEqualTo(1);
    }

    // ==================================================================
    // ③ 同一 msg_id 配不同载荷 ⇒ 报错
    // ==================================================================

    @Test
    @DisplayName("🔴 同一 msg_id 配不同载荷 ⇒ 报错（不是静默覆盖）")
    void conflictingPayloadIsRejected() {
        long uid = SEQ.incrementAndGet();
        String targetType = "article-conflict-" + uid;
        String targetId = "t-" + uid;
        String msgId = String.valueOf(SEQ.incrementAndGet());

        ingestService.processBatch(List.of(payload(msgId, uid, targetType, targetId, 1, true)));

        // 同 msg_id，但 action 不同 ⇒ 载荷哈希不同 ⇒ 必须报错
        LikeMessageIngestService.LikeProcessPayload conflicting =
                payload(msgId, uid, targetType, targetId, 3, false);

        assertThatThrownBy(() -> ingestService.processBatch(List.of(conflicting)))
                .as("同一 msg_id 复用不同载荷必须报错（B 侧：reused with conflicting payload）")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("conflicting payload");
    }

    // ==================================================================
    // ④ 乱序消息
    // ==================================================================

    @Test
    @DisplayName("🔴 乱序消息（operation_id 不大于已记录值）⇒ 标记完成但**不回放**状态变更")
    void outOfOrderMessageDoesNotReplayState() {
        long uid = SEQ.incrementAndGet();
        String targetType = "article-ooo-" + uid;
        String targetId = "t-" + uid;
        String newerMsgId = String.valueOf(SEQ.incrementAndGet());
        String olderMsgId = String.valueOf(Long.parseLong(newerMsgId) - 1000L);

        // 先投「新的」⇒ state=1
        ingestService.processBatch(List.of(payload(newerMsgId, uid, targetType, targetId, 1, true)));

        // 再投「旧的」（operation_id 更小）⇒ 必须被跳过，不得回放状态
        ingestService.processBatch(List.of(payload(olderMsgId, uid, targetType, targetId, 2, false)));

        Integer state = jdbcTemplate.queryForObject("""
                SELECT state FROM like_record WHERE user_id = ? AND target_type = ? AND target_id = ?
                """, Integer.class, uid, targetType, targetId);
        assertThat(state)
                .as("乱序消息不得把 state 从 1 改回 0")
                .isEqualTo(1);

        Integer facts = countRows("""
                SELECT count(*) FROM like_domain_fact_outbox WHERE aggregate_id = ?
                """, streamId(uid, targetType, targetId));
        assertThat(facts).as("乱序消息不得追加事实").isEqualTo(1);
    }

    // ==================================================================
    // ⑤ 批内失败 ⇒ 整批回滚
    // ==================================================================

    @Test
    @DisplayName("🔴 批内第 2 条非法 ⇒ **整批回滚**（第 1 条也不留痕）")
    void failingBatchRollsBackEverything() {
        long uid = SEQ.incrementAndGet();
        String targetType = "article-rollback-" + uid;
        String targetId = "t-" + uid;
        String goodMsgId = String.valueOf(SEQ.incrementAndGet());

        LikeMessageIngestService.LikeProcessPayload good =
                payload(goodMsgId, uid, targetType, targetId, 1, true);
        good.firstLikeOutbox = ingestService.buildFirstLikeOutbox(good);

        // 第 2 条：action=9 超出 1~4 ⇒ 载荷前置校验就会抛
        LikeMessageIngestService.LikeProcessPayload bad =
                payload(String.valueOf(SEQ.incrementAndGet()), uid, targetType, targetId + "-x", 9, false);

        assertThatThrownBy(() -> ingestService.processBatch(List.of(good, bad)))
                .isInstanceOf(RuntimeException.class);

        Integer inbox = countRows("SELECT count(*) FROM like_consume_inbox WHERE msg_id = ?", goodMsgId);
        assertThat(inbox)
                .as("同事务内先落的那条 inbox 也必须回滚 —— 否则「消息已消费」与「业务未生效」会不一致")
                .isZero();

        Integer records = countRows("""
                SELECT count(*) FROM like_record WHERE user_id = ? AND target_type = ? AND target_id = ?
                """, uid, targetType, targetId);
        assertThat(records).as("record 也必须回滚").isZero();
    }
}
