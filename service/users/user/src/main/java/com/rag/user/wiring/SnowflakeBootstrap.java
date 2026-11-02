package com.rag.user.wiring;

import com.rag.common.snowflake.Snowflake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * <p><b>Snowflake workerId 启动期校验</b> —— {@code @EventListener(ApplicationReadyEvent)}。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/bootstrap/SnowflakeBootstrap.java
 * </pre>
 *
 * <h2>⚠️ L4 idiomatization：B 侧注入 {@code SnowflakeBean} → C 侧读静态唯一状态</h2>
 * <p>B 侧本类注入 {@code com.rag.common.spring.SnowflakeBean}，调用其 {@code getWorkerId()}。
 * C 侧<b>没有 {@code SnowflakeBean} 这个类</b>（它已被
 * {@code com.rag.common.autoconfigure.SnowflakeAutoConfiguration} 取代 —— D3 starter 化，
 * {@code @Component} + {@code @Import} 改成 auto-config 的 {@code @Bean}）。
 *
 * <p><b>语义等价论证</b>：B 侧 {@code SnowflakeBean#getWorkerId()} 的方法体<b>本身就是</b>
 * <pre>
 *   Long id = Snowflake.currentWorkerId();
 *   if (id == null) { Snowflake.init(); id = Snowflake.currentWorkerId(); }
 *   return id == null ? 0L : id;
 * </pre>
 * ⇒ 即<b>读同一份静态状态</b>（{@code com.rag.common.snowflake.Snowflake} 的唯一 {@code STATE}）
 * ⇒ ⇒ C 侧<b>直接读同一个静态方法</b> ⇒ <b>逐字同源、零语义差</b>。
 * ⚠️ <b>不能</b>改成「自己解析一份 workerId」—— 那会造出第二份生成状态
 * （B 侧 {@code SnowflakeBean} 的 javadoc 明确记过这个坑：M11 修复前同一 JVM 有两个 workerId 交替产 ID）。
 *
 * <h2>行为等价论证（逐条）</h2>
 * <table border="1">
 *   <tr><th>B 侧</th><th>C 侧</th></tr>
 *   <tr><td>{@code long workerId = snowflakeBean.getWorkerId();}</td>
 *       <td>同源（{@link #resolveWorkerId()}）</td></tr>
 *   <tr><td>{@code if (workerId < 0 || workerId > Snowflake.MAX_WORKER_ID) throw new IllegalStateException(...)}</td>
 *       <td>逐字一致（含异常文案）</td></tr>
 *   <tr><td>{@code Snowflake.init();}</td><td>逐字一致（{@code init()} 幂等，{@code sync.Once} 等价物）</td></tr>
 *   <tr><td>{@code log.info("SnowflakeBootstrap verified workerId={}, Snowflake ready", workerId)}</td>
 *       <td>逐字一致</td></tr>
 * </table>
 *
 * <p>⚠️ <b>fail-fast 的实际可达性（照抄 B 侧，不加码）</b>：B 侧的 workerId 解析链
 * （env → properties → 兜底 hash）<b>三档都做了 {@code % (MAX_WORKER_ID + 1)}</b>
 * ⇒ 解析结果<b>恒在 [0, 1023]</b> ⇒ ⇒ <b>范围检查实际不可达</b>
 * ⇒ ⇒ 本类<b>照抄该形态</b>，**不**擅自加严（加严会改变「能否启动」这个可观察行为 ⇒ 属越权）。
 *
 * <p><b>框架职责</b>：
 * <ul>
 *   <li>{@code @EventListener(ApplicationReadyEvent)} ⇒ 容器 refresh 完成、开始接受流量<b>之前</b>触发；</li>
 *   <li>共享层 {@code SnowflakeAutoConfiguration} 已把 workerId 解析结果交给唯一静态状态
 *       ⇒ 本类只做<b>校验 + 兜底 init</b>，不持有任何生成状态；</li>
 *   <li>{@code users_user.uid} 的主键策略 = {@code ASSIGN_ID} ⇒ uid 由雪花算法生成
 *       （B 侧同一口径，见 {@code domain/entity/UserEntity.java}）。</li>
 * </ul>
 *
 * <p>⚠️ <b>与 D1 合并的关系</b>：B 侧该类在 <b>rpc 进程</b>（api 进程不生成 UID）；C 侧合并为单进程后
 * ⇒ 本类在<b>整个 app</b> 生效 ⇒ 触发时机与 B 侧 rpc 进程<b>完全相同</b>（都是 {@code ApplicationReadyEvent}）
 * ⇒ ⇒ <b>UID 生成的可观察时机不变</b>。
 */
@Component
public class SnowflakeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeBootstrap.class);

    /**
     * 启动期校验 workerId 合法，并确保雪花生成器已初始化。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initSnowflake() {
        long workerId = resolveWorkerId();
        if (workerId < 0 || workerId > Snowflake.MAX_WORKER_ID) {
            throw new IllegalStateException(
                    "Snowflake workerId out of range [0, " + Snowflake.MAX_WORKER_ID + "]: " + workerId);
        }
        // 触发 Snowflake.init() 校验（throws if generateNodeId failed）
        Snowflake.init();
        log.info("SnowflakeBootstrap verified workerId={}, Snowflake ready", workerId);
    }

    /**
     * 读本进程生效的 workerId —— 与 B 侧 {@code SnowflakeBean#getWorkerId()} <b>同源、同逻辑</b>。
     *
     * <p>⚠️ <b>为什么不能自己解析</b>：解析结果是<b>进程内唯一一份</b>的生成状态
     * （{@code Snowflake} 的静态 {@code STATE}）⇒ 任何「再解析一次」都会造出第二份状态。
     */
    private static long resolveWorkerId() {
        Long id = Snowflake.currentWorkerId();
        if (id == null) {
            Snowflake.init();
            id = Snowflake.currentWorkerId();
        }
        return id == null ? 0L : id;
    }
}
