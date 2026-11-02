package com.rag.users.common;

import com.rag.common.web.StringInt64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * <p><b>{@code common/web/StringInt64.parse} 的运行对拍</b>（闭合迁移审核的 <b>NOT_VERIFIED</b> 项）。
 *
 * <p><b>B 出处（本次迁移的行为基线）</b>：
 * <pre>
 *   B: RAG  service/users/users-common/src/main/java/com/rag/users/common/http/GoStyleStringInt64.java:83-125
 *      —— {@code parse(Object raw, String fieldName)}：
 *         null → null；String → {@code Long.parseLong}（失败即抛）；
 *         Byte/Short/Integer/Long → {@code longValue()}；
 *         BigInteger → {@code bitLength() >= 64} 判越界（±2^63 的 bitLength 都是 63 ⇒ 合法）；
 *         BigDecimal/Double/Float → 一律拒（上游的 "1.0" / "1e2" 同样 ParseInt 失败）；
 *         Boolean/数组/对象 → 一律拒。
 *      异常消息：{@code field "<fieldName>" is not a valid string-encoded int64: <raw>}。
 * </pre>
 *
 * <h2>⚠️ 为什么这个测试落在 <b>users-common</b> 而不是根 {@code common/}</h2>
 * <p>① 该类<b>原主域</b>就是 users 域（B 侧在 {@code users-common/http/}）⇒ D3 上提后根 {@code common/} 承接；
 * ② 根 {@code common/} 的写权归**收口任务**（AGENTS.md §五.3 规则 3）⇒ 本 lane **不改根 {@code common/} 一个字**
 * ⇒ 测试落本模块（对根 {@code common} 用 **test 作用域**依赖）⇒ 既补上了运行证据，又不越权。
 * <p>⚠️ **迁移审核原话**：「C 侧全仓 0 个测试调用 ⇒ §九 明令『不得用静态代码相似度替代运行验证』⇒ 判 NOT_VERIFIED」
 * ⇒ 本类就是那条 NOT_VERIFIED 的闭合件。
 */
class StringInt64ParityTest {

    private static final String FIELD = "uid";

    // ============== 接受 ==============

    @Test
    @DisplayName("null → null（B：null 由必填校验决定后续，解析器不报错）")
    void nullYieldsNull() {
        assertNull(StringInt64.parse(null, FIELD));
    }

    @Test
    @DisplayName("字符串整数 → long（含 \"+123\"，与上游的 ParseInt 同款）")
    void numericStringIsAccepted() {
        assertEquals(123L, StringInt64.parse("123", FIELD));
        assertEquals(123L, StringInt64.parse("+123", FIELD));
        assertEquals(Long.MAX_VALUE, StringInt64.parse("9223372036854775807", FIELD));
        assertEquals(Long.MIN_VALUE, StringInt64.parse("-9223372036854775808", FIELD));
    }

    @Test
    @DisplayName("整数族（byte/short/int/long）→ long（B：走 longValue() 分支）")
    void integralTypesAreAccepted() {
        assertEquals(7L, StringInt64.parse((byte) 7, FIELD));
        assertEquals(8L, StringInt64.parse((short) 8, FIELD));
        assertEquals(9L, StringInt64.parse(9, FIELD));
        assertEquals(10L, StringInt64.parse(10L, FIELD));
    }

    @Test
    @DisplayName("BigInteger：±2^63 合法（bitLength=63），2^63 越界被拒（bitLength=64）")
    void bigIntegerBoundaryIsExact() {
        assertEquals(Long.MAX_VALUE, StringInt64.parse(new BigInteger("9223372036854775807"), FIELD));
        assertEquals(Long.MIN_VALUE, StringInt64.parse(new BigInteger("-9223372036854775808"), FIELD));
        assertThrows(IllegalArgumentException.class,
                () -> StringInt64.parse(new BigInteger("9223372036854775808"), FIELD));
        assertThrows(IllegalArgumentException.class,
                () -> StringInt64.parse(new BigInteger("-9223372036854775809"), FIELD));
    }

    // ============== 拒绝 ==============

    @Test
    @DisplayName("非整数字符串一律拒（\"\" / \" 12\" / \"1.5\" / \"1e2\" / \"abc\"）")
    void malformedStringsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse("", FIELD));
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse(" 12", FIELD));
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse("1.5", FIELD));
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse("1e2", FIELD));
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse("abc", FIELD));
    }

    @Test
    @DisplayName("浮点与 BigDecimal 一律拒（B：上游的 \"1.0\" / \"1e2\" 同样 ParseInt 失败）")
    void floatingPointIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse(new BigDecimal("1.0"), FIELD));
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse(1.0d, FIELD));
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse(1.5d, FIELD));
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse(1.0f, FIELD));
    }

    @Test
    @DisplayName("布尔 / 数组 / 对象一律拒（B：上游报 “the value in map is not string, but <kind>”）")
    void nonScalarTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse(Boolean.TRUE, FIELD));
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse(new int[]{1}, FIELD));
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse(new Object[]{"a"}, FIELD));
        assertThrows(IllegalArgumentException.class, () -> StringInt64.parse(Map.of("a", 1), FIELD));
    }

    @Test
    @DisplayName("异常消息形状与 B 一致（field \"<name>\" is not a valid string-encoded int64: <raw>）")
    void exceptionMessageShapeMatchesB() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> StringInt64.parse("abc", FIELD));
        assertEquals("field \"uid\" is not a valid string-encoded int64: abc", ex.getMessage());
    }
}
