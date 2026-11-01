package com.rag.common.snowflake;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对应 B 侧版 service/common/snowflake/snowflake.go 的整个包（102 行）。
 *
 * <p>B 侧版用 {@code github.com/bwmarrin/snowflake v0.3.0}；Java 版按 bwmarrin/snowflake 的位布局
 * （41 位时间戳 + 10 位 workerId + 12 位 sequence）自己实现，保证 1:1 同位宽的 int64。
 *
 * <p><b>bwmarrin v0.3.0 位布局取证</b>（{@code snowflake.go} 15-33 行）：
 * <pre>
 *   Epoch    int64 = 1288834974657
 *   NodeBits uint8 = 10   → nodeMask / nodeShift = StepBits
 *   StepBits uint8 = 12   → stepMask / timeShift = NodeBits + StepBits
 * </pre>
 *
 * <p>框架职责：
 * <ul>
 *   <li>B 侧版：bwmarrin/snowflake 是分布式 ID 生成器（Twitter Snowflake 风格的 B 侧 实现）</li>
 *   <li>Java 版：纯 JDK 实现，无第三方依赖；用 {@code synchronized(state)} 串行化
 *       （对应 bwmarrin 内部的 {@code n.mu.Lock()}）</li>
 * </ul>
 *
 * <p><b>⚠️ L1 契约层（epoch / 位段是业务契约 · D13「L1 以 B 侧 为准」）</b>：
 * epoch {@code 1288834974657} + 位段 {@code 41 时间 / 10 worker / 12 seq}
 * ⇒ like 域的游标编码依赖 {@code >>22} 位移 ⇒ ⇒ <b>这三个数字逐字不可改</b>。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/snowflake/Snowflake.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 * <p><b>落点改名</b>：RAG 侧唯一引用方是 {@code com.rag.common.spring.SnowflakeBean} ⇒ RAG-V1 落
 * {@code com.rag.common.autoconfigure.SnowflakeAutoConfiguration}（D3：{@code @Configuration} → {@code @AutoConfiguration}）
 * ⇒ 本文件内相关 {@code @link} 已同步改指。
 */
public final class Snowflake {

    /** 对齐 B 侧版 {@code logx.Infof("Snowflake initialized with nodeId: %d", nodeId)}。 */
    /**
     * Spring 化 internal：仅供 {@link com.rag.common.autoconfigure.SnowflakeAutoConfiguration} 复用启动日志输出。
     * 业务代码请用 {@link #init()} / {@link #nextId()}，不要直接引用此 Logger。
     */
    public static final Logger LOG = LoggerFactory.getLogger(Snowflake.class);

    /**
     * 对应 B 侧版 {@code snowflake.Epoch}（{@code github.com/bwmarrin/snowflake@v0.3.0/snowflake.go:17}）：
     * <pre>
     *     // Epoch is set to the twitter snowflake epoch of Nov 04 2010 01:42:54 UTC in milliseconds
     *     Epoch int64 = 1288834974657
     * </pre>
     */
    static final long EPOCH = 1288834974657L;

    /** bwmarrin v0.3.0 {@code NodeBits uint8 = 10}。 */
    static final long WORKER_ID_BITS = 10L;

    /** bwmarrin v0.3.0 {@code StepBits uint8 = 12}。 */
    static final long SEQUENCE_BITS = 12L;

    /** 对应 bwmarrin {@code nodeMax = -1 ^ (-1 << NodeBits)} = 1023。 */
    public static final long MAX_WORKER_ID = -1L ^ (-1L << WORKER_ID_BITS);

    /** 对应 bwmarrin {@code stepMask = -1 ^ (-1 << StepBits)} = 4095。 */
    static final long SEQUENCE_MASK = -1L ^ (-1L << SEQUENCE_BITS);

    /** 对应 bwmarrin {@code nodeShift = StepBits} = 12。 */
    static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

    /** 对应 bwmarrin {@code timeShift = NodeBits + StepBits} = 22。 */
    static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /**
     * 墙钟读数（epoch 毫秒）—— <b>包级私有 · 只允许测试改</b>。
     *
     * <p>🔴 <b>为什么是包级可见的可变字段</b>：本条修复的<b>唯一可观察行为</b>是
     * 「墙钟跳变后 ID 时间戳不倒退」⇒ ⇒ 而墙钟<b>改不了</b>（改系统时间要 root、
     * 且会污染整台机器）⇒ ⇒ 只能<b>注入</b> ⇒ ⇒ 守门用它模拟一次 NTP 后跳 / 前跳。
     *
     * <p>与 {@code RpcInterceptor.DeadlineState.wallClockMillis}（同模块内既有约定）同款形态。
     *
     * <p>⚠️ <b>声明位置不可后移</b>：{@link #STATE} 是 {@code static final} ⇒ 类加载时即构造
     * ⇒ ⇒ 本字段与 {@link #monotonicNanos} <b>必须在 {@code STATE} 之前声明</b>
     * ⇒ 否则 {@link State} 构造时读到的 supplier 仍是 {@code null} ⇒ NPE。
     */
    static java.util.function.LongSupplier wallClockMillis = System::currentTimeMillis;

    /**
     * 单调钟读数（纳秒）—— 与 {@link #wallClockMillis} 同款（<b>包级私有 · 只允许测试改</b>），
     * 用途是让守门<b>确定性地推进时间</b>（不必 {@code sleep}，也不受 CI 负载影响）。
     */
    static java.util.function.LongSupplier monotonicNanos = System::nanoTime;

    // =========================================================================
    // 进程级唯一状态（对应 B 侧版的包级变量 sf / once / sfErr）
    // =========================================================================

    /**
     * <b>M11 修复</b>：全进程<b>只有一份</b>生成状态。
     *
     * <p>B 侧版只有 {@code var sf *snowflake.Node} 一个 node，其内部持有 {@code time} 与 {@code step}，
     * 进程内所有 {@code GetID()} 共用它。旧 Java 版有两份互不同步的状态：
     * <ul>
     *   <li>{@code Snowflake.HOLDER.state}（静态，workerId 取 hash 兜底）</li>
     *   <li>{@code SnowflakeBean.state}（Bean 实例，workerId 取自 Spring 配置）</li>
     * </ul>
     * {@code nextId()} 靠「Bean 有没有注册」在两者间切换，于是同一个 JVM 里可能出现
     * <b>两个不同 workerId 的生成器交替产出 ID</b>，且两者各自维护 lastTimestamp/sequence ——
     * workerId 相同时可撞 ID，不同时则同一条业务流里出现不同节点的 ID。
     *
     * <p>现在收敛为单一 {@link #STATE} + 单一 {@link #workerId}，与 B 侧版一一对应。
     */
    private static final State STATE = new State();

    /**
     * 已解析的 workerId；{@code null} 表示尚未初始化（对应 B 侧版 {@code sf == nil}）。
     *
     * <p>用包装类型而非 {@code long}，是为了区分「workerId=0」（合法）与「未解析」——
     * 用 primitive 会让 0 同时代表两种状态。
     */
    private static volatile Long workerId;

    /** 初始化失败时的原因，对应 B 侧版 {@code sfErr}。 */
    private static volatile Throwable initError;

    /**
     * 生成器状态，对应 bwmarrin {@code Node.time} / {@code Node.step} 两个字段。
     *
     * <p>Spring 化 internal：仅供本类持有。
     */
    public static final class State {
        /** bwmarrin {@code n.time}：上次生成 ID 的毫秒时间戳（相对 epoch）。 */
        long lastTimestamp;
        /** bwmarrin {@code n.step}：当前毫秒内的序号（0~4095）。 */
        long sequence;

        /**
         * <b>锚点 A</b>：Node 创建时刻「距 epoch 的纳秒数」——
         * 对应 B 侧 {@code curTime.Add(time.Unix(...).Sub(curTime))} 里的<b>墙钟偏移量</b>
         * （{@code github.com/bwmarrin/snowflake@service/common/src/main/java/com/rag/common/snowflake/Snowflake.java:132:126}）。
         *
         * <p>🔴 <b>为什么必须含 epoch 偏移</b>（AGENTS.md §3.3.1 的「读消费点」纪律）：
         * B 的 {@code n.epoch} 的<b>墙钟分量</b>就是 epoch 时刻本身 ⇒ {@code time.Since(n.epoch)}
         * 读出来的是「<b>距 epoch 时刻</b>的毫秒数」，<b>不是</b>「距进程启动的毫秒数」
         * ⇒ ⇒ 若这里只存「相对进程启动」，产出的 ID 时间戳分量会整体偏掉约 16 年
         * ⇒ ⇒ 落在 epoch 之前 ⇒ 与 B 的位布局语义不符。
         */
        final long anchorEpochNanos;

        /**
         * <b>锚点 B</b>：Node 创建时刻的单调钟读数（纳秒）——
         * 对应 B 侧 {@code curTime} 自带的<b>单调读数</b>（{@code github.com/bwmarrin/snowflake@service/common/src/main/java/com/rag/common/snowflake/Snowflake.java:145:124}）。
         *
         * <p>B 侧 {@code time.Since(n.epoch)} 之所以<b>免疫墙钟回拨</b>，靠的就是它：
         * 两个时间点都带单调读数 ⇒ {@code Time.Sub}（{@code $service/common/src/main/java/com/rag/common/snowflake/Snowflake.java:147:1194}）
         * 走 {@code subMono}（{@code :1210}）⇒ 只比单调读数，墙钟分量完全不参与。
         */
        final long anchorMonotonicNanos;

        /**
         * 构造即取锚点 —— 对应 B 侧 {@code snowflake.NewNode(...)}（{@code github.com/bwmarrin/snowflake@service/common/src/main/java/com/rag/common/snowflake/Snowflake.java:154:112}）
         * 里的 {@code var curTime = time.Now()}（{@code :124}）：<b>每个 Node 一份锚点</b>。
         */
        public State() {
            // B 侧: time.Unix(Epoch/1000, (Epoch%1000)*1000000).Sub(curTime)
            //   ⇒ 墙钟分量 = epoch 时刻 - 创建时刻 ⇒ 取负后即「距 epoch 的偏移」
            this.anchorEpochNanos = (wallClockMillis.getAsLong() - EPOCH) * 1_000_000L;
            this.anchorMonotonicNanos = monotonicNanos.getAsLong();
        }

        /**
         * 当前「距 epoch 时刻的毫秒数」—— 与 B 侧
         * {@code time.Since(n.epoch).Nanoseconds() / 1000000}（{@code github.com/bwmarrin/snowflake@service/common/src/main/java/com/rag/common/snowflake/Snowflake.java:165:139}）同构。
         *
         * <p><b>语义等价论证</b>：B 侧 {@code time.Since(n.epoch)} 在两个时间点都带单调读数时
         * 走 {@code subMono} ⇒ 结果 = 「创建时刻的墙钟偏移」+「创建至今的单调流逝量」
         * ⇒ ⇒ 本实现的 {@code (anchorEpochNanos + 单调流逝纳秒) / 1e6} 与之<b>逐项同构</b>：
         * <ul>
         *   <li>墙钟跳变（NTP 校正 / {@code setSystemTime} / 虚拟机快照恢复）<b>只影响锚点 A</b>
         *       ⇒ 而锚点 A 在 <b>Node 创建时一次性取定</b> ⇒ 之后<b>不再读墙钟</b>
         *       ⇒ ⇒ <b>回拨不会让时间戳倒退</b>（这正是 B 的性质）；</li>
         *   <li>墙钟<b>前跳</b>同样只影响锚点 ⇒ 与 B 侧 一致（B 侧 也不因前跳而跳号）；</li>
         *   <li>纳秒精度 + <b>最后一次整除</b>（{@code /1e6}）⇒ 与 B 的
         *       {@code .Nanoseconds() / 1000000} 截断位置一致。</li>
         * </ul>
         */
        long nowMillisSinceEpoch() {
            long sinceAnchorNanos = monotonicNanos.getAsLong() - anchorMonotonicNanos;
            return (anchorEpochNanos + sinceAnchorNanos) / 1_000_000L;
        }
    }

    /**
     * 对应 B 侧版 {@code func Init()}：
     *
     * <pre>
     * func Init() {
     *     once.Do(func() {
     *         nodeId, err := generateNodeId()
     *         if err != nil { sfErr = fmt.Errorf("generate node id failed: %w", err); return }
     *         nodeId = nodeId % 1024
     *         sf, sfErr = snowflake.NewNode(int64(nodeId))
     *         if sfErr == nil { logx.Infof("Snowflake initialized with nodeId: %d", nodeId) }
     *     })
     * }
     * </pre>
     *
     * <p><b>已知有意偏离（需 review 签字）</b>：B 侧 用 {@code sync.Once} 保证只跑一次；
     * Java 用 synchronized + 判空做同样的幂等，语义一致。
     */
    public static synchronized void init() {
        if (workerId != null || initError != null) {
            return; // 已初始化过，等价 sync.Once 的第二次调用直接返回
        }
        long id;
        try {
            id = generateNodeId();
        } catch (Throwable t) {
            initError = t;
            LOG.error("Snowflake initialize failed", t);
            return;
        }
        workerId = id;
        LOG.info("Snowflake initialized with nodeId: {}", id);
    }

    /**
     * 显式指定 workerId（对应 B 侧版「运维用 {@code SNOWFLAKE_NODE_ID} 指定固定 node」这一档）。
     *
     * <p><b>唯一调用者是 {@link com.rag.common.autoconfigure.SnowflakeAutoConfiguration}</b>，用于承载
     * {@code snowflake.worker-id} 这一 Spring 配置档。必须在容器 refresh 期、
     * 任何 {@link #nextId()} 调用<b>之前</b>完成；重复调用会被忽略（first-wins），
     * 避免运行中途 workerId 漂移导致 ID 断层。
     *
     * @param id 期望的 workerId；{@code null} 或负数表示放弃显式指定
     */
    public static synchronized void setWorkerId(Long id) {
        if (id == null || id < 0) {
            return;
        }
        if (workerId != null) {
            // 已解析过：保持 first-wins，不再改写（否则已产出的 ID 与新 workerId 不连续）
            LOG.warn("Snowflake workerId already resolved ({}), ignore explicit set {}", workerId, id);
            return;
        }
        workerId = id % (MAX_WORKER_ID + 1);
        LOG.info("Snowflake initialized with nodeId: {}", workerId);
    }

    /**
     * 对应 B 侧版 {@code func GetID() (int64, error)}：
     *
     * <pre>
     * func GetID() (int64, error) {
     *     Init()
     *     if sfErr != nil { return 0, sfErr }
     *     if sf == nil { return 0, fmt.Errorf("snowflake node is not initialized") }
     *     return sf.Generate().Int64(), nil
     * }
     * </pre>
     *
     * @return 生成的 64 位 ID
     * @throws IllegalStateException 当初始化失败时（对应 B 侧 返回 error）
     */
    public static long nextId() {
        if (workerId == null && initError == null) {
            init(); // 对应 B 的 GetID() 第一行 Init()
        }
        if (initError != null) {
            throw new IllegalStateException("snowflake node is not initialized", initError);
        }
        Long id = workerId;
        if (id == null) {
            throw new IllegalStateException("snowflake node is not initialized");
        }
        return nextIdInternal(id, STATE);
    }

    /**
     * 当前生效的 workerId（未初始化时返回 {@code null}）。供 {@link com.rag.common.autoconfigure.SnowflakeAutoConfiguration} 与测试观测用。
     */
    public static Long currentWorkerId() {
        return workerId;
    }

    /**
     * 对应 bwmarrin v0.3.0 {@code func (n *Node) Generate() ID}（{@code github.com/bwmarrin/snowflake@v0.3.0/snowflake.go:135-160}）。
     *
     * <pre>
     * n.mu.Lock()
     * now := time.Since(n.epoch).Nanoseconds() / 1000000
     * if now == n.time {
     *         n.step = (n.step + 1) &amp; n.stepMask
     *         if n.step == 0 {
     *                 for now &lt;= n.time { now = time.Since(n.epoch).Nanoseconds() / 1000000 }
     *         }
     * } else {
     *         n.step = 0
     * }
     * n.time = now
     * return ID((now &lt;&lt; timeShift) | (node &lt;&lt; nodeShift) | step)
     * </pre>
     *
     * <p><b>M12 修复：时钟回拨的处理必须按 v0.3.0 的真实算法，不能按「更安全」的想象写。</b>
     * v0.3.0 <b>没有</b>任何回拨保护 —— {@code now &lt; n.time} 时直接落进 {@code else} 分支：
     * {@code step} 归零、{@code time} 回退到更小的值、照常产出 ID。
     * 旧 Java 版写成「sleep 直到追上 + 抛 IllegalStateException 拒绝生成」，那是<b>凭空多出来的</b>行为：
     * <ul>
     *   <li>会阻塞调用线程（NTP 校正跳变几秒 = 整个进程卡几秒）</li>
     *   <li>B 侧永不失败的路径，Java 侧会抛异常</li>
     * </ul>
     * 现按 v0.3.0 原文实现。
     *
     * <p><b>🔴 DRIFT-SF-001 修复（2026-10-29 · G4 lane）：时钟源从墙钟改为「锚点 + 单调流逝」。</b>
     * <ul>
     *   <li><b>B 出处（逐条可核）</b>：{@code github.com/bwmarrin/snowflake@service/common/src/main/java/com/rag/common/snowflake/Snowflake.java:309}
     *       <ul>
     *         <li>{@code :124} {@code var curTime = time.Now()} —— <b>带单调读数</b>的当前时刻；</li>
     *         <li>{@code :126} {@code n.epoch = curTime.Add(time.Unix(Epoch/1000, (Epoch%1000)*1000000).Sub(curTime))}
     *             —— 库注释原文：<i>"to make sure we use the monotonic clock if available"</i>
     *             ⇒ <b>epoch 时刻</b>被挂上<b>单调读数</b>；</li>
     *         <li>{@code :139} {@code now := time.Since(n.epoch).Nanoseconds() / 1000000}
     *             —— 两个时间点都带单调读数 ⇒ {@code Time.Sub}
     *             （{@code $GOROOT/src/time/time.go:1194}）走 {@code subMono}（{@code :1210}）
     *             ⇒ <b>纯单调</b>，墙钟分量完全不参与。</li>
     *       </ul></li>
     *   <li><b>旧实现的问题</b>：{@code System.currentTimeMillis() - EPOCH} 是<b>纯墙钟</b>
     *       ⇒ NTP 校正 / {@code setSystemTime} / 虚拟机快照恢复一旦<b>倒退</b>
     *       ⇒ {@code now &lt; lastTimestamp} ⇒ 落进 {@code else} 分支把 {@code sequence} 归零
     *       ⇒ 若与回拨前某毫秒重合 ⇒ <b>产出完全相同的 ID</b>
     *       ⇒ 命中 {@code docs/PhasedGoal.md} <b>P0 #3「重复处理」</b>。</li>
     *   <li><b>⚠️ 订正旧 javadoc 的一处说反</b>：本方法上一版写「继承自 B 的已知风险（时钟回拨）」——
     *       <b>说反了</b>：B 侧 恰恰<b>免疫</b>回拨（上面三条出处），
     *       <b>「不免疫」是 RAG 侧纯墙钟实现独有的</b> ⇒ 故本条不是「为 B 侧 加保护」，
     *       而是<b>把 RAG 改成 B 侧 已有行为</b>。</li>
     *   <li><b>位布局与回绕逻辑一字未动</b>（41 位时间戳 + 10 位 workerId + 12 位 sequence），
     *       仍逐条对齐 {@code github.com/bwmarrin/snowflake@v0.3.0/snowflake.go:135-160}。</li>
     * </ul>
     *
     * <p>{@code synchronized (state)} 对应 bwmarrin 的 {@code n.mu.Lock()}。
     *
     * <p>Spring 化 internal：算法体；业务请用 {@link #nextId()}。
     */
    public static long nextIdInternal(long workerIdArg, State state) {
        synchronized (state) {
            // B 侧: now := time.Since(n.epoch).Nanoseconds() / 1000000
            //   ⇒ 距【epoch 时刻】的毫秒数（不是距进程启动）
            long now = state.nowMillisSinceEpoch();

            if (now == state.lastTimestamp) {
                state.sequence = (state.sequence + 1) & SEQUENCE_MASK;
                if (state.sequence == 0) {
                    // 当前毫秒序号耗尽：自旋到下一毫秒（对齐 v0.3.0 的 for now <= n.time）
                    // 🔴 DRIFT-SF-001：这里**也必须**用同一个时钟源（原实现是墙钟）
                    //    ⇒ 否则墙钟回拨时自旋会**永远转下去**（墙钟不再前进）⇒ 线程挂死。
                    while (now <= state.lastTimestamp) {
                        now = state.nowMillisSinceEpoch();
                    }
                }
            } else {
                // B 侧: v0.3.0 的 else 分支同时覆盖 now > n.time 与 now < n.time（后者在 B 侧 里不可达，
                //   因为 time.Since(n.epoch) 走 subMono ⇒ 纯单调 ⇒ now 永不倒退）。
                // 🔴 DRIFT-SF-001：本实现的 now 同样单调 ⇒ 此分支实际只覆盖 now > lastTimestamp
                //   ⇒ 与 B 的可达路径逐条一致（不再有「回拨 ⇒ sequence 归零 ⇒ 重复 ID」）。
                state.sequence = 0L;
            }

            state.lastTimestamp = now;

            return (now << TIMESTAMP_SHIFT)
                    | (workerIdArg << WORKER_ID_SHIFT)
                    | state.sequence;
        }
    }

    /**
     * 对应 B 侧版 {@code func generateNodeId() (uint16, error)}：
     *
     * <pre>
     *   1. 优先从环境变量 SNOWFLAKE_NODE_ID 指定
     *   2. 否则 SERVICE_NAME + "-" + PID 做 sha256，取前 2 字节作为 uint16
     *   3. 最后 % 1024 限定在 workerId 范围内
     * </pre>
     *
     * <p>B 侧版用 {@code binary.BigEndian.Uint16(hash[:2])} 取前 2 字节，Java 版用
     * {@code (hash[0] &lt;&lt; 8) | hash[1]}（默认大端）等价实现。
     *
     * <p><b>mod 顺序等价性</b>：B 侧 是「先 {@code uint16(id)} 截断，再 {@code % 1024}」，
     * Java 是「先 {@code parseLong}，再 {@code % 1024}」。因为 {@code 65536 = 1024 × 64}，
     * {@code (x mod 65536) mod 1024 == x mod 1024}，两条路径结果恒等。
     */
    public static long generateNodeId() {
        String explicit = System.getenv("SNOWFLAKE_NODE_ID");
        if (explicit != null && !explicit.isEmpty()) {
            try {
                long parsed = Long.parseLong(explicit.trim());
                if (parsed >= 0) {
                    return parsed % (MAX_WORKER_ID + 1);
                }
            } catch (NumberFormatException ignore) {
                // parse 失败被忽略，按 B 侧版语义继续走 hash 兜底
            }
        }

        String serviceName = System.getenv("SERVICE_NAME");
        if (serviceName == null || serviceName.isEmpty()) {
            serviceName = "unknown-service";
        }
        long pid = ProcessHandle.current().pid();
        String data = serviceName + "-" + pid;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            // 对应 B 侧版 binary.BigEndian.Uint16(hash[:2])：取 sha256 前 2 字节当 uint16
            int high = hash[0] & 0xFF;
            int low = hash[1] & 0xFF;
            long nodeId = ((high << 8) | low) & 0xFFFFL;
            return nodeId % (MAX_WORKER_ID + 1);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * <p><b>⚠️ L4 idiomatization（D3 starter 化）：构造器由 {@code private} 改为 {@code public}</b>
     *
     * <p>RAG 侧本类的构造器是私有的 ⇒ 只能由 {@code SnowflakeBean}（一个 {@code @Component}）
     * 内部 {@code new} 出来 ⇒ 而本仓的共享层是 <b>starter</b>（D3）⇒ 消费方不再扫
     * {@code com.rag.common} ⇒ {@code @Component} 永远扫不到
     * ⇒ ⇒ 注册动作改由 {@code SnowflakeAutoConfiguration} 的
     * {@code @Bean @ConditionalOnMissingBean Snowflake snowflake(...)} 承担
     * ⇒ ⇒ <b>框架要用 {@code new} 一个 Bean ⇒ 构造器必须可见</b>。
     *
     * <p>⚠️ <b>可见性放宽不等于「多了一个构造入口」</b>：本类的生成状态在
     * <b>唯一的静态 {@code STATE}</b> 里（批 1 已落地）⇒ 无论 new 多少个实例，
     * 生成状态始终只有那一份 ⇒ 不会出现「两个不同 workerId 的生成器交替产 ID」。
     *
     * <p>抄写基准：批 1 已落地的 {@code common/snowflake/Snowflake.java}（L2 · D13）
     * ＋ <b>构造器可见性回改</b>（L4 · D3）。
     */
    public Snowflake() {
    }
}