package com.rag.admin.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.rag.admin.config.AdminRpcProperties;
import com.rag.admin.domain.entity.AdminEntity;
import com.rag.admin.domain.entity.AdminInviteEntity;
import com.rag.admin.domain.entity.UserEntity;
import com.rag.admin.mapper.AdminInviteMapper;
import com.rag.admin.mapper.AdminMapper;
import com.rag.admin.mapper.UserMapper;
import com.rag.admin.metrics.AdminRpcMetrics;
import com.rag.admin.wiring.AdminSeederBootstrap;
import com.rag.common.security.PasswordUtil;
import com.rag.common.snowflake.Snowflake;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Go↔RAG 语义一致性回归网 —— admin-rpc 侧（issue #71186443）。
 *
 * <p>本类只锁「本轮审计发现并修复的漂移」，每条断言都写明 Go 出处，
 * 避免下一个人看不出"为什么该是这个值"。
 *
 * <p>覆盖：
 * <ul>
 *   <li><b>DRIFT-001</b> seeder 密码来自硬编码常量，不是 system.default-password</li>
 *   <li><b>DRIFT-006</b> keyword 不 trim（空白 keyword 也走 LIKE 过滤）</li>
 *   <li><b>DRIFT-007</b> rpc 层不对 page/pageSize 归一化（负 OFFSET 交给 PG 报错）</li>
 *   <li><b>DRIFT-008</b> createAdmin 不校验 username 非空</li>
 * </ul>
 *
 * <p><b>断言口径说明（对应 AGENTS.md §3.3）</b>：DRIFT-006 / 007 断言的是
 * <b>MyBatis-Plus 实际生成的 SQL 片段与绑定参数</b>，不是 mock 的返回值。
 * 纯 mock 返回值测不到"pattern 有没有被 trim"、"OFFSET 是不是负数"这类真正的行为差异：
 * mock 层无论 wrapper 里装的是什么都会照常返回预设值，测试必然绿。
 *
 * <p><b>B 出处</b>：
 * <pre>
 *   B 源文件（绝对路径）：
 *     /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/service/AdminSemanticDriftRegressionTest.java
 *   B 关键行号：
 *     :59-73    #initMyBatisPlusTableInfo（不开 Spring 容器时手动初始化 MP lambda cache）
 *     :75-92    #setUp（Mockito mock 装配）
 *     :102-116  boundParams / boundParamsContain（取真正被绑定的参数）
 *     :133-152  DRIFT-001：seeder 默认密码是硬编码 admin
 *     :155-168  DRIFT-001：seeder 幂等（已存在时不重复插入、不碰 Snowflake）
 *     :185-239  DRIFT-006：keyword 不 trim（空白 / 前后空格 / 空串）
 *     :268-333  R4 / DRIFT-007：分页参数不归一化（负 OFFSET / 负 LIMIT 被抑制）
 *     :351-374  DRIFT-008：username 为空串仍进事务；inviteCode 为空仍被拒
 *     :385-423  防回退：resetUserPassword 读 system.default-password，SET 子句含 update_time
 *     :426-436  防回退：total 来自独立 count 查询
 * </pre>
 *
 * <p>本类由 B 侧同名类移植，<b>期望值沿用 B 侧（D13）</b>。
 * ⇒ 迁移只做<b>包名 / import 重定向</b>：
 * <ul>
 *   <li>{@code com.rag.users.admin.rpc.service} → {@code com.rag.admin.domain.service}（D7 包压平）；</li>
 *   <li>{@code com.rag.users.admin.rpc.domain.*} → {@code com.rag.admin.domain.entity.*}；</li>
 *   <li>{@code com.rag.users.admin.rpc.mapper.*} → {@code com.rag.admin.mapper.*}；</li>
 *   <li>{@code com.rag.users.admin.rpc.config.*} → {@code com.rag.admin.config.*}；</li>
 *   <li>{@code com.rag.users.admin.rpc.metrics.*} → {@code com.rag.admin.metrics.*}；</li>
 *   <li>{@code com.rag.users.admin.rpc.bootstrap.AdminSeederBootstrap} → {@code com.rag.admin.wiring.AdminSeederBootstrap}；</li>
 *   <li>{@code com.rag.users.common.cryptx.PasswordUtil} → {@code com.rag.common.security.PasswordUtil}（D3 上提共享层）。</li>
 * </ul>
 * ⇒ <b>断言值、断言语义、@DisplayName 原文一字未改</b>。
 *
 * <p><b>🔴 唯一一处非机械改动：seeder 的 uid 来源</b>（<b>断言值未变</b>）
 * <ul>
 *   <li><b>B 侧</b>：{@code com.rag.common.spring.SnowflakeBean}（普通 bean，{@code nextId()} 是<b>实例方法</b>）
 *       ⇒ 测试用 {@code Mockito.mock(SnowflakeBean.class)} 打桩 uid，并断言「不得碰 Snowflake」。</li>
 *   <li><b>C 侧</b>：{@code com.rag.common.snowflake.Snowflake} 是 <b>final class + 静态方法</b>
 *       （{@code Snowflake.nextId()}；生成状态是全进程唯一的一份静态状态），
 *       {@code AdminSeederBootstrap} 注入它<b>只用于强制 bean 创建顺序</b>，
 *       取 id 走的是<b>静态</b> {@code Snowflake.nextId()} ⇒ ⇒ 实例方法无法打桩。</li>
 *   <li><b>改法</b>：改用 {@link Mockito#mockStatic(Class)} 拦截静态
 *       {@code Snowflake.nextId()} ⇒ ① uid 仍可<b>确定性地</b>钉成 {@code 123456789L}
 *       （与 B 侧同值）；② 「已存在时不得碰 Snowflake」这条守卫<b>比 B 侧更强</b>
 *       ——B 侧只能验「没调实例方法」，C 侧能验「静态方法一次都没被调」。</li>
 *   <li><b>为什么必须这么改</b>：若照抄 B 侧的 {@code verifyNoInteractions(snowflakeBean)}，
 *       在 C 侧会变成一条<b>恒真、恒绿</b>的空断言（C 侧那个注入实例永远不会被调用）
 *       ⇒ 正是 AGENTS.md §3.3 说的「假绿」形态。</li>
 * </ul>
 */
class AdminSemanticDriftRegressionTest {

    private AdminMapper adminMapper;
    private AdminInviteMapper adminInviteMapper;
    private UserMapper userMapper;
    private AdminCreateAtomicService createAtomicService;
    private AdminDomainService service;

    /** 与 AdminDomainServiceTest 同款 hack：不启Spring 容器时手动初始化 MP lambda cache。 */
    @BeforeAll
    static void initMyBatisPlusTableInfo() throws Exception {
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        java.lang.reflect.Method initMethod = TableInfoHelper.class.getDeclaredMethod(
                "initTableInfo",
                org.apache.ibatis.session.Configuration.class,
                String.class,
                Class.class
        );
        initMethod.setAccessible(true);
        initMethod.invoke(null, config, "com.rag.admin.mapper.AdminMapper", AdminEntity.class);
        initMethod.invoke(null, config, "com.rag.admin.mapper.AdminInviteMapper", AdminInviteEntity.class);
        initMethod.invoke(null, config, "com.rag.admin.mapper.UserMapper", UserEntity.class);
    }

    @BeforeEach
    void setUp() {
        adminMapper = Mockito.mock(AdminMapper.class);
        adminInviteMapper = Mockito.mock(AdminInviteMapper.class);
        userMapper = Mockito.mock(UserMapper.class);
        createAtomicService = Mockito.mock(AdminCreateAtomicService.class);
        Mockito.when(userMapper.selectList(any())).thenReturn(Collections.emptyList());
        Mockito.when(userMapper.selectCount(any())).thenReturn(0L);

        service = new AdminDomainService(
                adminMapper,
                adminInviteMapper,
                userMapper,
                Mockito.mock(StringRedisTemplate.class),
                new AdminRpcProperties(),
                new AdminRpcMetrics(new SimpleMeterRegistry()),
                createAtomicService);
    }

    /**
     * 取出 wrapper 上真正被绑定的参数值集合（pattern 不会出现在 SQL 文本里，只在这里）。
     *
     * <p><b>注意</b>：MyBatis-Plus 的 {@code formatParam} 是在 {@code appendSqlSegments}
     * 的 lambda 里被<b>惰性</b>调用的，参数不进 {@code paramNameValuePairs} 直到 SQL 真正被渲染。
     * 所以必须先调一次 {@code getTargetSql()}，否则读到的会是空 map——
     * 这会让断言假阴性通过。
     */
    private static List<Object> boundParams(LambdaQueryWrapper<?> w) {
        w.getTargetSql();
        return List.copyOf(w.getParamNameValuePairs().values());
    }

    /**
     * 判断绑定参数里是否存在<b>包含</b> {@code needle} 的值。
     *
     * <p>不能用 {@code List.contains}：那是全等匹配，而这里要断言的是
     * "LIKE pattern 里原样保留了 keyword 的空格" —— 即该值<b>包含</b>某个子串。
     */
    private static boolean boundParamsContain(LambdaQueryWrapper<?> w, String needle) {
        return boundParams(w).stream()
                .anyMatch(v -> String.valueOf(v).contains(needle));
    }

    // ==================================================================
    // DRIFT-001：默认管理员密码
    // ==================================================================

    /**
     * Go 出处：internal/svc/servicecontext.go:17-19
     * <pre>
     *   const (
     *       defaultAdminUsername = "admin"
     *       defaultAdminPassword = "admin"      // 硬编码常量，不读 Config
     *   )
     * </pre>
     * {@code System.DefaultPassword}（yaml 里的 "123456"）只服务于
     * {@code ResetUserPasswordLogic} 给<i>普通用户</i>重置密码，与 seeder 无关。
     */
    @Test
    @DisplayName("DRIFT-001：seeder 造的 admin 密码是 admin，不是 system.default-password 的 123456")
    void seeder_defaultAdminPasswordIsHardcodedAdmin() {
        Mockito.when(adminMapper.selectOne(any())).thenReturn(null);   // 不存在 → 走创建分支
        Mockito.when(adminMapper.insert(any(AdminEntity.class))).thenReturn(1);

        // C 侧 uid 走**静态** Snowflake.nextId()（见类级 javadoc「唯一一处非机械改动」）⇒ 用 mockStatic 钉住。
        try (MockedStatic<Snowflake> snowflake = Mockito.mockStatic(Snowflake.class)) {
            snowflake.when(Snowflake::nextId).thenReturn(123456789L);

            AdminSeederBootstrap seeder =
                    new AdminSeederBootstrap(adminMapper, new Snowflake());
            seeder.ensureDefaultAdmin();
        }

        ArgumentCaptor<AdminEntity> captor = ArgumentCaptor.forClass(AdminEntity.class);
        Mockito.verify(adminMapper).insert(captor.capture());
        String hash = captor.getValue().getPassword();

        assertTrue(PasswordUtil.check("admin", hash), "Go 侧默认管理员密码应为 admin");
        assertFalse(PasswordUtil.check("123456", hash),
                "DRIFT-001 回归：seeder 误用了 system.default-password(123456)");
    }

    /** 幂等：已存在时不得重复插入，也不得碰 Snowflake。 */
    @Test
    @DisplayName("DRIFT-001：admin 已存在时 seeder 不重复插入")
    void seeder_isIdempotentWhenAdminExists() {
        AdminEntity existing = new AdminEntity();
        existing.setUid(1L);
        existing.setUsername("admin");
        Mockito.when(adminMapper.selectOne(any())).thenReturn(existing);

        // C 侧的等价守卫比 B 侧更强：B 侧能验「没调 SnowflakeBean 的实例方法」，
        // C 侧能验「静态 Snowflake.nextId() 一次都没被调」（即没消耗 id）。
        try (MockedStatic<Snowflake> snowflake = Mockito.mockStatic(Snowflake.class)) {
            new AdminSeederBootstrap(adminMapper, new Snowflake()).ensureDefaultAdmin();

            snowflake.verify(Snowflake::nextId, Mockito.never());
        }

        Mockito.verify(adminMapper, Mockito.never()).insert(any(AdminEntity.class));
    }

    // ==================================================================
    // DRIFT-006：keyword 不 trim
    // ==================================================================

    /**
     * Go 出处：internal/model/admin.go:152-155
     * <pre>
     *   if len(keyword) &gt; 0 {
     *       keyWord := "%" + keyword + "%"
     *       db = db.Where("username LIKE ? OR email LIKE ?", keyWord, keyWord)
     *   }
     * </pre>
     * keyword=" " 时 {@code len>0} 成立 ⇒ 走 {@code LIKE '%  %}。
     * 本实现此前用 {@code keyword.trim().isEmpty()} 判空 ⇒ 返回全量。
     */
    @Test
    @DisplayName("DRIFT-006：空白 keyword 仍走 LIKE 过滤，pattern 保留原始空格")
    void getUserList_whitespaceKeywordStillFilters() {
        service.getUserList(1, 20, " ");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<UserEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(userMapper).selectCount(captor.capture());
        LambdaQueryWrapper<UserEntity> w = captor.getValue();

        assertTrue(w.getTargetSql().contains("LIKE"),
                "空白 keyword 应生成 LIKE 过滤，实际 SQL：" + w.getTargetSql());
        // MyBatis-Plus 的 like() 会在值两侧再包一层 %，所以这里实际绑定的是
        //   "%" + ("%" + keyword + "%") + "%"  ==  "%%" + keyword + "%%"
        // 对 keyword=" " 即 "%%" + " " + "%%"。
        // 生成的 SQL 是 LIKE '%%%keyword%%%'，与 Go 的 LIKE '%keyword%' 匹配集合相同
        // （连续的 % 会折叠），因此这只是 IMPLEMENTATION DIFFERENCE，不是语义漂移。
        assertTrue(boundParamsContain(w, "% %"),
                "pattern 应保留 keyword 的原始空格，实际绑定参数：" + boundParams(w));
    }

    /** keyword 含前后空格时，pattern 必须原样带空格（Go 不 trim）。 */
    @Test
    @DisplayName("DRIFT-006：keyword 前后空格不被裁掉")
    void getUserList_keywordSpacesArePreserved() {
        service.getUserList(1, 20, " alice ");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<UserEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(userMapper).selectList(captor.capture());
        LambdaQueryWrapper<UserEntity> w = captor.getValue();

        assertTrue(boundParamsContain(w, "% alice %"),
                "pattern 应保留 keyword 的前后空格，实际绑定参数：" + boundParams(w));
        assertFalse(boundParamsContain(w, "%alice%"),
                "DRIFT-006 回归：pattern 被 trim 了（trim 后会得到 %%alice%%）");
    }

    /** 空 keyword（""）才是真正的"无过滤"。 */
    @Test
    @DisplayName("DRIFT-006：空 keyword 不生成 LIKE 条件")
    void getUserList_emptyKeywordHasNoFilter() {
        service.getUserList(1, 20, "");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<UserEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(userMapper).selectCount(captor.capture());
        LambdaQueryWrapper<UserEntity> w = captor.getValue();

        assertFalse(w.getTargetSql().contains("LIKE"),
                "空 keyword 不应生成 LIKE 条件，实际：" + w.getTargetSql());
    }

    // ==================================================================
    // DRIFT-007：rpc 层不归一化分页参数
    // ==================================================================

    /**
     * ⚠️ <b>本条在 2026-10-22 被推翻重写（E-3 根因 R4）—— 旧断言是错的</b>。
     *
     * <p><b>旧断言</b>：「page=0 算出 OFFSET -20，原样下传给 PG 报错」。
     * <p><b>它错在哪</b>：Go 的 {@code model/admin.go:159-160} 是真实文件，但那两行<b>不是全部</b> ——
     * 真实行为还包含 GORM 的一层<b>静默抑制</b>。权威出处是 gorm.io/gorm@v1.31.1
     * {@code clause/limit.go} 的 {@code Limit.Build}：
     * <pre>
     *   if limit.Limit != nil &amp;&amp; *limit.Limit &gt;= 0 { 写 "LIMIT "  }
     *   if limit.Offset &gt; 0                            { 写 "OFFSET "  }
     * </pre>
     * 即 page=0 → offset=-20 → <b>不生成 OFFSET</b>，SQL 退化成 {@code ORDER BY id DESC LIMIT 20}，
     * 返回首页，<b>不报错</b>。
     *
     * <p><b>独立实测</b>（Go admin-api 18889 真进程，curl 直打）：
     * {@code page=0} / {@code page=-1} / {@code page_size=-5} / {@code page=int64max}
     * <b>四种全部 HTTP 200 + code 200</b>。
     *
     * <p>⇒ 这与 §3.4.3 末尾那条纪律同型：<b>引用了真实文件、却把 GORM 的行为读反了</b>，
     * 与 DRIFT-009（把 advice chain 的内外关系读反）一模一样。
     * 旧断言之所以能长期为绿，正是因为它<b>与被测代码共享同一个错误前提</b> ——
     * 断言和实现互相印证，测的是「我们俩想法一致」，不是「和 Go 一致」。
     */
    @Test
    @DisplayName("R4：page=0 → 负 offset 被 GORM 语义抑制，SQL 只剩 LIMIT 20（Go 是 200 不是 5001）")
    void getUserList_pageZeroSuppressesOffset() {
        service.getUserList(0, 20, "");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<UserEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(userMapper).selectList(captor.capture());
        String sql = captor.getValue().getTargetSql();

        assertTrue(sql.contains("LIMIT 20"), "pageSize=20 仍应生成 LIMIT 20，实际：" + sql);
        assertFalse(sql.contains("OFFSET"),
                "offset=-20 必须被抑制（Go 的 GORM 只在 offset>0 时生成 OFFSET），实际：" + sql);
    }

    /** 同一条规则的反向：page_size 为负时 LIMIT 也不生成，且 OFFSET 为负时同样不生成。 */
    @Test
    @DisplayName("R4：pageSize=-5 → LIMIT 与 OFFSET 双双被抑制（Go 返回全量，不是 400）")
    void getUserList_negativePageSizeSuppressesLimit() {
        service.getUserList(1, -5, "");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<UserEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(userMapper).selectList(captor.capture());
        String sql = captor.getValue().getTargetSql();

        assertFalse(sql.contains("LIMIT"),
                "pageSize=-5 < 0 ⇒ GORM 不生成 LIMIT，实际：" + sql);
        assertFalse(sql.contains("OFFSET"),
                "offset=(1-1)*(-5)=0，不是正数 ⇒ 不生成 OFFSET，实际：" + sql);
    }

    @Test
    @DisplayName("DRIFT-007：pageSize=0 不被兜底成 20")
    void getUserList_pageSizeZeroNotNormalized() {
        service.getUserList(1, 0, "");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<UserEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(userMapper).selectList(captor.capture());
        String sql = captor.getValue().getTargetSql();

        assertTrue(sql.contains("LIMIT 0"),
                "pageSize=0 不应被兜底成 20，实际：" + sql);
    }

    /** 正常分页不受影响：仍按 id desc + 正确 offset。 */
    @Test
    @DisplayName("DRIFT-007：正常分页保持 ORDER BY id DESC 与正确 OFFSET")
    void getUserList_normalPagingUnchanged() {
        service.getUserList(3, 10, "");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<UserEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(userMapper).selectList(captor.capture());
        LambdaQueryWrapper<UserEntity> w = captor.getValue();
        String sql = w.getTargetSql();

        assertTrue(sql.contains("ORDER BY"), sql);
        assertTrue(sql.contains("id DESC"), sql);
        assertTrue(sql.contains("LIMIT 10 OFFSET 20"), sql);
    }

    // ==================================================================
    // DRIFT-008：createAdmin 不校验 username 非空
    // ==================================================================

    /**
     * Go 出处：service/user/admin/rpc/internal/logic/createadminlogic.go:44-46 只做 {@code strings.TrimSpace}，
     * <b>没有</b>空值判断；空 username 会照常进事务、查重并 INSERT。
     * 本实现此前抛 {@code INVALID_ARGUMENT}，而 AdminCreateService 无该 case ⇒ 被报成 1015。
     *
     * <p><b>⚠️ 2026-10-22 R5 修复后，本断言的第 3 个参数语义已变</b>：
     * 旧断言假定第 3 参是<b>已哈希</b>的密码（{@code any(String.class)} 恰好两者都匹配，
     * 所以它过去一直是绿的）。R5 把 bcrypt 移进事务后，
     * {@code createAdmin} 改调 7 参重载，第 3 参是<b>明文</b>。
     * 现在这里用 {@code eq("pwd")} 显式锁死「传下去的是明文」——
     * 若有人把明文提前哈希掉再传，这条会红。
     */
    @Test
    @DisplayName("DRIFT-008：username 为空串时不报 INVALID_ARGUMENT，照常进事务")
    void createAdmin_emptyUsernameProceedsToTransaction() {
        // 注意：AdminDomainService.createAdmin 丢弃 createAdminAtomic 的返回值，
        // 直接返回事务外生成的 snowflake uid（Go 端同样是事务外生成的 uid 被返回）。
        long uid = service.createAdmin("   ", "pwd", null, null, "ADM-CODE");

        assertTrue(uid > 0, "应返回事务外生成的 snowflake uid");
        // R5：最后一位 passwordIsPlaintext 必须为 true —— 哈希由 AdminCreateAtomicService
        // 在事务内、W2 查 username 之后执行（对齐 Go createadminlogic.go 的步骤序）
        Mockito.verify(createAtomicService).createAdminAtomic(
                eq(""), eq("ADM-CODE"), eq("pwd"), any(), any(), any(Long.class), eq(true));
    }

    /** 对照：inviteCode 为空仍然必须被拒（Go: PermissionDenied）。 */
    @Test
    @DisplayName("DRIFT-008 对照：inviteCode 为空仍抛 PERMISSION_DENIED")
    void createAdmin_emptyInviteCodeStillRejected() {
        io.grpc.StatusRuntimeException ex = assertThrows(
                io.grpc.StatusRuntimeException.class,
                () -> service.createAdmin("alice", "pwd", null, null, "  "));
        assertEquals(io.grpc.Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        Mockito.verifyNoInteractions(createAtomicService);
    }

    // ==================================================================
    // 防回退：本轮刻意<b>没</b>改的语义（防止"顺手统一"）
    // ==================================================================

    /**
     * 防回退：resetUserPassword 必须读 {@code Config.System.DefaultPassword}，
     * <b>不能</b>跟着 seeder 一起改成硬编码常量。
     * Go 出处：resetuserpasswordlogic.go:56 {@code cryptx.PasswordEncrypt(l.svcCtx.Config.System.DefaultPassword)}。
     */
    @Test
    @DisplayName("防回退：resetUserPassword 仍读 system.default-password，而非 seeder 硬编码常量")
    void resetUserPassword_usesConfiguredDefaultNotSeederConstant() {
        var props = new AdminRpcProperties();
        props.getSystem().setDefaultPassword("cfg-pwd-123456");
        var svc = new AdminDomainService(
                adminMapper, adminInviteMapper, userMapper,
                Mockito.mock(StringRedisTemplate.class), props,
                new AdminRpcMetrics(new SimpleMeterRegistry()), createAtomicService);

        UserEntity user = new UserEntity();
        user.setUid(100L);
        Mockito.when(userMapper.selectOne(any())).thenReturn(user);
        Mockito.when(userMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertTrue(svc.resetUserPassword(100L, 1L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<UserEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(userMapper).update(Mockito.isNull(), captor.capture());
        LambdaUpdateWrapper<UserEntity> w = captor.getValue();

        // ⚠️ 本断言原先写死 `"password=#{ew.paramNameValuePairs.MPGENVAL1}"`，
        // 那是一条**把缺陷锁死**的绿测试：它断言 UPDATE 的 SET 子句**不含** update_time，
        // 而 Go 侧 `gorm:"autoUpdateTime"`（data_model.go:13，消费点
        // gorm@v1.31.1/callbacks/update.go:238-254）每次 Updates 都会推进它。
        // ⇒ 修复 DRIFT-ADMIN-RPC-003 时**必须先改这条断言**（AGENTS.md §3.3.2：
        // 「修复漂移时先改/删错误断言，再改实现」），否则会先撞上「测试红了」而误以为改错了方向。
        // 这里改成包含 update_time 的正确形状，并保留「只改密码时也推进时间戳」这层含义。
        assertEquals("password=#{ew.paramNameValuePairs.MPGENVAL1},update_time = CURRENT_TIMESTAMP",
                w.getSqlSet(),
                "SET 子句必须同时含 password 与 update_time（DRIFT-ADMIN-RPC-003 / GORM autoUpdateTime）");
        String hash = String.valueOf(w.getParamNameValuePairs().get("MPGENVAL1"));
        assertTrue(PasswordUtil.check("cfg-pwd-123456", hash),
                "重置密码应使用配置的 system.default-password");
        assertFalse(PasswordUtil.check(AdminDomainService.DEFAULT_ADMIN_PASSWORD, hash),
                "重置密码不应误用 seeder 的硬编码常量");
    }

    /** total 来自独立 count 查询，不等于当前页行数。 */
    @Test
    @DisplayName("防回退：total 来自独立 count 查询，不等于当前页行数")
    void getUserList_totalComesFromCountNotPageSize() {
        Mockito.when(userMapper.selectCount(any())).thenReturn(137L);
        Mockito.when(userMapper.selectList(any())).thenReturn(List.of(new UserEntity()));

        var result = service.getUserList(1, 20, "");

        assertEquals(137L, result.total());
        assertEquals(1, result.users().size());
    }

}
