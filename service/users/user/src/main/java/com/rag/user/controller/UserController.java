package com.rag.user.controller;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》对应条目。
 *
 * <h2>⚠️ L1 路由契约（Go 正推，勿凭直觉改）</h2>
 * <pre>
 *   Go 出处：⚠️ **签字偏离**：Go 为 `/usercenter/v1/user/*`，本仓库继承 RAG 的 `/user/v1/*`（见 `docs/Intentional_Deviations/_cross/signed-deviations.md`）
 *   Go 侧 method 分布：DELETE, GET, POST, PUT
 *   端点数：9
 * </pre>
 *
 * <p>Go 侧全部端点清单（Method + Path）：
 * <pre>
 *     POST   /user/v1/register
 *     POST   /user/v1/login
 *     POST   /user/v1/logout
 *     POST   /user/v1/getuser
 *     PUT    /user/v1/updateuser
 *     DELETE /user/v1/deleteuser
 *     POST   /user/v1/uploadavatar
 *     POST   /user/v1/selectavatar
 *     GET    /user/v1/avatarhistory
 * </pre>
 *
 * <p><b>⚠️ 鉴权划分（L1）</b>：**public 段 = register + login**
 * <p><b>⚠️ method 不是"统一 POST"</b> —— 除 users 域（HUMAN_ONLY.MD 决策，已签字）外，
 * Go 各域的 method 是<b>混合</b>的：article=Get3/Post2/Put1/Delete1、favorite=Get2/Post3/Delete2、
 * hot=Get1、message=Get1+Post12、comment/follow/like/task=全 Post。
 */
public class UserController {
}
