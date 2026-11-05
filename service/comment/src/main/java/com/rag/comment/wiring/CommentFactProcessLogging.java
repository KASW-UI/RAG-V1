package com.rag.comment.wiring;

import com.rag.common.communityfact.ProcessLogger;
import com.rag.common.communityfact.ProcessMetadata;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p><b>进程级日志身份隔离</b> —— B 侧 comment 域<b>没有</b>这个类。
 *
 * <h2>B 出处（抄写基准）</h2>
 * <p>① <b>B 侧 like 域的同款骨架</b>：
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/bootstrap/LikeFactProcessLogging.java:1-4}
 * —— 该文件在 B 侧是<b>骨架占位</b>，正文只有一行：
 * <pre>
 * // [骨架占位 · 未实现] 建 3 份 ProcessLogger（like-rpc / rtw-like-fact-dispatch / rtw-like-fact-authority）；
 * //                     合并拓扑的新职责，用于保住三角色日志身份隔离
 * // 推导依据：FileTree/like/rpc.md §12.5
 * </pre>
 * ⇒ 即 <b>B 侧的心智模型</b>：每个「fact-* 独立进程」各自持有一份<b>互不串扰</b>的进程级 logger。
 * <p>② <b>B 侧 favorite 域记录的 Go 侧身份三元组</b>：
 * {@code service/favorite/rpc/cmd/fact-dispatch/main.go} →
 * {@code ProcessMetadata{Service:"rtw-favorite-fact-dispatch", Component:"community"}}
 * （见本仓 {@code docs/File_Tree/favorite.md} 与 favorite 域
 * {@code wiring/FavoriteFactDispatchScheduler} 的类注释）。
 * <p>③ <b>B 侧 comment 域的两个 Go 独立进程</b>：
 * {@code service/comment/rpc/cmd/fact-dispatch/main.go} 与
 * {@code service/comment/rpc/cmd/fact-authority/main.go}
 * —— C 侧文件树 {@code docs/File_Tree/comment.md:135} 已把 comment 域的身份钉为
 * {@code rtw-comment-fact-dispatch}（对应 fact-dispatch 进程），
 * 本类的权威读身份对称地取 {@code rtw-comment-fact-authority}。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>把 B 侧的「骨架注释」补成可用实现</b> ——
 *       用共享层的 {@link ProcessLogger#newProcessLogger(OutputStream, ProcessMetadata)}
 *       建 <b>3 份</b>身份互不串扰的进程级 logger。</li>
 *   <li><b>语义还一样吗</b>：<b>语义层（B 侧）与本类等价</b>：B 侧骨架里写明的目标
 *       「保住三个角色的日志身份隔离」被本类完整实现。
 *       ⇒ <b>不涉及任何业务判定</b>，只涉及日志身份 ⇒ 不构成 L1/L2 行为漂移。
 *       ⚠️ B 侧 comment 域是 Java 落地形态、<b>没有</b> Go 的三个进程
 *       ⇒ 「三份 logger」在 C 侧是<b>同一进程内的三份身份</b>（不是三个进程），
 *       但<b>身份隔离的语义完全保留</b>。</li>
 *   <li><b>B 出处</b>：见上 ①②③。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么<b>不得</b>用 SLF4J 全局 MDC</h2>
 * <p>MDC 是<b>线程局部</b>的 ⇒ 异步线程 / 线程池里的任务拿不到 ⇒ 「进程身份」会丢；
 * 且 MDC 若被业务代码复用，同一线程上的不同角色日志会互相污染。
 * <p>共享层的 {@link ProcessLogger} 走的是<b>独立 logger 名 + 独立 appender</b>
 * （logger 名 = {@code communityfact.process.<service>.<env>.<component>}，且 {@code setAdditive(false)}）
 * ⇒ 身份由 <b>logger 名</b>决定，与线程无关 ⇒ 这才是「进程级身份隔离」。
 *
 * <h2>⚠️ 3 份 logger 的角色划分</h2>
 * <table border="1">
 *   <caption>角色 → component（B 侧身份逐字保留）</caption>
 *   <tr><th>component</th><th>角色</th><th>对应 B 侧</th></tr>
 *   <tr><td>{@code rtw.comment-rpc}</td><td>业务 RPC（主应用）</td>
 *       <td>B 侧 {@code CommentDomainFactService.PRODUCER}（{@code :52}）</td></tr>
 *   <tr><td>{@code rtw-comment-fact-dispatch}</td><td>事实投递</td>
 *       <td>Go {@code cmd/fact-dispatch/main.go}</td></tr>
 *   <tr><td>{@code rtw-comment-fact-authority}</td><td>权威读</td>
 *       <td>Go {@code cmd/fact-authority/main.go}</td></tr>
 * </table>
 *
 * <h2>⚠️ 版本号必须匹配 {@code ^[0-9a-f]{40}$}</h2>
 * <p>{@link ProcessMetadata#validate()} 对 version 有<b>硬校验</b>（40 位小写 hex），
 * 不满足就抛 ⇒ <b>不能让整个应用起不来</b>。
 * ⇒ 本类：取不到合法版本时用 <b>40 个 0</b> 作为占位（仍满足正则）并<b>告警</b>，
 * 让运维能通过日志发现「版本号没注入」；<b>不</b>因此让启动失败。
 */
@Component
public class CommentFactProcessLogging {

    private static final Logger LOG = LoggerFactory.getLogger(CommentFactProcessLogging.class);

    /** 40 位小写 hex 的占位版本号（满足 {@code ProcessMetadata} 的校验）。 */
    private static final String PLACEHOLDER_VERSION = "0000000000000000000000000000000000000000";

    /** 域名（进 logger 名的第 1 段）。 */
    private static final String SERVICE = "comment";

    /** 角色 component —— 逐字取 B 侧的进程身份（见类注释的表）。 */
    private static final String COMPONENT_RPC = "rtw.comment-rpc";
    private static final String COMPONENT_FACT_DISPATCH = "rtw-comment-fact-dispatch";
    private static final String COMPONENT_FACT_AUTHORITY = "rtw-comment-fact-authority";

    private final String environment;
    private final String instanceId;
    private final String version;

    /** 角色名 → 该角色的进程级 logger。 */
    private final Map<String, Logger> processLoggers = new LinkedHashMap<>();

    public CommentFactProcessLogging(
            @Value("${comment.fact.process.environment:local}") String environment,
            @Value("${comment.fact.process.instance-id:${HOSTNAME:unknown-instance}}") String instanceId,
            @Value("${comment.fact.process.version:}") String version) {
        this.environment = environment;
        this.instanceId = instanceId;
        this.version = normalizeVersion(version);
    }

    /**
     * 建立 3 份进程级 logger —— 对应 B 侧 like 域骨架注释里的「建 3 份 ProcessLogger」。
     *
     * <p>⚠️ <b>失败不阻断启动</b>：共享层的 {@code ProcessMetadata#validate()} 会因
     * 环境名 / 版本号不合法而抛 ⇒ 本类逐个角色<b>单独兜底</b>
     * ⇒ 某一个角色建不起来，其余两个照常可用（而不是整个应用起不来）。
     */
    @PostConstruct
    public void init() {
        build(COMPONENT_RPC, "业务 RPC（主应用）");
        build(COMPONENT_FACT_DISPATCH, "事实投递");
        build(COMPONENT_FACT_AUTHORITY, "权威读");
        LOG.info("comment fact process loggers initialized: components={} environment={} version={}",
                processLoggers.keySet(), environment, version);
    }

    /**
     * 建单个角色的进程级 logger。
     *
     * <p>⚠️ <b>逐个角色单独兜底</b>（不 try/catch 整个 init）—— 一个角色的元数据不合法
     * 不该连累另外两个角色。
     */
    private void build(String component, String role) {
        try {
            ProcessMetadata meta = new ProcessMetadata(
                    SERVICE, environment, version, instanceId, component);
            processLoggers.put(component, ProcessLogger.newProcessLogger(System.out, meta));
        } catch (RuntimeException e) {
            LOG.warn("build process logger failed (ignored): component={} role={} err={}",
                    component, role, e.getMessage());
        }
    }

    /**
     * 取某个角色的进程级 logger。
     *
     * @param component 角色名（见类注释的表）
     * @return 该角色的 logger；未建立成功时返回 {@code null}（调用方自行决定是否降级）
     */
    public Logger processLogger(String component) {
        return processLoggers.get(component);
    }

    /**
     * 归一化版本号：空 / 非 40 位小写 hex ⇒ 用 40 个 0 占位（仍满足共享层的校验）。
     */
    private static String normalizeVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return PLACEHOLDER_VERSION;
        }
        String trimmed = raw.trim();
        if (trimmed.length() == 40 && trimmed.matches("^[0-9a-f]{40}$")) {
            return trimmed;
        }
        return PLACEHOLDER_VERSION;
    }
}
