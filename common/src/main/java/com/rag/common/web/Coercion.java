package com.rag.common.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.cfg.MutableCoercionConfig;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;

/**
 * E-3 根因 **R1** 的修复规则：关掉 Jackson 的标量强转与浮点截断，对齐 go-zero 的解析层。
 *
 * <p><b>2026-10-22</b>。此前 22 个 api 端点的请求 DTO 全部走 Jackson 默认的宽松解析，
 * 而 Go 侧是 go-zero 的 {@code core/mapping} —— 两者对「JSON 标量塞进非标量字段」的
 * 判定完全相反。
 *
 * <p><b>Go 的真实判定（逐条实测，真进程 18889，不是推断）</b>：
 * <pre>
 *   String 字段：{"username":1}    → 400  type mismatch for field "username", expect "string", actual "number"
 *                {"username":1.5}  → 400  type mismatch for field "username", expect "string", actual "number"
 *                {"username":true} → 400  type mismatch for field "username"
 *                {"username":null} → 400  field "username" mustn't be nil
 *                {"username":[]}   → 400  type mismatch for field "username"
 *
 *   int64 字段：{"uid":1}     → 200（接受！）
 *              {"uid":"1"}   → 200（接受！先字符串化再 strconv.ParseInt）
 *              {"uid":1.0}   → 400  strconv.ParseInt: parsing "1.0": invalid syntax
 *              {"uid":1.5}   → 400  strconv.ParseInt: parsing "1.5": invalid syntax
 *              {"uid":true}  → 400  the value in map is not string, but bool
 *              {"uid":null}  → 400  field "uid" mustn't be nil
 * </pre>
 *
 * <p><b>由此得出两条规则</b>：
 * <ol>
 *   <li><b>数字 / 布尔不能进 String 字段</b> ⇒ {@link CoercionInputShape#Integer} /
 *       {@link CoercionInputShape#Float} / {@link CoercionInputShape#Boolean} 对
 *       {@link LogicalType#Textual} 一律 {@link CoercionAction#Fail}。</li>
 *   <li><b>浮点不能进整数字段，哪怕整数部分完整</b> ⇒ 关掉
 *       {@link DeserializationFeature#ACCEPT_FLOAT_AS_INT}。
 *       注意 {@code 1.0} 也必须拒：Go 侧字符串化后是 {@code "1.0"}，
 *       {@code strconv.ParseInt("1.0")} 直接失败 —— 所以这条<b>不是</b>
 *       「小数部分非零才拒」。</li>
 * </ol>
 *
 * <p><b>刻意不动的两条</b>：
 * <ul>
 *   <li><b>String → 整数字段保持接受</b>（{@code {"uid":"1"}}）。Go 侧
 *       {@code processNamedFieldWithValueFromString} 是「先 {@code convertToString}
 *       再 {@code strconv.ParseInt}」，字符串反而是它的正规入口。
 *       关掉它会把「两边都接受」的输入改成「只有一边接受」，是把 R1 修成新漂移。</li>
 *   <li><b>Array / Object → 任何字段</b>：Jackson 默认已经 Fail，无需配置。</li>
 * </ul>
 *
 * <p><b>失败后的响应形状</b>：Jackson 抛 {@code MismatchedInputException} →
 * Spring 包成 {@code HttpMessageNotReadableException} → api 层的
 * {@code handleUnreadableBody} → <b>HTTP 400 + text/plain</b>，
 * 与 Go 的 {@code http.Error(..., 400)} 同形状。
 * 文案不同属 §3.4.4 DRIFT-006 / §3.4.3 N-6 已确立的「形状对齐、文本不对齐」边界。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L4 idiomatization：Jackson 3（{@code tools.jackson.*}）→ Jackson 2（{@code com.fasterxml.jackson.*}）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p>RAG 侧用的是 Jackson 3（Boot 4.0.8 的 {@code spring-boot-starter-jackson} 带的是
 * {@code tools.jackson.core:jackson-databind:3.x}）；本仓按 **D14 全项目 = Jackson 2 + Boot 3.5**
 * ⇒ 全部换成 {@code com.fasterxml.jackson.*}。
 *
 * <p><b>⚠️ 本类的「回改」量＝0：Jackson 2 的 API 形状与 Jackson 3 完全同构</b>（逐条 javap 实测
 * {@code jackson-databind-2.19.4.jar}）：
 * <table border="1">
 *   <tr><th>能力</th><th>Jackson 3（RAG）</th><th>Jackson 2（本仓）</th></tr>
 *   <tr><td>builder 上挂 coercion</td>
 *       <td>{@code MapperBuilder.withCoercionConfig(LogicalType, Consumer&lt;CoercionConfig&gt;)}</td>
 *       <td><b>同名同参</b>：{@code withCoercionConfig(LogicalType, Consumer&lt;MutableCoercionConfig&gt;)}</td></tr>
 *   <tr><td>逐 shape 设 action</td>
 *       <td>{@code CoercionConfig.setCoercion(shape, action)}</td>
 *       <td><b>同名同参</b>：{@code MutableCoercionConfig.setCoercion(shape, action)}</td></tr>
 *   <tr><td>shape 枚举</td>
 *       <td>{@code CoercionInputShape.Integer/Float/Boolean}</td>
 *       <td><b>同名同值</b></td></tr>
 *   <tr><td>logical type</td>
 *       <td>{@code LogicalType.Textual}</td>
 *       <td><b>同名同值</b></td></tr>
 *   <tr><td>action</td>
 *       <td>{@code CoercionAction.Fail}</td>
 *       <td><b>同名同值</b></td></tr>
 *   <tr><td>浮点截断开关</td>
 *       <td>{@code DeserializationFeature.ACCEPT_FLOAT_AS_INT}</td>
 *       <td><b>同名同值</b></td></tr>
 * </table>
 * ⇒ <b>语义等价论证</b>：本类只依赖上表 6 项能力，逐项 API 同构且枚举取值一致
 * ⇒ 施加到 mapper 上后的<b>可观察行为</b>（哪类输入被拒 / 被接受）不变。
 * ⇒ <b>已由真触发测试钉住</b>：{@code common/src/test/java/com/rag/common/web/CoercionTest.java}
 * 用<b>真实</b> {@code JsonMapper} 跑「数字/布尔→String 拒」「浮点→Long 拒」「字符串→Long 接受」
 * 三组用例（不是 Mockito 桩）。
 *
 * <p><b>⚠️ 一处 Jackson 3 → 2 的<b>能力差异</b>（刻意不利用）</b>：Jackson 2 的
 * {@code MapperBuilder} <b>也有</b> {@code withCoercionConfig(Class&lt;?&gt;, Consumer&lt;MutableCoercionConfig&gt;)}
 * （Jackson 3 也有，但 RAG 实测「类级配置在 LogicalType 级配置存在时**完全不生效**」）。
 * <br>⇒ 本类<b>刻意只用 LogicalType 级</b>、<b>不</b>用类级豁免 ⇒ 规则保持<b>全局生效</b>
 * ⇒ 与 RAG 侧（以及 Go 侧的 go-zero 解析层）<b>同形</b>。
 * ⇒ 若日后有人加类级豁免 ⇒ 会把「两边都拒」的输入改成「一边收」⇒ 是新漂移，不是修好。
 *
 * <p><b>⚠️ 为什么这里是纯静态工具、不是 {@code @Configuration}</b>：
 * {@code common/} 是 10 个域<b>共用</b>的 starter；把 {@code @Configuration} +
 * {@code JsonMapperBuilderCustomizer} 塞进本类，等于给<b>纯 gRPC 进程</b>
 * （各域 rpc 侧）强加一条它不需要的 servlet 依赖。
 * 改成「本类只出规则、各域 api 侧（或 starter 的 auto-config）注册 customizer」，
 * 本类只需要 Jackson databind。
 *
 * <p><b>抄写基准</b>：RAG {@code service/users/users-common/src/main/java/com/rag/users/common/http/GoStyleCoercion.java}
 * 整段抄（L2 业务语义层以 RAG 为基准 · D13）＋ <b>Jackson 3 → 2 的包名回改</b>（L4 机制层）。
 */
public final class Coercion {

    private Coercion() {
    }

    /**
     * 把两条对齐规则施加到给定的 <b>Jackson 2</b> mapper builder 上。
     *
     * <p>调用方约定：只在 <b>api 层</b>（各域的 MVC mapper）上调用。
     * gRPC 用 protobuf marshaller、MyBatis 与 {@code JsonTextTypeHandler} 各持自己的
     * Jackson 2 mapper 实例，都与 go-zero 的 http 解析层无关，不该被这两条规则波及。
     *
     * @param builder Spring MVC 使用的 {@link JsonMapper.Builder}
     */
    public static void applyTo(JsonMapper.Builder builder) {
        // 规则 2：1.0 / 1.5 进 Long / int 一律拒（Go：strconv.ParseInt("1.0") 失败）
        builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

        // 规则 1：number / boolean 进 String 一律拒（Go：type mismatch）
        builder.withCoercionConfig(LogicalType.Textual, Coercion::applyTextualCoercions);
    }

    /**
     * 把同样两条规则施加到<b>已构建好的</b> Jackson 2 mapper 上。
     *
     * <p><b>为什么需要这条重载</b>（L4 · D3 starter 化 · D14 工具链回退）：
     * <ul>
     *   <li><b>Spring 6.2 的 {@code Jackson2ObjectMapperBuilder}</b> <b>没有</b> coercion API
     *       （javap 实测：只有 {@code featuresToDisable} / {@code featuresToEnable} /
     *       {@code modulesToInstall} 等，<b>无</b> {@code withCoercionConfig}）
     *       ⇒ <b>builder 路线在 Boot 3.5 上走不通</b>；</li>
     *   <li>但 {@code Jackson2ObjectMapperBuilder#postConfigurer(Consumer&lt;ObjectMapper&gt;)}
     *       <b>存在</b>（javap 实测）⇒ ⇒ 可以在 builder 构建完之后对 mapper 施加规则
     *       ⇒ ⇒ 这就是「postConfigurer 路线」。</li>
     *   <li>关键前提：{@code ObjectMapper#coercionConfigFor(LogicalType)} 返回的是<b>活对象</b>
     *       （探针实测：两次调用返回同一实例；就地 {@code setCoercion} 后 mapper 行为改变）
     *       ⇒ ⇒ 就地改它就等于改 mapper 的行为。</li>
     * </ul>
     *
     * <p><b>⚠️ 为什么走 postConfigurer 而不是「自己 new 一个 mapper 替换 Boot 的」</b>：
     * 替换 ⇒ 各域 {@code spring.jackson.*}（时区 / 命名策略 / 序列化开关）的绑定全部失效
     * ⇒ 那是把「加两条规则」变成「换掉整个 mapper」⇒ 是行为变更，不是等价改造。
     *
     * <p><b>与 {@link #applyTo(JsonMapper.Builder)} 的等价性</b>：两条路径最终都落到
     * <b>同一个</b> {@link #applyTextualCoercions(MutableCoercionConfig)} ⇒ 规则逐条同源
     * ⇒ ⇒ 不会出现「builder 路线与 mapper 路线规则不一致」的隐性分叉。
     */
    public static void applyTo(ObjectMapper mapper) {
        if (mapper == null) {
            return;
        }
        // 规则 2
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        // 规则 1
        applyTextualCoercions(mapper.coercionConfigFor(LogicalType.Textual));
    }

    /**
     * 规则 1 的<b>唯一实现点</b>（builder 路线与 mapper 路线共用）⇒ 两条路径不可能分叉。
     */
    private static void applyTextualCoercions(MutableCoercionConfig cfg) {
        cfg.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }
}
