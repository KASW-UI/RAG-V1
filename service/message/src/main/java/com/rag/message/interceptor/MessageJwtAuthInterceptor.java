package com.rag.message.interceptor;

import com.rag.message.config.MessageApiProperties;
import org.springframework.stereotype.Component;

/**
 * <p><b>职责</b>：用户侧 JWT 鉴权拦截器 —— 挂 {@code /message/v1/**}（<b>不含</b> {@code /admin/**}）。
 *
 * <p><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/interceptor/MessageJwtAuthInterceptor.java}
 * （1-49 行；类声明 {@code :43-44}；构造器 {@code :46-48}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯机械</b>改写 ——
 *       <ul>
 *         <li>包名 {@code com.rag.message.message.api.interceptor} → {@code com.rag.message.interceptor}
 *             （D7 包压平）；</li>
 *         <li>构造器入参：B 侧是 {@code MessageJwtSignatureVerifier}（B 侧 {@code auth/} 包的一个类）
 *             ⇒ 本仓<b>没有</b> {@code auth/} 包（映射表 §1.6 的 1:N 合并）⇒
 *             改注入 {@link MessageApiProperties}（两把 secret 的载体），
 *             由基类 {@link AbstractMessageJwtAuthInterceptor} 自己构造两把 key。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 本类<b>只提供组别</b>（{@code USER}），
 *       鉴权逻辑全在基类 ⇒ 与 B 侧「子类只提供 key」的意图完全一致。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>路径集合逐条对齐</b>（B 侧 8 个，全部在本拦截器覆盖范围内）：
 * <pre>
 *   POST /message/v1/conversations/list
 *   POST /message/v1/conversations/messages
 *   POST /message/v1/conversations/read
 *   POST /message/v1/conversations/send
 *   POST /message/v1/notifications/list
 *   POST /message/v1/notifications/read
 *   POST /message/v1/notifications/readall
 *   GET  /message/v1/unread            ← 唯一非 POST 的端点
 * </pre>
 * 与 B 侧的差别只有两点，都是表达层：
 * <ol>
 *   <li>Go 用 {@code rest.WithPrefix} + 相对 path 拼出完整路径，Spring 用
 *       {@code @RequestMapping("/message/v1")} + 类内相对 path；</li>
 *   <li>Go 的 path 匹配由 go-zero router 做，Spring 由 DispatcherServlet 做。
 *       两者都是「前缀 + 精确段」匹配，{@code /message/v1/admin/conversations/list}
 *       在 Go 侧<b>不</b>匹配第一段的 {@code /conversations/list}（prefix 是完整的
 *       {@code /message/v1} + 相对 path），所以 user 拦截器必须显式 exclude admin 前缀 ——
 *       见 {@code config/WebMvcConfig.java} 的
 *       {@code addPathPatterns("/message/v1/**")} + {@code excludePathPatterns("/message/v1/admin/**")}。</li>
 * </ol>
 *
 * <p><b>密钥补齐</b>：Go 侧 user secret 是 36 字节 UUID（≥32），jjwt 也要求 ≥32 字节，
 * 因此 {@code JwtUtil.padToHmacKeySize} 对它其实是 no-op；统一调用是为了与 admin 侧共用同一份
 * 逻辑（admin secret 只有 15 字节，必须补齐）。详见基类构造器的 N-5 留档。
 *
 * <p><b>🔴 §1.6 的 C 侧缺口</b>：本类只负责「<b>验签 + 抛 401</b>」，
 * 而 B 侧把验签搬进了 Servlet Filter（{@code @Order(+6)}，在 3s 预算<b>之外</b>）
 * ⇒ ⇒ B 侧的 {@code DRIFT-MESSAGEAPI-009②}（验签出预算）在 C 侧<b>未闭环</b>
 * ⇒ 详见 {@link AbstractMessageJwtAuthInterceptor} 的类注释。
 */
@Component
public class MessageJwtAuthInterceptor extends AbstractMessageJwtAuthInterceptor {

    /**
     * 构造器 —— 只声明<b>组别</b>，其余全部交给基类。
     *
     * <p><b>B 出处</b>：{@code .../interceptor/MessageJwtAuthInterceptor.java:46-48}
     * （B 侧传的是 {@code MessageJwtSignatureVerifier.RouteGroup.USER} + verifier 实例；
     * 本仓传的是 {@link MessageApiProperties}，由基类自行构造两把 key）。
     *
     * @param properties HTTP 层配置（承载 {@code user-auth.access-secret} 与 {@code admin-auth.access-secret}）
     */
    public MessageJwtAuthInterceptor(MessageApiProperties properties) {
        super(RouteGroup.USER, properties);
    }
}
