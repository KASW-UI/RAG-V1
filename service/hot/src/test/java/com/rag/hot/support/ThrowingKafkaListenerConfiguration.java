package com.rag.hot.support;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p><b>「故意抛异常」的探针 listener</b> —— 用来<b>实测</b>容器的错误处理策略是不是
 * 「<b>零重试</b>」（B 侧的 {@code sess.MarkMessage(msg, "")} 语义）。
 *
 * <h2>🔴 为什么需要它（§3.3「机制必须验证实际生效」）</h2>
 * <p>hot 域的 {@code HotEventConsumer} <b>永不外抛</b> ⇒ 容器级错误处理器<b>根本不会被触发</b>
 * ⇒ ⇒ 只用真实的 hot consumer 去测「零重试」，得到的是一个<b>恒绿</b>的断言
 * （把 {@code FixedBackOff(0,0)} 改成 {@code FixedBackOff(0,9)} 它照样绿）
 * ⇒ ⇒ 那是<b>假绿</b>。
 * <p>⇒ 本探针 listener <b>每次收到消息都抛异常</b> ⇒ 把容器逼进错误处理路径
 * ⇒ ⇒ 断言「这条记录被投递了<b>恰好 1 次</b>」就<b>有牙齿</b>：
 * <ul>
 *   <li>零重试（{@code FixedBackOff(0,0)}）⇒ 投递 <b>1</b> 次；</li>
 *   <li>Boot 默认（{@code FixedBackOff(0,9)}）⇒ 投递 <b>10</b> 次（1 + 9 次重试）。</li>
 * </ul>
 *
 * <h2>为什么它用<b>独立 topic + 独立 group</b></h2>
 * <p>必须与 hot 的主消费面<b>完全隔离</b>：
 * <ul>
 *   <li>独立 group ⇒ 不会与 {@code article-hot-group} 抢分区
 *       （同 group 同 topic 时两个 listener 会瓜分分区 ⇒ 主消费面的断言会被搅乱）；</li>
 *   <li>独立 topic ⇒ 探针的坏消息不会出现在主消费面的话题里。</li>
 * </ul>
 * <p>⚠️ 但它<b>共用同一个容器工厂</b>（{@code hotKafkaListenerContainerFactory}）
 * ⇒ ⇒ 断言的正是<b>生产那一套装配</b>上的错误处理策略（不是另起一个工厂自证自明）。
 *
 * <h2>为什么它是<b>顶层类</b>、listener 挂在 {@code @Bean} 上</h2>
 * <p>① Boot 3.4 起嵌套 {@code @TestConfiguration} 默认<b>不再被自动发现</b>
 * （{@code spring.test.nested-test-configuration} 默认 {@code INHERIT}）
 * ⇒ ⇒ 顶层 {@code @TestConfiguration} ＋ 在 {@code classes = {...}} 里<b>显式点名</b>；</p>
 * <p>② listener 挂在 {@code @Bean} 返回的<b>普通对象</b>上（而不是挂在配置类本身）
 * ⇒ ⇒ 避开「配置类被 CGLIB 代理后 {@code @KafkaListener} 方法是否仍被识别」的疑虑。</p>
 *
 * <p>⚠️ 本类只服务于<b>测试</b>：它<b>不</b>改变生产代码路径。
 */
@TestConfiguration(proxyBeanMethods = false)
public class ThrowingKafkaListenerConfiguration {

    /** 探针 topic（独立于主消费面）。 */
    public static final String TOPIC = "hot-test-drop-topic";

    /** 探针消费组（独立于主消费面）。 */
    public static final String GROUP = "hot-test-drop-group";

    /** 投递次数计数器。 */
    private static final AtomicInteger DELIVERIES = new AtomicInteger();

    /**
     * 注册探针 listener。
     *
     * @return 探针 listener 实例
     */
    @Bean
    public ProbeListener probeListener() {
        return new ProbeListener();
    }

    /** 清零投递计数（每个用例前调用）。 */
    public static void reset() {
        DELIVERIES.set(0);
    }

    /**
     * 当前投递次数。
     *
     * @return 投递次数
     */
    public static int deliveries() {
        return DELIVERIES.get();
    }

    /**
     * <p><b>探针 listener</b> —— 收到消息就抛异常，把容器逼进错误处理路径。
     */
    public static final class ProbeListener {

        /**
         * 每次被投递都抛异常 ⇒ 触发容器的 {@code CommonErrorHandler}。
         *
         * @param record Kafka 记录
         */
        @KafkaListener(topics = TOPIC, groupId = GROUP,
                containerFactory = "hotKafkaListenerContainerFactory")
        public void onMessage(ConsumerRecord<String, String> record) {
            DELIVERIES.incrementAndGet();
            throw new IllegalStateException(
                    "intentional failure: probe the container's zero-retry contract");
        }
    }
}
