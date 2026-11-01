package com.rag.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * <p><b>⚠️ K-02 · Go「何时才读请求体」的判据字面量（唯一真源）</b>。
 *
 * <p><b>Go 出处</b>：go-zero {@code rest/httpx/parsing.go} 的 {@code ParseRequests}
 * ＋ {@code rest/httpx/util.go} 的 {@code ParseForm} 判定：
 * <pre>
 *   if r.ContentLength &gt; 0 &amp;&amp; strings.Contains(r.Header.Get("Content-Type"), "application/json") {
 *       // 才读 body
 *   }
 * </pre>
 *
 * <p><b>⚠️ 判据的两条硬约束（行为契约，改一个字就是漂移）</b>：
 * <ol>
 *   <li><b>Content-Type 是<b>字面</b> {@code contains}，不是媒体类型解析</b>
 *       ⇒ {@code application/vnd.api+json} <b>也</b>命中（contains 是子串匹配）
 *       ⇒ 而 {@code Application/JSON}（大写）<b>不</b>命中（大小写敏感）
 *       ⇒ ⚠️ <b>不可</b>改用 {@code MediaType.parseMediaType(...)}：那会把头值小写化
 *       ⇒ ⇒ 判定变成大小写<b>不</b>敏感 ⇒ 与 Go <b>相反</b>；</li>
 *   <li><b>Content-Length 必须取 {@code getContentLengthLong()}</b>（int64）
 *       ⇒ ⚠️ {@code getContentLength()} 返回 int，超 2GiB 变 {@code -1}
 *       ⇒ ⇒ 与 Go 的 {@code r.ContentLength}（int64）<b>相反</b>；</li>
 * </ol>
 *
 * <p><b>⚠️ 落点差异（§7「职责对不上 #5」的建议形态）</b>：RAG 侧这段判据是
 * {@code GoStyleJsonBodyConverter} 的静态方法 ⇒ 而 RAG-V1 的
 * {@code JsonBodyConverter}（转换器本体）属<b>批 2</b>（Jackson 3 → 2 回改）
 * ⇒ ⇒ 若本类也引用它，批 1 就编不过 ⇒ ⇒ 故把<b>判据字面量</b>单独落成本类
 * （<b>只保留配置常量 + 判据</b>，注册动作交
 * {@code common/autoconfigure/JsonBodyConverterAutoConfiguration}）
 * ⇒ ⇒ 与 §7 #5 的建议一致：<b>「{@code JsonBodyConverterConfig} 只保留配置常量」</b>。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/http/GoStyleJsonBodyConverter.java}
 * 的静态方法 {@code goReadsBody(String, long)} ＋ {@code goReadsBody(HttpServletRequest)}
 * ＋ 常量 {@code GO_JSON_CONTENT_TYPE_MARK}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson 3 / Boot 4 专有写法 ⇒ 零回改）。
 */
public final class JsonBodyConverterConfig {

    /** Go 的判据字面量，刻意不做媒体类型兼容判定。 */
    static final String GO_JSON_CONTENT_TYPE_MARK = "application/json";

    /**
     * 对应 Go 版 {@code r.ContentLength > 0 && strings.Contains(Content-Type, "application/json")}。
     *
     * @param contentType   <b>原始</b>头值（不可用 {@code MediaType} 解析后的值）
     * @param contentLength 原始长度（int64）
     * @return Go 是否会读这个请求体
     */
    public static boolean goReadsBody(String contentType, long contentLength) {
        return contentLength > 0L
                && contentType != null
                && contentType.contains(GO_JSON_CONTENT_TYPE_MARK);
    }

    /**
     * {@link #goReadsBody(String, long)} 的 <b>servlet 版</b> —— 连「怎么从请求里取这两个输入」
     * 一起收进唯一真源。
     *
     * <p><b>⚠️ 取输入这一步才是真正的坑</b>：
     * <ul>
     *   <li>取 {@code request.getContentType()} ⇒ <b>原始</b>头值 ⇒ 大小写敏感 ⇒ 与 Go 同形 ✔
     *       （真 Tomcat 上逐行实测：{@code org/apache/catalina/connector/Request.java:1081-1083}
     *       → {@code org/apache/coyote/Request.java:694-700} → {@code MessageBytes.toStringType()}
     *       → <b>全链无小写化 / MIME 归一</b>）；</li>
     *   <li>取 {@code request.getHeader("Content-Type")} ⇒ 同样是原始头值 ✔；</li>
     *   <li>取 {@code MediaType.parseMediaType(...).toString()} ⇒ <b>已被小写化</b> ⇒ 判定变成
     *       大小写<b>不</b>敏感 ⇒ 与 Go <b>相反</b> ❌。</li>
     * </ul>
     *
     * <p><b>⚠️ 为什么用 {@code getContentLengthLong()} 而不是 {@code getContentLength()}</b>：
     * {@code org/apache/coyote/Request.java:664-671} 里 {@code getContentLength()} 是
     * {@code (int) getContentLengthLong()} ⇒ 超 2GiB 变 {@code -1}
     * ⇒ 而 Go 的 {@code r.ContentLength} 是 <b>int64</b>，2GiB 以上仍是正数
     * ⇒ ⇒ 必须取 {@code getContentLengthLong()}。
     *
     * @param request servlet 请求（{@code null} 视为「不读」）
     * @return Go 是否会读这个请求体
     */
    public static boolean goReadsBody(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        return goReadsBody(request.getContentType(), request.getContentLengthLong());
    }

    private JsonBodyConverterConfig() {
    }
}
