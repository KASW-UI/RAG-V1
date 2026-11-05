package com.rag.task.dto.task;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>请求体</b> —— 对应 Go {@code api/internal/types/types.go:52-54}：
 *
 * <pre>
 *   type GetTaskReq struct {
 *       Userid UserIDParam `json:"id"`
 *   }
 * </pre>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>可变 POJO → immutable record</b>（D5 ＋ <b>A9</b>「DTO 必须 immutable」）⇒
 *             B 侧是「字段 + 两个 setter」的类，本类是单组件 record；</li>
 *         <li><b>组件类型 {@code Object} 而非 {@code String}</b>：见下方「为什么组件是 Object」
 *             —— 这不是风格选择，而是<b>让 Jackson 不做标量强转</b>的唯一写法；</li>
 *         <li><b>归一化从 setter 搬进了 record 的 compact 构造器</b> ⇒ 时机不变（仍在
 *             「反序列化时」），但落点由 Jackson 的写入入口变成 canonical constructor；</li>
 *         <li>包名压平（{@code com.rag.task.task.api.dto} → {@code com.rag.task.dto.task}，D7 ＋ D11）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 两段判据（① 反序列化时归一化 / ② 取值时校验）、
 *       两条失败文案、{@code TrimSpace}、{@code strconv.ParseInt(v,10,64)} 的判据、
 *       「数字保留字面量文本」这条关键约束，<b>全部照抄</b>（逐条见下）。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/dto/GetTaskReq.java:67（类声明）
 * B 出处：…/dto/GetTaskReq.java:71（USER_ID_INVALID 文案）
 * B 出处：…/dto/GetTaskReq.java:74（ID_MUST_BE_STRING_OR_INTEGER 文案）
 * B 出处：…/dto/GetTaskReq.java:82-83（id 字段的默认值 ""）
 * B 出处：…/dto/GetTaskReq.java:95-98（setId(Object) = Go 的 UnmarshalJSON 入口）
 * B 出处：…/dto/GetTaskReq.java:116-135（userIdOrThrow = Go 的 Int64()）
 * B 出处：…/dto/GetTaskReq.java:139-151（normalize = Go types.go:16-36）
 * B 出处：…/dto/GetTaskReq.java:161-166（numberLiteral = Go types.go:29-33）
 * B 出处：…/dto/GetTaskReq.java:177-184（BadIdTypeException = Go types.go:35）
 *       </pre></li>
 * </ol>
 *
 * <h2>本类承载「{@code UserIDParam}」的两段逻辑，不只是一个字符串字段</h2>
 *
 * <p>Go 侧 {@code UserIDParam}（{@code types.go:14}）是一个 {@code string} 命名类型，
 * 带两个方法，<b>两段都必须搬</b>，且它们的<b>报错时机不同</b>：
 * <table border="1">
 *   <caption>UnmarshalJSON 与 Int64 的分工</caption>
 *   <tr><th>段</th><th>Go 出处</th><th>判据</th><th>失败文案</th></tr>
 *   <tr>
 *     <td>① 反序列化</td>
 *     <td>{@code types.go:16-36} {@code UnmarshalJSON}</td>
 *     <td>空 / {@code null} → {@code ""}；先试 string（<b>TrimSpace</b>），
 *         再试 json.Number（<b>保留字面量文本</b>），都不成 → 报错</td>
 *     <td>{@code "id must be a string or integer"}</td>
 *   </tr>
 *   <tr>
 *     <td>② 取值</td>
 *     <td>{@code types.go:38-50} {@code Int64}</td>
 *     <td>再 TrimSpace → 空则报错；{@code strconv.ParseInt(v,10,64)}；
 *         失败或 {@code <=0} → 报错</td>
 *     <td>{@code "user_id invalid"}</td>
 *   </tr>
 * </table>
 *
 * <h2>⚠️ 为什么①必须发生在「反序列化时」而不是延迟到取值时</h2>
 * Go 的 {@code UnmarshalJSON} 返回 error 会让 {@code decoder.Decode(&req)} 整段失败
 * （{@code gettaskhandler.go:21-24}）⇒ 报文是 {@code 400} +
 * {@code "id must be a string or integer\n"}。
 * <p>若把①拖到 {@link #userIdOrThrow()} 里做，{@code {"id":true}} 会变成
 * {@code "user_id invalid"} —— <b>状态码相同、文案不同</b>，仍是可观察漂移。
 * ⇒ 本类在 <b>canonical 构造器</b>里就完成①并抛异常，
 * 由 {@code advice/TaskApiExceptionHandler} 接住。
 *
 * <h2>🔴 为什么组件类型是 {@code Object}（而不是 {@code String}）</h2>
 * Go 的 {@code UnmarshalJSON} 拿到的是<b>原始 JSON token</b>，两种形态都要试。
 * <p>若组件声明成 {@code String}，Jackson 会先做<b>标量强转</b>：
 * {@code {"id":123}} 被静默转成 {@code "123"}（<b>合法</b>），
 * {@code {"id":true}} 被静默转成 {@code "true"}（<b>也合法</b>）⇒
 * <b>永远不会抛 {@link BadIdTypeException}</b> ⇒ Go 侧那条 400 分支在 C 侧<b>不可达</b>
 * ⇒ <b>方向相反的漂移</b>（Go 400「id must be a string or integer」/ C 200）。
 * <p>声明成 {@code Object} 让 Jackson <b>不做任何强转</b>，
 * 由本类完全接管判定（与 B 侧 {@code setId(Object)} 的意图逐字一致）。
 *
 * <h2>⚠️ 为什么「数字」要保留文本而不是直接 {@code longValue()}</h2>
 * Go 侧存的是 json.Number 的<b>字面量文本</b>（{@code types.go:31} {@code numeric.String()}），
 * 浮点写法则会把 {@code "1.5"} 原样存下，交给②的 {@code ParseInt} 拒掉。
 * 若这里直接 {@code longValue()}，{@code {"id":1.5}} 会变成 uid=1 ⇒
 * <b>凭空多出一批成功响应</b>。
 * ⇒ 数字一律存 {@code String.valueOf(...)}：整型得到原样数字，浮点型得到
 * {@code "1.5"} / {@code "1.0E20"} 这类<b>必定带 '.' 或 'E'} 的文本 ⇒ ②必然拒。
 * 两段合起来的可观察结果与 Go 一致，且不需要在①里特判「是不是浮点」。
 *
 * <h2>⚠️ 未知字段被忽略（不是本类的功劳，是必须<b>不</b>去配的东西）</h2>
 * Go 的 handler 用裸 {@code json.NewDecoder(r.Body)}（{@code gettaskhandler.go:19-20}），
 * <b>没有</b> {@code DisallowUnknownFields} ⇒ {@code {"id":"1","zzz":2}} 在 Go 侧成功。
 * Boot 的 Jackson 默认也是忽略，两者一致；此处<b>不</b>加
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} —— 共享层
 * {@code com.rag.common.web.Coercion} 施加的是「标量强转」规则，
 * <b>不</b>改「未知字段」策略 ⇒ 两侧逐条一致。
 */
public record GetTaskReq(@JsonProperty("id") Object id) {

    /** Go {@code types.go:41,46} 的错误文案（②的唯一文案）。 */
    public static final String USER_ID_INVALID = "user_id invalid";

    /** Go {@code types.go:35} 的错误文案（①的唯一文案）。 */
    public static final String ID_MUST_BE_STRING_OR_INTEGER = "id must be a string or integer";

    /**
     * canonical 构造器 —— <b>①的反序列化入口</b>（对应 Go {@code types.go:16-36} 的
     * {@code UnmarshalJSON}）。
     *
     * <p>⚠️ record 的 compact 构造器就是 canonical 构造器 ⇒ Jackson 反射调用它时
     * <b>拿到的就是原始 JSON 值</b>（{@code String} / {@code Integer} / {@code Double} /
     * {@code Boolean} / {@code Map} / {@code List} / {@code null}）⇒ 判定时机与 Go 一致。
     *
     * @param raw 原始 JSON 值
     * @throws BadIdTypeException 既不是字符串也不是数字 —— Go {@code types.go:35}
     */
    public GetTaskReq {
        // ⚠️ record 的 compact 构造器里，隐式参数的**名字就是组件名**（{@code id}），
        //    拿不到第二个名字 ⇒ 归一化的入参就是组件本身（此刻仍是**原始 JSON 值**）。
        id = normalize(id);
    }

    /**
     * 归一化后的 {@code id} 文本；{@code null} / 缺席 / {@code "null"} 字面量都落成 {@code ""}。
     *
     * <p>本类所有对外读取都走这个方法 ⇒ <b>类型是 {@code String}</b>，
     * 而 record 组件声明为 {@code Object} 只为「让 Jackson 不强转」。
     *
     * @return 归一化文本（永不为 {@code null}）
     */
    public String idText() {
        return id instanceof String s ? s : "";
    }

    /**
     * 取 uid —— 对应 Go {@code types.go:38-50} 的 {@code Int64()}。
     *
     * <p>三条失败路径（空 / 非法字面量 / {@code <=0}）<b>共用同一个文案</b>
     * {@code "user_id invalid"}，这是 Go 的原样行为，<b>不要</b>为了排障把它们拆成不同文案。
     *
     * @return 正整数 uid
     * @throws IllegalArgumentException 任一失败路径；由 advice 映成 400 text/plain
     */
    public long userIdOrThrow() {
        String value = idText().trim();            // Go types.go:39 的 TrimSpace
        if (value.isEmpty()) {                     // Go types.go:40-42
            throw new IllegalArgumentException(USER_ID_INVALID);
        }

        final long uid;
        try {
            // Go strconv.ParseInt(v, 10, 64)：
            //   接受可选的 '+'；拒绝 "" / " 12"(已 trim) / "1.5" / "1.0E20" / 超 long 范围。
            //   Java Long.parseLong 的这几条判据与之一致（含 '+'），不需额外处理。
            uid = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(USER_ID_INVALID); // Go types.go:45-46
        }
        if (uid <= 0) {
            throw new IllegalArgumentException(USER_ID_INVALID); // Go types.go:45-47
        }
        return uid;
    }

    // ============== ① 的实现 ==============

    private static Object normalize(Object raw) {
        if (raw == null) {
            return "";                              // Go types.go:18-21：null → ""
        }
        if (raw instanceof String s) {
            return s.trim();                        // Go types.go:24-27
        }
        if (raw instanceof Number n) {
            return numberLiteral(n);                // Go types.go:29-33
        }
        // Boolean / 数组 / 对象：Go types.go:35
        throw new BadIdTypeException(ID_MUST_BE_STRING_OR_INTEGER);
    }

    /**
     * 把一个 JSON 数字还原成 Go 侧的<b>字面量文本</b>。
     *
     * <p>整型给原样数字；浮点给 {@code String.valueOf} 的结果，<b>必定</b>带
     * {@code '.'} 或 {@code 'E'}（Java 的 {@code Double.toString} 对非整数绝不会省略小数点），
     * 于是 {@link #userIdOrThrow()} 的 {@code ParseInt} 必然拒绝 —— 与 Go 对
     * {@code 1.5} / {@code 1.0} / {@code 1e2} 的处理逐条一致。
     */
    private static String numberLiteral(Number n) {
        if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long) {
            return String.valueOf(n.longValue());
        }
        return String.valueOf(n); // BigInteger / BigDecimal / Double / Float
    }

    /**
     * 「{@code id} 既不是字符串也不是数字」—— 对应 Go {@code types.go:35} 的
     * {@code errors.New("id must be a string or integer")}。
     *
     * <p>⚠️ <b>必须是独立类型，不能用 {@code IllegalArgumentException}</b>：
     * advice 靠这个类型在 {@code HttpMessageNotReadableException} 的 cause 链里
     * 找回 Go 的原文（Jackson 会把构造器抛出的异常包一层，
     * 直接用 {@code e.getMessage()} 会拿到 "JSON parse error: ..." 那种包装文案）。
     */
    public static class BadIdTypeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public BadIdTypeException(String message) {
            super(message);
        }
    }
}
