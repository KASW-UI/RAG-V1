package com.rag.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * <p><b>职责</b>：admin 域的<b>唯一启动入口</b> —— 一个进程内同时起 Tomcat（HTTP 面）与 gRPC server（业务面）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>D1 合并</b> —— B 侧是<b>两个</b>进程、<b>两个</b>入口类
 *       （{@code AdminApiApplication} + {@code AdminRpcApplication}）⇒ 本仓合并为<b>一个</b>。
 *       具体合并口径：
 *     <ul>
 *       <li><b>包扫描</b>：B 侧 admin-api 扫 {@code com.rag.users.admin.api} + {@code com.rag.common}；
 *           本仓<b>只扫 {@code com.rag.admin}</b>（{@code @SpringBootApplication} 的默认包 = 本类所在包）
 *           ⇒ ⇒ <b>刻意不扫 {@code com.rag.common}</b>：共享层在本仓是 <b>starter</b>（D3），
 *           由 {@code AutoConfiguration.imports} 自动装配 ⇒ 再扫一遍会<b>双注册</b>
 *           （各 auto-config 都带 {@code @ConditionalOnMissingBean} 兜底，但重复扫描是自找麻烦）。</li>
 *       <li><b>{@code @Import} 清单</b>：B 侧 admin-rpc 显式 {@code @Import} 了 9 个共享层类
 *           （{@code SnowflakeBean} / {@code LoggerFactoryBean} / {@code RpcServerInterceptorFactory} /
 *           {@code ObservabilityProperties} / {@code ObservabilityLifecycle} /
 *           {@code DeadlineQueryTimeoutInjector} / {@code SqlTimeoutStatusTranslator} /
 *           {@code GoStyleProtectionConfiguration} 等）⇒ 本仓<b>全部由共享层的 auto-config 提供</b>
 *           ⇒ ⇒ <b>本类的 {@code @Import} 清单为空</b>（D3 starter 化的直接结果）。</li>
 *       <li><b>gRPC 拦截器链</b>：B 侧 admin-rpc 自己写了 {@code rpcInterceptorConfigurer}
 *           （把 {@code ServerInterceptor} 挂到 gRPC server 上）⇒ 本仓由共享层的
 *           {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer} 承担
 *           （devh starter 只认 {@code GrpcServerConfigurer}，域内再来一份会挂 2 条链）⇒
 *           ⇒ <b>本类不写这条 bean</b>。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。
 *     <ul>
 *       <li>{@code @EnableDiscoveryClient}（Nacos 自动发现）保留 ⇒ 与 B 侧 admin-rpc 同款；</li>
 *       <li>{@code @ConfigurationPropertiesScan("com.rag.admin.config")} ⇒ 绑定
 *           {@code AdminApiProperties}（{@code admin-api.*}）与 {@code AdminRpcProperties}
 *           （{@code admin-rpc.*}）—— <b>两段前缀都保留</b>（B 侧就是两份 yml 各有一份）⇒
 *           ⇒ <b>配置键逐字不变</b>；</li>
 *       <li>{@code @MapperScan("com.rag.admin.mapper")} ⇒ 注册 {@code AdminMapper} /
 *           {@code AdminInviteMapper} / {@code UserMapper}（B 侧 admin-rpc 同款；没有它这三个接口不会成为 bean）。</li>
 *     </ul></li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/AdminApiApplication.java}
 *       ＋ {@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/AdminRpcApplication.java}。</li>
 * </ol>
 *
 * <p><b>⚠️ 本模块同时起 Tomcat 与 gRPC</b>（D1）：HTTP 面 = {@code server.port}（8889），
 * gRPC 面 = {@code grpc.server.port}（8082）⇒ ⇒ Nacos 自动注册会取到 <b>web 端口</b> ⇒
 * ⇒ <b>必须</b>关掉自动注册（{@code spring.cloud.nacos.discovery.register-enabled: false}），
 * 由 {@code wiring/NacosRegistration} 用 <b>gRPC 端口</b> 显式注册（B 侧 admin-rpc yml:119 有同一行，
 * 本仓的 {@code application.yml} <b>已补上</b> —— 见该文件的 Nacos 段注释）。
 *
 * <p><b>⚠️ gRPC server 由 devh starter 自动装配</b>：{@code grpc/AdminGrpcService} 上的
 * {@code @GrpcService} 由 starter 扫描注册 ⇒ <b>本类不手写任何 gRPC 注册代码</b>。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.rag.admin.config")
@MapperScan("com.rag.admin.mapper")
@EnableDiscoveryClient
public class AdminServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminServiceApplication.class, args);
    }
}
