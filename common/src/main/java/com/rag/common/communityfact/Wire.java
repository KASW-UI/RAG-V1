package com.rag.common.communityfact;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * <p>共享层 wire 协议的<b>门面</b> —— 对应 B 侧
 * {@code service/common/src/main/java/com/rag/common/communityfact/Wire.java} 的包级函数与哨兵。
 *
 * <p><b>⚠️ 拆分类的落点依据（映射表 §1.2 / 决策锁 L-7 第 8 条）</b>：
 * RAG 侧把 4 个 wire 类型 + 校验 + sender <b>全塞进一个 {@code Wire.java}</b>；
 * RAG-V1 按「1 类型 1 文件」拆分 ⇒ 本类<b>只保留</b>：
 * <ul>
 *   <li>{@link #strictDecode} —— 严格反序列化（禁未知字段 + 禁尾随 JSON）；</li>
 *   <li>{@link #validateReceipt} —— receipt 校验；</li>
 *   <li>{@link #parseRTWSubject} —— subject 解析；</li>
 *   <li>{@link #Result} —— B 侧 多返回值的 Java 包装；</li>
 *   <li>2 个 sentinel（{@link #ERR_FROZEN_ENVELOPE_BLOCKED} / {@link #ERR_AUTHORITY_UNAVAILABLE}）。</li>
 * </ul>
 * ⇒ 拆出去的：4 个 wire 类型 ⇒ {@code Event} / {@code TechnicalReceipt} /
 * {@code SubjectRef} / {@code AuthorityFact}；{@code DeliveryStatus}；
 * {@code CanonicalHash}（含 {@code validHash}）；{@code EventSender}。
 *
 * <p><b>⚠️ 拆出后调用点要改 import</b>：RAG 侧的 {@code Wire.canonicalHash(...)} ⇒
 * RAG-V1 侧改为 {@code CanonicalHash.canonicalHash(...)}（**类名 + 包名都变了**）⇒
 * 迁移时须同步改 import（映射表 §1.5 第 167 行已就 logger 类记过同款纪律）。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/Wire.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类 Jackson 写法在 RAG 侧<b>本就是 Jackson 2</b> ⇒ 零回改）。
 */
public final class Wire {

    /**
     * frozen envelope 被阻止投递的哨兵异常类型（对应 B 侧版 {@code var ErrFrozenEnvelopeBlocked}）。
     *
     * <p><b>M16 修复</b>：此前 {@code ERR_FROZEN_ENVELOPE_BLOCKED} 是一个裸
     * {@code RuntimeException} 实例，{@code Dispatcher.deliveryErrorCode} 靠
     * 「类型相同 或 message 字符串相等」来识别 —— 后者意味着任何一条<b>恰好同文案</b>的
     * 业务异常都会被误判成 frozen envelope 阻塞，错误码从
     * {@code DELIVERY_ATTEMPT_FAILED} 变成 {@code INVALID_FROZEN_ENVELOPE}。
     * 改成专用类型后，识别方式与 B 的 {@code errors.Is(err, ErrFrozenEnvelopeBlocked)} 一致：
     * 只认类型，不看文案。
     */
    public static class FrozenEnvelopeBlockedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public FrozenEnvelopeBlockedException(String message) {
            super(message);
        }

        public FrozenEnvelopeBlockedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 错误：frozen envelope 被阻止投递。
     *
     * <p>{@code final} 是刻意的：哨兵必须是单例常量，否则 {@code ==} 身份比较不成立。
     */
    public static final FrozenEnvelopeBlockedException ERR_FROZEN_ENVELOPE_BLOCKED =
            new FrozenEnvelopeBlockedException("community fact frozen envelope blocked");

    /**
     * 错误：authority 不可用。
     *
     * <p><b>M13 修复</b>：此前这里声明的是普通 {@code RuntimeException}，而
     * {@code AuthorityHandler.isAuthorityUnavailable} 只认
     * {@code AuthorityUnavailableException} —— 于是这个「最顺手的常量」实际上<b>是死的</b>：
     * 业务按直觉抛 {@code Wire.ERR_AUTHORITY_UNAVAILABLE}，会走到 503 分支而不是 B 侧版的 404。
     * 现在它本身就是 {@link AuthorityHandler.AuthorityUnavailableException} 的实例，
     * 两条路径识别同一件事。
     */
    public static final RuntimeException ERR_AUTHORITY_UNAVAILABLE =
            new AuthorityHandler.AuthorityUnavailableException("community fact authority unavailable");

    /**
     * ObjectMapper 是 Jackson 库的类，负责 java-&gt;json 互相转化
     * .configure() 是 ObjectMapper 的一个
     */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    /**
     * 对应 B 侧版 {@code func StrictDecode(reader io.Reader, value any) error}：
     * 用 {@code DisallowUnknownFields()} 反序列化，禁止尾部多余 JSON。
     */
    public static <T> T strictDecode(InputStream in, Class<T> valueType) throws IOException {
        try (JsonParser parser = MAPPER.getFactory().createParser(in)) {
            T result = parser.readValueAs(valueType);
            if (parser.nextToken() != null) {
                throw new IOException("trailing JSON");
            }
            return result;
        }
    }

    /** Reader 重载，方便上游按字符串反序列化时调用。 */
    public static <T> T strictDecode(Reader reader, Class<T> valueType) throws IOException {
        try (JsonParser parser = MAPPER.getFactory().createParser(reader)) {
            T result = parser.readValueAs(valueType);
            if (parser.nextToken() != null) {
                throw new IOException("trailing JSON");
            }
            return result;
        }
    }

    /**
     * 对应 B 侧版 {@code func ValidateReceipt(event Event, receipt TechnicalReceipt) (time.Time, bool)}：
     * 校验 event/producer/status/receiptId/inputHash/offset，并通过 RFC3339Nano 解析 received_at。
     */
    public static Result validateReceipt(Event event, TechnicalReceipt receipt) {
        if (receipt == null || receipt.eventId == null || !receipt.eventId.equals(event.eventId)
                || receipt.producer == null || !receipt.producer.equals(event.producer)
                || !"accepted".equals(receipt.technicalStatus)
                || receipt.receiptId == null || receipt.receiptId.isEmpty()
                || !CanonicalHash.validHash(receipt.inputHash)
                || receipt.offset < 1L) {
            return new Result(null, false);
        }
        // DRIFT-COMMON-002 修复：receivedAt 缺失时 B 侧 返回 (零值, false)，不抛。
        // B 出处 service/common/src/main/java/com/rag/common/communityfact/Wire.java:352:148-149 `received, err := time.Parse(time.RFC3339Nano, receipt.ReceivedAt);
        // return received.UTC(), err == nil` —— "" 走的是 err != nil 分支。
        // Java 侧 Instant.parse(null) 抛 NullPointerException，不在 DateTimeParseException 的
        // catch 范围内 ⇒ 异常向上冒泡。上面那个守卫块对另外 7 个字段都做了 null 防御，唯独漏了它。
        if (receipt.receivedAt == null || receipt.receivedAt.isEmpty()) {
            return new Result(null, false);
        }
        Instant received;
        try {
            received = Instant.parse(receipt.receivedAt);
        } catch (DateTimeParseException e) {
            return new Result(null, false);
        }
        return new Result(received, true);
    }

    /**
     * 对应 B 侧版 {@code func ParseRTWSubject(value string) (SubjectRef, bool)}：
     * 解析 "rtw.identity/platform/{uid}" 形式，校验 uid &gt; 0 且为整数字面量。
     */
    public static Result parseRTWSubject(String value) {
        final String prefix = "rtw.identity/platform/";
        if (value == null || !value.startsWith(prefix)) {
            return new Result(null, false);
        }
        String id = value.substring(prefix.length());
        long uid;
        try {
            uid = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return new Result(null, false);
        }
        if (uid <= 0 || !String.valueOf(uid).equals(id)) {
            return new Result(null, false);
        }
        return new Result(new SubjectRef("rtw.identity", id), true);
    }

    /**
     * 对应 B 侧版多返回值 (time.Time, bool) / (SubjectRef, bool) 的 Java 包装。
     */
    public static final class Result<T> {
        public final T value;
        public final boolean ok;

        public Result(T value, boolean ok) {
            this.value = value;
            this.ok = ok;
        }

        public T get() {
            return value;
        }

        public boolean isOk() {
            return ok;
        }
    }

    /**
     * DC 拒绝 frozen envelope 的 sentinel 异常。
     * <p>对应 B 侧版 {@code fmt.Errorf("DataCenter event acceptance status %d", response.StatusCode)}；
     * Java 用 sentinel + 状态码字段，让业务层可以 {@code instanceof} 识别并附带 http status。
     */
    public static final class DataCenterAcceptanceRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        public DataCenterAcceptanceRejectedException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    private Wire() {
    }
}
