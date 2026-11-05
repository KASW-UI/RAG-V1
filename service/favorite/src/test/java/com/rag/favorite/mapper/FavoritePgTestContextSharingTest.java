package com.rag.favorite.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴🔴 **真 PG 测试类的 Spring 上下文必须共用同一个** —— 机器守门（2026-11-05 实测踩坑后补）。
 *
 * <h2>它防的是什么</h2>
 * <p>{@link FavoritePgParityTestBase} 的 {@code GRPC_PORT} 是<b>基类的静态常量</b>
 * ⇒ ⇒ 所有子类注册给 {@code grpc.server.port} 的是<b>同一个</b>端口。
 * <p>⇒ 若某个子类的 {@code @SpringBootTest.properties} 与其它子类<b>不逐字相同</b>
 * ⇒ ⇒ Spring TestContext 的缓存键（{@code MergedContextConfiguration}）**不同**
 * ⇒ ⇒ 框架**另起一个上下文** ⇒ ⇒ 两个上下文抢<b>同一个</b> gRPC 端口 ⇒ <b>EADDRINUSE</b>
 * ⇒ ⇒ 第二个上下文 refresh 失败 ⇒ ⇒ Spring 的
 * {@code ApplicationContext failure threshold (1) exceeded} 把<b>后续全部</b>上下文加载一并跳过。
 *
 * <h2>为什么这条必须是机器断言</h2>
 * <p>踩坑时的表象是「4 个 PG 测试类共 24 个用例全红」，但报错栈里
 * <b>没有 PG、没有 schema、没有端口冲突</b>的字样（只有一句 threshold 异常）
 * ⇒ ⇒ 极像「PG 容器坏了 / schema 漂了 / 迁移脚本错了」⇒ ⇒ 排查方向被完全带偏。
 * ⇒ ⇒ 所以把它变成一条<b>直接红</b>的断言，比留一段注释可靠。
 *
 * <h2>§3.2 三段式（AGENTS.md §3.2）</h2>
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：<b>新增一类测试</b> —— B 侧<b>没有</b>任何 preflight 测试，
 *       也就<b>没有</b>「多一个 PG 测试类」这件事 ⇒ ⇒ 本类是 C 侧新增的证据。</li>
 *   <li><b>语义还一样吗</b>：不适用（本类不判定 B/C 等价，只钉 C 侧自己的测试基础设施口径）。</li>
 *   <li><b>B 出处</b>：B 侧 {@code service/favorite/favorite-rpc/src/test/java/com/rag/favorite/favorite/rpc/
 *       FavoriteSchemaParityTest.java:73-87}（同款 {@code @DynamicPropertySource} 注入 +
 *       {@code grpc.server.port} 钉死端口的做法）；B 侧只有 1 个 PG 测试类 ⇒ ⇒
 *       <b>本条守门是 C 侧独有的</b>（C 侧有 5 个 PG 测试类 ⇒ 才需要这条）。</li>
 * </ol>
 */
class FavoritePgTestContextSharingTest {

    /**
     * 🔴 本类**不需要** Spring 上下文 ⇒ 纯反射断言 ⇒ 跑得极快、不占端口。
     */
    @Test
    @DisplayName("mapper 包下所有真 PG 测试类的 @SpringBootTest.properties 必须逐字相同 ⇒ 共用一个上下文 ⇒ 不会抢同一个 gRPC 端口")
    void allPgTestClassesShareTheSameContextConfiguration() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(FavoritePgParityTestBase.class));

        Set<BeanDefinition> candidates =
                scanner.findCandidateComponents(FavoritePgParityTestBase.class.getPackageName());

        List<Class<?>> pgTestClasses = new ArrayList<>();
        for (BeanDefinition definition : candidates) {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName());
                // 抽象基类本身不进候选（扫描器只收 concrete）⇒ 这里再兜一层底。
                if (!java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
                    pgTestClasses.add(type);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到的候选类加载失败：" + definition.getBeanClassName(), e);
            }
        }

        assertTrue(pgTestClasses.size() >= 2,
                "至少要有 2 个真 PG 测试类，这条守门才有意义（实扫到 " + pgTestClasses.size() + " 个）");

        Set<List<String>> distinctPropertySets = new LinkedHashSet<>();
        for (Class<?> type : pgTestClasses) {
            SpringBootTest annotation = type.getAnnotation(SpringBootTest.class);
            assertTrue(annotation != null,
                    type.getSimpleName() + " 继承 FavoritePgParityTestBase 就必须带 @SpringBootTest");
            distinctPropertySets.add(Arrays.asList(annotation.properties()));
        }

        assertEquals(1, distinctPropertySets.size(),
                "🔴 真 PG 测试类的 @SpringBootTest.properties 必须逐字相同 —— 否则 Spring 会起第二个上下文，"
                        + "而基类的 GRPC_PORT 是共享静态常量 ⇒ 两个上下文抢同一个 gRPC 端口 ⇒ EADDRINUSE ⇒ "
                        + "后续全部上下文加载被 'ApplicationContext failure threshold' 跳过（表象：几十个用例全红，"
                        + "但栈里没有端口冲突的字样）。实扫到的类：" + pgTestClasses.stream()
                        .map(Class::getSimpleName).sorted().toList()
                        + "；实扫到的不同 properties 集合数：" + distinctPropertySets.size());
    }
}
