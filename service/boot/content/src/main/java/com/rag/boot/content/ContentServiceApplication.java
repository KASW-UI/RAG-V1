package com.rag.boot.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * <p><b>职责</b>：<b>content-service</b> 进程的<b>唯一启动入口</b> —— 含 <b>article</b> 域，
 * 一个进程内同时起 Tomcat（HTTP 面）与 gRPC server（业务面）。
 *
 * <p><b>⚠️ 本类是「启动壳」的一部分，不是新的一层业务代码</b>（S1 · 方案 A · 不搬源码）：
 * article 域的<b>全部业务代码</b>（controller / service / mapper / gRPC / kafka / advice /
 * interceptor / config / wiring / contract）仍留在 {@code service/article/}，<b>一行未动</b>
 * ⇒ ⇒ 本类只负责「<b>把 article 域的 bean 装进一个 Spring 上下文</b>」。
 *
 * <h2>为什么域启动类被降级为普通 {@code @Configuration}</h2>
 * <p>降级前：{@code @SpringBootApplication} = {@code @SpringBootConfiguration} +
 * {@code @EnableAutoConfiguration} + {@code @ComponentScan}（实测
 * {@code spring-boot-autoconfigure-3.5.11.jar} 的 {@code SpringBootApplication.class} 字节码：
 * 三个元注解 + {@code proxyBeanMethods=true}）。
 * <p>⇒ 本类把三件套<b>显式摊开写</b>，而域启动类（{@code com.rag.article.ArticleServiceApplication}）
 * 降级为 {@code @Configuration + @EnableAutoConfiguration + @ComponentScan("com.rag.article")}
 * ⇒ ⇒ <b>两者可以共存于同一个上下文</b>：域启动类提供「article 域视角」的 bean，
 * 本类提供「进程视角」的入口。
 *
 * <h2>⚠️ 组件扫描边界（本类最关键的一行）</h2>
 * <p>{@code @ComponentScan("com.rag.article")} 只扫 article 域 ⇒ <b>不会</b>把别的域的 bean 拖进来。
 * <p>⚠️ 但注意：{@code com.rag.article} 包下<b>含域启动类本身</b>
 * （{@code ArticleServiceApplication}，现已降级为 {@code @Configuration}）⇒ 它<b>会被扫到</b>
 * ⇒ ⇒ 它的 {@code @ComponentScan("com.rag.article")} 会<b>再扫一遍</b>（幂等，Spring 会去重，
 * <b>不是</b>双注册问题）⇒ ⇒ 其余注解（{@code @ConfigurationPropertiesScan} / {@code @MapperScan} /
 * {@code @EnableScheduling} / {@code @EnableDiscoveryClient}）<b>逐条生效</b> ⇒
 * ⇒ <b>article 域的语义零变化</b>。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>多了一个进程级启动壳</b>（B 侧 article 是 api + rpc
 *       两个进程，本仓 D1 已合并为一个；本轮再把「域」与「进程」两级分开）。
 *       <b>除此之外不新增任何行为</b>：本类<b>不写任何 bean</b>、<b>不写任何 gRPC 注册代码</b>
 *       （devh starter 扫到 {@code @GrpcService} 即自动注册）、<b>不写任何 Kafka 工厂</b>
 *       （article 域的 {@code articleKafkaListenerContainerFactory} 在域启动类里，已随扫描生效）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 本类等价于「以 article 域启动类的视角，加一层进程外壳」。
 *       <ul>
 *         <li>⚠️ <b>本类刻意只声明 4 个注解</b>（{@code @Configuration} + {@code @EnableAutoConfiguration} +
 *             {@code @ComponentScan} + {@code @EnableDiscoveryClient}）——
 *             <b>不重复</b> {@code @ConfigurationPropertiesScan} / {@code @MapperScan} /
 *             {@code @EnableScheduling}：这三条<b>已在域启动类上</b>（随 {@code @ComponentScan} 带入生效）
 *             ⇒ ⇒ <b>重复声明会双注册</b>：{@code @MapperScan} 的 {@code MapperScannerRegistrar}
 *             按 bean name 注册（{@code articleMapper} …），第二次扫描同 name ⇒
 *             Boot 3 的 bean 覆盖保护抛 {@code BeanDefinitionOverrideException} ⇒ <b>启动炸</b>
 *             ⇒ ⇒ <b>只声明一次</b>（域启动类那份）。</li>
 *         <li>{@code @ConfigurationPropertiesScan("com.rag.article.config")}（域启动类上）⇒ 绑定
 *             {@code article-api.*} 与 {@code article-rpc.*} 两段前缀 ⇒ <b>配置键逐字不变</b>；</li>
 *         <li>{@code @MapperScan("com.rag.article.mapper")}（域启动类上）⇒ 注册 6 个 Mapper；</li>
 *         <li>{@code @EnableScheduling}（域启动类上）⇒ outbox relay 的 ticker 靠它，缺它 relay <b>静默不跑</b>；</li>
 *         <li>{@code @EnableDiscoveryClient} ⇒ 本类与域启动类<b>各一份</b>（幂等，同一开关）；</li>
 *         <li>⚠️ <b>不扫 {@code com.rag.common}</b>：共享层是 <b>starter</b>（D3），由
 *             {@code AutoConfiguration.imports} 自动装配 ⇒ 再扫一遍会双注册。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：本仓 {@code service/article/src/main/java/com/rag/article/ArticleServiceApplication.java}
 *       （域启动类，本次降级）＋ B 侧 {@code service/article/article-rpc/.../ArticleRpcApplication.java}
 *       ＋ {@code service/article/article-api/.../ArticleApiApplication.java}。</li>
 * </ol>
 *
 * <p><b>⚠️ 端口</b>（§2.6 P4 · 本轮只落形态，端口在 S3 统一改）：content 进程取
 * <b>article 的现端口</b> HTTP 8893 / gRPC 9001 ⇒ 与域内 {@code application.yml} 一致
 * ⇒ <b>S1 阶段不改任何配置键</b>。
 *
 * <p><b>⚠️ 注册名</b>（§2.6 P1）：本轮仍是域级 {@code article-rpc}；
 * 收敛为进程级 {@code content-rpc} 属 <b>S3-8</b>（L1 变更 ⇒ 不可灰度 ⇒ 必须同批发布）。
 */
@Configuration("contentProcessApplication")
@EnableAutoConfiguration
@ComponentScan("com.rag.article")
@EnableDiscoveryClient
public class ContentServiceApplication {

    /**
     * 进程入口 —— content 进程（article 域）的唯一 main。
     *
     * <p>⚠️ 本类<b>不含任何 bean 定义</b>：article 域的全部装配来自域启动类
     * （随 {@code @ComponentScan} 生效）⇒ ⇒ 与降级前<b>行为等价</b>。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ContentServiceApplication.class, args);
    }
}
