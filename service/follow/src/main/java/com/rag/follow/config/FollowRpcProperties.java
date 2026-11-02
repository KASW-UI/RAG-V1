package com.rag.follow.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * <p><b>follow 域 gRPC 侧的业务配置</b> —— 逐条对应 B 侧
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/config/FollowRpcProperties.java}
 * （B 侧 128 行，本类逐字段对应）。
 *
 * <h2>B 出处与语义等价论证</h2>
 * <table border="1">
 *   <caption>字段 ↔ B 侧</caption>
 *   <tr><th>本类</th><th>B 侧行号</th><th>默认值</th></tr>
 *   <tr><td>{@link ListConf#getDefaultLimit()}</td><td>B 侧 63 / 68</td><td>20</td></tr>
 *   <tr><td>{@link ListConf#getMaxLimit()}</td><td>B 侧 65 / 68</td><td><b>100</b></td></tr>
 *   <tr><td>{@link RecommendConf#getDefaultLimit()}</td><td>B 侧 92 / 103</td><td>20</td></tr>
 *   <tr><td>{@link RecommendConf#getMaxLimit()}</td><td>B 侧 94 / 104</td><td><b>50</b></td></tr>
 *   <tr><td>{@link RecommendConf#getMaxDepth()}</td><td>B 侧 96 / 105</td><td>3</td></tr>
 *   <tr><td>{@link RecommendConf#getMaxFanout()}</td><td>B 侧 98 / 106</td><td>50</td></tr>
 * </table>
 *
 * <h2>⚠️ 这 6 个数值全部参与业务分支，不是性能开关</h2>
 * <p>它们决定「列表默认返回几行」「limit 被截断到多少」「推荐展开几层」「每层扇出多少」，
 * 每一项都会改变 RPC 的可观察输出。改 yaml = 改行为，必须走 review。
 *
 * <h2>⚠️ 为什么每个字段都带 {@link DefaultValue}</h2>
 * <p>B 侧这些默认值写在 <b>struct tag</b> 上，由配置加载器在 yaml 缺字段时应用 —— 即「不配也有值」。
 * C 侧若只写 yaml 而不给默认值，一旦某个部署环境裁掉该段就会变成 {@code 0}，
 * 而 {@code 0} 在下游有<b>确定且不同</b>的含义：
 * <ul>
 *   <li>{@code defaultLimit = 0} ⇒ {@code normalizeListReq} 不再替换 limit ⇒ <b>不限行数</b></li>
 *   <li>{@code maxLimit = 0} ⇒ {@code if maxLimit > 0 && limit > maxLimit} 整条短路 ⇒ <b>不截断</b></li>
 *   <li>{@code maxDepth = 0} ⇒ 被 {@code clampRecommendDepth} 夹回 <b>3</b>（救回来了）</li>
 *   <li>{@code maxFanout = 0} ⇒ 被 {@code clampFanout} 夹回 <b>50</b>（救回来了）</li>
 * </ul>
 * 前两项是<b>静默的行为变化</b>（默认多返回 / 不截断），所以默认值必须内建，不能只靠 yaml。
 *
 * <h2>⚠️ 绑定机制：构造器绑定（constructor binding）</h2>
 * <p>Spring Boot 3.5 的 {@link DefaultValue} 的注解目标是 <b>构造器参数</b>（不是字段）
 * ⇒ 本类与两个内部类各自<b>只声明一个带参构造器</b>
 * ⇒ Spring 的 {@code BindConstructorProvider} 会把它识别为绑定构造器
 * ⇒ 嵌套对象在 yaml 整段缺失时也会被创建（并用各自的默认值填充）。
 *
 * <p><b>⚠️ 绝不能再加第二个构造器</b>（例如 {@code @AllArgsConstructor} / {@code @NoArgsConstructor}）：
 * 一旦出现两个候选构造器，Spring 的构造器推导会返回「无绑定构造器」
 * ⇒ 退化成 JavaBean 绑定 ⇒ 本类没有 setter ⇒ <b>字段全是 null</b> ⇒ 运行期 NPE。
 * ⇒ ⇒ <b>本类只用 {@code @Getter}（生成 getter，不生成构造器）</b>，构造器一律手写。
 *
 * <h2>⚠️ 不校验取值</h2>
 * <p>B 侧同样没有校验，0 / 负数会照原样流进 {@code normalizeListReq} 并产生上面注释里描述的那套语义。
 * 在这里加 {@code @Min} 校验会把「不配 0」从「不限行数」变成「启动失败」—— 那是新增行为。
 *
 * <h2>⚠️ yaml 前缀 {@code follow}</h2>
 * <p>与 B 侧 {@code @ConfigurationProperties(prefix = "follow")} 逐字一致。
 * ⇒ 键名：{@code follow.list.default-limit} / {@code follow.list.max-limit} /
 * {@code follow.recommend.default-limit} / {@code follow.recommend.max-limit} /
 * {@code follow.recommend.max-depth} / {@code follow.recommend.max-fanout}。
 *
 * <p><b>⚠️ 跨 worker 依赖</b>：本类需要被注册成 Bean
 * ⇒ 启动类上必须有 {@code @EnableConfigurationProperties(FollowRpcProperties.class)}
 * （本仓 users 域的同款写法见 {@code com.rag.user.UserServiceApplication}），
 * 否则本类不会进容器，注入点会拿不到 Bean。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class FollowRpcProperties {
 *     private final ListConf list;
 *     private final RecommendConf recommend;
 *
 *     public FollowRpcProperties(@DefaultValue ListConf list, @DefaultValue RecommendConf recommend) {
 *         this.list = list;
 *         this.recommend = recommend;
 *     }
 *
 *     public ListConf getList() { return list; }
 *     public RecommendConf getRecommend() { return recommend; }
 * }
 * }</pre>
 */
@Getter
@ConfigurationProperties(prefix = "follow")
public class FollowRpcProperties {

    /** 对应 Go {@code ListConf}（GetFollowList / GetBlockList / GetFollowerList 共用）。 */
    private final ListConf list;

    /** 对应 Go {@code RecommendConf}（只有 GetRecommendations 用）。 */
    private final RecommendConf recommend;

    public FollowRpcProperties(@DefaultValue ListConf list, @DefaultValue RecommendConf recommend) {
        this.list = list;
        this.recommend = recommend;
    }

    /**
     * <p>对应 Go {@code type ListConf struct { DefaultLimit int32 `json:",default=20"`; MaxLimit int32 `json:",default=100"` }}。
     *
     * <p>两个字段<b>不校验</b>：B 侧同样没有校验，0 / 负数会照原样流进
     * {@code normalizeListReq} 并产生上面注释里描述的那套语义。
     * 在这里加 {@code @Min} 校验会把「不配 0」从「不限行数」变成「启动失败」—— 那是新增行为。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class ListConf {
     *     private final int defaultLimit;
     *     private final int maxLimit;
     *
     *     public ListConf(@DefaultValue("20") int defaultLimit, @DefaultValue("100") int maxLimit) {
     *         this.defaultLimit = defaultLimit;
     *         this.maxLimit = maxLimit;
     *     }
     *
     *     public int getDefaultLimit() { return defaultLimit; }
     *     public int getMaxLimit() { return maxLimit; }
     * }
     * }</pre>
     */
    @Getter
    public static class ListConf {

        /** 对应 Go {@code DefaultLimit int32 `json:",default=20"`}。 */
        private final int defaultLimit;

        /** 对应 Go {@code MaxLimit int32 `json:",default=100"`}。 */
        private final int maxLimit;

        public ListConf(@DefaultValue("20") int defaultLimit, @DefaultValue("100") int maxLimit) {
            this.defaultLimit = defaultLimit;
            this.maxLimit = maxLimit;
        }
    }

    /**
     * <p>对应 Go {@code type RecommendConf struct { DefaultLimit int32 `json:",default=20"`;
     * MaxLimit int32 `json:",default=50"`; MaxDepth int `json:",default=3"`; MaxFanout int `json:",default=50"` }}。
     *
     * <p>⚠️ {@code MaxLimit} 是 <b>50</b>，与 {@link ListConf#getMaxLimit()} 的 100 <b>不是同一个值</b>。
     * B 侧它们分属两个 struct，推荐接口的天花板比列表接口低一半。
     *
     * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * public static class RecommendConf {
     *     private final int defaultLimit;
     *     private final int maxLimit;
     *     private final int maxDepth;
     *     private final int maxFanout;
     *
     *     public RecommendConf(@DefaultValue("20") int defaultLimit,
     *                         @DefaultValue("50") int maxLimit,
     *                         @DefaultValue("3") int maxDepth,
     *                         @DefaultValue("50") int maxFanout) {
     *         this.defaultLimit = defaultLimit;
     *         this.maxLimit = maxLimit;
     *         this.maxDepth = maxDepth;
     *         this.maxFanout = maxFanout;
     *     }
     *
     *     public int getDefaultLimit() { return defaultLimit; }
     *     public int getMaxLimit() { return maxLimit; }
     *     public int getMaxDepth() { return maxDepth; }
     *     public int getMaxFanout() { return maxFanout; }
     * }
     * }</pre>
     */
    @Getter
    public static class RecommendConf {

        /** 对应 Go {@code DefaultLimit int32 `json:",default=20"`}。 */
        private final int defaultLimit;

        /** 对应 Go {@code MaxLimit int32 `json:",default=50"`}。 */
        private final int maxLimit;

        /** 对应 Go {@code MaxDepth int `json:",default=3"`}。 */
        private final int maxDepth;

        /** 对应 Go {@code MaxFanout int `json:",default=50"`}。 */
        private final int maxFanout;

        public RecommendConf(@DefaultValue("20") int defaultLimit,
                             @DefaultValue("50") int maxLimit,
                             @DefaultValue("3") int maxDepth,
                             @DefaultValue("50") int maxFanout) {
            this.defaultLimit = defaultLimit;
            this.maxLimit = maxLimit;
            this.maxDepth = maxDepth;
            this.maxFanout = maxFanout;
        }
    }
}
