package com.rag.common.logger;

/**
 * <p><b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/logger/LogOption.java:4:32}（{@code type LogOption func(*LogOptions)}）
 * ⇒ 本类是它的 Java 表达（{@code @FunctionalInterface}）⇒ <b>语义等价</b>：B 的函数选项 = Java 的函数式接口。
 * 对应 B 侧版 service/common/logger/logger.go 的 LogOption 函数选项类型：
 *
 * <pre>
 * type LogOption func(*LogOptions)
 * </pre>
 *
 * <p>B 侧版用函数类型实现 Functional Options 模式；Java 21 没有"函数类型"，所以用 {@link FunctionalInterface} 显式标注。
 *
 * <p>框架职责：
 * <ul>
 *   <li>B 侧版：函数是一等公民，可以直接传入</li>
 *   <li>Java 版：用 {@code @FunctionalInterface} 标记单方法接口，可被 lambda 表达式代替</li>
 * </ul>
 *
 * <p><b>⚠️ N:1 合并（RAG 的 {@code WithUserID} / {@code WithArticleID} 并入本类）</b>：
 * RAG 侧把 B 的两个闭包工厂拆成两个<b>独立类</b>（{@code WithUserID} / {@code WithArticleID}）⇒
 * RAG-V1 把它们收成本类的<b>静态工厂方法</b>（{@link #withUserId(String)} / {@link #withArticleId(String)}）
 * ⇒ ⇒ <b>行为等价</b>（都产出同一个 {@code LogOption} 闭包），但<b>少两个类</b>。
 * <p><b>调用方写法不变</b>：因本类是 {@link FunctionalInterface} ⇒ 调用方仍可写 lambda
 * （{@code opts -> opts.setUserId("u1")}）⇒ 与 B 的闭包写法一一对应。
 * <p>落点依据：{@code docs/Mapping_Table/common.md} §1.5（第 171 行「N:1 · C 无独立文件」）。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/logger/LogOption.java}
 * ＋ {@code WithUserID.java} ＋ {@code WithArticleID.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
@FunctionalInterface
public interface LogOption {

    /**
     * 对应 B 侧版 {@code func(opt *LogOptions) { opts.UserID = userID }} 这种闭包体。
     *
     * @param opts 选项容器（B 侧版 *LogOptions → Java 的 LogOptions 实例引用）
     */
    void apply(LogOptions opts);

    /**
     * <b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/logger/WithUserID.java:35}
     * （{@code func WithUserID(userID string) LogOption} ＋ 闭包体 {@code func(opts *LogOptions) { opts.UserID = userID }}`）
     * ⇒ <b>语义等价</b>：B 侧 返回闭包 / Java 返回 {@link LogOption} 实现 ⇒ 调用点写法不同、行为一致。
     *
     * <pre>
     * func WithUserID(userID string) LogOption {
     *     return func(opts *LogOptions) { opts.UserID = userID }
     * }
     * </pre>
     *
     * <p>对应 RAG 的 {@code WithUserID} 类 ⇒ 本方法即其 {@code of(userId)} 静态工厂。
     */
    static LogOption withUserId(String userId) {
        return opts -> opts.setUserId(userId);
    }

    /**
     * <b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/logger/WithArticleID.java:28}
     * （{@code func WithArticleID(articleID string) LogOption} ＋ 闭包体 {@code func(opts *LogOptions) { opts.ArticleID = articleID }}`）
     * ⇒ <b>语义等价</b>（同 {@link #withUserId(String)}）。
     *
     * <pre>
     * func WithArticleID(articleID string) LogOption {
     *     return func(opts *LogOptions) { opts.ArticleID = articleID }
     * }
     * </pre>
     *
     * <p>对应 RAG 的 {@code WithArticleID} 类 ⇒ 本方法即其 {@code of(articleId)} 静态工厂。
     */
    static LogOption withArticleId(String articleId) {
        return opts -> opts.setArticleId(articleId);
    }
}
