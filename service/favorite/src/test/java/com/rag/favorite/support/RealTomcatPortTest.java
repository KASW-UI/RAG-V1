package com.rag.favorite.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RealTomcatPort} 的守门测试 —— 防止「端口选取」这个机制<b>自己被改坏</b>。
 *
 * <h2>为什么必须有它</h2>
 * <p>本类守的是 B 侧 PIT-FAV-API-008 的修复：{@code RANDOM_PORT} 挑的端口可能被
 * <b>别的会话的 Docker 容器</b>抢走（macOS 上 {@code 127.0.0.1:P} 会抢占通配地址
 * {@code 0.0.0.0:P}）⇒ 请求打到了 PostgreSQL ⇒ 零字节即关
 * ⇒ {@code HTTP/1.1 header parser received no bytes} ⇒ <b>看着像被测代码炸了</b>。
 *
 * <h2>⚠️ 断言口径：<b>不靠「抢端口段里的端口」</b></h2>
 * <p>B 侧 2026-10-25 第一版写法是「占住端口段的第一个端口，然后断言 {@code pick()} 绕开它」——
 * ⇒ <b>在全量套件里直接挂了</b>：同一个 JVM 里，<b>上一个 Spring 测试类的 Tomcat 还活着</b>
 * （Spring 会缓存测试上下文）⇒ 它的端口<b>本来就是</b>端口段的第一个
 * ⇒ 测试想占的那个端口<b>早就被占了</b> ⇒ {@code BindException: Address already in use}。
 * ⇒ <b>守门测试自己被环境左右</b> ⇒ 改成：
 * <ul>
 *   <li>① 判定逻辑（{@link RealTomcatPort#isFree(int)}）<b>包级可见</b> ⇒ 直接断言它；</li>
 *   <li>② 「被占」的样本用<b>独立的脚手架端口段</b>（{@link #SCRATCH_FIRST} 起），
 *       <b>不碰</b>生产用的端口段 ⇒ 与「谁还活着」完全无关。</li>
 * </ul>
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>包名</b>压平（D7）；
 *       <b>脚手架端口段</b>由 B 侧的 {@code 21950-21999} 改为 {@code 21500-21549}
 *       ⇒ 刻意避开 B 侧 favorite-api 的 {@code 21300-21399}（真 Tomcat 段）与
 *       {@code 21950-21999}（B 侧脚手架段）⇒ 本机同时跑两侧测试时不会互撞。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 四条断言的判据与口径逐条未改。</li>
 *   <li><b>B 出处</b>：B 侧
 *       {@code service/favorite/favorite-api/src/test/java/com/rag/favorite/favorite/api/support/RealTomcatPortTest.java:35-97}。</li>
 * </ol>
 */
class RealTomcatPortTest {

    /** 脚手架端口段：只给本测试用，**不与** {@link RealTomcatPort} 的端口段重叠。 */
    private static final int SCRATCH_FIRST = 21500;

    /** 脚手架端口段上界。 */
    private static final int SCRATCH_LAST = 21549;

    /** 在脚手架段里找一个当前空闲的端口（ bind 自检后立刻释放）。 */
    private static int scratchPort() {
        for (int p = SCRATCH_FIRST; p <= SCRATCH_LAST; p++) {
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress(p), 1);
                return p;
            } catch (IOException ignored) {
                // 换一个
            }
        }
        throw new IllegalStateException("脚手架端口段 " + SCRATCH_FIRST + "-" + SCRATCH_LAST + " 没有空闲端口");
    }

    @Test
    @DisplayName("选出来的端口必须落在专用段内（避开 OS 临时端口段，不与 Docker 发布端口抢）")
    void picksFromDedicatedRange() {
        int port = RealTomcatPort.pick();
        assertTrue(port >= RealTomcatPort.FIRST && port <= RealTomcatPort.LAST,
                "端口必须在 " + RealTomcatPort.FIRST + "-" + RealTomcatPort.LAST
                        + " 内（实际=" + port + "）");
    }

    @Test
    @DisplayName("选出来的端口：0.0.0.0 与 127.0.0.1 都必须真的能绑（= 没有第三方持有者）")
    void pickedPortHasNoThirdPartyHolder() {
        int port = RealTomcatPort.pick();
        assertTrue(RealTomcatPort.isFree(port),
                "pick() 返回的端口 " + port + " 必须两个地址都能绑"
                        + "（否则客户端连 127.0.0.1 会被更具体的监听者抢走）");
    }

    @Test
    @DisplayName("127.0.0.1 上的端口被别人占着 ⇒ 必须判定为不可用（Docker 发布端口那种占用）")
    void rejectsPortHeldOnLoopback() throws IOException {
        int p = scratchPort();
        try (ServerSocket hold = new ServerSocket()) {
            hold.setReuseAddress(true);
            hold.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 1);
            assertFalse(RealTomcatPort.isFree(p),
                    "127.0.0.1:" + p + " 已被占 ⇒ 必须判定为不可用");
        }
    }

    @Test
    @DisplayName("0.0.0.0 上的端口被别人占着 ⇒ 必须判定为不可用（另一个 Tomcat 占通配地址那种占用）")
    void rejectsPortHeldOnWildcard() throws IOException {
        int p = scratchPort();
        try (ServerSocket hold = new ServerSocket()) {
            hold.setReuseAddress(true);
            hold.bind(new InetSocketAddress(p), 1);
            assertFalse(RealTomcatPort.isFree(p),
                    "0.0.0.0:" + p + " 已被占 ⇒ 必须判定为不可用");
        }
    }
}
