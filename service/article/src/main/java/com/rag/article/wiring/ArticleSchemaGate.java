package com.rag.article.wiring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <p><b>职责</b>：启动期校验 article 域的<b>五张表</b>与<b>不可变触发器</b>确实存在 ——
 * 缺任何一项 ⇒ <b>fail-fast，进程不启动</b>。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/bootstrap/ArticleSchemaGate.java}
 * （类声明 {@code :81-82} · {@code REQUIRED_TABLES} {@code :87-92} · {@code IMMUTABILITY_TRIGGER} {@code :95} ·
 * 字段与构造器 {@code :97-101} · {@code afterSingletonsInstantiated} {@code :112-115} ·
 * {@code verifySchema} {@code :122-168}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅包名</b>（{@code com.rag.article.article.rpc.bootstrap} →
 *       {@code com.rag.article.wiring}，D7 包压平）＋ <b>本段 javadoc</b>
 *       ＋ <b>一处报错文案里的 artifact 名</b>（<b>纯表达层</b>，见下）
 *       ⇒ <b>断言集合、SQL 文本、异常的<b>语义与触发条件</b>逐字未改</b>。</li>
 *   <li><b>那一处文案改动是什么、为什么不改就是错的</b>：B 侧 {@code :140-141} 的提示语写的是
 *       「the pom includes {@code org.springframework.boot:spring-boot-flyway}（required since Boot 4）」
 *       ⇒ 但 <b>Boot 3.5 里 {@code spring-boot-flyway} 这个 artifact 根本不存在</b>（D14：
 *       {@code FlywayAutoConfiguration} 在 {@code spring-boot-autoconfigure} 里）
 *       ⇒ ⇒ 照抄会让运维<b>去加一个加不上的依赖</b>。
 *       ⇒ 本仓把该 artifact 名改成实际存在的 {@code org.flywaydb:flyway-core}
 *       ⇒ <b>判据（缺表 ⇒ 拒绝启动）、异常类型、其余文案一字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>断言仍是<b>「五张表 ＋ 触发器」全部存在</b>（映射表 L-3 第 5 条：B 侧已是扩展后的形态，
 *             本仓逐字继承，<b>不得</b>退回「只断言触发器」）；</li>
 *         <li>校验时机仍是 {@link SmartInitializingSingleton}（落在「所有单例已实例化」与
 *             「任何 {@code SmartLifecycle} 被启动」<b>之间</b>）⇒ ⇒ 缺触发器时进程<b>从不收流量</b>；</li>
 *         <li>两条 SQL 文本逐字一致（{@code information_schema.tables} 与 {@code pg_trigger} +
 *             {@code to_regclass('article_revision')}）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>Go 侧的对应门禁（消费点，不是声明点）</h2>
 * <pre>
 * SELECT EXISTS(SELECT 1 FROM pg_trigger
 *   WHERE tgname='article_revision_immutable' AND tgrelid=to_regclass('article_revision'))
 * if !revisionSchemaReady {
 *     return nil, fmt.Errorf("community revision migration 002_community_revision.sql is required
 *                             before article RPC startup")
 * }
 * </pre>
 *
 * <h2>🔴 为什么这道闸<b>必须 fail-fast</b>（与 like 域的 SchemaGate 不同）</h2>
 * like 域缺表只是「第一次点赞会失败」；而 article 域缺
 * {@code article_revision_immutable} 触发器意味着
 * <b>「已发布文章的字节不可变」这条保证被静默取消</b> ——
 * 进程照常启动、审核照常发布，但任何一次误 UPDATE 都能改写已发布正文，
 * <b>且不会有任何报错</b>。
 * <p>Go 侧对此 {@code return err} ⇒ 上层 {@code panic} ⇒ <b>进程不启动</b>。
 * 这是刻意的 fail-fast：宁可起不来，也不要在失去不可变保证的情况下运行。
 *
 * <p><b>与 Flyway 的分工</b>：本类不是 Flyway 的替代品，而是它的<b>第二道防线</b>。
 * 缺 {@code spring.flyway} 依赖时迁移永不执行 ⇒ 表与触发器都不存在
 * ⇒ 进程「看起来完全正常」⇒ 第一次真正执行 SQL 才炸。
 * 本类把「迁移到底跑没跑」变成<b>启动时的一条异常</b>。
 *
 * <h2>🔴 校验时机：{@link SmartInitializingSingleton}（不是 {@code ApplicationReadyEvent}）</h2>
 *
 * <p><b>Go 出处（逐行，消费点）</b>：
 * <pre>
 * article.go:41   u := model.NewArticleRepo(c)         ← InitDB 里校验触发器，失败 ⇒ panic
 * article.go:44   s := zrpc.MustNewServer(...)          ← gRPC server 在这之后才被创建
 * article.go:57   serviceGroup.Add(kq.MustNewQueue(...))  ← Kafka consumer 同理
 * article.go:78   serviceGroup.Start()                 ← 真正开始收流量
 * </pre>
 * ⇒ Go：<b>门禁在 server 被创建之前</b> ⇒ 缺触发器时进程<b>从不收流量</b>。
 *
 * <p><b>Spring 侧的等价位置</b>（spring-framework 6.2 · Boot 3.5，消费点）：
 * <pre>
 * AbstractApplicationContext.refresh()
 *   finishBeanFactoryInitialization(beanFactory)
 *          └─ DefaultListableBeanFactory.preInstantiateSingletons()
 *               └─ SmartInitializingSingleton.afterSingletonsInstantiated()  ← 本类在这
 *   finishRefresh()
 *          └─ getLifecycleProcessor().onRefresh()   ← 启动全部 SmartLifecycle
 *                    ├─ devh  GrpcServerLifecycle   (localServer.start() ⇒ 端口绑定)
 *                    └─ Kafka KafkaListenerEndpointRegistry (消费者开始拉消息)
 * </pre>
 * ⇒ 本类选 {@link SmartInitializingSingleton}，因为它落在
 * 「所有单例已实例化」与「任何 {@code SmartLifecycle} 被启动」<b>之间</b>。
 *
 * <p><b>为什么不能更早</b>：{@code BeanFactoryPostProcessor} 阶段连 DataSource 都还没建好，
 * Flyway 更是没跑 ⇒ 那时校验必然误报「缺表」。
 * <br><b>为什么 Flyway 已经跑完了</b>：{@code FlywayMigrationInitializer} 是普通单例，
 * 它的 {@code afterPropertiesSet()} 在 {@code preInstantiateSingletons()} 的实例化阶段就执行完。
 *
 * <p><b>为什么不能是 {@code ApplicationReadyEvent}</b>：
 * 它由 {@code SpringApplication.run()} 在 {@code refresh()} <b>返回之后</b>才发布，
 * 此时 gRPC server <b>早已绑定端口</b>、Kafka 消费者<b>早已开始拉消息</b>
 * ⇒ 存在一个「已在收流量、但不可变保证缺失」的窗口。
 */
@Component
public class ArticleSchemaGate implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ArticleSchemaGate.class);

    /** 本域 5 张表，与 {@code V8}/{@code V9}/{@code V10} 迁移脚本一一对应。 */
    private static final List<String> REQUIRED_TABLES = List.of(
            "article",
            "article_sync_outbox",
            "article_revision",
            "article_publication",
            "article_domain_outbox");

    /** Go {@code db.go:63-65} 的触发器名，不可改。 */
    private static final String IMMUTABILITY_TRIGGER = "article_revision_immutable";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 {@link JdbcTemplate}。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/ArticleSchemaGate.java:99-101}。
     *
     * @param jdbcTemplate Spring Boot 自动配置的数据访问模板
     */
    public ArticleSchemaGate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 校验表与触发器 —— <b>在 gRPC server / Kafka consumer 启动之前</b>。
     *
     * <p>⚠️ 这里<b>不能</b>再用 {@code ApplicationReadyEvent}：
     * 那是 {@code refresh()} <b>之后</b>才发布的，那时端口已绑、消费者已拉消息。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/ArticleSchemaGate.java:112-115}。
     *
     * @throws IllegalStateException 表缺失或触发器缺失 —— <b>阻止启动</b>，与 Go 的 panic 等价
     */
    @Override
    public void afterSingletonsInstantiated() {
        verifySchema();
    }

    /**
     * 校验表与触发器。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/ArticleSchemaGate.java:122-168}。
     *
     * @throws IllegalStateException 表缺失或触发器缺失 —— <b>阻止启动</b>，与 Go 的 panic 等价
     */
    public void verifySchema() {
        List<String> present;
        try {
            present = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                    String.class);
        } catch (Exception e) {
            // ⚠️ 连 information_schema 都查不到 ⇒ 数据源本身不可用。
            //    Go 侧这等价于 NewArticleRepo 里 gorm.Open 失败 ⇒ panic。
            throw new IllegalStateException(
                    "article-rpc schema gate could not reach PostgreSQL; refusing to start", e);
        }

        List<String> missing = REQUIRED_TABLES.stream().filter(t -> !present.contains(t)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "article-rpc schema gate: missing tables " + missing
                            + " ⇒ Flyway migrations did not run. Check spring.flyway.enabled, "
                            + "locations=classpath:db/migration, and that the pom includes "
                            + "org.flywaydb:flyway-core (required since Boot 3.5).");
        }

        Boolean triggerReady;
        try {
            // ⚠️ 与 Go db.go:62-67 逐字同一条查询（含 to_regclass 的存在性判断）
            triggerReady = jdbcTemplate.queryForObject("""
                    SELECT EXISTS(
                        SELECT 1 FROM pg_trigger
                        WHERE tgname = ?
                          AND tgrelid = to_regclass('article_revision')
                    )
                    """, Boolean.class, IMMUTABILITY_TRIGGER);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "article-rpc schema gate could not verify the article_revision immutability trigger", e);
        }

        if (!Boolean.TRUE.equals(triggerReady)) {
            // ⚠️ 与 Go 的错误文案对齐（Go: db.go:70）
            throw new IllegalStateException(
                    "community revision migration 002_community_revision.sql is required before "
                            + "article RPC startup (trigger " + IMMUTABILITY_TRIGGER + " not found)");
        }

        log.info("ArticleSchemaGate: all {} article tables and the {} immutability trigger are present",
                REQUIRED_TABLES.size(), IMMUTABILITY_TRIGGER);
    }
}
