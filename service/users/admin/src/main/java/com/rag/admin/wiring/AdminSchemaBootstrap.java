package com.rag.admin.wiring;

import com.rag.users.common.SchemaInitializer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * <p><b>职责</b>：admin 域启动期建表 —— 把 users 域全部 DDL <b>幂等重放</b>一遍。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>包名</b>（{@code com.rag.users.admin.rpc.bootstrap} →
 *       {@code com.rag.admin.wiring}，D7 包压平）＋ <b>建表执行器换落点</b> ——
 *       B 侧 {@code com.rag.users.common.ddl.SchemaInitializer} ⇒
 *       本仓 {@link SchemaInitializer}（{@code com.rag.users.common} · users-common 模块 ·
 *       D17「users 域不用 Flyway」的落地件）⇒ ⇒ <b>本类只调它，不自己重放 DDL</b>。</li>
 *   <li><b>语义还一样吗</b>：一样。
 *       <ul>
 *         <li>DDL 脚本清单（4 个：{@code db/01..04_*.sql}）与执行顺序由共享层持有 ⇒ 逐条一致；</li>
 *         <li>PG advisory lock（{@code 8675309}）串行化并发建表 ⇒ 逐条一致；</li>
 *         <li>「失败必须让启动失败」（不吞异常）⇒ 逐条一致。</li>
 *       </ul>
 *       唯一变化：日志里的服务名标签 {@code "admin-rpc"} → {@code "admin-service"}
 *       （<b>纯日志字符串</b>，不进 wire、不进任何契约）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/bootstrap/AdminSchemaBootstrap.java}。</li>
 * </ol>
 *
 * <p><b>为什么 admin 域也要建 user 域的表</b>：它<b>也读写 {@code users_user}</b>
 * （{@code BanUser} / {@code DeleteUser} / {@code ResetUserPassword} / {@code UpdateUser}），
 * 这是它自己那份迁移注释里写明的设计意图。
 * DDL 全部是 {@code CREATE ... IF NOT EXISTS}，所以两个进程先后或同时启动都安全 ——
 * 这正是 B 侧 GORM {@code AutoMigrate} 的无状态语义。
 *
 * <p><b>B 出处</b>：{@code service/user/admin/rpc/internal/model/db.go:11-18} ——
 * {@code db.AutoMigrate(&Admin{}, &AdminInvite{}, &User{})}，
 * 其中 {@code &User{}} 就是「admin-rpc 也管 users_user」的直接证据；
 * 失败走 {@code log.Fatalln("数据表迁移失败")}。
 *
 * <p><b>⚠️ 时序约束：本类必须在 {@code AdminSeederBootstrap} 之前跑完。</b>
 * {@code AdminSeederBootstrap} 是 {@code @PostConstruct} 且注入 {@code AdminMapper}，
 * 一上来就 {@code SELECT ... FROM admin_admin}。
 * DDL 若排在它后面，启动就会挂在
 * {@code PSQLException: relation "admin_admin" does not exist}。
 * 约束由 {@code AdminSeederBootstrap} 上的 {@code @DependsOn("adminSchemaBootstrap")} 保证
 * —— 而<b>不是</b>靠 bean 定义的扫描顺序（那只是巧合，不可依赖）。
 */
@Component
public class AdminSchemaBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminSchemaBootstrap.class);

    private final DataSource dataSource;

    public AdminSchemaBootstrap(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initSchema() {
        log.info("admin-service schema init starting");
        try (Connection connection = dataSource.getConnection()) {
            // ⚠️ 服务名标签是**日志字符串**（不进 wire）⇒ 本仓按 D1 合并后的进程名写 "admin-service"
            //    （B 侧写的是 "admin-rpc"，因为 B 侧 admin-rpc 是独立进程）。
            SchemaInitializer.applyAll(connection, "admin-service");
        } catch (Exception e) {
            // 不吞：建表失败必须让整个进程起不来。
            // 特别地，如果 admin_admin 没建出来，AdminSeederBootstrap 会在
            // @PostConstruct 里报 relation "admin_admin" does not exist，
            // 那条报错完全指不到真正的原因（DDL 为什么没跑），所以这里必须先拦一道。
            throw new IllegalStateException("admin-service 建表失败，进程拒绝启动", e);
        }
        log.info("admin-service schema init done");
    }
}
