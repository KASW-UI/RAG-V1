package com.rag.comment.advice;

import com.rag.comment.contract.CodeError;
import com.rag.comment.contract.ErrCodes;
import com.rag.comment.contract.ErrMsg;
import com.rag.common.contract.ApiResponse;
import com.rag.common.exception.AuthenticationException;
import com.rag.common.logger.FatalError;
import com.rag.common.web.RouterErrorResponses;
import io.grpc.Status;
import io.grpc.StatusException;
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
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * <p><b>comment 域异常 → HTTP 响应折叠</b> —— 对应 B 侧
 * {@code comment-api/…/api/advice/CommentGlobalExceptionHandler.java:66-294}。
 *
 * <p><b>⚠️ L4 落点变化（D1 + 域内隔离）</b>：
 * B 侧 {@code com.rag.comment.comment.api.advice.CommentGlobalExceptionHandler}
 * ⇒ C 侧 {@code com.rag.comment.advice.CommentApiExceptionHandler}
 * ⇒ 类名去掉 {@code Global} 前缀（**域内隔离**，AGENTS.md §八「未统一化」）。
 *
 * <h2>🔴 本类的核心语义：<b>HTTP 恒 200 ＋ {code,msg,data}</b></h2>
 * <p>除鉴权失败（401 空 body）、404 / 405 / 415 这三类<b>框架级</b>错误外，
 * <b>所有业务错误都返回 HTTP 200</b>，业务码放在 body 的 {@code code} 字段里。
 * ⇒ ⚠️ <b>不要「改成」RESTful 的 4xx/5xx</b>（那是改 L1 行为）。
 *
 * <h3>⚠️ 八类异常与各自的响应形状</h3>
 * <table border="1">
 *   <caption>B 侧 {@code CommentGlobalExceptionHandler} 的 8 个 handler</caption>
 *   <tr><th>异常</th><th>形状</th><th>码</th></tr>
 *   <tr><td>{@code AuthenticationException}</td><td>401 + <b>空 body</b></td><td>—</td></tr>
 *   <tr><td>{@code CodeError}</td><td>200 + envelope</td><td>异常自带的码</td></tr>
 *   <tr><td>{@code StatusRuntime/StatusException}</td><td>200 + envelope</td><td>5001</td></tr>
 *   <tr><td>{@code HttpMessageNotReadableException}</td><td>200 + envelope</td><td>5001</td></tr>
 *   <tr><td>{@code MethodArgumentNotValidException}</td><td>200 + envelope</td><td>5001</td></tr>
 *   <tr><td>{@code NoResourceFoundException}</td><td>404（框架形状）</td><td>—</td></tr>
 *   <tr><td>{@code HttpRequestMethodNotSupportedException}</td><td>405（框架形状）</td><td>—</td></tr>
 *   <tr><td>{@code HttpMediaTypeNotSupportedException}</td><td>415（框架形状）</td><td>—</td></tr>
 * </table>
 *
 * <h3>⚠️ 鉴权失败是<b>唯一</b>的空 body</h3>
 * <p>B 侧 {@code rest/handler/authhandler.go:106 unauthorized()} 只调
 * {@code writer.WriteHeader(http.StatusUnauthorized)}，<b>不写任何字节</b>，也不设 Content-Type。
 * ⇒ C 侧必须用 {@code ResponseEntity.status(401).build()}（<b>不带 body</b>）还原该形状。
 *
 * <h3>⚠️ 校验失败（{@code MethodArgumentNotValidException}）的码是 5001</h3>
 * <p>B 侧的必填校验失败（{@code GoRequiredFieldException}）走的是<b>解析失败</b>那条路
 * ⇒ 兜底码 5001 + 「系统内部错误」。
 * ⇒ C 侧用 Bean Validation 后异常类型不同（{@code MethodArgumentNotValidException}）
 * ⇒ <b>但响应形状必须逐条一致</b>：200 + {@code {code:5001, msg:"系统内部错误", data:null}}。
 */
@RestControllerAdvice
public class CommentApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CommentApiExceptionHandler.class);

    // ============== 1. 鉴权失败：401 + 空 body ==============

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Void> handleAuthentication(AuthenticationException e) {
        log.warn("AuthenticationException code={} msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // ============== 2. 业务异常：200 + envelope ==============

    /**
     * 本域的业务错误载体 —— B 侧 {@code errmsg.CodeError}。
     *
     * <p>⚠️ 用 {@link CodeError#getCode()} / {@link CodeError#getMsg()}，
     * <b>不是</b> {@code getMessage()} —— 后者是 {@code "ErrCode:X, Errmsg:Y"} 的包装格式，
     * 放进 body 会让客户端看到多一层前缀。
     */
    @ExceptionHandler(CodeError.class)
    public ResponseEntity<ApiResponse> handleCodeError(CodeError e) {
        log.error("CodeError code={} msg={}", e.getCode(), e.getMsg());
        return okEnvelope(e.getCode(), e.getMsg());
    }

    // ============== 3. gRPC 异常（领域层未捕获时的兜底） ==============

    @ExceptionHandler({StatusRuntimeException.class, StatusException.class})
    public ResponseEntity<ApiResponse> handleRpcStatus(Exception e) {
        log.error("unconverted gRPC status error", e);
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    // ============== 4. 解析 / 校验失败：200 + 5001 ==============

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("unreadable request body : {}", e.getMessage());
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    /**
     * Bean Validation 失败 —— 承接 B 侧 {@code GoRequiredFieldException} 的位置。
     *
     * <p>⚠️ <b>码是 5001（不是 2001）</b>：B 侧的必填校验发生在<b>解析层</b>
     * ⇒ 走的是「解析失败 ⇒ 兜底 5001」那条路，<b>不是</b>业务层的
     * {@code ErrorInputWrong}(2001)。两者不可混。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleValidation(MethodArgumentNotValidException e) {
        log.warn("request validation failed : {}", e.getMessage());
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    // ============== 5. 框架级错误：404 / 405 / 415 ==============

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<byte[]> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("no handler for {} : {}", e.getResourcePath(), e.getMessage());
        return RouterErrorResponses.notFound(e.getResourcePath());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("method not supported : {}", e.getMessage());
        return RouterErrorResponses.methodNotAllowed(
                e.getSupportedHttpMethods() == null ? java.util.List.of()
                        : e.getSupportedHttpMethods().stream().map(Object::toString).toList());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("media type not supported : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }

    // ============== 6. 兜底 ==============

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleAny(Exception e) {
        // ⚠️ FatalError 必须<b>原样重抛</b>：它是「进程级致命错误」的信号
        // （共享层 {@code com.rag.common.logger.FatalError}）⇒ 被这里吞掉就丢了告警。
        if (e instanceof FatalError fatal) {
            throw fatal;
        }
        log.error("unhandled exception", e);
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    // ============== 工具方法 ==============

    private static ResponseEntity<ApiResponse> okEnvelope(int code, String msg) {
        return ResponseEntity.ok(new ApiResponse(code, msg, null));
    }

    /** 本域兜底码：5001（B 侧 {@code ErrorServerCommon}）。 */
    private static int serverCommon() {
        return ErrCodes.ERROR_SERVER_COMMON;
    }

    /** 本域兜底文案：「系统内部错误」（B 侧 {@code CommentErrorMessages} 查表结果）。 */
    private static String serverCommonMsg() {
        return ErrMsg.getMsg(ErrCodes.ERROR_SERVER_COMMON);
    }
}
