package com.rag.article.wiring;

import com.rag.article.domain.service.ArticleAtomicService;
import com.rag.common.snowflake.Snowflake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * <p><b>职责</b>：雪花 ID 生成器就绪确认 —— 对应 Go 端
 * {@code internal/svc/service_context.go:58} 的 {@code snowflake.Init()}。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/bootstrap/SnowflakeBootstrap.java}
 * （类声明 {@code :33-34} · 字段与构造器 {@code :38-45} · {@code verifySnowflakeReady} {@code :56-77}）
 * ⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.bootstrap} → {@code com.rag.article.wiring}（D7 包压平）；</li>
 *         <li><b>共享层的载体名</b>：B 侧注入 {@code com.rag.common.spring.SnowflakeBean}
 *             ⇒ 本仓改注入共享层的 {@link Snowflake}（{@code com.rag.common.snowflake.Snowflake}，
 *             D3：{@code SnowflakeBean} + {@code SnowflakeProperties} 合并为
 *             {@code SnowflakeAutoConfiguration} 提供的 Bean）。
 *             ⇒ <b>位宽 / epoch / workerId 解析逻辑完全不动</b>（共享层逐字照抄 B 侧）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>不重复初始化</b>：Go 侧 {@code snowflake.Init()} 是<b>无参</b>的；
 *             Java 侧 {@code SnowflakeAutoConfiguration} 已在容器 refresh 期解析 workerId
 *             ⇒ ⇒ 本类<b>不</b>再调一次初始化（那会走 {@code setWorkerId} 的 first-wins 分支，
 *             把环境变量里解析好的 workerId 覆盖成默认值）；</li>
 *         <li>epoch {@code 1288834974657} ＋ 位段 {@code 41/10/12} <b>一字未改</b>
 *             （那是 L1 契约 —— 别的域的游标编码依赖 {@code >>22} 位移）；</li>
 *         <li>「顺带验证事务 Bean 的代理已生成」这条<b>启动期自检</b>照搬。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 与共享层 {@link Snowflake} 的分工（不要重复初始化）</h2>
 * <p>共享层 {@code com.rag.common.snowflake.Snowflake} 在<b>容器 refresh 期</b>解析 workerId
 * 并交给它自己的唯一静态状态（该类<b>不持有</b>生成状态），
 * 由 {@code com.rag.common.autoconfigure.SnowflakeAutoConfiguration} 产出。
 * <br>Go 侧 {@code snowflake.Init()} 是<b>无参</b>的，workerId 来自进程内默认值或环境变量。
 * <br>⇒ Java 侧<b>不需要</b>再调一次初始化。
 *
 * <p>本类因此只做<b>就绪确认与可见性</b>：确认 workerId 已解析成功，并把它打进启动日志。
 * <p><b>⚠️ 为什么需要确认</b>：若 {@code SNOWFLAKE_NODE_ID} 配置非法（例如 &gt; 1023），
 * {@code setWorkerId} 会拒绝或抛错。Go 侧 {@code snowflake.Init()} 在这种情况下同样会失败。
 * 本类把这种失败变成<b>启动即可见</b>，而不是等到第一篇文章创建时才发现 ID 重复。
 *
 * <p><b>不阻止启动</b>：与桶 bootstrap 同理，ID 只在写入路径用到；
 * 且 {@code Snowflake} 自身在 {@code nextId()} 时还有 workerId 兜底。
 */
@Component
public class SnowflakeBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeBootstrap.class);

    private final Snowflake snowflake;

    private final ArticleAtomicService atomicService;

    /**
     * 构造器 —— 注入共享层的雪花 ID 生成器与事务 Bean。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/SnowflakeBootstrap.java:41-45}。
     *
     * @param snowflake    共享层的雪花 ID 生成器（workerId 已在 refresh 期解析）
     * @param atomicService 事务 Bean（仅用于确认 AOP 代理已生成，不参与任何逻辑）
     */
    public SnowflakeBootstrap(Snowflake snowflake, ArticleAtomicService atomicService) {
        this.snowflake = snowflake;
        // 仅用于确认事务 Bean 已就绪（见下方说明），不参与任何逻辑
        this.atomicService = atomicService;
    }

    /**
     * 启动后确认 ID 生成器就绪。
     *
     * <p>⚠️ <b>顺带验证事务 Bean 的代理已生成</b>：
     * {@link ArticleAtomicService} 上的 {@code @Transactional} 依赖 AOP 代理。
     * 若某个装配问题让代理没生成，本类<b>仍然能构造</b>（Spring 会注入目标对象），
     * 但运行期事务会静默失效。所以这里显式断言它是<b>代理对象</b>，
     * 把 AGENTS.md §3.3 那类「注解在 ≠ 按预期生效」的问题在启动时就暴露。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/SnowflakeBootstrap.java:56-77}。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifySnowflakeReady() {
        // ⚠️ workerId 是共享层 Snowflake 的**进程级唯一静态状态** ⇒ 用静态方法读，
        //    避免「经实例读静态」的可读性歧义。
        Long workerId = Snowflake.currentWorkerId();
        log.info("SnowflakeBootstrap: workerId={} (article ids and sync event ids use it)", workerId);

        boolean proxied = org.springframework.aop.support.AopUtils.isAopProxy(atomicService);
        if (proxied) {
            log.info("SnowflakeBootstrap: ArticleAtomicService is an AOP proxy ⇒ @Transactional is active");
        } else {
            // ⚠️ 没有代理 ⇒ 三处多步事务（retract+update / retract+delete / 审核入队 / 发布落地）
            //    全部退化为 auto-commit，异常不回滚，且不报任何错。
            log.error("""
                    SnowflakeBootstrap: ArticleAtomicService is NOT an AOP proxy ⇒ @Transactional is INACTIVE.
                      The following operations would run without a transaction:
                        - update: 已发布文章降级（retract + 域事件 + 同步事件 + 更新 article）
                        - delete: retract + 同步事件 + 软删
                        - review consumer: 行锁 + 幂等复查 + 写同步 outbox
                        - sync result: revision + 指针 + 域事件 + 状态推进
                      典型原因：把事务方法挪到了被 self-invocation 调用的内部类里。
                    """);
        }
    }
}
