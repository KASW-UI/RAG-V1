package com.rag.common.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/**
 * {@code map[string]string} 的<b>元素级</b>解析器 —— 对齐 go-zero {@code generateMap} 的判据。
 *
 * <p><b>为什么必须单独一个反序列化器，而不是在 {@link Coercion} 里加一条规则</b>：
 * Go 对「map 的值」与「顶层标量字段」用的是<b>两段不同的代码</b>，判据正好相反。
 *
 * <pre>
 *   顶层标量字段 → processFieldPrimitiveWithJSONNumber
 *                  （core/mapping/unmarshaler.go:664-715）
 *                  switch typeKind 无 reflect.Bool 分支 ⇒ default ⇒ 一律 type mismatch
 *
 *   map 的值     → generateMap 的 default 分支
 *                  （core/mapping/unmarshaler.go:385-417）
 *                    case json.Number → setValueFromString(...) ⇒ **数字收下并字符串化**
 *                    case bool       → 要求 elem kind == Bool   ⇒ 对 string map 报错
 *                    default(nil/…)  → 要求 elem kind == 值 kind ⇒ Invalid ≠ String ⇒ 报错
 * </pre>
 *
 * ⚠️ <b>Javadoc 里的行号指向消费点，不是声明点</b>（AGENTS.md §3.3.1）：
 * {@code unmarshaler.go} 里 {@code generateMap} 有多处出现，上面这组行号是
 * <b>处理 map 元素赋值</b>的那一处，不是函数声明处。
 *
 * <p><b>为什么不能靠 LogicalType 级的 coercion 配置</b>：
 * {@link Coercion} 补强 1 依赖 {@code LogicalType.Textual + Integer/Float → Fail}
 * 来对齐<b>顶层 String 字段</b>；而 map 的值同样声明为 {@code String}，
 * 命中<b>同一条</b>规则 ⇒ 若想放行 map 元素上的数字，就得把顶层 String 字段一起放行，
 * 那会把 {@code {"content":123}} 重新变成 ACCEPTED（DRIFT-MSGAPI-002 的回退）。
 * 两个目标类型在 Jackson 的 LogicalType 维度上<b>无法区分</b>。
 * <br>
 * {@link Coercion} 的类注释还记录了另一条路也不通：Jackson 3 的
 * <b>类级</b> coercion 配置在这条路径上实测<b>完全不生效</b>（见那里的最小复现实验）。
 * ⇒ 唯一可靠的挂点是<b>字段上的 {@code contentUsing}</b>，即本类。
 *
 * <p><b>三条行为，逐条对应 Go 的一行</b>：
 * <ol>
 *   <li><b>字符串</b> ⇒ 原样（Go：{@code case string} 分支要求 elem kind == String）；</li>
 *   <li><b>数字（整/浮）</b> ⇒ <b>保留字面量</b>的字符串形式（Go：{@code case json.Number}
 *       → {@code setValueFromString(String, …, v.String())}，{@code v.String()} 就是
 *       <b>原始字面量</b>，所以 {@code 1.0} 落成 {@code "1.0"}、{@code 1e2} 落成 {@code "1e2"}，
 *       <b>不是</b> {@code "1"}）；</li>
 *   <li><b>null / bool / 数组 / 对象</b> ⇒ 一律拒绝（Go 落在
 *       {@code default} 分支，elem kind 对不上 ⇒ {@code errTypeMismatch}）。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么必须同时覆写 {@link #getNullValue}</b>（这条最容易漏）：
 * 反序列化器实现了 {@code NullValueProvider}，map 元素为 {@code null} 时
 * Jackson <b>不会</b>走 {@code deserialize}，而是走 {@code getNullValue} ——
 * 它的默认实现返回 {@code null}，于是 {@code {"extra":{"k":null}}} 被静默放行。
 * 这正是 DRIFT-MSGAPI-007 的成因；只覆写 {@code deserialize} 的话本条<b>不会</b>被修掉。
 *
 * <p><b>作用范围</b>：只在声明了本反序列化器的字段上生效（各域仅
 * {@code AdminSendNotificationReq.extra}），
 * <b>不影响字段级的 {@code extra: null}</b>——那是 {@code ,optional} 的另一条路径，
 * Go 侧接受（DRIFT-MSGAPI-003）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L4 idiomatization：基类从 Jackson 3 的 {@code ValueDeserializer} 换成 Jackson 2 的 {@code JsonDeserializer}</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p>RAG（Jackson 3）：{@code extends tools.jackson.databind.ValueDeserializer<String>}。
 * <br>本仓（Jackson 2）：<b>{@code jackson-databind-2.19.4.jar} 里根本没有 {@code ValueDeserializer} 这个类</b>
 * （javap 实测：该包下只有 {@code JsonDeserializer} / {@code KeyDeserializer} 两个顶层反序列化器基类）
 * ⇒ 落到 Jackson 2 的 {@code com.fasterxml.jackson.databind.JsonDeserializer<String>}。
 *
 * <p><b>语义等价论证</b>（逐条 javap 实测 jackson-databind-2.19.4.jar）：
 * <table border="1">
 *   <tr><th>能力</th><th>Jackson 3（RAG）</th><th>Jackson 2（本仓）</th></tr>
 *   <tr><td>值反序列化器基类</td><td>{@code ValueDeserializer<T>}</td>
 *       <td>{@code JsonDeserializer<T>}（<b>唯一</b>对应物）</td></tr>
 *   <tr><td>map 元素的 null 钩子</td><td>{@code getNullValue(DeserializationContext)}</td>
 *       <td><b>同名同参</b>（{@code JsonDeserializer implements NullValueProvider}）</td></tr>
 *   <tr><td>类型不匹配</td><td>{@code ctxt.handleUnexpectedToken(Class, JsonParser)}</td>
 *       <td><b>同名同参</b>（javap 确认 {@code handleUnexpectedToken(java.lang.Class&lt;?&gt;, JsonParser)} 存在）</td></tr>
 *   <tr><td>输入不匹配上报</td><td>{@code ctxt.reportInputMismatch(Class, String, Object...)}</td>
 *       <td><b>同名同参</b>（javap 确认存在）</td></tr>
 *   <tr><td>数字取原始字面量</td><td>{@code p.getText()}</td>
 *       <td><b>同名同语义</b>（实测返回原始字面量，见下）</td></tr>
 * </table>
 * ⇒ <b>「map 元素为 null ⇒ 拒绝」这条行为在两侧由同一条机制承载</b>
 * （Jackson 的 map 元素路径遇 null 会调 {@code NullValueProvider.getNullValue}，不会走 {@code deserialize}）
 * ⇒ 行为等价。
 *
 * <p><b>⚠️ 一处签名差异（编译期可见，非行为差）</b>：Jackson 2 的
 * {@code getNullValue(DeserializationContext)} 返回 {@code T}（此处 = {@code String}），
 * 而 Jackson 3 的 {@code ValueDeserializer#getNullValue} 返回 {@code Object}
 * ⇒ 本类的覆写签名按 Jackson 2 写（返回 {@code String}）⇒ 上游仍可正常编译。
 *
 * <p><b>⚠️ 一处语义依赖必须实测</b>：{@code p.getText()} 对数字 token 必须返回<b>原始字面量</b>
 * （Go 的 {@code json.Number.String()} 就是字面量）
 * ⇒ <b>已实测（jackson-core-2.19.4.jar 探针）</b>：{@code 1.0 → "1.0"}、{@code 1e2 → "1e2"}
 * ⇒ 与 Jackson 3 同语义 ⇒ 「{@code 1.0} 落成 {@code "1.0"}、不是 {@code "1"}」这条成立。
 *
 * <p><b>抄写基准</b>：RAG {@code service/message/message-api/src/main/java/com/rag/message/message/api/config/GoStyleMapStringValueDeserializer.java}
 * 整段抄（L2 · D13）＋ <b>基类 Jackson 3 → 2 的替换</b>（L4）。
 */
public final class MapStringValueDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        // Go: case string ⇒ 原样赋值
        if (token == JsonToken.VALUE_STRING) {
            return p.getText();
        }
        // Go: case json.Number ⇒ setValueFromString(elem, v.String())
        // ⚠️ 用 getText() 取**原始字面量**，与 Go 的 json.Number.String() 同义；
        //    用 getDoubleValue()/longValue() 会把 1.0 规范化成 "1.0" 之外的形态。
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return p.getText();
        }
        // Go: default 分支 ⇒ dereffedElemKind != keythValue.Kind() ⇒ errTypeMismatch
        //      （bool / 数组 / 对象都落这里）
        return (String) ctxt.handleUnexpectedToken(String.class, p);
    }

    /**
     * map 元素为显式 {@code null} 时<b>拒绝</b>。
     *
     * <p>Go 出处：{@code core/mapping/unmarshaler.go} 的 {@code generateMap}，
     * {@code default} 分支里 {@code dereffedElemKind}（String）与
     * {@code reflect.ValueOf(nil).Kind()}（Invalid）不等 ⇒ 返回 {@code errTypeMismatch}。
     *
     * <p>⚠️ 与「整个 map 为 null」<b>不同</b>：{@code extra: null} 命中
     * {@code processNamedFieldWithValue} 的 {@code if mapValue == nil { if optional { return nil } }}，
     * Go <b>接受</b>。两条路径分开，别把本方法挂到字段级。
     */
    @Override
    public String getNullValue(DeserializationContext ctxt) throws JsonMappingException {
        // ⚠️ 这里不能调 handleUnexpectedToken(…, p)：该路径下 Jackson **没有**给 parser，
        //    传 null 会 NPE。用 reportInputMismatch —— 它只需要目标类型与说明文本。
        return ctxt.reportInputMismatch(String.class,
                "map[string]string 的元素收显式 null：go-zero 在 generateMap 里报 type mismatch");
    }
}
