package com.rag.admin.parity;

import com.rag.proto.admin.AdminServiceGrpc;
import com.rag.proto.admin.BanUserReq;
import com.rag.proto.admin.CreateAdminReq;
import com.rag.proto.admin.CreateInviteCodeReq;
import com.rag.proto.admin.CreateInviteCodeResp;
import com.rag.proto.admin.DeleteUserReq;
import com.rag.proto.admin.GetSelfReq;
import com.rag.proto.admin.GetSelfResp;
import com.rag.proto.admin.GetUserListReq;
import com.rag.proto.admin.GetUserListResp;
import com.rag.proto.admin.GetUserReq;
import com.rag.proto.admin.LoginReq;
import com.rag.proto.admin.ResetUserPasswordReq;
import com.rag.proto.admin.UnBanUserReq;
import com.rag.proto.admin.UpdateUserReq;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>admin 域 · gRPC 面双侧活体对拍探针（E-4）</b> —— 用 **C 侧生成的 proto stub** 同时打
 * <b>B 侧</b>（RAG 仓 · admin-rpc）与 <b>C 侧</b>（RAG-V1 · admin-service）的 gRPC server，
 * 逐个 RPC 比较 <b>status code + description + 响应体</b>。
 *
 * <p><b>为什么能用 C 侧的 stub 打 B 侧</b>：两侧的 {@code admin.proto}
 * <b>契约逐条一致</b>（message 名 / 字段号 / service 名 / 方法名 / {@code package admin}）
 * ⇒ ⇒ 同一份 stub 的<b>方法路径完全相同</b> ⇒ ⇒ 一个 stub 打两侧。
 *
 * <p><b>⚠️ gRPC 面是 uid-based</b>（不是 HTTP 面的 JWT）：
 * {@code GetSelfReq{uid}} / {@code GetUserReq{uid}} / {@code LoginResp{uid}}
 * ⇒ ⇒ 探针先用 {@code GetUserList(keyword="admin")} 拿到 admin 的 uid，再用它驱动后续 RPC
 * ⇒ ⇒ 两侧 uid 不同（Snowflake）⇒ 归一化成 {@code <ADMIN_UID>}。
 *
 * <p><b>用法</b>：
 * <pre>
 * java -cp &lt;proto-classes&gt;:&lt;grpc-jars&gt; com.rag.admin.parity.GrpcParityProbe 127.0.0.1:18081 127.0.0.1:18082
 * </pre>
 */
public final class GrpcParityProbe {

    /** 一次 RPC 调用的结果（归一化后可比）。 */
    record Call(String rpc, int code, String description, String payload) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("用法：GrpcParityProbe <B_ADDR> <C_ADDR> <B_ADMIN_UID> <C_ADMIN_UID>");
            System.exit(2);
        }
        long bAdminUid = Long.parseLong(args[2]);
        long cAdminUid = Long.parseLong(args[3]);
        ManagedChannel bCh = ManagedChannelBuilder.forTarget(args[0]).usePlaintext().build();
        ManagedChannel cCh = ManagedChannelBuilder.forTarget(args[1]).usePlaintext().build();
        try {
            List<Call> b = runAll(bCh, "B", bAdminUid);
            List<Call> c = runAll(cCh, "C", cAdminUid);
            Map<String, Call> bm = index(b), cm = index(c);

            int same = 0;
            List<String> diffs = new ArrayList<>();
            System.out.println("=====================================================================");
            for (String rpc : bm.keySet()) {
                Call x = bm.get(rpc), y = cm.get(rpc);
                boolean eq = x.code() == y.code() && x.description().equals(y.description())
                        && x.payload().equals(y.payload());
                if (eq) {
                    same++;
                    System.out.printf("✅ %-26s code=%d desc=%s payload=%s%n",
                            rpc, x.code(), x.description(), x.payload());
                } else {
                    diffs.add(rpc);
                    System.out.printf("❌ %-26s%n   B: code=%d desc=[%s] payload=%s%n   C: code=%d desc=[%s] payload=%s%n",
                            rpc, x.code(), x.description(), x.payload(),
                            y.code(), y.description(), y.payload());
                }
            }
            System.out.println("=====================================================================");
            System.out.printf("gRPC RPC 用例 %d · 一致 %d · 差异 %d ⇒ %s%n",
                    bm.size(), same, diffs.size(),
                    diffs.isEmpty() ? "✅ gRPC 面两侧一致" : "❌ 存在差异");
            System.exit(diffs.isEmpty() ? 0 : 1);
        } finally {
            bCh.shutdownNow();
            cCh.shutdownNow();
        }
    }

    private static Map<String, Call> index(List<Call> calls) {
        Map<String, Call> m = new LinkedHashMap<>();
        calls.forEach(c -> m.put(c.rpc(), c));
        return m;
    }

    private static List<Call> runAll(ManagedChannel ch, String label, long adminUid) {
        List<Call> out = new ArrayList<>();
        var stub = AdminServiceGrpc.newBlockingStub(ch);

        // ① GetUserList(keyword="e4user") ⇒ fixture 用户在 users_user 表 ⇒ 成功路径
        try {
            GetUserListResp gl = stub.getUserList(
                    GetUserListReq.newBuilder().setPage(1).setPageSize(20).setKeyword("e4user").build());
            out.add(new Call("GetUserList(keyword=e4user)", 0, "OK",
                    "total=" + gl.getTotal() + " found=" + (gl.getListCount() > 0)
                            + " username=" + (gl.getListCount() > 0 ? gl.getList(0).getUsername() : "")));
        } catch (StatusRuntimeException e) {
            out.add(new Call("GetUserList(keyword=e4user)", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        // ② GetUserList 缺 keyword ⇒ 必填校验
        try {
            GetUserListResp gl = stub.getUserList(GetUserListReq.newBuilder().setPage(1).setPageSize(10).build());
            out.add(new Call("GetUserList(no keyword)", 0, "OK", "total=" + gl.getTotal()));
        } catch (StatusRuntimeException e) {
            out.add(new Call("GetUserList(no keyword)", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        // ③ GetSelf(admin uid)
        try {
            GetSelfResp gs = stub.getSelf(GetSelfReq.newBuilder().setUid(adminUid).build());
            out.add(new Call("GetSelf", 0, "OK",
                    "username=" + gs.getAdmin().getUsername() + " email=" + gs.getAdmin().getEmail()
                            + " uid_is_admin=" + (gs.getAdmin().getUid() == adminUid)));
        } catch (StatusRuntimeException e) {
            out.add(new Call("GetSelf", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        // ④ GetSelf(不存在的 uid)
        try {
            stub.getSelf(GetSelfReq.newBuilder().setUid(999999999999L).build());
            out.add(new Call("GetSelf(unknown uid)", 0, "OK", "<no error>"));
        } catch (StatusRuntimeException e) {
            out.add(new Call("GetSelf(unknown uid)", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        // ⑤ GetUser(fixture uid)
        try {
            var gu = stub.getUser(GetUserReq.newBuilder().setUid(1000000000000000001L).build());
            out.add(new Call("GetUser", 0, "OK",
                    "found=" + gu.getFound() + " username=" + gu.getUser().getUsername()));
        } catch (StatusRuntimeException e) {
            out.add(new Call("GetUser", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        // ⑥ CreateInviteCode
        try {
            CreateInviteCodeResp ci = stub.createInviteCode(CreateInviteCodeReq.newBuilder().build());
            out.add(new Call("CreateInviteCode", 0, "OK",
                    "invite_len=" + ci.getInviteCode().length()
                            + " prefix=" + ci.getInviteCode().substring(0, Math.min(4, ci.getInviteCode().length()))));
        } catch (StatusRuntimeException e) {
            out.add(new Call("CreateInviteCode", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        // ⑦ CreateAdmin（邀请码错误 ⇒ 业务错误）
        try {
            stub.createAdmin(CreateAdminReq.newBuilder()
                    .setUsername("e4grpc").setPassword("E4passw0rd")
                    .setInviteCode("ADM-0000000000000000").build());
            out.add(new Call("CreateAdmin(bad invite)", 0, "OK", "<no error>"));
        } catch (StatusRuntimeException e) {
            out.add(new Call("CreateAdmin(bad invite)", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        // ⑧ BanUser / ⑨ UnBanUser（fixture uid）
        try {
            stub.banUser(BanUserReq.newBuilder().setUid(1000000000000000001L).build());
            out.add(new Call("BanUser", 0, "OK", "<ok>"));
        } catch (StatusRuntimeException e) {
            out.add(new Call("BanUser", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }
        try {
            stub.unBanUser(UnBanUserReq.newBuilder().setUid(1000000000000000001L).build());
            out.add(new Call("UnBanUser", 0, "OK", "<ok>"));
        } catch (StatusRuntimeException e) {
            out.add(new Call("UnBanUser", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        // ⑩ UpdateUser（只传 uid + email ⇒ 必填字段缺失 ⇒ 校验错误）
        try {
            stub.updateUser(UpdateUserReq.newBuilder()
                    .setUid(1000000000000000001L)
                    .setUsername("e4user").setPassword("E4passw0rd").setEmail("e4grpc@example.com").build());
            out.add(new Call("UpdateUser(full)", 0, "OK", "<ok>"));
        } catch (StatusRuntimeException e) {
            out.add(new Call("UpdateUser(full)", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        // ⑪ ResetUserPassword
        try {
            stub.resetUserPassword(ResetUserPasswordReq.newBuilder()
                    .setUid(1000000000000000001L).build());
            out.add(new Call("ResetUserPassword", 0, "OK", "<ok>"));
        } catch (StatusRuntimeException e) {
            out.add(new Call("ResetUserPassword", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        // ⑫ DeleteUser（fixture uid）
        try {
            stub.deleteUser(DeleteUserReq.newBuilder().setUid(1000000000000000001L).build());
            out.add(new Call("DeleteUser", 0, "OK", "<ok>"));
        } catch (StatusRuntimeException e) {
            out.add(new Call("DeleteUser", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        // ⑬ Login（错误密码 ⇒ 业务错误）
        try {
            stub.login(LoginReq.newBuilder().setUsername("admin").setPassword("wrong").build());
            out.add(new Call("Login(wrong password)", 0, "OK", "<no error>"));
        } catch (StatusRuntimeException e) {
            out.add(new Call("Login(wrong password)", e.getStatus().getCode().value(),
                    e.getStatus().getDescription(), "<error>"));
        }

        out.forEach(c -> System.out.printf("  [%s] %-28s code=%d desc=%s%n",
                label, c.rpc(), c.code(), c.description()));
        return out;
    }

    private GrpcParityProbe() {
    }
}
