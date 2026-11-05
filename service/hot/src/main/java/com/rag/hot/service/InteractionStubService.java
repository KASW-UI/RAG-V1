package com.rag.hot.service;

import com.rag.proto.hot.CommentArticleResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Service;

/**
 * <p><b>4 个互动方法的既定桩行为</b> —— 对应 B 侧
 * {@code service/hot/hot-rpc/.../rpc/service/InteractionStubService.java}。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/service/InteractionStubService.java}
 * <pre>{@code
 *   类声明                                                   :30
 *   LIKE_UNIMPLEMENTED_MSG / COIN_ / SHARE_ 三个常量          :33 / :37 / :39
 *   likeArticle() ⇒ Status.UNIMPLEMENTED + 文案              :42-44
 *   coinArticle() ⇒ Status.UNIMPLEMENTED + 文案              :47-49
 *   shareArticle() ⇒ Status.UNIMPLEMENTED + 文案             :52-54
 *   commentArticle() ⇒ CommentArticleResponse{Success:true}   :62-64
 *   unknown(description, cause) ⇒ Status.UNKNOWN             :67-69
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯表达层</b>改动 ——
 *       ① 包名 {@code com.rag.hot.hot.rpc.service} → {@code com.rag.hot.service}（D7 包压平）；
 *       ② proto 包名 {@code com.rag.hot.hot.proto} → {@code com.rag.proto.hot}（D7 proto 集中）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 3 个 UNIMPLEMENTED 的<b>文案逐字未动</b>
 *       （{@code "LikeArticle not implemented yet"} 等是 <b>gRPC status 的 description</b>
 *       ⇒ 属 wire 可见文本 ⇒ 不可改）、{@code commentArticle} 仍返回 {@code success=true}。</li>
 *   <li><b>B 出处</b>：见上方逐条行号。</li>
 * </ol>
 *
 * <p><b>⚠️ 必须逐个照抄的不对称</b>：
 * <pre>
 * LikeArticle     → status.Error(codes.Unimplemented, "LikeArticle not implemented yet")
 * CommentArticle  → &amp;pb.CommentArticleResponse{Success: true}, nil        ← 唯一不对称
 * CoinArticle     → status.Error(codes.Unimplemented, "CoinArticle not implemented yet")
 * ShareArticle    → status.Error(codes.Unimplemented, "ShareArticle not implemented yet")
 * </pre>
 * ⇒ <b>3 个 UNIMPLEMENTED + 1 个 {@code success=true}</b>。
 * 若为了「统一风格」把它们全改成 UNIMPLEMENTED（或全改成 success），
 * 就是<b>行为变更</b>：客户端对 {@code CommentArticle} 会从拿到成功变成拿到错误码。
 *
 * <p>四个方法的 request 全部<b>原样忽略</b>（B 侧连字段都没读）。
 */
@Service
public class InteractionStubService {

    /** B 侧 {@code like_article_logic.go:44}。 */
    public static final String LIKE_UNIMPLEMENTED_MSG = "LikeArticle not implemented yet";

    /** B 侧 {@code coin_article_logic.go:36}。 */
    public static final String COIN_UNIMPLEMENTED_MSG = "CoinArticle not implemented yet";

    /** B 侧 {@code share_article_logic.go:36}。 */
    public static final String SHARE_UNIMPLEMENTED_MSG = "ShareArticle not implemented yet";

    /** 对应 B 侧 {@code status.Error(codes.Unimplemented, ...)}。 */
    public StatusRuntimeException likeArticle() {
        return Status.UNIMPLEMENTED.withDescription(LIKE_UNIMPLEMENTED_MSG).asRuntimeException();
    }

    /** 对应 B 侧 {@code coin_article_logic.go:36}。 */
    public StatusRuntimeException coinArticle() {
        return Status.UNIMPLEMENTED.withDescription(COIN_UNIMPLEMENTED_MSG).asRuntimeException();
    }

    /** 对应 B 侧 {@code share_article_logic.go:36}。 */
    public StatusRuntimeException shareArticle() {
        return Status.UNIMPLEMENTED.withDescription(SHARE_UNIMPLEMENTED_MSG).asRuntimeException();
    }

    /**
     * <b>唯一不对称的那个</b>：B 侧 {@code comment_article_logic.go:30}
     * {@code return &pb.CommentArticleResponse{Success: true}, nil}。
     *
     * <p>方案 A 由评论系统自行推 Kafka，本 RPC 仅作同步备选。
     *
     * @return {@code success=true} 的响应
     */
    public CommentArticleResponse commentArticle() {
        return CommentArticleResponse.newBuilder().setSuccess(true).build();
    }

    /** 供 gRPC 层复用的 UNKNOWN 构造：B 侧的裸 error 经 status.FromError 后落到 codes.Unknown。 */
    public static StatusRuntimeException unknown(String description, Throwable cause) {
        return Status.UNKNOWN.withDescription(description).withCause(cause).asRuntimeException();
    }
}
