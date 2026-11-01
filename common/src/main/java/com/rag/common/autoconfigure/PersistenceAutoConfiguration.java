package com.rag.common.autoconfigure;

import com.rag.common.persistence.DeadlineQueryTimeoutInjector;
import com.rag.common.persistence.SqlTimeoutStatusTranslator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * <p><b>持久层超时链路的<b>唯一</b>装配点</b>（MyBatis 拦截器 ⇒ MP 自动配置收集 ⇒ 真正生效）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ 为什么必须有这个类（DRIFT-COMMON-V02 的修法）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p>两个拦截器类<b>早就实现好了</b>（批 1 落地）：
 * <ul>
 *   <li>{@link DeadlineQueryTimeoutInjector} —— 按线程上的 deadline 给每条 SQL 设 query timeout
 *       ⇒ <b>取消时刻 == deadline 时刻</b>（对齐 B 的 ctx-aware 查询 ⇒ pgx CancelRequest）</li>
 *   <li>{@link SqlTimeoutStatusTranslator} —— 把 pg 的 {@code 57014 query_canceled}
 *       翻成 gRPC {@code DEADLINE_EXCEEDED} ⇒ 域侧落业务码 <b>1015</b>（不是 DB 故障 5001）</li>
 * </ul>
 * <p>但<b>它们没有任何注册点</b> ⇒ MyBatis-Plus 收不到 ⇒ <b>整条链静默失效</b>：
 * <ul>
 *   <li>SQL <b>不带超时</b>（长查询不被取消）；</li>
 *   <li>pg 的取消被当「数据库故障」上报（5001 而非 B 的 1015）。</li>
 * </ul>
 * <p>⚠️ <b>RAG 侧为什么不需要这个类</b>：RAG 的注册义务在<b>域内</b> ——
 * 5 个 rpc 应用各自用 {@code @Bean} 声明这两个拦截器
 * （{@code FollowRpcApplication:90} / {@code MessageRpcApplication:85} / {@code LikeRpcApplication:94} /
 * {@code ArticleRpcApplication:88} / {@code UserRpcApplication:67}）。
 * <br>⇒ ⇒ RAG-V1 是 <b>starter 化</b>形态（D3）⇒ 域不再逐个声明 ⇒ <b>注册义务必须上收到共享层</b>
 * ⇒ ⇒ 落在本类。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ 为什么「注册成 Bean」就等于「真的生效」（机制可证 · §3.3）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>实测依据</b>（{@code javap -c}，MyBatis-Plus 3.5.17
 * {@code MybatisPlusAutoConfiguration}）：
 * <pre>
 *   public MybatisPlusAutoConfiguration(..., ObjectProvider&lt;Interceptor[]&gt; interceptors, ...)
 *       11: invokeinterface  ObjectProvider.getIfAvailable:()Ljava/lang/Object;
 *       16: checkcast       [Lorg/apache/ibatis/plugin/Interceptor;
 *       19: putfield        interceptors
 * </pre>
 * ⇒ ⇒ MP 在<b>构造器里即时</b>解析容器里<b>全部</b> {@code Interceptor} Bean
 * ⇒ ⇒ 只要本类把两个拦截器注册成 Bean，MP 就一定收得到
 * ⇒ ⇒ <b>不需要</b>逐域声明，也<b>不需要</b>碰 {@code MybatisPlusProperties}。
 * <br>⇒ ⇒ 断言见 {@code common/src/test/java/com/rag/common/autoconfigure/PersistenceAutoConfigurationTest.java}
 * （用<b>与 MP 同一个</b> {@code ObjectProvider<Interceptor[]>} 注入点取证）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L4 idiomatization（RAG 的「域内 5 处 @Bean」→ 本共享层装配点）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <ol>
 *   <li><b>注册点从域内上收到共享层</b>：RAG 侧是 5 份重复的 {@code @Bean}（D3 之后会变成 10 份）
 *       ⇒ ⇒ 本仓 1 份 ⇒ <b>10 个域一次全挂上</b>；</li>
 *   <li><b>用 {@code @ConditionalOnMissingBean} 保证容器里最多一份</b>
 *       ⇒ 域若要覆盖（例如换成自己的超时口径），域内 {@code @Bean} 会让本类让位；</li>
 *   <li><b>用字符串形式的 {@code @ConditionalOnClass}</b>
 *       ⇒ 没有 MyBatis 的进程加载本类时<b>不碰</b> {@code Interceptor} 类型（避免 {@code NoClassDefFoundError}）。</li>
 * </ol>
 *
 * <p><b>B 出处</b>（两个拦截器自己的 javadoc 已逐条标注，此处只指认<b>消费点</b>）：
 * <pre>
 *   service/comment/rpc/internal/model/comment.go:36 / :166
 *       m.conn.WithContext(ctx).Transaction(...) / .Model(...)
 *   ⇒ database/sql 把 ctx 交给 driver ⇒ pgx 在 ctx 到点时向 PG 发 CancelRequest
 *   ⇒ B 侧 标准库 DB.conn(ctx, …) 在**取连接之前**就查 ctx.Err()
 *   ⇒ ctx 已过期 ⇒ 查询根本不会发到服务端 ⇒ 返回 context.DeadlineExceeded
 * </pre>
 *
 * <p><b>抄写基准</b>：两个拦截器类是 RAG {@code service/common/persistence/} 的**整段抄**（L2 · D13）；
 * <b>装配形态</b>由「域内 5 处 {@code @Bean}」改为「共享层 1 个 auto-config」（L4 · D3）。
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.apache.ibatis.plugin.Interceptor")
public class PersistenceAutoConfiguration {

    /**
     * SQL 超时注入器：按线程上的 deadline 设 {@code setQueryTimeoutMs}。
     *
     * <p>⚠️ <b>没有 deadline 时完全不碰 statement</b> ⇒ SQL 行为与引入本类之前逐字一致。
     */
    @Bean
    @ConditionalOnMissingBean
    public DeadlineQueryTimeoutInjector deadlineQueryTimeoutInjector() {
        return new DeadlineQueryTimeoutInjector();
    }

    /**
     * {@code 57014} → gRPC {@code DEADLINE_EXCEEDED} 的翻译器。
     *
     * <p>⚠️ <b>必须与 {@link DeadlineQueryTimeoutInjector} 成对</b> ——
     * 少了前者则查询不取消；少了后者则超时被当成「数据库故障」（5001 而非 B 的 1015）。
     */
    @Bean
    @ConditionalOnMissingBean
    public SqlTimeoutStatusTranslator sqlTimeoutStatusTranslator() {
        return new SqlTimeoutStatusTranslator();
    }
}
