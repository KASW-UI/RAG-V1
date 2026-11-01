package com.rag.common.protection;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.List;
import java.util.function.Supplier;

/**
 * <p><b>L4 idiomatization</b>：在 <b>Servlet Filter</b> 里解析出「<b>注册期路由模板</b>」，
 * 让共享层的 HTTP 熔断器能按 Go 的隔离单位（<b>每条路由一个 breaker</b>）分桶。
 *
 * <p>RAG 出处：{@code service/common/protection/GoStyleRouteTemplateResolver.java}
 * （抄写基准 = RAG，L2 业务语义层整段抄 · D13）。
 *
 * <p><b>Go 出处（消费点，不是声明点）</b>：
 * <pre>
 *   rest/engine.go:99-101   bindRoute(...)
 *       chn := ng.chain
 *       if chn == nil { chn = ng.buildChainWithNativeMiddlewares(fr, route, metrics) }
 *       ⇒ <b>route 是「每条路由」一个参数</b>（不是全局）
 *
 *   rest/engine.go:131-132  buildChainWithNativeMiddlewares(fr, route, metrics)
 *       if ng.conf.Middlewares.Breaker {
 *           chn = chn.Append(handler.BreakerHandler(route.Method, route.Path, metrics))
 *       }
 *       ⇒ <b>每条路由各建一个 breaker</b>，且<b>注册期</b>就把 route.Path（模板）传进去
 *
 *   rest/handler/breakerhandler.go:15  const breakerSeparator = "://"
 *   rest/handler/breakerhandler.go:19
 *       brk := breaker.NewBreaker(breaker.WithName(strings.Join([]string{method, path}, breakerSeparator)))
 *       ⇒ <b>breaker 的身份 = METHOD://route.Path（注册期模板）</b>
 *       ⇒ 例如 article 域的 {@code GET /article/:id} ⇒ 键 = {@code GET://article/:id}
 *          ⇒ <b>/article/1 与 /article/2 共用同一个 breaker</b>
 *
 *   rest/router/patrouter.go:59-78  ServeHTTP
 *       reqPath := path.Clean(r.URL.Path)
 *       if tree, ok := pr.trees[r.Method]; ok {
 *           if result, ok := tree.Search(reqPath); ok { …交给该路由的 handler… }
 *       }
 *       …没匹配上 ⇒ pr.handleNotFound(w, r)
 *
 *   rest/engine.go:244-268  notFoundHandler
 *       chn := chain.New(handler.TraceHandler(ng.conf.Name, "", …))
 *       if ng.conf.Middlewares.Log { chn = chn.Append(ng.getLogHandler()) }
 *       ⇒ <b>404 的链只有 Trace + Log</b>：<b>没有 MaxConns / Breaker / Shedding</b>
 *       ⇒ ⇒ <b>Go 侧未命中路由（404 / 405）根本不经过 breaker</b>
 * </pre>
 *
 * <p><b>本类要解决的两处漂移</b>（登记在 {@code DRIFT-USERAPI-013②} / {@code DRIFT-FOLLOW-008} 同族条目里）：
 * <ol>
 *   <li><b>隔离单位错</b>：原先用 raw {@code getRequestURI()} 当键
 *       ⇒ {@code /article/1} 与 {@code /article/2} 是<b>两个</b> breaker；
 *       Go 侧是<b>一个</b>（模板 {@code /article/:id}）
 *       ⇒ ⇒ <b>熔断爆炸半径不同</b>：Go 上 {@code /article/1} 持续 5xx 会把
 *       <b>同模板的 {@code /article/2} 一起挡住</b>，不照做就不会。</li>
 *   <li><b>未命中路由也进 breaker</b>：Filter 对<b>所有</b>请求生效（含 404）
 *       ⇒ 404 会凭空造出 breaker 条目；Go 侧 404 链里<b>没有</b> breaker。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么解析发生在 Filter 里、而不是靠 Spring MVC 现成的模板属性</b>：
 * {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE} 是 DispatcherServlet
 * <b>匹配成功之后</b>才写到 request 上的 ⇒ 而 breaker 是 <b>Filter</b>，跑在
 * DispatcherServlet <b>之前</b> ⇒ 那一刻属性还不存在 ⇒ 只能自己问一次「这条请求匹配到哪条路由」。
 * <p>用的是 Spring <b>自己的</b> {@link HandlerMapping#getHandler(HttpServletRequest)}
 * ⇒ 即 DispatcherServlet 走的同一条匹配路径 ⇒ <b>不重新实现路由匹配</b>
 * ⇒ 不存在「两套匹配规则漂移」的风险。
 *
 * <p><b>⚠️ 为什么只看 {@code RequestMappingHandlerMapping}（不 consult 所有 {@code HandlerMapping}）</b>：
 * Boot 的<b>静态资源处理器</b>（{@code SimpleUrlHandlerMapping}）把 {@code /**} 映射到
 * {@code ResourceHttpRequestHandler} ⇒ ⇒ <b>它匹配一切</b> ⇒
 * ⇒ 若把它算进来，<b>永远得不到「确定未命中」</b>（每条路径都被它兜住）
 * ⇒ ⇒ 「404 不进 breaker」这条 Go 语义就<b>永远落不了地</b>。
 * <p>⇒ 只认<b>注解驱动</b>的路由（{@code @RequestMapping} / {@code @GetMapping} …）
 * ⇒ 它正是 Go 侧 {@code AddRoutes} 注册表的等价物。
 * <p>⚠️ 同一个陷阱在 RAG 仓已有先例：{@code FavoriteJwtAuthInterceptor} 的注释
 * 写明「Boot 的静态资源处理器匹配 {@code /**}，于是未注册路径<b>也拿到了 handler</b>」
 * ⇒ ⇒ 本类沿用同一口径（{@code handler instanceof HandlerMethod}）。
 *
 * <p><b>⚠️ 三态返回是刻意的（防「静默失去熔断保护」）</b>：见 {@link RouteResolution}。
 */
public final class RouteTemplateResolver implements RouteTemplateSource {

    private final Supplier<List<RequestMappingHandlerMapping>> handlerMappingsSupplier;

    /** 解析出来的 HandlerMapping 列表（只缓存<b>非空</b>结果，见 {@link #mappings()}）。 */
    private volatile List<RequestMappingHandlerMapping> cachedMappings;

    /**
     * @param handlerMappings 本进程里的 {@link RequestMappingHandlerMapping}（按 Spring 的排序）
     *                        ⇒ 空的 ⇒ 永远返回 {@link RouteResolution.Kind#UNKNOWN}
     */
    public RouteTemplateResolver(List<RequestMappingHandlerMapping> handlerMappings) {
        this(() -> handlerMappings == null ? List.of() : List.copyOf(handlerMappings));
    }

    /**
     * @param handlerMappingsSupplier <b>惰性</b>提供 HandlerMapping 列表
     *        ⇒ 之所以要惰性：Filter 是在 Tomcat 启动时构造的，
     *        那时 DispatcherServlet 还没初始化 ⇒ 此刻去实例化
     *        {@code RequestMappingHandlerMapping} 属于「提前触发」。
     *        惰性化 ⇒ 推迟到<b>第一次请求</b>，此时容器已完全就绪。
     */
    public RouteTemplateResolver(Supplier<List<RequestMappingHandlerMapping>> handlerMappingsSupplier) {
        this.handlerMappingsSupplier = handlerMappingsSupplier == null ? List::of : handlerMappingsSupplier;
    }

    /**
     * 取 HandlerMapping 列表。
     *
     * <p>⚠️ <b>只缓存非空结果</b>：若第一次取到的是空列表（容器尚未完全就绪），
     * 就<b>不缓存</b>、下次重取 ⇒ 避免「早期空快照被永久固化 ⇒ 熔断保护被静默关掉」。
     */
    private List<RequestMappingHandlerMapping> mappings() {
        List<RequestMappingHandlerMapping> cached = cachedMappings;
        if (cached != null) {
            return cached;
        }
        List<RequestMappingHandlerMapping> resolved = handlerMappingsSupplier.get();
        if (resolved == null || resolved.isEmpty()) {
            return List.of();
        }
        cachedMappings = List.copyOf(resolved);
        return cachedMappings;
    }

    /**
     * 解析这条请求对应的注册期路由模板。
     *
     * @param request 当前请求（只读；本方法会<b>顺带</b>缓存解析后的请求路径，
     *                与 Spring 的 {@code ServletRequestPathFilter} 做的事一样，
     *                对 DispatcherServlet 后续的匹配<b>无副作用</b>）
     */
    @Override
    public RouteResolution resolve(HttpServletRequest request) {
        List<RequestMappingHandlerMapping> handlerMappings = mappings();
        if (handlerMappings.isEmpty()) {
            // 没有 RequestMappingHandlerMapping ⇒ 本进程不是 Spring MVC 应用 ⇒ 判不出来
            return RouteResolution.UNKNOWN;
        }
        for (RequestMappingHandlerMapping handlerMapping : handlerMappings) {
            HandlerExecutionChain chain;
            try {
                if (!ServletRequestPathUtils.hasParsedRequestPath(request)) {
                    ServletRequestPathUtils.parseAndCache(request);
                }
                chain = handlerMapping.getHandler(request);
            } catch (Exception | LinkageError e) {
                // 判不出来 ⇒ 不能把它当成「未命中」
                return RouteResolution.UNKNOWN;
            }
            if (chain == null) {
                // 没匹配上任何 @RequestMapping 方法 ⇒ 试下一个
                continue;
            }
            if (!(chain.getHandler() instanceof HandlerMethod)) {
                // 本仓既有判据：「有没有匹配上 @RequestMapping 方法」——
                // ResourceHttpRequestHandler 不是 HandlerMethod。
                // RequestMappingHandlerMapping 只挂 HandlerMethod ⇒ 理论上到不了这里 ⇒ 判不出来更保险。
                return RouteResolution.UNKNOWN;
            }
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern instanceof String s && !s.isEmpty()) {
                return RouteResolution.template(s);
            }
            // 匹配上了但拿不到模板（如 CORS 预检）⇒ 判不出来
            return RouteResolution.UNKNOWN;
        }
        // 所有 mapping 都没匹配上 ⇒ 确定未命中
        return RouteResolution.NOT_MATCHED;
    }
}
