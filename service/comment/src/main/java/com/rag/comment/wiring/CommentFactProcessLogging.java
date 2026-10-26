package com.rag.comment.wiring;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》对应条目。
 * <p>对应 Go 侧 {@code service/comment/rpc/cmd/fact-dispatch/main.go}（身份 {@code rtw-comment-fact-dispatch}）
 * 与 {@code service/comment/rpc/cmd/fact-authority/main.go}（身份 {@code rtw-comment-fact-authority}）——
 * 两个 Go 独立进程各自建一份 {@code communityfact.NewProcessLogger}，共 3 份进程级 logger 身份
 * （业务 RPC / fact-dispatch / fact-authority）。合并进域内组件（D1）后，这两份身份仍需各自独立：
 * ⚠️ <b>不得用 SLF4J 全局 MDC 承载</b>（MDC 跨线程插值会把两份身份混成一份）。
 * <p>形态对齐：{@code like} 域的 {@code LikeFactProcessLogging} / {@code favorite} 域的
 * {@code FavoriteFactProcessLogging}（A11：三域各须有 {@code wiring/<域>FactProcessLogging}）。
 */

public class CommentFactProcessLogging {
}
