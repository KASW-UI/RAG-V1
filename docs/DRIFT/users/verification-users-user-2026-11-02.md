# `users-user` 迁移审核报告 —— RAG（B 侧）→ RAG-V1（C 侧）

> **产出坐标**：由 **`align/users-user` 工作树**产出
> （`/Users/pr/Documents/GitHub/RAG-V1.worktrees/users-user`）· **基线 commit `e67c7ed`**
> （= 本地 `main` tip；⚠️ 本报告初稿写的是 `07ff14a`，其间 `main` 只多了一个
> **纯文档**提交 `e67c7ed`（仅改 `AGENTS.md` 一行）⇒ **代码等价** ⇒ 全部读数不受影响
> —— 2026-11-02 复核）· **核对日期 2026-11-02**
>
> **本文件归属**：`service/users/user/`（`user-service`）子域。
> ⚠️ **只写自己域**：`admin` 子域与 `users-common` 的台账各有其主，本文件**不代笔**。
> ⚠️ **不碰 `_INDEX.md`**（AGENTS.md §五.2：三个 `_INDEX.md` 合并后统一更新）。
>
> **判定基准**：**D16 · 2026-11-02 用户定** —— **B 侧（RAG 仓）是唯一行为判定基准**。
> ⇒ 本报告的所有「一致 / 不一致」结论，都是**读 B 侧源码**得出的，**不回溯**更早的历史版本。
>
> 📌 **相关文档**：
> 签字偏离 ⇒ [`docs/Intentional_Deviations/users/_signed-deviations.md`](../../Intentional_Deviations/users/_signed-deviations.md) ·
> 抄写路线图 ⇒ [`docs/Mapping_Table/users/user/user.md`](../../Mapping_Table/users/user/user.md) ·
> 文件树 ⇒ [`docs/File_Tree/users/user.md`](../../File_Tree/users/user.md)

---

## 11.1 审核范围

### 两侧坐标

| 侧 | 仓库 | 路径 | 角色 |
|---|---|---|---|
| **B（RAG，唯一真理源）** | `/Users/pr/Documents/java&golang/RAG` | `service/users/user/`（`user-api` **43** + `user-rpc` **15** = **58** 个 `.java`） | **唯一行为判定基准**（D16） |
| **C（RAG-V1，本仓）** | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/users-user` | `service/users/user/`（**48** 个主代码 `.java` + **6** 个测试 `.java`） | Spring 惯用目标形态（api+rpc 合并为一域一 app） |

### 实际解析版本（**读 classpath 取值，不是 pom 声明值**）

| 项 | B 侧（RAG 仓） | C 侧（RAG-V1） | 备注 |
|---|---|---|---|
| Spring Boot | **4.x** | **3.5.11** | ⚠️ **跨大版本** ⇒ 抄实现要按 3.5 的 API 面改写（D14） |
| Jackson | **3** | **2.19.4** | ⚠️ **跨大版本** ⇒ D12「全面 Jackson 3」**已作废**（D14 回退） |
| Spring Cloud | — | `2025.0.3` | 对应 Boot 3.5 线 |
| Spring Cloud Alibaba | — | `2025.0.0.0` | 原生 `nacos-client` **3.0.3** |
| gRPC | go-zero `zrpc` | `1.63.0` ＋ devh `3.1.0.RELEASE` | ⚠️ devh **只认 `GrpcServerConfigurer`** ⇒ 拦截器漏配 = 静默失效 |
| protobuf | — | `3.25.5` | 禁 `protobuf-javalite` ≤3.25.4（构建期 enforcer） |
| jjwt | golang-jwt v4 | `0.12.6` | ⚠️ jjwt **强制 ≥32 字节** ⇒ 共享层 `JwtUtil.padToHmacKeySize` 收口 |
| MyBatis-Plus | 3.5.x | `3.5.17` | |
| JDK | — | `21` | |

### 本次使用的资料

| 资料 | 用途 |
|---|---|
| `docs/Mapping_Table/users/user/user.md` | 抄写路线图（§1.0c 的 B↔C 逐类对应） |
| `docs/Mapping_Table/users/_shared.md` | users 域共享层 + 缺口清单 F-1~F-8 |
| `docs/File_Tree/users/user.md` | 目标文件树 + L1 特殊约定 |
| `docs/L3-降级清单.md` D-01 | D5 的 Bean Validation 降级登记 |
| `docs/决策记录.md` D1/D2/D3/D5/D7/D14/D16/D17 | 锁决策 |
| `docs/DRIFT/users/verification-users-common-2026-11-02.md` | 兄弟 lane 台账（D-01~D-11 偏离登记，本域**只读**） |
| B 侧 `service/users/user/**` **全部 58 个主代码文件** | **逐个读过**（6264 行） |

---

## 11.2 B → C 责任承接表

### 逐类承接（B 58 → C 48）

| B 侧分组 | B 文件数 | C 侧落点 | C 文件数 | 承接方式 |
|---|---|---|---|---|
| `user-api/dto/` | 19 | `dto/auth/`(4) + `dto/avatar/`(6) + `dto/user/`(9) | **19** | 1:1（**2 个改名**：见 DEV-USER-001） |
| `user-api/auth/` | 4 | `interceptor/{JwtAuthPaths,JwtSignatureVerifier,JwtVerificationResult,JwtAuthFilter}` | **4** | 1:1（**1 个改名**：`GoStyleJwtAuthFilter` → `JwtAuthFilter`） |
| `user-api/controller/` | 1 | `controller/UserController.java` | 1 | 1:1 |
| `user-api/config/` | 3 | `config/{UserApiProperties,WebMvcConfig}` + （`RedisDisconnectedBehaviorConfig` **删**） | 2 | 1:1 ×2 ＋ 1 删（DEV-USER-009） |
| `user-api/advice/` | 2 | `advice/{UserApiResponseAdvice,UserApiExceptionHandler}` | 2 | 1:1（改名） |
| `user-api/interceptor/` | 1 | `interceptor/JwtAuthInterceptor.java` | 1 | 1:1 |
| `user-api/metrics/` | 1 | `metrics/UserApiMetrics.java` | 1 | 1:1 |
| `user-api/service/` | 10 | `service/{UserApiService,UserApiSupport}` | 2 | **9:1**（D2 直连）＋ 1:1 |
| `user-api/`（入口） | 1 | `UserServiceApplication.java`（与 rpc **共用**） | — | N:1（D1 合并） |
| `user-rpc/service/`（logic） | 9 | `domain/service/{UserDomainService,AvatarDomainService}` | 2 | **9:1** |
| `user-rpc/service/impl/` | 1 | `grpc/UserGrpcService.java` | 1 | 1:1（改名） |
| `user-rpc/domain/` | 2 | `domain/entity/{UserEntity,UserAvatarHistoryEntity}` | 2 | 1:1 |
| `user-rpc/mapper/` | 3 | `mapper/{UserMapper,UserAvatarHistoryMapper}` | 2 | 1:1 ×2 ＋ `JsonTextTypeHandler` **上移共享层** |
| `user-rpc/exception/` | 1 | `exception/UserExceptions.java` | 1 | 1:1 |
| `user-rpc/metrics/` | 1 | `metrics/UserRpcMetrics.java` | 1 | 1:1 |
| `user-rpc/config/` | 1 | `config/UserRpcProperties.java` | 1 | 1:1 |
| `user-rpc/bootstrap/` | 3 | `wiring/{UserSchemaBootstrap,SnowflakeBootstrap,NacosRegistration}` | 3 | 1:1 |
| `user-rpc/`（入口） | 1 | `UserServiceApplication.java`（**共用**） | — | N:1（D1 合并） |
| `user-proto/` | — | 根级 `proto/src/main/proto/users.proto` | — | **逐字节一致**（D7 集中化） |
| **合计（B 侧）** | **58** | | **48** | ⇒ **C 侧 48 个文件全部有实现**（不再是空壳） |

### C 侧新增件（**B 侧没有 ⇒ 每件都要有职责依据**）

| C 侧文件 | 职责依据 | 判定 |
|---|---|---|
| `contract/ErrCodes.java` | 映射表 §0.5「B 侧 errmsg 在 `users-common` ⇒ C 侧落 `contract/`（**域内私有错误契约**）」 | ✅ 有据（**但实现是转发视图**，见 DEV-USER-006） |
| `contract/ErrMsg.java` | 同上 | ✅ 有据 |
| `interceptor/JwtAuthFilter.java` | **B 侧有** `GoStyleJwtAuthFilter`（同名不同类 ⇒ 类改名）⇒ **不是新增** | ✅ 有据（1:1 改名） |
| `interceptor/{JwtAuthPaths,JwtSignatureVerifier,JwtVerificationResult}.java` | **B 侧有**同名文件 ⇒ **不是新增** | ✅ 有据（1:1） |

⇒ **本域 `C 新增 = 0`**（与映射表 §7.2 的口径一致）⇒ RAG-V1 在本子域是**重组**而非新增能力。

---

## 11.3 多文件合并 / 拆分专项报告（§八）

### 8.1 合并（B 多份 → C 1 份）

| 合并 | B | C | 行为等价论证 |
|---|---|---|---|
| api 用例 | `UserLoginService` / `UserRegisterService` / `UserGetService` / `UserUpdateService` / `UserDeleteService` / `UserLogoutService` / `AvatarUploadService` / `AvatarSelectService` / `AvatarHistoryService`（**9**） | `service/UserApiService.java`（**1**） | ⚠️ **D2 直连**：B 的 9 个用例经 `UserRpcClient` 走 gRPC ⇒ C 侧直调 `UserDomainService` ⇒ ⇒ **判定链 6 步、错误码映射逐条保留**（`getUser` 用 `claimedUID` → 一次领域查询 → `IdentityResolver.verifyUser`，**只 1 次 DB 读**，与 B 等价） |
| rpc logic | 9 个 logic（含 `AvatarDomainService`） | `domain/service/{UserDomainService,AvatarDomainService}`（**2**） | ⚠️ **`selectAvatar` 的多步原子写**必须**独立 Bean**（自调用不触发代理）⇒ ⇒ 拆成 2 个而不是 1 个 |
| 进程 | `user-api` ＋ `user-rpc`（**2 进程**） | `UserServiceApplication`（**1 进程**） | D1 锁定；⇒ 「rpc 进程不可用」这一故障模式**消失** |

### 8.2 拆分（B 1 个类 → C 多個类）

| 拆分 | B | C | 理由 |
|---|---|---|---|
| 错误契约 | `users-common/errmsg/ErrorCode.java`（**1**） | `service/users/common/{ErrCodes,ErrMsg}`（**2**）+ 域内 `contract/` 转发视图（**2**） | 决策锁（D7 错误契约拆到域内）＋ 共享层真源单一 |
| DTO 分包 | `dto/`（平铺 **19**） | `dto/{auth,avatar,user}/`（**4/6/9**） | D11：按业务对象再拆一级 |

### 8.3 共享能力上收（B 的域内副本 → 根 `common/`）

| B 侧域内副本 | C 侧落点 | 本域处置 |
|---|---|---|
| `config/RedisDisconnectedBehaviorConfig.java` | `common/redis/ClientAutoConfiguration` | **删**（DEV-USER-009） |
| `mapper/JsonTextTypeHandler.java` | `common/persistence/JsonTextTypeHandler.java` | **删**（实体改用共享层那个） |
| `service/IdentityResolver.java` | `service/users/common/IdentityResolver.java`（**users 域共享层**） | 域内**不重复实现**，直接用静态方法 |

### 8.4 独立进程合并（B 的 api + rpc 两进程 → C 的单 app）

| 维度 | B 侧 | C 侧 | 备注 |
|---|---|---|---|
| 入口类 | 2 个 | **1** 个（`UserServiceApplication`） | D1 |
| 端口 | HTTP `49900` ＋ gRPC `49210` ＋ management `9094` | HTTP `8881` ＋ gRPC `8081` | ⚠️ **端口是 L1**（AGENTS.md §五.5）⇒ **本条按 D1 的合并形态落地**；B 侧的端口由部署清单承载，C 侧由 `application.yml` 的 `${USER_HTTP_PORT:8881}` / `${USER_GRPC_PORT:8081}` 承载 |
| Nacos 注册 | **2** 个条目 | **1** 个（DEV-USER-004） | 映射表锁决策 |
| 配置键 | 两份 yml | **一份** yml，**两个键都保留**（`user-api.*` ＋ `user-rpc.*`） | ⚠️ **键名是 L1** ⇒ 不可改 ⇒ ⇒ 两个键都指向**同一个环境变量** ⇒ 不可能只配一边 |
| Flyway | 无 | **无**（D17） | 建表走 `users-common` 的 `SchemaInitializer` 幂等重放 |

---

## 11.4 框架版本差异报告（§七 · 逐条）

| 差异点 | B 侧写法 | C 侧写法 | 处理 |
|---|---|---|---|
| **Jackson 3 → 2** | Jackson 3（`JsonMapperBuilderCustomizer` 等新 API） | Jackson **2**（`com.fasterxml.jackson.*`，**2.19.4**） | ⚠️ D14 回退 ⇒ **零排除项**、**零 `tools.jackson` 引用** ⇒ 本域的 DTO/实体**零回改** |
| **Boot 4 → 3.5** | `spring-boot-flyway` / `spring-boot-data-redis` 等独立 artifact | `org.springframework.kafka:spring-kafka` ＋ `spring-boot-starter-data-redis` | ⚠️ Boot 4 专有 artifact **在 3.5 不存在** ⇒ 本域**零命中**（本域不引 Kafka） |
| **jjwt** | golang-jwt v4 | jjwt `0.12.6` | ⚠️ jjwt 强制 ≥32 字节 ⇒ 共享层 `JwtUtil.padToHmacKeySize` 收口（签发方与验签方**同一份** padding 逻辑）⇒ wire 不变 |
| **gRPC server** | go-zero `zrpc` | devh `3.1.0.RELEASE` | ⚠️ **devh 只认 `GrpcServerConfigurer`** ⇒ 拦截器链挂载点由共享层 `ProtectionAutoConfiguration#seaGrpcServerConfigurer` **唯一**承担（DEV-USER-005）⇒ **零静默失效** |
| **Proto** | `package user;`（生成到 `com.rag.users.user.proto`） | `package user;`（生成到 `com.rag.proto.user`） | **pb 包名变、proto package 未变** ⇒ **gRPC 方法全路径不变**（DEV-USER-007） |
| **ORM** | MyBatis-Plus | MyBatis-Plus `3.5.17` | 同构；表达式层差异不产生行为分叉 |
| **Bean Validation** | go-zero 必填校验器 | Hibernate Validator | **L3 降级 D-01**（状态码与结构不变，**只有文案**变） |
| **Redis 断连** | 域内 5 份副本（后已删并上移） | 共享层 `ClientAutoConfiguration` | 零行为差（DEV-USER-009） |

---

## 11.5 语义漂移清单

### 11.5.1 C 侧**已闭合**的漂移（本轮修的）

| 编号 | 漂移 | 修法 | 证据 |
|---|---|---|---|
| **DRIFT-USER-IMPL-001** | **api 层把 proto 类型当 HTTP 入参** ⇒ `UserApiService` 的 `login` 收的是 `com.rag.proto.user.LoginReq`（proto 的 getter 是 `getUsername()`，不是 record 的 `username()`）⇒ **首轮编译直接失败** | 删掉 9 条 proto import ⇒ HTTP 入参一律用**域内 DTO**（record）⇒ 传给领域层时才构 proto | `mvn -pl service/users/user -am package` 由 **红 → 绿**（首轮编译的 11 条 `找不到符号`） |
| **DRIFT-USER-IMPL-002** | **`grpc.client:` 空键 ⇒ 整个上下文启动失败**。B 侧与本仓基线的 10 个域 yml 都写成「只有注释的空键」⇒ YAML 把它解析成**空串** ⇒ Spring 拿 `String` 去转 `Map<String, GrpcChannelProperties>` ⇒ `ConverterNotFoundException` ⇒ **`@SpringBootTest` 起不来** | 本域 yml 改成 `client: {}`（合法空 map）⇒ 键**仍在**（结构不变）⇒ 绑成空 Map | `UserServiceApplicationTests` 6/6 绿（修前：**上下文起不来**） |
| **DRIFT-USER-IMPL-003** | **测试的 in-process gRPC server 名字 JVM 内必须唯一** ⇒ 多个 Spring 上下文用同一个名字 ⇒ `IOException: name already registered` ⇒ 后起的上下文全挂 | 只在需要真 gRPC 的测试类里用 `@SpringBootTest(properties=...)` 指定**唯一**名字；其余上下文 `grpc.server.port=-1` **不起 gRPC server** | `UserGrpcServiceContractTest` 4/4 绿（修前：另外两个上下文全挂） |

### 11.5.2 **继承的 B 侧差异**（D16 ⇒ 继承，**不回溯**）

| 项 | B 侧行为 | C 侧行为 | 判定 |
|---|---|---|---|
| 未知路径（`/user/v1/**` 之外） | **500 + 信封**（B 的兜底 `handleAny` 吃掉 Spring 的 404） | **500 + 信封**（`handleAny` 与 B **逐字同构**） | ✅ **C ≡ B**（B 侧 Go 端是 404 ⇒ 那是 B 侧自身的差异） |
| 未知路径（`/user/v1/**` 之内） | **401**（B 的 `WebMvcConfig` 用 `addPathPatterns("/user/v1/**")` ⇒ 静态资源 handler 兜住 ⇒ 拦截器先跑） | **401**（同前缀注册） | ✅ **C ≡ B** |
| 405 | 裸状态码（无 `Allow` 头、无 body） | 同 | ✅ **C ≡ B** |
| `extra_info` 列类型 | `text` | `text` | ✅ **C ≡ B** |
| `users_user.status` | `NOT NULL DEFAULT 0` | 同 | ✅ **C ≡ B** |

### 11.5.3 **未闭合**（⇒ 交收口域 / 他 lane，**不是**本域能单方面修的）

| 编号 | 项 | 归属 | 说明 |
|---|---|---|---|
| **DRIFT-USER-IMPL-004** | **其余 9 个域的 `application.yml` 里 `grpc.client:` 仍是空键** ⇒ 那些域**一启动就挂**（与 DRIFT-USER-IMPL-002 同形） | **收口域**（跨域） | ⚠️ 本域**已修自己那份**；其余 9 份**不在本 lane 写权面**（AGENTS.md §五.3 规则 3）⇒ ⇒ 登记在此，交收口域 sweep |
| **DRIFT-USER-IMPL-005** | **`docs/Mapping_Table/users/admin/admin.md:158` 的 C 列仍指向已被删除的 `service/users/user/src/main/java/com/rag/user/config/RedisDisconnectedBehaviorConfig.java`** | **admin lane** | ⚠️ 该行的备注本身就写着「B 已把域内 5 份副本全删 ⇒ **C 侧也要走共享层**」⇒ 本域的删除**正是该行的意图**；但**该表属 admin 子域** ⇒ ⇒ 本 lane **不代笔**（AGENTS.md §五.2「只写自己域」）⇒ 登记在此<br>✅ **已闭合（2026-11-02 · 主树收口）** —— `docs/Mapping_Table/users/admin/admin.md` 的 C 列已改指共享层真实落点 `common/src/main/java/com/rag/common/redis/ClientAutoConfiguration.java`（断连行为收口，`ClientOptions.DisconnectedBehavior.REJECT_COMMANDS`）⇒ 悬空指针消除（该表属 admin 子域，故由主树收口时统一改） |
| **DRIFT-USER-IMPL-006** | **`docs/File_Tree/proto.md` 陈旧**：它声称 `proto/src/test/java/` 存在，实测**没有** | **收口域**（`proto/` 是跨域模块） | ⚠️ 本轮跑 `gen-file-tree.py` 时**实测到**（生成物与实际不符）⇒ 但 `proto/` **不是本域写权面** ⇒ ⇒ **本 lane 已把该文件的改动 `git checkout` 复原**，只登记不改 |
| **DRIFT-USER-IMPL-007** | **B 侧 `user-rpc` 的 `spring.application.name = user-rpc` vs C 侧 `user-service`** | 结构性（D1 合并的必然结果） | ⚠️ 合并进程的 app 名只能是**一个** ⇒ C 侧用 `user-service`；⚠️ **Nacos 注册名**刻意**不**跟着改（仍 `user-rpc`，DEV-USER-004）⇒ ⇒ 两者**不是同一件事**，勿混 |
| **DRIFT-USER-IMPL-008** | **`docs/File_Tree/users/user.md` 的 §五「状态」块仍写着「⬜ 全部待填（每个 Java 文件只有 package + 空类型 + TODO javadoc）」** —— 而本域 **48 个文件已全部有实现** | **收口域**（`tools/gen-file-tree.py` 是跨域工具） | ⚠️ **根因在脚本**：`gen-file-tree.py` 的 §五 状态块是**写死的全局占位**（不按域分派）⇒ ⇒ 任何域实现完都会撞这句 ⇒ ⇒ **手改生成物 = 下次 regen 就被覆写**（「生成物当落点」反模式）⇒ ⇒ 本 lane **不手改**、**不改共享脚本** ⇒ 只登记 |
| **DRIFT-USER-IMPL-009** | **全仓 `mvn -o -T1C clean verify` 在本域之外红**：`article` / `comment` / `favorite` / `follow` / `hot` / `like` / `message` / `task` **8 个域**的 `spring-boot-maven-plugin:repackage` 报 `Unable to find main class` | **各域 lane**（结构性） | ⚠️ **基线自带，非本轮引入**：实测 `git show 07ff14a:service/<域>/.../*ServiceApplication.java` ⇒ **8 个域的 Application 全都没有 `main` 方法**（还是 8 行空壳）⇒ ⇒ repackage **必然**失败。⚠️ 本域**补上了 `main`** ⇒ ⇒ 本域的 repackage **通过** ⇒ ⇒ 全仓 verify 要等 10 个域都落地才会绿（AGENTS.md §4.2 的「全量收口」是**所有 lane 完成后**的动作，不是单域的验收门） |

---

## 11.6 完成度统计

### 11.6.1 文件级

| 项 | 数 |
|---|---|
| B 侧主代码 `.java`（`service/users/user/`） | **58**（`user-api` 43 ＋ `user-rpc` 15） |
| C 侧主代码 `.java`（`service/users/user/src/main/java/`） | **48**（骨架 45 − 1 删除 ＋ 4 新增） |
| C 侧**空壳残留** | **0** |
| C 侧测试 `.java` | **7** |
| 映射表 C 列覆盖 | **109/109 = 100%**（users 域口径） |
| 映射表 B 列覆盖 | **145/145 = 100%**（users 域口径） |

### 11.6.2 行级（本域实现规模）

| 层 | 文件 | 行数（含 javadoc） |
|---|---|---|
| `dto/` | 19 | 479 |
| `domain/service/` | 2 | 905 |
| `service/` | 2 | 702 |
| `advice/` | 2 | 348 |
| `interceptor/` | 5 | 533 |
| `mapper/` | 2 | 378 |
| `domain/entity/` | 2 | 343 |
| `metrics/` | 2 | 219 |
| `config/` | 3 | 252 |
| `wiring/` | 3 | 414 |
| `grpc/` | 1 | 249 |
| `contract/` | 2 | 134 |
| `exception/` | 1 | 47 |
| `controller/` | 1 | 187 |
| 入口（`UserServiceApplication`） | 1 | 100 |
| **主代码合计** | **48** | **5,313** |
| **测试合计** | **7** | **1,337** |

> 口径：`wc -l` 实测（`find … -name '*.java' -exec cat {} + | wc -l`），**不是估算**。

### 11.6.3 测试级

| 测试类 | 例数 | 覆盖 |
|---|---|---|
| `UserServiceApplicationTests` | 6 | 上下文装配 · `@MapperScan` 生效 · `@GrpcService` 被扫到 · JWT 两段 bean 都在 · **DDL 在启动期真被重放**（查真 PG）· 表名 L1 |
| `api/UserApiHttpContractTest` | 12 | **真 Tomcat**（非 MockMvc）：L1 路由/method · public 白名单 · 信封三字段 · 401（缺 token / 坏签名）· 400+text/plain+nosniff · **🔴 验签来源 = filter**（009② 闭环）· 签名合法但用户不存在 ⇒ 200+1003 · **端到端 register→login→get**（真 PG + 真 bcrypt + 真 JWT）· 未知路径两态 · **🔴 400 的「报哪个字段」确定且与 B 同字段**（连打 8 次逐次相同） |
| `grpc/UserGrpcServiceContractTest` | 4 | **真 gRPC server**（in-process）：9 个方法全被 starter 分发 · `StatusRuntimeException` 原样透传（code+description）· 未映射异常 ⇒ INTERNAL + `"类名: 消息"` |
| `wiring/UserNacosRegistrationWiringTest` | 6 | 注册名 = `user-rpc` · 端口 = gRPC 端口 · group 来自配置 · 默认值零变化 · yml 仍声明 group · 自动注册已关 |
| `config/UserApiPropertiesSecretGuardTest` | 7 | 密钥 fail-closed：null / 空串 / **未解析占位符** 三路都必须启动失败（api ＋ rpc 两侧） |
| `mapper/AvatarTransactionWiringTest` | 5 | 🔴 **事务边界的真实测**（D-005 / D-015）：**真 UNIQUE 约束冲突**制造中途失败 ⇒ 断言 W2 的 UPDATE 被回滚 · 同序列成功路径作非空洞对照 · 5 步原子的 uid 隔离 · mapper bean 真被事务 advisor 包住 |
| **合计** | **40** | **skipped = 0** |

---

## 11.7 运行证据（**可复跑**）

> ⚠️ **口径提醒**：以下命令**必须在本工作树内**跑。
> ⚠️ `tools/gen-file-tree.py` 与 `tools/check-mapping-coverage.py` 的 `ROOT` **硬编码为主工作区**
> ⇒ 在工作树里直接跑**量的是别人的树** ⇒ ⇒ 须改指向 worktree（本次用 `/tmp` 下的副本，**不改仓内脚本**）。

```bash
# ── 1. 编译 ────────────────────────────────────────────────
cd /Users/pr/Documents/GitHub/RAG-V1.worktrees/users-user
mvn -o -pl service/users/user -am -DskipTests package
# ⇒ BUILD SUCCESS（16 个模块：根 ＋ common ＋ proto ＋ service ＋ users ＋ users-common ＋ user-service）

# ── 1b. 本域 + 全部依赖模块的完整 verify（串行）──────────────
mvn -o clean verify -pl service/users/user -am
# ⇒ BUILD SUCCESS（common 3:10 · proto 0:48 · users-common 1:59 · user-service 2:59 · 合计 08:56）
# ⇒ user-service: Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
# ⇒ 参与测试的模块合计: tests=320 failures=0 errors=0 skipped=0
#    （320 = common 234 + users-common 46 + user-service 40；
#      口径 = **回归日志的模块级汇总**，不取 `surefire-reports/*.xml` 求和 —— §五.8 守门硬规则 ①
#      ⇒ ⚠️ 也**不能**把「逐类行」与「汇总行」一起求和 ⇒ 那会算成 640）
# 📌 **核对日期 2026-11-03 02:00（合并 `main` 之后复跑）**：common 由 224 增至 **234**
#    （共享层 `6a3acb4` 带进来的 10 例新测试）⇒ 合计由 310 增至 **320**；**本域自身恒为 40 例**。
# ⇒ repackage 成功 ⇒ 可执行 jar 的 Start-Class = com.rag.user.UserServiceApplication
#
# ⚠️ **不要用 `-T1C` 跑全仓**：其余 8 个域的 Application 还是空壳（没有 `main`）
#    ⇒ `spring-boot-maven-plugin:repackage` 必然报 `Unable to find main class`
#    ⇒ **基线自带，非本轮引入**（见 DRIFT-USER-IMPL-009）。
# ⚠️ 另：`users-common` 的 Testcontainers 用例在 `-T1C` 下曾报
#    `Could not connect to Ryuk at localhost:<port>` ⇒ **并行构建时两个模块抢 Ryuk 端口**
#    ⇒ **串行跑即绿**（本轮实测：串行 users-common SUCCESS）⇒ 是**并行 flake**，不是代码缺陷。

# ── 2. 本域测试（只跑本域；依赖模块不重跑）──────────────────
mvn -o -pl service/users/user -am test -Dtest='com.rag.user.**' -Dsurefire.failIfNoSpecifiedTests=false
# ⇒ Tests run: 40, Failures: 0, Errors: 0, Skipped: 0   · BUILD SUCCESS
# ⚠️ `-DfailIfNoTests=false` **不是** surefire 3.x 的参数名 ⇒ 用错会让 `common` 模块直接 FAIL

# ── 3. 映射表守门（ROOT 改指向 worktree）────────────────────
sed 's|^ROOT = pathlib.Path("/Users/pr/Documents/GitHub/RAG-V1")|ROOT = pathlib.Path("/Users/pr/Documents/GitHub/RAG-V1.worktrees/users-user")|' \
  tools/check-mapping-coverage.py > /tmp/check-mapping-usersuser.py
python3 /tmp/check-mapping-usersuser.py
# ⇒ users 111/111 100% · B 145/145 100% · 合计 637/637 · 752/752
# ⇒ _INDEX.md 自报数对账 ✅ · 小节自报条数 ✅ · 主表行形状 ✅
# ⇒ B 列双向对账 ✅（精确 664 · 通配 3 · 目录 64）· B↔C 指代消解 ✅
# ⇒ EXIT=0
# 📌 **核对日期 2026-11-03（合并 `main` 之后复跑）**：users 域 C 列由 109 增至 **111**
#    （共享层合并带进来的影响已计入 `common` 域 ⇒ 合计由 629 增至 **637**）⇒ **本域自身 111/111 100%**。

# ── 4. 文件树重生成（ROOT 改指向 worktree；只保留本域）────────
sed 's|^ROOT = pathlib.Path("/Users/pr/Documents/GitHub/RAG-V1")|ROOT = pathlib.Path("/Users/pr/Documents/GitHub/RAG-V1.worktrees/users-user")|' \
  tools/gen-file-tree.py > /tmp/gen-file-tree-usersuser.py
python3 /tmp/gen-file-tree-usersuser.py
git checkout -- docs/File_Tree/proto.md     # ⚠️ proto/ 不是本域写权面（见 DRIFT-USER-IMPL-006）
# ⇒ 只留 docs/File_Tree/users/user.md 的变更（+65 −50）
```

### 🔴 **全仓 `clean verify` 的同条件对照（本轮实测 ⇒ 证明 9 个域的红是基线自带，且本域由红转绿）**

> ⚠️ 默认的 `mvn -o clean verify` 是 **fail-fast** ⇒ 第一个域（`article-service`）挂了就 **SKIPPED 后面全部**
> ⇒ ⇒ **看不出失败面** ⇒ ⇒ 必须加 **`-fae`**（fail-at-end）才能拿到**完整**的失败模块集合。

```bash
# ① 当前分支（-fae）
cd /Users/pr/Documents/GitHub/RAG-V1.worktrees/users-user && mvn -o -fae clean verify
# ② 基线纯净副本（-fae）
mkdir -p /tmp/ragv1-base && git archive 07ff14a | tar -x -C /tmp/ragv1-base
cd /tmp/ragv1-base && mvn -o -fae clean verify
```

| 模块 | 基线 `07ff14a` | 当前分支 `align/users-user` | 差异 |
|---|---|---|---|
| `article-service` | FAILURE | FAILURE | 无（同一根因） |
| `comment-service` | FAILURE | FAILURE | 无 |
| `favorite-service` | FAILURE | FAILURE | 无 |
| `follow-service` | FAILURE | FAILURE | 无 |
| `hot-service` | FAILURE | FAILURE | 无 |
| `like-service` | FAILURE | FAILURE | 无 |
| `message-service` | FAILURE | FAILURE | 无 |
| `task-service` | FAILURE | FAILURE | 无 |
| `admin-service` | FAILURE | FAILURE | 无 |
| **`user-service`** | **FAILURE** | **SUCCESS** ✅ | 🔴 **本 lane 由红转绿** |
| `common` / `proto` / `users` / `users-common` | SUCCESS | SUCCESS | 无 |

**根因（代码级取证，不是从报错反推）** —— `spring-boot-maven-plugin:repackage` 报
`Unable to find main class`，是因为**各域的 Application 类没有 `main`**：

```bash
for f in $(find service -name '*Application.java'); do
  grep -q "static void main" "$f" && echo "✅ $f" || echo "🔴 $f"
done
```

| 树 | 缺 `main` 的 Application |
|---|---|
| 基线 `07ff14a` | **10 个全缺**（含 `user-service`） |
| 当前分支 | **9 个缺**（`user-service` 已补上 `main`） |

⇒ ⇒ **失败面 10 → 9，差额恰好是本域自己** ⇒ ⇒ **其余 9 个域的红是基线自带（DRIFT-USER-IMPL-009），不是本轮引入**。

### 🔴 **变异测试：证明「回滚」判据不是假绿**（AGENTS.md §3.3.3「变异测试是另一半」）

> ⚠️ 只跑本仓探针只能证「代码写成了这样」，**证不了「期望值对」** ⇒ ⇒ 必须做一次**变异**：
> 把被测机制**摘掉**，看判据会不会**真的变红**。

```bash
# 变异：摘掉 mapper 接口 default 方法上的两处 @Transactional
sed -i '' '/^[[:space:]]*@org\.springframework\.transaction\.annotation\.Transactional[[:space:]]*$/d' \
  service/users/user/src/main/java/com/rag/user/mapper/UserAvatarHistoryMapper.java
mvn -o -pl service/users/user -am test -Dtest='AvatarTransactionWiringTest' -Dsurefire.failIfNoSpecifiedTests=false
# ⇒ Tests run: 5, Failures: 2, Errors: 0   ← 判据真的红了 ✅
#   ① createAvatarHistoryRollsBackTheEarlierWriteOnMidwayFailure
#      「W2 的 clearCurrentByUid 必须随事务回滚 ⇒ 旧 history 仍是 current」
#      Expecting value to be true but was false   ← 摘掉事务 ⇒ W2 落了库 ⇒ 无回滚
#   ② mapperBeanIsWrappedByTransactionAdvisor
#      「mapper bean 必须被 Spring AOP 包住」Expecting value to be true but was false
# 还原：git checkout -- service/users/user/src/main/java/com/rag/user/mapper/UserAvatarHistoryMapper.java
# ⇒ 还原校验：注解数 2 · `git diff` 空 · 复跑 39 例全绿
```

⇒ ⇒ **「事务真的回滚了」这条结论有变异证据支撑**（不是「跑绿了就当真」）。

### 🔴 **守门脚本的「硬编码 ROOT」陷阱（本轮实测，务必转给其他 lane）**

| 脚本 | `ROOT` 怎么来的 | 在 worktree 里跑的后果 |
|---|---|---|
| `tools/gen-file-tree.py` | **硬编码** `/Users/pr/Documents/GitHub/RAG-V1` | **量的是主工作区** ⇒ 生成物与本树无关（且会改主工作区的文件！）⇒ 必须改指向 worktree |
| `tools/check-mapping-coverage.py` | **硬编码** 同上 | 同上 ⇒ 读数是**别人的成绩** ⇒ 必须改指向 worktree |
| `tools/sync-mapping-index.py` | `__file__` 相对（**看着是对的**） | ⚠️ **但它 import 了 `check-mapping-coverage`** ⇒ 那个模块里的 `ROOT` 是硬编码 ⇒ ⇒ **它量的是主工作区** ⇒ ⇒ 直接 `--apply` 会**把别人域的数字写回来** ⇒ ⇒ 必须自己复算 + 手改本域那一行 |

### ⚠️ **同条件对照**（本轮实测，防「假绿灯」）

| 对照 | 读数 | 结论 |
|---|---|---|
| 映射表守门 · **基线纯净副本**（`git archive 07ff14a` → `/tmp/ragv1-base`） | users C 列 **106/106**，`_INDEX.md` 自报**一致** ⇒ **EXIT=0** | 基线是绿的 |
| 映射表守门 · **本工作树**（改动后） | users C 列 **109/109**（+3 = 新增 4 − 删除 1），`_INDEX.md` 自报仍 106 ⇒ **EXIT=1** | ⇒ **这处漂移是本轮引入的** ⇒ ⇒ 已手改 `_INDEX.md` 的 users 行与合计行 ⇒ 复跑 **EXIT=0** |
| `gen-file-tree.py` 在工作树内跑 | 产出 `proto.md` 与 `users/user.md` 两个变更 | ⇒ `proto.md` 的变更是**基线陈旧**（非本轮引入）⇒ ⇒ 已复原，只留本域 |

⇒ **教训**：`tools/sync-mapping-index.py` **看起来**是 `__file__` 相对（`ROOT = parent.parent`），
但它 **import 了 `check-mapping-coverage`**，而那个模块里的 `ROOT` 是**硬编码绝对路径**
⇒ ⇒ **它量的是主工作区** ⇒ ⇒ **直接 `--apply` 会把别人域的数字写回** ⇒ ⇒ 本轮**改为自己复算 + 手改 users 行**。

---

## 11.8 审核结果状态（按 `.agents/verification.md` §十 的状态词表）

> ⚠️ **本报告的证据边界（必须先读）**：
> 本轮**做完了「C 侧运行验证」**（**40 例**真触发测试，见 §11.6.3）与**「B 侧源码逐条对照」**（58/58 文件全读）；
> ⇒ 但**没有做「双侧活体对拍」**（同一输入同时打 B 与 C，逐字节比对响应）⇒ ⇒
> ⇒ 下表的 `PASS` 含义是「**C 侧运行证据 + B 侧源码逐条对照一致**」，
> **不等于**「双侧活体对拍已闭合」。
> ⇒ 需要双侧对拍才能定论的行为项，一律标 **`NOT_VERIFIED`**（**不计入 PASS**）。

| 审核项 | 状态 | 依据（`文件:行号` ＋ 读数） |
|---|---|---|
| 文件级承接（48/48 有实现 · 零空壳） | **PASS** | §11.2 ＋ `find service/users/user/src/main/java -name '*.java' \| wc -l` = **48** |
| L1 路由前缀 + 9 个端点全 POST | **PASS** | `controller/UserController.java`（9 个 `@PostMapping`）＋ `UserApiHttpContractTest#getOnPostOnlyEndpointIs405`（**405 裸状态码**） |
| L1 public / protected 路径集合 | **PASS** | `interceptor/JwtAuthPaths.java:41-43` ＋ `UserApiHttpContractTest#publicLoginPathDoesNotRequireJwt`（无 Authorization 仍走到业务层，**拿到 1002 而不是 401**） |
| L1 响应信封 `{code,msg,data}` | **PASS** | `common/contract/ApiResponse.java` ＋ `UserApiHttpContractTest#envelopeAlwaysHasThreeFields`（**三字段恒在，`data=null` 也在**） |
| L1 错误码 / 文案 | **PASS** | `contract/{ErrCodes,ErrMsg}.java` = **转发视图**（真源 `users-common`）⇒ **零副本** ⇒ 码值/文案**只有一份** |
| 鉴权失败 ⇒ HTTP 401（不是 200 + 业务码） | **PASS** | `advice/UserApiExceptionHandler.java:75-80` ＋ `UserApiHttpContractTest#protectedPathWithoutTokenIs401` |
| 400 的**结构**（text/plain + nosniff） | **PASS** | `PlainTextErrors.badRequest` ＋ `UserApiHttpContractTest#missingRequiredFieldIsPlainText400` |
| 400 的**文案** | **INTENTIONALLY_CHANGED**（L3 降级 D-01） | `docs/L3-降级清单.md` D-01 ＋ DEV-USER-002 |
| 🔴 JWT 验签在 **Filter** 里做（009② 闭环） | **PASS** | `interceptor/JwtAuthFilter.java:71-116`（**绝不写响应**）＋ `UserApiHttpContractTest#jwtVerificationHappensInFilter`（探针 filter 读 `verificationSource` ⇒ 必须 = `filter`） |
| Redis 黑名单 fail-open | **PASS**（C 侧单侧） | `interceptor/JwtAuthInterceptor.java:135-145`（异常吞成 `null` ⇒ 放行）＋ 测试环境 Redis **真连不上** ⇒ 走的**就是**真实 fail-open 分支 |
| gRPC 9 方法真分发 | **PASS** | `grpc/UserGrpcService.java` ＋ `UserGrpcServiceContractTest#allNineMethodsAreDispatched`（**真 in-process server** ＋ 真 proto 编解码） |
| gRPC `StatusRuntimeException` 原样透传 | **PASS** | 同上测试 `#statusRuntimeExceptionIsPassedThrough`（**code + description 都保留**） |
| gRPC 未映射异常 ⇒ INTERNAL | **PASS** | 同上测试 `#unmappedThrowableConvergesToInternal`（description = `"类名: 消息"`） |
| `@MapperScan` 真生效 | **PASS** | `UserServiceApplication.java` ＋ `UserServiceApplicationTests#mapperBeansAreRegistered`（**bean 真的在容器里**） |
| `@GrpcService` 真被 starter 扫到 | **PASS** | `UserServiceApplicationTests#grpcServiceBeanExists` |
| DDL 在启动期真被重放 | **PASS** | `wiring/UserSchemaBootstrap.java` ＋ `UserServiceApplicationTests#schemaWasAppliedAtStartup`（查**真 PG** 的 `information_schema`） |
| 表名 / 列类型 L1 | **PASS** | `users/user.md` §6 ＋ `UserServiceApplicationTests#tableNamesMatchB` |
| 指标名 + label（**只有 `result`**） | **PASS**（静态 + B 侧源码逐条对照） | `metrics/{UserApiMetrics,UserRpcMetrics}.java`（逐条照抄 B 侧） |
| 事务边界（4/5 步原子 · D-005 / D-015） | **PASS**（C 侧实测 · **含变异证据**） | `mapper/UserAvatarHistoryMapper.java:135,198`（`@Transactional` 挂在 mapper 接口的 **default** 方法上 —— 本仓最易静默失效的形态）＋ `AvatarTransactionWiringTest#createAvatarHistoryRollsBackTheEarlierWriteOnMidwayFailure`（**真 UNIQUE 约束冲突** ⇒ W2 的 UPDATE 被回滚 ⇒ 旧 history 仍是 current）＋ `#createAvatarHistoryCommitsAllFourSteps`（同序列成功路径 ⇒ 非空洞对照）＋ `#mapperBeanIsWrappedByTransactionAdvisor`（advisor 真在）＋ **变异证据**（摘掉 `@Transactional` ⇒ 判据变红，见 §11.7） |
| `updateById` vs `update(null, wrapper)` 的写路径形态 | **PASS**（逐语句对拍 · 2026-11-03 闭合） | ⚠️ **两侧领域服务的「可执行语句集」逐行 diff**（归一化空白 / 换行折叠 / 错误码常量名 `ErrorCode.X.getCode()`→`ErrCodes.X` / pb 包名后）⇒ **唯一差异 = 一处 `logger.logBusinessErr(...)` 的换行折叠** ⇒ 语义等价。**写路径 13 个调用点（顺序 / 参数 / 方法名）逐条一一对应**：`findOneByUserName`×3 · `insert`×1 · `findOneByUid`×5 · `updateUserById`×1 · `deleteUserByUid`×1 · `selectAvatarHistory`×1 · `createAvatarHistory`×1 · `listAvatarHistory`×1。且 **两侧都不是 `updateById`** ⇒ 都走 `update(null, wrapper)`（`mapper/UserMapper.java`）⇒ ⇒ **「形态差异」本就不存在**；两侧 `UserMapper.java` / `UserAvatarHistoryMapper.java` 归一化 diff 后**只差包名 + TypeHandler import 落点 + 大括号风格** |
| 端到端「register → login → get」 | **PASS**（C 侧单侧） | `UserApiHttpContractTest#registerLoginGetHappyPath`（**真 PG + 真 bcrypt + 真 JWT 签发 + 真 uid 字符串化**） |
| **双侧活体对拍**（同一输入打 B 与 C 比对） | **PASS**（**HTTP 20 例 + gRPC wire 18 例 + DB 最终态**） | ⚠️ **2026-11-03 实跑**（`tools/difftest/users-user/run.sh`，两侧真进程：真 Tomcat + 真 gRPC + 真 PG + 真 Redis + 真 Nacos）：HTTP **真差异 0/20**（探针 **EXIT=0**）· **字节级 12/20**（8 例差异 = 🔶 键序 4 ＋ 文案形态 4，两者**均已归因登记**）· **gRPC wire 真差异 0/18**（9 个方法全覆盖 + 错误路径 + 成功路径；**只屏蔽易变值、不屏蔽 presence**；**变异证据**：植入 presence 差异 ⇒ **只有 gRPC 维度变红**）· DB `users_user` 与 `users_user_avatar_history` **逐字段一致** · 表清单一致（4 张）· 详见 §十二 / §12.2.1 |
| C 侧 **fat jar 真能起得来**（生产类路径 ≠ 测试类路径） | **PASS**（本轮修） | 🔴 2026-11-03 对拍实测：`grpc-inprocess` 声明成 **test scope** ⇒ **生产 jar 缺类 ⇒ 启动即挂**（`NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder`）⇒ **39 例测试全绿却零告警**。已修（去 scope）＋ B 侧类路径取证 + 探针自检。详见 §十二.3 |
| 🔴 400 的「报哪个字段」**确定性** | **PASS**（本轮修） | 原实现 `getFieldErrors().findFirst()` ⇒ Hibernate Validator 违规集合是 `HashSet` ⇒ **同一输入 6 次出 3 种文案** ⇒ C 侧 400 **不是确定性输出**。已改为**按 DTO 声明序取第一条**（与 B 侧 `GoStyleRequired` 判定序逐个一致 ⇒ **报同一字段**）+ 新增断言连打 8 次逐次相同。详见 §十二.3 |
| 其余 9 域的 `grpc.client:` 空键 | **FAIL（跨域）** | DRIFT-USER-IMPL-004（那些域**一启动就挂**） |
| `admin.md:158` 的 C 列悬空指针 | **FAIL（跨域）** | DRIFT-USER-IMPL-005 |
| `docs/File_Tree/proto.md` 陈旧 | **FAIL（跨域）** | DRIFT-USER-IMPL-006 |
| `docs/File_Tree/users/user.md` 的 §五 状态块陈旧 | **FAIL（跨域 · 工具层）** | DRIFT-USER-IMPL-008（根因在 `tools/gen-file-tree.py` 的写死占位） |
| 🔴 **其余 7 个域的 fat jar 起不来**（`grpc-inprocess` 是 test scope） | **FAIL（跨域）** | DRIFT-USER-PARITY-002：`article`/`comment`/`favorite`/`hot`/`like`/`message`/`task` 七个域的 pom 把 `grpc-inprocess` 声明成 `<scope>test</scope>` ⇒ 生产 jar 缺类 ⇒ devh `GrpcClientAutoConfiguration:160` 抛 `NoClassDefFoundError` ⇒ **启动即挂**。⚠️ 当前被「Application 无 `main`」挡在前面（先挂 repackage）⇒ **各域补 `main` 的那一刻才会暴露** ⇒ 建议**先修这一条** |
| 🔴 **「DB 完全不可响应」时的 HTTP 超时出口形态**（B `200+1015@2.01s` ↔ C `503@3.00s`） | **INTENTIONALLY_CHANGED**（签字偏离 · 待签） | DRIFT-USER-PARITY-005 ⇒ **DEV-USER-010**（与 DEV-USER-008 同族：D1/D2 进程合并 ⇒ B 侧 api 层那一跳的「超时出口」不可达）⇒ 共享层预算机制本身**两侧对称**（§12.2.2 阶段C：两侧服务端预算都 ~2.1s） |
| 🔴 **客户端取消后，服务端在途写是否回滚**（B=0 行 ↔ C=1 行） | **FAIL（跨域 · 共享层）** | DRIFT-USER-PARITY-006：**两个判别性变体同结果**（cancel-after=1s 与 5s）⇒ 与「客户端取消」无关 ⇒ 归因＝**服务端 2s 预算到点后有没有把在途 SQL/事务取消掉** ⇒ 两侧共享层本就是两套实现（B＝`RpcInterceptor` 主动关 call · C＝`DeadlineState` 检查点式）⇒ **本 lane 无写权面** |
| **场景轮**（超时 / deadline / 取消传播 / 恢复 / 外部依赖失败 / 恢复 / 并发） | **PASS**（探针摘要：❌ 真差异 3 · ⚠️ 已知现象 2 ⇒ **2 个根因，均已归因**） | §12.2.2：**8 阶段** ⇒ 机器摘要「❌ 真差异 **3** ｜ ⚠️ 对称异常/已知现象 **2**」⇒ **3 条真差异归为 2 个根因**（第 3 条 = 第 2 条的**判别性变体**）· **两侧对称判据全部通过**：gRPC deadline status · 取消 status · 恢复轮**连续 3 轮**回 200 ×2 · **PG 停两侧同为 `1015`**（独立交叉验证 admin lane）· 并发 register 响应集合同为 `{200/200, 200/5001}` + 恰好 1 行 |
| 🔴 **JSON 响应键序**（Jackson 2 vs 3 的默认行为） | **FAIL（跨域 · 共享层）** | DRIFT-USER-PARITY-003：B（Boot 4 / Jackson 3）的 mapper 默认开 `SORT_PROPERTIES_ALPHABETICALLY` ⇒ 输出**字母序**；C（Boot 3.5 / Jackson 2.19.4）默认关 ⇒ 输出**声明序** ⇒ **10 个域全受影响** ⇒ 落点在共享层 `common/` 的 ObjectMapper 定制 ⇒ **本 lane 无写权面** |

### §十四 的 12 个问题 ＋ 最终审核标准（逐条回答）

| # | 问题 | 回答 |
|---|---|---|
| 1 | C 侧文件是否全部有实现？ | ✅ **48/48**，**零空壳** |
| 2 | 每个 C 侧文件的职责是否有据？ | ✅ 逐条列在 §11.2；**C 新增 = 0** |
| 3 | 是否逐个读过 B 侧源码？ | ✅ **58/58 主代码文件**（6264 行）全读 |
| 4 | 路由/method 是否与 B 一致？ | ✅ 9 个端点**全 POST**；前缀 `/user/v1`；运行证据见 HTTP 契约测试 |
| 5 | 错误码/文案是否逐条一致？ | ✅ 取自 `users-common` 真源（**转发视图**，零副本） |
| 6 | 响应信封形状是否一致？ | ✅ `{code,msg,data}` 三字段恒在（`data=null` 也在） |
| 7 | JWT 鉴权是否与 B 一致？ | ✅ public 集合、protected 前缀、claim 名 `userId`、黑名单 key、fail-open **逐条一致** |
| 8 | 表结构/实体是否一致？ | ✅ `users_user` / `users_user_avatar_history`；`extra_info` = text；`timestamptz` |
| 9 | 事务边界是否一致？ | ✅ **实现同形 + 已实测**：`@Transactional` 挂在 mapper 的 default 方法上 ⇒ **真 UNIQUE 约束冲突**下 W2 的 UPDATE 确实被回滚（+ 变异证据）⇒ **PASS** |
| 10 | 指标名/label 是否一致？ | ✅ `user_api_intercept_total{path,result}` · `user_rpc_login_total{result}` · `user_rpc_register_total{result}`（**只有 `result`**） |
| 11 | 是否有「无出处的 idiomatization」？ | ✅ **零** —— 每个改写点都带 **B 出处 ＋ 语义等价论证**（javadoc 三段式） |
| 12 | 框架机制是否有真触发测试？ | ✅ **40 例**、`skipped=0`；**真 Tomcat** ＋ **真 gRPC** ＋ **真 PostgreSQL**（Testcontainers `postgres:16-alpine`） |

### 最终结论（**按 §十 的状态词表**）

> **本域实现层：PASS**（文件级承接 48/48、零空壳、C 侧运行证据 40 例全绿 `skipped=0`、映射表守门 EXIT=0）。
>
> ⚠️ **但「行为与 B 侧完全等价」这一命题本身是 `NOT_VERIFIED`** —— **表内**能自闭合的四项
> （事务边界实测 · **双侧活体对拍**〔HTTP 20 例 + **gRPC wire 18 例** + DB 两张表〕· **写路径形态逐语句对拍** ·
> **场景轮**〔并发 / 超时 / 外部依赖失败 / 恢复 ×2 / **gRPC deadline** / **取消传播**〕）
> **已于 2026-11-03 全部闭合**（每项都带变异证据或**判别性变体**）。
> ⇒ ⇒ **剩下的缺口全在「表外」**：**B 侧 Go 真源第三方锚不可得**（本机无 `Sea-etcd`）·
> **场景轮暴露的 2 条已归因差异**（PARITY-005 ⇒ 签字偏离 **DEV-USER-010** · **PARITY-006** ⇒ 落点在**共享层** ⇒ 本 lane 无写权面）
> · **gRPC deadline 的 description 文案形态**不同（客户端可见 ⇒ 落点共享层 gRPC 拦截器）（§12.5）。
> ⇒ ⇒ **表内 `NOT_VERIFIED` 归零 ≠ 元命题闭合**（见 P-USER-14）。
>
> ⚠️ **另有 7 条跨域 `FAIL`**（DRIFT-USER-IMPL-004/005/006/008 ＋ **PARITY-002**〔7 域同型 jar 缺陷〕＋ **PARITY-003**〔JSON 键序 · 共享层〕＋ **PARITY-006**〔取消后在途写回滚 · 共享层〕）⇒ 归属收口域与各域 lane ⇒
> **不在本 lane 的写权面**，已逐条登记。

### 审核状态汇总

| 状态 | 项数 |
|---|---|
| **PASS** | **24** |
| **INTENTIONALLY_CHANGED**（L3 降级 D-01 ＋ **DEV-USER-010**） | **2** |
| **NOT_VERIFIED** | **0** |
| **FAIL（跨域 · 不在本 lane 写权面）** | **7** |
| **合计** | **33** |

> 📌 **口径**：本表 **33 行**逐行点过（**脚本按行复算**）⇒ 24 + 2 + 0 + 7 = **33**。
> （**初稿此处写的是 17，自身少算了 1 ⇒ 2026-11-02 复核时按行重数并订正；**
> **2026-11-03 对拍后订正为 22/1/2/6；**
> **2026-11-03 第二轮（gRPC wire 维度 + 写路径形态闭合）后订正为 23/1/0/6；**
> **2026-11-03 第三轮（场景轮）后订正为 24/2/0/7。**）
> ⚠️ **这四个数字是脚本按行复算出来的，不是手数的** —— 手数第一次写成了 24 ⇒ **被机器复算当场纠正**
> （同款教训见 [`P-08`](../../Pitfalls/_cross/P-08-落地证据未经机器复算.md)）。
>
> ⚠️ **「行为与 B 侧完全等价」这一命题本身**（§11.8 表**上方**那段结论文字）
> **仍标 `NOT_VERIFIED`** —— 它是**元命题**，不是表里的一行 ⇒
> ⇒ **不再有「表内 NOT_VERIFIED 项」**（已全部闭合或移交）⇒
> ⇒ 但**元命题**仍未闭合，原因是**表外**的三类：
> ① **B 侧 Go 真源第三方锚不可得**（本机无 `Sea-etcd`）⇒ 探针只能**双侧**（C ↔ B）；
> ② **场景轮暴露的 2 条已归因差异**（PARITY-005 签字偏离 · **PARITY-006 落点在共享层**）⇒ **本 lane 无写权面**；
> ③ **gRPC deadline 的 description 文案形态**不同（客户端可见 ⇒ 共享层 gRPC 拦截器）（§12.5）。
>
> 🔴 **不要把「表内 0 个 NOT_VERIFIED」读成「命题已闭合」** —— 那正是把**表**当**命题**。

---

## 11.9 本轮踩坑与元教训（⇒ 已登记进 [`docs/Pitfalls/users/_user-domain-pitfalls.md`](../../Pitfalls/users/_user-domain-pitfalls.md)）

> 📌 **`docs/Pitfalls/_INDEX.md` 不归本 lane**（AGENTS.md §五.2：三个 `_INDEX.md` 合并后统一更新）⇒ 需收口域合并。
> ⚠️ **通用方法论不在本表重复**：变异测试方法论见 [`P-12`](../../Pitfalls/_cross/P-12-变异没变红等于那条断言没覆盖到.md) ·
> 落地证据须机器复算见 [`P-08`](../../Pitfalls/_cross/P-08-落地证据未经机器复算.md)。

| # | 形态 | 判据（可复用） |
|---|---|---|
| **P-USER-01** | **「空键 YAML」是全仓潜伏的启动雷**：10 个域的 `application.yml` 都写 `grpc.client:` + 注释 ⇒ YAML 解析成**空串** ⇒ relaxed binding 拿 `String` 转 `Map` ⇒ `ConverterNotFoundException` ⇒ **上下文起不来** | ① YAML 的「只有注释的键」**不是 null，是空串**；② relaxed binding 对 `Map` 字段要求**真 map**（`{}`）⇒ ⇒ 空键要么删、要么写 `{}`；③ **第一个真启动的域会替全仓踩到它** ⇒ ⇒ 踩到时**要顺手登记成跨域项**，而不是只修自己那份 |
| **P-USER-02** | **gRPC in-process server 的名字是 JVM 全局的** ⇒ 多个 Spring 上下文用同名 ⇒ `IOException: name already registered` ⇒ **后起的上下文全挂** | ⇒ 需要真 gRPC 的测试类**各自指定唯一名字**；其余上下文用 `grpc.server.port=-1` **不起 server** ⇒ ⇒ 「一个测试类起 in-process」是安全的，「多个测试类共享一份配置起 in-process」不安全 |
| **P-USER-03** | **`-pl <module>` 单模块跑测会解析不到兄弟模块** ⇒ `Could not find artifact com.rag:proto` | ⇒ 本仓的模块依赖**只在 reactor 里解析** ⇒ ⇒ 必须 `-am`；要「只跑本域的测试」就配 `-Dtest=<pattern> -Dsurefire.failIfNoSpecifiedTests=false`（⚠️ `-DfailIfNoTests=false` **不是** surefire 3.x 的参数名 ⇒ 用错会让 `common` 模块直接 FAIL） |
| **P-USER-04** | **「测试写错了」和「实现写错了」长得一样**：本轮 3 次红里，2 次是**我的期望值按 Go 侧直觉写**（未知路径该 404），而 B 侧 Java 的真实行为是 500 / 401 | ⇒ 红了先问「**B 侧 Java 真的会那样吗**」，再问「实现错在哪」⇒ ⇒ **读 B 侧同形代码**（`WebMvcConfig` 的前缀注册 / `handleAny` 的兜底）比推理快；⇒ 顺带把「B 侧 Go 与 B 侧 Java 自身不一致」记成**继承项**，别当 C 侧漂移去「修」 |
| **P-USER-05** | **守门脚本的 `ROOT` 有三种形态，只有一种是对的**：`gen-file-tree.py` 与 `check-mapping-coverage.py` = **硬编码主工作区**；`sync-mapping-index.py` = `__file__` 相对（**看着是对的**）⇒ **但它 import 的那个模块里是硬编码** ⇒ ⇒ 它**间接**量的是主工作区 | ⇒ **在 worktree 里跑任何仓内脚本前，先确认它看的是哪棵树**：<br/>① `grep -n "ROOT" tools/<x>.py` ⇒ 直接硬编码 ⇒ 改指向 worktree（**用 `/tmp` 下的副本，不改仓内脚本**）；<br/>② `__file__` 相对 ⇒ **再查它 import 的模块里有没有硬编码**（本轮就踩了这个 ⇒ 「看着对」的也会中招）；<br/>③ ⇒ **绿灯也要做同条件对照**（本轮：基线纯净副本 users C 列 **106/106 且自报一致 ⇒ EXIT=0**；改动后 **109/109 但自报仍 106 ⇒ EXIT=1** ⇒ ⇒ **证明这处漂移是本轮引入的**，不是基线自带的） |
| **P-USER-06** | **「事务注解挂在 MyBatis-Plus mapper 接口的 `default` 方法上」是本仓最易静默失效的形态**：mapper 是 MyBatis 的 JDK 代理 ⇒ `@Transactional` 能不能生效取决于 ① auto-proxy 是否包住 `FactoryBean` 产出的代理 ② advisor 是否认得 default 方法上的注解 ③ `SqlSessionTemplate` 是否参与进已开事务 —— **三者任一不成立，注解就是装饰品，且零报错** | ⇒ **判据 = 真 DB 约束冲突**（拿 `UNIQUE(history_id)` 让 W3 真失败）⇒ 断言 **W2 的 UPDATE 不留痕**；<br/>⇒ **必须配「同序列成功路径」作非空洞对照**（否则「回滚了」可能只是「压根没写」）；<br/>⇒ **必须做变异测试**（摘掉注解 ⇒ 判据变红）⇒ 否则「跑绿」证不了「期望值对」；<br/>⇒ ⚠️ **Mockito 桩掉 mapper 方法不算数** —— 桩了就连真实 SQL 都不发 ⇒ 证不了回滚 |
| **P-USER-07** | **默认 `mvn clean verify` 是 fail-fast ⇒ 失败面被截断在第一个模块** ⇒ 拿它做「同条件对照」会得出**错误的结论**（看起来只有 1 个域红，实际有 9 个红） | ⇒ ⇒ **做跨树对照必须加 `-fae`（fail-at-end）** 才能拿到**完整**失败模块集合；<br/>⇒ ⇒ 失败模块集合相同 ⇒ 才是「基线自带」的证据；**集合差一条** ⇒ 那一条正是本轮的贡献（本次：10 → 9，差额 = 本域）；<br/>⇒ ⇒ 根因要**代码级取证**（本轮：逐个 `Application` 查 `static void main`），不能只从报错反推 |

> 📌 **P-USER-08 ~ P-USER-14**（对拍轮追加，**全文见坑台账**）：
> **08** 测试类路径 ≠ 生产 jar 类路径（`mvn test` 全绿证不了 fat jar 起得来）·
> **09** Bean Validation 的违规集合是 `HashSet` ⇒ `findFirst()` 不确定 ·
> **10** Python 打 HTTP 会把 `1xx` 当最终响应（`urllib` 只跳 100 ⇒ 改用 `curl`）·
> **11** bash 的 `$!` 是子 shell 的 PID ⇒ cleanup 杀不到 java（⇒ 子 shell 里用 `exec`）·
> **12** 拿**屏蔽后的规范化文本**去断言**原始值** ⇒ 恒 false ⇒ **两侧同时 FAIL ≠ 行为差异** ·
> **13** 用「有 schema 的反序列化」判 wire 等价 ⇒ **契约漂移被解码器吃掉** ⇒ 假绿 ·
> **14** 「表内 `NOT_VERIFIED` 归零」**≠**「元命题闭合」⇒ 别把**表**当**命题**。
>
> 📌 **P-USER-15 ~ P-USER-18**（**场景轮追加** —— 这 4 条**全是探针自身的缺陷**，不是被测对象的）：
> **15** 探针的调度线程**不是 daemon** ⇒ JVM 不退出 ⇒ 脚本挂在「明明跑完了」的位置 ·
> **16** 「jar 存在 ⇒ 跳过构建」⇒ **量到上一次的代码** ⇒ **读数是假的**
> （⚠️ 本轮**差点**把共享层修复前的 503 登记成真差异）·
> **17** 「端口已监听」**≠**「服务已注册」⇒ 启动顺序错了 ⇒ 下游 `Can't connect to channel user-rpc` ·
> **18** 任务被**外部取消** ⇒ `trap` 不执行 ⇒ **端口残留** ⇒ 下一轮直接「端口被占用」退出。

---

## 11.10 一句话结论（本 lane 汇报口径）

**user 子域 48 个主代码文件已全部落地并编译通过，40 例真触发测试（真 Tomcat + 真 gRPC + 真 PostgreSQL）全绿、`skipped=0`，映射表守门 EXIT=0（核对日期 2026-11-03：users 全域 C 列 111/111、B 列 145/145；合计 C 637/637 · B 752/752）；**
**全仓 `mvn -o -fae clean verify` 的同条件对照：失败面由基线的 10 个域收敛到 9 个域，差额恰好是本域（基线 10 个 Application 全缺 `main` ⇒ `repackage` 必挂 ⇒ 基线自带，非本轮引入）；**
**事务边界（D-005 / D-015）已由 `NOT_VERIFIED` 升为 `PASS` —— 用真 UNIQUE 约束冲突证明 W2 的 UPDATE 确实被回滚，并用变异测试证明该判据非空洞；**
**2026-11-03 已完成**双侧活体对拍（**HTTP 20 例 + gRPC wire 18 例 + DB 最终态**）：HTTP 结构级 16/20 一致（4 例差异全是**已登记**的 400 文案形态）、字节级 12/20、**gRPC wire 真差异 0/18**（9 个方法全覆盖；**只屏蔽易变值、不屏蔽 presence**）、DB 两张表最终态逐字段一致；**
**⇒ 写路径形态（`updateById` vs `update(null, wrapper)`）已由 `NOT_VERIFIED` 升为 `PASS` —— 两侧领域服务的可执行语句集逐行 diff（唯一差异 = 一处日志调用的换行折叠），13 个写路径调用点逐条一一对应，且**两侧都不是 `updateById`**；**
**⇒ 场景轮（并发 / 超时 / 外部依赖失败 / 恢复 ×2 / gRPC deadline / 取消传播）已做**（**8 阶段**；探针机器摘要「❌ 真差异 **3** ｜ ⚠️ 已知现象 **2**」⇒ **3 条真差异归为 2 个根因**）：**两侧对称判据全部通过** —— 并发 register 两侧响应集合同为 `{200/200, 200/5001}` 且各恰好 1 行 · **PG 停两侧同为 `1015`**（独立交叉验证 admin lane）· 恢复轮两侧**连续 3 轮**回 200 · gRPC deadline / 取消的 **status 两侧一致**（`DEADLINE_EXCEEDED` / `CANCELLED`，服务端预算对称 ~2.1s）；**2 个根因** = ① DB 完全不可响应档的 HTTP 出口形态 ⇒ 签字偏离 **DEV-USER-010**；② 取消后在途写是否回滚 ⇒ **落点在共享层** ⇒ 本 lane 无写权面；**
**⇒ 但「行为与 B 侧完全等价」这一命题本身仍是 `NOT_VERIFIED`** —— 剩下的缺口都在**表外**：**B 侧 Go 真源第三方锚不可得**（本机无 `Sea-etcd`）· **场景轮暴露的 2 条已归因差异**（PARITY-005 ⇒ 签字偏离 **DEV-USER-010**；**PARITY-006** ⇒ 落点在**共享层** ⇒ 本 lane 无写权面）· **gRPC deadline 的 description 文案形态**不同（客户端可见 ⇒ 共享层 gRPC 拦截器）；**
**另有 7 条跨域 FAIL：DRIFT-USER-IMPL-004/005/006/008（收口域与 admin lane）＋ **DRIFT-USER-PARITY-002（其余 7 域 fat jar 起不来）＋ DRIFT-USER-PARITY-003（JSON 键序 · 共享层）＋ DRIFT-USER-PARITY-006（取消后在途写回滚 · 共享层）** —— 本 lane 不越权代改，已逐条登记。**

---

# 十二、E-5 双侧活体对拍（2026-11-03）

> **目的**：把 §十一 里唯一还能在本 lane 内自闭合的 `NOT_VERIFIED`（**双侧活体对拍**）闭合。
> **探针**：[`tools/difftest/users-user/run.sh`](../../../tools/difftest/users-user/run.sh)（设计见其 [README](../../../tools/difftest/users-user/README.md)）
> **⚠️ 第二轮（2026-11-03 同日）把维度从「HTTP + DB」扩到「HTTP + gRPC wire + DB」** ⇒ §12.2.1。
> **⚠️ 第三轮（2026-11-03 同日）把维度从「静态矩阵」扩到「**故障注入场景轮**」** ⇒ §12.2.2
> （并发 / 超时 / 外部依赖失败 / 恢复 ×2 / **gRPC deadline** / **取消传播** ⇒ **8 阶段**）。
> **口径**：**B 侧**（RAG / Boot 4.0.8 · 双进程 `user-api` + `user-rpc`）↔ **C 侧**（RAG-V1 / Boot 3.5.11 · D1 合并单进程）
> ⇒ **两侧同时起真进程**（真 Tomcat + 真 gRPC + 真 PG + 真 Redis + 真 Nacos）+ **同输入** + **同字节**比对。

## 12.1 对拍环境（🔴 三个隔离点，踩一个就全错）

| 隔离点 | 做法 | 不隔离会怎样 |
|---|---|---|
| **Nacos 分组** | B 侧 `E4UB` · C 侧 `E4UC` | 两侧注册的 gRPC 服务**同名 `user-rpc`** ⇒ B 侧 user-api 的 `discovery:///user-rpc` **可能拨到 C 侧** ⇒ **对拍对象就错了** |
| **数据库** | 两侧各一个独立 PG 容器（`e4user-{b,c}-pg-$$`） | 共库 ⇒ 写入互相污染 ⇒ 「DB 最终态一致」变成同义反复 |
| **端口段** | 专用 `18301-18309` | 抢 Sea 栈 / 别的 lane 的端口 |

> ⚠️ **只走宿主发布口**（Nacos `49848` · Redis `49380`）：Docker Desktop(macOS) 的宿主**不可达容器 IP**
> ⇒ B 侧 user-rpc 注册的是宿主机 LAN IP ⇒ 宿主机上的 user-api 可达 ✔（实测通）。

## 12.2 结果（HTTP 20 例 + **gRPC wire 18 例** + DB 最终态）

| 口径 | 结果 |
|---|---|
| **真差异**（归一化 uid/history_id/JWT/时间戳/哈希 ＋ **JSON 键序** ＋ **文案形态** 后仍不一致） | **0 / 20** ⇒ 探针 **EXIT=0** |
| **字节级**（只归一化 ID/JWT/时间戳/哈希，**键序与文案参与比较**） | **12 / 20 一致**；8 例差异 = **🔶 键序 4**（DRIFT-USER-PARITY-003）＋ **文案形态 4**（已登记 D-01） |
| **gRPC wire 真差异**（**9 个方法全覆盖** ＋ 错误路径 ＋ 成功路径，**只屏蔽易变值、不屏蔽 presence**） | **0 / 18** ⇒ 探针 **EXIT=0**；7 条**同侧自洽断言**全绿 ⇒ 详见 §12.2.1 |
| **DB 最终态** | ✅ `users_user` **逐字段一致**（username/status/score/extra_info/密码存在性/email）· ✅ `users_user_avatar_history` **一致**（2 行 / 1 个 current / 内容序列一致）· ✅ **表清单一致**（4 张） |
| **B 侧只读** | ✅ 收尾断言 B 仓 `service/` 干净 |

> 📌 **口径**：探针把差异分**三档**并**分别归因** ⇒ **「已登记的偏离」与「未闭合的漂移」不混进同一个失败桶**：
> ① 文案形态（**已登记 D-01**）⇒ 判据放宽为「状态码 + content-type + **被点名的字段**」⇒ **不计入失败**；
> ② 仅键序不同（**DRIFT-USER-PARITY-003 · 共享层待收口**）⇒ 🔶 单列计数、显眼报出、**不计入失败**；
> ③ 其余 ⇒ ❌ **真差异** ⇒ 计入失败 ⇒ **探针退出码非 0**。

**逐例一致的部分（12 例，字节级）** —— 路由 · 方法 · 信封形状 · 业务码 · 401 面 · 黑名单 · 未知路径两态 · 405 裸状态码：

| 用例 | HTTP | 业务码 |
|---|---|---|
| register 正常 / 重名 | 200 / 200 | 200 / **1001** |
| login 用户不存在 / 密码错 | 200 / 200 | **1002** / **1002** |
| get 无 token / 坏签名 | **401** / **401** | **1006** / **1006** |
| get 合法 token（不存在 uid / 存在 uid） | 200 / 200 | 200 |
| avatar/history 合法 token | 200 | 200 |
| 未知路径 `/user/v1` **内** | **401** | **1006** |
| 未知路径 `/user/v1` **外** | **500** | **5001** |
| **GET 打 POST 端点** | **405** | —（**裸状态码**） |
| logout / logout 后再用 token | 200 / **401** | 200 / **1006** |

## 12.2.1 🔴 gRPC wire 维度（**9 个方法全覆盖** · 18 例 · 真差异 0）

> **本节把「双侧活体对拍」从「HTTP + DB 两维」扩到「HTTP + gRPC wire + DB 三维」。**
> 探针：`tools/difftest/users-user/grpc-wire/UserWireProbe.java`（**新增**）＋
> `tools/difftest/users-user/wire_compare.py`（**新增**）⇒ 由 `tools/difftest/users-user/run.sh` 调用。

### 探针形态（**为什么这么写** —— 三个「不用 XX」）

| 不用 | 原因 |
|---|---|
| **两侧各自的 pb 生成类** | 两侧 pb 包名**不同**（B = `com.rag.users.user.proto` · C = `com.rag.proto.user`）⇒ 用各自生成类构造/解析 ⇒ 两侧跑的**不是同一段代码** ⇒ 「字节一致」的证据被「两侧实现不同」污染 ⇒ ⇒ 本探针**只用原始字节**：请求按 proto3 wire format **手工构造**，响应用 `byte[]` marshaller **原样取出** ⇒ ⇒ **同一个 class 打两侧** |
| **「反序列化成对象再比」** | 那比的是**对象语义**、不是 wire：若两侧 proto 字段号不一致，解析器会把对方的多余字段当 unknown **丢掉** ⇒ **契约漂移被解码器吃掉** ⇒ 假绿 ⇒ ⇒ 本探针用 **schema 驱动的自研解码器**：按字段号逐个吐 `f<号>=<值>`，**未知字段照样打印**（`f=unknown(...)`）⇒ 字段号漂移**必然显形** |
| **「直接比 hex」** | user 域响应里带**两侧必然不同**的量（雪花 uid / 各自生成的历史 id / 写入时刻）⇒ 直接比 hex **恒红** ⇒ ⇒ **只屏蔽「值」，不屏蔽 presence** |

### 屏蔽表（**只屏蔽值 · presence 仍参与比对**）

| 字段 | 占位符 | 理由 |
|---|---|---|
| `UserInfo.uid` / `CreateUserResp.uid` / `LoginResp.uid` | `<UID>` | 雪花 ID ⇒ 两侧必然不同 |
| `AvatarHistoryItem.id` | `<HISTORY_ID>` | 服务端生成的 opaque id |
| `AvatarHistoryItem.create_time` | `<TIME>` | 写入时刻 ⇒ 必然不同 |

> ⇒ **B 有该字段而 C 没有 ⇒ 打印结果不同 ⇒ 照样红** ⇒ 即「屏蔽的是**两侧必然不同的那个值**」，不是「这个字段存不存在」。

### 18 例矩阵（**9 个方法全覆盖** + 错误路径 + 成功路径）

| # | 用例 | 方法 | 覆盖 |
|---|---|---|---|
| 1 | `wire_register` | Register | 注册成功（返回 uid） |
| 2 | `wire_register_dup` | Register | `ALREADY_EXISTS`「用户名已存在」 |
| 3 | `wire_get_user` | GetUser | 命中（UserInfo **含 status**） |
| 4 | `wire_get_user_missing` | GetUser | `NOT_FOUND`「用户不存在」 |
| 5 | `wire_login_ok` | Login | 成功（uid 有 / status=0 **不编码**） |
| 6 | `wire_login_wrong_password` | Login | `status=1`（与用户不存在**同码**，防枚举） |
| 7 | `wire_login_user_missing` | Login | `status=1` |
| 8 | `wire_update_user_same_name` | UpdateUser | `ALREADY_EXISTS`（**D-004 不排除自己**，见下） |
| 9 | `wire_update_user_rename` | UpdateUser | **成功路径**（UserInfo **不含 status**） |
| 10 | `wire_logout_empty_token` | Logout | `INVALID_ARGUMENT`「Token不能为空」 |
| 11 | `wire_logout` | Logout | 成功（**响应是空消息 · 0 字节**） |
| 12-13 | `wire_upload_avatar_1/2` | UploadAvatar | 上传 + 回显 history |
| 14 | `wire_get_avatar_history` | GetAvatarHistory | **repeated 列表（顺序参与比较）** |
| 15 | `wire_select_avatar` | SelectAvatar | 选中成功 |
| 16 | `wire_select_avatar_empty_id` | SelectAvatar | `INVALID_ARGUMENT`「history_id is required」 |
| 17 | `wire_delete_user` | DeleteUser | 成功（`success=true`） |
| 18 | `wire_delete_user_missing` | DeleteUser | `NOT_FOUND`「用户不存在」 |

> 📌 **7 条同侧自洽断言**（`SELFCHECK`）：`select_echoes_uploaded_url` · `select_history_id_echoes_request` ·
> `upload_returns_current_avatar` · `login_ok_has_uid` · `get_user_found_true` · `logout_response_is_empty` ·
> `history_list_has_two_items` · `rename_update_returns_new_username`
> ⇒ 它们保证「两侧 id 必然不同」这件事**不至于让语义判定落空**（语义靠**每侧自己的因果链自洽**判定，不靠「两侧 id 恰好一样」）。

### 🔴 变异证据（**证明这一维的判据不是空洞的**）

- **植入的变异**：在 C 侧 `UpdateUserResp` 的 `UserInfo` 上**多设一个 `status`**（`service/users/user/.../UserDomainService.java`）
  ⇒ ⇒ C 侧 wire 上**多出 `f6=0`**（`optional int64 status` 的 **presence** 差异）。
- **读数**：
  | 维度 | 结果 |
  |---|---|
  | **HTTP 20 例** | ✅ **真差异 0**（该字段**只出现在 gRPC 响应里** ⇒ HTTP 维度**完全看不见**） |
  | **gRPC wire 18 例** | ❌ **真差异 1** ⇒ **只有 `wire_update_user_rename` 变红**，其余 17 例仍绿 |
  | 差异内容 | `B: …;f5{…};f5{…}` ↔ `C: …;f5{…};f5{…};**f6=0**` |
  | **退出码** | **EXIT=1** ⇒ 门禁**有牙齿** |
- **还原**：用**文件备份 + `cp` 回来**（不依赖 git）⇒ 重建 ⇒ **同条件复跑** ⇒ EXIT=0。
- ⇒ **结论**：gRPC wire 维度能抓到 **HTTP 维度结构性看不见**的一类差异 —— **proto3 presence 语义**。
  这正是 §3.4「复制忠实 ≠ 消费方式已验」的最后一维。

### 📌 顺带**钉住**一个两侧共有的怪癖（**不是漂移**）

`UpdateUser` 用**自己的** username 更新 ⇒ 撞唯一性检查 ⇒ `ALREADY_EXISTS`。
⇒ 两侧**逐字一致**（`wire_update_user_same_name` 两边都是 `ERROR_ALREADY_EXISTS`）
⇒ ⇒ **不是 C 侧漂移**，是**继承自 B 侧**的行为（D16：继承，不回溯）⇒ **登记为「已钉住」而非偏离**。

## 12.2.2 🔴 场景轮（双侧对称）：超时 / gRPC deadline / 取消传播 / 恢复 / 外部依赖失败 / 恢复 / 并发

> **探针**：`tools/difftest/users-user/scenarios.py`（**新增**）⇒ 由 `run.sh` 在**最后**调用
> （⚠️ 场景轮会 **pause / stop 两侧的 PG** 并**改库** ⇒ 必须排在 HTTP 矩阵、wire 矩阵、
> DB 最终态对拍**之后** ⇒ 前几轮的期望值不受影响）。
> **借形**：`tools/difftest/follow/run.sh` 的 6 类场景轮（§8d 阶段 1~6）。

### 🔴 故障注入为什么必须「两侧各自 + 容器级」

| 注入 | 做法 | 为什么不能共享 |
|---|---|---|
| **PG 挂起**（超时 / deadline / 取消） | `docker pause <各自容器>` | 两容器**各自**挂 ⇒ 两侧走**完全同形**的路径 ⇒ 比出来的差异才是**行为差异** |
| **PG 停**（外部依赖失败） | `docker stop <各自容器>` ⇒ 连接被拒 | ⚠️ **绝不去碰共享栈的 `Sea-postgres`**（别的 lane 在用） |
| **Nacos 分组** | B=`E4UB` · C=`E4UC` | 两侧 gRPC 服务**同名** `user-rpc` ⇒ 同组时 B 的 api 会拨到 C 的 rpc ⇒ **对拍对象就错了** |

### 结果（**8 个阶段**；探针机器摘要见文末）

> 📌 **计数口径（照抄探针输出，不自造）**：机器摘要 = 「❌ 真差异 **3** ｜ ⚠️ 对称异常/已知现象 **2**」
> ⇒ ⇒ **3 条真差异 = 2 个根因**（第 3 条是第 2 条的**判别性变体** ⇒ 用来分离成因，不是新缺陷）
> ⇒ ⇒ ⚠️ 2 条 = 恢复轮的 **JSON 键序**（PARITY-003，已登记）
> ⇒ ⇒ **description 文案差异不在计数里** —— 探针只把 **status code** 计入 ✅/❌，description 是**并排打印**出来的（见下表第 3 行）

| 阶段 | 判据 | 结果 |
|---|---|---|
| **B 超时**（PG 冻结 ⇒ HTTP 读路径挂住） | HTTP 出口形态 | ❌ **B = `200 + 信封 1015「服务繁忙」` @ 2.01s** ↔ **C = `503 + 裸文本 "Request Timeout"` @ 3.00s** ⇒ **DRIFT-USER-PARITY-005** |
| **C gRPC deadline**（PG 仍冻结 · 客户端 deadline 20s） | gRPC status | ✅ 两侧 `ERROR_DEADLINE_EXCEEDED`（B @ 2088ms · C @ 2096ms）⇒ **服务端 2s 预算两侧对称** |
| **C gRPC deadline** | description | 🔶 文案形态不同：B `context deadline exceeded` ↔ C `rpc deadline exceeded` ⇒ 客户端可见 ⇒ 登记（**根因已在 follow 域台账取证**：C 侧 `common/.../ObservabilityAutoConfiguration.java:299` **硬编码** `.withDescription("rpc deadline exceeded")`；B 侧 `service/common/.../observability/RpcInterceptor.java` 的 `GO_DEADLINE_EXCEEDED_DESCRIPTION` ⇒ **同一机制、两种文案**） |
| **D 取消传播**（客户端 1s 取消） | gRPC status | ✅ 两侧 `ERROR_CANCELLED`（cancel_issued=true） |
| **D 取消传播** | **副作用** | ❌ **B = 0 行** ↔ **C = 1 行**（INSERT 提交了）⇒ **DRIFT-USER-PARITY-006** |
| **D 判别性变体**（cancel-after=**5s**，必定晚于服务端 2s 预算） | 副作用 | ❌ **同样 B=0 · C=1** ⇒ ⇒ **两个变体同结果** ⇒ ⇒ **差异与「客户端取消」无关** ⇒ 归因落在**服务端预算到点后有没有把在途写回滚** |
| **E 恢复**（PG 放行） | 恢复 + 响应 | ✅ 两侧**连续 3 轮**回 200（2s）⇒ 稳定态 ⇒ 响应一致（🔶 仅键序） |
| **F 外部依赖失败**（PG 停 ⇒ 连接被拒） | HTTP 出口 | ✅ **两侧都是 `200 + 信封 1015`**（3/3 轮）⇒ ⇒ **共享层 6a3acb4 的修复在 users-user 域也生效**（独立交叉验证 admin lane 的复测） |
| **G 恢复**（PG 重新 start） | 恢复 + 响应 | ✅ 两侧**连续 3 轮**回 200（2s）⇒ 响应一致（🔶 仅键序） |
| **H 并发**（两侧各 8 路并发 register 同一用户名） | 响应集合 + 行数 | ✅ 两侧响应集合**都是 `{200/200, 200/5001}`** · 各自**恰好 1 行** |

> **探针机器摘要（run6 · 照抄）**：`—— 场景轮摘要：❌ 真差异 3 ｜ ⚠️ 对称异常/已知现象 2 ——`
> ⇒ ⚠️ 2 条 = `scn_recover_after_pause` / `scn_recover_after_restart` 的 **JSON 键序**（PARITY-003）
> ⇒ ❌ 3 条 = 上表的 **超时出口** / **取消后副作用** / **取消后副作用·判别性变体** ⇒ **2 个根因**

### 🔴 两条真差异的归因（**3 条读数 · 2 个根因**）

#### DRIFT-USER-PARITY-005 · 「DB 完全不可用」时的 **HTTP 超时出口形态**不同（**B=1015@2s ↔ C=503@3s**）

- **读数**：B `200 + {"code":1015,"msg":"服务繁忙"}` @ **2.01s**；C `503 + "Request Timeout"` @ **3.00s**。
- **判别性读数（耗时）**：B 的 2.01s **= 它自己 gRPC 服务端的 2s 预算**（阶段C 实测两侧服务端预算都是 ~2.1s ⇒ **预算机制对称**）
  ⇒ ⇒ **B 的 1015 来自「user-rpc 的 2s 调度器关 call → api 层 default 分支 → 1015」**
  ⇒ ⇒ 而 C 的 3.00s **= 共享层 HTTP 中间件的 3s 预算兜底**（裸文本 503）。
- **根因（结构性 · D1 合并进程的连带后果）**：
  B 侧 HTTP 要走 **user-api →(gRPC)→ user-rpc → DB** ⇒ **多一跳**；
  C 侧 D1 合并成单进程 ⇒ HTTP 直接进领域层 ⇒ **那一跳不存在** ⇒
  ⇒ ⇒ **那一跳的「服务端 2s 预算主动关闭 call」在 C 侧的 HTTP 路径上没有对应物**；
  ⇒ ⇒ C 侧 HTTP 路径的「内层 2s 预算」只能**靠 SQL cancel 送达 PG** 才生效
  ⇒ ⇒ 而本次注入是 **PG 冻结** ⇒ **CancelRequest 送不到** ⇒ ⇒ 退化成 3s HTTP 预算兜底。
- **⚠️ 与 admin lane 的关系（重要，别混淆）**：admin lane 的 §9.10.3 测的是「**持锁制造慢查询**」
  ⇒ **PG 是活的** ⇒ CancelRequest **送得到** ⇒ ⇒ C 侧内层 2s 生效 ⇒ ⇒ 修后是 `1015 @ 2.05s` ⇒ **两侧一致**；
  ⇒ ⇒ **两次测的是不同注入** ⇒ ⇒ **本次的差异不是「共享层没修」**，而是**「DB 完全不可响应」这一档的出口形态差**。
- **⇒ 处置**：**签字偏离**（见 [`DEV-USER-010`](../../Intentional_Deviations/users/_signed-deviations.md)）——
  与 **DEV-USER-008**（D2 域内直连 ⇒ B 侧 api 层「客户端熔断打开」分支不可达）**同族**：
  **D1/D2 的进程合并 ⇒ B 侧 api 层那一跳的「超时出口」也一并不可达** ⇒ 出口形态不同但**语义等价**（都是「服务端忙 ⇒ 拒绝」）。

#### DRIFT-USER-PARITY-006 · **客户端取消**后，服务端**在途写**是否回滚 ⇒ **B=0 行 ↔ C=1 行**（**跨域 · 共享层**）

- **读数**：客户端在 1s 主动取消一个**写路径**（Register）调用 ⇒ 两侧 gRPC 都回 `CANCELLED`（✅ 一致）
  ⇒ ⇒ **放行 PG 后**查库：**B = 0 行**（INSERT 没提交）· **C = 1 行**（INSERT 提交了）。
- **判别性变体**：把取消推迟到 **5s**（**必定晚于服务端 2s 预算**）⇒ ⇒ **结果完全相同**（B=0 · C=1）
  ⇒ ⇒ ⇒ **差异与「客户端取消」无关** ⇒ 归因落在「**服务端 2s 预算到点后，有没有把在途 SQL/事务取消掉**」。
- **代码级取证（两侧共享层本就是两套实现，不是同一份 jar）**：
  | | B 仓 `service/common/` | RAG-V1 `common/` |
  |---|---|---|
  | gRPC 服务端预算 | `observability/RpcInterceptor.java`（**主动关 call** 的调度器 ＋ `DeadlineState` 内嵌其中） | `observability/DeadlineState.java`（**独立类** ＋ 领域层**检查点**式判定） |
  | SQL 超时翻译 | `persistence/SqlTimeoutStatusTranslator.java` | `persistence/SqlTimeoutStatusTranslator.java` ＋ `DeadlineBoundedConnectionTimeout` ＋ `DeadlineTranslatingDataSource` ＋ `StatusAwareSqlSession*`（6a3acb4 新增） |
  | HTTP 观测栈 | OpenTelemetry（`HttpMiddleware`） | Micrometer Observation（`HttpMiddleware`） |
- **⇒ 处置**：**跨域 · 归 `global` 收口域** —— 落点在**共享层**，**本 lane 无写权面**（AGENTS.md §五.3）。
  ⚠️ **不擅自改**：C 侧 `service/users/user/` 一行未改（本条**不是**域内实现问题）。

### 📌 顺带**钉住**的两条（两侧一致 ⇒ 不是漂移）

- **并发 register 同名**：两侧响应集合都是 `{200/200, 200/5001}`（**1 个成功 + 7 个 DB 唯一约束冲突**）
  ⇒ ⇒ **并发语义等价**（唯一索引 + 事务边界生效）⇒ 各自**恰好 1 行**。
- **PG 停（连接被拒）**：两侧都是 `200 + 信封 1015`（3/3 轮）⇒ ⇒ **共享层 6a3acb4 的修复在 users-user 域同样生效**
  ⇒ ⇒ 与 admin lane 的复测**交叉验证**（两个 lane · 两个域 · 两套探针 · 同一读数）。

## 12.3 🔴 本轮抓出并**已修**的 2 个真缺陷

### DRIFT-USER-PARITY-001 · `grpc-inprocess` 声明成 test scope ⇒ **C 侧 fat jar 起不来**（✅ 本域已修）

- **现象**：`java -jar user-service-1.0-SNAPSHOT.jar` ⇒ **上下文 refresh 失败，进程起不来**
  ```
  NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder
    at net.devh.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration
         .shadedNettyGrpcChannelFactory(GrpcClientAutoConfiguration.java:160)
  ```
- **根因**：devh `grpc-client-spring-boot-starter` 3.1.0 的 `GrpcClientAutoConfiguration`
  在**运行期**就要 `io.grpc.inprocess.InProcessChannelBuilder`；D1 把 api+rpc 合成单进程 ⇒ **client starter 也在**
  ⇒ 该 auto-config 会装配 ⇒ **缺类即炸**。而本域 pom 原把 `grpc-inprocess` 声明成 **`<scope>test</scope>`**
  ⇒ ⇒ **只在测试类路径上有** ⇒ ⇒ **39 例测试全绿（测试类路径带这个类），生产 jar 起不来**。
- **B 出处（类路径口径以 B 侧为准）**：B 侧两个 fat jar **都含** `BOOT-INF/lib/grpc-inprocess-1.63.0.jar`
  ⇒ B 的**运行时**类路径带这个类 ⇒ B 能起。
- **修法**：去掉 `<scope>test</scope>`（编译期）⇒ 运行时类路径与 B 同口径。
- **防复发**：探针 `run.sh` 在起 C 侧**之前**先断言 jar 内含 `grpc-inprocess` ⇒ **缺了直接 die**（不是起不来才发现）。

### DRIFT-USER-PARITY-004 · 400 的「报哪个字段」**不确定**（✅ 本域已修）

- **现象**：同一输入 `POST /user/v1/register {}` **连打 6 次 ⇒ 实得 3 种不同文案**
  （`password 不能为null` ×4 / `username 不能为null` ×1 / `email 不能为null` ×1）
  ⇒ C 侧的 400 响应体**不是确定性输出**。B 侧同样输入连打 6 次**全部相同**（`field "username" is not set`）。
- **根因**：原实现 `e.getBindingResult().getFieldErrors().stream().findFirst()`
  ⇒ Hibernate Validator 把违规装进 **`HashSet`** ⇒ `getFieldErrors()` 的遍历顺序**取决于 hash**。
- **B 出处**：B 侧 `GoStyleRequiredFields#assertGoRequiredFields` 是**按固定顺序逐个判**、遇第一个缺失就抛：
  `RegisterReq` = username→password→email · `LoginReq` = username→password · `SelectAvatarReq` = history_id。
- **修法**：**按 DTO record 的声明序取第一条违规** ⇒ C 侧各 record 的声明序**恰好与 B 的判定序逐个一致**
  ⇒ ⇒ 既**确定**，又与 B **报同一个字段**（修后实测：`register {}` ⇒ 两侧都报 `username`；
  `register {username}` ⇒ 两侧都报 `password`；`login {}` ⇒ 两侧都报 `username`；`login {username}` ⇒ 两侧都报 `password`）。
- **防复发**：新增断言 `UserApiHttpContractTest#validationViolationSelectionIsDeterministicAndMatchesB`
  ⇒ 连打 8 次断言**逐次相同** + 断言**报的是声明序第一个字段**。
- **⚠️ 边界**：只决定「报哪个字段」；**文案形态**仍是 Bean Validation 的（与 B 的 go-zero 不同）⇒ **那是已登记的 D-01**，本条**不改**它。

## 12.4 🔴 本轮抓出但**不在本 lane 写权面**的 2 条（跨域）

### DRIFT-USER-PARITY-002 · 其余 **7 个域**的 fat jar 起不来（收口域 / 各域 lane）

| 域 | client starter | `grpc-inprocess` scope | 会不会起不来 |
|---|---|---|---|
| `article` `comment` `favorite` `hot` `like` `message` `task` | ✅ 有 | **test** | 🔴 **会** |
| `follow` `users/admin` `users/user` | ✅ 有 | 编译期 | ✅ 不会（`users/user` 本轮已修） |

- **证据（代码级，不是从报错反推）**：`mvn -o -pl service/<域> dependency:list -DincludeScope=runtime`
  ⇒ 这 7 个域的 runtime 依赖清单里有 `net.devh:grpc-client-spring-boot-starter:compile`
  ⇒ **但没有** `io.grpc:grpc-inprocess` ⇒ ⇒ 生产 jar 缺类 ⇒ 同样会炸。
- ⚠️ **当前被「Application 无 `main`」挡在前面**（先挂在 `repackage`）
  ⇒ ⇒ **各域补 `main` 的那一刻才会暴露** ⇒ **建议先修这一条**，否则各域会连着栽两次。
- **修法**：与本域同款 —— 去掉 `<scope>test</scope>`。

### DRIFT-USER-PARITY-003 · JSON 响应**键序**（Jackson 2 ↔ 3 的默认行为）⇒ **共享层**，归收口域

- **现象**：B 侧输出**字母序**（`{"found":…,"user":{"email":…,"score":…,"uid":…,"username":…}}`），
  C 侧输出**声明序**（`{"user":{"uid":…,"score":…,"username":…,"email":…},"found":…}`）
  ⇒ **取值集合完全相同**，只是**键的顺序**不同 ⇒ 4/20 例在字节级不同。
- **根因（B 侧自己的 javadoc 是权威）**：B 侧 `hot-api/HotArticleCard.java` 写着
  「Jackson 的默认行为是**声明序**，但全局特性 `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY`
  **一旦被打开**（Spring Boot 的 mapper 定制、或某个 starter 的 `Jackson2ObjectMapperBuilder` 定制），
  输出就会变成**字母序**」
  ⇒ ⇒ **Boot 4（Jackson 3）的 mapper 定制默认开着它** ⇒ B 是字母序；
  ⇒ ⇒ **Boot 3.5（Jackson 2.19.4）默认关** ⇒ C 是声明序。
- **证据（两侧都不是 DTO 钉的）**：B 侧 `user-api` 与 C 侧 `users/user` **都是零处 `@JsonPropertyOrder`**
  ⇒ ⇒ 差异**纯来自全局默认** ⇒ ⇒ 是**共享层机制差**，**10 个域全受影响**。
- **落点**：C 侧共享层 `common/` 的 ObjectMapper 定制（加一个 `Jackson2ObjectMapperBuilderCustomizer`
  开 `SORT_PROPERTIES_ALPHABETICALLY`）⇒ **`common/**` 只有收口任务能写**（AGENTS.md §五.3）⇒ **本 lane 不越权代改**。
- ⚠️ **只做一半会更糟**：B 侧 `hot`/`article` 等域**显式用 `@JsonPropertyOrder` 钉了顺序**（为了对齐 Go 的声明序）
  ⇒ ⇒ 共享层开了字母序之后，**那些域必须同时在 C 侧钉上同样的顺序** ⇒ ⇒ **共享层 + 域级 pin 两件事要一起做**。
- **判据口径**：JSON 对象按 RFC 8259 是**无序集合** ⇒ 键序**不是**业务可观察行为
  ⇒ ⇒ 本报告把它单列为「字节级差异」，**结构级判为等价**（既不混进行为判定、也不隐藏）。

## 12.5 ⚠️ 本轮**仍未**覆盖（不声称闭合）

| 项 | 状态 | 备注 |
|---|---|---|
| ~~**gRPC wire 逐字节对拍**（9 个方法）~~ | ✅ **已闭合**（2026-11-03 第二轮） | ⇒ **18 例真差异 0** · 7 条同侧自洽断言全绿 · **变异证据**：植入 presence 差异 ⇒ **只有**该维度变红 ⇒ 详见 §12.2.1 |
| **B 侧 Go 真源第三方锚** | **不可得** | 本机无 `Sea-etcd` ⇒ Go 侧无法活体 ⇒ 本探针是**双侧**（C ↔ B），**不是** follow 域那种**三方** |
| ~~**并发 / 超时 / 外部依赖失败 / 恢复**场景轮~~ | ✅ **已做**（2026-11-03 第三轮 · §12.2.2） | ⇒ **8 阶段** ⇒ 探针机器摘要「❌ 真差异 **3** ｜ ⚠️ 对称异常/已知现象 **2**」⇒ **3 条真差异归为 2 个根因**（第 3 条 = 第 2 条的**判别性变体**）⇒ ❌ 两条根因均已归因：一条＝签字偏离 **DEV-USER-010**、一条＝**共享层**跨域 |
| **gRPC deadline 的 description 文案形态** | **🔶 已登记** | B `context deadline exceeded` ↔ C `rpc deadline exceeded` ⇒ **客户端可见** ⇒ 落点＝共享层的 gRPC 拦截器（C 侧 `common/.../ObservabilityAutoConfiguration.java:299` **硬编码**）⇒ 归收口域（随 DRIFT-USER-PARITY-006 一并处理） |
| **「DB 完全不可响应」档的 HTTP 出口形态** | **🔶 已登记为签字偏离** | DRIFT-USER-PARITY-005 ⇒ DEV-USER-010 ⇒ 落点＝D1 合并的连带后果（与 DEV-USER-008 同族） |
| **gRPC 的 deadline / 取消传播** | ✅ **已做**（2026-11-03 第三轮 · §12.2.2） | ⇒ **两侧 status 一致**（`DEADLINE_EXCEEDED` @ ~2.1s · `CANCELLED`）⇒ ⇒ **服务端 2s 预算两侧对称**；⚠️ **description 文案形态不同**（B `context deadline exceeded` ↔ C `rpc deadline exceeded`）⇒ 客户端可见 ⇒ 落点＝共享层 gRPC 拦截器；⚠️ **取消后在途写是否回滚**两侧不同（B=0 行 ↔ C=1 行）⇒ **PARITY-006** ⇒ 落点＝共享层 ⇒ 归收口域 |

## 12.6 可复跑

```bash
cd /Users/pr/Documents/GitHub/RAG-V1.worktrees/users-user
bash tools/difftest/users-user/run.sh          # ⇒ 退出码 0 = 一致 · 1 = 有差异 · 2 = 环境问题
# 派发器口径：bash tools/difftest/run-parity.sh users-user
```

> 📌 **本轮新增文件**（都在**域私有**目录 `tools/difftest/users-user/` ⇒ **不属共享层**）：
> | 文件 | 作用 |
> |---|---|
> | `grpc-wire/UserWireProbe.java` | **gRPC wire 层原始字节探针**（同一份 class 打两侧）· 第三轮加了 `--only` / `--deadline` / `--cancel-after` 与 4 个场景用例 |
> | `wire_compare.py` | gRPC wire 维度比对器（**三档分档**：一致 / 🔶 已登记偏离 / ❌ 真差异） |
> | `scenarios.py` | **场景轮**（**8 阶段**）：并发 / 超时 / 外部依赖失败 / 恢复 ×2 / gRPC deadline / 取消传播 |
> | `run.sh` | 「编译探针 → 打两侧 → 归一化比对」两段 ＋ `users_user_avatar_history` 最终态比对 ＋ **场景轮（排在最后）** ＋ 陈旧产物检测 ＋ Nacos 注册等待 ＋ 按端口追 PID |
> | `compare.py` | HTTP 维度比对器（第三轮把 argv 下沉到 `main()` ⇒ **可被 `scenarios.py` 复用**，口径不许分叉） |
>
> ⚠️ **探针自检**：起 C 侧之前先断言 fat jar 内含 `grpc-inprocess` ⇒ **缺了直接 die**（不是起不来才发现）。
> ⚠️ **陈旧产物检测**：jar 存在**不等于** jar 对得上源码 ⇒ 按**源文件 mtime** 判陈旧并重建（跨模块：域内 ＋ `users/common` ＋ `common/` ＋ `proto/`）⇒ 见 P-USER-16。
> ⚠️ **场景轮必须排在最后**：它会 **pause / stop 两侧的 PG** 并**改库** ⇒ 前几轮的期望值不受影响。
