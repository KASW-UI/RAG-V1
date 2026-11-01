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
 * <p><b>Go 出处</b>：{@code Sea-RideTheWind_golang/service/common/observability/http.go}
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
 * （Go 侧 {@code Write} 在 {@code timedOut} 时返回 {@code http.ErrHandlerTimeout} ⇒ 同款语义）。
 *
 * <h2>🔴 为什么要有 {@code underlyingLock}（底层响应的写锁）</h2>
 * <p>worker 线程（业务）与容器线程（超时 / 断连分支）<b>都可能碰底层响应</b>。
 * Tomcat 的 {@code MimeHeaders.createHeader()} 是
 * {@code if ((mh = headers[count]) == null) { headers[count] = mh = new MimeHeaderField(); } count++}
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
        // （对齐 Go 版 observabilityResponseWriter.Write 直接写 buffer 的语义）。
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
                // Go: 超时后业务写被丢弃（ErrHandlerTimeout）⇒ worker 不许再提交响应
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
     */
    public void writeTimeoutResponseQuietly(int statusCode, jakarta.servlet.http.HttpServletRequest req) {
        underlyingLock.lock();
        try {
            HttpMiddleware.writeTimeoutResponseQuietly(this.original, statusCode, req);
        } finally {
            underlyingLock.unlock();
        }
    }

    /**
     * 对齐 Go 版 {@code func (w *observabilityResponseWriter) flushTo(dst http.ResponseWriter)}。
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
