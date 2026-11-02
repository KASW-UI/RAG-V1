package com.rag.admin.exception;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * admin 域的业务 sentinel 异常 + PG 唯一约束判定工具。
 *
 * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/exception/AdminExceptions.java}
 * ⇒ <b>本类为同构照抄</b>（含 3 个 nested static class + 1 个静态判定方法）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅包名 {@code rpc.exception} → {@code exception}（D7）。
 *       <b>类名与成员逐条未动</b> —— 含 {@code isUniqueViolation} 这个静态方法
 *       （B 侧它在<b>本类</b>里，不在 Mapper 里 ⇒ 落点照抄）。</li>
 *   <li><b>语义还一样吗</b>：一样。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么这三个 sentinel 仍然用 gRPC 的 {@link StatusRuntimeException}，
 * 而不是共享层的 {@code BusinessException}</b> ——
 * {@code docs/Mapping_Table/users/_shared.md} §3 的「换错误表达（异常出口统一）」决策锁
 * 写着「业务层统一抛 {@code BusinessException}」，但<b>该决策在 admin 域不适用</b>：
 * <ol>
 *   <li><b>B 侧 admin-rpc 的业务层就是抛 {@link StatusRuntimeException}</b>
 *       （{@code Status.NOT_FOUND.withDescription("用户不存在")} 等），
 *       ⇒ 而这些 description 与 status code 是 <b>gRPC wire 上可见</b>的（L1 面）。</li>
 *   <li>若改成 {@code BusinessException}，共享层的
 *       {@code GrpcExceptionMapperAutoConfiguration} 会把 status 改写成
 *       {@code UNKNOWN}(2) + trailer {@code sea-biz-code}
 *       ⇒ ⇒ <b>gRPC 面的 status code 与 description 双双偏离 B 侧</b>
 *       ⇒ ⇒ 违反 AGENTS.md §3.1「差异一律判 RAG-V1 错」。</li>
 *   <li>⇒ ⇒ <b>本类保持 B 形态</b>；HTTP 边界由
 *       {@code advice/AdminApiExceptionHandler} 逐条照抄 B 侧的
 *       {@code mapRPCStatusToCode} 做 gRPC status → 业务码映射
 *       （那是 <b>B 侧自己的做法</b>，不是本仓新增）。</li>
 * </ol>
 * ⇒ 该判断留档于域级台账 {@code docs/DRIFT/users/admin/}。
 */
public final class AdminExceptions {

    private AdminExceptions() {}

    /**
     * 对应 B 侧的 {@code ErrorNotFound} sentinel。
     * Mapper / LambdaQueryWrapper 查不到 Admin / AdminInvite / User 任一行时抛。
     */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 内部异常：邀请码无效（不存在 / 已用 / 已过期）。
     * 业务层捕获后转 {@link Status#PERMISSION_DENIED}。
     */
    public static class InviteUnavailableException extends RuntimeException {
        public InviteUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * 内部异常：用户名已被占用。
     * 业务层捕获后转 {@link Status#ALREADY_EXISTS}。
     */
    public static class UsernameTakenException extends RuntimeException {
        public UsernameTakenException(String message) {
            super(message);
        }
    }

    /**
     * 对应 B 侧的 {@code IsUniqueViolation} —— Postgres SQLState 23505 识别。
     *
     * <p>B 侧在 CreateAdmin 里用它区分「用户名已存在」vs 其它 DB 错误，
     * 并在 {@code AdminSeederBootstrap} 里用它兜底多实例并发启动时的 unique 冲突。
     *
     * <p>MyBatis / Spring 把 unique violation 包装成 {@link DataIntegrityViolationException}，
     * 其 SQLState 以 "23" 开头（23505 = unique_violation，23503 = foreign_key_violation，
     * 23514 = check_violation）⇒ ⇒ <b>必须沿 cause 链找 {@link java.sql.SQLException}</b>。
     */
    public static boolean isUniqueViolation(Throwable err) {
        if (err == null) {
            return false;
        }
        Throwable cur = err;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException) {
                String state = ((java.sql.SQLException) cur).getSQLState();
                if (state != null && state.equals("23505")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}
