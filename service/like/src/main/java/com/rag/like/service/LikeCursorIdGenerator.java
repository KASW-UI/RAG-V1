package com.rag.like.service;

/**
 * 列表游标 ID 生成 —— 1:1 对应 Go 端 {@code service/like/rpc/internal/logic/likeactionlogic.go:26-54}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/like/like-rpc/src/main/java/com/rag/like/like/rpc/service/LikeCursorIdGenerator.java}
 * （L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：无。包名由 {@code com.rag.like.like.rpc.service} 压平为
 *       {@code com.rag.like.service}（D7）。</li>
 *   <li><b>语义还一样吗</b>：一样。位布局（22/12/10）、并发语义（进程级共享 + 互斥）、
 *       「同毫秒用满 4096 个则自旋到下一毫秒」的行为逐字未改。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <pre>
 * var (
 *     nodeId   int64 = 1
 *     sequence int64 = 0
 *     lastTime int64 = -1
 *     sfMutex  sync.Mutex
 * )
 *
 * func generateCursorID() int64 {
 *     sfMutex.Lock()
 *     defer sfMutex.Unlock()
 *     now := time.Now().UnixMilli()
 *     if now == lastTime {
 *         sequence = (sequence + 1) % 4096
 *         if sequence == 0 {
 *             for now &lt;= lastTime { now = time.Now().UnixMilli() }
 *         }
 *     } else {
 *         sequence = 0
 *     }
 *     lastTime = now
 *     return (now &lt;&lt; 22) | (nodeId &lt;&lt; 12) | sequence
 * }
 * </pre>
 *
 * <h2>⚠️ 它<b>不是</b> common 的 Snowflake，两者不可互换</h2>
 *
 * <p>Go 源码的注释写得很直白：项目里另有一套 {@code service/common/snowflake}，
 * 但「起始时间与本服务中不符，返回给前端可能点赞时间对不上，所以新写了一个」。
 * 根因是本 ID 被塞进 ZSet 的 <b>score</b>，而读路径靠
 * {@code score >> 22} 反解出毫秒时间戳（见 {@code getuserlikelistlogic.go:70}）。
 * 换句话说：<b>高 22 位必须是 Unix 毫秒</b>，这是 ZSet 排序与时间展示的前提。
 * 用 common Snowflake 的位布局会同时破坏排序与时间反解。
 *
 * <p>因此本类<b>不复用</b> {@code com.rag.common.snowflake.Snowflake}，
 * {@link LikeMessageDigest}/{@link LikeMessageIngestService} 里用于 Kafka 消息 msg_id 的
 * 雪花 ID 才走 common 那套（对应 Go {@code snowflake.GetID()}）。
 *
 * <h2>并发语义</h2>
 *
 * <p>Go 用包级变量 + {@code sync.Mutex} 保证「读 now / 改 sequence / 写 lastTime」这一段原子。
 * Java 用 {@code synchronized} 表达同一件事：{@code sequence}/{@code lastTime} 仍是
 * <b>进程级共享状态</b>（不是每实例一份），与 Go 的包级变量一致。
 *
 * <p>单毫秒内超过 4096 次时，Go 的行为是<b>自旋等到下一毫秒</b>（{@code sequence == 0} 分支）。
 * 这里照搬，包括那个看似可以优化成「等待」的循环 —— 它保证同一毫秒内不会有两个相同 ID。
 */
public final class LikeCursorIdGenerator {

    /** 低位留给 sequence 的位数。Go: {@code sequence = (sequence + 1) % 4096} ⇒ 2^12。 */
    private static final int SEQUENCE_BITS = 12;

    /** nodeId 的起始位。Go: {@code nodeId << 12}。 */
    private static final int NODE_ID_SHIFT = SEQUENCE_BITS;

    /**
     * nodeId 占用的位数 = 10。
     *
     * <p>Go 的表达式是 {@code (now << 22) | (nodeId << 12) | sequence}，
     * 而 sequence 有 12 位、nodeId 从第 12 位起 ⇒ nodeid 只能用 bits 12..21 共 10 位，
     * 时间戳从第 22 位起。<b>位移量 22 = 12 + 10，而不是 12 + 12。</b>
     *
     * <p>（写成 24 会让 {@code score >> 22} 反解出完全错误的时间戳，
     * 且因为两边都用同一个常量，单元测试外几乎不可能被发现。）
     */
    private static final int NODE_ID_BITS = 10;

    /** 时间戳左移位数。Go: {@code now << 22}，且读路径 {@code score >> 22} 反解。 */
    private static final int TIMESTAMP_SHIFT = NODE_ID_SHIFT + NODE_ID_BITS;

    /** sequence 的掩码：{@code 2^12 - 1}（Go 侧的 {@code % 4096} 等价物）。 */
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    /**
     * Go {@code nodeId int64 = 1}。
     *
     * <p>Go 注释：「目前为1, 如果部署在多个服务器上可以利用环境变量读取 nodeId」。
     * Java 侧保持<b>固定 1</b>，不引入环境变量 —— 改了就是新增配置面，
     * 而多实例部署时该 ID 会被 ZSet score 共享，必须先想清楚冲突后果再改。
     */
    private static final long NODE_ID = 1L;

    /** Go 包级变量 {@code sequence int64 = 0}。 */
    private static long sequence = 0L;

    /** Go 包级变量 {@code lastTime int64 = -1}。 */
    private static long lastTime = -1L;

    private LikeCursorIdGenerator() {
        // 有状态，禁止实例化
    }

    /**
     * 对应 Go {@code generateCursorID()}。
     *
     * @return {@code (UnixMilli << 22) | (nodeId << 12) | sequence}
     */
    public static long nextId() {
        synchronized (LikeCursorIdGenerator.class) {
            long now = System.currentTimeMillis();
            if (now == lastTime) {
                sequence = (sequence + 1) & SEQUENCE_MASK;
                if (sequence == 0) {
                    // Go: for now <= lastTime { now = time.Now().UnixMilli() }
                    // 同毫秒内用满 4096 个 ⇒ 阻塞到下一毫秒，而不是发出重复 ID。
                    while (now <= lastTime) {
                        now = System.currentTimeMillis();
                    }
                }
            } else {
                sequence = 0L;
            }
            lastTime = now;
            return (now << TIMESTAMP_SHIFT) | (NODE_ID << NODE_ID_SHIFT) | sequence;
        }
    }

    /**
     * 把 ZSet 的 score 反解为毫秒时间戳 —— 对应 Go 读路径里的 {@code score >> 22}。
     *
     * <p>Go 侧是内联的位运算散落在两个 logic 里（{@code getuserlikelistlogic.go:70}、
     * {@code gettargetlikerlistlogic.go:77}）。抽出来只为让「生成」与「解析」共用同一个位移常量，
     * 避免两处各写一个魔数后互相漂移。
     */
    public static long timestampOf(long score) {
        return score >> TIMESTAMP_SHIFT;
    }

    /**
     * 把毫秒时间戳打包成 ZSet score —— 对应 Go 写路径里的 {@code timestamp << 22}。
     *
     * <p>DB 回源时用（{@code getuserlikelistlogic.go:102} / {@code gettargetlikerlistlogic.go:112}）。
     */
    public static long scoreOf(long timestampMillis) {
        return timestampMillis << TIMESTAMP_SHIFT;
    }
}
