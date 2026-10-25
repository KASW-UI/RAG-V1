package com.rag.task.domain.entity;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》+《第二轮深挖-契约盘点》。
 *
 * <h2>⚠️ 位置说明（2026-10-25 第二轮深挖修正）</h2>
 * <p>Go 出处：**`service/task/rpc/internal/sink/like_sink.go`** 的 `type UserLikeCount`
 * （表名 `user_like_count`）—— **不在 `internal/model/` 下**，而在 `internal/sink/` 里。
 *
 * <p>⇒ 第一轮深挖曾按目录名盘点，误判为「Go 侧 task 域无此实体」；**第二轮按导出符号盘点后已翻案**。
 * ⇒ 本实体<b>存在且正确</b>，只是 Go 把它放在 sink 逻辑包内（本仓库归入 `rpc/domain/`，性质等价）。
 */
public class UserLikeCountEntity {
}
