package com.rag.common.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

import java.time.Duration;
import java.util.Map;

/**
 * <b>业务观测标记的<b>唯一载体</b></b> —— 对齐 go-zero {@code service/common/observability/observability.go} 的
 * {@code Mark*} 系列，<b>但把写入目标从「手写 OTel span」换成「Micrometer Observation + OTel span 双载体」</b>。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ D4 生态优先（L-4 · D-03 降级）：什么交给生态、什么必须手写</b>
 * ══════════════════════════════════════════════════════════════════════
 * <table border="1">
 *   <tr><th>能力</th><th>交给生态</th><th>本类保留（不可交）</th></tr>
 *   <tr><td>span 创建 / 生命周期 / 父子关系 / 计时</td><td>✅ Micrometer {@code Observation}</td><td>—</td></tr>
 *   <tr><td>指标与百分位</td><td>✅ Micrometer {@code Timer} / registry</td><td>—</td></tr>
 *   <tr><td>上下文传播</td><td>✅ Observation 作用域</td><td>—</td></tr>
 *   <tr><td><b>业务标记的键名与取值口径</b></td><td>—</td>
 *       <td>✅ {@code sea.slow} / {@code sea.biz_code} / {@code sea.biz_msg} /
 *           {@code sea.error.kind} / {@code sea.timeout} / {@code sea.duration_ms} / {@code error}
 *           —— <b>业务定义，键名必须逐字保留</b></td></tr>
 *   <tr><td><b>错误分类的判定顺序</b></td><td>—</td><td>✅（见下「判定顺序」）</td></tr>
 * </table>
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ 为什么是「双载体」而不是只写 Micrometer</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p>Micrometer 的 {@link io.micrometer.common.KeyValue} <b>只有 String 值</b>
 * （javap 实测：{@code KeyValue.of(String, String)} 是唯一的 String 键工厂）
 * ⇒ ⇒ 写进 Observation 的 {@code sea.biz_code}（Go 是 <b>long</b>）与
 * {@code sea.duration_ms}（Go 是 <b>double</b>）会退化成<b>字符串属性</b>
 * ⇒ ⇒ <b>属性类型变了</b>（值不变）⇒ 告警/看板的查询表达式要跟着改。
 * <br>⇒ ⇒ 本类同时写 <b>OTel span</b>（<b>带类型</b>：{@code put(String, long)} / {@code put(String, double)}）
 * ⇒ ⇒ <b>Go 的属性类型逐字保住</b>；
 * ⇒ ⇒ 同时写 <b>Micrometer {@code Observation.Context}</b>（D4 的目标形态，供指标与未来的 tracing bridge 用）。
 * <br>⚠️ <b>取舍说明</b>：两处都写 ⇒ 同一标记在两个载体里各有一份。
 * ⇒ 这是**有意的**：OTel 侧保类型、Micrometer 侧接生态。
 * ⇒ 若日后 {@code micrometer-tracing-bridge-otel} 到位（<b>当前离线仓无 1.5.9 ⇒ 见台账待补</b>），
 * bridge 会把 Observation 的键值再写一遍进 OTel span ⇒ <b>同名属性被覆盖成字符串</b>
 * ⇒ ⇒ 那时应当<b>撤掉本类的 OTel 直写</b>（否则类型又被降级回去）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ 判定顺序（逐条照抄 RAG，顺序本身就是行为）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <pre>
 *   HTTP 侧（RAG Observability.markHttpResult）：
 *     ① 信封 code != 0 &amp;&amp; code != 200  ⇒ **business error**   ← 业务码优先于状态码
 *     ② 否则 statusCode &gt;= 400             ⇒ **system error**
 *     ③ 否则                                  ⇒ 正常（只写 duration / slow）
 *
 *   gRPC 侧（RAG RpcInterceptor 的 close 路径）：
 *     ① status == OK                          ⇒ 正常
 *     ② status == DEADLINE_EXCEEDED 或 已超时 ⇒ **timeout**          ← 优先于业务码
 *     ③ 业务码集合（7 个）                     ⇒ **business**
 *     ④ 其余                                   ⇒ **system**
 * </pre>
 * ⇒ ⚠️ <b>把 ① 和 ② 的顺序调换 ⇒ {@code sea.error.kind} 变 ⇒ 是可观察差异</b>。
 *
 * <p><b>⚠️ 6 条最容易漏、但会改变可观察结果的细节（全部照抄 RAG）</b>：
 * <ol>
 *   <li><b>慢判定是<b>严格大于</b></b>：{@code duration &gt; threshold} ⇒ <b>等于阈值不算慢</b>
 *       （RAG {@code Observability.java:143-146}）；</li>
 *   <li><b>{@code sea.slow} 只有 {@code true}</b>，没有 {@code false} ⇒ 未出现即「不慢」；</li>
 *   <li><b>{@code sea.biz_code} 只在 business 类出现</b>；system / timeout <b>不带</b>它；</li>
 *   <li><b>{@code sea.timeout} 只在 timeout 类出现</b>；</li>
 *   <li><b>业务错误的 {@code sea.biz_msg} 可能为 null ⇒ 属性直接缺失</b>；
 *       而 system / timeout 的 msg <b>永远非 null</b>（{@code ""} 兜底）；</li>
 *   <li><b>system / timeout 无条件记一条 exception</b>（含 {@code err == null} 时造一个）
 *       ⇒ 少这一条会少一条 span event（RAG {@code Observability.java:108 / 131}）。</li>
 * </ol>
 * ⇒ 另有一条<b>刻意保留的 RAG 怪癖</b>：{@code markBusinessError(code == 200)} 会降级成
 * 「只写 duration / slow」，且此时用的是<b>环境变量口径</b>的阈值（{@link #slowThreshold()}）
 * 而<b>不是</b>调用方传入的阈值 ⇒ <b>两套阈值口径并存</b>（RAG {@code Observability.java:70} vs {@code :143}）
 * ⇒ ⇒ 照抄，不「顺手统一」。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/Observability.java}
 * ＋ {@code ObservabilityConstants.java} 整段抄（L2 业务语义层以 RAG 为基准 · D13）
 * ＋ <b>写入载体由「手写 OTel span」改为「Micrometer Observation + OTel 双载体」</b>（L4 · D4 / L-4 / D-03）。
 */
public final class ObservabilityCustomizer {

    // ── 业务常量：Go observability.go 的两个常量块，键名与取值逐字一致 ──────

    /** 业务成功码（⚠️ <b>不是</b> HTTP 200 / gRPC OK，是 SEA 业务约定的成功码）。 */
    public static final int SUCCESS_BIZ_CODE = 200;

    /** 默认慢调用阈值（500ms）。 */
    public static final Duration DEFAULT_SLOW_THRESHOLD = Duration.ofMillis(500);

    /** 通用 error 标志的 attribute key（⚠️ 字面 {@code "error"}，不是 {@code error.type}）。 */
    public static final String ATTR_ERROR = "error";

    /** 错误种类 attribute key。 */
    public static final String ATTR_ERROR_KIND = "sea.error.kind";

    /** 业务码 attribute key。 */
    public static final String ATTR_BIZ_CODE = "sea.biz_code";

    /** 业务信息 attribute key。 */
    public static final String ATTR_BIZ_MSG = "sea.biz_msg";

    /** 超时标记 attribute key。 */
    public static final String ATTR_TIMEOUT = "sea.timeout";

    /** 慢调用标记 attribute key。 */
    public static final String ATTR_SLOW = "sea.slow";

    /** 持续时间 attribute key（毫秒）。 */
    public static final String ATTR_DURATION = "sea.duration_ms";

    /** 错误种类：业务错误。 */
    public static final String ERROR_KIND_BUSINESS = "business";

    /** 错误种类：系统错误。 */
    public static final String ERROR_KIND_SYSTEM = "system";

    /** 错误种类：超时。 */
    public static final String ERROR_KIND_TIMEOUT = "timeout";

    /** 环境变量名（slow 阈值，Go duration 格式）。 */
    public static final String ENV_SLOW_THRESHOLD = "SEA_OBSERVABILITY_SLOW_THRESHOLD";

    /** 环境变量名（slow 阈值，整数毫秒）。 */
    public static final String ENV_SLOW_THRESHOLD_MS = "SEA_OBSERVABILITY_SLOW_THRESHOLD_MS";

    /**
     * Micrometer 侧布尔标记的<b>字符串</b>形态。
     *
     * <p>⚠️ <b>为什么是字符串</b>：Micrometer 的 {@link io.micrometer.common.KeyValue}
     * <b>只有 String 值的工厂</b>（javap 实测：{@code KeyValue.of(String, String)} 是唯一的
     * String 键工厂，<b>没有</b> {@code of(String, boolean)}）
     * ⇒ ⇒ {@code sea.error} / {@code sea.timeout} / {@code sea.slow} 在 Observation 侧
     * <b>只能是字符串</b> ⇒ ⇒ <b>类型退化为字符串</b>（值不变：{@code "true"}）
     * ⇒ ⇒ <b>已登记为 L3</b>（D-03 的降级面）。
     * <br>⇒ ⇒ OTel 侧仍然写<b>真正的 boolean</b>（{@code span.setAttribute(key, true)}）
     * ⇒ ⇒ Go 的属性类型在 OTel 侧逐字保住。
     */
    private static final String TRUE_STRING = "true";

    private ObservabilityCustomizer() {
    }

    // ── 阈值与时长 ───────────────────────────────────────────────────────

    /**
     * <b>环境变量口径</b>的慢调用阈值：先读 {@code SEA_OBSERVABILITY_SLOW_THRESHOLD}
     * （Go duration 串），再读 {@code SEA_OBSERVABILITY_SLOW_THRESHOLD_MS}（整数毫秒），
     * 全不合法回落 {@link #DEFAULT_SLOW_THRESHOLD}。
     */
    public static Duration slowThreshold() {
        for (String key : new String[]{ENV_SLOW_THRESHOLD, ENV_SLOW_THRESHOLD_MS}) {
            String raw = System.getenv(key);
            raw = raw == null ? "" : raw.trim();
            if (raw.isEmpty()) {
                continue;
            }
            Duration d = parseGoDuration(raw);
            if (d != null && d.compareTo(Duration.ZERO) > 0) {
                return d;
            }
            try {
                long ms = Long.parseLong(raw);
                if (ms > 0) {
                    return Duration.ofMillis(ms);
                }
            } catch (NumberFormatException ignore) {
                // 不是纯毫秒 ⇒ 继续下一个口径
            }
        }
        return DEFAULT_SLOW_THRESHOLD;
    }

    /** Go {@code TimeoutFromMillis}：{@code ms <= 0 ⇒ 0}（不限时）。 */
    public static Duration timeoutFromMillis(long ms) {
        if (ms <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(ms);
    }

    /** Go {@code durationMilliseconds}：{@code float64(microseconds)/1000}（保留亚毫秒精度）。 */
    public static double durationMillis(Duration duration) {
        if (duration == null) {
            return 0.0D;
        }
        return (double) duration.toNanos() / 1_000_000.0D;
    }

    /**
     * Go {@code IsTimeoutError}：沿 cause 链走，对齐 {@code errors.Is} / {@code errors.As}。
     *
     * <p>覆盖面很宽：{@link java.util.concurrent.TimeoutException} ·
     * {@link java.net.SocketTimeoutException} · {@link java.net.http.HttpTimeoutException} ·
     * {@link java.net.http.HttpConnectTimeoutException} · {@link java.io.InterruptedIOException}
     * ＋ gRPC {@code DEADLINE_EXCEEDED}。
     */
    public static boolean isTimeoutError(Throwable err) {
        if (err == null) {
            return false;
        }
        Throwable t = err;
        while (t != null) {
            if (t instanceof java.util.concurrent.TimeoutException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.http.HttpTimeoutException
                    || t instanceof java.net.http.HttpConnectTimeoutException
                    || t instanceof java.io.InterruptedIOException) {
                return true;
            }
            io.grpc.Status st = io.grpc.Status.fromThrowable(t);
            if (st != null && st.getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                return true;
            }
            Throwable next = t.getCause();
            if (next == t) {
                break;
            }
            t = next;
        }
        return false;
    }

    /**
     * 慢判定：<b>严格大于</b>（等于阈值<b>不算</b>慢）且阈值必须 &gt; 0。
     */
    public static boolean isSlow(Duration duration, Duration slowThreshold) {
        return slowThreshold != null && slowThreshold.compareTo(Duration.ZERO) > 0
                && duration != null && duration.compareTo(slowThreshold) > 0;
    }

    // ── 标记载体 ─────────────────────────────────────────────────────────

    /**
     * 一组<b>已判定好</b>的观测标记。
     *
     * <p>把「判定」与「写入」分开 ⇒ 判定是<b>纯函数</b> ⇒ 可直接单测（无需任何框架上下文）。
     */
    public static final class Markers {
        /** 错误种类；{@code null} 表示<b>无错误</b>（正常路径）。 */
        public final String errorKind;
        /** 业务码；<b>只在 business 类存在</b>。 */
        public final Long bizCode;
        /** 业务信息；business 类可能为 {@code null}（⇒ 属性缺失）。 */
        public final String bizMsg;
        /** 是否超时（⇒ {@code sea.timeout=true}）。 */
        public final boolean timeout;
        /** 是否慢调用（⇒ {@code sea.slow=true}）。 */
        public final boolean slow;
        /** 耗时（毫秒，double，亚毫秒精度）。 */
        public final double durationMs;
        /** 要记的异常（system / timeout 类恒非 null）。 */
        public final Throwable exception;

        Markers(String errorKind, Long bizCode, String bizMsg, boolean timeout, boolean slow,
                double durationMs, Throwable exception) {
            this.errorKind = errorKind;
            this.bizCode = bizCode;
            this.bizMsg = bizMsg;
            this.timeout = timeout;
            this.slow = slow;
            this.durationMs = durationMs;
            this.exception = exception;
        }
    }

    // ── 四个判定（纯函数 · 照抄 RAG 的 mark* 语义） ────────────────────────

    /**
     * Go {@code MarkBusinessError} 的判定部分。
     *
     * <p>⚠️ {@code code == 200} 时<b>降级</b>成「只写 duration / slow」，
     * 且此时用的是<b>环境变量口径</b>的阈值（{@link #slowThreshold()}）—— 照抄 RAG 的这处口径分叉。
     */
    public static Markers classifyBusinessError(int code, String msg, Duration duration) {
        if (code == SUCCESS_BIZ_CODE) {
            return classifySuccess(duration, slowThreshold());
        }
        return new Markers(ERROR_KIND_BUSINESS, (long) code, msg, false,
                isSlow(duration, slowThreshold()), durationMillis(duration), null);
    }

    /** Go {@code MarkSystemError} 的判定部分（<b>不带 biz_code</b>）。 */
    public static Markers classifySystemError(Throwable err, Duration duration) {
        String msg = err == null ? "system error" : errorMessageOf(err);
        Throwable recorded = err != null ? err : new RuntimeException(msg);
        return new Markers(ERROR_KIND_SYSTEM, null, msg, false,
                isSlow(duration, slowThreshold()), durationMillis(duration), recorded);
    }

    /** Go {@code MarkTimeout} 的判定部分（<b>不带 biz_code</b>，<b>带 sea.timeout</b>）。 */
    public static Markers classifyTimeout(Throwable err, Duration duration) {
        String msg = err == null ? "timeout" : errorMessageOf(err);
        Throwable recorded = err != null ? err : new RuntimeException(msg);
        return new Markers(ERROR_KIND_TIMEOUT, null, msg, true,
                isSlow(duration, slowThreshold()), durationMillis(duration), recorded);
    }

    /** Go {@code MarkDurationAndSlow} 的判定部分（正常路径：<b>无 error 标记</b>）。 */
    public static Markers classifySuccess(Duration duration, Duration slowThreshold) {
        return new Markers(null, null, null, false,
                isSlow(duration, slowThreshold), durationMillis(duration), null);
    }

    // ── 写入（双载体） ────────────────────────────────────────────────────

    /**
     * 对应 Go {@code MarkBusinessError}。
     *
     * @param ctx Micrometer {@link Observation.Context}（可为 {@code null} ⇒ 只写 OTel span）
     */
    public static void markBusinessError(Observation.Context ctx, int code, String msg, Duration duration) {
        Markers m = classifyBusinessError(code, msg, duration);
        apply(ctx, m);
    }

    /** 对应 Go {@code MarkSystemError}。 */
    public static void markSystemError(Observation.Context ctx, Throwable err, Duration duration) {
        apply(ctx, classifySystemError(err, duration));
    }

    /** 对应 Go {@code MarkTimeout}。 */
    public static void markTimeout(Observation.Context ctx, Throwable err, Duration duration) {
        apply(ctx, classifyTimeout(err, duration));
    }

    /** 对应 Go {@code MarkDurationAndSlow}（显式传阈值）。 */
    public static void markDurationAndSlow(Observation.Context ctx, Duration duration, Duration slowThreshold) {
        apply(ctx, classifySuccess(duration, slowThreshold));
    }

    /**
     * 把已判定的标记写进<b>两个载体</b>。
     *
     * <p>⚠️ OTel 侧的写入受 {@code span.isRecording()} 保护（与 RAG 一致：
     * <b>不 recording ⇒ 整批静默丢弃</b>）；Micrometer 侧以 {@code ctx == null} 为同样的保护。
     */
    public static void apply(Observation.Context ctx, Markers m) {
        // ── OTel span（带类型，保住 Go 的属性类型） ──
        Span span = Span.current();
        if (span.isRecording()) {
            if (m.errorKind != null) {
                span.setAttribute(ATTR_ERROR, true);
                span.setAttribute(ATTR_ERROR_KIND, m.errorKind);
            }
            if (m.bizCode != null) {
                span.setAttribute(ATTR_BIZ_CODE, m.bizCode.longValue());
            }
            if (m.bizMsg != null) {
                // ⚠️ business 类的 msg 可能为 null ⇒ OTel 忽略 null 值 ⇒ 属性直接缺失（与 Go 一致）
                span.setAttribute(ATTR_BIZ_MSG, m.bizMsg);
            }
            if (m.timeout) {
                span.setAttribute(ATTR_TIMEOUT, true);
            }
            if (m.slow) {
                span.setAttribute(ATTR_SLOW, true);
            }
            span.setAttribute(ATTR_DURATION, m.durationMs);
            if (m.exception != null) {
                span.recordException(m.exception);
            }
            if (m.errorKind != null) {
                span.setStatus(StatusCode.ERROR, m.bizMsg);
            }
        }

        // ── Micrometer Observation（D4 的目标形态） ──
        if (ctx != null) {
            if (m.errorKind != null) {
                ctx.addLowCardinalityKeyValue(KeyValue.of(ATTR_ERROR, TRUE_STRING));
                ctx.addLowCardinalityKeyValue(KeyValue.of(ATTR_ERROR_KIND, m.errorKind));
            }
            if (m.bizCode != null) {
                ctx.addHighCardinalityKeyValue(KeyValue.of(ATTR_BIZ_CODE, String.valueOf(m.bizCode)));
            }
            if (m.bizMsg != null) {
                ctx.addHighCardinalityKeyValue(KeyValue.of(ATTR_BIZ_MSG, m.bizMsg));
            }
            if (m.timeout) {
                ctx.addLowCardinalityKeyValue(KeyValue.of(ATTR_TIMEOUT, TRUE_STRING));
            }
            if (m.slow) {
                ctx.addLowCardinalityKeyValue(KeyValue.of(ATTR_SLOW, TRUE_STRING));
            }
            ctx.addHighCardinalityKeyValue(KeyValue.of(ATTR_DURATION, String.valueOf(m.durationMs)));
            if (m.exception != null) {
                ctx.setError(m.exception);
            }
        }
    }

    /**
     * Go {@code err.Error()} 的取值语义：{@code getMessage()} 原样，{@code null} 归一为 {@code ""}。
     *
     * <p>⚠️ <b>刻意不</b>用 {@code getClass().getSimpleName()} 兜底 ——
     * 那是 Java 独有的信息，Go 侧对应位置会是空串 ⇒ 属于凭空多出来的 {@code sea.biz_msg} 值。
     */
    private static String errorMessageOf(Throwable err) {
        String m = err.getMessage();
        return m != null ? m : "";
    }

    /** 解析 Go 版 {@code time.ParseDuration} 子集（ns/us/ms/s/m/h）。 */
    private static Duration parseGoDuration(String raw) {
        try {
            long totalNanos = 0;
            int i = 0;
            int n = raw.length();
            while (i < n) {
                int j = i;
                while (j < n && (Character.isDigit(raw.charAt(j)) || raw.charAt(j) == '.')) {
                    j++;
                }
                if (j == i) {
                    return null;
                }
                double value = Double.parseDouble(raw.substring(i, j));
                int k = j;
                while (k < n && Character.isLetter(raw.charAt(k))) {
                    k++;
                }
                if (k == j) {
                    return null;
                }
                long nanos;
                switch (raw.substring(j, k)) {
                    case "ns" -> nanos = (long) value;
                    case "us" -> nanos = (long) (value * 1_000L);
                    case "ms" -> nanos = (long) (value * 1_000_000L);
                    case "s" -> nanos = (long) (value * 1_000_000_000L);
                    case "m" -> nanos = (long) (value * 60L * 1_000_000_000L);
                    case "h" -> nanos = (long) (value * 3600L * 1_000_000_000L);
                    default -> {
                        return null;
                    }
                }
                totalNanos += nanos;
                i = k;
            }
            return Duration.ofNanos(totalNanos);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
