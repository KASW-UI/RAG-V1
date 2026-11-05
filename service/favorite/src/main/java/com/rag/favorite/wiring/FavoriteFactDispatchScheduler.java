package com.rag.favorite.wiring;

import com.rag.common.communityfact.EventDispatcher;
import com.rag.common.communityfact.EventSender;
import com.rag.favorite.service.FavoriteFactDispatchService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * <p><b>事实投递的编排层</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/fact/FavoriteFactDispatchScheduler.java}
 * （逐条照抄 · L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h2>循环形状逐条照抄（{@code cmd/fact-dispatch/main.go:67-89}）</h2>
 * <pre>
 * ticker := time.NewTicker(time.Second)          // 节奏 1s
 * for {
 *     for i := 0; i &lt; 16; i++ {                // 每轮最多 16 条
 *         attempt, cancel := context.WithTimeout(ctx, 10*time.Second)   // 单条 10s
 *         sent, dispatchErr := store.DispatchFavoriteFactOnce(attempt, sender)
 *         if dispatchErr != nil { 记日志; break }   // ⚠️ 单条失败即跳出内层
 *         if !sent { break }                          // ⚠️ 队列空即跳出内层
 *     }
 *     select { case &lt;-ctx.Done(): return; case &lt;-ticker.C: }
 * }
 * </pre>
 * ⇒ 这四个形状（<b>1s / 16 / 10s / 单条失败即 break</b>）由
 * {@link EventDispatcher#runAsync} 直接承担，本类只负责装配 + 生命周期。
 *
 * <h2>⚠️ Go 是独立进程，Java 是单 JVM 的可选组件</h2>
 * <p>Go 侧 {@code cmd/fact-dispatch/main.go:35-40} 要求三个环境变量齐全，缺一即进程退出。
 * 单 JVM 里<b>不能</b>让「没配 DC」拖垮 favorite-rpc ⇒
 * {@code favorite.fact.dispatch.enabled} 默认 <b>false</b>，
 * 未启用时本类<b>完全不启动任何线程</b>（不是「启动后发现没配置」）。
 *
 * <p>⚠️ <b>多副本下的行为放大</b>：Go 是「1 个 dispatch 进程」，
 * Java 合并后是「每副本 1 个」。这在语义上<b>安全</b> ——
 * {@code FOR UPDATE SKIP LOCKED} + 前驱屏障本就是为多实例设计的
 * （见 {@code FavoriteFactOutboxMapper#claimNextDeliverable}），
 * 只是吞吐上限从「1 进程」变成「每 JVM 16 × N 副本」。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.favorite.favorite.rpc.fact} 压平为
 *           {@code com.rag.favorite.wiring}（D7 包压平 + D10 {@code bootstrap} → {@code wiring}）；</li>
 *       <li>共享层 1:N 拆分 ⇒ {@code Wire.Sender} → 顶层 {@link EventSender}、
 *           {@code Wire.DCEventSender} → {@link EventSender.DCEventSender}、
 *           {@code Dispatcher.runAsync} → {@link EventDispatcher#runAsync}
 *           （映射表 §1.2 第 144 / 146 行 / 决策锁 L-7）；</li>
 *       <li><b>配置读取方式</b>：B 侧注入 {@code FavoriteRpcProperties}；
 *           C 侧 {@code com.rag.favorite.config.FavoriteRpcProperties} 是
 *           <b>配置层的落点</b>、不由本类拥有（写权面限制）
 *           ⇒ 本类用 {@code @Value} 读<b>同一批键名</b>
 *           （{@code favorite.fact.dispatch.enabled} / {@code .dc-endpoint} /
 *           {@code .dc-token} / {@code .dc-timeout} / {@code .once}）
 *           ⇒ 与 B 侧 {@code application.yml:302-320} 的键名<b>逐字一致</b>。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。三道门槛（{@code enabled} / endpoint 与 token 非空 /
 *       sender 装配成功）的判定与<b>顺序</b>逐条未改；「未启用 ⇒ 一条线程都不起」、
 *       「配置不全 ⇒ 只告警并保持停用」（<b>不</b>退出进程）、{@code once} 的语义、
 *       {@code @PreDestroy} 停循环、{@link #isRunning()} 的含义逐条未改。</li>
 *   <li><b>B 出处</b>：
 *       {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/fact/FavoriteFactDispatchScheduler.java:51-175}
 *       （类声明 :51-52 · 字段 :56-61 · 构造器 :63-69 ·
 *       {@code start} :77-142 · 三道门槛 :79-91 ·
 *       进程级 logger :93-94 · sender 装配 :96-104 · 启动日志 :106-108 ·
 *       {@code runAsync} :135-141 · {@code stop} :151-158 ·
 *       {@code isRunning} :167-169 · {@code touchOtel} :172-175）。</li>
 * </ol>
 *
 * <p>⚠️ <b>{@code runAsync} 的理由（B 侧已写明，逐条继承）</b>：
 * B 侧的 {@code Dispatcher.runAsync} 已把「Go 侧停止 dispatcher 的唯一途径 = 调用方 ctx 变 done」
 * 翻译成「句柄先发布、调用方随时可 stop()」⇒ 阻塞式 {@code run} 在 {@code once=false} 时
 * 永远交不出句柄（{@code @PreDestroy} 拿到 null ⇒ 循环停不下来）
 * 且会阻塞 {@code SpringApplication.run()} 的主线程 ⇒ 进程无法优雅关闭。
 */
@Component
public class FavoriteFactDispatchScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteFactDispatchScheduler.class);

    private final boolean dispatchEnabled;
    private final String dcEndpoint;
    private final String dcToken;
    private final Duration dcTimeout;
    private final boolean once;
    private final FavoriteFactDispatchService dispatchService;
    private final FavoriteFactProcessLogging processLogging;

    /** {@code EventDispatcher.runAsync(...)} 返回的句柄（<b>进入循环之前就发布</b>）；{@code @PreDestroy} 用它停止循环。 */
    private volatile EventDispatcher.DispatcherHandle handle;

    public FavoriteFactDispatchScheduler(
            @Value("${favorite.fact.dispatch.enabled:false}") boolean dispatchEnabled,
            @Value("${favorite.fact.dispatch.dc-endpoint:}") String dcEndpoint,
            @Value("${favorite.fact.dispatch.dc-token:}") String dcToken,
            @Value("${favorite.fact.dispatch.dc-timeout:8s}") Duration dcTimeout,
            @Value("${favorite.fact.dispatch.once:false}") boolean once,
            FavoriteFactDispatchService dispatchService,
            FavoriteFactProcessLogging processLogging) {
        this.dispatchEnabled = dispatchEnabled;
        this.dcEndpoint = dcEndpoint;
        this.dcToken = dcToken;
        this.dcTimeout = dcTimeout;
        this.once = once;
        this.dispatchService = dispatchService;
        this.processLogging = processLogging;
    }

    /**
     * 对应 Go {@code main()} 的 {@code run(once, logger)}（cmd/fact-dispatch/main.go:34-89）。
     *
     * <p>监听 {@link ApplicationReadyEvent}：与 Go 侧一样，必须在
     * Flyway 建表完成之后才启动投递循环，否则第一轮取件会撞上「表不存在」。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!dispatchEnabled) {
            LOG.info("FavoriteFactDispatchScheduler: 未启用（favorite.fact.dispatch.enabled=false）"
                    + " ⇒ 事实投递完全不参与运行");
            return;
        }
        if (!isConfigured()) {
            // Go: cmd/fact-dispatch/main.go:36-40 —— 缺配置即 return error → 进程退出。
            // ⚠️ 单 JVM 里刻意**不**退出：只告警并保持停用。
            LOG.error("FavoriteFactDispatchScheduler: 已启用但配置不完整"
                    + "（favorite.fact.dispatch.dc-endpoint / dc-token 为空）⇒ 保持停用，不启动投递循环");
            return;
        }

        Logger processLogger =
                processLogging.loggerFor(FavoriteFactProcessLogging.SERVICE_DISPATCH,
                        FavoriteFactProcessLogging.COMPONENT_DISPATCH);

        final EventSender sender;
        try {
            // Go: cmd/fact-dispatch/main.go:56 —— NewFavoriteDCEventSender(endpoint, token, &http.Client{Timeout: 8s})
            sender = EventSender.DCEventSender.create(dcEndpoint, dcToken,
                    HttpClient.newBuilder().connectTimeout(dcTimeout).build());
        } catch (RuntimeException e) {
            LOG.error("FavoriteFactDispatchScheduler: DC sender 装配失败 ⇒ 保持停用: {}", e.getMessage(), e);
            return;
        }

        // Go: cmd/fact-dispatch/main.go:36 —— DC_PLATFORM_EVENT_URL
        LOG.info("FavoriteFactDispatchScheduler: 启动事实投递 once={} dc_endpoint={}",
                once, dcEndpoint);

        // ── 以下四个参数由 EventDispatcher 直接承担，逐条对应 Go cmd/fact-dispatch/main.go:57-89 ──
        //   批次 16 / 节奏 1s / 单次 10s / 单条失败即 break
        // ⚠️ registerShutdownHook = false：Spring 化路径由 @PreDestroy 显式 stop()，
        //    避免 JVM hook 与 SpringApplication 的 graceful shutdown 时序冲突。
        //
        // 🔴🔴 L4 idiomatization：必须用 **runAsync**（非阻塞），不得用阻塞式 EventDispatcher.run。
        // <p>Go 出处：service/favorite/rpc/cmd/fact-dispatch/main.go:34-89 的 run(once, logger) ——
        //   其中 :50 `ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)`
        //   + :51 `defer stop()` ⇒ Go 侧停止 dispatcher 的**唯一**途径 = **调用方自己的 ctx 变 done**；
        //   且 Go 的 RunDispatcher（service/common/communityfact/process.go:20）**不返回任何东西**。
        // <p>语义等价论证：① Go 侧「停止能力从调用那一刻起就归调用方」⇒ Java 侧对应物 = **句柄先发布**、
        //   调用方随时可 stop() ⇒ runAsync 在**进入循环之前**就 complete 句柄 ⇒ 形状一致；
        //   ② 循环的四个参数（批次 16 / 节奏 1s / 单次 10s / 单条失败即 break）**一字未改** ⇒ 行为面不变；
        //   ③ 差异只在**谁来跑这个循环**：Go 是独立进程主线程，Java 是单 JVM 内的守护线程 ⇒ **表达层差异，非行为漂移**。
        Context ctx = Context.current();
        this.handle = EventDispatcher.runAsync(ctx,
                (dispatchCtx, wireSender, logger) -> dispatchService.dispatchFactOnce(dispatchCtx, wireSender),
                sender,
                processLogger,
                once,
                false);
    }

    /**
     * 容器关闭时停止投递循环 —— 对应 Go 的 {@code ctx.Done()} 分支
     * （cmd/fact-dispatch/main.go:85-86 {@code case <-ctx.Done(): return nil}）。
     *
     * <p>⚠️ Go 的 5s 优雅关闭（fact-authority/main.go:66-69）在 dispatch 侧不存在 ——
     * fact-dispatch 是 {@code return nil} 直接退出。所以本类<b>不</b>等待在途投递完成。
     */
    @PreDestroy
    public void stop() {
        EventDispatcher.DispatcherHandle current = this.handle;
        if (current != null) {
            current.stop();
            LOG.info("FavoriteFactDispatchScheduler: 投递循环已停止");
        }
    }

    /**
     * 便于测试与运维观察：当前是否真的在跑。
     *
     * <p>🔴 <b>这条就是 DRIFT-CF-018 的探针</b>：句柄非 null ⇔ 循环已启动
     * 且 {@code @PreDestroy} 拿得到句柄 ⇒ 能停 ⇒ 进程可优雅关闭。
     * 阻塞式 {@code EventDispatcher.run} 形态下 {@code once=false} ⇒ 句柄永远交不出来 ⇒ 恒为 false。
     */
    public boolean isRunning() {
        return handle != null;
    }

    /**
     * 对应 B 侧 {@code FavoriteRpcProperties.Dispatch#isConfigured()}
     * （B 侧 {@code config/FavoriteRpcProperties.java} 的 {@code isConfigured}）：
     * {@code dsn == "" || endpoint == "" || token == ""}。
     *
     * <p>⚠️ Go 还要求 {@code FAVORITE_DATABASE_URL}；Java 侧没有这个环境变量
     * （数据源来自 {@code spring.datasource}），但 token 长度约束
     * （{@code EventSender.DCEventSender.create} 要求 ≥32）由框架承担，见
     * {@link #start()} 的装配校验。
     */
    private boolean isConfigured() {
        return dcEndpoint != null && !dcEndpoint.isEmpty()
                && dcToken != null && !dcToken.isEmpty();
    }

    /** 保留引用：otel 的全局 provider 在 Dispatcher 内部使用；此处显式 touch 以固定依赖。 */
    @SuppressWarnings("unused")
    private static void touchOtel() {
        GlobalOpenTelemetry.get().getTracer("sea.rtw.communityfact");
    }
}
