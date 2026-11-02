package com.rag.user.wiring;

import com.rag.user.config.UserRpcProperties;
import com.rag.users.common.SchemaInitializer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * <p><b>user 域启动期建表</b> —— 把 users 域全部 DDL <b>幂等重放</b>一遍。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/bootstrap/UserSchemaBootstrap.java
 *   ＋ service/users/users-common/src/main/java/com/rag/users/common/ddl/SchemaInitializer.java
 * </pre>
 *
 * <h2>⚠️ 为什么需要它（为什么不用 Flyway）</h2>
 * <p>Flyway 的 {@code flyway_schema_history} 是 <b>schema 级</b>账本，一个 schema 只能有一本账；
 * 而 user 域与 admin 域<b>必须连同一个库</b>（admin 域也读写 {@code users_user}）
 * ⇒ 两侧的迁移版本号会撞车、内容还不一样
 * ⇒ ⇒ 改为<b>幂等重放 DDL</b>（{@code CREATE TABLE IF NOT EXISTS} 形态）⇒ 谁先启动都行、重复重放恒等。
 * <p>⚠️ 本仓已按 <b>D17（2026-11-02 用户定 · 「users 域不用 Flyway」）</b>落地：
 * 本域 pom <b>零 Flyway 依赖</b>，建表走 {@code users-common} 的 {@link SchemaInitializer}。
 *
 * <h2>Go 出处（B 侧注释里已记，本仓继承）</h2>
 * <pre>
 *   service/user/user/rpc/internal/model/db.go:29-36
 *       gorm.Open 之后紧跟 db.AutoMigrate(&amp;User{}, &amp;UserAvatarHistory{})
 *       失败走 log.Fatalln("数据表迁移失败") 让进程退出
 * </pre>
 * ⇒ 本类等价物：{@code @PostConstruct} 里重放 DDL，失败抛 {@link IllegalStateException}
 * 让 {@code SpringApplication} 启动失败。
 *
 * <h2>语义等价论证（逐条）</h2>
 * <table border="1">
 *   <tr><th>B 侧</th><th>C 侧</th></tr>
 *   <tr><td>{@code @Component}（包 {@code …rpc.bootstrap}）</td><td>{@code @Component}（包 {@code …user.wiring}）</td></tr>
 *   <tr><td>构造注入 {@link DataSource}</td><td>逐字一致</td></tr>
 *   <tr><td>{@code @PostConstruct initSchema()}</td><td>逐字一致（<b>时机不变</b>：容器 refresh 期、接受流量之前）</td></tr>
 *   <tr><td>{@code try (Connection c = dataSource.getConnection())}</td><td>逐字一致</td></tr>
 *   <tr><td>{@code SchemaInitializer.applyAll(connection, "user-rpc")}</td>
 *       <td>{@code SchemaInitializer.applyAll(connection, properties.getName())}
 *           ⇒ <b>默认取值仍是 {@code "user-rpc"}</b>（{@code UserRpcProperties.name} 的默认值），
 *           与 B 侧<b>字面一致</b>；且改成读配置后<b>可被 {@code user-rpc.name} 覆盖</b>（B 侧是硬编码）。</td></tr>
 *   <tr><td>{@code catch (Exception e) → throw new IllegalStateException("user-rpc 建表失败，进程拒绝启动", e)}</td>
 *       <td>逐字一致（异常文案含进程名，合并后取同一名字）</td></tr>
 *   <tr><td>两条 {@code log.info}（starting / done）</td><td>逐字一致</td></tr>
 * </table>
 *
 * <p>⚠️ <b>「不吞异常」是硬要求</b>：建表失败若被吞 ⇒ 进程会先打印「Started」，
 * 然后在第一次 SQL 时才炸 ⇒ 报错长得像业务 bug（B 侧的 {@code log.Fatalln} 就是为了避免这个）。
 *
 * <p><b>为什么放 {@code wiring/} 而不是让某个 Mapper 顺带建表</b>：启动期副作用集中在
 * {@code wiring} 包，失败一律 fail-fast（B 侧 {@code bootstrap/} 的既有约定）。
 *
 * <p>⚠️ <b>顺序约束</b>：B 侧 user-rpc <b>没有</b>顺序约束（启动期唯一的 DB 访问方是本类，
 * {@code SnowflakeBootstrap} 不碰数据库）⇒ C 侧合并后同样<b>无顺序约束</b>
 * （Mapper 只在首次调用时才发 SQL）。
 */
@Component
public class UserSchemaBootstrap {

    private static final Logger log = LoggerFactory.getLogger(UserSchemaBootstrap.class);

    private final DataSource dataSource;
    private final UserRpcProperties properties;

    public UserSchemaBootstrap(DataSource dataSource, UserRpcProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /**
     * 启动期重放全部 DDL；失败即让进程起不来。
     */
    @PostConstruct
    public void initSchema() {
        String serviceName = properties.getName();
        log.info("{} schema init starting", serviceName);
        try (Connection connection = dataSource.getConnection()) {
            SchemaInitializer.applyAll(connection, serviceName);
        } catch (Exception e) {
            // 不吞：建表失败必须让整个进程起不来，否则进程会「Started」然后在第一次
            // SQL 时才炸，报错长得像业务 bug。对齐 Go 的 log.Fatalln。
            throw new IllegalStateException(serviceName + " 建表失败，进程拒绝启动", e);
        }
        log.info("{} schema init done", serviceName);
    }
}
