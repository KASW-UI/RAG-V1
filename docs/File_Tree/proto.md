# proto（proto 契约根模块） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `proto` |
| artifactId | `proto` |
| 根包 | `com.rag.proto` |
| Go 出处 | `proto/` |
| 说明 | **10 个域的 `.proto` 契约集中于此**（pb 类包名 `com.rag.proto.<域>`）；域模块只依赖它，不再自带 proto |

## 二、依赖

- `io.grpc:grpc-stub`
- `io.grpc:grpc-protobuf`
- `com.google.protobuf:protobuf-java`
- `javax.annotation:javax.annotation-api`

## 三、目录树

```
├── src/
│   ├── main/
│   │   ├── proto/
│   │   │   ├── admin.proto
│   │   │   ├── article.proto
│   │   │   ├── comment.proto
│   │   │   ├── favorite.proto
│   │   │   ├── follow.proto
│   │   │   ├── hot.proto
│   │   │   ├── like.proto
│   │   │   ├── message.proto
│   │   │   ├── task.proto
│   │   │   └── users.proto
│   │   └── .DS_Store
│   └── test/
│       └── java/
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **`.proto` 契约集中到根级 `proto/` 模块** —— 10 个域各一份（`admin.proto` / `article.proto` / `comment.proto` / `favorite.proto` / `follow.proto` / `hot.proto` / `like.proto` / `message.proto` / `task.proto` / `users.proto`（user 域））⇒ 域模块**不再自带 proto**。
- **pb 类包名 `com.rag.proto.<域>`**（例 `com.rag.proto.like.LikeActionReq`）—— 不是 `com.rag.<域>.contract.proto.*` ⇒ 域代码 import 时用新包名。
- **文件内容与 Go 侧 `proto/*.proto` 逐字节一致** —— 只有 option 块与空白层面是表达层差异（`java_package` / `java_multiple_files`）。
- **Go 侧 `proto/` 多 2 份**（`points.proto` / `security.proto`）—— 那两个域**不在迁移范围**内 ⇒ 不是待办。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
