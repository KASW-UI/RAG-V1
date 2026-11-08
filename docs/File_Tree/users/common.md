# users-common（users 域公共库） —— 文件树

> 本文件由 `tools/gen-file-tree.py` **按实际文件系统生成** ⇒ 不会与代码漂移。
> 改动树结构后重新运行该脚本。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 模块路径 | `service/users/common` |
| artifactId | `users-common` |
| 根包 | `com.rag.users.common` |
| B 侧出处 | `service/users/users-common/` |
| 说明 | **公共库**：users 域（user + admin）共享的错误契约 —— 不是第三个域 |

## 二、依赖

- `org.springframework:spring-jdbc`
- `jakarta.servlet:jakarta.servlet-api`
- `org.slf4j:slf4j-api`
- `org.springframework.boot:spring-boot-starter-test`
- `org.junit.jupiter:junit-jupiter`
- `org.testcontainers:postgresql`
- `org.testcontainers:junit-jupiter`
- `org.postgresql:postgresql`
- `com.rag:common`

## 三、目录树

```
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── rag/
│   │   │           └── users/
│   │   │               └── common/
│   │   │                   ├── ErrCodes.java    # users 域错误码常量集 —— 逐条对应 B 侧 {@code ErrorCode} 枚举的 31 个 {@code code} 值。
│   │   │                   ├── ErrMsg.java    # users 域 code → msg 文案映射 —— 逐条对应 B 侧 {@code ErrorCode} 枚举里每个常量的 {@code msg}。
│   │   │                   ├── IdentityResolver.java    # 权威身份解析器 —— ClaimedUID → GetUser → status presence 检查 → SubjectRef（user/admin 两子域共用）。
│   │   │                   ├── JdbcUserStatusLookup.java    # {@link IdentityResolver.UserStatusLookup} 的 JDBC 实现 —— 读 {@code users_user} 表取 {@code status}。
│   │   │                   └── SchemaInitializer.java    # users 域 DDL 的幂等执行器（无版本账本，每次启动原样重放）。
│   │   └── resources/
│   │       └── db/
│   │           ├── 01_create_user.sql
│   │           ├── 02_create_user_avatar_history.sql
│   │           ├── 03_create_admin.sql
│   │           └── 04_create_admin_invite.sql
│   └── test/
│       └── java/
│           └── com/
│               └── rag/
│                   └── users/
│                       └── common/
│                           ├── ErrContractParityTest.java    # users 域错误契约逐条对拍 B（码值集 + 文案映射 + 未知码回落）。
│                           ├── IdentityResolverTest.java    # 身份解析器的 5 个 sentinel + 两套 uid 语义 + 三元组产出（对 B 逐条）。
│                           ├── StringInt64ParityTest.java    # {@code common/web/StringInt64.parse} 的运行对拍（闭合迁移审核的 NOT_VERIFIED 项）。
│                           └── UsersSchemaAndStatusLookupTest.java    # 真 PostgreSQL 上的 users 域 DDL 重放 + status presence 判定。
└── pom.xml    # Maven 模块描述
```

## 四、特殊约定（L1 契约 · 勿凭直觉改）

- **为什么存在**：B 侧 `service/users/users-common/` 的错误契约（`errmsg/`）被 `user/api`·`user/rpc`·`admin/api`·`admin/rpc` **四方共用** ⇒ 抽为独立共享模块，避免错误码段复制两份。
- **文案是中文**（`"服务繁忙"`）—— 与 follow/hot/message 的英文文案不同。
- ⚠️ **不含 proto** —— proto 已集中到根级 `proto/` 模块 ⇒ 本模块只留错误契约。

## 五、状态

| 项 | 状态 |
|---|---|
| 目录结构 | ✅ 已落地（本文件由 `tools/gen-file-tree.py` **按磁盘实际生成** ⇒ 不与代码漂） |
| 文件内容 | ✅ **无空壳** —— 本模块 **9** 个 `.java`（含测试源）· **待填空壳 0 个**（待填空壳 = 类型体为空 **且** 留有 `TODO`；**有意为空**的类型 —— 空 struct DTO（决策 D5）· MP `BaseMapper` 接口 · 无成员 auto-config —— **不计**） |
| B 侧 ↔ Java 逐文件对照 | 📄 [`../../Mapping_Table/users/users-common.md`](../../Mapping_Table/users/users-common.md) —— 逐文件对照的**唯一权威副本**；由 `tools/check-mapping-coverage.py` **机器双向核验**（B 列 ⇄ C 列 ⇄ 磁盘） |
