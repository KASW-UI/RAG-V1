package com.rag.favorite.dto;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》对应条目。
 * <p>Go 侧同名 struct 存在（空 struct）—— RAG 形态改用 `assertGoEmptyStructBody()` 手工复刻 Go 的「GET 端点也解析并校验 body」行为，属 L4。
 */

public record GetFavoriteFoldersReq(
) {
}
