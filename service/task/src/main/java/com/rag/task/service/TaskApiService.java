package com.rag.task.service;

import com.rag.proto.task.GetTaskResp;
import com.rag.task.domain.service.TaskDomainService;
import com.rag.task.dto.task.GetTaskReq;
import com.rag.task.dto.task.TaskInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>本域全部 HTTP 业务</b> —— 对应 Go {@code api/internal/logic/task/gettasklogic.go:30-56}。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>类名</b>：B 侧 {@code TaskGetService} → 本仓 {@code TaskApiService}
 *             （映射表 §1.1 已批准的 1:N 拆分）；</li>
 *         <li><b>调用方式</b>：B 侧是<b>跨进程 gRPC</b>（{@code @GrpcClient("task-rpc")}），
 *             C 侧是<b>域内直调</b> {@link TaskDomainService}（D1/D2：api 与 rpc 合并为一个进程）⇒
 *             ⇒ <b>没有</b> {@code client/} 包、<b>没有</b> {@code TaskRpcClient}；</li>
 *         <li><b>错误翻译的位置</b>：B 侧由 rpc 的 gRPC 层翻译（{@code TaskServiceGrpcImpl#invoke}），
 *             C 侧 HTTP 路径绕过 gRPC 层 ⇒ 必须<b>显式复用</b>同一份翻译模板
 *             （{@link TaskApiSupport#asGrpcStatus}）—— 否则同一个 DB 故障会
 *             「B 500 / C 400」。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 见下方逐段对照。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/service/TaskGetService.java:56-60（类声明 + 构造器）
 * B 出处：…/service/TaskGetService.java:73-84（getTask 主体：uid 校验 → RPC → 投影 → 包装）
 *       </pre></li>
 * </ol>
 *
 * <h2>Go 的这个 logic 是<b>纯转发 + 字段投影</b>：无状态转换、无缓存、无 DB、无分支</h2>
 * <pre>
 *   func (l *GetTaskLogic) GetTask(req *types.GetTaskReq) (resp *types.GetTaskResp, err error) {
 *       uid, err := req.Userid.Int64()                              // :31
 *       if err != nil { return nil, err }                            // :32-34
 *
 *       rpcResp, err := l.svcCtx.TaskCli.GetTask(l.ctx, &amp;taskpb.GetTaskReq{UserId: uid})   // :36
 *       if err != nil { return nil, err }                            // :39-41
 *
 *       out := make([]types.TaskInfo, 0, len(rpcResp.Task))          // :43
 *       for _, t := range rpcResp.Task {                             // :44
 *           out = append(out, types.TaskInfo{                        // :45-51
 *               Name: t.Name, Desc: t.Desc, Task_id: t.TaskId,
 *               Completion_progress: t.CompletionProgress,
 *               Required_progress:   t.RequiredProgress,
 *           })
 *       }
 *       return &amp;types.GetTaskResp{Tasks: out}, nil                // :53-55
 *   }
 * </pre>
 *
 * <h2>⚠️ 本类不做「更聪明的」事——三处最容易顺手加、但都是漂移的地方</h2>
 * <ol>
 *   <li><b>不按用户过滤任务</b>：返回的是领域服务给的<b>全部</b> task（Go {@code :44} 不带任何条件）。
 *       「只回未完成的任务」会凭空改变响应长度。</li>
 *   <li><b>不改写任何字段</b>：{@code completionProgress} / {@code requiredProgress} 都<b>原样透传</b>。
 *       在 api 侧做「进度封顶」「按百分比换算」都是新增业务规则。</li>
 *   <li><b>不吞异常</b>：Go 的 {@code err != nil} 是「返回 error」，
 *       落到 HTTP 上就是 {@code errcode.CodeFromGrpcError} 的状态码映射。
 *       在这里包一层 {@code try/catch} 换成别的错误类型，那条映射链就断了。</li>
 * </ol>
 */
@Service
public class TaskApiService {

    private final TaskDomainService taskDomainService;

    public TaskApiService(TaskDomainService taskDomainService) {
        this.taskDomainService = taskDomainService;
    }

    /**
     * {@code POST /taskcenter/v1/task/get} 的唯一用例。
     *
     * @param req 已完成①（string|number 归一化）的请求体；②（正整数校验）在第 1 行做
     * @return 裸响应体（<b>无信封</b>）；{@code tasks} 永远是数组
     * @throws IllegalArgumentException       {@code id} 为空 / 非正 / 非法字面量
     *                                       ⇒ advice 映成 {@code 400 "user_id invalid"}
     * @throws io.grpc.StatusRuntimeException 领域层失败（<b>原样透传</b>）
     *                                       ⇒ advice 按 gRPC 状态码映射 HTTP 状态
     */
    public com.rag.task.dto.task.GetTaskResp getTask(GetTaskReq req) {
        long userId = req.userIdOrThrow(); // Go gettasklogic.go:31-34

        GetTaskResp rpcResp;
        try {
            // Go :36-41 —— C 侧是域内直调（D2），不再是跨进程 gRPC。
            rpcResp = taskDomainService.getTask(TaskApiSupport.protoGetTaskReq(userId));
        } catch (RuntimeException e) {
            // 🔴 翻译模板必须与 gRPC 层共用（详见 TaskApiSupport 的类注释「为什么翻译模板必须共享」）：
            //    不翻译 ⇒ DB 故障会落到 HTTP 兜底分支（400）⇒ 与 B 侧的 500 不一致。
            throw TaskApiSupport.asGrpcStatus(e);
        }

        // Go 侧 rpcResp 为 nil 时会在 `range rpcResp.Task` 处 panic，被 go-zero 的 Recover
        // 中间件接住后同样走 httpx.ErrorCtx ⇒ 仍是 400 text/plain。这里显式抛同一个形状的
        // 异常，而不是靠 NPE 偶然落到兜底分支。
        if (rpcResp == null) {
            throw new IllegalStateException("task-rpc returned a nil GetTaskResp");
        }

        // Go :43 的 make(..., 0, n) ⇒ 预分配 + 永不产出 null
        List<TaskInfo> out = new ArrayList<>(rpcResp.getTaskCount());
        for (com.rag.proto.task.Task t : rpcResp.getTaskList()) { // :44-52
            out.add(TaskApiSupport.toTaskInfo(t));
        }
        return new com.rag.task.dto.task.GetTaskResp(out);
    }
}
