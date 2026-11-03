package com.rag.article.storage;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * <p><b>测试用真 MinIO（进程内单例容器）</b> —— 本包「MinIO 真链路」两个缺口的公共底座。
 *
 * <h2>为什么必须是<b>真 MinIO 容器</b>（不许内存假实现 / 不许 mock）</h2>
 * <p>本仓这一轮要闭合的两个缺口（U-5 / U-12，见
 * {@code docs/DRIFT/article/verification-article-service-2026-11-03.md:419} 与 {@code :426}）
 * 本质是<b>对象存储协议层</b>的缺口：
 * <ul>
 *   <li>{@code put} / {@code get} / {@code readString} 要真的发 S3 协议请求
 *       （{@code PutObject} / {@code HEAD} / {@code GET}）⇒ 假实现不验证签名、不验证
 *       {@code Content-Type} / {@code Cache-Control} 元数据、不验证「对象不存在」的失败时点；</li>
 *   <li>{@code ensureBucketWithPublicRead} 要真的建桶 + 真的写桶策略
 *       （{@code PUT /bucket} + {@code PUT /bucket?policy}）⇒ 假实现证明不了
 *       「已存在 ⇒ 不修正策略」这条不对称。</li>
 * </ul>
 * ⇒ 只能用<b>真容器</b>。镜像用本机已有的 {@code minio/minio:latest}（无需联网拉取）。
 *
 * <h2>为什么是「单例」</h2>
 * <p>本包有两组测试（U-5 的纯 JUnit 用例 + U-12 的 Spring 上下文用例）⇒ 每个类各起一个容器
 * 会让机器上同时跑两个 MinIO，且 Spring 上下文缓存会让容器生命周期与上下文生命周期错位。
 * ⇒ 单例：<b>整个 JVM 起一次</b>，各测试类共用。
 * <br>⚠️ <b>共用容器 ⇒ 桶名必须互不重叠</b> ⇒ U-5 用带随机后缀的桶名，U-12 用固定桶名
 * （它的对象名带文章 id ⇒ 不冲突）。
 *
 * <h2>⚠️ 禁 {@code @EnabledIfDockerAvailable}（AGENTS.md §五.8 守门硬规则 ③）</h2>
 * <p>本类<b>不</b>提供任何「Docker 不可用就跳过」的开关 ⇒ Docker 不可用时
 * {@link #start()} 直接抛异常 ⇒ 用例<b>红</b>而不是<b>被跳过</b>
 * ⇒ 这样回归读数里的 {@code Skipped: 0} 才有意义（跳过 = 假绿）。
 *
 * <h2>B 出处</h2>
 * <p>本类<b>没有 B 侧对应物</b> —— 它是<b>测试底座</b>，不是被测组件。
 * 被测组件（{@code client/ArticleObjectStorageClient}）的 B 出处见各用例的 javadoc。
 */
public final class TestMinio {

    /** MinIO API 端口（容器内）。 */
    private static final int API_PORT = 9000;

    /** 访问密钥 —— 与生产 {@code article-rpc.minio.access-key-id} 的默认值一致（{@code minioadmin}）。 */
    public static final String ACCESS_KEY = "minioadmin";

    /** 私有密钥 —— 与生产 {@code article-rpc.minio.secret-access-key} 的默认值一致（{@code minioadmin}）。 */
    public static final String SECRET_KEY = "minioadmin";

    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:latest"))
            // ⚠️ MinIO 官方镜像的用法就是 `server /data`（ENTRYPOINT 会补上 minio 可执行文件）
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withExposedPorts(API_PORT)
            // ⚠️ 必须等「健康端点」而不是「端口可连」⇒ 端口可连时 MinIO 还没准备好接受 S3 请求
            .waitingFor(new HttpWaitStrategy()
                    .forPort(API_PORT)
                    .forPath("/minio/health/live")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    static {
        start();
    }

    private TestMinio() {
    }

    /**
     * 启动容器（幂等）—— 由静态块调用。
     *
     * <p>⚠️ Docker 不可用时这里<b>抛异常</b>（不静默跳过）⇒ 用例红。
     */
    static void start() {
        if (!MINIO.isRunning()) {
            MINIO.start();
        }
    }

    /**
     * 容器 endpoint（{@code host:port}）—— 供 {@code article-rpc.minio.endpoint} 用。
     *
     * <p>⚠️ 返回的是<b>宿主机</b>映射端口 ⇒ 只能从宿主 JVM 连；容器内网络里的
     * {@code minio:9000} 在测试里<b>不可达</b>。
     */
    public static String endpoint() {
        return MINIO.getHost() + ":" + MINIO.getMappedPort(API_PORT);
    }

    /**
     * 对外公开基址（{@code http://host:port}）—— 供 {@code article-rpc.minio.public-base-url} 用。
     *
     * <p>⚠️ MinIO 容器默认<b>不开</b>匿名读（桶策略由 {@code ensureBucketWithPublicRead} 显式设置），
     * 但 SDK 走的是<b>带签名</b>的请求 ⇒ 连通性只依赖 endpoint，不依赖 public-base-url。
     */
    public static String publicBaseUrl() {
        return "http://" + endpoint();
    }
}
