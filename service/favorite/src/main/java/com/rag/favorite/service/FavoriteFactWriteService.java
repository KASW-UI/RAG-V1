package com.rag.favorite.service;

import com.rag.favorite.config.FavoriteRpcProperties;
import com.rag.favorite.domain.entity.FavoriteFactOutboxEntity;
import com.rag.favorite.domain.entity.FavoriteFolderEntity;
import com.rag.favorite.domain.entity.FavoriteItemEntity;
import com.rag.favorite.exception.FavoriteExceptions;
import com.rag.favorite.mapper.FavoriteFactOutboxMapper;
import com.rag.favorite.mapper.FavoriteFolderMapper;
import com.rag.favorite.mapper.FavoriteItemMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 🔴 三个事务写 —— 对应 Go
 * {@code service/favorite/rpc/internal/model/favorite_fact.go:270-378} 的
 * {@code InsertFavorite}、{@code DeleteFavoriteByFavoriteId}、{@code DeleteFolderCascade}。
 *
 * <h2>🔴 为什么必须是<b>独立 Bean</b>（AGENTS.md §3.3）</h2>
 *
 * <p>Go 的这三个方法都是 {@code FavoriteModel} 的内部方法，自己开事务、自己提交。
 * Java 的 {@code @Transactional} 走 Spring AOP 代理 ——
 * <b>同一个类内部自调用不经过代理 ⇒ 注解静默失效</b>。
 * ⇒ 这三个方法必须被 {@code FavoriteDomainService} <b>从外部调用</b>。
 *
 * <p>⚠️ 这是 §3.3 的典型形态：注解在、方法被调、<b>测试全绿</b>，但事务根本没开 ——
 * 「assert 失败后业务行仍然留下」这种 bug 在单测里看不出来。
 * 回归网：{@code FavoriteFactTransactionTest}（注入 outbox 写失败 ⇒ 业务行必须同事务回滚）。
 *
 * <h2>🔴 三个方法的锁顺序不可调换</h2>
 *
 * <p>Go 的注释（favorite_fact.go:267-269）说明了原子性的含义：
 * 「commits the existing favorite ID and its business fact in the same owner database.
 *  An already saved folder target fails its unique key <b>before</b> an outbox row can be committed.」
 * ⇒ <b>业务行与它的事实必须同生共死</b>：不能出现「收藏已存在但没有 assert 事实」。
 *
 * <h2>🔴 v2 开关只影响新建</h2>
 * <p>{@code InsertFavorite} 用当前进程的开关决定新 assert 的 schema（:286-289）；
 * {@code DeleteFavoriteByFavoriteId} / {@code DeleteFolderCascade}
 * 则用 {@code FavoriteAssertionSchemaResolver} 返回的<b>冻结 schema</b> ——
 * 所以进程重启后翻转开关，不会让旧收藏的撤回事件形状错乱。
 *
 * <h2>L4 idiomatization（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.favorite.favorite.rpc.service} 压平为
 *           {@code com.rag.favorite.service}（D7）；</li>
 *       <li>实体 / Mapper / 配置 / 异常的 import 由 {@code com.rag.favorite.favorite.rpc.*} 改为
 *           {@code com.rag.favorite.domain.entity} / {@code com.rag.favorite.mapper} /
 *           {@code com.rag.favorite.config} / {@code com.rag.favorite.exception}（D7）；</li>
 *       <li>构造器改用 Lombok 的 {@code @RequiredArgsConstructor}（D18）⇒ 展开见下；
 *           {@code clock} 改成<b>带初始化的 final 字段</b>（Lombok 不把它算进构造器），
 *           语义与 B 侧构造器体里的 {@code this.clock = Clock.systemUTC();} 逐条等价。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。三个事务方法的<b>锁顺序</b>（folder → item → assert/marker）、
 *       {@code @Transactional} 的三个属性（{@code REQUIRED} / {@code READ_COMMITTED} /
 *       {@code rollbackFor = Exception.class}）、「先写业务行再写 outbox」的次序、
 *       「legacy 分支不产撤回事实」逐字未改。</li>
 *   <li><b>B 出处</b>：{@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/service/FavoriteFactWriteService.java:54-288}
 *       （类声明 :54 · 字段与构造器 :58-80 · {@code insertFavorite} :97-131 ·
 *       {@code deleteFavoriteByFavoriteId} :149-184 · {@code deleteFolderCascade} :209-248 ·
 *       {@code insertOutboxFact} :271-275 · {@code lockFolder} :285-287）。</li>
 * </ol>
 *
 * <h2>⚠️ 日志：<b>不用</b> {@code @Slf4j}，也<b>不用</b> {@code GoStyleLogger}</h2>
 * B 侧本类<b>就是</b> {@code LoggerFactory.getLogger(FavoriteFactWriteService.class)}
 * （B 侧 {@code :56}），只调用 {@code LOG.debug(...)} 一处，<b>没有</b>任何
 * {@code logBusinessErr} / {@code call_chain} 语义的调用点
 * ⇒ ⇒ 换成 {@code GoStyleLogger} 属于<b>凭空新增</b>日志字段（表达层变更且无 B 侧依据）
 * ⇒ ⇒ <b>照抄普通 slf4j</b>（同 {@code ArticleDomainService} 的先例）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * private final FavoriteFolderMapper folderMapper;
 * private final FavoriteItemMapper itemMapper;
 * private final FavoriteFactOutboxMapper outboxMapper;
 * private final FavoriteAssertionSchemaResolver assertionResolver;
 * private final FavoriteFactEnvelopeFactory envelopeFactory;
 * private final FavoriteRpcProperties properties;
 *
 * // Go 全篇用 time.Now()（favorite_fact.go:290/323/365）。注入 Clock 便于测试固定时间。
 * private final Clock clock = Clock.systemUTC();
 *
 * public FavoriteFactWriteService(FavoriteFolderMapper folderMapper,
 *                                 FavoriteItemMapper itemMapper,
 *                                 FavoriteFactOutboxMapper outboxMapper,
 *                                 FavoriteAssertionSchemaResolver assertionResolver,
 *                                 FavoriteFactEnvelopeFactory envelopeFactory,
 *                                 FavoriteRpcProperties properties) {
 *     this.folderMapper = folderMapper;
 *     this.itemMapper = itemMapper;
 *     this.outboxMapper = outboxMapper;
 *     this.assertionResolver = assertionResolver;
 *     this.envelopeFactory = envelopeFactory;
 *     this.properties = properties;
 *     this.clock = Clock.systemUTC();
 * }
 * }</pre>
 *
 * <p>⚠️ 字段声明顺序 = B 侧构造器的参数顺序 ⇒ Lombok 生成的构造器签名逐条一致。
 * ⚠️ {@code clock} 是<b>带初始化的 final 字段</b> ⇒ Lombok 的
 * {@code @RequiredArgsConstructor} <b>不会</b>把它算进构造器参数
 * （Lombok 规则：声明处已初始化的 final 字段不参与）⇒ 与 B 侧
 * 「构造器体里 {@code this.clock = Clock.systemUTC();}」的落库效果一致。
 */
@Service
@RequiredArgsConstructor
public class FavoriteFactWriteService {

    private static final Logger LOG = LoggerFactory.getLogger(FavoriteFactWriteService.class);

    /**
     * ⚠️ <b>字段声明顺序 = B 侧构造器的参数顺序</b> ⇒ Lombok 的
     * {@code @RequiredArgsConstructor} 生成的构造器签名与 B 侧逐条一致
     * （展开块见类 javadoc）。
     */
    private final FavoriteFolderMapper folderMapper;
    private final FavoriteItemMapper itemMapper;
    private final FavoriteFactOutboxMapper outboxMapper;
    private final FavoriteAssertionSchemaResolver assertionResolver;
    private final FavoriteFactEnvelopeFactory envelopeFactory;
    private final FavoriteRpcProperties properties;

    /** Go 全篇用 time.Now()（favorite_fact.go:290/323/365）。注入 Clock 便于测试固定时间。 */
    private final Clock clock = Clock.systemUTC();

    /**
     * 🔴 对应 Go {@code InsertFavorite}（favorite_fact.go:270-296）。
     *
     * <p>Go 的事务体：
     * <pre>
     * SELECT folder FOR UPDATE                          → :276-277
     * folder.UserId != item.UserId ⇒ OwnerMismatch      → :280-282
     * CREATE item                                       → :283-285
     * fact := newFavoriteOutbox(*item, 1, "assert", now, schema)  → :286-290
     * CREATE fact                                       → :291-294
     * </pre>
     *
     * @throws FavoriteExceptions.OwnerMismatchException 归属复核失败（⇒ Go 的普通 error
     *         ⇒ logic 侧输出 {@code Internal/5008}，<b>不是</b> PermissionDenied）
     */
    @Transactional(propagation = Propagation.REQUIRED,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public void insertFavorite(FavoriteItemEntity item) {
        // Go: service/favorite/rpc/internal/model/favorite_fact.go:276-277 —— SELECT folder FOR UPDATE
        FavoriteFolderEntity folder = lockFolder(item.getFolderId());
        if (folder == null) {
            // Go: :277 —— tx.First 的 ErrRecordNotFound 原样返回
            // （调用方 create_favorite_logic.go:79-93 已在事务外查过，
            //  这里再失败只可能是并发删除 ⇒ 归一化为 NotFound 哨兵）
            throw new FavoriteExceptions.NotFoundException("favorite_folder");
        }
        // Go: service/favorite/rpc/internal/model/favorite_fact.go:280-282
        if (!folder.getUserId().equals(item.getUserId())) {
            throw new FavoriteExceptions.OwnerMismatchException();
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:283-285 —— 先写业务行：唯一键冲突必须发生在 outbox 之前
        // 🔴 2026-10-26 第二十三轮修：显式绑定 create_time（Go 的 GORM 用**进程时钟**填
        //   autoCreateTime ⇒ 不绑就是 NULL ⇒ 对外 created_at = 0）。
        //   ⚠️ 与下面的 outbox created_at 是**两次**取时（Go 侧同样是两次：item 由 GORM 填，
        //   outbox 由 newFavoriteOutbox 的 now 参数填 —— favorite_fact.go:290）。
        itemMapper.insertItem(item.getFavoriteId(), item.getFolderId(), item.getUserId(),
                item.getTargetId(), item.getTargetType(), item.getTargetRevision(),
                item.getTitle(), item.getCover(),
                Instant.now(clock).atOffset(ZoneOffset.UTC));

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:286-290 —— schema = m.subjectRefV2Facts ? 2 : 1
        int schema = properties.isSubjectRefV2Facts() ? 2 : 1;
        FavoriteFactOutboxEntity fact =
                envelopeFactory.build(item, 1L, "assert", Instant.now(clock), schema);

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:291-294
        insertOutboxFact(fact);
    }

    /**
     * 🔴 对应 Go {@code DeleteFavoriteByFavoriteId}（favorite_fact.go:300-332）。
     *
     * <p>Go 的事务体：
     * <pre>
     * SELECT item FOR UPDATE                            → :306-312（NotFound ⇒ ErrorNotFound）
     * item.UserId != userID ⇒ OwnerMismatch            → :313-315
     * schema, legacy, err := favoriteAssertionSchema(tx, item)  → :316-318
     * legacy ⇒ 只 DELETE item（不产撤回事实）            → :320-322
     * 否则 ⇒ CREATE v2 retract + DELETE item            → :323-330
     * </pre>
     *
     * <p>🔴 <b>legacy 分支刻意不产撤回事实</b>：
     * 这条收藏<b>从来没有</b> assert 事实被下游接受过，凭空造一条 retract
     * 会让下游收到「我撤回了从未宣告过的东西」。
     */
    @Transactional(propagation = Propagation.REQUIRED,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public void deleteFavoriteByFavoriteId(long favoriteId, long userId) {
        // Go: service/favorite/rpc/internal/model/favorite_fact.go:301-303 —— 参数校验（model 层内部校验）
        if (favoriteId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("invalid favorite delete");
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:306-312
        FavoriteItemEntity item = itemMapper.lockByFavoriteId(favoriteId);
        if (item == null) {
            throw new FavoriteExceptions.NotFoundException("favorite_item");
        }
        // Go: service/favorite/rpc/internal/model/favorite_fact.go:313-315
        if (!item.getUserId().equals(userId)) {
            throw new FavoriteExceptions.OwnerMismatchException();
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:316-318
        int schema = assertionResolver.resolve(item);

        if (schema == FavoriteAssertionSchemaResolver.SCHEMA_LEGACY) {
            // Go: service/favorite/rpc/internal/model/favorite_fact.go:320-322 —— legacy：只删业务行
            itemMapper.deleteByFavoriteId(favoriteId);
            return;
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:323-329 —— 继承冻结的 schema 造 v2 retract
        FavoriteFactOutboxEntity fact =
                envelopeFactory.build(item, 2L, "retract", Instant.now(clock), schema);
        insertOutboxFact(fact);

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:330
        itemMapper.deleteByFavoriteId(favoriteId);
    }

    /**
     * 🔴 对应 Go {@code DeleteFolderCascade}（favorite_fact.go:336-378）。
     *
     * <p>Go 的事务体：
     * <pre>
     * SELECT folder FOR UPDATE                          → :342-348
     * folder.UserId != userID ⇒ OwnerMismatch            → :349-351
     * SELECT items FOR UPDATE ORDER BY favorite_id       → :352-356
     * for each item: schema, legacy := favoriteAssertionSchema(...)
     *                legacy ⇒ continue（不产事件）
     *                否则 ⇒ CREATE v2 retract
     * DELETE items                                      → :373-375
     * DELETE folder                                     → :376
     * </pre>
     *
     * <p>🔴 <b>逐条</b>调 {@code resolve} 而不是批量：每个 item 的 schema 各不相同，
     * 且 legacy 行会被跳过（Go 的 {@code continue}，:363）。
     *
     * <p>Go 注释（:334-335）：
     * 「Deleting an empty folder emits none; a retry cannot fabricate a second
     *  business transition.」⇒ 空夹不产任何事件；重试因为业务行已删而查不到，
     * 也不会再产一次。
     */
    @Transactional(propagation = Propagation.REQUIRED,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public void deleteFolderCascade(long folderId, long userId) {
        // Go: service/favorite/rpc/internal/model/favorite_fact.go:337-339
        if (folderId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("invalid favorite folder delete");
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:342-348
        FavoriteFolderEntity folder = lockFolder(folderId);
        if (folder == null) {
            throw new FavoriteExceptions.NotFoundException("favorite_folder");
        }
        // Go: service/favorite/rpc/internal/model/favorite_fact.go:349-351
        if (!folder.getUserId().equals(userId)) {
            throw new FavoriteExceptions.OwnerMismatchException();
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:352-356 —— FOR UPDATE + ORDER BY favorite_id（确定性顺序）
        List<FavoriteItemEntity> items = itemMapper.lockAllByFolderIdOrdered(folderId);
        for (FavoriteItemEntity item : items) {
            int schema = assertionResolver.resolve(item);
            if (schema == FavoriteAssertionSchemaResolver.SCHEMA_LEGACY) {
                // Go: service/favorite/rpc/internal/model/favorite_fact.go:362-364 —— legacy ⇒ continue，不产撤回事件
                continue;
            }
            FavoriteFactOutboxEntity fact =
                    envelopeFactory.build(item, 2L, "retract", Instant.now(clock), schema);
            insertOutboxFact(fact);
        }

        // Go: service/favorite/rpc/internal/model/favorite_fact.go:373-375
        itemMapper.deleteByFolderId(folderId);
        // Go: service/favorite/rpc/internal/model/favorite_fact.go:376
        folderMapper.deleteByFolderId(folderId);

        LOG.debug("favorite folder cascade deleted: folder_id={} items={}",
                folderId, items.size());
    }

    /**
     * 对应 Go 的 {@code tx.Create(&fact)}（favorite_fact.go:291-294 / :323-329 / :366-369）。
     *
     * <p>🔴 <b>为什么必须显式绑 {@code created_at} / {@code updated_at}</b>（2026-10-25 第七轮修）：
     * <ul>
     *   <li><b>Go 出处</b>：{@code internal/model/favorite_fact.go:158-159} 的 struct 字面量
     *       <b>没有</b>设这两个字段 ⇒ 靠 tag {@code gorm:"autoCreateTime;not null"} /
     *       {@code gorm:"autoUpdateTime;not null"} ⇒ 由 <b>GORM 在应用层赋值</b>
     *       （{@code gorm.io/gorm@v1.31.1/callbacks/create.go:302-306}）
     *       ⇒ 值来自 <b>Go 进程时钟</b>。</li>
     *   <li><b>修前</b>：INSERT <b>不绑</b>这两列 ⇒ 落到 DDL 的 {@code DEFAULT now()}
     *       ⇒ 值来自 <b>PG 服务器时钟</b> ⇒ <b>时钟源不同</b>。</li>
     *   <li><b>为什么算行为差异</b>：{@code created_at} 是取件查询的排序键
     *       （{@code ORDER BY created_at, event_id}，favorite_delivery.go:141-147）
     *       ⇒ 应用进程与数据库之间的时钟偏移会让<b>取件顺序</b>产生分歧。</li>
     * </ul>
     *
     * <p>⚠️ 用 {@link Instant#now(Clock)}（可注入时钟）而不是 {@code Instant.now()} ⇒ 可测。
     * <p>⚠️ 用 {@link ZoneOffset#UTC} 渲染：{@code timestamptz} 存的是绝对时刻 ⇒ 时区只影响显示，
     * 不影响存储值 ⇒ 显式 UTC 是为了不依赖 JVM 默认时区。
     */
    private void insertOutboxFact(FavoriteFactOutboxEntity fact) {
        OffsetDateTime now = Instant.now(clock).atOffset(ZoneOffset.UTC);
        outboxMapper.insertFact(fact.getEventId(), fact.getFavoriteId(),
                fact.getAggregateVersion(), fact.getPayload(), now, now);
    }

    /**
     * 对应 Go 的 {@code tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("folder_id = ?").First()}
     * （{@code service/favorite/rpc/internal/model/favorite_fact.go:214}）。
     *
     * <p>⚠️ <b>必须带 FOR UPDATE</b>：这是「并发删除同一收藏夹」时保证
     * 「一方看到全部 items、另一方看到空集」的锁点。缺它会让两个事务
     * 各算出一半的 retract 集合，然后互相撞上 {@code UNIQUE(favorite_id, aggregate_version)}。
     */
    private FavoriteFolderEntity lockFolder(long folderId) {
        return folderMapper.findByFolderIdForUpdate(folderId);
    }
}
