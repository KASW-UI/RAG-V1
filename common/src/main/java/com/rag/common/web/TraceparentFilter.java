package com.rag.common.web;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;

/**
 * 🔴 <b>T-8</b>：把 <b>W3C {@code traceparent}</b> 写进<b>响应头</b> —— 对齐 B 侧 的
 * {@code service/common/src/main/java/com/rag/common/http/GoStyleTraceparentFilter.java:31}。
 *
 * <h2>B 侧事实（真源，逐行）</h2>
 * <pre>
 * B 出处：service/common/src/main/java/com/rag/common/http/GoStyleTraceparentFilter.java:36:47-63
 *   ctx := propagator.Extract(r.Context(), propagation.HeaderCarrier(r.Header))   // ← 取入向 traceparent
 *   spanCtx, span := tracer.Start(ctx, spanName,
 *       oteltrace.WithSpanKind(oteltrace.SpanKindServer),
 *       oteltrace.WithAttributes(semconv.HTTPServerAttributesFromHTTPRequest(...)))
 *   defer span.End()
 *   // convenient for tracking error messages
 *   propagator.Inject(spanCtx, propagation.HeaderCarrier(w.Header()))            // ← 注入【响应头】
 *   next.ServeHTTP(trw, r.WithContext(spanCtx))
 * </pre>
 *
 * <h3>⇒ 三条必须对齐的语义</h3>
 * <ol>
 *   <li><b>注入的是「当前 server span」，不是回显入向的 parent</b> ——
 *       入向 {@code traceparent} 只是 <b>parent</b>（Extract → Start），
 *       写出去的是 <b>server span 自己</b>的 {@code span-id}。
 *       ⚠️ <b>回显入向头是最容易写错的一种「看起来对」的实现</b>。</li>
 *   <li><b>注入在业务执行之前</b>（B 侧 在 {@code next.ServeHTTP} 之前就 Inject）
 *       ⇒ 任何状态码（200 / 400 / 401 / 404 / 503）都带得上。</li>
 *   <li><b>trace-id 沿用入向</b>（server span 是入向 context 的子 span）
 *       ⇒ 客户端能把自己的 trace 与服务端串起来。</li>
 * </ol>
 *
 * <h3>为什么 <b>10 个 api 全都有</b>（B 侧）</h3>
 * <pre>
 * B 出处：service/common/src/main/java/com/rag/common/http/GoStyleTraceparentFilter.java:61:11-22
 *   MiddlewaresConf struct {
 *       Trace bool `json:",default=true"`      // ← 默认开
 *       ...
 *   }
 * B 出处：service/common/src/main/java/com/rag/common/http/GoStyleTraceparentFilter.java:66:134-138
 *   if ng.conf.Middlewares.Trace {
 *       chn = chn.Append(handler.TraceHandler(ng.conf.Name, route.Path, ...))
 *   }
 * B 出处：service/common/src/main/java/com/rag/common/http/GoStyleTraceparentFilter.java:70:245-251
 *   // notFoundHandler 也挂 ⇒ 连 404 都带 traceparent
 *   // ⚠️ 而它**默认就装着**：rest/server.go:58 的 NewServer 里
 *   //   opts = append([]RunOption{WithNotFoundHandler(nil)}, opts...)
 *   //   ⇒ 每个 api 都有 ⇒ 「未注册路径」的 404 也经过它 ⇒ 也带 traceparent
 * </pre>
 * ⇒ 本仓 10 个 api 的 yaml <b>都没配</b> {@code Middlewares} / {@code TraceIgnorePaths}
 * ⇒ B 侧 {@code Trace=true}、无 ignore path ⇒ <b>每个响应都带</b>。
 *
 * <h2>🔴 为什么 RAG 侧此前<b>完全没有</b>这个头（根因，不是「忘了写一行」）</h2>
 * <p>写这行之前，RAG 侧<b>连「当前 server span」都不存在</b>：
 * <ul>
 *   <li>全仓 <b>没有</b> {@code micrometer-tracing-bridge-otel}、也没有
 *       {@code opentelemetry-sdk-extension-autoconfigure}
 *       ⇒ Spring Boot 的 tracing 自动配置<b>不生效</b>；</li>
 *   <li>⇒ {@code GlobalOpenTelemetry.get()} 是 <b>no-op</b>
 *       ⇒ {@code Span.fromContext(ctx).getSpanContext()} 恒为 <b>INVALID</b>
 *       ⇒ <b>任何</b>「从当前上下文取 traceparent 再写出去」的实现都写不出东西。</li>
 * </ul>
 * ⇒ 第一版（T-8，第十九轮）因此让本类自带一个<b>私有</b> {@link SdkTracerProvider}
 * （<b>不碰全局</b>）每个请求起一个 <b>SERVER</b> span 再注入。
 *
 * <h2>🔴 2026-10-25（第二十一轮 · global T-11）：改用<b>全局</b> provider</h2>
 * <p>T-11 已在共享层装配了<b>全局</b> provider ＋ OTLP exporter（对齐 B 的
 * {@code core/trace/agent.go} 的 {@code startAgent}）⇒ 本类随之改用全局 tracer：
 * <ul>
 *   <li><b>同源</b>：B 侧只有<b>一个</b> provider（{@code service/common/src/main/java/com/rag/common/http/GoStyleTraceparentFilter.java:96:32-64}
 *       用 {@code otel.GetTracer(...)} 起 server span）⇒ 响应头里的 traceparent
 *       与 jaeger 里导出的 span <b>是同一条 trace</b>；
 *       继续用私有 provider ⇒ 两边<b>不同源</b> ⇒ trace 在边界处断成两截。</li>
 *   <li>此前「不碰全局」的理由（{@code set()} 全局只能设一次 ⇒ 会改变
 *       {@code RpcInterceptor} / {@code MqConsumer} 等调用方的行为）<b>已由 T-11 正面解决</b>
 *       ⇒ 那条顾虑不再成立。</li>
 * </ul>
 *
 * <h2>🔴 2026-10-25（第二十一轮 · global T-11）：「不碰全局」的理由已被正面解决</h2>
 * <ul>
 *   <li>此前顾虑：{@code GlobalOpenTelemetry.set(...)} <b>全局只能设一次</b>
 *       ⇒ 一旦本类设了，<b>同 JVM 内</b>所有 {@code GlobalOpenTelemetry.get().getTracer(...)}
 *       的调用方（如 {@code RpcInterceptor} / {@code MqConsumer} / {@code AdminSpans}）
 *       的行为<b>全部改变</b>（从 no-op 变成真建 span）⇒ 那时判断「那是另一件事，不该由本条带出来」。
 *       <br>⇒ <b>现在 T-11 正是为了这个变化而做</b>（B 侧本来就是真建 span）
 *       ⇒ 这条顾虑<b>已消解</b>，不再是理由。</li>
 *   <li>「私有实例足够」的另一层顾虑（{@code HttpMiddleware} 取当前 span 走
 *       {@code Span.fromContext(Context.current())} ⇒ 不依赖全局）<b>依然成立</b>
 *       ⇒ 本类的改动只是「用哪个 provider 起 server span」。</li>
 * </ul>
 *
 * <h2>🔴 sampler 必须与 B 侧 一致</h2>
 * <pre>
 * B 出处：service/common/src/main/java/com/rag/common/http/GoStyleTraceparentFilter.java:120:111
 *   sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.TraceIDRatioBased(c.Sampler)))
 * B 侧 yaml：Telemetry: { Sampler: 1.0 }        （各 api 的 etc/*.yaml）
 * ⇒ 根 span 必采样；入向 parent 不采样时子 span 也不采样。
 * </pre>
 * ⇒ sampler 现在由<b>全局</b> provider 提供（{@code GoStyleOtelAgent} 照 B 侧 逐条装配），
 * 与 RAG 各 api yaml 里已写的 {@code otel.traces.sampler: parentbased_always_on} <b>同义</b>
 * （那份 yaml 一直是对的，缺的只是「把 SDK 装上并接进请求」——T-11 补的就是这一步）。
 *
 * <h2>⚠️ 头名<b>大小写</b>：本类主动 canonical 化</h2>
 * <p>B 的 {@code propagation.HeaderCarrier.Set} 走 {@code textproto.MIMEHeader.Set}
 * ⇒ 键被 canonical 成 {@code Traceparent}；而 OTel Java 的 propagator 写的是<b>小写</b>
 * {@code traceparent}，Tomcat 又<b>原样</b>发出 ⇒ 直接照抄会得到 {@code traceparent: ...}
 * （与 B 的 {@code Traceparent: ...} <b>字节级不一致</b>）。
 * ⇒ 本类的 setter 把键 canonical 成首字母大写 ⇒ 与 B 侧 逐字节对齐。
 *
 * <h2>⚠️ 边界：本类<b>只</b>保证「响应头」这一维</h2>
 * <ul>
 *   <li>B 侧 还会把 span <b>导出</b>到 jaeger（{@code Telemetry.Endpoint}）；
 *       RAG 侧的 <b>trace 导出链路整体缺失</b>（没有 OTel 自动配置、没有 exporter 装配）
 *       ⇒ 那是<b>另一条独立缺口</b>，本轮<b>不碰</b>（见 global 台账 T-11）。</li>
 *   <li>B 的 span 名 = <b>路由 pattern</b>（{@code route.Path}）；本类用请求 URI
 *       ⇒ <b>span 名不可 wire 观察</b>（只在导出端可见）⇒ 本轮不追求一致。</li>
 *   <li>🔴 <b>「方法不匹配」那个出口，RAG 比 B 侧 多带一个 {@code Traceparent}</b> ——
 *       B 的该出口在 <b>router 层</b>（{@code rest/router/patrouter.go:66-72}
 *       的 {@code pr.notAllowed} / 默认 405）⇒ <b>不经过 TraceHandler</b> ⇒ <b>不带</b>；
 *       RAG 的同一出口落在 <b>DispatcherServlet 内</b> ⇒ filter 链<b>早已跑完</b> ⇒ <b>多带</b>。
 *       <br>⇒ 根因是<b>出口所在层级不同</b>，<b>共享层改不动</b>（filter 阶段无法知道
 *       「这个请求最终会不会落到方法不匹配」）⇒ 已登记为 global 台账 <b>T-12</b>。</li>
 * </ul>
 *
 * <h2>⚠️ 链上位置：<b>CORS 之后、HttpMiddleware 之前</b>（本类取 {@code HIGHEST_PRECEDENCE + 3}）</h2>
 * <p>三个约束<b>各自都有 B 出处</b>，缺一个就会产生字节级差异：
 * <ol>
 *   <li><b>必须在 CORS 之后</b> ——
 *       B 侧 {@code rest/internal/cors/handlers.go:53-67} 的 {@code Middleware} 对 {@code OPTIONS}
 *       <b>短路</b>（{@code :61-63} 直接 {@code WriteHeader(204)}，<b>不调 next</b>）
 *       ⇒ 预检响应<b>根本不经过 router</b> ⇒ <b>不经过 TraceHandler</b>
 *       ⇒ <b>B 的 204 预检响应没有 traceparent</b>。
 *       <br>⇒ 本类若排在 CORS 之前 ⇒ 预检响应会<b>多带</b>一个 traceparent ⇒ 与 B 侧 不一致。</li>
 *   <li><b>必须在 MaxConns / Breaker / Shedding 之前</b> ——
 *       B 侧 {@code rest/engine.go:130-152} 的 {@code buildChainWithNativeMiddlewares} 里
 *       {@code TraceHandler} 是 <b>native middleware 链里的第一个</b>（{@code :135}），
 *       MaxConns/Breaker/Shedding 在其后（{@code :146/:149/:152}）
 *       ⇒ <b>被它们拒成 503 的响应也带</b> traceparent（Trace 早就注入过了）。
 *       <br>⇒ 本类若排在它们之后 ⇒ 503 响应<b>丢头</b>。</li>
 *   <li><b>必须在 {@code HttpMiddlewareFilter} 之前</b>（<b>承重</b>）——
 *       {@code HttpMiddleware} 在<b>容器线程</b>上抓 {@code Context.current()}
 *       （{@code HttpMiddleware.java:269} 的 {@code parentOtelCtx}），
 *       再在 worker 线程里用 {@code parentOtelCtx.makeCurrent()} 复原（{@code :312}）
 *       ⇒ 它给 span 打的属性（{@code Span.fromContext(...)}，{@code :358/:455/:485}）
 *       落在<b>「进入 HttpMiddleware 时当前的那个 span」</b>上
 *       ⇒ 本类的 server span 必须<b>已经 current</b>，否则属性全落到 no-op span 上。</li>
 * </ol>
 * <p>⚠️ <b>与 {@code path.Clean} 的相对位置无关</b>：
 * RAG 的 {@code PathNormalizingFilter} <b>不短路</b>（只包装 request 后继续下传）
 * ⇒ 它自己<b>不产生响应</b> ⇒ 排前排后都不影响任何响应头。
 * ⇒ 之所以仍排在它之后，是因为它要覆写「路径」而本类要读 {@code getRequestURI()} 取 span 名。</p>
 *
 * <h2>📌 favorite 域</h2>
 * <p>本类是<b>共享层</b> ⇒ 10 个 api <b>全部</b>生效，<b>含 favorite</b>。
 * ⇒ 但这<b>不是</b>「给 favorite 加了一个 B 侧 没有的头」：
 * B 的 favorite api 同样挂 {@code TraceHandler} ⇒ <b>B 侧 favorite 也带这个头</b>
 * ⇒ 本次是<b>向 B 侧 收敛</b>，不是制造新的漂移。
 * ⇒ 按 §5.3c 记账并告知该域 owner（favorite 的代码/配置/测试/台账<b>零改动</b>）。
 * <p>⚠️ 但 favorite 也**同吃 T-12**（共享层 ⇒ 全 10 个 api 一致生效）
 * ⇒ 即「方法不匹配」那个出口多带一个 {@code Traceparent} ⇒ 同样<b>不是</b>给 favorite
 * 新造的漂移（B 的 favorite 在该出口同样不带）⇒ 只是**两边的出口层级不同**而已。
 * <p>⚠️ <b>2026-10-30 · G4 lane：本类属 api 侧 rest 中间件 ⇒ 已挂 （D1 合并后已移除，见类注释）</b>
 * ⇒ ⇒ 此前「rpc 域不注册本类」的依据是<b>域侧不扫 {@code com.rag.common} 这道约定</b>（不是本类的条件）
 * ⇒ ⇒ 现由共享层自己兜住 ⇒ ⇒ B 出处与语义等价论证见该注解。
 */
/**
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/http/GoStyleTraceparentFilter.java}
 * §1.12 第 270 行 · 1:1
 * （L2 业务语义层以 RAG 为基准整段抄 · D13）。
 * <p><b>⚠️ 落点差异</b>：RAG 侧在<b>各域 api 模块</b>（或 {@code service/common/http/}）⇒
 * RAG-V1 归位到全局 {@code common/web/}（决策锁 L-13「HTTP 层归位到 web/」）
 * ⇒ ⇒ **包名与类名都变了** ⇒ 调用点要同步改 import 与类名。
 */
/**
 * <p><b>⚠️ D1 落地差异（去 {@code @ConditionalOnApiSide}）</b>：RAG 侧本类挂了
 * {@code @ConditionalOnApiSide}（{@code rag.side=api} ⇒ 只在 api 侧注册）⇒
 * ⇒ 但 RAG-V1 <b>D1 把 api + rpc 合并为单一进程</b> ⇒ ⇒ <b>不存在「api 侧」这个维度</b>
 * ⇒ ⇒ 该条件注解在本仓<b>没有对应物</b>（映射表 §1.12 第 282 行：<b>「不是缺口」</b>）
 * ⇒ ⇒ 本类<b>无条件注册</b>（由 {@code common/autoconfigure/WebAutoConfiguration} 统一装配）。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class TraceparentFilter implements Filter {

    /**
     * 🔴 <b>全局</b> tracer provider —— 2026-10-25（第二十一轮 · global T-11）改。
     *
     * <p><b>此前本类自带一个<b>私有</b> {@link SdkTracerProvider}</b>（不配任何 SpanProcessor）
     * ⇒ span 的 SpanContext <b>有效</b>（所以响应头能写）但<b>不会导出</b>
     * ⇒ 那时的取舍是：「只保证『响应头』这一维，导出链路是另一条独立缺口」。
     *
     * <p><b>为什么现在必须改用全局</b>：
     * T-11 已在共享层装配了<b>全局</b> provider ＋ OTLP exporter（对齐 B 的
     * {@code core/trace/agent.go} 的 {@code startAgent}）⇒
     * <ul>
     *   <li>B 侧只有<b>一个</b> provider：{@code rest} 的 {@code TraceHandler}
     *       （{@code service/common/src/main/java/com/rag/common/http/GoStyleTraceparentFilter.java:209:32-64}）用 {@code otel.GetTracer(...)} 起 server span
     *       ⇒ <b>响应头里的 traceparent 与 jaeger 里导出的 span 是同一条 trace</b>；</li>
     *   <li>若本类继续用私有 provider ⇒ 响应头指向<b>另一条</b>（未导出的）trace
     *       ⇒ <b>trace 在边界处断成两截</b>（客户端拿到的 trace-id 在 jaeger 里查不到）
     *       ⇒ 与 B 侧 不等价。</li>
     * </ul>
     * ⇒ ⇒ 改用全局 provider 后，本类的 span <b>会被导出</b> ⇒ 响应头与导出<b>同源</b> ✓。
     *
     * <p>⚠️ <b>不在静态字段里缓存 tracer</b>：全局 provider 是<b>运行期</b>才装配的
     * （装配点在容器刷新早期，见 {@code GoStyleOtelAutoConfiguration}）
     * ⇒ 静态初始化时缓存会拿到 no-op tracer。
     */
    private static final String TRACER_NAME = "rag-http-server";

    private static Tracer tracer() {
        return io.opentelemetry.api.GlobalOpenTelemetry.get().getTracer(TRACER_NAME);
    }

    /**
     * propagator 组成与 B 侧 一致：W3C TraceContext + W3C Baggage。
     *
     * <p>B 侧 {@code otel.GetTextMapPropagator()} 在装了
     * {@code go.opentelemetry.io/otel/sdk/trace} 时默认就是
     * {@code TraceContext + Baggage} 的 composite。
     */
    private static final TextMapPropagator PROPAGATOR = TextMapPropagator.composite(
            W3CTraceContextPropagator.getInstance(),
            W3CBaggagePropagator.getInstance());

    /** 从入向请求取 {@code traceparent} / {@code tracestate} / baggage。 */
    private static final TextMapGetter<HttpServletRequest> REQUEST_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(HttpServletRequest carrier) {
                    return carrier.getHeaderNames() == null
                            ? Collections.<String>emptyList()
                            : Collections.list(carrier.getHeaderNames());
                }

                @Override
                public String get(HttpServletRequest carrier, String key) {
                    return carrier == null ? null : carrier.getHeader(key);
                }
            };

    /**
     * 往响应头写。
     *
     * <p>🔴 <b>canonical 化键名</b>（与 B 的 {@code textproto.CanonicalMIMEHeaderKey} 同形）：
     * {@code traceparent ⇒ Traceparent}、{@code tracestate ⇒ Tracestate}。
     * ⇒ 否则 Tomcat 会原样发出小写 {@code traceparent}，与 B 的字节级不一致（见类注释）。
     */
    private static final TextMapSetter<HttpServletResponse> RESPONSE_SETTER =
            (carrier, key, value) -> carrier.setHeader(canonicalHeaderName(key), value);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpReq)
                || !(response instanceof HttpServletResponse httpResp)) {
            chain.doFilter(request, response);
            return;
        }

        // ① Extract：入向 traceparent 成为 server span 的 **parent**（B 侧: propagator.Extract）
        Context parent = PROPAGATOR.extract(Context.current(), httpReq, REQUEST_GETTER);

        // ② Start：起一个 SERVER span（B 侧: tracer.Start(ctx, spanName, WithSpanKind(SpanKindServer), …)）
        Span span = tracer().spanBuilder(spanName(httpReq))
                .setParent(parent)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // ③ Inject：**业务执行之前**写响应头（B 侧: propagator.Inject 在 next.ServeHTTP 之前）
            //    ⚠️ 写的是 **server span 自己**的 context（不是入向 parent）——
            //       这是 B 的语义，也是最容易写错的一处。
            PROPAGATOR.inject(Context.current(), httpResp, RESPONSE_SETTER);
            chain.doFilter(request, response);
        } finally {
            span.end();
        }
    }

    /**
     * span 名 —— B 侧 用的是<b>路由 pattern</b>（{@code route.Path}）。
     *
     * <p>本类用请求 URI：Spring 的 {@code BEST_MATCHING_PATTERN_ATTRIBUTE} 要等
     * DispatcherServlet 跑完才填 ⇒ filter 里拿不到；而 span 名<b>不可 wire 观察</b>
     * ⇒ 不为它引入额外耦合。
     */
    private static String spanName(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return (uri == null || uri.isEmpty()) ? "http" : uri;
    }

    /**
     * HTTP 头名 canonical 化（首字母大写 + 每段首字母大写），与 B 的
     * {@code textproto.CanonicalMIMEHeaderKey} 对齐。
     */
    static String canonicalHeaderName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name.length());
        boolean upper = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-') {
                sb.append(c);
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
