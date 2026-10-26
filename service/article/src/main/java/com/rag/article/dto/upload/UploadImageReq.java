package com.rag.article.dto.upload;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》对应条目。
 * <p>Go 侧同名 struct 存在 —— RAG 形态改用 `@RequestParam MultipartFile`（multipart 表单）承载，属 L4 idiomatization；本 DTO 若保留则用于承载 multipart 之外的其余字段。
 */

public record UploadImageReq(
) {
}
