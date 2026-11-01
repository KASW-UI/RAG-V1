package com.rag.common.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationImportSelector;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3.3「机制可证」守门：<b>starter 的装配清单真的生效</b>。
 *
 * <p>⚠️ <b>为什么必须有这条测试</b>：
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 是<b>纯文本</b>的 ⇒ 写错一个类名 / 少写一行 / 多写一行 ⇒
 * <b>编译期不报错、启动期不报错</b>（那个 auto-config 就是<b>不装配</b>）
 * ⇒ ⇒ 只能靠<b>文件内容断言</b>把它钉住。
 *
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>⚠️ 2026-11-02 扩了扫描范围（DRIFT-COMMON-V01 的根因就是这个盲区）</b>
 * ══════════════════════════════════════════════════════════════════════
 * <p><b>旧判据</b>：「{@code autoconfigure/} 目录下的类集合 == imports 清单的类集合」。
 * <br><b>盲区</b>：它<b>只看 {@code autoconfigure/} 目录</b> ⇒
 * <b>放在别的包下的 auto-config</b>（{@code redis/ClientAutoConfiguration} ·
 * {@code redis/DeadlineAutoConfiguration}）<b>根本不在判据范围内</b>
 * ⇒ ⇒ 这两个类<b>写了完整实现、却因为没登记进 imports 而永不加载</b>（静默失效、零报错）
 * ⇒ ⇒ 审核实测（B 侧 488 例 / C 侧 96 例双跑）把这条列为 🔴 <b>DRIFT-COMMON-V01</b>。
 *
 * <p><b>新判据（真正的不变式）</b>：
 * <b>全模块每一个 {@code @AutoConfiguration} 类都必须有「激活路径」</b> ——
 * 要么<b>登记在 imports 清单</b>里，要么<b>被某个 auto-config 的 {@code @Bean} 方法显式实例化</b>。
 * <br>⇒ 落在 {@code autoconfigure/} 之外的 auto-config 也被覆盖 ⇒ V01 这类缺口**当场变红**。
 */
class AutoConfigurationImportsTest {

    private static final Path IMPORTS = Paths.get(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");

    private static final Path MAIN_ROOT = Paths.get("src/main/java/com/rag/common");

    private static final Path AUTOCONFIGURE_DIR = Paths.get(
            "src/main/java/com/rag/common/autoconfigure");

    /** 源文件里**行首**的 {@code @AutoConfiguration}（行首判定 ⇒ 不会把 javadoc 里的提及算进来）。 */
    private static final Pattern AUTO_CONFIG_ANNOTATION = Pattern.compile("^\\s*@AutoConfiguration\\b");

    @Test
    @DisplayName("imports 清单里的每一个类都真实存在且带 @AutoConfiguration")
    void everyListedClassIsAnAutoConfiguration() throws Exception {
        List<String> listed = readImports();
        assertThat(listed).isNotEmpty();
        for (String className : listed) {
            Class<?> type = Class.forName(className);
            assertThat(type.isAnnotationPresent(AutoConfiguration.class))
                    .as("%s 必须带 @AutoConfiguration（否则 starter 形态不成立）", className)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("守恒断言：autoconfigure/ 目录下的每个类都在 imports 清单里")
    void directoryClassesAreAllListed() throws Exception {
        List<String> listed = readImports();
        List<String> inDirectory = new ArrayList<>();
        try (Stream<Path> files = Files.list(AUTOCONFIGURE_DIR)) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".java")) {
                    continue;
                }
                inDirectory.add("com.rag.common.autoconfigure." + fileName.substring(0, fileName.length() - 5));
            }
        }
        assertThat(inDirectory).isNotEmpty();
        // ⚠️ 「目录 ⊆ 清单」而不是「目录 == 清单」：清单里还可以有**别的包**下的 auto-config
        //    （例如 redis/ 的两个）⇒ 等号会把它们判成「多出来的行」。
        assertThat(listed).containsAll(inDirectory);
    }

    @Test
    @DisplayName("🔴 守恒断言：全模块每个 @AutoConfiguration 类都有激活路径（清单登记 或 被某个 auto-config 的 @Bean 实例化）")
    void everyAutoConfigurationHasAnActivationPath() throws Exception {
        List<String> listed = readImports();
        List<String> candidates = findAutoConfigurationClasses();
        assertThat(candidates)
                .as("全模块至少要有一个 @AutoConfiguration 类（否则本测试是空断言）")
                .isNotEmpty();

        Set<String> beanInstantiated = findBeanInstantiatedClasses(candidates);

        for (String fqcn : candidates) {
            boolean activated = listed.contains(fqcn) || beanInstantiated.contains(fqcn);
            assertThat(activated)
                    .as("%s 带 @AutoConfiguration，但既不在 imports 清单、也没被任何 auto-config 的 @Bean 注册"
                            + " ⇒ 它**永不激活**（静默失效、零报错）—— 这正是 DRIFT-COMMON-V01 的根因", fqcn)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("imports 清单没有重复行（重复行会让同一个 auto-config 被装配两次）")
    void noDuplicateEntries() throws Exception {
        List<String> listed = readImports();
        assertThat(listed).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("🔴 真触发：Boot **真实的** AutoConfigurationImportSelector 从 classpath 清单里选出的本模块 auto-config == 源码清单")
    void bootImportSelectorReallySelectsOurAutoConfigurations() throws Exception {
        List<String> selected = bootSelectedCandidates().stream()
                .filter(name -> name.startsWith("com.rag.common."))
                .toList();
        List<String> listed = readImports();

        // ⚠️ 判据用的是 **classpath 上的**清单（= 生产真源：Boot 读的就是它），
        //    与源码清单逐条比对 ⇒ 打包漏行 / 源码改了没重新打包 ⇒ 立刻变红。
        assertThat(selected)
                .as("Boot 从 classpath 清单里选出的本模块 auto-config 必须与源码清单逐条一致"
                        + "（这是**生产路径**，不是「读一下文件」那种弱断言）")
                .containsExactlyInAnyOrderElementsOf(listed);
    }

    // ── 扫描工具 ────────────────────────────────────────────────────────

    /**
     * 用 Boot <b>真实的</b> {@link AutoConfigurationImportSelector} 走一遍生产选择路径。
     *
     * <p>⚠️ {@code getCandidateConfigurations} 是 protected ⇒ 这里用子类把它<b>暴露</b>出来；
     * 内部读的是 classpath 上的 {@code META-INF/spring/…AutoConfiguration.imports}
     * （含 Boot 自己的全部条目）⇒ 我们只取 {@code com.rag.common.*} 的那些。
     */
    private static List<String> bootSelectedCandidates() throws Exception {
        ExposedImportSelector selector = new ExposedImportSelector();
        selector.setBeanClassLoader(AutoConfigurationImportsTest.class.getClassLoader());
        selector.setResourceLoader(new DefaultResourceLoader());
        return selector.exposedCandidates(
                AnnotationMetadata.introspect(EnableAutoConfiguration.class),
                new AnnotationAttributes());
    }

    /** 把 protected 的候选解析暴露出来（不改变 Boot 的任何行为）。 */
    static class ExposedImportSelector extends AutoConfigurationImportSelector {

        List<String> exposedCandidates(AnnotationMetadata metadata, AnnotationAttributes attributes) {
            return getCandidateConfigurations(metadata, attributes);
        }
    }

    /**
     * 全模块里带 {@code @AutoConfiguration} 的类（全限定名）。
     *
     * <p>⚠️ <b>两步判定</b>：① 源文件里**行首**出现 {@code @AutoConfiguration}
     * （行首 ⇒ javadoc 里的 {@code * @AutoConfiguration} 不会被误命中）；
     * ② 再用<b>反射</b>确认它真的带这个注解（文本可能出现在注释里，注解不会）。
     */
    private static List<String> findAutoConfigurationClasses() throws Exception {
        List<String> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_ROOT)) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".java")) {
                    continue;
                }
                boolean mentioned = false;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    Matcher matcher = AUTO_CONFIG_ANNOTATION.matcher(line);
                    if (matcher.find()) {
                        mentioned = true;
                        break;
                    }
                }
                if (!mentioned) {
                    continue;
                }
                String fqcn = toFqcn(file);
                if (Class.forName(fqcn).isAnnotationPresent(AutoConfiguration.class)) {
                    result.add(fqcn);
                }
            }
        }
        return result;
    }

    /**
     * 被某个 auto-config 的 {@code @Bean} 方法<b>实例化</b>的类（全限定名）。
     *
     * <p>⚠️ 判据 = {@code @Bean} 方法的<b>返回类型</b>（精确，不靠方法名猜）。
     */
    private static Set<String> findBeanInstantiatedClasses(List<String> autoConfigurations) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        for (String fqcn : autoConfigurations) {
            Class<?> type = Class.forName(fqcn);
            for (Method method : type.getDeclaredMethods()) {
                if (method.getAnnotation(Bean.class) == null) {
                    continue;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType.isAnnotationPresent(AutoConfiguration.class)) {
                    result.add(returnType.getName());
                }
            }
        }
        return result;
    }

    /** 文件路径 ⇒ 全限定类名（相对 {@code com/rag/common/} 根）。 */
    private static String toFqcn(Path file) {
        Path relative = MAIN_ROOT.relativize(file);
        StringBuilder sb = new StringBuilder("com.rag.common.");
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            sb.append(relative.getName(i)).append('.');
        }
        String fileName = relative.getName(relative.getNameCount() - 1).toString();
        sb.append(fileName, 0, fileName.length() - ".java".length());
        return sb.toString();
    }

    private static List<String> readImports() throws IOException {
        List<String> lines = Files.readAllLines(IMPORTS, StandardCharsets.UTF_8);
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            result.add(trimmed);
        }
        return result;
    }
}
