package com.rag.hot.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * <p><b>hot 域错误契约测试</b> —— 把 <b>L1</b> 的三张表钉死：
 * ① {@link ErrCodes} 的 <b>11 个码值 ＋ 声明顺序</b>；② {@link ErrMsg} 的 <b>11 条英文文案 ＋ 表顺序</b>；
 * ③ 两表<b>严格一一对应</b>；④ 未知码回退；⑤ {@link CodeError} 的<b>两个语义面</b>。
 *
 * <h2>🔴 为什么这张表必须有测试（不能只靠「逐字抄写」的自觉）</h2>
 * <p>错误码与文案是<b>跨服务可见契约</b>：HTTP 信封的 {@code code} / {@code msg} 字段直接写它
 * ⇒ ⇒ <b>改一个字符就是改客户端可见行为</b>（L1 变更 ⇒ 走决策流程）。
 * <p>而「抄写」这件事<b>没有机器判据</b>：抄错一个码值、抄漏一条文案、把英文文案写成中文
 * —— <b>编译照过、启动照过、其它测试也照过</b>（⇒ §3.3「假绿」的典型形态）。
 * ⇒ ⇒ 本类把<b>整张表</b>作为<b>期望值</b>显式写出来 ⇒ 任何漂移立刻红。
 *
 * <h2>为什么「声明顺序」也是契约</h2>
 * <p>B 侧 {@code HotErrorCode} 的<b>枚举声明顺序</b> = Go {@code errmsg.go} 的书写顺序
 * ⇒ 顺序本身是可观察的（序列化 / 人工对表）⇒ ⇒ 一并断言。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>码值与顺序：{@code service/hot/hot-common/src/main/java/com/rag/hot/hot/common/errmsg/HotErrorCode.java:39-72}；</li>
 *   <li>文案与表顺序：{@code .../errmsg/HotErrorMessages.java:54-72}（11 条 put）；</li>
 *   <li>未知码回退：{@code .../errmsg/HotErrorMessages.java:80-86}（回退到 {@code codeMsg[Error] = "FAIL"}）；</li>
 *   <li>异常载体的两个面：{@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/exception/HotCodeError.java:66-90}。</li>
 * </ul>
 *
 * <p>⚠️ **逐域隔离**：hot 的文案是<b>英文</b>；article / comment / like / users 域的同号码是<b>中文</b>
 * 且<b>语义不同</b>（hot 的 {@code 5002 = record not found}，users 的 {@code 5002 = 更新数据库失败}）
 * ⇒ ⇒ 本测试**只**锁 hot 域这张表，**不得**跨域统一（AGENTS.md §八「未统一化」）。
 */
class HotErrorContractTest {

    /**
     * 期望码表：<b>常量名 → 码值</b>，<b>顺序 = B 侧声明顺序</b>
     * （逐条抄自 {@code HotErrorCode.java:39-72}）。
     *
     * <p>⚠️ 用 {@link LinkedHashMap} ⇒ 断言时能同时校验<b>顺序</b>。
     */
    private static final Map<String, Integer> EXPECTED_CODES = new LinkedHashMap<>();

    static {
        EXPECTED_CODES.put("SUCCESS", 200);
        EXPECTED_CODES.put("ERROR", 500);
        EXPECTED_CODES.put("CODE_SERVER_BUSY", 1015);
        EXPECTED_CODES.put("ERROR_SERVER_COMMON", 5001);
        EXPECTED_CODES.put("ERROR_NOT_FOUND", 5002);
        EXPECTED_CODES.put("ERROR_INVALID_PARAM", 5003);
        EXPECTED_CODES.put("ERROR_UNAUTHORIZED", 5004);
        EXPECTED_CODES.put("ERROR_FORBIDDEN", 5005);
        EXPECTED_CODES.put("ERROR_ALREADY_EXIST", 5006);
        EXPECTED_CODES.put("ERROR_DB_SELECT", 5007);
        EXPECTED_CODES.put("ERROR_DB_UPDATE", 5008);
    }

    /**
     * 期望文案表：<b>码值 → 英文文案</b>，<b>顺序 = B 侧 {@code codeMsg} 书写序</b>
     * （逐条抄自 {@code HotErrorMessages.java:56-72}）。
     */
    private static final Map<Integer, String> EXPECTED_MSGS = new LinkedHashMap<>();

    static {
        EXPECTED_MSGS.put(200, "OK");
        EXPECTED_MSGS.put(500, "FAIL");
        EXPECTED_MSGS.put(1015, "service busy");
        EXPECTED_MSGS.put(5001, "internal server error");
        EXPECTED_MSGS.put(5002, "record not found");
        EXPECTED_MSGS.put(5003, "invalid parameter");
        EXPECTED_MSGS.put(5004, "unauthorized");
        EXPECTED_MSGS.put(5005, "forbidden");
        EXPECTED_MSGS.put(5006, "already exists");
        EXPECTED_MSGS.put(5007, "database query failed");
        EXPECTED_MSGS.put(5008, "database update failed");
    }

    @Test
    @DisplayName("码值与声明顺序逐条等于 B 侧（11 个码，一个都不能改）")
    void codesMatchBsideValuesAndOrder() {
        List<String> actualOrder = new ArrayList<>();
        for (ErrCodes code : ErrCodes.values()) {
            actualOrder.add(code.name());
        }
        assertEquals(new ArrayList<>(EXPECTED_CODES.keySet()), actualOrder,
                "枚举常量名或**声明顺序**与 B 侧不一致（顺序也是可观察的）");

        for (Map.Entry<String, Integer> entry : EXPECTED_CODES.entrySet()) {
            ErrCodes code = ErrCodes.valueOf(entry.getKey());
            assertEquals(entry.getValue(), code.getCode(),
                    "码值漂移：" + entry.getKey());
        }
    }

    @Test
    @DisplayName("文案逐条等于 B 侧（英文；一个字都不能改）")
    void messagesMatchBsideVerbatim() {
        for (Map.Entry<Integer, String> entry : EXPECTED_MSGS.entrySet()) {
            assertEquals(entry.getValue(), ErrMsg.getMsg(entry.getKey()),
                    "文案漂移：码 " + entry.getKey());
        }
    }

    @Test
    @DisplayName("码表与文案表严格一一对应（不多不少不重复）")
    void codeTableAndMessageTableAreStrictlyBijective() {
        // ① 码表侧：11 个码 ⇒ 11 个不同的码值
        assertEquals(EXPECTED_CODES.size(), ErrCodes.values().length);
        assertEquals(ErrCodes.values().length,
                Arrays.stream(ErrCodes.values()).map(ErrCodes::getCode).distinct().count(),
                "码表里有重复码值");

        // ② 文案表侧：键集 == 码值集合（不多不少），且**书写顺序**一致
        assertEquals(new ArrayList<>(EXPECTED_MSGS.keySet()),
                new ArrayList<>(ErrMsg.allMsgs().keySet()),
                "文案表的键集或**书写顺序**与 B 侧不一致");

        // ③ 逐条：每个码都能查到文案，且文案非空
        for (ErrCodes code : ErrCodes.values()) {
            String msg = ErrMsg.getMsg(code.getCode());
            assertNotNull(msg, "码 " + code.getCode() + " 没有文案");
            assertEquals(EXPECTED_MSGS.get(code.getCode()), msg);
        }
    }

    @Test
    @DisplayName("未知码必须回退到 FAIL（不是 null、不是空串）")
    void unknownCodeFallsBackToFail() {
        assertEquals("FAIL", ErrMsg.getMsg(999999));
        assertEquals("FAIL", ErrMsg.getMsg(-1));
        assertEquals("FAIL", ErrMsg.getMsg(0));
    }

    @Test
    @DisplayName("CodeError 的两个语义面必须分开：信封用纯文案，日志用 ErrCode:…, Errmsg:… 整串")
    void codeErrorKeepsTwoFacesApart() {
        CodeError error = new CodeError(ErrCodes.ERROR_SERVER_COMMON.getCode(),
                ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON.getCode()));

        // ① 信封面：纯文案（**不能**带 "ErrCode:" 前缀）
        assertEquals("internal server error", error.getBizMsg());

        // ② 日志面：B 侧 `(*CodeError).Error()` 的逐字节形态
        assertEquals("ErrCode:5001, Errmsg:internal server error", error.getMessage());

        // ③ 码值仍从基类透传
        assertEquals(5001, error.getCode());
    }

    @Test
    @DisplayName("CodeError 是 unchecked 的（BusinessException 的子类）⇒ 不会被强制的 catch 逼住")
    void codeErrorIsUnchecked() {
        assertThrows(RuntimeException.class,
                () -> {
                    throw new CodeError(5001, "internal server error");
                });
    }

    @Test
    @DisplayName("文案表对外只读（改不动 ⇒ 运行时无法篡改契约）")
    void messageTableIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> ErrMsg.allMsgs().put(12345, "hacked"));
        // ⇒ 复原（上面那次 put 必定失败，表未被改动）
        assertEquals("internal server error", ErrMsg.getMsg(5001));
        assertSame(ErrMsg.allMsgs(), ErrMsg.allMsgs(), "每次返回的应是同一个不可变视图");
    }
}
