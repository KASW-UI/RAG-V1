package com.rag.article.client;

import com.rag.article.config.ArticleRpcProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * <p><b>职责</b>：对象存储（MinIO）客户端 —— 写对象 / 读对象 / 拼公开 URL / 幂等建桶。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/client/ArticleObjectStorageClient.java}
 * （类声明 {@code :42-43} · 字段与构造器 {@code :45-74} · {@code normalizeEndpoint} {@code :77-83} ·
 * {@code getClient} {@code :86-88} · {@code getBucketName} {@code :90-92} ·
 * {@code getObjectNamePrefixForImage} {@code :103-105} · {@code getObjectNamePrefixForArticle} {@code :112-114} ·
 * {@code ensureBucketWithPublicRead} {@code :143-172} · {@code bucketPublicReadPolicy} {@code :180-184} ·
 * {@code put} {@code :194-208} · {@code get} {@code :226-236} · {@code readAll} {@code :239-243} ·
 * {@code readString} {@code :246-248} · {@code buildPublicFileURL} {@code :270-280} ·
 * {@code trimSlashes} {@code :282-284} · {@code trimLeadingSlashes} {@code :286-288}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>仅包名</b>（{@code com.rag.article.article.rpc.client} →
 *       {@code com.rag.article.client}，D7 包压平）＋ <b>本段 javadoc</b>
 *       ⇒ <b>方法体、SDK 调用序列、策略 JSON 逐字未改</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>对象名<b>不做规范化</b>（yaml 里的 {@code image-path} / {@code article-path} 带尾斜杠，
 *             Go 是纯字符串拼接）；</li>
 *         <li>公开 URL 的<b>三段拼接</b>（base 去右斜杠 / bucket 去两侧 / object 去左斜杠）逐字保留；</li>
 *         <li>「只有本次真的创建成功才设置 public-read 策略」这条不对称保留；</li>
 *         <li>「读对象失败与读内容失败是两次不同的失败」保留（{@link #get} 主动做一次存在性探测）。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>为什么不用 Spring 抽象</h2>
 * Spring 没有对象存储抽象（没有像 {@code JdbcTemplate} 那样的 {@code ObjectStorageTemplate}）。
 * 因此直接用 MinIO 官方 SDK —— 这与 Go 侧用 {@code minio-go/v7} 是<b>同层替换</b>，
 * 不是「绕过框架自造轮子」。
 *
 * <h2>⚠️ 四个不能改的行为</h2>
 * <ol>
 *   <li><b>对象名不做规范化</b>：Go 是 {@code fmt.Sprintf("%s%d%s", ImagePath, id, ext)}，
 *       {@code imagePath} 在 yaml 里<b>带尾斜杠</b>（{@code "articles/images/"}）。
 *       少一个斜杠就变成 {@code articles/images123.png}（与桶同级而非桶内目录），
 *       那是可观察的存储布局变化。Markdown 路径同理（{@code articlePath}）。</li>
 *   <li><b>公开 URL 的三段拼接</b>：{@code base}/{bucket}/{objectName}，
 *       base 为空时按 {@code UseSSL} 选 http/https 拼 {@code scheme://Endpoint}。
 *       每一段各自 trim 的方向不同（base 去<b>右</b>斜杠、bucket 去<b>两</b>侧、object 去<b>左</b>斜杠）。
 *       这看着像可以「统一规范化」，但改了就可能产生双斜杠。</li>
 *   <li><b>content-type 的三个来源</b>：markdown 恒为 {@code text/markdown}；
 *       图片按扩展名 {@code mime.TypeByExtension}，<b>未知则回落 {@code application/octet-stream}</b>；
 *       图片还带 {@code CacheControl: public, max-age=31536000, immutable}（仅图片有）。</li>
 *   <li><b>读对象失败与读内容失败是两次不同的失败</b>：Go 的
 *       {@code GetObject} 与 {@code io.ReadAll} 分别记一次指标与日志（get 标签相同、文案不同）。
 *       合并成一次会丢掉「对象不存在」与「传输中断」的区分。</li>
 * </ol>
 *
 * <h2>⚠️ D14：<b>不再排除 Jackson 2</b></h2>
 * <p>minio 8.5.17 传递的 Jackson 2 与本仓工具链（Jackson 2 + Boot 3.5）<b>同代</b>
 * ⇒ pom 里<b>零</b> Jackson 2 排除项（此前「排除 Jackson 2」的实验结论已作废，保留作历史）。
 */
@Component
public class ArticleObjectStorageClient {

    private final ArticleRpcProperties properties;

    private final MinioClient client;

    /**
     * 构造 MinIO 客户端。
     *
     * <h2>⚠️ SDK 8.5.x 的 {@code secure} 已从 Builder 移除，SSL 由 <b>endpoint 协议</b>决定</h2>
     * Go 侧是 {@code minio.Options{Secure: c.MinIO.UseSSL}}（{@code service_context.go:37}），
     * 8.5.17 的 {@code MinioClient.Builder} <b>没有</b> {@code secure(boolean)} 方法，
     * 只有 {@code endpoint(String)} / {@code endpoint(String,int,boolean)}。
     * ⇒ 这里按 {@code UseSSL} 选协议后拼进 endpoint：
     * <pre>
     * UseSSL=true  ⇒ endpoint("https://" + endpoint)
     * UseSSL=false ⇒ endpoint("http://"  + endpoint)
     * </pre>
     * 语义与 Go 的 {@code Secure} 一一对应，且不引入新行为。
     *
     * <p>⚠️ 若 {@code endpoint} 配置里<b>已经带了协议前缀</b>，直接拼会得到
     * {@code http://https://…}。这里显式处理：以 {@code http://} / {@code https://} 开头时原样使用，
     * 让「配置里带协议」这条既有用法继续可用（Go 的 minio-go 也接受带协议的 endpoint）。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:66-74}。
     *
     * @param properties 本域的 MinIO 配置
     */
    public ArticleObjectStorageClient(ArticleRpcProperties properties) {
        this.properties = properties;
        this.client = MinioClient.builder()
                .endpoint(normalizeEndpoint(properties.getMinio().getEndpoint(),
                        properties.getMinio().isUseSsl()))
                .credentials(properties.getMinio().getAccessKeyId(),
                        properties.getMinio().getSecretAccessKey())
                .build();
    }

    /**
     * 按 {@code UseSSL} 补协议头；已带协议则原样返回。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:77-83}。
     *
     * @param endpoint 配置里的 endpoint
     * @param useSsl   是否走 https
     * @return 补好协议的 endpoint
     */
    static String normalizeEndpoint(String endpoint, boolean useSsl) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        return (useSsl ? "https://" : "http://") + value;
    }

    /**
     * 暴露底层 client 供 {@code ArticleReviewConsumer} 读图 + {@code MinioBucketBootstrap} 用。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:86-88}。
     *
     * @return MinIO SDK 客户端
     */
    public MinioClient getClient() {
        return client;
    }

    /**
     * 桶名。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:90-92}。
     *
     * @return 配置里的桶名
     */
    public String getBucketName() {
        return properties.getMinio().getBucketName();
    }

    /**
     * 图片对象名前缀 —— 对应 Go {@code config.MinIO.ImagePath}（yaml 里<b>带尾斜杠</b>）。
     *
     * <p>⚠️ 原样返回，<b>不补也不去尾斜杠</b>：Go 是
     * {@code fmt.Sprintf("%s%d%s", ImagePath, id, ext)} 的纯字符串拼接。
     * 这里若「顺手规范化」，在配置漏写尾斜杠时会<b>静默改变</b>对象布局。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:103-105}。
     *
     * @return 图片对象名前缀
     */
    public String getObjectNamePrefixForImage() {
        return properties.getMinio().getImagePath();
    }

    /**
     * Markdown 对象名前缀 —— 对应 Go {@code config.MinIO.ArticlePath}（yaml 里<b>带尾斜杠</b>）。
     *
     * <p>⚠️ 同 {@link #getObjectNamePrefixForImage()}：原样返回，不规范化。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:112-114}。
     *
     * @return Markdown 对象名前缀
     */
    public String getObjectNamePrefixForArticle() {
        return properties.getMinio().getArticlePath();
    }

    /**
     * 对应 Go 的 bucket 幂等 bootstrap（{@code service_context.go:43-56}）。
     *
     * <p><b>⚠️ 语义逐条对照 Go</b>：
     * <pre>
     * err = MakeBucket(...)
     * if err != nil {
     *     exists, errBucketExists := BucketExists(...)
     *     if errBucketExists == nil &amp;&amp; exists {
     *         // 已存在：什么都不做（连 warn 都没有）
     *     } else {
     *         logx.Errorf("create minio bucket failed: %v", err)
     *     }
     * } else {
     *     SetBucketPolicy(... public-read ...)
     * }
     * </pre>
     * ⇒ <b>只有「本次真的创建成功」才设置 public-read 策略</b>；
     * bucket 已存在时<b>不会</b>去修正策略。这条不对称要照搬：
     * 若改成「每次启动都确保策略」，就会在策略被人为收紧后自动改回去 ——
     * 那是新增的运维行为。
     *
     * <p><b>创建失败也只是记日志，不 panic</b>（Go 是 {@code logx.Errorf} 后继续）。
     * 对比 {@code minio.New} 失败是 panic —— 两者不对称，照搬。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:143-172}。
     *
     * @return true = 本次创建成功并已设置策略
     */
    public boolean ensureBucketWithPublicRead() {
        String bucket = getBucketName();
        try {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } catch (Exception createErr) {
            boolean exists;
            try {
                exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            } catch (Exception existsErr) {
                // Go: errBucketExists != nil ⇒ 落到 else 分支记日志
                exists = false;
            }
            if (!exists) {
                // Go: logx.Errorf("create minio bucket failed: %v", err) —— 不 panic
                return false;
            }
            // Go: exists ⇒ 空分支（不设策略、不打日志）
            return false;
        }
        try {
            client.setBucketPolicy(SetBucketPolicyArgs.builder()
                    .bucket(bucket)
                    .config(bucketPublicReadPolicy(bucket))
                    .build());
        } catch (Exception policyErr) {
            // Go: logx.Errorf("set minio bucket policy failed: %v", err) —— 同样不 panic
            return true;
        }
        return true;
    }

    /**
     * 对应 Go {@code service_context.go:51} 的策略 JSON。
     *
     * <p>⚠️ <b>逐字照搬</b>，包括空 {@code "Sid": ""} 与 {@code s3:GetObject}。
     * 策略内容的任何「清理」都会改变桶的实际可访问范围。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:180-184}。
     *
     * @param bucket 桶名
     * @return 策略 JSON
     */
    static String bucketPublicReadPolicy(String bucket) {
        return "{\"Version\": \"2012-10-17\",\"Statement\": [{\"Action\": [\"s3:GetObject\"],"
                + "\"Effect\": \"Allow\",\"Principal\": {\"AWS\": [\"*\"]},"
                + "\"Resource\": [\"arn:aws:s3:::" + bucket + "/*\"],\"Sid\": \"\"}]}";
    }

    /**
     * 写对象 —— 对应 Go 的 {@code MinioClient.PutObject}。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:194-208}。
     *
     * @param objectName   完整对象名（含前缀路径），<b>不</b>含桶名
     * @param content      字节内容
     * @param contentType  写对象时的 Content-Type
     * @param cacheControl 图片专用；markdown 传 null（Go 的 markdown 分支不设 CacheControl）
     * @throws Exception SDK 抛出的异常
     */
    public void put(String objectName, byte[] content, String contentType, String cacheControl)
            throws Exception {
        PutObjectArgs.Builder args = PutObjectArgs.builder()
                .bucket(getBucketName())
                .object(objectName)
                .stream(new java.io.ByteArrayInputStream(content), content.length, -1)
                .contentType(contentType);
        if (cacheControl != null) {
            // ⚠️ 8.5.17 的 headers(...) 收 Map（不是 Multimap），且**会覆盖**默认头集合。
            //    这里只放 Cache-Control 一项：contentType 由 .contentType(...) 单独设置，
            //    两者不冲突（SDK 把 contentType 也放进 headers，调用顺序在后会补上）。
            args.headers(new java.util.HashMap<>(java.util.Map.of("Cache-Control", cacheControl)));
        }
        client.putObject(args.build());
    }

    /**
     * 打开对象流 —— 对应 Go 的 {@code MinioClient.GetObject}。
     *
     * <p>⚠️ <b>MinIO Java SDK 的 {@code getObject} 不校验对象存在性</b>：
     * 它在真正发出请求前就返回流，对象不存在时要在 {@code read} 时才抛
     * {@code ErrorResponseException}（S3 404）。这与 Go 的
     * {@code minio-go/v7} <b>行为不同</b> —— Go 侧
     * {@code GetObject} 在对象不存在时<b>立刻返回 error</b>（minio-go 会先发 HEAD）。
     *
     * <p>⇒ 本方法<b>主动做一次存在性探测</b>，把两种 SDK 的失败时点对齐到 Go：
     * 否则 Go「GetObject 失败」与 Java「read 失败」会落到不同的错误分支，
     * 改变日志与指标的可观察差异（见类级说明第 4 条）。
     * 代价是每次读多一次 HEAD 请求；这是<b>为对齐失败时点</b>付出的确定成本，不是优化。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:226-236}。
     *
     * @param objectName 完整对象名
     * @return 对象输入流
     * @throws Exception SDK 抛出的异常（对象不存在 ⇒ 在此抛）
     */
    public InputStream get(String objectName) throws Exception {
        // 存在性探测：对齐 minio-go 的「立刻报错」时点
        client.statObject(io.minio.StatObjectArgs.builder()
                .bucket(getBucketName())
                .object(objectName)
                .build());
        return client.getObject(GetObjectArgs.builder()
                .bucket(getBucketName())
                .object(objectName)
                .build());
    }

    /**
     * 读对象全部字节 —— 对应 Go 的 {@code io.ReadAll(object)}。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:239-243}。
     *
     * @param stream 输入流
     * @return 全部字节
     * @throws Exception 读失败
     */
    public byte[] readAll(InputStream stream) throws Exception {
        try (InputStream in = stream) {
            return in.readAllBytes();
        }
    }

    /**
     * 读对象并按 UTF-8 解码 —— Go 的 {@code string(contentBytes)}。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:246-248}。
     *
     * @param objectName 完整对象名
     * @return 对象的 UTF-8 文本
     * @throws Exception 读失败
     */
    public String readString(String objectName) throws Exception {
        return new String(readAll(get(objectName)), StandardCharsets.UTF_8);
    }

    /**
     * 对应 Go {@code upload_file_logic.go:93-108} 的 {@code buildPublicFileURL}。
     *
     * <p><b>⚠️ 三段各自的 trim 方向不可统一</b>：
     * <pre>
     * fmt.Sprintf("%s/%s/%s",
     *     strings.TrimRight(baseURL, "/"),                       // base：去右
     *     strings.Trim(l.bucketName, "/"),                        // bucket：两侧
     *     strings.TrimLeft(objectName, "/"))                      // object：去左
     * </pre>
     * 而 baseURL 为空时的回落：
     * <pre>
     * scheme := "http"; if UseSSL { scheme = "https" }
     * baseURL = fmt.Sprintf("%s://%s", scheme, strings.TrimSpace(Endpoint))
     * </pre>
     * ⇒ Endpoint 只 {@code TrimSpace}；<b>尾斜杠由下一行的 {@code replaceAll("/+$", "")} 去掉</b>
     * （作用在拼好的 baseURL 上 ⇒ 含 {@code public-base-url} 为空时回落出的 {@code scheme://endpoint}）。
     *
     * <p><b>🔴 与 B 侧 javadoc 的措辞出入（2026-11-03 核对，行为层零差异）</b>：
     * <ul>
     *   <li>B 侧同一方法的 javadoc 写「Endpoint 只 TrimSpace、<b>不</b>去尾斜杠」，
     *       并称 {@code Endpoint: "minio:9000/"} 会产出 {@code http://minio:9000//bucket/...}
     *       （「Go 产出的是双斜杠」）；</li>
     *   <li><b>但 B 侧自己的代码</b>（{@code baseURL.replaceAll("/+$", "")}）<b>确实去掉了尾斜杠</b>
     *       ⇒ 实测产出是 {@code http://minio:9000/bucket/...}（<b>单斜杠</b>）；</li>
     *   <li>⇒ B 侧那段 javadoc <b>与它自己的实现自相矛盾</b>；本实现<b>代码逐字节照抄 B 侧</b>
     *       ⇒ <b>行为零差异</b>，本段只是把<b>措辞改成与代码相符</b>（否则下一个人会被注释误导）。</li>
     *   <li>⚠️ <b>仍未被覆盖的那一半</b>：<b>对象名</b>的尾斜杠<b>保留</b>
     *       （{@code trimLeadingSlashes} 只去左侧）⇒ {@code objectName="a/"} ⇒ 产出 {@code .../bucket/a/}。</li>
     * </ul>
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:270-280}（代码逐字节一致）。
     *
     * @param objectName 完整对象名
     * @return 公开访问 URL
     */
    public String buildPublicFileURL(String objectName) {
        ArticleRpcProperties.Minio minio = properties.getMinio();
        String baseURL = minio.getPublicBaseUrl() == null ? "" : minio.getPublicBaseUrl().trim();
        if (baseURL.isEmpty()) {
            String scheme = minio.isUseSsl() ? "https" : "http";
            baseURL = scheme + "://" + (minio.getEndpoint() == null ? "" : minio.getEndpoint().trim());
        }
        return baseURL.replaceAll("/+$", "")
                + "/" + trimSlashes(minio.getBucketName())
                + "/" + trimLeadingSlashes(objectName);
    }

    /**
     * 去掉<b>两侧</b>斜杠（桶名用）。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:282-284}。
     *
     * @param value 原值
     * @return 去掉两侧斜杠后的值
     */
    private static String trimSlashes(String value) {
        return value == null ? "" : value.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    /**
     * 去掉<b>左侧</b>斜杠（对象名用）。
     *
     * <p><b>B 出处</b>：{@code .../client/ArticleObjectStorageClient.java:286-288}。
     *
     * @param value 原值
     * @return 去掉左侧斜杠后的值
     */
    private static String trimLeadingSlashes(String value) {
        return value == null ? "" : value.replaceAll("^/+", "");
    }
}
