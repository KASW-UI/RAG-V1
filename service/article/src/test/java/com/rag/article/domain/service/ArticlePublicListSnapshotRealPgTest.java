package com.rag.article.domain.service;

import com.rag.article.ArticleServiceApplication;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.mapper.ArticlePublicMapper;
import com.rag.article.support.NacosStubConfiguration;
import com.rag.article.support.TestPostgres;
import com.rag.proto.article.ListArticlesRequest;
import com.rag.proto.article.ListArticlesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>真触发</b>：公开列表的 count 与 page 是否真的来自<b>同一快照</b> ——
 * 以及这个事务在<b>运行期</b>到底是不是 {@code REPEATABLE_READ + readOnly}。
 *
 * <h2>🔴 为什么已有的那条不算数</h2>
 * <p>已有用例 {@code ArticlePublicDomainServiceRealPgTest.listPublicArticlesRunsInRepeatableReadOnlyTransaction}
 * 证的是<b>注解属性</b>（用 {@code AnnotationTransactionAttributeSource} 解析出
 * {@code readOnly=true} / {@code ISOLATION_REPEATABLE_READ}）。
 * ⇒ 按 AGENTS.md §3.3.1：「<b>判断某个 Java 框架能力是否生效，必须有运行期证据</b>」
 * ⇒ 读注解<b>证明不了</b>运行期真的按这个隔离级别开了事务。
 * ⇒ 本类补上两件事：<b>① 运行期事实</b>（PG 自己说的）+ <b>② 行为后果</b>（并发撤回时 total 与页内容不撕裂）。
 *
 * <h2>🔴 探针原理（确定性，不靠碰运气的交错）</h2>
 * <ol>
 *   <li>服务方法在<b>一个事务</b>里跑：{@code countPublic} → {@code selectPublicPage} → 取 revision；</li>
 *   <li>MyBatis 拦截器在 {@code selectPublicPage} <b>执行前</b>触发钩子
 *       ⇒ 钩子用<b>另一条连接</b>把一篇文章的指针撤回了（<b>已提交</b>）；</li>
 *   <li>此时若事务是 {@code REPEATABLE_READ}，快照在 {@code countPublic} 时就定好了
 *       ⇒ {@code selectPublicPage} <b>必须仍然看得到</b>那条被撤回的文章
 *       ⇒ {@code total} 与页内容<b>同源</b>，不撕裂；</li>
 *   <li>若隔离级别被改成 {@code READ COMMITTED}（或注解被摘掉）⇒ 快照会重取
 *       ⇒ 页内容<b>看不到</b>被撤回的那条，而 {@code total} 仍算着它 ⇒ <b>撕裂</b>。</li>
 * </ol>
 *
 * <h3>同条件对照（证明这条判据<b>有区分力</b>，不是恒绿）</h3>
 * <p>第二条用例用<b>同一套 mapper 方法</b>、<b>同一个并发撤回</b>，但<b>不经过服务方法</b>
 * ⇒ 每次调用各自 autocommit（PG 默认 {@code READ COMMITTED}）⇒ <b>确实会撕裂</b>。
 * ⇒ 两条合起来 ⇒ 第一条不是因为「探针看不见撤回」才绿的。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>Go {@code service/article/rpc/internal/model/article_public.go:65,128}：
 *       {@code Transaction(fn, &sql.TxOptions{Isolation: LevelRepeatableRead, ReadOnly: true})}，
 *       注释写明「A repeatable-read snapshot keeps the count, page, and revision pointers
 *       consistent when an article is edited or retracted concurrently」；</li>
 *   <li>B 侧可见性谓词：{@code article_public.go:20-32}（{@code publicArticlePredicate}）
 *       ⇒ 「撤回」= 指针 {@code state='retracted'} ⇒ 第一支失效、
 *       第二支要求 {@code p.article_id IS NULL} ⇒ 也失效 ⇒ 从公开列表消失。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code listPublicArticles} 的 isolation 改成 {@code DEFAULT}（或 readOnly=false）
 *       ⇒ 第一条 <b>立刻红</b>（页内容少了 1 条 ⇒ 撕裂）；</li>
 *   <li>把 count 与 page 拆成两个事务 ⇒ 第一条 <b>立刻红</b>；</li>
 *   <li>若探针本身失效（钩子没触发）⇒ 第一条会在
 *       「{@code facts()} 为 null」这一步 <b>红</b> ⇒ 不会静默退化成「没观察到撤回」。</li>
 * </ul>
 *
 * <h2>⚠️ 为什么本类<b>不继承</b> {@code ArticleRealPgTestBase}</h2>
 * <p>本类要往上下文里加一个 {@code Interceptor} bean ⇒ 必须换一套 {@code classes}
 * ⇒ Spring 的上下文缓存 key 变了 ⇒ <b>独立上下文</b>。
 * ⇒ 于是 {@code @SpringBootTest} 的属性只能<b>整份重写</b>（Boot 不会把父类的
 * {@code properties} 与子类的合并 —— 子类的注解整体<b>遮蔽</b>父类的）。
 * ⇒ 下面这份属性表与 {@code ArticleRealPgTestBase} <b>逐条相同</b>。
 *
 * <h2>数据隔离</h2>
 * <p>本类行 id 前缀 {@code 911001}；作者号 {@code 911000001}。
 */
@SpringBootTest(
        classes = {ArticleServiceApplication.class, NacosStubConfiguration.class,
                PublicListSnapshotProbeConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                "grpc.server.port=0",
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                "spring.kafka.producer.properties.max.block.ms=2000",
                "article-rpc.kq-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.kq-consumer-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-result-consumer-conf.brokers=127.0.0.1:1",
                "article-rpc.minio.endpoint=127.0.0.1:1"
        })
class ArticlePublicListSnapshotRealPgTest {

    private static final String AUTHOR = "911000001";

    @Autowired
    private ArticlePublicDomainService publicService;

    @Autowired
    private ArticlePublicMapper publicMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PublicListSnapshotProbeConfiguration.PublicListSnapshotProbe probe;

    /**
     * 注入连接信息与三个<b>必须</b>显式提供的属性。
     *
     * <p>⚠️ 本类<b>不继承</b> {@code ArticleRealPgTestBase}（要换一套 {@code classes}）
     * ⇒ 这份清单必须与基类<b>逐条相同</b>，否则上下文起不来：
     * <ul>
     *   <li>{@code POSTGRES_PASSWORD} —— 生产 {@code application.yml} 里是<b>裸占位符</b>
     *       （无默认值 ⇒ fail-closed）⇒ 必须由外部注入，否则占位符解析失败；</li>
     *   <li>{@code USER_JWT_SECRET} —— 同款裸占位符；
     *       {@code ArticleApiProperties#verifyAccessSecret} 是 fail-closed 守卫
     *       ⇒ 缺它上下文<b>直接起不来</b>（本轮第一次跑就是这么红的：
     *       {@code IllegalStateException: article-api.auth.access-secret 未配置}）；</li>
     *   <li>{@code grpc.client.message-rpc.address} —— 生产值是
     *       {@code discovery:///message-rpc}，而 devh 的 {@code @GrpcClient} 在
     *       <b>Bean 初始化期</b>建 channel ⇒ 不覆盖则上下文起不来。</li>
     * </ul>
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        registry.add("POSTGRES_PASSWORD", () -> "password");
        registry.add("USER_JWT_SECRET", () -> "test-only-article-jwt-secret-at-least-32-bytes-long");
        registry.add("grpc.client.message-rpc.address", () -> "static://127.0.0.1:1");
    }

    @BeforeEach
    void seed() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");
        probe.armBeforePageHook(null);

        seedPublished("911001001");
        seedPublished("911001002");
    }

    // ==================================================================
    // ① 运行期事实 + 行为后果：count 与 page 同一快照
    // ==================================================================

    @Test
    @DisplayName("🔴 真触发：listPublicArticles 在**运行期**是 REPEATABLE_READ + readOnly，且 count 与 page 来自**同一快照**（并发撤回不撕裂）")
    void countAndPageComeFromTheSameRuntimeSnapshot() {
        // ⚠️ 钩子装在 selectPublicPage 之前 ⇒ 撤回发生在「count 已算完、page 还没查」之间
        probe.armBeforePageHook(() -> probe.runOnSeparateConnection(st -> st.executeUpdate(
                "UPDATE article_publication SET state = 'retracted' WHERE article_id = '911001002'")));

        ListArticlesResponse response = publicService.listPublicArticles(
                ListArticlesRequest.newBuilder().setPage(1).setPageSize(20).build());

        // ① 运行期事实：此刻探针还在事务里读到的一切
        PublicListSnapshotProbeConfiguration.RuntimeTxFacts facts = probe.facts();
        assertNotNull(facts, "探针没观测到 selectPublicPage ⇒ 钩子没生效（不是被测行为的问题）");
        assertTrue(facts.transactionActive(),
                "listPublicArticles 必须在一个**真实事务**里（否则 count/page 必然不同源）");
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, facts.springIsolation(),
                "运行期隔离级别必须是 REPEATABLE_READ（Go: sql.TxOptions{Isolation: LevelRepeatableRead}）");
        assertTrue(facts.springReadOnly(),
                "运行期必须是 readOnly（Go: sql.TxOptions{ReadOnly: true}）");
        assertNotNull(facts.pgIsolation(),
                "探针取不到 PG 的 transaction_isolation ⇒ 观测手段本身失效");
        assertEquals("repeatable read", facts.pgIsolation(),
                "PG 自己说的隔离级别必须是 repeatable read");
        assertEquals("on", facts.pgReadOnly(),
                "PG 自己说的 transaction_read_only 必须是 on（readOnly 真的落到 PG 了）");

        // ② 行为后果：撤回发生在 count 之后，但 page 与 total 仍同源
        assertEquals(2L, response.getTotal(),
                "total 是 count 的结果（快照建立时点 ⇒ 仍含被撤回的那条）");
        assertEquals(2, response.getArticlesCount(),
                "🔴 页内容必须与 total 同源 ⇒ 并发撤回**不可见**；"
                        + "若只剩 1 条 ⇒ 快照在 count 与 page 之间被重取了 ⇒ 隔离级别没生效");
        List<String> ids = response.getArticlesList().stream()
                .map(com.rag.proto.article.Article::getId).toList();
        assertTrue(ids.contains("911001002"),
                "被撤回的文章必须仍在页内容里（同一快照的语义）；实际页内容 = " + ids);
    }

    // ==================================================================
    // ② 同条件对照：同一套 mapper + 同一个撤回，在 READ COMMITTED 下**确实会撕裂**
    // ==================================================================

    @Test
    @DisplayName("🔴 同条件对照：同一套 mapper SQL + 同一个并发撤回，在 READ COMMITTED（无事务）下**确实会撕裂** ⇒ 上一条的判据有区分力")
    void readCommittedControlActuallyTears() {
        // 不经过服务方法 ⇒ 每次 mapper 调用各自 autocommit ⇒ PG 默认 READ COMMITTED
        long total = publicMapper.countPublic(null, null, null);
        assertEquals(2L, total, "前置校验：撤回前 count 是 2");

        probe.runOnSeparateConnection(st -> st.executeUpdate(
                "UPDATE article_publication SET state = 'retracted' WHERE article_id = '911001002'"));

        List<ArticleEntity> page = publicMapper.selectPublicPage(
                null, null, null, null, false, 20, 0L);

        assertEquals(1, page.size(),
                "🔴 READ COMMITTED 下 page 必须**看不到**撤回的那条 ⇒ total(2) 与页内容(1) 撕裂"
                        + " ⇒ 由此可知第一条的「不撕裂」是隔离级别的功劳，而不是探针看不见撤回");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void seedPublished(String id) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, 'title-' || ?, 'brief', ? || '.md', '', 'row-manual',
                        '["row-tag"]'::jsonb, ?, 2, 0, 0, 0, 0,
                        '{}'::jsonb, now(), now())
                """, id, id, id, AUTHOR);
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id, revision, revision_id, author_id, source_object, content_sha256,
                     title, brief, cover_image_url, manual_type_tag, secondary_tags,
                     markdown, published_at, sync_event_id)
                VALUES (?, 1, ?, ?, ?, repeat('a', 64), 'rev-title', 'rev-brief', '', 'row-manual',
                        '[]'::jsonb, '# Approved', now(), ?)
                """, id, id + ":r1", AUTHOR, id + "-r1.md", "sync-" + id);
        jdbcTemplate.update("""
                INSERT INTO article_publication (article_id, current_revision, pointer_version,
                                                 state, last_event_id)
                VALUES (?, ?, 1, 'published', ?)
                """, id, id + ":r1", "evt-" + id);
    }
}
