package com.rag.article.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.regex.Pattern;

/**
 * <p><b>职责</b>：article 域的 <b>api 侧</b>配置聚合点 —— Spring Boot {@code @ConfigurationProperties}
 * 绑定到 {@code article-api.*}；并在<b>启动期</b>强制 JWT 验签密钥非空且非未解析占位符。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/config/ArticleApiProperties.java}
 * （类声明 {@code :74-75} · 字段 {@code :77-78} · getter/setter {@code :80-86} ·
 * {@code verifyAccessSecret} {@code :132-136} · {@code requireSecret} {@code :145-158} ·
 * {@code UNRESOLVED_PLACEHOLDER} {@code :169} · {@code Auth} {@code :172-191}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.api.config} → {@code com.rag.article.config}（D7 包压平）；</li>
 *         <li><b>Lombok 化</b>：getter/setter 样板由 {@code @Getter @Setter} 生成
 *             （<b>展开块见下</b> ⇒ D18）。</li>
 *       </ul>
 *       ⇒ <b>字段名、守卫判据、异常文案一字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>配置前缀 {@code article-api} <b>逐字未动</b>；</li>
 *         <li><b>绑定路径必须是 {@code article-api.auth.access-secret}</b>（{@code auth} 这一层
 *             <b>不能少</b>）—— 因为字段是嵌套的 {@code Auth auth}（{@code @NestedConfigurationProperty}）
 *             ⇒ 少一层 ⇒ 绑定落空 ⇒ {@code auth.accessSecret} 恒为 {@code null}
 *             ⇒ ⇒ 守卫<b>无论环境变量设没设都会抛</b> ⇒ ⇒ 组件永远起不来；</li>
 *         <li>守卫的<b>两条</b>判据（空串/全空白 · 未解析占位符）逐字保留
 *             ⇒ ⇒ 「缺变量 / 空串 / 占位符原文 三条路都 fail-closed」这条承诺在本仓<b>同样成立</b>。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🔴 启动期强制：验签密钥不得为空、也不得是未解析的占位符</h2>
 *
 * <h3>为什么「去掉 yaml 默认值」<b>只堵住了一半</b></h3>
 * <p>yaml 里写的是 {@code ${USER_JWT_SECRET}}（<b>无默认值</b>）⇒ 但<b>实测</b>（真
 * {@code SpringApplication} + 真 ConfigData 流程）有<b>三条</b>都能悄悄起来：
 * <ol>
 *   <li><b>变量没设</b> ⇒ Spring 的 {@code @ConfigurationProperties} 绑定器<b>不报错</b>，
 *       而是把<b>占位符原文</b>（形如 {@code "${USER_JWT_SECRET}"}）<b>当成值绑上去</b>
 *       ⇒ ⇒ 应用<b>照常启动</b> ⇒ ⇒ 而这个「密钥」是<b>仓里公开可知的一串字符</b>
 *       ⇒ ⇒ 🔴 <b>比空密钥更危险</b>（HMAC 密钥公开 ⇒ 谁都能伪造 token）；</li>
 *   <li><b>变量设了但值是空串</b> ⇒ Spring 的 {@code PropertyPlaceholderHelper}
 *       会把<b>空串当合法值</b>（<b>不走默认值、也不报错</b>）⇒ ⇒ 属性绑成 {@code ""}
 *       ⇒ ⇒ 应用带着<b>空签名密钥</b>正常起来 ⇒ ⇒ 🔴 <b>token 可伪造</b>；</li>
 *   <li><b>变量设了但值是全空白</b> ⇒ 同样是一条<b>可被任何人算出</b>的 HMAC 密钥
 *       ⇒ ⇒ 与空串同等对待。</li>
 * </ol>
 * ⇒ ⇒ 「去掉 yaml 默认值」<b>只堵住了一半</b> ⇒ ⇒ <b>另一半必须由代码堵</b> ⇒ 即本类。
 *
 * <h3>Go 出处与语义等价论证</h3>
 * <p>Go 侧 {@code service/article/api/etc/article-api.yaml:27} 写的是<b>字面量</b>
 * ⇒ ⇒ <b>Go 侧同样有「公开默认密钥」这个问题</b> ⇒ ⇒ 本条属 <b>RAG 侧更严</b>的一侧
 * ⇒ ⇒ 登记为有意偏离（不是「RAG 抄错了 Go」）。
 * <p>语义等价论证：<b>变量一旦被提供</b>，本方法<b>不改动任何值</b> ⇒ 验签密钥与 Go 侧
 * <b>逐字节一致</b> ⇒ ⇒ 验签行为零变化（Go 侧消费点 =
 * {@code service/article/api/internal/handler/routes.go:42} 的
 * {@code rest.WithJwt(serverCtx.Config.Auth.AccessSecret)}）。
 *
 * <h3>为什么用 {@code @PostConstruct} 而不是 {@code @Validated + @NotBlank}</h3>
 * <p>后者要引入 {@code spring-boot-starter-validation} ⇒ ⇒ 本模块<b>零新增依赖</b>更稳；
 * 且这里的判据是「<b>不得为空、也不得是未解析的占位符</b>」而不是「长度/格式」
 * ⇒ 显式抛异常更直白。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * @ConfigurationProperties(prefix = "article-api")
 * public class ArticleApiProperties {
 *     @NestedConfigurationProperty
 *     private Auth auth = new Auth();
 *
 *     public Auth getAuth() { return auth; }
 *     public void setAuth(Auth auth) { this.auth = auth; }
 * }
 * }</pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "article-api")
public class ArticleApiProperties {

    @NestedConfigurationProperty
    private Auth auth = new Auth();

    /**
     * 🔴 <b>启动期强制：验签密钥不得为空、也不得是未解析的占位符</b>。
     *
     * <p>判据（<b>三条路都 fail-closed</b>）：
     * <ol>
     *   <li>{@code null} / 空串 / 全空白 ⇒ 不可用；</li>
     *   <li>整串就是一个未解析的 {@code ${...}} 占位符字面量 ⇒ 环境变量<b>根本没设</b> ⇒ 不可用。</li>
     * </ol>
     *
     * <p>⚠️ <b>为什么空串 / 占位符 ⇒ 启动失败（而不是「启动成功但验签永远失败」）</b>：
     * 「用空密钥签发的 token」与「用占位符原文当密钥」都<b>可被任何人算出来</b>
     * ⇒ ⇒ 两者都属于<b>静默的安全降级</b> ⇒ ⇒ 宁可起不来。
     *
     * <p><b>B 出处</b>：{@code .../api/config/ArticleApiProperties.java:132-136}（守卫本体）
     * ＋ {@code :145-158}（{@code requireSecret}）＋ {@code :169}（占位符正则）。
     */
    @PostConstruct
    void verifyAccessSecret() {
        String secret = auth == null ? null : auth.getAccessSecret();
        requireSecret(secret, "article-api.auth.access-secret", "USER_JWT_SECRET");
    }

    /**
     * 判据本体：<b>null / 空串 / 全空白 / 未解析的占位符字面量</b> ⇒ 不可用 ⇒ 抛。
     *
     * <p><b>B 出处</b>：{@code .../api/config/ArticleApiProperties.java:145-158}。
     *
     * @param secret       绑到的密钥值（可能是 {@code null}）
     * @param propertyPath 出问题的配置路径（写进报错 ⇒ 运维能直接定位）
     * @param envVar       对应的环境变量名（写进报错）
     */
    private static void requireSecret(String secret, String propertyPath, String envVar) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    propertyPath + " 不能为空："
                            + "请设置环境变量 " + envVar + "（与签发方 user-api 一致）"
                            + "—— 本模块只验签、不签发，空密钥会让任何人都能伪造 JWT");
        }
        if (UNRESOLVED_PLACEHOLDER.matcher(secret).matches()) {
            throw new IllegalStateException(
                    propertyPath + " 未配置（绑到了未解析的占位符字面量 " + secret + "）："
                            + "请设置环境变量 " + envVar + "（与签发方 user-api 一致）"
                            + "—— 本模块只验签、不签发，占位符原文是公开可知的「密钥」⇒ 任何人都能伪造 JWT");
        }
    }

    /**
     * 「整串<b>就是</b>一个未解析的 {@code ${...}} 占位符」⇒ 说明环境变量<b>根本没设</b>。
     *
     * <p>⚠️ <b>为什么必须专门识别它</b>：Spring 的 {@code @ConfigurationProperties} 绑定器遇到
     * <b>解析不出来</b>的占位符时，<b>不会抛异常</b>，而是把<b>占位符原文</b>当成值绑上去
     * ⇒ ⇒ 若只判「空 / 空白」，这一条就<b>漏过去了</b>
     * ⇒ ⇒ 「yaml 不给默认值」<b>不等于</b>「缺变量会启动失败」⇒ ⇒ 必须在这里显式堵。
     *
     * <p><b>B 出处</b>：{@code .../api/config/ArticleApiProperties.java:169}。
     */
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("^\\$\\{[^}]*\\}$");

    /**
     * 对应 Go {@code Config.Auth} 段。
     *
     * <p>⚠️ {@code accessExpire} 在本模块<b>不被读取</b>：Go 侧同样如此
     * （签发在 user-api / admin-api）。保留字段是为了让 yaml 与 Go 端 1:1 可对照。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class Auth {
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
    public static class Auth {

        private String accessSecret;

        private long accessExpire;
    }
}
