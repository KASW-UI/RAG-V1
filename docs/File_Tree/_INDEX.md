# File_Tree 索引

> 本目录由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 布局规则

本目录是 **源树路径的镜像** —— 去掉 `service/` 前缀，模块路径末段换成 `.md`：

```
common                              →  File_Tree/common.md
proto                               →  File_Tree/proto.md
service/users/common                →  File_Tree/users/common.md
service/users/user                  →  File_Tree/users/user.md
service/users/admin                 →  File_Tree/users/admin.md
service/article                     →  File_Tree/article.md
```

⇒ 每域**一个**模块 ⇒ 域文档落在 `File_Tree/<域>.md`（单段）；
⇒ `users/` 下保留 `user/`·`admin/` 两级 ⇒ **一眼看出 users 是一个域、两个子域**。

## 全局

| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |
|---|---|---|---|---|
| `common` | `common` | 公共库 | [`common.md`](common.md) | `service/common/` |
| `proto` | `proto` | 契约 | [`proto.md`](proto.md) | `proto/` |

## 按域

### **article 域**

| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |
|---|---|---|---|---|
| `service/article` | `article-service` | 应用 | [`article.md`](article.md) | `service/article/` |

### **comment 域**

| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |
|---|---|---|---|---|
| `service/comment` | `comment-service` | 应用 | [`comment.md`](comment.md) | `service/comment/` |

### **favorite 域**

| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |
|---|---|---|---|---|
| `service/favorite` | `favorite-service` | 应用 | [`favorite.md`](favorite.md) | `service/favorite/` |

### **follow 域**

| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |
|---|---|---|---|---|
| `service/follow` | `follow-service` | 应用 | [`follow.md`](follow.md) | `service/follow/` |

### **hot 域**

| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |
|---|---|---|---|---|
| `service/hot` | `hot-service` | 应用 | [`hot.md`](hot.md) | `service/hot/` |

### **like 域**

| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |
|---|---|---|---|---|
| `service/like` | `like-service` | 应用 | [`like.md`](like.md) | `service/like/` |

### **message 域**

| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |
|---|---|---|---|---|
| `service/message` | `message-service` | 应用 | [`message.md`](message.md) | `service/message/` |

### **task 域**

| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |
|---|---|---|---|---|
| `service/task` | `task-service` | 应用 | [`task.md`](task.md) | `service/task/` |

### **users 域**（一个域，两个子域 user / admin）

| 模块路径 | artifactId | 类型 | 文件树 | Go 出处 |
|---|---|---|---|---|
| `service/users/common` | `users-common` | 公共库 | [`users/common.md`](users/common.md) | `service/user/common/errmsg/errmsg.go` |
| `service/users/user` | `user-service` | 应用 | [`users/user.md`](users/user.md) | `service/user/user/` |
| `service/users/admin` | `admin-service` | 应用 | [`users/admin.md`](users/admin.md) | `service/user/admin/` |

## 说明

- `common` = 全局 starter（原 `service/common`），**不属于任何域** ⇒ 放在本目录根。
- `proto` = **根级 proto 契约模块** —— 10 个域的 `.proto` 全部集中于此（pb 类包名 `com.rag.proto.<域>`）⇒ 域模块只依赖它。
- **不再有独立的 `*-contract` 模块** —— 各域的错误契约与事件 DTO 已并入域模块的 `com.rag.<域>.contract` 包。
- `service/users/` = **users 域的根**（一个域，两个子域 user / admin）—— 不是三个域。
- `users/common` = users 域的**公共库**（共享错误契约）—— artifactId 仍是 `users-common`，命名体现「库」，不是「域」。
- `users/user` / `users/admin` 是两个独立应用；它们**共享** `users/common` 与根级 `proto`。
- **物理目录 ≠ artifactId**：目录叫 `user` / `admin` / `article`，artifactId 仍是 `<域>-service`（`user-service` / `admin-service` / `article-service`）⇒ **不要用目录名反推 artifactId**。
- 每份文档含：模块坐标 · 依赖 · 带职责注释的目录树 · **该域的 L1 特殊约定** · 状态。
