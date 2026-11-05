package com.rag.favorite.parity;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * <b>favorite 域 gRPC 级可观测契约探针</b> —— 双侧活体对拍用（rpc 对 rpc）。
 *
 * <h2>它测什么</h2>
 * <p>「帧层之上、消息层之下」那一层<b>客户端可观测</b>的契约：
 * <ul>
 *   <li>gRPC status code（标准码 0-16）</li>
 *   <li>status description（= 本域业务文案的载体，<b>业务码靠它反查</b>）</li>
 *   <li>响应消息的 protobuf 字节（<b>逐字节</b>）</li>
 *   <li>响应头 / trailer 的 metadata key 集合</li>
 * </ul>
 * <p>⇒ 这四样合起来 = 「客户端拿到的 favorite 契约」的全貌。
 *
 * <h2>为什么不用生成的 pb 代码（🔴 关键）</h2>
 * <p>两侧的 pb 包名<b>不同</b>（B 侧 = {@code com.rag.favorite.favorite.proto} ·
 * C 侧 = {@code com.rag.proto.favorite}）⇒ 若用各自的生成类构造请求 / 解析响应，
 * 两侧跑的<b>不是同一段代码</b> ⇒ 「字节一致」的证据会被「两侧实现不同」污染。
 * <p>⇒ 本探针<b>只用原始字节</b>：请求体按 proto3 wire format <b>手工构造</b>，
 * 响应用 {@code byte[]} marshaller <b>原样取出</b> ⇒
 * ⇒ <b>同一个 jar 跑两侧</b> ⇒ 比的就是纯粹的 wire 字节。
 *
 * <h2>契约（{@code proto/src/main/proto/favorite.proto} · 与 B 侧逐字节一致）</h2>
 * <pre>
 *   package favorite;   service FavoriteService {
 *     CreateFavoriteFolder  (CreateFavoriteFolderReq)  → CreateFavoriteFolderResp
 *     CreateFavorite        (CreateFavoriteReq)        → CreateFavoriteResp
 *     DeleteFavoriteFolder  (DeleteFavoriteFolderReq)  → DeleteFavoriteFolderResp
 *     DeleteFavorite        (DeleteFavoriteReq)        → DeleteFavoriteResp
 *     UpdateFavoriteFolderName (UpdateFavoriteFolderNameReq) → UpdateFavoriteFolderNameResp
 *     GetFavoriteFolders    (GetFavoriteFoldersReq)    → GetFavoriteFoldersResp
 *     GetFavoritesByFolder  (GetFavoritesByFolderReq)  → GetFavoritesByFolderResp
 *   }
 *   CreateFavoriteFolderReq   { int64 user_id=1; string name=2; }
 *   CreateFavoriteReq         { int64 user_id=1; int64 folder_id=2; string target_id=3;
 *                               string target_type=4; string title=5; string cover=6; }
 *   DeleteFavoriteFolderReq   { int64 user_id=1; int64 folder_id=2; }
 *   DeleteFavoriteReq         { int64 user_id=1; int64 favorite_id=2; }
 *   UpdateFavoriteFolderNameReq { int64 user_id=1; int64 folder_id=2; string name=3; }
 *   GetFavoriteFoldersReq     { int64 user_id=1; }
 *   GetFavoritesByFolderReq   { int64 user_id=1; int64 folder_id=2; }
 * </pre>
 * ⚠️ <b>proto3 默认值不编码</b> ⇒ {@code user_id=0} / {@code folder_id=0} /
 * {@code name=""} / {@code target_id=""} 在 wire 上<b>不出现</b>
 * ⇒ 构造时<b>必须</b>按 proto3 语义省掉零值字段，否则请求字节与真实客户端不同。
 * <br>⚠️ 但 {@code "   "}（纯空白）<b>不是</b>默认值 ⇒ <b>必须编码</b>
 * ⇒ 「名称为空白」这条用例的请求体与「名称为空」那条<b>不同</b>。
 *
 * <h2>🔴 为什么必须用 {@code NettyChannelBuilder.forAddress(InetSocketAddress)}</h2>
 * <p>devh 的 {@code DiscoveryClientResolverFactory#priority()} = <b>6</b>（&gt; DNS 的 5）
 * ⇒ 它会把自己注册进<b>全局</b> {@code NameResolverRegistry} 并抢走默认 scheme
 * ⇒ {@code forAddress(String, int)} 的目标会被拼成 {@code discovery:///host:port}
 * ⇒ 去 Nacos 查「host」这个服务 ⇒ 零实例
 * ⇒ 客户端报 {@code UNAVAILABLE: No servers found for host:port}
 * ⇒ <b>长得像「服务端没起来」，极易误判</b>。
 * <p>⇒ 本探针用 {@code InetSocketAddress}（<b>直连地址，不走 NameResolver</b>）
 * ⇒ 完全绕开服务发现 ⇒ 对拍对象永远是<b>那个端口上的那个进程</b>。
 * （本仓同款根因注释：{@code service/like/src/test/java/com/rag/like/grpc/LikeGrpcNettyServerRealTest.java:79-99}）
 *
 * <h2>🔴 为什么 fullMethodName <b>不带前导斜杠</b></h2>
 * <p>grpc-java 的 {@code MethodDescriptor.generateFullMethodName(service, method)}
 * = {@code service + "/" + method}（<b>不</b>补前导斜杠），
 * 而 netty 客户端写 {@code :path} 时<b>自己补一个 "/"</b>
 * ⇒ 线上真实路径恒为 {@code /favorite.FavoriteService/DoesNotExist}。
 * <p>⇒ 若手写成 {@code "/favorite.FavoriteService/DoesNotExist"}，线上路径就成了
 * <b>双斜杠</b> {@code //favorite.…} ⇒ 而 grpc-go v1.81.1 {@code server.go:1826}
 * <b>只剥掉一个</b>前导 {@code "/"} ⇒ 它切出的 service 名 = {@code "/favorite.FavoriteService"}
 * ⇒ 查注册表必然落空 ⇒ 误落到「unknown service」分支而不是「unknown method」分支
 * ⇒ <b>措辞被探针自己污染</b>（B 侧 2026-10-25 第十四轮实测钉死）。
 *
 * <h2>用法</h2>
 * <pre>
 *   java -cp &lt;grpc jars&gt; com.rag.favorite.parity.FavoriteWireProbe \
 *        &lt;host&gt; &lt;port&gt; &lt;outFile&gt; [caseFilter]
 * </pre>
 * 输出每行 = {@code <case_id>\t<code=…|desc=…|body=…|rhdr=…|rtrl=…>}，
 * 格式与 B 侧 {@code tools/grpc-contract-probe/favorite/expected.txt} 的
 * <b>value 列逐字同形</b> ⇒ 可以直接与那份冻结锚比对。
 */
public final class FavoriteWireProbe {

    /** 非 null 时只跑该用例名（诊断模式）。⚠️ 默认 null ⇒ 跑全部 36 条。 */
    static String CASE_FILTER = null;

    /** 单次调用的超时上限（秒）。 */
    static final int DEADLINE_SECONDS = 20;

    /** metadata 里被滤掉的命名空间前缀（gRPC 协议 / 传输库产生，不是本域行为）。 */
    static final String GRPC_NAMESPACE_PREFIX = "grpc-";

    // ══════════════════════════ proto3 手工编码 ══════════════════════════

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

    /** field tag = (field_number &lt;&lt; 3) | wire_type。 */
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

    /**
     * int64 / int32 / bool / enum 字段（wire_type 0）。
     *
     * <p>⚠️ <b>零值省掉</b> —— proto3 默认值不上线（B 侧用 protobuf builder 的
     * {@code setUserId(0)} 同样不上线 ⇒ 两边字节同形）。
     */
    static byte[] varintField(int fieldNumber, long value) {
        if (value == 0L) {
            return new byte[0];
        }
        return concat(tag(fieldNumber, 0), varint(value));
    }

    /**
     * string 字段（wire_type 2 = length-delimited）。
     *
     * <p>⚠️ <b>空串省掉</b>（proto3 默认值不上线），
     * 但 <b>纯空白（"   "）不是默认值</b> ⇒ 必须编码。
     */
    static byte[] stringField(int fieldNumber, String value) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        return concat(tag(fieldNumber, 2), varint(raw.length), raw);
    }

    // ══════════════════════════ 请求体构造（与 proto 字段号逐条对应）══════════

    /** CreateFavoriteFolderReq：{user_id=1, name=2}。 */
    static byte[] createFavoriteFolderReq(long userId, String name) {
        return concat(varintField(1, userId), stringField(2, name));
    }

    /** CreateFavoriteReq：{user_id=1, folder_id=2, target_id=3, target_type=4}（title/cover 本矩阵不传）。 */
    static byte[] createFavoriteReq(long userId, long folderId, String targetId, String targetType) {
        return concat(varintField(1, userId),
                varintField(2, folderId),
                stringField(3, targetId),
                stringField(4, targetType));
    }

    /** DeleteFavoriteFolderReq：{user_id=1, folder_id=2}。 */
    static byte[] deleteFavoriteFolderReq(long userId, long folderId) {
        return concat(varintField(1, userId), varintField(2, folderId));
    }

    /** DeleteFavoriteReq：{user_id=1, favorite_id=2}。 */
    static byte[] deleteFavoriteReq(long userId, long favoriteId) {
        return concat(varintField(1, userId), varintField(2, favoriteId));
    }

    /** UpdateFavoriteFolderNameReq：{user_id=1, folder_id=2, name=3}。 */
    static byte[] updateFavoriteFolderNameReq(long userId, long folderId, String name) {
        return concat(varintField(1, userId), varintField(2, folderId), stringField(3, name));
    }

    /** GetFavoriteFoldersReq：{user_id=1}。 */
    static byte[] getFavoriteFoldersReq(long userId) {
        return varintField(1, userId);
    }

    /** GetFavoritesByFolderReq：{user_id=1, folder_id=2}。 */
    static byte[] getFavoritesByFolderReq(long userId, long folderId) {
        return concat(varintField(1, userId), varintField(2, folderId));
    }

    // ══════════════════════════ 矩阵 ══════════════════════════

    /**
     * 一个用例。
     *
     * @param id          case id（与 B 侧冻结锚逐字同形 ⇒ 可直接比对）
     * @param fullMethod  gRPC 全方法名（<b>不带前导斜杠</b>）
     * @param request     请求体原始字节
     * @param fullHeaders true = 记录<b>完整</b>头 key 集合（不过滤 {@code grpc-*}）
     */
    record Case(String id, String fullMethod, byte[] request, boolean fullHeaders) {
    }

    /** favorite 域的服务全名（proto package = {@code favorite}）。 */
    static final String SERVICE = "favorite.FavoriteService";

    static String rpc(String method) {
        return MethodDescriptor.generateFullMethodName(SERVICE, method);
    }

    /**
     * 请求矩阵 —— <b>与 B 侧 {@code FavoriteGrpcContractParityTest#matrix()} 逐条同形</b>
     * （case id · 顺序 · 请求参数全部对齐；每个 case 的期望出口都标了 Go 出处行号）。
     *
     * <p>⚠️ <b>顺序即契约</b>：只读用例全部在前，写入用例全部在后
     * ⇒ 「读同一行的用例」必须排在「写同一行的用例」之前
     * （c33 会改名 folder 9001 ⇒ 推后 updated_at ⇒ c11/c17/c30 的冻结值会变）。
     */
    static List<Case> matrix() {
        List<Case> cs = new ArrayList<>();

        // ─────────── 1. CreateFavoriteFolder（Go: create_favorite_folder_logic.go:37-118）───────────
        // :51-57  userId &lt;= 0 ⇒ InvalidArgument / 5003
        cs.add(new Case("c01_create_folder_uid0", rpc("CreateFavoriteFolder"),
                createFavoriteFolderReq(0L, "X"), false));
        // :59-66  name 规范化后为空 ⇒ InvalidArgument / 5202
        cs.add(new Case("c02_create_folder_name_empty", rpc("CreateFavoriteFolder"),
                createFavoriteFolderReq(1001L, ""), false));
        // :59-66  normalizeFolderName = TrimSpace ⇒ 纯空白也空 ⇒ 5202
        cs.add(new Case("c03_create_folder_name_blank", rpc("CreateFavoriteFolder"),
                createFavoriteFolderReq(1001L, "   "), false));
        // :68-73  ensureUserExists ⇒ user-rpc 不在 ⇒ Go helper.go:107 ⇒ Internal / 5001
        cs.add(new Case("c04_create_folder_user_rpc_absent", rpc("CreateFavoriteFolder"),
                createFavoriteFolderReq(1001L, "NewFolder"), false));

        // ─────────── 2. CreateFavorite（Go: create_favorite_logic.go:38-169）───────────
        // :54-60  userId &lt;= 0 || folderId &lt;= 0 ⇒ InvalidArgument / 5003
        cs.add(new Case("c05_create_item_uid0", rpc("CreateFavorite"),
                createFavoriteReq(0L, 9001L, "t-9", "post"), false));
        cs.add(new Case("c06_create_item_folder0", rpc("CreateFavorite"),
                createFavoriteReq(1001L, 0L, "t-9", "post"), false));
        // :62-70  target_id 空 ⇒ InvalidArgument / 5303
        cs.add(new Case("c07_create_item_target_empty", rpc("CreateFavorite"),
                createFavoriteReq(1001L, 9001L, "", "post"), false));
        // :62-70  target_type 规范化后为空 ⇒ 5303
        cs.add(new Case("c08_create_item_target_type_blank", rpc("CreateFavorite"),
                createFavoriteReq(1001L, 9001L, "t-9", "   "), false));
        // :72-77  ensureUserExists ⇒ user-rpc 不在 ⇒ Internal / 5001
        cs.add(new Case("c09_create_item_user_rpc_absent", rpc("CreateFavorite"),
                createFavoriteReq(1001L, 9001L, "t-9", "post"), false));

        // ─────────── 3. GetFavoriteFolders（Go: get_favorite_folders_logic.go:34-69）───────────
        // :45-51  userId &lt;= 0 ⇒ InvalidArgument / 5003
        cs.add(new Case("c10_list_folders_uid0", rpc("GetFavoriteFolders"),
                getFavoriteFoldersReq(0L), false));
        // :53-60  正常读 ⇒ 响应字节应逐字节一致（seed 的时间列写死 ⇒ UnixMilli 相同）
        cs.add(new Case("c11_list_folders_ok", rpc("GetFavoriteFolders"),
                getFavoriteFoldersReq(1001L), false));
        cs.add(new Case("c12_list_folders_empty", rpc("GetFavoriteFolders"),
                getFavoriteFoldersReq(4242L), false));

        // ─────────── 4. GetFavoritesByFolder（Go: get_favorites_by_folder_logic.go:36-97）───────────
        // :50-56  userId &lt;= 0 || folderId &lt;= 0 ⇒ InvalidArgument / 5003
        cs.add(new Case("c13_list_items_uid0", rpc("GetFavoritesByFolder"),
                getFavoritesByFolderReq(0L, 9001L), false));
        cs.add(new Case("c14_list_items_folder0", rpc("GetFavoritesByFolder"),
                getFavoritesByFolderReq(1001L, 0L), false));
        // :60-67  收藏夹不存在 ⇒ NotFound / 5201
        cs.add(new Case("c15_list_items_notfound", rpc("GetFavoritesByFolder"),
                getFavoritesByFolderReq(1001L, 9999L), false));
        // :73-79  归属不符 ⇒ PermissionDenied / 5005
        cs.add(new Case("c16_list_items_forbidden", rpc("GetFavoritesByFolder"),
                getFavoritesByFolderReq(2002L, 9001L), false));
        // :81-88  正常读 ⇒ 字节应一致
        cs.add(new Case("c17_list_items_ok", rpc("GetFavoritesByFolder"),
                getFavoritesByFolderReq(1001L, 9001L), false));
        cs.add(new Case("c18_list_items_empty", rpc("GetFavoritesByFolder"),
                getFavoritesByFolderReq(1001L, 9002L), false));

        // ─────────── 5. DeleteFavoriteFolder（Go: delete_favorite_folder_logic.go:36-95）───────────
        // :50-56  参数 ⇒ InvalidArgument / 5003
        cs.add(new Case("c19_delete_folder_uid0", rpc("DeleteFavoriteFolder"),
                deleteFavoriteFolderReq(0L, 0L), false));
        // :60-67  不存在 ⇒ NotFound / 5201
        cs.add(new Case("c20_delete_folder_notfound", rpc("DeleteFavoriteFolder"),
                deleteFavoriteFolderReq(1001L, 9999L), false));
        // :73-79  归属不符 ⇒ PermissionDenied / 5005
        cs.add(new Case("c21_delete_folder_forbidden", rpc("DeleteFavoriteFolder"),
                deleteFavoriteFolderReq(2002L, 9001L), false));

        // ─────────── 6. DeleteFavorite（Go: delete_favorite_logic.go:36-95）───────────
        // :50-56  参数 ⇒ InvalidArgument / 5003
        cs.add(new Case("c22_delete_item_uid0", rpc("DeleteFavorite"),
                deleteFavoriteReq(0L, 0L), false));
        // :60-67  不存在 ⇒ NotFound / 5301
        cs.add(new Case("c23_delete_item_notfound", rpc("DeleteFavorite"),
                deleteFavoriteReq(1001L, 9999L), false));
        // :73-79  归属不符 ⇒ PermissionDenied / 5005
        cs.add(new Case("c24_delete_item_forbidden", rpc("DeleteFavorite"),
                deleteFavoriteReq(2002L, 7001L), false));

        // ─────────── 7. UpdateFavoriteFolderName（Go: update_favorite_folder_name_logic.go:36-126）───────────
        // :51-57  参数 ⇒ InvalidArgument / 5003
        cs.add(new Case("c25_update_folder_uid0", rpc("UpdateFavoriteFolderName"),
                updateFavoriteFolderNameReq(0L, 0L, "Z"), false));
        // :59-66  name 空 ⇒ InvalidArgument / 5202
        cs.add(new Case("c26_update_folder_name_empty", rpc("UpdateFavoriteFolderName"),
                updateFavoriteFolderNameReq(1001L, 9001L, ""), false));
        // :68-75  不存在 ⇒ NotFound / 5201
        cs.add(new Case("c27_update_folder_notfound", rpc("UpdateFavoriteFolderName"),
                updateFavoriteFolderNameReq(1001L, 9999L, "Z"), false));
        // :83-89  归属不符 ⇒ PermissionDenied / 5005
        cs.add(new Case("c28_update_folder_forbidden", rpc("UpdateFavoriteFolderName"),
                updateFavoriteFolderNameReq(2002L, 9001L, "Z"), false));
        // :91-95  同名 ⇒ 直接成功、**不写库**（updated_at 不推进）
        cs.add(new Case("c29_update_folder_same_name", rpc("UpdateFavoriteFolderName"),
                updateFavoriteFolderNameReq(1001L, 9001L, "Alpha"), false));

        // ═══════════════ 写入用例（全部在只读用例之后）═══════════════
        // 🔴 **c30 必须排在 c33 之前**（c33 会改名 folder 9001 ⇒ 推后 updated_at
        //    ⇒ c30 读到的字节每次都不同 ⇒ 冻结值失效 ⇒ 假红）。
        cs.add(new Case("c30_response_header_surface", rpc("GetFavoriteFolders"),
                getFavoriteFoldersReq(1001L), true));

        // 正常删 ⇒ success=true
        cs.add(new Case("c31_delete_folder_ok", rpc("DeleteFavoriteFolder"),
                deleteFavoriteFolderReq(1001L, 9002L), false));
        // 正常删 ⇒ success=true
        cs.add(new Case("c32_delete_item_ok", rpc("DeleteFavorite"),
                deleteFavoriteReq(1001L, 7001L), false));
        // 改名字 ⇒ success=true
        cs.add(new Case("c33_update_folder_ok", rpc("UpdateFavoriteFolderName"),
                updateFavoriteFolderNameReq(1001L, 9001L, "Alpha2"), false));

        // ═══════════════ 服务面（框架层，非本域行为）═══════════════
        // go-zero 的 RpcServerConf.Health 默认 true ⇒ 真源注册 grpc.health.v1.Health
        cs.add(new Case("c34_health_check", "grpc.health.v1.Health/Check", new byte[0], false));
        // 未注册的**方法**（服务名用真源的 favorite.FavoriteService）
        cs.add(new Case("c35_unknown_method", rpc("DoesNotExist"), new byte[0], false));
        // 未注册的**服务**
        cs.add(new Case("c36_unknown_service", "favorite.NoSuchService/DoesNotExist", new byte[0], false));

        return cs;
    }

    // ══════════════════════════ gRPC 客户端 ══════════════════════════

    /** byte[] marshaller —— 原样进出，不做任何解析。 */
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

    static MethodDescriptor<byte[], byte[]> descriptor(String fullMethodName) {
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(fullMethodName)
                .setRequestMarshaller(BYTES)
                .setResponseMarshaller(BYTES)
                .build();
    }

    /** 抓响应头与 trailer 的 metadata key 集合。 */
    static final class CapturingInterceptor implements ClientInterceptor {
        private Metadata responseHeaders;
        private Metadata trailers;

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                        @Override
                        public void onHeaders(Metadata headers) {
                            responseHeaders = headers;
                            super.onHeaders(headers);
                        }

                        @Override
                        public void onClose(Status status, Metadata trailers) {
                            CapturingInterceptor.this.trailers = trailers;
                            super.onClose(status, trailers);
                        }
                    }, headers);
                }
            };
        }
    }

    /**
     * metadata 的 key（+ value）集合。
     *
     * @param full true = 不过滤 {@code grpc-*}；false = 只保留<b>非</b> grpc-* 的 key
     */
    static String keys(Metadata metadata, boolean full) {
        if (metadata == null || metadata.keys().isEmpty()) {
            return "-";
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String key : metadata.keys()) {
            if (!full && key.startsWith(GRPC_NAMESPACE_PREFIX)) {
                continue;
            }
            sorted.add(key + "=" + valuesOf(metadata, key));
        }
        if (sorted.isEmpty()) {
            return "-";
        }
        return String.join(",", sorted);
    }

    /**
     * 取某个 key 的全部值（用 ASCII marshaller）。
     *
     * <p>⚠️ 二进制 key（如 {@code grpc-status-details-bin}）用 ASCII marshaller 取会抛
     * ⇒ 本探针**不静默跳过**，而是标记成 {@code &lt;binary&gt;} ⇒
     * 出现即说明该 key 参与了本 case 的观测面（要如实进比对，不能被吃掉）。
     */
    static String valuesOf(Metadata metadata, String key) {
        try {
            Metadata.Key<String> k = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
            return String.join("/", metadata.getAll(k));
        } catch (RuntimeException e) {
            return "<binary>";
        }
    }

    /** 渲染 —— 与 B 侧 {@code FavoriteGrpcContractParityTest#render} 同形。 */
    static String render(Status status, byte[] response, CapturingInterceptor capture, boolean fullHeaders) {
        StringBuilder sb = new StringBuilder();
        sb.append("code=").append(status.getCode().name());
        String desc = status.getDescription();
        sb.append("|desc=").append((desc == null || desc.isEmpty()) ? "-" : desc);
        sb.append("|body=").append(response == null ? "-" : "hex:" + hex(response));
        sb.append("|rhdr=").append(keys(capture.responseHeaders, fullHeaders));
        sb.append("|rtrl=").append(keys(capture.trailers, fullHeaders));
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

    /** 观测单个用例。 */
    static String observe(Case c, ManagedChannel rawChannel) {
        CapturingInterceptor capture = new CapturingInterceptor();
        Channel channel = ClientInterceptors.intercept(rawChannel, capture);
        CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS);
        try {
            byte[] resp = ClientCalls.blockingUnaryCall(channel, descriptor(c.fullMethod()), options, c.request());
            return render(Status.OK, resp, capture, c.fullHeaders());
        } catch (StatusRuntimeException e) {
            return render(e.getStatus(), null, capture, c.fullHeaders());
        } catch (RuntimeException e) {
            // 非 gRPC 状态异常（例如连接层失败）⇒ 落成 UNKNOWN + 异常类名，
            // ⇒ **绝不吞掉**：两侧出现不同的异常类型本身就是差异。
            return render(Status.UNKNOWN.withDescription(e.getClass().getSimpleName() + ": " + e.getMessage()),
                    null, capture, c.fullHeaders());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.err.println("usage: FavoriteWireProbe <host> <port> <outFile> [caseFilter]");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String outFile = args[2];
        CASE_FILTER = args.length == 4 ? args[3] : null;

        // 🔴 **必须用 InetSocketAddress**：绕开 devh 抢走的全局 NameResolver（见类注释）。
        ManagedChannel channel = NettyChannelBuilder
                .forAddress(new InetSocketAddress(host, port))
                .usePlaintext()
                .maxInboundMessageSize(16 * 1024 * 1024)
                .build();

        try (PrintStream out = new PrintStream(new FileOutputStream(outFile), true, "UTF-8")) {
            for (Case c : matrix()) {
                if (CASE_FILTER != null && !CASE_FILTER.equals(c.id())) {
                    continue;
                }
                out.print(c.id());
                out.print('\t');
                out.println(observe(c, channel));
            }
        } finally {
            channel.shutdownNow();
        }
    }
}
