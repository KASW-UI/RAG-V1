package com.rag.common.exception;

/**
 * <p><b>Go 出处</b>：{@code service/common/response/response.go:3-7}（信封三字段 code/msg/data）
 * ＋ {@code service/message/common/errmsg.go:99}（{@code func BizCodeFromError(err error) int} ⇒ 业务码取值口径）。
 * <p><b>表达层说明（AGENTS.md §3.2）</b>：Go 侧用<b>双返回</b>（{@code return nil, errmsg.XXX}）表达失败，
 * Java 侧用<b>异常</b>表达 ⇒ 这是<b>表达层 idiomatization</b>，<b>可观察形状等价</b>（都最终变成 code/msg/data 三字段信封）。
 * RAG 通用业务异常 —— Service 层显式抛，Controller 层 GlobalExceptionHandler 统一捕获并包成 envelope。
 *
 * <p>对应 Go 端 logic 的 {@code return LogicResult.fail(errmsg.XXX)} 双返回模型：
 * <pre>
 *   Go: return nil, errmsg.ERROR_USER_EXIST
 *   Java: throw new BusinessException(1001, "用户名已存在")
 * </pre>
 *
 * <p>框架职责:
 * <ul>
 *   <li>{@code code} 直接给业务码（来自 {@code com.rag.users.common.errmsg.ErrorCode} 的 int 值），
 *       {@code GlobalExceptionHandler} 把异常包成 {@code Response(code, msg, data=null)}</li>
 *   <li>HTTP 状态码由 {@code GlobalExceptionHandler} 按 code 决定（401 / 403 / 500 / 200）</li>
 *   <li>不带 trace / 不带 metrics（metrics 由 GlobalExceptionHandler 统一处理）</li>
 * </ul>
 *
 * <p><b>设计注</b>：本类只接 {@code int} 业务码 + {@code String} 提示语，<b>不</b>耦合
 * {@code com.rag.users.common.errmsg.ErrorCode} 枚举 —— 因为 {@code common} 模块在 maven
 * 模块树里是下层，被 {@code users-common} 反向依赖；这里持引用会形成循环依赖。
 * 调用方传 {@code ErrorCode.ERROR_USER_EXIST.getCode()} + {@code ErrorCode.ERROR_USER_EXIST.getMsg()}
 * 即可。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/exception/BusinessException.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
