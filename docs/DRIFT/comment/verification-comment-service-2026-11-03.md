# comment 域迁移审核报告（RAG Boot 4 → RAG-V1 Boot 3.5）

> **本文件是 `docs/DRIFT/comment/` 下 comment 域的台账正文**（AGENTS.md §五.2「产出必写回本域目录」）。
> **审核对象**：C 侧 RAG-V1 的 comment 域实现是否**完整承接** B 侧 RAG 仓 comment 域的**实际职责与行为**。
> **判定基准**：**B 侧 = 唯一 oracle**（D16）。**L1 契约 / L2 业务语义 / L4 机制层全部以 B 侧为准** ⇒ 差异默认判 **RAG-V1 错**。
> **⚠️ 本报告不修改任何代码**（`.agents/verification.md` §十三）—— 发现的问题只登记证据与修复建议。
> **核对日期**：2026-11-03。**状态必带核对日期**（AGENTS.md §六），否则按 `UNKNOWN` 处理。

---

## 一、审核范围

### 1.1 两个项目的角色

| 标识 | 项目 | 路径 | 角色 |
|---|---|---|---|
| **B** | RAG（Boot 4） | `/Users/pr/Documents/java&golang/RAG/service/comment` | **迁移源项目** ＝ 行为对比基线（只读） |
| **C** | RAG-V1（Boot 3.5） | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/comment/service/comment`（分支 `align/comment`） | **迁移目标项目** ＝ 本次审核对象 |

### 1.2 审核的模块与文件规模（**实测**，非旧报告数字）

| 侧 | 组成 | 主代码 `.java` | 测试 `.java` |
|---|---|---|---|
| **B** | `comment-api` + `comment-common` + `comment-rpc`（**3 个模块 / 2 个进程**） | **83**（37 + 4 + 42） | **41** |
| **C** | `comment-service`（**1 个模块 / 1 个进程** · D1） | **69** | 见 §1.5 |

> ⚠️ **计数口径**：`find <域> -path '*/src/main/*' -name '*.java'`（**不含** `src/test/`、`target/`）。
> ⚠️ **C 侧 0 空壳**：实测 `find service/comment/src/main -name '*.java' -size -400c` **无输出** ⇒ 69 个主代码类**全部有实现**（AGENTS.md §0「已落地 ≠ 已实现」的反面：本轮已从空壳走到实现）。
> ⚠️ **C 侧 69 = B 侧 83 减去 14**：差值来源已逐条核到（§3.1/§3.2），**不是**丢职责。

**C 侧 69 个类的包分布（实测）**：

| 包 | 数 | 包 | 数 | 包 | 数 |
|---|---|---|---|---|---|
| `contract/` | 5 | `domain/entity/` | 7 | `mapper/` | 7 |
| `advice/` | 2 | `domain/service/` | 3 | `messaging/` | 4 |
| `client/` | 2 | `dto/comment/` | 16 | `metrics/` | 1 |
| `config/` | 3 | `exception/` | 1 | `service/` | 8 |
| `controller/` | 1 | `grpc/` | 1 + `PbCommentServiceAlias` | `interceptor/` | 1 |
| `wiring/` | 5 | 根（`CommentServiceApplication`） | 1 | | |

### 1.3 版本口径（**实际解析值**，非 POM 声明值）

| 组件 | B 侧 | C 侧 | 口径 |
|---|---|---|---|
| Spring Boot | **4.0.8** | **3.5.11** | 两侧 `spring-boot-starter-parent` |
| Spring Framework | 7.0.10 | 6.2.x | 随 Boot |
| Java | 21 | 21 | |
| Jackson | **Jackson 3**（Boot 4 线） | **Jackson 2.19.4**（D14 回退） | 全仓 `com.fasterxml.jackson.*`，**0 处** `tools.jackson` |
| gRPC | 1.63.0 | 1.63.0 | `<grpc.version>` 同值 |
| MyBatis-Plus | 3.5.17 | 3.5.17 | 同值 |
| Spring Cloud | — | 2025.0.3 | |
| Spring Cloud Alibaba | — | 2025.0.0.0（原生 nacos-client **3.0.3**） | |
| Flyway | 11.x | **11.14.1**（显式覆盖 Boot BOM） | D14 |

### 1.4 使用的资料与代码版本

| 资料 | 路径 | 用途 |
|---|---|---|
| Mapping_Table | `docs/Mapping_Table/comment/comment.md`（84 行映射） | B→C 责任定位依据（**不作为实现完成证据**） |
| File_Tree | `docs/File_Tree/comment.md`（69 主代码 + 测试树） | C 侧目标结构约束 |
| 决策记录 | `docs/决策记录.md`（D1–D18 + P1–P7） | 判断哪些差异是**已批准的主动变更** |
| 三档台账 | `docs/DRIFT/` · `docs/Intentional_Deviations/` · `docs/Pitfalls/` | 避免重复修已修项 / 误把签字偏离当漂移 |
| B 侧源码 | `/Users/pr/Documents/java&golang/RAG/service/comment`（**只读**） | 行为基线 |
| 代码版本 | C 侧工作树 `align/comment`，基线 `8de7818`（`main` 快进后） | |

### 1.5 本轮的验证读数（**全部真跑**）

> ⚠️ **证据强度分级**（AGENTS.md §0 指向 `_INDEX.md` 末节）：**E-4 双侧活体对拍** ＞ **E-3 真中间件/真容器** ＞ **E-2 源码级对照** ＞ **E-1 形态级对照** ＞ **E-0 未对照**。
> ✅ **本轮最强证据已是 E-4（双侧活体对拍）** —— 已建立 `tools/parity-probe/comment/` 并**真跑通过**（B 侧用本地现成镜像 `sea/comment-api:latest` / `sea/comment-rpc:latest`，C 侧用本仓 fat jar，**两侧同时运行**，对 **33 个请求**做逐字节比对）⇒ 详见 **§1.9**。
> ⚠️ **E-4 的覆盖面是「HTTP 面」**：gRPC 面**拓扑产物不可比**（B 侧两进程跨进程 gRPC vs C 侧单进程域内直连 · D2）⇒ gRPC 面的最强证据**仍是 E-3**。
> ⚠️ **E-4 本身不改变 §5.4 那 51 个 `NOT_COVERED` 点的判定** —— 那些点集中在 **L4 装配面 / 配置 → 运行期**（出向 deadline 上界、Redis 池参数、缓存打点次数、事实载荷 JSON 契约…）⇒ **黑盒 HTTP 探针观测不到** ⇒ 仍判 `NOT_VERIFIED`。
> ✅ **但本轮（§1.10 · E-5）用「装配层 / 进程内」断言把那 51 点里的 50 点补上了** ⇒ ⇒ **关掉这一类的是 E-5，不是 E-4** ⇒ **残留 1 点**（B 组第 ⑨ 点 · 根因 **D-15** 共享层差异）。

| # | 验证动作 | 证据强度 | 读数 | 判定 |
|---|---|---|---|---|
| V-1 | **去注释归一化逐行 diff**：`CommentDomainService`（B 883 行 / C 45 KB） | **E-2** | **B 550 行 = C 550 行**，差异**全部**为 import/package、`CommentSpans`→`CommentRpcMetrics` 合并、`SnowflakeBean` 实例→`Snowflake.nextId()` 静态、`CommentAuditMessage` setter→`CommentKafkaMsg` record 构造 | ✅ 零语义差异 |
| V-2 | 同上：`CommentTransactionalService` | **E-2** | **B 373 行 / C 371 行**；差异 = record 访问器 `getX()`→`x()` ＋ **B 侧 `outboxMapper` 字段被移除**（B 侧**只声明不使用**，实测无任何调用点 ⇒ 零行为影响） | ✅ 零语义差异 |
| V-3 | 同上：`ErrCodes` ↔ `CommentErrorCode`、`ErrMsg` ↔ `CommentErrorMessages`、`GrpcErrFactory` ↔ `CommentGrpcError`、`CodeError` ↔ `CommentCodeError` | **E-2** | 27 个码值**同序同值**；27 条文案**逐字相同**（含未知码回落 `"FAIL"`）；trailer key `rag-comment-biz-code` 不变；`findIn` 因果链遍历一致 | ✅ 逐字一致 |
| V-4 | span 常量集合比对：B `CommentSpans` 24 个常量 ↔ C `CommentRpcMetrics` | **E-2** | **缺失 0 个 · 取值差异 0 处**；C 侧另多 4 个 `MODULE_*`（来自 B 侧 `CommentRpcMetrics` 并入） | ✅ 一致 |
| V-5 | `CommentAuditListener` ↔ B 侧同名类 | **E-2** | 差异 = record 访问器 ＋ `MqConsumer.traceConsumer` 签名换形（共享层 API）＋ `toRuntime` 内联；**tracer 名两侧都是 `"comment-rpc"`**（B 侧字面量 vs C 侧常量，**同值**）⇒ span 名/属性不变 | ✅ 一致（`Error` 不再包 `IllegalStateException`，见 §5 D-06） |
| V-6 | **映射表 §7.4 12 条待订正项**逐条核对 | **E-2** | 12/12 已闭合（见 §5.3） | ✅ |
| V-7 | **守门 · 映射覆盖**：`tools/check-mapping-coverage.py comment` | — | **EXIT=0** · C 列 **69/69 = 100%** · B 列 **83/83 = 100%** · B 列全局未覆盖 **0** | ✅ |
| V-8 | **守门 · 架构不变量 A1–A13**：`tools/difftest/check-arch-invariants.py` | — | **PASS 10 · FAIL 0 · N/A 2 · PENDING 1** · **EXIT=0**（唯一 PENDING = **favorite 域**的 `FavoriteFactProcessLogging` 空壳，**不属本域**） | ✅ |
| V-9 | **守门 · docs ↔ 文件树**：`tools/difftest/check-docs-vs-tree.py` | — | **EXIT=0**（本轮**累计 15 个**新增测试类已用改过 `ROOT` 的 `gen-file-tree.py` 副本登记进 `docs/File_Tree/comment.md`；⚠️ 该脚本的 `ROOT` 仍**硬编码到主仓** ⇒ 在 worktree 里跑它会**重写主仓的 14 份文件树** ⇒ **工具侧债**，不在本域写权内） | ✅ |
| V-10 | **编译**：`mvn -o -q -pl service/comment -am compile -DskipTests` | — | **EXIT=0** | ✅ |
| V-11 | **测试**：见 §1.7 | **E-3** | 见 §1.7 | — |
| V-12 | **产物层**：真 fat jar 的 `BOOT-INF/lib/` 与运行时 classpath | **E-3** | `grpc-inprocess-1.63.0.jar` **在**（修前**不在**）；`BOOT-INF/lib` 条目 **175 → 176** | ✅ |
| V-13 | **进程层**：真 `java -jar` 起真应用 | **E-3** | **gRPC 端口真的在监听** ＋ 日志含 devh 的「gRPC Server started」＋ **无** `NoClassDefFoundError` | ✅ |
| V-14 | **位点层**：真 broker ＋ 真消费组已提交位点 | **E-3** | B-15 四条出口方向逐条钉住（§1.7 用例 21） | ✅ |
| **V-15** | **双侧活体对拍**：B 侧（本地现成镜像 · 两进程）与 C 侧（fat jar · 单进程）**同时运行**，对 **33 个请求**做**逐字节**比对（status + content-type + body）＋ 与冻结基线比对 | **E-4** | **33 例：24 例逐字节相等 ＋ 9 例已知差异（冻结双方各自字节）**；**播种未被污染**（两侧指纹一致）⇒ 详见 **§1.9** | ✅（9 例已知差异**根因已定位**，见 **D-14**） |

### 1.6 ⚠️ 已知的环境侧陷阱（**写给下一个跑本域的人**）

| 陷阱 | 现象 | 处置 |
|---|---|---|
| 🔴 **`tools/gen-file-tree.py` 的 `ROOT` 硬编码到主仓**（`ROOT = pathlib.Path("/Users/pr/Documents/GitHub/RAG-V1")`） | 在 worktree 里跑它 ⇒ **重写的是主仓的 14 份文件树**（按**主仓**的文件系统）⇒ worktree 的新增文件**不会**被它收录 | 要更新本域文件树 ⇒ 用 `sed` 改过 `ROOT` 的**副本**跑（勿改仓内脚本） |
| 🔴 **同一模块目录并发跑 maven ⇒ `target/` 竞争** | 症状是**满屏 `NoClassDefFoundError: com/rag/comment/...`（连本模块自己的主类都找不到）**，而 `target/classes` 明明有 `.class` | 本轮实测踩到：另一 agent 在我跑 surefire 期间执行了 `mvn clean` ⇒ **同一个模块目录下不要并发跑 maven** ⇒ 串行跑 |
| ⚠️ **`mvn -pl <module>` 不带 `-am` 会解析到陈旧的 `~/.m2` 里的 `common` jar** | 缺 `io.opentelemetry:opentelemetry-api` ⇒ 编译失败（像 `程序包 io.opentelemetry.context 不存在`） | **一律带 `-am`**（reactor 内解析） |
| ⚠️ **surefire 的 `-DfailIfNoSpecifiedTests=false` 属性名是错的** | 正确属性名是 **`-Dsurefire.failIfNoSpecifiedTests=false`**；用错 ⇒ `-am` 带上的 `common` 模块因「无匹配测试」直接 **BUILD FAILURE** | 用 `-Dsurefire.` 前缀那个 |
| ⚠️ **in-process gRPC server 名字是 JVM 内全局注册的** | 两个测试类用同一个名字 ⇒ 第二个起不来 | 每个测试类用**唯一** server 名 |
| 🔴 **`mvn -T1C`（并行构建）与「多域 Kafka 真链路测试」不相容** | 全仓并行构建时 `article` / `like` 的 Kafka 测试**变红**（`awaitPg` 超时 / `shadedNettyGrpcServerLifecycle` 起不来）⇒ 根因 = 多个域的测试**共享同一个 broker**（`127.0.0.1:49992`）⇒ 资源争用 / 消费组重平衡 / metadata 争抢 | **按域串行**跑各域的 Kafka 面（本域单跑 `-pl service/comment -am test` **226 例全绿**）；对照实验见 §1.8（串行跑 article+like **157 + 110 例全绿** ⇒ 与本域无关）|

### 1.7 测试读数（**最终回归 · 模块级汇总**）

> **口径**（AGENTS.md §五.8 守门四规则）：计数**只取回归日志的模块级汇总**
> （**不取** `surefire-reports/*.xml` 求和 —— 孤儿报告虚高）＋ **同断 `skipped=0`**。

**跑法**（`readers` 必须能复现）：

```bash
cd "/Users/pr/Documents/GitHub/RAG-V1.worktrees/comment" && mvn -o -pl service/comment -am clean verify
```

**读数**：

| 模块 | 模块级汇总 | 判定 |
|---|---|---|
| **`comment-service`（本域）** | **`Tests run: 301, Failures: 0, Errors: 0, Skipped: 0`** | ✅ **绿 ＋ skipped=0**（**上轮 226 ⇒ 本轮 301，+75**） |
| `common`（共享层，同 reactor） | `Tests run: 241, Failures: 0, Errors: 0, Skipped: 0` | ✅ 绿 ＋ skipped=0 |
| **reactor 合计** | **542 例 · 0 失败 · 0 错误 · 0 跳过** · `BUILD SUCCESS` | ✅ |

#### 本域 301 例的逐类读数（**全部真跑**，无 `@EnabledIfDockerAvailable`、无静默跳过）

| # | 测试类 | 例数 | 证据强度 | 覆盖什么 |
|---|---|---|---|---|
| 1 | `contract/CommentErrorCodeContractTest` | 5 | E-2 | 27 个错误码的**数值 + 顺序**逐条 1:1 |
| 2 | `contract/CommentErrorMessagesContractTest` | 10 | E-2 | 27 条文案逐字 ＋ 未知码回落 `"FAIL"` ＋ `findIn` 因果链 |
| 3 | `contract/CommentGrpcErrorRoundTripTest` | 15 | **E-3** | 真 in-process gRPC server ＋ 真 channel 的业务码往返 ＋ `pb.` 别名 |
| 4 | `proto/CommentProtoContractTest` | 8 | E-2 | proto 契约 ＋ **sha256 锚定** B 侧冻结文件 |
| 5 | `proto/CommentProtoWireParityTest` | 9 | E-2 | protobuf 消息体 wire 字节对拍 |
| 6 | `dto/comment/CommentDtoWireShapeTest` | 5 | E-2 | 16 个 DTO 的**线上 wire 形状** |
| 7 | `domain/service/CommentMetaNormalizationTest` | 6 | E-2 | `normalizeCommentMeta` 纯语义 |
| 8 | `messaging/CommentAuditMessageContractTest` | 7 | E-2 | 11 字段载荷 ＋ 生产 wire 与消费 DTO 逐字节 |
| 9 | `messaging/CommentAuditPreviewTest` | 5 | E-2 | `previewComment` 按 **rune** 截断 40 |
| 10 | `service/CommentSensitiveFilterAndKeysTest` | 10 | E-2 | 敏感词匹配 ＋ 5 个 Redis key 构造 |
| 11 | `service/CommentFlywayBaselineRealPgTest` | 19 | **E-3** | 真 PG ＋ 真 Flyway：账本表名独立 ＋ 空库 8 张表 |
| 12 | `service/CommentTransactionalSqlRealPgTest` | 14 | **E-3** | 真 PG ＋ 真事务：动态 SQL ＋ 回滚 ＋ 幂等删除 |
| 13 | `service/CommentFactEventTimeRealPgTest` | 7 | **E-3** | fact 时间归一化（UTC / Asia-Shanghai 双基准） |
| 14 | `service/CommentDomainFactRealPgTest` | 19 | **E-3** | fact 生成 ＋ 落库 ＋ 幂等 ＋ 排序边 |
| 15 | `grpc/CommentGrpcErrorWireRealTest` | 11 | **E-3** | 业务码穿真实传输层 ⇒ trailer `rag-comment-biz-code` |
| 16 | `grpc/CommentSpanExportRealTest` | 7 | **E-3** | 手写 span **真的被导出**（名 / tracer / 属性 / 值） |
| 17 | `grpc/CommentGrpcInterceptorWiringRealTest` | 3 | **E-3** | 拦截器真挂上 ＋ deadline 传播到 handler 线程 ＋ 服务端超时真打断 |
| 18 | `http/CommentHttpWireContractRealTomcatTest` | 33 | **E-3** | **真 Tomcat** 的 7 端点契约（401 空 body / 404 / 405+Allow / 5001 / 1006 …） |
| 19 | `http/CommentJwtTokenMatrixRealTomcatTest` | 14 | **E-3** | JWT 11 形态矩阵 ＋ 密钥 fail-closed ＋ **真 yaml 绑定** |
| 20 | `messaging/CommentKafkaAuditRealTest` | 4 | **E-3** | **真 broker**：建评论只推 Kafka ⇒ 消费侧才落库 ＋ 敏感词只改 status ＋ 落库副作用 ＋ 幂等 |
| 21 | `messaging/CommentKafkaFailureDirectionRealTest` | 4 | **E-3** | **真 broker + 真位点**：B-15 的**四条出口方向**（解析失败⇒丢弃 / 落库失败⇒不提交⇒重投 / 缓存失败⇒只记日志 / 通知失败⇒只记日志） |
| 22 | **`artifact/CommentArtifactClasspathGateTest`** | **4** | **E-3（产物层）** | 运行时 classpath 含 `grpc-inprocess` ＋ fat jar `BOOT-INF/lib` 条目 ＋ 内嵌 `common` jar 带共享层排序桥 ＋ **pom 源码层不许写 test scope** |
| 23 | **`artifact/CommentRuntimeBootGateTest`** | **1** | **E-3（进程级）** | **真 fat jar `java -jar` 起真应用 ⇒ gRPC 端口真的在监听** ＋ 无 `NoClassDefFoundError` |
| 24 | `CommentServiceApplicationTests` | 6 | **E-3** | 真上下文启动（`@EnableConfigurationProperties` 显式列举 3 个） |
| 25 | **`config/CommentRuntimeConfigContractTest`** | **16** | **E-3（装配层）** | 🔴 **C 组 10 点**（§1.10）—— Redis 池 `64/16/0/max-wait 4s` ＋ 连接超时 5s / 命令超时 3s ＋ `discovery:///` 三斜杠与**两斜杠反例** ＋ `target/classes` yml 逐字节一致 |
| 26 | **`config/CommentApiPropertiesSecretGuardTest`** | **9** | **E-3（生产同款启动）** | 🔴 **E 组**：真 `SpringApplication` ＋ 真 ConfigData 的 **4 条 fail-closed**（未设 / 空串 / 全空白 / 占位符原文）＋ 正常密钥对照组 ＋ 真 yaml 绑定 ＋ 判据边界 |
| 27 | **`http/CommentOversizedBodyRealTomcatTest`** | **4** | **E-3（真 Tomcat）** | 🔴 **E 组**：超限 body ⇒ **413 压过 401**（无 token / 无效 token 两形态）＋ 负控（正常 body ⇒ 401）＋ `max-bytes` 值 |
| 28 | **`service/CommentGoSemanticsGateTest`** | **4** | **E-2** | 🔴 **E 组**：short secret 的 **zero-pad 不改 HMAC**（与 Go 互通）＋ 不补齐被 jjwt 拒 ＋ `Span.current()` 无作用域根因条 ＋ 作用域退出还原 |
| 29 | **`service/CommentProtectionChainWiringTest`** | **2** | **E-3（装配层）** | 🔴 **E 组**：保护链**包装过的 call**（`isNotSameAs`）＋ 无包装层的反向对照 |
| 30 | **`service/CommentIdempotentDeleteRealPgTest`** | **2** | **E-3（真 PG）** | 🔴 **E 组**：**幂等删除只扣一次计数** ＋ 负控（第一次确实扣了） |
| 31 | **`service/CommentRedisDeadlineCarrierRealRedisTest`** | **2** | **E-3（真 Redis）** | 🔴 **E 组**：Redis 载体**耗尽 ⇒ 写命令不下发**（含**负控**：充足时写得进）＋ 事务绑定路径（`SessionCallback`）内层写也被拦 |
| 32 | **`service/CommentCacheRedisMetricGateTest`** | **7** | **E-3（真 Redis ＋ 真 PG ＋ 真 Micrometer）** | 🔴 **D 组**：D6/D7 打点**精确次数**（JSON 坏 ⇒ 只打 `GetSubjectWithCache` 且 == **2**；真异常 ⇒ 两个 label 各 == **2**；回填失败 ⇒ 读路径照常返回） |
| 33 | **`domain/service/CommentFactPayloadContractTest`** | **7** | **E-2** | 🔴 **D 组**：事实载荷 JSON 契约（`null` vs 缺失 · `old_state=0` 必输出 · 20/22 字段序 · `subject_ref` 守卫 · 互动 ID 前缀） |
| 34 | **`grpc/CommentOutboundDeadlineCapGateTest`** | **13** | **E-3（真 in-process gRPC）** | 🔴 **B 组**：出向 2s deadline 的**配置面 + 行为面**（无预算 / 30s 预算 / 生产 3s 形态 / 预算耗尽 / env 收紧 / `withDeadlineAfter` 代码行 == 0 / 无载体仍封顶 / 纳秒取预算） |
| 35 | **`grpc/CommentOutboundDeadlineCarrierGateTest`** | **9** | **E-3（真 socket ＋ 真 PG/Redis ＋ Spring 上下文）** | 🔴 **B 组**：载体与装配面（入向载体在 handler 线程真绑上 · devh 全局拦截器链真生效 · SQL 载体毫秒精度 + 在途取消 + 过期回滚 · Redis 载体耗尽） |
| | **合计** | **301** | | **0 失败 · 0 错误 · 0 跳过** |

#### 假绿证伪（**每条断言都被证明是活的**）

| 手法 | 结果 |
|---|---|
| **期望值变异**（worker A：3 处 · worker D：2 处 · worker E：3 处 · 主会话：2 处 jsonb 断言） | 变异后**当场变红** ⇒ 断言不是恒真 |
| **机制级自然变异**：`mvn surefire:test` 直调 goal ⇒ 解析到 `~/.m2` 里 **2026-11-02** 的陈旧 `common` jar ⇒ `deadlineIsPropagatedToHandlerThread` **由红转绿** | 该用例**确实在检测那个机制**（不是假绿） |
| **产物层自然变异**：修前的 pom（`grpc-inprocess` = `test` scope） | 修前 `java -jar` **启动失败** ＋ 修前 226 例**全绿** ⇒ **「跑绿 ≠ 本来就绿」**；修后产物层守门 5 例全绿 |
| **位点级变异**：`CommentKafkaFailureDirectionRealTest` 的「落库失败 ⇒ 不提交位点」 | 若实现改成「失败即提交」⇒ 该断言与「最终落库」断言**双双变红** ⇒ 判据有牙齿 |
| **本轮 4 组新增断言的变异（§1.10）**：C 组 **13 处** / D 组 **3 处** | 全部**精确转红** ⇒ 四组新守门点**都不是恒绿**；其中 C 组「把被拒的两斜杠 URI 改成三斜杠 ⇒ 该断言失败」⇒ **反例本身不是恒真** |

#### ⚠️ 本节的证据边界

1. **E-4（双侧活体对拍）本轮仍未做** ⇒ 「B 侧此刻真的产出同样字节 / 同样位点后果」**未证**。
2. ~~**B 侧 253 个守门点里，C 侧覆盖 181（71.5%）** ⇒ **51 点无对应断言** ⇒ 相应行为判 **`NOT_VERIFIED`**
   （明细见 §5.4）⇒ **这些行为不得按「一致」读取**。~~
   ✅ **本轮已更新**：§5.4 那 **51 个 `NOT_COVERED` 点**里 **50 点**已补成 C 侧真断言（§1.10）
   ⇒ **覆盖 231/253 = 91.3%**；**残留 1 点**（B 组第 ⑨ 点）因 **D-15**（共享层差异）**未闭合** ⇒ 仍判 **`NOT_VERIFIED`**。
3. 本节读数**只覆盖 C 侧单侧**；B 侧测试**未与 C 侧同时运行**。

### 1.8 全仓回归（`mvn -o -T1C clean verify`）与**本域之外的失败归属**

| 模块 | 结果 | 说明 |
|---|---|---|
| `RAG-V1` / `common` / `proto` / `service` | SUCCESS | `common` **241 例 0 失败 0 跳过** |
| **`comment-service`（本域）** | **SUCCESS [01:09 min]** | **226 例 0 失败 0 错误 0 跳过** |
| `follow-service` | SUCCESS [39.8 s] | —— |
| `message-service` | SUCCESS [52.0 s] | —— |
| `users` / `users-common` | SUCCESS | —— |
| `article-service` | FAILURE [01:25 min] | **不在本域**：Kafka 真链路 6 个 error（`Failed to start bean 'shadedNettyGrpcServerLifecycle'`） |
| `like-service` | FAILURE [03:02 min] | **不在本域**：`LikeKafkaThreeFaceRealTest` 2 个 `awaitPg` 超时 |
| `favorite-service` / `hot-service` / `task-service` | FAILURE [2.5 s] | **不在本域**：这三域**仍是空壳**（AGENTS.md §0）⇒ 连 `main` class 都没有 ⇒ `spring-boot:repackage` 必然报「Unable to find main class」 |
| `user-service` / `admin-service` | SKIPPED | reactor 在 `favorite-service` 处中断 |

#### 🔴 判「article / like 的失败**不是**本域造成的」—— 对照实验

**实验**：`mvn -o -pl service/like,service/article -am test`
（**串行** ＋ **reactor 里根本没有 `comment` 模块** ⇒ comment 域的测试**根本没跑**）

| 模块 | 读数 | 结论 |
|---|---|---|
| `article-service` | **`Tests run: 157, Failures: 0, Errors: 0, Skipped: 0`**（含 3 个 Kafka 真链路类 2＋2＋2 例**全绿**） | ✅ **串行全绿** |
| `like-service` | **`Tests run: 110, Failures: 0, Errors: 0, Skipped: 0`**（含 `LikeKafkaThreeFaceRealTest` 4 例**全绿**） | ✅ **串行全绿** |
| 整体 | **BUILD SUCCESS** | ✅ |

⇒ ⇒ **同一份代码，串行绿、并行红** ⇒ ⇒ 根因是 **`-T1C` 并行构建下多个域的 Kafka 测试共享同一个 broker**
（`127.0.0.1:49992`）造成的**资源争用 / 消费组重平衡 / metadata 争抢**，
**与 comment 域无关**（该实验的 reactor 里 comment 域**一行代码都没跑**）。

⚠️ **归属**：这是**仓库级**的问题（`-T1C` 与多域 Kafka 真链路测试不相容），**不在本域写权内**（AGENTS.md §五.2）⇒
**如实登记，不代改**。⇒ 收口人若要跑全仓守门，建议**串行**（`mvn -o clean verify` 不带 `-T`），
或**按域串行**跑各域的 Kafka 面。

#### ⚠️ 关于「本域用例在并行构建里也是绿的」

`comment-service` 在 `-T1C` 全仓构建里 **SUCCESS** ⇒ ⇒ 本域的 Kafka 真链路测试**没有**被并行构建打穿
（但这**不等于**并行下本域一定稳 —— 本域只占 1 个消费者组，与 article/like 的 topic 不重叠）。
⚠️ **本轮（上轮）该读数是 226 例；本轮补缺口后为 301 例** ⇒ ⇒ 该断言的**覆盖面随读数一起变**，
但**归属结论不变**（并行构建打不穿本域）。

---

### 1.9 🔴 双侧活体对拍（E-4）—— 本轮**已做**（2026-11-04）

> **一句话**：B 侧（**本地现成镜像**，Boot 4 · 两进程）与 C 侧（**本仓 fat jar**，Boot 3.5 · 单进程）
> **同时真跑起来**，对同一批 **33 个请求**做**逐字节比对** ⇒ **HTTP 面**两侧行为一致。
> 探针：**`tools/parity-probe/comment/`**（`run.sh` · `probe-isolation.sh` · `mutate-parity.sh` · `expected.txt` · `known-diff.txt` · `README.md`）。

#### 1.9.1 两侧形态与隔离面

| | **B 侧**（oracle · 只读） | **C 侧**（被测） |
|---|---|---|
| 形态 | **两进程**：`comment-api`（HTTP）+ `comment-rpc`（gRPC） | **一进程**：Tomcat + gRPC 合并（D1 + D2） |
| 运行 | **本地现成镜像** `sea/comment-api:latest` / `sea/comment-rpc:latest` ⇒ **B 仓一个字节都不改** | `mvn -o -pl service/comment -am package` 的 fat jar |
| 栈 | Boot **4.0.8** · Spring 7.0.9 · Tomcat 11.0.24 · **Jackson 3.1.5** | Boot **3.5.11** · Tomcat 10.1 · **Jackson 2.19.4** |

**五个隔离向量**（每轮独立 ⇒ 可并发跑）：

| 向量 | 内容 | 为什么必须 |
|---|---|---|
| **V1** | **两个** Nacos 命名空间（`probe-b-parity` / `probe-c-parity`） | 两侧**注册的服务名是同一个** —— `comment-rpc`（B 侧 `…/rpc/bootstrap/NacosRegistration.java` ／ C 侧 `wiring/NacosRegistration.java:68`）⇒ 共用一个命名空间 ⇒ B 的 `comment-api` 用 `discovery:///comment-rpc` **可能选中 C 侧实例** ⇒ 探针比的变成「B 侧 vs 随机一侧」⇒ **假绿** |
| **V2** | 两侧**各一个 Postgres 容器** | 🔴 **两侧的 Flyway 账本表名是同一个**：`flyway_schema_history_comment`（B 侧 `comment-rpc/…/application.yml:126` ／ C 侧 `application.yml`）⇒ 共用一个 PG ⇒ 后启动的一侧**自己的 V1..V8 全被跳过** ⇒ 少一半表 ⇒ 读面假红（**本域比 article 域更硬** —— article 域两侧账本表名不同） |
| **V3** | 宿主端口**租约**（原子 `set -o noclobber`） | 端口写死 ⇒ 两份必撞；且**必须先与「容器已发布的宿主端口」求交**（撞上时 `lsof` 返回 Docker Desktop backend ⇒ 一杀 ⇒ **整个 Docker 失联**） |
| **V4** | `mvn` **串行锁** ＋ 每轮把 fat jar **拷进自己的 WORK** | `target/` 是**同一份** ⇒ 并发写必互踩；实测 `-Dproject.build.directory` **不生效** |
| **V5** | 两侧**各一个 Redis 容器** | 🔴 **两侧的 Redis key 空间逐字相同**（`comment:subject:<id>` / `comment:content:<id>` / `comment:index:<type>:<id>:<root>:<sort>:<offset>:<limit>` / `comment:index:item:<id>`；B 侧 `…/service/CommentRedisKeys.java:31,36,47,60` ／ C 侧 `service/comment/…/service/CommentRedisKeys.java:60,72,86,104`）⇒ **共用一个 Redis ⇒ 两侧读同一份缓存 ⇒ 假绿** |

#### 1.9.2 读数（**逐条真跑**）

| 项 | 读数 |
|---|---|
| 请求矩阵 | **33 例**：**24 例两侧逐字节相等** ＋ **9 例已知差异**（见 §1.9.4 / **D-14**） |
| 鉴权面（8 例） | 缺 header / 坏签名 / 过期 ⇒ **401 + 空 body + 无 content-type**（两侧逐字节相同）；无 `userId` claim ⇒ **200 + 1006**（**6 个身份端点**）／ **list 端点不报 1006**（B 侧 `GetCommentService` 差异 1 的**活体证据**）；`userId` 为字符串 `"1001"` ⇒ 两侧**都接受**（与 like 域口径相反） |
| 校验面（9 例） | 7 个端点各一条「必填字段缺失」＋ 空 body ＋ 非法 JSON ＋ 空 `target_type` ＋ 空 `target_id` ⇒ 两侧均 **200 + `{"code":5001,"msg":"系统内部错误","data":null}`**（逐字节相同） |
| 路由面（4 例） | 未知路径（带/不带 token）⇒ **404 + `text/plain;charset=utf-8` + `404 page not found\n`**（两侧相同）；方法不允许 ⇒ **405 + 裸状态码 + 无 body + 无 content-type**；前缀写错 ⇒ 404 |
| 媒体类型面（1 例） | `Content-Type: text/plain` ⇒ 两侧**都**走 `StringHttpMessageConverter` ⇒ **200 + 5001**（不是 415）⇒ 逐字节相同 |
| 读面（9 例） | 播种后 list：时间序 / 热度序 / 翻页 / 回帖 / **四条业务层兜底**（`page≤0`、`page_size>50`、`sort_type∉{0,1}`、`root_id<0`）⇒ **业务语义逐条一致**（值完全相同）；**不存在的 target** ⇒ 两侧**都**返回 **200 + 5001**（B 侧 `getcommentlogic.go:72-75` 是**裸 `return nil, err`** ⇒ 不做 code 转换 ⇒ 兜底 5001）⇒ 这是 **L2 语义差异的活体证据** |
| **播种未被污染** | ✅ **两侧指纹一致**（`subject/comment_index/comment_content` 的行数与正文指纹）⇒ 证明本轮 Kafka 隔离生效、读面读数未被审计消费者写入污染 |
| **变异验证** | ✅ **M1–M4 全部精确转红**（改 C 侧库 / 改 B 侧库 / 改冻结基线 / 改 known-diff 的 C 侧字节）⇒ **探针不是恒绿** |
| 退出码语义 | `0` = 一致（或差异已登记且形状未变）· `1` = **真差异** · `2` = **环境问题**（**与真差异严格分开**，且必附原始报错） |

#### 1.9.3 ⚠️ **不可比面**（**显式声明**，不是遗漏）

| 面 | 为什么不可比 |
|---|---|
| **gRPC 面** | **拓扑产物 · 不是行为漂移**：B 侧 = 两进程（api 走 `discovery:///comment-rpc` **跨进程 gRPC**）；C 侧 = 一进程（api **直连**领域层，**没有网络跳** · D2）⇒ 只能对拍 HTTP 面 ⇒ **gRPC 面最强证据仍是 E-3** |
| **写路径**（`create` 等 6 个端点的成功响应） | `create` 的响应带**随机雪花 ID**且**同步推 Kafka** ⇒ 两侧字节必然不同 ⇒ 逐字节比它 = **必然红且红了也不代表有错** ⇒ 写路径真链路由仓内 `CommentKafkaAuditRealTest` / `CommentKafkaFailureDirectionRealTest` 等**真触发测试**覆盖 |
| **响应头全集** | 只比 **status + content-type + body**（与 article 域探针同口径）⇒ 例：`405` 的 `Allow` 头**不参与比较**；`Date` / `Set-Cookie` 等易变头不比 |

#### 1.9.4 🔴 已知差异（**9 条 · 根因已定位 ⇒ D-14**）

**现象**：`/comment/v1/comment/list` 响应里 `data.comment[0]` 与 `data.subject` 两个对象的 **JSON 键序**两侧不同；
**解析后取值完全相同**（已用 `json.loads` 逐条核对：**9/9 值相等**）。

| | 键序 |
|---|---|
| **B 侧** | **字母序**：`attribute, children, content, created_at, dislike_count, id, like_count, meta, parent_id, reply_count, root_id, state, user_id` |
| **C 侧** | **声明序**：`id, user_id, content, root_id, parent_id, like_count, dislike_count, reply_count, attribute, state, created_at, meta, children` |

⇒ **根因 = Jackson 3 ↔ Jackson 2 的框架版本分叉**，**不是 C 侧的业务逻辑缺陷** ⇒ 详见 **§5.1 D-14**。

#### 1.9.5 ⚠️ 本轮踩过的探针侧坑（**写给下一个跑本域的人**）

| 坑 | 现象 | 处置 |
|---|---|---|
| 🔴 **1xx 响应头污染状态码判定** | 共享层 `ClientDisconnectProbe` 会**直接往 socket 写** `HTTP/1.1 103 Early Hints\r\n\r\n`（**不经过 Tomcat**）⇒ curl 的 `-D` 文件里**多出一段 1xx** ⇒ 用 `head -1` 取状态码会把 **103** 当最终状态 ⇒ **假红** | 按 **RFC 9110 跳过所有 1xx**：只认 `status ≥ 200` 的那一段（`run.sh` 的 `parse_final_response`） |
| 🔴 **脚本里一处少写一个 `}` 会被 bash 静默吞掉几百行** | 症状是「某个变量 unbound」，而**真正的原因在几百行之前**；`bash -n` **查不出来**（整段被当成字符串 ⇒ 语法仍然合法） | `run.sh` 加**变量自检**段（0/10 步）⇒ 坏在哪儿一目了然 |
| 🔴 **`comment_content` 的主键列名是 `comment_id`（不是 `id`）** | 指纹 SQL 写成 `id` ⇒ 整条 SQL 报错 ⇒ 而 `2>/dev/null` 把错误吞了 ⇒ 指纹变**空串** ⇒ 误报「库被改动」 | 列名逐字对齐 V3 迁移脚本 |
| ⚠️ **PG 就绪判据必须是「走 psql 真正会走的那条路」** | `pg_isready` 过了，紧接着的 `psql` 仍可能报 `socket "/var/run/postgresql/.s.PGSQL.5432" failed` | 直接跑一条真查询 |
| ⚠️ **本轮 Kafka topic/group 必须覆盖成专属值** | 两侧审计 topic 名与消费组**逐字相同** ⇒ 与宿主上别的 comment 测试共用 broker ⇒ 对方 publish 的消息会被**本轮某一侧**消费 ⇒ 那一侧库里多出评论 ⇒ **读面假红** | 用 `SPRING_APPLICATION_JSON` 注入**精确属性名**（env 形式是 `COMMENT_KAFKA_AUDITTOPIC`，**易写错**） |
| ⚠️ **上一轮硬中断会留下探针容器** | 端口被占 ⇒ 本轮 `docker run` 绑不上 ⇒ 整轮起不来 | 探针先扫掉名字前缀 `parity-comment-` 的残留容器 |
| 🔴 **变异脚本自己崩掉 ⇒ 看起来像「探针假绿」** | `expected.txt` 的**第一条** CASE 是 **401 空 body** ⇒ hex 是**空串** ⇒ 对它做 `h[0]` ⇒ `IndexError` ⇒ **文件根本没被改** ⇒ 探针照常绿 ⇒ 「M3 没转红」 | 变异脚本**跳过空 hex 的行** ＋ **断言文件真的变了**，否则**退出码 2**（明确区分「变异脚本自身坏了」与「探针假绿」）⇒ **本轮真踩过一轮**（首次跑 M3 未转红 ⇒ 查出来是变异脚本的 bug，不是探针假绿） |

### 1.10 🔴 本轮补缺口（**E-5**）—— 把 §5.4 那 **51 个 `NOT_COVERED` 点**补成真断言

> **本节回答 §5.4 留下的那个洞**：「§5.4 那 51 点不是『C 侧行为错了』，而是『C 侧行为没被证明』」。
> ⇒ ⇒ 本轮**在装配层 / 进程内**补断言（**不是**再加 HTTP 黑盒探针 —— 那观测不到 L4 装配面）。

#### 1.10.1 口径与做法

| 项 | 内容 |
|---|---|
| **范围** | §5.4 的 **B 组 16 · C 组 10 · D 组 12 · E 组 13** = **51 点**（⚠️ **E 组原写「11」是计数笔误，本轮订正为 13** —— 见 §5.4） |
| **形态** | **装配层 / 进程内**断言：真 Spring 上下文 · 真 devh 拦截器链 · 真 in-process + 真 socket gRPC · 真 Redis（Testcontainers）· 真 PostgreSQL 16 + 真 Flyway · 真 Micrometer `SimpleMeterRegistry` |
| **交付物** | **11 个新测试类 / 4,769 行**（`service/comment/src/test/java/com/rag/comment/` 下）⇒ 本域测试类 29 → **40**，例数 226 → **301（+75）** |
| **构建读数** | `mvn -o -pl service/comment -am clean verify` ⇒ **comment-service: Tests run: 301, Failures: 0, Errors: 0, Skipped: 0** ＋ `common: 241, 0, 0, 0` ＋ **BUILD SUCCESS** |
| **闭合结果** | **51 点里 50 点闭合**（C 10/10 · D 12/12 · E 13/13 · B **15/16**）⇒ 覆盖 **231/253 = 91.3%**；**残留 1 点**（B 组第 ⑨ 点）**未闭合** ⇒ 见 §1.10.4 与 **D-15** |
| **守门纪律** | 每个断言都带**三段式 javadoc**（① 多做了什么 ② 语义还一样吗 ③ B 出处 `文件:行号`）· 禁 `@Slf4j` / `@SneakyThrows` · Jackson 只用 2 · **禁 `@EnabledIfDockerAvailable`** · **零 skipped** |

#### 1.10.2 四组逐条读数

| 组 | 点数 | 新测试类 | 例数 | 覆盖的守门点（逐条） |
|---|---|---|---|---|
| **C 组**（L4 配置面） | **10 / 10** ✅ | `config/CommentRuntimeConfigContractTest` | **16** | 池**已启用**（消费点 = Boot 3.5 `LettuceConnectionConfiguration#isPoolEnabled`）· `maxTotal==64` · `maxIdle==16` · `minIdle==0`（对齐 Go `MinIdleConns` **无 default ⇒ 0**，**不是 4 也不是 8**）· `maxWait==4s` · **阳性对照**（commons-pool2 默认 `maxWait==-1ms`）· 连接超时 **5s** · 命令超时 **3s** · 生产 grpc client 集合 + **三斜杠** + **两斜杠必须被拒绝**（devh 3.1.0 抛 `IllegalArgumentException`）· 解析出的服务名一致 · **`target/classes` yml 与源码树逐字节一致** |
| **D 组**（L2/L4 领域语义） | **12 / 12** ✅ | `service/CommentCacheRedisMetricGateTest` ＋ `domain/service/CommentFactPayloadContractTest` | **7 + 7** | 缓存打点**精确次数**：命中/未命中 ⇒ **0 个**错误指标 · JSON 坏 ⇒ **只**打 `GetSubjectWithCache` 且计数 == **2** · Redis 真异常 ⇒ 两个 label **各 == 2** · 回填失败 ⇒ 读路径**照常返回** + `SetSubjectCache` >= 1 · 载荷契约：无 omitempty ⇒ **null**（不是缺失）· 有 omitempty ⇒ **整个消失** · **`old_state=0` 必须被输出** · 4 个固定常量 + `event_time` 是**文本** · **20 / 22 字段序**（`containsExactly`）· `subject_ref` 格式 + `uid<=0` 抛错 · 互动 ID 前缀 `rtw.comment.interaction.` |
| **E 组**（跨层） | **13 / 13** ✅ | `config/CommentApiPropertiesSecretGuardTest` ＋ `http/CommentOversizedBodyRealTomcatTest` ＋ `service/CommentGoSemanticsGateTest` ＋ `service/CommentProtectionChainWiringTest` ＋ `service/CommentIdempotentDeleteRealPgTest` ＋ `service/CommentRedisDeadlineCarrierRealRedisTest` | **9 + 4 + 4 + 2 + 2 + 2** | **生产同款启动**（真 `SpringApplication` ＋ 真 ConfigData）**4 条** fail-closed（未设 / 空串 / 全空白 / 占位符原文）＋ 正常密钥对照组 · 判据边界（null/空串/全空白/未解析占位符）· **真 yaml 绑定到嵌套字段** · **超限 body ⇒ 413 压过 401**（无 token / 无效 token ＋ 负控 401）· **zero-pad 不改 HMAC**（与 Go 互通）＋ 不补齐被 jjwt 拒 · 保护链**包装过的 call**（`isNotSameAs`）＋ 无包装层反向对照 · **幂等删除只扣一次计数**（＋ 负控）· Redis 载体**耗尽 ⇒ 写不下发**（＋ 负控）＋ `SessionCallback` 事务绑定路径内层写也被拦 · `Span.current()` **无作用域根因条** |
| **B 组**（出向 2s deadline） | **15 / 16** 🟠 | `grpc/CommentOutboundDeadlineCapGateTest` ＋ `grpc/CommentOutboundDeadlineCarrierGateTest` | **13 + 9** | 配置面：yml 必须是 `${COMMENT_OUTBOUND_DEADLINE_CAP_MILLIS:2000}` 形态 ＋ 绑出 `cap==2000` 且开关开 ＋ **按服务全名覆盖**为空 · 行为面：**无预算** / **30s 预算** / **生产 3s 形态** 三种都必须在 **2s** 处 `DEADLINE_EXCEEDED`（耗时 < 2400 / 2400 / 2600ms）· **预算耗尽 ⇒ 立刻失败**（< 500ms）· **env 收紧 ⇒ 上界跟着收紧**（证明 yml 那个键**真被读到**）· **`withDeadlineAfter` 代码行 == 0**（点位级棘轮）· **无载体仍套 2s 封顶** ＋ **cap=0 阳性对照**（证明上界是机制决定的）· **纳秒取预算**（精度损失 < 0.4ms）· **入向更长 ⇒ 压到 2s** / **入向更短 ⇒ 继承入向剩余** · **载体面**：入向载体在 **handler 线程真绑上**（真 socket）· **devh 全局拦截器链真生效**（真出向调用在 2s 处断）· **SQL 载体毫秒精度 + 在途取消 + 过期回滚（0 行）** · **Redis 载体耗尽 ⇒ 写不下发** |

#### 1.10.3 假绿证伪（**本轮新断言都被证明是活的**）

| 手法 | 结果 |
|---|---|
| **C 组：13 处变异**（逐条改期望值 / 改 yml 值 / 把被拒 URI 改成三斜杠 / 把打包 yml 改掉 …） | **13/13 精确转红** ⇒ 含「**两斜杠反例**」在内**每条都不是恒真** |
| **D 组：3 处变异**（把 `readSubjectCacheDetailed` 的两个 catch **合并** ⇒ JSON 坏也打 `GetSubjectCache`；去掉回填 try/catch；去掉全部 `observeRedisError`） | **3/3 转红** ⇒ 打点的**精确次数**与**降级语义**确实被钉住 |
| **本轮唯一的红（第 1 次构建）** | 第 1 次 `clean verify` = **301 例 / 1 失败** ⇒ 那一条**如实暴露了 D-15**（共享层出向载体未绑）⇒ ⇒ **本轮新断言不是恒绿**（它们把一条真漂移抓出来了） |
| 🔴 **D-15 棘轮的「双向」验证**（本轮最有价值的一次变异） | ① **基线**（当前 C 侧）：`outboundCarrierIsNotBoundAndClientDeadlineIsNotHonouredYet` ⇒ **绿**（`started=1 succeeded=1 failed=0 skipped=0`）<br>② **变异**（把共享层的 `ObservabilityAutoConfiguration` 复制到**仓外** `/tmp`，按 **B 侧 `RpcInterceptor.java:517-520` 的形态**补上 `GoStyleRequestDeadline.bindAbsoluteNanos(...)`，编译后**前置**到 classpath）⇒ 同一条 ⇒ **红**（`started=1 succeeded=0 failed=1`），失败信息正是「**本条变红 = 共享层已修**（DRIFT-COMMON-V17 的「另案」闭合）⇒ ⇒ 届时请把本条改回 B 侧真值断言并撤掉台账 D-15」  ⇒ ⇒ **这条绿断言不是「恒假」**：它**恰好**在共享层把漂移修掉的那一刻翻红 ⇒ ⇒ **棘轮方向正确、牙齿有效** ⇒ ⇒ 仓内 **0 改动**（变异体全程在 `/tmp`） |

#### 1.10.4 ✅ B 组第 ⑨ 点的**根因已消除**：**D-15 已由 `global` 收口域闭合**（2026-11-05 · 合并工作树时销账）

- **B 侧真值**：`common/…/observability/RpcInterceptor.java:517-520` ⇒ `onHalfClose` 把**同一个绝对时刻**绑给**两个**载体
  （`DeadlineState` **＋** `GoStyleRequestDeadline`）；`:304` ⇒ `effectiveTimeoutNanos = min(serverBudgetNanos, clientDeadlineRemainingNanos())`。
- **C 侧现状（2026-11-05 复核 · 已变更）**：`common/…/autoconfigure/ObservabilityAutoConfiguration.java:349-352`
  ⇒ `onHalfClose` 里**两个载体绑【同一个绝对时刻】**；同文件 `:239-240` ⇒ `effectiveTimeoutNanos`（= **min 语义**）
  ＋ `absoluteDeadlineNanos = saturatingAddNanos(startNanos, effectiveTimeoutNanos)` ⇒ 旧的 `rpcDeadlineNanos(...)`（只认服务端 timeout）**已删除**。
- **销账依据（共享层台账，唯一权威）**：`docs/DRIFT/common/verification-common-2026-11-02.md`
  **DRIFT-COMMON-V20**（2026-11-04 · `global` 收口域 · ✅ 已收口 · 提交 `01c11dd`）
  ⇒ ⇒ **D-15 的根因（共享层出向载体未绑 ＋ 客户端 deadline 不参与）已消除**。
- **✅ 棘轮按其自身指示翻回（本轮 · `global` 收口域执行）**：
  `grpc/CommentOutboundDeadlineCarrierGateTest#outboundCarrierIsNotBoundAndClientDeadlineIsNotHonouredYet`
  ⇒ **已改名** `outboundCarrierIsBoundAndClientDeadlineIsHonoured`，三条断言**已改回 B 侧真值**：
  ① 出向载体 `isTrue()`（已绑）② 服务端剩余 `isLessThanOrEqualTo(客户端 deadline)` ＋ `isPositive()`（min 语义）
  ③ 出向载体剩余纳秒 `isGreaterThan(0L)`（不再返回 -1 = 未绑哨兵）
  ⇒ ⇒ **实测 9/9 绿**（`CommentOutboundDeadlineCarrierGateTest`）。
- ⚠️ **本域 §5.4 状态表的「残留 1 点 ⇒ `NOT_VERIFIED`」仍按本域原判** ——
  本点**根因已消除**，但「B 组第 ⑨ 点」的**逐项核对**属本域 253 点清单的一部分
  ⇒ ⇒ **改判由本域 lane 重跑其核对后自行给出**；**`global` 收口域只销「共享层差异」这一根**（与 DRIFT-COMMON-V19 的销账口径同款）。

#### 1.10.5 ⚠️ 本轮踩过的坑（**写给下一个跑本域的人**）

| 坑 | 现象 | 处置 |
|---|---|---|
| 🔴 **Boot 3.5 里没有 `DataRedisAutoConfiguration`** | B 侧（Boot 4.0.8）用 `org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration`；**Boot 3.5.11 拆包后该类改名为 `RedisAutoConfiguration`**（包路径 `org.springframework.boot.autoconfigure.data.redis` 不变）⇒ 照抄 B 侧 import **编译不过** | 用 `org.springframework.boot.autoconfigure.data.redis.**RedisAutoConfiguration**`；⚠️ 这是「Boot 4 → 3.5 **artifact 改名**」的又一例（D14 已列 `spring-boot-data-redis` ⇒ 撤） |
| 🔴 **同一模块目录并发跑 maven ⇒ `target/` 互踩** | 症状是**满屏 `NoClassDefFoundError`（连本模块自己的主类都找不到）**，而 `target/classes` 里 `.class` **明明存在** ⇒ 读起来像「实现缺类 / import 写错」 | ① worker **一律禁止跑 maven**，只跑**隔离编译**（`javac` + 现成 classpath，产物落 `/tmp`）；② 主控**持全局锁串行**跑 `mvn`（本轮用 `/tmp/rag-mvn-lock-comment.sh` 的 `mkdir` 互斥） |
| 🔴 **`mvn surefire:test` 直调 goal ⇒ 上游仍从 `~/.m2` 解析** | 即使带 `-am`，直调 goal **不触发上游 lifecycle** ⇒ 测到的是「**旧共享层 × 新本域**」 | 任何要验证「上游改动已生效」的读数，**必须走 lifecycle**（`test` / `package` / `verify`）⇒ 本轮两次 `clean verify` 都是 lifecycle |
| ⚠️ **B 组第 ⑨ 点的方向在 C 侧结构性不存在** | B 侧守的是「comment-api → comment-rpc」；C 侧 **api+rpc 已压平成单进程**（D1/D7）⇒ **该链路不存在** | 出向方向换成 C 侧**真实存在**的 `article-rpc`（`grpc.client.article-rpc.address`）＋ 真实方法名 `article.ArticleService/GetArticle`；拦截器是 **channel 级** ⇒ 对任何方向生效 ⇒ **判据强度不减** |
| ⚠️ **B 侧的测试基础设施类在 C 侧没有** | `RealContainerHostPort` / `RealGrpcPort`（B 侧专有的**固定宿主口登记册**）在 C 侧不存在 ⇒ C 侧用 Testcontainers **动态映射口** | 「死口」要挑一个**没人监听的端口**时 ⇒ **必须避开 macOS 临时段 49152–65535**（与 Docker 发布口同段）⇒ 在 **20000–21000** 专用段用 `ServerSocket` 绑一次确认空闲再释放 |
| ⚠️ **C 侧 Mapper 是纯 `@Mapper` ＋ 显式 SQL**（**不是** MP `BaseMapper`） | ⇒ **可以**用 B 侧那套手工 `Configuration` + `addMapper` + `SqlSessionTemplate` 装配（**MP 的 `BaseMapper` 方法注入会失败**） | 照抄本域 `service/CommentDomainFactRealPgTest` 的手工装配段落 |
| ⚠️ **Flyway 账本表名会撞号** | C 侧生产账本 = `flyway_schema_history_comment` ⇒ 新测试类**必须换一个独立账本表名** | 本轮用 `flyway_schema_history_comment_cache_gate`（与 B 侧同名） |
| ⚠️ **探针库要单独开** | 本域多个测试类共用 `TestPostgres` 单例 ⇒ 取件类查询会把**别人的行领走** ⇒ 假绿/假红 | 本轮沿用本域先例：在同一容器里另开探针库（如 `comment_cache_gate_probe`） |

---

## 二、B → C 责任承接

> **口径**：按 B 侧**源文件**逐个提取职责 → 在 C 侧找到承接位置。
> **每项都必须有明确承接结果**；找不到 ⇒ 标 `NOT_IMPLEMENTED` / `NOT_VERIFIED`，**不允许**按文件名推测、不允许认为「Spring 自动处理了」。

### 2.1 契约层（5 个 C 类 ← B 侧 4 个主类 ＋ 1 个 proto）

| ID | B 源文件 / 方法 | B 原始职责 | C 承接文件 / 方法 | 映射 | 状态 | 证据 |
|---|---|---|---|---|---|---|
| C-01 | `comment-common/…/errmsg/CommentErrorCode.java` | 27 个错误码常量（对外契约） | `contract/ErrCodes.java`（final class + int 常量） | 1:1 | ✅ | **E-2**（V-3：同序同值） |
| C-02 | `comment-common/…/errmsg/CommentErrorMessages.java` | 27 条中文文案 ＋ 未知码回落 `"FAIL"` | `contract/ErrMsg.java` | 1:1 | ✅ | **E-2**（V-3） |
| C-03 | `comment-common/…/errmsg/CommentCodeError.java` | 业务错误载体（`code` + `msg` ＋ `ErrCode:%d, Errmsg:%s` 包装 ＋ `findIn` 因果链遍历） | `contract/CodeError.java` | 1:1 | ✅ | **E-2**（V-3） |
| C-04 | `comment-common/…/errmsg/CommentGrpcError.java` | 业务码 ↔ gRPC 双向转换（**trailer `rag-comment-biz-code`** ＋ 标准码映射 ＋ `bizCodeFromError` 三级判定） | `contract/GrpcErrFactory.java` | 1:1 | ✅ | **E-2**（V-3） |
| C-05 | `comment-rpc/…/rpc/service/CommentAuditMessage.java` | Kafka 载荷（11 字段，**字段名与顺序即消费方解析依据**） | `contract/CommentKafkaMsg.java`（**生产侧 wire**）＋ `messaging/CommentAuditMessage.java`（**消费侧 DTO**） | 1:N | ✅ | **E-2**（V-3 ＋ 两 record 字段序逐条相同） |
| C-06 | `comment-proto/src/main/proto/comment.proto` | 7 RPC / 4 enum / 16 message，**字段号逐条不可改** | `proto/src/main/proto/comment.proto`（pb 包名 `com.rag.proto.comment`） | 1:1 | ✅ | **E-2**（B↔C diff **只差 `java_package` 一行**） |

### 2.2 API 侧（HTTP 边界）

| ID | B 源文件 / 方法 | B 原始职责 | C 承接文件 / 方法 | 映射 | 状态 | 证据 |
|---|---|---|---|---|---|---|
| A-01 | `comment-api/…/api/CommentApiApplication.java`（类声明 ＋ `main`） | api 进程入口 ＋ `@EnableDiscoveryClient` | `CommentServiceApplication.java`（**两入口合并**，见 §3.3） | 1:N | ✅ | **E-2** |
| A-02 | `comment-api/…/api/controller/CommentController.java`（7 个 `@PostMapping`） | 7 条 **POST** 路由（前缀 `/comment/v1`） | `controller/CommentController.java` | 1:1 | ✅ | **E-2**（路径逐条一致，⚠️ 第 1–6 条 `comment` 出现**两次**、第 7 条**一次**，B 侧原样） |
| A-03 | `comment-api/…/api/service/{7 个用例类}` | 7 个用例编排（取身份 → 组请求 → 调下游 → 映射响应） | `service/CommentApiService.java`（**7 方法**，N:1 合并，见 §3.1） | **N:1** | ✅ | **E-2**（V-11 关键点逐条核对） |
| A-04 | `comment-api/…/api/service/CommentApiSupport.java#userId` | claim 解析（**接受可解析为 int64 的 String claim**） | `service/CommentApiSupport.java#userId` | 1:1 | ✅ | **E-2**（⚠️ **与 like 域相反**：like 拒绝 String claim —— Go 出处不同，**不可跨域统一**） |
| A-05 | `comment-api/…/api/service/CommentApiSupport.java#rpcError` | gRPC status → 业务码/文案转换 | `service/CommentApiSupport.java#rpcError` | 1:1 | ✅ | **E-2**（D2 下多一条 `CommentExceptions.Rpc` 分支，与 B 侧 status 分支**逐字等价**） |
| A-06 | `comment-api/…/api/interceptor/CommentJwtAuthInterceptor.java` | 验签 ＋ 把**原始 claim 对象**写进 request attribute（键 `"userId"`） ＋ 失败 **401 空 body** | `interceptor/CommentJwtAuthInterceptor.java` | 1:1 | ✅ | **E-2**（⚠️ **public 路径集 = 空集** ⇒ `WebMvcConfig` **不调** `excludePathPatterns`） |
| A-07 | `comment-api/…/api/config/WebMvcConfig.java` | 拦截器注册 | `config/WebMvcConfig.java` | 1:1 | ✅ | **E-2** |
| A-08 | `comment-api/…/api/config/CommentApiProperties.java` | `@ConfigurationProperties("comment-api")` | `config/CommentApiProperties.java` | 1:1 | ✅ | **E-2**（`@PostConstruct` **fail-closed** 校验保留） |
| A-09 | `comment-api/…/api/advice/CommentGlobalResponseAdvice.java` | 成功响应包信封 `{code,msg,data}`（3 条分支：null body / 已是信封 / 非 JSON） | `advice/CommentApiResponseAdvice.java` | 1:1 | ✅ | **E-2** |
| A-10 | `comment-api/…/api/advice/CommentGlobalExceptionHandler.java` | 8 类异常 → 响应形状（**HTTP 恒 200** ＋ 业务码；401/404/405/415 除外） | `advice/CommentApiExceptionHandler.java` | 1:1 | ✅ | **E-2** |
| A-11 | `comment-api/…/api/dto/*.java`（16 个） | 请求/响应 DTO（**可变 POJO**） | `dto/comment/*.java`（16 个 **immutable record**） | 1:N | ✅ | **E-2**（D5 ＋ A10：字段名不变，必填校验从手写 `GoStyleRequiredFields` 改为 Bean Validation `@NotNull`） |
| A-12 | `comment-api/…/api/client/CommentRpcClient.java` | api → rpc 的 gRPC 客户端 | **不存在** —— D2 域内直连，`CommentApiService` 直接注入 `CommentDomainService` | 1:N | ✅ | **E-2**（§3.2） |
| A-13 | `comment-api/…/api/config/CommentGoStyleCoercion.java` | Jackson 强转判据 | `common/…/web/Coercion.java` ＋ `JsonBodyConverterAutoConfiguration` | 1:1 | ✅ | **E-2**（D3 上收，**不在本域**） |

### 2.3 RPC 侧 —— 领域层与协作者

| ID | B 源文件 / 方法 | B 原始职责 | C 承接文件 / 方法 | 映射 | 状态 | 证据 |
|---|---|---|---|---|---|---|
| R-01 | `comment-rpc/…/rpc/service/CommentDomainService.java`（7 个用例 ＋ `doXxx` 私有实现） | 7 个 rpc 用例的领域语义（**883 行**） | `domain/service/CommentDomainService.java`（7 个 public 方法） | 1:1 | ✅ | **E-2**（V-1：**B 550 行 = C 550 行，零语义差异**） |
| R-02 | `comment-rpc/…/rpc/service/CommentTransactionalService.java` | 3 个 `@Transactional` 方法 ＋ `normalizeCommentMeta` ＋ `goJsonQuote` | `domain/service/CommentTransactionalService.java` | 1:1 | ✅ | **E-2**（V-2：B 373 / C 371 行） |
| R-03 | `comment-rpc/…/rpc/service/CommentCacheService.java`（12 个方法） | 缓存读穿/写穿/失效（**四类读穿错误语义各不相同**） | `service/CommentCacheService.java` | 1:1 | ✅ | **E-2**（代码体与 B 侧**逐行相同**） |
| R-04 | `comment-rpc/…/rpc/service/CommentRedisKeys.java` | 5 个 Redis key 构造 | `service/CommentRedisKeys.java` | 1:1 | ✅ | **E-2**（⚠️ `subjectKey` **不含 targetType** —— B 侧既有缺陷，**照抄**） |
| R-05 | `comment-rpc/…/rpc/service/CommentSensitiveFilter.java` | 敏感词匹配（默认词库 `["傻逼","我草"]`，大小写不敏感） | `service/CommentSensitiveFilter.java` | 1:1 | ✅ | **E-2**（代码体逐行相同） |
| R-06 | `comment-rpc/…/rpc/service/CommentAuthorityLookupService.java` | 权威查询（`AuthorityHandler.AuthorityLookup` 实现） | `service/CommentAuthorityLookupService.java` | 1:1 | ✅ | **E-2**（代码体逐行相同；`Wire.*` 1:N 拆为共享层 `Event`/`EventSender`/`CanonicalHash`/`TechnicalReceipt`/`DeliveryStatus`） |
| R-07 | `comment-rpc/…/rpc/service/CommentDomainFactService.java` | 事实生成（24 字段 `CommentFact` ＋ `DateTimes`） | `domain/service/CommentDomainFactService.java` | 1:1 | ✅ | **E-2**（除 package+import 外**逐字相同**） |
| R-08 | `comment-rpc/…/rpc/service/CommentDeliveryValidator.java` | 信封/载荷严格解码 | `service/CommentDeliveryValidator.java` | 1:1 | ✅ | **E-2**（除 package+import 外逐字相同） |
| R-09 | `comment-rpc/…/rpc/service/CommentFactDeliveryService.java` | 事实投递（`SKIP LOCKED` ＋ 前序屏障 ＋ 回执校验；**失败(2) 可重试 / 阻塞(3) 不再调度**） | `service/CommentFactDeliveryService.java` | 1:1 | ✅ | **E-2**（⚠️ 方法名 `dispatchOnce` → `dispatchFactOnce`（共享层 SPI `Dispatcher` 的 SAM 名），**保留 `dispatchOnce` 别名**） |
| R-10 | `comment-rpc/…/rpc/service/CommentAuditPublisher.java` | Kafka 生产（**同步等待 `future.get(timeout)`**） | `messaging/CommentAuditPublisher.java` | 1:1 | ✅ | **E-2**（⚠️ **刻意不抄 like 域的异步** —— 方向相反） |
| R-11 | `comment-rpc/…/rpc/service/CommentAuditListener.java` | Kafka 消费（**四条出口方向**：解析失败⇒丢弃 / 落库失败⇒重投 / 缓存失败⇒记日志 / 通知失败⇒记日志） | `messaging/CommentAuditListener.java` | 1:1 | ✅ | **E-2**（V-5） |
| R-12 | `comment-rpc/…/rpc/service/CommentSpans.java`（**269 行**，span 常量 ＋ `traced` ＋ `tracedWithErrorStatus` ＋ `isSystemFailure` ＋ `enumName`） | 手写 span（**span 名 / tracer 名 / 属性名 / 属性值**全是契约） | `metrics/CommentRpcMetrics.java`（**与 B 侧 `CommentRpcMetrics` 合并**） | **N:1** | ✅ | **E-2**（V-4：24 个常量**缺失 0 · 取值差异 0**） |
| R-13 | `comment-rpc/…/rpc/metrics/CommentRpcMetrics.java`（5 个指标） | 5 个指标（`comment_request_total` / `comment_request_seconds_counter` / `comment_list_size` / `comment_postgres_error_total` / `comment_redis_error_total`） | 同上 | 1:1 | ✅ | **E-2**（⚠️ 见 §5 D-01：指标名的裁决） |
| R-14 | `comment-rpc/…/rpc/exception/CommentExceptions.java` | 领域异常集合（**3 个 sentinel 必须独立**：`ErrorSubjectNotFound`→1012 / `ErrorCommentNotFound`→1013 / `ErrorCommentForbidden`→1008） | `exception/CommentExceptions.java` | 1:1 | ✅ | **E-2**（码值与文案逐条一致；**msg 一律用调用点原文，不查表**） |
| R-15 | `comment-rpc/…/rpc/domain/*.java`（7 个 Entity） | 7 张表的实体 | `domain/entity/*.java`（7 个，Lombok `@Getter/@Setter`） | 1:1 | ✅ | **E-2**（⚠️ **主键策略分两组**：`IdType.AUTO`（PG IDENTITY）vs `IdType.INPUT`（雪花/业务给定）） |
| R-16 | `comment-rpc/…/rpc/mapper/*.java`（8 个，含 `JsonbTypeHandler`） | Mapper（**全部显式 SQL**） | `mapper/*.java`（7 个） | 1:1 | ✅ | **E-2**（`JsonbTypeHandler` 已由共享层 `common/persistence/` 承接 ⇒ **不是缺口**） |
| R-17 | `comment-rpc/…/rpc/service/impl/CommentServiceGrpcImpl.java` | gRPC 服务实现（7 个方法 ＋ 统一 `invoke` 出口） | `grpc/CommentGrpcService.java` | 1:1 | ✅ | **E-2**（**统一出口模板逐条照抄**：业务异常⇒`CommentGrpcError.grpcError` / status 异常⇒原样 / 其它 RuntimeException⇒**兜底 5001**） |
| R-18 | `comment-rpc/…/rpc/service/impl/PbCommentServiceAlias.java` | `pb.` 前缀兼容别名（同一实现再挂到 `/pb.CommentService/*`） | `grpc/PbCommentServiceAlias.java` | 1:1 | ✅ | **E-2** |
| R-19 | `comment-rpc/…/rpc/config/CommentRpcProperties.java` ＋ `CommentKafkaTopics.java` | 2 个 `@ConfigurationProperties` | `config/CommentRpcProperties.java`（`comment.*`）＋ `messaging/CommentKafkaTopics.java`（`comment.kafka.*`） | 1:N | ✅ | **E-2** |
| R-20 | `comment-rpc/…/rpc/config/Jackson2ObjectMapperConfiguration.java` | 关标量强转 | `common/…/autoconfigure/JsonBodyConverterAutoConfiguration.java` | 1:1 | ✅ | **E-2**（D3 上收） |
| R-21 | `comment-rpc/…/rpc/client/{ArticleRpcClient,MessageRpcClient}.java` | **跨域** gRPC 客户端（D6：跨域仍走 gRPC） | `client/{ArticleRpcClient,MessageRpcClient}.java` | 1:1 | ✅ | **E-2**（⚠️ **必须显式 `negotiation-type: plaintext`** —— A4 守门 PASS） |

### 2.4 事实流水线（B 侧 2 个独立 cmd → C 侧域内组件）

| ID | B 源文件 | B 原始职责 | C 承接文件 | 映射 | 状态 | 证据 |
|---|---|---|---|---|---|---|
| F-01 | `comment-rpc/…/rpc/bootstrap/CommentFactDispatchScheduler.java` | **独立进程 `cmd/fact-dispatch`**：定时扫事实并投递 | `wiring/CommentFactDispatchScheduler.java` | 1:1 | ✅ | **E-2**（D1 撤销 ⇒ 三域 fact 能力**作域内组件**，无例外） |
| F-02 | `comment-rpc/…/rpc/bootstrap/CommentAuthorityEndpointConfig.java` | **独立进程 `cmd/fact-authority`**：权威查询端点 | `wiring/CommentAuthorityEndpointConfig.java` | 1:1 | ✅ | **E-2**（⚠️ **默认不装配**） |
| F-03 | `comment-rpc/…/rpc/bootstrap/CommentSchemaGate.java` | 建表失败即退出的**前置闸门**（7 张表清单） | `wiring/CommentSchemaGate.java` | 1:1 | ✅ | **E-2**（⚠️ **只告警不阻止启动** —— B 侧既有行为，**照抄**） |
| F-04 | `comment-rpc/…/rpc/bootstrap/NacosRegistration.java` | Nacos 注册（`@Component("commentRpcNacosRegistration")`，**不依赖自动注册**） | `wiring/NacosRegistration.java` | 1:1 | ✅ | **E-2**（⚠️ group 由硬编码改为**读配置**，见 §5 D-05） |
| F-05 | （B 侧 comment 域**无**此类） | — | `wiring/CommentFactProcessLogging.java` | **C 新增** | ✅ | **E-2**（像/favorite 域同款骨架 ＋ B 侧 Go 身份 `rtw.comment-rpc` 等 3 份 logger） |

### 2.5 承接表汇总（**机器复算**）

| 维度 | 数 |
|---|---|
| B 侧源文件（主代码） | **83**（api 37 ＋ common 4 ＋ rpc 42） |
| B 侧源文件在承接表中**全部出现** | **83 / 83 = 100%**（`tools/check-mapping-coverage.py` 独立复算 **B 列 83/83**） |
| C 侧类 | **69**（**0 空壳**） |
| C 侧在映射表中**全部出现** | **69 / 69 = 100%**（同脚本 C 列 **69/69**） |
| 承接表行数（本文件 §2） | **C 6 ＋ A 13 ＋ R 21 ＋ F 5 = 45 行**（按**职责**聚合，**不按文件**；1:N / N:1 已在「映射」列标明） |
| 映射类型分布（映射表口径） | `1:1` 66 · `1:N` 10 · `N:1` 6 · `C 新增` 2 = **84 行** |

> ⚠️ **§2 的 45 行 vs 映射表的 84 行**：两者不是同一口径 —— 映射表按**文件**逐行列，本表按**职责组**聚合。**每一条 B 侧源文件都在两侧出现**（机器复算 C 69/69 · B 83/83）⇒ **无遗漏**。

---

## 三、多文件合并 / 拆分 / 架构调整专项（§8）

### 3.1 全部 N:1 映射（**6 条**）

| # | B 侧多文件 | C 侧单文件 | 职责是否完整承接 | 异常边界 | 事务边界 | 副作用 | 生命周期 |
|---|---|---|---|---|---|---|---|
| N-1 | `comment-api/…/api/service/` 的 **7 个用例类** | `service/CommentApiService.java` | ✅ 7/7 用例（`create`/`delete`/`like`/`list`/`manage`/`report`/`updateState`） | ✅ 6 个端点 catch → `support.rpcError`；**`list` 端点<b>刻意不 catch</b>**（B 侧差异 3） | ➖ 无事务（api 层不碰 DB） | ➖ 无 | ➖ 无状态 |
| N-2 | `comment-rpc/…/rpc/service/` 的 **7 个 rpc 协作者** | `domain/service/CommentDomainService.java` | ✅ 7 个用例 | ✅ 统一 `CommentExceptions.Rpc` | ⚠️ **3 个 `@Transactional` 方法仍在独立的 `CommentTransactionalService`**（**没有**被合进领域服务） | ✅ | ➖ 无状态 |
| N-3 | `comment-rpc/…/rpc/service/CommentCacheService.java` 的 12 个方法 | 同名单类 | ✅ | ✅ | ➖ | ✅ | ➖ |
| N-4 | `comment-rpc/…/rpc/service/CommentSpans.java` ＋ `comment-rpc/…/rpc/metrics/CommentRpcMetrics.java` | `metrics/CommentRpcMetrics.java` | ✅ 24 个 span 常量 ＋ 5 个指标 | ✅ | ➖ | ✅ | ➖ |
| N-5 | `comment-rpc/…/rpc/config/{CommentRpcProperties,CommentKafkaTopics}.java` | `config/CommentRpcProperties.java` ＋ `messaging/CommentKafkaTopics.java` | ✅（**1:N 而非 N:1** —— D7 把 Kafka 段独立成 `comment.kafka.*`） | ➖ | ➖ | ➖ | ✅ 显式注册（§4.B） |
| N-6 | `service/common/…/protection/*`（**17 个类**） | `common/…/protection/{CpuThresholdHealthIndicator,MaxBytesFilter}.java`（2 个） | ✅（**收进共享层，不在本域**） | ➖ | ➖ | ➖ | ➖ |

> ⚠️ **N-1 的关键点逐条核对**（V-11）：`setActionTypeValue`（枚举字段用**原始数值** setter ⇒ 未定义值不静默变 0）×3 · `setReasonValue` ×2 · `setStateValue` ×2 · `INVALID_ARGUMENT` ×2（`list` 端点的裸 status 校验）· `DEFAULT_PAGE_SIZE=20` / `MAX_PAGE_SIZE=50` · `hasSubject()` · `getStateValue()` ×3 · `new DeleteCommentResp(true, …)`（**字面量 true**）· `toCommentItem` 递归 + 每层先建空切片 ⇒ `children` 恒 `[]` · `subject.owner_id` 恒 **0** —— **13 个关键点全部在位**。

### 3.2 全部 1:N 映射（**关键 10 条**；共 10 条）

| # | B 侧单文件 | C 侧多文件 | 职责断裂检查 |
|---|---|---|---|
| 1-1 | `comment-common/…/errmsg/CommentAuditMessage.java` | `contract/CommentKafkaMsg.java` ＋ `messaging/CommentAuditMessage.java` | ✅ 调用链完整（`Publisher` 写 wire → `Listener` 读 DTO）；**数据完整**（11 字段同名同序，`from()`/`toWire()` 双向转换）；**异常传播**完整 |
| 1-2 | `comment-api/…/api/client/CommentRpcClient.java` | `service/CommentApiService.java` ＋ `domain/service/CommentDomainService.java` | ✅ D2 域内直连，**少一层序列化往返**；`grpc.client.comment-rpc.*` 段已从 yml 删除 |
| 1-3 | `comment-api/…/api/CommentApiApplication.java` ＋ 2 个 advice | `CommentServiceApplication.java` ＋ `advice/` 2 个 | ✅ 职责边界清晰（入口 / 成功包装 / 异常折叠） |
| 1-4 | `comment-api/…/api/dto/`（16 个） | `dto/comment/`（16 个 record） | ✅ **1 个子包**（D11：单业务对象） |
| 1-5 | `comment-rpc/…/rpc/service/CommentDomainService.java` ＋ `CommentCacheService.java` | `domain/service/` ＋ `service/` | ✅ 领域服务与缓存协作者**分属不同技术角色包**（§5.4 修正：领域协作者**不参与**用例合并） |
| 1-6 | `comment-rpc/…/rpc/service/CommentFactDeliveryService.java` ＋ `CommentDeliveryValidator.java` | `service/` 2 个 | ✅ 投递与校验分离 |
| 1-7 | `comment-rpc/…/rpc/domain/` | `domain/entity/`（按是否 `*Entity` 拆） | ✅ 7 个实体一一对应 |
| 1-8 | `comment-rpc/…/rpc/config/` | `config/` ＋ `messaging/` | ✅ Kafka topic 落 `messaging/`（**包压平的直接后果** ⇒ 必须显式注册，§4.B） |
| 1-9 | `comment-rpc/…/rpc/service/impl/` | `grpc/` | ✅ 压平 ＋ 改名 `CommentServiceGrpcImpl`→`CommentGrpcService`；**`PbCommentServiceAlias` 保留原名** |
| 1-10 | `comment-rpc/…/rpc/bootstrap/` | `wiring/` | ✅ 4 个原样落位 ＋ 1 个 C 新增 |

### 3.3 API ＋ RPC 合并（§8.3）

| 检查项 | 结论 | 证据 |
|---|---|---|
| **HTTP 入口是否完整保留** | ✅ 7 条全 POST，路径逐条一致 | **E-2**（§2.2 A-02） |
| **gRPC 入口是否完整保留** | ✅ 7 个 RPC ＋ `pb.` 别名 | **E-2**（§2.3 R-17/R-18） |
| **RPC Server 是否正确启动** | ✅ `grpc.server.port` 独立于 `server.port`（HTTP 面） | **E-2**（yml） |
| **RPC Client 是否正确注册** | ⚠️ **域内自我 gRPC 客户端已删除**（D2）；**跨域**客户端 2 个保留 ＋ `negotiation-type: plaintext` | **E-2** ＋ **A4 守门 PASS** |
| **原有域内 RPC 往返是否按既定设计调整** | ✅ D2 已定（§3.2 1-2） | **E-2** |
| **API 与 RPC 共用 Bean 是否正确装配** | ✅ `@EnableConfigurationProperties` **显式列举 3 个**（§4.B） | **E-2** ＋ **A11 守门 PASS** |
| **HTTP 与 RPC 的安全上下文是否受影响** | ⚠️ **B 侧是两段式验签**（Filter 3s 预算外 ＋ Interceptor 预算内）⇒ **C 侧合并为单 Interceptor** | ⚠️ **D-03**（见 §5） |
| **两类入口是否出现不必要的共享状态** | ✅ 无（领域服务无状态 ＋ 缓存走 Redis ＋ 事务边界独立） | **E-2** |
| **启动和关闭时两类服务是否都正确工作** | ✅ `@SpringBootTest(webEnvironment=RANDOM_PORT)` 真上下文测试 | **E-3**（§1.7） |

### 3.4 Common Starter 共享能力上收（§8.4）

| B 侧域内副本 | C 侧落点 | 原消费方是否都被覆盖 |
|---|---|---|
| `comment-api/…/api/config/CommentGoStyleCoercion.java` | `common/…/web/Coercion.java` ＋ `JsonBodyConverterAutoConfiguration` | ✅ |
| `comment-rpc/…/rpc/config/Jackson2ObjectMapperConfiguration.java` | `common/…/autoconfigure/JsonBodyConverterAutoConfiguration.java` | ✅ |
| `comment-rpc/…/rpc/mapper/JsonbTypeHandler.java` | `common/…/persistence/JsonbTypeHandler.java` | ✅ |
| `comment-rpc/…/rpc/bootstrap/` 4 个 | `common/…/observability/*` ＋ `common/…/autoconfigure/ObservabilityAutoConfiguration.java` | ✅ |
| `comment-rpc/…/rpc/service/CommentSpans.java` | `common/…/observability/*` ＋ 域内 `metrics/CommentRpcMetrics.java` | ✅（**域内那份保留** —— 域特有 span 名，**不属共享能力**） |

> ⚠️ **无重复 Bean**：`@Import` 清单**为空**（**A5 守门 PASS**）⇒ 与 auto-config 不混用（D3 的硬要求）。

### 3.5 独立进程合并（§8.5）

| B 侧独立进程 | C 侧落点 | 执行频率 | 启动条件 | 关闭条件 | 并发控制 | 重复执行保护 | 失败处理 |
|---|---|---|---|---|---|---|---|
| `cmd/fact-dispatch` | `wiring/CommentFactDispatchScheduler.java` | `@Scheduled`（配置驱动） | `comment.fact.dispatch-enabled`（**默认 false**） | Spring 生命周期 | 单线程 ＋ `SKIP LOCKED` | **依赖 DB 行级锁**（`FOR UPDATE SKIP LOCKED`） | 失败(2) 可重试 / 阻塞(3) 不再调度 |
| `cmd/fact-authority` | `wiring/CommentAuthorityEndpointConfig.java` | — | `comment.authority.enabled`（**默认 false ⇒ 不装配**） | Spring 生命周期 | — | — | — |

> ⚠️ **两个进程在 C 侧**默认**都不启用**（与 B 侧独立进程的「不启动即不存在」等效）⇒ **未引入新的自动执行面**。
> ⚠️ **遗留**：映射表 §5 记的 **P6-a（`-once` 模式）/ P6-b（authority 端口）/ P6-c（preflight 入口）** 仍**待拍板** ⇒ 本轮**未改**。

---

## 四、框架版本差异报告（§7）

### 4.A 依赖体系

| 组件 | B 侧机制与版本 | C 侧机制与版本 | 具体变化 | 影响执行流程？ | 是否影响业务行为 | 适配方式 | 证据 |
|---|---|---|---|---|---|---|---|
| Spring Boot | 4.0.8（Framework 7.0.10） | 3.5.11（Framework 6.2.x） | 框架大版本回退 | ✅ 是 | ⚠️ 见 4.C / 4.E | 逐项比对，见下 | **E-2** ＋ **E-3** |
| Jackson | Jackson 3（`tools.jackson.*`） | **Jackson 2.19.4**（`com.fasterxml.jackson.*`） | **主版本不同** ⇒ API 面不同 | ✅ 是 | ⚠️ **是 —— 但只影响「属性输出顺序」**（🔴 **E-4 活体抓到**：B 侧 **Jackson 3.1.5** 默认**字母序** / C 侧 **Jackson 2.19.4** 按**声明序** ⇒ 响应 JSON **值相同、键序不同** ⇒ 见 **D-14**）。⚠️ 本轮**修正了本表原先的「❌ 否」**：那是在**没有双侧活体对拍**时按「API 面都在」下的判断 ⇒ **源码级推不出 wire 字节** | D14 全仓回退到 Jackson 2；**0 处** `tools.jackson` 引用；⇒ **C 侧不改**（D-14：改成字母序＝背离 Go 的声明序）⇒ 登记为「框架限制类」 | **E-2** ＋ **E-3**（序列化逐字节断言）＋ **E-4**（D-14：两侧逐字节比对） |
| `spring-boot-flyway` | Boot 4 **独立 artifact** | Boot 3.5 **不存在** ⇒ 用 `flyway-core` ＋ `flyway-database-postgresql` | 自动配置载体变了 | ✅ 是 | ❌ **否**（Boot 3.5 的 `FlywayAutoConfiguration` 在 `spring-boot-autoconfigure` 里 ⇒ 自动配置仍生效） | D14 明令：Boot 4 专有 artifact 一律**撤**，不得残留 | **E-2** ＋ **E-3**（Flyway 真跑过测试） |
| `spring-boot-data-redis` | Boot 4 独立 artifact | Boot 3.5 不存在 ⇒ `spring-boot-starter-data-redis` | 同上 | ✅ 是 | ❌ **否** | 同上 | **E-3**（真 Redis 容器） |
| `spring-boot-tomcat` | Boot 4 独立 artifact | Boot 3.5 不存在 ⇒ starter 自带 | 同上 | ✅ 是 | ⚠️ **Tomcat 11 → 10.1**（D14 回退）⇒ HTTP 层断言必须**重跑重校** | D14 | **E-3**（真 Tomcat 断言） |
| `spring-boot-kafka` | Boot 4 独立 artifact | Boot 3.5 不存在 ⇒ `org.springframework.kafka:spring-kafka` | 同上 | ✅ 是 | ⚠️ **Boot 4 才有「只引底层 ⇒ `@KafkaListener` 静默不消费」** ⇒ **Boot 3.5 无此风险** | D14 | **E-3**（listener 进 registry 的装配层断言） |
| `grpc-client-spring-boot-starter`（devh） | 3.1.0.RELEASE | 3.1.0.RELEASE（同版本） | 无 | — | ❌ 否 | ⚠️ devh 的 starter 会拖自己的 `grpc-core` ⇒ **在引入起点 exclude**，版本只由根 pom 的 `<grpc.version>`=1.63.0 决定 | **A4 守门 PASS** ＋ **E-3** |
| Nacos client | 3.x | **3.0.3**（SCA 2025.0.0.0 原生） | Boot 4 线曾覆盖到 3.3.0 | ✅ 是 | ❌ 否（回退后回到**官方配套矩阵**） | D14 | **E-2** |
| MyBatis-Plus | 3.5.17 | 3.5.17 | 无 | — | ❌ 否 | — | **E-2** ＋ **E-3** |
| Flyway | 11.x | **11.14.1**（显式覆盖 Boot BOM 的 11.7.2） | 版本被显式钉住 | ✅ 是 | ❌ 否 | 显式声明 ⇒ 版本可断言 | **E-3** |

### 4.B 自动配置与 Bean 装配（**「Bean 存在」≠「机制生效」**）

| 装配点 | B 侧做法 | C 侧做法 | 机制真生效的证据 |
|---|---|---|---|
| **`@ConfigurationProperties` 注册** | `@ConfigurationPropertiesScan(basePackages = "com.rag.comment.comment.rpc.config")` | **显式列举 3 个**（`CommentApiProperties` / `CommentRpcProperties` / `CommentKafkaTopics`） | 🔴 **包压平把 `CommentKafkaTopics` 从 `config/` 移到了 `messaging/`** ⇒ 照抄 B 侧「扫 config 包」**会漏注册 ⇒ 应用直接起不来** ⇒ 改用显式列举 ＋ **真上下文测试断言它是 bean**（**E-3**） |
| **共享层 starter** | 域内显式 `@Import` 9 个共享类 | **auto-config**（`AutoConfiguration.imports`） | **A5 守门 PASS**（`@Import` 清单为空 ⇒ 不与 auto-config 混用） |
| **gRPC 拦截器链** | `server.intercept(...)`（`CommentRpcApplication.java:91-100`） | **共享层 `ProtectionAutoConfiguration#seaGrpcServerConfigurer`**（devh 只认 `GrpcServerConfigurer`，域内再来一份会**挂 2 条链**） | **A3 守门 PASS**（`common/` 声明了 `GrpcServerConfigurer` 且真调 `server.intercept(...)`；域内 0 重复） |
| **Kafka 容器工厂** | 域内 `new ConcurrentKafkaListenerContainerFactory`（`AckMode.RECORD` ＋ 只记日志的 `DefaultErrorHandler`） | **逐条照抄** | ⚠️ **域内自建工厂 ⇒ `spring.kafka.listener.*` 全族键没有消费点**（`auto-startup` / `concurrency` / `ack-mode` …）⇒ **并发度只能来自 `@KafkaListener(concurrency=…)`** ⇒ 真上下文测试断言**并发度 = 8**（读到 8 即证明域内工厂被用上） |
| **`@MapperScan`** | — | `@MapperScan("com.rag.comment.mapper")` | **E-3**（真上下文 ＋ 真 SQL 执行） |
| **Nacos 注册** | `@Component("commentRpcNacosRegistration")`，**不依赖自动注册** | `wiring/NacosRegistration.java`（同样显式） | ⚠️ 测试环境用 `NacosStubConfiguration` 替换 `getNamingService()` ⇒ **不碰网络**，但域内 bean **照常被创建**（构造契约仍被真实校验） |

### 4.C Web / Servlet / HTTP 行为

| 检查项 | 结论 |
|---|---|
| Filter / Interceptor / ArgumentResolver / HttpMessageConverter / ControllerAdvice | ✅ 逐条对上 B 侧（**唯一例外见 D-03**：B 侧两段式验签 ⇒ C 侧合并为单 Interceptor） |
| 请求体读取 / 响应序列化 | ✅ `JsonBodyConverter`（共享层）＋ 域内 advice |
| Header / Content-Type | ✅ **E-3 真 Tomcat 断言**（含 404 的 `text/plain` ＋ `X-Content-Type-Options: nosniff`） |
| HTTP 状态码 | ✅ **业务恒 200**；401 空 body；404/405/415 框架形状 |
| 请求路径匹配 | ✅ 7 条路径逐条一致 |
| CORS / CSRF / 文件上传 / 请求大小限制 | ➖ 本域无相关端点（**未引入** ＝ **未新增行为**） |
| ⚠️ **Tomcat 11 → 10.1** | ⇒ **HTTP 层断言全部重跑重校**，不使用 B 侧的旧期望值（D14） |

### 4.D 事务与数据库

| 检查项 | 结论 |
|---|---|
| `@Transactional` | ✅ **3 个方法**在 `CommentTransactionalService`（与 B 侧同数） |
| 事务传播 / 回滚规则 | ✅ 未改（**unchecked 异常 ⇒ 回滚**） |
| **自调用 / 代理行为** | ⚠️ `CommentTransactionalService` 的 3 个事务方法**由 `CommentAuditListener`（Kafka 线程）调用** ⇒ **跨线程** ⇒ 走代理 ⇒ 事务生效（**E-3 真 PG 断言**） |
| 事务管理器 / 数据源 | ✅ 单一数据源 ＋ `TimeZone=Asia/Shanghai`（**B 侧必配项，C 侧已补**） |
| MyBatis 执行 / 批量写入 | ✅ 全部显式 SQL（**无 Wrapper 拼接**）⇒ 动态 SQL 的 `<if>/<set>` 路径由 **E-3 真 PG** 覆盖 |
| 数据库异常转换 | ✅ 统一 `CommentExceptions.Rpc`（带业务码 ＋ 原文）⇒ gRPC 边界转 trailer |
| 连接池 | ✅ `spring.data.redis.lettuce.pool` + `commons-pool2` **不可省**（Boot 3.5 的 starter **不再传递**它 ⇒ 缺它启动即 `NoClassDefFoundError`） |

### 4.E Jackson / JSON

| 检查项 | 结论 |
|---|---|
| `ObjectMapper` 来源 | 共享层 auto-config 的 `JsonBodyConverterAutoConfiguration`；**另有一份**域内**专用** ObjectMapper（缓存用，`FAIL_ON_UNKNOWN_PROPERTIES` 关闭）—— **独立创建的 ObjectMapper 不能只看 Spring 那份**（§7.4 纪律） |
| 默认配置 / Coercion | ✅ `common/…/web/Coercion.java` 承接 B 侧 `CommentGoStyleCoercion`（判据逐字保留） |
| 空值 / 缺省值 | ⚠️ **未知字段**：B 侧 `CommentAuditMessage` **显式** `@JsonIgnoreProperties(ignoreUnknown=true)`；C 侧 record **原本只靠 Boot 默认值** ⇒ **本轮已补显式注解**（见 §5 D-07） |
| 数字精度 | ✅ `Long` 字段用 `long`；**不接受浮点**（uid 解析处显式拒绝 `Double`/`Float`/`BigDecimal`/`BigInteger`） |
| 尾随 Token / 异常类型 | ✅ 解析失败 ⇒ `HttpMessageNotReadableException` ⇒ **200 + 5001**（与 B 侧同形） |
| 🔴 **JSON 键序（属性输出顺序）** | ⚠️ **两侧不同 —— 且不是 C 侧的业务逻辑缺陷**（**E-4 活体实证**，见 §1.9.4 ＋ **D-14**）：B 侧 = **字母序**（Jackson **3.1.5** · Boot 4 默认）；C 侧 = **声明序**（Jackson **2.19.4** · D14 钉死）⇒ **值完全相同、键序不同** ⇒ 归 **「框架版本分叉」** 类 |
| Kafka / Outbox JSON | ✅ 载荷 11 字段**逐字节**断言（`CommentAuditMessageContractTest`） |

### 4.F Kafka

| 检查项 | 结论 |
|---|---|
| Topic | ✅ `comment-audit-topic`（**生产与消费同 topic**） |
| 消费组 / 偏移 / 并发 | ✅ `comment-audit-group` / `auto-offset-reset: latest` / `consumers: 8` |
| 载荷结构 | ✅ 11 字段，snake_case，**字段顺序固定**（`@JsonPropertyOrder`） |
| 消费确认 | ✅ `AckMode.RECORD` ＋ **失败不提交位点**（`DefaultErrorHandler` 只记日志）⇒ **落库失败 ⇒ 重投**（⚠️ **刻意不抄 like/article 的 `FixedBackOff(0,0)`** —— 那两个域的语义是「失败也提交、丢弃」，**方向相反**） |
| 重试 / 死信 | ✅ 无死信（与 B 侧一致） |
| 幂等 | ✅ `CommentTransactionalService#insertCommentTx` 的**幂等分支**（同 ID ＋ 同身份 ＋ 同内容 ⇒ 跳过；**冲突 ⇒ 抛异常**） |
| ⚠️ `spring.kafka.listener.*` 全族键 | ⚠️ **域内自建容器工厂 ⇒ 这族键没有消费点**（§4.B）⇒ **不要**把它当「消费者开关」 |

### 4.G 安全 / JWT

| 检查项 | 结论 |
|---|---|
| JWT 解析 / 验签 / 过期 | ✅ 共享层 `JwtUtil`；claim 键 `"userId"`；**原始 claim 对象**写进 request attribute |
| **无认证响应** | ✅ **401 + 空 body**（`ResponseEntity.status(401).build()`，**不写任何字节**、不设 Content-Type） |
| 权限检查 | ➖ 本域无 Spring Security 链；**授权在 rpc 侧**（up 主 / 本人） |
| ⚠️ **uid 解析口径** | ⚠️ **comment 域接受可解析为 int64 的 String claim**（Go 出处 `strconv.ParseInt`）**⇒ 与 like 域相反**（like 的 Go 出处是 `.(json.Number)` 断言）⇒ **跨域「统一」这条判据 = 引入越权取数风险** ⇒ **不可抄 like** |
| ⚠️ **两段式验签** | ⚠️ B 侧 = Filter（3s 预算外）＋ Interceptor（预算内）；**C 侧 = 单 Interceptor** ⇒ **D-03** |

### 4.H 生命周期与异步

| 检查项 | 结论 |
|---|---|
| `@PostConstruct` | ✅ `CommentApiProperties` 的 **fail-closed** 密钥校验（缺变量 / 空串 / 占位符原文 ⇒ **启动失败**） |
| `@Scheduled` | ✅ `CommentFactDispatchScheduler`（**默认不启用**） |
| Kafka Listener | ✅ 1 个端点（装配层断言：**真进 `KafkaListenerEndpointRegistry`**） |
| `@PreDestroy` / 线程池 | ➖ 共享层承接（`ObservabilityLifecycle` 等） |

### 4.I 观测（指标 / span）

| 检查项 | 结论 |
|---|---|
| 5 个指标名 | ⚠️ **1 处与 B 侧 Java 实现不同**（`comment_request_seconds_counter` vs B 侧 `comment_request_seconds`）⇒ **D-01**（映射表已两次裁决，取 Go 侧口径） |
| `module` 标签值 | ✅ `comment_rpc` / `comment_list` / `comment_redis` / `comment_postgres` |
| `result` 标签取值集合 | ✅ B 侧自身**不一致**（6 个写用例 `{ok,biz_fail,sys_fail}`，`GetComment` 只有 `{ok,sys_fail}`）⇒ **照抄，不统一** |
| span 名 / tracer 名 / 属性名 | ✅ **24 个常量逐条一致**（V-4）；tracer 名 `"comment-rpc"` / `"comment.rpc"` 不变 |
| `management` 段 | ⚠️ **D-02**（合并裁决：`show-details` 取 rpc 侧 `when_authorized`，比 B 侧 comment-api 的默认 `never` **更松**） |

---

## 五、语义漂移清单

> **口径**：**B 侧既有缺陷**（B 侧自己的问题）**不算** C 侧漂移，但**必须登记**（`.agents/verification.md` §1.1）。
> **D-xx** = 本轮新登记；**B-xx** = B 侧既有缺陷的登记（照抄，不修）。

### 5.1 🔴 本轮新登记的差异（**D-xx**）

| ID | 位置 | B 侧事实 | C 侧行为 | 原因 | 影响范围 | 严重度 | 修复建议 | 验证方法 |
|---|---|---|---|---|---|---|---|---|
| **D-01** | `metrics/CommentRpcMetrics.java` | B 侧 Java 用 `registry.timer("comment_request_seconds", …)`（**Timer**）；而 B 侧其余口径与映射表都是 `comment_request_seconds_counter`（**Counter**，累加秒数） | 用 `Counter.builder("comment_request_seconds_counter")` | **映射表 §1.4 + §7.4 #9 两次裁决**：取 **Go 侧口径**（名字像 Histogram、实际是累加秒数的 Counter）⇒ **不是 C 侧自创** | 指标名 / 类型（Prometheus 导出名与类型） | **L3（可观测面）** | 无（裁决已定） | 指标名断言 |
| **D-02** | `application.yml` 的 `management.endpoint.health.show-details` | B 侧 **comment-api 整段没有这个键** ⇒ 生效值 = Boot 默认 = **`never`**；B 侧 **comment-rpc** = `when_authorized` | 合并后取 **`when_authorized`** | **D1 合并的必然结果**（一个进程只能有一个值）⇒ 取**显式写出来的那一侧** | `/actuator/health` 对**已授权**调用方**会**给出组件明细 ⇒ **比 B 侧 comment-api 更松** | **L3（可观测面）** | 无（D1 不可逆）；⚠️ **若要更严 ⇒ 改回 `never`**（会与 rpc 侧不一致） | 真上下文 + actuator 断言 |
| **D-03** | `interceptor/CommentJwtAuthInterceptor.java` | B 侧 = **两段式**：Filter（**验签在 3s 预算之外**）＋ Interceptor（预算内） | **合并为单 Interceptor**（like 域同款） | C 侧文件树**没有 `filter/` 包** ⇒ 沿用 like 域既有先例 | **放弃的是「验签耗时是否计入 3s 预算」的计时口径**，**不是 wire 行为**（401 空 body ＋ claim 键 ＋ 1006 全部不变） | **L3（超时口径）** | 无（文件树已定）；若需还原 ⇒ 加 `filter/` 包（**会破文件树**） | 断言「401 空 body」＋「claim 键」＋「1006」三条 |
| **D-04** | `contract/CommentKafkaMsg.java` ＋ `messaging/CommentAuditMessage.java` | B 侧**只有一个** `CommentAuditMessage`（**生产端与消费端共用**） | 拆成 **`contract/CommentKafkaMsg`（生产侧 wire）** ＋ **`messaging/CommentAuditMessage`（消费侧 DTO）** | **映射表 §7.4 #4 的职责边界未定义** ⇒ 按 D7/D11 划清 | **零**：11 字段同名同序 ⇒ 两个入口产出**逐字节相同**的 JSON | **L4（表达层）** | 无 | `CommentAuditMessageContractTest` 逐字节断言 ＋ 两 record 互转 |
| **D-05** | `wiring/NacosRegistration.java` | B 侧 group **硬编码** `DEFAULT_GROUP`，而 B 侧 yml 又写了 `group:` 键 ⇒ **该键在 B 侧无消费点** | 改为**读配置**（默认值同值） | 修掉 B 侧自己的「写了不生效」 | 注册名 / 分组（默认值下**完全相同**） | **L4** | 无 | 装配层断言 |
| **D-06** | `messaging/CommentAuditListener.java` 的异常重抛 | B 侧 `toRuntime(t)` = `t instanceof RuntimeException ? t : new IllegalStateException(t)` ⇒ **`Error` 会被包成 `IllegalStateException`** | C 侧：`RuntimeException` 原样、`Error` 原样、其它 → `IllegalStateException` | 直觉上 `Error` 不该被包装（B 侧那条是**顺带**产生的） | 只影响**日志里打印的异常类型**；**消费语义不变**（两者都不提交位点 ⇒ 重投） | **L4（日志面）** | 无 | — |
| **D-07** | `contract/CommentKafkaMsg.java` ＋ `messaging/CommentAuditMessage.java` | B 侧 `CommentAuditMessage` **显式**标 `@JsonIgnoreProperties(ignoreUnknown = true)` | **本轮已补**：两个 record 均显式标同一注解 | C 侧原本**只靠 Boot 默认值**（`FAIL_ON_UNKNOWN_PROPERTIES=false`）⇒ 行为当时**相同**，但那是**隐式依赖框架默认值** ⇒ 一旦全局打开 `fail-on-unknown-properties=true`，C 侧会在**收到未来版本新增字段**的旧消息时解析失败，而 B 侧不会 ⇒ **潜伏分歧** | Kafka 消息反序列化 | **L4（已消除的潜伏分歧）** | ✅ **本轮已修** | 反序列化「带未知字段」用例 |
| **D-08** | `service/CommentTransactionalService.java` | B 侧**声明了** `CommentDomainFactOutboxMapper outboxMapper` 字段并赋值，但**全文无任何调用点** | C 侧**移除该字段** | 纯 L4 清理（**零行为影响**） | 无 | **L4** | 无 | — |
| **D-09** | `application.yml` 的 `spring.main.web-application-type` ＋ `rag.side` | B 侧 comment-rpc 配 `web-application-type: none` ＋ `rag.side: rpc` | **未迁** | D1 合并后是 **web app** ⇒ 迁了 Tomcat 起不来；`rag.side` 门在 C 侧已不存在 | 启动形态 | **L4（D1 必然结果）** | 无 | 真上下文启动测试 |
| **D-10** | `contract/ErrMsg.java` 的 `MSG_BY_CODE` | B 侧是 **`public static final Map`** | C 侧是 **`private static final Map`**（对外只暴露 `getMsg(int)`） | 收窄可见性（**L4 表达层**）⇒ 「文案表不可篡改」这条守门点改用**反射**取表后操作，**守门点等价** | 零（对外 API 不变） | **L4** | 无 | `CommentErrorMessagesContractTest#msgTableIsImmutable`（反射路径） |
| **D-11** | `contract/ErrCodes.java` 的「枚举顺序即契约」 | B 侧是 **enum** ⇒ `values()` 顺序 = 声明顺序（JVM 规范保证） | C 侧是 **final class + int 常量** ⇒ 改用 `Class#getDeclaredFields()` 还原顺序 | D5「DTO/常量层惯用形态」的产物 | **顺序维度的证据强度依赖 HotSpot 的 `getDeclaredFields()` 行为**（**JVM 规范不保证**）⇒ 本仓 Temurin 21 实测 = class 文件声明顺序 | **L4 ＋ 证据强度注记** | 若要恢复规范保证 ⇒ 改回枚举（**会破 A9/D5 惯用形态**） | `CommentErrorCodeContractTest#everyCodeMatchesBSideOneByOne`（javadoc 已标注该边界） |
| **D-12** | proto 契约测试的**基准资产** | B 侧把基准放在**资源文件**：`comment-rpc/src/test/resources/proto-contract/comment-proto-contract.expected.txt` ＋ `tools/proto-wire-probe/comment/expected.txt` | C 侧本仓 `tools/` 下**没有** `proto-wire-probe/` ⇒ 期望值**逐字内联**为常量，**并各加一条 sha256 断言**把内联文本与 B 侧那份冻结文件**钉成逐字节相同**（实测两个 sha256 完全匹配：`69f05c75…` / `52a30a67…`） | C 侧无对应资产目录（**且 `tools/` 属 global 域写权**） | **零**：内联文本与 B 侧冻结文件**逐字节相同** ⇒ B 侧担心的「手抄抄错」被**可执行地**关掉（抄错一个字节 ⇒ 红） | **L4（表达层）** | 无 | `CommentProtoContractTest#inlinedBaselineIsByteIdenticalToBSideFrozenFile` ＋ `CommentProtoWireParityTest` 的 sha256 锚定 |
| **D-13** | `service/comment/pom.xml` 的 `io.grpc:grpc-inprocess` 依赖声明 | B 侧 `service/comment/comment-api/pom.xml:159-174` **明写「🔴🔴 刻意不在这里声明 grpc-inprocess」**，并给出根因：Maven「最近者优先」⇒ 本模块**直接声明**（深度 1）**顶掉** devh starter 传递进来的 compile 声明（深度 2）⇒ scope 一旦写成 `test` ⇒ **运行时 classpath 上整个 artifact 消失** ⇒ `NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder` ⇒ **组件根本起不来**，**而单元测试全绿** | **修前**：C 侧**直接违反**该明文约束（`pom.xml:68-72` 写了 `<scope>test</scope>`）⇒ 修前 fat jar 的 `BOOT-INF/lib/` 里**没有** `grpc-inprocess` ⇒ `java -jar` **启动失败**。**修后**：**整条删除**该直接声明（由 devh starter 传递，compile 作用域） | **C 侧自创缺陷**（**不是** B 侧问题 —— B 侧已明文警告过这个坑） | **产物层**：交付物 `comment-service-1.0-SNAPSHOT.jar` 能否启动 ⇒ **修前完全起不来** | **🔴 L1/L4（交付级 · 整个服务不可用）** | ✅ **本轮已修**（修法与 B 出处写在 `pom.xml` 的注释块里）＋ **新增 2 个守门类**把这条钉死：`CommentArtifactClasspathGateTest`（4 例）＋ `CommentRuntimeBootGateTest`（1 例） | 真 fat jar `java -jar`（**gRPC 端口真的在监听** ＋ 日志含 devh 的「gRPC Server started」）＋ `BOOT-INF/lib` 条目断言 ＋ `dependency:build-classpath -DincludeScope=runtime` |
| **D-14** | **响应 JSON 的键序**（`dto/comment/CommentItem.java` ＋ `dto/comment/SubjectInfo.java` 的**属性输出顺序**） | **运行时产出 = 字母序**（`attribute, children, content, created_at, …`）—— 因为 B 侧跑 **Jackson 3.1.5**（Boot 4 默认；证据：镜像内 `BOOT-INF/lib/jackson-databind-3.1.5.jar`）⇒ **字母序是 Jackson 3 的默认行为**。⚠️ B 侧这两个 DTO **没有** `@JsonPropertyOrder` ⇒ 顺序**未被钉住** ⇒ 顺带暴露：B 侧自己的 `service/article/…/dto/Article.java:71` 与 `service/hot/…/dto/HotArticleCard.java:70` **明写**「Jackson 默认按字母序 ⇒ 必须用 `@JsonPropertyOrder` 钉回 **Go 的结构体声明序**」，而 **comment 域漏钉** | **声明序**（`id, user_id, content, root_id, …`）—— C 侧 DTO 是 **immutable record**（D5）⇒ Jackson 2（**2.19.4**，D14 钉死；证据：fat jar 内只有 `jackson-databind-2.19.4.jar`）按 **record 分量序**输出 | **框架版本分叉**（Jackson 3 ↔ Jackson 2）⇒ **不是 C 侧的业务逻辑缺陷**：**值完全相同**（E-4 活体实证：9/9 用例解析后取值逐字段相等 ⇒ 只差键序）。⚠️ **三方对照**：Go 真源（`encoding/json`）按**结构体声明序** = `id, user_id, root_id, parent_id, content, created_at, meta, like_count, dislike_count, reply_count, attribute, state, children`（B 侧 javadoc 引 `types.go:6-20` · 13 字段 ＋ 首尾 2 行 = 15 行 ✓）⇒ **C 侧（声明序）与 Go（声明序）是同一种序**，只是与 B 侧 DTO 的 Java 字段序不同 | `/comment/v1/comment/list` 响应里 `data.comment[0]` 与 `data.subject` 两个对象的**键序**（**值不变**）⇒ 严格按字节比对时两侧**不等** | **L1（wire 字节级）· 框架限制类** | **C 侧不改** —— D14 已把全项目钉在 Jackson 2；改成字母序＝**背离 Go 的声明序**（B 侧的字母序是它自己**漏钉 `@JsonPropertyOrder`** 的默认值泄漏，不是被签字的契约）⇒ 登记即可。⚠️ 若将来要「与 B 侧运行时字节一致」，则需给 C 侧所有响应 record 加 `@JsonPropertyOrder`（**会改 C 侧 wire 字节 ⇒ 须走决策流程**）<br>✅ **2026-11-09 已决策（`global` 收口域 · 用户拍板「全用 jackson2」）** ⇒ ⇒ **维持 C 侧 = 声明序，不改成字母序** ⇒ ⇒ 理由：① **Go 真源（`encoding/json`）按 struct 声明序输出** ⇒ 声明序才是**契约**；② B 侧的字母序是它**自己漏钉 `@JsonPropertyOrder`** 的**默认值泄漏**，不是被签字的契约；③ ⇒ **C 侧改为字母序＝背离 Go 真源** ⇒ ⇒ **D-14 本条至此销账**（不再待决策）<br>⇒ ⇒ **配套机制已落**：全仓**不开** `SORT_PROPERTIES_ALPHABETICALLY`（**新守门 A17**：`tools/difftest/check-arch-invariants.py` · PASS 645/645 Java + 10/10 yml）＋ **运行时探针** `tools/difftest/jackson-key-order/run.sh`（22/22 序一致 + 字节 BYTE-EQ）<br>⚠️ **同根因的其他登记同步销账**：`users-user` 的 `DRIFT-USER-PARITY-003`（JSON 响应键序）⇒ **一次统一，不再各域各签一遍** | **E-4 活体对拍**（`tools/parity-probe/comment/`）：33 例中 **24 例逐字节相等**、**9 例**列为**已知差异**并**冻结两侧各自字节**（`known-diff.txt`）⇒ **差异形状变了仍会判红**；变异 M4 证明该白名单**参与判定** |
| **D-15** | **共享层 gRPC handler 线程上的「出向载体」绑定**（`common/…/autoconfigure/ObservabilityAutoConfiguration.java:324` 的 `GrpcObservationInterceptor.withDeadline`） | B 侧 `common/…/observability/RpcInterceptor.java:517-520` ⇒ `onHalfClose` 把**同一个绝对时刻**绑给**两个**载体：`DeadlineState.bindAbsoluteNanos(...)` **＋** `GoStyleRequestDeadline.bindAbsoluteNanos(...)`；且 `:304` ⇒ `effectiveTimeoutNanos = min(serverBudgetNanos, clientDeadlineRemainingNanos())` ⇒ **客户端给的更短 deadline 参与服务端有效期限** | ✅ **已修**（`global` 收口域 · 2026-11-04 · `DRIFT-COMMON-V20` · 提交 `01c11dd`）⇒ `:349-352` **两个载体绑同一个绝对时刻**（`DeadlineState` **＋** `GoStyleRequestDeadline`）＋ `:239-240` **min 语义**（客户端 deadline 参与）⇒ 旧的 `rpcDeadlineNanos(...)` **已删除** | ⚠️ **不是本轮新发现**：共享层台账 `docs/DRIFT/common/verification-common-2026-11-02.md` **DRIFT-COMMON-V17 · PARITY-006 根因 · `global` 收口域**（2026-11-03）**已登记**「⚠️ **只绑入向载体** `DeadlineState` ⇒ 出向 `GoStyleRequestDeadline` **仍未绑** ⇒ 爆炸半径更大 ⇒ **另案**」；`common/…/observability/DeadlineState.java:126-129` 有同款自述 ⇒ ⇒ **本条只是把它在本域的可观察后果钉出来** | ① **本域出向 gRPC 出口**（`article-rpc` / `message-rpc`）**拿不到入向剩余预算** ⇒ 只剩 **2s 客户端封顶**（B 侧是 `min(入向剩余, 2s)`）⇒ **下游耗时落在 2s 上下时两侧结局可能不同**；② **连带**：`common/…/redis/Deadline` 的期限闸门读 `GoStyleRequestDeadline.remainingNanos()` ⇒ **在生产里永不触发**（Redis 期限闸门形同虚设） | **L2 / L4（跨域 · 共享层）** | ✅ **已由 `global` 收口域修完**（本域**无 `common/**` 写权** ⇒ 由收口域按同一修法落地并销账）—— 修法与本域登记的完全一致：在 `GrpcObservationInterceptor.withDeadline(...)` 里把同一个绝对时刻也绑给 `GoStyleRequestDeadline`，并把服务端有效期限改成 `min(serverBudget, clientDeadlineRemaining)` | ✅ **棘轮已按其自身指示翻回**（2026-11-05 · `global` 收口域执行）—— `grpc/CommentOutboundDeadlineCarrierGateTest#outboundCarrierIsNotBoundAndClientDeadlineIsNotHonouredYet` ⇒ **已改名** `outboundCarrierIsBoundAndClientDeadlineIsHonoured` ＋ 三条断言**改回 B 侧真值**（① `isTrue()` ② 剩余 `≤` 客户端 deadline 且 `> 0` ③ 出向剩余纳秒 `> 0`）⇒ **实测 9/9 绿**。⚠️ **本域 §5.4 的「B 组第 ⑨ 点仍判 `NOT_VERIFIED`」按本域原判保留**（**根因已消除**，但逐项核对属本域 253 点清单 ⇒ **改判由本域 lane 重跑后自行给出**） |

### 5.2 📌 B 侧既有缺陷 / 既有行为的登记（**照抄，不修** —— `B-xx`）

> ⚠️ **这些是 B 侧自己的行为**（§1.1「不能把 B 原有问题误报为 C 的迁移问题」）⇒ C 侧**逐字继承**，不修。

| ID | 位置 | B 侧既有行为 | C 侧继承方式 |
|---|---|---|---|
| B-01 | `CreateCommentResp.subject_count` | **恒 0** | 照抄 |
| B-02 | `GetCommentResp.subject.owner_id` | **恒 0**（proto 里有 `owner_id = 7`，B 侧**丢弃**） | 照抄 |
| B-03 | `LikeCommentResp.like_count` | **恒 0** | 照抄 |
| B-04 | `SubjectInfo.owner_id` | **恒 0** | 照抄 |
| B-05 | `CommentItem.children` | **恒 `[]`**（每层先建空切片） | 照抄 |
| B-06 | `DeleteCommentResp.success` | **字面量 `true`**（不取 rpc 返回值） | 照抄 |
| B-07 | `GetComment` 的排序 | `sort_type == 0` ⇒ **热度序**（反直觉） | 照抄 |
| B-08 | `GetComment` 的 `onlyNormal` | **恒 false** ⇒ 已删除/审核中评论也进列表 | 照抄 |
| B-09 | `ManageComment` | **无鉴权** | 照抄 |
| B-10 | `UpdateSubjectState` | **只有 up 主检查**（**无 admin 分支**，与 proto 注释不一致） | 照抄 |
| B-11 | `likeOwnerLookupFailed()` | 发 **2001**（B 侧日志记 5004、线上发 2001） | 照抄 |
| B-12 | `subjectStateForbidden()` | 文案「**非up主无法评论**」（出现在改评论区状态用例里） | 照抄 |
| B-13 | `CreateComment` | **不写库**，只推 Kafka；落库在 Kafka 消费侧 | 照抄 |
| B-14 | `CommentAuditPublisher` | **必须同步等待** `future.get(timeout)`（与 like 域刻意不同） | 照抄 |
| B-15 | `CommentAuditListener` | 四条出口方向：解析失败⇒丢弃 / 落库失败⇒重投 / 缓存失败⇒记日志 / 通知失败⇒记日志 | 照抄 |
| B-16 | `messaging.destination` | **字面量 `"comment_audit"`**（**非真 topic 名**） | 照抄 |
| B-17 | `previewComment` | 按 **rune** 截断 40（`codePointCount` / `offsetByCodePoints`） | 照抄 |
| B-18 | `CommentSchemaGate` | **只告警不阻止启动** | 照抄 |
| B-19 | `CommentAuthorityEndpointConfig` | **默认不装配** | 照抄 |
| B-20 | `CommentRedisKeys#subjectKey` | key **缺 targetType**（跨类型同 ID 会撞 key） | 照抄 |
| B-21 | `CommentCacheService` | 四类读穿错误语义**各不相同** | 照抄 |
| B-22 | 两处 `ON CONFLICT DO NOTHING` | **不带 Columns** | 照抄 |
| B-23 | `V5__create_comment_report_record.sql` 的 `report_record` 表 | B 侧已于 **2026-10-24 签字**（`SD-2026-10-24-CAND-02`） | **逐字继承** ⇒ C ≡ B，**不重复请求签字** |
| B-24 | `GetComment` 的下游失败 | **一律 5001**（裸 `return nil, err`，**没有** code/msg 转换）⇒ 与其余 6 个端点不同 | 照抄（`CommentApiService#get` **刻意不写 catch**） |
| B-25 | `application.yml` 的 JDBC URL 参数 `?TimeZone=Asia/Shanghai` | ⚠️ **该参数在 pgjdbc 上是惰性的** —— 实测 pgjdbc **42.6.2** 的 `PGProperty` 里**没有** `TIME_ZONE` 字段 ⇒ URL 里写它**不生效**；B 侧 yml 的注释「Java 侧必须在 JDBC URL 里带上同一个参数，否则渲染出来会差一个时区」**是误导性的**（B 侧自己也是 pgjdbc ⇒ 同样惰性） | 照抄（**同一行 yml**）⇒ ⚠️ **C ≡ B，不是漂移**；真正的时区保证在**代码**里：`BUSINESS_ZONE = ZoneId.of("Asia/Shanghai")` ＋ `createdAt.atZoneSameInstant(BUSINESS_ZONE).format(WIRE_TIME)`（**两侧逐字相同**）＋ fact 侧显式 UTC 归一化（**两侧逐字相同**）⇒ 渲染**不依赖 JVM 默认时区**（⚠️ 唯一例外：`LocalDateTime.now().format(WIRE_TIME)` 这一处**确实**依赖 JVM 默认时区，**两侧同样**） |
| B-26 | `application.yml` 的 `spring.kafka.listener.*` 全族键 | ⚠️ **域内自建 `ConcurrentKafkaListenerContainerFactory` ⇒ 这族键没有消费点**（`auto-startup` / `concurrency` / `ack-mode` …全族失效）⇒ B 侧那些键是**看着像开关的误导** | 照抄（**刻意不写** `auto-startup=false`，并在本域台账与 `application-test.properties` 注释里点明） |
| B-27 | fact 载荷的时间字符串（`occurred_at` / `available_at`） | ⚠️ **Go 的 `time.RFC3339Nano` 会去掉末尾的 0**（`.1Z`），**B 侧 Java 的 `DateTimes.toRfc3339Nano` 保留尾零**（`.100Z`）⇒ **B 侧 Java 与 Go 本身就有这个差** | 照抄（**两侧逐字相同**：归一化 diff 显示 `CommentDomainFactService` **B 194 行 = C 194 行**，仅 import/package 不同 ⇒ `toRfc3339Nano` **逐字节一致**）⇒ **C ≡ B，不是 C 侧引入的漂移**；⚠️ 刻意**不**把它写成 C 侧守门断言（否则会把一条继承差异固化成 C 侧契约） |

### 5.3 映射表 §7.4 的 12 条待订正项 —— **本轮闭合情况**

| # | 待订正项 | 本轮处置 | 状态 |
|---|---|---|---|
| 1 | `GrpcErrFactory` 壳注释写反（说业务码进 message） | 已按 B 侧实现写（**trailer ＋ 标准码映射 ＋ description**） | ✅ 闭合 |
| 2 | `CommentController` 路由清单写错 | 7 条按 B 侧 `:96,104,112,126,133,141,149` 逐条订正 | ✅ 闭合 |
| 3 | 「有 public 段」写错 | `WebMvcConfig` **不调** `excludePathPatterns` ⇒ **public 集 = 空集** | ✅ 闭合 |
| 4 | Kafka 载荷职责边界未定义 | 拆 `contract/CommentKafkaMsg`（生产）＋ `messaging/CommentAuditMessage`（消费） | ✅ 闭合（**D-04**） |
| 5 | 缺 `CommentFactProcessLogging` | 已补齐（形态对齐 like/favorite） | ✅ 闭合（早已补，本轮复核） |
| 6 | 8 个迁移脚本一个没落地 | **V1..V8 全部落地**（从 B 侧逐字节复制） | ✅ 闭合 |
| 7 | JDBC URL 未带 `TimeZone=Asia/Shanghai` | **已带** | ✅ 闭合 |
| 8 | 缺 `comment-api.user-auth.*` ＋ `rag.protection` 四旋钮 | **已补**（含 `max-conns` / `max-bytes` / `cpu-shedding` / `breaker`） | ✅ 闭合 |
| 9 | 指标名 `comment_request_seconds` vs `_counter` | 取 **`comment_request_seconds_counter`**（映射表两次裁决） | ✅ 闭合（**D-01**） |
| 10 | proto `package` 归一 | B/C **两侧都**是 `package comment;`（B↔C diff 只差 `java_package` 一行） | ✅ 闭合（前提本身已订正） |
| 11 | 协作者独立成类是否正确 | **正确，无需动**（领域协作者**不参与**用例合并） | ✅ 闭合 |
| 12 | 缺 `JsonbTypeHandler` | **不是缺口**（共享层 `common/persistence/` 已承接） | ✅ 闭合 |

### 5.4 🔴 B 侧测试资产 → C 侧覆盖逐点审计（**独立 verifier 复核 ＋ 本轮已补缺口**）

> **口径**：B 侧 comment 域共 **41 个测试文件 / 253 个守门点**。
> 逐点提取 B 侧每个 `@Test` 的**可观察行为 + 期望值**，再到 C 侧找承载它的断言 ——
> **读到断言的期望值才判覆盖**，不接受「类存在＝已覆盖」。
> ⚠️ **B 侧既有缺陷也算守门点**（C 侧必须继承**同样的**反直觉值，例如恒 0 的字段、字面量 `true`、计数 == 2）。
> ⚠️ **`SUPERSEDED` = 0**：逐条查过 `docs/决策记录.md` 与 `docs/Intentional_Deviations/comment/_signed-deviations.md`
> （后者只有 proto `package` / `java_package` 两条）⇒ **没有任何签字依据**能支撑「主动变更取代」；
> ⇒ ⇒ D-03（JWT 两段式验签合并）**只是 DRIFT 登记、尚未签字**，**不能**当批准依据。

#### 5.4.1 审计后的原始判定（**补缺口之前的读数**）

| 判定 | 点数 | 占比 | 说明 |
|---|---|---|---|
| `COVERED` | **170** | 67.2% | C 侧有对应断言，且期望值与 B 侧逐字 / 语义等价 |
| `COVERED_BY_EQUIVALENT` | **11** | 4.3% | C 侧无同名测试，但用**更强或等价**的形态覆盖了同一守门点（如真 Tomcat 取代 MockMvc、真 PG 取代源码断言、sha256 锚定取代手抄基线） |
| `PARTIAL` | **3** | 1.2% | 只覆盖一部分 |
| **`NOT_COVERED`** | **51** | **20.2%** | **C 侧无对应断言** ⇒ 相应行为按 §十 判 **`NOT_VERIFIED`**（**不计入 PASS**） |
| `N_A_TO_C` | **18** | 7.1% | B 侧结构在 C 侧已不存在（其中 **12 点**是 B 侧测试基础设施的**端口登记册** ⇒ C 侧改用 Testcontainers 动态映射口 ⇒ 端口冲突面结构性消失） |
| `SUPERSEDED` | **0** | 0% | 无签字依据 |

> ⚠️ **原始口径说明**：上表是**审计完成时**的读数。其中 **A 组 4 点**（B 侧专拦 P0 的守门点）
> 已由本轮新增的 `CommentArtifactClasspathGateTest`（4 例）＋ `CommentRuntimeBootGateTest`（1 例）
> **转为 `COVERED`** ⇒ ⇒ 未覆盖点由 55 降为 **51**。

#### 5.4.2 🔴 51 个未覆盖点的分布（**补缺口之前的分组**）

> ⚠️ **本轮已订正一个计数笔误**：原表把 **E 组写成「11」**，但按 verifier 的**逐文件汇总表**，
> E 组实为 **13 点**（`CommentApiJwtVerificationScopeTest` 1 ＋ `CommentApiPropertiesSecretGuardTest` 6 ＋
> `CommentJwtTokenMatrixGateTest` 1 ＋ `CommentProtectionChainWiringTest` 1 ＋
> `CommentRedisDeadlineCarrierWiringGateTest` 2 ＋ `CommentRpcGoSemanticsGateTest` 1 ＋ `CommentSchemaPgGateTest` 1）
> ⇒ ⇒ **B 16 + C 10 + D 12 + E 13 = 51** ✔ 与 `NOT_COVERED = 51` **自洽**
> ⇒ ⇒ **分组小计此前漏了 2 点（E 组）**，总数 51 无误。

| 组 | 点数 | 缺什么 | 归属层 |
|---|---|---|---|
| **B 组** | **16** | **出向 2s deadline 硬上界**：`application.yml:409` 的 `cap-millis: ${COMMENT_OUTBOUND_DEADLINE_CAP_MILLIS:2000}` **配了，但生效链路零断言**（含「预算耗尽 ⇒ 立刻失败 <500ms」「env 覆盖到更小 ⇒ 上界跟着收紧」「本域 `withDeadlineAfter` 代码行数 == 0」「真 wire + 真共享层拦截器 ⇒ 出向载体在 handler 线程真的绑上」等） | L4 装配面 |
| **C 组** | **10** | **配置值对但无人守**：Redis 池 `64 / 16 / 0 / max-wait 4s`、`timeout 3s / connect-timeout 5s`、`discovery:///message-rpc` 与 `discovery:///article-rpc`（**三斜杠**，且「**两斜杠写法必须被拒绝**」这条反例也丢了）、「`target/classes` 里打包的 yml 必须与源码树一致」 | L4 配置面 |
| **D 组** | **12** | **领域语义守门点丢失**：缓存打点语义（D6/D7，钉的是**精确次数**，如「JSON 坏了 ⇒ 只打 `GetSubjectWithCache` 且计数 == **2**」）＋ 事实载荷 JSON 契约（`omitempty` 与 `null` 的区别、`old_state=0` **必须被输出**、20 字段顺序、`subject_ref` 的 `uid ≤ 0` 直接失败） | L2 / L4 |
| **E 组** | **13** | 局部 / 部分覆盖：JWT 密钥的**「生产同款启动」** 6 条（真 `SpringApplication` + 真 ConfigData）、**超限 body ⇒ 413**（压过 401）、**zero-pad HMAC 互通性**、Redis 载体耗尽 2 条、保护链「包装过的 call」、**幂等删除只扣一次计数**、Go 语义根因条 | 跨层 |

#### 5.4.3 ✅ 本轮补缺口后的闭合结果（**§1.10**）

| 组 | 补缺口前 | 补缺口后 | 承接测试类 |
|---|---|---|---|
| **B 组** | 16 点未覆盖 | **15 / 16 闭合** 🟠 | `grpc/CommentOutboundDeadlineCapGateTest`（13 例）＋ `grpc/CommentOutboundDeadlineCarrierGateTest`（9 例） |
| **C 组** | 10 点未覆盖 | **10 / 10 闭合** ✅ | `config/CommentRuntimeConfigContractTest`（16 例） |
| **D 组** | 12 点未覆盖 | **12 / 12 闭合** ✅ | `service/CommentCacheRedisMetricGateTest`（7 例）＋ `domain/service/CommentFactPayloadContractTest`（7 例） |
| **E 组** | 13 点未覆盖 | **13 / 13 闭合** ✅ | 6 个新测试类（共 **23 例**） |
| **合计** | **51** | **50 / 51 闭合** ⇒ 覆盖 **231/253 = 91.3%** | **11 个新测试类 / 4,769 行 / +75 例** |

> 🔴 **唯一未闭合点 = B 组第 ⑨ 点**（出向载体在 handler 线程真绑上 ＋ 有效期限 = `min(服务端预算, 客户端 deadline)`）
> ⇒ **根因 = D-15**（共享层差异，**归属 `global` 收口域** ⇒ 本域**无 `common/**` 写权**）
> ⇒ ⇒ 该点**仍判 `NOT_VERIFIED`**，**不计入 PASS**。
> ⇒ ⇒ 本域测试已把它**钉成棘轮**（断言当前形状 ⇒ 共享层修好后**会红**）⇒ 详见 **§1.10.4**。

> ✅ **另有 3 个 `PARTIAL`**（原审计判定）**本轮未动**：
> ① 路径判据（`/comment/v10/x` 这种「前缀相同但非路径分隔」的边界）② 断连 A/B（`REJECT_COMMANDS` 毫秒级 vs 排队到超时）
> ③ 在途查询的**存活时长 ≈ 剩余毫秒**（时间面，共享层只覆盖了「在途写 0 行」的结果面）
> ⇒ ⇒ 这 3 点**仍各贡献 1 个 `NOT_VERIFIED` 半边** ⇒ §六 的 `NOT_VERIFIED` 共 **4**（3 半边 ＋ 1 残留）。

#### 与本轮已修 P0 的关系（**为什么这个审计值得做**）

> 🔴 **B 侧那 4 条守门点存在的唯一理由，就是拦本轮踩中的这个 P0**
> （`CommentRpcArtifactClasspathGateTest:59/76/104` ＋ `CommentRpcRuntimeBootGateTest:90`）。
> ⇒ ⇒ **丢掉它们，缺陷就直接穿透到交付物**：修前 `mvn -o -pl service/comment -am test` **全绿**，
> 而 `java -jar …/comment-service-1.0-SNAPSHOT.jar` **起不来**
> ⇒ ⇒ 这就是 AGENTS.md §3.3.3「**跑绿 ≠ 本来就绿**」的教科书形态。
> ⇒ ⇒ 本轮已把 B 侧那 4 条**原样移植**为 C 侧测试（并额外加了 1 条**更强**的 pom 源码层守门点）。
>
> ⚠️ **而 §1.10 的第 1 次构建又复现了一次同款形态**：**301 例里唯一的红** = worker D 那条
> 「出向载体在 handler 线程真绑上」的断言 ⇒ ⇒ **它如实报出了 D-15**
> ⇒ ⇒ 这是「跑绿 ≠ 本来就绿」的**第二次**教科书形态（只不过方向相反：**红 ⇒ 找到了真漂移**）。


## 六、完成度统计（§11.6）

> ⚠️ **不得用文件数量或代码行数代表迁移完成度**。
> ⚠️ 每一项按 §十 的 7 个状态取值；**`FRAMEWORK_DIFFERENCE` / `NOT_VERIFIED` / `NOT_IMPLEMENTED` / `BLOCKED` 都不得计入 PASS**。

**计数单位 = B 侧的**守门点**（不是文件、不是代码行）** —— 取 **253** 个（§5.4 的逐点审计）。

| 状态 | 数 | 说明 |
|---|---|---|
| ✅ **PASS**（职责已完整承接，行为一致且有充分证据） | **231** | `COVERED` 170→**213** ＋ `COVERED_BY_EQUIVALENT` 11→**18**（用**更强或等价**的形态覆盖）⇒ **本轮 +50**（§1.10 的四组）⇒ 证据强度 **E-2 / E-3**（§1.7）<br>⚠️ **2026-11-09：计数不改** —— D-15 的**共享层根因已由 `global` 收口域闭合**（`DRIFT-COMMON-V20`）＋ 本域棘轮已翻回 **B 侧真值断言**（9/9 绿）⇒ ⇒ **但本域 253 点清单的改判由 comment lane 自行给出**（§1.10.4 明写：*`global` 收口域只销「共享层差异」这一根*）⇒ ⇒ **本数由本域 lane 重跑核对后自行更新** |
| ❌ **FAIL**（明确存在职责遗漏或行为漂移） | **0** | ⚠️ **本轮唯一的一条 FAIL 已修**：**D-13**（`grpc-inprocess` 被写成 `test` scope ⇒ **产物 jar 起不来**）⇒ 修后由 2 个新守门类（5 例）钉死 |
| ⚠️ **INTENTIONALLY_CHANGED**（有明确批准依据的主动行为变更） | **2** | **D-02**（`show-details` 合并裁决 · 依据 D1）· **D-03**（两段式验签合并 · **⚠️ 尚未进 `docs/Intentional_Deviations/` ⇒ 严格说**未签字**，见 §5.4 注） |
| 🔶 **FRAMEWORK_DIFFERENCE**（框架差异，尚未确认是否影响行为） | **0** | §4.A 的每一项都**已判定影响**（多为「否」） |
| 🟡 **NOT_VERIFIED**（实现存在，但证据不足） | **4** | ⚠️ **上轮 54 ⇒ 本轮 4（-50）**：① **B 组第 ⑨ 点**（出向载体在 handler 线程真绑上 ＋ 有效期限 = `min(服务端, 客户端)`）—— **根因 = D-15**（共享层差异 · 归属 `global` 收口域 ⇒ 本域无写权）⇒ **仍不计入 PASS**<br>⚠️ **2026-11-09：① 的根因已消除，但计数仍由本域给出** —— 根因侧已闭合（**2026-11-04 · `DRIFT-COMMON-V20`** ＋ 本域棘轮 `CommentOutboundDeadlineCarrierGateTest` 按其**自身指示**翻回 **B 侧真值断言** `outboundCarrierIsBoundAndClientDeadlineIsHonoured` ⇒ **实测 9/9 绿**，提交 `d091c38`）⇒ ⇒ **但本域 253 点清单的改判由 comment lane 重跑其核对后自行给出**（§1.10.4）⇒ ⇒ **`global` 收口域不代改本数**；②③④ **3 个 `PARTIAL` 的未覆盖半边**（路径判据 / 断连 A/B / 在途查询存活时长）⇒ **不得计入 PASS** |
| ⛔ **NOT_IMPLEMENTED**（C 尚未实现） | **0** | 69/69 主代码**全部有实现**（0 空壳）；B 侧 83 个源文件**全部**在承接表中出现 |
| 🚧 **BLOCKED**（缺环境，无法验证） | **0** | ✅ **本轮唯一的阻塞项已解除**：**E-4 双侧活体对拍**已建立（`tools/parity-probe/comment/`）并**真跑通过**（33 例：24 例逐字节相等 ＋ 9 例已知差异；变异 M1–M4 全红）⇒ 见 §1.9。⚠️ **剩余的「gRPC 面」与「写路径」是**不可比面**（拓扑产物 / 随机雪花 ID），不是阻塞项** |
| 🔶 **E-4 证据类**（**不改变 253 的计数口径**） | **33 个请求用例** | **不是** B 侧守门点的计数单位，而是**新增的一类证据**：HTTP 面两侧**逐字节**比对。⚠️ **它当时不把 §5.4 那 51 个 `NOT_COVERED` 点变成 PASS** —— 那些点集中在 **L4 装配面 / 配置 → 运行期** ⇒ **黑盒 HTTP 探针观测不到** ⇒ ⇒ **那 51 点是靠 §1.10 的装配层断言补上的，不是靠 E-4** |
| 🔶 **E-5 证据类**（本轮新增 · **不改变 253 的计数口径**） | **75 个新用例 / 11 个新测试类** | 装配层 / 进程内断言（真上下文 · 真 devh 链 · 真 gRPC · 真 Redis · 真 PG · 真 Micrometer）⇒ **本轮它确实把那 51 点里的 50 点变成了 PASS**（§5.4.3）⇒ 但**仍留 1 点**（D-15）<br>⚠️ **2026-11-09**：该 1 点的**根因已闭合**（`DRIFT-COMMON-V20` ＋ 本域棘轮翻回 ⇒ 9/9 绿）⇒ ⇒ **但 E-5 的计数口径改判同属本域 253 点清单**（§1.10.4）⇒ **本轮不改** |
| ⛔ **N_A_TO_C**（B 侧结构在 C 侧已不存在） | **18** | 其中 12 点是 B 侧测试基础设施的**端口登记册**（C 侧改用 Testcontainers 动态映射口 ⇒ 端口冲突面结构性消失）；其余 6 点与 D1 合并 / C 侧无 `filter/` 包有关 |

**换算成一句话**：**B 侧 253 个守门点里，C 侧已用 E-2/E-3/E-5 证据证明 231 个（91.3%）**；
**4 个判 `NOT_VERIFIED`**（1 个因 **D-15** 共享层差异 ＋ 3 个 `PARTIAL` 半边）⇒ ⚠️ **2026-11-09：D-15 的根因已由 `global` 闭合，但本域清单的改判归 comment lane 自行给出（§1.10.4）⇒ 本数本轮未改**；
**18 个因 B 侧结构在 C 侧不存在而 `N_A_TO_C`**；
**0 个 FAIL、0 个未实现、0 个 BLOCKED**。

⚠️ **这 231 是怎么来的（上轮 181 ⇒ 本轮 231，+50）**
⇒ **§5.4 那 51 个 `NOT_COVERED` 点里 50 点闭合**（C 10/10 · D 12/12 · E 13/13 · B 15/16）⇒ 见 **§1.10** 与 **§5.4.3**。
⇒ 其中 **43 点是 1:1 移植**（期望值与判据逐字照抄 B 侧），
**7 点是「等价或更强」形态**：
① C-⑩ yml **整文件**逐字节一致（B 侧只比 `grpc.client.*.address` ⇒ 强于 B）·
② B-⑨ 前半「入向载体在 handler 线程绑上」—— B 侧方向 `comment-api→comment-rpc` 在 C 侧**结构性不存在**（D1/D7 压平）⇒ 换成 C 侧**真实存在**的 `article-rpc` 方向（拦截器是 channel 级 ⇒ 机制等价）·
③ B-⑩ SQL 载体精度 —— B 侧用本域业务表，C 侧用**专用探针表 + 注解式探针 Mapper**（判据同款且不依赖 8 张业务表 / Flyway 账本）·
④ B-⑪ Redis 载体 —— B 侧那条含「装配面」部分**已被共享层覆盖**（`COVERED_BY_EQUIVALENT`）⇒ C 侧只补**活体**两条（拆开承接）·
⑤ B-⑫ 「无载体仍套 2s 封顶」—— B 侧只断 `CallOptions` 面，C 侧**加了行为面**（真调用 `DEADLINE_EXCEEDED`）⇒ 强于 B ·
⑥ B-⑮ 「按纳秒取预算」—— B 侧断毫秒精度，C 侧断**纳秒**（C 侧实现本来就是纳秒）⇒ 强于 B ·
⑦ B-⑯ 「devh 全局拦截器链真生效」—— B 侧**无对应点** ⇒ C 侧**新增**（只增不减的加固）。
⇒ ⇒ 另**新增 6 条 B 侧没有的加固 / 负控断言**（`cap=0` 阳性对照 · 打包 yml 缺失时**失败而非跳过** · 读穿结果一致性 · 若干负控）⇒ **只增不减**。

⚠️ **E-4 做完之后，这句话怎么变？**
⇒ **「承接完整」现在有了**双侧活体**层面的支撑（HTTP 面）**：两侧**同时真跑**、**33 个请求**逐字节比对 ⇒
**24 例逐字节相等** ＋ **9 例已知差异（根因已定位：Jackson 3 ↔ 2 的键序分叉 · D-14）**。
⇒ **E-4 本身不改变 253 的计数口径** ⇒ 它覆盖的是「L1/L2 的 wire 行为」，
而 §5.4 那 51 点**集中在 L4 装配面 / 配置 → 运行期** ⇒ **黑盒 HTTP 探针根本观测不到它们**
⇒ ⇒ **「E-4 已做」≠「缺口已补」** ⇒ 这两件事**必须分开说**；
⇒ ✅ **本轮是「E-5 装配层断言」把 50 点补上的**（§1.10）⇒ 计数口径才从 181 变成 231。


## 七、最终必须回答的问题（§十四）

| # | 问题 | 回答 |
|---|---|---|
| 1 | **C 是否完整承接 B 的全部实际职责？** | ✅ **职责层面：是** —— B 侧 83 个源文件**全部**在承接表中出现（机器复算 B 列 83/83）；C 侧 69 个类**0 空壳**。🔴 **但「行为已被证明」仍不是 100%**：B 侧 **253 个守门点**里，C 侧以 E-2/E-3/**E-5** 证据证明 **231（91.3%）**（**上轮 181 ⇒ 本轮 231**：§5.4 那 51 个 `NOT_COVERED` 点里 **50 点**已补成装配层真断言 · §1.10）、**4 判 `NOT_VERIFIED`**（① B 组第 ⑨ 点 · 根因 **D-15** 共享层差异 · 归属 `global` 收口域；②③④ 3 个 `PARTIAL` 半边）、**18 判 `N_A_TO_C`**。⚠️ 承接的**证据强度**最高**已是 E-4**（HTTP 面：两侧**同时真跑**、33 个请求逐字节比对 ⇒ 24 例逐字节相等 ＋ 9 例已知差异 · §1.9）⇒ 但 **gRPC 面与写路径仍不是可比面**（拓扑产物 / 随机雪花 ID）⇒ 且 **4 个 `NOT_VERIFIED` 未被 E-4 覆盖**（L4 装配面，黑盒探针观测不到）⇒ ⇒ 「承接完整」在 **HTTP 面**上是**双侧活体**结论，在 **L4 装配面**上是**装配层断言（E-5）**结论，**只剩 1 个点因共享层差异未闭合**。 |
| 2 | **Mapping_Table 中 RAG → RAG-V1 的映射是否与当前真实代码一致？** | ✅ **一致** —— `tools/check-mapping-coverage.py comment` **EXIT=0**，C 列 **69/69**、B 列 **83/83**。⚠️ 映射表 §7.4 的 12 条待订正项**已在本轮闭合**，但**映射表本身未被修改**（§十三禁止改映射表）⇒ 复核人应以本台账为准。 |
| 3 | **File_Tree 规定的目标结构是否正确落地？** | ✅ **是** —— 69 个主代码类**逐个落位**（包分布见 §1.2），**0 空壳**；`gen-file-tree.py`（改过 `ROOT` 的副本）重跑**无漂移** ⇒ 本轮新增的 **11 个测试类**（`config/` 2 · `domain/service/` 1 · `grpc/` 2 · `http/` 1 · `service/` 5）**已登记进 `docs/File_Tree/comment.md`**；`check-docs-vs-tree.py` **EXIT=0**。⚠️ 该脚本的 `ROOT` 仍**硬编码到主仓** ⇒ 在 worktree 里跑它会**重写主仓的 14 份文件树**（见 §1.6 第一条）⇒ 属**工具侧债**，不在本域写权内。⚠️ **本轮实测**：用改过 `ROOT` 的副本跑 ② 号判据（重新生成 ⇒ 逐字节比对）⇒ `comment.md` 与 `proto.md` **完全一致**；`article.md` 仍差一行 ⇒ 根因 = **工作树基于更早的提交**（主仓已有 `article/…/web/` 包，本工作树还没有）⇒ 属**跨域既有差异**（不属本域写权）⇒ **如实登记，不伪造**。 |
| 4 | **多文件合并是否造成职责、分支或异常路径遗漏？** | ✅ **否** —— 6 条 N:1 全部逐项核过（§3.1）；其中 **N-1（7 个 api 用例合 1）** 的 **13 个关键点**逐条在位（V-11）；**N-2 的 3 个 `@Transactional` 方法没有被合进领域服务**（事务边界保持）。 |
| 5 | **文件拆分是否造成调用链、数据传递或事务边界断裂？** | ✅ **否** —— 10 条 1:N 逐条核过（§3.2）；D-04 的 Kafka 载荷拆分**字段同名同序** ＋ 双向转换 ⇒ wire 未断。 |
| 6 | **API + RPC 合并是否改变原有入口和执行行为？** | ⚠️ **入口未变**（7 HTTP ＋ 7 gRPC ＋ `pb.` 别名全在）；**两处执行行为有差异且已登记**：**D-02**（`show-details` 合并裁决，**L3 可观测面**）· **D-03**（两段式验签合并，**L3 超时口径**，**放弃的是计时口径而非 wire 行为**）。 |
| 7 | **Common Starter 上收是否遗漏原有消费方？** | ✅ **否** —— 5 份域内副本全部上收且**只有本域一个消费方**；`@Import` 清单**为空**（A5 PASS）⇒ 不与 auto-config 混用。 |
| 8 | **Spring Boot 4 → 3.5 是否引入业务可观察行为变化？** | ⚠️ **有三处，均已登记**：**D-02** ＋ **D-03** ＋ **D-14**（🔴 **D-14 是 E-4 活体对拍抓到的新增项** —— **Jackson 3→2 会改变响应 JSON 的属性输出顺序** ⇒ B 侧字母序 / C 侧声明序 ⇒ **值相同、字节不同** ⇒ 归「框架版本分叉」类，**C 侧不改**）。其余框架差异（Boot 4 专有 artifact / Tomcat 11→10.1 / `spring-boot-kafka`）经 4.A 逐项判定**不影响业务可观察行为**，且都**在 Boot 3.5 上有等价替代**（共享层 starter 化 ＋ 显式依赖声明 ＋ 重跑重校断言）。 |
| 9 | **Tomcat、Jackson、Spring Framework 等底层版本变化是否产生实际漂移？** | ⚠️ **产生了一条，且已被 E-4 活体抓到**：**D-14** —— **Jackson 3.1.5（B 侧，Boot 4 默认）↔ Jackson 2.19.4（C 侧，D14 钉死）** ⇒ **响应 JSON 的属性输出顺序**不同（B 侧字母序 / C 侧声明序）⇒ **值完全相同、键序不同** ⇒ 归 **「框架版本分叉」**类（C 侧**不改**，理由见 D-14）。⇒ 除此之外**未发现**版本引起的漂移；**D-03**（JWT 验签两段式载体差异）的根因是**文件树没有 `filter/` 包**，不是版本。⚠️ Tomcat 10.1 与 11 的差异需在 **E-3 真 Tomcat** 下重校（D14 明令）⇒ 本轮 HTTP 层断言**全部走真 Tomcat**（`CommentHttpWireContractRealTomcatTest` 33 例 ＋ `CommentJwtTokenMatrixRealTomcatTest` 14 例）＋ **E-4 双侧活体**（§1.9）。🔴 **另有一条不是版本、但同样致命的依赖面缺陷**：**D-13**（`grpc-inprocess` 被写成 `test` scope ⇒ **产物 jar 起不来**）⇒ 已修 ＋ 已加产物层/进程级守门。 |
| 10 | **哪些差异已经通过证据证明安全？** | ✅ **21 项**（§5.2 的 B-01~B-27 中的行为继承项 ＋ §5.3 的 12 条闭合项 ＋ V-1~V-5 的归一化 diff ＋ 本轮新增的 **B-15 四条出口方向的位点级运行证据**）—— 这些差异要么**逐条相同**（E-2），要么**是 B 侧既有行为被原样继承**（按 §1.1 **不算 C 侧漂移**），要么**已被 E-3 运行证据钉住**。 |
| 11 | **哪些差异仍然无法确认？** | ⚠️ **三类**：① **4 个 B 侧守门点在 C 侧仍无「B 侧真值」断言** ⇒ 一律判 **`NOT_VERIFIED`** —— **1 个**是 B 组第 ⑨ 点（出向载体在 handler 线程真绑上 ＋ 有效期限 = `min(服务端预算, 客户端 deadline)`），**根因 = D-15**（共享层 `ObservabilityAutoConfiguration` 只绑入向载体 ⇒ 出向载体在生产里从不绑；且服务端预算不含客户端 deadline）⇒ **共享层台账 DRIFT-COMMON-V17 已登记「另案」** ⇒ **本域无 `common/**` 写权** ⇒ **只登记不修**；本域测试已把它**钉成棘轮**（共享层修好后会红）⇒ **§1.10.4**；**3 个**是原审计的 `PARTIAL` 半边（路径判据 / 断连 A/B / 在途查询存活时长）—— ⚠️ **E-4 做完并没有关掉这一类**（E-4 覆盖的是 HTTP wire 行为），**本轮关掉它的是 E-5 装配层断言**（§1.10）。② **gRPC 面与写路径是「不可比面」**（B 侧两进程跨进程 gRPC vs C 侧单进程域内直连 · D2；写路径响应带随机雪花 ID）⇒ 不是「没验」，是**拓扑/随机性**决定的 ⇒ 这两面**最强证据仍是 E-3**。③ **D-14 的 JSON 键序**虽已定位根因（Jackson 3 ↔ 2）并冻结双方字节，但**「C 侧要不要改成与 B 侧运行时字节一致」**属 **L1 字节级变更** ⇒ 按 §八「L1 未变 ⇒ 走决策流程」**本轮未动** ⇒ 登记为**框架限制类**，**不需要拍板**（若要改则需拍板）。⚠️ 另：映射表 **P6-a / P6-b / P6-c**（fact 合并后的遗留子项）与 **P2（熔断子集）** 仍**待拍板** ⇒ 本轮**未动**。 |
| 12 | **当前 C 是否具备可靠的行为对拍条件？** | ✅ **具备（HTTP 面）** —— 探针已建立：**`tools/parity-probe/comment/`**（`run.sh` / `probe-isolation.sh` / `mutate-parity.sh` / `expected.txt` / `known-diff.txt` / `README.md`）⇒ B 侧用**本地现成镜像**（**B 仓零改动**）、C 侧用**本仓 fat jar**，**两侧同时真跑** ⇒ **33 个请求逐字节比对** ⇒ **24 例逐字节相等** ＋ **9 例已知差异**；**变异 M1–M4 全部精确转红** ⇒ **不是恒绿**。⚠️ 仍**不具备**的部分：① **gRPC 面**（拓扑不同）② **写路径逐字节**（随机雪花 ID）③ **L4 装配面的黑盒观测**（那 51 点需要**进程内/装配层**断言，不是 HTTP 探针）—— ✅ **本轮已用 E-5 装配层断言补上 50 点**（§1.10），**剩 1 点因 D-15 共享层差异未闭合**。⚠️ 工程代价：探针的**五个隔离向量**（双 Nacos 命名空间 / 双 PG / 端口租约 / mvn 锁 / **双 Redis**）缺一不可 ⇒ 复用时**必须整套搬**，不能只搬 `run.sh`。⚠️ **本轮新学的并发纪律**：同一模块目录并发跑 maven ⇒ `target/` 互踩 ⇒ 症状伪装成「满屏 `NoClassDefFoundError`」⇒ ⇒ **并行 worker 一律禁跑 maven**（只做隔离 `javac` 编译），主控**持全局锁串行**跑 `mvn`（§1.10.5）。 |

---

## 附：本台账的证据边界（**必读**）

1. **⚠️ 本轮最强证据已是 E-4（双侧活体对拍）** —— 但**只覆盖 HTTP 面**（§1.9）⇒ 台账里的 ✅ 一律读作「**在该证据强度下一致**」（AGENTS.md §0 明写）；**gRPC 面与写路径仍按 E-3 读**（不可比面，见 §1.9.3）。
2. **⚠️ 「已落地」≠「已实现」；「源码级对照」≠「双侧活体对拍」** —— V-1~V-5 是**源码级**证据，**不能**替代活体对拍；**E-4 也只替代得了「HTTP wire 行为」这一层**，替代不了 L4 装配面（§1.9.5）。
3. **⚠️ 计数口径**：§1.2 的文件数用 `find … -path '*/src/main/*' -name '*.java'` 实测；**测试数**只取**回归日志的模块级汇总**（**不取** `surefire-reports/*.xml` 求和 —— 孤儿报告虚高），且**须同断 `skipped=0`**（AGENTS.md §五.8 守门四规则）。
4. **⚠️ E-4 探针的 9 条已知差异是「冻结的字节对」，不是「免责清单」** —— `known-diff.txt` 冻结了**两侧各自**的字节 ⇒ **任一侧字节变了就判红**（变异 M4 已证）⇒ ⇒ 台账里那 9 条 **不得**读作「C 侧对」或「C 侧错」，只能读作「**两侧字节不同、形状已冻结、根因已定位（D-14）**」。
4. **⚠️ 本文件只写 `docs/DRIFT/comment/` 目录**，**未改** `docs/DRIFT/_INDEX.md`（AGENTS.md §五.2 的写权边界）⇒ **`_INDEX.md` 里 comment 域那一行仍写着「⬜ 未建立」** ⇒ 收口人需手动更新（**那是 global 收口域的写权**）。
5. ⚠️ **本轮改动了 `service/comment/pom.xml`** —— 这是**主代码模块**的构建文件，属本域写权内；改动内容**只有一处**：删除 `io.grpc:grpc-inprocess` 的 `test` scope 直接声明（**D-13**）＋ 补上 B 出处与根因的注释块。**未改**任何依赖坐标、版本或 `dependencyManagement`。
6. ⚠️ **本轮新增的 2 个守门类会自己构建 fat jar**（B 侧同款做法）：`CommentArtifactClasspathGateTest#fatJar()` 在 jar 缺失/过期时调 `mvn -o -q package -Dmaven.test.skip=true -am -pl service/comment`。
   ⇒ ⚠️ **「嵌套 maven 与外层构建抢同一个 `target/`」是已知冲突面**（本仓另一域为此改用过「产物 zip 手术 + 单独调 surefire goal」）；
   ⇒ ⇒ 本轮**实测**：外层 `mvn -o -pl service/comment -am clean verify` 与全仓 `mvn -o -T1C clean verify` **均绿**（surefire 默认**串行**跑测试类 ⇒ 嵌套构建期间没有别的用例在跑）⇒ 风险可控。
   ⇒ ⇒ 若将来把 surefire 改成**并行**，这条要重新评估。
