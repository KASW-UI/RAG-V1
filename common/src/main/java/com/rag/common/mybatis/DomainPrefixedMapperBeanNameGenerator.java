package com.rag.common.mybatis;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;

/**
 * <p><b>MyBatis Mapper 的 bean 名生成器 —— 加域前缀</b>（S1 · 5 进程形态）。
 *
 * <h2>🔴 为什么需要它（实测根因）</h2>
 * <p>MyBatis 的 {@code @MapperScan} 默认用<b>简单类名首字母小写</b>作 bean 名
 * ⇒ ⇒ <b>users/user</b> 与 <b>users/admin</b> 两个子域<b>各有一个
 * {@code UserMapper}</b>（都映射 {@code users_user} 表 —— admin 子域独立持有，见
 * {@code service/users/admin/.../mapper/UserMapper.java} 的 javadoc）
 * ⇒ ⇒ 两者都归入 {@code user-service} 进程后 **bean 名撞车** ⇒
 * {@code ConflictingBeanDefinitionException: Annotation-specified bean name 'userMapper' … conflicts with existing}
 *
 * <h2>解法：给 bean 名加域前缀</h2>
 * <p>本类继承 Spring 的 {@code AnnotationBeanNameGenerator}（它会先看
 * {@code @Component("x")} 之类的显式命名 ⇒ <b>显式命名的照旧优先</b>），
 * 并在默认名<b>未被显式指定</b>时加上<b>域前缀</b>。
 * <p>⚠️ <b>只改 bean 名，不改类型、不改 SQL、不改扫描范围</b> ⇒ ⇒
 * <b>零行为变化</b>；域内代码按<b>类型</b>注入（{@code UserMapper}）不受影响。
 *
 * <h2>为什么落在 {@code common/} 而不是各域各写一份</h2>
 * <p>这是<b>无域归属</b>的<b>机制</b>（L4 机制层）⇒ ⇒ 符合 AGENTS.md §2.3 R1
 * 「{@code common/} 只放无域归属能力」⇒ ⇒ 落 {@code common/} 共享给各域用。
 * <p>⚠️ 前缀由<b>调用方</b>给出（本类只提供「加前缀」这个动作）⇒ ⇒ 各域的
 * {@code @MapperScan} 各自传自己的前缀。
 *
 * <p>📌 <b>全仓核查</b>：本仓<b>无</b>按 bean 名注入 Mapper 的地方（全部按类型注入）
 * ⇒ ⇒ 改名<b>不会</b>破坏任何现有注入点。
 */
public class DomainPrefixedMapperBeanNameGenerator extends AnnotationBeanNameGenerator {

    /**
     * 域前缀 —— 由各域的 {@code @MapperScan} 各自指定。
     */
    private final String domainPrefix;

    /**
     * 构造器。
     *
     * @param domainPrefix 域前缀（如 {@code "user"} / {@code "admin"}）
     */
    public DomainPrefixedMapperBeanNameGenerator(String domainPrefix) {
        this.domainPrefix = domainPrefix;
    }

    /**
     * 生成 bean 名 —— 默认名 + 域前缀。
     *
     * <p>⚠️ 若类上有<b>显式</b>命名（{@code @Component("x")} 等），父类会先返回那个名字
     * ⇒ 本方法<b>只在父类给出默认名时</b>追加前缀 ⇒ ⇒ 显式命名优先。
     *
     * @param beanDefinition bean 定义
     * @param registry       bean 定义注册表
     * @return bean 名
     */
    @Override
    public String generateBeanName(BeanDefinition beanDefinition,
                                   org.springframework.beans.factory.support.BeanDefinitionRegistry registry) {
        String defaultName = super.generateBeanName(beanDefinition, registry);
        if (defaultName == null || defaultName.isEmpty()) {
            return defaultName;
        }
        return domainPrefix + Character.toUpperCase(defaultName.charAt(0)) + defaultName.substring(1);
    }
}
