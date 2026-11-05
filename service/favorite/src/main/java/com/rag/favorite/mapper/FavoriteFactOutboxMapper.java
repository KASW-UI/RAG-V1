package com.rag.favorite.mapper;

import com.rag.favorite.domain.entity.FavoriteFactOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <p><b>事实发件箱数据访问</b> —— 对应 B 侧
 * {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/mapper/FavoriteFactOutboxMapper.java}
 * 的全部 SQL（逐条照抄，SQL 文本<b>一字未改</b>）。
 *
 * <p>对应 Go {@code service/favorite/rpc/internal/model/favorite_delivery.go:131-202}
 * （{@code DispatchFavoriteFactOnce}）与 {@code favorite_fact.go:270-332}
 * （两个事务里的 {@code tx.Create(&fact)}）。
 *
 * <p><b>L4 落点变化（D7 包压平）</b>：B 侧 {@code com.rag.favorite.favorite.rpc.mapper.FavoriteFactOutboxMapper}
 * ⇒ C 侧 {@code com.rag.favorite.mapper.FavoriteFactOutboxMapper}。
 *
 * <p><b>本 Mapper 是并发正确性的核心</b>：取件查询里的
 * {@code FOR UPDATE SKIP LOCKED} + 前驱屏障子句决定「多副本下事件按版本序、
 * 互不阻塞、且 retract 绝不越过 assert」。
 */
@Mapper
public interface FavoriteFactOutboxMapper {

    /**
     * 对应 Go {@code favoriteOutbox} / {@code favoriteOutboxV2} 返回后的事务写入
     * （favorite_fact.go:160-161 / 196-197 的 {@code tx.Create(&fact)}）。
     *
     * <p>⚠️ {@code status} 显式写 0（pending）：Go 的 struct 字面量带了
     * {@code Status: FavoriteFactPending}，而 DDL 的 {@code DEFAULT 0} 只是兜底。
     * <p>⚠️ {@code payload} 显式 {@code ::jsonb}：与 DDL 列类型对齐，
     * 也保证写入的是<b>与 Go 完全相同的字节序列</b>（见
     * {@code FavoriteFactOutboxEntity} 的类注释）。
     *
     * <p>🔴 {@code created_at} / {@code updated_at} <b>由本方法显式绑定</b>（2026-10-25 第七轮修）。
     * <p><b>Go 出处</b>：Go 的 struct 字面量<b>没有</b>设 {@code CreatedAt} / {@code UpdatedAt}
     * （{@code internal/model/favorite_fact.go:158-159}），靠 struct tag
     * {@code gorm:"autoCreateTime;not null"} / {@code gorm:"autoUpdateTime;not null"}
     * ⇒ 由 <b>GORM 在应用层赋值</b>（{@code gorm.io/gorm@v1.31.1/callbacks/create.go:302-306}
     * 的 {@code field.AutoCreateTime > 0} 分支）⇒ <b>值来自 Go 进程时钟</b>。
     * <p><b>修前的问题</b>：本方法<b>不绑</b>这两列 ⇒ 落到 DDL 的 {@code DEFAULT now()}
     * ⇒ 值来自 <b>PG 服务器时钟</b> ⇒ <b>时钟源不同</b>（应用进程 vs 数据库）。
     * <p><b>为什么这是行为差异</b>：{@code created_at} 是取件查询的排序键
     * （{@code ORDER BY created_at, event_id}）⇒ 两个时钟偏移会让<b>取件顺序</b>产生分歧。
     * <p><b>修法</b>：显式绑定应用层时钟 ⇒ 与 Go 的 GORM 同源。
     *
     * <p>🔴 <b>2026-10-26 第二十三轮修</b>：本方法<b>原来不绑</b>
     * {@code technical_receipt_id} / {@code technical_input_hash} ⇒ 落库 <b>NULL</b>；
     * 而 Go 侧这两个字段是<b>非指针</b>（{@code internal/model/favorite_fact.go:67-68}）
     * ⇒ GORM 把零值 {@code ""} <b>原样写进 INSERT</b> ⇒ Go 落库 <b>空串</b>、C 侧落库 <b>NULL</b>
     * ⇒ <b>值不同</b>（不是「语句不同」而是「落库值不同」）。
     * <p><b>Go 出处（声明点 + 落库效果）</b>：{@code internal/model/favorite_fact.go:67-68}
     * —— {@code TechnicalReceiptID string} / {@code TechnicalInputHash string}
     * （<b>没有</b> {@code *time.Time} 那种指针 ⇒ 零值是 {@code ""} 而<b>不是</b> NULL）
     * ⇒ GORM 的 INSERT 走 {@code create.go:285-305}：非零字段按原值写、零值字段若带
     * {@code default} tag 才交给 DB 默认 ⇒ 这两列<b>没有</b> {@code default} tag
     * ⇒ <b>空串被显式写入</b>。
     * <p><b>语义等价论证</b>：本方法显式写 {@code ''} ⇒ 与 Go 的 {@code ""} <b>落库值一致</b>。
     * <p>⚠️ <b>不能留 NULL</b>：真源的 {@code TechnicalInputHash} 是 {@code string} 非指针
     * ⇒ pgx 扫 NULL 会报错 ⇒ 且 {@code technical_input_hash} 在权威读面
     * （{@code favorite_authority.go:131-133}）要与 DC 回执逐字节比对
     * ⇒ NULL 与 {@code ""} 不是一回事。
     *
     * <p>🟡 <b>刻意不写</b>的三列（与 Go 的列集不同，但<b>落库值相同</b> ⇒ 不是漂移）：
     * <ul>
     *   <li>{@code technical_offset} —— Go 显式写 {@code 0}；C 侧省 ⇒ DDL
     *       {@code technical_offset bigint NOT NULL DEFAULT 0}（{@code V5}:15）⇒ 落库同样是 {@code 0} ✅</li>
     *   <li>{@code technical_received_at} / {@code delivered_at} —— Go 是<b>指针</b>
     *       （{@code favorite_fact.go:70-71}）⇒ 写 {@code NULL}；C 侧省略 ⇒ 同样是 {@code NULL} ✅
     *       （这两列<b>可空且无 DEFAULT</b>）</li>
     * </ul>
     *
     * <p><b>L4 表达层差异</b>：B 侧用全限定名 {@code java.time.OffsetDateTime}，
     * C 侧改为 import 后简写 ⇒ <b>类型逐字相同</b>，无语义差异。
     */
    @Insert("""
            INSERT INTO favorite_fact_outbox
                (event_id, favorite_id, aggregate_version, payload, status, retry_count,
                 technical_receipt_id, technical_input_hash,
                 created_at, updated_at)
            VALUES (#{eventId}, #{favoriteId}, #{aggregateVersion}, #{payload}::jsonb, 0, 0,
                    '', '',
                    #{createdAt}, #{updatedAt})
            """)
    int insertFact(@Param("eventId") String eventId,
                   @Param("favoriteId") long favoriteId,
                   @Param("aggregateVersion") long aggregateVersion,
                   @Param("payload") String payload,
                   @Param("createdAt") OffsetDateTime createdAt,
                   @Param("updatedAt") OffsetDateTime updatedAt);

    /**
     * 🔴🔴 取件查询 —— 对应 Go {@code favorite_delivery.go:141-147}：
     * <pre>
     * tx.Clauses(clause.Locking{Strength: "UPDATE", Options: "SKIP LOCKED"}).
     *    Where("status IN ?", []int32{FavoriteFactPending, FavoriteFactFailed}).
     *    Where(`aggregate_version=1 OR EXISTS (
     *        SELECT 1 FROM favorite_fact_outbox predecessor
     *        WHERE predecessor.favorite_id=favorite_fact_outbox.favorite_id
     *            AND predecessor.aggregate_version=1 AND predecessor.status=1)`).
     *    Order("created_at,event_id").Take(&amp;row)
     * </pre>
     *
     * <p><b>四个要素一个都不能丢</b>：
     * <ol>
     *   <li>{@code status IN (0, 2)} —— 只取待投递与可重试；已接受(1)与已阻塞(3)
     *       永远不再取出。🔴 若漏掉 status=2，一条投递失败的事件就<b>永久卡住</b>。</li>
     *   <li><b>前驱屏障</b> {@code aggregate_version=1 OR EXISTS(…status=1)} ——
     *       保证 v2 retract <b>绝不</b>在 v1 assert 之前投给 DC。
     *       丢掉它，下游会先收到「不再收藏」再收到「曾经收藏」，状态机倒流。</li>
     *   <li>{@code FOR UPDATE SKIP LOCKED} —— 多 dispatcher 各取各的，互不阻塞；
     *       Go 侧注释明写「Multiple dispatchers skip the locked row」。</li>
     *   <li>{@code ORDER BY created_at, event_id} —— 与 Go 完全相同的排序，
     *       保证可复现。{@code event_id} 是 tie-breaker（{@code created_at} 可同值）。</li>
     * </ol>
     *
     * <p>⚠️ 返回 {@code null} ⇒ Go 的 {@code gorm.ErrRecordNotFound} ⇒
     * {@code return false, nil}（队列空，<b>不是错误</b>，favorite_delivery.go:148-150）。
     */
    @Select("""
            SELECT event_id, favorite_id, aggregate_version, payload, status, retry_count,
                   technical_receipt_id, technical_input_hash, technical_offset,
                   technical_received_at, delivered_at, created_at, updated_at
            FROM favorite_fact_outbox
            WHERE status IN (0, 2)
              AND (aggregate_version = 1 OR EXISTS (
                    SELECT 1 FROM favorite_fact_outbox predecessor
                    WHERE predecessor.favorite_id = favorite_fact_outbox.favorite_id
                      AND predecessor.aggregate_version = 1
                      AND predecessor.status = 1))
            ORDER BY created_at, event_id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """)
    FavoriteFactOutboxEntity claimNextDeliverable();

    /**
     * 对应 Go 冻结信封校验失败时的置 blocked（favorite_delivery.go:156-159）：
     * {@code tx.Model(&FavoriteFactOutbox{}).Where("event_id = ?", ...).Update("status", 3)}。
     *
     * <p>状态 3 = blocked：<b>既不重试也不丢弃</b>，等待人工迁移
     * （Go 的 {@code ErrFavoriteFactBlocked} → {@code INVALID_FROZEN_ENVELOPE}）。
     *
     * <p>⚠️ 本方法<b>不在</b>触发器的不可变清单内（event_id / favorite_id /
     * aggregate_version / payload），所以不会被 {@code favorite_fact_identity_immutable}
     * 误伤 —— 这一点是 V5 触发器的安全性前提。
     *
     * <p>🔴 {@code updated_at} <b>必须推进</b>（2026-10-25 第七轮修）：
     * Go 的 {@code Update(...)} 走 GORM 的 Update 回调 ⇒ {@code autoUpdateTime} 字段
     * <b>每次 UPDATE 都会刷新</b>（{@code gorm.io/gorm@v1.31.1/callbacks/update.go}
     * 的 {@code field.AutoUpdateTime > 0} 分支）⇒ 修前这条 UPDATE
     * <b>根本不碰 {@code updated_at}</b> ⇒ 行会永远停在「插入时刻」的时间戳。
     */
    @Update("""
            UPDATE favorite_fact_outbox
            SET status = 3, updated_at = #{updatedAt}
            WHERE event_id = #{eventId}
            """)
    int markBlocked(@Param("eventId") String eventId,
                    @Param("updatedAt") OffsetDateTime updatedAt);

    /**
     * 对应 Go 投递失败（favorite_delivery.go:176-177）：
     * {@code status=2, retry_count = retry_count + 1}。
     *
     * <p>🔴 {@code updated_at} 同 {@link #markBlocked}：Go 侧 GORM 的 {@code autoUpdateTime}
     * 会刷新它 ⇒ 修前漏刷。
     */
    @Update("""
            UPDATE favorite_fact_outbox
            SET status = 2, retry_count = retry_count + 1, updated_at = #{updatedAt}
            WHERE event_id = #{eventId}
            """)
    int markFailed(@Param("eventId") String eventId,
                   @Param("updatedAt") OffsetDateTime updatedAt);

    /**
     * 对应 Go 投递成功（favorite_delivery.go:189-192）：
     * {@code status=1, technical_receipt_id, technical_input_hash, technical_offset,
     * technical_received_at, delivered_at}。
     *
     * <p>⚠️ 这 6 列<b>全部</b>是投递元数据列，都不在不可变触发器的清单里。
     *
     * <p>🔴 {@code updated_at} 由本方法<b>显式绑定应用层时钟</b>；Go 侧靠 GORM 的
     * {@code autoUpdateTime} 在每次 UPDATE 时刷新（favorite_delivery.go:191 用的是
     * {@code Updates(map)}）。
     * <p>⚠️ <b>2026-10-25 第七轮修</b>：原实现是 {@code updated_at = now()}
     * ⇒ 值来自 <b>PG 服务器时钟</b>，而 Go 的 {@code autoUpdateTime} 是
     * <b>Go 进程时钟</b> ⇒ 时钟源不同 ⇒ 与 {@link #insertFact} 同源的问题。
     */
    @Update("""
            UPDATE favorite_fact_outbox
            SET status = 1,
                technical_receipt_id = #{receiptId},
                technical_input_hash = #{inputHash},
                technical_offset = #{offset},
                technical_received_at = #{receivedAt},
                delivered_at = #{deliveredAt},
                updated_at = #{updatedAt}
            WHERE event_id = #{eventId}
            """)
    int markAccepted(@Param("eventId") String eventId,
                     @Param("receiptId") String receiptId,
                     @Param("inputHash") String inputHash,
                     @Param("offset") long offset,
                     @Param("receivedAt") OffsetDateTime receivedAt,
                     @Param("deliveredAt") OffsetDateTime deliveredAt,
                     @Param("updatedAt") OffsetDateTime updatedAt);

    /**
     * 对应 Go {@code AuthoritativeFavoriteFact} 的按 event_id 取单行
     * （favorite_authority.go:213）：
     * <pre>m.conn.WithContext(ctx).Where("event_id = ?", eventID).Limit(1).Find(&amp;row)</pre>
     *
     * <p>🔴 <b>刻意不过滤 status</b>：是否可读由后续 17 道校验判定
     * （{@code authoritativeFavoriteRow} 首先就要求 {@code status == 1}）。
     * 在 SQL 里过滤会把「未投递」伪装成「查不到」，两者的 HTTP 语义不同
     * （前者走 404，后者同样走 404，但日志与告警口径不同）⇒ Go 选了在应用层判。
     *
     * <p>⚠️ <b>无 {@code FOR UPDATE}</b>：权威读是纯读路径。
     */
    @Select("""
            SELECT event_id, favorite_id, aggregate_version, payload, status, retry_count,
                   technical_receipt_id, technical_input_hash, technical_offset,
                   technical_received_at, delivered_at, created_at, updated_at
            FROM favorite_fact_outbox
            WHERE event_id = #{eventId}
            LIMIT 1
            """)
    FavoriteFactOutboxEntity selectByEventId(@Param("eventId") String eventId);

    /**
     * 对应 Go {@code favoriteAssertionSchema} 找 v1 断言
     * （favorite_fact.go:214-215）：
     * <pre>tx.Clauses(clause.Locking{Strength: "UPDATE"}).
     *    Where("favorite_id = ? AND aggregate_version = 1", item.FavoriteId).Take(&amp;row)</pre>
     *
     * <p><b>必须带 {@code FOR UPDATE}</b>：它是「同一收藏的 assert 与 retract
     * 不得并发」的锁点。🔴 漏掉它 ⇒ 两个并发事务可能同时判定「有 v1 断言」
     * 并各自写出一条 v2，而 {@code UNIQUE(favorite_id, aggregate_version)}
     * 只能让其中一个失败（23505）——那时错误码会变成 {@code Internal/5008}
     * 而不是设计的成功路径。
     */
    @Select("""
            SELECT event_id, favorite_id, aggregate_version, payload, status, retry_count,
                   technical_receipt_id, technical_input_hash, technical_offset,
                   technical_received_at, delivered_at, created_at, updated_at
            FROM favorite_fact_outbox
            WHERE favorite_id = #{favoriteId} AND aggregate_version = 1
            LIMIT 1
            FOR UPDATE
            """)
    FavoriteFactOutboxEntity lockAssertByFavoriteId(@Param("favoriteId") long favoriteId);

    /**
     * 对应 Go {@code favoriteAssertionSchema} 的孤儿检查（favorite_fact.go:236-239）：
     * <pre>tx.Model(&FavoriteFactOutbox{}).
     *    Where("favorite_id = ? AND aggregate_version = 2", item.FavoriteId).Count(&amp;orphanCount)</pre>
     *
     * <p>语义：走 legacy 分支时若已存在一条 v2 retract，说明这条收藏已经撤过一次
     * ⇒ {@code orphanCount != 0 ⇒ ErrFavoriteFactMigrationBlocked}。
     * 即「<b>没有前驱的撤回</b>」必须阻断，不能让标记把它放过去。
     */
    @Select("""
            SELECT count(*) FROM favorite_fact_outbox
            WHERE favorite_id = #{favoriteId} AND aggregate_version = 2
            """)
    long countRetractsByFavoriteId(@Param("favoriteId") long favoriteId);

    /**
     * 对应 Go {@code authoritativeFavoriteFact} 的两条独立核对：
     * <pre>
     * // favorite_authority.go:229  「断言还活着时，独立核对业务行」
     * query := m.conn.Where("favorite_id = ?", row.FavoriteID).Limit(1).Find(&item)
     * // favorite_authority.go:243  「断言 v1 没了时，必须恰好有一条 v2 墓碑」
     * m.conn.Model(&FavoriteFactOutbox{}).
     *     Where("favorite_id = ? AND aggregate_version = 2", row.FavoriteID).Count(&count)
     * </pre>
     *
     * <p>两条查询<b>共用</b>一个方法：都是 {@code aggregate_version = 2} 的计数，
     * 但调用点要求的语义不同（"恰好 1 条" vs "没有孤儿"）⇒ 由调用方判返回值。
     */
    @Select("""
            SELECT count(*) FROM favorite_fact_outbox
            WHERE favorite_id = #{favoriteId} AND aggregate_version = 2
            """)
    long countRetractsForAuthority(@Param("favoriteId") long favoriteId);

    /**
     * 对应 Go {@code authoritativeFavoriteFact} 的 v1 前驱查找（favorite_authority.go:262-263）：
     * <pre>
     * query = m.conn.Where("event_id = ? AND favorite_id = ? AND aggregate_version = 1",
     *     fact.PredecessorEventID, row.FavoriteID).Limit(1).Find(&predecessor)
     * </pre>
     *
     * <p>🔴 <b>三个条件都要带</b>：只按 event_id 查的话，一个 event_id 恰好等于
     * 另一个收藏的 {@code favorite.<id>.v1} 就能冒充前驱。
     * {@code PredecessorEventID} 是本服务生成的（favorite_fact.go:141），可预测，
     * 所以 {@code favorite_id} 的交叉校验是必需的。
     */
    @Select("""
            SELECT event_id, favorite_id, aggregate_version, payload, status, retry_count,
                   technical_receipt_id, technical_input_hash, technical_offset,
                   technical_received_at, delivered_at, created_at, updated_at
            FROM favorite_fact_outbox
            WHERE event_id = #{eventId} AND favorite_id = #{favoriteId} AND aggregate_version = 1
            LIMIT 1
            """)
    FavoriteFactOutboxEntity selectAssertPredecessor(@Param("eventId") String eventId,
                                                     @Param("favoriteId") long favoriteId);

    /**
     * 对应 Go {@code authoritativeFavoriteFact} 的「活业务行必须已消失」检查
     * （favorite_authority.go:253-258）：
     * <pre>
     * var liveCount int64
     * m.conn.Model(&FavoriteItem{}).Where("favorite_id = ?", row.FavoriteID).Count(&liveCount)
     * if liveCount != 0 { return ..., ErrFavoriteFactUnavailable }
     * </pre>
     *
     * <p>语义：一条 retract 权威事实只有在<b>业务行确实没了</b>时才成立。
     */
    @Select("SELECT count(*) FROM favorite_item WHERE favorite_id = #{favoriteId}")
    long countLiveItemsByFavoriteId(@Param("favoriteId") long favoriteId);

    /**
     * 对应 Go {@code FavoriteLegacyPreflight} 的孤儿撤回统计
     * （favorite_legacy_preflight.go:86-91）：
     * <pre>
     * SELECT count(*) FROM favorite_fact_outbox retract
     *  WHERE retract.aggregate_version=2 AND NOT EXISTS (
     *    SELECT 1 FROM favorite_fact_outbox assertion
     *    WHERE assertion.favorite_id=retract.favorite_id AND assertion.aggregate_version=1)
     * </pre>
     *
     * <p>语义：「有撤回但没有建立」的孤儿数，必须为 0 才算 clear。
     */
    @Select("""
            SELECT count(*) FROM favorite_fact_outbox retract
            WHERE retract.aggregate_version = 2
              AND NOT EXISTS (
                  SELECT 1 FROM favorite_fact_outbox assertion
                  WHERE assertion.favorite_id = retract.favorite_id
                    AND assertion.aggregate_version = 1)
            """)
    long countOrphanRetracts();

    /**
     * 对应 Go {@code FavoriteLegacyPreflight} 的主查询（favorite_legacy_preflight.go:40-47）。
     *
     * <p>🔴 这是一条 <b>只读</b> LEFT JOIN：拉出「有业务行但没有 v1 断言」的所有行，
     * 连同它们的 marker（可能为 NULL）。marker 的逐字段比对在
     * {@code FavoriteLegacyPreflightRunner} 里做（Go 是 :56-78 的 rows.Scan 循环）。
     *
     * <p>⚠️ {@code ORDER BY i.favorite_id} 逐字照抄 Go（:47）—— 报表要可复现。
     *
     * <p>⚠️ 全部 nullable 列用 {@code java.lang.Long} / {@code String} 承载 null；
     * marker 不存在时那 8 列全是 null，而「null」与「值不匹配」在 Go 里是<b>同一个</b>处置
     * （{@code report.Blocked++}，:74）⇒ Java 侧必须保留「哪些列是 null」的信息。
     */
    @Select("""
            SELECT i.favorite_id, i.user_id, i.folder_id, i.target_type, i.target_id, i.target_revision,
                   m.favorite_id AS marker_favorite_id, m.user_id AS marker_user_id,
                   m.folder_id AS marker_folder_id, m.target_type AS marker_target_type,
                   m.target_id AS marker_target_id, m.target_revision AS marker_target_revision,
                   m.source_snapshot_sha256 AS marker_source_snapshot_sha256,
                   m.approval_ref AS marker_approval_ref
            FROM favorite_item i
            LEFT JOIN favorite_fact_outbox assertion
              ON assertion.favorite_id = i.favorite_id AND assertion.aggregate_version = 1
            LEFT JOIN favorite_legacy_fact_marker m ON m.favorite_id = i.favorite_id
            WHERE assertion.event_id IS NULL
            ORDER BY i.favorite_id
            """)
    List<FavoriteLegacyPreflightRow> selectRowsWithoutAssertion();

    /**
     * 只读预检的一行结果 —— 对应 Go {@code FavoriteLegacyPreflight} 的 rows.Scan 目标（:42-47）。
     *
     * <p><b>L4 表达层差异</b>：B 侧是 {@code public static class}（嵌套在 Mapper 接口里）
     * ⇒ C 侧沿用同一形态（MyBatis 需要它是可实例化的 public 静态类才能映射结果集）。
     */
    class FavoriteLegacyPreflightRow {

        /** {@code i.favorite_id}。 */
        public Long favoriteId;
        /** {@code i.user_id}。 */
        public Long userId;
        /** {@code i.folder_id}。 */
        public Long folderId;
        /** {@code i.target_type}。 */
        public String targetType;
        /** {@code i.target_id}。 */
        public String targetId;
        /** {@code i.target_revision}（可空）。 */
        public String targetRevision;

        /** {@code m.favorite_id}（LEFT JOIN 未命中时为 null）。 */
        public Long markerFavoriteId;
        /** {@code m.user_id}。 */
        public Long markerUserId;
        /** {@code m.folder_id}。 */
        public Long markerFolderId;
        /** {@code m.target_type}。 */
        public String markerTargetType;
        /** {@code m.target_id}。 */
        public String markerTargetId;
        /** {@code m.target_revision}（可空）。 */
        public String markerTargetRevision;
        /** {@code m.source_snapshot_sha256}。 */
        public String markerSourceSnapshotSha256;
        /** {@code m.approval_ref}。 */
        public String markerApprovalRef;
    }
}
