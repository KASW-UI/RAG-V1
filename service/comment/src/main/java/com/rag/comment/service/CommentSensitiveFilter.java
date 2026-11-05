package com.rag.comment.service;

import com.rag.comment.config.CommentRpcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 敏感词判定 —— 对应 Go 端 {@code service/comment/rpc/internal/utils/sensitive.go:33-42}
 * 的 {@code SensitiveFilter.Match}。
 *
 * <p><b>抄写基准</b>：B 侧
 * {@code service/comment/comment-rpc/src/main/java/com/rag/comment/comment/rpc/service/CommentSensitiveFilter.java:1-114}
 * （L2 业务语义层以 B 侧为基准整段抄 · D13/D16）。
 *
 * <h2>Go 出处与实际词库</h2>
 * <p>Go 用的是 Aho-Corasick 自动机（{@code github.com/anknown/ahocorasick}），
 * 但词库<b>只有 2 个词</b>，且硬编码在 {@code service_context.go:29}：
 * <pre>
 * blackwords := []string{"傻逼", "我草"}
 * </pre>
 * Java 侧把词库外提到 {@code comment.sensitive-words}，<b>默认值与 Go 逐字一致</b>
 * （C 侧默认值落在 {@link CommentRpcProperties#getSensitiveWords()}，
 * 值为 {@code ["傻逼","我草"]}）。
 *
 * <h2>为什么用朴素子串扫描而不是 AC 自动机</h2>
 * <p>{@code Match} 返回两个值：
 * <ol>
 *   <li><b>{@code bool} 是否命中</b> —— 它决定 {@code status = 0 还是 1}
 *       （audit_consumer.go:41-46），进而决定评论是否进入「审核中」、
 *       是否被 {@code InsertCommentTx} 以 {@code state = 1} 落库、是否发通知。
 *       <b>这是可观察行为</b>。</li>
 *   <li>{@code string} 命中的那个词 —— 它<b>只</b>进一行日志
 *       （{@code audit_consumer.go:44} 的 {@code "comment %d matched sensitive word %q"}），
 *       不进库、不上线。</li>
 * </ol>
 *
 * <p>子串扫描与 AC 机在第 ① 项上<b>完全等价</b>（AC 机的本质就是「存在某个模式串出现」），
 * 且快得可以忽略（2 个词 × 平均长度 2）。第 ② 项在「多个词同时命中」时两者选取顺序可能不同
 * （AC 机按模式串长度优先，朴素扫描按词库顺序），但既然它只进日志，
 * <b>当前不可观察</b> ⇒ 不为它引入一个第三方自动机依赖。
 *
 * <p>⚠️ 若将来词库涨到几百条、或需要「按最长命中」选词，这里要换成真正的 AC 机
 * —— 那时第 ② 项就变成可观察的了。
 *
 * <h3>L4 差异（AGENTS §3.2 三段式）</h3>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：
 *     <ul>
 *       <li>包名由 {@code com.rag.comment.comment.rpc.service} 压平为 {@code com.rag.comment.service}（D7）；</li>
 *       <li>{@code CommentRpcProperties} 的 import 由 {@code com.rag.comment.comment.rpc.config}
 *           改为 {@code com.rag.comment.config}（D7）；</li>
 *       <li>🔴 <b>不用 {@code @Slf4j}</b>（D18 禁止项）：B 侧的 {@code GoStyleLogger} 带
 *           {@code call_chain} 字段，{@code @Slf4j} 拿不到 ⇒ 这里保持<b>显式</b>
 *           {@code LoggerFactory.getLogger(...)}（与 B 侧写法逐字一致）。</li>
 *     </ul></li>
 *   <li><b>语义还一样吗</b>：一样。词库的归一化规则（{@link Locale#ROOT} 小写折叠）、
 *       空词库的告警文案、三个方法的返回值逐字未改。</li>
 *   <li><b>B 出处</b>：见上（逐条行号见各方法的 javadoc）。</li>
 * </ol>
 */
@Component
public class CommentSensitiveFilter {

    private static final Logger LOG = LoggerFactory.getLogger(CommentSensitiveFilter.class);

    /** 构造期归一化后的小写词库。 */
    private final List<String> lowerCaseWords;

    /**
     * 对应 B 侧 {@code NewSensitiveFilter}（sensitive.go:12-31）的 Java 版。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentSensitiveFilter.java:51-66}。
     *
     * @param props 本域配置（词库来自 {@code comment.sensitive-words}）
     */
    public CommentSensitiveFilter(CommentRpcProperties props) {
        List<String> configured = props.getSensitiveWords();
        if (configured == null || configured.isEmpty()) {
            // Go 侧 NewSensitiveFilter 对空词库只打一行 warning 并返回一个空机
            // （sensitive.go:15-18），Match 恒返回 false。行为一致。
            LOG.warn("Warning!敏感词库为空!");
            this.lowerCaseWords = List.of();
        } else {
            // Go 的 NewSensitiveFilter 对每个词做 strings.ToLower（sensitive.go:20），
            // Match 时对文本做 strings.ToLower（sensitive.go:37）⇒ 比较是大小写不敏感的。
            this.lowerCaseWords = configured.stream()
                    .filter(w -> w != null && !w.isEmpty())
                    .map(w -> w.toLowerCase(Locale.ROOT))
                    .toList();
        }
    }

    /**
     * 对应 Go {@code Match(text)}。
     *
     * <p>⚠️ <b>大小写折叠必须用 {@link Locale#ROOT}</b>：Java 默认 locale 在土耳其语环境下
     * 会把 {@code "I"} 折叠成 {@code "ı"}（dotless i），而 Go 的 {@code strings.ToLower}
     * 走的是 Unicode 默认大小写映射，两者在含 {@code I/i} 的词上会分叉。
     * {@code Locale.ROOT} 才与 Go 对齐。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentSensitiveFilter.java:68-89}。
     *
     * @param text 待检文本
     * @return {@code true} 表示命中敏感词（⇒ 评论落库为「审核中」且不发通知）
     */
    public boolean match(String text) {
        if (text == null || text.isEmpty() || lowerCaseWords.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : lowerCaseWords) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 命中的第一个词（仅供日志）。
     *
     * <p>对应 Go {@code return true, string(terms[0].Word)} 的返回值。
     * 选取顺序见类级说明（当前不可观察）。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentSensitiveFilter.java:91-108}。
     *
     * @param text 待检文本
     * @return 命中的词；未命中时为空串
     */
    public String firstHit(String text) {
        if (text == null || text.isEmpty() || lowerCaseWords.isEmpty()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : lowerCaseWords) {
            if (lower.contains(word)) {
                return word;
            }
        }
        return "";
    }

    /**
     * 当前生效的词库快照（供测试断言默认值与 Go 一致）。
     *
     * <p>B 出处：B 侧 {@code …/rpc/service/CommentSensitiveFilter.java:110-113}。
     *
     * @return 小写化后的词库（不可变）
     */
    public List<String> words() {
        return lowerCaseWords;
    }
}
