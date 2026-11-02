package com.rag.common.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Observability 配置绑定（{@code application.yml}）：
 * <pre>{@code
 * observability:
 *   slow-threshold: 750ms    # B 侧 duration 格式或纯毫秒
 *   http-timeout: 3s         # 对齐 B 侧 rest.RestConf.Timeout
 *   rpc-timeout: 2s          # 对齐 B 侧 zrpc.RpcServerConf.Timeout
 * }</pre>
 *
 * <p><b>优先级</b>：
 * <ol>
 *   <li>{@link #getSlowThreshold()}（application.yml / Spring properties）</li>
 *   <li>环境变量 {@code SEA_OBSERVABILITY_SLOW_THRESHOLD} / {@code SEA_OBSERVABILITY_SLOW_THRESHOLD_MS}</li>
 *   <li>默认 500ms（{@link ObservabilityCustomizer#DEFAULT_SLOW_THRESHOLD}）</li>
 * </ol>
 *
 * <p>timeout 两项<b>没有 env 兜底</b>，缺省即 {@link Duration#ZERO}（不限时）——
 * 这正是 B 侧 {@code TimeoutFromMillis(ms<=0) → 0} 的语义：
 * <pre>
 *   B 侧 observability.go:57-62
 *     func TimeoutFromMillis(ms int64) time.Duration {
 *         if ms &lt;= 0 { return 0 }
 *         return time.Duration(ms) * time.Millisecond
 *     }
 * </pre>
 *
 * <p><b>默认值的取证</b>：B 侧 {@code service/user/{user,admin}/{api,rpc}/etc/*.yaml}
 * <b>均未配置 {@code Timeout}</b>，实际生效值来自 B 侧 转述的结构体默认值
 * （B 出处：{@code service/common/src/main/java/com/rag/common/spring/ObservabilityProperties.java:40-45}，RAG 仓）：
 * <ul>
 *   <li>{@code rest/config.go:55} {@code Timeout int64 `json:",default=3000"`} → <b>3000ms</b></li>
 *   <li>{@code zrpc/config.go:46} {@code Timeout int64 `json:",default=2000"`} → <b>2000ms</b></li>
 * </ul>
 * 即 B 侧 实际跑 HTTP 3s / gRPC 2s。各模块 yaml 须显式写出同值，不得留空。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L4 idiomatization：去掉 {@code @Component}，改由 auto-config 的
 * {@code @EnableConfigurationProperties} 注册</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p>RAG 侧本类带 {@code @Component}（靠 {@code scanBasePackages} 扫 {@code com.rag.common} 注册）。
 * ⇒ 但本仓的共享层是 <b>starter</b>（D3）：消费方<b>不再扫</b> {@code com.rag.common}
 * ⇒ ⇒ {@code @Component} 在本仓<b>永远扫不到</b> ⇒ 配置根本绑不上。
 * ⇒ ⇒ 改由 {@code ObservabilityAutoConfiguration} 的
 * {@code @EnableConfigurationProperties(ObservabilityProperties.class)} 注册
 * ⇒ ⇒ 消费方<b>零装配代码</b>（D3 的核心收益）。
 * ⇒ ⇒ ⚠️ **必须去掉 {@code @Component}**：留着会与 {@code @EnableConfigurationProperties}
 * 造成**同一前缀被注册两次**（两个 Bean ⇒ 后者覆盖前者 ⇒ 配置值可能取错实例）。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/spring/ObservabilityProperties.java}
 * 整段抄（L2 · D13）＋ <b>装配形态回改</b>（L4 · D3 starter 化）。
 */
@ConfigurationProperties("observability")
public class ObservabilityProperties {

    /**
     * 慢调用阈值；{@code null} 时回退到 env var 读取
     * （{@code SEA_OBSERVABILITY_SLOW_THRESHOLD} / {@code ..._MS}），再回退 500ms。
     */
    private Duration slowThreshold;

    /**
     * HTTP 请求整体超时（{@code observability.http-timeout}）。
     *
     * <p>对应 B 侧版 {@code DisableNativeRestTimeout(&c.RestConf)} 的返回值
     * （B 出处：{@code service/common/src/main/java/com/rag/common/observability/HttpMiddleware.java:59,213}，RAG 仓）：
     * B 侧 先关掉框架自带的 rest 超时中间件（{@code conf.Middlewares.Timeout = false}），
     * 再把 {@code conf.Timeout} 交回观测中间件接管 —— <b>超时只有一个执行者</b>，
     * 不会出现框架与观测层各判一次的情况。
     *
     * <p>{@code null} / 零值 = 不限时，与 B 的 {@code TimeoutFromMillis(0) → 0} 一致。
     */
    private Duration httpTimeout;

    /**
     * gRPC 单次调用超时（{@code observability.rpc-timeout}）。
     *
     * <p>对应 B 侧版 {@code DisableNativeRpcTimeout(&c.RpcServerConf)} 的返回值
     * （{@code conf.Timeout = 0} 后交回 {@code NewUnaryServerInterceptor}）。
     *
     * <p>{@code null} / 零值 = 不限时，与 B 的 {@code TimeoutFromMillis(0) → 0} 一致。
     */
    private Duration rpcTimeout;

    public Duration getSlowThreshold() {
        return slowThreshold;
    }

    public void setSlowThreshold(Duration slowThreshold) {
        this.slowThreshold = slowThreshold;
    }

    public Duration getHttpTimeout() {
        return httpTimeout;
    }

    public void setHttpTimeout(Duration httpTimeout) {
        this.httpTimeout = httpTimeout;
    }

    public Duration getRpcTimeout() {
        return rpcTimeout;
    }

    public void setRpcTimeout(Duration rpcTimeout) {
        this.rpcTimeout = rpcTimeout;
    }

    /**
     * <b>内层预算</b>（= B 侧「gRPC 中间件那一层」的超时）—— <b>全仓唯一口径</b>。
     *
     * <p><b>为什么需要它（B 出处 · 逐层对齐）</b>：
     * <pre>
     *   B 侧 admin-api（HTTP，rest.RestConf.Timeout = 3s）
     *      → gRPC admin-rpc（zrpc.RpcServerConf.Timeout = 2s）   ← **这一层就是「内层」**
     *         → SQL（继承 gRPC 的 deadline ⇒ DeadlineState ⇒ setQueryTimeout）
     * </pre>
     * ⇒ B 侧真正给 SQL 兜底的是<b>内层那个 2s</b>（不是 HTTP 的 3s）
     * ⇒ 而 RAG-V1 的 D2「域内直连」把 DB 调用搬进了 HTTP 边界
     * ⇒ ⇒ <b>内层这一层在 C 侧缺位</b> ⇒ 「PG 不可用 / 请求超时」时两侧响应面不一致
     * （B = {@code 200 + 信封 1015} ↔ C = {@code 503 + 裸文本 "Request Timeout"}）
     * ⇒ ⇒ 本方法把<b>内层预算</b>重新算出来，供共享层两处使用：
     * <ol>
     *   <li>{@link com.rag.common.observability.HttpMiddleware} ⇒ 把 deadline 绑到
     *       {@code DeadlineState}（⇒ {@code DeadlineQueryTimeoutInjector} 给每条 SQL 设 query timeout）；</li>
     *   <li>{@code DeadlineBoundedConnectionTimeout} ⇒ 把连接池的「取连接超时」收到同一预算
     *       （⇒ PG 不可用时不再干等 30s）。</li>
     * </ol>
     *
     * <p><b>算法 = min(http-timeout, rpc-timeout)</b>，与 B 侧 {@code context.WithTimeout} 的
     * <b>min 语义</b>一致（B 的 ctx deadline 3s 与 gRPC deadline 2s 取更早者 ⇒ 2s）。
     * <p>⚠️ <b>null / 零值 = 不限时</b>（与 B 的 {@code TimeoutFromMillis(0) → 0} 一致）
     * ⇒ ⇒ 两项都无限时 ⇒ 返回 {@code null}（= 无内层预算 ⇒ 调用方<b>不绑</b> deadline）。
     *
     * <p>⚠️ <b>为什么不能只取 rpc-timeout</b>：若 {@code rpc-timeout > http-timeout}
     * （例如 5s vs 3s）⇒ B 侧真正生效的是 HTTP 的 3s（ctx 先到）
     * ⇒ ⇒ C 侧也必须取 3s ⇒ 否则会出现「内层比外层还晚」⇒ 响应面反而更不像 B。
     */
    public Duration resolveInnerBudget() {
        Duration http = this.httpTimeout;
        Duration rpc = this.rpcTimeout;
        boolean httpSet = http != null && !http.isZero() && !http.isNegative();
        boolean rpcSet = rpc != null && !rpc.isZero() && !rpc.isNegative();
        if (!httpSet && !rpcSet) {
            return null;
        }
        if (!httpSet) {
            return rpc;
        }
        if (!rpcSet) {
            return http;
        }
        return rpc.compareTo(http) < 0 ? rpc : http;
    }

    // ── B 侧 `Telemetry` 段的 3 个字段：**已移出本类**（2026-11-02 批次 F） ──
    //
    // ⚠️ **为什么移出**：B 的 `Telemetry` 段（Name / Endpoint / Sampler / Batcher）原先在本类
    // 里以 `observability.endpoint` / `observability.protocol` / `observability.sampler-ratio`
    // 三个字段承载，但**只承载、不驱动任何装配** ⇒ 等于「配置面看着齐了、装配面却是空的」。
    // ⇒ 批次 F 装了 {@link GoStyleOtelAgent}（全局 OTel SDK + OTLP exporter + 传播器）⇒
    // ⇒ 这三个字段必须**真正驱动装配** ⇒ ⇒ 归位到
    // {@link GoStyleOtelConfig}（B 侧同名类的 1:1 对应物）。
    //
    // ⚠️ **键名也随之回到 B 侧**：`GoStyleOtelConfig` 读的是
    // `otel.exporter.otlp.endpoint` / `otel.exporter.otlp.protocol` / `otel.traces.sampler`
    // ⇒ ⇒ **L1 配置契约与 B 侧逐字节同构**（不改键名 ⇒ yml 搬运时零改写）。
    //
    // ⚠️ **本类只保留「观测层自己用」的三项**（slow-threshold / http-timeout / rpc-timeout）——
    // 它们是 Spring 绑定的 Duration 类型，不属于 B 的 Telemetry 段。
}
