# worktree 并发工作流（RAG-V1）

> **本文件是操作手册**；AGENTS.md §五.1 只留一行指针。
> **来源**：RAG 仓 `docs/Worktree.md`（2026-10-27 起）的**方法论部分** ⇒ 搬进本仓并按 RAG-V1 的
> 仓库坐标（路径 / 基线 / 远端 / 共享面）改写。
> ⚠️ **只搬方法，不搬 RAG 的组织形态** ⇒ 见 §七「本仓**不**继承 RAG 的哪些东西」。

---

## 一、为什么需要

`git worktree` = **同一个 Git 仓库，多开出几个独立目录，各装一份代码，互不干扰**。

与「手动 clone 第二份」的对比：

| | 手动 `git clone` | worktree |
|---|---|---|
| `.git` | 完整的第二份 | **文件**，内容是一行指回主仓库的路径 |
| 磁盘占用 | 历史全复制一份 | 几乎为零（共享对象库） |
| 对方 commit 你看得到吗 | ❌ 手动 `fetch` | ✅ 立刻可见（同一 reflog） |
| push 冲突 | 两边各自 fetch，易打架 | Git 自己挡 |

**「只建分支」是不够的**：分支名有了，目录还是一个，切分支会带着工作区未提交改动走或被 Git 拦下。
并发靠的是**目录隔离**。

⚠️ **本仓为什么需要它**：RAG-V1 的 10 个业务域**各自独立推进**（AGENTS.md §4.2「每域独立 stop point」），
而 10 个域会共用 `common/` 与 `docs/DRIFT/_INDEX.md` / `AGENTS.md` 这些**共享面**
⇒ ⇒ 「一个域一棵工作树」是**唯一**能让多域并行、又不互相偷工作区状态的形态。

---

## 二、建（一条命令同时建分支 + 建目录）

```bash
cd "/Users/pr/Documents/GitHub/RAG-V1"
git fetch origin
git status -sb                 # ⚠️ 先确认主工作区干净（见 §四）

for d in article comment favorite follow hot like message task users-user users-admin; do
  git worktree add "../RAG-V1.worktrees/$d" -b "align/$d" main
done

git worktree list
```

- `-b align/<域>` —— 顺手把**本地分支**也建了（不必先去 GitHub 网页建分支，push 时才出现在 `origin`）。
- **`main`** —— 本仓基线（⚠️ **不是** RAG 的 `do-my-own-gh`）。
- 目录名不能用斜杠（`users/user` 会被当路径分隔）⇒ 写作 `users-user`。

建完之后：

```
/Users/pr/Documents/GitHub/RAG-V1/          ← 主工作区，停在 main
/Users/pr/Documents/GitHub/RAG-V1.worktrees/ ← 各域工作树（**在仓外，不进版本库**）
├── article/            ← align/article
├── comment/            ← align/comment
├── favorite/  follow/  hot/  like/  message/  task/
├── users-user/         ← align/users-user
└── users-admin/        ← align/users-admin
```

⚠️ **`RAG-V1.worktrees/` 必须落在仓库外** ⇒ 否则 `git worktree list` 会把它当子模块 ⇒ 嵌套仓 ⇒ 工具链全乱。

---

## 三、纪律（硬约束）

### 1. 台账写入边界 —— **不碰共享索引**

| 文件 | 谁写 |
|---|---|
| `docs/DRIFT/<域>/<组件>.md` | ✅ 该域工作树自己写 |
| `docs/DRIFT/<域>/vs-common.md` | ✅ 该域自己写（**按域各一份**）⇒ 各树只写自己那几个域的 |
| `docs/DRIFT/_INDEX.md` | ❌ **所有工作树都不许碰**，合并后由主会话统一更新 |
| `AGENTS.md` | ❌ 同上 |
| `docs/Intentional_Deviations/_INDEX.md` · `docs/Pitfalls/_INDEX.md` | ❌ 同上（三个 `_INDEX.md` 合并后统一更新） |

理由：多路并发合并必撞，且事后分不清哪段台账是谁写的。
⇒ 各工作树的台账要能自证归属：**每份台账首行标注「由 `align/<域>` 工作树产出 + 基线 commit」**。

### 2. 每域做完的收尾 —— **必过迁移审核**

> 🔴 **2026-11-02 用户定（硬要求）**：任一业务域的实现**做完**
> （该域的关键路径有**真触发**测试 + 台账写回）
> ⇒ **必须**再经 **`.agents/verification.md`**（RAG→RAG-V1 迁移审核 Prompt）检测
> ⇒ 查「迁移后的实现是否**完整保留 RAG 的实际行为**」⇒
> ⚠️ **检测未过 ⇒ 该域不算做完** ⇒ 不许进下一域、不许合主干。

- 检测在**该域自己的工作树里**跑（读的是那棵树的代码，不是主工作区的）。
- 检测 Prompt 在**主仓**的 `/Users/pr/Documents/GitHub/RAG-V1/.agents/verification.md`（绝对路径 ⇒ 跨工作树可用）。
- **来源只能是 RAG 的实际代码**（B 侧）+ Go 真源（L1 契约层）⇒ 不是「RAG-V1 自己写的实现当基准」（§3.1 自举陷阱）。

### 3. 共享面只有两类

| 共享面 | 为什么 |
|---|---|
| `docs/DRIFT/_INDEX.md` · `AGENTS.md` · `docs/Intentional_Deviations/_INDEX.md` · `docs/Pitfalls/_INDEX.md` | 多路合并必撞，且事后无法区分台账归属 |
| 主工作区 `/Users/pr/Documents/GitHub/RAG-V1/`（停在 `main`） | 归主会话与其它域所有；在其上「签出」会带走别人的未提交改动 |

⚠️ **实测教训（2026-10-23 · RAG 仓）**：另一个并发会话曾用 `git checkout` 把本会话对 `AGENTS.md` 的编辑**整体回滚**。
⇒ **在共享文件上的改动要尽早提交**，不要长期挂在工作区里等合并。

### 4. 本仓的共享层 = `common/` ＋ `users/users-common/`

| 共享层 | 被谁依赖 | 写权 |
|---|---|---|
| `common/`（**starter**） | **全部 10 个 app** | ⚠️ 与 AGENTS.md §五.3 的 `global` 收口域规则叠加 ⇒ 改共享层 = 影响 10 个域 ⇒ 只能由**收口任务**改（§五.3 规则 3） |
| `service/users/users-common/` | users 域 + 跨域共享 | 同上 |

⚠️ **「物理位置不搬」**：共享层从域内提到 `common/` 会引发大范围 import 改动 ⇒ 收益不足 ⇒ 不搬（沿用 RAG 的同一取舍）。

---

## 四、⚠️ 已知冲突：基线与并发占用

### 建树时的基线 —— **每次建树前都要重新确认**

⚠️ **本仓的坑**：本地 `main` 可能**领先 `origin/main` 若干 commit**（批次搬运的 commit 尚未 push 时就是如此）
⇒ ⇒ **基线 = 本地 `main`**（工作树是从本地 ref 建的）⇒ ⇒ **别人从 GitHub 拉会缺这些 commit**。
⇒ ⇒ **两条纪律**：
1. **建树前**：`git status -sb` ⇒ 看到 `ahead N` ⇒ 决定是「先 push 再建树」还是「明确基线含这 N 个 commit」；
2. **台账里引用基线 commit 时**写**实际 commit 号 + 核对日期** ⇒ 不要照抄本文件里的任何 commit 号
   （§六纪律：无核对日期的断言按 `UNKNOWN` 处理）。

### 主工作区被占用时的通用规则

并发会话各自有工作树时，**共享面只有两类，任何工作树都不得触碰**（见 §三·3）。

---

## 五、拆（干完撤掉）

```bash
git worktree remove ../RAG-V1.worktrees/task
git branch -d align/task
```

`remove` 前必须确认该目录无未提交改动，否则 Git 会拦（这是好事）。

⚠️ **先确认已合回**：

```bash
git merge-base --is-ancestor align/task main && echo "已合回"
```

⚠️ **删之前先确认那棵树的写权面已清空** ⇒ 逐个核对「该树独有的文件是否都已合入 `main`」
⇒ ⚠️ **「守门恒绿假绿」的同款陷阱**：若某个守门脚本盯着一条**已删除的分支** ⇒ 它会**永远绿**而实际什么都没查。

---

## 六、命令速查

| 目的 | 命令 |
|---|---|
| 看挂了哪几个 | `git worktree list` |
| 建（分支+目录一起） | `git worktree add "<路径>" -b "<分支>" main` |
| 确认脚手架在位 | `ls ../RAG-V1.worktrees/article/docs/DRIFT/article/` |
| 同步基线 | `git merge main`（在工作树内） |
| 推 feature 分支 | `git push -u origin align/<域>` |
| 合回主干 | `git merge --no-ff align/<域>`（**在主工作区**） |
| 撤 | `git worktree remove <路径>` + `git branch -d <分支>` |
| 确认已合回 | `git merge-base --is-ancestor align/<域> main && echo 已合回` |

> ⚠️ IDE 里也能做（Git 工具窗口 ⌘9 → 右键分支 → `Git > 新建工作树…`），
> 但多建时终端循环更快、不易点错。**不要在主目录上点「签出」**——那会带走另一个会话的未提交改动。
>
> ⚠️ **同名分支已存在时**：`git worktree add` 会报「branch already checked out」
> ⇒ 先 `git worktree remove` + `git branch -D`（**确认无独有提交、无未提交改动后再删**）。

---

## 七、本仓**不**继承 RAG 的哪些东西

RAG 的 `docs/Worktree.md` 全文 1,263 行 / 194 KB ⇒ **本仓只搬了方法论部分**（§一~§六）。
⇒ 以下**留在 RAG 仓**，**不搬**：

| RAG 侧的内容 | 为什么**不**搬 |
|---|---|
| **G1~G4 波次划分**（`align/g1-users-follow` 等四棵） | RAG 特有的**域波排序**（由 RAG 的契约 DAG + 合并收口序定）⇒ RAG-V1 的域推进顺序由 AGENTS.md §4.2 定 ⇒ 照搬会把 RAG 的历史约束带进来 |
| **`PhasedGoal.md` / `twoStage.md`**（RAG 的「阶段 1 准入线」） | RAG 的**简历导向**阶段划分 ⇒ RAG-V1 没有同一套阶段口径 |
| **派生层施工单**（`DRIFT/_cross/stage1-derivation.{md,tsv}` + `tools/ledger-derive/`） | RAG 的派工机制 ⇒ 本仓的派工面 = `docs/Mapping_Table/`（§1.0c 抄写路线图）⇒ **两套并存会打架** |
| **§七 ~ M-32 的收官轮记录**（核销 / 订正，约 520 行） | **RAG 仓的历史台账**（记的是 RAG 自己的 merge 事故）⇒ 与 RAG-V1 无关 |
| `tools/difftest/check-wave-lane-ownership.py`（写权机器守门） | 依赖 RAG 的波次白名单 ⇒ 本仓无波次 ⇒ 搬过来就是**恒绿假绿** ⇒ ⚠️ 本仓的写权靠 AGENTS.md §五.3 的规则 3（`common/**` 只有收口任务能写） |

⚠️ **搬来的部分也做了坐标改写**：路径 ⇒ `/Users/pr/Documents/GitHub/RAG-V1` · 工作树目录 ⇒ `../RAG-V1.worktrees/` ·
基线 ⇒ `main`（RAG 是 `do-my-own-gh`）· 远端 ⇒ `origin`（RAG 是 `upstream`）· 共享面 ⇒ 本仓的
`common/` ＋ `users/users-common/`（RAG 是 `service/common` ＋ `service/users/users-common`）。

---

## 八、相关指针

| 文件 | 是什么 |
|---|---|
| `AGENTS.md` §五 | 用户协作规则（每域一个 PR / 三档台账 / `global` 收口域 / 作用范围 / 基准分层 / review / 守门脚本 4 硬规则） |
| `AGENTS.md` §四 | 实现组织（真理之源 = Go；每域独立 stop point） |
| `AGENTS.md` §3.3 | 框架机制必须验证实际生效（§3.3 的纪律与本手册的「回执后必须再查分支 tip」同族） |
| `docs/Mapping_Table/` | 抄写路线图（§1.0c：B↔C 逐类对应） |
| `.agents/verification.md` | **每域做完的收尾检测 Prompt**（§三·2） |
| `docs/Pitfalls/` | 踩坑台账（P-01~P-10） |
