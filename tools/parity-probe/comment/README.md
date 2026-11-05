# comment 域「B 侧 ↔ C 侧」双侧活体对拍探针（E-4）

> **一句话**：让 **B 侧（oracle，Boot 4 · 双进程）** 与 **C 侧（RAG-V1 · Boot 3.5 单进程）**
> **同时真跑起来**，对同一批请求做**逐字节比对**（status + content-type + body），
> 再与冻结基线比对 ⇒ 证明「C 侧 comment 域的 HTTP 面与 B 侧在字节级一致（或差异已被登记冻结）」。

---

## 一、对比双方

| | **B 侧**（oracle · 只读） | **C 侧**（被测） |
|---|---|---|
| 代码 | `/Users/pr/Documents/java&golang/RAG/service/comment` | 本仓工作树 `service/comment` |
| 形态 | **两个进程**：`comment-api`（HTTP :8888）+ `comment-rpc`（gRPC :9002） | **一个进程**：Tomcat + gRPC 合并（D1 + D2） |
| 运行 | **本地现成镜像** `sea/comment-api:latest` / `sea/comment-rpc:latest` ⇒ **B 仓一个字节都不改** | `mvn -o -pl service/comment -am package` 的 fat jar |
| 栈 | Boot **4.0.8** · Spring 7.0.9 · Tomcat 11.0.24 · **Jackson 3.1.5** | Boot **3.5.11** · Spring 6.2.x · Tomcat 10.1 · **Jackson 2.19.4** |

⚠️ **B 仓只读**：探针只 `docker run` 现成镜像，**不构建、不改 B 仓任何文件**。

---

## 二、怎么用

```bash
bash tools/parity-probe/comment/run.sh          # 正常跑：两侧逐字节比对 + 与冻结基线比对
FREEZE=1 bash tools/parity-probe/comment/run.sh  # 重建冻结基线（expected.txt + known-diff.txt）
bash tools/parity-probe/comment/mutate-parity.sh # 变异验证：证明探针不是恒绿
RUN_ID=A bash tools/parity-probe/comment/run.sh  # 并发隔离（端口块 + 命名空间隔离身份）
```

**退出码语义**（严格三分）：

| 码 | 含义 | 判定依据 |
|---|---|---|
| `0` | 两侧逐字节一致（或差异**已登记在 known-diff.txt 且字节形状未变**） | 见 §五 |
| `1` | **真差异** | 两侧字节不同 **且** 不在 `known-diff.txt` 里 / 或在但**字节形状变了** |
| `2` | **环境问题**（与真差异严格分开） | 缺命令 / 中间件没跑 / 镜像不存在 / 端口被占 / 侧没就绪 / 构建失败 |

⇒ 🔴 **「没起好」绝不能报成「C 侧有缺陷」** ⇒ 环境问题一律 `EXIT=2` 并附原始报错。

---

## 三、五个隔离向量（每轮独立 ⇒ 可并发跑）

见 `probe-isolation.sh` 的头注释。本域**特有**的两条：

### V1 双 Nacos 命名空间（**必须两个**）

B 侧与 C 侧**注册的服务名是同一个** —— `comment-rpc`

- B 侧 `…/comment/rpc/bootstrap/NacosRegistration.java` `SERVICE_NAME = "comment-rpc"`
- C 侧 `service/comment/…/wiring/NacosRegistration.java:68` `SERVICE_NAME = "comment-rpc"`

⇒ 若共用一个命名空间，B 的 `comment-api` 用 `discovery:///comment-rpc` 解析时**可能选中 C 侧的实例**
⇒ 探针比的不再是「B 侧 vs C 侧」，而是「B 侧 vs 随机一侧」⇒ **假绿**。
⇒ **服务名/group 是两侧硬编码常量 ⇒ 隔离只能靠命名空间** ⇒ 两个命名空间。

### V2 双 Postgres（**本域比 article 域更硬**）

两侧的 Flyway **账本表名是同一个**：`flyway_schema_history_comment`

- B 侧 `service/comment/comment-rpc/…/application.yml:126`
- C 侧 `service/comment/src/main/resources/application.yml`

⇒ 共用一个 PG ⇒ 先启动的一侧把自己的账本写进 `public` schema ⇒ 后启动的一侧看到
「非空 schema + 已有账本」⇒ **自己的 V1..V8 全部被跳过** ⇒ 少一半表 ⇒ 读面假红。
⇒ **两侧必须各一个 PG 容器**。

### V5 双 Redis（**本域最容易被忽略的一条**）

两侧的 **Redis key 空间逐字相同**：

- B 侧 `…/service/CommentRedisKeys.java:31,36,47,60`
- C 侧 `service/comment/…/service/CommentRedisKeys.java:60,72,86,104`
- ⇒ `comment:subject:<id>` / `comment:content:<id>` /
  `comment:index:<type>:<id>:<root>:<sort>:<offset>:<limit>` / `comment:index:item:<id>`

⇒ **共用一个 Redis ⇒ 两侧读的是同一份缓存** ⇒ 探针比的变成「B 侧 vs B 侧」⇒ **假绿**。
⇒ 两侧**各起一个 Redis 容器**（`redis:7-alpine`）。

### 其余三条（沿用 article 域探针）

- **V3 端口租约**：原子 `set -o noclobber` 占块；块号 ⇒ 端口偏移；**先与「容器已发布的宿主端口」求交**
  （撞上时 `lsof` 返回的是 Docker Desktop backend ⇒ 一杀 ⇒ **整个 Docker 失联**）。
- **V4 构建产物**：`mvn` 串行锁 + 每轮把 fat jar **拷进自己的 WORK**（`target/` 是同一份 ⇒ 并发写必互踩）。
- **探针自带 topic/group**：见 §四「Kafka 的两个 listener」。

---

## 四、基础设施与两个必须知道的坑

### Kafka 的两个 listener

宿主 `Sea-kafka` 的 EXTERNAL listener 广播的是 `127.0.0.1:49992` ⇒ **只对宿主有效**。

- **B 侧**（在容器里）必须用 compose DNS 名 `kafka:9092`（PLAINTEXT listener）
- **C 侧**（在宿主上）用 `127.0.0.1:49992`

⇒ B 侧容器**必须与 compose 那套中间件在同一个 docker 网络**（探针自动从 `Sea-nacos` 读出网络名）。

### 本轮 Kafka topic/group 覆盖（**探针侧隔离，不是被比较面**）

两侧的审计 topic 名逐字相同（`comment-audit-topic`）、消费组也相同（`comment-audit-group`）
⇒ 若与宿主上别的 comment 测试共用同一个 broker，对方 publish 进来的消息会被**本轮某一侧**消费掉
⇒ 那一侧的库多出评论 ⇒ **读面假红**。

⇒ 本轮给两侧各一个**专属 topic + group**（用 `SPRING_APPLICATION_JSON` 注入 —— 它的键名是**精确属性名**，
不经过 relaxed binding；`comment.kafka.audit-topic` 的 env 形式是 `COMMENT_KAFKA_AUDITTOPIC`，易写错）。

⚠️ **topic 名本身不在被比较面内**（它由 `docs/Mapping_Table/comment.md` 静态比对负责）。

### 宿主中间件

| 容器 | 宿主端口 | 用途 |
|---|---|---|
| `Sea-nacos` | 49848 | 服务注册 / 发现（**两个命名空间**） |
| `Sea-kafka` | 49992 | 事件总线 |
| `Sea-jaeger` | 49317 | OTLP（**不参与比较**） |

⚠️ **Redis 不在宿主中间件之列** —— 本探针**自带两个 Redis 容器**（V5）。

---

## 五、比较面与不可比面（**显式声明**）

### 可比面（本探针覆盖）

| 组 | 用例数 | 说明 |
|---|---|---|
| **鉴权面** | 8 | 缺 header / 坏签名 / 过期 / 无 `userId` claim / 非数字 `userId` / 字符串 `userId` / 无 claim 走 list |
| **校验面** | 9 | 7 个端点各一条「必填字段缺失」＋ 空 body / 非法 JSON / 空 `target_type` / 空 `target_id` |
| **路由面** | 4 | 未知路径（带/不带 token）/ 方法不允许 / 前缀写错 |
| **媒体类型面** | 1 | `Content-Type: text/plain` |
| **读面** | 9 | 播种后的 list：时间序 / 热度序 / 翻页 / 回帖 / 不存在的 target / **四条业务层兜底**（page≤0、page_size>50、sort_type 非法、root_id<0） |

⇒ **读面的「不存在的 target」** 两侧都返回 `200 + {code:5001}`（B 侧 `getcommentlogic.go:72-75` 是**裸 `return nil, err`**
⇒ 不做 code 转换 ⇒ 兜底 5001；C 侧照抄）⇒ **这是 L2 语义差异的活体证据**。

### 不可比面（**明确不做逐字节比对**，理由随附）

| 面 | 为什么不可比 |
|---|---|
| **gRPC 面** | **拓扑产物 · 不是行为漂移**：B 侧 = 两进程（api 走 `discovery:///comment-rpc` **跨进程 gRPC**）；C 侧 = 一进程（api **直连**领域层，**没有网络跳** · D2）⇒ 两边 gRPC 拓扑不同 ⇒ 只能对拍 HTTP 面 |
| **写路径（`create` 等 6 个端点）** | `create` 的响应带**随机雪花 ID**且**同步推 Kafka** ⇒ 两侧字节必然不同 ⇒ 逐字节比它 = **必然红且红了也不代表有错**（假红）。⇒ 写路径的真链路由仓内 `CommentKafkaAuditRealTest` / `CommentKafkaFailureDirectionRealTest` 等**真触发测试**覆盖 |
| **响应头全集** | 本探针只比 **status + content-type + body**（与 article 域探针同口径）。⇒ 例：`405` 的 `Allow` 头**不参与比较** |
| **`Set-Cookie` / `Date` 等易变头** | 每次请求都不同 ⇒ 比了必假红 |

---

## 六、已知差异（`known-diff.txt`）

### 机制：白名单是**更严的断言**，不是免责清单

- `expected.txt`：两侧**逐字节相等**的用例 ⇒ 冻结 `status|content-type|body-hex`。
- `known-diff.txt`：两侧**确实不同**的用例 ⇒ **冻结两侧各自的字节**
  （`DIFF|<name>|<B status>|<B ct>|<B hex>|<C status>|<C ct>|<C hex>`）。
  ⇒ 跑的时候，若实测的 (B, C) 字节对 **与冻结值完全一致** ⇒ 判 ⚠️「已知差异」；
  ⇒ **任一侧的字节变了（差异形状变了）⇒ 立即判红**。
  ⇒ ⇒ 这条白名单**参与判定**，不是「已知有差异 ⇒ 放行」。

### 当前 9 条已知差异：**JSON 键序**（值完全相同）

**现象**：`/comment/v1/comment/list` 的响应里，`data.comment[0]` 与 `data.subject` 两个对象的**键序**两侧不同；
**解析后取值完全相同**（已用 `json.loads` 逐条比对确认：9/9 值相等）。

| | 键序 |
|---|---|
| **B 侧** | **字母序**：`attribute, children, content, created_at, dislike_count, id, like_count, meta, parent_id, reply_count, root_id, state, user_id` |
| **C 侧** | **声明序**：`id, user_id, content, root_id, parent_id, like_count, dislike_count, reply_count, attribute, state, created_at, meta, children` |

**根因（三方对照，不是「C 侧抄错了」）**：

| 方 | 序列化器 | 属性顺序 |
|---|---|---|
| **Go（真源）** | `encoding/json` | **结构体声明序**：`id, user_id, root_id, parent_id, content, created_at, meta, like_count, dislike_count, reply_count, attribute, state, children`（B 侧 javadoc 引 `types.go:6-20` · 13 字段 + 首尾 2 行 = 15 行 ✓） |
| **B 侧** | **Jackson 3.1.5**（Boot 4 默认 · 证据：镜像内 `BOOT-INF/lib/jackson-databind-3.1.5.jar`） | **字母序** |
| **C 侧** | **Jackson 2.19.4**（D14 决策钉死 · 证据：fat jar 内只有 `jackson-databind-2.19.4.jar`） | **声明序** |

⇒ **⇒ 这是 Jackson 3 ↔ Jackson 2 的框架版本分叉**，**不是 C 侧的业务逻辑缺陷**。
⇒ B 侧自己的口径也印证这一点：`service/article/…/dto/Article.java:71` 与
`service/hot/…/dto/HotArticleCard.java:70` 明写「Jackson 默认按**字母序** ⇒ 必须用
`@JsonPropertyOrder` 显式钉回 Go 的声明序」—— **B 侧的 article / hot 域都钉了，comment 域没钉**
⇒ B 侧 comment 域的字母序是**它自己的默认值泄漏**，不是一条被签字的契约。
⇒ C 侧按 **D14「只 Jackson 2」** ⇒ **无法也不必**改成字母序 ⇒ 归 **「框架限制 / 版本分叉」** 类登记。

⇒ 台账：`docs/DRIFT/comment/verification-comment-service-2026-11-03.md`（E-4 段）。

---

## 七、基线冻结依据

- `expected.txt` 与 `known-diff.txt` **只允许由 `FREEZE=1` 重建**。
- 🔴 **只有本轮真的没红才允许覆盖** ⇒ 一次坏跑不会把基线写坏。
- ⚠️ `FREEZE=1` 会把「两侧字节不同」的用例**冻结为已知差异** ⇒ 因此**必须逐条核对根因后再提交**
  ⇒ 脚本会**逐条打印**冻结内容。
- 两份文件的 **sha256** 由 `mutate-parity.sh` 在变异前后核验 ⇒ 证明「冻结值确实参与判定」。

---

## 八、变异验证（`mutate-parity.sh`）

AGENTS.md §3.3.3：探针必须有「能真正触发它」的变异，否则无法排除「假绿」。

| 变异 | 手法 | 期望 |
|---|---|---|
| **M1** | 改 **C 侧**库的评论正文 | EXIT=1（证明比较面真的读 C 侧库） |
| **M2** | 改 **B 侧**库的评论正文 | EXIT=1（证明检测不是单向的） |
| **M3** | 改 `expected.txt` 里一枚 hex | EXIT=1（证明冻结基线参与判定） |
| **M4** | 改 `known-diff.txt` 里某条 DIFF 的 **C 侧 hex** | EXIT=1（证明白名单是更严的断言，不是免责清单） |

⇒ 四条**全部精确转红** + 两个冻结文件 sha256 还原核验通过 ⇒ 退出码 0。

---

## 九、本轮踩过的坑（**留给下一个人**）

1. 🔴 **1xx 响应头会污染状态码判定**：共享层 `ClientDisconnectProbe` 会**直接往 socket 写**
   `HTTP/1.1 103 Early Hints\r\n\r\n`（**不经过 Tomcat**）⇒ curl 的 `-D` 文件里会多出一段 1xx。
   ⇒ 用 `head -1` 取状态码会把 103 当最终状态 ⇒ **假红**。
   ⇒ 判据：**只认 status ≥ 200 的那一段**（`parse_final_response`）。
2. 🔴 **脚本里一处少写一个 `}` 会被 bash 静默吞掉几百行**：症状是「某个变量 unbound」，
   而**真正的原因在几百行之前** ⇒ `bash -n` **查不出来**（整段被当成字符串，语法仍然合法）。
   ⇒ 本探针加了「**变量自检**」段（0/10 步）⇒ 坏在哪儿一目了然。
3. 🔴 **`comment_content` 的主键列名是 `comment_id`（不是 `id`）** ⇒ 指纹 SQL 写成 `id` 会整条报错，
   而 `2>/dev/null` 把错误吞了 ⇒ 指纹变空串 ⇒ 误报「库被改动」。
4. ⚠️ **PG 就绪判据必须是「走 psql 真正会走的那条路」**（容器内 socket）：`pg_isready` 过了，
   紧接着的 `psql` 仍可能报 `socket "/var/run/postgresql/.s.PGSQL.5432" failed`。
5. ⚠️ **`docker run` 加 `--pull=never`** ⇒ 本机镜像已齐 ⇒ 避免慢/失败的 pull。
6. ⚠️ **上一轮硬中断会留下探针容器** ⇒ 端口被占 ⇒ 本轮起不来 ⇒ 探针会先扫掉
   名字前缀 `parity-comment-` 的残留容器。
7. 🔴 **变异脚本自己崩掉 ⇒ 看起来像「探针假绿」**：`expected.txt` 的**第一条** CASE 是
   **401 空 body** ⇒ 它的 hex 是**空串** ⇒ 对它做 `h[0]` ⇒ `IndexError` ⇒
   **文件根本没被改** ⇒ 探针照常绿 ⇒ 看起来「M3 没转红」。
   ⇒ 修法：变异脚本**跳过空 hex 的行**，**并且断言文件真的变了**，否则**退出码 2**
   （明确区分「变异脚本自身坏了」与「探针假绿」）。
   ⇒ **这条正是变异机制的价值**：它把「工具自己的 bug」和「被测对象的假绿」分开。

---

## 十、文件清单

| 文件 | 作用 |
|---|---|
| `run.sh` | 主驱动：起两侧 → 播种 → 请求矩阵 → 逐字节比对 → 判决 |
| `probe-isolation.sh` | 每轮独立机制（V1 双命名空间 / V2 双 PG / V3 端口租约 / V4 构建锁 / V5 双 Redis） |
| `mutate-parity.sh` | 变异验证（M1–M4） |
| `expected.txt` | 冻结基线：两侧**逐字节相等**的用例 |
| `known-diff.txt` | 冻结清单：两侧**确实不同**的用例（冻结**双方各自**的字节） |
| `README.md` | 本文件 |
