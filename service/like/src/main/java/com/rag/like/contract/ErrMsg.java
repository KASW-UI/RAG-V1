package com.rag.like.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p><b>like 域 code → msg 文案映射</b> —— <b>29 条文案的唯一真源</b>（L1 契约 · <b>含语言</b>）。
 *
 * <p><b>⚠️ 本域文案是<b>中文</b></b>（{@code "系统内部错误"} / {@code "查询数据库失败"} / …）
 * ⇒ 与 follow / message 域的英文文案<b>不可统一</b>
 * （AGENTS.md §八「未统一化」· §三「L1 以 B 侧为准」· D16）。
 *
 * <p><b>B 出处</b>：{@code service/like/like-common/src/main/java/com/rag/like/like/common/errmsg/LikeErrorMessages.java:37-107}
 * <pre>{@code
 *   类声明 + 私有构造器（工具类，禁止 new）            :28-35
 *   code → msg 表字段（LinkedHashMap，写入顺序 = Go 书写顺序）  :43
 *   static 块：29 条文案逐条写入 + unmodifiable 包装      :45-90
 *   getMsg(int)：命中返回文案，未知码回落 ERROR 的文案 "FAIL"  :101-107
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
 * ⇒ 「未知码返回 {@code "FAIL"}」是<b>客户端可观察的</b>：like-api 任何拿到未知码的分支
 * 最终都把 {@code "FAIL"} 呈现给用户。
 * ⇒ ⇒ {@link #getMsg(int)} 必须回落，<b>不能</b>返回 {@code null} 或空串。
 *
 * <h3>⚠️ 写入顺序 = B 侧 {@code codeMsg} 的书写顺序</h3>
 * <p>用 {@link LinkedHashMap}（而非 {@code HashMap}）保留插入序 ⇒ review 时可与 B 侧源码逐行对照。
 * ⚠️ <b>本表的 {@code getMsg} 只做「按码取文案」，不做任何改写</b> ——
 * 业务层的错误文案<b>一律用调用点原文</b>（见 {@code exception/LikeExceptions} 的说明：
 * B 侧 code=5003 查表得「查询数据库失败」，而 logic 里传的是「DB查询失败」，
 * 这个差值对调用方可见，必须原样搬运）。
 *
 * <h3>⚠️ 本类<b>不使用 Lombok</b></h3>
 * <p>B 侧是手写工具类（私有构造 + 静态表 + 静态方法）；本类为逐字照抄。
 * {@code @UtilityClass} 在本仓被明令禁止（会改变构造器可见性等可观察形状）⇒ 不引入。
 */
public final class ErrMsg {

    /**
     * code → msg 表（不可变）—— B 侧同名表（B 侧 :43）。
     *
     * <p>29 条文案逐条来自 B 侧 static 块（B 侧 :45-90）。
     */
    private static final Map<Integer, String> MSG_BY_CODE;

    static {
        Map<Integer, String> map = new LinkedHashMap<>();
        // === 通用（B 侧 LikeErrorMessages.java:46-48）===
        map.put(ErrCodes.SUCCESS, "OK");
        map.put(ErrCodes.ERROR, "FAIL");
        map.put(ErrCodes.CODE_SERVER_BUSY, "服务繁忙");
        // === 系统与基础设施（B 侧 LikeErrorMessages.java:49-60）===
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
        map.put(ErrCodes.ERROR_REDIS_SELECT, "Redis查询失败");
        map.put(ErrCodes.ERROR_REDIS_UPDATE, "Redis更新失败");
        // === 用户 / 鉴权（B 侧 LikeErrorMessages.java:61-71）===
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
        // === 入参 / 类型（B 侧 LikeErrorMessages.java:72-73）===
        map.put(ErrCodes.ERROR_INPUT_WRONG, "输入有误");
        map.put(ErrCodes.ERROR_TYPE_TRANSFER, "类型转换错误");
        // === Outbox（B 侧 LikeErrorMessages.java:74）===
        map.put(ErrCodes.ERROR_BUILD_OUTBOX, "构建 Outbox 消息载荷失败");
        MSG_BY_CODE = Collections.unmodifiableMap(map);
    }

    private ErrMsg() {
        // 工具类，禁止 new
    }

    /**
     * 对应 B 侧 {@code func GetErrMsg(code int) string}（B 侧 :101-107）。
     *
     * <p>未知码回退 {@link ErrCodes#ERROR} 的 {@code "FAIL"}，
     * 与 B 侧 {@code if !ok { return codeMsg[Error] }} 同向。
     *
     * @param code 业务码
     * @return 该码的文案；未知码返回 {@code "FAIL"}（<b>不是 null、不是空串</b>）
     */
    public static String getMsg(int code) {
        String msg = MSG_BY_CODE.get(code);
        if (msg != null) {
            return msg;
        }
        return MSG_BY_CODE.get(ErrCodes.ERROR);
    }

    /**
     * code → msg 表的<b>只读视图</b> —— 供本域 {@link GrpcErrFactory} 构建反查表。
     *
     * <p>⚠️ <b>本表<b>不</b>提供「文案 → 码」的反查</b>（B 侧 like 域的
     * {@code LikeGrpcError} 用的是<b>自定义 trailer</b> 承载业务码，见 {@link GrpcErrFactory}）
     * ⇒ ⇒ 消费方拿到的永远是「码 → 文案」这一个方向。
     *
     * @return 不可变视图（按 B 侧书写顺序迭代）
     */
    public static Map<Integer, String> allMsgs() {
        return MSG_BY_CODE;
    }
}
