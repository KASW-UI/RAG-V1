# follow 域 **E-4 双侧活体对拍**探针（C 侧 RAG-V1 ↔ B 侧 RAG）

> **落点理由**：`tools/difftest/follow/` 是 **follow 域私有**目录（只被 follow 域引用
> ⇒ 按 AGENTS.md §五.3f「共享判据 = 依赖度数，不是目录位置」⇒ **不属共享层** ⇒ follow lane 可写）。
>
> **它填的坑**：`.agents/verification.md` 第六阶段的 **E-4 双侧活体对拍**，
> 在本仓自实现完成前一直是 `BLOCKED` ⇒ 域台账 §11.6 里「51 个 B 侧类的整体一致性」
> 只能判 `NOT_VERIFIED`（§十：`NOT_VERIFIED` / `BLOCKED` **不能计入 PASS**）。

## 目的

把「**两侧同时起服务 + 两侧各自播种同一行 + 同一请求 + 比对字节**」搬进版本控制，
并把**期望值冻结**进 `expected.txt` ⇒ 判定条件是 **C == B == 冻结值** 三方相等
（期望值本身参与判定，不是摆设）。

> **手法出处**：B 侧已有一套同族探针（`tools/api-full-parity-probe/follow/run.sh`，710 行，
> 17 条已记录的坑）⇒ 本探针**沿用其设计**，但**被测对象从「Go 真源 ↔ B 侧」换成「B 侧 ↔ C 侧」**
> （见下节）。

## 三方是哪三方

```
                         ┌──────────── B 侧（RAG / Boot 4.0.8）────────────┐
  客户端 ──HTTP──▶ :18201│ follow-api ──gRPC(Nacos E4B)──▶ follow-rpc :18202
                         └──────────────┬───────────────────────────────┘
                                        │ 同一个 user-rpc 进程
                         ┌──────────────┴───────────────────────────────┐
                         └──────────── C 侧（RAG-V1 / Boot 3.5.11）─────┘
  客户端 ──HTTP──▶ :18204│ follow-service（D1 合并进程：HTTP + gRPC :18205）
                         └────────────────────────────────────────────┘

  第三方锚 = expected.txt = **Go 真源的冻结字节**（B 侧 2026-10-29 录的 20 条）
```

| 侧 | 进程 | 端口 | 服务发现 | 库 |
|---|---|---|---|---|
| **C** | `follow-service`（D1 合并） | 18204 (HTTP) / 18205 (gRPC) | Nacos `E4C` | PG :18207 |
| **B** | `follow-api` | 18201 | — | — |
| **B** | `follow-rpc` | 18202 | Nacos `E4B` | PG :18206 |
| **B** | `user-rpc`（**下游，两侧共用同一个**） | 18203 | Nacos `E4B` + `E4C` | PG :18206 |

### 🔴 为什么**不跑 Go 真源**（与 B 侧探针的唯一结构性差异）

B 侧那套探针起的是 **Go 真源**（服务发现走 **etcd**）。
⇒ **本机没有 `Sea-etcd` 容器**（Nacos / PG / Redis / Jaeger / Kafka 在跑，etcd 不在）
⇒ Go 侧**无法活体** ⇒ 改用 B 侧**已冻结**的 `expected.txt`（Go 字节）作第三方锚。

⇒ **三方等价的强度不降**：本探针判 **C == B == Go 字节**，
与 B 侧原探针判 **Go == B == Go 字节** 在信息量上**等价**
（都是「C 侧的每个字节 == 真源的每个字节」，B 侧只是中间的一层共识）。

### 🔴 为什么**必须用 Nacos 分组**（不是多此一举）

B 侧与 C 侧**都**把 gRPC 服务注册成 `follow-rpc` 这个服务名
（B 侧 `follow-rpc/bootstrap/NacosRegistration.java` · C 侧 `wiring/NacosRegistration.java`）。
⇒ **同组 ⇒ C 侧的实例会进 B 侧 `follow-api` 的解析池**
⇒ 请求随机打到对面 ⇒ 表现为「毫无规律的字节差异」⇒ **假漂移**。
⇒ 两侧分属 `E4B` / `E4C` 两个组；`user-rpc` **两个组都登记**（同一个进程、同一端口）
⇒ 两侧的下游**完全同源** ⇒ 任何「用户不存在 / 下游故障」的分歧都不可能来自下游不同。

## 用法

```bash
bash tools/difftest/follow/run.sh          # EXIT=0 = C == B == 冻结期望值 逐字节一致
bash tools/difftest/follow/mutate-parity.sh # 变异验证（M1~M7 ⇒ 必须按预期表现）
```

退出码：`0` = 三方逐字节一致 · `1` = 有差异 · `2` = 环境问题 / 依赖缺失 / 真源漂移。

可用 env 覆盖：`B_REPO`（B 侧仓路径，默认 `/Users/pr/Documents/java&golang/RAG`）·
`NACOS_PORT` / `REDIS_PORT` / `JAEGER_PORT` ·
`B_API_PORT` / `B_RPC_PORT` / `USER_RPC_PORT` / `C_HTTP_PORT` / `C_GRPC_PORT` ·
`B_PG_PORT` / `C_PG_PORT` · `B_GROUP` / `C_GROUP` · `JWT_SECRET` / `JWT_USER_ID` ·
`KEEP_WORK=1`（保留临时目录与四份进程日志）。

## 覆盖的用例（HTTP 21 条 ＋ gRPC wire 13 条 ＋ 场景轮 6 类）

### 三方判定（20 条 · C == B == 冻结的 Go 字节）

| 组 | 条数 | 用例 |
|---|---|---|
| 读路径（数据） | 9 | `follow_list_seeded` / `follower_list_seeded` / `block_list_seeded` / `follow_list_empty` / `recommendations_seeded` / `follow_list_limit_zero` / `follow_list_limit_max` / `follow_list_offset_neg` / `block_list_wrong_owner` |
| 协议层 | 6 | `no_auth_header` / `bad_jwt` / `follow_missing_target` / `follow_bad_target_type` / `follow_unknown_path` / `follow_wrong_method` |
| 写路径 | 5 | `follow_self_relation` / `follow_target_not_found` / `follow_new_relation` / `unfollow_seeded` / `unblock_seeded` |

### 双边判定（1 条 · C == B，**无 Go 锚点**）

| 用例 | 判据 | 说明 |
|---|---|---|
| `blacklist` | 两侧 **401** ＋ **body 非空** ＋ 两侧 body 逐字节相等 | 冻结的 20 条**不含黑名单**（Go 真源那轮没覆盖）⇒ 本条**不能**声称「三方一致」 |

> ⚠️ **判据为什么不能空洞**：`401` 有两种来源 —— ① **验签失败**（body **空**）② **命中黑名单**（body 非空）。
> ⇒ 只断言「401」会把「验签失败」当成「黑名单生效」⇒ **必须同时断言 body 非空**。

### gRPC wire 层判定（13 条 · **双边** · C 侧 gRPC ↔ B 侧 follow-rpc）

| 组 | 条数 | 用例 |
|---|---|---|
| 读路径 | 4 | `wire_follow_list` / `wire_block_list` / `wire_follower_list` / `wire_recommendations` |
| 读路径边界 | 3 | `wire_follow_list_offset_neg` / `wire_follow_list_limit_max` / `wire_follow_list_user_missing` |
| 写路径 | 6 | `wire_follow_self` / `wire_follow_target_notfound` / `wire_follow_new` / `wire_unfollow` / `wire_block` / `wire_unblock` |

> ⚠️ **探针不用任何 pb 生成码**（手构 proto3 varint 字节 ＋ `byte[]` marshaller）⇒ **同一个 jar 跑两侧**
> ⇒ 比的是**纯 wire 字节**，不被「两侧 pb 包名不同」污染（proto3 零值不编码 ⇒ 构造时必须省掉零值字段）。
> ⚠️ **没有 Go 锚** ⇒ 读作「**双侧一致**」，**不计入「三方一致」**。

### 场景轮判定（6 类 · **双边** · §九 的 11 类场景）

| 阶段 | 构造 | 判据 |
|---|---|---|
| **超时** | 下游 **SIGSTOP** | 3 轮两侧**逐字节**一致 · 业务码 **1015**（**钉死码值**：5001 与 1015 都会「非 200」⇒ 必须钉死） |
| **恢复** | **SIGCONT** | 两侧**连续 3 轮**都 200（稳定态）⇒ 再比 ⇒ 逐字节一致 |
| **外部依赖失败** | **kill -9** 下游 | 两侧一致 · **5001**（连接被拒 ⇒ 非超时的下游故障仍折叠成 5001） |
| **恢复（重启）** | 重启下游 | 连续 3 轮 200 ⇒ 逐字节一致 |
| **并发** | **双侧各 8 路** | 业务码集合 = **{200}** ＋ 各自**恰好 1 行** |
| **事务回滚** | 两侧各加**同款 CHECK 约束** | 两侧都是 **5008**（写路径 DB 失败）＋ follow 行仍在 · block 行未写入 |

### 钉住的字节级差异点（都是「只有真跑才看得见」的）
| 用例 | 冻结的字节 | 为什么值得钉 |
|---|---|---|
| `follow_list_empty` | `{"code":200,"msg":"OK","data":{"user_ids":null}}` | **空列表是 `null` 不是 `[]`**（中间隔着一次 protobuf 序列化） |
| `recommendations_seeded` | `{"target_id":"1004","mutual_score":20}` | `target_id` 带 `,string` ⇒ **出参是字符串**；且字段序 = **struct 声明序**（Jackson 全显式命名时走**字母序** ⇒ 需 `@JsonPropertyOrder` 钉死） |
| `follow_list_seeded` | `"user_ids":[1003,1002]` | `user_ids` **不带** `,string` ⇒ 出参是**数字**（与 `target_id` 相反的不对称） |
| `no_auth_header` / `bad_jwt` | HTTP **401 + 空 body** | 只 `WriteHeader(401)`，不写字节 |
| `follow_unknown_path` | HTTP 404 + `404 page not found\n`（38 字节含尾随换行） | Go `router/patrouter.go:73,93-97`；C 侧靠共享层 `common/web/RouterErrorResponses` 逐字节复刻 |
| `follow_wrong_method` | HTTP **405 + 空 body** | 与 404 形状相反（**裸状态码**） |
| `block_list_wrong_owner` | `{"code":5005,"msg":"forbidden"}` | `block/list` 与 `recommendations` 有**归属校验**，另两个 list 端点**没有**（刻意的不对称） |
| `follow_self_relation` | `{"code":5201,...}` | 自关系在 **rpc 层**判，api 层不判 |
| `follow_target_not_found` | `{"code":5101,"msg":"user not found"}` | user-rpc 明确回 NOT_FOUND ⇒ 与「下游挂掉的 5001」区分 |

## 播种（**两侧各自的库里各一份**）

| 表 | 行 | 用途 |
|---|---|---|
| `follow_relation` | (1001→1002) (1001→1003) (1002→1001) (1002→1004) | 关注 / 粉丝 / 推荐三张列表的**非空**样本 |
| `block_relation` | (1001→1005) | 黑名单样本 |
| `users_user`（**只在 B 侧 PG**） | uid 1001~1005 | user-rpc 的存在性校验（两侧**共用**这一个 user-rpc ⇒ 只需一份） |

> ⚠️ **绝不拿空库当证据**：「两侧都空」分不出「相同」与「都空」
> ⇒ 必须播种同一批行（`mutate-parity.sh` 的 **M3** 专门盯这一条：让 C 侧多一行 ⇒ 探针必须转红）。
>
> ⚠️ `create_time` 全部**互不相同** ⇒ `ORDER BY create_time desc` 的顺序确定
> （同值时 PG 不保证顺序 ⇒ 输出顺序会随机 ⇒ 探针会**假红**）。

## 变异验证（`mutate-parity.sh`）

AGENTS.md §3.3.3：新增的每个探针都必须配一个**能真正触发它**的变异，否则无法排除「假绿」。

| 变异 | 手法 | 期望 |
|---|---|---|
| **M1** | 去掉 **C 侧** `RecommendUser` 的 `@JsonPropertyOrder` | 探针 **EXIT=1**，且 `recommendations_seeded` 被判不等（字段序漂移被抓到） |
| **M2** | 改 `expected.txt` 里一枚 hex | 探针 **EXIT=1**，且 `follow_list_seeded` 与冻结值不符（**期望值确实在参与判定**） |
| **M3** | 让 **C 侧**多灌一行 | 探针 **EXIT=1**，且 `follow_list_seeded` 与 B 不等（**探针真在读两侧各自的库**，不是「两边都空」） |
| **M4** | C 侧在矩阵**中途挂掉**（`PROBE_STOP_C_AFTER`） | 探针 **EXIT=1**，且下一条报「**请求失败**」⇒ **不会**沿用上一次的残留 body |
| **M5** | 把 `run.sh` 改回旧写法（**固定 `-o` 文件 + 去掉 `000` 守卫 + 只比 body**） | 同场景下探针**误判成 ✅（假绿）** ⇒ **复现 FAILING-010** ⇒ 证明 M4 的红确实是守卫带来的 |

> ⚠️ **M5 的三处必须一起改**才等于「旧探针」：旧探针**只比 body**
> ⇒ 本探针多比的 HTTP 状态码会让「200 vs 000」先把差异挡下来 ⇒ 假绿复现不出来。

## 探针本身踩过的坑（写下来免得下一个人再踩一遍）

### 从 B 侧探针继承的 17 条

B 侧 `tools/api-full-parity-probe/follow/README.md` 的「探针本身踩过的坑」表
（Nacos 临时实例要保活 / 持久实例注册报 Raft 无 leader / `rm` 打印污染 stdout /
固定 `-o` 文件读到残留 body / 就绪闸门必须等 `code=200` / 端口阶梯的诊断信息必须走 stderr /
macOS bash 3.2 的空数组展开 / `rm -rf` 被回收站守卫拦下 / **收尾断言必须放在真正收尾之后** …）
⇒ 本探针**逐条照搬**，不再复述。

### 本轮新增的 18 条（18~35）· 含 1 条「变异残留」

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| **18** | B 侧 user-rpc 启动即 `UnknownHostException: postgres` | **bash 的前缀赋值语义**：`VAR=x cd dir && exec java` 里的 `VAR=x` **只作用于 `cd` 这一条命令** ⇒ 后面 `exec java` 的环境里**一个变量都没有** | 改成 `( cd dir && exec env VAR=… java -jar … )` ⇒ **用 `env` 显式传参**，不依赖前缀赋值的继承 |
| **19** | 探针注册进 Nacos 的实例「服务名在、hosts 为空」；或注册了却连不上 | **Docker Desktop（macOS）的宿主不可达容器 IP**（实测 `172.19.0.2:8848` / `172.19.0.5:49210` 全部超时）⇒ Nacos 里 `Sea-user-rpc` 注册的 `172.19.0.5:49210` 对探针**不可用** | 探针自己注册 **`127.0.0.1`** 形态的实例（发布口可达）＋ **每 5s 重注册**保活（ephemeral 实例靠心跳） |
| **20** | 🔴 **C 侧 `follow-service` 作为真实进程起不来**：`NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder` | `service/follow/pom.xml` 把 `io.grpc:grpc-inprocess` 声明成 **`test` scope** ⇒ Maven「最近者优先」压过 starter 的传递 compile 依赖 ⇒ **运行时 classpath 上整个 artifact 消失** ⇒ devh 的 `shadedNettyGrpcChannelFactory` 建 channel 时链接失败 | 去掉 `<scope>test</scope>`（版本由根 pom 的 `dependencyManagement` 管）⇒ **已修**，见 `service/follow/pom.xml` 的注释块 |
| **21** | 播种 `users` 表报「relation does not exist」 | **B 侧 Java 的用户表叫 `users_user`**（Go 侧才叫 `users`）⇒ B 侧 `users-common/db/01_create_user.sql` 建的是 `users_user` | 播种 SQL 改成 `users_user (uid,username,password,email,status,score,create_time,update_time)` |
| **22** | B 侧 user-rpc 起不来（端口被占） | B 侧 `USER_RPC_PORT` 默认 **49210**、`USER_RPC_MANAGEMENT_PORT` 默认 **9094** ⇒ **与 `Sea-user-rpc` 容器的发布口完全撞车** | 探针专用覆盖成 18203 / 18209（只改探针的启动 env，不改 B 侧任何文件） |
| **23** | 🔴 **派发入口 `run-parity.sh follow` 永远报「没有登记探针目录」** | 派发器按 **`<域>/run.sh`** 找探针 ⇒ 而目录叫 **`follow-parity/`** ⇒ **文档写的入口其实从没被跑过**（前几轮都是直接调 `run.sh`） | 目录改名成 `follow/` ＋ 同步全部引用（`tools/README.md` · 域台账 · `RecommendUser` 的 javadoc）⇒ **教训：登记了「入口」不等于「入口通」** |
| **24** | 场景轮的「判据」打出 **「没恢复」但业务码明明是 200** | `request_body` 的输出是 **`httpcode|hex`** ⇒ body 是**十六进制** ⇒ 拿明文模式 `*'"code":200'*` 去匹配**永远不中** ⇒ 「已恢复」被误报成「没恢复」 | 一律走 `biz_code_of`（内部先 `xxd -r -p` 解码）⇒ **凡是要看 body 的地方，都不能直接 grep 原始串** |
| **25** | 🔴 **下游 SIGCONT 后 40s 内两侧都回不来** | user-rpc 是 Nacos 的**临时实例**（TTL 数秒）⇒ 被挂起的那十几秒**没法续期** ⇒ **条目过期** ⇒ 两侧的客户端都拿不到实例 ⇒ **恢复失败是服务发现层的现象** | 放行后**等条目回池**（`nacos_assert_instance` 重试 15 次）再进恢复轮 |
| **26** | 🔴 **恢复轮「等到 200 了，下一轮却又不一致」** | 下游刚回来时系统处于**过渡态**（客户端通道在重连、服务端在消化积压的取消请求）⇒ 实测出现过「已等到 200，紧接着 **C=5001 · B=1015**」⇒ 单轮 200 **不代表**已进入稳定态 | 恢复判定改为**连续 3 轮**都 200；比对允许重试 3 次且**必须如实报出「第 N 次才一致」** ⇒ ⚠️ **重试不是把红洗成绿**：3 次都不同 ⇒ 判为漂移<br>🔴 **2026-11-03 已定位**（见 #33）⇒ **不是漂移** |
| **27** | 场景轮并发/回滚判据**通过得莫名其妙** | 场景轮的 target（1008 / 1009）在 `users_user` 里**不存在** ⇒ 两侧**都在 5101（用户不存在）分支短路** ⇒ 根本到不了「并发唯一索引」与「CHECK 约束注入」两条被测路径 ⇒ **判据空洞**（「两侧一致」只证明了「两边都 5101」） | 场景轮**阶段0** 先补 `users_user` 的 1008 / 1009；并发判据要求**业务码集合 = {200}**、回滚判据要求**业务码 = 5008** ⇒ ⇒ **空洞判据必须被显式排除** |
| **28** | gRPC 层分层探测**恒红**（两侧状态码不一致） | ① B 侧 follow-rpc 在超时场景**本身非确定**（服务端的 2s 超时调度器与内层 user-rpc 的 2s 预算**同一瞬间到期** ⇒ 实测采样到 `DEADLINE_EXCEEDED` **与** `INTERNAL`）⇒ 该层**不能作契约判据**；② description 里带**变动的毫秒数** ⇒ 比字节恒红 | 阶段1b **降级为观察项**（不参与 EXIT 码，但照样打印两侧 3 轮分布）⇒ **契约判据取 HTTP 层**（实测 6/6 轮稳定 1015） |
| **29** | 下游超时场景两侧码不同（C=5001 / B=1015）⇒ 差点被当成漂移去改代码 | **B 侧那层「折叠成 INTERNAL」在 B 侧从来不可观测** —— api→rpc 那一跳的 **2s 客户端预算**先到 ⇒ api 层看到 `DEADLINE_EXCEEDED` ⇒ 反查落默认码 **1015**；C 侧合并进程（D1）后**没有那一跳** ⇒ 折叠**第一次变得可观测** ⇒ 5001 | 领域层**单独放过 `DEADLINE_EXCEEDED`**（原样上抛）⇒ HTTP 层反查落默认码 ⇒ **1015** ⇒ 与 B 侧逐字节一致（3/3 + 3/3 轮）⇒ 见域台账 §5.5 漂移 I |
| **30** | 🔴 变异验证**被杀在轮次中间** ⇒ 下一轮的基线轮直接 **exit 2**（看起来像「环境问题」） | 两层叠加：① `pkill -f mutate-parity.sh` **不会杀掉内层探针**（探针是它的子进程）⇒ 残留进程**占着 18201~18205**；② 那一轮的**变异还留在文件里**（备份/还原只在轮次**结束时**跑）⇒ 本轮实测：`run.sh` 里留着 M3 的「C 侧多灌一行」⇒ **矩阵红 + 阶段6 主键冲突 `!! 灌事务回滚用的 follow 行失败` ⇒ exit 2**<br>⇒ ⇒ **探针目录未入 git ⇒ 没有 git 基线可还原** ⇒ 残留只能靠脚本自己还原 | ① **杀整棵进程树**（探针 + java）＋ **实测端口零占用**再重启；② 变异脚本**启动前自检六个靶点**（`residue_check`）⇒ **发现残留就拒绝启动**（并指出是哪个变异）⇒ ⇒ **宁可拒绝跑，也不要用被污染的读数下结论** |
| **31** | 🔴 **探针跑着跑着，下游 user-rpc 被 `SIGKILL`**（bash 报 `Killed: 9`）⇒ 但**探针照样跑完**并给出「两侧逐字节一致 3/3」的**绿灯** | 宿主**内存压力**：本机 24GB 物理内存 ⇒ **默认堆 6GB/JVM** ⇒ 探针起 **4 个服务端 JVM ＋ 多个 wire 探针 JVM** ⇒ macOS 的 jetsam **挑最大的杀**（实测被杀的正是 user-rpc）；swap 已用满（16G 里 15.6G）<br>⇒ ⇒ **致命点**：那几轮测的是「**连接被拒**」而不是「**挂起超时**」⇒ 而读数**看起来完全正常**（两侧同轮同码）⇒ **只读码值分布绝对发现不了** | ① **全部 JVM 封顶**（服务端 `-Xmx512m` · wire 探针 `-Xmx256m` · 构建 `MAVEN_OPTS=-Xmx768m`）⇒ 降峰值；② 🔴 **加「场景有效性断言」**：`downstream_alive()`（`kill -0`）在**阶段1 前后 + 每轮之间**、**阶段2b 每轮之前**都断言下游还活着 ⇒ 一旦被杀 ⇒ **`die`（exit 2）** ⇒ **绝不产出误导性读数**（⇒ **教训：读数的「分布好看」不等于「测的是那个场景」——必须有一条独立于被测物的场景有效性断言**） |
| **32** | 🔴 下游被外力杀掉后，探针**卡在 Nacos 等待里 7.5 分钟** | 等条目的代码是**外层 `seq 1 15` × 内层 `nacos_assert_instance`（自身 30×1s）** ⇒ **最坏 450s** ⇒ 而下游已死 ⇒ 条目**永远不会回来** ⇒ 后面所有阶段都跑在坏环境上 | 改成**单次**调用（内层已含 30s 上限）⇒ 最坏 30s；且**先断言下游活着**，死了直接 `die`（不做无谓等待） |
| **33** | 🔴 **「恢复窗口过渡态 C=5001 / B=1015」无法定位** —— 到底是**错误码映射**不同，还是**失败形态**不同？ | 只看 HTTP 层的码值**分不清**这两种 ⇒ 且 B 侧 **api→rpc 多一跳** ⇒ 它的 2s 客户端预算比内层预算**早一个网络跳**到期 ⇒ **B 的 HTTP 层 1015 其实是「预算抢先」的产物，不是领域语义**（B 的领域语义是「一切非 NOT_FOUND ⇒ `INTERNAL+5001`」） | 新增**阶段 2b「过渡窗口分层采样」**：SIGCONT 之后、**Nacos 条目回来之前**的窗口里**同时**采 ① HTTP 层（码 + **耗时**）② **gRPC 直连**（`caseFilter` 模式自带 15s deadline ⇒ **绕开 B 侧 api 的 2s 预算** ⇒ 能看见「follow-rpc 自己会答什么」）③ **Nacos 条目**<br>⇒ **实测结论**：两侧**逐轮同步**（`200` → `5001`×3 → `200`，5001 那几轮耗时仅 **10~38ms** ⇒ 快速失败）；**gRPC 直连两侧同为 `ERROR_INTERNAL` + 同一 description** ⇒ ⇒ **码值随「失败形态」走，不随错误码映射走** ⇒ **两侧同形态必然同码** ⇒ ⇒ **历史那一次不同步 = 两侧在同一瞬间落在不同形态** ⇒ **不是漂移**（详见域台账 §8.6.7.1） |
| **34** | 阶段2b 的 gRPC 直连「看起来两侧不一致」（B 1440ms / C 1024ms） | 两侧**各多一个 JVM 启动 + 各自的网络跳** ⇒ **耗时不可逐字节比** ⇒ 且直连探针**每次新建 channel** ⇒ 走的是**新鲜的服务发现解析**，与 api/follow-rpc 的**长连接**在「实例条目刚过期」时**可能走出不同分支** | ⇒ **直连读数只作旁证**（比**状态码**，不比耗时）；**主证据** = HTTP 层的「码 + 耗时」（耗时 10~38ms ⇒ 快速失败；~2s ⇒ 超时）⇒ **耗时本身就是区分两种形态的判据** |
| **35** | 🔴 **变异验证跑着跑着，我改的那个「变异靶点文件」被还原掉了**（我写好的 javadoc 消失） | `mutate-parity.sh` 的 `backup` 是**在脚本启动时一次性把四个靶点全备份**（`backup "${EXISTENCE}" …` 等）⇒ ⇒ **运行期间对该文件的任何修改，都会被该轮结束时的 `restore`（`cp` 备份回来）覆盖** ⇒ ⇒ ⚠️ **跑变异期间不要改任何变异靶点文件**（本轮靶点：`RecommendUser.java` · `expected.txt` · `run.sh` · `FollowUserExistenceService.java`） | ① **纪律**：跑变异期间**只改非靶点文件**（文档 / 其他源文件）；② **改完靶点文件后必须核对**（`grep` 一下自己写的那段还在不在）⇒ 本轮已踩：跑完后 javadoc 补充被冲掉 ⇒ **重新 apply 一次**（备份是旧版 ⇒ `edit` 的 old_string 仍能命中）<br>⚠️ **为什么不去改「backup 时机」**：改了脚本就得**重跑一遍变异**才让证据与脚本对得上 ⇒ 代价远大于收益 ⇒ ⇒ **只记录，不动脚本** |

> ⚠️ **#20 是本探针抓到的真实缺陷**，而且是 **71 个单测全绿也发现不了**的那一类：
> 测试跑在 **test classpath** 上 ⇒ `grpc-inprocess` 本来就在 ⇒ 缺陷不可见；
> **只有把 jar 真跑起来**才会炸。⇒ 这是 AGENTS.md §3.3.3「**跑绿 ≠ 本来就绿**」的教科书形态，
> 也是「E-4 不可被单测替代」的实证。

> 🔴 **#29 是本探针抓到的第二条真实缺陷**（业务码级别的 L1 漂移）⇒ **同样只有真跑双侧才看得见**：
> 单测若只断言「非 200」，5001 与 1015 **都会绿** ⇒ ⇒ **判据必须钉死**具体码值。

## 本探针**不覆盖**什么（不得据此声称「follow 域已完全闭环」）

1. ~~**只覆盖 HTTP 层**~~ ⇒ **gRPC wire 层已进探针**（`grpc-wire/FollowWireProbe.java` · 13 条 · **双边**逐字节一致）
   ⇒ ⚠️ 但**没有 Go 锚** ⇒ 读作「**双侧一致**」而非「三方等价」；且**超时场景不可作 wire 逐字节判据**（description 带变动毫秒数）。
2. **`blacklist` 那条只有双边锚**（C == B），**没有 Go 锚** ⇒ 不得计入「三方一致」。
3. ~~**只覆盖 21 个请求**~~ ⇒ **场景轮已覆盖 6 类**（超时 / 恢复 / 外部依赖失败 / 恢复 / 并发 / 事务回滚）
   ⇒ ⚠️ **恢复窗口的过渡态**两侧可能报不同码（实测 C=5001 · B=1015）⇒ **未定位** ⇒ 如实记录，不臆断成漂移。
4. **两侧的 DB 初始条件不完全同形**：B 侧 follow-rpc 与 user-rpc **共库**
   ⇒ 它的 Flyway 走的是「**非空 schema + baseline-version 0**」路径；
   C 侧独占一个空库 ⇒ 走的是「空 schema」路径。
   ⇒ 两条 Flyway 路径**分别**被本探针与 C 侧自己的 `FollowFlywayBaselineRealPgTest` 覆盖，
   **不互相顶替**。
5. **指标面（Prometheus）未覆盖**。`/actuator/prometheus` 的桶边界与指标名
   仍由 C 侧自己的 `FollowMetricsContractTest` 守着，且本仓生产形态上下文**没有**
   `PrometheusMeterRegistry`（域台账 §5.6 的 `DRIFT-FOLLOW-E`）⇒ 抓取口不可用。

## 真源冻结 ⇒ **B 侧只读**

本探针只在 B 仓跑 `mvn package`（产物落 `target/`，已被 `.gitignore` 排除）
⇒ **不碰 B 侧任何源码 / 配置**。第 2 步会**断言 B 仓 `git status` 干净**
（`service/follow` / `service/users` / `service/common` 三处）⇒ 否则 `die`（EXIT=2）。
