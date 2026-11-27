package com.rag.boot.interaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * <p><b>职责</b>：<b>interaction-service</b> 进程的<b>唯一启动入口</b> —— 含 <b>like</b> ＋ <b>comment</b> ＋ <b>favorite</b> ＋ <b>follow</b> ＋ <b>message</b> 五域，
 * 一个进程内同时起 Tomcat（HTTP 面）与 gRPC server（业务面）。
 *
 * <p><b>⚠️ 本类是「启动壳」的一部分，不是新的一层业务代码</b>（S1 · 方案 A · 不搬源码）：
 * <b>like</b> ＋ <b>comment</b> ＋ <b>favorite</b> ＋ <b>follow</b> ＋ <b>message</b> 五域的<b>全部业务代码</b>仍留在各自的域模块，<b>一行未动</b>
 * ⇒ ⇒ 本类只负责「<b>把 5 个域的 bean 装进一个 Spring 上下文</b>」。
 *
 * <h2>为什么域启动类被降级为普通 {@code @Configuration}</h2>
 * <p>降级前：{@code @SpringBootApplication} = {@code @SpringBootConfiguration} +
 * {@code @EnableAutoConfiguration} + {@code @ComponentScan}（实测
 * {@code spring-boot-autoconfigure-3.5.11.jar} 的 {@code SpringBootApplication.class} 字节码：
 * 三个元注解 + {@code proxyBeanMethods=true}）。
 * <p>⇒ 本类把三件套<b>显式摊开写</b>，各域启动类降级为
 * {@code @Configuration + @EnableAutoConfiguration + @ComponentScan("<域包>")}
 * ⇒ ⇒ <b>两者共存于同一上下文</b>：域启动类提供「域视角」的 bean，本类提供「进程视角」的入口。
 *
 * <h2>⚠️ 组件扫描边界（本类最关键的一行）</h2>
 * <p>{@code @ComponentScan} 列出 <b>5 个域包</b> ⇒ 五域的 bean 进同一上下文。
 * <p>★ <b>五域路径天然互斥</b>（实测各域 controller 的类级 {@code @RequestMapping}）：
 * {@code /like/v1} · {@code /comment/v1} · {@code /favorite/v1} · {@code /follow/v1} ·
 * {@code /message/v1}（+ {@code /message/v1/admin}）⇒ ⇒ <b>不需要</b>为了路由而改任何前缀。
 * <p>⚠️ <b>message 域有两套鉴权</b>（user 段 / admin 段，secret 不同）⇒ 五域共 6 条鉴权链
 * （详见计划书 §1.4.4）。
 *
 * <h2>⚠️ bean 名必须显式指定（否则与域启动类撞名）</h2>
 * <p>Spring 的默认 bean 名 = <b>简单类名首字母小写</b> ⇒ 域启动类
 * {@code com.rag.interaction.InteractionServiceApplication} 与本类 {@code com.rag.boot.interaction.InteractionServiceApplication} 的简单类名<b>相同</b>
 * ⇒ ⇒ 两者进同一上下文时 bean 名<b>撞车</b> ⇒
 * {@code ConflictingBeanDefinitionException: Annotation-specified bean name … conflicts with existing}</b>
 * ⇒ ⇒ 本类显式命名为 <b>{@code interactionProcessApplication}</b>（带 “Process” 以示「进程级」）。
 *
 * <h2>⚠️ 本类只声明 4 个注解 —— 不重复域启动类的扫描注解</h2>
 * <p>{@code @ConfigurationPropertiesScan} / {@code @MapperScan} / {@code @EnableScheduling}
 * <b>已在各域启动类上</b>（随 {@code @ComponentScan} 带入生效）⇒ ⇒ 本类<b>刻意不重复声明</b>
 * ⇒ ⇒ <b>重复声明会双注册</b>：{@code @MapperScan} 的 {@code MapperScannerRegistrar}
 * 按 bean name 注册，第二次扫描同 name ⇒ Boot 3 的 bean 覆盖保护抛
 * {@code BeanDefinitionOverrideException} ⇒ <b>启动炸</b>。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>多了一个进程级启动壳</b>（B 侧这些域各是独立进程，
 *       本仓 D1 已把 api+rpc 合并为域；本轮再把「域」与「进程」两级分开）。
 *       <b>除此之外不新增任何行为</b>：本类<b>不写任何 bean</b>、<b>不写任何 gRPC 注册代码</b>
 *       （devh starter 扫到 {@code @GrpcService} 即自动注册）、<b>不写任何 Kafka 工厂</b>
 *       （各域的 factory 在各自域启动类里，已随扫描生效）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 本类等价于「以各域启动类的视角，加一层进程外壳」。
 *       <ul>
 *         <li>⚠️ <b>不扫 {@code com.rag.common}</b>：共享层是 <b>starter</b>（D3），由
 *             {@code AutoConfiguration.imports} 自动装配 ⇒ 再扫一遍会双注册；</li>
 *         <li>⚠️ <b>不扫其它域</b>：{@code @ComponentScan} 只列本进程包含的 5 个域包
 *             ⇒ ⇒ <b>不会</b>把别的域的 controller / mapper / kafka listener 拖进来。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：本仓各域启动类（本次降级）＋ B 侧对应的 {@code *ApiApplication} /
 *       {@code *RpcApplication}（各域 api + rpc 两段合并，D1）。</li>
 * </ol>
 *
 * <p><b>⚠️ 端口</b>（§2.6 P4 · 本轮只落形态，端口在 S3 统一改）：interaction 进程取 <b>like 的现端口</b> HTTP 8896 / gRPC 9005；<br>⚠️ comment 8892/9002、favorite 8894/9003、follow 8891/8082、message 8883/8084 <b>被释放</b>
 * ⇒ ⇒ <b>S1 阶段不改任何配置键</b>。
 *
 * <p><b>⚠️ 注册名</b>（§2.6 P1）：本轮仍是域级注册名；收敛为进程级 {@code interaction-rpc}
 * 属 <b>S3-8</b>（L1 变更 ⇒ 不可灰度 ⇒ 必须同批发布）。
 */
@Configuration("interactionProcessApplication")
@EnableAutoConfiguration
/*
 * ⚠️ **`excludeFilters` 必须显式补回（S2 · 5 进程形态 · 实测）** ——
 * `@SpringBootApplication` 的 `@ComponentScan` **隐含**两个排除过滤器：
 *   ① `TypeExcludeFilter`（Boot 的测试类型排除机制）
 *   ② `AutoConfigurationExcludeFilter`（**排除 `@AutoConfiguration` 类**）
 * ⇒ ⇒ 裸 `@ComponentScan` 时这两个**一起丢** ⇒ ⇒ **实测症状（2026-11-28）**：
 *   用本类（boot 启动类）起上下文 ⇒ `com.rag.article.client.ContentAuditAutoConfiguration`
 *   被**组件扫描**扫到 ⇒ bean 名 = **短名** `contentAuditAutoConfiguration`
 *   ⇒ ⇒ **绕过了** `META-INF/spring/…AutoConfiguration.imports` 机制
 *   ⇒ ⇒ 而用【域启动类】起同一个上下文 ⇒ bean 名 = **FQCN**
 *   `com.rag.article.client.ContentAuditAutoConfiguration` ✅
 * ⇒ ⇒ **同一份域代码，两种启动入口 ⇒ bean 名不同** ⇒ ⇒ **这是真实的机制差异，不是风格问题**
 * ⇒ ⇒ 本处**逐字补回** `@SpringBootApplication` 原本的那两个过滤器。
 */
@ComponentScan(basePackages = {"com.rag.like", "com.rag.comment", "com.rag.favorite", "com.rag.follow", "com.rag.message"},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM,
                        classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM,
                        classes = AutoConfigurationExcludeFilter.class)
        })
@EnableDiscoveryClient
public class InteractionServiceApplication {

    /**
     * 进程入口 —— interaction-service 进程的唯一 main。
     *
     * <p>⚠️ 本类<b>不含任何 bean 定义</b>：各域的全部装配来自各域启动类
     * （随 {@code @ComponentScan} 生效）⇒ ⇒ 与降级前<b>行为等价</b>。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(InteractionServiceApplication.class, args);
    }
}
