package com.rag.follow.mapper;

/**
 * 🔴 分页子句的 Go 复刻 —— 对应 GORM 的
 * <pre>{@code
 * if offset > 0 { db = db.Offset(int(offset)) }
 * if limit  > 0 { db = db.Limit(int(limit))  }
 * }</pre>
 *
 * <p><b>B 出处</b>：
 * {@code service/follow/follow-rpc/src/main/java/com/rag/follow/follow/rpc/mapper/GoStyleLimitOffset.java:54-83}
 * ⇒ 逐条一致（类形态 / 私有构造器 / 方法签名 / 分段条件 / 拼接顺序 / 空串返回）。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅<b>表达层</b> —— 包路径压平
 *       （B 侧 {@code com.rag.follow.follow.rpc.mapper} ⇒ 本仓 {@code com.rag.follow.mapper}，
 *       D7 域内单层包树，见 {@code docs/File_Tree/follow.md}）＋ 补 B 出处行号。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 方法体逐行相同 ⇒ 同样的片段、同样的空串、同样的顺序。</li>
 *   <li><b>B 出处</b>：见上。</li>
 * </ol>
 *
 * <h2>为什么不能直接拼 {@code "LIMIT n OFFSET m"}</h2>
 *
 * <p>Go 侧<b>两段各自有条件</b>：{@code offset <= 0} 时整段 OFFSET 被跳过，
 * {@code limit <= 0} 时整段 LIMIT 被跳过。无条件拼接会生成
 * {@code LIMIT 20 OFFSET -1}，Postgres 直接拒收：
 * <pre>ERROR:  OFFSET must not be negative</pre>
 *
 * <p>⇒ <b>参数非法被降级成基础设施故障</b>：DB 层异常会被翻成
 * {@code INTERNAL} + {@code "database query failed"}，客户端拿到 <b>5007</b>，
 * 而 Go 返回的是 <b>SUCCESS + 第一页</b>（因为 normalizeListReq 已把负 offset 归零，
 * 且 limit<=0 早就被替换成 defaultLimit）。监控侧也会把它记成 DB 故障，污染告警。
 *
 * <h2>Go 出处（6 处 + 推荐算法 2 处，逐条同构）</h2>
 * <ul>
 *   <li>{@code internal/model/follow.go:79-87} —— {@code ListFollowTargets}</li>
 *   <li>{@code internal/model/follow.go:102-110} —— {@code ListFollowerUsers}</li>
 *   <li>{@code internal/model/follow.go:125-133} —— {@code ListBlockTargets}</li>
 *   <li>{@code internal/model/follow.go:148-153} —— {@code ListAllFollowTargets}（只有 limit）</li>
 * </ul>
 * 推荐算法在 BFS 里对每个源用户调用 {@code ListAllFollowTargets}，
 * 所以这一处的语义被<b>放大</b>：limit<=0 会让每一层都全量拉取，
 * 一次推荐退化成 N 次无上限查询。
 *
 * <h2>⚠️ 边界：{@code limit <= 0} 时 Go <b>不报错、也不限制</b></h2>
 *
 * <p>GORM 不加 {@code LIMIT} 就没有上限 ⇒ 返回<b>全部行</b>。
 * 这看起来像缺陷，但它是 Go 的<b>实际行为</b>，本类照原样复刻
 * （AGENTS.md §3.2：不「顺手改好」Go 的缺陷）。
 * 正常路径上 {@code limit} 已被 {@code normalizeListReq} 保证 &gt; 0，
 * 这里保留分支只是为了与 Go 的控制流逐字对齐。
 *
 * <h2>为什么是 public</h2>
 *
 * <p>片段由<b>service 层</b>生成、再作为 {@code @Param("limitOffset")} 传给 mapper ——
 * MyBatis 的 {@code ${...}} 只能插值一个<b>已经算好的字符串</b>，
 * 因此「算」的动作不能藏在 mapper 里（mapper 的 SQL 是注解里的常量字符串）。
 * 所以本类虽住在 {@code mapper} 包，却必须对 service 包可见。
 *
 * <h2>安全性</h2>
 *
 * <p>{@code limit}/{@code offset} 都是 {@code int}，字符串拼接是<b>类型安全</b>的
 * ⇒ 不构成 SQL 注入面。但若哪天把签名改成 {@code String}，
 * 这一行就从「类型安全」变成「注入面」—— 改签名时必须同步改用占位符绑定。
 */
public final class GoStyleLimitOffset {

    private GoStyleLimitOffset() {
    }

    /**
     * 生成 Go 语义的 {@code LIMIT}/{@code OFFSET} 尾子句。
     *
     * @param limit  上限；{@code <= 0} 时<b>不输出 LIMIT</b>（Go 侧同样不加 ⇒ 不限行数）
     * @param offset 偏移；{@code <= 0} 时<b>不输出 OFFSET</b>（Go 侧同样不加 ⇒ 从头返回）
     * @return 形如 {@code "LIMIT 20 OFFSET 40"} 的片段；两段都被跳过时返回<b>空串</b>。
     *         调用方用 MyBatis 的 {@code ${...}} 插值放在 SQL 尾部，
     *         <b>空串是安全的</b>（只留空白，SQL 仍合法）；<b>不要</b>改用 {@code #{}} —— 那样会被
     *         当成绑定参数，生成 {@code LIMIT $1} 这种 PG 不认的语法。
     */
    public static String limitOffset(int limit, int offset) {
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
