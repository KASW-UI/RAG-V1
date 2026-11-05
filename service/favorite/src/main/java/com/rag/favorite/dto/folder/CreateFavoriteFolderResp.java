package com.rag.favorite.dto.folder;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>新建收藏夹的响应体</b>（信封 {@code data} 的内容）—— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/CreateFavoriteFolderResp.java:20-40}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type CreateFavoriteFolderResp struct {
 *     FolderId int64 `json:"folderId,string"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code POST /favorite/v1/folder/create}。
 *
 * <h3>⚠️⚠️ 出参的 {@code ,string} 是「严格」的</h3>
 * <p>Go 的 {@code encoding/json} 对带 {@code ,string} 标签的 int64，
 * <b>序列化时一定写成 JSON 字符串</b>（B 侧 {@code CreateFavoriteFolderResp.java:13-15} 的类注释
 * ＋ {@code :32-36} 的 {@code getFolderId()} 返回 {@code String}）。
 * ⇒ 客户端拿到的必须是 {@code {"folderId":"123"}}，<b>不是</b> {@code {"folderId":123}}。
 * <p>与入参方向相反这件事常被搞混：<b>入参宽松（字符串与数字都收）、出参严格（只写字符串）</b>。
 *
 * <h3>⚠️ C 侧为什么直接把字段声明成 {@link String}</h3>
 * <p>B 侧的「存 {@code Long}、getter 返回 {@code String}」是<b>可变 POJO</b>时代的写法；
 * record 的分量访问器<b>就是</b> JSON 的取值来源 ⇒ 若分量仍声明成 {@code Long}，
 * Jackson 会写出 <b>JSON 数字</b> ⇒ <b>L1 出参漂移</b>。
 * <br>⇒ 故本仓把分量直接声明成 {@link String}，转换点前移到 Service 层
 * （B 侧同款做法见 {@code service/users/admin/.../AdminApiService.java:307}）。
 * <br>⇒ ⚠️ <b>null 的语义要逐条对齐</b>：B 侧 getter 是
 * {@code folderId == null ? null : folderId.toString()} ⇒ <b>null → JSON null</b>
 * ⇒ Service 层必须写 {@code id == null ? null : Long.toString(id)}，
 * <b>不能</b>直接写 {@code Long.toString(id)}（会 NPE），也<b>不能</b>写
 * {@code String.valueOf(id)}（null 会变成字符串 {@code "null"}）⇒ <b>这是 L1 出参</b>。
 * <br>⇒ ⚠️ <b>调用点的坑（proto 的 int64 getter 返回「原始 long」）</b>：
 * {@code new CreateFavoriteFolderResp(rpcResp.getFolderId())} <b>编译不过</b>
 * （{@code long → String} 没有隐式转换）⇒ 必须写成
 * {@code new CreateFavoriteFolderResp(Long.toString(rpcResp.getFolderId()))}
 * （proto3 的 int64 永不为 null ⇒ 无需判空）。
 *
 * <h3>⚠️ C 侧的落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变 class + 无参/单参构造器 + setter → immutable record</b>
 *       （D5 + A9）＋ <b>包名压平</b>（D11）＋ <b>「存 Long / getter 转 String」→ 分量直接是 String</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON key 名</b>：{@code folderId}（B 侧 {@code @JsonProperty("folderId")}，
 *             B {@code CreateFavoriteFolderResp.java:22}）⇒ 逐字相同 ✔；</li>
 *         <li><b>值的类型</b>：JSON <b>字符串</b>（B 侧 {@code ,string} 的严格出参方向）✔；</li>
 *         <li><b>null 的形状</b>：两侧都是 {@code null}（不是 {@code "null"} / {@code "0"}）✔
 *             —— 前置条件见上方「null 的语义要逐条对齐」；</li>
 *         <li>record 的访问器名（{@code folderId()} 而非 {@code getFolderId()}）<b>不进入 wire</b>
 *             ⇒ JSON 输出逐字节相同。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code CreateFavoriteFolderResp.java:22-23}（字段 ＋ 注解）
 *       ＋ {@code CreateFavoriteFolderResp.java:32-36}（出参字符串化的 getter）。</li>
 * </ol>
 *
 * @param folderId 新建的收藏夹 id（<b>JSON 字符串</b>；null ⇒ JSON null）
 */
public record CreateFavoriteFolderResp(
        @JsonProperty("folderId") String folderId) {
}
