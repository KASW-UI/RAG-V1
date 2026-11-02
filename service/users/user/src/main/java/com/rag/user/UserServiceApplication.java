package com.rag.user;

import com.rag.user.config.UserApiProperties;
import com.rag.user.config.UserRpcProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * <p><b>user 域唯一入口</b> —— B 侧 {@code user-api} / {@code user-rpc} <b>两个进程</b>合并后的单一 app（D1）。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   B 侧 user-api 入口：service/users/user/user-api/src/main/java/com/rag/users/user/api/UserApiApplication.java
 *   B 侧 user-rpc 入口：service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/UserRpcApplication.java
 * </pre>
 *
 * <h2>⚠️ L4 idiomatization：两个 B 侧入口 → 1 个 C 侧入口（D1）</h2>
 * <table border="1">
 *   <tr><th>B 侧（两侧各一份）</th><th>C 侧（本类）</th><th>语义等价论证</th></tr>
 *   <tr>
 *     <td>{@code @SpringBootApplication(scanBasePackages={"…user.api","com.rag.common"})}<br/>（api 侧）</td>
 *     <td>{@code @SpringBootApplication}（默认扫 {@code com.rag.user} 及子包）</td>
 *     <td>ⓘ <b>为何不再扫 {@code com.rag.common}</b>：B 侧 api 进程是<b>纯 HTTP</b>，必须显式扫
 *         {@code com.rag.common} 才能拿到 {@code HttpMiddlewareFilter}；而 B 侧 rpc 进程<b>刻意不扫</b>
 *         （否则会把 api 侧中间件拖进 rpc 进程）。<br/>
 *         C 侧合并成单进程 ⇒ 两边都跑 ⇒ <b>共享层改成 starter（auto-config）</b>（D3）
 *         ⇒ 域内<b>零 {@code @Import}</b>、零 {@code scanBasePackages}</b> ⇒ <b>不需要</b>扫
 *         {@code com.rag.common}，也不应该扫（那会把 starter 的装配语义退回 B 侧的形态）。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code @EnableConfigurationProperties(UserApiProperties.class)}（api 侧）<br/>
 *         {@code @EnableConfigurationProperties(UserRpcProperties.class)}（rpc 侧）</td>
 *     <td>{@code @EnableConfigurationProperties({UserApiProperties.class, UserRpcProperties.class})}</td>
 *     <td>两段配置<b>都</b>绑定 ⇒ 与 B 侧「两个进程各自绑定自己那段」在<b>取值上完全相同</b>
 *         （yml 里两个键都指向同一个环境变量 {@code USER_JWT_SECRET} / {@code USER_JWT_EXPIRE}）
 *         ⇒ ⇒ <b>不可能只配一边而漏掉另一边</b>。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code @EnableDiscoveryClient}（两侧各一份）</td>
 *     <td>{@code @EnableDiscoveryClient}（1 份）</td>
 *     <td>同一开关，合并后只声明一次。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code @MapperScan("com.rag.users.user.rpc.mapper")}（<b>仅 rpc 侧</b>）</td>
 *     <td>{@code @MapperScan("com.rag.user.mapper")}</td>
 *     <td>ⓘ B 侧 api 侧<b>没有</b> {@code @MapperScan}（它不碰 DB）；C 侧合并后 Mapper 唯一落点是
 *         {@code com.rag.user.mapper} ⇒ <b>必须</b>扫。<br/>
 *         ⚠️ B 侧 javadoc 自述「application.yaml 注释里声明依赖它，但代码一直没加 → Mapper 注册不上」
 *         ⇒ 本仓<b>显式加上</b>（属 <b>纯 fail-closed 补齐</b>，不改任何运行时取值语义）。</td>
 *   </tr>
 * </table>
 *
 * <h2>⚠️ L4 idiomatization：B 侧 rpc 入口的 {@code @Import} 清单 → C 侧共享层 auto-config（D3 · starter 化）</h2>
 * <p>B 侧 {@code UserRpcApplication} 用 {@code @Import} 逐个拉 9 个共享件；C 侧<b>全部由
 * {@code common/} 的 auto-config 提供</b> ⇒ 域内<b>零 {@code @Import}</b>：
 * <table border="1">
 *   <tr><th>B 侧 {@code @Import} 项</th><th>C 侧落点（auto-config）</th></tr>
 *   <tr><td>{@code SnowflakeBean} + {@code SnowflakeProperties}</td><td>{@code SnowflakeAutoConfiguration}</td></tr>
 *   <tr><td>{@code LoggerFactoryBean}</td><td>{@code LoggerAutoConfiguration}</td></tr>
 *   <tr><td>{@code RpcServerInterceptorFactory}</td><td>{@code ObservabilityAutoConfiguration#grpcObservationInterceptor}</td></tr>
 *   <tr><td>{@code ObservabilityProperties}</td><td>{@code ObservabilityAutoConfiguration}（{@code @EnableConfigurationProperties}）</td></tr>
 *   <tr><td>{@code ObservabilityLifecycle}</td><td>{@code ObservabilityAutoConfiguration#observabilityLifecycle}</td></tr>
 *   <tr><td>{@code DeadlineQueryTimeoutInjector} + {@code SqlTimeoutStatusTranslator}</td><td>{@code PersistenceAutoConfiguration}</td></tr>
 *   <tr><td>{@code GoStyleProtectionConfiguration}</td><td>{@code ProtectionAutoConfiguration}</td></tr>
 * </table>
 *
 * <p>⚠️ <b>连 gRPC 拦截链的挂载点也一并上收</b>：B 侧 rpc 入口自己写了一个
 * {@code @Bean rpcInterceptorConfigurer(...)}（因为 devh starter <b>只认 {@code GrpcServerConfigurer}</b>，
 * 漏配就<b>静默失效</b>）⇒ C 侧由 {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer}
 * <b>唯一挂载</b>（域内 0 重复）⇒ ⇒ <b>链上必须有 recover → breaker → shedding → 业务观测这 4 层</b>，
 * 由 {@code common/} 的装配层测试保证（不在本域）。
 *
 * <h2>框架职责</h2>
 * <ul>
 *   <li>{@code @SpringBootApplication} = {@code @SpringBootConfiguration} + {@code @EnableAutoConfiguration}
 *       + {@code @ComponentScan}（默认扫启动类所在包 {@code com.rag.user} 及全部子包
 *       ⇒ controller / service / domain / interceptor / advice / config / metrics / grpc / wiring
 *       / contract / dto / exception 全部纳入）</li>
 *   <li>{@code @EnableAutoConfiguration} ⇒ 共享层 {@code common/} 的 14 个 auto-config 生效
 *       （含 gRPC 拦截链挂载、观测、熔断/降载、持久层超时注入、Jackson 强转、Redis 断连行为…）</li>
 *   <li>gRPC server 由 {@code grpc-server-spring-boot-starter} 自动起（{@code @GrpcService} 被扫描到即可）</li>
 * </ul>
 *
 * <p>📌 <b>相关台账</b>：本类的装配形态偏离登记在
 * {@code docs/Intentional_Deviations/users/}（D1 合并 + D3 starter 化）；
 * 行为层（L1/L2）无偏离。
 */
@SpringBootApplication
@EnableConfigurationProperties({UserApiProperties.class, UserRpcProperties.class})
@EnableDiscoveryClient
@MapperScan("com.rag.user.mapper")
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
