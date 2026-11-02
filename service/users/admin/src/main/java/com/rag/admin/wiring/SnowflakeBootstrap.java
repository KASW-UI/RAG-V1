package com.rag.admin.wiring;

import com.rag.common.snowflake.Snowflake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * <p><b>职责</b>：Snowflake 初始化触发器 —— 保证 Snowflake 在 admin 域启动期已就绪
 * （默认 admin 种子的 uid 由它生成）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>包名</b>（{@code com.rag.users.admin.rpc.bootstrap} →
 *       {@code com.rag.admin.wiring}，D7 包压平）＋ <b>初始化载体的名字</b> ——
 *       B 侧是 {@code com.rag.common.spring.SnowflakeBean}（{@code @Configuration}），
 *       本仓是共享层的 {@code com.rag.common.autoconfigure.SnowflakeAutoConfiguration}
 *       （D3 starter 化 · 通过 {@code AutoConfiguration.imports} 自动注册）⇒
 *       ⇒ <b>本类不再负责初始化，只负责「确保它已就绪」</b>（这一职责逐字保留）。</li>
 *   <li><b>语义还一样吗</b>：一样。
 *       <ul>
 *         <li>仍在 {@link ContextRefreshedEvent} 时做一次 {@code nextId()} probe
 *             ⇒ 失败即抛 {@link IllegalStateException}（fail-fast）；</li>
 *         <li>{@code @Order(Ordered.HIGHEST_PRECEDENCE)} 保留。</li>
 *       </ul>
 *       ⚠️ <b>唯一的行为差异</b>：本仓的 {@link Snowflake} <b>生成状态是全进程唯一的一份静态状态</b>
 *       （共享层「M11 修复」把「静态兜底 workerId」与「Bean 持有 workerId」两份状态收敛成一份）
 *       ⇒ ⇒ <b>不存在</b> B 侧那个「同一 JVM 里两个不同 workerId 交替产 ID」的风险
 *       ⇒ ⇒ 本类的 probe 仍保留（它是 fail-fast 探针），但不再承担「纠正 workerId」的职责。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/bootstrap/SnowflakeBootstrap.java}。</li>
 * </ol>
 *
 * <p>实际 Snowflake 初始化由共享层的 {@code SnowflakeAutoConfiguration}
 * （{@code @AutoConfiguration} + {@code AutoConfiguration.imports}）自动完成
 * （workerId 计算、fail-fast），admin 域启动时只需要确保它已就绪。
 *
 * <p>{@code AdminSeederBootstrap} 依赖 {@code Snowflake.nextId()} 生成默认 admin uid，
 * 所以 Snowflake 必须先初始化（Order.HIGHEST_PRECEDENCE）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SnowflakeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeBootstrap.class);

    private volatile boolean initialized = false;

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            try {
                long probe = Snowflake.nextId();
                log.info("admin-service Snowflake ready, probe uid={}", probe);
                initialized = true;
            } catch (Throwable t) {
                log.error("admin-service Snowflake init failed", t);
                throw new IllegalStateException("snowflake init failed", t);
            }
        }
    }
}
