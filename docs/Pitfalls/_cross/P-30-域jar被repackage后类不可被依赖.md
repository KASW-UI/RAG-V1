# P-30 · 域 jar 被 repackage ⇒ 它的类在编译期**不可被依赖**（BOOT-INF 不是标准 jar 布局）

> 档位：跨模块（`_cross/`）· 首次登记 **2026-11-28** · 触发场景：**S1 · 新增 boot 模块依赖域模块**

---

## 一、现象

新增的 `service/boot/task/` 模块依赖 `service/task/`（域模块）⇒ 在 boot 模块的**测试**里写：

```java
import com.rag.task.controller.TaskController;
```

⇒ javac 报：

```
[ERROR] .../TaskServiceApplicationAssemblyTest.java:[55,58] 程序包com.rag.task.controller不存在
[ERROR] .../TaskServiceApplicationAssemblyTest.java:[57,54] 程序包com.rag.task.mapper不存在
```

⇒ 但**域模块自己编译得好好的** ⇒ `service/task/target/classes/` 里明明有那些 class。

---

## 二、根因（实测，不是推断）

```bash
python3 -c "
import zipfile
with zipfile.ZipFile('service/task/target/task-service-1.0-SNAPSHOT.jar') as z:
    n = z.namelist()
    print('BOOT-INF/classes/com/rag/task/ 下:', len([x for x in n if x.startswith('BOOT-INF/classes/com/rag/task/')]))
    print('顶层 com/ 下:', len([x for x in n if x.startswith('com/')]))
"
```
⇒ 输出：`BOOT-INF/classes/com/rag/task/ 下: 69` · `顶层 com/ 下: 0`

**`spring-boot-maven-plugin:repackage` 把域模块的产物重写成了可执行 Boot jar
⇒ 业务类被搬到 `BOOT-INF/classes/` 下
⇒ ⇒ 那**不是**标准 jar 布局 ⇒ ⇒ javac / Maven 把它当依赖时**找不到任何类**。**

⇒ ⚠️ 而且是**静默**的：依赖解析**成功**（jar 确实在）⇒ 只是**类不在**。

---

## 三、为什么没拦住

| 环节 | 为什么没拦住 |
|---|---|
| 域模块自身 | 它是**可执行 jar 的主人** ⇒ 自己用 `BOOT-INF/classes/` 没问题 ⇒ 域内一切正常 |
| 域模块的测试 | 测试类在**同一模块**内 ⇒ 走 `target/classes`（**未经 repackage 的普通目录**）⇒ 拿得到类 ⇒ 全绿 |
| 守门脚本 | 源码文本级扫描 ⇒ 判不了 jar 布局 |
| ⚠️ **最典型的假绿形态** | 「**域测试全绿 + 域 jar 能 repackage**」⇒ ⇒ **看起来一切正常**，但**一旦有人依赖它**就炸 |

---

## 四、解法（已落地）

**分工**：**域 = 库（产普通 jar）** · **boot = 可执行单元（产可执行 Boot jar）**
⇒ 这是 Spring Boot 多模块的**标准做法**。

具体：
1. 10 个域模块的 `pom.xml` **移除** `spring-boot-maven-plugin`（域的 `main` 方法仍在，只是 jar 不可 `java -jar` 跑）。
2. 5 个 boot 模块**保留** `spring-boot-maven-plugin` ⇒ 只有它们产可执行 jar。
3. **连带**：凡指向「域 jar」的路径要改指 boot jar
   ⇒ 本仓已改 `tools/difftest/{favorite,follow,hot,like,task,users-user}/run.sh` 的 `C_JAR`（6 处）。

---

## 五、⚠️ 元教训

1. **「域测试全绿」不能证明「这个模块可被别人依赖」** —— 域测试走的是 `target/classes`，
   **和 jar 的布局是两回事** ⇒ ⇒ 依赖方一旦引入就炸。
2. **凡是要被依赖的模块，产物形态必须单独验证** ⇒ 判据 = 「**依赖方的编译能引用到它的类**」，
   不是「它自己能编译」也不是「它能 repackage」。
3. ⇒ **可复用的自检**：`unzip -l <jar> | grep "^.* BOOT-INF/classes"` ⇒ 若类在 `BOOT-INF/classes/` 下
   ⇒ ⇒ **这个 jar 不可作依赖**。

---

## 四、⚠️ 追加（2026-11-28 晚）：**改 artifactId ⇒ `~/.m2` 里会同时躺着新旧两个坐标**

S1 把 `task-service` 改名成 `task-domain`（腾位置给 boot 模块）⇒ ⇒
`~/.m2/repository/com/rag/` 下**同时存在** `task-service/`（旧）**与** `task-domain/`（新）
⇒ ⇒ 旧坐标里的 jar **还是改动前的那份字节码** ⇒ ⇒ 单模块 `-pl` 跑时会捡到它
⇒ ⇒ 报错指向**已经修好的代码**（实测：`no 'KafkaListenerContainerFactory' with id
'kafkaListenerContainerFactory' was found` —— 而源码里早就改成 `taskKafkaListenerContainerFactory` 了）。

🔴 **可复用的判据**：`unzip -p ~/.m2/repository/com/rag/<artifact>/1.0-SNAPSHOT/<artifact>-1.0-SNAPSHOT.jar <类路径> | strings | grep <旧名字>`
⇒ ⇒ **直接看 jar 里的字节码**，别看源码。

⇒ ⇒ **修法**：**改动 artifactId 或域 jar 内容后，先 `mvn clean install` 再单模块跑**；
⇒ ⇒ **旧坐标的 jar 建议手工清掉**（否则它会长期在 `~/.m2` 里误导单模块跑）。

⇒ ⇒ **元教训**：**改名 ⇒ 旧名会变成「影子依赖」** ⇒ ⇒ 排查「明明改了却还是旧行为」时，
**第一件事是确认跑的是不是新字节码**，不是先怀疑代码。
