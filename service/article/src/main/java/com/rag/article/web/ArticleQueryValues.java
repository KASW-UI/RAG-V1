package com.rag.article.web;

import com.rag.common.web.BadRequestException;

/**
 * <p><b>职责</b>：<b>query string 的输入解释规则</b> —— Go {@code httpx.ParseForm} 的 Java 等价物。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/form/GoFormValues.java}
 * （类声明 {@code :72} · {@code raw} {@code :83-88} · {@code first} {@code :133-144}
 * ＋ {@code bool} {@code :154-168} · {@code int32} {@code :177-193} · {@code text} {@code :201-204}）
 * ⇒ <b>1:1 照抄</b>（异常类型除外，见下）。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>落点</b>：B 侧在 {@code article-api/…/form/} 包 ⇒ 本仓落进域内 {@code com.rag.article.web}
 *             （D11 域内 {@code dto/<业务对象>/} 的同款归位思路）；</li>
 *         <li><b>异常类型</b>：B 侧的 {@code GoStyleBadRequestException} ⇒ 共享层的
 *             {@link BadRequestException}（B 侧那个类的 C 侧落点，D3 上收）⇒
 *             <b>由 {@code ArticleApiExceptionHandler} 映成 400 + text/plain</b>，与 B 侧同形；</li>
 *         <li><b>类名</b>：{@code GoFormValues} → {@code ArticleQueryValues}
 *             （避免与共享层同名概念混淆，且标明归属域）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>五条规则的判定条件与<b>错误文案逐字</b>照抄 ⇒ <b>响应 body 逐字节相同</b>；</li>
 *         <li>异常类型换了但<b>最终 wire 形状不变</b>（400 + text/plain + nosniff）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🔴 域内副本 ⇒ <b>待收口</b>（global lane）</h2>
 * <p><b>共享层 {@code common/web/GetFormValuesFilter.java} <b>顶不上</b></b>：
 * 它只有<b>上限闸门</b>（{@code goParseViolation} / {@code writeGoStyleBadRequest}），
 * <b>没有</b> {@code first} / {@code bool} / {@code int32} / {@code text} 这套<b>取值真值表</b>
 * ⇒ ⇒ 本类是<b>域内副本</b>。
 * <p>⇒ <b>收口建议</b>（属 {@code common/} 写权 · global lane）：把本类提升到
 * {@code common/web/} 并公开 ⇒ 本域删副本改 import。
 * <p>⚠️ <b>本副本与共享层那份<b>不允许分叉</b></b>：一旦共享层改了文案而本域没改，
 * 同一个 {@code ?page=abc} 在不同域会产出不同的 body ⇒ L1 漂移。
 * <p>⚠️ <b>映射表缺陷（主会话统一修）</b>：{@code docs/Mapping_Table/article/article.md:69}
 * 把 B 侧<b>两个不同的类</b>（{@code article-api/…/form/GoFormValues} 与
 * {@code service/common/…/http/GoStyleFormValues}）合成了一个 ⇒ 那条映射是错的。
 *
 * <h2>为什么必须自己写（不能交给 Spring 的默认转换）</h2>
 * <p>Go 侧 {@code GET /v1/article/:id} 与 {@code GET /v1/articles} 的 {@code form:} 字段走的是
 * {@code httpx/requests.go:27-31} 那个 Unmarshaler：
 * <pre>
 *   formUnmarshaler = mapping.NewUnmarshaler(formKey,
 *       mapping.WithStringValues(),      // ← 标量按字符串解释
 *       mapping.WithOpaqueKeys(),
 *       mapping.WithFromArray())         // ← 单元素数组拆成标量
 *   formValues = httpx.GetFormValues(r)  // ← requests.go:22-65
 * </pre>
 * 两条规则的组合，Spring 的默认转换<b>都不满足</b>：
 *
 * <h3>规则 1：空值被<b>整个丢掉</b>（不是报错，也不是「空串」）</h3>
 * <pre>
 *   httpx/util.go:44-45
 *       for _, v := range values {
 *           if len(v) == 0 { continue }        // ← ?incr_view= 与 ?incr_view 都命中这里
 *       }
 *       ...
 *       if len(filtered) > 0 { params[name] = filtered }   // ← 值为空的 key 根本不进 map
 * </pre>
 * ⇒ {@code ?incr_view=} 与 {@code ?incr_view} 与<b>完全不传</b>三者<b>完全等价</b>：
 * 都取 Go 零值 {@code false}，<b>不报错</b>。
 * Spring 的 {@code @RequestParam(defaultValue="false") boolean} 会先尝试把 {@code ""}
 * 转 boolean 而<b>抛</b> {@code MethodArgumentTypeMismatchException}（400）——
 * 这是凭空多出一条 Go 没有的拒绝路径。
 *
 * <h3>规则 2：bool 的真值表是 {@code 1/0}，不是 Spring 的 {@code true/false}</h3>
 * <pre>
 *   core/mapping/utils.go  convertTypeFromString
 *       case reflect.Bool:
 *           if str == "1" || strings.EqualFold(str, "true") { return true, nil }
 *           if str == "0" || strings.EqualFold(str, "false") { return false, nil }
 *           return false, errTypeMismatch
 * </pre>
 * ⚠️ 这里<b>不是</b> {@code strconv.ParseBool}（那会额外接受 {@code "t"/"T"/"f"/"F"}）。
 * 对照 Spring 的 {@code StringToBooleanConverter}：只认 {@code "true"}/{@code "false"}
 * （{@code Boolean.valueOf} 语义），<b>拒绝 {@code "1"}</b>。
 * ⇒ {@code ?incr_view=1} 在 Go 是 {@code true}，在 Spring 默认绑定是 400。
 *
 * <h3>规则 3：整数是 {@code strconv.ParseInt(str, 10, bits)}</h3>
 * <pre>
 *       case reflect.Int32: return strconv.ParseInt(str, 10, 32)
 * </pre>
 * Spring 的 {@code StringToNumber} 内部走 {@code NumberFormat.parse} /
 * {@code Integer.decode}，两者与 Go 都不同：
 * <ul>
 *   <li>{@code Integer.decode("010")} = <b>8</b>（八进制），Go 的 base-10 解析 = <b>10</b>；</li>
 *   <li>{@code Integer.decode("0x10")} = <b>16</b>，Go 直接<b>报错</b>（400）。</li>
 * </ul>
 *
 * <h2>⚠️ 本类<b>不</b>做「值域校验」</h2>
 * <p>{@code page=-1} / {@code page_size=99999} 在 Go 侧同样只是原样透传给下游 ⇒
 * 本层提前拦会与下游重复，且可能不一致。
 */
public final class ArticleQueryValues {

    private ArticleQueryValues() {
    }

    /**
     * Go {@code GetFormValues} 的「空值丢弃」规则。
     *
     * <p><b>B 出处</b>：{@code .../form/GoFormValues.java:83-88}。
     *
     * @return {@code null} 表示「这个 key 在 Go 侧<b>不存在</b>」，
     *         调用方据此走默认值分支
     */
    public static String raw(String queryValue) {
        if (queryValue == null || queryValue.isEmpty()) {
            return null;
        }
        return queryValue;
    }

    /**
     * Go 的「同名 key 多值 ⇒ <b>取第一个</b>」规则。
     *
     * <h2>Go 出处（两段，缺一不可）</h2>
     * <pre>
     * // ① rest/httpx/util.go:35-62 —— 同名 key 的多个值收成 []string 存进 map
     *   for name, values := range r.Form {
     *       filtered := make([]string, 0, len(values))
     *       for _, v := range values {
     *           if len(v) == 0 { continue }        // ← 空值先被**整个丢掉**
     *           filtered = append(filtered, v)
     *       }
     *       if len(filtered) > 0 { params[name] = filtered }   // ← 值存的是**切片**
     *   }
     *
     * // ② core/mapping/unmarshaler.go:835-846（消费点）—— 目标不是 slice 时取 Index(0)
     *   if u.opts.fromArray {
     *       fieldKind := field.Type.Kind()
     *       if fieldKind != reflect.Slice &amp;&amp; fieldKind != reflect.Array {
     *           valueKind := reflect.TypeOf(mapValue).Kind()
     *           if valueKind == reflect.Slice || valueKind == reflect.Array {
     *               val := reflect.ValueOf(mapValue)
     *               if val.Len() > 0 { mapValue = val.Index(0).Interface() }   // ← **取第一个**
     *           }
     *       }
     *   }
     * </pre>
     * ⇒ {@code ?page=1&page=2} ⇒ Go 得 {@code page=1}（HTTP 200），<b>不是</b> 400。
     *
     * <h2>⚠️ 顺序不能反：先丢空值，再取第一个</h2>
     * <p>Go 的两步是<b>先过滤后取首</b>，所以 {@code ?page=&page=2} 落进 map 的是
     * {@code ["2"]}（空的 {@code ""} 在第 ① 步就被 {@code continue} 掉了）⇒ Go 得 {@code 2}。
     * <p>若先取第一个再过滤，{@code ?page=&page=2} 会得到 {@code null}（走默认值）——
     * 与 Go 不同。
     *
     * <h2>⚠️ 为什么 Spring 的默认转换不满足</h2>
     * <p>Spring 的 {@code @RequestParam String} 拿到多值时会走转换服务把它拼成一个字符串
     * （形如 {@code "1,2"}）⇒ 本域的 {@link #int32} 会 {@code parseInt("1,2")} 抛 ⇒ <b>400</b>。
     * <p>⇒ controller 的这些参数必须声明成 {@code String[]}，再由本方法按 Go 规则取首。
     *
     * <p><b>B 出处</b>：{@code .../form/GoFormValues.java:133-144}。
     *
     * @param values Spring 解析出的多值数组（可能为 {@code null} = 参数缺席）
     * @return 第一个<b>非空</b>值；没有非空值时返回 {@code null}（= Go 侧该 key 不进 map）
     */
    public static String first(String[] values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            // Go: util.go:44-45 —— len(v) == 0 的值先被丢掉
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /**
     * Go {@code convertTypeFromString} 的 {@code reflect.Bool} 分支。
     *
     * <p><b>B 出处</b>：{@code .../form/GoFormValues.java:154-168}。
     *
     * @param rawValue    Spring {@code @RequestParam} 拿到的原始值（可能为 {@code null} 或 {@code ""}）
     * @param fieldName   字段名，只用于错误文案（对齐 Go 的 {@code unmarshal field %q ...}）
     * @param defaultValue Go tag 上的 {@code ,default=...}；无 default 时传 Go 零值 {@code false}
     * @throws BadRequestException 值既不是 1/true 也不是 0/false ⇒ 400 text/plain
     */
    public static boolean bool(String rawValue, String fieldName, boolean defaultValue) {
        String v = raw(rawValue);
        if (v == null) {
            return defaultValue;
        }
        if ("1".equals(v) || "true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("0".equals(v) || "false".equalsIgnoreCase(v)) {
            return false;
        }
        // Go: convertTypeFromString → errTypeMismatch → unmarshaler 包成
        //     `type mismatch for field %q`（newTypeMismatchError）
        throw new BadRequestException("type mismatch for field \"" + fieldName + "\"");
    }

    /**
     * Go {@code strconv.ParseInt(str, 10, 32)}（{@code ListArticlesReq.page/page_size}）。
     *
     * <p>⚠️ 刻意<b>不</b>用 {@code Integer.decode} / {@code Integer.parseInt}：
     * 前者接受八进制与 {@code 0x} 前缀，后者的错误文案与 Go 不同。
     * 见类注释「规则 3」。
     *
     * <p><b>B 出处</b>：{@code .../form/GoFormValues.java:177-193}。
     *
     * @throws BadRequestException 非法数字 ⇒ 400 text/plain
     */
    public static int int32(String rawValue, String fieldName, int defaultValue) {
        String v = raw(rawValue);
        if (v == null) {
            return defaultValue;
        }
        try {
            // Go: strconv.ParseInt(str, 10, 32) —— 位宽 32，越界即 error
            return Integer.parseInt(v, 10);
        } catch (NumberFormatException e) {
            // Go 的报错文案（strconv.NumError）形如：
            //   strconv.ParseInt: parsing "abc": invalid syntax
            // 这里不逐字复刻（属 Go 标准库文案，非本组件契约），但保留字段名，
            // 便于与 Go 日志对照。
            throw new BadRequestException(
                    "strconv.ParseInt: parsing \"" + v + "\": invalid syntax (field \"" + fieldName + "\")");
        }
    }

    /**
     * String 字段：<b>原样透传</b>，不 trim、不校验。
     *
     * <p>Go 侧 {@code convertTypeFromString} 的 {@code reflect.String} 分支就是
     * {@code return str, nil}；空白串是<b>合法值</b>（空串与缺失等价，见规则 1）。
     *
     * <p><b>B 出处</b>：{@code .../form/GoFormValues.java:201-204}。
     */
    public static String text(String rawValue, String defaultValue) {
        String v = raw(rawValue);
        return v == null ? defaultValue : v;
    }
}
