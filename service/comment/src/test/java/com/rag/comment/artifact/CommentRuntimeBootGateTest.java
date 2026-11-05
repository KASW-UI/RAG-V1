package com.rag.comment.artifact;

import com.rag.comment.support.CommentTestSupport;
import com.rag.comment.support.TestPostgres;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🔴 <b>真启动守门（进程级）</b> —— 用<b>真 fat jar</b> 起本模块的<b>真 {@code CommentServiceApplication}</b> 进程，
 * 断言它<b>真的起来了</b>（gRPC 端口在监听）。
 *
 * <h2>B 出处</h2>
 * <p>逐条移植自 B 侧
 * {@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentRpcRuntimeBootGateTest.java:1-250}
 * （B 侧 1 条守门点 ＋ 4 条断言）。
 *
 * <h2>它守的是哪两类缺陷（都是「测试全绿、服务起不来」那一类）</h2>
 * <ol>
 *   <li><b>运行时 classpath 上缺 {@code io.grpc:grpc-inprocess}</b> ⇒
 *       {@code NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder}
 *       ⇒ <b>整个服务起不来</b>（产物层判据在 {@link CommentArtifactClasspathGateTest}，
 *       那条<b>不需要 Docker</b>）。
 *       <br>⚠️ <b>本轮真的踩中了</b>：C 侧 pom 曾把 grpc-inprocess 直接声明成 test scope
 *       ⇒ fat jar 起不来 ⇒ <b>已修</b>（见 C 侧 pom 的注释块 ＋ B 侧
 *       {@code service/comment/comment-api/pom.xml:159-174}）。</li>
 *   <li><b>生产 gRPC client 地址写成两斜杠</b> ⇒
 *       devh 判 {@code uri.getPath()} 长度 &gt; 1 ⇒ 两斜杠形式 path 是空串 ⇒
 *       {@code IllegalArgumentException: Incorrectly formatted target uri}
 *       ⇒ 炸在 {@code grpcClientBeanPostProcessor} 的 {@code @PostConstruct}
 *       ⇒ <b>连 Flyway 都跑不到</b>。</li>
 * </ol>
 *
 * <h2>为什么不能用「mvn test 的 classpath」当证据</h2>
 * <p>测试跑在 <b>test classpath</b> 上 ⇒ {@code grpc-inprocess} 本来就在 ⇒
 * 上面第 ① 类缺陷在测试里<b>永远测不到</b>；而所有 wiring 测试又把地址覆盖成
 * {@code static://127.0.0.1:1} ⇒ 第 ② 类缺陷也永远测不到。
 * ⇒ 所以本类用 {@code java -jar} 起<b>真 fat jar</b>（生产 classpath ＋ 生产 yaml）。
 *
 * <h2>本类<b>刻意不做</b>的事</h2>
 * <ul>
 *   <li>❌ <b>不覆盖</b> {@code grpc.client.*.address} ⇒ 生产地址原样生效
 *       （覆盖了就等于把被测对象换掉了）⇒ <b>断言的是「生产 yaml 原样起得来」</b>。</li>
 *   <li>❌ <b>不覆盖</b> Nacos / Kafka / Redis 的地址 ⇒ 只把它们指向<b>不可达</b>的地址
 *       （Nacos 注册失败只记日志、不中断启动 —— 与 Go 侧 etcd 注册失败同样只打日志）。
 *       ⇒ 本类<b>不验</b>「依赖在线时的启动面」，只验「<b>本模块自己的</b>配置与 classpath 能不能起」。</li>
 * </ul>
 *
 * <h2>L4 差异（AGENTS §3.2 三段式）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li>B 侧起的是 {@code CommentRpcApplication}（<b>rpc-only</b>进程，
 *             {@code spring.main.web-application-type: none}）；
 *             C 侧是 <b>api + rpc 合并</b>后的单一 {@code CommentServiceApplication}
 *             ⇒ 本类起的是<b>同一个</b>进程，<b>Tomcat 与 gRPC 同时起</b>
 *             ⇒ ⇒ HTTP 端口用 {@code --server.port=0}（随机口，避免与其它 lane 撞端口）；
 *             端口不是被测对象；</li>
 *         <li>C 侧多了一个 <b>JWT 密钥 fail-closed 守卫</b>（{@code CommentApiProperties}）
 *             ⇒ 子进程<b>必须</b>带 {@code USER_JWT_SECRET}，否则启动即失败
 *             ⇒ 这里传的是 {@link CommentTestSupport#SECRET}（与测试夹具同源）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样。四条断言的期望值与判据逐字照抄 B 侧；
 *       上面两处差异都是<b>进程形态</b>的必然结果（D1 合并 ＋ C 侧自带守卫），
 *       <b>不改变被守的行为</b>（仍是「真 classpath + 真 yaml 能不能起」）。</li>
 *   <li><b>B 出处</b>：B 侧
 *       {@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentRpcRuntimeBootGateTest.java:1-250}。</li>
 * </ol>
 *
 * <h2>环境依赖</h2>
 * <p>需要 Docker（真 PG 容器）⇒ 本类<b>刻意不用</b> {@code disabledWithoutDocker}
 * （AGENTS.md §五.8 ③：禁静默跳过）⇒ 无 Docker 时<b>如实变红</b>。
 */
class CommentRuntimeBootGateTest {

    /**
     * 🔴 devh 打印「gRPC Server started」的那一行标志串 —— <b>提为常量</b>。
     *
     * <p><b>为什么必须提常量</b>：本类的「<b>等这行出现</b>」（收摊前的等待循环）与
     * 「<b>断言这行在</b>」<b>两处都要用到同一个字面量</b> ⇒
     * 若任一处被改而另一处没改 ⇒ <b>等待与断言就不再配套</b> ⇒
     * 要么白等满上限后莫名失败，要么断言<b>平凡通过</b>（守门失效）。
     * ⇒ 提为常量 ⇒ <b>两处共用一个定义</b> ⇒ 不可能漂移。
     *
     * <p>（实测 devh 3.1.0.RELEASE 的字面量是
     * {@code "gRPC Server started, listening on address: {}, port: {}"}。）
     */
    private static final String GRPC_STARTED_MARKER = "gRPC Server started";

    /** 真进程的启动上限（秒）—— 冷启动要跑 Flyway 迁移，给足。 */
    private static final long BOOT_TIMEOUT_SECONDS = 180;

    /** 收摊前等日志行出现的上限（秒）。 */
    private static final long LOG_WAIT_SECONDS = 30;

    @Test
    @DisplayName("真 fat jar 起真 CommentServiceApplication：gRPC 端口真的在监听（生产 classpath + 生产 yaml）")
    void realFatJarProcessBootsAndListens() throws Exception {
        Path jar = CommentArtifactClasspathGateTest.fatJar();
        int port = pickFreePort();

        List<String> command = new ArrayList<>(List.of(
                "java", "-jar", jar.toAbsolutePath().toString(),
                "--grpc.server.port=" + port,
                // ⚠️ C 侧是 **web app**（D1 合并）⇒ Tomcat 与 gRPC 同时起
                //    ⇒ HTTP 口用 0（随机）⇒ 避免与其它 lane / 其它模块撞端口。
                "--server.port=0"));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(CommentArtifactClasspathGateTest.repoRoot()
                        .resolve(CommentArtifactClasspathGateTest.MODULE_PATH).toFile())
                .redirectErrorStream(true);

        Map<String, String> env = builder.environment();
        env.put("POSTGRES_HOST", postgresHost());
        env.put("POSTGRES_PORT", String.valueOf(postgresPort()));
        env.put("POSTGRES_DB", "first_db");
        env.put("POSTGRES_USER", TestPostgres.username());
        env.put("POSTGRES_PASSWORD", TestPostgres.password());
        // ⚠️ C 侧 JWT 密钥 fail-closed 守卫（CommentApiProperties 的 @PostConstruct）
        //    ⇒ 缺这个变量 ⇒ 启动即失败 ⇒ 必须给一个**真密钥**（与测试夹具同源）。
        env.put("USER_JWT_SECRET", CommentTestSupport.SECRET);
        // ⚠️ 下面三个指向**不可达**地址：它们只用于把「依赖在线」这个变量固定住，
        //    使本类只验「本模块自己的配置与 classpath 能不能起」。
        env.put("NACOS_ADDR", "127.0.0.1:1");
        env.put("KAFKA_BROKERS", "127.0.0.1:9092");
        env.put("REDIS_HOST", "127.0.0.1");
        env.put("REDIS_PORT", "6379");

        Process process = builder.start();
        // ⚠️ 用 StringBuffer（同步）而不是 StringBuilder：本类的 sink 会被
        //    「drain 线程」写、被「测试线程」读 ⇒ StringBuilder 不是线程安全的。
        StringBuffer log = new StringBuffer();
        Thread drain = new Thread(() -> drain(process.getInputStream(), log));
        drain.setDaemon(true);
        drain.start();

        boolean listening = false;
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(BOOT_TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            if (tcpProbe(port)) {
                listening = true;
                break;
            }
            if (!process.isAlive()) {
                break;
            }
            Thread.sleep(500);
        }

        // 🔴 **收摊前先等日志行出现**：
        //   gRPC 端口 **bind 成功**早于 devh 把「gRPC Server started」写进 stdout
        //   ⇒ 探活一成功就 `destroyForcibly()` ⇒ drain 线程**可能还没读到**那一行
        //   ⇒ 「日志里没有 gRPC Server started」这条断言**偶发红**。
        // ⇒ 这里**等日志行出现（带上限）再收摊**，而不是加长整体启动超时。
        long logDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(LOG_WAIT_SECONDS);
        while (System.currentTimeMillis() < logDeadline
                && process.isAlive()
                && !log.toString().contains(GRPC_STARTED_MARKER)) {
            Thread.sleep(200);
        }

        process.destroyForcibly();
        process.waitFor(30, TimeUnit.SECONDS);
        // ⚠️ 必须 join drain 线程：它可能还没把最后一段 stdout 读完
        //    ⇒ join 同时建立 happens-before（否则测试线程读到的 sink 是**残缺的**）。
        drain.join(TimeUnit.SECONDS.toMillis(10));
        String output = log.toString();

        org.junit.jupiter.api.Assertions.assertTrue(listening,
                "真 fat jar 起不来（gRPC 端口 " + port + " 没有在监听）—— 进程日志尾部：\n" + tail(output));
        org.junit.jupiter.api.Assertions.assertTrue(output.contains(GRPC_STARTED_MARKER),
                "日志里没有 devh 的「" + GRPC_STARTED_MARKER + "」⇒ 不能算真 gRPC server 起来了。日志尾部：\n"
                        + tail(output));
        org.junit.jupiter.api.Assertions.assertFalse(output.contains("NoClassDefFoundError"),
                "日志里有 NoClassDefFoundError ⇒ 运行时 classpath 缺依赖（grpc-inprocess?）"
                        + "⇒ 真启动会失败。日志尾部：\n" + tail(output));
        org.junit.jupiter.api.Assertions.assertFalse(output.contains("Incorrectly formatted target uri"),
                "日志里有 devh 的 URI 格式错误 ⇒ 生产 gRPC client 地址是**两斜杠**"
                        + "⇒ 真启动会失败。日志尾部：\n" + tail(output));
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** TCP 连通性探测：连得上 ⇒ 该端口有东西在监听（真 gRPC server 的证据）。 */
    private static boolean tcpProbe(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 挑一个当前空闲的端口。
     *
     * <p>⚠️ B 侧用的是「本域专用端口段」（22600–22699）＋ 一个端口登记册，
     * 用来避免与别的 lane 撞端口。B 侧的登记册测试（{@code RealGrpcPortTest} /
     * {@code RealContainerHostPortTest}）在 C 侧判为 <b>N_A_TO_C</b>
     * （C 侧容器口改用 Testcontainers 动态映射 ⇒ 端口冲突面结构性消失）
     * ⇒ 这里退化为「绑 0 让 OS 分配、立刻释放」。
     */
    private static int pickFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /** 从 {@link TestPostgres#jdbcUrl()} 里取 PG 主机名。 */
    private static String postgresHost() {
        Matcher matcher = Pattern.compile("jdbc:postgresql://([^:/?]+)").matcher(TestPostgres.jdbcUrl());
        if (!matcher.find()) {
            throw new IllegalStateException("解析 TestPostgres 的 JDBC URL 失败：" + TestPostgres.jdbcUrl());
        }
        return matcher.group(1);
    }

    /** 从 {@link TestPostgres#jdbcUrl()} 里取 PG 端口。 */
    private static int postgresPort() {
        Matcher matcher = Pattern.compile("jdbc:postgresql://[^:/?]+:(\\d+)").matcher(TestPostgres.jdbcUrl());
        if (!matcher.find()) {
            throw new IllegalStateException("解析 TestPostgres 的 JDBC URL 失败：" + TestPostgres.jdbcUrl());
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static void drain(InputStream in, StringBuffer sink) {
        try (InputStream stream = in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                sink.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException ignore) {
            // 进程结束时的 IOException 属正常
        }
    }

    /** 日志尾部（失败时给人看的证据）。 */
    private static String tail(String text) {
        int max = 6000;
        return text.length() <= max
                ? text
                : "……（前 " + (text.length() - max) + " 字符已省略）……\n" + text.substring(text.length() - max);
    }
}
