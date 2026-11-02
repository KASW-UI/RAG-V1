package com.rag.common.persistence;

import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * <p><b>把容器里的 {@link SqlSessionFactory} bean 换成 {@link StatusAwareSqlSessionFactory}</b>
 * ⇒ ⇒ 持久层的 {@code DEADLINE_EXCEEDED} 能以 {@code StatusRuntimeException} 的形态
 * 直达域层 ⇒ ⇒ 落业务码 <b>1015</b>（B 侧口径），而不是被 MyBatis 包成
 * {@code PersistenceException} 后落 <b>5001</b>。
 *
 * <h2>⚠️ 为什么必须做成 {@link BeanPostProcessor}（而不是换掉整个 bean 定义）</h2>
 * <p>MyBatis-Plus 的 {@code MybatisPlusAutoConfiguration} 会用
 * {@code MybatisSqlSessionFactoryBean} 造出 {@code SqlSessionFactory} bean
 * ⇒ ⇒ 本仓<b>不去替换</b>那个 bean 定义（那会与 MP 的自动配置打架）
 * ⇒ ⇒ 只在 <b>bean 初始化之后</b>把实例换掉
 * ⇒ ⇒ <b>依赖它的 bean（{@code SqlSessionTemplate}、各 mapper 的 {@code FactoryBean}）
 * 注入到的就是换过的实例</b>（Spring 的依赖注入发生在 BPP 之后）。
 *
 * <h2>⚠️ 为什么「换实例」不会破坏 MP 的取工厂逻辑</h2>
 * <p>MP 的 {@code MybatisUtils.getSqlSessionFactory(sqlSession)} 优先读 session 上的
 * {@code getSqlSessionFactory()} getter（{@code SqlSessionTemplate} 有 ⇒ 读的是
 * <b>容器里被换过的那个</b>）⇒ ⇒ 游标路径（{@code BaseMapper.selectOne(Wrapper, boolean)}）
 * 也会拿到被换过的工厂 ⇒ ⇒ 两条路径都覆盖（详见 {@link StatusAwareSqlSession} 类头）。
 *
 * <p><b>三段式留痕（AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：B 侧没有这一层（gRPC 中间件原样传 status）⇒ C 侧补上。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 只影响「共享层自己造的 DEADLINE_EXCEEDED」这一种异常。</li>
 *   <li><b>B 出处</b>：B 侧 {@code service/common/.../persistence/SqlTimeoutStatusTranslator.java}
 *       的类头（「ctx 到期 ⇒ 落 default ⇒ 1015」）＋ 本仓
 *       {@code docs/GORM-MP-语义对照.md} 第 157 行。</li>
 * </ol>
 */
public class StatusAwareSqlSessionFactoryPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof SqlSessionFactory factory)) {
            return bean;
        }
        if (factory instanceof StatusAwareSqlSessionFactory) {
            // 幂等：已经是包装过的就不再包一层（避免 BPP 被重复注册时套娃）。
            return bean;
        }
        return new StatusAwareSqlSessionFactory(factory);
    }
}
