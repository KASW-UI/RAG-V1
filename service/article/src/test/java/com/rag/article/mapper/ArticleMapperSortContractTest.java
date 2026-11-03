package com.rag.article.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p><b>排序白名单的结构守卫</b> —— 把 {@code ArticleMapper.selectAuthorView} 的
 * {@code <choose>} 结构钉死，<b>不依赖运行</b>。
 *
 * <h2>为什么这条判据必须是「真触发」，而本类却是「纯单测」</h2>
 * <p>这里要守的是<b>SQL 文本的结构</b>（哪几个排序字段被白名单承认、方向分支有几条）。
 * 这件事<b>在运行期不会「失效」</b> —— 它要么写对了，要么写错了 ⇒
 * <b>读文本就够了</b>，不需要起数据库。
 * <p>⚠️ 但它<b>不能替代</b>运行期证据：{@code <script>} 的<b>参数名</b>与
 * {@code @Param} 的一致性是运行期才解析的 ⇒ 那一条由
 * {@link ArticleMapperScriptRealPgTest}（真 PG）承担。
 * <p>⇒ 两者<b>配套</b>：本类守「结构写没写对」，真 PG 类守「运行时是不是真按这个结构执行」。
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧 {@code service/article/article-rpc/.../mapper/ArticleMapper.java:151-184}
 *       （{@code selectAuthorView} 的 {@code <choose>} 文本与 javadoc）；</li>
 *   <li>Go 出处（消费点）{@code service/article/rpc/internal/model/article.go:161-176}：
 *   <pre>
 *   order := "created_at desc"
 *   if opt.SortBy != "" {
 *       switch opt.SortBy {
 *       case "create_time": order = "created_at"
 *       case "view_count":  order = "view_count"
 *       case "like_count":  order = "like_count"
 *       }
 *       if opt.Desc { order += " desc" } else { order += " asc" }
 *   }
 *   </pre></li>
 * </ul>
 *
 * <h2>可证伪性（改掉实现会不会红）</h2>
 * <ul>
 *   <li>把方向 {@code <choose>} 从<b>三分支</b>简化成两分支
 *       （删掉 {@code <when test="sortBy == null or sortBy == ''">DESC</when>}）
 *       ⇒ 用例 ③ <b>立刻红</b> ⇒ 默认调用（{@code sort_by} 不设）会整页颠倒；</li>
 *   <li>往白名单里加一个字段（如 {@code comment_count}）⇒ 用例 ② <b>立刻红</b>；</li>
 *   <li>把字段白名单的映射改错（{@code like_count} → {@code created_at}）⇒ 用例 ② <b>立刻红</b>；</li>
 *   <li>把 {@code <if>} 里的参数名写错（如 {@code secondaryTag}）
 *       ⇒ 用例 ⑤ <b>立刻红</b>（与本仓历史缺陷同型）。</li>
 * </ul>
 */
class ArticleMapperSortContractTest {

    /** {@code <choose>…</choose>} 块（DOTALL，允许跨行）。 */
    private static final Pattern CHOOSE_BLOCK = Pattern.compile("(?s)<choose>(.*?)</choose>");

    /** 单个 {@code <when test="…">内容</when>}。 */
    private static final Pattern WHEN = Pattern.compile(
            "<when\\s+test=\"([^\"]*)\"\\s*>(.*?)</when>", Pattern.DOTALL);

    /** {@code <otherwise>内容</otherwise>}。 */
    private static final Pattern OTHERWISE = Pattern.compile(
            "<otherwise>(.*?)</otherwise>", Pattern.DOTALL);

    /** {@code <if test="名字 …">} —— 只取第一个标识符（参数名）。 */
    private static final Pattern IF_TEST = Pattern.compile(
            "<if\\s+test=\"([A-Za-z0-9_]+)\\s*(!=|==)");

    private static final Method SELECT_AUTHOR_VIEW = method(
            "selectAuthorView", String.class, String.class, String.class, String.class,
            boolean.class, int.class, long.class);

    private static final Method COUNT_AUTHOR_VIEW = method(
            "countAuthorView", String.class, String.class, String.class);

    // ==================================================================
    // ① 白名单：只认 create_time / view_count / like_count
    // ==================================================================

    @Test
    @DisplayName("结构守卫：sortBy 白名单**恰好**只认 create_time / view_count / like_count")
    void sortWhitelistContainsExactlyThreeFields() {
        String fieldChoice = chooseBlock(scriptOf(SELECT_AUTHOR_VIEW), 0);

        Set<String> whitelisted = new LinkedHashSet<>();
        Matcher m = WHEN.matcher(fieldChoice);
        while (m.find()) {
            String test = m.group(1).trim();
            Matcher sortByMatcher = Pattern.compile("sortBy\\s*==\\s*'([^']+)'").matcher(test);
            assertTrue(sortByMatcher.find(),
                    "字段白名单分支的 test 必须是 sortBy == '…' 形态，实际：" + test);
            whitelisted.add(sortByMatcher.group(1));
        }

        assertEquals(Set.of("create_time", "view_count", "like_count"), whitelisted,
                "🔴 白名单必须恰好是 {create_time, view_count, like_count}；"
                        + "B 侧 model/article.go:161-176 的 switch 只认这三个，其余值落默认字段 created_at");
    }

    @Test
    @DisplayName("结构守卫：白名单的字段→列映射逐条正确，且 otherwise 落回 created_at")
    void sortWhitelistMapsToTheRightColumns() {
        String fieldChoice = chooseBlock(scriptOf(SELECT_AUTHOR_VIEW), 0);

        java.util.Map<String, String> mapping = new java.util.LinkedHashMap<>();
        Matcher m = WHEN.matcher(fieldChoice);
        while (m.find()) {
            String test = m.group(1).trim();
            String column = m.group(2).trim();
            Matcher sortByMatcher = Pattern.compile("sortBy\\s*==\\s*'([^']+)'").matcher(test);
            assertTrue(sortByMatcher.find(), "字段白名单分支的 test 必须是 sortBy == '…' 形态：" + test);
            mapping.put(sortByMatcher.group(1), column);
        }

        assertEquals("created_at", mapping.get("create_time"),
                "Go: case \"create_time\": order = \"created_at\"");
        assertEquals("view_count", mapping.get("view_count"),
                "Go: case \"view_count\": order = \"view_count\"");
        assertEquals("like_count", mapping.get("like_count"),
                "Go: case \"like_count\": order = \"like_count\"");

        Matcher otherwise = OTHERWISE.matcher(fieldChoice);
        assertTrue(otherwise.find(), "字段白名单必须有 otherwise 分支");
        assertEquals("created_at", otherwise.group(1).trim(),
                "未知 sortBy 必须落回默认字段 created_at（B 侧 switch 的 default）");
    }

    // ==================================================================
    // ② 方向：三分支（不可简化成两分支）
    // ==================================================================

    @Test
    @DisplayName("🔴 结构守卫：方向分支必须**恰好三条**（sortBy 空 ⇒ DESC；desc ⇒ DESC；否则 ASC）")
    void directionHasExactlyThreeBranches() {
        String direction = chooseBlock(scriptOf(SELECT_AUTHOR_VIEW), 1);

        List<String> tests = new ArrayList<>();
        List<String> values = new ArrayList<>();
        Matcher m = WHEN.matcher(direction);
        while (m.find()) {
            tests.add(m.group(1).trim());
            values.add(m.group(2).trim());
        }
        Matcher otherwise = OTHERWISE.matcher(direction);
        assertTrue(otherwise.find(), "方向分支必须有 otherwise 分支");
        String otherwiseValue = otherwise.group(1).trim();

        assertEquals(2, tests.size(),
                "🔴 方向必须是**三分支**（2 个 <when> + 1 个 <otherwise>）："
                        + "Go model/article.go:161-176 的方向拼接整体位于 `if opt.SortBy != \"\"` 之内 "
                        + "⇒ SortBy 为空时 Desc 被**完全忽略**，恒 created_at desc；"
                        + "简化成两分支（删掉「sortBy 为空 ⇒ DESC」那一支）会让默认调用整页颠倒。"
                        + "实际 <when> 数：" + tests.size() + " ⇒ " + tests);

        // ① 第一支：sortBy 为空 ⇒ DESC
        assertEquals("sortBy == null or sortBy == ''", tests.get(0),
                "第一支必须是「sortBy 为 null 或空串 ⇒ DESC」");
        assertEquals("DESC", values.get(0), "sortBy 为空时方向恒为 DESC");

        // ② 第二支：desc 为真 ⇒ DESC
        assertEquals("desc", tests.get(1), "第二支必须是「desc ⇒ DESC」");
        assertEquals("DESC", values.get(1), "desc=true ⇒ DESC");

        // ③ 第三支：otherwise ⇒ ASC
        assertEquals("ASC", otherwiseValue,
                "第三支（otherwise）必须是 ASC —— 即 desc=false 且 sortBy 非空时按升序");

        // ⚠️ 分支顺序本身也是契约：三分支的**先后**决定谁先命中
        assertTrue(tests.get(0).contains("sortBy"),
                "「sortBy 为空」这一支必须排在「desc」这一支**之前**（否则空 sortBy 会落到 otherwise ⇒ ASC）");
    }

    // ==================================================================
    // ③ <script> 里的参数名必须与 @Param 一致
    // ==================================================================

    @Test
    @DisplayName("🔴 结构守卫：<script> 里 <if> 判的参数名必须与 @Param 声明一致（本仓历史缺陷同型）")
    void scriptParameterNamesMatchParamAnnotations() {
        assertScriptParameterNamesMatch(SELECT_AUTHOR_VIEW);
        assertScriptParameterNamesMatch(COUNT_AUTHOR_VIEW);
    }

    private void assertScriptParameterNamesMatch(Method method) {
        Set<String> paramNames = new LinkedHashSet<>();
        for (int i = 0; i < method.getParameterCount(); i++) {
            for (java.lang.annotation.Annotation annotation : method.getParameterAnnotations()[i]) {
                if (annotation instanceof Param param) {
                    paramNames.add(param.value());
                }
            }
        }
        assertFalse(paramNames.isEmpty(), method.getName() + " 必须用 @Param 显式命名参数");

        String script = scriptOf(method);
        Matcher m = IF_TEST.matcher(script);
        int checked = 0;
        while (m.find()) {
            String used = m.group(1);
            checked++;
            assertTrue(paramNames.contains(used),
                    "🔴 " + method.getName() + " 的 <if test=\"" + used + " …\"> 用了"
                            + "**未声明**的参数名；@Param 声明的是 " + paramNames
                            + " ⇒ MyBatis 解析 script 时会抛 "
                            + "BindingException: Parameter '" + used + "' not found");
        }
        assertTrue(checked > 0, method.getName() + " 的 script 至少要有一个 <if> 判据可供核对");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static String scriptOf(Method method) {
        Select select = method.getAnnotation(Select.class);
        assertNotNull(select, method.getName() + " 必须有 @Select");
        return String.join("\n", select.value());
    }

    private static String chooseBlock(String script, int index) {
        Matcher m = CHOOSE_BLOCK.matcher(script);
        List<String> blocks = new ArrayList<>();
        while (m.find()) {
            blocks.add(m.group(1));
        }
        assertTrue(blocks.size() > index,
                "script 里的 <choose> 块数不足：期望至少 " + (index + 1) + " 个，实际 " + blocks.size());
        return blocks.get(index);
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            return ArticleMapper.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("ArticleMapper 上找不到方法 " + name, e);
        }
    }
}
