# P-27 · **IDE 的增量构建器会「擦除并重写」Maven 的输出目录** ⇒ jar 被**静默打残** ⇒ 报错指向**无辜的下游域**

> **登记时间**：2026-11-05（`global` 收口域 · 合并工作树时暴露）
> **性质**：**环境面**的坑（构建产物被**非 Maven** 的进程改写 ⇒ 报出来的却是**源码面 / 下游域**的错误）
> **发现者**：合并 hot / task / comment / favorite 四域实现后，首次跑 `mvn -o clean verify`
> **同源**：**P-26**（同一根因的另一张脸）—— 本条把「**擦除**」这一面补齐 ⇒ 三张脸同源，见 §六

---

## 一、形态（**同一个根因，三张不同的脸**）

本机跑着 **Eclipse JDT 语言服务器**（Neovim 的 `jdtls` 客户端）⇒ 它的 `.classpath` 把输出目录写成
**`output="target/classes"`** ⇒ ⇒ **IDE 与 Maven 抢同一个输出目录**。

⇒ 它对 `target/classes` 做的是**「先整体擦除、再整体重写」**（Eclipse 增量构建器的默认行为）⇒ ⇒ **三个后果**：

| 脸 | 机制 | 表面症状 | 错误指向 |
|---|---|---|---|
| **①**（**P-26 已登记**） | IDE 编出的 class **不跑 Lombok** ⇒ 覆盖掉 Maven 编好的同名 class | 下游模块**不重编**却链接到**缺构造器 / 缺 getter** 的类 | 「**共享层**某类坏了」 |
| **②**（**本条**） | IDE 擦除 + 重写**发生在「Maven 编译完」与「jar 插件打包」之间** | **jar 只装进一部分 class** ⇒ **零报错** ⇒ 下游域**编译期**才炸 | 「**下游某个域**的代码有问题」 |
| **③**（**本条**） | IDE 在 `target/` 内的活动撞上 **Maven 插件的临时目录清理** | `protobuf-maven-plugin` 报「Unable to clean up temporary proto file directory」⇒ **构建在 proto 模块夭折** | 「proto 模块的 pom / .proto 有问题」 |

⇒ ⇒ **三张脸都指向无辜的地方**（共享层 / 下游域 / proto 契约模块）⇒ ⇒ **第一反应几乎必然是错的方向**。

## 二、🔴 脸② 的实测链（2026-11-05 · RAG-V1 · 合并工作树轮）

### 2.1 表面症状

```
[ERROR] /…/service/article/src/main/java/com/rag/article/client/MessageRpcClient.java:[3,29] 找不到符号
[ERROR]   符号:   类 MessageServiceGrpc
[ERROR] /…/service/article/src/main/java/com/rag/article/grpc/ArticleGrpcService.java:[101,59] 程序包ArticleServiceGrpc不存在
```

⇒ 第一反应 = 「**article 域的代码坏了**」或「**proto 模块生成失败了**」⇒ ⇒ **两个方向都不对**。

### 2.2 关键一步：**不要看源码，去看产物**

```bash
# ① jar 里装了多少 class？
unzip -Z1 proto/target/proto-1.0-SNAPSHOT.jar | grep -c '\.class$'      # ⇒ 221
# ② 输出目录里本来该有多少？
find proto/target/classes -name '*.class' | wc -l                          # ⇒ 760
# ③ gRPC 桩还在吗？
unzip -Z1 proto/target/proto-1.0-SNAPSHOT.jar | grep -c 'Grpc\.class$'    # ⇒ 0
```

⇒ ⇒ **jar 只装了 221 / 760 个 class，且一个 gRPC 桩都没有** ⇒ ⇒
**`jar` 插件在 IDE 擦除输出目录的「中间态」上打了包** ⇒ ⇒
**下游 article 编译失败 ⇒ 真凶是 IDE，不是 article 的代码**。

### 2.3 决定性取证：**在 Maven 不在场时采样输出目录**

```bash
# ① Maven 不跑 ⇒ 数字稳不稳？
for i in $(seq 1 24); do
  echo "$(find proto/target/classes -name '*.class' | wc -l)"; sleep 0.5
done
# ⇒ 恒为 760（24 次采样无波动）⇒ ⇒ 「不是 Maven 自己在改」

# ② 构建过程中采样 ⇒ 抓到了「0」
#    t≈1.6s  ⇒    0   ← Maven 的 clean（正常）
#    t≈4.0s  ⇒   26   ┐
#    t≈4.4s  ⇒  159   │ javac 编译 321 个源文件（正常）
#    t≈5.2s  ⇒  558   │
#    t≈5.6s  ⇒  760   ┘
#    t≈6.0s  ⇒ jar 内 = 760   ← jar 插件装满（这次侥幸装满）
#    t≈9.6s  ⇒    0   ← ⚠️⚠️ Maven 此时【已经打包完】⇒ 有人在擦！
#    t≈10.0s ⇒  760   ← 又被写回来
```

⇒ ⇒ **那个 `0` 出现在 jar 打好之后** ⇒ ⇒ **Maven 不会在打包后删自己的输出目录**
⇒ ⇒ **写入者是第三方** ⇒ ⇒ `pgrep -fl jdtls|eclipse` 把它找出来：

```
/usr/bin/java … org.eclipse.jdt.ls.core.product … -data /Users/pr/.cache/nvim/jdtls/RAG-V1/workspace
```

⇒ ⇒ **IDE 的 `.classpath` 里 `output="target/classes"`** ⇒ ⇒ **它与 Maven 抢同一个目录** ⇒ ⇒ **坐实**。

## 三、🔴 脸③ 的实测链（同一根因的另一面）

`mvn -o clean verify` 在 **proto 模块**夭折：

```
[ERROR] Failed to execute goal org.xolstice.maven.plugins:protobuf-maven-plugin:0.6.1:compile-custom
        on project proto: Unable to clean up temporary proto file directory:
        Directory …/proto/target/protoc-dependencies/<hash>/google/api unable to be deleted.
```

⇒ ⇒ **失败的是「清理临时目录」这一步**（不是生成那一步）⇒ ⇒ **产物已经生成好了** ⇒ ⇒ **与 `.proto` 内容、与代码无关**。
⇒ ⇒ **失败位置会漂移**（实测一次落在 `google/api`、一次落在 `google`）⇒ ⇒ **竞态特征**。

⇒ ⚠️ **遗留目录里全是正常的 `.proto`**（62 个文件）⇒ 权限 / ACL / flags 全正常、无符号链接、无只读文件
⇒ ⇒ **不是权限问题** ⇒ ⇒ 是**「枚举完之后、rmdir 之前，又有人往里写了」**。

## 四、判定式（四步 · 按序做）

1. **先看失败面在哪**：
   - 「找不到符号 `XxxGrpc` / `XxxServiceGrpc`」⇒ ⇒ **查 proto jar 的完整性**
     （`unzip -Z1 proto/target/proto-*.jar | grep -c '\.class$'` **对比** `find proto/target/classes -name '*.class' | wc -l`）
     ⇒ ⇒ **两个数不等 ⇒ jar 被打残** ⇒ ⇒ **不是下游域的错**；
   - 「Unable to clean up temporary proto file directory」⇒ ⇒ **是清理步骤**（产物已生成）⇒ ⇒ **与代码无关**。
2. **看产物，不看源码**（同 P-26 的「源码里真的缺吗」）。
3. **找写入者**：在 **Maven 不在场**时反复采样 `find <模块>/target/classes -name '*.class' | wc -l`
   ⇒ ⇒ **数字也会变 ⇒ 有第三方写入者** ⇒ ⇒ `pgrep -fl jdtls|eclipse`。
4. **对照实验**：把同一命令放到**一棵没有 IDE 配置的检出**（本仓的 `.classpath` **不进版本控制** ⇒ **新工作树天然隔离**）里跑
   ⇒ ⇒ **绿 ⇒ ⇒ 坐实是「本机 IDE 在抢 `target/`」，不是代码**。

⚠️ **不要因为「报错说下游域 / proto 坏了」就去改那儿的代码** —— 那是 P-18 老毛病换马甲。

## 五、根治（✅ **2026-11-09 已落地** · 用户拍板「改 IDE 输出目录，与 `target/` 隔开」）

⇒ ⇒ **把 IDE 的输出目录与 `target/` 隔开** ⇒ ⇒ 两套编译器各写各的目录 ⇒ ⇒ **互不覆盖、互不擦除**。

### 5.1 ⚠️ **订正**：初版写的「`target/eclipse-classes`」是**错的**

> 📌 **2026-11-09 订正**：本节初版建议把输出目录改成 **`target/eclipse-classes`** ⇒
> ⇒ **那个值仍然在 `target/` 里面** ⇒ ⇒ **`mvn clean` 照样把它连根擦掉** ⇒ ⇒ **不满足「与 `target/` 隔开」** ⇒ ⇒ **白改**。
> ⇒ ⇒ 正确取值必须**在 `target/` 之外**。

### 5.2 输出目录到底由谁决定（**实测 · 不是 jdtls 设置项**）

| 项 | 结论 |
|---|---|
| **真正的杠杆** | **各模块 `.classpath`** 里的 `<classpathentry kind="output" path="…"/>` ＋ 源目录条目上的 `output="…"`（**13 份**：`common` · `proto` · `service/{article,comment,favorite,follow,hot,like,message,task}` · `service/users/{common,user,admin}`） |
| **不是** | **jdtls 的 `java.project.outputPath`** —— 它只管「**隐形工程**」（`jdt.ls-java-project`），管不到 Maven 工程 |
| **不是** | **nvim 的 LSP 配置** —— `~/.config/nvim/lua/plugins/jdtls.lua` **无 `settings` 表** ⇒ 全默认 |
| **m2e 会不会改回去？** | 🟢 **实测不会** —— 2026-11-09 改完后 jdtls 触发了「Updated service / user-service / task-service / users-common / users」的一轮**重新导入** ⇒ ⇒ **`.classpath` 里的输出目录未被重置**（`kind="output"` 条目**不带** `maven.pomderived` 标记 ⇒ m2e 不接管它） |

### 5.3 ✅ 落地动作

```bash
bash tools/ide/isolate-ide-output-dir.sh            # 施加（幂等 · 13 份）
bash tools/ide/isolate-ide-output-dir.sh --verify   # 只体检（逐份报 ✅/❌）
bash tools/ide/isolate-ide-output-dir.sh --revert   # 还原回 target/classes
```

⇒ 取值：`target/classes` → **`.jdtls-build/classes`** · `target/test-classes` → **`.jdtls-build/test-classes`**（**各模块目录下**）
⇒ 配套：`.gitignore` 加 `.jdtls-build/` · `tools/gen-file-tree.py` 的 `SKIP` / `SKIP_DIRS` 加 `.jdtls-build`（否则**文件树会被本机环境产物污染** ⇒ `check-docs-vs-tree.py` 对不上）

### 5.4 ✅ 效果实测（**标记法** · 决定性）

```bash
mkdir -p service/article/.jdtls-build/classes
echo MARKER > service/article/.jdtls-build/classes/_isolation_marker.class
mvn -o -q clean
# ⇒ target/classes：21 个 class → 0 个 class      （Maven 照常清理自己的目录）
# ⇒ .jdtls-build/classes/_isolation_marker.class   【存活】  ← ⇒ mvn clean 不碰它
```

⇒ ⇒ **擦除机制被根除**：IDE 的产物与 Maven 的产物**物理分属两个目录** ⇒ ⇒ `mvn clean` **够不着**它。

⚠️ **尚未现场验证的一点**：jdtls **下一次真正编译**时产物落到 `.jdtls-build/classes`
（本次只验证到「`.classpath` 已改 + `mvn clean` 不碰该目录」）⇒
⚠️ **触发条件是编辑器的 `didChange` 通知**（单纯 `touch` 文件**不触发** ⇒ 本轮无法在无编辑器在场时强制触发）⇒ ⇒ **等下一次 IDE 编译自然验证**。
⇒ ⚠️ **已知残留（不修）**：`org.eclipse.jdt.apt.core.prefs` 的 `genSrcDir=target/generated-sources/annotations`
与 `.classpath` 里指向 `target/generated-sources/**` 的**源目录条目**仍在 `target/` 内
⇒ ⇒ `mvn clean` 会删掉它们 ⇒ jdtls 会重新生成（APT 自己管）⇒ **只影响 IDE 瞬时状态，不影响 Maven 产物**。

⚠️ **`.classpath` / `.project` / `.settings/` 是未跟踪的本地垃圾** ⇒ ⇒ **改它属于「本机环境动作」，不是仓库动作**
⇒ ⇒ 本仓**不为它提交任何文件**；但**必须把它登记下来**（否则下一个人会重复踩）⇒ **本节即登记**，
**可重跑的脚本** ⇒ `tools/ide/isolate-ide-output-dir.sh`（**进版本控制** ⇒ 别人也能用）。

## 六、⇒ 与 P-26 的关系（**同源三张脸**）

| | P-26 | **P-27（本条）** |
|---|---|---|
| IDE 对 `target/classes` 做了什么 | **覆盖**单个 class（**不跑 Lombok** ⇒ 缺构造器 / getter） | **整体擦除 + 重写** |
| 表面症状 | 下游链接到**残缺类** | ① **jar 静默残缺** ② **插件清理失败** |
| 错误指向 | 共享层 | **下游域** / **proto 契约模块** |
| 判定式 | ① 源码里真的缺吗 ② `javap` 看产物 ③ `clean compile` 后单独复跑 | **① 看 jar 完整性 ② 看失败的是生成还是清理 ③ 采样找写入者 ④ 换路径对照** |

⇒ ⇒ **一句话**：**IDE 与 Maven 抢同一个输出目录 ⇒ 产物的「完整性」不再由 Maven 独占保证**
⇒ ⇒ **三张脸都表现为「报出来的错误指向无辜的地方」**。

## 七、⇒ 取读数的纪律（**本条新增的操作面**）

**凡要在本机取「全量构建」读数，先确认没有 IDE 增量构建器在跑**：

```bash
pgrep -fl jdtls|eclipse     # 有输出 ⇒ 先停它，或换到隔离工作树去构建
```

⇒ ⇒ **要么先停 IDE**（⚠️ 本仓的 Neovim `jdtls` 是用户在用的，杀掉会影响其编辑器 ⇒ **须由用户决定**），
**要么把构建放到一棵没有 IDE 配置的工作树**（`.classpath` 不进版本控制 ⇒ **新工作树天然隔离**）
⇒ ⇒ **「从零验收」本来就该在隔离工作树里做**（它同时验证了「**已提交的树**能独立构建」）。

⇒ ⇒ ⚠️ **反过来的提醒**：**全绿的构建不等于产物是完整的** —— 脸② 的 jar 是在**没有一行报错**的情况下被打残的
⇒ ⇒ **要断言产物完整性 ⇒ 必须单独量一次**（数 jar 里的 class 数 / 数 `*Grpc.class`）。

## 八、⇒ 同族

- 与 **P-18**（「为了让红消失去改被测对象」）同族 —— 本条是它的**环境面变体**：报错指向无辜处 ⇒ 人会去改无辜处。
- 与 **P-24**（并行 reactor 污染时间敏感读数）同族 —— 那条是**跑法**污染，这条是**本机进程**污染 ⇒ ⇒
  **通用判据升级为：凡「一批红 / 一个怪红」的根因，必须先翻到栈底并找出「谁在写产物」**。
- 与 **P-26** 同源 ⇒ 见 §六。
