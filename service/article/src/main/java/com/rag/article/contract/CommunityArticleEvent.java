package com.rag.article.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * <p><b>职责</b>：article 域的<b>社区事件契约</b> —— community 信封 ＋ revision payload ＋ subject。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mq/ArticleSyncEventTypes.java}
 * （community 常量 {@code :78-95} · {@code CommunitySubject} {@code :302-330} ·
 * {@code CommunityRevisionPayload} {@code :333-403} · {@code CommunityArticleEnvelope} {@code :405-449} ·
 * {@code articleDomainEvent} {@code :488-577} · {@code OP_PUBLISHED} / {@code OP_RETRACTED} {@code :579-580}）
 * ⇒ <b>整段照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.mq} → {@code com.rag.article.contract}（D7 包压平）；</li>
 *         <li><b>形态</b>：B 侧是「工具类里的三个 public static 内部类」，C 侧改为
 *             <b>一个顶层 record + 两个嵌套 record</b>（D5：DTO 层 immutable record）；</li>
 *         <li><b>名称</b>：{@code CommunityArticleEnvelope} → 顶层 {@link CommunityArticleEvent}
 *             （C 侧契约包内的既定命名；B 出处的类型名逐条记录在下方各 record 的 javadoc 里）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON 字段名逐字一致</b>（{@code @JsonProperty} 的值一字未改）；</li>
 *         <li><b>字段顺序一致</b>（{@code @JsonPropertyOrder} 逐字照抄）；</li>
 *         <li><b>校验规则与抛错时机一致</b>（五条前置校验逐条照抄，失败仍让整个事务回滚）。</li>
 *       </ul>
 *       ⇒ 序列化的<b>字节内容</b>与 B 侧一致（下游 Sea-BreakTheWaves 按这些字段名消费）。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🔴 这是 <b>L1 跨服务契约</b>，逐字不可改</h2>
 * 下游服务（Sea-BreakTheWaves）按这些 JSON 字段名消费 community 事件，
 * 且发件箱里已冻结的历史 payload 也按这些名字解析。
 *
 * @param eventId          事件 ID（格式 {@code community.article.<syncEventID>.<operation>}）
 * @param eventType        事件类型（{@code community.article.<operation>}）
 * @param schemaVersion    信封版本（恒为 {@code 1}）
 * @param producer         生产者（恒为 {@code rtw.community.article}）
 * @param aggregateId      聚合根 ID（= article_id）
 * @param aggregateVersion 聚合版本号（> 0）
 * @param operationId      操作 ID（= syncEventID）
 * @param occurredAt       发生时刻（RFC3339Nano 文本）
 * @param payload          revision payload
 */
@JsonPropertyOrder({"event_id", "event_type", "schema_version", "producer", "aggregate_id",
        "aggregate_version", "operation_id", "occurred_at", "payload"})
public record CommunityArticleEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("producer") String producer,
        @JsonProperty("aggregate_id") String aggregateId,
        @JsonProperty("aggregate_version") long aggregateVersion,
        @JsonProperty("operation_id") String operationId,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("payload") RevisionPayload payload) {

    // ==================== community 常量（B 侧 :78-95 · :579-580） ====================

    /** 生产者标识。 */
    public static final String COMMUNITY_PRODUCER = "rtw.community.article";

    /** subject 的 authority。 */
    public static final String COMMUNITY_SUBJECT_ISSUER = "rtw.identity";

    /** subject 的 tenant。 */
    public static final String COMMUNITY_SUBJECT_TENANT = "platform";

    /**
     * 对应 B 侧常量 {@code SchemaVersion: 1}（{@code community_article_event.go:91}）。
     *
     * <p><b>是 int 而非 String</b>：B 侧的 {@code communityArticleEnvelope.SchemaVersion} 是
     * {@code int}，序列化成 JSON number；
     * 本仓共享的 {@code com.rag.common.communityfact.Wire.Event#schemaVersion} <b>同样是 int</b>
     * ⇒ 两侧一致用 number。
     * <br>⚠️ 顺带记一条排查经验：下游 Sea-BreakTheWaves 里<b>另有一批</b>
     * {@code schema_version string} 的结构体（如 {@code async/rpc/.../wiki_compile_native_*.go}），
     * 那是 wiki 编译票据的 payload 版本，与本信封<b>不是同一个字段</b>。
     * 看到"下游是 string"就改这里会改错。
     */
    public static final int COMMUNITY_SCHEMA_VERSION = 1;

    /** published 操作。 */
    public static final String OP_PUBLISHED = "published";

    /** retracted 操作。 */
    public static final String OP_RETRACTED = "retracted";

    /**
     * 对应 B 侧 {@code communitySubject}（{@code community_article_event.go:17-21}）。
     *
     * <p>⚠️ <b>字段名与 B 侧 Java 字段名不一致是有意的</b>：B 侧的三个字段是
     * {@code AuthorityID / TenantID / SubjectID}，但 <b>JSON 名</b>是
     * {@code authority_id / tenant_id / subject_id}。
     * 而 B 侧构造时传的是 {@code communitySubject{"rtw.identity", "platform", article.AuthorID}}
     * —— 三个位置分别对应 JSON 的三个名字。Java 侧照此对齐。
     *
     * @param authorityId authority
     * @param tenantId    tenant
     * @param subjectId   主体 ID（= authorId）
     */
    @JsonPropertyOrder({"authority_id", "tenant_id", "subject_id"})
    public record Subject(
            @JsonProperty("authority_id") String authorityId,
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("subject_id") String subjectId) {
    }

    /**
     * 对应 B 侧 {@code communityRevisionPayload}（{@code community_article_event.go:23-40}）。
     *
     * <h2>⚠️ 三条强约束（B 侧是显式校验，改动会让"社区文章永不进知识库"失效）</h2>
     * <ol>
     *   <li>{@link #wikiModuleId} <b>恒为 null</b> —— B 侧注释
     *       {@code // never inferred from a community article}。
     *       非空会让下游把社区文章误挂到知识库模块上。</li>
     *   <li>{@link #searchEvidence} <b>仅 {@code published} 为 true</b>；
     *       retracted 事件为 false（它是删除通知，不是可检索证据）。</li>
     *   <li>{@link #markdown} 是 {@code omitempty} —— retracted 事件<b>不带正文</b>
     *       （B 侧 {@code community_article_event.go:83-89} 的 else 分支不设 Markdown）。</li>
     * </ol>
     *
     * @param articleId       文章 ID
     * @param revisionId      revision ID
     * @param revision        revision 号
     * @param author          subject（作者）
     * @param sourceRef       来源引用
     * @param sourceObject    来源对象（MinIO key 快照）
     * @param contentSha256   内容 sha256（hex）
     * @param title           标题
     * @param brief           摘要
     * @param coverImageUrl   封面图 URL
     * @param manualTypeTag   一级标签
     * @param secondaryTags   二级标签
     * @param markdown        正文（<b>omitempty</b>）
     * @param operation       操作（{@code published} / {@code retracted}）
     * @param searchEvidence  是否可检索证据
     * @param wikiModuleId    知识库模块 ID（<b>恒为 null</b>）
     */
    @JsonPropertyOrder({"article_id", "revision_id", "revision", "subject_ref", "source_ref", "source_object",
            "content_sha256", "title", "brief", "cover_image_url", "manual_type_tag", "secondary_tags",
            "markdown", "operation", "search_evidence", "wiki_module_id"})
    public record RevisionPayload(
            @JsonProperty("article_id") String articleId,
            @JsonProperty("revision_id") String revisionId,
            @JsonProperty("revision") long revision,
            @JsonProperty("subject_ref") Subject author,
            @JsonProperty("source_ref") String sourceRef,
            @JsonProperty("source_object") String sourceObject,
            @JsonProperty("content_sha256") String contentSha256,
            @JsonProperty("title") String title,
            @JsonProperty("brief") String brief,
            @JsonProperty("cover_image_url") String coverImageUrl,
            @JsonProperty("manual_type_tag") String manualTypeTag,
            @JsonProperty("secondary_tags") List<String> secondaryTags,
            @JsonProperty("markdown") @JsonInclude(JsonInclude.Include.NON_EMPTY) String markdown,
            @JsonProperty("operation") String operation,
            @JsonProperty("search_evidence") boolean searchEvidence,
            @JsonProperty("wiki_module_id") String wikiModuleId) {
    }

    // ==================== 事件工厂（B 侧 :488-577） ====================

    /**
     * 对应 B 侧 {@code articleDomainEvent}（{@code community_article_event.go:54-104}）的
     * <b>校验 + 构造</b>部分。
     *
     * <h2>⚠️ 五条前置校验必须保留（B 侧用 error 返回，Java 用异常）</h2>
     * <ol>
     *   <li>article 非空、ID 非空、AuthorID 非空、{@code aggregateVersion > 0}、
     *       syncEventID 非空、operation ∈ {published, retracted}；</li>
     *   <li><b>{@code AuthorID} 必须能解析成 uid &gt; 0</b>
     *       （B 侧 {@code strconv.ParseInt(article.AuthorID, 10, 64)}）——
     *       它同时进 {@code subject_ref.subject_id} 与下游鉴权；</li>
     *   <li>published 时 revision 必须<b>六项齐全</b>：articleID 匹配、revision &gt; 0、
     *       revisionID 非空、contentSHA256 非空、markdown 非空；</li>
     *   <li>retracted 时 {@code previousRevision} 必须非空（否则无法指明撤回的是哪一版）；</li>
     *   <li>{@code wiki_module_id} <b>恒 null</b>、{@code search_evidence} 仅 published 为 true。</li>
     * </ol>
     *
     * <p>⚠️ <b>校验失败必须让整个事务失败</b>（抛异常），
     * 而不是"跳过这条事件继续"。B 侧返回 error 会让
     * {@code RetractPublicationTx} / {@code handleUpsertResult} 的事务回滚 ——
     * 这正是 B 侧测试 {@code TestArticleDomainOutboxFailureRollsBackPublication} 断言的。
     *
     * @param previousRevision 仅 retracted 用；published 传 {@code null}
     * @return 可直接落库的 {@link CommunityArticleEvent}
     */
    public static CommunityArticleEvent articleDomainEvent(
            com.rag.article.domain.entity.ArticleEntity article,
            com.rag.article.domain.entity.ArticleRevisionEntity revision,
            String previousRevision,
            String operation, String syncEventID,
            long aggregateVersion, String occurredAtRfc3339Nano) {
        if (article == null || isBlank(article.getId()) || isBlank(article.getAuthorId())
                || aggregateVersion <= 0 || isBlank(syncEventID)
                || !(OP_PUBLISHED.equals(operation) || OP_RETRACTED.equals(operation))) {
            throw new IllegalArgumentException("invalid community article event");
        }
        long uid = ArticleSyncEvent.parsePositiveUid(article.getAuthorId());
        if (uid <= 0) {
            throw new IllegalArgumentException("invalid community article author UID");
        }

        String eventId = "community.article." + syncEventID + "." + operation;

        Subject subject = new Subject(COMMUNITY_SUBJECT_ISSUER, COMMUNITY_SUBJECT_TENANT, article.getAuthorId());
        // Go: SearchEvidence: operation == "published"
        boolean searchEvidence = OP_PUBLISHED.equals(operation);
        // Go: WikiModule: nil —— 恒 null，社区文章永不进知识库

        RevisionPayload payload;
        if (OP_PUBLISHED.equals(operation)) {
            if (revision == null || !article.getId().equals(revision.getArticleId())
                    || revision.getRevision() == null || revision.getRevision() <= 0
                    || isBlank(revision.getRevisionId()) || isBlank(revision.getContentSha256())
                    || isBlank(revision.getMarkdown())) {
                throw new IllegalArgumentException("published article revision is incomplete");
            }
            payload = new RevisionPayload(
                    article.getId(),
                    revision.getRevisionId(),
                    revision.getRevision(),
                    subject,
                    "rtw.community.article/" + revision.getRevisionId(),
                    revision.getSourceObject(),
                    revision.getContentSha256(),
                    revision.getTitle(),
                    revision.getBrief(),
                    revision.getCoverImageUrl(),
                    revision.getManualTypeTag(),
                    revision.getSecondaryTags() == null
                            ? null : new java.util.ArrayList<>(revision.getSecondaryTags()),
                    revision.getMarkdown(),
                    operation,
                    searchEvidence,
                    null);
        } else {
            if (isBlank(previousRevision)) {
                throw new IllegalArgumentException("retraction requires the published revision");
            }
            payload = new RevisionPayload(
                    article.getId(),
                    previousRevision,
                    0L,
                    subject,
                    "rtw.community.article/" + previousRevision,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    operation,
                    searchEvidence,
                    null);
        }

        return new CommunityArticleEvent(
                eventId,
                "community.article." + operation,
                COMMUNITY_SCHEMA_VERSION,
                COMMUNITY_PRODUCER,
                article.getId(),
                aggregateVersion,
                syncEventID,
                occurredAtRfc3339Nano,
                payload);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
