# Mapping_Table/README.MD — 目录元结构

> **本目录的目的**：把 **Go 真理之源**（A 列）→ **RAG**（B 列，Go 部署形态版）→ **RAG-V1**（C 列，Spring 惯用目标架构）
> 三棵树的文件名逐条对应起来，方便在 RAG-V1 上填实现时逐条查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> **A 列**：`/Users/pr/Documents/java&golang/Sea-RideTheWind_golang/service/...`（真源，冻结）
> **B 列**：`/Users/pr/Documents/java&golang/RAG/service/...`（Go 形态版；`service/common` 与 `service/users`）
> **C 列**：`/Users/pr/Documents/GitHub/RAG-V1/service/...`（本仓；`common` 在仓库根，不在 `service/` 下）

> 📌 **与 RAG 的 `docs/MappingTable` 的差别**：RAG 那份是 A(Go) / B(`Sea-RideTheWind_java` 镜像) / C(RAG) 三列；
> **本份是 A(Go) / B(RAG) / C(RAG-V1) 三列** —— 因为 RAG-V1 的「前一形态」是 RAG，不是 Java 镜像。
> 格式、章节骨架、映射类型词表**完全沿用 RAG 那份**，便于两边对照。

---

## 一、三列语义与规模基线

| 列 | 仓库 | 角色 | 主代码文件数 |
|---|---|---|---|
| **A** | `Sea-RideTheWind_golang` | **真理之源**（冻结，只读） | **482** 个 `.go`（不含 `_test.go`） |
| **B** | `RAG` | Go 部署形态版（api/rpc 分离） | **695** 个 `.java`（不含测试） |
| **C** | `RAG-V1` | **Spring 惯用目标形态**（api/rpc 合并） | **548** 个 `.java`（不含测试） |

> ⚠️ **三列不是 1:1 数量关系** —— C 比 B 少 147 个文件，主要来自：
> ① **api/rpc 合并**（D1）⇒ B 的 `*-api` + `*-rpc` 两套包收敛成一套；
> ② **`service/common` → `common` starter**（D3）⇒ B 的 51 个手写类变 C 的 56 个（含 7 个 auto-config）；
> ③ **生态替代**（D4）⇒ B 的手写熔断/日志/校验器被 resilience4j / SLF4J / Bean Validation 取代。
>
> ⇒ **映射类型里出现大量 `N:1` 与 `C 新增` 是正常的，不是缺陷。**

---

## 二、每个子文档该记什么（5 个维度）

每个子文档（`<域>.md`）按这 5 节组织：

| 节 | 记什么 | 为什么 |
|---|---|---|
| 1. **包结构对照** | A / B / C 三列文件清单 + 映射类型 + 备注 | 翻某个文件时一眼能找到三方对应 |
| 2. **持久层差异** | ORM 注解 / Mapper / Schema 演进 / 事务 / 雪花 ID | RAG-V1 与 RAG 的持久层差异 |
| 3. **HTTP / DI 差异** | 路由注册 / Controller / Service 分层 / 错误表达 / 配置绑定 / 进程拓扑 | RAG-V1 与 RAG 的装配差异 |
| 4. **公共模块对照** | ErrorCode / Response / JwtUtil / communityfact / logger 等共享件 | 跨域共享的代码在哪 |
| 5. **决策锁** | 每节末尾「**N 处同时改** + **为什么换**」 | 复刻到下一个域时按这个清单改，不漏 |

每个子文档末尾必须有 **跨模块全局约定表** —— 这几项所有域共享，不会因为改某个域而漂移。

---

## 三、形式约定（写新子文档时按这个格式）

### 表头：5 列

| A (Go 真理之源) | B (RAG, Go 形态版) | C (RAG-V1, Spring 目标) | 映射类型 | 备注/差异 |
|---|---|---|---|---|
| `service/user/user/rpc/internal/logic/loginlogic.go` | `service/users/user/user-rpc/.../logic/LoginLogic.java` | `service/users/user/service/.../api/service/UserApiService.java` | N:1 | C 把 9 个用例合进 1 个 Service；A 的 Logic 中间层被删 |

### 映射类型（固定 4 种，不发明新词）

- **1:1** —— 一个 A 文件对应一个 B 文件对应一个 C 文件
- **1:N** —— 一个 A 文件拆成 N 个 C 文件（B 通常保持 1 个或也拆开）
- **N:1** —— 多个 A 文件合到 1 个 C 文件（B 通常已经是 1 个）
- **C 新增** —— A / B 都没有对应物，是 RAG-V1 形态本身需要的新文件

> ⚠️ **不要**发明 `N:M`、`1:1:1` 之类的新词。若确实是交叉关系，按 C 实际文件数标 `1:N`，并在备注里写明。

### 决策锁格式（每节末尾固定 2 段）

```text
**换 XXX（N 处同时改）**：
1. 第一处改什么
2. 第二处改什么
3. ...

**为什么换**：一句话讲清与 Go / RAG 原版形态对比的关键差异。
```

---

## 四、覆盖度要求（每份子文档必须自查）

| 要求 | 判据 |
|---|---|
| **C 列零遗漏** | 该域在 RAG-V1 的**每一个** `.java` 文件都必须出现在表里（C 列） |
| **A 列不遗漏职责** | 该域 Go 的**每一个非测试 `.go` 文件**要么出现在 A 列，要么在备注里说明「Go 侧无对应物（原因）」 |
| **B 列可核** | RAG 侧文件路径写**到文件名**（不含包路径全量），便于 `grep` 定位 |
| **映射类型必填** | 每行都要有映射类型，不得留空 |

> 💡 **自查命令**（在对应域目录下跑）：
> ```bash
> # C 列覆盖率：把文档里出现的 C 列文件名去重，与实际文件数比对
> find <RAG-V1 域目录> -name '*.java' -not -path '*/src/test/*' -not -path '*/target/*' \
>   | xargs -n1 basename | sort -u | wc -l
> ```

---

## 五、文件命名约定

> **目录布局**：**一域一文件夹**，文档即 `<域>/<域>.md`；
> `common.md`（全局共享层）**不属于任何域 ⇒ 留在目录根**。
> ⇒ 与 `docs/File_Tree/`、`docs/DRIFT/`、`docs/Pitfalls/` 同一套「按域分档」心智。

| 子系统 | 文件 |
|---|---|
| 全局共享层 | `common.md`（**留在根**） |
| users（一个域，两个子域 user / admin） | `users/users.md` |
| article | `article/article.md` |
| comment | `comment/comment.md` |
| favorite | `favorite/favorite.md` |
| follow | `follow/follow.md` |
| hot | `hot/hot.md` |
| like | `like/like.md` |
| message | `message/message.md` |
| task | `task/task.md` |

> ⚠️ **域文档放在子目录里 ⇒ 相对链接多一层**：
> 指向本目录根的文件写 `../README.MD`、`../../决策记录.md`；
> 跨仓路径写 `../../../../../java&golang/RAG/...`（**五层**）。

---

## 六、阅读路径

1. **第一次看**：`users.md` —— 用户域是 RAG 与 RAG-V1 差异最大的域（api/rpc 合并 + 双子域 + 公共库），覆盖最全
2. **写新域**：按上面的 5 节模板，填本域的 A/B/C 实际文件
3. **填 RAG-V1 实现时**：从 C 列反查 A 列 ⇒ **去 Go 源码逐字对照**（不要拿 B 列当 Go 的替身）

---

## 七、三条不可违反的读表纪律

1. ⚠️ **B 列不是 Go 的替身** —— RAG 在 `users`/`like`/`message`/`common`/`article` 五域已被判定 `NOT EQUIVALENT`。
   **拿 B 列当 Go 的替身 = 把漂移固化进 RAG-V1。** 一切行为判断以 **A 列的 Go 源码**为准。
2. ⚠️ **「有对应」≠「行为等价」** —— 本表只回答「**文件名/职责的对应关系**」，
   **不回答「行为是否一致」**。行为对齐状态看 `docs/DRIFT/<域>/<组件>.md` 的**证据强度列**。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— RAG-V1 的每个 Java 文件目前都只有 package + 空类型 + TODO javadoc。
   **C 列出现的每一行都是「这个位置该放什么」，不是「已经有什么」。**

---

## 八、一句话总览

**A 列 Go** = 单一真源 · 482 个 `.go`；
**B 列 RAG** = Go 部署拓扑的 Spring 复刻 · api/rpc 分离 · `service/common` 是普通 jar；
**C 列 RAG-V1** = Spring 惯用形态 · api/rpc 合并为一域一 app · `common` 是真 starter。
⇒ **文件名与职责逐条对应；行为以 A 列为准。**
