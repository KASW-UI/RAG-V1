package com.rag.common.logger;

/**
 * <p><b>Go 出处</b>：{@code service/common/logger/logger.go:26-29}
 * （{@code type LogOptions struct { UserID string; ArticleID string }}`）⇒ 两字段逐条对位 ⇒ <b>语义等价</b>。
 * 对应 Go 版 service/common/logger/logger.go 的 LogOptions struct：
 *
 * <pre>
 * type LogOptions struct {
 *     UserID    string
 *     ArticleID string
 * }
 * </pre>
 *
 * <p>该类是函数选项模式（Functional Options）的"载体"——多个 {@link LogOption} 共同修改它。
 * 比 Go 版多了一个无参构造的便利（Go 是零值即可用）。
 */
public class LogOptions {

    /**
     * 对应 Go 版字段 {@code UserID string}。
     * 默认值空字符串，等价于 Go 的零值。
     */
    private String userId = "";

    /**
     * 对应 Go 版字段 {@code ArticleID string}。
     * 默认值空字符串，等价于 Go 的零值。
     */
    private String articleId = "";

    /**
     * 对应 Go 版字段 {@code UserID string}。
     */
    public String getUserId() {
        return userId;
    }

    /**
     * 对应 Go 版字段 {@code UserID string}。
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 对应 Go 版字段 {@code ArticleID string}。
     */
    public String getArticleId() {
        return articleId;
    }

    /**
     * 对应 Go 版字段 {@code ArticleID string}。
      *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/logger/LogOptions.java}
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
*/
    public void setArticleId(String articleId) {
        this.articleId = articleId;
    }
}