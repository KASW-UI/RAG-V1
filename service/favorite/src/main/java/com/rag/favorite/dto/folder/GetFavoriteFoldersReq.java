package com.rag.favorite.dto.folder;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * <p><b>查询收藏夹列表的入参</b> —— <b>空 body 契约</b>（C 侧新增 · B 侧无此 DTO）。
 *
 * <p><b>B 出处</b>：<b>无</b> —— B 侧
 * {@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/}
 * 目录下<b>没有</b> {@code GetFavoriteFoldersReq.java}
 * （该目录共 15 个文件，无同名文件）⇒ Mapping_Table §1.2 把本文件登记为 <b>「C 新增」</b>。
 *
 * <p>使用它的端点：{@code GET /favorite/v1/folder/list}（<b>需 uid</b>）。
 *
 * <h3>🔴 为什么要造一个「空 record」</h3>
 * <p>看起来这个端点「不需要入参」，但 B 侧<b>仍然会解析并校验请求体</b>：
 * <ul>
 *   <li>Go 出处 {@code rest/httpx/requests.go:65-67}：{@code httpx.Parse} <b>无条件</b>跑
 *       {@code ParseJsonBody}（在 {@code if kind != Array && kind != Slice} 之外）；</li>
 *   <li>而 {@code ParseJsonBody} 先判
 *       {@code withJsonBody(r) = ContentLength &gt; 0 && strings.Contains(CT, "application/json")}；</li>
 *   <li>body 非法时 handler 落 {@code ErrorInvalidParam} ⇒ <b>HTTP 200 + {@code {code:5003}}</b>。</li>
 * </ul>
 * <p>B 侧的 Java 形态是<b>手写</b>的 {@code assertGoEmptyStructBody(HttpServletRequest)}
 * （{@code service/favorite/favorite-api/.../api/controller/FavoriteController.java:235-266}，
 * 调用点 {@code :152}）—— 它<b>不读 body 除非</b>「Content-Length &gt; 0 且 Content-Type 含
 * {@code application/json}」，然后只判<b>根节点是不是 JSON object</b>。
 * <p>⇒ <b>本 record 就是那个「空 body 契约」的载体</b>：零字段 ⇒ Jackson 对
 * <b>任何 JSON object</b> 都能绑成它（与 B 侧的「空 struct 忽略未知字段」同形），
 * 而<b>根不是 object</b>（{@code null} / 标量 / 数组 / 语法错误）时绑定失败 ⇒ 5003。
 *
 * <h3>⚠️ C 侧读数（Jackson 2.19.4 · 本地探针 · 2026-11-04）</h3>
 * <p>本类的绑定行为已用真 Jackson 跑过（探针：零分量 record ＋ Boot 默认 mapper），
 * 读数与 B 侧的「真源探针实测」表（{@code FavoriteController.java:218-228}）逐条对照：
 * <pre>
 *   body={}               → 绑定成功   ⇒ 200   （B 侧 200 ✔）
 *   body={"a":1}          → 绑定成功   ⇒ 200   （B 侧 200 · 「空 struct 忽略未知字段」✔）
 *   body=null             → 绑定成 null（**不报错**）⇒ ⚠️ 见下方「null 的例外」
 *   body=123 / 1.5 / true / "str" → 绑定失败（no int/double/boolean/String-argument constructor）⇒ 5003 ✔
 *   body=[] / [1,2]       → 绑定失败（Cannot deserialize … from Array value）⇒ 5003 ✔
 *   body={                → 绑定失败（JsonEOFException）⇒ 5003 ✔
 * </pre>
 * ⇒ ⚠️ <b>唯一与 B 侧不同形状的是 {@code body=null}</b>：Jackson 对 JSON 字面量
 * {@code null} 返回 {@code null}（<b>不抛异常</b>）⇒ 若 Controller 用
 * {@code @RequestBody(required = true)}，Spring 会因「参数为 null」抛
 * {@code HttpMessageNotReadableException} ⇒ <b>异常处理器必须把它映射成 5003</b>
 * （B 侧是「unsupported type on setting field value」⇒ 5003）⇒
 * <b>这是 Controller / advice 层的责任，不是本 DTO 的</b>。
 *
 * <h3>⚠️ 为什么本类<b>没有</b> {@code @JsonIgnoreProperties}</h3>
 * <p>「忽略未知字段」这件事由<b>全局 mapper</b> 决定，不由注解决定：
 * Spring 的 {@code Jackson2ObjectMapperBuilder} 默认
 * {@code disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)}
 * ⇒ 未知字段天然被忽略 ⇒ 与 Go 的「空 struct 忽略未知字段」逐条一致。
 * <br>⇒ 本仓其它 DTO 也一律<b>不加</b>这个注解（同款理由见
 * {@code service/like/.../contract/KafkaLikeMsg.java:27-33}：
 * 「真正决定行为的是全局 {@code ObjectMapper} 的 {@code .disable(FAIL_ON_UNKNOWN_PROPERTIES)}」）。
 *
 * <h3>⚠️ C 侧的落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>新增了一个 B 侧没有的类型</b>（B 侧是「Controller 里手写
 *       {@code assertGoEmptyStructBody}」，C 侧是「零字段 record ＋ Controller 绑定它」）
 *       ⇒ 属<b>表达层</b>重构（L4），目的是让「空 body 契约」在类型上可见、可测。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>合法 body</b>：{@code {}}、{@code {"a":1}}（未知字段被忽略）⇒ 两侧都通过 ✔；</li>
 *         <li><b>非法 body</b>：标量 / 数组 / 语法错误 ⇒ 两侧都是 <b>5003</b> ✔
 *             （依据：上方「C 侧读数」表 ＋ B 侧 {@code FavoriteController.java:218-228} 的
 *             「真源探针实测」表）；</li>
 *         <li><b>{@code body=null}</b>：两侧都是 5003，但<b>触发点不同</b>
 *             （B：解析层报错；C：Spring 的「参数为 null」）⇒ 需由异常处理器对齐（见上）✔；</li>
 *         <li><b>Content-Type 不是 {@code application/json}</b>（或缺失）或
 *             {@code Content-Length <= 0} ⇒ 两侧都<b>根本不读 body</b> ✔。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code FavoriteController.java:143-154}（端点 ＋ 调用点 ＋ 注释）
 *       ＋ {@code FavoriteController.java:218-228}（「真源探针实测」表）
 *       ＋ {@code FavoriteController.java:235-266}（{@code assertGoEmptyStructBody} 的实现）。</li>
 * </ol>
 */
public record GetFavoriteFoldersReq() {

    /**
     * Jackson 的<b>唯一</b>反序列化入口 —— 零参。
     *
     * <p>⚠️ 显式写成静态工厂（而非只靠规范构造器）⇒ 与本域其它 DTO 的
     * 「{@code @JsonCreator} 工厂 = 唯一入口」约定一致（A9 判据只看有无 setter）。
     */
    @JsonCreator
    public static GetFavoriteFoldersReq fromJson() {
        return new GetFavoriteFoldersReq();
    }
}
