package com.rag.favorite.wiring;

import com.rag.common.communityfact.ProcessLogger;
import com.rag.common.communityfact.ProcessMetadata;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p><b>进程级日志身份隔离</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/bootstrap/FavoriteFactProcessLogging.java}
 * （逐条照抄 · L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h2>🔴 三份进程身份（L1 契约 · 不可合并）</h2>
 * <pre>
 * favorite.rpc                  ← 业务 RPC 进程（Go P1）
 * rtw-favorite-fact-dispatch    ← FavoriteFactDispatchScheduler（Go P2）
 * rtw-favorite-fact-authority   ← FavoriteAuthorityEndpointConfig（Go P3）
 * </pre>
 *
 * <p>Go 侧四个进程各自有一条独立的 slog 身份：
 * <pre>
 * P1 gRPC            etc/favorite.yaml  Log.ServiceName: favorite.rpc
 * P2 fact-dispatch   cmd/fact-dispatch/main.go:25   service = rtw-favorite-fact-dispatch
 * P3 fact-authority  cmd/fact-authority/main.go:22  service = rtw-favorite-fact-authority
 * P4 legacy-preflight cmd/fact-legacy-preflight/main.go:23  service = rtw-favorite-legacy-preflight
 * </pre>
 * RAG 侧合并成单 JVM 后，如果只留一个 SLF4J 树，
 * 就<b>无法区分</b>「这条 dispatch 日志来自哪个进程」——
 * 而 fact-dispatch 的 blocked / retry 日志恰恰需要单独归因
 * （{@code favorite.delivery.blocked} 这类结构化事件在运维上是独立告警项）。
 *
 * <p><b>⚠️ P4（legacy-preflight）的身份常量保留，但 C 侧没有它的调用方</b>：
 * B 侧唯一调用 {@code loggerFor(SERVICE_PREFLIGHT, ...)} 的是
 * {@code fact/FavoriteLegacyPreflightRunner.java:113}，
 * 而该文件<b>刻意不在 C 侧创建</b>（触发入口形态未拍板 ⇒ 决策记录 P6-c）⇒
 * 本类的 {@link #SERVICE_PREFLIGHT} 是<b>保留常量</b>，不是已接线的身份。
 *
 * <h2>⚠️ 为什么<b>不得</b>用 SLF4J 全局 MDC</h2>
 * <p>MDC 是<b>线程局部</b>的 ⇒ 异步线程 / 线程池里的任务拿不到 ⇒ 「进程身份」会丢；
 * 且 MDC 若被业务代码复用，同一线程上的不同角色日志会互相污染。
 * <p>共享层的 {@link ProcessLogger} 走的是<b>独立 logger 名 + 独立 appender</b>
 * （{@code communityfact.process.<service>.<env>.<component>}，{@code setAdditive(false)}）
 * ⇒ 身份由 <b>logger 名</b>决定，与线程无关 ⇒ 这才是「进程级身份隔离」。
 *
 * <h2>⚠️ 只在对应功能<b>启用</b>时创建</h2>
 * <p>{@link ProcessMetadata#validate()} 要求 {@code version} 是 40 位小写 hex
 * （Git commit SHA）。拿不到时用「全 0」占位并降级为普通 SLF4J 名称，
 * 而不是让 {@code newProcessLogger} 抛异常拖垮整个容器。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.favorite.favorite.rpc.bootstrap} 压平为
 *           {@code com.rag.favorite.wiring}（D7 包压平 + D10 {@code bootstrap} → {@code wiring}）；</li>
 *       <li>共享层 1:N 拆分 ⇒ {@code ProcessLogger.ProcessMetadata} → 顶层
 *           {@link ProcessMetadata}（映射表 §1.2 第 147 行 / 决策锁 L-7 第 7 条）；</li>
 *       <li><b>建立三份身份的动作提前到 {@link #init()}（{@code @PostConstruct}）</b> ——
 *           B 侧是「谁要用谁调 {@code loggerFor}」的懒创建，且
 *           {@code SERVICE_AUTHORITY} / {@code SERVICE_RPC} 两个常量<b>在 B 侧无人引用</b>
 *           ⇒ B 侧实际只建立过 dispatch / preflight 两份。
 *           C 侧按「三份身份必须并存」的形态要求，在启动时就把三份都建出来
 *           （每份 = 独立 logger 名 + 独立 appender），
 *           避免「某份身份要等到第一次调用才存在」这种时序差异；</li>
 *       <li><b>按 (service, component) 缓存已建好的 logger</b> ——
 *           B 侧每次调用都重建（{@code ProcessLogger.newProcessLogger} 内部
 *           {@code detachAndStopAllAppenders()} 后再挂新 appender），
 *           观测结果一致（logger 名与 appender 都相同），只是少一次 appender 抖动。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。版本号解析（{@code GIT_COMMIT} 环境变量 +
 *       40 位小写 hex 校验 + 全 0 占位）、环境名映射（profile → local/test/staging/production）、
 *       「拿不到 commit SHA 不让 favorite 起不来」的降级路径、三份身份的
 *       <b>取值</b>（service 名）逐条未改。</li>
 *   <li><b>B 出处</b>：
 *       {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/bootstrap/FavoriteFactProcessLogging.java:35-121}
 *       （类声明 :35-36 · 常量 :44-47 · {@code UNKNOWN_VERSION} :53 ·
 *       字段与构造器 :55-68 · {@code loggerFor} :78-88 ·
 *       {@code resolveEnvironment} :95-108 · {@code resolveVersion} :114-120）。</li>
 * </ol>
 */
@Component
public class FavoriteFactProcessLogging {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteFactProcessLogging.class);

    /**
     * Go 侧四个进程的 service 身份（逐字照抄 cmd/* 里的字面量：
     *     {@code service/favorite/rpc/cmd/fact-authority/main.go:22}、
     *     {@code service/favorite/rpc/cmd/fact-dispatch/main.go:25}、
     *     {@code service/favorite/rpc/cmd/fact-legacy-preflight/main.go:19}）。
     */
    public static final String SERVICE_DISPATCH = "rtw-favorite-fact-dispatch";
    public static final String SERVICE_AUTHORITY = "rtw-favorite-fact-authority";
    public static final String SERVICE_PREFLIGHT = "rtw-favorite-legacy-preflight";

    /**
     * 🔴 <b>Go P1（业务 RPC 进程）的进程身份</b> = {@code favorite.rpc}
     * （Go {@code etc/favorite.yaml:1} 的 {@code Name: favorite.rpc}）。
     *
     * <p>⚠️ <b>与 B 侧 Java 同名常量的值不同</b>：B 侧写的是 {@code "favorite-rpc"}，
     * 而那个值是 <b>Nacos 服务名</b>（B 侧 {@code FavoriteNacosRegistration.java:48}
     * 的 {@code SERVICE_NAME}，其 javadoc 明写「与 {@code etc/favorite.yaml:1} 的
     * {@code Name: favorite.rpc} 一致」⇒ 二者<b>刻意不同</b>：Nacos 名用连字符、
     * 进程身份用点号）。
     * <p>⇒ B 侧 {@code FavoriteFactProcessLogging.SERVICE_RPC} 这个常量
     * <b>在 B 侧无人引用</b>（全仓 grep：只有它自己的声明处）⇒ 它不是任何运行期行为。
     * ⇒ 本类按 <b>Go 的进程身份</b> 取 {@code favorite.rpc}，
     * 否则建出来的 logger 名会是 {@code communityfact.process.favorite-rpc.…}
     * ⇒ 与 Go 的 {@code communityfact.process.favorite.rpc.…} 不同。
     */
    public static final String SERVICE_RPC = "favorite.rpc";

    /** Go P1 的 component 名（B 侧无对应常量 —— B 侧 P1 从不建进程级 logger）。 */
    public static final String COMPONENT_RPC = "favorite-rpc";

    /** Go P2 的 component 名（B 侧 {@code FavoriteFactDispatchScheduler.java:94} 传入的字面量）。 */
    public static final String COMPONENT_DISPATCH = "fact-dispatch";

    /** Go P3 的 component 名（与 like 域的 {@code fact-authority} 同款命名）。 */
    public static final String COMPONENT_AUTHORITY = "fact-authority";

    /**
     * {@code ProcessMetadata.validate()} 要求 40 位小写 hex；
     * 拿不到 commit SHA 时用全 0 占位（validate 只做格式检查，不校验是否真实存在）。
     */
    private static final String UNKNOWN_VERSION = "0000000000000000000000000000000000000000";

    private final String instanceId;
    private final String environment;
    private final String version;

    /** 已建立的进程级 logger：key = {@code service + "/" + component}。 */
    private final Map<String, Logger> processLoggers = new ConcurrentHashMap<>();

    public FavoriteFactProcessLogging(
            @Value("${spring.application.name:favorite-service}") String service,
            @Value("${HOSTNAME:unknown}") String hostname,
            Environment environment) {
        this.instanceId = hostname;
        this.environment = resolveEnvironment(environment);
        this.version = resolveVersion();
        LOG.info("FavoriteFactProcessLogging: 4 个进程身份将使用 version={} environment={} instance_id={} service={}",
                version, environment, instanceId, service);
    }

    /**
     * 🔴 <b>启动即建立三份进程身份</b>（Go P1 / P2 / P3）。
     *
     * <p>⚠️ <b>为什么提前建</b>：合并成单 JVM 后，三份身份必须<b>并存</b>且各有独立 appender
     * ⇒ 若「谁用谁建」，那么「还没被调用过的那份」根本不存在
     * ⇒ 运维侧按 logger 名分流时，会发现某份身份时有时无。
     *
     * <p>⚠️ <b>建不起来不阻断启动</b>：{@link ProcessMetadata#validate()} 会因
     * 环境名 / 版本号不合法而抛 ⇒ 本方法逐个身份<b>单独兜底</b>
     * ⇒ 某一个身份建不起来，其余两个照常可用（而不是整个应用起不来）。
     */
    @PostConstruct
    public void init() {
        loggerFor(SERVICE_RPC, COMPONENT_RPC);
        loggerFor(SERVICE_DISPATCH, COMPONENT_DISPATCH);
        loggerFor(SERVICE_AUTHORITY, COMPONENT_AUTHORITY);
        LOG.info("FavoriteFactProcessLogging: 已建立进程级 logger 身份: service={} dispatch={} authority={} "
                        + "environment={} version={} instance_id={}",
                SERVICE_RPC, SERVICE_DISPATCH, SERVICE_AUTHORITY, environment, version, instanceId);
    }

    /**
     * 取（或按需创建）某个进程角色的 {@link ProcessLogger}。
     *
     * <p>⚠️ {@link ProcessLogger#newProcessLogger} 需要一个 {@link java.io.OutputStream}；
     * 本实现传 {@code System.out}，实际 appender 由 logback 配置接管
     * （与 like 域的同名职责同款做法）。版本缺失时降级为普通 SLF4J logger，
     * 保证「拿不到 commit SHA」不会让 favorite 起不来。
     */
    public Logger loggerFor(String processService, String component) {
        String key = processService + "/" + component;
        Logger cached = processLoggers.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            ProcessMetadata meta = new ProcessMetadata(
                    processService, environment, version, instanceId, component);
            Logger created = ProcessLogger.newProcessLogger(System.out, meta);
            Logger previous = processLoggers.putIfAbsent(key, created);
            return previous != null ? previous : created;
        } catch (RuntimeException e) {
            LOG.warn("ProcessLogger 不可用（version={} environment={}），降级为普通 SLF4J logger: {}",
                    version, environment, e.getMessage());
            return LoggerFactory.getLogger("communityfact.process." + processService + "." + component);
        }
    }

    /**
     * 环境名：Go 侧 {@code ProcessMetadata.environment} 取值域是
     * {@code local / test / staging / production}。
     * 优先从 Spring profile 推断（与 {@link FavoriteSubjectRefGate} 的 dev/test 映射一致）。
     */
    private static String resolveEnvironment(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equals(profile) || "production".equals(profile)) {
                return "production";
            }
            if ("staging".equals(profile)) {
                return "staging";
            }
            if ("test".equals(profile)) {
                return "test";
            }
        }
        return "local";
    }

    /**
     * Git commit SHA：优先读 {@code GIT_COMMIT} 环境变量，
     * 拿不到用全 0 占位（validate 只校验格式）。
     */
    private static String resolveVersion() {
        String fromEnv = System.getenv("GIT_COMMIT");
        if (fromEnv != null && fromEnv.matches("[0-9a-f]{40}")) {
            return fromEnv;
        }
        return UNKNOWN_VERSION;
    }
}
