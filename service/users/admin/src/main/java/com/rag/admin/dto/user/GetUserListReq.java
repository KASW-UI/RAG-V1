package com.rag.admin.dto.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * <p><b>职责</b>：admin getUserList 用例的 HTTP 请求 DTO —— 分页（page / page_size）+ 搜索关键字。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：可变 class + setter → <b>immutable record</b>（D5）；
 *       B 侧手写 {@code assertGoRequiredFields()} → Bean Validation {@code @NotNull}（D-01 降级）；
 *       B 侧的 {@code setKeyword(Object)} <b>setter</b> → <b>{@code @JsonCreator} 构造器</b>
 *       （record 没有 setter ⇒ 同一段强转逻辑改在构造期完成，语义逐条照抄）。
 *       {@code DEFAULT_PAGE} / {@code DEFAULT_PAGE_SIZE} / {@code pageOrDefault()} /
 *       {@code pageSizeOrDefault()} 逐字照抄。</li>
 *   <li><b>语义还一样吗</b>：一样。① 分页默认值口径不变（null == 未传 ⇒ 套 default；
 *       显式 0 / 负数<b>原样保留</b>，好让下游按 B 侧行为算出负 OFFSET 并报错，而不是静默返回首页）；
 *       ② 必填判定口径一致（B 侧只判 null ⇒ 用 {@code @NotNull} 而非 {@code @NotBlank}）；
 *       ③ {@code keyword} 的标量强转语义逐条照抄（见下方「⚠️ keyword 强转」）。</li>
 *   <li><b>B 出处</b>：{@code service/users/admin/admin-api/src/main/java/com/rag/users/admin/api/dto/GetUserListReq.java}</li>
 * </ol>
 *
 * <p><b>必填口径（B 侧 {@code assertGoRequiredFields} 逐条）</b>：<b>只有 {@code keyword}</b> 必填；
 * {@code page} / {@code page_size} 在 B 侧带 {@code form:"...,default=N"} ⇒
 * {@code unmarshaler.go:918} 的 {@code processNamedFieldWithoutValue} 先判 default、填值即返回，
 * <b>不走必填分支</b>（实测：drop keyword ⇒ 400；缺 page / page_size ⇒ 200）⇒ <b>这两个组件不加约束</b>。
 *
 * <p><b>⚠️ keyword 强转（必须保留，E-3 根因 R1 的配套处理）</b>：
 * B 侧该字段走 <b>form（query string）通道</b>，值天然是字符串（{@code keyword=99999} 在 B 侧就是
 * {@code "99999"}，<b>完全合法</b>）；而 RAG 把该端点放在 JSON body 上 ⇒ 客户端可以发
 * {@code {"keyword":99999}}。若强转成 {@code String} 组件，Spring Boot 默认关闭的
 * {@code MapperFeature.ALLOW_COERCION_OF_SCALARS} 会把它挡成 400 —— 而 B 侧接受 ⇒ 等于把 R1 修成一条新漂移。
 * ⇒ 本实现<b>刻意收 {@code Object}</b> 并在构造期把<b>标量</b>字符串化：
 * {@code String} 原样；{@code Number} / {@code Boolean} 用 {@code String.valueOf} 转字符串；
 * <b>其它类型（array / object）必须抛 {@link IllegalArgumentException}</b> ——
 * 因为 query string 的值只能是标量，非标量在 B 侧根本到不了这个字段
 * （真发过去 B 侧 会得到 {@code type mismatch for field "keyword"}），
 * 若在此处 {@code String.valueOf} 会把 {@code {"keyword":{"a":1}}} 变成 {@code "{a=1}"} 再去 LIKE 匹配，
 * 是个<b>静默的错误结果</b>。{@code null} 原样保留，仍由 {@code @NotNull} 判必填。
 *
 * @param page     页码（B 侧 default 1；<b>不加</b> {@code @NotNull}）
 * @param pageSize 每页条数，wire 键名 {@code page_size}（B 侧 default 20；<b>不加</b> {@code @NotNull}）
 * @param keyword  搜索关键字（必填）
 */
public record GetUserListReq(
        Long page,
        @JsonProperty("page_size") Long pageSize,
        @JsonInclude(JsonInclude.Include.NON_NULL) @NotNull String keyword) {

    /** 默认值 1 —— 对应 B 侧 tag {@code form:"page,default=1"} */
    public static final long DEFAULT_PAGE = 1L;

    /** 默认值 20 —— 对应 B 侧 tag {@code form:"page_size,default=20"} */
    public static final long DEFAULT_PAGE_SIZE = 20L;

    /**
     * Jackson 反序列化入口 —— 把 {@code keyword} 的标量强转收在构造期（record 没有 setter）。
     *
     * <p>语义与 B 侧 {@code setKeyword(Object raw)} 逐条一致（见类型 javadoc 的「⚠️ keyword 强转」）。
     */
    @JsonCreator
    public GetUserListReq(@JsonProperty("page") Long page,
                          @JsonProperty("page_size") Long pageSize,
                          @JsonProperty("keyword") Object keywordRaw) {
        this(page, pageSize, coerceKeyword(keywordRaw));
    }

    /**
     * 标量强转 —— 逐字照抄 B 侧 {@code setKeyword(Object raw)}。
     *
     * @param raw Jackson 交过来的原始值（可能是 {@code String} / {@code Number} / {@code Boolean} / 其它）
     * @return 字符串化的关键字（非标量 ⇒ 抛 {@link IllegalArgumentException}）
     */
    private static String coerceKeyword(Object raw) {
        if (raw == null || raw instanceof String) {
            return (String) raw;
        }
        if (raw instanceof Number || raw instanceof Boolean) {
            return String.valueOf(raw);
        }
        throw new IllegalArgumentException(
                "keyword must be a scalar (B 侧 form 通道只可能传标量)，实际收到 "
                        + raw.getClass().getName());
    }

    /**
     * 取 page 并套用 B 侧 tag 的 default：null（未传）→ 1；显式 0 / 负数<b>原样保留</b>。
     * 保留是为了让下游按 B 侧的行为算出负 OFFSET 并报错，而不是静默返回首页。
     */
    public long pageOrDefault() {
        return page == null ? DEFAULT_PAGE : page;
    }

    /** 同 {@link #pageOrDefault()}，default = 20。 */
    public long pageSizeOrDefault() {
        return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }
}
