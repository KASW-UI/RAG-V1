package com.rag.common.protection;

import com.rag.common.observability.GoStyleRequestDeadline;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Deadline;
import io.grpc.MethodDescriptor;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.TimeUnit;

/**
 * <b>L4 idiomatization：把「请求 deadline 传播给出向 gRPC 调用」做成 client 侧拦截器。</b>
 *
 * <h2>它解决的是什么（E65）</h2>
 * <p>B 侧的出向 gRPC 调用<b>一律沿用（派生自）请求 ctx</b> ⇒ deadline 随 ctx 传播 ⇒
 * 请求超时后，<b>出向调用立即失败</b>。而 RAG 侧此前<b>没有 ctx</b> ⇒ 出向调用要么
 * 没有 deadline（裸 stub）、要么要靠每个调用点<b>手工</b>调
 * {@link GoStyleRequestDeadline#applyTo} ⇒ ⇒ <b>7 个类 / 27 处调用点是裸的</b>
 * ⇒ ⇒ <b>下游挂住时请求挂住</b>。
 *
 * <p>本类把这件事收进<b>共享层</b>：出向调用在<b>创建时</b>统一按
 * <b>min(请求剩余, 调用点自带, 客户端上界)</b> 算一次 deadline ⇒ ⇒
 * <b>域侧不用改一行</b>。
 *
 * <h2>B 出处（三段，逐行核对过）</h2>
 * <pre>
 * // ① B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleOutboundDeadlineInterceptor.java:31:25
 * //    —— 出向调用的 deadline = min(父 ctx 的 deadline, 本层的 timeout)
 * ctx, cancel := context.WithTimeout(ctx, t)
 * defer cancel()
 *
 * // ② B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleOutboundDeadlineInterceptor.java:36:115-116
 * //    —— 客户端超时中间件（默认开，① 装在**每个** zrpc client 上）
 * if c.middlewares.Timeout {
 *     interceptors = append(interceptors, clientinterceptors.TimeoutInterceptor(timeout))
 * }
 * //    开关默认值：B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleOutboundDeadlineInterceptor.java:41:15  Timeout bool `json:",default=true"`
 *
 * // ③ B 侧：service/common/src/main/java/com/rag/common/protection/GoStyleOutboundDeadlineInterceptor.java:43:70-71 ＋ zrpc/config.go:31
 * //    —— 客户端上界（默认 2000ms，**各域可覆盖**）
 * if c.Timeout &gt; 0 {
 *     opts = append(opts, WithTimeout(time.Duration(c.Timeout)*time.Millisecond))
 * }
 * //    Timeout       int64           `json:",default=2000"`
 * </pre>
 * <p>（<b>B 侧仓</b> <b>不包含</b> {@code zrpc} ——
 * 那是框架自身的包；B 侧仓 通过 {@code go.mod} 锁定框架的 v1.10.2 版本
 * ⇒ 上面的行号对应<b>该版本</b>的框架源码。）
 *
 * <h2>语义等价论证</h2>
 * <ul>
 *   <li><b>「继承」这一面</b>：B 侧出向 deadline ⊇ 父 ctx 的 deadline（① 的 min 语义）；
 *       本类把请求载体的剩余纳秒纳入 min ⇒ <b>两侧同形</b>。</li>
 *   <li><b>「不覆盖」这一面</b>：B 的 {@code context.WithTimeout} <b>取更早者</b>，从不覆盖父 deadline；
 *       本类同样取 min（请求剩余 / 调用点自带 / 上界 三者取最小）⇒ <b>不会把调用点已声明的
 *       deadline 放宽</b>。</li>
 *   <li><b>「无预算不设限」这一面</b>：B 侧 {@code WithTimeout} 只在 {@code c.Timeout > 0} 时才加；
 *       本类在<b>三项都没有</b>时<b>原样返回</b> {@link CallOptions} ⇒ 不给调用凭空加约束。</li>
 * </ul>
 *
 * <h2>为什么放在共享层（而不是让各域改调用点）</h2>
 * <p>B 侧这件事是<b>拦截器级</b>的（②：每个 zrpc client 都装）⇒ RAG 侧同形也应是
 * <b>拦截器级</b>。若改成「每个调用点手工 {@code applyTo}」⇒ 27 处调用点要逐个改
 * ⇒ 漏一处就是一个静默漂移 ⇒ ⇒ <b>不取</b>。
 *
 * <h2>装配面</h2>
 * <p>本类带 devh 的 {@code @GrpcGlobalClientInterceptor}
 * ⇒ 由 {@code AnnotationGlobalClientInterceptorConfigurer} 按<b>类上注解</b>收集
 * ⇒ 再由 {@code GlobalClientInterceptorRegistry} 交给 channel factory
 * ⇒ ⇒ <b>装配面</b>见 B 侧 {@code GoStyleOutboundDeadlineConfiguration}；
 * C 侧对应面 = {@link com.rag.common.autoconfigure.ClientProtectionAutoConfiguration}。
 * ⚠️ <b>Bean 存在 ≠ 机制生效</b>（AGENTS.md §3.3）⇒ 锁定用例 =
 * {@code ClientProtectionAutoConfigurationTest#devhCollectorReallyPicksUpBothInterceptors}
 * （断言它真进了 devh 的收集器 registry）。
 * <p>🔴 <b>2026-10-30 订正（第十一次）·「registry 里有」仍只是<b>装配链的推断</b></b>：
 * devh 把全局拦截器接在 <b>channel</b> 上 ⇒ ⇒ 凡建在 devh channel 上的 stub
 * —— <b>哪怕是「裸」写法、不调 {@code applyTo}</b> —— 都经过拦截器链
 * ⇒ ⇒ 「<b>域侧不用改一行</b>」这条才是真的。
 * ⇒ ⇒ 因此<b>应该</b>补一条<b>运行期</b>锁定用例（真 devh 上下文 ＋ 真 {@code @GrpcClient} stub
 * ＋ <b>不调 {@code applyTo}</b> ⇒ ⇒ 断言出向调用在请求预算处失败；负控＝裸 channel 挂满
 * ⇒ ⇒ 变异＝去掉 {@code @GrpcGlobalClientInterceptor} ⇒ ⇒ 该守门<b>精确转红</b>）。
 * <p>🔴 <b>⚠️ C 侧现状：这条运行期用例尚未落地</b> ——
 * C 侧现有的是①<b>装配层</b>守门（{@code ClientProtectionAutoConfigurationTest}）
 * ②<b>单元级</b>守门（{@code RpcProtectionLayerTest} 的出向 deadline 3 例）
 * ⇒ ⇒ 「devh 真实链路上的运行期差分」目前<b>无网</b>（登记见 common 域台账 DRIFT-COMMON-V18）。
 * <p>🔴 <b>2026-10-30 订正（第十三次）·「已过期 ⇒ 立即失败」这条分支只靠一个字符撑着</b>：
 * {@link GoStyleRequestDeadline#remainingNanos()} 在<b>已过期</b>时返回的是
 * <b>{@code 0}</b>（{@code Math.max(0L, d - now)}）⇒ <b>不是负数</b>
 * ⇒ ⇒ 本类里 {@code if (requestRemaining >= 0L)} <b>成立</b>
 * ⇒ ⇒ 预算被压成 {@code 0} ⇒ ⇒ 命中「预算已耗尽 ⇒ 立即失败」分支
 * ⇒ ⇒ <b>「载体已过期 ⇒ 出向调用立即失败」这条路径是已实现的</b>
 * ⇒ ⇒ ⚠️ <b>但若有人把那个 {@code >= 0L} 改成 {@code > 0L}</b>
 * ⇒ ⇒ 过期值（{@code 0}）会被跳过 ⇒ ⇒ <b>没有任何预算</b>
 * ⇒ ⇒ 出向调用<b>静默退回「挂满」</b>（实测挂满 5s ⇒ 失败码 {@code CANCELLED}）
 * ⇒ ⇒ <b>而这一行离「语义正确」只差一个字符 ⇒ 极易被无声改坏</b>
 * ⇒ ⇒ 因此<b>应该</b>补一条<b>运行期</b>锁定用例
 * （上游＝<b>真</b> B 侧 {@code com.rag.common.observability.RpcInterceptor} ＋ 下游＝devh channel ＋
 * <b>3×2 行为差分</b>＋「未过期 ⇒ 继承剩余预算」的互补分支
 * ⇒ ⇒ 变异①＝拿掉载体绑定 ⇒ 形态①④ 转红；变异②＝{@code >= 0L} ⇒ {@code > 0L}
 * ⇒ ⇒ <b>只有</b>形态①转红 ⇒ ⇒ 两个变异互不重叠 ⇒ ⇒ 判据活着）。
 * <p>🔴 <b>⚠️ C 侧现状：这条运行期用例尚未落地</b>（同前一条）
 * ⇒ ⇒ 「{@code >= 0L} 这道守卫」这条关键分支目前<b>无网</b>。
 *
 * <h2>效力边界（诚实声明）</h2>
 * <ol>
 *   <li><b>客户端硬上界（B 的 2000ms）本轮默认不启用</b> ——
 *       {@link ProtectionProperties.OutboundDeadline#getCapMillis()} 默认 0
 *       ⇒ 理由 = B 的 2000ms 是<b>默认值</b>而<b>各域可覆盖</b>（真源
 *       {@code service/article/api/etc/article-api.yaml:12} = 70000 ⇒ 那是
 *       <b>api 侧出向 article-rpc 的客户端</b>上界；⚠️ <b>别引错</b>：
 *       {@code service/article/rpc/etc/article.yaml:3} = 70000 是<b>服务端</b>超时
 *       （顶层挨着 {@code ListenOn}），同文件 {@code :90} = 10000 才是客户端）
 *       ⇒ 写成全局默认会让 article 比 B 侧 严 35 倍
 *       ⇒ ⇒ 逐域启用登记在 P2-T04（域侧 yml = 各域 lane 的写权面）
 *       ⇒ ⇒ <b>这一轴仍是漂移，不假装已对齐</b>。</li>
 *   <li><b>依赖「调用发生在带请求载体的线程上」</b>：本类读的是 ThreadLocal
 *       （{@link GoStyleRequestDeadline#remainingNanos()}）⇒ 若是<b>业务自己换线程</b>
 *       （如 hot-api 的补全跑在线程池 worker 上）⇒ 读不到载体 ⇒ 本类不生效
 *       ⇒ 那种形态仍需<b>显式传参</b>（已在 {@code HotRpcClient} 里实现）。</li>
 *   <li><b>只覆盖本进程发起的出向调用</b>：不改变<b>入向</b> server 侧的任何语义。</li>
 * </ol>
 */
@GrpcGlobalClientInterceptor
public class OutboundDeadlineInterceptor implements ClientInterceptor {

    private final boolean enabled;
    private final long capMillis;

    /**
     * <b>按下游 gRPC 服务全名</b>覆盖的客户端硬上界（毫秒）。
     *
     * <p>见 {@link ProtectionProperties.OutboundDeadline#getCapMillisByService()} 的 javadoc
     * （为什么组件级一个值表达不了 per-direction 真值 · 为什么键是服务全名 · 语义是「覆盖」不是 min）。
     */
    private final java.util.Map<String, Long> capMillisByService;

    /**
     * Spring 注入点。
     *
     * <p>⚠️ <b>必须显式标注 {@code @Autowired}</b>：本类有第二个构造器（给测试用），
     * Spring 在<b>多个</b>构造器且都无 {@code @Autowired} 时会退回找无参构造器 ⇒
     * 找不到就抛 {@code No default constructor found} ⇒ <b>整个上下文起不来</b>
     * （与 {@link ClientCircuitGateInterceptor} 同款缺陷，由
     * {@code GoStyleGrpcClientBreakerWiringTest} 抓出来过）。
     */
    @Autowired
    public OutboundDeadlineInterceptor(ProtectionProperties props) {
        this.enabled = props.getOutboundDeadline().isEnabled();
        this.capMillis = props.getOutboundDeadline().getCapMillis();
        java.util.Map<String, Long> byService = props.getOutboundDeadline().getCapMillisByService();
        this.capMillisByService = byService == null
                ? java.util.Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(byService));
    }

    /**
     * <b>公开</b>的「直接给开关与上界」构造器 —— 供<b>探针与共享层外的守门</b>复用<b>本类</b>。
     *
     * <p>🔴 <b>2026-10-30 · G4 lane：为什么从 package-private 改成 public</b>：
     * `tools/semantic-probe/deadline-downstream/` 的 RAG 半边要量「出向调用继承请求 deadline」
     * 这件事 ⇒ ⇒ 它必须装<b>生产这个类</b> ⇒ ⇒ 而探针不在 {@code com.rag.common.protection} 包里
     * ⇒ ⇒ **package-private 构造器它用不了** ⇒ ⇒ ⇒ 若不放开，探针只有两条路：
     * <b>① 在探针里自己写一份等价拦截器</b>（⇒ 那它量的是**副本**不是产品 ⇒ ⇒ 又一处漂移面）
     * ／ <b>② 起真 devh 上下文</b>（⇒ 探针变成集成测试 ⇒ ⇒ 与 B 侧 半边【独立进程】不对称
     * ⇒ ⇒ 双侧对拍失去公平性）⇒ ⇒ **两条都更差** ⇒ ⇒ ⇒ 故本构造器<b>公开</b>，
     * 让「探针复用生产类」这条路成立。
     *
     * <p>⚠️ <b>两个参数的口径必须与生产默认一致</b>：{@code enabled=true}（B 侧
     * {@code service/common/src/main/java/com/rag/common/protection/GoStyleOutboundDeadlineInterceptor.java:166:15} 的 {@code Timeout} 默认开）＋ {@code capMillis=0}
     * （客户端硬上界默认不启用，理由见 {@link ProtectionProperties.OutboundDeadline}）
     * ⇒ ⇒ **与 {@link ProtectionProperties} 的出厂默认同形**。
     *
     * @param enabled  出向 deadline 传播是否开启
     * @param capMillis 客户端硬上界（毫秒）；{@code 0} = 不启用
     */
    public OutboundDeadlineInterceptor(boolean enabled, long capMillis) {
        this(enabled, capMillis, java.util.Map.of());
    }

    /**
     * <b>公开</b>的「直接给开关、组件级上界、按服务名覆盖表」构造器。
     *
     * <p>🆕 2026-10-31（合并收口执行者 · 代 G4 落 per-client 上界）：
     * 组件级一个 {@code capMillis} 表达不了「同一进程不同出向方向不同上界」
     * ⇒ ⇒ 故补这张按<b>下游 gRPC 服务全名</b>的覆盖表。
     *
     * @param enabled         出向 deadline 传播是否开启
     * @param capMillis       组件级客户端硬上界（毫秒）；{@code 0} = 不启用
     * @param capMillisByService 按下游服务全名覆盖的上界（毫秒）；命中且 {@code > 0} ⇒ <b>取代</b>组件级
     */
    public OutboundDeadlineInterceptor(boolean enabled, long capMillis,
                                              java.util.Map<String, Long> capMillisByService) {
        this.enabled = enabled;
        this.capMillis = capMillis;
        this.capMillisByService = capMillisByService == null
                ? java.util.Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(capMillisByService));
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {
        // ⚠️ **服务全名 = 全局拦截器在拦截时唯一拿得到的出向标识**
        //    （MethodDescriptor#getServiceName）⇒ ⇒ per-service 覆盖表不需要域侧任何代码改动
        return next.newCall(method, effectiveCallOptions(callOptions, method.getServiceName()));
    }

    /**
     * 按 <b>min</b> 语义算出这次出向调用的 deadline（<b>不</b>应用 per-service 覆盖 ⇒ 回落组件级）。
     *
     * <p>⚠️ <b>为什么必须显式算 min 而不是直接覆盖</b>：grpc-java 的
     * {@link CallOptions#withDeadlineAfter(long, TimeUnit)} 是<b>覆盖</b>语义
     * ⇒ 若直接用它写「请求剩余」，会把调用点自己声明的<b>更早</b> deadline <b>放宽</b>
     * ⇒ ⇒ 那就与 B 的 {@code context.WithTimeout}（取更早者）<b>反向</b>了。
     *
     * @param callOptions 调用点原本的选项（可能已带 deadline，例如
     *                    {@link GoStyleRequestDeadline#applyTo} 的产物）
     * @return 带上 min 后 deadline 的新选项；<b>三项都没有预算时原样返回</b>
     */
    CallOptions effectiveCallOptions(CallOptions callOptions) {
        return effectiveCallOptions(callOptions, null);
    }

    /**
     * 按 <b>min</b> 语义算出这次出向调用的 deadline；<b>并</b>按下游服务全名应用 per-service 覆盖。
     *
     * <h2>⚠️ 两处「取 min」与一处「覆盖」必须分清</h2>
     * <table border="1">
     *   <caption>本方法的算术</caption>
     *   <tr><th>项</th><th>关系</th><th>理由</th></tr>
     *   <tr><td>入向请求预算</td><td>与 cap <b>取 min</b></td>
     *       <td>B 的 {@code context.WithTimeout(ctx, t)} ⇒ 取更早者</td></tr>
     *   <tr><td>调用点自带 deadline</td><td>与上面 <b>取 min</b></td>
     *       <td>同上（不能覆盖调用点自己声明的更早 deadline）</td></tr>
     *   <tr><td><b>per-service 上界 vs 组件级上界</b></td><td><b>覆盖</b>（不是 min）</td>
     *       <td>否则 per-service 永远赢不了组件级 ⇒ 「按方向分档」就<b>表达不出来</b>：
     *       组件级 2000 + per-service 70000 若取 min ⇒ 仍是 2000 ⇒ article-rpc 方向被改严 35 倍</td></tr>
     * </table>
     *
     * @param callOptions 调用点原本的选项
     * @param serviceName 下游 gRPC 服务全名（proto 的 {@code package.Service}）；
     *                    {@code null} ⇒ 不应用 per-service 覆盖（回落组件级）
     * @return 带上 min 后 deadline 的新选项
     */
    CallOptions effectiveCallOptions(CallOptions callOptions, String serviceName) {
        if (!enabled) {
            return callOptions;
        }
        long budgetNanos = Long.MAX_VALUE;

        // ① 请求载体（B 的 ctx 传播）：入向 deadline 的剩余纳秒
        long requestRemaining = GoStyleRequestDeadline.remainingNanos();
        if (requestRemaining >= 0L) {
            budgetNanos = Math.min(budgetNanos, requestRemaining);
        }

        // ② 客户端硬上界（B 的 zrpc client Timeout；本轮默认 0 = 不启用，见类 javadoc）
        //    ⚠️ **per-service 是「覆盖」组件级，不是 min**（理由见本方法 javadoc 的表）
        long effectiveCap = capMillis;
        if (serviceName != null) {
            Long perService = capMillisByService.get(serviceName);
            if (perService != null && perService > 0L) {
                effectiveCap = perService;
            }
        }
        if (effectiveCap > 0L) {
            budgetNanos = Math.min(budgetNanos, TimeUnit.MILLISECONDS.toNanos(effectiveCap));
        }

        // ③ 调用点自己声明的 deadline（= applyTo(stub) 的产物）⇒ 必须取 min，不能覆盖
        Deadline existing = callOptions.getDeadline();
        if (existing != null) {
            long existingRemaining = existing.timeRemaining(TimeUnit.NANOSECONDS);
            budgetNanos = Math.min(budgetNanos, Math.max(0L, existingRemaining));
        }

        if (budgetNanos == Long.MAX_VALUE) {
            // 三项都没有 ⇒ 不给这次调用凭空加约束（B 侧 WithTimeout 只在 c.Timeout > 0 时才加）
            return callOptions;
        }
        if (budgetNanos <= 0L) {
            // 预算已耗尽 ⇒ B 侧 ctx 已到期 ⇒ 立刻失败（与 GoStyleRequestDeadline.applyTo 的同款处理）
            return callOptions.withDeadlineAfter(1L, TimeUnit.NANOSECONDS);
        }
        return callOptions.withDeadlineAfter(budgetNanos, TimeUnit.NANOSECONDS);
    }
}
