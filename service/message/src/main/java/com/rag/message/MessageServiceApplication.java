package com.rag.message;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * <p><b>职责</b>：message 域的<b>唯一启动入口</b> —— 一个进程内同时起 Tomcat（HTTP 面 8883）
 * 与 gRPC server（业务面 8084）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>D1 合并</b> —— B 侧是<b>两个</b>进程、<b>两个</b>入口类
 *       （{@code MessageApiApplication} + {@code MessageRpcApplication}）⇒ 本仓合并为<b>一个</b>。
 *       具体合并口径：
 *     <ul>
 *       <li><b>包扫描</b>：B 侧 admin-api 扫 {@code com.rag.users.admin.api} + {@code com.rag.common}；
 *           本仓<b>只扫 {@code com.rag.message}</b>（{@code @SpringBootApplication} 的默认包 = 本类所在包）
 *           ⇒ ⇒ <b>刻意不扫 {@code com.rag.common}</b>：共享层在本仓是 <b>starter</b>（D3），
 *           由 {@code AutoConfiguration.imports} 自动装配 ⇒ 再扫一遍会<b>双注册</b>
 *           （各 auto-config 都带 {@code @ConditionalOnMissingBean} 兜底，但重复扫描是自找麻烦）。</li>
 *       <li><b>{@code @Import} 清单</b>：B 侧 message-rpc 显式 {@code @Import} 了 8 个共享层类
 *           （{@code SnowflakeBean} / {@code LoggerFactoryBean} / {@code RpcServerInterceptorFactory} /
 *           {@code ObservabilityProperties} / {@code ObservabilityLifecycle} /
 *           {@code DeadlineQueryTimeoutInjector} / {@code SqlTimeoutStatusTranslator} /
 *           {@code GoStyleProtectionConfiguration}）⇒ 本仓<b>全部由共享层的 auto-config 提供</b>
 *           （{@code SnowflakeAutoConfiguration} / {@code LoggerAutoConfiguration} /
 *           {@code ObservabilityAutoConfiguration} / {@code PersistenceAutoConfiguration} /
 *           {@code ProtectionAutoConfiguration}）
 *           ⇒ ⇒ <b>本类的 {@code @Import} 清单为空</b>（D3 starter 化的直接结果）。</li>
 *       <li><b>gRPC 拦截器链</b>：B 侧 message-rpc 自己写了 {@code rpcInterceptorConfigurer}
 *           （把 {@code ServerInterceptor} 挂到 gRPC server 上）⇒ 本仓由共享层的
 *           {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer} 承担
 *           （devh starter 只认 {@code GrpcServerConfigurer}，域内再来一份会挂 2 条链）⇒
 *           ⇒ <b>本类不写这条 bean</b>。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。
 *     <ul>
 *       <li>{@code @EnableDiscoveryClient}（Nacos 自动发现）保留 ⇒ 与 B 侧 message-rpc 同款；</li>
 *       <li>{@code @ConfigurationPropertiesScan("com.rag.message.config")} ⇒ 绑定
 *           {@link com.rag.message.config.MessageApiProperties}（{@code message-api.*}）与
 *           {@link com.rag.message.config.MessageProperties}（{@code message.*}）
 *           —— <b>两段前缀都保留</b>（B 侧就是两份 yml 各有一份）⇒ ⇒ <b>配置键逐字不变</b>；
 *           ⚠️ <b>这一行是硬要求</b>：两个配置类<b>刻意都不带 {@code @Component}</b>
 *           （若同时带 {@code @Component} 又被本扫描扫到 ⇒ 同一个类型注册<b>两个</b> bean
 *           ⇒ 构造器注入时 {@code NoUniqueBeanDefinitionException}）⇒ ⇒ 缺了本行，
 *           {@code MessageApiProperties} 的 <b>fail-closed 守卫</b>（两段 secret 缺失 ⇒ 启动失败）
 *           与 {@code MessageProperties} 的 {@code list.*} / {@code broadcast.*} 绑定<b>全部失效</b>；</li>
 *       <li>{@code @MapperScan("com.rag.message.mapper")} ⇒ 注册 {@code NotificationMapper} /
 *           {@code ConversationMapper} / {@code ConversationMessageMapper}
 *           （B 侧 message-rpc 同款；B 侧 DRIFT-MSG-005 的教训：没有它这三个接口不会成为 bean，
 *           报错是 {@code No qualifying bean of type '…mapper.NotificationMapper' available}）。</li>
 *     </ul></li>
 *   <li><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/MessageApiApplication.java}
 *       （1-83 行；类声明 {@code :63-77}；{@code main} {@code :79-81}）
 *       ＋ {@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/MessageRpcApplication.java}
 *       （1-141 行；类声明 {@code :64-98}；{@code rpcInterceptorConfigurer} {@code :114-136}；
 *       {@code main} {@code :138-140}）。</li>
 * </ol>
 *
 * <p><b>⚠️ 本模块同时起 Tomcat 与 gRPC</b>（D1）：HTTP 面 = {@code server.port}（8883），
 * gRPC 面 = {@code grpc.server.port}（8084）⇒ ⇒ Nacos 自动注册会取到 <b>web 端口</b> ⇒
 * ⇒ <b>必须</b>关掉自动注册（{@code spring.cloud.nacos.discovery.register-enabled: false}），
 * 由 {@link com.rag.message.wiring.NacosRegistration} 用 <b>gRPC 端口</b>显式注册
 * （B 侧 message-rpc yml 的同款要求；本仓 {@code application.yml} <b>尚未补上该行</b> ——
 * 归 yml 的 owner，见该类 javadoc 的「待补」小节）。
 *
 * <p><b>⚠️ 端口是 D1 合并带来的 L1 差异</b>：B 侧 api = HTTP {@code 8892}、rpc = gRPC {@code 8082}；
 * 本仓合并后 = HTTP {@code 8883} + gRPC {@code 8084}（见 {@code application.yml}）。
 * 该差异<b>由 D1 决策拍板</b>，须在 message 域 DRIFT 台账登记（端口属 L1）。
 *
 * <p><b>⚠️ gRPC server 由 devh starter 自动装配</b>：{@code grpc/MessageGrpcService} 上的
 * {@code @GrpcService} 由 starter 扫描注册 ⇒ <b>本类不手写任何 gRPC 注册代码</b>。
 *
 * <p><b>⚠️ 本类不引入 Lombok</b>：它没有任何字段与样板方法（只有 {@code main}）⇒ 无可消除的样板。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.rag.message.config")
@MapperScan("com.rag.message.mapper")
@EnableDiscoveryClient
public class MessageServiceApplication {

    /**
     * 进程入口 —— 对应 B 侧两个 {@code main}（api 的与 rpc 的）合并后的唯一入口。
     *
     * <p><b>B 出处</b>：{@code .../api/MessageApiApplication.java:79-81}
     * ＋ {@code .../rpc/MessageRpcApplication.java:138-140}（两段 {@code main} 体逐字相同：
     * {@code SpringApplication.run(XxxApplication.class, args)}）。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MessageServiceApplication.class, args);
    }
}
