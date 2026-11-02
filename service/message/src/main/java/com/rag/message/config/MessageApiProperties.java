package com.rag.message.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.regex.Pattern;

/**
 * <p><b>message 域 HTTP 层配置聚合点</b> —— Spring Boot {@code @ConfigurationProperties}。
 *
 * <p><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/config/MessageApiProperties.java}
 * （B 侧类 javadoc {@code :7-40} · 前缀与两个嵌套段 {@code :41-48} ·
 * 手写 accessor {@code :50-64} · {@code @PostConstruct verifyAccessSecrets :90-94} ·
 * {@code verifyUserAccessSecret :124-127} · {@code verifyAdminAccessSecret :140-143} ·
 * {@code requireSecret :152-165} · {@code UNRESOLVED_PLACEHOLDER :177-178} ·
 * 内部类 {@code UserAuth :181-200} / {@code AdminAuth :203-222}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.message.message.api.config} →
 *       {@code com.rag.message.config}，D7 包压平）＋ <b>Lombok 化</b>
 *       （{@code @Getter} ＋ {@code @Setter} 替掉 B 侧 {@code :50-64} 与两个内部类的
 *       手写 accessor，「不用 Lombok 时的完整代码」见下方三处展开块）
 *       ＋ <b>去掉 {@code @Component}</b>（见下方「装配方式」小节）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>配置前缀 {@code message-api} 与两个嵌套段名 {@code userAuth} / {@code adminAuth}
 *             <b>逐字未动</b> ⇒ {@code application.yml} 的
 *             {@code message-api.user-auth.*} / {@code message-api.admin-auth.*} 两段键名不变（L1）；</li>
 *         <li>{@link #verifyAccessSecrets()} 的守卫链（空串 / 全空白 / 未解析占位符）
 *             <b>逐字照抄</b> ⇒ ⇒ 「缺变量 ⇒ 启动失败」这条承诺在本仓<b>同样成立</b>。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>装配方式（⚠️ 与 B 侧不同的一处，语义等价）</b>：
 * B 侧由 {@code MessageApiApplication} 上的 {@code @EnableConfigurationProperties(MessageApiProperties.class)}
 * 注册；本仓按 D7 合并为<b>一个</b>进程 ⇒ 由<b>应用入口类</b>统一注册
 * （C 侧 users 域用 {@code @EnableConfigurationProperties({...})}、
 * admin 域用 {@code @ConfigurationPropertiesScan("com.rag.admin.config")}，
 * message 域对应写法 = {@code @ConfigurationPropertiesScan("com.rag.message.config")}
 * —— 它一次覆盖本类与 {@link MessageProperties} 两个 {@code @ConfigurationProperties}）。
 * ⇒ ⇒ 本类<b>刻意不带 {@code @Component}</b>：若同时带 {@code @Component} 又被
 * {@code @ConfigurationPropertiesScan} 扫到 ⇒ 同一个类型注册<b>两个</b> bean
 * ⇒ 构造器注入时 {@code NoUniqueBeanDefinitionException}
 * （共享层 {@code ObservabilityProperties} 的类注释里有同一条纪律的记录）。
 *
 * <p><b>L1 外部契约冻结（默认值即 Go yaml 的字面量）</b>：
 * <table border="1">
 *   <caption>默认值对照</caption>
 *   <tr><th>字段</th><th>Go 值</th></tr>
 *   <tr><td>{@code userAuth.accessSecret}</td><td>{@code ae0536f9-6450-4606-8e13-5a19ed505da0}</td></tr>
 *   <tr><td>{@code userAuth.accessExpire}</td><td>{@code 31536000}（365 天，<b>注意与 user-api 的 30 天不同</b>）</td></tr>
 *   <tr><td>{@code adminAuth.accessSecret}</td><td>{@code yU-hCm)2*?NQmWG}</td></tr>
 *   <tr><td>{@code adminAuth.accessExpire}</td><td>{@code 2592000}（30 天）</td></tr>
 * </table>
 *
 * <p>🔴 <b>上表是「Go 值」，不是「RAG 侧的默认值」</b>（2026-10-29 · G2 阶段 1 · P0 #8）：
 * RAG 侧 {@code application.yml} 的两段 {@code access-secret}
 * <b>已去掉明文默认值</b>（修前是「环境变量名 + 冒号 + 明文默认值」的占位符写法，
 * 默认值即上表那两个<b>写在仓里的公开密钥</b>）⇒ ⇒ <b>缺变量 ⇒ 启动失败</b>
 * ⇒ ⇒ 部署方必须用环境变量提供 ⇒ ⇒ 上表两行只在<b>环境变量被提供时</b>才是本模块的取值
 * ⇒ ⇒ 详见 {@link #verifyUserAccessSecret()} / {@link #verifyAdminAccessSecret()}。
 *
 * <p><b>本模块只验签、不签发</b>：Go 侧 message api 没有 login / register 端点，
 * 两个 accessSecret 的用途是 {@code rest.WithJwt} 的验签密钥。
 * 签发方分别在 user-api（user 段）与 admin-api（admin 段）。
 * 两段密钥必须与签发方一致，否则验签必失败 —— 该失败表现为
 * <b>HTTP 401 + 空 body</b>（不是业务码），排查时先看这里。
 *
 * <p>{@code accessExpire} 在本模块<b>不被读取</b>：Go 侧同样如此 ——
 * {@code api/internal/config/config.go} 的 {@code AccessExpire} 没有任何 logic 读它
 * （签发在别的服务）。保留字段是为了让 yaml 与 Go 端 1:1 可对照，不做「顺手清理」。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class MessageApiProperties {
 *     private UserAuth userAuth = new UserAuth();
 *     private AdminAuth adminAuth = new AdminAuth();
 *
 *     public UserAuth getUserAuth() { return userAuth; }
 *     public void setUserAuth(UserAuth userAuth) { this.userAuth = userAuth; }
 *     public AdminAuth getAdminAuth() { return adminAuth; }
 *     public void setAdminAuth(AdminAuth adminAuth) { this.adminAuth = adminAuth; }
 * }
 * }</pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "message-api")
public class MessageApiProperties {

    @NestedConfigurationProperty
    private UserAuth userAuth = new UserAuth();

    @NestedConfigurationProperty
    private AdminAuth adminAuth = new AdminAuth();

    /**
     * 🔴 **启动期强制：两段验签密钥都不得为空**（2026-10-29 · G2 阶段 1 · P0 #8）。
     *
     * <h3>为什么本模块需要「一个入口 + 逐段校验」</h3>
     * <p>本模块有<b>两段</b>验签密钥（user / admin），它们是两个<b>独立</b>的
     * {@code @NestedConfigurationProperty}（{@code userAuth} / {@code adminAuth}）
     * ⇒ ⇒ 绑定期<b>各自独立</b> ⇒ ⇒ 一段有值不代表另一段有值
     * ⇒ ⇒ 必须<b>逐段</b>校验（一条守卫覆盖两段 ⇒ 出错时无法指出是哪一段）。
     * <p>⚠️ 但**不能**写成「两个 {@code @PostConstruct} 方法」⇒ 因为
     * <b>同一个 bean 内多个 {@code @PostConstruct} 的执行顺序不由 JVM 规范保证</b>
     * ⇒ ⇒ 实测两次运行分别先抛 user 段与 admin 段的错 ⇒ ⇒ **报错内容不确定**
     * ⇒ ⇒ 改成<b>一个</b> {@code @PostConstruct} 入口 + <b>顺序固定</b>的逐段校验
     * ⇒ ⇒ 报错永远先指 user 段（user 段 OK 时才轮到 admin 段）。
     *
     * <h3>Go 出处与语义等价论证</h3>
     * <p>Go 侧 {@code service/message/api/etc/messagecenter.yaml:11}（user 段）与
     * {@code :15}（admin 段）写的都是<b>字面量</b> ⇒ ⇒ <b>Go 侧同样有「公开默认密钥」这个问题</b>
     * ⇒ ⇒ 本条属 <b>RAG 侧更严</b>的一侧（AGENTS.md §0 的共性：「RAG 做了更严/更安全的那一侧」
     * ⇒ 对拍看到它不同<b>先确认是不是签字项</b>）⇒ ⇒ <b>登记为有意偏离</b>，不是「RAG 抄错了 Go」。
     * <p>语义等价论证：<b>变量一旦被提供</b>，本方法不改动任何值 ⇒ 验签密钥与 Go 侧<b>逐字节一致</b>
     * ⇒ ⇒ 验签行为零变化（Go 侧消费点 = {@code service/message/api/internal/handler/routes.go:60}
     * 的 {@code rest.WithJwt(serverCtx.Config.UserAuth.AccessSecret)} 与
     * {@code :92} 的 {@code rest.WithJwt(serverCtx.Config.AdminAuth.AccessSecret)}）。
     */
    @PostConstruct
    void verifyAccessSecrets() {
        verifyUserAccessSecret();
        verifyAdminAccessSecret();
    }

    /**
     * user 段校验（由 {@link #verifyAccessSecrets()} <b>按序调用</b>，不单独挂 {@code @PostConstruct}
     * ⇒ 目的是让「两段都坏」时的报错<b>顺序确定</b>）。
     *
     * <h3>为什么这一条不能省（Spring 的坑）</h3>
     * <p>yaml 里写的是 {@code ${USER_JWT_SECRET}}（**无默认值**）⇒ 但**实测**（2026-10-29，
     * 真 {@code SpringApplication} + 真 ConfigData 流程，见
     * {@code MessageApiPropertiesSecretGuardTest#prodLikeStartupWithoutEnvVarFails}）
     * 有<b>两条</b>都能悄悄起来：
     * <ol>
     *   <li><b>变量没设</b> ⇒ Spring 的 {@code @ConfigurationProperties} 绑定器
     *       <b>不报错</b>，而是把<b>占位符原文</b> {@code "${USER_JWT_SECRET}"}
     *       <b>当成值绑上去</b> ⇒ ⇒ 应用<b>照常启动</b>，且这个「密钥」是<b>公开可知的字面量</b>
     *       ⇒ ⇒ 🔴 比空密钥更危险（HMAC 密钥 = 仓里公开的一串字符 ⇒ 谁都能伪造 token）；</li>
     *   <li><b>变量设了但值是空串</b> ⇒ Spring 的
     *       {@code PropertyPlaceholderHelper} 会把<b>空串当合法值</b>
     *       （<b>不走默认值、也不报错</b>）⇒ ⇒ 属性绑成 {@code ""}
     *       ⇒ ⇒ <b>应用带着空签名密钥正常起来</b> ⇒ ⇒ 🔴 <b>token 可伪造</b>。</li>
     * </ol>
     * ⇒ ⇒ 所以「去掉 yaml 默认值」<b>只堵住了一半</b> ⇒ ⇒ <b>另一半必须由代码堵</b> ⇒ 即本方法。
     * <p>⚠️ <b>空串 ⇒ 启动失败</b>（而不是「启动成功但验签永远失败」）⇒ 因为
     * 「用空密钥签发的 token」是<b>可被任何人算出来的</b>（HMAC 空密钥）⇒ ⇒ 属于<b>静默的安全降级</b>。
     *
     * <h3>为什么用 {@code @PostConstruct} 而不是 {@code @Validated + @NotBlank}</h3>
     * <p>后者要引入 {@code spring-boot-starter-validation}（<b>新增依赖</b>）⇒ ⇒ 本模块
     * <b>零新增依赖</b>更稳；且这里的判据是「<b>不得为空、也不得是未解析的占位符</b>」
     * 而不是「长度/格式」⇒ 显式抛异常更直白。
     */
    void verifyUserAccessSecret() {
        requireSecret(userAuth == null ? null : userAuth.getAccessSecret(),
                "message-api.user-auth.access-secret", "USER_JWT_SECRET");
    }

    /**
     * admin 段校验（由 {@link #verifyAccessSecrets()} <b>按序调用</b>，不单独挂 {@code @PostConstruct}
     * ⇒ 目的是让「两段都坏」时的报错<b>顺序确定</b>）。
     *
     * <h3>Go 出处与语义等价论证</h3>
     * <p>Go 侧 {@code service/message/api/etc/messagecenter.yaml:15} 写的是<b>字面量</b>
     * （15 字节）⇒ ⇒ 同属「<b>RAG 更严</b>」的一侧 ⇒ ⇒ <b>登记为有意偏离</b>。
     * <p>语义等价论证：<b>变量一旦被提供</b>，本方法不改动任何值 ⇒ 验签密钥与 Go 侧<b>逐字节一致</b>
     * ⇒ ⇒ 验签行为零变化（Go 侧消费点 = {@code service/message/api/internal/handler/routes.go:92}
     * 的 {@code rest.WithJwt(serverCtx.Config.AdminAuth.AccessSecret)}）。
     */
    void verifyAdminAccessSecret() {
        requireSecret(adminAuth == null ? null : adminAuth.getAccessSecret(),
                "message-api.admin-auth.access-secret", "ADMIN_JWT_SECRET");
    }

    /**
     * 判据本体：<b>null / 空串 / 全空白 / 未解析的占位符字面量</b> ⇒ 不可用 ⇒ 抛。
     *
     * @param secret       绑到的密钥值（可能是 {@code null}）
     * @param propertyPath 出问题的配置路径（写进报错 ⇒ 运维能直接定位）
     * @param envVar       对应的环境变量名（写进报错）
     */
    private static void requireSecret(String secret, String propertyPath, String envVar) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    propertyPath + " 不能为空："
                            + "请设置环境变量 " + envVar + "（与签发方一致）"
                            + "—— 本模块只验签、不签发，空密钥会让任何人都能伪造 JWT");
        }
        if (UNRESOLVED_PLACEHOLDER.matcher(secret).matches()) {
            throw new IllegalStateException(
                    propertyPath + " 未配置（绑到了未解析的占位符字面量 " + secret + "）："
                            + "请设置环境变量 " + envVar + "（与签发方一致）"
                            + "—— 本模块只验签、不签发，占位符原文是公开可知的「密钥」⇒ 任何人都能伪造 JWT");
        }
    }

    /**
     * 「整串<b>就是</b>一个未解析的 {@code ${...}} 占位符」⇒ 说明环境变量<b>根本没设</b>。
     *
     * <p>⚠️ <b>为什么必须专门识别它（2026-10-29 实测）</b>：Spring 的
     * {@code @ConfigurationProperties} 绑定器遇到<b>解析不出来</b>的占位符时，
     * <b>不会抛异常</b>，而是把<b>占位符原文</b>当成值绑上去
     * （实测：真 {@code SpringApplication} 启动 ⇒ {@code accessSecret} = {@code "${USER_JWT_SECRET}"}，
     * <b>应用照常启动</b>）⇒ ⇒ 若只判「空 / 空白」，这一条就<b>漏过去了</b>
     * ⇒ ⇒ 「yaml 不给默认值」不等于「缺变量会启动失败」⇒ ⇒ 必须在这里显式堵。
     */
    private static final Pattern UNRESOLVED_PLACEHOLDER =
            Pattern.compile("^\\$\\{[^}]*\\}$");

    /**
     * 对应 Go {@code UserAuth} 段。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class UserAuth {
     *     private String accessSecret;
     *     private long accessExpire;
     *
     *     public String getAccessSecret() { return accessSecret; }
     *     public void setAccessSecret(String accessSecret) { this.accessSecret = accessSecret; }
     *     public long getAccessExpire() { return accessExpire; }
     *     public void setAccessExpire(long accessExpire) { this.accessExpire = accessExpire; }
     * }
     * }</pre>
     */
    @Getter
    @Setter
    public static class UserAuth {
        private String accessSecret;
        private long accessExpire;
    }

    /**
     * 对应 Go {@code AdminAuth} 段。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class AdminAuth {
     *     private String accessSecret;
     *     private long accessExpire;
     *
     *     public String getAccessSecret() { return accessSecret; }
     *     public void setAccessSecret(String accessSecret) { this.accessSecret = accessSecret; }
     *     public long getAccessExpire() { return accessExpire; }
     *     public void setAccessExpire(long accessExpire) { this.accessExpire = accessExpire; }
     * }
     * }</pre>
     */
    @Getter
    @Setter
    public static class AdminAuth {
        private String accessSecret;
        private long accessExpire;
    }
}
