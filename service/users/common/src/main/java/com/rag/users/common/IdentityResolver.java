package com.rag.users.common;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;

/**
 * <p><b>权威身份解析器 —— ClaimedUID → GetUser → status presence 检查 → SubjectRef（user/admin 两子域共用）</b>。
 *
 * <p><b>B 出处（本次迁移的行为基线）</b>：
 * <pre>
 *   B: RAG  service/users/user/user-api/src/main/java/com/rag/users/user/api/service/IdentityResolver.java
 *          （admin 侧另有一份副本 service/users/admin/admin-api/.../service/IdentityResolver.java）
 *   抄写基准 = B 的 <b>user-api 版</b>（含 status presence 检查的那份）——
 *   依据 docs/Mapping_Table/users/user/user.md:142「Go 的<b>权威</b>身份解析器 … C 侧只 1 份，
 *   上收到域级共享层」与 docs/Mapping_Table/users/_shared.md §7.4 F-1（用户 2026-11-01 定）。
 * </pre>
 *
 * <h2>B 的两份副本 → C 的一份（消解的是「重复」，不是「行为」）</h2>
 * <p>B 侧 user-api 与 admin-api 各有一份，差异只有 3 处：① admin 版<b>不</b>做 status 检查
 * （admin.proto 的 {@code AdminInfo} 没有 status 字段）；② admin 版用 {@code ErrAdminNotFoundException}
 * 取代 {@code ErrUserNotFoundException}；③ admin 版查的是 {@code getSelf} 而非 {@code getUser}。
 * <p>⇒ C 侧把「查哪张表 / 有没有 status 列」<b>下沉给查询端口</b>（{@link UserStatusLookup}），
 * 解析器的 5 个 sentinel 判定顺序与三元组产出<b>只有一份</b> ⇒
 * <b>两份实现分叉的根源被消解</b>（B 侧把「同一规则的第二份实现」列为缺陷来源）。
 * <p>⚠️ <b>admin 子域的适配契约</b>：admin 表<b>没有</b> status 列 ⇒ admin 侧实现
 * {@link UserStatusLookup} 时应返回 <b>present 且值为 0</b> 的记录
 * （⇒ 与 B 的 admin 版「不做 status 检查」<b>可观察行为一致</b>）。
 *
 * <h2>🔴 为什么「查 user」从 gRPC 换成查询端口（表达层差异，行为等价）</h2>
 * <p>B 侧 api 与 rpc 是<b>两个进程</b> ⇒ api 只能通过 gRPC 找 rpc 拿 status；
 * C 侧按 D1 + D7 把 api + rpc <b>合并成一个进程</b>（每域一个 app）⇒
 * 「自己调自己」变成同进程内的直接查询 ⇒ 走 gRPC 只会凭空多一次网络往返与一次失败模式。
 * <p>⇒ 因此本类把「取 user 的 status」抽象成 {@link UserStatusLookup} 端口，
 * 由消费方（user 子域 / admin 子域）注入实现；本仓已提供 JDBC 实现
 * {@link JdbcUserStatusLookup}（读 {@code users_user} 表）。
 * <p><b>语义等价论证</b>：B 的判定链是
 * 「① claim 合法性 → ② RPC 通道在不在 → ③ 有没有这个人 → ④ 拿到的人是不是他要的那个
 * → ⑤ status 字段在不在（proto3 presence）→ ⑥ status 是不是 0」
 * ⇒ C 侧逐条保留，<b>顺序不变、错误类型不变</b>；差别只在第 ③ 步的
 * 「RPC 返 NOT_FOUND」被折叠进端口的「查无此人」返回 ⇒ <b>同一个 sentinel</b>。
 *
 * <h2>6 个可观察面（B 的口径，逐条保留）</h2>
 * <ol>
 *   <li><b>Return</b>：{@link SubjectRef} = {authorityId, tenantId, subjectId}，
 *       subjectId = UID 的十进制字符串</li>
 *   <li><b>Error</b>：5 个 sentinel 异常 → 5 个业务码（由调用方映射）</li>
 *   <li><b>Log</b>：本类<b>不打日志</b>；调用方打</li>
 *   <li><b>Trace/Context</b>：从 request attribute {@value #CLAIM_ATTRIBUTE} 取 UID
 *       （由各域的 JWT 拦截器写入）</li>
 *   <li><b>Metric</b>：本类<b>不写 metrics</b>；调用方按 5 种错误映射后写</li>
 *   <li><b>Lifecycle</b>：纯函数、无状态；调用顺序 = ClaimedUID → GetUser → status 检查 → SubjectRef</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么本类是静态工具而不是 {@code @Component}</b>：B 侧 {@code @Component} 挂在
 * 「全是 static 方法」的类上 ⇒ Spring 建的 bean 无人注入 ⇒ <b>无行为</b>；
 * 而 C 侧 users-common 是<b>库模块</b>，各 app 只扫自己的包（{@code com.rag.user} / {@code com.rag.admin}）
 * ⇒ {@code com.rag.users.common} <b>不在扫描范围</b> ⇒ 挂 {@code @Component} 只会造一个永不生效的假 bean。
 * <p>⇒ 保持「静态方法 + 无状态」，与 B 的<b>实际</b>行为一致（表达层差异）。
 */
public final class IdentityResolver {

    // ============== L1 冻结常量 ==============

    /** authority_id —— B 出处：{@code ErrorCode 同目录的 IdentityResolver.java:40}（{@code AuthorityID}）。 */
    public static final String AUTHORITY_ID = "rtw.identity";

    /** tenant_id —— B 出处：{@code IdentityResolver.java:41}（{@code PlatformTenantID}）。 */
    public static final String PLATFORM_TENANT_ID = "platform";

    /**
     * 请求属性名 —— claim 落地处。
     *
     * <p>B 出处：{@code IdentityResolver.java:105}（{@code request.getAttribute("userId")}）
     * 与 {@code :107}（类型必须是 {@code Long}）。
     * <p>⚠️ 本仓把它提成常量，是因为「属性名」是<b>拦截器 ↔ 解析器</b>之间的契约：
     * user 子域与 admin 子域各有一个 JWT 拦截器 ⇒ 属性名若各写一份，就是 B 侧那种
     * 「同一规则的第二份实现」⇒ 契约收在本类一处。
     */
    public static final String CLAIM_ATTRIBUTE = "userId";

    // ============== L1 冻结错误消息 ==============

    private static final String MSG_INVALID_CLAIM = "invalid authenticated user claim";
    private static final String MSG_USER_NOT_FOUND = "authenticated user not found";
    private static final String MSG_IDENTITY_MISMATCH = "authenticated user RPC identity mismatch";
    private static final String MSG_USER_INACTIVE = "authenticated user inactive";
    private static final String MSG_STATUS_UNAVAILABLE = "authenticated user status unavailable";

    private IdentityResolver() {
    }

    // ============== 查询端口 ==============

    /**
     * 「按 UID 查用户并取回 status」的端口。
     *
     * <p>对应 B 侧的 {@code UserServiceGrpc.UserServiceBlockingStub#getUser}（B 出处：
     * {@code IdentityResolver.java:176-217}）—— B 走 gRPC 是因为 api 与 rpc 分属两进程；
     * C 侧合并为单进程 ⇒ 端口化（表达层差异，判定链不变）。
     */
    @FunctionalInterface
    public interface UserStatusLookup {

        /**
         * 按 UID 查用户。
         *
         * @param uid 待查 UID
         * @return 查到的记录；<b>不得返回 {@code null}</b>（查无此人请返回 {@link UserStatusRecord#notFound()}）
         */
        UserStatusRecord findByUid(long uid);
    }

    /**
     * 一次查询的结果。
     *
     * <p>B 出处：{@code IdentityResolver.java:198-209}（{@code user.getUid()} / {@code user.hasStatus()} /
     * {@code user.getStatus()}）—— B 走 proto3 的 {@code optional int64 status}，
     * <b>「字段不存在」与「字段值为 0」是两种状态</b> ⇒ 本记录用 {@code Long status} 的
     * {@code null} 表达「字段不存在」，用 {@code 0L} 表达「存在且为 0」。
     *
     * @param found  是否查到该 UID（查无此人 ⇒ {@code false}）
     * @param uid    查到的 UID（应与查询键一致；不一致 ⇒ 身份错配）
     * @param status status 值；{@code null} = <b>字段不存在</b>（proto3 presence 语义的等价表达）
     */
    public record UserStatusRecord(boolean found, long uid, Long status) {

        /** 「查无此人」的记录。 */
        public static UserStatusRecord notFound() {
            return new UserStatusRecord(false, 0L, null);
        }

        /** status 字段是否<b>存在</b>（对应 B 的 {@code proto3 hasStatus()}）。 */
        public boolean hasStatus() {
            return status != null;
        }
    }

    // ============== SubjectRef wire type ==============

    /**
     * 身份三元组 —— wire JSON = {@code {"authority_id":"rtw.identity","tenant_id":"platform","subject_id":"<UID>"}}。
     *
     * <p>B 出处：{@code IdentityResolver.java:57-89}（嵌套类 {@code SubjectRef}）。
     * <p>⚠️ <b>刻意不加 {@code @JsonProperty}</b>：B 侧这个类<b>没有</b> Jackson 注解 ⇒
     * 它的 JSON 形态就是 getter 推导出的 {@code authorityId/tenantId/subjectId}；
     * 擅自加注解会把形态换成 snake_case ⇒ 那是一条<b>未被 B 验证过的 wire 变更</b>（L1）⇒ 禁止。
     * <p>⚠️ <b>不要与根级 {@code common/} 的 {@code com.rag.common.communityfact.SubjectRef} 混用</b>：
     * 那是一个<b>不同</b>的 wire 类型（{@code issuer, subject_id}，2 字段，跨域事件协议用），
     * 本类是 users 域身份三元组（3 字段）⇒ 同名不同义，混用会静默改掉 wire。
     */
    public static final class SubjectRef {

        private String authorityId;
        private String tenantId;
        private String subjectId;

        public SubjectRef() {
        }

        public SubjectRef(String authorityId, String tenantId, String subjectId) {
            this.authorityId = authorityId;
            this.tenantId = tenantId;
            this.subjectId = subjectId;
        }

        public String getAuthorityId() {
            return authorityId;
        }

        public void setAuthorityId(String authorityId) {
            this.authorityId = authorityId;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getSubjectId() {
            return subjectId;
        }

        public void setSubjectId(String subjectId) {
            this.subjectId = subjectId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SubjectRef)) {
                return false;
            }
            SubjectRef r = (SubjectRef) o;
            return Objects.equals(authorityId, r.authorityId)
                    && Objects.equals(tenantId, r.tenantId)
                    && Objects.equals(subjectId, r.subjectId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(authorityId, tenantId, subjectId);
        }

        @Override
        public String toString() {
            return "SubjectRef{authorityId='" + authorityId + "', tenantId='" + tenantId
                    + "', subjectId='" + subjectId + "'}";
        }
    }

    // ============== L3 冻结逻辑 ==============

    /**
     * 从 request attribute {@value #CLAIM_ATTRIBUTE} 取<b>正整数</b> UID。
     *
     * <p>B 出处：{@code IdentityResolver.java:101-114}。三条判定逐条一致：
     * ① request 为 null ⇒ {@link ErrInvalidClaimException}；
     * ② attribute <b>不是</b> {@code Long} ⇒ {@link ErrInvalidClaimException}；
     * ③ uid {@code <= 0} ⇒ {@link ErrInvalidClaimException}。
     *
     * <p>⚠️ <b>只适用于「拒绝 uid&lt;=0」的端点</b>；另一套语义见
     * {@link #claimedUIDAllowNonPositive(HttpServletRequest)} ⇒ <b>不要合并这两个方法</b>
     * （B 侧 javadoc 明确写了：合并等于擅自「修好」Go 的不对称 ⇒ 制造一处新的语义漂移）。
     *
     * @param request 当前请求
     * @return 正整数 UID
     * @throws ErrInvalidClaimException claim 缺失 / 类型不对 / 非正整数
     */
    public static long claimedUID(HttpServletRequest request) {
        long uid = claimedUIDAllowNonPositive(request);
        if (uid <= 0) {
            throw new ErrInvalidClaimException(MSG_INVALID_CLAIM);
        }
        return uid;
    }

    /**
     * 从 request attribute {@value #CLAIM_ATTRIBUTE} 取 UID，<b>不做</b> {@code uid <= 0} 判定。
     *
     * <p>B 出处：{@code IdentityResolver.java:160-171}（B 侧 {@code claimedUIDAllowNonPositive}）。
     * <p>⚠️ 与 {@link #claimedUID(HttpServletRequest)} 的差别<b>是行为</b>、不是实现细节：
     * B 的 {@code delete} / {@code update} 两端点<b>不</b>拒绝非正 UID（让 uid=0 透传到下游、
     * 由下游查无此人而返回「用户不存在」），而 {@code get} / {@code avatar/*} <b>拒绝</b>
     * ⇒ 两个方法必须并存，不得互相替代。
     *
     * @param request 当前请求
     * @return 请求里的 UID（可能 {@code <= 0}）
     * @throws ErrInvalidClaimException request 为 null 或 attribute 缺失 / 类型不对
     */
    public static long claimedUIDAllowNonPositive(HttpServletRequest request) {
        if (request == null) {
            throw new ErrInvalidClaimException(MSG_INVALID_CLAIM);
        }
        Object v = request.getAttribute(CLAIM_ATTRIBUTE);
        if (!(v instanceof Long)) {
            // claim 缺失 / 类型不对 ⇒ 与 B 的 type assertion 失败同款
            throw new ErrInvalidClaimException(MSG_INVALID_CLAIM);
        }
        return (Long) v;
    }

    /**
     * 解析身份并产出三元组 —— <b>ClaimedUID → GetUser → status presence 检查 → SubjectRef</b>。
     *
     * <p>B 出处：{@code IdentityResolver.java:212-223}（{@code resolveSubjectRef}）+
     * {@code :176-217}（{@code resolveUser}）。
     * <p><b>判定顺序逐条与 B 一致</b>（顺序本身是行为：先判身份合法性、再判查无此人、再判错配、
     * 再判 status presence、再判是否活跃）：
     * <ol>
     *   <li>{@code claimedUID} 失败 ⇒ {@link ErrInvalidClaimException}</li>
     *   <li>查询端口为 null ⇒ {@link ErrIdentityMismatchException}（B：{@code users == null}）</li>
     *   <li>查无此人 ⇒ {@link ErrUserNotFoundException}（B：{@code !resp.getFound()}）</li>
     *   <li>取回的 UID 与请求 UID 不一致 ⇒ {@link ErrIdentityMismatchException}（B：{@code user.getUid() != uid}）</li>
     *   <li>status 字段不存在 ⇒ {@link ErrStatusUnavailableException}（B：{@code !user.hasStatus()}）</li>
     *   <li>status 不为 0 ⇒ {@link ErrUserInactiveException}（B：{@code user.getStatus() != 0L}）</li>
     * </ol>
     *
     * @param request 当前请求
     * @param lookup  用户查询端口（不可为 null）
     * @return 身份三元组
     */
    public static SubjectRef resolveSubjectRef(HttpServletRequest request, UserStatusLookup lookup) {
        long uid = claimedUID(request);
        if (lookup == null) {
            throw new ErrIdentityMismatchException("user lookup is nil: " + MSG_IDENTITY_MISMATCH);
        }
        UserStatusRecord record = lookup.findByUid(uid);
        if (record == null || !record.found()) {
            throw new ErrUserNotFoundException(MSG_USER_NOT_FOUND);
        }
        if (record.uid() != uid) {
            throw new ErrIdentityMismatchException(MSG_IDENTITY_MISMATCH);
        }
        // proto3 presence 的等价表达：status 字段「存在」与「值为 0」是两种状态
        if (!record.hasStatus()) {
            throw new ErrStatusUnavailableException(MSG_STATUS_UNAVAILABLE);
        }
        if (record.status() != 0L) {
            throw new ErrUserInactiveException(MSG_USER_INACTIVE);
        }
        return new SubjectRef(
                AUTHORITY_ID,
                PLATFORM_TENANT_ID,
                Long.toString(record.uid())
        );
    }

    // ============== 异常类型（B 的 sentinel error pattern） ==============

    /** claim 缺失 / 类型不对 / 非正整数（B 出处：{@code IdentityResolver.java:228-231}）。 */
    public static class ErrInvalidClaimException extends RuntimeException {
        public ErrInvalidClaimException(String msg) {
            super(msg);
        }
    }

    /** 查无此人（B 出处：{@code IdentityResolver.java:231-234}）。 */
    public static class ErrUserNotFoundException extends RuntimeException {
        public ErrUserNotFoundException(String msg) {
            super(msg);
        }
    }

    /** 身份错配（查询通道缺失 / 取回的人不是请求的人）（B 出处：{@code IdentityResolver.java:234-237}）。 */
    public static class ErrIdentityMismatchException extends RuntimeException {
        public ErrIdentityMismatchException(String msg) {
            super(msg);
        }
    }

    /** 用户非活跃（status != 0）（B 出处：{@code IdentityResolver.java:237-240}）。 */
    public static class ErrUserInactiveException extends RuntimeException {
        public ErrUserInactiveException(String msg) {
            super(msg);
        }
    }

    /** status 字段不可得（presence 缺失）（B 出处：{@code IdentityResolver.java:240-243}）。 */
    public static class ErrStatusUnavailableException extends RuntimeException {
        public ErrStatusUnavailableException(String msg) {
            super(msg);
        }
    }
}
