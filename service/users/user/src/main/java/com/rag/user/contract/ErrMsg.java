package com.rag.user.contract;

/**
 * <p><b>user 子域错误文案查询入口（域内私有视图）</b>。
 *
 * <h2>⚠️ 错误文案是 L1 契约</h2>
 * <p>文案（{@code msg}）与码值一样，是客户端可见面 ⇒ <b>一个字都不能改</b>
 * （AGENTS.md §八「L1 未变」· §三「L1 以 B 侧为准」）。
 *
 * <p><b>B 侧真源</b>：{@code service/users/users-common/src/main/java/com/rag/users/common/errmsg/ErrorCode.java}
 * 的 {@code CODE_MAP} ＋ {@code getMsg(int)}（未知码 ⇒ <b>回落 {@code ERROR}</b> ⇒ 文案 {@code "FAIL"}）。
 *
 * <p><b>C 侧落点</b>：文案的<b>唯一真源</b>在共享层 {@code com.rag.users.common.ErrMsg}
 * （{@code users-common} lane 于 2026-11-02 落地，31 条文案逐条对拍过 B 侧）。
 *
 * <p>⚠️ <b>本类只做转发，不持有任何文案字面量</b>：
 * <ul>
 *   <li>文案若在这里再抄一份 ⇒ 就是第二份真源 ⇒ 与共享层可能漂移，且共享层的
 *       {@code ErrContractParityTest} <b>测不到</b>它 ⇒ 漂移是静默的。</li>
 *   <li>转发 ⇒ 文案永远等于 B 侧那一份 ⇒ 漂移面为零。</li>
 * </ul>
 *
 * <p><b>回落语义（逐条保留）</b>：未知码 ⇒ {@link ErrCodes#ERROR}（500）的文案
 * （B 侧 {@code ErrorCode.of(int)} 的 {@code getOrDefault(code, ERROR)} 语义）。
 *
 * @see com.rag.users.common.ErrMsg 真源（31 条文案）
 */
public final class ErrMsg {

    private ErrMsg() {
    }

    /**
     * 取码值对应的文案。
     *
     * <p><b>B 出处</b>：{@code ErrorCode.java:93-95} 的 {@code getMsg(int)}
     * ＋ {@code :88-90} 的 {@code of(int)}（未知码 ⇒ 回落 {@code ERROR}）。
     *
     * @param code 业务码
     * @return 文案（未知码 ⇒ {@link ErrCodes#ERROR} 的文案，<b>不是 null</b>、<b>不抛异常</b>）
     */
    public static String getMsg(int code) {
        return com.rag.users.common.ErrMsg.getMsg(code);
    }
}
