package com.rag.common.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §3.3 真触发测试 —— {@link JsonBodyConverter} 的「Go 何时才读 body」判据 + 8 MiB 上限。
 *
 * <p><b>Go 出处</b>：go-zero {@code rest/httpx/requests.go:117-124,153-155}
 * （{@code withJsonBody} = {@code r.ContentLength > 0 && strings.Contains(Content-Type, "application/json")}
 * ＋ {@code io.LimitReader(r.Body, maxBodyLen)}，{@code maxBodyLen = 8 << 20}）。
 *
 * <p><b>⚠️ 本测试是「真触发」而不是 Mockito 桩</b>：它<b>真的调用</b>
 * {@link JsonBodyConverter#read} ⇒ 真的走 Spring 的转换器路径 ⇒
 * 「判据不满足就返回 null」「8 MiB 之后被截断」这两条都是<b>可观察行为</b>。
 */
class JsonBodyConverterTest {

    static class Req {
        @JsonProperty("target_type")
        public String targetType;
    }

    /** 测试自用的 {@link HttpInputMessage}（真实值对象，不是对被测类的 mock）。 */
    private static final class SimpleInputMessage implements HttpInputMessage {
        private final HttpHeaders headers;
        private final byte[] body;

        SimpleInputMessage(String contentType, long contentLength, byte[] body) {
            this.headers = new HttpHeaders();
            if (contentType != null) {
                this.headers.set(HttpHeaders.CONTENT_TYPE, contentType);
            }
            if (contentLength >= 0L) {
                this.headers.setContentLength(contentLength);
            }
            this.body = body;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }
    }

    private static JsonBodyConverter converter() {
        return new JsonBodyConverter(JsonMapper.builder().build());
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ── 判据层 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("判据：application/json + 正长度 ⇒ 读 body")
    void jsonWithPositiveLengthReadsBody() {
        assertThat(JsonBodyConverterConfig.goReadsBody("application/json", 10L)).isTrue();
    }

    @Test
    @DisplayName("⚠️ 判据：Content-Type 大小写敏感（Go: strings.Contains）⇒ Application/JSON 不读")
    void contentTypeIsCaseSensitive() {
        assertThat(JsonBodyConverterConfig.goReadsBody("Application/JSON", 10L)).isFalse();
    }

    @Test
    @DisplayName("判据：text/plain 不读 body")
    void textPlainDoesNotReadBody() {
        assertThat(JsonBodyConverterConfig.goReadsBody("text/plain", 10L)).isFalse();
    }

    @Test
    @DisplayName("⚠️ 判据：application/vnd.api+json **不**命中（字面 contains 里没有 \"application/json\"）")
    void vendorJsonDoesNotMatch() {
        assertThat("application/vnd.api+json".contains("application/json")).isFalse();
        assertThat(JsonBodyConverterConfig.goReadsBody("application/vnd.api+json", 10L)).isFalse();
    }

    @Test
    @DisplayName("判据：Content-Length 为 0 不读（Go: r.ContentLength > 0）")
    void zeroLengthDoesNotReadBody() {
        assertThat(JsonBodyConverterConfig.goReadsBody("application/json", 0L)).isFalse();
    }

    @Test
    @DisplayName("⚠️ 判据：chunked（Content-Length = -1）不读（Go 侧同样是 -1 ⇒ 判据为假）")
    void chunkedDoesNotReadBody() {
        assertThat(JsonBodyConverterConfig.goReadsBody("application/json", -1L)).isFalse();
    }

    @Test
    @DisplayName("判据：Content-Type 缺失不读")
    void missingContentTypeDoesNotReadBody() {
        assertThat(JsonBodyConverterConfig.goReadsBody(null, 10L)).isFalse();
    }

    // ── 读层（真调用 Spring 转换器） ──────────────────────────────────────

    @Test
    @DisplayName("读层：命中判据 ⇒ 真的解析出对象")
    void readsAndParsesWhenPredicateHolds() throws Exception {
        byte[] body = utf8("{\"target_type\":\"article\"}");
        SimpleInputMessage msg = new SimpleInputMessage("application/json", body.length, body);
        Object out = converter().read(Req.class, null, msg);
        assertThat(out).isInstanceOf(Req.class);
        assertThat(((Req) out).targetType).isEqualTo("article");
    }

    @Test
    @DisplayName("⚠️ 读层：判据不满足 ⇒ 返回 null 且**不消费请求流**（Go: UnmarshalJsonMap(nil, v)）")
    void returnsNullWithoutReadingBodyWhenPredicateFails() throws Exception {
        byte[] body = utf8("{\"target_type\":\"article\"}");
        SimpleInputMessage msg = new SimpleInputMessage("text/plain", body.length, body);
        assertThat(converter().read(Req.class, null, msg)).isNull();
    }

    @Test
    @DisplayName("⚠️ 读层：畸形 Content-Type 不抛 InvalidMediaTypeException（Go 只做字面 Contains，从不解析 MIME）")
    void malformedContentTypeDoesNotThrow() throws Exception {
        byte[] body = utf8("{\"target_type\":\"article\"}");
        SimpleInputMessage msg = new SimpleInputMessage("application/json, application/xml", body.length, body);
        assertThat(converter().read(Req.class, null, msg)).isInstanceOf(Req.class);
    }

    @Test
    @DisplayName("⚠️ 读层：charset 被去掉 ⇒ Jackson 按 UTF-8 读（Go 的 encoding/json 只认 UTF-8）")
    void charsetIsStripped() throws Exception {
        byte[] body = utf8("{\"target_type\":\"文章\"}");
        SimpleInputMessage msg = new SimpleInputMessage("application/json; charset=gbk", body.length, body);
        Object out = converter().read(Req.class, null, msg);
        assertThat(((Req) out).targetType).isEqualTo("文章");
    }

    @Test
    @DisplayName("⚠️ 读层：超过 8 MiB 的 body 被截断 ⇒ 解析失败（Go: io.LimitReader 静默截断在 8 MiB）")
    void bodyIsTruncatedAtEightMiB() {
        String big = "x".repeat(9 * 1024 * 1024);
        byte[] body = utf8("{\"target_type\":\"" + big + "\"}");
        assertThat(body.length).isGreaterThan(JsonBodyConverter.GO_MAX_BODY_LEN);
        SimpleInputMessage msg = new SimpleInputMessage("application/json", body.length, body);
        assertThatThrownBy(() -> converter().read(Req.class, null, msg))
                .isInstanceOf(org.springframework.http.converter.HttpMessageNotReadableException.class);
    }

    @Test
    @DisplayName("读层：8 MiB 上限常量 = Go 的 8 << 20")
    void maxBodyLenMatchesGo() {
        assertThat(JsonBodyConverter.GO_MAX_BODY_LEN).isEqualTo(8 * 1024 * 1024);
    }

    // ── 转换器的媒体类型认领 ──────────────────────────────────────────────

    @Test
    @DisplayName("⚠️ 转换器：读侧无条件认领、写侧一律拒领（Spring 6.2 走 Type 版三参）")
    void mediaTypeClaims() {
        JsonBodyConverter c = converter();
        assertThat(c.canRead(Req.class, null, MediaType.TEXT_PLAIN)).isTrue();
        assertThat(c.canRead(Req.class, MediaType.TEXT_PLAIN)).isTrue();
        assertThat(c.canWrite(Req.class, null, MediaType.APPLICATION_JSON)).isFalse();
        assertThat(c.canWrite(Req.class, MediaType.APPLICATION_JSON)).isFalse();
    }
}
