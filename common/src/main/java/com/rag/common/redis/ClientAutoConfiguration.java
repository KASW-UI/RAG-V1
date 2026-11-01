package com.rag.common.redis;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
// ⚠️ **D14 · Boot 4 → Boot 3.5 包路径回改**：RAG 侧（Boot 4.0.8）用
//    `org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer`
//    （Boot 4 把 redis 自动配置拆到了独立模块）⇒ Boot 3.5.11 里它回到
//    `spring-boot-autoconfigure` 的 `org.springframework.boot.autoconfigure.data.redis` 包。
//    实测：本仓 ~/.m2 的 spring-boot-autoconfigure-3.5.11.jar 内
//    `org/springframework/boot/autoconfigure/data/redis/LettuceClientOptionsBuilderCustomizer.class` 存在。
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * <b>L4 idiomatization</b>：把 Lettuce 客户端的「<b>断连行为</b>」收进共享层，
 * 对齐 Go 的 <b>Redis 失败 ⇒ 尽快失败 ⇒ 上层 fail-open</b> 语义。
 *
 * <p><b>Go 出处（消费点，不是声明点）</b>：
 * <pre>
 *   service/user/user/api/internal/middleware/checkblacklistmiddleware.go:35
 *       exists, err := m.Redis.ExistsCtx(r.Context(), blackListKey)
 *   service/user/user/api/internal/middleware/checkblacklistmiddleware.go:36
 *       if err == nil &amp;&amp; exists { … 401 … }
 *         ⇒ err != nil 时<b>不拦截</b> ⇒ <b>fail-open 放行</b>
 *
 *   service/user/admin/api/internal/middleware/checkblacklistmiddleware.go  （admin 域同款）
 *   service/follow/api/internal/middleware/checkblacklistmiddleware.go       （follow 域同款）
 *     ⇒ 三份文件内容<b>不同</b>（md5 各异），但「err != nil ⇒ 放行」这一条<b>三份一致</b>。
 *
 *   core/stores/redis/redisclientmanager.go:18  maxRetries = 3
 *   core/stores/redis/redisclientmanager.go:27-46
 *       red.NewClient(&amp;red.Options{Addr, MaxRetries: maxRetries, MinIdleConns: idleConns})
 *       ⇒ <b>没有</b>设置 DialTimeout / ReadTimeout ⇒ 用 go-redis v9 默认值，
 *         且 MaxRetries=3 <b>明确开启重试</b>；连接被拒时每次重试都是<b>立即失败</b>
 *         ⇒ 整体<b>快速失败</b>（users 域实测 ~1.7s）。
 * </pre>
 *
 * <p><b>为什么 Lettuce 默认行为与 Go 不同（这是本类存在的唯一理由）</b>：
 * <ul>
 *   <li><b>Lettuce 默认</b>（{@link ClientOptions.DisconnectedBehavior#DEFAULT}）：
 *       断连期间到达的命令被<b>挂起排队</b>，等重连成功后补发
 *       ⇒ 一次黑名单查询要等到 {@code spring.data.redis.timeout}（users 域 3s）才失败
 *       ⇒ <b>请求超时预算先耗尽</b> ⇒ 对外 <b>503</b>（Go 是 <b>200</b>）。</li>
 *   <li><b>{@link ClientOptions.DisconnectedBehavior#REJECT_COMMANDS}</b>：
 *       断连时<b>立即拒单</b>（命令直接失败，不排队）
 *       ⇒ Redis 挂掉时失败耗时 ≪ 请求预算 ⇒ 进业务层 ⇒ <b>fail-open 200</b>，与 Go 一致。</li>
 * </ul>
 *
 * <p><b>语义等价论证</b>：
 * <ol>
 *   <li>两侧在「Redis 不可用」时都<b>尽快让命令失败</b>（Go：MaxRetries=3 + 立即失败的重试；
 *       RAG：断连即拒单）。</li>
 *   <li>失败都<b>被上层捕获并 fail-open</b>：Go 是 {@code if err == nil &amp;&amp; exists}；
 *       RAG 侧各 api 的黑名单拦截器是 {@code try { exists = redis.hasKey(key); }
 *       catch (Throwable) { /* 放行 *\/ }}（例如 users 域
 *       {@code JwtAuthInterceptor.isBlacklisted}）。</li>
 *   <li>⇒ <b>对外都是「放行 + 业务层正常响应」</b>。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么这一段曾经是「users 域私产」，现在要上移</b>：
 * {@code service/users/user/user-api} 里有一份同款配置
 * （{@code RedisDisconnectedBehaviorConfig}，2026-10-24 第九轮修 {@code DRIFT-USERAPI-014} 时落的）。
 * 但<b>黑名单 fail-open 是所有域共有的需求</b>，而那份配置<b>只对 user-api 生效</b>：
 * <ul>
 *   <li><b>follow-api</b>：{@code FollowBlacklistInterceptor} 同样查 Redis 黑名单
 *       ⇒ Redis 断连时**同样**会撞超时预算 ⇒ <b>同样的漂移</b>；</li>
 *   <li><b>favorite-api</b>：{@code FavoriteJwtAuthInterceptor} 同款；</li>
 *   <li><b>各 rpc 组件</b>：comment / like / hot / task 的缓存读写在 Redis 断连时
 *       同样会<b>挂起</b>而不是快速失败 ⇒ 与 Go 的「快速失败」不一致。</li>
 * </ul>
 * ⇒ 上移到共享层 ⇒ <b>零域侧改动</b>即让<b>没有域内副本</b>的用 Redis 组件获得 Go 的失败语义。
 *
 * <p><b>⚠️ 域内副本与本类并存时，是「遮蔽」不是「叠加」（2026-10-27 实测订正）</b>：
 * 本类的 {@code @Bean} 带 {@code @ConditionalOnMissingBean} ⇒
 * <b>域内一旦已定义 {@code LettuceClientOptionsBuilderCustomizer}，共享层这份 Bean 根本不会被创建</b>
 * ⇒ 容器里<b>只有一份</b> customizer ⇒ <b>不存在「后者覆盖前者」的叠加顺序问题</b>。
 * ⇒ 净效果：断连行为仍为 {@code REJECT_COMMANDS}（域内副本与本类<b>逐字同值</b>）⇒ <b>对外行为零变化</b>；
 * ⚠️ <b>但代价是「遮蔽」</b>：共享层的任何后续改动（例如 E66 的 {@code timeoutOptions}）
 * 在<b>仍有域内副本的域</b>会<b>静默不生效</b> ⇒ ⇒ <b>域内副本必须删除</b>，删除才是真正的收编。
 * 删除前须逐条核对两侧<b>同值</b>（本仓现状：<b>五份</b>域内副本 —— user-api / user-rpc /
 * admin-api / admin-rpc / follow-api —— 都只设
 * {@code disconnectedBehavior(REJECT_COMMANDS)}、都<b>不碰</b> {@code timeoutOptions} ⇒ 纯删除、零行为变化）。
 *
 * <p><b>⚠️ 为什么用自动配置而不是 {@code @Component}</b>：
 * 本类引用 {@link io.lettuce.core.ClientOptions} 与 {@link LettuceConnectionFactory}
 * —— 而<b>纯 gRPC 组件与不使用 Redis 的 api 组件根本没有这两个类</b>
 * ⇒ 若用 {@code @Component}，组件扫描会在这类进程里<b>硬失败</b>
 * （{@code NoClassDefFoundError}）⇒ 必须用 {@code @ConditionalOnClass} + 自动配置清单。
 *
 * <p><b>⚠️ 为什么不动命令超时（{@code timeoutOptions}）</b>：
 * Boot 的 {@code LettuceConnectionConfiguration} <b>先</b>把
 * {@code spring.data.redis.timeout} 写进 {@code ClientOptions.Builder.timeoutOptions}，
 * <b>再</b>依次应用 customizer ⇒ customizer 里再调一次 {@code timeoutOptions(...)}
 * 会<b>整体覆盖</b>掉运维配的命令超时 ⇒ 那才是真的新漂移。
 * ⇒ 本类<b>只</b>改断连行为，<b>不碰</b>超时。
 * （「Redis 命令感知请求 deadline」= global 台账 <b>E66</b>，属另一条，
 * 需要在保留既有命令超时的前提下另立一轮，见 {@code docs/DRIFT/global/vs-common.md}。）
 */
@AutoConfiguration
@ConditionalOnClass({LettuceConnectionFactory.class, ClientOptions.class,
        LettuceClientOptionsBuilderCustomizer.class})
public class ClientAutoConfiguration {

    /**
     * 注册「断连即拒单」的客户端选项定制器。
     *
     * <p>⚠️ Bean 名固定为 {@code goStyleRedisClientOptionsCustomizer}
     * ⇒ 便于守门按名字精确断言「共享层这一份确实在容器里」。
     *
     * <p>⚠️ <b>为什么有 {@code @ConditionalOnMissingBean}</b>（2026-10-27 字节码级订正）
     * ⇒ <b>原注释的理由是错的</b>：它说「本类会被组件扫描与自动配置清单各注册一次 ⇒ 启动直接失败」。
     * ⇒ <b>实测不成立</b>（Boot 4.0.8）：
     * <ul>
     *   <li>{@code @AutoConfiguration} <b>元注解就是 {@code @Configuration}</b>
     *       （{@code javap} ⇒ {@code @AliasFor(annotation = Configuration.class)}）；</li>
     *   <li>而 {@code AutoConfigurationExcludeFilter}（{@code @SpringBootApplication} 内建的
     *       {@code @ComponentScan} 排除过滤器之一；{@code task-api} 甚至显式把它重写了一遍）
     *       的判据是：<b>既是 {@code @Configuration}、又出现在 {@code AutoConfiguration.imports} 清单里</b>
     *       ⇒ <b>本类被排除出组件扫描</b>；</li>
     *   <li>⇒ 本类<b>只经自动配置清单注册一次</b> ⇒ <b>不存在双重注册</b>
     *       ⇒ 原注释描述的「启动直接失败」<b>不成立</b>。</li>
     * </ul>
     * ⇒ <b>本条件注解的真实作用</b>：<b>让域内副本优先</b> ——
     * 域内若已定义 {@code LettuceClientOptionsBuilderCustomizer}，本层<b>退让</b>、不叠加第二份
     * ⇒ 容器里恒为<b>恰好一份</b> customizer（守门按此断言）。
     * ⚠️ <b>代价 = 遮蔽</b>：域内副本存在时本层在该域<b>完全不生效</b>
     * ⇒ 共享层的任何后续改动（例如 E66 的 {@code timeoutOptions}）在该域<b>静默不生效</b>
     * ⇒ ⇒ <b>域内副本必须删除</b>，删除才是真正的收编。
     * 守门：{@code GoStyleRedisClientOptionsPrecedenceGateTest}（钉住「遮蔽」而非「叠加」这个契约）。
     *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/redis/GoStyleRedisClientAutoConfiguration.java}
 * （映射表 §1.12 第 279 行 · 1:1 · RAG 侧类名 {@code GoStyleRedisClientAutoConfiguration}）
 * <p><b>⚠️ 落点差异</b>：<b>去 {@code Redis} 前缀</b> —— 包名已含 {@code redis}，类名再带前缀即冗余（命名债 P4-④ 同款处置）
 * ⇒ ⇒ **调用点要同步改 import 与类名**。
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
    @Bean
    @ConditionalOnMissingBean
    public LettuceClientOptionsBuilderCustomizer goStyleRedisClientOptionsCustomizer() {
        return builder -> builder.disconnectedBehavior(
                ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
