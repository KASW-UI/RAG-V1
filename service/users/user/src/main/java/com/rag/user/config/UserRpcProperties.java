package com.rag.user.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * <p><b>user 域 gRPC / 领域层配置聚合点</b> —— Spring Boot {@code @ConfigurationProperties}。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/config/UserRpcProperties.java}
 * （前缀 {@code user-rpc}，嵌套段 {@code user-auth}）。
 *
 * <p><b>L1 契约：配置键名逐条一致</b>
 * <pre>
 *   user-rpc:
 *     user-auth:
 *       access-secret: ${USER_JWT_SECRET}
 *       access-expire: ${USER_JWT_EXPIRE:2592000}
 * </pre>
 *
 * <p><b>⚠️ 与 {@link UserApiProperties} 的关系（D1 合并进程后的必然结果）</b>：
 * B 侧 api 与 rpc 是<b>两个进程</b> ⇒ 同一把密钥在<b>两份 yml</b> 里各写一遍
 * ⇒ C 侧合并成一个进程后，<b>两份配置仍然各存一份键</b>（L1 键名不可改）
 * ⇒ ⇒ <b>两份必须绑定到<b>同一个值</b></b> ⇒ yml 里两个键都指向<b>同一个环境变量</b>
 * {@code USER_JWT_SECRET} ⇒ 不可能只改一边而漏掉另一边。
 *
 * <p><b>⚠️ fail-closed 同 B</b>（B 出处 {@code UserRpcProperties.java:65-78}）——
 * 空串 / 未解析占位符 ⇒ 启动失败。⚠️ B 侧 user-rpc <b>只消费 {@code AccessExpire}</b>
 * （{@code internal/logic/logoutlogic.go:36}）<b>不消费 {@code AccessSecret}</b>
 * ⇒ ⇒ 本条的密钥校验属<b>纯 fail-closed 收紧</b>，不改变任何已存在的运行时取值。
 */
@ConfigurationProperties(prefix = "user-rpc")
public class UserRpcProperties {

    private String name = "user-rpc";

    @NestedConfigurationProperty
    private UserAuth userAuth = new UserAuth();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UserAuth getUserAuth() {
        return userAuth;
    }

    public void setUserAuth(UserAuth userAuth) {
        this.userAuth = userAuth;
    }

    /**
     * 🔴 <b>启动期强制：密钥不得为空、不得是未解析的占位符</b>。
     *
     * <p><b>B 出处</b>：{@code UserRpcProperties.java:65-78}。
     */
    @PostConstruct
    void verifyAccessSecret() {
        String secret = userAuth == null ? null : userAuth.getAccessSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "user-rpc.user-auth.access-secret 不能为空：请设置环境变量 USER_JWT_SECRET（与签发方 user-api 一致）"
                            + "—— 空密钥会让任何人都能伪造 user JWT");
        }
        if (secret.contains("${")) {
            throw new IllegalStateException(
                    "user-rpc.user-auth.access-secret 仍是未解析的占位符（" + secret + "）：请设置环境变量 USER_JWT_SECRET"
                            + "—— 占位符字面量是公开在仓里的常量，拿它当密钥 = 密钥公开");
        }
    }

    /** {@code user-rpc.user-auth} 段。 */
    public static class UserAuth {

        private String accessSecret;
        private long accessExpire;

        public String getAccessSecret() {
            return accessSecret;
        }

        public void setAccessSecret(String accessSecret) {
            this.accessSecret = accessSecret;
        }

        public long getAccessExpire() {
            return accessExpire;
        }

        public void setAccessExpire(long accessExpire) {
            this.accessExpire = accessExpire;
        }
    }
}
