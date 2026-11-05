import com.rag.proto.task.GetTaskReq;
import com.rag.proto.task.GetTaskResp;
import com.rag.proto.task.Task;
import com.rag.proto.task.TaskServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * task 域双侧活体对拍 —— <b>gRPC 面探针（客户端侧）</b>。
 *
 * <p>用真 netty client 打真 gRPC server，把每次调用渲染成<b>一行可逐字节比对的读数</b>，
 * 交给 {@code grpc_probe.py} 做 B↔C 比对。</p>
 *
 * <h2>🔴 为什么必须有这一面（补的是哪条缺口）</h2>
 * <p>台账 §七·7-1 的 <b>U-6</b>：「<b>gRPC 面尚未双侧活体对拍</b>」
 * —— 现有 {@code tools/difftest/task/} 的四个面（M1 HTTP 字节面 / M2 Redis 状态面 /
 * M3 PG 状态面 / M4 冻结锚面）<b>全是 HTTP 面</b>
 * ⇒ 「B 侧 {@code task-rpc} 与 C 侧 {@code task-service} 在 gRPC 面是否逐字节一致」
 * <b>无任何证据</b>。</p>
 * <p>⇒ 而 <b>proto 一致 ≠ 消费方式已验</b>（AGENTS §3.4 效力边界 ①）：
 * {@code .proto} 逐字节相同，只能证「消息定义」相同，
 * <b>证不了</b>「服务全名 / 错误码 / description / 响应字段在真线路上相同」。</p>
 *
 * <h2>🔴 客户端<b>必须</b>用 {@code forAddress(SocketAddress)} 形式</h2>
 * <p>devh 的 client starter 会把一个 {@code DiscoveryClientResolverFactory} 注册进
 * <b>全局</b> {@link io.grpc.NameResolverRegistry}（priority 6 &gt; DNS 的 5）
 * ⇒ 「不带 scheme 的 gRPC 目标」<b>全 JVM 被劫持</b>
 * ⇒ {@code forAddress("127.0.0.1", port)} 的目标会被拼成
 * {@code discovery:///127.0.0.1:port} ⇒ 去 <b>Nacos</b> 查一个叫「127.0.0.1」的服务
 * ⇒ <b>零实例</b> ⇒ 报极具迷惑性的 {@code UNAVAILABLE: No servers found for 127.0.0.1:port}。</p>
 * <p>⇒ 本探针用 {@code forAddress(new InetSocketAddress(host, port))}
 * —— 该重载<b>不经过名字解析</b> ⇒ <b>免疫劫持</b>。</p>
 *
 * <h2>🔴 读数里<b>刻意不</b>放的东西</h2>
 * <ul>
 *   <li><b>trailers</b>：{@code grpc-status} / {@code grpc-message} 是<b>保留伪头</b>
 *       ⇒ 被 grpc-java <b>消费掉</b> ⇒ <b>不留在 trailers 里</b>
 *       ⇒ <b>文案只能从 {@code Status#getDescription()} 读</b>；</li>
 *   <li><b>channel / 连接细节</b>：两侧框架版本不同 ⇒ 必然不同 ⇒ 比它是假红。</li>
 * </ul>
 *
 * <h2>B 出处（读数里的每一个值都有 B 侧出处）</h2>
 * <table border="1">
 *   <caption>判据 ↔ B 侧出处</caption>
 *   <tr><th>判据</th><th>B 侧</th></tr>
 *   <tr><td>服务全名 {@code task.TaskService/GetTask}</td>
 *       <td>{@code service/task/task-proto/src/main/proto/task.proto:3}（{@code package task;}）
 *           ＋ {@code :23-24}（{@code service TaskService} / {@code rpc GetTask}）</td></tr>
 *   <tr><td>两条定义 190/3 · 1902/5（含中文名与描述）</td>
 *       <td>{@code service/task/task-rpc/.../service/TaskDefs.java:82-84}</td></tr>
 *   <tr><td>{@code user_id <= 0} ⇒ {@code INVALID_ARGUMENT} ＋ {@code "user_id invalid"}</td>
 *       <td>{@code service/task/task-rpc/.../service/TaskDomainService.java:91-93}</td></tr>
 *   <tr><td>Redis 命中 / PG 回源 ⇒ 进度值</td>
 *       <td>{@code service/task/task-rpc/.../service/TaskRedisKeys.java:69}
 *           ＋ {@code …/service/TaskDomainService.java:151-197}（读缓存 / 回源 / 回填）</td></tr>
 *   <tr><td>缓存值格式 {@code status|progress|target}（段数≠3 或第二段非法 ⇒ 进度回落 0）</td>
 *       <td>{@code service/task/task-rpc/.../service/TaskDomainService.java:207-211}（packProgress）
 *           ＋ {@code …/service/TaskDomainService.java:252-258}（unpackProgressValue）</td></tr>
 * </table>
 */
public final class GrpcProbe {

    private GrpcProbe() {
    }

    /**
     * 入口：{@code --cases <file> --port <port> --out <file>}。
     *
     * <p>读数写进 {@code --out}（每行 {@code <用例名>\t<读数>}）⇒ stdout 不打日志，
     * 避免把构建噪声混进比对输入。</p>
     */
    public static void main(String[] args) throws Exception {
        String casesPath = null;
        String outPath = null;
        int port = -1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--cases" -> casesPath = args[++i];
                case "--out" -> outPath = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("未知参数：" + args[i]);
            }
        }
        if (casesPath == null || outPath == null || port <= 0) {
            System.err.println("usage: GrpcProbe --cases <file> --port <port> --out <file>");
            System.exit(2);
        }

        List<String> lines = Files.readAllLines(Paths.get(casesPath), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();

        // 🔴 必须用 SocketAddress 重载：绕过名字解析 ⇒ 免疫 devh 的 discovery scheme 劫持
        ManagedChannel channel = NettyChannelBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", port))
                .usePlaintext()
                .build();
        try {
            TaskServiceGrpc.TaskServiceBlockingStub stub = TaskServiceGrpc.newBlockingStub(channel);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t");
                if (fields.length != 2) {
                    throw new IllegalArgumentException("用例行格式错误（应为 <名称>\\t<uid>）：" + raw);
                }
                String name = fields[0].trim();
                long uid = Long.parseLong(fields[1].trim());
                sb.append(name).append('\t').append(readOnce(stub, uid)).append('\n');
            }
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }

        Files.write(Paths.get(outPath), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 调一次 {@code GetTask} ⇒ 渲染成一行读数。
     *
     * <p>成功：{@code status=OK|description=|task_count=N|t0=<id>|<name>|<desc>|<progress>|<required>|…}</p>
     * <p>失败：{@code status=<CODE>|description=<文案>}</p>
     */
    private static String readOnce(TaskServiceGrpc.TaskServiceBlockingStub stub, long uid) {
        try {
            GetTaskResp resp = stub.getTask(GetTaskReq.newBuilder().setUserId(uid).build());
            StringBuilder sb = new StringBuilder();
            sb.append("status=").append(Status.Code.OK.name());
            sb.append("|description=");
            sb.append("|task_count=").append(resp.getTaskCount());
            for (int i = 0; i < resp.getTaskCount(); i++) {
                Task t = resp.getTask(i);
                sb.append("|t").append(i)
                        .append('=').append(t.getTaskId())
                        .append('|').append(t.getName())
                        .append('|').append(t.getDesc())
                        .append('|').append(t.getCompletionProgress())
                        .append('|').append(t.getRequiredProgress());
            }
            return sb.toString();
        } catch (StatusRuntimeException e) {
            // 🔴 grpc-status / grpc-message 是保留伪头 ⇒ 被 grpc-java 消费掉 ⇒
            //    description 只能从 Status#getDescription() 读（不是 trailers）
            Status st = e.getStatus();
            String desc = st.getDescription() == null ? "" : st.getDescription();
            return "status=" + st.getCode().name() + "|description=" + desc;
        }
    }
}
