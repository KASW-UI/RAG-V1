# task 域 —— 双侧活体对拍探针（B 侧 RAG ↔ C 侧 RAG-V1）

> **被测两侧（同时起、同时活、同请求、同字节）**
> · **B 侧** = `/Users/pr/Documents/java&golang/RAG/service/task/{task-api,task-rpc}`（Boot **4.0.8**）
> 　　⚠️ B 侧是 **两个进程**（api = HTTP 壳 · rpc = gRPC 业务）⇒ 探针要起 **2 个** jar；
> · **C 侧** = 本仓 `service/task`（Boot **3.5.11** · **D1 合并进程**：HTTP + gRPC 同进程）⇒ 1 个 jar。

---

## 一、判定强度：E-4（双侧活体对拍），**不冒充「三方一致」**

本机**没有 Go 真源可活体** ⇒ 判定条件只有一条：**C == B（双侧活体逐字节相等）**。
⇒ 与 `tools/difftest/like/`、`tools/difftest/hot/` 同款口径。

> ✅ **HTTP 面（M1~M4）与 gRPC 面（M5）**都是 **E-4（双侧活体对拍）**
> ⇒ ⇒ task 域的 **HTTP 契约**与 **gRPC wire 契约**两条面**都有双侧证据**
> ⇒ ⇒ 台账 §七·7-1 的 **U-6（gRPC 面尚未双侧对拍）已闭合**。

> ⚠️ **B 侧只读**：探针只在 B 仓跑 `mvn package`（产物落 `target/`，已被 `.gitignore` 排除）
> ⇒ **不碰 B 侧任何源码 / 配置**（第 0 步断言 B 仓 `git status` 干净，**断言失败即中止**）。

---

## 二、五个对拍面

| 面 | 比什么 | 怎么比 |
|---|---|---|
| **M1 HTTP 字节面** | **原始状态行** ＋ `Content-Type` ＋ `X-Content-Type-Options` ＋ `Allow` ＋ **body 原始字节** | 裸 socket（**不用 curl** —— curl 只给状态码，**不给状态行 reason phrase**） |
| **M5 gRPC 字节面** | **status code** ＋ **description** ＋ **响应的每条任务字段与顺序**（id / name / desc / progress / required） | 真 netty client（`grpc_probe/GrpcProbe.java`）打真 gRPC server |
| **M2 Redis 状态面** | 回填后的 `task:progress:<uid>` **hash 内容** ＋ **存在性** ＋ **TTL** | 两侧**物理隔离**的 Redis 容器；TTL 判据 = **口径判据**（每侧各自 ∈ [900 − 窗口时长, 900]）⇒ **负载免疫**（见 §3.2） |
| **M3 PG 状态面** | `task_progress` / `user_like_count` 的行 | 两侧**物理隔离**的 Postgres 容器（**库名也不同**）⇒ 读路径**不得**写库 |
| **M4 冻结锚面** | C 侧读数 vs `expected.txt`（**冻结的是 B 侧读数** ⇒ oracle 是 B 侧）<br>（gRPC 面同理：`grpc_expected.txt`） | `expected.txt` 逐条比对 ⇒ **B 侧漂移**与 **C 侧回归**都会被抓到 |

> 🔴 **M5（gRPC 面）是「新增的覆盖」，不是 M1 的重复** —— 它有**自己的变异**（M-P4）证明：
> **只动 gRPC 出口 ⇒ HTTP 面完全看不见 ⇒ gRPC 面抓到了 HTTP 面抓不到的东西**（§六）。

### 2.1 刻意**不比**的字段（及原因）

| 不比 | 原因 |
|---|---|
| `Date` / `Server` | 每次请求都变 |
| `Content-Length` | 由 body 长度决定（body 已逐字节比）⇒ 比它是冗余 |
| `Connection` | 由连接复用策略决定（两侧框架版本不同 ⇒ 必然不同 ⇒ **假红**） |
| `Traceparent` | OTel 追踪 ID，**每次都变** |
| **1xx 行数** | 🔴 本仓共享层 `common/observability/ClientDisconnectProbe` 会**直接往 socket 写** `HTTP/1.1 103 Early Hints\r\n\r\n` 作断连探针 ⇒ **每个响应前可能有 1~N 行 1xx**（行数取决于 socket 写时机 ⇒ 两侧不必相同 ⇒ 不可比）。⇒ 探针按 **RFC 9110** 跳过所有 1xx，只取最终响应 |
| **gRPC 的 trailers** | 🔴 `grpc-status` / `grpc-message` 是**保留伪头** ⇒ **被 grpc-java 消费掉** ⇒ **不留在 trailers 里** ⇒ **文案只能从 `Status#getDescription()` 读** ⇒ 比 trailers 是比一个**框架已经消费掉的**东西 |
| **channel / 连接细节** | 两侧框架版本不同 ⇒ 必然不同 ⇒ **假红** |

---

## 三、四档判定（**白名单不是豁免**）

| 档 | 判定 | 何时用 |
|---|---|---|
| **普通用例** | B 与 C 的读数**必须逐字节相同**；且 C 与**冻结锚**必须相同 | 默认 |
| **已登记的表达层差异**（`known-cases.txt`） | ① C 的读数必须**逐字节等于** `known-readings.txt` 里登记的形状（⇒ **差异形状变了 ⇒ 失败**）；② B 的读数必须等于冻结锚（⇒ **B 侧漂移 ⇒ 失败**） | 差异**已登记为有意偏离**时 |
| **无效读数（不可判）** | 🔴 **oracle 侧（B）本轮没给出业务应答** ⇒ 该条**不计入一致数、也不计真差异** ⇒ 探针 `EXIT=3`（⇒ `run.sh` 归为「环境不稳 ⇒ 需重跑」） | oracle **自己**超时报错时（见 §3.1） |
| **真差异** | 任何不满足上面几条的 ⇒ **失败**（`EXIT=1`） | — |

> ⇒ **净效果**：「允许不同」被钉成「**只允许差这一处、且必须还是当初那一处**」。
> ⇒ **两面各有一套**白名单（HTTP：`known-cases.txt` ＋ `known-readings.txt` + `expected.txt`；
> gRPC：`grpc_known-cases.txt` ＋ `grpc_known-readings.txt` + `grpc_expected.txt`）⇒
> **口径完全一致**，只是**各钉各的面**。

### 3.1 🔴 「oracle 无效读数」这一档（**冤枉 C 侧的代价比假红更高**）

**判据 = 「这一侧有没有给出业务应答」**，不是「两个读数是否相等」：

| 侧 | 判为「无效」的条件 |
|---|---|
| HTTP | 连不上 / 无响应 / **状态码 ≥ 500**（RFC 9110：服务端没能完成请求） |
| gRPC | `UNAVAILABLE` / `DEADLINE_EXCEEDED` / `CANCELLED`（🔴 **这三个不在 B 侧的业务 status 集合里** ⇒ 只可能来自传输层） |

> ⚠️ **4xx 一律有效** —— 本域的错误路径**故意**用 400/404/405 表达 ⇒ 若把 4xx 也算无效 ⇒ **所有错误用例都被降级成不可判** ⇒ 判据直接失效。
> ⚠️ **gRPC 的 `UNKNOWN` / `INTERNAL` 一律有效** —— 它们是**服务端真正产生的 status**（业务语义）⇒ 必须参与逐字节比对。

**为什么要单独这一档**（2026-11-10 实测暴露）：

B 侧（oracle）是 **task-api → task-rpc 的真实跨进程 gRPC 调用** ⇒ **B 侧自己有内部 hop 超时**
（B 侧 `observability.rpc-timeout: 2s`；`DEADLINE_EXCEEDED ⇒ 504` 的映射见 B 侧
`GlobalExceptionHandler.java:130-131`）⇒ ⇒ 机器一忙，**B 侧自己吐 504**，而同刻 **C 侧（单进程直调，没有这一跳）返回 200**
⇒ ⇒ 旧判据会**把 oracle 自己的失败记成「C 侧有差异」⇒ 冤枉 C 侧**（还会误导人去改本来对的 C 侧代码）。

**三档后果**：

| 情形 | 判定 | 退出码 |
|---|---|---|
| oracle 侧无业务应答 ＋ C 侧正常 | **不可判**（不是「C 侧有差异」） | `3` |
| oracle 侧正常 ＋ **C 侧无业务应答** | **真差异**（基准存在 ⇒ C 该答没答） | `1` |
| oracle 侧无业务应答 ＋ **C 侧与冻结锚不符** | **真差异**（不可判**不能**吃掉真回归） | `1` |

**配套的连带处理**：

- 🔴 **冻结时只收「oracle 侧有业务应答」的读数** ⇒ 否则会把一条 5xx 冻进锚 ⇒ **毒化锚**（下一轮所有正常读数都会被误判成「与锚不一致」）。
- 🔴 **FREEZE 时若存在不可判条目 ⇒ 拒绝写锚**（部分锚 = **静默削弱的锚** ⇒ 下一轮那些用例会跳过锚校验 ⇒ 等于把回归检测悄悄关掉）。
- 🔴 **M2（Redis）同步降级**：oracle 侧那次读**有没有把缓存回填进 Redis 是不确定的** ⇒ 该 uid 的 Redis 状态**不可判** ⇒ 跳过并标注（**只跳过受影响的 uid** ⇒ 不整面跳过 ⇒ 不吞掉其它 uid 的真差异）；**M3（PG）不跳过**（读路径本来就不写库 ⇒ 与 oracle 是否答上来无关）。
- 🔴 **变异脚本同步分流**：遇到 `EXIT=3` ⇒ 判「本轮**不成立**」⇒ **不能算「变异被触发」**（否则环境抖一下就被当成「变异生效」⇒ 假绿）。
- 🧪 **离线可复现**：`python3 tools/difftest/task/test_invalid_tier.py` 用假服务器造出 **8 种情形** ⇒ 断言退出码分别是 **3 / 1 / 0 / 1 / 1 / 3 / 3 / 1** ⇒ ⇒ 这一档**不再依赖「真机恰好抖一次」才被观察到**。<br>· 最后一条 **G2** 是**反向用例**（已登记差异用例 ＋ oracle 无效 ＋ **C 侧形状真的变了** ⇒ 仍须 `EXIT=1`）⇒ ⇒ 防止「把 G 改成永远绿」。

### 3.2 🔴 TTL 判据：**口径**判据，不是**时序**判据

M2 的 TTL 判据是：**每个存在的 key，两侧各自的 TTL 必须落在 `[900 − 窗口时长, 900]`**。

- **上界 900** = 两侧 TTL 口径都是 **15m**（B 侧 `TaskRedisKeys.PROGRESS_CACHE_TTL`）⇒ **口径**判据 ⇒ **与机器负载无关**；
- **下界 900 − 窗口时长** = 这个 key **最早**可能是「播种/回填」那一刻写的 ⇒ **窗口随负载自动放大** ⇒ ⇒ **不会假红**。

> ⚠️ **原判据「两侧 TTL 绝对差 > 10s ⇒ 失败」是「时序」判据** ⇒ 两侧写入天然**错开一个请求往返**（探针先发 B、等它返回、再发 C）⇒ 机器忙时这个往返能到 15~20s ⇒ ⇒ **把「时序」误判成「行为差异」** ⇒ 实测 10 条 key 里 8 条误报。
> ⇒ ⇒ **绝不能拍魔法数字**：判据要么是**口径**的（与负载无关），要么把时序偏移**实测**出来并扣掉。

### 3.3 已登记的表达层差异（**1 条**）

| 用例 | 差异 | 为什么**不修** |
|---|---|---|
| `err-empty-body` | Spring 6.2 的「缺 body」默认报文是 `Method.toString()` ⇒ 内嵌 DTO / Controller 的**类全限定名**：<br>B `com.rag.task.task.api.dto.GetTaskReq` ↔ C `com.rag.task.dto.task.GetTaskReq` | 成因是 **D7（域内 api+rpc 压平）＋ D11（dto 按业务对象拆子包）** —— **已锁定** ⇒ 要对齐就得把 C 侧包名改回 B 的形状 ⇒ **与 D7/D11 直接冲突**<br>⇒ **状态码 / Content-Type / nosniff / 报文结构全部逐字节相同**，**只有类名不同** ⇒ 表达层差异，非行为漂移<br>⇒ 台账：**DRIFT-TASK-016** |

### 3.4 🔴 **预热**与**就绪等待**（两个「测量条件」问题，2026-11-10 加）

**预热**（`run.sh` 步骤 7 内 · **播种之后 · 量之前**）

- 🔴 **为什么必须**：B 侧 **task-api → task-rpc 是跨进程调用**，出向带 **2 s deadline**
  （B 侧 `service/task/task-api/src/main/resources/application.yml:270`
  `cap-millis: ${TASK_API_OUTBOUND_DEADLINE_CAP_MILLIS:2000}`）
  ⇒ **JVM 刚起**时第一次请求要走「类加载 + JIT + 建连」⇒ 这一跳**首次调用**就能超 2 s
  ⇒ ⇒ **B 侧自己吐 504** ⇒ ⇒ **每一次都是第一个用例挂**（实测**必然而非偶发**）
  ⇒ ⇒ C 侧是**单进程直调**（**没有这一跳**）⇒ ⇒ **冷启动只罚 B 侧 ⇒ 对拍不公平**
  ⇒ ⇒ **「重跑一次就好」是错的**（每次重跑都是新 JVM ⇒ 每次第一个用例都挂 ⇒ 探针会**永远** `EXIT=3`）。
- 🔴 **预热怎么做**：**两侧各发一次**预热请求（HTTP 面 ＋ gRPC 面），uid = **9999**
  ⇒ **9999 不在任何用例表里** ⇒ 不会被量 ⇒ 且两侧对它都走「Redis 未命中 ⇒ PG 无行」
  ⇒ 对**不存在的** key 设 `EXPIRE` 是 **no-op** ⇒ **不产生任何 key** ⇒ 读数面与状态面**零污染**。
- 🔴 **零污染是断言出来的，不是假设出来的**：探针**显式断言**「预热后两侧 Redis 都没有 `task:progress:9999`」
  ⇒ 预热若污染了状态面 ⇒ 直接 `die` ⇒ **不可能静默假绿**。
- 📌 **gRPC 探针的 `build` 提前到预热之前**（预热要用同一个探针）⇒ 步骤 9 保留**幂等重建**。

**Nacos 就绪等待**（`run.sh` 步骤 3）

- 🔴 `Sea-nacos` 是 **Spring 应用** ⇒ 冷启动实测 **~200 s**
  （`Root WebApplicationContext initialization completed in 67150~115538 ms`）
  ⇒ ⇒ Docker 刚重启 / 容器刚拉起时 **API 还没起来** ⇒ ⇒ 建命名空间必然失败
  ⇒ ⇒ 探针改为**显式轮询就绪**：`/nacos/v1/console/health/readiness` = `200`
  ⇒ 最多等 `NACOS_WAIT`（默认 **300 s**）⇒ 每 30 s 打一次进度 ⇒ 超时才 `die`，且写明「**环境问题，不是本域缺陷**」。
- 🔴 **以 API 读数为准，不看容器 healthcheck** —— 实测 `Sea-nacos` 的 healthcheck **常年 unhealthy**，
  但 readiness API **正常返回 200** ⇒ ⇒ 用 healthcheck 当判据会把「就绪」误判成「没起来」。

---

## 四、隔离设计（**不是「省一个容器」的取舍，是正确性**）

| 资源 | B 侧 | C 侧 | 为什么必须隔离 |
|---|---|---|---|
| **Redis** | 容器 `e5task-b-redis-$$` @ `18243` | 容器 `e5task-c-redis-$$` @ `18244` | 🔴 读路径在 Redis 未命中时会**回填** `task:progress:<uid>` ⇒ 共用一个 Redis 时，后跑的一侧会命中前跑一侧写下的缓存 ⇒ **PG 回源那条路径被吃掉** ⇒ **假绿** |
| **Postgres** | 容器 `e5task-b-pg-$$` @ `18245` / 库 `taskdiff_b` | 容器 `e5task-c-pg-$$` @ `18246` / 库 `taskdiff_c` | 两侧**各自跑自己的 Flyway 迁移** ⇒ 独立证明「C 侧迁移真的建出了表」 |
| **Nacos** | 命名空间 `taskprobe` ＋ group `taskdiff$$` | 同左 | 共享 `Sea-nacos`（49848）⇒ 唯一 group ⇒ 不与别的 lane 串扰 |
| **Kafka** | **一律 `127.0.0.1:1`（死地址）** | 同左 | 本探针只测 **HTTP 读路径** ⇒ Kafka 不在这条路径上；且两侧的 `spring.kafka.listener.*` **都是死键**（两侧都自建 `kafkaListenerContainerFactory` ⇒ Boot 的 configurer 不参与 ⇒ 那些键**没有消费点**）⇒ **没法用 `auto-startup=false` 关 listener** ⇒ 只能用「broker 不可达」让它们连不上（**两侧对称施加** ⇒ 对拍公平性不受影响） |
| **用例 uid** | HTTP 面 `1001~1006` · **gRPC 面 `2001~2006`** | 同左 | 🔴 **两面必须物理错开**：`ok-pg-fallback` 会**回填** Redis 缓存 ⇒ 若共用 uid ⇒ 谁先跑谁就替对方把回填做了 ⇒ HTTP 面的 **M2 状态面**（验「读路径确实写了缓存」）就**失去判据** ⇒ ⇒ 两面互不污染 |

---

## 五、用法

```bash
bash tools/difftest/task/run.sh                # 对拍（同时校验冻结锚 + 已知差异白名单）
FREEZE=1 bash tools/difftest/task/run.sh       # 重新冻结 B 侧读数到 expected.txt ＋ grpc_expected.txt
KEEP_WORK=1 bash tools/difftest/task/run.sh    # 保留临时目录与日志
SKIP_B_BUILD=1 bash tools/difftest/task/run.sh # B 侧产物不变时跳过 B 侧构建
REPORT_OUT=/tmp/http.txt GRPC_REPORT_OUT=/tmp/grpc.txt bash tools/difftest/task/run.sh
                                                 # 把两面明细导出到指定路径（变异脚本用）

bash tools/difftest/task/mutate-parity.sh      # HTTP 面变异验证（证明判据有牙齿）
bash tools/difftest/task/mutate-parity.sh M-P1 # 只跑一条变异
bash tools/difftest/task/mutate-grpc-parity.sh # gRPC 面变异验证（M-P4）

python3 tools/difftest/task/test_invalid_tier.py  # 🧪 「oracle 无效读数」档的离线判据测试（8 情形：3/1/0/1/1/3/3/1）
```

**退出码（四个各有各的含义 —— 🔴 「非零」不是一个语义）**：

| 码 | 含义 | 处置 |
|---|---|---|
| `0` | 判定通过 | — |
| `1` | **真差异**（C 侧确实与 oracle 不一致） | 修代码 |
| `2` | **环境问题**（依赖缺失 / 构建失败 / 容器起不来 / 真源漂移）⇒ **本轮什么都没证到** | 修环境后重跑 |
| `3` | **oracle 无效读数**（oracle 自己没答上来 ⇒ 本轮不可判） | 需重跑 |

> ⇒ 🔴 **判据脚本若只判 `rc != 0` ⇒ 「环境挂了」与「判据被触发」会被混为一谈** ⇒
> ⇒ 而这两者的**处置完全相反**（前者修环境，后者修代码）⇒ ⇒ **每个退出码都要有自己的报错文案**。

### 5.1 端口段

**18241-18248**（本探针专用）：B api `18241` · B rpc `18242` · B redis `18243` · C redis `18244` ·
B pg `18245` · C pg `18246` · C http `18247` · C grpc `18248`。
（follow 占 18201- · like 占 18211- · hot 占 18221-）

---

## 六、变异验证（**证明判据不是假绿**）

`mutate-parity.sh` 每条变异要求**三件事同时成立**：

1. **目标断言转红** —— 探针 `EXIT != 0`，**且**指定用例在明细里变成 `DIFF`；
2. **对照断言仍绿** —— 其它用例仍是 `SAME`（⇒ 红的是**被测的那一处**，不是「全红」）；
3. **还原后回绿** —— 还原源码、重建、重跑 ⇒ `EXIT == 0`（`cmp` 断言逐字节还原）。

> ⚠️ **「变异后变红」本身不足以证明判据有牙齿** —— 若是**编译失败**导致全红，那也是红的 ⇒ 没有信息量
> ⇒ 所以必须「**目标红 ＋ 对照绿**」同时成立。

| ID | 变异 | 应转红 |
|---|---|---|
| **M-P1** | `GetTaskReq.USER_ID_INVALID` 的文案改一个字 | 7 条 `err-*` |
| **M-P2** | `TaskDefs` 里 taskId=190 的 `RequiredProgress` 3 → 4 | 10 条 `ok-*` |
| **M-P3** | `TaskRedisKeys.userProgressCache` 的 key 前缀改掉 | 7 条 Redis 命中用例（＋ M2 状态面） |

### 6.2 🧪 gRPC 面变异（`mutate-grpc-parity.sh`）—— **多一条判据**

gRPC 面的变异要求**四件事**同时成立：

1. **gRPC 目标断言转红** —— 探针 `EXIT != 0`，**且**指定用例在 **gRPC 明细**里变成 `DIFF`；
2. **gRPC 对照断言仍绿** —— 其它用例仍是 `SAME`；
3. 🧪 **HTTP 面必须「看不见」这条变异** —— HTTP 明细里的 `DIFF` 条数**不变**
   ⇒ ⇒ **证明 gRPC 面抓到了 HTTP 面抓不到的东西**（⇒ gRPC 面**不是** HTTP 面的重复）；
4. **还原后回绿** —— 还原源码、重建、重跑 ⇒ `EXIT == 0`。

| ID | 变异 | 应转红（gRPC 面） | HTTP 面应「看不见」 |
|---|---|---|---|
| **M-P4** | `TaskGrpcService#invoke` 的 `StatusRuntimeException` 透传 ⇒ 抹成 `UNKNOWN` | `err-zero` · `err-neg`（2 条 DIFF ＋ 5 条 SAME） | ✅ HTTP 明细 `DIFF` 条数**仍是 1**（`err-empty-body`）⇒ **完全看不见** |

---

## 七、文件清单

| 文件 | 说明 |
|---|---|
| `run.sh` | 主编排（构建 → 起容器 → 起进程 → 播种 → **HTTP 面** → **gRPC 面** → 状态面 → 收尾） |
| `http_probe.py` | 裸 socket 字节探针（`probe` 单发 / `compare` 比对） |
| `cases.txt` | 21 条用例表（制表符分隔） |
| `expected.txt` | **冻结锚**：B 侧逐条读数（**oracle**） |
| `known-cases.txt` | 已登记的表达层差异清单（手维护） |
| `known-readings.txt` | 已登记差异的 C 侧**形状**（`FREEZE=1` 生成） |
| `grpc_probe/GrpcProbe.java` | 🧪 **gRPC 面探针**（真 netty client）—— 🔴 **必须用 `forAddress(SocketAddress)`**（`SocketAddress` 重载**不经过名字解析** ⇒ 免疫 devh 的 discovery resolver 全局劫持 ⇒ 否则报极具迷惑性的 `UNAVAILABLE: No servers found for …`） |
| `grpc_probe.py` | gRPC 面的驱动（`build` 解 classpath + 编译 / `probe` 单侧 / `compare` 比对） |
| `grpc_cases.txt` | **7 条** gRPC 用例表（`uid` 与 HTTP 面**物理错开**：2001~ vs 1001~） |
| `grpc_expected.txt` | **gRPC 面的冻结锚**（B 侧逐条读数） |
| `grpc_known-cases.txt` | gRPC 面已登记差异清单（**当前 0 条** ⇒ 两侧逐字节全等） |
| `grpc_known-readings.txt` | gRPC 面已登记差异的 C 侧**形状**（当前为空） |
| `judge_report.py` | 变异判定器（目标红 / 对照绿）—— **两面共用**（明细格式相同）<br>⚠️ **认得 `INVALID` 档**（oracle 无效读数）⇒ 遇到它 ⇒ 判「本轮判据不成立」⇒ **需重跑**（不是「目标没转红」） |
| `mutate-parity.sh` | **HTTP 面**变异验证编排（M-P1~M-P3） |
| `mutate-grpc-parity.sh` | **gRPC 面**变异验证编排（M-P4 · 多一条「HTTP 面看不见」判据） |
| `test_invalid_tier.py` | 🧪 **「oracle 无效读数」档的离线判据测试** —— 假服务器造出 **8 种情形**（3/1/0/1/1/3/3/1）⇒ ⇒ 这一档**可离线复现**，不依赖「真机恰好抖一次」<br>⚠️ **夹具不手抄期望值** ⇒ 期望形状**从活体假服务器反推**（`probe()` ⇒ `render()`）⇒ ⇒ 不会与被测逻辑的渲染格式漂移 |
| `mutate-task.py` | ⚠️ **另一个脚本** —— 那是**单元测试层**的变异验证（M-1~M-6，`tools/difftest/task/`），与本探针**互不重叠** |
