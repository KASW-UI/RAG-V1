# like 域 **双侧活体对拍**探针（C 侧 RAG-V1 ↔ B 侧 RAG）

> **落点理由**：`tools/difftest/like/` 是 **like 域私有**目录（只被 like 域引用
> ⇒ 按 AGENTS.md §五.3f「共享判据 = 依赖度数，不是目录位置」⇒ **不属共享层** ⇒ like lane 可写）。
>
> **它填的坑**：本仓 `docs/DRIFT/like/verification-like-2026-11-03.md` §七·7.1 的
> **3 项验证缺口**里的第 1 项（**双侧活体对拍**）与第 3 项（**并发 / 超时 / 依赖失败 / 恢复**四类场景轮）
> —— 在本探针跑通之前，这两项**不能**从「未闭合」升级成「已闭环」。

## 改动归属（**谁改了什么**）

| 改动 | 归属 | 说明 |
|---|---|---|
| `tools/difftest/like/**`（`run.sh` · `expected.txt` · `mint-jwt.py` · `mutate-parity.sh` · `grpc-wire/LikeWireProbe.java` · `README.md`） | **本探针 lane** | 本 lane 的全部产出 |
| `tools/difftest/run-parity.sh`（`DOMAINS` 加 `like` ＋ usage 文案） | **本探针 lane** | 只加登记行，**不改派发逻辑** |
| `service/like/pom.xml`（去掉 `grpc-inprocess` 的 `<scope>test</scope>` ＋ 根因注释） | 🔴 **主 lane**（2026-11-03） | 本探针**没有 `service/**` 的写权**；该修复由主 lane 做（依据 = 本探针实测报出的「真 jar 起不来」） |

> ⚠️ `mutate-parity.sh` 会**临时**改动 C 侧的 `contract/ErrMsg.java` 与
> `dto/count/GetLikeCountResp.java`（变异验证的靶子）⇒ **退出前必还原**（trap + 文件备份），
> ⇒ 净改动为 **0** ⇒ 不属「本 lane 改了 `service/**`」。

## 目的

把「**两侧同时起服务 + 两侧各自播种同一批数据 + 同一请求 + 比对字节**」搬进版本控制，
并把**期望值冻结**进 `expected.txt` ⇒ 判定条件是 **C == B == 冻结值** 三方相等
（期望值本身参与判定，不是摆设）。

> **手法出处**：B 侧已有一套同族探针（`tools/api-full-parity-probe/like/run.sh`，1102 行）
> 与本仓的 follow 域探针（`tools/difftest/follow/run.sh`，1004 行）
> ⇒ 本探针**沿用其骨架**，但**被测对象从「Go 真源 ↔ B 侧」换成「B 侧 ↔ C 侧」**。

## 三方是哪三方

```
                        ┌──────────── B 侧（RAG / Boot 4.0.8）────────────┐
 客户端 ──HTTP──▶ :18211│ like-api ──gRPC(Nacos DEFAULT_GROUP)──▶ like-rpc :18212
                        │                    │                        │
                        │                    └──▶ message-rpc :8086（Nacos E5B）
                        └──────────────┬───────────────────────────────┘
                                       │
                        ┌──────────────┴───────────────────────────────┐
                        └──────────── C 侧（RAG-V1 / Boot 3.5.11）─────┘
 客户端 ──HTTP──▶ :18214│ like-service（D1 合并进程：HTTP :18214 + gRPC :18215）
                        │                    └──▶ message-rpc :8086（Nacos E5C）

 第三方锚 = expected.txt = **Go 真源的冻结字节**（B 侧 2026-10-29 录的 9 条）
```

| 侧 | 进程 | 端口 | 服务发现 | 库 |
|---|---|---|---|---|
| **B** | `like-api` | 18211 | —（discovery group = DEFAULT_GROUP） | — |
| **B** | `like-rpc` | 18212 | 注册 **DEFAULT_GROUP**（代码硬编码） | PG :18216 + Redis :18218 |
| **C** | `like-service`（D1 合并） | 18214 (HTTP) / 18215 (gRPC) | 注册 **E5C** | PG :18217 + Redis :18219 |
| **共用下游** | `message-rpc`（宿主 `Sea-message-rpc`） | 8086 | 手工注册 **E5B** + **E5C** | — |

### 🔴 为什么**不跑 Go 真源**（与 B 侧探针的唯一结构性差异）

B 侧那套探针起的是 **Go 真源**（服务发现走 **etcd**）。
⇒ **本机没有 `Sea-etcd` 容器**（Nacos / PG / Redis / Jaeger / Kafka 在跑，etcd 不在）
⇒ Go 侧**无法活体** ⇒ 改用 B 侧**已冻结**的 `expected.txt`（Go 字节）作第三方锚。

⇒ **三方等价的强度不降**：本探针判 **C == B == Go 字节**，
与 B 侧原探针判 **Go == B == Go 字节** 在信息量上**等价**
（都是「C 侧的每个字节 == 真源的每个字节」，B 侧只是中间的一层共识）。

### 🔴 探针的**证据边界**（必须连着读）

- ✅ 它证：**C 侧此刻产出的字节 == B 侧此刻产出的字节 == 2026-10-29 冻结下来的 Go 字节**。
- ❌ 它**不证**：「Go 此刻真的产出同样的字节」—— Go 侧**不可活体**（无 etcd）。
  ⇒ 冻结锚只能证明「与**当时**的 Go 一致」；Go 侧之后若变了，本探针**看不见**。
  ⇒ 这条边界必须连着「三方一致」的结论一起引用。

## 🔴 为什么**必须用 Nacos 分组**（不是多此一举）

B 侧与 C 侧**都**把 gRPC 服务注册成 `like-rpc` 这个服务名
（B 侧 `like-rpc/bootstrap/NacosRegistration.java` · C 侧 `wiring/NacosRegistration.java`）。
⇒ **同组 ⇒ C 侧的实例会进 B 侧 `like-api` 的解析池** ⇒ 请求随机打到对面
⇒ 表现为「毫无规律的字节差异」⇒ **假漂移**。⇒ 两侧分属不同组。

⚠️ **B 侧的分组是「半隔离」，这是 B 侧代码的既成事实**（探针照单全收，**不改 B 侧任何文件**）：

| 事实 | 出处 | 后果 |
|---|---|---|
| B 侧 `like-rpc` 的注册组**硬编码 `DEFAULT_GROUP`** | `service/like/like-rpc/.../bootstrap/NacosRegistration.java:87` | B 侧 `like-rpc` **永远**落在 DEFAULT_GROUP |
| B 侧 `like-api` 的 yml **没有 `group:` 键** | `service/like/like-api/src/main/resources/application.yml:52-58` | 它的 discovery group = DEFAULT_GROUP ⇒ **两者天然对上** |
| B 侧 `like-rpc` 的**出向客户端** group = `${NACOS_GROUP:DEFAULT_GROUP}` | `service/like/like-rpc/src/main/resources/application.yml:224` | 探针给 B 侧传 `NACOS_GROUP=E5B` ⇒ `message-rpc` 的查找落在 E5B |
| C 侧一切（注册 + 查找）都在 `NACOS_GROUP` | `service/like/src/main/resources/application.yml:186` + `wiring/NacosRegistration.java:63` | C 侧落在 E5C ⇒ **与 B 侧完全隔离** |

⇒ 探针**手工注册** `message-rpc` 的 **127.0.0.1:8086** 实例（**两组各一个**）：
`Sea-message-rpc` 自注册的是 **172.19.0.9:8086**（容器 IP）⇒ **Docker Desktop(macOS) 的宿主不可达容器 IP**
⇒ 不手工注册的话，两侧的 `message-rpc` 客户端会**连不上**（B 侧 follow 域踩过：
`IllegalStateException: Can't connect to channel user-rpc` ⇒ 进程起不来）。
⇒ 注册用 **ephemeral=true + 5s 保活循环**（临时实例靠心跳；persistent 走 Raft ⇒ 本机 Nacos 无 leader ⇒ 注册不进去）。

## 用法

```bash
bash tools/difftest/like/run.sh          # EXIT=0 = C == B == 冻结期望值 逐字节一致
bash tools/difftest/like/mutate-parity.sh # 变异验证（M1~M6 ⇒ 必须按预期表现）
bash tools/difftest/run-parity.sh like    # 派发入口
```

退出码：`0` = 三方逐字节一致 · `1` = 有真差异 · `2` = 环境问题 / 依赖缺失 / 真源漂移。

可用 env 覆盖：`B_REPO`（B 侧仓路径，默认 `/Users/pr/Documents/java&golang/RAG`）·
`NACOS_PORT` / `JAEGER_PORT` / `KAFKA_PORT` / `MESSAGE_RPC_PORT` ·
`B_API_PORT` / `B_RPC_PORT` / `B_RPC_MGMT_PORT` / `C_HTTP_PORT` / `C_GRPC_PORT` ·
`B_PG_PORT` / `C_PG_PORT` / `B_REDIS_PORT` / `C_REDIS_PORT` ·
`B_GROUP` / `C_GROUP` · `JWT_SECRET` · `KEEP_WORK=1`（保留临时目录与三份进程日志）。

## 阶段清单与判据（13 段）

| 段 | 内容 | 判据 |
|---|---|---|
| 0 | 环境自检 | 命令齐 / Sea 栈在跑 / **B 仓 `service/like` 干净** ⇒ 否则 **exit 2** |
| 1 | 两侧 Postgres + Redis（**4 个容器**） | 宿主端口真的可连（`nc -z`，不是「容器内 socket 通」） |
| 2 | 构建两侧（B 侧**只读**） | 两个 jar 产出 + **B 仓源码仍然干净** + 取 C 侧 classpath |
| 3 | 签 JWT | `userId` 必须是**数字**（字符串 ⇒ 两侧都 1006） |
| 4 | Nacos 分组注册 | `message-rpc` 在 **E5B 与 E5C** 都能查到（`ephemeral` + 保活） |
| 5 | 起两侧 | B `like-rpc`(:18212) → B `like-api`(:18211) → C `like-service`(:18214/:18215)；**C 必须注册进 E5C** |
| 6 | 两侧播种 | Redis 6 键 + PG 1 行（**等 Flyway 建完表**才播） |
| 7 | **阶段 A · HTTP 业务面** | 8 条用例 **C == B == 冻结的 Go 字节**（逐字节） |
| 8 | **阶段 B · gRPC wire** | 6 个 rpc 方法 **C == B** 原始字节（**双边**，无 Go 锚） |
| 9 | **阶段 C · E60 双失败** | 响应 == 锚（`like_count=4`）**且** 两侧都记了 `business_error` |
| 10 | **阶段 D · 恢复** | 撤注入 ⇒ 两侧**连续 3 轮**回 200（稳定态）⇒ 再比逐字节 |
| 11 | **阶段 E · 并发** | 两侧各 8 路并发 `likeaction` ⇒ 业务码集合相同 **且** 最终计数（HTTP + Redis）相同 |
| 12 | **阶段 F · 超时** | 两侧 Redis 容器 `docker pause` ⇒ **场景生效断言** + 两侧都**没挂死** + 逐条记录码值/耗时/分档 |
| 13 | 收尾复核 | 服务真停 + 容器删掉 + **9 个端口零残留** |

### 三方判定（8 条 · C == B == 冻结的 Go 字节）

| 组 | 条数 | 用例 |
|---|---|---|
| 读路径 | 5 | `getlikecount` / `getlikestate` / `gettargetlikerlist` / `getuserlikelist` / `getusertotallike` |
| 写路径 | 1 | `likeaction`（`author_id="0"` ⇒ 不走 notify 分支） |
| 协议层 | 2 | `no_token`（401 + 空 body） / `missing_required`（200 + `{code:5001,msg:"系统内部错误",data:null}`） |

### 双边判定（1 条 · C == B，**无 Go 锚**）

| 用例 | 判据 | 说明 |
|---|---|---|
| `likeaction_double_failure` | 响应 == 锚 **且** 两侧都记了 `business_error` | 冻结锚里含这一条，但它是 **E60 场景**的读数（依赖注入），**不是**普通读路径 |

### gRPC wire 层判定（6 条 · **双边**）

| rpc | 用例名 |
|---|---|
| `GetLikeCount` | `wire_get_like_count` |
| `GetLikeState` | `wire_get_like_state` |
| `GetUserLikeList` | `wire_get_user_like_list` |
| `GetTargetLikerList` | `wire_get_target_liker_list` |
| `GetUserTotalLike` | `wire_get_user_total_like` |
| `LikeAction` | `wire_like_action` |

> ⚠️ **探针不用任何 pb 生成码**（手构 proto3 字节 ＋ `byte[]` marshaller）⇒ **同一个 jar 跑两侧**
> ⇒ 比的是**纯 wire 字节**，不被「两侧 pb 包名不同」污染（proto3 零值不编码 ⇒ 构造时必须省掉零值字段）。
> ⚠️ **必须用 `NettyChannelBuilder.forAddress(new InetSocketAddress(host, port))`**：
> devh 的 `DiscoveryClientResolverFactory.priority()=6`（> DNS 的 5）会把自己注册进**全局**
> `NameResolverRegistry` 并抢走默认 scheme ⇒ `forAddress(String,int)` 的目标被拼成
> `discovery:///host:port` ⇒ 去 Nacos 查「host」这个服务 ⇒ 零实例
> ⇒ 客户端报 `UNAVAILABLE: No servers found for host:port` ⇒ **长得像「服务端没起来」，极易误判**。
> （本仓已有同款根因注释：`service/like/src/test/java/com/rag/like/grpc/LikeGrpcNettyServerRealTest.java`）

## 播种（**两侧各自的 Redis / PG 各一份**）

| 位置 | 键 / 行 | 值 | 用途 |
|---|---|---|---|
| Redis | `like_count:article` | `9007199254740994 → 2` | `getlikecount` |
| Redis | `dislike_count:article` | `9007199254740994 → 1` | `getlikecount` |
| Redis | `like_state:article:9007199254740994` | `1001 → 1` | `getlikestate` |
| Redis | `target_liker_list:article:9007199254740994` | `ZADD 1735787045000 1001` | `gettargetlikerlist` |
| Redis | `user_like_list:1001:article` | `ZADD 1735787045000 9007199254740994` | `getuserlikelist` |
| Redis | `user_total_like:1001` | `5` | `getusertotallike` |
| PG | `like_record` | `(1001, article, 9007199254740994, author 1002, state 1, last_operation_id 0, 2026-01-02 03:04:05+00)` | 双失败注入的删除对象 / 回源分支 |

> ⚠️ **ZSet 的 score 必须是「裸毫秒」，不是游标 ID**：
> 读路径把 score 反解成毫秒 = `score >> 22`
> （Go `getuserlikelistlogic.go:70` / `gettargetlikerlistlogic.go:77`；C 侧 `LikeCursorIdGenerator#timestampOf` 同款）
> ⇒ `1735787045000 >> 22` = **413843** ⇒ 正是冻结锚里那个数。
> ⇒ 若改种成「游标 ID 形态的 score」，列表里的 timestamp 会变成 1735787045000 ⇒ 与锚不符。
>
> ⚠️ **绝不拿空库当证据**：「两侧都空」分不出「相同」与「都空」
> ⇒ 必须播种同一批数据（`mutate-parity.sh` 的 **M3** 专门盯这一条：让 C 侧多一个成员 ⇒ 探针必须转红）。

## 🔴 三个 target 的分工（**别混用**）

| target | 用途 | 为什么必须分开 |
|---|---|---|
| `9007199254740994` | 阶段 A 的 8 条用例 + E60 + 恢复轮 | 冻结锚的基准（`likeaction` ⇒ `like_count=3`；`likeaction_double_failure` ⇒ `4`） |
| `9007199254740995` | **wire 读路径**（`wire_get_target_liker_list`） | `likeaction` 往 ZSet 里写的 score = **游标 ID**（`(now << 22) | …`）⇒ 读出的 timestamp = **墙钟毫秒** ⇒ 两侧的 likeaction 发生在**不同时刻** ⇒ 毫秒不同 ⇒ **wire 字节必然不同** ⇒ **与对拍无关的假红** ⇒ 用一个「只有种子成员」的专属 target ⇒ timestamp 恒为 413843 |
| `9007199254740996` | **wire 写路径**（`wire_like_action`） | 全新 target ⇒ `like_count` 恒为 1（确定性） |
| `9007199254740997` | **并发轮** | 并发写主 target 会把冻结锚的计数打乱 |

## 覆盖边界（**不得据此声称「like 域已完全闭环」**）

1. **`notifyArticleLiked` 分支不在比较面内** ——
   `likeaction` 的 `author_id` 必须取 `"0"`：Go 的 `likeactionlogic.go:151-162` 在
   `AuthorId > 0 && UserId != AuthorId` 时会调 `notifyArticleLiked` ⇒ 走 **message.rpc**
   ⇒ 两侧耗时不可比（Go 等 2s、RAG 立刻失败）⇒ 只比较**可比**的字节。
   ⇒ **代价**：作者 ≠ 点赞者 时的通知链路**完全没被本探针验过**。
2. **MQ 消费路径不在比较面内** —— 两侧都用
   `SPRING_KAFKA_LISTENER_AUTO_STARTUP=false` 关掉 `@KafkaListener`
   ⇒ 被排除的是「消费 `like_promotion_topic` → 写 `like_record`」那一段
   （两侧**对称**地关 ⇒ 不构成任何一侧的优待）。
   ⇒ 后果：`likeaction` 的**落库**路径（Go 侧在 MQ 消费侧写 PG）**未被验证**。
3. **事实投递链路（fact-dispatch / DC）不在比较面内** ——
   B 侧探针的阶段 B（投给假 DC 的字节）在 B 仓，本探针**不做**。
4. **gRPC wire 层没有 Go 锚** ⇒ 读作「**双侧一致**」，**不计入「三方一致」**。
5. **`likeaction_double_failure` 依赖注入** ⇒ 它是**场景**的读数，不是普通读路径的读数。
6. **两侧的 DB 初始条件不完全同形**：B 侧 `like-rpc` 与 C 侧 `like-service` 各自独占一个空库
   ⇒ 两侧的 Flyway 走的是同一条「空 schema + baseline-version 0」路径（**账本表名相同**
   `flyway_schema_history_like` ⇒ **必须物理隔离，否则撞号**）。
7. **指标面（Prometheus）未覆盖** —— `/actuator/prometheus` 的桶边界与指标名
   仍由 C 侧自己的域内测试守着。
8. **`getusertotallike` 的 DB 回源分支未覆盖** —— 种子把 `user_total_like:1001` 播成 5
   ⇒ 走的是**缓存命中**分支；回源分支（`countActiveLikeByAuthor`）只有 E60 注入期间被碰到。
9. **超时阶段的「失败形态」不做字节判据**（见下节）。

## 已知现象 / 已知偏离清单

### ⚠️ 已知现象 1 · 超时场景两侧失败形态不同（**不计失败**，但必须逐字引用）

| 侧 | HTTP | 业务码 | 耗时 | body |
|---|---|---|---|---|
| **C** | **503** | 无信封 | **≈3.0s**（分档 `3~10s`） | `Request Timeout` |
| **B** | **200** | **1015** | **≈2.0s**（分档 `1~3s`） | `{"code":1015,"msg":"CallOptions deadline exceeded after 1.999…s…","data":null}` |

> ⚠️ 上表是**实测样例**（2026-11-03 一轮：C 3056ms / B 2065ms）⇒ **毫秒数每轮都会变**
> ⇒ 引用时以**探针当轮输出**为准。

**根因（拓扑产物，不是漂移）**：
B 侧是 **api → rpc 两跳** ⇒ `like-api` 的 **2s 客户端预算**先到 ⇒ 折叠成 1015；
C 侧是 **D2 域内直连**（没有那一跳）⇒ Redis 的 **3s 命令超时**先到 ⇒ 共享层的 3s HTTP 预算再兜一层 ⇒ 503。

⇒ 因此**阶段 F 的判据只钉两条**：① **场景确实生效**（`docker inspect` 确认 `Paused=true`）
② **两侧都没有挂死**（都回了响应；`curl` 000 ⇒ **真差异**，计失败）。
⇒ 码值与耗时的差异**逐条记录并分档**，**不判失败**（**先定判据，再看结果**）。

### ✅ 已修缺陷 1 · C 侧 fat jar 曾**起不来**（**本轮已修 · 主 lane 改 `service/like/pom.xml`**）

`service/like/pom.xml` 曾把 `io.grpc:grpc-inprocess` 声明成 **`test` scope**
⇒ Maven「最近者优先」压过 devh starter 的传递 compile 依赖
⇒ **运行时 classpath 上整个 artifact 消失**（fat jar 的 `BOOT-INF/lib` 里也没有它）
⇒ devh 的 `shadedNettyGrpcChannelFactory` 建 channel 时链接
`io.grpc.inprocess.InProcessChannelBuilder` ⇒ **`NoClassDefFoundError`** ⇒ **真 jar 起不来**。

- **B 出处（基准）**：B 侧 `service/like/like-rpc/pom.xml:313-344`
  白纸黑字写着「🔴 **scope 必须是 compile（默认），不能是 test**」（2026-10-26 真启动实测修正）
  ⇒ 按 D16「B 侧是基准」，这是 **C 侧的漂移 + 真实缺陷**（不是「框架差异」）。
  ⇒ follow 域 2026-11-02 已修过同款（`service/follow/pom.xml` 的注释块 + follow 探针 README 的 #20）。
- **本仓证据**：`service/like/src/test/java/com/rag/like/grpc/LikeGrpcNettyServerRealTest.java`。
- **修法（主 lane，2026-11-03）**：去掉 `<scope>test</scope>`（版本由根 pom 的 `dependencyManagement` 管）
  ＋ 补上与 B 侧同款的根因注释（**B 出处已标**）。
  ⇒ **本探针不写 `service/**`** ⇒ 该修复**不是**本探针做的。
- **教训（保留）**：**测试跑在 test classpath 上 ⇒ 依赖本来就在 ⇒ 缺陷看不见**
  ⇒ 「**跑绿 ≠ 本来就绿**」（AGENTS.md §3.3.3 的教科书形态）
  ⇒ 只有**把 fat jar 真跑起来**才会炸。
- **探针现状**：正常路径下 `C_JAR_HAS_INPROCESS=1` ⇒ **直接 `java -jar` 起 C 侧**。
  探针里仍保留 `PropertiesLauncher + -Dloader.path` 的**兜底分支**（当保险）
  ⇒ **若兜底命中 ⇒ 说明 pom 的修法回退了** ⇒ 探针会显式告警（收尾摘要里也会点名）。

### 🔴 已登记批准的偏离

本探针的 8 条三方用例**没有**一条落在「C == B 但 C ≠ Go 锚」这一档
⇒ 「已登记批准的偏离」计数恒为 **0**。
（若将来某条变成「C == B ≠ Go 锚」，那**不是 C 侧的漂移**，而是 B 侧与 Go 的差异
⇒ 必须查 B 仓 `docs/DRIFT/like/api.md` 是否已登记 ⇒ 登记后才可放进这一档，**不许直接判失败**。）

## 变异验证（`mutate-parity.sh`）

AGENTS.md §3.3.3：新增的每个探针都必须配一个**能真正触发它**的变异，否则无法排除「假绿」。

| 变异 | 手法 | 期望 |
|---|---|---|
| **M1** | 改 **C 侧** `contract/ErrMsg.java` 的 5001 文案 | 探针 **EXIT=1**，且**只有** `missing_required` 变红（其余 7 条仍绿） |
| **M2** | 改 `expected.txt` 里一枚 hex | 探针 **EXIT=1**，且 `getlikecount` 与冻结值不符（**期望值确实在参与判定**） |
| **M3** | 让 **C 侧**多播一个 ZSet 成员（改探针的播种） | 探针 **EXIT=1**，且 `gettargetlikerlist` 与 B 不等（**探针真在读两侧各自的库**） |
| **M4** | C 侧在矩阵**中途挂掉**（`PROBE_STOP_C_AFTER`） | 探针 **EXIT=1**，且下一条报「**请求失败**」⇒ **不会**沿用上一次的残留 body |
| **M5** | 改 **C 侧** `GetLikeCountResp` 的 JSON 字段名（`counts` → `counts_x`） | 探针 **EXIT=1**，且**只有** `getlikecount` 变红（其余维仍绿） |
| **M6** | 把探针的 **E60 注入**去掉（`ACL SETUSER -zcard` → `ACL LIST`） | 探针 **EXIT=1**，且「**E60 业务错误分支**」断言变红（两侧都不再记 `business_error`）⇒ 「E60」这条判据**不是空洞断言** |

> ⚠️ **M6 的一处方法学**（实测踩出来的）：**E60 的「响应字节」在注入前后是相同的**
> （`likeaction` 照常 +1 ⇒ 计数照常 +1 ⇒ 响应仍是锚里那个数）
> ⇒ **敏感的是「业务错误分支」这条断言**，不是响应字节
> ⇒ 若把判据押在响应字节上 ⇒ 这条变异**永远不会红** ⇒ 那就是**空洞判据的教科书形态**。
> ⇒ 同理，B 侧探针的阶段 C 也是**两段断言**（响应字节 + 业务错误日志），缺一不可。

> ⚠️ **每个变异都断言「只有那一维变红、其余维仍绿」** ⇒ 变异**只**影响一维。
> ⚠️ **不用「变异没变红 = 没覆盖到」这种反向推断当证据**：每条都要求 **EXIT=1 + 目标用例判红 + 对照用例仍绿** 三件事同时成立。
> ⚠️ **还原一律用「文件备份 + `cp` 回来」**（绝不用 `git checkout --`：本 lane 的改动可能还没提交）
> ⇒ 且脚本 **trap 里还原全部靶点** ⇒ 探针被杀掉也不会把变异留在仓里。
> ⚠️ **启动前自检**四个靶点是否处于**原始形态** ⇒ 发现残留 ⇒ **拒绝启动**（宁可拒绝跑，也不要用被污染的读数下结论）。

## 探针本身踩过的坑（写下来免得下一个人再踩一遍）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 🔴 **C 侧真 jar 起不来**：`NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder` | `service/like/pom.xml` 曾把 `grpc-inprocess` 写成 `test` scope ⇒ 运行时 classpath 缺 artifact | **主 lane 已修**（去掉 `<scope>test</scope>` · B 出处 = B 侧 `like-rpc/pom.xml:313-344`）⇒ 探针现在**直接 `java -jar` 起**；探针保留 `PropertiesLauncher + -Dloader.path` **兜底分支** ⇒ **命中即说明修法回退** |
| 2 | 🔴 **wire 层「字节不一致」但两边看起来都对** | `likeaction` 写的 score 是**游标 ID**（`(now << 22) | …`）⇒ 读出的 timestamp = **墙钟毫秒** ⇒ 两侧差几十毫秒 | wire 读路径改用**专属 target**（只播 ZSet 一个键）⇒ timestamp 恒为 413843 |
| 3 | 🔴 **收尾报「端口仍有残留」** | 容器发布端口时是 **docker-proxy** 持有 ⇒ `docker rm -f` 之后 proxy 还会 linger 几秒 | 收尾**先删容器**、再**轮询**端口（≤20s）⇒ 不删就查 ⇒ **恒红的假信号** |
| 4 | 🔴 **B 侧 `like-api` 找不到下游** | B 侧 `like-rpc` 注册组**硬编码 `DEFAULT_GROUP`**，而 `like-api` 的 yml **没有 `group:` 键** ⇒ 两者天然对上 ⇒ 探针**不能**改 | 探针按事实走：B 侧 like-rpc 注册进 DEFAULT_GROUP，`message-rpc` 的**客户端**查找走 E5B |
| 5 | 🔴 **两侧 `likeaction` 的计数永远不同** | 两侧**共用一个 Redis** ⇒ B 侧写完计数变 3、C 侧再写变 4 | 两侧**各起一个 Redis 容器**（18218 / 18219）⇒ 物理隔离 |
| 6 | 🔴 **`message-rpc` 客户端连不上 ⇒ 进程起不来** | `Sea-message-rpc` 自注册的是 **容器 IP**（172.19.0.9）⇒ Docker Desktop(macOS) 宿主**不可达容器 IP** | 探针手工注册 **127.0.0.1:8086**（**两组各一个**）+ **ephemeral + 5s 保活循环** |
| 7 | 🔴 **`userId` claim 签成字符串 ⇒ 两侧都 1006** | like 域的 `userId` **只接受整型**（B 侧 `LikeApiSupport#userId` / Go 的 `json.Number`） | `mint-jwt.py` **签数字**（follow 域那份签的是字符串 ⇒ **在本域会直接 1006** ⇒ 不能照抄） |
| 8 | ⚠️ **两侧的 Kafka 消费互相串味** | 两侧都消费 `like_promotion_topic`（宿主 Kafka 是**共用**的 Sea-kafka） | 两侧**对称**地 `SPRING_KAFKA_LISTENER_AUTO_STARTUP=false` ⇒ 明确排除 MQ 消费路径 |
| 9 | ⚠️ **`rm` 打印污染 stdout** | 本机的 `rm` 被回收站守卫接管且**往 stdout 打印** | 一律不删文件；落盘都在 `${WORK}`（`mktemp -d`）里 |
| 10 | ⚠️ **收尾的「Terminated / Killed」噪声** | bash 回收后台任务时打印状态 | 启动后台进程后 `disown %%` ⇒ 输出干净 |
| 11 | 🔴 **「C 侧 fat jar 缺 grpc-inprocess」误报**（明明 jar 里有） | `unzip -l … \| grep -q …`：`grep -q` 命中即退出 ⇒ `unzip` 收 **SIGPIPE** ⇒ 本脚本开了 **`pipefail`** ⇒ 整条管道退出码变 **141** ⇒ `&& VAR=1` **不执行** ⇒ 断言**假阴性** | 改成「**先抓进变量再模式匹配**」（无管道 ⇒ 无 SIGPIPE）⇒ 同一条纪律顺手修掉了 `nacos_assert_instance` 与两处 **B 仓干净守卫**的同类隐患（守卫假阴性 ⇒ `die` 不会触发 ⇒ **最危险的一类静默失效**） |
| 12 | ⚠️ **告警文案里的一段文字凭空消失** | 双引号里的 **反引号 = 命令替换** ⇒ `` `service/like/pom.xml` `` 被当成命令执行 ⇒ 输出被替换成空 | 双引号里**不要用反引号**（用「」或去掉） |

## 真源冻结 ⇒ **B 侧只读**

本探针只在 B 仓跑 `mvn package`（产物落 `target/`，已被 `.gitignore` 排除）
⇒ **不碰 B 侧任何源码 / 配置**。第 0 步与第 2 步都会**断言 B 仓 `git status` 干净**
（`service/like` / `service/common`）⇒ 否则 `die`（EXIT=2）。

> ⚠️ **探针自己也不改 B 侧的分组配置**（即使 B 侧的 `DEFAULT_GROUP` 硬编码看着别扭）
> ⇒ 「B 侧的分组是半隔离」是**被记录的事实**，不是探针要修的东西。
