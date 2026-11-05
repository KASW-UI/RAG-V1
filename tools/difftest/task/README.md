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
| **M2 Redis 状态面** | 回填后的 `task:progress:<uid>` **hash 内容** ＋ **存在性** ＋ **TTL** | 两侧**物理隔离**的 Redis 容器；TTL 容忍 **≤10 s** 的测量抖动 |
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

## 三、三档判定（**白名单不是豁免**）

| 档 | 判定 | 何时用 |
|---|---|---|
| **普通用例** | B 与 C 的读数**必须逐字节相同**；且 C 与**冻结锚**必须相同 | 默认 |
| **已登记的表达层差异**（`known-cases.txt`） | ① C 的读数必须**逐字节等于** `known-readings.txt` 里登记的形状（⇒ **差异形状变了 ⇒ 失败**）；② B 的读数必须等于冻结锚（⇒ **B 侧漂移 ⇒ 失败**） | 差异**已登记为有意偏离**时 |
| **真差异** | 任何不满足上面两条的 ⇒ **失败** | — |

> ⇒ **净效果**：「允许不同」被钉成「**只允许差这一处、且必须还是当初那一处**」。
> ⇒ **两面各有一套**白名单（HTTP：`known-cases.txt` ＋ `known-readings.txt` + `expected.txt`；
> gRPC：`grpc_known-cases.txt` ＋ `grpc_known-readings.txt` + `grpc_expected.txt`）⇒
> **口径完全一致**，只是**各钉各的面**。

### 3.1 已登记的表达层差异（**1 条**）

| 用例 | 差异 | 为什么**不修** |
|---|---|---|
| `err-empty-body` | Spring 6.2 的「缺 body」默认报文是 `Method.toString()` ⇒ 内嵌 DTO / Controller 的**类全限定名**：<br>B `com.rag.task.task.api.dto.GetTaskReq` ↔ C `com.rag.task.dto.task.GetTaskReq` | 成因是 **D7（域内 api+rpc 压平）＋ D11（dto 按业务对象拆子包）** —— **已锁定** ⇒ 要对齐就得把 C 侧包名改回 B 的形状 ⇒ **与 D7/D11 直接冲突**<br>⇒ **状态码 / Content-Type / nosniff / 报文结构全部逐字节相同**，**只有类名不同** ⇒ 表达层差异，非行为漂移<br>⇒ 台账：**DRIFT-TASK-016** |

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
```

**退出码**：`0` = 双侧逐字节一致 · `1` = 有真差异 · `2` = 环境问题 / 依赖缺失 / 真源漂移。

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
| `judge_report.py` | 变异判定器（目标红 / 对照绿）—— **两面共用**（明细格式相同） |
| `mutate-parity.sh` | **HTTP 面**变异验证编排（M-P1~M-P3） |
| `mutate-grpc-parity.sh` | **gRPC 面**变异验证编排（M-P4 · 多一条「HTTP 面看不见」判据） |
| `mutate-task.py` | ⚠️ **另一个脚本** —— 那是**单元测试层**的变异验证（M-1~M-6，`tools/difftest/task/`），与本探针**互不重叠** |
