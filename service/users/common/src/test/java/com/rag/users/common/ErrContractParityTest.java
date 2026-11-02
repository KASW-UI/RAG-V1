package com.rag.users.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>users 域错误契约逐条对拍 B</b>（码值集 + 文案映射 + 未知码回落）。
 *
 * <p><b>B 出处（本次迁移的行为基线）</b>：
 * <pre>
 *   B: RAG  service/users/users-common/src/main/java/com/rag/users/common/errmsg/ErrorCode.java:8-38
 *      —— 31 个枚举常量的 {@code (code, msg)}；:88-90 {@code of(int)}；:93-95 {@code getMsg(int)}。
 * </pre>
 *
 * <h2>本类钉住三件事</h2>
 * <ol>
 *   <li><b>31 条码值 + 31 条文案逐条相等</b>（不是「抽查几条」）⇒ 码值与文案都是 L1 契约，
 *       任何一条被改动都会在这里变红。</li>
 *   <li><b>码值集合与文案表的 key 集合一致</b> ⇒ 不会出现「有码无文案」的空洞。</li>
 *   <li><b>未知码的回落</b>：B 的 {@code of(int)} 用 {@code getOrDefault(code, ERROR)}
 *       ⇒ 未知码拿 {@code ERROR} 的文案 {@code "FAIL"}；C 侧必须同款
 *       （<b>不是 null、不是空串、不是抛异常</b>）。</li>
 * </ol>
 *
 * <p>⚠️ <b>期望值为什么写死在这里</b>：这是<b>冻结契约</b>的载体 —— 一旦有人「顺手统一文案」
 * 或「补一个码」，期望值不会跟着动 ⇒ 测试立刻变红 ⇒ 变更必须走决策流程。
 * （对应 AGENTS.md §五.2「L1 未变 ⇒ 停，走决策流程」。）
 */
class ErrContractParityTest {

    /** B 侧 {@code ErrorCode} 的 31 个 {@code (code, msg)}，逐条冻结。 */
    private static final Map<Integer, String> B_EXPECTED = new LinkedHashMap<>();

    static {
        B_EXPECTED.put(200, "OK");
        B_EXPECTED.put(500, "FAIL");
        B_EXPECTED.put(1015, "服务繁忙");
        B_EXPECTED.put(5001, "系统内部错误");
        B_EXPECTED.put(5002, "更新数据库失败");
        B_EXPECTED.put(5003, "查询数据库失败");
        B_EXPECTED.put(5004, "数据库插入失败");
        B_EXPECTED.put(5005, "事务操作失败");
        B_EXPECTED.put(5006, "雪花ID生成失败");
        B_EXPECTED.put(5007, "JSON序列化失败");
        B_EXPECTED.put(5008, "JSON反序列化失败");
        B_EXPECTED.put(5009, "Kafka消息发送失败");
        B_EXPECTED.put(5010, "延时消息发送失败");
        B_EXPECTED.put(1001, "用户名已存在");
        B_EXPECTED.put(1002, "用户名或密码错误");
        B_EXPECTED.put(1003, "用户不存在");
        B_EXPECTED.put(1004, "TOKEN不存在");
        B_EXPECTED.put(1005, "TOKEN格式错误");
        B_EXPECTED.put(1006, "TOKEN已过期");
        B_EXPECTED.put(1007, "TOKEN刷新失败");
        B_EXPECTED.put(1008, "权限不足");
        B_EXPECTED.put(1009, "未登录");
        B_EXPECTED.put(1010, "已登录");
        B_EXPECTED.put(1011, "用户已被封禁");
        B_EXPECTED.put(1012, "Redis更新失败");
        B_EXPECTED.put(1016, "管理员邀请码错误");
        B_EXPECTED.put(1017, "请求参数错误");
        B_EXPECTED.put(1018, "头像历史不存在");
        B_EXPECTED.put(3001, "积分余量不足");
        B_EXPECTED.put(3002, "重试次数超限");
        B_EXPECTED.put(3003, "积分处理超时");
    }

    @Test
    @DisplayName("31 条码值 + 31 条文案逐条等于 B 侧 ErrorCode")
    void everyCodeAndMessageMatchesB() {
        assertEquals(B_EXPECTED.size(), ErrCodes.codes().size(),
                "码值数量与 B 侧不一致（B = " + B_EXPECTED.size() + "）");
        B_EXPECTED.forEach((code, msg) -> {
            assertNotNull(ErrMsg.getMsg(code), "码值 " + code + " 没有文案");
            assertEquals(msg, ErrMsg.getMsg(code), "码值 " + code + " 的文案与 B 不一致");
        });
    }

    @Test
    @DisplayName("文案表的 key 集合 = 码值集合（无「有码无文案」空洞）")
    void messageTableKeysEqualCodeSet() {
        assertEquals(ErrCodes.codes(), ErrMsg.all().keySet());
    }

    @Test
    @DisplayName("未知码回落成 ERROR 的文案（FAIL），与 B 的 of(int) 同款")
    void unknownCodeFallsBackToFail() {
        assertEquals("FAIL", ErrMsg.getMsg(0));
        assertEquals("FAIL", ErrMsg.getMsg(-1));
        assertEquals("FAIL", ErrMsg.getMsg(999_999));
        // 恰好等于 ERR_CODES.ERROR 本身 ⇒ 取自己的文案，不回落
        assertEquals("FAIL", ErrMsg.getMsg(ErrCodes.ERROR));
        assertEquals("OK", ErrMsg.getMsg(ErrCodes.SUCCESS));
    }

    @Test
    @DisplayName("码值常量逐条等于 B 侧枚举的 code")
    void constantsMatchBCodes() {
        assertEquals(200, ErrCodes.SUCCESS);
        assertEquals(500, ErrCodes.ERROR);
        assertEquals(1015, ErrCodes.CODE_SERVER_BUSY);
        assertEquals(5001, ErrCodes.ERROR_SERVER_COMMON);
        assertEquals(5002, ErrCodes.ERROR_DB_UPDATE);
        assertEquals(5003, ErrCodes.ERROR_DB_SELECT);
        assertEquals(5004, ErrCodes.ERROR_DB_INSERT);
        assertEquals(5005, ErrCodes.ERROR_DB_TRANSACTION);
        assertEquals(5006, ErrCodes.ERROR_SNOWFLAKE_ID);
        assertEquals(5007, ErrCodes.ERROR_JSON_MARSHAL);
        assertEquals(5008, ErrCodes.ERROR_JSON_UNMARSHAL);
        assertEquals(5009, ErrCodes.ERROR_KAFKA_PUSH);
        assertEquals(5010, ErrCodes.ERROR_DELAY_MSG);
        assertEquals(1001, ErrCodes.ERROR_USER_EXIST);
        assertEquals(1002, ErrCodes.ERROR_LOGIN_WRONG);
        assertEquals(1003, ErrCodes.ERROR_USER_NOT_EXIST);
        assertEquals(1004, ErrCodes.ERROR_TOKEN_NOT_EXIST);
        assertEquals(1005, ErrCodes.ERROR_TOKEN_TYPE_WRONG);
        assertEquals(1006, ErrCodes.ERROR_TOKEN_RUNTIME);
        assertEquals(1007, ErrCodes.ERROR_TOKEN_REFRESH);
        assertEquals(1008, ErrCodes.ERROR_USER_NO_RIGHT);
        assertEquals(1009, ErrCodes.ERROR_USER_NO_LOGIN);
        assertEquals(1010, ErrCodes.ERROR_USER_LOGINED);
        assertEquals(1011, ErrCodes.ERROR_USER_BANNED);
        assertEquals(1012, ErrCodes.ERROR_REDIS_UPDATE);
        assertEquals(1016, ErrCodes.ERROR_ADMIN_INVITE_CODE_WRONG);
        assertEquals(1017, ErrCodes.ERROR_REQUEST_PARAM);
        assertEquals(1018, ErrCodes.ERROR_AVATAR_HISTORY_NOT_EXIST);
        assertEquals(3001, ErrCodes.ERROR_POINTS_INSUFFICIENT);
        assertEquals(3002, ErrCodes.ERROR_POINTS_RETRY_EXCEEDED);
        assertEquals(3003, ErrCodes.ERROR_POINTS_TIMEOUT);
    }

    @Test
    @DisplayName("文案是中文（users 域特有）—— 不得被改成英文")
    void messagesStayChinese() {
        assertTrue(ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY).matches(".*[\\u4e00-\\u9fa5].*"),
                "users 域文案必须是中文（follow/hot/message 域才是英文）");
        assertEquals("服务繁忙", ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY));
    }
}
