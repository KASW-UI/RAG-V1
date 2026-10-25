package com.rag.article.controller;

/**
 * <p><b>TODO：职责待补</b> —— 推导依据见《完全spring化的文件树》对应条目。
 *
 * <h2>⚠️ L1 路由契约（Go 正推，勿凭直觉改）</h2>
 * <pre>
 *   Go 出处：Go `rest.WithPrefix("/v1")`（**article 是唯一不带域名前缀的域**）
 *   Go 侧 method 分布：DELETE, GET, POST, PUT
 *   端点数：7
 * </pre>
 *
 * <p>Go 侧全部端点清单（Method + Path）：
 * <pre>
 *     GET    /v1/articles
 *     GET    /v1/article
 *     GET    /v1/user/articles
 *     POST   /v1/article
 *     PUT    /v1/article
 *     DELETE /v1/article
 *     POST   /v1/upload
 * </pre>
 *
 * <p><b>⚠️ 鉴权划分（L1）</b>：**有 public 段**（`WithJwt` 只覆盖其中一组）
 * <p><b>⚠️ method 不是"统一 POST"</b> —— 除 users 域（HUMAN_ONLY.MD 决策，已签字）外，
 * Go 各域的 method 是<b>混合</b>的：article=Get3/Post2/Put1/Delete1、favorite=Get2/Post3/Delete2、
 * hot=Get1、message=Get1+Post12、comment/follow/like/task=全 Post。
 */
public class ArticleController {
}
