package com.rag.user.mapper;

import com.rag.user.domain.entity.UserAvatarHistoryEntity;
import com.rag.user.exception.UserExceptions.AvatarHistoryNotFoundException;
import com.rag.user.support.TestPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p><b>「4 步 / 5 步原子」的事务边界真实测</b> —— 把 D-005 / D-015 从「源码上看着像」推到
 * <b>运行期已证实</b>。
 *
 * <h2>🔴 为什么这条必须有运行证据（AGENTS.md §3.3）</h2>
 * <p>本域把事务注解挂在 <b>MyBatis-Plus mapper 接口的 {@code default} 方法</b>上：
 * <pre>
 *   {@code @org.springframework.transaction.annotation.Transactional}
 *   default UserAvatarHistoryEntity createAvatarHistory(...)
 * </pre>
 * 这是本仓里<b>最容易被静默失效</b>的一种形态 —— mapper 是 MyBatis 的 JDK 代理，
 * {@code @Transactional} 落在接口的 default 方法上，<b>能不能真的进事务链</b>取决于
 * ① Spring 的 auto-proxy 是否包住了 {@code FactoryBean} 产出的 mapper 代理
 * ② 事务 advisor 是否认得 default 方法上的注解
 * ③ MyBatis 的 {@code SqlSessionTemplate} 是否参与进已开启的 Spring 事务。
 * <p>AGENTS.md §3.3.1：**「类上有注解」不算证据** ⇒ ⇒ 本类用<b>真 PostgreSQL + 真约束冲突</b>实测。
 *
 * <h2>🔴 为什么不用 Mockito 桩注入失败</h2>
 * <p>桩掉 mapper 方法 ⇒ <b>连真实 SQL 都不发</b> ⇒ 证不了「事务是否真的把写回滚了」。
 * ⇒ 本类用<b>数据库自己的 UNIQUE 约束</b>制造中途失败（W3 INSERT 撞
 * {@code history_id} 的 UNIQUE）⇒ 这是<b>真实 DB 错误</b>（{@code DuplicateKeyException}）
 * ⇒ 回滚与否只能由真实事务语义决定。
 *
 * <h2>🔴 判据为什么非空洞（同条件对照 · AGENTS.md §3.3.3）</h2>
 * <p>「回滚了」单独看是空洞断言（万一 W2 压根没执行呢？）。⇒ 本类同时测<b>同一条序列的
 * 成功路径</b>：
 * <ul>
 *   <li><b>失败路径</b>：W3 撞 UNIQUE ⇒ 断言<b>旧 history 仍是 current</b>（W2 被回滚）；</li>
 *   <li><b>成功路径</b>：W3 用新 id ⇒ 断言<b>旧 history 落 false、新 history 落 true</b>
 *       （W2 <b>确实</b>执行了、确实落库了）。</li>
 * </ul>
 * ⇒ 两条合起来 ⇒ 「W2 会写库」与「失败时 W2 不留痕」同时被证实 ⇒ ⇒ 事务边界<b>真的存在</b>。
 *
 * <h2>B 出处（语义等价论证）</h2>
 * <p>B 侧（Go）：{@code m.conn.WithContext(ctx).Transaction(func(tx *gorm.DB) error {…})}
 * —— 4 步（createAvatarHistory）/ 5 步（selectAvatarHistory）包在<b>同一个 DB 事务</b>里
 * ⇒ 任一步失败 ⇒ 整段回滚（C 侧的 {@code @Transactional} + PG 事务 = 同构）。
 * <p>B 侧 C 侧等价点：D-005（4/5 步必须原子）· D-015（history 查询必须带 uid 条件，防越权取别人头像）。
 *
 * <p>⚠️ <b>测试基础设施</b>：真 PostgreSQL（Testcontainers {@code postgres:16-alpine}）
 * —— 本域 DDL 用到 {@code TIMESTAMPTZ} / {@code UNIQUE} 约束 ⇒ H2 认不了 ⇒ H2 上「跑绿」是假绿。
 * <p>⚠️ <b>禁 {@code @EnabledIfDockerAvailable}</b>（AGENTS.md §五.8 守门硬规则 ③）
 * ⇒ 容器不可用时直接失败，不静默跳过（跳过 = 假绿）。
 */
@SpringBootTest
@ActiveProfiles("test")
class AvatarTransactionWiringTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    /** uid 序号器：与其它测试类共用同一个库 ⇒ uid / username / history_id 必须全局唯一。 */
    private static final AtomicLong UID_SEQ = new AtomicLong(1_000_000_000_000L);

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserAvatarHistoryMapper userAvatarHistoryMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ======================== 4 步原子（D-005） ========================

    @Test
    @DisplayName("🔴 4 步原子真回滚：W3 撞 UNIQUE(history_id) ⇒ W2 的 clearCurrent 被回滚（旧头像仍是 current）")
    void createAvatarHistoryRollsBackTheEarlierWriteOnMidwayFailure() {
        long uid = insertUser("rollback");
        String historyId = "hist-" + uid + "-a";
        insertHistory(uid, historyId, true);

        // 前置：这条 history 此刻是 current
        assertThat(isCurrent(uid, historyId)).isTrue();

        // 用**同一个 history_id** 再走一遍 createAvatarHistory
        // ⇒ W1 查 user ✓ → W2 clearCurrentByUid（UPDATE）✓ → W3 INSERT 撞 UNIQUE ⇒ **真实 DB 失败**
        assertThatThrownBy(() -> userAvatarHistoryMapper.createAvatarHistory(
                userMapper, uid, historyId, "http://cdn/new.png", "image/png", 20L))
                .isInstanceOf(DataAccessException.class);

        // 🔴 判定：W2 的 UPDATE 必须**不留痕**（这才是「回滚」的运行期证据）
        assertThat(isCurrent(uid, historyId))
                .as("W2 的 clearCurrentByUid 必须随事务回滚 ⇒ 旧 history 仍是 current")
                .isTrue();
        // 失败的 INSERT 不能留下残行
        assertThat(historyCount(uid)).isEqualTo(1);
    }

    @Test
    @DisplayName("✅ 同序列成功路径（判据非空洞的对照组）：4 步全落库，旧 current 落 false、新 current 落 true、extra_info 双写")
    void createAvatarHistoryCommitsAllFourSteps() {
        long uid = insertUser("commit");
        String oldId = "hist-" + uid + "-old";
        insertHistory(uid, oldId, true);

        String newId = "hist-" + uid + "-new";
        UserAvatarHistoryEntity created = userAvatarHistoryMapper.createAvatarHistory(
                userMapper, uid, newId, "http://cdn/new.png", "image/png", 20L);

        // W3 落库（应用侧时钟写库 ⇒ create_time 非空，见 mapper 的 DRIFT-USERRPC-V-009 注释）
        assertThat(created.getHistoryId()).isEqualTo(newId);
        assertThat(created.getCreateTime()).isNotNull();
        // W2 落库：旧 current → false
        assertThat(isCurrent(uid, oldId)).isFalse();
        // W3 落库：新 row → true
        assertThat(isCurrent(uid, newId)).isTrue();
        // W4 双写：user.extra_info["avatar"]
        String extraInfo = jdbcTemplate.queryForObject(
                "SELECT extra_info FROM users_user WHERE uid = ?", String.class, uid);
        assertThat(extraInfo).contains("http://cdn/new.png");
        assertThat(historyCount(uid)).isEqualTo(2);
    }

    // ======================== 5 步原子（D-005 / D-015） ========================

    @Test
    @DisplayName("🔴 5 步原子的 uid 隔离（D-015）：history 属于别人 ⇒ W2 就失败 ⇒ 不动任何 current")
    void selectAvatarHistoryRejectsForeignHistoryIdWithoutTouchingCurrent() {
        long ownerUid = insertUser("owner");
        long otherUid = insertUser("other");
        String ownerHistoryId = "hist-" + ownerUid + "-own";
        insertHistory(ownerUid, ownerHistoryId, true);

        // W2 查 history 带 uid 条件 ⇒ 别人的 history 查不到 ⇒ 直接抛，不进入 W3~W5
        assertThatThrownBy(() -> userAvatarHistoryMapper.selectAvatarHistory(
                userMapper, otherUid, ownerHistoryId))
                .isInstanceOf(AvatarHistoryNotFoundException.class);

        // 越权失败不得改任何 current
        assertThat(isCurrent(ownerUid, ownerHistoryId)).isTrue();
    }

    @Test
    @DisplayName("✅ 5 步原子正常路径：目标 history 落 current、旧 current 落 false、extra_info 双写")
    void selectAvatarHistoryCommitsAllFiveSteps() {
        long uid = insertUser("select5");
        String oldId = "hist-" + uid + "-old";
        String targetId = "hist-" + uid + "-target";
        insertHistory(uid, oldId, true);
        insertHistory(uid, targetId, false);

        UserAvatarHistoryEntity selected = userAvatarHistoryMapper.selectAvatarHistory(
                userMapper, uid, targetId);

        assertThat(selected.getHistoryId()).isEqualTo(targetId);
        // W3：旧 current → false
        assertThat(isCurrent(uid, oldId)).isFalse();
        // W4：目标 → true
        assertThat(isCurrent(uid, targetId)).isTrue();
        // W5：user.extra_info["avatar"] 双写
        String extraInfo = jdbcTemplate.queryForObject(
                "SELECT extra_info FROM users_user WHERE uid = ?", String.class, uid);
        assertThat(extraInfo).contains("http://cdn/" + targetId);
    }

    // ======================== 装配层证据 ========================

    @Test
    @DisplayName("🔴 mapper bean 真被事务代理包住：@Transactional 落在 default 方法上仍进 AOP 链")
    void mapperBeanIsWrappedByTransactionAdvisor() {
        // 「Bean 存在」不等于「机制生效」（AGENTS.md §3.3.3）⇒ 断言的是**代理形态**本身：
        // mapper 是 MyBatis 的 JDK 代理 ⇒ 只有 Spring 的事务 advisor 真的把它包起来，
        // 上面那条「回滚」才有可能发生。
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(userAvatarHistoryMapper))
                .as("mapper bean 必须被 Spring AOP 包住（否则 @Transactional 不生效）")
                .isTrue();

        assertThat(((org.springframework.aop.framework.Advised) userAvatarHistoryMapper).getAdvisors())
                .as("advisor 里必须有事务 advisor")
                .anySatisfy(advisor ->
                        assertThat(advisor.getClass().getName()).contains("Transaction"));
    }

    // ======================== 夹具 ========================

    private long insertUser(String tag) {
        long uid = UID_SEQ.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO users_user (uid, username, password, email, status, extra_info, "
                        + "create_time, update_time) VALUES (?, ?, ?, ?, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                uid,
                "tx-" + tag + "-" + uid,
                "pwd-" + uid,
                "tx-" + tag + "-" + uid + "@example.test",
                "{}");
        return uid;
    }

    private void insertHistory(long uid, String historyId, boolean current) {
        jdbcTemplate.update(
                "INSERT INTO users_user_avatar_history (history_id, uid, avatar_url, content_type, "
                        + "size_bytes, is_current, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                historyId, uid, "http://cdn/" + historyId, "image/png", 10L, current);
    }

    private boolean isCurrent(long uid, String historyId) {
        Boolean v = jdbcTemplate.queryForObject(
                "SELECT is_current FROM users_user_avatar_history WHERE uid = ? AND history_id = ?",
                Boolean.class, uid, historyId);
        return Boolean.TRUE.equals(v);
    }

    private int historyCount(long uid) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users_user_avatar_history WHERE uid = ?", Integer.class, uid);
        return n == null ? 0 : n;
    }
}
