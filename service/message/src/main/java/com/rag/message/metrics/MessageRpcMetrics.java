package com.rag.message.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p><b>message 域 RPC 侧业务指标</b> —— 承载 5 个 {@code message_rpc_*} 指标。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/metrics/MessageMetrics.java}
 * （B 侧类 javadoc {@code :11-40} · 五个指标名常量 {@code :44-57} · registry {@code :59} ·
 * timerCache {@code :62} · 构造器 {@code :64-66} · {@code observeRpc :68-136} ·
 * {@code observeNotification :138-152} · {@code observeChat :154-168} · {@code observeDbError :170-180}）。
 * ⚠️ <b>类名</b>：B 侧叫 {@code MessageMetrics}，本仓叫 {@code MessageRpcMetrics}
 * （D7 合并 api + rpc 后两侧指标类同包 ⇒ 必须区分 ⇒ B 侧的 {@code MessageMetrics}
 * 这个名字<b>让位给</b>本仓新增的共享基座 {@link MessageMetrics}；
 * 映射表 §7.3 记的「与 {@code MessageRpcMetrics} 职责重叠」缺口由此消掉）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>包名</b>（{@code com.rag.message.message.rpc.metrics} →
 *       {@code com.rag.message.metrics}，D7 包压平）＋ <b>类名</b>
 *       （{@code MessageMetrics} → {@code MessageRpcMetrics}）＋ <b>继承共享基座</b>
 *       {@link MessageMetrics}（前缀 / 标签键 / {@code DefBuckets} / 注册手法收口到一处）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— <b>5 个指标名、标签键、标签取值、description、桶边界逐条一致</b>
 *       （桶边界由基座的 {@code DEF_BUCKETS} 给出，值与 B 侧 {@code :122-133} 的
 *       {@code serviceLevelObjectives(…)} <b>逐个相同</b>）。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p>对应 Go 端
 * {@code service/message/rpc/internal/metrics/metrics.go} 的 prometheus 注册器：
 *
 * <pre>
 * RPCRequestCounter          "message_rpc_request_total"                labels=[method, result]
 * RPCRequestDuration         "message_rpc_request_duration_seconds"     labels=[method]
 * NotificationActionCounter  "message_rpc_notification_action_total"    labels=[kind, result]
 * ChatActionCounter          "message_rpc_chat_action_total"            labels=[action, result]
 * DBErrorCounter             "message_rpc_db_error_total"               labels=[action, type]
 * </pre>
 *
 * <p>Spring Boot 项目用 Micrometer 代替 prometheus client_golang，命名一一对应：
 * 1:1 复刻 Prometheus 标签与 metrics 名，迁移完成后 Prometheus 抓取端不需要改配置。
 *
 * <p>指标语义保持：
 * <ul>
 *   <li>{@code result="success"} / {@code "fail"} 与 Go 一致</li>
 *   <li>{@code kind} 在 notification_action 里枚举：枚举值来自 {@code pb.NotificationKind}
 *       的小写名（{@code admin_broadcast} / {@code admin_notice} / {@code article_comment} /
 *       {@code comment_reply} / {@code article_published} / {@code article_liked}）</li>
 *   <li>{@code action} 在 chat_action 里是 {@code "send"}</li>
 *   <li>{@code type} 在 db_error 里是 SQL 类型短名：
 *       {@code broadcast_user_list} / {@code insert} / {@code list} / {@code mark_read} /
 *       {@code mark_all_read} / {@code notification} / {@code conversation} /
 *       {@code find} / {@code list_messages} / {@code count_unread} /
 *       {@code find_conversation} / {@code find_by_pair} / {@code create_conversation} /
 *       {@code create_pending_conversation} / {@code append_message}</li>
 * </ul>
 */
@Component
public class MessageRpcMetrics extends MessageMetrics {

    /** 对应 Go {@code metric: message_rpc_request_total labels: [method, result]}。 */
    private static final String RPC_REQUEST_TOTAL = "message_rpc_request_total";

    /** 对应 Go {@code metric: message_rpc_request_duration_seconds labels: [method]}。 */
    private static final String RPC_REQUEST_DURATION = "message_rpc_request_duration_seconds";

    /** 对应 Go {@code metric: message_rpc_notification_action_total labels: [kind, result]}。 */
    private static final String NOTIFICATION_ACTION_TOTAL = "message_rpc_notification_action_total";

    /** 对应 Go {@code metric: message_rpc_chat_action_total labels: [action, result]}。 */
    private static final String CHAT_ACTION_TOTAL = "message_rpc_chat_action_total";

    /** 对应 Go {@code metric: message_rpc_db_error_total labels: [action, type]}。 */
    private static final String DB_ERROR_TOTAL = "message_rpc_db_error_total";

    /** 缓存已注册的 timer（按 method name 缓存），避免每次 observeRPC 都重新 query。 */
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public MessageRpcMetrics(MeterRegistry registry) {
        super(registry);
    }

    /**
     * 对应 Go 端 {@code ObserveRPC(method, started, err)}：
     * <ul>
     *   <li>Counter +1（method + result 标签）</li>
     *   <li>Timer 记录耗时（method 标签）</li>
     * </ul>
     *
     * <p>{@code err == null} → {@code result="success"}，否则 {@code result="fail"}。
     */
    public void observeRpc(String method, long startedAtNanos, Throwable err) {
        String resultLabel = resultLabel(err);
        describedCounter(RPC_REQUEST_TOTAL,
                "Total number of message rpc requests",
                TAG_METHOD, method,
                TAG_RESULT, resultLabel)
                .increment();

        Timer timer = timerCache.computeIfAbsent(method, m ->
                describedDefBucketsTimer(RPC_REQUEST_DURATION,
                        "Message rpc request duration in seconds",
                        TAG_METHOD, m));
        timer.record(Duration.ofNanos(System.nanoTime() - startedAtNanos));
    }

    /**
     * 对应 Go 端 {@code ObserveNotification(kind, err)}：
     * 用于 {@code SendNotification} 写操作的 success/fail 计数。
     *
     * <p>{@code kind} 是 {@code pb.NotificationKind} 的 {@code String()} 小写形式。
     */
    public void observeNotification(String kind, Throwable err) {
        String resultLabel = resultLabel(err);
        describedCounter(NOTIFICATION_ACTION_TOTAL,
                "Total number of notification actions",
                TAG_KIND, kind,
                TAG_RESULT, resultLabel)
                .increment();
    }

    /**
     * 对应 Go 端 {@code ObserveChat(action, err)}：
     * 用于 {@code SendChatMessage} 写操作的 success/fail 计数。
     *
     * <p>{@code action} 是语义分类；Go 端 chat_action 只用 {@code "send"}。
     */
    public void observeChat(String action, Throwable err) {
        String resultLabel = resultLabel(err);
        describedCounter(CHAT_ACTION_TOTAL,
                "Total number of chat actions",
                TAG_ACTION, action,
                TAG_RESULT, resultLabel)
                .increment();
    }

    /**
     * 对应 Go 端 {@code ObserveDBError(action, type)}：数据库错误计数。
     */
    public void observeDbError(String action, String type) {
        describedCounter(DB_ERROR_TOTAL,
                "Total number of message rpc database errors",
                TAG_ACTION, action,
                TAG_TYPE, type)
                .increment();
    }
}
