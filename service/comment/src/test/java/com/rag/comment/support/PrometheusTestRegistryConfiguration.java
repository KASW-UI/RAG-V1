package com.rag.comment.support;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * <p><b>测试上下文里注入「真 Prometheus registry」</b> —— 让 Micrometer 真正吐出
 * <b>Prometheus 文本格式</b>（{@code _total{...}} 那些）。
 *
 * <h2>为什么需要它</h2>
 * <p>comment 域有 5 个指标名是 <b>L1 契约</b>（B 侧逐字照抄，见
 * {@code service/comment/src/main/java/com/rag/comment/metrics/CommentRpcMetrics.java}）：
 * <ul>
 *   <li>{@code comment_request_total{module,action,result}}（计数）</li>
 *   <li>{@code comment_request_seconds_counter{module,action}}（<b>累加秒数</b>的 Counter，
 *       <b>不是</b> B 侧 Java 写的 Timer —— 见该类注释「唯一一处与 B 侧 Java 实现不同的指标名」）</li>
 *   <li>{@code comment_postgres_error_total{module,action,type}}</li>
 *   <li>{@code comment_redis_error_total{module,action,type}}</li>
 *   <li>{@code comment_list_size{module,action}}（Gauge）</li>
 * </ul>
 * ⇒ 这些名字<b>不能写错</b>：告警规则 / 看板 / Grafana 面板都按它们建的
 * ⇒ ⇒ 断言必须打在<b>真实 scrape 文本</b>上（而不是「读一下常量」）
 * ⇒ ⇒ 「名字写错」这种缺陷<b>只有真 registry 才抓得住</b>
 * （比如把下划线写成连字符 ⇒ 该指标<b>根本不会出现在 scrape 文本里</b>）。
 *
 * <h2>⚠️ 本类只提供 registry，<b>不改主代码的 meter 名</b></h2>
 * <p>被测对象是主代码里那串 {@code Counter.builder("comment_request_total")}；
 * registry 类型只决定「用什么文本格式把指标吐出来」。
 *
 * <h3>⚠️ 包名是 {@code io.micrometer.prometheusmetrics.*}（<b>不是</b> {@code io.micrometer.prometheus.*}）</h3>
 * <p>Boot 3.5 / Micrometer 1.15 起，Prometheus registry 的包名已改成
 * {@code io.micrometer.prometheusmetrics}；旧包名在 1.13 已废弃、1.15 已移除
 * ⇒ ⇒ 用旧包名会<b>编译不过</b>。
 *
 * <h3>L4 差异（AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比参照实现（like 域同款）多做了什么</b>：<b>无</b> ——
 *       bean 定义、registry 构造方式、包名<b>逐条与 like 域一致</b>；
 *       只有 javadoc 里列的指标名清单按 comment 域改写（like 域那 4 个指标名
 *       {@code like_api_reject_total} 等<b>不适用于本域</b>）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 本类只决定「指标以什么文本格式被吐出」，
 *       不改变被测代码写入的指标名、标签集合与计数值。</li>
 *   <li><b>B 出处</b>：指标名清单来自 B 侧
 *       {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/metrics/CommentRpcMetrics.java}
 *       （Go 侧原始定义见 {@code service/comment/rpc/internal/metrics/metrics.go:28-47}）；
 *       C 侧落点：{@code service/comment/src/main/java/com/rag/comment/metrics/CommentRpcMetrics.java:198-252}。</li>
 * </ol>
 */
@TestConfiguration(proxyBeanMethods = false)
public class PrometheusTestRegistryConfiguration {

    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }
}
