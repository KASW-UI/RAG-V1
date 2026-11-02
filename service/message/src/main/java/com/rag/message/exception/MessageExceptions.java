package com.rag.message.exception;

/**
 * <p><b>message 域业务异常聚合</b> —— 4 个 unchecked 异常的容器类（自身不可实例化）。
 *
 * <p><b>B 出处</b>：{@code service/message/message-rpc/src/main/java/com/rag/message/message/rpc/exception/MessageExceptions.java}
 * <pre>{@code
 *   类声明 + 私有构造器（utility，禁止 new）   :16-20
 *   DbSelectException            （SELECT 失败） :22-29
 *   DbUpdateException            （INSERT/UPDATE 失败）:31-37
 *   TransactionRollbackException （事务回滚后上抛业务错误）:39-45
 *   DatabaseException            （兜底父类）    :47-53
 * }</pre>
 *
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：三处<b>纯表达层</b>改动 ——
 *       ① 包名 {@code com.rag.message.message.rpc.exception} → {@code com.rag.message.exception}
 *       （D7 包压平 · AGENTS.md §2.1：域内异常落 {@code exception/} 包）；
 *       ② 类 javadoc 里指向 {@code MessageDomainService} 的链接改指 D7 后的
 *       {@code com.rag.message.domain.service.MessageDomainService}（B 侧 :5 指向
 *       {@code com.rag.message.message.rpc.service.MessageDomainService}）；
 *       ③ 4 个内部异常类各补一个 {@code serialVersionUID = 1L}（<b>B 侧无</b>；本仓 C 侧异常类统一带，
 *       见 {@code common/src/main/java/com/rag/common/logger/FatalError.java:43}）
 *       ⇒ 纯序列化标识，不改异常文本 / 构造器 / cause 链等任何可观察行为。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 4 个内部类的<b>名字、可见性（public static）、父类
 *       （{@link RuntimeException}）、两个构造器的签名与调用 {@code super(...)} 的参数顺序</b>逐条未动
 *       ⇒ 抛出点、catch 点、异常文本、cause 链全部与 B 侧相同。</li>
 *   <li><b>B 出处</b>：同上（逐条行号）。</li>
 * </ol>
 *
 * <p><b>⚠️ 本类<b>不使用 Lombok</b></b>：4 个内部异常类各自手写两个构造器（B 侧 :27-28 / :35-36 /
 * :43-44 / :51-52）—— Lombok 的 {@code @NoArgsConstructor} / {@code @AllArgsConstructor}
 * 生成的是<b>无参</b>与<b>全参</b>两个构造器，与 B 侧「{@code (String)} 与 {@code (String, Throwable)}」
 * 的签名<b>不一致</b>（用 {@code @RequiredArgsConstructor} 只能生成后者）
 * ⇒ 改用 Lombok 会改变可用的构造器集合 ⇒ 保持手写。
 */
public final class MessageExceptions {

    private MessageExceptions() {
        // utility（B 侧 :18-20）
    }

    /**
     * 数据库 SELECT 失败 —— B 侧 {@code DbSelectException}（:26-29）。
     *
     * <p>调用方在 catch 后 {@code observeDbError(action, type)} 再上抛。
     *
     * @param msg   异常文本
     * @param cause 起因（可空）
     */
    public static class DbSelectException extends RuntimeException {

        /** 序列化版本号。 */
        private static final long serialVersionUID = 1L;

        /**
         * 单参构造器（B 侧 :27）。
         *
         * @param msg 异常文本
         */
        public DbSelectException(String msg) {
            super(msg);
        }

        /**
         * 双参构造器（B 侧 :28）。
         *
         * @param msg   异常文本
         * @param cause 起因
         */
        public DbSelectException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * 数据库 INSERT / UPDATE 失败 —— B 侧 {@code DbUpdateException}（:34-37）。
     *
     * @param msg   异常文本
     * @param cause 起因（可空）
     */
    public static class DbUpdateException extends RuntimeException {

        /** 序列化版本号。 */
        private static final long serialVersionUID = 1L;

        /**
         * 单参构造器（B 侧 :35）。
         *
         * @param msg 异常文本
         */
        public DbUpdateException(String msg) {
            super(msg);
        }

        /**
         * 双参构造器（B 侧 :36）。
         *
         * @param msg   异常文本
         * @param cause 起因
         */
        public DbUpdateException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * 事务回滚异常 —— 当事务中抛出业务错误、需要让外层感知时使用
     * —— B 侧 {@code TransactionRollbackException}（:42-45）。
     *
     * @param msg   异常文本
     * @param cause 起因（可空）
     */
    public static class TransactionRollbackException extends RuntimeException {

        /** 序列化版本号。 */
        private static final long serialVersionUID = 1L;

        /**
         * 单参构造器（B 侧 :43）。
         *
         * @param msg 异常文本
         */
        public TransactionRollbackException(String msg) {
            super(msg);
        }

        /**
         * 双参构造器（B 侧 :44）。
         *
         * @param msg   异常文本
         * @param cause 起因
         */
        public TransactionRollbackException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * 兜底父类 —— 在 {@code catch (Exception)} 路径上转抛 —— B 侧 {@code DatabaseException}（:50-53）。
     *
     * @param msg   异常文本
     * @param cause 起因（可空）
     */
    public static class DatabaseException extends RuntimeException {

        /** 序列化版本号。 */
        private static final long serialVersionUID = 1L;

        /**
         * 单参构造器（B 侧 :51）。
         *
         * @param msg 异常文本
         */
        public DatabaseException(String msg) {
            super(msg);
        }

        /**
         * 双参构造器（B 侧 :52）。
         *
         * @param msg   异常文本
         * @param cause 起因
         */
        public DatabaseException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
