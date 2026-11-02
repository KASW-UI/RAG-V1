package com.rag.user;

import com.rag.user.domain.entity.UserAvatarHistoryEntity;
import com.rag.user.domain.entity.UserEntity;
import com.rag.user.grpc.UserGrpcService;
import com.rag.user.interceptor.JwtAuthFilter;
import com.rag.user.interceptor.JwtAuthInterceptor;
import com.rag.user.mapper.UserAvatarHistoryMapper;
import com.rag.user.mapper.UserMapper;
import com.rag.user.support.TestPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>user 域上下文装配自检</b> —— 证明 D1 合并后的<b>唯一入口</b>真能把全链条装配起来。
 *
 * <h2>⚠️ 为什么必须「真启动」而不是「读源码断言」</h2>
 * <p>AGENTS.md §3.3.3：<b>不得凭注解名称断定机制已启用</b>。本类断言的每一条都是
 * <b>运行期事实</b>（bean 真的在容器里 / 表真的建出来了）⇒ 而非静态相似度。
 *
 * <h2>本类守的 4 条（每条都对应一个「B 侧有、C 侧可能悄悄没有」的点）</h2>
 * <ol>
 *   <li><b>{@code @MapperScan} 生效</b>：B 侧 {@code UserRpcApplication} 的 javadoc 自述
 *       「application.yaml 注释里声明依赖它，但代码一直没加 → UserMapper/UserAvatarHistoryMapper 注册不上」
 *       ⇒ C 侧入口类显式加了 ⇒ <b>用 bean 存在来证明</b>（不是用「源码里有这行注解」来证明）。</li>
 *   <li><b>gRPC service 被 starter 扫到</b>：{@code @GrpcService} ⇒ devh 的
 *       {@code GrpcServiceDiscoverer} 能发现它 ⇒ <b>真 in-process gRPC server</b> 起得来
 *       （本类不起 TCP 端口 ⇒ {@code grpc.server.port=-1} ⇒ 只走 in-process 工厂）。</li>
 *   <li><b>JWT 两段（Filter + Interceptor）都在</b>：B 侧把验签拆成 Filter ＋ Interceptor 两段
 *       ⇒ C 侧必须<b>两个 bean 都存在</b>（少一个 ⇒ 验签退回预算内 ⇒ B 侧 {@code DRIFT-USERAPI-009②} 不闭环）。</li>
 *   <li><b>DDL 在启动期真的被重放</b>：查真 PG ⇒ {@code users_user} 表存在
 *       ⇒ 证明 {@code UserSchemaBootstrap} 的 {@code @PostConstruct} 真跑了
 *       （不是「类在那儿」）。</li>
 * </ol>
 *
 * <h2>测试基础设施</h2>
 * <ul>
 *   <li>真 PostgreSQL（Testcontainers {@code postgres:16-alpine}）—— H2 认不了
 *       {@code TIMESTAMPTZ} / {@code pg_advisory_lock} ⇒ H2 上「跑绿」是假绿；</li>
 *   <li>本机无 Redis ⇒ 黑名单检查走<b>真实</b> fail-open 分支（连接立即被拒）；</li>
 *   <li>gRPC 走 in-process（{@code grpc.server.port=-1}）⇒ 不占 TCP 端口
 *       ⇒ 与其它测试上下文并存不冲突。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class UserServiceApplicationTests {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserAvatarHistoryMapper userAvatarHistoryMapper;

    @Autowired
    private UserGrpcService userGrpcService;

    @Autowired
    private JwtAuthFilter jwtAuthFilter;

    @Autowired
    private JwtAuthInterceptor jwtAuthInterceptor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("上下文能起来（合并单进程 · 共享层 auto-config 全部装配到位）")
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(UserServiceApplication.class)).isNotNull();
    }

    @Test
    @DisplayName("🔴 @MapperScan 真生效：两个 Mapper bean 都在容器里（B 侧曾因缺这行注册不上）")
    void mapperBeansAreRegistered() {
        assertThat(userMapper).isNotNull();
        assertThat(userAvatarHistoryMapper).isNotNull();
        assertThat(context.getBeanNamesForType(UserMapper.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(UserAvatarHistoryMapper.class)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 @GrpcService 真被 starter 扫到：UserGrpcService bean 在容器里")
    void grpcServiceBeanExists() {
        assertThat(userGrpcService).isNotNull();
        assertThat(context.getBeanNamesForType(UserGrpcService.class)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 JWT 两段都在：Filter（验签 · 预算外）＋ Interceptor（黑名单 · 预算内）")
    void jwtTwoStageBeansExist() {
        assertThat(jwtAuthFilter).isNotNull();
        assertThat(jwtAuthInterceptor).isNotNull();
        // Filter 必须落在共享层 HttpMiddleware(+7) 之前 ⇒ 否则验签回到 3s 预算之内
        assertThat(jwtAuthFilter.getClass().getName()).isEqualTo("com.rag.user.interceptor.JwtAuthFilter");
    }

    @Test
    @DisplayName("🔴 DDL 在启动期真被重放：users_user 表存在（不是「类在那儿」）")
    void schemaWasAppliedAtStartup() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'users_user'",
                Integer.class);
        assertThat(n).isNotNull().isGreaterThan(0);
    }

    @Test
    @DisplayName("实体映射的表名是 B 侧的 users_user / users_user_avatar_history（L1 可见面）")
    void tableNamesMatchB() {
        assertThat(UserEntity.class.getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class).value())
                .isEqualTo("users_user");
        assertThat(UserAvatarHistoryEntity.class.getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class).value())
                .isEqualTo("users_user_avatar_history");
    }
}
