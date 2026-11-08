package com.rag.hot.assembly;

import com.rag.hot.support.HotRealStackTestBase;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>hot 域「无表 ⇒ 无持久层」的守门</b> —— 钉死 2026-11-09（global 收口域统一处置 · 台账 F-13）
 * 从 {@code service/hot/pom.xml} 与 {@code application.yml} 删掉的那几行<b>不会</b>被模板重新带回来。
 *
 * <h2>🔴 为什么这条要守（它防的是「模板批量生成」的复发）</h2>
 * <p>全仓 10 个域的 {@code pom.xml} / {@code application.yml} 都是<b>同一套模板</b>生成的
 * ⇒ ⇒ 「10 域同款」曾经是事实 ⇒ ⇒ 一旦有人用模板重新生成，持久层依赖会<b>静默回来</b>
 * ⇒ ⇒ 而「hot 有 DataSource」这件事<b>不会报错</b>（Hikari 懒连接）⇒ ⇒ 只能靠<b>断言</b>钉住。
 *
 * <h2>🔴🔴 断言的是「<b>没有连接池</b>」，不是「没有 JDBC 驱动」—— 这两个必须分清</h2>
 * <ul>
 *   <li><b>{@code org.postgresql:postgresql} 驱动仍然在 classpath 上</b>（<b>且这是对的</b>）：
 *       它是 {@code common/pom.xml} 的<b>非 optional</b> compile 依赖
 *       ⇒ ⇒ 共享层的 {@code common/persistence/JsonbTypeHandler} 编译期就要引用
 *       {@code org.postgresql.util.PGobject} ⇒ ⇒ <b>不能</b>为了 hot 而把驱动从共享层摘掉
 *       ⇒ ⇒ <b>驱动在，但完全惰性</b>（见下）；</li>
 *   <li><b>{@code mybatis} 本体也仍然在</b>（同样来自共享层的非 optional compile 依赖）
 *       ⇒ ⇒ 共享层的超时拦截器才能在所有域里存在 ⇒ ⇒ <b>不能</b>摘。</li>
 *   <li>真正被删掉的是 <b>{@code mybatis-plus-spring-boot3-starter}</b>
 *       ⇒ ⇒ 它带来两样东西：<b>MyBatis-Plus 的 Spring Boot 自动配置</b> ＋ <b>HikariCP</b>；</li>
 *   <li><b>「不建 DataSource」的真正机制（Boot 3.5.11 源码逐行核对）</b>：
 *       {@code DataSourceAutoConfiguration.PooledDataSourceConfiguration} 带
 *       {@code @Conditional(PooledDataSourceCondition.class)}，而后者 =
 *       「配了 {@code spring.datasource.type}」<b>或</b>「classpath 上<b>有</b>可用的连接池实现」
 *       ⇒ ⇒ <b>没有 Hikari ⇒ 条件不匹配 ⇒ 整个池化配置不加载</b>
 *       ⇒ ⇒ 也<b>不需要</b> {@code spring.datasource.url}
 *       ⇒ ⇒ 顺带说明：<b>删掉 datasource 配置不会让 hot 启动炸</b>
 *       （若 classpath 上<b>还有</b> Hikari 而 url 又被删了 ⇒ 才会抛
 *       「Failed to configure a DataSource」⇒ ⇒ 这两条是<b>成对</b>的）。</li>
 * </ul>
 *
 * <h2>B 出处</h2>
 * <ul>
 *   <li>B 侧 hot-api / hot-rpc：<b>零</b> {@code domain/*Entity}、<b>零</b> {@code mapper/}、
 *       <b>零</b> DDL；hot 的 pom 里<b>无</b> mybatis / gorm / datasource / postgres
 *       ⇒ ⇒ C 侧删掉这几行 = <b>回到 B 侧基准</b>（D16），不是「少配了什么」。</li>
 *   <li>其余 9 域（article / comment / favorite / follow / like / message / task /
 *       users-user / users-admin）<b>都真有</b> {@code @TableName} 实体与 MP {@code BaseMapper}
 *       ⇒ ⇒ 它们保留持久层依赖 ⇒ ⇒ 本次统一决策 =「<b>只有 hot 该删</b>」。</li>
 * </ul>
 */
class HotNoPersistenceResidueTest extends HotRealStackTestBase {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("classpath 上没有 MyBatis-Plus / 没有连接池（Hikari）⇒ 造不出 DataSource")
    void classpathHasNoPersistenceRuntime() {
        ClassLoader cl = getClass().getClassLoader();

        assertThat(ClassUtils.isPresent("com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration", cl))
                .as("MyBatis-Plus 的 Spring Boot 自动配置必须在 classpath 上缺席（hot 是无表域）")
                .isFalse();
        assertThat(ClassUtils.isPresent("com.baomidou.mybatisplus.core.MybatisConfiguration", cl))
                .as("MyBatis-Plus 本体必须在 classpath 上缺席")
                .isFalse();
        assertThat(ClassUtils.isPresent("com.zaxxer.hikari.HikariDataSource", cl))
                .as("连接池实现必须缺席 —— 它才是『Boot 会不会建 DataSource』的决定项"
                        + "（PooledDataSourceCondition 要求 classpath 上有池）")
                .isFalse();
        // ⚠️ **不**断言 mybatis 本体 / PG 驱动缺席：两者都是共享层的非 optional compile 依赖
        //    （超时拦截器与 JsonbTypeHandler 要用）⇒ 全域都有 ⇒ 是设计，不是残留。
        assertThat(ClassUtils.isPresent("org.apache.ibatis.session.SqlSessionFactory", cl))
                .as("mybatis 本体在（共享层拦截器要用）⇒ 本断言只是钉住『它在，但没被 Spring 装配』")
                .isTrue();
    }

    @Test
    @DisplayName("真实上下文里没有 DataSource / SqlSessionFactory（装配面与 B 侧一致）")
    void contextHasNoDataSourceNorSqlSessionFactory() {
        assertThat(context.getBeanNamesForType(DataSource.class))
                .as("hot 是无表域 ⇒ 容器里不允许出现 DataSource")
                .isEmpty();
        // ⚠️ mybatis **本体**在 classpath 上 ⇒ 可以直接用类字面量查（不存在编译问题）。
        assertThat(context.getBeanNamesForType(SqlSessionFactory.class))
                .as("hot 是无表域 ⇒ 容器里不允许出现 SqlSessionFactory"
                        + "（MyBatis-Plus 自动配置已缺席 ⇒ 没人会造它）")
                .isEmpty();
        // 配置面：datasource 段已删 ⇒ 域内不许再出现 datasource 连接串
        assertThat(environment.getProperty("spring.datasource.url"))
                .as("hot 域不该再配 spring.datasource.url（模板残留已删）")
                .isNull();
    }
}
