package com.rag.user.parity;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <b>user 域 gRPC wire 层对拍探针</b> —— B 侧（RAG · Boot 4）↔ C 侧（RAG-V1 · Boot 3.5）。
 *
 * <h2>为什么不用生成的 pb 代码</h2>
 * <p>两侧 pb 包名<b>不同</b>（B 侧 = {@code com.rag.users.user.proto} · C 侧 = {@code com.rag.proto.user}）
 * ⇒ 若用各自的生成类构造请求 / 解析响应 ⇒ 两侧跑的<b>不是同一段代码</b>
 * ⇒ 「字节一致」的证据会被「两侧实现不同」污染。
 * <p>⇒ 本探针<b>只用原始字节</b>：请求体按 proto3 wire format <b>手工构造</b>，
 * 响应用 {@code byte[]} marshaller <b>原样取出</b>
 * ⇒ ⇒ <b>同一个 jar 打两侧</b> ⇒ 比的就是纯粹的 wire 字节。
 *
 * <h2>为什么不用「proto 文件反序列化成对象再比」</h2>
 * <p>那样比的是「<b>对象语义</b>」，不是 wire：若两侧 proto 字段号不一致，解析器会把对方的多余字段
 * 当 unknown 丢掉 ⇒ <b>契约漂移被解码器吃掉</b> ⇒ 假绿。
 * <p>⇒ 本探针用 <b>schema 驱动的自研解码器</b>：按字段号逐个吐出 {@code f<号>=<值>}，
 * <b>未知字段照样打印</b>（{@code f=unknown(...)}）
 * ⇒ ⇒ 字段号漂移<b>必然显形</b>。
 *
 * <h2>契约（{@code proto/users.proto} · 与 B 侧逐字节一致，只差一行注释）</h2>
 * <pre>
 *   package user;   service UserService {
 *     GetUser(GetUserReq) → GetUserResp              Register(CreateUserReq) → CreateUserResp
 *     Login(LoginReq) → LoginResp                    Logout(LogoutReq) → LogoutResp
 *     UpdateUser(UpdateUserReq) → UpdateUserResp    DeleteUser(DeleteUserReq) → DeleteUserResp
 *     UploadAvatar(UploadAvatarReq) → UploadAvatarResp
 *     GetAvatarHistory(GetAvatarHistoryReq) → GetAvatarHistoryResp
 *     SelectAvatar(SelectAvatarReq) → SelectAvatarResp
 *   }
 * </pre>
 *
 * <h2>⚠️ proto3 默认值不编码 ⇒ 手工构造必须省掉零值字段</h2>
 * <p>例如 {@code LoginResp{status=0, uid=123}} 在 wire 上<b>只有 uid</b>
 * （status=0 是 proto3 默认值 ⇒ 不编码）；{@code LogoutResp{}} 是<b>空消息</b>（0 字节）。
 * ⇒ ⇒ 这类「presence 差异」正是 wire 维度要抓的东西 ⇒ 构造时必须按 proto3 语义省掉零值。
 *
 * <h2>屏蔽规则（只屏蔽「值」，<b>不屏蔽 presence</b>）</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>占位符</th><th>为什么</th></tr>
 *   <tr><td>{@code UserInfo.uid} / {@code CreateUserResp.uid} / {@code LoginResp.uid}</td>
 *       <td>{@code <UID>}</td><td>雪花 ID ⇒ 两侧必然不同</td></tr>
 *   <tr><td>{@code AvatarHistoryItem.id}</td><td>{@code <HISTORY_ID>}</td><td>服务端生成的 opaque id</td></tr>
 *   <tr><td>{@code AvatarHistoryItem.create_time}</td><td>{@code <TIME>}</td><td>写入时刻 ⇒ 必然不同</td></tr>
 * </table>
 * ⇒ <b>presence 仍然参与比对</b>：B 有该字段而 C 没有 ⇒ 打印结果不同 ⇒ <b>照样红</b>。
 * ⇒ 即「屏蔽的只是<b>两侧必然不同的那个值</b>，不是「这个字段存不存在」」。
 *
 * <h2>用法</h2>
 * <pre>
 *   java -cp &lt;grpc jars&gt; com.rag.user.parity.UserWireProbe &lt;host&gt; &lt;port&gt; &lt;outFile&gt; &lt;username&gt;
 * </pre>
 * 输出每行 = {@code CASE|<用例名>|<OK|ERROR_<CODE>>|<规范化后的响应>}。
 * gRPC 出错时 = {@code CASE|<用例名>|ERROR_<CODE>|<description>}。
 * 另有 {@code SELFCHECK|<项>|<OK|FAIL>} 行 —— <b>同侧自洽断言</b>
 * （例如「SelectAvatar 回显的 avatar_url 必须等于 UploadAvatar 时用的 url」）
 * ⇒ 让「两侧 id 不同」这件事<b>不至于让语义判定落空</b>。
 */
public final class UserWireProbe {

    // ==================== proto3 手工编码 ====================

    /** proto3 varint 编码（int64 / int32 / uint32 / uint64 / bool 共用）。 */
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

    /** varint 字段（wire_type 0）；<b>零值省掉</b>（proto3 默认值不编码）。 */
    static byte[] intField(int fieldNumber, long value) {
        if (value == 0L) {
            return new byte[0];
        }
        return concat(tag(fieldNumber, 0), varint(value));
    }

    /** string 字段（wire_type 2）；<b>空串省掉</b>（proto3 默认值不编码）。 */
    static byte[] stringField(int fieldNumber, String value) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        return concat(tag(fieldNumber, 2), varint(raw.length), raw);
    }

    /**
     * map&lt;string,string&gt; 字段 —— protobuf 的 map 编码 =
     * <b>repeated message {1:key, 2:value}</b>（wire_type 2，外层 tag = 字段号）。
     */
    static byte[] mapField(int fieldNumber, Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return new byte[0];
        }
        byte[] out = new byte[0];
        for (Map.Entry<String, String> e : map.entrySet()) {
            byte[] entry = concat(
                    stringField(1, e.getKey()),
                    stringField(2, e.getValue()));
            out = concat(out, concat(tag(fieldNumber, 2), varint(entry.length), entry));
        }
        return out;
    }

    // ==================== 响应解码（schema 驱动 · 未知字段可见） ====================

    /** 一个字段的 wire 语义描述。 */
    static final class Field {
        final String kind;      // varint | bool | string | msg | map
        final String sub;       // kind=msg 时的子 message 名
        final boolean signed;   // varint 是否按有符号解读
        final boolean repeated; // 是否 repeated（顺序有意义 ⇒ 不排序）

        Field(String kind, String sub, boolean signed, boolean repeated) {
            this.kind = kind;
            this.sub = sub;
            this.signed = signed;
            this.repeated = repeated;
        }
    }

    static final Map<String, Map<Integer, Field>> SCHEMA = new HashMap<>();
    static final Map<String, Map<Integer, String>> MASKS = new HashMap<>();

    static void def(String msg, int no, String kind, String sub, boolean signed, boolean repeated) {
        SCHEMA.computeIfAbsent(msg, k -> new LinkedHashMap<>())
                .put(no, new Field(kind, sub, signed, repeated));
    }

    static void mask(String msg, int no, String placeholder) {
        MASKS.computeIfAbsent(msg, k -> new LinkedHashMap<>()).put(no, placeholder);
    }

    static {
        // ⚠️ 这张表是**从两侧逐字节一致的 users.proto 抄下来的**
        //    （B 侧 service/users/user/user-proto/src/main/proto/users.proto 与 C 侧 proto/src/main/proto/users.proto
        //     逐行 diff 只差一行注释 ⇒ message/字段号/字段名/类型全部一致）
        //    ⇒ 它是「契约的第三方副本」⇒ 字段号漂移必然让解码结果不同（而不是被悄悄吞掉）。
        def("UserInfo", 1, "varint", null, true, false);          // int64 uid
        def("UserInfo", 2, "varint", null, false, false);         // uint32 score
        def("UserInfo", 3, "string", null, false, false);         // string username
        def("UserInfo", 4, "string", null, false, false);         // string email
        def("UserInfo", 5, "map", null, false, false);            // map<string,string> extra_info
        def("UserInfo", 6, "varint", null, true, false);          // optional int64 status

        def("GetUserResp", 1, "msg", "UserInfo", false, false);
        def("GetUserResp", 2, "bool", null, false, false);        // bool found

        def("CreateUserResp", 1, "varint", null, true, false);    // int64 uid

        def("LoginResp", 1, "varint", null, true, false);         // int64 uid
        def("LoginResp", 2, "varint", null, false, false);        // uint64 status

        def("LogoutResp", 1, "bool", null, false, false);         // bool success

        def("UpdateUserResp", 1, "msg", "UserInfo", false, false);

        def("AvatarHistoryItem", 1, "string", null, false, false);// string id
        def("AvatarHistoryItem", 2, "string", null, false, false);// string avatar_url
        def("AvatarHistoryItem", 3, "string", null, false, false);// string content_type
        def("AvatarHistoryItem", 4, "varint", null, true, false); // int64 size_bytes
        def("AvatarHistoryItem", 5, "bool", null, false, false);  // bool is_current
        def("AvatarHistoryItem", 6, "string", null, false, false);// string create_time

        def("UploadAvatarResp", 1, "string", null, false, false);
        def("UploadAvatarResp", 2, "msg", "AvatarHistoryItem", false, false);

        def("GetAvatarHistoryResp", 1, "msg", "AvatarHistoryItem", false, true); // repeated

        def("SelectAvatarResp", 1, "string", null, false, false);
        def("SelectAvatarResp", 2, "msg", "AvatarHistoryItem", false, false);

        def("DeleteUserResp", 1, "bool", null, false, false);     // bool success

        // map entry 的内部结构（protobuf 规定：{1:key, 2:value}）
        def("__map_entry__", 1, "string", null, false, false);
        def("__map_entry__", 2, "string", null, false, false);

        // ---- 屏蔽表（只屏蔽值，不屏蔽 presence）----
        mask("UserInfo", 1, "<UID>");
        mask("CreateUserResp", 1, "<UID>");
        mask("LoginResp", 1, "<UID>");
        mask("AvatarHistoryItem", 1, "<HISTORY_ID>");
        mask("AvatarHistoryItem", 6, "<TIME>");
    }

    /** 字节段游标。 */
    static final class Cursor {
        final byte[] data;
        int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        boolean hasMore() {
            return pos < data.length;
        }

        long readVarint() {
            long result = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new IllegalStateException("truncated varint");
                }
                int b = data[pos++] & 0xFF;
                result |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalStateException("varint overflow");
                }
            }
        }

        byte[] readBytes(int len) {
            if (pos + len > data.length) {
                throw new IllegalStateException("truncated length-delimited field");
            }
            byte[] out = new byte[len];
            System.arraycopy(data, pos, out, 0, len);
            pos += len;
            return out;
        }
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    static String hex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 解码一个 message ⇒ 规范化字符串。
     * <ul>
     *   <li>字段按<b>字段号升序</b>输出（protobuf-java 两边都是这个序 ⇒ 不构成差异；排序只为读数稳定）</li>
     *   <li><b>repeated 字段保持原序</b>（列表顺序是语义）</li>
     *   <li><b>map 字段的条目排序</b>（map 的 wire 顺序本就不保证）</li>
     *   <li><b>未知字段照样打印</b>（{@code f=unknown(...)}）⇒ 契约漂移必然显形</li>
     * </ul>
     */
    static String decode(String msgName, byte[] data) {
        Map<Integer, List<String>> byField = new LinkedHashMap<>();
        Cursor c = new Cursor(data);
        while (c.hasMore()) {
            long tag = c.readVarint();
            int fieldNo = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x7);
            Field spec = SCHEMA.getOrDefault(msgName, Map.of()).get(fieldNo);
            String rendered = (spec == null)
                    ? renderUnknown(c, wireType)
                    : renderKnown(c, spec, fieldNo, msgName);
            byField.computeIfAbsent(fieldNo, k -> new ArrayList<>()).add(rendered);
        }

        List<Integer> fieldNos = new ArrayList<>(byField.keySet());
        fieldNos.sort(Comparator.naturalOrder());

        StringBuilder sb = new StringBuilder();
        for (int no : fieldNos) {
            List<String> items = byField.get(no);
            Field spec = SCHEMA.getOrDefault(msgName, Map.of()).get(no);
            boolean isMap = spec != null && "map".equals(spec.kind);
            boolean repeated = spec != null && spec.repeated;
            List<String> ordered = new ArrayList<>(items);
            if (isMap) {
                ordered.sort(Comparator.naturalOrder());
            }
            for (String item : ordered) {
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(item);
            }
            if (!repeated && !isMap && items.size() > 1) {
                // 非 repeated 字段出现多次 ⇒ 契约异常，标出来（不静默吞掉）
                sb.append("[DUP").append(items.size()).append(']');
            }
        }
        return sb.toString();
    }

    static String renderUnknown(Cursor c, int wireType) {
        switch (wireType) {
            case 0: {
                long v = c.readVarint();
                return "f=unknown(varint)=" + v;
            }
            case 1: {
                byte[] b = c.readBytes(8);
                return "f=unknown(fixed64)=" + hex(b);
            }
            case 2: {
                int len = (int) c.readVarint();
                byte[] b = c.readBytes(len);
                return "f=unknown(len)=" + hex(b);
            }
            case 5: {
                byte[] b = c.readBytes(4);
                return "f=unknown(fixed32)=" + hex(b);
            }
            default:
                throw new IllegalStateException("unsupported wire type " + wireType);
        }
    }

    static String renderKnown(Cursor c, Field spec, int fieldNo, String msgName) {
        switch (spec.kind) {
            case "varint": {
                long v = c.readVarint();
                if (spec.signed && (v & 0x8000000000000000L) != 0) {
                    // protobuf 的 10 字节补码 varint ⇒ 有符号解读：
                    // 去掉符号位后减 2^63（不能写 2^64 —— long 字面量放不下）。
                    v = (v & 0x7FFFFFFFFFFFFFFFL) - 0x8000000000000000L;
                }
                String placeholder = MASKS.getOrDefault(msgName, Map.of()).get(fieldNo);
                return "f" + fieldNo + "=" + (placeholder == null ? String.valueOf(v) : placeholder);
            }
            case "bool": {
                long v = c.readVarint();
                return "f" + fieldNo + "=" + (v != 0);
            }
            case "string": {
                int len = (int) c.readVarint();
                byte[] b = c.readBytes(len);
                String s = new String(b, StandardCharsets.UTF_8);
                String placeholder = MASKS.getOrDefault(msgName, Map.of()).get(fieldNo);
                return "f" + fieldNo + "=" + (placeholder == null ? "\"" + escape(s) + "\"" : placeholder);
            }
            case "msg": {
                int len = (int) c.readVarint();
                byte[] b = c.readBytes(len);
                return "f" + fieldNo + "{" + decode(spec.sub, b) + "}";
            }
            case "map": {
                int len = (int) c.readVarint();
                byte[] b = c.readBytes(len);
                return "f" + fieldNo + "{" + decode("__map_entry__", b) + "}";
            }
            default:
                throw new IllegalStateException("unknown kind " + spec.kind);
        }
    }

    // ==================== 原始字节里取易变值（供后续请求使用） ====================

    /** 取某个 varint 字段的原始值（找不到 ⇒ -1）。 */
    static long rawVarintField(byte[] data, int fieldNo) {
        Cursor c = new Cursor(data);
        while (c.hasMore()) {
            long tag = c.readVarint();
            int no = (int) (tag >>> 3);
            int wt = (int) (tag & 0x7);
            if (no == fieldNo && wt == 0) {
                return c.readVarint();
            }
            skip(c, wt);
        }
        return -1L;
    }

    /** 取某个 length-delimited 字段的原始字节（找不到 ⇒ null）。 */
    static byte[] rawBytesField(byte[] data, int fieldNo) {
        Cursor c = new Cursor(data);
        while (c.hasMore()) {
            long tag = c.readVarint();
            int no = (int) (tag >>> 3);
            int wt = (int) (tag & 0x7);
            if (no == fieldNo && wt == 2) {
                int len = (int) c.readVarint();
                return c.readBytes(len);
            }
            skip(c, wt);
        }
        return null;
    }

    /** 取某个 string 字段的原始值（找不到 ⇒ null）。 */
    static String rawStringField(byte[] data, int fieldNo) {
        byte[] b = rawBytesField(data, fieldNo);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    static void skip(Cursor c, int wireType) {
        switch (wireType) {
            case 0: c.readVarint(); break;
            case 1: c.readBytes(8); break;
            case 2: c.readBytes((int) c.readVarint()); break;
            case 5: c.readBytes(4); break;
            default: throw new IllegalStateException("unsupported wire type " + wireType);
        }
    }

    // ==================== gRPC 调用 ====================

    static final MethodDescriptor.Marshaller<byte[]> BYTES =
            new MethodDescriptor.Marshaller<byte[]>() {
                @Override
                public InputStream stream(byte[] value) {
                    return new ByteArrayInputStream(value);
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
                .setFullMethodName(MethodDescriptor.generateFullMethodName("user.UserService", rpcName))
                .setRequestMarshaller(BYTES)
                .setResponseMarshaller(BYTES)
                .build();
    }

    /** 一次调用的结果。 */
    static final class Reply {
        String status;    // OK | ERROR_<CODE>
        String payload;   // 规范化后的响应 / description
        byte[] raw;       // 原始响应字节（取易变值用）
    }

    static Reply call(ManagedChannel channel, CallOptions opts, String rpcName, byte[] request) {
        Reply r = new Reply();
        try {
            byte[] resp = ClientCalls.blockingUnaryCall(channel, method(rpcName), opts, request);
            r.status = "OK";
            r.raw = resp;
            r.payload = decode(responseMsg(rpcName), resp);
        } catch (StatusRuntimeException e) {
            r.status = "ERROR_" + e.getStatus().getCode().name();
            r.raw = new byte[0];
            r.payload = e.getStatus().getDescription() == null
                    ? "" : e.getStatus().getDescription();
        }
        return r;
    }

    /** 响应 message 名（按 rpc 名映射）。 */
    static String responseMsg(String rpcName) {
        switch (rpcName) {
            case "GetUser": return "GetUserResp";
            case "Register": return "CreateUserResp";
            case "Login": return "LoginResp";
            case "Logout": return "LogoutResp";
            case "UpdateUser": return "UpdateUserResp";
            case "UploadAvatar": return "UploadAvatarResp";
            case "GetAvatarHistory": return "GetAvatarHistoryResp";
            case "SelectAvatar": return "SelectAvatarResp";
            case "DeleteUser": return "DeleteUserResp";
            default: return "";
        }
    }

    // ==================== main ====================

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: UserWireProbe <host> <port> <outFile> <username>");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String outFile = args[2];
        String username = args[3];

        String password = "parity-pw-0001";
        String email = username + "@parity.local";

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(16 * 1024 * 1024)
                .build();
        // ⚠️ 每个调用都带 deadline ⇒ 探针**不可能挂死**（挂死 ≠ 结论）。
        //   deadline 只影响服务端 ctx 的剩余预算，不影响响应 payload 字节。
        CallOptions opts = CallOptions.DEFAULT.withDeadlineAfter(20L, TimeUnit.SECONDS);

        try (PrintStream out = new PrintStream(new FileOutputStream(outFile), true, "UTF-8")) {
            // ── 1) Register：拿到本侧的 uid（雪花 ID ⇒ 两侧必然不同 ⇒ 后面按 <UID> 屏蔽）──
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("city", "parity");
            extra.put("tag", "e5");
            byte[] createReq = concat(
                    stringField(1, username),
                    stringField(2, password),
                    stringField(3, email),
                    mapField(4, extra));

            Reply reg = call(channel, opts, "Register", createReq);
            out.printf("CASE|%s|%s|%s%n", "wire_register", reg.status, reg.payload);

            long uid = rawVarintField(reg.raw, 1);
            if (uid <= 0) {
                out.printf("CASE|%s|ERROR_PROBE|register 未拿到 uid ⇒ 后续用例无法进行%n",
                        "wire_register_setup");
                return;
            }

            // ── 2) Register 重复 ⇒ ALREADY_EXISTS ─────────────────────────────────
            Reply dup = call(channel, opts, "Register", createReq);
            out.printf("CASE|%s|%s|%s%n", "wire_register_dup", dup.status, dup.payload);

            // ── 3) GetUser：命中 / 未命中 ────────────────────────────────────────
            Reply getOk = call(channel, opts, "GetUser", intField(1, uid));
            out.printf("CASE|%s|%s|%s%n", "wire_get_user", getOk.status, getOk.payload);

            Reply getMiss = call(channel, opts, "GetUser", intField(1, uid + 1));
            out.printf("CASE|%s|%s|%s%n", "wire_get_user_missing", getMiss.status, getMiss.payload);

            // ── 4) Login：三态（成功 / 密码错 / 用户不存在）────────────────────────
            Reply loginOk = call(channel, opts, "Login",
                    concat(stringField(1, username), stringField(2, password)));
            out.printf("CASE|%s|%s|%s%n", "wire_login_ok", loginOk.status, loginOk.payload);

            Reply loginBad = call(channel, opts, "Login",
                    concat(stringField(1, username), stringField(2, "wrong-password")));
            out.printf("CASE|%s|%s|%s%n", "wire_login_wrong_password", loginBad.status, loginBad.payload);

            Reply loginNone = call(channel, opts, "Login",
                    concat(stringField(1, "no-such-user-" + username), stringField(2, password)));
            out.printf("CASE|%s|%s|%s%n", "wire_login_user_missing", loginNone.status, loginNone.payload);

            // ── 5) UpdateUser ──────────────────────────────────────────────────
            //    ⚠️ **D-004：不排除自己** ⇒ 用**自己的** username 去更新 ⇒ 撞唯一性检查
            //    ⇒ 两侧都返回 ALREADY_EXISTS（这是**继承自 B 侧**的行为，不是 C 侧漂移）。
            //    ⇒ 这一条**钉住**该怪癖；下面再来一条「改成新用户名」覆盖**成功路径**。
            byte[] updateSameNameReq = concat(
                    intField(1, uid),
                    stringField(2, username),
                    stringField(4, email),
                    mapField(5, extra));
            Reply updSame = call(channel, opts, "UpdateUser", updateSameNameReq);
            out.printf("CASE|%s|%s|%s%n", "wire_update_user_same_name", updSame.status, updSame.payload);

            String renamed = username + "_r";
            byte[] updateRenameReq = concat(
                    intField(1, uid),
                    stringField(2, renamed),
                    stringField(4, email),
                    mapField(5, extra));
            Reply updRename = call(channel, opts, "UpdateUser", updateRenameReq);
            out.printf("CASE|%s|%s|%s%n", "wire_update_user_rename", updRename.status, updRename.payload);

            // ── 6) Logout：空 token（INVALID_ARGUMENT）＋ 正常 token（空响应）──────
            Reply logoutEmpty = call(channel, opts, "Logout", new byte[0]);
            out.printf("CASE|%s|%s|%s%n", "wire_logout_empty_token", logoutEmpty.status, logoutEmpty.payload);

            Reply logoutOk = call(channel, opts, "Logout",
                    stringField(1, "parity-token-" + username));
            out.printf("CASE|%s|%s|%s%n", "wire_logout", logoutOk.status, logoutOk.payload);

            // ── 7) Avatar：上传 2 个 ⇒ 历史列表有 2 条（**顺序**必须一致）──────────
            String avatarUrl1 = "https://cdn.parity.local/" + username + "/a1.png";
            String avatarUrl2 = "https://cdn.parity.local/" + username + "/a2.png";

            Reply up1 = call(channel, opts, "UploadAvatar", concat(
                    intField(1, uid),
                    stringField(2, avatarUrl1),
                    stringField(3, "image/png"),
                    intField(4, 1024L)));
            out.printf("CASE|%s|%s|%s%n", "wire_upload_avatar_1", up1.status, up1.payload);

            Reply up2 = call(channel, opts, "UploadAvatar", concat(
                    intField(1, uid),
                    stringField(2, avatarUrl2),
                    stringField(3, "image/jpeg"),
                    intField(4, 2048L)));
            out.printf("CASE|%s|%s|%s%n", "wire_upload_avatar_2", up2.status, up2.payload);

            Reply hist = call(channel, opts, "GetAvatarHistory",
                    concat(intField(1, uid), intField(2, 10L)));
            out.printf("CASE|%s|%s|%s%n", "wire_get_avatar_history", hist.status, hist.payload);

            // ── 8) SelectAvatar：用**本侧自己**的 history_id（两侧 id 必然不同）──────
            byte[] historyMsg = rawBytesField(up1.raw, 2);
            String historyId = historyMsg == null ? null : rawStringField(historyMsg, 1);
            Reply selOk = call(channel, opts, "SelectAvatar",
                    concat(intField(1, uid), stringField(2, historyId)));
            out.printf("CASE|%s|%s|%s%n", "wire_select_avatar", selOk.status, selOk.payload);

            Reply selEmpty = call(channel, opts, "SelectAvatar", intField(1, uid));
            out.printf("CASE|%s|%s|%s%n", "wire_select_avatar_empty_id", selEmpty.status, selEmpty.payload);

            // ── 9) DeleteUser：命中 / 未命中（放在最后）──────────────────────────
            Reply del = call(channel, opts, "DeleteUser", intField(1, uid));
            out.printf("CASE|%s|%s|%s%n", "wire_delete_user", del.status, del.payload);

            Reply delMiss = call(channel, opts, "DeleteUser", intField(1, uid + 1));
            out.printf("CASE|%s|%s|%s%n", "wire_delete_user_missing", delMiss.status, delMiss.payload);

            // ── 10) 同侧自洽断言（**不依赖两侧 id 相同**）──────────────────────────
            //    语义判定不靠「两侧 id 恰好一样」，而靠「每侧自己的因果链自洽」。
            //
            // ⚠️ **断言必须比原始字节，不能比规范化文本**：
            //    规范化文本里 history_id 已被屏蔽成 <HISTORY_ID>
            //    ⇒ 拿原始 id 去 `contains` 规范化文本 ⇒ **恒 false**（2026-11-03 实测踩过：
            //    两侧同时 FAIL，但那是**断言写错**，不是行为差异 ⇒ 改断言，不改被测对象）。
            byte[] selHistoryMsg = rawBytesField(selOk.raw, 2);
            String selHistoryId = selHistoryMsg == null ? null : rawStringField(selHistoryMsg, 1);
            selfCheck(out, "select_echoes_uploaded_url", selOk.payload.contains(avatarUrl1));
            selfCheck(out, "select_history_id_echoes_request",
                    historyId != null && historyId.equals(selHistoryId));
            selfCheck(out, "upload_returns_current_avatar", up1.payload.contains("f5=true"));
            selfCheck(out, "login_ok_has_uid", loginOk.payload.contains("f1="));
            selfCheck(out, "get_user_found_true", getOk.payload.contains("f2=true"));
            selfCheck(out, "logout_response_is_empty",
                    "OK".equals(logoutOk.status) && logoutOk.raw.length == 0);
            selfCheck(out, "history_list_has_two_items",
                    hist.payload.split("f1\\{", -1).length - 1 == 2);
            selfCheck(out, "rename_update_returns_new_username",
                    updRename.payload.contains(renamed));
        } finally {
            channel.shutdownNow();
        }
    }

    static void selfCheck(PrintStream out, String name, boolean ok) {
        out.printf("SELFCHECK|%s|%s%n", name, ok ? "OK" : "FAIL");
    }
}
