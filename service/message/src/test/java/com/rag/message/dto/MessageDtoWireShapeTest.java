package com.rag.message.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.message.dto.chat.AdminSendChatReq;
import com.rag.message.dto.chat.SendChatResp;
import com.rag.message.dto.chat.UserSendChatReq;
import com.rag.message.dto.common.EmptyReq;
import com.rag.message.dto.common.PageReq;
import com.rag.message.dto.conversation.ChatMessageItem;
import com.rag.message.dto.conversation.ConversationItem;
import com.rag.message.dto.conversation.ConversationListResp;
import com.rag.message.dto.conversation.ConversationMessagesReq;
import com.rag.message.dto.conversation.ConversationMessagesResp;
import com.rag.message.dto.notification.AdminSendNotificationReq;
import com.rag.message.dto.notification.AdminSendNotificationResp;
import com.rag.message.dto.notification.MarkNotificationReq;
import com.rag.message.dto.notification.NotificationItem;
import com.rag.message.dto.notification.NotificationListResp;
import com.rag.message.dto.read.MarkConversationReadReq;
import com.rag.message.dto.read.UnreadSummaryResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>message 域 17 个 DTO 的 wire 形状锁定测试</b>（Jackson 2）。
 *
 * <p><b>B 出处（本次迁移的行为基线 · D16）</b>：
 * <pre>
 *   B: RAG  service/message/message-api/src/main/java/com/rag/message/message/api/dto/
 *      AdminSendChatReq.java:33-73        AdminSendNotificationReq.java:65-125
 *      AdminSendNotificationResp.java:28-51 ChatMessageItem.java:30-130
 *      ConversationItem.java:36-95        ConversationListResp.java:31-60
 *      ConversationMessagesReq.java:53-100 ConversationMessagesResp.java:36-95
 *      EmptyReq.java                      MarkConversationReadReq.java:27-45
 *      MarkNotificationReq.java:29-50     NotificationItem.java:41-140
 *      NotificationListResp.java:35-60    PageReq.java:33-60
 *      SendChatResp.java:32-80            UnreadSummaryResp.java:25-56
 *      UserSendChatReq.java:36-85
 *      GoStyleStringInt64.java:21:889-894 + :19:74-77   ← ,string 的入参宽松口径
 * </pre>
 *
 * <h2>本类钉住什么</h2>
 * <ol>
 *   <li><b>17 个 record 的 JSON 键名逐条照抄 B 侧</b>（下划线式；{@code is_read} 不是 {@code read}）；</li>
 *   <li><b>{@code ,string} 字段出参是 JSON 字符串</b>（带引号），不是数字；</li>
 *   <li><b>{@code items} 缺席 ⇒ {@code []} 而不是 {@code null}</b>；
 *       <b>{@code extra} 缺席 ⇒ {@code null}</b>（Go 的 nil map 走 encoding/json 的 null 分支）；</li>
 *   <li><b>{@code ,string} 入参「字符串与整数都接受、浮点 / 布尔 / 空串一律拒」</b>
 *       （B 侧 {@code GoStyleStringInt64.parse} 的口径 ⇒ C 侧共享层
 *       {@code com.rag.common.web.StringInt64.parse}）。</li>
 * </ol>
 *
 * <p>⚠️ 本类是<b>纯单元测试</b>（不执行 SQL、不装配容器）—— 它锁的是<b>纯函数型 L1 wire 契约</b>，
 * 不属于「必须真触发」的范畴（真触发测试见
 * {@code com.rag.message.domain.service.MessageDomainServiceRealPgTest}）。
 */
class MessageDtoWireShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ============== 夹具 ==============

    private static ChatMessageItem sampleChatMessageItem() {
        return new ChatMessageItem(1L, 2L, 3L, 1, 4L, 1, "hello", false, 1700000000L);
    }

    private static ConversationItem sampleConversationItem() {
        return new ConversationItem(1L, 2L, 1, "peer", "peer@example.com", 2, 5L, "hi", 1700000001L,
                1700000002L, true);
    }

    private static NotificationItem sampleNotificationItem() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("k", "v");
        return new NotificationItem(1L, 2L, 3L, 1, 2, "t", "c", false, 1700000000L, extra);
    }

    private static List<String> keysOf(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            return keys;
        } catch (Exception e) {
            throw new AssertionError("不是合法 JSON：" + json, e);
        }
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new AssertionError("序列化失败", e);
        }
    }

    private static void assertKeys(Object value, String... expected) {
        assertEquals(List.of(expected), keysOf(write(value)));
    }

    /** 断言某个 {@code ,string} 字段在 JSON 里是<b>字符串</b>（带引号），不是数字。 */
    private static void assertStringField(Object value, String field) {
        try {
            JsonNode node = MAPPER.readTree(write(value));
            JsonNode n = node.get(field);
            assertTrue(n != null && n.isTextual(),
                    field + " 必须是 JSON 字符串，实际 = " + node.get(field));
        } catch (Exception e) {
            throw new AssertionError("不是合法 JSON", e);
        }
    }

    /**
     * 断言某个 {@code ,string} 字段在 JSON 里是<b>数字</b>（B 侧 tag 里没有 {@code ,string} 的那些）。
     */
    private static void assertNumberField(Object value, String field) {
        try {
            JsonNode node = MAPPER.readTree(write(value));
            JsonNode n = node.get(field);
            assertTrue(n != null && n.isNumber(),
                    field + " 必须是 JSON 数字，实际 = " + node.get(field));
        } catch (Exception e) {
            throw new AssertionError("不是合法 JSON", e);
        }
    }

    // ============== 1. 17 个 record 的键名逐条照抄 ==============

    @Test
    @DisplayName("AdminSendChatReq：键名 recipient_id / conversation_id / content")
    void adminSendChatReqKeys() {
        assertKeys(new AdminSendChatReq(1L, 2L, "hi"),
                "recipient_id", "conversation_id", "content");
    }

    @Test
    @DisplayName("SendChatResp：键名 conversation_id / message / status / can_send")
    void sendChatRespKeys() {
        assertKeys(new SendChatResp(1L, sampleChatMessageItem(), 2, true),
                "conversation_id", "message", "status", "can_send");
    }

    @Test
    @DisplayName("UserSendChatReq：键名 recipient_id / conversation_id / content")
    void userSendChatReqKeys() {
        assertKeys(new UserSendChatReq(1L, 2L, "hi"),
                "recipient_id", "conversation_id", "content");
    }

    @Test
    @DisplayName("EmptyReq：无字段 ⇒ 空对象")
    void emptyReqKeys() {
        assertKeys(new EmptyReq());
    }

    @Test
    @DisplayName("PageReq：键名 offset / limit / unread_only")
    void pageReqKeys() {
        assertKeys(new PageReq(0, 20, true), "offset", "limit", "unread_only");
    }

    @Test
    @DisplayName("ChatMessageItem：键名 id / conversation_id / sender_id / sender_role / recipient_id / recipient_role / content / is_read / created_at")
    void chatMessageItemKeys() {
        assertKeys(sampleChatMessageItem(),
                "id", "conversation_id", "sender_id", "sender_role", "recipient_id", "recipient_role",
                "content", "is_read", "created_at");
    }

    @Test
    @DisplayName("ConversationItem：键名 conversation_id / peer_id / peer_role / peer_name / peer_email / status / unread_count / latest_message / latest_message_at / updated_at / can_send")
    void conversationItemKeys() {
        assertKeys(sampleConversationItem(),
                "conversation_id", "peer_id", "peer_role", "peer_name", "peer_email", "status",
                "unread_count", "latest_message", "latest_message_at", "updated_at", "can_send");
    }

    @Test
    @DisplayName("ConversationListResp：键名 items / total / unread_count")
    void conversationListRespKeys() {
        assertKeys(new ConversationListResp(List.of(sampleConversationItem()), 1L, 2L),
                "items", "total", "unread_count");
    }

    @Test
    @DisplayName("ConversationMessagesReq：键名 conversation_id / offset / limit")
    void conversationMessagesReqKeys() {
        assertKeys(new ConversationMessagesReq(1L, 0, 20), "conversation_id", "offset", "limit");
    }

    @Test
    @DisplayName("ConversationMessagesResp：键名 items / status / can_send / pending_sender_id / pending_sender_role")
    void conversationMessagesRespKeys() {
        assertKeys(new ConversationMessagesResp(List.of(sampleChatMessageItem()), 2, true, 7L, 1),
                "items", "status", "can_send", "pending_sender_id", "pending_sender_role");
    }

    @Test
    @DisplayName("AdminSendNotificationReq：键名 broadcast / target_id / kind / title / content / extra")
    void adminSendNotificationReqKeys() {
        assertKeys(new AdminSendNotificationReq(false, 1L, 2, "t", "c", Map.of("k", "v")),
                "broadcast", "target_id", "kind", "title", "content", "extra");
    }

    @Test
    @DisplayName("AdminSendNotificationResp：键名 affected")
    void adminSendNotificationRespKeys() {
        assertKeys(new AdminSendNotificationResp(3L), "affected");
    }

    @Test
    @DisplayName("MarkNotificationReq：键名 notification_id")
    void markNotificationReqKeys() {
        assertKeys(new MarkNotificationReq(1L), "notification_id");
    }

    @Test
    @DisplayName("NotificationItem：键名 id / recipient_id / sender_id / sender_role / kind / title / content / is_read / created_at / extra")
    void notificationItemKeys() {
        assertKeys(sampleNotificationItem(),
                "id", "recipient_id", "sender_id", "sender_role", "kind", "title", "content",
                "is_read", "created_at", "extra");
    }

    @Test
    @DisplayName("NotificationListResp：键名 items / total / unread_count")
    void notificationListRespKeys() {
        assertKeys(new NotificationListResp(List.of(sampleNotificationItem()), 1L, 2L),
                "items", "total", "unread_count");
    }

    @Test
    @DisplayName("MarkConversationReadReq：键名 conversation_id")
    void markConversationReadReqKeys() {
        assertKeys(new MarkConversationReadReq(1L), "conversation_id");
    }

    @Test
    @DisplayName("UnreadSummaryResp：键名 notification_unread / conversation_unread / total_unread（三个都是数字）")
    void unreadSummaryRespKeys() {
        assertKeys(new UnreadSummaryResp(1L, 2L, 3L),
                "notification_unread", "conversation_unread", "total_unread");
        assertNumberField(new UnreadSummaryResp(1L, 2L, 3L), "notification_unread");
        assertNumberField(new UnreadSummaryResp(1L, 2L, 3L), "conversation_unread");
        assertNumberField(new UnreadSummaryResp(1L, 2L, 3L), "total_unread");
    }

    // ============== 2. ,string 出参是 JSON 字符串 ==============

    @Test
    @DisplayName(",string 出参：id / conversation_id / sender_id / recipient_id / peer_id / pending_sender_id / affected 都是 JSON 字符串")
    void stringTaggedFieldsAreJsonStrings() {
        assertStringField(sampleChatMessageItem(), "id");
        assertStringField(sampleChatMessageItem(), "conversation_id");
        assertStringField(sampleChatMessageItem(), "sender_id");
        assertStringField(sampleChatMessageItem(), "recipient_id");

        assertStringField(sampleNotificationItem(), "id");
        assertStringField(sampleNotificationItem(), "recipient_id");
        assertStringField(sampleNotificationItem(), "sender_id");

        assertStringField(sampleConversationItem(), "conversation_id");
        assertStringField(sampleConversationItem(), "peer_id");

        assertStringField(new SendChatResp(9L, sampleChatMessageItem(), 2, true), "conversation_id");
        assertStringField(new ConversationMessagesResp(List.of(), 2, true, 7L, 1), "pending_sender_id");
        assertStringField(new AdminSendNotificationResp(3L), "affected");

        // ⚠️ 未带 ,string 的字段必须仍是数字（B 侧 tag 里没有 ,string ⇒ 不得「顺手统一」）
        assertNumberField(sampleChatMessageItem(), "sender_role");
        assertNumberField(sampleChatMessageItem(), "created_at");
        assertNumberField(sampleNotificationItem(), "kind");
        assertNumberField(sampleConversationItem(), "unread_count");
        assertNumberField(new ConversationListResp(List.of(), 0L, 0L), "total");
        assertNumberField(new NotificationListResp(List.of(), 0L, 0L), "unread_count");
    }

    // ============== 3. items 空 ⇒ []；extra 空 ⇒ null ==============

    @Test
    @DisplayName("items 缺席 ⇒ [] 而不是 null（Go 的非 nil 空切片）")
    void emptyItemsSerializeAsEmptyArray() {
        assertTrue(write(new ConversationListResp(null, 0L, 0L)).contains("\"items\":[]"));
        assertTrue(write(new NotificationListResp(null, 0L, 0L)).contains("\"items\":[]"));
        assertTrue(write(new ConversationMessagesResp(null, 0, false, 0L, 0)).contains("\"items\":[]"));
    }

    @Test
    @DisplayName("extra 缺席 ⇒ null（Go 的 nil map 走 encoding/json 的 null 分支）")
    void nullExtraSerializesAsJsonNull() {
        String json = write(new NotificationItem(1L, 2L, 3L, 1, 2, "t", "c", false, 0L, null));
        assertTrue(json.contains("\"extra\":null"), "实际 = " + json);
        assertTrue(write(new AdminSendNotificationReq(false, 1L, 2, "t", "c", null))
                .contains("\"extra\":null"));
    }

    @Test
    @DisplayName("零值占位：字符串字段缺席 ⇒ \"\" 而不是 null")
    void nullStringsBecomeEmptyString() {
        assertTrue(write(new ChatMessageItem(0L, 0L, 0L, 0, 0L, 0, null, false, 0L))
                .contains("\"content\":\"\""));
        assertTrue(write(new NotificationItem(0L, 0L, 0L, 0, 0, null, null, false, 0L, null))
                .contains("\"title\":\"\""));
        assertTrue(write(new ConversationItem(0L, 0L, 0, null, null, 0, 0L, null, 0L, 0L, false))
                .contains("\"peer_name\":\"\""));
    }

    @Test
    @DisplayName("SendChatResp：message 永不返回 null（缺失时是全零对象）")
    void sendChatRespMessageIsNeverNull() {
        SendChatResp resp = new SendChatResp(1L, null, 2, true);
        assertTrue(write(resp).contains("\"message\":{"));
    }

    // ============== 4. ,string 入参：字符串与整数都接受 ==============

    @Test
    @DisplayName(",string 入参：JSON 字符串 \"123\" 与 JSON 数字 123 都接受")
    void stringTaggedRequestAcceptsStringAndNumber() throws Exception {
        UserSendChatReq fromString = MAPPER.readValue(
                "{\"recipient_id\":\"123\",\"conversation_id\":456,\"content\":\"hi\"}",
                UserSendChatReq.class);
        assertEquals(123L, fromString.recipientId());
        assertEquals(456L, fromString.conversationId());

        MarkNotificationReq mark = MAPPER.readValue("{\"notification_id\":\"9\"}",
                MarkNotificationReq.class);
        assertEquals(9L, mark.notificationId());

        MarkConversationReadReq read = MAPPER.readValue("{\"conversation_id\":77}",
                MarkConversationReadReq.class);
        assertEquals(77L, read.conversationId());
    }

    @Test
    @DisplayName(",string 入参：缺席 ⇒ null（必填与否交给 Bean Validation，不在解析层判）")
    void stringTaggedRequestAbsentIsNull() throws Exception {
        UserSendChatReq req = MAPPER.readValue("{\"content\":\"hi\"}", UserSendChatReq.class);
        assertNull(req.recipientId());
        assertNull(req.conversationId());
    }

    // ============== 5. ,string 入参：浮点 / 布尔 / 空串一律拒 ==============

    @Test
    @DisplayName(",string 入参：1.5 / \"1.5\" / true / \"\" 一律拒绝（Go strconv.ParseInt 口径）")
    void stringTaggedRequestRejectsNonIntegerForms() {
        assertRejected("{\"recipient_id\":1.5,\"content\":\"hi\"}", UserSendChatReq.class);
        assertRejected("{\"recipient_id\":\"1.5\",\"content\":\"hi\"}", UserSendChatReq.class);
        assertRejected("{\"recipient_id\":true,\"content\":\"hi\"}", UserSendChatReq.class);
        assertRejected("{\"recipient_id\":\"\",\"content\":\"hi\"}", UserSendChatReq.class);

        // ⚠️ 1.0 也是浮点字面量 ⇒ B 侧 ParseInt 同样失败（哪怕数值上是整数）
        assertRejected("{\"recipient_id\":1.0,\"content\":\"hi\"}", UserSendChatReq.class);

        assertRejected("{\"notification_id\":\"1.5\"}", MarkNotificationReq.class);
        assertRejected("{\"conversation_id\":true}", MarkConversationReadReq.class);
        assertRejected("{\"conversation_id\":\"\"}", MarkConversationReadReq.class);
    }

    /**
     * 断言一个 {@code ,string} 请求字段被拒绝。
     *
     * <p>拒绝的判定来自共享层 {@code com.rag.common.web.StringInt64.parse} 的
     * {@link IllegalArgumentException}；Jackson 可能直接把它抛出来，也可能包一层
     * {@code JsonMappingException} ⇒ ⇒ 断言时**沿 cause 链**找那个
     * {@code IllegalArgumentException}（或含同一句文案的异常）。
     */
    private static void assertRejected(String json, Class<?> targetType) {
        Exception thrown = assertThrows(Exception.class, () -> MAPPER.readValue(json, targetType));
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException) {
                assertTrue(t.getMessage().contains("not a valid string-encoded int64"),
                        "拒绝理由应来自 StringInt64.parse，实际 = " + t.getMessage());
                return;
            }
            if (t.getMessage() != null && t.getMessage().contains("not a valid string-encoded int64")) {
                return;
            }
        }
        throw new AssertionError("期望拒绝 " + json + "，实际抛出 = " + thrown);
    }
}
