# MinIO-Jackson2-Isolation-Experiment.md

> ⛔ **本报告结论已作废（2026-11-02 · D14 工具链回退）**
>
> **本项目现为「全项目 Jackson 2」**：Spring Boot **3.5.11** + `com.fasterxml.jackson.*` **2.19.4**
> ⇒ 业务代码、第三方依赖、运行时 classpath **全部 Jackson 2** ⇒ **classpath 上零 Jackson 3**。
> ⇒ 本报告的「要不要迁 / 迁了多少钱 / 哪些文件要改」**全部不再适用**（Boot 4 时代的评估过程**保留作历史存档**）。
> ⇒ 现行口径 ⇒ [`docs/决策记录.md`](决策记录.md) **D14** · 索引 ⇒ [`docs/README.md`](README.md)。

> **实验问题**：RAG-V1 能否「业务代码统一 Jackson 3」的同时「MinIO SDK 全部实际使用场景正常工作」？
> **核对日期**：2026-11-01 · **性质**：只读审查 + **隔离**实验（正式 POM / 源码 / 测试 / 配置**未改**）。
> **配套**：[`SpringBoot4-Jackson3-Migration-Cost.md`](SpringBoot4-Jackson3-Migration-Cost.md) · [`SpringBoot4-Jackson3-Jackson2-Inventory.md`](SpringBoot4-Jackson3-Jackson2-Inventory.md)

---

## 一、项目关系与实验对象

| 项目 | 角色 | 本次实验中的用法 |
|---|---|---|
| `RAG`（B 侧 · Java 半成品） | **唯一真理**（本仓一切行为判定的基准） | **仅作参考**：取其 MinIO 调用面（`service/article/article-rpc/.../client/ArticleObjectStorageClient.java`）作为「实际使用场景」的推导依据 |
| **RAG-V1** | 完整 Spring 化目标项目 | **实验对象**：其依赖基线 = Spring Boot **4.0.8**；其树内已有空壳落点 `service/article/src/main/java/com/rag/article/wiring/MinioBucketBootstrap.java`（**尚未引入 MinIO 依赖**） |

**实验环境**：隔离目录 `/tmp/minio-jackson-isolation`（**不在任何 git 工作区内**）；真实 MinIO 服务端 = 本地 docker 镜像 `minio/minio:latest`，容器 `minio-iso`，端口 `127.0.0.1:19000`，账号 `minioadmin/minioadmin`。
**工作区未被污染**：`git status` 显示 RAG-V1 仅多出本报告与前两份报告（均为未跟踪新文件）。

---

## 二、真实依赖关系

### 2.1 MinIO SDK 的依赖声明（`~/.m2/repository/io/minio/minio/8.5.17/minio-8.5.17.pom`）

```
io.minio:minio:8.5.17
 ├─ com.carrotsearch.thirdparty:simple-xml-safe:2.7.1
 ├─ com.google.guava:guava:33.3.1-jre
 ├─ com.squareup.okhttp3:okhttp:4.12.0
 ├─ com.fasterxml.jackson.core:jackson-annotations:2.18.0   compile（非 optional）
 ├─ com.fasterxml.jackson.core:jackson-core:2.18.0          compile（非 optional）
 ├─ com.fasterxml.jackson.core:jackson-databind:2.18.0      compile（非 optional）
 ├─ org.bouncycastle:bcprov-jdk18on:1.78.1
 ├─ org.apache.commons:commons-compress:1.27.1
 ├─ commons-codec:commons-codec:1.17.1
 └─ org.xerial.snappy:snappy-java:1.1.10.7
```

### 2.2 实际解析结果（**版本被 Boot 4 的 BOM 覆盖**）

变体 A（默认）实测依赖树：

```
+- org.springframework.boot:spring-boot-starter-jackson:jar:4.0.8:compile
|  \- org.springframework.boot:spring-boot-jackson:jar:4.0.8:compile
|     \- tools.jackson.core:jackson-databind:jar:3.1.5:compile      ← Jackson 3
|        \- tools.jackson.core:jackson-core:jar:3.1.5:compile
\- io.minio:minio:jar:8.5.17:compile
   +- com.fasterxml.jackson.core:jackson-annotations:jar:2.21:compile
   +- com.fasterxml.jackson.core:jackson-core:jar:2.21.5:compile      ← Jackson 2
   +- com.fasterxml.jackson.core:jackson-databind:jar:2.21.5:compile  ← Jackson 2
```

⚠️ **注意**：Jackson 2 的版本不是 POM 里写的 **2.18.0**，而是被 **Boot 4 BOM 覆盖成 2.21 / 2.21.5** ⇒ RAG-V1 里不能再依赖「MinIO 带 2.18.0」这个前提。

### 2.3 Jackson 2 的引入路径（唯一一条）

`io.minio:minio:8.5.17` → `jackson-databind` / `jackson-core` / `jackson-annotations`（compile）。
**除此之外没有第二条引入路径**（Boot 4 侧只带 `tools.jackson.*`）。

### 2.4 包名冲突判定

- Jackson 2 = `com.fasterxml.jackson.*`；Jackson 3 = `tools.jackson.*` ⇒ **无同名类冲突**（实测两变体均正常启动）。
- 唯一共享的是 **`jackson-annotations`**（`com.fasterxml.jackson.annotation`）—— 它在 Jackson 3 里**仍是同一个包名**，且是 `tools.jackson.core:jackson-databind:3.1.5` 自己的依赖 ⇒ **变体 B 里它依然存在，但它是 Jackson 3 的依赖，不是 Jackson 2 运行时**。
  ⇒ 准确表述：**「运行时零 Jackson 2」= 零 `jackson-databind` / `jackson-core`（2.x）**；`jackson-annotations` 必然在，且无害。

### 2.5 SDK 内部 Jackson 的真实使用点（读 `minio-8.5.17-sources.jar` 源码定位，**不是靠依赖声明推断**）

| 类别 | 源码位置 | 用的 Jackson | 是否在 RAG-V1 需要的路径上 |
|---|---|---|---|
| **仅注解**（16 个 `messages/*` + `credentials/Credentials` + `credentials/Jwt`） | `io/minio/messages/ObjectMetadata.java:20` 等 | `com.fasterxml.jackson.annotation.*` | —— 包名两代通用 ⇒ **兼容** |
| **`S3Base` 的 Jackson** | `io/minio/S3Base.java:1617`（`NotificationResultRecords` 内部类的 `ObjectMapper mapper`）+ `:1681` `mapper.readValue(recordsString, NotificationRecords.class)` | Jackson 2 databind | ❌ **仅 `listenBucketNotification`（桶通知事件流）** —— 不在 put/get/stat 路径 |
| **`PostPolicy`** | `io/minio/PostPolicy.java:54` `new ObjectMapper()` + `:219` `writeValueAsString(policyMap)` | Jackson 2 databind | ❌ 仅**浏览器表单直传策略** |
| **`ServerSideEncryptionKms`** | `io/minio/ServerSideEncryptionKms.java:29` `new ObjectMapper()` + `:46` | Jackson 2 databind | ❌ 仅 **SSE-KMS 加密上下文** |
| **`credentials/MinioClientConfigProvider` / `IamAwsProvider`** | `MinioClientConfigProvider.java:38` / `IamAwsProvider.java:51` | Jackson 2 databind | ❌ 仅**读外部 config 文件 / IAM 临时凭证** |
| **`Result`** | `io/minio/Result.java:84-89`（`instanceof JsonMappingException` 重抛） | Jackson 2 异常类 | ❌ 仅通知记录路径 |

⇒ **结论**：SDK 确实需要 Jackson 2，但**只在这 4 类非对象读写路径上**；RAG-V1 需要的对象读写路径（makeBucket / bucketExists / setBucketPolicy / putObject / statObject / getObject / removeObject）**不触碰 Jackson databind**。

---

## 三、MinIO 实际调用路径（RAG-V1 需要的场景集合）

RAG-V1 尚未写 MinIO 代码 ⇒ 场景集合由两处推导：
1. **RAG 参考实现** `service/article/article-rpc/.../client/ArticleObjectStorageClient.java`：`MinioClient.builder()`（`:68`）· `makeBucket`（`:146`）· `bucketExists`（`:150`）· `setBucketPolicy`（`:163`）· `putObject`（`:207`）· `statObject`（`:228`）· `getObject`（`:232`）。
2. **RAG-V1 预留落点** `service/article/.../wiring/MinioBucketBootstrap.java`（空壳）⇒ 建桶 / 桶策略（对应上面的 makeBucket + setBucketPolicy）。
3. 补充：`removeObject`（删除）—— **B 侧**有对象删除语义，纳入验证。

⇒ **实验覆盖场景 = 上述 7 个 API + 1 条错误路径（对象不存在）**。

---

## 四、实验方案及执行结果

### 4.1 工程结构（隔离目录 `/tmp/minio-jackson-isolation`）

```
pom.xml                       ← parent spring-boot-starter-parent 4.0.8
                                deps: spring-boot-starter-web + spring-boot-starter-jackson
                                      + io.minio:minio:8.5.17
                                profile `no-jackson2`：对 minio 排除 jackson-databind/core/annotations
src/main/java/exp/IsolationApplication.java   ← @SpringBootApplication
src/main/java/exp/BusinessDto.java            ← 业务 DTO（注解用 com.fasterxml.jackson.annotation）
src/test/java/exp/MinioOpsTest.java           ← MinIO 真实对象操作
src/test/java/exp/Jackson3BusinessTest.java   ← @SpringBootTest：Jackson 3 Bean + 上下文内 MinIO
src/test/java/exp/JacksonSensitivePathsTest.java ← 反向对照：SDK 里确实需要 Jackson 2 的路径
```

### 4.2 变体 A —— 双版本共存（保留 MinIO 的 Jackson 2）

命令：`mvn -B test` ⇒ **Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS**

```
[BUSINESS] Jackson3 序列化 = {"article_id":42,"title":"标题"}
[BUSINESS] 上下文内 MinIO getObject = ctx-bytes
[BUSINESS] 上下文内 MinIO 全流程=OK
[OP] bucketExists=true
[OP] setBucketPolicy=OK
[OP] putObject+statObject size=12 contentType=text/plain
[OP] getObject=hello-rag-v1
[OP] removeObject=OK + 删除后 statObject 抛异常（错误路径）
[CLASSPATH] com.fasterxml.jackson.databind.ObjectMapper 可加载 = true
[SENSITIVE] PostPolicy.formData(J2 存在) = OK, 字段数=5
[SENSITIVE] configProvider(J2 存在) 构造 = OK -> io.minio.credentials.MinioClientConfigProvider
```

⇒ **A 成立**：Boot 4 上下文正常起；Jackson 3 `JsonMapper` Bean 正常；MinIO 全流程正常；Jackson 2 可加载 ⇒ **两代共存无冲突**。

### 4.3 变体 B —— 排除 Jackson 2

命令：`mvn -B -Pno-jackson2 test` ⇒ 依赖树中 `io.minio:minio:8.5.17` 下**零 Jackson 依赖** ⇒ **Tests run: 8, Failures: 0, Errors: 0 / BUILD SUCCESS**

```
[CLASSPATH] com.fasterxml.jackson.databind.ObjectMapper 可加载 = false      ← 运行时确无 Jackson 2 databind
[OP] bucketExists=true / setBucketPolicy=OK / putObject+statObject size=12
[OP] getObject=hello-rag-v1 / removeObject=OK + 删除后 statObject 抛异常
[BUSINESS] Jackson3 序列化 = {"article_id":42,"title":"标题"}
[BUSINESS] 上下文内 MinIO 全流程=OK
--- 反向对照（证明排除是「路径相关」）---
[SENSITIVE] PostPolicy.formData(无 J2) 抛 = java.lang.NoClassDefFoundError / com/fasterxml/jackson/core/JsonProcessingException
[SENSITIVE] MinioClientConfigProvider(无 J2) 抛 = java.lang.NoClassDefFoundError
```

⇒ **B 在 RAG-V1 需要的场景上成立**：排除 Jackson 2 后，7 个 API + 错误路径**全部真实跑通**（对真实 MinIO 服务端），且业务 Jackson 3 序列化不受影响。

### 4.4 反向对照的意义（为什么不能只报「B 绿」）

反向对照在变体 B 下**确实炸了**（`NoClassDefFoundError`）⇒ 证明：
1. MinIO SDK **确实有**运行时 Jackson 2 依赖（不是"白带一个 jar 没人用"）；
2. 排除是**路径相关**的，不是 SDK 普遍安全 ⇒ 若 RAG-V1 将来用 **桶通知订阅 / 浏览器直传 / SSE-KMS / IAM 临时凭证 / 外部 config 文件**，就必须把 Jackson 2 引回来。

---

## 五、测试覆盖范围

| # | 覆盖项 | 形态 | 变体 A | 变体 B |
|---|---|---|:--:|:--:|
| 1 | `MinioClient` 初始化 | 真实 MinIO | ✅ | ✅ |
| 2 | `makeBucket` + `bucketExists` | 真实 MinIO | ✅ | ✅ |
| 3 | `setBucketPolicy`（公开读策略） | 真实 MinIO | ✅ | ✅ |
| 4 | `putObject`（流式 + contentType） | 真实 MinIO | ✅ | ✅ |
| 5 | `statObject`（size / contentType 断言） | 真实 MinIO | ✅ | ✅ |
| 6 | `getObject`（**内容逐字节断言**） | 真实 MinIO | ✅ | ✅ |
| 7 | `removeObject` | 真实 MinIO | ✅ | ✅ |
| 8 | 错误路径：删除后再 `statObject` 抛异常 | 真实 MinIO | ✅ | ✅ |
| 9 | Spring Boot 4 上下文启动 | `@SpringBootTest` | ✅ | ✅ |
| 10 | 业务 Jackson 3 `JsonMapper` Bean 存在且序列化正确 | `@SpringBootTest` | ✅ | ✅ |
| 11 | 上下文内 MinIO 全流程（Jackson 3 与 MinIO 互不干扰） | `@SpringBootTest` | ✅ | ✅ |
| 12 | 运行时 Jackson 2 databind 可加载性断言 | `Class.forName` | ✅(true) | ✅(false) |
| 13 | **反向对照**：`PostPolicy.formData` | 源码级路径 | ✅ | ✅(预期 NoClassDefFoundError) |
| 14 | **反向对照**：`MinioClientConfigProvider` | 源码级路径 | ✅ | ✅(预期 NoClassDefFoundError) |

**未覆盖（明确标注，不能当作已验证）**：
- `listenBucketNotification`（桶通知事件流）—— **未跑**（需配桶通知）；但源码证据 `S3Base.java:1617/1681` 表明它**必然需要 Jackson 2** ⇒ 变体 B 下会失败（**推断，非实测**）。
- `ServerSideEncryptionKms`（SSE-KMS）—— 未跑；源码 `ServerSideEncryptionKms.java:29` 表明需 Jackson 2（**推断**）。
- `IamAwsProvider` —— 未跑（**推断**）。
- MinIO **版本升级**路径（是否存在不依赖 Jackson 2 的新版）—— **未验证**，本地仅 8.5.17。

---

## 六、每项结论的证据

| # | 结论 | 证据 | 强度 |
|---|---|---|---|
| 1 | RAG-V1 业务代码可统一用 Jackson 3 | 变体 A/B 均：`[BUSINESS] Jackson3 序列化 = {"article_id":42,"title":"标题"}`；Boot 4 自动配置 `tools.jackson.databind.json.JsonMapper` 正常注入 | **实测** |
| 2 | MinIO SDK **不必**在对象读写路径上使用 Jackson 2 | 源码定位（`S3Base.java:1617/1681` 等 4 处均在非对象读写路径）+ 变体 B 下 7 个 API 全部真实跑通 | **实测 + 源码** |
| 3 | Jackson 2 与 3 可在同一 JVM 共存 | 变体 A：`[CLASSPATH] ... 可加载 = true` 且全流程绿；包名不同（`com.fasterxml.jackson.*` vs `tools.jackson.*`） | **实测** |
| 4 | 排除 Jackson 2 后 MinIO 真实业务调用仍正常 | 变体 B：`[CLASSPATH] ... = false` + 7 个 API + 错误路径全绿（对真实 MinIO 服务端，非 mock） | **实测** |
| 5 | 排除是**路径相关**而非普遍安全 | 反向对照：变体 B 下 `PostPolicy.formData` → `NoClassDefFoundError: com/fasterxml/jackson/core/JsonProcessingException`；`MinioClientConfigProvider` → `NoClassDefFoundError` | **实测** |
| 6 | Jackson 2 的唯一引入路径是 minio | 两变体依赖树：排除后 `io.minio:minio:8.5.17` 下零 Jackson 依赖 | **实测** |
| 7 | Jackson 2 版本被 Boot 4 BOM 覆盖 | 变体 A 依赖树实测 `jackson-databind:2.21.5`（非 POM 声明的 2.18.0） | **实测** |
| 8 | `jackson-annotations` 不是「Jackson 2 残留」 | 变体 B 依赖树：它来自 `tools.jackson.core:jackson-databind:3.1.5`，是 Jackson 3 自己的依赖；包名两代通用 | **实测** |
| 9 | 存在可靠的隔离方案 | 变体 B 的 **Maven `<exclusions>`** 即可（不是 ClassLoader 方案）⇒ 最小成本 | **实测** |
| 10 | 是否有必要为 MinIO 追求运行时零 Jackson 2 | 取决于是否使用那 4 类非对象读写能力 ⇒ RAG-V1 当前场景**不需要** | **推断（基于 1/2/5）** |

---

## 七、对 Jackson 3 迁移计划的影响

1. **B1 阻塞项从「高风险·可能互斥」降级为「低风险·已解决」**：实验证明「运行时零 Jackson 2」**可达**，代价 = 在 `minio` 依赖上写 3 条 `<exclusion>` + 一条依赖树断言 + 域内冒烟 ⇒ **剩余成本 0.2~0.5 人天**（原估 0.5~2.0 人天，其中实验部分已完成）。
2. **Jackson 迁移增量下调**：14~28 人天 ⇒ **13.5~27 人天**（主要在高估值端下调；常规值 ~21 人天基本不变）。
3. **更新总工期**：49~79 人天 ⇒ **约 48.5~78 人天**，最可能 ~55~60 人天（**结论不变**）。
4. **给 RAG-V1 的具体落地要求**（建议写进该域的 DRIFT / 决策记录）：
   - 声明 `io.minio:minio` 时排除 `com.fasterxml.jackson.core:jackson-databind` 与 `jackson-core`；
   - 加一条**依赖树断言**：业务模块不得出现 `com.fasterxml.jackson.core:jackson-databind:2.x`；
   - 对象存储封装类**只允许**使用已验证的 7 个 API（makeBucket / bucketExists / setBucketPolicy / putObject / statObject / getObject / removeObject）⇒ 用到其它 API 必须重新引入 Jackson 2；
   - 若将来要用桶通知 / 表单直传 / SSE-KMS / IAM 临时凭证 ⇒ **登记为签字偏离**（它们需要 Jackson 2）。
5. **不需要**独立 ClassLoader、不需要替换 SDK、不需要改造 MinIO 调用代码 ⇒ 此前 §八「其他隔离方案（ClassLoader / 封装 SDK / 换版本）」整块**可删除**。

---

## 八、最终建议

- **最终状态：`PARTIALLY_CONFIRMED`**
  - ✅ **CONFIRMED 部分**：RAG-V1 业务代码可统一 Jackson 3；MinIO 的**对象读写全流程**（含错误路径）在**运行时无 Jackson 2 databind** 的条件下**真实可用**；两代 Jackson 可共存且无冲突；隔离方案就是一行 `<exclusion>`。
  - ⚠️ **未确认部分**：MinIO SDK 的 4 类非对象读写能力（桶通知 / 表单直传 / SSE-KMS / IAM 凭证）在排除后**会失败**（其中 2 类**已实测**炸，2 类为源码推断**未实测**）⇒ 「MinIO 在无 Jackson 2 下完全可用」**不成立**，只对**限定 API 集合**成立。
- **回答七个问题**：
  1. **业务代码能否统一 Jackson 3？** ✅ 能（实测）。
  2. **MinIO SDK 是否必须在运行时使用 Jackson 2？** ❌ **不必**（对象读写路径不需要；4 类其它能力需要）。
  3. **Jackson 2 与 3 能否同 JVM 共存？** ✅ 能（实测，包名不同无冲突）。
  4. **排除 Jackson 2 后真实业务调用是否仍正常？** ✅ **正常**（对真实 MinIO 服务端实测，非 mock）。
  5. **是否存在可靠的隔离方案？** ✅ 有：Maven `<exclusions>`（最小成本，无需 ClassLoader）。
  6. **是否有必要为 MinIO 追求运行时零 Jackson 2？** ⚠️ **不必须**；但成本已很低（0.2~0.5 人天），若能守住 API 集合则值得做，收益是依赖树干净 + 消除「未来误用 Jackson 2 API」的入口。
  7. **对迁移计划的影响**：**B1 阻塞解除**；Jackson 增量下调到 **13.5~27 人天**；总工期结论不变（~55~60 人天）；并新增一条域内纪律（对象存储封装只用已验证 API 集合）。
- **落地建议**：RAG-V1 引入 MinIO 时**直接带 `<exclusion>`** + 加依赖树断言；**不要**为此引入 ClassLoader 或替换 SDK；把「不得使用桶通知/表单直传/SSE-KMS/IAM 凭证」写进该域的封装类 javadoc 与 DRIFT 台账。

---

## 附录 · 复现命令

```bash
# 环境
docker run -d --name minio-iso -p 19000:9000 \
  -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio:latest server /data
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:19000/minio/health/live   # 200

# 变体 A（共存）
cd /tmp/minio-jackson-isolation && mvn -B test
# 变体 B（排除 Jackson 2）
cd /tmp/minio-jackson-isolation && mvn -B -Pno-jackson2 test
# 依赖树
mvn -B -o dependency:tree | grep -E "jackson|minio:jar"
mvn -B -o -Pno-jackson2 dependency:tree | grep -E "jackson|minio:jar"
```

> **实验产物位置**：`/tmp/minio-jackson-isolation`（隔离区，不在 git 内）· 日志 `/tmp/varA_full.log`、`/tmp/varB_full.log`、`/tmp/negA.log`、`/tmp/negB.log` · MinIO 源码解包 `/tmp/minio-src-1`。
> **未验证项**：见 §五「未覆盖」；**未修改**任何正式 POM / 源码 / 测试 / 配置。
