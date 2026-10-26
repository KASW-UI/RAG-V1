# tools —— 生成器与守门脚本

| 脚本 | 作用 | 何时跑 |
|---|---|---|
| `gen-file-tree.py` | **按实际文件系统生成 `docs/File_Tree/` 的 13 份逐模块文件树**（**源树路径镜像**：域模块落在 `File_Tree/<域>.md`，`users/` 下保留 `user/`·`admin/` 两级） | **改完目录结构后必跑**（否则文档与代码漂） |
| `difftest/check-arch-invariants.py` | 架构符合性断言 A1–A12 | 提交前 / CI |
| `difftest/check-proto-static.sh` | proto 契约对照 | ⬜ **待实现**（文件不存在）|
| `difftest/check-ddl-static.py` | DDL 列级对照 | ⬜ **待实现**（文件不存在）|
| `difftest/run-parity.sh` | 双侧语义探针（Go ↔ RAG-V1） | ⬜ **待实现**（文件不存在）|
| `check-mapping-coverage.py` | **三方对照文档的五重校验**：① A / C 列覆盖率（从文件系统反查，不采信文档自报数字）② **`_INDEX.md` 自报数字对账**（行数 / 覆盖 / 映射行 vs 实测值）③ 映射行计数（扫全行找类型词，避开多列表的列位陷阱）④ **小节标题自报条数 == 表内实际行数**（P-08 同病 ⇒ 机器复算）⑤ **B 列双向对账**（B1 正向：B 列引用的类必须在 RAG 磁盘真实存在；B2 反向：RAG 侧每个业务类都必须被 B 列覆盖 ⇒ 否则抄写路线图会漏掉它） | 改完 `docs/Mapping_Table/` 任一域文档后；提交前（EXIT=0 才算通过） |

## `gen-file-tree.py` 的约定

- **不手写文件树文档** —— 文档由脚本从文件系统生成 ⇒ 不会与代码漂。
- **输出布局 = 源树路径的镜像**：去掉 `service/` 前缀，模块路径末段换 `.md`
  （`service/users/user` → `docs/File_Tree/users/user.md`；`service/article` → `docs/File_Tree/article.md`）
  ⇒ `users/` 保留 `user/`·`admin/` 两级。
- ⚠️ **打平后的模块清单（2026-10-26）**：每域**一个** jar 模块（`service/<域>/`）——
  各域的 `*-contract` 模块已并入域模块（错误契约 + 事件 DTO 在 `com.rag.<域>.contract` 包），
  `.proto` 契约集中到**根级 `proto/` 模块**（pb 类包名 `com.rag.proto.<域>`）⇒ 叶子模块 22 → **13**。
  `service/users/` 仍是聚合结构（真有两个子域）⇒ `user` / `admin` 各自一个 jar 模块。
- ⚠️ **物理目录 ≠ artifactId**：目录叫 `user` / `admin` / `article`，artifactId 仍是 `user-service` / `admin-service` / `article-service`（Maven reactor 内须唯一）⇒ 文档里两者**分列两行**，**不要用目录名反推 artifactId**。
- **排除构建产物**：`target/` 不进文件树（否则编译一次就会混入 `.class`）。
- **幂等**：每次运行先清掉不再产出的陈旧 `.md` 与空目录（含历史遗留的拼写错误目录），再重新生成 ⇒ 连跑两次结果一致。
- 每个文件树上标注了该文件的**首行职责**（从 javadoc 抽取）。
- 每个模块额外带一段 **「特殊约定（L1 契约 · 勿凭直觉改）」** —— 这部分**手写**在脚本的 `SPECIAL` 字典里，改动时需同步。

## `check-mapping-coverage.py` 的约定

- **域路径表 = 域的聚合根**：RAG-V1 侧写 `service/<域>`（打平后每域一个 jar 模块，域内 `contract/` 包仍落在该根之下 ⇒ C 列分母不因打平而变）· Go 侧写 `service/<域>`（Go 未动）。
- ⚠️ **路径不存在即报红**：目录改名 / 模块被并掉后，路径表若没跟着改，会静悄悄走 `0 个文件 ⇒ 100% 覆盖` 的**假绿** ⇒ 脚本先断言路径存在，不存在直接 EXIT=1。
- ⚠️ **根级 `proto/` 模块不在本表**：它只有 `.proto`（无手写 Java），pb 类生成到 `target/` 被过滤 ⇒ proto 契约对照由 `difftest/check-proto-static.sh` 负责。

### B 列守卫（第 ⑤ 重校验）的约定

> **背景**：A 列（482/482）与 C 列（549/549）长期有机器守卫，**B 列一直没有** ——
> 而 §1.0c 已把 **B↔C 对应定为「逐类抄写路线图」** ⇒ B 列漏一个类 = 抄写时漏一个类。
> 2026-10-26 补上**双向对账**。

| 判据 | 方向 | 红了说明 |
|---|---|---|
| **B1 正向** | B 列写下的**具体类名** → RAG 磁盘 | 文档引用了 RAG 侧**不存在**的类（RAG 改名/删除后文档没跟上）|
| **B2 反向** | RAG 侧每个**业务** `.java` → B 列 | RAG 侧有类**没进 B 列** ⇒ 抄写路线图会漏掉它 |

- ⚠️ **只扫 B 列单元格，不扫全文** —— 全文里的 `.java` 也包含 C 列的类名，拿全文当 B 列会把「C 侧有、RAG 侧没有」的类误报成「B 列引用了不存在的类」。
- ⚠️ **B 列有三种写法，守卫必须全认**：**精确类名**（`Xxx.java`）· **通配**（`Message<Case>Service.java`）· **目录形态**（`like-rpc/…/mapper/`，**≥2 段**才收 —— 单段目录 token 会把全仓吞掉）。
- ⚠️ **RAG 的 `tools/`（语义探针 / 对拍 runner）不是业务代码** ⇒ 不计入 B2 分母。
- 📌 **白名单**（`B_COLUMN_WHITELIST`）= 登记在案的例外，**每条必须写原因 + 核对日期**；脚本会报「已失效条目」⇒ **防白名单腐化**。
- ✅ **负向测试已做**（2026-10-26）：① 从白名单删一条 ⇒ B2 报红 ② B 列写一个不存在的类 ⇒ B1 报红 ③ 白名单塞一个已被覆盖的类 ⇒ 报「条目已失效」⇒ **不是不会失败的假守卫**。

