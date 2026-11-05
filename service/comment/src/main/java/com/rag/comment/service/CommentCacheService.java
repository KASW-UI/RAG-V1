package com.rag.comment.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rag.comment.config.CommentRpcProperties;
import com.rag.comment.domain.entity.CommentContentEntity;
import com.rag.comment.domain.entity.CommentIndexEntity;
import com.rag.comment.domain.entity.SubjectEntity;
import com.rag.comment.exception.CommentExceptions;
import com.rag.comment.mapper.CommentContentMapper;
import com.rag.comment.mapper.CommentIndexMapper;
import com.rag.comment.mapper.SubjectMapper;
import com.rag.comment.metrics.CommentRpcMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 评论缓存读穿与失效 —— 对应 Go 端 {@code service/comment/rpc/internal/cache/} 的<b>六个</b>文件、
 * 12 个方法。Go 侧它们本就是<b>同一个 struct {@code CommentCache} 的方法集</b>，
 * 所以 Java 侧合成一个类（这不是「为了对应 Go 文件」，而是它们共享同一个 redis.Client 与同一个 singleflight.Group）。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentCacheService.java:1-855}
 * （L2 业务语义层以 B 侧为基准整段抄 · D13/D16；
 * Jackson 写法在 B 侧本就是 Jackson 2 ⇒ 零回改 · D14）。
 *
 * <h2>⚠️ 四类读穿的<b>错误语义各不相同</b>，必须逐条区分</h2>
 * <table border="1">
 *   <caption>Redis 异常时的行为差异</caption>
 *   <tr><th>方法</th><th>Redis 异常时</th><th>Go 出处</th></tr>
 *   <tr><td>{@link #getSubjectWithCache}</td><td>打点后<b>继续回源 DB</b></td>
 *       <td>{@code comment_subject_cache.go:31-35}</td></tr>
 *   <tr><td>{@link #getReplyIdsPageCache}</td><td>打点后<b>继续回源 DB</b></td>
 *       <td>{@code comment_ids_page_cache.go:39-44}</td></tr>
 *   <tr><td>{@link #getCommentIndexCache}</td><td>打点后<b>直接返回错误</b>，不回源</td>
 *       <td>{@code comment_index_cache.go:41-47}</td></tr>
 *   <tr><td>{@link #batchGetContentCache}</td><td>打点后<b>直接返回错误</b>，不回源</td>
 *       <td>{@code comment_content_cache.go:41-48}</td></tr>
 * </table>
 * 看着像可以统一，实际上<b>不统一</b>：前两者是「单 key」，后两者是「批量 MGET」。
 * 统一成任一种都会改变一条 RPC 的失败模式（前者降级成功、后者整体失败）。
 * ⚠️ 映射表 {@code docs/Mapping_Table/comment/comment.md:192} 记「C 的失败模式（fail-open / fail-fast）待定」
 * ⇒ <b>本次实现按 D16 逐条继承 B 侧现状，不做统一</b>。
 *
 * <h2>⚠️ 缓存 JSON 的字段名是 Go 的<b>结构体字段名</b>，不是 snake_case</h2>
 * <p>Go 的 {@code model.Subject} / {@code CommentIndex} / {@code CommentContent}
 * <b>都没有 json tag</b> ⇒ {@code encoding/json} 按导出字段名输出
 * （{@code "Id"}, {@code "TargetType"}, {@code "TotalCount"} …）。
 * Java 侧若用 snake_case 或 camelCase，灰度期两个进程会互相读不到对方的缓存，
 * 而「读不到」表现为静默回源（放大 DB 压力但不出错）—— 这类漂移最难发现。
 * ⇒ 本类的三个缓存 DTO 用 {@link JsonProperty} 显式钉成 Go 的字段名。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.comment.comment.rpc.service} 压平为 {@code com.rag.comment.service}（D7）；</li>
 *       <li>实体 / Mapper / 指标 / 配置 / 异常的 import 由
 *           {@code com.rag.comment.comment.rpc.{domain,mapper,metrics,config,exception}}
 *           改为 {@code com.rag.comment.{domain.entity,mapper,metrics,config,exception}}（D7）；</li>
 *       <li>🔴 <b>不用 {@code @Slf4j}</b>（D18 禁止项）：B 侧的 {@code GoStyleLogger} 带
 *           {@code call_chain} 字段 ⇒ 保持<b>显式</b> {@code LoggerFactory.getLogger(...)}。</li>
 *       <li>本类<b>不用 Lombok</b>：构造器需要自定义初始化（缓存专用 Jackson + singleflight 表），
 *           {@code @RequiredArgsConstructor} 表达不了 ⇒ 保持显式构造器，
 *           D18 的「Lombok 展开注释」因此无适用对象。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。12 个 public 方法的<b>签名、判定顺序、指标 label、
 *       TTL、回填失败的处理、单飞（singleflight）语义</b>逐条未改；
 *       4 个 TTL 常量（5 分钟 / 3 分钟 / 10 分钟 / 24 小时）与删除批大小 100 逐字未改。</li>
 *   <li><b>B 出处</b>：见上（逐条行号见各方法的 javadoc）。</li>
 * </ol>
 */
@Service
public class CommentCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(CommentCacheService.class);

    // ===== TTL（逐条对应 Go 的 const）=====

    /** 对应 Go {@code comment_subject_cache.go:14} {@code defaultSubjectTTL = 5 * time.Minute}。 */
    private static final Duration SUBJECT_TTL = Duration.ofMinutes(5);

    /** 对应 Go {@code comment_ids_page_cache.go:14} {@code defaultReplyIDsPageTTL = 3 * time.Minute}。 */
    private static final Duration REPLY_IDS_PAGE_TTL = Duration.ofMinutes(3);

    /** 对应 Go {@code comment_index_cache.go:12} {@code defaultCommentIndexTTL = 10 * time.Minute}。 */
    private static final Duration COMMENT_INDEX_TTL = Duration.ofMinutes(10);

    /** 对应 Go {@code comment_content_cache.go:12} {@code defaultReplyContentTTL = 24 * time.Hour}。 */
    private static final Duration REPLY_CONTENT_TTL = Duration.ofHours(24);

    /** 对应 Go {@code comment_invalidation_cache.go:73} 的删除批大小 100。 */
    private static final int DELETE_BATCH = 100;

    private final StringRedisTemplate redis;
    private final SubjectMapper subjectMapper;
    private final CommentIndexMapper commentIndexMapper;
    private final CommentContentMapper commentContentMapper;
    private final CommentRpcMetrics metrics;

    /**
     * 缓存专用的 Jackson。
     *
     * <p>与注入的 {@code ObjectMapper} <b>刻意分开</b>：那个是按 Go 的
     * 「忽略未知字段 / 禁止标量强转」配的（对齐 Kafka 消息解析），
     * 这里需要的是「字段名对齐 Go 缓存 + 时间按 RFC3339」。
     * 两套配置的诉求不同，混用会让其中一边漂移。
     */
    private final ObjectMapper cacheMapper;

    /** 对应 Go {@code cache.CommentCache.sf singleflight.Group}。 */
    private final ConcurrentHashMap<String, CompletableFuture<Object>> singleflight = new ConcurrentHashMap<>();

    /**
     * 构造器 —— 与 B 侧<b>同参序</b>（含那个未被使用的 {@code props}）。
     *
     * <p>⚠️ <b>{@code props} 在 B 侧构造器里也是「收下但不用」</b>
     * （B 侧 {@code …/rpc/service/CommentCacheService.java:107-122} 全文可见 {@code props}
     * 只出现在签名里，构造体只用它占位）⇒ 这里<b>照抄该签名</b>，
     * 不擅自删参（删了会改掉 B 侧的构造契约，也让按 B 侧签名写的测试编不过）。
     *
     * <p>⚠️ 缓存专用的 Jackson 是<b>本类自建</b>的（不注入容器里的 ObjectMapper）——
     * 理由见 {@link #cacheMapper} 的说明。
     */
    public CommentCacheService(StringRedisTemplate redis,
                               SubjectMapper subjectMapper,
                               CommentIndexMapper commentIndexMapper,
                               CommentContentMapper commentContentMapper,
                               CommentRpcProperties props,
                               CommentRpcMetrics metrics) {
        this.redis = redis;
        this.subjectMapper = subjectMapper;
        this.commentIndexMapper = commentIndexMapper;
        this.commentContentMapper = commentContentMapper;
        this.metrics = metrics;
        this.cacheMapper = JsonMapper.builder()
                // Go 的 encoding/json 忽略未知字段 ⇒ 老版本 Java 写入的缓存能被新版本读到
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    // ====================================================================================
    // 1. subject 读穿 —— 对应 Go comment_subject_cache.go:16-132
    // ====================================================================================

    /**
     * 对应 Go {@code GetSubjectWithCache}：读缓存 → singleflight 内双检 → 回源 DB → 回填（5 分钟）。
     *
     * <p><b>「回源 DB 失败也打 redis 指标的 type}db_fallback"」是 Go 的现状</b>
     * （comment_subject_cache.go:52-55）—— 看着该进 postgres 指标，实际进了 redis 指标。
     * 修正会让「redis 错误率」看板的历史曲线断裂 ⇒ 保留。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:128-180}。
     *
     * @throws CommentExceptions.SubjectNotFound 评论区不存在（由调用点决定发 1012 还是回落）
     */
    public SubjectEntity getSubjectWithCache(String subjectType, String subjectId) {
        if (subjectId == null || subjectId.isEmpty()) {
            throw new IllegalArgumentException("invalid subjectID: empty");
        }

        // --- 1. 尝试从缓存取（Redis 异常 ⇒ 打点后继续，不阻断）
        //     Go :24-30：命中就返回；err != nil ⇒ 打 ("comment_redis","GetSubjectWithCache","query")
        SubjectCacheRead first = readSubjectCacheDetailed(subjectId);
        if (first.value() != null) {
            return first.value();
        }
        if (first.error()) {
            metrics.observeRedisError("GetSubjectWithCache", "query");
        }

        // --- 2. singleflight：同一 key 的并发 miss 只回源一次
        return singleflight("subject:" + subjectId, () -> {
            // 双检 Redis（Go :37-43，判定与上面**逐条相同**）
            SubjectCacheRead again = readSubjectCacheDetailed(subjectId);
            if (again.value() != null) {
                return again.value();
            }
            if (again.error()) {
                metrics.observeRedisError("GetSubjectWithCache", "query");
            }
            // --- 3. 回源 DB
            SubjectEntity subject = subjectMapper.findOneByTarget(subjectType, subjectId);
            if (subject == null) {
                metrics.observeRedisError("GetSubjectWithCache", "db_fallback");
                throw new CommentExceptions.SubjectNotFound();
            }
            // --- 4. 回填 Redis
            // ⚠️ Go :59 是 `_ = c.SetSubjectCache(...)` ⇒ **回填失败被显式忽略**。
            // 若照搬成「抛异常」⇒ Redis 挂掉时整个读路径失败（500），
            // 而 Go 侧**照常返回 DB 回源的结果** ⇒ 这是可观察差异。
            // ⇒ 这里照搬 Go：打点（writeSubjectCache 内部已打）后忽略异常。
            try {
                writeSubjectCache(subjectId, subject, SUBJECT_TTL);
            } catch (RuntimeException cacheErr) {
                LOG.warn("set subject cache failed for target {}: {}", subjectId, cacheErr.getMessage());
            }
            return subject;
        });
    }

    /**
     * 对应 Go {@code GetSubjectCache} 的<b>三态</b>返回：{@code (*Subject, error)}。
     *
     * <p>⚠️ <b>为什么必须是三态而不是「entity 或 null」</b>：
     * Go 的 {@code GetSubjectCache} 有<b>三种</b>结局，而 {@code null} 会把其中两种合并掉 ——
     * 而这两种结局的<b>打点不同</b>：
     * <table border="1">
     *   <caption>Go comment_subject_cache.go 的三种结局</caption>
     *   <tr><th>结局</th><th>Go 出处</th><th>{@code GetSubjectCache} 打点</th>
     *       <th>{@code GetSubjectWithCache} 打点</th></tr>
     *   <tr><td>命中</td><td>:86-92</td><td>无</td><td>无</td></tr>
     *   <tr><td>未命中（{@code redis.Nil}）</td><td>:83-85</td><td>无</td><td>无</td></tr>
     *   <tr><td><b>Redis 真异常</b></td><td>:78-82</td>
     *       <td><b>有</b>（{@code GetSubjectCache} / {@code query}）</td>
     *       <td><b>有</b>（{@code GetSubjectWithCache} / {@code query}）</td></tr>
     *   <tr><td><b>JSON 解析失败</b></td><td>:94-98</td>
     *       <td><b>无</b>（Go 注释明说「视作缓存未命中，不记 metric」）</td>
     *       <td><b>有</b>（{@code GetSubjectWithCache} / {@code query}）</td></tr>
     * </table>
     * ⚠️ <b>每个 label 各打 2 次</b>：Go 的读路径<b>读两次 Redis</b> ——
     * 外层一次（{@code :24-30}）+ singleflight 里的<b>双检</b>一次（{@code :37-43}）——
     * 两处的打点判定<b>逐条相同</b>。
     * ⇒ 修复前 RAG 把「Redis 真异常」与「JSON 解析失败」<b>合并</b>成一个 catch
     * ⇒ 两者都只打一个 {@code GetSubjectCache} ⇒ <b>label 集合与 Go 不同</b>
     * （Go 的 JSON 解析失败打在 {@code GetSubjectWithCache} 上，RAG 打在 {@code GetSubjectCache} 上；
     *  且 Redis 真异常时 Go 打两个 label，RAG 只打一个）。
     * ⇒ 见 docs/DRIFT/comment/rpc.md 的 D6。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:182-231}。
     *
     * @param value   命中的缓存值；未命中 / 出错时为 null
     * @param error   是否「出错」（区别于「未命中」）—— Go 的 {@code err != nil}
     * @param redisFailure 是否是 <b>Redis 传输层</b>异常（区别于 JSON 解析失败）
     */
    private record SubjectCacheRead(SubjectEntity value, boolean error, boolean redisFailure) {

        static SubjectCacheRead hit(SubjectEntity value) {
            return new SubjectCacheRead(value, false, false);
        }

        static SubjectCacheRead miss() {
            return new SubjectCacheRead(null, false, false);
        }

        static SubjectCacheRead redisError() {
            return new SubjectCacheRead(null, true, true);
        }

        static SubjectCacheRead parseError() {
            return new SubjectCacheRead(null, true, false);
        }
    }

    /**
     * 对应 Go {@code GetSubjectCache}：读缓存 + 反序列化。<b>null 返回 = 未命中</b>。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:233-236}。
     *
     * @param subjectId 主体 ID
     * @return 命中的缓存值；未命中 / 出错时为 {@code null}
     */
    public SubjectEntity readSubjectCache(String subjectId) {
        return readSubjectCacheDetailed(subjectId).value();
    }

    /**
     * 对应 Go {@code GetSubjectCache}（comment_subject_cache.go:75-101）——<b>含打点</b>。
     *
     * <p>⚠️ 打点必须发生在这里（而不是在调用方），因为 Go 的
     * {@code GetSubjectCache} <b>自己</b>就打了 {@code GetSubjectCache} 那个 label。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:238-267}。
     */
    private SubjectCacheRead readSubjectCacheDetailed(String subjectId) {
        if (subjectId == null || subjectId.isEmpty()) {
            throw new IllegalArgumentException("invalid subjectID: empty");
        }
        String raw;
        try {
            raw = redis.opsForValue().get(CommentRedisKeys.subjectKey(subjectId));
        } catch (Exception e) {
            // Go :78-82：Redis **真异常** ⇒ 打 ("comment_redis","GetSubjectCache","query") 并返回 err
            metrics.observeRedisError("GetSubjectCache", "query");
            return SubjectCacheRead.redisError();
        }
        if (raw == null) {
            // Go :83-85：redis.Nil ⇒ 未命中，**不打点**
            return SubjectCacheRead.miss();
        }
        try {
            return SubjectCacheRead.hit(cacheMapper.readValue(raw, SubjectCacheDto.class).toEntity());
        } catch (Exception e) {
            // Go :94-98：JSON 解析失败视作缓存未命中，**不记 metric**（但返回 err
            // ⇒ 调用方仍会打 "GetSubjectWithCache"）
            return SubjectCacheRead.parseError();
        }
    }

    /**
     * 对应 Go {@code SetSubjectCache}。TTL ≤ 0 时用默认 5 分钟（comment_subject_cache.go:113-115）。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:269-286}。
     *
     * @param subjectId 主体 ID
     * @param subject   评论区实体
     * @param ttl       存活时间；{@code null} / ≤ 0 时用 5 分钟默认值
     */
    public void writeSubjectCache(String subjectId, SubjectEntity subject, Duration ttl) {
        if (subjectId == null || subjectId.isEmpty()) {
            throw new IllegalArgumentException("invalid subjectID: empty");
        }
        if (subject == null) {
            throw new IllegalArgumentException("subject is nil");
        }
        Duration effective = (ttl == null || ttl.isZero() || ttl.isNegative()) ? SUBJECT_TTL : ttl;
        try {
            redis.opsForValue().set(CommentRedisKeys.subjectKey(subjectId),
                    cacheMapper.writeValueAsString(SubjectCacheDto.from(subject)), effective);
        } catch (Exception e) {
            metrics.observeRedisError("SetSubjectCache", "set");
            throw new IllegalStateException("redis set subject cache failed, key="
                    + CommentRedisKeys.subjectKey(subjectId), e);
        }
    }

    // ====================================================================================
    // 2. 分页 id 列表读穿 —— 对应 Go comment_ids_page_cache.go:16-89
    // ====================================================================================

    /**
     * 对应 Go {@code GetReplyIDsPageCache}：读缓存 → singleflight 内双检 → 回源 DB → 回填（3 分钟）。
     *
     * <p>⚠️ Go 侧 {@code preloadNextReplyIDsPage}（预热下一页）<b>零调用点</b>
     * （只有它自己的定义与注释，全仓无调用）⇒ <b>不迁移</b>，
     * 不为死代码预留结构。
     *
     * <p>⚠️ <b>Redis 真异常 ⇒ 打点后继续回源 DB</b>（与
     * {@link #getCommentIndexCache} 的「直接失败」相反）—— 这是 Go 的现状，逐条照抄。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:292-337}。
     *
     * @param hot true = 热度序；false = 时间序（见 {@code CommentIndexMapper#selectReplyIdsByPage}）
     */
    public List<Long> getReplyIdsPageCache(String targetType, String targetId, long rootId,
                                           String sort, int offset, int limit, boolean onlyNormal) {
        String pageKey = CommentRedisKeys.replyIndexPageKey(targetType, targetId, rootId, sort, offset, limit);

        // --- 1. Redis 命中直接返回；Redis 真异常 ⇒ 打点后继续回源
        List<Long> cached = readIdList(pageKey);
        if (cached != null) {
            return cached;
        }

        return singleflight("reply_ids_page:" + pageKey, () -> {
            // 双检
            List<Long> again = readIdList(pageKey);
            if (again != null) {
                return again;
            }
            // --- 3. 回源 DB
            List<Long> ids;
            try {
                ids = commentIndexMapper.selectReplyIdsByPage(
                        targetType, targetId, rootId, onlyNormal, "hot".equals(sort), offset, limit);
            } catch (Exception e) {
                metrics.observeRedisError("GetReplyIDsPageCache", "db_fallback");
                throw new IllegalStateException("db selectReplyIdsByPage failed: " + e.getMessage(), e);
            }
            if (ids == null) {
                ids = List.of();
            }
            // --- 4. 回填
            try {
                redis.opsForValue().set(pageKey, cacheMapper.writeValueAsString(ids), REPLY_IDS_PAGE_TTL);
            } catch (Exception e) {
                // Go 侧回填是 `_ = c.rdb.Set(...).Err()`，失败静默（comment_ids_page_cache.go:75）
            }
            return ids;
        });
    }

    /**
     * 读分页 id 列表 —— 对应 Go {@code comment_ids_page_cache.go:39-44} 的三态读。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:339-351}。
     *
     * @param pageKey 分页缓存 key
     * @return 命中的列表；未命中 / Redis 异常时为 {@code null}（⇒ 继续回源）
     */
    private List<Long> readIdList(String pageKey) {
        try {
            String raw = redis.opsForValue().get(pageKey);
            if (raw == null) {
                return null;
            }
            return cacheMapper.readValue(raw,
                    cacheMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        } catch (Exception e) {
            metrics.observeRedisError("GetReplyIDsPageCache", "query");
            return null;
        }
    }

    // ====================================================================================
    // 3. 单条评论索引批量读 —— 对应 Go comment_index_cache.go:14-119
    // ====================================================================================

    /**
     * 对应 Go {@code GetCommentIndexCache}：MGET → miss 回源 DB → pipeline 回填（10 分钟）→ 按入参顺序重排。
     *
     * <p><b>⚠️ MGET 失败时返回错误，不回源</b>（comment_index_cache.go:41-47）。
     * 这与 {@link #getSubjectWithCache} 的降级策略<b>相反</b>，是 Go 的现状。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:357-456}。
     *
     * @param ids 评论 ID 列表
     * @return 按入参顺序重排后的索引行（入参里查不到的<b>不出现</b>在结果中）
     */
    public List<CommentIndexEntity> getCommentIndexCache(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> validIds = ids.stream().filter(i -> i != null && i > 0).toList();
        if (validIds.isEmpty()) {
            return List.of();
        }

        // --- 1. MGET
        List<String> keys = validIds.stream().map(CommentRedisKeys::commentIndexKey).toList();
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
        } catch (Exception e) {
            metrics.observeRedisError("GetCommentIndexCache", "batch_get");
            throw new IllegalStateException("redis MGet comment index cache failed: " + e.getMessage(), e);
        }

        Map<Long, CommentIndexEntity> indexMap = new LinkedHashMap<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < validIds.size(); i++) {
            long id = validIds.get(i);
            String raw = (values != null && i < values.size()) ? values.get(i) : null;
            if (raw == null) {
                missIds.add(id);
                continue;
            }
            try {
                indexMap.put(id, cacheMapper.readValue(raw, CommentIndexCacheDto.class).toEntity());
            } catch (Exception parseFail) {
                // Go 侧反序列化失败按 miss 处理（comment_index_cache.go:72-75）
                missIds.add(id);
            }
        }

        // --- 2. miss 回源 DB
        if (!missIds.isEmpty()) {
            List<CommentIndexEntity> dbIndexes;
            try {
                dbIndexes = commentIndexMapper.selectByIds(missIds);
            } catch (Exception e) {
                metrics.observeRedisError("GetCommentIndexCache", "db_fallback");
                throw new IllegalStateException("db fallback selectByIds failed: " + e.getMessage(), e);
            }
            for (CommentIndexEntity e : dbIndexes) {
                indexMap.put(e.getId(), e);
            }

            // --- 3. 回填（一次 pipeline 发全部 SET，失败不阻断 —— Go 侧 `_ , _ = pipe.Exec(ctx)`）
            if (!dbIndexes.isEmpty()) {
                // 先在回调外完成序列化（writeValueAsString 抛受检异常，
                // 而 SessionCallback.execute 不允许抛）—— 语义上与 Go 的
                // `b, err := json.Marshal(idx); if err != nil { continue }` 一致：
                // 单条序列化失败就跳过该条，不影响其余条目。
                List<String[]> writes = new ArrayList<>(dbIndexes.size());
                for (CommentIndexEntity e : dbIndexes) {
                    try {
                        writes.add(new String[]{
                                CommentRedisKeys.commentIndexKey(e.getId()),
                                cacheMapper.writeValueAsString(CommentIndexCacheDto.from(e))});
                    } catch (Exception marshalFail) {
                        continue;   // Go comment_index_cache.go:98-100 同款：跳过该条
                    }
                }
                try {
                    redis.execute(new SessionCallback<Object>() {
                        @Override
                        public Object execute(RedisOperations operations) {
                            for (String[] kv : writes) {
                                operations.opsForValue().set(kv[0], kv[1], COMMENT_INDEX_TTL);
                            }
                            return null;
                        }
                    });
                } catch (Exception e) {
                    LOG.warn("pipeline fill comment index cache failed (ignored): {}", e.getMessage());
                }
            }
        }

        // --- 4. 按输入 ids 顺序输出（Go comment_index_cache.go:108-116）
        List<CommentIndexEntity> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (id == null || id <= 0) {
                continue;
            }
            CommentIndexEntity found = indexMap.get(id);
            if (found != null) {
                result.add(found);
            }
        }
        return result;
    }

    // ====================================================================================
    // 4. 正文批量读 —— 对应 Go comment_content_cache.go:14-122
    // ====================================================================================

    /**
     * 对应 Go {@code BatchGetContentCache}：与 {@link #getCommentIndexCache} 同构，TTL 24 小时。
     *
     * <p><b>⚠️ MGET 失败同样直接返回错误，不回源</b>（comment_content_cache.go:41-48）。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:462-550}。
     *
     * @param ids 评论 ID 列表
     * @return 按入参顺序重排后的正文行
     */
    public List<CommentContentEntity> batchGetContentCache(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> validIds = ids.stream().filter(i -> i != null && i > 0).toList();
        if (validIds.isEmpty()) {
            return List.of();
        }

        List<String> keys = validIds.stream().map(CommentRedisKeys::replyContentKey).toList();
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
        } catch (Exception e) {
            metrics.observeRedisError("BatchGetContentCache", "batch_get");
            throw new IllegalStateException("redis MGet content cache failed: " + e.getMessage(), e);
        }

        Map<Long, CommentContentEntity> contentMap = new LinkedHashMap<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < validIds.size(); i++) {
            long id = validIds.get(i);
            String raw = (values != null && i < values.size()) ? values.get(i) : null;
            if (raw == null) {
                missIds.add(id);
                continue;
            }
            try {
                contentMap.put(id, cacheMapper.readValue(raw, CommentContentCacheDto.class).toEntity());
            } catch (Exception parseFail) {
                missIds.add(id);
            }
        }

        if (!missIds.isEmpty()) {
            List<CommentContentEntity> dbContents;
            try {
                dbContents = commentContentMapper.selectByIds(missIds);
            } catch (Exception e) {
                metrics.observeRedisError("BatchGetContentCache", "db_fallback");
                throw new IllegalStateException("db fallback selectByIds failed: " + e.getMessage(), e);
            }
            for (CommentContentEntity c : dbContents) {
                contentMap.put(c.getCommentId(), c);
            }
            if (!dbContents.isEmpty()) {
                List<String[]> writes = new ArrayList<>(dbContents.size());
                for (CommentContentEntity c : dbContents) {
                    try {
                        writes.add(new String[]{
                                CommentRedisKeys.replyContentKey(c.getCommentId()),
                                cacheMapper.writeValueAsString(CommentContentCacheDto.from(c))});
                    } catch (Exception marshalFail) {
                        continue;   // Go comment_content_cache.go:101-103 同款
                    }
                }
                try {
                    redis.execute(new SessionCallback<Object>() {
                        @Override
                        public Object execute(RedisOperations operations) {
                            for (String[] kv : writes) {
                                operations.opsForValue().set(kv[0], kv[1], REPLY_CONTENT_TTL);
                            }
                            return null;
                        }
                    });
                } catch (Exception e) {
                    LOG.warn("pipeline fill comment content cache failed (ignored): {}", e.getMessage());
                }
            }
        }

        List<CommentContentEntity> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (id == null || id <= 0) {
                continue;
            }
            CommentContentEntity found = contentMap.get(id);
            if (found != null) {
                result.add(found);
            }
        }
        return result;
    }

    // ====================================================================================
    // 5-6. 失效 —— 对应 Go comment_invalidation_cache.go:10-83
    // ====================================================================================

    /**
     * 对应 Go {@code InvalidateTargetCaches}：删 {@code comment:subject:<id>}
     * + SCAN 出来的所有分页 key。
     *
     * <p>⚠️ <b>不删 {@code comment:content:*}，也不删 {@code comment:index:item:*}</b>。
     * Go 的理由是「正文不可变，24h TTL 自然过期」（FileTree/comment/rpc.md 的缓存表）。
     * 因此「删除评论后仍可能从内容缓存读到它的正文」是 Go 的既有行为。
     *
     * <p>⚠️ {@code targetType} 或 {@code targetId} 为空 ⇒ 直接成功返回（Go comment_invalidation_cache.go:14-16）。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:556-583}。
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     */
    public void invalidateTargetCaches(String targetType, String targetId) {
        if (targetType == null || targetType.isEmpty() || targetId == null || targetId.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>();
        keys.add(CommentRedisKeys.subjectKey(targetId));

        String pattern = CommentRedisKeys.targetPageScanPattern(targetType, targetId);
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            throw new IllegalStateException("scan comment page cache failed: " + e.getMessage(), e);
        }
        deleteKeys(keys);
    }

    /**
     * 对应 Go {@code DeleteCommentIndexCache(ctx, commentIDs...)}：删单条评论的索引缓存 key。
     *
     * <p><b>入参是变长的</b>：删除路径传 <b>两个</b>（{@code delete_comment_logic.go:125} 传
     * {@code commentId} 与 {@code comment.ParentId}），点赞路径传一个。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:585-602}。
     *
     * @param commentIds 评论 ID（{@code <= 0} 的会被跳过）
     */
    public void deleteCommentIndexCache(long... commentIds) {
        if (commentIds == null || commentIds.length == 0) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (long id : commentIds) {
            if (id > 0) {
                keys.add(CommentRedisKeys.commentIndexKey(id));
            }
        }
        deleteKeys(keys);
    }

    /**
     * 对应 Go {@code deleteKeys}：去重 + 每 100 个一批 DEL。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:604-621}。
     *
     * @param keys 待删 key 列表
     */
    private void deleteKeys(List<String> keys) {
        List<String> filtered = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String key : keys) {
            if (key == null || key.isEmpty() || !seen.add(key)) {
                continue;
            }
            filtered.add(key);
        }
        if (filtered.isEmpty()) {
            return;
        }
        for (int start = 0; start < filtered.size(); start += DELETE_BATCH) {
            int end = Math.min(start + DELETE_BATCH, filtered.size());
            redis.delete(filtered.subList(start, end));
        }
    }

    // ====================================================================================
    // singleflight —— 对应 Go cache/*.go 里的 singleflight.Group
    // ====================================================================================

    /**
     * 对应 {@code golang.org/x/sync/singleflight.Group.Do(key, fn)} 的语义：
     * <b>同一个 key 上正在执行的 fn 只跑一次，其余调用方等待并共享同一个结果</b>；
     * 结束后条目立刻移除，下一批并发重新竞争。
     *
     * <p><b>为什么必须实现而不是去掉</b>：它承担的是<b>缓存穿透保护</b> ——
     * 一批同时到达的 GetComment 在缓存未命中时只会打 1 次 DB，而不是 N 次。
     * 换成「直接去掉」会让 DB 回源量随并发线性增长；
     * 换成「每个线程自己加锁」会把并发度压到 1，语义与 singleflight（<b>共享结果</b>而非串行）不同。
     *
     * <p>Spring / JDK <b>没有</b>等价物（Guava 有 {@code LoadingCache} 但不是同一件事，
     * 且本仓未引 Guava）⇒ 这 20 行是「具体缺口 + 自定义实现如何补齐」的最小闭环。
     *
     * <p>异常处理：fn 抛出的异常会被<b>所有</b>等待方看到
     * （Go 的 {@code Do} 把 {@code err} 一并返回给所有调用方），语义一致。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:627-669}。
     *
     * @param key 单飞分组键
     * @param fn  回源逻辑
     * @return fn 的返回值（同组调用方共享同一结果）
     */
    @SuppressWarnings("unchecked")
    private <T> T singleflight(String key, Supplier<T> fn) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        CompletableFuture<Object> existing = singleflight.putIfAbsent(key, future);
        if (existing != null) {
            // 已有同 key 在飞：等待它的结果（Go 的 Do 第二个返回值 shared=true）
            try {
                return (T) existing.join();
            } catch (java.util.concurrent.CompletionException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw e;
            }
        }
        try {
            T result = fn.get();
            future.complete(result);
            return result;
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            // Go 的 Do 会在 fn 返回后删除条目；这里同理，否则 key 泄漏且永远返回旧结果
            singleflight.remove(key, future);
        }
    }

    // ====================================================================================
    // 缓存 DTO —— 字段名逐字对齐 Go 的**无 tag** 结构体字段名
    // ====================================================================================

    /**
     * 对应 Go {@code model.Subject}（无 json tag ⇒ 输出 "Id"/"TargetType"/…）。
     *
     * <p>⚠️ <b>它不是 DTO 层的 record，而是「缓存载荷」</b>：A9 / D5 的「DTO 必须 immutable」
     * 约束的是 {@code dto/} 包下的请求/响应模型；本类是<b>类内私有</b>的 Jackson 载荷，
     * 其存在的唯一目的是<b>逐字复刻 Go 的缓存字节</b>（字段名 + 可读写 + 无参构造）
     * ⇒ 与 B 侧同款，<b>不用 Lombok</b>（D18 的 Lombok 展开注释在此无适用对象）。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:675-733}。
     */
    @JsonPropertyOrder({"Id", "TargetType", "TargetId", "TotalCount", "RootCount",
            "State", "Attribute", "OwnerId", "CreatedAt", "UpdatedAt"})
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class SubjectCacheDto {

        @JsonProperty("Id")
        Long id;
        @JsonProperty("TargetType")
        String targetType;
        @JsonProperty("TargetId")
        String targetId;
        @JsonProperty("TotalCount")
        Long totalCount;
        @JsonProperty("RootCount")
        Long rootCount;
        @JsonProperty("State")
        Integer state;
        @JsonProperty("Attribute")
        Long attribute;
        @JsonProperty("OwnerId")
        Long ownerId;
        // Go 侧 time.Time ⇒ JSON 字符串（RFC3339Nano）。用 String 承载以与 Go 的
        // 缓存字节一致，也避免依赖 JavaTimeModule。
        @JsonProperty("CreatedAt")
        String createdAt;
        @JsonProperty("UpdatedAt")
        String updatedAt;

        static SubjectCacheDto from(SubjectEntity e) {
            SubjectCacheDto dto = new SubjectCacheDto();
            dto.id = e.getId();
            dto.targetType = e.getTargetType();
            dto.targetId = e.getTargetId();
            dto.totalCount = e.getTotalCount();
            dto.rootCount = e.getRootCount();
            dto.state = e.getState();
            dto.attribute = e.getAttribute();
            dto.ownerId = e.getOwnerId();
            dto.createdAt = toWireTime(e.getCreatedAt());
            dto.updatedAt = toWireTime(e.getUpdatedAt());
            return dto;
        }

        SubjectEntity toEntity() {
            SubjectEntity e = new SubjectEntity();
            e.setId(id);
            e.setTargetType(targetType);
            e.setTargetId(targetId);
            e.setTotalCount(totalCount == null ? 0L : totalCount);
            e.setRootCount(rootCount == null ? 0L : rootCount);
            e.setState(state == null ? 0 : state);
            e.setAttribute(attribute == null ? 0L : attribute);
            e.setOwnerId(ownerId == null ? 0L : ownerId);
            e.setCreatedAt(fromWireTime(createdAt));
            e.setUpdatedAt(fromWireTime(updatedAt));
            return e;
        }
    }

    /**
     * 对应 Go {@code model.CommentIndex}（无 json tag）。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:735-804}。
     */
    @JsonPropertyOrder({"Id", "TargetType", "TargetId", "UserId", "RootId", "ParentId",
            "LikeCount", "DislikeCount", "ReplyCount", "State", "Attribute",
            "CreatedAt", "UpdatedAt"})
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class CommentIndexCacheDto {

        @JsonProperty("Id")
        Long id;
        @JsonProperty("TargetType")
        String targetType;
        @JsonProperty("TargetId")
        String targetId;
        @JsonProperty("UserId")
        Long userId;
        @JsonProperty("RootId")
        Long rootId;
        @JsonProperty("ParentId")
        Long parentId;
        @JsonProperty("LikeCount")
        Long likeCount;
        @JsonProperty("DislikeCount")
        Long dislikeCount;
        @JsonProperty("ReplyCount")
        Long replyCount;
        @JsonProperty("State")
        Integer state;
        @JsonProperty("Attribute")
        Long attribute;
        @JsonProperty("CreatedAt")
        String createdAt;
        @JsonProperty("UpdatedAt")
        String updatedAt;

        static CommentIndexCacheDto from(CommentIndexEntity e) {
            CommentIndexCacheDto dto = new CommentIndexCacheDto();
            dto.id = e.getId();
            dto.targetType = e.getTargetType();
            dto.targetId = e.getTargetId();
            dto.userId = e.getUserId();
            dto.rootId = e.getRootId();
            dto.parentId = e.getParentId();
            dto.likeCount = e.getLikeCount();
            dto.dislikeCount = e.getDislikeCount();
            dto.replyCount = e.getReplyCount();
            dto.state = e.getState();
            dto.attribute = e.getAttribute();
            dto.createdAt = toWireTime(e.getCreatedAt());
            dto.updatedAt = toWireTime(e.getUpdatedAt());
            return dto;
        }

        CommentIndexEntity toEntity() {
            CommentIndexEntity e = new CommentIndexEntity();
            e.setId(id);
            e.setTargetType(targetType);
            e.setTargetId(targetId);
            e.setUserId(nz(userId));
            e.setRootId(nz(rootId));
            e.setParentId(nz(parentId));
            e.setLikeCount(nz(likeCount));
            e.setDislikeCount(nz(dislikeCount));
            e.setReplyCount(nz(replyCount));
            e.setState(state == null ? 0 : state);
            e.setAttribute(nz(attribute));
            e.setCreatedAt(fromWireTime(createdAt));
            e.setUpdatedAt(fromWireTime(updatedAt));
            return e;
        }
    }

    /**
     * 对应 Go {@code model.CommentContent}（无 json tag）。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentCacheService.java:806-841}。
     */
    @JsonPropertyOrder({"CommentId", "Content", "Meta", "CreatedAt", "UpdatedAt"})
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class CommentContentCacheDto {

        @JsonProperty("CommentId")
        Long commentId;
        @JsonProperty("Content")
        String content;
        @JsonProperty("Meta")
        String meta;
        @JsonProperty("CreatedAt")
        String createdAt;
        @JsonProperty("UpdatedAt")
        String updatedAt;

        static CommentContentCacheDto from(CommentContentEntity e) {
            CommentContentCacheDto dto = new CommentContentCacheDto();
            dto.commentId = e.getCommentId();
            dto.content = e.getContent();
            dto.meta = e.getMeta();
            dto.createdAt = toWireTime(e.getCreatedAt());
            dto.updatedAt = toWireTime(e.getUpdatedAt());
            return dto;
        }

        CommentContentEntity toEntity() {
            CommentContentEntity e = new CommentContentEntity();
            e.setCommentId(commentId);
            e.setContent(content);
            e.setMeta(meta);
            e.setCreatedAt(fromWireTime(createdAt));
            e.setUpdatedAt(fromWireTime(updatedAt));
            return e;
        }
    }

    /** Go {@code time.Time} 的 JSON 形态：RFC3339Nano 字符串。 */
    private static String toWireTime(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }

    private static OffsetDateTime fromWireTime(String value) {
        return (value == null || value.isEmpty()) ? null : OffsetDateTime.parse(value);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
