package probe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🔴🔴🔴 <b>favorite 域 · 双侧活体对拍探针</b>（B 侧 RAG ↔ C 侧 RAG-V1，第三方锚 = Go 真源冻结字节）。
 *
 * <h2>它是什么</h2>
 * <p>一个<b>独立可执行</b>的 HTTP 探针（<b>JDK-only · 零第三方依赖</b>）：
 * 用<b>同一个</b> {@link HttpClient} 打两侧（{@code PROBE_B_API} / {@code PROBE_C_API}），
 * 对 <b>同一套 32 个 case</b> 逐条渲染成可逐字节比对的字符串，落成两份 TSV。
 *
 * <h2>为什么是「独立程序」而不是 JUnit 测试</h2>
 * <ul>
 *   <li>🔴 <b>B 侧仓是只读的</b>（AGENTS.md §五.4）⇒ ⇒ 不能往 B 侧塞本探针的测试类
 *       ⇒ ⇒ 探针必须住在 <b>C 侧</b>；</li>
 *   <li>🔴 <b>探针自己的依赖也必须唯一来源</b> ⇒ 既然它只用 JDK 的 {@code java.net.http}
 *       ⇒ ⇒ <b>classpath 恒为空</b> ⇒ ⇒ 不存在「量到的是哪一版依赖」的问题
 *       ⇒ ⇒ 只需 {@code javac} + {@code java}（零 maven、零 jar）；</li>
 *   <li>⇒ 探针<b>不会</b>因为任一侧的测试树编译失败而挂掉（两侧测试树都不参与）。</li>
 * </ul>
 *
 * <h2>🔴🔴 渲染格式必须与冻结锚<b>逐字节同形</b></h2>
 * <p>本类的 {@code render()} / {@code escape()} / {@code normalizeContentType()} / {@code bizCode()}
 * 四个方法，以及 {@code matrix()} 的 32 个 case，
 * <b>逐字抄自</b> B 侧探针类：
 * <pre>
 *   B 侧 service/favorite/favorite-api/src/test/java/com/rag/favorite/favorite/api/contract/FavoriteApiFullParityTest.java
 *   sha256 = 3cb10758c16f50cbe83edb21655c2fa03e89fa9d561a74dd4702f90be2ed1588
 * </pre>
 * ⇒ ⇒ 冻结锚（Go 真源字节）才能<b>直接</b>与本探针的输出比对
 * ⇒ ⇒ <b>改了渲染函数 ⇒ 冻结锚立刻失配</b> ⇒ ⇒ 这正是我们要的「改了就红」。
 *
 * <h2>🔴 为什么用<b>同一个</b>客户端打两侧</h2>
 * <p>换掉客户端，结论就不成立：请求矩阵、请求头、请求体、路径、query 全部由本类生成
 * ⇒ ⇒ 客户端自身的差异被消掉 ⇒ ⇒ 只留下<b>服务端</b>差异。
 *
 * <h2>用例顺序 = 副作用顺序</h2>
 * <p>写入类用例（改名 / 删除）会改库 ⇒ ⇒ 矩阵里<b>所有只读用例在前、所有写入用例在后</b>，
 * 且最后补一个只读用例验证「写入确实生效」⇒ ⇒ 顺序本身就是被冻结的契约的一部分。
 *
 * <h2>输出格式</h2>
 * <p>TSV：{@code <caseId>\t<渲染值>}，渲染值 =
 * {@code status=<码>|ct=<Content-Type 已归一>|body=<body 已转义>|biz=<业务码>}[|hkeys=<响应头 key 集合>}]
 *
 * <h2>退出码</h2>
 * <ul>
 *   <li>{@code 0} —— 两侧各跑完 32 个 case，输出文件已落盘；</li>
 *   <li>{@code 1} —— 某个 case 请求失败（连接被拒 / 超时 / 读响应失败）⇒ <b>环境问题</b>；</li>
 *   <li>{@code 2} —— 环境变量缺失。</li>
 * </ul>
 */
public final class FavoriteApiParityProbe {

    /** 一个 HTTP 用例。 */
    private record Case(String id,
                        String method,
                        String path,
                        String query,
                        String auth,
                        String body,
                        boolean json,
                        boolean fullHeaders) {
    }

    private static final String JSON = "application/json";

    /**
     * 请求矩阵（逐字抄自 B 侧探针，见类注释的 sha256）。
     *
     * <p>⚠️ <b>顺序即契约</b>：只读用例全部在前，写入用例全部在后，最后一个只读用例验证写入生效。
     */
    private static List<Case> matrix() {
        List<Case> cs = new ArrayList<>();

        // ─────────── 1. 鉴权组（JWT 中间件 + 黑名单中间件）───────────
        cs.add(new Case("a01_no_auth_header", "GET", "/folder/list", null, null, null, false, false));
        cs.add(new Case("a02_bad_signature", "GET", "/folder/list", null,
                "Bearer " + token("BADSIG"), null, false, false));
        cs.add(new Case("a03_expired_token", "GET", "/folder/list", null,
                "Bearer " + token("EXPIRED"), null, false, false));
        cs.add(new Case("a04_bearer_prefix_only", "GET", "/folder/list", null,
                "Bearer ", null, false, false));
        cs.add(new Case("a05_lowercase_bearer", "GET", "/folder/list", null,
                "bearer " + token("VALID_USER1"), null, false, false));
        cs.add(new Case("a06_blacklisted_token", "GET", "/folder/list", null,
                "Bearer " + token("BLACKLISTED"), null, false, false));

        // ─────────── 2. 路由组（404 / 405 不经鉴权）───────────
        cs.add(new Case("r01_unknown_path", "GET", "/nope", null, null, null, false, false));
        cs.add(new Case("r02_method_not_allowed_post", "POST", "/folder/list", null,
                "Bearer " + token("VALID_USER1"), null, false, false));
        cs.add(new Case("r03_method_not_allowed_get", "GET", "/folder/create", null,
                "Bearer " + token("VALID_USER1"), null, false, false));

        // ─────────── 3. 解析组（httpx.Parse 的每一档）───────────
        cs.add(new Case("p01_invalid_json_body", "POST", "/folder/create", null,
                "Bearer " + token("VALID_USER1"), "{not-json", true, false));
        cs.add(new Case("p02_missing_required_name", "POST", "/folder/create", null,
                "Bearer " + token("VALID_USER1"), "{}", true, false));
        cs.add(new Case("p03_wrong_type_name", "POST", "/folder/create", null,
                "Bearer " + token("VALID_USER1"), "{\"name\":123}", true, false));
        cs.add(new Case("p04_item_list_missing_folder_id", "GET", "/item/list", null,
                "Bearer " + token("VALID_USER1"), null, false, false));
        cs.add(new Case("p05_item_list_non_numeric_folder_id", "GET", "/item/list",
                "folderId=abc", "Bearer " + token("VALID_USER1"), null, false, false));
        cs.add(new Case("p06_json_body_without_content_type", "POST", "/folder/create", null,
                "Bearer " + token("VALID_USER1"), "{\"name\":\"NoContentType\"}", false, false));
        cs.add(new Case("p07_delete_folder_missing_id", "DELETE", "/folder/delete", null,
                "Bearer " + token("VALID_USER1"), "{}", true, false));

        // ─────────── 4. 业务组（穿过 gRPC 往返 ⇒ 业务码靠 description 反查）───────────
        cs.add(new Case("b01_create_folder_downstream_absent", "POST", "/folder/create", null,
                "Bearer " + token("VALID_USER1"), "{\"name\":\"NewFolder\"}", true, false));
        cs.add(new Case("b02_create_item_downstream_absent", "POST", "/item/create", null,
                "Bearer " + token("VALID_USER1"),
                "{\"folderId\":\"9001\",\"targetId\":\"t-9\",\"targetType\":\"post\"}", true, false));
        cs.add(new Case("b03_delete_folder_not_found", "DELETE", "/folder/delete", null,
                "Bearer " + token("VALID_USER1"), "{\"folderId\":\"9999\"}", true, false));
        cs.add(new Case("b04_delete_item_not_found", "DELETE", "/item/delete", null,
                "Bearer " + token("VALID_USER1"), "{\"favoriteId\":\"9999\"}", true, false));
        cs.add(new Case("b05_update_folder_not_found", "POST", "/folder/update", null,
                "Bearer " + token("VALID_USER1"), "{\"folderId\":\"9999\",\"name\":\"Z\"}", true, false));
        cs.add(new Case("b06_delete_folder_forbidden", "DELETE", "/folder/delete", null,
                "Bearer " + token("VALID_USER1"), "{\"folderId\":\"9003\"}", true, false));
        cs.add(new Case("b07_list_items_forbidden", "GET", "/item/list", "folderId=9003",
                "Bearer " + token("VALID_USER1"), null, false, false));

        // ─────────── 5. 只读成功组（seed 写死 ⇒ 两侧字节可比）───────────
        cs.add(new Case("s01_list_folders", "GET", "/folder/list", null,
                "Bearer " + token("VALID_USER1"), null, false, false));
        cs.add(new Case("s02_list_items_of_9001", "GET", "/item/list", "folderId=9001",
                "Bearer " + token("VALID_USER1"), null, false, false));
        cs.add(new Case("s03_list_items_of_empty_folder", "GET", "/item/list", "folderId=9002",
                "Bearer " + token("VALID_USER1"), null, false, false));
        cs.add(new Case("s04_list_folders_empty_user", "GET", "/folder/list", null,
                "Bearer " + token("VALID_USER2"), null, false, false));

        // ─────────── 6. 响应头面（完整响应头 key 集合）───────────
        cs.add(new Case("h01_response_header_surface", "GET", "/folder/list", null,
                "Bearer " + token("VALID_USER1"), null, false, true));

        // ─────────── 7. 写入组（副作用；必须排在只读组之后）───────────
        cs.add(new Case("w01_update_folder_name", "POST", "/folder/update", null,
                "Bearer " + token("VALID_USER1"), "{\"folderId\":\"9002\",\"name\":\"BetaRenamed\"}", true, false));
        cs.add(new Case("w02_delete_item", "DELETE", "/item/delete", null,
                "Bearer " + token("VALID_USER1"), "{\"favoriteId\":\"7001\"}", true, false));
        cs.add(new Case("w03_delete_folder", "DELETE", "/folder/delete", null,
                "Bearer " + token("VALID_USER1"), "{\"folderId\":\"9002\"}", true, false));

        // ─────────── 8. 写入生效验证（必须最后）───────────
        cs.add(new Case("z01_list_folders_after_writes", "GET", "/folder/list", null,
                "Bearer " + token("VALID_USER1"), null, false, false));

        return cs;
    }

    // ══════════════════════════ 主流程 ══════════════════════════

    public static void main(String[] args) {
        try {
            String bBase = env("PROBE_B_API");
            String cBase = env("PROBE_C_API");

            // ⚠️ 同一个客户端打两侧 ⇒ 消掉客户端自身差异
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            Map<String, String> bOut = new LinkedHashMap<>();
            Map<String, String> cOut = new LinkedHashMap<>();

            List<Case> matrix = matrix();
            for (Case c : matrix) {
                bOut.put(c.id(), render(call(client, bBase, c), c.fullHeaders()));
                cOut.put(c.id(), render(call(client, cBase, c), c.fullHeaders()));
            }

            writeLines(env("PROBE_OUT_B"), bOut);
            writeLines(env("PROBE_OUT_C"), cOut);
            System.out.println("probe ok: " + matrix.size() + " cases x 2 sides");
        } catch (IllegalStateException e) {
            System.err.println("probe 环境问题: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("probe 失败: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ══════════════════════════ HTTP ══════════════════════════

    private static HttpResponse<String> call(HttpClient client, String base, Case c) throws Exception {
        String url = base + c.path() + (c.query() == null ? "" : "?" + c.query());
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30));
        if (c.auth() != null) {
            b.header("Authorization", c.auth());
        }
        if (c.json()) {
            b.header("Content-Type", JSON);
        }
        if (c.body() != null) {
            b.method(c.method(), HttpRequest.BodyPublishers.ofString(c.body(), StandardCharsets.UTF_8));
        } else {
            b.method(c.method(), HttpRequest.BodyPublishers.noBody());
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ══════════════════════════ 渲染 ══════════════════════════

    /**
     * 把一个响应渲染成可逐字节比对的字符串。
     *
     * <p>格式：{@code status=<code>|ct=<content-type 已归一>|body=<body>|biz=<业务码>}
     *
     * <p>🔴 <b>Content-Type 的 OWS 归一</b>：Go 写 {@code application/json; charset=utf-8}
     * （分号后<b>有</b>空格），Spring 渲染成 {@code application/json;charset=utf-8}（<b>无</b>空格）
     * ⇒ HTTP 媒体类型参数分隔符两侧的空白是可选的（RFC 9110 §5.6.2 的 OWS）
     * ⇒ 任何解析器都会归一化 ⇒ <b>无客户端能分辨</b> ⇒ 属表达层差异
     * ⇒ 只对 Content-Type 这一个字段做 OWS 归一，<b>body 仍逐字节比较</b>。
     */
    private static String render(HttpResponse<String> resp, boolean fullHeaders) {
        StringBuilder sb = new StringBuilder();
        sb.append("status=").append(resp.statusCode());
        sb.append("|ct=").append(normalizeContentType(resp.headers().firstValue("content-type").orElse("-")));
        sb.append("|body=").append(escape(resp.body()));
        sb.append("|biz=").append(bizCode(resp.body()));
        if (fullHeaders) {
            sb.append("|hkeys=").append(String.join(",", new TreeSet<>(
                    resp.headers().map().keySet())));
        }
        return sb.toString();
    }

    /**
     * 转义控制字符 —— 让渲染值能安全地塞进 <b>TSV</b>（{@code id\tvalue}）。
     *
     * <p>🔴 <b>为什么必须转义</b>：Go 的 404 body 是 {@code "404 page not found\n"}
     * （<b>带一个尾随换行</b>）⇒ 不转义就会把一行 TSV 撑成两行
     * ⇒ 解析出来的 case 会**少一个**，而且看起来「只是少了一个 case」，**不会报错**
     * ⇒ 静默丢覆盖。
     */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String normalizeContentType(String ct) {
        return ct.replace(";", ";").replaceAll(";\\s*", ";").trim();
    }

    /** 业务码：从 body 的 JSON 里取 {@code "code":<数字>}；取不到就是 {@code -}。 */
    private static String bizCode(String body) {
        if (body == null || body.isEmpty()) {
            return "-";
        }
        Matcher m = Pattern.compile("\"code\"\\s*:\\s*(\\d+)").matcher(body);
        return m.find() ? m.group(1) : "-";
    }

    private static void writeLines(String path, Map<String, String> data) throws IOException {
        Path p = Paths.get(path);
        Files.createDirectories(p.getParent());
        List<String> lines = new ArrayList<>();
        data.forEach((k, v) -> lines.add(k + "\t" + v));
        Files.write(p, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    // ══════════════════════════ 环境 ══════════════════════════

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + key + "（探针必须由 run.sh 编排，不要单跑）");
        }
        return v;
    }

    private static String token(String key) {
        return env("PROBE_TOKEN_" + key);
    }

    private FavoriteApiParityProbe() {
    }
}
