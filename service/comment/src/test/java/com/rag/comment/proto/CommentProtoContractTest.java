package com.rag.comment.proto;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.util.JsonFormat;
import com.rag.proto.comment.CommentItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴 <b>comment proto 的契约回归网</b> —— 把「{@code .proto} 文件两侧一致」升级为
 * 「<b>生成码的契约</b>两侧一致」。
 *
 * <h2>为什么需要它（AGENTS.md §3.4 的效力边界）</h2>
 * <p>§3.4 只保证 {@code .proto} <b>文件本身</b>两侧一致 ⇒ <b>推不出</b>：
 * <ul>
 *   <li><b>字段号</b>一致（改一个号 ⇒ wire 完全变，但文件「看着一致」）；</li>
 *   <li>proto3 {@code optional} 的 <b>presence 语义</b>一致
 *       （{@code optional string} 与 {@code string} 在文件里只差三个词，
 *       wire 上却是「有没有 presence」）；</li>
 *   <li><b>repeated / map</b> 的编码方式一致；</li>
 *   <li><b>枚举值</b>编号一致（枚举值编号<b>进 wire</b>）；</li>
 *   <li><b>rpc 方法名与出入参类型</b>一致（= 服务路径）。</li>
 * </ul>
 * ⇒ 本类把这些<b>全部</b>钉成断言。
 *
 * <h2>① 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>🔴 基准文件内联 + sha256 锚定</b>：B 侧把基准放在
 *       {@code comment-rpc/src/test/resources/proto-contract/comment-proto-contract.expected.txt}
 *       （<b>冻结文件</b>，由脚本从 Go 真源生成）。
 *       本次 C 侧迁移的<b>写权只限 5 个 java 文件</b> ⇒ 无法新增 test resource
 *       ⇒ 基准被<b>逐字内联</b>成本类的一个常量，
 *       并<b>额外加一条 sha256 断言</b>（{@code inlinedBaselineIsByteIdenticalToBSideFrozenFile}）
 *       把内联文本与 B 侧那份<b>冻结文件</b>钉成<b>逐字节相同</b>
 *       ⇒ 「手抄会不会抄错」这个 B 侧原注释担心的问题被<b>可执行地</b>关掉：
 *       抄错一个字节 ⇒ sha256 断言红。</li>
 *   <li><b>🔴 额外钉住「pb 包名」</b>：C 侧的 pb 类在 {@code com.rag.proto.comment}
 *       （D7 集中化后的根级 {@code proto} 模块），B 侧在
 *       {@code com.rag.comment.comment.proto} ⇒ 本类断言
 *       {@code CommentItem} 的实际类名与所在包，确保 D7 改名没走偏。</li>
 * </ul>
 *
 * <h2>② 语义还一样吗</h2>
 * <p><b>一样</b>。本类断言的是<b>生成码的描述符契约</b>（字段号 / 类型 / presence / 枚举值 /
 * rpc 签名），与「基准放在文件里还是内联在代码里」的存放形态无关
 * ⇒ 守门点与 B 侧一一对应。
 *
 * <h2>③ B 出处</h2>
 * <pre>
 * B 侧源文件：service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/proto/CommentProtoContractTest.java
 *   protoContractMatchesGoBaseline            :92-118  → protoContractMatchesGoBaseline
 *   protoPackageIsTheSignedNormalisedValue   :120-131 → protoPackageIsTheSignedNormalisedValue
 *   noProto3OptionalField                    :141-156 → noProto3OptionalField
 *   generatedCodeHasNoPresenceApiForPlainScalar:169-184 → generatedCodeHasNoPresenceApiForPlainScalar
 *   jsonFormHasNoPresenceForPlainScalar      :205-221 → jsonFormHasNoPresenceForPlainScalar
 *   noHardcodedProtoFieldNumbersInSource     :234-256 → noHardcodedProtoFieldNumbersInSource
 * B 侧冻结基准：service/comment/comment-rpc/src/test/resources/proto-contract/comment-proto-contract.expected.txt
 *   （sha256 = 69f05c75570663413f30a05852f14674eb4a64ce3de6060c079530d42200eced）
 * C 侧签字偏离：DEV-COMMENT-PROTO-001（proto package 由 Go 的 pb 归一为 comment；B 侧裁定编号 SD-2026-10-23-04）
 * C 侧待签字偏离：DEV-COMMENT-PROTO-002（option java_package 随 D7 集中化而重定位：
 *                  com.rag.comment.comment.proto → com.rag.proto.comment）
 * </pre>
 *
 * <h2>🔴 proto {@code package} 行<b>必须排除</b>（已签字偏离 DEV-COMMENT-PROTO-001 · B 侧裁定号 SD-2026-10-23-04）</h2>
 * <p>Go 侧是 {@code pb;}，RAG 侧是 {@code comment;}
 * ⇒ 这是<b>已签字的</b>表达层归一
 * ⇒ 归一后 gRPC 方法路径由 {@code /pb.*Service/*} 变为 {@code /comment.*Service/*}（<b>wire 层可见</b>）。
 * <p>⇒ 本类照该签字件的办理：<b>基准比对排除 package 行</b>，
 * 同时<b>单独钉住</b> 本仓侧的 package 必须是签字值 {@code comment}
 * ⇒ 偏离被<b>钉成「签字偏离」</b>，而不是被<b>静默忽略</b>。
 */
class CommentProtoContractTest {

    /**
     * B 侧冻结基准的<b>逐字内联副本</b>（26 行，含 4 行 {@code #} 溯源注释）。
     *
     * <p>🔴 <b>这不是手抄的「字段表」，而是 B 侧那份冻结文件的逐字节副本</b>
     * ⇒ 由 {@code inlinedBaselineIsByteIdenticalToBSideFrozenFile} 用 sha256 钉死。
     */
    private static final String[] B_SIDE_BASELINE_LINES = {
            "# proto 契约基准 —— **由脚本从 Go 真源生成，勿手工编辑**",
            "# 生成脚本：同目录 generate.py（用法见脚本头部）",
            "# 真源：Sea-RideTheWind_golang/proto/comment.proto",
            "# 真源 sha256：dfbbcb269cfed533454a8de51094231db94ed3543c09a230db5bebffbc3754d4",
            "package pb",
            "enum|CommentState|STATE_NORMAL=0|STATE_REVIEWING=1|STATE_DELETED=2|STATE_BLOCKED=3",
            "enum|SubjectState|SUBJECT_OPEN=0|SUBJECT_CLOSED=1|SUBJECT_ONLY=2",
            "enum|ReportReason|REASON_UNKNOWN=0|REASON_SPAM_ADVERT=1|REASON_PORN_VULGAR=2|REASON_HARASSMENT_ABUSE=3|REASON_ILLEGAL_COTENT=4|REASON_FRAUD_SCAM=5|REASON_SPOILER=6|REASON_PRIVACY_LEAK=7|REASON_MEANINGLESS_SPAM=8|REASON_OTHER=99",
            "enum|ManageType|UNKNOWN=0|MANAGE_PIN=1|MANAGE_UNPIN=2|MANAGE_FEATURE=3|MANAGE_UNFEATURE=4",
            "message|CommentItem|id=1:int64|user_id=2:int64|content=3:string|root_id=4:int64|parent_id=5:int64|like_count=6:int64|dislike_count=7:int64|reply_count=8:int64|attribute=9:int64|state=10:CommentState|created_at=11:string|meta=12:string|children=13:repeated CommentItem",
            "message|SubjectInfo|target_type=1:string|target_id=2:string|total_count=3:int64|root_count=4:int64|state=5:SubjectState|attribute=6:int64|owner_id=7:int64",
            "message|CreateCommentReq|user_id=1:int64|target_type=2:string|target_id=3:string|root_id=4:int64|parent_id=5:int64|content=6:string|meta=7:string",
            "message|CreateCommentResp|id=1:int64|created_at=2:string|subject_count=3:int64",
            "message|GetCommentReq|target_type=1:string|target_id=2:string|sort_type=3:int64|root_id=4:int64|page=5:int64|page_size=6:int64",
            "message|GetCommentResp|comment=1:repeated CommentItem|subject=2:SubjectInfo",
            "message|LikeCommentReq|user_id=1:int64|target_type=2:string|target_id=3:string|comment_id=4:int64|action_type=5:int32",
            "message|LikeCommentResp|success=1:bool|like_count=2:int64",
            "message|DeleteCommentReq|user_id=1:int64|comment_id=2:int64|target_type=3:string|target_id=4:string",
            "message|DeleteCommentResp|success=1:bool|subject_total_count=2:int64",
            "message|ManageCommentReq|user_id=1:int64|comment_id=2:int64|target_type=3:string|target_id=4:string|action_type=5:ManageType",
            "message|ManageCommentResp|success=1:bool",
            "message|ReportCommentReq|user_id=1:int64|comment_id=2:int64|target_type=3:string|target_id=4:string|reason=5:ReportReason|detail=6:string",
            "message|ReportCommentResp|success=1:bool",
            "message|UpdateSubjectStateReq|user_id=1:int64|target_type=2:string|target_id=3:string|state=4:SubjectState",
            "message|UpdateSubjectStateResp|success=1:bool",
            "service|CommentService|CreateComment(CreateCommentReq)->CreateCommentResp|GetComment(GetCommentReq)->GetCommentResp|DeleteComment(DeleteCommentReq)->DeleteCommentResp|LikeComment(LikeCommentReq)->LikeCommentResp|ReportComment(ReportCommentReq)->ReportCommentResp|ManageComment(ManageCommentReq)->ManageCommentResp|UpdateSubjectState(UpdateSubjectStateReq)->UpdateSubjectStateResp",
    };

    /** B 侧冻结基准文件 {@code comment-proto-contract.expected.txt} 的 sha256。 */
    private static final String B_SIDE_BASELINE_SHA256 =
            "69f05c75570663413f30a05852f14674eb4a64ce3de6060c079530d42200eced";

    /** Go 真源 proto 的 {@code package}（基准文件首行 {@code package} 之后那行）。 */
    private static final String GO_PACKAGE = "pb";

    /** 本仓侧的 proto {@code package}（已签字归一值）。 */
    private static final String RAG_PACKAGE = "comment";

    /** 本域 proto package 是否两侧不一致（不一致 ⇒ 排除 package 行 + 单独钉住签字值）。 */
    private static final boolean PACKAGE_DIVERGES = true;

    @Test
    @DisplayName("🔴 内联的基准与 B 侧那份冻结文件逐字节相同（sha256 锚定，防手抄抄错）")
    void inlinedBaselineIsByteIdenticalToBSideFrozenFile() throws NoSuchAlgorithmException {
        // ⚠️ 这条是「基准本身可信」的守门：内联文本若与 B 侧冻结文件差一个字节 ⇒ 这里红。
        String text = String.join("\n", B_SIDE_BASELINE_LINES) + "\n";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        assertThat(HexFormat.of().formatHex(digest))
                .as("内联基准与 B 侧冻结文件 comment-proto-contract.expected.txt 不再逐字节相同 "
                        + "⇒ 要么是内联抄错了，要么是 B 侧冻结文件变了（⇒ 必须重新评审 proto 契约）")
                .isEqualTo(B_SIDE_BASELINE_SHA256);
    }

    @Test
    @DisplayName("🔴 comment proto 契约（消息/字段号/类型/optional/repeated/map/枚举/rpc）与 B 侧基准逐条一致")
    void protoContractMatchesGoBaseline() {
        List<String> expected = readBaseline();
        List<String> actual = describe();

        // 🔴 结构性 sanity（**不是行数启发式**）：本域 proto 大小差异极大
        // （task 只有 5 行 ⇒ 「> 5 行」这种阈值会把**正确**的基准判成异常），
        // ⇒ 改为钉「基准必须含 package 行 + 至少一条 message + 至少一条 service」。
        assertThat(expected).hasSizeGreaterThanOrEqualTo(4);
        assertThat(expected).anyMatch(l -> l.startsWith("package "));
        assertThat(expected).anyMatch(l -> l.startsWith("message|"));
        assertThat(expected).anyMatch(l -> l.startsWith("service|"));

        if (PACKAGE_DIVERGES) {
            // 🔴 排除 package 行（已签字偏离），其余**逐行**相等。
            assertThat(stripPackage(actual))
                    .as("comment proto 契约与 B 侧基准不一致 ⇒ 字段号 / 类型 / presence / 枚举值 / rpc 签名发生了漂移")
                    .containsExactlyElementsOf(stripPackage(expected));
        } else {
            assertThat(actual)
                    .as("comment proto 契约与 B 侧基准不一致 ⇒ 字段号 / 类型 / presence / 枚举值 / rpc 签名发生了漂移")
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("🔴 comment proto package 必须是 {@code comment}（Go 侧 pb ⇒ 已签字偏离，单独钉住）")
    void protoPackageIsTheSignedNormalisedValue() {
        List<String> expected = readBaseline();
        assertThat(packageOf(expected))
                .as("🔴 基准文件里的 Go 真源 package 变了 ⇒ 要重新评审 comment 域 proto package 的签字偏离")
                .isEqualTo(GO_PACKAGE);

        Descriptors.FileDescriptor file = CommentItem.getDescriptor().getFile();
        assertThat(file.getPackage())
                .as("🔴 本仓侧 proto package 不是签字值 " + RAG_PACKAGE
                        + " ⇒ 要么是有人「顺手改回 Go 的 " + GO_PACKAGE + "」（违反签字偏离），要么是改成了别的值")
                .isEqualTo(RAG_PACKAGE);
    }

    /**
     * 🔴 【PROTO 型 ①】<b>本 proto 没有 proto3 {@code optional} 字段</b> ⇒ presence 语义与 Go 一致。
     *
     * <p><b>为什么必须钉死</b>：Go 真源 {@code comment.proto} 里<b>没有</b> {@code optional} 字段；
     * 本仓侧若<b>多出</b> {@code optional} ⇒ 该字段在 wire 上从「无 presence」变成「有 presence」
     * ⇒ 与 Go 侧<b>不同形</b>（Go 的 {@code string} 与本仓的 {@code optional string} 不能互换）。
     * <p>⇒ 本条把「没有 optional」这条事实钉成断言，<b>防后人顺手加 optional</b>。
     */
    @Test
    @DisplayName("🔴 comment.proto 无 proto3 optional 字段 ⇒ presence 语义与 Go 一致（显式钉死，防后人加 optional）")
    void noProto3OptionalField() {
        Descriptors.FileDescriptor file = CommentItem.getDescriptor().getFile();
        List<String> optionalFields = new ArrayList<>();
        for (Descriptors.Descriptor msg : file.getMessageTypes()) {
            for (Descriptors.FieldDescriptor f : msg.getFields()) {
                if (f.toProto().hasProto3Optional()) {
                    optionalFields.add(msg.getName() + "." + f.getName());
                }
            }
        }
        assertThat(optionalFields)
                .as("🔴 Go 真源 proto/comment.proto 里**没有** proto3 optional 字段；"
                        + "本仓侧若多出 optional ⇒ presence 语义与 Go 不同（wire 上「有值/无值」不可区分）")
                .isEmpty();
    }

    /**
     * 🔴 【PROTO 型 ①·生成码侧】<b>生成码确实<b>没有</b>为 content 生成 {@code hasXxx()}</b>
     * ⇒ presence <b>没有被凭空加出来</b>。
     *
     * <p><b>为什么用反射钉</b>：描述符上的 {@code hasProto3Optional()} 只说明「{@code .proto} 里写了 optional」，
     * 而<b>生成码</b>才是真正参与 wire 编码的那份 ⇒ 必须看生成码<b>有没有</b> {@code hasXxx()}。
     *
     * <p>实测依据（protobuf-java 3.25.5）：proto3 <b>普通标量</b> ⇒ 生成码<b>只有</b> {@code getXxx()} /
     * {@code setXxx()}，<b>没有</b> {@code hasXxx()}；
     * 只有 {@code optional} 标量与 message 类型才有 {@code hasXxx()}。
     */
    @Test
    @DisplayName("🔴 comment 生成码不得为普通标量 content 生成 hasXxx()（无 presence ⇒ 与 Go 同形）")
    void generatedCodeHasNoPresenceApiForPlainScalar() {
        assertThatThrownBy(() -> CommentItem.class.getMethod("hasContent"))
                .as("🔴 CommentItem.content 是 proto3 普通 string（无 optional）"
                        + " ⇒ 生成码**不该**有 hasContent()；有 ⇒ presence 语义与 Go 不同")
                .isInstanceOf(NoSuchMethodException.class);

        Method getter;
        try {
            getter = CommentItem.class.getMethod("getContent");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("生成码缺少 getContent() ⇒ 生成码与基准不是同一份契约", e);
        }
        assertThat(getter).isNotNull();
    }

    /**
     * 🔴 【PROTO 型 ③】<b>JSON 形态：普通标量<b>没有</b> presence ⇒ 设成默认值也<b>不出现</b>。</b>
     *
     * <p>用 protobuf 官方 {@code JsonFormat.printer().omittingInsignificantWhitespace()} 序列化<b>生成码</b>（不是手搓 JSON）⇒
     * 断言的是「<b>presence 在 JSON 上的可观察差异</b>」：
     * <p>⚠️ <b>必须用 omittingInsignificantWhitespace</b>：默认 printer 输出<b>带缩进换行</b>的
     * pretty JSON（空 message 也不是紧凑的空对象）⇒ 断言无法逐字比对。
     * <ul>
     *   <li>普通标量 {@code content}：设成默认值（""）⇒ JSON 里<b>没有</b>这个 key
     *       ⇒ 与 Go 的「非指针 + omitempty」同形；</li>
     *   <li>普通标量设成非默认值 ⇒ JSON 里<b>有</b>这个 key。</li>
     * </ul>
     *
     * <p>⚠️ 这正是 Go 的 {@code encoding/json} 对 {@code string} vs {@code *string} 的输出差异<b>同源</b>之处：
     * Go 侧 {@code *string}（指针）⇒ 即使指向 "" 也<b>会</b>输出（除非带 omitempty 且为 nil）；
     * Go 侧 {@code string} + omitempty ⇒ "" <b>不</b>输出。
     * <p>⇒ proto3 {@code optional} ⇒ 等价于 Go 的<b>指针</b>；proto3 普通标量 ⇒ 等价于 Go 的<b>值 + omitempty</b>。
     */
    @Test
    @DisplayName("🔴 comment proto JSON 形态：普通标量 content 无 presence（设默认值也不出现）")
    void jsonFormHasNoPresenceForPlainScalar() throws Exception {
        // ① 设成默认值 ⇒ key 不出现（无 presence）
        String emptyJson = JsonFormat.printer().omittingInsignificantWhitespace().print(
                CommentItem.newBuilder().setContent("").build());
        assertThat(emptyJson)
                .as("🔴 CommentItem 的 content 是 proto3 普通 string ⇒ 设成默认值后 JSON 里**不该**出现该 key"
                        + "（有 ⇒ 出现了 Go 侧不存在的 presence 语义）")
                .isEqualTo("{}");

        // ② 设成非默认值 ⇒ key 出现
        String filledJson = JsonFormat.printer().omittingInsignificantWhitespace().print(
                CommentItem.newBuilder().setContent("hi").build());
        assertThat(filledJson)
                .as("🔴 CommentItem 的 content 设成非默认值后 JSON 里应出现 \"content\":\"hi\"")
                .contains("\"content\":\"hi\"");
    }

    /**
     * 🔴 【PROTO 型 ④】<b>字段号无硬编码覆盖</b> —— 源码层扫描。
     *
     * <p>为什么必须查：protobuf 生成码允许用 {@code setField(FieldDescriptor, value)} /
     * {@code writeTo(CodedOutputStream)} 这类<b>按字段号</b>的低层 API 绕过生成的方法
     * ⇒ 一旦有人在业务代码里硬编码字段号，{@code .proto} 与生成码的契约比对<b>照样绿</b>
     * （它比的是<b>声明</b>），而 wire 已经变了。
     *
     * <p>⚠️ <b>扫描根必须真的找到</b>：找不到源码树时<b>直接失败</b>，不静默跳过
     * ⇒ 否则本条会退化成「永远绿」的假门（AGENTS.md §3.3.3）。
     */
    @Test
    @DisplayName("🔴 comment 域源码不得硬编码 proto 字段号（setField/FIELD_NUMBER/CodedOutputStream 等）")
    void noHardcodedProtoFieldNumbersInSource() throws IOException {
        Path scanRoot = locateDomainSourceRoot();
        assertThat(scanRoot)
                .as("🔴 找不到 comment 域源码目录 ⇒ 本条会退化成「永远绿」的假门 ⇒ 必须失败而不是跳过")
                .isNotNull();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(scanRoot)) {
            for (Path file : files.filter(CommentProtoContractTest::isProductionSource).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (isProtobufLowLevelUsage(line)) {
                        violations.add(scanRoot.relativize(file) + ":" + (i + 1) + "  " + line.trim());
                    }
                }
            }
        }
        assertThat(violations)
                .as("🔴 comment 域源码里出现了按字段号的 protobuf 低层 API ⇒ "
                        + "契约比对（比的是声明）抓不到它，但 wire 已经变了")
                .isEmpty();
    }

    /**
     * 🔴 【C 侧新增】<b>pb 类必须落在 D7 归一后的包 {@code com.rag.proto.comment}</b>。
     *
     * <p>⚠️ B 侧的 pb 在 {@code com.rag.comment.comment.proto}（域内 {@code comment-proto} 模块），
     * C 侧按 D7 集中到根级 {@code proto} 模块 ⇒ 包名变成 {@code com.rag.proto.comment}
     * ⇒ 这一格是<b>表达层</b>差异，但<b>改错包名会让所有跨模块 import 断掉</b>
     * ⇒ 钉一条断言防走偏。
     */
    @Test
    @DisplayName("🔴 pb 类必须落在 com.rag.proto.comment（D7 集中化后的包名）")
    void generatedPbLivesInTheD7CentralisedPackage() {
        assertThat(CommentItem.class.getName())
                .as("🔴 pb 类落点变了（D7：根级 proto 模块 · com.rag.proto.comment）")
                .isEqualTo("com.rag.proto.comment.CommentItem");
        assertThat(CommentItem.class.getPackageName())
                .isEqualTo("com.rag.proto.comment");
    }

    /**
     * 只认<b>生产源码</b>（{@code src/main/java}）。
     *
     * <p>🔴 <b>为什么必须限定生产源码</b>：本判据自己的源码里就写着
     * {@code FIELD_NUMBER} / {@code CodedOutputStream} 这些模式串（写在 javadoc 与判定函数里）
     * ⇒ 若扫描范围包含 {@code src/test/java}，扫描器会<b>命中它自己</b> ⇒ 每次跑都红 ⇒ <b>假红</b>。
     * <p>⇒ 双重收敛：① 只扫 {@code src/main/java}（判据在 {@code src/test/java} ⇒ 天然不重叠）；
     * ② 目标目录也排除了 {@code target/}（生成码里有大量 {@code FIELD_NUMBER} 常量，
     * 那是 protoc 产物、不是人写的硬编码）。
     */
    private static boolean isProductionSource(Path path) {
        String p = path.toString();
        return p.endsWith(".java")
                && p.contains(File.separator + "src" + File.separator + "main" + File.separator + "java" + File.separator);
    }

    /**
     * 判「protobuf 的按字段号低层用法」。
     *
     * <p>⚠️ <b>刻意排除 {@code ReflectionTestUtils.setField}</b>：那是 Spring 的
     * 「反射设 bean 字段」，与 protobuf 字段号<b>毫无关系</b> ⇒ 误判会把合规代码判成红。
     */
    private static boolean isProtobufLowLevelUsage(String line) {
        String trimmed = line.trim();
        if (trimmed.contains("ReflectionTestUtils.setField") || trimmed.contains("ReflectionTestUtils.getField")) {
            return false;
        }
        return trimmed.contains("FIELD_NUMBER")
                || trimmed.contains("setField(FieldDescriptor")
                || trimmed.contains("getField(FieldDescriptor")
                || trimmed.contains("CodedOutputStream")
                || trimmed.contains("UnknownFieldSet")
                || trimmed.contains("getUnknownFields()")
                || trimmed.contains(".writeTo(")
                || trimmed.contains("mergeFrom(");
    }

    /**
     * 定位本域的源码根 —— 从当前工作目录向上找「含 {@code service/comment} 的目录」。
     *
     * <p>surefire 的 {@code user.dir} = 当前模块目录 ⇒ 向上最多 5 层即可命中仓库根。
     */
    private static Path locateDomainSourceRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path candidate = cursor.resolve("service/comment");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    // ==================================================================================
    // 基准 ↔ 生成码描述符 的同一套渲染
    // ==================================================================================

    /**
     * 把生成码的描述符渲染成与基准<b>同一套格式</b>的文本。
     *
     * <p>⚠️ 取的是 protobuf 的 {@link Descriptors.FileDescriptor}（<b>真正参与 wire 编码</b>的那份），
     * 不是 grpc-java 的 {@code io.grpc.ServiceDescriptor}（后者<b>不暴露</b> message / enum / 字段号）。
     */
    private static List<String> describe() {
        Descriptors.FileDescriptor file = CommentItem.getDescriptor().getFile();
        List<String> lines = new ArrayList<>();
        lines.add("package " + file.getPackage());

        for (Descriptors.EnumDescriptor en : file.getEnumTypes()) {
            List<String> values = new ArrayList<>();
            for (Descriptors.EnumValueDescriptor v : en.getValues()) {
                values.add(v.getName() + "=" + v.getNumber());
            }
            lines.add("enum|" + en.getName() + "|" + String.join("|", values));
        }

        for (Descriptors.Descriptor msg : file.getMessageTypes()) {
            List<String> fields = new ArrayList<>();
            for (Descriptors.FieldDescriptor f : msg.getFields()) {
                fields.add(f.getName() + "=" + f.getNumber() + ":" + typeToken(f));
            }
            lines.add("message|" + msg.getName() + "|" + String.join("|", fields));
        }

        for (Descriptors.ServiceDescriptor svc : file.getServices()) {
            List<String> methods = new ArrayList<>();
            for (Descriptors.MethodDescriptor m : svc.getMethods()) {
                methods.add(m.getName()
                        + "(" + m.getInputType().getName() + ")"
                        + "->" + m.getOutputType().getName());
            }
            lines.add("service|" + svc.getName() + "|" + String.join("|", methods));
        }
        return lines;
    }

    /**
     * 字段的类型标记 —— 与基准文件的归一规则逐条对应。
     *
     * <p>⚠️ <b>map 要先判</b>：protobuf 里 map 字段的label 也是 {@code LABEL_REPEATED}
     * ⇒ 若先判 repeated，map 会被渲染成 {@code repeated FooEntry} ⇒ 与基准对不上。
     */
    private static String typeToken(Descriptors.FieldDescriptor f) {
        if (f.isMapField()) {
            Descriptors.Descriptor entry = f.getMessageType();
            Descriptors.FieldDescriptor key = entry.findFieldByName("key");
            Descriptors.FieldDescriptor value = entry.findFieldByName("value");
            assertThat(key).as("map 字段的 entry 没有 key").isNotNull();
            assertThat(value).as("map 字段的 entry 没有 value").isNotNull();
            return "map<" + scalarOrNamed(key) + ", " + scalarOrNamed(value) + ">";
        }
        String base = scalarOrNamed(f);
        if (f.toProto().hasProto3Optional()) {
            // proto3 显式 presence（`optional string x = 1;`）—— 与普通 `string` 的 wire 语义不同
            return "optional " + base;
        }
        if (f.toProto().getLabel() == DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED) {
            return "repeated " + base;
        }
        return base;
    }

    /** 标量取小写类型名；message / enum 取<b>类型名</b>（与 .proto 文本里的写法一致）。 */
    private static String scalarOrNamed(Descriptors.FieldDescriptor fd) {
        switch (fd.getJavaType()) {
            case MESSAGE:
                return fd.getMessageType().getName();
            case ENUM:
                return fd.getEnumType().getName();
            default:
                return fd.getType().name().toLowerCase(Locale.ROOT).replace("type_", "");
        }
    }

    /** 读基准；**注释行（{@code #} 开头）不参与比对**（那是溯源信息，不是契约）。 */
    private static List<String> readBaseline() {
        List<String> lines = new ArrayList<>();
        for (String line : B_SIDE_BASELINE_LINES) {
            if (!line.isBlank() && !line.startsWith("#")) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** 🔴 排除 {@code package} 行 —— 仅在 package 两侧不一致（已签字偏离）时启用。 */
    private static List<String> stripPackage(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith("package ")) {
                out.add(line);
            }
        }
        return out;
    }

    private static String packageOf(List<String> lines) {
        for (String line : lines) {
            if (line.startsWith("package ")) {
                return line.substring("package ".length()).trim();
            }
        }
        return null;
    }
}
