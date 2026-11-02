package com.rag.follow.metrics;

import com.rag.follow.FollowServiceApplication;
import com.rag.follow.contract.ErrCodes;
import com.rag.follow.contract.GrpcErrorMapper;
import com.rag.follow.domain.service.FollowDomainService;
import com.rag.follow.support.FakeUserRpcServer;
import com.rag.follow.support.NacosStubConfiguration;
import com.rag.follow.support.PrometheusTestRegistryConfiguration;
import com.rag.follow.support.TestPostgres;

import com.rag.proto.follow.ListReq;
import com.rag.proto.follow.RelationReq;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>follow 域的 5 个 rpc 指标（L1 观测契约 · 真 PG 触发 + 真 Prometheus 文本格式断言）</b>。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   B 侧 follow-rpc/.../metrics/FollowRpcMetrics.java 注册 5 个 prometheus 指标：
 *     follow_rpc_request_total{method,result}             Counter
 *     follow_rpc_request_duration_seconds{method}         Histogram(DefBuckets)
 *     follow_rpc_relation_action_total{action,result}      Counter
 *     follow_rpc_db_error_total{action,type}               Counter
 *     follow_rpc_recommendation_size                       Gauge
 * </pre>
 *
 * <h2>🔴 桶边界必须逐个等于 B 侧 {@code prometheus.DefBuckets}</h2>
 * <p>B 侧是 {@code prometheus.NewHistogramVec(..., Buckets: prometheus.DefBuckets)}
 * ⇒ 11 个边界：.005 / .01 / .025 / .05 / .1 / .25 / .5 / 1 / 2.5 / 5 / 10。
 * <p>⚠️ <b>只靠 {@code publishPercentileHistogram()} 是错的</b> ⇒ Micrometer 会用它自己的
 * 68 个 HdrHistogram 边界 ⇒ 与 DefBuckets <b>零重合</b> ⇒ 同一段 PromQL 在两侧给出不同分位数。
 * ⇒ C 侧用 {@code serviceLevelObjectives(...)} 把桶边界钉死 ⇒ 本类断言 {@code le} 集合。
 * <p>⚠️ <b>文本差异</b>：Go 的 {@code expfmt} 把 {@code 1.0} 写成 {@code "1"}、Micrometer 的
 * {@code DoubleFormat} 也把整数值写成 {@code "1"} ⇒ 本类按<b>浮点</b>解析后比较
 * （不看文本形态）⇒ 语义等价即可。
 */
@SpringBootTest(classes = {FollowServiceApplication.class, NacosStubConfiguration.class,
        PrometheusTestRegistryConfiguration.class},
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "grpc.server.port=-1"
        })
@ActiveProfiles("test")
class FollowMetricsContractTest {

    /** B 侧 {@code prometheus.DefBuckets} 的 11 个边界（逐个照抄）。 */
    private static final Double[] GO_DEF_BUCKETS =
            {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0};

    private static final AtomicLong UID_SEQ = new AtomicLong(8_500_000_000_000L);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        registry.add("grpc.client.user-rpc.address", FakeUserRpcServer::address);
    }

    @Autowired
    private FollowDomainService domainService;

    @Autowired
    private FollowRpcMetrics rpcMetrics;

    @Autowired
    private PrometheusMeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================================================================
    // 1. 指标名与标签
    // ==================================================================

    @Test
    @DisplayName("🔴 follow_rpc_request_total{method,result}：method 首字母大写，result 只有 success/fail")
    void requestTotalHasGoMethodAndResultLabels() {
        long me = UID_SEQ.incrementAndGet();
        long target = UID_SEQ.incrementAndGet();

        domainService.follow(RelationReq.newBuilder().setUserId(me).setTargetId(target).build());

        String scrape = scrape();
        assertThat(scrape).contains("follow_rpc_request_total");
        assertThat(scrape).contains("method=\"Follow\"");
        assertThat(scrape).contains("result=\"success\"");
        // ⚠️ method 名必须是 B 侧的字面量（首字母大写），不是 "follow"
        assertThat(scrape).doesNotContain("method=\"follow\"");
    }

    @Test
    @DisplayName("🔴 follow_rpc_relation_action_total{action,result}：action **全小写**，且**只有 4 个写方法**有")
    void relationActionTotalOnlyCoversTheFourWriteMethods() {
        long me = UID_SEQ.incrementAndGet();
        long target = UID_SEQ.incrementAndGet();

        domainService.follow(RelationReq.newBuilder().setUserId(me).setTargetId(target).build());
        domainService.unfollow(RelationReq.newBuilder().setUserId(me).setTargetId(target).build());
        domainService.block(RelationReq.newBuilder().setUserId(me).setTargetId(target).build());
        domainService.unblock(RelationReq.newBuilder().setUserId(me).setTargetId(target).build());
        domainService.getFollowList(ListReq.newBuilder().setUserId(me).build());
        domainService.getBlockList(ListReq.newBuilder().setUserId(me).build());
        domainService.getFollowerList(ListReq.newBuilder().setUserId(me).build());
        domainService.getRecommendations(ListReq.newBuilder().setUserId(me).build());

        String scrape = scrape();
        assertThat(scrape).contains("follow_rpc_relation_action_total");
        assertThat(scrape).contains("action=\"follow\"");
        assertThat(scrape).contains("action=\"unfollow\"");
        assertThat(scrape).contains("action=\"block\"");
        assertThat(scrape).contains("action=\"unblock\"");
        // 🔴 4 个读方法**不**报 relation_action ⇒ 永远不得出现 get_follow_list 这类 action
        // ⚠️ 必须**只扫 relation_action_total 的那些行**：db_error_total 的 action 标签
        //    用的就是 method 名（get_follow_list）⇒ 全局扫会误判。
        List<String> relationActionLines = linesStartingWith(scrape, "follow_rpc_relation_action_total");
        assertThat(relationActionLines)
                .as("读方法不得上报 relation_action_total（B 侧只有 4 个写方法调 ObserveRelation）")
                .noneMatch(line -> line.contains("get_follow_list")
                        || line.contains("get_block_list")
                        || line.contains("get_follower_list")
                        || line.contains("get_recommendations"));
        assertThat(relationActionLines).isNotEmpty();
    }

    // ==================================================================
    // 2. 桶边界
    // ==================================================================

    @Test
    @DisplayName("🔴 follow_rpc_request_duration_seconds 的 le 集合必须逐个等于 B 侧 prometheus.DefBuckets（11 个）")
    void histogramBucketsMatchGoDefBuckets() {
        long me = UID_SEQ.incrementAndGet();
        long target = UID_SEQ.incrementAndGet();

        domainService.follow(RelationReq.newBuilder().setUserId(me).setTargetId(target).build());

        Set<Double> les = bucketBoundaries("follow_rpc_request_duration_seconds_bucket");
        assertThat(les)
                .as("桶边界必须与 B 侧 DefBuckets 逐个相同（否则同一段 PromQL 在两侧给出不同分位数）")
                .containsExactlyInAnyOrder(GO_DEF_BUCKETS);
        assertThat(les).hasSize(GO_DEF_BUCKETS.length);
        // ⚠️ +Inf 是 Prometheus 格式自带的最后一个桶（两侧都有）
        assertThat(scrape()).contains("follow_rpc_request_duration_seconds_bucket{");
    }

    @Test
    @DisplayName("🔴 follow_api_request_duration_seconds 的 le 集合同样逐个等于 DefBuckets（api 侧那份）")
    void apiHistogramBucketsAlsoMatchGoDefBuckets() {
        // api 侧那份 Timer 只在 HTTP 管线里被观测 ⇒ 本类直接读注册表里的 meter（先触发一次 api 埋点）
        // ⇒ 用 FollowApiMetrics 直接观测一次即可（与真 HTTP 走的是同一段代码）。
        com.rag.follow.metrics.FollowApiMetrics apiMetrics =
                new com.rag.follow.metrics.FollowApiMetrics(meterRegistry);
        apiMetrics.observeRequest("/follow/v1/follow/list", false, 1_000L);

        Set<Double> les = bucketBoundaries("follow_api_request_duration_seconds_bucket");
        assertThat(les).containsExactlyInAnyOrder(GO_DEF_BUCKETS);
    }

    // ==================================================================
    // 3. db_error 指标
    // ==================================================================

    @Test
    @DisplayName("🔴 follow_rpc_db_error_total{action,type}：真 DB 失败 ⇒ 5007 + 指标被上报")
    void dbErrorCounterIsReportedOnRealDatabaseFailure() {
        long me = UID_SEQ.incrementAndGet();

        // 🔴 真失败注入：把表改名 ⇒ mapper 的 SELECT 真的报「relation does not exist」
        //   ⇒ 领域层把 RuntimeException 翻成 5007（ERROR_DB_SELECT）并上报 db_error_total
        jdbcTemplate.execute("ALTER TABLE follow_relation RENAME TO follow_relation_tmp");
        try {
            assertThatThrownBy(() -> domainService.getFollowList(ListReq.newBuilder().setUserId(me).build()))
                    .satisfies(t -> assertThat(GrpcErrorMapper.bizCodeFromError(t))
                            .isEqualTo(ErrCodes.ERROR_DB_SELECT));
        } finally {
            jdbcTemplate.execute("ALTER TABLE follow_relation_tmp RENAME TO follow_relation");
        }

        String scrape = scrape();
        assertThat(scrape).contains("follow_rpc_db_error_total");
        assertThat(scrape).contains("action=\"get_follow_list\"");
        assertThat(scrape).contains("type=\"list_follow_targets\"");
    }

    // ==================================================================
    // 4. recommendation_size gauge
    // ==================================================================

    @Test
    @DisplayName("🔴 follow_rpc_recommendation_size 是 Gauge（记最近一次的条数，不是累计）")
    void recommendationSizeIsAGauge() {
        long me = UID_SEQ.incrementAndGet();
        long friend = UID_SEQ.incrementAndGet();
        long stranger = UID_SEQ.incrementAndGet();

        // 直关为空 ⇒ 上报 0（**不是**「没上报」⇒ 图上看得出来）
        domainService.getRecommendations(ListReq.newBuilder().setUserId(me).build());
        assertThat(rpcMetrics.currentRecommendationSize()).isZero();
        assertThat(scrape()).contains("follow_rpc_recommendation_size");

        insertFollow(me, friend);
        insertFollow(friend, stranger);
        domainService.getRecommendations(ListReq.newBuilder().setUserId(me).build());
        assertThat(rpcMetrics.currentRecommendationSize())
                .as("正常推荐 ⇒ gauge 记最近一次的条数")
                .isEqualTo(1);
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private String scrape() {
        // ⚠️ 上下文里注入的是**真 Prometheus registry**（见 PrometheusTestRegistryConfiguration）
        //   ⇒ 拿到的就是 Prometheus 文本格式（_bucket{le="..."} / _count / _sum）。
        return meterRegistry.scrape();
    }

    /** 从 Prometheus 文本里抽出某个直方图的 {@code le="..."} 边界集合（按浮点解析）。 */
    private Set<Double> bucketBoundaries(String metricPrefix) {
        Pattern p = Pattern.compile("(?m)^" + Pattern.quote(metricPrefix)
                + "\\{[^}]*\\ble=\"([^\"]+)\"[^}]*\\}\\s+.*$");
        Set<Double> les = new LinkedHashSet<>();
        Matcher matcher = p.matcher(scrape());
        while (matcher.find()) {
            String raw = matcher.group(1);
            if ("+Inf".equals(raw)) {
                continue;
            }
            les.add(Double.parseDouble(raw));
        }
        return les;
    }

    private void insertFollow(long userId, long targetId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO follow_relation (user_id, target_id, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?)",
                userId, targetId, Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));
    }


    /** 只取 Prometheus 文本里以某个指标名开头的那些行（避免跨指标误判标签）。 */
    private static List<String> linesStartingWith(String scrape, String metricName) {
        List<String> out = new java.util.ArrayList<>();
        for (String line : scrape.split("\n")) {
            if (line.startsWith(metricName)) {
                out.add(line);
            }
        }
        return out;
    }
}
