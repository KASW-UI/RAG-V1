package com.rag.common.contract;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 共享层的响应信封 —— 对应 B 侧 {@code service/common/src/main/java/com/rag/common/response/Response.java:14}。
 *
 * <p><b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/response/Response.java:16:3-7}
 * （{@code type Response struct { Code int `json:"code"`; Msg string `json:"msg"`; Data interface{} `json:"data"` }}`）
 * ⇒ <b>三个 JSON 键与字段顺序逐条一致</b>（B 的 struct tag 顺序 = Jackson 的字段声明顺序）
 * ⇒ ⇒ <b>语义等价</b>（这是共享层里「逐条等价」的那一个包，见
 * {@code docs/DRIFT/common/alignment-2026-10-23.md} §六「response —— ✅ SEMANTICALLY EQUIVALENT」）。
 *
 * <p><b>⚠️ L1 契约层（三条硬约束 · 动它要走决策流程 · D13「L1 以 B 侧 为准」）</b>：
 * <ol>
 *   <li><b>键名与顺序</b>：{@code code} → {@code msg} → {@code data}，逐条一致 ⇒ 不可调序、不可改名；</li>
 *   <li><b>无 {@code omitempty}</b>：B 侧三个字段<b>都没有</b> {@code omitempty} ⇒
 *       {@code data} 为 null 时<b>仍然出现</b>（{@code "data":null}）⇒ ⇒
 *       Java 侧<b>不得</b>给本类加 {@code @JsonInclude(NON_NULL)} / {@code @JsonInclude(NON_EMPTY)}
 *       ⇒ 否则 {@code data} 键会消失 ⇒ <b>可观察形状不等价</b>；</li>
 *   <li><b>不可用 {@code ProblemDetail} 替换</b>（K-06 已登记降级项）⇒ 本类是<b>唯一</b>信封载体。</li>
 * </ol>
 *
 * <p><b>落点改名说明</b>：RAG 侧类名是 {@code com.rag.common.response.Response} ⇒ RAG-V1 落
 * {@code com.rag.common.contract.ApiResponse}（**包改名** {@code response} → {@code contract}
 * ＋ **类改名** {@code Response} → {@code ApiResponse}）。
 * ⚠️ 改名理由：{@code response} 在本仓已被 {@code web/} 与各域 {@code advice/} 大量使用，
 * 而本类是<b>全局 L1 信封</b> ⇒ 归入 {@code contract/} 以与「各域错误契约」区分
 * （见 {@code docs/Mapping_Table/common.md} §1.3）。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/response/Response.java}
 * （L1 契约层**以 B 侧 为准** · D13；RAG 侧本就是 Jackson 2 写法 ⇒ <b>零回改</b>）。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码 · D18）</b>：
 * <pre>{@code
 * public class ApiResponse {
 *     private int code;
 *     private String msg;
 *     private Object data;
 *
 *     public int getCode() { return code; }
 *     public void setCode(int code) { this.code = code; }
 *     public String getMsg() { return msg; }
 *     public void setMsg(String msg) { this.msg = msg; }
 *     public Object getData() { return data; }
 *     public void setData(Object data) { this.data = data; }
 *
 *     public ApiResponse() { }
 *     public ApiResponse(int code, String msg, Object data) {
 *         this.code = code;
 *         this.msg = msg;
 *         this.data = data;
 *     }
 * }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse {
    private int code;
    private String msg;
    private Object data;
}
