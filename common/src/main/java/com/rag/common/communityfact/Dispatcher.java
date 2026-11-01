package com.rag.common.communityfact;

import io.opentelemetry.context.Context;
import org.slf4j.Logger;

/**
 * <p>对应 B 侧 {@code service/common/src/main/java/com/rag/common/communityfact/Dispatcher.java} 的
 * {@code Dispatcher} 接口（{@code dispatchFactOnce}）。
 *
 * <p><b>⚠️ 这是域侧 store SPI —— 由 comment / favorite / like 各域实现</b>
 * （映射表 §1.2 第 144 行：「<b>域侧 store SPI</b> —— 由 comment / favorite / like 各域实现」）。
 * ⇒ ⇒ 共享层<b>只给接口</b>，不给实现；「从 outbox 取一条 frozen event」是<b>各域自己的</b>存储语义。
 *
 * <p><b>⚠️ 拆出后的落点差异</b>：RAG 侧本接口是 {@code Dispatcher.DispatcherImpl}
 * （{@code Dispatcher.java} 的<b>嵌套</b>类型）⇒ ⇒ RAG-V1 落成本文件的<b>顶层</b>接口
 * ⇒ ⇒ 调用点由 {@code Dispatcher.DispatcherImpl} 改为 {@code Dispatcher} ⇒ **import 要同步改**。
 *
 * <p><b>行为契约</b>：返回 {@code true} 表示已发送一条；{@code false} 表示 outbox 空（停止内层循环）。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/communityfact/Dispatcher.java}
 * 的嵌套接口 {@code DispatcherImpl}（1:N 拆分 · 映射表 §1.2 第 144 行 / 决策锁 L-7 第 5 条：
 * 「{@code Dispatcher} 接口（域侧 SPI）留在 {@code Dispatcher.java}」）。
 */
@FunctionalInterface
public interface Dispatcher {

    /**
     * @param ctx otel context
     * @param sender Sender（RAG-V1：{@link EventSender}）
     * @param logger 业务 Logger
     * @return {@code true} 表示已发送一条；{@code false} 表示 outbox 空（停止内层循环）
     */
    boolean dispatchFactOnce(Context ctx, EventSender sender, Logger logger) throws Exception;
}
