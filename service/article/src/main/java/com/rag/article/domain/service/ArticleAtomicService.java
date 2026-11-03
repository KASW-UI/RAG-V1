package com.rag.article.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.article.contract.ArticleSyncEvent;
import com.rag.article.contract.CommunityArticleEvent;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.domain.entity.ArticlePublicationEntity;
import com.rag.article.domain.entity.ArticleRevisionEntity;
import com.rag.article.domain.entity.ArticleSyncOutboxEntity;
import com.rag.article.mapper.ArticleDomainOutboxMapper;
import com.rag.article.mapper.ArticleMapper;
import com.rag.article.mapper.ArticlePublicationMapper;
import com.rag.article.mapper.ArticleRevisionMapper;
import com.rag.article.mapper.ArticleSyncOutboxMapper;
import com.rag.common.persistence.JsonbValueTypeHandler;
import com.rag.proto.article.ArticleStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * <p><b>职责</b>：article-rpc 的<b>三处多步写事务</b>的载体 —— <b>刻意独立成 Bean</b>。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/service/ArticleAtomicService.java}
 * （类声明 {@code :64-65} · {@code RFC3339_NANO} {@code :68-69} · 构造器 {@code :78-90} ·
 * {@code retractAndUpdate} {@code :112-145} · {@code retractAndDelete} {@code :158-180} ·
 * {@code retractPublicationTx} {@code :207-235} · {@code queueSyncOutboxWithLock} {@code :264-304} ·
 * {@code acceptSyncResult} {@code :336-419} · {@code PublishOutcome} {@code :437-438} ·
 * {@code publishApprovedRevision} {@code :476-522} · {@code markSyncFailed} {@code :534-542} ·
 * {@code insertSyncOutbox} {@code :555-562} · {@code insertDomainOutbox} {@code :565-572} ·
 * {@code insertRevision} {@code :575-594} · {@code revisionMaxOf} {@code :596-598} ·
 * {@code writeJson} {@code :600-606} · {@code sha256Hex} {@code :608-616} ·
 * {@code extOrEmpty} {@code :618-624} · {@code isBlank} {@code :626-628}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：五处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.service} →
 *             {@code com.rag.article.domain.service}（D7 包压平）；</li>
 *         <li><b>实体 / mapper 包名</b>：{@code ...rpc.domain.*Entity} →
 *             {@code com.rag.article.domain.entity.*Entity}；
 *             {@code ...rpc.mapper.*Mapper} → {@code com.rag.article.mapper.*Mapper}；</li>
 *         <li><b>常量宿主类改名</b>：{@code ArticleSyncEventTypes}（工具类 + 三个 public static 内部类）
 *             ⇒ C 侧拆成 {@link ArticleSyncEvent}（同步事件契约：{@code ArticleSyncEvent} /
 *             {@code ArticleReviewMessage} / {@code ArticleSyncResult} 三个 record）
 *             ＋ {@link CommunityArticleEvent}（社区事件契约）⇒
 *             <b>引用的常量名与工厂方法名逐字保留</b>；</li>
 *         <li><b>字段访问 → record 访问器</b>：B 侧的 {@code ArticleSyncEventTypes.ArticleSyncEvent}
 *             等是「public 字段的普通类」，C 侧是 <b>immutable record</b>（D5）
 *             ⇒ {@code event.eventId} ⇒ {@code event.eventId()}、
 *             {@code result.versionMs} ⇒ {@code result.versionMs()}、
 *             {@code envelope.aggregateVersion} ⇒ {@code envelope.aggregateVersion()}
 *             ⇒ <b>读写的是同一批值</b>，只是访问形式从「字段」变成「访问器方法」；</li>
 *         <li><b>proto 枚举包名</b>：{@code com.rag.article.article.proto.ArticleStatus} →
 *             {@link ArticleStatus}（{@code com.rag.proto.article.ArticleStatus}），
 *             <b>枚举常量名与数值一字未改</b>。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>三处事务的方法签名与事务边界逐字未动</b>（{@code @Transactional} 落在
 *             <b>独立 Bean 的 public 方法</b>上 ⇒ 代理生效 ⇒ 异常回滚）；</li>
 *         <li><b>事务内的语句顺序逐条未动</b>（撤回指针 → 改 article 行 → 写同步 outbox；
 *             撤回指针 → 写同步 outbox → 软删）；</li>
 *         <li><b>幂等判据（六项比对 + 双重判据）逐字未动</b>；</li>
 *         <li><b>冻结 payload 的序列化字节内容未变</b>（仍由 {@code writeJson} 一次性产出，
 *             此后不重新序列化）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号（逐方法另给行号）。</li>
 * </ol>
 *
 * <p><b>为什么必须独立成 Bean</b>（AGENTS.md §3.3）：
 * Spring 的 {@code @Transactional} 靠 AOP 代理生效，而代理<b>只拦截从容器外部进来的调用</b>。
 * 若这三个方法挂在 {@link ArticleDomainService} 本类上、且由同类的私有方法用 {@code this.} 调用
 * （self-invocation），事务会<b>静默失效</b>：代码看着有事务，运行时是 auto-commit，
 * 异常不回滚，<b>不报任何错</b>。
 * AGENTS.md §1.1 列出的 11 处历史回归里第一处就是这个；
 * message-rpc 也因此抽出过 {@code MessageAtomicService}（DRIFT-MSG-001）。
 *
 * <h2>三处事务与 Go 的对应关系</h2>
 * <table border="1">
 *   <caption>Go 闭包 → 本类方法</caption>
 *   <tr><th>Go</th><th>本类</th><th>原子性保障的对象</th></tr>
 *   <tr>
 *     <td>{@code update_article_logic.go:216-227}<br>{@code RunInTx(RetractPublicationTx → UpdateTx → CreateTx)}</td>
 *     <td>{@link #retractAndUpdate}</td>
 *     <td>发布状态降级 + 指针撤回 + 域事件 + 同步事件<b>四者同生共死</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@code delete_article_logic.go:84-96}<br>{@code RunInTx(RetractPublicationTx → CreateTx → DeleteTx)}</td>
 *     <td>{@link #retractAndDelete}</td>
 *     <td>指针撤回 + 域事件 + 同步事件 + 软删<b>同生共死</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@code article_consumer.go:104-136}<br>{@code WithArticleTx}</td>
 *     <td>{@link #queueSyncOutboxWithLock}</td>
 *     <td>行锁 + 二次幂等判定 + 状态推进 + outbox 写入</td>
 *   </tr>
 * </table>
 *
 * <h2>🔴 事务内的语句顺序严格对齐 Go</h2>
 * <ul>
 *   <li>{@link #retractAndUpdate}：{@code RetractPublicationTx}（写域事件 + 改指针）
 *       → {@code UpdateTx}（改 article 行） → {@code CreateTx}（写同步 outbox）。
 *       <b>顺序反过来会让锁获取顺序相反</b>，是潜在死锁来源。</li>
 *   <li>{@link #retractAndDelete}：{@code RetractPublicationTx} → {@code CreateTx}（同步 outbox）
 *       → {@code DeleteTx}（软删）。
 *       ⚠️ 注意<b>同步 outbox 在软删之前</b>：软删后若再插行，article 行已不可见，
 *       下游收到 delete 事件却查不到文章。</li>
 * </ul>
 */
@Service
public class ArticleAtomicService {

    /** Go {@code time.Now().UTC().Format(time.RFC3339Nano)} 的等价格式（9 位小数、Z 结尾）。 */
    private static final DateTimeFormatter RFC3339_NANO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'");

    private final ArticleMapper articleMapper;
    private final ArticlePublicationMapper publicationMapper;
    private final ArticleSyncOutboxMapper syncOutboxMapper;
    private final ArticleDomainOutboxMapper domainOutboxMapper;
    private final ArticleRevisionMapper articleRevisionMapper;
    private final ObjectMapper objectMapper;

    public ArticleAtomicService(ArticleMapper articleMapper,
                                ArticlePublicationMapper publicationMapper,
                                ArticleSyncOutboxMapper syncOutboxMapper,
                                ArticleDomainOutboxMapper domainOutboxMapper,
                                ArticleRevisionMapper articleRevisionMapper,
                                ObjectMapper objectMapper) {
        this.articleMapper = articleMapper;
        this.publicationMapper = publicationMapper;
        this.syncOutboxMapper = syncOutboxMapper;
        this.domainOutboxMapper = domainOutboxMapper;
        this.articleRevisionMapper = articleRevisionMapper;
        this.objectMapper = objectMapper;
    }

    // ==================================================================
    // 1. 已发布文章降级为源态（update 的 A 路径）
    // ==================================================================

    /**
     * 对应 Go {@code updatePublishedToSourceOnly}（{@code update_article_logic.go:197-230}）。
     *
     * <p><b>Go 出处逐字</b>：
     * <pre>
     * RunInTx(ctx, func(tx) error {
     *     RetractPublicationTx(ctx, tx, article, eventID, now)   // ①
     *     ArticleRepo.UpdateTx(ctx, tx, article)                 // ②
     *     return ArticleSyncOutbox.CreateTx(ctx, tx, outbox)      // ③
     * })
     * </pre>
     *
     * <p><b>⚠️ 只有这条路径会建同步 outbox</b>（Go {@code update_article_logic.go:115} 注释：
     * 「只有 A 路径建 outbox 行，B/C 依赖审核消费者在复审后建」）。
     * B/C 路径若也在这里插 outbox，会让推荐系统收到一条<b>未经审核</b>的内容。
     */
    @Transactional
    public void retractAndUpdate(ArticleEntity article, String syncEventID, long versionMs,
                                 String syncReason) {
        // ⓪ 先取 article 行锁 —— 统一全局锁顺序（article → publication）
        //    ⚠️ Go 的 retractAndUpdate（update_article_logic.go:197-230）在 RunInTx 闭包里**不重取**文章，
        //       直接用事务外那份实例，所以它的锁序是 publication → article；
        //       而 retractAndDelete 这里的取行锁让本类自己的顺序是 article → publication。
        //       两条路径在 Go 侧都由「先锁 publication」串行化（RetractPublicationTx 的 FOR UPDATE），
        //       但 Java 侧这两个顺序相反 ⇒ 同一篇文章被「降级」与「删除」并发命中时构成
        //       锁顺序反转，存在死锁风险（本次验证标为 UNRESOLVED，未能证实/证伪）。
        //    取行锁不是为了拿新数据（实体仍用调用点传进来的那份，与 Go 同），
        //    **只为把锁序对齐成 article → publication**，与另外两条事务路径一致：
        //       retractAndDelete   : article → publication
        //       queueSyncOutboxWithLock : article
        //       acceptSyncResult   : article → publication（先 selectByIdForUpdate，再 upsert 指针）
        ArticleEntity locked = articleMapper.selectByIdForUpdate(article.getId());
        if (locked == null) {
            // 行在事务开始前已被软删（Go 侧此时 RetractPublicationTx 仍会执行并随后 DeleteTx 影响 0 行）。
            // 这里提前返回会让「撤回指针」也一并跳过 —— 与 Go 不同，但比让整个事务报错更接近 Go 的结果，
            // 且该窗口需要并发删除才能进入。
            return;
        }

        // ① 撤回社区指针 + 写 retracted 域事件（Go RetractPublicationTx）
        retractPublicationTx(article, syncEventID, versionMs);

        // ② 保存 article 行（status 与 ext_info 已在调用点改好）
        articleMapper.updateById(article);

        // ③ 写 delete 同步事件
        ArticleSyncEvent event = ArticleSyncEvent.newArticleSyncEvent(
                article, "", ArticleSyncEvent.OP_DELETE, syncReason, syncEventID, versionMs);
        insertSyncOutbox(event);
    }

    // ==================================================================
    // 2. 删除文章（retract + outbox + 软删）
    // ==================================================================

    /**
     * 对应 Go {@code delete_article_logic.go:84-96} 的 {@code RunInTx} 闭包。
     *
     * <p><b>⚠️ 不删 MinIO 对象</b>：Go 侧有显式注释
     * （{@code delete_article_logic.go:64-66}）：已发布 revision 的冻结字节必须仍可被
     * 历史 H03 消费者读取，对象回收需要独立的保留期/确认策略。本方法只改数据库。
     */
    @Transactional
    public void retractAndDelete(String articleId, String syncEventID, long versionMs) {
        // ① 取文章（FOR UPDATE）—— Go 在闭包外 FindOne，闭包内用同一实例；
        //    这里在事务内重取，保证行锁与后续写入在同一快照。
        ArticleEntity article = articleMapper.selectByIdForUpdate(articleId);
        if (article == null) {
            // Go: DeleteTx 对不存在的行是 no-op（软删 UPDATE 影响 0 行），不报错。
            // 调用点已完成存在性与作者校验，这里是防御性返回。
            return;
        }

        // ② 撤回指针 + 写 retracted 域事件
        retractPublicationTx(article, syncEventID, versionMs);

        // ③ 写 delete 同步事件（Go 在 DeleteTx 之前）
        ArticleSyncEvent event = ArticleSyncEvent.newArticleSyncEvent(
                article, "", ArticleSyncEvent.OP_DELETE, ArticleSyncEvent.REASON_DELETE,
                syncEventID, versionMs);
        insertSyncOutbox(event);

        // ④ 软删（Go DeleteTx）
        articleMapper.softDelete(articleId);
    }

    /**
     * 对应 Go {@code RetractPublicationTx}（{@code community_article_event.go:110-137}）——
     * <b>必须在调用方的事务内</b>执行。
     *
     * <h2>⚠️ 「指针行不存在」不是错误，而是一条<b>显式的迁移缺口标记</b></h2>
     * Go {@code community_article_event.go:115-119}：
     * <pre>
     * if err == gorm.ErrRecordNotFound {
     *     EnsureExtInfo(article)
     *     article.ExtInfo["h03_publication_gap"] = "legacy_revision_missing"
     *     return nil                          // ← 正常返回
     * }
     * </pre>
     * 即：一个<b>从未发布过</b>（因此没有指针行）的文章被删除或降级时，
     * 只在 {@code ext_info} 留一个缺口标记，<b>不写域事件</b>。
     * <br>⚠️ 不要把它当成错误抛出去：那会让"删除一篇从未发布的草稿"变成失败，
     * 而 Go 是成功的。Go 测试 {@code community_article_test.go} 的
     * {@code "article-delete"} 场景正是"发布过 → 有指针 → 产生第 2 条域事件"，
     * 数量从 1 变 2，验证的是有指针的分支。
     *
     * <h2>⚠️ 「指针状态冲突」<b>必须</b>抛异常</h2>
     * Go {@code community_article_event.go:123-125}：
     * {@code if pointer.State != "published" || pointer.CurrentRevision == "" { return errors.New(...) }}
     * ⇒ 文章行是 PUBLISHED 但指针说已撤回，是<b>数据不一致</b>，必须让事务失败。
     */
    private void retractPublicationTx(ArticleEntity article, String syncEventID, long versionMs) {
        ArticlePublicationEntity pointer = publicationMapper.selectForUpdate(article.getId());
        if (pointer == null) {
            // legacy：无指针行 ⇒ 标记缺口并正常返回
            Map<String, String> extInfo = ArticleSyncEvent.ensureExtInfo(article.getExtInfo());
            extInfo.put(ArticleSyncEvent.H03_GAP_KEY, ArticleSyncEvent.LEGACY_GAP_VALUE);
            article.setExtInfo(extInfo);
            return;
        }
        if (!"published".equals(pointer.getState()) || pointer.getCurrentRevision() == null
                || pointer.getCurrentRevision().isEmpty()) {
            throw new IllegalStateException("community publication pointer conflicts with published article");
        }

        long nextVersion = pointer.getPointerVersion() + 1;
        OffsetDateTime at = OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(versionMs), ZoneOffset.UTC);
        CommunityArticleEvent envelope =
                CommunityArticleEvent.articleDomainEvent(article, null, pointer.getCurrentRevision(),
                        CommunityArticleEvent.OP_RETRACTED, syncEventID, nextVersion,
                        RFC3339_NANO.format(at));
        insertDomainOutbox(envelope);

        publicationMapper.markRetracted(article.getId(), nextVersion, envelope.eventId());

        // ⚠️ Go 只改指针不改 article 的 ext_info（retract 路径的 ext_info 由调用点设置）。
        // 这里把 legacy 标记写回实体即可（指针存在时没有标记要写）。
        article.setExtInfo(ArticleSyncEvent.ensureExtInfo(article.getExtInfo()));
    }

    // ==================================================================
    // 3. 审核通过后写同步 outbox（行锁 + 幂等）
    // ==================================================================

    /**
     * 对应 Go {@code article_consumer.go:104-136} 的 {@code WithArticleTx} 闭包。
     *
     * <h2>🔴 两层幂等，缺一不可</h2>
     * <ol>
     *   <li><b>事务外</b>（{@code article_consumer.go:85-87}）与
     *       <b>事务内</b>（{@code :111-113}）各查一次
     *       {@code reco_sync_state == "pending" && last_sync_event_id != ""}。
     *       事务外那次挡住"Kafka 快速重投"；事务内那次挡住"并发两个消费者"。
     *       ⚠️ 只留事务外那次：两个消费者可能都通过了外层检查、都进入事务，
     *       然后都插 outbox ⇒ 撞 {@code uk_article_sync_outbox_event_key}
     *       ⇒ 其中一个事务失败并重投（虽然最终收敛，但会浪费一轮）。</li>
     *   <li><b>四元组在事务内再校验一次</b>（{@code :105-109}）：
     *       外层已校验过，但两次读取之间文章可能被改。</li>
     * </ol>
     *
     * <h2>⚠️ 返回值语义</h2>
     * {@code true} = 本次真的入队了；{@code false} = 因幂等或四元组不匹配而<b>跳过</b>。
     * 两者对 Kafka 都是"正常结束、不重投"，但<b>可观察副作用不同</b>（是否多了一条 outbox 行），
     * 故必须区分。
     *
     * @param syncEventId  调用方预先生成的雪花 ID（Go 在事务外生成，见 :97-101）
     */
    @Transactional
    public boolean queueSyncOutboxWithLock(String articleId, String authorId, String contentPath,
                                           String reviewNonce, String markdown,
                                           String eventId, long versionMs) {
        ArticleEntity current = articleMapper.selectByIdForUpdate(articleId);
        if (current == null) {
            return false;
        }
        // Go: if current.DeletedAt.Valid || current.Status != REVIEWING || current.Content != msg.ContentPath
        //        || current.AuthorID != msg.AuthorID || current.ExtInfo[ExtReviewNonce] != msg.ReviewNonce { return nil }
        if (current.getDeletedAt() != null
                || current.getStatus() != ArticleStatus.REVIEWING_VALUE
                || !contentPath.equals(current.getContent())
                || !authorId.equals(current.getAuthorId())
                || !reviewNonce.equals(extOrEmpty(current.getExtInfo(), ArticleSyncEvent.EXT_REVIEW_NONCE))) {
            return false;
        }

        Map<String, String> extInfo = ArticleSyncEvent.ensureExtInfo(current.getExtInfo());
        current.setExtInfo(extInfo);
        // 事务内幂等复查
        if ("pending".equals(extInfo.get(ArticleSyncEvent.EXT_RECO_SYNC_STATE))
                && !isBlank(extInfo.get(ArticleSyncEvent.EXT_LAST_SYNC_EVENT_ID))) {
            return false;
        }

        String syncReason = extInfo.get(ArticleSyncEvent.EXT_PENDING_SYNC_REASON);
        if (syncReason == null || syncReason.trim().isEmpty()) {
            syncReason = ArticleSyncEvent.REASON_CREATE;
        }

        ArticleSyncEvent event = ArticleSyncEvent.newArticleSyncEvent(
                current, markdown, ArticleSyncEvent.OP_UPSERT, syncReason, eventId, versionMs);

        // Go 顺序：SetSyncState → tx.Save(current) → CreateTx(outbox)
        ArticleSyncEvent.setSyncState(extInfo, "reco_queued", "pending", syncReason,
                eventId, versionMs, "");
        articleMapper.updateById(current);
        insertSyncOutbox(event);
        return true;
    }

    // ==================================================================
    // 4. 发布成功落地（revision + 指针 + 域事件 + 状态推进）
    // ==================================================================

    /**
     * 对应 Go {@code handleUpsertResult} 的<b>整个 {@code WithArticleTx} 闭包</b>
     * （{@code article_sync_result_consumer.go:74-153}）：判定 + 写入在<b>同一个事务与行锁内</b>。
     *
     * <h2>🔴 判定链的顺序就是幂等语义本身，不可调换</h2>
     * <pre>
     * ① if article.DeletedAt.Valid { return nil }                                   已删
     * ② if ext[last_sync_event_id] != result.EventID
     *      || ext[last_sync_version] != FormatInt(result.VersionMs)
     *      || article.Status == PUBLISHED { return nil }                            已处理/已发布/版本不符
     * ③ if !result.Success { status=REVIEWING; SetSyncState(reco_failed,…); Save } 失败
     * ④ if article.Status != REVIEWING { return nil }                              已不是审核中
     * ⑤ approved := 读回 outbox payload；六项比对不符 ⇒ error                       冻结输入
     * ⑥ 建 revision + 推进指针 + 写域事件 + status=PUBLISHED + published_once        发布
     * </pre>
     * <b>② 必须在 ③ 之前</b>：否则「已发布结果的重放」会走进失败分支，把状态从 PUBLISHED
     * 改回 REVIEWING —— 一条已发布的文章被"回滚"。
     * <br><b>② 必须在 ④ 之前</b>：否则版本不匹配的迟到结果会在④被拦下但已经污染了状态。
     * <br><b>④ 必须在 ⑤ 之前</b>：不处于审核中的文章不该去读 outbox payload（Go 同样如此）。
     *
     * <p><b>⚠️ 全部判定与写入都在行锁内</b>：若在锁外判定，两条并发结果消息可能都通过
     * 「仍是 REVIEWING」检查，然后都算出相同的 {@code max(revision)+1} ⇒ 撞 article_revision 主键。
     *
     * @param objectMapper 用于反序列化冻结的 outbox payload
     * @return 需要在<b>事务外</b>发通知时的信息；不需要通知时返回 {@code null}
     */
    @Transactional
    public PublishOutcome acceptSyncResult(ArticleSyncEvent.ArticleSyncResult result,
                                            ObjectMapper objectMapper) {
        ArticleEntity article = articleMapper.selectByIdForUpdate(result.articleId());

        // ① 已软删 ⇒ 什么都不做（Go :75-77）
        if (article == null || article.getDeletedAt() != null) {
            return null;
        }

        Map<String, String> extInfo = ArticleSyncEvent.ensureExtInfo(article.getExtInfo());
        article.setExtInfo(extInfo);

        // ② 三条 no-op 条件（Go :81-85）
        if (!result.eventId().equals(extOrEmpty(extInfo, ArticleSyncEvent.EXT_LAST_SYNC_EVENT_ID))
                || !String.valueOf(result.versionMs())
                        .equals(extOrEmpty(extInfo, ArticleSyncEvent.EXT_LAST_SYNC_VERSION))
                || article.getStatus() == ArticleStatus.PUBLISHED_VALUE) {
            return null;
        }

        String syncReason = extOrEmpty(extInfo, ArticleSyncEvent.EXT_LAST_SYNC_REASON);

        // ③ 同步失败 ⇒ 回到 REVIEWING 并记录失败态（Go :87-91）
        if (!result.success()) {
            markSyncFailed(article, syncReason, result.eventId(), result.versionMs(), result.errorMessage());
            return null;
        }

        // ④ 已不是审核中 ⇒ 什么都不做（Go :92-94）
        if (article.getStatus() != ArticleStatus.REVIEWING_VALUE) {
            return null;
        }

        // ⑤ 读回冻结的 outbox payload 并做六项比对（Go :95-107）
        ArticleSyncOutboxEntity outboxRow = syncOutboxMapper.selectByEventId(result.eventId());
        if (outboxRow == null) {
            // Go: First(&syncOutbox) 出错 ⇒ return err ⇒ 重投
            throw new IllegalStateException("article sync outbox row not found: " + result.eventId());
        }
        ArticleSyncEvent approved;
        try {
            approved = objectMapper.readValue(outboxRow.getPayload(), ArticleSyncEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("article sync outbox payload is not valid JSON", e);
        }
        if (!ArticleSyncEvent.OP_UPSERT.equals(approved.op())
                || !approved.eventId().equals(result.eventId())
                || approved.versionMs() != result.versionMs()
                || !approved.articleId().equals(article.getId())
                || !approved.authorId().equals(article.getAuthorId())) {
            throw new IllegalStateException("article sync result does not match frozen approved input");
        }

        // ⑥ 一次性通知的判定（Go :146-148）：create 原因 且 从未发布过
        boolean shouldNotify = ArticleSyncEvent.REASON_CREATE.equals(syncReason)
                && extOrEmpty(extInfo, ArticleSyncEvent.EXT_PUBLISHED_ONCE).isEmpty();

        publishApprovedRevision(article, approved);

        // 🔴 通知标题取**当前行**（article.getTitle()），不是冻结 payload 的 approved.title。
        //
        // Go 出处：article_sync_result_consumer.go:147
        //   notifyAuthor, notifyTitle = article.AuthorID, article.Title
        //   —— article 是本次事务里 FOR UPDATE 锁住的那一行；
        //   而 revision 的标题取的是另一个源：:115  Title: approved.Title（冻结输入）。
        //   ⇒ Go 侧这两个标题**本就来自不同的地方**，不可互相代替。
        //
        // 可达性（不是理论上的可能）：作者在 REVIEWING 期间改标题时，
        //   update_article_logic.go:142-144 立即把新标题写进行；
        //   而 enqueueReview（:232-245）只推 Kafka，**不推进** last_sync_event_id
        //   —— 新 event_id 要等审核回调（article_consumer.go:107）才写。
        //   ⇒ 在「标题已改」到「新 event_id 写入」的窗口里，旧同步结果到达时
        //     双重判据（last_sync_event_id + last_sync_version_ms）依然成立
        //     ⇒ 发布照常发生，但 Go 通知里用的是**新标题**。
        //
        // ⚠️ 曾经误取 approved.title，台账当时以「双重判据 ⇒ 两者必相等」结案 —— 该论证不成立
        //    （双重判据约束的是 event_id/version，与标题无关）。
        return shouldNotify
                ? new PublishOutcome(article.getAuthorId(), article.getTitle(), true)
                : null;
    }

    /**
     * {@link #acceptSyncResult} 的返回值：事务提交后需要发通知时的信息。
     *
     * <p>⚠️ <b>只在事务返回后使用</b>：此时文章已发布、revision 已冻结、
     * 域事件已入 outbox —— 通知失败不应回滚这些。
     *
     * <p>⚠️ 字段名用 {@code notifyRequired} 而非 {@code notify}：
     * 后者与 {@code Object.notify()} 冲突，记录组件会拒绝该名称（编译期硬错误）。
     *
     * <h2>{@code title} 的来源：<b>当前行</b>，不是冻结 payload</h2>
     * Go 出处：{@code article_sync_result_consumer.go:147}
     * {@code notifyAuthor, notifyTitle = article.AuthorID, article.Title}。
     * <br>⚠️ <b>不要</b>把它换成冻结输入的 {@code approved.title} ——
     * revision 的标题确实取那个源（{@code :115}），但通知取的是行。
     * 两者在「作者审核期间改了标题」时不同 ⇒ 详见 {@link #acceptSyncResult} 里的可达性论证。
     */
    public record PublishOutcome(String authorId, String title, boolean notifyRequired) {
    }

    /**
     * 对应 Go {@code handleUpsertResult} 的 {@code WithArticleTx} 闭包
     * （{@code article_sync_result_consumer.go:74-153}）的<b>成功分支</b>。
     *
     * <p><b>⚠️ 校验已在调用点完成</b>（event_id / version_ms 匹配、六项冻结输入比对、
     * 状态仍为 REVIEWING），本方法只负责<b>写入</b>。
     * 保持"校验在 service、写入在 atomic"的分层，是为了让本方法的每个分支都是纯粹的写操作，
     * 便于逐条对照 Go 的语句序列。
     *
     * <h2>Go 的语句顺序（逐条对齐，顺序有语义）</h2>
     * <pre>
     * highest = COALESCE(MAX(revision), 0)
     * at := time.Now().UTC(); contentHash := sha256(approved.Markdown)
     * revision := ArticleRevision{ Revision: highest+1, RevisionID: "&lt;id&gt;:r&lt;highest+1&gt;", … }
     * pointer := tx.Where("article_id = ?").First(&amp;pointer)     // ErrRecordNotFound 也继续
     * pointerVersion := pointer.PointerVersion + 1
     * domainEvent := articleDomainEvent(article, &amp;revision, "", "published", result.EventID, pointerVersion, at)
     * tx.Create(&amp;revision)
     * pointer = ArticlePublication{…}
     * tx.Save(&amp;pointer)
     * tx.Create(&amp;domainEvent)
     * article.Status = PUBLISHED; SetSyncState(…); ExtPublishedOnce = "true"
     * tx.Save(article)
     * </pre>
     * ⚠️ <b>域事件的构造在插入之前</b>：校验失败要在任何写入发生<b>之前</b>暴露，
     * 否则会留下"revision 已插入但事件构造失败"的半成品（虽然会回滚，但顺序反了会改变
     * 触发 {@code article_revision_immutable} 与唯一约束的时机）。
     * <p>
     * 发布已批准版本 —— <b>由 {@link #acceptSyncResult} 在同一事务内调用</b>。
     *
     * <p>⚠️ <b>刻意是 package-private 且<b>不加</b> {@code @Transactional}</b>：
     * 它只被 {@link #acceptSyncResult} 以 {@code this.} 调用（self-invocation）。
     * 若在这里加 {@code @Transactional}，注解<b>静默失效</b>（不过代理），
     * 让人误以为"这一步自己是个事务"。真正的边界是 {@link #acceptSyncResult} 上的那个。
     * <br>若将来需要独立入口，必须抽到<b>另一个 Bean</b>（AGENTS.md §3.3）。
     */
    void publishApprovedRevision(ArticleEntity article, ArticleSyncEvent approved) {
        long highest = revisionMaxOf(article.getId());
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);

        ArticleRevisionEntity revision = new ArticleRevisionEntity();
        revision.setArticleId(article.getId());
        revision.setRevision(highest + 1);
        revision.setRevisionId(article.getId() + ":r" + (highest + 1));
        revision.setAuthorId(article.getAuthorId());
        revision.setSourceObject(article.getContent());
        revision.setContentSha256(sha256Hex(approved.markdown()));
        revision.setTitle(approved.title());
        revision.setBrief(approved.brief());
        revision.setCoverImageUrl(approved.coverUrl());
        revision.setManualTypeTag(approved.manualTypeTag());
        revision.setSecondaryTags(approved.secondaryTags());
        revision.setMarkdown(approved.markdown());
        revision.setPublishedAt(at);
        revision.setSyncEventId(approved.eventId());

        // 指针：不存在时 pointerVersion 从 0+1=1 起（Go 侧 ErrRecordNotFound 时结构体零值 → +1 = 1）
        ArticlePublicationEntity pointer = publicationMapper.selectByArticleId(article.getId());
        long pointerVersion = (pointer == null ? 0L : pointer.getPointerVersion()) + 1;

        CommunityArticleEvent envelope =
                CommunityArticleEvent.articleDomainEvent(article, revision, "",
                        CommunityArticleEvent.OP_PUBLISHED, approved.eventId(), pointerVersion,
                        RFC3339_NANO.format(at));

        insertRevision(revision);
        publicationMapper.upsert(article.getId(), revision.getRevisionId(), pointerVersion,
                "published", envelope.eventId());
        insertDomainOutbox(envelope);

        // Go: syncReason == create && ExtPublishedOnce == "" ⇒ 记下要发通知（事务外发）
        String syncReason = extOrEmpty(article.getExtInfo(), ArticleSyncEvent.EXT_LAST_SYNC_REASON);
        boolean shouldNotify = ArticleSyncEvent.REASON_CREATE.equals(syncReason)
                && extOrEmpty(article.getExtInfo(), ArticleSyncEvent.EXT_PUBLISHED_ONCE).isEmpty();

        Map<String, String> extInfo = ArticleSyncEvent.ensureExtInfo(article.getExtInfo());
        article.setExtInfo(extInfo);
        article.setStatus(ArticleStatus.PUBLISHED_VALUE);
        ArticleSyncEvent.setSyncState(extInfo, "published", "succeeded", syncReason,
                approved.eventId(), approved.versionMs(), "");
        extInfo.put(ArticleSyncEvent.EXT_PUBLISHED_ONCE, "true");
        articleMapper.updateById(article);
    }

    /**
     * 对应 Go {@code handleUpsertResult} 的<b>失败分支</b>（{@code :87-91}）。
     *
     * <p>⚠️ <b>失败时状态回到 REVIEWING</b>（不是 REJECTED、不是删除）：
     * 文章仍可被作者修改后重新提交。同步系统失败<b>不等于</b>内容违规 ——
     * 把这两者混为一谈会让"推荐服务抖动"变成"作者的文章被判违规"。
     * <p>
     * <b>同样不加 {@code @Transactional}</b>：只被 {@link #acceptSyncResult} 以
     * {@code this.} 调用，注解会静默失效（见 {@link #publishApprovedRevision} 的说明）。
     */
    void markSyncFailed(ArticleEntity article, String syncReason, String eventId,
                        long versionMs, String errorMessage) {
        Map<String, String> extInfo = ArticleSyncEvent.ensureExtInfo(article.getExtInfo());
        article.setExtInfo(extInfo);
        article.setStatus(ArticleStatus.REVIEWING_VALUE);
        ArticleSyncEvent.setSyncState(extInfo, "reco_failed", "failed", syncReason,
                eventId, versionMs, errorMessage);
        articleMapper.updateById(article);
    }

    // ==================================================================
    // 内部：插入辅助
    // ==================================================================

    /**
     * 写同步发件箱行 —— 对应 Go 的 {@code ArticleSyncOutbox.CreateTx}。
     *
     * <p>⚠️ <b>payload 必须是 {@code event} 的序列化结果，且此后不再重新序列化</b>：
     * 发布回调要读回这行做「冻结输入比对」，
     * 若 payload 与推送出去的字节不是同一份，比对就失去意义。
     */
    void insertSyncOutbox(ArticleSyncEvent event) {
        syncOutboxMapper.insert(
                event.eventId(),
                ArticleSyncEvent.articleSyncEventKey(event.articleId(), event.op(), event.eventId()),
                ArticleSyncEvent.ARTICLE_SYNC_SCOPE,
                event.articleId(),
                writeJson(event));
    }

    /** 写社区域发件箱行 —— 对应 Go 的 {@code tx.Create(&event)}。 */
    void insertDomainOutbox(CommunityArticleEvent envelope) {
        domainOutboxMapper.insert(
                envelope.eventId(),
                envelope.aggregateId(),
                envelope.aggregateVersion(),
                envelope.eventType(),
                writeJson(envelope));
    }

    /** 写 revision 行 —— 对应 Go 的 {@code tx.Create(&revision)}。 */
    void insertRevision(ArticleRevisionEntity revision) {
        String secondaryTagsJson = revision.getSecondaryTags() == null
                ? "null"
                : JsonbValueTypeHandler.writeJson(revision.getSecondaryTags());
        articleRevisionMapper.insert(
                revision.getArticleId(),
                revision.getRevision(),
                revision.getRevisionId(),
                revision.getAuthorId(),
                revision.getSourceObject(),
                revision.getContentSha256(),
                revision.getTitle(),
                revision.getBrief(),
                revision.getCoverImageUrl(),
                revision.getManualTypeTag(),
                secondaryTagsJson,
                revision.getMarkdown(),
                revision.getPublishedAt(),
                revision.getSyncEventId());
    }

    long revisionMaxOf(String articleId) {
        return articleRevisionMapper.selectMaxRevision(articleId);
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize article event payload", e);
        }
    }

    static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String extOrEmpty(Map<String, String> extInfo, String key) {
        if (extInfo == null) {
            return "";
        }
        String value = extInfo.get(key);
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
