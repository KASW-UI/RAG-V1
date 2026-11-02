package com.rag.message.interceptor;

import com.rag.message.config.MessageApiProperties;
import org.springframework.stereotype.Component;

/**
 * <p><b>职责</b>：管理员侧 JWT 鉴权拦截器 —— 挂 {@code /message/v1/admin/**}。
 *
 * <p><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/interceptor/MessageAdminJwtAuthInterceptor.java}
 * （1-45 行；类声明 {@code :38-39}；构造器 {@code :41-43}）。
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
 *   <li><b>语义还一样吗</b>：一样 —— 本类<b>只提供组别</b>（{@code ADMIN}），
 *       鉴权逻辑全在基类 ⇒ 与 B 侧「子类只提供 key」的意图完全一致。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <p><b>路径集合逐条对齐</b>：
 * <pre>
 *   POST /message/v1/admin/conversations/list
 *   POST /message/v1/admin/conversations/messages
 *   POST /message/v1/admin/conversations/read
 *   POST /message/v1/admin/conversations/send
 *   POST /message/v1/admin/notifications/send
 * </pre>
 *
 * <p><b>与 user 侧共用一份鉴权逻辑</b>（{@link AbstractMessageJwtAuthInterceptor}），
 * 但<b>密钥不同</b> —— 两段路由在 B 侧也是两次独立的 {@code rest.WithJwt}。
 * 后果：拿 user token 打 admin 端点会验签失败 → <b>401</b>（B 侧同样如此），
 * 反之亦然。这条「两套密钥互不通用」是 B 侧的既有语义，<b>不要</b>为了方便合成一份密钥。
 *
 * <p><b>密钥补齐（N-5 留档）</b>：B 侧的 admin secret 是 15 字节，golang-jwt 不校验长度；
 * jjwt 0.12 强制 ≥256 bit ⇒ 统一由基类用 {@code JwtUtil.padToHmacKeySize} zero-pad 到 32 字节。
 * 代价是 <b>B 侧签的 admin token 在 RAG 验不过，反之亦然</b>（两套部署各自闭环）。
 * 这是运行环境约束，不是行为差异 —— token 的 payload / claim / 过期语义完全一致。
 *
 * <p><b>🔴 §1.6 的 C 侧缺口</b>：本类只负责「<b>验签 + 抛 401</b>」，
 * 而 B 侧把验签搬进了 Servlet Filter（{@code @Order(+6)}，在 3s 预算<b>之外</b>）
 * ⇒ ⇒ B 侧的 {@code DRIFT-MESSAGEAPI-009②}（验签出预算）在 C 侧<b>未闭环</b>
 * ⇒ 详见 {@link AbstractMessageJwtAuthInterceptor} 的类注释。
 */
@Component
public class MessageAdminJwtAuthInterceptor extends AbstractMessageJwtAuthInterceptor {

    /**
     * 构造器 —— 只声明<b>组别</b>，其余全部交给基类。
     *
     * <p><b>B 出处</b>：{@code .../interceptor/MessageAdminJwtAuthInterceptor.java:41-43}
     * （B 侧传的是 {@code MessageJwtSignatureVerifier.RouteGroup.ADMIN} + verifier 实例；
     * 本仓传的是 {@link MessageApiProperties}，由基类自行构造两把 key）。
     *
     * @param properties HTTP 层配置（承载 {@code user-auth.access-secret} 与 {@code admin-auth.access-secret}）
     */
    public MessageAdminJwtAuthInterceptor(MessageApiProperties properties) {
        super(RouteGroup.ADMIN, properties);
    }
}
