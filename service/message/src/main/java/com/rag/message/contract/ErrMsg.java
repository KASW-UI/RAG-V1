package com.rag.message.contract;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>message 域 code → msg 文案映射</b> —— <b>20 条文案的唯一真源</b>（L1 契约 · <b>含语言</b>）。
 *
 * <p><b>⚠️ 本域文案是<b>英文</b></b>（{@code "service busy"} / {@code "internal server error"} / …）
 * ⇒ 与其它域的中文文案<b>不可统一</b>（AGENTS.md §八「未统一化」· §三「L1 以 B 侧为准」· D16）。
 *
 * <p><b>B 出处</b>：{@code service/message/message-common/src/main/java/com/rag/message/message/common/errmsg/MessageErrorMessages.java}
 * <pre>{@code
 *   类声明 + 私有构造器（工具类，禁止 new）   :31-35
 *   code → msg 表字段（unmodifiable 包装）     :48
 *   static 块：20 条文案逐条写入 + 包装       :50-76（逐条 :53-:74）
 *   getMsg(int)：命中返回文案，未知码回落 ERROR 的文案 "FAIL"   :86-92
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯表达层</b>改动 ——
 *       ① 类名 {@code MessageErrorMessages} → {@code ErrMsg}（本仓域内契约包的既定类名；
 *       形态依据见 {@code docs/深挖修正记录.md} §三 M-3 落地表）；
 *       ② 包名 {@code com.rag.message.message.common.errmsg} → {@code com.rag.message.contract}（D7 包压平）；
 *       ③ B 侧表字段是 <b>package-private</b>（:48），C 侧改为 <b>private</b> ＋ 对外只读视图
 *       {@link #allMsgs()}（原 B 侧唯一的跨类消费者是同包的 {@code MessageGrpcError} 反查表构建；
 *       C 侧同包消费者 {@link GrpcErrorMapper} 改走 {@link #allMsgs()} 公共入口 ⇒ 表本身不再外泄）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 20 条码值与 20 条文案逐字照抄（:53-:74），
 *       查表回落语义（未知码 ⇒ {@link ErrCodes#ERROR} 的文案 {@code "FAIL"}，不是 null、不是空串、不抛异常）
 *       与 B 侧 {@code getMsg}（:86-92）逐条相同 ⇒ 任何未知码在两侧得到同一个字符串。</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>码值来源</b>：本表的 key 一律取 {@link ErrCodes} 的<b>公开</b>枚举常量
 * （{@code ErrCodes.SUCCESS.getCode()} 等）⇒ 与 B 侧写法一致（B 侧同样以
 * {@code MessageErrorCode.SUCCESS.getCode()} 作 key，见 :53-:74）⇒ <b>码值只有一处真源</b>，
 * 不在本类重复抄一份数字（避免两份码表漂移）。
 *
 * <p><b>⚠️ 文案是 L1 契约</b>（客户端可见）⇒ 本表任何一条都不得「顺手润色」、不得改成中文、
 * 不得与其他域统一。改文案 = 改可见行为 = 走决策流程。
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：B 侧是手写工具类（私有构造 + 静态表 + 静态方法），
 * 本类为逐字照抄；{@code @UtilityClass} 在本仓被明令禁止（会改变构造器可见性等可观察形状）⇒ 不引入。
 */
public final class ErrMsg {

    /**
     * code → msg 表（不可变）—— B 侧同名表（:48），C 侧收窄为 {@code private} ＋ {@link #allMsgs()} 只读出口。
     *
     * <p>20 条文案逐条来自 B 侧 static 块（:53-:74）。
     */
    private static final Map<Integer, String> MSG_BY_CODE;

    static {
        Map<Integer, String> map = new HashMap<>();
        // === 通用 ===
        map.put(ErrCodes.SUCCESS.getCode(), "OK");
        map.put(ErrCodes.ERROR.getCode(), "FAIL");
        map.put(ErrCodes.CODE_SERVER_BUSY.getCode(), "service busy");
        map.put(ErrCodes.ERROR_SERVER_COMMON.getCode(), "internal server error");
        map.put(ErrCodes.ERROR_NOT_FOUND.getCode(), "record not found");
        map.put(ErrCodes.ERROR_INVALID_PARAM.getCode(), "invalid parameter");
        map.put(ErrCodes.ERROR_UNAUTHORIZED.getCode(), "unauthorized");
        map.put(ErrCodes.ERROR_FORBIDDEN.getCode(), "forbidden");
        map.put(ErrCodes.ERROR_ALREADY_EXIST.getCode(), "already exists");
        map.put(ErrCodes.ERROR_DB_SELECT.getCode(), "database query failed");
        map.put(ErrCodes.ERROR_DB_UPDATE.getCode(), "database update failed");
        // === 用户 / 管理员 ===
        map.put(ErrCodes.ERROR_USER_NOT_FOUND.getCode(), "user not found");
        map.put(ErrCodes.ERROR_ADMIN_NOT_FOUND.getCode(), "admin not found");
        // === 消息 / 会话 / 通知 ===
        map.put(ErrCodes.ERROR_SELF_CHAT.getCode(), "self chat is not allowed");
        map.put(ErrCodes.ERROR_CONVERSATION_MISS.getCode(), "conversation not found");
        map.put(ErrCodes.ERROR_NOTIFICATION_MISS.getCode(), "notification not found");
        map.put(ErrCodes.ERROR_CHAT_PENDING.getCode(), "waiting for the other side to reply");
        map.put(ErrCodes.ERROR_ADMIN_ONLY_INIT.getCode(), "only admin can initiate this conversation");
        map.put(ErrCodes.ERROR_FIRST_MSG_TOO_LONG.getCode(), "first private message cannot exceed 50 characters");
        map.put(ErrCodes.ERROR_UNSUPPORTED_ROLE.getCode(), "unsupported sender role");
        MSG_BY_CODE = Collections.unmodifiableMap(map);
    }

    private ErrMsg() {
        // 工具类，禁止 new（B 侧 :33-35）
    }

    /**
     * 取业务码对应的提示文案 —— 与 B 侧 {@code getMsg(int)} 逐条一致（:86-92）。
     *
     * <p><b>回落语义</b>：码值不存在 ⇒ 返回 {@link ErrCodes#ERROR}（500）的文案 {@code "FAIL"}；
     * 与 B 侧 {@code MSG_BY_CODE.get(...)} + {@code get(ERROR)} 的两段写法逐条相同
     * ⇒ 未知码在两侧都拿到<b>同一个字符串</b>，不会出现「一侧 null、一侧 FAIL」的分叉。
     *
     * <p>消费点（B 侧同类调用）：HTTP 信封的 {@code msg} 字段、gRPC status 的 description
     * （B 侧 :83-85 注释所述的两处）。
     *
     * @param code 业务码
     * @return 对应文案；未知码返回 {@code "FAIL"}（<b>不是 null</b>、<b>不抛异常</b>）
     */
    public static String getMsg(int code) {
        String msg = MSG_BY_CODE.get(code);
        if (msg != null) {
            return msg;
        }
        return MSG_BY_CODE.get(ErrCodes.ERROR.getCode());
    }

    /**
     * 返回 code → msg 映射的<b>只读视图</b>（全部 20 条，含 {@code SUCCESS}/{@code ERROR}）。
     *
     * <p><b>用途</b>：{@link GrpcErrorMapper} 需要遍历「全部 code → msg」来构建
     * <b>msg → code 反查表</b>（排除 {@link ErrCodes#SUCCESS} / {@link ErrCodes#ERROR} 再 first-write-wins 去重，
     * 与 B 侧 :185-198 逐条一致）⇒ 反查逻辑留在 {@link GrpcErrorMapper}，文案表留在本类（单一真源）。
     *
     * <p><b>返回的 Map 是不可变的</b>（{@link Collections#unmodifiableMap} 包装，B 侧 :75 同款）
     * ⇒ 调用方无法在运行期改动文案表。
     *
     * @return 不可变映射，key 集合 = {@link ErrCodes} 的 20 个码值
     */
    public static Map<Integer, String> allMsgs() {
        return MSG_BY_CODE;
    }
}
