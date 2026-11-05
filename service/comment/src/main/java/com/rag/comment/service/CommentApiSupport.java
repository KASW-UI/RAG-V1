package com.rag.comment.service;

import com.rag.comment.contract.CodeError;
import com.rag.comment.contract.ErrCodes;
import com.rag.comment.contract.ErrMsg;
import com.rag.comment.contract.GrpcErrFactory;
import com.rag.comment.interceptor.CommentJwtAuthInterceptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * <p><b>comment 域 api 侧协作者</b> —— 对应 B 侧
 * {@code comment-api/…/api/service/CommentApiSupport.java:13-102}。
 *
 * <p><b>⚠️ L4 落点变化（D1 + D7）</b>：
 * B 侧 {@code com.rag.comment.comment.api.service.CommentApiSupport}
 * ⇒ C 侧 {@code com.rag.comment.service.CommentApiSupport}。
 *
 * <p>它<b>不是用例</b>，是<b>协作者</b>（映射表 §1.2 决策锁第 3 条：
 * 「{@code CommentApiSupport}（uid 解析 / 业务码映射）保持独立 —— 它<b>不是用例</b>」）。
 *
 * <h2>🔴 本类的 uid 解析口径与 like 域<b>不同</b>，不可抄 like</h2>
 * <p>B 侧 comment 域的 {@code userId} 接受 <b>String</b> claim：
 * <pre>
 * // Go: case string —— strconv.ParseInt，失败即整体失败
 * if (claim instanceof String s) {
 *     try { return Long.parseLong(s); } catch (NumberFormatException e) { … 1006 }
 * }
 * </pre>
 * <p>⚠️ <b>like 域的同款方法明确<b>拒绝</b>字符串 claim</b>
 * （like 域 {@code LikeApiSupport} 的类注释：「JSON 字符串 claim（含 {@code "123"}）一律拒绝 ⇒ 1006」，
 * 因为 like 域的 Go 出处是 {@code .(json.Number)} 类型断言）。
 * <p>⇒ <b>两个域的 Go 出处不同 ⇒ 判定口径必须不同</b>
 * ⇒ ⇒ 本类<b>逐字保留 B 侧 comment 域的判定</b>（接受可解析为 int64 的字符串）。
 * ⇒ ⚠️ <b>跨域「统一」这条判据 = 引入越权取数风险</b>
 * （把 uid 换成别人的字符串就能通过）。
 *
 * <h3>⚠️ 禁止截断与回绕</h3>
 * <p>浮点字面量与越界整数在 B 侧<b>必然</b>解析失败 ⇒ 一律 1006。
 * ⇒ 禁止 {@code 1.5 ⇒ 1} 的截断，禁止 {@code BigInteger ⇒ 负数} 的回绕 ——
 * 那是<b>静默的错误身份</b>。
 */
@Component
public class CommentApiSupport {

    private static final Logger LOG = LoggerFactory.getLogger(CommentApiSupport.class);

    /**
     * 从 request attribute 取 uid —— 对应 B 侧 {@code CommentApiSupport#userId}。
     *
     * <p>⚠️ 失败 ⇒ {@link CodeError}（{@code ERROR_TOKEN_RUNTIME}=1006，
     * 文案「TOKEN已过期」）⇒ 由 advice 折叠成 <b>HTTP 200 + {code:1006}</b>
     * ⇒ <b>不是 401</b>（鉴权层已放过，claim 取值判定属于业务层）。
     *
     * @param request 当前请求
     * @return uid
     */
    public long userId(HttpServletRequest request) {
        Object claim = request.getAttribute(CommentJwtAuthInterceptor.CLAIM_ATTRIBUTE);
        // Go: case int64 / case int —— jjwt 对范围内的整数字面量给 Integer / Long
        if (claim instanceof Long l) {
            return l;
        }
        if (claim instanceof Integer i) {
            return i.longValue();
        }
        if (claim instanceof Short s) {
            return s.longValue();
        }
        if (claim instanceof Byte b) {
            return b.longValue();
        }
        // ⚠️ Go: case json.Number → value.Int64() → strconv.ParseInt(字面量原文, 10, 64)
        //    JSON 浮点字面量与越界整数在 Go 侧**必然**解析失败 ⇒ 一律 1006。
        //    禁止截断（1.5 ⇒ 1）或回绕（BigInteger ⇒ 负数）：那是静默的错误身份。
        if (claim instanceof BigInteger bi) {
            logTokenClaimRejected("integer claim out of int64 range: " + bi);
            throw tokenRuntime();
        }
        if (claim instanceof Double d) {
            logTokenClaimRejected("float claim is not an int64 literal: " + d);
            throw tokenRuntime();
        }
        if (claim instanceof Float f) {
            logTokenClaimRejected("float claim is not an int64 literal: " + f);
            throw tokenRuntime();
        }
        if (claim instanceof BigDecimal bd) {
            logTokenClaimRejected("decimal claim is not an int64 literal: " + bd);
            throw tokenRuntime();
        }
        // Go: case string —— strconv.ParseInt，失败即整体失败
        // ⚠️ 本域**接受**可解析为 int64 的字符串（与 like 域相反，理由见类注释）。
        if (claim instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                logTokenClaimRejected("string not parseable as int64");
                throw tokenRuntime();
            }
        }
        // Go: raw == nil 或 default 分支
        logTokenClaimRejected(claim == null ? "claim absent" : "unexpected claim type");
        throw tokenRuntime();
    }

    /**
     * 把领域异常 / gRPC 异常映射成 {@link CodeError} —— 对应 B 侧 {@code CommentApiSupport#rpcError}。
     *
     * <p>⚠️ <b>D2（域内直连）的关键适配</b>：B 侧这里收到的是<b>gRPC status 异常</b>
     * （api → {@code CommentRpcClient} → grpc → rpc 进程），业务码藏在 trailer
     * （{@code rag-comment-biz-code}）里、原始文案藏在 description 里。
     * ⇒ D2 之后中间那层 gRPC 往返<b>被取消</b> ⇒ 领域层直接抛
     * {@link com.rag.comment.exception.CommentExceptions.Rpc}（它带 bizCode + msg 两个字段）。
     *
     * ⇒ 这一分支与 B 侧的「gRPC status 分支」<b>逐字等价</b>：
     * <pre>
     *   B: status(description=msg, trailer=bizCode) ⇒ newCodeMsg(bizCode, msg)
     *   C: CommentExceptions.Rpc(bizCode, msg)       ⇒ newCodeMsg(bizCode, msg)
     * </pre>
     * ⚠️ <b>绝不能</b>把它当成「非 status 异常」⇒ 那会落进下面的 5001 分支
     * ⇒ 业务码被换掉（5003 → 5001）且文案从「DB查询失败」变成「系统内部错误」。
     *
     * @param err 任意异常
     * @return 可直接进 envelope 的业务错误
     */
    public CodeError rpcError(Throwable err) {
        if (err instanceof com.rag.comment.exception.CommentExceptions.Rpc business) {
            return CodeError.newCodeMsg(business.getBizCode(), business.getMsg());
        }
        Status status = statusOf(err);
        if (status == null) {
            // Go: status.FromError 的 ok == false 分支 —— 注意是 5001，不是 1015
            return CodeError.newCode(ErrCodes.ERROR_SERVER_COMMON);
        }
        int bizCode = GrpcErrFactory.bizCodeFromError(err);
        String description = status.getDescription();
        return CodeError.newCodeMsg(bizCode, description == null ? "" : description);
    }

    private static Status statusOf(Throwable err) {
        if (err instanceof StatusRuntimeException sre) {
            return sre.getStatus();
        }
        if (err instanceof StatusException se) {
            return se.getStatus();
        }
        return null;
    }

    /**
     * Go {@code NewErrCode(ErrorTokenRuntime)} 的等价物：<b>查表</b>得到
     * {@code "TOKEN已过期"}，而不是把 Go 那条日志文本塞进 msg。
     */
    private static CodeError tokenRuntime() {
        return CodeError.newCode(ErrCodes.ERROR_TOKEN_RUNTIME);
    }

    private static void logTokenClaimRejected(String reason) {
        LOG.warn("extract userId from context failed: {}", reason);
    }

    /**
     * 兜底文案查询（供本域其它协作者复用）。
     *
     * @param code 业务码
     * @return 文案
     */
    public static String msgOf(int code) {
        return ErrMsg.getMsg(code);
    }
}
