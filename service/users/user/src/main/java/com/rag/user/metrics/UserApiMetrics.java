package com.rag.user.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * <p><b>user 域 HTTP 层业务指标收集</b>。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/metrics/UserApiMetrics.java}
 * （逐条照抄）。
 *
 * <p><b>⚠️ L1 观测契约：指标完整名 ＋ label 逐条一致</b>
 * <pre>
 *   user_api_intercept_total{path="/user/login",    result="rpc_error"}       → loginRpcError
 *   user_api_intercept_total{path="/user/login",    result="wrong_password"}  → loginWrongPassword
 *   user_api_intercept_total{path="/user/login",    result="user_banned"}     → loginUserBanned
 *   user_api_intercept_total{path="/user/login",    result="token_error"}     → loginTokenError
 *   user_api_intercept_total{path="/user/login",    result="success"}         → loginSuccess
 *   user_api_intercept_total{path="/user/register", result="rpc_error"}       → registerRpcError
 *   user_api_intercept_total{path="/user/register", result="success"}         → registerSuccess
 * </pre>
 *
 * <p>其余 7 个端点在 Go 端没有埋点 ⇒ <b>1:1 不补</b>（D-016：最小覆盖）。
 *
 * <p><b>框架职责</b>：Micrometer {@code Counter} ＋ {@code MeterRegistry}；
 * 暴露端点 = Spring Boot Actuator {@code /actuator/prometheus}。
 */
@Component
public class UserApiMetrics {

    private final Counter loginRpcError;
    private final Counter loginWrongPassword;
    private final Counter loginUserBanned;
    private final Counter loginTokenError;
    private final Counter loginSuccess;

    private final Counter registerRpcError;
    private final Counter registerSuccess;

    public UserApiMetrics(MeterRegistry registry) {
        this.loginRpcError = Counter.builder("user_api_intercept_total")
                .description("Total number of API frontline interceptions")
                .tag("path", "/user/login")
                .tag("result", "rpc_error")
                .register(registry);
        this.loginWrongPassword = Counter.builder("user_api_intercept_total")
                .description("Total number of API frontline interceptions")
                .tag("path", "/user/login")
                .tag("result", "wrong_password")
                .register(registry);
        this.loginUserBanned = Counter.builder("user_api_intercept_total")
                .description("Total number of API frontline interceptions")
                .tag("path", "/user/login")
                .tag("result", "user_banned")
                .register(registry);
        this.loginTokenError = Counter.builder("user_api_intercept_total")
                .description("Total number of API frontline interceptions")
                .tag("path", "/user/login")
                .tag("result", "token_error")
                .register(registry);
        this.loginSuccess = Counter.builder("user_api_intercept_total")
                .description("Total number of API frontline interceptions")
                .tag("path", "/user/login")
                .tag("result", "success")
                .register(registry);

        this.registerRpcError = Counter.builder("user_api_intercept_total")
                .description("Total number of API frontline interceptions")
                .tag("path", "/user/register")
                .tag("result", "rpc_error")
                .register(registry);
        this.registerSuccess = Counter.builder("user_api_intercept_total")
                .description("Total number of API frontline interceptions")
                .tag("path", "/user/register")
                .tag("result", "success")
                .register(registry);
    }

    public Counter getLoginRpcError() {
        return loginRpcError;
    }

    public Counter getLoginWrongPassword() {
        return loginWrongPassword;
    }

    public Counter getLoginUserBanned() {
        return loginUserBanned;
    }

    public Counter getLoginTokenError() {
        return loginTokenError;
    }

    public Counter getLoginSuccess() {
        return loginSuccess;
    }

    public Counter getRegisterRpcError() {
        return registerRpcError;
    }

    public Counter getRegisterSuccess() {
        return registerSuccess;
    }
}
