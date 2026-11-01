package com.rag.common.observability;

import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p><b>客户端断连的主动探测 —— 往 socket 写 1xx 探针，把断连提前暴露出来</b>。
 *
 * <p><b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/observability/GoStyleClientDisconnectProbe.java:21}（RAG 仓）
 * <pre>
 * case &lt;-ctx.Done():
 *     if errors.Is(ctx.Err(), context.Canceled) { statusCode = httpClientClosedRequest }  // 499
 * </pre>
 * ⇒ ⇒ B 侧 ctx 一取消（= 客户端断开，{@code net/http} 会取消 {@code r.Context()}）⇒ **立刻 499**。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/observability/GoStyleClientDisconnectProbe.java}
 * 整段抄（L2 业务语义层逐条保留 · D13）＋ <b>改名</b>（本仓不用 {@code GoStyle} 前缀 · D11）
 * ＋ <b>反射链在 Tomcat 10.1 上重新取证</b>（见下）。
 *
 * <h2>⚠️ 机制：写 1xx 探针</h2>
 * <p>Tomcat 在 HTTP/1.1 下**不向应用层发断连信号** ⇒ 容器线程只有「往 socket 写」时才知道客户端已经走了。
 * ⇒ 本类往 socket 直接写一段 {@code HTTP/1.1 103 Early Hints\r\n\r\n}（ASCII，无 body）：
 * <ul>
 *   <li>写<b>成功</b> ⇒ 客户端还在线 ⇒ {@link State#ALIVE}；</li>
 *   <li>写<b>失败</b>（{@code IOException}：Broken pipe / Connection reset）⇒ 客户端已走 ⇒ {@link State#DISCONNECTED}；</li>
 *   <li>直接写 socket 绕开 Tomcat 的 {@code OutputBuffer} ⇒ <b>不提交响应</b>
 *       ⇒ {@code Content-Type} 与 {@code Content-Length} 全部保住。</li>
 * </ul>
 * <p>⚠️ **为什么选 103 Early Hints**：RFC 9110 要求客户端忽略 1xx ⇒ 业务语义不受影响；
 * 且它<b>没有 body</b> ⇒ 不会污染业务响应体。
 *
 * <h2>⚠️ 为什么必须有「1xx 行数上界」</h2>
 * <p>慢请求会写出 N 行 103 ⇒ **击穿客户端的 interim 上限**
 * （JDK {@code java.net.http} 默认 8 ⇒ 第 9 行即 {@code ProtocolException}）
 * ⇒ ⇒ 每请求的 1xx 行数必须有上界（默认 {@link #DEFAULT_MAX_PROBE_LINES} = 4）。
 * ⇒ ⇒ 超预算后返回 {@link State#BUDGET_EXHAUSTED} ⇒ 调用方<b>不再写探针字节</b>，等待语义完全不变。
 *
 * <h2>fail-open 纪律（§3.3）</h2>
 * <p>本类<b>任何一步失败都不得抛给调用方</b>、<b>不得改变现有行为</b>：
 * 反射链取不到（Tomcat 换版本 / 不是 Tomcat / 走了别的 wrapper）⇒ 返回
 * {@link State#UNAVAILABLE} ⇒ 调用方<b>什么都不做</b>，继续按原来的超时语义等待。
 *
 * <h2>🔴 反射链在 <b>Tomcat 10.1</b> 上的重新取证（2026-11-02 · 批次 C）</h2>
 * <p>B 侧的反射链取证全部基于 <b>Tomcat 11</b> ⇒ 本仓的 Tomcat <b>10.1.52</b>（Boot 3.5.11 管）
 * 必须重测（审核报告 **F-7**）。实测结论（{@code javap -p} 取证）：
 * <pre>
 *   org.apache.catalina.connector.RequestFacade   → protected 字段 "request"      ✔ 存在
 *   org.apache.catalina.connector.Request         → public getCoyoteRequest()      ✔ 存在
 *   org.apache.coyote.Request                      → private volatile "hook"          ✔ 存在
 *   org.apache.coyote.AbstractProcessor            → protected final getSocketWrapper() ✔ 存在
 *   org.apache.tomcat.util.net.SocketWrapperBase   → public final write(boolean, byte[], int, int) ✔
 *                                                      public boolean flush(boolean)                     ✔
 * </pre>
 * ⚠️ <b>唯一与 Tomcat 11 的差异</b>：{@code org.apache.coyote.ActionHook} 在 Tomcat 10.1 里
 * 是<b>接口</b>（只有 {@code action(ActionCode, Object)}，<b>没有</b> {@code getSocketWrapper()}）
 * ⇒ ⇒ 所以 {@code getSocketWrapper()} 必须<b>按实例的类层次</b>找
 * （实际持有者是 {@code AbstractProcessor} 的子类）⇒ ⇒ 本类用
 * {@link Reflect#socketWrapperMethod(Class)} 按类层次查找并缓存。
 */
public final class ClientDisconnectProbe {

    /**
     * 探测结果。
     */
    public enum State {
        /** 客户端仍在线（写 socket 成功）。 */
        ALIVE,
        /** 客户端已断连（写 socket 失败）。 */
        DISCONNECTED,
        /**
         * 探测不可用 ⇒ 调用方必须 <b>fail-open</b>（什么都不做，继续等）。
         */
        UNAVAILABLE,
        /**
         * 本请求的 <b>1xx 行数预算已用尽</b> ⇒ <b>不再写探针字节</b>。
         *
         * <p>⇒ 调用方同样必须 <b>fail-open</b>（什么都不做，继续按原超时语义等待）
         * ⇒ 与 {@link #UNAVAILABLE} 的区别只在于「为什么不做」：
         * {@code UNAVAILABLE} = 机制不可用（反射链取不到 / 非 HTTP/1.1 / 响应已提交 /
         * 被运维显式关闭），{@code BUDGET_EXHAUSTED} = 机制可用但<b>本请求已经写够了</b>。
         */
        BUDGET_EXHAUSTED
    }

    private static final Logger log = LoggerFactory.getLogger(ClientDisconnectProbe.class);

    /**
     * 探测间隔的 system property（毫秒）。
     *
     * <p>⚠️ <b>刻意不是配置项</b>：本机制必须做到「<b>零域侧改动</b>」——
     * 往 10 个 {@code application.yml} 加一项就等于要求每个域都改配置 ⇒ 违反
     * 「共享层改动不许外溢到业务模块」的纪律。
     * ⇒ 用 <b>常量 + system property</b>：默认 {@link #DEFAULT_PROBE_INTERVAL_MILLIS}，
     * 需要调整时用 {@code -Drag.common.http.disconnect-probe-interval-ms=200} 覆盖。
     */
    public static final String PROBE_INTERVAL_PROPERTY = "rag.common.http.disconnect-probe-interval-ms";

    /** 探测间隔默认值（毫秒）。 */
    public static final long DEFAULT_PROBE_INTERVAL_MILLIS = 500L;

    /** 探测间隔下限（毫秒）—— 低于此值 ⇒ 探针过于频繁，反而成为负担。 */
    public static final long MIN_PROBE_INTERVAL_MILLIS = 50L;

    /**
     * 每请求 1xx 行数上界的 system property。
     *
     * <p>⚠️ <b>同样刻意不是配置项</b>：理由同 {@link #PROBE_INTERVAL_PROPERTY}。
     */
    public static final String MAX_PROBE_LINES_PROPERTY = "rag.common.http.disconnect-probe-max-lines";

    /** 每请求 1xx 行数上界默认值。 */
    public static final int DEFAULT_MAX_PROBE_LINES = 4;

    /**
     * 已写出的 1xx 行数计数（挂在 request attribute 上 ⇒ 每请求独立）。
     */
    private static final String PROBE_LINES_ATTRIBUTE = "rag.common.http.disconnect-probe-lines";

    /**
     * 探针字节：{@code HTTP/1.1 103 Early Hints\r\n\r\n}（ASCII）。
     *
     * <p>⚠️ 长度以 {@link #PROBE_BYTES} 的实际值为准（28 字节），<b>不要在这里写死数字</b>。
     */
    private static final byte[] PROBE_BYTES =
            "HTTP/1.1 103 Early Hints\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /** 只在 HTTP/1.1 上探测（HTTP/2 是二进制帧协议 ⇒ 写 HTTP/1.1 文本会破坏帧流）。 */
    private static final String HTTP_11 = "HTTP/1.1";

    /** 「行数上界非法」只告警一次（避免每次请求刷日志）。 */
    private static final AtomicBoolean MAX_PROBE_LINES_WARNED = new AtomicBoolean();

    private ClientDisconnectProbe() {
    }

    /**
     * 探测间隔（毫秒），恒 {@code >= }{@link #MIN_PROBE_INTERVAL_MILLIS}。
     */
    public static long probeIntervalMillis() {
        String raw = System.getProperty(PROBE_INTERVAL_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_PROBE_INTERVAL_MILLIS;
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PROBE_INTERVAL_MILLIS;
        }
        if (parsed < MIN_PROBE_INTERVAL_MILLIS) {
            return MIN_PROBE_INTERVAL_MILLIS;
        }
        return parsed;
    }

    /**
     * 每请求 1xx 行数上界。
     *
     * <p>⚠️ 非法取值 ⇒ 回落 {@link #DEFAULT_MAX_PROBE_LINES} ＋ <b>只告警一次</b>。
     */
    public static int maxProbeLines() {
        String raw = System.getProperty(MAX_PROBE_LINES_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_MAX_PROBE_LINES;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            warnMaxProbeLinesOnce(raw, "非整数 ⇒ 非法", DEFAULT_MAX_PROBE_LINES);
            return DEFAULT_MAX_PROBE_LINES;
        }
        if (parsed < 0) {
            warnMaxProbeLinesOnce(raw, "负数 ⇒ 非法", DEFAULT_MAX_PROBE_LINES);
            return DEFAULT_MAX_PROBE_LINES;
        }
        return parsed;
    }

    private static void warnMaxProbeLinesOnce(String raw, String reason, int effective) {
        if (MAX_PROBE_LINES_WARNED.compareAndSet(false, true)) {
            log.warn("{} 非法（{}）⇒ 回落 {}", MAX_PROBE_LINES_PROPERTY, reason, effective);
        }
    }

    /** 本请求已写出的 1xx 行数（未写过 ⇒ 0）。 */
    public static int probeLinesWritten(HttpServletRequest request) {
        if (request == null) {
            return 0;
        }
        Object attr = request.getAttribute(PROBE_LINES_ATTRIBUTE);
        if (attr instanceof Integer n) {
            return n;
        }
        return 0;
    }

    /**
     * 执行一次断连探测。
     *
     * <p><b>本方法绝不抛异常</b>（fail-open）：任何失败都折叠成 {@link State#UNAVAILABLE}。
     *
     * @param request  当前请求（用于取 socket + 判协议 + 记行数预算）
     * @param response 当前响应（用于判「是否已提交」）
     * @return 探测结果
     */
    public static State probe(HttpServletRequest request, HttpServletResponse response) {
        if (request == null) {
            return State.UNAVAILABLE;
        }
        // ⚠️ 前置守卫 1：**只在 HTTP/1.1 上探测**。
        //    HTTP/2 是二进制帧协议，往 socket 写 HTTP/1.1 文本会**破坏帧流** ⇒ 必须跳过。
        String protocol = request.getProtocol();
        if (protocol == null || !HTTP_11.equalsIgnoreCase(protocol)) {
            return State.UNAVAILABLE;
        }
        // ⚠️ 前置守卫 2：**只在响应尚未提交时探测**。
        //    已提交 ⇒ Tomcat 的 OutputBuffer 可能正在往同一个 socket 写 ⇒ 裸写会与它交错。
        if (response != null && response.isCommitted()) {
            return State.UNAVAILABLE;
        }
        // 🔴 预算闸门：本请求的 1xx 行数必须有上界
        //    ⇒ 本闸门必须排在**反射链之前** ⇒ 预算用尽后不再做无谓的反射。
        final int maxLines = maxProbeLines();
        if (maxLines <= 0) {
            // 显式关闭 ⇒ 机制整体不可用 ⇒ fail-open（调用方继续按原语义等待）
            return State.UNAVAILABLE;
        }
        final int written = probeLinesWritten(request);
        if (written >= maxLines) {
            // 预算用尽 ⇒ 本请求不再写探针字节 ⇒ 等待语义完全不变（fail-open）
            return State.BUDGET_EXHAUSTED;
        }
        Object socketWrapper = resolveSocketWrapper(request);
        if (socketWrapper == null) {
            return State.UNAVAILABLE;
        }
        try {
            // ⚠️⚠️ write 与 flush **必须成对**：只 write 的话字节滞留在 Tomcat 的写缓冲里，
            //    永远不触发 syscall ⇒ 断连检测不到。
            Reflect.WRITE.invoke(socketWrapper, Boolean.TRUE, PROBE_BYTES,
                    Integer.valueOf(0), Integer.valueOf(PROBE_BYTES.length));
            Reflect.FLUSH.invoke(socketWrapper, Boolean.TRUE);
            // ★ 只有**真的写出去**才占预算（写失败 ⇒ 客户端已走 ⇒ 响应已死）
            request.setAttribute(PROBE_LINES_ATTRIBUTE, Integer.valueOf(written + 1));
            return State.ALIVE;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof IOException) {
                // 写 socket 失败 ⇒ 客户端已断连（Broken pipe / Connection reset）
                return State.DISCONNECTED;
            }
            log.debug("[HTTP] 断连探测异常（非 IOException），按不可用处理", cause);
            return State.UNAVAILABLE;
        } catch (Throwable t) {
            // 反射链在运行期变形（Tomcat 升级 / 代理类）⇒ fail-open
            log.debug("[HTTP] 断连探测不可用", t);
            return State.UNAVAILABLE;
        }
    }

    // ── 反射链 ──────────────────────────────────────────────────────────

    /**
     * 取出底层 {@code SocketWrapperBase}。
     *
     * <p>反射链（<b>Tomcat 10.1.52 实测可达</b>，见类 javadoc）：
     * <pre>
     *   HttpServletRequest（实际是 org.apache.catalina.connector.RequestFacade）
     *     → 字段 "request"（protected，声明在 RequestFacade）
     *     → org.apache.catalina.connector.Request
     *     → public getCoyoteRequest()
     *     → org.apache.coyote.Request
     *     → 字段 "hook"（private volatile，类型 ActionHook；实际持有 AbstractProcessor）
     *     → getSocketWrapper()（protected final，声明在 AbstractProcessor）
     *     → org.apache.tomcat.util.net.SocketWrapperBase
     * </pre>
     *
     * @return socket wrapper；取不到返回 {@code null}
     */
    private static Object resolveSocketWrapper(HttpServletRequest request) {
        Object catalinaRequest = findCatalinaRequest(request);
        if (catalinaRequest == null || Reflect.GET_COYOTE_REQUEST == null) {
            return null;
        }
        Object coyoteRequest;
        try {
            coyoteRequest = Reflect.GET_COYOTE_REQUEST.invoke(catalinaRequest);
        } catch (Throwable t) {
            return null;
        }
        if (coyoteRequest == null || Reflect.HOOK_FIELD == null) {
            return null;
        }
        Object hook;
        try {
            hook = Reflect.HOOK_FIELD.get(coyoteRequest);
        } catch (Throwable t) {
            return null;
        }
        if (hook == null) {
            return null;
        }
        Method getSocketWrapper = Reflect.socketWrapperMethod(hook.getClass());
        if (getSocketWrapper == null) {
            return null;
        }
        try {
            return getSocketWrapper.invoke(hook);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 Servlet 请求对象里挖出 {@code org.apache.catalina.connector.Request}。
     *
     * <p>兼容两种形态：① 直接就是 {@code RequestFacade}（字段 {@code request}）；
     * ② 被 {@link ServletRequestWrapper} 包了一层（先解包再找字段）。
     */
    private static Object findCatalinaRequest(Object request) {
        Object current = request;
        // 解包深度上限：只为防环，正常 1~2 层足够
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof ServletRequestWrapper wrapper) {
                current = wrapper.getRequest();
                continue;
            }
            Object found = readRequestField(current);
            if (found != null) {
                return found;
            }
            return null;
        }
        return null;
    }

    /**
     * 在对象类层次里找名为 {@code request} 的字段（Tomcat 的 {@code RequestFacade.request}）。
     */
    private static Object readRequestField(Object target) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field field;
            try {
                field = c.getDeclaredField("request");
            } catch (NoSuchFieldException e) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(target);
                if (value != null
                        && (Reflect.CATALINA_REQUEST == null || Reflect.CATALINA_REQUEST.isInstance(value))) {
                    return value;
                }
            } catch (Throwable t) {
                // setAccessible 被模块系统拒绝 / 字段类型不对 ⇒ 继续往上找
                return null;
            }
        }
        return null;
    }

    /**
     * 反射对象的<b>一次性</b>解析结果。
     *
     * <p>全部在类初始化时解析一次（探测每 500ms 一次，不值得每次都查反射表）；
     * 任何一步失败都留 {@code null} ⇒ 探测恒为 {@link State#UNAVAILABLE}（fail-open）。
     */
    private static final class Reflect {

        /** {@code org.apache.catalina.connector.Request}（非 Tomcat ⇒ null）。 */
        static final Class<?> CATALINA_REQUEST = loadClass("org.apache.catalina.connector.Request");

        /** {@code org.apache.coyote.Request}（非 Tomcat ⇒ null）。 */
        static final Class<?> COYOTE_REQUEST = loadClass("org.apache.coyote.Request");

        /** {@code org.apache.tomcat.util.net.SocketWrapperBase}（非 Tomcat ⇒ null）。 */
        static final Class<?> SOCKET_WRAPPER_BASE = loadClass("org.apache.tomcat.util.net.SocketWrapperBase");

        /** {@code Request.getCoyoteRequest()}。 */
        static final Method GET_COYOTE_REQUEST =
                lookupMethod(CATALINA_REQUEST, "getCoyoteRequest");

        /** {@code coyote.Request.hook}（private）。 */
        static final Field HOOK_FIELD = lookupField(COYOTE_REQUEST, "hook");

        /** {@code SocketWrapperBase.write(boolean, byte[], int, int)}（public final）。 */
        static final Method WRITE = lookupMethod(SOCKET_WRAPPER_BASE,
                "write", boolean.class, byte[].class, int.class, int.class);

        /** {@code SocketWrapperBase.flush(boolean)}（public）。 */
        static final Method FLUSH = lookupMethod(SOCKET_WRAPPER_BASE, "flush", boolean.class);

        /**
         * {@code getSocketWrapper()}：声明在 {@code AbstractProcessor}（protected final），
         * 但 Tomcat 的实现类不同（Http11Processor / Http2Processor …）
         * ⇒ 按<b>实例的类层次</b>查，不能写死类名。
         */
        static final Map<Class<?>, Method> GET_SOCKET_WRAPPER_METHOD = new ConcurrentHashMap<>();

        private Reflect() {
        }

        private static Class<?> loadClass(String name) {
            try {
                return Class.forName(name, false, Reflect.class.getClassLoader());
            } catch (Throwable t) {
                return null;
            }
        }

        private static Method lookupMethod(Class<?> owner, String name, Class<?>... params) {
            if (owner == null) {
                return null;
            }
            try {
                Method m = owner.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable t) {
                return null;
            }
        }

        private static Field lookupField(Class<?> owner, String name) {
            if (owner == null) {
                return null;
            }
            try {
                Field f = owner.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * 在类层次里找 protected 的 {@code getSocketWrapper()} 并缓存。
         *
         * <p>⚠️ <b>Tomcat 10.1 与 11 的差异就在这里</b>：
         * {@code ActionHook} 在 10.1 是接口（无 {@code getSocketWrapper()}）⇒ 必须按<b>实际持有者</b>
         * （{@code AbstractProcessor} 的子类）的类层次找。
         */
        static Method socketWrapperMethod(Class<?> concrete) {
            Method cached = GET_SOCKET_WRAPPER_METHOD.get(concrete);
            if (cached != null) {
                return cached;
            }
            Method found = null;
            for (Class<?> c = concrete; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod("getSocketWrapper");
                    m.setAccessible(true);
                    found = m;
                    break;
                } catch (NoSuchMethodException e) {
                    continue;
                } catch (Throwable t) {
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            GET_SOCKET_WRAPPER_METHOD.put(concrete, found);
            return found;
        }
    }
}
