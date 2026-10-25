# users-common（users 域公共库） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/users/common` |
| artifactId | `users-common` |
| 根包 | `com.rag.users.common` |
| Go 出处 | `service/user/common/errmsg/errmsg.go` |
| 说明 | **公共库**：users 域（user + admin）共享的错误契约 —— 不是第三个域 |

## 二、依赖

- `io.grpc:grpc-stub`
- `com.google.protobuf:protobuf-java`
- `javax.annotation:javax.annotation-api`

## 三、目录树

```
├── src/
│   └── main/
│       └── java/
│           └── com/
│               └── rag/
│                   └── users/
│                       └── common/
│                           ├── ErrCodes.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
│                           └── ErrMsg.java    # ⚠️ 硬约束：错误契约逐域隔离，不可跨域统一
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **为什么存在**：Go 的 `service/user/common/errmsg/errmsg.go` 被 `user/api`·`user/rpc`·`admin/api`·`admin/rpc` **四方共用** ⇒ 抽为独立共享模块，避免错误码段复制两份。
- **文案是中文**（`"服务繁忙"`）—— 与 follow/hot/message 的英文文案不同。
- ⚠️ **不含 proto** —— proto 已集中到根级 `proto/` 模块 ⇒ 本模块只留错误契约。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地 |
| 文件内容 | ⬜ **全部待填**（每个 Java 文件只有 package + 空类型 + TODO javadoc） |
| Go ↔ Java 逐文件对照 | ⬜ 未开始（按 `docs/完全spring化的文件树.md` §6.2 的字段逐项填） |
