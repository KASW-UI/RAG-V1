package com.rag.comment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.domain.entity.CommentContentEntity;
import com.rag.comment.domain.entity.CommentIndexEntity;
import com.rag.comment.domain.service.CommentDomainFactService;
import com.rag.comment.domain.service.CommentTransactionalService;
import com.rag.comment.exception.CommentExceptions;
import com.rag.comment.mapper.CommentContentMapper;
import com.rag.comment.mapper.CommentDomainFactOutboxMapper;
import com.rag.comment.mapper.CommentFactStreamMapper;
import com.rag.comment.mapper.CommentIndexMapper;
import com.rag.comment.mapper.CommentLikeMapper;
import com.rag.comment.mapper.SubjectMapper;
import com.rag.comment.messaging.CommentAuditMessage;
import com.rag.comment.metrics.CommentRpcMetrics;
import com.rag.comment.support.TestPostgres;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p>🔴 <b>真 PostgreSQL ＋ 真 MyBatis ＋ 真 Spring 事务</b>上的<b>动态 SQL</b>守门测试。
 *
 * <h2>1. 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>动态 SQL 守门点从 2 个 Mapper 扩到 7 个 Mapper 的全部 {@code <if>/<set>/<foreach>/<choose>}</b>：
 *       B 侧只守了 {@code SubjectMapper.decrementCounts} 与
 *       {@code CommentIndexMapper.applyInteractionDeltas / incrementParentReplyCount} 三处；
 *       C 侧 7 个 Mapper <b>全部</b>是显式 SQL（无 MP Wrapper 拼接）⇒ 每一处动态拼接都要在真 PG 上跑通。
 *       新增守门点：{@code SubjectMapper.upsertCommentSubject} 的 {@code <if>}、
 *       {@code CommentIndexMapper.selectReplyIdsByPage} 的 {@code <if> + <choose>}、
 *       {@code CommentIndexMapper.selectByIds} 与 {@code CommentContentMapper.selectByIds} 的
 *       {@code <foreach>}。</li>
 *   <li><b>新增「真 Spring 事务」的两条守门</b>：B 侧本类<b>完全没有</b>事务断言
 *       ⇒ 本类用 <b>{@code @Transactional} 代理</b>（CGLIB ＋ {@code TransactionInterceptor}）
 *       真跑一遍 {@link CommentTransactionalService#insertCommentTx}：
 *       ① 成功路径 ⇒ 业务行 / subject 计数 / 事实 outbox <b>一次提交</b>；
 *       ② 失败路径（父评论不存在）⇒ <b>整笔回滚</b>（连已写入的 subject 计数也回滚）。
 *       ⇒ 这是 AGENTS.md §3.3「框架机制必须验证实际生效」的落点：
 *       <b>注解在不在类上不作数，事务真的回滚了才算数</b>。</li>
 *   <li><b>探针库隔离</b>：B 侧本类自起一个 PG 容器；C 侧共用<b>进程内单例</b>容器
 *       ⇒ 本类在同容器里另开一个探针库 {@code comment_sql_probe}。</li>
 * </ul>
 *
 * <h2>2. 语义还一样吗</h2>
 * <p>一样。B 侧 {@code CommentTransactionalSqlGateTest} 的 8 条断言逐条保留
 * （{@code decrementCounts} 两个分支 / {@code upsertCommentSubject} 三个分支 /
 * {@code incrementParentReplyCount} 两个分支 / {@code applyInteractionDeltas} 两个分支 /
 * {@code markDeleted}）。
 *
 * <h2>3. B 出处</h2>
 * <ul>
 *   <li>{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentTransactionalSqlGateTest.java}
 *       （254 行 · 本类的逐条 oracle）</li>
 *   <li>动态 SQL 的 B 侧实现：{@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/mapper/SubjectMapper.java}
 *       ＋ {@code .../mapper/CommentIndexMapper.java}（C 侧落在 {@code com.rag.comment.mapper}）</li>
 *   <li>Go 侧：{@code service/comment/rpc/internal/model/comment.go:99-123}（subject upsert）
 *       ＋ {@code :295-304}（计数回退）＋ {@code :126-141}（父评论回复数）＋
 *       {@code :561-583}（互动差量）</li>
 * </ul>
 *
 * <h2>🔴 为什么单独开一个类（而不是并进别的类）</h2>
 * <p>本域此前的测试分两类：① 纯 Java 逻辑断言（不碰 SQL）② 裸 JDBC 的 DDL 断言
 * （<b>不经过 MyBatis 的动态拼接</b>）。
 * ⇒ <b>没有任何测试执行过 mapper 里那些 {@code <if>/<set>} 拼出来的 SQL</b>。
 *
 * <p>🔴 <b>代价已经付过了</b>：B 侧 {@code SubjectMapper#decrementCounts} 的 {@code <if>}
 * 被拼在 WHERE 子句之后 ⇒ 删根评论时是<b>语法错误</b>
 * ⇒ {@code DeleteCommentTx} 整个事务回滚 ⇒ 对外报 5002「DB更新失败」。
 * 该缺陷是<b>双侧活体对拍</b>（2026-10-24）抓出来的，不是单测抓出来的。
 *
 * <p>⇒ 本类把**每一个**带 {@code <if>} / {@code <set>} 的 mapper 方法都跑一遍真 PG，
 * 让「动态拼接出来的 SQL 是不是合法 SQL」不再靠运气。
 */
class CommentTransactionalSqlRealPgTest {

    /** 本类专用探针库（与其它测试类的库互不干扰）。 */
    private static final String PROBE_DB = "comment_sql_probe";

    private static JdbcTemplate jdbc;
    private static SubjectMapper subjectMapper;
    private static CommentIndexMapper indexMapper;
    private static CommentContentMapper contentMapper;
    private static CommentLikeMapper likeMapper;
    private static CommentDomainFactService factService;

    /** 带 {@code @Transactional} 代理的领域事务服务（见类注释「真 Spring 事务」段）。 */
    private static CommentTransactionalService transactionalService;

    @BeforeAll
    static void setUp() throws IOException {
        createProbeDatabase(PROBE_DB);
        migrateWithModuleConfig(PROBE_DB);

        DataSource dataSource = new DriverManagerDataSource(
                containerJdbcUrl(PROBE_DB), TestPostgres.username(), TestPostgres.password());
        jdbc = new JdbcTemplate(dataSource);

        // --- 真 MyBatis：与生产同构的「mapUnderscoreToCamelCase」+ Spring 事务感知的会话模板
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("sql-gate",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(SubjectMapper.class);
        configuration.addMapper(CommentIndexMapper.class);
        configuration.addMapper(CommentContentMapper.class);
        configuration.addMapper(CommentLikeMapper.class);
        configuration.addMapper(CommentDomainFactOutboxMapper.class);
        configuration.addMapper(CommentFactStreamMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        subjectMapper = template.getMapper(SubjectMapper.class);
        indexMapper = template.getMapper(CommentIndexMapper.class);
        contentMapper = template.getMapper(CommentContentMapper.class);
        likeMapper = template.getMapper(CommentLikeMapper.class);

        ObjectMapper objectMapper = new ObjectMapper();
        factService = new CommentDomainFactService(
                template.getMapper(CommentDomainFactOutboxMapper.class),
                template.getMapper(CommentFactStreamMapper.class),
                objectMapper);

        // --- 真 Spring 事务：CGLIB 代理 + TransactionInterceptor ⇒ @Transactional 真的生效
        CommentTransactionalService target = new CommentTransactionalService(
                subjectMapper, indexMapper, contentMapper, likeMapper, factService,
                objectMapper, new CommentRpcMetrics(new SimpleMeterRegistry()));
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        transactionalService = (CommentTransactionalService) proxyFactory.getProxy();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM subject");
        jdbc.update("DELETE FROM comment_index");
        jdbc.update("DELETE FROM comment_content");
        jdbc.update("DELETE FROM comment_domain_fact_outbox");
        jdbc.update("DELETE FROM comment_fact_stream");
    }

    // ====================================================================================
    // ① SubjectMapper.decrementCounts —— 修复前的「<if> 拼在 WHERE 之后」缺陷
    // ====================================================================================

    @Test
    @DisplayName("🔴 decrementCounts(rootCountDecrement=true)：根评论删除 ⇒ total/root 计数都减 1")
    void decrementCountsDecrementsRootCountForRootComment() {
        seedSubject("article", "c3-a-root", 3, 2);

        // 修复前这里会抛 PG 的 syntax error（"," 位置）
        assertThatCode(() -> subjectMapper.decrementCounts("article", "c3-a-root", true))
                .doesNotThrowAnyException();

        assertCounts("c3-a-root", 2L, 1L);
    }

    @Test
    @DisplayName("🔴 decrementCounts(rootCountDecrement=false)：非根评论删除 ⇒ 只减 total")
    void decrementCountsLeavesRootCountForReply() {
        seedSubject("article", "c3-a-reply", 3, 2);

        assertThatCode(() -> subjectMapper.decrementCounts("article", "c3-a-reply", false))
                .doesNotThrowAnyException();

        assertCounts("c3-a-reply", 2L, 2L);
    }

    // ====================================================================================
    // ② SubjectMapper.upsertCommentSubject —— ON CONFLICT 分支的 <if>
    // ====================================================================================

    @Test
    @DisplayName("🔴 upsertCommentSubject(rootCount=1)：首次插入 total/root 都是 1")
    void upsertSubjectCreatesRootCounts() {
        assertThatCode(() -> subjectMapper.upsertCommentSubject("article", "c3-a-up-1", 1, 5L))
                .doesNotThrowAnyException();

        assertCounts("c3-a-up-1", 1L, 1L);
    }

    @Test
    @DisplayName("🔴 upsertCommentSubject(rootCount=0)：首次插入只有 total=1，root 保持 0")
    void upsertSubjectCreatesReplyWithoutRootCount() {
        assertThatCode(() -> subjectMapper.upsertCommentSubject("article", "c3-a-up-2", 0, 5L))
                .doesNotThrowAnyException();

        assertCounts("c3-a-up-2", 1L, 0L);
    }

    @Test
    @DisplayName("🔴 upsertCommentSubject(rootCount=1)：冲突分支只加 root_count（rootCount=0 时不加）")
    void upsertSubjectOnConflictAddsRootCountOnlyForRoot() {
        seedSubject("article", "c3-a-up-3", 1, 1);
        subjectMapper.upsertCommentSubject("article", "c3-a-up-3", 1, 5L);
        assertCounts("c3-a-up-3", 2L, 2L);

        subjectMapper.upsertCommentSubject("article", "c3-a-up-3", 0, 5L);
        assertCounts("c3-a-up-3", 3L, 2L);
    }

    // ====================================================================================
    // ③ CommentIndexMapper 的动态 SET
    // ====================================================================================

    @Test
    @DisplayName("🔴 incrementParentReplyCount(markAuthorReplied=true)：置 attribute 第 1 位")
    void incrementParentReplyCountSetsAuthorRepliedBit() {
        jdbc.update("INSERT INTO comment_index (id, target_type, target_id, user_id, root_id, parent_id) "
                + "VALUES (9103001, 'article', 'a1', 7, 0, 0)");
        jdbc.update("INSERT INTO comment_index (id, target_type, target_id, user_id, root_id, parent_id) "
                + "VALUES (9103002, 'article', 'a1', 7, 9103001, 9103001)");

        assertThatCode(() -> indexMapper.incrementParentReplyCount(9103001, true)).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT attribute FROM comment_index WHERE id=9103001", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT reply_count FROM comment_index WHERE id=9103001", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("🔴 incrementParentReplyCount(markAuthorReplied=false)：不动 attribute")
    void incrementParentReplyCountWithoutAuthorRepliedBit() {
        jdbc.update("INSERT INTO comment_index (id, target_type, target_id, user_id, root_id, parent_id) "
                + "VALUES (9103003, 'article', 'a1', 7, 0, 0)");

        assertThatCode(() -> indexMapper.incrementParentReplyCount(9103003, false)).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT attribute FROM comment_index WHERE id=9103003", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("🔴 applyInteractionDeltas：点赞 +1 / 置位 / 取消赞清位（<set> 动态拼接）")
    void applyInteractionDeltasAppliesLikeAndAttributeBits() {
        jdbc.update("INSERT INTO comment_index (id, target_type, target_id, user_id, root_id, parent_id) "
                + "VALUES (9103004, 'article', 'a1', 7, 0, 0)");

        // 点赞：like_count +1 且（作者）置 attribute 第 0 位
        assertThatCode(() -> indexMapper.applyInteractionDeltas(9103004, 1L, 0L, true, false))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT like_count FROM comment_index WHERE id=9103004", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT attribute FROM comment_index WHERE id=9103004", Long.class))
                .isEqualTo(1L);

        // 取消赞：like_count -1 且清 attribute 第 0 位（bitwise NOT，不是减法）
        assertThatCode(() -> indexMapper.applyInteractionDeltas(9103004, -1L, 0L, false, true))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT like_count FROM comment_index WHERE id=9103004", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT attribute FROM comment_index WHERE id=9103004", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("🔴 applyInteractionDeltas：差量为 0 时不写对应列（Go 的 len(updateCols)>0 语义）")
    void applyInteractionDeltasSkipsZeroDeltas() {
        jdbc.update("INSERT INTO comment_index (id, target_type, target_id, user_id, root_id, parent_id, "
                + "like_count, dislike_count) VALUES (9103005, 'article', 'a1', 7, 0, 0, 5, 3)");

        assertThatCode(() -> indexMapper.applyInteractionDeltas(9103005, 0L, 0L, false, false))
                .doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT like_count FROM comment_index WHERE id=9103005", Long.class))
                .isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT dislike_count FROM comment_index WHERE id=9103005", Long.class))
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("🔴 markDeleted：软删除置 state=2（不是物理删除），updated_at 前进")
    void markDeletedSoftDeletes() {
        jdbc.update("INSERT INTO comment_index (id, target_type, target_id, user_id, root_id, parent_id, "
                + "created_at) VALUES (9103006, 'article', 'a1', 7, 0, 0, now() - interval '1 hour')");

        assertThatCode(() -> indexMapper.markDeleted(9103006)).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT state FROM comment_index WHERE id=9103006", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comment_index WHERE id=9103006", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT updated_at > created_at FROM comment_index WHERE id=9103006", Boolean.class)).isTrue();
    }

    // ====================================================================================
    // ④ C 侧新增守门点：selectReplyIdsByPage 的 <if> + <choose>、selectByIds 的 <foreach>
    // ====================================================================================

    @Test
    @DisplayName("🔴 selectReplyIdsByPage：onlyNormal 过滤 + 热度/时间两种排序分支（<if> + <choose>）")
    void selectReplyIdsByPageHonoursFilterAndBothSortBranches() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        insertIndex(9103011L, "c3-sort", 0, 5L, base);            // 正常 · like 5
        insertIndex(9103012L, "c3-sort", 1, 9L, base.plusMinutes(1)); // 已删除 · like 9
        insertIndex(9103013L, "c3-sort", 0, 1L, base.plusMinutes(2)); // 正常 · like 1

        // ① onlyNormal=true ⇒ 过滤掉 state != 0；时间序（hot=false）⇒ created_at DESC
        List<Long> normal = indexMapper.selectReplyIdsByPage(
                "article", "c3-sort", 0, true, false, 0, 10);
        assertThat(normal)
                .as("onlyNormal=true ⇒ 只留 state=0；created_at DESC ⇒ 9103013 在前")
                .containsExactly(9103013L, 9103011L);

        // ② onlyNormal=false ⇒ 已删除的也会出现（Go 的现状，见 selectReplyIdsByPage 的注释）
        List<Long> all = indexMapper.selectReplyIdsByPage(
                "article", "c3-sort", 0, false, false, 0, 10);
        assertThat(all).containsExactly(9103013L, 9103012L, 9103011L);

        // ③ hot=true ⇒ like_count DESC, id DESC
        List<Long> hot = indexMapper.selectReplyIdsByPage(
                "article", "c3-sort", 0, false, true, 0, 10);
        assertThat(hot)
                .as("hot=true ⇒ like_count DESC, id DESC")
                .containsExactly(9103012L, 9103011L, 9103013L);

        // ④ 分页：limit/offset 生效
        List<Long> page = indexMapper.selectReplyIdsByPage(
                "article", "c3-sort", 0, false, false, 1, 1);
        assertThat(page).containsExactly(9103012L);
    }

    @Test
    @DisplayName("🔴 selectByIds 的 <foreach> 动态 IN 拼接：comment_index 与 comment_content 都要真跑")
    void selectByIdsForeachWorksOnRealPg() {
        insertIndex(9103021L, "c3-foreach", 0, 0L, OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
        insertIndex(9103022L, "c3-foreach", 0, 0L, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        insertContent(9103021L, "c3-foreach-1", OffsetDateTime.now(ZoneOffset.UTC).minusDays(2));
        insertContent(9103022L, "c3-foreach-2", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        assertThat(indexMapper.selectByIds(List.of(9103021L, 9103022L)))
                .as("<foreach> 拼出来的 IN 列表必须能真跑（单元素 / 多元素都要）")
                .hasSize(2);
        assertThat(indexMapper.selectByIds(List.of(9103021L)))
                .hasSize(1);
        assertThat(contentMapper.selectByIds(List.of(9103021L, 9103022L)))
                .as("comment_content 的 <foreach> 同样要真跑")
                .hasSize(2);
        assertThat(contentMapper.selectByIds(List.of(9103021L)))
                .hasSize(1);
    }

    // ====================================================================================
    // ⑤ 真 Spring 事务：@Transactional 生效 ⇒ 成功一次提交 / 失败整笔回滚
    // ====================================================================================

    @Test
    @DisplayName("🔴 真事务：insertCommentTx 成功 ⇒ 业务行 + subject 计数 + 事实 outbox 一次提交")
    void transactionalInsertCommitsEverythingTogether() {
        transactionalService.insertCommentTx(auditMessage(9103031L, "c3-tx-ok", 0L), 0);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comment_index WHERE id = 9103031", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comment_content WHERE comment_id = 9103031", Integer.class)).isEqualTo(1);
        assertCounts("c3-tx-ok", 1L, 1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comment_domain_fact_outbox WHERE event_id = 'rtw.comment.9103031.created'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM comment_fact_stream WHERE aggregate_id = '9103031'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("🔴 真事务：父评论不存在 ⇒ 整笔回滚（业务行 / subject 计数 / 事实行都不落盘）")
    void transactionalInsertRollsBackEverythingWhenParentMissing() {
        // parentId 指向一条不存在的评论 ⇒ incrementParentReplyCount 返回 0
        // ⇒ B 侧 comment.go:138-140 抛 CommentNotFound ⇒ 整个事务回滚
        assertThatThrownBy(() -> transactionalService.insertCommentTx(
                auditMessage(9103032L, "c3-tx-rollback", 9199999L), 0))
                .isInstanceOf(CommentExceptions.CommentNotFound.class);

        // 🔴 这些断言才是本测试的意义：@Transactional 若没生效（自动提交），
        //    上面第 ⑥ 步之前的写入（comment_content / comment_index / subject 计数）就会留在库里。
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comment_index WHERE id = 9103032", Integer.class))
                .as("comment_index 必须被回滚").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comment_content WHERE comment_id = 9103032", Integer.class))
                .as("comment_content 必须被回滚").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM subject WHERE target_id = 'c3-tx-rollback'", Integer.class))
                .as("subject 计数（在失败点之前写入）必须一并回滚").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comment_domain_fact_outbox WHERE event_id = 'rtw.comment.9103032.created'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM comment_fact_stream WHERE aggregate_id = '9103032'", Integer.class))
                .as("事实流版本号也必须回滚（取号发生在同一事务内）").isZero();
    }

    // ====================================================================================
    // helpers
    // ====================================================================================

    /** 造一条 Kafka 消费侧的审计消息（{@code createTime} = Unix 秒）。 */
    private static CommentAuditMessage auditMessage(long commentId, String targetId, long parentId) {
        return new CommentAuditMessage(
                commentId,          // comment_id（雪花 ID）
                "article",         // target_type
                targetId,          // target_id
                7L,                // user_id
                0L,                // root_id
                parentId,          // parent_id
                "c3 tx content",   // content
                "{\"k\":\"v\"}",   // meta（合法 JSON ⇒ 原样保留）
                9L,                // owner_id
                0L,                // attribute
                1761266400L);      // create_time（Unix 秒）
    }

    private static void insertIndex(long id, String targetId, int state, long likeCount, OffsetDateTime createdAt) {
        CommentIndexEntity index = new CommentIndexEntity();
        index.setId(id);
        index.setTargetType("article");
        index.setTargetId(targetId);
        index.setUserId(7L);
        index.setRootId(0L);
        index.setParentId(0L);
        index.setState(state);
        index.setAttribute(0L);
        index.setCreatedAt(createdAt);
        indexMapper.insert(index);
        if (likeCount != 0L) {
            jdbc.update("UPDATE comment_index SET like_count = ? WHERE id = ?", likeCount, id);
        }
    }

    private static void insertContent(long commentId, String content, OffsetDateTime createdAt) {
        CommentContentEntity entity = new CommentContentEntity();
        entity.setCommentId(commentId);
        entity.setContent(content);
        entity.setMeta("{}");
        entity.setCreatedAt(createdAt);
        contentMapper.insert(entity);
    }

    private static void seedSubject(String targetType, String targetId, long total, long root) {
        jdbc.update("INSERT INTO subject (target_type, target_id, total_count, root_count, state, attribute, owner_id) "
                + "VALUES (?, ?, ?, ?, 0, 0, 2002)", targetType, targetId, total, root);
    }

    /** 断言 subject 行的 [total_count, root_count]。 */
    private static void assertCounts(String targetId, long total, long root) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT total_count, root_count FROM subject WHERE target_id = ?", targetId);
        assertThat(((Number) row.get("total_count")).longValue()).isEqualTo(total);
        assertThat(((Number) row.get("root_count")).longValue()).isEqualTo(root);
    }

    // ====================================================================================
    // 基础设施
    // ====================================================================================

    /** 用本模块 {@code application.yml} 的真实 flyway 配置跑迁移（不硬编码）。 */
    private static void migrateWithModuleConfig(String database) throws IOException {
        Properties p = moduleYaml();
        assertThat(p.getProperty("spring.flyway.table"))
                .as("读到的必须是本模块自己的 application.yml（账本表名对不上 ⇒ 可能读串了）")
                .isEqualTo("flyway_schema_history_comment");
        Flyway.configure()
                .dataSource(containerJdbcUrl(database), TestPostgres.username(), TestPostgres.password())
                .locations(p.getProperty("spring.flyway.locations", "classpath:db/migration"))
                .schemas(p.getProperty("spring.flyway.schemas", "public"))
                .table(p.getProperty("spring.flyway.table", "flyway_schema_history"))
                .baselineOnMigrate(Boolean.parseBoolean(
                        p.getProperty("spring.flyway.baseline-on-migrate", "false")))
                .baselineVersion(String.valueOf(p.getProperty("spring.flyway.baseline-version", "0")).trim())
                .load()
                .migrate();
    }

    private static Properties moduleYaml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("comment-module-app", new ClassPathResource("application.yml"));
        Properties p = new Properties();
        for (PropertySource<?> s : sources) {
            if (s instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    Object v = s.getProperty(name);
                    if (v != null) {
                        p.setProperty(name, String.valueOf(v));
                    }
                }
            }
        }
        return p;
    }

    private static String containerJdbcUrl(String database) {
        String base = TestPostgres.jdbcUrl();
        int q = base.indexOf('?');
        String bare = q >= 0 ? base.substring(0, q) : base;
        int slash = bare.lastIndexOf('/');
        String host = slash >= 0 ? bare.substring(0, slash + 1) : bare;
        return host + database + "?TimeZone=Asia/Shanghai";
    }

    private static void createProbeDatabase(String database) {
        String adminUrl = containerJdbcUrl("postgres");
        try (Connection conn = DriverManager.getConnection(adminUrl,
                TestPostgres.username(), TestPostgres.password());
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("探针库创建失败: " + adminUrl, e);
        }
    }
}
