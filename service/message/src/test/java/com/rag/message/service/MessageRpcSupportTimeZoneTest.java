package com.rag.message.service;

import com.rag.message.domain.entity.ConversationEntity;
import com.rag.message.domain.entity.ConversationMessageEntity;
import com.rag.message.domain.entity.NotificationEntity;
import com.rag.proto.message.ChatMessageItem;
import com.rag.proto.message.ConversationItem;
import com.rag.proto.message.NotificationItem;
import com.rag.proto.message.SenderRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * <p><b>移植说明（本文件来自 B 侧测试资产 · AGENTS.md §1.0c「测试资产单独一档」）</b>。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/test/java/com/rag/message/message/rpc/service/MessageRpcSupportTimeZoneTest.java}
 * （216 行 · **测试体与期望值逐条照搬**）。
 * <p><b>移植时只改了 3 处「纯机械」项</b>：① package {@code com.rag.message.message.rpc.service}
 * → {@code com.rag.message.service}（D7 包压平 · 与 C 侧 {@code MessageRpcSupport} 同包）
 * ② proto import {@code com.rag.message.message.proto.*} → {@code com.rag.proto.message.*}（D7 proto 集中化）
 * ③ 实体 import {@code ...rpc.domain.*} → {@code com.rag.message.domain.entity.*}（D7）。
 * ⇒ ⇒ <b>断言体、期望值、{@code @DisplayName} 里的 DRIFT 编号一个字未改</b>。
 *
 * <p><b>为什么这批用例的期望值可信</b>：它们断言的是<b>时区无关性</b>这一<b>性质</b>
 * （换任意 JVM 默认时区，epoch 必须不变），而不是「在某个时区下算对」——
 * ⇒ ⇒ 性质型断言<b>不依赖任何一方的具体取值</b> ⇒ ⇒ B 侧与 C 侧实现必须<b>同时</b>满足它
 * ⇒ ⇒ 这正是 `.agents/verification.md` 第九阶段要求的「以相同输入比较 B 与 C」。
 *
 * <p><b>⚠️ 本类不构成「双侧活体对拍」的全部</b>：它是<b>纯函数级</b>的（无 DB / 无网络 / 无 Spring 上下文），
 * 覆盖面限于「时间列改 TIMESTAMPTZ 后的墙钟→epoch 换算」一条轴；
 * HTTP / gRPC / 事务 / DB 终态等面仍需真容器对拍 ⇒ 状态见
 * {@code docs/DRIFT/message/verification-message-service-2026-11-02.md} §七·4。
 */
@DisplayName("DRIFT-MSG-V-020：时间列改 TIMESTAMPTZ 后，墙钟→epoch 换算必须与时区无关")
class MessageRpcSupportTimeZoneTest {

    /**
     * 一个<b>明确的瞬时</b>：2026-10-23T02:00+08:00 = 2026-10-22T18:00Z。
     * <p>刻意用 {@code +08:00} 而不是 UTC：只有当实现<b>真的读了 offset</b>时，
     * 用例才能测出「把墙钟当 UTC」的旧 bug（那种实现会算出相差 8 小时的值）。
     */
    private static final OffsetDateTime INSTANT =
            OffsetDateTime.of(2026, 10, 23, 2, 0, 0, 0, ZoneOffset.ofHours(8));

    /** 跑一遍「给定 JVM 默认时区下的换算」，跑完还原。 */
    private static long epochUnderJvmZone(String zone, java.util.function.Supplier<NotificationEntity> arg) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            return MessageRpcSupport.toNotificationItem(arg.get()).getCreatedAt();
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private static NotificationEntity notificationWithCreatedAt(OffsetDateTime createdAt) {
        NotificationEntity n = new NotificationEntity();
        n.setId(1L);
        n.setRecipientId(9L);
        n.setSenderId(8L);
        n.setContent("hello");
        // ⚠️ protobuf builder 对 null 字符串直接 NPE（生产路径上 title/content 恒非空），
        //    补上不是"为了让测试过"，而是复刻真实入参。
        n.setTitle("t");
        n.setCreatedAt(createdAt);
        return n;
    }

    // ==================================================================
    // 1. 核心性质：epoch 与 JVM 默认时区无关（这是本次修复真正买到的东西）
    // ==================================================================

    @Test
    @DisplayName("🔴 V-020：同一个瞬时在 UTC / Asia/Shanghai / America/New_York 下必须给出同一 epoch")
    void epochIsIndependentOfJvmDefaultZone() {
        long expected = INSTANT.toEpochSecond();

        long utc = epochUnderJvmZone("UTC", () -> notificationWithCreatedAt(INSTANT));
        long shanghai = epochUnderJvmZone("Asia/Shanghai", () -> notificationWithCreatedAt(INSTANT));
        long newYork = epochUnderJvmZone("America/New_York", () -> notificationWithCreatedAt(INSTANT));

        assertEquals(expected, utc, "UTC 下应等于瞬时本身的 epoch");
        assertEquals(expected, shanghai, "Asia/Shanghai 下必须**同样**等于瞬时 epoch");
        assertEquals(expected, newYork, "America/New_York 下必须**同样**等于瞬时 epoch");
    }

    /**
     * 反事实检验：证明上条不是在测一个恒等式。
     * <p>把值退化成「无 offset 的墙钟」并按 UTC 解释，结果必须<b>不同</b> ——
     * 差 8 小时。这正是修复前的 bug 形态（{@code toEpochSecond(ZoneOffset.UTC)}）。
     */
    @Test
    @DisplayName("反事实：若把墙钟当 UTC 解释，结果必须与正确值差 8 小时（证明上一条不是恒真）")
    void treatingWallClockAsUtcMustDiffer() {
        long correct = INSTANT.toEpochSecond();
        long wrongAsUtc = java.time.LocalDateTime.ofInstant(INSTANT.toInstant(), ZoneOffset.UTC)
                .toEpochSecond(ZoneOffset.UTC);
        assertEquals(INSTANT.toEpochSecond(), wrongAsUtc,
                "前置条件：同一个瞬时用不同 offset 构造 ⇒ epoch 相同（本用例的判据本身要自洽）");

        long oldBehaviour = INSTANT.toLocalDateTime().toEpochSecond(ZoneOffset.UTC);
        assertNotEquals(correct, oldBehaviour,
                "若两者相等，说明这两个值其实代表同一瞬时，用例失去区分力");
        // ⚠️ 符号别写反：把「02:00+08」当成「02:00 UTC」，得到的 epoch 比真实瞬时**大** 8 小时
        //   （= 时间被算得**更晚**），这与 DRIFT-MSG-V-007 记录的「RAG 比 Go 晚 8 小时」同向。
        assertEquals(8 * 3600L, oldBehaviour - correct,
                "旧行为应把时间算晚整整一个东八区（+8h）");
    }

    // ==================================================================
    // 2. 三条业务输出路径共用同一个换算（上一轮就有，本轮保留）
    // ==================================================================

    @Test
    @DisplayName("通知 created_at / 会话消息 created_at 走同一套换算")
    void notificationAndMessageUseTheSameConversion() {
        long expected = INSTANT.toEpochSecond();
        assertEquals(expected,
                MessageRpcSupport.toNotificationItem(notificationWithCreatedAt(INSTANT)).getCreatedAt());

        ConversationMessageEntity m = new ConversationMessageEntity();
        m.setId(1L);
        m.setContent("hello");
        m.setCreatedAt(INSTANT);
        ChatMessageItem out = MessageRpcSupport.toMessageItem(m);
        assertEquals(expected, out.getCreatedAt(),
                "会话消息与通知走同一套规则 —— 两处曾各自写死 UTC 转换");
    }

    @Test
    @DisplayName("会话 latest_message_at / updated_at 用同一套换算")
    void conversationTimestampsUseTheSameConversion() {
        long expected = INSTANT.toEpochSecond();

        ConversationEntity conv = new ConversationEntity();
        conv.setId(1L);
        conv.setParticipant1Role(SenderRole.USER.getNumber());
        conv.setParticipant1Id(11L);
        conv.setParticipant2Role(SenderRole.USER.getNumber());
        conv.setParticipant2Id(22L);
        conv.setLastMessageAt(INSTANT);
        conv.setUpdatedAt(INSTANT);

        ConversationItem out = MessageRpcSupport.buildConversationItem(
                conv, SenderRole.USER, 11L, 0L, "peer", "peer@example.com");

        assertEquals(expected, out.getLatestMessageAt());
        assertEquals(expected, out.getUpdatedAt());
    }

    /** null 时间戳仍回 0（proto3 默认值），不抛 NPE —— 与时区无关，防修坏边界。 */
    @Test
    @DisplayName("null 时间戳回 0，不抛 NPE")
    void nullTimestampsMustStillMapToZero() {
        NotificationItem out = MessageRpcSupport.toNotificationItem(
                notificationWithCreatedAt(null));
        assertEquals(0L, out.getCreatedAt(),
                "proto3 的 int64 默认值是 0；改时区逻辑不得顺手改变 null 的处理");
    }

    // ==================================================================
    // 3. 反射守门：实体字段的声明类型必须仍是 OffsetDateTime（users 域同款做法）
    // ==================================================================

    @Test
    @DisplayName("🔴 实体 8 个时间字段必须声明为 OffsetDateTime（防止有人改回 LocalDateTime）")
    void entityFieldsMustBeOffsetDateTime() {
        assertOffsetDateTime(NotificationEntity.class, "readAt", "createdAt", "updatedAt");
        assertOffsetDateTime(ConversationEntity.class, "lastMessageAt", "createdAt", "updatedAt");
        assertOffsetDateTime(ConversationMessageEntity.class, "readAt", "createdAt");
    }

    private static void assertOffsetDateTime(Class<?> type, String... fieldNames) {
        for (String name : fieldNames) {
            Field f;
            try {
                f = type.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                throw new AssertionError(type.getSimpleName() + " 缺少字段 " + name, e);
            }
            assertEquals(OffsetDateTime.class, f.getType(),
                    type.getSimpleName() + "#" + name + " 必须是 OffsetDateTime："
                            + "改回 LocalDateTime 会把「猜时区」那一步重新引回来，"
                            + "而 TIMESTAMPTZ 列的值本来就自带 offset");
        }
    }

    /**
     * 变异验证用的辅助断言：<b>不同 offset 的同一瞬时必须相等</b>，
     * 不同瞬时必须不等。
     * <p>有了它，上面 {@link #epochIsIndependentOfJvmDefaultZone()} 才有意义 ——
     * 若有人把 {@code OffsetDateTime} 换成只取 {@code toLocalDateTime()} 的实现，
     * 这一条会先红。
     */
    @Test
    @DisplayName("辅助性质：同一瞬时的不同 offset 表示 ⇒ 同一 epoch；不同瞬时 ⇒ 不同 epoch")
    void sameInstantDifferentOffsetsCollapse() {
        OffsetDateTime asUtc = INSTANT.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime asNewYork = INSTANT.withOffsetSameInstant(ZoneId.of("America/New_York").getRules().getOffset(INSTANT.toInstant()));
        assertEquals(INSTANT.toEpochSecond(), asUtc.toEpochSecond());
        assertEquals(INSTANT.toEpochSecond(), asNewYork.toEpochSecond());
        assertNotEquals(INSTANT.toEpochSecond(), INSTANT.plusHours(1).toEpochSecond(),
                "不同时刻必须给出不同 epoch —— 否则上面的相等是恒真");
    }
}
