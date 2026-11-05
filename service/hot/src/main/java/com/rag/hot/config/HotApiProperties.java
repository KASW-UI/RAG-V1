package com.rag.hot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * <p><b>hot 域 HTTP 层的配置聚合点</b> —— 只承载<b>一个</b>业务阈值：
 * 出向 {@code article-rpc} 的<b>客户端单次调用上界</b>。
 *
 * <p><b>B 出处</b>：B 侧<b>没有</b>这个类 —— 同一件事在 B 侧落在
 * {@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/client/GoStyleGrpcCallDeadline.java}
 * 的 javadoc 里 + {@code service/hot/hot-api/src/main/resources/application.yml} 的
 * {@code rag.protection.outbound-deadline.cap-millis: 2000}。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>类形态</b>：B 侧的 deadline 逻辑是一个<b>独立类</b>
 *             （{@code GoStyleGrpcCallDeadline}，只做「把请求剩余预算挂到 stub 上」）；
 *             本仓<b>不建独立类</b>（映射表 {@code docs/Mapping_Table/hot/hot.md} §1.2 第 8 行
 *             已拍板「落进配置，无独立类」）⇒ 改由本类承载<b>上界数值</b>、
 *             由 {@link com.rag.hot.client.ArticleRpcClient} 在<b>唯一出口</b>消费。</li>
 *         <li><b>Lombok 化</b>：{@code @Getter} / {@code @Setter} 替掉手写 accessor
 *             （展开块见下方）。</li>
 *         <li><b>去掉 {@code @Component}</b>：由应用入口的
 *             {@code @ConfigurationPropertiesScan("com.rag.hot.config")} 统一注册
 *             ⇒ 否则同一个类型注册两个 bean ⇒ {@code NoUniqueBeanDefinitionException}。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>数值逐字相同</b>：{@link ArticleRpc#getTimeout()} 默认 <b>2000ms</b>，
 *             = B 侧 zrpc 的 {@code RpcClientConf.Timeout int64 `json:",default=2000"`}
 *             （B 侧 {@code etc/hotcenter.yaml} 的 {@code HotRpcConf} / {@code ArticleRpcConf}
 *             <b>都没写</b> {@code Timeout} ⇒ 生效值来自 struct tag ⇒ 见下方「B 出处」三段）。</li>
 *         <li><b>生效位置等价</b>：B 侧 = 共享层出向拦截器按
 *             {@code rag.protection.outbound-deadline.cap-millis} 取 min；
 *             本仓 = {@link com.rag.hot.client.ArticleRpcClient} 在出向调用点取
 *             {@code min(本值, 请求剩余预算)} ⇒ ⇒ <b>有效上界同为 2000ms</b>。</li>
 *       </ul>
 *       ⚠️ <b>为什么不配共享层的 {@code rag.protection.outbound-deadline.cap-millis}</b>：
 *       那会造出<b>第二个上界来源</b>（域侧私有常量 + 共享层参数）⇒ 违反
 *       「cap 单一来源」纪律 ⇒ ⇒ 本仓<b>只</b>保留域侧这一处，
 *       共享层的 {@code cap-millis} 保持<b>未配置</b>（出厂默认 0 = 不启用）。
 *       ⇒ 该差异（配置键位不同、有效值相同）登记在 hot 域 DRIFT 台账。</li>
 *   <li><b>B 出处</b>：见下方「B 出处」三段。</li>
 * </ol>
 *
 * <p><b>B 出处（三段，缺一段就得不出「2000」这个数）</b>：
 * <pre>
 *   ① zrpc/config.go:31            RpcClientConf.Timeout int64 `json:",default=2000"`
 *                                  // etc/hotcenter.yaml 的 HotRpcConf / ArticleRpcConf 都**没写** Timeout
 *                                  // ⇒ 生效值来自 struct tag，不是 yaml（AGENTS.md §3.3.1 的典型形态）
 *   ② zrpc/internal/config.go:15   ClientMiddlewaresConf.Timeout bool `json:",default=true"`
 *   ③ zrpc/internal/clientinterceptors/timeoutinterceptor.go:20-25
 *          ctx, cancel := context.WithTimeout(ctx, t)
 * </pre>
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * @ConfigurationProperties(prefix = "hot-api")
 * public class HotApiProperties {
 *
 *     private ArticleRpc articleRpc = new ArticleRpc();
 *     private long domainQueryTimeout = 2000L;
 *
 *     public ArticleRpc getArticleRpc() { return articleRpc; }
 *     public void setArticleRpc(ArticleRpc articleRpc) { this.articleRpc = articleRpc; }
 *
 *     public long getDomainQueryTimeout() { return domainQueryTimeout; }
 *     public void setDomainQueryTimeout(long domainQueryTimeout) { this.domainQueryTimeout = domainQueryTimeout; }
 *
 *     public static class ArticleRpc {
 *         private long timeout = 2000L;
 *
 *         public long getTimeout() { return timeout; }
 *         public void setTimeout(long timeout) { this.timeout = timeout; }
 *     }
 * }
 * }</pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hot-api")
public class HotApiProperties {

    /** 出向 {@code article-rpc} 的客户端配置。 */
    @NestedConfigurationProperty
    private ArticleRpc articleRpc = new ArticleRpc();

    /**
     * <p><b>域内查询方向（hot-api → hot-rpc 域内直连）的单次调用上界</b>，单位毫秒，默认 <b>2000</b>。
     *
     * <p>⚠️ <b>为什么需要它</b>：B 侧这条方向走 gRPC ⇒ 客户端出向 deadline
     * （{@code rag.protection.outbound-deadline.cap-millis} = 2000）⇒ <b>Redis 卡住时 2s 就失败</b>
     * ⇒ hot-api 侧收到失败 ⇒ 走 fatal ⇒ <b>HTTP 200 + 5001 业务信封</b>。
     * <p>⚠️ <b>本仓 D2 域内直连</b> ⇒ 没有 gRPC ⇒ <b>没有任何出向上界</b>
     * ⇒ Redis 自己要等到 socket timeout（3s）才抛 ⇒ 而 HTTP 层的请求预算也是 3s
     * ⇒ ⇒ <b>两者同时到点</b> ⇒ 落进共享层的「HTTP 预算 × 业务失败」竞态（F-15）
     * ⇒ ⇒ 客户端可能拿到 <b>503 + 纯文本</b>（不是业务信封）。
     * <p>⇒ 本值把这条方向的失败<b>拉回到 2s</b> ⇒ ⇒ 失败形状与 B 侧<b>逐条一致</b>
     * （200 + 5001 信封），且<b>早于</b> HTTP 预算 3s ⇒ ⇒ 不再撞上那个竞态。
     *
     * <p>⚠️ <b>与 {@link ArticleRpc#getTimeout()} 为什么是两个键</b>：B 侧只有<b>一个</b>
     * {@code cap-millis}（共享层参数）统一管所有出向；本仓两个方向的<b>传输形态不同</b>
     * （一个走 gRPC、一个是同进程调用）⇒ 共享层那个拦截器<b>管不到域内方向</b>
     * ⇒ ⇒ 只能域内自持 ⇒ 拆成两个键，<b>值同为 2000</b>（= B 侧的同一个数）。
     */
    private long domainQueryTimeout = 2000L;

    /**
     * <p><b>出向 {@code article-rpc} 的客户端单次调用上界</b>。
     *
     * <p>⚠️ <b>单位是毫秒</b>（B 侧 zrpc 的 {@code Timeout} 也是毫秒）。
     *
     * <p><b>语义</b>：出向 {@code article-rpc} 的 deadline =
     * {@code min(本值, 请求剩余预算)} ⇒ 与 B 侧
     * {@code context.WithTimeout(ctx, 2s)} 嵌在请求 ctx 之上的行为<b>逐条等价</b>
     * （两者都是「先到者生效」）。
     */
    @Getter
    @Setter
    public static class ArticleRpc {

        /**
         * 客户端单次调用上界（毫秒）—— 默认 <b>2000</b>（B 侧 zrpc struct tag 默认值）。
         *
         * <p>⚠️ <b>不要</b>把它调小到「以为能省资源」：B 侧是 2s，
         * 调小会让「慢 article」从「skip 该条」变成「更频繁地 skip 该条」。
         */
        private long timeout = 2000L;
    }
}
