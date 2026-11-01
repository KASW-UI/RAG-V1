package com.rag.common.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.io.IOException;

/**
 * {@code float64} 字段的「B 侧 风格」反序列化 —— 修 <b>DRIFT-LIKEAPI-016</b>。
 *
 * <h2>B 侧的判据（已读到消费点）</h2>
 * <pre>
 * // B 出处：service/like/like-api/src/main/java/com/rag/like/like/api/dto/GoStyleFloat64Deserializer.java:14:684-687
 * case reflect.Float32:
 *     fValue, err := v.Float64()      // v 是 json.Number
 *     if err != nil { return err }    // ← 1e400 在这里被拒
 * </pre>
 * {@code json.Number("1e400").Float64()} 返回 {@code (+Inf, ErrRange)} ⇒ **解析失败** ⇒
 * 整个请求 5001，<b>不发出任何 RPC</b>。
 *
 * <p>⚠️ 而 Jackson 对溢出字面量<b>不报错</b>：{@code "weight":1e400} 直接得到
 * {@code Double.POSITIVE_INFINITY} ⇒ 会真的发出一条 {@code weight=+Inf} 的写调用。
 * <br>⚠️ {@code weight} 随即被 {@code (float)} 窄化到 f32，{@code +Inf} 原样带进 proto
 * ⇒ 下游任何按权重加权的计算都会被污染。
 *
 * <h2>为什么用原始 token 文本而不是 {@code Double} 入参</h2>
 * <p>与 {@link RawStringListDeserializer} 同理由：改成 {@code Double} 入参后
 * Jackson 已经把 {@code 1e400} 变成 {@code Infinity}，<b>无从区分</b>「字面量越界」与
 * 「本来就是无穷」。⚠️ 而且 {@code ,optional} 字段一旦声明成 {@code Double}，
 * 原先那条 {@code LogicalType.Float ← String = Fail} 的 coercion 规则会
 * <b>因为目标类型不再是 Float 而失效</b> ⇒ 必须由本类自己拒绝字符串。
 *
 * <h2>逐条对齐</h2>
 * <table border="1">
 *   <tr><th>输入</th><th>B 侧</th><th>本类</th></tr>
 *   <tr><td>{@code 1.5}</td><td>{@code 1.5}</td><td>✅ {@code 1.5}</td></tr>
 *   <tr><td>{@code 1e39}</td><td>✅ 接受（f64 合法；weight 是 float64，<b>不</b>触发 f32 的 OverflowFloat）</td>
 *       <td>✅ 接受（{@code Double.parseDouble("1e39")} = 1e39，finite）</td></tr>
 *   <tr><td>{@code 1e400}</td><td>❌ {@code ErrRange} ⇒ 解析失败</td><td>❌ <b>非 finite ⇒ 抛错</b></td></tr>
 *   <tr><td>{@code -1e400}</td><td>❌ 同上</td><td>❌ 同上</td></tr>
 *   <tr><td>{@code "1.5"}（字符串）</td><td>❌ type mismatch</td><td>❌ 抛错（不能靠 coercion，见上）</td></tr>
 *   <tr><td>缺席 / 显式 null</td><td>✅ 合法（{@code ,optional}）</td><td>✅ 保持 null</td></tr>
 * </table>
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L4 idiomatization：Jackson 3 → Jackson 2 的包名回改</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p>{@code tools.jackson.core.*} / {@code tools.jackson.databind.*} ⇒
 * {@code com.fasterxml.jackson.core.*} / {@code com.fasterxml.jackson.databind.*}。
 * <br>⚠️ <b>逐条 javap 实测（{@code jackson-databind-2.19.4.jar} / {@code jackson-core-2.19.4.jar}）：
 * 本类用到的 5 个 API 在 Jackson 2 下全部同名同参存在</b>：
 * <ul>
 *   <li>{@code JsonParser.currentToken()} / {@code getText()} —— ✅ 同名；</li>
 *   <li>{@code JsonToken.VALUE_NULL / VALUE_NUMBER_INT / VALUE_NUMBER_FLOAT} —— ✅ 同名同值；</li>
 *   <li>{@code DeserializationContext} —— ✅ 同名同包路径；</li>
 *   <li>{@code StdDeserializer<Double>} —— ✅ {@code com.fasterxml.jackson.databind.deser.std.StdDeserializer}
 *       ＋ 构造器 {@code StdDeserializer(Class<?>)} 同名；</li>
 *   <li>{@code MismatchedInputException.from(JsonParser, Class<?>, String)} —— ✅
 *       {@code com.fasterxml.jackson.databind.exc.MismatchedInputException} 有<b>同名同参</b>的静态工厂。</li>
 * </ul>
 *
 * <p><b>⚠️ 一处必须实测的语义点：{@code getText()} 对数字 token 返回什么</b>
 * ⇒ RAG 侧（Jackson 3）依赖「{@code getText()} 返回<b>原始字面量</b>」——
 * 例如 {@code 1e400} 必须拿得到 {@code "1e400"} 字面量才能判越界，
 * 而不能先变成 {@code Infinity} 再判断。
 * ⇒ <b>已实测（{@code /tmp 探针 · jackson-core-2.19.4.jar}）</b>：Jackson 2.19.4 的
 * {@code getText()} 对 {@code VALUE_NUMBER_FLOAT} 返回<b>原始字面量</b>
 * （{@code 1.50 → "1.50"}、{@code 1e3 → "1e3"}、{@code 1e400 → "1e400"}）
 * ⇒ <b>与 Jackson 3 同语义</b> ⇒ 本类的判据可原样成立。
 * ⇒ ⚠️ 这是<b>版本相关</b>的事实（Boot 3.5.11 管理 jackson-bom <b>2.19.4</b>）⇒
 * 已由真触发测试 {@code Float64DeserializerTest} 钉住（换 Jackson 大版本时必红）。
 *
 * <p><b>抄写基准</b>：RAG {@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GoStyleFloat64Deserializer.java}
 * 整段抄（L2 · D13）＋ <b>Jackson 3 → 2 的包名回改</b>（L4）。
 */
public class Float64Deserializer extends StdDeserializer<Double> {

    public Float64Deserializer() {
        super(Double.class);
    }

    @Override
    public Double deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            // ,optional：显式 null 合法
            return null;
        }
        if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
            // bool / 字符串 / object / array —— B 侧一律 type mismatch
            throw MismatchedInputException.from(p, Double.class,
                    "浮点字段只接受 JSON 数字，收到 " + token);
        }

        String literal = p.getText();
        // ⚠️ Double.parseDouble("1e400") 返回 +Inf 且**不抛异常** —— 必须显式查 finite
        double parsed;
        try {
            parsed = Double.parseDouble(literal);
        } catch (NumberFormatException e) {
            throw MismatchedInputException.from(p, Double.class,
                    "无法解析为 float64: " + literal);
        }
        if (!Double.isFinite(parsed)) {
            // 对齐 B 侧: json.Number("1e400").Float64() ⇒ (+Inf, ErrRange) ⇒ return err
            throw MismatchedInputException.from(p, Double.class,
                    "parsing " + literal + " as float64: value out of range");
        }
        return parsed;
    }
}
