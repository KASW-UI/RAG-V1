package com.rag.user.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>🔴 密钥 fail-closed 守门</b> —— 缺变量 / 空串 / 未解析占位符，<b>一律启动失败</b>。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   B: service/users/user/user-api/src/main/java/com/rag/users/user/api/config/UserApiProperties.java:65-78
 *   B: service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/config/UserRpcProperties.java:65-78
 * </pre>
 *
 * <h2>⚠️ 为什么这条要守</h2>
 * <p>B 侧的 yml 写的是 {@code access-secret: ${USER_JWT_SECRET}}（<b>无明文默认值</b>）
 * ⇒ 环境变量没设时，Spring 绑上去的是<b>占位符字面量</b> {@code "${USER_JWT_SECRET}"}（实测口径见 B 侧 javadoc）
 * ⇒ ⇒ 只判「null / 空串」<b>堵不住</b>这条路
 * ⇒ ⇒ 而那个字面量是<b>公开在仓里的常量</b> ⇒ 拿它当 HMAC 密钥 = 等于把密钥公开
 * ⇒ ⇒ <b>必须显式拒绝「仍是占位符」的值</b>。
 *
 * <p>⇒ 副作用是：<b>缺变量 ⇒ 整个进程起不来</b>（而不是带着空密钥正常服务、token 可被伪造）。
 *
 * <h2>⚠️ 本测试只测「启动期守卫」，不测「密钥泄漏」</h2>
 * <p>密钥本身由环境变量提供 ⇒ <b>仓里永远不出现明文</b>（本仓的 {@code application.yml} 两个键
 * 都写 {@code ${USER_JWT_SECRET}}，无默认值）⇒ ⇒ 这条由
 * {@code UserNacosRegistrationWiringTest} 同款的「读 yml 断言」手法守。
 */
class UserApiPropertiesSecretGuardTest {

    private static final String VALID = "ae0536f9-6450-4606-8e13-5a19ed505da0";

    // ============== api 侧 ==============

    @Test
    @DisplayName("🔴 api 侧：access-secret 为 null ⇒ 启动失败")
    void apiNullSecretFailsFast() {
        UserApiProperties props = new UserApiProperties();
        props.getUserAuth().setAccessSecret(null);

        assertThatThrownBy(props::verifyAccessSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-secret 不能为空");
    }

    @Test
    @DisplayName("🔴 api 侧：access-secret 为空串 ⇒ 启动失败（Spring 会把空串当合法值绑上来）")
    void apiBlankSecretFailsFast() {
        UserApiProperties props = new UserApiProperties();
        props.getUserAuth().setAccessSecret("");

        assertThatThrownBy(props::verifyAccessSecret)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("🔴 api 侧：access-secret 仍是未解析占位符 ⇒ 启动失败（占位符字面量 = 公开的密钥）")
    void apiPlaceholderSecretFailsFast() {
        UserApiProperties props = new UserApiProperties();
        props.getUserAuth().setAccessSecret("${USER_JWT_SECRET}");

        assertThatThrownBy(props::verifyAccessSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未解析的占位符");
    }

    @Test
    @DisplayName("✅ api 侧：合法密钥 ⇒ 放行")
    void apiValidSecretPasses() {
        UserApiProperties props = new UserApiProperties();
        props.getUserAuth().setAccessSecret(VALID);

        assertThatCode(props::verifyAccessSecret).doesNotThrowAnyException();
    }

    // ============== rpc 侧 ==============

    @Test
    @DisplayName("🔴 rpc 侧：access-secret 为 null ⇒ 启动失败")
    void rpcNullSecretFailsFast() {
        UserRpcProperties props = new UserRpcProperties();
        props.getUserAuth().setAccessSecret(null);

        assertThatThrownBy(props::verifyAccessSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-secret 不能为空");
    }

    @Test
    @DisplayName("🔴 rpc 侧：access-secret 仍是未解析占位符 ⇒ 启动失败")
    void rpcPlaceholderSecretFailsFast() {
        UserRpcProperties props = new UserRpcProperties();
        props.getUserAuth().setAccessSecret("${USER_JWT_SECRET}");

        assertThatThrownBy(props::verifyAccessSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未解析的占位符");
    }

    @Test
    @DisplayName("✅ rpc 侧：合法密钥 ⇒ 放行")
    void rpcValidSecretPasses() {
        UserRpcProperties props = new UserRpcProperties();
        props.getUserAuth().setAccessSecret(VALID);

        assertThatCode(props::verifyAccessSecret).doesNotThrowAnyException();
    }
}
