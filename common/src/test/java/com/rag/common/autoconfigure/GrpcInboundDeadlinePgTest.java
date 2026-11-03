package com.rag.common.autoconfigure;

import com.rag.common.observability.ObservabilityProperties;
import com.rag.common.persistence.DeadlineQueryTimeoutInjector;
import com.rag.common.persistence.SqlTimeoutStatusTranslator;
import com.rag.common.persistence.StatusAwareSqlSessionFactory;
import com.zaxxer.hikari.HikariDataSource;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.micrometer.observation.ObservationRegistry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>PARITY-006 的端到端验收</b>（DRIFT-COMMON-V17）：
 * <b>gRPC 服务端路径上，客户端取消了 / 服务端预算到点了 ⇒ 那条在途写<b>不许落库</b>。</b>
 *
 * <h2>修前 / 修后的差别（本用例要证的就是这一格）</h2>
 * <table border="1">
 *   <tr><th></th><th>修前</th><th>修后</th></tr>
 *   <tr><td>handler 线程上的入向 deadline</td><td>恒空</td><td>绑上了（≈ 2s 绝对预算）</td></tr>
 *   <tr><td>每条 SQL 的 {@code setQueryTimeout}</td><td>不注入</td><td>注入剩余毫秒</td></tr>
 *   <tr><td>预算到点时 PG</td><td>什么都不做（SQL 继续跑）</td><td>收到 <b>CancelRequest</b> ⇒ {@code 57014}</td></tr>
 *   <tr><td><b>那条 INSERT 的最终行数</b></td><td>🔴 <b>1 行（副作用已落库）</b></td><td>✅ <b>0 行（回滚）</b></td></tr>
 * </table>
 *
 * <h2>🔴 为什么判据必须落在「DB 的最终行数」上</h2>
 * <p>「客户端收到了 DEADLINE_EXCEEDED」<b>修前修后都一样</b>（服务端 2s 关 call ⇒ 客户端照样超时）
 * ⇒ ⇒ 只断言客户端侧 ⇒ ⇒ <b>测不出 PARITY-006</b>（那正是当初这条差异能溜过去的原因）。
 * ⇒ ⇒ 本用例断言的是<b>服务端那一侧有没有回滚</b> ⇒ ⇒ 修前 = 1 行 ⇒ <b>必红</b>。
 *
 * <h2>🔴 为什么断言顺序是「行数 → 异常 → 耗时」</h2>
 * <p>断言<b>逐条短路</b>（第一条红了就不看后面的）⇒ ⇒ 判据必须<b>按「最能定位差异」的顺序排</b>。
 * 本用例把<b>行数放第一条</b> ⇒ ⇒ 变异（拿掉绑定）时<b>红灯精确落在核心判据上</b>
 * ⇒ ⇒ 而不是被「客户端拿到什么异常」这种<b>修前修后都一样</b>的前置断言提前吃掉
 * ⇒ ⇒ 否则「变异确实会红」这件事就<b>证明不了是哪个机制在起作用</b>。
 *
 * <h2>🔴 为什么必须真 PG</h2>
 * <p>要验的三件事全部长在 PG 上：① {@code 57014}（<b>只有真 PG 被 CancelRequest 取消</b>才会产出）；
 * ② {@code pg_sleep} + 驱动 {@code setQueryTimeoutMs} ⇒ <b>取消时刻 == deadline 时刻</b>；
 * ③ 取消 ⇒ <b>整个事务回滚</b> ⇒ ⇒ 那条 INSERT 不落库。
 * ⇒ ⇒ mock / H2 都验不到要验的东西。
 *
 * <h2>🔴 为什么必须装真的 {@code ObservationRegistry}</h2>
 * <p>装了 registry ⇒ {@code ObservabilityAutoConfiguration} 才会走「观测分支」
 * ⇒ ⇒ 服务端的 {@code scheduleTimeout} <b>才会被调度</b> ⇒ ⇒ 2s 到点服务端关 call
 * ⇒ ⇒ 客户端拿到 {@code DEADLINE_EXCEEDED}（<b>与生产一致</b>：域应用都装了 Micrometer）。
 * <br>⇒ ⇒ <b>副作用</b>：变异（拿掉绑定）时<b>客户端那一侧照样是绿的</b>（服务端照样 2s 关 call）
 * ⇒ ⇒ <b>红灯只会落在「行数」这一条上</b> ⇒ ⇒ 判据<b>精确</b>（不是「一有差异就全红」）。
 * <br>⇒ ⇒ 另有一个反向理由：<b>不装</b> registry ⇒ 服务端<b>从不</b>关 call
 * ⇒ ⇒ 客户端会一路等到 handler 自己结束 ⇒ ⇒ 那时的「客户端异常」是
 * <b>handler 的 DB 异常</b>（不是服务端超时）⇒ ⇒ 判据会<b>被别的机制污染</b>。
 *
 * <h2>⚠️ 为什么断言必须写明「类型与码」</h2>
 * <p>MyBatis 的 {@code DefaultSqlSession} 会把 mapper 调用里的<b>任何</b>异常包成
 * {@code PersistenceException} ⇒ ⇒ 若只断言「抛了异常」，翻译器坏掉时<b>照样绿</b>
 * ⇒ ⇒ 本用例断言「到达 handler 的是 {@link StatusRuntimeException} 且 code =
 * {@code DEADLINE_EXCEEDED}」。
 *
 * <h2>🔴 为什么「客户端取消」场景用<b>显式 cancel</b>而不是「deadline 到期」</h2>
 * <p>① <b>客户端码不同</b>：显式 cancel ⇒ <b>CANCELLED</b>（admin 域 E-4-R2 场景轮就是这一种）；
 * 而 deadline 到期在 grpc-java 里是 <b>DEADLINE_EXCEEDED</b> ⇒ ⇒ 两者<b>不可混用</b>。
 * <br>② <b>更重要的归因</b>：显式 cancel 会让服务端的监听器走 {@code onCancel}
 * ⇒ ⇒ 观测层的 {@code cancelQuietly(timeoutTask)} 会<b>撤掉服务端自己的 2s 超时调度</b>
 * ⇒ ⇒ 服务端<b>不会</b>在 2s 关 call ⇒ ⇒ 那条在途写<b>只可能</b>被「入向 deadline 绑到了 SQL 上」这件事取消
 * ⇒ ⇒ ⇒ <b>行数判据 100% 归因于本次修复</b>（不是被别的机制顺带救的）。
 *
 * <h2>B 出处（判定基准）</h2>
 * <p>B 侧 {@code service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java:516-522}
 * （{@code newUnaryServerInterceptor} 的 {@code onHalfClose} 里绑 {@code DeadlineState}）
 * ＋ {@code zrpc.RpcServerConf.Timeout = 2s}（= 本仓 {@code observability.rpc-timeout}）。
 */
@Testcontainers
class GrpcInboundDeadlinePgTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("grpc_deadline_probe")
            .withUsername("probe")
            .withPassword("probe");

    /** gRPC 侧的入向预算（B 侧 {@code zrpc.RpcServerConf.Timeout} = 2s）。 */
    private static final Duration RPC_BUDGET = Duration.ofSeconds(2);

    /** 客户端主动取消的时刻（= admin 域 E-4-R2 场景轮里「客户端 1s 取消」的那个 1s）。 */
    private static final Duration CLIENT_CANCEL_AFTER = Duration.ofSeconds(1);

    /** INSERT 之后的阻塞时长：<b>必须大于预算</b>（否则「还没到预算就写完了」⇒ 测不出取消）。 */
    private static final long SLEEP_MILLIS = 8_000L;

    /** 场景一（服务端预算到点）那条 INSERT 的 id。 */
    private static final long BUDGET_CASE_ID = 42L;

    /** 场景二（客户端主动取消）那条 INSERT 的 id。 */
    private static final long CANCEL_CASE_ID = 43L;

    private static HikariDataSource hikari;
    private static SqlSessionFactory factory;

    /** handler 收到的异常（用来断言类型与码）。 */
    private final AtomicReference<Throwable> handlerError = new AtomicReference<>();

    /** handler 跑完的信号。 */
    private final CountDownLatch handlerDone = new CountDownLatch(1);

    @BeforeAll
    static void setUp() throws Exception {
        hikari = new HikariDataSource();
        hikari.setJdbcUrl(PG.getJdbcUrl());
        hikari.setUsername(PG.getUsername());
        hikari.setPassword(PG.getPassword());
        hikari.setMaximumPoolSize(4);

        // 真 MyBatis：注入 query timeout 的拦截器 + 57014 翻译器 + 解包装饰的工厂
        Configuration configuration = new Configuration(new Environment(
                "probe", new JdbcTransactionFactory(), hikari));
        configuration.addMapper(ProbeMapper.class);
        configuration.addInterceptor(new DeadlineQueryTimeoutInjector());
        configuration.addInterceptor(new SqlTimeoutStatusTranslator());
        factory = new StatusAwareSqlSessionFactory(new SqlSessionFactoryBuilder().build(configuration));

        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS probe_row (id bigint primary key)");
        }
    }

    @AfterAll
    static void tearDown() {
        if (hikari != null) {
            hikari.close();
        }
    }

    @Test
    @DisplayName("🔴 PARITY-006：gRPC 路径上服务端预算到点 ⇒ 那条在途写【必须 0 行】"
            + "（修前 = 1 行 ⇒ 本用例必红）")
    void inFlightWriteIsRolledBackWhenServerBudgetExpires() throws Exception {
        ScenarioResult result = runScenario(BUDGET_CASE_ID, null, RPC_BUDGET);

        // ★★★ PARITY-006 的核心判据：那行 INSERT 的最终行数 ★★★
        assertThat(countTargetRows(BUDGET_CASE_ID))
                .as("🔴 PARITY-006 的核心：预算到点 ⇒ PG 收到 CancelRequest ⇒ 事务回滚"
                        + " ⇒ 那条在途写【不许落库】（修前 = 1 行）")
                .isZero();

        // handler 侧收到的异常必须写明类型与码（否则翻译器坏掉时本用例照样绿）
        assertThat(handlerError.get())
                .as("handler 必须因为 SQL 被取消而失败（若为 null ⇒ 说明 SQL 根本没被取消）")
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                        .isEqualTo(Status.Code.DEADLINE_EXCEEDED));

        // ⚠️ 客户端侧放在【最后】断言 ⇒ ⇒ 变异时红灯不会提前落在这一条上
        assertThat(result.clientSide())
                .as("服务端预算到点会关 call ⇒ 客户端应当收到 DEADLINE_EXCEEDED")
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                        .isEqualTo(Status.Code.DEADLINE_EXCEEDED));

        assertThat(result.elapsedMillis())
                .as("场景有效性 + 判据：必须在服务端预算（2s）附近结束，"
                        + "而不是睡满 %d ms（睡满 ⇒ 副作用落库 ⇒ 这就不是「预算到点」这个场景了）",
                        SLEEP_MILLIS)
                .isBetween(RPC_BUDGET.toMillis() - 500, SLEEP_MILLIS);
    }

    @Test
    @DisplayName("🔴 PARITY-006：gRPC 路径上【客户端主动取消】⇒ 那条在途写【同样必须 0 行】"
            + "（admin 域 E-4-R2 的现象侧）")
    void inFlightWriteIsRolledBackWhenClientCancels() throws Exception {
        ScenarioResult result = runScenario(CANCEL_CASE_ID, CLIENT_CANCEL_AFTER, RPC_BUDGET);

        assertThat(countTargetRows(CANCEL_CASE_ID))
                .as("🔴 PARITY-006 的核心：客户端取消只取消回包，**必须同时取消那条在途写**"
                        + "（修前 = 1 行 ⇒ 副作用已落库）")
                .isZero();

        assertThat(handlerError.get())
                .as("handler 必须因为 SQL 被取消而失败（若为 null ⇒ 说明 SQL 根本没被取消）")
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                        .isEqualTo(Status.Code.DEADLINE_EXCEEDED));

        // ⚠️ 客户端侧放在【最后】断言 ⇒ ⇒ 变异时红灯不会提前落在这一条上
        assertThat(result.clientSide())
                .as("客户端主动取消 ⇒ 客户端应当收到 CANCELLED")
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                        .isEqualTo(Status.Code.CANCELLED));

        assertThat(result.elapsedMillis())
                .as("场景有效性 + 判据：客户端 %d ms 就该取消（**必须早于服务端预算 %d ms**），"
                        + "不该睡满 %d ms",
                        CLIENT_CANCEL_AFTER.toMillis(), RPC_BUDGET.toMillis(), SLEEP_MILLIS)
                .isBetween(CLIENT_CANCEL_AFTER.toMillis() - 500, RPC_BUDGET.toMillis() - 100);
    }

    /**
     * 跑一次「写路径 + 阻塞」的 gRPC 调用 ⇒ 返回客户端侧耗时（毫秒）＋ 客户端侧看到的异常。
     *
     * @param targetId          被测那行的 id
     * @param clientCancelAfter 客户端主动取消的时刻；{@code null} = 不取消（只等服务端预算到点）
     * @param rpcBudget         服务端入向预算
     *
     * <p>⚠️ <b>客户端侧的结果刻意不在这里断言</b> ⇒ ⇒ 断言全部落在
     * {@link #inFlightWriteIsRolledBackWhenServerBudgetExpires()} /
     * {@link #inFlightWriteIsRolledBackWhenClientCancels()} 里，且<b>行数排第一</b>
     * ⇒ ⇒ 变异时红灯<b>精确落在核心判据</b>上。
     */
    private ScenarioResult runScenario(long targetId, Duration clientCancelAfter, Duration rpcBudget)
            throws Exception {
        return runScenario(targetId, clientCancelAfter, rpcBudget, null);
    }

    /**
     * 🔴 <b>探针同款注入</b>：<b>PG 冻结</b>（{@code docker pause}）＋ 客户端在途取消。
     *
     * <p>⚠️ <b>为什么必须用「冻结 PG」而不是「{@code pg_sleep}」</b>：
     * users-user 域的场景轮（§12.2.2 阶段D）用的注入是
     * <b>{@code docker pause} 冻结 PG 容器</b> ⇒ ⇒ 那条 INSERT <b>已经发出去</b>、
     * 但 PG <b>整个进程被冻住</b> ⇒ ⇒ 放行后才执行 ⇒ ⇒
     * <b>「CancelRequest 送不到」</b>（容器冻住 ⇒ 网络栈不处理）⇒ ⇒
     * <b>「靠 pgjdbc CancelRequest 回滚」这条路在这种注入下根本不成立</b>
     * ⇒ ⇒ 本用例复刻同一套注入 ⇒ ⇒ 读数与探针<b>同口径</b>。
     *
     * <p>⚠️ <b>这个用例要回答的是</b>：「客户端主动取消」这条路上，
     * 客户端的取消 ⇒ 服务端监听器的 {@code onCancel} ⇒ ⇒ 服务端自己的 2s {@code scheduleTimeout}
     * <b>被撤掉</b>（{@code cancelQuietly(timeoutTask)}）⇒ ⇒ 服务端<b>不会</b>在 2s 关 call
     * ⇒ ⇒ 此时那条在途 INSERT <b>只能</b>被「入向 deadline 落到 SQL 上」这件事清掉。
     */
    @Test
    @DisplayName("🔴 探针同款注入（PG 冻结 ＋ 客户端在途取消）：这条在途 INSERT 到底落不落库")
    void frozenPgWithClientCancel() throws Exception {
        // 变体 1：cancel@1s（**早于**服务端 2s 预算）
        ScenarioResult v1 = runScenario(44L, CLIENT_CANCEL_AFTER, RPC_BUDGET, PG.getContainerId());
        long rows1 = countTargetRows(44L);
        System.out.println("[PROBE-LIKE] 变体1（cancel@1s · 早于 2s 预算）⇒ 行数=" + rows1
                + " · handlerError=" + describe(handlerError.get())
                + " · 客户端=" + describe(v1.clientSide())
                + " · 客户端耗时=" + v1.elapsedMillis() + "ms");

        // 变体 2：cancel@5s（**晚于**服务端 2s 预算）
        handlerError.set(null);
        ScenarioResult v2 = runScenario(45L, Duration.ofSeconds(5), RPC_BUDGET, PG.getContainerId());
        long rows2 = countTargetRows(45L);
        System.out.println("[PROBE-LIKE] 变体2（cancel@5s · 晚于 2s 预算）⇒ 行数=" + rows2
                + " · handlerError=" + describe(handlerError.get())
                + " · 客户端=" + describe(v2.clientSide())
                + " · 客户端耗时=" + v2.elapsedMillis() + "ms");
    }

    /**
     * 🔴 <b>判定式实验（「行数到底由什么决定」）</b>：<b>固定客户端取消时刻，只改「PG 何时放行」</b>。
     *
     * <p><b>要回答的问题</b>：PARITY-006 的残留读数（探针里「B=0 行 · C=1 行」）到底是
     * <b>「客户端先放弃 ⇒ 服务端要跟着放弃」这条行为差异</b>，
     * 还是<b>「放行 PG 的那一刻，服务端还剩多少预算」</b>？
     *
     * <p><b>为什么这个实验能分开</b>：探针（{@code scenarios.py} 的阶段D）是
     * <b>先发 B 的调用、再发 C 的调用、最后才放行两侧 PG</b> ⇒ ⇒
     * <b>B 的调用比 C 早约 1 秒开始</b> ⇒ ⇒ <b>放行那一刻，两侧离各自预算到点的距离不同</b>
     * ⇒ ⇒ 若读数由「放行时刻 vs 预算」决定 ⇒ ⇒ 两侧读数可以不同，<b>而两侧代码完全一样</b>
     * ⇒ ⇒ 那就是<b>注入时序的假差</b>，不是行为漂移。
     *
     * <p><b>本用例怎么做</b>：handler 改成<b>两步</b>（<b>SELECT → INSERT</b>，与 users-user 域
     * {@code register} 的真实形状同款：查重在前、INSERT 在最后）⇒
     * <ul>
     *   <li><b>变体 R1（放行早于预算到点）</b> ⇒ 第一条 SELECT 放行后返回 ⇒
     *       第二条 INSERT <b>还有预算</b> ⇒ 执行 ⇒ 提交 ⇒ <b>1 行</b>；</li>
     *   <li><b>变体 R2（放行晚于预算到点）</b> ⇒ 第一条 SELECT 放行后返回 ⇒
     *       第二条 INSERT <b>预算已耗尽</b> ⇒ 被 {@code DeadlineQueryTimeoutInjector} 的
     *       「已过期 ⇒ 57014 ⇒ <b>不发 SQL</b>」守卫挡掉 ⇒ <b>0 行</b>。</li>
     * </ul>
     * ⇒ ⇒ <b>两次的客户端取消时刻完全相同</b>（都是 1s）⇒ ⇒
     * <b>唯一变量是「放行时刻」</b> ⇒ ⇒ 行数随它翻转 ⇒ ⇒
     * <b>「客户端先放弃」本身并不决定行数</b>。
     *
     * <p>⚠️ <b>本用例不是「双侧对拍」</b>：它只在本仓跑 ⇒ ⇒ 证不了「B 侧是什么样」
     * ⇒ ⇒ 跨侧那一半必须由探针（{@code tools/difftest/users-user/}）在<b>对称注入</b>下补上
     * ⇒ ⇒ 两半合起来才是结论。
     */
    @Test
    @DisplayName("🔴 判定式实验：固定客户端取消时刻 · 只改「放行 PG 的时刻」⇒ 行数随「剩余预算」翻转")
    void rowCountFollowsRemainingBudgetNotCancelTiming() throws Exception {
        // 变体 R1：客户端 1s 取消（**早于**服务端 2s 预算）＋ **1.2s 放行 PG**（预算还剩 ~0.8s）
        handlerError.set(null);
        ScenarioResult r1 = runScenario(46L, CLIENT_CANCEL_AFTER, RPC_BUDGET,
                PG.getContainerId(), 1_200L);
        long rowsR1 = countTargetRows(46L);
        System.out.println("[RELEASE-TIMING] R1（cancel@1s · 放行@1.2s · 预算剩 ~0.8s）⇒ 行数=" + rowsR1
                + " · handlerError=" + describe(handlerError.get())
                + " · 客户端=" + describe(r1.clientSide())
                + " · 客户端耗时=" + r1.elapsedMillis() + "ms");

        // 变体 R2：客户端 1s 取消（**同样早于**预算）＋ **2.6s 放行 PG**（预算已耗尽）
        handlerError.set(null);
        ScenarioResult r2 = runScenario(47L, CLIENT_CANCEL_AFTER, RPC_BUDGET,
                PG.getContainerId(), 2_600L);
        long rowsR2 = countTargetRows(47L);
        System.out.println("[RELEASE-TIMING] R2（cancel@1s · 放行@2.6s · 预算已耗尽）⇒ 行数=" + rowsR2
                + " · handlerError=" + describe(handlerError.get())
                + " · 客户端=" + describe(r2.clientSide())
                + " · 客户端耗时=" + r2.elapsedMillis() + "ms");

        assertThat(rowsR1)
                .as("🔴 R1：放行时**预算还剩** ⇒ 第二条 INSERT 照常执行 ⇒ 写**落库** ⇒ 1 行"
                        + "（⇒ 「客户端取消 ⇒ 服务端必须停手」在本仓**不成立**）")
                .isEqualTo(1L);
        assertThat(rowsR2)
                .as("🔴 R2：放行时**预算已耗尽** ⇒ 第二条 INSERT 被「已过期 ⇒ 57014」守卫挡掉"
                        + " ⇒ 写**不落库** ⇒ 0 行")
                .isZero();
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        if (t instanceof StatusRuntimeException sre) {
            return sre.getClass().getSimpleName() + "(" + sre.getStatus().getCode() + ")";
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** 跑一次场景；{@code freezeContainerId} 非空 ⇒ 发请求前先把该容器 {@code docker pause} 住。 */
    private ScenarioResult runScenario(long targetId, Duration clientCancelAfter, Duration rpcBudget,
            String freezeContainerId) throws Exception {
        return runScenario(targetId, clientCancelAfter, rpcBudget, freezeContainerId, 0L, false);
    }

    /**
     * 跑一次场景（可指定「放行 PG 的时刻」）。
     *
     * <p>⚠️ <b>默认走「SELECT → INSERT」两步形状</b>（真实 {@code register} 形状）⇒ ⇒
     * 「放行时还剩多少预算」才能决定第二条语句的命运 ⇒ ⇒ 判定式实验的前提。
     */
    private ScenarioResult runScenario(long targetId, Duration clientCancelAfter, Duration rpcBudget,
            String freezeContainerId, long releaseDelayMillis) throws Exception {
        return runScenario(targetId, clientCancelAfter, rpcBudget, freezeContainerId, releaseDelayMillis, true);
    }

    /**
     * 跑一次场景（可指定「放行 PG 的时刻」＋「handler 形状」）。
     *
     * @param releaseDelayMillis <b>放行 PG 的时刻 = 调用开始 + 这个毫秒数</b>；
     *                            {@code 0} = 客户端一返回就放行（原行为）
     * @param twoStep            {@code true} = handler 走<b>SELECT → INSERT</b> 两步
     *                            （users-user 域 {@code register} 的真实形状：查重在前、INSERT 在最后）
     */
    private ScenarioResult runScenario(long targetId, Duration clientCancelAfter, Duration rpcBudget,
            String freezeContainerId, long releaseDelayMillis, boolean twoStep) throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setRpcTimeout(rpcBudget);

        // 🔴 **必须装真的 ObservationRegistry** ⇒ 服务端的 scheduleTimeout 才会被调度
        // ⇒ 客户端拿到的 DEADLINE_EXCEEDED 来自「服务端关 call」（与生产一致）
        // ⇒ ⇒ 变异时客户端那一侧仍绿 ⇒ ⇒ 红灯只会落在「行数」这一条 ⇒ ⇒ 判据精确
        ObservationRegistry registry = ObservationRegistry.create();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("observationRegistry", registry);
        ObjectProvider<ObservationRegistry> registryProvider =
                beanFactory.getBeanProvider(ObservationRegistry.class);

        // 🔴 **场景有效性断言（1）**：registry 必须真的能被找到
        // ⇒ 否则 `interceptCall` 走「早退分支」⇒ **服务端 2s 关 call 这件事根本没发生**
        // ⇒ ⇒ 客户端侧的读数就变成「handler 自己报什么」的产物 ⇒ ⇒ 判据被污染
        assertThat(registryProvider.getIfAvailable())
                .as("场景有效性：registry 必须找得到 ⇒ 服务端的 scheduleTimeout 才会被调度")
                .isNotNull();

        io.grpc.ServerInterceptor interceptor = new ObservabilityAutoConfiguration()
                .grpcObservationInterceptor(registryProvider, properties);

        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name)
                .addService(writeService(targetId, freezeContainerId == null ? SLEEP_MILLIS : 0L, twoStep))
                .intercept(interceptor)
                .build()
                .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        ScheduledExecutorService canceller = Executors.newSingleThreadScheduledExecutor();
        try {
            // 🔴 **探针同款注入**：先把 PG 冻住 ⇒ 那条 INSERT 发出去但没人处理
            if (freezeContainerId != null) {
                docker(freezeContainerId, "pause");
            }
            // 🔴 **用「异步 + 显式 cancel」，不用「阻塞 + deadline 到期」**
            // ⇒ ① 显式 cancel 的客户端码是 **CANCELLED**（admin 域 E-4-R2 场景轮就是这一种）；
            //    而 deadline 到期在 grpc-java 里是 **DEADLINE_EXCEEDED** ⇒ ⇒ 两者**不可混用**。
            // ⇒ ② 更重要：**服务端自己的 scheduleTimeout 会因客户端 cancel 而被撤掉**
            //    （监听器的 onCancel ⇒ cancelQuietly(timeoutTask)）⇒ ⇒ 服务端**不会**在 2s 关 call
            //    ⇒ ⇒ 这条在途写**只可能**被「入向 deadline 绑到了 SQL 上」这件事取消
            //    ⇒ ⇒ **行数判据 100% 归因于本次修复**（不是被别的机制顺带救的）。
            ClientCall<String, String> clientCall = channel.newCall(METHOD, CallOptions.DEFAULT);
            AtomicReference<Throwable> clientError = new AtomicReference<>();
            CountDownLatch clientDone = new CountDownLatch(1);
            long t0 = System.nanoTime();
            ClientCalls.asyncUnaryCall(clientCall, "go", new StreamObserver<String>() {
                @Override
                public void onNext(String value) {
                    // 本用例不关心响应体
                }

                @Override
                public void onError(Throwable t) {
                    clientError.set(t);
                    clientDone.countDown();
                }

                @Override
                public void onCompleted() {
                    clientDone.countDown();
                }
            });
            if (clientCancelAfter != null) {
                canceller.schedule(() -> clientCall.cancel("client cancelled", null),
                        clientCancelAfter.toMillis(), TimeUnit.MILLISECONDS);
            }

            assertThat(clientDone.await(20, TimeUnit.SECONDS))
                    .as("客户端侧必须结束（预算到点 ⇒ 服务端关 call；或客户端主动取消）")
                    .isTrue();
            long elapsedMillis = (System.nanoTime() - t0) / 1_000_000L;

            // 🔴 **放行 PG**（探针同款）⇒ ⇒ 那条已发出但被冻住的 INSERT 这时才有机会被执行
            // ⚠️ **放行时刻 = 调用开始 + releaseDelayMillis**（不是「客户端返回后再等一会儿」）
            // ⇒ ⇒ 否则「放行时刻」这个变量会与「客户端取消时刻」耦合在一起 ⇒ ⇒ 判据被污染。
            if (freezeContainerId != null) {
                long elapsedSinceStart = (System.nanoTime() - t0) / 1_000_000L;
                long waitMore = releaseDelayMillis - elapsedSinceStart;
                if (waitMore > 0) {
                    Thread.sleep(waitMore);
                }
                docker(freezeContainerId, "unpause");
            }

            // 🔴 **必须等 handler 真的跑完**才能去查行数
            // ⇒ 否则「客户端取消」场景下客户端 1s 就返回、而 handler 还在睡
            // ⇒ ⇒ 此刻那笔事务**还没提交也还没回滚** ⇒ ⇒ 查到 0 行 = **假绿**
            // ⇒ ⇒ 等 handler 结束 ⇒ 事务此刻已定局（提交 ⇒ 1 行 / 回滚 ⇒ 0 行）
            assertThat(handlerDone.await(20, TimeUnit.SECONDS))
                    .as("handler 必须跑完（否则查库会看到「还没回滚」的中间态）")
                    .isTrue();
            return new ScenarioResult(elapsedMillis, clientError.get());
        } finally {
            canceller.shutdownNow();
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 一次场景的客户端侧读数。
     *
     * @param elapsedMillis 客户端调用耗时（毫秒）
     * @param clientSide    客户端看到的异常（成功时为 {@code null}）
     */
    private record ScenarioResult(long elapsedMillis, Throwable clientSide) {
    }

    /** 查被测那行的行数（用**另一条连接** ⇒ 不会撞上 handler 那条已回滚的事务）。 */
    private static long countTargetRows(long targetId) {
        try (Connection connection = hikari.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT count(*) FROM probe_row WHERE id = " + targetId)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * handler：<b>一个事务里</b>先 INSERT、再睡 ⇒ ⇒ 取消 ⇒ <b>整笔回滚</b> ⇒ ⇒ INSERT 不落库。
     */
    private ServerServiceDefinition writeService(long targetId) {
        return writeService(targetId, SLEEP_MILLIS, false);
    }

    /**
     * handler（可指定阻塞时长 ＋ 步骤形状）。
     *
     * <p>⚠️ <b>{@code sleepMillis == 0} 时不睡</b> ⇒ ⇒ INSERT 之后<b>直接提交</b>
     * ⇒ ⇒ 这正是 users-user 域 {@code register} 的形状（<b>INSERT 是最后一个 DB 步骤</b>，
     * 之后没有 {@code checkDeadline}）⇒ ⇒ 冻结 PG 的场景必须用这个形状，
     * 否则会多出一个「睡完之后才判 deadline」的环节 ⇒ ⇒ 判据会被污染。
     *
     * <p>⚠️ <b>{@code twoStep == true} ⇒ SELECT → INSERT</b>：这是 users-user 域
     * {@code register} 的<b>真实</b>形状（<b>先查重、再 INSERT</b>，B 侧
     * {@code UserDomainService.register} 的 W1/W4 同款）⇒ ⇒
     * 「放行时预算还剩多少」才能<b>决定第二条语句是否被守卫挡掉</b>
     * ⇒ ⇒ 这才是能分开「谁先取消」与「放行时刻」的形状。
     */
    private ServerServiceDefinition writeService(long targetId, long sleepMillis, boolean twoStep) {
        ServerCalls.UnaryMethod<String, String> body = (request, responseObserver) -> {
            try (SqlSession session = factory.openSession()) {
                if (twoStep) {
                    // 🔴 **第一步：查重**（真实 register 的形状）⇒ ⇒ 这条 SELECT 是**第一条**发出去的语句
                    // ⇒ ⇒ 冻结 PG 时它就是**卡住的那条** ⇒ ⇒ 放行后它才返回
                    session.getMapper(ProbeMapper.class).countById(targetId);
                }
                session.getMapper(ProbeMapper.class).insert(targetId);
                if (sleepMillis > 0) {
                    session.getMapper(ProbeMapper.class).sleepMillis(sleepMillis);
                }
                session.commit();
                responseObserver.onNext("ok");
                responseObserver.onCompleted();
            } catch (Throwable t) {
                handlerError.set(t);
                responseObserver.onError(t);
            } finally {
                handlerDone.countDown();
            }
        };
        return ServerServiceDefinition.builder("test.Write")
                .addMethod(METHOD, ServerCalls.asyncUnaryCall(body))
                .build();
    }

    /** {@code docker pause / unpause <containerId>}（探针同款注入）。 */
    private static void docker(String containerId, String action) {
        try {
            Process process = new ProcessBuilder("docker", action, containerId)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(process.waitFor())
                    .as("docker " + action + " 必须成功：%s", output)
                    .isZero();
        } catch (Exception e) {
            throw new IllegalStateException("docker " + action + " 失败", e);
        }
    }

    interface ProbeMapper {

        @Insert("INSERT INTO probe_row (id) VALUES (#{id})")
        int insert(@Param("id") long id);

        /**
         * 🔴 **查重 SELECT**（真实 {@code register} 的第一步）⇒ ⇒ 冻结 PG 时**这条**先卡住
         * ⇒ ⇒ 放行后它返回 ⇒ ⇒ 第二条 INSERT 能不能发出去，取决于**此刻还剩多少预算**。
         */
        @Select("SELECT count(*) FROM probe_row WHERE id = #{id}")
        int countById(@Param("id") long id);

        /**
         * 🔴 <b>{@code pg_sleep} 的参数单位是「秒」，不是「毫秒」</b>。
         *
         * <p>⚠️ <b>踩过的坑（1）· 单位</b>：初版写成 {@code pg_sleep(#{millis})} 传 {@code 8000}
         * ⇒ ⇒ PG 睡 <b>8000 秒</b>（≈ 2.2 小时）⇒ ⇒ 变异态下（没有取消）客户端会一直等
         * ⇒ ⇒ 用例**挂死**而不是变红 ⇒ ⇒ 判据被「测例本身的 bug」污染。
         * ⇒ ⇒ 现在显式换算：{@code 8000ms → 8.0s}。
         *
         * <p>⚠️ <b>踩过的坑（2）· 返回值类型（更隐蔽 ⇒ 会造成【假绿】）</b>：
         * {@code pg_sleep} 的列类型是 PG 的 {@code void} ⇒ 若 mapper 的返回类型写成 {@code void}
         * ⇒ ⇒ MyBatis 在「处理结果」阶段抛
         * {@code ExecutorException: No constructor found in void matching [java.lang.String]}
         * ⇒ ⇒ <b>这个异常与 deadline 无关</b> ⇒ ⇒ handler <b>永远</b>在 sleep 之后抛异常
         * ⇒ ⇒ 事务<b>永远</b>回滚 ⇒ ⇒ <b>行数永远是 0</b>
         * ⇒ ⇒ <b>核心判据彻底失去判别力</b>（修前修后都读 0 ⇒ 永远绿）。
         * ⇒ ⇒ 修法：外层套一个常量列，<b>不让 {@code void} 列参与结果映射</b>
         * （{@code pg_sleep} 是 volatile ⇒ 不会被优化掉 ⇒ 阻塞照样发生）。
         */
        @Select("SELECT 1 FROM (SELECT pg_sleep(CAST(#{millis} AS double precision) / 1000.0)) AS slept")
        int sleepMillis(@Param("millis") long millis);
    }

    private static final MethodDescriptor<String, String> METHOD =
            MethodDescriptor.<String, String>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("test.Write/Write")
                    .setRequestMarshaller(new StringMarshaller())
                    .setResponseMarshaller(new StringMarshaller())
                    .build();

    private static final class StringMarshaller implements MethodDescriptor.Marshaller<String> {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
