# DRIFT 索引 —— 语义漂移台账

> **记什么**：RAG-V1 与 Go 真源**不一致**的逐条留档。
> **不记什么**：明知不同且已签字的（→ `../Intentional_Deviations/`）、框架做不到的（→ `../Pitfalls/`）、语义已对齐仅字面不同的。

## 档位结构

```
docs/DRIFT/
├── _INDEX.md          # 本文件
└── <域>/<组件>.md     # 每域一目录，组件级台账
```

> 📌 **组件名按 D7（2026-10-26）后的真实模块坐标**：`<域>-service`（每域一个 jar 模块）
> + `users-common`（users 域公共库）+ `common`（starter）+ `proto`（**根级 proto 契约模块**）。
> ⇒ **不再有 `*-contract` 组件**（10 个 `*-contract` 子模块已随 D7 删除，错误契约落进域内 `contract/` 包）。

## 域目录

| 域 | 组件 | 台账 |
|---|---|---|
| `user` | user-service | ⬜ 未建立 |
| `users` | users-common | ⬜ 未建立（users 域公共库） |
| `admin` | admin-service | ⬜ 未建立 |
| `article` | article-service | ⬜ 未建立 |
| `comment` | comment-service | ⬜ 未建立 |
| `favorite` | favorite-service | ⬜ 未建立 |
| `follow` | follow-service | ⬜ 未建立 |
| `hot` | hot-service | ⬜ 未建立 |
| `like` | like-service | ⬜ 未建立 |
| `message` | message-service | ⬜ 未建立 |
| `task` | task-service | ⬜ 未建立 |
| `common` | common | ⬜ 未建立 |
| `proto` | proto | ⬜ 未建立（根级 proto 契约模块，10 个 `.proto`） |

## 纪律

1. **开工前必读**本域台账 —— 否则会重复已修项、误把签字偏离当新漂移「顺手修回」。
2. **产出必写回**本域目录。
3. **每条必带证据强度**：「源码级对照」与「双侧活体对拍」不是一回事，后者才是行为层证据。
4. **状态必带核对日期** —— 否则按 `UNKNOWN` 处理。
5. **⚠️「已改」≠「已验证」** —— 绿测试可能是「错误答案的守卫」。
