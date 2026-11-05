package com.rag.favorite.contract;

import com.google.protobuf.Message;
import com.rag.proto.favorite.CreateFavoriteReq;
import com.rag.proto.favorite.FavoriteFolder;
import com.rag.proto.favorite.FavoriteItem;
import com.rag.proto.favorite.GetFavoriteFoldersResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 🔴🔴 favorite proto 的 <b>protobuf 消息体 wire 字节</b>双侧对拍。
 *
 * <h2>它补的是哪个缺口</h2>
 * <p>AGENTS.md §3.4 只保证「{@code .proto} <b>文件本身</b>两侧一致」
 * （Go 侧生成码 {@code service/favorite/rpc/pb/favorite.pb.go:1}），
 * <b>推不出</b>「两侧<b>生成码</b>产出的 wire 字节一致」——
 * 字段号、零值落不落 wire、repeated 的编码方式，<b>只有序列化出来才看得见</b>。
 * <p>⇒ 本类用<b>同一组消息</b>在两侧各序列化一次，比对<b>十六进制字节</b>。
 * <p>⚠️ 两侧的运行时<b>不同</b>：Go 侧是 {@code google.golang.org/protobuf}
 * （protoc-gen-go 的运行时），Java 侧是 {@code protobuf-java}
 * （protoc 的 Java 运行时）⇒ 这不是「同一份实现自证」。
 *
 * <h2>期望值的来源</h2>
 * <p>🔴 <b>全部期望值来自 Go 真源的实测</b>：B 侧 {@code tools/proto-wire-probe/favorite/}
 * 用<b>从 Go 真源逐字节复制</b>的 {@code service/favorite/rpc/pb/favorite.pb.go}
 * （Go 侧<b>真正在用</b>的生成码）算出字节，落在 {@code expected.txt}。
 * ⇒ <b>Go 真源换了生成码 ⇒ 探针的 diff 失败</b> ⇒ 提醒更新本测试。
 *
 * <h2>§3.2 三段式留痕</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>无</b>（用例集、消息构造、期望字节逐条照搬 B 侧）。
 *       两处表达层差异：
 *       <ul>
 *         <li><b>期望值载体</b>：B 侧从 {@code tools/proto-wire-probe/favorite/expected.txt}
 *             <b>运行时读文件</b>（B 侧测试 :75-91 的三候选路径查找）；
 *             C 侧内联为<b>类内常量</b> {@link #EXPECTED_TEXT} ⇒
 *             <b>原因</b>：本仓没有 {@code tools/proto-wire-probe/} 目录，且本次任务的写权面
 *             限定为「4 个测试文件」⇒ 不得新增资源/探针目录。
 *             文本<b>逐字节等于</b> B 侧 {@code expected.txt}。</li>
 *         <li><b>pb 包名</b>：B 侧 {@code com.rag.favorite.favorite.proto.*}
 *             ⇒ C 侧 {@code com.rag.proto.favorite.*}（D7 proto 集中）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 断言的仍是「同一组消息经 protobuf 序列化后的<b>十六进制字节</b>」，
 *       与字节如何取得（读文件 vs 类内常量）无关；
 *       protobuf 的 wire 编码由 {@code protobuf-java} 决定，与 B 侧同一套规范。</li>
 *   <li><b>B 出处</b>：B 侧测试
 *       {@code service/favorite/favorite-rpc/src/test/java/com/rag/favorite/favorite/rpc/proto/FavoriteProtoWireParityTest.java:50-208}
 *       ＋ B 侧期望值文件 {@code tools/proto-wire-probe/favorite/expected.txt:1-6}
 *       ＋ B 侧探针脚本 {@code tools/proto-wire-probe/favorite/run.sh:1-140}。</li>
 * </ol>
 */
class FavoriteProtoWireParityTest {

    /**
     * B 侧 Go 探针实测的期望字节（原文件：{@code tools/proto-wire-probe/favorite/expected.txt}）。
     *
     * <p>格式：{@code WIRE|<用例名>|<十六进制字节>}；空值表示「零字节」。
     */
    private static final String EXPECTED_TEXT = """
            WIRE|folder_full|08818080808080801010e9071a06e68a80e69caf20fbd095ffbc3128c8d395ffbc31
            WIRE|folder_zero|
            WIRE|item_full|08818080808080801010d93618e9072204323030312a0761727469636c653206e6a087e9a2983a1d68747470733a2f2f6578616d706c652e696e76616c69642f632e706e6740fbd095ffbc31
            WIRE|item_partial|08012a04706f7374
            WIRE|folders_resp_two|0a14080710e9071a09e694b6e8978fe5a4b9200128020a14080810ea071a09e58fa6e4b880e4b8aa20032804
            WIRE|create_req|08e90710d9361a0432303031220761727469636c652a06e6a087e9a298
            """;

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
                    "expected.txt 里没有 WIRE|" + name + "（探针与本测试的用例集不一致）");
        }
        return hex;
    }

    private static List<String> readExpected() {
        List<String> lines = new ArrayList<>();
        for (String line : EXPECTED_TEXT.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    // ══════════════════ 产出 RAG 侧的字节 ══════════════════

    private static String hex(Message message) {
        return HexFormat.of().formatHex(message.toByteArray());
    }

    // ══════════════════ 用例 ══════════════════

    @Test
    @DisplayName("🔴 FavoriteFolder 的 wire 字节与 Go 真源逐字节一致")
    void favoriteFolderWireMatchesGo() {
        // ① 全部字段都有值。
        //    ⚠️ 用 2^53+1 的雪花 ID：int64 在 wire 上是 varint，不会丢精度 ——
        //    但某一侧若先转成 JSON 数字，字节就不同。
        FavoriteFolder full = FavoriteFolder.newBuilder()
                .setFolderId(9007199254740993L)
                .setUserId(1001L)
                .setName("技术")
                .setCreatedAt(1700000000123L)
                .setUpdatedAt(1700000000456L)
                .build();
        assertEquals(expectedHex("folder_full"), hex(full));

        // ② 全零值：proto3 的标量零值**不落 wire** ⇒ 必须是**空字节**。
        //    ⚠️ 这条能抓住「某一侧给零值写了 presence」。
        assertEquals(expectedHex("folder_zero"), hex(FavoriteFolder.getDefaultInstance()));
        assertEquals("", hex(FavoriteFolder.getDefaultInstance()),
                "零值消息在 wire 上必须是空字节（proto3 的隐式 presence）");
    }

    @Test
    @DisplayName("🔴 FavoriteItem 的 wire 字节与 Go 真源逐字节一致")
    void favoriteItemWireMatchesGo() {
        // ③ 全部字段都有值。
        FavoriteItem full = FavoriteItem.newBuilder()
                .setFavoriteId(9007199254740993L)
                .setFolderId(7001L)
                .setUserId(1001L)
                .setTargetId("2001")
                .setTargetType("article")
                .setTitle("标题")
                .setCover("https://example.invalid/c.png")
                .setCreatedAt(1700000000123L)
                .build();
        assertEquals(expectedHex("item_full"), hex(full));

        // ④ 只设两个字段 ⇒ **只有这两个字段落 wire**。
        //    ⚠️ 这条能抓住「某一侧把空字符串也写上去」（那是 JSON 的行为，不是 proto3 的）。
        FavoriteItem partial = FavoriteItem.newBuilder()
                .setFavoriteId(1L)
                .setTargetType("post")
                .build();
        assertEquals(expectedHex("item_partial"), hex(partial));
    }

    @Test
    @DisplayName("🔴 repeated 字段（收藏夹列表）的 wire 字节与 Go 真源逐字节一致")
    void repeatedFieldWireMatchesGo() {
        // ⑤ 两个收藏夹 ⇒ 两次 tag 1 的 length-delimited。
        //    ⚠️ 这条能抓住「某一侧把 repeated 编成 map 或 JSON 数组」。
        GetFavoriteFoldersResp resp = GetFavoriteFoldersResp.newBuilder()
                .addFolders(FavoriteFolder.newBuilder()
                        .setFolderId(7L).setUserId(1001L).setName("收藏夹")
                        .setCreatedAt(1L).setUpdatedAt(2L).build())
                .addFolders(FavoriteFolder.newBuilder()
                        .setFolderId(8L).setUserId(1002L).setName("另一个")
                        .setCreatedAt(3L).setUpdatedAt(4L).build())
                .build();
        assertEquals(expectedHex("folders_resp_two"), hex(resp));
    }

    @Test
    @DisplayName("🔴 请求消息的 wire 字节与 Go 真源逐字节一致")
    void requestMessageWireMatchesGo() {
        // ⑥ folderId 是 int64 ⇒ wire 上是 varint（**不是**字符串）。
        //    ⚠️ 这条能抓住「某一侧把 int64 编成 string」（那是 api 层的 JSON 约定，不是 proto 的）。
        CreateFavoriteReq req = CreateFavoriteReq.newBuilder()
                .setUserId(1001L)
                .setFolderId(7001L)
                .setTargetId("2001")
                .setTargetType("article")
                .setTitle("标题")
                .build();
        assertEquals(expectedHex("create_req"), hex(req));
    }

    @Test
    @DisplayName("🔴 探针与本测试的用例集必须完全一致（防止有人只改了一边）")
    void expectedFileAndThisTestCoverTheSameCases() {
        // ⚠️ 本测试逐条断言 expected.txt 里的行 ⇒ 少一条会 NPE、多一条不会报错。
        // ⇒ 这里显式锁住「用例集」：Go 探针产的与 Java 侧产的必须是同一组。
        TreeSet<String> expectedNames = new TreeSet<>(expected().keySet());
        TreeSet<String> producedNames = new TreeSet<>(List.of(
                "WIRE|folder_full",
                "WIRE|folder_zero",
                "WIRE|item_full",
                "WIRE|item_partial",
                "WIRE|folders_resp_two",
                "WIRE|create_req"));
        assertEquals(expectedNames, producedNames,
                "Go 探针与 Java 侧的用例集必须一致（少一条 = 假绿，多一条 = 没人在比）");
    }

    /** 兜底：expected.txt 的行数不该变化（防止有人手改冻结文件）。 */
    @Test
    @DisplayName("📌 expected.txt 的行数与用例数一致")
    void expectedFileLineCountIsStable() {
        assertEquals(6, readExpected().size(),
                "expected.txt 应当正好 6 条用例（新增用例时同步更新本断言与本测试）");
    }
}
