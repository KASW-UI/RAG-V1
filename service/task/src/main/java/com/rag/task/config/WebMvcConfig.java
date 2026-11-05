package com.rag.task.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>本域 HTTP 面的「装配边界声明」</b> —— 两条<b>否定式</b>决定：
 * <b>零鉴权</b>与<b>零 CORS</b>。</p>
 *
 * <h2>🔴 决定一：<b>零鉴权</b>（不注册任何拦截器）</h2>
 * <p>B 侧 {@code task-api} <b>没有</b> {@code interceptor/} 包、<b>没有</b> JWT 依赖：
 * <pre>
 *   service/task/api/internal/handler/routes.go:15-25
 *       server.AddRoutes(
 *           []rest.Route{ { Method: http.MethodPost, Path: "/task/get", … } },
 *           rest.WithPrefix("/taskcenter/v1"),
 *       )
 *       // ← 没有 rest.WithJwt / rest.WithCors / rest.WithMaxBytes
 * </pre>
 * {@code etc/task.yaml:9-11} 的 {@code Auth{AccessSecret, AccessExpire}} 是<b>死配置</b>
 * （{@code internal/config/config.go} 里声明了，但 {@code servicecontext.go} 与
 * {@code gettasklogic.go} 都没读它）。
 * ⇒ {@code POST /taskcenter/v1/task/get} 是<b>公开端点</b>，用户身份来自请求体的 {@code id}。
 * <p>⇒ 引入 {@code JwtAuthInterceptor} 会把「公开读」变成「必须带 token」⇒ <b>L2 级行为变更</b>，
 * 不是「顺手补一个缺失的安全措施」。
 * <p>⚠️ 该端点因此存在<b>水平越权面</b>（任何调用方都能查任意 uid 的任务进度）——
 * 这是 Go 的既定行为，按原样保留；若要补鉴权属于<b>新增行为</b>，需单独拍板。
 *
 * <h2>🔴 决定二：<b>零 CORS</b>（不注册任何 CORS 映射）</h2>
 * <p>B 侧 {@code task-api} <b>没有</b> {@code rest.WithCors()} ⇒ go-zero 的 patrouter 走
 * {@code rest/router/patrouter.go:79-82} 的 else 分支 ⇒ 方法不匹配输出
 * <b>405 + Allow 头 + 无 body</b>。
 * <p>⚠️ <b>这条与 article 域相反</b>：article-api 是<b>唯一</b>用了 {@code rest.WithCors()} 的域
 * ⇒ 它的 {@code notAllowed} 被 CORS handler 接管 ⇒ 输出 <b>404 + 空 body</b>
 * （article 域的 C 侧已据此分叉，见 {@code ArticleApiExceptionHandler#handleMethodNotSupported}）。
 * ⇒ ⇒ <b>本域必须走 405 分支</b> ⇒ 共享层的 {@code RouterErrorResponses#methodNotAllowed}
 * 是<b>正确</b>的那个 ⇒ ⇒ <b>注册 CORS 映射会把这条 wire 形状改掉</b>。
 * <p>⇒ 本类<b>显式覆盖</b> {@link #addCorsMappings} 为空：把「不注册 CORS」写进代码，
 * 而不只是「碰巧没写」。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li>B 侧<b>没有这个类</b>（B 侧用 {@code @ComponentScan} + 无 {@code WithJwt}/{@code WithCors}
 *             表达同一件事）⇒ C 侧本类是<b>新增的「否定式声明」</b>；</li>
 *         <li>包名压平 + 域名前缀（D7）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>一样</b> —— 本类<b>不注册任何拦截器、不注册任何 CORS 映射</b>
 *       ⇒ 与 B 侧「没有 {@code WithJwt} / 没有 {@code WithCors}」<b>逐条等价</b>
 *       （两个空覆写不改变 Spring 的默认行为，默认本来就是空的）。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/TaskApiApplication.java
 *         ← @ComponentScan 里**没有**任何鉴权 / CORS 相关的 FilterType.ASSIGNABLE_TYPE
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/internal/handler/routes.go:15-25
 *         ← AddRoutes 只有 rest.WithPrefix，**没有** rest.WithJwt / rest.WithCors
 *       </pre></li>
 * </ol>
 *
 * <h2>⚠️ 为什么本类<b>不</b>注册鉴权，却仍然保留</h2>
 * <p>因为「本域零鉴权」是<b>需要被显式记录</b>的 L2 事实：
 * 本仓其它域（like / message / article / users）都有 {@code JwtAuthInterceptor} ＋
 * public 路径白名单 ⇒ 后人照抄别的域时会<b>顺手</b>把鉴权层搬过来 ⇒
 * ⇒ 本类就是那条「<b>这里刻意没有</b>」的落点。
 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * <b>刻意不注册任何拦截器</b> —— 本域全部端点免鉴权。
     *
     * <p>⚠️ 本方法的<b>空实现</b>与「不写这个方法」等价（Spring 默认就不注册任何拦截器）
     * ⇒ 它存在的意义是<b>把「这里刻意没有鉴权」写进代码</b>，供 review 与后人对照。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 🔴 刻意为空：本域零鉴权（B 侧 task-api 无 interceptor 包、无 JWT 依赖、Auth 是死配置）。
        //    注册 JwtAuthInterceptor 会把「公开读」变成「必须带 token」⇒ L2 级行为变更。
    }

    /**
     * <b>刻意不注册任何 CORS 映射</b> —— 与 B 侧 task-api 未开 {@code rest.WithCors()} 一致。
     *
     * <p>⚠️ 注册 CORS 会把「方法不匹配」的响应形状从
     * <b>405 + Allow 头</b> 改成 <b>404 + 空 body</b>（article 域已实测到这条分叉）
     * ⇒ 那是 <b>L1 wire 形状</b>的改变。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 🔴 刻意为空：B 侧 task-api 没有 rest.WithCors() ⇒ 405 分支（共享层
        //    RouterErrorResponses#methodNotAllowed）才是本域的正确形状。
    }
}
