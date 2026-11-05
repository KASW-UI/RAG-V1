package com.rag.favorite.support;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.util.function.Consumer;

/**
 * <p><b>favorite 域 Testcontainers 宿主口段</b> —— 把「容器发布到宿主机的那个端口」
 * 从「Docker 随机分配」改成「<b>固定在本域专用段内</b>」。
 *
 * <h2>为什么必须钉</h2>
 * <p>Testcontainers <b>没有「宿主口段」这个概念</b>：它不传 {@code -p} ⇒ Docker 自己在
 * <b>OS 临时端口段</b>（macOS：{@code 49152–65535}）里挑一个空口发布出来
 * ⇒ ⇒ 那个口和<b>别的会话的容器发布口、别的进程临时占的口</b>落在<b>同一段</b>
 * ⇒ ⇒ 谁先占住谁拿到 ⇒ ⇒ 于是会出现「我连的是那个口，但回话的<strong>不是我的容器</strong>」。
 * <p>B 侧已实测到该形态：{@code EOFException @ PGStream.receiveChar /
 * ConnectionFactoryImpl.enableSSL} ⇒ 读起来像「PG 挂了」，实则是「那个口上根本不是我要的 PG」。
 *
 * <h2>段是怎么分的（不许自己改）</h2>
 * <p>B 侧 global 端口段表把 {@code 22910–22939} <b>整段</b>预分配给 <b>favorite 域</b>
 * ⇒ ⇒ <b>本域自用</b>
 * ⇒ ⇒ favorite 域段横跨<b>两个模块</b>（favorite-rpc 与 favorite-api）
 * ⇒ ⇒ 本模块取<b>上半段</b>（{@code 22930–22939}），favorite-rpc 取<b>下半段</b>（{@code 22910–22929}）
 * ⇒ ⇒ 两个模块的宿主口<b>零重叠</b>。
 * <ul>
 *   <li><b>宿主口段</b>（{@link #FIRST}–{@link #LAST}）= <b>Testcontainers 宿主口</b> ⇒ 本模块自用；</li>
 *   <li><b>脚手架段</b>（{@link #SCRATCH_FIRST}–{@link #SCRATCH_LAST}）= <b>只给守门自己用</b>
 *       ⇒ <b>与生产用的口不复用</b>。</li>
 * </ul>
 *
 * <h2>为什么「每个测试类一个专属口」</h2>
 * <p>同一模块内测试类<b>串行</b>跑（surefire 默认 1 fork、无 parallel 配置）
 * ⇒ ⇒ 理论上「全模块共用一个口」也能跑 ⇒ ⇒ 但那样会出现「上一个类刚停、下一个类立刻抢同一个口」的
 * <b>释放窗口</b> ⇒ ⇒ 撞上就是 <b>EADDRINUSE</b> ⇒ ⇒ <b>每个测试类一个专属口，全域不复用</b>。
 *
 * <h2>⚠️ 钉口机制（已实测，两条都成立）</h2>
 * <p>机制 A（{@code withCreateContainerCmdModifier} + {@code HostConfig.withPortBindings}）
 * ⇒ {@code getMappedPort()} <b>返回那个固定口</b> ✔ ⇒ <b>本类用它</b>；
 * 机制 B（子类暴露 {@code addFixedExposedPort}）⇒ 同样成立 ✔
 * ⇒ ⇒ 留档说明「不止一条路可行」，避免后来人以为只有 A。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>包名</b>压平（D7）：
 *             {@code com.rag.favorite.favorite.api.support} ⇒ {@code com.rag.favorite.support}。</li>
 *         <li><b>模块路径变了</b>：B 侧 favorite-api 是独立 Maven 模块
 *             （{@code service/favorite/favorite-api/}）⇒ C 侧 D7 打平为
 *             <b>一个</b> jar 模块 {@code service/favorite/} ⇒ ⇒ 端口段沿用
 *             B 侧「api 模块取上半段」的分配不变。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 段边界、登记册机制、钉口实现、前置校验逐条未改。</li>
 *   <li><b>B 出处</b>：B 侧
 *       {@code service/favorite/favorite-api/src/test/java/com/rag/favorite/favorite/api/support/RealContainerHostPort.java:49-181}。</li>
 * </ol>
 */
public final class RealContainerHostPort {

    /** favorite 域在 global 端口段表里分到的<b>整段</b>（30 口）。 */
    public static final int DOMAIN_SEGMENT_BEGIN = 22910;

    /** favorite 域整段的上界。 */
    public static final int DOMAIN_SEGMENT_END = 22939;

    /** 本模块实际使用的 Testcontainers 宿主口段。 */
    public static final int FIRST = 22930;

    /** 本模块 Testcontainers 宿主口段的上界。 */
    public static final int LAST = 22934;

    /** 守门自己的脚手架段 ⇒ <b>与 {@link #FIRST}–{@link #LAST} 不复用</b>。 */
    public static final int SCRATCH_FIRST = 22935;

    /** 脚手架段上界。 */
    public static final int SCRATCH_LAST = 22939;

    /** OS 临时端口段下界（macOS / Linux 同值）—— Testcontainers 随机宿主口就落在这里。 */
    public static final int OS_EPHEMERAL_BEGIN = 49152;

    /** OS 临时端口段上界。 */
    public static final int OS_EPHEMERAL_END = 65535;

    // ── 每个「起容器」的测试类一个**专属**固定宿主口（不复用）────────────────────
    /**
     * {@code FavoritePgParityTestBase}（4 个 mapper 域用例共用）的 PG 宿主口。
     *
     * <p>⚠️ <b>为什么是 PG 而不是 Redis</b>：B 侧原本把 Redis 口登记给
     * {@code FavoriteApiMvcPipelineTest}（B 侧 {@code favorite-api/src/test/…/FavoriteApiMvcPipelineTest.java:98-101}），
     * 但那个测试<b>本轮未移植</b>（B 侧 822 行 · 依赖 mock 掉的下游客户端）⇒
     * ⇒ 登记册里那个口<b>没有任何测试类在用</b> ⇒ 守门 {@code RealContainerHostPortTest}
     * 的「每个登记口恰好被引用一次」会红 ⇒ ⇒ **撤掉它**（登记册必须与源码同形）
     * ⇒ ⇒ 换成**确实在用**的那个口（PG）。
     * <p>⚠️ 该缺口已登记在 {@code docs/DRIFT/favorite/verification-favorite-service-2026-11-04.md}。
     */
    public static final int FAVORITE_PG_PARITY = 22930;

    /** 守门自己起容器用的口（脚手架段第一口）⇒ <b>不与任何测试类复用</b>。 */
    public static final int GATE_LIVE = SCRATCH_FIRST;

    /**
     * <b>登记册</b>：本模块**测试类**用的固定宿主口<b>常量名</b>全集
     * ⇒ ⇒ 守门拿它跟「源码里实际被引用的<b>名字</b>」做<b>守恒对账</b>
     * ⇒ ⇒ 新增测试类必须同时登记 ⇒ 不登记就会被守门抓住。
     *
     * <p>⚠️ <b>为什么按「名字」登记而不是按「值」</b>：段的边界常量
     * （{@link #FIRST} = 22930）与第一个登记口（值也是 22930）
     * <b>同值</b> ⇒ ⇒ 若按值反查名字，会把 {@code FIRST} 当成登记项
     * ⇒ ⇒ 守门对不上账 ⇒ ⇒ 只能按名字登记。
     */
    public static final String[] TEST_CLASS_PORT_NAMES = {
        "FAVORITE_PG_PARITY",
    };

    /**
     * 按名字取登记口的值。
     *
     * @param name 常量名（必须出现在 {@link #TEST_CLASS_PORT_NAMES} 里）
     * @return 该常量对应的宿主口
     */
    public static int portOf(String name) {
        for (String candidate : TEST_CLASS_PORT_NAMES) {
            if (candidate.equals(name)) {
                try {
                    return RealContainerHostPort.class.getField(name).getInt(null);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("读不到常量 " + name, e);
                }
            }
        }
        throw new IllegalArgumentException("常量名 " + name + " 不在登记册里");
    }

    private RealContainerHostPort() {
    }

    /**
     * 把容器端口 {@code 5432}（PG）钉到指定宿主口。
     *
     * @param hostPort 宿主口
     * @return 可直接挂给 {@code withCreateContainerCmdModifier} 的消费者
     */
    public static Consumer<CreateContainerCmd> bindPg(int hostPort) {
        return bindTo(hostPort, 5432);
    }

    /**
     * 把容器端口 {@code 6379}（Redis）钉到指定宿主口。
     *
     * @param hostPort 宿主口
     * @return 可直接挂给 {@code withCreateContainerCmdModifier} 的消费者
     */
    public static Consumer<CreateContainerCmd> bindRedis(int hostPort) {
        return bindTo(hostPort, 6379);
    }

    /**
     * 通用钉口。
     *
     * @param hostPort 宿主口
     * @param containerPort 容器内口
     * @return 可直接挂给 {@code withCreateContainerCmdModifier} 的消费者
     */
    public static Consumer<CreateContainerCmd> bindTo(int hostPort, int containerPort) {
        requireAllocatable(hostPort);
        return cmd -> {
            HostConfig hostConfig = cmd.getHostConfig();
            if (hostConfig == null) {
                hostConfig = new HostConfig();
                cmd.withHostConfig(hostConfig);
            }
            hostConfig.withPortBindings(new PortBinding(
                    Ports.Binding.bindPort(hostPort), new ExposedPort(containerPort)));
        };
    }

    /**
     * 钉口前的<b>前置校验</b>：越段 / 落进 OS 临时端口段 ⇒ 立刻抛（而不是等到跑测时才发现）。
     *
     * <p>⚠️ 这里校验的是<b>域段</b>（{@link #DOMAIN_SEGMENT_BEGIN}–{@link #DOMAIN_SEGMENT_END}）
     * ⇒ ⇒ <b>脚手架段也在允许范围内</b> ⇒ ⇒ 因为守门自己就要用它起容器
     * ⇒ ⇒ 「生产用的口不许踩脚手架段」这条<b>不是靠这里守的</b>，
     * 而是靠 {@code RealContainerHostPortTest} 的<b>白名单</b>：
     * 源码里只允许引用<b>登记册</b>里的常量 + 守门自己的 {@code GATE_LIVE}。
     *
     * @param hostPort 待校验的宿主口
     */
    public static void requireAllocatable(int hostPort) {
        if (hostPort < DOMAIN_SEGMENT_BEGIN || hostPort > DOMAIN_SEGMENT_END) {
            throw new IllegalArgumentException("宿主口 " + hostPort
                    + " 越出 favorite 域段 " + DOMAIN_SEGMENT_BEGIN + "-" + DOMAIN_SEGMENT_END
                    + "（宿主口段 " + FIRST + "-" + LAST
                    + "，脚手架段 " + SCRATCH_FIRST + "-" + SCRATCH_LAST + " 只给守门用）");
        }
        if (hostPort >= OS_EPHEMERAL_BEGIN && hostPort <= OS_EPHEMERAL_END) {
            throw new IllegalArgumentException("宿主口 " + hostPort
                    + " 落在 OS 临时端口段 " + OS_EPHEMERAL_BEGIN + "-" + OS_EPHEMERAL_END
                    + " ⇒ 会与别的进程抢口 ⇒ 这正是本类要消灭的那类抖动");
        }
    }
}
