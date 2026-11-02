package com.rag.admin.service;

/**
 * 业务逻辑共享的字符串归一化工具。
 *
 * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminHelpers.java}
 * ⇒ <b>本类为同构照抄</b>（两个静态方法逐字一致）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅包名 {@code rpc.service} → {@code service}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样。两个方法的入参语义、返回值语义、null 处理逐条未动。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p>Java 侧用包装类型 {@link String} 表示 nullable（{@code null} = 没值，{@code ""} = 有值），
 * 与 B 侧的 {@code *string} 语义对齐。
 */
public final class AdminHelpers {

    private AdminHelpers() {}

    /**
     * 对应 B 侧的 {@code optionalString}：trim 后为空则返回 {@code null}，否则返回 trim 后的值。
     */
    public static String optionalString(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }

    /**
     * 对应 B 侧的 {@code stringValue}：{@code null} 归一为 {@code ""}，否则原样返回。
     */
    public static String stringValue(String value) {
        return value == null ? "" : value;
    }
}
