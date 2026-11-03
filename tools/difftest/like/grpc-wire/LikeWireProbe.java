package com.rag.like.parity;

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
 * <b>like 域 gRPC wire 层原始字节探针</b> —— 对拍用。
 *
 * <h2>为什么不用生成的 pb 代码</h2>
 * <p>两侧的 pb 包名<b>不同</b>（B 侧 = {@code com.rag.like.like.proto} ·
 * C 侧 = {@code com.rag.proto.like}）⇒ 若用各自的生成类构造请求 / 解析响应，
 * 两侧跑的<b>不是同一段代码</b> ⇒ 「字节一致」的证据会被「两侧实现不同」污染。
 * <p>⇒ 本探针<b>只用原始字节</b>：请求体按 proto3 的 wire format <b>手工构造</b>，
 * 响应用 {@code byte[]} marshaller <b>原样取出</b> ⇒
 * ⇒ <b>同一个 jar 跑两侧</b> ⇒ 比的就是纯粹的 wire 字节。
 *
 * <h2>契约（{@code proto/like.proto} · 与 B 侧逐字节一致）</h2>
 * <pre>
 *   package like;   service LikeService {
 *     LikeAction        (LikeActionReq)        → LikeActionResp
 *     GetLikeCount      (GetLikeCountReq)      → GetLikeCountResp
 *     GetLikeState      (GetLikeStateReq)      → GetLikeStateResp
 *     GetUserLikeList   (GetUserLikeListReq)   → GetUserLikeListResp
 *     GetTargetLikerList(GetTargetLikerListReq)→ GetTargetLikerListResp
 *     GetUserTotalLike  (GetUserTotalLikeReq)  → GetUserTotalLikeResp
 *   }
 *   LikeActionReq  { string target_type=1; string target_id=2; int64 user_id=3;
 *                     ActionType action_type=4; int64 author_id=5; float weight=6;
 *                     string client_ip=7; string promotion_id=8; }
 *   LikeActionResp { bool success=1; string message=2; int64 like_count=3; int64 dislike_count=4; }
 *   GetLikeCountReq{ string target_type=1; repeated string target_ids=2; }
 *   GetLikeStateReq{ string target_type=1; int64 user_id=2; repeated string target_ids=3; }
 *   GetUserLikeListReq { string target_type=1; int64 user_id=2; int64 cursor=3; int32 page_size=4; }
 *   GetTargetLikerListReq { string target_type=1; string target_id=2; int64 cursor=3; int32 page_size=4; }
 *   GetUserTotalLikeReq { int64 user_id=1; }
 * </pre>
 * ⚠️ <b>proto3 默认值不编码</b> ⇒ {@code cursor=0} / {@code author_id=0} /
 * {@code weight=0} / {@code client_ip=""} 在 wire 上<b>不出现</b>
 * ⇒ 构造时<b>必须</b>按 proto3 语义省掉零值字段，否则请求字节与真实客户端不同。
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
 * （本仓已有同款根因注释：{@code service/like/src/test/java/com/rag/like/grpc/LikeGrpcNettyServerRealTest.java}）
 *
 * <h2>用法</h2>
 * <pre>
 *   java -cp <grpc jars> com.rag.like.parity.LikeWireProbe <host> <port> <outFile> [caseFilter]
 * </pre>
 * 输出每行 = {@code CASE|<用例名>|<grpc status 名>|<响应字节 hex>}；
 * gRPC 错误时输出 {@code CASE|<用例名>|ERROR_<CODE>|<description 的 hex>}。
 * 另有一行 {@code DUR|<用例名>|<毫秒>}（<b>耗时</b>）—— 探针比对时<b>只比 CASE 行</b>。
 */
public final class LikeWireProbe {

    /** 非 null 时只跑该用例名（诊断模式）。⚠️ 默认 null ⇒ 跑全部 6 条。 */
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

    /** int64 / int32 / bool / enum 字段（wire_type 0）。 */
    static byte[] varintField(int fieldNumber, long value) {
        return concat(tag(fieldNumber, 0), varint(value));
    }

    /** string 字段（wire_type 2 = length-delimited）。 */
    static byte[] stringField(int fieldNumber, String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        return concat(tag(fieldNumber, 2), varint(raw.length), raw);
    }

    /** message 字段（wire_type 2 = length-delimited）。 */
    static byte[] messageField(int fieldNumber, byte[] payload) {
        return concat(tag(fieldNumber, 2), varint(payload.length), payload);
    }

    /** repeated string：每个元素一个 tag（proto3 的 repeated 语义 = 重复同号字段）。 */
    static byte[] repeatedStringField(int fieldNumber, String... values) {
        byte[] b = new byte[0];
        for (String v : values) {
            b = concat(b, stringField(fieldNumber, v));
        }
        return b;
    }

    /** GetLikeCountReq：{target_type=1, target_ids=2}。 */
    static byte[] getLikeCountReq(String targetType, String... targetIds) {
        return concat(stringField(1, targetType), repeatedStringField(2, targetIds));
    }

    /** GetLikeStateReq：{target_type=1, user_id=2, target_ids=3}。 */
    static byte[] getLikeStateReq(String targetType, long userId, String... targetIds) {
        return concat(stringField(1, targetType),
                varintField(2, userId),
                repeatedStringField(3, targetIds));
    }

    /** GetUserLikeListReq：{target_type=1, user_id=2, cursor=3, page_size=4}；零值省掉。 */
    static byte[] getUserLikeListReq(String targetType, long userId, long cursor, int pageSize) {
        byte[] b = concat(stringField(1, targetType), varintField(2, userId));
        if (cursor != 0L) {
            b = concat(b, varintField(3, cursor));
        }
        if (pageSize != 0) {
            b = concat(b, varintField(4, pageSize));
        }
        return b;
    }

    /** GetTargetLikerListReq：{target_type=1, target_id=2, cursor=3, page_size=4}；零值省掉。 */
    static byte[] getTargetLikerListReq(String targetType, String targetId, long cursor, int pageSize) {
        byte[] b = concat(stringField(1, targetType), stringField(2, targetId));
        if (cursor != 0L) {
            b = concat(b, varintField(3, cursor));
        }
        if (pageSize != 0) {
            b = concat(b, varintField(4, pageSize));
        }
        return b;
    }

    /** GetUserTotalLikeReq：{user_id=1}。 */
    static byte[] getUserTotalLikeReq(long userId) {
        return varintField(1, userId);
    }

    /**
     * LikeActionReq：{target_type=1, target_id=2, user_id=3, action_type=4, author_id=5, …}。
     * ⚠️ {@code author_id=0} 是**默认值** ⇒ proto3 **不编码** ⇒ 线上字节里没有这个字段。
     */
    static byte[] likeActionReq(String targetType, String targetId, long userId, int actionType, long authorId) {
        byte[] b = concat(stringField(1, targetType), stringField(2, targetId), varintField(3, userId));
        if (actionType != 0) {
            b = concat(b, varintField(4, actionType));
        }
        if (authorId != 0L) {
            b = concat(b, varintField(5, authorId));
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
                .setFullMethodName(MethodDescriptor.generateFullMethodName("like.LikeService", rpcName))
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
            System.err.println("usage: LikeWireProbe <host> <port> <outFile> [caseFilter]");
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
            // ── 读路径（5 个查询类 RPC）──────────────────────────────────
            // ⚠️ **target_liker_list 用专属 target（9007199254740995）**：
            //    `likeaction` 往 ZSet 里写的 score = **游标 ID**（`(now << 22) | …`）
            //    ⇒ 读路径反解出的 timestamp = **墙钟毫秒** ⇒ 两侧的 likeaction 发生在不同时刻
            //    ⇒ 毫秒不同 ⇒ **wire 字节必然不同** ⇒ 与对拍无关的**假红**。
            //    ⇒ 用一个**只有种子成员**的专属 target ⇒ timestamp 恒为 413843。
            call(out, channel, callOptions, "wire_get_like_count", "GetLikeCount",
                    getLikeCountReq("article", "9007199254740994"));
            call(out, channel, callOptions, "wire_get_like_state", "GetLikeState",
                    getLikeStateReq("article", 1001L, "9007199254740994"));
            call(out, channel, callOptions, "wire_get_user_like_list", "GetUserLikeList",
                    getUserLikeListReq("article", 1001L, 0L, 10));
            call(out, channel, callOptions, "wire_get_target_liker_list", "GetTargetLikerList",
                    getTargetLikerListReq("article", "9007199254740995", 0L, 10));
            call(out, channel, callOptions, "wire_get_user_total_like", "GetUserTotalLike",
                    getUserTotalLikeReq(1001L));
            // ── 写路径（likeAction）──────────────────────────────────────
            // ⚠️ **用独立的 target（9007199254740996）** —— 主 target 的 like_count
            //    是冻结锚（likeaction=3 / double_failure=4）的基准 ⇒ 这里写它会把锚打乱。
            // ⚠️ **author_id=0** ⇒ 不走 notifyArticleLiked 分支 ⇒ 两侧耗时可比。
            call(out, channel, callOptions, "wire_like_action", "LikeAction",
                    likeActionReq("article", "9007199254740996", 7001L, 1, 0L));
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
