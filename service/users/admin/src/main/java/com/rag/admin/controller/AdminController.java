package com.rag.admin.controller;

import com.rag.admin.dto.admin.CreateAdminReq;
import com.rag.admin.dto.admin.CreateAdminResp;
import com.rag.admin.dto.admin.GetSelfReq;
import com.rag.admin.dto.admin.GetSelfResp;
import com.rag.admin.dto.admin.UpdateSelfReq;
import com.rag.admin.dto.admin.UpdateSelfResp;
import com.rag.admin.dto.auth.LoginReq;
import com.rag.admin.dto.auth.LoginResp;
import com.rag.admin.dto.auth.LogoutReq;
import com.rag.admin.dto.auth.LogoutResp;
import com.rag.admin.dto.invite.CreateInviteCodeReq;
import com.rag.admin.dto.invite.CreateInviteCodeResp;
import com.rag.admin.dto.user.BanUserReq;
import com.rag.admin.dto.user.BanUserResp;
import com.rag.admin.dto.user.DeleteUserReq;
import com.rag.admin.dto.user.DeleteUserResp;
import com.rag.admin.dto.user.GetUserListReq;
import com.rag.admin.dto.user.GetUserListResp;
import com.rag.admin.dto.user.GetUserReq;
import com.rag.admin.dto.user.GetUserResp;
import com.rag.admin.dto.user.ResetUserPasswordReq;
import com.rag.admin.dto.user.ResetUserPasswordResp;
import com.rag.admin.dto.user.UnBanUserReq;
import com.rag.admin.dto.user.UnBanUserResp;
import com.rag.admin.dto.user.UpdateUserReq;
import com.rag.admin.dto.user.UpdateUserResp;
import com.rag.admin.service.AdminApiService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p><b>职责</b>：admin Center 的 HTTP 出口 —— <b>13 个端点</b>，<b>全部 POST</b>，前缀 {@code /admin/v1}。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处 ——
 *       <br>① 包名 {@code com.rag.users.admin.api.controller} → {@code com.rag.admin.controller}（D7）；
 *       <br>② <b>13 个用例类合成 1 个</b> {@code AdminApiService}（D2 + 用例内聚）⇒ 本类只依赖 1 个 bean；
 *       <br>③ <b>必填校验从手写 {@code GoStyleRequired} 改成 Bean Validation</b>（本仓 L3 降级清单 D-01，
 *       已登记为 L3 降级 D-01）⇒ 本类给每个 {@code @RequestBody} 加 {@code @Valid}。
 *       <br>其余（method / path / {@code required = false} / 3 个空 struct 端点的 null 归一化）<b>逐条照抄</b>。</li>
 *   <li><b>语义还一样吗</b>：HTTP 面（method + path + 请求体可空性）<b>逐条一致</b>；
 *       唯一差异是「必填字段缺失」这一条的<b>错误形状</b>：B 侧是 {@code 400 + text/plain
 *       field "x" is not set}，C 侧是 Hibernate Validator 决定的 {@code 400 + JSON}
 *       ⇒ 那是 <b>已登记的 L3 降级 D-01</b>（本仓 L3 降级清单 D-01），不需要用户签字。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/controller/AdminController.java}
 *       （13 个 {@code @PostMapping} 的 method / path / 签名逐条照抄）。</li>
 * </ol>
 *
 * <p><b>L1 外部契约冻结（映射表 admin.md §6 —— 13 个端点，全 POST）</b>：
 * <pre>
 *   POST /admin/v1/login             (public) → AdminApiService.login
 *   POST /admin/v1/create            (public) → AdminApiService.createAdmin
 *   POST /admin/v1/logout            (JWT)   → AdminApiService.logout
 *   POST /admin/v1/getself           (JWT)   → AdminApiService.getSelf
 *   POST /admin/v1/update            (JWT)   → AdminApiService.updateSelf
 *   POST /admin/v1/updateuser        (JWT)   → AdminApiService.updateUser
 *   POST /admin/v1/delete            (JWT)   → AdminApiService.deleteUser
 *   POST /admin/v1/resetuserpassword (JWT)   → AdminApiService.resetUserPassword
 *   POST /admin/v1/banuser           (JWT)   → AdminApiService.banUser
 *   POST /admin/v1/unbanuser         (JWT)   → AdminApiService.unBanUser
 *   POST /admin/v1/getuser           (JWT)   → AdminApiService.getUser
 *   POST /admin/v1/getuserlist       (JWT)   → AdminApiService.getUserList
 *   POST /admin/v1/invite/create     (JWT)   → AdminApiService.createInviteCode
 * </pre>
 * <p>⚠️ <b>F-6 / F-7</b>：本文件<b>此前</b>的空壳 javadoc 写的是「PUT / GET 混合」且只列了
 * <b>12</b> 个端点（漏 {@code /updateuser}）⇒ <b>那是错的</b>（以 映射表 admin.md §6 的 13 个全 POST 为准），
 * 本 javadoc 已按 §6.1 重写。
 * <p>⚠️ <b>public vs JWT</b>（映射表 admin.md §6）：<b>public = {@code /create} + {@code /login}</b> 两个，
 * 其余 11 个走 JWT ⇒ 路径白名单由 {@code interceptor/AdminJwtAuthInterceptor} 持有（另一条 lane）。
 *
 * <p><b>L4 idiomatization：10 个端点用必填 {@code @RequestBody} + 3 个空 struct 端点用
 * {@code @RequestBody(required = false)}</b>
 * <pre>
 *   B 出处（三段，全部实测源码，非推断）：
 *   1. B 侧 13 个 handler 一律 {@code if err := httpx.Parse(r, &req); err != nil}，req 是包级零值 struct。
 *   2. go-zero v1.10.2 rest/httpx/requests.go:153 {@code withJsonBody} =
 *      {@code r.ContentLength > 0 && ContentType 含 application/json}；
 *      <b>空 body 或非 JSON content-type</b> ⇒ 都走 {@code ParseJsonBody} 的
 *      {@code mapping.UnmarshalJsonMap(nil, v)} 分支。
 *   3. core/mapping/jsonunmarshaler.go:19-21 的 {@code UnmarshalJsonMap(m map[string]any, v any)} 收的是
 *      <b>有类型的 nil map</b> ⇒ 在 unmarshaler.go:988 的 type switch 里命中 {@code case map[string]any}
 *      ⇒ 照常遍历全部字段 ⇒ 标量必填字段照样走 {@code newInitError} ⇒ {@code field %q is not set}。
 *
 *   ⇒ <b>空 body 与 {@code {}} 都应被拒</b>（400）—— 对<b>有必填字段</b>的 10 个端点。
 *   ⇒ <b>非 JSON content-type</b>（body 有内容但不是 application/json）⇒ B 侧同样走
 *     {@code UnmarshalJsonMap(nil, v)} ⇒ <b>必填字段缺失 ⇒ 400</b>；
 *     ⇒ B 侧<b>没有 415 这个状态码</b>（go-zero 不做 content-type 协商）。
 * </pre>
 *
 * <p><b>⚠️ 为什么 10 个端点用「必填」{@code @RequestBody}</b>（2026-11-02 真 Tomcat 契约测试实测后改）：
 * <ul>
 *   <li>本类此前 13 个端点<b>一律</b> {@code @RequestBody(required = false)}，
 *       理由是「保留 B 侧『body 可以整个缺席』的传输层语义」——
 *       <b>但这条理由是错的</b>：B 侧「body 缺席」=「零值 struct」=<b>必填字段缺失 ⇒ 400</b>，
 *       <b>不是</b>「接受空 body」。</li>
 *   <li>而 Spring 侧 {@code required = false} + <b>空 body</b> ⇒
 *       {@code readWithMessageConverters} 返回 {@code null}
 *       （spring-webmvc 6.2.16 {@code AbstractMessageConverterMethodArgumentResolver} 的
 *       {@code handleEmptyBody} 分支）⇒ <b>{@code @Valid} 对 null 不生效</b>
 *       ⇒ req 传进用例层是 {@code null} ⇒ <b>NPE ⇒ HTTP 500</b>
 *       ⇒ ⇒ <b>把「客户端参数写错」误报成「服务端故障」</b>（实测复现）。</li>
 *   <li>⇒ 修法：10 个有必填字段的端点改用<b>必填</b> {@code @RequestBody}
 *       ⇒ 空 body ⇒ {@code HttpMessageNotReadableException}
 *       ⇒ 落到 {@code advice/AdminApiExceptionHandler#handleUnreadableBody}
 *       ⇒ <b>400 + text/plain + nosniff + 尾随换行</b>（形状与 B 侧 {@code http.Error} 一致；
 *       <b>文本内容</b>属已登记的 D-01 降级项）。</li>
 * </ul>
 *
 * <p><b>⚠️ 3 个空 struct 端点的 null 归一化</b>（{@code /logout}、{@code /getself}、{@code /invite/create}）：
 * B 侧这 3 个端点的 req 是<b>空 struct</b> ⇒ 空 body 时 go-zero 把它留在<b>零值 struct</b>（不是 null）、
 * 且<b>不报错</b>（没有必填字段）⇒ 本类用 {@code new XxxReq()} 归一化，保证与 B 侧 同构
 * ⇒ ⇒ 这 3 个端点必须保留 {@code required = false}（若改成必填 ⇒ 空 body 会 400，
 * 而 B 侧是<b>正常受理</b> ⇒ 反而漂移）。
 */
@RestController
@RequestMapping("/admin/v1")
public class AdminController {

    private final AdminApiService adminApiService;

    public AdminController(AdminApiService adminApiService) {
        this.adminApiService = adminApiService;
    }

    // ============== public（无 JWT · 映射表 admin.md §6） ==============

    @PostMapping("/login")
    public LoginResp login(@Valid @RequestBody LoginReq req, HttpServletRequest httpRequest) {
        return adminApiService.login(req, httpRequest);
    }

    @PostMapping("/create")
    public CreateAdminResp createAdmin(@Valid @RequestBody CreateAdminReq req, HttpServletRequest httpRequest) {
        return adminApiService.createAdmin(req, httpRequest);
    }

    // ============== JWT-protected（11 个） ==============

    @PostMapping("/logout")
    public LogoutResp logout(@Valid @RequestBody(required = false) LogoutReq req, HttpServletRequest httpRequest) {
        if (req == null) {
            // B 出处：httpx.Parse 在 ContentLength == 0 时走 UnmarshalJsonMap(nil, v)，
            // 把 req 留在**零值 struct**（不是 null），且不报错。此处归一化保证与 B 侧 同构。
            req = new LogoutReq();
        }
        return adminApiService.logout(req, httpRequest);
    }

    @PostMapping("/getself")
    public GetSelfResp getSelf(@Valid @RequestBody(required = false) GetSelfReq req, HttpServletRequest httpRequest) {
        if (req == null) {
            // B 出处：httpx.Parse 在 ContentLength == 0 时走 UnmarshalJsonMap(nil, v)，
            // 把 req 留在**零值 struct**（不是 null），且不报错。此处归一化保证与 B 侧 同构。
            req = new GetSelfReq();
        }
        return adminApiService.getSelf(req, httpRequest);
    }

    @PostMapping("/update")
    public UpdateSelfResp updateSelf(@Valid @RequestBody UpdateSelfReq req, HttpServletRequest httpRequest) {
        return adminApiService.updateSelf(req, httpRequest);
    }

    @PostMapping("/updateuser")
    public UpdateUserResp updateUser(@Valid @RequestBody UpdateUserReq req, HttpServletRequest httpRequest) {
        return adminApiService.updateUser(req, httpRequest);
    }

    @PostMapping("/delete")
    public DeleteUserResp deleteUser(@Valid @RequestBody DeleteUserReq req, HttpServletRequest httpRequest) {
        return adminApiService.deleteUser(req, httpRequest);
    }

    @PostMapping("/resetuserpassword")
    public ResetUserPasswordResp resetUserPassword(@Valid @RequestBody ResetUserPasswordReq req, HttpServletRequest httpRequest) {
        return adminApiService.resetUserPassword(req, httpRequest);
    }

    @PostMapping("/banuser")
    public BanUserResp banUser(@Valid @RequestBody BanUserReq req, HttpServletRequest httpRequest) {
        return adminApiService.banUser(req, httpRequest);
    }

    @PostMapping("/unbanuser")
    public UnBanUserResp unBanUser(@Valid @RequestBody UnBanUserReq req, HttpServletRequest httpRequest) {
        return adminApiService.unBanUser(req, httpRequest);
    }

    @PostMapping("/getuser")
    public GetUserResp getUser(@Valid @RequestBody GetUserReq req, HttpServletRequest httpRequest) {
        return adminApiService.getUser(req, httpRequest);
    }

    @PostMapping("/getuserlist")
    public GetUserListResp getUserList(@Valid @RequestBody GetUserListReq req, HttpServletRequest httpRequest) {
        return adminApiService.getUserList(req, httpRequest);
    }

    @PostMapping("/invite/create")
    public CreateInviteCodeResp createInviteCode(@Valid @RequestBody(required = false) CreateInviteCodeReq req, HttpServletRequest httpRequest) {
        if (req == null) {
            // B 出处：httpx.Parse 在 ContentLength == 0 时走 UnmarshalJsonMap(nil, v)，
            // 把 req 留在**零值 struct**（不是 null），且不报错。此处归一化保证与 B 侧 同构。
            req = new CreateInviteCodeReq();
        }
        return adminApiService.createInviteCode(req, httpRequest);
    }
}
