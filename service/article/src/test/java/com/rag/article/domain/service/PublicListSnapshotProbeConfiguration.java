package com.rag.article.domain.service;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p><b>公开列表「同一快照」探针</b> —— 一个<b>只在测试上下文里存在</b>的 MyBatis 拦截器，
 * 用来在 {@code countPublic} 与 {@code selectPublicPage} <b>之间</b>插入一次并发的撤回。
 *
 * <h2>为什么需要这个钩子</h2>
 * <p>要证明「{@code listPublicArticles} 的 count 与 page 来自<b>同一快照</b>」，
 * 必须能在<b>这两条查询之间</b>制造一次并发写入。
 * ⇒ 而这两条查询都在服务方法的<b>同一个事务</b>里 ⇒ 外部无法在它们之间插手
 * ⇒ 只能挂一个<b>查询级</b>的钩子。
 *
 * <h2>为什么用 MyBatis 拦截器（而不是别的机制）</h2>
 * <ul>
 *   <li>MyBatis 的 {@link Interceptor} 是<b>官方扩展点</b>，它<b>不替换</b>被测对象
 *       ⇒ 被执行的仍是<b>生产 SQL 本体</b>（不是另写一份 SQL —— 另写只能证明 SQL 对，
 *       证不了「服务真的在同一快照里」）；</li>
 *   <li>MyBatis-Plus 3.5.17 的 {@code MybatisPlusAutoConfiguration} 会把容器里的
 *       {@code Interceptor[]} bean 收进插件链（{@code factory.setPlugins(this.interceptors)}）
 *       ⇒ 钩子<b>一定会生效</b>；</li>
 *   <li>⚠️ 本配置类只被<b>本测试类</b>在 {@code classes = {...}} 里点名
 *       ⇒ 它<b>不进</b>其它测试类的上下文 ⇒ 不会污染别的用例。</li>
 * </ul>
 *
 * <h2>它顺带采集的「运行期事务事实」</h2>
 * <p>钩子触发时，代码<b>正处在</b>服务方法的事务里 ⇒ 这时读到的隔离级别与只读标志
 * <b>就是运行期真正生效的值</b>（不是读注解猜的）：
 * <ul>
 *   <li>{@code TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()}
 *       —— Spring 事务管理器在 {@code doBegin} 时写入；</li>
 *   <li>{@code TransactionSynchronizationManager.isCurrentTransactionReadOnly()}；</li>
 *   <li>{@code SHOW transaction_isolation} / {@code SHOW transaction_read_only}
 *       —— <b>直接问 PG</b>（同一事务连接上执行 ⇒ 看到的就是当前事务的真实状态）。</li>
 * </ul>
 *
 * <h2>B 出处</h2>
 * <p>Go {@code service/article/rpc/internal/model/article_public.go:65,128}：
 * {@code Transaction(fn, &sql.TxOptions{Isolation: LevelRepeatableRead, ReadOnly: true})}
 * —— 注释写明「A repeatable-read snapshot keeps the count, page, and revision pointers
 * consistent when an article is edited or retracted concurrently」。
 */
@TestConfiguration(proxyBeanMethods = false)
public class PublicListSnapshotProbeConfiguration {

    /**
     * 探针 bean —— 类型必须是 {@link Interceptor}，MP 才会把它收进插件链。
     */
    @Bean
    public PublicListSnapshotProbe publicListSnapshotProbe(ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                                                           ObjectProvider<DataSource> dataSourceProvider) {
        return new PublicListSnapshotProbe(jdbcTemplateProvider, dataSourceProvider);
    }

    /**
     * 探针本体。
     *
     * <p>⚠️ 它只拦 {@code Executor.query} —— 写操作走 {@code Executor.update}，不受影响。
     */
    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    public static class PublicListSnapshotProbe implements Interceptor {

        /** 命中 {@code selectPublicPage} 时触发一次的钩子（由测试装配）。 */
        private final AtomicReference<Runnable> beforePageHook = new AtomicReference<>();

        /** 命中时采集到的运行期事务事实。 */
        private final AtomicReference<RuntimeTxFacts> facts = new AtomicReference<>();

        private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

        private final ObjectProvider<DataSource> dataSourceProvider;

        PublicListSnapshotProbe(ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                                ObjectProvider<DataSource> dataSourceProvider) {
            this.jdbcTemplateProvider = jdbcTemplateProvider;
            this.dataSourceProvider = dataSourceProvider;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object[] args = invocation.getArgs();
            if (args.length > 0 && args[0] instanceof MappedStatement ms
                    && ms.getId().endsWith(".selectPublicPage")) {
                // ① 先采集运行期事务事实（此刻仍在服务方法的事务内）
                facts.set(readRuntimeTxFacts());
                // ② 再触发钩子（并发撤回）
                Runnable hook = beforePageHook.get();
                if (hook != null) {
                    hook.run();
                }
            }
            return invocation.proceed();
        }

        /** 装配「{@code selectPublicPage} 之前要跑的钩子」（传 {@code null} 表示卸掉）。 */
        public void armBeforePageHook(Runnable hook) {
            beforePageHook.set(hook);
        }

        /** 取最近一次采集到的运行期事务事实（没采到就是 {@code null}）。 */
        public RuntimeTxFacts facts() {
            return facts.get();
        }

        /**
         * 从<b>另一条连接</b>上执行一次已提交的写 —— 必须是<b>另一条连接</b>：
         * 同一个事务里的写根本不会被当前快照看见。
         */
        public void runOnSeparateConnection(RunnableOnConnection action) {
            DataSource ds = dataSourceProvider.getObject();
            try (Connection conn = ds.getConnection()) {
                conn.setAutoCommit(true);
                try (Statement st = conn.createStatement()) {
                    action.run(st);
                }
            } catch (Exception e) {
                throw new IllegalStateException("探针的并发写失败", e);
            }
        }

        /**
         * 读运行期事务事实。
         *
         * <p>⚠️ {@code SHOW …} 走的是 {@link JdbcTemplate} ⇒ 它取的是
         * <b>事务绑定的那条连接</b> ⇒ 看到的就是<b>当前事务</b>的真实状态。
         */
        private RuntimeTxFacts readRuntimeTxFacts() {
            boolean active = TransactionSynchronizationManager.isActualTransactionActive();
            Integer springIsolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            boolean springReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            String pgIsolation = null;
            String pgReadOnly = null;
            JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
            if (jdbc != null) {
                try {
                    pgIsolation = jdbc.queryForObject("SHOW transaction_isolation", String.class);
                    pgReadOnly = jdbc.queryForObject("SHOW transaction_read_only", String.class);
                } catch (Exception ignored) {
                    // 取不到就留 null —— 断言里会显式区分「取不到」与「取到但值不对」
                }
            }
            return new RuntimeTxFacts(active, springIsolation, springReadOnly, pgIsolation, pgReadOnly);
        }
    }

    /** 探针里跑的「对一条独立连接做一次写」的回调。 */
    @FunctionalInterface
    public interface RunnableOnConnection {
        void run(Statement statement) throws Exception;
    }

    /**
     * 运行期事务事实 —— 每项都带「从哪读来的」以便复核。
     *
     * @param transactionActive Spring 是否认为有真实事务在进行
     * @param springIsolation   {@code TransactionSynchronizationManager} 里的隔离级别
     * @param springReadOnly    {@code TransactionSynchronizationManager} 里的只读标志
     * @param pgIsolation       PG 自己说的 {@code transaction_isolation}
     * @param pgReadOnly        PG 自己说的 {@code transaction_read_only}
     */
    public record RuntimeTxFacts(boolean transactionActive,
                                  Integer springIsolation,
                                  boolean springReadOnly,
                                  String pgIsolation,
                                  String pgReadOnly) {
    }
}
