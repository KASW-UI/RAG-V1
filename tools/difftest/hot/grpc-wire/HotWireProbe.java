package com.rag.hot.parity;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * <b>hot 域 gRPC wire 层原始字节探针</b> —— 双侧活体对拍用。
 *
 * <h2>为什么不用生成的 pb 代码</h2>
 * <p>两侧的 pb 包名<b>不同</b>（B 侧 = {@code com.rag.hot.hot.proto} ·
 * C 侧 = {@code com.rag.proto.hot}）⇒ 若用各自的生成类构造请求 / 解析响应，
 * 两侧跑的<b>不是同一段代码</b> ⇒ 「字节一致」的证据会被「两侧实现不同」污染。
 * <p>⇒ 本探针<b>只用原始字节</b>：请求体按 proto3 的 wire format <b>手工构造</b>，
 * 响应用 {@code byte[]} marshaller <b>原样取出</b>
 * ⇒ ⇒ <b>同一个 jar 跑两侧</b> ⇒ 比的就是纯粹的 wire 字节。
 *
 * <h2>契约（{@code proto/hot.proto} · 与 B 侧逐字节一致）</h2>
 * <pre>
 *   package hot;   service HotService {
 *     GetHotArticles(GetHotArticlesRequest) → GetHotArticlesResponse
 *     LikeArticle   (LikeArticleRequest)    → LikeArticleResponse
 *     CommentArticle(CommentArticleRequest) → CommentArticleResponse
 *     CoinArticle   (CoinArticleRequest)    → CoinArticleResponse
 *     ShareArticle  (ShareArticleRequest)   → ShareArticleResponse
 *   }
 *   GetHotArticlesRequest { int32 top_k = 1; }
 *   GetHotArticlesResponse{ repeated HotArticleItem items = 1; }
 *   HotArticleItem        { string article_id = 1; uint32 hot_score = 2; }
 *   LikeArticleRequest    { string article_id = 1; string user_id = 2; }
 *   CommentArticleRequest { string article_id = 1; string user_id = 2; }
 *   CoinArticleRequest    { string article_id = 1; string user_id = 2; int32 coin_count = 3; }
 *   ShareArticleRequest   { string article_id = 1; string user_id = 2; string channel = 3; }
 *   *ArticleResponse      { bool success = 1; }
 * </pre>
 *
 * <h2>⚠️ proto3 的两个编码细节（弄错就不是「真实客户端」的字节了）</h2>
 * <ol>
 *   <li><b>默认值不编码</b>：{@code top_k = 0} ⇒ 请求体<b>是空的</b>（0 字节）
 *       ⇒ {@code code=0} 这个 case 测的正是「零值不落 wire」。</li>
 *   <li><b>int32 的负值按 64 位符号扩展编码</b>：{@code top_k = -1} ⇒ <b>10 字节</b> varint
 *       （{@code 0xFF × 9 + 0x01}），不是 5 字节。
 *       ⇒ {@link #varint(long)} 必须用 {@code long} 运算（{@code >>>= 7}）才能得到这 10 字节。</li>
 * </ol>
 *
 * <h2>🔴 为什么必须用 {@code NettyChannelBuilder.forAddress(InetSocketAddress)}</h2>
 * <p>devh 的 {@code DiscoveryClientResolverFactory#priority()} = <b>6</b>（> DNS 的 5）
 * ⇒ 它会把自己注册进<b>全局</b> {@code NameResolverRegistry} 并抢走默认 scheme
 * ⇒ {@code ManagedChannelBuilder.forAddress(String, int)} 的目标会被拼成
 * {@code discovery:///host:port} ⇒ 去 Nacos 查「host」这个服务 ⇒ 零实例
 * ⇒ 客户端报 {@code UNAVAILABLE: No servers found for host:port}
 * ⇒ <b>长得像「服务端没起来」，极易误判</b>。
 * <p>⇒ 本探针用 {@code InetSocketAddress}（<b>直连地址，不走 NameResolver</b>）
 * ⇒ 完全绕开服务发现 ⇒ 对拍对象永远是<b>那个端口上的那个进程</b>。
 *
 * <h2>用法</h2>
 * <pre>
 *   java -cp &lt;grpc jars&gt; com.rag.hot.parity.HotWireProbe &lt;host&gt; &lt;port&gt; &lt;outFile&gt; [caseFilter]
 * </pre>
 * 输出每行 = {@code CASE|<用例名>|<grpc status 名>|<响应字节 hex>}；
 * gRPC 错误时输出 {@code CASE|<用例名>|ERROR_<CODE>|<description 的 hex>}。
 * 另有一行 {@code DUR|<用例名>|<毫秒>}（<b>耗时</b>）—— 探针比对时<b>只比 CASE 行</b>。
 */
public final class HotWireProbe {

    /** 非 null 时只跑该用例名（诊断模式）。默认 null ⇒ 跑全部 9 条。 */
    static String CASE_FILTER = null;

    /** proto3 varint 编码（int64 / int32 / bool / enum 都走同一条路径）。 */
    static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long v = value;
        while (true) {
            if ((v & ~0x7FL) == 0) {
                out.write((int) v);
                return out.toByteArray();
            }
            out.write(((int) v & 0x7F) | 0x80);
            v >>>= 7;
        }
    }

    /** field tag = (field_number << 3) | wire_type。 */
    static byte[] tag(int fieldNumber, int wireType) {
        return varint(((long) fieldNumber << 3) | (long) wireType);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] all = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, all, off, p.length);
            off += p.length;
        }
        return all;
    }

    /** int32 / int64 / bool / enum 字段（wire_type 0）。 */
    static byte[] varintField(int fieldNumber, long value) {
        return concat(tag(fieldNumber, 0), varint(value));
    }

    /** string 字段（wire_type 2 = length-delimited）。 */
    static byte[] stringField(int fieldNumber, String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        return concat(tag(fieldNumber, 2), varint(raw.length), raw);
    }

    /**
     * {@code GetHotArticlesRequest{ int32 top_k = 1 }}。
     *
     * <p>⚠️ <b>零值不编码</b>：{@code topK == 0} ⇒ 返回<b>空数组</b>（proto3 默认值不上 wire）。
     * <p>⚠️ <b>负值按 64 位符号扩展</b>：{@code topK < 0} ⇒ {@link #varint(long)} 产出 10 字节。
     */
    static byte[] getHotArticlesReq(int topK) {
        if (topK == 0) {
            return new byte[0];
        }
        return varintField(1, topK);
    }

    /** {@code LikeArticleRequest{ article_id=1, user_id=2 }}。 */
    static byte[] likeArticleReq(String articleId, String userId) {
        return concat(stringField(1, articleId), stringField(2, userId));
    }

    /** {@code CommentArticleRequest{ article_id=1, user_id=2 }}。 */
    static byte[] commentArticleReq(String articleId, String userId) {
        return concat(stringField(1, articleId), stringField(2, userId));
    }

    /** {@code CoinArticleRequest{ article_id=1, user_id=2, coin_count=3 }}。 */
    static byte[] coinArticleReq(String articleId, String userId, int coinCount) {
        byte[] b = concat(stringField(1, articleId), stringField(2, userId));
        if (coinCount != 0) {
            b = concat(b, varintField(3, coinCount));
        }
        return b;
    }

    /** {@code ShareArticleRequest{ article_id=1, user_id=2, channel=3 }}。 */
    static byte[] shareArticleReq(String articleId, String userId, String channel) {
        byte[] b = concat(stringField(1, articleId), stringField(2, userId));
        if (channel != null && !channel.isEmpty()) {
            b = concat(b, stringField(3, channel));
        }
        return b;
    }

    /** byte[] marshaller —— 原样进出，不做任何解析。 */
    static final MethodDescriptor.Marshaller<byte[]> BYTES =
            new MethodDescriptor.Marshaller<byte[]>() {
                @Override
                public InputStream stream(byte[] value) {
                    return new java.io.ByteArrayInputStream(value);
                }

                @Override
                public byte[] parse(InputStream stream) {
                    try {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = stream.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        return out.toByteArray();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };

    static MethodDescriptor<byte[], byte[]> method(String rpcName) {
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName("hot.HotService", rpcName))
                .setRequestMarshaller(BYTES)
                .setResponseMarshaller(BYTES)
                .build();
    }

    static String hex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.err.println("usage: HotWireProbe <host> <port> <outFile> [caseFilter]");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String outFile = args[2];
        // ⚠️ **只有传了 caseFilter 才加 deadline** —— 默认路径的读数口径必须与之前逐字一致。
        String caseFilter = args.length == 4 ? args[3] : null;
        CASE_FILTER = caseFilter;
        CallOptions callOptions = caseFilter == null
                ? CallOptions.DEFAULT
                : CallOptions.DEFAULT.withDeadlineAfter(15L, TimeUnit.SECONDS);

        // 🔴 **必须用 InetSocketAddress**：绕开 devh 抢走的全局 NameResolver（见类注释）。
        ManagedChannel channel = NettyChannelBuilder
                .forAddress(new InetSocketAddress(host, port))
                .usePlaintext()
                .maxInboundMessageSize(16 * 1024 * 1024)
                .build();

        try (PrintStream out = new PrintStream(new FileOutputStream(outFile), true, "UTF-8")) {
            // ── 读路径：top_k 的 5 个边界 ───────────────────────────────────
            // ⚠️ **top_k 的边界是本域的 L1 契约**（B 侧 HotRankingQueryService:59-66）：
            //    k <= 0 ⇒ 20；k > 100 ⇒ 100。
            //    ⇒ 0 / -1 两条测「非正数归一」，101 测「上界截断」，20 / 100 测「正常值」。
            call(out, channel, callOptions, "wire_topk_20", "GetHotArticles", getHotArticlesReq(20));
            call(out, channel, callOptions, "wire_topk_100", "GetHotArticles", getHotArticlesReq(100));
            call(out, channel, callOptions, "wire_topk_0", "GetHotArticles", getHotArticlesReq(0));
            call(out, channel, callOptions, "wire_topk_neg1", "GetHotArticles", getHotArticlesReq(-1));
            call(out, channel, callOptions, "wire_topk_101", "GetHotArticles", getHotArticlesReq(101));
            // ── 互动桩：3 × UNIMPLEMENTED + 1 × success=true ───────────────
            call(out, channel, callOptions, "wire_like_article", "LikeArticle",
                    likeArticleReq("probe-wire-1", "user-1"));
            call(out, channel, callOptions, "wire_comment_article", "CommentArticle",
                    commentArticleReq("probe-wire-1", "user-1"));
            call(out, channel, callOptions, "wire_coin_article", "CoinArticle",
                    coinArticleReq("probe-wire-1", "user-1", 2));
            call(out, channel, callOptions, "wire_share_article", "ShareArticle",
                    shareArticleReq("probe-wire-1", "user-1", "wechat"));
        } finally {
            channel.shutdownNow();
        }
    }

    static void call(PrintStream out, ManagedChannel channel, CallOptions callOptions, String caseName,
                     String rpcName, byte[] request) {
        // ⚠️ **过滤在发请求之前** —— 诊断模式只跑指定的那一条。
        if (CASE_FILTER != null && !CASE_FILTER.equals(caseName)) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            byte[] resp = ClientCalls.blockingUnaryCall(
                    channel, method(rpcName), callOptions, request);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            out.printf("CASE|%s|OK|%s%n", caseName, hex(resp));
            out.printf("DUR|%s|%d%n", caseName, ms);
        } catch (StatusRuntimeException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            // ⚠️ **description 的字节也进比对** —— 它承载业务码文案（wire 层反查的载体）
            String desc = e.getStatus().getDescription();
            out.printf("CASE|%s|ERROR_%s|%s%n",
                    caseName, e.getStatus().getCode().name(),
                    hex(desc == null ? new byte[0] : desc.getBytes(StandardCharsets.UTF_8)));
            out.printf("DUR|%s|%d%n", caseName, ms);
        }
    }
}
