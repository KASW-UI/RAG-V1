# Mapping_Table/favorite/favorite.md — favorite 域三方文件名对照表

> **本表目的**：把 **Go 真理之源**（A 列）→ **RAG**（B 列，Go 部署形态版）→ **RAG-V1**（C 列，Spring 惯用目标架构）
> 三棵树里 **favorite 域**的文件逐条对应起来，方便在 RAG-V1 上填实现时反查「这个 Java 类是从哪个 Go 文件 / RAG 文件来的」。
>
> **A 列**：`Sea-RideTheWind_golang/service/favorite/`（真源，冻结，只读）
> **B 列**：`RAG/service/favorite/`（Go 形态版；api / rpc / common / proto 四模块）
> **C 列**：`RAG-V1/service/favorite/`（本仓；`favorite-service` **一个**模块；错误契约与事件 DTO 在域内 `contract/` 包，proto 在根级 `proto/`）
>
> **格式**：沿用 `docs/Mapping_Table/README.MD` 的 5 列表头与 4 词映射类型词表
> （`1:1` / `1:N` / `N:1` / `C 新增`），章节骨架沿用 RAG 那份 `docs/MappingTable/users.md`。

---

## ⚠️ 读表前必读的三条纪律

1. ⚠️ **B 列不是 Go 的替身。**
   RAG 的 favorite 域 **api / common / proto 三个组件台账仍是 0 字节（从未对照）**
   （`RAG/docs/DRIFT/favorite/{api,common,proto}.md`），**rpc 侧已修 6 处 L2 偏差，但还有 7 条未决**
   （`RAG/docs/DRIFT/favorite/rpc.md` §2 / §5）。
   ⇒ **一切行为判断以 A 列 Go 源码为准**；B 列只用来定位「RAG 当时把它放在哪」。
2. ⚠️ **本表只回答「文件名 / 职责的对应关系」，不下「已对齐 / 未对齐」结论。**
   行为对齐状态看 `RAG/docs/DRIFT/favorite/<组件>.md` 的**证据强度列**。
3. ⚠️ **C 列的空壳 ≠ 缺职责。**
   C 侧每个 `.java` 目前都只有 `package` + 空类型 + `TODO` javadoc
   ⇒ **C 列出现的每一行都是「这个位置该放什么」，不是「已经有什么」。**

> 📌 **路径写法**：A 列用 Go 仓库根相对路径；B 列用「模块名 / 包路径缩写 / 文件名」形式
> （`…` = `src/main/java/com/rag/favorite/favorite`，便于 `grep` 到文件名即可定位）；
> C 列用 RAG-V1 仓库根相对路径（覆盖度自查要逐字比对，故不给缩写）。

---

> 📌 **时点标注（2026-10-26 · D7）** —— 本表若干**决策锁**条目记录的是 **2026-10-25 之前**的形态
> （每域一个 `*-contract` 契约子模块 + `api/` + `rpc/` 两棵包树）。
> 这两处已被 **D7** 取代：① **10 个 `*-contract` 子模块全部删除** —— `.proto` 集中到**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`），
> 错误契约与事件 DTO 落进**域模块内的 `contract/` 包**；② **包压平** —— `api/` + `rpc/` 两棵树合并为**单层技术角色包树**。
> ⇒ **决策锁条目保留原样**（它们记录的是**当时的决策**，不是当前位置）；**当前位置一律看 §0.5「B → C 结构映射速查表」**。

## 0. 顶层模块对照

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `service/favorite/api/` | `service/favorite/favorite-api/` | `service/favorite/`（的 `service/favorite/src/main/java/com/rag/favorite/advice + service/favorite/src/main/java/com/rag/favorite/config + service/favorite/src/main/java/com/rag/favorite/controller + service/favorite/src/main/java/com/rag/favorite/dto + service/favorite/src/main/java/com/rag/favorite/interceptor + service/favorite/src/main/java/com/rag/favorite/service` 子包） | N:1 | Go 的 api 是独立进程（`api/favorite.go`，HTTP `:8890`）；C 里 api 与 rpc 合并为单一 app（决策 **D1**）⇒ **C 侧没有独立 api 模块** |
| `service/favorite/rpc/` | `service/favorite/favorite-rpc/` | `service/favorite/`（的 `service/favorite/src/main/java/com/rag/favorite/bootstrap + service/favorite/src/main/java/com/rag/favorite/client + service/favorite/src/main/java/com/rag/favorite/config + service/favorite/src/main/java/com/rag/favorite/domain/entity + service/favorite/src/main/java/com/rag/favorite/domain/model + service/favorite/src/main/java/com/rag/favorite/domain/service + service/favorite/src/main/java/com/rag/favorite/exception + service/favorite/src/main/java/com/rag/favorite/grpc + service/favorite/src/main/java/com/rag/favorite/mapper + service/favorite/src/main/java/com/rag/favorite/metrics + service/favorite/src/main/java/com/rag/favorite/service` 子包） | N:1 | Go 的 rpc 是独立进程（`rpc/favorite.go`，gRPC `:8082`）；C 里与 api 合并（**D1**） |
| `service/favorite/common/` | `service/favorite/favorite-common/` | `service/favorite/src/main/java/com/rag/favorite/contract/`（`ErrCodes`/`ErrMsg`/`GrpcErrorMapper`） | 1:N | Go 侧 `package common` 单文件（`errmsg.go`）；C 侧按「proto（L1）+ 域内错误契约」重新分包，**码表内容一字不改** |
| `proto/favorite.proto`（Go 仓库根，**不在 `service/favorite/` 下**） | `service/favorite/favorite-proto/src/main/proto/favorite.proto` | `proto/src/main/proto/favorite.proto` | 1:1 | Go 侧 proto 放仓库根、被 api 与 rpc **共用**；C 侧 proto 集中到根级 `proto/` 模块 ⇒ 跨服务共享面更干净 |
| —（Go 用 etcd 服务发现） | `favorite-rpc/…/bootstrap/FavoriteNacosRegistration.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteNacosRegistration.java` | 1:1 | **A 列无 Go 对应物**：Go 用 etcd（`rpc/etc/favorite.yaml:28-31`），C 用 Nacos（生态替代，**D4**）⇒ 行内 A 记「—」时，`1:1` 表达「B→C 1:1，A 侧无对应物」 |
| —（Go 用 `logx` + `observability`） | `favorite-rpc/…/bootstrap/FavoriteFactProcessLogging.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteFactProcessLogging.java` | 1:1 | **A 列无 domain 内对应物**：Go 侧进程级日志由 `service/common/communityfact/logging.go` 提供（域外共享层，见 §4）⇒ 记「—（域外共享层）」 |

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
| `favorite/api/dto/` | `dto/` | 直接压平（15 个同名类全落此）|
| `favorite/api/interceptor/` | `interceptor/` | 直接压平 |
| `favorite/api/service/` | `service/` | 与 rpc 侧合并 |
| `favorite/common/errmsg/` | `contract/` | 错误码（`ErrCodes` / `ErrMsg` / `GrpcErrorMapper`）|
| `favorite/rpc/bootstrap/` | `bootstrap/` | 直接压平 |
| `favorite/rpc/client/` | `client/` | 与 api 侧合并 |
| `favorite/rpc/config/` | `config/` | 合并 |
| `favorite/rpc/domain/` | `domain/entity/` + `domain/model/` | 按是否 `*Entity` 拆分（`FavoriteAuthorityFact` 非 Entity ⇒ 落 `domain/model/`）|
| `favorite/rpc/exception/` | `exception/` | 直接压平 |
| `favorite/rpc/fact/`（3 类） | `bootstrap/`（2 类）| `FavoriteAuthorityEndpointConfig` + `FavoriteFactDispatchScheduler`；**`FavoriteLegacyPreflightRunner` 无落点**（P6-c 待拍板）|
| `favorite/rpc/mapper/` | `mapper/` | 直接压平 |
| `favorite/rpc/metrics/` | `metrics/` | 与 api 侧合并 |
| `favorite/rpc/service/` | `service/` + `domain/service/` | 领域服务另出 `domain/service/` |
| `favorite/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `FavoriteServiceGrpcImpl` → `FavoriteGrpcService` |
| `favorite-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.favorite`）| **集中化**（D7）|

## 1. 包结构对照

### 1.1 A 列 Go 侧的进程拓扑（先看清「几个进程」）

| Go 进程 | 入口 | 注册了什么 | 读写 |
|---|---|---|---|
| **P1** gRPC server | `rpc/favorite.go:26-56` | `favoritepb.RegisterFavoriteServiceServer`（7 个 RPC） | DB 读写 4 张表 + `UserRpc` + `ArticleRpc` |
| **P2** fact-dispatch | `rpc/cmd/fact-dispatch/main.go:22-32` | **零服务注册**，只跑 `DispatchFavoriteFactOnce` 循环 | DB 读 + DC HTTP 出网 |
| **P3** fact-authority | `rpc/cmd/fact-authority/main.go:21-29` | **零服务注册**，只起 1 个 `http.Server`（`:56-57`） | DB 读 + HTTP 入 |
| **P4** legacy-preflight | `rpc/cmd/fact-legacy-preflight/main.go:18-54` | **零服务注册**，只跑一次只读报表 | DB 只读 |

⇒ **4 个 Go 进程 → C 侧 1 个 JVM**（`FavoriteServiceApplication`）。P2/P3 落成域内组件，P4 落成「可触发的运维入口」（**C 侧尚未规划 ⇒ 缺口，见 §1.6**）。

---

### 1.2 api 组件（Go `advice + config + controller + dto + interceptor + service`，22 个非测试文件）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `api/favorite.go` | `favorite-api/…/api/FavoriteApiApplication.java` | `service/favorite/src/main/java/com/rag/favorite/FavoriteServiceApplication.java` | N:1 | Go 的 api main（`:25-34`：`logx.MustSetup` → `DisableNativeRestTimeout` → `rest.MustNewServer` → `NewServiceContext` → `RegisterHandlers`）与 rpc main 合并成 C 侧唯一 `@SpringBootApplication`（**D1**）。Go 的 `svc.NewServiceContext` + `RegisterHandlers` 两步装配由 Spring DI + 注解式路由承担 |
| `api/favorite.go`（信封与错误处理段） | `favorite-api/…/api/advice/GlobalResponseAdvice.java` + `…/advice/GlobalExceptionHandler.java` | `service/favorite/src/main/java/com/rag/favorite/advice/FavoriteApiResponseAdvice.java` + `service/favorite/src/main/java/com/rag/favorite/advice/FavoriteApiExceptionHandler.java` | 1:N | 🔴 Go 侧**没有** `httpx.SetOkHandler` / `httpx.SetErrorHandler` ⇒ 响应信封**逐个 handler 手写**（`create_favorite_folder_handler.go:29-33`）。C 侧改成 advice 后**只包一层**（`body == null \|\| body instanceof Response` 放行），否则双层信封 |
| `api/etc/favorite.yaml` | `favorite-api/src/main/resources/application.yml` | `service/favorite/src/main/resources/application.yml` | N:1 | Go 的 api yaml 与 rpc yaml 两份合并成 C 的一份。⚠️ 端口值未逐字照抄（Go api=`:8890`、rpc=`:8082`；C HTTP=`8894`、gRPC=`9003`）⇒ 属**部署层（L3）**，L1 契约（前缀 / 码 / 表名 / 字段号）不受影响 |
| `api/internal/config/config.go` | `favorite-api/…/api/config/FavoriteApiProperties.java` | `service/favorite/src/main/java/com/rag/favorite/config/FavoriteApiProperties.java` | 1:1 | Go `Config = rest.RestConf + UserAuth + FavoriteRpc + BizRedis`（`:9-17`）→ C 只保留 `UserAuth{accessSecret,accessExpire}`。`FavoriteRpc` 因域内直连（**D2**）、`BizRedis` 因 Spring Boot 标准 `spring.data.redis` ⇒ 不再是本域配置源 |
| `api/internal/handler/routes.go` | `favorite-api/…/api/controller/FavoriteController.java` + `favorite-api/…/api/config/WebMvcConfig.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/config/WebMvcConfig.java` | 1:N | 7 条路由表（`routes.go:21-52`）→ `@RequestMapping("/favorite/v1")` + 方法映射；`rest.WithMiddlewares(blacklist)` + `rest.WithJwt(secret)` → `WebMvcConfig.addInterceptors`。⚠️ **L3 顺序**：Go 里 JWT 在 chain **外层**（先跑）、blacklist 在**内层** ⇒ C 的 Interceptor 注册顺序必须保持「JWT → blacklist」 |
| `api/internal/handler/favorite/create_favorite_folder_handler.go` | `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteCreateFolderService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | Go handler（35 行）：`httpx.Parse` → 失败回 **HTTP 200 + `code:5003`** → `NewXxxLogic` → `httpx.OkJsonCtx` 包信封。C 侧 7 个 api 用例合并进 1 个 `FavoriteApiService`（用例内聚判据，§5.4） |
| `api/internal/handler/favorite/create_favorite_handler.go` | `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteCreateItemService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | 同上 |
| `api/internal/handler/favorite/delete_favorite_folder_handler.go` | `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteDeleteFolderService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | 同上。⚠️ 该用例的 `Success: true` 是**硬编码**（`api/internal/logic/favorite/delete_favorite_folder_logic.go:65`）⇒ C 侧不得把 `success` 改成「业务结果标志」 |
| `api/internal/handler/favorite/delete_favorite_handler.go` | `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteDeleteItemService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | 同上 |
| `api/internal/handler/favorite/get_favorite_folders_handler.go` | `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteListFoldersService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | ⚠️ **GET 端点仍解析并校验 body**：`httpx.Parse` 失败 ⇒ HTTP 200 + `5003`（`get_favorite_folders_handler.go:18-25`）；`GetFavoriteFoldersReq` 是**空 struct**（`types.go:42`）⇒ 不得跳过解析直接 200 |
| `api/internal/handler/favorite/get_favorites_by_folder_handler.go` | `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteListItemsService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | ⚠️ `GetFavoritesByFolderReq.FolderId` 用 **`form:"folderId,string"`**（`types.go:60`）⇒ **取自查询串**（非 JSON body），且走 go-zero 的 `,string` 解析 |
| `api/internal/handler/favorite/update_favorite_folder_name_handler.go` | `favorite-api/…/api/controller/FavoriteController.java` + `…/api/service/FavoriteUpdateFolderNameService.java` | `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | 同上 |
| `api/internal/logic/favorite/helper.go` | `favorite-api/…/api/service/FavoriteApiSupport.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiSupport.java` | 1:1 | 4 个职责：`extractUserID`（`:14-34`，**5 个类型分支**，`json.Number` 是主路径）/ `userLogOption`（`:36-38`）/ `articleLogOption`（`:40-42`）/ `codeFromRPCError`（`:44-46`）。⚠️ `codeFromRPCError` 是**唯一的 rpc 错误码入口** |
| `api/internal/logic/favorite/create_favorite_folder_logic.go` | `favorite-api/…/api/service/FavoriteCreateFolderService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | 7 个 api 用例（api 侧）→ 1 个 `FavoriteApiService`。骨架：起 span `FavoriteAPI.Xxx` → `extractUserID` 失败即 `5004` → 调 rpc → nil 守卫 ⇒ `5001` → 成功日志 → 返回 `Success=200` |
| `api/internal/logic/favorite/create_favorite_logic.go` | `favorite-api/…/api/service/FavoriteCreateItemService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | ⚠️ `title`/`cover` **原样**传给 rpc（api 侧不规范化）；日志用的 `strings.TrimSpace(req.TargetId)` 与实参**不一致**是 Go 事实，不要「顺手统一」 |
| `api/internal/logic/favorite/delete_favorite_folder_logic.go` | `favorite-api/…/api/service/FavoriteDeleteFolderService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | ⚠️ `!rpcResp.Success` 守卫**只存在于 3 个写操作**；两个 create 与两个 list **只有 nil 守卫** ⇒ 这处不对称本身是语义 |
| `api/internal/logic/favorite/delete_favorite_logic.go` | `favorite-api/…/api/service/FavoriteDeleteItemService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | 同上 |
| `api/internal/logic/favorite/get_favorite_folders_logic.go` | `favorite-api/…/api/service/FavoriteListFoldersService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | 逐项投影 `FolderId/Name/CreatedAt/UpdatedAt` |
| `api/internal/logic/favorite/get_favorites_by_folder_logic.go` | `favorite-api/…/api/service/FavoriteListItemsService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | 逐项投影 7 字段 |
| `api/internal/logic/favorite/update_favorite_folder_name_logic.go` | `favorite-api/…/api/service/FavoriteUpdateFolderNameService.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | N:1 | `Success: true` 硬编码 |
| `api/internal/middleware/checkblacklist_middleware.go` | `favorite-api/…/api/interceptor/FavoriteJwtAuthInterceptor.java` | `service/favorite/src/main/java/com/rag/favorite/interceptor/FavoriteJwtAuthInterceptor.java` | 1:1 | 🔴 **favorite 域独有**（comment / follow 无此目录）。语义 4 条：① 缺头 / 空 token ⇒ **fail-open** 直接 `next`；② Redis 故障 ⇒ **fail-open**；③ 401 的 body **只有 `code`+`msg` 两字段、没有 `data`**；④ 用的码是 favorite 自己的 `ErrorUnauthorized=5004`。key = `user:jwt_blacklist:%s`（`:36`） |
| `api/internal/svc/service_context.go` | `favorite-api/…/client/FavoriteRpcClient.java` + `…/api/service/FavoriteApiSupport.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiSupport.java` | 1:N | 三件事：`redis.MustNewRedis` + `favoriteservice.NewFavoriteService(zrpc.MustNewClient)` + `middleware.NewCheckBlacklistMiddleware`。C 侧：Redis 走标准配置、blacklist 走 Interceptor、**`@GrpcClient` 客户端文件因域内直连（**D2**）而消失** |
| `api/internal/types/types.go`（`CreateFavoriteFolderReq`） | `favorite-api/…/api/dto/CreateFavoriteFolderReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/CreateFavoriteFolderReq.java` | 1:1 | `Name string \`json:"name"\``（`types.go:5-7`） |
| `api/internal/types/types.go`（`CreateFavoriteFolderResp`） | `favorite-api/…/api/dto/CreateFavoriteFolderResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/CreateFavoriteFolderResp.java` | 1:1 | `FolderId int64 \`json:"folderId,string"\`` ⇒ **字符串化**，Java 侧 `Long.toString(id,10)` |
| `api/internal/types/types.go`（`CreateFavoriteReq`） | `favorite-api/…/api/dto/CreateFavoriteReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/CreateFavoriteReq.java` | 1:1 | 5 字段；`title`/`cover` 带 `,optional` |
| `api/internal/types/types.go`（`CreateFavoriteResp`） | `favorite-api/…/api/dto/CreateFavoriteResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/CreateFavoriteResp.java` | 1:1 | `FavoriteId int64 \`json:"favoriteId,string"\`` |
| `api/internal/types/types.go`（`DeleteFavoriteFolderReq`） | `favorite-api/…/api/dto/DeleteFavoriteFolderReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/DeleteFavoriteFolderReq.java` | 1:1 | `FolderId int64 \`json:"folderId,string"\`` |
| `api/internal/types/types.go`（`DeleteFavoriteFolderResp`） | `favorite-api/…/api/dto/DeleteFavoriteFolderResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/DeleteFavoriteFolderResp.java` | 1:1 | `Success bool \`json:"success"\`` |
| `api/internal/types/types.go`（`DeleteFavoriteReq`） | `favorite-api/…/api/dto/DeleteFavoriteReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/DeleteFavoriteReq.java` | 1:1 | `FavoriteId int64 \`json:"favoriteId,string"\`` |
| `api/internal/types/types.go`（`DeleteFavoriteResp`） | `favorite-api/…/api/dto/DeleteFavoriteResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/DeleteFavoriteResp.java` | 1:1 | `Success bool \`json:"success"\`` |
| `api/internal/types/types.go`（`FavoriteFolder`） | `favorite-api/…/api/dto/FavoriteFolder.java` | `service/favorite/src/main/java/com/rag/favorite/dto/FavoriteFolder.java` | 1:1 | 🔴 **白名单投影**：proto `FavoriteFolder` 有 5 字段（含 `user_id`），HTTP DTO **只有 4 个**（`types.go:42-47`）⇒ `user_id` 永不出 api 边界 |
| `api/internal/types/types.go`（`FavoriteItem`） | `favorite-api/…/api/dto/FavoriteItem.java` | `service/favorite/src/main/java/com/rag/favorite/dto/FavoriteItem.java` | 1:1 | 同上：proto 8 字段 → HTTP **7 字段**（丢 `user_id`，`types.go:49-57`） |
| `api/internal/types/types.go`（`GetFavoriteFoldersReq`） | —（B 侧无此 DTO） | `service/favorite/src/main/java/com/rag/favorite/dto/GetFavoriteFoldersReq.java` | C 新增 | Go 侧是**空 struct**（`types.go:42`）；B 侧直接不建 DTO ⇒ C 侧必须补一个空 record，用 `assertGoEmptyStructBody()` 复刻「GET 端点也解析并校验 body」的 L4 行为 |
| `api/internal/types/types.go`（`GetFavoriteFoldersResp`） | `favorite-api/…/api/dto/GetFavoriteFoldersResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/GetFavoriteFoldersResp.java` | 1:1 | `Folders []FavoriteFolder \`json:"folders"\`` |
| `api/internal/types/types.go`（`GetFavoritesByFolderReq`） | `favorite-api/…/api/dto/GetFavoritesByFolderReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/GetFavoritesByFolderReq.java` | 1:1 | 🔴 `FolderId int64 \`form:"folderId,string"\`` ⇒ **查询参数**（不是 JSON body）+ `,string` 解析 |
| `api/internal/types/types.go`（`GetFavoritesByFolderResp`） | `favorite-api/…/api/dto/GetFavoritesByFolderResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/GetFavoritesByFolderResp.java` | 1:1 | `Favorites []FavoriteItem \`json:"favorites"\`` |
| `api/internal/types/types.go`（`UpdateFavoriteFolderNameReq`） | `favorite-api/…/api/dto/UpdateFavoriteFolderNameReq.java` | `service/favorite/src/main/java/com/rag/favorite/dto/UpdateFavoriteFolderNameReq.java` | 1:1 | `FolderId int64` + `Name string` |
| `api/internal/types/types.go`（`UpdateFavoriteFolderNameResp`） | `favorite-api/…/api/dto/UpdateFavoriteFolderNameResp.java` | `service/favorite/src/main/java/com/rag/favorite/dto/UpdateFavoriteFolderNameResp.java` | 1:1 | `Success bool` |

---

### 1.3 rpc 组件（Go `bootstrap + client + config + domain/entity + domain/model + domain/service + exception + grpc + mapper + metrics + service`，28 个非测试 `.go` + 4 个 `.sql`）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `rpc/favorite.go` | `favorite-rpc/…/rpc/FavoriteRpcApplication.java` | `service/favorite/src/main/java/com/rag/favorite/FavoriteServiceApplication.java` | N:1 | Go 的 rpc main（`:26-56`）与 api main 合并（**D1**）。Go `:31-35` 的 **v2 启动拒绝门禁**（`os.Exit(2)`）落到 `FavoriteSubjectRefGate` |
| `rpc/favorite.go`（`:31-35` v2 门禁） | `favorite-rpc/…/rpc/bootstrap/FavoriteSubjectRefGate.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteSubjectRefGate.java` | 1:1 | `if c.SubjectRefV2Facts && c.Mode != DevMode && c.Mode != TestMode { os.Exit(2) }`。⚠️ Go 的 `Mode` 取值域**不在本仓库** ⇒ C 侧用 Spring profile 映射（未决项 U3） |
| `rpc/etc/favorite.yaml` | `favorite-rpc/src/main/resources/application.yml` | `service/favorite/src/main/resources/application.yml` | N:1 | 与 api yaml 合并（见 §1.2 同名行） |
| `rpc/internal/config/config.go` | `favorite-rpc/…/rpc/config/FavoriteRpcProperties.java` | `service/favorite/src/main/java/com/rag/favorite/config/FavoriteRpcProperties.java` | 1:1 | Go `Config = zrpc.RpcServerConf + Postgres + UserRpc + ArticleRpc + SubjectRefV2Facts`（`:9-19`）。C 侧多出 `Fact{dispatch,authority,legacyPreflight}` 三个嵌套段（承载 3 个 `cmd/*` 的环境变量） |
| `rpc/internal/svc/service_context.go` | `favorite-rpc/…/rpc/service/FavoriteDomainService.java` + `…/rpc/client/UserRpcClient.java` + `…/rpc/client/ArticleRpcClient.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` + `service/favorite/src/main/java/com/rag/favorite/client/UserRpcClient.java` + `service/favorite/src/main/java/com/rag/favorite/client/ArticleRpcClient.java` | 1:N | Go 装配根：`InitDB` → `NewFavoriteModel(db, factOptions...)` + `UserRpc` + `ArticleRpc`。C 侧无 `ServiceContext` 类 ⇒ Spring DI 取代 |
| `rpc/internal/server/favorite_service_server.go` | `favorite-rpc/…/rpc/service/impl/FavoriteServiceGrpcImpl.java` | `service/favorite/src/main/java/com/rag/favorite/grpc/FavoriteGrpcService.java` | 1:1 | 7 个方法，每个 2 行：new logic + 转发 |
| `rpc/favoriteservice/favorite_service.go` | `favorite-api/…/api/client/FavoriteRpcClient.java` | `service/favorite/src/main/java/com/rag/favorite/grpc/FavoriteGrpcService.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiService.java` | 1:N | Go 的 **goctl 客户端 stub**（被 api 侧 `service_context.go:24` 引用）。C 侧因域内直连（**D2**）⇒ `@GrpcClient` 客户端文件消失，客户端 stub 的职责落成「服务端实现 + api 侧直连调用」两处 |
| `rpc/internal/metrics/metrics.go` | `favorite-rpc/…/rpc/metrics/FavoriteRpcMetrics.java` | `service/favorite/src/main/java/com/rag/favorite/metrics/FavoriteRpcMetrics.java` | 1:1 | 4 个 CounterVec + 3 个 Observe 函数。**指标完整名与 label 是 L1 观测契约**，见 §6 |
| `rpc/internal/logic/helper.go`（`:26-40` const 块） | `favorite-rpc/…/rpc/config/FavoriteRpcLabels.java` | `service/favorite/src/main/java/com/rag/favorite/config/FavoriteRpcLabels.java` | 1:1 | `folderModule="favorite_folder"` / `itemModule="favorite_item"` / action 5 值 / result 2 值。⚠️ 这些字符串是**运维契约**，拼错 ⇒ 告警静默失效 |
| `rpc/internal/logic/helper.go`（`:48-90` 纯函数组） | `favorite-rpc/…/rpc/service/FavoriteRpcSupport.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteRpcSupport.java` | 1:1 | normalize / toProto / isUniqueViolation 等静态方法 |
| `rpc/internal/logic/helper.go`（`:92-124` `ensureUserExists`） | `favorite-rpc/…/rpc/service/FavoriteUserGuard.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteUserGuard.java` | 1:1 | 跨域身份校验，被 2 个 rpc logic 调用 |
| `rpc/internal/logic/helper.go`（`:126-202` `resolveArticleSnapshot` + `articleCover`） | `favorite-rpc/…/rpc/service/FavoriteArticleSnapshotResolver.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteArticleSnapshotResolver.java` | 1:1 | 文章公开修订冻结；依赖 `ArticleRpc.GetArticle(PublicOnly:true, IncrView:false)` |
| `rpc/internal/logic/create_favorite_folder_logic.go` | `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 7 个 rpc logic → 1 个 `FavoriteDomainService`（AGENTS.md §4.1 锁定的形态） |
| `rpc/internal/logic/create_favorite_logic.go` | `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `rpc/internal/logic/delete_favorite_folder_logic.go` | `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `rpc/internal/logic/delete_favorite_logic.go` | `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `rpc/internal/logic/get_favorite_folders_logic.go` | `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `rpc/internal/logic/get_favorites_by_folder_logic.go` | `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `rpc/internal/logic/update_favorite_folder_name_logic.go` | `favorite-rpc/…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | N:1 | 同上 |
| `rpc/internal/model/data_model.go` | `favorite-rpc/…/rpc/domain/FavoriteFolderEntity.java` + `…/rpc/domain/FavoriteItemEntity.java` | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteFolderEntity.java` + `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteItemEntity.java` | 1:N | Go 的两个 GORM struct（`:6-35`）→ 2 个 `@TableName` Entity。⚠️ 唯一键 / 可空性 / `target_id varchar(255)` 逐列对拍（DDL 探针） |
| `rpc/internal/model/db.go` | `favorite-rpc/src/main/resources/db/migration/V1__create_favorite_folder.sql` … `V6__align_favorite_schema_with_go.sql` + `favorite-rpc/…/rpc/bootstrap/FavoriteSchemaGate.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteSchemaGate.java` + `service/favorite/src/main/resources/db/migration/`（**当前只有 `.gitkeep`**） | 1:N | 🔴 **缺口**：Go 的 `AutoMigrate(3 实体)` + `migrateFavoriteTargetIDToString`（`db.go:33,39`）在 C 侧**尚无落地脚本**；C 侧 `db/migration/` 是空的。`FavoriteSchemaGate` 负责**启动后校验**「Go 侧有、RAG 侧容易静默丢失」的 schema 资产（CHECK / 部分索引 / 2 个不可变触发器 / `favorite_legacy_fact_marker` 整表）⇒ **不抛异常、不阻止启动**（Go 侧是 `log.Fatalln` 退出进程，C 侧单 JVM 里还跑着别的组件 ⇒ 刻意降级为告警） |
| `rpc/internal/model/favorite.go` | `favorite-rpc/…/rpc/mapper/FavoriteFolderMapper.java` + `…/rpc/mapper/FavoriteItemMapper.java` + `…/rpc/service/FavoriteDomainService.java` | `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteFolderMapper.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteItemMapper.java` + `service/favorite/src/main/java/com/rag/favorite/domain/service/FavoriteDomainService.java` | 1:N | Go `FavoriteModel` 8 方法（7 读 + `InsertFolder`）→ 2 个 Mapper + DomainService |
| `rpc/internal/model/favorite_fact.go` | `favorite-rpc/…/rpc/domain/FavoriteFactOutboxEntity.java` + `…/rpc/domain/FavoriteLegacyFactMarkerEntity.java` + `…/rpc/mapper/FavoriteFactOutboxMapper.java` + `…/rpc/mapper/FavoriteLegacyFactMarkerMapper.java` + `…/rpc/service/FavoriteFactEnvelopeFactory.java` + `…/rpc/service/FavoriteAssertionSchemaResolver.java` + `…/rpc/service/FavoriteFactWriteService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteFactOutboxEntity.java` + `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteLegacyFactMarkerEntity.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteFactOutboxMapper.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteLegacyFactMarkerMapper.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteFactEnvelopeFactory.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteAssertionSchemaResolver.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteFactWriteService.java` | 1:N | 🔴 **Go 侧 1 个文件拆成 7 个 Java 文件**：outbox / legacy marker 两张表的 Entity + **两张表的 Mapper** + 冻结信封单点构造 + 断言版本解析 + **3 个事务写的独立 Bean**。拆 `FavoriteFactWriteService` 的原因是 **`@Transactional` self-invocation 静默失效**（AGENTS.md §3.3） |
| `rpc/internal/model/favorite_delivery.go` | `favorite-rpc/…/rpc/service/FavoriteDeliveryValidator.java` + `…/rpc/service/FavoriteFactDispatchService.java` + `…/rpc/domain/FavoriteFactOutboxEntity.java` | `service/favorite/src/main/java/com/rag/favorite/service/FavoriteDeliveryValidator.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteFactDispatchService.java` + `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteFactOutboxEntity.java` | 1:N | wire event / receipt / DC sender / `DispatchFavoriteFactOnce`。⚠️ 投递路径用 `TransactionTemplate` **显式 commit**（Go 是 `tx.Commit()` 后返回错误）⇒ 不用 `@Transactional` |
| `rpc/internal/model/favorite_authority.go` | `favorite-rpc/…/rpc/domain/FavoriteAuthorityFact.java` + `…/rpc/service/FavoriteAuthorityLookupService.java` | `service/favorite/src/main/java/com/rag/favorite/domain/model/FavoriteAuthorityFact.java` + `service/favorite/src/main/java/com/rag/favorite/service/FavoriteAuthorityLookupService.java` | 1:N | 严格 JSON / JCS hash / 权威事实校验。🔴 v1 的 `subject_ref` 是 **3 字段** `{authority_id, tenant_id, subject_id}` ⇒ **不能用** `common/communityfact` 的 v2 形状（2 字段） |
| `rpc/internal/model/favorite_legacy_preflight.go` | `favorite-rpc/…/rpc/fact/FavoriteLegacyPreflightRunner.java` | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteLegacyFactMarkerEntity.java`（**仅数据载体**） | 1:N | 🔴 **缺口**：C 侧**只有数据载体**（marker Entity），**没有预检报表 / 触发入口**。Go 侧是 `FavoriteLegacyPreflight(ctx)` 只读 RR 快照报表 ⇒ C 侧对应物**尚未规划**（决策记录 P6-c）。详见 §1.6 |
| `rpc/internal/model/vars.go` | `favorite-rpc/…/rpc/exception/FavoriteExceptions.java` | `service/favorite/src/main/java/com/rag/favorite/exception/FavoriteExceptions.java` | 1:1 | Go `ErrorNotFound` 哨兵（`vars.go:5-7`）→ Java 异常类型族。⚠️ 哨兵**不自带码翻译**，由 logic 层决定 |
| `rpc/internal/authority/http.go` | `favorite-rpc/…/rpc/fact/FavoriteAuthorityEndpointConfig.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteAuthorityEndpointConfig.java` | 1:1 | 🔴 Go favorite **自持** authority handler，形态是 **path 参数**：`GET /internal/v1/favorite/facts/{producer}/{event_id}`（`:32`）+ `GET /internal/v2/...`（`:63`）⇒ **不能用** RAG 通用 `AuthorityHandlerFactory` 的 query 形态。C 侧包位从 `fact/` 迁到 `bootstrap/`（装配层唯一落点） |
| `rpc/cmd/fact-dispatch/main.go` | `favorite-rpc/…/rpc/fact/FavoriteFactDispatchScheduler.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteFactDispatchScheduler.java` | 1:1 | 见 §1.6 |
| `rpc/cmd/fact-authority/main.go` | `favorite-rpc/…/rpc/fact/FavoriteAuthorityEndpointConfig.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteAuthorityEndpointConfig.java` | 1:1 | 见 §1.6 |
| `rpc/cmd/fact-legacy-preflight/main.go` | `favorite-rpc/…/rpc/fact/FavoriteLegacyPreflightRunner.java` | （**无对应文件 —— 触发入口未规划**） | 1:N | 🔴 **缺口**：见 §1.6 |
| `rpc/pb/favorite.pb.go` | `favorite-proto/src/main/proto/favorite.proto` | `proto/src/main/proto/favorite.proto` | 1:1 | **protoc 生成物 ↔ `.proto` 源**。Go 的 `.proto` 在**仓库根** `proto/favorite.proto`，被 api 与 rpc 共用 |
| `rpc/pb/favorite_grpc.pb.go` | `favorite-proto/src/main/proto/favorite.proto`（service 段） | `…/favorite/contract/src/main/proto/favorite.proto`（service 段） | 1:1 | 7 个 RPC ⇒ 方法路径 `/favorite.FavoriteService/*` |

---

### 1.4 common 组件（Go `service/favorite/common/`，1 个非测试文件）

> 🔴 **本域是唯一「只有常量 + `codeMsg` + 反查索引 + `GRPCError` + `BizCodeFromError`，**无 `CodeError` 类型**」的域。**
> ⇒ C 侧 `contract/` 包里 **不得**出现 `CodeError` 类、**不得**出现 `NewErrCode` / `NewErrCodeMsg`（Go 侧 0 定义 0 引用）。

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `common/errmsg.go`（const 块 `:8-41`） | `favorite-common/…/common/errmsg/FavoriteErrorCode.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` | 1:1 | **24 个常量**整表保留：Success=200 / Error=500 / CodeServerBusy=1015 / 5001–5009 / 5101–5105 / 5201–5203 / 5301–5304。⚠️ 6 个常量在域内无直接调用点但**不可删**（反查表的基础） |
| `common/errmsg.go`（`codeMsg` `:43-76` + `GetErrMsg` `:91-97`） | `favorite-common/…/common/errmsg/FavoriteErrorMessages.java` | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 1:1 | 24 条文案逐字照抄，**含语言**：23 条中文 + 🔴 **5002 = 英文 `not found`**（`:50`，本域唯一英文）+ `OK` / `FAIL`。未命中回退 `codeMsg[Error] = "FAIL"`（回退值本身是 L1） |
| `common/errmsg.go`（`bizCodeByMsg` `:78-89` + `GRPCError` `:104-106` + `BizCodeFromError` `:108-138`） | `favorite-common/…/common/errmsg/FavoriteGrpcError.java` | `service/favorite/src/main/java/com/rag/favorite/contract/GrpcErrorMapper.java` | 1:1 | 🔴 **互逆往返**：`GRPCError` 写的 description 是**业务文案**（不是码数字），`BizCodeFromError` 靠文案反查 ⇒ **业务码在 gRPC status 的 code 字段里是「丢失」的**。反查顺序：**先 message、后 gRPC 标准码**（InvalidArgument→5003 / Unauthenticated→5004 / PermissionDenied→5005 / AlreadyExists→5006 / NotFound→5002 / Internal→5001 / default→1015）。⚠️ 反查表构建**必须 `putIfAbsent`**（与 Go 的 map 遍历顺序等价） |

---

### 1.5 proto 组件

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| `proto/favorite.proto`（仓库根） | `favorite-proto/src/main/proto/favorite.proto` | `proto/src/main/proto/favorite.proto` | 1:1 | `package favorite` 两侧逐字一致 ⇒ 方法路径零 wire 偏离。7 个 message + `service FavoriteService`（7 个 RPC）的**字段号 / 字段名 / 类型逐条不改**。C 侧只改 option 块（`go_package` → `java_package` + `java_multiple_files`） |

---

### 1.6 🔴 三个 `cmd/` 独立进程各自的落点（含缺口）

| A Go 文件 | B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|---|---|
| **P2** fact-dispatch —— `rpc/cmd/fact-dispatch/main.go` | `favorite-rpc/…/rpc/fact/FavoriteFactDispatchScheduler.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteFactDispatchScheduler.java` | 1:1 | ✅ **有落点**。Go 的 `-once` flag（`:23`）降级为配置项（决策 P6-a 已选 ①）；节奏 **1s** / 批次 **16** / 单次 **10s deadline**（`:67,70,71`）逐条保留 |
| **P3** fact-authority —— `rpc/cmd/fact-authority/main.go` | `favorite-rpc/…/rpc/fact/FavoriteAuthorityEndpointConfig.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteAuthorityEndpointConfig.java` | 1:1 | ✅ **有落点**。wire 形态按 **Go 原样**（path 参数）；⚠️ Go 是**独立 HTTP 端口**（`FAVORITE_AUTHORITY_LISTEN`），C 并入主 app 端口（决策 P6-b 已选 ①）⇒ **wire 面变更，未拍板**（未决项 U1） |
| **P4** legacy-preflight —— `rpc/cmd/fact-legacy-preflight/main.go` | `favorite-rpc/…/rpc/fact/FavoriteLegacyPreflightRunner.java` | 🔴 **无对应文件** | 1:N | ❌ **缺口 —— 触发入口尚未规划**。C 侧只有数据载体 `rpc/domain/FavoriteLegacyFactMarkerEntity` + `rpc/service/FavoriteAssertionSchemaResolver`（`favorite_legacy_preflight.go` 的读侧依赖已具备），但**没有**跑一次出报告的入口。决策记录 **P6-c** 待拍板（① Actuator 自定义端点 / ② 一次性 CLI profile / ③ 独立可执行 jar）<br>⚠️ Go 的**退出码语义**（`exit 2` = 未裁决 / `exit 1` = 读取失败 / `exit 0` = clear，`cmd/fact-legacy-preflight/main.go:24,30,38,50`）在 Spring 进程内**没有退出码** ⇒ 需显式设计（未决项 U2）<br>⚠️ 若选 ① Actuator 端点，**必须确保它不被健康检查探活**（否则每次探活都跑一次预检） |

> 📌 **为什么 P2/P3 能合并而 P4 不能直接合并**：Go 的独立进程是**架构意图**（`fact-dispatch/main.go:2` 注释：「It is a separate process so favorite RPC availability does not depend on DC」），不是技术约束 ——
> P2/P3 **零 gRPC 注册**、与 P1 共用同一张 PG；P4 更是纯只读（`favorite_legacy_preflight.go:27-28` 注释 `never mutates`）⇒ 合并无写风险，但**入口形态**（一次性 vs 常驻）不同。

### 1.7 决策锁 —— §1 的改动清单

**换包结构（Go 的 3 个包 → C 的 2 个包 + 3 层子包）7 处同时改**：
1. `favorite-api` + `favorite-rpc` 合成 `favorite-service`；`favorite-common` + `favorite-proto` 合成 `favorite-contract`；
2. Go 的 `internal/{logic,model,server,svc,config,metrics}` 分层在 C 侧改成 `{advice,config,controller,dto,interceptor,service}/{controller,service,dto,advice,config,interceptor}` + `{bootstrap,client,config,domain/entity,domain/model,domain/service,exception,grpc,mapper,metrics,service}/{service,domain,mapper,bootstrap,config,client,metrics,exception}`；
3. Go 的 `internal/model/*.go`（6 个文件）→ C 的 `domain/entity + domain/model`（5 个 Entity）+ `mapper`（4 个 Mapper）+ `domain/service + service`（10 个 Service）；
4. Go 的 `internal/authority/http.go` 从 `internal/` 迁到 C 的 `bootstrap`（装配层唯一落点）；
5. Go 的 `cmd/*` 三个 `main` 从**进程入口**变成**域内组件**（§1.6）；
6. Go 的 `package common`（单文件包）拆成 C 的 `contract/{ErrCodes,ErrMsg,GrpcErrorMapper}`；
7. Go 的 `rpc/pb/*.pb.go`（生成物）变成 C 的 `favorite-contract` 模块的 `favorite.proto` + protoc 插件段。

**为什么换**：Go 的目录结构是 goctl codegen 的产物（13 个 logic 文件是因为 codegen，不是职责边界）；Spring 侧的「一域一 app」要求 HTTP 边界（`advice + config + controller + dto + interceptor + service`）与服务端能力层（`bootstrap + client + config + domain/entity + domain/model + domain/service + exception + grpc + mapper + metrics + service`）在同一进程内**用包区分**而不是用进程区分。⚠️ **代价**：`rpc.*` 这层命名债已登记（决策 P4，本次不改）。

---

## 2. 持久层差异

| 维度 | A Go（真理之源） | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|---|
| ORM | GORM（`gorm:"column:…;uniqueIndex;not null"` struct tag） | MyBatis-Plus `@TableName` + `@TableId` + `BaseMapper` | MyBatis-Plus（沿用 B 列形态） | C 列 4 个 Entity 已在树里，注解待填 |
| Entity / Model 文件 | `rpc/internal/model/data_model.go`（2 struct） | `domain/FavoriteFolderEntity` + `FavoriteItemEntity` | `service/favorite/src/main/java/com/rag/favorite/domain/entity + service/favorite/src/main/java/com/rag/favorite/domain/model` 下同样 2 个 | 一一对应 |
| 数据访问 | `FavoriteModel` 8 方法（GORM 查询） | Mapper 接口（注解式原生 SQL，全仓零 XML） | `service/favorite/src/main/java/com/rag/favorite/mapper` 下 4 个 Mapper 接口 | — |
| jsonb 列 | `favorite_fact_outbox.payload` / `delivery_envelope` | `mapper/JsonbTypeHandler.java`（域内副本） | ⚠️ **C 侧 favorite 域无此文件** ⇒ 应取 `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`（共享层，**D3**） | B 的域内副本在 C 侧归位到 `common/` |
| Schema 演进 | `db.AutoMigrate(3 实体)` + `migrateFavoriteTargetIDToString`（`db.go:33,39`）**+ 4 个手写 SQL** | Flyway 6 个脚本（`V1__create_favorite_folder.sql` … `V6__align_favorite_schema_with_go.sql`） | 🔴 `service/favorite/src/main/resources/db/migration/` **当前只有 `.gitkeep`** | ⚠️ **DDL 事实源有两处**（AutoMigrate + 4 个手写 SQL）；手写 SQL 独占的资产（CHECK / 部分索引 / **2 个不可变触发器** / `favorite_legacy_fact_marker` 整表）`AutoMigrate` **绝不创建** |
| Go 手写 SQL | `001_favorite_fact_outbox.sql` / `002_favorite_fact_delivery.sql` / `003_favorite_target_revision.sql` / `004_favorite_legacy_fact_marker.sql` | 折进 Flyway V1–V6 | 🔴 **未落地**（同上） | 逐条对照见 §2 的「4 张表」小表 |
| 事务 | GORM 显式 `tx.Commit()` / `tx.Rollback()`（3 个事务写 + 投递路径） | 3 个 `@Transactional` 写（`FavoriteFactWriteService`）+ 投递路径 `TransactionTemplate` | 同 B 列形态 | ⚠️ **`@Transactional` self-invocation 静默失效** ⇒ 事务写必须在**独立 Bean** |
| 投递路径的事务 | `favorite_delivery.go:135-195`：`Begin → HTTP 出网 → Commit`（**持行锁跨网络 I/O**） | `TransactionTemplate` **显式 commit/rollback**（**不用 `@Transactional`**） | 待填 | ⚠️ `@Transactional` 会把 blocked / failed 状态一起回滚，而那正是 Go 明确提交的内容 |
| 行锁 | `SELECT … FOR UPDATE`（收藏夹创建/删除锁点）；级联删除 `FOR UPDATE ORDER BY favorite_id` | 同 | 待填 | 排序保证确定性，否则两个事务各算一半 retract 集合、撞 `UNIQUE(favorite_id, aggregate_version)` |
| 雪花 ID | `snowflake.GetID()`（仅 folder_id / favorite_id） | `common/spring/SnowflakeBean` | `common/` starter 自动配置（**D3**） | favorite **无游标** ⇒ 不依赖 `>>22` |
| 幂等 | 唯一键 + `UNIQUE(favorite_id, aggregate_version)` 双保险；`target_type` 小写化后参与 `uk_folder_target` | 同 | 待填 | ⚠️ 小写化必须用**逐 code point** 映射（Go `strings.ToLower` 是简单映射，`İ`→`i`；Java `toLowerCase(Locale.ROOT)` 是完整 Unicode 映射） |

### 2.1 4 张表逐表对照（Go 出处 = DDL 事实源）

| 表名 | Go 出处 | 关键约束 | C 侧落点 |
|---|---|---|---|
| `favorite_folder` | `data_model.go:14-15` + Go struct tag `:6-12` | `uk_user_folder_name(user_id,name)`；`name varchar(100) NOT NULL`；`create_time`/`update_time` | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteFolderEntity.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteFolderMapper.java` |
| `favorite_item` | `data_model.go:33-34` + struct tag `:17-31` | `uk_folder_target(folder_id,target_id,target_type)`；`target_id varchar(255)`（雪花 ID > 2^53）；**无 `update_time`**；`target_revision varchar(96)` 可空 | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteItemEntity.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteItemMapper.java` |
| `favorite_fact_outbox` | `favorite_fact.go:76` + `001_favorite_fact_outbox.sql` / `002_favorite_fact_delivery.sql` | `CHECK (aggregate_version IN (1,2))`；部分索引 `favorite_fact_outbox_pending`；**不可变触发器 `favorite_fact_identity_immutable`**；5 个回执列 | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteFactOutboxEntity.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteFactOutboxMapper.java` |
| `favorite_legacy_fact_marker` | `favorite_fact.go:39` + `004_favorite_legacy_fact_marker.sql` | 5 个 CHECK + **不可变触发器 `favorite_legacy_marker_immutable`** | `service/favorite/src/main/java/com/rag/favorite/domain/entity/FavoriteLegacyFactMarkerEntity.java` + `service/favorite/src/main/java/com/rag/favorite/mapper/FavoriteLegacyFactMarkerMapper.java` |

> ⚠️ **缺 `favorite_legacy_fact_marker` 表 ⇒ 错误码静默改变**：Go 的 `favoriteAssertionSchema` 走 legacy 分支时会拿到 `relation … does not exist`，
> 那**不是** NotFound ⇒ 删除收藏返回 `Internal/5008` 而不是设计的 `FailedPrecondition/5304`。
> 而**只有「没有 v1 断言」的行会走到那里** ⇒ 新建收藏完全正常 ⇒ **只有历史数据才会暴露**。

### 2.2 决策锁 —— §2 的改动清单

**换持久层（GORM → MyBatis-Plus + 幂等 DDL 脚本）6 处同时改**：
1. `rpc/internal/model/data_model.go` 的 2 个 GORM struct → `domain/entity/FavoriteFolderEntity.java` + `FavoriteItemEntity.java`（`@TableName` + `@TableId` + `@TableField`）；
2. `rpc/internal/model/favorite.go` 的 `FavoriteModel` 8 方法 → `mapper/FavoriteFolderMapper.java` + `FavoriteItemMapper.java`（`BaseMapper` + 注解式原生 SQL，全仓零 XML mapper）；
3. `rpc/internal/model/favorite_fact.go` / `favorite_delivery.go` / `favorite_authority.go` 的表访问 → `mapper/FavoriteFactOutboxMapper.java` + `FavoriteLegacyFactMarkerMapper.java`；
4. `rpc/internal/model/db.go` 的 `AutoMigrate(3)` + `migrateFavoriteTargetIDToString` → `favorite/service/src/main/resources/db/migration/*.sql`（**C 侧当前为空 ⇒ 🔴 缺口**）；
5. `rpc/internal/model/vars.go` 的 `ErrorNotFound` 哨兵 → `exception/FavoriteExceptions.java`；
6. jsonb 列的读写 → `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`（**D3** 归位；Entity 需 `@TableName(autoResultMap = true)` + `ps.setObject(i, json, Types.OTHER)`）。

**为什么换**：GORM 的 `AutoMigrate` 隐式 DDL **不会创建**手写 SQL 独占的资产（CHECK / 部分索引 / **2 个不可变触发器** / `favorite_legacy_fact_marker` 整表）⇒ 幂等 DDL 脚本 + 启动后 `FavoriteSchemaGate` 校验是唯一能把「DDL 事实源」收敛到一处的方式。
⚠️ **两处刻意偏离 Go 的机制**：① `@Transactional` 必须落在**独立 Bean**（self-invocation 静默失效）；② 投递路径用 `TransactionTemplate` **显式 commit**（`@Transactional` 会把 Go 明确 `tx.Commit()` 的 blocked / failed 状态一起回滚）。

---

## 3. HTTP / DI 差异

| 维度 | A Go | B RAG | C RAG-V1 | 备注 |
|---|---|---|---|---|
| 进程拓扑 | **4 个进程**（api + rpc + 3 个 `cmd/*`） | 4 个 Maven 模块 = **4 个进程** | **1 个进程**（`favorite-service`） | 决策 **D1** |
| 路由注册 | `rest.WithPrefix("/favorite/v1")` + `server.AddRoutes(...)`（`routes.go:17-59`） | `@RequestMapping("/favorite/v1")` + 方法映射 | 同 B 列 | 前缀是 **L1**，逐字保留 |
| HTTP method | **POST×3 / DELETE×2 / GET×2**（非全 POST，`routes.go:21-52`） | 同 | 同 | `@DeleteMapping` / `@GetMapping` 是 Spring 一等公民 |
| public 路径集 | **空集**（7 条路由**全部** `rest.WithJwt`，`routes.go:57`） | 空集 | ⚠️ **C 侧 `FavoriteController.java` 的 javadoc 写「有 public 段」⇒ 与 Go 相反**（见 §7 缺口表） | 无 public 路由 ⇒ 无需白名单 |
| JWT + blacklist 顺序 | JWT 在 chain **外层**（先跑）→ blacklist 在内层 | Interceptor 顺序 | 同 | ⚠️ 顺序是 L3 语义，必须有断言 |
| Handler / Controller | 7 个薄 handler（各 35 行，逐字同质） | 1 个 `FavoriteController`（7 个方法）+ 7 个 `Favorite*Service` | 1 个 `FavoriteController` + **1 个** `FavoriteApiService` | C 侧 7 个 api 用例合并（用例内聚判据） |
| Logic 中间层 | api 7 logic / rpc 7 logic | api 7 Service / rpc 1 `FavoriteDomainService` | api 1 `FavoriteApiService` / rpc 1 `FavoriteDomainService` | 合并后 HTTP 与 gRPC **共享同一份业务用例** |
| 响应信封 | **逐 handler 手写** `httpx.OkJsonCtx(w,r,&response.Response{Code,Msg,Data})` | `advice/GlobalResponseAdvice` | `service/favorite/src/main/java/com/rag/favorite/advice/FavoriteApiResponseAdvice.java` | 🔴 只包一层；否则双层信封 |
| 错误表达 | 业务失败 ⇒ **HTTP 200 + body 的 `code`**；解析失败也是 **HTTP 200 + 5003** | `advice/GlobalExceptionHandler` | `service/favorite/src/main/java/com/rag/favorite/advice/FavoriteApiExceptionHandler.java` | 🔴 Go 侧**没有** `httpx.ErrorHandler` ⇒ 框架级错误落 go-zero 默认语义（400 + text/plain），**业务错误不走那里** |
| 业务码 → 响应码 | `GetErrMsg(code)` 查表（`errmsg.go:91`） | `FavoriteErrorMessages.getMsg` | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 🔴 文案是 **L1**，含语言 |
| gRPC 错误表达 | `status.Error(grpcCode, GetErrMsg(bizCode))`（`errmsg.go:104`） | `FavoriteGrpcError.grpcError(Status.Code,int)` | `service/favorite/src/main/java/com/rag/favorite/contract/GrpcErrorMapper.java` | ⚠️ **标准 gRPC 码 + 查表文案**（**不是** like 域那种「业务码塞进 grpc-status」的做法） |
| 业务异常 | Go 侧**无 `CodeError` 类型** ⇒ 直接 `status.Error` | `FavoriteExceptions`（rpc 侧） | `service/favorite/src/main/java/com/rag/favorite/exception/FavoriteExceptions.java` | 🔴 哨兵不带码翻译 ⇒ 由 logic 层决定 |
| 配置绑定 | `conf.MustLoad` → `Config` struct（`config.go`） | `@ConfigurationProperties(prefix="favorite-api")` / `("favorite")` | `service/favorite/src/main/java/com/rag/favorite/config/FavoriteApiProperties.java` + `service/favorite/src/main/java/com/rag/favorite/config/FavoriteRpcProperties.java` | 类型安全 |
| 域内调用 | api → rpc **走 gRPC**（etcd 寻址 `favorite.rpc`） | `@GrpcClient("favorite-rpc")` | **直连**（**D2**）⇒ C 侧**无** `FavoriteRpcClient` | — |
| 跨域调用 | `UserRpc` / `ArticleRpc`（etcd key `user.rpc` / `article.rpc`） | `@GrpcClient` + Nacos | `service/favorite/src/main/java/com/rag/favorite/client/UserRpcClient.java` + `service/favorite/src/main/java/com/rag/favorite/client/ArticleRpcClient.java` | 决策 **D6**：跨域仍走 gRPC |
| gRPC 拦截器 | `s.AddUnaryInterceptors(observability.NewUnaryServerInterceptor(...))`（`favorite.go:49`） | `GrpcServerConfigurer.intercept()` **显式挂上** | 待填（`common/src/main/java/com/rag/common/autoconfigure/RagGrpcExceptionMapperAutoConfiguration.java` + `RagObservabilityAutoConfiguration`） | ⚠️ devh starter **不自动注册** ⇒ 漏配 = **静默失效** |
| 服务发现 | etcd（`favorite.rpc` / `user.rpc` / `article.rpc`） | Nacos | Nacos（`service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteNacosRegistration.java`） | 生态替代（**D4**） |
| gRPC 反射 | `c.Mode == DevMode \|\| TestMode` 才 `reflection.Register`（`favorite.go:44-46`） | `grpc.server.reflection-service-enabled` | `application.yml` 写 `false` | 与 Go 的「非 dev/test 不开」一致 |
| v2 启动门禁 | `favorite.go:31-35`：`os.Exit(2)` | `bootstrap/FavoriteSubjectRefGate.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteSubjectRefGate.java` | ⚠️ `Mode` 取值域不在本仓库（U3） |
| 进程级 logger 身份 | 4 个独立 `service` 值（`rtw-favorite-fact-dispatch` / `-fact-authority` / `-legacy-preflight` / gRPC 走 `c.Name`） | `bootstrap/FavoriteFactProcessLogging.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteFactProcessLogging.java` | ⚠️ **不要用 SLF4J 全局 MDC**（会跨线程插值） |
| Schema 闸 | Go：`AutoMigrate` 失败 ⇒ `log.Fatalln` **退出进程** | `bootstrap/FavoriteSchemaGate.java`（**不抛异常、不阻止启动**） | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteSchemaGate.java` | C 侧单 JVM 里还跑着别的组件 ⇒ 刻意降级为告警 |

### 3.1 决策锁 —— §3 的改动清单

**换进程拓扑（Go 4 进程 → C 1 JVM）6 处同时改**：
1. `favorite-api` + `favorite-rpc` 两个模块合成 `favorite-service` 一个模块；
2. `favorite-api/…/FavoriteApiApplication.java` 与 `favorite-rpc/…/FavoriteRpcApplication.java` 合成 `FavoriteServiceApplication.java`；
3. `favorite-api/…/client/FavoriteRpcClient.java` 删除 —— 域内调用改直连（**D2**）；
4. `favorite-common` + `favorite-proto` 合成 `favorite-contract`；
5. `favorite-api/…/advice/*` + `favorite-rpc` 侧错误类合成 `favorite-contract` 的 3 个类 + `exception/FavoriteExceptions.java`；
6. `cmd/fact-dispatch` / `cmd/fact-authority` / `cmd/fact-legacy-preflight` 三个 `main` 的落点改为域内组件 / 运维入口（见 §1.6）。

**为什么换**：Spring Boot 的「一域一 app」（**D1**）让 4 进程拓扑收敛为 1；代价是**失去独立扩缩容**（dispatch 的资源需求与 gRPC 争同一堆内存/CPU）、**多副本下 dispatcher 数量 = JVM 数**（安全，`FOR UPDATE SKIP LOCKED` 本就为多副本设计，但吞吐上限从「1 进程 × 16」变成「每 JVM 16 × N 副本」）、**进程边界变成配置开关**。

**换 HTTP 层形态（7 handler + 7 logic → 1 Controller + 1 Service）5 处同时改**：
1. 删 `favorite-api/…/service/FavoriteCreateFolderService.java` 等 7 个 Service，合并进 `service/FavoriteApiService.java`；
2. `FavoriteController.java` 的 7 个端点方法改为直接调 `FavoriteApiService`；
3. `service/FavoriteApiSupport.java` 保留 `extractUserID` / `userLogOption` / `articleLogOption` / `codeFromRPCError` 四件事（helper 不并进 Service）；
4. `dto` 补 `GetFavoriteFoldersReq.java`（Go 空 struct）；
5. `advice` 两个类改名去掉 `Global` 前缀（`GlobalResponseAdvice` → `FavoriteApiResponseAdvice`，`GlobalExceptionHandler` → `FavoriteApiExceptionHandler`）。

**为什么换**：「用例内聚」判据 5 条全中（同一 `@RequestMapping` 聚合 / 变化原因一致 / 无独立生命周期 / 无独立契约 / 合并后职责不模糊）⇒ 合并为 1 个 Service 是**去掉 goctl codegen 产物**的必然结果，不是「文件少」。

---

## 4. 公共模块对照（favorite 域 ↔ `common/` 的边界）

| 能力 | A Go（`service/common/`，域外共享层） | B RAG（`service/common/`，普通 jar） | C RAG-V1（`common/`，真 starter，**D3**） | favorite 侧接线位置 |
|---|---|---|---|---|
| **communityfact 事件协议** | `service/common/communityfact/wire.go` + `logging.go` + `process.go` + `authority_http.go` | `service/common/.../communityfact/{Wire,Dispatcher,AuthorityHandler,ProcessLogger}.java` | `common/src/main/java/com/rag/common/communityfact/*`（12 个类：`Wire` / `Event` / `TechnicalReceipt` / `DeliveryStatus` / `SubjectRef` / `AuthorityFact` / `CanonicalHash` / `Dispatcher` / `EventDispatcher` / `EventSender` / `ProcessLogger` / `ProcessMetadata` / `AuthorityHandler`） | 🔴 **协议本体归 `common.md`**；favorite 侧只写接线：`rpc/service/FavoriteFactEnvelopeFactory.java`（构造 `Wire.Event`）+ `rpc/service/FavoriteDeliveryValidator.java`（校验回执）+ `rpc/bootstrap/FavoriteFactDispatchScheduler.java`（跑 `Dispatcher`）+ `rpc/bootstrap/FavoriteAuthorityEndpointConfig.java`（挂 `AuthorityHandler`） |
| 社区事实 v1 形状 | `rpc/internal/model/favorite_authority.go:27-43`（`FavoriteAuthorityFact` + `FavoriteSubjectRef` **3 字段**） | `rpc/domain/FavoriteAuthorityFact.java` | `service/favorite/src/main/java/com/rag/favorite/domain/model/FavoriteAuthorityFact.java` | ⚠️ **不能用** `common` 的 `Wire.AuthorityFact`（其 `subject_ref` 只有 2 字段 `{issuer,subject_id}`）⇒ favorite **自持 v1 类型** |
| 权威端点 wire 形态 | `service/common/communityfact/authority_http.go:28,50`（**query** 参数 + 固定路径 `/internal/v1/community/facts`） | `common/spring/AuthorityHandlerFactory.java:30` | `common/src/main/java/com/rag/common/communityfact/AuthorityHandler.java` | 🔴 Go favorite **没用**通用 handler，自己写了 **path 参数**版（`rpc/internal/authority/http.go:32,63`）⇒ favorite 侧**不能**直接用 `common` 的通用 handler |
| 信封 | `service/common/response/response.go:3-7`（`{int code; String msg; Object data;}`） | `service/common/.../response/Response.java` | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | `api/advice/FavoriteApiResponseAdvice.java` 引用 |
| 业务异常 | —（Go 无 `CodeError`） | `service/common/.../exception/BusinessException.java` | `common/src/main/java/com/rag/common/exception/BusinessException.java` | `rpc/exception/FavoriteExceptions.java` |
| 鉴权原语 | `rest.WithJwt`（go-zero 内置） | `service/users/users-common/.../jwt/{JwtUtil,BearerTokenExtractor}.java` | `common/src/main/java/com/rag/common/security/{JwtUtil,BearerTokenExtractor}.java` | `api/interceptor/FavoriteJwtAuthInterceptor.java` |
| go-zero `,string` 解析 | go-zero `httpx.Parse` 内置 | `users-common/.../http/GoStyleStringInt64.java` | `common/src/main/java/com/rag/common/web/GoStyleStringInt64.java` | `api/dto/*` 的 `folderId,string` / `favoriteId,string` 字段 |
| go-zero `form:` 解析 | go-zero `httpx.ParseForm`（`GetFavoritesByFolderReq.FolderId`） | `users-common/.../http/GoStyleGetFormValuesFilter`（或等价） | ⚠️ `common/src/main/java/com/rag/common/web/GoStyleGetFormValuesFilter.java` **目前仍是空壳** | `api/dto/GetFavoritesByFolderReq.java`（`folderId` 取自**查询串**，`GET /item/list` 依赖它） |
| 雪花 ID | `service/common/snowflake/snowflake.go` | `service/common/.../snowflake/Snowflake.java` + `spring/SnowflakeBean.java` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` + `common/src/main/java/com/rag/common/autoconfigure/RagSnowflakeAutoConfiguration.java` | 仅 folder_id / favorite_id |
| 业务日志 | `service/common/logger/logger.go` | `service/common/.../logger/{Logger,WithUserID,WithArticleID}.java` | `common/src/main/java/com/rag/common/logger/{GoStyleLogger,LogOption,LogOptions}.java` | `api/service/FavoriteApiSupport.java` + `rpc/service/FavoriteRpcSupport.java` |
| SQL 超时 / `57014` | `service/common/observability/rpc.go` | `service/common/.../persistence/{DeadlineQueryTimeoutInjector,SqlTimeoutStatusTranslator}.java` | `common/src/main/java/com/rag/common/persistence/*` | 隐式（starter 自动装配） |
| jsonb typeHandler | —（Go 用 `[]byte` + GORM） | `favorite-rpc/.../mapper/JsonbTypeHandler.java`（**域内副本**） | `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` | ⚠️ C 侧 favorite 域**不建**此文件（**D3** 归位到共享层） |
| MyBatis-Plus 排序桥 | —（Go 无） | `favorite-rpc/.../config/MybatisPlusOrderingBridgeAutoConfiguration.java`（域内副本，Boot 4 workaround） | `common/src/main/java/com/rag/common/autoconfigure/RagMybatisPlusAutoConfiguration.java` | ⚠️ C 侧 favorite 域**不建**此文件（**D3** 收编 9 份重复） |
| 进程级 OBS 日志 | `service/common/communityfact/logging.go` | `favorite-rpc/.../bootstrap/FavoriteFactProcessLogging.java` | `service/favorite/src/main/java/com/rag/favorite/bootstrap/FavoriteFactProcessLogging.java` | ⚠️ 3 份独立 logger 身份（`favorite.rpc` / `rtw-favorite-fact-dispatch` / `rtw-favorite-fact-authority`） |

### 4.1 决策锁 —— §4 的改动清单

**把共享件从域内副本归位到 `common/` starter 4 处同时改**：
1. 删 `favorite-rpc/…/mapper/JsonbTypeHandler.java`，改引 `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java`；
2. 删 `favorite-rpc/…/config/MybatisPlusOrderingBridgeAutoConfiguration.java`，改由 `common/src/main/java/com/rag/common/autoconfigure/RagMybatisPlusAutoConfiguration.java` 统一提供；
3. `favorite-api/…/advice/*` 的 `Response` / `BusinessException` / `JwtUtil` 改引 `common/` 下的同名件（`ApiResponse` / `BusinessException` / `JwtUtil`）；
4. `favorite-common` 里的 3 个 errmsg 类迁到 `favorite-contract`（**域内**，**不进 `common/`** —— 错误契约**逐域隔离**）。

**为什么换**：**D3**（`service/common` → 真 starter）+ **D4**（生态优先）。但 🔴 **错误码 / 错误文案 / 反查表是逐域隔离的**（favorite 的 `5002` = `not found`，users 的 `5002` = 「更新数据库失败」）⇒ **这三类不进 `common/`**，留在 `favorite-contract`。

---

## 5. 决策锁（全局）

| # | 决策 | 影响本域的 N 处 | 为什么换 |
|---|---|---|---|
| **D1** | api 与 rpc 合并为单一 Spring Boot 应用 | 6 处（§3.1） | Spring Boot 的「一域一 app」；代价是失去独立扩缩容 + 进程边界变配置开关 |
| **D2** | 域内调用直连，不再自我 gRPC 往返 | 3 处：删 `FavoriteRpcClient.java`；`FavoriteApiService` 直调 `FavoriteDomainService`；`favorite-api` 的 `FavoriteRpc` 配置项删除 | 同域往返 gRPC 是纯浪费 |
| **D3** | `service/common` → 真 starter | 4 处（§4.1） | 消除 9 份重复的 MP 桥与 10 份 `@Import` 清单 |
| **D4** | 生态优先，手写只补生态缺口 | 2 处：`FavoriteNacosRegistration`（etcd→Nacos）、`MybatisPlusOrderingBridge`（归 `common/`） | 少维护一套自研基建 |
| **D5** | DTO 改 immutable record + Bean Validation | 16 个 `api/dto/*.java` 全部 | Go 的可变 struct → Java record（**契约不变，只改形态**）。⚠️ Bean Validation 是 **L3 降级**：错误文案从 go-zero 改为 Bean Validation 文案 |
| **D6** | 跨域调用仍走 gRPC | 2 个 `rpc/client/*.java` | 跨域是业务能力（取作者资料、取文章快照），不该被 HTTP 层直连 |
| **P6** | 3 个 `cmd/fact-*` 的遗留子项 | 3 处（§1.6） | ⏳ **待拍板**：P6-a `-once` 模式（已选 ①）、P6-b authority 端口（已选 ①）、P6-c **preflight 触发入口（未拍板 ⇒ 本表记为缺口）** |

---

## 6. 跨模块全局约定（值逐条核对 A 列 Go 源码）

| 项 | 值 | Go 出处（`文件:行号`） |
|---|---|---|
| proto package | `favorite` | `proto/favorite.proto:4` |
| proto service / 方法路径 | `service FavoriteService` ⇒ `/favorite.FavoriteService/*` | `proto/favorite.proto:99-106` |
| proto 字段号 | `FavoriteFolder` 1–5；`FavoriteItem` 1–8；`CreateFavoriteFolderReq` 1–2；`CreateFavoriteReq` 1–6；`DeleteFavoriteFolderReq` 1–2；`DeleteFavoriteReq` 1–2；`UpdateFavoriteFolderNameReq` 1–3；`GetFavoriteFoldersReq` 1；`GetFavoritesByFolderReq` 1–2；各 Resp 1（`bool success = 1` / `int64 folder_id = 1` / `int64 favorite_id = 1` / `repeated … = 1`） | `proto/favorite.proto:6-97` |
| HTTP path 前缀 | `/favorite/v1` | `api/internal/handler/routes.go:58` |
| HTTP 端点（7 条） | POST `/folder/create`；DELETE `/folder/delete`；GET `/folder/list`；POST `/folder/update`；POST `/item/create`；DELETE `/item/delete`；GET `/item/list` | `api/internal/handler/routes.go:21-52` |
| HTTP method 分布 | POST×3 / DELETE×2 / GET×2（**非全 POST**） | `api/internal/handler/routes.go:21,26,31,36,41,46,51` |
| public 路径集 | **空集**（7 条全部 `rest.WithJwt`） | `api/internal/handler/routes.go:57` |
| 错误码（24 条） | `Success=200`、`Error=500`、`CodeServerBusy=1015`、`ErrorServerCommon=5001`、`ErrorNotFound=5002`、`ErrorInvalidParam=5003`、`ErrorUnauthorized=5004`、`ErrorForbidden=5005`、`ErrorAlreadyExist=5006`、`ErrorDbSelect=5007`、`ErrorDbUpdate=5008`、`ErrorGenerateID=5009`、`ErrorUserNotFound=5101`、`ErrorUserExists=5102`、`ErrorPasswordInvalid=5103`、`ErrorTokenInvalid=5104`、`ErrorTokenExpired=5105`、`ErrorFavoriteFolderNotFound=5201`、`ErrorFavoriteFolderNameEmpty=5202`、`ErrorFavoriteFolderNameExists=5203`、`ErrorFavoriteNotFound=5301`、`ErrorFavoriteAlreadyExist=5302`、`ErrorFavoriteTargetEmpty=5303`、`ErrorFavoriteHistoryBlocked=5304` | `service/favorite/common/errmsg.go:9-40` |
| 错误文案语言 | 22 条中文 + 🔴 **`ErrorNotFound(5002)` = 英文 `not found`** + `Success`="OK" / `Error`="FAIL" | `service/favorite/common/errmsg.go:43-76`（`:50` 是唯一英文） |
| 错误码反查顺序 | **先按 message 反查**（`bizCodeByMsg`），**查不到才**按 gRPC 标准码 fallback（InvalidArgument→5003 / Unauthenticated→5004 / PermissionDenied→5005 / AlreadyExists→5006 / NotFound→5002 / Internal→5001 / default→1015） | `service/favorite/common/errmsg.go:118-137` |
| gRPC 错误形态 | `status.Error(grpcCode, GetErrMsg(bizCode))` ⇒ **description 装的是业务文案**，业务码在 code 字段**丢失** | `service/favorite/common/errmsg.go:104-106` + `:108-138` |
| HTTP 错误码（解析失败） | **HTTP 200 + `{code:5003, msg:"参数错误", data:null}`**（7 个 handler 逐字同质） | `api/internal/handler/favorite/*.go:18-25` |
| JWT claim | `userId`（go-zero 写入的是 `json.Number` ⇒ **主路径是 `json.Number` 分支**） | `api/internal/logic/favorite/helper.go:15-34` |
| JWT secret | `ae0536f9-6450-4606-8e13-5a19ed505da0`（与 `users` 域 **同一密钥域**，签发方是 user-api） | `api/etc/favorite.yaml:11` |
| JWT `AccessExpire` | `2592000` —— **api 侧是死配置**（只签发方 user-rpc 需要它） | `api/etc/favorite.yaml:12`；全仓无 api 侧读取点 |
| Redis blacklist key | `user:jwt_blacklist:%s` | `api/internal/middleware/checkblacklist_middleware.go:36` |
| Redis 401 body | **只有 `code` + `msg` 两个字段，没有 `data`**；`Content-Type: application/json;charset=utf-8`；HTTP 401 | `api/internal/middleware/checkblacklist_middleware.go:38-46` |
| 表名 | `favorite_folder`、`favorite_item`、`favorite_fact_outbox`、`favorite_legacy_fact_marker` | `rpc/internal/model/data_model.go:15,34`；`rpc/internal/model/favorite_fact.go:76,39` |
| 唯一键 | `uk_user_folder_name(user_id,name)`、`uk_folder_target(folder_id,target_id,target_type)`、`UNIQUE(favorite_id,aggregate_version)` | `rpc/internal/model/data_model.go:8-9,21-24`；`001_favorite_fact_outbox.sql` |
| DB | `first_db` / user `admin` / schema `public` | `rpc/etc/favorite.yaml:13-19` |
| 端口 | api HTTP `:8890`；rpc gRPC `:8082`；DevServer api `:16063` / rpc `:16062` | `api/etc/favorite.yaml:3,28`；`rpc/etc/favorite.yaml:2,25` |
| etcd key | `favorite.rpc` / `user.rpc` / `article.rpc` | `api/etc/favorite.yaml:37`；`rpc/etc/favorite.yaml:30,36,40` |
| 指标完整名 | `favorite_request_total`、`favorite_request_seconds_counter`、`favorite_ops_total`、`favorite_db_error_total`（= `namespace_subsystem_name`，namespace 为空） | `rpc/internal/metrics/metrics.go:12-43` |
| 指标 label | request：`module,action,result`；seconds：`module,action`；ops：`module,action,result`；db_error：`module,action,type` | `rpc/internal/metrics/metrics.go:12-43` |
| label 取值 | `module` ∈ {`favorite_folder`,`favorite_item`}；`action` ∈ {`create`,`delete`,`list`,`update_name`,`list_by_folder`}；`result` ∈ {`success`,`fail`} | `rpc/internal/logic/helper.go:26-40` |
| Kafka topic | **无** —— favorite 域全仓无 kafka 引用 ⇒ **不引 `spring-kafka`** | `service/favorite/**` grep `kafka` = 0 命中 |
| 雪花 ID 用途 | 仅 `folder_id` / `favorite_id`（**无游标** ⇒ 不依赖 `>>22`） | `rpc/internal/logic/create_favorite_folder_logic.go`、`create_favorite_logic.go` |
| communityfact 协议 | `Event` 9 字段（`event_id/event_type/schema_version/producer/aggregate_id/aggregate_version/operation_id/occurred_at/payload`）；`TechnicalReceipt` 7 字段；`DeliveryStatus` 0/1/2/3；JCS → SHA-256 → hex | `service/common/communityfact/wire.go`；`rpc/internal/model/favorite_delivery.go:26-46`、`rpc/internal/model/favorite_fact.go:15-20` |
| v1 事实 `subject_ref` | **3 字段** `{authority_id:"rtw.identity", tenant_id:"platform", subject_id:"1001"}` | `rpc/internal/model/favorite_authority.go:106-112` |
| v2 事实 `subject_ref` | 2 字段 `{issuer, subject_id}`（**与 `common` 的 `Wire.SubjectRef` 一致**） | `rpc/internal/model/favorite_authority.go`（v2 分支） |

---

## 7. 覆盖度自查

### 7.1 C 列零遗漏

```bash
find "/Users/pr/Documents/GitHub/RAG-V1/service/favorite" -name '*.java' \
  -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u
```

**结果：60 个 `.java`（去重后 60 个 basename —— 本域无同名文件）。全部出现在本表 C 列，覆盖率 60 / 60 = 100%。**

| C 列位置 | 文件数 | 覆盖 |
|---|---|---|
| `service/favorite/src/main/java/com/rag/favorite/contract/` | 3（`ErrCodes` / `ErrMsg` / `GrpcErrorMapper`） | §1.4 |
| `service/favorite/src/main/java/com/rag/favorite/FavoriteServiceApplication.java` | 1 | §1.2 / §1.3 |
| `service/favorite/src/main/java/com/rag/favorite/advice` | 2 | §1.2 |
| `service/favorite/src/main/java/com/rag/favorite/config` | 2 | §1.2 |
| `service/favorite/src/main/java/com/rag/favorite/controller` | 1 | §1.2 |
| `service/favorite/src/main/java/com/rag/favorite/dto` | 16 | §1.2 |
| `service/favorite/src/main/java/com/rag/favorite/interceptor` | 1 | §1.2 |
| `service/favorite/src/main/java/com/rag/favorite/service` | 2（`FavoriteApiService` + `FavoriteApiSupport`） | §1.2 |
| `service/favorite/src/main/java/com/rag/favorite/bootstrap` | 6 | §1.3 / §1.6 |
| `service/favorite/src/main/java/com/rag/favorite/client` | 2 | §1.3 |
| `service/favorite/src/main/java/com/rag/favorite/config` | 2 | §1.3 |
| `service/favorite/src/main/java/com/rag/favorite/domain/entity + service/favorite/src/main/java/com/rag/favorite/domain/model` | 5 | §1.3 |
| `service/favorite/src/main/java/com/rag/favorite/exception` | 1 | §1.3 |
| `service/favorite/src/main/java/com/rag/favorite/mapper` | 4 | §1.3 |
| `service/favorite/src/main/java/com/rag/favorite/metrics` | 1 | §1.3 |
| `service/favorite/src/main/java/com/rag/favorite/domain/service + service/favorite/src/main/java/com/rag/favorite/service` | 10 | §1.3 |
| `service/favorite/src/main/java/com/rag/favorite/grpc` | 1 | §1.3 |
| **合计** | **60** | ✅ |

### 7.2 A 列不遗漏职责

```bash
cd "/Users/pr/Documents/java&golang/Sea-RideTheWind_golang/service/favorite" \
  && find . -name '*.go' -not -name '*_test.go' | sort
```

**结果：50 个非测试 `.go`（其中 2 个是 protoc 生成物 `rpc/pb/*.pb.go`）。全部出现在本表 A 列，覆盖率 50 / 50 = 100%。**

| A 列分区 | `.go` 数 | 覆盖 | 无对应物 / 备注 |
|---|---|---|---|
| `api/` | 21 | §1.2 | — |
| `common/` | 1 | §1.4 | — |
| `rpc/`（含 2 个 `pb/*.pb.go`） | 28 | §1.3 | — |
| **合计** | **50** | ✅ | — |

**A 列之外但影响本域的 Go 文件（域外共享层，不计入 50）**：

| Go 文件 | 归属 | 本表落点 |
|---|---|---|
| `service/common/communityfact/{wire,logging,process,authority_http}.go` | 域外共享层 | §4（协议本体归 `common.md`） |
| `service/common/response/response.go` | 域外共享层 | §4 |
| `service/common/logger/logger.go` | 域外共享层 | §4 |
| `service/common/snowflake/snowflake.go` | 域外共享层 | §4 |
| `proto/favorite.proto`（仓库根） | 域外（api+rpc 共用） | §1.5 |
| `rpc/internal/model/00{1,2,3,4}_*.sql` | 域内（非 `.go`） | §2.1（DDL 事实源） |

### 7.3 映射类型分布

> 统计口径：**本表内 6 张映射表**（§0 / §1.2 / §1.3 / §1.4 / §1.5 / §1.6）的数据行，共 **84 行**；
> 每行的映射类型单元格必填且只用 4 种合法词（`1:1` / `1:N` / `N:1` / `C 新增`）。

| 映射类型 | 行数 | 占比 | 说明 |
|---|---|---|---|
| `1:1` | 41 | 48.8% | 「Go 1 文件 ↔ Java 1 文件」的直接对应（含 B→C 1:1 而 A 侧无 Go 对应物的 2 行） |
| `1:N` | 22 | 26.2% | Go 单文件拆成多个 Java（`favorite_fact.go` → 5 个、`favorite_delivery.go` → 3 个、`types.go` 逐 struct、3 个 `cmd/*` 的部分落点） |
| `N:1` | 20 | 23.8% | 多文件合 1（7 个 api logic → `FavoriteApiService`、7 个 rpc logic → `FavoriteDomainService`、2 份 yaml → 1 份 `application.yml`、2 个 main → 1 个 `FavoriteServiceApplication`、3 段 `common/errmsg.go` → 3 个 contract 类） |
| `C 新增` | 1 | 1.2% | `GetFavoriteFoldersReq.java`（Go 侧空 struct，B 侧未建 DTO） |

> ✅ **合法词校验**：全文仅出现 `1:1` / `1:N` / `N:1` / `C 新增` 四种，**无 `N:M` / `1:1:1` 等自造词**；
> 84 行数据行**无一空缺**（校验脚本：`按 markdown 表块解析，定位表头中 `映射类型` 所在列，逐行断言 ∈ 4 词集合`）。

### 7.4 🔴 三列之间「职责对不上」的地方（逐条登记）

| # | 项 | A 列 Go（事实） | B 列 RAG | C 列 RAG-V1 | 性质 |
|---|---|---|---|---|---|
| **M1** | **`POST /favorite/v1/folder/create` 端点缺失** | `routes.go:21-22` 有 7 条路由，**含** `POST /folder/create` | `FavoriteController.java` 7 个端点齐 | 🔴 `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` 的 javadoc 只列 **6 条**，**漏了 `POST /favorite/v1/folder/create`**（其自述的「Post3」分布也与所列 6 条自相矛盾） | **C 列 javadoc 与 Go 不一致** ⇒ 实现时按 Go 补第 7 条 |
| **M2** | **public 路径集** | 7 条路由**全部** `rest.WithJwt` ⇒ **public 集为空** | 空集 | 🔴 `service/favorite/src/main/java/com/rag/favorite/controller/FavoriteController.java` javadoc 写「**有 public 段**」；`service/favorite/src/main/java/com/rag/favorite/config/WebMvcConfig.java` javadoc 写「注册 … + **public 路径白名单**」 | **C 列 javadoc 与 Go 相反** ⇒ 不得凭空造 public 段 |
| **M3** | **preflight 触发入口** | `rpc/cmd/fact-legacy-preflight/main.go` 是一次性 CLI，**退出码即门禁**（0=clear / 1=读取失败 / 2=未裁决） | `favorite-rpc/…/fact/FavoriteLegacyPreflightRunner.java`（有） | 🔴 **无对应文件**；只有 `FavoriteLegacyFactMarkerEntity` + `FavoriteAssertionSchemaResolver`（数据侧） | **C 列缺口** ⇒ 决策 P6-c 待拍板（Actuator 端点 / CLI profile / 独立 jar） |
| **M4** | **DDL 迁移脚本** | `db.go:33` `AutoMigrate(3)` + `001`–`004` 四个手写 SQL（含 2 个不可变触发器 + `favorite_legacy_fact_marker` 整表） | Flyway 6 个脚本齐 | 🔴 `service/favorite/src/main/resources/db/migration/` **只有 `.gitkeep`** | **C 列缺口** ⇒ 触发器缺失会让 `5304` 静默变成 `5008`（只有历史数据暴露） |
| **M5** | **`JsonbTypeHandler` / MP 排序桥的归属** | Go 无（`[]byte` + GORM） | **域内副本**：`favorite-rpc/…/mapper/JsonbTypeHandler.java`、`…/config/MybatisPlusOrderingBridgeAutoConfiguration.java` | C 侧 favorite 域**无**这两个文件 ⇒ 应取 `common/src/main/java/com/rag/common/persistence/JsonbTypeHandler.java` 与 `common/src/main/java/com/rag/common/autoconfigure/RagMybatisPlusAutoConfiguration.java` | **D3 归位**（不是缺陷，是形态差异）⇒ 填实现时不要在域内重建副本 |
| **M6** | **`GetFavoritesByFolderReq.FolderId` 的取参位置** | `types.go:60` 是 **`form:"folderId,string"`** ⇒ 取自**查询串**，且走 go-zero 的 `,string` 解析 | `favorite-api/…/dto/GetFavoritesByFolderReq.java`（承载） | `service/favorite/src/main/java/com/rag/favorite/dto/GetFavoritesByFolderReq.java` javadoc 只写「immutable record」，**未标注 `form` 语义**；且 C 侧 `common/src/main/java/com/rag/common/web/GoStyleGetFormValuesFilter.java` **仍是空壳** | **C 列待补**：DTO 注释要写明 `form` + `,string`，且依赖 `common` 的 `form` 解析件（该件属 `common.md`，非本域） |
| **M7** | **域内 gRPC client** | api → rpc 走 gRPC（`favoriteservice.NewFavoriteService(zrpc.MustNewClient)`） | `favorite-api/…/client/FavoriteRpcClient.java` | C 侧**无**（**D2** 直连）⇒ `favorite-api` 模块在 C 侧整体消失 | **形态差异**（已定决策 D2），非缺陷 |
| **M8** | **端口号** | api `:8890` / rpc `:8082` | 与 Go 一致 | C 侧 HTTP `:8894` / gRPC `:9003` | **L3 部署层差异**（非 L1），但需在部署文档登记 |

### 7.5 未决项沿用（不在本表结论内，仅指针）

RAG 侧 `favorite-rpc` 台账的 **7 条未决**（`RAG/docs/DRIFT/favorite/rpc.md` §5）**同样适用于 C 列**：
U1 authority wire 形态 · U2 preflight 退出码语义 · U3 `Mode` 取值域 · U4 authority lookup 的 3s 超时 ·
U5 `ObserveRPC` 的 `result` 标签 · U6 `favorite_request_seconds_counter` 指标名 · U7 双侧活体对拍。
⇒ **本表不为它们下任何「已对齐 / 未对齐」结论**；C 列实现前请回 A 列 Go 源码逐字核对。

---

## 一句话总览

`Go favorite` = **4 进程**（api + rpc + 3 个 `cmd/fact-*`）+ GORM + `httpx.Parse` 逐 handler 手写信封 + 24 条域私有错误码（唯一英文 `not found`）+ 4 张表含 2 个不可变触发器；
`RAG` = **4 个 Maven 模块**（api / rpc / common / proto）+ MyBatis-Plus + Flyway 6 脚本 + advice 统一信封 + 域内 gRPC 往返；
`RAG-V1` = **1 个 app + 1 个 contract 模块** + Spring 注解式路由 + immutable record + `BusinessException` 双边界适配 + 域内直连 + 3 个 `cmd/*` 落成域内组件（**preflight 入口待拍板**）。
⇒ **文件名与职责逐条对应；行为以 A 列 Go 源码为准。**
