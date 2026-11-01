package com.rag.common.observability;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 🔴 **假 OTLP 接收端**（扮演 jaeger）—— 一个**真的** gRPC 服务端，
 * 接收 OTLP/gRPC 的 {@code Export} 调用并把请求体原样记下来。
 *
 * <h2>为什么必须是「真的 gRPC 服务端」而不是 mock</h2>
 * <p>要证明的是「<b>span 真的被导出到网络上</b>」，
 * 而不是「exporter 被配上了」（§3.3：<b>「注解存在 / Bean 存在 / 编译通过」≠「机制生效」</b>）。
 * ⇒ 用 mock 只能证明「代码调用了 exporter」，证明不了「字节真的出去了」。
 * ⇒ ⇒ 所以起一个**真的** gRPC 服务端：
 * <ul>
 *   <li>客户端侧：{@code opentelemetry-exporter-otlp} 内部的 OkHttp 传输，
 *       明文时用 {@code okhttp3.Protocol.H2_PRIOR_KNOWLEDGE} ⇒ h2c；</li>
 *   <li>服务端侧：本类用 {@code grpc-netty-shaded}（h2c prior knowledge 同样支持）
 *       ⇒ 两侧都是 HTTP/2 + gRPC 帧 ⇒ 真的能通。</li>
 * </ul>
 *
 * <h2>为什么请求体用「字节数组」而不是 protobuf</h2>
 * <p>OTLP 的 proto 定义（{@code ExportTraceServiceRequest}）不在本仓依赖里
 * ⇒ 本类不引入 proto 依赖，改用 {@link MethodDescriptor.Marshaller} 的字节数组实现，
 * <b>原样记录</b>收到的字节 ⇒ 断言「收到了 ≥1 个非空请求」即可。
 * ⇒ 响应体是<b>空</b>字节数组 ⇒ 等价于一个空的 {@code ExportTraceServiceResponse}
 * （protobuf 允许空消息）⇒ exporter 侧视作成功。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/test/java/com/rag/common/observability/FakeOtlpCollector.java}
 * 整段抄（L2 · D13 · 测试形态可直接借）。
 */
public final class FakeOtlpCollector implements AutoCloseable {

    /** OTLP trace 收集器的标准方法名（{@code opentelemetry-proto} 的标准定义）。 */
    private static final String EXPORT_METHOD =
            "opentelemetry.proto.collector.trace.v1.TraceService/Export";

    private final Server server;
    private final List<byte[]> received = new CopyOnWriteArrayList<>();

    public FakeOtlpCollector() throws IOException {
        MethodDescriptor<byte[], byte[]> method = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(EXPORT_METHOD)
                .setRequestMarshaller(new ByteArrayMarshaller())
                .setResponseMarshaller(new ByteArrayMarshaller())
                .build();
        ServerServiceDefinition service = ServerServiceDefinition
                .builder("opentelemetry.proto.collector.trace.v1.TraceService")
                .addMethod(method, new ServerCallHandler<byte[], byte[]>() {
                    @Override
                    public ServerCall.Listener<byte[]> startCall(ServerCall<byte[], byte[]> call, Metadata headers) {
                        call.request(2);
                        return new ServerCall.Listener<byte[]>() {
                            @Override
                            public void onMessage(byte[] message) {
                                received.add(message);
                            }

                            @Override
                            public void onHalfClose() {
                                call.sendHeaders(new Metadata());
                                call.close(Status.OK, new Metadata());
                            }
                        };
                    }
                })
                .build();
        server = NettyServerBuilder.forPort(0).addService(service).build().start();
    }

    /** 端点（{@code http://127.0.0.1:<port>}）—— 与上游的 {@code WithEndpoint} + {@code WithInsecure()} 同义。 */
    public String endpoint() {
        return "http://127.0.0.1:" + server.getPort();
    }

    /** 已收到的 Export 请求数。 */
    public int requestCount() {
        return received.size();
    }

    /** 收到的请求体总字节数（用来确认不是空请求）。 */
    public int receivedBytes() {
        int total = 0;
        for (byte[] body : received) {
            total += body.length;
        }
        return total;
    }

    @Override
    public void close() {
        server.shutdownNow();
        try {
            server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 字节数组的 gRPC 编解码（不引 proto 依赖 ⇒ 原样透传）。 */
    private static final class ByteArrayMarshaller implements MethodDescriptor.Marshaller<byte[]> {

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("read OTLP request body failed", e);
            }
        }
    }
}
