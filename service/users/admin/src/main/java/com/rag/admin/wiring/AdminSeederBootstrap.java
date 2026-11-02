package com.rag.admin.wiring;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.admin.domain.entity.AdminEntity;
import com.rag.admin.domain.service.AdminDomainService;
import com.rag.admin.exception.AdminExceptions;
import com.rag.admin.mapper.AdminMapper;
import com.rag.common.security.PasswordUtil;
import com.rag.common.snowflake.Snowflake;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * <p><b>职责</b>：admin 域启动期 bootstrap —— 创建默认 admin 账号（用户名 / 密码均硬编码 {@code admin}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>包名</b>（{@code com.rag.users.admin.rpc.bootstrap} →
 *       {@code com.rag.admin.wiring}，D7 包压平）＋ <b>三处 import 替换</b>（映射表 _shared.md §1 共享层 API 替换表）——
 *       ① {@code com.rag.common.spring.SnowflakeBean} → {@link Snowflake}
 *       （{@code com.rag.common.snowflake} · 共享层 D3 上收，<b>生成状态是全进程唯一的那一份</b>）；
 *       ② {@code com.rag.users.common.cryptx.PasswordUtil} →
 *       {@link PasswordUtil}（{@code com.rag.common.security}）；
 *       ③ {@code com.rag.users.admin.rpc.domain.AdminEntity} →
 *       {@code com.rag.admin.domain.entity.AdminEntity}（D7 包压平）。
 *       <b>方法体逐字照抄</b>（含查 → 加密 → 生成 uid → 插入 → 23505 回查 → fail-fast 五段）。</li>
 *   <li><b>语义还一样吗</b>：一样。
 *       <ul>
 *         <li>密码来源仍是<b>硬编码常量</b>（{@link AdminDomainService#DEFAULT_ADMIN_PASSWORD}），
 *             <b>不读</b> {@code admin-rpc.system.default-password}（B 侧 DRIFT-001 修法，理由见下）；</li>
 *         <li>uid 仍由 {@link Snowflake#nextId()} 生成；</li>
 *         <li>PG {@code 23505} ⇒ <b>回查一次</b>确认 ⇒ 仍找不到 ⇒ {@link IllegalStateException} fail-fast；</li>
 *         <li>失败语义（抛异常 ⇒ 容器启动失败、进程退出）等价于 B 侧 {@code logx.Must(...)} 的 fatal 行为。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/bootstrap/AdminSeederBootstrap.java}。</li>
 * </ol>
 *
 * <p>对应 B 侧 internal/svc/servicecontext.go 的 ensureDefaultAdmin 业务方法：
 * <pre>
 *   const (
 *       defaultAdminUsername = "admin"
 *       defaultAdminPassword = "admin"      // ← 注意：不是 Config.System.DefaultPassword
 *   )
 *
 *   func NewServiceContext(c config.Config) *ServiceContext {
 *       ...
 *       logx.Must(svcCtx.ensureDefaultAdmin(context.Background()))
 *       return svcCtx
 *   }
 * </pre>
 *
 * <p><b>DRIFT-001（已修）· 密码来源</b>：B 侧 seeder 用的是<b>包内常量</b>
 * {@code defaultAdminPassword = "admin"}，<b>从不读</b> {@code Config.System.DefaultPassword}
 * （后者是 {@code admin.yaml} 里的 {@code "123456"}，只被
 * {@code ResetUserPasswordLogic} 用于重置<i>普通用户</i>密码）。
 * <p>本类此前误接了 {@code admin-rpc.system.default-password}，导致全新环境下
 * 默认管理员密码从 {@code admin} 变成 {@code 123456}。
 * <p>修法：seeder 完全不依赖 {@code AdminRpcProperties}，密码取
 * {@link AdminDomainService#DEFAULT_ADMIN_PASSWORD}。
 *
 * <p><b>DRIFT-010（已修）· 触发时机</b>：B 侧 {@code NewServiceContext(c)} 在
 * {@code zrpc.MustNewServer} <b>之前</b>执行 seeder，即 gRPC server 开始收流量之前。
 * 本类此前用 {@code @EventListener(ApplicationReadyEvent)}，而该事件在
 * {@code SmartLifecycle} 启动 gRPC server <b>之后</b>才发布 —— 冷启动窗口内
 * admin-api 若已连通，登录会拿到 {@code 1003 用户不存在} 而不是连不上服务。
 * <p>修法：改用 {@link PostConstruct}。它在 {@code finishBeanFactoryInitialization}
 * 阶段执行，早于 {@code finishRefresh} 里的 {@code SmartLifecycle.start()}，
 * 且依赖的 {@code AdminMapper} 由 {@code @MapperScan} 注册的 bean definition 已就绪。
 *
 * <p><b>关于建表时序：本类靠 {@link DependsOn} 显式排在 {@code AdminSchemaBootstrap} 之后。</b>
 * <p>把 seeder 提前到 {@code @PostConstruct} 后引入了一个时序问题：本类跑在
 * {@code finishBeanFactoryInitialization}，而建表跑在 {@code AdminSchemaBootstrap}
 * 的 {@code @PostConstruct}。两者都是普通 {@code @Component}，
 * <b>注册顺序只是扫描顺序，不是契约</b> —— 谁的 {@code @PostConstruct} 先跑不可依赖。
 * 若建表排在本类之后，启动就会挂在
 * {@code PSQLException: relation "admin_admin" does not exist}。
 *
 * <p><b>历史（值得记下来，因为方向反过一次）</b>：原先这条约束是<b>传递性</b>的 ——
 * MyBatis-Plus 自带 {@code MybatisDependsOnDatabaseInitializationDetector}，
 * Spring Boot 的 {@code DatabaseInitializationDependencyConfigurer} 会给
 * {@code SqlSessionTemplate} 自动打上「依赖数据库初始化」标记，于是链路是
 * <pre>
 *   AdminSeederBootstrap
 *     └─ AdminMapper (MapperFactoryBean)
 *          └─ SqlSessionTemplate   ← detector 自动加标记
 *               └─ flywayInitializer 先跑 migrate()
 * </pre>
 * <b>2026-10-21 丢掉 Flyway 之后，那条链整个消失了</b> ——
 * 没有 {@code flywayInitializer}，{@code DatabaseInitializerDetector} 也就无处可施。
 * 所以现在改成<b>点名到具体 bean</b>的 {@code @DependsOn("adminSchemaBootstrap")}：
 * 约束不再经过任何自动配置推断，ORM 换了、Boot 升大版本都不会失效。
 *
 * <p>{@link DependsOnDatabaseInitialization} 予以保留：万一将来换回 Flyway 或
 * {@code spring.sql.init}，它是另一条独立的保护。
 *
 * <p><b>失败语义</b>：抛异常 → Spring 容器启动失败、进程退出，等价于 B 侧
 * {@code logx.Must(...)} 的 fatal 行为。
 *
 * <p><b>1:1 复刻 B 侧语义</b>：先尝试查默认账号；已存在 → no-op；不存在 → 加密密码 + 生成 uid + 插入；
 * 插入时若唯一冲突（多实例同时启动）→ 重读一次，存在就 OK，不存在就把原异常抛出去。
 */
@Component
@DependsOn("adminSchemaBootstrap")
@DependsOnDatabaseInitialization
public class AdminSeederBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminSeederBootstrap.class);

    private final AdminMapper adminMapper;

    /**
     * 注入 {@link Snowflake} Bean（而非只用静态 {@code Snowflake.nextId()}）是为了强制 Spring 的
     * bean 创建顺序：{@code SnowflakeAutoConfiguration#snowflake} 在创建 Bean 时调
     * {@code Snowflake.setWorkerId(configuredId)}，
     * 而 {@code @PostConstruct} 阶段的 seeder 若先跑，会用「静态兜底 workerId」生成 uid，
     * 随后该 Bean 才把 workerId 换成配置值 —— 同一进程内先后两个不同 workerId
     * 产 ID，存在跨 workerId 撞号风险。
     * 注入后 Spring 保证该 Bean 先构造完，seeder 再执行。
     *
     * <p>⚠️ 本仓 {@link Snowflake} 的<b>生成状态是全进程唯一的一份静态状态</b>
     * （共享层「M11 修复」已把两份状态收敛成一份）⇒ ⇒ 注入的<b>主要作用就是强制创建顺序</b>。
     */
    private final Snowflake snowflake;

    public AdminSeederBootstrap(AdminMapper adminMapper, Snowflake snowflake) {
        this.adminMapper = adminMapper;
        this.snowflake = snowflake;
    }

    @PostConstruct
    public void ensureDefaultAdmin() {
        // 先尝试查默认账号；已存在 → no-op
        AdminEntity existing = adminMapper.selectOne(
                new LambdaQueryWrapper<AdminEntity>().eq(AdminEntity::getUsername, AdminDomainService.DEFAULT_ADMIN_USERNAME)
        );
        if (existing != null) {
            log.info("default admin account already exists, skip bootstrap");
            return;
        }

        // 不存在 → 创建。密码来自硬编码常量，不读 system.default-password（B 出处见类注释 DRIFT-001）。
        String passwordHash = PasswordUtil.encrypt(AdminDomainService.DEFAULT_ADMIN_PASSWORD);

        long uid;
        try {
            uid = Snowflake.nextId();
        } catch (Exception e) {
            log.error("ensureDefaultAdmin failed: snowflake uid generation error", e);
            throw new IllegalStateException("snowflake uid generation failed", e);
        }

        AdminEntity admin = new AdminEntity();
        admin.setUid(uid);
        admin.setUsername(AdminDomainService.DEFAULT_ADMIN_USERNAME);
        admin.setPassword(passwordHash);

        try {
            adminMapper.insert(admin);
        } catch (Exception e) {
            if (AdminExceptions.isUniqueViolation(e)) {
                // 多实例并发启动场景：另一个实例已经创建。重读一次确认。
                try {
                    AdminEntity relookup = adminMapper.selectOne(
                            new LambdaQueryWrapper<AdminEntity>().eq(AdminEntity::getUsername, AdminDomainService.DEFAULT_ADMIN_USERNAME)
                    );
                    if (relookup != null) {
                        log.info("default admin account bootstrapped by another instance");
                        return;
                    }
                    // 重读也没找到 → 把原 unique violation 抛上去
                } catch (Exception lookupErr) {
                    log.error("ensureDefaultAdmin relookup failed", lookupErr);
                }
            }
            log.error("ensureDefaultAdmin failed: insert admin error", e);
            throw new IllegalStateException("ensureDefaultAdmin insert failed", e);
        }

        log.info("default admin account bootstrapped, uid={}", uid);
    }
}
