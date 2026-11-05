# Mapping_Table/favorite/favorite.md — favorite 域两方文件名对照表

> **本表目的**：把 **RAG**（B 列，判定基准）→ **RAG-V1**（C 列，Spring 惯用目标架构）
> 两棵树里 **favorite 域**的文件逐条对应起来，方便在 RAG-V1 上填实现时反查「这个 Java 类是从哪个 RAG 文件来的」。
>
> **B 列**：`/Users/pr/Documents/java&golang/RAG/service/favorite/`（判定基准 · 只读；api / rpc / common / proto 四模块）
> **C 列**：`/Users/pr/Documents/GitHub/RAG-V1/service/favorite/`（本仓；`favorite-service` **一个**模块；错误契约与事件 DTO 在域内 `contract/` 包，proto 在根级 `proto/`）
>
> **格式**：沿用 `docs/Mapping_Table/README.MD` 的 4 列表头与 4 词映射类型词表
> （`1:1` / `1:N` / `N:1` / `C 新增`），章节骨架沿用 RAG 那份 `docs/MappingTable/users.md`。

---

## ⚠️ 读表前必读的三条纪律

1. ⚠️ **B 列是唯一行为基准（D16 · 2026-11-02）。**
   B 侧是 RAG 仓（`/Users/pr/Documents/java&golang/RAG`）—— 本表所有「值」都以 B 侧源码为准逐条抄出；
   B 侧与 C 侧不一致的地方，本表**保留差异说明**，不做抹平。
   ⚠️ B 侧自身仍有已登记差异（favorite 域 **api / common / proto 三个组件台账仍是 0 字节（从未对照）**
   （`RAG/docs/DRIFT/favorite/{api,common,proto}.md`），**rpc 侧已修 6 处 L2 偏差，但还有 7 条未决**
   （`RAG/docs/DRIFT/favorite/rpc.md` §2 / §5））⇒ **继承并登记为签字偏离，不回溯第三方。**
2. ⚠️ **本表只回答「文件名 / 职责的对应关系」，不下「已对齐 / 未对齐」结论。**
   行为对齐状态看 `RAG/docs/DRIFT/favorite/<组件>.md` 的**证据强度列**。
3. ⚠️ **C 列的空壳 ≠ 缺职责。**
   C 侧每个 `.java` 目前都只有 `package` + 空类型 + `TODO` javadoc
   ⇒ **C 列出现的每一行都是「这个位置该放什么」，不是「已经有什么」。**

> 📌 **路径写法**：B 列用「模块名 / 包路径缩写 / 文件名」形式
> （`…` = `src/main/java/com/rag/favorite/favorite`，便于 `grep` 到文件名即可定位）；
> C 列用 RAG-V1 仓库根相对路径（覆盖度自查要逐字比对，故不给缩写）。

---

> 📌 **时点标注（2026-10-26 · D7）** —— 本表若干**决策锁**条目记录的是 **2026-10-25 之前**的形态
> （每域一个 `*-contract` 契约子模块 + `api/` + `rpc/` 两棵包树）。
> 这两处已被 **D7** 取代：① **10 个 `*-contract` 子模块全部删除** —— `.proto` 集中到**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`），
> 错误契约与事件 DTO 落进**域模块内的 `contract/` 包**；② **包压平** —— `api/` + `rpc/` 两棵树合并为**单层技术角色包树**。
> ⇒ **决策锁条目保留原样**（它们记录的是**当时的决策**，不是当前位置）；**当前位置一律看 §0.5「B → C 结构映射速查表」**。

## 0. 顶层模块对照

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `service/favorite/favorite-api/` | `service/favorite/`（的 `service/favorite/src/main/java/com/rag/favorite/advice + service/favorite/src/main/java/com/rag/favorite/config + service/favorite/src/main/java/com/rag/favorite/controller + service/favorite/src/main/java/com/rag/favorite/dto + service/favorite/src/main/java/com/rag/favorite/interceptor + service/favorite/src/main/java/com/rag/favorite/service` 子包） | N:1 | B 侧 api 是独立 Spring Boot 进程（`favorite-api/…/api/FavoriteApiApplication.java`，HTTP `server.port=${FAVORITE_API_PORT:18893}`）；C 里 api 与 rpc 合并为单一 app（决策 **D1**）⇒ **C 侧没有独立 api 模块** |
| `service/favorite/favorite-rpc/` | `service/favorite/`（的 `service/favorite/src/main/java/com/rag/favorite/wiring + service/favorite/src/main/java/com/rag/favorite/client + service/favorite/src/main/java/com/rag/favorite/config + service/favorite/src/main/java/com/rag/favorite/domain/entity + service/favorite/src/main/java/com/rag/favorite/domain/model + service/favorite/src/main/java/com/rag/favorite/domain/service + service/favorite/src/main/java/com/rag/favorite/exception + service/favorite/src/main/java/com/rag/favorite/grpc + service/favorite/src/main/java/com/rag/favorite/mapper + service/favorite/src/main/java/com/rag/favorite/metrics + service/favorite/src/main/java/com/rag/favorite/service` 子包） | N:1 | B 侧 rpc 是独立 Spring Boot 进程（`favorite-rpc/…/rpc/FavoriteRpcApplication.java`，gRPC `grpc.server.port=${FAVORITE_RPC_PORT:8082}`）；C 里与 api 合并（**D1**） |
| `service/favorite/favorite-common/` | `service/favorite/src/main/java/com/rag/favorite/contract/`（`ErrCodes`/`ErrMsg`/`GrpcErrorMapper`） | 1:N | B 侧 `favorite-common` 装 `errmsg/`（3 类：码表 / 文案表 / gRPC 往返）+ `span/`（`FavoriteSpans`）；C 侧按「proto（L1）+ 域内错误契约」重新分包，**码表内容一字不改** |
| `service/favorite/favorite-proto/src/main/proto/favorite.proto` | `proto/src/main/proto/favorite.proto` | 1:1 | B 侧 proto 在 `favorite-proto` 模块内、被 api 与 rpc **共用**；C 侧 proto 集中到根级 `proto/` 模块 ⇒ 跨服务共享面更干净 |
| `favorite-rpc/…/bootstrap/FavoriteNacosRegistration.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteNacosRegistration.java` | 1:1 | B 侧用 Nacos（`favorite-rpc/…/bootstrap/FavoriteNacosRegistration.java`），C 侧沿用（**D4** 生态替代已完成）⇒ B→C 1:1 |
| `favorite-rpc/…/bootstrap/FavoriteFactProcessLogging.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteFactProcessLogging.java` | 1:1 | B 侧的进程级日志由域外共享层 `service/common/.../communityfact/ProcessLogger.java` 提供，本域只装**身份**载体 ⇒ C 侧同样落 `wiring/`（域外共享层见 §4）|

> ⚠️ **C 侧模块数从 4 降到 1**：`favorite-proto` 落根级 `proto/`、`favorite-common` 落域内 `contract/` 包，
> `favorite-api` + `favorite-rpc` 合成 `favorite-service`。**新增 maven 模块 = 0**，C 侧 `service/favorite/pom.xml` 只声明 2 个 `<module>`。

---

## 0.5 · B → C 结构映射速查表（RAG 的哪个包 → RAG-V1 的哪个包）

> 📌 **本表是 D7（proto 集中 + 模块打平 + 包压平）之后的落点对照** —— 左列是 **RAG 仓（B 列）** 的真实包路径，
> 右列是 **RAG-V1（C 列）** 的真实包路径。**两侧均以 `find` 实测为准**，不是推测。
> ⇒ 用途：拿一个 RAG 侧的文件，能立刻定位到 RAG-V1 侧该读哪个包。

| RAG（B）包 | RAG-V1（C）包 | 说明 |
|---|---|---|
| `favorite/api/`（`FavoriteApiApplication`） | `com.rag.favorite`（`FavoriteServiceApplication.java`） | 入口类合并（D1）|
| `favorite/api/advice/` | `advice/` | 直接压平（`GlobalResponseAdvice` → `FavoriteApiResponseAdvice`）|
| `favorite/api/client/` | `client/` | 直接压平 |
| `favorite/api/config/` | `config/` | 与 rpc 侧合并 |
| `favorite/api/controller/` | `controller/` | 直接压平 |
| `favorite/api/dto/` | `dto/favorite/` + `dto/folder/` | 直接压平后**按业务对象拆 2 个子包**（D11：`favorite` 7 / `folder` 9） |
| `favorite/api/interceptor/` | `interceptor/` | 直接压平 |
| `favorite/api/service/` | `service/` | 与 rpc 侧合并 |
| `favorite/common/errmsg/` | `contract/` | 错误码（`ErrCodes` / `ErrMsg` / `GrpcErrorMapper`）|
| `favorite/rpc/bootstrap/` | `wiring/` | 直接压平 |
| `favorite/rpc/client/` | `client/` | 与 api 侧合并 |
| `favorite/rpc/config/` | `config/` | 合并 |
| `favorite/rpc/domain/` | `domain/entity/` + `domain/model/` | 按是否 `*Entity` 拆分（`FavoriteAuthorityFact` 非 Entity ⇒ 落 `domain/model/`）|
| `favorite/rpc/exception/` | `exception/` | 直接压平 |
| `favorite/rpc/fact/`（3 类） | `wiring/`（3 类）+ `service/`（1 类） | `FavoriteAuthorityEndpointConfig` + `FavoriteFactDispatchScheduler` + `FavoriteLegacyPreflightEndpoint`（`wiring/`）；`FavoriteLegacyPreflightService`（`service/`，报表计算）—— **P6-c 已拍板 ① Actuator 自定义端点**（2026-11-05），落点见 §1.6 |
| `favorite/rpc/mapper/` | `mapper/` | 直接压平 |
| `favorite/rpc/metrics/` | `metrics/` | 与 api 侧合并 |
| `favorite/rpc/service/` | `service/` + `domain/service/` | 领域服务另出 `domain/service/` |
| `favorite/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `FavoriteServiceGrpcImpl` → `FavoriteGrpcService` |
| `favorite-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.favorite`）| **集中化**（D7）|

## 1. 包结构对照

### 1.1 B 侧的进程拓扑（先看清「几个进程」）

> 📌 **B 侧 = 2 个 Spring Boot 进程**（`favorite-api` / `favorite-rpc`）；
> B 侧 `favorite-rpc` 是**单 JVM**，内部承载 gRPC server 与 3 个 fact 子组件（见下表 B 列的「B 侧落点」）。

| B RAG 文件 | B 侧落点（进程 / 组件） | 注册了什么 | 读写 |
|---|---|---|---|
| `favorite-rpc/…/rpc/FavoriteRpcApplication.java` | **favorite-rpc 进程**（gRPC） | `FavoriteServiceGrpcImpl`（7 个 RPC） | DB 读写 4 张表 + `UserRpcClient` + `ArticleRpcClient` |
| `favorite-rpc/…/rpc/fact/FavoriteFactDispatchScheduler.java` | favorite-rpc 进程内 `@Component` | **零 gRPC 服务注册**，只跑投递循环 | DB 读 + DC HTTP 出网 |
| `favorite-rpc/…/rpc/fact/FavoriteAuthorityEndpointConfig.java` | favorite-rpc 进程内 `@Configuration` + Servlet | **零 gRPC 服务注册**，只起 authority HTTP 入口 | DB 读 + HTTP 入 |
| `favorite-rpc/…/rpc/fact/FavoriteLegacyPreflightRunner.java` | favorite-rpc 进程内 `@Component` | **零 gRPC 服务注册**，只跑一次只读报表 | DB 只读 |

⇒ **B 侧 2 个进程 → C 侧 1 个 JVM**（`FavoriteServiceApplication`）。3 个 fact 组件**全部**落成域内组件：dispatch / authority 落 `wiring/`；legacy-preflight 拆成 **`service/`（报表计算）+ `wiring/`（Actuator 触发入口）** ⇒ **缺口已闭合**，见 §1.6。

---

### 1.2 api 组件（B 侧 `advice + auth + client + config + controller + dto + interceptor + service`）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `favorite-api/…/api/FavoriteApiApplication.java` | `service/favorite/src/main/java/com/rag/favorite/FavoriteServiceApplication.java` | N:1 | B 侧 api 入口（`@SpringBootApplication` + `SpringApplication.run`）与 rpc 入口合并成 C 侧唯一 `@SpringBootApplication`（**D1**）。B 侧「装配上下文 + 注册路由」两步由 Spring DI + 注解式路由承担 |
| `favorite-api/…/api/advice/GlobalResponseAdvice.java` + `…/advice/GlobalExceptionHandler.java` | `service/favorite/src/main/java/com/rag/favorite/advice/FavoriteApiResponseAdvice.java` + `service/favorite/src/main/java/com/rag/favorite/advice/FavoriteApiExceptionHandler.java` | 1:N | 🔴 B 侧**没有**统一的响应 / 错误处理器全局钩子 ⇒ 响应信封**逐个 Service 手写**（`favorite-api/…/api/service/FavoriteCreateFolderService.java`）。C 侧改成 advice 后**只包一层**（`body == null` 或 `body instanceof Response` 放行），否则双层信封 |
| `favorite-api/src/main/resources/application.yml` | `service/favorite/src/main/resources/application.yml` | N:1 | B 侧 api yaml 与 rpc yaml 两份合并成 C 的一份。⚠️ 端口值未逐字照抄（B api=`${FAVORITE_API_PORT:18893}`、rpc=`${FAVORITE_RPC_PORT:8082}`；C HTTP=`8894`、gRPC=`9003`）⇒ 属**部署层（L3）**，L1 契约（前缀 / 码 / 表名 / 字段号）不受影响 |
| `favorite-api/…/api/config/FavoriteApiProperties.java` | `service/favorite/src/main/java/com/rag/favorite/config/FavoriteApiProperties.java` | 1:1 | B 侧配置段 = `UserAuth` + `FavoriteRpc` + `BizRedis` ⇒ C 只保留 `UserAuth{accessSecret,accessExpire}`。`FavoriteRpc` 因域内直连（**D2**）、`BizRedis` 因 Spring Boot 标准 `spring.data.redis` ⇒ 不再是本域配置源 |
| `favorite-api/…/api/controller/FavoriteController.java` + `favorite-api/…/api/config/WebMvcConfig.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/config/WebMvcConfig.java` | 1:N | B 侧 7 个端点（`FavoriteController.java:129,136,143,156,167,174,191`）→ `@RequestMapping("/favorite/v1")` + 方法映射；B 的「黑名单中间件 + JWT 中间件」双层 → `WebMvcConfig.addInterceptors`。⚠️ **L3 顺序**：B 侧 JWT 在 chain **外层**（先跑）、blacklist 在**内层** ⇒ C 的 Interceptor 注册顺序必须保持「JWT → blacklist」 |
| `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteCreateFolderService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | B 侧 handler 链路：解析 body → 失败回 **HTTP 200 + `code:5003`** → 调 Service → 包信封。C 侧 7 个 api 用例合并进 1 个 `FavoriteApiService`（用例内聚判据，§5.4） |
| `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteCreateItemService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | 同上 |
| `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteDeleteFolderService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | 同上。⚠️ 该用例的 `Success: true` 是**硬编码**（`favorite-api/…/api/service/FavoriteDeleteFolderService.java`）⇒ C 侧不得把 `success` 改成「业务结果标志」 |
| `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteDeleteItemService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | 同上 |
| `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteListFoldersService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | ⚠️ **GET 端点仍解析并校验 body**：解析失败 ⇒ HTTP 200 + `5003`；B 侧**未建** `GetFavoriteFoldersReq` DTO（等价于**空 body 契约**）⇒ 不得跳过解析直接 200 |
| `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteListItemsService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | ⚠️ `GetFavoritesByFolderReq.FolderId` 走 **`form:"folderId,string"`**（`favorite-api/…/api/dto/GetFavoritesByFolderReq.java:11,33`）⇒ **取自查询串**（非 JSON body），且带 `,string` 解析（非法值 ⇒ `5003`） |
| `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteUpdateFolderNameService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | 同上 |
| `favorite-api/…/api/service/FavoriteApiSupport.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiSupport.java` | 1:1 | 4 个职责：`userId`（**5 个类型分支**，`json.Number` 是主路径）/ `logInfo` / `logBusinessErr` / `invokeRpc` + `nullResponse` / `nullOrUnsuccessfulResponse`。⚠️ 错误码映射是**唯一的 rpc 错误码入口** |
| `favorite-api/…/api/service/FavoriteCreateFolderService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | 7 个 api 用例（api 侧）→ 1 个 `FavoriteApiService`。骨架：起 span `FavoriteAPI.Xxx` → `userId` 提取失败即 `5004` → 调 rpc → nil 守卫 ⇒ `5001` → 成功日志 → 返回 `Success=200` |
| `favorite-api/…/api/service/FavoriteCreateItemService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | ⚠️ `title`/`cover` **原样**传给 rpc（api 侧不规范化）；日志用的 `TrimSpace(TargetId)` 与实参**不一致**是 B 侧既有事实，不要「顺手统一」 |
| `favorite-api/…/api/service/FavoriteDeleteFolderService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | ⚠️ `!rpcResp.Success` 守卫**只存在于 3 个写操作**；两个 create 与两个 list **只有 nil 守卫** ⇒ 这处不对称本身是语义 |
| `favorite-api/…/api/service/FavoriteDeleteItemService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | 同上 |
| `favorite-api/…/api/service/FavoriteListFoldersService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | 逐项投影 `FolderId/Name/CreatedAt/UpdatedAt` |
| `favorite-api/…/api/service/FavoriteListItemsService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | 逐项投影 7 字段 |
| `favorite-api/…/api/service/FavoriteUpdateFolderNameService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | `Success: true` 硬编码 |
| `favorite-api/…/interceptor/FavoriteJwtAuthInterceptor.java` | `service/favorite/src/main/java/com/rag/favorite/interceptor/FavoriteJwtAuthInterceptor.java` | 1:1 | 🔴 **favorite 域独有**（comment / follow 无此目录）。语义 4 条：① 缺头 / 空 token ⇒ **fail-open** 直接 `next`；② Redis 故障 ⇒ **fail-open**；③ 401 的 body **只有 `code`+`msg` 两字段、没有 `data`**；④ 用的码是 favorite 自己的 `ErrorUnauthorized=5004`。key = `user:jwt_blacklist:%s`（`favorite-api/…/interceptor/FavoriteJwtAuthInterceptor.java:107`） |
| `favorite-api/…/client/FavoriteRpcClient.java` + `…/api/service/FavoriteApiSupport.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiSupport.java` | 1:N | 三件事：B 侧的 Redis 客户端 + gRPC 客户端构造 + 黑名单中间件。C 侧：Redis 走标准配置、blacklist 走 Interceptor、**`@GrpcClient` 客户端文件因域内直连（**D2**）而消失** |
| `favorite-api/…/api/dto/CreateFavoriteFolderReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/folder/CreateFavoriteFolderReq.java` | 1:1 | `name` 字段（JSON `name`），必填由 `assertGoRequiredFields()` 承担 |
| `favorite-api/…/api/dto/CreateFavoriteFolderResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/folder/CreateFavoriteFolderResp.java` | 1:1 | `folderId` ⇒ **字符串化**（JSON `folderId` 为字符串），Java 侧 `Long.toString(id,10)` |
| `favorite-api/…/api/dto/CreateFavoriteReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/favorite/CreateFavoriteReq.java` | 1:1 | 5 字段；`title`/`cover` 可选 |
| `favorite-api/…/api/dto/CreateFavoriteResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/favorite/CreateFavoriteResp.java` | 1:1 | `favoriteId` ⇒ **字符串化** |
| `favorite-api/…/api/dto/DeleteFavoriteFolderReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/folder/DeleteFavoriteFolderReq.java` | 1:1 | `folderId` ⇒ **字符串化** |
| `favorite-api/…/api/dto/DeleteFavoriteFolderResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/folder/DeleteFavoriteFolderResp.java` | 1:1 | `success` 布尔 |
| `favorite-api/…/api/dto/DeleteFavoriteReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/favorite/DeleteFavoriteReq.java` | 1:1 | `favoriteId` ⇒ **字符串化** |
| `favorite-api/…/api/dto/DeleteFavoriteResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/favorite/DeleteFavoriteResp.java` | 1:1 | `success` 布尔 |
| `favorite-api/…/api/dto/FavoriteFolder.java` | `service/favorite/src/main/java/com/rag/favorite/dto/folder/FavoriteFolder.java` | 1:1 | 🔴 **白名单投影**：proto `FavoriteFolder` 有 5 字段（含 `user_id`），HTTP DTO **只有 4 个**（`favorite-api/…/api/dto/FavoriteFolder.java:33-42`）⇒ `user_id` 永不出 api 边界 |
| `favorite-api/…/api/dto/FavoriteItem.java` | `service/favorite/src/main/java/com/rag/favorite/dto/favorite/FavoriteItem.java` | 1:1 | 同上：proto 8 字段 → HTTP **7 字段**（丢 `user_id`，`favorite-api/…/api/dto/FavoriteItem.java`） |
| —（B 侧无此 DTO） | `service/favorite/src/main/java/com/rag/favorite/dto/folder/GetFavoriteFoldersReq.java` | C 新增 | B 侧**未建**该 DTO（等价于**空 body 契约**）⇒ C 侧必须补一个空 record，复刻「GET 端点也解析并校验 body」的 L4 行为 |
| `favorite-api/…/api/dto/GetFavoriteFoldersResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/folder/GetFavoriteFoldersResp.java` | 1:1 | `folders` 数组（JSON `folders`） |
| `favorite-api/…/api/dto/GetFavoritesByFolderReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/favorite/GetFavoritesByFolderReq.java` | 1:1 | 🔴 `folderId` 走 **`form:"folderId,string"`** ⇒ **查询参数**（不是 JSON body）+ `,string` 解析 |
| `favorite-api/…/api/dto/GetFavoritesByFolderResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/favorite/GetFavoritesByFolderResp.java` | 1:1 | `favorites` 数组（JSON `favorites`） |
| `favorite-api/…/api/dto/UpdateFavoriteFolderNameReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/folder/UpdateFavoriteFolderNameReq.java` | 1:1 | `folderId` + `name` |
| `favorite-api/…/api/dto/UpdateFavoriteFolderNameResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/folder/UpdateFavoriteFolderNameResp.java` | 1:1 | `success` 布尔 |

---

### 1.3 rpc 组件（B 侧 `bootstrap + client + config + domain + exception + fact + mapper + metrics + service`）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `favorite-rpc/…/rpc/FavoriteRpcApplication.java` | `service/favorite/src/main/java/com/rag/favorite/FavoriteServiceApplication.java` | N:1 | B 侧 rpc 入口（`@SpringBootApplication` + `SpringApplication.run`）与 api 入口合并（**D1**）。B 侧的 **v2 启动拒绝门禁**落到 `FavoriteSubjectRefGate` |
| `favorite-rpc/…/rpc/bootstrap/FavoriteSubjectRefGate.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteSubjectRefGate.java` | 1:1 | 门禁判据：`SubjectRefV2Facts` 开启且**非** dev/test 模式 ⇒ 拒绝启动。⚠️ 模式取值域由 Spring profile 映射（未决项 U3） |
| `favorite-rpc/src/main/resources/application.yml` | `service/favorite/src/main/resources/application.yml` | N:1 | 与 api yaml 合并（见 §1.2 同名行） |
| `favorite-rpc/…/rpc/config/FavoriteRpcProperties.java` | `service/favorite/src/main/java/com/rag/favorite/config/FavoriteRpcProperties.java` | 1:1 | B 侧配置段 = gRPC server + Postgres + `UserRpc` + `ArticleRpc` + `SubjectRefV2Facts`。C 侧多出 `Fact{dispatch,authority,legacyPreflight}` 三个嵌套段（承载 3 个 fact 组件的环境变量） |
| `favorite-rpc/…/rpc/service/FavoriteDomainService.java` + `…/rpc/client/UserRpcClient.java` + `…/rpc/client/ArticleRpcClient.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` + `service/favorite/src/main/java/com/rag/favorite/client/UserRpcClient.java` + `service/favorite/src/main/java/com/rag/favorite/client/ArticleRpcClient.java` | 1:N | B 侧装配根：DB 初始化 → Model 构造 + `UserRpc` + `ArticleRpc`。C 侧无 `ServiceContext` 类 ⇒ Spring DI 取代 |
| `favorite-rpc/…/rpc/service/impl/FavoriteServiceGrpcImpl.java` | `service/favorite/src/main/java/com/rag/favorite/grpc/FavoriteGrpcService.java` | 1:1 | 7 个方法，每个 2 行：new logic + 转发 |
| `favorite-api/…/api/client/FavoriteRpcClient.java` | `service/favorite/src/main/java/com/rag/favorite/grpc/FavoriteGrpcService.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | B 侧的 **gRPC 客户端 stub**（被 api 侧装配引用）。C 侧因域内直连（**D2**）⇒ `@GrpcClient` 客户端文件消失，客户端 stub 的职责落成「服务端实现 + api 侧直连调用」两处 |
| `favorite-rpc/…/rpc/metrics/FavoriteRpcMetrics.java` | `service/favorite/src/main/java/com/rag/favorite/metrics/FavoriteRpcMetrics.java` | 1:1 | 4 个 CounterVec + 3 个 Observe 函数。**指标完整名与 label 是 L1 观测契约**，见 §6 |
| `favorite-rpc/…/rpc/config/FavoriteRpcLabels.java` | `service/favorite/src/main/java/com/rag/favorite/config/FavoriteRpcLabels.java` | 1:1 | `folderModule="favorite_folder"` / `itemModule="favorite_item"` / action 5 值 / result 2 值。⚠️ 这些字符串是**运维契约**，拼错 ⇒ 告警静默失效 |
| `favorite-rpc/…/rpc/service/FavoriteRpcSupport.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteRpcSupport.java` | 1:1 | normalize / toProto / isUniqueViolation 等静态方法 |
| `favorite-rpc/…/rpc/service/FavoriteUserGuard.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteUserGuard.java` | 1:1 | 跨域身份校验，被 2 个 rpc 用例调用 |
| `favorite-rpc/…/rpc/service/FavoriteArticleSnapshotResolver.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteArticleSnapshotResolver.java` | 1:1 | 文章公开修订冻结；依赖 `ArticleRpc.GetArticle(PublicOnly:true, IncrView:false)` |
| `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 7 个 rpc 用例 → 1 个 `FavoriteDomainService`（AGENTS.md §4.1 锁定的形态） |
| `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `favorite-rpc/…/rpc/domain/FavoriteFolderEntity.java` + `…/rpc/domain/FavoriteItemEntity.java` | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteFolderEntity.java` + `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteItemEntity.java` | 1:N | B 侧两个 `@TableName` Entity（`FavoriteFolderEntity.java:19` / `FavoriteItemEntity.java:30`）⇒ 2 个 `@TableName` Entity。⚠️ 唯一键 / 可空性 / `target_id varchar(255)` 逐列对拍（DDL 探针） |
| `favorite-rpc/src/main/resources/db/migration/V1__create_favorite_folder.sql` … `V7__align_favorite_outbox_time_columns_with_go.sql` + `favorite-rpc/…/rpc/bootstrap/FavoriteSchemaGate.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteSchemaGate.java` + `service/favorite/src/main/resources/db/migration/V1__create_favorite_folder.sql` … `V7__align_favorite_outbox_time_columns_with_go.sql` | 1:N | ✅ **已落地（2026-11-04）**：7 个 Flyway 脚本**逐字零差异**（diff / sed 去注释 / tail-cmp 三层验证全过）⇒ B 侧原文即 C 侧文件末段连续字节后缀，仅文件头新增 C 侧落点注释。`FavoriteSchemaGate` 负责**启动后校验**「容易静默丢失」的 schema 资产（CHECK / 部分索引 / 2 个不可变触发器 / `favorite_legacy_fact_marker` 整表）⇒ **不抛异常、不阻止启动**（B 侧是 fail-fast 退出进程，C 侧单 JVM 里还跑着别的组件 ⇒ 刻意降级为告警） |
| `favorite-rpc/…/rpc/mapper/FavoriteFolderMapper.java` + `…/rpc/mapper/FavoriteItemMapper.java` + `…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteFolderMapper.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteItemMapper.java` + `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | 1:N | B 侧 Model 8 方法（7 读 + `InsertFolder`）→ 2 个 Mapper + DomainService |
| `favorite-rpc/…/rpc/mapper/JsonbTypeHandler.java` | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`（**共享层，不在本域**） | 1:1 | **D3**：B 侧 favorite 域内带一份 jsonb type handler 副本 ⇒ C 侧归入 `common/` starter；⚠️ C 侧 favorite 域**不建**此文件（域内 `mapper/` 包只有 4 个 Mapper） |
| `favorite-rpc/…/rpc/domain/FavoriteFactOutboxEntity.java` + `…/rpc/domain/FavoriteLegacyFactMarkerEntity.java` + `…/rpc/mapper/FavoriteFactOutboxMapper.java` + `…/rpc/mapper/FavoriteLegacyFactMarkerMapper.java` + `…/rpc/service/FavoriteFactEnvelopeFactory.java` + `…/rpc/service/FavoriteAssertionSchemaResolver.java` + `…/rpc/service/FavoriteFactWriteService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteFactOutboxEntity.java` + `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteLegacyFactMarkerEntity.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteFactOutboxMapper.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteLegacyFactMarkerMapper.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteFactEnvelopeFactory.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteAssertionSchemaResolver.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteFactWriteService.java` | 1:N | 🔴 **B 侧 1 组文件拆成 7 个 Java 文件**：outbox / legacy marker 两张表的 Entity + **两张表的 Mapper** + 冻结信封单点构造 + 断言版本解析 + **3 个事务写的独立 Bean**。拆 `FavoriteFactWriteService` 的原因是 **`@Transactional` self-invocation 静默失效**（AGENTS.md §3.3） |
| `favorite-rpc/…/rpc/service/FavoriteDeliveryValidator.java` + `…/rpc/service/FavoriteFactDispatchService.java` + `…/rpc/domain/FavoriteFactOutboxEntity.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteDeliveryValidator.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteFactDispatchService.java` + `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteFactOutboxEntity.java` | 1:N | wire event / receipt / DC sender / 投递一次。⚠️ 投递路径用 `TransactionTemplate` **显式 commit**（B 侧是提交后才返回错误）⇒ 不用 `@Transactional` |
| `favorite-rpc/…/rpc/domain/FavoriteAuthorityFact.java` + `…/rpc/service/FavoriteAuthorityLookupService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/model/FavoriteAuthorityFact.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteAuthorityLookupService.java` | 1:N | 严格 JSON / JCS hash / 权威事实校验。🔴 v1 的 `subject_ref` 是 **3 字段** `{authority_id, tenant_id, subject_id}` ⇒ **不能用** `common/communityfact` 的 v2 形状（2 字段） |
| `favorite-rpc/…/rpc/fact/FavoriteLegacyPreflightRunner.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteLegacyPreflightService.java` + `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteLegacyPreflightEndpoint.java` + `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteLegacyFactMarkerEntity.java`（**数据载体**） | 1:N | ✅ **已落地（2026-11-05）**：B 侧的**只读 RR 快照报表**拆成 2 个 C 侧文件 —— `service/` 放**报表计算**（逐条照抄 Go `favorite_legacy_preflight.go:30-96` + B 侧 `:82-231`）· `wiring/` 放**触发入口**（Actuator `@WebEndpoint`，决策 P6-c 拍板 ①）。详见 §1.6 |
| `favorite-rpc/…/rpc/exception/FavoriteExceptions.java` | `service/favorite/src/main/java/com/rag/favorite/exception/FavoriteExceptions.java` | 1:1 | B 侧的 `ErrorNotFound` 哨兵族 → Java 异常类型族。⚠️ 哨兵**不自带码翻译**，由用例层决定 |
| `favorite-rpc/…/rpc/fact/FavoriteAuthorityEndpointConfig.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteAuthorityEndpointConfig.java` | 1:1 | 🔴 B 侧 favorite **自持** authority handler，形态是 **path 参数**：`GET /internal/v1/favorite/facts/{producer}/{event_id}` + `GET /internal/v2/...` ⇒ **不能用** B 侧通用 `AuthorityHandlerFactory` 的 query 形态。C 侧包位从 `fact/` 迁到 `wiring/`（装配层唯一落点） |
| `favorite-rpc/…/rpc/fact/FavoriteFactDispatchScheduler.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteFactDispatchScheduler.java` | 1:1 | 见 §1.6 |
| `favorite-rpc/…/rpc/fact/FavoriteAuthorityEndpointConfig.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteAuthorityEndpointConfig.java` | 1:1 | 见 §1.6 |
| `favorite-rpc/…/rpc/fact/FavoriteLegacyPreflightRunner.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteLegacyPreflightEndpoint.java` | 1:N | ✅ **有落点**（**触发入口**）：Actuator `@WebEndpoint(id = "favorite-legacy-preflight")` + `@ConditionalOnProperty(favorite.fact.legacy-preflight.enabled=true)` ⇒ **默认不注册**（404）⇒ 不会被健康检查探活误触发。见 §1.6 |
| `favorite-proto/src/main/proto/favorite.proto` | `proto/src/main/proto/favorite.proto` | 1:1 | **protoc 生成物 ↔ `.proto` 源**。B 侧的 `.proto` 在 `favorite-proto` 模块内，被 api 与 rpc 共用 |
| `favorite-proto/src/main/proto/favorite.proto`（service 段） | `proto/src/main/proto/favorite.proto`（service 段） | 1:1 | 7 个 RPC ⇒ 方法路径 `/favorite.FavoriteService/*` |

---

### 1.4 common 组件（B 侧 `favorite-common/`：`errmsg/` 3 类 + `span/` 1 类）

> 🔴 **本域是唯一「只有常量 + 文案表 + 反查索引 + `GRPCError` + `BizCodeFromError`，**无 `CodeError` 类型**」的域。**

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `favorite-common/…/common/errmsg/FavoriteErrorCode.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` | 1:1 | **24 个常量**整表保留：Success=200 / Error=500 / CodeServerBusy=1015 / 5001–5009 / 5101–5105 / 5201–5203 / 5301–5304（`favorite-common/…/common/errmsg/FavoriteErrorCode.java:41` 起）。⚠️ 6 个常量在域内无直接调用点但**不可删**（反查表的基础） |
| `favorite-common/…/common/errmsg/FavoriteErrorMessages.java` | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 1:1 | 24 条文案逐字照抄，**含语言**：23 条中文 + 🔴 **5002 = 英文 `not found`**（`favorite-common/…/common/errmsg/FavoriteErrorMessages.java:52`，本域唯一英文）+ `OK` / `FAIL`。未命中回退 `"FAIL"`（回退值本身是 L1，`favorite-common/…/common/errmsg/FavoriteErrorMessages.java:48,86`） |
| `favorite-common/…/common/errmsg/FavoriteGrpcError.java` | `service/favorite/src/main/java/com/rag/favorite/contract/GrpcErrorMapper.java` | 1:1 | 🔴 **互逆往返**：`grpcError` 写的 description 是**业务文案**（不是码数字），`bizCodeFromError` 靠文案反查 ⇒ **业务码在 gRPC status 的 code 字段里是「丢失」的**。反查顺序：**先 message、后 gRPC 标准码**（InvalidArgument→5003 / Unauthenticated→5004 / PermissionDenied→5005 / AlreadyExists→5006 / NotFound→5002 / Internal→5001 / default→1015，`favorite-common/…/common/errmsg/FavoriteGrpcError.java:193-194`）。⚠️ 反查表构建**必须 `putIfAbsent`**（与 B 侧 map 遍历顺序等价，`:139`） |

---

### 1.5 proto 组件

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `favorite-proto/src/main/proto/favorite.proto` | `proto/src/main/proto/favorite.proto` | 1:1 | `package favorite` 两侧逐字一致 ⇒ 方法路径零 wire 偏离。7 个 message + `service FavoriteService`（7 个 RPC）的**字段号 / 字段名 / 类型逐条不改**。C 侧只改 option 块（`go_package` → `java_package` + `java_multiple_files`） |

---

### 1.6 三个 fact 组件（dispatch / authority / legacy-preflight）各自的落点

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `favorite-rpc/…/rpc/fact/FavoriteFactDispatchScheduler.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteFactDispatchScheduler.java` | 1:1 | ✅ **有落点**。B 侧的 `-once` 开关降级为配置项（决策 P6-a 已选 ①）；节奏 **1s** / 批次 **16** / 单次 **10s deadline** 逐条保留 |
| `favorite-rpc/…/rpc/fact/FavoriteAuthorityEndpointConfig.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteAuthorityEndpointConfig.java` | 1:1 | ✅ **有落点**。wire 形态按 **B 侧原样**（path 参数）；⚠️ B 侧是**独立 HTTP 端口**（`FAVORITE_AUTHORITY_LISTEN`），C 并入主 app 端口（决策 P6-b 已选 ①）⇒ **wire 面变更，未拍板**（未决项 U1） |
| `favorite-rpc/…/rpc/fact/FavoriteLegacyPreflightRunner.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteLegacyPreflightService.java`（**报表计算**）+ `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteLegacyPreflightEndpoint.java`（**触发入口**） | 1:N | ✅ **有落点（2026-11-05 · 决策 P6-c 拍板 ① Actuator 自定义端点）**。B 侧 1 个类拆成 2 个 C 侧文件：① `service/` = **报表计算**（`Report` 为 immutable record，5 个字段名与顺序由 `@JsonProperty` 钉死，与 Go `favorite_legacy_preflight.go:30-96` 逐字同形）② `wiring/` = **Actuator `@WebEndpoint(id="favorite-legacy-preflight")`**（`@ConditionalOnProperty(favorite.fact.legacy-preflight.enabled=true)` ⇒ **默认不注册** ⇒ 404 ⇒ 不会被健康检查探活误触发）<br>⚠️ **退出码语义的替代**：B 侧/Go 的 `exit 2` = 未裁决 / `exit 1` = 读取失败 / `exit 0` = clear ⇒ Spring 进程内无退出码 ⇒ C 侧改用 **HTTP 状态码**（**200** = clear / **409** = 未裁决 / **500** = 读取失败）+ **结构化日志**双通道表达 ⇒ 登记 `DEV-FAV-RPC-009`<br>⚠️ **刻意不加 `@Transactional`**：B 侧 `preflight()` 的注解在，但**只被同 bean 的 `run()` 自调用** ⇒ Spring AOP 代理被绕过 ⇒ 事务从未生效 ⇒ 按 D16「继承 B 侧既有差异」照抄 ⇒ 登记 `DEV-FAV-RPC-008` |

> 📌 **为什么 dispatch / authority 能合并而 legacy-preflight 不能直接合并**：B 侧把三者做成**独立进程**是**架构意图**（dispatch 的目的是「favorite RPC 可用性不依赖 DC」），不是技术约束 ——
> dispatch / authority **零 gRPC 注册**、与主 gRPC server 共用同一张 PG；legacy-preflight 更是纯只读 ⇒ 合并无写风险，但**入口形态**（一次性 vs 常驻）不同。

### 1.7 决策锁 —— §1 的改动清单

**换包结构（B 侧的 api / rpc / common / proto 四模块 → C 的 1 模块 + 技术角色包树）7 处同时改**：
1. `favorite-api` + `favorite-rpc` 合成 `favorite-service`；`favorite-common` + `favorite-proto` 合成域内 `contract/` 包 + 根级 `proto/` 模块；
2. B 侧的 `{logic,model,server,svc,config,metrics}` 式内部分层在 C 侧改成 `{advice,config,controller,dto,interceptor,service}/{controller,service,dto,advice,config,interceptor}` + `{wiring,client,config,domain/entity,domain/model,domain/service,exception,grpc,mapper,metrics,service}/{service,domain,mapper,wiring,config,client,metrics,exception}`；
3. B 侧的 model 层（Entity + Model）→ C 的 `domain/entity + domain/model`（5 个 Entity）+ `mapper`（4 个 Mapper）+ `domain/service + service`（10 个 Service）；
4. B 侧的 `fact/FavoriteAuthorityEndpointConfig.java` 从 `fact/` 迁到 C 的 `wiring`（装配层唯一落点）；
5. B 侧 `fact/` 包的 3 个组件从**进程入口**变成**域内组件**（§1.6）；
6. B 侧 `favorite-common` 的 `errmsg/` 3 类拆成 C 的 `contract/{ErrCodes,ErrMsg,GrpcErrorMapper}`；
7. B 侧 `favorite-proto` 的 `.proto`（生成物）变成 C 的根级 `proto/` 模块的 `favorite.proto` + protoc 插件段。

**为什么换**：B 侧的目录结构是脚手架 codegen 的产物（api / rpc 用例文件多是因为 codegen，不是职责边界）；Spring 侧的「一域一 app」要求 HTTP 边界（`advice + config + controller + dto + interceptor + service`）与服务端能力层（`wiring + client + config + domain/entity + domain/model + domain/service + exception + grpc + mapper + metrics + service`）在同一进程内**用包区分**而不是用进程区分。⚠️ **代价**：`rpc.*` 这层命名债已登记（决策 P4，本次不改）。

### 1.8 🔴 RAG 侧新增类补录（3 条 · 覆盖 6 个 `.java`）

> ⚠️ **本节是 2026-11-01 的增量**：RAG 在首版映射表之后新增了 `favorite-api/…/auth/` 包（4 类）、`FavoriteGoQueryLimitInterceptor`（1 类）与 `FavoriteSpans`（1 类）
> ⇒ 首版 §1.2 / §1.3 的 **B 列漏了这 6 个 `.java`** ⇒ 本节逐个落到 C 侧；
> 补齐后 `tools/check-mapping-coverage.py` 的 **B 列双向对账归零**。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `favorite-api/…/auth/FavoriteJwtAuthPaths.java` ＋ `favorite-api/…/auth/FavoriteJwtSignatureVerifier.java` ＋ `favorite-api/…/auth/FavoriteJwtVerificationResult.java` ＋ `favorite-api/…/auth/GoStyleJwtAuthFilter.java` | `service/favorite/src/main/java/com/rag/favorite/interceptor/FavoriteJwtAuthInterceptor.java` | N:1 | ⚠️ RAG 把 JWT **验签**拆成两段（`DRIFT-USERAPI-009②` 的修法）：验签在 **Servlet Filter**（3s 预算**之外**）＋ 黑名单/响应在 **HandlerInterceptor**（预算**之内**）⇒ C 侧**只有 1 个 `HandlerInterceptor`** ⇒ 🔴 **C 侧缺口**：要么补 1 个 `OncePerRequestFilter` 承载验签，要么显式登记为签字偏离。⚠️ **受保护路由表 ＋ public 前缀集合是 L1** |
| `favorite-api/…/interceptor/FavoriteGoQueryLimitInterceptor.java` | `service/favorite/src/main/java/com/rag/favorite/interceptor/QueryLimitInterceptor.java` | 1:1 | ✅ **已落地（2026-11-01）**：C 侧新建 `interceptor/QueryLimitInterceptor.java`（**类名去掉了语言前缀**，用户 2026-11-01 定）⇒ **这一层不再靠 Tomcat `server.tomcat.max-parameters` 兜底**；⚠️ 「总参数个数 vs 非空值个数」的口径差异（`DRIFT-HOTAPI-030`）**仍未决** ⇒ 移植实现时**必须先定口径**，否则 favorite 域会出现「B 拒 / C 不拒」 |
| `favorite-common/…/span/FavoriteSpans.java` | `service/favorite/src/main/java/com/rag/favorite/metrics/FavoriteRpcMetrics.java` | 1:1 | B 侧把 span 名常量从指标类里**拆出**（且落在 `favorite-common`）⇒ C 侧并回 `FavoriteRpcMetrics`；⚠️ 指标**完整名**带 `favorite_service_` 前缀，**不许补全** |

---

## 2. 持久层差异

| 维度 | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|
| ORM | MyBatis-Plus `@TableName` + `@TableId` + `BaseMapper` | MyBatis-Plus（沿用 B 列形态） | C 列 4 个 Entity 已在树里，注解待填 |
| Entity / Model 文件 | `domain/FavoriteFolderEntity` + `FavoriteItemEntity` | `service/favorite/src/main/java/com/rag/favorite/domain/entity + service/favorite/src/main/java/com/rag/favorite/domain/model` 下同样 2 个 | 一一对应 |
| 数据访问 | Mapper 接口（注解式原生 SQL，全仓零 XML） | `service/favorite/src/main/java/com/rag/favorite/mapper` 下 4 个 Mapper 接口 | — |
| jsonb 列 | `mapper/JsonbTypeHandler.java`（域内副本） | ⚠️ **C 侧 favorite 域无此文件** ⇒ 应取 `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`（共享层，**D3**） | B 的域内副本在 C 侧归位到 `common/` |
| Schema 演进 | Flyway 7 个脚本（`V1__create_favorite_folder.sql` … `V7__align_favorite_outbox_time_columns_with_go.sql`） | 🔴 `service/favorite/src/main/resources/db/migration/` **当前只有 `.gitkeep`** | ⚠️ **DDL 事实源收敛到 Flyway 脚本**；脚本独占的资产（CHECK / 部分索引 / **2 个不可变触发器** / `favorite_legacy_fact_marker` 整表）必须逐条搬过来 |
| 手写 SQL 折进 Flyway | `001_favorite_fact_outbox.sql` / `002_favorite_fact_delivery.sql` / `003_favorite_target_revision.sql` / `004_favorite_legacy_fact_marker.sql` → `V3`–`V6` | 🔴 **未落地**（同上） | 逐条对照见 §2.1 的「4 张表」小表 |
| 事务 | 3 个 `@Transactional` 写（`FavoriteFactWriteService`）+ 投递路径 `TransactionTemplate` | 同 B 列形态 | ⚠️ **`@Transactional` self-invocation 静默失效** ⇒ 事务写必须在**独立 Bean** |
| 投递路径的事务 | `TransactionTemplate` **显式 commit/rollback**（**不用 `@Transactional`**） | 待填 | ⚠️ `@Transactional` 会把 blocked / failed 状态一起回滚，而那正是 B 侧明确提交的内容 |
| 行锁 | `SELECT … FOR UPDATE`（收藏夹创建/删除锁点）；级联删除 `FOR UPDATE ORDER BY favorite_id` | 待填 | 排序保证确定性，否则两个事务各算一半 retract 集合、撞 `UNIQUE(favorite_id, aggregate_version)` |
| 雪花 ID | `common/spring/SnowflakeBean` | `common/` starter 自动配置（**D3**） | favorite **无游标** ⇒ 不依赖 `>>22` |
| 幂等 | 唯一键 + `UNIQUE(favorite_id, aggregate_version)` 双保险；`target_type` 小写化后参与 `uk_folder_target` | 待填 | ⚠️ 小写化必须用**逐 code point** 映射（B 侧 `toLowerCase` 是简单映射，`İ`→`i`；Java `toLowerCase(Locale.ROOT)` 是完整 Unicode 映射） |

### 2.1 4 张表逐表对照（B 侧出处 = DDL 事实源）

| 表名 | B 侧出处 | 关键约束 | C 侧落点 |
|---|---|---|---|
| `favorite_folder` | `favorite-rpc/src/main/resources/db/migration/V1__create_favorite_folder.sql:14,22,36,39` | `uk_user_folder_name(user_id,name)`；`name varchar(100) NOT NULL`；`create_time`/`update_time` | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteFolderEntity.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteFolderMapper.java` |
| `favorite_item` | `favorite-rpc/src/main/resources/db/migration/V2__create_favorite_item.sql:24,33,38` | `uk_folder_target(folder_id,target_id,target_type)`；`target_id varchar(255)`（雪花 ID > 2^53）；**无 `update_time`**；`target_revision varchar(96)` 可空 | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteItemEntity.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteItemMapper.java` |
| `favorite_fact_outbox` | `favorite-rpc/src/main/resources/db/migration/V3__create_favorite_fact_outbox.sql:14,42,47,57` | `CHECK (aggregate_version IN (1,2))`；部分索引 `favorite_fact_outbox_pending`；**不可变触发器 `favorite_fact_identity_immutable`**；5 个回执列 | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteFactOutboxEntity.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteFactOutboxMapper.java` |
| `favorite_legacy_fact_marker` | `favorite-rpc/src/main/resources/db/migration/V4__create_favorite_legacy_fact_marker.sql:21,33-51` | 5 个 CHECK + 复合 CHECK + 正则 CHECK + **不可变触发器 `favorite_legacy_marker_immutable`** | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteLegacyFactMarkerEntity.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteLegacyFactMarkerMapper.java` |

> ⚠️ **缺 `favorite_legacy_fact_marker` 表 ⇒ 错误码静默改变**：B 侧的 `favoriteAssertionSchema` 走 legacy 分支时会拿到 `relation … does not exist`，
> 那**不是** NotFound ⇒ 删除收藏返回 `Internal/5008` 而不是设计的 `FailedPrecondition/5304`。
> 而**只有「没有 v1 断言」的行会走到那里** ⇒ 新建收藏完全正常 ⇒ **只有历史数据才会暴露**。

### 2.2 决策锁 —— §2 的改动清单

**换持久层（GORM → MyBatis-Plus + 幂等 DDL 脚本）6 处同时改**：
1. B 侧 model 层的 2 个实体 → `domain/entity/FavoriteFolderEntity.java` + `FavoriteItemEntity.java`（`@TableName` + `@TableId` + `@TableField`）；
2. B 侧 `FavoriteModel` 8 方法 → `mapper/FavoriteFolderMapper.java` + `FavoriteItemMapper.java`（`BaseMapper` + 注解式原生 SQL，全仓零 XML mapper）；
3. B 侧 outbox / delivery / authority 三处的表访问 → `mapper/FavoriteFactOutboxMapper.java` + `FavoriteLegacyFactMarkerMapper.java`；
4. B 侧 Flyway `V1`–`V7` → `favorite/service/src/main/resources/db/migration/*.sql`（✅ **2026-11-04 已落地** · 7 个脚本 · SQL 正文逐字零差异）；
5. B 侧 `ErrorNotFound` 哨兵族 → `exception/FavoriteExceptions.java`；
6. jsonb 列的读写 → `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`（**D3** 归位；Entity 需 `@TableName(autoResultMap = true)` + `ps.setObject(i, json, Types.OTHER)`）。

**为什么换**：B 侧的 DDL 事实源必须**收敛到一处** ⇒ 幂等 DDL 脚本 + 启动后 `FavoriteSchemaGate` 校验是唯一能防止「schema 资产静默丢失」的方式。
⚠️ **两处刻意偏离 B 侧机制的表达**：① `@Transactional` 必须落在**独立 Bean**（self-invocation 静默失效）；② 投递路径用 `TransactionTemplate` **显式 commit**（`@Transactional` 会把 B 侧明确提交成功的 blocked / failed 状态一起回滚）。

---

## 3. HTTP / DI 差异

| 维度 | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|
| 进程拓扑 | **2 个进程**（`favorite-api` + `favorite-rpc`；`favorite-rpc` 内含 3 个 fact 组件） | **1 个进程**（`favorite-service`） | 决策 **D1** |
| 路由注册 | `@RequestMapping("/favorite/v1")` + 方法映射（`favorite-api/…/api/controller/FavoriteController.java:89`） | 同 B 列 | 前缀是 **L1**，逐字保留 |
| HTTP method | **POST×3 / DELETE×2 / GET×2**（非全 POST，`FavoriteController.java:129,136,143,156,167,174,191`） | 同 | `@DeleteMapping` / `@GetMapping` 是 Spring 一等公民 |
| public 路径集 | **空集**（7 条端点**全部**受保护，`favorite-api/…/api/auth/FavoriteJwtAuthPaths.java:16,53`） | 空集 | ⚠️ **C 侧 `FavoriteController.java` 的 javadoc 写「有 public 段」⇒ 与 B 侧相反**（见 §7 缺口表）<br>无 public 路由 ⇒ 无需白名单 |
| JWT + blacklist 顺序 | JWT 在 chain **外层**（先跑）→ blacklist 在内层（`favorite-api/…/api/interceptor/FavoriteJwtAuthInterceptor.java:46-58`） | Interceptor 顺序 | ⚠️ 顺序是 L3 语义，必须有断言（C 侧与 B 侧**同序**） |
| Handler / Controller | 1 个 `FavoriteController`（7 个 `@*Mapping`）+ 7 个 `Favorite*Service` | 1 个 `FavoriteController` + **1 个** `FavoriteApiService` | C 侧 7 个 api 用例合并（用例内聚判据） |
| 用例中间层 | api 7 Service / rpc 1 `FavoriteDomainService` | api 1 `FavoriteApiService` / rpc 1 `FavoriteDomainService` | 合并后 HTTP 与 gRPC **共享同一份业务用例** |
| 响应信封 | `advice/GlobalResponseAdvice` | `service/favorite/src/main/java/com/rag/favorite/advice/FavoriteApiResponseAdvice.java` | 🔴 只包一层；否则双层信封 |
| 错误表达 | 业务失败 ⇒ **HTTP 200 + body 的 `code`**；解析失败也是 **HTTP 200 + 5003**（`favorite-api/…/api/advice/GlobalExceptionHandler.java:37,44`） | `service/favorite/src/main/java/com/rag/favorite/advice/FavoriteApiExceptionHandler.java` | 🔴 B 侧**没有**框架级错误处理器 ⇒ 框架级错误走框架默认语义（400 + text/plain），**业务错误不走那里** |
| 业务码 → 响应码 | `FavoriteErrorMessages.getMsg`（`favorite-common/…/common/errmsg/FavoriteErrorMessages.java:86`） | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 🔴 文案是 **L1**，含语言 |
| gRPC 错误表达 | `FavoriteGrpcError.grpcError(Status.Code,int)`（`favorite-common/…/common/errmsg/FavoriteGrpcError.java:94`） | `service/favorite/src/main/java/com/rag/favorite/contract/GrpcErrorMapper.java` | ⚠️ **标准 gRPC 码 + 查表文案**（**不是** like 域那种「业务码塞进 grpc-status」的做法） |
| 业务异常 | **无 `CodeError` 类型** ⇒ 直接抛 gRPC 异常 | `FavoriteExceptions`（rpc 侧，`favorite-rpc/…/rpc/exception/FavoriteExceptions.java:42`） | C 侧落点 `service/favorite/src/main/java/com/rag/favorite/exception/FavoriteExceptions.java`<br>🔴 哨兵不带码翻译 ⇒ 由用例层决定 |
| 配置绑定 | `@ConfigurationProperties(prefix="favorite-api")` / `("favorite")` | `service/favorite/src/main/java/com/rag/favorite/config/FavoriteApiProperties.java` + `service/favorite/src/main/java/com/rag/favorite/config/FavoriteRpcProperties.java` | 类型安全 |
| 域内调用 | api → rpc **走 gRPC**（`@GrpcClient` + Nacos 寻址） | **直连**（**D2**）⇒ C 侧**无** `FavoriteRpcClient` | — |
| 跨域调用 | `UserRpcClient` / `ArticleRpcClient`（`@GrpcClient` + Nacos） | `service/favorite/src/main/java/com/rag/favorite/client/UserRpcClient.java` + `service/favorite/src/main/java/com/rag/favorite/client/ArticleRpcClient.java` | 决策 **D6**：跨域仍走 gRPC |
| gRPC 拦截器 | `GrpcServerConfigurer.intercept()` **显式挂上** | 待填（`common/src/main/java/com/rag/common/autoconfigure/GrpcExceptionMapperAutoConfiguration.java` + `ObservabilityAutoConfiguration`） | ⚠️ devh starter **不自动注册** ⇒ 漏配 = **静默失效** |
| 服务发现 | Nacos | Nacos（`service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteNacosRegistration.java`） | 生态替代（**D4**）已完成 |
| gRPC 反射 | `grpc.server.reflection-service-enabled` | `application.yml` 写 `false` | 与 B 侧的「非 dev/test 不开」一致 |
| v2 启动门禁 | `bootstrap/FavoriteSubjectRefGate.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteSubjectRefGate.java` | ⚠️ 模式取值域不在本仓库（U3） |
| 进程级 logger 身份 | 多个独立 `service` 值（`rtw-favorite-fact-dispatch` / `-fact-authority` / `-legacy-preflight` / gRPC 走 `c.Name`） | `bootstrap/FavoriteFactProcessLogging.java` | C 侧落点 `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteFactProcessLogging.java`<br>⚠️ **不要用 SLF4J 全局 MDC**（会跨线程插值） |
| Schema 闸 | `bootstrap/FavoriteSchemaGate.java`（**不抛异常、不阻止启动**） | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteSchemaGate.java` | C 侧单 JVM 里还跑着别的组件 ⇒ 刻意降级为告警 |

### 3.1 决策锁 —— §3 的改动清单

**换进程拓扑（B 侧 2 进程 → C 1 JVM）6 处同时改**：
1. `favorite-api` + `favorite-rpc` 两个模块合成 `favorite-service` 一个模块；
2. `favorite-api/…/FavoriteApiApplication.java` 与 `favorite-rpc/…/FavoriteRpcApplication.java` 合成 `FavoriteServiceApplication.java`；
3. `favorite-api/…/client/FavoriteRpcClient.java` 删除 —— 域内调用改直连（**D2**）；
4. `favorite-common` + `favorite-proto` 合成 `favorite-contract`；
5. `favorite-api/…/advice/*` + `favorite-rpc` 侧错误类合成 `favorite-contract` 的 3 个类 + `exception/FavoriteExceptions.java`；
6. `favorite-rpc/…/rpc/fact/` 的 3 个组件（dispatch / authority / legacy-preflight）的落点改为域内组件 / 运维入口（见 §1.6）。

**为什么换**：Spring Boot 的「一域一 app」（**D1**）让多进程拓扑收敛为 1；代价是**失去独立扩缩容**（dispatch 的资源需求与 gRPC 争同一堆内存/CPU）、**多副本下 dispatcher 数量 = JVM 数**（安全，`FOR UPDATE SKIP LOCKED` 本就为多副本设计，但吞吐上限从「1 进程 × 16」变成「每 JVM 16 × N 副本」）、**进程边界变成配置开关**。

**换 HTTP 层形态（1 Controller + 7 Service → 1 Controller + 1 Service）5 处同时改**：
1. 删 `favorite-api/…/service/FavoriteCreateFolderService.java` 等 7 个 Service，合并进 `service/FavoriteApiService.java`；
2. `FavoriteController.java` 的 7 个端点方法改为直接调 `FavoriteApiService`；
3. `service/FavoriteApiSupport.java` 保留 `userId` / `logInfo` / `logBusinessErr` / `invokeRpc` 四件事（helper 不并进 Service）；
4. `dto` 补 `GetFavoriteFoldersReq.java`（B 侧未建 DTO ⇒ 空 body 契约）；
5. `advice` 两个类改名去掉 `Global` 前缀（`GlobalResponseAdvice` → `FavoriteApiResponseAdvice`，`GlobalExceptionHandler` → `FavoriteApiExceptionHandler`）。

**为什么换**：「用例内聚」判据 5 条全中（同一 `@RequestMapping` 聚合 / 变化原因一致 / 无独立生命周期 / 无独立契约 / 合并后职责不模糊）⇒ 合并为 1 个 Service 是**去掉脚手架 codegen 产物**的必然结果，不是「文件少」。

---

## 4. 公共模块对照（favorite 域 ↔ `common/` 的边界）

| 能力 | B RAG（`service/common/`，普通 jar） | C RAG-V1（`common/`，真 starter，**D3**） | favorite 侧接线位置 |
|---|---|---|---|
| **communityfact 事件协议** | `service/common/.../communityfact/{Wire,Dispatcher,AuthorityHandler,ProcessLogger}.java` | `common/src/main/java/com/rag/common/communityfact/*`（12 个类：`Wire` / `Event` / `TechnicalReceipt` / `DeliveryStatus` / `SubjectRef` / `AuthorityFact` / `CanonicalHash` / `Dispatcher` / `EventDispatcher` / `EventSender` / `ProcessLogger` / `ProcessMetadata` / `AuthorityHandler`） | 🔴 **协议本体归 `common.md`**；favorite 侧只写接线：`rpc/service/FavoriteFactEnvelopeFactory.java`（构造 `Wire.Event`）+ `rpc/service/FavoriteDeliveryValidator.java`（校验回执）+ `wiring/FavoriteFactDispatchScheduler.java`（跑 `Dispatcher`）+ `wiring/FavoriteAuthorityEndpointConfig.java`（挂 `AuthorityHandler`） |
| 社区事实 v1 形状 | `rpc/domain/FavoriteAuthorityFact.java` | `service/favorite/src/main/java/com/rag/favorite/domain/model/FavoriteAuthorityFact.java` | ⚠️ **不能用** `common` 的 `Wire.AuthorityFact`（其 `subject_ref` 只有 2 字段 `{issuer,subject_id}`）⇒ favorite **自持 v1 类型** |
| 权威端点 wire 形态 | `common/spring/AuthorityHandlerFactory.java` | `common/src/main/java/com/rag/common/communityfact/AuthorityHandler.java` | 🔴 B 侧 favorite **没用**通用 handler，自己写了 **path 参数**版（`favorite-rpc/…/rpc/fact/FavoriteAuthorityEndpointConfig.java`）⇒ favorite 侧**不能**直接用 `common` 的通用 handler |
| 信封 | `service/common/.../response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | `api/advice/FavoriteApiResponseAdvice.java` 引用 |
| 业务异常 | `service/common/.../exception/BusinessException.java` | `common/src/main/java/com/rag/common/exception/BusinessException.java` | `rpc/exception/FavoriteExceptions.java` |
| 鉴权原语 | `service/users/users-common/.../jwt/{JwtUtil,BearerTokenExtractor}.java` | `common/src/main/java/com/rag/common/security/{JwtUtil,BearerTokenExtractor}.java` | `api/interceptor/FavoriteJwtAuthInterceptor.java` |
| `,string` 解析 | `users-common/.../http/GoStyleStringInt64.java` | `common/src/main/java/com/rag/common/web/StringInt64.java` | `api/dto/*` 的 `folderId,string` / `favoriteId,string` 字段 |
| `form:` 解析 | `users-common/.../http/GoStyleGetFormValuesFilter`（或等价） | ⚠️ `common/src/main/java/com/rag/common/web/GetFormValuesFilter.java` **目前仍是空壳** | `api/dto/GetFavoritesByFolderReq.java`（`folderId` 取自**查询串**，`GET /item/list` 依赖它） |
| 雪花 ID | `service/common/.../snowflake/Snowflake.java` + `spring/SnowflakeBean.java` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `common/src/main/java/com/rag/common/autoconfigure/SnowflakeAutoConfiguration.java` | 仅 folder_id / favorite_id |
| 业务日志 | `service/common/.../logger/{Logger,WithUserID,WithArticleID}.java` | `common/src/main/java/com/rag/common/logger/{GoStyleLogger,LogOption,LogOptions}.java` | `api/service/FavoriteApiSupport.java` + `rpc/service/FavoriteRpcSupport.java` |
| SQL 超时 / `57014` | `service/common/.../persistence/{DeadlineQueryTimeoutInjector,SqlTimeoutStatusTranslator}.java` | `common/src/main/java/com/rag/common/persistence/*` | 隐式（starter 自动装配） |
| jsonb typeHandler | `favorite-rpc/.../mapper/JsonbTypeHandler.java`（**域内副本**） | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | ⚠️ C 侧 favorite 域**不建**此文件（**D3** 归位到共享层） |
| MyBatis-Plus 排序桥 | `favorite-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java`（域内副本，Boot 4 workaround） | `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | ⚠️ C 侧 favorite 域**不建**此文件（**D3** 收编 9 份重复） |
| 进程级 OBS 日志 | `favorite-rpc/.../bootstrap/FavoriteFactProcessLogging.java` | `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteFactProcessLogging.java` | ⚠️ 3 份独立 logger 身份（`favorite.rpc` / `rtw-favorite-fact-dispatch` / `rtw-favorite-fact-authority`） |

### 4.1 决策锁 —— §4 的改动清单

**把共享件从域内副本归位到 `common/` starter 4 处同时改**：
1. 删 `favorite-rpc/…/mapper/JsonbTypeHandler.java`，改引 `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`；
2. 删 `favorite-rpc/…/config/MybatisPlusOrderingBridgeAutoConfiguration.java`，改由 `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` 统一提供；
3. `favorite-api/…/advice/*` 的 `Response` / `BusinessException` / `JwtUtil` 改引 `common/` 下的同名件（`ApiResponse` / `BusinessException` / `JwtUtil`）；
4. `favorite-common` 里的 3 个 errmsg 类迁到域内 `contract/` 包（**域内**，**不进 `common/`** —— 错误契约**逐域隔离**）。

**为什么换**：**D3**（`service/common` → 真 starter）+ **D4**（生态优先）。但 🔴 **错误码 / 错误文案 / 反查表是逐域隔离的**（favorite 的 `5002` = `not found`，users 的 `5002` = 「更新数据库失败」）⇒ **这三类不进 `common/`**，留在域内 `contract/` 包。

---

## 5. 决策锁（全局）

| # | 决策 | 影响本域的 N 处 | 为什么换 |
|---|---|---|---|
| **D1** | api 与 rpc 合并为单一 Spring Boot 应用 | 6 处（§3.1） | Spring Boot 的「一域一 app」；代价是失去独立扩缩容 + 进程边界变配置开关 |
| **D2** | 域内调用直连，不再自我 gRPC 往返 | 3 处：删 `FavoriteRpcClient.java`；`FavoriteApiService` 直调 `FavoriteDomainService`；`favorite-api` 的 `FavoriteRpc` 配置项删除 | 同域往返 gRPC 是纯浪费 |
| **D3** | `service/common` → 真 starter | 4 处（§4.1） | 消除 9 份重复的 MP 桥与 10 份 `@Import` 清单 |
| **D4** | 生态优先，手写只补生态缺口 | 2 处：`FavoriteNacosRegistration`（注册中心）、`MybatisPlusOrderingBridge`（归 `common/`） | 少维护一套自研基建 |
| **D5** | DTO 改 immutable record + Bean Validation | 16 个 `api/dto/*.java` 全部 | B 侧的可变 POJO → Java record（**契约不变，只改形态**）。⚠️ Bean Validation 是 **L3 降级**：错误文案从 B 侧改为 Bean Validation 文案 |
| **D6** | 跨域调用仍走 gRPC | 2 个 `rpc/client/*.java` | 跨域是业务能力（取作者资料、取文章快照），不该被 HTTP 层直连 |
| **P6** | 3 个 `fact/*` 组件的遗留子项 | 3 处（§1.6） | ✅ **全部拍板**：P6-a `-once` 模式（① 降级为配置项）、P6-b authority 端口（① 并入主 app 端口 · 未决项 U1 仍挂）、P6-c **preflight 触发入口（① Actuator 自定义端点 · 2026-11-05 拍板 ⇒ §1.6 缺口闭合）** |

---

## 6. 跨模块全局约定（值逐条核对 B 侧源码）

> ⚠️ **本表的每个值都来自 B 侧源码**（RAG 仓 `service/favorite/`），逐条抄出，不是推测。
> 出处列写 **B 仓相对路径:行号**（RAG 仓）。

| 项 | 值 | B 侧出处（B 仓相对路径:行号） |
|---|---|---|
| proto package | `favorite` | `favorite-proto/src/main/proto/favorite.proto:3` |
| proto service / 方法路径 | `service FavoriteService` ⇒ `/favorite.FavoriteService/*` | `favorite-proto/src/main/proto/favorite.proto:100-107` |
| proto 字段号 | `FavoriteFolder` 1–5；`FavoriteItem` 1–8；`CreateFavoriteFolderReq` 1–2；`CreateFavoriteReq` 1–6；`DeleteFavoriteFolderReq` 1–2；`DeleteFavoriteReq` 1–2；`UpdateFavoriteFolderNameReq` 1–3；`GetFavoriteFoldersReq` 1；`GetFavoritesByFolderReq` 1–2；各 Resp 1（`bool success = 1` / `int64 folder_id = 1` / `int64 favorite_id = 1` / `repeated … = 1`） | `favorite-proto/src/main/proto/favorite.proto:5-98` |
| HTTP path 前缀 | `/favorite/v1` | `favorite-api/…/api/controller/FavoriteController.java:89` + `favorite-api/…/api/config/WebMvcConfig.java`（`FAVORITE_PREFIX`） |
| HTTP 端点（7 条） | POST `/folder/create`；DELETE `/folder/delete`；GET `/folder/list`；POST `/folder/update`；POST `/item/create`；DELETE `/item/delete`；GET `/item/list` | `favorite-api/…/api/controller/FavoriteController.java:129,136,143,156,167,174,191` |
| HTTP method 分布 | POST×3 / DELETE×2 / GET×2（**非全 POST**） | 同上（7 个 `@*Mapping`） |
| public 路径集 | **空集**（7 条全部受保护） | `favorite-api/…/api/auth/FavoriteJwtAuthPaths.java:16,53`（`PROTECTED_PREFIX = "/favorite/v1"`，无 public 段） |
| 错误码（24 条） | `Success=200`、`Error=500`、`CodeServerBusy=1015`、`ErrorServerCommon=5001`、`ErrorNotFound=5002`、`ErrorInvalidParam=5003`、`ErrorUnauthorized=5004`、`ErrorForbidden=5005`、`ErrorAlreadyExist=5006`、`ErrorDbSelect=5007`、`ErrorDbUpdate=5008`、`ErrorGenerateID=5009`、`ErrorUserNotFound=5101`、`ErrorUserExists=5102`、`ErrorPasswordInvalid=5103`、`ErrorTokenInvalid=5104`、`ErrorTokenExpired=5105`、`ErrorFavoriteFolderNotFound=5201`、`ErrorFavoriteFolderNameEmpty=5202`、`ErrorFavoriteFolderNameExists=5203`、`ErrorFavoriteNotFound=5301`、`ErrorFavoriteAlreadyExist=5302`、`ErrorFavoriteTargetEmpty=5303`、`ErrorFavoriteHistoryBlocked=5304` | `favorite-common/…/common/errmsg/FavoriteErrorCode.java:37` 起（枚举，24 个常量，**声明顺序即契约**） |
| 错误文案语言 | 22 条中文 + 🔴 **`ErrorNotFound(5002)` = 英文 `not found`** + `Success`="OK" / `Error`="FAIL" | `favorite-common/…/common/errmsg/FavoriteErrorMessages.java:52`（`not found`，本域唯一英文）+ `:48`（`FAIL`） |
| 错误码反查顺序 | **先按 message 反查**（`bizCodeByMsg`），**查不到才**按 gRPC 标准码 fallback（InvalidArgument→5003 / Unauthenticated→5004 / PermissionDenied→5005 / AlreadyExists→5006 / NotFound→5002 / Internal→5001 / default→1015） | `favorite-common/…/common/errmsg/FavoriteGrpcError.java:171`（`bizCodeFromError`）+ `:193-194`（6 个标准码白名单） |
| gRPC 错误形态 | `grpcError(grpcCode, bizCode)` ⇒ **description 装的是业务文案**，业务码在 code 字段**丢失** | `favorite-common/…/common/errmsg/FavoriteGrpcError.java:94` |
| HTTP 错误码（解析失败） | **HTTP 200 + `{code:5003, msg:"参数错误", data:null}`**（7 个端点逐字同质） | `favorite-api/…/api/advice/GlobalExceptionHandler.java:37,44,70` |
| JWT claim | `userId`（JWT 数字 claim 走 `json.Number` 分支 ⇒ **主路径是 `json.Number` 分支**） | `favorite-api/…/api/service/FavoriteApiSupport.java:32,80`（`userId`，5 类型分支） |
| JWT secret | `favorite-api.user-auth.access-secret`（与 `users` 域 **同一密钥域**，签发方是 user-api） | `favorite-api/src/main/resources/application.yml:174` |
| JWT `AccessExpire` | `2592000` —— **api 侧是死配置**（只签发方 user-rpc 需要它） | `favorite-api/src/main/resources/application.yml:175` |
| Redis blacklist key | `user:jwt_blacklist:%s` | `favorite-api/…/api/interceptor/FavoriteJwtAuthInterceptor.java:107`（`BLACKLIST_KEY_PREFIX`） |
| Redis 401 body | **只有 `code` + `msg` 两个字段，没有 `data`**；`Content-Type: application/json;charset=utf-8`；HTTP 401 | `favorite-api/…/api/auth/FavoriteJwtSignatureVerifier.java:133-137`（本域 401 的唯一构造点） |
| 表名 | `favorite_folder`、`favorite_item`、`favorite_fact_outbox`、`favorite_legacy_fact_marker` | `favorite-rpc/…/rpc/domain/FavoriteFolderEntity.java:19` · `FavoriteItemEntity.java:30` · `FavoriteFactOutboxEntity.java:39` · `FavoriteLegacyFactMarkerEntity.java:35`（各自的 `@TableName`） |
| 唯一键 | `uk_user_folder_name(user_id,name)`、`uk_folder_target(folder_id,target_id,target_type)`、`UNIQUE(favorite_id,aggregate_version)` | `favorite-rpc/src/main/resources/db/migration/V1__create_favorite_folder.sql:36` · `V2__create_favorite_item.sql` · `V3__create_favorite_fact_outbox.sql:47` |
| DB | `first_db` / user `admin` / schema `public` | `favorite-rpc/src/main/resources/application.yml`（datasource 段） |
| 端口 | api HTTP `${FAVORITE_API_PORT:18893}`；rpc gRPC `${FAVORITE_RPC_PORT:8082}`；rpc management `${FAVORITE_RPC_MANAGEMENT_PORT:16062}` | `favorite-api/src/main/resources/application.yml:110` · `favorite-rpc/src/main/resources/application.yml:227,181` |
| 注册中心 key | Nacos（`favorite-rpc` / `user-rpc` / `article-rpc`） | `favorite-rpc/…/bootstrap/FavoriteNacosRegistration.java` + `favorite-rpc/src/main/resources/application.yml`（nacos discovery 段） |
| 指标完整名 | `favorite_request_total`、`favorite_request_seconds_counter`、`favorite_ops_total`、`favorite_db_error_total` | `favorite-rpc/…/rpc/metrics/FavoriteRpcMetrics.java:92,99,113,126`（4 个 `Counter.builder`） |
| 指标 label | request：`module,action,result`；seconds：`module,action`；ops：`module,action,result`；db_error：`module,action,type` | 同上（各 `Counter.builder(...).tag(...)`） |
| label 取值 | `module` ∈ {`favorite_folder`,`favorite_item`}；`action` ∈ {`create`,`delete`,`list`,`update_name`,`list_by_folder`}；`result` ∈ {`success`,`fail`} | `favorite-rpc/…/rpc/config/FavoriteRpcLabels.java:31-40`（action 5 值 + result 2 值） |
| Kafka topic | **无** —— favorite 域全仓无 kafka 引用 ⇒ **不引 `spring-kafka`** | `favorite-rpc/src/main/resources/application.yml:10`（注释明写 pom 不引 kafka） |
| 雪花 ID 用途 | 仅 `folder_id` / `favorite_id`（**无游标** ⇒ 不依赖 `>>22`） | `favorite-rpc/…/rpc/FavoriteRpcApplication.java:69`（`SnowflakeBean.class` 注册） |
| communityfact 协议 | `Event` 9 字段（`event_id/event_type/schema_version/producer/aggregate_id/aggregate_version/operation_id/occurred_at/payload`）；`TechnicalReceipt` 7 字段；`DeliveryStatus` 0/1/2/3；JCS → SHA-256 → hex | `service/common/.../communityfact/Wire.java:120-131`（Event）+ `:135-142`（TechnicalReceipt）+ `:37-41`（DeliveryStatus） |
| v1 事实 `subject_ref` | **3 字段** `{authority_id:"rtw.identity", tenant_id:"platform", subject_id:"1001"}` | `favorite-rpc/…/rpc/domain/FavoriteAuthorityFact.java:57,60,63` |
| v2 事实 `subject_ref` | 2 字段 `{issuer, subject_id}`（**与 `common` 的 `Wire.SubjectRef` 一致**） | `favorite-rpc/…/rpc/domain/FavoriteAuthorityFact.java:177-191`（v2 分支，`tenant_id` 被丢弃） |

---

## 7. 覆盖度自查

> 判据来自 `docs/Mapping_Table/README.MD` §四。

### 7.1 C 列零遗漏 —— ✅ 63/63（100%）

```bash
# ⚠️ 在**域工作树**里跑 ⇒ 路径要指向工作树（主仓与工作树的文件数可能不同）
find service/favorite -name '*.java' \
  -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u
```

实测 **63** 个唯一文件名（本域无同名文件），逐条比对结果：

| # | C 列文件 | 出现在 | # | C 列文件 | 出现在 |
|---|---|---|---|---|---|
| 1 | `FavoriteServiceApplication.java` | §1.2 / §1.3 | 32 | `GetFavoriteFoldersReq.java` | §1.2 |
| 2 | `FavoriteApiExceptionHandler.java` | §1.2 | 33 | `GetFavoriteFoldersResp.java` | §1.2 |
| 3 | `FavoriteApiResponseAdvice.java` | §1.2 | 34 | `GetFavoritesByFolderReq.java` | §1.2 |
| 4 | `FavoriteApiProperties.java` | §1.2 | 35 | `GetFavoritesByFolderResp.java` | §1.2 |
| 5 | `FavoriteApiService.java` | §1.2 | 36 | `UpdateFavoriteFolderNameReq.java` | §1.2 |
| 6 | `FavoriteApiSupport.java` | §1.2 | 37 | `UpdateFavoriteFolderNameResp.java` | §1.2 |
| 8 | `ArticleRpcClient.java` | §1.3 | 38 | `FavoriteExceptions.java` | §1.3 |
| 9 | `UserRpcClient.java` | §1.3 | 39 | `FavoriteGrpcService.java` | §1.3 |
| 10 | `FavoriteRpcLabels.java` | §1.3 | 40 | `FavoriteJwtAuthInterceptor.java` | §1.2 |
| 11 | `FavoriteRpcProperties.java` | §1.3 | 41 | `QueryLimitInterceptor.java` | §1.8 |
| 12 | `WebMvcConfig.java` | §1.2 | 42 | `FavoriteFactOutboxMapper.java` | §1.3 |
| 13 | `ErrCodes.java` | §1.4 | 43 | `FavoriteFolderMapper.java` | §1.3 |
| 14 | `ErrMsg.java` | §1.4 | 44 | `FavoriteItemMapper.java` | §1.3 |
| 15 | `GrpcErrorMapper.java` | §1.4 | 45 | `FavoriteLegacyFactMarkerMapper.java` | §1.3 |
| 16 | `FavoriteController.java` | §1.2 | 46 | `FavoriteRpcMetrics.java` | §1.3 |
| 17 | `CreateFavoriteReq.java` | §1.2 | 47 | `FavoriteArticleSnapshotResolver.java` | §1.3 |
| 18 | `CreateFavoriteResp.java` | §1.2 | 48 | `FavoriteAssertionSchemaResolver.java` | §1.3 |
| 19 | `DeleteFavoriteReq.java` | §1.2 | 49 | `FavoriteAuthorityLookupService.java` | §1.3 |
| 20 | `DeleteFavoriteResp.java` | §1.2 | 50 | `FavoriteDeliveryValidator.java` | §1.3 |
| 21 | `FavoriteItem.java` | §1.2 | 51 | `FavoriteFactDispatchService.java` | §1.3 |
| 22 | `CreateFavoriteFolderReq.java` | §1.2 | 52 | `FavoriteFactEnvelopeFactory.java` | §1.3 |
| 23 | `CreateFavoriteFolderResp.java` | §1.2 | 53 | `FavoriteFactWriteService.java` | §1.3 |
| 24 | `DeleteFavoriteFolderReq.java` | §1.2 | 54 | `FavoriteRpcSupport.java` | §1.3 |
| 25 | `DeleteFavoriteFolderResp.java` | §1.2 | 55 | `FavoriteUserGuard.java` | §1.3 |
| 26 | `FavoriteFolder.java` | §1.2 | 56 | `FavoriteAuthorityEndpointConfig.java` | §1.3 / §1.6 |
| 27 | `FavoriteFactOutboxEntity.java` | §1.3 | 57 | `FavoriteFactDispatchScheduler.java` | §1.3 / §1.6 |
| 28 | `FavoriteFolderEntity.java` | §1.3 | 58 | `FavoriteFactProcessLogging.java` | §0 |
| 29 | `FavoriteItemEntity.java` | §1.3 | 59 | `FavoriteNacosRegistration.java` | §0 |
| 30 | `FavoriteLegacyFactMarkerEntity.java` | §1.3 | 60 | `FavoriteSchemaGate.java` | §1.3 |
| 31 | `FavoriteAuthorityFact.java` | §1.3 | 61 | `FavoriteSubjectRefGate.java` | §1.3 |
| — | — | — | 7 | `FavoriteDomainService.java` | §1.3 |
| 62 | `FavoriteLegacyPreflightService.java` | §1.6 | 63 | `FavoriteLegacyPreflightEndpoint.java` | §1.6 |

> **结论：63/63，无遗漏。**（**2026-11-05 更新** —— P6-c ① 落地后新增 2 个主代码：`service/FavoriteLegacyPreflightService.java` ＋ `wiring/FavoriteLegacyPreflightEndpoint.java`）
> ⚠️ 排除项：`src/test/java/...`（测试）、`target/`（构建产物）。

### 7.2 映射类型分布（实测计数）

> 统计口径：**全文所有主表**（表头第 3 格为「映射类型」的表）的**数据行**；§2 / §3 / §4 / §6 是按维度组织的**差异对照表**，不设「映射类型」列，故不计入。

| 映射类型 | §0 顶层 | §1.2 api | §1.3 rpc | §1.4 common | §1.5 proto | §1.6 fact | §1.8 补录 | 合计 | 占比 |
|---|---|---|---|---|---|---|---|---|---|
| `1:1` | 3 | 18 | 15 | 3 | 1 | 2 | 2 | **44** | 50.0% |
| `1:N` | 1 | 10 | 10 | 0 | 0 | 1 | 0 | **22** | 25.0% |
| `N:1` | 2 | 9 | 9 | 0 | 0 | 0 | 1 | **21** | 23.9% |
| `C 新增` | 0 | 1 | 0 | 0 | 0 | 0 | 0 | **1** | 1.1% |
| **合计** | **6** | **38** | **34** | **3** | **1** | **3** | **3** | **88** | 100% |

> 每行都填了映射类型，且只用 `1:1` / `1:N` / `N:1` / `C 新增` 四种词（未发明新词）。
> ⚠️ **核对日期 2026-11-02**（删列后重算）。**重算依据**：逐行扫 7 张主表（§0 6 行 + §1.2 38 + §1.3 34 + §1.4 3 + §1.5 1 + §1.6 3 + §1.8 3 = **88 行**），按第 3 格取值计数；
> 与删列前的同一批行**逐行比对，映射类型判定未变**（删列只影响列数，不影响 B→C 的行数关系）⇒ **行数 87 → 88**（+1 行 = §1.3 补录的 `JsonbTypeHandler.java` 条目，见该表末行），**类型分布按 B→C 口径重列**。

### 7.3 ⚠️ 两方之间「职责对不上」的地方（逐条登记）

> 本表产出的待订正项：B 侧有、C 侧无（C 列缺口），或 C 列现有 javadoc / TODO 与 B 侧源码不符。

| # | 项 | B 列 RAG（事实） | C 列 RAG-V1 | 性质 |
|---|---|---|---|---|
| **M1** | **`POST /favorite/v1/folder/create` 端点缺失** | `FavoriteController.java:129` 有 7 个端点，**含** `POST /folder/create` | ✅ **已订正（2026-11-04）** —— `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` 的 javadoc 现列出**全部 7 条**路由（含 `POST /favorite/v1/folder/create`），且方法分布写明 **Get2 / Post3 / Delete2**；实现层 7 个 `@*Mapping` 逐条落地 | **已闭合**（C 列 javadoc 与 B 侧一致） |
| **M2** | **public 路径集** | 7 个端点**全部**受保护（`FavoriteJwtAuthPaths.java:16`）⇒ **public 集为空** | ✅ **已订正（2026-11-04）** —— `FavoriteController.java` javadoc 写「**鉴权划分（L1）：7/7 端点全部受保护，无 public 段**」；`config/WebMvcConfig.java` javadoc 写「**为什么只有两条拦截器、且没有 excludePathPatterns**」⇒ 实现里**没有** `excludePathPatterns` | **已闭合**（C 列 javadoc 与 B 侧一致） |
| **M3** | **preflight 触发入口** | `favorite-rpc/…/rpc/fact/FavoriteLegacyPreflightRunner.java` 是**只读报表**组件（一次性语义） | ✅ **已订正（2026-11-05）** —— 决策 P6-c 拍板 ① ⇒ C 侧新增 `service/favorite/src/main/java/com/rag/favorite/service/FavoriteLegacyPreflightService.java`（**报表计算**）+ `service/favorite/src/main/java/com/rag/favorite/wiring/FavoriteLegacyPreflightEndpoint.java`（**Actuator 触发入口**） | **已闭合**（缺口消 ⇒ C 列 **61 → 63**）<br>⚠️ **附带登记**：`DEV-FAV-RPC-008`（事务：自调用绕过代理 ⇒ B 侧注解在但事务从未生效）· `DEV-FAV-RPC-009`（触发形态：Actuator 端点 + HTTP 状态码替代退出码 + Actuator 响应被业务信封包裹） |
| **M4** | **DDL 迁移脚本** | Flyway 7 个脚本齐（含 2 个不可变触发器 + `favorite_legacy_fact_marker` 整表） | ✅ **已落地（2026-11-04）** —— `service/favorite/src/main/resources/db/migration/` 下 `V1__create_favorite_folder.sql` … `V7__align_favorite_outbox_time_columns_with_go.sql` 共 7 个，**SQL 正文逐字零差异**（diff / sed 去注释 / tail-cmp 三层验证全过） | **已闭合** ⇒ 触发器到位 ⇒ `5304` 不会被静默变成 `5008`<br>⚠️ **附带缺口**：映射表 §2.1 **漏记**了 V6 ③ 的 `favorite_item_target_revision_scope` CHECK（**B 侧有意加强**）⇒ 登记进 `docs/DRIFT/favorite/` 台账 |
| **M5** | **`JsonbTypeHandler` / MP 排序桥的归属** | **域内副本**：`favorite-rpc/…/mapper/JsonbTypeHandler.java`、`…/config/MybatisPlusOrderingBridgeAutoConfiguration.java` | C 侧 favorite 域**无**这两个文件 ⇒ 应取 `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` 与 `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` | **D3 归位**（不是缺陷，是形态差异）⇒ 填实现时不要在域内重建副本 |
| **M6** | **`GetFavoritesByFolderReq.FolderId` 的取参位置** | `favorite-api/…/dto/GetFavoritesByFolderReq.java:11,33` 是 **`form:"folderId,string"`** ⇒ 取自**查询串**，且带 `,string` 解析 | ✅ **已订正（2026-11-04）** —— `service/favorite/src/main/java/com/rag/favorite/dto/favorite/GetFavoritesByFolderReq.java` 的 javadoc 已写明 **form 通道 + `,string` 宽松解析 + 「先滤空值再取首值」**；并提供 `fromForm(String)` 静态工厂（空串 ⇒ 缺席 ⇒ `null`）<br>⚠️ **共享层的 `GetFormValuesFilter` 已实现**（`common/src/main/java/com/rag/common/web/GetFormValuesFilter.java:210-302`，含 `goParseViolation` 判据）⇒ **不再是空壳** ⇒ 域内 `interceptor/QueryLimitInterceptor` 直接复用该判据 | **已闭合**（DTO 注释 + 共享层依赖双侧就位） |
| **M7** | **域内 gRPC client** | `favorite-api/…/client/FavoriteRpcClient.java`（api → rpc 走 gRPC） | C 侧**无**（**D2** 直连）⇒ `favorite-api` 模块在 C 侧整体消失 | **形态差异**（已定决策 D2），非缺陷 |
| **M8** | **端口号** | api `${FAVORITE_API_PORT:18893}` / rpc `${FAVORITE_RPC_PORT:8082}` | C 侧 HTTP `:8894` / gRPC `:9003` | **L3 部署层差异**（非 L1），但需在部署文档登记 |

### 7.4 未决项沿用（不在本表结论内，仅指针）

RAG 侧 `favorite-rpc` 台账的 **7 条未决**（`RAG/docs/DRIFT/favorite/rpc.md` §5）**同样适用于 C 列**：
U1 authority wire 形态 · U2 preflight 退出码语义（✅ **已由 P6-c ① 消解**：改用 HTTP 状态码 + 结构化日志，登记 `DEV-FAV-RPC-009`）· U3 `Mode` 取值域 · U4 authority lookup 的 3s 超时 ·
U5 `ObserveRPC` 的 `result` 标签 · U6 `favorite_request_seconds_counter` 指标名 · U7 双侧活体对拍。
⇒ **本表不为它们下任何「已对齐 / 未对齐」结论**；C 列实现前请回 **B 侧源码**逐字核对。

---

## 一句话总览

`B 列 RAG` = **2 进程**（api + rpc；rpc 内含 3 个 fact 组件）+ MyBatis-Plus + Flyway 7 脚本 + advice 统一信封 + 域内 gRPC 往返 + 24 条域私有错误码（唯一英文 `not found`）+ 4 张表含 2 个不可变触发器；
`C 列 RAG-V1` = **1 个 app** + 域内 `contract/` 包（proto 在根级 `proto/`）+ Spring 注解式路由 + immutable record + `BusinessException` 双边界适配 + 域内直连 + 3 个 fact 组件落成域内组件（dispatch / authority → `wiring/`；legacy-preflight → `service/` 报表计算 + `wiring/` Actuator 端点，决策 P6-c ①）。
⇒ **文件名与职责逐条对应；行为以 B 侧源码为准。**
