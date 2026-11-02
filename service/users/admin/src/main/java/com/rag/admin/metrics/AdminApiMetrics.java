package com.rag.admin.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * <p><b>职责</b>：admin 域 <b>HTTP 面</b>的业务指标收集（Micrometer Counter）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>包名</b>（{@code com.rag.users.admin.api.metrics} →
 *       {@code com.rag.admin.metrics}，D7 包压平）＋ <b>类名未动</b>
 *       ⇒ ⇒ <b>指标名、label 名、label 取值、description、Counter 数量逐条照抄</b>。</li>
 *   <li><b>语义还一样吗</b>：一样。
 *       <ul>
 *         <li>⚠️ <b>指标完整名 {@code admin_api_intercept_total} 是 L1 观测契约</b>
 *             （映射表 admin.md §6）⇒ 逐字未动；</li>
 *         <li>⚠️ <b>16 个具名 Counter 逐条未动</b>（login 3 + banuser 2 + unbanuser 2 + delete 2
 *             + resetuserpassword 2 + updateuser 2 + invite/create 3）
 *             ⇒ ⇒ <b>没有新增任何序列</b>（B 侧没埋点的端点，本侧也不埋 —— 1:1 不补）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/metrics/AdminApiMetrics.java}。</li>
 * </ol>
 *
 * <p>对应 B 侧: service/user/admin/api/internal/metrics/metrics.go 的 AdminApiInterceptCount。
 *
 * <p>B 侧 → Java label 映射（16 个具名 Counter，覆盖 7 个 Logic）：
 * <pre>
 *   admin_api_intercept_total{path="/admin/login", result="rpc_error"}      → loginRpcError
 *   admin_api_intercept_total{path="/admin/login", result="internal_error"} → loginInternalError  // JWT 签发失败
 *   admin_api_intercept_total{path="/admin/login", result="success"}        → loginSuccess
 * </pre>
 *
 * <p>banuser (2)：
 * <pre>
 *   admin_api_intercept_total{path="/admin/banuser", result="rpc_error"}    → banRpcError
 *   admin_api_intercept_total{path="/admin/banuser", result="success"}       → banSuccess
 * </pre>
 *
 * <p>unbanuser (2) / delete (2) / resetuserpassword (2)：
 * 同 banuser 模式（rpc_error + success）。
 *
 * <p>updateuser (2)：
 * <pre>
 *   admin_api_intercept_total{path="/admin/updateuser", result="db_error"}  → updateDbError  (DRIFT-NEW-005)
 *   admin_api_intercept_total{path="/admin/updateuser", result="success"}    → updateSuccess
 * </pre>
 *
 * <p>invite/create (3，DRIFT-NEW-007：唯一有 token_invalid label）：
 * <pre>
 *   admin_api_intercept_total{path="/admin/invite/create", result="token_invalid"}  → inviteTokenInvalid
 *   admin_api_intercept_total{path="/admin/invite/create", result="rpc_error"}       → inviteRpcError
 *   admin_api_intercept_total{path="/admin/invite/create", result="success"}         → inviteSuccess
 * </pre>
 *
 * <p>其它 6 个 RPC（create / getself / getuser / getuserlist / logout / update）B 侧没埋点，1:1 不补。
 *
 * <p>⚠️ <b>exposition 上多一个 label（已知、未修）</b>：{@code application.yml} 配了
 * {@code management.metrics.tags.application: ${spring.application.name}}，
 * ⇒ Prometheus 输出行是 {@code admin_api_intercept_total{application="admin-service",path=...,result=...}}，
 * 而 B 侧 {@code CounterVec} <b>只有 path / result 两个</b>。
 * ⇒ 按完整 label 集做匹配（scrape 规则 / 告警规则）的下游会失配。
 *
 * <p>⚠️ <b>increment 点在业务层，不在拦截器</b>：B 侧 16 个 Counter 的 {@code increment()}
 * 全部落在 {@code api/service/Admin*Service} 里 ⇒ ⇒ 本域同样落在
 * {@code service/AdminApiService}（本类的 getter 就是那些调用点的入口）。
 */
@Component
public class AdminApiMetrics {

    // ===== login =====
    private final Counter loginRpcError;
    private final Counter loginInternalError;
    private final Counter loginSuccess;

    // ===== banuser =====
    private final Counter banRpcError;
    private final Counter banSuccess;

    // ===== unbanuser =====
    private final Counter unbanRpcError;
    private final Counter unbanSuccess;

    // ===== delete =====
    private final Counter deleteRpcError;
    private final Counter deleteSuccess;

    // ===== resetuserpassword =====
    private final Counter resetRpcError;
    private final Counter resetSuccess;

    // ===== updateuser =====
    private final Counter updateDbError;
    private final Counter updateSuccess;

    // ===== invite/create =====
    private final Counter inviteTokenInvalid;
    private final Counter inviteRpcError;
    private final Counter inviteSuccess;

    public AdminApiMetrics(MeterRegistry registry) {
        // ---- login ----
        this.loginRpcError = counter(registry, "/admin/login", "rpc_error");
        this.loginInternalError = counter(registry, "/admin/login", "internal_error");
        this.loginSuccess = counter(registry, "/admin/login", "success");

        // ---- banuser ----
        this.banRpcError = counter(registry, "/admin/banuser", "rpc_error");
        this.banSuccess = counter(registry, "/admin/banuser", "success");

        // ---- unbanuser ----
        this.unbanRpcError = counter(registry, "/admin/unbanuser", "rpc_error");
        this.unbanSuccess = counter(registry, "/admin/unbanuser", "success");

        // ---- delete ----
        this.deleteRpcError = counter(registry, "/admin/delete", "rpc_error");
        this.deleteSuccess = counter(registry, "/admin/delete", "success");

        // ---- resetuserpassword ----
        this.resetRpcError = counter(registry, "/admin/resetuserpassword", "rpc_error");
        this.resetSuccess = counter(registry, "/admin/resetuserpassword", "success");

        // ---- updateuser ----
        this.updateDbError = counter(registry, "/admin/updateuser", "db_error");
        this.updateSuccess = counter(registry, "/admin/updateuser", "success");

        // ---- invite/create ----
        this.inviteTokenInvalid = counter(registry, "/admin/invite/create", "token_invalid");
        this.inviteRpcError = counter(registry, "/admin/invite/create", "rpc_error");
        this.inviteSuccess = counter(registry, "/admin/invite/create", "success");
    }

    private static Counter counter(MeterRegistry registry, String path, String result) {
        return Counter.builder("admin_api_intercept_total")
                .description("Total number of API frontline interceptions")
                .tag("path", path)
                .tag("result", result)
                .register(registry);
    }

    public Counter getLoginRpcError() { return loginRpcError; }
    public Counter getLoginInternalError() { return loginInternalError; }
    public Counter getLoginSuccess() { return loginSuccess; }

    public Counter getBanRpcError() { return banRpcError; }
    public Counter getBanSuccess() { return banSuccess; }

    public Counter getUnbanRpcError() { return unbanRpcError; }
    public Counter getUnbanSuccess() { return unbanSuccess; }

    public Counter getDeleteRpcError() { return deleteRpcError; }
    public Counter getDeleteSuccess() { return deleteSuccess; }

    public Counter getResetRpcError() { return resetRpcError; }
    public Counter getResetSuccess() { return resetSuccess; }

    public Counter getUpdateDbError() { return updateDbError; }
    public Counter getUpdateSuccess() { return updateSuccess; }

    public Counter getInviteTokenInvalid() { return inviteTokenInvalid; }
    public Counter getInviteRpcError() { return inviteRpcError; }
    public Counter getInviteSuccess() { return inviteSuccess; }
}
