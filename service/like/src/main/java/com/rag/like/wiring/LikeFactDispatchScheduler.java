package com.rag.like.wiring;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》U-2。
 *
 * <h2>Go 出处</h2>
 * <pre>
 * service/like/rpc/cmd/fact-dispatch/main.go
 *   → communityfact.RunDispatcher(ctx, model.NewLikeFactModel(db), sender, logger, once)
 *   → ProcessMetadata{Service:"rtw-like-fact-dispatch", Component:"community"}
 * </pre>
 *
 * <h2>为什么它是域内组件而不是独立进程（D1 的例外判定）</h2>
 * <p>Go 侧把它做成**独立进程**，源码注释写的是「keeps retry cadence outside the business RPC process」
 * （<code>communityfact/process.go:18-19</code>）。RAG 的组件级对照已判定：**这句是 Go 的架构意图，
 * 而非技术约束** —— 理由有三条，每条都可复核：
 * <ol>
 *   <li><b>不注册任何对外服务</b>：<code>main.go:76</code> 只调 <code>RunDispatcher</code>，
 *       没有 gRPC server、没有对外端口 ⇒ 没有 wire 契约需要独立进程承载。</li>
 *   <li><b>它已经是纯库</b>：<code>Dispatcher</code> 是静态工厂（<code>Dispatcher.java:64</code>），
 *       职责就是「跑投递循环」，天然可被宿主进程调用。</li>
 *   <li><b>Spring 侧有等价编排位</b>：<code>@Scheduled</code> + <code>@PreDestroy</code> 停机
 *       正好对应 Go 的 ticker 循环 + ctx 取消。</li>
 * </ol>
 *
 * <h2>⚠️ L1 观测契约：三个 logger 身份必须隔离</h2>
 * <p>Go 侧一个 like 域有 <b>三个</b>进程级日志身份（由 <code>service.env.component</code> 拼出 logger 名）：
 * <pre>
 *   like.rpc                  ← 业务 RPC 进程
 *   rtw-like-fact-dispatch    ← 本类
 *   rtw-like-fact-authority   ← {@link LikeAuthorityEndpointConfig}
 * </pre>
 * ⇒ 合并成一域一 app 后，<b>必须</b>保住三份独立 appender 与 logger 名，
 * 否则 <code>call_chain</code> 与日志来源语义漂移。
 * ⚠️ 实现时<b>不要用 SLF4J 全局 MDC</b> —— MDC 会跨线程插值（RAG 的
 * {@code ProcessLogger.java:114-117} 已明确记录这一点）。
 *
 * <h2>⚠️ 待拍板：<code>-once</code> 单次投递模式</h2>
 * <p>Go 的 <code>-once</code> 是命令行 flag（<code>main.go:25</code>），为 cron / k8s CronJob /
 * 手工排障准备 ⇒ 常驻 <code>@Scheduled</code> <b>覆盖不了该用法</b>。
 * ⇒ 需降级为配置项（如 <code>like.fact.dispatch.once</code>）。
 * ⚠️ <b>保留 CronJob 部署形态，还是改为常驻循环？</b> —— RAG 侧同样标 <code>UNKNOWN</code> 待拍板，
 * 本仓沿用同一待办（见《决策记录》P6）。
 *
 * @see LikeAuthorityEndpointConfig
 * @see LikeFactProcessLogging
 */
public class LikeFactDispatchScheduler {
}
