package com.rag.task.controller;

import com.rag.task.dto.task.GetTaskReq;
import com.rag.task.dto.task.GetTaskResp;
import com.rag.task.service.TaskApiService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p><b>单端点 {@code POST /taskcenter/v1/task/get}</b> —— 对应 Go
 * {@code api/internal/handler/routes.go:15-25} 的<b>唯一</b>路由表：
 *
 * <pre>
 *   server.AddRoutes(
 *       []rest.Route{
 *           {
 *               Method:  http.MethodPost,
 *               Path:    "/task/get",
 *               Handler: task.GetTaskHandler(serverCtx),
 *           },
 *       },
 *       rest.WithPrefix("/taskcenter/v1"),
 *   )
 * </pre>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li>包名压平（{@code com.rag.task.task.api.controller} → {@code com.rag.task.controller}，D7）；</li>
 *         <li>依赖由 B 侧的 {@code TaskGetService} 改为本仓的 {@link TaskApiService}
 *             （<b>类名</b>变更，职责不变 ⇒ 映射表 §1.1 已批准的 1:N 拆分）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 路由前缀、路径段、method、
 *       「不包信封」、「不设 {@code consumes}」、「不鉴权」全部照抄（逐条见下）。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/controller/TaskController.java:59-61（类声明 + 前缀）
 * B 出处：…/controller/TaskController.java:87-90（@PostMapping("/task/get") + 方法体）
 *       </pre></li>
 * </ol>
 *
 * <h2>⚠️ 前缀是 {@code /taskcenter/v1}，<b>不要</b>"统一"成 {@code /task/v1}</h2>
 * RAG 已落地的 6 个 api 前缀是 {@code /user/v1}、{@code /admin/v1}、{@code /message/v1}、
 * {@code /like/v1}、{@code /article/v1}、{@code /hot/v1}，本模块的 {@code taskcenter}
 * 是唯一一个英文复合词。看着不一致，但它是 <b>L1 wire 契约</b>——
 * 改掉它等于让所有已对接的客户端 404。
 *
 * <h2>⚠️ 零鉴权：本类<b>刻意没有</b>任何 interceptor / SecurityFilter</h2>
 * Go 侧的 {@code AddRoutes} 只有 {@code rest.WithPrefix}，<b>没有</b> {@code rest.WithJwt}、
 * {@code rest.WithCors}、{@code rest.WithMaxBytes}。
 * {@code etc/task.yaml:9-11} 的 {@code Auth{AccessSecret, AccessExpire}} 是<b>死配置</b>
 * （{@code internal/config/config.go} 里声明了，但 {@code servicecontext.go} 与
 * {@code gettasklogic.go} 都没读它）。
 * ⇒ {@code POST /taskcenter/v1/task/get} 是<b>公开端点</b>，用户身份来自请求体的
 * {@code id}（见 {@link GetTaskReq}）。引入 {@code JwtAuthInterceptor} 会把「公开读」
 * 变成「必须带 token」，那是 L2 级行为变更，<b>不是</b>「顺手补一个缺失的安全措施」。
 * ⚠️ 该端点因此存在<b>水平越权面</b>（任何调用方都能查任意 uid 的任务进度）——
 * 这是 Go 的既定行为，按原样保留；若要补鉴权属于<b>新增行为</b>，需单独拍板。
 * ⇒ C 侧对应落点：{@code config/WebMvcConfig}（<b>不</b>注册任何拦截器）。
 *
 * <h2>⚠️ 返回值<b>不包信封</b></h2>
 * Go 未调 {@code httpx.SetOkHandler} ⇒ {@code OkJsonCtx} 直接写原始对象。
 * ⇒ 本方法直接返回 {@link GetTaskResp}，且本模块<b>不注册</b>任何 ResponseAdvice 包装。
 *
 * <h2>⚠️ {@code @RequestBody(required = true)} 是默认值，但必须显式保留</h2>
 * Go 的 {@code gettaskhandler.go:21-24}：{@code decoder.Decode(&req)} 对<b>空 body</b>
 * 返回 {@code io.EOF} ⇒ 进错误路径（400）。{@code required = true} 让空 body 在 Spring 侧
 * 也以「读不到 body」失败（{@code HttpRequestBodyRequiredException}，
 * 是 {@code HttpMessageNotReadableException} 的子类）⇒ advice 映成 400 text/plain。
 * <p>而 Go <b>不检查 Content-Type</b>（裸 {@code json.NewDecoder(r.Body)}，无
 * {@code withJsonBody} 判据）。这件事由 {@code config/TaskApiJsonBodyConfig} 承担，
 * 不要在这里写 {@code consumes = "application/json"}——那会让 Go 能接受的请求变成 415。
 */
@RestController
@RequestMapping("/taskcenter/v1")
public class TaskController {

    private final TaskApiService taskApiService;

    public TaskController(TaskApiService taskApiService) {
        this.taskApiService = taskApiService;
    }

    /**
     * {@code POST /taskcenter/v1/task/get} —— Go {@code gettaskhandler.go:16-33} +
     * {@code gettasklogic.go:30-56}。
     *
     * <p>Go 的 handler 是「薄适配层」：Decode → Logic → {@code httpx.OkJsonCtx} /
     * {@code httpx.ErrorCtx}，<b>本身不做任何校验</b>。本类保持同样形状：
     * 只做「反序列化 + 委托」，校验全在 {@link GetTaskReq} 与 {@link TaskApiService} 里。
     *
     * <p>⚠️ <b>path 里那个 {@code /task} 段不能省</b>：Go 的路由表里 {@code Path} 写的是
     * {@code "/task/get"}（{@code routes.go:20}），前缀只提供 {@code /taskcenter/v1}。
     * 写成 {@code "/get"} 会得到 {@code /taskcenter/v1/get}，
     * <b>少一段</b> ⇒ 全部客户端 404。
     *
     * @param req 已完成①（string|number 归一化）的请求体；②（正整数校验）在 Service 的第 1 行做
     * @return 裸响应体 {@code {"tasks":[...]}}（<b>无信封</b>）
     */
    @PostMapping("/task/get")
    public GetTaskResp getTask(@RequestBody GetTaskReq req) {
        return taskApiService.getTask(req);
    }
}
