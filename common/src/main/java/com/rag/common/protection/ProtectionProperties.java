package com.rag.common.protection;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B 侧「请求保护层」四道中间件的配置聚合点。
 *
 * <p><b>为什么 B 侧 yaml 里一个字都没写，这里却要显式写默认值</b>：
 * B 的 {@code service/message/api/etc/messagecenter.yaml} <b>完全没有</b>提到
 * {@code MaxBytes} / {@code MaxConns} / {@code CpuThreshold} / {@code Breaker}，
 * 但它们<b>确实是开启的</b> —— 依据是 B 侧 的两处默认值声明
 * （B 出处：{@code service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java:15-23}，RAG 仓）：
 * <ul>
 *   <li>{@code rest/config.go:52-56}：{@code MaxConns int json:",default=10000"}、
 *       {@code MaxBytes int64 json:",default=1048576"}、
 *       {@code CpuThreshold int64 json:",default=900,range=[0:1000)"}</li>
 *   <li>{@code rest/config.go:12-22} {@code MiddlewaresConf}：九个开关
 *       <b>全部</b> {@code json:",default=true"}</li>
 * </ul>
 * ⇒ <b>逐字段照 yaml 对是永远看不见这几层的</b>，它们只存在于 框架结构体的 tag 里。
 * （这正是 §3.1「不要拿另一个域的实测结论当对照基准」的另一种形态：yaml 不是行为的全集。）
 *
 * <p><b>四层各自的 B 出处与可观察行为</b>：
 * <table border="1">
 *   <caption>保护层对照</caption>
 *   <tr><th>层</th><th>B 侧 实现</th><th>拒绝时</th><th>本类字段</th></tr>
 *   <tr>
 *     <td>并发连接上限</td>
 *     <td>{@code rest/handler/maxconnshandler.go:12-36}</td>
 *     <td><b>503 空 body</b></td>
 *     <td>{@link #maxConns}</td>
 *   </tr>
 *   <tr>
 *     <td>请求体大小</td>
 *     <td>{@code rest/handler/maxbyteshandler.go:10-25}</td>
 *     <td><b>413 空 body</b></td>
 *     <td>{@link #maxBytes}</td>
 *   </tr>
 *   <tr>
 *     <td>CPU 自适应降载</td>
 *     <td>{@code rest/handler/sheddinghandler.go:22-56} + {@code core/load/adaptiveshedder.go}</td>
 *     <td><b>503 空 body</b></td>
 *     <td>{@link CpuShedding}</td>
 *   </tr>
 *   <tr>
 *     <td>熔断</td>
 *     <td>{@code rest/handler/breakerhandler.go:18-42} + {@code core/breaker/googlebreaker.go:52-79}</td>
 *     <td><b>503 空 body</b></td>
 *     <td>{@link #breaker}</td>
 *   </tr>
 * </table>
 *
 * <p><b>四层的 body 都是空的</b>，且<b>不走 envelope</b>：B 侧这四处都是裸
 * {@code w.WriteHeader(code)}，没有 {@code httpx.Error}，因此既没有
 * {@code {code:...}} 也没有 {@code Content-Type: application/json}。
 * 这与各业务模块「所有业务错误恒 HTTP 200 + envelope」的约定<b>刻意不同</b> ——
 * 保护层在到达业务逻辑之前就拒绝了，没有 envelope 可包。
 *
 * <h2>为什么这套实现住在 {@code service/common}</h2>
 *
 * <p>B 侧这四层是 <b>框架 {@code rest.Server} 的内建行为</b>，不是某个业务模块的代码 ——
 * 任何 {@code rest.MustNewServer} 起的进程都自带。因此 RAG 侧也只有一处实现才对：
 * 复制到每个 api 模块，最终必然出现「message 有 MaxBytes、like 没有」这类静默不一致。
 *
 * <h2>为什么四个 Filter 带 {@code @ConditionalOnWebApplication(SERVLET)}</h2>
 *
 * <p><b>诚实边界：这四个条件在当前仓库里不是「补现行 bug」，而是「防止下一个模块踩坑」。</b>
 * 实测（{@code mvn dependency:list}）：{@code service/message/message-rpc} 的依赖树里
 * <b>没有</b> {@code jakarta.servlet-api}、也没有 {@code tomcat-embed-core}
 * （只有 {@code tomcat-embed-el}，它不含 servlet API）；
 * 而四个 rpc 进程（{@code message-rpc} / {@code like-rpc} / {@code admin-rpc} / {@code user-rpc}）
 * 又<b>已经刻意不扫 {@code com.rag.common}</b> —— 原因正是同包下
 * {@code com.rag.common.spring.HttpMiddlewareFilter} 是 servlet Filter，
 * 全包扫描会把 web 类拖进纯 gRPC 进程。所以今天两者没有相交。
 * <p>但「安全」此刻依赖的是「每个 rpc 进程都记得不扫 {@code com.rag.common}」这条<b>口头约定</b>，
 * 而 {@code com.rag.common} 恰恰是唯一必须被所有进程共享的包。把 HTTP 保护层放进共享包时，
 * 用一个条件注解把「这是 HTTP 层」这件事编码进去，比在每个 rpc 进程的注释里重复警告更可靠
 * —— <b>约定会随新人流失，条件不会</b>。
 *
 * <h2>配置前缀为什么是 {@code rag.protection}</h2>
 *
 * <p>本类原先是 {@code message-api.protection}，因为它只服务 message-api。
 * 下沉到共享包后带业务域前缀是错的：一个「共用的限流器」不该归某一个业务域所有。
 * ⇒ 前缀改为 {@code rag.protection}。
 * ⚠️ <b>这是本次下沉唯一一处「部署可见」的变更</b>：各服务 yaml 里
 * {@code message-api.protection.*} 必须改写成 {@code rag.protection.*}。
 * 未配置时全部字段回落到下方默认值，而<b>这些默认值就是 框架结构体的 tag 默认值</b>，
 * 所以「漏改」不会变成「保护层静默关闭」，只会变成「阈值回到 B 侧 默认」——
 * 这是刻意选的失效方向（fail-safe，而非 fail-open）。
 */
@ConfigurationProperties(prefix = "rag.protection")
public class ProtectionProperties {

    /**
     * B 侧 {@code rest/config.go:52}：{@code MaxConns int json:",default=10000"}。
     * {@code <= 0} 时 B 的 {@code MaxConnsHandler} 直接返回 no-op（不限流）。
     */
    private int maxConns = 10000;

    /**
     * B 侧 {@code rest/config.go:53}：{@code MaxBytes int64 json:",default=1048576"}（1 MiB）。
     * {@code <= 0} 时 {@code MaxBytesHandler} 返回 no-op。
     */
    private long maxBytes = 1048576L;

    @NestedConfigurationProperty
    private CpuShedding cpuShedding = new CpuShedding();

    @NestedConfigurationProperty
    private Breaker breaker = new Breaker();

    /**
     * 出向 gRPC 的 deadline 传播（B 的 <b>ctx 传播</b>）。
     *
     * <p>🆕 2026-10-30（E65 落码）：见 {@link GoStyleOutboundDeadlineInterceptor}。
     */
    @NestedConfigurationProperty
    private OutboundDeadline outboundDeadline = new OutboundDeadline();

    /**
     * Redis 的「请求期限感知」（G1 反馈单 2/3 · 2026-10-30 · G4 lane）。
     *
     * <p>🆕 见 {@link com.rag.common.redis.GoStyleRedisDeadline}。
     */
    @NestedConfigurationProperty
    private RedisDeadline redisDeadline = new RedisDeadline();

    /**
     * Stat 层（B 的 {@code Middlewares.Stat}）。
     *
     * <p>🆕 2026-10-30（G4 lane）：见 {@link com.rag.common.observability.GoStyleStatInterceptor}。
     */
    @NestedConfigurationProperty
    private Stat stat = new Stat();

    /**
     * Prometheus 层（B 的 {@code Middlewares.Prometheus}）。
     *
     * <p>🆕 2026-10-30（G4 lane）：见 {@link com.rag.common.observability.GoStylePrometheusInterceptor}。
     */
    @NestedConfigurationProperty
    private Prometheus prometheus = new Prometheus();

    public Stat getStat() {
        return stat;
    }

    public void setStat(Stat stat) {
        this.stat = stat;
    }

    public Prometheus getPrometheus() {
        return prometheus;
    }

    public void setPrometheus(Prometheus prometheus) {
        this.prometheus = prometheus;
    }

    public int getMaxConns() {
        return maxConns;
    }

    public void setMaxConns(int maxConns) {
        this.maxConns = maxConns;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public CpuShedding getCpuShedding() {
        return cpuShedding;
    }

    public void setCpuShedding(CpuShedding cpuShedding) {
        this.cpuShedding = cpuShedding;
    }

    public Breaker getBreaker() {
        return breaker;
    }

    public void setBreaker(Breaker breaker) {
        this.breaker = breaker;
    }

    public OutboundDeadline getOutboundDeadline() {
        return outboundDeadline;
    }

    public void setOutboundDeadline(OutboundDeadline outboundDeadline) {
        this.outboundDeadline = outboundDeadline;
    }

    public RedisDeadline getRedisDeadline() {
        return redisDeadline;
    }

    public void setRedisDeadline(RedisDeadline redisDeadline) {
        this.redisDeadline = redisDeadline;
    }

    /**
     * Redis 的「请求期限感知」开关。
     *
     * <p>🆕 2026-10-30（G1 反馈单 2/3 · G4 lane）：见
     * {@link com.rag.common.redis.GoStyleRedisDeadline}。
     *
     * <p>⚠️ <b>本开关默认开启，且这一层让 RAG <b>比 B 侧 更严</b></b>（Jason 2026-10-30 明确批准）：
     * B 侧的 Redis 命令<b>本来就不受请求 deadline 约束</b>
     * （go-redis 的 {@code ContextTimeoutEnabled=false} ⇒ {@code baseClient.context(ctx)}
     * 把 ctx 换成 {@code context.Background()}）⇒ ⇒ 本开关让 RAG 在「请求预算已耗尽」时
     * <b>不再下发新的 Redis 命令</b> ⇒ ⇒ <b>这是一处有意偏离，不是「对齐」</b>
     * ⇒ 已登记为有意偏离候选（签字落点不归 G4 ⇒ 转合并收口执行者）。
     */
    public static class RedisDeadline {

        /**
         * 是否启用「请求预算耗尽 ⇒ 不再下发 Redis 命令」。
         *
         * <p>默认 <b>true</b>：因为它<b>只影响「请求已经死了」的那一小段</b>
         * ⇒ 对正常请求<b>零行为变化</b>。
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 出向 gRPC 的 deadline 传播（B 的 ctx 传播 ＋ 客户端上界）。
     *
     * <p>🆕 2026-10-30（E65 落码 · G4 lane）。
     *
     * <h2>B 出处</h2>
     * <pre>
     * // ① B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java:250:15   —— 客户端超时中间件默认开
     * Timeout    bool `json:",default=true"`
     * // ② B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java:252:115-116 —— client 侧装配
     * if c.middlewares.Timeout {
     *     interceptors = append(interceptors, clientinterceptors.TimeoutInterceptor(timeout))
     * }
     * // ③ B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java:256:70-71 —— 客户端默认上界
     * if c.Timeout &gt; 0 {
     *     opts = append(opts, WithTimeout(time.Duration(c.Timeout)*time.Millisecond))
     * }
     * // ④ B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java:260:31 —— 上界默认值
     * Timeout       int64           `json:",default=2000"`
     * </pre>
     *
     * <h2>为什么 {@code capMillis} 默认是 0（不设上界）</h2>
     * <p>B 的 2000ms 是<b>默认值</b>，而<b>各域可覆盖</b>（真源
     * {@code service/article/api/etc/article-api.yaml:12} = {@code Timeout: 70000}
     * ⇒ 那是 <b>api 侧出向 article-rpc 的客户端</b>上界；<b>注意别引错</b>：
     * {@code service/article/rpc/etc/article.yaml:3} = 70000 是<b>服务端</b>超时
     * （顶层、挨着 {@code ListenOn} ⇒ 落在 {@code zrpc.RpcServerConf.Timeout}），
     * 同文件 {@code :90} = 10000 才是<b>客户端</b>（在 {@code SecurityConf} 块内 ⇒
     * {@code zrpc.RpcClientConf}））⇒ ⇒ 把 2000ms 写成 RAG 的<b>全局默认</b>
     * 会让 article 的出向调用<b>比 B 侧 严格 35 倍</b>（70s → 2s）⇒ ⇒ **必须按域配**，
     * 而按域配 = 域侧 yml（各域 lane 的写权面）⇒ ⇒ 本轮共享层只提供<b>能力</b>，
     * <b>默认不启用上界</b>，逐域启用登记在 {@code docs/RoundPlan/} 的 P2-T04。
     * ⇒ ⚠️ 启用前这一轴仍是漂移（**不假装已对齐**）。
     */
    public static class OutboundDeadline {
        /**
         * 是否把<b>请求 deadline</b>（入向 ctx 的剩余）传播给出向 gRPC 调用。
         *
         * <p>B 出处：{@code service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java:281:25}（RAG 仓）
         * —— {@code context.WithTimeout(ctx, t)} ⇒ 父 ctx 的 deadline <b>参与 min</b>。
         */
        private boolean enabled = true;

        /**
         * 客户端硬上界（毫秒）；{@code 0} = <b>不设上界</b>（见本类 javadoc「为什么默认是 0」）。
         */
        private long capMillis = 0L;

        /**
         * <b>按下游 gRPC 服务全名</b>覆盖的客户端硬上界（毫秒）。
         *
         * <h2>为什么需要它（组件级一个值表达不了 per-direction 真值）</h2>
         * <p>{@link #capMillis} 是<b>组件级</b>一个值 ⇒ 而
         * {@link GoStyleOutboundDeadlineInterceptor} 带 devh 的
         * {@code @GrpcGlobalClientInterceptor} ⇒ 它装在<b>每一个</b>出向调用上
         * ⇒ ⇒ <b>同一个进程里不同出向方向无法有不同上界</b>。
         * <p>实测反例（RAG 仓 · 2026-10-31 · {@code article-api}）：
         * <pre>
         *   B 侧 service/article/api/etc/article-api.yaml
         *     ArticleRpcConf.Timeout: 70000   ⇒ article-rpc 方向 = 70s
         *     UserRpcConf:（无 Timeout）       ⇒ 走 zrpc struct tag 默认 2000 ⇒ user-rpc 方向 = 2s
         * </pre>
         * ⇒ ⇒ 组件级配 <b>2000</b> ⇒ 会把 article-rpc 方向从 70s <b>改严 35 倍</b>；
         * ⇒ ⇒ 组件级配 <b>70000</b> ⇒ 会把 user-rpc 方向从 2s <b>放宽 35 倍</b>
         * ⇒ ⇒ <b>两个值都不对</b> ⇒ ⇒ 该组件只能「刻意不配 + 域侧内联副本」⇒ ⇒ 那正是漂移面。
         *
         * <h2>键是什么、为什么是它</h2>
         * <p>键 = <b>下游 gRPC 服务全名</b>（proto 的 {@code package.Service}，如
         * {@code article.ArticleService} / {@code user.UserService}）⇒ ⇒ 理由 = 那是
         * <b>全局拦截器在拦截时唯一拿得到</b>的出向标识（{@code MethodDescriptor#getServiceName()}）
         * ⇒ ⇒ <b>域侧零代码改动</b>（只需配参数）⇒ ⇒ 与「域侧只配参数」的原则同形。
         *
         * <h2>语义 = <b>覆盖</b>（不是 min）</h2>
         * <p>某个服务名命中且值 <b>&gt; 0</b> ⇒ ⇒ <b>取代</b>组件级 {@link #capMillis}
         * ⇒ ⇒ 可<b>放宽</b>（70000 覆盖 2000）也可<b>收紧</b>（2000 覆盖 70000）。
         * <p>值 <b>≤ 0</b> 或<b>缺省</b> ⇒ ⇒ 回落组件级 {@link #capMillis}
         * ⇒ ⇒ <b>0 = 未配置</b>（与组件级 0 = 不启用同义）⇒ ⇒ 不引入第三种含义。
         *
         * <h2>B 出处（为什么是「每方向一个上界」）</h2>
         * <pre>
         *   B 出处：service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java:323:70-71
         *       if c.Timeout &gt; 0 { opts = append(opts, WithTimeout(time.Duration(c.Timeout)*time.Millisecond)) }
         *   B 出处：service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java:325:31
         *       Timeout int64 `json:",default=2000"` ⇒ 默认值，**各 client 段可各自覆盖**
         * </pre>
         * ⇒ ⇒ B 侧本就是<b>每个 client 段一个 Timeout</b> ⇒ ⇒ RAG 侧按下游服务名分档同形。
         */
        private Map<String, Long> capMillisByService = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getCapMillis() {
            return capMillis;
        }

        public void setCapMillis(long capMillis) {
            this.capMillis = capMillis;
        }

        /** 按下游 gRPC 服务全名覆盖的客户端硬上界（毫秒）；缺省为空 ⇒ 全部回落组件级。 */
        public Map<String, Long> getCapMillisByService() {
            return capMillisByService;
        }

        public void setCapMillisByService(Map<String, Long> capMillisByService) {
            this.capMillisByService = capMillisByService;
        }
    }

    /**
     * 对应 B 侧 {@code rest/config.go:56} 的 {@code CpuThreshold}（千分比）+ {@code core/load/adaptiveshedder.go:22}。
     *
     * <p><b>取值范围 {@code [0:1000)}</b> 与 B 的 tag 一致（{@code range=[0:1000)}）；
     * RAG 侧不做 tag 级校验，越界时 {@link #maxCpuPermille()} 会夹到 {@code [0,1000)}，
     * 因为「一个 1200 的阈值」在 B 侧是启动期配置错误、在 Java 侧若不夹会变成永不降载的静默失效。
     */
    public static class CpuShedding {
        /** {@code rest/config.go:22} {@code Middlewares.Shedding bool json:",default=true"}。 */
        private boolean enabled = true;

        /** {@code rest/config.go:56} {@code CpuThreshold int64 json:",default=900,range=[0:1000)"}。 */
        private long cpuThreshold = 900L;

        /**
         * CPU 口径。B 侧 {@code service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java:373:41-43} 的
         * {@code systemOverloadChecker} 读的是<b>整机</b> CPU（Linux 下 {@code /proc/stat}）。
         * <ul>
         *   <li>{@code SYSTEM}（默认，= B 侧）：{@code getSystemCpuLoad()}，整机口径。
         *       在容器里若宿主很忙但本进程很闲，RAG 与 B 侧 会<b>同样</b>降载 —— 这是刻意对齐。</li>
         *   <li>{@code PROCESS}：{@code getProcessCpuLoad()}，只看本 JVM。
         *       偏离 B 侧，但适合「多副本各自限流」的部署口径。</li>
         * </ul>
         */
        private CpuSource cpuSource = CpuSource.SYSTEM;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getCpuThreshold() {
            return cpuThreshold;
        }

        public void setCpuThreshold(long cpuThreshold) {
            this.cpuThreshold = cpuThreshold;
        }

        public CpuSource getCpuSource() {
            return cpuSource;
        }

        public void setCpuSource(CpuSource cpuSource) {
            this.cpuSource = cpuSource;
        }
    }

    /** {@code rest/config.go:15} {@code Middlewares.Breaker bool json:",default=true"}。 */
    public static class Breaker {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * <b>Stat 层</b>的开关（B 侧 {@code zrpc/internal/config.go:23}）。
     *
     * <p>B 出处：{@code service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java:425:23} {@code Stat bool `json:",default=true"`}
     * ⇒ 真源各域 yaml <b>没写</b> ⇒ 生效值 = <b>true</b>。
     *
     * <p>⚠️ <b>这个开关不进 {@code Composition}</b>：{@code Composition} 是 record，
     * 加字段会<b>破坏 8 个域级守门的编译</b>（域侧写权面）⇒
     * ⇒ 保护层的构成仍由 {@code compose()} 唯一计算；观测层的开关在
     * {@link GoStyleRpcProtectionChain#build} 内直接读本属性。
     */
    public static class Stat {

        /** B 侧 {@code zrpc/internal/config.go:23} {@code Stat bool `json:",default=true"`}。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * <b>Prometheus 层</b>的开关（B 侧 {@code zrpc/internal/config.go:24}）。
     *
     * <p>B 出处：{@code service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java:450:24} {@code Prometheus bool `json:",default=true"`}
     * ⇒ 真源各域 yaml <b>没写</b> ⇒ 生效值 = <b>true</b>。
     *
     * <p>⚠️ 关掉它<b>只</b>停掉「写指标」这一件事，<b>不</b>停掉「暴露面」
     * （暴露面由各域的 actuator 决定 ⇒ 域侧写权面）。
     */
    public static class Prometheus {

        /** B 侧 {@code zrpc/internal/config.go:24} {@code Prometheus bool `json:",default=true"`}。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** CPU 采样口径，见 {@link CpuShedding#getCpuSource()}。 */
    public enum CpuSource {        /** 整机 CPU，等价 B 侧 {@code /proc/stat} 口径。 */
        SYSTEM,
        /** 仅本 JVM 进程 CPU。 */
        PROCESS
    }

    /**
     * 夹到 B 的 tag 声明域 {@code [0,1000)}。
     *
     * <p>B 的 {@code range=[0:1000)} 会在<b>配置加载期</b>报错并拒绝启动；
     * Java 侧没有等价的 tag 校验，若照抄一个越界值，最可能的结果不是启动失败，
     * 而是「阈值永远达不到 ⇒ 永不降载」的<b>静默失效</b>（§3.3 同款形态）。
     *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/protection/GoStyleProtectionProperties.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 * <p><b>⚠️ 落点差异（与映射表 §1.8 的「N:1 并入 ProtectionAutoConfiguration」<b>刻意不同</b>）</b>：
 * 映射表把 {@code GoStyleProtectionProperties} 与 {@code GoStyleProtectionConfiguration} 一起
 * 归入 {@code ProtectionAutoConfiguration}（N:1）⇒ 但那是<b>装配入口</b>的归并；
 * <b>配置载体本身</b>在本仓<b>独立成类</b>（本文件）⇒ 理由：
 * <ol>
 *   <li><b>跨批次硬依赖</b>：{@code common/redis/DeadlineAutoConfiguration} 需要
 *       {@code props.getRedisDeadline().isEnabled()} ⇒ 若等批 3 才落，批 1 无法编译；</li>
 *   <li><b>生态惯例</b>：Spring Boot 的标准形态 = 「{@code @ConfigurationProperties} 类独立
 *       ＋ {@code @AutoConfiguration} 里 {@code @EnableConfigurationProperties(X.class)}」
 *       ⇒ 把配置类塞进 auto-config 类里是<b>反惯例</b>；</li>
 *   <li><b>本类无任何保护链实现</b>（纯配置载体 + 默认值）⇒ 不属于 D4「生态吸收」的对象
 *       ⇒ 与批 3 的 {@code ProtectionAutoConfiguration}（resilience4j 装配）<b>职责不重叠</b>。</li>
 * </ol>
 * ⇒ ⇒ 批 3 的 {@code ProtectionAutoConfiguration} 将以
 * <b>{@code @EnableConfigurationProperties(ProtectionProperties.class)}</b> 引用本类。
 *
 * <p><b>⚠️ 去 {@code GoStyle} 前缀</b>：命名债 P4-④ 同款处置（类名带域风格前缀是噪声）。
 */
    public long maxCpuPermille() {
        long t = cpuShedding.getCpuThreshold();
        if (t < 0) {
            return 0;
        }
        return Math.min(t, 999);
    }
}