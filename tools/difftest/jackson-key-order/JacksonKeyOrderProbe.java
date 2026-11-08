import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 🔴 **JSON 键序运行时探针**（`global` 收口域 · 2026-11-09「全用 jackson2」决策的字节级证据）。
 *
 * <h2>它回答什么</h2>
 * <p>本仓有 **23 处** {@code @JsonPropertyOrder}。它们在 **B 侧（Jackson 3 + 可变 class）**
 * 是**必需**的（Jackson 3 默认**属性字母序** ⇒ 不钉就与 Go 真源的 struct **声明序**分叉）。
 * <p>⇒ 而 **C 侧是 Jackson 2 + immutable record** ⇒ Jackson 2 对 record 默认按
 * **构造参数（= 声明）序**输出 ⇒ ⇒ **注解到底还有没有作用？**
 *
 * <h2>判据（三条同时成立才算 PASS）</h2>
 * <ol>
 *   <li><b>I2 · 序一致</b>：每个 {@code @JsonPropertyOrder} 的**声明序**
 *       ≡ Jackson 2 的**自然序**（压制该注解后实测得到）⇒ 注解与默认序**一致**；</li>
 *   <li><b>I3 · 字节一致</b>：同一实例在「带注解的 mapper」与「压制注解的 mapper」下
 *       序列化结果**逐字节相同** ⇒ ⇒ **删掉该注解，今天不改任何 wire 字节**；</li>
 *   <li><b>I1 · 不开字母序</b>（由守门 <b>A17</b> 静态负责，本探针不重复判）。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么 I3 成立 ≠ 「注解该删」</h2>
 * <p>I3 证明的是「<b>今天</b>删不改字节」，**不**证明「以后删也不改字节」。
 * <p>⇒ 注解在 C 侧的真正作用是**把顺序显式钉死**：record 组件一旦被重排，
 * 无注解时字节序会**静默漂移**（无编译错误、无测试红灯）⇒ ⇒ 本仓**保留**注解。
 * <p>⇒ 这与 follow 域变异脚本 M1「把注解改成<b>反序</b>」（而非「删注解」）的既有口径一致
 * —— B 侧能靠「删注解」转红，是因为 B 侧是**可变 class**；C 侧是 record，**删注解转不了红**。
 *
 * <h2>用法</h2>
 * <pre>
 *   bash tools/difftest/jackson-key-order/run.sh
 * </pre>
 * <p>⇒ 退出码：<b>0</b> = 全部一致 · <b>1</b> = 有类型不一致（序或字节）· <b>2</b> = 环境未就绪。
 */
public class JacksonKeyOrderProbe {

    /**
     * 压制 {@code @JsonPropertyOrder} 的 introspector。
     *
     * <p>⚠️ **为什么是「继承」而不是「委托」**：{@code JacksonAnnotationIntrospector} 是
     * {@code AnnotationIntrospector} 的完整实现 ⇒ 继承它并**只覆盖一个方法**
     * ⇒ 其余 Jackson 注解（{@code @JsonProperty} / {@code @JsonInclude} / …）**全部照常生效**
     * ⇒ 两个 mapper 的**唯一**差异就是「这条顺序注解」⇒ 对照才干净。
     */
    static final class SuppressOrder extends JacksonAnnotationIntrospector {
        @Override
        public String[] findSerializationPropertyOrder(
                com.fasterxml.jackson.databind.introspect.AnnotatedClass ac) {
            return null;
        }
    }

    /** 带注解的 mapper（= 生产形态的默认行为）。 */
    static final ObjectMapper NORMAL = new ObjectMapper();
    /** 压制注解的 mapper（= 「假如没有这条注解」的世界）。 */
    static final ObjectMapper SUPPRESSED = new ObjectMapper();

    static {
        SUPPRESSED.setAnnotationIntrospector(new SuppressOrder());
    }

    /** 取 Jackson 实际会写出的**属性序**（而不是反射声明序 —— 后者对 POJO 不保证）。 */
    static List<String> orderOf(ObjectMapper m, Class<?> c) {
        List<String> out = new ArrayList<>();
        try {
            JavaType t = m.constructType(c);
            JsonSerializer ser = m.getSerializerProviderInstance().findValueSerializer(t);
            if (ser instanceof BeanSerializerBase bs) {
                java.util.Iterator<PropertyWriter> it = bs.properties();
                while (it.hasNext()) {
                    out.add(it.next().getName());
                }
            } else {
                out.add("<not-bean:" + ser.getClass().getSimpleName() + ">");
            }
        } catch (Throwable t) {
            out.add("<err:" + t.getClass().getSimpleName() + ">");
        }
        return out;
    }

    /** 尽力造一个实例：先无参构造，再 record 规范构造（默认值）。 */
    static Object newInstance(Class<?> c) {
        try {
            Constructor<?> c0 = c.getDeclaredConstructor();
            c0.setAccessible(true);
            return c0.newInstance();
        } catch (Throwable ignore) {
            // 无无参构造 ⇒ 走 record 分支
        }
        if (c.isRecord()) {
            RecordComponent[] rc = c.getRecordComponents();
            Class<?>[] ps = new Class<?>[rc.length];
            Object[] vs = new Object[rc.length];
            for (int i = 0; i < rc.length; i++) {
                ps[i] = rc[i].getType();
                vs[i] = defaultOf(ps[i]);
            }
            try {
                Constructor<?> k = c.getDeclaredConstructor(ps);
                k.setAccessible(true);
                return k.newInstance(vs);
            } catch (Throwable ignore) {
                // 造不出来 ⇒ 字节级比对跳过（序比对仍有效）
            }
        }
        return null;
    }

    static Object defaultOf(Class<?> t) {
        if (!t.isPrimitive()) {
            return null;
        }
        if (t == boolean.class) return Boolean.FALSE;
        if (t == char.class) return (char) 0;
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        return null;
    }

    static String bytes(ObjectMapper m, Object v) {
        try {
            return m.writeValueAsString(v);
        } catch (Throwable t) {
            return "<err:" + t.getClass().getSimpleName() + ">";
        }
    }

    static void walk(Path root, TreeSet<String> out) throws Exception {
        if (!Files.isDirectory(root)) {
            return;
        }
        Files.walk(root)
                .filter(p -> p.toString().endsWith(".class"))
                .forEach(p -> {
                    String rel = root.relativize(p).toString();
                    String cn = rel.substring(0, rel.length() - 6).replace(File.separatorChar, '.');
                    out.add(cn);
                });
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法: JacksonKeyOrderProbe <classes-dir> [<classes-dir> …]");
            System.exit(2);
        }
        TreeSet<String> all = new TreeSet<>();
        for (String a : args) {
            walk(Paths.get(a), all);
        }

        Map<String, Class<?>> withAnn = new LinkedHashMap<>();
        int loadFail = 0;
        for (String cn : all) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, JacksonKeyOrderProbe.class.getClassLoader());
            } catch (Throwable t) {
                loadFail++;
                continue;
            }
            if (c.getAnnotation(JsonPropertyOrder.class) != null) {
                withAnn.put(c.getName(), c);
            }
            try {
                for (Class<?> n : c.getDeclaredClasses()) {
                    if (n.getAnnotation(JsonPropertyOrder.class) != null) {
                        withAnn.put(n.getName(), n);
                    }
                }
            } catch (Throwable ignore) {
                // 拿不到嵌套类就跳过
            }
        }
        List<Class<?>> targets = new ArrayList<>(withAnn.values());
        targets.sort(Comparator.comparing(Class::getName));

        System.out.println("=== 扫描：class=" + all.size() + " · 加载失败=" + loadFail
                + " · 带 @JsonPropertyOrder 的类型=" + targets.size() + " ===");
        System.out.println();

        int consistent = 0;
        int inconsistent = 0;
        int byteEq = 0;
        int byteDiff = 0;
        int byteSkipped = 0;
        List<String> bad = new ArrayList<>();

        for (Class<?> c : targets) {
            String[] decl = c.getAnnotation(JsonPropertyOrder.class).value();
            List<String> declared = List.of(decl);
            List<String> natural = orderOf(SUPPRESSED, c);
            List<String> honored = orderOf(NORMAL, c);

            Object inst = newInstance(c);
            String bN = inst == null ? null : bytes(NORMAL, inst);
            String bS = inst == null ? null : bytes(SUPPRESSED, inst);
            String byteState;
            if (bN == null || bS == null) {
                byteState = "SKIP(造不出实例)";
                byteSkipped++;
            } else if (bN.equals(bS)) {
                byteState = "BYTE-EQ";
                byteEq++;
            } else {
                byteState = "BYTE-DIFF";
                byteDiff++;
            }

            boolean orderSame = declared.equals(natural);
            boolean honoredSame = declared.equals(honored);
            boolean ok = orderSame && honoredSame && !"BYTE-DIFF".equals(byteState);
            if (ok) {
                consistent++;
            } else {
                inconsistent++;
                bad.add(c.getName());
            }

            System.out.println((ok ? "  ✅ " : "  ❌ ") + c.getName());
            System.out.println("       declared = " + declared);
            System.out.println("       natural  = " + natural
                    + (orderSame ? "   ← 与声明序一致" : "   ← ⚠️ 与声明序【不一致】"));
            System.out.println("       honored  = " + honored
                    + (honoredSame ? "   ← 与声明序一致" : "   ← ⚠️ 与声明序【不一致】"));
            System.out.println("       bytes    = " + byteState
                    + (inst == null ? "" : "   （样例：" + trunc(bN) + "）"));
        }

        System.out.println();
        System.out.println("=== SUMMARY: total=" + targets.size()
                + " consistent=" + consistent + " inconsistent=" + inconsistent
                + " byteEq=" + byteEq + " byteDiff=" + byteDiff + " byteSkipped=" + byteSkipped + " ===");
        if (inconsistent > 0) {
            System.out.println("=== 不一致的类型：" + bad);
            System.out.println("=== 结论：❌ 有类型的「注解序 ≠ Jackson 2 自然序」"
                    + " ⇒ ⇒ 删注解会改 wire 字节 ⇒ 注解是【承重】的 ⇒ 不得删");
            System.exit(1);
        }
        System.out.println("=== 结论：✅ 全部一致 ⇒ 注解在 C 侧是【显式钉子】"
                + "（删掉今天不改字节，但组件被重排时会静默漂移 ⇒ 保留）");
        System.exit(0);
    }

    static String trunc(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
