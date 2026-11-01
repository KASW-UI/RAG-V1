package com.rag.common.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
 * <p>⚠️ <b>「7 行」这个数字不是约定，是守恒断言</b>：
 * {@code autoconfigure/} 目录下每个 {@code @AutoConfiguration} 类都<b>必须</b>在清单里
 * ⇒ ⇒ 「目录里的类集合 == 清单里的类集合」才是真正的判据；
 * 单纯断言「清单有 7 行」会在有人加了第 8 个 auto-config 却忘了登记时<b>仍然绿</b>。
 */
class AutoConfigurationImportsTest {

    private static final Path IMPORTS = Paths.get(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");

    private static final Path AUTOCONFIGURE_DIR = Paths.get(
            "src/main/java/com/rag/common/autoconfigure");

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
    @DisplayName("守恒断言：autoconfigure/ 目录下的类集合 == imports 清单的类集合")
    void directoryAndImportsFileAgree() throws Exception {
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
        // ⚠️ 两个方向都要比：只比「清单 ⊆ 目录」会漏掉「目录里有类但没登记」
        assertThat(listed).containsExactlyInAnyOrderElementsOf(inDirectory);
        assertThat(listed).hasSize(7);
    }

    @Test
    @DisplayName("imports 清单没有重复行（重复行会让同一个 auto-config 被装配两次）")
    void noDuplicateEntries() throws Exception {
        List<String> listed = readImports();
        assertThat(listed).doesNotHaveDuplicates();
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
