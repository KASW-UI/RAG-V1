package com.rag.message.mapper;

/**
 * <p>🔴 <b>分页子句的 Go 复刻</b> —— 对应 GORM 的
 * <pre>{@code
 * if offset > 0 { db = db.Offset(int(offset)) }
 * if limit  > 0 { db = db.Limit(int(limit))  }
 * }</pre>
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/mapper/GoStylePagination.java}
 * （B 侧类 javadoc {@code :3-42} · 类声明与私有构造 {@code :43-46} · {@code limitOffset :48-69}）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.message.message.rpc.mapper} →
 *       {@code com.rag.message.mapper}，D7 包压平）⇒ <b>可见性也逐字保留</b>
 *       （包私有 final class ＋ 包私有 static 方法 + 私有构造器）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— <b>判定口径逐字照抄</b>：
 *       {@code limit > 0} 才输出 LIMIT、{@code offset > 0} 才输出 OFFSET、
 *       两段只出现其一时<b>不带前导空格</b>、两段都被跳过时返回<b>空串</b>。
 *       ⚠️ 该空串<b>不能</b>交给 {@code last()}（调用方先判 {@code isEmpty()}）。</li>
 *   <li><b>B 出处</b>：同上。</li>
 * </ol>
 *
 * <h2>为什么不能直接拼 {@code "LIMIT n OFFSET m"}</h2>
 *
 * <p>Go 侧<b>两段各自有条件</b>：{@code offset <= 0} 时<b>整段 OFFSET 被跳过</b>，
 * {@code limit <= 0} 时整段 LIMIT 被跳过。无条件拼接会生成
 * {@code LIMIT 20 OFFSET -1}，Postgres 直接拒收：
 * <pre>ERROR:  OFFSET must not be negative</pre>
 *
 * <p>⇒ <b>参数非法被降级成基础设施故障</b>：{@code MessageRpcServiceImpl} 的兜底会把
 * {@code BadSqlGrammarException} 翻成 {@code INTERNAL} + {@code "internal server error"}，
 * 客户端拿到 <b>5001</b>，而 Go 返回的是 <b>SUCCESS + 第一页</b>。
 * 监控侧也会把它记成 DB 故障，污染告警。
 *
 * <h2>Go 出处（三处，逐条一致）</h2>
 * <ul>
 *   <li>{@code internal/model/message.go:59-64} —— {@code ListNotifications}</li>
 *   <li>{@code internal/model/message.go:127-131} —— {@code ListConversations}</li>
 *   <li>{@code internal/model/message.go:164-168} —— {@code ListConversationMessages}</li>
 * </ul>
 * 实测三处<b>逐字同构</b>（消费点，不是 struct tag）。
 *
 * <h2>⚠️ 边界：{@code limit <= 0} 时 Go <b>不报错、也不限制</b></h2>
 *
 * <p>GORM 不加 {@code LIMIT} 就没有上限 ⇒ 返回<b>全部行</b>。
 * 这看起来像缺陷，但它是 Go 的<b>实际行为</b>，本类照原样复刻
 * （AGENTS.md §3.2：不「顺手改好」Go 的缺陷）。
 * 若日后 Go 侧补上默认值，再反向收敛这里。
 *
 * <h2>安全性：{@code limit}/{@code offset} 都是 {@code int}</h2>
 *
 * <p>参数是<b>原始 int</b>，字符串拼接是<b>类型安全</b>的
 * ⇒ <b>不构成 SQL 注入面</b>。但若哪天把签名改成 {@code String}，
 * 这一行就从「类型安全」变成「注入面」——改签名时必须同步改用占位符绑定。
 */
final class GoStylePagination {

    private GoStylePagination() {
    }

    /**
     * 生成 Go 语义的 {@code LIMIT}/{@code OFFSET} 尾子句。
     *
     * @param limit  上限；{@code <= 0} 时<b>不输出 LIMIT</b>（Go 侧同样不加 ⇒ 不限行数）
     * @param offset 偏移；{@code <= 0} 时<b>不输出 OFFSET</b>（Go 侧同样不加 ⇒ 从头返回）
     * @return 形如 {@code "LIMIT 20 OFFSET 40"} 的片段；两段都被跳过时返回<b>空串</b>
     *         （调用方<b>不要</b>把空串交给 {@code last()}）
     */
    static String limitOffset(int limit, int offset) {
        StringBuilder sb = new StringBuilder();
        if (limit > 0) {
            sb.append("LIMIT ").append(limit);
        }
        if (offset > 0) {
            // SQL 语法要求 LIMIT 在 OFFSET 之前；两者只出现其一时不能带前导空格。
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("OFFSET ").append(offset);
        }
        return sb.toString();
    }
}
