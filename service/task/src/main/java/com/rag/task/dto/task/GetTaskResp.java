package com.rag.task.dto.task;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * <p><b>响应体</b> —— 对应 Go {@code api/internal/types/types.go:56-58}：
 *
 * <pre>
 *   type GetTaskResp struct {
 *       Tasks []TaskInfo `json:"tasks"`
 *   }
 * </pre>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>可变 POJO → immutable record</b>（D5 ＋ <b>A9</b>）；</li>
 *         <li><b>「null 兜底」从 setter 搬进 compact 构造器</b> ⇒ 语义等价
 *             （B 侧两个构造器都做了同一件事，见下）；</li>
 *         <li>包名压平 + 业务对象子包（{@code dto/task/}，D7 ＋ D11）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 成功响应<b>无信封</b>、
 *       {@code tasks} <b>永远是数组、永不为 null</b>、空列表输出 {@code []}
 *       这三条全部照抄（逐条见下）。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/dto/GetTaskResp.java:28（类声明）
 * B 出处：…/dto/GetTaskResp.java:31-32（默认构造器把 tasks 置成 List.of()）
 * B 出处：…/dto/GetTaskResp.java:34-37（带参构造器的 null 兜底）
 * B 出处：…/dto/GetTaskResp.java:39-42（setter 的 null 兜底）
 *       </pre></li>
 * </ol>
 *
 * <h2>⚠️ 成功响应<b>没有</b> {@code {code,msg,data}} 信封（这一条最容易抄错）</h2>
 * Go 的 {@code api/task.go} 全文 37 行，<b>没有调用 {@code httpx.SetOkHandler}</b>。
 * 于是在 {@code rest/httpx/responses.go:56-64}：
 * <pre>
 *   func OkJsonCtx(ctx, w, v) {
 *       ...
 *       if handlerCtx != nil { v = handlerCtx(ctx, v) }   // ← handlerCtx 为 nil，不包装
 *       WriteJsonCtx(ctx, w, http.StatusOK, v)          // :63 直接写原始对象
 *   }
 * </pre>
 * ⇒ 成功响应体就是 {@code {"tasks":[...]}}。
 * <p>对比：{@code like/api/likecenter.go:41-47} <b>注册了</b> okHandler（返回信封），
 * {@code hot/api/hotcenter.go:53}、{@code message/api/messagecenter.go:53} 同理。
 * <b>把那几个域的 ResponseAdvice 照抄到本模块就是漂移</b>（AGENTS.md §3.1
 * 「不要拿另一个域的实测结论当对照基准」）。
 * ⇒ 本模块<b>不</b>加信封 —— 落地见 {@code advice/TaskApiResponseAdvice}。
 *
 * <h2>⚠️ {@code tasks} 永远是数组，绝不为 {@code null}</h2>
 * Go 侧 {@code gettasklogic.go:43} 是
 * {@code out := make([]types.TaskInfo, 0, len(rpcResp.Task))} ——
 * <b>无条件</b>创建、且长度可能为 0 但<b>非 nil</b> ⇒ 输出 {@code "tasks":[]}。
 * <p>proto 的 {@code repeated} 在 Java 侧天然返回空 List（不会为 null），
 * 但本类在构造时仍<b>再兜一层</b>：任何一条路径都不允许产出 {@code {"tasks":null}}。
 */
public record GetTaskResp(@JsonProperty("tasks") List<TaskInfo> tasks) {

    /**
     * canonical 构造器 —— <b>「tasks 永不为 null」的兜底落点</b>
     * （对应 B 侧两个构造器与 setter 里的三处 null 兜底，逐条等价）。
     *
     * @param tasks 任务列表；{@code null} ⇒ 落成空列表（<b>不是</b> {@code null}）
     */
    public GetTaskResp {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
