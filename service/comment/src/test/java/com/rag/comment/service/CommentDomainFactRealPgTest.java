package com.rag.comment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.comment.domain.entity.CommentContentEntity;
import com.rag.comment.domain.entity.CommentDomainFactOutboxEntity;
import com.rag.comment.domain.entity.CommentIndexEntity;
import com.rag.comment.domain.service.CommentDomainFactService;
import com.rag.comment.mapper.CommentContentMapper;
import com.rag.comment.mapper.CommentDomainFactOutboxMapper;
import com.rag.comment.mapper.CommentFactStreamMapper;
import com.rag.comment.mapper.CommentIndexMapper;
import com.rag.comment.support.TestPostgres;
import com.rag.common.communityfact.EventSender;
import com.rag.common.communityfact.TechnicalReceipt;
import com.rag.common.communityfact.Wire;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p>🔴 <b>真 PostgreSQL ＋ 真 MyBatis ＋ 真 Spring 事务</b>上的事实投递语义守门测试。
 *
 * <h2>1. 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>探针库隔离</b>：B 侧各 gate test 各起一个 PG 容器；C 侧共用<b>进程内单例</b>容器
 *       ⇒ 本类在同容器里另开一个探针库 {@code comment_fact_probe}。
 *       <br>🔴 <b>这条不是洁癖，是必需</b>：取件查询
 *       {@code selectNextDispatchableForUpdate} 取的是「<b>任意一条</b>可投递行」
 *       ⇒ 若与其它测试类共用同一个库，别人的待投递行会被本类领走 ⇒ 断言会假绿/假红。</li>
 *   <li><b>共享层 1:N 拆分后的类型改名</b>：B 侧的 {@code Wire.Sender} / {@code Wire.Event} /
 *       {@code Wire.TechnicalReceipt} 在 C 侧是共享层顶层类型
 *       {@code com.rag.common.communityfact.EventSender / Event / TechnicalReceipt}
 *       ⇒ 夹具的 lambda 签名按新接口改写（<b>语义逐条未变</b>）。</li>
 *   <li><b>新增 5 条 B 侧只在别的类里守、但本域必须守的不变量</b>：
 *       ① 失败(2) 可重试 / 阻塞(3) 不再被调度；② 前序屏障在 SQL 里；
 *       ③ legacy 守卫 {@code >0} ⇒ 拒绝发新事实；④ {@code payload.aggregate_version} 必须为 null；
 *       ⑤ 投递只改 {@code delivery_status}，绝不碰信封列。</li>
 * </ul>
 *
 * <h2>2. 语义还一样吗</h2>
 * <p>一样。B 侧 {@code CommentRpcGoSemanticsGateTest} 的 7 条 MISMATCH 守门断言
 * （MISMATCH-01/02/03/04/05/06/07）<b>逐条保留</b>，
 * 外加「正向对照组」与「span 契约」两条（防止后面的「应当 BLOCKED」是因为夹具坏了而假绿）。
 *
 * <h2>3. B 出处</h2>
 * <ul>
 *   <li>{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentRpcGoSemanticsGateTest.java}
 *       （659 行 · 本类的逐条 oracle）</li>
 *   <li>投递语义事实来源：{@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentFactDeliveryService.java}
 *       与 {@code .../service/CommentDeliveryValidator.java}（C 侧逐字抄，落在
 *       {@code com.rag.comment.service}）</li>
 *   <li>Go 侧：{@code service/comment/rpc/internal/model/fact_delivery.go:20-127}
 *       ＋ {@code service/common/communityfact/wire.go:121-131}</li>
 * </ul>
 *
 * <h2>🔴 为什么必须是「真 PG ＋ 真 MyBatis ＋ 真事务」</h2>
 * <p>MISMATCH-01/02 的本质是<b>事务边界</b>：状态更新与异常抛出的先后关系。
 * 用 mock 数据层测不到任何东西（mock 的 mapper 不参与事务，UPDATE「永远成功」）。
 * 用 H2 也测不到：{@code SELECT … FOR UPDATE SKIP LOCKED}、jsonb、
 * {@code INSERT … ON CONFLICT … RETURNING} 在 H2 上不是同一套语义。
 * ⇒ 必须是真 PG。而且必须是<b>真 MyBatis ＋ 真 DataSourceTransactionManager</b>：
 * 本类手工搭 {@link SqlSessionTemplate}（{@code SpringManagedTransactionFactory}）
 * ⇒ mapper 的 SQL 与 {@link CommentFactDeliveryService} 的事务跑在<b>同一条连接</b>上，
 * 与生产（Spring Boot + MyBatis-Plus）同构。若用 {@code SqlSessionFactory.openSession()}
 * 直接开会话，mapper 会拿到<b>另一条连接</b> ⇒ 事务形同虚设 ⇒ 测试必然假绿。
 */
class CommentDomainFactRealPgTest {

    /** 本类专用探针库（与其它测试类的库互不干扰）。 */
    private static final String PROBE_DB = "comment_fact_probe";

    private static final Logger LOG = LoggerFactory.getLogger(CommentDomainFactRealPgTest.class);

    /** Go 侧 {@code domain_fact.go:97} 的 {@code OccurredAt = EventTime} ⇒ 载荷与信封必须同一时刻。 */
    private static final String OCCURRED_AT = "2026-10-24T06:00:00Z";
    private static final String AVAILABLE_AT = "2026-10-24T06:00:01Z";

    /** Go 的 {@code commentFactProducer}（fact_delivery.go:18）。 */
    private static final String PRODUCER = "rtw.comment-rpc";
    /** Go 的 {@code commentFact.SchemaVersion}（domain_fact.go:99）。 */
    private static final String SCHEMA_VERSION = "rtw.community-fact.v1";
    private static final String EVENT_TYPE = "community.comment.created";

    /**
     * 自建收集型 exporter —— <b>不引 {@code opentelemetry-sdk-testing}</b>
     * （只为测试引入它反而扩大依赖面；范式照抄 B 侧 {@code CommentSpanWiringTest}）。
     */
    static final class CollectingExporter implements SpanExporter {
        final List<SpanData> spans = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static final CollectingExporter EXPORTER = new CollectingExporter();

    private static JdbcTemplate jdbc;
    private static CommentDomainFactOutboxMapper outboxMapper;
    private static CommentIndexMapper indexMapper;
    private static CommentContentMapper contentMapper;
    private static CommentFactStreamMapper factStreamMapper;
    private static CommentDomainFactService factService;
    private static CommentFactDeliveryService deliveryService;

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
        configuration.setEnvironment(new Environment("go-gate",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(CommentDomainFactOutboxMapper.class);
        configuration.addMapper(CommentFactStreamMapper.class);
        configuration.addMapper(CommentIndexMapper.class);
        configuration.addMapper(CommentContentMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);

        outboxMapper = template.getMapper(CommentDomainFactOutboxMapper.class);
        indexMapper = template.getMapper(CommentIndexMapper.class);
        contentMapper = template.getMapper(CommentContentMapper.class);
        factStreamMapper = template.getMapper(CommentFactStreamMapper.class);

        ObjectMapper objectMapper = new ObjectMapper();
        factService = new CommentDomainFactService(outboxMapper, factStreamMapper, objectMapper);
        deliveryService = new CommentFactDeliveryService(
                outboxMapper,
                new CommentDeliveryValidator(objectMapper),
                new DataSourceTransactionManager(dataSource));

        // 🔴 把 GlobalOpenTelemetry 指向本测试的收集器（范式照抄 B 侧 CommentSpanWiringTest）。
        //    不装 SDK ⇒ GlobalOpenTelemetry.getTracer(...) 是 no-op ⇒ `Span.current()` 永远是 invalid
        //    ⇒ 下面的守门会「平凡通过」⇒ 装它是**守门能生效**的前提。
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(EXPORTER))
                        .build())
                .build());
    }

    @AfterAll
    static void uninstallSdk() {
        GlobalOpenTelemetry.resetForTest();
    }

    @AfterEach
    void clean() {
        // 取件查询是「任意一条可投递行」⇒ 必须清干净，否则下一个用例会领到上一条。
        jdbc.update("DELETE FROM comment_domain_fact_outbox");
        jdbc.update("DELETE FROM comment_fact_stream");
        jdbc.update("DELETE FROM comment_index");
        jdbc.update("DELETE FROM comment_content");
    }

    // ====================================================================================
    // ① 正向对照组：合法冻结信封必须被正常投递并落技术回执
    //    （没有这一条，后面的「应当 BLOCKED」可能是因为夹具本身坏了而假绿）
    // ====================================================================================

    @Test
    @DisplayName("🔴 正向对照：合法冻结信封 ⇒ 投递成功 + 6 列技术元数据落盘")
    void validFrozenEnvelopeIsDeliveredAndAccepted() throws Exception {
        String aggregateId = "c2-agg-7001";
        insertValidFact(aggregateId);

        boolean sent = deliveryService.dispatchOnce(Context.root(), acceptedSender("2026-10-24T06:00:09Z"), LOG);

        assertThat(sent).isTrue();
        assertThat(deliveryStatus(aggregateId)).isEqualTo(1);      // DeliveryAccepted
        assertThat(jdbc.queryForObject(
                "SELECT technical_receipt_id FROM comment_domain_fact_outbox WHERE event_id = ?",
                String.class, eventId(aggregateId))).isEqualTo("rcpt-" + aggregateId);
        assertThat(jdbc.queryForObject(
                "SELECT technical_offset FROM comment_domain_fact_outbox WHERE event_id = ?",
                Long.class, eventId(aggregateId))).isEqualTo(42L);
        assertThat(jdbc.queryForObject(
                "SELECT technical_input_hash FROM comment_domain_fact_outbox WHERE event_id = ?",
                String.class, eventId(aggregateId))).isEqualTo("a".repeat(64));
    }

    // ====================================================================================
    // ② MISMATCH-01：冻结信封非法 ⇒ DeliveryBlocked 必须落盘
    // ====================================================================================

    @Test
    @DisplayName("🔴 MISMATCH-01：producer 不匹配 ⇒ delivery_status=3 落盘（抛错也必须落盘）")
    void blockedFrozenEnvelopeIsPersistedEvenThoughTheCallThrows() {
        String aggregateId = "c2-agg-7002";
        // producer 不匹配 ⇒ Go 的 validCommentDelivery（fact_delivery.go:23）判非法
        String payload = payloadJson(aggregateId, OCCURRED_AT, null);
        String envelope = envelopeJson(aggregateId, "1", payload)
                .replace("\"producer\":\"" + PRODUCER + "\"", "\"producer\":\"rtw.someone-else\"");
        insertFact(aggregateId, payload, envelope);

        assertThatThrownBy(() -> deliveryService.dispatchOnce(Context.root(), mustNotBeCalled(), LOG))
                .isInstanceOf(Wire.FrozenEnvelopeBlockedException.class);

        // 🔴 这一行才是本测试的全部意义：Go 在 fact_delivery.go:79-83 是「UPDATE 后 Commit」。
        //    若用 @Transactional + RuntimeException ⇒ 回滚 ⇒ status 恒为 0（无限重领活锁）。
        assertThat(deliveryStatus(aggregateId)).isEqualTo(3);
    }

    // ====================================================================================
    // ③ MISMATCH-02：投递失败 ⇒ DeliveryFailed + retry_count+1 必须落盘
    // ====================================================================================

    @Test
    @DisplayName("🔴 MISMATCH-02：投递失败 ⇒ delivery_status=2 且 retry_count=1 落盘")
    void failedDeliveryIsPersistedWithRetryCountEvenThoughTheCallThrows() {
        String aggregateId = "c2-agg-7003";
        insertValidFact(aggregateId);

        IllegalStateException cause = new IllegalStateException("DC endpoint refused the connection");
        assertThatThrownBy(() -> deliveryService.dispatchOnce(Context.root(), senderFailingWith(cause), LOG))
                .isInstanceOf(CommentFactDeliveryService.DeliveryDeferredException.class)
                // Go 的 fact_delivery.go:110 是 `return false, sendErr` ⇒ 原始错误必须带出来
                .hasCause(cause);

        assertThat(deliveryStatus(aggregateId)).isEqualTo(2);
        assertThat(retryCount(aggregateId)).isEqualTo(1);
    }

    // ====================================================================================
    // ④ MISMATCH-03：technical_received_at 必须是 DC 回执的 received_at
    // ====================================================================================

    @Test
    @DisplayName("🔴 MISMATCH-03：technical_received_at = DC 回执的 received_at（≠ 本地投递时刻）")
    void technicalReceivedAtComesFromTheDataCenterReceipt() throws Exception {
        String aggregateId = "c2-agg-7004";
        insertValidFact(aggregateId);
        // 一个刻意很早的时刻 ⇒ 若实现仍写本地 now，这一列就会与它相差数年
        String dcReceivedAt = "2020-01-02T03:04:05Z";

        deliveryService.dispatchOnce(Context.root(), acceptedSender(dcReceivedAt), LOG);

        Instant received = technicalReceivedAt(aggregateId);
        Instant delivered = deliveredAt(aggregateId);
        assertThat(received).isEqualTo(Instant.parse(dcReceivedAt));
        // delivered_at 是本地投递时刻 ⇒ 两列必须是两个不同的值
        assertThat(delivered).isNotEqualTo(received);
        assertThat(delivered).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
    }

    // ====================================================================================
    // ⑤ MISMATCH-04：载荷多一个未知字段 ⇒ BLOCKED
    // ====================================================================================

    @Test
    @DisplayName("🔴 MISMATCH-04：载荷多一个未知字段 ⇒ BLOCKED（Go 的 DisallowUnknownFields）")
    void payloadWithUnknownFieldIsBlocked() {
        String aggregateId = "c2-agg-7005";
        String payload = payloadJson(aggregateId, OCCURRED_AT, "tampered_extra_field");
        String envelope = envelopeJson(aggregateId, "1", payload);
        insertFact(aggregateId, payload, envelope);

        assertThatThrownBy(() -> deliveryService.dispatchOnce(Context.root(), mustNotBeCalled(), LOG))
                .isInstanceOf(Wire.FrozenEnvelopeBlockedException.class);

        assertThat(deliveryStatus(aggregateId)).isEqualTo(3);
    }

    // ====================================================================================
    // ⑥ MISMATCH-05：载荷的三个时间字段必须是 RFC3339
    // ====================================================================================

    @Test
    @DisplayName("🔴 MISMATCH-05：载荷 event_time 非 RFC3339 ⇒ BLOCKED")
    void payloadWithMalformedEventTimeIsBlocked() {
        String aggregateId = "c2-agg-7006";
        // Go 侧 commentFact.EventTime 是 time.Time（domain_fact.go:77）
        // ⇒ encoding/json 解码非 RFC3339 直接报错 ⇒ StrictDecode 失败 ⇒ BLOCKED
        String payload = payloadJson(aggregateId, "not-a-timestamp", null);
        String envelope = envelopeJson(aggregateId, "1", payload);
        insertFact(aggregateId, payload, envelope);

        assertThatThrownBy(() -> deliveryService.dispatchOnce(Context.root(), mustNotBeCalled(), LOG))
                .isInstanceOf(Wire.FrozenEnvelopeBlockedException.class);

        assertThat(deliveryStatus(aggregateId)).isEqualTo(3);
    }

    @Test
    @DisplayName("🔴 MISMATCH-05：载荷 available_at 非 RFC3339 ⇒ BLOCKED")
    void payloadWithMalformedAvailableAtIsBlocked() {
        String aggregateId = "c2-agg-7007";
        String payload = payloadJson(aggregateId, OCCURRED_AT, null)
                .replace("\"available_at\":\"" + AVAILABLE_AT + "\"", "\"available_at\":\"2026-10-24 06:00:01\"");
        String envelope = envelopeJson(aggregateId, "1", payload);
        insertFact(aggregateId, payload, envelope);

        assertThatThrownBy(() -> deliveryService.dispatchOnce(Context.root(), mustNotBeCalled(), LOG))
                .isInstanceOf(Wire.FrozenEnvelopeBlockedException.class);

        assertThat(deliveryStatus(aggregateId)).isEqualTo(3);
    }

    // ====================================================================================
    // ⑦ MISMATCH-06：信封 / 载荷的标量类型必须精确匹配（Go 的 encoding/json 规则）
    // ====================================================================================

    @Test
    @DisplayName("🔴 MISMATCH-06：信封 schema_version 被字符串化 ⇒ BLOCKED")
    void envelopeWithStringifiedSchemaVersionIsBlocked() {
        String aggregateId = "c2-agg-7008";
        String payload = payloadJson(aggregateId, OCCURRED_AT, null);
        // Go 的 communityfact.Event.SchemaVersion 是 int ⇒ {"schema_version":"1"} 解码报错
        String envelope = envelopeJson(aggregateId, "\"1\"", payload);
        insertFact(aggregateId, payload, envelope);

        assertThatThrownBy(() -> deliveryService.dispatchOnce(Context.root(), mustNotBeCalled(), LOG))
                .isInstanceOf(Wire.FrozenEnvelopeBlockedException.class);

        assertThat(deliveryStatus(aggregateId)).isEqualTo(3);
    }

    @Test
    @DisplayName("🔴 MISMATCH-06：载荷的字符串字段被数字化 ⇒ BLOCKED（Go 的 string 字段不收数字）")
    void payloadWithNumericStringFieldIsBlocked() {
        String aggregateId = "c2-agg-7009";
        // Go 的 commentFact.TargetType 是 string（domain_fact.go:65）
        // ⇒ JSON 数字解进 string 字段报错 → StrictDecode 失败 → BLOCKED。
        //
        // ⚠️ 为什么用 target_type 而不是 schema_version：
        //   若把 schema_version 改成数字，宽松解码会读成 "1"，
        //   而 "1" ≠ "rtw.community-fact.v1" ⇒ **照样 BLOCKED**，
        //   但原因是「版本号不匹配」而不是「类型不合法」
        //   ⇒ 那条断言即使没有强转禁令也会绿 ⇒ **是因错误原因而绿的假守卫**（§3.3）。
        String payload = payloadJson(aggregateId, OCCURRED_AT, null)
                .replace("\"target_type\":\"article\"", "\"target_type\":5");
        String envelope = envelopeJson(aggregateId, "1", payload);
        insertFact(aggregateId, payload, envelope);

        assertThatThrownBy(() -> deliveryService.dispatchOnce(Context.root(), mustNotBeCalled(), LOG))
                .isInstanceOf(Wire.FrozenEnvelopeBlockedException.class);

        assertThat(deliveryStatus(aggregateId)).isEqualTo(3);
    }

    // ====================================================================================
    // ⑧ MISMATCH-07：INSERT 的 updated_at 必须是入库时刻，不是评论创建时刻
    // ====================================================================================

    @Test
    @DisplayName("🔴 MISMATCH-07：comment_index INSERT 的 updated_at = 入库时刻（≠ created_at）")
    void commentIndexInsertUsesInsertTimeForUpdatedAt() {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        CommentIndexEntity index = new CommentIndexEntity();
        index.setId(9102001L);
        index.setTargetType("article");
        index.setTargetId("a-c2-7101");
        index.setUserId(7L);
        index.setRootId(0L);
        index.setParentId(0L);
        index.setState(0);
        index.setAttribute(0L);
        index.setCreatedAt(createdAt);

        indexMapper.insert(index);

        OffsetDateTime storedCreated = timestamp("comment_index", "id", 9102001L, "created_at");
        OffsetDateTime storedUpdated = timestamp("comment_index", "id", 9102001L, "updated_at");
        // created_at = 评论创建时刻（Kafka 的 create_time）
        assertThat(storedCreated.toInstant()).isEqualTo(createdAt.toInstant());
        // updated_at = 入库时刻 ⇒ 必须比 created_at 晚一个多小时（修复前两列相同）
        assertThat(storedUpdated.toInstant()).isAfter(createdAt.toInstant().plusSeconds(600));
        assertThat(storedUpdated.toInstant()).isBefore(Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("🔴 MISMATCH-07：comment_content INSERT 的 updated_at = 入库时刻（≠ created_at）")
    void commentContentInsertUsesInsertTimeForUpdatedAt() {
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        CommentContentEntity content = new CommentContentEntity();
        content.setCommentId(9102002L);
        content.setContent("gate-test-content");
        content.setMeta("{}");
        content.setCreatedAt(createdAt);

        contentMapper.insert(content);

        OffsetDateTime storedCreated = timestamp("comment_content", "comment_id", 9102002L, "created_at");
        OffsetDateTime storedUpdated = timestamp("comment_content", "comment_id", 9102002L, "updated_at");
        assertThat(storedCreated.toInstant()).isEqualTo(createdAt.toInstant());
        assertThat(storedUpdated.toInstant()).isAfter(createdAt.toInstant().plusSeconds(600));
        assertThat(storedUpdated.toInstant()).isBefore(Instant.now().plusSeconds(60));
    }

    // ====================================================================================
    // ⑨ span 契约：fact 投递链路**不产生** span 属性（与 Go 侧一致）
    // ====================================================================================

    @Test
    @DisplayName("🔴 装置活性：同线程挂的真 span **能**收到属性（否则下面那条会平凡通过）")
    void spanCollectorIsAliveOnThisThread() {
        Span probe = GlobalOpenTelemetry.getTracer("gate").spanBuilder("gate.probe").startSpan();
        try (Scope ignored = probe.makeCurrent()) {
            probe.setAttribute("gate.probe.attr", "v");
        } finally {
            probe.end();
        }
        assertThat(EXPORTER.spans)
                .as("观测装置必须活着：能导出的 span 上要真能看到人为写的属性")
                .anyMatch(s -> "gate.probe".equals(s.getName())
                        && s.getAttributes().asMap().containsKey(AttributeKey.stringKey("gate.probe.attr")));
    }

    @Test
    @DisplayName("🔴 span 契约：投递链路**不**向 span 写 comment.fact.* 属性（与 Go 侧零 span 属性一致）")
    void factDeliveryDoesNotWriteSpanAttributes() throws Exception {
        String aggregateId = "c2-agg-7010";
        insertValidFact(aggregateId);

        Span observed = GlobalOpenTelemetry.getTracer("gate").spanBuilder("gate.delivery").startSpan();
        try (Scope ignored = observed.makeCurrent()) {
            assertThat(deliveryService.dispatchOnce(Context.root(), acceptedSender("2026-10-24T06:00:09Z"), LOG))
                    .isTrue();
        } finally {
            observed.end();
        }

        SpanData exported = EXPORTER.spans.stream()
                .filter(s -> "gate.delivery".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("gate.delivery span 未被导出 ⇒ 观测装置有问题"));
        assertThat(exported.getAttributes().asMap().keySet())
                .as("投递链路不得向 span 写 comment.fact.* 属性（Go 侧的 fact 投递 span 不带这两个属性）")
                .noneMatch(k -> k.getKey().startsWith("comment.fact."));
    }

    // ====================================================================================
    // ⑩ 投递状态语义：失败(2) 可重试 / 阻塞(3) 不再被调度
    // ====================================================================================

    @Test
    @DisplayName("🔴 失败(2) 仍可被取件查询领走（可重试）")
    void failedStatusIsRetryable() {
        String failedAggregate = "c2-agg-7011";
        insertValidFact(failedAggregate);
        assertThatThrownBy(() -> deliveryService.dispatchOnce(Context.root(),
                senderFailingWith(new IllegalStateException("dc down")), LOG))
                .isInstanceOf(CommentFactDeliveryService.DeliveryDeferredException.class);
        assertThat(deliveryStatus(failedAggregate)).isEqualTo(2);

        // 取件查询的过滤条件是 `delivery_status IN (0, 2)` ⇒ 失败行仍可被领走（可重试）
        CommentDomainFactOutboxEntity picked = outboxMapper.selectNextDispatchableForUpdate();
        assertThat(picked)
                .as("失败(2) 的行必须仍可被取件查询领走（Go fact_delivery.go:96-101）")
                .isNotNull();
        assertThat(picked.getAggregateId()).isEqualTo(failedAggregate);
    }

    @Test
    @DisplayName("🔴 阻塞(3) 不再被调度器领走")
    void blockedStatusIsNotScheduledAgain() {
        String blockedAggregate = "c2-agg-7012";
        String payload = payloadJson(blockedAggregate, OCCURRED_AT, null);
        insertFact(blockedAggregate, payload,
                envelopeJson(blockedAggregate, "1", payload)
                        .replace("\"producer\":\"" + PRODUCER + "\"", "\"producer\":\"rtw.someone-else\""));
        assertThatThrownBy(() -> deliveryService.dispatchOnce(Context.root(), mustNotBeCalled(), LOG))
                .isInstanceOf(Wire.FrozenEnvelopeBlockedException.class);
        assertThat(deliveryStatus(blockedAggregate)).isEqualTo(3);

        // 取件查询的过滤条件是 `delivery_status IN (0, 2)` ⇒ 阻塞(3) 的行不再被领走
        assertThat(outboxMapper.selectNextDispatchableForUpdate())
                .as("阻塞(3) 的行必须不再被调度器领走（等人工迁移处理）")
                .isNull();
    }

    @Test
    @DisplayName("🔴 前序屏障在 SQL 里：同 aggregate 的 v2 在 v1 未 Accepted 前不可被领取")
    void predecessorBarrierBlocksLaterVersionUntilAccepted() {
        String aggregateId = "c2-agg-7013";
        // ⚠️ 两条必须是**同一个 aggregate_id**、不同 fact_version ⇒ 前序屏障（NOT EXISTS）才成立
        outboxMapper.appendFact("rtw.comment.c2-agg-7013.created", EVENT_TYPE,
                "{\"aggregate_id\":\"" + aggregateId + "\"}", aggregateId, 1L,
                "{\"event_id\":\"rtw.comment.c2-agg-7013.created\"}");
        outboxMapper.appendFact("rtw.comment.c2-agg-7013.v2.created", EVENT_TYPE,
                "{\"aggregate_id\":\"" + aggregateId + "\"}", aggregateId, 2L,
                "{\"event_id\":\"rtw.comment.c2-agg-7013.v2.created\"}");

        // v1 未 Accepted ⇒ NOT EXISTS 屏障挡住 v2 ⇒ 只能领到 v1
        CommentDomainFactOutboxEntity picked = outboxMapper.selectNextDispatchableForUpdate();
        assertThat(picked).isNotNull();
        assertThat(picked.getFactVersion()).isEqualTo(1L);

        // v1 被接受后 ⇒ v2 才可领
        // ⚠️ 只把 **v1** 置为 Accepted —— 若按 aggregate_id 一把置 1，v2 也会变成 Accepted
        //    ⇒ 它就不再满足 `delivery_status IN (0, 2)` ⇒ 取件查询会返回 null（那是断言写错，不是实现错）。
        jdbc.update("UPDATE comment_domain_fact_outbox SET delivery_status = 1 "
                + "WHERE event_id = 'rtw.comment.c2-agg-7013.created'");
        CommentDomainFactOutboxEntity next = outboxMapper.selectNextDispatchableForUpdate();
        assertThat(next).isNotNull();
        assertThat(next.getFactVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("🔴 投递只改 delivery_status 等元数据列，绝不碰信封列（不可变触发器不拦）")
    void deliveryOnlyTouchesMetadataColumns() throws Exception {
        String aggregateId = "c2-agg-7014";
        insertValidFact(aggregateId);
        String envelopeBefore = jdbc.queryForObject(
                "SELECT delivery_envelope::text FROM comment_domain_fact_outbox WHERE event_id = ?",
                String.class, eventId(aggregateId));

        deliveryService.dispatchOnce(Context.root(), acceptedSender("2026-10-24T06:00:09Z"), LOG);

        String envelopeAfter = jdbc.queryForObject(
                "SELECT delivery_envelope::text FROM comment_domain_fact_outbox WHERE event_id = ?",
                String.class, eventId(aggregateId));
        assertThat(envelopeAfter)
                .as("投递路径绝不允许改写信封列 ⇒ 改了会被 comment_fact_identity_immutable 触发器拒绝")
                .isEqualTo(envelopeBefore);
        assertThat(deliveryStatus(aggregateId)).isEqualTo(1);
    }

    // ====================================================================================
    // ⑪ 事实生成侧：legacy 守卫 + payload.aggregate_version 必须为 null
    // ====================================================================================

    @Test
    @DisplayName("🔴 legacy 守卫 >0 ⇒ 拒绝发新事实（不补号、不跳过）")
    void legacyGuardRejectsNewFact() {
        String aggregateId = "c2-agg-legacy";
        // 造一条「无冻结信封的遗留事实」—— CHECK 约束是 NOT VALID ⇒ 存量行不受检
        // ⇒ 必须先摘掉约束再插，插完再原样装回（NOT VALID）。
        jdbc.update("ALTER TABLE comment_domain_fact_outbox DROP CONSTRAINT comment_new_fact_has_dc_wire");
        try {
            jdbc.update("""
                    INSERT INTO comment_domain_fact_outbox (event_id, event_type, payload, aggregate_id, fact_version)
                    VALUES ('rtw.comment.legacy.created', 'community.comment.created',
                            '{"aggregate_id":"c2-agg-legacy"}'::jsonb, 'c2-agg-legacy', NULL)
                    """);
        } finally {
            jdbc.update("""
                    ALTER TABLE comment_domain_fact_outbox ADD CONSTRAINT comment_new_fact_has_dc_wire
                    CHECK (aggregate_id IS NOT NULL AND fact_version IS NOT NULL AND delivery_envelope IS NOT NULL) NOT VALID
                    """);
        }

        try {
            // Go domain_fact.go:103-114：检测到未版本化的历史事实 ⇒ 直接返回错误
            assertThatThrownBy(() -> factService.append(fact(aggregateId, "2026-10-24T06:00:00Z")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unversioned legacy facts");

            // 守卫在取号**之前** ⇒ 版本号不能被消耗掉
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM comment_fact_stream WHERE aggregate_id = ?", Integer.class, aggregateId))
                    .isZero();
        } finally {
            jdbc.update("DELETE FROM comment_domain_fact_outbox WHERE event_id = 'rtw.comment.legacy.created'");
        }
    }

    @Test
    @DisplayName("🔴 payload.aggregate_version 必须为 null（版本号的唯一权威在信封）")
    void payloadAggregateVersionMustBeNull() {
        String aggregateId = "c2-agg-7015";
        long version = factService.append(fact(aggregateId, "2026-10-24T06:00:00Z"));
        assertThat(version).isEqualTo(1L);

        // ⚠️ 用 jsonb_typeof 而不是比对文本：PG 的 jsonb 文本输出是 `"key": value`（冒号后有空格），
        //   且键序由 PG 重排 ⇒ 比文本既脆又不可读。
        //   jsonb_typeof(payload->'aggregate_version') = 'null' ⇒ 键**存在**且值是 JSON null；
        //   若键**不存在** ⇒ 表达式返回 SQL NULL ⇒ 两者可区分。
        String payloadType = jdbc.queryForObject(
                "SELECT jsonb_typeof(payload->'aggregate_version') FROM comment_domain_fact_outbox "
                        + "WHERE event_id = ?", String.class, eventId(aggregateId));
        assertThat(payloadType)
                .as("载荷里 aggregate_version 必须**存在**且为 null"
                        + "（Go 的 commentFact.AggregateVersion 是 *int64 且未填即 nil）")
                .isEqualTo("null");

        // 信封里必须有版本号（aggregate_version = 1）
        String envelopeVersion = jdbc.queryForObject(
                "SELECT delivery_envelope->>'aggregate_version' FROM comment_domain_fact_outbox WHERE event_id = ?",
                String.class, eventId(aggregateId));
        assertThat(envelopeVersion)
                .as("信封里必须有版本号（aggregate_version=1）")
                .isEqualTo("1");
    }

    // ====================================================================================
    // 夹具
    // ====================================================================================

    private static String eventId(String aggregateId) {
        return "rtw.comment." + aggregateId + ".created";
    }

    /** 造一个最小可用的 fact（Go 的必填四项 + 业务字段）。 */
    private static CommentDomainFactService.CommentFact fact(String aggregateId, String eventTime) {
        CommentDomainFactService.CommentFact f = new CommentDomainFactService.CommentFact();
        f.eventId = eventId(aggregateId);
        f.eventType = EVENT_TYPE;
        f.aggregateId = aggregateId;
        f.operationId = eventId(aggregateId);
        f.subjectRef = "rtw.identity/platform/2002";
        f.operatorRef = "rtw.identity/platform/1001";
        f.targetType = "article";
        f.targetId = "a-diff";
        f.operation = "created";
        f.sourceRef = "src-" + aggregateId;
        f.commentId = aggregateId;
        f.eventTime = eventTime;
        return f;
    }

    /**
     * 按 Go 的 {@code commentFact}（domain_fact.go:55-80）逐字段构造载荷 JSON。
     *
     * @param eventTime  {@code event_time} 的字面量（用于注入畸形值）
     * @param extraField 非 null 时追加一个<b>未知字段</b>（Go 的 DisallowUnknownFields 会因此失败）
     */
    private static String payloadJson(String aggregateId, String eventTime, String extraField) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"schema_version\":\"").append(SCHEMA_VERSION).append("\"");
        sb.append(",\"event_id\":\"").append(eventId(aggregateId)).append("\"");
        sb.append(",\"event_type\":\"").append(EVENT_TYPE).append("\"");
        sb.append(",\"producer\":\"").append(PRODUCER).append("\"");
        sb.append(",\"aggregate_id\":\"").append(aggregateId).append("\"");
        // ⚠️ aggregate_version 刻意**不出现**：Go 的载荷里它必须是 nil（版本号的唯一权威在信封）
        sb.append(",\"operation_id\":\"").append(eventId(aggregateId)).append("\"");
        sb.append(",\"subject_ref\":\"rtw.identity/platform/7\"");
        sb.append(",\"target_type\":\"article\"");
        sb.append(",\"target_id\":\"a-").append(aggregateId).append("\"");
        sb.append(",\"revision_status\":\"unknown\"");
        sb.append(",\"operation\":\"create\"");
        sb.append(",\"source_ref\":\"rtw.comment/").append(aggregateId).append("\"");
        sb.append(",\"comment_id\":\"").append(aggregateId).append("\"");
        sb.append(",\"visibility_state\":0");
        sb.append(",\"search_evidence\":false");
        sb.append(",\"event_time\":\"").append(eventTime).append("\"");
        sb.append(",\"occurred_at\":\"").append(OCCURRED_AT).append("\"");
        sb.append(",\"available_at\":\"").append(AVAILABLE_AT).append("\"");
        if (extraField != null) {
            sb.append(",\"").append(extraField).append("\":\"injected\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 按 Go 的 {@code commentDeliveryEnvelope}（domain_fact.go:43-53）构造信封 JSON。
     *
     * @param schemaVersion {@code schema_version} 的<b>字面量</b>（用于注入字符串化的数值）
     */
    private static String envelopeJson(String aggregateId, String schemaVersion, String payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"event_id\":\"").append(eventId(aggregateId)).append("\"");
        sb.append(",\"event_type\":\"").append(EVENT_TYPE).append("\"");
        sb.append(",\"schema_version\":").append(schemaVersion);
        sb.append(",\"producer\":\"").append(PRODUCER).append("\"");
        sb.append(",\"aggregate_id\":\"").append(aggregateId).append("\"");
        sb.append(",\"aggregate_version\":1");
        sb.append(",\"operation_id\":\"").append(eventId(aggregateId)).append("\"");
        sb.append(",\"occurred_at\":\"").append(OCCURRED_AT).append("\"");
        sb.append(",\"payload\":").append(payload);
        sb.append("}");
        return sb.toString();
    }

    private static void insertValidFact(String aggregateId) {
        String payload = payloadJson(aggregateId, OCCURRED_AT, null);
        insertFact(aggregateId, payload, envelopeJson(aggregateId, "1", payload));
    }

    private static void insertFact(String aggregateId, String payload, String envelope) {
        insertFact(aggregateId, payload, envelope, 1L);
    }

    private static void insertFact(String aggregateId, String payload, String envelope, long factVersion) {
        // 走**生产同款**的写路径（显式 jsonb typeHandler），而不是另写一份 INSERT。
        outboxMapper.appendFact(eventId(aggregateId), EVENT_TYPE, payload, aggregateId, factVersion, envelope);
    }

    private static int deliveryStatus(String aggregateId) {
        return jdbc.queryForObject(
                "SELECT delivery_status FROM comment_domain_fact_outbox WHERE event_id = ?",
                Integer.class, eventId(aggregateId));
    }

    private static int retryCount(String aggregateId) {
        return jdbc.queryForObject(
                "SELECT retry_count FROM comment_domain_fact_outbox WHERE event_id = ?",
                Integer.class, eventId(aggregateId));
    }

    private static Instant technicalReceivedAt(String aggregateId) {
        return timestamp("comment_domain_fact_outbox", "event_id", eventId(aggregateId), "technical_received_at")
                .toInstant();
    }

    private static Instant deliveredAt(String aggregateId) {
        return timestamp("comment_domain_fact_outbox", "event_id", eventId(aggregateId), "delivered_at").toInstant();
    }

    private static OffsetDateTime timestamp(String table, String keyColumn, Object key, String column) {
        OffsetDateTime value = jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE " + keyColumn + " = ?",
                OffsetDateTime.class, key);
        assertThat(value).as(table + "." + column).isNotNull();
        return value;
    }

    private static EventSender acceptedSender(String receivedAt) {
        return (ctx, event, raw) -> {
            TechnicalReceipt receipt = new TechnicalReceipt();
            receipt.eventId = event.eventId;
            receipt.producer = event.producer;
            receipt.technicalStatus = "accepted";
            receipt.receiptId = "rcpt-" + event.aggregateId;
            receipt.inputHash = "a".repeat(64);
            receipt.offset = 42L;
            receipt.receivedAt = receivedAt;
            return receipt;
        };
    }

    private static EventSender senderFailingWith(Exception cause) {
        return (ctx, event, raw) -> {
            throw cause;
        };
    }

    private static EventSender mustNotBeCalled() {
        return (ctx, event, raw) -> {
            throw new AssertionError("sender must not be called: the frozen envelope should have been blocked");
        };
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
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(adminUrl,
                TestPostgres.username(), TestPostgres.password());
             java.sql.Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + database);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("探针库创建失败: " + adminUrl, e);
        }
    }
}
