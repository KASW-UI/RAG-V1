package com.rag.hot.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p><b>hot 域 code → msg 文案表</b> —— <b>11 条文案的唯一真源</b>（L1 契约 · 含语言）。
 *
 * <p><b>B 出处</b>：{@code service/hot/hot-common/src/main/java/com/rag/hot/hot/common/errmsg/HotErrorMessages.java}
 * <pre>{@code
 *   工具类声明 + 私有构造器（禁止 new）                       :41-45
 *   static final Map<Integer,String> MSG_BY_CODE（包级私有）  :54
 *   static 初始化块（11 条 put，顺序 = Go codeMsg 书写序）      :56-72
 *   getMsg(int) —— 未知码回退 codeMsg[Error] = "FAIL"          :80-86
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：两处<b>纯命名 / 定位</b>改动 ——
 *       ① 类型名 {@code HotErrorMessages} → {@code ErrMsg}（本仓域内契约包的既定类名）；
 *       ② 包名 {@code com.rag.hot.hot.common.errmsg} → {@code com.rag.hot.contract}（D7 包压平）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 表的<b>键序</b>、<b>键值</b>、<b>不可变包装</b>、
 *       {@code getMsg} 的「未知码回退 {@code "FAIL"}」逐条未动。</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>⚠️「未知码返回 {@code "FAIL"}」是客户端可观察的</b>：B 侧 hot-api 的兜底出口
 * （B 侧 {@code hot-api/.../api/advice/GlobalExceptionHandler.java:151-155}）在错误不是
 * {@code *hotcommon.CodeError} 时就走这条回退，把 {@code code=5001 / msg="internal server error"}
 * 写进 envelope ⇒ ⇒ <b>回退值本身属 L1</b>，{@link #getMsg(int)} 必须回退，
 * <b>不能</b>返回 {@code null} 或空串。
 *
 * <p><b>⚠️ 文案是英文，与 users / article 域的中文文案不同语言</b>（那是<b>不同域</b>的独立码表，
 * 不是同一套码的翻译）⇒ 逐域隔离，禁止跨域统一（见 {@link ErrCodes} 的类注释）。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：B 侧是「工具类 + 静态初始化块 + 静态方法」，
 * Lombok 对静态初始化块与静态方法无可消除的样板；{@code @UtilityClass} 在本仓被明令禁止 ⇒ 不引入。
 */
public final class ErrMsg {

    /**
     * 对应 B 侧 {@code codeMsg} 整张表（11 条）；对外只读。
     *
     * <p><b>可见性：包级私有</b>（B 侧 :54 同款）⇒ 同包的契约类可以遍历它做
     * 「码表与码常量表严格一一对应」的断言，但对外业务模块只通过 {@link #getMsg(int)} 访问。
     */
    static final Map<Integer, String> MSG_BY_CODE;

    static {
        Map<Integer, String> map = new LinkedHashMap<>();
        // === 通用（B 侧 :59-61）===
        map.put(ErrCodes.SUCCESS.getCode(), "OK");
        map.put(ErrCodes.ERROR.getCode(), "FAIL");
        map.put(ErrCodes.CODE_SERVER_BUSY.getCode(), "service busy");
        // === 系统与基础设施（B 侧 :63-70）===
        map.put(ErrCodes.ERROR_SERVER_COMMON.getCode(), "internal server error");
        map.put(ErrCodes.ERROR_NOT_FOUND.getCode(), "record not found");
        map.put(ErrCodes.ERROR_INVALID_PARAM.getCode(), "invalid parameter");
        map.put(ErrCodes.ERROR_UNAUTHORIZED.getCode(), "unauthorized");
        map.put(ErrCodes.ERROR_FORBIDDEN.getCode(), "forbidden");
        map.put(ErrCodes.ERROR_ALREADY_EXIST.getCode(), "already exists");
        map.put(ErrCodes.ERROR_DB_SELECT.getCode(), "database query failed");
        map.put(ErrCodes.ERROR_DB_UPDATE.getCode(), "database update failed");
        MSG_BY_CODE = Collections.unmodifiableMap(map);
    }

    private ErrMsg() {
        // 工具类，禁止 new（B 侧 :43-45）
    }

    /**
     * 取业务文案 —— 与 B 侧 {@code getMsg(int)} 逐字一致（B 侧 :80-86）。
     *
     * <p>未知码回退 {@link ErrCodes#ERROR} 的 {@code "FAIL"}。
     *
     * @param code 业务码
     * @return 对应文案；未知码返回 {@code "FAIL"}（<b>不是</b> {@code null}、<b>不是</b> 空串）
     */
    public static String getMsg(int code) {
        String msg = MSG_BY_CODE.get(code);
        if (msg != null) {
            return msg;
        }
        return MSG_BY_CODE.get(ErrCodes.ERROR.getCode());
    }

    /**
     * 整张表的只读视图 —— 供同包的 {@link GrpcErrorMapper} 构建「msg → code」反查表。
     *
     * <p>B 出处：B 侧 {@code HotErrorMessages.MSG_BY_CODE}（:54）是<b>包级私有</b>字段，
     * 而 B 侧 {@code MessageGrpcError} 与它<b>同包</b> ⇒ 直接读；C 侧两者同在
     * {@code com.rag.hot.contract} 包 ⇒ 用<b>包级可见</b>的只读视图暴露，语义等价。
     *
     * @return code → msg 的只读映射（保持 B 侧的书写顺序）
     */
    static Map<Integer, String> allMsgs() {
        return MSG_BY_CODE;
    }
}
