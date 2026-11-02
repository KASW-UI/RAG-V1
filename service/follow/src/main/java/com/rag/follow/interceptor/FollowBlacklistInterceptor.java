package com.rag.follow.interceptor;

import com.rag.common.security.BearerTokenExtractor;
import com.rag.follow.contract.ErrCodes;
import com.rag.follow.contract.ErrMsg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * <p><b>follow 域的 JWT 黑名单闸门</b> —— 对应 B 侧
 * {@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/interceptor/FollowBlacklistInterceptor.java:91-223}
 * 的等价物（逐条照抄）。
 *
 * <h2>B 出处（逐字，已读）</h2>
 * <pre>
 * func (m *CheckBlacklistMiddleware) Handle(next http.HandlerFunc) http.HandlerFunc {
 *     return func(w http.ResponseWriter, r *http.Request) {
 *         authHeader := r.Header.Get("Authorization")
 *         if authHeader == "" { next(w, r); return }
 *
 *         token := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
 *         if token == "" { next(w, r); return }
 *
 *         blacklistKey := fmt.Sprintf("user:jwt_blacklist:%s", token)
 *         exists, err := m.Redis.ExistsCtx(r.Context(), blacklistKey)
 *         if err == nil &amp;&amp; exists {
 *             w.Header().Set("Content-Type", "application/json;charset=utf-8")
 *             w.WriteHeader(http.StatusUnauthorized)
 *             _, _ = w.Write([]byte(fmt.Sprintf(
 *                 `{"code":%d,"msg":"%s"}`,
 *                 followcommon.ErrorUnauthorized,
 *                 followcommon.GetErrMsg(followcommon.ErrorUnauthorized),
 *             )))
 *             return
 *         }
 *         next(w, r)
 *     }
 * }
 * </pre>
 *
 * <h3>⚠️ 与 {@link FollowJwtAuthInterceptor} 的 401 body <b>形状不同</b>（不要合并）</h3>
 * <table border="1">
 *   <caption>本域两条 401 路径</caption>
 *   <tr><th>路径</th><th>状态码</th><th>Content-Type</th><th>body</th></tr>
 *   <tr>
 *     <td>验签失败 / 头缺失 / token 为空</td><td>401</td><td><b>无</b></td><td><b>空</b></td>
 *   </tr>
 *   <tr>
 *     <td>命中黑名单</td><td>401</td>
 *     <td>{@code application/json;charset=utf-8}</td>
 *     <td>{@code {"code":5004,"msg":"unauthorized"}}</td>
 *   </tr>
 * </table>
 * <p>⇒ 黑名单分支<b>不能</b>走 {@code @RestControllerAdvice}：
 * {@code FollowApiResponseAdvice} 会把非空 body 包成 envelope（{@code {code,msg,data}}），
 * 而 B 侧输出的是<b>没有 {@code data} 字段</b>的两字段对象；
 * {@code FollowApiExceptionHandler} 的 {@code handleAuthentication} 又是为「空 body」写的。
 * <p>⇒ 所以本拦截器<b>直接写响应并返回 {@code false}</b>（不再进入 handler / advice），
 * 这是 B 侧「自己拼字节写出去」的最直接等价物。
 *
 * <h3>⚠️ {@code ,} 分隔的 Content-Type 里没有空格</h3>
 * <p>B 侧写的是字面量 {@code "application/json;charset=utf-8"}（{@code ;} 后无空格）。
 * 用 Spring 的 {@code MediaType#toString()} 之类会输出 {@code ; charset=}（有空格），
 * 那是<b>另一个字节串</b>。客户端若做严格字符串匹配（少见但存在）会失配 ⇒ 此处照抄字面量。
 *
 * <h3>⚠️ fail-open 是硬行为，不是容错策略</h3>
 * <p>B 侧的判据是 {@code err == nil &amp;&amp; exists}：Redis 不可达时 {@code err != nil}
 * ⇒ 条件为假 ⇒ <b>放行</b>。也就是说 Redis 挂掉不会拦住任何人，只会失去「已登出的 token
 * 立刻失效」这一条能力。
 * <p>本实现把异常吞成 {@code null}，随后 {@code exists != null &amp;&amp; exists} 同样为
 * false ⇒ 放行。⚠️ <b>改成 fail-closed 就是一个真实的安全回归</b>
 * （Redis 故障会让全站请求 401），不是「更安全」。
 *
 * <h3>⚠️ 取 token 的方式与验签层<b>刻意不同</b>（B 侧自身的不一致，原样保留）</h3>
 * <p>B 侧的黑名单中间件是「大小写<b>敏感</b>的精确 {@code TrimPrefix("Bearer ")}」
 * <b>再</b> {@code TrimSpace}；而验签走 go-zero 的大小写不敏感 strip。
 * ⇒ 请求头写成 {@code "bearer <token>"} 时，验签用 {@code "<token>"}（成功），
 * 黑名单查的却是 {@code "bearer <token>"}（必然查不到 ⇒ 黑名单被绕过）。
 * <p>这是 B 侧的既有行为，<b>不做统一</b>：统一了就等于修了一个 B 侧从未修过的行为差异，
 * 而 AGENTS.md 要求「迁移不是重构」。
 */
@Component
public class FollowBlacklistInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FollowBlacklistInterceptor.class);

    /**
     * Redis 黑名单 key 前缀 —— B 侧 {@code checkblacklistmiddleware.go:39} 的
     * {@code fmt.Sprintf("user:jwt_blacklist:%s", token)}。
     *
     * <p>⚠️ <b>必须是 {@code user:} 而不是 {@code admin:}</b>：B 侧 follow/api 用的
     * 就是 {@code user:jwt_blacklist:}，与登出写入端同 key ⇒ follow-api
     * 才能拦住已登出的 token。写错前缀的后果是「黑名单层永远不命中」，
     * 而且<b>没有任何报错</b>（查的是一个不存在的 key，Redis 正常返回 0）。
     */
    private static final String BLACKLIST_KEY_PREFIX = "user:jwt_blacklist:";

    /** B 侧字面量，刻意不经过 MediaType 归一化（那会补上空格）。 */
    private static final String GO_CONTENT_TYPE = "application/json;charset=utf-8";

    private final StringRedisTemplate redisTemplate;

    public FollowBlacklistInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 与 JWT 拦截器同一条判据（B 侧两条中间件都挂在 router.Handle 匹配的路由上，
        // 404 / 405 根本进不了 chain）—— 见 FollowJwtAuthInterceptor 的注释。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // B 出处：checkblacklistmiddleware.go:26-27 —— header 为空 ⇒ 直接放行。
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            return true;
        }

        // B 出处：checkblacklistmiddleware.go:28-30
        //   strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
        // ⚠️ trimBearerPrefix 是**大小写敏感**的精确前缀剥离（与验签层刻意不同）。
        String jwtToken = goTrimSpace(BearerTokenExtractor.trimBearerPrefix(authHeader));
        if (jwtToken.isEmpty()) {
            return true;
        }

        if (isBlacklisted(jwtToken)) {
            log.warn("follow token in blacklist, uri={}", request.getRequestURI());
            writeUnauthorized(response);
            return false;
        }
        return true;
    }

    /**
     * Redis 黑名单查询 —— fail-open 与 B 侧逐条对齐。
     *
     * <p>B 侧：{@code exists, err := m.Redis.ExistsCtx(...); if err == nil && exists { 401 }}。
     * Redis 故障时 {@code err != nil} ⇒ 条件为 false ⇒ <b>放行</b>，不阻塞业务。
     */
    private boolean isBlacklisted(String token) {
        String blacklistKey = BLACKLIST_KEY_PREFIX + token;
        Boolean exists;
        try {
            exists = redisTemplate.hasKey(blacklistKey);
        } catch (Throwable t) {
            // ⚠️ fail-open：见类注释「fail-open 是硬行为」。这里刻意吞掉异常。
            log.warn("follow Redis blacklist check failed, fail-open : {}", t.getMessage());
            exists = null;
        }
        return exists != null && exists;
    }

    /**
     * B 侧 {@code checkblacklistmiddleware.go:42-48} 的逐字节等价物。
     *
     * <p>body 模板：{@code {"code":<5004>,"msg":"unauthorized"}} ——
     * <b>没有 {@code data} 字段</b>（B 侧这里是手写字符串，不是 envelope 结构体）。
     */
    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        int code = ErrCodes.ERROR_UNAUTHORIZED;
        String body = "{\"code\":" + code + ",\"msg\":\"" + ErrMsg.getMsg(code) + "\"}";
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // ⚠️ 刻意**不**调 setCharacterEncoding：Tomcat 会在 content-type 不带 charset 时
        //   追加 `;charset=...`，可能把 B 侧的字面量改写成另一个字节串。这里 charset 已经
        //   写在 content-type 里，且 body 是直接写字节（不走 Writer），不涉及编码协商。
        response.setContentType(GO_CONTENT_TYPE);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    /**
     * B 侧 {@code strings.TrimSpace} 的等价实现。
     *
     * <p>⚠️ <b>刻意不用 {@link String#trim()}、也不用 {@link String#strip()}</b>：
     * <ul>
     *   <li>{@code trim()} 只裁 ASCII ≤ ' '，不裁 U+00A0 / U+0085；</li>
     *   <li>{@code strip()} 用 {@link Character#isWhitespace}，它<b>不</b>包含
     *       U+00A0（NBSP）与 U+0085（NEL），而 B 侧的 {@code unicode.IsSpace} 包含。</li>
     * </ul>
     */
    private static String goTrimSpace(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isGoSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /** B 侧 {@code unicode.IsSpace} 的判定表（{@code unicode.IsSpace} / {@code unicode.White_Space}）。 */
    private static boolean isGoSpace(char c) {
        switch (c) {
            case '\t':
            case '\n':
            case '\u000B':
            case '\f':
            case '\r':
            case ' ':
            case '\u0085':
            case '\u00A0':
                return true;
            default:
                // 其余 Unicode 空白：Java 的 Character.isWhitespace 与 B 侧的 unicode.IsSpace
                // 在这一段上取值一致（Java 不含 U+00A0/U+0085，已在上面单列）。
                return Character.isWhitespace(c);
        }
    }
}
