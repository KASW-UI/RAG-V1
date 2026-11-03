package com.rag.article;

import com.rag.article.client.ContentAuditPort;
import com.rag.article.client.UnavailableContentAuditClient;
import com.rag.article.domain.entity.ArticleDomainOutboxEntity;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.domain.entity.ArticlePublicationEntity;
import com.rag.article.domain.entity.ArticleRevisionEntity;
import com.rag.article.domain.entity.ArticleSyncOutboxEntity;
import com.rag.article.mapper.ArticleMapper;
import com.rag.article.mapper.ArticlePublicMapper;
import com.rag.article.support.ArticleRealPgTestBase;

import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>article 域上下文装配自检</b> —— 证明 D1/D7 合并后的<b>唯一入口</b>真能把全链条装配起来。
 *
 * <h2>⚠️ 为什么必须「真启动」而不是「读源码断言」</h2>
 * <p>AGENTS.md §3.3.3：<b>不得凭注解名称断定机制已启用</b>；§3.3.1：判断某个能力是否生效
 * <b>必须有运行证据</b>。
 * <p>本类断言的每一条都是<b>运行期事实</b>（bean 真的在容器里 / 迁移账本里真的有那几个版本 /
 * 上下文真的起来了）⇒ 而非静态相似度。
 *
 * <h2>本类与其它测试类的分工（不重复造轮子）</h2>
 * <table border="1">
 *   <caption>谁管什么</caption>
 *   <tr><th>判据</th><th>本类</th><th>已有测试</th></tr>
 *   <tr><td>上下文能起来</td><td><b>本类</b>（生产入口 bean 在容器里）</td>
 *       <td>—</td></tr>
 *   <tr><td>{@code @MapperScan} 生效</td><td><b>本类</b>（bean 存在）</td>
 *       <td>{@code mapper/*} 那几类验的是 <b>SQL 行为</b>，不验 bean 是否注册</td></tr>
 *   <tr><td>域内 {@code .imports} 真被 Boot 读到</td><td><b>本类</b>（兜底 bean <b>在</b>）</td>
 *       <td>{@code assembly/ArticleAssemblyAutoConfigAndValidationTest} 验的是
 *           <b>真提交缺 title 的 body</b> ⇒ 走的是另一条链</td></tr>
 *   <tr><td>迁移<b>按版本</b>跑过</td><td><b>本类</b>（账本里有 V8/V9/V10 且 success）</td>
 *       <td>{@code mapper/ArticleSchemaAndTimestampRealPgTest} 验的是
 *           <b>表与约束</b>存在，不验账本版本号</td></tr>
 *   <tr><td>HTTP / gRPC / Kafka / MinIO 的行为面</td><td>—</td>
 *       <td>{@code http/} · {@code grpc/} · {@code kafka/} · {@code storage/} 各管一段</td></tr>
 * </table>
 *
 * <h2>① 比 B 侧多做了什么</h2>
 * <p>B 侧是**两个进程**（{@code article-api} + {@code article-rpc}）⇒ **没有**「一个上下文装起全链条」
 * 这件事 ⇒ B 侧无法有本类这种形态的测试（它只能各自测自己那半边）。
 * <p>C 侧按 D1（api+rpc 合一个进程）+ D7（域内协议层压平）合并为<b>单一入口</b>
 * ⇒ <b>才有</b>「起一次 ⇒ 全链条装配到位」这条判据。
 * ⚠️ 这是<b>拓扑产物</b>（D1/D7），<b>不是</b> B 侧行为的等价物 ⇒ 本类<b>不产出</b>任何
 * 「与 B 侧行为一致」的结论，它只证明 **C 侧自己的装配是完整的**。
 *
 * <h2>② 语义还一样吗</h2>
 * <p><b>一样</b>（就「本域该有的东西都在」这个命题而言）——
 * 断言涉及的每一个表名、每一个 bean 职责，都能在 B 侧找到对应物（B 出处见下表）；
 * 差别只在<b>它们被装进同一个上下文</b>（B 侧分两个进程）。
 * ⇒ ⚠️ <b>效力边界</b>：本类<b>不</b>证明「B 侧也是这么装的」，也<b>不</b>证明
 * 「HTTP / gRPC 的对外行为一致」⇒ 那两件事分别由 {@code http/} 与 {@code grpc/} 里的
 * 真触发测试承担。
 *
 * <h2>③ B 出处（逐条 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ B 侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th></tr>
 *   <tr><td>表名 {@code article}</td>
 *       <td>{@code service/article/article-rpc/src/main/resources/db/migration/V8__create_article.sql:21}
 *           （{@code CREATE TABLE IF NOT EXISTS article (}）</td></tr>
 *   <tr><td>表名 {@code article_sync_outbox}</td>
 *       <td>{@code V9__create_article_sync_outbox.sql:11}</td></tr>
 *   <tr><td>表名 {@code article_revision}</td>
 *       <td>{@code V10__create_article_community_revision.sql:16}</td></tr>
 *   <tr><td>表名 {@code article_publication}</td>
 *       <td>{@code V10__create_article_community_revision.sql:34}</td></tr>
 *   <tr><td>表名 {@code article_domain_outbox}</td>
 *       <td>{@code V10__create_article_community_revision.sql:43}</td></tr>
 *   <tr><td>迁移<b>版本号</b>是 V8 / V9 / V10（<b>不得重编号</b>）</td>
 *       <td>同上三个文件名的版本号（{@code V8} / {@code V9} / {@code V10}）⇒
 *           C 侧 {@code application.yml:87-91} 把这条列为硬要求</td></tr>
 *   <tr><td>审核链路<b>无对端</b> ⇒ 兜底实现</td>
 *       <td>B 侧 {@code article-rpc/…/mq/ArticleReviewConsumer.java}（{@code SecurityRpc == nil ⇒ return error}）
 *           ⇒ C 侧对应 {@link UnavailableContentAuditClient}（<b>恒 fail-loud</b> · D-06 签字偏离）</td></tr>
 * </table>
 *
 * <h2>⚠️ 为什么继承 {@link ArticleRealPgTestBase} 而不是自己再写一份 {@code @SpringBootTest}</h2>
 * <p>该基类的 javadoc 已说明：Spring 的 {@code ContextCache} 用 {@code MergedContextConfiguration} 作 key，
 * 而 {@code @DynamicPropertySource} 的方法集合是 key 的一部分 ⇒ 每个测试类<b>各自</b>声明
 * {@code @DynamicPropertySource} 就会<b>多起一个完整上下文</b>（连 PG + 跑 Flyway + 起 gRPC server）。
 * ⇒ ⇒ 继承该基类 ⇒ 与本域其它集成测试<b>共享同一个上下文</b>（本类也因此<b>不能</b>再声明
 * {@code @DynamicPropertySource}）。
 */
class ArticleServiceApplicationTests extends ArticleRealPgTestBase {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private ArticlePublicMapper articlePublicMapper;

    @Autowired
    private ContentAuditPort contentAuditPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("🔴 上下文能起来（合并单进程 · 全量 main 代码装配到位）—— D1/D7 的守门")
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(ArticleServiceApplication.class)).isNotNull();
    }

    @Test
    @DisplayName("🔴 @MapperScan 真生效：ArticleMapper / ArticlePublicMapper 两个 bean 都在容器里")
    void mapperBeansAreRegistered() {
        assertThat(articleMapper).isNotNull();
        assertThat(articlePublicMapper).isNotNull();
        assertThat(context.getBeanNamesForType(ArticleMapper.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ArticlePublicMapper.class)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 域内 .imports 真被 Boot 读到：审核兜底 Bean 在容器里（A5 的运行期证据）")
    void domainAutoConfigurationIsRegistered() {
        // ⚠️ 断言的是「**bean 在**」，不是「配置类上有 @AutoConfiguration」⇒ 后者是形态证据
        assertThat(contentAuditPort).isNotNull();
        assertThat(contentAuditPort).isInstanceOf(UnavailableContentAuditClient.class);
        assertThat(context.getBeanNamesForType(ContentAuditPort.class)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 迁移按版本真跑过：账本里有 V8/V9/V10 且全部 success（版本号没被重编号）")
    void flywayLedgerRecordsTheBVersions() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT version, success FROM flyway_schema_history_article
                ORDER BY installed_rank
                """);

        assertThat(rows)
                .as("账本表名必须是 article 域的**独立**账本（application.yml:86），"
                        + "不能用默认的 flyway_schema_history（schema 级账本会与其它域撞号）")
                .isNotEmpty();

        List<String> versions = rows.stream()
                .map(r -> String.valueOf(r.get("version")))
                .toList();

        assertThat(versions)
                .as("迁移版本号必须保持 B 侧的 V8 / V9 / V10 ⇒ **不许重编号成 V1/V2/V3**"
                        + "（application.yml:87-91 的硬要求）")
                .contains("8", "9", "10")
                .doesNotContain("1", "2", "3");

        assertThat(rows)
                .as("账本里每一条都必须 success=true")
                .allSatisfy(r -> assertThat(r.get("success")).isEqualTo(Boolean.TRUE));
    }

    @Test
    @DisplayName("🔴 实体映射的表名是 B 侧那五张（L1 可见面）")
    void entityTableNamesMatchB() {
        assertThat(tableNameOf(ArticleEntity.class)).isEqualTo("article");
        assertThat(tableNameOf(ArticleSyncOutboxEntity.class)).isEqualTo("article_sync_outbox");
        assertThat(tableNameOf(ArticleRevisionEntity.class)).isEqualTo("article_revision");
        assertThat(tableNameOf(ArticlePublicationEntity.class)).isEqualTo("article_publication");
        assertThat(tableNameOf(ArticleDomainOutboxEntity.class)).isEqualTo("article_domain_outbox");
    }

    /** 读实体上的 {@code @TableName} 值（表名是 L1 契约 ⇒ 逐字对比）。 */
    private static String tableNameOf(Class<?> entityType) {
        TableName tableName = entityType.getAnnotation(TableName.class);
        assertThat(tableName)
                .as("%s 必须有 @TableName", entityType.getSimpleName())
                .isNotNull();
        return tableName.value();
    }
}
