package com.rag.common.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * <p><b>B 出处</b>：<b>无</b> —— B 侧用 GORM，<b>没有</b>「MyBatis-Plus 自动配置排序」这个概念
 * ⇒ ⇒ 本类是 <b>Spring Boot 自动配置的排序 workaround</b>
 * ⇒ ⇒ <b>纯表达层</b>：它只保证 MP 的自动配置被求值 ⇒ <b>不改变任何业务语义</b> ⇒ <b>无行为面</b>。
 *
 * <h2>⚠️ Boot 4 → Boot 3.5：本类在本仓是「护栏」，不是「修复」</h2>
 * <p>MyBatis-Plus 的 {@code MybatisPlusAutoConfiguration} 上写着
 * {@code @AutoConfigureAfter(org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration)}。
 * <br>⇒ <b>Boot 3.5 下这个类名是对的</b>（Boot 4.0 才把它挪到
 * {@code org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration}）
 * ⇒ ⇒ MP 声明的排序引用<b>不会悬空</b> ⇒ 本类<b>不需要</b>来救场。
 * <br>⇒ ⇒ 保留它的理由是<b>两个</b>：
 * <ol>
 *   <li><b>断言 A6</b>：「MP ordering bridge 全仓只有一份，且必须在 {@code common/}」
 *       ⇒ 本类是那份「唯一一份」的存在载体（各域若再抄一份 ⇒ A6 变红）；</li>
 *   <li><b>升级护栏</b>：若日后 Boot 再挪这个类，本类里的 {@code afterName} 会<b>先于</b>
 *       MP 自己的声明生效 ⇒ 不会出现「静默悬空」；
 *       而没有本类时，Boot 4 那种静默失效（MP 自动配置不生效 ⇒
 *       mapper 报 {@code Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required}）
 *       <b>零报错</b>，只能靠人肉发现。</li>
 * </ol>
 *
 * <h2>🔴🔴 为什么这里用<b>字符串</b>形式（{@code afterName} / {@code beforeName}）而不是类字面量</h2>
 * <p>本类在<b>共享层的主 jar</b>里 ⇒ ⇒ 它会出现在<b>所有消费者</b>的 classpath 上
 * ⇒ ⇒ 其中<b>没有 MyBatis-Plus 的那些</b>（10 个 api ＋ 各域不需要 MP 的进程）
 * <b>根本解析不了</b> {@code MybatisPlusAutoConfiguration.class} 这个类字面量。
 * <p>⇒ 若写成类字面量形式：
 * <pre>
 *   &#64;AutoConfiguration(after = DataSourceAutoConfiguration.class,
 *                        before = MybatisPlusAutoConfiguration.class)   // ← 类字面量
 * </pre>
 * ⇒ 一旦有消费者在<b>反射</b>层面读这个注解（而不是用 ASM 读元数据），
 * 就会抛 {@code TypeNotPresentException} ⇒ ⇒ <b>那些消费者的上下文直接起不来</b>。
 * <p>⇒ 而字符串形式（Boot 的 {@code @AutoConfiguration} 用
 * {@code @AliasFor(annotation = AutoConfigureBefore.class, attribute = "name")} 把它落到
 * {@code AutoConfigureBefore.name}）：
 * <pre>
 *   &#64;AutoConfiguration(
 *           afterName  = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
 *           beforeName = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
 * </pre>
 * ⇒ ⇒ <b>类名只是字符串</b> ⇒ ⇒ 目标类在不在 classpath 上都<b>不影响</b>本类的加载
 * ⇒ ⇒ <b>对「没有 MyBatis-Plus」的消费者结构性安全</b>
 * ⇒ ⇒ 且排序机制照样成立（{@code AutoConfigurationSorter} 读的就是这两个字符串）。
 *
 * <h2>⚠️ 本类【没有】{@code @ConditionalOnMissingBean} ⇒ 「遮蔽」不成立</h2>
 * <p>域内副本与本类<b>并存</b>时：<b>两份都会被注册</b>（两份都刻意类体为空、<b>不注册任何 bean</b>）
 * ⇒ ⇒ <b>零行为影响，但不是「遮蔽」</b> ⇒ ⇒ 真正的差别只是「<b>排序图上多一个同向节点</b>」
 * （域内用类字面量、共享层用字符串，但指向<b>同一对 FQN</b> ⇒ 同向等价、无环）⇒ ⇒ 删除仍<b>零行为差</b>。
 *
 * <h2>⚠️ 删域内副本时：回归【必须带 {@code mvn clean}】</h2>
 * <p>删掉某模块的域内副本时，<b>必须同批删掉该模块 {@code AutoConfiguration.imports} 里注册它的那一行</b>
 * ⇒ ⇒ 只删 java 文件、不删那一行 ⇒ Boot 启动期找不到类 ⇒ 上下文起不来。
 * <p>🔴 <b>但即使两处都删对了，回归也可能是红的</b> ⇒ <b>原因不在代码，在 stale 产物</b>：
 * <ul>
 *   <li>{@code maven-resources-plugin} <b>不会删除已删源文件的副本</b> ⇒ ⇒ {@code target/classes} 里
 *       <b>旧的 {@code .imports} 与旧的 {@code .class} 仍在</b>；</li>
 *   <li>⇒ ⇒ Boot 照旧注册那个「<b>源码已不存在</b>」的类 ⇒ ⇒ 报
 *       {@code IllegalStateException: Unable to read meta-data for class …}；</li>
 * </ul>
 * ⇒ ⇒ <b>处置 = 回归带 {@code mvn clean}</b>
 * ⇒ ⇒ <b>判据</b>：红在 {@code Unable to read meta-data for class …} ⇒ <b>先查 stale 产物，别急着判收编失败</b>。
 *
 * <p><b>抄写基准</b>：RAG {@code service/common/src/main/java/com/rag/common/persistence/MybatisPlusOrderingBridgeAutoConfiguration.java}
 * ＋ 9 份域内副本（**收编为 1 份** · L-1 断言 A6）＋ <b>Boot 4 的 {@code afterName} 改回 Boot 3.5 的包名</b>（L4 · D14）。
 */
@AutoConfiguration(
        afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        beforeName = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
public class MybatisPlusOrderingBridgeAutoConfiguration {
    // 刻意为空：只为建立自动配置排序边，不注册任何 bean。
}
