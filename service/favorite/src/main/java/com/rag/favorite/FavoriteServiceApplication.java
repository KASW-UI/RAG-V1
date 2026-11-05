package com.rag.favorite;

import com.rag.favorite.config.FavoriteApiProperties;
import com.rag.favorite.config.FavoriteRpcProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * <p><b>favorite 域应用入口</b> —— 对应 B 侧<b>两个</b>入口
 * {@code favorite-api/.../FavoriteApiApplication.java}（47 行）
 * ＋ {@code favorite-rpc/.../FavoriteRpcApplication.java}（113 行）
 * ＋ <b>三个</b> {@code cmd/fact-*} 子命令入口。
 *
 * <p><b>抄写基准</b>：B 侧上述入口类（**D1**：api + rpc 合并为单一 Spring Boot 应用）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li><b>多个入口合并成一个</b>（D1）—— B 侧是 <b>2 个进程</b>（api + rpc）
 *           ＋ <b>3 个 fact 子命令</b>（fact-dispatch / fact-authority / fact-legacy-preflight）；
 *           C 侧是<b>单进程</b>（fact 组件落成域内 {@code wiring/}）。</li>
 *       <li>B 侧 {@code @Import} 清单里的 10 个类（{@code SnowflakeBean} /
 *           {@code SnowflakeProperties} / {@code LoggerFactoryBean} /
 *           {@code RpcServerInterceptorFactory} / {@code ObservabilityProperties} /
 *           {@code ObservabilityLifecycle} / {@code DeadlineQueryTimeoutInjector} /
 *           {@code SqlTimeoutStatusTranslator} / {@code GoStyleProtectionConfiguration}）
 *           ⇒ <b>全部由共享层的 {@code @AutoConfiguration} 承担</b>（D3 · starter 化）
 *           ⇒ ⇒ <b>本类不再需要 {@code @Import} 清单</b>（D3 的核心收益之一）
 *           ⇒ ⇒ **架构断言 A5「@Import 清单必须为空」得以保持 PASS**。</li>
 *       <li>B 侧的 {@code rpcInterceptorConfigurer} Bean ⇒ <b>由共享层的
 *           {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer} 承担</b>
 *           （**唯一**挂载点）⇒ ⇒ <b>架构断言 A3「域内 0 重复」PASS**。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 7 个 gRPC 方法 + 7 个 HTTP 端点 + Mapper 扫描范围
 *       + 配置属性绑定 + Nacos 注册，逐条未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 四个「不写就静默失效 / 直接起不来」的装配点</h2>
 * <ol>
 *   <li><b>{@code @MapperScan}</b> — 缺它 4 个 Mapper 全部注册不上，
 *       报 {@code No qualifying bean of type '…mapper.FavoriteFolderMapper'}，
 *       上下文直接起不来。</li>
 *   <li><b>{@code @EnableConfigurationProperties}</b> — 没有它
 *       {@code FavoriteRpcProperties}（{@code favorite.*}）不会被绑定，
 *       于是 {@code subjectRefV2Facts} 恒为 false、三个 fact 开关恒为关闭，
 *       <b>且没有任何报错</b>。</li>
 *   <li><b>{@code @EnableDiscoveryClient}</b> — 缺它 Nacos 服务发现不生效
 *       ⇒ 跨域客户端（{@code user-rpc} / {@code article-rpc}）解析不到下游。</li>
 *   <li><b>{@code FavoriteApiProperties} 的密钥守卫</b> —
 *       {@code @PostConstruct verifyAccessSecret()} 在缺 {@code FAVORITE_JWT_SECRET}
 *       / 空串 / 未解析占位符时<b>启动失败</b> ⇒ 这是 <b>B 侧同款的 fail-closed</b>
 *       （B 侧 {@code FavoriteApiProperties.java:79-113}）⇒ <b>不是缺陷</b>。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么<b>不</b>用 {@code @ConfigurationPropertiesScan}</h2>
 * <p>B 侧用的是 {@code @ConfigurationPropertiesScan(basePackages = "com.rag.favorite.favorite.rpc.config")}。
 * <p>⚠️ <b>D7 包压平</b>后 C 侧的配置类在 {@code com.rag.favorite.config}
 * ⇒ 照抄「扫 {@code config} 包」<b>可行</b>，但会有一个隐患：{@code @ConfigurationPropertiesScan}
 * 与 {@code @Component} <b>同时</b>作用于同一个类时会<b>注册两个 bean</b>
 * ⇒ {@code NoUniqueBeanDefinitionException}。
 * <p>⇒ ⇒ 本仓采用<b>显式列举</b>（与 like 域 / follow 域 / users 域同款）：
 * 把两个 {@code @ConfigurationProperties} 类都写进 {@code @EnableConfigurationProperties}
 * ⇒ <b>不受包压平影响</b>，也不会与组件扫描重复注册。
 * <br>⚠️ **前提</b>：两个 Properties 类<b>都不得</b>再标 {@code @Component}
 * （like 域的 {@code LikeApiProperties} 同样不标）⇒ 若将来有人加了 {@code @Component}，
 * **必须**同时把这里改成 {@code @ConfigurationPropertiesScan}（二选一，**不可 both**）。
 *
 * <h2>⚠️ 为什么<b>不</b>需要 {@code @EnableScheduling}</h2>
 * <p>B 侧明确写了不需要（like-rpc 需要它是因为 Kafka 的 ticker relay）。
 * favorite 侧的事实投递由 {@code FavoriteFactDispatchScheduler} <b>自己起线程</b>
 * （B 侧是独立进程的 {@code for { … select { case <-ctx.Done() / case <-ticker.C } }}）
 * ＋ {@code @PreDestroy} 停循环，<b>不依赖</b> Spring 的调度器 ⇒ 不引入调度基础设施。
 */
@SpringBootApplication
@EnableConfigurationProperties({FavoriteApiProperties.class, FavoriteRpcProperties.class})
@EnableDiscoveryClient
@MapperScan("com.rag.favorite.mapper")
public class FavoriteServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FavoriteServiceApplication.class, args);
    }
}
