package com.rag.follow;

import com.rag.follow.domain.entity.BlockRelationEntity;
import com.rag.follow.domain.entity.FollowRelationEntity;
import com.rag.follow.grpc.FollowGrpcService;
import com.rag.follow.interceptor.FollowBlacklistInterceptor;
import com.rag.follow.interceptor.FollowJwtAuthInterceptor;
import com.rag.follow.mapper.BlockRelationMapper;
import com.rag.follow.mapper.FollowRelationMapper;
import com.rag.follow.support.FakeUserRpcServer;
import com.rag.follow.support.NacosStubConfiguration;
import com.rag.follow.support.TestPostgres;
import com.rag.follow.support.TestRedis;

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
 * <p><b>follow 域上下文装配自检</b> —— 证明 D1 合并后的<b>唯一入口</b>真能把全链条装配起来。
 *
 * <h2>⚠️ 为什么必须「真启动」而不是「读源码断言」</h2>
 * <p>AGENTS.md §3.3.3：<b>不得凭注解名称断定机制已启用</b>。
 * 本类断言的每一条都是<b>运行期事实</b>（bean 真的在容器里 / 表真的建出来了 / 上下文真的起来了）
 * ⇒ 而非静态相似度。
 *
 * <h2>🔴 本类同时是<b>两条已修漂移的守门</b>（它们都会让上下文<b>起不来</b>）</h2>
 * <table border="1">
 *   <caption>两条漂移 ⇒ 同一类症状 ⇒ 同一道守门</caption>
 *   <tr><th>漂移</th><th>症状（上下文启动期）</th></tr>
 *   <tr>
 *     <td><b>DRIFT-FOLLOW-A</b>（{@code user-auth} 落在 {@code follow:} 前缀）</td>
 *     <td>{@code FollowApiProperties} 的 fail-closed {@code @PostConstruct} 抛
 *         {@code IllegalStateException}（access-secret 绑不上）</td>
 *   </tr>
 *   <tr>
 *     <td><b>DRIFT-FOLLOW-D</b>（{@code flyway:} 段落在顶层）</td>
 *     <td>{@code FlywayInitializer} 抛
 *         {@code Found non-empty schema(s) "public" but no schema history table}</td>
 *   </tr>
 * </table>
 * ⇒ ⇒ <b>本类的第一条断言（上下文能起来）就是这两条漂移的守门</b>
 * ⇒ ⇒ 反过来说：这两条漂移<b>任一复发 ⇒ 本类立刻变红</b>（而不是等到线上启动失败）。
 *
 * <h2>本类守的其余各条（每条都对应一个「B 侧有、C 侧可能悄悄没有」的点）</h2>
 * <ol>
 *   <li><b>{@code @MapperScan} 生效</b>：{@code FollowRelationMapper} / {@code BlockRelationMapper}
 *       两个 bean 都在容器里 ⇒ <b>用 bean 存在来证明</b>，不是用「源码里有这行注解」来证明。</li>
 *   <li><b>gRPC service 被 starter 扫到</b>：{@code @GrpcService} ⇒ devh 的
 *       {@code GrpcServiceDiscoverer} 能发现它 ⇒ 真 in-process gRPC server 起得来
 *       （本类不起 TCP 端口 ⇒ {@code grpc.server.port=-1}）。</li>
 *   <li><b>两个拦截器都在</b>：JWT 验签 + Redis 黑名单 ⇒ 少一个 ⇒ 鉴权链断一半。</li>
 *   <li><b>DDL 在启动期真被重放</b>：查真 PG ⇒ {@code follow_relation} / {@code block_relation}
 *       表存在 ⇒ 证明 {@code V1/V2} 真跑了（不是「文件在那儿」）。</li>
 *   <li><b>实体映射的表名是 B 侧那两个</b>（L1 可见面）。</li>
 * </ol>
 */
@SpringBootTest(classes = {FollowServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                // Nacos 整体不生效 ⇒ 自动配置不注册 NacosServiceManager ⇒ 由 NacosStubConfiguration 补上
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // gRPC **不起 TCP 端口** ⇒ in-process server ⇒ 不撞端口（与其它测试上下文并存）
                "grpc.server.port=-1"
        })
@ActiveProfiles("test")
class FollowServiceApplicationTests {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
        // 出向 user-rpc 客户端指向「假 user-rpc」真 gRPC server（随机端口）
        registry.add("grpc.client.user-rpc.address", FakeUserRpcServer::address);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private FollowRelationMapper followRelationMapper;

    @Autowired
    private BlockRelationMapper blockRelationMapper;

    @Autowired
    private FollowGrpcService followGrpcService;

    @Autowired
    private FollowJwtAuthInterceptor jwtAuthInterceptor;

    @Autowired
    private FollowBlacklistInterceptor blacklistInterceptor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("🔴 上下文能起来（合并单进程 · 共享层 auto-config 全部装配到位）—— 漂移 A/D 的守门")
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBean(FollowServiceApplication.class)).isNotNull();
    }

    @Test
    @DisplayName("🔴 @MapperScan 真生效：两个 Mapper bean 都在容器里")
    void mapperBeansAreRegistered() {
        assertThat(followRelationMapper).isNotNull();
        assertThat(blockRelationMapper).isNotNull();
        assertThat(context.getBeanNamesForType(FollowRelationMapper.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(BlockRelationMapper.class)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 @GrpcService 真被 starter 扫到：FollowGrpcService bean 在容器里")
    void grpcServiceBeanExists() {
        assertThat(followGrpcService).isNotNull();
        assertThat(context.getBeanNamesForType(FollowGrpcService.class)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 两个拦截器都在：JWT 验签 + Redis 黑名单（少一个 ⇒ 鉴权链断一半）")
    void bothInterceptorsExist() {
        assertThat(jwtAuthInterceptor).isNotNull();
        assertThat(blacklistInterceptor).isNotNull();
        assertThat(context.getBeanNamesForType(FollowJwtAuthInterceptor.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(FollowBlacklistInterceptor.class)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 DDL 在启动期真被重放：follow_relation / block_relation 两张表都存在")
    void schemaWasAppliedAtStartup() {
        Integer followTable = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'follow_relation'
                """, Integer.class);
        assertThat(followTable)
                .as("follow_relation 必须存在（不是「V1 文件在那儿」）")
                .isEqualTo(1);

        Integer blockTable = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'block_relation'
                """, Integer.class);
        assertThat(blockTable).isEqualTo(1);
    }

    @Test
    @DisplayName("实体映射的表名是 B 侧的 follow_relation / block_relation（L1 可见面）")
    void tableNamesMatchB() {
        assertThat(FollowRelationEntity.class
                .getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class).value())
                .isEqualTo("follow_relation");
        assertThat(BlockRelationEntity.class
                .getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class).value())
                .isEqualTo("block_relation");
    }
}
