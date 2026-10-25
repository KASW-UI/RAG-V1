package com.rag.favorite.bootstrap;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》U-2。
 *
 * <h2>Go 出处</h2>
 * <pre>
 * service/favorite/rpc/cmd/fact-authority/main.go
 *   → communityfact.NewAuthorityHandler("rtw.favorite-rpc", token, model.NewFavoriteFactModel(db).AuthoritativeFact, logger)
 *   → net.Listen(...)   ← 独立 HTTP 端口
 * </pre>
 *
 * <h2>它承载的是 Servlet，不是 gRPC 服务</h2>
 * <p>只做「构造 <code>AuthorityHandler</code> + <code>net.Listen</code>」⇒ 零 gRPC 注册
 * ⇒ 无强类型契约依赖独立进程 ⇒ 可并入域内 app 作为 Servlet 装配。
 *
 * <h2>⚠️ L1：权威事实响应体是逐字节契约</h2>
 * <p>Go 侧 <code>authority_http.go:72</code> 的 <code>json.NewEncoder().Encode</code>
 * <b>追加尾 <code>\n</code></b>，且 <code>escapeHTML</code> 默认开启
 * ⇒ 响应体<b>字节</b>是跨服务契约。
 * ⚠️ RAG 在此处有 <code>DRIFT-CF-003</code>（尾 <code>\n</code> + <code>escapeHTML</code> 缺失）
 * 与 <code>DRIFT-CF-014</code>（畸形 endpoint 报错形态）两条<b>已登记未修</b>漂移
 * ⇒ 本仓<b>直接从 Go 语义写</b>，不要照抄 RAG 实现。
 *
 * <h2>⚠️ 与 favorite 域第三进程的关系</h2>
 * <p>本类（权威事实查询）与 {@code fact-legacy-preflight}（一次性预检）职责不同：
 * 前者常驻、后者一次性 ⇒ 本类才是需要常驻 Servlet 装配的那个。
 *
 * @see FavoriteFactDispatchScheduler
 * @see FavoriteFactProcessLogging
 */
public class FavoriteAuthorityEndpointConfig {
}
