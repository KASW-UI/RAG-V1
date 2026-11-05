package com.rag.comment.proto;

import com.google.protobuf.Message;
import com.rag.proto.comment.CommentItem;
import com.rag.proto.comment.CommentState;
import com.rag.proto.comment.CreateCommentReq;
import com.rag.proto.comment.GetCommentResp;
import com.rag.proto.comment.LikeCommentReq;
import com.rag.proto.comment.LikeCommentResp;
import com.rag.proto.comment.ReportCommentReq;
import com.rag.proto.comment.ReportReason;
import com.rag.proto.comment.SubjectInfo;
import com.rag.proto.comment.SubjectState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴🔴 comment proto 的 <b>protobuf 消息体 wire 字节</b>双侧对拍。
 *
 * <h2>它补的是哪个缺口</h2>
 * <p>AGENTS.md §3.4 只保证「{@code .proto} <b>文件本身</b>两侧一致」
 * ⇒ <b>推不出</b>「两侧<b>生成码</b>产出的 wire 字节一致」——
 * 字段号、零值落不落 wire、repeated 的编码方式，<b>只有序列化出来才看得见</b>。
 * <p>⇒ 本类用<b>同一组消息</b>在两侧各序列化一次，比对<b>十六进制字节</b>。
 * <p>⚠️ 两侧的运行时<b>不同</b>：B 侧探针用的是 {@code google.golang.org/protobuf}
 * （protoc-gen-go 的运行时），C 侧是 {@code protobuf-java}
 * （protoc 的 Java 运行时）⇒ 这不是「同一份实现自证」。
 *
 * <h2>期望值的来源</h2>
 * <p>🔴 <b>全部期望值来自 B 侧的真源实测</b>：B 侧 {@code tools/proto-wire-probe/comment/}
 * 用<b>从 Go 真源逐字节复制</b>的 {@code service/comment/rpc/pb/comment.pb.go}
 * （Go 侧<b>真正在用</b>的生成码）算出字节，落在 {@code expected.txt}。
 * ⇒ <b>Go 真源换了生成码 ⇒ 探针的 diff 失败</b> ⇒ 提醒更新本测试。
 *
 * <h2>① 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>🔴 期望值内联 + sha256 锚定</b>：B 侧从仓库里的
 *       {@code tools/proto-wire-probe/comment/expected.txt}（<b>冻结文件</b>）读期望值。
 *       本次 C 侧迁移的<b>写权只限 5 个 java 文件</b>，且本仓
 *       {@code tools/} 下<b>没有</b> {@code proto-wire-probe/}（该目录只在 B 侧）
 *       ⇒ 期望值被<b>逐字内联</b>成本类常量，
 *       并<b>额外加一条 sha256 断言</b>把内联文本与 B 侧那份<b>冻结文件</b>钉成逐字节相同
 *       ⇒ 「手抄会不会抄错」这个问题被<b>可执行地</b>关掉（抄错一个字节 ⇒ 红）。</li>
 *   <li><b>🔴 额外钉住「用例集与冻结文件一致」</b>：B 侧是读文件比用例集；
 *       C 侧是<b>内联常量</b> ⇒ 必须自己把用例名列表与内联行一一对上，
 *       否则「少一条用例」会静默通过 ⇒ 本类用 sha256 + 行数 + 用例集<b>三重</b>钉住。</li>
 * </ul>
 *
 * <h2>② 语义还一样吗</h2>
 * <p><b>一样</b>。本类断言的是「<b>同一组消息</b>在两侧序列化出的<b>十六进制字节</b>相同」
 * ⇒ 与「期望值存在文件里还是存在代码里」无关。
 *
 * <h2>③ B 出处</h2>
 * <pre>
 * B 侧源文件：service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/proto/CommentProtoWireParityTest.java
 *   commentItemWireMatchesGo                :134-163 → commentItemWireMatchesGo
 *   enumZeroValueIsNotOnTheWire             :165-180 → enumZeroValueIsNotOnTheWire
 *   repeatedAndNestedWireMatchesGo          :182-212 → repeatedAndNestedWireMatchesGo
 *   actionTypeAndBoolWireMatchesGo          :214-237 → actionTypeAndBoolWireMatchesGo
 *   enum99AndSubjectWireMatchesGo           :239-262 → enum99AndSubjectWireMatchesGo
 *   utf8StringLengthIsInBytes               :264-274 → utf8StringLengthIsInBytes
 *   expectedFileAndThisTestCoverTheSameCases:276-301 → expectedFileAndThisTestCoverTheSameCases
 *   expectedFileLineCountIsStable           :303-315 → expectedFileLineCountIsStable
 * B 侧冻结期望值：tools/proto-wire-probe/comment/expected.txt
 *   （sha256 = 52a30a6771fccbbe667c381e0058773491c06f8bdad9e50a65fb9072e3ed5ae3）
 * </pre>
 *
 * <h2>🔴 本探针<b>不</b>覆盖的一格：{@code owner_id}</h2>
 * <p>Go 真源的 {@code proto/comment.proto:105} 有 {@code int64 owner_id = 7}，
 * 但 Go 侧<b>真正在用的生成码</b> {@code service/comment/rpc/pb/comment.pb.go}
 * 里<b>根本没有</b> {@code owner_id} / {@code OwnerId}（连 rawDesc 都没有
 * ⇒ 该生成码比 {@code .proto} 旧）。
 * <p>⇒ 两侧的 proto 定义<b>本身就不等</b> ⇒ 这正是「{@code .proto} 一致 ⇒ 生成码一致」
 * 这条假设的<b>反例</b>（AGENTS.md §3.4 效力边界第 1 条）。
 * <p>⇒ <b>两侧都不落这个字段</b>：Go 侧无字段可设；C 侧虽生成出了 {@code ownerId}，
 * 但 rpc 层刻意不设（{@code CommentDomainService} 组装 {@code SubjectInfo} 时逐字段赋值、
 * 无 {@code owner_id}）⇒ ⇒ 字节一致。
 * <p>⇒ <b>因此该字段不进本测试的用例集</b>（Go 侧无法对称设置 ⇒ 比不了），
 * 且<b>不在</b>本测试的「用例集一致」断言里。
 */
class CommentProtoWireParityTest {

    /**
     * B 侧冻结期望值 {@code tools/proto-wire-probe/comment/expected.txt} 的<b>逐字内联副本</b>（16 行）。
     *
     * <p>🔴 由 {@code inlinedExpectationIsByteIdenticalToBSideFrozenFile} 用 sha256 钉死。
     */
    private static final String[] B_SIDE_EXPECTED_LINES = {
            "WIRE|item_full|08818080808080801010e9071a06e4bda0e5a5bd300750025a13323032362d30312d30322030333a30343a3035",
            "WIRE|item_zero|",
            "WIRE|item_id_negative|08ffffffffffffffffff01",
            "WIRE|item_id_max|08ffffffffffffffff7f",
            "WIRE|item_state_normal|",
            "WIRE|item_state_deleted|5002",
            "WIRE|item_state_blocked|5003",
            "WIRE|item_children|08016a020802",
            "WIRE|resp_two|0a0a080110e9071a03e4b8800a0a080210e9071a03e4ba8c12170a0761727469636c651204323030311802200128023003",
            "WIRE|resp_empty|",
            "WIRE|like_req_action_negative|08e90720d10f28ffffffffffffffffff01",
            "WIRE|like_resp_true|0801",
            "WIRE|like_resp_false|",
            "WIRE|report_req_reason_other|08e90710d10f28633206e8afa6e68385",
            "WIRE|subject_full|0a0761727469636c65120432303031180c20052801",
            "WIRE|create_req_utf8|08e907320ce4b8ade69687e8af84e8aeba",
    };

    /** B 侧冻结期望值文件的 sha256。 */
    private static final String B_SIDE_EXPECTED_SHA256 =
            "52a30a6771fccbbe667c381e0058773491c06f8bdad9e50a65fb9072e3ed5ae3";

    // ══════════════════ 期望值（Go 真源实测） ══════════════════

    private static Map<String, String> expected() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : readExpected()) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", 3);
            map.put(parts[0] + "|" + parts[1], parts[2]);
        }
        return map;
    }

    private static String expectedHex(String name) {
        String hex = expected().get("WIRE|" + name);
        if (hex == null) {
            throw new IllegalStateException(
                    "期望值里没有 WIRE|" + name + "（探针与本测试的用例集不一致）");
        }
        return hex;
    }

    private static List<String> readExpected() {
        List<String> lines = new ArrayList<>();
        for (String line : B_SIDE_EXPECTED_LINES) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    // ══════════════════ 产出 C 侧的字节 ══════════════════

    private static String hex(Message message) {
        return HexFormat.of().formatHex(message.toByteArray());
    }

    // ══════════════════ 用例 ══════════════════

    @Test
    @DisplayName("🔴 内联的期望值与 B 侧那份冻结文件逐字节相同（sha256 锚定，防手抄抄错）")
    void inlinedExpectationIsByteIdenticalToBSideFrozenFile() throws NoSuchAlgorithmException {
        // ⚠️ 这条是「期望值本身可信」的守门：内联文本若与 B 侧冻结文件差一个字节 ⇒ 这里红。
        String text = String.join("\n", B_SIDE_EXPECTED_LINES) + "\n";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        assertThat(HexFormat.of().formatHex(digest))
                .as("内联期望值与 B 侧冻结文件 tools/proto-wire-probe/comment/expected.txt 不再逐字节相同 "
                        + "⇒ 要么是内联抄错了，要么是 B 侧探针重跑过（⇒ 必须重新评审 proto wire 契约）")
                .isEqualTo(B_SIDE_EXPECTED_SHA256);
    }

    @Test
    @DisplayName("🔴 CommentItem 的 wire 字节与 Go 真源逐字节一致")
    void commentItemWireMatchesGo() {
        // ① 全字段：int64 是 varint；string 是 length-delimited；**枚举非零**才落 wire。
        CommentItem full = CommentItem.newBuilder()
                .setId(9007199254740993L)
                .setUserId(1001L)
                .setContent("你好")
                .setLikeCount(7L)
                .setState(CommentState.STATE_DELETED)
                .setCreatedAt("2026-01-02 03:04:05")
                .build();
        assertThat(hex(full)).isEqualTo(expectedHex("item_full"));

        // ② 全零值：proto3 的标量零值**不落 wire** ⇒ 必须是**空字节**。
        //    ⚠️ 这条能抓住「某一侧给零值写了 presence」。
        assertThat(hex(CommentItem.getDefaultInstance())).isEqualTo(expectedHex("item_zero"));
        assertThat(hex(CommentItem.getDefaultInstance()))
                .as("零值消息在 wire 上必须是空字节（proto3 的隐式 presence）")
                .isEmpty();

        // ③ 🔴 负 int64 ⇒ wire 上是 **10 字节 varint**（符号扩展成 64 位补码）。
        //    ⚠️ 这条能抓住「某一侧把 int64 编成 sint32/zigzag」（那是另一种编码 ⇒ 字节不同）。
        assertThat(hex(CommentItem.newBuilder().setId(-1L).build()))
                .isEqualTo(expectedHex("item_id_negative"));

        // ④ int64 上界 9223372036854775807 ⇒ **10 字节 varint**。
        //    ⚠️ 这条能抓住「某一侧用 int32 装它 ⇒ 截断 ⇒ 字节不同」。
        assertThat(hex(CommentItem.newBuilder().setId(Long.MAX_VALUE).build()))
                .isEqualTo(expectedHex("item_id_max"));
    }

    @Test
    @DisplayName("🔴 枚举的零值在 wire 上完全不落（STATE_NORMAL ⇒ 空字节）")
    void enumZeroValueIsNotOnTheWire() {
        // ⑤ 枚举零值（STATE_NORMAL = 0）⇒ **不落 wire** ⇒ 与「全零值」同形（空字节）。
        //    ⚠️ 这条能抓住「某一侧把枚举编成带 presence 的 optional」。
        CommentItem normal = CommentItem.newBuilder().setState(CommentState.STATE_NORMAL).build();
        assertThat(hex(normal)).isEqualTo(expectedHex("item_state_normal"));
        assertThat(hex(normal))
                .as("STATE_NORMAL 在 wire 上必须不落（proto3 的隐式 presence）")
                .isEmpty();

        // ⑥⑦ 枚举非零 ⇒ 落 wire（tag 10 ⇒ 0x50，值分别是 2 / 3）。
        assertThat(hex(CommentItem.newBuilder().setState(CommentState.STATE_DELETED).build()))
                .isEqualTo(expectedHex("item_state_deleted"));
        assertThat(hex(CommentItem.newBuilder().setState(CommentState.STATE_BLOCKED).build()))
                .isEqualTo(expectedHex("item_state_blocked"));
    }

    @Test
    @DisplayName("🔴 repeated 消息字段与嵌套消息的 wire 字节与 Go 真源逐字节一致")
    void repeatedAndNestedWireMatchesGo() {
        // ⑧ repeated 消息：一条子评论 ⇒ 「tag 13 的 length-delimited」。
        //    ⚠️ 这条能抓住「某一侧把 repeated 编成 map 或 JSON 数组」。
        CommentItem withChild = CommentItem.newBuilder()
                .setId(1L)
                .addChildren(CommentItem.newBuilder().setId(2L).build())
                .build();
        assertThat(hex(withChild)).isEqualTo(expectedHex("item_children"));

        // ⑨ 响应：两条评论 ＋ 一个嵌套的 SubjectInfo ⇒ repeated + 嵌套消息同时验。
        GetCommentResp resp = GetCommentResp.newBuilder()
                .addComment(CommentItem.newBuilder().setId(1L).setUserId(1001L).setContent("一").build())
                .addComment(CommentItem.newBuilder().setId(2L).setUserId(1001L).setContent("二").build())
                .setSubject(SubjectInfo.newBuilder()
                        .setTargetType("article")
                        .setTargetId("2001")
                        .setTotalCount(2L)
                        .setRootCount(1L)
                        .setState(SubjectState.SUBJECT_ONLY)
                        .setAttribute(3L)
                        .build())
                .build();
        assertThat(hex(resp)).isEqualTo(expectedHex("resp_two"));

        // ⑩ 空响应：repeated 为空 ＋ 嵌套消息为 nil ⇒ **空字节**（tag 都不落）。
        assertThat(hex(GetCommentResp.getDefaultInstance())).isEqualTo(expectedHex("resp_empty"));
        assertThat(hex(GetCommentResp.getDefaultInstance()))
                .as("空 repeated + 无嵌套消息在 wire 上必须是空字节")
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 int32 action_type 与 bool success 的 wire 字节与 Go 真源逐字节一致")
    void actionTypeAndBoolWireMatchesGo() {
        // ⑪ 负 int32（action_type）⇒ 同样 **10 字节 varint**（int32 也符号扩展成 64 位）。
        //    ⚠️ 这条能抓住「某一侧把 int32 编成 sint32」（zigzag ⇒ 字节完全不同）。
        assertThat(hex(LikeCommentReq.newBuilder()
                .setUserId(1001L)
                .setCommentId(2001L)
                .setActionType(-1)
                .build()))
                .isEqualTo(expectedHex("like_req_action_negative"));

        // ⑫ true ⇒ varint 1。
        assertThat(hex(LikeCommentResp.newBuilder().setSuccess(true).build()))
                .isEqualTo(expectedHex("like_resp_true"));

        // ⑬ 🔴 false ⇒ proto3 零值**不落 wire** ⇒ 整条消息是**空字节**。
        //    ⚠️ api 层给的 JSON 是 {"success": false} ⇒ 两层表达不同 ⇒ 只有字节级对拍钉得住。
        LikeCommentResp failed = LikeCommentResp.newBuilder().setSuccess(false).build();
        assertThat(hex(failed)).isEqualTo(expectedHex("like_resp_false"));
        assertThat(hex(failed))
                .as("success=false 在 wire 上必须是空字节（proto3 的隐式 presence）—— "
                        + "api 层的 JSON 会写 \"success\": false，两层表达本就不同")
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 枚举 99（REASON_OTHER）与嵌套 SubjectInfo 的 wire 字节与 Go 真源逐字节一致")
    void enum99AndSubjectWireMatchesGo() {
        // ⑭ 枚举 99（REASON_OTHER）⇒ **单字节 varint**（99 < 128 ⇒ 0x63）。
        //    ⚠️ 这条能抓住「某一侧把枚举编成 string」或「用了 sint32」。
        assertThat(hex(ReportCommentReq.newBuilder()
                .setUserId(1001L)
                .setCommentId(2001L)
                .setReason(ReportReason.REASON_OTHER)
                .setDetail("详情")
                .build()))
                .isEqualTo(expectedHex("report_req_reason_other"));

        // ⑮ 嵌套消息全字段（SubjectInfo 的**两侧共有**那 6 个字段；owner_id 两侧都不发）。
        assertThat(hex(SubjectInfo.newBuilder()
                .setTargetType("article")
                .setTargetId("2001")
                .setTotalCount(12L)
                .setRootCount(5L)
                .setState(SubjectState.SUBJECT_CLOSED)
                .setAttribute(0L)
                .build()))
                .isEqualTo(expectedHex("subject_full"));
    }

    @Test
    @DisplayName("🔴 中文 UTF-8 的 string 按字节长度算 length-delimited")
    void utf8StringLengthIsInBytes() {
        // ⑯ 中文 UTF-8：length-delimited 的长度是**字节数**（不是字符数）。
        //    ⚠️ 这条能抓住「某一侧按 rune 数算长度」。
        assertThat(hex(CreateCommentReq.newBuilder()
                .setUserId(1001L)
                .setContent("中文评论")
                .build()))
                .isEqualTo(expectedHex("create_req_utf8"));
    }

    @Test
    @DisplayName("🔴 探针与本测试的用例集必须完全一致（防止有人只改了一边）")
    void expectedFileAndThisTestCoverTheSameCases() {
        // ⚠️ 本测试逐条断言期望值里的行 ⇒ 少一条会 NPE、多一条不会报错。
        // ⇒ 这里显式锁住「用例集」：Go 探针产的与 C 侧产的必须是同一组。
        TreeSet<String> expectedNames = new TreeSet<>(expected().keySet());
        TreeSet<String> producedNames = new TreeSet<>(List.of(
                "WIRE|item_full",
                "WIRE|item_zero",
                "WIRE|item_id_negative",
                "WIRE|item_id_max",
                "WIRE|item_state_normal",
                "WIRE|item_state_deleted",
                "WIRE|item_state_blocked",
                "WIRE|item_children",
                "WIRE|resp_two",
                "WIRE|resp_empty",
                "WIRE|like_req_action_negative",
                "WIRE|like_resp_true",
                "WIRE|like_resp_false",
                "WIRE|report_req_reason_other",
                "WIRE|subject_full",
                "WIRE|create_req_utf8"));
        assertThat(producedNames)
                .as("Go 探针与 C 侧的用例集必须一致（少一条 = 假绿，多一条 = 没人在比）")
                .isEqualTo(expectedNames);
    }

    /** 兜底：期望值的行数不该变化（防止有人手改冻结文件）。 */
    @Test
    @DisplayName("🔴 期望值的行数与用例数一致（兜底：防止有人手改冻结文件）")
    void expectedFileLineCountIsStable() {
        assertThat(readExpected())
                .as("期望值应当正好 16 条用例（新增用例时同步更新本断言与本测试）")
                .hasSize(16);
    }
}
