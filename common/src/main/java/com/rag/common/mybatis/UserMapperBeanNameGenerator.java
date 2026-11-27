package com.rag.common.mybatis;

/**
 * <p><b>users/user 域的 Mapper bean 名生成器</b> —— 加 {@code user} 前缀。
 *
 * <p>⚠️ <b>为什么必须是无参子类</b>：MyBatis 的 {@code @MapperScan#nameGenerator} 属性类型是
 * {@code Class} ⇒ ⇒ Spring/MyBatis 用<b>无参构造器</b>反射实例化
 * ⇒ ⇒ 带构造参数的生成器<b>不能</b>直接填进那个属性（实测：
 * {@code NoSuchMethodException: DomainPrefixedMapperBeanNameGenerator.<init>()}）。
 *
 * <p>📌 父类见 {@link DomainPrefixedMapperBeanNameGenerator}（含完整论证）。
 */
public class UserMapperBeanNameGenerator extends DomainPrefixedMapperBeanNameGenerator {

    /**
     * 无参构造器 —— MyBatis 反射实例化用。
     */
    public UserMapperBeanNameGenerator() {
        super("user");
    }
}
