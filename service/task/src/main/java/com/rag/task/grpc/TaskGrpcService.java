package com.rag.task.grpc;

import com.rag.proto.task.GetTaskReq;
import com.rag.proto.task.GetTaskResp;
import com.rag.proto.task.TaskServiceGrpc;
import com.rag.task.domain.service.TaskDomainService;
import com.rag.task.service.TaskApiSupport;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p><b>{@code TaskService} 的 gRPC 入口</b> —— 对应 Go 端
 * {@code internal/server/task_service_server.go}。</p>
 *
 * <h2>⚠️ Go 的 server 层没有任何逻辑</h2>
 * <p>Go 的 {@code task_service_server.go:26-29} 只有「构造 logic 并转发」，
 * 全部业务在 {@code internal/logic/*}。本类保持同样的分层：
 * 薄转发 + <b>唯一的错误转换点</b>。</p>
 *
 * <h2>错误出口：两类异常两个去向</h2>
 * <table border="1">
 *   <caption>Java → gRPC 的映射依据</caption>
 *   <tr><th>Go 侧写法</th><th>grpc-go 产出</th><th>Java 侧</th></tr>
 *   <tr><td>{@code status.Error(codes.InvalidArgument, "user_id invalid")}
 *       （get_task_logic.go:44）</td>
 *       <td>{@code INVALID_ARGUMENT} + 原文 description</td>
 *       <td>领域层抛 {@link StatusRuntimeException} ⇒ <b>透传</b></td></tr>
 *   <tr><td>{@code return nil, err}（裸 error，get_task_logic.go:59）</td>
 *       <td>{@code UNKNOWN} + {@code err.Error()} 作 description</td>
 *       <td>领域层抛任意 RuntimeException ⇒ 兜底翻成 {@code UNKNOWN}</td></tr>
 * </table>
 *
 * <h3>⚠️ {@code StatusRuntimeException} 必须透传，不能改写成 UNKNOWN</h3>
 * <p>grpc-go 的 {@code status.FromError} 见到已是 status 的 error 会<b>保留</b>它的 code。
 * 若本 catch 把 {@link StatusRuntimeException} 当成普通 RuntimeException 改写成 UNKNOWN，
 * {@code INVALID_ARGUMENT} 会退化成 {@code UNKNOWN} ⇒ 调用方
 * （Go {@code task-api} 的 {@code gettasklogic.go:39}）拿到的错误语义变了。</p>
 *
 * <h3>⚠️ task 域<b>没有 -common 模块</b></h3>
 * <p>Go 侧 {@code service/task} 下只有 api 与 rpc，所以本模块不需要 like/message 那种
 * 业务码 ↔ gRPC code 转换类 —— 与 article-rpc 同款。</p>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>类名</b>：B 侧 {@code TaskServiceGrpcImpl} → C 侧 {@code TaskGrpcService}；
 *             <b>落点</b>：{@code rpc/service/impl/} → {@code grpc/}（D7 + 映射表登记）；</li>
 *         <li><b>pb 包名</b>：{@code com.rag.task.task.proto} → {@code com.rag.proto.task}
 *             （D7）⇒ <b>proto package 仍是 {@code task}</b> ⇒ gRPC 服务全名
 *             {@code task.TaskService} <b>逐字未变</b>（wire 契约不变）；</li>
 *         <li><b>去掉 {@code @Component}</b>：devh 的 {@code @GrpcService} <b>本身</b>就带
 *             {@code @Service} + {@code @Bean} ⇒ 再叠一个 {@code @Component} 是<b>重复声明</b>
 *             （与 like 域同款处理）；</li>
 *         <li><b>兜底翻译改用共享模板</b>：B 侧在本类里内联写
 *             {@code new StatusRuntimeException(Status.UNKNOWN.withDescription(...))}
 *             ⇒ C 侧改调 {@link TaskApiSupport#asGrpcStatus(RuntimeException)}
 *             （<b>HTTP 路径共用同一份</b>）⇒ 见 {@link TaskApiSupport} 的
 *             「为什么翻译模板必须共享」。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 两条错误出口（status 透传 / 兜底 UNKNOWN）、
 *       {@code onError} 而非让异常逃逸、不捕获 {@link Error}，逐条照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-rpc/src/main/java/com/rag/task/task/rpc/service/impl/TaskServiceGrpcImpl.java:48-58（类声明 + 构造）
 * B 出处：…/service/impl/TaskServiceGrpcImpl.java:60-63（getTask 转发）
 * B 出处：…/service/impl/TaskServiceGrpcImpl.java:76-90（invoke 模板：两条错误出口）
 *       </pre></li>
 * </ol>
 */
@GrpcService
public class TaskGrpcService extends TaskServiceGrpc.TaskServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(TaskGrpcService.class);

    private final TaskDomainService domainService;

    public TaskGrpcService(TaskDomainService domainService) {
        this.domainService = domainService;
    }

    @Override
    public void getTask(GetTaskReq request, StreamObserver<GetTaskResp> responseObserver) {
        invoke(() -> domainService.getTask(request), responseObserver);
    }

    /**
     * 统一出口模板。
     *
     * <p><b>必须用 {@code observer.onError(...)} 而不是让异常逃逸</b>：
     * 异常若逃出 listener 回调，grpc-java 会把它当成<b>传输层内部错误</b>，
     * description 被替换成 {@code "Application error processing RPC"}，
     * 错误码与原始文案当场全丢（article-rpc 的同款实测见其类注释）。</p>
     *
     * <p>⚠️ {@link Error} 不捕获：让容器线程的默认处理接管，
     * 与 grpc-java 默认行为一致（不把 {@code OutOfMemoryError} 当成业务错误）。</p>
     */
    private static <Resp> void invoke(Supplier<Resp> handler, StreamObserver<Resp> observer) {
        try {
            Resp resp = handler.get();
            observer.onNext(resp);
            observer.onCompleted();
        } catch (StatusRuntimeException e) {
            // 🔴 必须透传（见类注释）：INVALID_ARGUMENT 等已定好的 code 不能被抹成 UNKNOWN
            observer.onError(e);
        } catch (RuntimeException e) {
            // Go 侧 `return nil, err`（裸 error）⇒ grpc-go 转成 UNKNOWN
            log.error("task-rpc handler failed", e);
            // ⚠️ 与 HTTP 路径共用同一份翻译模板（TaskApiSupport#asGrpcStatus）
            observer.onError(TaskApiSupport.asGrpcStatus(e));
        }
    }
}
