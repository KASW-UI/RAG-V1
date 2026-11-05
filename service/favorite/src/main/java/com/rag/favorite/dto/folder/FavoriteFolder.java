package com.rag.favorite.dto.folder;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * <p><b>收藏夹条目</b>（{@link GetFavoriteFoldersResp} 的元素）—— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/FavoriteFolder.java:30-88}
 * （可变 class）。B 侧原始形态：
 * <pre>
 * type FavoriteFolder struct {
 *     FolderId  int64  `json:"folderId,string"`
 *     Name      string `json:"name"`
 *     CreatedAt int64  `json:"createdAt"`
 *     UpdatedAt int64  `json:"updatedAt"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code GET /favorite/v1/folder/list}（{@link GetFavoriteFoldersResp} 的元素）。
 *
 * <h3>🔴 白名单投影：proto 5 字段 → HTTP DTO <b>4 字段</b>（{@code user_id} 被丢弃）</h3>
 * <p>proto {@code favorite.FavoriteFolder} 有 5 个字段（含 {@code user_id}），
 * 而 HTTP DTO 只有 4 个 ⇒ <b>{@code user_id} 永不出 api 边界</b>。
 * <br>B 侧逐条依据：{@code FavoriteApiSupport#toFolderDto}
 * （{@code service/favorite/favorite-api/.../api/service/FavoriteApiSupport.java:127-133}）
 * 只映射 4 个值（{@code folderId} / {@code name} / {@code createdAt} / {@code updatedAt}）。
 *
 * <h3>⚠️⚠️ 两种 int64 的编码方式不同，别统一（B 侧 {@code FavoriteFolder.java:16-23}）</h3>
 * <ul>
 *   <li>{@code folderId} 带 {@code ,string} ⇒ 出参是 <b>JSON 字符串</b> {@code "123"}；</li>
 *   <li>{@code createdAt} / {@code updatedAt} <b>没有</b> {@code ,string}
 *       ⇒ 出参是 <b>JSON 数字</b>（Go 的 {@code encoding/json} 对 int64 默认写数字）。</li>
 * </ul>
 * 这是 Go 的 {@code types.go} 里 {@code FavoriteFolder} 与 {@code FavoriteItem} 的共同形态，
 * 混掉任何一个都会改变 L1 出参。
 *
 * <p><b>时间戳单位</b>：{@code createdAt} / {@code updatedAt} 来自 favorite-rpc 的
 * {@code favorite.FavoriteFolder.created_at / updated_at}（Unix <b>毫秒</b>，
 * 见 favorite-rpc 侧 {@code service/favorite/rpc/internal/logic/helper.go:69,83} 的 {@code UnixMilli()}）。
 * 本层<b>原样透传</b>，不做任何单位换算。
 *
 * <h3>⚠️ C 侧为什么 {@code folderId} 直接声明成 {@link String}</h3>
 * <p>B 侧「存 {@code Long}、getter 返回 {@code String}」是可变 POJO 时代的写法
 * （{@code FavoriteFolder.java:54-58}）；record 的分量访问器<b>就是</b> JSON 的取值来源
 * ⇒ 若分量仍声明成 {@code Long}，Jackson 会写出 <b>JSON 数字</b> ⇒ <b>L1 出参漂移</b>。
 * <br>⇒ 故本仓把分量直接声明成 {@link String}，转换点前移到 Service 层。
 * <br>⇒ ⚠️ <b>null 的语义要逐条对齐</b>：B 侧 getter 是
 * {@code folderId == null ? null : folderId.toString()} ⇒ <b>null → JSON null</b>
 * ⇒ Service 层必须写 {@code id == null ? null : Long.toString(id)}。
 * <br>⇒ ⚠️ <b>调用点的坑（proto 的 int64 getter 返回「原始 long」）</b>：
 * {@code new FavoriteFolder(proto.getFolderId(), proto.getName(), proto.getCreatedAt(), proto.getUpdatedAt())}
 * <b>编译不过</b>（{@code long → String} 没有隐式转换）⇒ 必须写成
 * {@code new FavoriteFolder(Long.toString(proto.getFolderId()), …)}
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
 *         <li><b>JSON key 名</b>：{@code folderId} / {@code name} / {@code createdAt} /
 *             {@code updatedAt}（B 侧 {@code FavoriteFolder.java:32-42} 的 4 个
 *             {@code @JsonProperty}）⇒ 逐字相同 ✔；</li>
 *         <li><b>值的类型</b>：{@code folderId} 是 <b>JSON 字符串</b>、
 *             两个时间戳是 <b>JSON 数字</b> ✔；</li>
 *         <li><b>时间戳类型</b>：B 侧是原始 {@code long}（不是 {@code Long}）
 *             ⇒ 缺席时是 {@code 0}、键一定出现 ⇒ 本仓用 {@code long} 逐条对齐 ✔；</li>
 *         <li>record 的访问器名（{@code folderId()} 而非 {@code getFolderId()}）<b>不进入 wire</b>
 *             ⇒ JSON 输出逐字节相同。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code FavoriteFolder.java:32-42}（类声明 ＋ 4 个字段与注解）
 *       ＋ {@code FavoriteFolder.java:54-58}（id 的出参字符串化 getter）
 *       ＋ {@code FavoriteApiSupport.java:127-133}（4 字段白名单投影）。</li>
 * </ol>
 *
 * @param folderId  收藏夹 id（<b>JSON 字符串</b>）
 * @param name      收藏夹名
 * @param createdAt 创建时间（<b>JSON 数字</b> · Unix <b>毫秒</b>）
 * @param updatedAt 更新时间（<b>JSON 数字</b> · Unix <b>毫秒</b>）
 */
public record FavoriteFolder(
        @JsonProperty("folderId") String folderId,
        @JsonProperty("name") String name,
        @JsonProperty("createdAt") long createdAt,
        @JsonProperty("updatedAt") long updatedAt) {
}
