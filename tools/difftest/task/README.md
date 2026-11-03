# task 域 —— 双侧活体对拍探针（B 侧 RAG ↔ C 侧 RAG-V1）

> **被测两侧（同时起、同时活、同请求、同字节）**
> · **B 侧** = `/Users/pr/Documents/java&golang/RAG/service/task/{task-api,task-rpc}`（Boot **4.0.8**）
> 　　⚠️ B 侧是 **两个进程**（api = HTTP 壳 · rpc = gRPC 业务）⇒ 探针要起 **2 个** jar；
> · **C 侧** = 本仓 `service/task`（Boot **3.5.11** · **D1 合并进程**：HTTP + gRPC 同进程）⇒ 1 个 jar。

---

## 一、判定强度：E-4（双侧活体对拍），**不冒充「三方一致」**

本机**没有 Go 真源可活体** ⇒ 判定条件只有一条：**C == B（双侧活体逐字节相等）**。
⇒ 与 `tools/difftest/like/`、`tools/difftest/hot/` 同款口径。

> ⚠️ **B 侧只读**：探针只在 B 仓跑 `mvn package`（产物落 `target/`，已被 `.gitignore` 排除）
> ⇒ **不碰 B 侧任何源码 / 配置**（第 0 步断言 B 仓 `git status` 干净，**断言失败即中止**）。

---

## 二、四个对拍面

| 面 | 比什么 | 怎么比 |
|---|---|---|
| **M1 HTTP 字节面** | **原始状态行** ＋ `Content-Type` ＋ `X-Content-Type-Options` ＋ `Allow` ＋ **body 原始字节** | 裸 socket（**不用 curl** —— curl 只给状态码，**不给状态行 reason phrase**） |
| **M2 Redis 状态面** | 回填后的 `task:progress:<uid>` **hash 内容** ＋ **存在性** ＋ **TTL** | 两侧**物理隔离**的 Redis 容器；TTL 容忍 **≤10 s** 的测量抖动 |
| **M3 PG 状态面** | `task_progress` / `user_like_count` 的行 | 两侧**物理隔离**的 Postgres 容器（**库名也不同**）⇒ 读路径**不得**写库 |
| **M4 冻结锚面** | C 侧读数 vs `expected.txt`（**冻结的是 B 侧读数** ⇒ oracle 是 B 侧） | `expected.txt` 逐条比对 ⇒ **B 侧漂移**与 **C 侧回归**都会被抓到 |

### 2.1 刻意**不比**的字段（及原因）

| 不比 | 原因 |
|---|---|
| `Date` / `Server` | 每次请求都变 |
| `Content-Length` | 由 body 长度决定（body 已逐字节比）⇒ 比它是冗余 |
| `Connection` | 由连接复用策略决定（两侧框架版本不同 ⇒ 必然不同 ⇒ **假红**） |
| `Traceparent` | OTel 追踪 ID，**每次都变** |
| **1xx 行数** | 🔴 本仓共享层 `common/observability/ClientDisconnectProbe` 会**直接往 socket 写** `HTTP/1.1 103 Early Hints\r\n\r\n` 作断连探针 ⇒ **每个响应前可能有 1~N 行 1xx**（行数取决于 socket 写时机 ⇒ 两侧不必相同 ⇒ 不可比）。⇒ 探针按 **RFC 9110** 跳过所有 1xx，只取最终响应 |

---

## 三、三档判定（**白名单不是豁免**）

| 档 | 判定 | 何时用 |
|---|---|---|
| **普通用例** | B 与 C 的读数**必须逐字节相同**；且 C 与**冻结锚**必须相同 | 默认 |
| **已登记的表达层差异**（`known-cases.txt`） | ① C 的读数必须**逐字节等于** `known-readings.txt` 里登记的形状（⇒ **差异形状变了 ⇒ 失败**）；② B 的读数必须等于冻结锚（⇒ **B 侧漂移 ⇒ 失败**） | 差异**已登记为有意偏离**时 |
| **真差异** | 任何不满足上面两条的 ⇒ **失败** | — |

> ⇒ **净效果**：「允许不同」被钉成「**只允许差这一处、且必须还是当初那一处**」。

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

---

## 五、用法

```bash
bash tools/difftest/task/run.sh                # 对拍（同时校验冻结锚 + 已知差异白名单）
FREEZE=1 bash tools/difftest/task/run.sh       # 重新冻结 B 侧读数到 expected.txt
KEEP_WORK=1 bash tools/difftest/task/run.sh    # 保留临时目录与日志
SKIP_B_BUILD=1 bash tools/difftest/task/run.sh # B 侧产物不变时跳过 B 侧构建

bash tools/difftest/task/mutate-parity.sh      # 变异验证（证明判据有牙齿）
bash tools/difftest/task/mutate-parity.sh M-P1 # 只跑一条变异
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

---

## 七、文件清单

| 文件 | 说明 |
|---|---|
| `run.sh` | 主编排（构建 → 起容器 → 起进程 → 播种 → 比对 → 收尾） |
| `http_probe.py` | 裸 socket 字节探针（`probe` 单发 / `compare` 比对） |
| `cases.txt` | 21 条用例表（制表符分隔） |
| `expected.txt` | **冻结锚**：B 侧逐条读数（**oracle**） |
| `known-cases.txt` | 已登记的表达层差异清单（手维护） |
| `known-readings.txt` | 已登记差异的 C 侧**形状**（`FREEZE=1` 生成） |
| `judge_report.py` | 变异判定器（目标红 / 对照绿） |
| `mutate-parity.sh` | 变异验证编排 |
| `mutate-task.py` | ⚠️ **另一个脚本** —— 那是**单元测试层**的变异验证（M-1~M-5，`tools/difftest/task/`），与本探针**互不重叠** |
