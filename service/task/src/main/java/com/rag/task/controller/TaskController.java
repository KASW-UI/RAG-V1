package com.rag.task.controller;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》对应条目。
 *
 * <h2>⚠️ L1 路由契约（Go 正推，勿凭直觉改）</h2>
 * <pre>
 *   Go 出处：Go `rest.WithPrefix("/taskcenter/v1")` —— ⚠️ **注意 `taskcenter` 不是 `task`**
 *   Go 侧 method 分布：POST
 *   端点数：1
 * </pre>
 *
 * <p>Go 侧全部端点清单（Method + Path）：
 * <pre>
 *     POST   /taskcenter/v1/task/get
 * </pre>
 *
 * <p><b>⚠️ 鉴权划分（L1）</b>：⚠️ **无 `WithJwt` 组 ⇒ 全部端点免鉴权**
 * <p><b>⚠️ method 不是"统一 POST"</b> —— 除 users 域（HUMAN_ONLY.MD 决策，已签字）外，
 * Go 各域的 method 是<b>混合</b>的：article=Get3/Post2/Put1/Delete1、favorite=Get2/Post3/Delete2、
 * hot=Get1、message=Get1+Post12、comment/follow/like/task=全 Post。
 */
public class TaskController {
}
