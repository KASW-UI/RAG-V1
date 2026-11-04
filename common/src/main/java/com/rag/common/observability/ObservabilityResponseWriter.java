package com.rag.common.observability;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <b>观测用的响应记录器</b> —— 捕获响应体，并允许在超时 / 断连之后<b>掐掉</b>底层的提交。
 *
 * <p><b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/observability/HttpMiddleware.java:1105}
 * <pre>
 * type observabilityResponseWriter struct {
 *     header http.Header
 *     body   bytes.Buffer
 *     status int
 *     mu       sync.Mutex
 *     timedOut bool
 * }
 *
 * func (w *observabilityResponseWriter) Write(p []byte) (int, error) {
 *     w.mu.Lock(); defer w.mu.Unlock()
 *     if w.timedOut { return 0, http.ErrHandlerTimeout }
 *     return w.body.Write(p)
 * }
 * func (w *observabilityResponseWriter) flushTo(dst http.ResponseWriter) { ... }
 * </pre>
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/HttpMiddleware.java}
 * 的嵌套类 {@code ObservabilityResponseWriter} 整段抄（L2 业务语义层逐条保留 · D13）
 * ＋ <b>从嵌套类提到顶层</b>（L4 · D11 包压平）。
 *
 * <h2>🔴 为什么必须有它（不能直接用 Spring 的 {@code ContentCachingResponseWrapper}）</h2>
 * <p>Spring 的 {@code ContentCachingResponseWrapper.flushBuffer()} 会<b>透传</b>到底层
 * ⇒ ⇒ worker 线程（业务）能<b>抢先提交响应</b> ⇒ 两个写者 ⇒
 * ① 状态码被抢（客户端收到 200 而不是 503）
 * ② Tomcat 的 {@code MimeHeaders} 被写坏 ⇒ NPE ⇒ 客户端只看到 EOF。
 * <p>⇒ ⇒ 本类覆写 {@link #flushBuffer()}：<b>超时 / 断连之后一律 no-op</b>
 * （B 侧 {@code Write} 在 {@code timedOut} 时返回 {@code http.ErrHandlerTimeout} ⇒ 同款语义）。
 *
 * <h2>🔴 为什么要有 {@code underlyingLock}（底层响应的写锁）</h2>
 * <p>worker 线程（业务）与容器线程（超时 / 断连分支）<b>都可能碰底层响应</b>。
 * Tomcat 的 {@code MimeHeaders.createHeader()} 是
 * ⇒ 两个线程同时加头时，各自 {@code count++} 却只填了一个槽位 ⇒ <b>数组里留 null 洞</b>
 * ⇒ 随后任何线程读头 ⇒ <b>NPE</b> ⇒ 响应一个字都发不出去（客户端只看到 EOF）。
 * <p>⇒ ⇒ 这把锁把「对底层响应的所有修改」串行化 ⇒ 洞不可能出现。
 */
public class ObservabilityResponseWriter extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final Map<String, String> capturedHeaders = new LinkedHashMap<>();
    private final HttpServletResponse original;
    private int status = 200;
    private volatile boolean timedOut = false;
    private volatile boolean clientCancelled = false;

    /**
     * 底层响应的写锁（见类 javadoc「为什么要有 underlyingLock」）。
     */
    private final ReentrantLock underlyingLock = new ReentrantLock();

    public ObservabilityResponseWriter(HttpServletResponse original) {
        super(original);
        this.original = original;
        // ⚠️ **继承底层已有的状态码**（而不是硬编码 200）：
        //   若有人在 filter 之前设过状态码（少见但合法），本记录器必须看到它
        //   ⇒ 否则观测层会把「503」记成「200」。
        this.status = (original == null) ? 200 : original.getStatus();
    }

    /** 对底层响应做一次写：<b>加锁</b> ＋ <b>标志检查</b>（超时 / 断连后一律 no-op）。 */
    private void writeToOriginal(OriginalAction action) {
        underlyingLock.lock();
        try {
            if (timedOut || clientCancelled) {
                return;
            }
            action.run(this.original);
        } finally {
            underlyingLock.unlock();
        }
    }

    /** 写动作（不抛 {@code IOException} 的那批）。 */
    @FunctionalInterface
    private interface OriginalAction {
        void run(HttpServletResponse resp);
    }

    @Override
    public void setStatus(int sc) {
        // ⚠️ status 必须**立即** propagate 到 underlying：只缓存到 this.status 的话，
        //   下层 chain.doFilter() 跑完时已经 outputMessage.flush() → 底层被标 committed
        //   ⇒ 事后 flushTo 再写 status 直接被 !isCommitted() 吞掉
        //   ⇒ 实测表象：HTTP 401 / 500 一律降到 200。
        writeToOriginal(resp -> {
            this.status = sc;
            resp.setStatus(sc);
        });
    }

    @Override
    public void sendError(int sc) throws IOException {
        underlyingLock.lock();
        try {
            if (timedOut || clientCancelled) {
                return;
            }
            this.status = sc;
            this.original.sendError(sc);
        } finally {
            underlyingLock.unlock();
        }
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        underlyingLock.lock();
        try {
            if (timedOut || clientCancelled) {
                return;
            }
            this.status = sc;
            this.original.sendError(sc, msg);
        } finally {
            underlyingLock.unlock();
        }
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        // 不走 OutputStreamWriter 的 char/byte 缓冲：每次 write 直接转 UTF-8 字节写入 captured body。
        // 这样 handler 写完 body 后无需 flush 也能在 bodyBytes() 读到
        // （对齐 B 侧版 observabilityResponseWriter.Write 直接写 buffer 的语义）。
        Writer unbuffered = new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) {
                if (timedOut || clientCancelled) {
                    return;
                }
                byte[] bytes = new String(cbuf, off, len).getBytes(StandardCharsets.UTF_8);
                body.write(bytes, 0, bytes.length);
            }

            @Override
            public void write(String str, int off, int len) {
                if (timedOut || clientCancelled) {
                    return;
                }
                byte[] bytes = str.substring(off, off + len).getBytes(StandardCharsets.UTF_8);
                body.write(bytes, 0, bytes.length);
            }

            @Override
            public void flush() {
                // body 是 ByteArrayOutputStream，flush 是 no-op
            }

            @Override
            public void close() {
                // no-op；body 随 wrapper 一起释放
            }
        };
        return new PrintWriter(unbuffered, false);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        // 返回一个只写到 captured body 的 ServletOutputStream
        return new ServletOutputStream() {
            @Override
            public void write(int b) throws IOException {
                if (timedOut || clientCancelled) {
                    return;
                }
                body.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (timedOut || clientCancelled) {
                    return;
                }
                body.write(b, off, len);
            }

            @Override
            public void flush() {
                // no-op；buffer 模式不主动 flush
            }

            @Override
            public boolean isReady() {
                return !timedOut && !clientCancelled;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                // buffer 模式不支持 write listener；no-op
            }
        };
    }

    @Override
    public void setHeader(String name, String value) {
        // ⚠️ 与 setStatus 同构：头也要**实时** propagate。
        //   只写 capturedHeaders、等 flushTo 再统一写回 underlying 的话，
        //   下游 flush() 之后底层已 committed ⇒ 此时再 setHeader 会被 Tomcat **静默忽略**。
        writeToOriginal(resp -> {
            capturedHeaders.put(name, value);
            resp.setHeader(name, value);
        });
    }

    @Override
    public void addHeader(String name, String value) {
        writeToOriginal(resp -> {
            capturedHeaders.merge(name, value, (a, b) -> a + "," + b);
            resp.addHeader(name, value);
        });
    }

    /**
     * Content-Type 单独 override。
     *
     * <p>{@link HttpServletResponseWrapper} 的默认实现 {@code setContentType(type) → response.setContentType(type)}
     * 是<b>直接透传</b>的 ⇒ 为避免「setContentType 直通、setHeader 进 buffer」两套语义并存，
     * 这里显式接管，走与 setHeader 相同的实时路径。
     */
    @Override
    public void setContentType(String type) {
        writeToOriginal(resp -> {
            if (type != null) {
                capturedHeaders.put("Content-Type", type);
            }
            resp.setContentType(type);
        });
    }

    /**
     * 🔴🔴 <b>超时 / 断连之后，不再把底层响应提交出去</b>。
     *
     * <p>正常路径下<b>没有人</b>调 {@code flushBuffer()}（Spring 写 body 走本类的缓冲流，不会 flush）
     * ⇒ 真正提交响应的是中间件的收尾分支 ⇒ 行为不变。
     * ⇒ 唯一会调它的是<b>异常处理路径</b>（{@code @ExceptionHandler} 返回 {@code ResponseEntity}
     * ⇒ {@code ServletServerHttpResponse.flush()}）⇒ 那正是要掐掉的那次提交。
     *
     * <p>⚠️ <b>为什么是「超时后 no-op」而不是「永远 no-op」</b>：Servlet 规范里「commit 之后设 status 无效」
     * 是<b>容器语义</b> ⇒ 正常路径下 {@code flushBuffer()} 必须<b>照常透传</b>
     * ⇒ 只在<b>超时 / 断连之后</b>掐掉它。
     */
    @Override
    public void flushBuffer() throws IOException {
        underlyingLock.lock();
        try {
            if (timedOut || clientCancelled) {
                // B 侧: 超时后业务写被丢弃（ErrHandlerTimeout）⇒ worker 不许再提交响应
                return;
            }
            this.original.flushBuffer();
        } finally {
            underlyingLock.unlock();
        }
    }

    /** 本次响应的状态码。 */
    public int statusCode() {
        return status;
    }

    /** 捕获到的响应体字节。 */
    public byte[] bodyBytes() {
        return body.toByteArray();
    }

    /** 标记「已超时」⇒ 之后的业务写全部丢弃、底层不再被提交。 */
    public void markTimedOut() {
        // ⚠️ **与对底层的写同一把锁** ⇒ 「置标志」与「写底层」不会交错
        // ⇒ 不存在「标志还没落地，worker 就把响应提交了」的窗口。
        underlyingLock.lock();
        try {
            this.timedOut = true;
        } finally {
            underlyingLock.unlock();
        }
    }

    /** 标记「客户端已断连」⇒ 之后的业务写全部丢弃、底层不再被提交。 */
    public void markClientCancelled() {
        underlyingLock.lock();
        try {
            this.clientCancelled = true;
        } finally {
            underlyingLock.unlock();
        }
    }

    /**
     * 写超时 / 断连响应（<b>容器线程</b>用）—— 与 worker 线程对底层的写<b>互斥</b>。
     *
     * <p>⚠️ 这里<b>不能</b>走 {@link #writeToOriginal}：那个方法在 timedOut / clientCancelled
     * 时是 no-op，而超时响应恰恰要在这两个标志已置位时写出去 ⇒ 这里直接持锁写。
     *
     * <h2>🔴🔴 F-15：底层响应<b>已被 worker 提交</b>时 ⇒ <b>必须完全弃权</b></h2>
     * <p><b>比 B 侧多做了什么</b>：B 侧（RAG）的同名实现<b>没有这道「已提交」检查</b> ——
     * 它在 {@code HttpMiddleware.java:1153} 的 {@code flushBuffer()} 里直接
     * {@code ((HttpServletResponse) this.original).flushBuffer();}
     * ⇒ ⇒ <b>commit 之后</b>，容器线程的<b>超时分支</b>仍会往同一个响应上写
     * {@code setStatus(503)} ＋ {@code setContentType("text/plain")} ＋ {@code "Request Timeout\n"}。
     *
     * <p><b>语义还一样吗</b>：<b>一样，且更安全</b>。理由 = <b>commit 之后头已经上 wire</b>：
     * <ul>
     *   <li>再改 {@code status} / {@code Content-Type} ⇒ <b>对客户端无效</b>（状态行与头早已发出）
     *       ⇒ 客户端看到的是「200 + application/json」；</li>
     *   <li>但 <b>body 会被追加</b>（Tomcat 的输出流在 commit 后仍可写）⇒ ⇒ 客户端拿到
     *       <b>{@code 200 + application/json + "Request Timeout\n"}</b>
     *       ⇒ ⇒ <b>混血</b>：状态码与 Content-Type 是业务信封的，body 却是纯文本
     *       ⇒ ⇒ <b>信封契约被打断</b>（客户端按 JSON 解会失败）。</li>
     * </ul>
     * ⇒ ⇒ 本方法在「已提交」时<b>改为把已捕获的业务 body 补写出去</b>（<b>只写 body</b>：头已上 wire）
     * ⇒ ⇒ 客户端拿到<b>干净的「200 + 业务信封」</b>
     * ⇒ ⇒ 这是 B 侧在该竞态窗口里的<b>干净候选之一</b>（B 侧的另一个干净候选 = 503 + 纯文本，
     * 由「超时先落定」那条分支产出）⇒ ⇒ <b>本仓只删掉那个非法形状，不新增任何形状</b>
     * ⇒ ⇒ 两侧在「B 侧行为确定」的所有场景下<b>逐字节一致</b>。
     *
     * <p><b>B 出处</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/HttpMiddleware.java:1153}
     * （B 侧 {@code ObservabilityResponseWriter#flushBuffer} · <b>两侧同源 ⇒ 竞态两侧都有</b>）。
     * ⇒ ⇒ 本条属<b>共享层缺陷</b>（不是 C 侧引入的漂移）⇒ 登记在
     * {@code docs/DRIFT/common/verification-common-2026-11-02.md} 的 **DRIFT-COMMON-V21**。
     */
    public void writeTimeoutResponseQuietly(int statusCode, jakarta.servlet.http.HttpServletRequest req) {
        underlyingLock.lock();
        try {
            // 🔴 **已提交 ⇒ 完全弃权**（不得再写 status / CT / body ⇒ 否则混血）
            if (this.original.isCommitted()) {
                flushCapturedBodyToOriginal();
                return;
            }
            HttpMiddleware.writeTimeoutResponseQuietly(this.original, statusCode, req);
        } finally {
            underlyingLock.unlock();
        }
    }

    /**
     * 把<b>已捕获的业务 body</b> 补写到底层响应（<b>只写 body</b> —— 头已随 commit 上 wire）。
     *
     * <p>⚠️ <b>为什么用 {@code getOutputStream()} 而不是 {@code getWriter()}</b>：与
     * {@link #flushTo(HttpServletResponse)} 同一口径（它也是 {@code dst.getOutputStream().write(...)}）
     * ⇒ ⇒ 两条路径不会在同一个响应上混用「writer + stream」。
     *
     * <p>⚠️ <b>为什么吞掉 {@link IllegalStateException}</b>：若底层响应的 writer 已被别处取走
     * （Tomcat 不允许 writer 与 stream 混用）⇒ ⇒ 这时写不进去是<b>既有事实</b>，
     * 不该让「超时收尾」因它而抛 ⇒ ⇒ 与 {@code writeTimeoutResponseQuietly} 的吞异常同款。
     */
    private void flushCapturedBodyToOriginal() {
        byte[] captured = body.toByteArray();
        if (captured.length == 0) {
            return;
        }
        try {
            this.original.getOutputStream().write(captured);
            this.original.getOutputStream().flush();
        } catch (IOException | IllegalStateException ignore) {
            // 客户端已走 / 底层 writer 已被占用 ⇒ 写不进去无所谓（同 writeTimeoutResponseQuietly 的吞异常）
        }
    }

    /**
     * 对齐 B 侧版 {@code func (w *observabilityResponseWriter) flushTo(dst http.ResponseWriter)}。
     */
    public void flushTo(HttpServletResponse dst) throws IOException {
        if (timedOut || clientCancelled) {
            // 超时 / 客户端断连时不 flush captured body（避免污染 timeout 响应）
            return;
        }
        underlyingLock.lock();
        try {
            // setHeader / addHeader / setContentType 均已改为**实时 propagate**，
            // 与 setStatus 同一策略。这里保留二次写入作为兜底：Tomcat 的 setHeader 是
            // **覆盖**语义（不是 add），所以重复写入不会产生重复 header。
            for (Map.Entry<String, String> e : capturedHeaders.entrySet()) {
                dst.setHeader(e.getKey(), e.getValue());
            }
            if (body.size() > 0) {
                dst.getOutputStream().write(body.toByteArray());
            }
        } finally {
            underlyingLock.unlock();
        }
    }

    /** 头枚举（本类不持有真实头 ⇒ 返回空枚举）。 */
    public Enumeration<String> emptyHeaders() {
        return Collections.emptyEnumeration();
    }
}
