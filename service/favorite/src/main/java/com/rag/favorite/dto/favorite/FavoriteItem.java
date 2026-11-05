package com.rag.favorite.dto.favorite;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>收藏条目</b>（{@link GetFavoritesByFolderResp} 的元素）—— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/FavoriteItem.java:33-129}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type FavoriteItem struct {
 *     FavoriteId int64  `json:"favoriteId,string"`
 *     FolderId   int64  `json:"folderId,string"`
 *     TargetId   string `json:"targetId"`
 *     TargetType string `json:"targetType"`
 *     Title      string `json:"title,optional"`
 *     Cover      string `json:"cover,optional"`
 *     CreatedAt  int64  `json:"createdAt"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code GET /favorite/v1/item/list}（{@link GetFavoritesByFolderResp} 的元素）。
 *
 * <h3>🔴 白名单投影：proto 8 字段 → HTTP DTO <b>7 字段</b>（{@code user_id} 被丢弃）</h3>
 * <p>proto {@code favorite.FavoriteItem} 有 8 个字段（含 {@code user_id}），
 * 而 HTTP DTO 只有 7 个 ⇒ <b>{@code user_id} 永不出 api 边界</b>。
 * <br>B 侧逐条依据：{@code FavoriteApiSupport#toItemDto}
 * （{@code service/favorite/favorite-api/.../api/service/FavoriteApiSupport.java:141-150}）
 * 只映射 7 个值，且注释明写「<b>刻意不映射 {@code user_id}</b>」。
 *
 * <h3>⚠️⚠️ 编码方式与 {@code ,optional} 的双重陷阱（B 侧 {@code FavoriteItem.java:19-28}）</h3>
 * <ul>
 *   <li>{@code favoriteId} / {@code folderId} 带 {@code ,string} ⇒ 出参是 <b>JSON 字符串</b>；</li>
 *   <li>{@code createdAt} <b>没有</b> {@code ,string} ⇒ 出参是 <b>JSON 数字</b>；</li>
 *   <li>{@code title} / {@code cover} 的 {@code ,optional} 是 <b>go-zero mapping 的选项</b>，
 *       <b>不是</b> Go {@code encoding/json} 的 {@code omitempty} ⇒
 *       <b>这两个字段永远出现</b>，为空时输出 {@code ""}（不是 null、也不是消失）。</li>
 * </ul>
 * ⇒ 本 DTO 的 {@code title} / {@code cover} <b>刻意不加</b> {@code @JsonInclude(NON_NULL)}，
 * 与 admin-api 的 {@code GetUserListResp#list} 同款理由。
 * <br>⇒ ⚠️ Service 层从 proto 投影时也必须<b>原样透传</b>（proto 的 string 字段永不为 null，
 * 缺省是 {@code ""}）⇒ 不会出现 JSON null。
 *
 * <p><b>时间戳单位</b>：{@code createdAt} 来自 favorite-rpc 的
 * {@code favorite.FavoriteItem.created_at}（Unix <b>毫秒</b>），本层原样透传，
 * <b>不做任何单位换算</b>（B 侧 {@code FavoriteItem.java:30-31}）。
 *
 * <h3>⚠️ C 侧为什么 {@code favoriteId} / {@code folderId} 直接声明成 {@link String}</h3>
 * <p>B 侧「存 {@code Long}、getter 返回 {@code String}」是可变 POJO 时代的写法
 * （{@code FavoriteItem.java:70-88}）；record 的分量访问器<b>就是</b> JSON 的取值来源
 * ⇒ 若分量仍声明成 {@code Long}，Jackson 会写出 <b>JSON 数字</b> ⇒ <b>L1 出参漂移</b>。
 * <br>⇒ 故本仓把分量直接声明成 {@link String}，转换点前移到 Service 层。
 * <br>⇒ ⚠️ <b>null 的语义要逐条对齐</b>：B 侧 getter 是
 * {@code folderId == null ? null : folderId.toString()} ⇒ <b>null → JSON null</b>
 * ⇒ Service 层必须写 {@code id == null ? null : Long.toString(id)}。
 * <br>⇒ ⚠️ <b>调用点的坑（proto 的 int64 getter 返回「原始 long」）</b>：
 * {@code new FavoriteItem(proto.getFavoriteId(), proto.getFolderId(), …)}
 * <b>编译不过</b>（{@code long → String} 没有隐式转换）⇒ 必须写成
 * {@code new FavoriteItem(Long.toString(proto.getFavoriteId()), Long.toString(proto.getFolderId()), …)}
 * （proto3 的 int64 永不为 null ⇒ 无需判空；<b>但</b>若改用包装类型 {@code Long}，
 * 则须写 {@code id == null ? null : Long.toString(id)}）。
 *
 * <h3>⚠️ C 侧的落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>可变 class + 无参/全参构造器 + setter → immutable record</b>
 *       （D5 + A9）＋ <b>包名压平</b>（D11）
 *       ＋ <b>「存 Long / getter 转 String」→ 分量直接是 String</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>JSON key 名</b>：{@code favoriteId} / {@code folderId} / {@code targetId} /
 *             {@code targetType} / {@code title} / {@code cover} / {@code createdAt}
 *             （B 侧 {@code FavoriteItem.java:35-54} 的 7 个 {@code @JsonProperty}）⇒ 逐字相同 ✔；</li>
 *         <li><b>值的类型</b>：两个 id 是 <b>JSON 字符串</b>、{@code createdAt} 是 <b>JSON 数字</b> ✔；</li>
 *         <li><b>{@code title} / {@code cover}</b>：<b>键一定出现</b>（不加 {@code @JsonInclude}）✔；</li>
 *         <li><b>{@code createdAt} 的类型</b>：B 侧是原始 {@code long}（不是 {@code Long}）
 *             ⇒ 缺席时是 {@code 0}、键一定出现 ⇒ 本仓用 {@code long} 逐条对齐 ✔；</li>
 *         <li>record 的访问器名（{@code favoriteId()} 而非 {@code getFavoriteId()}）<b>不进入 wire</b>
 *             ⇒ JSON 输出逐字节相同。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code FavoriteItem.java:33-54}（类声明 ＋ 7 个字段与注解）
 *       ＋ {@code FavoriteItem.java:70-88}（两个 id 的出参字符串化 getter）
 *       ＋ {@code FavoriteApiSupport.java:141-150}（7 字段白名单投影）。</li>
 * </ol>
 *
 * @param favoriteId 收藏 id（<b>JSON 字符串</b>）
 * @param folderId   所属收藏夹 id（<b>JSON 字符串</b>）
 * @param targetId   被收藏对象 id
 * @param targetType 被收藏对象类型
 * @param title      标题（<b>键一定出现</b>；proto 侧缺省为 {@code ""}）
 * @param cover      封面（<b>键一定出现</b>；proto 侧缺省为 {@code ""}）
 * @param createdAt  创建时间（<b>JSON 数字</b> · Unix <b>毫秒</b>）
 */
public record FavoriteItem(
        @JsonProperty("favoriteId") String favoriteId,
        @JsonProperty("folderId") String folderId,
        @JsonProperty("targetId") String targetId,
        @JsonProperty("targetType") String targetType,
        @JsonProperty("title") String title,
        @JsonProperty("cover") String cover,
        @JsonProperty("createdAt") long createdAt) {
}
