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
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🔴 **favorite 场景轮的动作探针** —— 把「矩阵型」用例之外的那 6 类场景做出来。
 *
 * <h2>为什么要有这一层（矩阵覆盖不到什么）</h2>
 * <p>现有 32 case 是<b>「单请求 × 请求面」</b>的矩阵 ⇒ ⇒ 它<b>故意</b>让下游缺席
 * （{@code static://127.0.0.1:1}）⇒ ⇒ 走的是「下游不可用」那条契约分支。
 * ⇒ ⇒ 以下 6 类场景它<b>结构性覆盖不到</b>：
 * <ol>
 *   <li><b>create 成功路径</b> —— 需要 user-rpc <b>在场</b>；</li>
 *   <li><b>并发</b> —— 需要<b>同时</b>打多个请求（矩阵是串行的）；</li>
 *   <li><b>超时</b> —— 需要下游<b>慢</b>（不是缺席）；</li>
 *   <li><b>部分成功</b> —— 需要一次多步写在<b>中途</b>失败；</li>
 *   <li><b>Redis 故障</b> —— 需要 Redis <b>不可用</b>；</li>
 *   <li><b>重复请求（幂等重放）</b> —— 需要同一个请求<b>连发两次</b>（矩阵是<b>单请求</b>的）。</li>
 * </ol>
 * ⇒ ⇒ 本探针只负责「**把动作打出去，把结果渲染出来**」；
 * ⇒ ⇒ 故障注入（慢下游 / 触发器 / 停 Redis）由编排脚本 {@code scenarios.py} 负责。
 *
 * <h2>🔴 归一化：只有 id 会被抹平</h2>
 * <p>场景里新建的收藏夹 id 是<b>雪花 ID</b> ⇒ ⇒ 两侧各自生成 ⇒ ⇒ 必然不同
 * ⇒ ⇒ 只把 {@code "folderId":"<数字>"} 抹成 {@code "folderId":"<ID>"}。
 * <p>⚠️ <b>抹平 id 会掩盖「id 缺失 / id 非正」这类差异</b> ⇒ ⇒ 所以额外单独输出
 * {@code folderIdPositive} 字段 ⇒ ⇒ 由比对器做<b>绝对断言</b>（两侧都必须为 true）
 * ⇒ ⇒ 「抹平的是值，不是存在性」。
 *
 * <h2>⚠️ 本层<b>没有第三方锚</b></h2>
 * <p>Go 真源侧跑不起来（本机无 {@code Sea-etcd}）⇒ ⇒ 这 6 类场景**只有 B↔C 双侧活体**
 * ⇒ ⇒ 证据强度**低于** 32 case 的「三方」⇒ ⇒ 比对器必须给出<b>绝对断言</b>来补上这个缺口
 * ⇒ ⇒ 不能只比「两侧相等」（两侧都坏也算相等）。
 *
 * <h2>§3.2 三段式（AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>新增一类测试</b> —— B 侧的探针只做单请求矩阵
 *       ⇒ ⇒ 本类是 C 侧新增的证据面。</li>
 *   <li><b>语义还一样吗</b>：不适用（本类只打请求，不判定 B/C 等价）。</li>
 *   <li><b>B 出处</b>：被测契约见 B 侧
 *       {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/controller/FavoriteController.java}
 *       （7 条路由）与
 *       {@code service/favorite/favorite-rpc/src/main/java/com/rag/favorite/favorite/rpc/logic/create_favorite_folder_logic.go}。</li>
 * </ol>
 */
public final class FavoriteScenarioProbe {

    private static final String JSON = "application/json";

    /** 并发场景的同时发起数。 */
    private static final int CONCURRENT_N = 8;

    /**
     * 🔴 id 归一化：只抹 {@code "folderId":"<数字>"} 与 {@code "favoriteId":"<数字>"}。
     * <p>⚠️ **刻意不抹** {@code createdAt} / {@code updatedAt}（应用层时钟 ⇒ 两侧本就可能差几毫秒）
     * ⇒ ⇒ 抹掉会掩盖真差异 ⇒ ⇒ 但也意味着：凡响应里带时间戳的场景，两侧<b>不会</b>逐字节相等
     * ⇒ ⇒ 比对器要按场景挑断言面。
     */
    private static final Pattern FOLDER_ID = Pattern.compile("\"folderId\"\\s*:\\s*\"\\d+\"");
    private static final Pattern FAVORITE_ID = Pattern.compile("\"favoriteId\"\\s*:\\s*\"\\d+\"");

    public static void main(String[] args) throws Exception {
        String stage = null;
        for (int i = 0; i < args.length; i++) {
            if ("--stage".equals(args[i]) && i + 1 < args.length) {
                stage = args[i + 1];
                i++;
            }
        }
        if (stage == null || stage.isBlank()) {
            System.err.println("用法: FavoriteScenarioProbe --stage <stage>");
            System.exit(2);
            return;
        }

        try {
            String bBase = env("PROBE_B_API");
            String cBase = env("PROBE_C_API");

            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            Map<String, String> bOut = new LinkedHashMap<>();
            Map<String, String> cOut = new LinkedHashMap<>();

            String b = runStage(stage, client, bBase);
            String c = runStage(stage, client, cBase);

            bOut.put(stage, b);
            cOut.put(stage, c);

            writeLines(env("PROBE_OUT_B"), bOut);
            writeLines(env("PROBE_OUT_C"), cOut);
            System.out.println("scenario probe ok: stage=" + stage);
        } catch (IllegalStateException e) {
            System.err.println("scenario probe 环境问题: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("scenario probe 失败: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ══════════════════════════ 阶段实现 ══════════════════════════

    /**
     * S6 · <b>重复请求（幂等重放）</b> —— 同一个请求<b>连发两次</b>，看两侧的第二次是什么。
     *
     * <h2>为什么要有这一层（verification.md §九 的「幂等和重复请求 / 重复请求」）</h2>
     * <p>矩阵（32 case）是<b>单请求</b>的 ⇒ ⇒ 同一个请求发两次会得到什么，矩阵<b>结构性测不到</b>。
     * <p>⇒ ⇒ 这里把 favorite 域真实存在的<b>三种重复语义</b>各打一次：
     * <ol>
     *   <li><b>建夹重放</b> ⇒ 第二次撞<b>重名预查</b> ⇒ <b>5203</b>
     *       （B 侧 {@code create_favorite_folder_logic.go:75-87}：重名预查命中 ⇒ AlreadyExists/5203）；</li>
     *   <li><b>收藏重放</b> ⇒ 第二次撞<b>重复收藏判定</b> ⇒ <b>5302</b>
     *       （B 侧 {@code create_favorite_logic.go:122-134}：{@code findByFolderTarget} 命中 ⇒ AlreadyExists/5302）；</li>
     *   <li><b>改名幂等</b> ⇒ 第二次<b>同名</b> ⇒ <b>直接成功、且不写库</b> ⇒ <b>200</b> 且 {@code updated_at} <b>不动</b>
     *       （B 侧 {@code update_favorite_folder_name_logic.go:91-95}：「新名字 == 旧名字」⇒ 直接返回成功，<b>不写库</b>
     *       ⇒ 这是 <b>L2 语义</b>，不是优化 ⇒ <b>updated_at 不会被推进</b>）。</li>
     * </ol>
     *
     * <h2>🔴 三个子场景里只有第 3 个是「幂等」</h2>
     * <p>前两个是「<b>重复请求被拒</b>」（重复 ⇒ 不产生第二条副作用）；
     * 第三个才是「<b>幂等</b>」（重复 ⇒ <b>返回同样结果且不产生任何副作用</b>，连 {@code updated_at} 都不推进）。
     * <p>⇒ ⇒ 三者都要测 ⇒ ⇒ 只测一个都答不出「重复请求」这一行。
     *
     * <h2>§3.2 三段式（AGENTS.md §3.2）</h2>
     * <ol>
     *   <li><b>比 B 侧多做了什么</b>：<b>新增一类测试</b> —— B 侧的探针只做单请求矩阵
     *       ⇒ ⇒ 本方法是 C 侧新增的证据面（B 侧<b>没有任何</b>「重复请求」探针）。</li>
     *   <li><b>语义还一样吗</b>：不适用（本方法只打请求，不判定 B/C 等价）。</li>
     *   <li><b>B 出处</b>：被测契约见上面三条的 B 侧文件与行号；
     *       路由见 B 侧 {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/controller/FavoriteController.java}
     *       （{@code POST /favorite/v1/folder/create} · {@code POST /favorite/v1/item/create} · {@code POST /favorite/v1/folder/update}）。</li>
     * </ol>
     */
    private static String replay(HttpClient client, String base) throws Exception {
        // ── ① 建夹重放：同名建两次 ⇒ 第二次 5203 ──────────────────────────
        String folderCreate1 = render(call(client, base, "POST", "/folder/create", null, token(), true,
                "{\"name\":\"ReplayFolder\"}"));
        String folderCreate2 = render(call(client, base, "POST", "/folder/create", null, token(), true,
                "{\"name\":\"ReplayFolder\"}"));

        // ── ② 收藏重放：同一 (folderId, targetId, targetType) 建两次 ⇒ 第二次 5302 ──
        //    ⚠️ targetId 用 90002（**不是** S4 的 90001）⇒ ⇒ 两个场景互不干扰。
        //    ⚠️ targetType 必须是 article（favorite_item 的 CHECK 要求 article 分支带非空修订号）。
        String itemCreate1 = render(call(client, base, "POST", "/item/create", null, token(), true,
                "{\"folderId\":\"9001\",\"targetId\":\"90002\",\"targetType\":\"article\"}"));
        String itemCreate2 = render(call(client, base, "POST", "/item/create", null, token(), true,
                "{\"folderId\":\"9001\",\"targetId\":\"90002\",\"targetType\":\"article\"}"));

        // ── ③ 改名幂等：改成**当前的名字** ⇒ 直接成功且不写库 ─────────────
        //    ⚠️ 用 9002（种子里的 'Beta'）⇒ 第一次就是「同名」⇒ 两次都不该推进 updated_at。
        String rename1 = render(call(client, base, "POST", "/folder/update", null, token(), true,
                "{\"folderId\":\"9002\",\"name\":\"Beta\"}"));
        String rename2 = render(call(client, base, "POST", "/folder/update", null, token(), true,
                "{\"folderId\":\"9002\",\"name\":\"Beta\"}"));

        StringBuilder sb = new StringBuilder();
        sb.append("folderCreate1=").append(field(folderCreate1, "biz"));
        sb.append("|folderCreate2=").append(field(folderCreate2, "biz"));
        sb.append("|itemCreate1=").append(field(itemCreate1, "biz"));
        sb.append("|itemCreate2=").append(field(itemCreate2, "biz"));
        sb.append("|rename1=").append(field(rename1, "biz"));
        sb.append("|rename2=").append(field(rename2, "biz"));
        // 🔴 改名响应体是固定形状（{"success":true}）⇒ ⇒ 两侧可逐字节比 ⇒ ⇒ 顺带钉一个 L1 面。
        sb.append("|renameBody=").append(field(rename2, "body"));
        return sb.toString();
    }

    private static String runStage(String stage, HttpClient client, String base) throws Exception {
        switch (stage) {
            case "create_ok":
                return createOk(client, base);
            case "concurrent":
                return concurrent(client, base);
            case "timeout":
                return timeout(client, base);
            case "read_folders":
                return render(call(client, base, "GET", "/folder/list", null, token(), false, null));
            case "create_item":
                return createItem(client, base);
            case "replay":
                return replay(client, base);
            case "delete_folder":
                return render(call(client, base, "DELETE", "/folder/delete", null, token(), true,
                        "{\"folderId\":\"9001\"}"));
            default:
                throw new IllegalStateException("未知 stage: " + stage);
        }
    }

    /** create 成功路径：下游在场 ⇒ 期望 200 + data.folderId 为正。 */
    private static String createOk(HttpClient client, String base) throws Exception {
        long t0 = System.nanoTime();
        HttpResponse<String> resp = call(client, base, "POST", "/folder/create", null, token(), true,
                "{\"name\":\"ScenarioFolder\"}");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        return render(resp) + "|elapsedBucket=" + bucket(elapsedMs);
    }

    /**
     * 并发：N 个<b>同时</b>发起、<b>同名</b>的建夹请求。
     *
     * <p>🔴 为什么要同名：唯一索引 {@code uk_user_folder_name (user_id, name)}
     * ⇒ ⇒ 并发下<b>恰好一个</b>该成功，其余全部撞唯一键 ⇒ ⇒ 业务码全是 5203。
     * ⇒ ⇒ 这条断言<b>同时</b>验了三件事：① 并发下「先查重后插入」不会写出两个同名夹
     * ② 唯一键兜住了竞态 ③ 失败方的业务码是 5203（不是 5008 之类的 DB 错误）。
     *
     * <p>🔴 渲染用<b>分布</b>（按业务码聚合计数）而不是逐条序列 ⇒ ⇒ 与线程调度顺序无关 ⇒ ⇒ 可逐字节比。
     * <p>🔴 <b>成功体与失败体必然不同</b>（一个是成功信封、一个是错误信封）⇒ ⇒
     * **不能**断言「所有响应体相同」⇒ ⇒ 改成两个字段：
     * {@code okBody}（成功那一条的归一化体）与 {@code failBodiesDistinct}（失败体的<b>去重</b>个数 ⇒ 期望 1）。
     */
    private static String concurrent(HttpClient client, String base) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_N);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_N; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    HttpResponse<String> resp = call(client, base, "POST", "/folder/create", null, token(), true,
                            "{\"name\":\"ConcurrentFolder\"}");
                    return render(resp);
                }));
            }
            gate.countDown();

            Map<String, Integer> dist = new TreeMap<>();
            java.util.Set<String> failBodies = new java.util.LinkedHashSet<>();
            java.util.Set<String> okBodies = new java.util.LinkedHashSet<>();
            for (Future<String> f : futures) {
                String rendered = f.get();
                String biz = field(rendered, "biz");
                dist.merge(biz, 1, Integer::sum);
                if ("200".equals(biz)) {
                    okBodies.add(field(rendered, "body"));
                } else {
                    failBodies.add(field(rendered, "body"));
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("n=").append(CONCURRENT_N).append("|dist=");
            dist.forEach((k, v) -> sb.append(k).append(':').append(v).append(','));
            sb.append("|okBodiesDistinct=").append(okBodies.size());
            sb.append("|okBody=").append(okBodies.isEmpty() ? "-" : okBodies.iterator().next());
            sb.append("|failBodiesDistinct=").append(failBodies.size());
            return sb.toString();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 新建收藏条目 —— 走 <b>「写 item + 写 outbox 事实」</b>这条双写事务。
     *
     * <p>🔴 为什么要单独一个 stage：这是 favorite 域**唯一**能在一次请求里写两行的地方
     * ⇒ ⇒ 「部分成功」（一半落库）只可能在这里出现 ⇒ ⇒ 原子性断言打在这儿。
     *
     * <p>⚠️ {@code targetType} 必须是 {@code article}：{@code favorite_item} 的 CHECK
     * {@code (target_revision IS NULL OR (target_type = 'article' AND target_revision <> ''))}
     * ⇒ ⇒ 走 article 分支才能带上非空修订号。
     */
    private static String createItem(HttpClient client, String base) throws Exception {
        long t0 = System.nanoTime();
        HttpResponse<String> resp = call(client, base, "POST", "/item/create", null, token(), true,
                "{\"folderId\":\"9001\",\"targetId\":\"90001\",\"targetType\":\"article\"}");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        return render(resp) + "|elapsedBucket=" + bucket(elapsedMs);
    }

    /**
     * 超时：下游<b>慢</b>（不是缺席）⇒ ⇒ 期望撞出向 deadline。
     *
     * <p>🔴 为什么必须测「慢」而不是「缺席」：缺席是<b>立即失败</b>（连不上）
     * ⇒ ⇒ 压根不会触发任何<b>出向预算</b> ⇒ ⇒ 两侧的「出向上界是否同值」根本测不出来。
     * ⇒ ⇒ 慢下游才能让「出向上界」真正生效 ⇒ ⇒ 才问得出「两侧的预算同不同」。
     */
    private static String timeout(HttpClient client, String base) throws Exception {
        long t0 = System.nanoTime();
        HttpResponse<String> resp = call(client, base, "POST", "/folder/create", null, token(), true,
                "{\"name\":\"TimeoutFolder\"}");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        return render(resp) + "|elapsedBucket=" + bucket(elapsedMs);
    }

    // ══════════════════════════ HTTP ══════════════════════════

    private static HttpResponse<String> call(HttpClient client, String base, String method, String path,
                                             String query, String auth, boolean json, String body) throws Exception {
        String url = base + path + (query == null ? "" : "?" + query);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        if (json) {
            b.header("Content-Type", JSON);
        }
        if (body != null) {
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ══════════════════════════ 渲染 ══════════════════════════

    /**
     * 渲染格式：{@code status=<code>|ct=<ct>|biz=<业务码>|folderIdPositive=<bool>|body=<body>}
     *
     * <p>🔴 <b>与矩阵探针的渲染口径一致</b>（{@code status= / ct= / body= / biz=}）
     * ⇒ ⇒ 同一套「业务码从 body 里反查」的判据 ⇒ ⇒ 两层口径不分叉。
     */
    private static String render(HttpResponse<String> resp) {
        String body = resp.body() == null ? "" : resp.body();
        String normalized = normalizeIds(body);
        return "status=" + resp.statusCode()
                + "|ct=" + normalizeContentType(resp.headers().firstValue("content-type").orElse("-"))
                + "|biz=" + bizCode(body)
                + "|folderIdPositive=" + folderIdPositive(body)
                + "|body=" + escape(normalized);
    }

    private static String normalizeIds(String body) {
        String s = FOLDER_ID.matcher(body).replaceAll("\"folderId\":\"<ID>\"");
        return FAVORITE_ID.matcher(s).replaceAll("\"favoriteId\":\"<ID>\"");
    }

    /** 从 body 里取 {@code folderId} 的数值形态 ⇒ 判断它是不是「一个正数」。 */
    private static String folderIdPositive(String body) {
        Matcher m = Pattern.compile("\"folderId\"\\s*:\\s*\"?(\\d+)\"?").matcher(body);
        if (!m.find()) {
            return "absent";
        }
        try {
            return Long.parseLong(m.group(1)) > 0 ? "true" : "false";
        } catch (NumberFormatException e) {
            return "false";
        }
    }

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
        return ct.replaceAll(";\\s*", ";").trim();
    }

    private static String bizCode(String body) {
        if (body == null || body.isEmpty()) {
            return "-";
        }
        Matcher m = Pattern.compile("\"code\"\\s*:\\s*(\\d+)").matcher(body);
        return m.find() ? m.group(1) : "-";
    }

    /** 从渲染值里取某个字段（{@code key=value}）。 */
    private static String field(String rendered, String key) {
        for (String part : rendered.split("\\|")) {
            if (part.startsWith(key + "=")) {
                return part.substring(key.length() + 1);
            }
        }
        return "-";
    }

    /**
     * 耗时分桶（500ms 一档）。
     *
     * <p>🔴 为什么分桶而不是比毫秒数：两侧的<b>机器负载、调度抖动</b>不同 ⇒ ⇒ 精确毫秒**必然不等**
     * ⇒ ⇒ 逐毫秒比会把「同一语义」判成差异 ⇒ ⇒ 分桶只问「落在同一个区间吗」。
     * ⇒ ⇒ 分桶宽度取 500ms ⇒ 远小于出向预算（2000ms）⇒ ⇒ 预算同值时两侧必落同一桶；
     * ⇒ ⇒ 而预算不同（如一侧 2s、一侧无上限）⇒ ⇒ 桶会差得很远 ⇒ ⇒ 判据有牙。
     */
    private static String bucket(long elapsedMs) {
        long b = (elapsedMs / 500L) * 500L;
        return b + "-" + (b + 499);
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

    private static String token() {
        return "Bearer " + env("PROBE_TOKEN_VALID_USER1");
    }

    private FavoriteScenarioProbe() {
    }
}
