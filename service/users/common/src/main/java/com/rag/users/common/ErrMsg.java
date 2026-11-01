package com.rag.users.common;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>users 域 code → msg 文案映射</b> —— 逐条对应 B 侧 {@code ErrorCode} 枚举里每个常量的 {@code msg}。
 *
 * <h2>B 出处（本次迁移的行为基线）</h2>
 * <pre>
 *   B: RAG  service/users/users-common/src/main/java/com/rag/users/common/errmsg/ErrorCode.java:8-38
 *      —— 31 个枚举常量的 {@code (code, msg)}；
 *         第 88-90 行 {@code of(int)}：找不到 ⇒ 回落 {@code ERROR}；
 *         第 93-95 行 {@code getMsg(int)}：{@code of(code).msg}。
 * </pre>
 *
 * <h2>语义等价论证（对 B 逐条）</h2>
 * <ol>
 *   <li><b>31 条映射逐条一致</b>：码值与文案都逐字照抄 B 的枚举常量
 *       ⇒ {@code getMsg(x)} 与 B 的 {@code ErrorCode.getMsg(x)} 返回<b>同一个字符串</b>
 *       （含 {@code "服务繁忙"} 这类<b>中文</b>文案 —— 与 follow/hot/message 域的英文文案不同，
 *       错误契约<b>逐域隔离</b>，禁止上提到根级 {@code common/}）。</li>
 *   <li><b>回落语义一致</b>：B 的 {@code of(int)} 用 {@code getOrDefault(code, ERROR)}
 *       ⇒ 未知码拿到 {@code ERROR} 的文案 {@code "FAIL"}；本类用
 *       {@code CODE_MSG.getOrDefault(code, CODE_MSG.get(ErrCodes.ERROR))} ⇒ <b>同一个回落</b>
 *       （不是 {@code null}、不是空串、不是异常）。</li>
 *   <li><b>「码值有、文案缺」这一形态不存在</b>：B 的 enum 里码与文案同生共死
 *       ⇒ C 侧本表的 key 集合与 B 的枚举集合<b>同为 31 条</b>
 *       ⇒ 不会出现「某码值存在但查不到文案」的分叉。</li>
 *   <li><b>本类不持有码值常量</b>：码值一律引用 {@link ErrCodes} ⇒ <b>码与文案只有一处真源</b>。</li>
 * </ol>
 *
 * <p>⚠️ <b>文案是 L1 契约</b>（客户端可见）⇒ 本表的任何一条都不得「顺手润色」、
 * 不得改成英文、不得与其他域统一。改文案 = 改可见行为 = 走决策流程。
 */
public final class ErrMsg {

    /**
     * code → msg 映射（不可变）。
     *
     * <p>B 出处：{@code ErrorCode.java:8-38} 的 31 个枚举常量（码与文案同源）。
     */
    private static final Map<Integer, String> CODE_MSG;

    static {
        Map<Integer, String> map = new HashMap<>();
        map.put(ErrCodes.SUCCESS, "OK");
        map.put(ErrCodes.ERROR, "FAIL");
        map.put(ErrCodes.CODE_SERVER_BUSY, "服务繁忙");
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
        map.put(ErrCodes.ERROR_REDIS_UPDATE, "Redis更新失败");
        map.put(ErrCodes.ERROR_ADMIN_INVITE_CODE_WRONG, "管理员邀请码错误");
        map.put(ErrCodes.ERROR_REQUEST_PARAM, "请求参数错误");
        map.put(ErrCodes.ERROR_AVATAR_HISTORY_NOT_EXIST, "头像历史不存在");
        map.put(ErrCodes.ERROR_POINTS_INSUFFICIENT, "积分余量不足");
        map.put(ErrCodes.ERROR_POINTS_RETRY_EXCEEDED, "重试次数超限");
        map.put(ErrCodes.ERROR_POINTS_TIMEOUT, "积分处理超时");
        CODE_MSG = Collections.unmodifiableMap(map);
    }

    private ErrMsg() {
    }

    /**
     * 取码值对应的提示文案。
     *
     * <p><b>回落语义</b>：码值不存在 ⇒ 返回 {@link ErrCodes#ERROR} 的文案（{@code "FAIL"}）。
     * 这与 B 侧 {@code ErrorCode.getMsg(int)}（{@code of(code).msg}，{@code of} 回落 {@code ERROR}）<b>逐条一致</b>
     * ⇒ 任何<b>未知码</b>在两侧都得到同一个字符串，不会出现「一侧 null、一侧 FAIL」的分叉。
     *
     * @param code 业务码
     * @return 对应文案；未知码返回 {@code "FAIL"}
     */
    public static String getMsg(int code) {
        return CODE_MSG.getOrDefault(code, CODE_MSG.get(ErrCodes.ERROR));
    }

    /**
     * 返回 code → msg 映射的只读视图（自检 / 契约对拍用）。
     *
     * @return 不可变映射，key 集合 = {@link ErrCodes#codes()}
     */
    public static Map<Integer, String> all() {
        return CODE_MSG;
    }
}
