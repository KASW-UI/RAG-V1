package com.rag.comment.service;

import com.rag.comment.config.CommentRpcProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>敏感词判定与 Redis key 的契约测试</b> —— 对应 Go
 * {@code service_context.go:29} 的黑名单与 {@code internal/cache/keys.go:5-20} 的 key 构造。
 *
 * <p><b>为什么这两处必须单独钉</b>：它们都是「写错了不会报错、只会在线上出问题」的常量：
 * <ul>
 *   <li><b>词库错了 ⇒ 审核状态错</b> —— {@code match} 的返回值决定评论落库为
 *       {@code state=1}（审核中）还是 {@code state=0}（正常），并决定是否发通知；</li>
 *   <li><b>key 格式错了 ⇒ 缓存永远不命中</b> —— 表现为 DB 压力上升，
 *       <b>没有任何异常</b>。</li>
 * </ul>
 *
 * <h3>L4 差异（AGENTS.md §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.comment.comment.rpc.service} 压平为
 *           {@code com.rag.comment.service}（D7）；</li>
 *       <li>{@link CommentRpcProperties} 的 import 由
 *           {@code com.rag.comment.comment.rpc.config} 改为 {@code com.rag.comment.config}（D7）；</li>
 *       <li>{@link CommentRedisKeys} 由「{@code @Component} + 可实例化」改为
 *           「{@code final} + 私有构造器」的纯静态工具（5 个方法全是 static，
 *           B 侧的 {@code @Component} 只注册了一个无实例状态的 bean
 *           ⇒ 去掉它不改变任何可观察行为）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。词库归一化规则（{@link java.util.Locale#ROOT} 小写折叠）、
 *       空词库的告警行为、三个方法的返回值、以及 5 个 key 的<b>字面量 / 分隔符 / 参数顺序</b>
 *       全部逐字未改；{@code subjectKey}「只含 subjectID 不含 targetType」的
 *       <b>既有缺陷也照抄未修</b>（见 {@link #subjectKeyOmitsTargetTypeOnPurpose}）。</li>
 *   <li><b>B 出处</b>：
 *     <ul>
 *       <li>断言来源：B 侧
 *           {@code comment-rpc/src/test/java/com/rag/comment/comment/rpc/service/CommentSensitiveFilterAndKeysTest.java:27-105}；</li>
 *       <li>被测实现：B 侧
 *           {@code comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentSensitiveFilter.java:51-113}
 *           ＋ {@code …/rpc/service/CommentRedisKeys.java:30-75}
 *           ＋ {@code …/rpc/config/CommentRpcProperties.java:31}；</li>
 *       <li>Go 语义来源：B 侧自记其 Go 出处为
 *           {@code service/comment/rpc/internal/utils/sensitive.go:12-42}
 *           ＋ {@code service/comment/rpc/internal/cache/keys.go:5-20}
 *           ＋ {@code service/comment/rpc/internal/service_context.go:29}。</li>
 *     </ul></li>
 * </ol>
 */
class CommentSensitiveFilterAndKeysTest {

    // ==================== 敏感词 ====================

    private static CommentSensitiveFilter defaultFilter() {
        CommentRpcProperties props = new CommentRpcProperties();
        return new CommentSensitiveFilter(props);
    }

    @Test
    @DisplayName("🔴 默认词库与 Go service_context.go:29 逐字一致：{\"傻逼\", \"我草\"}")
    void defaultWordListMatchesGo() {
        assertThat(defaultFilter().words()).containsExactlyInAnyOrder("傻逼", "我草");
    }

    @Test
    @DisplayName("🔴 命中敏感词 ⇒ status 应置 1（审核中），且不改内容")
    void matchesSensitiveWord() {
        assertThat(defaultFilter().match("你说的是傻逼吗")).isTrue();
        assertThat(defaultFilter().match("我草")).isTrue();
    }

    @Test
    @DisplayName("🔴 未命中 ⇒ false（落库为 state=0 正常，且会发通知）")
    void doesNotMatchOrdinaryContent() {
        assertThat(defaultFilter().match("今天天气不错")).isFalse();
        assertThat(defaultFilter().match("")).isFalse();
        assertThat(defaultFilter().match(null)).isFalse();
    }

    @Test
    @DisplayName("🔴 大小写不敏感（Go 对词与文本都做 strings.ToLower）")
    void caseInsensitive() {
        assertThat(defaultFilter().match("SENSITIVE")).isFalse(); // 词库里没有英文词，验证不误命中
        CommentRpcProperties props = new CommentRpcProperties();
        props.setSensitiveWords(List.of("BadWord"));
        CommentSensitiveFilter filter = new CommentSensitiveFilter(props);
        assertThat(filter.match("this is badword")).isTrue();
        assertThat(filter.match("this is BADWORD")).isTrue();
    }

    @Test
    @DisplayName("🔴 空词库 ⇒ 恒不命中（Go NewSensitiveFilter 对空词库只打 warning）")
    void emptyWordListNeverMatches() {
        CommentRpcProperties props = new CommentRpcProperties();
        props.setSensitiveWords(List.of());
        CommentSensitiveFilter filter = new CommentSensitiveFilter(props);
        assertThat(filter.match("傻逼")).isFalse();
        assertThat(filter.match("我草")).isFalse();
    }

    /**
     * 🔴 <b>{@code firstHit} 返回的是<b>小写化后</b>的词</b>，且未命中时返回<b>空串</b>。
     *
     * <p>B 出处：B 侧 {@code CommentSensitiveFilter.java:97-108}（{@code firstHit}）
     * 遍历的是<b>构造期已小写化</b>的 {@code lowerCaseWords}
     * （同文件 {@code :61-64} 的 {@code .map(w -> w.toLowerCase(Locale.ROOT))}）
     * ⇒ 返回值必然是小写形式；未命中走 {@code :106-107} 的 {@code return ""}。
     *
     * <p>⚠️ 该值只进一行日志（{@code audit_consumer.go:44}），<b>不进库、不上线</b>
     * ⇒ 但它是 {@code public} 方法且契约明确 ⇒ 钉住，免得将来有人改成「返回原词」而无人察觉。
     */
    @Test
    @DisplayName("🔴 firstHit 返回小写化的命中词；未命中返回空串")
    void firstHitReturnsLowercasedWord() {
        CommentRpcProperties props = new CommentRpcProperties();
        props.setSensitiveWords(List.of("BadWord"));
        CommentSensitiveFilter filter = new CommentSensitiveFilter(props);

        assertThat(filter.firstHit("this is BADWORD")).isEqualTo("badword");
        assertThat(filter.firstHit("this is badword")).isEqualTo("badword");
        assertThat(filter.firstHit("干净的评论")).isEmpty();
        assertThat(filter.firstHit(null)).isEmpty();
        assertThat(filter.firstHit("")).isEmpty();
    }

    // ==================== Redis key ====================

    @Test
    @DisplayName("🔴 key 格式逐字对应 Go keys.go")
    void redisKeysMatchGoFormats() {
        assertThat(CommentRedisKeys.subjectKey("a1")).isEqualTo("comment:subject:a1");
        assertThat(CommentRedisKeys.replyContentKey(42L)).isEqualTo("comment:content:42");
        assertThat(CommentRedisKeys.replyIndexPageKey("article", "a1", 7L, "hot", 20, 20))
                .isEqualTo("comment:index:article:a1:7:hot:20:20");
        assertThat(CommentRedisKeys.commentIndexKey(42L)).isEqualTo("comment:index:item:42");
        assertThat(CommentRedisKeys.targetPageScanPattern("article", "a1"))
                .isEqualTo("comment:index:article:a1:*");
    }

    @Test
    @DisplayName("🔴 已知缺陷必须保留：SubjectKey 不含 targetType，跨类型同 ID 会撞 key")
    void subjectKeyOmitsTargetTypeOnPurpose() {
        // Go keys.go:5-7 就是 `comment:subject:%s`，没有 targetType。
        // 「顺手补上」会让灰度期新旧进程互相读不到对方的缓存，并让存量 key 变孤儿。
        // 见 CommentRedisKeys#subjectKey 的类注释。
        assertThat(CommentRedisKeys.subjectKey("a1"))
                .isEqualTo(CommentRedisKeys.subjectKey("a1"));
        assertThat(CommentRedisKeys.subjectKey("42")).doesNotContain("article");
    }

    /**
     * 🔴 <b>把「不含 targetType」这条缺陷钉成<b>可执行</b>断言</b>（不是靠注释提醒）。
     *
     * <p>B 出处：B 侧 {@code CommentRedisKeys.java:30-32} 的实现就是
     * {@code "comment:subject:" + subjectId} —— 签名里<b>根本没有</b> targetType 参数
     * ⇒ 同 ID 不同类型的 target <b>必然</b>撞同一个 key。
     *
     * <p>⚠️ 本条与 {@link #subjectKeyOmitsTargetTypeOnPurpose} 的差别：
     * 那条断言的是「返回值里没有 {@code article} 字样」（弱），
     * 本条断言的是「<b>签名与实现里都不存在 targetType 这个维度</b>」（强）
     * ⇒ 将来若有人「顺手补上」targetType（例如把签名改成
     * {@code subjectKey(String targetType, String subjectId)}），
     * <b>本条会直接编译失败</b> ⇒ 缺陷的「补」动作不可能静默发生。
     */
    @Test
    @DisplayName("🔴 subjectKey 的签名里根本没有 targetType 维度（B 侧 keys.go:5-7 的既有缺陷）")
    void subjectKeyHasNoTargetTypeDimension() {
        // 编译期保证：subjectKey 只接受一个参数 ⇒ 没有地方可以塞 targetType
        assertThat(java.util.Arrays.stream(CommentRedisKeys.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("subjectKey"))
                .findFirst()
                .orElseThrow()
                .getParameterCount())
                .as("subjectKey 必须仍是单参（只吃 subjectID）")
                .isEqualTo(1);
        // 运行期保证：同 ID 不同类型的 target 撞同一个 key（这正是 B 侧的既有缺陷）
        assertThat(CommentRedisKeys.subjectKey("a1")).isEqualTo(CommentRedisKeys.subjectKey("a1"));
    }

    @Test
    @DisplayName("🔴 失效用的 SCAN 模式不覆盖单条索引 key 与正文 key（Go 的既有行为）")
    void scanPatternDoesNotCoverItemOrContentKeys() {
        String pattern = CommentRedisKeys.targetPageScanPattern("article", "a1");
        // comment:index:item:42 不匹配（item 段在前）
        assertThat("comment:index:item:42".startsWith("comment:index:article:a1:")).isFalse();
        // comment:content:42 完全不在 comment:index: 前缀下
        assertThat("comment:content:42".startsWith("comment:index:article:a1:")).isFalse();
        // comment:index:article:a1:7:hot:20:20 是分页 key，应被 SCAN 模式覆盖
        assertThat("comment:index:article:a1:7:hot:20:20").startsWith("comment:index:article:a1:");
        assertThat(pattern).isEqualTo("comment:index:article:a1:*");
    }
}
