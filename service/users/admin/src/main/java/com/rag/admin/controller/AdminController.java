package com.rag.admin.controller;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》对应条目。
 *
 * <h2>⚠️ L1 路由契约（Go 正推，勿凭直觉改）</h2>
 * <pre>
 *   Go 出处：⚠️ **签字偏离**：Go 为 `/admincenter/v1/admin/*`，本仓库继承 RAG 的 `/admin/v1/*`
 *   Go 侧 method 分布：DELETE, GET, POST, PUT
 *   端点数：12
 * </pre>
 *
 * <p>Go 侧全部端点清单（Method + Path）：
 * <pre>
 *     POST   /admin/v1/create
 *     POST   /admin/v1/login
 *     POST   /admin/v1/logout
 *     GET    /admin/v1/getself
 *     PUT    /admin/v1/update
 *     GET    /admin/v1/getuser
 *     GET    /admin/v1/getuserlist
 *     DELETE /admin/v1/delete
 *     POST   /admin/v1/banuser
 *     POST   /admin/v1/unbanuser
 *     POST   /admin/v1/resetuserpassword
 *     POST   /admin/v1/invite/create
 * </pre>
 *
 * <p><b>⚠️ 鉴权划分（L1）</b>：**public 段 = create + login**（第一组 AddRoutes 无 `WithJwt`）
 * <p><b>⚠️ method 不是"统一 POST"</b> —— 除 users 域（HUMAN_ONLY.MD 决策，已签字）外，
 * Go 各域的 method 是<b>混合</b>的：article=Get3/Post2/Put1/Delete1、favorite=Get2/Post3/Delete2、
 * hot=Get1、message=Get1+Post12、comment/follow/like/task=全 Post。
 */
public class AdminController {
}
