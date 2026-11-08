# Intentional_Deviations / favorite —— 已签字偏离

> **记什么**：明知与 **B 侧（RAG 仓）** 不同、**经用户签字批准**的差异。
> ⚠️ **是决策不是缺陷**：不重复质疑，**也不得「顺手修正回旧行为」**。
> **核对日期 2026-11-04**（逐行核对 B 侧源码 + C 侧实现，非估算）· **依据基准 2026-11-02**：**B 侧为唯一真理**（D16）· **本域原始来源侧逐条来源下沉到 B 侧自有台账**。
>
> **本文件的 12 条里，9 条是「继承并承接 B 侧已签字的决策」** —— B 侧（RAG 仓）自己已对这 9 条签过字（`/Users/pr/Documents/java&golang/RAG/docs/Intentional_Deviations/favorite/{api,common,rpc}.md`），
> C 侧按 D16「**B 侧已有的差异一律继承，不回溯第三方**」逐条承接 ⇒ **不是 C 侧自创的偏离**。
> ⚠️ **另 3 条（`DEV-FAV-RPC-008` / `009` / `DEV-FAV-API-003`）是 C 侧专属** —— 它们记录的是「**C 侧如何承接** B 侧/Go 的既有形态」时**必然产生**的差异（事务的实际生效性 · 触发形态与信封 · **D2 域内直连后 api→领域层这一跳自持出向上界**），**不是新引入的行为漂移**。
>
> ⚠️ **撞号检查**：`DEV-FAV-*` 编号**全域唯一**（B 侧 `favorite/api.md` + `common.md` + `rpc.md` 共 9 条 + `proto.md` 无独立条目）⇒ **无撞号** ⇒ **不需要** `SD-<编号>@<域>` 别名。

---

## 〇 · 本域偏离总览（12 条 · **9 条继承 B 侧已签字 ＋ 3 条 C 侧专属**）

| ID | 一句话 | C 侧落点 | 状态（核对日期 2026-11-04） |
|---|---|---|---|
| `DEV-FAV-API-001` | 401 响应的 body 形态（**三字段 envelope**，非 Go 的空 body / 两字段） | `advice/FavoriteApiExceptionHandler#handleAuthentication` | ✅ **仍成立** |
| `DEV-FAV-API-002` | 500 兜底的 body 形态（**三字段 envelope**，非 Go 的空 body） | `advice/FavoriteApiExceptionHandler#handleAny` | ✅ **仍成立** |
| `DEV-FAV-API-003` | api→领域层这一跳**自持出向上界**（复刻 B 侧 api→rpc 那一跳的 cap）＋ 领域层**保留原始 gRPC cause** | `service/FavoriteApiSupport#invokeRpc` / `#resolveBizCode` / `FavoriteUserGuard#onDownstreamFailure` | 🟡 **C 侧专属**（D2 域内直连的结构性代价 · 行为已对齐） |
| `DEV-FAV-COMMON-001` | `GRPCError(codes.OK, …)` 的返回形态 | `contract/GrpcErrorMapper#grpcError` | ✅ **仍成立** |
| `DEV-FAV-COMMON-002` | `status.FromError` 的「**顶层 vs cause 链**」判定（**只判顶层**） | `contract/GrpcErrorMapper#extractStatus` | ✅ **仍成立** |
| `DEV-FAV-RPC-001` | `favorite_request_seconds_counter` 的 Prometheus 渲染名 | `metrics/FavoriteRpcMetrics` | ✅ **仍成立** |
| `DEV-FAV-RPC-003` | preflight 的退出码 → 结构化日志（U2） | `wiring/FavoriteLegacyPreflightEndpoint` | ✅ **仍成立**（2026-11-05 P6-c 拍板①后落位） |
| `DEV-FAV-RPC-008` | preflight 的**只读快照事务**（B 侧注解在但**自调用绕过代理** ⇒ 实际无事务） | `service/FavoriteLegacyPreflightService#preflight` | 🟡 **C 侧专属**（照抄 B 侧实际行为 · 差异仅在并发写下可观察） |
| `DEV-FAV-RPC-009` | preflight 的**触发形态**（Actuator 端点 + HTTP 状态码替代退出码）＋ **Actuator 响应被业务信封包裹** | `wiring/FavoriteLegacyPreflightEndpoint` | 🟡 **C 侧专属**（D1 合并 + P6-c 拍板①的必然结果） |
| `DEV-FAV-RPC-004` | 权威读端点的**部署形态**（U1） | `wiring/FavoriteAuthorityEndpointConfig` | ✅ **仍成立** |
| `DEV-FAV-RPC-006` | 约束与索引的**名字**（12 处） | `resources/db/migration/V1..V7` | ✅ **仍成立** |
| `DEV-FAV-RPC-007` | `favorite_item` 上多一道 `target_revision scope` CHECK（**B 侧比 Go 更严**） | `resources/db/migration/V6` | ✅ **仍成立** |

---

## DEV-FAV-API-001 · ✅ 已签字 · 401 响应的 body 形态

| 项 | 内容 |
|---|---|
| **B 侧行为** | ① **验签失败**（`rest/handler/authhandler.go:106-122` 的 `unauthorized()`）⇒ `writer.WriteHeader(401)`，**body 为空**（一个字节都不写）。<br>② **黑名单命中**（`api/internal/middleware/checkblacklist_middleware.go:39-45`）⇒ 401 + 手写 `fmt.Sprintf` 的 **两字段** JSON：`{"code":5004,"msg":"未登录或登录已失效"}`（**没有 `data` 字段**）。 |
| **C 侧行为** | 两种情形**统一**成三字段 envelope：`{"code":5004,"msg":"未登录或登录已失效","data":null}` ⇒ `service/favorite/src/main/java/com/rag/favorite/advice/FavoriteApiExceptionHandler.java` 的 `@ExceptionHandler(AuthenticationException.class)` 分支。 |
| **对齐的部分** | ✅ **HTTP 状态码 401 一致**；✅ **body 里的业务码 5004 一致**；✅ **msg 文案一致**。 |
| **差异** | 仅 **body 的字段集合**：Go 空 body / 两字段；RAG（**B 侧与 C 侧同款**）三字段。 |
| **为什么接受** | ① 与 users / admin / comment / like 域的既有形态统一（全仓 401 一律走 envelope）；② 空 body 对客户端与排障都不友好，而状态码与业务码已完全对齐，不影响客户端的失败分支判定；③ **属表达层**（envelope 的字段组织），不是 L1 语义。 |
| **⚠️ 反面理由（必须一并记录）** | `follow` 域选择了**逐条对齐 Go**（保留 Go 的两种形状）⇒ **本仓两种做法都存在**，本条**不是唯一解**，需拍板后与其它域收敛口径。 |
| **什么时候会变成真差异** | 若客户端**只**在 body 非空时解析 ⇒ Go 侧验签失败会解析失败、RAG 侧不会 ⇒ 差异显现；若客户端按 `body.data` 是否存在来区分「验签失败」与「黑名单命中」⇒ **两侧都拿不到区分信息**（Go 两者的 `data` 都不存在）⇒ 行为一致。 |
| **C 侧守门** | `http/FavoriteApiMvcPipelineTest` 覆盖 401 的状态码与 body 形状（见 `docs/DRIFT/favorite/` 台账的读数）。 |
| **状态** | ✅ **已签字**（B 侧 2026-10-25，用户 Jason：**保留现状，登记为签字偏离**）⇒ C 侧**继承**，**不是缺陷**；改动需**重新征签**。 |

---

## DEV-FAV-API-002 · ✅ 已签字 · 500 兜底的 body 形态

| 项 | 内容 |
|---|---|
| **B 侧行为** | go-zero v1.10.2 的**默认中间件** `rest/handler/recoverhandler.go:17` 是 `w.WriteHeader(http.StatusInternalServerError)` ⇒ **500 + 空 body + 无 Content-Type**（挂在**默认链**上 ⇒ 只要 handler panic 就会走到）。 |
| **C 侧行为** | 500 + **JSON envelope**：`{"code":5001,"msg":"系统内部错误","data":null}` ⇒ `FavoriteApiExceptionHandler#handleAny`。 |
| **对齐的部分** | ✅ **HTTP 状态码 500 一致**；✅ **业务码 5001 一致**。 |
| **差异** | 仅 **body**：Go 空 body；RAG（**B 侧与 C 侧同款**）三字段 envelope。 |
| **⚠️ 签字附带的前置动作** | 客户端若在 500 时**靠 body 区分成因** ⇒ RAG 侧多给了信息 ⇒ 客户端行为**分叉**（但这是 RAG 侧更宽，不是更窄）；「5xx ⇒ 不解析 body」的客户端**两侧都安全** ⇒ 无需改动。 |
| **C 侧守门** | `http/FavoriteApiMvcPipelineTest`。 |
| **状态** | ✅ **已签字**（B 侧 2026-10-25）⇒ C 侧**继承**。 |

---

## DEV-FAV-API-003 · 🟡 C 侧专属 · api→领域层这一跳**自持出向上界**（复刻 B 侧 api→rpc 那一跳的 cap）＋ 领域层**保留原始 gRPC cause**

| 项 | 内容 |
|---|---|
| **B 侧行为** | B 侧的 api 面调 rpc 是**真的 gRPC 往返** ⇒ ⇒ 那一跳**自带 2s 客户端硬上界**（`service/favorite/favorite-api/src/main/resources/application.yml:196-197` ⇒ `cap-millis: 2000`；`favorite-rpc` 侧另有一份同值的 `:220-221`）⇒ ⇒ 慢下游时 **api 那一跳的 cap 先到**（它在 t=0 起算，rpc 侧那一跳在 t=ε 起算 ⇒ 同为 2s ⇒ api 的更早）⇒ ⇒ api 收到的是**自己那一次的 `DEADLINE_EXCEEDED`**（**没有业务 description**）⇒ ⇒ `favoritecommon.BizCodeFromError` **反查不出业务码** ⇒ ⇒ 落 **`CODE_SERVER_BUSY(1015)`**（`favorite-common/.../FavoriteErrorCode.java:47` · 文案「服务繁忙」）⇒ ⇒ **HTTP 200 + `{code:1015}`**。<br>⇒ **B 侧自己写明了这是预期行为**：`favorite-api/.../client/FavoriteRpcClient.java:108-115`「Go 侧下游挂死时是 **2s 先到** ⇒ api 返回 **200 + 业务码 1015**；若只带 3s 的 ctx 预算，就会一路顶到 HTTP 超时分支 ⇒ 返回 503」⇒ ⇒ **1015 是 oracle 预期的行为，不是缺陷**。 |
| **C 侧行为** | **D1 合并 + D2 域内直连** ⇒ ⇒ **api→rpc 那一跳不存在** ⇒ ⇒ 2s cap 只剩「service→user-rpc」那一处 ⇒ ⇒ 超时被**领域层**接住 ⇒ ⇒ 按领域层自己的规则翻成 **5001** ⇒ ⇒ **同一个故障，两侧业务码不同**（双侧活体对拍实测 ⇒ **DRIFT-FAV-014**）。<br>⇒ ⇒ **2026-11-05 用户拍板「方向 ②」后修成**：<br>① **api 面自持同值上界** —— `service/favorite/src/main/java/com/rag/favorite/service/FavoriteApiSupport.java`：构造器注入 `ProtectionProperties` ⇒ 取 **`rag.protection.outbound-deadline.cap-millis`（= 2000，与共享层/出向拦截器**同一个配置键** ⇒ **不是「第二个 cap 来源」**）** ⇒ ⇒ `invokeRpc(...)` 用 **try-with-resources** 绑 `GoStyleRequestDeadline.bindAbsoluteNanos(...)`（**不换线程** ⇒ deadline 传播 / OTel 上下文 / 事务边界都不受影响）<br>② **判据用 cause 链不用耗时** —— `FavoriteApiSupport#resolveBizCode(Exception)` + `#carriesDeadlineExceeded(Throwable)`：**异常链里有 gRPC `DEADLINE_EXCEEDED` ⇒ 落 `ErrCodes.CODE_SERVER_BUSY(1015)`**；否则领域异常走自己的码<br>③ **领域层保留原始 gRPC cause** —— `FavoriteUserGuard#onDownstreamFailure` / `FavoriteArticleSnapshotResolver` 改用 `FavoriteExceptions.internalServerCommon(e)` ⇒ **保留 cause**（对齐 B 侧 `helper.go:100-107` / `:139-146` 把原始 gRPC error 交回上层） |
| **对齐的部分** | ✅ **api 面业务码同为 `1015`**（文案「服务繁忙」两侧逐字一致）· ✅ **HTTP 状态同为 200 + 业务信封** · ✅ **耗时桶两侧相同** `2000-2499`（⇒ 出向上界同值）· ✅ **gRPC 面完全不受影响**（C 侧 gRPC 面仍落 **5001**，B 侧 gRPC 面也是 5001 ⇒ 两侧同形） |
| **差异（结构性 · 载体不同）** | **B 侧靠「真 gRPC 往返天然带 cap」**；**C 侧靠「域内自持预算载体」** ⇒ ⇒ **载体不同，但可观察行为一致** ⇒ ⇒ 属 **L4 表达层 / 落点差异**，**不是行为漂移**（⇒ 本条登记的是「C 侧如何承接 B 侧既有形态」，与 `DEV-FAV-RPC-008` / `009` 同族）<br>⇒ ⇒ **刻意不做的事**：① **不**改领域层的错误映射（那会让 **C 侧 gRPC 面**变成 1015 ⇒ **用一个面换另一个面**）② **不**新写一个配置键（那会造出「两个 cap 来源」） |
| **慢 DB（非慢下游）那一格 —— ✅ 已闭合（2026-11-05 复核订正）** | 🔴 **此前误判为「已知边界 · 两侧仍不等 · 归 `common/` 收口任务」** ⇒ ⇒ **该前提不成立** —— 共享层的 SQL 超时载体 `DeadlineState` **早已落地**：<br>· **共享层链路（已落地）**：`HttpMiddleware` 在整条 filter chain 上绑 `DeadlineState.bindAbsoluteNanos(min(http-timeout 3s, rpc-timeout 2s) = 2s)` ⇒ ⇒ `DeadlineQueryTimeoutInjector` 按线程上的 deadline 给每条 SQL 设 `queryTimeout` ⇒ ⇒ PG 取消 ⇒ ⇒ `SqlTimeoutStatusTranslator` 把 **57014** 翻成 **1015** ⇒ ⇒ **慢 DB ⇒ 1015**<br>· **B 侧**：api→rpc 那一跳的 cap 覆盖**整个 rpc 侧处理（含 DB 写）** ⇒ 慢 DB 也落 1015<br>· ⇒ ⇒ **两侧同为 1015 ⇒ 该格已闭合** ⇒ ⇒ **本条不再有「已知边界」**<br>⇒ ⇒ ⚠️ **本行的错误来源**：原写「共享层 SQL 超时载体 `DeadlineState` 尚未接线 ⇒ 见 `GoStyleRequestDeadline` 类头『本仓该引用暂缺』」⇒ ⇒ 那是**批次 3 之前的过期文案**（`DeadlineState` 早已落地）⇒ ⇒ **过期注释被当成了现状** ⇒ ⇒ 该文案已在 `global` 收口域（`common/` 写权面内）就地订正（提交 `df9067d`）⇒ ⇒ **教训**：**注释不是现状**（AGENTS.md §3.1）⇒ ⇒ 判断现状只认**代码 + 运行证据** |
| **C 侧守门** | ① `http/FavoriteApiHopDeadlineContractTest` —— **5 条真触发**（真 gRPC 慢下游服务器，`GetUser` 睡 5s）：api 面 ⇒ **1015** / **反向对照** gRPC 面 ⇒ **仍 5001** / 非超时对照 ⇒ **仍 5001** / api→领域层这一跳**必须自带预算载体** / 慢下游服务器**真的在监听**<br>② **三处机制变异（红 → 绿）**：去掉「`DEADLINE_EXCEEDED` ⇒ 1015」⇒ **红**；领域层丢掉 gRPC cause ⇒ **红**；不绑预算载体 ⇒ **红** ⇒ ⇒ 还原后复跑 **绿** ⇒ ⇒ **判据有牙齿**<br>③ 场景轮 **S3**（双侧活体）⇒ **两侧同为 1015** ⇒ ⇒ `KNOWN_DIFFS` **已清空** |
| **状态** | 🟡 **C 侧专属偏离**（载体差异 · 非行为漂移）· **2026-11-05 用户拍板「方向 ②」后新增**（`DRIFT-FAV-014` 的处置载体） |

---

## DEV-FAV-COMMON-001 · ✅ 已签字 · `GRPCError(codes.OK, …)` 的返回形态

| 项 | 内容 |
|---|---|
| **B 侧行为** | `favorite-common/…/errmsg/FavoriteGrpcError#grpcError` **允许**用 `codes.OK` 构造 error ⇒ ⇒ grpc-java 会把一个 **OK 码的 error** 写上线路（客户端侧表现为 `StatusRuntimeException(INTERNAL)` 或空 status）。<br>⚠️ 该分支在本域实际**几乎不可达**（业务码恒为失败码），但 B 侧**没有**在 `grpcError` 里拦截 `codes.OK`。 |
| **C 侧行为** | `service/favorite/src/main/java/com/rag/favorite/contract/GrpcErrorMapper#grpcError` —— **刻意不拦截** `Status.Code.OK` ⇒ 与 B 侧逐字同形（**原样透传**调用方给的 gRPC 码）。 |
| **差异** | **无**（C ≡ B）。本条登记的是「**B 侧与 Go 的这处形状差异，C 侧继承**」。 |
| **为什么接受** | C ≡ B ⇒ 不构成 C 侧漂移；且**刻意拦截**反而会让「业务码 ↔ 文案」的往返表出现一条 B 侧不存在的分支（违反 D16）。 |
| **状态** | ✅ **已签字**（B 侧）⇒ C 侧**继承**。 |

---

## DEV-FAV-COMMON-002 · ✅ 已签字 · `status.FromError` 的「顶层 vs cause 链」判定

| 项 | 内容 |
|---|---|
| **B 侧行为** | `favorite-common/…/errmsg/FavoriteGrpcError#bizCodeFromError` 调 `status.FromError(err)` ⇒ **只检查顶层** error 是否实现 `GRPCStatus()`，**不沿 cause 链回溯**。<br>⇒ 一个被业务代码包了一层的 `StatusException` ⇒ **落 1015**（`CODE_SERVER_BUSY`），**不**还原出业务码。 |
| **C 侧行为** | `contract/GrpcErrorMapper#extractStatus` —— **手写单层判定**（只认 `StatusException` / `StatusRuntimeException` 这两个**顶层**类型）⇒ **刻意不沿 cause 链**。<br>⚠️ 理由已写进该方法的 javadoc：grpc-java 的 `Status.fromThrowable` **会**沿 cause 链回溯 ⇒ 若直接用它，一个被包了一层的 `StatusException` 在 **C 侧会还原出业务码**、在 **B 侧会落 1015** ⇒ **两侧分叉**。 |
| **差异** | **无**（C ≡ B）。本条登记的是「**B 侧与 Go 的这处语义差异，C 侧继承**」。 |
| **状态** | ✅ **已签字**（B 侧）⇒ C 侧**继承** ⇒ ⚠️ **且 C 侧是**主动**照抄的**（不是无意继承）⇒ 该方法的 javadoc 必须保留这条理由，否则后人会「顺手优化成 `Status.fromThrowable`」⇒ 直接引入一条**两侧分叉**的漂移。 |

---

## DEV-FAV-RPC-001 · ✅ 已签字 · `favorite_request_seconds_counter` 的 Prometheus 渲染名

| 项 | 内容 |
|---|---|
| **B 侧行为** | 指标名 `favorite_request_seconds_counter` —— ⚠️ **以 `_counter` 结尾**，但它承载的是**耗时直方图**（`_seconds`）⇒ Prometheus 渲染出的是 `favorite_request_seconds_counter_seconds_bucket` 这类**怪名**（`_counter` + `_seconds` 双重后缀）。 |
| **C 侧行为** | `metrics/FavoriteRpcMetrics` —— 4 个指标名**逐字照抄**：`favorite_request_total` / `favorite_request_seconds_counter` / `favorite_ops_total` / `favorite_db_error_total`。 |
| **差异** | **无**（C ≡ B）。本条登记的是「**B 侧与 Go 的这处命名差异，C 侧继承**」。 |
| **为什么接受** | 改名会让两侧的监控面板 / 告警规则**全部失效** ⇒ 指标名是 **L1 运维契约**，不得「顺手修正」。 |
| **状态** | ✅ **已签字**（B 侧）⇒ C 侧**继承**。 |

---

## DEV-FAV-RPC-003 · ✅ 已签字 · preflight 的退出码 → 结构化日志（U2）

| 项 | 内容 |
|---|---|
| **B 侧行为** | `fact/FavoriteLegacyPreflightRunner` 是一次性进程 ⇒ 用**退出码**表达结果（`exit 2` = 未裁决 / `exit 1` = 读取失败 / `exit 0` = clear）；B 侧签字把它改成**结构化日志**输出。 |
| **C 侧行为** | ✅ **已落位**（2026-11-05 · 决策 **P6-c** 拍板 = **① Actuator 自定义端点**）——<br>· 报表计算：`service/FavoriteLegacyPreflightService`（逐条照抄 Go 的判定）<br>· 触发入口：`wiring/FavoriteLegacyPreflightEndpoint`（`@WebEndpoint("favorite-legacy-preflight")`）<br>· **结构化日志照抄 B 侧**：`event=favorite.legacy_preflight` ＋ `outcome=succeeded\|not_reconciled\|failed` ＋ `error_code=LEGACY_RECONCILIATION_REQUIRED\|PREFLIGHT_READ_FAILED` ＋ 5 个报表字段（进程身份 `rtw-favorite-legacy-preflight` / 组件 `legacy-preflight` 逐字取自 `FavoriteFactProcessLogging`）。 |
| **⚠️ 触发形态的差异** | B 侧 = `@EventListener(ApplicationReadyEvent.class)`（**启动时跑一次**，结果只在日志里）；C 侧 = **按需触发**（运维/CI 打端点）⇒ ⇒ **Go 的退出码语义**（`0/1/2`）在 C 侧**没有对应物** ⇒ C 侧的替代信号 = **HTTP 状态码**（200 / 409 / 500）⇒ ⇒ 这条形态差异单独登记为 **`DEV-FAV-RPC-009`**。 |
| **状态** | ✅ **仍成立**（B 侧已签字 · C 侧已承接；2026-11-05 从「随缺口挂起」转正）。 |

---

## DEV-FAV-RPC-004 · ✅ 已签字 · 权威读端点的部署形态（U1）

| 项 | 内容 |
|---|---|
| **B 侧行为** | 权威读端点（`cmd/fact-authority/main.go`）是**独立进程**，监听**独立 HTTP 端口**；wire 形态是 favorite **自持**的 **path 参数** servlet：`GET /internal/v1/favorite/facts/{producer}/{event_id}`（+ v2 变体）⇒ **不是** B 侧通用 `AuthorityHandlerFactory` 的 query 形态（`/internal/v1/community/facts?producer=&event_id=`）。 |
| **C 侧行为** | `wiring/FavoriteAuthorityEndpointConfig` —— 做成**域内 Servlet 装配**（**零 gRPC 注册** ⇒ 无强类型契约依赖独立进程 ⇒ 可并入域内 app）；**path 参数形态逐字保留**。 |
| **差异** | **部署形态**不同（独立进程 ⇒ 域内 Servlet）—— 属 **L3 部署层**，**不是 L1 wire 差异**（path 参数形态与响应体字节口径均逐条保留）。 |
| **⚠️ 响应体字节口径（L1）** | 权威读的响应体**字节**是跨服务契约：B 侧 `json.NewEncoder().Encode` **追加尾 `\n`** ⇒ C 侧**显式补写尾 `\n`**（`wiring/FavoriteAuthorityEndpointConfig`）。<br>⚠️ `escapeHTML`：B 侧 Java 端口用 Jackson 2 **默认不转义** ⇒ C 侧**照抄**（**不**加 `CharacterEscapes`）⇒ 加了会让 **C ≠ B**（新增漂移）。 |
| **状态** | ✅ **已签字**（B 侧）⇒ C 侧**继承**（部署形态的收敛已由 D1「api + rpc 合并为单进程」拍板）。 |

---

## DEV-FAV-RPC-006 · ✅ 已签字 · 约束与索引的**名字**（12 处）

| 项 | 内容 |
|---|---|
| **B 侧行为** | 4 张表的**约束名 / 索引名**与 Go 侧原始 DDL **不同**（Go 侧用内联匿名约束 / 无名索引；B 侧给了显式名字）⇒ 共 **12 处**命名差异。 |
| **C 侧行为** | `resources/db/migration/V1..V7` —— **SQL 正文逐字零差异** ⇒ 约束名 / 索引名**逐字保留** B 侧的 12 处命名。 |
| **差异** | **无**（C ≡ B）。本条登记的是「**B 侧与 Go 的这处命名差异，C 侧继承**」。 |
| **为什么接受** | 约束名是**运维契约**（`ALTER TABLE … DROP CONSTRAINT` 的依据）⇒ 改名会让线上运维脚本与告警规则失效。 |
| **状态** | ✅ **已签字**（B 侧）⇒ C 侧**继承**。 |

---

## DEV-FAV-RPC-007 · ✅ 已签字 · `favorite_item` 上多一道 `target_revision scope` CHECK

| 项 | 内容 |
|---|---|
| **B 侧行为** | `favorite_item` 表上多一道 **`target_revision scope` CHECK** ⇒ **B 侧比 Go 更严**（Go 侧没有这道 CHECK）。 |
| **C 侧行为** | `resources/db/migration/V6__align_favorite_schema_with_go.sql` —— **SQL 正文逐字零差异** ⇒ 这道 CHECK **逐字保留**。 |
| **⚠️ 附带** | 🔴 **映射表 §2.1 漏记了这条**（`docs/Mapping_Table/favorite/favorite.md`）⇒ 已在本文件与 `docs/DRIFT/favorite/` 台账里**补记**（映射表条目：M4 的「⚠️ 附带缺口」行）。 |
| **为什么接受** | B 侧更严 ⇒ 是**有意的加强**（防 `target_revision` 越界）⇒ C 侧继承即可；**不**回溯 Go。 |
| **状态** | ✅ **已签字**（B 侧）⇒ C 侧**继承**。 |

---

## DEV-FAV-RPC-008 · 🟡 C 侧专属 · preflight 的**只读快照事务**（B 侧注解在，但实际不生效）

| 项 | 内容 |
|---|---|
| **Go 真源行为** | `service/favorite/rpc/internal/model/favorite_legacy_preflight.go:35` ⇒ `sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true}` ⇒ ⇒ **有**一个可重复读的只读快照（两条 SELECT 看同一份快照）。 |
| **B 侧行为** | `fact/FavoriteLegacyPreflightRunner.java:157-158` **写了** `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`，且其 javadoc 明写「Java 侧必须同样声明，否则一条误写的 UPDATE 会污染『证据快照』」⇒ ⇒ **意图明确**。<br>🔴 **但实际不生效**：该注解所在的 `preflight()` **只**被同一个 bean 的 `run()` 调用（B 侧 `:115`；全仓 grep 无第二个调用点）⇒ ⇒ **Spring AOP 代理被绕过**（自调用）⇒ ⇒ **事务从未生效**，两条 SELECT 各自跑在自动提交里。 |
| **C 侧行为** | `service/FavoriteLegacyPreflightService#preflight` —— **刻意不加** `@Transactional` ⇒ ⇒ **照抄 B 侧的实际行为**（AGENTS.md §三 · D16：B 侧已有的差异**一律继承**，登记为签字偏离，**不回溯第三方**）。<br>⇒ ⇒ 理由：① Go 有、B 没有 ⇒ 这是「B 侧已有的差异」⇒ 继承；② 写成「有事务」就是 C 侧单方面比 B 侧严 ⇒ 与 D16 冲突。 |
| **⚠️ 可观察性边界** | 差异**只在并发写下**可能出现（两条 SELECT 看到不同快照）⇒ 而 Go 的注释要求「先冻结真实写入方再在生产快照上重跑」（`favorite_legacy_preflight.go:26-28`）⇒ ⇒ **在预检的预期用法下不可观察**。 |
| **为什么不写成「C 侧修正了它」** | 那会让 C 侧在这一点上**优于** B 侧 ⇒ 与 D16「差异默认判 RAG-V1 错」冲突 ⇒ ⇒ 且本条**已登记** ⇒ ⇒ 要改必须走决策流程。 |
| **状态** | 🟡 **C 侧专属偏离**（B 侧与 Go 的差异被 C 侧继承）· 2026-11-05 新增。 |

---

## DEV-FAV-RPC-009 · 🟡 C 侧专属 · preflight 的**触发形态**（Actuator 端点）＋ **Actuator 响应被业务信封包裹**

| 项 | 内容 |
|---|---|
| **B 侧行为** | `fact/FavoriteLegacyPreflightRunner` = `@Component` + `@EventListener(ApplicationReadyEvent.class)` ⇒ **进程启动时跑一次** ⇒ 结果只进**日志** ⇒ 运维要读日志。<br>B 侧**没有**任何 HTTP 面 ⇒ **没有**可对拍的 HTTP 契约。 |
| **C 侧行为** | 决策 **P6-c** 拍板 = **① Actuator 自定义端点** ⇒ ⇒ `wiring/FavoriteLegacyPreflightEndpoint`（`@WebEndpoint(id = "favorite-legacy-preflight")`）⇒ URL = `/actuator/favorite-legacy-preflight`。<br>· **替代信号**：Go 的退出码（`0` clear / `1` 读取失败 / `2` 未裁决）⇒ C 侧 = **HTTP 状态码**（**200** / **500** / **409**）＋ **结构化日志**（双通道）。<br>· **默认关闭**：两个开关都默认 false ⇒ 端点**不注册** ⇒ 访问得 **404** ⇒ ⇒ 健康检查 / K8s 探针**不会**误触发预检（真触发断言见 `http/FavoriteLegacyPreflightEndpointTest#healthCheckDoesNotTriggerPreflight`）。 |
| **⚠️ 附带差异：Actuator 的 JSON 响应被本域的全局 `@RestControllerAdvice` 包成业务信封** | 机制：Actuator 的 `WebMvcEndpointHandlerMapping` 产出的**也是 HandlerMethod** ⇒ ⇒ 走同一条 `ResponseBodyAdvice` 链 ⇒ ⇒ 连**健康端点**的 body 都是 `{"code":200,"msg":"OK","data":{…}}`。<br>⇒ ⇒ **B 侧的 api 进程也这样**（它的 `GlobalResponseAdvice` 同样是全局的）⇒ ⇒ **C ≡ B（api 面）** ⇒ ⇒ **不是漂移**；<br>⇒ ⇒ 但 B 侧的 **rpc 进程没有这个 advice** ⇒ ⇒ **D1 合并后** C 的管理面与 B 的 rpc 面不同 ⇒ ⇒ 这部分才是 D1 的结构性差异。<br>⚠️ **可观察后果**：健康端点 DOWN 时 **HTTP 状态是 503**，而 body 的信封里 `code` 是 **200** ⇒ ⇒ **探针必须读 HTTP 状态，不能读 body 里的 `code`**。 |
| **为什么不把 advice 的作用域收窄** | 收窄能让 Actuator 变回裸响应（更像 Go 的 stdout 形状）⇒ ⇒ 但那会让 **C ≠ B（api 面）** ⇒ ⇒ 与 D16 冲突 ⇒ ⇒ **不收窄**，并把现状登记在此。<br>⇒ ⇒ 守护断言：`http/FavoriteLegacyPreflightEndpointTest#actuatorJsonResponsesAreWrappedByTheDomainEnvelope` ⇒ 哪天有人改了作用域，这条会红 ⇒ ⇒ 逼他同步改本登记。 |
| **状态** | 🟡 **C 侧专属偏离**（形态差异 · 非行为漂移）· 2026-11-05 新增。 |

---

## ⚠️ 计数与后续

- **本域登记条数：12 条**（`API` 3 + `COMMON` 2 + `RPC` 7）⇒ 其中 **9 条 ✅ 仍成立**、**3 条 🟡 C 侧专属偏离**（`DEV-FAV-RPC-008` 事务 · `DEV-FAV-RPC-009` 触发形态 · `DEV-FAV-API-003` api→领域层自持出向上界）。
- ✅ **2026-11-05 更新**：`DEV-FAV-RPC-003` 从「🟡 随缺口挂起」转 **✅ 仍成立**（P6-c 拍板① ⇒ C 侧落位 `wiring/FavoriteLegacyPreflightEndpoint`）。
- ✅ **2026-11-05 新增 `DEV-FAV-API-003`**：**DRIFT-FAV-014**（慢下游时 api 面业务码 B=1015 / C=5001）由用户拍板**方向 ②**后修成 ⇒ ⇒ **api 面两侧已同为 1015** ⇒ ⇒ **行为层已对齐** ⇒ ⇒ 本条登记的是**载体差异**（C 侧自持预算载体 vs B 侧真 gRPC 往返天然带 cap）⇒ ⇒ **不是行为漂移**。
- ⚠️ **主线更新 `docs/Intentional_Deviations/_INDEX.md` 时本域**需**新增一行**（当前索引里**没有** `favorite` 行）—— **该索引不在本域写权范围** ⇒ 由主会话统一更新。
- ⚠️ **不要**把这里的差异当新回归去「顺手修回 Go 行为」（AGENTS.md §0 的共性提醒）。
