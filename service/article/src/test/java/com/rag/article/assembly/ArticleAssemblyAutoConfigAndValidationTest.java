package com.rag.article.assembly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.article.ArticleServiceApplication;
import com.rag.article.client.ContentAuditAutoConfiguration;
import com.rag.article.client.ContentAuditPort;
import com.rag.article.client.UnavailableContentAuditClient;
import com.rag.article.domain.entity.ArticleEntity;
import com.rag.article.dto.article.CreateArticleReq;
import com.rag.article.mapper.ArticleMapper;
import com.rag.article.support.AssemblyTestBase;
import com.rag.common.security.JwtUtil;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>装配 / 机制门禁测试（A5 · A10 · A13）</b> —— 全部是<b>运行期证据</b>，
 * 不是「类上有这个注解」的形态证据。
 *
 * <h2>🔴 为什么这三条判据必须落到运行期</h2>
 * <p>AGENTS.md §3.3：<b>「Bean 存在」≠「机制生效」</b>；
 * §3.3.1：判断某个能力是否生效，<b>必须有运行证据</b>；
 * §3.3.3：<b>不得凭注解名称断定机制已启用</b>。
 * <p>而守门脚本 {@code tools/difftest/check-arch-invariants.py}（A1–A13）
 * <b>只做源码文本级扫描</b> ⇒ 它能判「形状对不对」，<b>判不了「机制真不真在跑」</b>。
 * ⇒ ⇒ 本类补的正是后半段：
 * <table border="1">
 *   <caption>形态证据 vs 运行期证据（本类的分工）</caption>
 *   <tr><th>判据</th><th>形态证据（守门脚本）</th><th>运行期证据（本类）</th></tr>
 *   <tr><td>A5 域内 {@code @Import} 清单为空</td>
 *       <td>扫源码里的 {@code @Import}</td>
 *       <td><b>容器里真的有 {@code ContentAuditPort} 兜底 Bean</b>
 *           ⇒ 说明域内 {@code .imports} 文件真的被 Boot 读到了</td></tr>
 *   <tr><td>A10 Bean Validation 生效</td>
 *       <td>扫「有 DTO 的模块引了 validation 依赖」</td>
 *       <td><b>真的提交一个缺 {@code title} 的 body</b>
 *           ⇒ 真的抛 {@code MethodArgumentNotValidException}、文案逐字节一致</td></tr>
 *   <tr><td>A13 Lombok 展开注释</td>
 *       <td>扫「用了 Lombok 就要有展开块」</td>
 *       <td><b>实体真写真读</b> ⇒ Lombok 生成的 getter/setter 真的能跑</td></tr>
 * </table>
 *
 * <h2>B 出处（逐条 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ 两侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th><th>C 侧</th></tr>
 *   <tr><td>域内自动配置的注册机制（模块内 {@code .imports} 文件）</td>
 *       <td>{@code article-rpc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 *           （一行：{@code com.rag.article.article.rpc.client.ContentSecurityAutoConfiguration}）</td>
 *       <td>{@code service/article/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 *           （一行：{@code com.rag.article.client.ContentAuditAutoConfiguration}）</td></tr>
 *   <tr><td>审核兜底实现（恒抛，fail-loud）</td>
 *       <td>{@code client/UnavailableContentSecurityClient.java:61-84}</td>
 *       <td>{@code client/UnavailableContentAuditClient.java:71-113}</td></tr>
 *   <tr><td>必填校验的文案（逐字节）</td>
 *       <td>{@code api/dto/CreateArticleReq.java:108-119}
 *           ⇒ {@code field "title" is not set}</td>
 *       <td>{@code dto/article/CreateArticleReq.java:80-81}</td></tr>
 *   <tr><td>Bean Validation 失败的 HTTP 形状（400 + text/plain）</td>
 *       <td>{@code api/advice/GlobalExceptionHandler.java:263-267}</td>
 *       <td>{@code advice/ArticleApiExceptionHandler.java:301-308}</td></tr>
 *   <tr><td>实体字段集合（getter/setter 覆盖范围）</td>
 *       <td>{@code rpc/domain/ArticleEntity.java:49-141} + {@code :143-278}（手写访问器）</td>
 *       <td>{@code domain/entity/ArticleEntity.java:106-141}
 *           （Lombok {@code @Getter @Setter @NoArgsConstructor @AllArgsConstructor}）</td></tr>
 * </table>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把域内 {@code .imports} 文件删掉（或把 auto-config 改成 {@code @Component}）
 *       ⇒ 用例 ① <b>立刻红</b>（容器里没有 {@code ContentAuditPort}）；</li>
 *   <li>把 {@code CreateArticleReq} 的 {@code @NotNull(message=…)} 去掉
 *       ⇒ 用例 ② 与 ③ <b>立刻红</b>（缺 title 的 body 不再被拒 / 文案变 Hibernate 默认措辞）；</li>
 *   <li>把 {@code ArticleEntity} 的 {@code @Getter/@Setter} 去掉（或改名）
 *       ⇒ 用例 ④ <b>立刻红</b>（真写真读直接编译不过 / 字段值回不来）。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>单例 PG 容器共用 ⇒ 本类用专属 id 段 {@code 903001xxx}。
 */
class ArticleAssemblyAutoConfigAndValidationTest extends AssemblyTestBase {

    /** 与 {@code AssemblyTestBase} 注入的 {@code USER_JWT_SECRET} 逐字一致。 */
    private static final String JWT_SECRET = "test-only-article-jwt-secret-at-least-32-bytes-long";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Validator validator;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    // ==================================================================
    // ① A5 · 域内 @Import 清单为空 ⇒ 域内 .imports 文件真的被自动装配读到了
    // ==================================================================

    @Test
    @DisplayName("🔴 A5 运行期证据：容器里真的有 ContentAuditPort 兜底 Bean"
            + "（且 bean 名 = 自动配置的 FQCN ⇒ 域内 .imports 机制真生效）")
    void domainAutoConfigurationIsReallyAssembled() {
        // ── 形态证据（守门脚本已经覆盖，这里只做对照，不当作运行期结论）──
        assertNull(ArticleServiceApplication.class.getAnnotation(Import.class),
                "ArticleServiceApplication 不得带 @Import（A5 判据）");

        // ── 运行期证据：容器里真的有这个 Bean ──
        ContentAuditPort port = context.getBean(ContentAuditPort.class);
        assertNotNull(port, "容器里必须有 ContentAuditPort 兜底实现");
        assertInstanceOf(UnavailableContentAuditClient.class, port,
                "兜底实现必须是 UnavailableContentAuditClient（恒抛，fail-loud）");

        // 🔴 最锋利的一条：自动配置注册出来的 bean 名 = **类的全限定名**
        //   （Boot 注册 auto-config 的命名规则）⇒ 它<b>只可能</b>来自
        //   {@code META-INF/spring/…AutoConfiguration.imports}，
        //   <b>不可能</b>来自组件扫描（扫描会叫 "contentAuditAutoConfiguration"）
        //   ⇒ ⇒ 这一条就是「.imports 机制真生效」的<b>运行期</b>证据。
        Set<String> autoConfigBeanNames = Set.of(
                context.getBeanNamesForType(ContentAuditAutoConfiguration.class));
        assertTrue(autoConfigBeanNames.contains(ContentAuditAutoConfiguration.class.getName()),
                "自动配置必须以 FQCN 注册 ⇒ 证明是 .imports 文件读进来的；实际 bean 名 = "
                        + autoConfigBeanNames);

        // 兜底实现的 @Bean 方法名（"unavailableContentAuditClient"）也必须真的存在
        assertTrue(context.containsBean("unavailableContentAuditClient"),
                "ContentAuditAutoConfiguration#unavailableContentAuditClient 必须被调用");

        // ⚠️ 兜底必须是 fail-loud：调用即抛（绝不能「返回 isAd=false 就算通过」）
        org.junit.jupiter.api.Assertions.assertThrows(
                ContentAuditPort.ContentAuditException.class,
                () -> port.sanitizeText("# body"),
                "审核不可用时必须抛（fail-loud）⇒ 文章停在 REVIEWING，而不是被放行");
    }

    // ==================================================================
    // ② A10 · Bean Validation 真生效（容器里的真 Validator）
    // ==================================================================

    @Test
    @DisplayName("🔴 A10 运行期证据：容器里的真 Validator 真的拦住了「缺 title」的入参，"
            + "且 default message 逐字节 = field \"title\" is not set")
    void beanValidationReallyRejectsMissingTitle() throws Exception {
        assertNotNull(validator, "容器里必须有 jakarta.validation.Validator（Hibernate Validator）");

        // ⚠️ 用**生产的 ObjectMapper** 反序列化一份「缺 title」的 body
        //   ⇒ 走的是真实的 Jackson 绑定路径（不是手工 new 一个 DTO）
        CreateArticleReq req = objectMapper.readValue(
                "{\"content\":\"c\"}", CreateArticleReq.class);
        assertNull(req.title(), "前提：title 确实缺席");

        Set<ConstraintViolation<CreateArticleReq>> violations = validator.validate(req);
        assertEquals(1, violations.size(), "应当恰好一条违规；实际 = " + violations);

        ConstraintViolation<CreateArticleReq> violation = violations.iterator().next();
        assertEquals("title", violation.getPropertyPath().toString(),
                "违规字段必须是 title");
        // ⚠️ Bean Validation 3.x 里「DTO 上写死的那条文案」= getMessageTemplate()，
        //    getMessage() 是插值后的结果（本条文案里没有 EL 占位符 ⇒ 两者逐字节相同）。
        //    Spring 的 FieldError#getDefaultMessage() 取的也是这一条（见用例 ③ 的 HTTP 断言）。
        assertEquals("field \"title\" is not set", violation.getMessageTemplate(),
                "🔴 message template 必须逐字节等于 B 侧的 field \"title\" is not set");
        assertEquals("field \"title\" is not set", violation.getMessage(),
                "🔴 插值后的文案必须与 message template 逐字节相同"
                        + "（文案里没有 EL 占位符 ⇒ 两者必须一致）");
    }

    // ==================================================================
    // ③ A10 · 真 Tomcat 上的 HTTP 路径（真的抛 MethodArgumentNotValidException）
    // ==================================================================

    @Test
    @DisplayName("🔴 A10 运行期证据：真 Tomcat 上 POST 缺 title 的 body ⇒ 400 + text/plain，"
            + "body 逐字节 = field \"title\" is not set\\n")
    void missingTitleBodyIsRejectedOverRealHttp() {
        String token = JwtUtil.getToken(JWT_SECRET, System.currentTimeMillis() / 1000L, 3600L, 903001001L);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/article", HttpMethod.POST,
                new HttpEntity<>("{\"content\":\"c\"}", headers), String.class);

        assertEquals(400, response.getStatusCode().value(),
                "缺 title 的 body 必须 400");
        assertEquals("field \"title\" is not set\n", response.getBody(),
                "🔴 body 必须是 B 侧逐字节文案（末尾 \\n 来自 Fprintln 语义）");
        assertTrue(response.getHeaders().getFirst("Content-Type") != null
                        && response.getHeaders().getFirst("Content-Type").startsWith("text/plain"),
                "必须是 text/plain（B 侧 httpx.Error ⇒ http.Error）；实际 = "
                        + response.getHeaders().getFirst("Content-Type"));
    }

    // ==================================================================
    // ④ A13 · Lombok 生成的访问器真能跑（真 PG 写入 + 读回）
    // ==================================================================

    @Test
    @DisplayName("🔴 A13 运行期证据：ArticleEntity 经 Lombok 生成的 getter/setter 真写真读 ⇒ "
            + "全部字段值逐字段一致（含 jsonb 的 List/Map）")
    void lombokGeneratedAccessorsReallyWorkOnRealPg() {
        String id = "903001010";
        ArticleEntity entity = new ArticleEntity();
        entity.setId(id);
        entity.setTitle("标题");
        entity.setBrief("摘要");
        entity.setContent("articles/content/" + id + ".md");
        entity.setCoverImageUrl("http://host/img.png");
        entity.setManualTypeTag("tag-a");
        entity.setSecondaryTags(java.util.List.of("x", "y"));
        entity.setAuthorId("903000001");
        entity.setStatus(com.rag.proto.article.ArticleStatus.REVIEWING_VALUE);
        entity.setViewCount(7);
        entity.setLikeCount(3);
        entity.setCommentCount(2);
        entity.setShareCount(5);
        entity.setExtInfo(Map.of("review_nonce", "nonce-1", "reco_sync_state", "pending"));
        // ⚠️ 建行时**显式**写两个时间列（ArticleMetaObjectHandler#insertFill 是刻意的空实现：
        //    「Java 侧建行已显式写入两个时间列，此处再填会覆盖调用方的显式赋值」）
        java.time.OffsetDateTime createdAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt);

        int inserted = articleMapper.insert(entity);
        assertEquals(1, inserted, "写入必须成功（Lombok setter → MP 反射取值这条路径）");

        ArticleEntity loaded = articleMapper.selectById(id);
        assertNotNull(loaded, "读回必须成功（Lombok setter ← MyBatis 结果映射这条路径）");

        // ⚠️ 逐字段断言：读回的值必须**逐个**等于写进去的值
        //   ⇒ getter 拿到的不是常量、不是默认值 ⇒ Lombok 生成的访问器真的参与了往返
        assertEquals(id, loaded.getId());
        assertEquals("标题", loaded.getTitle());
        assertEquals("摘要", loaded.getBrief());
        assertEquals("articles/content/" + id + ".md", loaded.getContent());
        assertEquals("http://host/img.png", loaded.getCoverImageUrl());
        assertEquals("tag-a", loaded.getManualTypeTag());
        assertEquals(java.util.List.of("x", "y"), loaded.getSecondaryTags(),
                "jsonb 数组必须原样回来");
        assertEquals("903000001", loaded.getAuthorId());
        assertEquals(com.rag.proto.article.ArticleStatus.REVIEWING_VALUE, loaded.getStatus());
        assertEquals(7, loaded.getViewCount());
        assertEquals(3, loaded.getLikeCount());
        assertEquals(2, loaded.getCommentCount());
        assertEquals(5, loaded.getShareCount());
        assertEquals("nonce-1", loaded.getExtInfo().get("review_nonce"),
                "jsonb map 必须原样回来");
        assertEquals("pending", loaded.getExtInfo().get("reco_sync_state"));
        assertNotNull(loaded.getCreatedAt(), "created_at 必须被写进去并读回来");
        assertNotNull(loaded.getUpdatedAt(), "updated_at 必须被写进去并读回来");

        // ── 再走一次「更新」路径：ArticleMetaObjectHandler#updateFill 会自动推进 updated_at
        //   ⇒ 这条同时证明「Lombok 生成的 setter 被 MP 的更新语句真的用上了」
        //   ⚠️ **必须在 updateById 之前捕获 before** —— MP 的 updateFill 是**原地**改实体的
        //   （setFieldValByName 写的就是参数对象本身）⇒ ⇒ 若在调用**之后**再读
        //   `loaded.getUpdatedAt()`，拿到的**已经是新值** ⇒ `isAfter` 恒 false ⇒ **一条假判据**。
        final java.time.OffsetDateTime beforeUpdate = loaded.getUpdatedAt();
        loaded.setTitle("改了标题");
        articleMapper.updateById(loaded);
        ArticleEntity reloaded = articleMapper.selectById(id);
        assertNotNull(reloaded);
        assertEquals("改了标题", reloaded.getTitle(), "更新必须真的落库");
        assertTrue(reloaded.getUpdatedAt() != null
                        && reloaded.getUpdatedAt().isAfter(beforeUpdate),
                "🔴 updated_at 必须被 ArticleMetaObjectHandler#updateFill 自动推进；写前 = "
                        + beforeUpdate + "，写后 = " + reloaded.getUpdatedAt());

        jdbcTemplate.update("DELETE FROM article WHERE id = ?", id);
    }
}
