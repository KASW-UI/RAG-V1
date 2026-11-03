package com.rag.article.assembly;

import com.rag.article.metrics.ArticleRpcMetrics;
import com.rag.article.support.AssemblyTestBase;
import io.micrometer.core.instrument.Meter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>「指标真注册」门禁</b> —— 真 {@link PrometheusMeterRegistry} 抓取，
 * 断言 6 个指标的<b>完整名</b>与<b>分桶边界</b>。
 *
 * <h2>🔴 为什么这条判据必须落在<b>真 Prometheus registry</b> 上</h2>
 * <p>AGENTS.md §3.3：<b>「Bean 存在」≠「机制生效」</b>；
 * §3.3.1 ③：判断某个 Java 框架能力是否生效，<b>必须有运行证据</b>。
 * <p>而「指标的完整名」这件事<b>恰恰是「注解/常量写对了也可能是错的」</b>：
 * <ul>
 *   <li>Micrometer <b>不提供前缀机制</b> ⇒ B 侧 Go 的 {@code Namespace = "article_service"}
 *       必须<b>手工压进每个 meter name</b>；漏一个 ⇒ 该指标的<b>全名</b>就与 B 侧不同；</li>
 *   <li>Micrometer 会按 {@code Meter.Type} <b>自动补后缀</b>
 *       （Counter ⇒ {@code _total}；Timer ⇒ {@code _seconds}）
 *       ⇒ meter name 里<b>不能</b>自己写这个后缀（写了会被 Prometheus client 拒绝）；</li>
 *   <li>后果：<b>任何差异都会让既有 dashboard / 告警规则静默失效</b> ——
 *       Prometheus 抓的是<b>全名</b>，名字对不上 ⇒ 抓不到数据、<b>不会报错</b>。</li>
 * </ul>
 * ⇒ ⇒ 必须<b>真抓一次</b>，看 scrape 文本里的名字，而不是读源码里的字符串常量。
 *
 * <h2>B 出处（逐条 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ 两侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th><th>C 侧</th></tr>
 *   <tr><td>命名空间 {@code article_service}（指标名前缀）</td>
 *       <td>{@code metrics/ArticleRpcMetrics.java:59}
 *           （Go {@code prometheus.Namespace = "article_service"}）</td>
 *       <td>{@code metrics/ArticleRpcMetrics.java:185}</td></tr>
 *   <tr><td>6 个指标的完整名</td>
 *       <td>{@code metrics/ArticleRpcMetrics.java:87-183}（B 侧 6 个 {@code prometheus.NewCounter} / {@code NewTimer}）</td>
 *       <td>{@code metrics/ArticleRpcMetrics.java:212-309}</td></tr>
 *   <tr><td>MinIO Timer 的分桶边界（8 个）</td>
 *       <td>{@code metrics/ArticleRpcMetrics.java:62-63}
 *           （Go {@code Buckets: []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1}}）</td>
 *       <td>{@code metrics/ArticleRpcMetrics.java:188-189}</td></tr>
 *   <tr><td>{@code article_status_total} <b>只有 reviewing 有调用点</b></td>
 *       <td>Go {@code create_article_logic.go:115}（B 侧 javadoc 转录）</td>
 *       <td>{@code metrics/ArticleRpcMetrics.java:221-232}（类注释明写「不许补全」）</td></tr>
 * </table>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把某个 meter name 的 {@code article_service_} 前缀去掉
 *       ⇒ 用例 ① <b>立刻红</b>（全名与 B 侧分叉 ⇒ dashboard 静默失效）；</li>
 *   <li>把 {@code MINIO_BUCKETS} 改成一个值
 *       ⇒ 用例 ② <b>立刻红</b>（分桶边界逐个比对）；</li>
 *   <li>给 {@code observeMinio} 加上 {@code publishPercentileHistogram()}
 *       ⇒ 用例 ② <b>立刻红</b>（桶边界会从 9 个涨到 77 个 ⇒ {@code histogram_quantile} 的结果变了）；</li>
 *   <li>把 {@code articleStatus} 改成「三个 status 都记」
 *       ⇒ 用例 ③ <b>立刻红</b>（{@code reviewing} 之外多出两支 ⇒ 告警规则行为变了）。</li>
 * </ul>
 *
 * <h2>⚠️ 「恰好 6 个」的口径</h2>
 * <p>生产的 {@link PrometheusMeterRegistry} 里还会有 JVM / HTTP / Kafka client 等其它指标
 * ⇒ ⇒ 「恰好 6 个」指的是<b>本域的 6 个</b>：
 * <ul>
 *   <li>用例 ① 断言<b>生产 registry 的 scrape 文本</b>里 {@code article_service_} 前缀的名字<b>恰好 6 个</b>；</li>
 *   <li>用例 ③ 用一个<b>全新的空 registry</b> 装载生产的 {@link ArticleRpcMetrics}
 *       ⇒ 那里面的 meter <b>必然恰好 6 个</b>（不会被别的组件污染）。</li>
 * </ul>
 *
 * <h2>🔴 为什么必须写 {@link AutoConfigureObservability}(metrics = true)</h2>
 * <p>Boot 3.5.11 的 {@code spring-boot-test-autoconfigure} 里
 * {@code ObservabilityContextCustomizerFactory} 会在<b>测试类没有</b>
 * {@link AutoConfigureObservability} 注解时，往环境里注入
 * {@code management.defaults.metrics.export.enabled=false}
 * （PropertySource 名 = {@code test}）⇒ 容器里<b>根本不会</b>有
 * {@link PrometheusMeterRegistry}（{@code PrometheusMetricsExportAutoConfiguration}
 * 的 {@code @ConditionalOnEnabledMetricsExport} 不匹配）。
 * <p>⚠️ 这是<b>测试框架的行为</b>，<b>生产运行时没有</b>这层注入
 * ⇒ ⇒ 本测试显式把 metrics 打开，让上下文与生产同形态。
 */
@AutoConfigureObservability(metrics = true, tracing = false)
class ArticleAssemblyMetricsTest extends AssemblyTestBase {

    /** B 侧的 6 个 Prometheus 完整名（Go 侧 prometheus 客户端的输出名）。 */
    private static final Set<String> EXPECTED_NAMES = Set.of(
            "article_service_article_total",
            "article_service_article_status_total",
            "article_service_file_upload_total",
            "article_service_minio_request_errors_total",
            "article_service_kafka_push_errors_total",
            "article_service_minio_request_duration_seconds");

    /**
     * ⚠️ **Micrometer 从 {@code Timer} 自动派生的 {@code _max} gauge**（<b>不是</b>第 7 个指标定义）。
     *
     * <p>Micrometer 的 {@code Timer} 在构造时就把 {@code maximum()} 注册成一个 {@code TimeGauge}
     * ⇒ Prometheus 的 {@code scrape()} 会多吐一行 {@code <name>_seconds_max}。
     * <br>⇒ ⚠️ <b>这不是漂移</b>：B 侧用的是<b>同一套 Micrometer</b>（同样的 Timer ⇒ 同样的派生 gauge）
     * ⇒ ⇒ 判据必须把它<b>显式列出来</b>，否则「恰好 6 个」这条断言**恒红**。
     * <br>⇒ 真正的判据是「<b>本域自己定义的指标恰好 6 个</b>，且 scrape 里除这 6 个之外
     * <b>只多</b> Micrometer 自己派生的那一个」。
     */
    private static final Set<String> DERIVED_NAMES = Set.of(
            "article_service_minio_request_duration_seconds_max");

    /** B 侧的 MinIO 分桶边界（秒）＋ Prometheus 固有的 {@code +Inf}。 */
    private static final List<Double> EXPECTED_BUCKETS = List.of(
            0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ArticleRpcMetrics metrics;

    @Autowired
    private org.springframework.core.env.Environment environment;

    /**
     * 取容器里的<b>真</b> {@link PrometheusMeterRegistry}。
     *
     * <p>🔴 取不到就<b>立刻红</b> —— 因为「取不到」本身就说明
     * {@code PrometheusMetricsExportAutoConfiguration} 没生效 ⇒
     * {@code /actuator/prometheus} 抓不到本域的<b>任何</b>指标（静默失效，§3.3.1 ②）。
     */
    private PrometheusMeterRegistry prometheusRegistry() {
        assertTrue(context.containsBean("prometheusMeterRegistry"),
                "🔴 容器里必须有真 PrometheusMeterRegistry（否则 /actuator/prometheus 抓不到任何指标）"
                        + "；management.defaults.metrics.export.enabled = "
                        + environment.getProperty("management.defaults.metrics.export.enabled")
                        + "；management.prometheus.metrics.export.enabled = "
                        + environment.getProperty("management.prometheus.metrics.export.enabled")
                        + "；本类必须带 @AutoConfigureObservability(metrics = true)");
        return context.getBean(PrometheusMeterRegistry.class);
    }

    // ==================================================================
    // ① 生产 registry 的 scrape 文本：6 个完整名 + 前缀
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Prometheus registry：抓出的 6 个指标完整名全部带 article_service_ 前缀，"
            + "且本域指标恰好 6 个（裸名一个都不许有）")
    void allSixMetricsAreRegisteredWithTheArticleServicePrefix() {
        PrometheusMeterRegistry registry = prometheusRegistry();

        // ⚠️ 触发生产的 ArticleRpcMetrics（**生产 bean**，不是手工 new 的）
        metrics.article("create");
        metrics.articleStatus("reviewing");
        metrics.fileUpload("image");
        metrics.observeMinio("get", 12_000_000L);
        metrics.minioError("get");
        metrics.kafkaPushError("article_sync_event");

        String scrape = registry.scrape();
        Set<String> names = metricNames(scrape);

        Set<String> articleNames = new TreeSet<>();
        for (String name : names) {
            if (name.startsWith("article_service_")) {
                articleNames.add(name);
            }
        }
        // 🔴 本域**自己定义**的指标恰好 6 个，且完整名逐字与 B 侧一致
        Set<String> missing = new TreeSet<>(EXPECTED_NAMES);
        missing.removeAll(articleNames);
        assertTrue(missing.isEmpty(),
                "🔴 本域的指标完整名必须与 B 侧逐字一致；缺 = " + missing
                        + "；实际 scrape 里的 = " + articleNames);
        // ⚠️ 除这 6 个之外，**只多** Micrometer 从 Timer 自动派生的那一个 `_max`（见 DERIVED_NAMES 的说明）
        Set<String> extra = new TreeSet<>(articleNames);
        extra.removeAll(EXPECTED_NAMES);
        assertEquals(new TreeSet<>(DERIVED_NAMES), extra,
                "🔴 本域自己定义的指标必须恰好 6 个（scrape 里多出来的只能是 Micrometer 从 Timer "
                        + "派生的 _max gauge）；多出来的 = " + extra);

        // ⚠️ 裸名一个都不许有（Micrometer 没有前缀机制 ⇒ 前缀必须手工压进 meter name）
        for (String bare : List.of("article_total", "article_status_total", "file_upload_total",
                "minio_request_errors_total", "kafka_push_errors_total",
                "minio_request_duration_seconds")) {
            assertFalse(names.contains(bare),
                    "🔴 裸名 " + bare + " 不许出现（必须带 article_service_ 前缀）");
        }
    }

    // ==================================================================
    // ② MinIO Timer 的分桶边界（逐个相等）
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Prometheus registry：minio 耗时的分桶边界逐个相等"
            + "（B 侧 8 个 + Prometheus 固有的 +Inf = 9 个）")
    void minioHistogramBucketBoundariesMatchGoExactly() {
        PrometheusMeterRegistry registry = prometheusRegistry();
        metrics.observeMinio("get", 12_000_000L);

        String scrape = registry.scrape();
        List<Double> buckets = bucketBoundaries(scrape,
                "article_service_minio_request_duration_seconds_bucket", "operation=\"get\"");

        List<Double> expected = new ArrayList<>(EXPECTED_BUCKETS);
        assertEquals(expected, buckets,
                "🔴 分桶边界必须与 B 侧逐个相等"
                        + "（Go: {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1} 秒）");
        assertEquals(EXPECTED_BUCKETS.size(), buckets.size(),
                "桶数应为 8 个（B 侧的 8 个边界）+ 1 个 Prometheus 固有的 +Inf（helper 已单独断言）");
    }

    // ==================================================================
    // ③ 本域的 6 个 meter（空 registry ⇒ 不会被别的组件污染）
    // ==================================================================

    @Test
    @DisplayName("🔴 真 Prometheus registry（空 registry）：本域恰好 6 个 meter；"
            + "且 article_status 只有 reviewing 这一支（不许补全成 3 个 status）")
    void articleStatusHasOnlyTheReviewingBranch() {
        PrometheusMeterRegistry fresh = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ArticleRpcMetrics isolated = new ArticleRpcMetrics(fresh);

        isolated.article("create");
        isolated.articleStatus("reviewing");
        isolated.fileUpload("image");
        isolated.observeMinio("get", 12_000_000L);
        isolated.minioError("get");
        isolated.kafkaPushError("article_sync_event");

        Set<String> meterNames = new LinkedHashSet<>();
        for (Meter meter : fresh.getMeters()) {
            meterNames.add(meter.getId().getName());
        }
        assertEquals(6, meterNames.size(),
                "🔴 本域必须恰好 6 个 meter；实际 = " + meterNames);
        assertTrue(meterNames.contains("article_service_article_status"),
                "article_status 的 meter name 必须带前缀");

        // ⚠️ article_status 只允许 reviewing 这一支
        //   （B 侧 Go 只有 create_article_logic.go:115 一处 +1；published/rejected 没有调用点）
        long statusSeries = fresh.scrape().lines()
                .filter(l -> l.startsWith("article_service_article_status_total{"))
                .count();
        assertEquals(1L, statusSeries,
                "🔴 article_status_total 只允许存在 reviewing 这一支（B 侧只有 reviewing 有调用点）");
        assertTrue(fresh.scrape().contains("article_service_article_status_total{status=\"reviewing\"}"),
                "必须存在 status=\"reviewing\" 这一支");
        assertFalse(fresh.scrape().contains("status=\"published\"")
                        || fresh.scrape().contains("status=\"rejected\""),
                "🔴 不许补全 published / rejected 两支（那是 B 侧不存在的埋点）");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 从 scrape 文本里取出全部指标名（{@code # TYPE <name> <type>} 行）。 */
    private static Set<String> metricNames(String scrape) {
        Set<String> names = new TreeSet<>();
        for (String line : scrape.split("\n")) {
            if (line.startsWith("# TYPE ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    names.add(parts[2]);
                }
            }
        }
        return names;
    }

    /**
     * 取出某个 histogram 在某个标签值下的 {@code le} 边界列表（按 Prometheus 渲染顺序）。
     *
     * @param scrape   scrape 文本
     * @param series   series 名（如 {@code xxx_seconds_bucket}）
     * @param tagValue 标签判据（如 {@code operation="get"}）
     * @return 边界列表（{@code +Inf} 不含在内，另行断言）
     */
    private static List<Double> bucketBoundaries(String scrape, String series, String tagValue) {
        Pattern pattern = Pattern.compile("^" + Pattern.quote(series)
                + "\\{[^}]*le=\"([^\"]+)\"[^}]*\\}");
        List<Double> out = new ArrayList<>();
        boolean sawInf = false;
        for (String line : scrape.split("\n")) {
            if (!line.startsWith(series + "{")) {
                continue;
            }
            if (!line.contains(tagValue)) {
                continue;
            }
            Matcher matcher = pattern.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String le = matcher.group(1);
            if ("+Inf".equals(le)) {
                sawInf = true;
                continue;
            }
            out.add(Double.parseDouble(le));
        }
        assertTrue(sawInf, "Prometheus 必须额外渲染一个 +Inf 桶");
        return out;
    }
}
