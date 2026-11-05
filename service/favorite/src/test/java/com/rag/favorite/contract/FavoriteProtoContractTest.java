package com.rag.favorite.contract;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.util.JsonFormat;
import com.rag.proto.favorite.FavoriteItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>favorite proto 的契约回归网</b> —— 把「{@code .proto} 文件两侧一致」升级为
 * 「<b>生成码的契约</b>两侧一致」。
 *
 * <h2>为什么需要它（AGENTS.md §3.4 的效力边界）</h2>
 *
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
 * <h2>🔴 期望值是「由真源生成」的，不是手抄的</h2>
 *
 * <p>基准文本来自 B 侧 {@code service/favorite/favorite-rpc/src/test/resources/proto-contract/favorite-proto-contract.expected.txt}
 * —— 该文件是<b>B 侧脚本从 Go 真源的 {@code proto/favorite.proto} 生成</b>的
 * （同目录 {@code generate.py}，头部记录了真源的 sha256）⇒
 * 手抄一份字段表 = 把真源的实现细节抄一遍 ⇒ <b>抄错时看不出错</b>。
 * <p>⚠️ <b>基准文本不是手工编辑物</b>：真源 {@code .proto} 变了要重跑 B 侧脚本 + 重新评审 diff。
 *
 * <h2>⚠️ 基准脚本的已知边界（嵌套 message）</h2>
 *
 * <p>B 侧该目录的 {@code generate.py} 是 {@code like} 域那份（<b>非贪婪正则</b>抓 message 体）
 * ⇒ 它<b>不解析嵌套 message</b>。
 * <p>⇒ 本 proto <b>一旦新增嵌套 message</b>，基准会失真 ⇒ 本类会<b>变红</b>（这是期望行为：
 * 红了就说明「该重跑生成脚本并重新评审」），<b>但不会假绿</b>。
 *
 * <h2>🔴 proto {@code package} 两侧一致</h2>
 *
 * <p>Go 侧 {@code favorite} = RAG 侧 {@code favorite} ⇒
 * 本类把 {@code package} 行<b>一并纳入</b>逐行比对（不豁免）。
 * ⚠️ 注意：{@code java_package = "com.rag.proto.favorite"} 是<b>Java 侧</b>的选项，
 * <b>不改</b> protobuf 的 {@code package} ⇒ 描述符读出来仍是 {@code favorite}。
 *
 * <h2>§3.2 三段式留痕</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>无</b>（断言集合、期望值、渲染规则逐条照搬 B 侧）。
 *       唯一的两处表达层差异：
 *       <ul>
 *         <li><b>基准载体</b>：B 侧是 classpath 下的<b>资源文件</b>
 *             （{@code /proto-contract/favorite-proto-contract.expected.txt}）；
 *             C 侧内联为<b>类内常量</b> {@link #BASELINE_TEXT} ⇒
 *             <b>原因</b>：本次任务的写权面限定为「4 个测试文件」，不得新增资源文件。
 *             基准<b>文本逐字节等于</b> B 侧资源文件（含 sha256 溯源行）。</li>
 *         <li><b>pb 包名</b>：B 侧 {@code com.rag.favorite.favorite.proto.FavoriteItem}
 *             ⇒ C 侧 {@code com.rag.proto.favorite.FavoriteItem}（D7 proto 集中）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 被测对象仍是<b>真正参与 wire 编码</b>的那份描述符
 *       （{@code com.google.protobuf.Descriptors.FileDescriptor}），
 *       断言的字段号 / 类型 token / presence / repeated / 枚举值 / rpc 签名<b>逐条同义</b>。</li>
 *   <li><b>B 出处</b>：B 侧测试
 *       {@code service/favorite/favorite-rpc/src/test/java/com/rag/favorite/favorite/rpc/proto/FavoriteProtoContractTest.java:74-426}
 *       ＋ B 侧基准文件
 *       {@code service/favorite/favorite-rpc/src/test/resources/proto-contract/favorite-proto-contract.expected.txt:1-22}
 *       ＋ B 侧生成脚本
 *       {@code service/favorite/favorite-rpc/src/test/resources/proto-contract/generate.py:1-120}。</li>
 * </ol>
 */
class FavoriteProtoContractTest {

    /**
     * B 侧基准文件的<b>逐字节内联副本</b>（原文件：
     * {@code service/favorite/favorite-rpc/src/test/resources/proto-contract/favorite-proto-contract.expected.txt}）。
     *
     * <p>⚠️ 以 {@code #} 开头的行是<b>溯源信息</b>（不是契约）⇒ 读取时被过滤掉，
     * 与 B 侧 {@code readBaseline()} 的过滤规则一致。
     */
    private static final String BASELINE_TEXT = """
            # proto 契约基准 —— **由脚本从 Go 真源生成，勿手工编辑**
            # 生成脚本（B 侧同目录）：service/favorite/favorite-rpc/src/test/resources/proto-contract/generate.py
            # 真源：Sea-RideTheWind_golang/proto/favorite.proto
            # 真源 sha256：c1e59375401f55b439450c62b9aee18e698555ed4f8aba2fa9d4cac7d9199de4
            package favorite
            message|FavoriteFolder|folder_id=1:int64|user_id=2:int64|name=3:string|created_at=4:int64|updated_at=5:int64
            message|FavoriteItem|favorite_id=1:int64|folder_id=2:int64|user_id=3:int64|target_id=4:string|target_type=5:string|title=6:string|cover=7:string|created_at=8:int64
            message|CreateFavoriteFolderReq|user_id=1:int64|name=2:string
            message|CreateFavoriteFolderResp|folder_id=1:int64
            message|CreateFavoriteReq|user_id=1:int64|folder_id=2:int64|target_id=3:string|target_type=4:string|title=5:string|cover=6:string
            message|CreateFavoriteResp|favorite_id=1:int64
            message|DeleteFavoriteFolderReq|user_id=1:int64|folder_id=2:int64
            message|DeleteFavoriteFolderResp|success=1:bool
            message|DeleteFavoriteReq|user_id=1:int64|favorite_id=2:int64
            message|DeleteFavoriteResp|success=1:bool
            message|UpdateFavoriteFolderNameReq|user_id=1:int64|folder_id=2:int64|name=3:string
            message|UpdateFavoriteFolderNameResp|success=1:bool
            message|GetFavoriteFoldersReq|user_id=1:int64
            message|GetFavoriteFoldersResp|folders=1:repeated FavoriteFolder
            message|GetFavoritesByFolderReq|user_id=1:int64|folder_id=2:int64
            message|GetFavoritesByFolderResp|favorites=1:repeated FavoriteItem
            service|FavoriteService|CreateFavoriteFolder(CreateFavoriteFolderReq)->CreateFavoriteFolderResp|CreateFavorite(CreateFavoriteReq)->CreateFavoriteResp|DeleteFavoriteFolder(DeleteFavoriteFolderReq)->DeleteFavoriteFolderResp|DeleteFavorite(DeleteFavoriteReq)->DeleteFavoriteResp|UpdateFavoriteFolderName(UpdateFavoriteFolderNameReq)->UpdateFavoriteFolderNameResp|GetFavoriteFolders(GetFavoriteFoldersReq)->GetFavoriteFoldersResp|GetFavoritesByFolder(GetFavoritesByFolderReq)->GetFavoritesByFolderResp
            """;

    /** Go 真源 proto 的 {@code package}（基准文件首行 {@code package} 之后那行）。 */
    private static final String GO_PACKAGE = "favorite";

    /** RAG 侧 proto 的 {@code package}。 */
    private static final String RAG_PACKAGE = "favorite";

    /** 本域 proto package 是否两侧不一致（不一致 ⇒ 排除 package 行 + 单独钉住签字值）。 */
    private static final boolean PACKAGE_DIVERGES = false;

    @Test
    @DisplayName("🔴 favorite proto 契约（消息/字段号/类型/optional/repeated/map/枚举/rpc）与 Go 真源基准逐条一致")
    void protoContractMatchesGoBaseline() throws IOException {
        List<String> expected = readBaseline();
        List<String> actual = describe();

        // 🔴 结构性 sanity（**不是行数启发式**）：本域 proto 大小差异极大
        // （task 只有 5 行 ⇒ 「> 5 行」这种阈值会把**正确**的基准判成异常），
        // ⇒ 改为钉「基准必须含 package 行 + 至少一条 message + 至少一条 service」。
        assertTrue(expected.size() >= 4,
                "基准文件内容异常（只有 " + expected.size() + " 行）⇒ 生成脚本可能没跑对");
        assertTrue(expected.stream().anyMatch(l -> l.startsWith("package ")),
                "🔴 基准文件里没有 package 行 ⇒ 生成脚本可能没跑对");
        assertTrue(expected.stream().anyMatch(l -> l.startsWith("message|")),
                "🔴 基准文件里没有 message 行 ⇒ 生成脚本可能没跑对");
        assertTrue(expected.stream().anyMatch(l -> l.startsWith("service|")),
                "🔴 基准文件里没有 service 行 ⇒ 生成脚本可能没跑对");

        if (PACKAGE_DIVERGES) {
            // 🔴 排除 package 行（已签字偏离），其余**逐行**相等。
            assertEquals(stripPackage(expected), stripPackage(actual),
                    "favorite proto 契约与 Go 真源基准不一致 ⇒ 字段号 / 类型 / presence / 枚举值 / rpc 签名发生了漂移");
        } else {
            assertEquals(expected, actual,
                    "favorite proto 契约与 Go 真源基准不一致 ⇒ 字段号 / 类型 / presence / 枚举值 / rpc 签名发生了漂移");
        }
    }

    @Test
    @DisplayName("🔴 favorite proto package 必须是{@code favorite}（Go 侧 favorite ⇒ 两侧一致）")
    void protoPackageIsTheSignedNormalisedValue() throws IOException {
        List<String> expected = readBaseline();
        assertEquals(GO_PACKAGE, packageOf(expected),
                "🔴 基准文件里的 Go 真源 package 变了 ⇒ 要重新评审 favorite 域 proto package 的签字偏离");

        Descriptors.FileDescriptor file = FavoriteItem.getDescriptor().getFile();
        assertEquals(RAG_PACKAGE, file.getPackage(),
                "🔴 RAG 侧 proto package 不是签字值 " + RAG_PACKAGE
                        + " ⇒ 要么是有人「顺手改回 Go 的 " + GO_PACKAGE + "」（违反签字偏离），要么是改成了别的值");
    }

    /**
     * 🔴 【PROTO 型 ①】<b>本 proto 没有 proto3 {@code optional} 字段</b> ⇒ presence 语义与 Go 一致。
     *
     * <p><b>为什么必须钉死</b>：Go 真源 {@code favorite.proto} 里<b>没有</b> {@code optional} 字段；
     * RAG 侧若<b>多出</b> {@code optional} ⇒ 该字段在 wire 上从「无 presence」变成「有 presence」
     * ⇒ 与 Go 侧<b>不同形</b>（Go 的 {@code string} 与 RAG 的 {@code optional string} 不能互换）。
     * <p>⇒ 本条把「没有 optional」这条事实钉成断言，<b>防后人顺手加 optional</b>。
     */
    @Test
    @DisplayName("🔴 favorite.proto 无 proto3 optional 字段 ⇒ presence 语义与 Go 一致（显式钉死，防后人加 optional）")
    void noProto3OptionalField() {
        Descriptors.FileDescriptor file = FavoriteItem.getDescriptor().getFile();
        List<String> optionalFields = new ArrayList<>();
        for (Descriptors.Descriptor msg : file.getMessageTypes()) {
            for (Descriptors.FieldDescriptor f : msg.getFields()) {
                if (f.toProto().hasProto3Optional()) {
                    optionalFields.add(msg.getName() + "." + f.getName());
                }
            }
        }
        assertEquals(List.of(), optionalFields,
                "🔴 Go 真源 proto/favorite.proto 里**没有** proto3 optional 字段；"
                        + "RAG 侧若多出 optional ⇒ presence 语义与 Go 不同（wire 上「有值/无值」不可区分）");
    }

    /**
     * 🔴 【PROTO 型 ①·生成码侧】<b>生成码确实<b>没有</b>为 title 生成 {@code hasXxx()}</b>
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
    @DisplayName("🔴 favorite 生成码不得为普通标量 title 生成 hasXxx()（无 presence ⇒ 与 Go 同形）")
    void generatedCodeHasNoPresenceApiForPlainScalar() {
        assertThrows(NoSuchMethodException.class,
                () -> FavoriteItem.class.getMethod("hasTitle"),
                "🔴 FavoriteItem.title 是 proto3 普通 string（无 optional）"
                        + " ⇒ 生成码**不该**有 hasTitle()；有 ⇒ presence 语义与 Go 不同");

        Method getter = null;
        try {
            getter = FavoriteItem.class.getMethod("getTitle");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("生成码缺少 getTitle() ⇒ 生成码与基准不是同一份契约", e);
        }
        assertNotNull(getter, "生成码缺少 getTitle()");
    }

    /**
     * 🔴 【PROTO 型 ③】<b>JSON 形态：普通标量<b>没有</b> presence ⇒ 设成默认值也<b>不出现</b>。</b>
     *
     * <p>用 protobuf 官方 {@code JsonFormat.printer().omittingInsignificantWhitespace()} 序列化<b>生成码</b>（不是手搓 JSON）⇒
     * 断言的是「<b>presence 在 JSON 上的可观察差异</b>」：
     * <p>⚠️ <b>必须用 omittingInsignificantWhitespace</b>：默认 printer 输出<b>带缩进换行</b>的
     * pretty JSON（空 message 也不是紧凑的空对象）⇒ 断言无法逐字比对。
     * <ul>
     *   <li>普通标量 {@code title}：设成默认值（""）⇒ JSON 里<b>没有</b>这个 key
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
    @DisplayName("🔴 favorite proto JSON 形态：普通标量 title 无 presence（设默认值也不出现）")
    void jsonFormHasNoPresenceForPlainScalar() throws Exception {
        // ① 设成默认值 ⇒ key 不出现（无 presence）
        String emptyJson = JsonFormat.printer().omittingInsignificantWhitespace().print(
                FavoriteItem.newBuilder().setTitle("").build());
        assertEquals("{}", emptyJson,
                "🔴 FavoriteItem 的 title 是 proto3 普通 string ⇒ 设成默认值后 JSON 里**不该**出现该 key"
                        + "（有 ⇒ 出现了 Go 侧不存在的 presence 语义）");

        // ② 设成非默认值 ⇒ key 出现
        String filledJson = JsonFormat.printer().omittingInsignificantWhitespace().print(
                FavoriteItem.newBuilder().setTitle("t").build());
        assertTrue(filledJson.contains("\"title\":\"t\""),
                "🔴 FavoriteItem 的 title 设成非默认值后 JSON 里应出现 " + "\"title\":\"t\""
                        + " ⇒ 实际得到 " + filledJson);
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
    @DisplayName("🔴 favorite 域源码不得硬编码 proto 字段号（setField/FIELD_NUMBER/CodedOutputStream 等）")
    void noHardcodedProtoFieldNumbersInSource() throws IOException {
        Path scanRoot = locateDomainSourceRoot();
        assertNotNull(scanRoot,
                "🔴 找不到 favorite 域源码目录 ⇒ 本条会退化成「永远绿」的假门 ⇒ 必须失败而不是跳过");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(scanRoot)) {
            for (Path file : files.filter(FavoriteProtoContractTest::isProductionSource).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (isProtobufLowLevelUsage(line)) {
                        violations.add(scanRoot.relativize(file) + ":" + (i + 1) + "  " + line.trim());
                    }
                }
            }
        }
        assertEquals(List.of(), violations,
                "🔴 favorite 域源码里出现了按字段号的 protobuf 低层 API ⇒ "
                        + "契约比对（比的是声明）抓不到它，但 wire 已经变了");
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
     * 定位本域的源码根 —— 从当前工作目录向上找「含 {@code service/favorite} 的目录」。
     *
     * <p>surefire 的 {@code user.dir} = 当前模块目录 ⇒ 向上最多 5 层即可命中仓库根。
     */
    private static Path locateDomainSourceRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path candidate = cursor.resolve("service/favorite");
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
        Descriptors.FileDescriptor file = FavoriteItem.getDescriptor().getFile();
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
     * 字段的类型标记 —— 与 {@code generate.py} 的归一规则逐条对应。
     *
     * <p>⚠️ <b>map 要先判</b>：protobuf 里 map 字段的 label 也是 {@code LABEL_REPEATED}
     * ⇒ 若先判 repeated，map 会被渲染成 {@code repeated FooEntry} ⇒ 与基准对不上。
     */
    private static String typeToken(Descriptors.FieldDescriptor f) {
        if (f.isMapField()) {
            Descriptors.Descriptor entry = f.getMessageType();
            Descriptors.FieldDescriptor key = entry.findFieldByName("key");
            Descriptors.FieldDescriptor value = entry.findFieldByName("value");
            assertNotNull(key, "map 字段的 entry 没有 key");
            assertNotNull(value, "map 字段的 entry 没有 value");
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

    /** 读基准文本；**注释行（{@code #} 开头）不参与比对**（那是溯源信息，不是契约）。 */
    private static List<String> readBaseline() {
        List<String> lines = new ArrayList<>();
        for (String line : BASELINE_TEXT.split("\\R")) {
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
