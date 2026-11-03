package com.rag.article.storage;

import com.google.protobuf.ByteString;
import com.rag.article.client.ArticleObjectStorageClient;
import com.rag.article.domain.service.ArticleDomainService;
import com.rag.proto.article.UploadFileRequest;
import com.rag.proto.article.UploadFileResponse;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>U-11 剩余的 1/6 · 真触发</b>：{@code domain/service/ArticleDomainService#uploadFile}
 * —— 那条<b>唯一还没被真触发过</b>的写路径 —— 对着<b>真 MinIO 容器</b>实跑。
 *
 * <h2>① 为什么它是 U-11 里唯一「需要外部环境」的那一条</h2>
 * <p>U-11 的其余 5 条（创建 / 删除 / 撤回发布 / 撤回并删除 / 审核入队）都在
 * {@code domain/service/ArticleWritePathRealPgTest} 里用<b>真 PG</b> 验完了
 * ⇒ 唯独 {@code uploadFile} 需要<b>真的写进对象存储</b>
 * ⇒ ⇒ 之前一直挂在「要真 MinIO ⇒ 留给 U-5/U-12 那组」名下 ⇒ 本类把它补掉。
 *
 * <h2>② B 侧 oracle（逐条 · 带行号）</h2>
 * <table border="1">
 *   <caption>判据 ↔ B 侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th></tr>
 *   <tr><td>对象名 = {@code <imagePath><雪花ID><ext>}</td>
 *       <td>{@code service/article/article-rpc/src/main/java/…/service/ArticleDomainService.java:859}
 *           （{@code storage.getObjectNamePrefixForImage() + id + ext}）</td></tr>
 *   <tr><td>{@code ext} = 文件名<b>最后一个点之后</b>的部分（<b>含点</b>）；无点 ⇒ 空串</td>
 *       <td>同上 {@code :852-858}（Go 的 {@code filepath.Ext} 语义）</td></tr>
 *   <tr><td>content-type = {@code mime.TypeByExtension(ext)}；查不到 ⇒ {@code application/octet-stream}</td>
 *       <td>同上 {@code :861} ＋ {@code :966-975}（回落<b>必须在本方法内</b>完成）</td></tr>
 *   <tr><td>写入时带 {@code Cache-Control: public, max-age=31536000, immutable}</td>
 *       <td>同上 {@code :871-872}（<b>图片上传带</b>；markdown 上传不带）</td></tr>
 *   <tr><td>返回 {@code fileUrl = storage.buildPublicFileURL(objectName)}</td>
 *       <td>同上 {@code :884-886}</td></tr>
 *   <tr><td>写入失败 ⇒ {@code ArticleExceptions.raw(e)}（<b>不带</b> gRPC status）</td>
 *       <td>同上 {@code :873-878}</td></tr>
 * </table>
 *
 * <h2>③ ⚠️ 期望值来源是 <b>E-2 级</b>（源码级），不是 E-4</h2>
 * <p><b>B 侧对 {@code uploadFile} 没有任何测试</b>（已全仓检索 {@code article-rpc/src/test/}
 * ⇒ 命中处<b>全是 mock</b>）⇒ ⇒ 期望值只能从 B 侧<b>实现源码</b>逐字读出
 * （D16：B 侧自身就是基准）⇒ ⇒ <b>不得单独拿本类当「B 侧活体证据」</b>
 * （那件事由 U-1 的双侧对拍探针承担）。
 *
 * <h2>④ 可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把 {@code put} 的 {@code Cache-Control} 参数去掉 ⇒ 用例 ① <b>立刻红</b>
 *       （stat 的响应头里没有它）；</li>
 *   <li>把 {@code ext} 的取法改成「第一个点」⇒ 用例 ③ <b>立刻红</b>
 *       （{@code a.b.png} 的对象名会变成 {@code a.b.png} 之外的形状）；</li>
 *   <li>把 content-type 的回落<b>挪到调用点</b>（B 侧注释明确禁止）⇒ 用例 ② <b>立刻红</b>；</li>
 *   <li>把 {@code buildPublicFileURL} 的返回改成对象名 ⇒ 用例 ① <b>立刻红</b>。</li>
 * </ul>
 *
 * <h2>⑤ 为什么对象名里的雪花 ID 只能「形状断言」</h2>
 * <p>雪花 ID 由 {@code Snowflake.nextId()} 在<b>运行期</b>生成 ⇒ 期望值里写不出具体数字
 * ⇒ ⇒ 断言「前缀 = {@code getObjectNamePrefixForImage()}」＋「后缀 = 扩展名」
 * ＋「中间是<b>纯数字</b>且<b>长度</b>符合雪花 ID」⇒
 * <b>并且</b>用「对象在 MinIO 上真的存在、内容逐字节相等」把这条形状断言<b>锚在真实副作用上</b>
 * ⇒ ⇒ 不是「断言一个字符串长得对」，而是「断言对象<b>真的被写了</b>」。
 */
class ArticleUploadFileRealMinioTest extends MinioSpringTestBase {

    /** 生产默认桶名（{@code application.yml} 的 {@code article-rpc.minio.bucket-name}）。 */
    private static final String EXPECTED_IMAGE_PREFIX = "articles/images/";

    @Autowired
    private ArticleDomainService articleDomainService;

    @Autowired
    private ArticleObjectStorageClient storage;

    @Test
    @DisplayName("🔴 真触发：uploadFile 把对象真的写进 MinIO（png ⇒ image/png ＋ immutable 缓存头）")
    void pngUploadReallyWritesTheObject() throws Exception {
        byte[] content = "fake-image-bytes-for-upload".getBytes(StandardCharsets.UTF_8);

        UploadFileResponse response = articleDomainService.uploadFile(UploadFileRequest.newBuilder()
                .setFileName("seed.png")
                .setContent(ByteString.copyFrom(content))
                .build());

        // ── ① 返回的 URL 形状：<publicBaseUrl>/<bucket>/<imagePath><雪花ID><ext> ──
        String expectedPrefix = TestMinio.publicBaseUrl() + "/" + storage.getBucketName() + "/";
        String fileUrl = response.getFileUrl();
        assertTrue(fileUrl.startsWith(expectedPrefix),
                "🔴 fileUrl 必须以 <publicBaseUrl>/<bucket>/ 开头（B: buildPublicFileURL）；实际=" + fileUrl);

        String objectName = fileUrl.substring(expectedPrefix.length());
        assertTrue(objectName.startsWith(EXPECTED_IMAGE_PREFIX),
                "🔴 对象名必须以 <imagePath> 开头（B: :859）；实际=" + objectName);
        assertTrue(objectName.endsWith(".png"),
                "🔴 对象名必须带原扩展名（含点，B: :852-858）；实际=" + objectName);

        String snowflakePart = objectName.substring(EXPECTED_IMAGE_PREFIX.length(),
                objectName.length() - ".png".length());
        assertTrue(snowflakePart.matches("\\d{10,}"),
                "🔴 对象名中间必须是雪花 ID（纯数字）；实际=" + objectName);

        // ── ② 对象真的在 MinIO 上：内容逐字节相等、大小相等 ──
        StatObjectResponse stat = storage.getClient().statObject(StatObjectArgs.builder()
                .bucket(storage.getBucketName()).object(objectName).build());
        assertEquals(content.length, stat.size(),
                "🔴 对象大小必须等于上传的字节数（B: put 直接写 content）");
        assertArrayEquals(content, storage.readAll(storage.get(objectName)),
                "🔴 对象内容必须与上传的字节逐字节相等");

        // ── ③ content-type：png ⇒ image/png（不是 application/octet-stream）──
        assertEquals("image/png", stat.contentType(),
                "🔴 png 对象的 content-type 必须是 image/png（否则浏览器按二进制下载）");

        // ── ④ 缓存头：图片上传带 immutable（markdown 上传才不带）──
        String cacheControl = stat.headers().get("Cache-Control");
        assertNotNull(cacheControl, "🔴 图片上传必须带 Cache-Control（B: :871-872）；实际头=" + stat.headers());
        assertEquals("public, max-age=31536000, immutable", cacheControl,
                "🔴 Cache-Control 必须逐字一致（B: :871-872）");
    }

    @Test
    @DisplayName("🔴 真触发：文件名**无扩展名** ⇒ content-type 回落 application/octet-stream")
    void fileNameWithoutExtensionFallsBackToOctetStream() throws Exception {
        byte[] content = "no-extension-bytes".getBytes(StandardCharsets.UTF_8);

        UploadFileResponse response = articleDomainService.uploadFile(UploadFileRequest.newBuilder()
                .setFileName("noext")
                .setContent(ByteString.copyFrom(content))
                .build());

        String expectedPrefix = TestMinio.publicBaseUrl() + "/" + storage.getBucketName() + "/";
        String objectName = response.getFileUrl().substring(expectedPrefix.length());

        assertTrue(objectName.startsWith(EXPECTED_IMAGE_PREFIX),
                "对象名必须以 <imagePath> 开头；实际=" + objectName);
        assertTrue(!objectName.contains("."),
                "🔴 无扩展名 ⇒ 对象名里不该有点（B: filepath.Ext ⇒ 空串）；实际=" + objectName);

        StatObjectResponse stat = storage.getClient().statObject(StatObjectArgs.builder()
                .bucket(storage.getBucketName()).object(objectName).build());
        assertEquals("application/octet-stream", stat.contentType(),
                "🔴 回落必须发生在方法内（B: :966-975 明确禁止挪到调用点）");
    }

    @Test
    @DisplayName("🔴 真触发：多点文件名取的是**最后一个**点之后的扩展名（Go filepath.Ext 语义）")
    void extensionIsTakenFromTheLastDot() throws Exception {
        byte[] content = "multi-dot-bytes".getBytes(StandardCharsets.UTF_8);

        UploadFileResponse response = articleDomainService.uploadFile(UploadFileRequest.newBuilder()
                .setFileName("a.b.png")
                .setContent(ByteString.copyFrom(content))
                .build());

        String expectedPrefix = TestMinio.publicBaseUrl() + "/" + storage.getBucketName() + "/";
        String objectName = response.getFileUrl().substring(expectedPrefix.length());

        assertTrue(objectName.endsWith(".png"),
                "🔴 扩展名必须取最后一个点之后的部分（B: :852-858）；实际=" + objectName);
        assertTrue(objectName.startsWith(EXPECTED_IMAGE_PREFIX),
                "对象名必须以 <imagePath> 开头；实际=" + objectName);

        StatObjectResponse stat = storage.getClient().statObject(StatObjectArgs.builder()
                .bucket(storage.getBucketName()).object(objectName).build());
        assertEquals("image/png", stat.contentType(),
                "🔴 扩展名取错 ⇒ content-type 也会错（这一条把「取哪个点」钉死）");
    }
}
