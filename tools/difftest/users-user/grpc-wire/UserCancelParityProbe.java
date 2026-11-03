package com.rag.user.parity;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <b>「对称注入」的取消传播探针</b> —— 同一个 JVM 里<b>同时</b>向两侧发出 <b>Register</b> 调用、
 * 并在<b>同一个绝对时刻</b>取消两侧的调用。
 *
 * <h2>🔴 为什么需要它（探针阶段D 的读数为什么不可信）</h2>
 * <p>阶段D（{@code scenarios.py}）的注入是<b>顺序</b>的：
 * <pre>
 *   发 B 的调用（cancel-after=1000）⇒ 等它返回 ⇒ 发 C 的调用（cancel-after=1000）
 *   ⇒ 等它返回 ⇒ **这时才放行两侧 PG**
 * </pre>
 * ⇒ ⇒ <b>B 的调用比 C 的调用早约 1 秒开始</b>（两次 JVM 启动 + 各自的 1s 取消）
 * ⇒ ⇒ <b>放行 PG 的那一刻，两侧离各自 2s 预算到点的距离不同</b>
 * ⇒ ⇒ 而「那条写到底落不落库」由<b>放行那一刻服务端还剩多少预算</b>决定
 * （本仓侧实测见 {@code common/src/test/.../GrpcInboundDeadlinePgTest#rowCountFollowsRemainingBudgetNotCancelTiming}：
 * 客户端取消时刻固定在 1s，只改放行时刻 ⇒ 行数在 1 / 0 之间翻转）
 * ⇒ ⇒ 阶段D 读到的「B=0 行 · C=1 行」<b>混进了「两侧调用起点相差 1 秒」这个注入变量</b>
 * ⇒ ⇒ 那不是行为差异。
 *
 * <h2>🔴 本探针怎么把变量消掉</h2>
 * <ul>
 *   <li><b>同一个 JVM</b> ⇒ 两侧调用的发出时刻相差<b>微秒级</b>（不再是 1 秒）；</li>
 *   <li><b>同一个取消时刻</b> ⇒ 两侧的取消是<b>一个任务</b>里一起做的（绝对时刻相同）；</li>
 *   <li><b>打印 {@code call_start_epoch_ms}</b> ⇒ 调用方（python）据此计算
 *       「放行 PG 的绝对时刻」⇒ ⇒ 放行时刻相对两侧<b>同样远</b>。</li>
 * </ul>
 * ⇒ ⇒ 三处时序全部对齐 ⇒ ⇒ 剩下的差异才是<b>行为差异</b>。
 *
 * <h2>用法</h2>
 * <pre>
 * java -cp &lt;cp&gt; com.rag.user.parity.UserCancelParityProbe \
 *      &lt;bHost&gt; &lt;bPort&gt; &lt;cHost&gt; &lt;cPort&gt; &lt;outFile&gt; &lt;username&gt; \
 *      [--cancel-after=MS] [--deadline=MS]
 * </pre>
 * <p>输出（{@code outFile}）：
 * <pre>
 *   START|&lt;side&gt;|&lt;epoch_ms&gt;            ← 调用发出的绝对时刻（**紧接着 flush** ⇒ 调用方可据此定时放行）
 *   CASE|&lt;side&gt;|&lt;status&gt;|&lt;payload&gt;     ← 客户端侧看到的结果
 *   META|&lt;side&gt;|cancel_issued=..|elapsed_ms=..|call_start_epoch_ms=..|cancel_at_epoch_ms=..
 * </pre>
 *
 * <p>⚠️ <b>复用 {@link UserWireProbe} 的静态助手</b>（同一 package ⇒ 包级可见）
 * ⇒ ⇒ 请求体的 proto3 wire 编码、响应解码<b>与主探针逐字同一份</b>
 * ⇒ ⇒ 两边量的是「同一个客户端行为」。
 */
public final class UserCancelParityProbe {

    private UserCancelParityProbe() {
    }

    /** 一次「可取消的调用」的客户端侧读数。 */
    private static final class Side {
        final String name;
        final ManagedChannel channel;
        final ClientCall<byte[], byte[]> call;
        final CountDownLatch done = new CountDownLatch(1);
        final long[] startEpochMs = new long[1];
        volatile String status = "?";
        volatile String payload = "";
        volatile boolean cancelIssued;
        volatile long elapsedMs;

        Side(String name, ManagedChannel channel, ClientCall<byte[], byte[]> call) {
            this.name = name;
            this.channel = channel;
            this.call = call;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("用法：UserCancelParityProbe <bHost> <bPort> <cHost> <cPort> "
                    + "<outFile> <username> [--cancel-after=MS] [--deadline=MS]");
            System.exit(2);
        }
        String bHost = args[0];
        int bPort = Integer.parseInt(args[1]);
        String cHost = args[2];
        int cPort = Integer.parseInt(args[3]);
        String outFile = args[4];
        String username = args[5];

        long cancelAfterMs = 1000L;
        long deadlineMs = 60000L;
        for (int i = 6; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--cancel-after=")) {
                cancelAfterMs = Long.parseLong(a.substring("--cancel-after=".length()));
            } else if (a.startsWith("--deadline=")) {
                deadlineMs = Long.parseLong(a.substring("--deadline=".length()));
            }
        }

        try (PrintStream out = new PrintStream(new FileOutputStream(outFile), true, "UTF-8")) {
            ManagedChannel bChannel = ManagedChannelBuilder.forAddress(bHost, bPort).usePlaintext().build();
            ManagedChannel cChannel = ManagedChannelBuilder.forAddress(cHost, cPort).usePlaintext().build();
            ScheduledExecutorService canceller = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "parity-probe-cancel");
                t.setDaemon(true);
                return t;
            });
            try {
                CallOptions opts = CallOptions.DEFAULT.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
                byte[] request = UserWireProbe.concat(
                        UserWireProbe.stringField(1, username),
                        UserWireProbe.stringField(2, "pw123456"),
                        UserWireProbe.stringField(3, username + "@example.test"));

                Side b = start(bChannel, opts, "b", request);
                Side c = start(cChannel, opts, "c", request);

                // 🔴 **两侧的调用在同一个绝对时刻被取消** ⇒ ⇒ 注入对称
                long cancelAtEpochMs = System.currentTimeMillis() + cancelAfterMs;
                canceller.schedule(() -> {
                    b.cancelIssued = true;
                    c.cancelIssued = true;
                    b.call.cancel("probe-cancel", null);
                    c.call.cancel("probe-cancel", null);
                }, cancelAfterMs, TimeUnit.MILLISECONDS);

                out.printf("START|b|%d%n", b.startEpochMs[0]);
                out.printf("START|c|%d%n", c.startEpochMs[0]);
                out.flush();

                b.done.await(30, TimeUnit.SECONDS);
                c.done.await(30, TimeUnit.SECONDS);

                out.printf("CASE|b|%s|%s%n", b.status, b.payload);
                out.printf("CASE|c|%s|%s%n", c.status, c.payload);
                out.printf("META|b|cancel_issued=%s|elapsed_ms=%d|call_start_epoch_ms=%d|cancel_at_epoch_ms=%d%n",
                        b.cancelIssued, b.elapsedMs, b.startEpochMs[0], cancelAtEpochMs);
                out.printf("META|c|cancel_issued=%s|elapsed_ms=%d|call_start_epoch_ms=%d|cancel_at_epoch_ms=%d%n",
                        c.cancelIssued, c.elapsedMs, c.startEpochMs[0], cancelAtEpochMs);
                out.flush();
            } finally {
                canceller.shutdownNow();
                bChannel.shutdownNow();
                cChannel.shutdownNow();
            }
        }
    }

    /** 发起一次调用并登记客户端侧读数。 */
    private static Side start(ManagedChannel channel, CallOptions opts, String name, byte[] request) {
        Side side = new Side(name, channel, channel.newCall(UserWireProbe.method("Register"), opts));
        long t0 = System.nanoTime();
        side.startEpochMs[0] = System.currentTimeMillis();
        side.call.start(new ClientCall.Listener<byte[]>() {
            @Override
            public void onHeaders(Metadata headers) {
            }

            @Override
            public void onMessage(byte[] message) {
                side.status = "OK";
                side.payload = UserWireProbe.decode("CreateUserResp", message);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                if (!"OK".equals(side.status)) {
                    side.status = "ERROR_" + status.getCode().name();
                    side.payload = status.getDescription() == null ? "" : status.getDescription();
                }
                side.elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                side.done.countDown();
            }
        }, new Metadata());
        side.call.sendMessage(request);
        side.call.halfClose();
        return side;
    }
}
