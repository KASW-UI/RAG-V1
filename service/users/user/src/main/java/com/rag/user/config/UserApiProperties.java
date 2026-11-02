package com.rag.user.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * <p><b>user 域 HTTP 层配置聚合点</b> —— Spring Boot {@code @ConfigurationProperties}。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/config/UserApiProperties.java}
 * （前缀 {@code user-api}，嵌套段 {@code user-auth}）。
 *
 * <p><b>L1 契约：配置键名逐条一致</b>
 * <pre>
 *   user-api:
 *     user-auth:
 *       access-secret: ${USER_JWT_SECRET}        ← 无默认值
 *       access-expire: ${USER_JWT_EXPIRE:2592000} ← 30 天
 * </pre>
 * ⇒ 键名 {@code user-api.user-auth.access-secret} / {@code access-expire} <b>一字不改</b>。
 *
 * <p><b>框架职责</b>：{@code @ConfigurationProperties} 把 yml 里的 {@code user-api.*} 段绑到本类；
 * {@code @NestedConfigurationProperty} 标记嵌套对象（{@code userAuth} 段）。
 *
 * <p><b>⚠️ 两处 fail-closed（与 B 逐条一致 · B 出处 {@code UserApiProperties.java:63-76}）</b>：
 * <ol>
 *   <li><b>空串</b> ⇒ {@code PropertyPlaceholderHelper} 把空串当合法值 ⇒ 属性绑成 {@code ""}
 *       ⇒ 应用带着<b>空签名密钥</b>正常起来 ⇒ <b>token 可伪造</b> ⇒ 必须判掉。</li>
 *   <li><b>未解析的占位符</b> ⇒ 绑上去的是<b>字面量</b> {@code "${USER_JWT_SECRET}"}
 *       （Spring 的占位符解析器 {@code ignoreUnresolvablePlaceholders = true} ⇒ 解析不出来时原样留下）
 *       ⇒ 只判「null/空串」<b>堵不住这条路</b> ⇒ 必须显式拒绝「仍是占位符」的值。</li>
 * </ol>
 * ⇒ ⇒ <b>空串 / 占位符 ⇒ 启动失败</b>（而不是「启动成功但验签永远失败」）⇒ 因为这两种值都可被任何人算出来。
 *
 * <p>⚠️ <b>Go 侧对照（§3.1）</b>：Go 侧写的是<b>字面量</b>（公开在仓里）⇒ ⇒ 本条属 <b>RAG 侧更严</b>的一侧
 * ⇒ ⇒ 登记为<b>有意偏离</b>（不是「RAG 抄错了 Go」）。
 */
@ConfigurationProperties(prefix = "user-api")
public class UserApiProperties {

    private String name = "user-api";

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
     * 🔴 <b>启动期强制：验签/签名密钥不得为空、不得是未解析的占位符</b>。
     *
     * <p><b>B 出处</b>：{@code UserApiProperties.java:63-76}（{@code @PostConstruct verifyAccessSecret}）。
     *
     * <p>⚠️ <b>为什么本方法在本仓依然必要</b>：C 侧把 api 与 rpc <b>合并成一个进程</b>（D1 + D7）
     * ⇒ 本模块<b>既签发</b>（登录）<b>又验签</b>（拦截器）⇒ 密钥为空 = 任何人可伪造 user token。
     */
    @PostConstruct
    void verifyAccessSecret() {
        String secret = userAuth == null ? null : userAuth.getAccessSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "user-api.user-auth.access-secret 不能为空：请设置环境变量 USER_JWT_SECRET（与 user-rpc 共享同一把签发密钥）"
                            + "—— 本模块既签发又验签，空密钥会让任何人都能伪造 JWT");
        }
        if (secret.contains("${")) {
            throw new IllegalStateException(
                    "user-api.user-auth.access-secret 仍是未解析的占位符（" + secret + "）：请设置环境变量 USER_JWT_SECRET"
                            + "—— 占位符字面量是公开在仓里的常量，拿它当密钥 = 密钥公开");
        }
    }

    /** {@code user-api.user-auth} 段。 */
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
