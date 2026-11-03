package com.rag.hot.advice;

import com.rag.common.contract.ApiResponse;
import com.rag.common.exception.BusinessException;
import com.rag.common.logger.FatalError;
import com.rag.common.web.LimitExceededException;
import com.rag.common.web.RouterErrorResponses;
import com.rag.hot.contract.CodeError;
import com.rag.hot.contract.ErrCodes;
import com.rag.hot.contract.ErrMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * <p><b>职责</b>：hot 域 HTTP 面的<b>异常出口</b> —— 把「业务层抛出的异常」翻译成
 * B 侧 go-zero 那套 wire 形状。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/advice/GlobalExceptionHandler.java}
 * <pre>{@code
 *   @RestControllerAdvice                                       :72-73
 *   onBusinessException(BusinessException) ⇒ envelope(code, bizMsg) :81-84
 *   bizMsgOf(e) ⇒ CodeError ? getBizMsg() : getMessage()          :98-100
 *   onTypeMismatch(MethodArgumentTypeMismatchException) ⇒ 5001    :109-113
 *   onAnyException(Exception) ⇒ 5001                              :124-128
 *   onNotFound(NoResourceFoundException) ⇒ 裸 404                 :135-138
 *   onMethodNotAllowed(HttpRequestMethodNotSupportedException) ⇒ 裸 405 :146-149
 *   serverCommon() ⇒ code=5001 + GetErrMsg(5001)                  :152-155
 *   envelope(code,msg) ⇒ HTTP 200 + Response(code,msg,null)       :158-160
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：四处<b>纯改名/换包</b> ＋ <b>两个新增分支</b>：
 *       <ul>
 *         <li>类名 {@code GlobalExceptionHandler} → {@code HotApiExceptionHandler}；</li>
 *         <li>信封载体 {@code com.rag.common.response.Response} → {@link ApiResponse}；</li>
 *         <li>错误码取值 {@code HotErrorCode.XXX.getCode()} → {@link ErrCodes#XXX}
 *             ＋ {@code HotErrorMessages.getMsg(x)} → {@link ErrMsg#getMsg(int)}；</li>
 *         <li>框架级 404 / 405 的实现：{@code com.rag.common.http.GoStyleRouterErrorResponses}
 *             → 共享层 {@link RouterErrorResponses}（D3 上提；<b>body / Content-Type / nosniff
 *             逐字节由共享实现给出</b>，本类只负责转交）；</li>
 *         <li><b>新增分支 {@link LimitExceededException}</b>（共享层的 form 值上限闸门在
 *             {@code envelope} 模式下抛出的异常）—— B 侧的对应物是 hot-api 自己的
 *             {@code GoFormValues.parseFailure}（抛 {@code BusinessException(5001)}）⇒
 *             <b>wire 形状完全相同</b>（200 + {code:5001}）⇒ 本分支只是<b>把同一个结局
 *             显式化</b>（日志级别可区分），<b>不改变任何可观察结果</b>；</li>
 *         <li><b>新增分支 {@link FatalError}</b>（共享层 {@code GoStyleLogger#logFatal} 抛出的）
 *             —— 本域<b>当前无触发点</b>（hot 域不调 {@code logFatal}）⇒
 *             <b>不改变任何可观察结果</b>；但必须有 ⇒ 否则「fatal 后必须停止接流量」的硬契约
 *             会被兜底分支改写成 200 + 5001（进程继续正常服务）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：一样 —— hot 域的<b>三类响应形状</b>逐条未动：
 *       <table border="1">
 *         <caption>hot-api 的三类响应形状</caption>
 *         <tr><th>场景</th><th>HTTP</th><th>body</th></tr>
 *         <tr><td>成功</td><td><b>200</b></td><td>{@code {"code":200,"msg":"OK","data":{...}}}</td></tr>
 *         <tr><td>业务失败（hot-rpc 失败、query 解析失败、框架层异常）</td>
 *             <td><b>200</b></td><td>{@code {"code":5001,"msg":"internal server error","data":null}}</td></tr>
 *         <tr><td>404 / 405（路由层，不进任何 handler）</td>
 *             <td>404 / 405</td><td><b>裸响应，无信封</b></td></tr>
 *       </table></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号（逐方法另给行号）。</li>
 * </ol>
 *
 * <p><b>⚠️ 错误码必须是 hot 域自己的表，不能复用 users / article</b>：
 * hot 的 5002/5003/5004 与其它域的同号码<b>语义冲突且语言不同</b>（hot 是英文）。
 *
 * <p><b>⚠️ 为什么 {@code decideStatus} 恒为 200</b>：
 * B 侧 {@code SetErrorHandler} 的返回值<b>字面量就是</b> {@code http.StatusOK}，
 * 不存在「code → 状态码」的映射 ⇒ 照抄「按 code 决定 401/403/500」的判定
 * 会让「业务码 5001」变成「HTTP 500」，那是新增的偏离。
 */
@RestControllerAdvice
public class HotApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(HotApiExceptionHandler.class);

    // ============== 1. 业务异常：200 + envelope ==============

    /**
     * 业务异常 —— B 侧对应 {@code errors.As(err, &codeErr)} 命中的分支。
     * <b>HTTP 恒 200</b>，{@code data} 恒 {@code null}。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:81-84}。
     *
     * @param e 业务异常
     * @return 200 + {@code {code,msg,data:null}} 信封
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse> onBusinessException(BusinessException e) {
        return envelope(e.getCode(), bizMsgOf(e));
    }

    /**
     * 取<b>业务文案</b>填信封 —— 对齐 B 侧 {@code hotcenter.go:53-66} 的<b>逐字段复制</b>：
     * <pre>
     *   code = codeErr.Code        // 5001
     *   msg  = codeErr.Msg         // "internal server error"   ← 纯文案，**不是** Error() 整串
     * </pre>
     *
     * <p>⚠️ {@link CodeError} 把 {@code getMessage()} 覆写成了日志用的整串
     * ⇒ 这里必须走 {@link CodeError#getBizMsg()} 拿纯文案；
     * 用 {@code getMessage()} 会把 {@code "ErrCode:5001, Errmsg:internal server error"}
     * 写进<b>响应体</b> ⇒ 那是<b>制造新漂移</b>。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:98-100}。
     *
     * @param e 业务异常
     * @return 纯业务文案
     */
    private static String bizMsgOf(BusinessException e) {
        return (e instanceof CodeError codeErr) ? codeErr.getBizMsg() : e.getMessage();
    }

    // ============== 2. 解析层失败：一律 5001 + 200 ==============

    /**
     * query / path 标量转换失败 —— B 侧对应 {@code httpx.Parse} 返回错误。
     *
     * <p>B 侧的解析错误不是 {@code *hotcommon.CodeError} ⇒ {@code SetErrorHandler} 取默认 5001
     * ⇒ <b>HTTP 200 + {code:5001}</b>，而不是 400。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:109-113}。
     *
     * @param e 标量转换失败异常
     * @return 200 + 5001 信封
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        LOG.debug("hot-api query parse failed: {}", e.getMessage());
        return serverCommon();
    }

    /**
     * <b>【共享层闸门 · 新增分支】</b>共享层的 form 值上限闸门在 {@code envelope} 模式下
     * 抛出的异常 ⇒ 必须渲染成<b>本域自己的</b> 5001 文案。
     *
     * <p>⚠️ {@link LimitExceededException} <b>刻意不是</b> {@link BusinessException}
     * ⇒ 它<b>不会</b>被上一条分支接住 ⇒ 必须单列（否则会落进「未知异常」的兜底路径）。
     * <b>wire 形状与 B 侧完全一致</b>（B 侧的 hot-api 用自己的 {@code parseFailure} 抛
     * {@code BusinessException(5001, "internal server error")}）⇒ 本分支只是显式化。
     *
     * @param e form 值超限异常
     * @return 200 + 5001 信封
     */
    @ExceptionHandler(LimitExceededException.class)
    public ResponseEntity<ApiResponse> onLimitExceeded(LimitExceededException e) {
        LOG.debug("hot-api form value limit exceeded: {}", e.getMessage());
        return serverCommon();
    }

    // ============== 3. 框架级错误：保留 Spring / go-zero 语义 ==============

    /**
     * 404 —— B 侧 {@code router/patrouter.go:73} → {@code http.NotFound}：
     * {@code 404 page not found\n} + {@code Content-Type: text/plain; charset=utf-8}
     * + {@code X-Content-Type-Options: nosniff}。<b>无信封</b>。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:135-138}
     * （body / Content-Type / nosniff 全部由共享实现
     * {@link RouterErrorResponses#notFound(String)} 给出，逐字节证据见该类 javadoc）。
     *
     * @param e 资源未找到异常
     * @return 404 + B 侧精确形状的裸响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<byte[]> onNotFound(NoResourceFoundException e) {
        return RouterErrorResponses.notFound(e.getResourcePath());
    }

    /**
     * 405 —— B 侧 {@code router/patrouter.go:79-82}：裸状态码 + {@code Allow}，<b>无 body</b>。
     *
     * <p>本域唯一路由是 {@code GET /hot/v1/articles}，所以除该路径外的其它方法都是 405。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:146-149}。
     *
     * @param e 方法不支持异常
     * @return 405 + 空 body（带 Allow 头）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> onMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return RouterErrorResponses.methodNotAllowed(List.of("GET"));
    }

    // ============== 4. 致命错误 / 兜底 ==============

    /**
     * {@link FatalError} 原样抛出，绝不转 envelope。
     *
     * <p>⚠️ <b>本域当前无触发点</b>（hot 域不调 {@code logFatal}）⇒ 本分支<b>不改变</b>任何可观察结果；
     * 但必须有 ⇒ 否则共享层的 fatal 一旦真的发生，会被兜底分支改写成 200 + 5001
     * ⇒ 「fatal 后必须停止接流量」的硬契约就丢了。
     *
     * @param e 致命错误
     */
    @ExceptionHandler(FatalError.class)
    public void onFatal(FatalError e) {
        throw e;
    }

    /**
     * 未捕获异常的兜底 —— B 侧 go-zero 的 {@code Recover} 中间件捕获后同样交给
     * {@code SetErrorHandler}，产出 200 + 5001。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:124-128}。
     *
     * @param e 任意异常
     * @return 200 + 5001 信封
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> onAnyException(Exception e) {
        if (e instanceof FatalError fatal) {
            throw fatal;
        }
        LOG.warn("hot-api unhandled exception: {}", e.toString());
        return serverCommon();
    }

    // ============== 工具 ==============

    /**
     * B 侧 {@code SetErrorHandler} 的默认分支：{@code code=5001} + {@code Data=nil}。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:152-155}。
     */
    private ResponseEntity<ApiResponse> serverCommon() {
        int code = ErrCodes.ERROR_SERVER_COMMON.getCode();
        return envelope(code, ErrMsg.getMsg(code));
    }

    /**
     * 业务码分支：{@code code}/{@code msg} 取自异常，{@code data} 恒 null。
     *
     * <p><b>B 出处</b>：{@code .../advice/GlobalExceptionHandler.java:158-160}。
     */
    private ResponseEntity<ApiResponse> envelope(int code, String msg) {
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse(code, msg, null));
    }
}
