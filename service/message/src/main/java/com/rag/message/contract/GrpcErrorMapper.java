package com.rag.message.contract;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>message 域 gRPC 错误转换工具</b> —— 业务码 ⇄ gRPC status 的双向转换 + status 提取。
 *
 * <p><b>B 出处</b>：{@code service/message/message-common/src/main/java/com/rag/message/message/common/errmsg/MessageGrpcError.java}
 * <pre>{@code
 *   工具类声明 + 私有构造器（禁止 new）          :45-49
 *   newCode(int)      —— 按码值查表造 CodeError   :112-114
 *   newCodeMsg(int,String) —— 不查表，直接用传入文案 :120-122
 *   grpcError(Status.Code,int) —— 业务码查表文案塞进 status description :135-139
 *   statusMessage(Throwable)   —— 沿 cause 链取 status 的 description     :154-161
 *   msg → code 反查表（BIZ_CODE_BY_MSG）+ 构建    :185-198
 *   bizCodeFromError(Throwable) —— 还原业务码（判定顺序见下方专段）       :217-248
 *   mapStatusCodeToBizCode(Status.Code) —— gRPC code class 兜底映射      :255-272
 *   extractStatus(Throwable)     —— 沿 cause 链找 status（带自引用防环）   :300-314
 *   FoundStatus 载体（status + 是否解包命中）      :325-326
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯表达层</b>拆分 ——
 *       ① B 侧一个 {@code MessageGrpcError} 同时装「工具方法」与「内部类 {@code CodeError}」，
 *       C 侧拆成两个顶层类：{@code GrpcErrorMapper}（本类，装工具方法）＋
 *       {@link CodeError}（错误载体，{@code contract/CodeError.java}）；
 *       ② 类名 {@code MessageGrpcError} → {@code GrpcErrorMapper}、
 *       包名 {@code com.rag.message.message.common.errmsg} → {@code com.rag.message.contract}（D7 包压平）；
 *       ③ 反查表的<b>数据来源</b>改为 {@link ErrMsg#allMsgs()}（B 侧 :189 直接读同包的
 *       {@code MessageErrorMessages.MSG_BY_CODE}）—— 可见性收窄后的等价出口，<b>表内容逐条相同</b>。</li>
 *   <li><b>语义还一样吗</b>：一样 ——
 *       ① 三个转换方法的分支、顺序、返回值口径逐条未动（详见各方法 javadoc 的逐条对照）；
 *       ② 反查表的<b>构建规则</b>（排除 {@code SUCCESS} / {@code ERROR} + first-write-wins 去重）逐条未动
 *       ⇒ 20 条文案唯一 ⇒ 反查实际是 1:1；
 *       ③ {@code FoundStatus} 的「直接命中 / 沿 cause 链解包命中」标记语义逐条未动
 *       ⇒ 解包路径用外层异常文本覆盖 description 这一行为保持不变。</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>⚠️ 判定顺序（B 侧 :230-232 的代码为准）</b>：{@link #bizCodeFromError(Throwable)} 的顺序是
 * <b>先判 {@link CodeError}（取它的 {@code code}）→ 再沿 cause 链找 gRPC status → 最后兜底 {@code 1015}</b>。
 * ⚠️ B 侧类注释 :214-215 写的「先识别 status 再识别 CodeError」是<b>过期散文</b>，与 B 侧自己的代码
 * （:230-232）相反；B 侧内联注释 :222-229 明确记载了这一点（Go 的顺序是「先 CodeError 再 status」，
 * 并写明「此前把这两步对调了……那是自己发明的理由，照 Go 排，别再对调」）
 * ⇒ 本实现按 <b>B 侧代码</b>（= Go 顺序）落，不按过期散文落。
 *
 * <p><b>⚠️ 逐域隔离纪律</b>：本类的反查表与兜底表只在本域自洽
 * （本域文案是英文；各域文案不同 ⇒ 反查表<b>不可跨域复用</b>）。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：B 侧是手写工具类（私有构造 + 静态方法 + 静态初始化块 + 私有 record），
 * 本类为逐字照抄；{@code @UtilityClass} 在本仓被明令禁止 ⇒ 不引入。
 */
public final class GrpcErrorMapper {

    private GrpcErrorMapper() {
        // 工具类，禁止 new（B 侧 :47-49）
    }

    /**
     * 按业务码造一个 {@link CodeError} —— B 侧 {@code newCode(int)}（:112-114）。
     *
     * <p><b>语义还一样吗</b>：一样 —— 先用 {@link ErrMsg#getMsg(int)} 查表得到文案，再构造 {@link CodeError}；
     * 查表未命中时文案为 {@code "FAIL"}（回落语义见 {@link ErrMsg#getMsg(int)}）⇒ 与 B 侧逐条相同。
     *
     * @param code 业务码
     * @return 携带该码值与对应文案的 {@link CodeError}
     */
    public static CodeError newCode(int code) {
        return new CodeError(code, ErrMsg.getMsg(code));
    }

    /**
     * 按业务码 + <b>指定文案</b>造一个 {@link CodeError}（<b>不查表</b>）—— B 侧 {@code newCodeMsg(int, String)}（:120-122）。
     *
     * <p><b>语义还一样吗</b>：一样 —— 直接使用传入的 msg，不做任何查表或改写。
     *
     * @param code 业务码
     * @param msg  业务文案（<b>原样使用</b>）
     * @return 携带该码值与该文案的 {@link CodeError}
     */
    public static CodeError newCodeMsg(int code, String msg) {
        return new CodeError(code, msg);
    }

    /**
     * 造一个 gRPC {@link StatusException}：gRPC code 用入参，业务码的<b>文案</b>塞进 status description
     * —— B 侧 {@code grpcError(Status.Code, int)}（:135-139）。
     *
     * <p><b>这是 Go 服务之间约定俗成的「业务码藏在 gRPC status 文案里」的 wire 格式</b>：
     * 客户端拿到 gRPC error 后调 {@link #bizCodeFromError(Throwable)}，先用 description 反查回业务码，
     * 反查失败再按 gRPC code class 兜底。属 L3 行为相关技术语义，必须保持。
     *
     * <p><b>语义还一样吗</b>：一样 —— {@code Status.fromCode(grpcCode).withDescription(文案).asException()}
     * 三步逐条未动（gRPC code 在 wire 的 status 字段，description 在 message 字段）。
     *
     * @param grpcCode gRPC 标准码
     * @param bizCode  业务码（只用于查文案）
     * @return 携带 description 的 {@link StatusException}
     */
    public static StatusException grpcError(Status.Code grpcCode, int bizCode) {
        return Status.fromCode(grpcCode)
                .withDescription(ErrMsg.getMsg(bizCode))
                .asException();
    }

    /**
     * 取 gRPC status 的 message（即 description）—— B 侧 {@code statusMessage(Throwable)}（:154-161）。
     *
     * <p><b>语义还一样吗</b>：一样 ——
     * ① 沿 cause 链找 status（{@link #extractStatus(Throwable)}），<b>直接命中</b>时返回
     * {@code status.getDescription()}；<b>解包命中</b>时返回<b>外层</b>异常的文本
     * （复刻 grpc-go {@code status.FromError} 在解包路径上的 {@code p.Message = err.Error()}）；
     * ② 整条链上都没有 status ⇒ 返回 {@code null}（对应 Go 的 {@code ok=false}）。
     *
     * <p><b>为什么存在</b>：B 侧 {@code message-api} 的 {@code shared.RPCError} 也需要这份 message
     * 来决定 envelope 的 {@code msg} 字段 ⇒ 两侧各写一遍 {@code instanceof} 迟早分叉，
     * 而分叉的后果是「码对了、msg 错了」这种最难在端到端测试里看出来的不一致。
     *
     * @param err 任意异常（可含 cause 链）
     * @return status description；链上无 status 时返回 {@code null}
     */
    public static String statusMessage(Throwable err) {
        FoundStatus found = extractStatus(err);
        if (found == null) {
            return null;
        }
        // 与 Go 的 `p.Message = err.Error()` 对齐：解包路径用外层文本
        return found.wrapped() ? err.getMessage() : found.status().getDescription();
    }

    /**
     * msg → code 反查表 —— B 侧 {@code BIZ_CODE_BY_MSG}（:185-198）。
     *
     * <p><b>构建规则（逐条照抄 B 侧 :187-198）</b>：
     * <ul>
     *   <li>遍历<b>全部</b> code → msg（本域 20 条，来源 {@link ErrMsg#allMsgs()}）；</li>
     *   <li>排除 {@link ErrCodes#SUCCESS} / {@link ErrCodes#ERROR} —— 它们的文案
     *       （{@code "OK"} / {@code "FAIL"}）过于泛化，反查会乱套；</li>
     *   <li>first-write-wins 去重（{@code putIfAbsent}）—— 本域 20 条文案互不相同 ⇒ 实际是 1:1 反查；
     *       保留去重逻辑防止以后加重复项时反查行为漂移。</li>
     * </ul>
     */
    private static final Map<String, Integer> BIZ_CODE_BY_MSG;

    static {
        Map<String, Integer> reverse = new HashMap<>();
        for (Map.Entry<Integer, String> entry : ErrMsg.allMsgs().entrySet()) {
            int code = entry.getKey();
            if (code == ErrCodes.SUCCESS.getCode()
                    || code == ErrCodes.ERROR.getCode()) {
                continue;
            }
            reverse.putIfAbsent(entry.getValue(), code);
        }
        BIZ_CODE_BY_MSG = Collections.unmodifiableMap(reverse);
    }

    /**
     * 从 gRPC / 业务异常中<b>还原业务码</b> —— B 侧 {@code bizCodeFromError(Throwable)}（:217-248）。
     *
     * <p><b>逐条行为（对 B 侧 :217-248）</b>：
     * <ul>
     *   <li>{@code err == null} ⇒ {@link ErrCodes#SUCCESS}（200）；</li>
     *   <li>是 {@link CodeError} ⇒ <b>直接返回它的 {@code code}</b>（不看表、不看 gRPC code）；</li>
     *   <li>沿 cause 链找到 gRPC status ⇒ 先用 description 反查 {@code BIZ_CODE_BY_MSG}，
     *       查不到再按 gRPC code class 兜底映射（{@link #mapStatusCodeToBizCode(Status.Code)}）；</li>
     *   <li>其它任意 {@link Throwable} ⇒ {@link ErrCodes#CODE_SERVER_BUSY}（1015）。</li>
     * </ul>
     *
     * <p><b>⚠️ 判定顺序必须与 B 侧一致</b>：<b>先判 {@link CodeError}，再判 status</b>（B 侧代码 :230-232）。
     * B 侧类注释 :214-215 那句「先识别 status」是过期散文，与 B 侧代码相反
     * （B 侧内联注释 :222-229 写明「此前把这两步对调了……照 Go 排，别再对调」）⇒ 本实现按代码落。
     *
     * <p><b>⚠️ 解包路径的 description 覆盖</b>：沿 cause 链<b>解包</b>命中时，用<b>外层</b>异常文本
     * 覆盖 description（复刻 grpc-go {@code status.FromError} 的 {@code p.Message = err.Error()}）
     * ⇒ 此时按 description 反查业务码会<b>查不中</b> ⇒ 最终落到 gRPC code class 兜底；
     * <b>直接命中</b>时不做覆盖（B 侧 :236-239 的同一段逻辑）。
     *
     * @param err 任意异常（可含 cause 链）
     * @return 业务码
     */
    public static int bizCodeFromError(Throwable err) {
        if (err == null) {
            return ErrCodes.SUCCESS.getCode();
        }

        // ⚠️ 顺序必须与 B 侧完全一致（B 侧 MessageGrpcError.java:230-232）：**先判 CodeError，再判 status**。
        // B 侧内联注释 :222-229 写明：Go 的原文是
        //     if codeErr, ok := err.(*CodeError); ok { return codeErr.Code }
        //     st, ok := status.FromError(err)
        // 本实现此前把这两步对调了，并写成「status 也可能裹着业务码，所以先看 status」——
        // 那是**自己发明的理由**，Go 里没有。今天两类在 Java 里互斥（CodeError 不是
        // StatusException）所以不可观察，但一旦 CodeError 开始携带 status 载荷，
        // 两侧就会给出不同的码。照 Go 排，别再对调。
        if (err instanceof CodeError) {
            return ((CodeError) err).getCode();
        }

        FoundStatus found = extractStatus(err);
        if (found != null) {
            // ⚠️ 这一行对应 grpc-go 的 `p.Message = err.Error()`（仅在**解包**路径上发生）：
            //    Go 用**外层**异常文本覆盖 description，于是按 description 反查业务码会查不中，
            //    最终落到下面的 code class 映射。直接命中时不做覆盖。
            String description = found.wrapped() ? err.getMessage() : found.status().getDescription();
            Integer code = BIZ_CODE_BY_MSG.get(description);
            if (code != null) {
                return code;
            }
            return mapStatusCodeToBizCode(found.status().getCode());
        }

        return ErrCodes.CODE_SERVER_BUSY.getCode();
    }

    /**
     * gRPC code class → 业务码的兜底映射 —— B 侧 {@code mapStatusCodeToBizCode(Status.Code)}（:255-272）。
     *
     * <p><b>逐条对照（B 侧 :256-271 的 switch）</b>：
     * <pre>
     *   INVALID_ARGUMENT  → 5003 ERROR_INVALID_PARAM
     *   UNAUTHENTICATED   → 5004 ERROR_UNAUTHORIZED
     *   PERMISSION_DENIED → 5005 ERROR_FORBIDDEN
     *   ALREADY_EXISTS    → 5006 ERROR_ALREADY_EXIST
     *   NOT_FOUND         → 5002 ERROR_NOT_FOUND
     *   INTERNAL          → 5001 ERROR_SERVER_COMMON
     *   default（其它）    → 1015 CODE_SERVER_BUSY
     * </pre>
     *
     * @param grpcCode gRPC 标准码
     * @return 业务码
     */
    private static int mapStatusCodeToBizCode(Status.Code grpcCode) {
        switch (grpcCode) {
            case INVALID_ARGUMENT:
                return ErrCodes.ERROR_INVALID_PARAM.getCode();
            case UNAUTHENTICATED:
                return ErrCodes.ERROR_UNAUTHORIZED.getCode();
            case PERMISSION_DENIED:
                return ErrCodes.ERROR_FORBIDDEN.getCode();
            case ALREADY_EXISTS:
                return ErrCodes.ERROR_ALREADY_EXIST.getCode();
            case NOT_FOUND:
                return ErrCodes.ERROR_NOT_FOUND.getCode();
            case INTERNAL:
                return ErrCodes.ERROR_SERVER_COMMON.getCode();
            default:
                return ErrCodes.CODE_SERVER_BUSY.getCode();
        }
    }

    /**
     * 取出 gRPC status —— <b>沿 cause 链向上找</b>（B 侧 {@code extractStatus(Throwable)}，:300-314）。
     *
     * <p><b>语义还一样吗</b>：一样 ——
     * ① 逐个检查链上的异常是否为 {@link StatusException} / {@link StatusRuntimeException}；
     * ② 命中时用「是否等于最初的 {@code err}」区分<b>直接命中</b>与<b>解包命中</b>
     *   （B 侧 {@code t != err}，:303 / :306）⇒ 下游才能复刻 grpc-go 的「外层文本覆盖 description」；
     * ③ 自引用的 cause（{@code t.getCause() == t}）会 break 防死循环（B 侧 :309-311）；
     * ④ 链上无 status ⇒ 返回 {@code null}（对应 Go 的 {@code ok=false}）。
     *
     * <p>⚠️ <b>不是只做 {@code instanceof}</b>：gRPC-Java 的异步 stub 会抛 {@code CompletionException}、
     * Spring 的 {@code @Async}/AOP 也常包一层，这些都不是 {@code StatusRuntimeException} 本身
     * ⇒ 只做 instanceof 会直接落到兜底 {@code 1015 service busy}，而 Go 会解包后按
     * gRPC code class 映射（NOT_FOUND ⇒ {@code 5002} 等）。
     *
     * @param err 任意异常
     * @return 命中的 status 载体；链上无 status 时返回 {@code null}
     */
    private static FoundStatus extractStatus(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof StatusException se) {
                return new FoundStatus(se.getStatus(), t != err);
            }
            if (t instanceof StatusRuntimeException sre) {
                return new FoundStatus(sre.getStatus(), t != err);
            }
            // 防环：自引用的 cause 会让上面的循环变成死循环
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /**
     * 「找到了 status」的返回载体 —— B 侧 {@code FoundStatus}（:325-326）。
     *
     * <p><b>用途只有一个</b>：忠实复刻 grpc-go {@code status.FromError} 在解包路径上的那句
     * {@code p.Message = err.Error()} —— 解包时 Go 会用<b>外层</b>异常的文本覆盖 description。
     * 于是「按 description 反查业务码」会<b>查不中</b>，最终按 gRPC code class 兜底。
     * 不复刻这一步，解包路径就会比 Go 多命中一层反查，码可能从 {@code 5002} 变成别的值。
     *
     * @param status  命中的 gRPC status
     * @param wrapped 是否沿 cause 链<b>解包</b>命中（{@code false} = 直接命中）
     */
    private record FoundStatus(Status status, boolean wrapped) {
    }
}
