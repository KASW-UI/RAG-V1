package com.rag.favorite.wiring;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》U-2。
 *
 * <h2>Go 出处</h2>
 * <pre>
 * service/favorite/rpc/cmd/fact-dispatch/main.go
 *   → communityfact.RunDispatcher(ctx, model.NewFavoriteFactModel(db), sender, logger, once)
 *   → ProcessMetadata{Service:"rtw-favorite-fact-dispatch", Component:"community"}
 * </pre>
 *
 * <h2>为什么它是域内组件而不是独立进程（D1 的例外判定）</h2>
 * <p>与 like 域同源：Go 的独立进程是<b>架构意图</b>而非技术约束 ——
 * <code>main.go</code> 不注册任何对外服务，{@code Dispatcher} 已是纯库（静态工厂），
 * Spring 侧用 <code>@Scheduled</code> + <code>@PreDestroy</code> 即可等价承载。
 *
 * <h2>⚠️ favorite 域独有的第三个 fact-* 进程</h2>
 * <p>Go 侧 favorite <b>比 comment/like 多一个</b>：
 * <pre>
 * service/favorite/rpc/cmd/fact-legacy-preflight/main.go
 *   → model.NewFavoriteModel(db).FavoriteLegacyPreflight(ctx)   ← 一次性诊断报告
 * </pre>
 * ⇒ 它是<b>一次性预检</b>（跑一次出报告），不是常驻服务 ⇒ 在 Spring 形态下应是
 * <b>可触发的运维入口</b>（如 Actuator 自定义端点 / 一次性 CLI profile），
 * <b>不是</b>常驻进程。
 * ⚠️ 本仓对应物已落在 {@code FavoriteLegacyFactMarkerEntity} + {@code FavoriteAssertionSchemaResolver}
 * —— 但<b>触发入口</b>尚未规划 ⇒ 需在实现时明确（见《决策记录》P6）。
 *
 * <h2>⚠️ L1：三个 logger 身份必须隔离</h2>
 * <pre>
 *   favorite.rpc                  ← 业务 RPC 进程
 *   rtw-favorite-fact-dispatch    ← 本类
 *   rtw-favorite-fact-authority   ← {@link FavoriteAuthorityEndpointConfig}
 * </pre>
 * ⇒ 合并后必须保住三份独立 logger 名与 appender；<b>不要用 SLF4J 全局 MDC</b>（会跨线程插值）。
 *
 * @see FavoriteAuthorityEndpointConfig
 * @see FavoriteFactProcessLogging
 */
public class FavoriteFactDispatchScheduler {
}
