package com.rag.common.exception;

/**
 * <b>鉴权层失败</b> —— 与 {@link BusinessException} 的区别只有一条：<b>HTTP 状态码是 401</b>。
 *
 * <p><b>为什么必须单列一个类型（DRIFT-004 修复）</b>
 * <p>修复前 user-api / admin-api 的鉴权失败（缺 Bearer / 签名错 / 过期 / token 在黑名单）
 * 抛的是普通 {@code BusinessException(1006)}，而 {@code GlobalExceptionHandler} 的
 * {@code decideStatus(code)} 无条件返回 {@code HttpStatus.OK} ⇒
 * <b>HTTP 200 + {@code {"code":1006,...}}</b>。同一份代码里那段 javadoc 的论证是：
 * <blockquote>「401 只由鉴权层的 Servlet Filter 写出，不经过本处理器」</blockquote>
 * 但鉴权短路是 {@code HandlerInterceptor} 抛异常，<b>恰恰会经过本处理器</b> ——
 * 论证引用了一个在本工程里并不存在的机制。
 *
 * <p>更麻烦的是<b>不能靠业务码区分层次</b>：1006 在两条路径上都可能出现
 * （拦截器的鉴权失败 = 应 401；Service 的 token 失效 = Go 侧同样是 envelope + 200）。
 * 按 code 分流会把两层重新搅在一起，正是这次缺陷的成因。
 * 所以层次必须由<b>类型</b>承载。
 *
 * <p><b>Go 出处（HTTP 状态码对齐 Go，body 形状是本端有意统一，见 AGENTS.md §3.4）</b>
 * <table border="1">
 *   <caption>Go 侧两处鉴权短路</caption>
 *   <tr><th>位置</th><th>触发</th><th>Go 输出</th></tr>
 *   <tr>
 *     <td>{@code rest.WithJwt}<br>{@code → rest/handler/authhandler.go:106 unauthorized()}
 *         <br>{@code → rest/engine.go:70-79 appendAuthHandler}</td>
 *     <td>缺 Authorization / 签名错 / 过期</td>
 *     <td><b>HTTP 401</b>，body 为空</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CheckBlacklistMiddleware.Handle}
 *         <br>{@code user/api/internal/middleware/checkblacklistmiddleware.go:37-40}
 *         <br>{@code admin/api/internal/middleware/checkblacklistmiddleware.go:37-40}</td>
 *     <td>token 在 Redis 黑名单</td>
 *     <td><b>HTTP 401</b> + {@code {"code":401,"msg":"登录已失效,请重新登陆"}}（无 data）</td>
 *   </tr>
 * </table>
 * 两处都是 <b>401</b>，这是必须对齐的部分；两处 body 形状不同是 Go 自身的不一致，
 * 本端统一成 {@code {code,msg,data}} 完整 envelope，属表达层差异。
 *
 * <p><b>不要在 Service 层抛这个类型</b>：Service 层的失败一律是 HTTP 200 + 业务码
 * （与 Go 的 {@code httpx.OkJson} 一致）。只有拦截器用。
 *
 * <p>Go 侧的对应实现已由 {@code service/user/user/api/identity_contract.md} 第 11 行坐实：
 * 「错误签名在读取 RPC 前即 401」。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/exception/AuthenticationException.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
public class AuthenticationException extends BusinessException {

    public AuthenticationException(int code, String msg) {
        super(code, msg);
    }
}
