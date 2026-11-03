package com.rag.article.dto.article;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rag.article.web.ArticleStringFields;
import com.rag.common.web.BadRequestException;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * <p><b>职责</b>：创建文章的入参（JSON body）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-api/src/main/java/com/rag/article/article/api/dto/CreateArticleReq.java}
 * （类声明 {@code :57} · 六个字段 {@code :59-64} · 必填校验 {@code :108-119}）⇒ <b>逐条照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *       <ul>
 *         <li><b>可变类 + setter → immutable record</b>（D5 + A9）⇒ setter 变成
 *             <b>{@link JsonCreator} 次构造器</b>（形参保持 {@code Object}）；</li>
 *         <li><b>必填校验从「手写 {@code assertGoRequiredFields()}」改成 Bean Validation
 *             {@link NotNull}</b>（A10：有 validation 依赖 ⇒ 禁用手写必填校验器）；</li>
 *         <li><b>包名压平</b>（{@code com.rag.article.article.api.dto} → {@code com.rag.article.dto.article}，D11）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>判定等价，body 文案有一处不同</b> ——
 *       <ul>
 *         <li><b>JSON key 名逐字相同</b>（见下方对照表）；</li>
 *         <li><b>类型不符</b>：走 {@link ArticleStringFields#parse}（B 侧
 *             {@code dto/GoStyleStringFields.java:62-78} 的逐行副本）⇒
 *             {@code type mismatch for field "title", expect "string", actual "number"}
 *             ⇒ <b>逐字节相同</b>，且被 Jackson 包成 {@code ValueInstantiationException}
 *             ⇒ Spring 的 {@code HttpMessageNotReadableException} ⇒ <b>400 + text/plain</b>；</li>
 *         <li><b>必填</b>：B 侧「缺席」⇒ {@code field "title" is not set}、
 *             「显式 null」⇒ {@code field "title" mustn't be nil}，<b>两条都是 400 text/plain</b>；
 *             本仓用 {@link NotNull}（<b>不是</b> {@code @NotBlank}）⇒ 缺席 / 显式 null <b>都被拒</b>、
 *             <b>空串放行</b>（B 侧只判 null 不判空串）⇒ <b>判定逐条等价</b>，
 *             但 <b>body 文案</b>是 Hibernate Validator 的措辞，不是 go-zero 的措辞
 *             ⇒ <b>登记为签字偏离（状态码一致，body 文案不同）</b>。</li>
 *       </ul></li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>JSON key 逐条对照（L1 · 不可改）</h2>
 * <pre>
 *   Go (types.go:26-33):
 *     Title         string   `json:"title"`                  ← 必填
 *     Brief         string   `json:"brief,optional"`
 *     Content       string   `json:"content"`                ← 必填
 *     CoverImageUrl string   `json:"cover_image_url,optional"`
 *     ManualTypeTag string   `json:"manual_type_tag,optional"`
 *     SecondaryTags []string `json:"secondary_tags,optional"`
 * </pre>
 * <table border="1">
 *   <tr><th>Go tag</th><th>本 record 组件</th></tr>
 *   <tr><td>{@code title}</td><td>{@code title}</td></tr>
 *   <tr><td>{@code brief}</td><td>{@code brief}</td></tr>
 *   <tr><td>{@code content}</td><td>{@code content}</td></tr>
 *   <tr><td>{@code cover_image_url}</td><td>{@code coverImageUrl}</td></tr>
 *   <tr><td>{@code manual_type_tag}</td><td>{@code manualTypeTag}</td></tr>
 *   <tr><td>{@code secondary_tags}</td><td>{@code secondaryTags}</td></tr>
 * </table>
 *
 * <h2>⚠️ 「必填」的判定依据是 tag 里有没有 {@code ,optional}，不是「值是否为空」</h2>
 * <p>go-zero 的 {@code core/mapping/utils.go:372} {@code parseOption} <b>只认三种</b>选项：
 * {@code inherit} / {@code string} / 以 {@code optional} 开头的 ——
 * <b>{@code omitempty} 不在其中</b> ⇒ 该字段<b>必填</b>。
 *
 * <p><b>⚠️ 空串是合法值</b>：Go 侧对 String 只判 null 不判空串
 * ⇒ {@code {"title":""}} <b>会成功</b>并把空标题发给域服务；标题是否为空由下游判定，本层不重复校验
 * ⇒ 这也是用 {@link NotNull} 而<b>不是</b> {@code @NotBlank} 的原因。
 *
 * <p><b>⚠️ 本类依赖 {@code com.rag.article.web.ArticleStringFields}</b>（域内副本 ·
 * 对应 B 侧 {@code dto/GoStyleStringFields.java}）⇒ <b>待收口</b>：
 * 共享层 {@code common/web/StringFields} 是<b>包私有</b> ⇒ 域内 DTO 调不到。
 */
public record CreateArticleReq(

        @NotNull(message = "field \"title\" is not set")
        @JsonProperty("title")
        String title,

        @JsonProperty("brief")
        String brief,

        @NotNull(message = "field \"content\" is not set")
        @JsonProperty("content")
        String content,

        @JsonProperty("cover_image_url")
        String coverImageUrl,

        @JsonProperty("manual_type_tag")
        String manualTypeTag,

        @JsonProperty("secondary_tags")
        List<String> secondaryTags) {

    /**
     * Jackson 的绑定入口 —— 形参刻意声明成 {@code Object}（<b>不是</b> {@code String}）。
     *
     * <p><b>为什么形参必须是 {@code Object}</b>：
     * <ul>
     *   <li>声明成 {@code String} ⇒ Jackson 自己强转（{@code {"title":123}} ⇒ {@code "123"}）
     *       ⇒ 要么被共享层 coercion 拒成 Jackson 措辞的 400，要么（coercion 未挂）被静默接受
     *       ⇒ <b>body 文案与 B 侧不一致</b>；</li>
     *   <li>声明成 {@code Object} ⇒ 由 {@link ArticleStringFields#parse} 判定
     *       ⇒ <b>文案与 B 侧逐字节相同</b>。</li>
     * </ul>
     *
     * <p>⚠️ <b>本构造器抛出的 {@code BadRequestException} 会被 Jackson 包成
     * {@code ValueInstantiationException}</b>（实测 jackson-databind 2.19.4）
     * ⇒ Spring 侧是 {@code HttpMessageNotReadableException} ⇒ <b>400 + text/plain</b>，
     * 且 root cause 仍是 {@code BadRequestException} ⇒ handler 取到的 root message
     * <b>就是 B 侧那句原文</b>。
     *
     * <p>⚠️ <b>record 的规范构造器与本构造器并存</b>：本构造器形参是 {@code Object}，
     * 规范构造器形参是 {@code String} ⇒ Java 的「最具体方法」规则选中规范构造器
     * ⇒ 无歧义；Jackson 侧实测（jackson-databind 2.19.4）<b>无 creator 冲突</b>。
     *
     * <p><b>B 出处</b>：{@code .../dto/CreateArticleReq.java:69-99}（五个 {@code Object} 形参 setter）
     * ＋ {@code .../dto/GoStyleStringFields.java:62-78}（类型判定）。
     */
    @JsonCreator
    public CreateArticleReq(
            @JsonProperty("title") Object rawTitle,
            @JsonProperty("brief") Object rawBrief,
            @JsonProperty("content") Object rawContent,
            @JsonProperty("cover_image_url") Object rawCoverImageUrl,
            @JsonProperty("manual_type_tag") Object rawManualTypeTag,
            @JsonProperty("secondary_tags") List<String> secondaryTags) {
        this(ArticleStringFields.parse("title", rawTitle),
                ArticleStringFields.parse("brief", rawBrief),
                ArticleStringFields.parse("content", rawContent),
                ArticleStringFields.parse("cover_image_url", rawCoverImageUrl),
                ArticleStringFields.parse("manual_type_tag", rawManualTypeTag),
                secondaryTags);
    }

    /**
     * 空 body 的落点 —— B 侧 {@code httpx.Parse} 走
     * {@code mapping.UnmarshalJsonMap(nil, v)}（<b>当空对象继续解析</b>）的等价物。
     *
     * <p>⚠️ <b>为什么需要它</b>：{@code @RequestBody(required = false)} 在 body 缺席时给
     * {@code null} ⇒ Bean Validation <b>不会</b>对 {@code null} 跑（{@code @Valid} 只校验已绑定的对象）
     * ⇒ 「空 body」这条路径<b>拿不到</b> {@link NotNull} 的 400
     * ⇒ 必须在这里显式产出 <b>400 text/plain</b>。
     *
     * <p>⚠️ <b>文案取 B 侧的「缺席」那一条</b>（{@code field "title" is not set}）——
     * B 侧两条必填失败路径的<b>顺序</b>是先判 title（{@code CreateArticleReq.java:109-113}）
     * ⇒ 空 body 时 title 与 content <b>都</b>缺席 ⇒ B 报的是 title 那一条。
     *
     * <p><b>L4 idiomatization（三段式）</b>：
     * <ol>
     *   <li><b>比 B 侧多做了什么</b>：B 侧是 {@code GoStyleRequest.normalize(req, CreateArticleReq::new)}
     *       （B {@code ArticleController.java:126}）补零值 DTO 后交给必填校验；
     *       本仓改为<b>显式的静态方法</b>（record 没有无参构造器可补零值）。</li>
     *   <li><b>语义还一样吗</b>：一样 —— 「空 body ⇒ 400 text/plain」且<b>文案取同一条</b>
     *       ⇒ <b>响应逐字节相同</b>。</li>
     *   <li><b>B 出处</b>：{@code .../controller/ArticleController.java:115-128}
     *       ＋ {@code .../dto/CreateArticleReq.java:108-119}。</li>
     * </ol>
     *
     * @param req Spring 绑定的结果（{@code null} = body 缺席 / 非 JSON）
     * @return 非 null 的入参
     * @throws BadRequestException body 缺席 ⇒ 400 text/plain
     */
    public static CreateArticleReq requireBody(CreateArticleReq req) {
        if (req == null) {
            throw new BadRequestException("field \"title\" is not set");
        }
        return req;
    }
}
