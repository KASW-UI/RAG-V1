package com.rag.message.advice;

import com.rag.common.contract.ApiResponse;
import com.rag.common.exception.AuthenticationException;
import com.rag.common.exception.BusinessException;
import com.rag.common.logger.FatalError;
import com.rag.common.protection.ServiceBusyException;
import com.rag.common.web.RouterErrorResponses;
import com.rag.message.contract.ErrCodes;
import com.rag.message.contract.ErrMsg;
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

import java.util.List;

/**
 * <p><b>职责</b>：message 域 HTTP 面的<b>异常出口</b> —— 把「业务层抛出的异常」翻译成
 * B 侧 go-zero 那套 wire 形状。
 *
 * <p><b>B 出处</b>：{@code service/message/message-api/src/main/java/com/rag/message/message/api/advice/GlobalExceptionHandler.java}
 * （1-316 行）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：四处<b>纯改名/换包</b> ＋ <b>一个新增分支</b>：
 *       <ul>
 *         <li>类名 {@code GlobalExceptionHandler} → {@code MessageApiExceptionHandler}
 *             （映射表 message.md §0.5 已批准的改名）；</li>
 *         <li>信封载体 {@code com.rag.common.response.Response} → {@link ApiResponse}
 *             （共享层 D3 上提；L1 三键与字段顺序不变）；</li>
 *         <li>错误码取值 {@code MessageErrorCode.XXX.getCode()} → {@link ErrCodes#XXX}
 *             ＋ {@code MessageErrorMessages.getMsg(x)} → {@link ErrMsg#getMsg(int)}；</li>
 *         <li>框架级 404 / 405 的实现：{@code com.rag.common.http.GoStyleRouterErrorResponses}
 *             → 共享层 {@link RouterErrorResponses}（D3 上提；<b>body / Content-Type / nosniff
 *             逐字节由共享实现给出</b>，本类只负责转交）；</li>
 *         <li><b>新增分支</b> {@link MethodArgumentNotValidException}（Bean Validation 失败）——
 *             B 侧对应的是 {@code com.rag.users.common.http.GoRequiredFieldException}
 *             （B 侧 {@code :171-176}），而本仓按 D5 改用 Bean Validation
 *             ⇒ 「必填字段缺失」现在由 Hibernate Validator 表达 ⇒ 它抛的是
 *             {@code MethodArgumentNotValidException} ⇒ <b>必须接住，否则会被兜底分支吞成
 *             5001</b>（结局恰好也是 5001，但走的是「未知异常」那条路 ⇒ 语义与可观测性都不同）。
 *             ⇒ 本分支<b>替代</b>（不是叠加于）B 侧那条。</li>
 *       </ul>
 *       <b>方法体逐条照抄</b>：{@code handleAuthentication} / {@code handleBusiness} /
 *       {@code handleRpcStatus} / {@code handleUnreadableBody} / {@code handleMediaTypeNotSupported} /
 *       {@code handleNoResourceFound} / {@code handleMethodNotSupported} / {@code handleFatal} /
 *       {@code handleAny} 与两个工具方法。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 见下方「message api 的对外契约」小节的逐条论证。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号（逐方法另给行号）。</li>
 * </ol>
 *
 * <p><b>🔴 message api 对客户端的契约：除鉴权失败外，HTTP 状态码恒为 200，成败全在 body 的 code 里。</b>
 * <p>Go 侧 {@code service/message/api/messagecenter.go} 注册了 {@code httpx.SetErrorHandler}，
 * 默认分支被整个替换掉 ⇒ <b>包括 {@code httpx.Parse} 的解析失败在内，一切都是
 * HTTP 200 + envelope</b>。
 * <p>⚠️ 具体差异（照抄 user-api 的 400 text/plain 会直接构成行为回归）：
 * <table border="1">
 *   <caption>解析失败的两条截然不同的出口</caption>
 *   <tr><th>场景</th><th>Go message api（真源）</th><th>Go user api</th></tr>
 *   <tr>
 *     <td>body 非法 JSON</td>
 *     <td rowspan="3"><b>200</b> + {@code {code:5001,msg:"internal server error",data:null}}</td>
 *     <td rowspan="3"><b>400</b> + text/plain</td>
 *   </tr>
 *   <tr><td>必填字段缺失</td></tr>
 *   <tr><td>标量类型不匹配</td></tr>
 * </table>
 *
 * <p><b>鉴权失败是唯一的例外，且 body 为空</b>：Go
 * {@code rest/handler/authhandler.go:106 unauthorized()} 只调
 * {@code writer.WriteHeader(http.StatusUnauthorized)}，不写任何字节 ⇒ 本类逐字还原成
 * <b>401 + 空 body</b>（{@link MessageApiResponseAdvice} 对 {@code null} body 原样放行 ⇒
 * 不会被包成 envelope）。
 *
 * <p><b>框架级错误（404 / 405 / 415）保留 Spring / go-zero 的裸状态码语义</b>：
 * 它们<b>不是</b> text/plain 的业务信封，而是 {@link RouterErrorResponses} 给出的裸响应。
 */
@RestControllerAdvice
public class MessageApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageApiExceptionHandler.class);

    // ============== 1. 鉴权失败：401 + 空 body ==============

    /**
     * Go {@code unauthorized()} 的逐字等价物：<b>401，空 body</b>。
     *
     * <p>返回 {@code ResponseEntity.status(401).build()} 而不是带 envelope 的 body ——
     * 后者会把 Go 从未输出过的字节写给客户端。空 body 不会被
     * {@link MessageApiResponseAdvice} 包成 envelope（它对 {@code null} 直接放行）。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:90-94}
     * （Go 侧 {@code rest/handler/authhandler.go:106}）。
     *
     * @param e 鉴权异常（{@code AuthenticationException extends BusinessException}）
     * @return 401 + 空 body
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Void> handleAuthentication(AuthenticationException e) {
        log.warn("AuthenticationException code={} msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // ============== 2. 业务异常：200 + envelope ==============

    /**
     * Go {@code errors.As(err, &codeErr)} 分支 —— {@code CodeError} 直接决定 code + msg。
     *
     * <p>对应 Go 侧三类抛出点：
     * <ul>
     *   <li>{@code shared.UserIDFromContext} 失败 → {@code NewErrCode(ErrorUnauthorized)} → 5004</li>
     *   <li>{@code SendAdminNotification} 的 target 校验 →
     *       {@code shared.RPCError(NewErrCode(ErrorInvalidParam))} → 5003</li>
     *   <li>{@code shared.RPCError(err)} → {@code NewErrCode / NewErrCodeMsg}</li>
     * </ul>
     *
     * <p><b>HTTP 恒为 200</b>：Go 的 error handler 返回 {@code http.StatusOK}。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:112-116}。
     *
     * @param e 业务异常（携带业务码与文案）
     * @return 200 + {@code {code,msg,data:null}} 信封
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse> handleBusiness(BusinessException e) {
        log.error("BusinessException code={} msg={}", e.getCode(), e.getMessage());
        return okEnvelope(e.getCode(), e.getMessage());
    }

    // ============== 3. gRPC 异常（Service 未捕获时的兜底） ==============

    /**
     * 正常路径下各 Service 都会先调 {@code MessageApiSupport.rpcError(...)} 把
     * {@link StatusRuntimeException} 转成 {@link BusinessException}，本 handler 只是最后一道网。
     *
     * <p>映射规则与 Go 的 {@code httpx.SetErrorHandler} 兜底分支完全一致：
     * 非 {@code CodeError} ⇒ {@code ErrorServerCommon}（5001）+ {@code "internal server error"}。
     * 因此这里<b>不</b>调用 {@code MessageApiSupport.rpcError} ——
     * 那会把 code 变成「按 gRPC code 反查」的结果，与 Go 的兜底语义不同。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:129-139}。
     *
     * @param e gRPC 状态异常
     * @return 200 + {@code {code:5001,msg:"internal server error",data:null}} 信封
     */
    @ExceptionHandler({StatusRuntimeException.class, StatusException.class})
    public ResponseEntity<ApiResponse> handleRpcStatus(Exception e) {
        Status.Code code = e instanceof StatusRuntimeException sre
                ? sre.getStatus().getCode()
                : ((StatusException) e).getStatus().getCode();
        log.error("unconverted gRPC status code={} desc={}", code,
                e instanceof StatusRuntimeException sre
                        ? sre.getStatus().getDescription()
                        : ((StatusException) e).getStatus().getDescription(), e);
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    // ============== 4. 解析层失败：一律 5001 + 200 ==============

    /**
     * {@code httpx.Parse} 失败 —— 非法 JSON / 类型不匹配 / body 畸形。
     *
     * <p>Go 出处：{@code rest/httpx/requests.go} 的 {@code ParseJsonBody} →
     * {@code mapping.UnmarshalJsonReader} 返回 error → handler 调 {@code httpx.ErrorCtx}
     * → {@code SetErrorHandler} 已注册 ⇒ <b>200 + 5001</b>。
     *
     * <p><b>错误文本刻意不对齐</b>：Go 的文本来自 go-zero 的 mapping 层，Java 侧来自 Jackson。
     * 两边都<b>不会</b>把它写进 body（body 恒为 {@code msg:"internal server error"}）⇒
     * 文本差异在本服务里<b>不可观测</b>。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:156-160}。
     *
     * @param e body 不可读异常
     * @return 200 + 5001 信封
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("unreadable request body : {}", e.getMessage());
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    /**
     * <b>【D5 / D-01 降级 · 新增分支】</b>Bean Validation 失败 → <b>200 + 5001</b>。
     *
     * <p>B 侧这里是 {@code GoRequiredFieldException}（B 侧 {@code :171-176}）：
     * go-zero 必填字段缺失 → 同上路径 → 200 + 5001。
     * 本仓按 D5 改用 Bean Validation（{@code @NotNull}）⇒ 「必填字段缺失」现在由
     * Hibernate Validator 表达 ⇒ 它抛的是 {@link MethodArgumentNotValidException}。
     *
     * <p>⚠️ <b>为什么不能落进兜底分支</b>：兜底 {@link #handleAny} 的结局<b>恰好</b>也是 5001，
     * 但那条路的语义是「服务端内部错误」⇒ 日志级别与可观测性都不同 ⇒ 必须单列。
     *
     * <p><b>⚠️ 错误文案口径（D-01 已登记的 L3 降级 · 不需要用户签字）</b>：
     * 文案由 Hibernate Validator 决定，本仓<b>不</b>还原 go-zero 的
     * {@code field "x" is not set} 字面量 ⇒ ⇒ body 的<b>形状</b>（200 + 信封）与 B 侧一致，
     * <b>文本内容</b>属于已登记的降级项。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:171-176}（B 侧类型，
     * 本仓按 D5 换类型）。
     *
     * @param e Bean Validation 失败异常
     * @return 200 + 5001 信封
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleBeanValidation(MethodArgumentNotValidException e) {
        log.warn("bean validation failed : {}", e.getMessage());
        return okEnvelope(serverCommon(), serverCommonMsg());
    }

    // ============== 5. 框架级错误：保留 Spring / go-zero 语义 ==============

    /**
     * Content-Type 不支持 → 415。
     *
     * <p><b>已知偏离（OBSERVABLE DIFFERENCE · B 侧已登记）</b>：Go 侧的 go-zero 没有
     * 「媒体类型协商」这回事，{@code withJsonBody} 只判 Content-Type 是否<b>包含</b>
     * {@code application/json}；不含时它<b>不报错</b>，而是走
     * {@code UnmarshalJsonMap(nil, v)}。Java 侧由 Spring 的消息转换器选择直接判 415。
     *
     * <p>取舍：本服务选择保留 Spring 默认（不为了「更接近 Go」去放宽媒体类型协商），
     * 差异只出现在<b>客户端 Content-Type 写错</b>时，两侧都是拒绝、都不产生业务副作用。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:194-198}。
     *
     * @param e 媒体类型不支持异常
     * @return 415 + 空 body
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("media type not supported : {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }

    /**
     * 路径不存在 → 404。
     *
     * <p><b>这条是必需的，不是可选的兜底。</b>Spring 6+ 对未匹配路径抛
     * {@link NoResourceFoundException}，它会落进下面的 {@link #handleAny(Exception)}，
     * 于是未知路径被改写成 <b>HTTP 200 + {code:5001}</b> —— 而 Go 的 router 对未注册路径
     * 返回 <b>404</b>。
     *
     * <p>道理与「层次由类型承载」一致：路由未命中与业务失败是两种不同的结果，
     * 不能共用同一个 fallback 出口，否则「客户端调错了地址」会被伪装成「服务端处理失败」。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:213-218}
     * （body / Content-Type / nosniff 全部由共享实现
     * {@link RouterErrorResponses#notFound(String)} 给出，逐字节证据见该类 javadoc）。
     *
     * @param e 资源未找到异常
     * @return 404 + B 侧精确形状的裸响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<byte[]> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("no handler for {} : {}", e.getResourcePath(), e.getMessage());
        return RouterErrorResponses.notFound(e.getResourcePath());
    }

    /**
     * HTTP 方法不支持 → 405（Go 的 router 同样不匹配 ⇒ 405）。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:221-229}。
     * ⚠️ Go 侧是<b>裸状态码</b>（只 WriteHeader，无 body、无 Content-Type），
     * 多出来的只有 Allow 头。Allow 的取值顺序刻意不排序，见共享实现类 javadoc。
     *
     * @param e 方法不支持异常
     * @return 405 + 空 body（带 Allow 头）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("method not supported : {}", e.getMessage());
        return RouterErrorResponses.methodNotAllowed(
                e.getSupportedHttpMethods() == null ? List.of()
                        : e.getSupportedHttpMethods().stream().map(Object::toString).toList());
    }

    // ============== 6. 熔断 / 致命错误 / 兜底 ==============

    /**
     * gRPC client 熔断打开 → <b>1015 + "service busy"</b>。
     *
     * <p><b>为什么必须单列，不能落进 {@link #handleAny} 的兜底分支</b>：
     * Go 侧每个 logic 的错误出口是 {@code if err != nil { return nil, shared.RPCError(err) }}，
     * 而 Go 的 {@code err != nil} 是<b>捕获一切</b>，所以熔断抛出的
     * {@code breaker.ErrServiceUnavailable}（普通 error）也会被
     * {@code RPCError → BizCodeFromError} 转成 {@code CodeServerBusy=1015}。
     *
     * <p>本仓的对应写法是 {@code catch (StatusRuntimeException e) { throw support.rpcError(e); }}
     * —— <b>只捕 gRPC status</b>。而共享层的 {@link ServiceBusyException}
     * （B 侧对应 {@code GoStyleCircuitOpenException}）刻意不是 status
     * ⇒ 它<b>绕过</b> service 的 catch，最终落到 {@link #handleAny} 被兜底成 <b>5001</b>
     * ⇒ 客户端看到的业务码与 Go 不同（1015 vs 5001）。
     *
     * <p>⚠️ <b>没有把 13 个 service 的 catch 改成 {@code Exception}</b>：
     * 那个 try 里除了 stub 调用，还包着响应转换；一旦它抛 {@code BusinessException}，
     * 宽 catch 会把它重新包成 1015 —— 那是<b>新引入的错</b>。
     * 所以在这里按异常类型精确处理，是副作用最小且可证明的。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:275-279}
     * （B 侧类型 {@code com.rag.common.protection.GoStyleCircuitOpenException}
     * ⇒ 本仓按 D3 上收为共享层 {@link ServiceBusyException}，语义逐条不变）。
     *
     * @param e 熔断打开异常
     * @return 200 + {@code {code:1015,msg:"service busy",data:null}} 信封
     */
    @ExceptionHandler(ServiceBusyException.class)
    public ResponseEntity<ApiResponse> handleCircuitOpen(ServiceBusyException e) {
        log.error("gRPC client circuit breaker is open", e);
        return okEnvelope(serverBusy(), serverBusyMsg());
    }

    /**
     * {@link FatalError} 原样抛出，绝不转 envelope。
     *
     * <p>与 user-api 同款理由：Go 侧 {@code logger.go} 在 fatal_error 之后无条件
     * {@code os.Exit(1)}，「fatal 后必须停止接流量」是硬契约；转成 200 + 5001
     * 会让请求看起来「只是业务码不对」而进程继续正常服务。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:240-243}。
     *
     * @param e 致命错误
     */
    @ExceptionHandler(FatalError.class)
    public void handleFatal(FatalError e) {
        throw e;
    }

    /**
     * 兜底 —— 对应 Go error handler 的 default 分支：<b>200 + 5001</b>。
     *
     * <p>Go 侧 {@code SetErrorHandler} 对任何非 {@code CodeError} 都返回 5001，
     * 所以「Spring 抛出的任何意外异常」在 Go 里也只会得到 5001 —— 这里保持一致。
     * 刻意<b>不</b>用 500：那会让 message-service 成为本仓唯一一个「服务端内部错误会返回 5xx」
     * 的服务，破坏 Go 侧「除 401 外恒为 200」的对外契约。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:281-288}。
     *
     * @param e 任意异常
     * @return 200 + 5001 信封
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

    /**
     * 造一个 200 + 信封 —— B 侧逐字照抄。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:292-294}。
     */
    private static ResponseEntity<ApiResponse> okEnvelope(int code, String msg) {
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse(code, msg, null));
    }

    /** B 侧 {@code .../GlobalExceptionHandler.java:296-298}。 */
    private static int serverCommon() {
        return ErrCodes.ERROR_SERVER_COMMON.getCode();
    }

    /** B 侧 {@code .../GlobalExceptionHandler.java:300-302}。 */
    private static String serverCommonMsg() {
        return ErrMsg.getMsg(serverCommon());
    }

    /** Go {@code errmsg.go:31} {@code CodeServerBusy = 1015}（B 侧 {@code :305-307}）。 */
    private static int serverBusy() {
        return ErrCodes.CODE_SERVER_BUSY.getCode();
    }

    /**
     * msg 一律<b>查表</b>（Go {@code errmsg.go:55}：{@code CodeServerBusy: "service busy"}），
     * 刻意不用 {@code e.getMessage()} —— Go 侧的文案同样来自码表，不是异常文本。
     * （B 侧 {@code :313-315}。）
     */
    private static String serverBusyMsg() {
        return ErrMsg.getMsg(serverBusy());
    }
}
