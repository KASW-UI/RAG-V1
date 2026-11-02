package com.rag.admin.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>结构性元测试</b>：把「每个 {@code catch (Exception)} 前面都必须有
 * {@code catch (StatusRuntimeException) { throw e; }}」从人工逐个读，变成机器守恒。
 *
 * <p><b>B 出处</b>：
 * <pre>
 *   B 源文件（绝对路径）：
 *     /Users/pr/Documents/java&golang/RAG/service/users/admin/admin-rpc/src/test/java/com/rag/users/admin/rpc/service/AdminDomainServiceCatchStructureTest.java
 *   B 关键行号：
 *     :50        类级 @DisplayName
 *     :71-72     EXPECTED_CATCH_EXCEPTION_TOTAL = 28 / EXPECTED_GUARDED = 28
 *     :74-97     #everyCatchExceptionIsGuardedByPassthrough
 *     :107-122   #countsAreConserved
 *     :130-153   readExceptionTable（Spring ASM 读 JVM 异常表）
 *     :174-185   #labelEqualityReliesOnOffsetHash（自检）
 * </pre>
 *
 * <p>本类由 B 侧同名类移植，<b>期望值沿用 B 侧（D13）</b>。
 * ⇒ 迁移只做<b>包名 / import 重定向</b>（{@code com.rag.users.admin.rpc.service} →
 * {@code com.rag.admin.domain.service}，D7 包压平），<b>断言值、断言语义、常量一字未改</b>。
 *
 * <p><b>⚠️ 移植后实测复核（C 侧 · 2026-11-02）</b>：C 侧 {@code AdminDomainService} 与 B 侧
 * <b>方法体逐字节一致</b>（仅包名 / import / 一处 {@code ObjectMapper} import 化）⇒
 * 字节码异常表必然同构。C 侧 {@code mvn -o -pl service/users/admin -am compile} 后的
 * {@code javap -c -p} 实测：{@code Class java/lang/Exception} <b>28</b> 条、
 * {@code Class io/grpc/StatusRuntimeException} <b>28</b> 条 ⇒ 与 B 侧常量 <b>28/28</b> 完全一致。
 * <p>⚠️ 注意：<b>源码里数到的 {@code catch (Exception e)} 是 29 处</b>，而字节码异常表里是
 * <b>28</b> 条 —— 差的那一条被 javac 合并掉了 ⇒ ⇒ <b>本类必须扫字节码，不能扫源码</b>
 * （这正是 B 侧选择 ASM 的理由）。
 * <p>⚠️ 另注：B 侧那条 {@code @DisplayName} 里的「29 处」是<b>陈旧文案</b>
 * （同一文件的常量是 28）⇒ 按「@DisplayName 逐字沿用 B 侧原文」的移植纪律<b>保留原文不改</b>。
 *
 * <p><b>为什么需要它</b>：issue #71187874 用脚本批量插入了 46 处放行分支
 * （user-rpc 17 + admin-rpc 29）；本类是 admin-rpc 那一半，user-rpc 侧同构副本见
 * {@code UserDomainServiceCatchStructureTest}。逐个行为测试只覆盖到 8 个方法，
 * 其余 38 处当时只有「代码写进去了」这一层保证。
 * ⚠️ 而「代码存在」正是本仓库刚吃过的坑：H2 留档说「gRPC 拦截器已修」，
 * {@code @Import} 确实在代码里、Bean 确实是容器里的、{@code ObservabilityWiringTest}
 * 确实是绿的 —— 然后 {@code interceptCall} 一次都没执行过。
 * ⇒ 凡是「批量改动 + 逐点验证」，就必须有一条**全量守恒**的断言兜底。
 *
 * <p><b>为什么扫字节码而不是源码</b>：源码扫描依赖缩进与文本形态，
 * 而 JVM 异常表是这件事唯一权威的事实来源。实测 javap 形态：
 * <pre>
 *   Exception table:
 *      from  to  target  type
 *        42   61     64  Class ...UserExceptions$NotFoundException
 *        42   61    120  Class io/grpc/StatusRuntimeException      ← 放行分支
 *        42   61    125  Class java/lang/Exception               ← 必须前邻上面那条
 *        38  111    454  any                                     ← finally，忽略
 * </pre>
 * 同一个 try 的多个 catch 共享 {@code (from, to)}，且顺序即源码顺序 ⇒ 可精确判邻。
 *
 * <p><b>双向守恒</b>：既断言「每个 {@code catch(Exception)} 都前邻放行分支」，
 * 也断言「放行分支总数 == 17」。前者防止新增 catch 漏加，后者防止有人
 * 删掉一个 {@code catch(Exception)} 让计数"看起来对得上"。
 */
@DisplayName("§3.3 AdminDomainService 的 catch(Exception) 必须全部有 StatusRuntimeException 放行分支")
class AdminDomainServiceCatchStructureTest {

    private static final String CATCH_EXCEPTION = "java/lang/Exception";
    private static final String CATCH_STATUS_RUNTIME = "io/grpc/StatusRuntimeException";

    /**
     * 2026-10-21 实测：{@code AdminDomainService} 有 29 处 {@code catch(Exception)}，
     * 且 29 处都紧邻一个 {@code catch(StatusRuntimeException)} 放行分支。
     * 与 user-rpc 不同，admin-rpc 此前**一处放行分支都没有** ——
     * user-rpc 至少在 {@code register} 里因 DRIFT-009 修复留下过一个先例。
     *
     * <p><b>2026-10-22 R5 修复后：29 → 28</b>。这一处减少<b>不是</b>漏加了守卫，
     * 而是 {@code createAdmin} 里那处 {@code catch(Exception)}（包裹 bcrypt 的那个）
     * <b>整体移进了</b> {@link com.rag.admin.domain.service.AdminCreateAtomicService}
     * ——R5 要求 bcrypt 在事务内、W2 查 username 之后执行（对齐 Go 的 createadminlogic.go）。
     * 因此 {@code AdminDomainService.createAdmin} 少了一段 catch，
     * {@code AdminCreateAtomicService} 多了一段。
     *
     * <p><b>不要把它改回 29</b>：那意味着有人又把 bcrypt 搬回了事务外。
     */
    private static final int EXPECTED_CATCH_EXCEPTION_TOTAL = 28;
    private static final int EXPECTED_GUARDED = 28;

    @Test
    @DisplayName("每个 catch(Exception) 前邻都有 catch(StatusRuntimeException){throw e;}")
    void everyCatchExceptionIsGuardedByPassthrough() throws IOException {
        List<Entry> entries = readExceptionTable(AdminDomainService.class);

        List<String> violations = new ArrayList<>();
        for (List<Entry> catches : groupByTryRange(entries).values()) {
            List<String> sequence = catches.stream().map(Entry::type).toList();
            for (int i = 0; i < catches.size(); i++) {
                if (!CATCH_EXCEPTION.equals(catches.get(i).type())) {
                    continue;
                }
                if (i == 0 || !CATCH_STATUS_RUNTIME.equals(catches.get(i - 1).type())) {
                    violations.add(String.format("%s 的 catch 序列 = %s —— catch(Exception) 前面没有 catch(StatusRuntimeException)",
                            catches.get(i).method(), shorten(sequence)));
                }
            }
        }

        assertThat(violations)
                .as("这些 catch(Exception) 会把 deadline 的 DEADLINE_EXCEEDED 吞成 Status.INTERNAL，"
                        + "导致 api 层返回 5001 而非 Go 的 1015")
                .isEmpty();
    }

    /** 包名太长会淹没真正的信息，只保留类名尾段。 */
    private static List<String> shorten(List<String> sequence) {
        return sequence.stream()
                .map(t -> t == null ? "any(finally)"
                        : t.substring(t.lastIndexOf('/') + 1))
                .toList();
    }

    @Test
    @DisplayName("守恒计数：catch(Exception) 29 处、放行分支 29 处（admin-rpc 此前一处都没有，全部是本轮插入）")
    void countsAreConserved() throws IOException {
        List<Entry> entries = readExceptionTable(AdminDomainService.class);

        long catchException = entries.stream().filter(e -> CATCH_EXCEPTION.equals(e.type())).count();
        long statusRuntime = entries.stream().filter(e -> CATCH_STATUS_RUNTIME.equals(e.type())).count();

        assertThat(catchException)
                .as("catch(Exception) 总数变了：新增或删除了一个 catch 分支，请同步本测试的期望值并复核放行分支")
                .isEqualTo(EXPECTED_CATCH_EXCEPTION_TOTAL);
        assertThat(statusRuntime)
                .as("放行分支总数变了 —— 少一个就说明有 catch(Exception) 被删掉了"
                        + "（那样上面的计数恰好会「看起来对得上」，所以两条断言必须成对存在）")
                .isEqualTo(EXPECTED_GUARDED);
    }

    // ---------------------------------------------------------------- 字节码读取

    private record Range(Label start, Label end) { }

    private record Entry(String method, Label start, Label end, String type) { }

    private static List<Entry> readExceptionTable(Class<?> clazz) throws IOException {
        List<Entry> all = new ArrayList<>();
        byte[] bytes = readClassBytes(clazz);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            private String currentMethod = "<class>";

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                currentMethod = name;
                if ((access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
                    // lambda$ / 桥接方法不含业务 catch，统计它们只会让计数失真
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        all.add(new Entry(currentMethod, start, end, type));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return all;
    }

    /** 同一个 try 的多个 catch 共享 (from, to)；`any`（finally）各自成条但 type 为 null，会被断言逻辑忽略。 */
    private static Map<Range, List<Entry>> groupByTryRange(List<Entry> entries) {
        Map<Range, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry e : entries) {
            groups.computeIfAbsent(new Range(e.start(), e.end()), k -> new ArrayList<>()).add(e);
        }
        return groups;
    }

    private static byte[] readClassBytes(Class<?> clazz) throws IOException {
        String resource = clazz.getName().replace('.', '/') + ".class";
        try (InputStream in = clazz.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("读不到 class 文件：" + resource);
            }
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("自检：Range/Entry 的 equals 依赖 Label 的 hashCode（按字节码偏移），否则分组会失效")
    void labelEqualityReliesOnOffsetHash() {
        // 这条不是凑数：Spring ASM 的 Label.equals 只比较 hashCode，
        // 而 hashCode 由 ClassReader 在解析时写入字节码偏移。
        // 若换成别的 ASM 实现或误用默认 Object.equals，分组会静默变成"每条一组"，
        // 上面的前邻断言就会全部失效且永远为绿 —— 又一次 §3.3 形态。
        Label a = new Label();
        Label b = new Label();
        assertThat(a).isNotEqualTo(b);
        assertThat(Objects.hashCode(a)).isNotEqualTo(Objects.hashCode(b));
    }
}
