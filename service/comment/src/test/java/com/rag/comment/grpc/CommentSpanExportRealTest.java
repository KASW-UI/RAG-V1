package com.rag.comment.grpc;

import com.rag.comment.CommentServiceApplication;
import com.rag.comment.domain.service.CommentDomainService;
import com.rag.comment.metrics.CommentRpcMetrics;
import com.rag.comment.support.NacosStubConfiguration;
import com.rag.comment.support.TestPostgres;

import com.rag.proto.comment.CommentServiceGrpc;
import com.rag.proto.comment.CreateCommentReq;
import com.rag.proto.comment.DeleteCommentReq;
import com.rag.proto.comment.GetCommentReq;
import com.rag.proto.comment.LikeCommentReq;
import com.rag.proto.comment.ManageCommentReq;
import com.rag.proto.comment.ManageType;
import com.rag.proto.comment.ReportCommentReq;
import com.rag.proto.comment.ReportReason;
import com.rag.proto.comment.SubjectState;
import com.rag.proto.comment.UpdateSubjectStateReq;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * <p><b>🔴 comment 域的「手写 span 必须真的被导出」守门</b>，
 * 且带齐 B 侧的 span 名 / tracer 名 / 属性名 / 属性值。
 *
 * <h2>🔴 为什么不能只写「结构断言」</h2>
 * <p>AGENTS.md §3.3：<b>不得凭注解 / 方法名称断定机制已启用</b>。
 * 断言「{@code CommentRpcMetrics.traced} 被调用了」只能证明<b>代码写成了这样</b>，
 * 证明不了<b>运行时真的产生了 span</b>。
 * ⇒ ⇒ 本类走<b>装配层</b>：自己装一个 SDK ＋ 收集型 {@link SpanExporter}，
 * 让 {@link GlobalOpenTelemetry} 指向它，然后断言<b>导出的 span</b>。
 *
 * <h2>🔴 B 侧「7 个 logic 里只有 6 个手写了 span」是<b>双向</b>契约</h2>
 * <p>B 侧的 6 个手写 span：{@code create_comment_logic.go:41} · {@code delete_comment_logic.go:37} ·
 * {@code manage_comment_logic.go:37} · {@code report_comment_logic.go:37} ·
 * {@code update_subject_state_logic.go:37} · {@code get_comment_logic.go:52}；
 * {@code like_comment_logic.go} <b>没有</b>任何 otel / span 代码（实测命中数 = 0）。
 * ⇒ ⇒ 本类<b>双向</b>钉死：有 span 的名字必须出现、<b>没 span 的方法必须不产生</b>对应 span
 * （否则 trace 里的 comment span 数会从 6 变 7，与真源不符）。
 *
 * <h2>🔴 B 侧的三条「不一致」必须逐字保留（本类逐条断言）</h2>
 * <ol>
 *   <li><b>tracer 名不统一</b>：5 个 {@code Action-*} 用 {@code "comment-rpc"}，
 *       而 {@code GetComment} 用 {@code "comment.rpc"}（<b>带点</b>）⇒ 断言 instrumentation scope 名。</li>
 *   <li><b>属性前缀不统一</b>：5 个 {@code Action-*} 用 {@code audit.*}，
 *       而 {@code GetComment} 的 6 个属性<b>无前缀</b>
 *       ⇒ 断言 {@code GetComment} 的 span <b>有</b> {@code target_id}、
 *       <b>没有</b> {@code audit.target_id}。</li>
 *   <li><b>状态语义不统一</b>：B 侧的 {@code span.RecordError(err)}（otel-go v1.x）<b>只加异常事件、不改状态</b>；
 *       而 {@code get_comment_logic.go} 的 4 个错误分支<b>额外显式</b>
 *       {@code span.SetStatus(codes.Error, err.Error())}
 *       ⇒ ⇒ <b>5 个 {@code Action-*} 的业务失败：既无异常事件、状态也不是 ERROR</b>；
 *       <b>{@code GetComment} 的系统失败：有异常事件 ＋ 状态 ERROR</b>。</li>
 * </ol>
 *
 * <h2>🔴 为什么用「业务失败」来验那 5 个 Action-* span</h2>
 * <p>5 个 {@code Action-*} span 在 B 侧的属性是在 logic 开头就设的（早于入参校验）
 * ⇒ 所以「校验失败」这条路上 span 与属性<b>照样存在</b>
 * ⇒ 用它当探针既能拿到 span，<b>又不需要写库</b>（不碰 DB ⇒ 不受 PG 数据状态影响）。
 * <br>⚠️ 同时它正好能验第 3 条：B 侧这些分支是 {@code biz_fail} ⇒ <b>不记错误</b>。
 *
 * <h3>AGENTS.md §3.2 三段式</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：① span 常量的落点从 B 侧的 {@code CommentSpans} 改成
 *       {@link CommentRpcMetrics}（D7 包压平 + 映射表 §1.6 第 249 行的「并回指标类」）
 *       ⇒ <b>常量值逐字未改</b>；② 探针从「调 rpc 层的 logic」改成「调域内的
 *       {@link CommentDomainService}」（D2 域内直连的必然结果）⇒ 断言的<b>被测语义相同</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 —— span 名 / tracer 名 / 9+6 个属性名 / 属性值 / 状态语义
 *       逐条照抄 B 侧。</li>
 *   <li><b>B 出处</b>：
 *       <ul>
 *         <li>{@code comment-rpc/…/rpc/CommentSpanWiringTest.java:117-433}</li>
 *         <li>{@code comment-rpc/…/rpc/service/CommentSpans.java}（常量来源）</li>
 *         <li>B 侧 {@code create_comment_logic.go:41} · {@code delete_comment_logic.go:37} ·
 *             {@code manage_comment_logic.go:37} · {@code report_comment_logic.go:37} ·
 *             {@code update_subject_state_logic.go:37} · {@code get_comment_logic.go:52}</li>
 *       </ul></li>
 * </ol>
 *
 * <h2>⚠️ 本类的证据边界（AGENTS.md §3.3.3）</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧真的导出了这些 span」。
 * 「B 侧此刻也导出同样的 span」属于<b>双侧活体对拍</b>（本类<b>不冒充</b>该证据）。
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（容器不可用时<b>直接失败</b>）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}。</li>
 * </ul>
 */
@SpringBootTest(
        classes = {CommentServiceApplication.class, NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                // OTLP 指向不可达端点，只会在后台重试刷日志，关掉（本类自装收集型 exporter）。
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                // 绕过 Kafka：consumer 会持续重试连接，但不影响上下文启动与本类断言。
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                // 绕过 Redis：Lettuce 连接懒建，不真正连（127.0.0.1:1 立即拒连）。
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=1",
                // 让判据**只**取决于「span 有没有真的产生」，不被 CPU 自适应降载抢先返回。
                "rag.protection.cpu-shedding.enabled=false"
        })
@ActiveProfiles("test")
class CommentSpanExportRealTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        registry.add("grpc.server.port", () -> -1);
    }

    /**
     * 自建收集型 exporter —— <b>不引 {@code opentelemetry-sdk-testing}</b>
     * （引入它只为测试反而扩大依赖面；B 侧同样自建）。
     */
    static final class CollectingExporter implements SpanExporter {
        final List<SpanData> spans = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        void clear() {
            spans.clear();
        }
    }

    private static final CollectingExporter EXPORTER = new CollectingExporter();

    @BeforeAll
    static void installSdk() {
        // 🔴 关键：把 GlobalOpenTelemetry 指向本测试的收集器。
        // CommentRpcMetrics 用的是 GlobalOpenTelemetry.getTracer(...)，不装 SDK 就是 no-op（永远导不出 span）。
        // ⚠️ 共享层的 GoStyleOtelAgent.install 也会 GlobalOpenTelemetry.set(...)，
        //    但它**捕获** IllegalStateException（Go: startAgent 失败只 logx.Error、继续跑）⇒ 两边不打架。
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(EXPORTER))
                        .build())
                .build());
    }

    @AfterAll
    static void uninstallSdk() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Autowired
    private CommentDomainService domain;

    private static Optional<SpanData> findSpan(String name) {
        synchronized (EXPORTER.spans) {
            return EXPORTER.spans.stream().filter(s -> name.equals(s.getName())).findFirst();
        }
    }

    private static List<String> exportedSpanNames() {
        synchronized (EXPORTER.spans) {
            return EXPORTER.spans.stream().map(SpanData::getName).toList();
        }
    }

    // ==================================================================
    // ① 5 个 Action-Comment-* span：名字 / tracer 名 / audit.* 属性 / 「业务失败不记错误」
    // ==================================================================

    @Test
    @DisplayName("✅ createComment 真的导出 Action-Comment-Create（tracer=comment-rpc）＋ 5 个 audit.* 属性")
    void createCommentExportsGoSpanWithFiveAuditAttributes() {
        EXPORTER.clear();

        // 🔴 用「业务失败」当探针：B 侧的属性在 logic 开头就设了（早于校验）⇒ span 与属性照样存在 ⇒ 不需要写库。
        assertThrows(RuntimeException.class, () -> domain.createComment(CreateCommentReq.newBuilder()
                        .setUserId(1101L)
                        .setTargetType("article")
                        .setTargetId("")                       // ← 故意为空 ⇒ 触发 biz_fail 分支
                        .setRootId(2202L)
                        .setParentId(3303L)
                        .build()),
                "目标为空 ⇒ 必须失败（探针前提）");

        SpanData span = findSpan(CommentRpcMetrics.CREATE).orElseThrow(
                () -> new AssertionError("没有导出名为 " + CommentRpcMetrics.CREATE + " 的 span。实际导出的 = "
                        + exportedSpanNames()));

        assertThat(span.getInstrumentationScopeInfo().getName())
                .as("Go: create_comment_logic.go:41 ⇒ otel.Tracer(\"comment-rpc\")")
                .isEqualTo("comment-rpc");
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_OPERATOR_ID)).isEqualTo(1101L);
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_TARGET_TYPE)).isEqualTo("article");
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_TARGET_ID)).isEqualTo("");
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_ROOT_ID)).isEqualTo(2202L);
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_PARENT_ID)).isEqualTo(3303L);

        // 🔴 Go 的 RecordError 只落在 result="sys_fail" 分支 ⇒ 业务失败**不记错误、状态也不是 ERROR**
        assertThat(span.getEvents())
                .as("业务失败（biz_fail）在 Go 侧不 RecordError ⇒ 不该有异常事件")
                .isEmpty();
        assertThat(span.getStatus().getStatusCode())
                .as("Go 的 RecordError 不改状态（otel-go v1.x）⇒ 状态应保持 UNSET")
                .isEqualTo(StatusCode.UNSET);
    }

    @Test
    @DisplayName("✅ deleteComment 真的导出 Action-Comment-Delete ＋ 4 个 audit.* 属性")
    void deleteCommentExportsGoSpanWithFourAuditAttributes() {
        EXPORTER.clear();

        assertThrows(RuntimeException.class, () -> domain.deleteComment(DeleteCommentReq.newBuilder()
                        .setUserId(4404L)
                        .setCommentId(0L)                       // ← 故意为 0 ⇒ biz_fail 分支
                        .setTargetType("article")
                        .setTargetId("t-1")
                        .build()),
                "评论 ID 为空 ⇒ 必须失败（探针前提）");

        SpanData span = findSpan(CommentRpcMetrics.DELETE).orElseThrow(
                () -> new AssertionError("没有导出名为 " + CommentRpcMetrics.DELETE + " 的 span。实际导出的 = "
                        + exportedSpanNames()));

        assertThat(span.getInstrumentationScopeInfo().getName()).isEqualTo("comment-rpc");
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_OPERATOR_ID)).isEqualTo(4404L);
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_COMMENT_ID)).isZero();
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_TARGET_TYPE)).isEqualTo("article");
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_TARGET_ID)).isEqualTo("t-1");
        assertThat(span.getEvents()).as("业务失败在 Go 侧不 RecordError").isEmpty();
    }

    @Test
    @DisplayName("✅ manageComment 真的导出 Action-Comment-Manage ＋ audit.action_type＝枚举的 proto 值名")
    void manageCommentExportsGoSpanWithEnumValueName() {
        EXPORTER.clear();

        assertThrows(RuntimeException.class, () -> domain.manageComment(ManageCommentReq.newBuilder()
                        .setUserId(5505L)
                        .setCommentId(0L)                       // ← 故意为 0 ⇒ biz_fail 分支
                        .setTargetType("article")
                        .setTargetId("t-1")
                        .setActionType(ManageType.MANAGE_PIN)
                        .build()),
                "评论 ID 为空 ⇒ 必须失败（探针前提）");

        SpanData span = findSpan(CommentRpcMetrics.MANAGE).orElseThrow(
                () -> new AssertionError("没有导出名为 " + CommentRpcMetrics.MANAGE + " 的 span。实际导出的 = "
                        + exportedSpanNames()));

        assertThat(span.getInstrumentationScopeInfo().getName()).isEqualTo("comment-rpc");
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_OPERATOR_ID)).isEqualTo(5505L);
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_COMMENT_ID)).isZero();
        // 🔴 Go: manage_comment_logic.go:54 ⇒ attribute.String("audit.action_type", in.ActionType.String())
        //    ⇒ 值是**枚举的 proto 值名**（"MANAGE_PIN"），不是 Java 常量名、也不是数字。
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_ACTION_TYPE)).isEqualTo("MANAGE_PIN");
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_TARGET_TYPE)).isEqualTo("article");
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_TARGET_ID)).isEqualTo("t-1");
    }

    @Test
    @DisplayName("✅ reportComment 真的导出 Action-Comment-Report ＋ audit.reason＝枚举的 proto 值名")
    void reportCommentExportsGoSpanWithReasonValueName() {
        EXPORTER.clear();

        assertThrows(RuntimeException.class, () -> domain.reportComment(ReportCommentReq.newBuilder()
                        .setUserId(6606L)
                        .setCommentId(0L)                       // ← 故意为 0 ⇒ biz_fail 分支
                        .setTargetType("article")
                        .setTargetId("t-1")
                        .setReason(ReportReason.REASON_SPAM_ADVERT)
                        .build()),
                "评论 ID 为空 ⇒ 必须失败（探针前提）");

        SpanData span = findSpan(CommentRpcMetrics.REPORT).orElseThrow(
                () -> new AssertionError("没有导出名为 " + CommentRpcMetrics.REPORT + " 的 span。实际导出的 = "
                        + exportedSpanNames()));

        assertThat(span.getInstrumentationScopeInfo().getName()).isEqualTo("comment-rpc");
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_OPERATOR_ID)).isEqualTo(6606L);
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_COMMENT_ID)).isZero();
        // 🔴 Go: report_comment_logic.go:57 ⇒ attribute.String("audit.reason", in.Reason.String())
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_REASON)).isEqualTo("REASON_SPAM_ADVERT");
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_TARGET_TYPE)).isEqualTo("article");
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_TARGET_ID)).isEqualTo("t-1");
    }

    @Test
    @DisplayName("✅ updateSubjectState 真的导出 Action-Comment-UpdateSubjectState ＋ audit.target_state＝枚举的【编号】")
    void updateSubjectStateExportsGoSpanWithStateNumber() {
        EXPORTER.clear();

        assertThrows(RuntimeException.class, () -> domain.updateSubjectState(UpdateSubjectStateReq.newBuilder()
                        .setUserId(7707L)
                        .setTargetType("")                     // ← 故意为空 ⇒ biz_fail 分支
                        .setTargetId("t-1")
                        .setState(SubjectState.SUBJECT_CLOSED)
                        .build()),
                "目标为空 ⇒ 必须失败（探针前提）");

        SpanData span = findSpan(CommentRpcMetrics.UPDATE_SUBJECT_STATE).orElseThrow(
                () -> new AssertionError("没有导出名为 " + CommentRpcMetrics.UPDATE_SUBJECT_STATE
                        + " 的 span。实际导出的 = " + exportedSpanNames()));

        assertThat(span.getInstrumentationScopeInfo().getName()).isEqualTo("comment-rpc");
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_OPERATOR_ID)).isEqualTo(7707L);
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_TARGET_TYPE)).isEqualTo("");
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_TARGET_ID)).isEqualTo("t-1");
        // 🔴 Go: update_subject_state_logic.go:56 ⇒ attribute.Int64("audit.target_state", int64(in.State))
        //    ⇒ 值是**枚举的编号**（SUBJECT_CLOSED = 1），**不是**名字。
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_TARGET_STATE))
                .as("audit.target_state 必须是枚举编号（int64(in.State)），不是名字")
                .isEqualTo(1L);
    }

    // ==================================================================
    // ② GetComment：tracer 名带点 + 6 个无前缀属性 + 系统失败才有异常事件与 ERROR 状态
    // ==================================================================

    @Test
    @DisplayName("✅ getComment 真的导出 GetComment（tracer=comment.rpc，带点）＋ 6 个【无前缀】属性 ＋ 系统失败 ⇒ 异常事件 + ERROR 状态")
    void getCommentExportsGoSpanWithUnprefixedAttributes() {
        EXPORTER.clear();

        // 评论区不存在 ⇒ B 侧的 loadSubject 失败分支 ⇒ RecordError + SetStatus(Error)
        assertThrows(RuntimeException.class, () -> domain.getComment(GetCommentReq.newBuilder()
                        .setTargetType("post")                   // ← 不是 "article" ⇒ 回落分支直接返回 null，不去打 RPC
                        .setTargetId("span-probe-missing-subject")
                        .setRootId(8808L)
                        .setPage(3)
                        .setPageSize(25)
                        .setSortType(7)
                        .build()),
                "评论区不存在 ⇒ 必须失败（探针前提）");

        SpanData span = findSpan(CommentRpcMetrics.GET_COMMENT).orElseThrow(
                () -> new AssertionError("没有导出名为 " + CommentRpcMetrics.GET_COMMENT
                        + " 的 span。实际导出的 = " + exportedSpanNames()));

        // 🔴 Go: get_comment_logic.go:52 ⇒ otel.Tracer("comment.rpc") —— **带点**，与那 5 个不同
        assertThat(span.getInstrumentationScopeInfo().getName())
                .as("Go 的 GetComment 用的是另一个 tracer 名（带点）⇒ 不得被「统一」成 comment-rpc")
                .isEqualTo("comment.rpc");

        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_LIST_TARGET_ID))
                .isEqualTo("span-probe-missing-subject");
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_LIST_ROOT_ID)).isEqualTo(8808L);
        // ⚠️ Go :72-73 记的是**原始入参**（in.Page / in.PageSize），不是归一后的值
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_LIST_PAGE)).isEqualTo(3L);
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_LIST_PAGE_SIZE)).isEqualTo(25L);
        assertThat(stringAttr(span, CommentRpcMetrics.ATTR_LIST_TARGET_TYPE)).isEqualTo("post");
        assertThat(longAttr(span, CommentRpcMetrics.ATTR_LIST_SORT_TYPE)).isEqualTo(7L);

        // 🔴 判别式：这 6 个属性**没有 audit. 前缀** ⇒ span 里不该出现 audit.target_id
        assertThat(span.getAttributes().asMap().keySet().stream()
                        .noneMatch(k -> k.getKey().startsWith("audit.")))
                .as("GetComment 的 6 个属性在 B 侧**没有 audit. 前缀** ⇒ span 里不该出现任何 audit.* 属性。实际 = "
                        + span.getAttributes())
                .isTrue();

        // 🔴 B 侧的这 4 个错误分支额外显式 SetStatus(Error) ⇒ 状态必须是 ERROR，且有异常事件
        assertThat(span.getStatus().getStatusCode())
                .as("Go: get_comment_logic.go:86 ⇒ span.SetStatus(codes.Error, err.Error()) ⇒ 状态必须是 ERROR")
                .isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents().stream().anyMatch(e -> "exception".equals(e.getName())))
                .as("Go 的系统失败分支会 RecordError ⇒ 必须有 exception 事件。实际事件 = " + span.getEvents())
                .isTrue();
    }

    // ==================================================================
    // ③ 双向钉死：likeComment **不产生** span（Go 侧没有 ⇒ 不许凭空加）
    // ==================================================================

    @Test
    @DisplayName("✅ likeComment 【不产生】任何 span —— Go 的 like_comment_logic.go 根本没有 otel 代码")
    void likeCommentMustNotProduceSpan() {
        EXPORTER.clear();

        assertThrows(RuntimeException.class, () -> domain.likeComment(
                        LikeCommentReq.newBuilder()
                                .setUserId(9909L)
                                .setCommentId(0L)
                                .build()),
                "评论 ID 为空 ⇒ 必须失败（探针前提）");

        List<String> names = exportedSpanNames();
        assertThat(names.stream().noneMatch(n -> n.contains("Like")))
                .as("Go 的 like_comment_logic.go 没有 tracer.Start ⇒ 不该产生任何含 Like 的 span。实际导出的 = " + names)
                .isTrue();
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private static Long longAttr(SpanData span, String key) {
        Long v = span.getAttributes().get(AttributeKey.longKey(key));
        assertThat(v)
                .as("span " + span.getName() + " 缺少属性 " + key + "（实际属性 = " + span.getAttributes() + "）")
                .isNotNull();
        return v;
    }

    private static String stringAttr(SpanData span, String key) {
        String v = span.getAttributes().get(AttributeKey.stringKey(key));
        assertThat(v)
                .as("span " + span.getName() + " 缺少属性 " + key + "（实际属性 = " + span.getAttributes() + "）")
                .isNotNull();
        return v;
    }

    /**
     * ⚠️ <b>编译期锚点</b>：确认 {@code CommentServiceGrpc} 在本测试的 classpath 上
     * （proto 模块是根级 {@code proto/}，pb 包名 {@code com.rag.proto.comment}）。
     * ⇒ 若 proto 落点变动，本类会**编译期**报错，而不是运行期静默走空。
     */
    @SuppressWarnings("unused")
    private static final Class<?> PROTO_ANCHOR = CommentServiceGrpc.class;
}
