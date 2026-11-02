package com.rag.follow.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p><b>follow 域 code → msg 查表（L1 契约层 · 域内私有）</b>。
 *
 * <p><b>B 出处</b>：{@code service/follow/follow-common/src/main/java/com/rag/follow/follow/common/errmsg/FollowErrorMessages.java:52-54}
 * （整张表 16 条）＋ 同文件 {@code getMsg(int)} 的回落逻辑。
 *
 * <p><b>⚠️ B 侧的回落行为是 L1 外部契约，必须保留</b>：
 * <pre>
 * func GetErrMsg(code int) string {
 *     msg, ok := codeMsg[code]
 *     if !ok {
 *         return codeMsg[Error]      // ← 未知码回退到 500 号的文案 "FAIL"
 *     }
 *     return msg
 * }
 * </pre>
 * 「未知码返回 {@code "FAIL"}」是客户端可观察的：follow-api 的错误处理把
 * {@code GetErrMsg(...)} 的结果写进 HTTP envelope 的 {@code msg} 字段，
 * follow-rpc 的成功响应也用它填 {@code BaseResp.msg}。
 * ⇒ {@link #getMsg(int)} <b>必须</b>回退，<b>不能</b>返回 {@code null}、空串或码的字符串。
 *
 * <p><b>⚠️ 本域整表英文（逐域隔离 · AGENTS.md §八）</b>：16 条文案<b>无一例外</b>是英文小写短语。
 * 与 users 域的整表中文、与 favorite 域的「同码不同义」形态都不同
 * ⇒ <b>别按邻近域的直觉补中文</b>，也<b>不要</b>把这张表并进 {@code service/users/common/}。
 *
 * <p><b>⚠️ 键类型与写入顺序</b>：B 侧用 {@code map[int]string}；
 * C 侧用 {@code Map<Integer, String>} ＋ {@link LinkedHashMap}（保持 B 侧的书写顺序）
 * ⇒ 便于 review 时逐行对照 ⇒ 且 {@link GrpcErrorMapper} 的反查表
 * （first-write-wins）与 B 侧逐条同源。
 *
 * <p><b>⚠️ 表达层差异（L4）</b>：B 侧是「静态初始化块 + {@code Collections.unmodifiableMap}」，
 * C 侧沿用同一形态 ⇒ <b>零回改</b>（纯静态工具类，无框架依赖）。
 *
 * @see com.rag.follow.contract.ErrCodes 码值来源（16 条）
 */
public final class ErrMsg {

    /** 对应 B 侧 {@code codeMsg} 整张表（16 条）；对外只读。 */
    public static final Map<Integer, String> MSG_BY_CODE;

    static {
        Map<Integer, String> map = new LinkedHashMap<>();
        // === 通用（B 侧 FollowErrorMessages.java:56-57）===
        map.put(ErrCodes.SUCCESS, "OK");
        map.put(ErrCodes.ERROR, "FAIL");
        // === 通用错误码（B 侧 FollowErrorMessages.java:59-67）===
        map.put(ErrCodes.CODE_SERVER_BUSY, "service busy");
        map.put(ErrCodes.ERROR_SERVER_COMMON, "internal server error");
        map.put(ErrCodes.ERROR_NOT_FOUND, "record not found");
        map.put(ErrCodes.ERROR_INVALID_PARAM, "invalid parameter");
        map.put(ErrCodes.ERROR_UNAUTHORIZED, "unauthorized");
        map.put(ErrCodes.ERROR_FORBIDDEN, "forbidden");
        map.put(ErrCodes.ERROR_ALREADY_EXIST, "already exists");
        map.put(ErrCodes.ERROR_DB_SELECT, "database query failed");
        map.put(ErrCodes.ERROR_DB_UPDATE, "database update failed");
        // === 用户（B 侧 FollowErrorMessages.java:69）===
        map.put(ErrCodes.ERROR_USER_NOT_FOUND, "user not found");
        // === 关注（B 侧 FollowErrorMessages.java:70-73）===
        map.put(ErrCodes.ERROR_SELF_RELATION, "self relation is not allowed");
        map.put(ErrCodes.ERROR_RELATION_BLOCKED, "blocked relation exists");
        map.put(ErrCodes.ERROR_FOLLOW_NOT_FOUND, "follow relation not found");
        map.put(ErrCodes.ERROR_BLOCK_NOT_FOUND, "block relation not found");
        MSG_BY_CODE = Collections.unmodifiableMap(map);
    }

    private ErrMsg() {
        // 工具类，禁止 new
    }

    /**
     * 对应 B 侧 {@code func GetErrMsg(code int) string}：未知码回退到 {@code "FAIL"}。
     *
     * <p><b>⚠️ 不能用 {@code getOrDefault(code, null)}</b> —— 那会让上游把 {@code null}
     * 写进 HTTP envelope 的 {@code msg}，客户端拿到 {@code {"code":…,"msg":null}}，
     * 与 B 侧的 {@code {"code":…,"msg":"FAIL"}} 不同 ⇒ <b>这是 L1 外部契约</b>。
     *
     * @param code 业务码
     * @return 该码的文案；未知码返回 500 号的文案 {@code "FAIL"}
     */
    public static String getMsg(int code) {
        String msg = MSG_BY_CODE.get(code);
        if (msg == null) {
            // B: return codeMsg[Error] —— 500 号的文案，即 "FAIL"
            return MSG_BY_CODE.get(ErrCodes.ERROR);
        }
        return msg;
    }
}
