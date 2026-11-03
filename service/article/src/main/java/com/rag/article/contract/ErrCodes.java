package com.rag.article.contract;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * <p><b>职责</b>：article 域的<b>错误码表</b> —— 码值本身（{@code int}）。
 *
 * <p><b>B 出处</b>：{@code service/article/article-common/src/main/java/com/rag/article/article/common/errmsg/ErrorCode.java}
 * （枚举常量块 {@code :78-176} · {@code getCode()} {@code :199-201} · {@code getMsg()} {@code :206-209} ·
 * {@code CODE_MAP} {@code :211-219} · {@code of(int)} {@code :226-228}）⇒ <b>整段照抄</b>。
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅 <b>包名</b>（{@code com.rag.article.article.common.errmsg} →
 *       {@code com.rag.article.contract}，D7 包压平）＋ <b>本段 javadoc</b>补上「为何拆成码表/文案表两件」。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 码值、回落语义（未知码 → {@code ERROR}）、文案逐字一致。</li>
 *   <li><b>B 出处</b>：见上方文件路径 + 行号。</li>
 * </ol>
 *
 * <h2>⚠️ 硬约束：错误契约<b>逐域隔离</b>，不可跨域统一</h2>
 * <p>B 侧实测（8 个域各不相同）：
 * <pre>
 *   同一语义的码值在各域<b>不同</b>：
 *     ErrorDbUpdate   favorite=5008  comment/like/user=5002  article=5002
 *     ErrorDbSelect   favorite=5007  comment/like/user=5003
 *     「用户不存在」段   favorite/follow/message=5101+   comment/like/user=1001+
 *
 *   同一语义的<b>文案与语言</b>在各域<b>不同</b>：
 *     CodeServerBusy   article/comment/favorite/like/user = "服务繁忙"（中文）
 *                       follow/hot/message                 = "service busy"（英文）
 * </pre>
 *
 * <p>⇒ <b>禁止</b>为了「统一」而把各域的错误码/文案合并成一个共享基座 —— 那会改掉 8 个域的可见行为（L1）。
 * <p>⇒ 本类的值必须<b>逐字照抄</b> B 侧 {@code ErrorCode.java}（含码值、含文案、含语言）。
 *
 * <h2>⚠️ 跨域码值撞车：数值可复用，含义绝不可跨域推断</h2>
 * <p>article 与 users / like 是<b>不同域但共用同一段 int 值空间</b>，且若干数值含义完全相反。
 * 这些码由 api 层直接透传进 HTTP body 的 {@code code} 字段，是<b>客户端可见</b>的：
 * <table border="1">
 *   <caption>数值相同、语义不同的码</caption>
 *   <tr><th>码值</th><th>article（本类）</th><th>users / like 中的含义</th></tr>
 *   <tr><td>1005</td><td>请先登录</td><td>users: TOKEN格式错误 · like: TOKEN格式错误</td></tr>
 *   <tr><td>1006</td><td>只能编辑或删除自己的文章</td><td>users: TOKEN已过期 · like: TOKEN已过期</td></tr>
 *   <tr><td>5004</td><td>文件上传失败</td><td>users: 数据库插入失败</td></tr>
 *   <tr><td>5005</td><td>文件删除失败</td><td>users: 事务操作失败</td></tr>
 *   <tr><td>5006</td><td>文件下载失败</td><td>users: 雪花ID生成失败</td></tr>
 * </table>
 * ⇒ <b>排查线上问题或写告警规则时，必须指明是哪个域</b>，不能因为「5005 就是事务失败」
 * 就跨域套用。
 *
 * <h2>为什么拆成「码值表」与「文案表」两个类</h2>
 * <p>B 侧是<b>一个枚举</b>（码值与文案同体）。本仓按 D5 + D11 拆成两个类：
 * {@code ErrCodes}（本类，持有码值 ＋ 逐条文案）＋ {@link ErrMsg}（{@code int → 文案} 的查表入口）。
 * ⇒ <b>拆分只改表达层</b>：码值与文案本身一字未动，回落语义（未知码 → {@code "FAIL"}）也逐字保留。
 */
public enum ErrCodes {

    // ===== 通用（B 侧 ErrorCode.java:81-101）=====
    /**
     * B 侧 {@code SUCCESS = 200}。
     *
     * <p>注意这是<b>信封里的 code 字段值</b>，不是 HTTP 状态码——
     * B 侧所有业务成败都走 {@code httpx.OkJson}，HTTP 恒 200。
     */
    SUCCESS(200, "OK"),

    /**
     * B 侧 {@code ERROR = 500}。同时是 {@link ErrMsg#getMsg(int)} 未知码的兜底文案 {@code "FAIL"}。
     *
     * <p>⚠️ 它有两个身份：既是「通用失败」这个码，也是查表未命中时的回落值。
     * 回落取的是 <b>500 号的 msg</b>（{@code "FAIL"}），不是空串、也不是把 code 转成字符串。
     */
    ERROR(500, "FAIL"),

    /**
     * B 侧 {@code CodeServerBusy = 1015}。
     *
     * <p>在 api 侧是 gRPC {@code default} 分支的落点（RPC 返回了非
     * NotFound/PermissionDenied/Internal 的任何码都归到这里），属于<b>兜底</b>而非具体故障类型。
     */
    CODE_SERVER_BUSY(1015, "服务繁忙"),

    // ===== 文章业务（B 侧 ErrorCode.java:103-113）=====
    /** B 侧 {@code ErrorArticleExist = 1001}。 */
    ERROR_ARTICLE_EXIST(1001, "文章已存在"),

    /** B 侧 {@code ErrorArticleNone = 1002}。也是 api 侧 {@code codes.NotFound} 的落点。 */
    ERROR_ARTICLE_NONE(1002, "文章不存在"),

    /** B 侧 {@code ErrorArticlePublishing = 1003}。 */
    ERROR_ARTICLE_PUBLISHING(1003, "文章处理中，请稍后查看"),

    /** B 侧 {@code ErrorArticlePublishFailed = 1004}。 */
    ERROR_ARTICLE_PUBLISH_FAILED(1004, "文章发布失败"),

    /**
     * B 侧 {@code ErrorUnauthorized = 1005}，文案「请先登录」。
     *
     * <p>⚠️ <b>这是 api 层的业务码，不是 HTTP 401</b>。B 侧
     * {@code extractCurrentUserID}（{@code logic/article/helper.go:19-34}）在
     * {@code ctx} 里取不到 userId 时返回 {@code ErrorUnauthorized}，
     * 该值随 logic 的 {@code (resp, code)} 双返回流到 handler，被包成
     * {@code {"code":1005,"msg":"请先登录"}} 且 <b>HTTP 状态码仍是 200</b>。
     *
     * <p>⇒ article api <b>不要</b>拿 {@code com.rag.common.exception.AuthenticationException}
     * 承接这个场景：那个类型的语义是「HTTP 401」，与 B 侧的 200 + 信封 1005 相反，
     * 套用会引入真实行为漂移（客户端从 200 变 401）。
     */
    ERROR_UNAUTHORIZED(1005, "请先登录"),

    /**
     * B 侧 {@code ErrorArticleForbidden = 1006}，文案「只能编辑或删除自己的文章」。
     *
     * <p>两个来源：① api 侧 {@code codes.PermissionDenied} 的落点；
     * ② <b>业务级 ownership 判定</b>——{@code get_author_article_logic.go:60-62} 与
     * {@code update_article_logic.go:65-67} 显式比对 {@code Article.AuthorId != 当前用户}。
     * 即：rpc 没拦住的越权，由 api 层用这个码兜住，二者不可互相省略。
     */
    ERROR_ARTICLE_FORBIDDEN(1006, "只能编辑或删除自己的文章"),

    // ===== 系统与基础设施（B 侧 ErrorCode.java:115-126）=====
    /** B 侧 {@code ErrorServerCommon = 5001}。也是 api 侧 {@code codes.Internal} 的落点。 */
    ERROR_SERVER_COMMON(5001, "系统内部错误"),

    /** B 侧 {@code ErrorDbUpdate = 5002}。 */
    ERROR_DB_UPDATE(5002, "更新数据库失败"),

    /** B 侧 {@code ErrorDbSelect = 5003}。 */
    ERROR_DB_SELECT(5003, "查询数据库失败"),

    /** B 侧 {@code ErrorMinioUpload = 5004}。⚠️ 跨域同名不同义，见类级说明。 */
    ERROR_MINIO_UPLOAD(5004, "文件上传失败"),

    /** B 侧 {@code ErrorMinioDelete = 5005}。⚠️ 跨域同名不同义，见类级说明。 */
    ERROR_MINIO_DELETE(5005, "文件删除失败"),

    /** B 侧 {@code ErrorMinioDownload = 5006}。⚠️ 跨域同名不同义，见类级说明。 */
    ERROR_MINIO_DOWNLOAD(5006, "文件下载失败");

    private final int code;
    private final String msg;

    ErrCodes(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    /**
     * 把枚举还原成 {@code int} —— 供 {@code new BusinessException(ec.getCode(), ec.getMsg())} 使用。
     *
     * <p>B 侧 {@code getCode()}（{@code ErrorCode.java:199-201}）⇒ 逐字照抄。
     */
    public int getCode() {
        return code;
    }

    /**
     * 本枚举对应的提示语（实例版）。
     *
     * <p>用于 {@code new BusinessException(ec.getCode(), ec.getMsg())} 这类
     * 「调用点已经知道自己要哪个码」的场景，省掉一次查表。
     *
     * <p>B 侧 {@code getMsg()}（{@code ErrorCode.java:206-209}）⇒ 逐字照抄。
     */
    public String getMsg() {
        return msg;
    }

    /** code → 枚举常量缓存，避免每次 {@code values()} 新建数组 + O(n) 遍历。 */
    private static final Map<Integer, ErrCodes> CODE_MAP;

    static {
        // HashMap 而非 EnumMap：这里是「错误码 int → 枚举」的反向索引，键是 Integer 不是枚举类型。
        Map<Integer, ErrCodes> map = new HashMap<>();
        for (ErrCodes ec : values()) {
            map.put(ec.code, ec);
        }
        CODE_MAP = Collections.unmodifiableMap(map);
    }

    /**
     * code → 枚举常量；<b>未命中返回 {@link #ERROR}</b>，不返回 {@code null}。
     *
     * <p>与 {@link ErrMsg#getMsg(int)} 同源的回落策略：B 侧只有一层回落（{@code GetErrMsg}），
     * 这里把回落后的文案也一并提供，免得调用点拿到 {@code null} 后再 NPE。
     *
     * <p>B 侧 {@code of(int)}（{@code ErrorCode.java:226-228}）⇒ 逐字照抄。
     */
    public static ErrCodes of(int code) {
        return CODE_MAP.getOrDefault(code, ERROR);
    }
}
