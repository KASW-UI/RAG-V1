package com.rag.like.wiring;

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
 * <p><b>进程级日志身份隔离</b> —— 对应 B 侧
 * {@code like-rpc/src/main/java/com/rag/like/like/rpc/bootstrap/LikeFactProcessLogging.java}
 * （B 侧该类是<b>骨架占位</b>：注释写明「建 3 份 ProcessLogger
 * （like-rpc / rtw-like-fact-dispatch / rtw-like-fact-authority）」）。
 *
 * <p><b>抄写基准</b>：B 侧骨架注释 + 映射表第 157 / 330 行
 * （「C 侧合并后必须保住这几份独立 logger 名，<b>不得用 SLF4J 全局 MDC</b>（会跨线程插值）」
 * ＋「{@code wiring/LikeFactProcessLogging.java} 建 4 份独立进程级 logger（<b>不用 MDC</b>）」）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>把 B 侧的骨架补成可用实现</b> ——
 *       用共享层的 {@link ProcessLogger#newProcessLogger(OutputStream, ProcessMetadata)}
 *       建 <b>4 份</b>身份互不串扰的进程级 logger。</li>
 *   <li><b>语义还一样吗</b>：<b>语义层（B 侧）与本类等价</b>：B 侧骨架里写明的目标
 *       「保住三个角色日志身份隔离」被本类完整实现，且比 B 侧骨架多保住了
 *       Kafka 消费角色（{@code like-mq}）这一份。
 *       ⇒ <b>不涉及任何业务判定</b>，只涉及日志身份 ⇒ 不构成 L1/L2 行为漂移。</li>
 *   <li><b>B 出处</b>：见上（B 侧骨架注释 + 映射表第 157 / 330 行）。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么<b>不得</b>用 SLF4J 全局 MDC</h2>
 * <p>MDC 是<b>线程局部</b>的 ⇒ 异步线程 / 线程池里的任务拿不到 ⇒ 「进程身份」会丢；
 * 且 MDC 若被业务代码复用，同一线程上的不同角色日志会互相污染。
 * <p>共享层的 {@link ProcessLogger} 走的是<b>独立 logger 名 + 独立 appender</b>
 * （{@code communityfact.process.<service>.<env>.<component>}，{@code setAdditive(false)}）
 * ⇒ 身份由 <b>logger 名</b>决定，与线程无关 ⇒ 这才是「进程级身份隔离」。
 *
 * <h2>⚠️ 4 份 logger 的角色划分</h2>
 * <table border="1">
 *   <caption>角色 → component</caption>
 *   <tr><th>component</th><th>角色</th><th>对应 B 侧</th></tr>
 *   <tr><td>{@code like}</td><td>主应用（HTTP + gRPC）</td><td>{@code like-rpc}</td></tr>
 *   <tr><td>{@code like-mq}</td><td>Kafka 消费（落库）</td><td>Go 侧 {@code like-mq}</td></tr>
 *   <tr><td>{@code like-fact-dispatch}</td><td>事实投递</td><td>{@code rtw-like-fact-dispatch}</td></tr>
 *   <tr><td>{@code like-fact-authority}</td><td>权威读</td><td>{@code rtw-like-fact-authority}</td></tr>
 * </table>
 *
 * <h2>⚠️ 版本号必须匹配 {@code ^[0-9a-f]{40}$}</h2>
 * <p>{@link ProcessMetadata#validate()} 对 version 有<b>硬校验</b>（40 位小写 hex），
 * 不满足就抛 ⇒ <b>不能让整个应用起不来</b>。
 * ⇒ 本类：取不到合法版本时用 <b>40 个 0</b> 作为占位（仍满足正则）并<b>告警</b>，
 * 让运维能通过日志发现「版本号没注入」；<b>不</b>因此让启动失败。
 */
@Component
public class LikeFactProcessLogging {

    private static final Logger LOG = LoggerFactory.getLogger(LikeFactProcessLogging.class);

    /** 40 位小写 hex 的占位版本号（满足 {@code ProcessMetadata} 的校验）。 */
    private static final String PLACEHOLDER_VERSION = "0000000000000000000000000000000000000000";

    private static final String SERVICE = "like";

    private final String environment;
    private final String instanceId;
    private final String version;

    /** 角色名 → 该角色的进程级 logger。 */
    private final Map<String, Logger> processLoggers = new LinkedHashMap<>();

    public LikeFactProcessLogging(
            @Value("${like.fact.process.environment:local}") String environment,
            @Value("${like.fact.process.instance-id:${HOSTNAME:unknown-instance}}") String instanceId,
            @Value("${like.fact.process.version:}") String version) {
        this.environment = environment;
        this.instanceId = instanceId;
        this.version = normalizeVersion(version);
    }

    /**
     * 建立 4 份进程级 logger。
     *
     * <p>⚠️ <b>失败不阻断启动</b>：共享层的 {@code ProcessMetadata#validate()} 会因
     * 环境名 / 版本号不合法而抛 ⇒ 本类逐个角色<b>单独兜底</b>
     * ⇒ 某一个角色建不起来，其余三个照常可用（而不是整个应用起不来）。
     */
    @PostConstruct
    public void init() {
        build("like", "主应用（HTTP + gRPC）");
        build("like-mq", "Kafka 消费（落库）");
        build("like-fact-dispatch", "事实投递");
        build("like-fact-authority", "权威读");
        LOG.info("like fact process loggers initialized: components={} environment={} version={}",
                processLoggers.keySet(), environment, version);
    }

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
