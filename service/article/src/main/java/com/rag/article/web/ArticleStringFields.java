package com.rag.article.web;

import com.rag.common.web.BadRequestException;

import java.util.Locale;

/**
 * <p><b>Go 风格的 JSON 字符串字段解析</b> —— 对应 B 侧
 * {@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/GoStyleStringFields.java}
 * （整段照抄 · 注释剥离后逐行相同）。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改落点与可见性</b> ——
 *       ① B 侧它是 {@code dto} 包下的<b>包私有</b>工具类（{@code final class GoStyleStringFields}，
 *       静态方法也是包私有）⇒ C 侧落进域内 {@code web/} 包并改为 {@code public}
 *       （DTO 在 {@code dto/article/} 与 {@code dto/upload/} 两个子包 ⇒ 必须跨包可见）；
 *       ② 异常类型由 B 侧的 {@code GoStyleBadRequestException} 改为共享层的
 *       {@link BadRequestException}（B 侧那个类的 C 侧落点，D3 上收）；
 *       ③ 补 B 出处行号。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 判定条件（null 放行 / {@code String} 放行 / 其余一律拒）、
 *       <b>错误文案逐字节</b>（{@code type mismatch for field "title", expect "string", actual "number"}）、
 *       以及「Go 类型名」的判定表（整数/浮点 ⇒ {@code number}，布尔 ⇒ {@code bool}，
 *       其余取类名小写）全部逐条照抄 ⇒ <b>响应 body 逐字节相同</b>。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/article/article-api/src/main/java/com/rag/article/article/api/dto/GoStyleStringFields.java:6-22（parse 本体）
 * B 出处：…/dto/GoStyleStringFields.java:23-35（goTypeName）
 *       </pre></li>
 * </ol>
 *
 * <h2>🔴 为什么共享层的同名类<b>顶不上</b>（⇒ 域内副本 ⇒ <b>待收口</b>）</h2>
 * <p>共享层 {@code common/web/StringFields.java} 的实现与本类<b>逐行相同</b>，
 * 但它是<b>包私有</b>的（实测编译产物：
 * {@code final class com.rag.common.web.StringFields} / {@code static String parse(...)}）
 * ⇒ <b>域内 DTO 调不到</b> ⇒ ⇒ 本类是<b>域内副本</b>。
 * <p>⇒ <b>收口建议</b>（属 {@code common/} 写权 · global lane）：
 * 把 {@code common/web/StringFields} 改成 {@code public} 并把 {@code parse} 改成 {@code public static}
 * ⇒ 本域删副本改 import。
 * <p>⚠️ <b>本副本与共享层那份<b>不允许分叉</b></b>：一旦共享层改了文案而本域没改，
 * 同一个 {@code {"title":123}} 在不同域会产出不同的 body ⇒ L1 漂移。
 *
 * <h2>⚠️ 为什么 DTO 必须是「{@code Object} 入参 + 静态工厂」的形态</h2>
 * <p>B 侧的 DTO 是<b>可变类 + setter</b>，setter 形参声明成 {@code Object} ⇒ 由本类判定类型。
 * <p>C 侧 DTO 层是 <b>immutable record</b>（D5 + A9）⇒ 没有 setter ⇒ 必须用
 * {@link com.fasterxml.jackson.annotation.JsonCreator} 静态工厂把 {@code Object} 转成 {@code String}。
 * <p>⚠️ <b>若把 record 组件直接声明成 {@code String}</b> ⇒ Jackson 会自己做强转
 * ⇒ 共享层的 {@code Coercion} 规则（Textual ⇒ Fail）会把它拒成
 * {@code MismatchedInputException} ⇒ <b>状态码仍是 400</b>（与 B 侧一致）
 * 但 <b>body 文案不同</b>（Jackson 的措辞 vs Go 的 {@code type mismatch for field ...}）
 * ⇒ ⇒ 必须走本类 ⇒ 才是<b>逐字节</b>一致。
 *
 * <h2>⚠️ 与「必填」的分工</h2>
 * <p>本类只判<b>类型</b>，不判<b>缺席</b>：缺席与显式 {@code null} 都得到 {@code null}
 * ⇒ 交给 Bean Validation 的 {@code @NotNull}（A10 禁用手写校验器）
 * ⇒ 产出 {@code MethodArgumentNotValidException} ⇒ 由
 * {@code ArticleApiExceptionHandler} 映成 <b>400 + text/plain</b>（与 B 侧同状态码）。
 */
public final class ArticleStringFields {

    private ArticleStringFields() {
    }

    /**
     * Go 风格的字符串字段解析 —— 判定条件与错误文案逐条照抄 B 侧。
     *
     * @param fieldName 字段名（写进错误文案）
     * @param raw       Jackson 交来的原始值（{@code null} / {@code String} / 其它类型）
     * @return {@code null} 或原样 {@code String}
     * @throws BadRequestException 值不是字符串时抛出（⇒ 400 + text/plain）
     */
    public static String parse(String fieldName, Object raw) {
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

    /**
     * 输入值的 <b>Go 类型名</b>（判据表逐条照抄 B 侧）。
     */
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
        return raw.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }
}
