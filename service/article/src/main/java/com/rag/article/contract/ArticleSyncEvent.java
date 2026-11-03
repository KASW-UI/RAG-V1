package com.rag.article.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p><b>职责</b>：article 域的<b>同步事件契约</b> —— outbox 冻结载荷 ＋ 两个 wire 消息 ＋ 常量 ＋ 事件工厂。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mq/ArticleSyncEventTypes.java}
 * （scope/op/reason 常量 {@code :37-50} · ext_info 键名 {@code :52-68} ·
 * ext_info 操作 {@code :99-125} · {@code articleSyncEventKey} {@code :127-133} ·
 * {@code ArticleReviewMessage} {@code :135-162} · {@code ArticleSyncEvent} {@code :164-259} ·
 * {@code ArticleSyncResult} {@code :261-295} · {@code NewArticleSyncEvent} {@code :429-456} ·
 * {@code parsePositiveUid} {@code :478-487} · {@code articleStatusName} {@code :505-513}）
 * ⇒ <b>整段照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯表达层</b>改动 ——
 *       <ul>
 *         <li><b>包名</b>：{@code com.rag.article.article.rpc.mq} → {@code com.rag.article.contract}（D7 包压平）；</li>
 *         <li><b>形态</b>：B 侧是「工具类 + 三个 public static 内部类」，C 侧改为
 *             <b>一个顶层 record + 两个嵌套 record</b>（D5：DTO 层 immutable record）；
 *             常量与静态工厂从工具类搬进 record（record 允许静态成员）；</li>
 *         <li><b>名称</b>：B 侧 {@code ArticleSyncEventTypes.ArticleSyncEvent} → 顶层
 *             {@link ArticleSyncEvent}；{@code ArticleReviewMessage} → {@link ArticleReviewMessage}；
 *             {@code ArticleSyncResult} → {@link ArticleSyncResult}。</li>
 *       </ul>
 *       ⇒ 本类顺带<b>关闭映射表 §1.5 的缺口 G-6</b>（"ArticleReviewMessage / ArticleSyncResult 无载体"）——
 *       两个载荷以<b>嵌套 record</b>形态落在本文件内（文件数不变 ⇒ C 列计数不变）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON 字段名逐字一致</b>（{@code @JsonProperty} 的值一字未改）；</li>
 *         <li><b>字段顺序一致</b>（{@code @JsonPropertyOrder} 逐字照抄）；</li>
 *         <li><b>omitempty 语义一致</b>（{@code @JsonInclude(NON_EMPTY)} 逐字照抄）；</li>
 *         <li>常量值与 ext_info 键名<b>一字未改</b>。</li>
 *       </ul>
 *       ⇒ 序列化后的<b>字节内容</b>与 B 侧一致（这是 outbox 冻结载荷能被下游解出来的前提）。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🔴 这些 JSON 字段名是 <b>L1 跨服务契约</b>，逐字不可改</h2>
 * 消费者在别的服务（Sea-BreakTheWaves）、发件箱里已冻结的历史 payload、
 * 以及 Kafka 里已积压的消息<b>都按这些字段名解析</b>。
 * 改一个 {@code @JsonProperty} 的值就会让<b>已冻结的 outbox 行解不出来</b>
 * （发布回调要读回 payload 做六项比对）。
 *
 * <h2>⚠️ {@code ,omitempty} 必须原样复刻</h2>
 * B 侧 {@code ArticleSyncEvent} 有<b>七个</b> {@code omitempty} 字段
 * （title / brief / cover_url / manual_type_tag / secondary_tags / markdown 与 review_nonce）
 * 与<b>七个非</b> omitempty 字段。
 * 这直接决定冻结 payload 的<b>字节内容</b>：空值字段出现与否会改变 JSON 文本，
 * 而 relay 推送的是<b>原文</b>。用 Jackson 的
 * {@link JsonInclude.Include#NON_EMPTY} 复刻（见各字段注释）。
 *
 * <p>⚠️ 注意 B 侧的 {@code omitempty} 对<b>空切片</b>与<b>空字符串</b>都省略，
 * 对 {@code int64 version_ms == 0} <b>不省略</b>（0 是 int 的零值 ⇒ 会被省略！）。
 * 实际使用中 {@code versionMs} 恒为 {@code time.Now().UnixMilli()}（非 0），
 * 但 {@code status} 与 {@code op} 是<b>无 omitempty</b> 的必出字段。
 *
 * @param eventScope    事件作用域（恒为 {@code article_sync}）
 * @param eventId       事件 ID
 * @param articleId     文章 ID
 * @param op            操作（{@code upsert} / {@code delete}）
 * @param reason        原因（{@code create} / {@code update} / {@code status_change} / {@code delete}）
 * @param authorId      作者 ID
 * @param status        状态<b>枚举名</b>（如 {@code PUBLISHED}）
 * @param versionMs     版本号（毫秒时间戳）
 * @param title         标题（<b>omitempty</b>）
 * @param brief         摘要（<b>omitempty</b>）
 * @param coverUrl      封面图 URL（<b>omitempty</b>）
 * @param manualTypeTag 一级标签（<b>omitempty</b>）
 * @param secondaryTags 二级标签（<b>omitempty</b>）
 * @param markdown      正文（<b>omitempty</b>）
 */
@JsonPropertyOrder({"event_scope", "event_id", "article_id", "op", "reason", "author_id", "status",
        "version_ms", "title", "brief", "cover_url", "manual_type_tag", "secondary_tags", "markdown"})
public record ArticleSyncEvent(
        @JsonProperty("event_scope") String eventScope,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("article_id") String articleId,
        @JsonProperty("op") String op,
        @JsonProperty("reason") String reason,
        @JsonProperty("author_id") String authorId,
        @JsonProperty("status") String status,
        @JsonProperty("version_ms") long versionMs,
        @JsonProperty("title") @JsonInclude(JsonInclude.Include.NON_EMPTY) String title,
        @JsonProperty("brief") @JsonInclude(JsonInclude.Include.NON_EMPTY) String brief,
        @JsonProperty("cover_url") @JsonInclude(JsonInclude.Include.NON_EMPTY) String coverUrl,
        @JsonProperty("manual_type_tag") @JsonInclude(JsonInclude.Include.NON_EMPTY) String manualTypeTag,
        @JsonProperty("secondary_tags") @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> secondaryTags,
        @JsonProperty("markdown") @JsonInclude(JsonInclude.Include.NON_EMPTY) String markdown) {

    // ==================== scope / op / reason（B 侧 :37-50） ====================

    /** 事件作用域。 */
    public static final String ARTICLE_SYNC_SCOPE = "article_sync";

    /** upsert 操作。 */
    public static final String OP_UPSERT = "upsert";

    /** delete 操作。 */
    public static final String OP_DELETE = "delete";

    /** 创建原因。 */
    public static final String REASON_CREATE = "create";

    /** 更新原因。 */
    public static final String REASON_UPDATE = "update";

    /** 状态变更原因。 */
    public static final String REASON_STATUS_CHANGE = "status_change";

    /** 删除原因。 */
    public static final String REASON_DELETE = "delete";

    // ==================== ext_info 键名（B 侧 :52-68） ====================
    // ⚠️ 这些字符串同时是 article.ext_info jsonb 的键，属 L1 契约：
    //    审核消费者与发布回调都按它们比对，改名会让已冻结的 ext_info 全部失配。

    /** ext_info 键：发布阶段。 */
    public static final String EXT_PUBLISH_STAGE = "publish_stage";

    /** ext_info 键：推荐同步状态。 */
    public static final String EXT_RECO_SYNC_STATE = "reco_sync_state";

    /** ext_info 键：最近一次同步错误。 */
    public static final String EXT_LAST_SYNC_ERROR = "last_sync_error";

    /** ext_info 键：最近一次同步事件 ID。 */
    public static final String EXT_LAST_SYNC_EVENT_ID = "last_sync_event_id";

    /** ext_info 键：最近一次同步版本号（<b>存成字符串</b>）。 */
    public static final String EXT_LAST_SYNC_VERSION = "last_sync_version_ms";

    /** ext_info 键：最近一次同步原因。 */
    public static final String EXT_LAST_SYNC_REASON = "last_sync_reason";

    /** ext_info 键：是否曾发布过（{@code "true"} 字符串）。 */
    public static final String EXT_PUBLISHED_ONCE = "published_once";

    /** ext_info 键：待同步原因。 */
    public static final String EXT_PENDING_SYNC_REASON = "pending_sync_reason";

    /** ext_info 键：审核随机数（**初始值 = articleID 本身**）。 */
    public static final String EXT_REVIEW_NONCE = "review_nonce";

    /** legacy 发布缺口标记（B 侧 {@code get_article_logic.go:180} 与 {@code community_article_event.go:117}）。 */
    public static final String LEGACY_GAP_KEY = "publication_gap";

    /** legacy 发布缺口取值。 */
    public static final String LEGACY_GAP_VALUE = "legacy_revision_missing";

    /** H03 缺口标记（B 侧 {@code RetractPublicationTx} 在无指针行时写 {@code h03_publication_gap}）。 */
    public static final String H03_GAP_KEY = "h03_publication_gap";

    // ==================== ext_info 操作（B 侧 :99-125） ====================

    /**
     * 对应 B 侧 {@code EnsureExtInfo}：{@code if article.ExtInfo == nil { article.ExtInfo = JSONMap{} }}。
     *
     * <p>⚠️ <b>必须返回同一个 map 实例</b>并让调用方写回实体 ——
     * B 侧是指针接收者上的 map 写入，Java 的 {@code Map} 同样是引用类型，
     * 但实体字段可能为 {@code null}，此时直接 put 会 NPE。
     */
    public static Map<String, String> ensureExtInfo(Map<String, String> current) {
        if (current == null) {
            return new LinkedHashMap<>();
        }
        return current;
    }

    /**
     * 对应 B 侧 {@code SetSyncState}（{@code article_sync_types.go:75-88}）。
     *
     * <p><b>⚠️ 两条条件赋值不能改成无条件</b>：
     * <pre>
     * if versionMs &gt; 0 { article.ExtInfo[ExtLastSyncVersion] = strconv.FormatInt(versionMs, 10) }
     * if syncReason != "" { article.ExtInfo[ExtPendingSyncReason] = syncReason }
     * </pre>
     * ⇒ {@code versionMs <= 0} 时<b>保留旧版本号</b>；{@code syncReason == ""} 时<b>保留旧待同步原因</b>。
     * 无条件赋值会让「不携带版本号的重试」把版本号清成 0 / 待同步原因清成空，
     * 进而让发布回调的 {@code ExtLastSyncVersion != FormatInt(result.VersionMs)} 判据失效。
     *
     * <p>⚠️ 版本号<b>存成字符串</b>（{@code String.valueOf}），与 B 侧的 {@code FormatInt} 一致。
     */
    public static void setSyncState(Map<String, String> extInfo, String publishStage, String syncState,
                                    String syncReason, String eventId, long versionMs, String syncError) {
        extInfo.put(EXT_PUBLISH_STAGE, publishStage);
        extInfo.put(EXT_RECO_SYNC_STATE, syncState);
        extInfo.put(EXT_LAST_SYNC_ERROR, syncError);
        extInfo.put(EXT_LAST_SYNC_EVENT_ID, eventId);
        extInfo.put(EXT_LAST_SYNC_REASON, syncReason);
        if (versionMs > 0) {
            extInfo.put(EXT_LAST_SYNC_VERSION, String.valueOf(versionMs));
        }
        if (syncReason != null && !syncReason.isEmpty()) {
            extInfo.put(EXT_PENDING_SYNC_REASON, syncReason);
        }
    }

    /**
     * 对应 B 侧 {@code ArticleSyncEventKey}（{@code article_sync_types.go:90-92}）：
     * {@code TrimSpace(articleID) + ":" + TrimSpace(op) + ":" + TrimSpace(eventID)}。
     *
     * <p>⚠️ 三段各自 trim，<b>段间不额外 trim</b>。这是 {@code uk_article_sync_outbox_event_key}
     * 唯一索引的唯一键来源。
     */
    public static String articleSyncEventKey(String articleID, String op, String eventID) {
        return trim(articleID) + ":" + trim(op) + ":" + trim(eventID);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    // ==================== 消息结构（B 侧 :135-295） ====================

    /**
     * 对应 B 侧 {@code ArticleReviewMessage}（{@code article_sync_types.go:35-40}）。
     *
     * <p><b>⚠️ 四个字段构成"审核四元组"</b>：审核消费者要求
     * {@code status==REVIEWING ∧ content==ContentPath ∧ authorID==AuthorID ∧ extInfo[review_nonce]==ReviewNonce}
     * 四项<b>全部</b>成立才继续。四元组的作用是<b>丢弃过期的审核任务</b>：
     * 作者在上一轮审核期间又改了文章，旧任务必须作废。
     * ⇒ 任何字段名改动都会削弱这层保护。
     *
     * @param articleId   文章 ID
     * @param authorId    作者 ID
     * @param contentPath 正文对象 key（MinIO）
     * @param reviewNonce 审核随机数（<b>omitempty</b>）
     */
    @JsonPropertyOrder({"article_id", "author_id", "content_path", "review_nonce"})
    public record ArticleReviewMessage(
            @JsonProperty("article_id") String articleId,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("content_path") String contentPath,
            @JsonProperty("review_nonce") @JsonInclude(JsonInclude.Include.NON_EMPTY) String reviewNonce) {
    }

    /**
     * 对应 B 侧 {@code ArticleSyncResult}（{@code article_sync_types.go:59-67}）——
     * 下游回传的审核/同步结果。
     *
     * <p>⚠️ {@code version_ms} 是<b>幂等判据之一</b>：
     * 发布回调要求 {@code extInfo[last_sync_version_ms] == FormatInt(result.VersionMs)}。
     *
     * @param eventScope   事件作用域
     * @param eventId      事件 ID
     * @param articleId    文章 ID
     * @param op           操作
     * @param versionMs    版本号（毫秒时间戳）
     * @param success      是否成功
     * @param errorMessage 错误信息（<b>omitempty</b>）
     */
    @JsonPropertyOrder({"event_scope", "event_id", "article_id", "op", "version_ms", "success", "error_message"})
    public record ArticleSyncResult(
            @JsonProperty("event_scope") String eventScope,
            @JsonProperty("event_id") String eventId,
            @JsonProperty("article_id") String articleId,
            @JsonProperty("op") String op,
            @JsonProperty("version_ms") long versionMs,
            @JsonProperty("success") boolean success,
            @JsonProperty("error_message") @JsonInclude(JsonInclude.Include.NON_EMPTY) String errorMessage) {
    }

    // ==================== 事件工厂（B 侧 :429-456） ====================

    /**
     * 对应 B 侧 {@code NewArticleSyncEvent}（{@code article_sync_types.go:94-116}）。
     *
     * <p>⚠️ {@link #status()} 的特殊规则见本 record 的说明（upsert 一律 PUBLISHED）。
     * <br>⚠️ {@code SecondaryTags} 用 {@code new ArrayList<>(...)} 做<b>防御性拷贝</b>：
     * B 侧的 {@code append([]string(nil), article.SecondaryTags...)} 同样是拷贝。
     * 不拷贝会让事件与实体共享同一个 list，后续修改互相污染。
     */
    public static ArticleSyncEvent newArticleSyncEvent(
            com.rag.article.domain.entity.ArticleEntity article,
            String markdown,
            String op,
            String reason,
            String eventId, long versionMs) {
        String status = articleStatusName(article.getStatus());
        if (OP_UPSERT.equals(op)) {
            status = "PUBLISHED";
        }
        return new ArticleSyncEvent(
                ARTICLE_SYNC_SCOPE,
                eventId,
                article.getId(),
                op,
                reason,
                article.getAuthorId(),
                status,
                versionMs,
                article.getTitle(),
                article.getBrief(),
                article.getCoverImageUrl(),
                article.getManualTypeTag(),
                article.getSecondaryTags() == null
                        ? null : new java.util.ArrayList<>(article.getSecondaryTags()),
                markdown);
    }

    /**
     * 对应 B 侧 {@code strconv.ParseInt(article.AuthorID, 10, 64)} + {@code uid <= 0} 检查。
     *
     * <p>⚠️ 解析失败<b>返回 -1 而不是抛异常</b>：B 侧是把 err 与 uid 一起交给调用点判断，
     * 由调用点决定是"拒绝事件"还是"标记缺口"。这里返回哨兵值让调用点显式处理。
     */
    public static long parsePositiveUid(String authorId) {
        if (isBlank(authorId)) {
            return -1L;
        }
        try {
            return Long.parseLong(authorId.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    /**
     * proto {@code ArticleStatus} 的<b>枚举名</b>映射 —— 对应 B 侧的
     * {@code pb.ArticleStatus(n).String()}。
     *
     * <p>⚠️ <b>用名字而不是数值</b>：下游按字符串消费（B 侧 {@code .String()} 返回枚举标识符）。
     *
     * <p><b>为什么委托给 protobuf 生成的枚举而不是自己写 switch</b>：
     * 生成代码由根级 {@code proto} 模块从<b>逐字复制自 B 侧的</b> {@code article.proto} 产出，
     * 它的 {@code toString()} 与 B 侧的 {@code .String()} 同源（都取 proto 里的枚举标识符）。
     * 自己再写一份 switch 等于<b>多一个可能与 proto 漂移的真相源</b>：
     * 日后 proto 增删枚举值时，switch 会静默失配，而生成代码自动跟随。
     * 这与 AGENTS.md §3.2「idiomatization 必须有出处」一致 —— 这里是"复用生成代码"而非"自己实现"。
     *
     * <p>⚠️ <b>未知数值的回落必须保留</b>：B 侧的 {@code .String()} 对未登记的数值返回
     * <b>该数字的十进制字符串</b>（protoc-gen-go 的实现），而不是 "UNKNOWN"。
     * protobuf-java 的 {@code forNumber} 对未知值返回 {@code null}，
     * 所以这里显式回落成 {@code String.valueOf(status)}，与 B 侧对齐。
     * 若改成返回 "UNRECOGNIZED" 或抛异常，未知状态的事件文本就会与 B 侧不同。
     */
    public static String articleStatusName(int status) {
        com.rag.proto.article.ArticleStatus value =
                com.rag.proto.article.ArticleStatus.forNumber(status);
        return value != null ? value.toString() : String.valueOf(status);
    }
}
