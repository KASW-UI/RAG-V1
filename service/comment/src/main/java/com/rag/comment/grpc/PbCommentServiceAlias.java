package com.rag.comment.grpc;

import com.rag.proto.comment.CommentServiceGrpc;
import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * <p><b>{@code pb.CommentService} 兼容别名</b> —— 对应 B 侧
 * {@code comment-rpc/…/rpc/service/impl/PbCommentServiceAlias.java:9-58}。
 *
 * <p><b>⚠️ 类名保留原样</b>（映射表 §0.5 第 81 行：「{@code PbCommentServiceAlias} <b>保留原名</b>」）
 * —— 它是 wire 契约的一部分，改名会让跨栈调用方的排查手段失效。
 *
 * <h2>为什么需要这个别名</h2>
 * <p>B 侧的 proto {@code package} 已签字归一为 {@code comment;}
 * （B 侧 {@code SD-2026-10-23-04}）⇒ 真实方法全名是 {@code /comment.CommentService/*}。
 * <p>但**历史遗留**的调用方（还在用 {@code pb} 前缀的那部分）会请求
 * {@code /pb.CommentService/*} ⇒ 没有这个别名就<b>连不上</b>（UNIMPLEMENTED）。
 * ⇒ 本类把<b>同一份实现</b>再挂到 {@code pb.CommentService} 这个服务名下。
 *
 * <h3>⚠️ 它只解决<b>连通性</b>，不解决<b>业务码</b></h3>
 * <p>grpc-java 只认 17 个标准码 ⇒ 走 {@code pb} 别名的那条链路拿不到业务码 trailer
 * （业务码在 {@code rag-comment-biz-code} trailer 里，由 {@code GrpcErrFactory} 产出
 * —— <b>该 trailer 与服务名无关</b>，所以业务码其实仍可取到；
 * 但 <b>grpc-status 字段的数值</b>在两条链路上都是标准码，无法复原业务码）。
 * ⇒ ⇒ 灰度必须<b>同栈配对</b>（C 侧调 C 侧 / B 侧调 B 侧），跨栈时业务码降级。
 *
 * <h3>⚠️ handler 与 descriptor 无关 ⇒ 直接复用是安全的</h3>
 * <p>handler 在调用时才收到 descriptor
 * （{@code ServerCallHandler#startCall(MethodDescriptor, ServerCallContext)}）
 * ⇒ 两个服务名下<b>行为完全一致</b>。
 */
@GrpcService
public class PbCommentServiceAlias implements BindableService {

    /** 遗留的 Go 侧服务名（B 侧 {@code GO_SERVICE_NAME}）。 */
    public static final String GO_SERVICE_NAME = "pb.CommentService";

    /** 归一后的服务名（B 侧 {@code RAG_SERVICE_NAME}）。 */
    public static final String RAG_SERVICE_NAME = "comment.CommentService";

    private final CommentServiceGrpc.CommentServiceImplBase delegate;

    public PbCommentServiceAlias(CommentServiceGrpc.CommentServiceImplBase delegate) {
        this.delegate = delegate;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ServerServiceDefinition bindService() {
        ServerServiceDefinition original = delegate.bindService();
        ServerServiceDefinition.Builder builder = ServerServiceDefinition.builder(GO_SERVICE_NAME);
        for (ServerMethodDefinition<?, ?> method : original.getMethods()) {
            MethodDescriptor<?, ?> source = method.getMethodDescriptor();
            MethodDescriptor<?, ?> renamed = source.toBuilder()
                    .setFullMethodName(goFullMethodName(source.getFullMethodName()))
                    .build();
            // ⚠️ handler 与 descriptor 无关：它在调用时才收到 descriptor
            // （ServerCallHandler#startCall(MethodDescriptor, ServerCallContext)）
            // ⇒ 直接复用是安全的，且保证两个服务名下**行为完全一致**。
            builder.addMethod((MethodDescriptor) renamed, (ServerCallHandler) method.getServerCallHandler());
        }
        return builder.build();
    }

    /**
     * 把 {@code /comment.CommentService/xxx} 改写成 {@code /pb.CommentService/xxx}。
     *
     * @param ragFullMethodName 归一后的完整方法名
     * @return 遗留前缀的完整方法名
     */
    static String goFullMethodName(String ragFullMethodName) {
        int slash = ragFullMethodName.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("not a full method name: " + ragFullMethodName);
        }
        return GO_SERVICE_NAME + ragFullMethodName.substring(slash);
    }
}
