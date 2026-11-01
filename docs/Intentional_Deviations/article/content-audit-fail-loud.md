# Intentional_Deviations / article · 内容安全恒 fail-loud（security 域范围外）

> **记什么**：明知与 **B 侧（RAG 仓）** 不同、**经用户签字批准**的差异。
> **⚠️ 是决策不是缺陷**：不重复质疑，**也不得「顺手修正回旧行为」**。
> **核对日期 2026-11-01**（逐行核对源文件，非估算）· **依据基准 2026-11-02**：**B 侧为唯一真理**（原始逐条来源下沉到 B 侧自有代码与台账）

---

## DEV-ARTICLE-AUDIT-001 · 内容安全与图片广告检测在 RAG-V1 恒不可用（审核链路恒 fail-loud）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（登记即交付物；「两侧确实不同」的前提已由本文件逐行核对确认） |
| **分域** | article 域（`service/article/`） |
| **偏离类型** | **有意偏离**（L2 业务语义层 —— **能力范围收窄**，非行为改写） |
| **提出** | 2026-11-01 会话：用户指出「rag 没有 security 域啊」 |

### 偏离内容

**B 侧（RAG 仓）** 里，article 域的**审核消费者**有两条跨域审计腿（`security` 域在 B 侧同样**范围外** ⇒ 两个真 client 只是 4 行骨架占位）：

| | **B 侧（RAG 仓）** | **RAG-V1（C）** |
|---|---|---|
| **内容净化** | `ContentSecurityPort.sanitizeText(...)` —— 唯一实现 `UnavailableContentSecurityClient` **恒抛**（`:69` 文案 / `:76` 抛点） | **无对端** ⇒ 走 `ContentAuditPort` 的兜底实现 ⇒ **恒 fail-loud** |
| **图片广告检测** | `ContentSecurityPort.detectImageAd(...)`（同款兜底 **恒抛**，`:83` 抛点） | **无对端** ⇒ **该调用点被移除** |

⇒ ⚠️ **基准切换后的定性**：**B 侧同样没有 `security` 域对端**（B 侧自己的 `docs/DRIFT/article/rpc.md` §12.1 已把 `S-5` 改判「**范围外**」· 现行态 ✅ 已闭合）⇒ ⇒ **两侧的审核链路同样恒 fail-loud** ⇒ 本条登记的是「**C 侧不承接 B 侧那 2 个骨架 client，并把 3 个类改名落 `ContentAudit*`**」这一**类清单层面**的取舍，**可观察行为两侧一致**（都把文章留在 `REVIEWING`）。

RAG-V1 的**迁移范围排除 `security` 域**（AGENTS.md §1.0「范围外：`knowledge`/`points`/`security`」）⇒
article 域**没有内容安全对端**，审核链路的两条腿（文本净化 + 图片广告检测）**在 RAG-V1 恒不可用**。

### B 侧出处（逐行核对 · 只看**消费点**，不看声明点 —— §3.3.1）

- B 侧 `service/article/article-rpc/src/main/java/com/rag/article/article/rpc/client/UnavailableContentSecurityClient.java:69`
  ```java
  private static final String NOT_INITIALIZED = "content security client not initialized";
  ```
  ⇒ **B 侧的失败语义是 fail-loud**（`:76` 与 `:83` 两处 `throw new SecurityAuditException(NOT_INITIALIZED);` ⇒ **抛错**，**不是**放行）。
- B 侧 `service/article/article-rpc/src/main/java/com/rag/article/article/rpc/mq/ArticleReviewConsumer.java:267` —— `auditArticle(...)` 消费点；`:296` —— 图片广告检测调用点 `contentSecurity.detectImageAd(dataUri)`。
- B 侧 `.../client/SecurityRpcClient.java` 与 `.../client/ImageSecurityRpcClient.java` —— **各 4 行骨架占位**（`[骨架占位 · 未实现]`）⇒ **B 侧确实没有可用的 `security` 域对端**。
- B 侧 `docs/DRIFT/article/rpc.md:2400`（§12.1）—— 🔴 `S-5` 改判：**范围外**（⇒ 不计入本组件必相对齐项）· 现行态 = ✅ **已闭合**（2026-11-01 · G3 回写销账）。
- B 侧 `docs/DRIFT/_cross/grading/W4.md:17` —— 取证：**两侧可观察行为一致**（都把文章留在 `REVIEWING`）＋ 错误文案**逐字相同**。

### RAG-V1 落点（2026-11-01 已建空壳）

| 文件 | 职责 |
|---|---|
| `service/article/src/main/java/com/rag/article/client/ContentAuditPort.java` | 内容安全审计的**窄接口**（审核链路唯一的审计入口） |
| `service/article/src/main/java/com/rag/article/client/UnavailableContentAuditClient.java` | 兜底实现 —— **fail-loud**（复刻 B 侧 `UnavailableContentSecurityClient` 的**恒抛**语义，**不 fail-open**） |
| `service/article/src/main/java/com/rag/article/client/ContentAuditAutoConfiguration.java` | 装配点 —— 容器里没有真实现时落 `Unavailable` |
| `service/article/src/main/java/com/rag/article/messaging/ArticleReviewConsumer.java` | 审核消费者（**消费 + 内容审计 + 幂等 + `FOR UPDATE` 写 outbox**） |

⇒ **不建** `SecurityRpcClient` / `ImageSecurityRpcClient`（**无对端**）⇒ B 侧 RAG 的这 2 个类**无 C 落点**。

### 影响面（已核实）

| 项 | 结论 |
|---|---|
| **B 侧的 fail-loud 语义** | ✅ **保留** —— `UnavailableContentAuditClient` **抛错而非放行**（⚠️ 若改成 fail-open ＝ 把「审核不可用」变成「审核放行」，那是**行为改写**，不是偏离） |
| **article 审核链路的可用性** | ⛔ **收窄** —— RAG-V1 里内容安全**永远不可用** ⇒ 审核消费**恒失败**（与 B 侧「`security` 域不在范围内」时的表现同形） |
| **图片广告检测** | ⛔ **该腿整体缺失** —— B 侧 `ArticleReviewConsumer.java:296` 的调用点在 RAG-V1 **无对应物** |
| **L1 契约层** | ✅ **零影响** —— proto / 错误码值 / HTTP path / Kafka topic / 表结构**均未动**；article 域的 `contract/` 包**不含**内容安全相关错误码 |
| **B 侧对应类** | ⚠️ `SecurityRpcClient` / `ImageSecurityRpcClient` / `ContentSecurityPort` / `ContentSecurityAutoConfiguration` / `UnavailableContentSecurityClient` —— **5 个类无 C 落点**，其中 3 个**改名**落 `ContentAudit*`（去 `Security` 域名含义） |

### 偏离理由

1. **范围外的域不迁** —— `security` 域不在 RAG-V1 的 10 个业务域内（AGENTS.md §1.0）⇒ 迁进来等于**扩范围**。
2. **有对端才有「对得上」** —— 跨域 gRPC 的对端不存在时，**保留 client 类只会得到一个永远连不上的壳** ⇒ 改为**端口 + fail-loud 兜底**，把「不可用」显式化。
3. **fail-loud 优于 fail-open** —— B 侧 `UnavailableContentSecurityClient.java:76` 明确 `throw new SecurityAuditException(...)` ⇒ RAG-V1 的兜底实现**必须照抄这个语义**（否则审核不可用会静默变成审核放行 ＝ **新增一层静默的行为差**）。

### ⚠️ 边界声明（不得越界）

- 本条**只覆盖「内容安全 / 图片广告检测这两个跨域调用在 RAG-V1 无对端」**这一件事。
- 本条**不豁免** L1 的其它部分：proto 字段号 / 错误码值 / HTTP path / Kafka topic / 表结构 / JWT claims **仍须逐字对齐 B 侧**。
- 本条**不豁免** L2 的其它部分：article 域审核消费者的**四元组校验 / 幂等 / `FOR UPDATE` 写 outbox** 语义**仍须逐条对齐 B 侧**。
- ⚠️ **不得**把 `UnavailableContentAuditClient` 改成「静默放行」—— 那是**行为改写**，需另走决策流程。
- ⚠️ **不得**因为「本域审核恒失败」就把 article 域的审核链路**整体删掉** —— B 侧的**控制流**（消费 → 校验 → 审计 → 落 outbox）**仍须保留**，只有审计这一腿没有对端。

---

## 引用

- 迁移范围 → [`../../../AGENTS.md`](../../../AGENTS.md) §1.0（范围外：`knowledge`/`points`/`security`）
- 两方对照 → [`../../Mapping_Table/article/article.md`](../../Mapping_Table/article/article.md) §1.2「`SecurityRpc` / `ImageSecurityRpc`」两行
- 降级档 → [`../../L3-降级清单.md`](../../L3-降级清单.md)
- 漂移档 → [`../../DRIFT/_INDEX.md`](../../DRIFT/_INDEX.md)
- **B 侧台账与代码（原始逐条来源下沉处）** → `/Users/pr/Documents/java&golang/RAG` 的 `docs/DRIFT/article/rpc.md:2400`（§12.1 `S-5` 改判范围外）· `docs/DRIFT/_cross/grading/W4.md:17`（取证）· `service/article/article-rpc/src/main/java/com/rag/article/article/rpc/client/UnavailableContentSecurityClient.java`（恒抛实现）
