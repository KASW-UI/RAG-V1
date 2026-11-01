package com.rag.common.observability;

import org.springframework.core.env.Environment;

import java.util.Map;
import java.util.Set;

/**
 * B 侧「Telemetry」配置段在 RAG-V1 侧的<b>投影</b> —— 对应 B 侧 各服务的
 * {@code etc/*.yaml} 里的 {@code Telemetry: { Name, Endpoint, Sampler, Batcher }}。
 *
 * <h2>🔴 为什么 RAG-V1 侧要自己读，而不用 Boot 的自动配置</h2>
 * <p>B 侧那一段的<b>唯一消费者</b>是 {@code service/common/src/main/java/com/rag/common/observability/GoStyleOtelConfig.java:10}（RAG 仓）的
 * {@code startAgent(c Config)}（{@code :105-135}）——
 * 它只读四个字段：{@code Name} / {@code Endpoint} / {@code Sampler} / {@code Batcher}。
 * ⇒ <b>语义面极窄</b>，不需要 Boot 那套「自动配置 + 属性绑定 + 条件化」的完整机制。
 * ⇒ 本类<b>显式</b>把这四个字段读出来（每个键都标注它对应 B 的哪个字段），
 * 装配动作见 {@link GoStyleOtelAgent}。
 *
 * <h2>⚠️ 键名沿用 B 侧（RAG）的 {@code otel.*} ⇒ 配置契约零漂移</h2>
 * <p>本类的键名<b>逐条与 B 侧一致</b>（{@code otel.service.name} /
 * {@code otel.resource.attributes.service.name} / {@code otel.exporter.otlp.endpoint} /
 * {@code otel.exporter.otlp.protocol} / {@code otel.traces.sampler} /
 * {@code otel.traces.sampler.arg}）⇒ <b>L1 配置契约逐字节同构</b>。
 * <p>⚠️ <b>为什么不另起 {@code observability.*} 命名空间</b>：
 * 那样会让「同一份 B 侧 Telemetry 配置」在两侧落到<b>不同的键名</b>上
 * ⇒ 配置契约漂移 ⇒ yml 搬运时必然要改键名 ⇒ 违背 §3.1「L1 以 B 侧 为准」。
 *
 * <h2>⚠️ B 侧「两套拼法并存」的坑必须一起搬</h2>
 * <table border="1">
 *   <caption>api 模块 vs rpc 模块的 yaml 差异（B 侧实测）</caption>
 *   <tr><th>语义</th><th>api 模块</th><th>rpc 模块</th></tr>
 *   <tr><td>服务名</td>
 *       <td>{@code otel.resource.attributes.service.name}</td>
 *       <td>{@code otel.service.name}</td></tr>
 *   <tr><td>导出端点</td>
 *       <td>{@code otel.exporter.otlp.endpoint}（<b>带</b> {@code http://}）</td>
 *       <td>{@code otel.exporter.otlp.endpoint}（<b>不带</b> scheme）</td></tr>
 *   <tr><td>协议</td><td>{@code otel.exporter.otlp.protocol: grpc}</td><td>不写（默认 grpc）</td></tr>
 *   <tr><td>采样</td>
 *       <td>{@code otel.traces.sampler: always_on}（部分模块写 {@code parentbased_always_on}）</td>
 *       <td>{@code otel.traces.sampler: parentbased_always_on}</td></tr>
 * </table>
 * ⇒ ⇒ 两套拼法<b>都要认</b>，否则一半模块装不上。
 *
 * <h2>🔴 端点<b>没有 scheme</b> 时的处理</h2>
 * <p>B 的 {@code otlptracegrpc.WithEndpoint(c.Endpoint) + WithInsecure()}
 * ⇒ 端点是<b>裸 host:port</b>，传输是<b>明文</b>。
 * 而 Java 的 {@code OtlpGrpcSpanExporter} 要求端点带 scheme（{@code http://} = 明文 h2c，
 * {@code https://} = TLS）。
 * ⇒ ⇒ <b>裸 host:port 一律补成 {@code http://}</b> ⇒ 与 B 的 {@code WithInsecure()} 同义。
 *
 * <h2>抄写基准</h2>
 * <p>RAG {@code service/common/src/main/java/com/rag/common/observability/GoStyleOtelConfig.java}
 * 整段抄（L2 业务语义层以 RAG 为基准整段抄 · D13）
 * ⇒ <b>仅一处 L4 改动</b>：本仓把它从「B 侧独占的配置载体」升格为
 * <b>C 侧唯一的 Telemetry 配置载体</b>（原 {@code ObservabilityProperties} 的
 * {@code endpoint}/{@code protocol}/{@code samplerRatio} 三字段已<b>并入本类</b>，
 * 因为它们必须<b>真正驱动装配</b>，而不是「只承载配置」）。
 *
 * @see GoStyleOtelAgent
 */
public final class GoStyleOtelConfig {

    /** B 侧 {@code Telemetry.Name}；缺省时回落到 {@code spring.application.name}。 */
    private final String serviceName;
    /** B 侧 {@code Telemetry.Endpoint}；<b>null</b> ⇒ 不装 exporter（照 B 的 {@code len(c.Endpoint) > 0}）。 */
    private final String endpoint;
    /** B 侧 {@code Telemetry.Batcher}；本仓只用 {@code otlpgrpc}。 */
    private final String protocol;
    /** B 侧 {@code Telemetry.Sampler}（0.0 ~ 1.0 的比例）。 */
    private final double samplerRatio;

    public GoStyleOtelConfig(String serviceName, String endpoint, String protocol, double samplerRatio) {
        this.serviceName = serviceName;
        this.endpoint = endpoint;
        this.protocol = protocol;
        this.samplerRatio = samplerRatio;
    }

    public String serviceName() {
        return serviceName;
    }

    /** 已归一（补过 scheme）的导出端点；{@code null} ⇒ 不装 exporter。 */
    public String endpoint() {
        return endpoint;
    }

    public String protocol() {
        return protocol;
    }

    public double samplerRatio() {
        return samplerRatio;
    }

    // ── 键名（B 侧逐条一致 · L1 配置契约） ────────────────────────────────

    private static final String KEY_SERVICE_NAME = "otel.service.name";
    private static final String KEY_RESOURCE_SERVICE_NAME = "otel.resource.attributes.service.name";
    private static final String KEY_ENDPOINT = "otel.exporter.otlp.endpoint";
    private static final String KEY_PROTOCOL = "otel.exporter.otlp.protocol";
    private static final String KEY_SAMPLER = "otel.traces.sampler";
    private static final String KEY_SAMPLER_ARG = "otel.traces.sampler.arg";

    /**
     * 从 Spring {@link Environment} 读出 B 的 Telemetry 四字段。
     *
     * <p>⚠️ <b>为什么必须直接读 {@link Environment} 而不是注入配置 Bean</b>：
     * 装配点是 {@link GoStyleOtelAutoConfiguration} 里的
     * {@code BeanFactoryPostProcessor} ⇒ 它跑在<b>所有单例创建之前</b>
     * ⇒ ⇒ 此时 {@code @ConfigurationProperties} 绑定的 Bean <b>还不存在</b>
     * ⇒ ⇒ 只能直接读 {@code Environment}。
     *
     * @param env 运行环境（Spring Boot 启动早期即可拿到）
     */
    public static GoStyleOtelConfig from(Environment env) {
        // --- 服务名：两套拼法都认（见类注释的表）---
        String serviceName = firstNonBlank(
                env.getProperty(KEY_SERVICE_NAME),
                env.getProperty(KEY_RESOURCE_SERVICE_NAME),
                env.getProperty("spring.application.name"));

        // --- 端点：scheme 缺失时补 http://（对齐 B 的 WithInsecure）---
        String endpoint = normalizeEndpoint(env.getProperty(KEY_ENDPOINT));

        // --- 协议：B 的 Batcher；本仓只用 otlpgrpc（缺省 = grpc，与 B 侧同款） ---
        String protocol = firstNonBlank(env.getProperty(KEY_PROTOCOL));
        if (protocol == null) {
            protocol = IMPLEMENTED_BATCHER;
        }

        // --- 采样：B 的 Sampler 是一个比例；Boot 侧写的是「采样器名字」⇒ 映射成比例 ---
        String samplerName = firstNonBlank(env.getProperty(KEY_SAMPLER));
        Double samplerArg = env.getProperty(KEY_SAMPLER_ARG, Double.class);
        double ratio = samplerName == null
                ? 1.0d
                : toRatio(samplerName, samplerArg);

        return new GoStyleOtelConfig(serviceName, endpoint, protocol, ratio);
    }

    /**
     * B 的 {@code Batcher} <b>合法集合</b> ——
     * {@code core/trace/config.go:11}
     * {@code Batcher string `json:",default=otlpgrpc,options=zipkin|otlpgrpc|otlphttp|file"`}。
     *
     * <p>⚠️ B 侧的 yaml 解析器（框架的 config loader）会按 {@code options} tag <b>先校验</b>
     * ⇒ 写错值在<b>启动读配置</b>时就炸 ⇒ 根本走不到 {@code createExporter}。
     * ⇒ 但 Java 侧没有这一层校验 ⇒ <b>必须自己补</b>（否则非法值会被静默当成 grpc）。
     */
    private static final Set<String> GO_BATCHERS =
            Set.of("zipkin", "otlpgrpc", "otlphttp", "file");

    /**
     * 🔴 <b>两侧的命名空间不一样</b>，必须显式映射：
     *
     * <table border="1">
     *   <caption>B 的 Batcher ↔ 本仓的 otel.exporter.otlp.protocol</caption>
     *   <tr><th>B 侧（{@code Telemetry.Batcher}）</th><th>本仓（{@code otel.exporter.otlp.protocol}）</th>
     *       <th>本仓是否实现</th></tr>
     *   <tr><td>{@code otlpgrpc}</td><td>{@code grpc}</td><td>✅ <b>唯一实现</b>（B 侧 各服务 yaml 也都是它）</td></tr>
     *   <tr><td>{@code otlphttp}</td><td>—</td><td>❌ 未实现（<b>能力缺口</b>，不是静默降级）</td></tr>
     *   <tr><td>{@code zipkin}</td><td>—</td><td>❌ 未实现（同上）</td></tr>
     *   <tr><td>{@code file}</td><td>—</td><td>❌ 未实现（同上）</td></tr>
     * </table>
     *
     * <p>⚠️ 注意 B 侧的 yaml 写的是 {@code Batcher: otlpgrpc}，
     * 而 yml 写的是 {@code otel.exporter.otlp.protocol: grpc}
     * ⇒ <b>字面量就差一个 {@code otlp} 前缀</b> ⇒ 不显式映射就会把「合法值」判成「非法值」。
     */
    private static final Map<String, String> PROTOCOL_TO_GO_BATCHER =
            Map.of("grpc", "otlpgrpc");

    /** 本仓<b>只实现了</b> OTLP/gRPC（对齐 B 侧 各服务 yaml 的 {@code Batcher: otlpgrpc}）。 */
    public static final String IMPLEMENTED_BATCHER = "grpc";

    /**
     * 🔴 校验 batcher —— 对齐 B 的两条路径：
     *
     * <ul>
     *   <li><b>端点为空 ⇒ 根本不看 batcher</b>：B 的 {@code core/trace/agent.go:115-124}
     *       只在 {@code len(c.Endpoint) > 0} 时才调 {@code createExporter(c)}
     *       ⇒ {@code Batcher} 写错也<b>不报错</b>（只是不装 exporter）⇒ 本仓同款。</li>
     *   <li><b>端点非空 + 不认识的协议 ⇒ 整个 agent 装不上</b>：B 的 {@code createExporter} 的
     *       {@code default} 分支返回 {@code fmt.Errorf("unknown exporter: %s", c.Batcher)}
     *       ⇒ {@code startAgent} 返错 ⇒ {@code StartAgent} 只 {@code logx.Error} ⇒
     *       <b>继续启动</b>（全局 trace 不生效）。</li>
     * </ul>
     *
     * @throws IllegalStateException 协议不认识时
     */
    void validateBatcher() {
        if (endpoint == null) {
            // B 侧: createExporter 只在 len(c.Endpoint) > 0 时被调用 ⇒ 端点为空不看 batcher
            return;
        }
        String effective = protocol == null ? IMPLEMENTED_BATCHER : protocol;
        if (!PROTOCOL_TO_GO_BATCHER.containsKey(effective)) {
            throw new IllegalStateException(
                    "unknown exporter: " + effective
                            + "（本仓只实现了 otel.exporter.otlp.protocol=" + IMPLEMENTED_BATCHER
                            + "（= B 的 Telemetry.Batcher=" + PROTOCOL_TO_GO_BATCHER.get(IMPLEMENTED_BATCHER)
                            + "）；B 的合法 Batcher 集合 = " + GO_BATCHERS
                            + "，其余三种本仓未实现）");
        }
    }

    /**
     * B 的 {@code Telemetry.Sampler} 是<b>比例</b>（{@code core/trace/config.go:9}
     * {@code Sampler float64 `json:",default=1.0"`}），
     * 而 yml 里写的是 Boot 风格的<b>采样器名字</b> ⇒ 这里做一次映射。
     *
     * <p>⚠️ <b>无论哪种名字，最终都要包在 {@code ParentBased} 里</b> ——
     * B 的 {@code core/trace/agent.go:110} 是
     * {@code sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.TraceIDRatioBased(c.Sampler)))}
     * ⇒ <b>永远</b>是 ParentBased ⇒ 「入向 parent 不采样 ⇒ 子 span 也不采样」。
     */
    static double toRatio(String samplerName, Double samplerArg) {
        String name = samplerName == null ? "" : samplerName.trim().toLowerCase();
        switch (name) {
            case "always_on":
            case "parentbased_always_on":
                return 1.0d;
            case "always_off":
            case "parentbased_always_off":
                return 0.0d;
            case "traceidratio":
            case "parentbased_traceidratio":
                return samplerArg == null ? 1.0d : samplerArg;
            default:
                throw new IllegalStateException(
                        "otel.traces.sampler=" + samplerName + " 无法映射到 B 的 Telemetry.Sampler（比例）。"
                                + "支持：always_on / parentbased_always_on / always_off / parentbased_always_off"
                                + " / traceidratio / parentbased_traceidratio");
        }
    }

    /**
     * 端点归一：<b>裸 {@code host:port} 补成 {@code http://host:port}</b>。
     *
     * <p>B 出处：{@code service/common/src/main/java/com/rag/common/observability/GoStyleOtelConfig.java:204:71-72}
     * {@code otlptracegrpc.WithEndpoint(c.Endpoint)} + {@code otlptracegrpc.WithInsecure()}
     * ⇒ B 的端点<b>不带</b> scheme，传输<b>明文</b>。
     * ⇒ Java 侧 {@code OtlpGrpcSpanExporter} 需要 scheme ⇒ {@code http://} 即明文 h2c（等价于 WithInsecure）。
     */
    static String normalizeEndpoint(String rawEndpoint) {
        if (rawEndpoint == null) {
            return null;
        }
        String endpoint = rawEndpoint.trim();
        if (endpoint.isEmpty()) {
            return null;
        }
        String lower = endpoint.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return endpoint;
        }
        return "http://" + endpoint;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
