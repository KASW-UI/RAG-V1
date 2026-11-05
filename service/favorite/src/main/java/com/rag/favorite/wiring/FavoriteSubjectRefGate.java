package com.rag.favorite.wiring;

import com.rag.common.logger.FatalError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * <p><b>启动期硬门禁</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/bootstrap/FavoriteSubjectRefGate.java}
 * （逐条照抄 · L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <p>对应 Go {@code service/favorite/rpc/favorite.go:31-35}：
 * <pre>
 * if c.SubjectRefV2Facts &amp;&amp; c.Mode != service.DevMode &amp;&amp; c.Mode != service.TestMode {
 *     logx.Error("SubjectRefV2Facts is refused outside dev/test")
 *     os.Exit(2)
 * }
 * </pre>
 *
 * <h2>🔴 为什么这是<b>硬</b>门禁而不是一条告警</h2>
 *
 * <p>Go 把它放在 <b>{@code logx.MustSetup}（:36）之前</b> ⇒ 进程在<b>打开数据库之前</b>就退出。
 * 这是有意的：v2 的 {@code subject_ref} 会改变<b>已冻结事实的形状</b>
 * （v2 的 {@code subject_ref} 没有 {@code tenant_id}，见
 * {@code FavoriteFactEnvelopeFactory} 的 {@code subjectRefV2}）。
 * 一个 v2 进程与 v1 进程对<b>同一个收藏</b>写出的 assert/retract 形状不同，
 * 下游权威读会因 {@code strictFavoriteJSON} 的未知字段规则而<b>永久 404</b>。
 *
 * <p>⇒ 宁可启动失败，也不能让它在生产里"跑起来再说"。
 *
 * <h2>Go 的 {@code Mode} ↔ Java 的 profile</h2>
 *
 * <p>⚠️ Go 的 {@code c.Mode} 来自 go-zero 的 {@code zrpc.RpcServerConf} 内嵌
 * {@code service.ServiceConf}，<b>取值域不在本仓库</b>
 * （docs/FileTree/favorite/rpc.md 的 UNKNOWN U3）。
 * 本实现按 Spring 惯例映射：<b>激活了 {@code dev} 或 {@code test} profile 即视为
 * 开发/测试模式</b>。
 * ⚠️ 这是映射而非逐字复刻；Go 的三值（DevMode/TestMode/其它）在本仓库无法完全取证。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>落点由 {@code com.rag.favorite.favorite.rpc.bootstrap} 压平为
 *           {@code com.rag.favorite.wiring}（D7 包压平 + D10 {@code bootstrap} → {@code wiring}）；</li>
 *       <li><b>配置读取方式</b>：B 侧注入 {@code FavoriteRpcProperties}（B 侧
 *           {@code config/FavoriteRpcProperties.java}，前缀 {@code favorite}）。
 *           C 侧 {@code com.rag.favorite.config.FavoriteRpcProperties} 是
 *           <b>配置层的落点</b>、不由本类拥有（写权面限制）
 *           ⇒ 本类直接用 {@code @Value} 读<b>同一批键名</b>
 *           （{@code favorite.subject-ref-v2-facts} /
 *           {@code favorite.fact.dispatch.enabled} /
 *           {@code favorite.fact.authority.enabled} /
 *           {@code favorite.fact.legacy-preflight.enabled}）
 *           ⇒ 与 B 侧 {@code application.yml:288-331} 的键名<b>逐字一致</b>
 *           ⇒ 两边读到的是同一份配置。</li>
 *       <li><b>启动日志的前缀仍是 {@code "favorite-rpc starting"}</b>（B 侧逐字），
 *           尽管 C 侧合并后的应用名是 {@code favorite-service}
 *           ⇒ 刻意保留：它是<b>日志字符串</b>不是行为，且运维侧可能按它 grep
 *           ⇒ 要改只是一行文案决策，<b>不属于本类的对齐范围</b>。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。门禁判据（{@code subjectRefV2Facts && !(dev|test profile)}
 *       ⇒ 抛 {@link FatalError} 让 JVM 非零退出）、dev/test profile 的取值集合、
 *       启动日志的字段与顺序逐条未改。</li>
 *   <li><b>B 出处</b>：
 *       {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/bootstrap/FavoriteSubjectRefGate.java:41-86}
 *       （类声明 :41-42 · 常量 :48 · 字段与构造器 :50-56 ·
 *       {@code run} :58-86 · dev/test 判定 :60-61 · 启动日志 :66-71 ·
 *       门禁本体 :74-85）。</li>
 * </ol>
 */
@Component
public class FavoriteSubjectRefGate implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteSubjectRefGate.class);

    /** 视为「Go 的 DevMode / TestMode」的 Spring profile 名
     *     （{@code go-zero@v1.10.2/core/service/serviceconf.go:16,18}）。 */
    private static final List<String> DEV_TEST_PROFILES = List.of("dev", "test");

    private final boolean subjectRefV2Facts;
    private final boolean dispatchEnabled;
    private final boolean authorityEnabled;
    private final boolean legacyPreflightEnabled;
    private final Environment environment;

    public FavoriteSubjectRefGate(
            @Value("${favorite.subject-ref-v2-facts:false}") boolean subjectRefV2Facts,
            @Value("${favorite.fact.dispatch.enabled:false}") boolean dispatchEnabled,
            @Value("${favorite.fact.authority.enabled:false}") boolean authorityEnabled,
            @Value("${favorite.fact.legacy-preflight.enabled:false}") boolean legacyPreflightEnabled,
            Environment environment) {
        this.subjectRefV2Facts = subjectRefV2Facts;
        this.dispatchEnabled = dispatchEnabled;
        this.authorityEnabled = authorityEnabled;
        this.legacyPreflightEnabled = legacyPreflightEnabled;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean devOrTest = DEV_TEST_PROFILES.stream()
                .anyMatch(profile -> Arrays.asList(environment.getActiveProfiles()).contains(profile));

        // 对应 Go service/favorite/rpc/favorite.go:52-54 —— 启动日志带 subject_ref_version
        // （Go 的 favoriteSubjectVersion 产出 "v1" / "v2"）
        String version = subjectRefV2Facts ? "v2" : "v1";
        LOG.info("favorite-rpc starting: subject_ref_version={} profiles={} fact.dispatch.enabled={} "
                        + "fact.authority.enabled={} fact.legacy-preflight.enabled={}",
                version, Arrays.toString(environment.getActiveProfiles()),
                dispatchEnabled,
                authorityEnabled,
                legacyPreflightEnabled);

        // Go: service/favorite/rpc/favorite.go:31-35 —— 门禁本体
        if (subjectRefV2Facts && !devOrTest) {
            // ⚠️ 抛 FatalError 而不是 System.exit(2)：
            //    FatalError 的契约（common/logger/FatalError.java）就是
            //    「打出 fatal 日志 + 抛出让异常冒泡出 main，JVM 非零退出」，
            //    等价于 Go 的 os.Exit(2)，同时不绕过 Spring 的 graceful shutdown。
            //    Spring Boot 的 main 未捕获该异常 ⇒ 容器启动失败 ⇒ 进程退出。
            throw new FatalError(
                    "SubjectRefV2Facts is refused outside dev/test "
                            + "(favorite.subject-ref-v2-facts=true, profiles="
                            + Arrays.toString(environment.getActiveProfiles()) + ")",
                    null);
        }
    }
}
