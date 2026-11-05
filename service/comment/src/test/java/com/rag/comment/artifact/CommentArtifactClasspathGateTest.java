package com.rag.comment.artifact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>产物层守门（不需要 Docker / 不需要起进程）</b> —— 断言本模块的
 * <b>运行时 classpath</b> 与 <b>fat jar</b> 上<b>真的有</b> {@code io.grpc:grpc-inprocess}。
 *
 * <h2>B 出处</h2>
 * <p>逐条移植自 B 侧
 * {@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentRpcArtifactClasspathGateTest.java:1-247}
 * （B 侧 3 条守门点 ＋ C 侧新增第 ④ 条）。
 *
 * <h2>它守的是什么缺陷（本轮真的踩中了 ⇒ 已修）</h2>
 * <p>Maven「<b>最近者优先</b>」：本模块<b>直接声明</b>的 {@code io.grpc:grpc-inprocess}
 * 压过 devh starter（{@code net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE}）
 * 传递进来的 <b>compile</b> 声明 ⇒ scope 一旦写成 {@code test}，
 * <b>运行时 classpath 上整个 artifact 消失</b>（fat jar 的 {@code BOOT-INF/lib/} 里也没有它）
 * ⇒ devh 的 {@code InProcessChannelFactory} 链接失败 ⇒
 * {@code NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder}
 * ⇒ <b>整个服务起不来</b>（硬失败，不是静默失效）。
 *
 * <p><b>本轮实测（修前）</b>：
 * <ul>
 *   <li>{@code mvn -o -pl service/comment dependency:list} ⇒ {@code io.grpc:grpc-inprocess:jar:1.63.0:test}；</li>
 *   <li>修前 fat jar 的 {@code BOOT-INF/lib/} 里<b>没有</b> {@code grpc-inprocess}；</li>
 *   <li>{@code java -jar …/comment-service-1.0-SNAPSHOT.jar} ⇒
 *       {@code NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder}
 *       ⇒ <b>整个服务起不来</b>；</li>
 *   <li>⚠️ <b>修前 {@code mvn -o -pl service/comment -am test} 是全绿的</b> ——
 *       测试跑在 <b>test classpath</b> 上 ⇒ grpc-inprocess 在那儿 ⇒ <b>缺陷看不见</b>
 *       ⇒ 这是 AGENTS.md §3.3.3「<b>跑绿 ≠ 本来就绿</b>」的教科书形态。</li>
 * </ul>
 *
 * <h2>为什么这三条断言在 <b>test classpath</b> 上是测不出来的</h2>
 * <p>测试跑在 test classpath 上 ⇒ {@code grpc-inprocess} 本来就在 ⇒
 * 「pom 里 scope 写错」这件事<b>永远测不到</b>；而所有 wiring 测试又把地址覆盖成
 * {@code static://127.0.0.1:1} ⇒ 生产地址也永远测不到。
 * ⇒ 这正是 AGENTS.md §3.3 说的「<b>跑绿 ≠ 对</b>」。
 * ⇒ 所以本类解析的是 <b>runtime classpath</b>（{@code -DincludeScope=runtime}
 * ⇒ 与 fat jar 的 {@code BOOT-INF/lib} 同源），并<b>解包真 fat jar</b> 看条目在不在。
 *
 * <h2>为什么本类<b>不</b>需要 Docker</h2>
 * <p>它只看<b>产物</b>（classpath + jar 条目），不起进程、不连库 ⇒
 * <b>无 Docker 的 CI 也能跑</b> ⇒ 这是本缺陷的<b>环境无关</b>判据；
 * 进程级的「真起得来」由 {@link CommentRuntimeBootGateTest} 承担。
 *
 * <h2>L4 差异（AGENTS §3.2 三段式）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：新增第 <b>④</b> 条守门点 ——
 *       <b>直接解析本模块 {@code pom.xml} 的源码</b>，断言<b>没有</b>把
 *       {@code grpc-inprocess} 声明成 {@code test} scope。
 *       <br>理由：B 侧只能「看 classpath」（因为 B 侧那条声明在别的模块里）；
 *       而 C 侧<b>本来就不该有这条直接声明</b>
 *       （B 出处 = B 侧 {@code service/comment/comment-api/pom.xml:159-174}
 *       明写「🔴🔴 <b>刻意不在这里声明 grpc-inprocess</b>」）
 *       ⇒ ⇒ C 侧<b>能在源头就把「最近者优先」这个陷阱关掉</b>
 *       ⇒ ⇒ 这条比 B 侧<b>更强</b>：B 侧是「声明错了要能发现」，C 侧是「不许声明」。</li>
 *   <li><b>语义还一样吗</b>：一样。第 ①②③ 条与 B 侧<b>逐条等价</b>
 *       （期望值、判据、失败信息口径都照抄）；第 ④ 条是<b>只增不减</b>的加固，
 *       不改变任何运行时行为。</li>
 *   <li><b>B 出处</b>：B 侧
 *       {@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentRpcArtifactClasspathGateTest.java:1-247}
 *       ＋ {@code service/comment/comment-api/pom.xml:159-174}。</li>
 * </ol>
 */
class CommentArtifactClasspathGateTest {

    /** 本模块在仓库里的路径（相对仓库根）。 */
    static final String MODULE_PATH = "service/comment";

    /** 共享层模块路径（fat jar 内嵌的是它构建出来的 jar ⇒ 新鲜度要一起算）。 */
    static final String SHARED_LAYER_PATH = "common";

    /**
     * 共享层排序桥的<b>全限定名</b> —— <b>独立字面量 oracle</b>。
     *
     * <p>⚠️ <b>为什么不从共享层源码里读这个类名</b>：
     * 那样写的话，共享层<b>改包名</b>时这条断言会跟着一起变 ⇒ ⇒ 永远绿 ⇒ ⇒ 守门失效。
     * ⇒ 这里<b>写死字符串</b> ⇒ 共享层真改了包名 ⇒ 本条<b>如实转红</b> ⇒ ⇒ 提醒人同步改这里。
     *
     * <p>（C 侧的包名是 {@code com.rag.common.autoconfigure.…} —— 与 B 侧的
     * {@code com.rag.common.persistence.…} 不同 ⇒ 属 D3 共享层上收的落点差异。）
     */
    private static final String SHARED_LAYER_ORDERING_BRIDGE =
            "com.rag.common.autoconfigure.MybatisPlusOrderingBridgeAutoConfiguration";

    // ==================================================================
    // ① runtime classpath 上必须有 grpc-inprocess（scope 不得是 test）
    // ==================================================================

    @Test
    @DisplayName("运行时 classpath 上必须有 io.grpc:grpc-inprocess（scope 不得是 test）")
    void runtimeClasspathContainsGrpcInprocess() throws Exception {
        String classpath = runtimeClasspath();

        assertTrue(classpath.contains("grpc-inprocess"),
                "运行时 classpath 上没有 grpc-inprocess ⇒ devh 的 InProcessChannelFactory 链接失败 ⇒ "
                        + "NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder ⇒ "
                        + "**整个服务起不来**。"
                        + "根因是 Maven「最近者优先」：本模块的直接声明压过 devh starter 传递进来的 "
                        + "compile 声明 ⇒ scope 一旦写成 test，运行时 classpath 上整个 artifact 消失。"
                        + "修法见 B 侧 service/comment/comment-api/pom.xml:159-174"
                        + "（**刻意不在这里声明 grpc-inprocess**）⇒ 本模块应把该直接声明整条删除。");
    }

    // ==================================================================
    // ② 真 fat jar 里必须有 grpc-inprocess（test scope 的依赖不进 fat jar）
    // ==================================================================

    @Test
    @DisplayName("真 fat jar 的 BOOT-INF/lib 里必须有 grpc-inprocess（test scope 的依赖不进 fat jar）")
    void fatJarContainsGrpcInprocess() throws Exception {
        Path jar = fatJar();

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            boolean found = jarFile.stream()
                    .anyMatch(entry -> entry.getName().startsWith("BOOT-INF/lib/grpc-inprocess-"));
            assertTrue(found,
                    "fat jar " + jar.getFileName() + " 的 BOOT-INF/lib 里没有 grpc-inprocess ⇒ "
                            + "真启动会抛 NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder。");
        }
    }

    // ==================================================================
    // ③ fat jar 内嵌的 common jar 必须带共享层排序桥
    // ==================================================================

    @Test
    @DisplayName("fat jar 内嵌的 common jar 必须带共享层排序桥（域侧副本删掉后，排序边只能来自共享层）")
    void fatJarEmbeddedCommonJarCarriesSharedLayerOrderingBridge() throws Exception {
        Path jar = fatJar();

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry commonJar = jarFile.stream()
                    .filter(e -> e.getName().startsWith("BOOT-INF/lib/common-")
                            && e.getName().endsWith(".jar"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(commonJar,
                    "fat jar " + jar.getFileName() + " 的 BOOT-INF/lib 里没有内嵌的 common-*.jar"
                            + " ⇒ ⇒ 无从判断排序桥在不在 ⇒ ⇒ 这条守门的前提就断了");

            Path extracted = Files.createTempFile("embedded-common-", ".jar");
            try {
                try (InputStream in = jarFile.getInputStream(commonJar)) {
                    Files.copy(in, extracted, StandardCopyOption.REPLACE_EXISTING);
                }
                try (JarFile embedded = new JarFile(extracted.toFile())) {
                    JarEntry importsEntry = embedded.getJarEntry(
                            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
                    assertNotNull(importsEntry,
                            "内嵌的 common jar 里没有 AutoConfiguration.imports"
                                    + " ⇒ ⇒ 共享层的排序桥根本没被注册");
                    String imports;
                    try (InputStream in = embedded.getInputStream(importsEntry)) {
                        imports = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    assertTrue(imports.contains(SHARED_LAYER_ORDERING_BRIDGE),
                            "fat jar 内嵌的 common jar 的 AutoConfiguration.imports 里【没有】共享层排序桥（"
                                    + SHARED_LAYER_ORDERING_BRIDGE + "）"
                                    + " ⇒ ⇒ 域侧排序桥副本删掉后，MyBatis-Plus 的排序边就没了"
                                    + " ⇒ ⇒ MP 的 @AutoConfigureAfter 在 Boot 3.5 下悬空"
                                    + " ⇒ ⇒ MP 先于 DataSource 求值 ⇒ ⇒ 不产 SqlSessionFactory"
                                    + " ⇒ ⇒ 进程启动即挂"
                                    + "（Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required）"
                                    + " ⇒ ⇒ 常见根因 = 构建 fat jar 的命令【没带 -am】⇒ 从 ~/.m2 取了旧 common jar");
                }
            } finally {
                Files.deleteIfExists(extracted);
            }
        }
    }

    // ==================================================================
    // ④【C 侧新增 · 比 B 侧更强】pom 源码层：不得把 grpc-inprocess 直接声明成 test scope
    // ==================================================================

    @Test
    @DisplayName("【C 侧加固】本模块 pom 不得把 grpc-inprocess 直接声明成 test scope（Maven「最近者优先」的源头）")
    void pomDoesNotDeclareGrpcInprocessAsTestScope() throws Exception {
        Path pom = repoRoot().resolve(MODULE_PATH).resolve("pom.xml");
        assertTrue(Files.exists(pom), "找不到本模块的 pom.xml：" + pom);

        String xml = Files.readString(pom, StandardCharsets.UTF_8);

        // 只看 **<dependency> 块**：先定位 grpc-inprocess 那个 artifactId，
        // 再在它所在的 dependency 块里找 <scope>。
        int artifactAt = xml.indexOf("<artifactId>grpc-inprocess</artifactId>");
        if (artifactAt < 0) {
            // ✅ B 侧要求的形态：**刻意不声明** ⇒ 由 devh starter 传递（compile 作用域）
            //    ⇒ 「最近者优先」的陷阱在本模块根本不存在。
            return;
        }

        int depStart = xml.lastIndexOf("<dependency>", artifactAt);
        int depEnd = xml.indexOf("</dependency>", artifactAt);
        assertTrue(depStart >= 0 && depEnd > depStart,
                "pom.xml 里 grpc-inprocess 的 <dependency> 块解析失败（depStart=" + depStart
                        + ", depEnd=" + depEnd + "）");
        String block = xml.substring(depStart, depEnd);

        assertFalse(block.contains("<scope>test</scope>"),
                "本模块 pom 直接声明了 grpc-inprocess 且 scope=test ⇒"
                        + "它会【压过】net.devh:grpc-client-spring-boot-starter 传递进来的 compile 声明"
                        + "（Maven「最近者优先」）⇒ 运行时 classpath 上整个 artifact 消失"
                        + " ⇒ fat jar 的 BOOT-INF/lib 里也没有它"
                        + " ⇒ 真启动抛 NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder"
                        + " ⇒ **整个服务起不来**（而单元测试全绿）。"
                        + "修法 = **整条删除**该直接声明（B 出处 = B 侧 "
                        + "service/comment/comment-api/pom.xml:159-174："
                        + "「🔴🔴 刻意不在这里声明 grpc-inprocess」）。"
                        + "当前 pom 片段：\n" + block);
    }

    // ==================================================================
    // helpers（package-private ⇒ 同包的进程级守门类复用）
    // ==================================================================

    /** 解析本模块的<b>运行时</b> classpath（= fat jar 的 BOOT-INF/lib 那一层，不含 test scope）。 */
    static String runtimeClasspath() throws Exception {
        Path outputFile = Files.createTempFile("l12-comment-runtime-cp", ".txt");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                "mvn", "-o", "-q", "-pl", MODULE_PATH, "dependency:build-classpath",
                "-DincludeScope=runtime",
                "-Dmdep.outputFile=" + outputFile.toAbsolutePath()))
                .directory(repoRoot().toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        String log = drainToString(process);
        int exit = process.waitFor();
        assertEquals(0, exit, "解析运行时 classpath 失败（mvn dependency:build-classpath）：\n" + log);
        return Files.readString(outputFile, StandardCharsets.UTF_8);
    }

    /** 取本模块的 fat jar；缺失或比源码旧时重新构建（构建产物必须与当前源码一致）。 */
    static Path fatJar() throws Exception {
        Path moduleDir = repoRoot().resolve(MODULE_PATH);
        Path jar = newestFatJar(moduleDir.resolve("target"));
        if (jar == null || isStale(jar, moduleDir)) {
            buildFatJar();
            jar = newestFatJar(moduleDir.resolve("target"));
        }
        assertNotNull(jar, "找不到本模块的 fat jar（" + moduleDir.resolve("target") + " 下没有 *.jar）");
        return jar;
    }

    private static Path newestFatJar(Path target) throws IOException {
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (Stream<Path> files = Files.list(target)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith(".jar.original"))
                    .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        }
    }

    /**
     * jar 比 pom / 源码旧 ⇒ 构建产物是旧的 ⇒ 必须重建。
     *
     * <p>🔴 <b>共享层的源码也要算进「新鲜度」</b> ——
     * fat jar 里内嵌的是 {@code BOOT-INF/lib/common-*.jar}
     * ⇒ 共享层改了而本模块没改时，本模块的 fat jar <b>不会</b>自动重建
     * ⇒ ⇒ 内嵌的那份是旧的 ⇒ ⇒ 第 ③ 条守门会读到过期内容（假绿/假红都可能出现）。
     */
    private static boolean isStale(Path jar, Path moduleDir) throws IOException {
        long jarTime = jar.toFile().lastModified();
        List<Path> inputs = new ArrayList<>();
        inputs.add(moduleDir.resolve("pom.xml"));
        Path main = moduleDir.resolve("src").resolve("main");
        if (Files.isDirectory(main)) {
            try (Stream<Path> files = Files.walk(main)) {
                files.filter(Files::isRegularFile).forEach(inputs::add);
            }
        }
        Path sharedLayer = repoRoot().resolve(SHARED_LAYER_PATH);
        inputs.add(sharedLayer.resolve("pom.xml"));
        Path sharedMain = sharedLayer.resolve("src").resolve("main");
        if (Files.isDirectory(sharedMain)) {
            try (Stream<Path> files = Files.walk(sharedMain)) {
                files.filter(Files::isRegularFile).forEach(inputs::add);
            }
        }
        for (Path input : inputs) {
            if (Files.exists(input) && input.toFile().lastModified() > jarTime) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建 fat jar（跳过测试 —— 本类自己就是那个测试）。
     *
     * <p>🔴 <b>命令必须带 {@code -am}</b>：不带 ⇒ 共享层 {@code common} <b>不在 reactor 里</b>
     * ⇒ ⇒ fat jar 内嵌的 {@code BOOT-INF/lib/common-*.jar} 来自 <b>{@code ~/.m2}</b>
     * ⇒ ⇒ 那是<b>旧的</b>共享层包 ⇒ ⇒ 第 ③ 条守门会读到过期内容。
     */
    private static void buildFatJar() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(List.of(
                "mvn", "-o", "-q", "package", "-Dmaven.test.skip=true", "-am", "-pl", MODULE_PATH))
                .directory(repoRoot().toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        String log = drainToString(process);
        int exit = process.waitFor();
        assertEquals(0, exit, "构建 fat jar 失败：\n" + log);
    }

    /**
     * 向上找仓库根。
     *
     * <p>⚠️ <b>不能</b>只按「pom 里出现根 artifactId」来找 ——
     * 本模块的祖先里有 {@code service/pom.xml} 这个**中间聚合 pom**，
     * 它的 {@code <parent>} 也写着根 artifactId ⇒ 会误判成根。
     * ⇒ 这里取<b>最高</b>的、且<b>确实含有本模块路径</b>的那个祖先。
     */
    static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        Path found = null;
        for (int i = 0; i < 10 && dir != null; i++) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve(MODULE_PATH))) {
                found = dir;
            }
            dir = dir.getParent();
        }
        if (found == null) {
            throw new IllegalStateException(
                    "找不到仓库根目录（工作目录：" + Paths.get("").toAbsolutePath()
                            + "，要求祖先里有 " + MODULE_PATH + "）");
        }
        return found;
    }

    private static String drainToString(Process process) throws IOException {
        StringBuilder sink = new StringBuilder();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                sink.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        }
        return sink.toString();
    }
}
