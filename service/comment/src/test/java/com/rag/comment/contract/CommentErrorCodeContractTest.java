package com.rag.comment.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>comment 域错误码「数值 + 顺序」的 1:1 守门测试</b>（纯单元，<b>不起 Spring 上下文</b>）。
 *
 * <h2>🔴 为什么这些值得锁死</h2>
 * <p>错误码数值同时出现在 HTTP envelope 与 gRPC 上，是<b>跨服务对外契约</b>
 * ⇒ 改任何一条都会让已发布的客户端解析失败。
 * 而这些值「看起来像是随便写的常量」，编译器与 IDE 都不会提醒有人改错了。
 *
 * <h2>① 比 B 侧多做了什么</h2>
 * <ul>
 *   <li><b>C 侧 {@link ErrCodes} 是「final class + int 常量」，不是枚举</b>（表达层形态差异，
 *       见 {@link ErrCodes} 的类注释）⇒ B 侧的 {@code CommentErrorCode.values()} 遍历
 *       <b>在 C 侧不存在</b> ⇒ 本类改用<b>反射遍历 {@code ErrCodes} 的声明字段</b>
 *       （{@code Class#getDeclaredFields()}）来还原「声明顺序」这一契约维度。</li>
 *   <li><b>C 侧 {@link ErrMsg} 的 {@code MSG_BY_CODE} 是 {@code private}</b>（B 侧是 {@code public}）
 *       ⇒ 「码表与文案表一一对应」这条断言只能<b>反射读取</b>该私有表。</li>
 *   <li><b>额外钉住「常量名与码值同时一致」</b>：B 侧只比了码值序列，C 侧多比了一层
 *       「B 侧枚举常量名 ↔ C 侧常量名」的一一对应（两侧名字本就逐字相同 ⇒ 零成本）。</li>
 * </ul>
 *
 * <h2>② 语义还一样吗</h2>
 * <p><b>一样</b>。本类断言的全是<b>数值与顺序</b>这两个 L1 契约维度，
 * 与「枚举 vs 常量类」的表达层形态无关：枚举的 {@code values()} 顺序与常量类的声明顺序
 * 在 B 侧被当作同一份契约（B 侧 javadoc 明写「枚举顺序即契约」）
 * ⇒ C 侧用反射还原同一个顺序维度，<b>守门点等价</b>。
 * <p>⚠️ <b>反射读字段顺序的 JVM 依赖</b>：{@code Class#getDeclaredFields()} 返回顺序
 * 在 HotSpot 上是<b>类文件里的声明顺序</b>（javac 按源码书写顺序写 class 文件）
 * ⇒ 本断言在本仓的 JVM（Temurin 21）上成立；它守的是「C 侧没有按数值大小重排」，
 * 而不是「JVM 规范保证的顺序」。
 *
 * <h2>③ B 出处</h2>
 * <pre>
 * B 侧源文件：service/comment/comment-common/src/test/java/com/rag/comment/comment/common/errmsg/CommentErrorCodeContractTest.java
 *   thereAreTwentySevenCodes            :77-83    → thereAreTwentySevenCodes
 *   everyCodeMatchesGoOneByOne          :85-90    → everyCodeMatchesGoOneByOne
 *   commentSpecificCodesAreNotMerged    :92-102   → commentSpecificCodesAreNotMerged
 *   frequentlyUsedRpcCodesArePinned     :104-118  → frequentlyUsedRpcCodesArePinned
 *   everyCodeHasAMessage                :120-125  → everyCodeHasAMessage
 * B 侧 oracle（码值真源）：service/comment/comment-common/src/main/java/com/rag/comment/comment/common/errmsg/CommentErrorCode.java:33-104
 * B 侧 oracle（文案表真源）：service/comment/comment-common/src/main/java/com/rag/comment/comment/common/errmsg/CommentErrorMessages.java:44-84
 * </pre>
 * <p>⚠️ B 侧原注释里的码值出处写的是更早的 Go 真源
 * {@code Sea-RideTheWind_golang/service/comment/common/errmsg/errmsg.go:37-65}
 * ⇒ B 侧的 Java {@code CommentErrorCode} 是同一张表的逐字搬运，本类以
 * <b>B 侧 Java 枚举</b>为唯一 oracle（AGENTS.md §3.1「B 侧是唯一 oracle」）。
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>跑的是 <b>C 侧单侧</b>：断言「C 侧的码值/顺序与 B 侧源码<b>逐条一致</b>」。
 * 「B 侧此刻真的产出同样的码」属于<b>双侧活体对拍</b>（本类不冒充该证据）。
 */
class CommentErrorCodeContractTest {

    /**
     * B 侧 {@code CommentErrorCode} 的<b>声明顺序 + 码值 + 常量名</b>，27 条逐字照抄。
     *
     * <p>⚠️ <b>这张表就是被测对象</b>：断言「C 侧 {@link ErrCodes} 与它逐条相同」
     * ⇒ 任何一条码被漏掉、被换值、或常量被改名 ⇒ 断言红。
     *
     * <p>key = 码值，value = <b>常量名</b>（B 侧枚举常量名与 C 侧常量名<b>逐字相同</b>）。
     */
    private static final Map<Integer, String> B_SIDE_CODES = bSideCodes();

    private static Map<Integer, String> bSideCodes() {
        Map<Integer, String> map = new LinkedHashMap<>();
        // === 通用（B 侧 CommentErrorCode.java:33-37）===
        map.put(200, "SUCCESS");
        map.put(500, "ERROR");
        map.put(1015, "CODE_SERVER_BUSY");
        // === 系统与基础设施（B 侧 CommentErrorCode.java:42-61）===
        map.put(5001, "ERROR_SERVER_COMMON");
        map.put(5002, "ERROR_DB_UPDATE");
        map.put(5003, "ERROR_DB_SELECT");
        map.put(5004, "ERROR_DB_INSERT");
        map.put(5005, "ERROR_DB_TRANSACTION");
        map.put(5006, "ERROR_SNOWFLAKE_ID");
        map.put(5007, "ERROR_JSON_MARSHAL");
        map.put(5008, "ERROR_JSON_UNMARSHAL");
        map.put(5009, "ERROR_KAFKA_PUSH");
        map.put(5010, "ERROR_DELAY_MSG");
        // === 用户 / 令牌（B 侧 CommentErrorCode.java:65-88）===
        map.put(1001, "ERROR_USER_EXIST");
        map.put(1002, "ERROR_LOGIN_WRONG");
        map.put(1003, "ERROR_USER_NOT_EXIST");
        map.put(1004, "ERROR_TOKEN_NOT_EXIST");
        map.put(1005, "ERROR_TOKEN_TYPE_WRONG");
        map.put(1006, "ERROR_TOKEN_RUNTIME");
        map.put(1007, "ERROR_TOKEN_REFRESH");
        map.put(1008, "ERROR_USER_NO_RIGHT");
        map.put(1009, "ERROR_USER_NO_LOGIN");
        map.put(1010, "ERROR_USER_LOGINED");
        map.put(1011, "ERROR_USER_BANNED");
        // === comment 专属（B 侧 CommentErrorCode.java:93-97）===
        map.put(1012, "ERROR_SUBJECT_NOT_EXIST");
        map.put(1013, "ERROR_COMMENT_NOT_EXIST");
        // === 输入校验（B 侧 CommentErrorCode.java:104）===
        map.put(2001, "ERROR_INPUT_WRONG");
        return map;
    }

    /**
     * 按 C 侧 {@link ErrCodes} 的<b>声明顺序</b>取出 (码值 → 常量名)。
     *
     * <p>⚠️ C 侧 {@link ErrCodes} 不是枚举 ⇒ 没有 {@code values()}；
     * 本方法用反射遍历 {@code static int} 字段来还原同一个顺序维度。
     *
     * <p>🔴 <b>顺带钉住「码值不重复」</b>（B 侧 {@code declaredConstants()} 里
     * {@code assertNull(actual.put(...))} 的同款检查）：两个常量共用一个码值
     * ⇒ 客户端按码分支时会走错支路，而编译器不会报任何错。
     */
    private static Map<Integer, String> declaredConstants() {
        Map<Integer, String> actual = new LinkedHashMap<>();
        for (Field field : ErrCodes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                continue;
            }
            int code = readInt(field);
            String previous = actual.put(code, field.getName());
            if (previous != null) {
                throw new AssertionError("错误码重复：" + code + "（" + previous + " 与 " + field.getName() + "）");
            }
        }
        return actual;
    }

    private static int readInt(Field field) {
        try {
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 ErrCodes 的常量：" + field.getName(), e);
        }
    }

    /**
     * 反射读取 C 侧 {@link ErrMsg} 的私有文案表 {@code MSG_BY_CODE}。
     *
     * <p>⚠️ B 侧该字段是 {@code public}（B 侧 {@code CommentErrorMessages.java:38}），
     * C 侧收紧成了 {@code private}（{@code ErrMsg.java:53}）⇒ 本类只能反射读取。
     * <p>🔴 <b>读不到就失败</b>（不静默跳过）⇒ 否则这条断言会退化成「永远绿」的假门。
     */
    @SuppressWarnings("unchecked")
    private static Map<Integer, String> msgTable() {
        try {
            Field field = ErrMsg.class.getDeclaredField("MSG_BY_CODE");
            field.setAccessible(true);
            return (Map<Integer, String>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 ErrMsg.MSG_BY_CODE（码表 ⇒ 文案表的对应关系无法核对）", e);
        }
    }

    @Test
    @DisplayName("🔴 错误码总数是 27 —— 骨架注释若写别的数字一律以 B 侧源码为准")
    void thereAreTwentySevenCodes() {
        assertThat(B_SIDE_CODES)
                .as("B 侧 comment 域错误码表应当正好 27 条（改了条数 ⇒ 本表过时 ⇒ 必须同步更新断言）")
                .hasSize(27);
        assertThat(declaredConstants())
                .as("C 侧 ErrCodes 必须正好 27 个码（漏一个 / 多一个都会让客户端走错分支）")
                .hasSize(27);
        assertThat(msgTable())
                .as("C 侧文案表必须正好 27 条")
                .hasSize(27);
    }

    @Test
    @DisplayName("🔴 27 个码的数值、顺序与常量名与 B 侧逐条 1:1（不能增、不能改、不能漏、不能重排）")
    void everyCodeMatchesBSideOneByOne() {
        Map<Integer, String> actual = declaredConstants();

        assertThat(new ArrayList<>(actual.keySet()))
                .as("码值序列（含顺序）与 B 侧不一致 —— 改值 / 增删 / 重排都会被这条抓住")
                .containsExactlyElementsOf(new ArrayList<>(B_SIDE_CODES.keySet()));

        assertThat(new ArrayList<>(actual.values()))
                .as("常量名序列（含顺序）与 B 侧不一致 —— 改名会让按名排查的同事对不上")
                .containsExactlyElementsOf(new ArrayList<>(B_SIDE_CODES.values()));
    }

    @Test
    @DisplayName("🔴 comment 域两个专属码 1012 / 1013 都在，且互不合并")
    void commentSpecificCodesAreNotMerged() {
        // 这两个码是 comment 域独有的（like 域没有），漏掉任何一个都会让
        // 「评论区不存在 / 评论不存在」在 Java 侧退化成 1015 或 5001。
        assertThat(ErrCodes.ERROR_SUBJECT_NOT_EXIST).isEqualTo(1012);
        assertThat(ErrCodes.ERROR_COMMENT_NOT_EXIST).isEqualTo(1013);
        // 边界：subject 与 comment 是两个独立概念，绝不能共用一个码
        assertThat(List.of(ErrCodes.ERROR_SUBJECT_NOT_EXIST, ErrCodes.ERROR_COMMENT_NOT_EXIST))
                .as("subject 与 comment 是两个独立概念，绝不能共用一个码")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("🔴 comment-rpc 高频码锁定（改这些会打穿 7 个 RPC 的错误还原）")
    void frequentlyUsedRpcCodesArePinned() {
        // 逐条取自 B 侧调用点
        assertThat(ErrCodes.CODE_SERVER_BUSY).isEqualTo(1015);
        assertThat(ErrCodes.ERROR_SERVER_COMMON).isEqualTo(5001);
        assertThat(ErrCodes.ERROR_INPUT_WRONG).isEqualTo(2001);
        assertThat(ErrCodes.ERROR_DB_SELECT).isEqualTo(5003);
        assertThat(ErrCodes.ERROR_DB_UPDATE).isEqualTo(5002);
        assertThat(ErrCodes.ERROR_DB_INSERT).isEqualTo(5004);
        assertThat(ErrCodes.ERROR_KAFKA_PUSH).isEqualTo(5009);
        assertThat(ErrCodes.ERROR_SNOWFLAKE_ID).isEqualTo(5006);
        assertThat(ErrCodes.ERROR_USER_NO_RIGHT).isEqualTo(1008);
        assertThat(ErrCodes.ERROR_TOKEN_RUNTIME).isEqualTo(1006);
    }

    @Test
    @DisplayName("🔴 码常量表与文案表严格一一对应（没有只声明码却漏文案的）")
    void everyCodeHasAMessage() {
        assertThat(declaredConstants().keySet())
                .as("有码没有文案，或有文案没有码")
                .containsExactlyInAnyOrderElementsOf(msgTable().keySet());
    }
}
