package com.rag.hot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <p><b>hot 域的唯一启动入口</b> —— 一个进程内同时起 Tomcat（HTTP 面 8895）
 * 与 gRPC server（业务面 8083）。
 *
 * <p><b>B 出处</b>：B 侧<b>两个</b>入口类
 * （{@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/HotApiApplication.java}
 * ＋ {@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/HotRpcApplication.java}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>D1 合并</b> —— B 侧是<b>两个</b>进程、<b>两个</b>入口类
 *       ⇒ 本仓合并为<b>一个</b>。具体合并口径：
 *       <ul>
 *         <li><b>包扫描</b>：B 侧 api 扫 {@code com.rag.hot.hot.api} + {@code com.rag.common}；
 *             本仓<b>只扫 {@code com.rag.hot}</b>（{@code @SpringBootApplication} 的默认包 = 本类所在包）
 *             ⇒ ⇒ <b>刻意不扫 {@code com.rag.common}</b>：共享层在本仓是 <b>starter</b>（D3），
 *             由 {@code AutoConfiguration.imports} 自动装配 ⇒ 再扫一遍会<b>双注册</b>
 *             （各 auto-config 都带 {@code @ConditionalOnMissingBean} 兜底，但重复扫描是自找麻烦）。
 *             <br>⚠️ <b>副作用（刻意）</b>：B 侧的 rpc 进程<b>不带 web 容器</b>，
 *             本仓合并后<b>必然带</b> ⇒ ⇒ 共享层那批<b>按 {@code rag.side} 分边</b>的 web 能力
 *             必须落在 <b>api 侧</b>（即<b>不配</b> {@code rag.side: rpc}）
 *             ⇒ 否则 CPU 降载 / 熔断那几道闸门会挂到 hot 的 HTTP 面上
 *             ⇒ <b>B 侧 hot-api 带着它们，B 侧 hot-rpc 不带</b>（B 侧 rpc 是纯 gRPC 进程）
 *             ⇒ ⇒ 合并后<b>只能取 api 侧的形态</b>（热榜 HTTP 端点需要这四道闸门）。
 *             ⇒ 登记在 hot 域 DRIFT 台账。</li>
 *         <li><b>{@code @Import} 清单</b>：B 侧 hot-rpc 显式 {@code @Import} 了 4 个共享层类
 *             （{@code RpcServerInterceptorFactory} / {@code ObservabilityProperties} /
 *             {@code Jackson2ObjectMapperConfiguration} / {@code GoStyleProtectionConfiguration}）
 *             ⇒ 本仓<b>全部由共享层的 auto-config 提供</b>
 *             ⇒ ⇒ <b>本类的 {@code @Import} 清单为空</b>（D3 starter 化的直接结果 · A5）。</li>
 *         <li><b>gRPC 拦截器链</b>：B 侧 hot-rpc 自己写了 {@code rpcInterceptorConfigurer}
 *             ⇒ 本仓由共享层的 {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer} 承担
 *             （devh starter 只认 {@code GrpcServerConfigurer}，域内再来一份会挂 2 条链）
 *             ⇒ ⇒ <b>本类不写这条 bean</b>。</li>
 *         <li><b>{@code Jackson2ObjectMapperConfiguration}</b>：B 侧那个类是 <b>Boot 4 拆模块</b>
 *             （Jackson 2 / 3）的补丁 ⇒ 本仓是 Jackson 2 + Boot 3.5（D14）
 *             ⇒ 由共享层的 {@code JsonBodyConverterAutoConfiguration} 统一承担
 *             ⇒ ⇒ <b>本类不引它</b>。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>{@code @EnableDiscoveryClient}（Nacos 自动发现）保留；</li>
 *         <li>{@code @EnableScheduling}（B 侧 hot-rpc 的两条裸 goroutine ticker）
 *             ⇒ 打开 30s 快照（{@code HotRedisSnapshotStore#periodicSync}）
 *             与 10s 合并（{@code HotMergeScheduler#periodicMerge}）；</li>
 *         <li>{@code @ConfigurationPropertiesScan("com.rag.hot.config")} ⇒ 绑定
 *             {@link com.rag.hot.config.HotApiProperties}（{@code hot-api.*}）与
 *             {@link com.rag.hot.config.HotRpcProperties}（{@code hot-rpc.*}）
 *             —— <b>两段前缀都保留</b>（B 侧就是两份 yml 各有一份）⇒ ⇒ <b>配置键逐字不变</b>；
 *             ⚠️ <b>这一行是硬要求</b>：两个配置类<b>刻意都不带 {@code @Component}</b>
 *             （若同时带 {@code @Component} 又被本扫描扫到 ⇒ 同一个类型注册<b>两个</b> bean
 *             ⇒ 构造器注入时 {@code NoUniqueBeanDefinitionException}）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>{@code .../hot/api/HotApiApplication.java:69-82}（类声明 + {@code main}）；</li>
 *         <li>{@code .../hot/rpc/HotRpcApplication.java:61-113}
 *             （类声明 + {@code rpcInterceptorConfigurer} + {@code main}）。</li>
 *       </ul></li>
 * </ol>
 *
 * <p><b>⚠️ 端口是 D1 合并带来的 L1 差异</b>：B 侧 api = HTTP {@code 18897}、rpc = gRPC {@code 8083}
 * （rpc 的 HTTP 观测口 {@code 19091}）；本仓合并后 = HTTP {@code 8895} + gRPC {@code 8083}
 * ⇒ ⇒ 该差异<b>由 D1 决策拍板</b>，须在 hot 域 DRIFT 台账登记（端口属 L1）。
 *
 * <p><b>⚠️ gRPC server 由 devh starter 自动装配</b>：{@code grpc/HotGrpcService} 上的
 * {@code @GrpcService} 由 starter 扫描注册 ⇒ <b>本类不手写任何 gRPC 注册代码</b>。
 *
 * <p><b>⚠️ 本类不引入 Lombok</b>：它没有任何字段与样板方法（只有 {@code main}）⇒ 无可消除的样板。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.rag.hot.config")
@EnableScheduling
@EnableDiscoveryClient
public class HotServiceApplication {

    /**
     * 进程入口 —— 对应 B 侧两个 {@code main}（api 的与 rpc 的）合并后的唯一入口。
     *
     * <p><b>B 出处</b>：{@code .../hot/api/HotApiApplication.java:79-81}
     * ＋ {@code .../hot/rpc/HotRpcApplication.java:111-113}（两段 {@code main} 体逐字相同：
     * {@code SpringApplication.run(XxxApplication.class, args)}）。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(HotServiceApplication.class, args);
    }
}
