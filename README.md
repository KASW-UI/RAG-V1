# RAG-V1

> 把 **RAG 仓（B 侧）** 的识海社区后端实现，用 **Spring Boot 惯用形态**重新组织一遍 —— **行为等价**，代码按 Java 生态重新排布。

## 两个仓库的关系

| 仓库 | 角色 |
|---|---|
| [KASW-UI/RAG](https://github.com/KASW-UI/RAG)（B 侧） | **唯一真源** —— 所有行为与实现的唯一判定标准（951 文件 / 157K 行 + 数百测试 + 逐条 DRIFT 台账）；**只读**，不反写 |
| **RAG-V1**（本仓） | **Spring Boot 惯用形态的重做** —— 每域一进程 + 每域一个 jar 模块 + 域内单层技术角色包树 |

> **行为判定只以 B 侧为准**（D16 · 2026-11-02 用户定）。本仓的实现直接从 B 侧抄
> （`docs/Mapping_Table/` 的 B↔C 对应 = 逐类抄写路线图）；**抄来的代码若与 B 侧冲突，以 B 侧改。**
> ⚠️ B 侧自身有已登记的差异（组件级 `NOT EQUIVALENT`）⇒ 本仓**继承并登记为签字偏离**，不回溯第三方。

## 当前状态（2026-11-02）

**形态：完整空文件树已落地；共享层 `common/` 已实现，10 个业务域待填。**

- 16 个 Maven 模块（13 叶子 + 2 聚合 + 1 根）· 670 个 Java 文件（`common/` 104 类已落地；`service/` 520 仍是空壳：package + 空类型 + TODO javadoc）
- 10 个 `.proto` 集中在根级 `proto/` 模块，pb 包名 `com.rag.proto.<域>`（内容与 B 侧逐字节一致）
- `common/` 是真 starter（`AutoConfiguration.imports` 注册 14 个 auto-config）
- 10 个域：`article` · `comment` · `favorite` · `follow` · `hot` · `like` · `message` · `task` · `users/user` · `users/admin`

⚠️ **「已落地」≠「已实现」** —— 台账里的 ✅ 一律读作「**在该证据强度下一致**」，不是已闭环。

## 快速开始

```bash
# 编译（当前唯一全绿的构建门禁：16/16 模块）
mvn -o clean test-compile

# 守门脚本：架构符合性 A1–A12
python3 tools/difftest/check-arch-invariants.py
python3 tools/difftest/check-arch-invariants.py --self-test   # 造违规假树自证判据能红

# 守门脚本：两方对照文档覆盖率（B 列 RAG / C 列本仓）
python3 tools/check-mapping-coverage.py

# 重新生成文件树文档（按实际文件系统生成，不会与代码漂）
python3 tools/gen-file-tree.py
```

### 关于 `mvn -o clean package` / `verify`

**当前为红，属既有问题**（非重构引入）：10 个 `*ServiceApplication` 仍是空壳、无 `main` 与 `@SpringBootApplication` ⇒ `spring-boot:repackage` 报 `Unable to find main class`；各域暂无 JUnit 依赖 ⇒ surefire 离线取不到 `surefire-junit3` provider。**填实现后自然消解**，现阶段以 `test-compile` 为准。

## 目录结构

```
RAG-V1/
├── pom.xml              根 pom（modules: common, proto, service）
├── proto/               10 个 .proto 集中地，pb 包名 com.rag.proto.<域>
├── common/              starter（104 类，14 个 auto-config）
├── service/             10 个域，每域一个 jar 模块
│   ├── like/            like-service
│   └── users/           聚合（users-common + user-service + admin-service）
├── docs/                决策记录 · 两方对照 · 三档台账 · 文件树
└── tools/               守门脚本（架构符合性 / 对照覆盖率 / 文件树生成）
```

域内单层技术角色包树：`controller` · `grpc` · `service` · `domain/{entity,model,service}` · `mapper` · `dto` · `config` · `interceptor` · `advice` · `exception` · `client` · `messaging` · `metrics` · `wiring` · `contract`

## 文档索引

完整索引见 **[`docs/README.md`](docs/README.md)**。动手前必读：

| 文档 | 作用 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | **根政策文件** —— 项目目的 · 语义对齐纪律 · 实现组织 · 协作规则 |
| [`docs/决策记录.md`](docs/决策记录.md) | D1–D16 已定 + P1–P7 待拍板 + 决策关系图 |
| [`docs/Mapping_Table/`](docs/Mapping_Table/_INDEX.md) | **抄写路线图** —— B 列 RAG ↔ C 列本仓的逐类对应 |
| [`docs/RAG-困境反哺.md`](docs/RAG-困境反哺.md) | B 侧踩过的坑 → 本仓的处置 |
| [`docs/架构符合性断言.md`](docs/架构符合性断言.md) | A1–A12 ⇒ 写测试 / 接框架时 |
| [`docs/GORM-MP-语义对照.md`](docs/GORM-MP-语义对照.md) | B 侧持久层写法 → 本仓 MyBatis-Plus 用法 |

**三档台账**：[`docs/DRIFT/`](docs/DRIFT/_INDEX.md)（逐条漂移）· [`docs/Pitfalls/`](docs/Pitfalls/_INDEX.md)（踩坑与元教训）· [`docs/Intentional_Deviations/`](docs/Intentional_Deviations/_INDEX.md)（签字偏离）

## 协作约定

- **每域一个 PR** —— PR 自带 spec（提交前已落仓）；issue 在 PR 之前，PR body 引用 issue
- 分支从 `main` 切出，不直接推 `main`
- 提交前跑 `mvn -o clean test-compile` + 三个守门脚本
- **「已修 / 未修」断言必须带核对日期**，否则按 `UNKNOWN` 处理
- 行为争议一律以 **B 侧源码** 为准；差异只允许出现在表达层

## 语义对齐纪律（最重要的一条）

本仓与 B 侧的差异**只允许出现在表达层**。proto 字段号 / 错误码值 / HTTP path / JWT claims / 表结构 / 事务边界 / 行级锁 / 端口 / 服务注册名 —— 一律逐字对齐 B 侧，改动须走决策流程。

三段式留痕（每个 idiomatization 都要能回答「这段比 B 侧多做了什么？语义还一样吗？」）：

```java
/** <p>L4 idiomatization：HandlerInterceptor 取代 B 侧的 rest.WithJwt + CheckBlacklistMiddleware。
 * <p>B 出处：service/users/user/user-api/.../routes.java（public 路径不带 JWT）。
 * <p>语义等价论证：public 路径集合逐条一致；blacklist key 前缀与 fail-open 一致；
 *   <b>HTTP 状态码差异</b>：B 侧写 401，本实现走异常处理器 —— 见已知差异。 */
```

## 技术栈

Java 17 · Spring Boot **3.5.11** · Jackson **2**（不使用 Jackson 3）· MyBatis-Plus · PostgreSQL · Redis · Nacos · Flyway 11 · Kafka · gRPC + protobuf 3.25.5
