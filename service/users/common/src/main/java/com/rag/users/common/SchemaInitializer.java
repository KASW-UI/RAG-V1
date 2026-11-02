package com.rag.users.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * <p><b>users 域 DDL 的幂等执行器</b>（无版本账本，每次启动原样重放）。
 *
 * <p><b>B 出处（本次迁移的行为基线）</b>：
 * <pre>
 *   B: RAG  service/users/users-common/src/main/java/com/rag/users/common/ddl/SchemaInitializer.java
 *      —— 第 63-68 行 脚本清单（SCRIPTS）、第 91 行 advisory lock 键、
 *         第 109-136 行 {@code applyAll(Connection, String)}。
 *   消费方（B 侧）：
 *      service/users/user/user-rpc/.../bootstrap/UserSchemaBootstrap.java:51
 *      service/users/admin/admin-rpc/.../bootstrap/AdminSchemaBootstrap.java:55
 *   脚本（B 侧，随 users-common 一起打包）：
 *      src/main/resources/db/01_create_user.sql
 *      src/main/resources/db/02_create_user_avatar_history.sql
 *      src/main/resources/db/03_create_admin.sql
 *      src/main/resources/db/04_create_admin_invite.sql
 * </pre>
 *
 * <h2>🔴 为什么 users 域不用 Flyway</h2>
 * <p>{@code flyway_schema_history} 是 <b>schema 级</b>账本，一个 schema 只能有一本账；
 * 而 user-service 与 admin-service <b>必须连同一个库</b>（admin 侧也要读写 {@code users_user}）
 * ⇒ 两个进程各带一套 {@code db/migration/} 必然撞账本（版本号相同、内容不同 ⇒
 * 后启动者要么静默跳过自己的迁移，要么 checksum mismatch 启动失败）。
 * <p>⇒ 本仓沿用 B 的定案：users 域<b>弃用 Flyway</b>、改用本类做幂等重放
 * （与 {@code docs/完全spring化的文件树.md} §「users 域 {@code SchemaInitializer} 幂等重放 —— 保留（已弃用 Flyway）」一致）。
 * <p>⇒ DDL 全部写成 {@code CREATE ... IF NOT EXISTS} + 幂等 {@code ALTER} ⇒ 谁先启动都对。
 *
 * <h2>语义等价论证（对 B 逐条）</h2>
 * <ol>
 *   <li><b>脚本清单与执行顺序逐条一致</b>：4 个脚本、同样的文件名、同样的数字前缀顺序
 *       ⇒ 建表集合与顺序相同。</li>
 *   <li><b>「失败必须让启动失败」</b>：B 用 {@code ScriptUtils.executeSqlScript}（默认
 *       {@code continueOnError=false}）⇒ 任一语句失败即抛 {@link SQLException}；C 沿用同一调用与同一默认值
 *       ⇒ <b>不吞异常</b>。</li>
 *   <li><b>PG advisory lock</b>：同一个常量键（{@value #ADVISORY_LOCK_KEY}）⇒ 两个进程并发建表时
 *       仍然串行化 ⇒ <b>并发建表不炸</b>（PG 的 {@code CREATE TABLE IF NOT EXISTS} 在并发下并不安全，
 *       后到者可能撞 {@code pg_type_typname_nsp_index}）。</li>
 *   <li><b>锁的释放</b>：B 在 {@code finally} 里显式 {@code pg_advisory_unlock}，失败只告警不抛
 *       ⇒ C <b>逐条一致</b>（会话级锁在连接断开时也会自动释放）。</li>
 *   <li><b>连接由调用方持有</b>：本类不开连接、不管事务 ⇒ 生命周期与 B 相同。</li>
 * </ol>
 *
 * <p>⚠️ <b>不是 {@code @Component}</b>（与 B 一致）：本模块是<b>库</b>，各 app 只扫自己的包
 * ⇒ 挂 {@code @Component} 只会造一个永不生效的假 bean ⇒ 保持<b>静态工具 + 各服务自己的
 * {@code wiring/*SchemaBootstrap} 显式调用</b>（B 侧的
 * {@code UserSchemaBootstrap} / {@code AdminSchemaBootstrap} 正是这个角色）。
 *
 * <p>⚠️ <b>Spring 版本差异</b>：B 侧跑在 spring-jdbc 7.x，C 侧是 Boot 3.5 的 spring-jdbc 6.2.x
 * ⇒ {@code ScriptUtils} 的<b>包名与语义未变</b>（{@code org.springframework.jdbc.datasource.init.ScriptUtils}），
 * 但两版<b>都不支持 PG 美元引号（{@code $$}）</b>（B 侧已实测确认并因此把条件守卫改写成
 * 「{@code SET TIME ZONE 'UTC'} + 恒等 ALTER」）⇒ <b>脚本内容必须原样照抄，不得引入 {@code $$} 块</b>。
 */
public final class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    /**
     * users 域全部 DDL，按文件名顺序执行。
     *
     * <p>数字前缀只保证<b>确定性顺序</b>，不表示版本。B 出处：{@code SchemaInitializer.java:63-68}。
     */
    private static final String[] SCRIPTS = {
            "db/01_create_user.sql",
            "db/02_create_user_avatar_history.sql",
            "db/03_create_admin.sql",
            "db/04_create_admin_invite.sql",
    };

    /**
     * PG advisory lock 的键（bigint）。
     *
     * <p>B 出处：{@code SchemaInitializer.java:91}（{@code ADVISORY_LOCK_KEY = 8_675_309L}）。
     * <p>⚠️ <b>键值是常量，两个服务必须用同一个</b> ⇒ 改键等于让并发建表的保护失效。
     */
    private static final long ADVISORY_LOCK_KEY = 8_675_309L;

    private SchemaInitializer() {
    }

    /**
     * 用给定连接把全部 DDL 原样重放一遍。
     *
     * <p>连接由调用方持有 —— 通常是 Spring 注入的 {@code DataSource}。
     * 本类刻意<b>不</b>自己开连接，避免每份脚本各拿一条、也便于调用方把整段纳入自己的生命周期。
     *
     * <p>B 出处：{@code SchemaInitializer.java:109-136}（{@code applyAll}）。
     *
     * @param serviceName 日志里用来区分是哪个进程在执行
     * @param connection  已建立的 JDBC 连接，调用方负责关闭
     * @throws SQLException 任一 DDL 失败即抛出 —— <b>必须让启动失败</b>，绝不能吞掉
     */
    public static void applyAll(Connection connection, String serviceName) throws SQLException {
        // 先拿会话级 advisory lock：把两个服务并发建表这件事串行化。
        // 拿不到就一直等（PG 的 pg_advisory_lock 是阻塞语义），等到即可，
        // 因为另一个进程执行完就会释放 —— 不会死锁，也不需要超时。
        boolean locked = false;
        try (Statement st = connection.createStatement()) {
            st.execute("select pg_advisory_lock(" + ADVISORY_LOCK_KEY + ")");
            locked = true;
            log.info("schema init [{}]: acquired advisory lock {}", serviceName, ADVISORY_LOCK_KEY);
            for (String script : SCRIPTS) {
                log.info("schema init [{}]: applying {}", serviceName, script);
                // continueOnError=false（ScriptUtils 默认）：任一条语句失败即抛 SQLException。
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(script));
            }
        } finally {
            if (locked) {
                // 会话级锁在连接断开时也会自动释放，这里显式释放只是为了尽快放开，
                // 避免另一个进程干等。失败不抛：解锁失败不影响 DDL 本身的结论。
                try (Statement st = connection.createStatement()) {
                    st.execute("select pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
                } catch (SQLException e) {
                    log.warn("schema init [{}]: advisory unlock failed (连接断开也会自动释放)",
                            serviceName, e);
                }
            }
        }
        log.info("schema init [{}]: all {} scripts applied", serviceName, SCRIPTS.length);
    }

    /**
     * 脚本清单（只读视图）—— 自检 / 契约对拍用。
     *
     * @return 脚本的 classpath 路径，顺序 = 执行顺序
     */
    public static String[] scripts() {
        return SCRIPTS.clone();
    }

    /**
     * advisory lock 键 —— 两个服务必须共用同一个（改键 = 并发建表保护失效）。
     *
     * @return lock 键
     */
    public static long advisoryLockKey() {
        return ADVISORY_LOCK_KEY;
    }
}
