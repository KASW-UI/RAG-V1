package com.rag.article.dto.upload;

/**
 * <p><b>职责</b>：图片上传的入参 —— multipart 的<b>字段名</b>与<b>文件内容</b>。
 *
 * <p><b>B 出处</b>：B 侧<b>没有</b>这个 DTO —— B 侧直接用 Spring 的
 * {@code MultipartFile}（B {@code ArticleController.java:237-241}），
 * 字段名常量落在 B 侧 {@code ArticleUploadService.FORM_FIELD}（B {@code :89}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>新增一个 DTO</b>（B 侧无对应物）——
 *       把「multipart 字段名」与「文件内容」收成<b>一个不可变载体</b>，
 *       让 service 层<b>不依赖 Servlet API</b>（{@code MultipartFile}）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li>multipart 字段名仍是 {@code "image"}（<b>L1 契约</b>，逐字未改）；</li>
 *         <li>文件名仍取 multipart 的<b>原始 filename</b>（<b>不做清洗</b>，
 *             B {@code ArticleUploadService.java:118-119}）；</li>
 *         <li>文件内容仍取 {@code file.getBytes()}（B {@code :106-107}）。</li>
 *       </ul>
 *       ⇒ <b>wire 形状逐字节相同</b>。</li>
 *   <li><b>B 出处</b>：{@code .../service/ArticleUploadService.java:89}（字段名常量）
 *       ＋ {@code .../service/ArticleUploadService.java:103-120}（读文件名与字节）
 *       ＋ {@code .../controller/ArticleController.java:237-241}（multipart 取值）。</li>
 * </ol>
 *
 * <h2>⚠️ 字段名 {@code "image"} 是 L1 契约</h2>
 * <p>Go 侧 {@code r.FormFile("image")}（B {@code upload_image_handler.go:14}）
 * ⇒ 客户端必须以 {@code image} 为字段名上传 ⇒ <b>不可改</b>。
 */
public record UploadImageReq(

        /**
         * multipart 字段名 —— 恒为 {@code "image"}（见 {@link #FORM_FIELD}）。
         *
         * <p>⚠️ <b>保留在 DTO 里</b>而不是只放在常量：让「这份内容属于哪个字段」随内容一起传，
         * 避免 service 层再猜。
         */
        String fieldName,

        /**
         * multipart 的<b>原始</b>文件名 —— <b>不做清洗</b>（B 侧 {@code ArticleUploadService.java:118-119}）。
         *
         * <p>⚠️ 可能为 {@code null}（客户端未带 filename）⇒ 下发时按空串处理。
         */
        String fileName,

        /**
         * 文件字节内容。
         *
         * <p>⚠️ 读文件失败在 Go 侧是<b>另一条</b>失败路径（恒 400 text/plain），
         * 由 service 层在读取时处理（本 DTO 只承载<b>已读出</b>的内容）。
         */
        byte[] content) {

    /**
     * Go {@code r.FormFile("image")} 的 multipart 字段名 —— <b>L1 契约</b>，不可改。
     *
     * <p><b>B 出处</b>：{@code .../service/ArticleUploadService.java:89}（B 侧同名常量）
     * ＋ {@code .../controller/ArticleController.java:239}（B 侧用它做 {@code @RequestParam} 的值）。
     */
    public static final String FORM_FIELD = "image";
}
