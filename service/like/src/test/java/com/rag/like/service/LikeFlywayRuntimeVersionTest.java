package com.rag.like.service;

import com.rag.like.LikeServiceApplication;
import com.rag.like.support.NacosStubConfiguration;
import com.rag.like.support.TestPostgres;
import com.rag.like.support.TestRedis;

import org.flywaydb.core.Flyway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p><b>Flyway 运行时版本的<b>显式</b>断言</b>（不靠「迁移跑通了 ⇒ 版本肯定对」的间接覆盖）。
 *
 * <h2>🔴 为什么这条必须单独钉</h2>
 * <p>本仓根 pom 的 {@code <flyway.version>} = <b>11.14.1</b>，
 * 而 <b>Boot 3.5.11 的 BOM 自己管的是 {@code 11.7.2}</b>
 * （{@code spring-boot-dependencies-3.5.11.pom} 的 {@code <flyway.version>11.7.2</flyway.version>}）。
 * <p>⇒ 也就是说：<b>根 pom 的版本声明必须真的覆盖掉 Boot BOM 的值</b>，运行时才是 11.14.1。
 * <p>⚠️ <b>这个覆盖一旦失效不会立刻报错</b>：Flyway 11.7.2 与 11.14.1 在本域这 6 个脚本上
 * <b>都能跑通</b> ⇒ 迁移测试照样全绿 ⇒ ⇒ 只看「迁移成功」<b>证不了版本</b>。
 * <p>⇒ ⇒ 本类直接断言<b>运行时加载的那个 artifact 就是 11.14.1</b>。
 *
 * <h2>为什么用「code source 路径」而不是 {@code Package#getImplementationVersion()}</h2>
 * <p>实测：{@code flyway-core-11.14.1.jar} 的 {@code META-INF/MANIFEST.MF} 里
 * <b>没有 {@code Implementation-Version}</b> ⇒ {@code getImplementationVersion()} 返回 {@code null}
 * ⇒ 用它断言会得到「null == null」的假绿。
 * <p>⇒ ⇒ 用两条<b>互相独立</b>的证据：
 * <ol>
 *   <li><b>code source 路径</b>：{@code Flyway.class} 的加载位置就是
 *       {@code .../flyway-core/11.14.1/flyway-core-11.14.1.jar}
 *       ⇒ <b>文件名里就带着版本</b> ⇒ 判据自带「是哪一版」；</li>
 *   <li><b>jar 内的 {@code org/flywaydb/core/internal/version.txt}</b>：
 *       内容就是 {@code 11.14.1} ⇒ 与第 ① 条来自<b>同一个 jar</b>，但读的是<b>另一个文件</b>
 *       ⇒ 两条互不依赖。</li>
 * </ol>
 *
 * <h2>🔴 还要钉住「<b>跑迁移的那个引擎</b>就是这一版」</h2>
 * <p>本类 autowire 的 {@link Flyway} bean 就是 Boot 的 {@code FlywayAutoConfiguration}
 * 用同一个 classpath 上的 Flyway 建的 ⇒ 它的 code source 与 {@code Flyway.class} 必然同源
 * ⇒ ⇒ 「跑迁移的引擎 = 11.14.1」成立（不是「某个别处的 jar 是 11.14.1」）。
 *
 * <h2>⚠️ 本类的证据边界</h2>
 * <p>本类只证明「<b>版本是 11.14.1 且迁移引擎同源</b>」。
 * 「这 6 个脚本在 11.14.1 上的<b>行为</b>（baseline-version 0 / {@code $$} 块 / NOT VALID CHECK）」
 * 由 {@link LikeFlywayBaselineRealPgTest} 覆盖 ⇒ 两条证据互补，缺一不可。
 */
@SpringBootTest(classes = {LikeServiceApplication.class, NacosStubConfiguration.class})
@ActiveProfiles("test")
class LikeFlywayRuntimeVersionTest {

    /** 本仓根 pom 钉的版本（{@code <flyway.version>}）。 */
    private static final String EXPECTED_VERSION = "11.14.1";

    /**
     * Boot 3.5.11 的 BOM 自己管的版本 —— <b>本仓必须覆盖掉它</b>。
     * ⇒ 本条断言是「区分性」的：若覆盖失效，运行时版本会变成 11.7.2 ⇒ 这里会红。
     */
    private static final String BOOT_BOM_VERSION = "11.7.2";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
        TestRedis.register(registry);
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("🔴 运行时加载的 flyway-core 必须就是 11.14.1（覆盖 Boot 3.5.11 BOM 的 11.7.2）")
    void runtimeFlywayArtifactIsThePinnedVersion() {
        String codeSource = Flyway.class.getProtectionDomain().getCodeSource().getLocation().toString();

        assertThat(codeSource)
                .as("classpath 上实际加载的 flyway-core 必须是 %s —— 若变成 Boot BOM 的 %s "
                        + "说明根 pom 的 <flyway.version> 覆盖失效了", EXPECTED_VERSION, BOOT_BOM_VERSION)
                .contains("flyway-core-" + EXPECTED_VERSION + ".jar")
                .doesNotContain("flyway-core-" + BOOT_BOM_VERSION + ".jar");
    }

    @Test
    @DisplayName("🔴 jar 内自带的 version.txt 必须是 11.14.1（与 code source 路径互为独立证据）")
    void flywayJarSelfReportsTheSameVersion() throws Exception {
        try (InputStream in = Flyway.class.getClassLoader()
                .getResourceAsStream("org/flywaydb/core/internal/version.txt")) {
            assertThat(in).as("flyway-core jar 内必须有 org/flywaydb/core/internal/version.txt").isNotNull();
            String reported;
            try (InputStream stream = in) {
                reported = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            assertThat(reported)
                    .as("Flyway 自报版本必须与 <flyway.version> 一致")
                    .isEqualTo(EXPECTED_VERSION);
        }
    }

    @Test
    @DisplayName("🔴 跑迁移的那个 Flyway bean 必须与库里的 class 同源（即：这批脚本确实是 11.14.1 跑的）")
    void theEngineThatRanMigrationsIsTheSameArtifact() {
        String beanClass = flyway.getClass().getProtectionDomain().getCodeSource().getLocation().toString();
        String classSource = Flyway.class.getProtectionDomain().getCodeSource().getLocation().toString();

        assertThat(beanClass)
                .as("Spring 容器里的 Flyway bean 与 Flyway.class 必须来自同一个 artifact")
                .isEqualTo(classSource)
                .contains("flyway-core-" + EXPECTED_VERSION + ".jar");
    }

    @Test
    @DisplayName("🔴 11.14.1 确实跑完了 V1..V6（账本 6 条全 success）—— 版本与行为互为交叉验证")
    void migrationsWereAppliedByThisEngine() {
        Integer applied = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM flyway_schema_history_like
                WHERE version IN ('1', '2', '3', '4', '5', '6') AND success = true
                """, Integer.class);
        assertThat(applied)
                .as("V1..V6 六条都必须在 like 域自己的账本里标记为 success")
                .isEqualTo(6);
    }
}
