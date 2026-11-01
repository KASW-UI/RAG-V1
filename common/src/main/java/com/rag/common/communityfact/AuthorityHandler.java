package com.rag.common.communityfact;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BiFunction;

/**
 * 对应 B 侧版 service/common/communityfact/authority_http.go（80 行）
 *
 * <p>职责：暴露一个内部 HTTP 端点 {@code GET /internal/v1/community/facts}，
 * 用于跨服务查询 community fact authority 的源数据。
 *
 * <p>B 侧版用 {@code http.Handler} + {@code http.ServeMux}，Java 版用 {@link HttpServlet}；
 * 注册方式由业务模块决定（{@code ServletContext.addServlet(...)} 或 {@code @Bean ServletRegistrationBean}）。
 *
 * <p>框架职责：
 * <ul>
 *   <li>B 侧版 net/http → Jakarta Servlet API（{@link HttpServlet}/{@link HttpServletRequest}/{@link HttpServletResponse}）</li>
 *   <li>B 侧版 log/slog → SLF4J（{@link Logger}）</li>
 *   <li>B 侧版 crypto/subtle.ConstantTimeCompare → Java {@link java.security.MessageDigest#isEqual(byte[], byte[])}（恒定时间）</li>
 *   <li>B 侧版 otel propagator → Java OpenTelemetry {@link TextMapGetter} 从 servlet header 提取</li>
 * </ul>
 */
public final class AuthorityHandler {

    /** 共用 Wire 的 Jackson MAPPER；FAIL_ON_UNKNOWN_PROPERTIES 只影响反序列化，发出去无影响。 */
    private static final ObjectMapper MAPPER = Wire.MAPPER;

    /**
     * 对应 B 侧版 {@code type AuthorityLookup func(context.Context, string, string) (AuthorityFact, error)}。
     */
    @FunctionalInterface
    public interface AuthorityLookup {
        AuthorityFact lookup(Context ctx, String producer, String eventId) throws Exception;
    }

    /**
     * DRIFT-012 修复：业务模块抛此 sentinel 异常（或其子类）表示"authority 不可用"，被识别为 404 而非 503。
     * 对应 B 侧版 {@code var ErrAuthorityUnavailable = errors.New("community fact authority unavailable")} + errors.Is。
     */
    public static final class AuthorityUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public AuthorityUnavailableException(String message) {
            super(message);
        }
        public AuthorityUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 沿 cause 链查找 {@link AuthorityUnavailableException} sentinel，等价 B 侧版 {@code errors.Is(err, ErrAuthorityUnavailable)}。
     */
    private static boolean isAuthorityUnavailable(Throwable err) {
        Throwable t = err;
        while (t != null) {
            if (t instanceof AuthorityUnavailableException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * 对应 B 侧版 {@code func NewAuthorityHandler(producer, token string, lookup AuthorityLookup, logger *slog.Logger) (http.Handler, error)}。
     *
     * <p>工厂方法：传入 producer/token/lookup，返回一个 Servlet，由业务模块注册到 servlet 容器上。
     */
    public static HttpServlet create(String producer, String token, AuthorityLookup lookup, Logger logger) {
        if (producer == null || producer.isEmpty()
                || token == null || token.length() < 32
                || lookup == null || logger == null) {
            throw new IllegalArgumentException("community fact authority is not configured");
        }
        return new AuthorityServlet(producer, token, lookup, logger);
    }

    /** SLF4J Logger 工厂版（避免外部传 slog；internal trace handler 由业务模块自行装配）。 */
    public static HttpServlet create(String producer, String token, AuthorityLookup lookup) {
        return create(producer, token, lookup, LoggerFactory.getLogger(AuthorityHandler.class));
    }

    private static final class AuthorityServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        private final String producer;
        private final byte[] tokenBytes;
        private final AuthorityLookup lookup;
        private final Logger logger;
        private final Tracer tracer;

        AuthorityServlet(String producer, String token, AuthorityLookup lookup, Logger logger) {
            this.producer = producer;
            // M20 修复：M20 之前用 token.getBytes()（平台默认字符集）。
            // B 侧 []byte(token) 是 UTF-8 字节切片；在非 UTF-8 默认字符集的平台上
            // （LANG=C / POSIX locale 的 JVM）两边的字节序列不同，恒定时间比较永远不等 → 合法 token 也 401。
            this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
            this.lookup = lookup;
            this.logger = logger;
            this.tracer = GlobalOpenTelemetry.get().getTracer("sea.rtw.communityfact");
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            long started = System.nanoTime();

            // 1) otel context 提取 + 起 server span
            // 对应 B 侧版：ctx := otel.GetTextMapPropagator().Extract(r.Context(), propagation.HeaderCarrier(r.Header))
            //            ctx, span := otel.Tracer(...).Start(ctx, "...", trace.WithSpanKind(SERVER))
            Context ctx = GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                    .extract(Context.current(), req, HeaderGetter.INSTANCE);
            Span span = tracer.spanBuilder("community.fact_authority.lookup")
                    .setSpanKind(SpanKind.SERVER)
                    .setParent(ctx)
                    .startSpan();
            ctx = ctx.with(span);

            int status = 200;
            String outcome = "succeeded";
            try {
                resp.setHeader("Cache-Control", "no-store");

                // 2) Bearer token 校验（恒定时间比较）
                // 对应 B 侧版 subtle.ConstantTimeCompare([]byte(...), []byte(token)) != 1
                // B 侧版 r.Header.Values("Authorization") 要求 len == 1（多个 Authorization 头直接 401）；
                // Java 版 req.getHeader 取首个会漏检；DRIFT-013 修复：显式遍历所有 Authorization 头。
                java.util.List<String> authzValues = java.util.Collections.list(req.getHeaders("Authorization"));
                if (authzValues.size() != 1 || !authzValues.get(0).startsWith("Bearer ")) {
                    status = 401;
                    outcome = "rejected";
                    resp.setStatus(status);
                    return;
                }
                String authz = authzValues.get(0);
                byte[] presented = authz.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
                boolean tokenOk = presented.length == tokenBytes.length
                        && java.security.MessageDigest.isEqual(presented, tokenBytes);
                if (!tokenOk) {
                    status = 401;
                    outcome = "rejected";
                    resp.setStatus(status);
                    return;
                }

                // 3) Query 校验：producer 必须匹配 + event_id 必须非空
                // DRIFT-031 修复：用 getParameterValues 严格检查重复参数
                // 对应 B 侧版 r.URL.Query()["producer"] 取 []string，len != 1 → 404
                // Java 默认 req.getParameter 取第一个，导致 ?producer=a&producer=b 通过验证 → 错误语义
                String[] producerValues = req.getParameterValues("producer");
                String[] eventIdValues = req.getParameterValues("event_id");
                if (producerValues == null || producerValues.length != 1
                        || !producer.equals(producerValues[0])
                        || eventIdValues == null || eventIdValues.length != 1
                        || eventIdValues[0].isEmpty()) {
                    status = 404;
                    outcome = "rejected";
                    resp.setStatus(status);
                    return;
                }
                String producerParam = producerValues[0];
                String eventIdParam = eventIdValues[0];

                // 4) 实际 lookup
                AuthorityFact fact;
                try {
                    fact = lookup.lookup(ctx, producerParam, eventIdParam);
                } catch (Exception ex) {
                    // DRIFT-COMMON-003 修复：判定必须从**原始异常 ex** 起遍历整条 cause 链。
                    // B 出处 service/common/src/main/java/com/rag/common/communityfact/AuthorityHandler.java:185:58 `errors.Is(err, ErrAuthorityUnavailable)` ——
                    // errors.Is 从 err 出发沿 Unwrap 链逐个比对。
                    // 旧实现先 unwrapCause(ex) 走到链**最深**，再拿那个末端元素去判：
                    // AuthorityUnavailableException 有 (String, Throwable) 构造器（:61），
                    // 一旦它自己带 cause，它就在链的**中间**而被 unwrapCause 跳过
                    // ⇒ 404 误降级成 503，客户端可见。
                    // isAuthorityUnavailable 本身已经沿链遍历，不需要任何预遍历。
                    if (isAuthorityUnavailable(ex)) {
                        status = 404;
                        outcome = "rejected";
                        resp.setStatus(status);
                        return;
                    }
                    logger.atLevel(Level.ERROR)
                            .addKeyValue("event", "community.fact_authority.lookup_failed")
                            .addKeyValue("producer", producer)
                            .addKeyValue("event_id", eventIdParam)
                            .addKeyValue("outcome", "failed")
                            .addKeyValue("error_code", "SOURCE_READ_FAILED")
                            .addKeyValue("error_type", "storage")
                            .setCause(ex)
                            .log("community fact authority lookup failed");
                    status = 503;
                    outcome = "failed";
                    resp.setStatus(status);
                    return;
                }

                // 5) 写 JSON 响应
                resp.setContentType("application/json");
                byte[] body;
                try {
                    body = MAPPER.writeValueAsBytes(fact);
                } catch (Exception encErr) {
                    outcome = "failed";
                    logger.atLevel(Level.WARN)
                            .addKeyValue("event", "community.fact_authority.response_interrupted")
                            .addKeyValue("producer", producer)
                            .addKeyValue("event_id", eventIdParam)
                            .addKeyValue("outcome", "failed")
                            .addKeyValue("error_code", "RESPONSE_WRITE_FAILED")
                            .log("community fact authority response interrupted");
                    return;
                }
                resp.setStatus(200);
                // DRIFT-033 修复：捕获 outputStream.write() 的 IOException（client 断开 / 容器关闭）
                // 输出 community.fact_authority.response_interrupted 日志，对齐 B 侧版 json.NewEncoder(w).Encode 失败语义
                try {
                    resp.getOutputStream().write(body);
                } catch (IOException writeErr) {
                    outcome = "failed";
                    logger.atLevel(Level.WARN)
                            .addKeyValue("event", "community.fact_authority.response_interrupted")
                            .addKeyValue("producer", producer)
                            .addKeyValue("event_id", eventIdParam)
                            .addKeyValue("outcome", "failed")
                            .addKeyValue("error_code", "RESPONSE_WRITE_FAILED")
                            .setCause(writeErr)
                            .log("community fact authority response interrupted");
                }
            } finally {
                long elapsedNanos = System.nanoTime() - started;
                logger.atInfo()
                        .addKeyValue("event", "community.fact_authority.request_finished")
                        .addKeyValue("outcome", outcome)
                        .addKeyValue("duration_ms", Duration.ofNanos(elapsedNanos).toNanos() / 1_000_000.0)
                        .addKeyValue("status", status)
                        .addKeyValue("producer", producer)
                        .log("community fact authority request finished");
                span.end();
            }
        }
    }

    /**
     * Servlet Header getter，把 HttpServletRequest 的 header 喂给 otel propagator。
     * 对应 B 侧版 {@code propagation.HeaderCarrier(r.Header)}。
     *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/AuthorityHandler.java}
 * （1:1 · 映射表 §1.2 第 146 行；L2 业务语义层以 RAG 为基准整段抄 · D13；
 * 本类 Jackson 写法在 RAG 侧<b>本就是 Jackson 2</b> ⇒ 零回改）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧引用 {@code Wire.AuthorityFact}（嵌套类型）
 * ⇒ ⇒ RAG-V1 的 wire 类型已拆成顶层类 ⇒ 改为直接引用 {@link AuthorityFact} ⇒ **import 不用改**（同包）。
 */
    private enum HeaderGetter implements TextMapGetter<HttpServletRequest> {
        INSTANCE;

        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return java.util.Collections.list(carrier.getHeaderNames());
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier == null ? null : carrier.getHeader(key);
        }
    }

    private AuthorityHandler() {
    }

    // 保留 BiFunction 引用作为"业务模块可自行实现的接口"提示（B 侧版 AuthorityLookup 是函数类型）
    @SuppressWarnings("unused")
    private static final Class<?> LOOKUP_HINT = BiFunction.class;
}
