package com.rag.task.service;

import com.rag.proto.task.GetTaskReq;
import com.rag.proto.task.Task;
import com.rag.task.dto.task.TaskInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * <p><b>API 层的公共支撑</b> —— 三件事：proto 请求的构造、proto → DTO 的投影、
 * 以及「非 gRPC 异常 → gRPC 状态」的<b>传输层翻译模板</b>。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li>B 侧 {@code TaskGetService} 里那三段「私有方法」（proto 请求构造、proto → DTO 投影）
 *             <b>被本类接住</b>（映射表 §1.1 已批准的 1:N 拆分「Service ＋ Support」）；</li>
 *         <li>「非 gRPC 异常 → UNKNOWN」的翻译模板：B 侧在 <b>rpc 的 gRPC 层</b>
 *             （{@code TaskServiceGrpcImpl#invoke}）做；C 侧 api 与 rpc <b>合并为一个进程</b> ⇒
 *             HTTP 路径<b>绕过</b>了 gRPC 层 ⇒ 该模板必须被<b>显式复用</b>，
 *             否则 HTTP 面的错误状态码会与 B 侧不同（见下方「为什么翻译模板必须共享」）；</li>
 *         <li>包名压平（{@code com.rag.task.task.api.service} → {@code com.rag.task.service}，D7）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 投影的 5 个字段、proto 请求的
 *       {@code user_id=1} 字段号、翻译模板的「{@code StatusRuntimeException} 透传 /
 *       其它 RuntimeException → UNKNOWN」两条分支，全部照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/service/TaskGetService.java:73-84（proto 请求 + 投影）
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/impl/TaskServiceGrpcImpl.java:38-49（invoke 模板）
 *       </pre></li>
 * </ol>
 *
 * <h2>🔴 为什么「翻译模板」必须被 HTTP 路径复用（不是可选的整洁）</h2>
 * <p>B 侧 api → rpc 是<b>跨进程 gRPC</b> ⇒ rpc 侧的 DB 异常会被
 * {@code TaskServiceGrpcImpl#invoke} 翻成 {@code Status.UNKNOWN}
 * ⇒ api 侧收到 {@code StatusRuntimeException(UNKNOWN)} ⇒
 * {@code advice} 按 {@code errcode.CodeFromGrpcError} 映成 <b>HTTP 500</b>。
 * <p>C 侧 api 与 rpc <b>同进程</b>（D1）⇒ HTTP 路径<b>直接</b>调用领域服务 ⇒
 * 领域服务抛出的 {@code DataAccessException} 会<b>原样</b>冒到 HTTP 的兜底分支
 * ⇒ 而 B 侧 api 的兜底是 <b>400</b>（{@code httpx/responses.go:155}）
 * ⇒ ⇒ <b>同一个 DB 故障，B 侧 500 / C 侧 400</b> ⇒ <b>可观察的 L1 漂移</b>。
 * <p>⇒ 故本类提供 {@link #asGrpcStatus(RuntimeException)}，
 * 由 {@link TaskApiService}（HTTP 路径）与
 * {@code grpc/TaskGrpcService}（gRPC 路径）<b>共用同一份</b> ⇒ 两侧状态码一致。
 *
 * <h2>⚠️ 为什么 proto → DTO 的投影要单独成类（B 侧是私有方法）</h2>
 * <p>B 侧注释（{@code TaskGetService.java:57-61}）明确说：单一消费者、5 个字段、
 * 没有第二个调用方 ⇒ 开一个类/一个包都是净负担。
 * <p>C 侧仍然拆出来，是因为<b>文件树已把 {@code service/TaskApiSupport.java} 定为本域落点</b>
 * （映射表 §1.1 的 1:N 拆分）⇒ 拆分是<b>纯表达层</b>的重排，
 * <b>不改变任何行为</b>（字段、顺序、类型逐条一致）。
 */
public final class TaskApiSupport {

    private TaskApiSupport() {
        // 纯静态工具，禁止实例化
    }

    /**
     * 构造 proto 的 {@code GetTaskReq} —— 对应 Go {@code gettasklogic.go:36}：
     * {@code l.svcCtx.TaskCli.GetTask(l.ctx, &taskpb.GetTaskReq{UserId: uid})}。
     *
     * <p>⚠️ 字段号 {@code user_id = 1}（{@code task.proto:15-17}）是 <b>L1 契约</b>。
     *
     * @param userId 已通过正整数校验的 uid
     * @return proto 请求
     */
    public static GetTaskReq protoGetTaskReq(long userId) {
        return GetTaskReq.newBuilder()
                .setUserId(userId)
                .build();
    }

    /**
     * proto {@code Task} → DTO {@code TaskInfo} —— 对应 Go {@code gettasklogic.go:45-51}：
     *
     * <pre>
     *   out = append(out, types.TaskInfo{
     *       Name: t.Name, Desc: t.Desc, Task_id: t.TaskId,
     *       Completion_progress: t.CompletionProgress,
     *       Required_progress:   t.RequiredProgress,
     *   })
     * </pre>
     *
     * <p>⚠️ <b>五个字段逐条原样透传</b>：不做「进度封顶」「按百分比换算」
     * 「只回未完成的任务」这类 B 侧没有的加工。
     *
     * @param task proto 任务
     * @return DTO 任务
     */
    public static TaskInfo toTaskInfo(Task task) {
        return new TaskInfo(
                task.getName(),
                task.getDesc(),
                task.getTaskId(),
                task.getCompletionProgress(),
                task.getRequiredProgress());
    }

    /**
     * 把「非 gRPC 异常」翻译成 gRPC 状态 —— 对应 Go
     * {@code TaskServiceGrpcImpl#invoke} 的兜底分支：
     *
     * <pre>
     *   } catch (StatusRuntimeException e) {
     *       observer.onError(e)                                  // ← 已定好的 code 透传
     *   } catch (RuntimeException e) {
     *       observer.onError(new StatusRuntimeException(
     *           Status.UNKNOWN.withDescription(e.getMessage()).withCause(e)))
     *   }
     * </pre>
     *
     * <p>⚠️ <b>{@code StatusRuntimeException} 必须透传，不能改写成 UNKNOWN</b>：
     * grpc-go 的 {@code status.FromError} 见到已是 status 的 error 会<b>保留</b>它的 code。
     * 若本方法把它当成普通 RuntimeException 改写成 UNKNOWN，
     * {@code INVALID_ARGUMENT} 会退化成 {@code UNKNOWN} ⇒ 调用方拿到的错误语义变了。
     *
     * @param e 捕获到的异常
     * @return 可直接交给 {@code observer.onError(...)} 的状态异常
     */
    public static StatusRuntimeException asGrpcStatus(RuntimeException e) {
        if (e instanceof StatusRuntimeException sre) {
            return sre;
        }
        return new StatusRuntimeException(
                Status.UNKNOWN.withDescription(e.getMessage()).withCause(e));
    }
}
