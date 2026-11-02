package com.rag.user.interceptor;

/**
 * <p><b>一次 JWT 验签的结果</b> —— <b>数据载体</b>，不含行为。
 *
 * <h2>为什么要单独一个类型</h2>
 * <p>B 侧把验签从 {@code JwtAuthInterceptor}（HandlerInterceptor，3s 预算<b>之内</b>）
 * 搬到了 {@code GoStyleJwtAuthFilter}（Servlet Filter，3s 预算<b>之外</b>）
 * ⇒ 两段之间只能靠 request attribute 传递 ⇒ 需要一个明确的载体。
 *
 * <p>⚠️ <b>失败必须以「异常对象」的形式带过来，而不是「失败标志位」</b>：
 * B 侧原实现在 interceptor 里直接 {@code throw new AuthenticationException(...)}，
 * 由 {@code GlobalExceptionHandler}（{@code @ExceptionHandler}，只在 DispatcherServlet 内生效）
 * 渲染成 <b>401 + 完整信封</b>。
 * ⇒ 若把失败压成标志位、由 Filter 自己写响应，{@code @ExceptionHandler} 就<b>管不到</b>了
 * ⇒ ⇒ 401 的 body 会从「完整信封」退化成空 body ⇒ <b>推翻 B 侧的签字项</b>。
 * ⇒ ⇒ 本类型把<b>异常对象原样带过去</b>，由 interceptor 重新抛出 ⇒ 渲染路径与 B 侧<b>逐字一致</b>。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/auth/JwtVerificationResult.java}
 * （record，5 个分量，逐条一致）。
 *
 * @param uid            claim {@code userId} 的值；claim 不可用时为 {@code -1}
 * @param claimUsable    claim {@code userId} 是否是数字（决定是否写 request attribute {@code userId}）
 * @param signatureToken <b>验签用</b>的 token（{@code BearerTokenExtractor#tokenForSignatureVerification} 的产物）
 * @param trimmedToken   <b>黑名单 key 用</b>的 token（{@code BearerTokenExtractor#trimBearerPrefix} 的产物）
 *                       —— 两者<b>可以不同</b>（B 侧自身如此：非规范写法下两段取法不同）
 * @param failure        验签失败时的异常（{@code null} = 验签通过）
 */
public record JwtVerificationResult(
        long uid,
        boolean claimUsable,
        String signatureToken,
        String trimmedToken,
        RuntimeException failure
) {

    /** 验签通过。 */
    public static JwtVerificationResult ok(long uid,
                                           boolean claimUsable,
                                           String signatureToken,
                                           String trimmedToken) {
        return new JwtVerificationResult(uid, claimUsable, signatureToken, trimmedToken, null);
    }

    /** 验签失败 —— {@code failure} 必须非 null（会被 interceptor 原样重抛）。 */
    public static JwtVerificationResult failed(RuntimeException failure) {
        return new JwtVerificationResult(-1L, false, "", "", failure);
    }

    /** 验签是否失败。 */
    public boolean failed() {
        return failure != null;
    }
}
