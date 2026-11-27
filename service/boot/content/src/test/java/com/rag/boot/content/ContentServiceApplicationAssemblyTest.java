package com.rag.boot.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import com.rag.boot.support.BootAssemblyTestBase;
import com.rag.boot.support.NacosStubConfiguration;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * <p><b>S1 · 进程壳装配层门禁</b> —— 验证 {@link ContentServiceApplication} 的
 * <b>组件扫描边界</b>正确：<b>article 域的 bean 在，别域的 bean 不在</b>。
 *
 * <h2>🔴 为什么必须有这条测试（AGENTS.md §3.3 · §3.3.1 · §3.3.3）</h2>
 * <p>本仓的四类<b>静默失效</b>里，「<b>扫描边界把别的域的 bean 拖进来</b>」是最阴的一种：
 * <ul>
 *   <li>它<b>不报错</b>：启动成功、测试全绿，只是上下文里多了不该有的 bean；</li>
 *   <li>它的后果<b>延迟到运行时</b>：比如 comment 域的 {@code @RestControllerAdvice} 捕获了
 *       article 的异常 ⇒ 错误码错乱，且<b>极难定位</b>（见计划书 S3-7）；</li>
 *   <li>⇒ 守门脚本（源码文本扫描）<b>判不了</b>它 ⇒ ⇒ 必须有<b>运行期证据</b>。</li>
 * </ul>
 * ⇒ ⇒ 本类断言「<b>本域 bean 存在</b>」＋「<b>他域 bean 不存在</b>」两条，缺一不可。
 *
 * <h2>为什么用「bean 不存在」而不是「bean 数量」</h2>
 * <p>「bean 数量」是<b>脆断言</b>：域内加一个 bean 就红 ⇒ ⇒ 本类只断言
 * <b>具体类的存在/不存在</b>（按类名锚定）⇒ 域内正常演进不受影响。
 *
 * <h2>⚠️ 本测试不需要外部容器</h2>
 * <p>装配层测试<b>不连</b> PG / Redis / Kafka ⇒ ⇒ <b>不需要 Docker</b>、不需要 Testcontainers
 * ⇒ ⇒ 属本仓「<b>真触发但自包含</b>」的测试类型（§3.3.3：纯 Mockito 单测不算，
 * 但本类<b>真的建了 Spring 上下文</b> ⇒ 算运行期证据）。
 *
 * <h2>⚠️ 已知限制（诚实登记）</h2>
 * <p>本类<b>不起</b> {@code @SpringBootTest} 的完整上下文（未加 {@code webEnvironment}）⇒
 * ⇒ <b>不</b>断言「HTTP 端点真的可达」「gRPC server 真的起来」⇒ ⇒ 那属 S3/S4 的验证范围。
 * <p>本类<b>只</b>回答一个问题：「<b>组件扫描边界对不对</b>」。
 */
@SpringBootTest(
        classes = {ContentServiceApplication.class, NacosStubConfiguration.class},
        properties = {
                "spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
                "management.otlp.tracing.export.enabled=false",
                "logging.level.com.alibaba.nacos=WARN",
                "grpc.server.port=0",
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                "spring.kafka.producer.properties.max.block.ms=2000"
        })
class ContentServiceApplicationAssemblyTest extends BootAssemblyTestBase {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("content 进程：article 域的 bean 存在（controller + mapper + 域配置）")
    void articleDomainBeansArePresent() {
        assertNotNull(ctx.getBean(com.rag.article.controller.ArticleController.class),
                "article 域的 controller 必须被扫进 content 上下文");
        assertNotNull(ctx.getBean(com.rag.article.mapper.ArticleSyncOutboxMapper.class),
                "article 域的 mapper 必须被扫进 content 上下文（@MapperScan 随域启动类生效）");
        assertNotNull(ctx.getBean(com.rag.article.config.ArticleApiProperties.class),
                "article-api.* 配置必须被绑定（@ConfigurationPropertiesScan 随域启动类生效）");
    }

    @Test
    @DisplayName("content 进程：他域的 bean 不得出现（扫描边界不串域）")
    void otherDomainBeansAreAbsent() {
        // ★ 这条是本类的核心判据：扫描边界若放宽，会静默把别域拖进来
        assertNoBean("commentController");
        assertNoBean("favoriteController");
        assertNoBean("followController");
        assertNoBean("hotArticleController");
        assertNoBean("likeController");
        assertNoBean("messageController");
        assertNoBean("taskController");
        assertNoBean("userController");
        assertNoBean("adminController");
    }

    /**
     * 🔴 <b>域内 auto-config 的 bean 名必须是 FQCN</b>（不是短名）。
     *
     * <h2>为什么这条必须钉住（S2 · 实测）</h2>
     * <p>article 域有一个<b>域内</b> auto-config：
     * {@code com.rag.article.client.ContentAuditAutoConfiguration}
     * （注册在 {@code service/article/src/main/resources/META-INF/spring/
     * org.springframework.boot.autoconfigure.AutoConfiguration.imports}）。
     * <p>⚠️ <b>两种启动入口 ⇒ 两种 bean 名</b>：
     * <ul>
     *   <li>用<b>域启动类</b>起上下文 ⇒ bean 名 = <b>FQCN</b>
     *       （{@code com.rag.article.client.ContentAuditAutoConfiguration}）✅</li>
     *   <li>用<b>boot 启动类</b>起上下文 ⇒ 若 boot 的 {@code @ComponentScan}
     *       <b>没带</b> {@code AutoConfigurationExcludeFilter}
     *       ⇒ 该类被<b>组件扫描</b>扫到 ⇒ bean 名 = <b>短名</b>
     *       {@code contentAuditAutoConfiguration} ❌
     *       ⇒ ⇒ <b>绕过了</b> {@code .imports} 机制</li>
     * </ul>
     * <p>⇒ ⇒ <b>这是真实的机制差异，不是风格问题</b>（实测两个启动入口跑同一份域代码，
     * bean 名不同）⇒ ⇒ 必须钉住。
     *
     * <p>⚠️ <b>为什么既有守门盖不住</b>：A5 只查「{@code @Import} 清单必须为空」
     * ⇒ ⇒ <b>不查</b>「auto-config 的 bean 名是不是 FQCN」⇒ ⇒ 这条只能靠本类钉。
     *
     * <p>🔴 <b>判据要点</b>：不能只断言「短名<b>不存在</b>」⇒ 还要断言「FQCN <b>存在</b>」
     * ⇒ ⇒ 否则「两个都不存在」（auto-config 压根没装配）也会绿 ⇒ ⇒ **两条都要**。
     */
    @Test
    @DisplayName("🔴 域内 auto-config 的 bean 名必须是 FQCN（boot 启动类的 @ComponentScan 必须带 excludeFilters）")
    void domainAutoConfigurationBeanNameIsFqcn() {
        org.junit.jupiter.api.Assertions.assertTrue(
                ctx.containsBean("com.rag.article.client.ContentAuditAutoConfiguration"),
                () -> "article 域内 auto-config 的 bean 名必须是 FQCN"
                        + "（com.rag.article.client.ContentAuditAutoConfiguration）"
                        + " ⇒ boot 启动类的 @ComponentScan 丢了 AutoConfigurationExcludeFilter"
                        + " ⇒ 该类被组件扫描扫到 ⇒ bean 名变成短名 contentAuditAutoConfiguration"
                        + " ⇒ ⇒ 绕过了 .imports 机制");
        org.junit.jupiter.api.Assertions.assertFalse(
                ctx.containsBean("contentAuditAutoConfiguration"),
                () -> "出现了**短名** bean（contentAuditAutoConfiguration）"
                        + " ⇒ 该 auto-config 是被组件扫描扫到的，不是 .imports 机制装配的"
                        + " ⇒ ⇒ bean 名与域启动类起上下文时不一致");
    }

    /**
     * 断言某个 bean 名不存在。
     *
     * <p>⚠️ 用 {@code containsBean} 而非 {@code getBean}：后者在缺失时会抛
     * {@code NoSuchBeanDefinitionException}（能红，但错误信息不友好）⇒
     * 这里显式判定「不存在」并给出可读信息。
     *
     * @param beanName bean 名
     */
    private void assertNoBean(String beanName) {
        org.junit.jupiter.api.Assertions.assertFalse(ctx.containsBean(beanName),
                () -> "content 进程里不得出现他域的 bean：" + beanName
                        + " ⇒ @ComponentScan 的范围被放宽了");
    }
}
