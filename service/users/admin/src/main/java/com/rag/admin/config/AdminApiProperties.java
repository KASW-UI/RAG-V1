package com.rag.admin.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * <p><b>职责</b>：admin 域 <b>HTTP 面</b>的配置聚合点 —— Spring Boot {@code @ConfigurationProperties}。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>包名</b>（{@code com.rag.users.admin.api.config} → {@code com.rag.admin.config}，
 *       D7 包压平）＋ <b>类名未动</b> ⇒ <b>字段、守卫、异常文案逐字照抄</b>。</li>
 *   <li><b>语义还一样吗</b>：一样。
 *       <ul>
 *         <li>配置前缀 {@code admin-api} <b>逐字未动</b> ⇒ {@code application.yml} 的
 *             {@code admin-api.admin-auth.*} 两段键名不变（映射表 admin.md §6 · L1）；</li>
 *         <li>{@link #verifyAccessSecret()} 的<b>两条</b>守卫（空串 / 未解析占位符）逐字保留
 *             ⇒ ⇒ 「缺变量 ⇒ 启动失败」这条承诺在本仓<b>同样成立</b>。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/config/AdminApiProperties.java}。</li>
 * </ol>
 *
 * <p>对应 B 端 service/user/admin/api/etc/admincenter.yaml 的 admin-api.* 段。
 *
 * <p>L1 外部契约冻结：
 * <ul>
 *   <li>{@link AdminAuth#getAccessSecret()} = B 侧 AdminAuth.AccessSecret（15 字节口令，B 出处
 *       {@code service/user/admin/api/etc/admincenter.yaml:11}）—— ⚠️ <b>RAG 侧不再有默认值</b>：
 *       只接受环境变量 {@code ADMIN_JWT_SECRET}（见 {@link #verifyAccessSecret()}）。</li>
 *   <li>{@link AdminAuth#getAccessExpire()} 默认值 = B 侧 AdminAuth.AccessExpire = 2592000（30 天）</li>
 * </ul>
 *
 * <p>关键：JWT secret 必须与 admin-rpc 进程共享同一份密钥 —— 否则跨进程 JWT 校验会失败。
 * secret 来自 application.yml 的 {@code admin-api.admin-auth.access-secret}（env: {@code ADMIN_JWT_SECRET}）。
 */
@ConfigurationProperties(prefix = "admin-api")
public class AdminApiProperties {

    @NestedConfigurationProperty
    private AdminAuth adminAuth = new AdminAuth();

    public AdminAuth getAdminAuth() { return adminAuth; }
    public void setAdminAuth(AdminAuth adminAuth) { this.adminAuth = adminAuth; }

    /**
     * 🔴 <b>启动期强制：签名/验签密钥不得为空、不得是未解析的占位符</b>（2026-10-29 · G1 阶段 1 · P0 #8）。
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
     * <p>⚠️ <b>空串 / 占位符 ⇒ 启动失败</b>（而不是「启动成功但验签永远失败」）⇒ 因为这两种值
     * <b>都可被任何人算出来</b>（空 HMAC 密钥 / 公开字面量）⇒ ⇒ 属于<b>静默的安全降级</b>。
     *
     * <p>⚠️ <b>B 侧对照（§3.1）</b>：B 侧 {@code service/user/admin/api/etc/admincenter.yaml:11}
     * 写的是<b>字面量</b>（公开在仓里）⇒ ⇒ 本条属 <b>RAG 侧更严</b>的一侧
     * ⇒ ⇒ 登记为有意偏离（不是「RAG 抄错了 B 侧」）。
     */
    @PostConstruct
    void verifyAccessSecret() {
        String secret = adminAuth == null ? null : adminAuth.getAccessSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "admin-api.admin-auth.access-secret 不能为空：请设置环境变量 ADMIN_JWT_SECRET（与 admin-rpc 共享同一把密钥）"
                            + "—— 本模块既签发又验签，空密钥会让任何人都能伪造 admin JWT（越权）");
        }
        if (secret.contains("${")) {
            throw new IllegalStateException(
                    "admin-api.admin-auth.access-secret 仍是未解析的占位符（" + secret + "）：请设置环境变量 ADMIN_JWT_SECRET"
                            + "—— 占位符字面量是公开在仓里的常量，拿它当密钥 = 密钥公开");
        }
    }

    /**
     * 对应 B 侧 AdminAuth：
     *   AccessSecret = B 侧 AdminAuth.AccessSecret（15 字节口令，B 侧 etc/admincenter.yaml:11；
     *     RAG 侧改由环境变量注入，**本仓不再持有该明文**）
     *   AccessExpire = 2592000   // 30 天
     */
    public static class AdminAuth {
        private String accessSecret;
        private long accessExpire;

        public String getAccessSecret() { return accessSecret; }
        public void setAccessSecret(String accessSecret) { this.accessSecret = accessSecret; }
        public long getAccessExpire() { return accessExpire; }
        public void setAccessExpire(long accessExpire) { this.accessExpire = accessExpire; }
    }
}
