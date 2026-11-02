package com.rag.user.interceptor;

import java.util.Set;

/**
 * <p><b>user 域的 JWT 路径判据</b> —— <b>Filter 与 Interceptor 共用同一份</b>。
 *
 * <h2>为什么必须只有一份</h2>
 * <p>B 侧把 JWT <b>验签</b>拆成两段：验签在 Servlet Filter（3s 预算<b>之外</b>），
 * 黑名单与响应在 HandlerInterceptor（3s 预算<b>之内</b>）⇒ 两段若各写一份路径判据，
 * 就会出现「Filter 判为受保护、Interceptor 判为放行」的缝隙：
 * <ul>
 *   <li>后者（Interceptor 放行）⇒ <b>鉴权被绕过</b> ⇒ 安全漏洞；</li>
 *   <li>前者（Interceptor 没拿到 Filter 的结果）⇒ 落进 interceptor 的兜底分支
 *       ⇒ 验签退回 3s 预算之内 ⇒ B 侧 {@code DRIFT-USERAPI-009②} 静默不闭环。</li>
 * </ul>
 * ⇒ 判据收敛到这一个类，两段都调它 ⇒ 两个缝隙同时消失。
 *
 * <h2>B 出处与 Go 侧依据</h2>
 * <pre>
 *   B: service/users/user/user-api/src/main/java/com/rag/users/user/api/auth/JwtAuthPaths.java
 *      （public = /user/v1/register ＋ /user/v1/login；protected = /user/v1 前缀）
 * </pre>
 *
 * <p><b>L1 契约：public 路径集合与 protected 前缀逐条一致</b>：
 * <ul>
 *   <li>{@code public} = {@code /user/v1/register} ＋ {@code /user/v1/login}（<b>只有这两个</b>）</li>
 *   <li>{@code protected 前缀} = {@code /user/v1}</li>
 * </ul>
 *
 * <p><b>语义等价论证</b>：B 侧用 go-zero 的「注册时挂不挂中间件」表达 public/protected，
 * C 侧用「路径前缀 ＋ 白名单」表达 ⇒ <b>表达层差异，语义等价</b>（集合逐条一致）。
 */
public final class JwtAuthPaths {

    /**
     * public 路径集合（L1 · B 出处：{@code JwtAuthPaths.java:40-42}）。
     *
     * <p>⚠️ <b>register 与 login 是 public</b>，其余 7 个端点全部受保护。
     */
    public static final Set<String> PUBLIC_PATHS = Set.of(
            "/user/v1/register", "/user/v1/login"
    );

    /**
     * 受保护路径的共同前缀 —— 与 {@code WebMvcConfig} 注册的 {@code /user/v1/**} 对齐。
     *
     * <p>（B 出处：{@code JwtAuthPaths.java:48}）
     */
    private static final String PROTECTED_PREFIX = "/user/v1";

    private JwtAuthPaths() {
    }

    /**
     * 是否 public（不挂 JWT，直接放行）。
     *
     * @param uri 请求路径
     */
    public static boolean isPublic(String uri) {
        return uri != null && PUBLIC_PATHS.contains(uri);
    }

    /**
     * 是否受保护（要验签）。
     *
     * <p>⚠️ <b>这里必须与 {@code WebMvcConfig} 注册的 {@code /user/v1/**} 覆盖同一集合</b>：
     * 少覆盖 ⇒ interceptor 拿不到 Filter 的验签结果（落兜底 ⇒ ② 不闭环）；
     * 多覆盖 ⇒ 只会多做一次无用验签，<b>不构成鉴权绕过</b>（拦截器仍按自己的判据放行）。
     * ⇒ 判据取 {@code /user/v1} 本身 ＋ {@code /user/v1/} 前缀 ⇒ 是 {@code /user/v1/**} 的<b>超集</b>
     * ⇒ 安全侧对齐。
     *
     * @param uri 请求路径
     */
    public static boolean isProtected(String uri) {
        if (uri == null) {
            return false;
        }
        if (isPublic(uri)) {
            return false;
        }
        return uri.equals(PROTECTED_PREFIX) || uri.startsWith(PROTECTED_PREFIX + "/");
    }
}
