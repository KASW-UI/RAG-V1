package com.rag.hot.support;

import com.rag.hot.HotServiceApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * <p><b>hot 域「真栈」测试底座</b> —— 真 Spring 上下文 + 真 Tomcat + 真 Redis
 * ＋ <b>真进程内 gRPC</b>（article-rpc）。
 *
 * <h2>① 为什么<b>不</b>用 MockMvc</h2>
 * <p>AGENTS.md §八 检查清单「HTTP 断言走真 Tomcat」：断言 {@code Content-Type} /
 * {@code nosniff} / 状态行时<b>不用</b> MockMvc ⇒ 本类用
 * {@link TestRestTemplate} ＋ {@link #rawHttp(String)}（裸 socket 逐字节读回）。
 *
 * <h2>② 为什么 {@code grpc.server.port=0} 是必需的</h2>
 * <p>devh 的 gRPC server 是 {@code SmartLifecycle} ⇒ 上下文一启动就 bind 端口
 * ⇒ 固定端口在「多个上下文同时存活」时必然 {@code BindException}。
 *
 * <h2>③ 为什么 Redis 是<b>真容器</b>而不是 mock</h2>
 * <p>本域的三个 Lua 脚本 + ZSET 读写 + TTL 断言<b>只有在真 Redis 上才能验</b>
 * ⇒ 理由见 {@link TestRedis} 的类注释。
 *
 * <h2>④ 为什么 article-rpc 是<b>真进程内 gRPC</b>而不是 mock</h2>
 * <p>「补全的 4 条 skip 分支」与「fatal / best-effort 不对称」这两组判据的输入源
 * <b>就是</b>这条跨域 gRPC 边界 ⇒ mock 掉它 ⇒ 那两组判据就没有真触发点
 * ⇒ 理由见 {@link ArticleRpcInProcessServer} 的类注释。
 *
 * <h2>⑤ 为什么关掉 CPU 自适应降载</h2>
 * <p>降载命中时共享层的 CPU 降载 filter 走 {@code response.setStatus(503)} 并<b>直接 return</b>
 * ⇒ <b>裸 503、无 body、无信封</b> ⇒ 会把本类要验的判据「抢先盖掉」
 * ⇒ ⇒ 机器一忙就<b>红在错的地方</b>。
 * <p>⚠️ 这是<b>测试专用</b>覆盖，<b>不改生产默认值</b>（生产默认 {@code true} 对齐 B 侧
 * {@code rest/config.go:22} 的 {@code Middlewares.Shedding bool json:",default=true"}）。
 *
 * <h2>⑥ 为什么 Kafka 指向不可达地址</h2>
 * <p>本底座服务的 HTTP / Redis 用例<b>不需要</b>真 Kafka ⇒ 指向 {@code 127.0.0.1:1}
 * ⇒ listener 容器连不上 ⇒ 只在日志里刷重试 ⇒ <b>不阻塞</b>上下文就绪。
 * <p>需要<b>真 Kafka</b>的用例另起一个自己的上下文（见
 * {@code com.rag.hot.messaging.HotEventConsumerRealKafkaTest}）。
 */
@SpringBootTest(
        classes = {HotServiceApplication.class, NacosStubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // ── Nacos 不触网 ──
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "logging.level.com.alibaba.nacos=WARN",
                // ── 追踪不外发 ──
                "management.otlp.tracing.export.enabled=false",
                // ── gRPC 端口交给 OS（devh 的 gRPC server 是 SmartLifecycle ⇒ 启动即 bind）──
                "grpc.server.port=0",
                // ── Kafka 不可达（本底座不验 Kafka 面）──
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                // ── 关掉 CPU 自适应降载（见 ⑤）：否则会抢先返回裸 503，把判据盖掉 ──
                "rag.protection.cpu-shedding.enabled=false"
        })
public abstract class HotRealStackTestBase {

    /**
     * 把「真 Redis 连接信息」与「进程内 article-rpc 地址」注入上下文。
     *
     * <p>⚠️ 本方法被本包内全部 HTTP 测试类继承 ⇒ 方法集合相同 ⇒ <b>共享同一个上下文</b>。
     *
     * @param registry Spring 的动态属性注册表
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestRedis.register(registry);
        // ⚠️ devh 的 `in-process` scheme ⇒ 走 grpc-inprocess 传输（真 gRPC 栈，只是不走网络）
        registry.add("grpc.client.article-rpc.address",
                () -> "in-process:" + ArticleRpcInProcessServer.IN_PROCESS_NAME);
    }

    /** 真 Tomcat 上的 {@code TestRestTemplate}。 */
    @Autowired
    protected TestRestTemplate rest;

    /** 真 Tomcat 的端口。 */
    @LocalServerPort
    protected int port;

    /** 真 Redis 模板。 */
    @Autowired
    protected StringRedisTemplate redis;

    /**
     * <p>用<b>裸 socket</b> 手写一条 HTTP/1.1 请求，把响应<b>逐字节</b>读回来。
     *
     * <p>用途：断言「状态行逐字」「body 真的是 N 字节」「响应头里有没有某个头」
     * —— 这些是 {@code TestRestTemplate} 拿不到（或会被折叠）的字节面事实。
     *
     * @param request 完整请求报文（请求行 + 头 + 空行 + 可选 body），{@code \r\n} 分隔
     * @return 响应报文（ISO-8859-1 解码 ⇒ 字节 1:1 保留）
     * @throws IOException socket 读写失败
     */
    protected String rawHttp(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * 取响应报文的状态行（第一行）。
     *
     * @param rawResponse 响应报文
     * @return 状态行
     */
    protected String statusLineOf(String rawResponse) {
        int end = rawResponse.indexOf('\r');
        return end < 0 ? rawResponse : rawResponse.substring(0, end);
    }

    /**
     * 取响应报文的 body 部分（第一个空行之后）。
     *
     * @param rawResponse 响应报文
     * @return body 文本
     */
    protected String rawBodyOf(String rawResponse) {
        int separator = rawResponse.indexOf("\r\n\r\n");
        return separator < 0 ? "" : rawResponse.substring(separator + 4);
    }

    /**
     * 归一化 {@code Content-Type}（真 Tomcat 会把 {@code text/plain; charset=utf-8}
     * 归一化成 {@code text/plain;charset=utf-8} ⇒ 分号后的空格不可观测）。
     *
     * @param contentType 原始头值
     * @return 归一化后的头值
     */
    protected static String normalizeContentType(String contentType) {
        return contentType == null ? null : contentType.replace(" ", "");
    }
}
