package com.rag.common.redis;

import com.rag.common.protection.ProtectionProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * <b>把「Redis 请求期限感知」接到各域的 {@code RedisTemplate} / {@code StringRedisTemplate} 上。</b>
 *
 * <h2>为什么是 {@link BeanPostProcessor}（而不是让各域换注入点）</h2>
 * <p>各域注入的是 {@code StringRedisTemplate} / {@code RedisTemplate}
 * ⇒ 若要「域侧零改动」生效，就只能<b>把这些 Bean 背后的 connection factory 换掉</b>
 * ⇒ ⇒ 用 {@link BeanPostProcessor} 在 Bean 初始化后调
 * {@link RedisTemplate#setConnectionFactory(RedisConnectionFactory)}
 * ⇒ ⇒ <b>域侧一行都不用改</b>（形态①）。
 *
 * <h2>⚠️ 为什么用字符串形式的 {@code @ConditionalOnClass}</h2>
 * <p>本类的方法签名引用了 {@code RedisTemplate} ⇒ 若 Redis 不在 classpath，
 * <b>加载本类就会失败</b>（{@code NoClassDefFoundError}）⇒ 条件必须写成字符串形式，
 * 让 Spring 用 ASM 读元数据、<b>在加载本类之前</b>完成判定。
 *
 * <h2>装配面</h2>
 * <p>本类在 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 里登记 ⇒ ⇒ <b>凡是依赖 {@code service/common} 且用 Redis 的进程都自动生效</b>
 * ⇒ ⇒ 10 个 api ＋ 8 个用 Redis 的 rpc 组件<b>零改动</b>获得该能力。
 *
 * <h2>🔴 覆盖面边界（2026-10-30 G4 第六次订正 · 实测）</h2>
 * <p>⚠️ <b>本能力只覆盖「经 {@link RedisTemplate} 的路径」</b> ——
 * 本类换的是 <b>{@code RedisTemplate} 里的那个 factory 引用</b>
 * ⇒ ⇒ <b>不是把容器里的 {@code RedisConnectionFactory} Bean 换掉</b>
 * ⇒ ⇒ ⇒ <b>直接注入 {@code RedisConnectionFactory} 的代码拿到的仍是裸 factory</b>（实测：
 * 注入点是 {@code LettuceConnectionFactory}，不是包装版）。
 * <ul>
 *   <li><b>今天为什么还不是活 bug</b>：全仓 grep，域侧 <b>main</b> 代码对
 *       {@code RedisConnectionFactory} <b>零</b>直接注入（只有测试注入）⇒ ⇒ <b>潜在</b>缺口。</li>
 *   <li><b>但「10 个 api ＋ 8 个 rpc 组件零改动获得该能力」这句话本身超出实际覆盖面</b>
 *       ⇒ ⇒ 已改成「<b>经 RedisTemplate 的路径</b>」（本节）。</li>
 *   <li><b>若将来要覆盖「直接注入 factory」这条路</b> ⇒ ⇒ 需把包装器<b>也注册成 Bean</b>
 *       （并处理「既有注入点拿到的是哪个」的替换问题）⇒ ⇒ <b>本轮不做</b>（无活需求 ⇒ 不造无用抽象）。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(name = {
        "org.springframework.data.redis.core.RedisTemplate",
        "org.springframework.data.redis.connection.RedisConnectionFactory"
})
@EnableConfigurationProperties(ProtectionProperties.class)
public class DeadlineAutoConfiguration {

    /**
     * 把各 {@code RedisTemplate} 的 connection factory 换成预算感知版。
     *
     * <p>⚠️ <b>必须是 {@code static}</b>：否则这个 BeanPostProcessor 会过早实例化
     * {@link ProtectionProperties} ⇒ 触发「配置类还没准备好」的告警
     * ⇒ 而 {@code static} 的 {@code @Bean} 方法允许带参数注入 ⇒ 正好绕开这个问题。
     */
    @Bean
    static BeanPostProcessor goStyleRedisDeadlineBeanPostProcessor(ProtectionProperties props) {
        return new DeadlineBeanPostProcessor(props.getRedisDeadline().isEnabled());
    }

    /**
     * 把 {@code RedisTemplate} 的 connection factory 换成 {@link DeadlineConnectionFactory}。
     *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/redis/GoStyleRedisDeadlineAutoConfiguration.java}
 * （映射表 §1.12 第 280 行 · 1:1 · RAG 侧类名 {@code GoStyleRedisDeadlineAutoConfiguration}）
 * <p><b>⚠️ 落点差异</b>：<b>去 {@code Redis} 前缀</b> —— 包名已含 {@code redis}，类名再带前缀即冗余（命名债 P4-④ 同款处置）
 * ⇒ ⇒ **调用点要同步改 import 与类名**。
 * （L2 业务语义层以 RAG 为基准整段抄 · D13；本类<b>无</b> Jackson / Boot 版本相关写法 ⇒ 零回改）。
 */
    static final class DeadlineBeanPostProcessor implements BeanPostProcessor {

        private final boolean enabled;

        DeadlineBeanPostProcessor(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof RedisTemplate<?, ?> template)) {
                return bean;
            }
            RedisConnectionFactory current = template.getConnectionFactory();
            if (current == null || current instanceof DeadlineConnectionFactory) {
                // ① 没有 factory ⇒ 不管；② 已经是包装版 ⇒ 幂等（避免重复包装）
                return bean;
            }
            template.setConnectionFactory(new DeadlineConnectionFactory(current, enabled));
            return bean;
        }
    }
}
