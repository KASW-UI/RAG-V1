package com.rag.common.logger;

/**
 * H3 修复：致命错误的专用异常类型。
 *
 * <p><b>为什么需要这个类型</b>
 * <p>Go 版 {@code logger.go:293-296} 在打出 {@code fatal_error} 之后无条件
 * {@code os.Exit(1)} —— 方法不返回，进程立即停止接流量：
 * <pre>
 *   logx.WithContext(ctx).Errorw("fatal_error", fields...)
 *   os.Exit(1)
 * </pre>
 *
 * <p>Java 版此前直接抛普通 {@link IllegalStateException}，导致「fatal 后必须停止」
 * 这个契约降级成一个<b>可以被 catch 掉的普通异常</b>：任何
 * {@code try { logger.logFatal(...) } catch (Exception e) {}} —— 包括 Spring MVC 的
 * {@code @ExceptionHandler(RuntimeException.class)} 全局兜底 —— 都会让进程带着致命状态继续服务。
 * 而当前 user-rpc 的 {@code UserDomainService} 正是通过 LoggerFactoryBean 打这类日志的活跃链路。
 *
 * <p><b>本类的契约（已与用户确认）</b>
 * <p>不恢复 {@code System.exit(1)}，因为它会绕过 Spring 的 graceful shutdown、
 * {@code @PreDestroy} 与 Bean 生命周期回调。改为：
 * <ol>
 *   <li>打出与 Go 完全一致的 {@code fatal_error} 日志（含 event_id / trace_id / file_line /
 *       call_path / call_chain / error_reason）</li>
 *   <li>抛本类型异常，由调用方决定终止方式；Spring Boot 的 {@code main} 未捕获时
 *       JVM 以非零退出码结束，等价于 Go 的 {@code os.Exit(1)}</li>
 *   <li>注册了 {@code GlobalExceptionHandler} 的模块必须<b>显式 rethrow</b> 本类型，
 *       不允许转成业务 envelope</li>
 * </ol>
 *
 * <p><b>等价域收窄（必须如实记录）</b>
 * <p>与 Go 的等价域是「<b>永远</b>终止」；本实现是「<b>仅在无人 catch 时</b>终止」。
 * 这是有意保留的残留差异，理由见上。若后续业务里出现
 * {@code catch (FatalError)} 后继续运行的写法，等价性即被打破 —— 该用例会由
 * {@code FatalErrorNotSwallowedTest} 锁住注册路径。
  *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/logger/FatalError.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
*/
public class FatalError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FatalError(String message, Throwable cause) {
        super(message, cause);
    }
}