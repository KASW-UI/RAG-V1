package com.rag.comment.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p><b>comment 域 code → msg 文案映射</b> —— <b>27 条文案的唯一真源</b>（L1 契约 · <b>含语言</b>）。
 *
 * <p>⚠️ <b>本域文案是<b>中文</b></b>（{@code "系统内部错误"} / {@code "查询数据库失败"} / …）
 * ⇒ 与 follow / hot / message 域的英文文案<b>不可统一</b>
 * （AGENTS.md §八「未统一化」· §三「L1 以 B 侧为准」· D16）。
 *
 * <p><b>B 出处</b>：{@code service/comment/comment-common/src/main/java/com/rag/comment/comment/common/errmsg/CommentErrorMessages.java:30-97}
 * <pre>{@code
 *   类声明 + 私有构造器（工具类，禁止 new）            :30-35
 *   code → msg 表字段（LinkedHashMap，写入顺序 = Go 书写顺序）  :41
 *   static 块：27 条文案逐条写入 + unmodifiable 包装      :44-84
 *   getMsg(int)：命中返回文案，未知码回落 ERROR 的文案 "FAIL"  :89-95
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
 * ⇒ 「未知码返回 {@code "FAIL"}」是<b>客户端可观察的</b>：comment-api 正常路径的 envelope 走
 * OkHandler（{@code Msg: errmsg.GetErrMsg(errmsg.Success)}），错误路径走 ErrorHandler；
 * 后者在错误<b>不是</b> {@code *CodeError} 时会用
 * {@code errmsg.GetErrMsg(errmsg.ErrorServerCommon)} 兜底。
 * ⇒ ⇒ {@link #getMsg(int)} 必须回落，<b>不能</b>返回 {@code null} 或空串。
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
     * code → msg 表（不可变）—— B 侧同名表（B 侧 :41）。
     *
     * <p>27 条文案逐条来自 B 侧 static 块（B 侧 :44-84）。
     */
    private static final Map<Integer, String> MSG_BY_CODE;

    static {
        Map<Integer, String> map = new LinkedHashMap<>();
        // === 通用（B 侧 CommentErrorMessages.java:45-47）===
        map.put(ErrCodes.SUCCESS, "OK");
        map.put(ErrCodes.ERROR, "FAIL");
        map.put(ErrCodes.CODE_SERVER_BUSY, "服务繁忙");
        // === 系统与基础设施（B 侧 CommentErrorMessages.java:48-57）===
        map.put(ErrCodes.ERROR_SERVER_COMMON, "系统内部错误");
        map.put(ErrCodes.ERROR_DB_UPDATE, "更新数据库失败");
        map.put(ErrCodes.ERROR_DB_SELECT, "查询数据库失败");
        map.put(ErrCodes.ERROR_DB_INSERT, "数据库插入失败");
        map.put(ErrCodes.ERROR_DB_TRANSACTION, "事务操作失败");
        map.put(ErrCodes.ERROR_SNOWFLAKE_ID, "雪花ID生成失败");
        map.put(ErrCodes.ERROR_JSON_MARSHAL, "JSON序列化失败");
        map.put(ErrCodes.ERROR_JSON_UNMARSHAL, "JSON反序列化失败");
        map.put(ErrCodes.ERROR_KAFKA_PUSH, "Kafka消息发送失败");
        map.put(ErrCodes.ERROR_DELAY_MSG, "延时消息发送失败");
        // === 用户 / 令牌（B 侧 CommentErrorMessages.java:58-68）===
        map.put(ErrCodes.ERROR_USER_EXIST, "用户名已存在");
        map.put(ErrCodes.ERROR_LOGIN_WRONG, "用户名或密码错误");
        map.put(ErrCodes.ERROR_USER_NOT_EXIST, "用户不存在");
        map.put(ErrCodes.ERROR_TOKEN_NOT_EXIST, "TOKEN不存在");
        map.put(ErrCodes.ERROR_TOKEN_TYPE_WRONG, "TOKEN格式错误");
        map.put(ErrCodes.ERROR_TOKEN_RUNTIME, "TOKEN已过期");
        map.put(ErrCodes.ERROR_TOKEN_REFRESH, "TOKEN刷新失败");
        map.put(ErrCodes.ERROR_USER_NO_RIGHT, "权限不足");
        map.put(ErrCodes.ERROR_USER_NO_LOGIN, "未登录");
        map.put(ErrCodes.ERROR_USER_LOGINED, "已登录");
        map.put(ErrCodes.ERROR_USER_BANNED, "用户已被封禁");
        // === comment 专属（B 侧 CommentErrorMessages.java:69-70）===
        map.put(ErrCodes.ERROR_SUBJECT_NOT_EXIST, "评论区不存在");
        map.put(ErrCodes.ERROR_COMMENT_NOT_EXIST, "评论不存在");
        // === 输入校验（B 侧 CommentErrorMessages.java:71）===
        map.put(ErrCodes.ERROR_INPUT_WRONG, "输入有误");
        MSG_BY_CODE = Collections.unmodifiableMap(map);
    }

    /**
     * 对应 B 侧 {@code func GetErrMsg(code int) string}（B 侧 :89-95）。
     *
     * <p>未知码回退 {@link ErrCodes#ERROR} 的 {@code "FAIL"}，
     * 与 B 侧 {@code if !ok { return codeMsg[Error] }} 同向。
     *
     * @param code 业务码
     * @return 对应文案；未知码返回 {@code "FAIL"}
     */
    public static String getMsg(int code) {
        String msg = MSG_BY_CODE.get(code);
        if (msg != null) {
            return msg;
        }
        return MSG_BY_CODE.get(ErrCodes.ERROR);
    }

    private ErrMsg() {
        // 工具类，禁止 new
    }
}
