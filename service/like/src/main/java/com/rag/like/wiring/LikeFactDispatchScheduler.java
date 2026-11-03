package com.rag.like.wiring;

import com.rag.like.config.LikeRpcProperties;
import com.rag.like.service.LikeFactDispatchService;
import com.rag.common.communityfact.EventDispatcher;
import com.rag.common.communityfact.EventSender;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;

/**
 * <p><b>事实流派发调度器</b> —— 对应 B 侧
 * {@code like-rpc/src/main/java/com/rag/like/like/rpc/mq/LikeFactDispatchScheduler.java}。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.like.like.rpc.mq} 移到 {@code com.rag.like.wiring}（D10）；</li>
 *       <li>共享层 1:N 拆分 ⇒ {@code Wire.Sender} → {@link EventSender}、
 *           {@code Wire.DCEventSender} → {@link EventSender.DCEventSender}、
 *           {@code Dispatcher.runAsync} → {@link EventDispatcher#runAsync}。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。启用的三道门槛（{@code dispatch-enabled} / endpoint 非空 /
 *       token ≥32 字节）、{@code once} 的配置项化（决策记录 <b>P6-a</b> ①）、
 *       启停与错误吞吐逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>⚠️ 本类默认<b>不</b>启用</h2>
 * <p>Go 侧是<b>独立进程</b>（{@code cmd/fact-dispatch}），配置不全就 {@code return error} 让进程退出。
 * C 侧是单 JVM 合并进程，缺 DC 配置<b>不能</b>让整个 like 起不来
 * ⇒ 改为「默认关闭 + 启动时告警」，关闭时该功能完全不参与运行。
 * 这是「多进程 → 单进程」带来的<b>生命周期</b>差异，不改变任何对外行为。
 *
 * <h2>⚠️ 批次 16 / 节奏 1s / deadline 10s 在<b>共享层</b></h2>
 * <p>这三项由 {@link EventDispatcher} 承担，本类<b>不重复实现</b>。
 */
@Component
public class LikeFactDispatchScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(LikeFactDispatchScheduler.class);

    private final LikeRpcProperties props;
    private final LikeFactDispatchService deliveryService;
    private volatile EventDispatcher.DispatcherHandle handle;

    public LikeFactDispatchScheduler(LikeRpcProperties props, LikeFactDispatchService deliveryService) {
        this.props = props;
        this.deliveryService = deliveryService;
    }

    @PostConstruct
    public void start() {
        LikeRpcProperties.Fact fact = props.getFact();
        if (!fact.isDispatchEnabled()) {
            LOG.info("LikeFactDispatchScheduler: 事实流派发未启用（like.fact.dispatch-enabled=false）"
                    + "，Go 侧为独立进程，此处默认关闭以免 DC 未配置时拖垮 like");
            return;
        }
        if (fact.getDcEndpoint() == null || fact.getDcEndpoint().isEmpty()
                || fact.getDcToken() == null || fact.getDcToken().length() < 32) {
            LOG.error("LikeFactDispatchScheduler: 配置不完整（dc-endpoint 非空 + dc-token ≥32 字节），"
                    + "事实流派发不启动。对应 Go cmd/fact-dispatch/main.go:55-56 的进程退出条件");
            return;
        }
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(fact.getDcTimeout())
                    .build();
            EventSender sender = EventSender.DCEventSender.create(
                    fact.getDcEndpoint(), fact.getDcToken(), httpClient);
            boolean once = fact.isOnce();
            this.handle = EventDispatcher.runAsync(
                    Context.current(),
                    deliveryService::dispatchFactOnce,
                    sender,
                    LOG,
                    once,
                    false);
            LOG.info("LikeFactDispatchScheduler: 事实流派发已启动，once={} endpoint={}",
                    once, fact.getDcEndpoint());
        } catch (Exception e) {
            LOG.error("LikeFactDispatchScheduler: 启动失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void stop() {
        EventDispatcher.DispatcherHandle current = this.handle;
        if (current != null) {
            current.stop();
            LOG.info("LikeFactDispatchScheduler: 事实流派发已停止");
        }
    }

    public boolean isRunning() {
        return handle != null;
    }
}
