package com.rag.article.exception;

import io.grpc.Status;

/**
 * <p><b>职责</b>：article 域的 gRPC 状态异常集合（异常工厂）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-rpc/src/main/java/com/rag/article/article/rpc/exception/ArticleExceptions.java}
 * （类声明 {@code :47-49} · {@code Rpc} {@code :55-75} · 各工厂方法 {@code :81-192}
 * · {@code Raw} {@code :194-203}）⇒ <b>整段照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.article.article.rpc.exception} →
 *       {@code com.rag.article.exception}，D7 包压平）＋ <b>本段 javadoc</b>补上「错误模型与 like 域相反」的论证。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 每个工厂的 gRPC code 与<b>文案逐字</b>一致
 *       （文案会出现在日志与 grpcurl 输出里，是可观察的）。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>🔴 本域与 like 域的错误模型<b>相反</b>，不要照抄 like</h2>
 * <ul>
 *   <li><b>like 域</b>：业务码直接塞进 {@code grpc-status}（{@code status.Error(codes.Code(5003), …)}），
 *       api 侧 {@code status.FromError} 后取 {@code int(st.Code())} 反推业务码。</li>
 *   <li><b>article 域</b>：rpc 侧发的是<b>标准 gRPC 码</b>
 *       （{@code status.Error(codes.Internal, err.Error())} 等），
 *       api 侧在 logic 里<b>显式映射</b>回业务码：
 *       <pre>
 *       get_author_article_logic.go:44-52 / delete_article_logic.go:51-58 / update_article_logic.go:52
 *         codes.NotFound         → errmsg.ErrorArticleNone(1002)
 *         codes.PermissionDenied → errmsg.ErrorArticleForbidden(1006)
 *         codes.Internal         → errmsg.ErrorServerCommon(5001)
 *         default                → errmsg.CodeServerBusy(1015)
 *       </pre>
 *       ⇒ <b>业务码从不上线</b>。</li>
 * </ul>
 *
 * <p>因此本类<b>只承载 gRPC status</b>，不引入 {@code contract.ErrCodes}：
 * 那些码只在<b>业务错误日志字段</b>与 api 层信封里出现，不参与 rpc 的 wire 契约。
 * {@code contract.ErrCodes} 仍被领域层用于
 * {@code Logger.logBusinessErr(ctx, code, err)} 的日志埋点，
 * 那条路径<b>只打日志、不影响返回值</b>。
 *
 * <h2>⚠️「裸返回 err」的路径必须原样保留，不能一律包成 Internal</h2>
 * B 侧有若干处直接 {@code return nil, err}（把 ORM / MinIO 的原始 error 抛上线）。
 * grpc-go 会把<b>非 status 的普通 error</b> 映射成 {@code codes.Unknown}，
 * 而 api 侧的 {@code default} 分支把 {@code Unknown} 映射成 {@code CodeServerBusy(1015)}。
 * ⇒ 这些路径在 Java 侧必须抛<b>普通异常</b>（最终由 grpc 边界转成 {@code Unknown}），
 * <b>不能</b>包成 {@link #internal} —— 那会把客户端看到的业务码从 1015 变成 5001。
 * 见 {@link #raw} 的说明。
 */
public final class ArticleExceptions {

    private ArticleExceptions() {
        // 异常工厂，禁止实例化
    }

    /**
     * 携带 gRPC {@link Status.Code} 的领域异常 —— 对应 B 侧的 {@code status.Error(code, msg)}。
     *
     * <p>异常本身是 unchecked，业务码由 {@code grpc.ArticleGrpcService} 在 gRPC 边界统一转换。
     */
    public static class Rpc extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Status.Code code;

        public Rpc(Status.Code code, String msg) {
            super(msg);
            this.code = code;
        }

        public Rpc(Status.Code code, String msg, Throwable cause) {
            super(msg, cause);
            this.code = code;
        }

        public Status.Code getCode() {
            return code;
        }
    }

    // ==================== 与 B 侧调用点一一对应的工厂 ====================

    /**
     * 对应 {@code get_article_logic.go:45}：
     * {@code status.Error(codes.InvalidArgument, "author read cannot increment view")}。
     *
     * <p>⚠️ <b>message 必须逐字一致</b>：grpc-java 会把它放进
     * {@code StatusRuntimeException.getMessage()}，api 侧虽然只取 code，
     * 但该文案会出现在日志与 {@code grpcurl} 输出里。
     */
    public static Rpc authorReadCannotIncrementView() {
        return new Rpc(Status.Code.INVALID_ARGUMENT, "author read cannot increment view");
    }

    /** 对应 {@code get_article_logic.go:58}：{@code status.Error(codes.NotFound, "article not found")}。 */
    public static Rpc articleNotFound() {
        return new Rpc(Status.Code.NOT_FOUND, "article not found");
    }

    /**
     * 对应 {@code get_article_logic.go:67}：
     * {@code status.Error(codes.PermissionDenied, "article author differs")}。
     *
     * <p>⚠️ 与 {@link #forbidden} <b>是两条不同的判据、文案也不同</b>：
     * 本条是 Get 的作者校验（读），{@code forbidden} 是 Delete 的操作人校验（删）。
     * 合并成一个工厂会改掉调用方看到的 message。
     */
    public static Rpc articleAuthorDiffers() {
        return new Rpc(Status.Code.PERMISSION_DENIED, "article author differs");
    }

    /**
     * 对应 {@code delete_article_logic.go:59}：
     * {@code status.Error(codes.PermissionDenied, "forbidden")}。
     *
     * <p>⚠️ 覆盖两种情形（B 侧的 {@code in.GetOperatorId() == "" || != article.AuthorID} 合并为一个错误）：
     * <b>未传操作人</b>与<b>操作人不是作者</b>返回<b>同一个</b>码与文案。
     * 不要"改进"成区分二者 —— 那会让未传操作人的请求从 1006 变成别的码。
     */
    public static Rpc forbidden() {
        return new Rpc(Status.Code.PERMISSION_DENIED, "forbidden");
    }

    /** 对应 {@code get_article_logic.go:129}：{@code status.Error(codes.NotFound, "article not published")}。 */
    public static Rpc articleNotPublished() {
        return new Rpc(Status.Code.NOT_FOUND, "article not published");
    }

    /** 对应 {@code get_article_logic.go:133}：{@code status.Error(codes.Internal, "read publication failed")}。 */
    public static Rpc readPublicationFailed() {
        return new Rpc(Status.Code.INTERNAL, "read publication failed");
    }

    /** 对应 {@code get_article_logic.go:146,152}：{@code status.Error(codes.Internal, "read legacy article failed")}。 */
    public static Rpc readLegacyArticleFailed() {
        return new Rpc(Status.Code.INTERNAL, "read legacy article failed");
    }

    /** 对应 {@code get_article_logic.go:160}：{@code status.Error(codes.Internal, "increment public view failed")}。 */
    public static Rpc incrementPublicViewFailed() {
        return new Rpc(Status.Code.INTERNAL, "increment public view failed");
    }

    /** 对应 {@code get_article_logic.go:163}：{@code status.Error(codes.NotFound, "article no longer published")}。 */
    public static Rpc articleNoLongerPublished() {
        return new Rpc(Status.Code.NOT_FOUND, "article no longer published");
    }

    /**
     * 对应 B 侧的 {@code status.Error(codes.Internal, err.Error())} —— <b>直接用原始 error 的 message</b>。
     *
     * <p>用法：{@code internal("failed to read object", e)}。
     * ⚠️ B 侧是把 {@code err.Error()} <b>整句</b>放进 status description，
     * 这里保持"传入的 msg 就是最终上线的文案"这一约定，
     * 由调用点决定传什么，<b>不在这里做包装</b>。
     */
    public static Rpc internal(String msg) {
        return new Rpc(Status.Code.INTERNAL, msg);
    }

    public static Rpc internal(String msg, Throwable cause) {
        return new Rpc(Status.Code.INTERNAL, msg, cause);
    }

    /**
     * 🔴 对应 B 侧的<b>裸 {@code return nil, err}</b>（不带 status 包装）。
     *
     * <p><b>为什么要专门一个工厂</b>：B 侧这些路径把 ORM / MinIO 的原始 error 直接返回，
     * grpc-go 见到非 status error 会包成 {@code codes.Unknown}；
     * api 侧的 {@code default} 分支把 {@code Unknown} 映射成 {@code errmsg.CodeServerBusy(1015)}。
     * ⇒ <b>客户端看到的是 1015，不是 5001。</b>
     *
     * <p>本工厂抛出的异常<b>没有</b> gRPC status，由
     * {@code grpc.ArticleGrpcService} 统一转成 {@code StatusRuntimeException(Status.UNKNOWN, msg)}，
     * 从而复刻这条链路。
     *
     * <p>涉及的 B 侧位置（逐条）：
     * <ul>
     *   <li>{@code get_article_logic.go:62}（FindOne 的非 NotFound 错误）</li>
     *   <li>{@code get_article_logic.go:90,100}（MinIO get / read 失败）</li>
     *   <li>{@code update_article_logic.go:57}（FindOne 失败）、{@code :226}（事务失败）</li>
     *   <li>{@code delete_article_logic.go:51}（FindOne 失败）、{@code :95}（事务失败）</li>
     *   <li>{@code list_articles_logic.go:68,116}（列表查询失败）</li>
     *   <li>{@code upload_file_logic.go:51,80}（雪花 ID / MinIO put 失败）</li>
     * </ul>
     */
    public static RuntimeException raw(Throwable cause) {
        return new Raw(cause.getMessage(), cause);
    }

    /**
     * 对应 B 侧的 <b>裸 {@code return nil, err}</b>（{@code gorm.ErrRecordNotFound} 的
     * <b>not-found</b> 场景 —— 不是常见的 {@code Internal} 或暗静的）。
     *
     * <p>注意这与 {@link #articleNotFound()} 的区别：
     * <ul>
     *   <li>{@link #articleNotFound()} → {@code NOT_FOUND("article not found")}，
     *       对应 {@code get_article_logic.go:58} —— {@code GetArticle} <b>显式判了</b>
     *       {@code gorm.ErrRecordNotFound} 才返回 NotFound；</li>
     *   <li>本方法 → {@code UNKNOWN("record not found")}，对应
     *       {@code update_article_logic.go:57} / {@code delete_article_logic.go:51} 的<b>裸返回</b>
     *       —— 那两处<b>没有</b> NotFound 分支；</li>
     * </ul>
     *
     * <p>文案为什么是 {@code "record not found"}：B 侧 {@code model/article.go:83-86}
     * 直接返回 ORM 的 error，其 {@code Error()} 字面量就是这一句，
     * grpc-go 把它整句放进 status description。
     * <br>两者的 <b>code 相同</b>（B 侧都是 UNKNOWN → api {@code default} → 1015），
     * 差别只在 description —— 但它是 B 侧真实上线的字面量，
     * 按它建的日志检索规则在两侧必须能互相命中。
     */
    public static RuntimeException articleNotFoundRaw() {
        return new Raw("record not found", null);
    }

    /**
     * {@link #raw} 的载体：<b>刻意不是</b> {@link Rpc} —— 它没有 gRPC status，
     * 边界必须把它映射成 {@code UNKNOWN} 而不是 {@code INTERNAL}。
     */
    public static class Raw extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public Raw(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
