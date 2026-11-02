package com.rag.admin.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * <p><b>职责</b>：admin 域 <b>业务/领域面</b>的配置聚合点 —— Spring Boot {@code @ConfigurationProperties}。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>包名</b>（{@code com.rag.users.admin.rpc.config} → {@code com.rag.admin.config}，
 *       D7 包压平）＋ <b>类名未动</b> ⇒ <b>字段、守卫、异常文案逐字照抄</b>。</li>
 *   <li><b>语义还一样吗</b>：一样。
 *       <ul>
 *         <li>配置前缀 {@code admin-rpc} <b>逐字未动</b> ⇒ {@code application.yml} 的
 *             {@code admin-rpc.admin-auth.*} / {@code admin-rpc.system.*} 键名不变；</li>
 *         <li>{@link #verifyAccessSecret()} 的<b>两条</b>守卫（空串 / 未解析占位符）逐字保留。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/config/AdminRpcProperties.java}。</li>
 * </ol>
 *
 * <p>对应 B 侧 service/user/admin/rpc/etc/admin.yaml 的 admin-rpc.* 段；
 * B 侧是 conf.MustLoad(...) 强转到 Config struct。
 *
 * <p>框架职责：
 * <ul>
 *   <li>{@code @ConfigurationProperties} 把 application.yaml 中 {@code admin-rpc.*} 段自动绑定到字段</li>
 *   <li>{@code @NestedConfigurationProperty} 用于嵌套对象（adminAuth / system 段）</li>
 *   <li>adminAuth.accessSecret / accessExpire 给 admin JWT 接入预留</li>
 *   <li>system.defaultPassword 给<b>重置普通用户密码</b>用
 *       （⚠️ <b>不是</b>默认 admin 账号的密码 —— 后者是硬编码常量 {@code admin}，
 *       见 {@code wiring/AdminSeederBootstrap}）</li>
 * </ul>
 *
 * <p>L1 外部契约冻结（admin 启动期）：
 * <ul>
 *   <li>{@link AdminAuth#getAccessSecret()} = B 侧 AdminAuth.AccessSecret（15 字节口令，B 出处
 *       {@code service/user/admin/rpc/etc/admin.yaml:28}）—— ⚠️ <b>RAG 侧不再有默认值</b>：
 *       只接受环境变量 {@code ADMIN_JWT_SECRET}（见 {@link #verifyAccessSecret()}）。</li>
 *   <li>{@link AdminAuth#getAccessExpire()} = B 侧 AdminAuth.AccessExpire = 2592000（30 天，与 user-rpc UserAuth 对齐）</li>
 *   <li>{@link System#getDefaultPassword()} = B 侧 System.DefaultPassword = "123456"（重置<b>普通用户</b>密码用）</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "admin-rpc")
public class AdminRpcProperties {

    @NestedConfigurationProperty
    private AdminAuth adminAuth = new AdminAuth();

    @NestedConfigurationProperty
    private System system = new System();

    public AdminAuth getAdminAuth() { return adminAuth; }
    public void setAdminAuth(AdminAuth adminAuth) { this.adminAuth = adminAuth; }
    public System getSystem() { return system; }
    public void setSystem(System system) { this.system = system; }

    /**
     * 🔴 <b>启动期强制：验签密钥不得为空、不得是未解析的占位符</b>（2026-10-29 · G1 阶段 1 · P0 #8）。
     *
     * <p>yaml 里写的是 {@code ${ADMIN_JWT_SECRET}}（<b>无默认值</b>）⇒ <b>两条路都要堵</b>：
     * <ol>
     *   <li><b>变量设了但值是空串</b> ⇒ Spring 的 {@code PropertyPlaceholderHelper}
     *       会把<b>空串当合法值</b>（<b>不走默认值、也不报错</b>）⇒ ⇒ 属性绑成 {@code ""}
     *       ⇒ ⇒ 应用带着<b>空签名密钥</b>正常起来 ⇒ ⇒ 🔴 <b>admin token 可伪造</b>（越权）。</li>
     *   <li><b>变量没设</b> ⇒ ⚠️ <b>绑上的不是 {@code null}，而是占位符字面量</b>
     *       （实测口径见下）⇒ ⇒ 只判「null/空串」<b>堵不住这条路</b> ⇒ ⇒ 必须显式拒绝「仍是占位符」的值。</li>
     * </ol>
     *
     * <p>🔴 <b>第 ② 条的框架实测口径（AGENTS.md §3.3.1：不靠记忆，读消费点）</b>：
     * <ul>
     *   <li>{@code SpringConfigurationPropertySource#getConfigurationProperty} 取值走
     *       {@code getPropertySourceProperty} → {@code propertySource.getProperty(name)}
     *       ⇒ 拿到的是<b>原始标量</b>（此处 = {@code "${ADMIN_JWT_SECRET}"}），<b>不在此处解析占位符</b>；</li>
     *   <li>随后的占位符解析由 {@code PropertySourcesPlaceholdersResolver} 承担，
     *       其 {@code PropertyPlaceholderHelper(..., ignoreUnresolvablePlaceholders = true)}
     *       ⇒ ⇒ <b>解析不出来时占位符原样留下</b> ⇒ ⇒ 最终绑进去的值是字面量 {@code "${ADMIN_JWT_SECRET}"}。</li>
     * </ul>
     * ⇒ ⇒ 「那个字面量」是<b>公开在仓里的常量</b> ⇒ 用它当 HMAC 密钥 = 等于把密钥公开 ⇒
     * ⇒ ⇒ <b>必须判掉</b>（否则「缺变量 ⇒ 启动失败」这条承诺是假的）。
     *
     * <p>⚠️ <b>B 侧对照（§3.1）</b>：B 侧 {@code service/user/admin/rpc/etc/admin.yaml:28} 写的是<b>字面量</b>
     * （公开在仓里）⇒ ⇒ 本条属 <b>RAG 侧更严</b>的一侧 ⇒ ⇒ 登记为有意偏离（不是「RAG 抄错了 B 侧」）。
     * <p>（补充事实：B 侧 admin-rpc <b>只消费 AccessExpire</b>（{@code internal/logic/logoutlogic.go:37}），
     * <b>不消费 AccessSecret</b> ⇒ ⇒ 本条是<b>纯 fail-closed 收紧</b>，不改变任何已存在的运行时取值。）
     */
    @PostConstruct
    void verifyAccessSecret() {
        String secret = adminAuth == null ? null : adminAuth.getAccessSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "admin-rpc.admin-auth.access-secret 不能为空：请设置环境变量 ADMIN_JWT_SECRET（与签发方 admin-api 一致）"
                            + "—— 空密钥会让任何人都能伪造 admin JWT（越权）");
        }
        if (secret.contains("${")) {
            throw new IllegalStateException(
                    "admin-rpc.admin-auth.access-secret 仍是未解析的占位符（" + secret + "）：请设置环境变量 ADMIN_JWT_SECRET"
                            + "—— 占位符字面量是公开在仓里的常量，拿它当密钥 = 密钥公开");
        }
    }

    /**
     * 对应 B 侧 AdminAuth：
     *   AccessSecret = B 侧 AdminAuth.AccessSecret（15 字节口令，B 侧 etc/admin.yaml:28；
     *     RAG 侧改由环境变量注入，**本仓不再持有该明文**）
     *   AccessExpire = 2592000   // 30 天，与 user-rpc UserAuth 对齐
     */
    public static class AdminAuth {
        private String accessSecret;
        private long accessExpire;

        public String getAccessSecret() { return accessSecret; }
        public void setAccessSecret(String accessSecret) { this.accessSecret = accessSecret; }
        public long getAccessExpire() { return accessExpire; }
        public void setAccessExpire(long accessExpire) { this.accessExpire = accessExpire; }
    }

    /**
     * 对应 B 侧 System：DefaultPassword 用于<b>重置普通用户密码</b>。
     * B 侧 etc/admin.yaml: System.DefaultPassword = "123456"。
     *
     * <p>⚠️ <b>不是</b>默认 admin 账号的密码 —— 后者是硬编码常量 {@code admin}
     * （见 {@code wiring/AdminSeederBootstrap} 与 B 侧的 DRIFT-001 修法）。
     */
    public static class System {
        private String defaultPassword;

        public String getDefaultPassword() { return defaultPassword; }
        public void setDefaultPassword(String defaultPassword) { this.defaultPassword = defaultPassword; }
    }
}
