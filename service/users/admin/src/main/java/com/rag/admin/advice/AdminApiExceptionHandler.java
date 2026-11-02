package com.rag.admin.advice;

import com.rag.common.contract.ApiResponse;
import com.rag.common.exception.AuthenticationException;
import com.rag.common.exception.BusinessException;
import com.rag.common.logger.FatalError;
import com.rag.common.web.BadRequestException;
import com.rag.common.web.PlainTextErrors;
import com.rag.users.common.ErrCodes;
import com.rag.users.common.ErrMsg;
import com.rag.users.common.IdentityResolver;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * <p><b>职责</b>：admin 域 HTTP 面的<b>异常出口</b> —— 把「业务层抛出的异常」翻译成
 * B 侧 go-zero 那套 wire 形状（业务码 + 信封 / 裸 text/plain 400 / 裸状态码 405·415）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>类名 {@code GlobalExceptionHandler} → {@code AdminApiExceptionHandler}（映射表 admin.md §0.5 已批准的改名）；</li>
 *       <li>信封载体 {@code com.rag.common.response.Response} → {@link ApiResponse}
 *           （共享层 D3 上提 · L1 三键 code/msg/data 与字段顺序不变）；</li>
 *       <li>错误码取值 {@code ErrorCode.XXX.getCode()} → {@link ErrCodes#XXX}
 *           ＋ {@code ErrorCode.getMsg(x)} → {@link ErrMsg#getMsg(int)}（本仓错误契约形态 · 映射表 _shared.md §1）；</li>
 *       <li>{@code GoRequiredFieldException}（B 侧 {@code users-common/http/}）→ 共享层
 *           {@link BadRequestException} ＋ {@link PlainTextErrors}（D5 删掉了 B 的手写必填校验，
 *           映射表 _shared.md §1 的替换表逐条指定）；</li>
 *       <li><b>新增</b> {@link MethodArgumentNotValidException} 分支 —— D5 改 Bean Validation 后
 *           「必填字段缺失」由 Hibernate Validator 产出，必须落到与 B 侧
 *           {@code GoRequiredFieldException} <b>同一条</b> 400 + text/plain 路径（见该方法 javadoc）。</li>
 *     </ul>
 *     <b>方法体逐条照抄</b>：{@code mapRPCStatusToCode} 的 7 条映射、{@code decideStatus} 一律 200、
 *     {@code goStylePlainTextBadRequest} 的「原始 Content-Type 字符串 + 尾随 \n」三件事，全部逐字保留。</li>
 *   <li><b>语义还一样吗</b>：一样。逐条对照见各方法 javadoc；
 *       唯一需要读者注意的是 <b>{@code MethodArgumentNotValidException} 是新增分支</b> ——
 *       它<b>替代</b>（不是叠加于）B 侧 {@code GoRequiredFieldException} 的职责：
 *       D5 之后「必填字段缺失」只会由 Hibernate Validator 抛出这一个异常类型，
 *       两边同时存在反而会造成「同一种缺失给出两种 body」的分裂。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/advice/GlobalExceptionHandler.java}
 *       （252 行 · 本类按 §1.0c 档 ③「L1 契约层以 B 侧为准」整段抄）。</li>
 * </ol>
 *
 * <p><b>7 个 {@code @ExceptionHandler} + 1 个新增分支</b>：
 * <ul>
 *   <li>{@link BusinessException} → 业务码 + HTTP 200（与参考侧 envelope 语义 1:1）</li>
 *   <li>{@link StatusRuntimeException} → gRPC code 转业务码 + HTTP 200（<b>D-A</b>）</li>
 *   <li>{@link AuthenticationException} → HTTP 401 + 完整信封</li>
 *   <li>IdentityResolver 3 个 sentinel → 业务码 + HTTP 200</li>
 *   <li>解析层失败（{@link HttpMessageNotReadableException} / {@link BadRequestException} /
 *       {@link MethodArgumentNotValidException}）→ 400 + text/plain</li>
 *   <li>框架级错误 → 交回 Spring 默认 400 / 405 / 415</li>
 *   <li>{@link Exception} → 5001 Internal</li>
 * </ul>
 *
 * <p><b>⚠️ D-A（本仓决策记录 D16 · 主控拍板 · worker 不得自行改动）</b>：
 * B 侧 admin-rpc 的业务层抛的是 {@link StatusRuntimeException}，
 * 而这些 <b>status code + description 是 gRPC wire 上可见的 L1 面</b>
 * ⇒ 若业务层改抛 {@link BusinessException}，共享层的
 * {@code GrpcExceptionMapperAutoConfiguration} 会把 status 改写成 {@code UNKNOWN(2)} + trailer
 * ⇒ ⇒ <b>gRPC 面偏离 B 侧</b> ⇒ ⇒ 本类必须<b>逐字照抄</b> B 侧的
 * {@code mapRPCStatusToCode}，它是 HTTP 面业务码的<b>唯一来源</b>。
 *
 * <p><b>⚠️ HTTP 面的 msg 取 {@link ErrMsg#getMsg(int)}(映射码)</b>（<b>不是</b> rpc 层抛的 description）
 * —— 这是 B 侧自己的口径，逐字照抄。
 */
@RestControllerAdvice
public class AdminApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse> handleBusiness(BusinessException e) {
        int code = e.getCode();
        String msg = e.getMessage();
        log.error("BusinessException code={} msg={}", code, msg);
        return ResponseEntity.status(decideStatus(code)).body(new ApiResponse(code, msg, null));
    }

    /**
     * <b>鉴权层失败 → HTTP 401</b>（DRIFT-004 修复，与 user-api 同款）。
     *
     * <p>Spring 挑最具体的 handler，故本方法优先于 {@link #handleBusiness}。
     *
     * <p>B 出处（admin 侧与 user 侧同构）：
     * {@code rest.WithJwt} → {@code rest/handler/authhandler.go:106 unauthorized()}
     * → {@code WriteHeader(http.StatusUnauthorized)}；
     * {@code admin/api/internal/middleware/checkblacklistmiddleware.go:38} 同。
     * 两处都是 401；body 形状 B 侧 自身不一致，本端统一成完整信封（已按 §3.4 留档）。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse> handleAuthentication(
            AuthenticationException e, jakarta.servlet.http.HttpServletResponse response) {
        log.warn("admin AuthenticationException code={} msg={}", e.getCode(), e.getMessage());
        // 显式钉 401 — 防 gap：若某日 HttpMiddleware status 转发再次失效，这里确保状态码提交
        // （response 实际就是 HttpMiddleware 的 ORW wrapper，传到此处时仍可能未被 commit）。
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiResponse(e.getCode(), e.getMessage(), null));
    }

    /**
     * <b>D-A</b>：gRPC {@link StatusRuntimeException} → 业务码 + <b>HTTP 200</b>。
     *
     * <p><b>⚠️ 本方法是「兜底」映射，不是唯一映射</b> ——
     * B 侧<b>逐端点</b>在 api service 内各自 catch + 映射（B 出处：
     * {@code admin-api/.../service/Admin*Service.java} 的 {@code catch (StatusRuntimeException e)} 块），
     * C 侧在 {@code service/AdminApiService.java} 里<b>逐条照抄那 12 个 switch</b>；
     * <b>本方法</b>对应 B 侧 {@code GlobalExceptionHandler.handleRPC} —— 承接
     * <b>没有被端点自己 catch 住</b>的 {@link StatusRuntimeException}
     * （例：{@code getSelf} 端点 B 侧就是原样 rethrow，走的正是这条通用表）。
     *
     * <p>⚠️ <b>为什么不能反过来「只留通用表」</b>：B 侧<b>逐端点的 INTERNAL 码与通用表不同</b> ——
     * {@code updateSelf} / {@code unBanUser} / {@code resetUserPassword} 的 INTERNAL 是
     * <b>5003（ERROR_DB_SELECT）</b>，而通用表是 <b>5001</b>；
     * {@code login} 的 UNAUTHENTICATED 是 <b>1002（ERROR_LOGIN_WRONG）</b>，而通用表是 <b>1006</b>。
     * ⇒ 只留通用表 ⇒ 这 <b>4 个端点</b>的 HTTP 业务码与 B 侧不一致 ⇒ <b>未批准的 wire 漂移</b>
     * ⇒ ⇒ 端点级映射<b>必须</b>留在 api 层，本方法只做兜底。
     */
    @ExceptionHandler(StatusRuntimeException.class)
    public ResponseEntity<ApiResponse> handleRPC(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        int businessCode = mapRPCStatusToCode(code);
        String msg = ErrMsg.getMsg(businessCode);
        log.error("gRPC StatusRuntimeException code={} mapped to businessCode={} desc={}",
                code, businessCode, e.getStatus().getDescription(), e);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse(businessCode, msg, null));
    }

    /**
     * gRPC code → 业务码<b>通用兜底表</b>。
     *
     * <p><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/advice/GlobalExceptionHandler.java}
     * 的 {@code mapRPCStatusToCode(Status.Code)} —— <b>逐字照抄 B 侧，不得删改</b>：
     * <pre>
     *   INTERNAL          → 5001  ERROR_SERVER_COMMON
     *   NOT_FOUND         → 1003  ERROR_USER_NOT_EXIST
     *   ALREADY_EXISTS    → 1001  ERROR_USER_EXIST
     *   INVALID_ARGUMENT  → 500   ERROR
     *   PERMISSION_DENIED → 1016  ERROR_ADMIN_INVITE_CODE_WRONG
     *   UNAUTHENTICATED   → 1006  ERROR_TOKEN_RUNTIME
     *   （default）        → 1015  CODE_SERVER_BUSY
     * </pre>
     *
     * <p>⚠️ <b>本表是「兜底表」，不是「端点表」</b>：B 侧<b>每个 api service 有自己的 switch</b>，
     * 其中 4 个端点与本表不同（见 {@link #handleRPC} 的说明）⇒ 端点级映射在
     * {@code service/AdminApiService.java}，本表只承接未被端点 catch 的异常。
     *
     * <p>⚠️ <b>为什么 {@code PERMISSION_DENIED} 映射成「邀请码错误」（1016）</b>：
     * B 侧 admin 域的 {@code PERMISSION_DENIED} 只在「邀请码无效 / 已用 / 过期」这一条路径上出现
     * ⇒ ⇒ 该码在这一域里<b>就是</b>「管理员邀请码错误」的语义 ⇒ 逐字照抄。
     */
    private int mapRPCStatusToCode(Status.Code code) {
        if (code == Status.Code.INTERNAL) return ErrCodes.ERROR_SERVER_COMMON;          // 5001
        if (code == Status.Code.NOT_FOUND) return ErrCodes.ERROR_USER_NOT_EXIST;        // 1003
        if (code == Status.Code.ALREADY_EXISTS) return ErrCodes.ERROR_USER_EXIST;       // 1001
        if (code == Status.Code.INVALID_ARGUMENT) return ErrCodes.ERROR;                // 500
        if (code == Status.Code.PERMISSION_DENIED) return ErrCodes.ERROR_ADMIN_INVITE_CODE_WRONG; // 1016
        if (code == Status.Code.UNAUTHENTICATED) return ErrCodes.ERROR_TOKEN_RUNTIME;   // 1006
        return ErrCodes.CODE_SERVER_BUSY;                                              // 1015
    }

    /**
     * B 侧 admin 的 {@code IdentityResolver.ErrInvalidClaimException} → 1006。
     *
     * <p>⚠️ <b>落点差异</b>：B 侧 admin 有<b>自己的</b> {@code IdentityResolver}
     * （{@code com.rag.users.admin.api.service.IdentityResolver}），本仓按 映射表 admin.md §0.5
     * 把它<b>上收</b>到 {@link IdentityResolver}（{@code com.rag.users.common}）
     * ⇒ ⇒ sentinel 类型的<b>全限定名</b>变了，<b>映射码逐条未动</b>。
     */
    @ExceptionHandler(IdentityResolver.ErrInvalidClaimException.class)
    public ResponseEntity<ApiResponse> handleErrInvalidClaim(IdentityResolver.ErrInvalidClaimException e) {
        log.error("ErrInvalidClaim : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse(ErrCodes.ERROR_TOKEN_RUNTIME, ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME), null));
    }

    /**
     * B 侧 admin 的 {@code IdentityResolver.ErrAdminNotFoundException} → 1003。
     *
     * <p>⚠️ <b>类型改名（表达层）</b>：B 侧 admin 私有的
     * {@code ErrAdminNotFoundException} 在共享层里叫 {@link IdentityResolver.ErrUserNotFoundException}
     * （共享层是 user 域起的名）⇒ ⇒ <b>映射码 1003 逐条未动</b>。
     * B 出处：{@code service/users/admin/admin-api/.../service/IdentityResolver.java} 的
     * {@code ErrAdminNotFoundException}（其 javadoc 明写「调用方仍映射业务码 1003」）。
     */
    @ExceptionHandler(IdentityResolver.ErrUserNotFoundException.class)
    public ResponseEntity<ApiResponse> handleErrUserNotFound(IdentityResolver.ErrUserNotFoundException e) {
        log.error("ErrUserNotFound : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse(ErrCodes.ERROR_USER_NOT_EXIST, ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST), null));
    }

    /**
     * B 侧 admin 的 {@code IdentityResolver.ErrIdentityMismatchException} → 5001。
     */
    @ExceptionHandler(IdentityResolver.ErrIdentityMismatchException.class)
    public ResponseEntity<ApiResponse> handleErrIdentityMismatch(IdentityResolver.ErrIdentityMismatchException e) {
        log.error("ErrIdentityMismatch : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse(ErrCodes.ERROR_SERVER_COMMON, ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON), null));
    }

    /**
     * 框架级错误 → 交回 Spring 默认语义。
     *
     * <p>参考侧 admin-api 同样没有针对框架异常的 {@code @ExceptionHandler}，
     * 请求体缺失 / 方法不对 / Content-Type 不对分别落在 400 / 405 / 415。
     * 若被下面的兜底 {@code Exception} 吞掉会变成 500，把客户端调用错误误报成服务端故障。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<byte[]> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("unreadable request body : {}", e.getMessage());
        return goStylePlainTextBadRequest(e.getMessage());
    }

    /**
     * go-zero 必填字段缺失 → 400 + text/plain（DRIFT-016）。
     *
     * <p>B 出处：{@code handler.Entries()} 拿到 {@code httpx.Parse} 的错误后调
     * {@code httpx.ErrorCtx}；admin 服务的 yaml <b>从未</b>注册
     * {@code httpx.SetErrorHandler}（service/user 全树 grep 零命中），
     * 所以一定落到 {@code rest/httpx/responses.go:doHandleError} 的
     * {@code handler == nil} 分支 → {@code http.Error(w, err.Error(), 400)}。
     *
     * <p>这条同时把 §3.4.3 N-6 的「缺失 uid → 1017 + envelope」改成与 B 侧 一致的
     * {@code 400 + text/plain}（用户 2026-10-21 对 DRIFT-016 的拍板）。
     * <b>N-6 仍保留的部分</b>：uid <b>存在但非法</b>（{@code uid=abc}）与
     * <b>uid=0</b> 仍走 {@code AdminApiSupport.parseUid} → 1017，分别对应
     * B 侧的 {@code strconv.ParseInt} 400 与 {@code 1003 用户不存在}。
     *
     * <p><b>为什么刻意不并进 {@link #handleBusiness}</b>：{@code BusinessException}
     * 会被渲染成 {@code HTTP 200 + envelope}，而这里是 {@code HTTP 400 + text/plain}。
     * AGENTS.md §3.4.2 N-1 的结论是<b>层次由类型承载，不靠业务码推断</b>。
     *
     * <p><b>⚠️ 类型替换（D5 · 映射表 _shared.md §1 替换表逐条指定）</b>：
     * B 侧这里是 {@code com.rag.users.common.http.GoRequiredFieldException}
     * （B 侧自己的手写必填校验），本仓按 D5 改 Bean Validation ⇒ 该类型<b>已删</b>
     * ⇒ ⇒ 改用共享层 {@link BadRequestException}（同一条「裸状态码 + text/plain」语义）。
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<byte[]> handleGoRequiredField(BadRequestException e) {
        log.warn("go-style required field missing : {}", e.getMessage());
        return goStylePlainTextBadRequest(e.getMessage());
    }

    /**
     * <b>【D5 / D-01 降级 · 新增分支】</b>Bean Validation 失败 → 400 + text/plain。
     *
     * <p><b>为什么必须有这个分支</b>：B 侧「必填字段缺失」由手写的
     * {@code GoRequiredFieldException} 表达；本仓按 D5 改用 Bean Validation（{@code @NotNull} 等）
     * ⇒ 「必填字段缺失」这件事现在由 Hibernate Validator 表达 ⇒ 它抛的是
     * {@link MethodArgumentNotValidException}（Spring MVC 对 {@code @Valid @RequestBody} 的标准异常）
     * ⇒ ⇒ <b>若不在此接住</b>，它会被下面的兜底 {@link #handleAny} 吞成
     * <b>HTTP 500 + 5001</b> ⇒ ⇒ 「客户端参数写错」被误报成「服务端故障」
     * ⇒ ⇒ 与 B 侧的 400 + text/plain <b>完全相反</b>（B 侧同样把这类错误落在 400 那一支）。
     *
     * <p><b>⚠️ 错误文案口径（D-01 已登记的 L3 降级 · 不需要用户签字）</b>：
     * D5 之后文案与结构<b>由 Hibernate Validator 决定</b>，本仓<b>不</b>试图还原
     * B 侧 go-zero 的 {@code field "x" is not set} 字面量
     * ⇒ ⇒ 本方法直接透出 {@code e.getMessage()}（Hibernate Validator 的字段级错误汇总）
     * ⇒ ⇒ <b>body 的<b>形状</b>（400 + text/plain + nosniff + 尾随 \n）与 B 侧一致</b>，
     * <b>文本内容</b>属于已登记的降级项。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<byte[]> handleBeanValidation(MethodArgumentNotValidException e) {
        log.warn("bean validation failed : {}", e.getMessage());
        return goStylePlainTextBadRequest(e.getMessage());
    }

    /**
     * B 侧 {@code http.Error(w, msg, 400)} 的逐字等价实现（DRIFT-006）。
     *
     * <p>B 出处：go-zero v1.10.2 {@code rest/httpx/responses.go:doHandleError} 的
     * {@code handler == nil} 分支。{@code service/user} 全树<b>从未</b>调用
     * {@code httpx.SetErrorHandler}（grep 零命中），故 {@code httpx.Parse} 失败一定落到
     * {@code http.Error(w, err.Error(), http.StatusBadRequest)}。
     *
     * <p>B 侧 {@code net/http.Error} 的四件事必须都做：
     * <ol>
     *   <li>{@code Content-Type: text/plain; charset=utf-8}</li>
     *   <li>{@code X-Content-Type-Options: nosniff}</li>
     *   <li>写入 HTTP 状态码</li>
     *   <li>{@code fmt.Fprintln(w, error)} —— <b>带一个尾随换行</b></li>
     * </ol>
     *
     * <p><b>对齐边界（刻意）</b>：body 的<b>形状</b>（text/plain + 非空文本 + 尾换行）对齐；
     * 文本<b>内容</b>不对齐、也不试图对齐 —— B 侧是 go-zero 的解析错误文本，
     * Java 侧是 Spring 的 {@code HttpMessageNotReadableException.getMessage()}，
     * 两者来自不同框架，强行凑字面只会制造一处假的「1:1」。
     *
     * <p><b>⚠️ 落点差异（L4 · 映射表 _shared.md §1 替换表）</b>：B 侧本方法是本类内的
     * {@code ResponseEntity<String>} 私有方法；本仓改调共享层
     * {@link PlainTextErrors#badRequest(String)}（L3 上提 · D3 starter 化）
     * ⇒ ⇒ 但<b>两处细节仍在本方法里逐字保留</b>：
     * <ul>
     *   <li><b>空文案回落</b>：B 侧是 {@code (message == null || message.isEmpty()) ? "bad request" : message}
     *       ⇒ 本仓<b>原样保留</b>（共享层的 {@code PlainTextErrors.text} 对 null 给的是空串，
     *       差这一个词 ⇒ <b>body 文本会变</b> ⇒ ⇒ 在此归一后再交给共享层）；</li>
     *   <li><b>Content-Type 的原始字符串</b>：共享层写的是
     *       {@code "text/plain; charset=utf-8"}（<b>分号后有空格</b>）⇒ 与 B 侧逐字相同
     *       ⇒ ⇒ 至于空格/大小写，取决于<b>容器</b>重渲染（真 Tomcat 会归一化成
     *       {@code text/plain;charset=utf-8}），<b>不由本层控制</b>。</li>
     * </ul>
     *
     * <p>配套改动见 {@code AdminApiResponseAdvice#beforeBodyWrite}：非 JSON 响应不再被包 envelope。
     */
    private static ResponseEntity<byte[]> goStylePlainTextBadRequest(String message) {
        String body = (message == null || message.isEmpty() ? "bad request" : message);
        return PlainTextErrors.badRequest(body);
    }

    /**
     * <b>🔴 2026-11-02 · E-4 双侧活体对拍实测后<b>删除</b>本分支（原来给未知路径返 404）。</b>
     *
     * <p><b>为什么删</b>：本分支原先的依据是 <b>Go 原版</b>
     * （go-zero v1.10.2 {@code rest/engine.go:244-268 notFoundHandler} ⇒ B 侧未知路径 = 404）。
     * <p><b>但 B 侧 RAG 仓是 Java / Spring Boot 4</b>，<b>不是 go-zero</b>
     * ⇒ ⇒ E-4 活体实测（B 侧 admin-api 真实进程）：
     * <ul>
     *   <li>{@code POST /admin/v1/not-exist}（<b>带 token</b>）⇒ <b>HTTP 500</b> ＋
     *       信封 {@code {"code":5001,"msg":"系统内部错误","data":null}}；</li>
     *   <li>{@code GET /foo}（不在 {@code /admin/v1} 前缀下）⇒ 同上 <b>500 + 信封 5001</b>。</li>
     * </ul>
     * ⇒ ⇒ <b>B 侧根本没有 404 响应面</b> ⇒ 「客户端打错路径」在 B 侧就<b>被报成 500</b>。
     * ⇒ ⇒ C 侧<b>不该</b>把它改造成 404（那是<b>把 B 侧的行为改掉</b>，D16 下判 C 错）。
     *
     * <p><b>修法</b>：<b>删掉本分支</b> ⇒ {@code NoResourceFoundException} 自然落到
     * {@link #handleAny(Exception)} 的<b>通用兜底</b> ⇒ <b>HTTP 500 + 信封 code=5001</b>
     * ⇒ ⇒ <b>与 B 侧活体逐字一致</b>。
     *
     * <p>⚠️ <b>连带</b>：鉴权判据已同步改回 <b>前缀语义</b>（B 侧 {@code AdminJwtAuthPaths#isProtected} 逐字），
     * ⇒ ⇒ {@code /admin/v1/**} 下的未知路径<b>先过鉴权</b> ⇒ 无/坏 token ⇒ <b>401 + 信封 1006</b>
     * ⇒ ⇒ 带 token ⇒ 过鉴权 ⇒ 落进本兜底 ⇒ <b>500 + 信封 5001</b>。
     */

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("method not supported : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /**
     * <b>🔴 2026-11-02 真 Tomcat 契约测试实测后改：415 ⇒ <b>400 + text/plain</b></b>。
     *
     * <p><b>比 B 侧多做了什么</b>：把「框架级 415」改写成「B 侧的 400 + text/plain」。
     *
     * <p><b>B 出处（go-zero v1.10.2 实测源码）</b>：
     * <pre>
     *   rest/httpx/requests.go:153  withJsonBody(r) =
     *       r.ContentLength > 0 && strings.Contains(r.Header.Get("ContentType"), "application/json")
     *   rest/httpx/requests.go:145  ParseJsonBody(r, v) =
     *       if withJsonBody(r) { json.Unmarshal(r.Body, v) } else { mapping.UnmarshalJsonMap(nil, v) }
     * </pre>
     * ⇒ <b>body 有内容但 content-type 不是 application/json</b> ⇒ B 侧<b>跳过 JSON 解析</b>
     * ⇒ 走 {@code UnmarshalJsonMap(nil, v)} ⇒ <b>必填字段缺失 ⇒ {@code field %q is not set} ⇒ 400 + text/plain</b>
     * ⇒ ⇒ <b>B 侧根本没有 415 这个状态码</b>（go-zero 不做 content-type 协商）
     * ⇒ ⇒ C 侧若回 415 ⇒ 客户端拿到的状态码与 B 不一致 ⇒ 属 <b>未批准的 wire 漂移</b>
     * ⇒ ⇒ <b>故本分支改回 400 + text/plain</b>（形状与 {@link #handleUnreadableBody} 一致）。
     *
     * <p><b>语义还一样吗</b>：对<b>有必填字段的 10 个端点</b>，<b>一样</b>（B 侧也是 400 + text/plain）。
     * <p>⚠️ <b>已知残留差异（窄）</b>：对<b>3 个空 struct 端点</b>
     * （{@code /logout}、{@code /getself}、{@code /invite/create}），
     * B 侧的「非 JSON content-type」是<b>正常受理</b>（零值 struct ⇒ 无字段可缺 ⇒ 不报错），
     * 而 C 侧在此分支统一给 400 ⇒ <b>这 3 个端点存在一处窄差异</b>
     * ⇒ 已登记为 Intentional Deviation（框架差异 · 窄）。
     *
     * <p><b>⚠️ 405 不受影响</b>（method 不匹配）：B 侧 httprouter 的 405 由路由器直接写出、
     * <b>不经过路由链</b>（也就没有 JWT 中间件）⇒ C 侧同样在<b>路由匹配阶段</b>就抛出
     * ⇒ 仍是裸 405（无 body），与 B 侧一致。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<byte[]> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("media type not supported : {}", e.getMessage());
        return goStylePlainTextBadRequest(e.getMessage());
    }

    /**
     * H3 修复：{@link FatalError} 必须原样抛出，<b>不得</b>被转成 200 业务 envelope。
     *
     * <p>B 侧 版 {@code logger.go:296} 在打出 fatal_error 后无条件 {@code os.Exit(1)}，
     * 「fatal 后必须停止接流量」是硬契约。Java 版改为抛专用异常（不调 System.exit，
     * 以免绕过 Spring graceful shutdown / {@code @PreDestroy}），
     * 但若不在此显式处理，{@link #handleAny} 会把它当普通业务异常包成 envelope，
     * 进程带着致命状态继续服务 —— B 侧绝不会发生。
     *
     * <p>刻意不做 {@code System.exit(1)}：终止决策权留给调用 {@code logFatal} 的位置。
     */
    @ExceptionHandler(FatalError.class)
    public void handleFatal(FatalError e) {
        // 故意不返回 ResponseEntity：让异常继续向上抛
        throw e;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleAny(Exception e) {
        // FatalError 已被专门处理；此处兜底再放行一次，防止 advice 未注册该分支时静默吞掉
        if (e instanceof FatalError fatal) {
            throw fatal;
        }
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(ErrCodes.ERROR_SERVER_COMMON, ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON), null));
    }

    /**
     * 业务码 → HTTP 状态码：<b>一律 200</b>。
     *
     * <p>与参考实现 1:1：参考侧所有 Logic 结果都经 {@code LogicResult} 走
     * {@code envelope(...)}，HTTP 恒为 200，业务成败由 body 的 {@code code} 表达；
     * 401 只由鉴权层的 Servlet Filter 写出，不经过本处理器。
     */
    private HttpStatus decideStatus(int code) {
        return HttpStatus.OK;
    }
}
