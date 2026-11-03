package com.rag.article.client;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * <p><b>职责</b>：内容审核端口的<b>兜底注册</b> —— <b>只做一件事</b>：当容器里还没有任何
 * {@link ContentAuditPort} 实现时，装上 {@link UnavailableContentAuditClient}。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/client/ContentSecurityAutoConfiguration.java}
 * （类声明 {@code :42-43} · {@code unavailableContentSecurityClient} {@code :45-49}）
 * ＋ <b>注册机制</b>：{@code service/article/article-rpc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * （该文件<b>只有一行</b>：{@code com.rag.article.article.rpc.client.ContentSecurityAutoConfiguration}）
 * ⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅改名 + 包名</b>（映射表 §1.2 已批准）——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.client} → {@code com.rag.article.client}（D7 包压平）；</li>
 *         <li><b>类名</b>：{@code ContentSecurityAutoConfiguration} →
 *             {@link ContentAuditAutoConfiguration} ⇒ <b>去 {@code Security} 的域名含义</b>。</li>
 *       </ul>
 *       ⇒ <b>Bean 方法体、{@code @ConditionalOnMissingBean} 的判据一字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>仍然<b>只做兜底</b>：容器里已有实现 ⇒ 本类自动退场；</li>
 *         <li>兜底实现仍然是 <b>fail-loud</b>（恒抛）⇒ 审核链路不会 fail-open。</li>
 *       </ul>
 *       ⇒ <b>唯一的机制差异</b>是「本类怎么被装配」（见下节），<b>不影响兜底语义</b>。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>为什么必须是自动配置而不是 {@code @Component}</h2>
 * {@code @ConditionalOnMissingBean} 的判断语义是「<b>处理本配置类之后</b>
 * 容器里是否已有该类型的 Bean」。这个判断只有在自动配置类上才是<b>确定的</b>——
 * 自动配置在用户 {@code @Component} <b>之后</b>求值。
 * 若把条件写在被组件扫描的普通类上，扫描顺序不确定，可能误判「已有 Bean」
 * 而让兜底实现<b>不被注册</b> ⇒ 审核消费者拿到 {@code null} 端口 ⇒ NPE。
 *
 * <h2>🔴 装配机制：本仓用<b>域内 {@code .imports} 文件</b>（与 B 侧同款）</h2>
 * <p>B 侧靠 {@code article-rpc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 那一行注册本类。RAG-V1 的<b>共享层</b>是 starter（D3），其 auto-config 由
 * {@code common/src/main/resources/META-INF/spring/…AutoConfiguration.imports} 注册；
 * 但<b>域内</b>的 auto-config 属于本域资源文件 ⇒ 本仓用<b>域内</b>的
 * {@code resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 注册本类 ⇒ ⇒ <b>入口类的 {@code @Import} 清单为空</b>（A5 守门判据），
 * 且<b>判定时序仍然是确定的</b>：
 * <pre>
 * ConfigurationClassPostProcessor
 *   parse(candidates)
 *     └─ ComponentScan（Application 的隐式 @ComponentScan）
 *          ⇒ **所有被扫描的 @Component 的 bean definition 在此注册**
 *   loadBeanDefinitions(configClasses)
 *     └─ 逐个配置类评估 @Bean 方法上的 @ConditionalOnMissingBean
 *          ⇒ 此时**被扫描的 bean definition 全部已可见**
 * </pre>
 * ⇒ ⇒ 「容器里已有 {@code ContentAuditPort} 实现 ⇒ 兜底退场」这条判据
 * <b>仍然成立</b>（本仓当前无其它实现 ⇒ 兜底<b>必</b>注册）。
 * <p>⚠️ <b>待收口</b>：若日后本域要新增第二个 auto-config，标准做法是补一个域内
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * （与 B 侧同款）⇒ 归「域内资源文件」owner。
 */
@AutoConfiguration
public class ContentAuditAutoConfiguration {

    /**
     * 兜底注册 —— 容器里没有别的 {@link ContentAuditPort} 实现时才生效。
     *
     * <p><b>B 出处</b>：{@code .../client/ContentSecurityAutoConfiguration.java:45-49}。
     *
     * @return 恒抛的兜底审核实现
     */
    @Bean
    @ConditionalOnMissingBean(ContentAuditPort.class)
    ContentAuditPort unavailableContentAuditClient() {
        return new UnavailableContentAuditClient();
    }
}
