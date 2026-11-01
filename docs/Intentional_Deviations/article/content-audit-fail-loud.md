# Intentional_Deviations / article · 内容安全恒 fail-loud（security 域范围外）

> **记什么**：明知与 Go 真源不同、**经用户签字批准**的差异。
> **⚠️ 是决策不是缺陷**：不重复质疑，**也不得「顺手修正回旧行为」**。
> **核对日期 2026-11-01**（逐行核对 Go 源文件，非估算）

---

## DEV-ARTICLE-AUDIT-001 · 内容安全与图片广告检测在 RAG-V1 恒不可用（审核链路恒 fail-loud）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（登记即交付物；「两侧确实不同」的前提已由本文件逐行核对确认） |
| **分域** | article 域（`service/article/`） |
| **偏离类型** | **有意偏离**（L2 业务语义层 —— **能力范围收窄**，非行为改写） |
| **提出** | 2026-11-01 会话：用户指出「rag 没有 security 域啊」 |

### 偏离内容

Go 真源里，article 域的**审核消费者**会跨域调用 **`security` 域**的两个 gRPC 方法：

| | Go 真源 | RAG-V1 |
|---|---|---|
| **内容净化** | `SecurityRpc.SanitizeContent(...)`（`contentsecurityservice`） | **无对端** ⇒ 走 `ContentAuditPort` 的兜底实现 ⇒ **恒 fail-loud** |
| **图片广告检测** | `ImageSecurityRpc.DetectImageAd(...)`（`imagesecurityservice`） | **无对端** ⇒ **该调用点被移除** |

RAG-V1 的**迁移范围排除 `security` 域**（AGENTS.md §1.0「范围外：`knowledge`/`points`/`security`」）⇒
article 域**没有内容安全对端**，审核链路的两条腿（文本净化 + 图片广告检测）**在 RAG-V1 恒不可用**。

### Go 出处（逐行核对 · 只看**消费点**，不看声明点 —— §3.3.1）

- `Sea-RideTheWind_golang/service/article/rpc/internal/mqs/article_consumer.go:153-156`
  ```go
  func (l *ArticleConsumer) auditArticle(ctx context.Context, article *model.Article, content string, authorID string) error {
      if l.svcCtx.SecurityRpc == nil {
          return fmt.Errorf("content security client not initialized")
      }
  ```
  ⇒ **Go 的失败语义是 fail-loud**（`SecurityRpc == nil` ⇒ **返回 error**，**不是**放行）。
- `Sea-RideTheWind_golang/service/article/rpc/internal/mqs/article_consumer.go:253`
  ```go
  resp, err := l.svcCtx.ImageSecurityRpc.DetectImageAd(ctx, &imagesecurity.DetectImageAdRequest{...})
  ```
  ⇒ 图片广告检测**无 nil 守卫**（Go 侧由 `service_context.go:73` 保证必然初始化）。
- `Sea-RideTheWind_golang/service/security/rpc/` —— **真源确实有 `security` 域**
  （`internal/logic/contentsecurityservice/sanitize_content_logic.go` + `internal/logic/imagesecurityservice/detect_image_ad_logic.go`）。
- `Sea-RideTheWind_golang/service/article/rpc/internal/svc/service_context.go:72-73` —— 两个 client 的装配点。

### RAG-V1 落点（2026-11-01 已建空壳）

| 文件 | 职责 |
|---|---|
| `service/article/src/main/java/com/rag/article/client/ContentAuditPort.java` | 内容安全审计的**窄接口**（审核链路唯一的审计入口） |
| `service/article/src/main/java/com/rag/article/client/UnavailableContentAuditClient.java` | 兜底实现 —— **fail-loud**（复刻 Go 侧 `SecurityRpc == nil` 分支，**不 fail-open**） |
| `service/article/src/main/java/com/rag/article/client/ContentAuditAutoConfiguration.java` | 装配点 —— 容器里没有真实现时落 `Unavailable` |
| `service/article/src/main/java/com/rag/article/messaging/ArticleReviewConsumer.java` | 审核消费者（**消费 + 内容审计 + 幂等 + `FOR UPDATE` 写 outbox**） |

⇒ **不建** `SecurityRpcClient` / `ImageSecurityRpcClient`（**无对端**）⇒ B 侧 RAG 的这 2 个类**无 C 落点**。

### 影响面（已核实）

| 项 | 结论 |
|---|---|
| **Go 的 fail-loud 语义** | ✅ **保留** —— `UnavailableContentAuditClient` **抛错而非放行**（⚠️ 若改成 fail-open ＝ 把「审核不可用」变成「审核放行」，那是**行为改写**，不是偏离） |
| **article 审核链路的可用性** | ⛔ **收窄** —— RAG-V1 里内容安全**永远不可用** ⇒ 审核消费**恒失败**（与 Go 侧「security 域不启动」时的表现同形） |
| **图片广告检测** | ⛔ **该腿整体缺失** —— Go 侧 `article_consumer.go:253` 的调用点在 RAG-V1 **无对应物** |
| **L1 契约层** | ✅ **零影响** —— proto / 错误码值 / HTTP path / Kafka topic / 表结构**均未动**；article 域的 `contract/` 包**不含**内容安全相关错误码 |
| **RAG 侧对应类** | ⚠️ `SecurityRpcClient` / `ImageSecurityRpcClient` / `ContentSecurityPort` / `ContentSecurityAutoConfiguration` / `UnavailableContentSecurityClient` —— **5 个类无 C 落点**，其中 3 个**改名**落 `ContentAudit*`（去 `Security` 域名含义） |

### 偏离理由

1. **范围外的域不迁** —— `security` 域不在 RAG-V1 的 10 个业务域内（AGENTS.md §1.0）⇒ 迁进来等于**扩范围**。
2. **有对端才有「对得上」** —— 跨域 gRPC 的对端不存在时，**保留 client 类只会得到一个永远连不上的壳** ⇒ 改为**端口 + fail-loud 兜底**，把「不可用」显式化。
3. **fail-loud 优于 fail-open** —— Go 侧 `article_consumer.go:155` 明确 `return fmt.Errorf(...)` ⇒ RAG-V1 的兜底实现**必须照抄这个语义**（否则审核不可用会静默变成审核放行 ＝ **新增一层静默的行为差**）。

### ⚠️ 边界声明（不得越界）

- 本条**只覆盖「内容安全 / 图片广告检测这两个跨域调用在 RAG-V1 无对端」**这一件事。
- 本条**不豁免** L1 的其它部分：proto 字段号 / 错误码值 / HTTP path / Kafka topic / 表结构 / JWT claims **仍须逐字对齐 Go**。
- 本条**不豁免** L2 的其它部分：article 域审核消费者的**四元组校验 / 幂等 / `FOR UPDATE` 写 outbox** 语义**仍须逐条对齐 Go**。
- ⚠️ **不得**把 `UnavailableContentAuditClient` 改成「静默放行」—— 那是**行为改写**，需另走决策流程。
- ⚠️ **不得**因为「本域审核恒失败」就把 article 域的审核链路**整体删掉** —— Go 侧的**控制流**（消费 → 校验 → 审计 → 落 outbox）**仍须保留**，只有审计这一腿没有对端。

---

## 引用

- 迁移范围 → [`../../../AGENTS.md`](../../../AGENTS.md) §1.0（范围外：`knowledge`/`points`/`security`）
- 三方对照 → [`../../Mapping_Table/article/article.md`](../../Mapping_Table/article/article.md) §1.2「`SecurityRpc` / `ImageSecurityRpc`」两行
- 降级档 → [`../../L3-降级清单.md`](../../L3-降级清单.md)
- 漂移档 → [`../../DRIFT/_INDEX.md`](../../DRIFT/_INDEX.md)
