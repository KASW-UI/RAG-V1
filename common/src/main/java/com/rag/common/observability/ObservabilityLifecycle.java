package com.rag.common.observability;

import jakarta.annotation.PreDestroy;

/**
 * Spring 容器关闭时回收<b>RPC 侧</b>的观测层线程池。
 *
 * <p><b>B 出处</b>：B 侧<b>没有</b>对应物 —— B 的观测层不跑线程池
 * ⇒ ⇒ 本类是 <b>纯表达层补齐</b> ⇒ ⇒ <b>无 B 侧 对应物</b>（D-03 同族）。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/spring/ObservabilityLifecycle.java}
 * 整段抄（L2 · D13）＋ <b>包名 / 装配形态回改</b>（L4 · D3）。
 *
 * <h2>为什么必须有这一层</h2>
 * <p>两个池（{@code obs-rpc-timeout} / {@code obs-rpc-worker}）都是<b>进程级静态</b>的，
 * 而它们各自的 javadoc 都写着「业务模块在 {@code @PreDestroy} 里显式调 {@code shutdown()}」——
 * 在 RAG 侧这个契约是<b>真的</b>（各域的 {@code @PreDestroy} 都写了）；
 * 而在本仓，域侧尚未落地 ⇒ 若<b>没有</b>这一层，两个池就<b>永远没人关</b>。
 * <p>因为线程都 setDaemon(true)，不关也不会阻止 JVM 退出，所以问题<b>不显形</b>；
 * 但 Spring 的 graceful shutdown 与「容器已关闭、观测层线程池仍在收尾」的时序会错开，
 * 优雅停机期间可能丢最后几个 span。
 *
 * <p><b>为什么收口成一个 Bean 而不是让业务各写一遍</b>：这两个池是 static 的，
 * 每模块各写一份 {@code @PreDestroy} 只是把同一个调用复制 N 份、漏一份就静默失效。
 * 收口到本 Bean 后，<b>凡是注册了本 Bean 的模块都自动生效</b>。
 *
 * <p><b>幂等</b>：{@link ObservabilityRuntime#shutdown()} 是 {@code shutdownNow()}，重复调用安全。
 *
 * <p>⚠️ <b>HTTP 侧的池不在本类的关闭面里</b>（RAG 侧同款分工）：
 * HTTP 侧的 worker 池由「HTTP 池生命周期」单独负责（只有 servlet 进程才有那个池）
 * ⇒ ⇒ 本类<b>只碰 RPC 侧的两个池</b>，两个池都在 {@link ObservabilityRuntime} 里
 * ⇒ ⇒ <b>零 servlet 依赖</b> ⇒ ⇒ 纯 gRPC 进程加载本类也是安全的
 * （RAG 侧曾在这里炸过 {@code NoClassDefFoundError: jakarta/servlet/Filter}，见 RAG 的
 * 「🔴 为什么 HTTP 池不能在这里关」一节；本仓用<b>包结构</b>从根上避开了那个坑）。
 */
public class ObservabilityLifecycle {

    /**
     * 关闭 {@code obs-rpc-timeout} / {@code obs-rpc-worker} 两个池。
     *
     * <p>⚠️ <b>不吞异常</b> ⇒ 关闭失败向上抛（与 RAG 侧同口径）。
     */
    @PreDestroy
    public void shutdown() {
        ObservabilityRuntime.shutdown();
    }
}
