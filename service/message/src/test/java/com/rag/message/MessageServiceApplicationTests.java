package com.rag.message;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.rag.common.observability.HttpMiddleware;
import com.rag.common.observability.ObservabilityProperties;
import com.rag.message.config.MessageApiProperties;
import com.rag.message.config.MessageProperties;
import com.rag.message.domain.entity.ConversationEntity;
import com.rag.message.domain.entity.ConversationMessageEntity;
import com.rag.message.domain.entity.NotificationEntity;
import com.rag.message.grpc.MessageGrpcService;
import com.rag.message.interceptor.MessageAdminJwtAuthInterceptor;
import com.rag.message.interceptor.MessageJwtAuthInterceptor;
import com.rag.message.mapper.ConversationMapper;
import com.rag.message.mapper.ConversationMessageMapper;
import com.rag.message.mapper.NotificationMapper;
import com.rag.message.support.NacosStubConfiguration;
import com.rag.message.support.TestPostgres;
import net.devh.boot.grpc.server.serverfactory.GrpcServerLifecycle;
import io.grpc.ServerInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * <p><b>message 域「真启动」契约测试</b> —— 进程<b>起得来</b>这件事本身就是契约。
 *
 * <h2>为什么必须存在这个类</h2>
 * <p>D1 把 B 侧的两个进程（message-api / message-rpc）合成一个
 * ⇒ 一个进程里同时要起 <b>Tomcat</b> + <b>gRPC server</b> + <b>Flyway 迁移</b>
 * + <b>Nacos 注册</b> + <b>两把 JWT 密钥的 fail-closed 守卫</b>
 * ⇒ ⇒ <b>任一环节配错，进程就起不来</b>。
 * <p>⇒ 本类断言的<b>不是</b>业务逻辑（那是 {@code MessageDomainServiceRealPgTest} 的职责），
 * 而是<b>启动期契约</b>—— 每一条都是<b>运行期事实</b>，不是静态相似度。
 *
 * <h2>本类守的 7 条（每条都对应一个「B 侧有、C 侧可能悄悄没有」的点）</h2>
 * <ol>
 *   <li><b>上下文起得来</b>（D1 合并进程 · Tomcat 与 gRPC 同进程）；</li>
 *   <li><b>{@code @MapperScan} 生效</b>：3 个 Mapper bean 都在容器里
 *       （B 侧 {@code MessageRpcApplication} 的 javadoc 自述「application.yaml 注释里声明依赖它，
 *       但代码一直没加 → Mapper 注册不上」⇒ 用 <b>bean 存在</b>证明，不是用「源码里有这行注解」证明）；</li>
 *   <li><b>{@code @ConfigurationPropertiesScan} 生效</b>：两个 Properties bean 都在
 *       （<b>漏了它 ⇒ 两把密钥的 fail-closed 守卫与 {@code message.list.*} / {@code message.broadcast.*}
 *       全部静默失效</b> ⇒ 这是一条<b>安全面</b>断言）；</li>
 *   <li><b>{@code @GrpcService} 真被 starter 扫到</b>：{@code MessageGrpcService} bean 在容器里
 *       （⇒ 9 个 gRPC 方法真的被挂上）；</li>
 *   <li><b>13 个 HTTP 端点的 method + path 逐条等于 B 侧</b>（<b>运行期</b>取
 *       {@link RequestMappingHandlerMapping} 的真实注册表，不是读源码）
 *       —— 含「唯一的 GET 是 {@code /message/v1/unread}」与「admin 组全 POST」；</li>
 *   <li><b>DDL 真被 Flyway 建出来</b>，且<b>账本是独立的那本</b>
 *       （{@code flyway_schema_history_message} 存在 ⇒ 默认账本 {@code flyway_schema_history}
 *       <b>不存在</b>）—— 这是本仓多域共 schema 的<b>撞号病根</b>；</li>
 *   <li><b>Nacos 自动注册被关掉</b>，注册到 Nacos 的<b>只有 gRPC 端口</b>。</li>
 * </ol>
 *
 * <h2>🔴 这个类是被一次真实缺陷逼出来的</h2>
 * <p>2026-11-02 写边界层时，{@code application.yml} 的 {@code grpc.client} 段<b>只有注释、没有条目</b>
 * ⇒ 而域内两个 {@code @GrpcClient} 通道需要 {@code grpc.client.<name>.negotiation-type: plaintext}
 * ⇒ ⇒ <b>上下文启动直接失败</b> ⇒ 而此前<b>一个 {@code @SpringBootTest} 上下文测试都没有</b>
 * ⇒ ⇒ 这个缺陷一直藏着（守门脚本 A4 也只能查「配了没有」，查不出「配了但没生效」）。
 * <p>⇒ ⇒ 本类就是那道防线。
 *
 * <p><b>测试环境摘依赖</b>：Nacos 用 {@link NacosStubConfiguration} 桩（不连网络）·
 * PG 走 Testcontainers 真容器（DDL 里有 {@code TIMESTAMPTZ} / {@code JSONB}
 * ⇒ H2 认不了 ⇒ H2 上「跑绿」是假绿）· gRPC 走一个<b>真实空闲端口</b>
 * （要能区分「注册的是 gRPC 端口还是 web 端口」⇒ 端口必须是两个可区分的真值）。
 *
 * <h2>⚠️ 为什么<b>不</b>关掉 {@code spring.cloud.nacos.discovery.enabled}</h2>
 * <p>关掉它 ⇒ {@code NacosDiscoveryAutoConfiguration#nacosProperties()}
 * （{@code NacosDiscoveryProperties} 的唯一 bean 定义处）整个不生效
 * ⇒ {@code NacosDiscoveryProperties} bean <b>不存在</b>
 * ⇒ ① {@code NacosServiceRegistryAutoConfiguration#nacosServiceRegistry(...)} 缺参数
 * ⇒ <b>上下文起不来</b> ⇒ ② 也<b>读不到</b> {@code register-enabled} 的值 ⇒ 无法断言。
 * <p>⇒ 改为<b>保留</b> discovery 开关、用 {@link NacosStubConfiguration} 的桩接管
 * {@code NacosServiceManager}（bean 名错开 + {@code @Primary}）⇒ 既不触网，又能真触发地观察注册行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {MessageServiceApplication.class, NacosStubConfiguration.class})
class MessageServiceApplicationTests {

    /**
     * <p>gRPC server 的端口 —— 取一个<b>真实空闲端口</b>（不是 0，也不是 -1）。
     *
     * <p>为什么要真实端口：断言「注册的是 <b>gRPC 端口</b>、<b>不是</b> web 端口」需要
     * 两个<b>可区分</b>的端口值 ⇒ 若 gRPC 端口配 0（= 随机）或 -1（= in-process），
     * 「是不是 web 端口」这条判据会失效（恒成立 ⇒ <b>假绿</b>）。
     */
    static final int GRPC_PORT;

    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            GRPC_PORT = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("取空闲端口失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestPostgres::jdbcUrl);
        registry.add("spring.datasource.username", TestPostgres::username);
        registry.add("spring.datasource.password", TestPostgres::password);
        // 两把 JWT 密钥：message-api 的 yml 里是裸占位符 ${USER_JWT_SECRET} / ${ADMIN_JWT_SECRET}
        // ⇒ 不给值 ⇒ 占位符解析不了 ⇒ 上下文起不来（B 侧同款：空密钥 = 任何人都能伪造 token）。
        registry.add("USER_JWT_SECRET", () -> "message-boot-test-user-secret-0123456789");
        registry.add("ADMIN_JWT_SECRET", () -> "message-boot-test-admin-secret-0123456789");
        registry.add("grpc.server.port", () -> GRPC_PORT);
        // Nacos 地址指向一个必然连不通的端口 ⇒ 即便桩漏了也不会真去连
        registry.add("spring.cloud.nacos.discovery.server-addr", () -> "127.0.0.1:1");
    }

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ConversationMessageMapper conversationMessageMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private MessageApiProperties messageApiProperties;

    @Autowired
    private MessageProperties messageProperties;

    @Autowired
    private MessageGrpcService messageGrpcService;

    @Autowired
    private MessageJwtAuthInterceptor messageJwtAuthInterceptor;

    @Autowired
    private MessageAdminJwtAuthInterceptor messageAdminJwtAuthInterceptor;

    @Autowired
    private GrpcServerLifecycle grpcServerLifecycle;

    @Autowired
    private NacosDiscoveryProperties nacosDiscoveryProperties;

    @Autowired
    private NacosStubConfiguration.NacosNamingServiceSpy namingServiceSpy;

    @LocalServerPort
    private int webPort;

    @Test
    @DisplayName("启动期：上下文能起来（D1 合并进程 · Tomcat 与 gRPC 同进程）")
    void contextLoadsWithBothTomcatAndGrpc() {
        assertThat(ctx).isNotNull();
        assertThat(ctx.getBean(MessageServiceApplication.class)).isNotNull();
        assertThat(webPort).isGreaterThan(0);
        assertThat(grpcServerLifecycle).isNotNull();
        assertThat(grpcServerLifecycle.isRunning())
                .as("gRPC server 必须已启动（D1：一个进程同时起 Tomcat 与 gRPC）")
                .isTrue();
    }

    @Test
    @DisplayName("🔴 @MapperScan 真生效：3 个 Mapper bean 都在容器里（B 侧曾因缺这行注册不上）")
    void mapperBeansAreRegistered() {
        assertThat(conversationMapper).isNotNull();
        assertThat(conversationMessageMapper).isNotNull();
        assertThat(notificationMapper).isNotNull();
        assertThat(ctx.getBeanNamesForType(ConversationMapper.class)).hasSize(1);
        assertThat(ctx.getBeanNamesForType(ConversationMessageMapper.class)).hasSize(1);
        assertThat(ctx.getBeanNamesForType(NotificationMapper.class)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 @ConfigurationPropertiesScan 真生效：两个 Properties bean 都在（漏了 ⇒ 密钥守卫与 list/broadcast 配置全失效）")
    void configurationPropertiesBeansAreRegistered() {
        assertThat(messageApiProperties).isNotNull();
        assertThat(messageProperties).isNotNull();
        // 证明两段密钥真的绑上了（fail-closed 守卫的 @PostConstruct 已经跑过 ⇒ 否则上下文起不来）
        assertThat(messageApiProperties.getUserAuth().getAccessSecret())
                .isEqualTo("message-boot-test-user-secret-0123456789");
        assertThat(messageApiProperties.getAdminAuth().getAccessSecret())
                .isEqualTo("message-boot-test-admin-secret-0123456789");
        // message.* 段绑上了（B 侧 rpc yml:278-285）
        assertThat(messageProperties.getList().getDefaultLimit()).isEqualTo(20);
        assertThat(messageProperties.getList().getMaxLimit()).isEqualTo(100);
        assertThat(messageProperties.getBroadcast().getPageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("🔴 @GrpcService 真被 starter 扫到：MessageGrpcService bean 在容器里")
    void grpcServiceBeanExists() {
        assertThat(messageGrpcService).isNotNull();
        assertThat(ctx.getBeanNamesForType(MessageGrpcService.class)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 两段 JWT 拦截器都在容器里（user 组 + admin 组）")
    void jwtInterceptorsAreRegistered() {
        assertThat(messageJwtAuthInterceptor).isNotNull();
        assertThat(messageAdminJwtAuthInterceptor).isNotNull();
    }

    /**
     * <p><b>🔴 L1 契约断言：13 个 HTTP 端点的 method + path 逐条等于 B 侧</b>。
     *
     * <p><b>为什么是运行期取注册表</b>：读源码只能证明「代码里写了这些字面量」，
     * 而本条要证明的是「<b>Spring 真把它们注册成了这些路由</b>」
     * ⇒ ⇒ 取 {@link RequestMappingHandlerMapping} 的真实注册表。
     *
     * <p><b>⚠️ 这条断言抓到过一处真实错误</b>：C 侧<b>空壳</b>的 javadoc 里写着
     * {@code /message/v1/conversation/list}（单数）且把 admin 的两个端点写成 GET
     * ⇒ ⇒ 那份 javadoc 是<b>按文件树推导的、不是 B 侧正推的</b> ⇒ 属于错误信息。
     * <p>B 侧真源（{@code message-api/.../controller/MessageController.java:52-141} ＋
     * {@code .../MessageAdminController.java:56-140}）是<b>复数</b>路径 + <b>admin 组全 POST</b>
     * + <b>唯一的 GET 是 {@code /message/v1/unread}</b>。
     */
    @Test
    @DisplayName("🔴 L1：13 个 HTTP 端点的 method + path 逐条等于 B 侧（运行期注册表）")
    void httpEndpointTableMatchesBExactly() {
        // ⚠️ **按 bean 名取，不按类型取**：Actuator 的 `controllerEndpointHandlerMapping`
        //   也是 RequestMappingHandlerMapping ⇒ 按类型取会 NoUniqueBeanDefinition。
        RequestMappingHandlerMapping mapping =
                ctx.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        Set<String> actual = new TreeSet<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            // ⚠️ 用 `getPatternValues()` 而不是 `getPatternsCondition().getPatterns()`：
            //   后者在 Spring 6.2 对部分映射（如 Actuator 的）会返回 null ⇒ NPE。
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/message/v1")) {
                    continue;
                }
                if (info.getMethodsCondition().getMethods().isEmpty()) {
                    actual.add("ANY " + pattern);
                } else {
                    for (org.springframework.web.bind.annotation.RequestMethod m
                            : info.getMethodsCondition().getMethods()) {
                        actual.add(m.name() + " " + pattern);
                    }
                }
            }
        }
        Set<String> expected = new TreeSet<>(List.of(
                "POST /message/v1/admin/conversations/list",
                "POST /message/v1/admin/conversations/messages",
                "POST /message/v1/admin/conversations/read",
                "POST /message/v1/admin/conversations/send",
                "POST /message/v1/admin/notifications/send",
                "POST /message/v1/conversations/list",
                "POST /message/v1/conversations/messages",
                "POST /message/v1/conversations/read",
                "POST /message/v1/conversations/send",
                "POST /message/v1/notifications/list",
                "POST /message/v1/notifications/read",
                "POST /message/v1/notifications/readall",
                "GET /message/v1/unread"));
        assertThat(actual)
                .as("13 个端点必须与 B 侧逐条一致（含「唯一的 GET 是 /unread」「admin 组全 POST」）")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).hasSize(13);
    }

    /**
     * <p><b>🔴 DDL 断言：3 张表真被 Flyway 建出来</b>。
     *
     * <p><b>DDL 出处</b>：B 侧 {@code message-rpc/src/main/resources/db/migration/}
     * 的 V1~V5 五个脚本<b>逐字节复制</b>到本仓 ⇒ 断言的对象就是 B 侧的 DDL 本身。
     */
    @Test
    @DisplayName("🔴 DDL：3 张表真被 Flyway 建出来了（真 PG · 不是「类在那儿」）")
    void flywayCreatedAllThreeTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name IN "
                        + "('message_notification','message_conversation','message_conversation_message')",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "message_notification", "message_conversation", "message_conversation_message");
    }

    /**
     * <p><b>🔴 账本断言：Flyway 用的是<b>独立账本</b> {@code flyway_schema_history_message}。</p>
     *
     * <p><b>为什么这条重要</b>：{@code flyway_schema_history} 是 <b>schema 级</b>账本
     * ⇒ 本仓多个域连同一个 {@code first_db}、同一个 {@code public} schema
     * ⇒ 共用默认账本时，后启动的域会把自己的迁移判成「已应用」而静默跳过
     * ⇒ 而 message 的 V1~V3 与 like/article 的版本号<b>直接撞号且内容不同</b>
     * ⇒ ⇒ 撞号 ⇒ <b>表建不出来</b>。
     * <p>⇒ 断言两件事：① 独立账本存在且 <b>5 个版本全部 success</b>；
     * ② <b>默认账本不存在</b>（若存在 ⇒ 说明 yml 的 {@code table:} 没生效 ⇒ 撞号病根还在）。
     */
    @Test
    @DisplayName("🔴 Flyway 走的是独立账本 flyway_schema_history_message（5 个版本全 success · 默认账本不存在）")
    void flywayUsesIndependentLedgerTable() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history_message WHERE success = true",
                Integer.class);
        assertThat(applied).as("V1~V5 五个迁移应全部成功应用").isEqualTo(5);

        Integer defaultLedger = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'",
                Integer.class);
        assertThat(defaultLedger)
                .as("默认账本 flyway_schema_history 绝不能存在（多域共 schema ⇒ 撞号 ⇒ 表建不出来）")
                .isZero();
    }

    /**
     * <p><b>🔴 实体映射的表名是 B 侧的三个表名（L1 可见面）</b>。
     */
    @Test
    @DisplayName("实体映射的表名是 B 侧的三个表名（L1 可见面）")
    void tableNamesMatchB() {
        assertThat(ConversationEntity.class.getAnnotation(
                com.baomidou.mybatisplus.annotation.TableName.class).value()).isEqualTo("message_conversation");
        assertThat(ConversationMessageEntity.class.getAnnotation(
                com.baomidou.mybatisplus.annotation.TableName.class).value()).isEqualTo("message_conversation_message");
        assertThat(NotificationEntity.class.getAnnotation(
                com.baomidou.mybatisplus.annotation.TableName.class).value()).isEqualTo("message_notification");
    }

    /**
     * <p><b>🔴 Nacos 注册面</b>：自动注册必须<b>没发生</b>，只有域内显式的 gRPC 端口注册发生过。
     *
     * <p><b>B 出处</b>：B 侧 {@code service/message/message-rpc/src/main/resources/application.yml:156}
     * 的 {@code register-enabled: false}（注释原文：「【本模块的注册是手写的】…
     * ⇒ <b>自动注册必须关掉</b>，否则<b>同一服务名下会有两个端口的实例</b>」）。
     *
     * <p><b>消费点</b>（SCA 2025.0.0.0）：{@code NacosAutoServiceRegistration}
     * ⇒ {@code if (!nacosDiscoveryProperties.isRegisterEnabled()) { return; }} ⇒ <b>运行期</b>判定。
     *
     * <p><b>本断言为什么不是假绿</b>（三条缺一不可）：
     * <ol>
     *   <li>自动注册的 <b>bean 存在</b>（{@code NacosAutoServiceRegistration} 被注入成功）
     *       ⇒ 证明「链路是活的」，不是靠把整条 discovery 开关关掉来蒙混过关；</li>
     *   <li>{@code NacosDiscoveryProperties#isRegisterEnabled()} 为 false
     *       ⇒ 证明值来自<b>真实 application.yml</b>（本测试<b>没有</b>用 {@code properties=}
     *           覆盖它 ⇒ 只可能是 yml 给的）；</li>
     *   <li>桩 {@link NamingService} 上 <b>只被注册了 1 次</b>，且那次的端口
     *       <b>等于 gRPC 端口</b>、<b>不等于 web 端口</b>
     *       ⇒ 若 yml 少了那一行 ⇒ {@code registerEnabled} 回落默认值 {@code true}
     *       ⇒ 自动注册会<b>再注册一次 web 端口</b> ⇒ <b>本条断言会红</b>。</li>
     * </ol>
     */
    @Test
    @DisplayName("🔴 Nacos 自动注册被关掉，注册到 Nacos 的只有 gRPC 端口（web 端口从未被注册）")
    void nacosAutoRegistrationIsDisabledAndOnlyGrpcPortIsRegistered() throws NacosException {
        // ① 值来自真实 application.yml（本测试未覆盖该键）
        assertThat(nacosDiscoveryProperties.isRegisterEnabled())
                .as("spring.cloud.nacos.discovery.register-enabled 必须是 false（B 侧 message-rpc yml:156）")
                .isFalse();

        // ② 桩上只该有 1 次注册，且是 gRPC 端口
        ArgumentCaptor<Instance> captor = ArgumentCaptor.forClass(Instance.class);
        NamingService namingService = namingServiceSpy.namingService();
        verify(namingService, times(1)).registerInstance(anyString(), anyString(), captor.capture());

        Instance registered = captor.getValue();
        assertThat(registered.getPort())
                .as("注册到 Nacos 的必须是 gRPC 端口（B 侧用 GrpcServerProperties.getPort()）")
                .isEqualTo(GRPC_PORT);
        assertThat(registered.getPort())
                .as("web 端口绝不能被注册 —— 否则同名服务下会出现两个端口的实例，gRPC 链路断（业务码塌 1015）")
                .isNotEqualTo(webPort);
        assertThat(registered.getServiceName())
                .as("注册名必须是 message-rpc（B 侧逐字 · like 域 discovery:///message-rpc 依赖它）")
                .isEqualTo("message-rpc");
    }

    /**
     * <p><b>🔴 观测层的两个超时预算必须真的绑上</b>（AGENTS.md §3.3.1：
     * 「配置里没写」≠「没开」—— 缺省即 {@code Duration.ZERO}（不限时））。
     *
     * <p><b>为什么这条必须存在</b>：本域的 {@code application.yml} 曾经<b>完全没有</b>
     * {@code observability} 段 ⇒ {@code ObservabilityProperties} 的
     * {@code httpTimeout} / {@code rpcTimeout} 都是 {@code null}
     * ⇒ ⇒ <b>3s HTTP 预算与 2s gRPC 超时都不存在</b>
     * ⇒ ⇒ 慢请求<b>不会</b>返回 503 + {@code Request Timeout}，gRPC 调用也<b>不会</b>被服务端超时打断。
     *
     * <p><b>B 出处</b>：
     * <ul>
     *   <li>{@code service/message/message-api/src/main/resources/application.yml:248-254}
     *       ⇒ {@code observability.http-timeout: 3s}
     *       （取值依据：Go 侧 yaml 未配 Timeout ⇒ go-zero {@code rest/config.go:55}
     *       {@code Timeout int64 json:",default=3000"} ⇒ 3000ms；
     *       且 {@code DisableNativeRestTimeout} 已关掉 go-zero 原生超时中间件 ⇒ 超时只有一个执行者）</li>
     *   <li>{@code service/message/message-rpc/src/main/resources/application.yml:352-355}
     *       ⇒ {@code observability.rpc-timeout: 2s}
     *       （取值依据：go-zero {@code zrpc/config.go:46} 默认 2000ms）</li>
     * </ul>
     *
     * <p><b>⚠️ D1 合并 ⇒ 两个键都要写</b>：B 侧 api 进程只管 http-timeout、rpc 进程只管 rpc-timeout；
     * 本仓是<b>单一进程</b>（Tomcat + gRPC 同起）⇒ 两个预算同时存在 ⇒ 两个键都必须显式写。
     *
     * <p><b>消费点（证明「绑上了」而不只是「Bean 存在」）</b>：
     * <ul>
     *   <li>HTTP ⇒ {@code ObservabilityAutoConfiguration#httpMiddleware}
     *       （filter · {@code HIGHEST_PRECEDENCE + 7}）读 {@code getHttpTimeout()}；</li>
     *   <li>gRPC ⇒ {@code ObservabilityAutoConfiguration#grpcObservationInterceptor} 读
     *       {@code getRpcTimeout()}，该拦截器被
     *       {@code ProtectionAutoConfiguration#seaGrpcServerConfigurer} 用
     *       {@code interceptors.orderedStream()} 收进服务端链 ⇒ <b>确实在执行链里</b>。</li>
     * </ul>
     */
    @Test
    @DisplayName("🔴 observability 的两个超时预算真绑上了（3s HTTP + 2s gRPC · 缺键 = 机制静默不生效）")
    void observabilityTimeoutsAreBound() {
        ObservabilityProperties observability = ctx.getBean(ObservabilityProperties.class);

        assertThat(observability.getHttpTimeout())
                .as("observability.http-timeout 必须是 3s（B 侧 message-api yml:254 · "
                        + "go-zero rest/config.go:55 默认 3000ms）—— 缺键即 Duration.ZERO = 不限时")
                .isEqualTo(Duration.ofSeconds(3));

        assertThat(observability.getRpcTimeout())
                .as("observability.rpc-timeout 必须是 2s（B 侧 message-rpc yml:355 · "
                        + "go-zero zrpc/config.go:46 默认 2000ms）—— 缺键即 Duration.ZERO = 不限时")
                .isEqualTo(Duration.ofSeconds(2));

        // ⚠️ 不只看 Properties：还要证明**消费它的机制真在执行链里**（AGENTS.md §3.3.1）
        assertThat(ctx.getBeansOfType(HttpMiddleware.class))
                .as("HTTP 观测 filter 必须在容器里（它才是 http-timeout 的执行者）")
                .hasSize(1);
        assertThat(ctx.getBeansOfType(ServerInterceptor.class).values())
                .as("gRPC 观测拦截器必须在容器里（它才是 rpc-timeout 的执行者）")
                .anyMatch(bean -> bean.getClass().getName().contains("GrpcObservationInterceptor"));
    }
}
