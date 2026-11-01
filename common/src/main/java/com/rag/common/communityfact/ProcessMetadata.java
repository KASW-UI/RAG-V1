package com.rag.common.communityfact;

import java.util.regex.Pattern;

/**
 * <p><b>Go 出处</b>：{@code service/common/communityfact/logging.go} 的
 * {@code ProcessMetadata struct} ＋ {@code func (m ProcessMetadata) Validate() error}。
 *
 * <p><b>⚠️ 两条校验规则（行为契约）</b>：
 * <ol>
 *   <li><b>{@code version} 必须匹配 {@code ^[0-9a-f]{40}$}</b>（40 位小写 hex）；</li>
 *   <li><b>{@code environment} ∈ {local, test, staging, production}</b>。</li>
 * </ol>
 * ⇒ 另需 {@code service} / {@code instanceId} / {@code component} 非空。
 * ⇒ 任一不满足 ⇒ 抛 {@link IllegalArgumentException}。
 *
 * <p><b>⚠️ 拆出后的落点差异</b>：RAG 侧 {@code ProcessMetadata} 是 {@code ProcessLogger.java} 的
 * <b>嵌套</b>类型 ⇒ ⇒ RAG-V1 拆成本文件的<b>顶层</b>类型
 * （映射表 §1.2 第 147 行 / 决策锁 L-7 第 7 条：
 * 「{@code ProcessMetadata} 从 {@code ProcessLogger} 里拆出」）
 * ⇒ ⇒ 调用点由 {@code ProcessLogger.ProcessMetadata} 改为 {@code ProcessMetadata} ⇒ **import 要同步改**。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/ProcessLogger.java}
 * 的嵌套类 {@code ProcessMetadata}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
public final class ProcessMetadata {

    /** process version 必须 40 位小写 hex。 */
    private static final Pattern PROCESS_VERSION = Pattern.compile("^[0-9a-f]{40}$");

    public String service;
    public String environment;
    public String version;
    public String instanceId;
    public String component;

    public ProcessMetadata() {}

    public ProcessMetadata(String service, String environment, String version,
                           String instanceId, String component) {
        this.service = service;
        this.environment = environment;
        this.version = version;
        this.instanceId = instanceId;
        this.component = component;
    }

    /**
     * 对应 Go 版 {@code func (m ProcessMetadata) Validate() error}。
     */
    public void validate() {
        if (service == null || service.isEmpty()
                || instanceId == null || instanceId.isEmpty()
                || component == null || component.isEmpty()
                || version == null || !PROCESS_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("community fact process identity incomplete");
        }
        if (!("local".equals(environment) || "test".equals(environment)
                || "staging".equals(environment) || "production".equals(environment))) {
            throw new IllegalArgumentException("community fact process environment invalid");
        }
    }

    String resolved(String value) {
        return (value == null || value.isEmpty()) ? "unresolved" : value;
    }
}
