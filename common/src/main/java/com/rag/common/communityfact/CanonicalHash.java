package com.rag.common.communityfact;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》。
 *
 * <p>Go 出处：wire.go 的 `CanonicalHash(raw []byte) (string, error)` + `ValidHash(value)`。
⚠️ **canonical JSON + sha256** —— 事件指纹 / 幂等键的来源。
⚠️ Go 用 `github.com/cyberphone/json-canonicalization`（RFC 8785 JCS）⇒ **Jackson 的 `SORT_PROPERTIES_ALPHABETICALLY` 不等价**（JCS 还规定了数字规范化与字符串转义规则）。
⚠️ 指纹若不一致 ⇒ 去重失效 / 回执校验失败 ⇒ **L3 幂等性**。
 */
public class CanonicalHash {
}
