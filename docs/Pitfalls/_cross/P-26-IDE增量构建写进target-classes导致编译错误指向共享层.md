# P-26 · **IDE 的增量构建器写进 `target/classes`** ⇒ 编译错误指向**共享层**，真凶是**构建产物被外部覆盖**

> **登记时间**：2026-11-05（`global` 收口域 · 合并工作树时暴露）
> **性质**：**环境面**的坑（构建产物被**非 Maven** 的进程改写 ⇒ 报出来的却是**源码面**的错误）
> **发现者**：合并 comment / favorite 两域实现后首次编译

---

## 一、形态

本机跑着 **Eclipse JDT 语言服务器**（`org.eclipse.jdt.ls.core`）⇒ 它会**就地增量编译**工作区里的 Java 文件。

🔴 **致命的一步**：IDE 的输出目录**恰好就是 Maven 的输出目录** ——
实测 `service/hot/.classpath` 里写着：

```
output="target/classes"
```

⇒ ⇒ **IDE 把自己的 class 文件写进 Maven 的 `target/classes`** ⇒ ⇒ **两套编译器抢同一个目录**。

🔴 **IDE 那套通常不跑 Lombok 的注解处理器**（除非在 IDE 里另配了 processor）
⇒ ⇒ IDE 编出的类**缺**所有 Lombok 生成的成员（构造器 / getter / setter）
⇒ ⇒ **覆盖**掉 Maven 之前编好的同名 class 文件。

⇒ ⇒ 接下来 **Maven 增量编译下游模块**时：下游模块的源没变 ⇒ Maven 认为「已是最新」⇒ **不重编**
⇒ ⇒ 但它链接到的 `common/target/classes/…` **已经是 IDE 写的残缺版**
⇒ ⇒ 报出来的错误是：

```
无法将类 com.rag.common.contract.ApiResponse中的构造器 ApiResponse应用到给定类型;
  需要: 没有参数
  找到: int,java.lang.String,<nulltype>
```

⇒ ⇒ **错误信息指向「共享层的类缺构造器」** ⇒ ⇒ 第一反应必然是「共享层源码坏了 / 合并把共享层改坏了」。

## 二、🔴 为什么它特别危险

1. **错误指向完全无辜的地方** —— 共享层源码里那个类**明明有** `@AllArgsConstructor` ⇒ 报错却像它没有。
2. **它**「**只在有变更时出现**」** —— 没改动的模块不会重编 ⇒ ⇒ 平时全绿 ⇒ ⇒ **一旦动了跨模块的代码就炸** ⇒ ⇒ 极易被误判成「我这次改动引入的回归」。
3. **它是**偶发**的** —— IDE 增量编译的时机取决于文件保存 ⇒ ⇒ **同样两条命令，一次绿一次红** ⇒ ⇒ 极易被误判成 flaky。
4. **重跑不一定好** —— 若 IDE 又在那个时刻写了同一个 class 文件 ⇒ ⇒ 「单独复跑」也会随机复现。

## 三、判定式（三步 · 按序做）

1. **先问「这个类源码里真的缺吗」** ⇒ 打开源码看 ⇒ 若**明明有**（Lombok 注解 / 显式构造器 / 显式 getter）⇒ ⇒ **不是源码问题**。
2. **`javap` 看产物**（不是看源码！）：
   ```bash
   javap -p -cp common/target/classes com.rag.common.contract.ApiResponse
   ```
   ⇒ 若**缺** Lombok 生成的成员（`getCode()` / `setCode()` / 全参构造器）⇒ ⇒ **产物被外部写了** ⇒ ⇒ 凶手通常是 IDE 增量构建器。
3. **让 Maven 重写产物再复跑**：
   ```bash
   mvn -o -pl <被指认的模块> clean compile     # 让 Maven 重新编出「带 Lombok」的类
   mvn -o -pl <真正报错的模块> -am -Dtest='<那条用例>' test
   ```
   ⇒ ⇒ **单独复跑绿** ⇒ ⇒ **坐实是环境面，不是代码面**。

⚠️ **不要因为「报错说共享层坏了」就去改共享层** —— 那是本项目 P-18「为了让红消失去改被测对象」的老毛病换个马甲。

## 四、根治

⇒ ⇒ **把 IDE 的输出目录与 Maven 的 `target/classes` 隔开**（改 `.classpath` 里的 `output=` 指向 `target/eclipse-classes` 之类）
⇒ ⇒ 两套编译器各写各的目录 ⇒ ⇒ 互不覆盖。

⚠️ **`.classpath` / `.project` / `.settings/` 是未跟踪的本地垃圾** ⇒ ⇒ **改它属于「本机环境动作」，不是仓库动作**
⇒ ⇒ 本仓**不为它提交任何文件**；但**必须把它登记下来**（否则下一个人会重复踩）。

## 五、连带发现（同源）

本轮还撞出**同源的另一面**：`tools/gen-file-tree.py` 直接扫文件系统 ⇒ ⇒ 那些**未跟踪的 IDE 垃圾**
（`.settings/` `.classpath` `.factorypath` `.project`）**被渲染进了 12 个域的文件树** ⇒ ⇒ 文件树被污染。
⇒ ⇒ 同一批文件、同一类问题（**「本地产物 / 本机垃圾」与「仓库内容」没有边界**）的两面：
**写侧**（IDE 覆盖构建产物）· **读侧**（生成器把 IDE 垃圾当源码）。

⇒ ⇒ **通用判据**：凡工具/脚本**直接扫文件系统**或**直接读构建输出目录**的地方，
⇒ ⇒ 都要先分清「**哪些是仓库内容、哪些是本机产物**」⇒ ⇒ 本仓的既有事实是：
**`git ls-files` 查得到的才是仓库内容** ⇒ ⇒ IDE 垃圾与 `target/` 同级，都该被排除。

## 六、实测代价（RAG-V1 · 2026-11-05 · 合并工作树轮）

- **现象**：`mvn -o -pl service/comment -am test` ⇒ 编译失败，报「`ApiResponse` 构造器不匹配」
  ⇒ ⇒ 第一反应是「comment 域与共享层的 `ApiResponse` API 不兼容」⇒ ⇒ **方向完全错了**：
  该域的用法与 like / follow / message / favorite **同款**（都调 3 参构造器）⇒ ⇒ 共享层**没有**改过它。
- **坐实**：`javap` ⇒ `ApiResponse.class` **只有无参构造器、没有 getter/setter** ⇒ ⇒ Lombok 没跑
  ⇒ ⇒ `mvn -o -pl common clean compile` ⇒ ⇒ 重编后 `javap` 立刻看到 **3 参构造器 + getter/setter 齐全**
  ⇒ ⇒ 随后**单独复跑**该域 ⇒ ⇒ **9/9 绿** ⇒ ⇒ **坐实为环境面**。
- **旁证**：`service/like/target/classes` 的时间戳与我没构建过的模块同刻 ⇒ ⇒ 有个非 Maven 的进程在写这些目录。
- **根治侧**：生成器已补 IDE 垃圾排除（判据 = **不在版本控制里 ⇒ 不是源码**）。

⇒ ⇒ **同族**：与「『容器起不来』类红属环境面」「『并行 reactor 污染时间敏感读数』」
「『一次环境假红』的判定式」同源 —— 本条补的是「**IDE 与 Maven 抢同一个输出目录**」这一面，
以及它特有的伪装：**错误信息指向共享层源码**。
