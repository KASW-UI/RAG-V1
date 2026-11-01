# DEV-GLOBAL-SEC-001 · `BearerTokenExtractor` 的前缀剥离判定：C 侧 `> 6` vs B 侧 `> 7`

> **状态**：🏷 **候选，建议签**（2026-11-02 · D16 基准切换后新增）
> **层**：**L1**（HTTP 契约 —— `Authorization` 头的剥离规则）
> **域**：`global`（收口域 · 共享层 `common/`）
> **组件**：`common/src/main/java/com/rag/common/security/BearerTokenExtractor.java`
> **触发**：D16（2026-11-02 · 用户定「B 侧是唯一真理」）把判定基准从上游切到 B 侧 ⇒ 本条原本「按上游改」的差异**变成了 C 与 B 的实差异**。

---

## 一、两侧确实不同（逐行核对 · 2026-11-02）

| 侧 | 文件 | 判定条件 |
|---|---|---|
| **B（RAG 仓）** | `service/users/users-common/src/main/java/com/rag/users/common/jwt/BearerTokenExtractor.java:75` | `authHeader.length() > BEARER_PREFIX.length()` ⇒ **> 7** |
| **C（本仓）** | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java:81` | `authHeader.length() > BEARER_PREFIX.length() - 1` ⇒ **> 6** |

`BEARER_PREFIX = "Bearer "` ⇒ 长度 **7**。

⇒ **分叉点**：`Authorization: Bearer `（`Bearer ` 正好 7 字符、后面没有 token）
- **B 侧**：`7 > 7` 为假 ⇒ **不剥** ⇒ 整串 `"Bearer "` 交给验签器
- **C 侧**：`7 > 6` 为真 ⇒ **剥成空串** `""` 交给验签器

---

## 二、为什么会分叉（背景，非缺陷说明）

- **B 侧的代码是 `> 7`，但 B 侧自己的 javadoc 写的是 `> 6`**（`BearerTokenExtractor.java:64`：*"判定是 `len(tok) > 6`（严格大于）。`"Bearer "` 正好 7 字符会被剥成空串"*）
  ⇒ **B 侧自身的注释与 B 侧自身的代码不一致** ⇒ 属 **B 侧的移植偏差**（B 侧照抄时把判定条件写成了 `> 7`，注释却抄的是 `> 6`）。
- **C 侧在批次 D（2026-10-22）发现这个分叉，按当时的基准（上游）改成了 `> 6`**，并在 javadoc 里留了痕（`BearerTokenExtractor.java:75-80`）。

---

## 三、为什么不「改回 B 侧」

- **两种写法都让这种畸形头验签失败** ⇒ **无安全洞**；差别只在「传给验签器的是哪个串」。
- 但 `BearerTokenExtractor` 是**鉴权边界** ⇒ 把判定条件从 `> 6` 改回 `> 7` 是**安全语义上的收紧**，**不是表达层改动** ⇒ 按 AGENTS.md §三「L1 未变 ⇒ 停，走决策流程」，**不由迁移任务顺手决定**。
- ⇒ 本条登记为**签字偏离**，等使用者确认「C 侧保持 `> 6`（上游口径）」还是「改回 B 侧 `> 7`（B 口径）」。

---

## 四、若签字确认「C 侧保持 `> 6`」

- 本条**留在本档**（C ≠ B 是事实，不是缺陷）。
- `docs/DRIFT/common/verification-common-2026-11-02.md` 的对应行（`BearerTokenExtractor` 证据列）**保持现状**（已注明「有意与 B 侧不同」）。
- 对拍脚本若以 B 侧为基准跑这个组件，**必须显式排除这一格**，否则会误判成回归 ⇒ 触发「静默修回 B 侧」。

## 五、若决定「改回 B 侧 `> 7`」

- 改 `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java:81` ⇒ 去掉 `- 1`。
- 同步改 javadoc（`:75-80` 的注释块）与本条。
- 补一条**真触发**的测试（断言 `Authorization: Bearer `（7 字符）在两侧的判定结果）⇒ 证明不是假绿。

---

> 📌 **登记规则第 1 条已履行**：两侧实现已逐行读过（路径 + 行号见 §一），「两侧确实不同」是自己确认的，**不是推给使用者去核的前提**。
> ⚠️ **登记规则第 2 条**：登记本身是交付物 —— **不打包推给用户逐条拍板**；使用者确认后改状态即可。
