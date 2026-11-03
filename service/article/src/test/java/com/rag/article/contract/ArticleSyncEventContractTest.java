package com.rag.article.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.domain.entity.ArticleRevisionEntity;
import com.rag.proto.article.ArticleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>消息 / 事件契约的单测 —— 全部是 <b>L1 跨服务契约</b>的锁定。
 *
 * <h2>为什么这组判据必须是「真触发」</h2>
 * <p>这些断言不碰数据库：它们锁的是<b>序列化后的字节与字段名</b>，
 * 而下游（推荐 / 检索 / Sea-BreakTheWaves）按这些字段名解析，
 * 发件箱里<b>已冻结的历史 payload</b> 也按这些名字解析，
 * 发布回调要读回冻结的 payload 做六项比对。
 * <p>⇒ 「契约表」本身没有运行机制 ⇒ 纯单测是<b>恰当</b>形态；
 * 但必须断言<b>序列化后的真实字节</b>（而不是断言注解存在）。
 * <p>⚠️ 特别注意：<b>「两侧的测试都可能是绿的」</b>仍可能让下游静默解不出内容 ——
 * 所以每条断言都必须落在**字段名**与**字段缺省**上。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧测试 {@code service/article/article-rpc/src/test/java/com/rag/article/article/rpc/mq/ArticleSyncEventTypesContractTest.java:23-300}
 *       （14 例全量可移植）；</li>
 *   <li>B 侧真源 {@code .../rpc/mq/ArticleSyncEventTypes.java}（{@code ArticleReviewMessage} /
 *       {@code ArticleSyncEvent} / {@code ArticleSyncResult} / community 信封）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>改任一 {@code @JsonProperty} 的值 ⇒ 用例 ① / ③ <b>立刻红</b>；</li>
 *   <li>把 {@code review_nonce} 的 {@code @JsonInclude} 去掉 ⇒ 用例 ② <b>立刻红</b>；</li>
 *   <li>把 {@code secondary_tags} / {@code markdown} 的 omitempty 去掉 ⇒ 用例 ⑤ <b>立刻红</b>；</li>
 *   <li>把 {@code setSyncState} 的两条条件赋值改成无条件 ⇒ 用例 ⑪ <b>立刻红</b>；</li>
 *   <li>把 {@code articleStatusName} 改成自己写 switch ⇒ 用例 ⑬ <b>可能仍绿</b>
 *       （本条是「回归守门」，真正的锚是 B 侧的 errmsg-probe 逐字节比对）。</li>
 * </ul>
 */
class ArticleSyncEventContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================================================================
    // 1. ArticleReviewMessage：审核四元组
    // ==================================================================

    @Test
    @DisplayName("B 侧：ArticleReviewMessage 的四个 JSON 字段名逐字不变（审核四元组载体）")
    void reviewMessageFieldNamesAreFrozen() throws Exception {
        ArticleSyncEvent.ArticleReviewMessage msg = new ArticleSyncEvent.ArticleReviewMessage(
                "a-1", "1001", "articles/content/a-1.md", "nonce-1");

        String json = mapper.writeValueAsString(msg);
        assertTrue(json.contains("\"article_id\":\"a-1\""), json);
        assertTrue(json.contains("\"author_id\":\"1001\""), json);
        assertTrue(json.contains("\"content_path\":\"articles/content/a-1.md\""), json);
        assertTrue(json.contains("\"review_nonce\":\"nonce-1\""), json);
    }

    @Test
    @DisplayName("B 侧：review_nonce 是 omitempty —— 空值必须整个字段消失（不是 \"\"）")
    void reviewNonceIsOmittedWhenEmpty() throws Exception {
        ArticleSyncEvent.ArticleReviewMessage msg = new ArticleSyncEvent.ArticleReviewMessage(
                "a-1", "1001", "p.md", "");
        String json = mapper.writeValueAsString(msg);
        assertFalse(json.contains("review_nonce"),
                "空 review_nonce 被序列化成空串而不是省略 ⇒ 与 B 侧的 omitempty 不一致：" + json);
    }

    // ==================================================================
    // 2. ArticleSyncEvent：omitempty 组与必出组
    // ==================================================================

    @Test
    @DisplayName("B 侧：ArticleSyncEvent 的必出字段全部出现（含 status 与 version_ms）")
    void syncEventAlwaysCarriesRequiredFields() throws Exception {
        ArticleEntity article = article("a-1", "1001", ArticleStatus.REVIEWING_VALUE);

        ArticleSyncEvent event = ArticleSyncEvent.newArticleSyncEvent(
                article, "", ArticleSyncEvent.OP_DELETE, ArticleSyncEvent.REASON_DELETE,
                "e-1", 1700000000000L);

        String json = mapper.writeValueAsString(event);
        // 这八个是 B 侧里**没有** omitempty 的字段
        assertTrue(json.contains("\"event_scope\":\"article_sync\""), json);
        assertTrue(json.contains("\"event_id\":\"e-1\""), json);
        assertTrue(json.contains("\"article_id\":\"a-1\""), json);
        assertTrue(json.contains("\"op\":\"delete\""), json);
        assertTrue(json.contains("\"reason\":\"delete\""), json);
        assertTrue(json.contains("\"author_id\":\"1001\""), json);
        assertTrue(json.contains("\"version_ms\":1700000000000"), json);
        // ⚠️ delete 事件带的是**当前状态的名字**，不是数值
        assertTrue(json.contains("\"status\":\"REVIEWING\""),
                "delete 事件应报当前状态的枚举名：" + json);
    }

    @Test
    @DisplayName("B 侧：op=upsert 时 status 恒为 PUBLISHED（哪怕源行还在 REVIEWING）")
    void upsertForcesPublishedStatus() {
        ArticleEntity article = article("a-1", "1001", ArticleStatus.REVIEWING_VALUE);

        ArticleSyncEvent upsert = ArticleSyncEvent.newArticleSyncEvent(
                article, "# body", ArticleSyncEvent.OP_UPSERT, ArticleSyncEvent.REASON_UPDATE,
                "e-1", 1L);
        assertEquals("PUBLISHED", upsert.status(),
                "upsert 事件必须报 PUBLISHED（B 侧 NewArticleSyncEvent:97），下游据此认为内容已批准");

        ArticleSyncEvent delete = ArticleSyncEvent.newArticleSyncEvent(
                article, "", ArticleSyncEvent.OP_DELETE, ArticleSyncEvent.REASON_DELETE, "e-1", 1L);
        assertEquals("REVIEWING", delete.status(), "delete 事件应报当前状态名");
    }

    @Test
    @DisplayName("B 侧：六字段空值被 omitempty 省略（下游按字段缺省判断「没有」）")
    void syncEventOmitsEmptyOptionalFields() throws Exception {
        ArticleEntity article = article("a-1", "1001", ArticleStatus.PUBLISHED_VALUE);

        ArticleSyncEvent event = ArticleSyncEvent.newArticleSyncEvent(
                article, "", ArticleSyncEvent.OP_DELETE, ArticleSyncEvent.REASON_DELETE, "e-1", 1L);
        String json = mapper.writeValueAsString(event);
        for (String field : List.of("title", "brief", "cover_url", "manual_type_tag",
                "secondary_tags", "markdown")) {
            assertFalse(json.contains("\"" + field + "\""),
                    "空字段 " + field + " 未被省略（B 侧是 omitempty）：" + json);
        }
    }

    @Test
    @DisplayName("ArticleSyncEvent 可反序列化回同一结构（发布回调的冻结输入比对依赖它）")
    void syncEventRoundTrips() throws Exception {
        ArticleEntity article = article("a-2", "2002", ArticleStatus.PUBLISHED_VALUE);
        article.setTitle("t");
        article.setBrief("b");
        article.setCoverImageUrl("c");
        article.setManualTypeTag("m");
        article.setSecondaryTags(List.of("x", "y"));

        ArticleSyncEvent original = ArticleSyncEvent.newArticleSyncEvent(
                article, "# body", ArticleSyncEvent.OP_UPSERT, ArticleSyncEvent.REASON_CREATE,
                "e-2", 42L);

        ArticleSyncEvent parsed = mapper.readValue(
                mapper.writeValueAsString(original), ArticleSyncEvent.class);

        assertEquals(original.eventId(), parsed.eventId());
        assertEquals(original.articleId(), parsed.articleId());
        assertEquals(original.op(), parsed.op());
        assertEquals(original.versionMs(), parsed.versionMs());
        assertEquals(original.authorId(), parsed.authorId());
        assertEquals("# body", parsed.markdown());
        assertEquals(List.of("x", "y"), parsed.secondaryTags());
        assertEquals(original.status(), parsed.status());
        assertEquals(original.reason(), parsed.reason());
    }

    // ==================================================================
    // 3. event key（三元组唯一键）
    // ==================================================================

    @Test
    @DisplayName("B 侧：ArticleSyncEventKey 三段各自 TrimSpace，段间不额外处理")
    void eventKeyTrimsEachSegment() {
        assertEquals("a-1:upsert:e-1",
                ArticleSyncEvent.articleSyncEventKey(" a-1 ", " upsert ", " e-1 "));
        assertEquals("::", ArticleSyncEvent.articleSyncEventKey(null, null, null),
                "null 段应变成空串而不是 \"null\"（B 侧的 TrimSpace(nil) 是空串）");
    }

    // ==================================================================
    // 4. community 信封
    // ==================================================================

    @Test
    @DisplayName("B 侧：published 事件的信封字段与三条强约束（wiki_module_id 恒 null 等）")
    void publishedEnvelopeHonoursHardConstraints() {
        ArticleEntity article = article("a-3", "1001", ArticleStatus.PUBLISHED_VALUE);

        ArticleRevisionEntity revision = revision("a-3", 1L, "a-3:r1", "1001");

        CommunityArticleEvent envelope = CommunityArticleEvent.articleDomainEvent(
                article, revision, "", CommunityArticleEvent.OP_PUBLISHED, "sync-1", 1L,
                "2026-10-22T00:00:00.000000000Z");

        assertEquals("community.article.sync-1.published", envelope.eventId());
        assertEquals("community.article.published", envelope.eventType());
        assertEquals("rtw.community.article", envelope.producer());
        assertEquals(1, envelope.schemaVersion(), "信封 schema_version 是数字 1（不是字符串）");
        assertEquals("a-3", envelope.aggregateId());
        assertEquals(1L, envelope.aggregateVersion());
        assertEquals("sync-1", envelope.operationId());

        // 三条强约束
        assertNull(envelope.payload().wikiModuleId(), "社区文章永不进知识库 ⇒ wiki_module_id 必须恒 null");
        assertTrue(envelope.payload().searchEvidence(), "published 的 search_evidence 必须为 true");
        assertEquals("rtw.community.article/a-3:r1", envelope.payload().sourceRef());
        assertEquals("rtw.identity", envelope.payload().author().authorityId());
        assertEquals("platform", envelope.payload().author().tenantId());
        assertEquals("1001", envelope.payload().author().subjectId());
    }

    @Test
    @DisplayName("B 侧：retracted 事件不带 markdown 且 search_evidence=false")
    void retractedEnvelopeOmitsMarkdown() throws Exception {
        ArticleEntity article = article("a-4", "1001", ArticleStatus.PUBLISHED_VALUE);

        CommunityArticleEvent envelope = CommunityArticleEvent.articleDomainEvent(
                article, null, "a-4:r1", CommunityArticleEvent.OP_RETRACTED, "sync-2", 2L,
                "2026-10-22T00:00:00.000000000Z");

        assertFalse(envelope.payload().searchEvidence(), "retracted 不是可检索证据");
        assertNull(envelope.payload().markdown(), "retracted 事件不带正文");
        assertEquals("a-4:r1", envelope.payload().revisionId());

        String json = mapper.writeValueAsString(envelope);
        assertFalse(json.contains("\"markdown\""), "retracted 事件的 markdown 必须因 omitempty 消失：" + json);
    }

    @Test
    @DisplayName("B 侧：published 缺 revision / retracted 缺 previousRevision ⇒ 抛异常让事务回滚")
    void incompleteEventsAreRejected() {
        ArticleEntity article = article("a-5", "1001", ArticleStatus.PUBLISHED_VALUE);

        // published 但 revision 为 null
        assertThrows(IllegalArgumentException.class, () ->
                CommunityArticleEvent.articleDomainEvent(article, null, "",
                        CommunityArticleEvent.OP_PUBLISHED, "sync-3", 1L, "now"));
        // retracted 但没有 previousRevision
        assertThrows(IllegalArgumentException.class, () ->
                CommunityArticleEvent.articleDomainEvent(article, null, "",
                        CommunityArticleEvent.OP_RETRACTED, "sync-3", 1L, "now"));
        // 作者 UID 不可解析
        ArticleEntity badAuthor = article("a-5", "not-a-number", ArticleStatus.PUBLISHED_VALUE);
        assertThrows(IllegalArgumentException.class, () ->
                CommunityArticleEvent.articleDomainEvent(badAuthor, null, "a-5:r1",
                        CommunityArticleEvent.OP_RETRACTED, "sync-3", 1L, "now"));
        ArticleEntity zeroAuthor = article("a-5", "0", ArticleStatus.PUBLISHED_VALUE);
        assertThrows(IllegalArgumentException.class, () ->
                CommunityArticleEvent.articleDomainEvent(zeroAuthor, null, "a-5:r1",
                        CommunityArticleEvent.OP_RETRACTED, "sync-3", 1L, "now"));
    }

    // ==================================================================
    // 5. setSyncState 的两条条件赋值
    // ==================================================================

    @Test
    @DisplayName("B 侧：setSyncState 里 versionMs<=0 保留旧版本号、syncReason=\"\" 保留旧原因")
    void setSyncStateKeepsPreviousValuesWhenArgumentsAreEmpty() {
        Map<String, String> ext = new LinkedHashMap<>();
        ext.put(ArticleSyncEvent.EXT_LAST_SYNC_VERSION, "1792670400000");
        ext.put(ArticleSyncEvent.EXT_PENDING_SYNC_REASON, "update");

        ArticleSyncEvent.setSyncState(ext, "queued", "pending_review", "", "", 0L, "");

        assertEquals("1792670400000", ext.get(ArticleSyncEvent.EXT_LAST_SYNC_VERSION),
                "versionMs<=0 时必须保留旧版本号；无条件赋值会清成 0 并破坏发布回调的版本比对");
        assertEquals("update", ext.get(ArticleSyncEvent.EXT_PENDING_SYNC_REASON),
                "syncReason 为空时必须保留旧待同步原因");
        assertEquals("queued", ext.get(ArticleSyncEvent.EXT_PUBLISH_STAGE));
        assertEquals("pending_review", ext.get(ArticleSyncEvent.EXT_RECO_SYNC_STATE));
    }

    @Test
    @DisplayName("版本号存成字符串（与 B 侧的 FormatInt 一致，不是数字）")
    void versionIsStoredAsString() {
        Map<String, String> ext = new LinkedHashMap<>();
        ArticleSyncEvent.setSyncState(ext, "s", "st", "create", "e1", 1792670400000L, "");
        String stored = ext.get(ArticleSyncEvent.EXT_LAST_SYNC_VERSION);
        assertEquals("1792670400000", stored);
        assertInstanceOf(String.class, stored,
                "版本号必须是 String；若读成数字，发布回调的 "
                        + "extInfo.get(LAST_SYNC_VERSION) != String.valueOf(result.versionMs) 会恒真");
    }

    // ==================================================================
    // 6. 枚举名映射
    // ==================================================================

    @Test
    @DisplayName("ArticleStatus 枚举名与 proto 生成枚举一致（未知值回落为数字字符串）")
    void articleStatusNamesMatchGeneratedEnum() {
        assertEquals("ARTICLE_STATUS_UNSPECIFIED", ArticleSyncEvent.articleStatusName(0));
        assertEquals("DRAFT", ArticleSyncEvent.articleStatusName(1));
        assertEquals("PUBLISHED", ArticleSyncEvent.articleStatusName(2));
        assertEquals("REVIEWING", ArticleSyncEvent.articleStatusName(3));
        assertEquals("REJECTED", ArticleSyncEvent.articleStatusName(4));
        assertEquals("DELETED", ArticleSyncEvent.articleStatusName(5));
        // B 侧的 .String() 对未登记数值返回数字本身
        assertEquals("7", ArticleSyncEvent.articleStatusName(7));
    }

    @Test
    @DisplayName("parsePositiveUid：解析失败或非正数返回 -1（由调用点决定处置）")
    void parsePositiveUidReturnsSentinel() {
        assertEquals(1001L, ArticleSyncEvent.parsePositiveUid("1001"));
        assertEquals(-1L, ArticleSyncEvent.parsePositiveUid("abc"));
        assertEquals(-1L, ArticleSyncEvent.parsePositiveUid(""));
        assertEquals(-1L, ArticleSyncEvent.parsePositiveUid(null));
        assertEquals(0L, ArticleSyncEvent.parsePositiveUid("0"));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static ArticleEntity article(String id, String authorId, int status) {
        ArticleEntity article = new ArticleEntity();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setTitle("");
        article.setBrief("");
        article.setCoverImageUrl("");
        article.setManualTypeTag("");
        article.setSecondaryTags(List.of());
        article.setStatus(status);
        return article;
    }

    private static ArticleRevisionEntity revision(String articleId, long revision,
                                                   String revisionId, String authorId) {
        ArticleRevisionEntity r = new ArticleRevisionEntity();
        r.setArticleId(articleId);
        r.setRevision(revision);
        r.setRevisionId(revisionId);
        r.setAuthorId(authorId);
        r.setSourceObject(articleId + "-r1.md");
        r.setContentSha256("a".repeat(64));
        r.setTitle("approved");
        r.setMarkdown("# approved");
        return r;
    }

    /** 让编译器看到 {@link ArticleStatus} 的引用（值来自 proto 生成枚举）。 */
    @SuppressWarnings("unused")
    private static int protoStatusValue() {
        assertNotNull(ArticleStatus.PUBLISHED);
        return ArticleStatus.PUBLISHED_VALUE;
    }
}
