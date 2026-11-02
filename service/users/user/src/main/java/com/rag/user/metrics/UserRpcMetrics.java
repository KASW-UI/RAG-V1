package com.rag.user.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * <p><b>user 域领域层业务指标收集</b>。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/metrics/UserRpcMetrics.java}
 * （逐条照抄）。
 *
 * <p><b>⚠️ L1 观测契约：指标完整名 ＋ label 逐条一致</b>
 * <pre>
 *   user_rpc_register_total{result=success}        → registerSuccess
 *   user_rpc_register_total{result=fail_exists}    → registerFailExists
 *   user_rpc_register_total{result=db_error}       → registerDbError
 *   user_rpc_register_total{result=internal_error} → registerInternalError
 *   user_rpc_login_total{result=success}           → loginSuccess
 *   user_rpc_login_total{result=user_not_found}    → loginUserNotFound
 *   user_rpc_login_total{result=db_error}          → loginDbError
 *   user_rpc_login_total{result=internal_error}    → loginInternalError
 * </pre>
 *
 * <p>⚠️ <b>标签集只有 {@code result}</b>（B 侧用 {@code Namespace:""} ＋ {@code Subsystem:"user_rpc"}
 * ⇒ 指标名自带前缀）⇒ ⇒ <b>不得</b>再挂 {@code application} 标签 ⇒ 那会让同一份 PromQL
 * 在两侧得到不同的聚合结果。
 *
 * <p><b>框架职责</b>：Micrometer {@code Counter} ＋ {@code MeterRegistry}；
 * 8 个具名 Counter，每个自带 {@code tag("result", ...)}（与 B 侧 label 一致）。
 */
@Component
public class UserRpcMetrics {

    private final Counter registerSuccess;
    private final Counter registerFailExists;
    private final Counter registerDbError;
    private final Counter registerInternalError;

    private final Counter loginSuccess;
    private final Counter loginUserNotFound;
    private final Counter loginDbError;
    private final Counter loginInternalError;

    public UserRpcMetrics(MeterRegistry registry) {
        this.registerSuccess = Counter.builder("user_rpc_register_total")
                .description("Total number of user registerations")
                .tag("result", "success")
                .register(registry);
        this.registerFailExists = Counter.builder("user_rpc_register_total")
                .description("Total number of user registerations")
                .tag("result", "fail_exists")
                .register(registry);
        this.registerDbError = Counter.builder("user_rpc_register_total")
                .description("Total number of user registerations")
                .tag("result", "db_error")
                .register(registry);
        this.registerInternalError = Counter.builder("user_rpc_register_total")
                .description("Total number of user registerations")
                .tag("result", "internal_error")
                .register(registry);

        this.loginSuccess = Counter.builder("user_rpc_login_total")
                .description("Total number of user logins")
                .tag("result", "success")
                .register(registry);
        this.loginUserNotFound = Counter.builder("user_rpc_login_total")
                .description("Total number of user logins")
                .tag("result", "user_not_found")
                .register(registry);
        this.loginDbError = Counter.builder("user_rpc_login_total")
                .description("Total number of user logins")
                .tag("result", "db_error")
                .register(registry);
        this.loginInternalError = Counter.builder("user_rpc_login_total")
                .description("Total number of user logins")
                .tag("result", "internal_error")
                .register(registry);
    }

    public Counter getRegisterSuccess() {
        return registerSuccess;
    }

    public Counter getRegisterFailExists() {
        return registerFailExists;
    }

    public Counter getRegisterDbError() {
        return registerDbError;
    }

    public Counter getRegisterInternalError() {
        return registerInternalError;
    }

    public Counter getLoginSuccess() {
        return loginSuccess;
    }

    public Counter getLoginUserNotFound() {
        return loginUserNotFound;
    }

    public Counter getLoginDbError() {
        return loginDbError;
    }

    public Counter getLoginInternalError() {
        return loginInternalError;
    }
}
