package com.rag.comment.wiring;

import com.rag.comment.config.CommentRpcProperties;
import com.rag.comment.service.CommentAuthorityLookupService;
import com.rag.common.autoconfigure.WebAutoConfiguration;
import jakarta.servlet.http.HttpServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <p><b>权威读端点装配</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/bootstrap/CommentAuthorityEndpointConfig.java:1-91}
 * （B 侧该文件的语义来源注释写的是 Go 端
 * {@code service/comment/rpc/cmd/fact-authority/main.go:50-95} 的 {@code run(logger)}）。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧为基准整段抄 · D13 / D16）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.comment.comment.rpc.bootstrap} 移到 {@code com.rag.comment.wiring}（D7 · D10）；</li>
 *       <li><b>共享层收口</b>：B 侧的 {@code com.rag.common.spring.AuthorityHandlerFactory}
 *           （含 {@code AUTHORITY_PATH} 常量与静态 {@code register(...)} 工厂）
 *           在 C 侧已由 {@link WebAutoConfiguration} 承担
 *           （{@code WebAutoConfiguration.AUTHORITY_PATH} + {@code registerAuthorityHandler(...)}）
 *           ⇒ ⇒ <b>本类不再持有 marker Bean</b>（B 侧的 {@code authorityHandlerFactoryMarker()}，
 *           其存在只是为了让 {@code AuthorityHandlerFactory} 这个类被加载并注册成 Bean，
 *           而 C 侧它已是共享层 auto-config 的一部分 ⇒ 该 marker <b>失去存在理由</b>）；</li>
 *       <li>路径常量不再在本类重复定义 ⇒ 直接用共享层的 {@code AUTHORITY_PATH}，
 *           避免「域内一份、共享层一份」的两处真值。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。默认关闭（{@code comment.authority.enabled=false}）、
 *       token 长度 ≥32 的启动期门槛、路径 {@code /internal/v1/community/facts}、
 *       「打开等于新增对外 HTTP 端口」的告警逐字未改。
 *       ⇒ <b>本类只负责「装配什么」，不负责「暴露在哪」</b> —— 端口是部署契约，留在配置里。</li>
 *   <li><b>B 出处</b>：B 侧 {@code comment-rpc/.../bootstrap/CommentAuthorityEndpointConfig.java:1-91}
 *       （逐条对应关系见各成员注释）。</li>
 * </ol>
 *
 * <h2>⚠️ 默认关闭：打开它等于<b>新增一个对外 HTTP 端口</b>（wire 面变更）</h2>
 * <p>B 侧注释自记：Go 侧该端点跑在<b>独立进程 + 独立监听端口</b>上（{@code COMMENT_FACT_AUTHORITY_LISTEN}）。
 * 合并进单 JVM 后要提供它，必须引入 servlet 容器。
 * <p>⇒ 本类默认 <b>不装配</b>（{@code comment.authority.enabled=false}）。
 * 若要启用，需要人工同时完成三件事，缺一件都会起不来或暴露面失控：
 * <ol>
 *   <li>确认本模块已引 servlet 容器（B 侧 comment-rpc 的 pom <b>没有</b> {@code spring-boot-starter-web}，
 *       这正是 B 侧那个类不能无条件注册 {@code ServletRegistrationBean} 的原因；C 侧本模块
 *       <b>已有</b> {@code spring-boot-starter-web} ⇒ 不再是阻塞项）；</li>
 *   <li>显式设置监听端口，不要让它抢业务端口；</li>
 *   <li>确认 {@code COMMENT_FACT_AUTHORITY_TOKEN} ≥ 32 字节并完成对外暴露面的评审。</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "comment.authority", name = "enabled", havingValue = "true")
public class CommentAuthorityEndpointConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CommentAuthorityEndpointConfig.class);

    /**
     * 生产者标识 —— 逐字取自 B 侧 {@code CommentAuthorityEndpointConfig.java:15} 的
     * {@code communityfact.NewAuthorityHandler("rtw.comment-rpc", token, lookup, logger)}。
     *
     * <p>⚠️ <b>为什么在这里固化字面量</b>：B 侧此处引用的是同包
     * {@code CommentDomainFactService.PRODUCER}（B 侧 {@code CommentDomainFactService.java:52}
     * 的 {@code public static final String PRODUCER = "rtw.comment-rpc";}）。
     * C 侧把该类落到了 {@code com.rag.comment.domain.service.CommentDomainFactService}，
     * 本类<b>不</b>跨类引用那个常量 ⇒ 好处是本类的编译不依赖另一个域内类的常量是否已就位；
     * 代价是同一个字面量在仓内有两处。
     * ⇒ ⚠️ <b>两处必须同步</b>：改动生产者标识时，
     * {@code domain/service/CommentDomainFactService#PRODUCER} 与本常量要一起改。
     */
    private static final String PRODUCER = "rtw.comment-rpc";

    private final CommentRpcProperties props;

    public CommentAuthorityEndpointConfig(CommentRpcProperties props) {
        this.props = props;
    }

    /**
     * 注册权威读 Servlet —— 对应 B 侧 {@code CommentAuthorityEndpointConfig.java:67-90}。
     *
     * <p>⚠️ <b>路径逐字沿用</b>共享层的 {@code WebAutoConfiguration.AUTHORITY_PATH}
     * （= {@code /internal/v1/community/facts}，与 B 侧 {@code AuthorityHandlerFactory.AUTHORITY_PATH}
     * 及 Go 的 {@code mux.HandleFunc} 路由一致）⇒ 路径是 <b>L1 契约</b>，DC 侧按它回查。
     *
     * <p>⚠️ <b>方法引用而不是 lambda</b>：{@code CommentAuthorityLookupService#lookup} 的签名
     * （{@code (Context, String, String) → AuthorityFact}）与共享层的
     * {@code AuthorityLookup} 逐字一致 ⇒ 用方法引用可在编译期发现签名漂移。
     *
     * @throws IllegalStateException token 长度不足 —— 与 B 侧
     *         {@code CommentAuthorityEndpointConfig.java:77-82} 的 {@code IllegalStateException}
     *         同向（同为「启动即失败」而非「起来了但每次请求都 401」）。
     */
    @Bean
    public ServletRegistrationBean<HttpServlet> communityFactAuthorityServlet(
            CommentAuthorityLookupService lookupService) {
        String token = props.getAuthority().getToken();
        if (token == null || token.length() < 32) {
            throw new IllegalStateException(
                    "community fact authority is not configured: token 必须 ≥32 字节"
                            + "（对应 B 侧 comment-rpc/.../bootstrap/CommentAuthorityEndpointConfig.java:78-82）");
        }
        LOG.warn("""
                CommentAuthorityEndpointConfig: 已启用权威读端点 {}。
                  ⚠️ 这会让 comment 从「仅 gRPC + HTTP 业务端点」变成「额外对外提供事实查询 HTTP 端点」——
                     请确认监听端口已显式设置、且对外暴露面已评审。
                """, WebAutoConfiguration.AUTHORITY_PATH);
        return WebAutoConfiguration.registerAuthorityHandler(
                PRODUCER, token, lookupService::lookup);
    }
}
