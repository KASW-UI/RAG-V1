package com.rag.like.wiring;

import com.rag.common.communityfact.AuthorityHandler;
import com.rag.like.config.LikeRpcProperties;
import com.rag.like.service.LikeAuthorityLookupService;
import com.rag.like.service.LikeDeliveryValidator;
import jakarta.servlet.http.HttpServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <p><b>权威读端点的 Servlet 注册</b> —— 对应 B 侧
 * {@code like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeAuthorityEndpointConfig.java}。
 *
 * <p><b>抄写基准</b>：B 侧该文件是<b>骨架占位</b>（语义层已实现，只差「暴露形态」这一半），
 * 本类补齐 C 侧的形态（映射表第 329 行：「{@code wiring/LikeAuthorityEndpointConfig.java} 注册
 * {@code common/.../AuthorityHandler} 的 Servlet（路径 {@code /internal/v1/community/facts}）」）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>把形态从「独立进程」落到「并入主 app 端口」</b>
 *       —— 决策记录 <b>P6-b</b> 已定 ①「并入主 app 端口」：
 *       <ul>
 *         <li>B 侧的三个硬约束在 C 侧<b>都不成立</b>：
 *             ① C 侧 like 是<b>合并后的 web app</b>（已有 {@code spring-boot-starter-web}）⇒ classpath 上
 *             <b>本来就有 servlet-api</b>；② 不存在「like-rpc 纯 gRPC 进程」的形态；</li>
 *         <li>⇒ 用 {@link ServletRegistrationBean} 把共享层的 {@link AuthorityHandler}
 *             注册到<b>主 app 的 Tomcat</b>上（不新开端口）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样。语义层（12 道校验）由
 *       {@link LikeAuthorityLookupService} 承担（与 B 侧同源），
 *       共享层的 {@code AuthorityHandler} 负责 wire 形态（404 / 503 的分流、鉴权 token）。
 *       ⇒ 本类只做「<b>接线</b>」，不引入任何新的判定。</li>
 *   <li><b>B 出处</b>：B 侧骨架文件 + 共享层
 *       {@code service/common/src/main/java/com/rag/common/communityfact/AuthorityHandler.java}。</li>
 * </ol>
 *
 * <h2>⚠️ 默认关闭</h2>
 * <p>B 侧 {@code like.authority.enabled} 默认 false ⇒ 默认关闭时<b>不注册任何额外 HTTP 端点</b>
 * ⇒ 本类用 {@link ConditionalOnProperty} 在关闭时<b>连 Bean 都不建</b>（与 B 侧「默认不注册」同形）。
 *
 * <h3>🔴 配置键前缀必须是 {@code like.authority}（不是 {@code like.fact.authority}）</h3>
 * <p><b>绑定依据</b>：B 侧 {@code LikeRpcProperties} =
 * {@code @ConfigurationProperties(prefix = "like")}，其 {@code Authority} 是
 * {@code like} 的<b>直接子节点</b>（不是 {@code fact} 的子节点）⇒ Spring 的绑定键 =
 * {@code like.authority.enabled} / {@code like.authority.token}
 * ⇒ 与 B 侧 {@code like-rpc/application.yml} 的
 * {@code like: → authority: → enabled / token} 两段逐字一致。
 * <p>⇒ ⇒ 本注解的 {@code prefix} 必须写成 {@code like.authority}：
 * 写成 {@code like.fact.authority} ⇒ 与 {@link LikeRpcProperties} 的绑定键<b>不是同一个</b>
 * ⇒ 开关与 token 两处<b>各绑各的</b> ⇒ 「开关开了但 token 是空串」⇒
 * {@link AuthorityHandler#create} 因 token &lt; 32 抛异常 ⇒ <b>启动失败</b>。
 * <p>⚠️ 这类「键名写错 ⇒ 静默不生效」的形态正是 AGENTS.md §3.3 点名的那一类
 * （<b>配置里没写 ≠ 没开，写了也可能没接</b>）⇒ 键名属于 L1 契约，逐字抄 B 侧。
 *
 * <h2>⚠️ token 长度 ≥32 是共享层的硬门槛</h2>
 * <p>{@link AuthorityHandler#create(String, String, AuthorityHandler.AuthorityLookup)} 在
 * token 长度 &lt; 32 时直接抛 {@code IllegalArgumentException}
 * ⇒ ⇒ 配置不全时<b>启动失败</b>（而不是「起来了但每次请求都 401」）⇒ 失败点更早、更明确。
 */
@Configuration
@ConditionalOnProperty(prefix = "like.authority", name = "enabled", havingValue = "true")
public class LikeAuthorityEndpointConfig {

    private static final Logger LOG = LoggerFactory.getLogger(LikeAuthorityEndpointConfig.class);

    /**
     * 权威读端点的 Servlet 路径 —— 映射表第 329 行钉死的值。
     *
     * <p>⚠️ <b>这是 L1 契约</b>：DC 侧按这个路径回查 ⇒ 改路径会让权威读失效。
     */
    public static final String AUTHORITY_SERVLET_PATH = "/internal/v1/community/facts";

    private final LikeAuthorityLookupService lookupService;
    private final LikeRpcProperties properties;

    public LikeAuthorityEndpointConfig(LikeAuthorityLookupService lookupService,
                                       LikeRpcProperties properties) {
        this.lookupService = lookupService;
        this.properties = properties;
    }

    /**
     * 注册权威读 Servlet —— 落在<b>主 app 的 Tomcat</b>（决策记录 P6-b ①）。
     *
     * <p>⚠️ <b>方法引用而不是 lambda</b>：
     * {@link LikeAuthorityLookupService#lookup} 的签名与共享层的
     * {@code AuthorityLookup}（{@code (Context, String, String) → AuthorityFact}）<b>逐字一致</b>
     * ⇒ 用方法引用（{@code lookupService::lookup}）编译期即可发现签名漂移。
     *
     * <p>⚠️ <b>本类不知道 HTTP 的存在</b>：语义层（{@code LikeAuthorityLookupService}）
     * 只依赖零 servlet 依赖的共享层 {@code Wire} ⇒ HTTP 适配完全落在这一个 Bean 里。
     */
    @Bean
    public ServletRegistrationBean<HttpServlet> likeFactAuthorityServletRegistration() {
        LikeRpcProperties.Authority authority = properties.getAuthority();
        HttpServlet servlet = AuthorityHandler.create(
                LikeDeliveryValidator.FACT_PRODUCER,
                authority.getToken(),
                lookupService::lookup);
        ServletRegistrationBean<HttpServlet> registration =
                new ServletRegistrationBean<>(servlet, AUTHORITY_SERVLET_PATH);
        registration.setName("likeFactAuthorityServlet");
        registration.setLoadOnStartup(1);
        LOG.info("like fact authority endpoint registered: path={} producer={}",
                AUTHORITY_SERVLET_PATH, LikeDeliveryValidator.FACT_PRODUCER);
        return registration;
    }
}
