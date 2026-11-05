package com.rag.favorite.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * favorite 域错误码<b>文案</b>契约测试 —— 真源
 * {@code service/favorite/favorite-common/src/main/java/com/rag/favorite/favorite/common/errmsg/FavoriteErrorMessages.java:42-92}
 * （{@code MSG_BY_CODE} 表 ＋ {@code getMsg(int)} 的回退逻辑）。
 *
 * <p><b>本测试断言的是「B 侧源码的行为」，不是「Java 现在的行为」</b>：期望值直接抄自 B 侧源码行。
 *
 * <p>文案与码值一样是 L1 跨服务契约，且在本域<b>权重更高</b>：业务码在 wire 上是
 * 「藏在 description 文案里」的（见 {@link GrpcErrorMapper}），文案不只是给人看的提示，
 * 它<b>同时是业务码的传输载体</b>。文案改一个字，业务码就还原不出来了。
 *
 * <h2>§3.2 三段式留痕</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧 {@code FavoriteErrorMessages.MSG_BY_CODE} 是
 *       <b>public 字段</b>（B 侧 {@code FavoriteErrorMessages.java:42}）；
 *       C 侧 {@code ErrMsg.msgByCode()} 是<b>只读访问器</b>
 *       （{@code service/favorite/src/main/java/com/rag/favorite/contract/ErrMsg.java:119-121}，
 *       内部是 {@code Collections.unmodifiableMap} 包装）
 *       ⇒ 本类把 B 侧的 {@code MSG_BY_CODE.xxx} 全部改写成 {@code ErrMsg.msgByCode().xxx}。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 两边都是「插入序固定（LinkedHashMap）+ 外部改不动」的
 *       24 条文案表；C 侧只是把「public 字段」换成「public 方法返回同一张不可变视图」，
 *       可观察面（键集合 / 键顺序 / 值 / 不可变性）逐条一致。</li>
 *   <li><b>B 出处</b>：B 侧测试
 *       {@code service/favorite/favorite-common/src/test/java/com/rag/favorite/favorite/common/errmsg/FavoriteErrorMessagesContractTest.java:28-210}
 *       ＋ B 侧被测源 {@code .../favorite/common/errmsg/FavoriteErrorMessages.java:42-92}
 *       （逐条文案行号见下方 {@link #GO_CODE_MSG} 的行内注释）。</li>
 * </ol>
 */
class FavoriteErrorMessagesContractTest {

    /**
     * B 侧 {@code MSG_BY_CODE} 表逐条抄录（含大小写、空格、英文/中文形态，逐字节）。
     *
     * <p>B 出处行号：B 侧 {@code FavoriteErrorMessages.java:47,48,50,51,52,53,54,55,56,57,58,59,
     * 61,62,63,64,65,67,68,69,71,72,73,74}。
     */
    private static final Map<Integer, String> GO_CODE_MSG = new LinkedHashMap<>();

    static {
        // 通用（B 侧 FavoriteErrorMessages.java:47,48）
        GO_CODE_MSG.put(200, "OK");
        GO_CODE_MSG.put(500, "FAIL");
        // 通用错误码（B 侧 FavoriteErrorMessages.java:50-59）
        GO_CODE_MSG.put(1015, "服务繁忙");
        GO_CODE_MSG.put(5001, "系统内部错误");
        GO_CODE_MSG.put(5002, "not found");
        GO_CODE_MSG.put(5003, "参数错误");
        GO_CODE_MSG.put(5004, "未登录或登录已失效");
        GO_CODE_MSG.put(5005, "无权限操作");
        GO_CODE_MSG.put(5006, "记录已存在");
        GO_CODE_MSG.put(5007, "数据库查询失败");
        GO_CODE_MSG.put(5008, "数据库更新失败");
        GO_CODE_MSG.put(5009, "ID生成失败");
        // 用户（B 侧 FavoriteErrorMessages.java:61-65）
        GO_CODE_MSG.put(5101, "用户不存在");
        GO_CODE_MSG.put(5102, "用户已存在");
        GO_CODE_MSG.put(5103, "密码错误");
        GO_CODE_MSG.put(5104, "token无效");
        GO_CODE_MSG.put(5105, "token已过期");
        // 收藏夹（B 侧 FavoriteErrorMessages.java:67-69）
        GO_CODE_MSG.put(5201, "收藏夹不存在");
        GO_CODE_MSG.put(5202, "收藏夹名称不能为空");
        GO_CODE_MSG.put(5203, "收藏夹名称已存在");
        // 收藏（B 侧 FavoriteErrorMessages.java:71-74）
        GO_CODE_MSG.put(5301, "收藏记录不存在");
        GO_CODE_MSG.put(5302, "该内容已收藏");
        GO_CODE_MSG.put(5303, "收藏目标不能为空");
        GO_CODE_MSG.put(5304, "收藏暂不可撤回");
    }

    @Test
    @DisplayName("24 条文案逐条等于 B 侧 FavoriteErrorMessages 的值（含大小写与中英文形态）")
    void everyMessageMatchesBSideOneByOne() {
        assertEquals(24, GO_CODE_MSG.size());
        for (Map.Entry<Integer, String> expected : GO_CODE_MSG.entrySet()) {
            assertEquals(expected.getValue(), ErrMsg.getMsg(expected.getKey()),
                    "文案漂移：code=" + expected.getKey() + " 的文案与 B 侧不一致");
        }
    }

    @Test
    @DisplayName("文案表与码常量表严格一一对应（没有只声明码却漏文案的，也没有只登记文案却没有码的）")
    void tableAndConstantsAreInBijection() {
        Set<Integer> constantCodes = new HashSet<>();
        for (int code : new int[]{
                ErrCodes.SUCCESS, ErrCodes.ERROR, ErrCodes.CODE_SERVER_BUSY, ErrCodes.ERROR_SERVER_COMMON,
                ErrCodes.ERROR_NOT_FOUND, ErrCodes.ERROR_INVALID_PARAM, ErrCodes.ERROR_UNAUTHORIZED,
                ErrCodes.ERROR_FORBIDDEN, ErrCodes.ERROR_ALREADY_EXIST, ErrCodes.ERROR_DB_SELECT,
                ErrCodes.ERROR_DB_UPDATE, ErrCodes.ERROR_GENERATE_ID, ErrCodes.ERROR_USER_NOT_FOUND,
                ErrCodes.ERROR_USER_EXISTS, ErrCodes.ERROR_PASSWORD_INVALID, ErrCodes.ERROR_TOKEN_INVALID,
                ErrCodes.ERROR_TOKEN_EXPIRED, ErrCodes.ERROR_FAVORITE_FOLDER_NOT_FOUND,
                ErrCodes.ERROR_FAVORITE_FOLDER_NAME_EMPTY, ErrCodes.ERROR_FAVORITE_FOLDER_NAME_EXISTS,
                ErrCodes.ERROR_FAVORITE_NOT_FOUND, ErrCodes.ERROR_FAVORITE_ALREADY_EXIST,
                ErrCodes.ERROR_FAVORITE_TARGET_EMPTY, ErrCodes.ERROR_FAVORITE_HISTORY_BLOCKED}) {
            constantCodes.add(code);
        }
        assertEquals(constantCodes, ErrMsg.msgByCode().keySet(),
                "码常量表与文案表的键集合不一致");
    }

    @Test
    @DisplayName("文案表内顺序与 B 侧 codeMsg 的书写顺序一致（LinkedHashMap 保序，便于逐行对照）")
    void tableOrderMatchesBSideSourceOrder() {
        List<Integer> expectedOrder = List.of(
                200, 500,
                1015, 5001, 5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009,
                5101, 5102, 5103, 5104, 5105,
                5201, 5202, 5203,
                5301, 5302, 5303, 5304);

        assertEquals(expectedOrder, new ArrayList<>(ErrMsg.msgByCode().keySet()),
                "文案表的键顺序与 B 侧 codeMsg 书写顺序不一致");
    }

    @Test
    @DisplayName("未知码回退为 \"FAIL\"，不是 null、不是空串、不是 code 的字符串（L2 本组件唯一分支）")
    void unknownCodeFallsBackToFail() {
        // B 侧 FavoriteErrorMessages.java:86-92：
        //   msg, ok := codeMsg[code]; if !ok { return codeMsg[Error] }
        // codeMsg[Error] 即 500 号的文案 "FAIL"。
        // 触发路径真实存在：B 侧 api 的 handler 直接把查表结果写进 envelope。
        List<Integer> unknownCodes = List.of(
                0,                          // 零值
                -1,                         // 负数
                1,                          // 介于两个真实码之间
                999,                        // 1000~1014 空档
                1016,                       // 1015 与 5001 之间
                5000,                       // 5001 之前
                5010,                       // 5009 之后、5101 之前
                5100,                       // 用户段起点
                5106,                       // 用户段末尾之后
                5200,                       // 收藏夹段起点
                5204,                       // 收藏夹段末尾之后
                5300,                       // 收藏段起点
                5305,                       // 收藏段末尾之后
                2001,                       // ⚠️ 真实存在于 like 域，favorite 域必须回落
                3001,                       // ⚠️ 真实存在于 like 域，favorite 域必须回落
                Integer.MAX_VALUE,
                Integer.MIN_VALUE);

        for (int code : unknownCodes) {
            assertEquals("FAIL", ErrMsg.getMsg(code),
                    "未知码 " + code + " 必须回落为 \"FAIL\"（B 侧: codeMsg[Error]）");
        }
    }

    @Test
    @DisplayName("已知码取回自身文案：200→OK、500→FAIL（500 是命中而非回落）")
    void knownCodesReturnTheirOwnMessage() {
        assertEquals("OK", ErrMsg.getMsg(200));
        assertEquals("FAIL", ErrMsg.getMsg(500));
        assertEquals("服务繁忙", ErrMsg.getMsg(1015));
        assertEquals("系统内部错误", ErrMsg.getMsg(5001));
    }

    @Test
    @DisplayName("文案两两互不相同——这是 B 侧 bizCodeByMsg 能确定反查的前提")
    void messagesArePairwiseDistinct() {
        // B 侧 FavoriteGrpcError.java:103-125 的 bizCodeByMsg 用「先到先得」处理重复文案，
        // 而 B 侧 map 遍历顺序随机 ⇒ 一旦有两条同文案，反查结果就是真随机。
        // 当前 24 条文案互不相同（B 侧靠这一点让该分支实际不可达），
        // 这里把该前提钉死，防止日后加文案时无声破坏它。
        Set<String> seen = new HashSet<>();
        for (Map.Entry<Integer, String> entry : ErrMsg.msgByCode().entrySet()) {
            assertTrue(seen.add(entry.getValue()),
                    "文案重复：" + entry.getValue() + " 已被 code=" + entry.getKey() + " 占用");
        }
        assertEquals(24, seen.size());
    }

    @Test
    @DisplayName("跨域同码不同义：favorite 的 5002/5003/5004 不得被 hot / users / article 域的含义覆盖")
    void favoriteCodesKeepTheirOwnMeaningAgainstOtherDomains() {
        // 这三条数值在别的域里指向完全不同的故障类型。RAG 多个域在同一仓库内演进，
        // 一旦有人按 hot 域的直觉（5002=record not found / 5003=invalid parameter / 5004=unauthorized）
        // 去「修正」favorite，就会引入客户端可见的文案漂移。此处把差异显式钉死。
        assertNotEquals("record not found", ErrMsg.getMsg(5002),
                "favorite 的 5002 必须是 B 侧源码里的「not found」，不能被 hot 域的「record not found」覆盖");
        assertNotEquals("invalid parameter", ErrMsg.getMsg(5003),
                "favorite 的 5003 必须是「参数错误」，不能被 hot 域的「invalid parameter」覆盖");
        assertNotEquals("unauthorized", ErrMsg.getMsg(5004),
                "favorite 的 5004 必须是「未登录或登录已失效」，不能被 hot 域的「unauthorized」覆盖");
        assertNotEquals("更新数据库失败", ErrMsg.getMsg(5002),
                "favorite 的 5002 不能是 users/article 域的「更新数据库失败」");

        // 同时确认 favorite 域确实登记了这三个码（防止上一步因为「查不到」而假通过）。
        assertEquals("not found", ErrMsg.getMsg(5002));
        assertEquals("参数错误", ErrMsg.getMsg(5003));
        assertEquals("未登录或登录已失效", ErrMsg.getMsg(5004));
    }

    @Test
    @DisplayName("favorite 域文案是中文为主但 5002 是英文——不得被统一「补中文」")
    void messagesAreChineseExceptEnglishNotFound() {
        // 唯一含非 ASCII 之外形态的条目：B 侧 FavoriteErrorMessages.java:52 的 ERROR_NOT_FOUND 是英文 "not found"。
        // hot 域是整表英文、users/article 域整表中文；favorite 是混合形态，
        // 按「本域应该是中文」的直觉补成「记录不存在」即为文案漂移。
        assertEquals("not found", ErrMsg.getMsg(5002));
        assertTrue(ErrMsg.getMsg(5003).chars().anyMatch(c -> c > 127),
                "5003 应是中文「参数错误」");
        assertTrue(ErrMsg.getMsg(5304).chars().anyMatch(c -> c > 127),
                "5304 应是中文「收藏暂不可撤回」");

        // 除 5002 / 200 / 500 外，其余 21 条都必须含中文，防止有人把整表换成英文。
        // B 侧源码里 favorite 域只有 200「OK」/ 500「FAIL」/ 5002「not found」三条是 ASCII。
        List<Integer> asciiOnlyCodes = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : ErrMsg.msgByCode().entrySet()) {
            if (entry.getValue().chars().allMatch(c -> c < 128)) {
                asciiOnlyCodes.add(entry.getKey());
                assertTrue(List.of(200, 500, 5002).contains(entry.getKey()),
                        "code=" + entry.getKey() + " 的文案是非 ASCII，但 B 侧源码里它是 ASCII："
                                + entry.getValue());
            }
        }
        assertEquals(List.of(200, 500, 5002), asciiOnlyCodes,
                "B 侧源码里 favorite 域只有 200/500/5002 三条文案是 ASCII；"
                        + "多出来的 ASCII 文案说明有人把中文文案换成了英文");
    }

    @Test
    @DisplayName("文案表不可被外部篡改")
    void msgTableIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> ErrMsg.msgByCode().put(12345, "篡改"));
    }

    /**
     * 🔴 <b>变异实测（B 侧没有 · C 侧新增）</b> —— 证明「文案反查 / 回落判据不是恒真」。
     *
     * <p>做法：造一个<b>不在</b>文案表里的文案，把它塞进 gRPC description
     * ⇒ 反查必须<b>落空</b>并走标准码兜底（而不是"碰巧"反查成功）；
     * 再把一条<b>在</b>表里的文案塞进去 ⇒ 必须<b>命中</b>原码。
     * ⇒ 两条合起来证明：反查判据<b>既能红也能绿</b>，不是恒真（AGENTS.md §3.3.3）。
     */
    @Test
    @DisplayName("🔴 变异实测：不在表里的文案必须落空反查、在表里的文案必须命中（判据不是恒真）")
    void reverseLookupJudgeIsNotTautological() {
        // ① 表内的文案 ⇒ 命中（5304 走 FAILED_PRECONDITION，不在标准码白名单里 ⇒ 只能靠文案反查）
        assertEquals(5304, GrpcErrorMapper.bizCodeFromError(
                GrpcErrorMapper.grpcError(io.grpc.Status.Code.FAILED_PRECONDITION,
                        ErrCodes.ERROR_FAVORITE_HISTORY_BLOCKED)),
                "表内文案必须能反查出原业务码");

        // ② 表外的文案 ⇒ 反查落空 ⇒ 走标准码白名单（INTERNAL → 5001）
        assertEquals(5001, GrpcErrorMapper.bizCodeFromError(
                io.grpc.Status.INTERNAL.withDescription("一个不存在的文案").asRuntimeException()),
                "表外文案必须反查落空并走标准码兜底（INTERNAL → 5001）");
    }
}
