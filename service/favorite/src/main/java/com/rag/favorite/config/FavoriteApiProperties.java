package com.rag.favorite.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.regex.Pattern;

/**
 * <p><b>favorite-api 模块配置聚合点</b> —— Spring Boot {@code @ConfigurationProperties}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/config/FavoriteApiProperties.java:36-168}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <p>对应 Go: {@code service/favorite/api/etc/favorite.yaml} 的 {@code UserAuth} 段
 * （Go 侧由 {@code conf.MustLoad} 强转进 {@code internal/config/config.go} 的
 * {@code Config} struct：{@code rest.RestConf} + {@code UserAuth} + {@code FavoriteRpc} +
 * {@code BizRedis}）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：包名由 {@code com.rag.favorite.favorite.api.config}
 *       压平为 {@code com.rag.favorite.config}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 默认值、fail-closed 校验的判据逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <p><b>L1 外部契约冻结（默认值即 Go yaml 的字面量）</b>：
 * <table border="1">
 *   <caption>默认值对照</caption>
 *   <tr><th>字段</th><th>Go 值</th><th>出处</th></tr>
 *   <tr><td>{@code userAuth.accessSecret}</td>
 *       <td><b>36 字节 UUID</b>（Go 侧 yaml 的字面量 —— ⚠️ <b>值不贴在本仓</b>：
 *       它就是那把「公开的验签密钥」⇒ 🔴 <b>2026-10-29 起不再自带这个默认值</b>
 *       ⇒ 改由环境变量 {@code FAVORITE_JWT_SECRET} 提供
 *       ⇒ 缺变量 / 空串 / 未解析占位符 ⇒ <b>启动失败</b>）</td>
 *       <td>{@code favorite.yaml:11}</td></tr>
 *   <tr><td>{@code userAuth.accessExpire}</td>
 *       <td>{@code 2592000}（30 天）</td>
 *       <td>{@code favorite.yaml:12}</td></tr>
 * </table>
 *
 * <p><b>本模块只验签、不签发</b>：Go 侧 favorite api 没有 login / register 端点，
 * {@code accessSecret} 的唯一用途是 {@code rest.WithJwt} 的验签密钥
 * （{@code api/internal/handler/routes.go:57}）。
 * 签发方是 user-api —— <b>两边密钥必须一致</b>，否则验签必失败，
 * 表现为 <b>HTTP 401</b>。
 *
 * <p>{@code accessExpire} 在本模块<b>不被读取</b>：Go 侧同样如此 ——
 * {@code api/internal/config/config.go:13} 的 {@code AccessExpire} 没有任何 logic 读它
 * （签发在 user-api）。保留字段是为了让 yaml 与 Go 端 1:1 可对照。
 *
 * <p><b>为什么没有 FavoriteRpc / BizRedis 字段</b>：
 * <ul>
 *   <li>{@code FavoriteRpc zrpc.RpcClientConf} 在 Go 侧只是 {@code zrpc.MustNewClient} 的入参；
 *       Java 侧 {@code @GrpcClient("favorite-rpc")} + Nacos discovery 已经接管寻址，
 *       再放一份 yaml 字段就是<b>没有消费者的死配置</b>。</li>
 *   <li>{@code BizRedis} 在 Java 侧由 {@code spring.data.redis.*} 承载（Spring Boot 标准配置），
 *       再造一份自定义属性就是第二套 Redis 配置源。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "favorite-api")
public class FavoriteApiProperties {

    @NestedConfigurationProperty
    private UserAuth userAuth = new UserAuth();

    public UserAuth getUserAuth() {
        return userAuth;
    }

    public void setUserAuth(UserAuth userAuth) {
        this.userAuth = userAuth;
    }

    /**
     * 🔴 <b>启动期强制：验签密钥不得为空、也不得是未解析的占位符</b>
     * （B 侧 {@code FavoriteApiProperties.java:79-113} · 2026-10-29 · G3 阶段 1 · P0 #8；
     * 同轮补「未解析占位符」判据）。
     *
     * <h3>为什么这一条不能省（Spring 的坑 · <b>实测</b>）</h3>
     * <p>yaml 里写的是 {@code ${FAVORITE_JWT_SECRET}}（**无默认值**）⇒ 但<b>实测</b>（真
     * {@code SpringApplication} + 真 ConfigData 流程 ⇒ 见 B 侧
     * {@code FavoriteApiPropertiesSecretGuardTest#prodLikeStartupWithoutEnvVarFails}）
     * 有<b>两条</b>都能悄悄起来：
     * <ol>
     *   <li><b>变量没设</b> ⇒ Spring 的 {@code @ConfigurationProperties} 绑定器
     *       <b>不报错</b>，而是把<b>占位符原文</b>（形如 {@code "${FAVORITE_JWT_SECRET}"}）
     *       <b>当成值绑上去</b> ⇒ ⇒ 应用<b>照常启动</b>
     *       ⇒ ⇒ 而这个「密钥」是<b>仓里公开可知的一串字符</b>
     *       ⇒ ⇒ 🔴 <b>比空密钥更危险</b>（HMAC 密钥公开 ⇒ 谁都能伪造 token）；</li>
     *   <li><b>变量设了但值是空串</b> ⇒ Spring 的
     *       {@code PropertyPlaceholderHelper} 会把<b>空串当合法值</b>
     *       （<b>不走默认值、也不报错</b>）⇒ ⇒ 属性绑成 {@code ""}
     *       ⇒ ⇒ <b>应用带着空签名密钥正常起来</b> ⇒ ⇒ 🔴 <b>token 可伪造</b>。</li>
     * </ol>
     * ⇒ ⇒ 所以「去掉 yaml 默认值」<b>只堵住了一半</b> ⇒ ⇒ <b>另一半必须由代码堵</b> ⇒ 即本方法。
     *
     * <h3>为什么空串 / 占位符 ⇒ 启动失败（而不是「启动成功但验签永远失败」）</h3>
     * <p>「用空密钥签发的 token」是<b>可被任何人算出来的</b>（HMAC 空密钥），
     * 而「用占位符原文当密钥」是<b>公开可知</b>的 ⇒ ⇒ 两者都属于<b>静默的安全降级</b>
     * ⇒ ⇒ 宁可起不来。
     *
     * <h3>判据</h3>
     * <p><b>null / 空串 / 全空白 / 未解析的占位符字面量</b> ⇒ 不可用 ⇒ 抛
     * （实现见 {@link #requireSecret(String, String, String)} 与 {@link #UNRESOLVED_PLACEHOLDER}）。
     *
     * <h3>为什么用 {@code @PostConstruct} 而不是 {@code @Validated + @NotBlank}</h3>
     * <p>后者要引入 {@code spring-boot-starter-validation}（<b>新增依赖</b>）⇒ ⇒ 本模块
     * <b>零新增依赖</b>更稳；且这里的判据是「<b>不得为空、也不得是未解析的占位符</b>」
     * 而不是「长度/格式」⇒ 显式抛异常更直白。
     *
     * <h3>Go 出处</h3>
     * <p>Go 侧 {@code service/favorite/api/etc/favorite.yaml:11} 写的是<b>字面量</b>
     * ⇒ ⇒ <b>Go 侧同样有「公开默认密钥」这个问题</b> ⇒ ⇒ 本条属 <b>RAG 侧更严</b>的一侧
     * ⇒ ⇒ <b>登记为有意偏离</b>，不是「RAG 抄错了 Go」。
     * <p>语义等价论证：<b>变量一旦被提供</b>，本方法<b>不改动任何值</b> ⇒ 验签密钥与 Go 侧
     * <b>逐字节一致</b> ⇒ ⇒ 验签行为零变化（Go 侧消费点 =
     * {@code service/favorite/api/internal/handler/routes.go:57} 的
     * {@code rest.WithJwt(serverCtx.Config.UserAuth.AccessSecret)}）。
     */
    @PostConstruct
    void verifyAccessSecret() {
        String secret = userAuth == null ? null : userAuth.getAccessSecret();
        requireSecret(secret, "favorite-api.user-auth.access-secret", "FAVORITE_JWT_SECRET");
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
     * <p>⚠️ <b>为什么必须专门识别它（2026-10-29 实测）</b>：Spring 的
     * {@code @ConfigurationProperties} 绑定器遇到<b>解析不出来</b>的占位符时，
     * <b>不会抛异常</b>，而是把<b>占位符原文</b>当成值绑上去
     * ⇒ ⇒ 若只判「空 / 空白」，这一条就<b>漏过去了</b>
     * ⇒ ⇒ 「yaml 不给默认值」**不等于**「缺变量会启动失败」⇒ ⇒ 必须在这里显式堵。
     */
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("^\\$\\{[^}]*\\}$");

    /** 对应 Go {@code UserAuth} 段（{@code favorite.yaml:10-12}）。 */
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
