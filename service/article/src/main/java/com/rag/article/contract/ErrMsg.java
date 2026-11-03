package com.rag.article.contract;

/**
 * <p><b>职责</b>：article 域的<b>错误文案查表</b> —— {@code int 码 → 文案}。
 *
 * <p><b>B 出处</b>：{@code service/article/article-common/src/main/java/com/rag/article/article/common/errmsg/ErrorCode.java}
 * 的 {@code getMsg(int)}（{@code :251-253}）＋ {@code of(int)}（{@code :226-228}）
 * ⇒ <b>整段照抄</b>（本类只承载查表入口，码值表在 {@link ErrCodes}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.article.article.common.errmsg} →
 *       {@code com.rag.article.contract}，D7 包压平）＋ <b>本段 javadoc</b>补上「为何与码值表分离」。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 查表来源与回落语义逐字一致（见下方「回落语义是唯一有分支的行为」）。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 硬约束：错误契约<b>逐域隔离</b>，不可跨域统一</h2>
 * <p>本类的文案是<b>客户端可见</b>的（HTTP 信封的 {@code msg} 字段）⇒ 逐字照抄 B 侧，
 * <b>含语言</b>（article 域是中文）。
 *
 * <h2>回落语义是本组件唯一有分支的行为，必须逐字对齐</h2>
 * <p>B 侧 {@code GetErrMsg(code)} 的回落是 {@code codeMsg[Error]} = {@code "FAIL"}，
 * <b>不是</b> {@code null}、<b>不是</b>空串、<b>不是</b>把 code 拼成字符串。
 * <p>可观察后果：任何把「RPC 侧来的、未在本表登记」的码透传到 api 信封的路径，
 * 用户看到的一定是 {@code "FAIL"}。若改成返回 null，信封的 {@code msg} 会变成 {@code "null"}
 * 或被序列化层省略 —— 那是客户端可见的行为漂移。
 *
 * <h2>为什么与 {@link ErrCodes} 分成两个类</h2>
 * <p>B 侧是<b>一个枚举</b>（码值与文案同体）。本仓按 D5 + D11 拆成两个类：
 * {@link ErrCodes}（码值 ＋ 逐条文案）＋ 本类（{@code int → 文案} 的查表入口）。
 * ⇒ <b>拆分只改表达层</b>：码值与文案本身一字未动，回落语义也逐字保留。
 */
public final class ErrMsg {

    private ErrMsg() {
        // 文案查表工具类，禁止实例化
    }

    /**
     * <b>对应 B 侧 {@code func GetErrMsg(code int) string}（{@code errmsg.go:39-45}）。</b>
     *
     * <pre>
     * func GetErrMsg(code int) string {
     *     msg, ok := codeMsg[code]
     *     if !ok {
     *         return codeMsg[Error]     // ← 未命中回落到 "FAIL"
     *     }
     *     return msg
     * }
     * </pre>
     *
     * <p><b>回落语义是本组件唯一有分支的行为，必须逐字对齐</b>：
     * 未知码返回 {@link ErrCodes#ERROR} 的文案 {@code "FAIL"}，
     * <b>不是</b> {@code null}、<b>不是</b>空串、<b>不是</b>把 code 拼成字符串。
     *
     * <p>B 侧 {@code ErrorCode.getMsg(int)}（{@code :251-253}）⇒ 逐字照抄。
     *
     * @param code 业务码（int）
     * @return 该码的文案；未登记的码返回 {@code "FAIL"}
     */
    public static String getMsg(int code) {
        return ErrCodes.of(code).getMsg();
    }
}
