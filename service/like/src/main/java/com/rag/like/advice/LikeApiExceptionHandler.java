package com.rag.like.advice;

import com.rag.common.contract.ApiResponse;
import com.rag.common.exception.AuthenticationException;
import com.rag.common.logger.FatalError;
import com.rag.common.web.RouterErrorResponses;
import com.rag.like.contract.CodeError;
import com.rag.like.contract.ErrCodes;
import com.rag.like.contract.ErrMsg;
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
 * <p><b>全局异常处理器</b> —— 对应 B 侧
 * {@code like-api/src/main/java/com/rag/like/like/api/advice/GlobalExceptionHandler.java:75-333}
 * （分支逐条照抄 + C 侧落点变化）。
 *
 * <p><b>抄写基准</b>：B 侧（L2 业务语义层以 B 侧 为基准整段抄 · D13）。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>{@code GoRequiredFieldException} 分支 ⇒ 换成 Spring 的
 *           {@link MethodArgumentNotValidException}（A10 禁用手写校验器 ⇒ 必填校验走 Bean Validation）。</li>
 *       <li>{@code IllegalArgumentException} 分支（Go 侧字段类型转换失败）⇒ 换成
 *           {@link HttpMessageNotReadableException}（Jackson 2 的解析失败载体）。</li>
 *       <li>404 / 405 的响应体改用共享层的 {@link RouterErrorResponses}（表达层，形状不变）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。三个「解析/校验失败」分支的出口都是
 *       <b>200 + {code:5001}</b>；鉴权失败是 <b>401 + 空 body</b>；
 *       404 / 405 / 415 是框架级错误，照抄。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h3>⚠️ 与 users 域 api 的关键差异：<b>本服务把所有业务错误压成 HTTP 200</b></h3>
 * <p>Go 侧 {@code service/user/**} 的两个 api <b>从未</b>调用 {@code httpx.SetErrorHandler}
 * ⇒ 解析失败落到 go-zero 默认分支 {@code http.Error(w, err.Error(), 400)}
 * ⇒ <b>HTTP 400 + text/plain</b>；而 <b>like api 注册了</b> ⇒ 默认分支被整个替换掉 ⇒
 * <b>包括 {@code httpx.Parse} 的解析失败在内，一切都是 HTTP 200 + envelope</b>。
 * <table border="1">
 *   <caption>解析失败的两条截然不同的出口</caption>
 *   <tr><th>场景</th><th>B 侧 like api（真源）</th><th>B 侧 user api / 本仓 users 域</th></tr>
 *   <tr>
 *     <td>body 非法 JSON</td>
 *     <td rowspan="3"><b>200</b> + {@code {code:5001,msg:"系统内部错误",data:null}}</b></td>
 *     <td rowspan="3"><b>400</b> + text/plain</td>
 *   </tr>
 *   <tr><td>必填字段缺失</td></tr>
 *   <tr><td>标量类型不匹配</td></tr>
 * </table>
 * ⇒ ⇒ <b>照抄 users 域的 400 形状会构成行为回归</b>。
 *
 * <h3>⚠️ 鉴权失败是唯一的例外，且 body 为空</h3>
 * <p>B 侧 {@code rest/handler/authhandler.go:106 unauthorized()} 只调
 * {@code writer.WriteHeader(http.StatusUnauthorized)}，不写任何字节。
 * ⇒ 本域的错误响应形状：
 * <ol>
 *   <li>业务失败 → <b>200</b> + {@code {code,msg,data}}；</li>
 *   <li>验签失败 → <b>401</b> + <b>空 body</b>；</li>
 *   <li>框架级错误 → 404 / 405 / 415。</li>
 * </ol>
 *
 * <h3>⚠️ 本类的分支清单（逐条对应 B 侧 + C 侧落点）</h3>
 * <table border="1">
 *   <caption>分支 → 出口</caption>
 *   <tr><th>异常</th><th>出口</th></tr>
 *   <tr><td>{@link AuthenticationException}</td><td><b>401 + 空 body</b></td></tr>
 *   <tr><td>{@link CodeError}（含 cause 链）</td><td>200 + {@code {code,msg,data:null}}</td></tr>
 *   <tr><td>{@link StatusRuntimeException} / {@link StatusException}</td><td>200 + 5001</td></tr>
 *   <tr><td>{@link HttpMessageNotReadableException}</td><td>200 + 5001</td></tr>
 *   <tr><td>{@link MethodArgumentNotValidException}</td><td>200 + 5001</td></tr>
 *   <tr><td>{@link NoResourceFoundException}</td><td><b>404</b>（{@link RouterErrorResponses#notFound}）</td></tr>
 *   <tr><td>{@link HttpRequestMethodNotSupportedException}</td><td><b>405</b>（{@link RouterErrorResponses#methodNotAllowed}）</td></tr>
 *   <tr><td>{@link HttpMediaTypeNotSupportedException}</td><td><b>415</b>（兜底分支）</td></tr>
 *   <tr><td>{@link FatalError}</td><td><b>原样重抛</b>，绝不转信封</td></tr>
 *   <tr><td>{@link Exception}（兜底）</td><td>200 + 5001</td></tr>
 * </table>
 */
@RestControllerAdvice
public class LikeApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LikeApiExceptionHandler.class);

    // ============== 1. 鉴权失败：401 + 空 body ==============

    /**
     * B 侧 {@code unauthorized()} 的逐字等价物：<b>401，空 body</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:91-95}
     * （{@code return ResponseEntity.status(401).build()}）。
     *
     * <p>返回 {@code ResponseEntity.status(401).build()} 而不是带 envelope 的 body ——
     * 后者会把 B 侧从未输出过的字节写给客户端。空 body 不会被
     * {@link LikeApiResponseAdvice} 包成 envelope（它对 {@code null} 直接放行）。
     *
     * <p>⚠️ <b>Spring 按「最具体」挑 handler</b>：{@code AuthenticationException extends BusinessException}
     * ⇒ 本方法优先于下面的 {@link #handleBusiness} ⇒ 只有拦截器抛出的鉴权失败走这里。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Void> handleAuthentication(AuthenticationException e) {
        log.warn("AuthenticationException code={} msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // ============== 2. 业务异常：200 + envelope ==============

    /**
     * B 侧 {@code errors.As(err, &codeErr)} 分支 —— {@link CodeError} 直接决定 code + msg。
     *
     * <p>对应 B 侧四类抛出点：
     * <ul>
     *   <li>claim 解析失败 → {@code NewErrCode(ErrorTokenRuntime)} → <b>1006</b></li>
     *   <li>领域层错误 → {@code NewErrCodeMsg(rpcResp.Code, rpcResp.Msg)} → <b>原样透传</b> code + msg</li>
     *   <li>其它业务码（2001 / 5003 / 5012 …）</li>
     * </ul>
     *
     * <p><b>HTTP 恒为 200</b>：B 侧的 error handler 返回 {@code http.StatusOK}。
     */
    @ExceptionHandler(CodeError.class)
    public ResponseEntity<ApiResponse> handleCodeError(CodeError e) {
        log.error("CodeError code={} msg={}", e.getCode(), e.getMsg());
        return okEnvelope(e.getCode(), e.getMsg());
    }

    // ============== 3. gRPC 异常（领域层未捕获时的兜底） ==============

    /**
     * 未被领域层翻译成业务码的 gRPC status ⇒ <b>200 + 5001</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:130-140}。
     *
     * <p><b>⚠️ 刻意不按 gRPC status code 反查业务码</b>：B 侧的兜底分支对
     * 「非 {@code CodeError}」一律给 5001 + {@code "系统内部错误"}；
     * 若在这里按 gRPC 码反查，得到的会是 1015 / 5003 / … ⇒ <b>与 B 侧兜底语义不同</b>。
     * ⇒ 并且 <b>B 侧的 {@code rpcError} 分支在 C 侧不存在</b>（D2：域内直连，没有 gRPC 往返）
     * ⇒ ⇒ 本类<b>不实现</b>「gRPC → 业务码」的反查。
     */
    @ExceptionHandler({StatusRuntimeException.class, StatusException.class})
    public ResponseEntity<ApiResponse> handleRpcStatus(Exception e) {
        log.error("unconverted gRPC status error", e);
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    // ============== 4. 解析 / 校验失败：200 + 5001 ==============

    /**
     * body 不可读（非法 JSON / 类型不匹配）⇒ <b>200 + 5001</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java} 的
     * {@code handleUnreadableBody}（{@code HttpMessageNotReadableException}）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("unreadable request body : {}", e.getMessage());
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    /**
     * Bean Validation 失败（必填字段缺失 / 显式 null）⇒ <b>200 + 5001</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java} 的
     * {@code handleGoRequiredField}（{@code GoRequiredFieldException}）。
     *
     * <p>⚠️ <b>C 侧换成 {@code MethodArgumentNotValidException} 的理由</b>（A10）：
     * 手写必填校验器已被禁用 ⇒ 必填判定改由 DTO 上的 {@code @NotNull} 承担
     * ⇒ 失败时的载体就是 Spring 的标准校验异常。
     * ⇒ 出口与 B 侧<b>完全相同</b>（200 + {code:5001,msg:"系统内部错误"}）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleValidation(MethodArgumentNotValidException e) {
        log.warn("request validation failed : {}", e.getMessage());
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    // ============== 5. 框架级错误：404 / 405 / 415 ==============

    /**
     * 没有匹配的路由 ⇒ <b>404</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java} 的
     * {@code handleNoResourceFound} ＋ {@code GoStyleRouterErrorResponses.notFound(...)}。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<byte[]> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("no handler for {} : {}", e.getResourcePath(), e.getMessage());
        return RouterErrorResponses.notFound(e.getResourcePath());
    }

    /**
     * 路径存在但方法不支持 ⇒ <b>405</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java} 的 {@code handleMethodNotSupported}。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("method not supported : {}", e.getMessage());
        return RouterErrorResponses.methodNotAllowed(
                e.getSupportedHttpMethods() == null ? java.util.List.of()
                        : e.getSupportedHttpMethods().stream().map(Object::toString).toList());
    }

    /**
     * Content-Type 不支持 ⇒ <b>415</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java} 的 {@code handleMediaTypeNotSupported}。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("media type not supported : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }

    // ============== 6. 兜底 ==============

    /**
     * 其余任何异常 ⇒ <b>200 + 5001</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java} 的 {@code handleAny}。
     *
     * <p>⚠️ <b>{@link FatalError} 必须原样重抛</b>：那是「不可恢复」的信号
     * （B 侧 {@code followcommon.FatalError} 的语义），折叠成 5001 会把
     * 「进程级故障」伪装成「一次请求失败」。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleAny(Exception e) {
        if (e instanceof FatalError fatal) {
            throw fatal;
        }
        log.error("unhandled exception", e);
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    // ============== 工具 ==============

    private static ResponseEntity<ApiResponse> okEnvelope(int code, String msg) {
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse(code, msg, null));
    }

    private static int serverCommon() {
        return ErrCodes.ERROR_SERVER_COMMON;
    }

    private static String serverCommonMsg() {
        return ErrMsg.getMsg(serverCommon());
    }
}
