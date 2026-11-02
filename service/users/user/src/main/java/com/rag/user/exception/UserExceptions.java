package com.rag.user.exception;

/**
 * <p><b>user 域业务异常 sentinel</b> —— 领域层抛出，gRPC 服务入口统一捕获后映射成 {@code io.grpc.Status}。
 *
 * <h2>B 出处</h2>
 * <pre>
 *   B: service/users/user/user-rpc/src/main/java/com/rag/users/user/rpc/exception/UserExceptions.java
 * </pre>
 *
 * <p><b>语义等价论证</b>：B 侧用 sentinel error ＋ {@code ==} 比较；Java 没有这个语义，
 * 用<b>异常子类 ＋ instanceof</b> 替换 ⇒ <b>表达层差异，行为等价</b>。
 *
 * <p><b>框架职责</b>：
 * <ul>
 *   <li>业务异常独立成包（不在 {@code domain/} 下）—— 体现「异常是跨切关注点，不是数据模型」；</li>
 *   <li>领域层捕获后映射到 gRPC code（NOT_FOUND / ALREADY_EXISTS / INVALID_ARGUMENT 等）；</li>
 *   <li>private 构造器禁止实例化（只能抛出，不能 new 当数据用）。</li>
 * </ul>
 */
public final class UserExceptions {

    private UserExceptions() {
    }

    /**
     * 对应 Go 端 {@code ErrorNotFound}：DataModel 查不到 user 时抛。
     *
     * <p>B 出处：{@code UserExceptions.java:24-26}
     */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 对应 Go 端 {@code ErrorAvatarHistoryNotFound}：selectAvatar 查不到 history 时抛。
     *
     * <p>B 出处：{@code UserExceptions.java:29-31}
     */
    public static class AvatarHistoryNotFoundException extends RuntimeException {
        public AvatarHistoryNotFoundException(String message) {
            super(message);
        }
    }
}
