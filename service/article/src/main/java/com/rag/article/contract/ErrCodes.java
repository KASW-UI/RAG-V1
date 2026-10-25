package com.rag.article.contract;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》对应条目。
 *
 * <h2>⚠️ 硬约束：错误契约<b>逐域隔离</b>，不可跨域统一</h2>
 * <p>Go 侧实测（8 个域各不相同）：
 * <pre>
 *   同一语义的码值在各域<b>不同</b>：
 *     ErrorDbUpdate   favorite=5008  comment/like/user=5002  article=5002
 *     ErrorDbSelect   favorite=5007  comment/like/user=5003
 *     「用户不存在」段   favorite/follow/message=5101+   comment/like/user=1001+
 *
 *   同一语义的<b>文案与语言</b>在各域<b>不同</b>：
 *     CodeServerBusy   article/comment/favorite/like/user = "服务繁忙"（中文）
 *                       follow/hot/message                 = "service busy"（英文）
 * </pre>
 *
 * <p>⇒ <b>禁止</b>为了「统一」而把各域的错误码/文案合并成一个共享基座 —— 那会改掉 8 个域的可见行为（L1）。
 * <p>⇒ 本包的值必须<b>逐字照抄</b> Go 侧对应域的 {@code errmsg.go}（含码值、含文案、含语言）。
 * <p><b>Go 出处：对应域 `common/errmsg*.go` 的 const 块（Success=200 / Error=500 / CodeServerBusy=1015 + 域专属段）。</b>
 */
public class ErrCodes {
}
