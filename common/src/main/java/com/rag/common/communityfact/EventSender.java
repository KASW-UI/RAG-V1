package com.rag.common.communityfact;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * <p><b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/communityfact/Wire.java} 的
 * {@code type Sender interface { Send(context.Context, Event, json.RawMessage) (TechnicalReceipt, error) }}
 * ＋ {@code type DCEventSender struct} ＋ {@code func NewDCEventSender(...)} ＋ {@code func (s *DCEventSender) Send(...)}。
 *
 * <p><b>⚠️ 拆出后的落点差异</b>：RAG 侧 {@code Sender} 与 {@code DCEventSender} 都是
 * {@code Wire.java} 的<b>嵌套</b>类型 ⇒ ⇒ RAG-V1 拆成本文件的<b>顶层</b>类型
 * （映射表 §1.2 第 143 行 / 决策锁 L-7 第 4 条：
 * 「{@code Sender} 接口 + {@code DCEventSender} 拆成 {@code EventSender}」）
 * ⇒ ⇒ 调用点由 {@code Wire.Sender} / {@code Wire.DCEventSender} 改为
 * {@code EventSender} / {@code EventSender.DCEventSender} ⇒ **import 要同步改**。
 *
 * <p><b>行为契约（逐条照抄 RAG · 不可省）</b>：
 * <ol>
 *   <li><b>6 条 endpoint / token 校验</b>：协议须 http/https · host 非空 ·
 *       无 userinfo · 无 query · 无 fragment · path 须<b>恰为</b> {@code /v1/events}；</li>
 *   <li><b>token 长度 ≥ 32</b>，{@code client} 非空；</li>
 *   <li><b>64KB body 限流</b>：非 2xx 分支「读满 64KB 再关连接」（让连接进复用池）；
 *       2xx 分支<b>读到 64KB 即停</b>（M21：<b>不</b>抽干剩余字节）；</li>
 *   <li><b>丢弃 body</b>（非 2xx 时）；</li>
 *   <li><b>{@code Idempotency-Key = event.eventId}</b>；</li>
 *   <li><b>otel trace propagation</b>：把当前 ctx 的 traceparent 注入 header。</li>
 * </ol>
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/Wire.java}
 * 的嵌套接口 {@code Sender} 与嵌套类 {@code DCEventSender}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
public interface EventSender {

    /**
     * 发出 ctx、event、raw，返回 TechnicalReceipt。
     */
    TechnicalReceipt send(Context ctx, Event event, byte[] raw) throws Exception;

    /**
     * DC 事件发送器 —— 对应 B 侧版 {@code type DCEventSender struct} ＋
     */
    final class DCEventSender implements EventSender {
        private final String endpoint;
        private final String token;
        private final HttpClient client;

        private DCEventSender(String endpoint, String token, HttpClient client) {
            this.endpoint = endpoint;
            this.token = token;
            this.client = client;
        }

        /**
         * 对应 B 侧版 {@code func NewDCEventSender(endpoint, token string, client *http.Client) (*DCEventSender, error)}。
         * 校验 endpoint 协议/路径 + token 长度 + client 非空。
         */
        public static DCEventSender create(String endpoint, String token, HttpClient client) {
            if (endpoint == null || token == null || client == null || token.length() < 32) {
                throw new IllegalArgumentException("community DataCenter event sender is not configured");
            }
            URI parsed = URI.create(endpoint);
            String scheme = parsed.getScheme();
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || parsed.getHost() == null
                    || parsed.getUserInfo() != null
                    || (parsed.getQuery() != null && !parsed.getQuery().isEmpty())
                    || (parsed.getFragment() != null && !parsed.getFragment().isEmpty())
                    || !"/v1/events".equals(parsed.getPath())) {
                throw new IllegalArgumentException("community DataCenter event sender is not configured");
            }
            return new DCEventSender(parsed.toString(), token, client);
        }

        /**
         * 对应 B 侧版 {@code func (s *DCEventSender) Send(ctx, event, raw) (TechnicalReceipt, error)}。
         *
         * <p>非 200/201 状态码按 B 侧版语义抛 {@link Wire.DataCenterAcceptanceRejectedException}，
         * 不再返"伪 receipt"以避免调用方误把错误的 receipt 当成合法的 acceptance。
         */
        @Override
        public TechnicalReceipt send(Context ctx, Event event, byte[] raw) throws Exception {
            if (client == null) {
                throw new IllegalStateException("community DataCenter event sender is not configured");
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", event.eventId == null ? "" : event.eventId)
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(raw));
            // otel trace propagation：把当前 ctx 的 traceparent 写进 header
            // 对应 B 侧版 otel.GetTextMapPropagator().Inject(ctx, propagation.HeaderCarrier(request.Header))
            GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(ctx, builder, BuilderSetter.INSTANCE);

            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status != 201 && status != 200) {
                // 对应 B 侧版 io.Copy(io.Discard, io.LimitReader(response.Body, 64<<10)) 丢弃 body 防连接泄漏
                try (InputStream in = response.body()) {
                    discardUpTo(in, 64 * 1024);
                }
                throw new Wire.DataCenterAcceptanceRejectedException(status, "DataCenter event acceptance status " + status);
            }
            // 对应 B 侧版 io.LimitReader(response.Body, 64<<10)：流式读取最多 64KB
            try (InputStream in = response.body()) {
                byte[] limited = readLimitUpTo(in, 64 * 1024);
                return Wire.strictDecode(new java.io.ByteArrayInputStream(limited), TechnicalReceipt.class);
            }
        }

        /**
         * 流式读取最多 {@code maxBytes} 字节后停止；<b>不</b>把剩余字节抽干。
         *
         * <p><b>M21 修复</b>：B 侧版是 {@code StrictDecode(io.LimitReader(body, 64<<10), &receipt)}，
         * {@code LimitReader} 读到上限就停，剩余部分由 {@code defer response.Body.Close()} 直接丢弃 ——
         * <b>从不多读一个字节</b>。旧 Java 版在触到上限后还调 {@code discardUpTo(in, MAX_VALUE)}
         * 把整个响应体读完：一个超大/慢速的响应体会让投递线程一直挂着，而 B 侧会立刻返回。
         * 现与 B 侧 对齐：读到上限即返回，剩下的交给 try-with-resources 关闭。
         */
        private static byte[] readLimitUpTo(InputStream in, int maxBytes) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(maxBytes, 1024));
            byte[] chunk = new byte[1024];
            int total = 0;
            while (total < maxBytes) {
                int allowed = Math.min(chunk.length, maxBytes - total);
                int n = in.read(chunk, 0, allowed);
                if (n < 0) break;
                buf.write(chunk, 0, n);
                total += n;
            }
            // M21：不抽干剩余字节；剩余部分由 try-with-resources 关闭连接时一并丢弃
            return buf.toByteArray();
        }

        /**
         * 对应 B 侧版 {@code io.Copy(io.Discard, io.LimitReader(body, 64<<10))}：
         * <b>仅用于非 2xx 分支</b>——那里 B 侧 确实主动读了最多 64KB 再关连接（为了让连接能进复用池）。
         * 2xx 分支走 {@link #readLimitUpTo}，不调用本方法。
         */
        private static void discardUpTo(InputStream in, int maxBytes) throws IOException {
            byte[] sink = new byte[1024];
            int total = 0;
            while (total < maxBytes) {
                int n = in.read(sink, 0, Math.min(sink.length, maxBytes - total));
                if (n < 0) break;
                total += n;
            }
        }

        private static final class BuilderSetter implements TextMapSetter<HttpRequest.Builder> {
            static final BuilderSetter INSTANCE = new BuilderSetter();
            @Override
            public void set(HttpRequest.Builder carrier, String key, String value) {
                if (carrier != null) {
                    carrier.header(key, value);
                }
            }
        }
    }
}
