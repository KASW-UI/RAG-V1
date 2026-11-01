package com.rag.common.autoconfigure;

import com.rag.common.snowflake.Snowflake;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * <p><b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/spring/SnowflakeBean.java:7:22-38}
 * （{@code func Init()} ⇒ {@code sync.Once} 懒加载、进程内<b>唯一</b>一份 {@code snowflake.Node}）
 * ＋ {@code service/common/snowflake/snowflake.go:53-59}（{@code generateNodeId()} 的 {@code SNOWFLAKE_NODE_ID} 档）。
 *
 * <p>workerId 来源优先级：
 * <ol>
 *   <li>环境变量 {@code SNOWFLAKE_NODE_ID}（运维契约）</li>
 *   <li>{@link SnowflakeProperties#getWorkerId()}（application.yml / Spring properties）</li>
 *   <li>兜底 hash：{@code SERVICE_NAME + PID} sha256 前 2 字节 % 1024</li>
 * </ol>
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ L4 idiomatization：RAG 的 {@code SnowflakeBean} + {@code SnowflakeProperties} → 本 auto-config</b>
 * ══════════════════════════════════════════════════════════════════════
 * <ol>
 *   <li><b>两个 RAG 类合并为一个 auto-config</b>（映射表 §1.1 记的是 {@code N:1}）：
 *       RAG 侧 {@code SnowflakeBean}（81 行）＋ {@code SnowflakeProperties}（41 行）
 *       ⇒ C 侧 1 个 {@code SnowflakeAutoConfiguration}，配置载体作为<b>嵌套类</b>存在
 *       ⇒ ⇒ 不新增文件（C 列仍是 71 类）；</li>
 *   <li><b>去掉 {@code @Component}</b>：共享层是 starter（D3）⇒ 消费方不再扫
 *       {@code com.rag.common} ⇒ {@code @Component} 永远扫不到 ⇒ 配置绑不上；</li>
 *   <li><b>配置绑定改由 {@code @EnableConfigurationProperties}</b> 承担 ⇒ 消费方零装配代码。</li>
 * </ol>
 *
 * <p><b>⚠️ 「全进程只有一份生成状态」这条不变式怎么保住</b>
 * <p>RAG 侧 {@code SnowflakeBean} 此前<b>自己持有</b>一份 {@code State}，而 {@code Snowflake} 里
 * 还有另一份静态 {@code HOLDER.state}，靠 {@code bindBean} 决定走哪边 ⇒
 * 后果是同一 JVM 可能有两个不同 workerId 的生成器交替产 ID。
 * <br>⇒ 本仓的 {@code Snowflake}（批 1 已落）把生成状态放在<b>唯一的静态</b> {@code STATE} 里
 * ⇒ ⇒ <b>无论容器里注册几个 {@code Snowflake} 实例，生成状态始终只有那一份</b>
 * ⇒ ⇒ 双计数器风险在结构上就不存在。
 * <br>⇒ ⚠️ 但<b>「容器里 {@code Snowflake} 有且仅有 1 个」</b>（映射表 §7 #4）仍是必要的：
 * 多个实例 ⇒ 多个 {@code @Bean} 依赖注入点各自持有引用（虽然状态共享）
 * ⇒ ⇒ 本类加 {@code @ConditionalOnMissingBean} ⇒ 容器里**最多一个** {@code Snowflake} Bean。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/spring/SnowflakeBean.java}
 * ＋ {@code SnowflakeProperties.java} 整段抄（L2 · D13）＋ <b>装配形态回改</b>（L4 · D3）。
 */
@AutoConfiguration
@EnableConfigurationProperties(SnowflakeAutoConfiguration.SnowflakeProperties.class)
public class SnowflakeAutoConfiguration {

    /**
     * 容器里<b>唯一</b>的 {@link Snowflake} Bean。
     *
     * <p>⚠️ {@code Snowflake} 的生成状态是<b>静态</b>的（批 1 落地时已如此）⇒
     * 本 Bean 只是把 workerId 的解析结果交给那唯一一份状态，
     * <b>本 Bean 自己不持有任何生成状态</b>。
     */
    @Bean
    @ConditionalOnMissingBean
    public Snowflake snowflake(SnowflakeProperties props) {
        long workerId = resolveWorkerId(props);
        Snowflake.setWorkerId(workerId);
        return new Snowflake();
    }

    /**
     * 本进程生效的 workerId；委托 {@link Snowflake#currentWorkerId()}，与全局唯一状态同源。
     */
    public long currentWorkerId() {
        Long id = Snowflake.currentWorkerId();
        if (id == null) {
            Snowflake.init();
            id = Snowflake.currentWorkerId();
        }
        return id == null ? 0L : id;
    }

    /**
     * 生成下一个 64 位 ID；算法与 B 侧版 bwmarrin/snowflake v0.3.0 同位宽。
     */
    public long nextId() {
        return Snowflake.nextId();
    }

    /**
     * 决策 5：env &gt; Properties &gt; 兜底。
     */
    private static long resolveWorkerId(SnowflakeProperties props) {
        // 1. env var（运维契约，最高优先级）
        String explicit = System.getenv("SNOWFLAKE_NODE_ID");
        if (explicit != null && !explicit.isEmpty()) {
            try {
                long parsed = Long.parseLong(explicit.trim());
                if (parsed >= 0) {
                    return parsed % (Snowflake.MAX_WORKER_ID + 1);
                }
            } catch (NumberFormatException ignore) {
                // parse 失败被忽略，按优先级继续
            }
        }
        // 2. Properties（application.yml）
        if (props != null && props.getWorkerId() != null && props.getWorkerId() >= 0) {
            return props.getWorkerId() % (Snowflake.MAX_WORKER_ID + 1);
        }
        // 3. 兜底 hash（复用 Snowflake.generateNodeId 算法）
        return Snowflake.generateNodeId();
    }

    /**
     * Snowflake 配置绑定（{@code snowflake.worker-id}）。
     *
     * <p>⚠️ <b>B 侧<b>没有</b>「yaml 配置 workerId」这一档</b> ⇒ ⇒ 本项是 <b>RAG 多出的一档</b>
     * ⇒ ⇒ 属<b>表达层扩展</b>（已登记为 {@code DRIFT-SF-002} · C 类 · 不计入 R3 ⑥）。
     */
    @ConfigurationProperties("snowflake")
    public static class SnowflakeProperties {

        /**
         * 显式指定的 workerId；{@code null} 时按优先级回退到环境变量 / 兜底 hash。
         */
        private Long workerId;

        public Long getWorkerId() {
            return workerId;
        }

        public void setWorkerId(Long workerId) {
            this.workerId = workerId;
        }
    }
}
