# Mapping_Table/hot/hot.md — hot 域两方文件名对照表（B RAG ↔ C RAG-V1）

> **本表目的**：把 **RAG**（B 列，判定基准）→ **RAG-V1**（C 列，Spring 惯用目标架构）两棵树里
> **hot 域**的文件名逐条对应起来，方便在 RAG-V1 上填实现时反查「这个 Java 类是从哪个 RAG 文件来的」。
>
> | 列 | 仓库 | 该域路径 |
> |---|---|---|
> | **B (RAG, 判定基准)** | `/Users/pr/Documents/java&golang/RAG` | `service/hot/`（`hot-api` / `hot-common` / `hot-proto` / `hot-rpc` 四模块） |
> | **C (RAG-V1, 本仓目标)** | `/Users/pr/Documents/GitHub/RAG-V1` | `service/hot/`（**一个**模块；错误契约与事件 DTO 在域内 `contract/` 包，proto 在根级 `proto/`） |
>
> **规模基线（实测）**：B = **34 个 `.java`**（不含测试）；C = **31 个 `.java`**（不含测试）。
>
> ⚠️ **C 列目前全是空壳** —— 每个 `.java` 只有 `package` + 空类型 + TODO javadoc ⇒
> **C 列写的是「这个位置该放什么职责」，不是「已经有什么」。**
>
> 格式契约见 [`README.MD`](../README.MD)；章节骨架照抄 RAG 侧 `/Users/pr/Documents/java&golang/RAG/docs/MappingTable/users.md`（跨仓路径，不做链接）。

---

## 〇、读表纪律与映射类型词表

### 三条不可违反的纪律

1. ⚠️ **B 列是唯一行为基准（D16 · 2026-11-02）** —— 本表里所有「值」都以 B 侧源码为准逐条抄出（行号可点验）；
   B 侧与 C 侧不一致的地方，本表**保留差异说明**，不做抹平。⚠️ B 侧自身仍有已登记差异 ⇒ **继承并登记为签字偏离，不回溯第三方。**
2. ⚠️ **「有对应」≠「行为等价」** —— 本表**只回答「文件名 / 职责的对应关系」**，
   **不下「已对齐 / 未对齐」的结论**。行为对齐状态看 RAG 的 `docs/DRIFT/hot/<组件>.md` 的证据强度列。
3. ⚠️ **C 列的空壳 ≠ 缺职责** —— C 列每个 `.java` 目前只有 `package` + 空类型 + TODO javadoc
   ⇒ **C 列写的每一行都是「这个位置该放什么职责」，不是「已经有什么」**。

### 映射类型词表（固定 4 种，不发明新词）

| 类型 | 含义 |
|---|---|
| **1:1** | 一个 B 文件 → 一个 C 文件 |
| **1:N** | 一个 B 文件拆成 N 个 C 文件 |
| **N:1** | 多个 B 文件合到 1 个 C 文件 |
| **C 新增** | B 侧没有对应物，是 RAG-V1 形态本身需要的新文件 |

> **本表对「无对应物」行的记法约定**：映射类型只描述 **B ↔ C 的文件数关系**。
> 当 B 列有文件、而 C 列无落点时，类型仍记 `1:1`（无拆分、无合并），
> 并在「备注」列写明 **「无对应物（原因）」**。当 B 侧无独立文件、C 侧有类时，
> 同样记 `1:1` 并注明「B 侧无独立类 ⇒ 该职责由 B 侧配置 / Spring 原生机制承担」。

### hot 域三条专属要点（本表反复引用）

- **⚠️ 全部端点免鉴权** —— B 侧 `hot-api/…/api/controller/HotArticleController.java` 只有 1 个端点，
  且 **B 侧 `hot-api` 全模块零 interceptor、零 `WebMvcConfig`** ⇒ hot 域**不该有** `*JwtAuthInterceptor`
  （`docs/第二轮深挖-契约盘点.md` §三 T-4 实测：10 域中仅 hot / task 端点免鉴权）。
- **⚠️ `HeavyKeeper` 是 hot 域私有算法类** —— B 侧只有 `hot-rpc/…/rpc/domain/HeavyKeeper.java` 一个文件，
  引用方只有 hot-rpc 自己的 `config` + `mq`（`HotRpcProperties.java:41` + `HotRedisSnapshotStore.java:130`）
  ⇒ **不归 `common/`**（详见 §4）。
- **⚠️ 错误契约逐域隔离** —— hot 域的码值与文案**是英文**（`service busy` / `internal server error`），
  与 article / comment / favorite / like / user 域的中文文案不同 ⇒ **禁止跨域统一**（详见 §1.1）。

---

> 📌 **时点标注（2026-10-26 · D7）** —— 本表若干**决策锁**条目记录的是 **2026-10-25 之前**的形态
> （每域一个 `*-contract` 契约子模块 + `api/` + `rpc/` 两棵包树）。
> 这两处已被 **D7** 取代：① **10 个 `*-contract` 子模块全部删除** —— `.proto` 集中到**根级 `proto/` 模块**（pb 包名 `com.rag.proto.<域>`），
> 错误契约与事件 DTO 落进**域模块内的 `contract/` 包**；② **包压平** —— `api/` + `rpc/` 两棵树合并为**单层技术角色包树**。
> ⇒ **决策锁条目保留原样**（它们记录的是**当时的决策**，不是当前位置）；**当前位置一律看 §0.5「B → C 结构映射速查表」**。

## 0. 顶层模块对照

| B RAG | C RAG-V1 | 映射类型 | 备注 |
|---|---|---|
| `service/hot/hot-api/` | `service/hot/`（`service/hot/src/main/java/com/rag/hot/advice + service/hot/src/main/java/com/rag/hot/config + service/hot/src/main/java/com/rag/hot/controller + service/hot/src/main/java/com/rag/hot/dto + service/hot/src/main/java/com/rag/hot/metrics + service/hot/src/main/java/com/rag/hot/service` 子包） | 1:1 | **D1**：api 侧不再独立成模块，收进 `hot-service` 的 `api/` 子包 |
| `service/hot/hot-rpc/` | `service/hot/`（`service/hot/src/main/java/com/rag/hot/wiring + service/hot/src/main/java/com/rag/hot/client + service/hot/src/main/java/com/rag/hot/config + service/hot/src/main/java/com/rag/hot/domain/model + service/hot/src/main/java/com/rag/hot/grpc + service/hot/src/main/java/com/rag/hot/metrics + service/hot/src/main/java/com/rag/hot/service` 子包） | 1:1 | **D1**：同上；`pb/`（生成物）移到根级 `proto/` |
| `service/hot/hot-common/`（`errmsg/`） | `service/hot/src/main/java/com/rag/hot/contract/`（`contract/`） | 1:1 | ⚠️ **模块名与职责都变了**：B 的 `hot-common` 只装 errmsg；C 的域内 `contract/` 包 = **错误契约 + 事件 DTO**；proto 另落根级 `proto/` |
| `service/hot/hot-rpc/.../domain/HeavyKeeper.java` | `service/hot/src/main/java/com/rag/hot/domain/model/HeavyKeeper.java` | 1:1 | 见 §4；C 的落点由 `第二轮深挖-契约盘点` §六 T-6 判定为「位置待定，性质明确」 |
| `service/hot/hot-proto/src/main/proto/hot.proto`（生成物） | `proto/src/main/proto/hot.proto`（生成物） | 1:1 | 逐字节一致；仅 option 块表达层差异（`go_package` → `java_package` + `java_multiple_files`） |
| — | — | 1:1 | **无对应物**（压测工具，非业务进程；`决策记录.md` D1 明确列为「不属业务进程」） |
| `service/common/`（普通 jar） | `common/`（**仓库根**，真 starter） | N:1 | **D3**；hot 域只用其中 3 个包（§4） |

---

## 0.5 · B → C 结构映射速查表（RAG 的哪个包 → RAG-V1 的哪个包）

> 📌 **本表是 D7（proto 集中 + 模块打平 + 包压平）之后的落点对照** —— 左列是 **RAG 仓（B 列）** 的真实包路径，
> 右列是 **RAG-V1（C 列）** 的真实包路径。**两侧均以 `find` 实测为准**，不是推测。
> ⇒ 用途：拿一个 RAG 侧的文件，能立刻定位到 RAG-V1 侧该读哪个包。

| RAG（B）包 | RAG-V1（C）包 | 说明 |
|---|---|---|
| `hot/api/`（`HotApiApplication`） | `com.rag.hot`（`HotServiceApplication.java`） | 入口类合并（D1）|
| `hot/api/advice/` | `advice/` | 直接压平（`GlobalResponseAdvice` → `HotApiResponseAdvice`）|
| `hot/api/client/` | `client/` | 直接压平 |
| `hot/api/config/` | `config/` | 与 rpc 侧合并 |
| `hot/api/controller/` | `controller/` | 直接压平（`HotArticleController`）|
| `hot/api/dto/` | `dto/article/` | 直接压平后**按业务对象拆 1 个子包**（D11） |
| `hot/api/exception/` | `exception/` | 直接压平 |
| `hot/api/form/`（`GoFormValues`） | `common/src/main/java/com/rag/common/web/GetFormValuesFilter.java` | **上收 `common/`**（D3）|
| `hot/api/metrics/` | `metrics/` | 与 rpc 侧合并 |
| `hot/api/service/` | `service/` | 与 rpc 侧合并 |
| `hot/common/errmsg/` | `contract/` | 错误码（`CodeError` / `ErrCodes` / `ErrMsg` / `GrpcErrorMapper`）|
| `hot/rpc/bootstrap/` | `wiring/` | 直接压平 |
| `hot/rpc/config/` | `config/` | 合并（`HotKafkaConfig` 等）|
| `hot/rpc/domain/`（`HeavyKeeper`） | `domain/model/` | **非 Entity ⇒ 落 `domain/model/`** |
| `hot/rpc/metrics/` | `metrics/` | 合并 |
| `hot/rpc/mq/`（5 类） | **—（C 侧无 `messaging/` 包）** | ⚠️ **`HotEventConsumer` / `HotMergeScheduler` / `HotPartitionSketchRegistry` / `HotRedisSnapshotStore` / `ArticleHotEvent` 无落点**（见 §1.5）|
| `hot/rpc/service/` | `service/` | 合并（无 `domain/service/` —— 本域无 `*DomainService`）|
| `hot/rpc/service/impl/` | `grpc/` | 压平 + 类改名 `HotServiceGrpcImpl` → `HotGrpcService` |
| `hot-proto/` | 根级 `proto/`（pb 包名 `com.rag.proto.hot`）| **集中化**（D7）|

## 1. 包结构对照

### 1.1 契约 —— 错误契约（域内 `contract/` 包）+ proto（根级 `proto/` 模块）

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `hot-common/.../errmsg/HotErrorCode.java` | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` | 1:1 | 码值 L1，逐字照抄：`200` / `500` / `1015` / `5001`–`5008`。**枚举顺序即契约**（B 侧有 `declarationOrderMatchesGoSourceOrder` 断言） |
| `hot-common/.../errmsg/HotErrorMessages.java` | `service/users/common/src/main/java/com/rag/users/common/ErrMsg.java` | 1:1 | ⚠️ **英文文案**（`service busy` / `internal server error` / …）；未知码回退 `"FAIL"`（回退值本身是 L1） |
| `hot-common/.../errmsg/HotErrorCode.java` + `hot-api/.../exception/HotCodeError.java` | `service/hot/src/main/java/com/rag/hot/contract/CodeError.java` | 1:N | ⚠️ B 侧 `HotCodeError` **同时是 error 类型和 JSON 响应体**（`{code,msg}`）⇒ 不能当普通异常丢字段。B 侧构造点：`hot-api/…/api/exception/HotCodeError.java:66`（构造器）+ `:89`（`getMessage()` 覆写为 `ErrCode:%d, Errmsg:%s` 整串，**只进日志**） |
| **无对应类**（B 侧按可达性刻意不建） | `service/hot/src/main/java/com/rag/hot/contract/GrpcErrorMapper.java` | 1:N | ⚠️ **B 侧无对应实现**（全域 grep 实测：hot 域零 gRPC 错误码反查调用点）⇒ C 建的是「B 侧原本不可达」的映射器。**反查算法是 L1**：先按 message 反查 code，查不到才按 gRPC 标准码 fallback（`InvalidArgument→5003` / `Unauthenticated→5004` / `PermissionDenied→5005` / `AlreadyExists→5006` / `NotFound→5002` / `Internal→5001` / default→`1015`） |
| `service/hot/hot-proto/src/main/proto/hot.proto`（生成物） | `proto/src/main/proto/hot.proto`（生成物） | 1:1 | 字段号 / 类型 / message 名 / rpc 签名逐条 1:1（见 §6）；生成产物不入 `.java` 计数 |

### 1.2 `hot-service` · `advice + config + controller + dto + metrics + service` —— HTTP 边界

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `hot-api/.../HotApiApplication.java` + `hot-rpc/.../HotRpcApplication.java` | `service/hot/src/main/java/com/rag/hot/HotServiceApplication.java` | N:1 | **D1**：B 侧 2 个入口（`hot-api/…/api/HotApiApplication.java` + `hot-rpc/…/rpc/HotRpcApplication.java`）→ **1 个 C 入口**（`@SpringBootApplication` + `@EnableDiscoveryClient`） |
| `hot-api/.../advice/GlobalResponseAdvice.java` | `service/hot/src/main/java/com/rag/hot/advice/HotApiResponseAdvice.java` | 1:1 | 产出 `{code:200,msg:"OK",data}`；必须对 `body == null` 与已包装 body 放行 |
| `hot-api/.../advice/GlobalExceptionHandler.java` | `service/hot/src/main/java/com/rag/hot/advice/HotApiExceptionHandler.java` | 1:1 | ⚠️ B 侧**恒 HTTP 200** + `{code,msg,null}`（B 侧 `hot-api/…/api/advice/GlobalExceptionHandler.java:81-137` 全部 handler 都返回 200 口径） |
| `hot-api/.../controller/HotArticleController.java` | `service/hot/src/main/java/com/rag/hot/controller/HotArticleController.java` | 1:1 | 唯一端点 `GET /hot/v1/articles`（B 侧 `hot-api/…/api/controller/HotArticleController.java:54` + `:77`）；B 侧用注解式路由，**无独立路由表** |
| `hot-api/.../service/HotArticleQueryService.java` + `HotArticleHydrator.java` + `HotRankPaginator.java` | `service/hot/src/main/java/com/rag/hot/service/HotApiService.java` + `HotApiSupport.java` | 1:N | B 侧 3 类（编排 `HotArticleQueryService` / 并发补全 `HotArticleHydrator` / 分页数值 `HotRankPaginator`）→ **C 2 类**（编排 + 辅助）。分页阈值 1/20/50/100、稠密跨页 rank、并发窗口 8 都在这两类里 |
| `hot-api/.../dto/HotArticlesReq.java` | `service/hot/src/main/java/com/rag/hot/dto/article/HotArticlesReq.java` | 1:1 | form tag `page` / `page_size`（**下划线**）；两层默认值（form tag 默认 1/20 + logic 层 default 1/20）缺一不可 |
| `hot-api/.../dto/HotArticlesResp.java` | `service/hot/src/main/java/com/rag/hot/dto/article/HotArticlesResp.java` | 1:1 | `items` / `total` / `page` / `page_size` / `scope`（`scope` 恒 `"rolling"`） |
| `hot-api/.../dto/HotArticleCard.java` | `service/hot/src/main/java/com/rag/hot/dto/article/HotArticleItem.java` | 1:1 | ⚠️ **命名债**：B 刻意改名 `HotArticleCard` 以避开与 proto message `hot.HotArticleItem`（**只有 2 字段**）同名；C 沿用了 `HotArticleItem` ⇒ 两个同名不同形的类型并存，易误用 |
| `hot-api/.../metrics/HotApiMetrics.java` | `service/hot/src/main/java/com/rag/hot/metrics/HotApiMetrics.java` | 1:1 | 5 个指标名 L1（§6）；⚠️ `observeReject`（B 侧 `hot-api/…/api/metrics/HotApiMetrics.java:196`）**零触发点**（全域 grep 只有定义行）⇒ 保留指标名、**不补触发点** |
| **无对应类**（B 按 `FileTree/hot/api.md` §12.5「刻意不建」—— 字段全被 Spring 原生属性接管，hot 无专属阈值） | `service/hot/src/main/java/com/rag/hot/config/HotApiProperties.java` | 1:1 | ⚠️ C 建了但**职责待定**：B 侧 api 侧无任何专属阈值（`defaultPage` / `maxPageSize` / `maxHotArticles` / `hydrationConcurrency` 是**编译期常量**，不是配置） |
| `hot-api/.../client/HotRpcClient.java` | **无对应物** | 1:1 | **D2**：同域调用改直连 ⇒ `HotApiService` 直接注入 `rpc/service/HotRankingQueryService`（不再自我 gRPC 往返） |
| `hot-api/.../client/ArticleRpcClient.java` | `service/hot/src/main/java/com/rag/hot/client/ArticleRpcClient.java` | 1:1 | **D6**：跨域仍走 gRPC；包位从 `api/client/` 迁到 `rpc/client/`（依据：`完全spring化的文件树.md` §3.3.8） |
| `hot-api/.../HotApiApplication.java`（Spring DI 取代） | `service/hot/src/main/java/com/rag/hot/HotServiceApplication.java`（Spring DI 取代） | 1:1 | **ABSORB**：`ServiceContext` 在 C 侧无对应文件 |
| `hot-api/.../client/GoStyleGrpcCallDeadline.java` | `service/hot/src/main/java/com/rag/hot/config/HotApiProperties.java` + `service/hot/src/main/resources/application.yml` 的 `grpc.client.*` 段 | 1:1 | ⚠️ **C 侧无独立 deadline 类** —— devh starter 不提供默认 deadline ⇒ 必须**显式配**（否则退化为无限等待） |
| `hot-api/.../form/GoFormValues.java` | `common/src/main/java/com/rag/common/web/GetFormValuesFilter.java`（**跨域共享，不在 hot 域内**） | 1:N | **D5**：C 侧 DTO 改 immutable record + Bean Validation ⇒ ⚠️ **L3 降级**（错误文案从 B 侧的 form 解析文案改为 Bean Validation 文案） |
| `hot-api/.../config/HotHydrationExecutorConfiguration.java` | `service/hot/src/main/java/com/rag/hot/config/HotHydrationExecutorConfiguration.java` | 1:1 | B 侧 `HotHydrationExecutorConfiguration`（`ThreadPoolTaskExecutor`，池 64 + 队列 64，`HotHydrationExecutorConfiguration.java:82-85`）⇒ C 侧同形态落成有界池 + 自带生命周期；**每请求并发窗口 8** 由 `HotArticleHydrator` 的 `Semaphore(8)` 施加（B 侧 `HotArticleHydrator.java:29,67`） |
| **无对应类**（B 侧的 2s 出向封顶由**共享层** `rag.protection.outbound-deadline.cap-millis = 2000` 承担，装在 **gRPC 出向拦截器**上） | `service/hot/src/main/java/com/rag/hot/config/HotDomainQueryConfiguration.java` | **C 新增**（2026-11-05）| 🔴 **D2 域内直连的连带后果**：域内直连**不经 gRPC** ⇒ 共享层那个封顶**管不到它** ⇒ ⇒ C 侧**域内自持** 2s 上界（`hot-api.domain-query-timeout` = 2000 = **B 侧同一个数**）⇒ ⇒ 失败形状回到与 B 侧**逐条一致**（`200` + 5001 信封）。执行器用 Spring `SimpleAsyncTaskExecutor` 的**虚拟线程**模式 ⇒ **不排队**（与 gRPC「直发」同形）；**不能用** `hotHydrationExecutor`（CallerRuns ⇒ 超时等不到） |
| **无对应类**（B 侧 `FileTree/hot/api.md` §12.5「刻意不建」） | `service/hot/src/main/java/com/rag/hot/config/WebMvcConfig.java` | C 新增 | 🔴 **待确认 / 疑似多余** —— C 的该类 javadoc 写「注册 JwtAuthInterceptor + public 路径白名单」，与「hot 域全部端点免鉴权」**直接矛盾**；且 `File_Tree/hot/hot-service.md` §四 自己也写「本域**不该有** `*JwtAuthInterceptor`」⇒ **C 树内部自相矛盾**，需拍板 |

### 1.3 `hot-service` · `wiring + client + config + domain/model + grpc + metrics + service` —— 领域 / 服务端能力层

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注 |
|---|---|---|
| `hot-rpc/.../service/impl/HotServiceGrpcImpl.java` | `service/hot/src/main/java/com/rag/hot/grpc/HotGrpcService.java` | 1:1 | `@GrpcService`；5 个 override（`GetHotArticles` + 4 个互动） |
| `hot-rpc/.../service/HotRankingQueryService.java` | `service/hot/src/main/java/com/rag/hot/service/HotRankingQueryService.java` | 1:1 | 唯一有逻辑的 rpc 方法；`redis.Nil` → 空 items（不能冒泡）、`uint32(score)` **向零截断**（不能 `Math.round`） |
| `hot-rpc/.../service/InteractionStubService.java` | `service/hot/src/main/java/com/rag/hot/service/InteractionStubService.java` | N:1 | ⚠️ **不对称**：like / coin / share → `UNIMPLEMENTED`；**comment → `success=true`**（B 侧既定行为，照抄不得「修齐」） |
| `hot-rpc/.../mq/HotEventConsumer.java` + `HotPartitionSketchRegistry.java` + `HotRedisSnapshotStore.java` + `HotMergeScheduler.java`（+ `HotEventConsumer`） | `service/hot/src/main/java/com/rag/hot/messaging/HotEventConsumer.java` + `service/hot/src/main/java/com/rag/hot/messaging/HotPartitionSketchRegistry.java` + `service/hot/src/main/java/com/rag/hot/messaging/HotRedisSnapshotStore.java` + `service/hot/src/main/java/com/rag/hot/messaging/HotMergeScheduler.java` | 1:N | ✅ **已落地（2026-11-01）**：C 侧新建 `messaging/` 包 ⇒ B 侧 `hot-rpc/…/mq/` 这一组的 5 项职责（① Kafka 消费 ② 分区 sketch 生命周期 ③ Redis 影子 + `RENAME` 原子快照 ④ 跨分片合并求和 ⑤ 分布式锁 + 看门狗）**全部有了载体** ⇒ **缺口 G-1~G-4 关闭** |
| `hot-rpc/.../mq/ArticleHotEvent.java` | `service/hot/src/main/java/com/rag/hot/contract/ArticleHotEvent.java` | 1:1 | ✅ **已落地（2026-11-01）**：落**域内 `contract/` 包**（用户 2026-11-01 定）⇒ 缺口 G-5 关闭。⚠️ 必须 `@JsonIgnoreProperties(ignoreUnknown=true)`（B 侧 `hot-rpc/…/mq/ArticleHotEvent.java:22` 已带该注解）—— 生产侧实测多发 `user_id` / `timestamp`，消费端必须忽略未知字段 |
| `hot-rpc/.../domain/HeavyKeeper.java` | `service/hot/src/main/java/com/rag/hot/domain/model/HeavyKeeper.java` | 1:1 | **必须移植**：FNV 指纹 + `depth × width` 桶表 + 最小堆。⚠️ 三条语义要精确复刻：`uint32` 回绕、概率衰减不可断言精确值、同 count 顺序不稳定 |
| `hot-rpc/.../config/HotRpcProperties.java` | `service/hot/src/main/java/com/rag/hot/config/HotRpcProperties.java` | 1:1 | `HeavyKeeper{width,depth,decay,topK}` + `Interaction{syncEvery,ttl,partitionCount,weights[]}` 内嵌；`RpcServerConf` / Redis 交给 Spring 原生属性 |
| **无对应类**（B 侧 Redis / Kafka 连接与生产参数走 Spring 原生属性） | **无对应物** | 1:1 | ⚠️ B 侧 `HotRpcProperties` 只承载 **HeavyKeeper / Interaction** 两段业务参数（`HotRpcProperties.java:24,41`），Redis 连接与 Kafka 生产配置由 `application.yml` 原生属性承担 ⇒ C 侧同样**不建额外配置类** |
| `hot-rpc/.../config/HotKafkaConfig.java` | `service/hot/src/main/java/com/rag/hot/config/HotKafkaConfig.java` | 1:1 | 承载 L3「MQ 失败处理」语义：分区分配回调 + 错误处理策略。⚠️ `auto-offset-reset=latest`（不回放历史）与 `RoundRobinAssignor`（影响 `hkMap` 布局）是 L1 语义 |
| `hot-rpc/.../bootstrap/NacosRegistration.java` | `service/hot/src/main/java/com/rag/hot/wiring/NacosRegistration.java` | 1:1 | etcd flat key `hot.rpc` → Nacos `SERVICE_NAME="hot-rpc"` |
| `hot-rpc/.../metrics/HotRpcMetrics.java` | `service/hot/src/main/java/com/rag/hot/metrics/HotRpcMetrics.java` | 1:1 | 指标名 L1：`article_hot_kafka_consume_total{type}` / `article_hot_redis_sync_total{status}`（B 侧 `hot-rpc/…/rpc/metrics/HotRpcMetrics.java:37,40`） |
| **无对应类**（B 侧无独立 metrics HTTP 端口，不迁移） | **无对应物**（C 侧由 Actuator `management.endpoints.web.exposure.include: prometheus` 在主端口暴露） | 1:1 | ⚠️ B 侧 `HotRpcMetrics` 只作为 Micrometer `Counter` 的持有者被 `HotEventConsumer` / `HotRedisSnapshotStore` 注入（`HotEventConsumer.java:62` + `HotRedisSnapshotStore.java:93`）⇒ **无独立 metrics 端口**；C 用 Actuator 是**表达层**替换，不是行为变更 |
| `hot-rpc/.../config/Jackson2ObjectMapperConfiguration.java` + `mq/ArticleHotEvent.java` | `service/hot/src/main/java/com/rag/hot/config/HotKafkaConfig.java` | 1:1 | ⚠️ B 侧那个类是 **Boot 4 拆模块**（Jackson 2 / 3）的补丁；C 侧由 `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration` 统一承担 ⇒ **C 域内不需要** |
| — | — | 1:1 | **无对应物**（压测用的 Kafka 生产器，非业务进程；`决策记录.md` D1 明确列为「不属业务进程」） |
| — | — | 1:1 | **无对应物**（压测工具，非业务进程） |

### 1.4 B 有而 C 无（清单 + 归位建议）

> 这 5 个 B 文件是 **B 侧有、C 侧无**。它们不是「RAG 特有」，而是 **C 侧还没建的位置**。列在此处是为了避免「以为 C 覆盖了 B」。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 归位建议 |
|---|---|---|
| `hot-api/.../client/HotRpcClient.java` | — | 1:1 | **不需要** —— D2 域内直连 |
| `hot-api/.../client/GoStyleGrpcCallDeadline.java` | `service/hot/src/main/java/com/rag/hot/config/HotApiProperties.java` + `application.yml` 的 `grpc.client.*` | 1:1 | 落进配置（无独立类） |
| `hot-api/.../form/GoFormValues.java` | `common/src/main/java/com/rag/common/web/GetFormValuesFilter.java`（**跨域共享**） | 1:N | 落进 `common/`（C 已有该类） |
| `hot-rpc/.../config/Jackson2ObjectMapperConfiguration.java` | `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java` | 1:1 | 落进 `common/`（Boot 4 拆模块补丁） |
| `hot-rpc/.../mq/` **5 个文件** | `service/hot/src/main/java/com/rag/hot/messaging/`（4 个：`HotEventConsumer` / `HotPartitionSketchRegistry` / `HotRedisSnapshotStore` / `HotMergeScheduler`）+ `service/hot/src/main/java/com/rag/hot/contract/ArticleHotEvent.java` | 1:N | ✅ **已落地（2026-11-01）**：`messaging/` 包已建（**5 个 B 类里 4 个落 `messaging/`、载荷 `ArticleHotEvent` 落域内 `contract/`**） |

### 1.5 C 列缺位汇总（B 侧有职责、C 侧无落点）—— 2026-11-01 销账：G-1~G-5 已关闭

> ✅ **2026-11-01**：G-1~G-5（`messaging/` 4 类 + `contract/ArticleHotEvent`）**已落地** ⇒ 下表 C 列已填实；
> **G-6 / G-7** 两行 C 列本就有落点（跨域共享类 / 配置类）⇒ **本域无剩余「无落点」项**。

| B RAG 文件 | C RAG-V1 文件 | 映射类型 | 备注（缺什么 · 影响面） |
|---|---|---|
| `hot-rpc/.../mq/HotEventConsumer.java` | `service/hot/src/main/java/com/rag/hot/messaging/HotEventConsumer.java` | 1:1 | ✅ **已落地（2026-11-01）** —— 热度数据的**唯一入口**；⚠️ 消费入口**任何异常都不外抛** |
| `hot-rpc/.../mq/HotPartitionSketchRegistry.java` | `service/hot/src/main/java/com/rag/hot/messaging/HotPartitionSketchRegistry.java` | 1:1 | ✅ **已落地（2026-11-01）** —— 分区 sketch 生命周期（assign 回灌 / revoke 落盘） |
| `hot-rpc/.../mq/HotRedisSnapshotStore.java` | `service/hot/src/main/java/com/rag/hot/messaging/HotRedisSnapshotStore.java` | 1:1 | ✅ **已落地（2026-11-01）** —— Redis 影子 + `RENAME` 原子快照（4 段 Lua、`EXISTS` 守卫、`EXPIRE 300`）—— **最易漂移面** |
| `hot-rpc/.../mq/HotMergeScheduler.java` | `service/hot/src/main/java/com/rag/hot/messaging/HotMergeScheduler.java` | 1:1 | ✅ **已落地（2026-11-01）** —— 分布式锁 + 看门狗续期 + 跨分片合并求和（10s tick） |
| `hot-rpc/.../mq/ArticleHotEvent.java` | `service/hot/src/main/java/com/rag/hot/contract/ArticleHotEvent.java` | 1:1 | ✅ **已落地（2026-11-01）** —— 落**域内 `contract/` 包**（用户定）⇒ 无它 G-1 无法反序列化 |
| `hot-api/.../form/GoFormValues.java` | `common/src/main/java/com/rag/common/web/GetFormValuesFilter.java`（**跨域共享，非域内**） | 1:N | 🟡 中 —— C 已有该类，需确认它覆盖 hot 的 2 个参数 |
| `hot-api/.../client/GoStyleGrpcCallDeadline.java` | `service/hot/src/main/java/com/rag/hot/config/HotApiProperties.java` + `application.yml` | 1:1 | 🟡 中 —— 需在 `grpc.client.*` 显式配 |

> ⇒ **G-1 ~ G-5 是一条完整链路，缺任一环 hot 域的 rpc 侧就没有「热度计算」**。
> 本表**不代 RAG-V1 发明文件名**，只标出「该放什么」；落点命名建议沿用 B 侧 `mq/` 口径。

---

## 2. 持久层差异

> ⚠️ **hot 域两侧都没有数据库表** —— 这是本域最容易被模板误伤的地方。

| 维度 | B RAG | C RAG-V1 |
|---|---|---|
| 实体 / Mapper | ❌ 无（`hot-api` / `hot-rpc` 均无 `domain/*Entity`、无 `mapper/`） | ❌ 无（`hot-service` 的 `domain/model/` 下无 `*Entity`、且**整个域没有 `mapper/` 子包**） |
| DDL / Schema | ❌ 无 | ⚠️ `service/hot/pom.xml:37-43` 引了 `mybatis-plus-spring-boot3-starter` + `postgresql`，`application.yml` 有 `spring.datasource` + `mybatis-plus.configuration.map-underscore-to-camel-case` ⇒ **配了但无表**（模板残留） |
| 持久化介质 | Redis **ZSET**（`hot:articles*`）+ Kafka（`article-hot-events`） | 同（`spring-boot-starter-data-redis` + `org.springframework.kafka:spring-kafka`，均已在 pom —— Boot 3.5 无 `spring-boot-kafka`） |
| 事务 | ❌ 无 | ❌ 无 |
| 雪花 ID | ❌ 无 | ❌ 无（`common/src/main/java/com/rag/common/snowflake` 存在但 hot 不用） |
| `@MapperScan` 断言 | — | ✅ `架构符合性断言.md:163` 已明确「`hot-rpc` 没有 `@MapperScan` —— 因为它确实没有 mapper 包」 |
| Redis 客户端 | `StringRedisTemplate`（`opsForZSet` + `execute(RedisScript)`） | 同 B（Spring Data Redis 原生） |
| Redis 脚本 | 4 段 Lua（快照 `EXISTS` 守卫 / 快照 `RENAME` / 锁续期 CAS / 锁释放 CAS）+ 合并 `RENAME` CAS（`hot-rpc/…/mq/HotRedisSnapshotStore.java:82-87` + `HotMergeScheduler.java:77,96-100`） | 🔴 **缺位**（G-3 / G-4） |

**决策锁 · 无表域的持久层依赖（3 处同时改）**：
1. `service/hot/pom.xml` 的 `mybatis-plus-spring-boot3-starter` + `postgresql` —— hot 域无表 ⇒ 要么删，要么明确「保留给未来扩展」。
2. `service/hot/src/main/resources/application.yml` 的 `spring.datasource` + `mybatis-plus` 段 —— 同上。
3. `common/src/main/java/com/rag/common/autoconfigure/MybatisPlusOrderingBridgeAutoConfiguration.java` 的扫描策略 —— 需确认不会给 hot 域装配出「空 mapper 扫描」副作用。

**为什么换**：RAG-V1 的树是**按域模板批量生成**的，持久层依赖跟着模板走。hot 域是**纯 Redis + Kafka** 域（B 侧本就无表）⇒ 模板带来的 DataSource / MyBatis-Plus 是**多出来的东西**，留着会让「这个域依赖哪个库」这个问题变得没有答案。

---

## 3. HTTP / DI 差异

| 维度 | B RAG | C RAG-V1 |
|---|---|---|
| 路由注册 | `@RequestMapping("/hot/v1")` + `@GetMapping("/articles")`（`hot-api/…/api/controller/HotArticleController.java:54,77`） | 同 B（`HotArticleController`） |
| 端点数 / method | 1 个端点，GET | 1 个 |
| 鉴权 | ❌ 无 interceptor、无 `WebMvcConfig` ⇒ 全部端点免鉴权 | ⚠️ **有 `service/hot/src/main/java/com/rag/hot/config/WebMvcConfig.java`**（javadoc 写「注册 JwtAuthInterceptor」）⇒ 🔴 与免鉴权事实矛盾 |
| Controller → Service | Controller → Service → gRPC client | Controller → `HotApiService` →（直连 `HotRankingQueryService` / `ArticleRpcClient`） |
| 逻辑层分层 | 3 类（编排 `HotArticleQueryService` / 补全 `HotArticleHydrator` / 分页 `HotRankPaginator`） | 2 类（`HotApiService` / `HotApiSupport`） |
| 响应信封 | `GlobalResponseAdvice`（B 侧 `hot-api/…/api/advice/GlobalResponseAdvice.java`） | `HotApiResponseAdvice` |
| 错误形状 | `GlobalExceptionHandler`（**恒 200** + `{code,msg,null}`） | `HotApiExceptionHandler` |
| 双返回 → 异常流 | throw `BusinessException` + advice | 同 B（`BusinessException` 在 `common/src/main/java/com/rag/common/exception/`） |
| DTO 形态 | 可变 POJO + `@JsonProperty` | **immutable record**（**D5**）+ Bean Validation |
| 配置绑定 | `application.yml` 原生属性 | `HotApiProperties` + `HotRpcProperties`（`@ConfigurationProperties`） |
| `ServiceContext` | ❌ 已删（Spring Bean） | ❌ 已删 |
| 进程拓扑 | 2 类进程（`hot-api` / `hot-rpc`） | **1 个进程**（**D1**），同时开 `server.port`（HTTP）与 `grpc.server.port`（gRPC） |
| 服务发现 | Nacos（`namespace`/`group` 必须与被调方一致） | Nacos（`application.yml` 有 `spring.cloud.nacos.discovery`） |
| gRPC 传输 | 明文（`negotiation-type: plaintext`） | 明文（`application.yml` 已写 `negotiation-type: plaintext` + 断言 A4） |
| 客户端 deadline | `GoStyleGrpcCallDeadline` 集中实现（B 侧 `hot-api/…/api/client/GoStyleGrpcCallDeadline.java`） | ⚠️ 无独立类 ⇒ 需在 `grpc.client.*` 显式配 |
| 服务端 deadline | `RpcServerInterceptorFactory` | 依赖 `common/src/main/java/com/rag/common/observability/ObservabilityProperties.java` |
| 容器三旋钮 | ✅ 已配（query-chars / param-count / header-size），守门 `tools/difftest/check-tomcat-limits.py` | ⚠️ **RAG-V1 `service/hot/src/main/resources/application.yml` 未见三旋钮** ⇒ 🔴 需补（B 侧 = `maxFormParamCount=2048` + `1MiB+4KiB` header 上限） |
| 并发模型 | `ThreadPoolTaskExecutor`（有界池 64 + 队列 64）+ 每请求 `Semaphore(8)` 窗口 | `HotHydrationExecutorConfiguration`（同 B） |
| 定时任务 | `@Scheduled`（合并 tick 10s：`hot-rpc/…/mq/HotMergeScheduler.java:68,148`） | 🔴 **缺位**（G-3 / G-4） |

**决策锁 · 免鉴权域的 `WebMvcConfig`（1 处，待拍板）**：
1. `config/WebMvcConfig.java` —— 现状写「注册 JwtAuthInterceptor + public 路径白名单」。
   **B 侧 `hot-api` 全模块零 interceptor、零 `WebMvcConfig`**（唯一的端点事实是 `hot-api/…/api/controller/HotArticleController.java:54,77`）⇒ 若该类注册了 interceptor，公开热门列表就会突然要 token（**行为变更**）；若它只是空壳占位，则无任何职责 ⇒ 属多余文件。

**为什么换**：RAG-V1 的 `WebMvcConfig` 是**全 10 域模板**，其职责描述来自 message 域（有鉴权）。hot / task 两域**根本没有鉴权** ⇒ 该类必须按域裁剪，不能照抄模板的描述。

---

## 4. 公共模块对照 —— hot 域与 `common/` 的边界

### 4.1 `heavykeeper` 归本域还是 `common/`？—— **判定：归 hot 域自己**

| 判据 | 实测证据 | 结论 |
|---|---|---|
| import 方 | B 侧全仓 grep：`HeavyKeeper` 只被 hot-rpc 自己的 `config`（`HotRpcProperties.java:41`）与 `mq`（`HotRedisSnapshotStore.java:130`）引用 | ✅ **只有 hot 域自己用** |
| 领域属性 | 依赖「文章热度」这一业务概念（`article_id` + 互动权重） | ✅ 业务特有，非通用算法 |
| 生态等价物 | 无（`PriorityQueue` + FNV + 概率衰减均需自写） | ✅ 无生态替代 |
| ⇒ 落点 | `service/hot/src/main/java/com/rag/hot/domain/model/HeavyKeeper.java` | ✅ **归 hot 域**；**不得**放进 `common/` |

> 对照：`docs/第二轮深挖-契约盘点.md` §六 T-6 已给出同一判定（「hot 域私有的 Top-K 重算法包，不是跨域共享」），
> `docs/File_Tree/hot/hot-service.md` §四 亦已写明。本表仅补上 **import 证据的行号**。

### 4.2 hot 域从 B 侧 `service/common` 只取 3 个包

> 全域 grep 实测：hot 域对 B 侧 `service/common` 的 import 只有 4 行（`logger` ×2、`observability` ×2、`response` ×1）。

| B RAG（`service/common/`） | C RAG-V1 | 备注 |
|---|---|---|
| `service/common/.../response/Response.java`（`{code,msg,data}` 信封） | `common/src/main/java/com/rag/common/contract/ApiResponse.java` | 信封是 L1（客户端按 `{code,msg,data}` 解析）⇒ **不能用 `ProblemDetail` 替换** |
| `service/common/.../logger/Logger.java`（`LogBusinessErr` / `WithArticleID` / `call_chain`） | `common/src/main/java/com/rag/common/logger/GoStyleLogger.java` + `LogOption.java` + `LogOptions.java` + `FatalError.java` | **D4**（实现 SLF4J 的 `Logger`）；日志字段属业务契约 |
| `service/common/.../observability/HttpMiddleware.java`（HTTP 中间件 + timeout） | `common/src/main/java/com/rag/common/observability/*`（3 类）+ `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` | **D3 + D4** |
| `service/common/.../observability/*` + `RpcServerInterceptorFactory`（服务端拦截器 / deadline） | `common/src/main/java/com/rag/common/observability/GoStyleRequestDeadline.java` + `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java` | ⚠️ `ObservabilityProperties` 是否含 rpc / http timeout 字段待填 |
| `service/common/.../observability/MqConsumer.java`（`TraceConsumer`） | `common/src/main/java/com/rag/common/observability/*` | 🔴 hot 域**确实需要**它（B 侧 `hot-rpc/…/mq/HotEventConsumer.java` 的消费 span 包装）—— 但该 span 落在缺位的 G-1 里 |
| `service/common/.../snowflake/Snowflake.java` | `common/src/main/java/com/rag/common/snowflake/Snowflake.java` | ⚠️ **hot 域不用**（无引用） |
| `service/common/.../communityfact/*` | `common/src/main/java/com/rag/common/communityfact/`（12 类） | ⚠️ **hot 域不用**（无引用） |
| `service/common/.../security/JwtUtil.java` | `common/src/main/java/com/rag/common/security/JwtUtil.java` | ⚠️ **hot 域零鉴权 ⇒ 不用**；`common/src/main/java/com/rag/common/security/` 对本域是死依赖 |

### 4.3 本域私有 vs 跨域共享 —— 一句话判定

| 件 | 判定 | 依据 |
|---|---|---|
| `HeavyKeeper` | **本域私有** | import 方只有 hot-rpc 的 config + mq（B 侧 `HotRpcProperties.java:41` + `HotRedisSnapshotStore.java:130`） |
| `hot-common/.../errmsg/`（错误契约） | **本域私有** | 只被 hot 域自己的 api / rpc 引用 ⇒ C 放域内 `contract/` 包，**不放** `common/` |
| `ArticleHotEvent` | **本域私有**（但由 like 域等**生产**） | 生产侧在别的域（like 域的类型定义），消费侧只有 hot ⇒ 载荷定义归 hot 域；**生产侧的类型定义在 like 域**（跨域重复，是既有事实） |
| `ApiResponse` / `BusinessException` / `JwtUtil` / `logger` / `observability` | **跨域共享** | B 侧 `service/common` ⇒ RAG-V1 根 `common/`（**D3**） |

**决策锁 · `hot/common` → 域内 `contract/` 包（4 处同时改）**：
1. `hot-common/.../errmsg/HotErrorCode.java` → `service/hot/src/main/java/com/rag/hot/contract/ErrCodes.java`（并改名，类名与 B 侧枚举常量块对齐）。
2. `hot-common/.../errmsg/HotErrorMessages.java` → `service/hot/src/main/java/com/rag/hot/contract/ErrMsg.java`（并改名）。
3. `hot-api/.../exception/HotCodeError.java` → `service/hot/src/main/java/com/rag/hot/contract/CodeError.java`（异常载体下沉到契约包）。
4. `hot-service` 的 `pom.xml` 补上错误契约类所需的依赖；`hot-service` 对根级 `proto` 模块的依赖方向保持单向。

> 📌 **时点标注（2026-10-26）** —— 本小节原文写的是「`hot-contract` 契约模块」（proto + 错误契约同模块）。
> **该形态已被 D7 取代**：`.proto` 全部集中到根级 `proto/` 模块（pb 包名 `com.rag.proto.hot`），
> 错误契约与事件 DTO 落到**域模块内**的 `contract/` 包 ⇒ **`*-contract` 子模块已删除**。
> ⇒ 「契约与实现分离」的诉求仍成立，只是分离粒度从「每域一个契约模块」变成「**全仓一个根级 proto 模块 + 域内 `contract/` 包**」。

**为什么换**：错误码是**跨服务可见契约**（HTTP 信封的 `code` 字段直接写它），与 proto 同属「消费方需要、但不该依赖整个 app」的那一类。
⚠️ **代价**：错误契约一旦进 `contract` 包，就**更容易被误认为可以跨域共用** ⇒ 必须在类注释首行写死「逐域隔离，禁止跨域统一」。

**决策锁 · `hot/common` → 域内 `contract/` 包之外的归属确认（2 处）**：
1. `heavykeeper` 不进 `common/`（依据见 §4.1）。
2. `ArticleHotEvent` 归 hot 域（消费侧），但 **like 域的生产侧类型不动**（跨域重复是 B 侧既有事实，不在本表改）。

**为什么换**：`common/` 只放**跨域共享**件；把域私有件塞进 `common/` 会让共享层的边界失守（各域都能改它 ⇒ 逐域隔离的错误契约会被误统一）。

---

## 5. 决策锁（汇总 · 按改动批次）

> 复刻到下一个域时按这个清单改，不漏。

**批次 1 · api/rpc 合并为单 app（D1）—— 6 处同时改**：
1. `hot-api/.../HotApiApplication.java` + `hot-rpc/.../HotRpcApplication.java` → 1 个 `service/hot/src/main/java/com/rag/hot/HotServiceApplication.java`。
2. `hot-api/src/main/resources/application.yml` + `hot-rpc/src/main/resources/application.yml` → 1 个 `service/hot/src/main/resources/application.yml`（同时含 `server.port` 与 `grpc.server.port`）。
3. `hot-api/.../client/HotRpcClient.java` **删除**（域内直连，D2）。
4. `hot-api/.../client/ArticleRpcClient.java` 移到 `client`（D2 / D6：跨域仍 gRPC，但属业务层）。
5. `hot-api/.../service/*` 与 `hot-rpc/.../service/*` 并进同一模块的两个子包 `advice + config + controller + dto + metrics + service` `wiring + client + config + domain/model + grpc + metrics + service`。
6. `hot-common` 模块**消失** ⇒ 其 2 个类并入 `hot-contract`（批次 2）。

**为什么换**：B 侧把 api / rpc 拆成**两个独立进程**（`hot-api` / `hot-rpc` 各一个 `@SpringBootApplication`），拆分点是**部署边界**而非职责边界；
Spring Cloud 的部署单元是「可部署服务」，`@RestController` 与 `@GrpcService` 共处一个 app 是默认形态。

**批次 2 · 错误契约下沉到契约模块 —— 4 处同时改**：见 §4.3 决策锁。

**为什么换**：错误码是对外可见契约（信封 `code` 字段），与 proto 同属「消费方需要但不该依赖整个 app」的一类。

**批次 3 · Kafka 消费链路（`mq/` 包）—— 🔴 C 侧 0 处已建**：
1. 新建 `{wiring,client,config,domain/model,grpc,metrics,service}/mq/` 包，承载 G-1 ~ G-5（见 §1.5）。
2. `HotKafkaConfig` 的分区分配回调 / 错误处理策略要与 `mq/` 的消费者**成对**落地。
3. `HotRpcMetrics` 的 2 个指标（`article_hot_kafka_consume_total` / `article_hot_redis_sync_total`）的**触发点**在 `mq/` 里。
4. `HotRpcProperties.Interaction`（`syncEvery` / `ttl` / `partitionCount` / `weights`）的**消费点**在 `mq/` 里。

**为什么换**：B 侧 `hot-rpc/…/mq/` 这一组是「一组装 5 件事」（消费 / 分区生命周期 / 快照 / 合并 / 锁），
Spring 侧必须拆开才有可测的边界；但**当前 C 树里这一层根本不存在** ⇒ 这是**待建**，不是「已对齐」。

**批次 4 · query 解析与 deadline 下沉 —— 3 处同时改**：
1. `hot-api/.../form/GoFormValues.java` → `common/src/main/java/com/rag/common/web/GetFormValuesFilter.java`（域内不再保留）。
2. `hot-api/.../client/GoStyleGrpcCallDeadline.java` → `config/HotApiProperties.java` + `application.yml` 的 `grpc.client.*`。
3. `hot-rpc/.../config/Jackson2ObjectMapperConfiguration.java` → `common/src/main/java/com/rag/common/autoconfigure/JsonBodyConverterAutoConfiguration.java`（域内不再保留）。

**为什么换**：这三件都不是 hot 域的领域职责 —— form 解析是**全域共用的输入解释规则**，deadline 是**客户端基建**，Jackson 是**Boot 4 拆模块补丁** ⇒ 留在域内会造成 10 份重复。

**批次 5 · 免鉴权域的 `WebMvcConfig` —— 1 处（待拍板）**：见 §3 决策锁。

**为什么换**：模板职责描述来自有鉴权的域；hot 域照抄会**新增鉴权**（行为变更），照留空壳则文件无职责。

**批次 6 · 无表域的持久层依赖 —— 3 处同时改**：见 §2 决策锁。

**为什么换**：hot 域是纯 Redis + Kafka 域，DataSource / MyBatis-Plus 是模板带来的多出来的东西。

---

## 6. 跨模块全局约定

> 下列每一项的**值都逐条核对 B 侧源码**（RAG 仓 `service/hot/`，行号可点验）。**它们是 L1 契约，跨域共享，不会因为改 hot 域而漂移。**

| 项 | 值 | B 侧出处（B 仓相对路径:行号） |
|---|---|---|
| proto package | `hot` | `service/hot/hot-proto/src/main/proto/hot.proto:3` |
| proto service | `HotService{ GetHotArticles, LikeArticle, CommentArticle, CoinArticle, ShareArticle }` | `service/hot/hot-proto/src/main/proto/hot.proto:70-77` |
| proto 字段号 | `GetHotArticlesRequest.top_k=1`；`HotArticleItem.article_id=1` / `hot_score=2`；`GetHotArticlesResponse.items=1`；`Like/Comment/Coin/ShareArticleRequest.article_id=1` / `user_id=2`；`CoinArticleRequest.coin_count=3`；`ShareArticleRequest.channel=3`；5 个 `*Response.success=1` | `service/hot/hot-proto/src/main/proto/hot.proto:11-66` |
| 错误码 | `200` / `500` / `1015` / `5001` / `5002` / `5003` / `5004` / `5005` / `5006` / `5007` / `5008` | `service/hot/hot-common/src/main/java/com/rag/hot/hot/common/errmsg/HotErrorCode.java:41-73` |
| 错误文案（**英文**） | `OK` / `FAIL` / `service busy` / `internal server error` / `record not found` / `invalid parameter` / `unauthorized` / `forbidden` / `already exists` / `database query failed` / `database update failed`；**未知码回退 `FAIL`** | `service/hot/hot-common/src/main/java/com/rag/hot/hot/common/errmsg/HotErrorMessages.java:59-70` + `:80`（`getMsg` 回退） |
| JWT claims | **本域无** —— B 侧 `hot-api` 全模块零 interceptor、零 `WebMvcConfig` ⇒ 无 JWT claim、无 blacklist key、无 `JwtAuthInterceptor` | `service/hot/hot-api/` 全模块实测（`find`）· `hot-api/…/api/controller/HotArticleController.java:54,77` |
| 表名 | **本域无表** —— `hot-api` / `hot-rpc` 均无 `domain/*Entity`、无 `mapper/`、无 DDL | `service/hot/` 目录实测（`find`） |
| HTTP path | `GET /hot/v1/articles`（`@RequestMapping("/hot/v1")` + `@GetMapping("/articles")`）；**端点数 = 1** | `service/hot/hot-api/src/main/java/com/rag/hot/hot/api/controller/HotArticleController.java:54,77` |
| HTTP 响应信封 | `{code,msg,data}`；成功 `{200,"OK",data}`；失败**恒 HTTP 200** + `{code,msg,null}` | `service/hot/hot-api/src/main/java/com/rag/hot/hot/api/advice/GlobalResponseAdvice.java` · `.../advice/GlobalExceptionHandler.java:81-137` |
| Kafka topic | `article-hot-events`（生产与消费同 topic） | `service/hot/hot-rpc/src/main/resources/application.yml:234` |
| Kafka consumer group | `article-hot-group` | `service/hot/hot-rpc/src/main/resources/application.yml:102` |
| Kafka consumer name | **B 侧无独立 consumer name** —— 消费者标识由 group-id 决定；应用名 `hot-rpc` | `service/hot/hot-rpc/src/main/resources/application.yml:17` + `:102` |
| Kafka 消费位点 | `auto-offset-reset: latest`（新 group **不回放历史**） | `service/hot/hot-rpc/src/main/resources/application.yml:105` |
| Kafka 分区分配 | `RoundRobinAssignor`（改策略会改 `hkMap` 布局） | `service/hot/hot-rpc/src/main/resources/application.yml:116` |
| Kafka 载荷 | `{"article_id": "...", "type": "like" / "comment" / "coin" / "share"}`（**2 字段**；⚠️ 生产侧实测多发 `user_id` / `timestamp` ⇒ 消费端必须忽略未知字段 —— B 侧已用 `@JsonIgnoreProperties(ignoreUnknown=true)` 兜住） | `service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/mq/ArticleHotEvent.java:22-25` |
| 指标名（api） | `hot_api_request_total{route,result}` · `hot_api_reject_total{route,reason}` · `hot_api_request_duration_seconds{route}` · `hot_api_rpc_error_total{callee}` · `hot_api_article_skip_total{reason}` | `service/hot/hot-api/src/main/java/com/rag/hot/hot/api/metrics/HotApiMetrics.java:149,155,173,182,197` |
| 指标名（rpc） | `article_hot_kafka_consume_total{type}` · `article_hot_redis_sync_total{status}` | `service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/metrics/HotRpcMetrics.java:37,40` |
| 指标 label 常量 | skip reason：`empty_article_id` / `article_rpc_error` / `article_missing` / `article_not_published` / `hot_rpc_nil_response`；callee：`hot_rpc_get_hot_articles` / `article_rpc_get_article` | `service/hot/hot-api/src/main/java/com/rag/hot/hot/api/metrics/HotApiMetrics.java:61,64,67,70,73,76,83` |
| Redis keyspace | `hot:articles`（合并结果）· `hot:articles:p{n}`（分片）· `hot:articles:p{n}:shadow`（分片影子）· `hot:articles:shadow`（合并影子）· `hot:merge:lock`（分布式锁） | `service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/mq/HotRedisSnapshotStore.java:53,56,59,62` · `.../mq/HotMergeScheduler.java:59` |
| Redis 过期时间 | 分片快照 `EXPIRE 300`（**硬编码**）；合并结果 `EXPIRE = Interaction.TTL`（**两个不同的值**） | `service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/mq/HotRedisSnapshotStore.java:87` · `.../mq/HotMergeScheduler.java:34` |
| 互动权重 | `like=1` `comment=2` `coin=3` `share=4`；**按 `Name` 分发**（`Code` 字段零读取 —— B 侧刻意保留的死字段） | `service/hot/hot-rpc/src/main/resources/application.yml:257-269` · `.../config/HotRpcProperties.java:173,184` |
| HeavyKeeper 参数 | `Width=1000` `Depth=5` `Decay=0.9` `TopK=100` | `service/hot/hot-rpc/src/main/resources/application.yml:238-241` |
| Interaction 参数 | `SyncEvery=100` `TTL=3600` `PartitionCount=3` | `service/hot/hot-rpc/src/main/resources/application.yml:247,251,255` · `.../config/HotRpcProperties.java:128-134` |
| api 分页阈值 | `page=1` `pageSize=20` `maxPageSize=50` `maxHotArticles=100`；`scope="rolling"` | `service/hot/hot-api/src/main/java/com/rag/hot/hot/api/service/HotRankPaginator.java:48,51,54,57` · `.../dto/HotArticlesResp.java:43-46` |
| rpc TopK | `defaultTopK=20` `maxTopK=100` | `service/hot/hot-rpc/src/main/java/com/rag/hot/hot/rpc/service/HotRankingQueryService.java:43,46` |
| 并发窗口 | 补全并发上限 **8**（每请求一个 `Semaphore(8)`） | `service/hot/hot-api/src/main/java/com/rag/hot/hot/api/service/HotArticleHydrator.java:29,67` |
| 端口（**非 L1，仅记录**） | B api `18897`（`${HOT_API_PORT:18897}`）· B rpc gRPC `8083`（`${HOT_RPC_GRPC_PORT:8083}`）· B rpc management `19091` · C `server.port=8895` + `grpc.server.port=8083` | `service/hot/hot-api/src/main/resources/application.yml:37` · `service/hot/hot-rpc/src/main/resources/application.yml:223,188` |

---

## 7. 覆盖度自查

> 核对日期 **2026-11-02**；命令为 `find` 实测，非估算。

### 7.1 C 列零遗漏 —— ✅ 31/31（100%）

```bash
find "/Users/pr/Documents/GitHub/RAG-V1/service/hot" -name '*.java' \
  -not -path '*/src/test/*' -not -path '*/target/*' | xargs -n1 basename | sort -u
```

| # | C 列文件（basename） | 出现在 |
|---|---|---|
| 1 | `CodeError.java` | §1.1 |
| 2 | `ErrCodes.java` | §1.1 |
| 3 | `ErrMsg.java` | §1.1 |
| 4 | `GrpcErrorMapper.java` | §1.1 |
| 5 | `HotServiceApplication.java` | §1.2 |
| 6 | `HotApiExceptionHandler.java` | §1.2 |
| 7 | `HotApiResponseAdvice.java` | §1.2 |
| 8 | `HotApiProperties.java` | §1.2 |
| 9 | `HotHydrationExecutorConfiguration.java` | §1.2 |
| 10 | `WebMvcConfig.java` | §1.2（🔴 待确认 / 疑似多余） |
| 11 | `HotArticleController.java` | §1.2 |
| 12 | `HotArticleItem.java` | §1.2 |
| 13 | `HotArticlesReq.java` | §1.2 |
| 14 | `HotArticlesResp.java` | §1.2 |
| 15 | `HotApiMetrics.java` | §1.2 |
| 16 | `HotApiService.java` | §1.2 |
| 17 | `HotApiSupport.java` | §1.2 |
| 18 | `NacosRegistration.java` | §1.3 |
| 19 | `ArticleRpcClient.java` | §1.3 |
| 20 | `HotKafkaConfig.java` | §1.3 |
| 21 | `HotRpcProperties.java` | §1.3 |
| 22 | `HeavyKeeper.java` | §1.3 |
| 23 | `HotRpcMetrics.java` | §1.3 |
| 24 | `HotRankingQueryService.java` | §1.3 |
| 25 | `InteractionStubService.java` | §1.3 |
| 26 | `HotGrpcService.java` | §1.3 |
| 27 | `ArticleHotEvent.java` | §1.3 / §1.5（G-5） |
| 28 | `HotEventConsumer.java` | §1.3 / §1.5（G-1） |
| 29 | `HotMergeScheduler.java` | §1.3 / §1.5（G-4） |
| 30 | `HotPartitionSketchRegistry.java` | §1.3 / §1.5（G-2） |
| 31 | `HotRedisSnapshotStore.java` | §1.3 / §1.5（G-3） |

⇒ **无遗漏**（域内 `contract/` 5 + `messaging/` 4 + 其余 22 = **31**）。
> ⚠️ 排除项：`src/test/java/...`（测试）、`target/`（构建产物）。
> ⚠️ **旧稿写「26 / 26」是 2026-11-01 `messaging/` 落地前的数** —— 补上 `contract/ArticleHotEvent` + `messaging/` 4 类后实测 **31** ⇒ 旧稿**漏记 5 个**。
>
> 📌 **原先逐行列「另一份实现」文件的覆盖度清单（26 行）已随该列移除** —— 两方口径（B / C）下它不再作为覆盖度判据。
> 其中**与「C 列缺位」有关的条目**在两方口径下的落点：① 独立 metrics HTTP 端口 ⇒ **B 侧同样没有**（`HotRpcMetrics` 只是 `Counter` 的持有者）⇒ C 由 Actuator 承担（§1.3）；② 消费链路 5 件套（Kafka 消费 / 分区生命周期 / 快照 / 合并 / 锁）⇒ **B 侧 `hot-rpc/…/mq/` 5 个类一一对应**，C 侧 `messaging/` 4 类 + `contract/ArticleHotEvent` **已落地**（§1.3 / §1.5，G-1~G-5 关闭）；③ 上下文装配类 ⇒ **B 侧由 Spring Bean 承担**，两侧都无独立类（§1.3）；④ 压测工具 ⇒ **B 侧与 C 侧均无**（§0 / §1.3 的「无对应物」行）。

### 7.2 B 列可核 —— **34 / 34 = 100%**

| # | B 列文件（basename） | 出现在 |
|---|---|---|
| 1 | `HotApiApplication.java` | §1.2 |
| 2 | `GlobalExceptionHandler.java` | §1.2 |
| 3 | `GlobalResponseAdvice.java` | §1.2 |
| 4 | `ArticleRpcClient.java` | §1.2 |
| 5 | `GoStyleGrpcCallDeadline.java` | §1.2 / §1.4 |
| 6 | `HotRpcClient.java` | §1.2 / §1.4 |
| 7 | `HotHydrationExecutorConfiguration.java` | §1.2 |
| 8 | `HotArticleController.java` | §1.2 |
| 9 | `HotArticleCard.java` | §1.2 |
| 10 | `HotArticlesReq.java` | §1.2 |
| 11 | `HotArticlesResp.java` | §1.2 |
| 12 | `HotCodeError.java` | §1.1 |
| 13 | `GoFormValues.java` | §1.2 / §1.4 |
| 14 | `HotApiMetrics.java` | §1.2 |
| 15 | `HotArticleHydrator.java` | §1.2 |
| 16 | `HotArticleQueryService.java` | §1.2 |
| 17 | `HotRankPaginator.java` | §1.2 |
| 18 | `HotErrorCode.java` | §1.1 |
| 19 | `HotErrorMessages.java` | §1.1 |
| 20 | `HotRpcApplication.java` | §1.2 |
| 21 | `NacosRegistration.java` | §1.3 |
| 22 | `HotKafkaConfig.java` | §1.3 |
| 23 | `HotRpcProperties.java` | §1.3 |
| 24 | `Jackson2ObjectMapperConfiguration.java` | §1.3 / §1.4 |
| 25 | `HeavyKeeper.java` | §1.3 |
| 26 | `HotRpcMetrics.java` | §1.3 |
| 27 | `ArticleHotEvent.java` | §1.3 / §1.5（G-5） |
| 28 | `HotEventConsumer.java` | §1.3 / §1.5（G-1） |
| 29 | `HotMergeScheduler.java` | §1.3 / §1.5（G-4） |
| 30 | `HotPartitionSketchRegistry.java` | §1.3 / §1.5（G-2） |
| 31 | `HotRedisSnapshotStore.java` | §1.3 / §1.5（G-3） |
| 32 | `HotRankingQueryService.java` | §1.3 |
| 33 | `InteractionStubService.java` | §1.3 |
| 34 | `HotServiceGrpcImpl.java` | §1.3 |

### 7.3 映射类型分布（逐行核对重算）

| 类型 | §0 | §1.1 | §1.2 | §1.3 | §1.4 | §1.5 | 合计 | 占比 |
|---|---|---|---|---|---|---|---|---|
| **1:1** | 6 | 3 | 13 | 13 | 3 | 6 | **44** | 78.6% |
| **1:N** | 0 | 2 | 2 | 1 | 2 | 1 | **8** | 14.3% |
| **N:1** | 1 | 0 | 1 | 1 | 0 | 0 | **3** | 5.4% |
| **C 新增** | 0 | 0 | 1 | 0 | 0 | 0 | **1** | 1.8% |
| **合计** | **7** | **5** | **17** | **15** | **5** | **7** | **56** | 100% |

- `1:1` = 一个 B 文件对应一个 C 文件（绝大多数行）。
- `1:N` = 一个 B 文件拆成 N 个 C 文件（如 `hot-common/…/errmsg/HotErrorCode.java` → 4 个契约类、`hot-api/…/service/` 3 类 → 2 个 C 类、`hot-rpc/…/mq/` 4 类 → `messaging/` 4 类 + `contract/ArticleHotEvent`）。
- `N:1` = 多个 B 文件合到 1 个 C 文件（2 个入口 → 1 个 `HotServiceApplication`（§0）、4 个互动用例 → 1 个 `InteractionStubService`（§1.3）、B 侧 `service/common` → RAG-V1 根 `common/`（§0））。
- `C 新增` = B 侧没有对应物、C 侧新增（`api/config/WebMvcConfig.java` —— 🔴 疑似多余）。

> ⚠️ 类型只描述 **B ↔ C 的文件数关系**（定义见 §〇）。
> 同一行里 B 列可能列出多个文件（如 4 个互动用例记为 1 行 `N:1`）。
> §2 / §3 / §4 / §6 的表是**差异对照表**（按维度组织），不是文件名映射行，故不设「映射类型」列；
> §2 / §3 / §4 各节末尾另附「决策锁」。
> ⚠️ **核对日期 2026-11-02**（删去第三列后逐行核对重算）。**重算依据**：逐行扫 6 张主表（§0 7 + §1.1 5 + §1.2 17 + §1.3 15 + §1.4 5 + §1.5 7 = **56 行**），按类型列取值计数；
> 与删列前的同一批行**逐行比对，映射类型判定未变**（删列只影响列数，不影响 B→C 的行数关系）⇒ **行数 56 → 56**（不变），
> **但类型分布由旧稿的「1:1=40 / 1:N=12」修正为「1:1=44 / 1:N=8」** —— 旧稿的 §1.5 分节计数（1:1=2 / 1:N=5）是 **2026-11-01 `messaging/` 落地前**的数，该节 5 行的类型已随落地改为 `1:1` ⇒ 本次按现状逐行重算。

### 7.4 本表**不回答**的问题（交回台账）

| 问题 | 去哪看 |
|---|---|
| hot 域与 B 侧（RAG）的行为是否一致 | `docs/DRIFT/hot/`（本仓）或 RAG 侧的 `docs/DRIFT/hot/{api,rpc,common}.md`（证据强度列） |
| hot 域的 `DRIFT-HOTAPI-030`（Tomcat 参数个数语义） | RAG 的 `docs/DRIFT/_cross/` + `docs/DRIFT/global/` |
| C 侧那些「生态无等价物」的缺口（G-1 ~ G-7）该谁建 | `docs/决策记录.md` 的待拍板档（P1–P6） |
| L3 降级项（query 解析文案、熔断策略、span 属性） | `docs/L3-降级清单.md` |

---

## 附：一句话总览

**B 列 RAG** = `hot-api`（1 端点，免鉴权）+ `hot-rpc`（5 个 gRPC 方法 + Kafka 消费 + HeavyKeeper + Redis ZSET）+ `hot-common`（错误契约）+ `hot-proto`，**零数据库表**；
**C 列 RAG-V1** = 合并成 `hot-service` 一个模块、错误契约进域内 `contract/` 包（proto 在根级 `proto/`）、`advice + config + controller + dto + metrics + service`+`wiring + client + config + domain/model + grpc + metrics + service` 两个子包 ——
**但 rpc 侧的 Kafka 消费链路（`messaging/`）与免鉴权域的 `WebMvcConfig` 两处仍需拍板。**
⇒ **文件名与职责逐条对应；行为以 B 侧源码为准。**
