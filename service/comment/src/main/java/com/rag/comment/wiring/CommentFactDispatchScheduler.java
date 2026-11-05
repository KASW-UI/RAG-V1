package com.rag.comment.wiring;

import com.rag.common.communityfact.EventDispatcher;
import com.rag.common.communityfact.EventSender;
import com.rag.comment.config.CommentRpcProperties;
import com.rag.comment.service.CommentFactDeliveryService;
import io.opentelemetry.context.Context;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import net.devh.boot.grpc.server.config.GrpcServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;

/**
 * <p><b>事实流派发调度</b> —— 对应 B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/bootstrap/CommentFactDispatchScheduler.java:1-131}
 * （B 侧该文件的语义来源注释写的是 Go 端 {@code service/comment/rpc/cmd/fact-dispatch/main.go:52-77}
 * 的 {@code run(once, logger)}，即 {@code communityfact.RunDispatcher} 的调用点）。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧为基准整段抄 · D13 / D16）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.comment.comment.rpc.bootstrap} 移到 {@code com.rag.comment.wiring}（D7 · D10）；</li>
 *       <li><b>共享层 1:N 拆分</b>（映射表 §1.2 第 144 / 147 行）⇒ B 侧的
 *           {@code Wire.Sender} → {@link EventSender}、
 *           {@code Wire.DCEventSender.create(...)} → {@link EventSender.DCEventSender#create}、
 *           {@code Dispatcher.runAsync(...)} → {@link EventDispatcher#runAsync}；</li>
 *       <li>投递方法名由 B 侧的 {@code dispatchOnce} 改为共享层 SPI 规定的
 *           {@code dispatchFactOnce}（{@link com.rag.common.communityfact.Dispatcher} 的接口方法名），
 *           落地在 {@link CommentFactDeliveryService} 上。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。三道启用门槛（{@code dispatch-enabled} / {@code dc-endpoint} 非空 /
 *       {@code dc-token} ≥32 字节）、{@code once} 的配置项化、启停时序、失败吞吐逐字未改。
 *       ⇒ <b>本类不注册任何对外服务</b>，只驱动「本进程 → DataCenter HTTP 端点」的后台循环。</li>
 *   <li><b>B 出处</b>：B 侧 {@code comment-rpc/.../bootstrap/CommentFactDispatchScheduler.java:1-131}
 *       （逐条对应关系见各方法注释）。</li>
 * </ol>
 *
 * <h2>⚠️ 默认关闭，这是刻意的</h2>
 * <p>B 侧注释自记：Go 侧 fact-dispatch 是<b>独立进程</b>，缺 DC 配置时它自己 {@code return error} ⇒ 退出，
 * <b>不会</b>影响 comment-rpc 的可用性。
 * C 侧合并成单 JVM 后若默认开启，就会把「DC 没配」变成「整个 comment 起不来」
 * ⇒ 改为「{@code comment.fact.dispatch-enabled=false} + 启动时显式告警」，
 * 关闭时该功能<b>完全不参与运行</b>（不创建 sender、不起调度线程）。
 *
 * <h2>🔴🔴 必须用 {@link EventDispatcher#runAsync}，<b>不能</b>用阻塞式的 {@code run}</h2>
 * <p>{@code run} 是<b>阻塞式</b>的：它在<b>调用线程</b>上跑整个投递循环，直到 {@code stop()} 被调用才返回。
 * 而本类的启动点挂在 {@code @PostConstruct} 上 ⇒ 那是 <b>Spring 上下文刷新线程</b>
 * ⇒ 用 {@code run} 会让<b>上下文永远刷新不完</b> ⇒ <b>整个 comment 起不来</b>。
 * <p>⚠️ 这类缺陷<b>「开关默认关闭」时完全测不出来</b>：测试全绿，而真启动就挂。
 *
 * <h2>⚠️ 与 {@link CommentAuthorityEndpointConfig} 的分工</h2>
 * <p>本类<b>不注册任何对外服务</b>（投递方向是「本进程 → DC」）；真正对外暴露 HTTP 的是
 * {@link CommentAuthorityEndpointConfig}（默认也关闭）。
 */
@Component
public class CommentFactDispatchScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CommentFactDispatchScheduler.class);

    private final CommentRpcProperties props;
    private final CommentFactDeliveryService deliveryService;
    private final GrpcServerProperties grpcServerProperties;

    private volatile EventDispatcher.DispatcherHandle handle;

    public CommentFactDispatchScheduler(CommentRpcProperties props,
                                        CommentFactDeliveryService deliveryService,
                                        GrpcServerProperties grpcServerProperties) {
        this.props = props;
        this.deliveryService = deliveryService;
        this.grpcServerProperties = grpcServerProperties;
    }

    /**
     * 启动调度循环 —— 对应 B 侧 {@code CommentFactDispatchScheduler.java:63-105}。
     *
     * <p>⚠️ <b>三道门槛逐条照抄</b>：
     * <ol>
     *   <li>{@code dispatch-enabled=false} ⇒ 直接返回，<b>不创建任何 sender</b>（B 侧 {@code :66-70}）；</li>
     *   <li>{@code dc-endpoint} 为空 或 {@code dc-token} 长度 &lt; 32 ⇒ 记 error 后返回，
     *       <b>不启动</b>（B 侧 {@code :71-78}）——
     *       ⚠️ B 侧此处对应 Go 的「进程退出」，C 侧<b>不能</b>退出（会带走 gRPC 与 Kafka 消费）
     *       ⇒ 只告警不启动，这是<b>多进程 → 单进程</b>带来的生命周期差异，不改变对外行为；</li>
     *   <li>其余情况创建 sender 并 <b>异步</b>启动循环（B 侧 {@code :80-104}）。</li>
     * </ol>
     */
    @PostConstruct
    public void start() {
        CommentRpcProperties.Fact fact = props.getFact();
        if (!fact.isDispatchEnabled()) {
            LOG.info("CommentFactDispatchScheduler: 事实流派发未启用（comment.fact.dispatch-enabled=false）"
                    + "，B 侧为独立进程，此处默认关闭以免 DC 未配置时拖垮 comment");
            return;
        }
        if (fact.getDcEndpoint() == null || fact.getDcEndpoint().isEmpty()
                || fact.getDcToken() == null || fact.getDcToken().length() < 32) {
            // B 侧: CommentFactDispatchScheduler.java:71-78（对应 Go cmd/fact-dispatch/main.go:56-58
            // 的进程退出条件）—— Java 侧不能退出，改为告警后不启动。
            LOG.error("CommentFactDispatchScheduler: 配置不完整（dc-endpoint 非空 + dc-token ≥32 字节），"
                    + "事实流派发不启动。对应 B 侧 CommentFactDispatchScheduler.java:71-78 的进程退出条件");
            return;
        }

        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    // 对应 B 侧 CommentFactDispatchScheduler.java:81-84
                    // （其注释指向 Go cmd/fact-dispatch/main.go:73 的 &http.Client{Timeout: 8 * time.Second}）
                    .connectTimeout(fact.getDcTimeout())
                    .build();
            EventSender sender = EventSender.DCEventSender.create(
                    fact.getDcEndpoint(), fact.getDcToken(), httpClient);

            boolean once = fact.isOnce();
            // 🔴🔴 必须用 **runAsync**（非阻塞）：@PostConstruct 跑在 Spring 上下文刷新线程上，
            //    而 Dispatcher.run 是**阻塞式**的（循环跑在调用线程上，直到 stop() 才返回）
            //    ⇒ 用 run 会让**上下文永远刷新不完** ⇒ 整个 comment 起不来。
            //    这类缺陷在「开关默认关闭」时测不出来（绿测试 + 起不来）。
            this.handle = EventDispatcher.runAsync(
                    Context.current(),
                    deliveryService::dispatchFactOnce,
                    sender,
                    LOG,
                    once,
                    false);
            LOG.info("CommentFactDispatchScheduler: 事实流派发已启动，once={} endpoint={}",
                    once, fact.getDcEndpoint());
        } catch (Exception e) {
            LOG.error("CommentFactDispatchScheduler: 启动失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 容器关闭时停掉循环 —— 对应 B 侧 {@code CommentFactDispatchScheduler.java:107-120}。
     *
     * <p>⚠️ 共享层的调度循环<b>默认不注册 JVM shutdown hook</b>（避免与 Spring 的
     * graceful shutdown 时序冲突、测试场景累积）⇒ 必须显式 stop。
     */
    @PreDestroy
    public void stop() {
        EventDispatcher.DispatcherHandle current = this.handle;
        if (current != null) {
            current.stop();
            LOG.info("CommentFactDispatchScheduler: 事实流派发已停止");
        }
    }

    /** 便于测试与运维观察：当前是否真的在跑（B 侧 {@code :122-125}）。 */
    public boolean isRunning() {
        return handle != null;
    }

    /**
     * 端口仅用于日志对齐（B 侧 {@code :127-130}；B 侧 fact-dispatch 是独立进程，本无端口）。
     */
    int grpcPort() {
        return grpcServerProperties.getPort();
    }
}
