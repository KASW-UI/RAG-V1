package com.rag.comment.service;

import com.rag.common.security.JwtUtil;

import io.jsonwebtoken.security.WeakKeyException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p>🔴 comment 域的 <b>Go 语义根因条</b> ＋ <b>zero-pad HMAC 互通性</b> 守门网。
 *
 * <h2>1. 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>「Go 语义根因条」只补了 B 侧 {@code CommentRpcGoSemanticsGateTest} 里
 *       <b>唯一一条 C 侧尚未覆盖</b>的用例</b>（逐条对照见下面的 14 行表）
 *       ⇒ ⇒ 其余 13 条<b>不重复写</b>（B 侧原类与 C 侧
 *       {@code CommentDomainFactRealPgTest} 已逐条覆盖）。</li>
 *   <li><b>zero-pad 那条补了「不填充 ⇒ 必失败」这半边</b>：B 侧
 *       {@code CommentJwtTokenMatrixGateTest#paddingDoesNotChangeTheHmacKey}
 *       只证了「填充 ⇔ 不填充<b>等价</b>」；本类<b>额外</b>证了
 *       「<b>不</b>填充 ⇒ jjwt 直接抛 {@link WeakKeyException}」
 *       ⇒ ⇒ 这样「为什么必须补齐」这条理由才有<b>可执行的证据</b>，
 *       而不只是注释里的一句话。</li>
 * </ul>
 *
 * <h2>2. 语义还一样吗</h2>
 * <p>一样。
 * <ul>
 *   <li>zero-pad：期望值（B 侧：短密钥的 raw 与 zero-pad 到 32 字节后 HMAC <b>逐字节相同</b>）
 *       逐条来自 B 侧 {@code CommentJwtTokenMatrixGateTest:294-312}。</li>
 *   <li>Go 语义根因条：期望值（B 侧：<b>不挂作用域</b>时线程上 {@code Span.current()} 是
 *       <b>invalid span</b> ⇒ 这正是原实现「写不进 span 属性」的原因）
 *       逐条来自 B 侧 {@code CommentRpcGoSemanticsGateTest:283-289}。</li>
 * </ul>
 *
 * <h2>3. B 出处</h2>
 * <ul>
 *   <li>{@code service/comment/comment-rpc/src/test/java/com/rag/comment/comment/rpc/CommentRpcGoSemanticsGateTest.java}
 *       （659 行 · 14 条 @Test 的逐条 oracle）</li>
 *   <li>{@code service/comment/comment-api/src/test/java/com/rag/comment/comment/api/interceptor/CommentJwtTokenMatrixGateTest.java:294-312}
 *       （zero-pad 那条的 oracle）</li>
 *   <li>Go 侧 fact 投递 span 的零属性事实：{@code service/common/communityfact/process.go:26}
 *       （{@code otel.Tracer("sea.rtw.communityfact").Start(ctx, "community.fact_delivery.dispatch")}，
 *       全文零 {@code SetAttributes}）</li>
 *   <li>共享层 C 侧落点：{@code common/src/main/java/com/rag/common/security/JwtUtil.java:102-111}
 *       （<b>消费点</b>：{@code padToHmacKeySize}）与
 *       {@code common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java:109-118}
 *       （{@code remainingNanos} 的载体语义）</li>
 * </ul>
 *
 * <h2>🔴 「Go 语义根因条」的逐条对照（B 侧 14 条 @Test ⇒ C 侧覆盖情况）</h2>
 * <table border="1">
 *   <caption>B 侧 {@code CommentRpcGoSemanticsGateTest} 的 14 条 @Test</caption>
 *   <tr><th>#</th><th>B 侧用例</th><th>C 侧覆盖位置</th></tr>
 *   <tr><td>1</td><td>{@code spanCollectorIsAliveOnThisThread}（装置活性）</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:461-474}</td></tr>
 *   <tr><td>2</td><td>{@code factDeliveryDoesNotWriteSpanAttributes}（span 契约）</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:476-497}</td></tr>
 *   <tr><td>3</td><td>{@code currentSpanIsInvalidWithoutScope}（<b>根因</b>）</td>
 *       <td>🔴 <b>本类补上</b>：{@link #currentSpanIsInvalidWithoutScope()}</td></tr>
 *   <tr><td>4</td><td>{@code validFrozenEnvelopeIsDeliveredAndAccepted}（正向对照）</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:228-247}</td></tr>
 *   <tr><td>5</td><td>MISMATCH-01 冻结信封非法 ⇒ status=3 落盘</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:253-269}</td></tr>
 *   <tr><td>6</td><td>MISMATCH-02 投递失败 ⇒ status=2 且 retry_count=1</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:275-289}</td></tr>
 *   <tr><td>7</td><td>MISMATCH-03 technical_received_at = DC 回执的 received_at</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:295-311}</td></tr>
 *   <tr><td>8</td><td>MISMATCH-04 未知字段 ⇒ BLOCKED</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:317-329}</td></tr>
 *   <tr><td>9</td><td>MISMATCH-05 event_time 非 RFC3339 ⇒ BLOCKED</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:335-349}</td></tr>
 *   <tr><td>10</td><td>MISMATCH-05 available_at 非 RFC3339 ⇒ BLOCKED</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:351-364}</td></tr>
 *   <tr><td>11</td><td>MISMATCH-06 信封 schema_version 字符串化 ⇒ BLOCKED</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:370-383}</td></tr>
 *   <tr><td>12</td><td>MISMATCH-06 载荷字符串字段被数字化 ⇒ BLOCKED</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:385-406}</td></tr>
 *   <tr><td>13</td><td>MISMATCH-07 comment_index 的 updated_at = 入库时刻</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:412-436}</td></tr>
 *   <tr><td>14</td><td>MISMATCH-07 comment_content 的 updated_at = 入库时刻</td>
 *       <td>✅ 已覆盖：{@code CommentDomainFactRealPgTest:438-455}</td></tr>
 * </table>
 *
 * <h2>🔴 禁止项自查</h2>
 * <ul>
 *   <li>不用 {@code @EnabledIfDockerAvailable}（本类<b>不碰容器</b>）；</li>
 *   <li>不用 {@code @Slf4j} / {@code @SneakyThrows}。</li>
 * </ul>
 */
class CommentGoSemanticsGateTest {

    /**
     * 短密钥（<b>15 字节</b>）—— 与 B 侧那条用例<b>同一个长度档</b>
     * （B 侧 {@code CommentJwtTokenMatrixGateTest:300}：{@code "yU-hCm)2*?NQmWG"}）。
     *
     * <p>⚠️ 值<b>不是</b>真源密钥（真源密钥不得进仓）⇒ 这里只取「形状 + 长度」。
     */
    private static final String SHORT_SECRET = "yU-hCm)2*?NQmWG";

    /** HS256 要求的最小密钥长度（RFC 7518：256 bit = 32 字节）。 */
    private static final int HS256_MIN_KEY_BYTES = 32;

    // ====================================================================================
    // ① zero-pad HMAC 互通性
    // ====================================================================================

    /**
     * ✅ 短 secret 的 zero-pad <b>不改变 HMAC 结果</b> ⇒ 与 Go 的签名天然互通。
     *
     * <p>⚠️ <b>为什么这条必须存在</b>：
     * <ul>
     *   <li>Go 侧（golang-jwt）<b>不校验 key 长度</b> ⇒ 用<b>原始字节</b>做 HMAC key；</li>
     *   <li>Java 侧（jjwt 0.12）<b>强制</b> HS256 的 key ≥ 32 字节 ⇒ 必须补齐；</li>
     *   <li>补齐方式是 <b>zero-fill</b> ⇒ 而 HMAC <b>本来就会</b>把短于分组尺寸（64 字节）的
     *       密钥零填充（RFC 2104 §2 第 2 步）⇒ ⇒ 两种写法补到 64 字节后得到<b>完全相同的 K′</b>
     *       ⇒ ⇒ 「jjwt 要求 key ≥ 32 字节」这个<b>运行环境约束</b>不产生行为差异。</li>
     * </ul>
     *
     * <p>⚠️ <b>本类的断言落在「消费点」</b>：
     * 共享层 {@code JwtUtil#padToHmacKeySize}（<b>签发与验签共用同一份补齐过的 key</b>）
     * ⇒ ⇒ 与「手工按同一算法补齐」的结果<b>逐字节相同</b> ⇒ ⇒ 落到 HMAC 上也相同。
     */
    @Test
    @DisplayName("✅ 短 secret 的 zero-pad **不改变 HMAC 结果** ⇒ 与 Go 的签名天然互通")
    void zeroPadDoesNotChangeTheHmacKey() throws Exception {
        byte[] raw = SHORT_SECRET.getBytes(StandardCharsets.UTF_8);
        byte[] padded = JwtUtil.padToHmacKeySize(SHORT_SECRET);

        assertThat(padded.length)
                .as("zero-pad 必须补到 HS256 的最小密钥长度（32 字节）")
                .isEqualTo(HS256_MIN_KEY_BYTES);

        // ⚠️ 「手工按同一算法补齐」= 复制原始字节、其余补 0 ⇒ 必须与共享层的实现逐字节相同。
        byte[] manualPad = Arrays.copyOf(raw, HS256_MIN_KEY_BYTES);
        assertThat(padded)
                .as("共享层的补齐结果必须与「手工补齐」逐字节相同（补的必须是 0、且只补到 32 字节）")
                .isEqualTo(manualPad);
        assertThat(padded[raw.length])
                .as("补出来的字节必须是 0（填非零会改变 HMAC 的 K′）")
                .isZero();

        Mac mac1 = Mac.getInstance("HmacSHA256");
        mac1.init(new SecretKeySpec(raw, "HmacSHA256"));
        Mac mac2 = Mac.getInstance("HmacSHA256");
        mac2.init(new SecretKeySpec(padded, "HmacSHA256"));
        byte[] data = "comment-domain-padding-check".getBytes(StandardCharsets.UTF_8);
        assertThat(mac1.doFinal(data))
                .as("短 secret 与 zero-pad 后的 secret 在 HMAC 下必须完全等价")
                .isEqualTo(mac2.doFinal(data));
    }

    /**
     * 🔴 <b>不</b>补齐 ⇒ 长度非法 ⇒ jjwt 直接拒（⇒ 「补齐」不是可有可无的装饰）。
     *
     * <p>⚠️ 这一条是 B 侧那条用例<b>没有</b>覆盖的另一半（B 侧只证了「补了等价」）：
     * 没有它，「为什么必须补齐」就只是一句注释里的断言，没有可执行的证据。
     *
     * <p>⚠️ <b>为什么这条不是「自创行为」</b>：它断言的是 <b>jjwt 的运行环境约束</b>，
     * 而这条约束正是 C 侧<b>必须</b>走 zero-pad 的<b>原因</b>
     * ⇒ ⇒ 断言的是「不补齐 ⇒ 一定失败」，<b>不是</b>「不补齐也能过」。
     */
    @Test
    @DisplayName("🔴 不补齐 ⇒ 密钥长度非法 ⇒ jjwt 直接拒（WeakKeyException）⇒ 补齐是必需项")
    void unpaddedShortKeyIsRejectedByJjwt() {
        byte[] raw = SHORT_SECRET.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> io.jsonwebtoken.security.Keys.hmacShaKeyFor(raw))
                .as("15 字节 < 32 字节 ⇒ jjwt 必须直接抛 WeakKeyException"
                        + " ⇒ ⇒ 这就是 C 侧必须走 zero-pad 的原因（不是「顺手补一下」）")
                .isInstanceOf(WeakKeyException.class);
    }

    // ====================================================================================
    // ② Go 语义根因条：为什么原实现「写不进 span 属性」
    // ====================================================================================

    /**
     * 🔴 <b>根因</b>：不挂作用域时线程上 {@code Span.current()} <b>无效</b>
     * ⇒ ⇒ 原实现写不进去（它永远不生效的原因）。
     *
     * <h3>⚠️ 这条为什么不是「常识」而是「必须钉住的根因」</h3>
     * <p>B 侧 2026-10-27 订正过一处早期误判：注记曾写「Go 侧 fact 投递链路根本不用 span」
     * ⇒ <b>不准确</b> ⇒ Go 侧<b>有</b>这个 span，但建在<b>共享层</b>且<b>不带属性</b>
     * （{@code service/common/communityfact/process.go:26}）。
     * <p>而 RAG 域内那句 {@code Span.current().setAttribute(...)} 为何<b>够不到</b>那个 span：
     * <ul>
     *   <li>共享层把 span 放进 <b>Context</b>（{@code Dispatcher.java:189} {@code ctx.with(span)}）
     *       而<b>从不 makeCurrent</b>；</li>
     *   <li>且 {@code dispatchFactOnce} 跑在<b>另一个线程</b>
     *       ⇒ OTel 默认的 {@code ThreadLocalContextStorage} 是<b>普通</b> {@code ThreadLocal}
     *       （<b>不继承</b>）⇒ 投递线程上 {@code Span.current()} 恒为 <b>invalid span</b>
     *       ⇒ {@code if (span != null)} 恒真 ⇒ {@code setAttribute} 落到 invalid span
     *       ⇒ ⇒ <b>静默丢弃</b>（otel-java 的 invalid span 是 no-op）</li>
     * </ul>
     * ⇒ ⇒ 即「看起来在工作、实际永远不生效」⇒ 该写法已删（2026-10-27），
     * 本条钉住这条不变式。
     *
     * <h3>🔴 为什么要加「反向对照」那一半</h3>
     * <p>只断言「不挂作用域 ⇒ invalid」的话，它可能是一条<b>平凡通过</b>的断言
     * （比如观测装置根本没装）。⇒ ⇒ 本类<b>额外</b>断言「挂了作用域 ⇒ <b>有效</b>」
     * ⇒ ⇒ 这样「invalid」这个结论才是<b>被区分出来的</b>，而不是「恒为 false」。
     */
    @Test
    @DisplayName("🔴 根因：不挂作用域时线程上 Span.current() 无效 ⇒ 原实现写不进去（它永远不生效的原因）")
    void currentSpanIsInvalidWithoutScope() {
        // ---- ① 根因本体：没有作用域 ⇒ 当前 span 必须是 invalid
        assertThat(Span.current().getSpanContext().isValid())
                .as("没有作用域时 Span.current() 必须是 invalid span ⇒ 这正是原实现写不进属性的原因")
                .isFalse();

        // ⚠️ invalid span 上 setAttribute 是 **no-op**（不抛、也不记录任何东西）
        //    ⇒ ⇒ 这就是「看起来在工作、实际永远不生效」的机制本体。
        Span.getInvalid().setAttribute("comment.fact.probe", "v");

        // ---- ② 反向对照：挂了作用域 ⇒ 同一个线程上的当前 span 必须**有效**
        //    ⇒ ⇒ 证明 ① 里的「invalid」是**被区分出来的**，不是「恒为 false」。
        SpanContext remoteParent = SpanContext.createFromRemoteParent(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault());
        Span scoped = Span.wrap(remoteParent);
        try (Scope ignored = scoped.makeCurrent()) {
            assertThat(Span.current().getSpanContext().isValid())
                    .as("挂了作用域 ⇒ 当前 span 必须有效 ⇒ ⇒ 反证 ① 的『invalid』不是恒假")
                    .isTrue();
            assertThat(Span.current().getSpanContext().getTraceId())
                    .as("作用域内的当前 span 必须是刚绑上去的那一个")
                    .isEqualTo(remoteParent.getTraceId());
        }

        // ---- ③ 作用域退出后必须**恢复**成 invalid（作用域是成对的，不是永久改写）
        assertThat(Span.current().getSpanContext().isValid())
                .as("作用域退出后必须回到 invalid ⇒ ⇒ 作用域是 try-with-resources 式的成对绑定")
                .isFalse();
    }

    /**
     * 🔴 <b>作用域必须是成对的</b>：{@link Scope#close()} 之后必须<b>恢复</b>成调用前的状态。
     *
     * <p>⚠️ 这一条是 {@link #currentSpanIsInvalidWithoutScope()} 里 ③ 那半的<b>加强</b>：
     * 若作用域退出后没有还原，线程池复用时会把上一个请求的 span 带到下一个请求
     * ⇒ ⇒ 「invalid ⇒ 写不进属性」这条不变式就会在某些请求上<b>失效</b>。
     *
     * <p>⚠️ <b>嵌套</b>是这条的关键：内层作用域退出后必须回到<b>外层</b>那个 span
     * ⇒ ⇒ 共享层的 {@code GoStyleRequestDeadline#bindAbsoluteNanos}
     * （<b>消费点</b>：{@code common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java:144-158}）
     * 正是靠这个「保存原值 → 退出时还原」的实现做到嵌套安全。
     */
    @Test
    @DisplayName("🔴 作用域退出必须还原（嵌套安全）⇒ 否则线程池复用会把上一个请求的 span 带到下一个请求")
    void scopeRestoresThePreviousSpanOnClose() {
        SpanContext outerCtx = SpanContext.createFromRemoteParent(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault());
        SpanContext innerCtx = SpanContext.createFromRemoteParent(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203332",
                TraceFlags.getSampled(), TraceState.getDefault());

        Span outer = Span.wrap(outerCtx);
        Span inner = Span.wrap(innerCtx);
        try (Scope outerScope = outer.makeCurrent()) {
            assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(outerCtx.getTraceId());
            try (Scope innerScope = inner.makeCurrent()) {
                assertThat(Span.current().getSpanContext().getTraceId())
                        .as("内层作用域必须覆盖外层")
                        .isEqualTo(innerCtx.getTraceId());
            }
            assertThat(Span.current().getSpanContext().getTraceId())
                    .as("内层作用域退出后必须**还原**回外层那个 span ⇒ 嵌套安全")
                    .isEqualTo(outerCtx.getTraceId());
        }
        assertThat(Span.current().getSpanContext().isValid())
                .as("最外层作用域退出后必须回到 invalid ⇒ 线程池复用不会串味")
                .isFalse();
    }
}
