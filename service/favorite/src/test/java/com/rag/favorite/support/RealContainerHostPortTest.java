package com.rag.favorite.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RealContainerHostPort} 的守门测试 —— 防止「钉宿主口」这个机制<b>自己被改坏</b>。
 *
 * <h2>为什么必须有它</h2>
 * <p>钉口这件事<b>本身没有网络副作用</b>：绑错了、段越了、落进 OS 临时端口段了，
 * 测试<b>照样全绿</b> ⇒ ⇒ 靠「跑绿」判不出来 ⇒ ⇒ 必须有这条守门把
 * <b>「机制是否真的生效」</b>与<b>「分配是否仍然合规」</b>都钉成断言。
 *
 * <h2>三条守的是什么</h2>
 * <ol>
 *   <li><b>分配合规</b>：登记的口必须落在 {@link RealContainerHostPort#FIRST}–{@link RealContainerHostPort#LAST}
 *       内、互不重复、且<b>不落进 OS 临时端口段</b>；</li>
 *   <li><b>守恒</b>：本模块<b>每个</b>「起容器」的测试类都必须钉口，
 *       且引用的口必须<b>在白名单里</b>、<b>恰好被引用一次</b>
 *       ⇒ ⇒ 「新增一个测试类却忘了钉口」与「登记了却没人用」都<b>会被抓住</b>；</li>
 *   <li><b>机制生效</b>（活体）：钉了口的容器，{@code getMappedPort()} 必须<b>返回那个固定口</b>，
 *       且那个口上<b>真的就是我的容器</b>（RESP 实测应答）。</li>
 * </ol>
 *
 * <h2>为什么第 ③ 条必须是「活体」而不是「断言配置」</h2>
 * <p>钉口是 <b>Docker 侧</b>的行为：{@code withCreateContainerCmdModifier} 改的是
 * {@code CreateContainerCmd} ⇒ ⇒ <b>光看代码/注解看不出来它有没有生效</b>
 * ⇒ ⇒ 唯一可靠的判据是<b>运行时证据</b>：容器起来之后，宿主机上那个固定口
 * 必须真的是它的端口（AGENTS.md §3.3「注解存在 ≠ 机制生效」）。
 *
 * <h2>⚠️ 源扫必须先剥注释</h2>
 * <p>本类的扫描面是「源码里<b>真的</b>调了钉口助手」⇒ ⇒ <b>注释与 javadoc 里的示例不能进扫描面</b>
 * ⇒ ⇒ 扫之前<b>先剥注释</b>（状态机实现，<b>不动字符串字面量</b>）
 * ⇒ ⇒ 否则「javadoc 里写了个示例常量名」会被当成「代码里真的用了它」
 * ⇒ ⇒ 守门会报一条**假红**。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>包名</b>压平（D7）：
 *             {@code com.rag.favorite.favorite.api.support} ⇒ {@code com.rag.favorite.support}；</li>
 *         <li><b>模块路径</b>：B 侧 favorite-api 是独立 Maven 模块
 *             （{@code service/favorite/favorite-api/}）⇒ C 侧 D7 打平为<b>一个</b> jar 模块
 *             {@code service/favorite/} ⇒ ⇒ 源扫根目录随之改成
 *             {@code service/favorite/src/test/java}；</li>
 *         <li><b>③ 的活体探针</b>：B 侧用 {@code Socket} 直连 Redis 端口发 {@code PING}
 *             ⇒ C 侧<b>逐字沿用</b>（唯一的差别是断言消息里的类名引用）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— 三条守门的判据、源扫面（剥注释）、
 *       白名单语义（登记册 + {@code GATE_LIVE}）逐条未改。</li>
 *   <li><b>B 出处</b>：B 侧
 *       {@code service/favorite/favorite-api/src/test/java/com/rag/favorite/favorite/api/support/RealContainerHostPortTest.java:31-286}
 *       （含 {@code stripComments} 状态机实现 :240-285）。</li>
 * </ol>
 */
class RealContainerHostPortTest {

    /** 识别「本模块起容器」的源码特征。 */
    private static final Pattern CONTAINER_DECL =
            Pattern.compile("new\\s+(?:PostgreSQLContainer|GenericContainer)\\b");

    /** 识别「钉口调用」：{@code RealContainerHostPort.bindPg(RealContainerHostPort.XXX)} 这种形态。 */
    private static final Pattern PIN_CALL = Pattern.compile(
            "RealContainerHostPort\\.bind(?:Pg|Redis|To)?\\(\\s*RealContainerHostPort\\.([A-Z][A-Z0-9_]*)\\s*\\)");

    // ── ① 分配合规 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("登记册里的每个宿主口都必须在本模块的宿主口段内、互不重复、且不落进 OS 临时端口段")
    void registeredPortsAreInsideTheSegmentUniqueAndOutOfTheEphemeralRange() {
        String[] names = RealContainerHostPort.TEST_CLASS_PORT_NAMES;
        assertTrue(names.length > 0, "登记册不能是空的");

        Map<Integer, String> seen = new HashMap<>();
        for (String name : names) {
            int port = RealContainerHostPort.portOf(name);
            assertTrue(port >= RealContainerHostPort.FIRST && port <= RealContainerHostPort.LAST,
                    "宿主口 " + port + "（" + name + "）越出宿主口段 "
                            + RealContainerHostPort.FIRST + "-" + RealContainerHostPort.LAST);
            assertFalse(port >= RealContainerHostPort.OS_EPHEMERAL_BEGIN
                            && port <= RealContainerHostPort.OS_EPHEMERAL_END,
                    "宿主口 " + port + "（" + name + "）落在 OS 临时端口段 "
                            + RealContainerHostPort.OS_EPHEMERAL_BEGIN + "-"
                            + RealContainerHostPort.OS_EPHEMERAL_END);
            assertFalse(seen.containsKey(port),
                    "宿主口 " + port + " 被重复分配（" + name + " 与 " + seen.get(port) + "）");
            seen.put(port, name);
        }
    }

    @Test
    @DisplayName("宿主口段与脚手架段必须彼此不重叠、且都落在 favorite 域段内")
    void segmentsDoNotOverlapAndStayInsideTheDomainSegment() {
        assertTrue(RealContainerHostPort.DOMAIN_SEGMENT_BEGIN < RealContainerHostPort.DOMAIN_SEGMENT_END,
                "域段边界写反了");
        assertTrue(RealContainerHostPort.FIRST >= RealContainerHostPort.DOMAIN_SEGMENT_BEGIN
                        && RealContainerHostPort.LAST <= RealContainerHostPort.DOMAIN_SEGMENT_END,
                "宿主口段必须落在域段 " + RealContainerHostPort.DOMAIN_SEGMENT_BEGIN + "-"
                        + RealContainerHostPort.DOMAIN_SEGMENT_END + " 内");
        assertTrue(RealContainerHostPort.SCRATCH_FIRST >= RealContainerHostPort.DOMAIN_SEGMENT_BEGIN
                        && RealContainerHostPort.SCRATCH_LAST <= RealContainerHostPort.DOMAIN_SEGMENT_END,
                "脚手架段必须落在域段内");
        assertTrue(RealContainerHostPort.LAST < RealContainerHostPort.SCRATCH_FIRST,
                "宿主口段与脚手架段重叠了 ⇒ 守门会和测试类抢同一个口");
    }

    // ── ② 守恒：源扫 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("本模块每个「起容器」的测试类都必须钉到本域宿主口段（漏钉 ⇒ 抓出来）")
    void everyContainerDeclaringTestClassIsPinned() throws IOException {
        List<String> unpinned = new ArrayList<>();
        for (Path file : testSourceFiles()) {
            String code = stripComments(read(file));
            if (!CONTAINER_DECL.matcher(code).find()) {
                continue;
            }
            if (!PIN_CALL.matcher(code).find()) {
                unpinned.add(rel(file));
            }
        }
        assertTrue(unpinned.isEmpty(),
                "这些测试类起了容器但**没有钉宿主口** ⇒ 它们仍会落进 OS 临时端口段：\n  "
                        + String.join("\n  ", unpinned));
    }

    @Test
    @DisplayName("被引用的宿主口必须在白名单里、且每个登记口恰好被引用一次（守恒）")
    void everyPinnedPortIsRegisteredAndUsedExactlyOnce() throws IOException {
        Map<String, Integer> usage = new LinkedHashMap<>();
        for (Path file : testSourceFiles()) {
            String code = stripComments(read(file));
            Matcher matcher = PIN_CALL.matcher(code);
            while (matcher.find()) {
                usage.merge(matcher.group(1), 1, Integer::sum);
            }
        }

        // 白名单 = 登记册里的常量名 + 守门自己那个口（脚手架段）
        Set<String> allowed = new HashSet<>(Arrays.asList(RealContainerHostPort.TEST_CLASS_PORT_NAMES));
        allowed.add("GATE_LIVE");

        List<String> notAllowed = new ArrayList<>();
        for (String name : usage.keySet()) {
            if (!allowed.contains(name)) {
                notAllowed.add(name);
            }
        }
        assertTrue(notAllowed.isEmpty(),
                "这些宿主口常量不在白名单里 ⇒ 守门对不上账（或它越到了脚手架段）：\n  "
                        + String.join("\n  ", notAllowed));

        List<String> duplicated = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : usage.entrySet()) {
            if (entry.getValue() > 1) {
                duplicated.add(entry.getKey() + " 被引用 " + entry.getValue() + " 次");
            }
        }
        assertTrue(duplicated.isEmpty(),
                "同一个宿主口被多个测试类复用 ⇒ 会撞「上一个类刚停、下一个类立刻抢同一个口」：\n  "
                        + String.join("\n  ", duplicated));

        List<String> orphans = new ArrayList<>();
        for (String name : RealContainerHostPort.TEST_CLASS_PORT_NAMES) {
            if (!usage.containsKey(name)) {
                orphans.add(name + "(" + RealContainerHostPort.portOf(name) + ")");
            }
        }
        assertTrue(orphans.isEmpty(),
                "登记册里这些口**没有任何测试类在用** ⇒ 登记册与源码脱节：\n  "
                        + String.join("\n  ", orphans));
    }

    // ── ③ 机制生效（活体） ─────────────────────────────────────────────────────

    @Test
    @DisplayName("钉了口的 Redis 容器：getMappedPort() 必须返回那个固定口，且那个口上就是我的 Redis")
    void pinnedRedisReportsTheFixedHostPortAndAnswersPing() throws Exception {
        try (org.testcontainers.containers.GenericContainer<?> container =
                new org.testcontainers.containers.GenericContainer<>("redis:7-alpine")
                        .withExposedPorts(6379)
                        .withCreateContainerCmdModifier(
                                RealContainerHostPort.bindRedis(RealContainerHostPort.GATE_LIVE))) {
            container.start();

            assertEquals(RealContainerHostPort.GATE_LIVE, container.getMappedPort(6379),
                    "getMappedPort() 必须返回钉的那个固定口（钉口没生效？）");

            try (Socket socket = new Socket("127.0.0.1", RealContainerHostPort.GATE_LIVE)) {
                socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                byte[] buffer = new byte[64];
                int read = socket.getInputStream().read(buffer);
                String reply = read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.US_ASCII);
                assertTrue(reply.startsWith("+PONG"),
                        "那个口上必须是我的 Redis（实际回应：" + reply + "）");
            }
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    /**
     * 本模块的测试源码根目录。
     *
     * <p>⚠️ B 侧是 {@code service/favorite/favorite-api/src/test/java}（独立 Maven 模块）
     * ⇒ C 侧 D7 打平后是 {@code service/favorite/src/test/java}（唯一可写路径）。
     */
    private static Path moduleTestSourceRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 10 && dir != null; i++) {
            Path candidate = dir.resolve("service/favorite/src/test/java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 service/favorite/src/test/java");
    }

    private static List<Path> testSourceFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(moduleTestSourceRoot())) {
            return stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String rel(Path file) {
        return moduleTestSourceRoot().relativize(file).toString();
    }

    /**
     * <b>剥注释</b>（状态机实现，<b>不动字符串字面量</b>）⇒ 扫描面只剩真代码。
     *
     * <p>注释内容替换成<b>等长空格</b>（换行保留）⇒ 行号不变 ⇒ 报错信息仍能指到原行。
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < n) {
                    if (src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/') {
                        out.append("  ");
                        i += 2;
                        break;
                    }
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
