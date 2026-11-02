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
# ⇒ 参与测试的模块合计: tests=310 failures=0 errors=0 skipped=0
#    （310 = common 224 + users-common 46 + user-service 40；
#      口径 = **回归日志的模块级汇总**，不取 `surefire-reports/*.xml` 求和 —— §五.8 守门硬规则 ①
#      ⇒ ⚠️ 也**不能**把「逐类行」与「汇总行」一起求和 ⇒ 那会算成 620）
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
# ⇒ users 109/109 100% · B 145/145 100% · 合计 629/629 · 752/752
# ⇒ _INDEX.md 自报数对账 ✅ · 小节自报条数 ✅ · 主表行形状 ✅
# ⇒ B 列双向对账 ✅（精确 664 · 通配 3 · 目录 64）· B↔C 指代消解 ✅
# ⇒ EXIT=0

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
> 本轮**做完了「C 侧运行验证」**（39 例真触发测试，见 §11.6.3）与**「B 侧源码逐条对照」**（58/58 文件全读）；
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
| `updateById` vs `update(null, wrapper)` 的写路径形态 | **NOT_VERIFIED** | ⚠️ B 侧与 C 侧的写入 API 形态差异需逐语句对拍 ⇒ ⇒ 未做 |
| 端到端「register → login → get」 | **PASS**（C 侧单侧） | `UserApiHttpContractTest#registerLoginGetHappyPath`（**真 PG + 真 bcrypt + 真 JWT 签发 + 真 uid 字符串化**） |
| **双侧活体对拍**（同一输入打 B 与 C 比对） | **PASS**（**HTTP 20 例 + DB 最终态**）· gRPC wire 仍 `NOT_VERIFIED` | ⚠️ **2026-11-03 实跑**（`tools/difftest/users-user/run.sh`，两侧真进程：真 Tomcat + 真 gRPC + 真 PG + 真 Redis + 真 Nacos）：HTTP **结构级 16/20 一致**（4 例差异全是 **400 文案形态** = 已登记 D-01）· **字节级 12/20**（另 4 例 = JSON 键序）· DB `users_user` 最终态**逐字段一致** · 表清单一致（4 张）· 详见 §十二 |
| C 侧 **fat jar 真能起得来**（生产类路径 ≠ 测试类路径） | **PASS**（本轮修） | 🔴 2026-11-03 对拍实测：`grpc-inprocess` 声明成 **test scope** ⇒ **生产 jar 缺类 ⇒ 启动即挂**（`NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder`）⇒ **39 例测试全绿却零告警**。已修（去 scope）＋ B 侧类路径取证 + 探针自检。详见 §十二.3 |
| 🔴 400 的「报哪个字段」**确定性** | **PASS**（本轮修） | 原实现 `getFieldErrors().findFirst()` ⇒ Hibernate Validator 违规集合是 `HashSet` ⇒ **同一输入 6 次出 3 种文案** ⇒ C 侧 400 **不是确定性输出**。已改为**按 DTO 声明序取第一条**（与 B 侧 `GoStyleRequired` 判定序逐个一致 ⇒ **报同一字段**）+ 新增断言连打 8 次逐次相同。详见 §十二.3 |
| 其余 9 域的 `grpc.client:` 空键 | **FAIL（跨域）** | DRIFT-USER-IMPL-004（那些域**一启动就挂**） |
| `admin.md:158` 的 C 列悬空指针 | **FAIL（跨域）** | DRIFT-USER-IMPL-005 |
| `docs/File_Tree/proto.md` 陈旧 | **FAIL（跨域）** | DRIFT-USER-IMPL-006 |
| `docs/File_Tree/users/user.md` 的 §五 状态块陈旧 | **FAIL（跨域 · 工具层）** | DRIFT-USER-IMPL-008（根因在 `tools/gen-file-tree.py` 的写死占位） |
| 🔴 **其余 7 个域的 fat jar 起不来**（`grpc-inprocess` 是 test scope） | **FAIL（跨域）** | DRIFT-USER-PARITY-002：`article`/`comment`/`favorite`/`hot`/`like`/`message`/`task` 七个域的 pom 把 `grpc-inprocess` 声明成 `<scope>test</scope>` ⇒ 生产 jar 缺类 ⇒ devh `GrpcClientAutoConfiguration:160` 抛 `NoClassDefFoundError` ⇒ **启动即挂**。⚠️ 当前被「Application 无 `main`」挡在前面（先挂 repackage）⇒ **各域补 `main` 的那一刻才会暴露** ⇒ 建议**先修这一条** |
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
| 12 | 框架机制是否有真触发测试？ | ✅ 39 例、`skipped=0`；**真 Tomcat** ＋ **真 gRPC** ＋ **真 PostgreSQL**（Testcontainers `postgres:16-alpine`） |

### 最终结论（**按 §十 的状态词表**）

> **本域实现层：PASS**（文件级承接 48/48、零空壳、C 侧运行证据 39 例全绿 `skipped=0`、映射表守门 EXIT=0）。
>
> ⚠️ **但「行为与 B 侧完全等价」这一命题本身是 `NOT_VERIFIED`** —— 本轮做的是
> **「B 侧源码逐条对照 ＋ C 侧运行验证」**，**不是**「双侧活体对拍」。
> ⇒ ⇒ 需补：**双侧对拍**（同一输入打 B 与 C 比对）与 **写路径形态逐语句对拍**。
> （**事务回滚实测已于本轮补上** ⇒ 由 `NOT_VERIFIED` 升为 `PASS`，含变异证据。）
>
> ⚠️ **另有 4 条跨域 `FAIL`**（DRIFT-USER-IMPL-004/005/006/008）⇒ 归属收口域与 admin lane ⇒
> **不在本 lane 的写权面**，已逐条登记。

### 审核状态汇总

| 状态 | 项数 |
|---|---|
| **PASS** | **22** |
| **INTENTIONALLY_CHANGED**（L3 降级 D-01） | 1 |
| **NOT_VERIFIED**（写路径形态 · **gRPC wire 逐字节对拍**） | **1 + 1** |
| **FAIL（跨域 · 不在本 lane 写权面）** | **6** |
| **合计** | **30** |

> 📌 **口径**：本表 30 行**逐行点过**（脚本按行取状态列统计）⇒ 22 + 1 + 2 + 6 = 31 ⇒
> 其中「双侧活体对拍」一行含**两个子项**（HTTP+DB = PASS · gRPC wire = NOT_VERIFIED）⇒ **行数 30 · 计分 31**。
> （**初稿此处写的是 17，自身少算了 1 ⇒ 2026-11-02 复核时按行重数并订正；2026-11-03 对拍后再订正为 22/1/2/6。**）

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

---

## 11.10 一句话结论（本 lane 汇报口径）

**user 子域 48 个主代码文件已全部落地并编译通过，40 例真触发测试（真 Tomcat + 真 gRPC + 真 PostgreSQL）全绿、`skipped=0`，映射表守门 EXIT=0（C 列 109/109、B 列 145/145）；**
**全仓 `mvn -o -fae clean verify` 的同条件对照：失败面由基线的 10 个域收敛到 9 个域，差额恰好是本域（基线 10 个 Application 全缺 `main` ⇒ `repackage` 必挂 ⇒ 基线自带，非本轮引入）；**
**事务边界（D-005 / D-015）已由 `NOT_VERIFIED` 升为 `PASS` —— 用真 UNIQUE 约束冲突证明 W2 的 UPDATE 确实被回滚，并用变异测试证明该判据非空洞；**
**2026-11-03 已完成**双侧活体对拍（HTTP 20 例 + DB 最终态）：结构级 16/20 一致（4 例差异全是**已登记**的 400 文案形态）、字节级 12/20、DB 最终态逐字段一致；**
**⇒ 但「行为与 B 侧完全等价」这一命题本身仍是 `NOT_VERIFIED`** —— **gRPC wire 逐字节对拍**尚未做（且 B 侧 Go 真源本机无法活体，无第三方锚）；**
**另有 6 条跨域 FAIL：DRIFT-USER-IMPL-004/005/006/008（收口域与 admin lane）＋ **DRIFT-USER-PARITY-002（其余 7 域 fat jar 起不来）＋ DRIFT-USER-PARITY-003（JSON 键序 · 共享层）** —— 本 lane 不越权代改，已逐条登记。**

---

# 十二、E-5 双侧活体对拍（2026-11-03）

> **目的**：把 §十一 里唯一还能在本 lane 内自闭合的 `NOT_VERIFIED`（**双侧活体对拍**）闭合。
> **探针**：[`tools/difftest/users-user/run.sh`](../../../tools/difftest/users-user/run.sh)（设计见其 [README](../../../tools/difftest/users-user/README.md)）
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

## 12.2 结果（HTTP 20 例 + DB 最终态）

| 口径 | 结果 |
|---|---|
| **结构级**（归一化 uid/history_id/JWT/时间戳/哈希 **＋ JSON 键序**） | **16 / 20 一致**；4 例不一致 = **全部是 400 文案形态**（go-zero `field "x" is not set` ↔ Bean Validation `x 不能为null`）⇒ **已登记 D-01 / DEV-USER-002** |
| **字节级**（只归一化 ID/JWT/时间戳/哈希，**键序参与比较**） | **12 / 20 一致**；另 4 例 = **JSON 键序**（⇒ DRIFT-USER-PARITY-003 · 共享层） |
| **DB 最终态** | ✅ `users_user` **逐字段一致**（username/status/score/extra_info/密码存在性/email）· ✅ **表清单一致**（4 张） |
| **B 侧只读** | ✅ 收尾断言 B 仓 `service/` 干净 |

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
| **gRPC wire 逐字节对拍**（9 个方法） | **NOT_VERIFIED** | 本轮只对拍了 HTTP + DB ⇒ gRPC 那一维**没做** ⇒ 探针 README §五已显式声明 |
| **B 侧 Go 真源第三方锚** | **不可得** | 本机无 `Sea-etcd` ⇒ Go 侧无法活体 ⇒ 本探针是**双侧**（C ↔ B），**不是** follow 域那种**三方** |
| **并发 / 超时 / 外部依赖失败 / 恢复**场景轮 | **未做** | follow 域已有 6 类（`tools/difftest/follow/run.sh`）⇒ 可借形 |

## 12.6 可复跑

```bash
cd /Users/pr/Documents/GitHub/RAG-V1.worktrees/users-user
bash tools/difftest/users-user/run.sh          # ⇒ 退出码 0 = 结构级一致 · 1 = 有差异 · 2 = 环境问题
# 派发器口径：bash tools/difftest/run-parity.sh users-user
```
