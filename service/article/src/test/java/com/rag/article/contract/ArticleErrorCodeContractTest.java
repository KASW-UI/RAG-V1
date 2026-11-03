package com.rag.article.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>article 域错误码契约 —— 逐条对照 B 侧
 * {@code service/article/article-common/src/main/java/com/rag/article/article/common/errmsg/ErrorCode.java}。
 *
 * <h2>为什么这组判据必须是「真触发」</h2>
 * <p>错误码表是<b>L1 跨服务契约</b>：码值与文案进 HTTP 信封的 {@code code}/{@code msg} 字段，
 * <b>客户端可见</b>。而本类不碰数据库 —— 它要验的是「<b>这张表本身</b>是不是 B 侧那张表」，
 * 这件事<b>只由表的内容决定</b>，与运行机制无关 ⇒ 纯单测是<b>恰当</b>的形态
 * （AGENTS.md §3.3.3 的「真触发」要求针对的是<b>机制是否生效</b>；错误码表没有机制）。
 * <p>⚠️ 但断言的期望值<b>全部写成字面量</b>并逐条注明 B 侧出处
 * ⇒ 不允许「照着当前实现写断言」—— 那只会把一个错误答案锁成绿色。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧测试 {@code service/article/article-common/src/test/java/com/rag/article/article/common/errmsg/ArticleErrorCodeContractTest.java:76-338}
 *       （15 例全量可移植）；</li>
 *   <li>B 侧真源 {@code ErrorCode.java}：const 块 {@code :78-176} ＋
 *       {@code codeMsg} 表（码值逐条见各断言的注释）。</li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>改任何一个码值 ⇒ 用例 ② <b>立刻红</b>；</li>
 *   <li>改任何一条文案（含中文标点）⇒ 用例 ③ <b>立刻红</b>；</li>
 *   <li>把回落从 {@code "FAIL"} 改成 null/空串 ⇒ 用例 ⑥ <b>立刻红</b>；</li>
 *   <li>新增一个码 ⇒ 用例 ①（恰好 15 个）与 ⑪（集合相等）<b>立刻红</b>；</li>
 *   <li>把 5004/5005/5006「统一」成 users/like 域的含义 ⇒ 用例 ⑨ <b>立刻红</b>。</li>
 * </ul>
 *
 * <h2>C 侧的表达层差异（不影响本类断言）</h2>
 * <p>B 侧是<b>一个枚举</b>（码值与文案同体）；C 侧按 D5 + D11 拆成两个类：
 * {@link ErrCodes}（码值 ＋ 逐条文案 ＋ {@code of(int)}）与 {@link ErrMsg}（{@code int → 文案} 查表）。
 * ⇒ 本类的 {@code ErrMsg.getMsg(code)} 就是 B 侧 {@code ErrorCode.getMsg(code)} 的对位物。
 */
class ArticleErrorCodeContractTest {

    /**
     * B 侧 {@code ErrorCode.java} 的 const 块逐条抄录（码值）。
     *
     * <p>用枚举名而非裸 int 作为期望值的键，避免两边一起错却仍然自洽。
     */
    private static final Map<ErrCodes, Integer> B_CONST_VALUES = Map.ofEntries(
            Map.entry(ErrCodes.SUCCESS, 200),
            Map.entry(ErrCodes.ERROR, 500),
            Map.entry(ErrCodes.CODE_SERVER_BUSY, 1015),
            Map.entry(ErrCodes.ERROR_ARTICLE_EXIST, 1001),
            Map.entry(ErrCodes.ERROR_ARTICLE_NONE, 1002),
            Map.entry(ErrCodes.ERROR_ARTICLE_PUBLISHING, 1003),
            Map.entry(ErrCodes.ERROR_ARTICLE_PUBLISH_FAILED, 1004),
            Map.entry(ErrCodes.ERROR_UNAUTHORIZED, 1005),
            Map.entry(ErrCodes.ERROR_ARTICLE_FORBIDDEN, 1006),
            Map.entry(ErrCodes.ERROR_SERVER_COMMON, 5001),
            Map.entry(ErrCodes.ERROR_DB_UPDATE, 5002),
            Map.entry(ErrCodes.ERROR_DB_SELECT, 5003),
            Map.entry(ErrCodes.ERROR_MINIO_UPLOAD, 5004),
            Map.entry(ErrCodes.ERROR_MINIO_DELETE, 5005),
            Map.entry(ErrCodes.ERROR_MINIO_DOWNLOAD, 5006));

    /** B 侧 {@code ErrorCode.java} 的 {@code codeMsg} 表逐条抄录（含中文标点）。 */
    private static final Map<ErrCodes, String> B_CODE_MSG = Map.ofEntries(
            Map.entry(ErrCodes.SUCCESS, "OK"),
            Map.entry(ErrCodes.ERROR, "FAIL"),
            Map.entry(ErrCodes.CODE_SERVER_BUSY, "服务繁忙"),
            Map.entry(ErrCodes.ERROR_ARTICLE_EXIST, "文章已存在"),
            Map.entry(ErrCodes.ERROR_ARTICLE_NONE, "文章不存在"),
            Map.entry(ErrCodes.ERROR_ARTICLE_PUBLISHING, "文章处理中，请稍后查看"),
            Map.entry(ErrCodes.ERROR_ARTICLE_PUBLISH_FAILED, "文章发布失败"),
            Map.entry(ErrCodes.ERROR_UNAUTHORIZED, "请先登录"),
            Map.entry(ErrCodes.ERROR_ARTICLE_FORBIDDEN, "只能编辑或删除自己的文章"),
            Map.entry(ErrCodes.ERROR_SERVER_COMMON, "系统内部错误"),
            Map.entry(ErrCodes.ERROR_DB_UPDATE, "更新数据库失败"),
            Map.entry(ErrCodes.ERROR_DB_SELECT, "查询数据库失败"),
            Map.entry(ErrCodes.ERROR_MINIO_UPLOAD, "文件上传失败"),
            Map.entry(ErrCodes.ERROR_MINIO_DELETE, "文件删除失败"),
            Map.entry(ErrCodes.ERROR_MINIO_DOWNLOAD, "文件下载失败"));

    @Test
    @DisplayName("B 侧 const 块恰好 15 个常量——多一个少一个都算契约变更")
    void thereAreExactlyFifteenCodes() {
        assertEquals(15, ErrCodes.values().length, "B 侧 const 块共 15 行；漏迁或擅自新增都会让计数对不上");
    }

    @Test
    @DisplayName("每个常量的码值逐条等于 B 侧 ErrorCode 的 const 值（L1 跨服务契约）")
    void everyCodeValueMatchesBOneByOne() {
        for (Map.Entry<ErrCodes, Integer> expected : B_CONST_VALUES.entrySet()) {
            assertEquals(expected.getValue().intValue(), expected.getKey().getCode(),
                    "码值漂移：" + expected.getKey() + " 的码值与 B 侧 ErrorCode 不一致");
        }
    }

    @Test
    @DisplayName("每条文案逐条等于 B 侧 codeMsg 值，含中文标点")
    void everyMessageMatchesBOneByOne() {
        for (Map.Entry<ErrCodes, String> expected : B_CODE_MSG.entrySet()) {
            assertEquals(expected.getValue(), expected.getKey().getMsg(),
                    "文案漂移：" + expected.getKey() + " 的文案与 B 侧 codeMsg 不一致");
        }
    }

    @Test
    @DisplayName("枚举声明顺序与 B 侧 const 块书写顺序一致，便于逐行 review")
    void declarationOrderMatchesBSourceOrder() {
        // B 侧书写顺序：通用 → 文章业务 → 系统与基础设施。
        List<ErrCodes> expectedOrder = List.of(
                ErrCodes.SUCCESS,
                ErrCodes.ERROR,
                ErrCodes.CODE_SERVER_BUSY,
                ErrCodes.ERROR_ARTICLE_EXIST,
                ErrCodes.ERROR_ARTICLE_NONE,
                ErrCodes.ERROR_ARTICLE_PUBLISHING,
                ErrCodes.ERROR_ARTICLE_PUBLISH_FAILED,
                ErrCodes.ERROR_UNAUTHORIZED,
                ErrCodes.ERROR_ARTICLE_FORBIDDEN,
                ErrCodes.ERROR_SERVER_COMMON,
                ErrCodes.ERROR_DB_UPDATE,
                ErrCodes.ERROR_DB_SELECT,
                ErrCodes.ERROR_MINIO_UPLOAD,
                ErrCodes.ERROR_MINIO_DELETE,
                ErrCodes.ERROR_MINIO_DOWNLOAD);

        assertEquals(expectedOrder, List.of(ErrCodes.values()),
                "枚举顺序与 B 侧不一致；B 侧 codeMsg 与 const 是分开的，顺序漂移会让逐行对照失效");
    }

    @Test
    @DisplayName("码值无重复——重复会让 code→枚举 反查静默丢码")
    void codeValuesAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (ErrCodes ec : ErrCodes.values()) {
            assertTrue(seen.add(ec.getCode()),
                    "码值重复：" + ec + " 的码值 " + ec.getCode() + " 已被前面的常量占用");
        }
        assertEquals(15, seen.size());
    }

    @Test
    @DisplayName("未知码回落为 \"FAIL\"，不是 null、不是空串、不是 code 的字符串（L2 唯一分支）")
    void unknownCodeFallsBackToFail() {
        // B 侧：msg, ok := codeMsg[code]; if !ok { return codeMsg[Error] }
        // codeMsg[Error] 即 500 号的文案 "FAIL"。
        List<Integer> unknownCodes = List.of(
                0,                          // 零值
                -1,                         // 负数
                1,                          // 介于两个真实码之间
                999,                        // 1000~1014 空档
                1016,                       // 1015 与 2001 之间
                2001,                       // ⚠️ 真实存在于 users/like 域，article 域必须回落
                5013,                       // 5006 之后
                5007,                       // ⚠️ 真实存在于 users 域（JSON序列化失败）
                Integer.MAX_VALUE,
                Integer.MIN_VALUE);

        for (int code : unknownCodes) {
            assertEquals("FAIL", ErrMsg.getMsg(code),
                    "未知码 " + code + " 必须回落为 \"FAIL\"（B 侧 codeMsg[Error]）");
            assertSame(ErrCodes.ERROR, ErrCodes.of(code),
                    "未知码 " + code + " 必须回落到 ERROR 枚举，不得返回 null");
        }
    }

    @Test
    @DisplayName("ErrMsg.getMsg(int) 对每个已登记码都能取回自身文案（查表与枚举不自洽会漏文案）")
    void getMsgRoundTripsForEveryRegisteredCode() {
        for (ErrCodes ec : ErrCodes.values()) {
            assertEquals(ec.getMsg(), ErrMsg.getMsg(ec.getCode()),
                    ec + "：ErrMsg.getMsg(int) 与实例 getMsg() 结果不一致");
        }
    }

    @Test
    @DisplayName("getCode() 产出裸 int，可直接喂给异常工厂 / 日志埋点")
    void getCodeYieldsPrimitiveIntForExistingConsumers() {
        for (ErrCodes ec : ErrCodes.values()) {
            int code = ec.getCode();
            assertEquals(ec, ErrCodes.of(code));
            assertNotNull(ErrMsg.getMsg(code));
        }
    }

    /**
     * article 与 users / like 是<b>不同域但共用同一段 int 值空间</b>，
     * 且 article 的 15 个码<b>全部</b>都能在 users 域找到同号。
     *
     * <p>其中 <b>6 个是同源模板、两侧文案相同</b>（200 / 500 / 1015 / 5001 / 5002 / 5003），
     * <b>9 个是同号不同义</b> —— 后者才是危险区。
     *
     * <p>⇒ 这里把 <b>9 个</b>同号不同义的码逐条钉死。
     * 期望值全部写成<b>字面量</b>而不是引用 users 模块的当前值 ——
     * users 日后改文案不应连带改掉本断言（article 的真源是 B 侧，与 users 无关）。
     */
    @Test
    @DisplayName("跨域同码不同义：article 的 9 个同号不同义码不得被改成 users/like 域的含义")
    void codesDoNotCollideWithUsersDomainMeaning() {
        record Collision(int code, String articleMsg, String usersMsg) {
        }
        List<Collision> collisions = List.of(
                new Collision(1001, "文章已存在", "用户名已存在"),
                new Collision(1002, "文章不存在", "用户名或密码错误"),
                new Collision(1003, "文章处理中，请稍后查看", "用户不存在"),
                new Collision(1004, "文章发布失败", "TOKEN不存在"),
                new Collision(1005, "请先登录", "TOKEN格式错误"),
                new Collision(1006, "只能编辑或删除自己的文章", "TOKEN已过期"),
                new Collision(5004, "文件上传失败", "数据库插入失败"),
                new Collision(5005, "文件删除失败", "事务操作失败"),
                new Collision(5006, "文件下载失败", "雪花ID生成失败"));

        for (Collision c : collisions) {
            assertAll(
                    () -> assertEquals(c.articleMsg(), ErrMsg.getMsg(c.code()),
                            "article 的 " + c.code() + " 必须是「" + c.articleMsg() + "」"),
                    () -> assertNotEquals(c.usersMsg(), ErrMsg.getMsg(c.code()),
                            "article 的 " + c.code() + " 不能被 users/like 域的含义覆盖成「"
                                    + c.usersMsg() + "」"),
                    () -> assertNotEquals(c.usersMsg(), ErrCodes.of(c.code()).getMsg(),
                            "article 的 " + c.code() + " 不能被 users/like 域的含义覆盖成「"
                                    + c.usersMsg() + "」"));
        }
    }

    /**
     * 与 {@link #codesDoNotCollideWithUsersDomainMeaning()} 互补：另外 <b>6 个</b>码
     * 在 users / like 域是<b>同源模板、两侧文案本来就相同</b>。
     *
     * <p>把它们也钉死，是为了防止反向操作 —— 有人看到「5004 = 文件上传失败」与别域不同，
     * 就「顺手统一」成「数据库插入失败」。那同样是漂移。
     */
    @Test
    @DisplayName("跨域同号同义的 6 个码：article 保持模板文案，不得被「统一」成别的说法")
    void sharedTemplateCodesKeepTheirTemplateWording() {
        Map<Integer, String> shared = Map.of(
                200, "OK",
                500, "FAIL",
                1015, "服务繁忙",
                5001, "系统内部错误",
                5002, "更新数据库失败",
                5003, "查询数据库失败");

        for (Map.Entry<Integer, String> e : shared.entrySet()) {
            assertEquals(e.getValue(), ErrMsg.getMsg(e.getKey()),
                    "article 的 " + e.getKey() + " 是同源模板码，文案必须是「" + e.getValue() + "」");
        }
    }

    /**
     * B 侧的 {@code const} 块与 {@code codeMsg} 表是<b>两个独立结构</b>；
     * C 侧合并成了一个枚举。
     *
     * <p>合并本身不改变可观察行为，但<b>合并动作是可能丢东西的</b>：漏掉一个 const、
     * 或漏掉一条 table entry，在「表 = 枚举的 msg 字段」的单结构下都看不出来。
     */
    @Test
    @DisplayName("B 侧 const 集合 ≡ B 侧 codeMsg 键集合 ≡ C 侧枚举的码集合（合并成单枚举没丢东西）")
    void bConstSetEqualsBCodeMsgSetEqualsJavaEnumSet() {
        // B 侧 const 块 —— 裸 int 抄录。
        Set<Integer> bConstCodes = Set.of(
                200, 500, 1015,
                1001, 1002, 1003, 1004, 1005, 1006,
                5001, 5002, 5003, 5004, 5005, 5006);

        // B 侧 codeMsg 表的键 —— 裸 int 抄录（**独立抄一遍，不复用上面的字面量**）。
        Set<Integer> bCodeMsgKeys = Set.of(
                200, 500, 1015,
                1001, 1002, 1003, 1004, 1005, 1006,
                5001, 5002, 5003, 5004, 5005, 5006);

        assertEquals(15, bConstCodes.size(), "B 侧 const 块抄录出来应有 15 个码");
        assertEquals(15, bCodeMsgKeys.size(), "B 侧 codeMsg 表抄录出来应有 15 个键");
        assertEquals(bConstCodes, bCodeMsgKeys,
                "B 侧 const 块与 codeMsg 表登记的码集合不相等 —— 真源自身的不变量被破坏"
                        + "（B 侧的 api 层会因此在信封里意外回落成 \"FAIL\"）");

        Set<Integer> javaEnumCodes = new HashSet<>();
        for (ErrCodes ec : ErrCodes.values()) {
            javaEnumCodes.add(ec.getCode());
        }
        assertEquals(bConstCodes, javaEnumCodes,
                "C 侧枚举的码集合与 B 侧不一致 —— 合并成单枚举时漏迁/多迁了码");
    }

    @Test
    @DisplayName("15 条文案两两互异")
    void messagesAreMutuallyDistinct() {
        Map<String, ErrCodes> byMsg = new LinkedHashMap<>();
        for (ErrCodes ec : ErrCodes.values()) {
            ErrCodes prev = byMsg.put(ec.getMsg(), ec);
            assertNull(prev, "文案重复：" + ec.getMsg() + " 同时用于 " + prev + " 与 " + ec);
        }
        assertEquals(15, byMsg.size());
    }

    @Test
    @DisplayName("每个码都同时有枚举常量与非空白文案（无「有码无文案」的空洞）")
    void everyCodeHasBothAConstantAndANonBlankMessage() {
        for (ErrCodes ec : ErrCodes.values()) {
            assertNotNull(ec.getMsg(), ec + " 的文案为 null —— B 侧 codeMsg 的值都非空");
            assertFalse(ec.getMsg().isBlank(), ec + " 的文案是空白 —— B 侧 codeMsg 的值都非空白");
        }
    }

    @Test
    @DisplayName("错误码表不可变：values() 每次返回新数组，of() 返回同一实例，getMsg() 是纯函数")
    void errorCodeIsImmutableAndSideEffectFree() {
        ErrCodes[] first = ErrCodes.values();
        first[0] = ErrCodes.ERROR;                 // 试图篡改返回的数组
        assertSame(ErrCodes.SUCCESS, ErrCodes.values()[0],
                "values() 必须返回防御性副本，调用方改写它不得影响后续调用");

        assertSame(ErrCodes.of(1002), ErrCodes.of(1002),
                "of() 必须对同一码返回同一实例（不得每次新建）");
        assertSame(ErrCodes.ERROR, ErrCodes.of(12345),
                "of() 对未登记码必须回落到同一个 ERROR 实例");

        String m1 = ErrMsg.getMsg(1002);
        String m2 = ErrMsg.getMsg(1002);
        assertEquals(m1, m2, "getMsg() 必须是纯函数（两次调用结果必须相等）");
    }

    @Test
    @DisplayName("article 域不搬 users/like 域独有的码")
    void doesNotCarryOverOtherDomainsExclusiveCodes() {
        // users 域独有 5011/5012（Redis 查询/更新失败）等。
        for (int code : new int[]{5011, 5012}) {
            assertEquals("FAIL", ErrMsg.getMsg(code),
                    "code " + code + " 不属于 article 域，必须回落 \"FAIL\"");
        }
        // 1001 在 article 域有独立含义（文章已存在），不是「用户名已存在」。
        assertEquals("文章已存在", ErrMsg.getMsg(1001));
        assertNotEquals("用户名已存在", ErrMsg.getMsg(1001));
    }
}
