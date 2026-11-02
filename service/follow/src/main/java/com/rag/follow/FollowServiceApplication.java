package com.rag.follow;

import com.rag.follow.config.FollowApiProperties;
import com.rag.follow.config.FollowRpcProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * <p><b>follow 域唯一入口</b> —— B 侧 {@code follow-api} / {@code follow-rpc} <b>两个进程</b>合并后的单一 app（D1）。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   B 侧 follow-api 入口：service/follow/follow-api/src/main/java/com/rag/follow/follow/api/FollowApiApplication.java
 *   B 侧 follow-rpc 入口：service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/FollowRpcApplication.java
 * </pre>
 *
 * <h2>⚠️ L4 idiomatization：两个 B 侧入口 → 1 个 C 侧入口（D1）</h2>
 * <table border="1">
 *   <tr><th>B 侧（两侧各一份）</th><th>C 侧（本类）</th><th>语义等价论证</th></tr>
 *   <tr>
 *     <td>{@code @SpringBootApplication(scanBasePackages={"…follow.api","com.rag.common"})}<br/>（api 侧）</td>
 *     <td>{@code @SpringBootApplication}（默认扫 {@code com.rag.follow} 及子包）</td>
 *     <td>ⓘ <b>为何不再扫 {@code com.rag.common}</b>：B 侧 api 进程是<b>纯 HTTP</b>，必须显式扫
 *         {@code com.rag.common} 才能拿到其中的 servlet 组件；而 B 侧 rpc 进程<b>刻意不扫</b>
 *         （否则会把 api 侧中间件拖进 rpc 进程）。<br/>
 *         C 侧合并成单进程 ⇒ 两边都跑 ⇒ <b>共享层改成 starter（auto-config）</b>（D3）
 *         ⇒ 域内<b>零 {@code @Import}</b>、零 {@code scanBasePackages}</b> ⇒ <b>不需要</b>扫
 *         {@code com.rag.common}。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code @EnableConfigurationProperties(FollowApiProperties.class)}（api 侧）<br/>
 *         {@code @EnableConfigurationProperties(FollowRpcProperties.class)}（rpc 侧）</td>
 *     <td>{@code @EnableConfigurationProperties({FollowApiProperties.class, FollowRpcProperties.class})}</td>
 *     <td>两段配置<b>都</b>绑定 ⇒ 与 B 侧「两个进程各自绑定自己那段」在<b>取值上完全相同</b>
 *         ⇒ ⇒ <b>不可能只配一边而漏掉另一边</b>。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code @EnableDiscoveryClient}（两侧各一份）</td>
 *     <td>{@code @EnableDiscoveryClient}（1 份）</td>
 *     <td>同一开关，合并后只声明一次。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code @MapperScan("…follow.rpc.mapper")}（<b>仅 rpc 侧</b>）</td>
 *     <td>{@code @MapperScan("com.rag.follow.mapper")}</td>
 *     <td>ⓘ B 侧 api 侧<b>没有</b> {@code @MapperScan}（它不碰 DB）；C 侧合并后 Mapper 唯一落点是
 *         {@code com.rag.follow.mapper} ⇒ <b>必须</b>扫。
 *         ⇒ 属 <b>纯 fail-closed 补齐</b>，不改任何运行时取值语义。</td>
 *   </tr>
 * </table>
 *
 * <h2>⚠️ 本域是「一域一进程 ＋ 双传输」的形态</h2>
 * <p>与 users 域同形：**同一个** Spring 容器里同时起
 * <ul>
 *   <li><b>Tomcat</b>（{@code server.port}，承载 8 个 HTTP 端点 ＋ Actuator）</li>
 *   <li><b>gRPC server</b>（{@code grpc.server.port}，承载 8 个 gRPC 用例）</li>
 * </ul>
 * ⇒ ⇒ <b>异常处理必须分两套</b>：HTTP 侧走 {@code advice/}（{@code @RestControllerAdvice}），
 * gRPC 侧走 {@code grpc/} 的 {@code onError} 出口（AGENTS.md §2.2 的三点之一）。
 *
 * <h2>框架职责</h2>
 * <ul>
 *   <li>{@code @SpringBootApplication} = {@code @SpringBootConfiguration} + {@code @EnableAutoConfiguration}
 *       + {@code @ComponentScan}（默认扫启动类所在包 {@code com.rag.follow} 及全部子包
 *       ⇒ controller / service / domain / interceptor / advice / config / metrics / grpc / wiring
 *       / contract / dto / mapper / client 全部纳入）</li>
 *   <li>{@code @EnableAutoConfiguration} ⇒ 共享层 {@code common/} 的 auto-config 生效
 *       （含 gRPC 拦截链挂载、观测、熔断/降载、持久层超时注入、Jackson 强转、Redis 断连行为…）</li>
 *   <li>gRPC server 由 {@code grpc-server-spring-boot-starter} 自动起（{@code @GrpcService} 被扫描到即可）</li>
 * </ul>
 *
 * <p>📌 <b>相关台账</b>：本类的装配形态偏离登记在
 * {@code docs/Intentional_Deviations/follow/}（D1 合并 + D3 starter 化）；
 * 行为层（L1/L2）无偏离。
 */
@SpringBootApplication
@EnableConfigurationProperties({FollowApiProperties.class, FollowRpcProperties.class})
@EnableDiscoveryClient
@MapperScan("com.rag.follow.mapper")
public class FollowServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FollowServiceApplication.class, args);
    }
}
