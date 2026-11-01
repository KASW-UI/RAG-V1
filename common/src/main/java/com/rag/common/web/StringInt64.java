package com.rag.common.web;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Go {@code json:",string"} int64 请求字段的<b>逐条等价</b>解析器。
 *
 * <h3>为什么需要它：Go 的 {@code ,string} 是「入参宽松、出参严格」</h3>
 *
 * <p>最容易踩的坑是：看到 {@code ,string} 就以为「必须是 JSON 字符串」。
 * <b>Go 不是这样。</b>根因是一个反射事实：
 * <pre>
 *   encoding/json:  type Number string     ← json.Number 是【string 的命名类型】
 *   ⇒ reflect.TypeOf(json.Number("123")).Kind() == reflect.String
 * </pre>
 * （实测：`reflect.TypeOf(json.Number("123")).Kind() = string`）
 *
 * <p>而 go-zero 的解析层用 {@code UseNumber()} 读 body（`core/jsonx/json.go:74-77`），
 * 于是 JSON 数字进了 map 之后是 {@code json.Number} —— 它的 Kind 是 {@code String}，
 * 于是 {@code core/mapping/unmarshaler.go:889-894} 的这行判定<b>放行</b>：
 * <pre>
 *   valueKind := reflect.TypeOf(mapValue).Kind()
 *   if valueKind != reflect.String { return fmt.Errorf("the value in map is not string, but %s", valueKind) }
 * </pre>
 * 随后 {@code fillPrimitive}（{@code unmarshaler.go:1115-1125}）里<b>同时有</b>
 * {@code case string} 与 {@code case json.Number} 两条分支，都会走
 * {@code setValueFromString → strconv.ParseInt}。
 *
 * <p><b>⇒ Go 的实测行为（v1.10.2 真源探针）</b>：
 * <table border="1">
 *   <caption>Go vs RAG（修复前）</caption>
 *   <tr><th>输入</th><th>Go</th><th>RAG（修复前）</th></tr>
 *   <tr><td>{@code "123"}（JSON 字符串）</td><td>接受 → 123</td><td>接受 → 123</td></tr>
 *   <tr><td>{@code 123}（JSON 数字）</td><td><b>接受 → 123</b></td><td><b>拒绝 5001</b> ← DRIFT-001</td></tr>
 *   <tr><td>{@code 1.5}</td><td>拒绝（ParseInt 失败）</td><td>拒绝</td></tr>
 *   <tr><td>{@code 1.0}</td><td>拒绝（字面量 "1.0"）</td><td>拒绝</td></tr>
 *   <tr><td>{@code true}</td><td>拒绝（"not string, but bool"）</td><td>拒绝</td></tr>
 *   <tr><td>{@code null}</td><td>置 nil → 必填校验拒绝</td><td>同</td></tr>
 * </table>
 *
 * <h3>为什么用 {@code Object} 作 setter 入参</h3>
 * <p>改成 {@code Object} 是为了让 Jackson <b>不做任何强转</b>，
 * 由本类完全接管判定。上一版用 {@code String} 入参 + {@code GoStyleCoercion} 的
 * {@code LogicalType.Textual=Integer/Fail}，那条规则会连合法的 JSON 数字一起拒掉。
 *
 * <p>副作用（已知且可接受）：这些字段<b>不再受</b> {@code LogicalType.Textual} 规则约束，
 * 因为声明类型是 {@code Object}、没有目标 LogicalType。
 *
 * <h3>拒绝浮点的依据</h3>
 * <p>Go 侧浮点 JSON 字面量会被 {@code UseNumber} 保留成原样字符串
 * （{@code 1.0} → {@code "1.0"}、{@code 1e2} → {@code "1e2"}），
 * {@code strconv.ParseInt} 对它们一律失败 —— <b>哪怕数值上是整数（1.0）也拒</b>。
 * 所以这里拒绝所有 {@link Double}/{@link Float}/{@link BigDecimal}，
 * 而不是「有小数部分才拒」。
 *
 * <p><b>⚠️ 这个类不比较大小、也不做零值归一化</b>：它只回答「这个 JSON 标量是不是一个
 * 合法的 {@code ,string} int64」。范围校验、是否必填，都不在这里。
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/message/message-api/src/main/java/com/rag/message/message/api/dto/GoStyleStringInt64.java}
 * §1.11 第 241 行 · N:1（2 份副本合一）
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
public final class StringInt64 {

    private StringInt64() {
        // 工具类，禁止 new
    }

    /**
     * 解析一个 {@code ,string} int64 请求字段。
     *
     * @param raw      Jackson 交过来的原始值（{@code String} / 数值类型 / {@code Boolean} / {@code null}）
     * @param fieldName JSON tag 名，仅用于异常消息，便于排查
     * @return 解析结果；{@code raw == null} 时返回 {@code null}（由必填校验决定后续）
     * @throws IllegalArgumentException 输入不是 Go 侧会接受的 {@code ,string} int64 形态
     */
    public static Long parse(Object raw, String fieldName) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String s) {
            // Go: strconv.ParseInt(s, 10, 64)
            //   - 拒绝 "" / " 12" / "1.5" / "1e2" / 超 long 范围
            //   - 接受 "+123"（Java 的 parseLong 同样接受，两侧一致）
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw invalid(fieldName, s);
            }
        }
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof BigInteger bi) {
            // Go: ParseInt 对超出 int64 的字面量报 range error
            if (bi.bitLength() >= 64) {
                throw invalid(fieldName, bi.toString());
            }
            return bi.longValue();
        }
        if (raw instanceof BigDecimal || raw instanceof Double || raw instanceof Float) {
            // Go: 字面量是 "1.0" / "1.5" / "1e2"，ParseInt 全部失败（1.0 也不例外）
            throw invalid(fieldName, String.valueOf(raw));
        }
        // Boolean / 数组 / 对象：Go 报 "the value in map is not string, but <kind>"
        throw invalid(fieldName, String.valueOf(raw));
    }

    private static IllegalArgumentException invalid(String fieldName, String raw) {
        return new IllegalArgumentException(
                "field \"" + fieldName + "\" is not a valid string-encoded int64: " + raw);
    }
}