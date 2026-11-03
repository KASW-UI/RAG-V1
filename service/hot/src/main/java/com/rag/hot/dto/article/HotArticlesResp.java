package com.rag.hot.dto.article;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * <p><b>热门文章列表响应</b> —— 对应 B 侧 {@code service/hot/hot-api/.../api/dto/HotArticlesResp.java}。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/dto/HotArticlesResp.java}
 * <pre>{@code
 *   @JsonPropertyOrder({"items","total","page","page_size","scope"})   :48
 *   类声明 + 5 个字段（全部 @JsonProperty 显式钉键名）                  :49-64
 *   无参构造器                                                          :66-68
 *   全参构造器 (List,long,int,int,String)                              :69-75
 *   5 组手写 getter/setter                                             :76-115
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯表达层</b>改动 ——
 *       ① <b>可变 POJO → immutable record</b>（D5 + A9）⇒ setter 全部消失；
 *       ② 包名 {@code com.rag.hot.hot.api.dto} → {@code com.rag.hot.dto.article}（D7 + D11）；
 *       ③ 字段名 {@code pageSize} → record 组件名不变（record 组件名即字段名，逐字未动）。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       ① 5 个字段的<b>名字</b>、<b>类型</b>、<b>JSON 键名</b>、<b>输出顺序</b>逐条未动
 *       （{@link JsonPropertyOrder} 逐条照抄 B 侧 :48）；</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>⚠️ {@code items} 必须是空列表而不是 null（L1 字节级）</b>：
 * B 侧的切片在空页时是<b>非 nil 的空切片</b> ⇒ JSON 输出 {@code "items":[]}；
 * 任何返回 {@code null} 的路径都会产出 {@code "items":null}，客户端按 {@code items.length}
 * 遍历时行为不同 ⇒ 构造处无条件给空列表。
 *
 * <p><b>⚠️ {@code total} 不是「全站热门文章总数」</b>：
 * B 侧取的是<b>补全 + 过滤之后</b>的列表长度（≤ 100）⇒ 它是「<b>本次滚动窗口内可公开展示的条目数</b>」，
 * 不是历史累计值、也不随分页参数变化。
 *
 * <p><b>⚠️ {@code scope} 是编译期常量</b>：恒为 {@code "rolling"}（B 侧直接写死，
 * hot-rpc 当前<b>不返回</b>窗口类型）。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：DTO 层按 D5 + A9 一律用 record ⇒ Lombok 被明令禁止。
 */
@JsonPropertyOrder({"items", "total", "page", "page_size", "scope"})
public record HotArticlesResp(
        @com.fasterxml.jackson.annotation.JsonProperty("items") List<HotArticleItem> items,
        @com.fasterxml.jackson.annotation.JsonProperty("total") long total,
        @com.fasterxml.jackson.annotation.JsonProperty("page") int page,
        @com.fasterxml.jackson.annotation.JsonProperty("page_size") int pageSize,
        @com.fasterxml.jackson.annotation.JsonProperty("scope") String scope) {
}
