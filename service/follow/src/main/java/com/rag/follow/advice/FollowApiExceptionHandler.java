package com.rag.follow.advice;

import com.rag.common.contract.ApiResponse;
import com.rag.common.exception.AuthenticationException;
import com.rag.common.exception.BusinessException;
import com.rag.common.logger.FatalError;
import com.rag.common.protection.ServiceBusyException;
import com.rag.common.web.RouterErrorResponses;
import com.rag.follow.contract.CodeError;
import com.rag.follow.contract.ErrCodes;
import com.rag.follow.contract.ErrMsg;
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

import java.util.Collection;
import java.util.List;

/**
 * <p><b>全局异常处理器</b> —— 对应 B 侧
 * {@code service/follow/follow-api/src/main/java/com/rag/follow/follow/api/advice/GlobalExceptionHandler.java:75-333}
 * （分支逐条照抄 + C 侧落点变化）。
 *
 * <h2>B 出处（逐字，已读）</h2>
 * <pre>
 * httpx.SetErrorHandler(func(err error) (int, interface{}) {
 *     code := followcommon.ErrorServerCommon        // 5001
 *     msg  := followcommon.GetErrMsg(code)         // "internal server error"
 *     var codeErr *followcommon.CodeError
 *     if errors.As(err, &amp;codeErr) {              // errors.As：可穿透包装
 *         code = codeErr.Code
 *         msg  = codeErr.Msg
 *     }
 *     return http.StatusOK, &amp;response.Response{Code: code, Msg: msg, Data: nil}
 * })
 * </pre>
 *
 * <h3>⚠️ 与 users 域 api 的关键差异：<b>本服务把所有业务错误压成 HTTP 200</b></h3>
 * <p>Go 侧 {@code service/user/**} 的两个 api <b>从未</b>调用 {@code httpx.SetErrorHandler}
 * ⇒ 解析失败落到 go-zero 默认分支 {@code http.Error(w, err.Error(), 400)}
 * ⇒ <b>HTTP 400 + text/plain</b>；而 <b>follow api 注册了</b> ⇒ 默认分支被整个替换掉 ⇒
 * <b>包括 {@code httpx.Parse} 的解析失败在内，一切都是 HTTP 200 + envelope</b>。
 * <table border="1">
 *   <caption>解析失败的两条截然不同的出口</caption>
 *   <tr><th>场景</th><th>B 侧 follow api（真源）</th><th>B 侧 user api / 本仓 users 域</th></tr>
 *   <tr>
 *     <td>body 非法 JSON</td>
 *     <td rowspan="3"><b>200</b> + {@code {code:5001,msg:"internal server error",data:null}}</b></td>
 *     <td rowspan="3"><b>400</b> + text/plain</td>
 *   </tr>
 *   <tr><td>必填字段缺失（{@code target_id}）</td></tr>
 *   <tr><td>标量类型不匹配（{@code offset:"10"}）</td></tr>
 * </table>
 * ⇒ ⇒ <b>照抄 users 域的 400 形状会构成行为回归</b>。
 *
 * <h3>⚠️ 鉴权失败是唯一的例外，且 body 为空</h3>
 * <p>B 侧 {@code rest/handler/authhandler.go:106 unauthorized()} 只调
 * {@code writer.WriteHeader(http.StatusUnauthorized)}，不写任何字节。
 * <p>⚠️ <b>但黑名单那条 401 不是空 body</b>：它写
 * {@code {"code":5004,"msg":"unauthorized"}}，且<b>不经过本类</b>（由拦截器直接写响应）。
 * ⇒ 本域共有<b>三种</b>错误响应形状：
 * <ol>
 *   <li>业务失败 → <b>200</b> + {@code {code,msg,data}}；</li>
 *   <li>验签失败 → <b>401</b> + <b>空 body</b>；</li>
 *   <li>黑名单命中 → <b>401</b> + {@code {code,msg}}（无 {@code data}）。</li>
 * </ol>
 *
 * <h3>⚠️ 本类的分支清单（逐条对应 B 侧 + C 侧落点）</h3>
 * <table border="1">
 *   <caption>分支 → 出口</caption>
 *   <tr><th>异常</th><th>出口</th></tr>
 *   <tr><td>{@link AuthenticationException}</td><td><b>401 + 空 body</b></td></tr>
 *   <tr><td>{@link BusinessException}</td><td>200 + {@code {code,msg,data:null}}</td></tr>
 *   <tr><td>{@link CodeError}（含 cause 链）</td><td>200 + {@code {code,msg,data:null}}</td></tr>
 *   <tr><td>{@link StatusRuntimeException} / {@link StatusException}</td><td>200 + 5001</td></tr>
 *   <tr><td>{@link HttpMessageNotReadableException}</td><td>200 + 5001</td></tr>
 *   <tr><td>{@link MethodArgumentNotValidException}</td><td>200 + 5001</td></tr>
 *   <tr><td>{@link NoResourceFoundException}</td><td><b>404</b>（{@link RouterErrorResponses#notFound}）</td></tr>
 *   <tr><td>{@link HttpRequestMethodNotSupportedException}</td><td><b>405</b>（{@link RouterErrorResponses#methodNotAllowed}）</td></tr>
 *   <tr><td>{@link HttpMediaTypeNotSupportedException}</td><td><b>415</b>（兜底分支）</td></tr>
 *   <tr><td>{@link ServiceBusyException}</td><td>200 + <b>1015 + "service busy"</b></td></tr>
 *   <tr><td>{@link FatalError}</td><td><b>原样重抛</b>，绝不转信封</td></tr>
 *   <tr><td>{@link Exception}（兜底）</td><td>200 + 5001</td></tr>
 * </table>
 */
@RestControllerAdvice
public class FollowApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FollowApiExceptionHandler.class);

    // ============== 1. 鉴权失败：401 + 空 body ==============

    /**
     * B 侧 {@code unauthorized()} 的逐字等价物：<b>401，空 body</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:91-95}
     * （{@code return ResponseEntity.status(401).build()}）。
     *
     * <p>返回 {@code ResponseEntity.status(401).build()} 而不是带 envelope 的 body ——
     * 后者会把 B 侧从未输出过的字节写给客户端。空 body 不会被
     * {@link FollowApiResponseAdvice} 包成 envelope（它对 {@code null} 直接放行）。
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
     *   <li>取不到 userId → {@code NewErrCode(ErrorUnauthorized)} → <b>5004</b>（8 个端点）</li>
     *   <li>归属校验失败 → {@code NewErrCode(ErrorForbidden)} → <b>5005</b>
     *       （{@code /follow/v1/block/list} + {@code /follow/v1/recommendations}）</li>
     *   <li>{@code NewErrCodeMsg(rpcResp.Code, rpcResp.Msg)} → <b>原样透传</b> code + msg</li>
     *   <li>领域层的其它业务码（5201 / 5202 / 5101 …）</li>
     * </ul>
     *
     * <p><b>HTTP 恒为 200</b>：B 侧的 error handler 返回 {@code http.StatusOK}。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse> handleBusiness(BusinessException e) {
        log.error("BusinessException code={} msg={}", e.getCode(), e.getMessage());
        return okEnvelope(e.getCode(), e.getMessage());
    }

    /**
     * B 侧 {@code *followcommon.CodeError} 的直译分支。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:35-38}
     * （{@code if errors.As(err, &codeErr) { code = codeErr.Code; msg = codeErr.Msg }}）。
     *
     * <p>⚠️ <b>C 侧为什么要有这一条（B 侧的 {@code CodeError} 与共享层的 {@code BusinessException}
     * 是两个载体）</b>：B 侧的 {@code errors.As} 会<b>沿 cause 链</b>找 {@code CodeError}；
     * C 侧的 {@link CodeError}（域内契约）不是 {@link BusinessException} 的子类
     * ⇒ 若不单列，它会落进 {@link #handleAny} 的兜底 ⇒ 业务码（如 5201）被换成 5001
     * ⇒ <b>那是可观察的业务码漂移</b>。
     * ⇒ {@code CodeError} 自己的类注释也明确要求「写 api 层异常处理器时……必须遍历
     * {@link Throwable#getCause()} 链，不能只判顶层」。
     *
     * <p>⚠️ <b>msg 原样透传</b>（B 侧 {@code NewErrCodeMsg} 的形状）⇒ 不查本地码表。
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
     * 「非 {@code CodeError}」一律给 5001 + {@code "internal server error"}；
     * 若在这里按 gRPC 码反查，得到的会是 1015 / 5003 / … ⇒ <b>与 B 侧兜底语义不同</b>。
     * ⇒ 并且 <b>B 侧的 {@code rpcError} 分支在 C 侧不存在</b>（D2：域内直连，没有 gRPC 往返）⇒
     * ⇒ 本类<b>不实现</b>「gRPC → 业务码」的反查。
     *
     * <p>⚠️ <b>唯一的例外</b>：若异常链里有 {@link CodeError} ⇒ 按 B 侧 {@code errors.As} 的语义
     * 用它的 code + msg（与 {@link #handleAny} 同一份判定，见 {@link #findCodeError(Throwable)}）。
     */
    @ExceptionHandler({StatusRuntimeException.class, StatusException.class})
    public ResponseEntity<ApiResponse> handleRpcStatus(Exception e) {
        Status.Code code = e instanceof StatusRuntimeException sre
                ? sre.getStatus().getCode()
                : ((StatusException) e).getStatus().getCode();
        String desc = e instanceof StatusRuntimeException sre
                ? sre.getStatus().getDescription()
                : ((StatusException) e).getStatus().getDescription();
        CodeError codeError = findCodeError(e);
        if (codeError != null) {
            log.error("gRPC status carrying CodeError code={} msg={}", codeError.getCode(), codeError.getMsg());
            return okEnvelope(codeError.getCode(), codeError.getMsg());
        }
        log.error("unconverted gRPC status code={} desc={}", code, desc, e);
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    // ============== 4. 解析层失败：一律 5001 + 200 ==============

    /**
     * {@code httpx.Parse} 失败 —— 非法 JSON / 类型不匹配 / body 畸形。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:166-170}
     * （{@code httpx.Parse} 失败 → {@code httpx.ErrorCtx} → {@code SetErrorHandler} 已注册
     * ⇒ <b>200 + 5001</b>）。
     *
     * <p>本域的真实触发点：
     * <ul>
     *   <li>{@code {"offset":"10"}} —— 无 {@code ,string} 的整数字段收到 JSON 字符串
     *       （Go: {@code type mismatch for field "offset"}），
     *       由本域的 Jackson 强转补强（{@code LogicalType.Integer + String → Fail}）让 Jackson 也拒绝；</li>
     *   <li>{@code target_id: 1.5} —— {@code StringInt64} 抛 {@code IllegalArgumentException}，
     *       被 Spring 包成 {@code HttpMessageNotReadableException}；</li>
     *   <li>body 为空（动作端点）—— {@code required = true} ⇒ 同一个异常。</li>
     * </ul>
     *
     * <p><b>错误文本刻意不对齐</b>：Go 的文本来自 go-zero 的 mapping 层，
     * Java 侧来自 Jackson。两边都<b>不会</b>把它写进 body（body 恒为
     * {@code msg:"internal server error"}）⇒ 文本差异在本服务里<b>不可观测</b>。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("unreadable request body : {}", e.getMessage());
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    /**
     * Bean Validation 违规（{@code target_id} 缺席 / 显式 null）→ <b>200 + 5001</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:180-185} 捕获
     * {@code GoRequiredFieldException}（go-zero 的必填校验器）⇒ 同一条路 ⇒ 5001。
     *
     * <p>⚠️ <b>C 侧换成了 Bean Validation</b>（A10 禁用手写校验器）⇒ 异常类型从
     * {@code GoRequiredFieldException} 变成 Spring 的 {@link MethodArgumentNotValidException}
     * ⇒ <b>但业务码与 HTTP 状态码逐条不变</b>（200 + 5001）⇒ 形状等价 ✔。
     *
     * <p>⚠️ <b>文案不同</b>（Hibernate Validator vs go-zero mapping）⇒ 不可观测
     * （body 恒为 {@code msg:"internal server error"}）⇒ 不构成漂移。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleBeanValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse(e.getMessage());
        log.warn("bean validation failed : {}", message);
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    // ============== 5. 框架级错误：保留 Spring / B 侧路由层语义 ==============

    /**
     * 路径不存在 → <b>404</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:238-243}
     * （{@code GoStyleRouterErrorResponses.notFound(e.getResourcePath())}）。
     *
     * <p><b>⚠️ 这条是必需的，不是可选的兜底</b>：Spring 6+ 对未匹配路径抛
     * {@link NoResourceFoundException}，它会落进 {@link #handleAny} 的兜底，
     * 于是未知路径被改写成 <b>HTTP 200 + {code:5001}</b> —— 而 B 侧的 router 对未注册路径
     * 返回 <b>404</b>。
     *
     * <p>⚠️ body / Content-Type / {@code nosniff} 全部由共享层的
     * {@link RouterErrorResponses#notFound} 给出（逐字节证据见该类 javadoc）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<byte[]> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("no handler for {} : {}", e.getResourcePath(), e.getMessage());
        return RouterErrorResponses.notFound(e.getResourcePath());
    }

    /**
     * HTTP 方法不支持 → <b>405</b>（裸状态码 · 无 body · 无 Content-Type）。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:246-253}。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("method not supported : {}", e.getMessage());
        Collection<String> allowed = e.getSupportedHttpMethods() == null
                ? List.of()
                : e.getSupportedHttpMethods().stream().map(Object::toString).toList();
        return RouterErrorResponses.methodNotAllowed(allowed);
    }

    /**
     * Content-Type 不支持 → <b>415</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:224-228}。
     *
     * <p><b>⚠️ 本分支在本域<b>不可达</b>（兜底，不是主路径）</b>：共享层的
     * {@code JsonBodyConverterAutoConfiguration} 把 {@code JsonBodyConverter} 插到
     * Jackson 转换器<b>之前</b>，而它 {@code setSupportedMediaTypes(MediaType.ALL)}
     * ⇒ Spring 的消息转换器根本轮不到「媒体类型不匹配」这条分支
     * ⇒ {@code HttpMediaTypeNotSupportedException} 不会被抛出。
     * <p>⇒ <b>保留分支 ＝ 留下「一旦变成主路径就会与 B 侧不一致」的提示</b>，
     * 而不是把它当成当前已知的、已签字的行为差异。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("media type not supported : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }

    // ============== 6. 熔断 / 致命错误 / 兜底 ==============

    /**
     * gRPC client 熔断打开 → <b>200 + 1015 + "service busy"</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:272-276}。
     *
     * <p><b>⚠️ 为什么必须单列，不能落进 {@link #handleAny} 的兜底分支</b>：
     * B 侧的熔断错误是<b>普通 error</b>（不是 gRPC status）⇒ {@code BizCodeFromError}
     * 把它转成 {@code CodeServerBusy = 1015}、文案查表得 {@code "service busy"}。
     * <p>C 侧的对应物是共享层的 {@link ServiceBusyException}（B 侧的
     * {@code GoStyleCircuitOpenException} 在本仓的落点）—— 它<b>刻意不是</b>
     * {@code StatusRuntimeException}（否则文案会变成 {@code "circuit breaker is open"}
     * 而非 B 侧的 {@code "service busy"}）⇒ ⇒ 它<b>绕过</b> gRPC status 分支
     * ⇒ ⇒ 必须单列，否则业务码与 B 侧不同。
     *
     * <p>⚠️ <b>可达性</b>：本域唯一的跨域调用是 {@code UserRpcClient}（跨域 gRPC，D6）
     * ⇒ 共享层的 {@code ClientCircuitGateInterceptor} 会在熔断打开时抛本异常
     * ⇒ <b>本分支可达</b>（不是死代码）。
     */
    @ExceptionHandler(ServiceBusyException.class)
    public ResponseEntity<ApiResponse> handleServiceBusy(ServiceBusyException e) {
        log.error("gRPC client circuit breaker is open", e);
        return okEnvelope(serverBusy(), serverBusyMsg());
    }

    /**
     * {@link FatalError} 原样抛出，<b>绝不</b>转 envelope。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:285-288}。
     *
     * <p>与其它域同款理由：B 侧 {@code logger.go} 在 fatal_error 之后无条件
     * {@code os.Exit(1)}，「fatal 后必须停止接流量」是硬契约；
     * 转成 200 + 5001 会让请求看起来「只是业务码不对」而进程继续正常服务。
     */
    @ExceptionHandler(FatalError.class)
    public void handleFatal(FatalError e) {
        // 故意不返回 ResponseEntity：让异常继续向上抛
        throw e;
    }

    /**
     * 兜底 —— 对应 B 侧 error handler 的 default 分支：<b>200 + 5001</b>。
     *
     * <p>B 出处：B 侧 {@code GlobalExceptionHandler.java:298-305}。
     *
     * <p>B 侧对任何非 {@code CodeError} 都返回 5001，所以「Spring 抛出的任何意外异常」
     * 在 B 侧也只会得到 5001 —— 这里保持一致。
     * 刻意<b>不</b>用 500：那会让 follow 成为本仓唯一一个「服务端内部错误会返回 5xx」
     * 的服务，破坏 B 侧「除 401 外恒为 200」的对外契约。
     *
     * <p>⚠️ <b>但仍先按 B 侧 {@code errors.As} 的语义找一次 {@link CodeError}</b>
     * （沿 cause 链）⇒ 找到了就用它的 code + msg ⇒ 与 B 侧逐条一致。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleAny(Exception e) {
        if (e instanceof FatalError fatal) {
            // FatalError 已在上面被专门处理；这里若仍收到，说明 advice 未注册 FatalError 分支，
            // 此时也必须放行而不是包成 envelope。
            throw fatal;
        }
        CodeError codeError = findCodeError(e);
        if (codeError != null) {
            log.error("exception chain carries CodeError code={} msg={}", codeError.getCode(), codeError.getMsg());
            return okEnvelope(codeError.getCode(), codeError.getMsg());
        }
        log.error("unhandled exception", e);
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    // ============== 工具 ==============

    /**
     * B 侧 {@code errors.As(err, &codeErr)} 的等价物 —— <b>沿 cause 链</b>找
     * {@link CodeError}。
     *
     * <p>⚠️ <b>必须沿链找，不能只判顶层</b>：B 侧的 {@code errors.As} 就是这个语义
     * （{@code CodeError} 自己的类注释也明确要求这一点）。
     *
     * @param err 任意异常（可为 null）
     * @return 链上的第一个 {@link CodeError}；没有则 {@code null}
     */
    private static CodeError findCodeError(Throwable err) {
        Throwable current = err;
        // ⚠️ 防御性上限：cause 链理论上可成环 ⇒ 限制深度，避免把栈打满
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof CodeError codeError) {
                return codeError;
            }
            current = current.getCause();
        }
        return null;
    }

    /** B 侧 {@code GlobalExceptionHandler.java:309-311} 的 {@code okEnvelope}。 */
    private static ResponseEntity<ApiResponse> okEnvelope(int code, String msg) {
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse(code, msg, null));
    }

    /** B 侧 {@code errmsg.go} 的 {@code ErrorServerCommon = 5001}。 */
    private static int serverCommon() {
        return ErrCodes.ERROR_SERVER_COMMON;
    }

    /** 5001 的文案（查表 ⇒ {@code "internal server error"}）。 */
    private static String serverCommonMsg() {
        return ErrMsg.getMsg(serverCommon());
    }

    /** B 侧 {@code errmsg.go:37} 的 {@code CodeServerBusy = 1015}。 */
    private static int serverBusy() {
        return ErrCodes.CODE_SERVER_BUSY;
    }

    /**
     * msg 一律<b>查表</b>（B 侧 {@code errmsg.go:59}：{@code CodeServerBusy: "service busy"}），
     * 刻意不用 {@code e.getMessage()} —— B 侧的文案同样来自码表，不是异常文本。
     */
    private static String serverBusyMsg() {
        return ErrMsg.getMsg(serverBusy());
    }
}
