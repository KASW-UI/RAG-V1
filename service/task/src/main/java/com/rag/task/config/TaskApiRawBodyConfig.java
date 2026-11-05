package com.rag.task.config;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>把 Spring 默认的 {@code RequestResponseBodyMethodProcessor} 换成
 * {@link GoStyleRawBodyArgumentProcessor}</b> —— 让 {@code @RequestBody} 一律按 JSON 读原始 body。</p>
 *
 * <h2>为什么必须换「处理器」而不是换「转换器」</h2>
 * <p>body 在转换器被调用<b>之前</b>就已经被 Spring 换掉了（消费点见
 * {@link GoStyleRawBodyArgumentProcessor} 的 javadoc）⇒ 转换器层根本拿不到原始 body。
 * 唯一能改的是「造出 {@code HttpInputMessage} 的那一环」，也就是
 * {@code RequestResponseBodyMethodProcessor#createInputMessage}。
 *
 * <h2>为什么用 BeanPostProcessor 而不是自定义 {@code HandlerMethodArgumentResolver}</h2>
 * {@code RequestMappingHandlerAdapter#getDefaultArgumentResolvers()}
 * 把<b>自定义</b> resolver 放在<b>所有</b>内置 resolver <b>之后</b>，
 * 而 {@code RequestResponseBodyMethodProcessor.supportsParameter()} 对任何带
 * {@code @RequestBody} 的参数都返回 {@code true}
 * ⇒ 自定义 resolver <b>永远轮不到</b>。
 * <p>⇒ 只能<b>就地替换</b>默认处理器那个实例。
 *
 * <h2>⚠️ 副作用面（必须知道再改）</h2>
 * <p>替换实例会丢掉 Spring 内部持有的 {@code RequestBodyAdvice} 链
 * （spring-webmvc 6.2.16 的 {@code RequestResponseBodyAdviceChain} 是 private 且
 * <b>没有 public getter</b> ⇒ 无法从 adapter 取回）。
 * ⇒ 本仓全量 {@code service/} 主代码里<b>没有任何</b> {@code RequestBodyAdvice} 实现
 * ⇒ 传 {@code null} 与 Spring 自己的装配<b>完全等价</b>。
 * <p>⚠️ 一旦将来有人加了 {@code RequestBodyAdvice}，这里会<b>启动即失败</b>（而不是静默丢掉）——
 * 见 {@link RawBodyProcessorRegistrar#postProcessAfterInitialization} 里的断言。
 *
 * <h2>B 出处（三段式 · AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>只改表达层</b> —
 *       <ul>
 *         <li><b>Spring 7 → Spring 6.2 的 API 面回改</b>（D14）：
 *             {@code getArgumentResolvers()} / {@code setArgumentResolvers()}
 *             在 spring-webmvc 6.2.16 里<b>仍然存在</b>（javap 实测）⇒ 逐字沿用；</li>
 *         <li>包名压平（{@code …/task/api/config} → {@code com.rag.task.config}，D7）。</li>
 *       </ul></li>
 *   <li><b>语义还一样吗</b>：<b>逐条等价</b> —— 替换点、断言、分块复制逻辑逐字照抄。</li>
 *   <li><b>B 出处</b>：
 *       <pre>
 * B 出处：service/task/task-api/src/main/java/com/rag/task/task/api/config/TaskApiRawBodyConfig.java:44-45（类声明）
 * B 出处：…/config/TaskApiRawBodyConfig.java:51-54（static BeanPostProcessor 工厂方法）
 * B 出处：…/config/TaskApiRawBodyConfig.java:60-63（内部类 + ListableBeanFactory）
 * B 出处：…/config/TaskApiRawBodyConfig.java:72-89（替换逻辑 + RequestBodyAdvice 断言）
 * B 出处：…/config/TaskApiRawBodyConfig.java:91-104（逐项替换 + 只读视图必须复制）
 *       </pre></li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class TaskApiRawBodyConfig {

    /**
     * ⚠️ 必须是 {@code static}：{@code BeanPostProcessor} 会被容器提前实例化，
     * 非 static 的工厂方法会让 {@code @Configuration} 类过早初始化。
     */
    @Bean
    static BeanPostProcessor goStyleRawBodyArgumentProcessor() {
        return new RawBodyProcessorRegistrar();
    }

    /**
     * 就地把 {@code RequestMappingHandlerAdapter} 里的默认
     * {@code RequestResponseBodyMethodProcessor} 换成 {@link GoStyleRawBodyArgumentProcessor}。
     */
    static final class RawBodyProcessorRegistrar implements BeanPostProcessor, BeanFactoryAware {

        /** ⚠️ 用 {@link ListableBeanFactory} 而非 {@code BeanFactory}：后者没有 {@code getBeanNamesForType}。 */
        private ListableBeanFactory beanFactory;

        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            // ApplicationContext 一定是 ListableBeanFactory。
            this.beanFactory = (ListableBeanFactory) beanFactory;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof RequestMappingHandlerAdapter adapter)) {
                return bean;
            }
            List<HandlerMethodArgumentResolver> current = adapter.getArgumentResolvers();
            if (current == null || current.isEmpty()) {
                return bean;
            }
            // ⚠️ 失败要响亮：替换实例会丢掉 RequestBodyAdvice 链（Spring 不给 getter），
            //    而「悄悄丢掉」正是 §3.3 反复踩的那类坑。
            // ⚠️ allowEagerInit=false：避免为了查类型而**提前初始化**别的 bean
            //    （本方法跑在容器 refresh 的中段，那时提前初始化是有代价的）。
            if (beanFactory.getBeanNamesForType(RequestBodyAdvice.class, true, false).length > 0) {
                throw new IllegalStateException(
                        "task-api 已用 GoStyleRawBodyArgumentProcessor 替换默认 RequestResponseBodyMethodProcessor，"
                                + "该替换无法传递 RequestBodyAdvice 链（Spring 未暴露 getter）。"
                                + "请先给 GoStyleRawBodyArgumentProcessor 补上 advice 传递，再移除本断言。");
            }

            List<HandlerMethodArgumentResolver> updated = null;
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i) instanceof RequestResponseBodyMethodProcessor) {
                    if (updated == null) {
                        updated = new ArrayList<>(current);
                    }
                    updated.set(i, new GoStyleRawBodyArgumentProcessor(
                            adapter.getMessageConverters(), null));
                }
            }
            if (updated != null) {
                // ⚠️ getArgumentResolvers() 返回的是**只读视图**，必须复制后再 set。
                adapter.setArgumentResolvers(updated);
            }
            return bean;
        }
    }
}
