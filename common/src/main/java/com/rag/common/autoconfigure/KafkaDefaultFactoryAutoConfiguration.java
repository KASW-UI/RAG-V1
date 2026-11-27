package com.rag.common.autoconfigure;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

/**
 * <p><b>移除 Boot 自动配置的「默认 Kafka 容器工厂」</b> ——
 * 让上下文里<b>只剩各域自建的</b> {@link ConcurrentKafkaListenerContainerFactory}。
 *
 * <h2>为什么需要它（5 进程形态 · S1 实测踩中）</h2>
 * <p>Boot 的自动配置类 {@code KafkaAnnotationDrivenConfiguration} 提供一个
 * <b>默认</b>的 {@code ConcurrentKafkaListenerContainerFactory}
 * （<b>bean 名 = {@code kafkaListenerContainerFactory}</b>）。
 * <p>⚠️ <b>它带的是 Boot 的默认错误处理器</b>（{@code DefaultErrorHandler} + 默认重试语义）
 * ⇒ 与本仓各域的契约<b>方向相反</b>：
 * <ul>
 *   <li><b>article / hot</b>：B 侧语义 = {@code ForceCommit=true}（失败也提交位点、消息丢弃）；</li>
 *   <li><b>comment / like / task</b>：B 侧语义 = 「<b>落库失败 ⇒ 不提交位点 ⇒ 重投</b>」。</li>
 * </ul>
 * ⇒ ⇒ 这个默认 factory 一旦被某个 listener 用上，<b>位点提交契约就静默翻转</b>。
 *
 * <h3>🔴 为什么「改个名」不够（实测根因链）</h3>
 * <p>Boot 的默认 factory 带的是
 * {@code @ConditionalOnMissingBean(name = ["kafkaListenerContainerFactory"])} ——
 * <b>按名字</b>判空，<b>不是</b>按类型。
 * <p>⇒ ⇒ <b>各域的 factory 只要不叫这个名字，Boot 的默认 factory 就会被创建</b>。
 * <p>⚠️ <b>5 进程形态下的连锁反应</b>：
 * <ol>
 *   <li>10 个域合并进 5 个进程 ⇒ 各域的 factory <b>同名会撞车</b>
 *       （{@code BeanDefinitionOverrideException}）⇒ 必须<b>加域前缀</b>改名；</li>
 *   <li>改名 ⇒ Boot 的按名字判空<b>不再命中</b> ⇒ <b>Boot 的默认 factory 冒出来了</b>；</li>
 *   <li>上下文里于是有 <b>N 个域 factory + 1 个 Boot 默认 factory</b>
 *       ⇒ ⇒ <b>任何按类型注入 / {@code getBean(工厂类型)} 都变成歧义</b>
 *       ⇒ ⇒ 实测报错：{@code NoUniqueBeanDefinition: ... expected single matching bean
 *       but found 2: commentKafkaListenerContainerFactory, kafkaListenerContainerFactory}。</li>
 * </ol>
 *
 * <h3>🔴 为什么不用「域 factory 挂 {@code @ConditionalOnMissingBean(name=...)}」</h3>
 * <p>因为那是<b>按名字</b>判空：{@code interaction} 进程装了 <b>comment + like</b> 两个域
 * ⇒ ⇒ 两个域的 factory 都带同一个条件 ⇒ ⇒ <b>只有一个能注册</b>
 * ⇒ ⇒ 另一个域的 {@code @KafkaListener(containerFactory = "...")} <b>找不到 bean</b>
 * ⇒ ⇒ 启动即失败。<br>
 * ⇒ ⇒ 所以判空必须做成「<b>把 Boot 那一个删掉</b>」，而不是「<b>让域的互相排斥</b>」。
 *
 * <h3>本类怎么做到「删掉」</h3>
 * <p>注册一个 {@link BeanDefinitionRegistryPostProcessor}：它在
 * <b>所有 {@code @Bean} 方法（含自动配置的）都注册完之后</b>、
 * <b>但任何 bean 被实例化之前</b>运行
 * ⇒ ⇒ 此刻 {@code kafkaListenerContainerFactory} 的 bean 定义若存在 ⇒ <b>移除它</b>。
 * <p>⚠️ <b>为什么不用 {@code spring.autoconfigure.exclude}</b>：
 * 那会把整个 {@code KafkaAnnotationDrivenConfiguration} 排除掉
 * ⇒ ⇒ {@code ConcurrentKafkaListenerContainerFactoryConfigurer} 也一起没了
 * ⇒ ⇒ <b>{@code hot} 域</b>的 factory 依赖它（{@code configurer.configure(factory, consumerFactory)}）
 * ⇒ ⇒ <b>hot 域启动即失败</b>（{@code NoSuchBeanDefinition}）
 * ⇒ ⇒ 只能<b>精准删一个 bean 定义</b>，不能整体排除。
 *
 * <h2>为什么归共享层（AGENTS.md §2.3 · R1）</h2>
 * <p>本能力<b>无域归属</b>：它对<b>全部 10 个域</b>一视同仁（任何域只要自建了 factory，
 * Boot 的默认那个就不该存在）⇒ ⇒ 属「跨域 + 改 {@code common/**} 的搁置漂移」这一类
 * ⇒ ⇒ 归共享层（而不是某个域）。
 *
 * <h2>L4 差异（AGENTS.md §3.2 三段式）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧是 <b>10 个独立进程</b>，每个进程里
 *       <b>只有本域一个</b> factory，且 B 侧<b>没有</b> Spring Boot 的自动配置
 *       ⇒ ⇒ B 侧<b>不存在</b>「同名撞车」与「Boot 默认 factory 冒出」这两个问题
 *       ⇒ ⇒ 本类是 <b>5 进程形态特有的收口</b>。</li>
 *   <li><b>语义还一样吗</b>：一样。删掉的是「<b>没人用的</b>」那个 bean ——
 *       ① 各域的 listener 都<b>显式点名</b>了 {@code containerFactory="<域前缀>..."}
 *       ⇒ ⇒ 本类删掉 Boot 默认 factory 后，<b>没有任何 listener 的行为发生变化</b>；
 *       ② 各域 factory 的配置（错误处理器 / ack 模式 / 并发度）<b>一字未动</b>
 *       ⇒ ⇒ 与 B 侧的位点提交契约<b>逐条一致</b>。</li>
 *   <li><b>B 出处</b>：B 侧各域的 factory 定义（B 侧 {@code *RpcApplication} 里的
 *       {@code kafkaListenerContainerFactory} 方法体，本仓逐字照抄，见各域启动类）
 *       ＋ Spring Boot 3.5 {@code spring-boot-autoconfigure} 的
 *       {@code KafkaAnnotationDrivenConfiguration}（本类的删除对象）。</li>
 * </ol>
 */
@AutoConfiguration
@ConditionalOnClass(ConcurrentKafkaListenerContainerFactory.class)
public class KafkaDefaultFactoryAutoConfiguration {

    /**
     * Boot 自动配置注册的默认工厂的 <b>bean 名</b>。
     *
     * <p>⚠️ <b>这是独立字面量 oracle</b>：Boot 的
     * {@code KafkaAnnotationDrivenConfiguration#kafkaListenerContainerFactory} 方法名
     * 就是 {@code kafkaListenerContainerFactory}
     * ⇒ ⇒ <b>Spring 的默认 bean 名 = 方法名</b> ⇒ 就是这个字符串。
     *
     * <p>（实测证据：{@code javap -v} 反编译
     * {@code spring-boot-autoconfigure-3.5.11.jar} 的
     * {@code KafkaAnnotationDrivenConfiguration.class} ⇒ 方法
     * {@code kafkaListenerContainerFactory(...)} ��
     * {@code @ConditionalOnMissingBean(name = ["kafkaListenerContainerFactory"])}。）
     */
    static final String BOOT_DEFAULT_FACTORY_BEAN_NAME = "kafkaListenerContainerFactory";

    /**
     * 移除 Boot 自动配置的默认 Kafka 容器工厂。
     *
     * <p>⚠️ <b>为什么用 {@link BeanDefinitionRegistryPostProcessor} 而不是
     * {@code BeanFactoryPostProcessor}</b>：前者拿到的是<b>可写</b>的
     * {@link BeanDefinitionRegistry} ⇒ 才能 {@code removeBeanDefinition}；
     * 后者只拿到 {@code ConfigurableListableBeanFactory}
     * （{@code BeanDefinitionRegistry} 只是它的可选子接口）⇒ 类型不稳。
     *
     * <p>⚠️ <b>执行时机</b>：{@code BeanDefinitionRegistryPostProcessor} 跑在
     * <b>所有</b> {@code @Bean} 方法（含自动配置的）注册完毕之后 ⇒
     * ⇒ 此刻 Boot 的默认 factory 定义若存在 ⇒ 一定删得掉。
     *
     * <p>⚠️ <b>为什么方法签名里没有 {@code BeanDefinitionRegistry} 参数</b>：
     * {@code BeanDefinitionRegistry} <b>不是</b>一个可注入的 bean
     * （它是容器内部的注册表，不是 bean 定义里的一个 bean）
     * ⇒ ⇒ 实测报错：{@code NoSuchBeanDefinitionException: No qualifying bean of type
     * 'BeanDefinitionRegistry' available}
     * ⇒ ⇒ 正确写法 = <b>直接实现 {@code BeanDefinitionRegistryPostProcessor}</b>，
     * 由框架把注册表<b>作为方法参数</b>传进来。
     *
     * @return bean 定义注册表后处理器
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor removeBootDefaultKafkaListenerContainerFactory() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry beanRegistry)
                    throws BeansException {
                if (beanRegistry.containsBeanDefinition(BOOT_DEFAULT_FACTORY_BEAN_NAME)) {
                    beanRegistry.removeBeanDefinition(BOOT_DEFAULT_FACTORY_BEAN_NAME);
                }
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
                    throws BeansException {
                // 本类只处理 bean **定义** ⇒ 不需要改 bean 工厂本身。
            }
        };
    }
}
