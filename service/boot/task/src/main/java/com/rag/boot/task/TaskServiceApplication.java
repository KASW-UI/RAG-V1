package com.rag.boot.task;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * <p><b>职责</b>：<b>task-service</b> 进程的<b>唯一启动入口</b> —— 含 <b>task</b> 域（纯异步消费方，Kafka 链路下游终点），
 * 一个进程内同时起 Tomcat（HTTP 面）与 gRPC server（业务面）。
 *
 * <p><b>⚠️ 本类是「启动壳」的一部分，不是新的一层业务代码</b>（S1 · 方案 A · 不搬源码）：
 * <b>task</b> 域（纯异步消费方，Kafka 链路下游终点）的<b>全部业务代码</b>仍留在各自的域模块，<b>一行未动</b>
 * ⇒ ⇒ 本类只负责「<b>把 1 个域的 bean 装进一个 Spring 上下文</b>」。
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
 * <p>{@code @ComponentScan("com.rag.task")} ⇒ 只扫 task 域。
 * <p>⚠️ <b>task 域无鉴权</b>（{@code WebMvcConfig} 明确「不引入 {@code JwtAuthInterceptor}」，
 * 其 {@code etc/task.yaml} 的 {@code Auth{}} 是<b>死配置</b>）⇒ ⇒ <b>不建</b> Spring Security 链。
 * <p>⚠️ <b>task 的 HTTP 基路径是 {@code /taskcenter/v1}</b>（<b>不是</b> {@code /task/v1}）
 * ⇒ ⇒ 将来配 {@code securityMatcher} 时必须用真值，否则<b>静默匹配不到</b>。
 *
 * <h2>⚠️ bean 名必须显式指定（否则与域启动类撞名）</h2>
 * <p>Spring 的默认 bean 名 = <b>简单类名首字母小写</b> ⇒ 域启动类
 * {@code com.rag.task.TaskServiceApplication} 与本类 {@code com.rag.boot.task.TaskServiceApplication} 的简单类名<b>相同</b>
 * ⇒ ⇒ 两者进同一上下文时 bean 名<b>撞车</b> ⇒
 * {@code ConflictingBeanDefinitionException: Annotation-specified bean name … conflicts with existing}</b>
 * ⇒ ⇒ 本类显式命名为 <b>{@code taskProcessApplication}</b>（带 “Process” 以示「进程级」）。
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
 *         <li>⚠️ <b>不扫其它域</b>：{@code @ComponentScan} 只列本进程包含的 1 个域包
 *             ⇒ ⇒ <b>不会</b>把别的域的 controller / mapper / kafka listener 拖进来。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：本仓各域启动类（本次降级）＋ B 侧对应的 {@code *ApiApplication} /
 *       {@code *RpcApplication}（各域 api + rpc 两段合并，D1）。</li>
 * </ol>
 *
 * <p><b>⚠️ 端口</b>（§2.6 P4 · 本轮只落形态，端口在 S3 统一改）：task 进程<b>沿用 task 现端口</b> HTTP 8890 / gRPC 9006（<b>无端口变更</b>）
 * ⇒ ⇒ <b>S1 阶段不改任何配置键</b>。
 *
 * <p><b>⚠️ 注册名</b>（§2.6 P1）：本轮仍是域级注册名；收敛为进程级 {@code task-rpc}
 * 属 <b>S3-8</b>（L1 变更 ⇒ 不可灰度 ⇒ 必须同批发布）。
 */
@Configuration("taskProcessApplication")
@EnableAutoConfiguration
@ComponentScan("com.rag.task")
@EnableDiscoveryClient
public class TaskServiceApplication {

    /**
     * 进程入口 —— task-service 进程的唯一 main。
     *
     * <p>⚠️ 本类<b>不含任何 bean 定义</b>：各域的全部装配来自各域启动类
     * （随 {@code @ComponentScan} 生效）⇒ ⇒ 与降级前<b>行为等价</b>。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(TaskServiceApplication.class, args);
    }
}
