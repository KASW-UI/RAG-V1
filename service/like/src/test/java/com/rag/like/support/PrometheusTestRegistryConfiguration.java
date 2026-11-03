package com.rag.like.support;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * <p><b>测试上下文里注入「真 Prometheus registry」</b> —— 让 Micrometer 真正吐出
 * <b>Prometheus 文本格式</b>（{@code _total{...}} 那些）。
 *
 * <h2>为什么需要它</h2>
 * <p>like 域有 4 个指标名是 <b>L1 契约</b>（B 侧逐字照抄）：
 * <ul>
 *   <li>{@code like_api_reject_total{route,reason}}（api 层）</li>
 *   <li>{@code like_rpc_action_total{target_type,action,result}}（rpc 层）</li>
 *   <li>{@code like_rpc_query_cache_total{target_type,result}}（rpc 层）</li>
 *   <li>{@code like_mq_consume_total{action,result}}（mq 层）</li>
 * </ul>
 * ⇒ 映射表 §7.3 <b>GAP-8</b> 点名的缺陷就是「C 侧 javadoc 写连字符版
 * {@code like-rpc_action_total}」⇒ 连字符**不符合 Prometheus 指标名正则**
 * ⇒ 该指标**根本不会出现在 scrape 文本里**。
 * <p>⇒ ⇒ 断言必须打在<b>真实 scrape 文本</b>上（而不是「读一下常量」）
 * ⇒ ⇒ 「名字写错」这种缺陷**只有真 registry 才抓得住**。
 *
 * <h2>⚠️ 本类只提供 registry，<b>不改主代码的 meter 名</b></h2>
 * <p>被测对象是主代码里那串 {@code Counter.builder("like_rpc_action_total")}；
 * registry 类型只决定「用什么文本格式把指标吐出来」。
 */
@TestConfiguration(proxyBeanMethods = false)
public class PrometheusTestRegistryConfiguration {

    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }
}
