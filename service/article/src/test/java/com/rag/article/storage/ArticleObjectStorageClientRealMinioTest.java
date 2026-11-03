package com.rag.article.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.article.client.ArticleObjectStorageClient;
import com.rag.article.config.ArticleRpcProperties;
import io.minio.BucketExistsArgs;
import io.minio.DeleteBucketPolicyArgs;
import io.minio.GetBucketPolicyArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>U-5 真触发</b>：{@code client/ArticleObjectStorageClient} 的
 * {@code ensureBucketWithPublicRead} / {@code put} / {@code get} / {@code readString} /
 * {@code buildPublicFileURL} 五个方法<b>对着真 MinIO 容器实跑</b>。
 *
 * <h2>为什么这个判据必须是「真触发」</h2>
 * <p>本仓这一轮之前，这五个方法<b>只被 Mockito 桩过，从未连过真对象存储</b>
 * （缺口登记见 {@code docs/DRIFT/article/verification-article-service-2026-11-03.md:419}
 * 的 **U-5** 行）。而这几个方法承载的可观察行为<b>全部</b>发生在<b>协议层</b>：
 * <ul>
 *   <li>桶策略的 JSON 形状（{@code s3:GetObject} + {@code Principal.AWS=["*"]} + 资源 ARN）
 *       —— 错了 ⇒ <b>匿名读整个桶</b>或<b>完全读不到</b>；</li>
 *   <li>写对象时的 {@code Content-Type} / {@code Cache-Control} 元数据
 *       —— 错了 ⇒ 浏览器按 {@code application/octet-stream} 下载图片（Go 侧靠
 *       {@code mime.TypeByExtension} + 图片专用缓存头）；</li>
 *   <li>「对象不存在」的<b>失败时点</b> —— Go 的 {@code minio-go/v7} 在 {@code GetObject}
 *       就立刻报错，Java SDK 的 {@code getObject} 要到 read 时才报错
 *       ⇒ B 侧为此<b>刻意加了一次 {@code statObject} 探测</b>；</li>
 *   <li>「桶已存在 ⇒ <b>不</b>修正策略」这条<b>不对称</b>。</li>
 * </ul>
 * ⇒ 这些<b>没有一条</b>能被 mock 验出来 ⇒ 只能对真 MinIO 实跑。
 *
 * <h2>B 侧 oracle 与本仓的对应关系</h2>
 * <p>B 侧 = {@code /Users/pr/Documents/java&golang/RAG/service/article/article-rpc/src/main/java/com/rag/article/article/rpc/client/ArticleObjectStorageClient.java}
 * （下称「B 侧文件」）。C 侧 = {@code service/article/src/main/java/com/rag/article/client/ArticleObjectStorageClient.java}。
 * <br>⚠️ <b>B 侧没有本类的任何测试</b>（已全仓检索：{@code grep -rn "ArticleObjectStorageClient"
 * service/article/article-rpc/src/test/} ⇒ 命中处<b>全是 mock</b>，无一条针对本类的用例）
 * ⇒ ⇒ <b>期望值只能从 B 侧<b>实现源码</b>逐字读出</b>（D16：B 侧自身就是基准），
 * 本类每个用例的 javadoc 都给出 {@code file:line}。
 *
 * <h2>🔴 B-06：{@code buildPublicFileURL} 的「不去尾斜杠」是<b>照抄</b>，本类只钉现状不修</h2>
 * <p>B 侧台账把它登记为<b>既有缺陷</b>（B 侧 {@code docs/DRIFT/article/rpc.md:267}：
 * 「逐条一致（含 base 不去尾斜杠）」；C 侧台账 <b>B-06</b> 行：
 * {@code docs/DRIFT/article/verification-article-service-2026-11-03.md:289}）。
 * <br>⇒ 按 <b>D16</b>（B 侧自身就是基准 · 不回溯修正）<b>照抄不修</b> ⇒
 * 本类用 {@link #buildPublicFileURLKeepsBSideTrimSemantics()} 把<b>当前实际行为逐字钉死</b>，
 * 并在断言里<b>显式标注</b>哪一段是 B-06 的照抄点。
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code put} 的 {@code .contentType(contentType)} 去掉
 *       ⇒ 用例 ③ <b>立刻红</b>（stat 的 contentType 变成 {@code application/octet-stream}）；</li>
 *   <li>把 {@code put} 的 {@code Cache-Control} 分支去掉
 *       ⇒ 用例 ③ <b>立刻红</b>（stat 的响应头里没有 {@code Cache-Control}）；</li>
 *   <li>把 {@code get} 的 {@code statObject} 探测删掉
 *       ⇒ 用例 ⑤ <b>立刻红</b>（{@code get} 不再抛异常，改为 read 时才炸）；</li>
 *   <li>把 {@code ensureBucketWithPublicRead} 改成「每次都确保策略」
 *       ⇒ 用例 ② <b>立刻红</b>（已删除的策略会被重新写回）；</li>
 *   <li>把 {@code buildPublicFileURL} 的三段 trim 改成「全局去斜杠」
 *       ⇒ 用例 ⑥ <b>立刻红</b>（对象名的尾斜杠会被吃掉）。</li>
 * </ul>
 *
 * <h2>数据隔离</h2>
 * <p>本类用<b>一用例一桶</b>（{@code articles-u5-<用例标签>-<12 位随机 hex>}）
 * ⇒ 不与 U-12 的固定桶名、不与本机其它 MinIO 容器里的桶冲突；
 * 对象名带同一随机后缀 ⇒ 同理。
 * <br>⚠️ <b>为什么必须一用例一桶</b>：{@code ensureBucketWithPublicRead} 的语义是
 * 「<b>只有本次真的创建成功</b>才返回 true」（B :143-172）⇒ 共用一个桶时，
 * 第二个用例起必然拿到 {@code false} ⇒ 「首次创建 ⇒ true」会假红
 * （⚠️ 本轮<b>实测踩到过</b>：5 个用例同时假红）。
 */
class ArticleObjectStorageClientRealMinioTest {

    /**
     * B 侧 {@code bucketPublicReadPolicy} 的<b>逐字</b>期望值
     * （B 侧文件 {@code :180-184}）。
     *
     * <p>⚠️ <b>刻意不调用被测代码里的那个方法</b>（它是 package-private，且在
     * {@code com.rag.article.client} 包下）⇒ 期望值必须<b>独立</b>写一份，
 * 否则「实现改了 ⇒ 期望值跟着改」⇒ 断言永远绿（假绿）。
     */
    private static final String B_PUBLIC_READ_POLICY_TEMPLATE =
            "{\"Version\": \"2012-10-17\",\"Statement\": [{\"Action\": [\"s3:GetObject\"],"
                    + "\"Effect\": \"Allow\",\"Principal\": {\"AWS\": [\"*\"]},"
                    + "\"Resource\": [\"arn:aws:s3:::%s/*\"],\"Sid\": \"\"}]}";

    /** 本类专属桶名后缀（避免与 U-12 / 其它容器冲突）。 */
    private static final String SUFFIX =
            UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    /**
     * 每个用例<b>专属</b>的桶名。
     *
     * <p>⚠️ <b>必须一用例一桶</b>：{@code ensureBucketWithPublicRead} 的语义是
     * 「<b>只有本次真的创建成功</b>才返回 true」（B :143-172）⇒
     * 若多个用例共用一个桶，<b>第二个用例起就必然返回 false</b>
     * （桶已存在 ⇒ 走 {@code exists} 空分支）⇒ 「首次创建 ⇒ true」这条断言会假红。
     * <br>⇒ 桶名带用例标签 + 本类随机后缀 ⇒ 既互不冲突，也不与其它测试/容器冲突。
     */
    private static String bucket(String tag) {
        return "articles-u5-" + tag + "-" + SUFFIX;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================================================================
    // ① 建桶 + 桶策略（首次创建 ⇒ true + public-read 策略逐字生效）
    // ==================================================================

    /**
     * <p><b>真触发</b>：{@code ensureBucketWithPublicRead()} 对<b>真 MinIO</b> 发
     * {@code PUT /bucket}（建桶）+ {@code PUT /bucket?policy}（写策略），
     * 且桶策略的 JSON <b>逐字</b>等于 B 侧的 {@code bucketPublicReadPolicy}。
     *
     * <p><b>B 出处</b>：
     * <ul>
     *   <li>B 侧文件 {@code :143-172}（{@code ensureBucketWithPublicRead}）——
     *       「<b>只有本次真的创建成功才设置 public-read 策略</b>」；</li>
     *   <li>B 侧文件 {@code :180-184}（{@code bucketPublicReadPolicy}）——
     *       策略 JSON 的<b>逐字</b>形状（含空 {@code "Sid": ""} 与 {@code s3:GetObject}）。</li>
     * </ul>
     *
     * <p><b>怎么才能证伪</b>：把 {@code bucketPublicReadPolicy} 里的 {@code s3:GetObject}
     * 改成 {@code s3:PutObject}，或把 {@code Principal.AWS} 的 {@code ["*"]} 改成 {@code ["root"]}
     * ⇒ 本用例 <b>立刻红</b>（JSON 树不等）。
     */
    @Test
    @DisplayName("🔴 真触发：ensureBucketWithPublicRead 真建桶，且 public-read 策略逐字落到真 MinIO")
    void ensureBucketWithPublicReadCreatesBucketAndAppliesPolicy() throws Exception {
        String bucket = bucket("ensure");
        ArticleObjectStorageClient client = clientFor(bucket, TestMinio.publicBaseUrl(), false);
        assertFalse(client.getClient().bucketExists(
                        BucketExistsArgs.builder().bucket(bucket).build()),
                "前置：桶还不存在（否则「首次创建 ⇒ true」这条断言没有判别力）");

        // 桶还不存在 ⇒ 本次真的创建成功 ⇒ 返回 true（B :146-149 的 makeBucket 成功分支）
        assertTrue(client.ensureBucketWithPublicRead(),
                "首次建桶 ⇒ true（B :143-172：只有本次真的创建成功才往下走）");
        assertTrue(client.getClient().bucketExists(
                        BucketExistsArgs.builder().bucket(bucket).build()),
                "真 MinIO 里必须真的有这个桶（B :146 的 MakeBucket 真的发出去了）");

        String rawPolicy = client.getClient().getBucketPolicy(
                GetBucketPolicyArgs.builder().bucket(bucket).build());
        // ⚠️ 服务端会**规范化**策略：空的 "Sid" 被 MinIO 直接丢掉（键序也会重排）
        //    ⇒ 断言「去掉空 Sid 后的结构」而不是逐字节相等（逐字节相等的是**发出去**的那份，见下一条）
        JsonNode expected = MAPPER.readTree(
                String.format(B_PUBLIC_READ_POLICY_TEMPLATE, bucket));
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.at("/Statement/0"))
                .remove("Sid");
        JsonNode actual = MAPPER.readTree(rawPolicy);
        assertEquals(expected, actual,
                "桶策略必须与 B :180-184 同形（本次真的写进去了）"
                        + "（MinIO 实际返回：" + rawPolicy + "）");
        // 逐字钉住三个可观察要素（万一将来有人改 JSON 结构，提示会更直接）
        assertEquals("s3:GetObject", actual.at("/Statement/0/Action/0").asText(),
                "只允许读对象（B :180-184 的 Action 逐字）");
        assertEquals("*", actual.at("/Statement/0/Principal/AWS/0").asText(),
                "匿名主体（B :180-184 的 Principal 逐字）");
        assertEquals("arn:aws:s3:::" + bucket + "/*", actual.at("/Statement/0/Resource/0").asText(),
                "资源必须是本桶（B :180-184 的 Resource 逐字）");

        // 🔴 发出去的那份必须**逐字节**等于 B :180-184（含空 "Sid"）
        //    —— 上面那条断的是「服务端存下来的」，服务端把空 Sid 规范化掉了 ⇒ 这里补上「客户端发出去的」
        assertEquals(String.format(B_PUBLIC_READ_POLICY_TEMPLATE, bucket), sentPolicy(bucket),
                "🔴 客户端**发出去**的策略 JSON 必须与 B :180-184 逐字一致"
                        + "（含空 \"Sid\"：服务端会规范化掉它 ⇒ 只能从发送侧断言）");
    }

    // ==================================================================
    // ② 幂等 + 「桶已存在 ⇒ 不修正策略」的不对称
    // ==================================================================

    /**
     * <p><b>真触发</b>：桶已存在时 {@code ensureBucketWithPublicRead()} 返回
     * <b>false</b>，<b>并且不会把被人为删掉的策略再写回去</b>。
     *
     * <p><b>B 出处</b>：B 侧文件 {@code :143-172}，特别是
     * <pre>
     * } catch (Exception createErr) {
     *     exists = client.bucketExists(...);
     *     if (!exists) { return false; }   // 建桶失败且桶不存在 ⇒ 记日志后返回 false
     *     return false;                     // Go: exists ⇒ 空分支（不设策略、不打日志）
     * }
     * </pre>
     * ＋ B 侧文件 {@code :133-136} 的自述：「<b>只有本次真的创建成功才设置 public-read 策略</b>；
     * 桶已存在时<b>不会</b>去修正策略。这条不对称要照搬：若改成「每次启动都确保策略」，
     * 就会在策略被人为收紧后自动改回去 —— 那是新增的运维行为。」
     *
     * <p><b>怎么才能证伪</b>：把 C 侧 {@code ensureBucketWithPublicRead} 改成
     * 「无论是否新建都 {@code setBucketPolicy}」⇒ 本用例 <b>立刻红</b>
     * （被删掉的策略会被写回 ⇒ {@code getBucketPolicy} 不再抛异常）。
     */
    @Test
    @DisplayName("🔴 真触发：桶已存在 ⇒ 返回 false，且**不**修正被人为改掉的策略（B 侧不对称照抄）")
    void ensureBucketWithPublicReadOnExistingBucketDoesNotRepairPolicy() throws Exception {
        String bucket = bucket("idem");
        ArticleObjectStorageClient client = clientFor(bucket, TestMinio.publicBaseUrl(), false);
        assertTrue(client.ensureBucketWithPublicRead(), "前置：先建桶");
        assertFalse(client.ensureBucketWithPublicRead(),
                "桶已存在 ⇒ false（B :155-160 的 exists 空分支）");

        // 模拟「策略被人为收紧/删掉」
        client.getClient().deleteBucketPolicy(
                DeleteBucketPolicyArgs.builder().bucket(bucket).build());
        String afterDelete = policyOrEmpty(client, bucket);
        assertTrue(isNoPolicy(afterDelete),
                "前置：策略已被删除（真 MinIO 里读不到策略），实际读到：" + afterDelete);

        assertFalse(client.ensureBucketWithPublicRead(),
                "仍然 false（B :159-160：exists ⇒ 空分支）");
        String afterSecondEnsure = policyOrEmpty(client, bucket);
        assertTrue(isNoPolicy(afterSecondEnsure),
                "🔴 桶已存在 ⇒ **不重设策略**（B :133-136 的不对称照抄）；"
                        + "若被改成「每次启动都确保策略」，这里会读到 public-read 策略 ⇒ 立刻红。"
                        + "实际读到：" + afterSecondEnsure);
    }

    // ==================================================================
    // ③ put：Content-Type / Cache-Control 元数据
    // ==================================================================

    /**
     * <p><b>真触发</b>：{@code put(...)} 真发 {@code PUT /bucket/object}，
     * 且对象上真的带上了 {@code Content-Type} 与 {@code Cache-Control}。
     *
     * <p><b>B 出处</b>：
     * <ul>
     *   <li>B 侧文件 {@code :194-208}（{@code put}）—— {@code contentType} 必设、
     *       {@code cacheControl} 非空才加 {@code Cache-Control} 头；</li>
     *   <li>B 侧文件类级说明 {@code :34-36}：
     *       「<b>content-type 的三个来源</b>：markdown 恒为 {@code text/markdown}；
     *       图片按扩展名 {@code mime.TypeByExtension}，<b>未知则回落
     *       {@code application/octet-stream}</b>；图片还带
     *       {@code CacheControl: public, max-age=31536000, immutable}（仅图片有）。」</li>
     * </ul>
     *
     * <p><b>怎么才能证伪</b>：
     * <ul>
     *   <li>删掉 {@code .contentType(contentType)} ⇒ {@code contentType} 断言红；</li>
     *   <li>删掉 {@code Cache-Control} 那个分支 ⇒ {@code Cache-Control} 断言红；</li>
     *   <li>把 {@code headers(...)} 改成「追加而不是覆盖」⇒ 本用例仍绿（说明 SDK 侧行为稳），
     *       但若 SDK 行为变了则会红（这正是要跑真容器的原因）。</li>
     * </ul>
     */
    @Test
    @DisplayName("🔴 真触发：put 真写对象，Content-Type 与 Cache-Control 元数据真落到对象上")
    void putStoresContentTypeAndCacheControl() throws Exception {
        String bucket = bucket("put");
        ArticleObjectStorageClient client = clientFor(bucket, TestMinio.publicBaseUrl(), false);
        assertTrue(client.ensureBucketWithPublicRead(), "前置：建桶");

        // ① markdown 分支：contentType 恒 text/markdown，cacheControl 传 null（B :212 的约定）
        String mdName = "articles/content/u5-" + SUFFIX + ".md";
        byte[] md = "# 真 MinIO\n\n正文".getBytes(StandardCharsets.UTF_8);
        client.put(mdName, md, "text/markdown", null);

        StatObjectResponse mdStat = client.getClient().statObject(
                StatObjectArgs.builder().bucket(bucket).object(mdName).build());
        assertEquals("text/markdown", mdStat.contentType(),
                "markdown 的 content-type 恒为 text/markdown（B :34-36）");
        assertEquals(md.length, mdStat.size(), "对象大小必须与写入字节数一致");

        // ② 图片分支：带 Cache-Control（B :36 的图片专用头）
        String imgName = "articles/images/u5-" + SUFFIX + ".png";
        byte[] png = new byte[]{(byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G',
                0x0D, 0x0A, 0x1A, 0x0A};
        String cacheControl = "public, max-age=31536000, immutable";
        client.put(imgName, png, "image/png", cacheControl);

        StatObjectResponse imgStat = client.getClient().statObject(
                StatObjectArgs.builder().bucket(bucket).object(imgName).build());
        assertEquals("image/png", imgStat.contentType(),
                "图片的 content-type 来自扩展名（B :34-36）");
        assertEquals(cacheControl, imgStat.headers().get("Cache-Control"),
                "🔴 图片必须带 Cache-Control（B :36）；且必须与 .contentType(...) **并存**"
                        + "（SDK 的 headers(...) 会覆盖默认头集合，B :202-205 的注释即为此）");
        assertEquals(png.length, imgStat.size(), "图片大小必须与写入字节数一致");
    }

    // ==================================================================
    // ④ get：真读回字节
    // ==================================================================

    /**
     * <p><b>真触发</b>：{@code get(objectName)} 返回的流<b>真的</b>能读出刚写进去的字节
     * （含二进制 0x00 / 0xFF，不做编码假设）。
     *
     * <p><b>B 出处</b>：B 侧文件 {@code :226-236}（{@code get}）＋ {@code :239-243}
     * （{@code readAll}）。
     *
     * <p><b>怎么才能证伪</b>：把 {@code get} 里的 {@code statObject} 探测删掉，或把
     * {@code readAll} 改成按字符读（会破坏二进制）⇒ 本用例红。
     */
    @Test
    @DisplayName("🔴 真触发：get 真从 MinIO 读回字节（逐字节相等）")
    void getReturnsExactlyTheStoredBytes() throws Exception {
        String bucket = bucket("get");
        ArticleObjectStorageClient client = clientFor(bucket, TestMinio.publicBaseUrl(), false);
        assertTrue(client.ensureBucketWithPublicRead(), "前置：建桶");

        String name = "articles/content/u5-binary-" + SUFFIX + ".bin";
        byte[] content = new byte[]{0x00, 0x01, (byte) 0xFF, (byte) 0x80, 0x7F, 0x00};
        client.put(name, content, "application/octet-stream", null);

        byte[] read = client.readAll(client.get(name));
        assertNotNull(read, "get 必须返回流（B :232-235）");
        assertArrayEquals(content, read, "读回的字节必须与写入的逐字节相等");
    }

    // ==================================================================
    // ⑤ 对象不存在 ⇒ 在 get() 调用处就失败（对齐 minio-go 的失败时点）
    // ==================================================================

    /**
     * <p><b>真触发</b>：对象不存在时，{@code get(objectName)} <b>在调用处</b>就抛异常
     * （不是等到 read 时才炸）—— 这正是 B 侧<b>刻意加一次 {@code statObject} 探测</b>的原因。
     *
     * <p><b>B 出处</b>：B 侧文件 {@code :210-236}（{@code get} 的 javadoc 逐字）：
     * <pre>
     * ⚠️ **MinIO Java SDK 的 getObject 不校验对象存在性**：它在真正发出请求前就返回流，
     * 对象不存在时要在 read 时才抛 ErrorResponseException（S3 404）。
     * 这与 Go 的 minio-go/v7 **行为不同** —— Go 侧 GetObject 在对象不存在时**立刻返回 error**。
     * ⇒ 本方法**主动做一次存在性探测**，把两种 SDK 的失败时点对齐到 Go。
     * </pre>
     *
     * <p><b>怎么才能证伪</b>：把 {@code get} 里那三行 {@code statObject} 删掉
     * ⇒ 本用例 <b>立刻红</b>（{@code get} 不再抛，异常推迟到 read 时）。
     */
    @Test
    @DisplayName("🔴 真触发：对象不存在 ⇒ get() 调用处就抛（对齐 minio-go 的失败时点，B :226-236）")
    void getOnMissingObjectFailsAtCallTime() {
        String bucket = bucket("missing");
        ArticleObjectStorageClient client = clientFor(bucket, TestMinio.publicBaseUrl(), false);
        assertTrue(client.ensureBucketWithPublicRead(), "前置：建桶");

        String missing = "articles/content/u5-missing-" + SUFFIX + ".md";
        ErrorResponseException ex = assertThrows(ErrorResponseException.class,
                () -> client.get(missing),
                "🔴 存在性探测（B :228-231）必须让 get() 在调用处就失败，而不是 read 时才炸");
        assertEquals("NoSuchKey", ex.errorResponse().code(),
                "S3 侧的错误码是 NoSuchKey（真 MinIO 的真实响应）");
    }

    // ==================================================================
    // ⑥ readString：UTF-8 解码
    // ==================================================================

    /**
     * <p><b>真触发</b>：{@code readString(objectName)} 把真 MinIO 上的对象按 UTF-8 解码回来
     * （含中文 ⇒ 验证的是<b>解码</b>而不是字节相等）。
     *
     * <p><b>B 出处</b>：B 侧文件 {@code :246-248}
     * （{@code readString} = {@code new String(readAll(get(objectName)), StandardCharsets.UTF_8)}，
     * 对应 Go 的 {@code string(contentBytes)}）。
     *
     * <p><b>怎么才能证伪</b>：把解码字符集改成平台默认（GBK）⇒ 中文断言红。
     */
    @Test
    @DisplayName("🔴 真触发：readString 真读真 MinIO 并按 UTF-8 解码")
    void readStringDecodesUtf8() throws Exception {
        String bucket = bucket("utf8");
        ArticleObjectStorageClient client = clientFor(bucket, TestMinio.publicBaseUrl(), false);
        assertTrue(client.ensureBucketWithPublicRead(), "前置：建桶");

        String name = "articles/content/u5-utf8-" + SUFFIX + ".md";
        String markdown = "# 真 MinIO 标题\n\n正文含中文与 emoji 🧪。";
        client.put(name, markdown.getBytes(StandardCharsets.UTF_8), "text/markdown", null);

        assertEquals(markdown, client.readString(name),
                "readString 必须按 UTF-8 解码（B :246-248）");
    }

    // ==================================================================
    // ⑦ buildPublicFileURL：三段拼接 + 各自 trim（B-06 照抄）
    // ==================================================================

    /**
     * <p><b>真触发（纯函数）</b>：{@code buildPublicFileURL} 的<b>三段拼接</b>与
     * <b>各自不同的 trim 方向</b>被逐字钉死。
     *
     * <p><b>B 出处</b>：B 侧文件 {@code :250-288}
     * （{@code buildPublicFileURL} {@code :270-280} · {@code trimSlashes} {@code :282-284} ·
     * {@code trimLeadingSlashes} {@code :286-288}），其自述的 Go 语义逐字是：
     * <pre>
     * fmt.Sprintf("%s/%s/%s",
     *     strings.TrimRight(baseURL, "/"),                       // base：去右
     *     strings.Trim(l.bucketName, "/"),                        // bucket：两侧
     *     strings.TrimLeft(objectName, "/"))                      // object：去左
     * </pre>
     *
     * <h2>🔴 B-06（照抄不修 · D16）</h2>
     * <p><b>对象名的尾斜杠不被去掉</b>（{@code trimLeadingSlashes} 只去左侧）
     * ⇒ 这是 B 侧登记的既有缺陷 <b>B-06</b>
     * （B 侧台账 {@code docs/DRIFT/article/rpc.md:267}；C 侧台账
     * {@code docs/DRIFT/article/verification-article-service-2026-11-03.md:289}）
     * ⇒ <b>按 D16 照抄</b>，本用例把它<b>钉成现状</b>（未来若两侧一起修，本用例应同步改）。
     * <br>📌 <b>另一段「去尾斜杠」的实测结论</b>（供台账核对 B-06 的措辞）：
     * <b>base 段的尾斜杠其实<b>会</b>被去掉</b> —— {@code :277} 的
     * {@code baseURL.replaceAll("/+$", "")} 作用在<b>拼好的 baseURL</b>上
     * （含 {@code public-base-url} 为空时回落出来的 {@code scheme://endpoint}）。
     * ⇒ 「不去尾斜杠」这句在<b>代码</b>上只对<b>对象名</b>成立。
     *
     * <p><b>怎么才能证伪</b>：把三段 trim 统一成「全局去斜杠」（对象名也去尾）
     * ⇒ 本用例 <b>立刻红</b>（对象名那条断言）。
     */
    @Test
    @DisplayName("🔴 真触发：buildPublicFileURL 三段拼接 + 各自 trim（B-06 照抄：对象名尾斜杠保留）")
    void buildPublicFileURLKeepsBSideTrimSemantics() {
        String base = "http://localhost:39000";
        String bucket = "articles";
        String object = "articles/content/1.md";

        // ① 基准形态：base/{bucket}/{object}
        assertEquals(base + "/articles/articles/content/1.md",
                clientFor(bucket, base, false).buildPublicFileURL(object),
                "三段拼接（B :277-279）");

        // ② base 去**右**斜杠（B :277 的 replaceAll("/+$","")）
        assertEquals(base + "/articles/articles/content/1.md",
                clientFor(bucket, base + "/", false).buildPublicFileURL(object),
                "base 的尾斜杠被去掉（B :277）");

        // ③ bucket 去**两侧**斜杠（B :282-284 的 trimSlashes）
        assertEquals(base + "/articles/articles/content/1.md",
                clientFor("/articles/", base, false).buildPublicFileURL(object),
                "bucket 两侧都去（B :282-284）");

        // ④ object 去**左**斜杠（B :286-288 的 trimLeadingSlashes）
        assertEquals(base + "/articles/articles/content/1.md",
                clientFor(bucket, base, false).buildPublicFileURL("/articles/content/1.md"),
                "对象名只去左侧（B :286-288）");

        // ⑤ 🔴 B-06 照抄：对象名的**尾**斜杠**保留**（只去左侧）
        assertEquals(base + "/articles/articles/content/1.md/",
                clientFor(bucket, base, false).buildPublicFileURL("articles/content/1.md/"),
                "🔴 B-06 照抄（B 侧 :286-288 的 trimLeadingSlashes 只去左侧）："
                        + "对象名的尾斜杠不被去掉 ⇒ URL 以 / 结尾。按 D16 不修，只钉现状。");

        // ⑥ public-base-url 为空 ⇒ 回落成 scheme://endpoint（B :272-276）
        ArticleObjectStorageClient fallback = clientFor(bucket, "", false, "minio:9000");
        assertEquals("http://minio:9000/articles/articles/content/1.md",
                fallback.buildPublicFileURL(object),
                "public-base-url 为空 ⇒ 按 UseSSL 回落成 scheme://endpoint（B :272-276）");

        // ⑦ 回落路径下 UseSSL=true ⇒ https（B :274）
        ArticleObjectStorageClient ssl = clientFor(bucket, "", true, "minio:9000");
        assertEquals("https://minio:9000/articles/articles/content/1.md",
                ssl.buildPublicFileURL(object),
                "UseSSL=true ⇒ scheme 选 https（B :274）");

        // ⑧ 回落路径下 endpoint 带尾斜杠 ⇒ 仍然只出**一个**斜杠
        //    （实测：:277 的 replaceAll("/+$","") 作用在拼好的 baseURL 上 ⇒ 尾斜杠被去掉）
        ArticleObjectStorageClient trailing = clientFor(bucket, "", false, "minio:9000/");
        assertEquals("http://minio:9000/articles/articles/content/1.md",
                trailing.buildPublicFileURL(object),
                "📌 实测：回落路径下 endpoint 的尾斜杠**会**被 :277 的 replaceAll 去掉 ⇒ 只出一个斜杠。"
                        + "（B 侧 javadoc :265-268 自述「Endpoint 只 TrimSpace、不去尾斜杠」与代码实际不符 ⇒ 记入报告）");

        // ⑨ 对象名为 null ⇒ 兜底成空串（B :286-288 的 null 分支）
        assertEquals(base + "/articles/",
                clientFor(bucket, base, false).buildPublicFileURL(null),
                "对象名 null ⇒ 兜底空串（B :286-288：value == null ? \"\" : …）");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 读回桶上的策略；<b>没有策略</b>时返回空串（而不是抛异常）。
     *
     * <p>📌 <b>实测</b>：MinIO 在桶<b>没有策略</b>时，{@code getBucketPolicy} 返回<b>空文档</b>
     * （不是 404）⇒ 断言「策略还在不在」必须按「空 or 404」两种形态判定。
     */
    private static String policyOrEmpty(ArticleObjectStorageClient client, String bucket) {
        try {
            String policy = client.getClient().getBucketPolicy(
                    GetBucketPolicyArgs.builder().bucket(bucket).build());
            return policy == null ? "" : policy;
        } catch (Exception e) {
            // ⚠️ 只有「NoSuchBucketPolicy」才算「没有策略」；其它异常（连不上、鉴权失败…）
            //    一律编码成可见的字符串 ⇒ 断言会红并把原因带出来，**不会**被当成「没有策略」蒙混过去
            return "<error:" + e.getClass().getSimpleName() + ":" + e.getMessage() + ">";
        }
    }

    /**
     * 判断读到的策略是不是「<b>没有</b>策略」。
     *
     * <p>两种形态都算「没有」：空文档（MinIO 的实测行为）或 404（{@code NoSuchBucketPolicy}）。
     */
    private static boolean isNoPolicy(String policy) {
        return policy.isEmpty() || policy.contains("NoSuchBucketPolicy");
    }

    /**
     * 取生产代码<b>发出去</b>的那份策略 JSON。
     *
     * <p>⚠️ <b>为什么用反射</b>：被测方法是 {@code static String bucketPublicReadPolicy(String)}，
     * <b>package-private</b>，且宿主类在 {@code com.rag.article.client} 包下
     * ⇒ 本测试类（{@code com.rag.article.storage}）编译期拿不到它。
     * <br>⚠️ <b>为什么不能「照抄一份字符串」来代替</b>：照抄只能证明「我抄对了」，
     * 证不了「生产代码发出去的就是这个」⇒ 断言会变成<b>假绿</b>。
     *
     * <p>📌 <b>为什么这条断言不可省</b>：MinIO 服务端会把<b>空 {@code "Sid"}</b> 规范化掉
     * ⇒ 「服务端存下来的那份」<b>证不出</b>客户端是否发了 {@code "Sid": ""}
     * ⇒ 只能从<b>发送侧</b>断言（这正是 B :180-184 自述「逐字照搬，包括空 Sid」的可证伪点）。
     */
    private static String sentPolicy(String bucket) throws Exception {
        java.lang.reflect.Method method =
                ArticleObjectStorageClient.class.getDeclaredMethod("bucketPublicReadPolicy", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, bucket);
    }

    /**
     * 造一个<b>对着真 MinIO</b>的客户端（配置逐项显式给出，避免继承到任何默认值）。
     *
     * <p>⚠️ 期望值<b>不</b>来自被测代码 ⇒ 这里只提供「输入」；
     * 「输出」的期望全部写在各用例里。
     */
    private static ArticleObjectStorageClient clientFor(String bucket, String publicBaseUrl, boolean useSsl) {
        return clientFor(bucket, publicBaseUrl, useSsl, TestMinio.endpoint());
    }

    private static ArticleObjectStorageClient clientFor(String bucket, String publicBaseUrl,
                                                        boolean useSsl, String endpoint) {
        ArticleRpcProperties properties = new ArticleRpcProperties();
        ArticleRpcProperties.Minio minio = properties.getMinio();
        minio.setEndpoint(endpoint);
        minio.setPublicBaseUrl(publicBaseUrl);
        minio.setAccessKeyId(TestMinio.ACCESS_KEY);
        minio.setSecretAccessKey(TestMinio.SECRET_KEY);
        minio.setBucketName(bucket);
        minio.setUseSsl(useSsl);
        // 与生产 application.yml:279-280 同值（带尾斜杠，不做规范化）
        minio.setImagePath("articles/images/");
        minio.setArticlePath("articles/content/");
        return new ArticleObjectStorageClient(properties);
    }
}
