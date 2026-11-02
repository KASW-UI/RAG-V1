package com.rag.user.controller;

import com.rag.user.dto.avatar.GetAvatarHistoryResp;
import com.rag.user.dto.avatar.SelectAvatarReq;
import com.rag.user.dto.avatar.SelectAvatarResp;
import com.rag.user.dto.avatar.UploadAvatarReq;
import com.rag.user.dto.avatar.UploadAvatarResp;
import com.rag.user.dto.auth.LoginReq;
import com.rag.user.dto.auth.LoginResp;
import com.rag.user.dto.auth.LogoutReq;
import com.rag.user.dto.auth.LogoutResp;
import com.rag.user.dto.user.CreateUserReq;
import com.rag.user.dto.user.CreateUserResp;
import com.rag.user.dto.user.DeleteUserReq;
import com.rag.user.dto.user.DeleteUserResp;
import com.rag.user.dto.user.GetUserReq;
import com.rag.user.dto.user.GetUserResp;
import com.rag.user.dto.user.UpdateUserReq;
import com.rag.user.dto.user.UpdateUserResp;
import com.rag.user.service.UserApiService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p><b>user 域 HTTP 网关</b> —— 9 个 REST 端点。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/controller/UserController.java}
 * —— 路由契约（path ＋ method）<b>逐条照抄</b>。
 *
 * <h2>⚠️ L1 路由契约冻结（勿凭直觉改）</h2>
 * <pre>
 *   POST /user/v1/register          → 创建用户        (public)
 *   POST /user/v1/login             → 登录            (public)
 *   POST /user/v1/logout            → 注销            (JWT)
 *   POST /user/v1/get               → 查询当前用户    (JWT)
 *   POST /user/v1/update            → 更新当前用户    (JWT)
 *   POST /user/v1/delete            → 删除当前用户    (JWT)
 *   POST /user/v1/avatar/select     → 选定历史头像    (JWT)
 *   POST /user/v1/avatar            → 上传头像        (JWT)
 *   POST /user/v1/avatar/history    → 头像历史列表    (JWT)
 * </pre>
 * ⇒ <b>9 个端点全是 {@code @PostMapping}</b>（无 GET/PUT/DELETE）—— 与 B 侧逐条一致。
 *
 * <p>🔴 <b>2026-11-02 订正（映射表 F-6）</b>：本类此前的 javadoc 写「B 侧 method 分布：DELETE, GET, POST, PUT」
 * 并列出 {@code PUT /user/v1/updateuser}、{@code GET /user/v1/avatarhistory} 等路径 ——
 * <b>那些是错的</b>（C 侧 javadoc 自述的「B 出处」本身就不可信）⇒ 现按 B 侧 {@code UserController}
 * 的<b>实测</b>路径与方法逐条改正。
 *
 * <h2>请求体的 {@code required} 口径（L3 · 与 B 侧行为等价）</h2>
 * <ul>
 *   <li><b>4 个有必填字段的端点</b>（register / login / avatar / avatar/select）：
 *       C 侧用 {@code required = true} ＋ {@code @Valid}。
 *       <ul>
 *         <li>⚠️ B 侧是 {@code required = false} ＋ {@code GoStyleRequest.normalize(...)}
 *             （共享层已按 D5 删除该手写校验器）⇒ B 侧「空 body ⇒ 必填字段缺失 ⇒ 400」。</li>
 *         <li>C 侧 {@code required = true} ⇒ 空 body ⇒ Spring 抛
 *             {@code HttpMessageNotReadableException} ⇒ <b>400 ＋ text/plain</b> ✔（形状一致）；</li>
 *         <li>body 为 {@code {}} ⇒ {@code @NotNull} 违规 ⇒ {@code MethodArgumentNotValidException}
 *             ⇒ <b>400 ＋ text/plain</b> ✔（形状一致）；</li>
 *         <li>⚠️ <b>空串合法</b>（B 侧 {@code GoStyleRequired.field} 只判 null）⇒ 用
 *             {@code @NotNull}（<b>不是</b> {@code @NotBlank}）⇒ 逐条等价。</li>
 *       </ul>
 *   </li>
 *   <li><b>5 个无必填字段的端点</b>（logout / get / delete）：{@code required = false}
 *       ⇒ body 可缺省 ⇒ <b>逐条照抄</b> B 侧。</li>
 *   <li><b>1 个不读 body 的端点</b>（avatar/history）：<b>不绑定 body</b> ——
 *       B 侧 {@code getavatarhistoryhandler.go} 全文 26 行<b>没有 httpx.Parse</b>，根本不读 body
 *       ⇒ 畸形 JSON / 非 JSON Content-Type 一律不影响该端点。</li>
 * </ul>
 */
@RestController
@RequestMapping("/user/v1")
public class UserController {

    private final UserApiService userApiService;

    public UserController(UserApiService userApiService) {
        this.userApiService = userApiService;
    }

    // ============== public（无 JWT） ==============

    /**
     * 创建用户。
     *
     * <p>必填：username / password / email（B 侧 {@code RegisterReq} 的三个 {@code GoStyleRequired.field}）。
     */
    @PostMapping("/register")
    public CreateUserResp register(@Valid @RequestBody CreateUserReq req, HttpServletRequest httpRequest) {
        return userApiService.register(req, httpRequest);
    }

    /**
     * 登录。
     *
     * <p>必填：username / password。
     */
    @PostMapping("/login")
    public LoginResp login(@Valid @RequestBody LoginReq req, HttpServletRequest httpRequest) {
        return userApiService.login(req, httpRequest);
    }

    // ============== JWT-protected（7 个） ==============

    /**
     * 注销。
     *
     * <p>⚠️ <b>body 字段不参与业务分支</b>（操作人取自 JWT 的 {@code userId}，token 取自
     * request attribute {@code jwt_token}）⇒ body 缺失不影响业务 ⇒ {@code required = false}。
     */
    @PostMapping("/logout")
    public LogoutResp logout(@RequestBody(required = false) LogoutReq req, HttpServletRequest httpRequest) {
        return userApiService.logout(req, httpRequest);
    }

    /**
     * 查询当前用户。
     *
     * <p>⚠️ <b>body 字段不参与业务分支</b>（uid 走 JWT claim）⇒ {@code required = false}。
     */
    @PostMapping("/get")
    public GetUserResp getUser(@RequestBody(required = false) GetUserReq req, HttpServletRequest httpRequest) {
        return userApiService.getUser(req, httpRequest);
    }

    /**
     * 更新当前用户（部分更新）。
     *
     * <p>⚠️ B 侧的零值 {@code UpdateUserReq} 直接进业务层 ⇒ body 缺失时等价于「不改任何字段」⇒
     * {@code required = false}（且 4 个字段<b>都没有</b>必填校验）。
     */
    @PostMapping("/update")
    public UpdateUserResp updateUser(@RequestBody(required = false) UpdateUserReq req,
                                     HttpServletRequest httpRequest) {
        return userApiService.updateUser(req, httpRequest);
    }

    /**
     * 删除当前用户。
     *
     * <p>⚠️ <b>body 字段不参与业务分支</b>（uid 走 JWT claim）⇒ {@code required = false}。
     */
    @PostMapping("/delete")
    public DeleteUserResp deleteUser(@RequestBody(required = false) DeleteUserReq req,
                                     HttpServletRequest httpRequest) {
        return userApiService.deleteUser(req, httpRequest);
    }

    /**
     * 选定历史头像。
     *
     * <p>必填：history_id。
     */
    @PostMapping("/avatar/select")
    public SelectAvatarResp selectAvatar(@Valid @RequestBody SelectAvatarReq req,
                                         HttpServletRequest httpRequest) {
        return userApiService.selectAvatar(req, httpRequest);
    }

    /**
     * 上传头像。
     *
     * <p>必填：avatar_url（content_type / size_bytes 可选）。
     */
    @PostMapping("/avatar")
    public UploadAvatarResp uploadAvatar(@Valid @RequestBody UploadAvatarReq req,
                                         HttpServletRequest httpRequest) {
        return userApiService.uploadAvatar(req, httpRequest);
    }

    /**
     * 头像历史列表。
     *
     * <p>🔴 <b>本端点<b>刻意不绑定 request body</b></b>（B 出处：{@code getavatarhistoryhandler.go} 全文 26 行
     * <b>没有 httpx.Parse</b>）⇒ 请求体从头到尾没被读过一次：畸形 JSON、非 JSON Content-Type
     * 一律不影响该端点。
     * <p>参数：uid 取自 JWT claim；limit <b>硬编码 30</b>（与 B 侧一致）⇒ 不需要任何入参 DTO。
     */
    @PostMapping("/avatar/history")
    public GetAvatarHistoryResp getAvatarHistory(HttpServletRequest httpRequest) {
        return userApiService.getAvatarHistory(httpRequest);
    }
}
