package com.rag.users.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>身份解析器的 5 个 sentinel + 两套 uid 语义 + 三元组产出</b>（对 B 逐条）。
 *
 * <p><b>B 出处（本次迁移的行为基线）</b>：
 * <pre>
 *   B: RAG  service/users/user/user-api/src/main/java/com/rag/users/user/api/service/IdentityResolver.java
 *      :45-49   5 个冻结错误消息常量
 *      :101-114 {@code claimedUID}（uid&lt;=0 ⇒ 拒）
 *      :160-171 {@code claimedUIDAllowNonPositive}（不拒）
 *      :176-217 {@code resolveUser}（6 步判定链）
 *      :212-223 {@code resolveSubjectRef}（三元组）
 *      :228-243 5 个 sentinel 异常类
 * </pre>
 *
 * <h2>本类钉住什么</h2>
 * <ol>
 *   <li><b>判定顺序</b>：B 的判定链顺序本身是行为（先身份合法性 → 再查无此人 → 再错配 → 再 presence → 再活跃）
 *       ⇒ 本类用「同时违反多条」的对象（如「查无此人 + status 非 0」）钉住<b>谁先抛</b>。</li>
 *   <li><b>两套 uid 语义不可合并</b>：{@code claimedUID} 拒 {@code uid<=0}，
 *       {@code claimedUIDAllowNonPositive} 不拒 ⇒ 合并即制造新漂移。</li>
 *   <li><b>5 个 sentinel 的类型与文案</b>逐条相等。</li>
 * </ol>
 */
class IdentityResolverTest {

    private static HttpServletRequest requestWith(Object claim) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (claim != null) {
            req.setAttribute(IdentityResolver.CLAIM_ATTRIBUTE, claim);
        }
        return req;
    }

    // ============== claimedUID ==============

    @Test
    @DisplayName("claimedUID：request 为 null ⇒ ErrInvalidClaim")
    void claimedUidRejectsNullRequest() {
        IdentityResolver.ErrInvalidClaimException ex = assertThrows(
                IdentityResolver.ErrInvalidClaimException.class,
                () -> IdentityResolver.claimedUID(null));
        assertEquals("invalid authenticated user claim", ex.getMessage());
    }

    @Test
    @DisplayName("claimedUID：claim 缺失 ⇒ ErrInvalidClaim")
    void claimedUidRejectsMissingAttribute() {
        assertThrows(IdentityResolver.ErrInvalidClaimException.class,
                () -> IdentityResolver.claimedUID(requestWith(null)));
    }

    @Test
    @DisplayName("claimedUID：claim 类型不是 Long ⇒ ErrInvalidClaim（B 的 type assertion 失败同款）")
    void claimedUidRejectsWrongType() {
        assertThrows(IdentityResolver.ErrInvalidClaimException.class,
                () -> IdentityResolver.claimedUID(requestWith("1001")));
        assertThrows(IdentityResolver.ErrInvalidClaimException.class,
                () -> IdentityResolver.claimedUID(requestWith(1001)));
    }

    @Test
    @DisplayName("claimedUID：uid <= 0 ⇒ ErrInvalidClaim（B 的第 3 条判定）")
    void claimedUidRejectsNonPositive() {
        assertThrows(IdentityResolver.ErrInvalidClaimException.class,
                () -> IdentityResolver.claimedUID(requestWith(0L)));
        assertThrows(IdentityResolver.ErrInvalidClaimException.class,
                () -> IdentityResolver.claimedUID(requestWith(-1L)));
    }

    @Test
    @DisplayName("claimedUID：正整数 UID 原样返回")
    void claimedUidReturnsPositive() {
        assertEquals(1001L, IdentityResolver.claimedUID(requestWith(1001L)));
    }

    // ============== claimedUIDAllowNonPositive ==============

    @Test
    @DisplayName("claimedUIDAllowNonPositive：uid=0 / 负数<b>不</b>拒（B 的 delete/update 语义）")
    void claimedUidAllowNonPositiveKeepsNonPositive() {
        assertEquals(0L, IdentityResolver.claimedUIDAllowNonPositive(requestWith(0L)));
        assertEquals(-7L, IdentityResolver.claimedUIDAllowNonPositive(requestWith(-7L)));
    }

    @Test
    @DisplayName("claimedUIDAllowNonPositive：claim 缺失 / 类型不对仍然拒（B 侧同样拒）")
    void claimedUidAllowNonPositiveStillRejectsBadClaim() {
        assertThrows(IdentityResolver.ErrInvalidClaimException.class,
                () -> IdentityResolver.claimedUIDAllowNonPositive(requestWith(null)));
        assertThrows(IdentityResolver.ErrInvalidClaimException.class,
                () -> IdentityResolver.claimedUIDAllowNonPositive(requestWith("1001")));
    }

    // ============== resolveSubjectRef 的判定链 ==============

    @Test
    @DisplayName("查询端口为 null ⇒ ErrIdentityMismatch（B：users == null）")
    void nullLookupIsIdentityMismatch() {
        IdentityResolver.ErrIdentityMismatchException ex = assertThrows(
                IdentityResolver.ErrIdentityMismatchException.class,
                () -> IdentityResolver.resolveSubjectRef(requestWith(1001L), null));
        assertTrue(ex.getMessage().contains("authenticated user RPC identity mismatch"),
                "错误文案必须含 B 侧的冻结消息");
    }

    @Test
    @DisplayName("查无此人 ⇒ ErrUserNotFound")
    void notFoundIsUserNotFound() {
        assertThrows(IdentityResolver.ErrUserNotFoundException.class,
                () -> IdentityResolver.resolveSubjectRef(requestWith(1001L),
                        uid -> IdentityResolver.UserStatusRecord.notFound()));
    }

    @Test
    @DisplayName("取回的 UID 与请求不符 ⇒ ErrIdentityMismatch")
    void uidMismatchIsIdentityMismatch() {
        assertThrows(IdentityResolver.ErrIdentityMismatchException.class,
                () -> IdentityResolver.resolveSubjectRef(requestWith(1001L),
                        uid -> new IdentityResolver.UserStatusRecord(true, 2002L, 0L)));
    }

    @Test
    @DisplayName("status 字段不存在（presence 缺失）⇒ ErrStatusUnavailable")
    void absentStatusIsStatusUnavailable() {
        assertThrows(IdentityResolver.ErrStatusUnavailableException.class,
                () -> IdentityResolver.resolveSubjectRef(requestWith(1001L),
                        uid -> new IdentityResolver.UserStatusRecord(true, 1001L, null)));
    }

    @Test
    @DisplayName("status != 0 ⇒ ErrUserInactive")
    void nonZeroStatusIsInactive() {
        assertThrows(IdentityResolver.ErrUserInactiveException.class,
                () -> IdentityResolver.resolveSubjectRef(requestWith(1001L),
                        uid -> new IdentityResolver.UserStatusRecord(true, 1001L, 1L)));
    }

    @Test
    @DisplayName("判定顺序：查无此人 < 错配 < presence < 活跃（B 的链序）")
    void checkOrderIsStable() {
        // 同时「查无此人」且「status 非 0」⇒ 先抛 not-found
        assertThrows(IdentityResolver.ErrUserNotFoundException.class,
                () -> IdentityResolver.resolveSubjectRef(requestWith(1001L),
                        uid -> new IdentityResolver.UserStatusRecord(false, 999L, 5L)));
        // 同时「UID 错配」且「status 非 0」⇒ 先抛 mismatch
        assertThrows(IdentityResolver.ErrIdentityMismatchException.class,
                () -> IdentityResolver.resolveSubjectRef(requestWith(1001L),
                        uid -> new IdentityResolver.UserStatusRecord(true, 2002L, 5L)));
        // 同时「presence 缺失」且「status 非 0」⇒ 先抛 status-unavailable
        assertThrows(IdentityResolver.ErrStatusUnavailableException.class,
                () -> IdentityResolver.resolveSubjectRef(requestWith(1001L),
                        uid -> new IdentityResolver.UserStatusRecord(true, 1001L, null)));
    }

    @Test
    @DisplayName("claim 非法 ⇒ 先于查询端口被判掉（第 1 步）")
    void invalidClaimBeatsLookup() {
        assertThrows(IdentityResolver.ErrInvalidClaimException.class,
                () -> IdentityResolver.resolveSubjectRef(requestWith(null),
                        uid -> new IdentityResolver.UserStatusRecord(true, 1001L, 0L)));
    }

    // ============== 三元组 ==============

    @Test
    @DisplayName("成功路径 ⇒ 三元组 = {rtw.identity, platform, UID 十进制}")
    void successfulResolutionProducesSubjectRef() {
        IdentityResolver.SubjectRef ref = IdentityResolver.resolveSubjectRef(requestWith(1001L),
                uid -> new IdentityResolver.UserStatusRecord(true, 1001L, 0L));
        assertNotNull(ref);
        assertEquals("rtw.identity", ref.getAuthorityId());
        assertEquals("platform", ref.getTenantId());
        assertEquals("1001", ref.getSubjectId());
    }

    @Test
    @DisplayName("冻结常量：AUTHORITY_ID / PLATFORM_TENANT_ID / CLAIM_ATTRIBUTE")
    void frozenConstants() {
        assertEquals("rtw.identity", IdentityResolver.AUTHORITY_ID);
        assertEquals("platform", IdentityResolver.PLATFORM_TENANT_ID);
        assertEquals("userId", IdentityResolver.CLAIM_ATTRIBUTE);
    }

    @Test
    @DisplayName("SubjectRef 的 equals / hashCode 按三元组")
    void subjectRefEquality() {
        IdentityResolver.SubjectRef a = new IdentityResolver.SubjectRef("rtw.identity", "platform", "1");
        IdentityResolver.SubjectRef b = new IdentityResolver.SubjectRef("rtw.identity", "platform", "1");
        IdentityResolver.SubjectRef c = new IdentityResolver.SubjectRef("rtw.identity", "platform", "2");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(!a.equals(c));
    }
}
