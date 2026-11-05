package com.rag.comment.exception;

import com.rag.comment.contract.ErrCodes;

/**
 * <p><b>comment 域业务异常集合</b> —— 承载 B 侧各处
 * {@code errmsg.NewGrpcErr(code, msg)} 的那两个参数，
 * 以及 B 侧 {@code model/vars.go} 的三个 sentinel。
 *
 * <p><b>采用「持有式 + 嵌套静态类」而不是「每种异常一个文件」</b>，与本仓
 * {@code like 域 exception/LikeExceptions.java}、{@code message 域 exception/MessageExceptions.java}
 * 保持同一约定。
 *
 * <p><b>⚠️ L4 落点变化（D7 包压平）</b>：
 * B 侧 {@code com.rag.comment.comment.rpc.exception.CommentExceptions}
 * ⇒ C 侧 {@code com.rag.comment.exception.CommentExceptions}。
 *
 * <h2>为什么要先落成领域异常，再由 gRPC 边界转换</h2>
 *
 * <p>B 侧的 logic 直接 {@code return nil, errmsg.NewGrpcErr(...)}，因为 Go 的一等公民就是
 * 「返回值里带 error」。Java 没有这个语言机制，而本域这些方法<b>还要在 gRPC 与 Kafka 两条链路间复用</b>
 * （CreateComment 只推 Kafka，真正的落库在 AuditConsumer 里做同一条
 * {@code InsertCommentTx}）。等价的只有一条路：
 * unchecked 领域异常 + <b>单一出口转换</b>。
 * 选它之后，wire 上最终仍是<b>同一个业务码 + 同一句原文</b>，可观察行为不变。
 *
 * <h2>⚠️ sentinel 必须是三个独立类型，不能合并</h2>
 *
 * <p>B 侧 {@code model/vars.go:5-9} 有三个 sentinel，它们映射到<b>三个不同的业务码</b>：
 * <pre>
 * ErrorSubjectNotFound  → 1012 ERROR_SUBJECT_NOT_EXIST（评论区缺失）
 * ErrorCommentNotFound  → 1013 ERROR_COMMENT_NOT_EXIST（评论行缺失）
 * ErrorCommentForbidden → 1008 ERROR_USER_NO_RIGHT（鉴权失败）
 * </pre>
 * 且这三个码与「DB 失败」类的 5001/5002/5003/5004 全部不同。
 * 合并任何一个都会让某个调用点发出错误的码。
 *
 * <p><b>⚠️ msg 一律用调用点的原文，不查表。</b>B 侧两者在多处不同：
 * {@code code=5003} 查表得「查询数据库失败」，而 logic 里传的是「DB查询失败」。
 * 这个差值对调用方可见，必须原样搬运（查表会把它改掉）。
 */
public final class CommentExceptions {

    private CommentExceptions() {
        // 异常工厂，禁止实例化
    }

    /**
     * 携带 comment 业务码的领域异常（对应 B 侧的 {@code errmsg.NewGrpcErr(code, msg)}）。
     *
     * <p>{@link #getMessage()} 采用与 {@code contract/CodeError} / gRPC description
     * 同一套 {@code ErrCode:%d, Errmsg:%s} 格式，便于全链路 grep。
     */
    public static class Rpc extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int bizCode;

        /** <b>原始</b>文案（不含 {@code ErrCode:…} 前缀）。 */
        private final String msg;

        public Rpc(int bizCode, String msg) {
            super(String.format("ErrCode:%d, Errmsg:%s", bizCode, msg));
            this.bizCode = bizCode;
            this.msg = msg;
        }

        public Rpc(int bizCode, String msg, Throwable cause) {
            super(String.format("ErrCode:%d, Errmsg:%s", bizCode, msg), cause);
            this.bizCode = bizCode;
            this.msg = msg;
        }

        /** comment 业务码，取值见 {@link ErrCodes}。 */
        public int getBizCode() {
            return bizCode;
        }

        /**
         * <b>原始</b>文案 —— gRPC 边界必须用这个，不能用 {@link #getMessage()}。
         *
         * <p>{@link #getMessage()} 是给日志看的包装格式（{@code ErrCode:X, Errmsg:Y}）；
         * 把它当成 msg 传给 {@code GrpcErrFactory.grpcError(bizCode, msg)}
         * 会得到双重包装，客户端拿到的 msg 与 B 侧不一致。
         */
        public String getMsg() {
            return msg;
        }
    }

    /**
     * 对应 B 侧 {@code model/vars.go:6} {@code ErrorSubjectNotFound} —— 评论区不存在。
     *
     * <p>在 {@code contract/CodeError.findIn} 的语义下，它是「不带业务码的内部信号」，
     * 由调用点决定最终发 1012 还是别的码。
     */
    public static final class SubjectNotFound extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SubjectNotFound() {
            super("subject not found");
        }

        public SubjectNotFound(Throwable cause) {
            super("subject not found", cause);
        }
    }

    /** 对应 B 侧 {@code model/vars.go:7} {@code ErrorCommentNotFound} —— 评论不存在。 */
    public static final class CommentNotFound extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public CommentNotFound() {
            super("comment not found");
        }

        public CommentNotFound(Throwable cause) {
            super("comment not found", cause);
        }
    }

    /** 对应 B 侧 {@code model/vars.go:8} {@code ErrorCommentForbidden} —— 无权执行该操作。 */
    public static final class CommentForbidden extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public CommentForbidden() {
            super("comment operation forbidden");
        }
    }

    // ==================== 与 B 侧调用点一一对应的工厂 ====================

    // ---------- CreateComment（create_comment_logic.go）----------

    /** 对应 {@code create_comment_logic.go:66}：目标类型和 ID 为空。 */
    public static Rpc targetEmpty() {
        return new Rpc(ErrCodes.ERROR_INPUT_WRONG, "目标类型和ID不能为空");
    }

    /** 对应 {@code create_comment_logic.go:73}：评论内容为空。 */
    public static Rpc contentEmpty() {
        return new Rpc(ErrCodes.ERROR_INPUT_WRONG, "评论内容不能为空");
    }

    /** 对应 {@code create_comment_logic.go:82}：评论区不存在。 */
    public static Rpc subjectNotExist() {
        return new Rpc(ErrCodes.ERROR_SUBJECT_NOT_EXIST, "评论区不存在");
    }

    /** 对应 {@code create_comment_logic.go:89}：ownerId 回源时 DB 查询失败。 */
    public static Rpc dbSelectForOwner() {
        return new Rpc(ErrCodes.ERROR_DB_SELECT, "DB查询失败");
    }

    /** 对应 {@code create_comment_logic.go:98}：雪花算法生成 ID 失败。 */
    public static Rpc snowflakeFailed() {
        return new Rpc(ErrCodes.ERROR_SNOWFLAKE_ID, "雪花算法生成ID出错");
    }

    /** 对应 {@code create_comment_logic.go:122}：事件载荷 JSON 序列化失败。 */
    public static Rpc jsonMarshalFailed() {
        return new Rpc(ErrCodes.ERROR_JSON_MARSHAL, "Json序列化失败");
    }

    /** 对应 {@code create_comment_logic.go:130}：Kafka 推送失败。 */
    public static Rpc kafkaPushFailed() {
        return new Rpc(ErrCodes.ERROR_KAFKA_PUSH, "Kafka推送失败");
    }

    // ---------- GetComment（get_comment_logic.go）----------
    // ⚠️ B 侧本用例**从不构造 errmsg.NewGrpcErr**，失败时一律返回裸 error
    //    ⇒ grpc-go 发 codes.Unknown + 原始错误文本。
    //    Java 侧统一走下面的兜底码 5001 + "系统内部错误"（不泄漏 SQL / 连接串）。
    //    该差异已显式登记，见 CommentDomainService#getComment 的类内说明。

    // ---------- DeleteComment（delete_comment_logic.go）----------

    /** 对应 {@code delete_comment_logic.go:61}：评论 ID 与目标不能为空。 */
    public static Rpc deleteTargetEmpty() {
        return new Rpc(ErrCodes.ERROR_INPUT_WRONG, "评论ID和目标不能为空");
    }

    /** 对应 {@code delete_comment_logic.go:70 / :108}：评论不存在。 */
    public static Rpc commentNotExist() {
        return new Rpc(ErrCodes.ERROR_COMMENT_NOT_EXIST, "评论不存在");
    }

    /** 对应 {@code delete_comment_logic.go:86}：评论区不存在。 */
    public static Rpc deleteSubjectNotExist() {
        return new Rpc(ErrCodes.ERROR_SUBJECT_NOT_EXIST, "评论区不存在");
    }

    /** 对应 {@code delete_comment_logic.go:100 / :113}：无权删除。 */
    public static Rpc deleteForbidden() {
        return new Rpc(ErrCodes.ERROR_USER_NO_RIGHT, "无权执行删除操作");
    }

    /** 对应 {@code delete_comment_logic.go:119}：删除事务内 DB 更新失败。 */
    public static Rpc dbUpdateOnDelete() {
        return new Rpc(ErrCodes.ERROR_DB_UPDATE, "DB更新失败");
    }

    // ---------- LikeComment（like_comment_logic.go）----------

    /** 对应 {@code like_comment_logic.go:48}：评论 ID 为空。 */
    public static Rpc likeCommentIdEmpty() {
        return new Rpc(ErrCodes.ERROR_INPUT_WRONG, "评论ID不能为空");
    }

    /** 对应 {@code like_comment_logic.go:57}：评论区不存在。 */
    public static Rpc likeSubjectNotExist() {
        return new Rpc(ErrCodes.ERROR_SUBJECT_NOT_EXIST, "评论区不存在");
    }

    /**
     * 对应 {@code like_comment_logic.go:63}。
     *
     * <p>⚠️ <b>这里必须发 2001 而不是 5004</b>：B 侧的代码是
     * <pre>
     * result = "sys_fail"
     * logger.LogBusinessErr(ctx, errmsg.ErrorDbInsert, err)   // 日志记 5004
     * err = errmsg.NewGrpcErr(errmsg.ErrorInputWrong, "DB更新失败")  // 线上发 2001
     * </pre>
     * 「日志码 5004、线上码 2001」是 B 侧源码的事实，不是笔误 ——
     * 修正它会改变 {@code result} 标签与客户端可见码。照搬。
     */
    public static Rpc likeOwnerLookupFailed() {
        return new Rpc(ErrCodes.ERROR_INPUT_WRONG, "DB更新失败");
    }

    /** 对应 {@code like_comment_logic.go:80}：评论不存在。 */
    public static Rpc likeCommentNotExist() {
        return new Rpc(ErrCodes.ERROR_COMMENT_NOT_EXIST, "评论不存在");
    }

    /** 对应 {@code like_comment_logic.go:86}：互动事务内 DB 更新失败。 */
    public static Rpc dbUpdateOnLike() {
        return new Rpc(ErrCodes.ERROR_DB_UPDATE, "DB更新失败");
    }

    // ---------- ManageComment（manage_comment_logic.go）----------

    /** 对应 {@code manage_comment_logic.go:62}：评论 ID 为空。 */
    public static Rpc manageCommentIdEmpty() {
        return new Rpc(ErrCodes.ERROR_INPUT_WRONG, "评论ID不能为空");
    }

    /** 对应 {@code manage_comment_logic.go:84}：未知操作类型。 */
    public static Rpc unknownManageAction() {
        return new Rpc(ErrCodes.ERROR_INPUT_WRONG, "操作类型输入错误");
    }

    /** 对应 {@code manage_comment_logic.go:92}：评论不存在。 */
    public static Rpc manageCommentNotExist() {
        return new Rpc(ErrCodes.ERROR_COMMENT_NOT_EXIST, "评论不存在");
    }

    /** 对应 {@code manage_comment_logic.go:100}：位图更新失败。 */
    public static Rpc dbUpdateOnManage() {
        return new Rpc(ErrCodes.ERROR_DB_UPDATE, "DB更新失败");
    }

    // ---------- ReportComment（report_comment_logic.go）----------

    /** 对应 {@code report_comment_logic.go:64}：评论 ID 为空。 */
    public static Rpc reportCommentIdEmpty() {
        return new Rpc(ErrCodes.ERROR_INPUT_WRONG, "评论ID不能为空");
    }

    /**
     * 对应 {@code report_comment_logic.go:83}：评论不存在。
     *
     * <p>⚠️ B 侧的 {@code InsertReport} <b>从不返回</b> {@code ErrorCommentNotFound}
     * —— 它就是一条 insert 冲突被吞。这条分支在 B 侧不可达，
     * 这里保留仅为结构对齐，不会有调用方触发。
     */
    public static Rpc reportCommentNotExist() {
        return new Rpc(ErrCodes.ERROR_COMMENT_NOT_EXIST, "评论不存在");
    }

    /** 对应 {@code report_comment_logic.go:91}：举报落库失败。 */
    public static Rpc dbInsertOnReport() {
        return new Rpc(ErrCodes.ERROR_DB_INSERT, "DB插入失败");
    }

    // ---------- UpdateSubjectState（update_subject_state_logic.go）----------

    /** 对应 {@code update_subject_state_logic.go:63}：目标类型和 ID 不能为空。 */
    public static Rpc subjectTargetEmpty() {
        return new Rpc(ErrCodes.ERROR_INPUT_WRONG, "Type和ID不能为空");
    }

    /** 对应 {@code update_subject_state_logic.go:73 / :97}：评论区不存在。 */
    public static Rpc updateSubjectNotExist() {
        return new Rpc(ErrCodes.ERROR_SUBJECT_NOT_EXIST, "评论区不存在");
    }

    /**
     * 对应 {@code update_subject_state_logic.go:88}。
     *
     * <p>⚠️ 文案是「非up主无法评论」—— 出现在<b>改评论区状态</b>的用例里，
     * 与「评论」二字无关。这是 B 侧的原文，客户端可见，照搬不改成「无权修改评论区状态」。
     */
    public static Rpc subjectStateForbidden() {
        return new Rpc(ErrCodes.ERROR_USER_NO_RIGHT, "非up主无法评论");
    }

    /** 对应 {@code update_subject_state_logic.go:105}：状态更新失败。 */
    public static Rpc dbUpdateOnSubjectState() {
        return new Rpc(ErrCodes.ERROR_DB_UPDATE, "DB修改失败");
    }

    // ---------- 兜底 ----------

    /**
     * 对应 B 侧 comment-api 侧 {@code createcommentlogic.go:56-58} 等 6 处的非 status 兜底
     * {@code errmsg.NewErrCode(errmsg.ErrorServerCommon)}。
     *
     * <p>文案走查表得「系统内部错误」。
     */
    public static Rpc serverCommon() {
        return new Rpc(ErrCodes.ERROR_SERVER_COMMON, "系统内部错误");
    }
}
