package com.rag.article.support;

import com.rag.article.ArticleServiceApplication;
import com.rag.article.client.ArticleObjectStorageClient;
import com.rag.common.security.JwtUtil;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * <p><b>article 域 HTTP 层的「真 Tomcat」测试底座</b> —— 真 Spring 上下文 + 真 Tomcat
 * ＋ 真 HTTP 请求（本仓<b>不用</b> MockMvc 断言 {@code Content-Type} / {@code nosniff} /
 * 状态行 —— AGENTS.md §八 检查清单「HTTP 断言走真 Tomcat」）。
 *
 * <h2>① 为什么<b>不</b> {@code extends ArticleRealPgTestBase}</h2>
 * <p>{@code ArticleRealPgTestBase} 的 {@code webEnvironment = NONE}
 * ⇒ <b>根本没有 Tomcat</b> ⇒ HTTP 断言无处可断言。
 * <p>而 {@code webEnvironment} 是 {@code MergedContextConfiguration} 的组成部分
 * ⇒ ⇒ <b>哪怕继承它，也拿不到它那个上下文</b>（键不同 ⇒ Spring 会另起一个上下文）
 * ⇒ ⇒ 继承<b>省不到</b>任何一次装配，却要额外承担「父类属性是否被子类覆盖」的风险。
 * <p>⇒ ⇒ 故本类<b>自建</b> {@code @SpringBootTest} + {@code @DynamicPropertySource}
 * （属性逐条照抄 {@code ArticleRealPgTestBase}），并让<b>本包内全部 HTTP 测试类共享这一个上下文</b>
 * （它们都继承本类 ⇒ {@code @DynamicPropertySource} 的方法集合相同 ⇒ 上下文键相同）。
 *
 * <h2>② 「裸占位符」必须显式提供（否则上下文起不来）</h2>
 * <p>{@code application.yml} 的 {@code POSTGRES_PASSWORD} / {@code USER_JWT_SECRET} 是
 * <b>裸占位符</b>（无默认值）⇒ 缺一个都解析失败。
 * ⚠️ {@code USER_JWT_SECRET} 还额外被 {@code ArticleApiProperties#verifyAccessSecret}
 * 的 fail-closed 守卫卡着（空串 / 未解析占位符 ⇒ 启动即失败）。
 *
 * <h2>③ 为什么 {@code grpc.server.port=0} 是必需的</h2>
 * <p>devh 的 gRPC server 是 {@code SmartLifecycle} ⇒ 上下文一启动就 bind 端口
 * ⇒ 固定端口在「多个上下文同时存活」时必然 {@code BindException}。
 * <p>⚠️ 本类与 {@code ArticleRealPgTestBase} 是<b>两个</b>上下文 ⇒ 两个都会 bind ⇒ 必须 0。
 *
 * <h2>④ 为什么 Kafka 的「四个通道」必须与全局 bootstrap-servers 同步</h2>
 * <p>{@code ArticleKafkaChannelValidator} 在<b>启动时</b>比对「四个通道的 brokers」与
 * 「全局 {@code spring.kafka.bootstrap-servers}」⇒ 不一致 ⇒ <b>启动失败</b>。
 * <p>⇒ 只改全局会直接起不来 ⇒ 四个通道的 {@code brokers} 必须一起改。
 * <p>⚠️ {@code spring.kafka.producer.properties.max.block.ms=2000} 同样是必需的：
 * broker 不可达时 {@code KafkaTemplate.send()} 会阻塞到 {@code max.block.ms}（默认 60s）
 * ⇒ {@code ArticleSyncOutboxRelay} 的定时任务会把测试挂住 60s。
 *
 * <h2>⑤ 为什么关掉 CPU 自适应降载</h2>
 * <p>降载命中时 {@code CpuSheddingFilter} 走 {@code response.setStatus(503)} 并<b>直接 return</b>
 * ⇒ <b>裸 503、无 body、无信封</b> ⇒ 会把本类要验的判据「抢先盖掉」
 * ⇒ ⇒ 机器一忙就<b>红在错的地方</b>（判据与被测路径无关）。
 * <p>⚠️ 这是<b>测试专用</b>覆盖，<b>不改生产默认值</b>（生产默认 {@code true} 对齐 Go 侧
 * {@code rest/config.go:22} {@code Middlewares.Shedding bool json:",default=true"}）。
 *
 * <h2>⑥ 为什么 mock 掉 {@link ArticleObjectStorageClient}（<b>唯一的</b> mock）</h2>
 * <p>对象存储是<b>跨进程外部依赖</b>（本仓没有 MinIO 容器，且 pom 不可改）。
 * B 侧当年也是把跨进程边界 mock 掉的（{@code @MockitoBean ArticleRpcClient} /
 * {@code @MockitoBean UserRpcClient}）⇒ 本仓 D2 去掉 gRPC 跳之后，对应的外部边界就是对象存储。
 * <p>⚠️ <b>被测对象不是它</b>：本类验的是 <b>HTTP 层的 wire 形状</b>（信封 / 状态码 /
 * 响应头 / 文案 / 键序），这些全部由 {@code advice/} + {@code controller/} 产出
 * ⇒ ⇒ mock 掉外部存储<b>不削弱</b>任何一条被验契约。
 * <p>⚠️ mock 只声明在<b>本基类</b>一处 ⇒ 所有子类共享同一个 {@code Field}
 * ⇒ Spring 的 {@code DefinitionsParser}（{@code SearchStrategy.SUPERCLASS}）能在子类中找到它
 * ⇒ ⇒ 且 {@code MockitoContextCustomizer} 的 {@code equals} 只比 {@code name/reset/types}
 * ⇒ ⇒ <b>四个 HTTP 测试类共用同一个 Spring 上下文</b>（不重复装配）。
 *
 * <h2>⑦ 零字节 body 的断言为什么走「裸 socket」</h2>
 * <p>{@code TestRestTemplate} 对 4xx/5xx 的 body 抽取语义（{@code hasMessageBody}）
 * 会把「零长度 body」与「null body」折叠 ⇒ <b>拿不到字节数</b>。
 * <p>⇒ 本类额外提供 {@link #rawHttp(String)}：用裸 socket 手写 HTTP/1.1 请求、
 * 把响应<b>逐字节</b>读回来 ⇒ 可以断言「状态行逐字」「body 真的是 0 字节」
 * 「响应头里有没有 Content-Type / nosniff」这些<b>字节面</b>事实。
 *
 * <h2>⑧ B 出处</h2>
 * <ul>
 *   <li>B 侧真 Tomcat 形态的测试资产：
 *       {@code service/article/article-api/src/test/java/…/support/RealTomcatPort.java}
 *       ＋ {@code ArticleApiMvcPipelineTest} / {@code ArticleApiRouteAuthBoundaryTest}
 *       （B 侧用 MockMvc，本仓按 AGENTS.md §八 改成真 Tomcat ⇒ <b>形态差异已在各测试类 javadoc 标注</b>）；</li>
 *   <li>属性来源 = 本仓 {@code service/article/src/test/java/…/support/ArticleRealPgTestBase.java:57-90}；</li>
 *   <li>JWT 签发形态 = 共享层 {@code com.rag.common.security.JwtUtil#getToken}
 *       （与 B 侧 {@code service/users/users-common/…/jwt/JwtUtil.java:63} 同形，HS256 + {@code userId} claim）。</li>
 * </ul>
 */
@SpringBootTest(
        classes = {ArticleServiceApplication.class, NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // ── Nacos 不触网 ──
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "logging.level.com.alibaba.nacos=WARN",
                // ── 追踪不外发 ──
                "management.otlp.tracing.export.enabled=false",
                // ── gRPC 端口交给 OS（devh 的 gRPC server 是 SmartLifecycle ⇒ 启动即 bind）──
                "grpc.server.port=0",
                // ── Kafka 不可达：四个通道的 brokers 必须与全局 bootstrap-servers 同步 ──
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                "spring.kafka.producer.properties.max.block.ms=2000",
                "article-rpc.kq-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.kq-consumer-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-pusher-conf.brokers=127.0.0.1:1",
                "article-rpc.article-sync-result-consumer-conf.brokers=127.0.0.1:1",
                // ── 对象存储不可达（真 MinIO 客户端只在本类里被 mock 掉，见 ⑥）──
                "article-rpc.minio.endpoint=127.0.0.1:1",
                // ── 关掉 CPU 自适应降载（见 ⑤）：否则会抢先返回裸 503，把判据盖掉 ──
                "rag.protection.cpu-shedding.enabled=false"
        })
public abstract class HttpRealTomcatTestBase {

    /**
     * 测试用 JWT 密钥 —— <b>必须与 {@code @DynamicPropertySource} 注入的那一份逐字相同</b>。
     *
     * <p>⚠️ 这不是生产密钥（生产由环境变量 {@code USER_JWT_SECRET} 提供，
     * 与签发方 user-api 一致）⇒ 本值只在本仓测试上下文内有效。
     */
    public static final String JWT_SECRET = "test-only-article-jwt-secret-at-least-32-bytes-long";

    /** 种子作者 id（与既有真 PG 测试的号段不重叠）。 */
    protected static final String SEED_AUTHOR = "900101001";

    /** 另一篇种子文章的作者 id（用于「非作者」场景）。 */
    protected static final String OTHER_AUTHOR = "900101999";

    /** 较老的种子文章 id。 */
    protected static final String ARTICLE_OLD = "900101001";

    /** 较新的种子文章 id。 */
    protected static final String ARTICLE_NEW = "900101002";

    /**
     * <p><b>唯一的 mock</b> —— 跨进程的对象存储边界（见类注释 ⑥）。
     *
     * <p>⚠️ 刻意<b>声明在基类</b>：Spring 的 {@code DefinitionsParser} 用
     * {@code SearchStrategy.SUPERCLASS} 查找 {@code @MockitoBean} 字段
     * ⇒ 子类能找到本字段 ⇒ 且 {@code Field} 对象唯一 ⇒ 四个 HTTP 测试类共享一个上下文。
     */
    @MockitoBean
    protected ArticleObjectStorageClient articleObjectStorageClient;

    /** 真 Tomcat 上的 {@code TestRestTemplate}（{@code webEnvironment = RANDOM_PORT} ⇒ 自动指向随机端口）。 */
    @Autowired
    protected TestRestTemplate rest;

    /** 真 Tomcat 的端口（{@code @LocalServerPort}）。 */
    @LocalServerPort
    protected int port;

    /** 真 PG（复用域内既有单例容器）。 */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * <p>把「真连接信息」与「三个必须显式提供的属性」注入上下文。
     *
     * <p>⚠️ 本方法被本包内全部 HTTP 测试类继承 ⇒ 方法集合相同 ⇒ <b>共享同一个上下文</b>。
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        // 🔴 裸占位符必须能被解析（application.yml:35 / :221 / :305）
        registry.add("POSTGRES_PASSWORD", () -> "password");
        registry.add("USER_JWT_SECRET", () -> JWT_SECRET);
        // ⚠️ devh 在 Bean 初始化期建 channel ⇒ discovery:// 解析不了 ⇒ 上下文起不来
        registry.add("grpc.client.message-rpc.address", () -> "static://127.0.0.1:1");
    }

    /**
     * 每个用例前重置 mock 桩 —— {@code @MockitoBean} 默认 {@code MockReset.AFTER}
     * ⇒ 桩必须逐用例重设。
     */
    @BeforeEach
    void stubObjectStorage() throws Exception {
        Mockito.when(articleObjectStorageClient.getObjectNamePrefixForImage())
                .thenReturn("articles/images/");
        Mockito.when(articleObjectStorageClient.buildPublicFileURL(Mockito.anyString()))
                .thenReturn("http://minio.invalid/articles/images/seed.png");
        Mockito.when(articleObjectStorageClient.readString(Mockito.anyString()))
                .thenReturn("# seed markdown");
    }

    // ==================================================================
    // JWT 工具（测试自己签 token —— 本域只验签、不签发）
    // ==================================================================

    /**
     * 签一个<b>合法</b>的 token（含 {@code userId} claim）。
     *
     * <p>形态与共享层 {@code JwtUtil#getToken} 一致（HS256 + {@code userId}）
     * ⇒ 与 B 侧 {@code service/users/users-common/…/jwt/JwtUtil.java:63} 同形。
     */
    protected String tokenFor(long userId) {
        long now = System.currentTimeMillis() / 1000L;
        return JwtUtil.getToken(JWT_SECRET, now, 3600L, userId);
    }

    /**
     * 签一个<b>已过期</b>的 token（{@code exp} 在过去）—— Go 侧 {@code handler.Authorize}
     * 只验签名与 {@code exp} ⇒ 过期即 {@code unauthorized()}。
     */
    protected String expiredTokenFor(long userId) {
        long now = System.currentTimeMillis() / 1000L;
        return JwtUtil.getToken(JWT_SECRET, now - 7200L, 60L, userId);
    }

    /**
     * 签一个用<b>别的密钥</b>签的 token（签名不匹配）⇒ 验签失败 ⇒ 401。
     */
    protected String tokenSignedWithWrongSecret(long userId) {
        long now = System.currentTimeMillis() / 1000L;
        return JwtUtil.getToken("a-completely-different-secret-of-32-bytes!!", now, 3600L, userId);
    }

    /**
     * 签一个<b>没有 {@code userId} claim</b>的 token ——
     * 用于验「验签通过但 claim 缺席 ⇒ 业务码 1005（HTTP 200 + 信封）」这条路径。
     *
     * <p>⚠️ Go 侧 {@code handler.Authorize} <b>不校验 claim 取值</b>
     * ⇒ 「签名合法但没有 userId」在 Go 侧是 <b>200 + {code:1005}</b>，<b>不是 401</b>。
     */
    protected String tokenWithoutUserIdClaim() {
        long now = System.currentTimeMillis() / 1000L;
        SecretKeySpec key = new SecretKeySpec(JwtUtil.padToHmacKeySize(JWT_SECRET), "HmacSHA256");
        return Jwts.builder()
                .header().add("typ", "JWT").and()
                .issuedAt(new Date(now * 1000L))
                .expiration(new Date((now + 3600L) * 1000L))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** {@code Authorization} 头的规范形态。 */
    protected String bearer(String token) {
        return "Bearer " + token;
    }

    /** 带 {@code Content-Type: application/json} 的请求头。 */
    protected HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** 带 {@code Content-Type: application/json} ＋ {@code Authorization} 的请求头。 */
    protected HttpHeaders authHeaders(String token) {
        HttpHeaders headers = jsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearer(token));
        return headers;
    }

    // ==================================================================
    // 种子数据（真 PG）
    // ==================================================================

    /**
     * 清空本域全部业务表。
     *
     * <p>⚠️ <b>只能用 TRUNCATE，不能用 DELETE</b>：{@code article_revision} 上有
     * {@code BEFORE UPDATE OR DELETE} 触发器（{@code article_revision_immutable}）
     * ⇒ DELETE 会被触发器拒绝。
     */
    protected void truncateArticleTables() {
        jdbcTemplate.execute("TRUNCATE article, article_sync_outbox, article_revision, "
                + "article_publication, article_domain_outbox RESTART IDENTITY CASCADE");
    }

    /**
     * 插入一篇「<b>已发布 + 有指针 + 有 revision</b>」的文章（⇒ 公开可见）。
     *
     * <p>⚠️ 为什么必须带 {@code article_publication} + {@code article_revision}：
     * 公开读的可见性谓词是
     * {@code (p.state='published' AND p.current_revision<>'' AND EXISTS(revision))
     *  OR (p.article_id IS NULL AND a.status=2)}
     * ⇒ 只插 {@code article} 行（legacy 分支）会走「正文回落读对象存储」那条路径
     * ⇒ 语义与「有 revision」不同 ⇒ 断言会失真。
     *
     * @param id                 文章 id
     * @param authorId           作者 id
     * @param title              源行标题（公开读看到的是 <b>revision</b> 的标题）
     * @param manualTypeTag      分类标签（源行与 revision 各写一份，便于按标签过滤）
     * @param createdAtIso       创建时间（ISO-8601 UTC）—— 决定列表排序
     */
    protected void insertPublishedArticle(String id, String authorId, String title,
                                           String manualTypeTag, String createdAtIso) {
        jdbcTemplate.update("""
                INSERT INTO article (id, title, brief, content, cover_image_url, manual_type_tag,
                                     secondary_tags, author_id, status, view_count, like_count,
                                     comment_count, share_count, ext_info, created_at, updated_at)
                VALUES (?, ?, 'seed-brief', ?, '', ?,
                        '[]'::jsonb, ?, 2, 0, 0, 0, 0,
                        '{}'::jsonb, ?::timestamptz, ?::timestamptz)
                """, id, title, id + ".md", manualTypeTag, authorId, createdAtIso, createdAtIso);

        String revisionId = id + ":r1";
        jdbcTemplate.update("""
                INSERT INTO article_revision
                    (article_id, revision, revision_id, author_id, source_object, content_sha256,
                     title, brief, cover_image_url, manual_type_tag, secondary_tags,
                     markdown, published_at, sync_event_id)
                VALUES (?, 1, ?, ?, 'source-object', repeat('a', 64),
                        ?, 'seed-brief', '', ?,
                        '[]'::jsonb, '# seed markdown', ?::timestamptz, ?)
                """, id, revisionId, authorId, title, manualTypeTag, createdAtIso, "sync-" + id);

        jdbcTemplate.update("""
                INSERT INTO article_publication (article_id, current_revision, pointer_version,
                                                 state, last_event_id)
                VALUES (?, ?, 1, 'published', ?)
                """, id, revisionId, "evt-" + id);
    }

    /** 插入两篇种子文章（较老 + 较新）⇒ 列表排序可观测。 */
    protected void seedTwoPublishedArticles() {
        insertPublishedArticle(ARTICLE_OLD, SEED_AUTHOR, "old-title", "http-tag",
                "2026-01-01T00:00:00Z");
        insertPublishedArticle(ARTICLE_NEW, SEED_AUTHOR, "new-title", "http-tag",
                "2026-01-02T00:00:00Z");
    }

    // ==================================================================
    // 字节面工具
    // ==================================================================

    /**
     * 用<b>裸 socket</b> 手写一条 HTTP/1.1 请求，把响应<b>逐字节</b>读回来。
     *
     * <p>用途：断言「状态行逐字」「body 真的是 N 字节」「响应头里有没有某个头」
     * —— 这些是 {@code TestRestTemplate} 拿不到（或会被折叠）的字节面事实。
     *
     * @param request 完整请求报文（请求行 + 头 + 空行 + 可选 body），{@code \r\n} 分隔
     * @return 响应报文（ISO-8859-1 解码 ⇒ 字节 1:1 保留）
     */
    protected String rawHttp(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.ISO_8859_1);
        }
    }

    /** 取响应报文的状态行（第一行）。 */
    protected String statusLineOf(String rawResponse) {
        int end = rawResponse.indexOf('\r');
        return end < 0 ? rawResponse : rawResponse.substring(0, end);
    }

    /** 取响应报文的 body 部分（第一个空行之后）。 */
    protected String rawBodyOf(String rawResponse) {
        int separator = rawResponse.indexOf("\r\n\r\n");
        return separator < 0 ? "" : rawResponse.substring(separator + 4);
    }

    /**
     * 归一化 {@code Content-Type}（真 Tomcat 会把 {@code text/plain; charset=utf-8}
     * 归一化成 {@code text/plain;charset=utf-8} ⇒ 分号后的空格不可观测）。
     *
     * <p>⚠️ 归一化只做<b>去空格</b>，<b>不改大小写、不改媒体类型</b>
     * ⇒ 媒体类型本身写错仍然会红。
     */
    protected static String normalizeContentType(String contentType) {
        return contentType == null ? null : contentType.replace(" ", "");
    }

    /**
     * Tomcat 10.1 的<b>状态行</b>（真实字节形态）。
     *
     * <p>🔴 <b>Tomcat 10.1 不发 reason phrase</b> ⇒ 状态行是
     * {@code "HTTP/1.1 <code> "}（<b>码后面有一个空格，但<b>没有</b>原因短语</b>）。
     *
     * <p><b>证据（源码级）</b> —— {@code tomcat-embed-core-10.1.52} 的
     * {@code org/apache/coyote/http11/Http11OutputBuffer#sendStatus(int)}：
     * <pre>
     *   headerBuffer.put(Constants.SP);
     *   // The reason phrase is optional but the space before it is not. Skip
     *   // sending the reason phrase. Clients should ignore it (RFC 7230) and it
     *   // just wastes bytes.
     *   headerBuffer.put(Constants.CR).put(Constants.LF);
     * </pre>
     *
     * <p>⚠️ <b>B 侧（Go {@code net/http}）是会发 reason phrase 的</b>
     * ⇒ 形如 {@code "HTTP/1.1 200 OK"} ⇒ <b>两侧状态行<b>不逐字节相同</b></b>。
     * <p>⇒ 这属<b>框架限制</b>（共享层 {@code JsonContentTypeAdvice.java:71} 的注释
     * 明写「与 T-7『状态行缺 reason phrase』同族 ⇒ 已登记在 global 台账」）
     * ⇒ 本仓无法在应用层补上（Tomcat 把这一行写死在输出缓冲里）。
     * <p>⇒ 按 RFC 9110 / RFC 7230，客户端<b>必须忽略</b> reason phrase ⇒ 语义等价。
     */
    protected static String expectedStatusLine(int code) {
        return "HTTP/1.1 " + code + " ";
    }

    /**
     * 本仓 JSON 响应的 {@code Content-Type}（真实字节形态）。
     *
     * <p>🔴 共享层 {@code JsonContentTypeAdvice}（{@code common/web/JsonContentTypeAdvice.java}）
     * 把<b>所有</b> JSON 响应的 Content-Type 钉成 {@code application/json;charset=utf-8}
     * （<b>分号后无空格</b>）⇒ 这是容器归一化后的唯一形态。
     *
     * <p>⚠️ B 侧（Go {@code httpx.OkJson}）写的是 {@code "application/json; charset=utf-8"}
     * （<b>分号后有空格</b>）⇒ 两侧<b>差一个空格</b> ⇒ 同属 T-7 同族的框架限制。
     */
    protected static final String GO_JSON_CONTENT_TYPE = "application/json;charset=utf-8";
}
