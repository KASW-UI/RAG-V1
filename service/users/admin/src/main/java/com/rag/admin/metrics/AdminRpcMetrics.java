package com.rag.admin.metrics;

import io.micrometer.core.instrument.Counter;
import lombok.Getter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * <p><b>职责</b>：admin 域 <b>业务/领域面</b>的 Prometheus 计数器（Micrometer Counter）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>包名</b>（{@code com.rag.users.admin.rpc.metrics} →
 *       {@code com.rag.admin.metrics}，D7 包压平）＋ <b>类名未动</b>
 *       ⇒ ⇒ <b>指标名、label 名、label 取值、description、Counter 数量逐条照抄</b>。</li>
 *   <li><b>语义还一样吗</b>：一样。
 *       <ul>
 *         <li>⚠️ <b>指标名 {@code admin_rpc_login_total} 与
 *             {@code admin_rpc_high_risk_action_logal} 是 L1 观测契约</b>（映射表 admin.md §6）
 *             ⇒ ⇒ 逐字未动；</li>
 *         <li>🔴 <b>{@code logal} 是 B 侧的源码 typo（应为 {@code total}）—— 1:1 保留，不得修正</b>；
 *             修正会让 Prometheus 侧的抓取规则 / 告警规则失配。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/metrics/AdminRpcMetrics.java}。</li>
 * </ol>
 *
 * <p>对应 B 侧 internal/metrics/metrics.go：
 * <pre>
 *   AdminLoginCount      = prometheus.NewCounterVec(... Name: "login_total",      Labels: ["result"])
 *   AdminActionCount     = prometheus.NewCounterVec(... Name: "high_risk_action_logal", Labels: ["action", "result"])
 * </pre>
 *
 * <p>B 侧 Subsystem="admin_rpc"，所以完整 metric 名是：
 * <ul>
 *   <li>{@code admin_rpc_login_total{result=...}}</li>
 *   <li>{@code admin_rpc_high_risk_action_logal{action=..., result=...}}</li>
 * </ul>
 *
 * <p>注：B 侧 metric 名 {@code "high_risk_action_logal"} 是源码 typo（应为 {@code "total"}），
 * 1:1 保留 —— Java 侧也用 {@code "logal"} 与 B 侧 Prometheus name 对齐。
 *
 * <p>B 侧 → Java label 映射（25 个具名 Counter）：
 * <pre>
 *   admin_rpc_login_total{result=user_not_found}        → loginUserNotFound
 *   admin_rpc_login_total{result=db_error}              → loginDbError
 *   admin_rpc_login_total{result=wrong_answer}          → loginWrongAnswer
 *   admin_rpc_login_total{result=success}               → loginSuccess
 *
 *   admin_rpc_high_risk_action_logal{action=createinvite, result=user_not_found}  → createInviteUserNotFound
 *   admin_rpc_high_risk_action_logal{action=createinvite, result=db_error}        → createInviteDbError
 *   admin_rpc_high_risk_action_logal{action=createinvite, result=internal_error}  → createInviteInternalError
 *   admin_rpc_high_risk_action_logal{action=createinvite, result=success}          → createInviteSuccess
 *
 *   admin_rpc_high_risk_action_logal{action=update, result=user_not_found}        → updateUserUserNotFound
 *   admin_rpc_high_risk_action_logal{action=update, result=db_error}              → updateUserDbError
 *   admin_rpc_high_risk_action_logal{action=update, result=internal_error}        → updateUserInternalError
 *   admin_rpc_high_risk_action_logal{action=update, result=success}               → updateUserSuccess
 *
 *   admin_rpc_high_risk_action_logal{action=delete, result=user_not_found}        → deleteUserUserNotFound
 *   admin_rpc_high_risk_action_logal{action=delete, result=db_error}              → deleteUserDbError
 *   admin_rpc_high_risk_action_logal{action=delete, result=success}               → deleteUserSuccess
 *
 *   admin_rpc_high_risk_action_logal{action=resetpassword, result=user_not_found}  → resetPasswordUserNotFound
 *   admin_rpc_high_risk_action_logal{action=resetpassword, result=db_error}        → resetPasswordDbError
 *   admin_rpc_high_risk_action_logal{action=resetpassword, result=internal_error}  → resetPasswordInternalError
 *   admin_rpc_high_risk_action_logal{action=resetpassword, result=success}         → resetPasswordSuccess
 *
 *   admin_rpc_high_risk_action_logal{action=ban, result=user_not_found}           → banUserNotFound
 *   admin_rpc_high_risk_action_logal{action=ban, result=db_error}                 → banDbError
 *   admin_rpc_high_risk_action_logal{action=ban, result=success}                  → banSuccess
 *
 *   admin_rpc_high_risk_action_logal{action=unban, result=user_not_found}         → unbanUserNotFound
 *   admin_rpc_high_risk_action_logal{action=unban, result=db_error}               → unbanDbError
 *   admin_rpc_high_risk_action_logal{action=unban, result=success}                → unbanSuccess
 * </pre>
 *
 * <p>覆盖 B 侧埋点的 7 个 Logic：Login / CreateInviteCode / UpdateUser / DeleteUser /
 * ResetUserPassword / BanUser / UnBanUser。GetSelf / UpdateSelf / GetUser / GetUserList / Logout /
 * CreateAdmin 在 B 侧*不埋点*（D-016），所以本类不为其建 Counter。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class AdminRpcMetrics {
 *     private final Counter loginUserNotFound;
 *     private final Counter loginDbError;
 *     private final Counter loginWrongAnswer;
 *     private final Counter loginSuccess;
 *     private final Counter createInviteUserNotFound;
 *     private final Counter createInviteDbError;
 *     private final Counter createInviteInternalError;
 *     private final Counter createInviteSuccess;
 *     private final Counter updateUserUserNotFound;
 *     private final Counter updateUserDbError;
 *     private final Counter updateUserInternalError;
 *     private final Counter updateUserSuccess;
 *     private final Counter deleteUserUserNotFound;
 *     private final Counter deleteUserDbError;
 *     private final Counter deleteUserSuccess;
 *     private final Counter resetPasswordUserNotFound;
 *     private final Counter resetPasswordDbError;
 *     private final Counter resetPasswordInternalError;
 *     private final Counter resetPasswordSuccess;
 *     private final Counter banUserNotFound;
 *     private final Counter banDbError;
 *     private final Counter banSuccess;
 *     private final Counter unbanUserNotFound;
 *     private final Counter unbanDbError;
 *     private final Counter unbanSuccess;
 *
 *     public Counter getLoginUserNotFound() { return loginUserNotFound; }
 *     public Counter getLoginDbError() { return loginDbError; }
 *     public Counter getLoginWrongAnswer() { return loginWrongAnswer; }
 *     public Counter getLoginSuccess() { return loginSuccess; }
 *     public Counter getCreateInviteUserNotFound() { return createInviteUserNotFound; }
 *     public Counter getCreateInviteDbError() { return createInviteDbError; }
 *     public Counter getCreateInviteInternalError() { return createInviteInternalError; }
 *     public Counter getCreateInviteSuccess() { return createInviteSuccess; }
 *     public Counter getUpdateUserUserNotFound() { return updateUserUserNotFound; }
 *     public Counter getUpdateUserDbError() { return updateUserDbError; }
 *     public Counter getUpdateUserInternalError() { return updateUserInternalError; }
 *     public Counter getUpdateUserSuccess() { return updateUserSuccess; }
 *     public Counter getDeleteUserUserNotFound() { return deleteUserUserNotFound; }
 *     public Counter getDeleteUserDbError() { return deleteUserDbError; }
 *     public Counter getDeleteUserSuccess() { return deleteUserSuccess; }
 *     public Counter getResetPasswordUserNotFound() { return resetPasswordUserNotFound; }
 *     public Counter getResetPasswordDbError() { return resetPasswordDbError; }
 *     public Counter getResetPasswordInternalError() { return resetPasswordInternalError; }
 *     public Counter getResetPasswordSuccess() { return resetPasswordSuccess; }
 *     public Counter getBanUserNotFound() { return banUserNotFound; }
 *     public Counter getBanDbError() { return banDbError; }
 *     public Counter getBanSuccess() { return banSuccess; }
 *     public Counter getUnbanUserNotFound() { return unbanUserNotFound; }
 *     public Counter getUnbanDbError() { return unbanDbError; }
 *     public Counter getUnbanSuccess() { return unbanSuccess; }
 * }
 * }</pre>

 */
@Getter
@Component
public class AdminRpcMetrics {

    // ===== 4 个 Login counters =====
    private final Counter loginUserNotFound;
    private final Counter loginDbError;
    private final Counter loginWrongAnswer;
    private final Counter loginSuccess;

    // ===== 4 个 CreateInviteCode counters =====
    private final Counter createInviteUserNotFound;
    private final Counter createInviteDbError;
    private final Counter createInviteInternalError;
    private final Counter createInviteSuccess;

    // ===== 4 个 UpdateUser counters =====
    private final Counter updateUserUserNotFound;
    private final Counter updateUserDbError;
    private final Counter updateUserInternalError;
    private final Counter updateUserSuccess;

    // ===== 3 个 DeleteUser counters =====
    private final Counter deleteUserUserNotFound;
    private final Counter deleteUserDbError;
    private final Counter deleteUserSuccess;

    // ===== 4 个 ResetUserPassword counters =====
    private final Counter resetPasswordUserNotFound;
    private final Counter resetPasswordDbError;
    private final Counter resetPasswordInternalError;
    private final Counter resetPasswordSuccess;

    // ===== 3 个 BanUser counters =====
    private final Counter banUserNotFound;
    private final Counter banDbError;
    private final Counter banSuccess;

    // ===== 3 个 UnBanUser counters =====
    private final Counter unbanUserNotFound;
    private final Counter unbanDbError;
    private final Counter unbanSuccess;

    public AdminRpcMetrics(MeterRegistry registry) {
        // ---- Login ----
        this.loginUserNotFound = Counter.builder("admin_rpc_login_total")
                .description("Admin login attempts and results")
                .tag("result", "user_not_found")
                .register(registry);
        this.loginDbError = Counter.builder("admin_rpc_login_total")
                .description("Admin login attempts and results")
                .tag("result", "db_error")
                .register(registry);
        this.loginWrongAnswer = Counter.builder("admin_rpc_login_total")
                .description("Admin login attempts and results")
                .tag("result", "wrong_answer")
                .register(registry);
        this.loginSuccess = Counter.builder("admin_rpc_login_total")
                .description("Admin login attempts and results")
                .tag("result", "success")
                .register(registry);

        // ---- CreateInviteCode ----
        this.createInviteUserNotFound = actionCounter(registry, "createinvite", "user_not_found");
        this.createInviteDbError = actionCounter(registry, "createinvite", "db_error");
        this.createInviteInternalError = actionCounter(registry, "createinvite", "internal_error");
        this.createInviteSuccess = actionCounter(registry, "createinvite", "success");

        // ---- UpdateUser ----
        this.updateUserUserNotFound = actionCounter(registry, "update", "user_not_found");
        this.updateUserDbError = actionCounter(registry, "update", "db_error");
        this.updateUserInternalError = actionCounter(registry, "update", "internal_error");
        this.updateUserSuccess = actionCounter(registry, "update", "success");

        // ---- DeleteUser ----
        this.deleteUserUserNotFound = actionCounter(registry, "delete", "user_not_found");
        this.deleteUserDbError = actionCounter(registry, "delete", "db_error");
        this.deleteUserSuccess = actionCounter(registry, "delete", "success");

        // ---- ResetUserPassword ----
        this.resetPasswordUserNotFound = actionCounter(registry, "resetpassword", "user_not_found");
        this.resetPasswordDbError = actionCounter(registry, "resetpassword", "db_error");
        this.resetPasswordInternalError = actionCounter(registry, "resetpassword", "internal_error");
        this.resetPasswordSuccess = actionCounter(registry, "resetpassword", "success");

        // ---- BanUser ----
        this.banUserNotFound = actionCounter(registry, "ban", "user_not_found");
        this.banDbError = actionCounter(registry, "ban", "db_error");
        this.banSuccess = actionCounter(registry, "ban", "success");

        // ---- UnBanUser ----
        this.unbanUserNotFound = actionCounter(registry, "unban", "user_not_found");
        this.unbanDbError = actionCounter(registry, "unban", "db_error");
        this.unbanSuccess = actionCounter(registry, "unban", "success");
    }

    private static Counter actionCounter(MeterRegistry registry, String action, String result) {
        return Counter.builder("admin_rpc_high_risk_action_logal")
                .description("Total number of high-risk admin actions")
                .tag("action", action)
                .tag("result", result)
                .register(registry);
    }

}
