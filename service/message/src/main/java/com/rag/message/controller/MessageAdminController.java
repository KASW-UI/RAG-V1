package com.rag.message.controller;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》对应条目。
 *
 * <h2>⚠️ L1 路由契约（Go 正推，勿凭直觉改）</h2>
 * <pre>
 *   Go 出处：Go `rest.WithPrefix("/message/v1")` + **第二组** `rest.WithPrefix("/message/v1/admin")`（两套 JWT：UserAuth / AdminAuth）
 *   Go 侧 method 分布：GET, POST
 *   端点数：13
 * </pre>
 *
 * <p>Go 侧全部端点清单（Method + Path）：
 * <pre>
 *     POST   /message/v1/conversation/list
 *     POST   /message/v1/conversation/messages
 *     POST   /message/v1/conversation/read
 *     POST   /message/v1/notification/list
 *     POST   /message/v1/notification/read
 *     POST   /message/v1/notification/read/all
 *     POST   /message/v1/unread/summary
 *     POST   /message/v1/chat/send
 *     GET    /message/v1/admin/conversation/list
 *     GET    /message/v1/admin/conversation/messages
 *     POST   /message/v1/admin/conversation/read
 *     POST   /message/v1/admin/chat/send
 *     POST   /message/v1/admin/notification/send
 * </pre>

 * <p><b>第二组路由</b>：Go `rest.WithPrefix("/message/v1/admin")`，JWT secret 用 **AdminAuth**（与 `/message/v1` 组的 UserAuth 不同）。 *
 * <p><b>⚠️ 鉴权划分（L1）</b>：**两组 JWT**：`/message/v1` 用 UserAuth、`/message/v1/admin` 用 **AdminAuth**
 * <p><b>⚠️ method 不是"统一 POST"</b> —— 除 users 域（HUMAN_ONLY.MD 决策，已签字）外，
 * Go 各域的 method 是<b>混合</b>的：article=Get3/Post2/Put1/Delete1、favorite=Get2/Post3/Delete2、
 * hot=Get1、message=Get1+Post12、comment/follow/like/task=全 Post。
 */
public class MessageAdminController {
}
