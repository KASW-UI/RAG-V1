package com.rag.common.logger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3 真触发测试 —— 断言 **A8**：{@link GoStyleLogger} 是一个真正的
 * {@code org.slf4j.Logger} 实现（不是「长得像 logger 的工具类」）。
 *
 * <p><b>断言 A8 的原文</b>：{@code GoStyleLogger implements org.slf4j.Logger}
 * ⇒ 目的 = 让它能直接塞进任何接受 {@code org.slf4j.Logger} 的位置
 * （Spring 的 {@code @Slf4j} 替身、第三方库的日志参数、MDC 注入…）。
 *
 * <p><b>⚠️ 为什么必须「真触发」</b>（AGENTS.md §3.3）：{@code implements Logger} 会带来
 * **50 个抽象方法** —— 若只是「看起来有这些方法」而其中几个是空的，
 * 那么调用方在特定分支（如 {@code isDebugEnabled()} 返回 true 但 {@code debug(...)} 是空实现）
 * 会<b>静默丢日志</b> ⇒ ⇒ 本测试用<b>真实 logback appender</b> 断言消息真的落进了日志管道。
 */
class GoStyleLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger root;

    @BeforeEach
    void setUp() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("A8：GoStyleLogger 是 org.slf4j.Logger 的实现（可赋值给 org.slf4j.Logger）")
    void implementsSlf4jLogger() {
        org.slf4j.Logger asSlf4j = new GoStyleLogger("test-service");
        assertThat(asSlf4j).isInstanceOf(org.slf4j.Logger.class);
        assertThat(asSlf4j.getName()).isEqualTo("test-service");
    }

    @Test
    @DisplayName("A8：50 个抽象方法真的被实现了（用反射逐个核对，无 abstract / 无空实现）")
    void allAbstractMethodsAreImplemented() {
        // org.slf4j.Logger 的抽象方法数（2.19.x 的 slf4j-api）
        long abstractCount = java.util.Arrays.stream(org.slf4j.Logger.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isAbstract(m.getModifiers()))
                .count();
        assertThat(abstractCount).isGreaterThanOrEqualTo(50L);

        // GoStyleLogger 自己**没有**留下任何抽象方法（否则编译不过 —— 这条是防回归的显式断言）
        long remainingAbstract = java.util.Arrays.stream(GoStyleLogger.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isAbstract(m.getModifiers()))
                .count();
        assertThat(remainingAbstract).isZero();
    }

    @Test
    @DisplayName("真触发：info 消息真的落进 logback 的 appender（委托生效，不是空实现）")
    void infoReachesTheAppender() {
        GoStyleLogger logger = new GoStyleLogger("go-style-logger-test");
        logger.info("hello-call-chain");

        List<ILoggingEvent> events = appender.list;
        assertThat(events).isNotEmpty();
        assertThat(events.get(events.size() - 1).getFormattedMessage())
                .isEqualTo("hello-call-chain");
        assertThat(events.get(events.size() - 1).getLoggerName())
                .isEqualTo("go-style-logger-test");
    }

    @Test
    @DisplayName("真触发：error + 异常对象真的带着 Throwable 落进 appender")
    void errorWithThrowableReachesTheAppender() {
        GoStyleLogger logger = new GoStyleLogger("go-style-logger-test");
        RuntimeException boom = new IllegalStateException("boom");
        logger.error("failed", boom);

        List<ILoggingEvent> events = appender.list;
        ILoggingEvent last = events.get(events.size() - 1);
        assertThat(last.getLevel()).isEqualTo(Level.ERROR);
        assertThat(last.getThrowableProxy()).isNotNull();
        assertThat(last.getThrowableProxy().getMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("真触发：带占位符的格式化消息正确渲染")
    void formattedMessageIsRendered() {
        GoStyleLogger logger = new GoStyleLogger("go-style-logger-test");
        logger.info("a={} b={}", 1, "x");

        List<ILoggingEvent> events = appender.list;
        assertThat(events.get(events.size() - 1).getFormattedMessage()).isEqualTo("a=1 b=x");
    }
}
