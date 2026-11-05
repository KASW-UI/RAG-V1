package com.rag.favorite.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p><b>favorite 域 code → msg 文案映射</b> —— <b>24 条文案的唯一真源</b>（L1 契约 · <b>含语言</b>）。
 *
 * <p><b>⚠️ 本域文案是<b>中文为主，但 5002 是英文</b></b>：{@code ERROR_NOT_FOUND = 5002} 的文案是
 * {@code "not found"}，不是「记录不存在」。
 * 这是 hot 域整表英文、users/article 域整表中文<b>之外的第三种形态</b>
 * ⇒ <b>别按直觉补中文</b>（AGENTS.md §八「未统一化」· §三「L1 以 B 侧为准」· D16）。
 *
 * <p><b>B 出处</b>：{@code service/favorite/favorite-common/src/main/java/com/rag/favorite/favorite/common/errmsg/FavoriteErrorMessages.java:35-110}
 * <pre>{@code
 *   类声明 + 私有构造器（工具类，禁止 new）            :35-39
 *   code → msg 表字段（LinkedHashMap，写入顺序 = Go 书写顺序）  :42
 *   static 块：24 条文案逐条写入 + unmodifiable 包装      :44-76
 *   getMsg(int)：命中返回文案，未知码回落 ERROR 的文案 "FAIL"  :104-110
 * }</pre>
 *
 * <h3>⚠️ 未知码必须回落 {@code "FAIL"}，不能返回 {@code null} / 空串</h3>
 * <p>B 侧 {@code GetErrMsg} 的形状是：
 * <pre>
 * func GetErrMsg(code int) string {
 *     msg, ok := codeMsg[code]
 *     if !ok { return codeMsg[Error] }   // ← 未知码回退到 "FAIL"
 *     return msg
 * }
 * </pre>
 * ⇒ 「未知码返回 {@code "FAIL"}」是<b>客户端可观察的</b>：favorite-api 任何拿到未知码的分支
 * 最终都把 {@code "FAIL"} 呈现给用户。
 * ⇒ ⇒ {@link #getMsg(int)} 必须回落，<b>不能</b>返回 {@code null}、空串或码的字符串。
 *
 * <h3>⚠️ 写入顺序 = B 侧 {@code codeMsg} 的书写顺序</h3>
 * <p>用 {@link LinkedHashMap}（而非 {@code HashMap}）保留插入序 ⇒ review 时可与 B 侧源码逐行对照。
 * ⚠️ <b>本表的 {@code getMsg} 只做「按码取文案」，不做任何改写</b> ——
 * 业务层的错误文案<b>一律用调用点原文</b>。
 *
 * <h3>⚠️ 本类<b>不使用 Lombok</b></h3>
 * <p>B 侧是手写工具类（私有构造 + 静态表 + 静态方法）；本类为逐字照抄。
 * {@code @UtilityClass} 在本仓被明令禁止（会改变构造器可见性等可观察形状）⇒ 不引入。
 */
public final class ErrMsg {

    /**
     * code → msg 表（不可变）—— B 侧同名表（B 侧 :42）。
     *
     * <p>24 条文案逐条来自 B 侧 static 块（B 侧 :44-76）。
     */
    private static final Map<Integer, String> MSG_BY_CODE;

    static {
        Map<Integer, String> map = new LinkedHashMap<>();
        // === 通用（B 侧 FavoriteErrorMessages.java:47-48）===
        map.put(ErrCodes.SUCCESS, "OK");
        map.put(ErrCodes.ERROR, "FAIL");
        // === 通用错误码（B 侧 FavoriteErrorMessages.java:49-58）===
        map.put(ErrCodes.CODE_SERVER_BUSY, "服务繁忙");
        map.put(ErrCodes.ERROR_SERVER_COMMON, "系统内部错误");
        // 🔴 本域唯一英文文案（B 侧 FavoriteErrorMessages.java:52）—— 不是「记录不存在」
        map.put(ErrCodes.ERROR_NOT_FOUND, "not found");
        map.put(ErrCodes.ERROR_INVALID_PARAM, "参数错误");
        map.put(ErrCodes.ERROR_UNAUTHORIZED, "未登录或登录已失效");
        map.put(ErrCodes.ERROR_FORBIDDEN, "无权限操作");
        map.put(ErrCodes.ERROR_ALREADY_EXIST, "记录已存在");
        map.put(ErrCodes.ERROR_DB_SELECT, "数据库查询失败");
        map.put(ErrCodes.ERROR_DB_UPDATE, "数据库更新失败");
        map.put(ErrCodes.ERROR_GENERATE_ID, "ID生成失败");
        // === 用户（B 侧 FavoriteErrorMessages.java:60-64）===
        map.put(ErrCodes.ERROR_USER_NOT_FOUND, "用户不存在");
        map.put(ErrCodes.ERROR_USER_EXISTS, "用户已存在");
        map.put(ErrCodes.ERROR_PASSWORD_INVALID, "密码错误");
        map.put(ErrCodes.ERROR_TOKEN_INVALID, "token无效");
        map.put(ErrCodes.ERROR_TOKEN_EXPIRED, "token已过期");
        // === 收藏夹（B 侧 FavoriteErrorMessages.java:67-69）===
        map.put(ErrCodes.ERROR_FAVORITE_FOLDER_NOT_FOUND, "收藏夹不存在");
        map.put(ErrCodes.ERROR_FAVORITE_FOLDER_NAME_EMPTY, "收藏夹名称不能为空");
        map.put(ErrCodes.ERROR_FAVORITE_FOLDER_NAME_EXISTS, "收藏夹名称已存在");
        // === 收藏（B 侧 FavoriteErrorMessages.java:72-75）===
        map.put(ErrCodes.ERROR_FAVORITE_NOT_FOUND, "收藏记录不存在");
        map.put(ErrCodes.ERROR_FAVORITE_ALREADY_EXIST, "该内容已收藏");
        map.put(ErrCodes.ERROR_FAVORITE_TARGET_EMPTY, "收藏目标不能为空");
        map.put(ErrCodes.ERROR_FAVORITE_HISTORY_BLOCKED, "收藏暂不可撤回");
        MSG_BY_CODE = Collections.unmodifiableMap(map);
    }

    private ErrMsg() {
        // 工具类，禁止 new
    }

    /**
     * 对应 B 侧 {@code func GetErrMsg(code int) string}（B 侧 :104-110）：未知码回退到 {@code "FAIL"}。
     *
     * <p><b>不能用 {@code getOrDefault(code, null)}</b> —— 那会让上游 handler 把
     * {@code null} 写进 HTTP envelope 的 {@code msg}，客户端拿到 {@code {"code":…, "msg":null}}，
     * 与 B 侧的 {@code {"code":…, "msg":"FAIL"}} 不同。<b>这是 L1 外部契约。</b>
     *
     * @param code 业务码
     * @return 命中返回文案；未知码返回 {@code "FAIL"}
     */
    public static String getMsg(int code) {
        String msg = MSG_BY_CODE.get(code);
        if (msg == null) {
            // B 侧: return codeMsg[Error] —— 500 号的文案，即 "FAIL"
            return MSG_BY_CODE.get(ErrCodes.ERROR);
        }
        return msg;
    }

    /**
     * 暴露整张文案表（只读）—— B 侧同名表对外可查。
     *
     * <p>⚠️ 返回的是<b>不可变</b>视图（{@link Collections#unmodifiableMap} 包装）⇒ 外部改不动。
     *
     * @return code → msg 的只读表（24 条）
     */
    public static Map<Integer, String> msgByCode() {
        return MSG_BY_CODE;
    }
}
