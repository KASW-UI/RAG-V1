package com.rag.follow.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * <p><b>follow 域 HTTP 层配置聚合点</b> —— Spring Boot {@code @ConfigurationProperties}。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/config/FollowApiProperties.java:47-147}
 * （前缀 {@code follow-api}，嵌套段 {@code user-auth}）。
 *
 * <p><b>L1 契约：配置键名逐条一致</b>
 * <pre>
 *   follow-api:
 *     user-auth:
 *       access-secret: ${USER_JWT_SECRET}          ← 无默认值（见下方 fail-closed）
 *       access-expire: ${USER_JWT_EXPIRE:31536000}  ← 365 天（B 侧字面量）
 * </pre>
 * ⇒ 键名 {@code follow-api.user-auth.access-secret} / {@code follow-api.user-auth.access-expire}
 * <b>一字不改</b>。
 *
 * <p><b>框架职责</b>：{@code @ConfigurationProperties} 把 yml 里的 {@code follow-api.*} 段绑到本类；
 * {@code @NestedConfigurationProperty} 标记嵌套对象（{@code userAuth} 段）。
 *
 * <h3>⚠️ 只迁<b>有消费者</b>的段</h3>
 * <ul>
 *   <li>{@code UserAuth.AccessSecret} / {@code UserAuth.AccessExpire} ⇒ <b>本类</b>
 *       （前者是验签密钥，后者与 B 侧一样在本模块<b>不被读取</b>，保留是为了与 yaml 1:1 可对照）；</li>
 *   <li>{@code BizRedis} ⇒ <b>不</b>在本类：它是纯连接参数 ⇒ 落 {@code spring.data.redis.*}
 *       （Spring Boot 的标准连接配置）；</li>
 *   <li>{@code FollowRpc} ⇒ <b>不</b>在本类：它是寻址 ⇒ 落 {@code grpc.client.*}
 *       （并由域内 {@code FollowRpcProperties} 承载）。</li>
 * </ul>
 *
 * <h3>⚠️ fail-closed：密钥不得为空、也不得是未解析的占位符</h3>
 * <p>B 侧 {@code FollowApiProperties.java:97-112} 的 {@code @PostConstruct verifyAccessSecret}
 * —— 逐条照抄。B 出处与理由见本方法自己的 javadoc。
 *
 * <p><b>⚠️ Go 侧对照（§3.1）</b>：B 侧写的是<b>字面量</b>（公开在仓里）
 * ⇒ ⇒ 本条属 <b>C 侧更严</b>的一侧 ⇒ ⇒ 登记为<b>有意偏离</b>（不是「C 侧抄错了 B 侧」）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class FollowApiProperties {
 *     private UserAuth userAuth = new UserAuth();
 *
 *     public UserAuth getUserAuth() { return userAuth; }
 *     public void setUserAuth(UserAuth userAuth) { this.userAuth = userAuth; }
 *
 *     @PostConstruct
 *     void verifyAccessSecret() {
 *         String secret = userAuth == null ? null : userAuth.getAccessSecret();
 *         if (secret == null || secret.isBlank()) { throw new IllegalStateException("..."); }
 *         if (UNRESOLVED_PLACEHOLDER.matcher(secret).matches()) { throw new IllegalStateException("..."); }
 *     }
 * }
 * }</pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "follow-api")
public class FollowApiProperties {

    @NestedConfigurationProperty
    private UserAuth userAuth = new UserAuth();

    /**
     * 🔴 <b>启动期强制：验签密钥不得为空、不得是未解析的占位符</b>。
     *
     * <p><b>B 出处</b>：{@code FollowApiProperties.java:97-112}（{@code @PostConstruct verifyAccessSecret}）。
     *
     * <h3>⚠️ 为什么这条不能省（Spring 的坑）</h3>
     * <p>yml 里写的是 {@code ${USER_JWT_SECRET}}（<b>无默认值</b>）⇒ 但<b>实测</b>有<b>两条</b>
     * 都能悄悄起来：
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
     * ⇒ ⇒ 所以「去掉 yml 默认值」<b>只堵住了一半</b> ⇒ ⇒ <b>另一半必须由代码堵</b> ⇒ 即本方法。
     * <p>⚠️ <b>空串 ⇒ 启动失败</b>（而不是「启动成功但验签永远失败」）⇒ 因为
     * 「用空密钥签发的 token」是<b>可被任何人算出来的</b>（HMAC 空密钥）⇒ ⇒ 属于<b>静默的安全降级</b>。
     *
     * <h3>⚠️ 为什么用 {@code @PostConstruct} 而不是 {@code @Validated + @NotBlank}</h3>
     * <p>后者要引入 {@code spring-boot-starter-validation} 作为<b>校验规则</b>的额外依赖，
     * 且这里的判据是「<b>不得为空、也不得是未解析的占位符</b>」而不是「长度/格式」
     * ⇒ ⇒ 显式抛异常更直白（本域的 pom 已因 DTO 的 Bean Validation 引入 validation starter，
     * 但本条判据与 Bean Validation 的标准约束<b>不是一回事</b> ⇒ 不套用）。
     *
     * <h3>语义等价论证</h3>
     * <p>B 侧 {@code service/follow/api/etc/followcenter.yaml:11} 写的是<b>字面量</b>
     * （36 字节 UUID）⇒ ⇒ <b>B 侧同样有「公开默认密钥」这个问题</b>
     * ⇒ ⇒ 本条属 <b>C 侧更严</b>的一侧 ⇒ ⇒ <b>登记为有意偏离</b>。
     * <p><b>语义等价</b>：<b>变量一旦被提供</b>，本方法不改动任何值 ⇒ 验签密钥与 B 侧
     * <b>逐字节一致</b> ⇒ ⇒ 验签行为零变化（B 侧消费点 = B 侧 {@code routes.go:62} 的
     * {@code rest.WithJwt(serverCtx.Config.UserAuth.AccessSecret)}）。
     */
    @PostConstruct
    void verifyAccessSecret() {
        String secret = userAuth == null ? null : userAuth.getAccessSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "follow-api.user-auth.access-secret 不能为空："
                            + "请设置环境变量 USER_JWT_SECRET（与签发方 user-api 一致）"
                            + "—— 本模块只验签、不签发，空密钥会让任何人都能伪造 JWT");
        }
        if (UNRESOLVED_PLACEHOLDER.matcher(secret).matches()) {
            throw new IllegalStateException(
                    "follow-api.user-auth.access-secret 未配置（绑到了未解析的占位符字面量 " + secret + "）："
                            + "请设置环境变量 USER_JWT_SECRET（与签发方 user-api 一致）"
                            + "—— 本模块只验签、不签发，占位符原文是公开可知的「密钥」⇒ 任何人都能伪造 JWT");
        }
    }

    /**
     * 「整串<b>就是</b>一个未解析的 {@code ${...}} 占位符」⇒ 说明环境变量<b>根本没设</b>。
     *
     * <p>⚠️ <b>为什么必须专门识别它</b>：Spring 的 {@code @ConfigurationProperties} 绑定器遇到
     * <b>解析不出来</b>的占位符时，<b>不会抛异常</b>，而是把<b>占位符原文</b>当成值绑上去
     * ⇒ ⇒ 若只判「空 / 空白」，这一条就<b>漏过去了</b>
     * ⇒ ⇒ 「yml 不给默认值」不等于「缺变量会启动失败」⇒ ⇒ 必须在这里显式堵。
     */
    private static final java.util.regex.Pattern UNRESOLVED_PLACEHOLDER =
            java.util.regex.Pattern.compile("^\\$\\{[^}]*\\}$");

    /**
     * 对应 B 侧 {@code UserAuth} 段（B 侧 {@code FollowApiProperties.java:128-147}）。
     *
     * <p>⚠️ <b>{@code accessExpire} 在本模块<b>不被读取</b></b>：B 侧同样如此 ——
     * B 侧 api 侧的 {@code AccessExpire} 没有任何 logic 读它。
     * 保留字段是为了让 yml 与 B 端 1:1 可对照，<b>不做「顺手清理」</b>。
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
}
