package com.rag.user.advice;

import com.rag.common.contract.ApiResponse;
import com.rag.common.exception.AuthenticationException;
import com.rag.common.exception.BusinessException;
import com.rag.common.logger.FatalError;
import com.rag.common.web.PlainTextErrors;
import com.rag.user.contract.ErrCodes;
import com.rag.user.contract.ErrMsg;
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
 * <p><b>全局异常处理器</b> —— {@code @RestControllerAdvice} ＋ {@code @ExceptionHandler}。
 *
 * <p><b>B 出处</b>：{@code service/users/user/user-api/src/main/java/com/rag/users/user/api/advice/GlobalExceptionHandler.java}
 * —— 映射表<b>逐条照抄</b>，仅以下几处因 D5 / 共享层收口而变（其余逐字一致）：
 * <ol>
 *   <li><b>必填字段缺失</b>：B 捕获 {@code GoRequiredFieldException}（共享层已按 D5 删除）
 *       ⇒ C 捕获 Spring 的 {@link MethodArgumentNotValidException}（Bean Validation），
 *       <b>状态码 400 与 text/plain 结构不变</b>，仅<b>文案</b>不同
 *       ⇒ 已登记降级（{@code docs/L3-降级清单.md} D-01）。</li>
 *   <li><b>400 的写法</b>：B 自己拼 header ＋ body；C 用共享层的
 *       {@link PlainTextErrors#badRequest(String)}（与 B 的 {@code http.Error} 逐字同构：
 *       text/plain;charset=utf-8 ＋ nosniff ＋ 尾随 {@code "\n"}）⇒ <b>不重复实现</b>。</li>
 *   <li><b>405 的形状</b>：B 侧 Java 版返回<b>裸状态码</b>（无 Allow 头、无 body）⇒ C 侧逐条照抄。</li>
 * </ol>
 *
 * <p><b>框架职责</b>：
 * <ul>
 *   <li>把业务异常 / gRPC 异常 / IdentityResolver sentinel 异常 / 兜底 RuntimeException 统一映射成
 *       {@code ApiResponse{code, msg, data=null}}；</li>
 *   <li>HTTP 状态码：<b>鉴权层失败 = 401</b>（{@link AuthenticationException}，由拦截器抛出）；
 *       其余业务失败一律 200（业务码由 body 表达）；框架级错误保留 Spring 默认语义（400 / 405 / 415），
 *       未捕获异常 500。</li>
 * </ul>
 */
@RestControllerAdvice
public class UserApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UserApiExceptionHandler.class);

    // ============== 1. 业务异常（Service 主动抛） ==============

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse> handleBusiness(BusinessException e) {
        int code = e.getCode();
        String msg = e.getMessage();
        log.error("BusinessException code={} msg={}", code, msg);
        HttpStatus status = decideStatus(code);
        return ResponseEntity.status(status).body(new ApiResponse(code, msg, null));
    }

    /**
     * <b>鉴权层失败 → HTTP 401</b>。
     *
     * <p>Spring 按「最具体」挑 handler：{@code AuthenticationException extends BusinessException}，
     * 因此本方法优先于上面的 {@link #handleBusiness}，只有拦截器抛出的鉴权失败会走这里。
     *
     * <p><b>B 出处</b>：{@code GlobalExceptionHandler.java:76-81}。
     * <p>B 侧两处鉴权短路（缺 Bearer / 签名错 / 过期、黑名单命中）都是 401，
     * 但 body 形状不同（B 侧自身的不一致）⇒ 本端统一成完整 envelope，属表达层差异。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse> handleAuthentication(AuthenticationException e) {
        log.warn("AuthenticationException code={} msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiResponse(e.getCode(), e.getMessage(), null));
    }

    // ============== 2. gRPC 异常（领域层未捕获透传） ==============

    /**
     * <b>gRPC code → 业务码映射（与 B 侧每个 Logic 的 switch 1:1 对齐）</b>。
     *
     * <p>⚠️ <b>本方法是「通用映射」</b>：个别端点在 api 层<b>显式</b>覆盖了它
     * （B 侧 {@code DRIFT-USERAPI-003} / {@code DRIFT-003}：logout 的 INVALID_ARGUMENT、
     * selectAvatar 的 NOT_FOUND 都必须走各自的分支）⇒ ⇒ <b>不要把通用映射当成唯一口径</b>。
     */
    @ExceptionHandler(StatusRuntimeException.class)
    public ResponseEntity<ApiResponse> handleRPC(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        int businessCode = mapRPCStatusToCode(code);
        String msg = ErrMsg.getMsg(businessCode);
        log.error("gRPC StatusRuntimeException code={} mapped to businessCode={} desc={}",
                code,
                businessCode,
                e.getStatus().getDescription(),
                e);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse(businessCode, msg, null));
    }

    /**
     * gRPC code → 业务码映射。
     *
     * <p><b>B 出处</b>：{@code GlobalExceptionHandler.java:153-163}（逐条一致）：
     * <pre>
     *   INTERNAL        → 5001（ErrorServerCommon）
     *   NOT_FOUND       → 1003（ErrorUserNotExist）
     *   ALREADY_EXISTS  → 1001（ErrorUserExist）
     *   INVALID_ARGUMENT→  500（Error）
     *   其它            → 1015（CodeServerBusy）
     * </pre>
     */
    private int mapRPCStatusToCode(Status.Code code) {
        if (code == Status.Code.INTERNAL) {
            return ErrCodes.ERROR_SERVER_COMMON;
        }
        if (code == Status.Code.NOT_FOUND) {
            return ErrCodes.ERROR_USER_NOT_EXIST;
        }
        if (code == Status.Code.ALREADY_EXISTS) {
            return ErrCodes.ERROR_USER_EXIST;
        }
        if (code == Status.Code.INVALID_ARGUMENT) {
            return ErrCodes.ERROR;
        }
        return ErrCodes.CODE_SERVER_BUSY;
    }

    // ============== 3. IdentityResolver 5 个 sentinel exception ==============

    @ExceptionHandler(IdentityResolver.ErrInvalidClaimException.class)
    public ResponseEntity<ApiResponse> handleErrInvalidClaim(IdentityResolver.ErrInvalidClaimException e) {
        log.error("ErrInvalidClaim : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse(ErrCodes.ERROR_TOKEN_RUNTIME, ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME), null));
    }

    @ExceptionHandler(IdentityResolver.ErrUserNotFoundException.class)
    public ResponseEntity<ApiResponse> handleErrUserNotFound(IdentityResolver.ErrUserNotFoundException e) {
        log.error("ErrUserNotFound : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse(ErrCodes.ERROR_USER_NOT_EXIST, ErrMsg.getMsg(ErrCodes.ERROR_USER_NOT_EXIST), null));
    }

    @ExceptionHandler(IdentityResolver.ErrUserInactiveException.class)
    public ResponseEntity<ApiResponse> handleErrUserInactive(IdentityResolver.ErrUserInactiveException e) {
        log.error("ErrUserInactive : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse(ErrCodes.ERROR_USER_BANNED, ErrMsg.getMsg(ErrCodes.ERROR_USER_BANNED), null));
    }

    @ExceptionHandler(IdentityResolver.ErrStatusUnavailableException.class)
    public ResponseEntity<ApiResponse> handleErrStatusUnavailable(IdentityResolver.ErrStatusUnavailableException e) {
        log.error("ErrStatusUnavailable : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse(ErrCodes.CODE_SERVER_BUSY, ErrMsg.getMsg(ErrCodes.CODE_SERVER_BUSY), null));
    }

    @ExceptionHandler(IdentityResolver.ErrIdentityMismatchException.class)
    public ResponseEntity<ApiResponse> handleErrIdentityMismatch(IdentityResolver.ErrIdentityMismatchException e) {
        log.error("ErrIdentityMismatch : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse(ErrCodes.ERROR_SERVER_COMMON, ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON), null));
    }

    // ============== 4. 框架级错误 ==============

    /**
     * 请求体不可读 / 缺失 → <b>400 + text/plain</b>。
     *
     * <p><b>B 出处</b>：{@code GlobalExceptionHandler.java:216-220} → {@code goStylePlainTextBadRequest}。
     * <p>形状：{@code Content-Type: text/plain; charset=utf-8} ＋ {@code X-Content-Type-Options: nosniff}
     * ＋ 尾随 {@code "\n"} ⇒ 由共享层的 {@link PlainTextErrors#badRequest(String)} 逐字产出。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<byte[]> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("unreadable request body : {}", e.getMessage());
        return PlainTextErrors.badRequest(e.getMessage());
    }

    /**
     * <b>Bean Validation 失败 → 400 + text/plain</b>（D5 的降级落点）。
     *
     * <p><b>B 出处</b>：{@code GlobalExceptionHandler.java:238-242} 捕获 {@code GoRequiredFieldException}
     * （B 侧 go-zero 必填校验器抛的异常，文案 = {@code field "username" is not set}）。
     *
     * <p>⚠️ <b>本条是已批准的 L3 降级</b>（{@code docs/L3-降级清单.md} D-01 · 决策 D5）：
     * <ul>
     *   <li><b>状态码 400</b> ⇒ 不变；</li>
     *   <li><b>错误结构</b>（text/plain ＋ nosniff ＋ 尾换行）⇒ 不变；</li>
     *   <li><b>错误文案</b> ⇒ 由 Hibernate Validator 决定 ⇒ <b>与 B 不同</b>（已登记）。</li>
     * </ul>
     * ⇒ ⇒ 对拍时<b>断言 status ＋ content-type ＋ 结构</b>，<b>不断言文案</b>。
     *
     * <p>⚠️ <b>为什么不并进 {@link #handleBusiness}</b>：{@code BusinessException} 会被渲染成
     * {@code HTTP 200 + envelope}，而这里是 {@code HTTP 400 + text/plain} ⇒
     * <b>层次由类型承载，不靠业务码推断</b>。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<byte[]> handleBeanValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse(e.getMessage());
        log.warn("bean validation failed : {}", message);
        return PlainTextErrors.badRequest(message);
    }

    /** HTTP 方法不支持 → <b>405（裸状态码 · 无 Allow 头 · 无 body）</b>。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("method not supported : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /** Content-Type 不支持 → 415。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("media type not supported : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }

    // ============== 5. 致命错误：绝不转 envelope ==============

    /**
     * {@link FatalError} 必须原样抛出，<b>不得</b>被转成 200 业务 envelope。
     *
     * <p><b>B 出处</b>：{@code GlobalExceptionHandler.java:325-329}。
     * <p>「fatal 后必须停止接流量」是硬契约：若把它当普通业务异常处理 ⇒ 请求看起来「失败了」，
     * 而<b>进程带着致命状态继续正常服务</b> ⇒ 正是 Go 侧绝不会发生的情况。
     *
     * <p><b>刻意不做 {@code System.exit(1)}</b>：终止决策权留给调用 {@code logFatal} 的位置。
     */
    @ExceptionHandler(FatalError.class)
    public void handleFatal(FatalError e) {
        // 故意不返回 ResponseEntity：让异常继续向上抛
        throw e;
    }

    // ============== 6. 兜底：所有未捕获异常 ==============

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleAny(Exception e) {
        // FatalError 已在上面被专门处理；这里若仍收到，说明有 advice 未注册 FatalError 分支，
        // 此时也必须放行而不是包成 envelope。
        if (e instanceof FatalError fatal) {
            throw fatal;
        }
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(ErrCodes.ERROR_SERVER_COMMON,
                        ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON), null));
    }

    // ============== 业务码 → HTTP 状态码 ==============

    /**
     * 业务码 → HTTP 状态码：<b>一律 200</b>。
     *
     * <p>与 Go 端 {@code httpx.OkJson} 1:1：Go 侧所有 Logic 结果都经 envelope 输出，
     * HTTP 状态码恒为 200，业务成败完全由 body 里的 {@code code} 表达。
     *
     * <p>⚠️ <b>鉴权层不走本方法</b>：鉴权失败由 {@link #handleAuthentication} 单独产出 401 ⇒
     * ⇒ 1006 在两层都可能出现时，<b>按码分流会把两层重新搅在一起</b>（B 侧 {@code DRIFT-004} 的教训）。
     */
    private HttpStatus decideStatus(int code) {
        return HttpStatus.OK;
    }
}
