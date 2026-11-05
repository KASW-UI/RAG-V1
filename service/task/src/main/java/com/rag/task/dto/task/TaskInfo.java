package com.rag.task.dto.task;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>单条任务</b> —— 对应 Go {@code api/internal/types/types.go:60-66}：
 *
 * <pre>
 *   type TaskInfo struct {
 *       Name                string `json:"name"`
 *       Desc                string `json:"desc,optional"`
 *       Task_id             int64  `json:"taskId"`
 *       Completion_progress int64  `json:"completionProgress"`
 *       Required_progress   int64  `json:"requiredProgress"`
 *   }
 * </pre>
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> ——
 *       <ul>
 *         <li><b>可变 POJO → immutable record</b>（D5 ＋ <b>A9</b>）⇒
 *             B 侧是「5 个字段 + 5 对 getter/setter + 2 个构造器」的类，本类是单行 record；</li>
 *         <li>包名压平 + 按业务对象拆子包（{@code dto/task/}，D7 ＋ D11）；</li>
 *         <li>字段名与 JSON key 的对应关系由 <b>record 组件名</b> ＋ 显式
 *             {@link JsonProperty} 双写（不再是「字段 + 注解」的组合）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 5 个 JSON key、<b>键序</b>、
 *       {@code int64 ⇒ long} 的零值语义（{@code 0} 而不是 {@code null}）、
 *       「{@code desc} 绝不省略」这条硬约束，全部照抄（逐条见下）。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/dto/TaskInfo.java:37（类声明）
 * B 出处：…/dto/TaskInfo.java:44-45（name）
 * B 出处：…/dto/TaskInfo.java:48-49（desc）
 * B 出处：…/dto/TaskInfo.java:52-53（taskId）
 * B 出处：…/dto/TaskInfo.java:56-57（completionProgress）
 * B 出处：…/dto/TaskInfo.java:60-61（requiredProgress）
 * B 出处：…/dto/TaskInfo.java:63-68（构造器参数顺序 = 序列化键序）
 *       </pre></li>
 * </ol>
 *
 * <h2>⚠️ Go 的字段名是 snake，JSON key 是 camel —— 两个都要对上</h2>
 * {@code Task_id} / {@code Completion_progress} 是 goctl 生成的 Go 字段名，
 * 而 tag 上写的是 camelCase。落到 wire 上只有 camelCase 可见，但<b>字段名也按 Go 保留</b>，
 * 便于逐行对照 {@code types.go}。
 *
 * <h2>⚠️ {@code desc} 绝不能加 {@code NON_EMPTY}</h2>
 * tag 里的 {@code ,optional} 是 goctl 的标记，<b>不是</b> {@code encoding/json} 的
 * {@code omitempty}：{@code encoding/json} 不认识 {@code optional} 这个选项，
 * 于是空 {@code Desc} 照样输出 {@code "desc":""}。
 * <p>本模块走的是裸 {@code encoding/json}（{@code gettaskhandler.go:19}），没有 go-zero 的
 * {@code httpx.Parse} 参与 ⇒ <b>这个 {@code ,optional} 是彻底的死标记</b>。
 * 加了 {@code JsonInclude.NON_EMPTY} 会让该键整个消失，客户端按 {@code desc} 取值会拿到
 * {@code undefined} 而不是空串。
 *
 * <h2>⚠️ 字段顺序即字节顺序</h2>
 * Go 的 {@code encoding/json} 按<b>结构体字段声明顺序</b>输出。Jackson 对 record 的
 * creator 属性同样按<b>声明顺序</b>输出；本类的声明顺序与 {@code types.go:60-66} 逐行一致，
 * 因此 {@code {"name":..,"desc":..,"taskId":..,"completionProgress":..,"requiredProgress":..}}
 * 的键序两侧相同。
 *
 * <h2>为什么用 {@code long} 而不是 {@code Long}</h2>
 * Go 是 {@code int64}，零值必须是数字 {@code 0} 而不是 {@code null}。
 * 用包装类型一旦漏赋值就会输出 {@code null}，那是新增行为。
 */
public record TaskInfo(
        @JsonProperty("name") String name,
        @JsonProperty("desc") String desc,
        @JsonProperty("taskId") long taskId,
        @JsonProperty("completionProgress") long completionProgress,
        @JsonProperty("requiredProgress") long requiredProgress) {
}
