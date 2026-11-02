package com.rag.admin.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.rag.admin.config.AdminRpcProperties;
import com.rag.admin.domain.entity.AdminEntity;
import com.rag.admin.domain.entity.AdminInviteEntity;
import com.rag.admin.mapper.AdminInviteMapper;
import com.rag.admin.mapper.AdminMapper;
import com.rag.admin.mapper.UserMapper;
import com.rag.admin.metrics.AdminRpcMetrics;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * admin-rpc 两个已确认缺陷的回归测试：
 * <ol>
 *   <li>{@code updateSelf} 部分更新时空 email 曾被写成 SQL NULL —— 管理员只改密码就会丢邮箱</li>
 *   <li>{@code createAdmin} 的 4 步事务曾因 self-invocation 导致 {@code @Transactional} 静默失效，
 *       且邀请码查询根本没有行锁 —— 同一邀请码可被并发消费多次</li>
 * </ol>
 *
 * <p><b>B 出处</b>：
 * <pre>
 *   B 源文件（绝对路径）：
 *     /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/service/AdminUpdateSelfAndCreateAdminRegressionTest.java
 *   B 关键行号：
 *     :50-60    #initMyBatisPlusTableInfo（不开 Spring 容器时手动初始化 MP lambda cache）
 *     :62-77    #setUp（Mockito mock 装配）
 *     :90-133   缺陷 1：updateSelf 的 email 三条用例（空串 / 纯空白 / 合法值）
 *     :137-155  缺陷 2：createAdmin 邀请码查询必须带 FOR UPDATE 行锁
 *     :157-180  缺陷 2：4 步事务已抽到独立 Bean（结构性反射断言）
 *     :182-196  缺陷 2：已使用的邀请码仍被拒绝
 * </pre>
 *
 * <p>本类由 B 侧同名类移植，<b>期望值沿用 B 侧（D13）</b>。
 * ⇒ 迁移只做<b>包名 / import 重定向</b>：
 * <ul>
 *   <li>{@code com.rag.users.admin.rpc.service} → {@code com.rag.admin.domain.service}（D7 包压平）；</li>
 *   <li>{@code com.rag.users.admin.rpc.domain.*} → {@code com.rag.admin.domain.entity.*}；</li>
 *   <li>{@code com.rag.users.admin.rpc.mapper.*} → {@code com.rag.admin.mapper.*}；</li>
 *   <li>{@code com.rag.users.admin.rpc.config.*} → {@code com.rag.admin.config.*}；</li>
 *   <li>{@code com.rag.users.admin.rpc.metrics.*} → {@code com.rag.admin.metrics.*}。</li>
 * </ul>
 * ⇒ <b>断言值、断言语义、@DisplayName 原文一字未改</b>。
 *
 * <p><b>⚠️ 关于「不开 Spring 容器」这件事在 C 侧依然成立</b>：本类断言的是
 * <b>SQL 片段形状</b>（SET 子句里有没有 {@code email} / 有没有 {@code FOR UPDATE}）
 * 与<b>结构性事实</b>（{@code @Transactional} 方法在哪个类上），
 * 用 Mockito 捕获 wrapper 就能观测到 ⇒ <b>不需要真 PG</b>（真 PG 的那一层由
 * {@code AdminDomainServiceRealPgTest} 与本次移植的
 * {@code AdminRpcColumnTypeParityTest} / {@code AdminRpcPaginationGormSemanticsTest} /
 * {@code AdminCreatePasswordOrderRegressionTest} 覆盖）。
 * <p>⚠️ 但 {@code @Transactional} 的<b>运行期生效</b>（代理是否真的包住 4 步）由
 * {@code AdminCreatePasswordOrderRegressionTest}（真 PG + 真代理）守门 ⇒ 本类的
 * 结构性断言不越位。
 */
class AdminUpdateSelfAndCreateAdminRegressionTest {

    private AdminMapper adminMapper;
    private AdminInviteMapper adminInviteMapper;
    private UserMapper userMapper;
    private StringRedisTemplate redisTemplate;
    private AdminRpcProperties properties;
    private AdminRpcMetrics metrics;
    private AdminDomainService service;

    @BeforeAll
    static void initMyBatisPlusTableInfo() throws Exception {
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        java.lang.reflect.Method initMethod = TableInfoHelper.class.getDeclaredMethod(
                "initTableInfo", org.apache.ibatis.session.Configuration.class, String.class, Class.class);
        initMethod.setAccessible(true);
        initMethod.invoke(null, config, "com.rag.admin.mapper.AdminMapper", AdminEntity.class);
        initMethod.invoke(null, config, "com.rag.admin.mapper.AdminInviteMapper", AdminInviteEntity.class);
        initMethod.invoke(null, config, "com.rag.admin.mapper.UserMapper",
                com.rag.admin.domain.entity.UserEntity.class);
    }

    @BeforeEach
    @SuppressWarnings({"unchecked", "unused"})
    void setUp() {
        adminMapper = Mockito.mock(AdminMapper.class);
        adminInviteMapper = Mockito.mock(AdminInviteMapper.class);
        userMapper = Mockito.mock(UserMapper.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        properties = new AdminRpcProperties();
        properties.getAdminAuth().setAccessExpire(2592000L);
        properties.getSystem().setDefaultPassword("123456");
        metrics = new AdminRpcMetrics(new SimpleMeterRegistry());

        service = new AdminDomainService(adminMapper, adminInviteMapper, userMapper,
                redisTemplate, properties, metrics,
                new AdminCreateAtomicService(adminMapper, adminInviteMapper));
    }

    private AdminEntity givenExistingAdmin(long uid) {
        AdminEntity a = new AdminEntity();
        a.setUid(uid);
        a.setUsername("boss");
        a.setEmail("boss@example.com");
        Mockito.when(adminMapper.selectOne(Mockito.any())).thenReturn(a);
        return a;
    }

    // ================== 缺陷 1：updateSelf 空 email ==================

    @Test
    @DisplayName("updateSelf 未带 email（proto3 默认空串）→ UPDATE 子句里不能出现 email 列")
    void updateSelf_emptyEmail_doesNotTouchEmailColumn() {
        givenExistingAdmin(1001L);

        // username=null, password="newpwd", email="" ← proto3 未设置时的线上默认值
        assertDoesNotThrow(() -> service.updateSelf(1001L, null, "newpwd", "", Map.of()));

        ArgumentCaptor<LambdaUpdateWrapper<AdminEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(adminMapper).update(Mockito.isNull(), captor.capture());

        String sqlSet = captor.getValue().getSqlSet();
        assertFalse(sqlSet.contains("email"),
                "空 email 不应出现在 SET 子句中，否则会把列写成 NULL。实际 SET = " + sqlSet);
        assertTrue(sqlSet.contains("password"), "密码确实应被更新。实际 SET = " + sqlSet);
    }

    @Test
    @DisplayName("updateSelf 传纯空白 email → 同样不更新该列")
    void updateSelf_whitespaceEmail_doesNotTouchEmailColumn() {
        givenExistingAdmin(1001L);

        assertDoesNotThrow(() -> service.updateSelf(1001L, null, "newpwd", "   ", Map.of()));

        ArgumentCaptor<LambdaUpdateWrapper<AdminEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(adminMapper).update(Mockito.isNull(), captor.capture());

        assertFalse(captor.getValue().getSqlSet().contains("email"),
                "纯空白 email 同样应被当作「本次不修改」。实际 SET = " + captor.getValue().getSqlSet());
    }

    @Test
    @DisplayName("updateSelf 带合法 email → 该列必须被更新（确认守卫没有把正常路径也挡掉）")
    void updateSelf_validEmail_updatesEmailColumn() {
        givenExistingAdmin(1001L);

        assertDoesNotThrow(() -> service.updateSelf(1001L, null, null, "new@example.com", Map.of()));

        ArgumentCaptor<LambdaUpdateWrapper<AdminEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(adminMapper).update(Mockito.isNull(), captor.capture());

        assertTrue(captor.getValue().getSqlSet().contains("email"),
                "合法 email 必须出现在 SET 子句中。实际 SET = " + captor.getValue().getSqlSet());
    }

    // ================== 缺陷 2：createAdmin 邀请码行锁 ==================

    @Test
    @DisplayName("createAdmin 查询邀请码必须带 FOR UPDATE 行锁（一次性使用的并发保证）")
    void createAdmin_inviteLookupTakesRowLock() {
        AdminInviteEntity invite = new AdminInviteEntity();
        invite.setId(9L);
        invite.setCode("ADM-0123456789ABCDEF");
        invite.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        Mockito.when(adminInviteMapper.selectOne(Mockito.any())).thenReturn(invite);
        Mockito.when(adminMapper.selectOne(Mockito.any())).thenReturn(null); // username 未占用

        service.createAdmin("newboss", "pwd", null, Map.of(), "ADM-0123456789ABCDEF");

        ArgumentCaptor<LambdaQueryWrapper<AdminInviteEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(adminInviteMapper).selectOne(captor.capture());

        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql != null && sql.toUpperCase().contains("FOR UPDATE"),
                "邀请码查询必须以 FOR UPDATE 结尾才能串行化并发消费。实际 SQL = " + sql);
    }

    @Test
    @DisplayName("createAdmin：4 步事务已抽到独立 Bean，@Transactional 不再是 self-invocation")
    void createAdmin_atomicStepLivesInSeparateBean() throws Exception {
        // 结构性断言：AdminDomainService 不应再有 public createAdminAtomic 方法
        // （有的话就是回到 self-invocation，事务会静默失效）。
        boolean hasAtomicMethod = false;
        for (java.lang.reflect.Method m : AdminDomainService.class.getDeclaredMethods()) {
            if ("createAdminAtomic".equals(m.getName())) {
                hasAtomicMethod = true;
            }
        }
        assertFalse(hasAtomicMethod,
                "4 步事务必须在 AdminCreateAtomicService 里，否则同类自调用会让 @Transactional 失效");

        // 并且该独立 Bean 的方法确实带着 @Transactional
        boolean transactional = false;
        for (java.lang.reflect.Method m : AdminCreateAtomicService.class.getDeclaredMethods()) {
            if ("createAdminAtomic".equals(m.getName())
                    && m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)) {
                transactional = true;
            }
        }
        assertTrue(transactional, "AdminCreateAtomicService.createAdminAtomic 必须标注 @Transactional");
    }

    @Test
    @DisplayName("createAdmin：已使用的邀请码仍被拒绝（行锁改动没有放松校验）")
    void createAdmin_usedInviteStillRejected() {
        AdminInviteEntity invite = new AdminInviteEntity();
        invite.setId(9L);
        invite.setCode("ADM-0123456789ABCDEF");
        invite.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        invite.setUsedAt(OffsetDateTime.now().minusMinutes(1));
        Mockito.when(adminInviteMapper.selectOne(Mockito.any())).thenReturn(invite);

        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> service.createAdmin("newboss", "pwd", null, Map.of(), "ADM-0123456789ABCDEF"));
        assertEquals(io.grpc.Status.Code.PERMISSION_DENIED, e.getStatus().getCode());
        Mockito.verify(adminMapper, Mockito.never()).insert(Mockito.any(AdminEntity.class));
    }
}
