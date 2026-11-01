package com.rag.common.web;

import com.rag.common.web.BadRequestException;

/**
 * JSON 入参里 String 字段的<b>输入解释规则</b> —— 对应 go-zero
 * {@code core/mapping/unmarshaler.go} 的 {@code processFieldPrimitive} 类型判定。
 *
 * <h2>为什么需要它（不是「防御性校验」）</h2>
 *
 * <p>article 域的 String 字段有两种 tag 形态：
 * <pre>
 *   types.go: Title   string `json:"title"`              ← 无 option ⇒ FromString = false
 *   types.go: Status  int32  `json:"status,optional"`
 * </pre>
 * 对<b>无 {@code ,string} 的 String 字段</b>，Go 走
 * {@code processFieldPrimitive}（{@code unmarshaler.go:643-662}）：
 * <pre>
 *   typeKind  := baseType.Kind()                       // String
 *   valueKind := reflect.TypeOf(mapValue).Kind()       // JSON 数字 ⇒ Int
 *   if typeKind != valueKind { return newTypeMismatchError(fullName) }
 * </pre>
 * ⇒ {@code {"title":123}} 在 Go 侧是 <b>400</b>（{@code type mismatch for field "title"}）。
 *
 * <h3>⚠️ 实测：不能只靠 {@code GoStyleCoercion}</h3>
 *
 * <p>共享的 {@code GoStyleCoercion} 规则 1 是
 * {@code LogicalType.Textual + Integer/Float/Boolean → Fail}，
 * 看着正好覆盖这件事，<b>但它对本模块的 DTO 完全不生效</b>：
 * Jackson 的 coercion 规则要作用在<b>声明的目标类型</b>上，
 * 而 {@code CreateArticleReq} / {@code UpdateArticleReq} 的 setter 形参刻意是
 * {@link Object}（为了区分「缺席」与「显式 null」）。
 * 形参是 {@code Object} ⇒ 没有目标类型 ⇒ 规则<b>没有落脚点</b>。
 *
 * <p>实测证据：只挂 coercion 不加本类时，{@code {"title":123}} 被 <b>成功</b> 绑定，
 * 随后 {@code String.valueOf(123)} 得到 {@code "123"}，
 * 请求以 {@code 200 + code=200} 通过 —— 一个 Go 侧拒绝、RAG 侧接受的 L2 漂移。
 *
 * <h3>为什么形参必须是 {@link Object}</h3>
 * <p>Jackson 对「key 缺席」与「显式 {@code null}」给的都是 {@code null}，
 * <b>声明成 {@code String} 就无法区分这两者</b>，而 Go 侧这两条路径的<b>报错文案不同</b>
 * （{@code is not set} vs {@code mustn't be nil}）。
 * 「不声明目标类型、让 Jackson 原样递出」是唯一能同时做到
 * 「两种都收」与「区分两者」的写法。
 *
 * <p>⇒ 代价就是类型判定必须自己补，即本类。
 * <b>共享层（users-common / com.rag.common）一行不动</b> ——
 * 改共享规则会连带影响已验证的 users / message 域行为。
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/GoStyleStringFields.java}
 * §1.11 第 245 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
final class StringFields {

    private StringFields() {
    }

    /**
     * @param fieldName Go tag 里的字段名（用于错误文案，<b>用 tag 名而不是 Java 字段名</b>）
     * @param raw       Jackson 递出的原始值：{@code null}（缺席或显式 null）、
     *                  {@link String}（正常），或<b>其它类型</b>（类型不符）
     * @return {@code null} 或原样字符串
     * @throws BadRequestException 类型不符 —— 对齐 Go 的 {@code newTypeMismatchError}
     */
    static String parse(String fieldName, Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String s) {
            return s;
        }
        // DRIFT-006（已修）：Go 的文案是三段式，逐字对齐
        //   实测：type mismatch for field "title", expect "string", actual "number"
        //   依据 core/mapping/unmarshaler.go:1202-1204 newTypeMismatchError(name)
        //        → fmt.Errorf("type mismatch for field %q, expect %q, actual %q", name, baseType, mapValue)
        //   ⚠️ expect 取**目标类型**（String），actual 取**输入值的 Go 类型**（number/bool…）。
        //   上一版只输出前两段，状态码与 Content-Type 对但 body 少一截。
        throw new BadRequestException(
                "type mismatch for field \"" + fieldName + "\", expect \"string\", actual \""
                        + goTypeName(raw) + "\"");
    }

    /** Go 侧 {@code reflect.TypeOf(mapValue).String()} 用的类型名（jsonx/core/jsonx 的 json.Number 特例已并入）。 */
    private static String goTypeName(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short
                || raw instanceof Byte || raw instanceof java.math.BigInteger) {
            return "number";
        }
        if (raw instanceof Float || raw instanceof Double) {
            return "number";
        }
        if (raw instanceof Boolean) {
            return "bool";
        }
        return raw.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }
}