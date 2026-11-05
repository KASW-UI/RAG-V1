package com.rag.favorite.dto.favorite;

import com.rag.common.web.StringInt64;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>查询收藏夹下收藏的入参</b> —— <b>form 通道（query string）</b>，<b>不是</b> JSON body
 * —— immutable record（决策 D5 · A9）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-api/src/main/java/com/rag/favorite/favorite/api/dto/GetFavoritesByFolderReq.java:54-87}
 * （可变 class ＋ 手写必填校验器 ＋ 静态工厂 {@code fromForm}）。B 侧原始形态：
 * <pre>
 * type GetFavoritesByFolderReq struct {
 *     FolderId int64 `form:"folderId,string"`
 * }
 * </pre>
 *
 * <p>使用它的端点：{@code GET /favorite/v1/item/list}（<b>需 uid</b>）。
 *
 * <h3>🔴🔴 本 DTO 走的是 <b>form 通道</b>（query string），不是 JSON body</h3>
 * <p>这是 favorite 域<b>唯一</b>一个 form 通道字段（其余 6 个端点全是 json tag）
 * —— B 侧 {@code GetFavoritesByFolderReq.java:15-16} 的类注释明写这一点。
 *
 * <h3>为什么「tag 决定走哪个通道」</h3>
 * <p>Go 出处：{@code rest/httpx/requests.go:50-77} 的 {@code Parse} 依次跑
 * {@code ParsePath} → {@code ParseForm} → {@code ParseHeaders} → {@code ParseJsonBody}，
 * 每个通道用<b>各自的 tag 名</b>取 key（form 通道用 {@code "form"}，json 通道用 {@code "json"}）。
 * <p>而 {@code core/mapping/unmarshaler.go:569-573} 的 {@code processField} 第一句就是：
 * <pre>
 *   if usingDifferentKeys(u.key, field) { return nil }    // 该字段在本通道整体跳过
 * </pre>
 * {@code core/mapping/utils.go:589-597} 的 {@code usingDifferentKeys}：
 * <pre>
 *   if len(field.Tag) &gt; 0 { if _, ok := field.Tag.Lookup(key); !ok { return true } }
 * </pre>
 * ⇒ <b>字段只要带了「别的通道」的 tag，就在本通道被整体跳过</b>（连必填判定都不做）。
 * <p>所以：
 * <ul>
 *   <li>{@code folderId} 带 {@code form} tag ⇒ <b>只在 form 通道生效</b>
 *       ⇒ 值来自 query string；</li>
 *   <li>其余 6 个端点的字段带 {@code json} tag ⇒ <b>只在 json 通道生效</b>
 *       ⇒ 它们在 form 通道被跳过，所以「POST 请求带 query 参数」不会影响它们。</li>
 * </ul>
 *
 * <h3>form 通道的实测语义（go-zero v1.10.2 真源探针 · B 侧 {@code GetFavoritesByFolderReq.java:39-49}）</h3>
 * <pre>
 *   ?folderId=123                 → OK（123）
 *   ?folderId=0                   → OK（0）
 *   ?folderId=abc                 → strconv.ParseInt: parsing "abc": invalid syntax ⇒ 5003
 *   ?folderId=1.5                 → strconv.ParseInt: parsing "1.5": invalid syntax ⇒ 5003
 *   ?folderId=1e2                 → strconv.ParseInt: parsing "1e2": invalid syntax ⇒ 5003
 *   ?folderId=                    → field "folderId" is not set ⇒ 5003
 *   ?folderid=123（大小写不同）    → field "folderId" is not set ⇒ 5003（key 大小写敏感）
 *   完全没有 query                → field "folderId" is not set ⇒ 5003
 * </pre>
 * <p>⚠️ <b>空值被整个丢掉</b>：Go 出处 {@code rest/httpx/util.go:37-43} 的
 * {@code GetFormValues} 里 {@code if len(v) == 0 { continue }} ⇒
 * {@code ?folderId=} 退化成「字段缺席」，而不是「值是空串」。
 * <br>⇒ C 侧的对应件：域内 {@code com.rag.favorite.service.FavoriteApiSupport#goFormFirstValue}
 * （<b>先滤空值、再取首值</b> · 与 B 侧同款实现）；共享层的等价件是
 * {@code com.rag.common.web.FormValues#first(String[])}。
 *
 * <h3>🔴 本类<b>不是</b> Jackson 的绑定目标 ⇒ {@code @NotNull} <b>不会自动生效</b></h3>
 * <p>本 record <b>没有</b> {@code @JsonCreator} 工厂（form 通道没有 JSON body）⇒
 * Bean Validation <b>不会</b>随绑定自动跑。
 * <br>⇒ <b>Controller 层必须显式触发校验</b>（{@code @Validated} ＋ {@code @Valid}），
 * 否则「{@code folderId} 缺席」会退化成 {@code null} → 被 Service 层的
 * {@code nullToZero} 归一成 {@code 0} ⇒ <b>本该 5003 的请求会变成 200</b>（L1 漂移）。
 * <br>⇒ B 侧的对应形态是 Controller 里显式调 {@code req.assertGoRequiredFields()}
 * （B 侧 {@code service/favorite/favorite-api/.../api/controller/FavoriteController.java:197-198}）。
 *
 * <h3>⚠️ C 侧的必填落地方式（L4 idiomatization · AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：把 B 侧的<b>手写必填校验器</b>
 *       （{@code assertGoRequiredFields()} ＋ {@code GoStyleRequired.field}）
 *       换成 <b>Bean Validation</b>（{@code @NotNull}）—— A10 断言要求的形态；
 *       <b>可变 class → immutable record</b>（D5 + A9）；<b>包名压平</b>（D11）；
 *       <b>B 侧的 {@code GoStyleStringInt64} → 共享层 {@link StringInt64}</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       <ul>
 *         <li><b>必填口径</b>：B 侧 {@code GoStyleRequired.field} <b>只判 null</b>
 *             ⇒ {@code @NotNull} 同样只判 null ⇒ 逐条等价 ✔；</li>
 *         <li><b>空串 = 缺席</b>：{@link #fromForm(String)} 里对空串的处理与 B 侧
 *             {@code GetFavoritesByFolderReq.java:70-78} <b>逐条一致</b>
 *             （空串 ⇒ 不解析 ⇒ 字段留 null ⇒ 必填判定拒）✔；</li>
 *         <li><b>{@code ,string} 解析口径</b>：form 通道的值<b>本来就是字符串</b>
 *             ⇒ 由 {@link StringInt64#parse} 走 {@code strconv.ParseInt} 同款判据
 *             （拒 {@code ""} / {@code " 12"} / {@code "1.5"} / {@code "1e2"} / 超 long）✔。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：{@code GetFavoritesByFolderReq.java:70-78}（{@code fromForm} 静态工厂）
 *       ＋ {@code GetFavoritesByFolderReq.java:80-83}（{@code assertGoRequiredFields}）
 *       ＋ {@code FavoriteController.java:191-200}（取首值 ＋ 显式必填校验）。</li>
 * </ol>
 *
 * @param folderId 收藏夹 id（必填；来自 query string 的 {@code folderId}）
 */
public record GetFavoritesByFolderReq(
        @NotNull Long folderId) {

    /**
     * 从 query string 构造 —— 对应 Go 的 form 通道。
     *
     * <p>⚠️ <b>重复参数的「先滤空值、再取首值」两步不在这里</b>：那属于 go-zero 的
     * {@code GetFormValues} ＋ {@code WithFromArray} 两段机制，已由域内
     * {@code com.rag.favorite.service.FavoriteApiSupport#goFormFirstValue} 完整复刻
     * （共享层的等价件是 {@code com.rag.common.web.FormValues#first(String[])}）。
     * 传入这里的正常只会是「首值」或 {@code null}（缺席）。
     * 下面的空串判断是<b>防御性冗余</b>，与上游口径一致。
     *
     * @param rawFolderId 清洗后的 {@code folderId} 首值（可能为 {@code null}）
     * @throws IllegalArgumentException 值不是合法的 int64（对应 Go 的 {@code strconv.ParseInt} 失败）
     */
    public static GetFavoritesByFolderReq fromForm(String rawFolderId) {
        // Go 的 GetFormValues 把空值整个丢掉 ⇒ 这里也把空串当「缺席」，
        // 而不是交给 ParseInt（那会给出 "invalid syntax"，与 Go 的错误文案不同）。
        Long folderId = null;
        if (rawFolderId != null && !rawFolderId.isEmpty()) {
            folderId = StringInt64.parse(rawFolderId, "folderId");
        }
        return new GetFavoritesByFolderReq(folderId);
    }
}
