package com.rag.admin.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.admin.AdminServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>admin 域 · DB 超时预算的「域级守卫」（真 PG · 真 Tomcat）</b>。
 *
 * <p><b>为什么要有这条</b>：§9.10.3 那条「PG 不可用 / 请求超时 ⇒ 两侧响应面不一致 ＋
 * <b>C 侧超时后副作用已落库</b>」的漂移，根因在<b>共享层</b>（D2 域内直连后，
 * HTTP 路径没有绑定入向 {@code DeadlineState} ⇒ SQL 不被取消 ⇒ 事务照常提交）
 * ⇒ 已由 <b>{@code global} 收口域</b>在 {@code common/} 修完（2026-11-03）。
 *
 * <p><b>但收口域的复测是「跨仓对拍」</b>（要同时起 B 侧进程）
 * ⇒ ⇒ **admin 域自己没有一条「不依赖 B 侧进程」的守卫**
 * ⇒ ⇒ 共享层日后任何一次改动，都可能把「副作用归零」悄悄改回去，而**域内 151 条用例照绿**。
 *
 * <p><b>⇒ 本类把那两条判据固化成域内可跑的测试</b>：
 * <ol>
 *   <li><b>响应面</b>：DB 被行锁卡住 ⇒ 请求在<b>内层预算（2s）</b>到点 ⇒
 *       <b>HTTP 200 + 信封 {@code code=1015「服务繁忙」}</b>（不是 503 + 裸文本）；
 *       且耗时<b>明显小于 HTTP 预算（3s）</b> ⇒ ⇒ 证明到点的是**内层**预算而不是 HTTP 那一层。</li>
 *   <li><b>副作用面</b>：<b>{@code admin_admin} 里没有新行</b> ＋
 *       <b>邀请码未被消费（{@code used_at IS NULL}）</b>
 *       ⇒ ⇒ 「客户端拿到失败、服务端却已提交」这条**失败-成功不一致**被钉死。</li>
 * </ol>
 *
 * <p><b>注入方式（可复现、不依赖任何 mock）</b>：另开一条<b>独立 JDBC 连接</b>
 * 对 {@code admin_invite} 那一行 {@code SELECT ... FOR UPDATE} 并<b>保持事务不提交</b>
 * ⇒ ⇒ 创建管理员的第 1 步（W1 的 {@code SELECT ... FOR UPDATE}）必然阻塞
 * ⇒ ⇒ 阻塞到预算到点 ⇒ 取消 / 回滚路径被真触发。
 *
 * <p><b>⚠️ 两条断言都是可判别的</b>（变异即变红）：
 * <ul>
 *   <li>若共享层退回「只有 HTTP 3s 预算、没有内层预算」⇒ 耗时 ≈ 3s ⇒ <b>耗时断言变红</b>
 *       （响应也会是 503 + 裸文本 ⇒ 响应断言同时变红）；</li>
 *   <li>若 SQL 又不被取消 ⇒ 事务照常提交 ⇒ <b>{@code admin_admin} 多出一行</b>
 *       ⇒ <b>副作用断言变红</b>。</li>
 * </ul>
 *
 * <p><b>B 侧对照读数（E-4 双侧活体对拍 · 2026-11-03 复测）</b>：
 * B 侧 = {@code 200 + 信封 1015「服务繁忙」} @ 2.03s ＋ {@code admin_admin} 里 {@code r2%to} 0 行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {AdminServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "grpc.server.port=0",
                "grpc.server.address=127.0.0.1"
        })
@Testcontainers
class AdminDbTimeoutBudgetRealTomcatTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("first_db")
            .withUsername("admin")
            .withPassword("password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("ADMIN_JWT_SECRET", () -> "admin-db-timeout-guard-secret-0123456789");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSource;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("DB 被行锁卡住 ⇒ 内层预算到点 ⇒ 200 + 信封 1015「服务繁忙」＋ 副作用 0 行（B 侧同形）")
    void dbBlockedBeyondInnerBudgetYields1015AndNoSideEffect() throws Exception {
        // ① 登录拿 token（真 HTTP · 真 Tomcat）
        ResponseEntity<String> loginResp = rest.postForEntity("/admin/v1/login",
                json("{\"username\":\"admin\",\"password\":\"admin\"}"), String.class);
        assertEquals(HttpStatus.OK, loginResp.getStatusCode());
        String token = parse(loginResp.getBody()).get("data").get("token").asText();
        assertFalse(token.isEmpty());

        // ② 建一个邀请码（真 HTTP）⇒ 后面用它的 code 去持锁
        ResponseEntity<String> inviteResp = rest.postForEntity("/admin/v1/invite/create",
                json("{}", "Authorization", "Bearer " + token), String.class);
        assertEquals(HttpStatus.OK, inviteResp.getStatusCode());
        String inviteCode = parse(inviteResp.getBody()).get("data").get("invite_code").asText();
        assertNotNull(inviteCode);
        assertFalse(inviteCode.isEmpty());

        final String username = "timeout-guard-admin";
        // ③ 另开一条**独立** JDBC 连接，对 invite 那一行加锁并**保持事务不提交**
        try (Connection holder = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement(
                    "SELECT id FROM admin_invite WHERE code = ? FOR UPDATE")) {
                ps.setString(1, inviteCode);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "持锁前必须能查到邀请码行");
                }
            }

            // ④ 调创建管理员 ⇒ W1 的 SELECT ... FOR UPDATE 必然阻塞 ⇒ 直到预算到点
            String body = "{\"username\":\"" + username + "\",\"password\":\"E4passw0rd\","
                    + "\"email\":\"timeout-guard@example.com\",\"invite_code\":\"" + inviteCode + "\"}";
            long t0 = System.nanoTime();
            ResponseEntity<String> resp = rest.postForEntity("/admin/v1/create",
                    json(body, "Authorization", "Bearer " + token), String.class);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            // ⑤ 响应面：与 B 侧逐字同形（200 + 信封 1015「服务繁忙」）
            assertEquals(HttpStatus.OK, resp.getStatusCode(),
                    "DB 超时必须落业务错误码信封（200），不是 503 + 裸文本 —— B 侧实测就是 200 + 1015");
            assertTrue(resp.getBody().contains("\"code\":1015"),
                    "业务码必须是 1015（服务繁忙），实际 body=" + resp.getBody());
            assertTrue(resp.getBody().contains("服务繁忙"),
                    "文案必须是「服务繁忙」，实际 body=" + resp.getBody());

            // ⚠️ 可判别断言：到点的必须是**内层**预算（2s），不是 HTTP 预算（3s）
            //    ⇒ 若共享层丢了内层预算，这里会 ≈3s ⇒ 变红
            assertTrue(elapsedMs < 2_900L,
                    "到点必须发生在内层预算（rpc-timeout=2s）附近，实测 " + elapsedMs + "ms"
                            + "（若 ≈3000ms ⇒ 说明只剩 HTTP 预算 ⇒ 内层预算又丢了）");

            // ⑥ 副作用面：事务必须整体回滚 ⇒ 既没有新 admin 行，邀请码也没被消费
            assertEquals(0, countAdmins(username),
                    "超时后 admin_admin 不得留下新行（客户端看到失败、服务端却已提交 ⇒ 重试会撞唯一约束）");
            assertTrue(inviteUsedAtIsNull(inviteCode),
                    "超时后邀请码不得被消费（W4 必须在 W3 之后整体回滚）");
        }
    }

    // ============== 夹具 ==============

    private HttpEntity<String> json(String body, String... headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            h.set(headers[i], headers[i + 1]);
        }
        return new HttpEntity<>(body, h);
    }

    private JsonNode parse(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("响应体不是合法 JSON: [" + raw + "]", e);
        }
    }

    private int countAdmins(String username) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM admin_admin WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new AssertionError("统计 admin_admin 失败", e);
        }
    }

    private boolean inviteUsedAtIsNull(String code) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT used_at IS NULL FROM admin_invite WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (Exception e) {
            throw new AssertionError("读取邀请码状态失败", e);
        }
    }
}
