package com.rag.follow.parity;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * <b>follow 域 gRPC wire 层原始字节探针</b> —— 对拍用。
 *
 * <h2>为什么不用生成的 pb 代码</h2>
 * <p>两侧的 pb 包名**不同**（B 侧 = {@code com.rag.follow.follow.rpc.proto} ·
 * C 侧 = {@code com.rag.proto.follow}）⇒ 若用各自的生成类构造请求/解析响应，
 * 两侧跑的**不是同一段代码** ⇒ 「字节一致」的证据会被「两侧实现不同」污染。
 * <p>⇒ 本探针**只用原始字节**：请求体按 proto3 的 wire format **手工构造**，
 * 响应用 {@code byte[]} marshaller **原样取出** ⇒
 * ⇒ **同一个 jar 跑两侧** ⇒ 比的就是纯粹的 wire 字节。
 *
 * <h2>契约（{@code proto/follow.proto} · 与 B 侧逐字节一致）</h2>
 * <pre>
 *   package follow;   service FollowService {
 *     Follow(RelationReq) → BaseResp        Unfollow(RelationReq) → BaseResp
 *     Block(RelationReq)  → BaseResp        Unblock(RelationReq)  → BaseResp
 *     GetFollowList(ListReq)    → UserListResp
 *     GetBlockList(ListReq)     → UserListResp
 *     GetFollowerList(ListReq)  → UserListResp
 *     GetRecommendations(ListReq) → RecommendResp
 *   }
 *   RelationReq { int64 user_id = 1; int64 target_id = 2; }
 *   ListReq     { int64 user_id = 1; int32 offset = 2; int32 limit = 3; }
 *   BaseResp    { int32 code = 1; string msg = 2; }
 *   UserListResp{ int32 code = 1; string msg = 2; repeated int64 user_ids = 3; }
 *   RecommendResp{ int32 code = 1; string msg = 2; repeated RecommendUser users = 3; }
 * </pre>
 * ⚠️ **proto3 默认值不编码** ⇒ {@code offset=0} / {@code limit=0} 在 wire 上**不出现**
 * ⇒ 构造时**必须**按 proto3 语义省掉零值字段，否则请求字节与真实客户端不同。
 *
 * <h2>用法</h2>
 * <pre>
 *   java -cp &lt;grpc jars&gt; com.rag.follow.parity.FollowWireProbe &lt;host&gt; &lt;port&gt; &lt;outFile&gt; [caseFilter]
 * </pre>
 * 输出每行 = {@code CASE|<用例名>|<grpc status 名>|<响应字节 hex>}。
 * gRPC 错误时输出 {@code CASE|<用例名>|ERROR_<CODE>|<description 的 hex>}。
 *
 * <h3>⚠️ 第 4 个参数（{@code caseFilter}）= 「只跑指定用例 ＋ 给每次调用加 15s deadline」</h3>
 * <p>用于<b>超时场景的分层诊断</b>：下游挂起时，服务端要等到 2s 预算耗尽才回包
 * ⇒ 探针<b>必须自带 deadline</b>，否则会无限期挂着（探针挂死 ≠ 结论）。
 * <p>⚠️ <b>加 deadline 会让 grpc-java 多发一个 {@code grpc-timeout} 头</b>
 * ⇒ 它<b>只影响服务端 ctx 的剩余预算</b>，不影响<b>响应 payload 字节</b>
 * ⇒ ⇒ 正常用例（毫秒级返回）加了 deadline 后<b>逐字节读数不变</b>
 * ⇒ ⇒ 默认路径（不传第 4 个参数）<b>不加</b> deadline ⇒ 保持原读数口径。
 */
public final class FollowWireProbe {

    /** 非 null 时只跑该用例名（诊断模式）。⚠️ 默认 null ⇒ 跑全部 13 条。 */
    static String CASE_FILTER = null;

    /** proto3 varint 编码（int64 / int32 走同一条路径）。 */
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

    /** field tag = (field_number << 3) | wire_type；wire_type 0 = varint。 */
    static byte[] tag(int fieldNumber) {
        return varint(((long) fieldNumber << 3) | 0L);
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

    /** int64 字段（wire_type 0）。 */
    static byte[] int64Field(int fieldNumber, long value) {
        return concat(tag(fieldNumber), varint(value));
    }

    /** int32 字段（wire_type 0）。⚠️ 负数按 protobuf 的 10 字节补码 varint 编码。 */
    static byte[] int32Field(int fieldNumber, int value) {
        return concat(tag(fieldNumber), varint(value));
    }

    /** RelationReq：{user_id=1, target_id=2}；**零值省掉**（proto3 默认值不编码）。 */
    static byte[] relationReq(long userId, long targetId) {
        byte[] b = new byte[0];
        if (userId != 0L) {
            b = concat(b, int64Field(1, userId));
        }
        if (targetId != 0L) {
            b = concat(b, int64Field(2, targetId));
        }
        return b;
    }

    /** ListReq：{user_id=1, offset=2, limit=3}；**零值省掉**。 */
    static byte[] listReq(long userId, int offset, int limit) {
        byte[] b = new byte[0];
        if (userId != 0L) {
            b = concat(b, int64Field(1, userId));
        }
        if (offset != 0) {
            b = concat(b, int32Field(2, offset));
        }
        if (limit != 0) {
            b = concat(b, int32Field(3, limit));
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
                .setFullMethodName(MethodDescriptor.generateFullMethodName("follow.FollowService", rpcName))
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
            System.err.println("usage: FollowWireProbe <host> <port> <outFile> [caseFilter]");
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

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(16 * 1024 * 1024)
                .build();

        try (PrintStream out = new PrintStream(new FileOutputStream(outFile), true, "UTF-8")) {
            // ── 读路径（4 个 list 类 RPC）──────────────────────────────────
            call(out, channel, callOptions, "wire_follow_list", "GetFollowList", listReq(1001L, 0, 0));
            call(out, channel, callOptions, "wire_block_list", "GetBlockList", listReq(1001L, 0, 0));
            call(out, channel, callOptions, "wire_follower_list", "GetFollowerList", listReq(1001L, 0, 0));
            call(out, channel, callOptions, "wire_recommendations", "GetRecommendations", listReq(1001L, 0, 0));
            // ── 读路径边界（offset / limit 归一化）─────────────────────────
            call(out, channel, callOptions, "wire_follow_list_offset_neg", "GetFollowList", listReq(1001L, -1, 0));
            call(out, channel, callOptions, "wire_follow_list_limit_max", "GetFollowList", listReq(1001L, 0, 1000));
            call(out, channel, callOptions, "wire_follow_list_user_missing", "GetFollowList", listReq(999999L, 0, 0));
            // ── 写路径（4 个 relation 类 RPC）──────────────────────────────
            call(out, channel, callOptions, "wire_follow_self", "Follow", relationReq(1001L, 1001L));
            call(out, channel, callOptions, "wire_follow_target_notfound", "Follow", relationReq(1001L, 999999L));
            call(out, channel, callOptions, "wire_follow_new", "Follow", relationReq(1001L, 1004L));
            call(out, channel, callOptions, "wire_unfollow", "Unfollow", relationReq(1001L, 1003L));
            call(out, channel, callOptions, "wire_block", "Block", relationReq(1001L, 1006L));
            call(out, channel, callOptions, "wire_unblock", "Unblock", relationReq(1001L, 1005L));
        } finally {
            channel.shutdownNow();
        }
    }

    static void call(PrintStream out, ManagedChannel channel, CallOptions callOptions, String caseName,
                     String rpcName, byte[] request) {
        // ⚠️ **过滤在发请求之前** —— 诊断模式只跑指定的那一条，避免把 2s 超时的等待
        //    摊到 13 条用例上（每条都要等满服务端预算）。
        if (CASE_FILTER != null && !CASE_FILTER.equals(caseName)) {
            return;
        }
        try {
            byte[] resp = ClientCalls.blockingUnaryCall(
                    channel, method(rpcName), callOptions, request);
            out.printf("CASE|%s|OK|%s%n", caseName, hex(resp));
        } catch (StatusRuntimeException e) {
            // ⚠️ **description 的字节也进比对** —— 它承载业务码文案（wire 层反查的载体）
            String desc = e.getStatus().getDescription();
            out.printf("CASE|%s|ERROR_%s|%s%n",
                    caseName, e.getStatus().getCode().name(),
                    hex(desc == null ? new byte[0] : desc.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
