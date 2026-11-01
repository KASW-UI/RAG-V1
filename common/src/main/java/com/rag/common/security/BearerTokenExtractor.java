package com.rag.common.security;

import java.util.Locale;

/**
 * Authorization 头的 token 提取 —— Go 侧<b>两套互不相同</b>的取法的逐字等价实现。
 *
 * <p><b>为什么必须把两套都留成两个方法，而不是「顺手统一成一套」</b>：
 * Go 的鉴权链由两个中间件组成，它们对同一个 header 用了<b>不同</b>的取法，
 * 且这个差异在畸形输入下会真实分叉。两套都必须各自逐字对齐，
 * 合并即等于擅自修好 Go 的 bug —— 那会制造一处新的语义漂移。
 *
 * <p>抽到 users-common 而不是两个模块各写一份的原因很直接：
 * DRIFT-010 就是「admin 侧一份实现、user 侧另一份实现」各自演化出来的。
 * 鉴权是安全边界，同一个规则的第二份实现本身就是缺陷来源。
 *
 * <h3>用法约定（与 Go 调用点一一对应）</h3>
 * <ul>
 *   <li>{@link #tokenForSignatureVerification} —— 只用于 <b>验签</b>，
 *       对应 Go {@code rest.WithJwt → handler.Authorize →
 *       rest/token/tokenparser.go doParseToken → request.ParseFromRequest}</li>
 *   <li>{@link #trimBearerPrefix} —— 只用于 <b>黑名单 key</b>，
 *       对应 Go {@code CheckBlacklistMiddleware.Handle}</li>
 * </ul>
 *
 * <p><b>调用顺序同样是对齐的一部分</b>：Go 是「先验签、后黑名单」
 * （{@code rest/engine.go:101-113} bindRoute 先 appendAuthHandler 再 ThenFunc(route.Handler)，
 * 而 {@code rest/chain/chain.go} 的 Then = {@code m0(m1(...mN(h))} 使索引 0 最外层先执行；
 * 黑名单被 {@code rest/server.go:222-233} WithMiddleware 包进 route.Handler，
 * 它是 {@code ThenFunc} 的参数，比 chain 任何元素都内层）。
 */
public final class BearerTokenExtractor {

    /** Authorization 头里的 Bearer 前缀（含尾随空格）。 */
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * Go 的比较字面量<b>是大写</b>形式。
     *
     * <p>Go 原文是 {@code strings.ToUpper(tok[0:7]) == "BEARER "} —— 被比较的一侧做了 ToUpper，
     * 字面量一侧本来就是大写。若这里直接拿 {@link #BEARER_PREFIX}（{@code "Bearer "}）去比，
     * {@code "Bearer ".equals("BEARER ")} 恒为 false ⇒ 会把所有规范请求全部误拒为 401。
     * 该bug 由 {@code AdminApiMvcPipelineTest.validTokenReachesController} 当场抓出。
     */
    private static final String BEARER_PREFIX_UPPERCASE = "BEARER ";

    private BearerTokenExtractor() {}

    /**
     * 验签用 token —— 对应 Go {@code golang-jwt/jwt/v4@v4.5.2/request/oauth2.go:7-12}。
     *
     * <p>Go 原文：
     * <pre>
     * func stripBearerPrefixFromTokenString(tok string) (string, error) {
     *     if len(tok) &gt; 6 &amp;&amp; strings.ToUpper(tok[0:7]) == "BEARER " {
     *         return tok[7:], nil
     *     }
     *     return tok, nil
     * }
     * </pre>
     *
     * <p>三条必须逐条对齐的规则：
     * <ol>
     *   <li>判定是 {@code len(tok) > 6}（严格大于）。{@code "Bearer "} 正好 7 字符会被剥成空串；
     *       {@code "Bearer"} 6 字符不满足，原样当 token</li>
     *   <li>{@code strings.ToUpper} ⇒ 前缀<b>大小写不敏感</b>，
     *       {@code "bearer "} / {@code "BEARER "} 均被剥</li>
     *   <li>不匹配时<b>原样返回整串</b>，不抛错、不置空。
     *       这一条是 DRIFT-010 的根：若改用 {@code split(" ", 2).parts[1]}，
     *       {@code "XXBearer eyJ..."} 会变成用 {@code "eyJ..."} 验签而通过，
     *       Go 则用整串验签必然失败 ⇒ 构成注销绕过</li>
     * </ol>
     */
    public static String tokenForSignatureVerification(String authHeader) {
        // ⚠️⚠️ **2026-11-02：判定条件按 Go 修正（RAG 侧是 `> 7`，Go 是 `> 6`）**
        //   Go: len(tok) > 6 && strings.ToUpper(tok[0:7]) == "BEARER "
        //   ⇒ 「Bearer 」正好 7 字符 ⇒ Go **剥成空串**；RAG 的 `length() > 7` ⇒ 7 > 7 为假 ⇒ **不剥**。
        //   ⇒ 二者在「恰好 7 字符的头」上分叉（RAG 自己的 javadoc 描述的也是 Go 的 `> 6` ⇒ 属 RAG 侧移植偏差）。
        //   ⇒ 本仓按 **Go**（AGENTS.md §五.5：L1 契约层以 Go 为准；JWT 提取明确属 L1）。
        //   ⇒ 实践影响：两种写法都让这种畸形头**验签失败** ⇒ 无安全洞；差别只在「传给验签器的是哪个串」。
        if (authHeader.length() > BEARER_PREFIX.length() - 1
                && BEARER_PREFIX_UPPERCASE.equals(
                        authHeader.substring(0, BEARER_PREFIX.length()).toUpperCase(Locale.ROOT))) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return authHeader;
    }

    /**
     * 黑名单 key 用 token —— 对应 Go
     * {@code service/user/{user,admin}/api/internal/middleware/checkblacklistmiddleware.go} 的
     * {@code token := strings.TrimPrefix(authHeader, "Bearer ")}。
     *
     * <p><b>与 {@link #tokenForSignatureVerification} 的差异是 Go 自身的不一致，不是笔误</b>：
     * 这里是大小写<b>敏感</b>的精确前缀 {@code TrimPrefix}，同样在不匹配时原样返回整串。
     *
     * <p>后果举例（Go 的真实行为，勿"修正"）：
     * header 为 {@code "bearer eyJ..."} 时，验签用 {@code "eyJ..."}，
     * 黑名单查的却是整串 {@code "bearer eyJ..."} —— 两者本就查不到同一个 key。
     */
    public static String trimBearerPrefix(String authHeader) {
        return authHeader.startsWith(BEARER_PREFIX)
                ? authHeader.substring(BEARER_PREFIX.length())
                : authHeader;
    }
}