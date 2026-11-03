package com.rag.article.wiring;

import com.rag.article.client.ArticleObjectStorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * <p><b>职责</b>：启动期幂等创建对象存储桶并设置公开读策略 —— <b>失败不阻止启动</b>。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/bootstrap/MinioBucketBootstrap.java}
 * （类声明 {@code :42-43} · 字段与构造器 {@code :47-51} · {@code ensureBucket} {@code :53-71}）
 * ⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅包名</b>（{@code com.rag.article.article.rpc.bootstrap} →
 *       {@code com.rag.article.article.wiring}，D7 包压平）＋ <b>本段 javadoc</b>
 *       ⇒ <b>方法体逐字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>「只有本次真的创建成功才设置 public-read 策略」这条<b>不对称</b>照搬；</li>
 *         <li>「创建失败也只是记日志、不 panic」照搬（与「{@code minio.New} 失败才 panic」的不对称也照搬）；</li>
 *         <li>挂 {@code ApplicationReadyEvent}（不是构造器）照搬。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 语义逐条对照 Go（这段看起来「可以简化」，但每条都有行为含义）</h2>
 * <pre>
 * err = MakeBucket(...)
 * if err != nil {
 *     exists, errBucketExists := BucketExists(...)
 *     if errBucketExists == nil &amp;&amp; exists {
 *         // 已存在：<b>什么都不做</b>（连 warn 都没有，也<b>不</b>去修正策略）
 *     } else {
 *         logx.Errorf("create minio bucket failed: %v", err)
 *     }
 * } else {
 *     err = SetBucketPolicy(public-read)
 *     if err != nil { logx.Errorf("set minio bucket policy failed: %v", err) }
 * }
 * </pre>
 * ⇒ <b>只有「本次真的创建成功」才设置 public-read 策略</b>。
 * 改成「每次启动都确保策略正确」会在运维<b>刻意收紧</b>桶策略后被自动改回去 ——
 * 那是<b>新增的运维行为</b>，不是对齐。
 *
 * <p><b>失败不阻止启动</b>：Go 侧这里只记日志（与 {@code minio.New} 失败才 panic 形成不对称），
 * 因为 gRPC server 与 Kafka 消费不依赖桶当时可用。
 *
 * <p><b>为什么挂在 {@code ApplicationReadyEvent} 而不是构造器</b>：
 * Go 侧是在 {@code NewServiceContext}（进程装配期）做的；
 * Java 侧放构造期会让<b>单元测试无法构造该 Bean</b>（它会去连对象存储），
 * 且启动日志里对象存储的错误会淹没真正的启动失败。事件回调是等价且更安全的时点。
 */
@Component
public class MinioBucketBootstrap {

    private static final Logger log = LoggerFactory.getLogger(MinioBucketBootstrap.class);

    private final ArticleObjectStorageClient storage;

    /**
     * 构造器注入对象存储客户端。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/MinioBucketBootstrap.java:49-51}。
     *
     * @param storage 对象存储客户端
     */
    public MinioBucketBootstrap(ArticleObjectStorageClient storage) {
        this.storage = storage;
    }

    /**
     * 启动完成后幂等建桶。
     *
     * <p><b>B 出处</b>：{@code .../bootstrap/MinioBucketBootstrap.java:53-71}。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        try {
            boolean created = storage.ensureBucketWithPublicRead();
            if (created) {
                log.info("MinioBucketBootstrap: bucket {} created with public-read policy",
                        storage.getBucketName());
            } else {
                // Go 侧这一分支是完全静默的；这里记一条 info 便于排障，
                // 属纯观测增强，不改变任何行为。
                log.info("MinioBucketBootstrap: bucket {} already exists, policy left untouched",
                        storage.getBucketName());
            }
        } catch (Exception e) {
            // Go: logx.Errorf("create minio bucket failed: %v", err) —— 不 panic
            log.error("MinioBucketBootstrap: ensure bucket {} failed (not fatal): {}",
                    storage.getBucketName(), e.getMessage(), e);
        }
    }
}
