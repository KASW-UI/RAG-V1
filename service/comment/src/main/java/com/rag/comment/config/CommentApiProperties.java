package com.rag.comment.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.regex.Pattern;

/**
 * <p><b>comment-api 侧配置</b> —— 对应 B 侧
 * {@code service/comment/comment-api/src/main/java/com/rag/comment/comment/api/config/CommentApiProperties.java:1-100}
 * （绑定前缀 {@code comment-api}）。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.api.config.CommentApiProperties}
 * ⇒ C 侧 {@code com.rag.comment.config.CommentApiProperties}。
 *
 * <h3>⚠️ {@code verifyAccessSecret} 的启动期校验是<b>安全闸</b>，不可省</h3>
 * <p>本模块<b>只验签、不签发</b> ⇒ 密钥为空或绑到未解析的占位符字面量时，
 * <b>任何人都能伪造 JWT</b> ⇒ 必须在启动期就炸掉，而不是等第一个请求进来。
 * <p>⇒ 两条拒绝条件：
 * <ol>
 *   <li>密钥为 {@code null} / 空白；</li>
 *   <li>密钥形如 {@code ${...}}（未解析的占位符原文是公开可知的「密钥」）。</li>
 * </ol>
 *
 * <h3>⚠️ C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：无 —— 本类<b>逐字照抄</b> B 侧。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 两条拒绝条件与两条异常文案逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 */
@ConfigurationProperties(prefix = "comment-api")
public class CommentApiProperties {

    /** 未解析占位符的字面量形态（形如 {@code ${ENV_VAR}}）。 */
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("^\\$\\{[^}]*\\}$");

    /** 令牌验签参数（B 侧同名嵌套类）。 */
    @NestedConfigurationProperty
    private UserAuth userAuth = new UserAuth();

    /** 域内直连目标（B 侧同名嵌套类）。 */
    @NestedConfigurationProperty
    private CommentRpc commentRpc = new CommentRpc();

    public UserAuth getUserAuth() {
        return userAuth;
    }

    public void setUserAuth(UserAuth userAuth) {
        this.userAuth = userAuth;
    }

    public CommentRpc getCommentRpc() {
        return commentRpc;
    }

    public void setCommentRpc(CommentRpc commentRpc) {
        this.commentRpc = commentRpc;
    }

    /**
     * 启动期密钥校验 —— 与 B 侧逐字一致。
     *
     * <p>⚠️ B 侧把校验放在 {@code @PostConstruct} ⇒ 发生在 Bean 初始化后、
     * 容器 refresh 完成前 ⇒ <b>启动即失败</b>，不会对外提供服务。
     */
    @PostConstruct
    void verifyAccessSecret() {
        String secret = userAuth == null ? null : userAuth.getAccessSecret();
        requireSecret(secret, "comment-api.user-auth.access-secret", "USER_JWT_SECRET");
    }

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
     * 令牌验签参数。
     *
     * <p>⚠️ {@code access-secret} 必须与签发方 {@code user-api} <b>同值</b>
     * （B 侧 {@code comment-api/src/main/resources/application.yml:94-95}：
     * {@code access-secret} + {@code access-expire = 31536000}）。
     */
    public static class UserAuth {

        /** JWT 验签密钥（须与签发方 user-api 同值）。 */
        private String accessSecret;

        /** 令牌有效期（秒）。 */
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

    /**
     * 域内直连目标。
     *
     * <p>⚠️ D2 之后域内不再走网络 ⇒ 该配置在 C 侧<b>只作为历史字段保留</b>
     * （B 侧的 {@code comment-api/…/client/CommentRpcClient} 整包已删除）。
     */
    public static class CommentRpc {

        /** 直连目标标识（B 侧遗留）。 */
        private String target;

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }
    }
}
