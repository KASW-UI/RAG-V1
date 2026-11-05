package com.rag.favorite.wiring;

import com.rag.favorite.service.FavoriteLegacyPreflightService;
import com.rag.favorite.service.FavoriteLegacyPreflightService.Report;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 🔴🔴 **legacy 只读预检的触发入口** —— Actuator 自定义端点（决策 <b>P6-c</b> 拍板：<b>①</b>）。
 *
 * <h2>为什么是 Actuator 端点（而不是 B 侧那种「启动时跑一次」）</h2>
 * <p>B 侧：{@code fact/FavoriteLegacyPreflightRunner.java} = {@code @Component} +
 * {@code @EventListener(ApplicationReadyEvent.class)} ⇒ <b>进程起来跑一次</b>，
 * 结果只进<b>日志</b>。
 * <p>C 侧：{@code @WebEndpoint(id = "favorite-legacy-preflight")} ⇒
 * <b>运维 / CI 按需触发</b>，结果既进日志、也<b>进 HTTP 响应体</b>。
 * <p>⇒ ⇒ 「<b>算出来什么</b>」完全没变（同一段 SQL + 同一套逐字段判定，见
 * {@link FavoriteLegacyPreflightService}）；变的只是「<b>什么时候跑</b>」与
 * 「<b>结果怎么拿</b>」。
 *
 * <h2>🔴 Go 的退出码在 Spring 进程内不存在 ⇒ 本端点的替代信号（显式设计）</h2>
 * <table border="1">
 *   <caption>Go 的退出码（{@code cmd/fact-legacy-preflight/main.go:24,30,38,50}）</caption>
 *   <tr><th>码</th><th>含义</th><th>C 侧替代信号</th></tr>
 *   <tr><td>0</td><td>clear</td><td><b>HTTP 200</b> + 报表体</td></tr>
 *   <tr><td>1</td><td>读取失败</td><td><b>HTTP 500</b>（异常上抛；先打 {@code outcome=failed} 结构化日志）</td></tr>
 *   <tr><td>2</td><td>未裁决（blocked）</td><td><b>HTTP 409</b> + 报表体（带 {@code blocked} / {@code approved_legacy} 计数）</td></tr>
 * </table>
 * <p>⇒ ⇒ <b>双通道</b>：① HTTP 状态码（CI 用 {@code curl -f} 就能判门禁）
 * ② 结构化日志（B 侧的形态，CI 也可解析日志）。
 * ⇒ ⚠️ **409（Conflict）** 的语义 = 「请求本身没问题，但当前数据状态与发布闸门冲突」。
 *
 * <h2>🔴🔴 为什么它<b>不可能</b>被健康检查探活</h2>
 * <p>Actuator 的健康检查只探 {@code HealthIndicator}（{@code management.endpoint.health.group.*.include}
 * 配的是<b>指标名</b>，不是端点 id）⇒ ⇒ 一个自定义 {@code @WebEndpoint}
 * <b>结构上就不在</b>健康检查的探活路径里。
 * <p>⇒ 再加一道<b>默认关闭</b>：{@code @ConditionalOnProperty(favorite.fact.legacy-preflight.enabled=true)}
 * ⇒ ⇒ <b>默认（false）时这个 bean 根本不存在</b> ⇒ 端点<b>不注册</b> ⇒ 访问得到 <b>404</b>。
 * <p>⇒ ⇒ 运维要打开它，得<b>同时</b>给两个开关（见下）。
 *
 * <h2>🔴 打开它需要<b>两个</b>开关（少一个都打不开）</h2>
 * <pre>
 * favorite.fact.legacy-preflight.enabled=true          # 域语义开关：报表能力是否可用
 * management.endpoint.favorite-legacy-preflight.enabled=true   # Actuator 开关
 * #   ⇒ 因为本仓 application.yml 写了 management.endpoints.enabled-by-default: false
 * management.endpoints.web.exposure.include 里已有 favorite-legacy-preflight
 * </pre>
 * <p>⇒ 两个开关都可用 Spring 的<b>宽松绑定</b>从环境变量给：
 * {@code FAVORITE_FACT_LEGACY_PREFLIGHT_ENABLED=true} ＋
 * {@code MANAGEMENT_ENDPOINT_FAVORITE_LEGACY_PREFLIGHT_ENABLED=true}。
 *
 * <h2>⚠️ 端点 <b>不注册</b> ⇒ 「404」是<b>默认且正确</b>的形态，不是故障</h2>
 * <p>⇒ 健康检查、K8s 探针<b>不会</b>因为它而多跑一次预检。
 *
 * <h2>§3.2 三段式（AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>多了一个对外可触发的 HTTP 面</b>
 *       （Actuator 管理端口上的一个端点）—— 这正是 P6-c 拍板要的东西；
 *       <b>报表算法没多做任何事</b>。</li>
 *   <li><b>语义还一样吗</b>：<b>算报表的语义一样</b>（同一段 SQL + 同一套判定）；
 *       <b>触发时机不一样</b>（启动一次 ⇒ 按需触发）且<b>结果多了一个 HTTP 出口</b>
 *       ⇒ 这两处是<b>形态差异</b>，已登记为签字偏离（{@code DEV-FAV-RPC-009}）。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/fact/
 *       FavoriteLegacyPreflightRunner.java:107-155}（触发 + 日志）；
 *       Go：{@code service/favorite/rpc/cmd/fact-legacy-preflight/main.go:18-54}（退出码）。</li>
 * </ol>
 */
@Component
@WebEndpoint(id = "favorite-legacy-preflight")
@ConditionalOnProperty(prefix = "favorite.fact.legacy-preflight", name = "enabled", havingValue = "true")
public class FavoriteLegacyPreflightEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteLegacyPreflightEndpoint.class);

    private final FavoriteLegacyPreflightService preflightService;
    private final FavoriteFactProcessLogging processLogging;

    /**
     * <b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * private final FavoriteLegacyPreflightService preflightService;
     * private final FavoriteFactProcessLogging processLogging;
     *
     * public FavoriteLegacyPreflightEndpoint(FavoriteLegacyPreflightService preflightService,
     *                                        FavoriteFactProcessLogging processLogging) {
     *     this.preflightService = preflightService;
     *     this.processLogging = processLogging;
     * }
     * }</pre>
     */
    public FavoriteLegacyPreflightEndpoint(FavoriteLegacyPreflightService preflightService,
                                            FavoriteFactProcessLogging processLogging) {
        this.preflightService = preflightService;
        this.processLogging = processLogging;
    }

    /**
     * 跑一次只读预检 ⇒ 返回报表。
     *
     * <p>🔴 **每次调用都重新跑一遍**（不缓存）—— 这是运维闸门的语义：
     * 「冻结写入方 → 重跑生产快照」⇒ 每次都必须看<b>当下</b>的数据。
     *
     * @return 报表 + 门禁状态码（200 = clear / 409 = 未裁决）
     */
    @ReadOperation
    public WebEndpointResponse<Report> preflight() {
        Logger processLogger =
                processLogging.loggerFor(FavoriteFactProcessLogging.SERVICE_PREFLIGHT, "legacy-preflight");
        Report report;
        try {
            report = preflightService.preflight();
        } catch (RuntimeException e) {
            // Go: favorite_legacy_preflight.go:48-50（读取失败）⇒ cmd/fact-legacy-preflight/main.go:38 的 os.Exit(1)
            //     ⇒ C 侧的替代信号 = HTTP 500（异常继续上抛，由 Actuator 渲染 500）
            // ⚠️ 与 B 侧的一处形态差异：B 侧在启动路径上**吞掉**异常（不让进程挂）
            //     ⇒ 端点路径上**必须上抛**（调用方要看见「没算出来」）⇒ 已登记为形态偏离。
            processLogger.atError()
                    .addKeyValue("event", "favorite.legacy_preflight")
                    .addKeyValue("outcome", "failed")
                    .addKeyValue("error_code", "PREFLIGHT_READ_FAILED")
                    .setCause(e)
                    .log("favorite legacy preflight failed");
            LOG.error("FavoriteLegacyPreflightEndpoint: 预检失败", e);
            throw e;
        }

        boolean clear = report.clear();
        String outcome = clear ? "succeeded" : "not_reconciled";
        String errorCode = clear ? null : "LEGACY_RECONCILIATION_REQUIRED";
        processLogger.atInfo()
                .addKeyValue("event", "favorite.legacy_preflight")
                .addKeyValue("outcome", outcome)
                .addKeyValue("error_code", errorCode)
                .addKeyValue("contract", report.contract())
                .addKeyValue("missing_assert", report.missingAssert())
                .addKeyValue("approved_legacy", report.approvedLegacy())
                .addKeyValue("blocked", report.blocked())
                .addKeyValue("orphan_retracts", report.orphanRetracts())
                .log("favorite legacy preflight report");
        LOG.warn("""
                FavoriteLegacyPreflightEndpoint: legacy 预检报告（clear={}）
                  contract={} missing_assert={} approved_legacy={} blocked={} orphan_retracts={}
                  ⚠️ 这**不是**发布许可：Go 侧要求先冻结真实写入方，再在生产快照上重跑
                    （favorite_legacy_preflight.go:26-28 的注释）。
                  ⚠️ Go 用退出码 2 表达「未裁决」，Spring 进程内没有退出码
                    ⇒ CI 需读 HTTP 状态码（200 = clear / 409 = 未裁决）或本行结构化日志。
                """, clear, report.contract(), report.missingAssert(),
                report.approvedLegacy(), report.blocked(), report.orphanRetracts());

        return new WebEndpointResponse<>(report, clear ? HttpStatus.OK.value() : HttpStatus.CONFLICT.value());
    }
}
