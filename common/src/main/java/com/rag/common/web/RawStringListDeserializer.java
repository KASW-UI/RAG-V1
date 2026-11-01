package com.rag.common.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;

/**
 * {@code []string} 字段的「Go 风格」反序列化 —— 修 <b>DRIFT-LIKEAPI-012</b>。
 *
 * <h2>为什么需要它（Go 侧完整因果链，均已读到消费点）</h2>
 *
 * <ol>
 *   <li>{@code go-zero@v1.10.2/core/jsonx/json.go:74-77}
 *       {@code unmarshalUseNumber} 对<b>三个</b> Unmarshal 入口统一调用 {@code decoder.UseNumber()}
 *       ⇒ 一切 JSON 数字都是 {@code json.Number}（{@code type Number string}）；
 *       ⚠️ <b>不是</b> float64、<b>不是</b> int。</li>
 *   <li>{@code core/mapping/unmarshaler.go:228-229} {@code fillSliceValue} 的 type switch 第一条就是
 *       {@code case fmt.Stringer: return setValueFromString(baseKind, ithVal, v.String())}
 *       —— {@code json.Number} <b>实现了 {@code fmt.Stringer}</b>（{@code Number.String()} 返回字面量）
 *       ⇒ 数组里的数字被字符串化<b>接受</b>；</li>
 *   <li>{@code core/mapping/utils.go} {@code convertTypeFromString} 的
 *       {@code case reflect.String: return str, nil} —— <b>原样返回，不解析、不校验</b>
 *       ⇒ {@code 99999999999999999999} 连溢出都不管，就是字面量。</li>
 * </ol>
 *
 * <p>⇒ 完整链路：{@code [1,2]} ⇒ {@code ["1","2"]}；{@code [1.5]} ⇒ {@code ["1.5"]}；
 * {@code [99999999999999999999]} ⇒ {@code ["99999999999999999999"]}。
 *
 * <h2>⚠️ 这是 go-zero 自身的<b>不对称</b>（只看「String 字段拒数字」必然漏掉这一半）</h2>
 * <p>同一个 JSON 数字：进<b>标量</b> String 字段被拒（{@code processFieldPrimitive}），
 * 进 <b>Slice 元素</b>却被收（{@code fillSliceValue} 走的是另一条路）。
 * RAG 侧原先用全局 {@code LogicalType.Textual ← Integer = Fail} 一刀切，
 * 于是<b>把 Slice 元素也一起拒了</b> ⇒ 与 Go 相反。
 *
 * <h2>⚠️ 为什么必须用「原始 token 文本」而不是转成数值再拼回字符串</h2>
 *
 * <p><b>实测过三种方案，只有一种能 100% 复现 Go：</b>
 * <table border="1">
 *   <caption>Jackson 对 JSON 数字到 Object 的行为（实测）</caption>
 *   <tr><th>字面量</th><th>默认（Object）</th><th>+USE_BIG_DECIMAL_FOR_FLOATS</th><th><b>本类 getText()</b></th><th>Go json.Number</th></tr>
 *   <tr><td>{@code 1}</td><td>{@code Integer 1}</td><td>{@code Integer 1}</td><td><b>{@code "1"}</b></td><td>{@code "1"} ✅</td></tr>
 *   <tr><td>{@code 1.5}</td><td>{@code Double 1.5}</td><td>{@code BigDecimal 1.5}</td><td><b>{@code "1.5"}</b></td><td>{@code "1.5"} ✅</td></tr>
 *   <tr><td>{@code 1.0}</td><td>{@code Double 1.0}</td><td>{@code BigDecimal 1.0}</td><td><b>{@code "1.0"}</b></td><td>{@code "1.0"} ✅</td></tr>
 *   <tr><td>{@code 1.50}</td><td>⚠️ {@code Double 1.5}<b>（尾零丢失）</b></td><td>{@code BigDecimal 1.50}</td><td><b>{@code "1.50"}</b></td><td>{@code "1.50"} ✅</td></tr>
 *   <tr><td>{@code 1e3}</td><td>⚠️ {@code Double 1000.0}</td><td>⚠️ {@code BigDecimal 1E+3}</td><td><b>{@code "1e3"}</b></td><td>{@code "1e3"} ✅</td></tr>
 *   <tr><td>{@code -2.5e-7}</td><td>⚠️ {@code Double -2.5E-7}</td><td>⚠️ {@code BigDecimal -2.5E-7}</td><td><b>{@code "-2.5e-7"}</b></td><td>{@code "-2.5e-7"} ✅</td></tr>
 * </table>
 *
 * <p>⚠️ 注意 {@code BigDecimal} 也不行：{@code new BigDecimal("1e3").toString()} 是 {@code "1E+3"}
 * （<b>大写 E、正指数带 {@code +}</b>）⇒ 仍与 Go 的 {@code "1e3"} 不同。
 * <br>⚠️ 而且开启 {@code USE_BIG_DECIMAL_FOR_FLOATS} 是<b>全局</b>开关，会连带改变
 * {@code weight} 等<b>有明确目标类型</b>的 Double 字段 ⇒ 副作用外溢，故<b>不采用</b>。
 *
 * <h2>⚠️ 本类同时承担 013 的 {@code null} 元素语义</h2>
 * <p>实测：{@code p.getText()} 对 {@code VALUE_NULL} 返回<b>字符串 {@code "null"}</b>（不是 Java null）
 * ⇒ 若照抄，会把 {@code [null]} 变成 {@code ["null"]}，<b>直接破坏 DRIFT-LIKEAPI-013</b>
 * （Go 的 {@code unmarshaler.go:158-160} 对 null 是 {@code continue}，交给
 * {@code GoStyleTargetIds#normalize} 处理）。⇒ 本类显式返回 <b>Java null</b>。
 *
 * <h2>⚠️ 其它 token 的处置（与 Go 的分支逐条对应）</h2>
 * <table border="1">
 *   <tr><th>token</th><th>Go 走哪个分支</th><th>本类</th></tr>
 *   <tr><td>数值</td><td>{@code case fmt.Stringer}（Stringer）</td><td>{@code getText()} 原字面量</td></tr>
 *   <tr><td>字符串</td><td>{@code case string}</td><td>{@code getText()}（无引号）</td></tr>
 *   <tr><td>null</td><td>{@code fillSlice} 的 {@code continue}，<b>不进</b> fillSliceValue</td><td><b>Java null</b>，交给 normalize</td></tr>
 *   <tr><td>bool</td><td>{@code default} → {@code errTypeMismatch}（{@code unmarshaler.go:255-261}）</td><td><b>抛错</b></td></tr>
 *   <tr><td>object / array</td><td>{@code case map[string]any} → String 元素 ⇒ {@code errTypeMismatch}</td><td><b>抛错</b></td></tr>
 * </table>
 *
 * <p>抛出的 {@link MismatchedInputException} 会被 Spring MVC 兜成 {@code 200 + 5001}，
 * 与 Go 的「解析失败 ⇒ 5001」同形（错误**文案**两侧都不可观测）。
 *
 * <h2>⚠️ 用在哪些字段</h2>
 * <p>只用于<b>元素为 string 且 Go 侧无 {@code ,string} 修饰</b>的切片
 * —— 当前只有 {@code target_ids}（{@code GetLikeCountReq} / {@code GetLikeStateReq}）。
 * <br>⚠️ <b>不要</b>套到 {@code ,string} 字段上：那些走 {@code StringInt64}，语义不同。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L4 idiomatization：Jackson 3 → Jackson 2 的包名回改</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p>{@code tools.jackson.*} ⇒ {@code com.fasterxml.jackson.*}。
 * <br>⚠️ <b>本类最关键的依赖 = {@code getText()} 对数字返回原始字面量</b>
 * ⇒ <b>已实测（jackson-core-2.19.4.jar 探针）</b>：Jackson 2.19.4 的
 * {@code getText()} 对数字 token 返回<b>原始字面量</b>
 * （{@code 1.50 → "1.50"}、{@code 1e3 → "1e3"}、{@code -2.5e-7 → "-2.5e-7"}）
 * ⇒ 与 Jackson 3 同语义 ⇒ 上表的「本类 getText()」列在 Jackson 2 下<b>逐格成立</b>。
 * ⇒ ⚠️ 这是<b>版本相关</b>的事实（Boot 3.5.11 管理 jackson-bom <b>2.19.4</b>）
 * ⇒ 已由真触发测试 {@code RawStringListDeserializerTest} 钉住（换 Jackson 大版本时必红）。
 *
 * <p><b>其余 API 逐条 javap 实测同名同参存在</b>：
 * {@code JsonParser.currentToken()} · {@code JsonParser.getText()} ·
 * {@code JsonToken.VALUE_*} · {@code DeserializationContext} ·
 * {@code StdDeserializer<List<String>>}（构造器 {@code StdDeserializer(Class<?>)}）·
 * {@code MismatchedInputException.from(JsonParser, Class<?>, String)}。
 *
 * <p><b>抄写基准</b>：RAG {@code service/like/like-api/src/main/java/com/rag/like/like/api/dto/GoStyleRawStringListDeserializer.java}
 * 整段抄（L2 · D13）＋ <b>Jackson 3 → 2 的包名回改</b>（L4）。
 */
public class RawStringListDeserializer extends StdDeserializer<List<String>> {

    public RawStringListDeserializer() {
        super(List.class);
    }

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        List<String> out = new ArrayList<>();
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            // 整个字段显式 null ⇒ Go 侧 mapValue==nil 且无 tag ⇒ 记为空列表（与「缺席」同形）
            return out;
        }
        if (p.currentToken() != JsonToken.START_ARRAY) {
            // ⚠️ Jackson 没有 DeserializationException；「类型不匹配」用 MismatchedInputException
            //    （其构造器是 protected，必须走静态工厂 from(...)）
            throw MismatchedInputException.from(p, List.class, "target_ids 必须是 JSON 数组");
        }
        while (p.nextToken() != JsonToken.END_ARRAY) {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                // ⚠️ 必须是 Java null，不能是 "null" —— 见类注释「本类同时承担 013 的 null 语义」
                out.add(null);
            } else if (token == JsonToken.VALUE_STRING
                    || token == JsonToken.VALUE_NUMBER_INT
                    || token == JsonToken.VALUE_NUMBER_FLOAT) {
                // 数值走 getText() 拿**原始字面量**，这是能 100% 复现 Go json.Number 的唯一方式
                out.add(p.getText());
            } else {
                // bool / object / array：Go 侧一律 errTypeMismatch（unmarshaler.go:239-261）
                throw MismatchedInputException.from(p, List.class,
                        "target_ids 的元素类型必须是字符串或数字，收到 " + token);
            }
        }
        return out;
    }
}
