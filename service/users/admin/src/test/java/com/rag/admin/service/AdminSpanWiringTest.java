package com.rag.admin.service;

import com.rag.admin.AdminServiceApplication;
import com.rag.admin.domain.service.AdminDomainService;
import com.rag.admin.http.NacosStubConfiguration;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 DRIFT-ARPC-006 守门 —— admin-rpc 的手写 span 必须<b>真的被导出</b>。
 *
 * <h2>B 出处（本类的行为基线）</h2>
 * <p><b>绝对路径</b>：{@code /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminSpanWiringTest.java}
 * <pre>
 *   B: RAG  service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/AdminSpanWiringTest.java
 *      :95-132   自建收集型 exporter + 把 GlobalOpenTelemetry 指向它
 *      :161-182  ① resetUserPassword 真的导出 Action-Admin-ResetUserPassword + 两个 audit 属性
 *      :184-201  ② Go 没打 span 的方法，RAG 也不许凭空加
 *      :203-216  ③ Go 有 span 的 8 个方法，AdminSpans 常量与之逐条对应
 * </pre>
 *
 * <p>🔴 <b>本类由 B 侧同名类移植，期望值沿用 B 侧（D13）</b> ——
 * span 名 / tracer 名 / 属性名 / 属性值<b>一字未改</b>。
 *
 * <h2>为什么不能只写「结构断言」</h2>
 * <p>AGENTS.md §3.3：「<b>不得凭注解/方法名称断定机制已启用</b>」。
 * 断言「{@code AdminSpans.traced} 被调用了」只能证明<b>代码写成了这样</b>，
 * 证明不了<b>运行时真的产生了 span</b>。
 * ⇒ 本类走<b>装配层</b>：自己装一个 SDK + 收集型 {@link SpanExporter}，
 * 让 {@code GlobalOpenTelemetry} 指向它，然后断言<b>导出的 span</b>里
 * 有正确的名字与属性。
 *
 * <h2>Go 出处（决定「有几个 span」，不是「13 个」）</h2>
 * <p>Go 的 admin/rpc <b>13 个 logic 里只有 8 个</b>import 了 otel 并手写 span；
 * 另外 <b>5 个没有</b>：
 * {@code getselflogic.go} / {@code getuserlogic.go} / {@code getuserlistlogic.go} /
 * {@code logoutlogic.go} / {@code updateselflogic.go}。
 * ⇒ 台账此前写「Go 逐 Logic 打 audit 属性」会让人误以为是 13 个；
 * 真实是 <b>8 个 logic / 13 个 attribute</b>（13 是属性条数）。
 * 本类<b>双向</b>钉死：有 span 的名字必须出现、没 span 的方法<b>必须不产生</b>对应 span。
 *
 * <p>⚠️ 顺带证明 <b>N-4（签字偏离）确实落到了数据里</b>：
 * Go 的 {@code audit.operator_id} 恒为 0（读 {@code ctx["uid"]} 而 claim 名是 {@code userId}），
 * RAG 传<b>真实 uid</b>。补了 span 之后这个差异第一次真正出现在 trace 数据里
 * ⇒ 对拍/排障时两侧该属性不同，<b>属预期差异，不是回归</b>。
 *
 * <h2>⚠️ 移植说明（C 侧 ↔ B 侧的差异，逐条登记）</h2>
 * <ol>
 *   <li><b>包名</b>（D7）：{@code com.rag.users.admin.rpc} → {@code com.rag.admin.service}
 *       ⇒ C 侧 {@code AdminSpans} 也在 {@code com.rag.admin.service}
 *       （B 侧在 {@code rpc.service}）⇒ <b>同包 ⇒ 无需 import</b>。</li>
 *   <li><b>{@code AdminDomainService}</b>：{@code com.rag.users.admin.rpc.service.AdminDomainService}
 *       → {@code com.rag.admin.domain.service.AdminDomainService}（D7：实体/服务下沉到 {@code domain.service}）。</li>
 *   <li><b>启动类</b>：B 侧 {@code AdminRpcApplication} → C 侧 {@code AdminServiceApplication}（D1）
 *       ＋ 本模块既有的顶层 {@link NacosStubConfiguration}（<b>不触网</b>）。</li>
 *   <li><b>⛔ 删掉 {@code @org.testcontainers.junit.jupiter.EnabledIfDockerAvailable}</b>
 *       （AGENTS.md §五.8 守门硬规则 ③：禁止用「Docker 不可用就跳过」掩盖失败）——
 *       B 侧 {@code AdminSpanWiringTest.java:73} 有，本移植<b>必须删</b>。</li>
 *   <li><b>⛔ 删掉 {@code GoStyleHostPortPinning.pinContainerPort}</b> ——
 *       <b>C 侧不存在该辅助类</b> ⇒ 容器用 Testcontainers 的随机映射端口。</li>
 *   <li><b>{@code support/RealGrpcPort}</b> —— <b>C 侧不存在该辅助类</b>
 *       ⇒ 本移植按 admin 域既有范式（{@code AdminServiceApplicationTests}）用
 *       {@code new ServerSocket(0)} 取真实空闲端口；
 *       <b>B 侧原注记（macOS 临时段与 Docker 发布口同段 ⇒ 静默假红的风险）如实留档</b>
 *       在 {@link #pickFreePort()} 的注释里。</li>
 *   <li><b>DDL 形态</b>：C 侧 {@code users_user.extra_info} 是 <b>TEXT</b>
 *       （B 侧 DRIFT-USERRPC-V-003 的修法）⇒ 种子 SQL 里的 {@code '{}'::jsonb} 改成 {@code '{}'}
 *       （<b>纯类型适配，值语义不变</b>）。</li>
 *   <li><b>{@code ADMIN_JWT_SECRET}</b>：C 侧 {@code admin-api.admin-auth.access-secret} /
 *       {@code admin-rpc.admin-auth.access-secret} 是<b>无默认值的必填项</b>
 *       （B 侧 P0 #8 修法）⇒ 上下文启动必须注入 ⇒ 用 {@code @DynamicPropertySource} 注入
 *       （同本模块 {@code AdminServiceApplicationTests}）。</li>
 *   <li><b>Redis 容器</b>：C 侧 admin 域的每个 {@code @SpringBootTest} 都带 Redis 容器
 *       （见 {@code AdminServiceApplicationTests} / {@code AdminHttpContractRealTomcatTest}）⇒ 本移植照此。</li>
 *   <li><b>共享层的全局 OTel 装配</b>：C 侧 {@code common} 的 {@code GoStyleOtelAutoConfiguration}
 *       会在容器刷新早期调 {@code GlobalOpenTelemetry.set(...)}
 *       ⇒ 本类已在 {@code @BeforeAll} 里 set 过 ⇒ 共享层那次会撞上
 *       {@code IllegalStateException}，而 <b>{@code GoStyleOtelAgent#install} 自己 catch 住并只记 ERROR</b>
 *       ⇒ ⇒ <b>容器照常启动</b>、<b>全局仍是本类的收集器</b>（B 侧无此共享层 ⇒ B 侧无此并存关系）。</li>
 *   <li><b>🆕 {@code @DirtiesContext(AFTER_CLASS)}（C 侧新增 · 纯测试装置，非行为面）</b>：
 *       B 侧本类<b>没有</b>这个注解 ⇒ 但 B 侧 admin-rpc 是<b>独立进程</b>
 *       ⇒ 不存在「一个 JVM 里堆多个 Spring 上下文」的关停压力；
 *       C 侧 D1 合并后<b>一个 JVM 跑全部 8 个 admin 测试类</b>
 *       ⇒ 每个 {@code @SpringBootTest} 上下文都带一份 Nacos discovery 客户端
 *       （其 graceful shutdown <b>各等 10 秒</b>）⇒ 若让这些上下文留到 JVM 退出时才关
 *       ⇒ 关停总时长会<b>超过 surefire 的 30 秒 fork 超时</b> ⇒ 日志里出现
 *       {@code [ERROR] Surefire is going to kill self fork JVM}。
 *       ⇒ 本类（与 {@code AdminRpcGoWireParityTest}）加上 {@code @DirtiesContext(AFTER_CLASS)}
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
class AdminSpanWiringTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("admin_rpc_span_wiring_it")
                    .withUsername("rag")
                    .withPassword("rag");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    /**
     * 自建收集型 exporter —— <b>不引 {@code opentelemetry-sdk-testing}</b>
     * （该包不在 admin 模块的依赖里，引入它只为测试反而扩大面）。
     */
    static final class CollectingExporter implements SpanExporter {
        final List<SpanData> spans = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        void clear() {
            spans.clear();
        }
    }

    private static final CollectingExporter EXPORTER = new CollectingExporter();

    @BeforeAll
    static void installSdk() {
        // 🔴 关键：把 GlobalOpenTelemetry 指向本测试的收集器。
        // AdminSpans 用的是 GlobalOpenTelemetry.getTracer(...)，不装 SDK 就是 no-op。
        GlobalOpenTelemetry.resetForTest();
        // GlobalOpenTelemetry.set 要的是 OpenTelemetry，不是 SdkTracerProvider
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(EXPORTER))
                        .build())
                .build());
    }

    @AfterAll
    static void uninstallSdk() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Autowired private AdminDomainService domain;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final long USER_UID = 7701L;
    private static final long OPERATOR_ID = 8801L;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("grpc.server.port", () -> String.valueOf(pickFreePort()));
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("ADMIN_JWT_SECRET", () -> "admin-span-wiring-test-secret-0123456789");
    }

    private void seedUser() {
        jdbcTemplate.update("""
                INSERT INTO users_user (uid, username, password, email, status, score,
                                       extra_info, create_time, update_time)
                VALUES (?, 'span-probe-user', 'x', 'span@mail.test', 0, 0, '{}',
                        TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')
                ON CONFLICT (uid) DO NOTHING
                """, USER_UID);
    }

    private static Optional<SpanData> findSpan(String name) {
        synchronized (EXPORTER.spans) {
            return EXPORTER.spans.stream().filter(s -> name.equals(s.getName())).findFirst();
        }
    }

    @Test
    @DisplayName("✅ resetUserPassword 真的导出 Action-Admin-ResetUserPassword + 两个 audit 属性")
    void resetUserPasswordProducesGoSpanWithAttributes() {
        seedUser();
        EXPORTER.clear();

        assertTrue(domain.resetUserPassword(USER_UID, OPERATOR_ID));

        SpanData span = findSpan(AdminSpans.RESET_USER_PASSWORD).orElseThrow(
                () -> new AssertionError("没有导出名为 " + AdminSpans.RESET_USER_PASSWORD
                        + " 的 span。实际导出的 = " + EXPORTER.spans.stream().map(SpanData::getName).toList()));

        assertEquals(AdminSpans.TRACER_NAME, span.getInstrumentationScopeInfo().getName(),
                "tracer 名必须是 admin-rpc（Go: otel.Tracer(\"admin-rpc\")）");

        assertEquals(OPERATOR_ID, span.getAttributes().get(AttributeKey.longKey(AdminSpans.ATTR_OPERATOR_ID)),
                "audit.operator_id 必须带上（Go updateuserlogic 等 4 个 logic 共用这两个属性）");
        assertEquals(USER_UID, span.getAttributes().get(AttributeKey.longKey(AdminSpans.ATTR_TARGET_UID)),
                "audit.target_uid 必须带上");

        assertTrue(span.hasEnded(), "span 必须已 end（Go 的 defer span.End()）");
    }

    @Test
    @DisplayName("✅ Go 没打 span 的方法，RAG 也不许凭空加（getUser 不产生 span）")
    void methodsWithoutGoSpanMustNotProduceOne() {
        seedUser();
        EXPORTER.clear();

        // Go 的 getuserlogic.go **没有** otel import、没有任何 span ⇒ RAG 也不该有
        assertNotNull(domain.getUser(USER_UID));

        // GetSelf 用的就是 getOneAdminByUsername 一类的读路径，同样无 span；
        // 这里断言「没有产生任何 Action-Admin-* span」
        boolean anyAdminSpan = EXPORTER.spans.stream()
                .anyMatch(s -> s.getName() != null && s.getName().startsWith("Action-Admin-"));
        assertFalse(anyAdminSpan,
                "Go 的 getuserlogic/getselflogic 本来就没有 span，RAG 不得凭空补 —— "
                        + "否则 trace 里的 admin span 数会从 Go 的 8 个变成 13 个。实际 = "
                        + EXPORTER.spans.stream().map(SpanData::getName).toList());
    }

    @Test
    @DisplayName("✅ Go 有 span 的 8 个方法，AdminSpans 常量与之逐条对应")
    void spanNameConstantsMatchGoInventory() {
        // Go 侧有 span 的 8 个 logic 及其 span 名（声明点见 AdminSpans 的 javadoc 表格）
        assertEquals("Action-Admin-Login", AdminSpans.LOGIN);
        assertEquals("Action-Admin-CreateAdmin", AdminSpans.CREATE_ADMIN);
        assertEquals("Action-Admin-CreateInviteCode", AdminSpans.CREATE_INVITE_CODE);
        assertEquals("Action-Admin-UpdateUser", AdminSpans.UPDATE_USER);
        assertEquals("Action-Admin-BanUser", AdminSpans.BAN_USER);
        assertEquals("Action-Admin-UnBanUser", AdminSpans.UNBAN_USER);
        assertEquals("Action-Admin-DeleteUser", AdminSpans.DELETE_USER);
        assertEquals("Action-Admin-ResetUserPassword", AdminSpans.RESET_USER_PASSWORD);
        assertEquals("admin-rpc", AdminSpans.TRACER_NAME);
    }

    /**
     * 取一个真实空闲端口。
     *
     * <p>⚠️ <b>B 侧原注记（C 侧一并留档）</b>：B 侧原用 {@code new ServerSocket(0)}，
     * 后来改成 {@code support/RealGrpcPort.pick()} —— 因为 macOS 的 {@code ServerSocket(0)} 是
     * <b>OS 盲选口</b>（临时段 49152–65535）⇒ 与 Docker 随机发布口<b>同段</b>
     * ⇒ 「挑到没人监听的口」这个前提<b>直接失效</b>
     * ⇒ 邻居占 127.0.0.1:P 时我们的通配绑定照样成功、lsof 两个监听者并存
     * ⇒ 客户端被更具体的那个抢走 ⇒ <b>静默假红</b>。
     * <b>C 侧无 {@code RealGrpcPort} 辅助类</b> ⇒ 本移植按 admin 域既有范式
     * （{@code AdminServiceApplicationTests}）回到 {@code new ServerSocket(0)}，
     * 并把 B 侧这条已知风险<b>如实留档</b>在此。
     */
    private static int pickFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("取空闲端口失败", e);
        }
    }
}
