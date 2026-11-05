package com.rag.favorite.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * favorite 域错误码<b>码值</b>契约测试 —— 真源
 * {@code service/favorite/favorite-common/src/main/java/com/rag/favorite/favorite/common/errmsg/FavoriteErrorCode.java:37-119}
 * 的枚举（24 个常量）。
 *
 * <p><b>本测试断言的是「B 侧源码的行为」，不是「Java 现在的行为」</b>：每条期望值都直接抄自
 * B 侧枚举声明行，不允许「照着当前实现写断言」——那只会把一个错误答案锁成绿色
 * （本仓已吃过这个亏：AGENTS.md 记录的 DRIFT-MSGAPI-001/002/003
 * 三条漂移都曾被一条「断言了 RAG 错误行为」的绿测试锁死）。
 *
 * <p>覆盖四条真实风险：
 * <ol>
 *   <li><b>码值漂移</b>（L1 跨服务契约，改了客户端就解错码）；</li>
 *   <li><b>漏迁 / 擅自新增</b>；</li>
 *   <li><b>码值重复</b>（会让任何 code → 枚举 的反查静默丢码）；</li>
 *   <li><b>分组归属漂移</b>（{@code CODE_SERVER_BUSY = 1015} 在 B 侧源码里写在
 *       「通用错误码 5001-5099」注释下、却在 5001 之前，容易被"顺手归位"）。</li>
 * </ol>
 *
 * <h2>§3.2 三段式留痕</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧的被测对象是 {@code enum FavoriteErrorCode}，
 *       枚举天然带 {@code values()} / {@code valueOf(String)} / {@code name()} / {@code getCode()}；
 *       C 侧按本仓惯例改成 {@code final class ErrCodes + int 常量}
 *       （{@code service/favorite/src/main/java/com/rag/favorite/contract/ErrCodes.java:46-148}）
 *       ⇒ <b>没有</b> {@code values()}。本类因此用<b>反射读 {@code ErrCodes} 的 public static final int 字段</b>
 *       来还原「枚举项集合 + 声明顺序 + 取值」这三件事，
 *       并额外加了一条 B 侧没有的<b>变异实测</b>（见 {@link #mutationDetectorIsNotTautological()}）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 两边暴露的都是「一组有名字的 int 码值」，
 *       可观察面（码值集合 / 码值大小 / 含义 / 书写顺序）逐条一致；
 *       唯一差异是 C 侧拿不到 {@code Enum.name()} 之外的枚举专属 API，
 *       而本类用「字段名 ↔ 码值」把这层差异补平了。</li>
 *   <li><b>B 出处</b>：B 侧测试
 *       {@code service/favorite/favorite-common/src/test/java/com/rag/favorite/favorite/common/errmsg/FavoriteErrorCodeContractTest.java:32-158}
 *       ＋ B 侧被测源 {@code .../favorite/common/errmsg/FavoriteErrorCode.java:37-119}
 *       （逐条常量行号见下方 {@link #GO_CONST_VALUES} 的行内注释）。</li>
 * </ol>
 */
class FavoriteErrorCodeContractTest {

    /**
     * B 侧 {@code FavoriteErrorCode} 枚举的 const 块逐条抄录（<b>顺序与 B 侧书写顺序一致</b>）。
     *
     * <p>键 = B 侧枚举项名，C 侧 {@link ErrCodes} 的字段名与之<b>逐字同名</b>
     * （这是 C 侧 {@code ErrCodes} 逐字照抄 B 侧枚举名的结果）⇒ 可用反射一一对回。
     *
     * <p>B 出处行号：B 侧 {@code FavoriteErrorCode.java:41,43,47,51,53,55,57,59,61,63,65,67,
     * 71,73,75,77,79,83,85,87,91,93,95,108}。
     */
    private static final Map<String, Integer> GO_CONST_VALUES = new LinkedHashMap<>();

    static {
        // 通用（B 侧 FavoriteErrorCode.java:41,43）
        GO_CONST_VALUES.put("SUCCESS", 200);
        GO_CONST_VALUES.put("ERROR", 500);
        // 通用错误码（B 侧 FavoriteErrorCode.java:47,51,53,55,57,59,61,63,65,67）
        GO_CONST_VALUES.put("CODE_SERVER_BUSY", 1015);
        GO_CONST_VALUES.put("ERROR_SERVER_COMMON", 5001);
        GO_CONST_VALUES.put("ERROR_NOT_FOUND", 5002);
        GO_CONST_VALUES.put("ERROR_INVALID_PARAM", 5003);
        GO_CONST_VALUES.put("ERROR_UNAUTHORIZED", 5004);
        GO_CONST_VALUES.put("ERROR_FORBIDDEN", 5005);
        GO_CONST_VALUES.put("ERROR_ALREADY_EXIST", 5006);
        GO_CONST_VALUES.put("ERROR_DB_SELECT", 5007);
        GO_CONST_VALUES.put("ERROR_DB_UPDATE", 5008);
        GO_CONST_VALUES.put("ERROR_GENERATE_ID", 5009);
        // 用户模块 5100-5199（B 侧 FavoriteErrorCode.java:71,73,75,77,79）
        GO_CONST_VALUES.put("ERROR_USER_NOT_FOUND", 5101);
        GO_CONST_VALUES.put("ERROR_USER_EXISTS", 5102);
        GO_CONST_VALUES.put("ERROR_PASSWORD_INVALID", 5103);
        GO_CONST_VALUES.put("ERROR_TOKEN_INVALID", 5104);
        GO_CONST_VALUES.put("ERROR_TOKEN_EXPIRED", 5105);
        // 收藏夹模块 5200-5299（B 侧 FavoriteErrorCode.java:83,85,87）
        GO_CONST_VALUES.put("ERROR_FAVORITE_FOLDER_NOT_FOUND", 5201);
        GO_CONST_VALUES.put("ERROR_FAVORITE_FOLDER_NAME_EMPTY", 5202);
        GO_CONST_VALUES.put("ERROR_FAVORITE_FOLDER_NAME_EXISTS", 5203);
        // 收藏模块 5300-5399（B 侧 FavoriteErrorCode.java:91,93,95,108）
        GO_CONST_VALUES.put("ERROR_FAVORITE_NOT_FOUND", 5301);
        GO_CONST_VALUES.put("ERROR_FAVORITE_ALREADY_EXIST", 5302);
        GO_CONST_VALUES.put("ERROR_FAVORITE_TARGET_EMPTY", 5303);
        GO_CONST_VALUES.put("ERROR_FAVORITE_HISTORY_BLOCKED", 5304);
    }

    // ══════════════════════════ 工具：反射读 C 侧的 int 常量 ══════════════════════════

    /**
     * 读出 {@link ErrCodes} 里所有 {@code public static final int} 字段（<b>保持声明顺序</b>）。
     *
     * <p>⚠️ <b>为什么用反射</b>：C 侧把 B 侧的枚举改成常量类 ⇒ 拿不到 {@code values()}；
     * 而本测试必须断言「常量集合」与「声明顺序」两件事（都是 B 侧的契约），
     * 逐个硬编码常量名只能覆盖第一件。
     *
     * <p>⚠️ <b>声明顺序的来源</b>：{@link Class#getDeclaredFields()} 在 HotSpot 上按声明顺序返回
     * （JDK 规范未强制，但本仓所有 JVM 运行时均是 HotSpot）⇒ 本条是「顺序契约」的守门。
     * 若某天真换到不保序的 JVM，这条会红 —— 那正是「顺序漂移了要有人看一眼」的信号。
     */
    private static Map<String, Integer> actualCodeFields() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Field field : ErrCodes.class.getDeclaredFields()) {
            if (field.getType() != int.class
                    || !Modifier.isPublic(field.getModifiers())
                    || !Modifier.isStatic(field.getModifiers())
                    || !Modifier.isFinal(field.getModifiers())
                    || field.isSynthetic()) {
                continue;
            }
            try {
                out.put(field.getName(), field.getInt(null));
            } catch (IllegalAccessException e) {
                throw new AssertionError("读不到 ErrCodes." + field.getName() + " ⇒ 字段不是 public static final int", e);
            }
        }
        return out;
    }

    /**
     * 比对两张「字段名 → 码值」表，返回<b>漂移清单</b>（空 = 一致）。
     *
     * <p>抽成方法是为了让 {@link #mutationDetectorIsNotTautological()} 能把判据<b>喂坏数据</b>，
     * 从而证明这条判据<b>不是恒真</b>（AGENTS.md §3.3.3：判据必须能红）。
     */
    private static List<String> findDrift(Map<String, Integer> expected, Map<String, Integer> actual) {
        List<String> drift = new ArrayList<>();
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            Integer actualValue = actual.get(e.getKey());
            if (actualValue == null) {
                drift.add("缺少常量 " + e.getKey());
            } else if (!actualValue.equals(e.getValue())) {
                drift.add("码值漂移：" + e.getKey() + " 期望 " + e.getValue() + "，实际 " + actualValue);
            }
        }
        for (String name : actual.keySet()) {
            if (!expected.containsKey(name)) {
                drift.add("擅自新增常量 " + name);
            }
        }
        return drift;
    }

    // ══════════════════════════ 用例 ══════════════════════════

    @Test
    @DisplayName("B 侧 const 块恰好 24 个常量——多一个少一个都算契约变更")
    void thereAreExactlyTwentyFourCodes() {
        // B 侧 FavoriteErrorCode.java:41-108 逐行计数共 24 行常量；漏迁或擅自新增都会让计数对不上。
        assertEquals(24, GO_CONST_VALUES.size(), "本表（B 侧抄录）应当正好 24 条");
        assertEquals(24, actualCodeFields().size(),
                "C 侧 ErrCodes 的 int 常量应当正好 24 个（多一个少一个都是契约变更）");
    }

    @Test
    @DisplayName("每个常量的码值逐条等于 B 侧 FavoriteErrorCode 的值（L1 跨服务契约）")
    void everyCodeValueMatchesBSideOneByOne() {
        assertEquals(List.of(), findDrift(GO_CONST_VALUES, actualCodeFields()),
                "码值与 B 侧 FavoriteErrorCode 不一致");
    }

    @Test
    @DisplayName("常量声明顺序与 B 侧枚举书写顺序一致，便于逐行 review")
    void declarationOrderMatchesBSideSourceOrder() {
        // B 侧 FavoriteErrorCode 的书写顺序：通用（200/500）→ 通用错误码（1015 起，**1015 在 5001 之前**）
        // → 用户（5101-5105）→ 收藏夹（5201-5203）→ 收藏（5301-5304）。
        // 特别注意 1015：它在 B 侧源码里位于「通用错误码」注释块的第一行，数值却是 1015 而非 500x。
        List<String> expectedOrder = List.copyOf(GO_CONST_VALUES.keySet());

        assertEquals(expectedOrder, List.copyOf(actualCodeFields().keySet()),
                "常量顺序与 B 侧不一致；顺序漂移会让逐行对照失效");
    }

    @Test
    @DisplayName("码值无重复——重复会让 code→文案 反查静默丢码")
    void codeValuesAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (Map.Entry<String, Integer> entry : actualCodeFields().entrySet()) {
            assertTrue(seen.add(entry.getValue()),
                    "码值重复：" + entry.getKey() + " 的码值 " + entry.getValue() + " 已被前面的常量占用");
        }
        assertEquals(24, seen.size());
    }

    @Test
    @DisplayName("码常量产出裸 int，可直接喂给 grpcError(grpcCode, bizCode) 与 envelope 的 code 字段")
    void codeYieldsPrimitiveIntForExistingConsumers() {
        // 依据（均为 B 侧既有链路 / 本仓既有基础设施，本模块零反射引用它们的类型，只对齐签名）：
        //   B 侧 rpc: GRPCError(grpcCode codes.Code, bizCode int)   —— bizCode 形参是 int
        //   B 侧 api : 返回 (*types.XResp, int)，int 即业务码
        //   com.rag.common.exception.BusinessException(int code, String msg)
        // 两处都要求裸 int；若本常量集拿不到 int，这两条既有链路就接不上。
        for (Map.Entry<String, Integer> entry : actualCodeFields().entrySet()) {
            int code = entry.getValue();
            assertTrue(code > 0, entry.getKey() + " 的码值必须是正数");
        }
        // 抽样断言：这些常量必须能直接当 int 传给 grpcError / getMsg（签名对齐的证据）。
        assertEquals(5304, ErrCodes.ERROR_FAVORITE_HISTORY_BLOCKED);
        assertEquals("收藏暂不可撤回", ErrMsg.getMsg(ErrCodes.ERROR_FAVORITE_HISTORY_BLOCKED));
        assertEquals(200, ErrCodes.SUCCESS);
        assertEquals(500, ErrCodes.ERROR);
    }

    @Test
    @DisplayName("跨域同码不同义：favorite 的 1015 不得被 users / article 域的编号段覆盖")
    void favoriteKeepsItsOwnNumberingAgainstOtherDomains() {
        // favorite 域的 1015 = 「服务繁忙」；users / article / like 域在 1001-1015 段另有含义
        // （例如 like 的 1011 = 用户封禁、users 的 1008 = 无权限）。
        // RAG 多个域在同一仓库内演进，按别的域的直觉「顺手补齐 1001-1015 段」即为漂移。
        assertEquals(1015, ErrCodes.CODE_SERVER_BUSY);
        assertEquals("服务繁忙", ErrMsg.getMsg(1015),
                "favorite 的 1015 必须是「服务繁忙」，不能被别的域的 1015 含义覆盖");
    }

    /**
     * 🔴 <b>变异实测（B 侧没有 · C 侧新增）</b> —— 证明「码值比对判据不是恒真」。
     *
     * <p>做法：把 C 侧实际读出的码值表<b>故意改坏</b>（改一个值 + 删一个键 + 加一个键），
     * 再喂给<b>同一个</b>比对函数 ⇒ 必须报出漂移。
     * ⇒ 若这条红了，说明 {@link #findDrift(Map, Map)} 写成了「永远返回空」的假判据
     * ⇒ 那前面所有绿测试都是假绿（AGENTS.md §3.3.3）。
     */
    @Test
    @DisplayName("🔴 变异实测：把码值表改坏 ⇒ 比对判据必须报出漂移（证明判据不是恒真）")
    void mutationDetectorIsNotTautological() {
        Map<String, Integer> mutated = new LinkedHashMap<>(actualCodeFields());
        // ① 改坏一个值
        mutated.put("ERROR_NOT_FOUND", 5999);
        // ② 删一个键
        mutated.remove("ERROR_DB_SELECT");
        // ③ 加一个键
        mutated.put("ERROR_BRAND_NEW", 8888);

        List<String> drift = findDrift(GO_CONST_VALUES, mutated);

        assertTrue(drift.stream().anyMatch(d -> d.contains("ERROR_NOT_FOUND") && d.contains("5999")),
                "改坏的值必须被报出 ⇒ 实际 drift=" + drift);
        assertTrue(drift.stream().anyMatch(d -> d.contains("缺少常量 ERROR_DB_SELECT")),
                "删掉的键必须被报出 ⇒ 实际 drift=" + drift);
        assertTrue(drift.stream().anyMatch(d -> d.contains("擅自新增常量 ERROR_BRAND_NEW")),
                "多出来的键必须被报出 ⇒ 实际 drift=" + drift);
        assertEquals(3, drift.size(), "恰好 3 条漂移（改值 / 缺键 / 多键）⇒ 实际 drift=" + drift);
    }

    @Test
    @DisplayName("🔴 反向守门：未改坏时判据必须报「无漂移」（防止判据写成永远红）")
    void driftDetectorIsNotAlwaysRed() {
        assertEquals(List.of(), findDrift(GO_CONST_VALUES, actualCodeFields()));
        assertFalse(actualCodeFields().isEmpty(), "C 侧 ErrCodes 读不到任何 int 常量 ⇒ 反射读法失效");
        assertNotNull(ErrCodes.class, "ErrCodes 必须存在");
    }
}
