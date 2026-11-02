package com.rag.admin.service;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * admin 域的<b>手写 span</b>。
 *
 * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/service/AdminSpans.java}
 * ⇒ <b>本类为同构照抄</b>（span 名 / 属性名 / tracer 名逐条未动 —— 这些是 L2 语义面）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅包名 {@code rpc.service} → {@code service}（D7）。
 *       方法体（{@code traced} 的 span 创建 / 属性设置 / finally 收尾）逐字照抄。</li>
 *   <li><b>语义还一样吗</b>：一样。B 侧的「ctx 显式传递」在 Java 侧本来就没有对应参数，
 *       B 侧自己就是用 {@link Scope} 模拟「span 进入当前上下文、退出时还原」⇒ 本仓照抄 B 侧形态，
 *       <b>不是本仓新增的 idiomatization</b>。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>🔴 span 名清单（只有 8 个，其余 5 个用例本来就没有 span）</b>：
 * <table border="1">
 *   <tr><th>用例</th><th>span 名</th><th>属性</th></tr>
 *   <tr><td>login</td><td>{@code Action-Admin-Login}</td>
 *       <td>{@code audit.admin_username = in.Username}</td></tr>
 *   <tr><td>createAdmin</td><td>{@code Action-Admin-CreateAdmin}</td>
 *       <td>{@code audit.admin_username = username}<b>（trim 之后）</b></td></tr>
 *   <tr><td>createInviteCode</td><td>{@code Action-Admin-CreateInviteCode}</td>
 *       <td>{@code audit.operator_id}</td></tr>
 *   <tr><td>updateUser</td><td>{@code Action-Admin-UpdateUser}</td>
 *       <td>{@code audit.operator_id}、{@code audit.target_uid}</td></tr>
 *   <tr><td>banUser</td><td>{@code Action-Admin-BanUser}</td>
 *       <td>{@code audit.operator_id}、{@code audit.target_uid}</td></tr>
 *   <tr><td>unBanUser</td><td>{@code Action-Admin-UnBanUser}</td>
 *       <td>{@code audit.operator_id}、{@code audit.target_uid}</td></tr>
 *   <tr><td>deleteUser</td><td>{@code Action-Admin-DeleteUser}</td>
 *       <td>{@code audit.operator_id}、{@code audit.target_uid}</td></tr>
 *   <tr><td>resetUserPassword</td><td>{@code Action-Admin-ResetUserPassword}</td>
 *       <td>{@code audit.operator_id}、{@code audit.target_uid}</td></tr>
 * </table>
 *
 * <p>🔴 <b>另外 5 个用例在 B 侧没有任何 span</b>：{@code getSelf}、{@code getUser}、
 * {@code getUserList}、{@code logout}、{@code updateSelf}
 * ⇒ <b>本类不给这 5 个加 span</b> —— 那会让 trace 里的 admin span 数从 8 变 13，<b>与真源不符</b>。
 * 这不是遗漏，是对齐。
 *
 * <p><b>⚠️ 「8 而非 13」这个事实</b>：13 是<b>属性条数</b>，不是用例数。
 *
 * <p><b>🔴 span 状态面必须恒为 Unset</b>：B 侧只调 {@code span.RecordError}，
 * 而 otel-go 的 {@code recordingSpan.RecordError} 的方法注释原文是
 * “SetStatus is required if the Status of the Span should be set to Error,
 * <b>this method does not change the Span status</b>.”
 * ⇒ ⇒ B 侧导出后 span 状态恒为 <b>Unset</b> ⇒ ⇒ <b>本类也不得额外置 ERROR</b>，
 * 否则导出到 trace 的 span 状态与 B 侧不一致（遥测面漂移）。
 */
public final class AdminSpans {

    /** B 侧 13 个 logic 里全部写死 {@code otel.Tracer("admin-rpc")}。 */
    public static final String TRACER_NAME = "admin-rpc";

    public static final String LOGIN = "Action-Admin-Login";
    public static final String CREATE_ADMIN = "Action-Admin-CreateAdmin";
    public static final String CREATE_INVITE_CODE = "Action-Admin-CreateInviteCode";
    public static final String UPDATE_USER = "Action-Admin-UpdateUser";
    public static final String BAN_USER = "Action-Admin-BanUser";
    public static final String UNBAN_USER = "Action-Admin-UnBanUser";
    public static final String DELETE_USER = "Action-Admin-DeleteUser";
    public static final String RESET_USER_PASSWORD = "Action-Admin-ResetUserPassword";

    public static final String ATTR_OPERATOR_ID = "audit.operator_id";
    public static final String ATTR_TARGET_UID = "audit.target_uid";
    public static final String ATTR_ADMIN_USERNAME = "audit.admin_username";

    private AdminSpans() {
    }

    /**
     * 在一个<b>手写 span</b> 里执行 {@code body}。
     *
     * @param spanName   上面 8 个常量之一（<b>不要</b>给 B 侧没打 span 的那 5 个方法用）
     * @param attributes 设置 {@code audit.*} 属性；B 侧在 {@code Start} 之后立刻设
     * @param body       业务体；抛出的 {@link RuntimeException} 会被记进 span 后原样上抛
     */
    public static <T> T traced(String spanName, Consumer<Span> attributes, Supplier<T> body) {
        Tracer tracer = GlobalOpenTelemetry.getTracer(TRACER_NAME);
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            if (attributes != null) {
                attributes.accept(span);
            }
            return body.get();
        } catch (RuntimeException | Error e) {
            // B 侧的 span.RecordError(err) + defer span.End()
            // ⚠️ **不置** span 状态：B 侧的 RecordError 不改状态（otel-go SDK v1.44.0
            //    sdk@v1.44.0/trace/span.go:631-656 注释原文："this method does not change
            //    the Span status"）⇒ B 侧 span 状态恒为 Unset ⇒ 多置 ERROR 就是遥测面漂移。
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
