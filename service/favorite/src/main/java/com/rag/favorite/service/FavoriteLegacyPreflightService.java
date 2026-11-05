package com.rag.favorite.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.favorite.domain.entity.FavoriteItemEntity;
import com.rag.favorite.mapper.FavoriteFactOutboxMapper;
import com.rag.favorite.mapper.FavoriteFactOutboxMapper.FavoriteLegacyPreflightRow;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 🔴🔴 **legacy 只读预检的报表计算** —— 对应 Go
 * {@code service/favorite/rpc/internal/model/favorite_legacy_preflight.go:30-96} 的
 * {@code FavoriteLegacyPreflight}。
 *
 * <h2>它回答什么</h2>
 * <p>「当前库里有没有<b>没有 assert 事实</b>的收藏行，以及它们有没有被逐字段签字的 legacy 标记？」
 *
 * <p>Go 注释（{@code favorite_legacy_preflight.go:26-28}）说得很直接：
 * <pre>
 * // FavoriteLegacyPreflight never mutates business, markers or Outbox. A clear
 * // local report is only one release gate: real source writers must be frozen
 * // while operators enumerate, approve and re-run the production snapshot.
 * </pre>
 * ⇒ <b>只读</b>，且「本地 clear」<b>不是</b>发布许可。
 *
 * <h2>🔴 「Clear()」的判定逐字保留（{@code favorite_legacy_preflight.go:22-25}）</h2>
 * <pre>
 * return r.Contract == "rtw.favorite.legacy-preflight.v1" &amp;&amp;
 *        r.MissingAssert == r.ApprovedLegacy &amp;&amp; r.Blocked == 0 &amp;&amp; r.OrphanRetracts == 0
 * </pre>
 * 四条<b>全部</b>满足才算 clear。
 *
 * <h2>🔴🔴 事务：<b>刻意不加</b> {@code @Transactional}（这不是漏写）</h2>
 * <p><b>Go 真源</b>：{@code sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true}}
 * （{@code favorite_legacy_preflight.go:35}）⇒ <b>有</b>一个可重复读的只读快照。
 * <p><b>B 侧</b>：{@code fact/FavoriteLegacyPreflightRunner.java:157-158} <b>写了</b>
 * {@code @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)}，
 * 且其 javadoc 明写「Java 侧必须同样声明，否则一条误写的 UPDATE 会污染『证据快照』」
 * ⇒ <b>意图明确</b>。
 * <p>🔴 <b>但 B 侧的实际行为是「没有事务」</b>：该注解所在的 {@code preflight()}
 * <b>只</b>被同一个 bean 的 {@code run()} 调用（B 侧 {@code :115}，
 * 全仓 grep 无第二个调用点）⇒ <b>Spring AOP 代理被绕过</b>（自调用）
 * ⇒ ⇒ <b>事务从未生效</b>，两条 SELECT 各自跑在自动提交里。
 * <p>⇒ <b>C 侧照抄 B 侧的实际行为</b>（AGENTS.md §三 · D16：B 侧已有的差异<b>一律继承</b>，
 * 登记为签字偏离，<b>不回溯第三方</b>）⇒ ⇒ <b>刻意不加</b> {@code @Transactional}。
 * <p>⚠️ <b>可观察性边界</b>：本差异<b>只在并发写下</b>才可能出现
 * （两条 SELECT 看到不同快照）⇒ 而 Go 的注释要求「先冻结真实写入方再重跑生产快照」
 * ⇒ ⇒ <b>在预检的预期用法下不可观察</b>。
 * ⇒ 登记见 {@code docs/Intentional_Deviations/favorite/_signed-deviations.md} 的
 * {@code DEV-FAV-RPC-008}。
 *
 * <h2>🔴 与 B 侧的结构差异（L4 · 表达层 · 不改语义）</h2>
 * <ol>
 *   <li><b>包名压平</b>（D7）：B 侧 {@code com.rag.favorite.favorite.rpc.fact} ⇒
 *       C 侧 {@code com.rag.favorite.service}（业务语义层）／
 *       {@code com.rag.favorite.wiring}（触发入口层）。</li>
 *   <li><b>触发入口形态</b>：B 侧 = {@code @EventListener(ApplicationReadyEvent.class)}
 *       （进程起来跑一次）；C 侧 = <b>Actuator 自定义端点</b>
 *       （{@code wiring/FavoriteLegacyPreflightEndpoint} · 决策 <b>P6-c</b>）
 *       ⇒ ⇒ 「<b>什么时候跑</b>」变了（启动时一次 ⇒ 运维/CI 按需触发），
 *       「<b>算出来什么</b>」没变（同一段 SQL + 同一套逐字段判定）。</li>
 *   <li><b>报表载体</b>：B 侧是 {@code public static class Report}（可变 public 字段）
 *       ⇒ C 侧改为 <b>immutable record</b>（本仓 D5 的形态惯例）
 *       ⇒ <b>JSON 字段名与顺序逐条不变</b>（见 {@link Report}）。</li>
 * </ol>
 *
 * <h2>§3.2 三段式（AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>无</b> —— 只把 B 侧 {@code Runner} 里的
 *       「算报表」与「打日志 / 定触发形态」拆成两个类（业务语义 / 装配），
 *       <b>判定逻辑逐字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— SQL 逐字相同（复用 C 侧已存在的
 *       {@code selectRowsWithoutAssertion()} / {@code countOrphanRetracts()}），
 *       逐字段比对 12 项逐条未改，{@code Clear()} 的四条判据逐条未改。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/fact/
 *       FavoriteLegacyPreflightRunner.java:82-231}；
 *       Go：{@code service/favorite/rpc/internal/model/favorite_legacy_preflight.go:14-96}
 *       与 {@code service/favorite/rpc/cmd/fact-legacy-preflight/main.go:18-54}。</li>
 * </ol>
 */
@Component
public class FavoriteLegacyPreflightService {

    /** Go {@code favorite_legacy_preflight.go:31} 的契约串。 */
    public static final String CONTRACT = "rtw.favorite.legacy-preflight.v1";

    private final FavoriteFactOutboxMapper outboxMapper;
    private final FavoriteFactEnvelopeFactory envelopeFactory;

    /**
     * <b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
     * <pre>{@code
     * private final FavoriteFactOutboxMapper outboxMapper;
     * private final FavoriteFactEnvelopeFactory envelopeFactory;
     *
     * public FavoriteLegacyPreflightService(FavoriteFactOutboxMapper outboxMapper,
     *                                       FavoriteFactEnvelopeFactory envelopeFactory) {
     *     this.outboxMapper = outboxMapper;
     *     this.envelopeFactory = envelopeFactory;
     * }
     * }</pre>
     *
     * <p>⚠️ <b>没有注入 {@code FavoriteDeliveryValidator}</b> ——
     * {@code sameFavoriteRevision} 是<b>静态</b>方法（B 侧 {@code :390-395} 同款）
     * ⇒ ⇒ B 侧的 Runner 也<b>没有</b>注入它（B 侧 {@code :62-68} 的构造器只有 3 个参数）
     * ⇒ ⇒ 静态调用，不进构造器。
     */
    public FavoriteLegacyPreflightService(FavoriteFactOutboxMapper outboxMapper,
                                           FavoriteFactEnvelopeFactory envelopeFactory) {
        this.outboxMapper = outboxMapper;
        this.envelopeFactory = envelopeFactory;
    }

    /**
     * 🔴 对应 Go {@code FavoriteLegacyPreflight}（{@code favorite_legacy_preflight.go:30-96}）。
     *
     * <p>⚠️ <b>没有 {@code @Transactional}</b> —— 见类注释「事务」一节：<b>刻意</b>照抄
     * B 侧「自调用 ⇒ 代理被绕过 ⇒ 事务未生效」的实际行为（D16：B 侧已有的差异一律继承）。
     *
     * <p><b>只读</b>：本方法只调两条 {@code SELECT}（{@code selectRowsWithoutAssertion()} /
     * {@code countOrphanRetracts()}）⇒ 不改业务数据 / marker / Outbox
     * ⇒ 与 Go 注释「never mutates」一致。
     */
    public Report preflight() {
        long missingAssert = 0;
        long approvedLegacy = 0;
        long blocked = 0;

        // Go: favorite_legacy_preflight.go:40-47 —— 一条 LEFT JOIN 拉出「无 v1 断言」的行 + 它们的 marker
        List<FavoriteLegacyPreflightRow> rows = outboxMapper.selectRowsWithoutAssertion();

        // Go: favorite_legacy_preflight.go:50-78 —— 逐行比对 12 项，分 Blocked / ApprovedLegacy
        for (FavoriteLegacyPreflightRow row : rows) {
            missingAssert++;
            if (!markerMatches(row)) {
                blocked++;
                continue;
            }
            approvedLegacy++;
        }

        // Go: favorite_legacy_preflight.go:86-91 —— 孤儿撤回统计
        long orphanRetracts = outboxMapper.countOrphanRetracts();
        return new Report(CONTRACT, missingAssert, approvedLegacy, blocked, orphanRetracts);
    }

    /**
     * Go {@code favorite_legacy_preflight.go:62-77} 的逐行判定。
     *
     * <p>⚠️ <b>与 {@code favoriteAssertionSchema} 的 legacy 分支是同一条规则的两次独立表达</b>：
     * 预检在<b>只读快照</b>里统计，撤回路径在<b>写事务</b>里阻断。
     * 两者必须给出一致的结论，否则运维会看到「预检说 clear、删除却仍被 5304 阻断」。
     * ⇒ 这里的比对项与 {@code FavoriteAssertionSchemaResolver#resolveLegacy} 逐条对齐。
     */
    private boolean markerMatches(FavoriteLegacyPreflightRow row) {
        if (row.markerFavoriteId == null || row.markerUserId == null || row.markerFolderId == null
                || row.markerTargetType == null || row.markerTargetId == null
                || row.markerSourceSnapshotSha256 == null || row.markerApprovalRef == null) {
            return false;
        }
        // Go: favorite_legacy_preflight.go:69-73 —— 逐字段相等
        if (!row.markerFavoriteId.equals(row.favoriteId)
                || !row.markerUserId.equals(row.userId)
                || !row.markerFolderId.equals(row.folderId)
                || !row.markerTargetType.equals(row.targetType)
                || !row.markerTargetId.equals(row.targetId)) {
            return false;
        }
        // Go: favorite_legacy_preflight.go:72 —— markerRevision.Valid != revision.Valid ⇒ 不匹配（null 与空串不可混）
        if (!FavoriteDeliveryValidator.sameFavoriteRevision(row.markerTargetRevision, row.targetRevision)) {
            return false;
        }
        // Go: favorite_legacy_preflight.go:71 —— markerApproval.String == "" ⇒ 不匹配
        if (row.markerApprovalRef.isEmpty()) {
            return false;
        }
        // Go: favorite_legacy_preflight.go:71 —— markerHash.String != hash ⇒ 不匹配（重新算一遍业务行快照）
        return row.markerSourceSnapshotSha256.equals(snapshotHash(row));
    }

    /** 重算业务行的不可变快照哈希 —— 与 Go 的 {@code favoriteLegacySnapshotHash}
     *     （{@code service/favorite/rpc/internal/model/favorite_fact.go:44}）同一口径。 */
    private String snapshotHash(FavoriteLegacyPreflightRow row) {
        FavoriteItemEntity item = new FavoriteItemEntity();
        item.setFavoriteId(row.favoriteId);
        item.setUserId(row.userId);
        item.setFolderId(row.folderId);
        item.setTargetType(row.targetType);
        item.setTargetId(row.targetId);
        item.setTargetRevision(row.targetRevision);
        return envelopeFactory.legacySnapshotHash(item);
    }

    /**
     * Go {@code FavoriteLegacyPreflightReport}（{@code favorite_legacy_preflight.go:14-20}）的 Java 形状。
     *
     * <p>🔴 <b>JSON 字段名与顺序必须与 Go 逐字一致</b>（Go 的 struct 字段顺序即 JSON 输出顺序）
     * ⇒ ⇒ 字段用 {@link JsonProperty} 显式钉住名字，用 record 的声明顺序钉住顺序。
     *
     * <p>⚠️ <b>不输出 {@code clear} 布尔</b> —— Go 的 {@code Clear()} 是<b>方法</b>不是字段
     * （{@code json.Marshal} 只序列化 5 个字段）⇒ ⇒ C 侧也不输出
     * ⇒ ⇒ 「是否 clear」由<b>触发层的 HTTP 状态码</b>表达（Go 侧由退出码表达）⇒ 形态差异，已登记。
     */
    public record Report(
            @JsonProperty("contract") String contract,
            @JsonProperty("missing_assert") long missingAssert,
            @JsonProperty("approved_legacy") long approvedLegacy,
            @JsonProperty("blocked") long blocked,
            @JsonProperty("orphan_retracts") long orphanRetracts) {

        /**
         * Go {@code func (r FavoriteLegacyPreflightReport) Clear() bool}（{@code :22-25}）的等价物。
         *
         * <p>四条判据<b>全部</b>满足才算 clear（缺一不可）。
         */
        public boolean clear() {
            return CONTRACT.equals(contract)
                    && missingAssert == approvedLegacy
                    && blocked == 0L
                    && orphanRetracts == 0L;
        }
    }
}
