package com.rag.message.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.rag.common.web.Coercion;
import com.rag.message.interceptor.MessageAdminJwtAuthInterceptor;
import com.rag.message.interceptor.MessageJwtAuthInterceptor;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>message 域的 Spring MVC 装配点</b> —— 挂两条鉴权路径 + 注册 go-zero 对齐的 Jackson 解析规则。
 *
 * <p><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/config/WebMvcConfig.java}
 * （B 侧类 javadoc {@code :11-53} · 两个前缀常量 {@code :57-61} · 构造器 {@code :63-70} ·
 * {@code goStyleCoercionCustomizer :72-76} · {@code addInterceptors :78-86}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>两处 API 回改（Boot 4 → Boot 3.5 · D14）</b>：
 *     <ul>
 *       <li>B 侧的 {@code org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer}
 *           （Boot 4 / Jackson 3 专有）⇒ 本仓改用 Boot 3.5 的
 *           {@link Jackson2ObjectMapperBuilderCustomizer}；</li>
 *       <li>B 侧的 {@code com.rag.message.message.api.config.MessageGoStyleCoercion}
 *           （本域私有的 Jackson 3 副本）⇒ 本仓的<b>共享两条</b>规则改用共享层
 *           {@link Coercion}（{@code com.rag.common.web.Coercion} · D3 上收 · Jackson 2 写法），
 *           <b>message 域自己的 4 条补强</b>就地落在本类的私有方法里（见
 *           {@link #applyMessageDomainCoercions(ObjectMapper)}）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *     <ul>
 *       <li>拦截器注册的<b>两组路径</b>逐条一致（见下方路径表）；</li>
 *       <li>Jackson 侧：共享 2 条 ＋ message 4 条 = B 侧 {@code MessageGoStyleCoercion} 的<b>同一套</b>
 *           ⇒ 「哪些输入被拒 / 被接受」的判定口径不变。</li>
 *     </ul></li>
 *   <li><b>B 出处</b>：同上（另：B 侧的规则实现类
 *       {@code service/message/message-api/src/main/java/com/rag/message/message/api/config/MessageGoStyleCoercion.java}）。</li>
 * </ol>
 *
 * <p><b>路径注册与 Go 端两段 {@code AddRoutes} 逐条对应</b>：
 * <table border="1">
 *   <caption>拦截器注册</caption>
 *   <tr><th>拦截器</th><th>addPathPatterns</th><th>excludePathPatterns</th></tr>
 *   <tr>
 *     <td>{@link MessageJwtAuthInterceptor}</td>
 *     <td>{@code /message/v1/**}</td>
 *     <td><b>{@code /message/v1/admin/**}</b> —— 必须 exclude，否则两把钥匙都会验一遍</td>
 *   </tr>
 *   <tr>
 *     <td>{@link MessageAdminJwtAuthInterceptor}</td>
 *     <td>{@code /message/v1/admin/**}</td>
 *     <td>—</td>
 *   </tr>
 * </table>
 *
 * <p><b>exclude 是必需的，不是防御性冗余</b>：Go 侧两段路由表各自带一把 key，
 * admin 端点只被 admin key 验签。若 Java 侧 user 拦截器也覆盖 admin 前缀，
 * 拿 admin token 打 admin 端点会先被 user key 拒掉 —— 凭空多出 401。
 *
 * <p><b>注意 Go 侧不存在 public 路径</b>：message api 的 13 个端点<b>全部</b>要求 JWT
 * （两段 {@code AddRoutes} 都带 {@code rest.WithJwt}），所以这里没有 user-api 那种
 * 「public 路径直接放行」的分支。
 *
 * <p><b>只动 MVC 这一个 mapper</b>：gRPC 用 protobuf marshaller，不走 Jackson。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** Go 侧 admin 路由组的前缀（{@code rest.WithPrefix("/message/v1/admin")}）。 */
    private static final String ADMIN_PREFIX = "/message/v1/admin/**";

    /** Go 侧 user 路由组的前缀（{@code rest.WithPrefix("/message/v1")}）。 */
    private static final String USER_PREFIX = "/message/v1/**";

    private final MessageJwtAuthInterceptor messageJwtAuthInterceptor;
    private final MessageAdminJwtAuthInterceptor messageAdminJwtAuthInterceptor;

    public WebMvcConfig(MessageJwtAuthInterceptor messageJwtAuthInterceptor,
                        MessageAdminJwtAuthInterceptor messageAdminJwtAuthInterceptor) {
        this.messageJwtAuthInterceptor = messageJwtAuthInterceptor;
        this.messageAdminJwtAuthInterceptor = messageAdminJwtAuthInterceptor;
    }

    /**
     * E-3 根因 R1：把 go-zero 风格的解析规则施加到<b>本模块 MVC</b> 的 Jackson 2 mapper 上。
     *
     * <p><b>为什么是 {@code postConfigurer} 而不是改 converter 上的 ObjectMapper</b>：
     * Boot 3.5 的 {@code Jackson2ObjectMapperBuilder} <b>没有</b> coercion API
     * （只有 {@code featuresToDisable} / {@code featuresToEnable} 等）⇒ builder 路线走不通；
     * 但 {@code Jackson2ObjectMapperBuilder#postConfigurer(Consumer<ObjectMapper>)} <b>存在</b>
     * ⇒ ⇒ 可以在 builder 构建完之后对 mapper 施加规则。
     * （与 C 侧 admin 域 {@code com.rag.admin.config.WebMvcConfig} 同一手法。）
     *
     * <p>⚠️ <b>共享层也已施加共享那两条</b>
     * （{@code JsonBodyConverterAutoConfiguration#seaCoercionMapperCustomizer} → 同样调
     * {@code Coercion::applyTo}）⇒ ⇒ 共享部分<b>幂等</b>（同一份规则施加两次 = 施加一次）
     * ⇒ ⇒ 本 bean 的额外作用是「message 域自己的 4 条补强」。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer goStyleCoercionCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            // 共享的两条：number/bool 不进 String 字段 · float 不进整数字段
            Coercion.applyTo(mapper);
            // message 域自己的 4 条补强（B 侧 MessageGoStyleCoercion）
            applyMessageDomainCoercions(mapper);
        });
    }

    /**
     * <b>message 域自己的 4 条解析规则</b>（B 侧
     * {@code service/message/message-api/src/main/java/com/rag/message/message/api/config/MessageGoStyleCoercion.java}
     * 整段照抄，只做 Jackson 3 → Jackson 2 的 API 回改）。
     *
     * <p><b>为什么必须在本域叠加、而不是改共享层的 {@code Coercion}</b>：
     * 共享层 {@code Coercion} 的第 3 条豁免「<b>String → 整数字段保持接受</b>」
     * 是 <b>users 域按真源实测得出的结论</b>（{@code {"uid":"1"}} 在 Go 侧接受）。
     * 那条豁免对 users 成立，对 message <b>不成立</b> ⇒ ⇒ 改共享规则会连带把
     * user-api / admin-api 的已验证行为改坏 ⇒ ⇒ 本域只叠加 message 自己的规则。
     *
     * <p><b>补强 1：无 {@code ,string} 的整数字段<b>拒绝</b> JSON 字符串</b>
     * <p>Go 出处（消费点）：{@code core/mapping/unmarshaler.go:643-662} 的
     * {@code processFieldPrimitive}：{@code typeKind != valueKind} ⇒ {@code type mismatch}。
     * 实测（v1.10.2 真源探针）：{@code {"offset":"10"} → "type mismatch for field \"offset\""}。
     * <p>⚠️ <b>为什么 Jackson 默认会放行</b>：Jackson 2/3 默认允许标量强转，
     * {@code {"offset":"10"}} 会安静地变成 10。
     *
     * <p><b>补强 2：无 {@code ,string} 的布尔字段<b>拒绝</b> JSON 字符串</b>
     * <p>同一条 {@code processFieldPrimitive} 路径：{@code {"unread_only":"true"}} →
     * {@code "type mismatch for field \"unread_only\""}（实测）。
     *
     * <p><b>补强 3：JSON <b>数字</b>也不进布尔字段</b>
     * <p>Go 的 {@code encoding/json} 对 number → bool 是<b>报错</b>
     * （{@code json: cannot unmarshal number into Go struct field ... of type bool}），
     * 而 Jackson 默认<b>接受</b>并静默转换（1 ⇒ true、0 ⇒ false）。
     * ⚠️ <b>为什么这条是「投放事故级」的</b>：message 域的
     * {@code AdminSendNotificationReq.broadcast}（Go: {@code Broadcast bool json:"broadcast,optional"}）
     * 决定「定向通知」还是「<b>全量广播</b>」——
     * 一条本意为定向的请求写成 {@code {"broadcast":1}} ⇒ Java 侧静默变成 true ⇒
     * <b>单播变全站广播</b>，而 Go 侧会直接拒绝这条请求。
     *
     * <p><b>⚠️ 本仓实测（2026-11-02 · Jackson 2.19.4 · 真 ObjectMapper 探针）</b>：
     * 本方法施加后，6 组用例的判定<b>逐条与 Go 侧一致</b>：
     * <pre>
     * {"offset":"10"}                  → REJECTED（MismatchedInputException）  ← 补强 1 生效
     * {"unread_only":"true"}            → REJECTED（MismatchedInputException）  ← 补强 2 生效
     * {"broadcast":1}                  → REJECTED（InvalidFormatException）    ← 补强 3 生效
     * {"title":123}                    → REJECTED（InvalidFormatException）    ← 共享规则 1 生效
     * {"offset":null}                  → ACCEPTED（offset=0）                 ← 补强 4 生效
     * {"offset":10,"unread_only":true}  → ACCEPTED
     * </pre>
     * ⇒ ⇒ 「规则写在代码里」≠「规则生效」这条纪律（AGENTS.md §3.3.3）在此<b>有运行证据</b>。
     *
     * <p><b>📌 这 6 组读数现在是「可回归的断言」</b>，不是只写在 javadoc 里的记录 ——
     * 见 {@code service/message/src/test/java/com/rag/message/config/MessageCoercionContractTest.java}
     * （7 例 · 断言的是<b>容器里那个生产 {@code ObjectMapper}</b>，不是另抄一份规则跑一遍）。
     *
     * <p><b>⚠️ 本表第 2 行的字段名是 {@code unread_only}（snake_case），不是 {@code unreadOnly}</b>：
     * C 侧 {@code PageReq} 的 JSON 属性名<b>逐字照抄 B 侧</b>，是 snake_case。
     * ⇒ ⇒ 用 {@code {"unreadOnly":"true"}} 去打 C 侧**命中不了那个布尔字段**
     * （Spring Boot 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=false} ⇒ 未知属性被<b>静默忽略</b>）
     * ⇒ ⇒ 它<b>不能</b>作为「补强 2 生效」的证据。
     * <p>⇒ 📌 **教训**：javadoc 里记的「实测读数」也会随**字段改名**而失效
     * ⇒ ⇒ 探针读数必须有一条<b>可执行断言</b>兜底（AGENTS.md §3.3.2）。
     *
     * <p><b>补强 4：{@code ,optional} 标量字段的<b>显式 null</b>应等于零值</b>
     * <p>Go 出处（消费点）：{@code core/mapping/unmarshaler.go:854-863} 的
     * {@code processNamedFieldWithValue}：{@code if mapValue == nil { if opts.optional() { return nil } }}。
     * ⚠️ Jackson 的 {@code FAIL_ON_NULL_FOR_PRIMITIVES} <b>默认开启</b>
     * ⇒ {@code {"offset":null}} / {@code {"kind":null}} / {@code {"unread_only":null}}
     * 三种形态全部被拒，而 Go 侧一律 ACCEPTED。
     * 📌 <b>为什么在本模块<b>全局</b>关掉是安全的</b>：本模块<b>没有任何必填的原始类型字段</b>
     * （Go {@code types.go} 逐字段核过：{@code offset/limit/unread_only/broadcast/kind} 全部
     * {@code ,optional}；{@code title/content/conversation_id} 等必填字段全是<b>引用类型</b>）⇒
     * 关掉它动不到必填语义。
     */
    private static void applyMessageDomainCoercions(ObjectMapper mapper) {
        // 补强 4：显式 null ⇒ 零值（Go 的 ,optional 字段接受 null）
        mapper.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        // 补强 1：JSON 字符串不进整数字段
        mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        // 补强 2 + 3：JSON 字符串 / JSON 数字不进布尔字段
        mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(messageJwtAuthInterceptor)
                .addPathPatterns(USER_PREFIX)
                .excludePathPatterns(ADMIN_PREFIX);

        registry.addInterceptor(messageAdminJwtAuthInterceptor)
                .addPathPatterns(ADMIN_PREFIX);
    }
}
