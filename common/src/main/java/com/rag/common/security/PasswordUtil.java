package com.rag.common.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * bcrypt 口令散列工具。
 *
 * <p>Go 出处：{@code service/user/common/cryptx/password.go}
 * <pre>
 * func CheckPassword(dbHash, inputPassword string) bool {
 *     err := bcrypt.CompareHashAndPassword([]byte(dbHash), []byte(inputPassword))
 *     return err == nil          ← 任何 err 都返回 false
 * }
 * </pre>
 *
 * <p><b>DRIFT-004（已修，2026-10-21 用户签字）</b>：Go 把
 * 「密码不匹配」与「hash 本身非法」<b>彻底压成同一个 false</b>，
 * 两者都表现为登录失败 {@code 1002 密码错}。
 * RAG 修复前 {@code BCryptPasswordEncoder.matches} 对部分畸形 hash 会抛
 * {@code IllegalArgumentException} ⇒ 一路冒泡成 {@code 500 + 5001}，
 * 把「数据损坏」报成「服务端故障」。
 *
 * <p><b>实测行为谱（Spring Security 7.0.7 + spring-security-crypto，非推断）</b>：
 * <pre>
 *   输入形态                          RAG 修复前        Go        修复后
 *   合法 hash + 密码匹配              true              true      true
 *   合法 hash + 密码不匹配            false             false     false
 *   空串 hash                         false             false     false
 *   "abc"（完全不是 bcrypt）          false             false     false
 *   "$2a$10$tooshort"（太短）        false             false     false
 *   "$2a$99$…"（cost 越界）          **抛异常 → 500**  false     **false**
 *   "$2b$" / "$2y$" 合法 hash        true              true      true
 *   null hash / null 明文             false             false     false
 * </pre>
 * 即 <b>触发面比原留档窄得多</b>：只有 <b>cost 字段被改坏</b>才抛异常，
 * 空串 / 非 bcrypt / 截断这些形态 Spring 早就返回 false、与 Go 一致。
 * Spring 的 {@code BCRYPT_PATTERN} 是 {@code \A\$2(a|y|b)?\$(\d\d)\$[./0-9A-Za-z]{53}}，
 * 已覆盖 {@code $2$}/{@code $2a$}/{@code $2b$}/{@code $2y$} 四种前缀，
 * 与 Go {@code decodeVersion} 接受的范围相同 —— 「RAG 不认 $2b$」的担心<b>不成立</b>。
 *
 * <p><b>刻意保留的可观测性</b>：对外行为与 Go 逐字一致（都报密码错），
 * 但调用方可以用 {@link #checkDetailed} 拿到 {@link Outcome#MALFORMED_HASH}
 * 并自行打点 —— 这是 §1「行为以 Go 为准、表达以 Spring 为准」的直接应用：
 * <b>错误码属行为必须对齐，指标属表达可以比 Go 更强</b>。Go 侧这一路完全没有信号。
 */
public final class PasswordUtil {

    private static final int STRENGTH = 10;
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(STRENGTH);

    private PasswordUtil() {
    }

    /**
     * 口令比对结果。
     *
     * <p>三者对 Go 而言<b>都等价于 {@code false}</b>；区分只是为了可观测性。
     */
    public enum Outcome {
        /** 密码匹配。 */
        MATCHED,
        /** hash 结构合法，但密码不匹配 —— 这是绝大多数失败的原因。 */
        MISMATCHED,
        /**
         * hash 本身非法（实测触发形态：cost 字段越界，如 {@code $2a$99$...}）。
         *
         * <p>Go 把它与 {@link #MISMATCHED} 同样报成密码错；
         * RAG 对外也这么做，但会额外留信号。
         */
        MALFORMED_HASH
    }

    public static String encrypt(String rawPassword) {
        return ENCODER.encode(rawPassword);
    }

    /**
     * 与 Go {@code CheckPassword} 等价：<b>密码匹配返回 true，其余一律 false，绝不抛异常</b>。
     *
     * <p>修复前本方法对畸形 hash 抛 {@code IllegalArgumentException}（DRIFT-004）。
     * 需要区分「密码错」与「hash 损坏」的调用方请改用 {@link #checkDetailed}。
     */
    public static boolean check(String rawPassword, String dbHash) {
        return checkDetailed(rawPassword, dbHash) == Outcome.MATCHED;
    }

    /**
     * 带原因的版本，供调用方补可观测性信号。
     *
     * <p>捕获 {@link RuntimeException} 而不是只捕获
     * {@code IllegalArgumentException}：Spring 的
     * {@code BCryptPasswordEncoder} 在不同版本、不同形态下可能抛
     * {@code IllegalArgumentException} 或其子类（如
     * {@code IllegalArgumentException: Bad number of rounds}）。
     * <b>契约是「任何 bcrypt 侧异常都等价于不匹配」</b>，
     * 与 Go {@code err == nil} 的写法逐条同构 —— 额外抛出的异常类型不该
     * 成为攻击面或运维噪音。
     */
    public static Outcome checkDetailed(String rawPassword, String dbHash) {
        try {
            return ENCODER.matches(rawPassword, dbHash) ? Outcome.MATCHED : Outcome.MISMATCHED;
        } catch (RuntimeException e) {
            return Outcome.MALFORMED_HASH;
        }
    }
}
