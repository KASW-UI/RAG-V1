package com.rag.admin.grpc;

import com.rag.admin.AdminServiceApplication;
import com.rag.admin.domain.service.AdminDomainService;
import com.rag.admin.http.NacosStubConfiguration;
import com.rag.common.security.PasswordUtil;
import com.rag.proto.admin.BanUserReq;
import com.rag.proto.admin.CreateAdminReq;
import com.rag.proto.admin.CreateInviteCodeReq;
import com.rag.proto.admin.DeleteUserReq;
import com.rag.proto.admin.GetSelfReq;
import com.rag.proto.admin.GetUserReq;
import com.rag.proto.admin.LoginReq;
import com.rag.proto.admin.LogoutReq;
import com.rag.proto.admin.ResetUserPasswordReq;
import com.rag.proto.admin.UnBanUserReq;
import com.rag.proto.admin.UpdateSelfReq;
import com.rag.proto.admin.UpdateUserReq;
import com.rag.proto.admin.UpdateUserResp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 DRIFT-ARPC-007 / DRIFT-ARPC-008 守门测试 —— admin-rpc 的 <b>gRPC wire 层</b>与 Go 真源逐条对齐。
 *
 * <h2>B 出处（本类的行为基线）</h2>
 * <p><b>绝对路径</b>：{@code /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcGoWireParityTest.java}
 * <pre>
 *   B: RAG  service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminRpcGoWireParityTest.java
 *      :123-133   Go 侧 description 的原始字节（hex 常量；「已损坏」= Go 仓里的乱码字面量）
 *      :167-200   status IS NULL ⇒ 读成 0（推翻 AGENTS.md §0 的旧前提）
 *      :210-305   13 个 RPC 的错误分支：code + description 逐条对齐
 *      :318-333   createAdmin 撞用户名 ⇒ ALREADY_EXISTS「用户名已存在」
 *      :368-383   UpdateUserResp.success = false（Go 不 set Success）
 *      :418-446   createAdmin 的 W4 推进 admin_invite.update_time
 * </pre>
 *
 * <p>🔴 <b>本类由 B 侧同名类移植，期望值沿用 B 侧（D13）</b> ——
 * 断言值（gRPC code / description 逐字节 / span 名 / 属性名）<b>一律不因 C 侧实现而改</b>；
 * 若某条在 C 侧为红，那是<b>待查的漂移</b>，不是「C 侧的正确答案」。
 *
 * <h2>这个类补的是哪一层证据</h2>
 *
 * <p>{@code docs/DRIFT/users/admin/rpc.md} 的 §5.1 错误映射全表此前把 13 个用例的
 * 「Go desc」一栏写成中文并判 <b>EQUIVALENT</b>。2026-10-23 的<b>双侧活体对拍</b>
 * （{@code tools/difftest/exp-difftest-grpc-admin-errors.sh}）证明那张表<b>有 5 处是错的</b>：
 * Go 真源那几处的字面量在仓库里已被 <b>UTF-8 → GBK 误解码 → UTF-8</b> 的转码往返损坏，
 * Go 进程实际吐出的是乱码字节，而 RAG 吐的是正常中文。
 *
 * <p>⇒ 本类把两侧的 description <b>都</b>钉死：
 * <ul>
 *   <li>未损坏的那些：断言 RAG 的 description 与 <b>Go 原始字节</b>逐字节相等
 *       （Go 那一侧用实测抓到的 hex 常量表达，不写中文字面量 —— 免得又被编辑器转码一次）；</li>
 *   <li>已损坏的那些：断言 RAG 仍然吐正常中文，且<b>断言它与 Go 的原始字节不同</b> ——
 *       这是「<b>决策 tripwire</b>」：若有人没走用户签字就把 RAG 改成乱码对齐 Go，
 *       本条立刻红，并提示「先改台账 + 改本断言」。</li>
 * </ul>
 *
 * <h2>为什么必须真 PG（不能 mock）</h2>
 *
 * <p>要触发的是<b>真实的 DB 分支</b>：NOT_FOUND / ALREADY_EXISTS / PERMISSION_DENIED
 * 全都要真的查不到行、真的撞唯一约束、真的锁到 invite 行才走得到。
 * mock 数据层会把这几条分支全部编造出来。
 *
 * <h2>本类的可证伪性</h2>
 *
 * <p>把 {@code AdminDomainService} 里任意一处 {@code withDescription("…")} 改掉，
 * 对应用例立刻红（description 是逐字断言）。
 * 反过来，把「已损坏」那几条的 Go 字节常量改成 RAG 的中文，
 * 「决策 tripwire」那几条立刻红 —— 说明这条守卫真的在盯。
 *
 * <h2>⚠️ 移植说明（C 侧 ↔ B 侧的差异，逐条登记）</h2>
 * <ol>
 *   <li><b>包名 / 类名 / pb 包名</b>（D7 包压平 + 映射表已批准改名）：
 *       {@code com.rag.users.admin.rpc} → {@code com.rag.admin.grpc}；
 *       {@code com.rag.users.admin.proto.*} → {@code com.rag.proto.admin.*}；
 *       {@code AdminServiceGrpcImpl} → {@code com.rag.admin.grpc.AdminGrpcService}
 *       （<b>同一模块内</b>，故无需 import）；
 *       {@code com.rag.users.common.cryptx.PasswordUtil} → {@code com.rag.common.security.PasswordUtil}。</li>
 *   <li><b>启动类</b>：B 侧 {@code AdminRpcApplication} → C 侧 {@code AdminServiceApplication}
 *       （D1：api + rpc 合成一个进程）。</li>
 *   <li><b>⛔ 删掉 {@code @org.testcontainers.junit.jupiter.EnabledIfDockerAvailable}</b>
 *       （AGENTS.md §五.8 守门硬规则 ③：禁止用「Docker 不可用就跳过」掩盖失败）——
 *       B 侧 {@code AdminRpcGoWireParityTest.java:90} 有，本移植<b>必须删</b>。</li>
 *   <li><b>Nacos 桩</b>：B 侧靠 {@code spring.cloud.nacos.discovery.server-addr=127.0.0.1:1}
 *       让注册失败；C 侧改用本模块既有的顶层 {@link NacosStubConfiguration}
 *       （<b>不触网</b>，且沿用 admin 域既有范式）。</li>
 *   <li><b>容器端口钉住</b>：B 侧用 {@code GoStyleHostPortPinning.pinContainerPort}，
 *       <b>C 侧不存在该辅助类</b> ⇒ 本移植沿用 admin 域既有范式（{@code AdminServiceApplicationTests}）：
 *       <b>不钉端口</b>，用 Testcontainers 的随机映射端口。</li>
 *   <li><b>DDL 形态</b>：C 侧 {@code users_user.extra_info} / {@code admin_admin.extra_info}
 *       是 <b>TEXT</b>（B 侧 DRIFT-USERRPC-V-003 / DRIFT-ARPC-004① 的修法）⇒
 *       种子 SQL 里的 {@code '{}'::jsonb} 改成 {@code '{}'}（<b>纯类型适配，值语义不变</b>）；
 *       同理时间列统一用 {@code TIMESTAMPTZ} 字面量。</li>
 *   <li><b>邀请码有效期</b>：B 侧写 {@code LocalDateTime.now().plusMinutes(10)}；
 *       C 侧 {@code admin_invite.expires_at} 是 {@code TIMESTAMPTZ} 且实体用 {@code OffsetDateTime}
 *       ⇒ 本移植改用 {@code OffsetDateTime.now().plusMinutes(10)}
 *       （与本模块 {@code AdminDomainServiceRealPgTest#seedInvite} 同款，
 *       <b>避开 JVM 时区 ≠ DB 会话时区时「过期时间算错」的坑</b>；语义仍是「+10 分钟内有效」）。</li>
 *   <li><b>真实空闲端口</b>：B 侧用 {@code support/RealGrpcPort.pick()}（避开 macOS 临时段），
 *       <b>C 侧不存在该辅助类</b> ⇒ 本移植按 admin 域既有范式用 {@code new ServerSocket(0)} 取端口
 *       （同 {@code AdminServiceApplicationTests}）。</li>
 *   <li><b>Redis 容器</b>：C 侧 admin 域的每个 {@code @SpringBootTest} 都带 Redis 容器
 *       （见 {@code AdminServiceApplicationTests} / {@code AdminHttpContractRealTomcatTest}）⇒ 本移植照此。</li>
 *   <li><b>🆕 {@code @DirtiesContext(AFTER_CLASS)}（C 侧新增 · 纯测试装置，非行为面）</b>：
 *       B 侧本类<b>没有</b>这个注解 ⇒ 但 B 侧 admin-rpc 是<b>独立进程</b>、每个测试类各自起进程
 *       ⇒ 不存在「一个 JVM 里堆多个 Spring 上下文」的关停压力；
 *       C 侧 D1 合并后<b>一个 JVM 跑全部 8 个 admin 测试类</b>
 *       ⇒ 每个 {@code @SpringBootTest} 上下文都带一份 Nacos discovery 客户端
 *       （其 graceful shutdown <b>各等 10 秒</b>）⇒ 若让这些上下文留到 JVM 退出时才关
 *       ⇒ 关停总时长会<b>超过 surefire 的 30 秒 fork 超时</b> ⇒ 日志里出现
 *       {@code [ERROR] Surefire is going to kill self fork JVM}。
 *       ⇒ 本类（与 {@code AdminSpanWiringTest}）加上 {@code @DirtiesContext(AFTER_CLASS)}
 *       ⇒ <b>关停挪进测试运行期</b> ⇒ JVM 退出即干净 ⇒ 回归日志不出现该 ERROR 行。
 *       ⚠️ 这是<b>测试装置</b>层面的调整，<b>不碰任何断言值</b>。</li>
 * </ol>
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {AdminServiceApplication.class, NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN"
        })
@Testcontainers
class AdminRpcGoWireParityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("admin_rpc_wire_parity_it")
                    .withUsername("rag")
                    .withPassword("rag");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    /**
     * <p>gRPC server 的端口 —— 取一个<b>真实空闲端口</b>（不是 0）。
     *
     * <p>为什么要真实端口：gRPC server 与本测试断言的 gRPC wire 面必须<b>真的起来</b>
     * ⇒ 端口 0（= 随机）虽然也能起，但拿不到可复述的端口值 ⇒ 沿用 admin 域既有范式
     * （{@code AdminServiceApplicationTests}）取一个真实空闲口。
     *
     * <p>⚠️ <b>B 侧原注记（C 侧一并留档）</b>：B 侧用 {@code support/RealGrpcPort.pick()}
     * 而不是 {@code new ServerSocket(0)} —— 原因是 macOS 的 {@code ServerSocket(0)} 是
     * <b>OS 盲选口</b>（临时段 49152–65535）⇒ 与 Docker 随机发布口<b>同段</b>
     * ⇒ 「挑到没人监听的口」这个前提<b>直接失效</b> ⇒ 邻居占 127.0.0.1:P 时静默假红。
     * <b>C 侧无该辅助类</b> ⇒ 本移植按 admin 域既有范式用 {@code new ServerSocket(0)}，
     * 并把 B 侧这条已知风险<b>如实留档</b>在此。
     */
    static final int GRPC_PORT = pickFreePort();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("grpc.server.port", () -> String.valueOf(GRPC_PORT));
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("ADMIN_JWT_SECRET", () -> "admin-wire-parity-test-secret-0123456789");
    }

    @Autowired private AdminGrpcService grpc;
    @Autowired private AdminDomainService domain;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 一定不存在的 uid（与双侧活体对拍用的那个同款量级）。 */
    private static final long NO_SUCH_UID = 8_999_999_999_999_999_999L;

    private static final long ADMIN_UID = 9901L;
    private static final long USER_UID = 9902L;
    private static final String TAKEN_USERNAME = "taken-admin";

    // ── Go 侧的 description 原始字节（2026-10-23 双侧活体对拍实测抓到的 hex） ──
    //
    // 「未损坏」的一律与 RAG 逐字节相同；「已损坏」的是 Go 仓库里被 GBK 误解码往返弄坏的字面量。
    // ⚠️ 这里写 hex 而不是中文字面量，是为了让「Go 那一侧到底是什么字节」不可被编辑器二次转码。
    private static final String GO_OK_USER_NOT_EXIST = "e794a8e688b7e4b88de5ad98e59ca8";     // 用户不存在
    private static final String GO_OK_PASSWORD_WRONG = "e5af86e7a081e99499e8afaf";             // 密码错误
    private static final String GO_OK_TOKEN_EMPTY = "546f6b656ee4b88de883bde4b8bae7a9ba";             // Token不能为空

    /** 🔴 Go 侧已被转码损坏的字面量（乱码），逐条对应 docs/DRIFT/users/admin/rpc.md §5.1 / §7。 */
    private static final String GO_CORRUPT_USER_NOT_EXIST =
            "e990a2e384a6e59f9be6b693e5b685e793a8e98da63f";                                   // 鐢ㄦ埛涓嶅瓨鍦?
    private static final String GO_CORRUPT_INVITE_WRONG =
            "e7bba0efbc84e6828ae98d9be6a980e5808be79287e9a38ee7889ce996bfe6ac92ee87a4";       // 绠＄悊鍛橀個璇风爜閿欒
    private static final String GO_CORRUPT_USERNAME_TAKEN =
            "e990a2e384a6e59fbe98d9ae5b685e587a1e7809be6a8bae6b9aa";                         // 鐢ㄦ埛鍚嶅凡瀛樺湪

    // ==================================================================
    // 1. status IS NULL —— 双侧活体对拍证明 Go 也读成 0，<b>不是</b>「读不出来」
    // ==================================================================

    /**
     * 🔴 <b>本条推翻了一个被 AGENTS.md §0 标红的前提</b>。
     *
     * <p>AGENTS.md §0 与 {@code docs/DRIFT/users/user/rpc.md} 的 DRIFT-USERRPC-V-001 都写：
     * 「Go 的语义是『NULL 读不出来』⇒ 应 fail-closed 抛 INTERNAL」。
     * <b>2026-10-23 的双侧活体对拍否掉了这个前提。</b>
     *
     * <p><b>Go 侧证据（机制）</b>：
     * <ul>
     *   <li>{@code gorm.io/gorm@v1.31.1/scan.go:19}（{@code prepareValues}）：
     *       {@code values[idx] = reflect.New(reflect.PointerTo(field.FieldType)).Interface()}
     *       ⇒ 对 {@code int64} 字段造出来的是 <b>{@code **int64}</b>（双指针），不是 {@code *int64}；</li>
     *   <li>{@code database/sql} 的 {@code convertAssign} 对「dest 是指针、src 为 nil」走
     *       {@code case reflect.Pointer: if src == nil { dv.SetZero(); return nil }}
     *       ⇒ <b>不报错</b>，只是把内层指针置 nil；</li>
     *   <li>GORM 的 int setter 看到 {@code **int64} 且 {@code *data == nil} ⇒
     *       <b>字段保持零值 0</b>，同样不报错。</li>
     * </ul>
     *
     * <p><b>Go 侧证据（活体）</b>：2026-10-23 在 {@code tools/difftest} 的两套真栈上，
     * 往 {@code go_db.users} 插一行 {@code status IS NULL}，
     * 打 Go admin-api {@code GET /admincenter/v1/admin/getuser?uid=…}
     * ⇒ {@code {"code":200,…,"user":{…,"status":0}}}；
     * 同一时刻打 RAG admin-api ⇒ 同样是 {@code "status":0}。<b>两侧一致。</b>
     *
     * <p>⇒ RAG 侧 {@code UserEntity.status = 0L}（{@code domain/UserEntity.java:85}）
     * <b>与 Go 一致，不是漂移</b>。本条把它钉死，防止下一个 agent 按 AGENTS.md §0 把它「修」掉。
     */
    @Test
    @DisplayName("🔴 status IS NULL：RAG 读成 0，与 Go 一致（不是「读不出来」）")
    void nullStatusColumnReadsAsZeroLikeGo() {
        // RAG 的 DDL 声明 status NOT NULL，Go 的 GORM tag（default:0，无 not null）不声明
        // ⇒ 这是 DDL 层的差异（DRIFT-USERRPC-V-002，归属 users-common）。
        // 为了复现 Go 的可达性，本用例先把它放开 —— 与 user-rpc 侧的同类用例同款做法。
        jdbcTemplate.execute("ALTER TABLE users_user ALTER COLUMN status DROP NOT NULL");
        long nullStatusUid = USER_UID + 100L;
        jdbcTemplate.update("""
                INSERT INTO users_user (uid, username, password, email, status, score,
                                       extra_info, create_time, update_time)
                VALUES (?, 'null-status-user', 'x', 'null@mail.test', NULL, 0, '{}',
                        TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')
                """, nullStatusUid);

        // 前提断言：这一行的 status 在 DB 里真的是 NULL（否则本用例什么也没测到）
        Boolean isNull = jdbcTemplate.queryForObject(
                "SELECT status IS NULL FROM users_user WHERE uid = ?", Boolean.class, nullStatusUid);
        assertTrue(Boolean.TRUE.equals(isNull),
                "前提：status 列必须真的是 NULL —— 否则本用例测的是普通行而不是 NULL 行");

        // Go：NULL → 0（无错误）。RAG 必须同样。
        var user = domain.getUser(nullStatusUid);
        assertEquals(0L, user.getStatus(),
                "Go 侧实测（gorm v1.31.1 的 **int64 + convertAssign + int setter 三段）把 NULL 读成 0 且不报错，"
                        + "RAG 必须一致；若这里变成 null 或抛错，说明有人按 DRIFT-USERRPC-V-001 的"
                        + "（已被证伪的）前提改了实体 —— 那是把 RAG 改离 Go");

        // 列表路径同理：Go 的 FindUserListByKeyword 走同一条扫描路径
        var list = domain.getUserList(1, 20, "null-status-user");
        assertEquals(1, list.users().size(), "前提：列表里应当能查到这行");
        assertEquals(0L, list.users().get(0).getStatus(),
                "列表路径的 NULL 读取必须与单行路径一致（Go 侧同样是 0）");
    }

    // ==================================================================
    // 2. gRPC status description 全表 —— 逐字节对齐 Go 原始字节
    // ==================================================================

    /**
     * 🔴 逐条对齐 <b>13 个 RPC 的错误分支</b>的 gRPC status：
     * <b>code</b> 必须两侧相同，<b>description</b> 必须与 Go 的原始字节逐字节相同（除非那条已被判为转码损坏）。
     */
    @Test
    @DisplayName("🔴 13 个 RPC 的错误分支：code 与 description 逐条对齐 Go 原始字节")
    void errorStatusTableMatchesGoWireBytes() {
        Map<String, Object[]> table = new LinkedHashMap<>();

        // ── 未损坏的那些：RAG 的 description 必须与 Go 原始字节逐字节相等 ──
        table.put("login.notfound", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.login(LoginReq.newBuilder()
                        .setUsername("zzz_no_such_admin").setPassword("x").build(), obs),
                Status.Code.NOT_FOUND, GO_OK_USER_NOT_EXIST, false});
        table.put("login.wrongpw", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.login(LoginReq.newBuilder()
                        .setUsername("admin").setPassword("zzz_wrong").build(), obs),
                Status.Code.UNAUTHENTICATED, GO_OK_PASSWORD_WRONG, false});
        table.put("logout.empty", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.logout(LogoutReq.newBuilder()
                        .setToken("").build(), obs),
                Status.Code.INVALID_ARGUMENT, GO_OK_TOKEN_EMPTY, false});
        table.put("getuser.notfound", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.getUser(GetUserReq.newBuilder()
                        .setUid(NO_SUCH_UID).build(), obs),
                Status.Code.NOT_FOUND, GO_OK_USER_NOT_EXIST, false});
        table.put("banuser.notfound", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.banUser(BanUserReq.newBuilder()
                        .setUid(NO_SUCH_UID).setOperatorId(1).build(), obs),
                Status.Code.NOT_FOUND, GO_OK_USER_NOT_EXIST, false});
        table.put("unbanuser.notfound", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.unBanUser(UnBanUserReq.newBuilder()
                        .setUid(NO_SUCH_UID).setOperatorId(1).build(), obs),
                Status.Code.NOT_FOUND, GO_OK_USER_NOT_EXIST, false});
        table.put("deleteuser.notfound", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.deleteUser(DeleteUserReq.newBuilder()
                        .setUid(NO_SUCH_UID).setOperatorId(1).build(), obs),
                Status.Code.NOT_FOUND, hexOf("用户" + NO_SUCH_UID + "不存在"), false});
        table.put("resetuserpassword.notfound", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.resetUserPassword(ResetUserPasswordReq.newBuilder()
                        .setUid(NO_SUCH_UID).setOperatorId(1).build(), obs),
                Status.Code.NOT_FOUND, GO_OK_USER_NOT_EXIST, false});
        table.put("updateuser.notfound", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.updateUser(UpdateUserReq.newBuilder()
                        .setUid(NO_SUCH_UID).setUsername("zzz_probe").build(), obs),
                Status.Code.NOT_FOUND, GO_OK_USER_NOT_EXIST, false});

        // ── 已损坏的那些：RAG 仍吐正常中文，且**必须与 Go 的原始字节不同**（决策 tripwire）──
        table.put("getself.notfound", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.getSelf(GetSelfReq.newBuilder()
                        .setUid(NO_SUCH_UID).build(), obs),
                Status.Code.NOT_FOUND, GO_CORRUPT_USER_NOT_EXIST, true});
        table.put("updateself.notfound", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.updateSelf(UpdateSelfReq.newBuilder()
                        .setUid(NO_SUCH_UID).build(), obs),
                Status.Code.NOT_FOUND, GO_CORRUPT_USER_NOT_EXIST, true});
        table.put("createinvitecode.notfound", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.createInviteCode(CreateInviteCodeReq.newBuilder()
                        .setOperatorId(NO_SUCH_UID).build(), obs),
                Status.Code.NOT_FOUND, GO_CORRUPT_USER_NOT_EXIST, true});
        table.put("createadmin.emptyinvite", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.createAdmin(CreateAdminReq.newBuilder()
                        .setUsername("zzz_probe_admin").setPassword("p").setInviteCode("").build(), obs),
                Status.Code.PERMISSION_DENIED, GO_CORRUPT_INVITE_WRONG, true});
        table.put("createadmin.badinvite", new Object[]{
                (Consumer<StreamObserver>) obs -> grpc.createAdmin(CreateAdminReq.newBuilder()
                        .setUsername("zzz_probe_admin").setPassword("p")
                        .setInviteCode("ADM-0000000000000000").build(), obs),
                Status.Code.PERMISSION_DENIED, GO_CORRUPT_INVITE_WRONG, true});

        for (Map.Entry<String, Object[]> e : table.entrySet()) {
            @SuppressWarnings("unchecked")
            Consumer<StreamObserver> invoker = (Consumer<StreamObserver>) e.getValue()[0];
            Status.Code expectedCode = (Status.Code) e.getValue()[1];
            String goRawHex = (String) e.getValue()[2];
            boolean goCorrupted = (Boolean) e.getValue()[3];

            Outcome o = capture(invoker);
            assertNotNull(o.error, e.getKey() + " 应当失败（预期 gRPC code=" + expectedCode + "）");
            assertEquals(expectedCode, o.error.getStatus().getCode(),
                    e.getKey() + " 的 gRPC code 与 Go 不一致");
            String ragDesc = o.error.getStatus().getDescription() == null
                    ? "" : o.error.getStatus().getDescription();

            if (goCorrupted) {
                // Go 那一条的字面量在仓库里已被 UTF-8→GBK 误解码往返弄坏 ⇒ Go 吐的是乱码字节。
                // RAG 保留正常中文（待用户签字，见 docs/DRIFT/users/admin/rpc.md §7 DRIFT-ARPC-007）。
                assertNotEquals(decodeHex(goRawHex), ragDesc,
                        e.getKey() + "：RAG 的 description 不该与 Go 的损坏字节相同。"
                                + "若此断言失败，说明有人已把 RAG 改成复刻 Go 的乱码 —— "
                                + "请先在 docs/DRIFT/users/admin/rpc.md §7 更新签字结论，再改本条断言");
                assertEquals(expectedChineseFor(e.getKey()), ragDesc,
                        e.getKey() + "：RAG 的 description 必须是正常中文");
            } else {
                assertEquals(decodeHex(goRawHex), ragDesc,
                        e.getKey() + "：RAG 的 description 必须与 Go 的原始字节**逐字节**相同"
                                + "（Go 原始 hex=" + goRawHex + "）");
            }
        }
    }

    // ==================================================================
    // 3. ALREADY_EXISTS 分支 —— Go 侧那一条也已损坏
    // ==================================================================

    /**
     * 🔴 CreateAdmin 撞用户名时的 description：Go 那一条的字面量同样被转码损坏。
     *
     * <p>Go 出处（声明点）：{@code service/user/admin/rpc/internal/logic/createadminlogic.go:73}
     * 与 {@code :99}（两处同一个字面量）。
     * 消费点：{@code AdminDomainService.createAdmin} 的 ALREADY_EXISTS 分支。
     */
    @Test
    @DisplayName("🔴 createAdmin 撞用户名：RAG 吐「用户名已存在」，Go 那一条已被转码损坏")
    void createAdminUsernameTakenDescriptionIsStillChinese() {
        seedUsableInvite("ADM-PARITY-USABLE-0001");
        seedTakenUsername();

        Outcome o = capture((Consumer<StreamObserver>) obs -> grpc.createAdmin(CreateAdminReq.newBuilder()
                .setUsername(TAKEN_USERNAME).setPassword("p").setInviteCode("ADM-PARITY-USABLE-0001").build(),
                obs));

        assertNotNull(o.error, "应当因用户名冲突而失败");
        assertEquals(Status.Code.ALREADY_EXISTS, o.error.getStatus().getCode());
        assertEquals("用户名已存在", o.error.getStatus().getDescription());
        assertNotEquals(decodeHex(GO_CORRUPT_USERNAME_TAKEN), o.error.getStatus().getDescription(),
                "RAG 的 description 不该与 Go 的损坏字节相同（决策 tripwire，见 DRIFT-ARPC-007）");
    }

    // ==================================================================
    // 4. UpdateUserResp.success —— 已对齐 Go（DRIFT-ARPC-002，2026-10-23 修复）
    // ==================================================================

    /**
     * <b>两侧已一致：{@code UpdateUserResp.success} 都是 {@code false}</b>。
     *
     * <p><b>Go 出处</b>：{@code service/user/admin/rpc/internal/logic/updateuserlogic.go:105-113}
     * <pre>
     *   return &amp;pb.UpdateUserResp{
     *       User: &amp;pb.UserInfo{ Uid: …, Username: …, Email: …, Status: …, ExtraInfo: … },
     *   }, nil
     * </pre>
     * 只 set 了 {@code User}，<b>没有 set Success</b> ⇒ proto3 bool 默认值 {@code false}，
     * 线上序列化时该字段<b>缺席</b>，客户端读到 {@code false}。
     *
     * <p><b>2026-10-23 变更经过（§3.3.3 的原型案例：绿测试锁死了错误答案）</b>：
     * 本断言<b>原先要求 {@code assertTrue(resp.getSuccess())}</b>，
     * 并在失败消息里写「若本断言失败，说明有人改了实现」——
     * 也就是说，它<b>把「与 Go 相反」这个漂移固化成了绿测试</b>。
     * 修复实现后它立刻变红，<b>这条红正是「跑绿不是对」的直接证据</b>。
     * 现改为断言<b>与 Go 一致的 {@code false}</b>，并保留 Go 出处作为反向守卫。
     *
     * <p><b>⚠️ 顺序说明</b>：本轮实际是<b>先改实现、后改断言</b>，
     * 严格说与 §3.3.3「先改/删错误断言，再改实现」的顺序相反。<b>结论一致</b>：
     * 那条绿测试锁的是错误答案，改实现后它变红，正好证明了问题所在。
     *
     * <p><b>为什么 admin-api 的 HTTP 面看不到这个字段</b>：
     * {@code AdminUpdateUserService} 硬编码 {@code success=true}
     * （Go 侧 {@code service/user/admin/api/internal/logic/admin/updateuserlogic.go:68} 同样硬编码）
     * ⇒ 端到端 HTTP 两侧都是 {@code {"success":true}}，<b>本条只对直连 gRPC 的调用方可见</b>。
     * ⇒ 也正因如此，HTTP 对拍全绿 <b>不能</b> 证明 rpc 层已对齐。
     */
    @Test
    @DisplayName("✅ UpdateUserResp.success：与 Go 一致为 false（Go 不 set Success，proto3 默认值）")
    void updateUserRespSuccessMatchesGoWire() {
        Outcome o = capture((Consumer<StreamObserver>) obs -> grpc.updateUser(UpdateUserReq.newBuilder()
                .setUid(USER_UID).setUsername("renamed-by-probe").build(), obs));

        assertNull(o.error, "更新一个存在的用户不应失败: " + o.error);
        UpdateUserResp resp = (UpdateUserResp) o.response;
        assertFalse(resp.getSuccess(),
                "Go 的 updateuserlogic.go:105-113 只 set 了 User、没 set Success"
                        + " ⇒ wire 上必须是 false。RAG 侧 AdminGrpcService 的 updateUser"
                        + " 若重新 setSuccess(true)，就又与真源分叉了 —— 请同步更新 "
                        + "docs/DRIFT/users/admin/rpc.md 的 DRIFT-ARPC-002 与本断言。");
        assertTrue(resp.hasUser(), "Go 侧同样构造了 user（updateuserlogic.go:106-113 带 user）");
    }

    // ==================================================================
    // 4b. createAdmin 的 W4 —— admin_invite.update_time 必须推进
    // ==================================================================

    /**
     * 🔴 2026-10-23「台账 vs 实际」复核新发现：这条 UPDATE 此前<b>被漏掉了</b>。
     *
     * <p><b>Go 出处（消费点，不是 struct tag 声明行）</b>：
     * <pre>
     *   service/user/admin/rpc/internal/model/admin.go:141-146
     *     tx.Model(&amp;AdminInvite{}).Where("id = ?", id).Updates(map[string]any{
     *         "used_by_uid": …, "used_at": … })
     *
     *   gorm.io/gorm@v1.31.1/callbacks/update.go:235-255
     *     if !stmt.SkipHooks &amp;&amp; stmt.Schema != nil {
     *       for _, dbName := range stmt.Schema.DBNames {
     *         field := stmt.Schema.LookUpField(dbName)
     *         if field.AutoUpdateTime &gt; 0 &amp;&amp; value[field.Name] == nil &amp;&amp; value[field.DBName] == nil {
     *           … set = append(set, clause.Assignment{Column: …update_time…, Value: now})
     *   } }
     * </pre>
     * 关键：这段位于 <b>map 更新分支</b>内（{@code default:} 结构体分支<b>之前</b>），
     * 而本调用既无 {@code Select}（⇒ {@code selectColumns} 空、{@code restricted=false}）
     * 也没在 map 里带 {@code update_time} ⇒ <b>条件成立，Go 确实会补写</b>。
     * ⇒ <b>「map 更新不推进 autoUpdateTime」是个常见误解</b>，本条把它钉死。
     *
     * <p><b>可观察性</b>：{@code admin_invite.update_time} 不在 {@code admin.proto}
     * 的任何 message 里、业务代码也从没读过它 ⇒ 补它<b>不改变任何可观察行为</b>，
     * 属「让 DDL 行为与 Go 同构」的纯对齐。
     *
     * <p><b>为什么仍要测</b>：正因为不可观察，静态 review 极容易再漏一次
     * —— 而它一旦漂移，将来若有人给 invite 加「最后使用时间」类字段就会读到错值。
     */
    @Test
    @DisplayName("✅ createAdmin 的 W4 推进 admin_invite.update_time（对齐 Go 的 map 分支 autoUpdateTime）")
    void createAdminAdvancesInviteUpdateTimeLikeGo() {
        String code = "ADM-UPDATETIME-0001";
        seedUsableInvite(code);
        Long before = jdbcTemplate.queryForObject(
                "select extract(epoch from update_time)::bigint from admin_invite where code = ?",
                Long.class, code);
        assertNotNull(before, "种子数据必须存在");

        Outcome o = capture((Consumer<StreamObserver>) obs -> grpc.createAdmin(CreateAdminReq.newBuilder()
                .setUsername("invite-time-boss").setPassword("p")
                .setInviteCode(code).build(), obs));
        assertNull(o.error, "用有效邀请码建号应成功: " + o.error);

        Long after = jdbcTemplate.queryForObject(
                "select extract(epoch from update_time)::bigint from admin_invite where code = ?",
                Long.class, code);
        assertNotNull(after, "invite 行必须还在");
        assertTrue(after > before,
                "W4 标记 invite 已用时必须同时推进 update_time（Go 的 GORM map 分支会补写）。"
                        + "before=" + before + " after=" + after
                        + " —— 若本断言失败，说明 W4 的 setSql(\"update_time = CURRENT_TIMESTAMP\") 被删了");

        // 顺带确认 W4 的主行为没被这次改动影响
        Long usedBy = jdbcTemplate.queryForObject(
                "select used_by_uid from admin_invite where code = ?", Long.class, code);
        assertNotNull(usedBy, "邀请码必须被标记为已用");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 一次 gRPC 调用的结果（成功取响应，失败取 StatusRuntimeException）。 */
    private static final class Outcome {
        final Object response;
        final StatusRuntimeException error;

        Outcome(Object response, StatusRuntimeException error) {
            this.response = response;
            this.error = error;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Outcome capture(Consumer<StreamObserver> invoker) {
        final Object[] resp = new Object[1];
        final StatusRuntimeException[] err = new StatusRuntimeException[1];
        invoker.accept(new StreamObserver() {
            @Override public void onNext(Object value) { resp[0] = value; }
            @Override public void onError(Throwable t) {
                if (t instanceof StatusRuntimeException) {
                    err[0] = (StatusRuntimeException) t;
                } else {
                    throw new IllegalStateException(t);
                }
            }
            @Override public void onCompleted() { }
        });
        return new Outcome(resp[0], err[0]);
    }

    private static String hexOf(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String decodeHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    /** 已损坏那几条的 RAG 侧期望中文。 */
    private static String expectedChineseFor(String caseName) {
        switch (caseName) {
            case "getself.notfound":
            case "updateself.notfound":
            case "createinvitecode.notfound":
                return "用户不存在";
            case "createadmin.emptyinvite":
            case "createadmin.badinvite":
                return "管理员邀请码错误";
            default:
                throw new IllegalArgumentException("未登记的 case: " + caseName);
        }
    }

    @BeforeEach
    void resetTables() {
        jdbcTemplate.execute("TRUNCATE users_user, admin_admin, admin_invite RESTART IDENTITY CASCADE");
        // seeder 造的 admin（AdminSeederBootstrap，密码常量 "admin"）—— 用真实的 bcrypt hash，
        // 否则 login.wrongpw 那条会因「hash 畸形」而不是「密码错」走到同一个分支，测的就不是同一件事。
        jdbcTemplate.update("""
                INSERT INTO admin_admin (uid, username, password, email, extra_info,
                                        create_time, update_time)
                VALUES (?, 'admin', ?, NULL, '{}',
                        TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')
                """, ADMIN_UID, PasswordUtil.encrypt("admin"));
        jdbcTemplate.update("""
                INSERT INTO users_user (uid, username, password, email, status, score,
                                       extra_info, create_time, update_time)
                VALUES (?, ?, 'x', 'u@mail.test', 0, 0, '{}',
                        TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')
                """, USER_UID, "probe-user");
    }

    /** 造一条可用的邀请码（Go: CreateInviteCode → expires_at = now + 10min）。 */
    private void seedUsableInvite(String code) {
        jdbcTemplate.update("""
                INSERT INTO admin_invite (code, inviter_uid, expires_at, used_by_uid, used_at,
                                          create_time, update_time)
                VALUES (?, ?, ?, NULL, NULL,
                        TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')
                """, code, ADMIN_UID, OffsetDateTime.now().plusMinutes(10));
    }

    /** 让 username 冲突那条也走到 ALREADY_EXISTS（需要一个已存在的用户名）。 */
    private void seedTakenUsername() {
        jdbcTemplate.update("""
                INSERT INTO admin_admin (uid, username, password, email, extra_info,
                                        create_time, update_time)
                VALUES (?, ?, 'x', NULL, '{}',
                        TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')
                """, ADMIN_UID + 1L, TAKEN_USERNAME);
    }

    /** 取一个真实空闲端口（admin 域既有范式，见 {@code AdminServiceApplicationTests}）。 */
    private static int pickFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("取空闲端口失败", e);
        }
    }
}
