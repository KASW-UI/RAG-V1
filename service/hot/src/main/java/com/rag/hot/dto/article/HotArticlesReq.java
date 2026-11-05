package com.rag.hot.dto.article;

/**
 * <p><b>分页入参</b> —— 对应 B 侧 {@code service/hot/hot-api/.../api/dto/HotArticlesReq.java}。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-api/src/main/java/com/rag/hot/hot/api/dto/HotArticlesReq.java}
 * <pre>{@code
 *   类声明 + 两个字段（int page / int pageSize）    :41-47
 *   无参构造器                                     :49-51
 *   全参构造器 (int,int)                           :52-56
 *   getPage / setPage                             :57-63
 *   getPageSize / setPageSize                     :64-71
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯表达层</b>改动 ——
 *       ① <b>可变 POJO → immutable record</b>（D5 + A9「DTO 必须 immutable」）——
 *       setter 全部消失 ⇒ 调用点改用构造器；
 *       ② 包名 {@code com.rag.hot.hot.api.dto} → {@code com.rag.hot.dto.article}（D7 + D11）；
 *       ③ 删掉 B 侧那段「form tag 默认值」注释的<b>承载方式</b> ——
 *       B 侧靠 go-zero 的 {@code form:"page,default=1"} tag 表达默认值，
 *       C 侧<b>不走 form 绑定</b>（见下方「两层默认值」）⇒ 默认值改由
 *       {@code HotApiSupport#parseInt32} 的第三参数承担。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       ① 两个字段的<b>名字</b>（{@code page} / {@code pageSize}）、<b>类型</b>（{@code int}）、
 *          <b>取值口径</b>（页面从 1 起、每页条数）逐条未动；
 *       ② <b>int32 位宽语义保留</b>：Java 的 {@code int} 同为 32 位补码 ⇒
 *          {@code HotApiSupport#paginate} 里的 {@code (page - 1) * pageSize} 溢出行为与 B 侧逐位相同
 *          ⇒ <b>不用</b> {@code long}（用了会把「超长 page ⇒ 空页」这个可观察行为悄悄改掉）。</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>⚠️ 两层默认值，缺一不可（B 侧 :13-33 的分工）</b>：
 * <table border="1">
 *   <caption>两层默认值的分工</caption>
 *   <tr><th>层</th><th>B 侧规则</th><th>C 侧落点</th></tr>
 *   <tr><td>① form tag</td>
 *       <td>仅当 key <b>缺席或值为空</b>时生效（空值被整个丢弃）</td>
 *       <td>{@link HotApiSupport#parseInt32} 的 {@code defaultValue} 参数</td></tr>
 *   <tr><td>② logic</td>
 *       <td>{@code page<=0 → 1}、{@code pageSize<=0 → 20}、{@code pageSize>50 → 50}</td>
 *       <td>{@link HotApiSupport#normalize}</td></tr>
 * </table>
 * ⇒ 传 {@code ?page=0} 时：① 让它保持 0，② 再把它归一化成 1。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：DTO 层按 D5 + A9 一律用 record ⇒
 * {@code @Data} / {@code @Getter} 会被明令禁止（A13 判据不适用）。
 */
public record HotArticlesReq(
        /** 页码（从 1 起）。 */
        int page,
        /** 每页条数。 */
        int pageSize) {
}
