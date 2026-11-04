# hot 域 · 双侧活体对拍探针（E-4）

> **B 侧（RAG 仓 / Boot 4.0.8）↔ C 侧（RAG-V1 / Boot 3.5.11）**
> **同时起、同时活、同请求、同字节。**

---

## 一、这是什么 / 判定强度

| 项 | 内容 |
|---|---|
| 被测两侧 | B 侧 = `/Users/pr/Documents/java&golang/RAG/service/hot/{hot-api,hot-rpc}`（Boot **4.0.8**）<br>C 侧 = 本仓 `service/hot`（Boot **3.5.11** · D1 合并进程：HTTP + gRPC 同进程） |
| 判定条件 | **C == B**（双侧活体、同一请求、逐字节相等） |
| 证据强度 | **E-4（双侧活体对拍）** |
| ⚠️ **没有第三方锚** | 本机**没有 Go 真源可活体**（无 Sea-etcd 容器）⇒ **不读作「三方一致」**。<br>Go 侧的冻结字节锚在 B 仓 `tools/api-full-parity-probe/hot/` —— 本探针**刻意不引用**它：<br>引用一个本机不可复现的锚 ⇒ 锚坏了也看不出来 ⇒ 那是**摆设锚**。 |
| B 侧只读 | 只在 B 仓跑 `mvn package`（产物落 `target/`，已被 `.gitignore` 排除）⇒ **不碰 B 侧任何源码 / 配置**（脚本第 0 步与第 2 步各断言一次 `git status` 干净）。 |

---

## 二、怎么跑

```bash
bash tools/difftest/hot/run.sh              # 全量（M1 + M2 + M3 + M4）
SKIP_KAFKA=1 bash tools/difftest/hot/run.sh  # 只跑 M1/M2/M3（不起 Kafka broker · 快）
KEEP_WORK=1 bash tools/difftest/hot/run.sh   # 保留临时目录与日志
B_REPO=/path/to/RAG bash tools/difftest/hot/run.sh
```

退出码：`0` = 双侧逐字节一致 · `1` = 有真差异 · `2` = 环境问题 / 依赖缺失 / 真源漂移。

前置条件（缺 ⇒ `exit 2`，**绝不静默降级**）：`docker` / `mvn` / `java` / `javac` / `curl` / `python3` / `lsof` / `nc` / `unzip`；
Sea 栈的 **Nacos（49848）** 与 **Jaeger（49317）** 在跑；B 仓 `service/hot` 与 `service/common` **工作区干净**。

---

## 三、隔离拓扑（**每一条隔离都是正确性要求，不是「省资源」的取舍**）

```
              ┌──────────── Nacos（namespace=hotprobe）────────────┐
              │  group=DEFAULT_GROUP   group=E5C                 │
              │  ├ hot-rpc   (B 侧)     ├ hot-rpc   (C 侧)       │
              │  └ article-rpc(桩·同一进程，两个组各注册一次)      │
              └──────────────────────────────────────────────────┘

   B 侧 hot-api :18221 ──gRPC(discovery)──▶ B 侧 hot-rpc :18222 ──▶ B 侧 Redis :18226
        └──────────────────gRPC(discovery)──▶ article-rpc 桩 :18228（127.0.0.1）

   C 侧 hot-service :18224(HTTP) / :18225(gRPC) ──▶ C 侧 Redis :18227
        └──────────────────gRPC(discovery)──▶ article-rpc 桩 :18228（127.0.0.1）
```

| 隔离项 | 为什么必须 |
|---|---|
| **Nacos 专属命名空间** `hotprobe` | 避免与其它 lane（`public` / `probe-b-smoke` / `probe-c-smoke`）同名串扰 |
| **Nacos 分组**：B 侧 `DEFAULT_GROUP` / C 侧 `E5C` | 两侧的服务名**都叫 `hot-rpc`** ⇒ 不分组 ⇒ 解析池里混进对面的实例 ⇒ **对拍对象静默地错了** |
| ⚠️ **B 侧的组是「半隔离」** | B 侧 hot-rpc 的 `NacosRegistration` **硬编码 `DEFAULT_GROUP`**（`bootstrap/NacosRegistration.java:58,113`）⇒ 探针**不能改 B 侧任何文件** ⇒ 只能让 B 侧 hot-api 也用 `DEFAULT_GROUP`（它的 yml 是 `${NACOS_GROUP:DEFAULT_GROUP}` ⇒ 探针**不传** `NACOS_GROUP` 给 B 侧）⇒ 两者**天然对上** |
| **2 个独立 Redis 容器** | `hot:articles` 是**写路径**（Kafka 合并会 `RENAME` 覆盖它）⇒ 共用一个 ⇒ B 侧合并完 C 侧再合并 ⇒ 读数**必然不同** ⇒ **假漂移** |
| **2 个独立 Kafka broker** | 两侧的 `spring.kafka.consumer.group-id` 都是**字面量** `article-hot-group`、topic 都是**字面量** `article-hot-events`（**B 侧不可覆盖**）⇒ 共用一个 broker ⇒ 两侧互吃对方的消息 ⇒ **假漂移** |
| **article-rpc 桩 = 同一个进程，两组各注册一次** | 若两侧各连一个**不同的** article-rpc ⇒ 返回的 article 投影只要有任意差异（哪怕随机 `create_time`）⇒ 热榜卡片就不同 ⇒ **差异被归因到 hot 域头上** ⇒ 假漂移。<br>⇒ 同一进程 ⇒ 两侧拿到**逐字节相同**的投影 ⇒ 任何差异**只能**来自 hot 域自身 |

---

## 四、五个对拍面

| 面 | 比什么 | 用例数 |
|---|---|---|
| **M1 · HTTP 字节面** | **原始状态行**（含 HTTP 版本与 reason phrase）+ `Content-Type` + body 的 hex | **18** |
| **M2 · gRPC wire 字节面** | **原始 proto3 字节**（手构请求 + `byte[]` marshaller 原样取响应） | **9** ＋ 1 自检 |
| **M3 · Redis 状态面** | `hot:articles` 的类型 / 基数 / 成员 / 分数（**TTL 本层只报告、不判定** —— 理由见 §五） | 1 组 |
| **M4 · Kafka 三面** | 发同款事件 ⇒ 两侧消费 ⇒ HeavyKeeper ⇒ 合并 ⇒ 比 `hot:articles`（＋ TTL 落在 `(0,3600]`）＋ 合并后的 HTTP 面 | 3 组 |
| **M5 · 超时面**（2026-11-04 新增） | `CLIENT PAUSE` 让两侧 Redis **卡住** ⇒ 比**失败形状 + 失败时延**（**判据 =「已登记差异的形状」**，**不是**「两侧相等」） | 1 面 · 4 条判据 |

> ⚠️ **M5 测的是「已登记的差异」（F-02）⇒ 两侧本来就该不同** ⇒
> ⇒ 判据 = **两侧各自的状态行 + body 字节 + 时延区间都要冻结**（差异形状变了 ⇒ 判失败；差异消失了 ⇒ 也判失败）
> ⇒ ⇒ **已登记 ≠ 豁免**（「白名单是更严的断言，不是免责清单」）。
> ⚠️ **C 侧的失败形状是三候选集合**（实测四轮命中过全部三种）⇒ 见 **§六·6.4**。

> ⚠️ **另有 3 条「自检闸门」**（不是对拍断言，是**「探针自己没退化」的前置条件**）——
> **数据面闸门**（数据面没跑起来 ⇒ `die exit 2`，不给结论）、**`top_k` 边界自检**
> （`top_k=20` 与 `top_k=100` 的响应字节必须不同）、**解析器自检**（`http_probe_selftest.py`）
> ⇒ 详见 **§六**。

### M1 的用例清单

| 用例 | 覆盖什么 |
|---|---|
| `empty_hot_set` | `hot:articles` **不存在** ⇒ 返回**空 items、不报错**（`items:[]` 而非 `null` / 5001） |
| `default` | 默认分页（page=1 / page_size=20） |
| `page1_size2` / `page2_size2` / `page3_size2` | **rank 跨页连续**（第 2 页首条 rank = 3，不是 1） |
| `page20_out_of_range` | 越界页（page=20 · 库里 29 条 ⇒ 每页 2 条 ⇒ 第 20 页越界）⇒ 空 items、`total` 仍是被过滤后的总数 |
| `page_zero` / `page_negative` | `page <= 0` ⇒ 归一为 1 |
| `page_size_zero` | `page_size <= 0` ⇒ 归一为 20 |
| `page_size_over_max` | `page_size > 50` ⇒ 截断到 50 |
| `page_not_a_number` | 解析失败 ⇒ **5001 信封**（HTTP 仍 200） |
| `page_int32_overflow` | 超出 int32 ⇒ 解析失败 ⇒ 5001 |
| `page_multi_value` | 同名多值 ⇒ **取首值**（不是 Spring 那样拼成 `"1,5"`） |
| `page_array_notation` | `?page[]=2` ⇒ 剥掉 `[]` 后缀（`relaxed-query-chars`） |
| `malformed_json_body` | 畸形 JSON body ⇒ 5001（**用 GET 带 body**：两侧的判据都是 `ContentLength > 0 && Content-Type 含 application/json`，与 method 无关） |
| `json_body_not_object` | JSON 合法但**顶层不是对象** ⇒ 5001 |
| `unknown_path` | 404 ⇒ **裸状态码 + `text/plain` + `nosniff`**（无信封） |
| `method_not_allowed` | 405 ⇒ 裸状态码 + `Allow`（无 body） |

### M2 的用例清单（`hot.HotService`）

| 用例 | 覆盖什么 |
|---|---|
| `wire_topk_20` / `wire_topk_100` | 正常 top_k |
| `wire_topk_0` | **proto3 默认值不上 wire** ⇒ 请求体是**空的**（0 字节） |
| `wire_topk_neg1` | **int32 负值按 64 位符号扩展编码** ⇒ 10 字节 varint |
| `wire_topk_101` | `k > 100` ⇒ 截断到 100 |
| `wire_like_article` / `wire_coin_article` / `wire_share_article` | 3 × `UNIMPLEMENTED` ＋ **description 文案逐字节** |
| `wire_comment_article` | 唯一**不对称**的那个 ⇒ `success=true` |

### 播种数据的**四个 skip 分支触发器**

`hot:articles` 播 **33 个成员**（两侧逐条相同）＝ **8 条基础成员** ＋ **25 条填充成员**：

| 成员 | 分数 | 触发的分支 | 桩的行为 |
|---|---|---|---|
| `probe-a1` / `probe-a2` | 100 / 90 | —（正常条目） | 正常返回 |
| `probe-a3` | **3000000000** | —（> 2^31）⇒ 测 **uint32 的无符号还原** | 正常返回 |
| `probe-tags` | 80 | —（`secondary_tags` 非空 ⇒ 数组而非 `null`） | 返回 2 个 tag |
| `probe-draft` | 70 | **④ `article_not_published`** | `status=DRAFT` |
| `probe-missing` | 60 | **③ `article_missing`** | 返回空响应 |
| `probe-rpcerr` | 50 | **② `article_rpc_error`** | 返回 gRPC 错误 |
| `""`（空成员） | 40 | **① `empty_article_id`** | —（hot 侧自己就跳过了） |
| `probe-fill-01` … `probe-fill-25` | **1 … 25** | —（填充） | 正常返回 |

⇒ 正常条目只有 4 条（`a3` / `a1` / `a2` / `tags`）⇒ **`total` = 33 − 4 = 29**。

⚠️ **填充成员的分数（1..25）是刻意压低的** —— 空成员的分数是 40 ⇒ 填充成员**不进入前 4 名**
⇒ ⇒ 「rank 跨页连续」「前 4 名顺序」这些断言**不会被填充成员打乱**。
⚠️ **填充成员同时是 `top_k` 边界的必要条件**：库里要有 **> 20 条**，`top_k=20` 与 `top_k=100`
才会返回**不同条数** ⇒ 「`k<=0 ⇒ 20`」与「`k>100 ⇒ 100`」两条 clamp 契约才**验得到**
⇒ 详见 **§六**（这是本探针自己踩出来的第二个判据空洞）。

---

## 五、比对口径（**比什么 / 不比什么**）

**比**：
- HTTP：**原始状态行**（逐字节）＋ `Content-Type` ＋ body 的 hex。
  - ⚠️ **用裸 socket 而不是 curl**：curl 的 `-w '%{http_code}'` **只给状态码，不给状态行** ⇒ 而本域关心的正是「404 的状态行长什么样」。
  - ⚠️ **两侧的状态行实测同为 `HTTP/1.1 404 `（尾部一个空格，**不带 reason phrase**）** ——
    B 侧是 **Tomcat 11.0.24**、C 侧是 **Tomcat 10.1.52** ⇒ **两个版本都不写 reason phrase** ⇒ 逐字节相同。
    （⚠️ 曾把 B 侧误当成 Go `net/http` 真源 ⇒ 得出「两侧状态行不同」的**错误结论** ⇒ 见台账 F-09 的更正。）
- gRPC：**响应体的原始字节**（成功时）或 **status code ＋ description 的字节**（失败时）。
- Redis：`TYPE` / `ZCARD` / `ZRANGE ... WITHSCORES` 的完整输出。

**不比**（**刻意排除**，理由写在脚本注释里）：
- HTTP 的 `Date` / `Server` 等**易变头** ⇒ 每请求都变 ⇒ 恒红。
- Redis 的 **TTL 相等性**（⚠️ **不是「≤5 秒容差」，而是「根本不相等」**）：
  合并出来的 `hot:articles` 带 `EXPIRE 3600`，而 TTL 是**从「该侧自己那次合并」起算**的剩余秒数
  ⇒ 两侧的合并 tick 差几秒 ⇒ **两侧 TTL 天生不相等** ⇒ 「相等比较」与「容差比较」**都会假红**。
  ⇒ ⇒ **M3（手工播种的 key）只报告 TTL、不判定**；**M4（合并后）只断言「确实设了 EXPIRE 且落在 `(0, 3600]`」**。
- HTTP 的**耗时**（单独打印，不参与判定；耗时受两侧拓扑差异影响 —— 见「覆盖边界」）。

---

## 六、探针**自己**踩过的三个判据空洞（**「全绿」≠「验到了东西」**）

> ⚠️ **这一节是本探针最值钱的部分**：探针**自己**也会有洞 ⇒
> ⇒ 「31 条全绿」**不能**直接当成「行为一致已证」。
> ⇒ 前两个洞是**被变异验证逼出来的**（AGENTS.md §3.3.3）；第三个洞是**被新增的超时面逼出来的**。

### 6.1 数据面空洞 —— 第一轮的 HTTP 面**全在比两个空壳**

| 项 | 内容 |
|---|---|
| 现象 | 第一轮 E-4 跑出来 **「31 条全绿」** ⇒ 但**读数里 `items:[]`、`total:0`** ⇒ **两侧都在比「空热榜」** ⇒ 那是**假绿** |
| 根因 | `run.sh` 的 `free_probe_ports`（会 `kill -9` 占用端口的进程）**在 article-rpc 桩启动之后**才被调用 ⇒ **把自己的桩踢掉** ⇒ 两侧的补全**整片 skip**（`article_rpc_error` 分支）⇒ 热榜恒空<br>⇒ ⚠️ **wire 面读的是 ZSet（仍有 33 条）⇒ wire 面的「有数据」掩盖了 HTTP 面的空洞** |
| **怎么发现的** | 🚨 **变异验证的 V4**：「只给 B 侧多播一个成员」⇒ **预期** `default` 会红（两侧 items 不同）⇒ **实得绿** ⇒ ⇒ **暴露了 HTTP 面根本没在读数据** |
| 修法 ① | `free_probe_ports` 移到**桩启动之前** |
| 修法 ②（更关键） | 加**数据面闸门**：播种后断言「**C/B 两侧返回的 `total` ≥ 期望存活条数**（33 − 4 = 29）」⇒ 数据面没跑起来 ⇒ **直接 `die(exit 2)`，不给结论** |
| ⚠️ **闸门为什么用「≥」而不是「==」** | 判据只管「**数据面在不在**」，**不管「两侧一不一样」** ⇒ 必须容许 V4 那种**刻意不对称**的播种（B 侧 `total`=30、C 侧 29）⇒ 若用 `==` ⇒ V4 会被闸门误判成「数据面没跑起来」⇒ **误 die**。<br>⇒ 「两侧一不一样」由 **M1/M2/M3 三面的逐字节比对**负责 ⇒ **两个职责不许混进同一个判据** |

### 6.2 `top_k` 边界空洞 —— 两条 clamp 契约**根本没验到**

| 项 | 内容 |
|---|---|
| 现象 | 种子只有 8 条 ⇒ `top_k` 取 20 / 0 / -1 / 100 / 101 **返回的是同一批 8 条** ⇒ 响应字节**完全相同** ⇒ 「`k<=0 ⇒ 20`」与「`k>100 ⇒ 100`」**两条契约验不到** ⇒ 那是**判据空洞**（不是「跑绿」） |
| 修法 ① | 种子补 **25 条填充成员**（分数 1..25，**低于**空成员的 40 ⇒ 不打乱前 4 名的 rank 连续性断言）⇒ 库里 33 条 ⇒ `top_k∈{20,0,-1}` 返回 **20** 条、`top_k∈{100,101}` 返回 **33** 条 ⇒ **两条边界各自显形**（实测响应行长 **717 vs 1212**） |
| 修法 ② | 加**自检断言**：`top_k=20` 与 `top_k=100` 的响应字节**必须不同** ⇒ 否则直接报红 ⇒ **种子退化了不会默默「全绿」** |

### 6.3 🔴 解析器空洞 —— 慢请求的响应**前面带 1xx** ⇒ 整份读数错位（**由 M5 面逼出来**）

| 项 | 内容 |
|---|---|
| 现象 | M5 面第一次跑，两侧读数是**一长串 hex**（状态行取到了 `103 Early Hints`）⇒ 被**场景有效性闸门**判成非法读数 ⇒ **exit 2 · 不给结论**（✅ 闸门按设计生效） |
| 根因 | 共享层的 `ClientDisconnectProbe` 会**直接往 socket 写** `HTTP/1.1 103 Early Hints\r\n\r\n` 作断连探针（**行数随 socket 写时机变**）⇒ **慢请求**（M5 要 2~3s）的响应流**前面带若干个 1xx 块**；而 `http_probe.py` **按第一个 `\r\n\r\n` 切分** ⇒ 把 1xx 当最终响应 ⇒ **状态行 / CT / body 三项全错位** |
| 为什么以前没暴露 | M1 的请求都是**毫秒级**返回 ⇒ 探针一个 1xx 都没写出来 ⇒ 解析器「恰好对」⇒ ⇒ **缺陷只在慢请求上显形** ⇒ 而本域此前**没有慢请求用例** |
| 修法 ① | 解析器改成「**跳过所有 1xx、只取最终响应**」（RFC 9110 §15.2）⇒ 1xx 块后面紧跟下一个块 ⇒ 循环里把「本块 body 起点」当下一块起点 |
| 修法 ② | **把解析器提成有自检的判据**：`http_probe_selftest.py` 用**合成字节流**喂 5 类形态（普通 / 1xx 前缀 / 1xx 前缀 + chunked / 无响应 / `100 Continue`）⇒ **每次探针运行都执行**（挂在 step 3 · **起服务之前**）⇒ ⇒ 解析器回退 ⇒ **在起服务之前就死**（而不是跑到一半拿着错读数给结论） |
| 附带（探针自己的第二个错） | M5 面的字段索引**整体错位一格**（读数格式 `TIMEOUT\|<状态行>\|<CT>\|<body hex>\|<耗时>` ⇒ 第 1 段是字面量 `TIMEOUT`，**不是数据**）⇒ 症状与 ① **同型**（形状对、字段乱）⇒ ⇒ 两者都被**场景有效性断言**拦下 ⇒ ⇒ **这正是那条断言的价值：它不问你「对不对」，只问「这还是不是我能读的数」** |

### 6.4 🔴 C 侧失败形状**非确定**（三候选）⇒ 「已登记的差异」必须**冻结成集合**

| 项 | 内容 |
|---|---|
| 现象 | M5 面（`CLIENT PAUSE` 让两侧 Redis 卡住）⇒ 两侧**本来就该不同**（F-02）⇒ ⇒ **「允许不同」不等于「怎么都行」** ⇒ ⇒ 必须把**两侧各自的形状**冻结 |
| 实测 | 四轮跑出**三种** C 侧形状：① `200` + `application/json` + 5001 信封（干净）② `200` + `application/json` + body `Request Timeout\n`（**混血** ⇒ **信封契约被打断**）③ `503` + `text/plain` + `Request Timeout\n`（干净） |
| 根因 | **共享层**的「HTTP 预算 × 业务失败」**竞态**：容器线程的超时分支与 worker 的业务异常分支**并发写同一个响应**（`ObservabilityResponseWriter` 的 body 走捕获缓冲、`flushBuffer()` 只 commit 响应不带 body、超时分支直写底层）⇒ ⇒ 谁先落定决定形状 ⇒ ⚠️ **C 侧的失败时延被 F-02 推到了 3s 预算线上 ⇒ 正好落进这个窗口**；B 侧有 2s 出向封顶 ⇒ **永远命中不到** |
| 归属 | 🔴 **共享层 `common/observability/`** ⇒ **修法属收口域（`global`）** ⇒ 本域**只登记**（DRIFT 台账 **F-15** · Pitfalls **P-HOT-09**） |
| 判据怎么写 | C 侧形状 = **三候选集合**（①/②/③）⇒ 不在集合里 ⇒ 判失败 ⇒ ⇒ **形状变了判失败 · 差异消失也判失败** |

⇒ ⇒ **三个洞都补完之后**，探针才具备「全绿 = 真的验到了」的前提；
⇒ ⇒ 再用 **五条变异**（§八，含两条**探针自身**的）守住这层前提。

---

## 七、覆盖边界（**本探针不覆盖什么** —— 不许拿「全绿」当「全对」）

| 不覆盖 | 为什么 | 影响 |
|---|---|---|
| **Go 真源** | 本机无 Sea-etcd 容器 ⇒ Go 侧无法活体 | 本探针是 **E-4**，**不是**「三方一致」 |
| **出向 2s 上界的时序形态** | B 侧是 **api → rpc 两跳**（客户端预算先到）；C 侧是 **D2 域内直连** | **M5 面已把这条测掉**（2026-11-04）：B @2011ms / C @3014ms ⇒ **失败形状也不同**（⇒ DRIFT **F-02** 的定性升级）⇒ ⚠️ 但 **M5 只比形状 + 时延**，**不证**「失败原因相同」（两侧的失败根因结构上就不同：B 卡在 hot-rpc 的 gRPC 上） |
| **M5 不覆盖：竞态窗口的**分布** | C 侧三形状由**毫秒级抖动**决定 ⇒ 四轮命中过全部三种 | **给不出概率**（要估需上百轮）⇒ ⇒ 形状按**集合**冻结，**不做频率断言** |
| **M5 不覆盖：F-15 的波及范围** | 只在 hot 域复现 | 「是否只有 hot 域会命中」**未证伪** ⇒ 归**收口域** |
| **多 key 的 HeavyKeeper 分数** | HeavyKeeper 是**有损概率 sketch**：桶冲突时按 `decay^count` **概率**让位（两侧**都是**随机 —— B 侧 `HeavyKeeper.java:138` 用 `ThreadLocalRandom`，它自己的 javadoc:31 就写着「随机分支不可断言精确值」）⇒ 多 key 场景下两侧分数**天生不可复现** | M4 用**单 key**（每个分区里该 key 的 5 行计数都只走「指纹匹配累加」分支 ⇒ 分数 = 事件总条数，**确定性**）⇒ 字节可比 |
| **Kafka 的分区分布** | 生产端的分区器决定事件落到哪个分区 ⇒ 两侧的**分片分数**必然不同 | M4 只比**合并后**的 `hot:articles`（= 各分片之和 ⇒ 与分布无关） |
| **响应头全集** | `Date` / `Server` / `Set-Cookie` 等易变 | 只比状态行 + `Content-Type` + body |
| **压测 / 并发** | 单请求串行 | — |

---

## 八、变异验证（**证明探针不是假绿**）

```bash
bash tools/difftest/hot/mutate-parity.sh
```

五条变异，每条**先定判据、再看结果**（AGENTS.md §3.3.3「变异没变红 = 没覆盖到」）。
**2026-11-04 实跑：五条全部按预期表现 + 还原后基线复跑 `EXIT=0`、红名单为空。**

| 变异 | 改动位置 | 期望变红 | 期望**保持绿** | 实跑结果 |
|---|---|---|---|---|
| **V1** | `HotApiSupport.DEFAULT_PAGE_SIZE` 20 → 21 | M1 `page_size_zero` | M1 `default`（**缺省值写在 controller 里**，不是这个常量）· `page_size_over_max` · wire | ✅ 退出码 1 · 红名单 = {`page_size_zero`} |
| **V2** | `InteractionStubService.LIKE_UNIMPLEMENTED_MSG` 追加 `!` | M2 `wire_like_article` | M1 `default` · wire `wire_comment_article` | ✅ 退出码 1 · 红名单 = {`wire:wire_like_article`} |
| **V3** | `HotApiSupport.ROLLING_SCOPE` `"rolling"` → `"rolling_v2"` | M1 `default`（`scope` 字段） | M1 `page_not_a_number` · wire（message 里没有 scope 字段） | ✅ 退出码 1 · 红名单 = {`default`} |
| **V4** | **探针自身**：只给 B 侧多播一个成员（`PROBE_SEED_ASYMMETRIC=1`） | M3 `redis:hot:articles` ＋ M1 `default` ＋ M2 `wire_topk_20` | M2 `wire_like_article`（互动桩不看种子） | ✅ 退出码 1 · 红名单 = {`redis:hot:articles`, `default`, `wire:wire_topk_20`} |
| **V5** | `HotRedisSnapshotStore.HOT_ARTICLES_KEY` 改键名 | —— **不是「报红」，是数据面闸门 `die(exit 2)`** | —— | ✅ 退出码 **2** ＋ 输出含「数据面没跑起来」 |

⚠️ **V1 是「读代码 ≠ 读行为」的例子**：`DEFAULT_PAGE_SIZE` 在 HTTP 路径**几乎不可达**
（controller 的缺省值是**字面量 20**）⇒ 只有 `?page_size=0` 那条能触发它 ⇒
⇒ **「这个常量被谁用」不能靠读代码下结论**。

⚠️ **V4 是「M3 判据不是摆设」的证据**：两侧种子相同时，若探针读错了容器 / 读错了 key，
读数也会**相等** ⇒ 判据空转 ⇒ ⇒ 只给一侧加一个成员 ⇒ 读数必须不等。

⚠️ **V5 是「探针不会在数据面没跑起来时给结论」的证据**（正是 §六·6.1 那条洞的**回归守卫**）：
key 改名 ⇒ C 侧读不到热榜 ⇒ `total=0` ⇒ 数据面闸门必须 `die` ⇒
**若探针此时报「全绿」，那就又回到了「两个空壳相等」的假绿**。

---

## 九、本轮探针**实际抓到**的问题

| # | 发现 | 处置 |
|---|---|---|
| 1 | **C 侧 `service/hot/pom.xml` 的 `io.grpc:grpc-inprocess` 写成了 `test` scope** ⇒ fat jar 的 `BOOT-INF/lib` 里**没有**该 artifact ⇒ devh 的 `shadedNettyGrpcChannelFactory` 链接 `InProcessChannelFactory` 失败 ⇒ `NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder` ⇒ **真 jar 起不来**（`mvn test` 却 **70/70 全绿** —— 测试跑在 test classpath 上 ⇒ 缺陷看不见） | 已修（scope 改回 compile）· 与 like 域 2026-11-03 的同款修复**逐条对齐**（B 出处 = B 侧 `service/like/like-rpc/pom.xml:313-344`） |
| 2 | **探针自己的两个判据空洞**：① 数据面空洞（HTTP 面全在比两个空壳）② `top_k` 边界空洞（两条 clamp 契约根本没验到） | 已修（见 **§六**）⇒ 并各配一条**自检闸门**（数据面闸门 / `top_k` 边界自检）+ 五条**变异**做回归守卫（见 **§八**） |
| 3 | **探针自己的第三个洞**：**HTTP 解析器不跳过 1xx** ⇒ 慢请求的读数**整份错位**（M1 全是毫秒级请求 ⇒ 恰好没触发） | 已修（**跳过所有 1xx** · RFC 9110 §15.2）⇒ 并把解析器提成**可执行自检**（`http_probe_selftest.py` · 挂在起服务之前）（见 **§六·6.3**） |
| 4 | 🔴 **共享层的「HTTP 预算 × 业务失败」竞态** ⇒ 客户端可能收到**混血响应**（`200` + `application/json` + body `Request Timeout\n`）⇒ **信封契约被打断**，且**形状非确定**（三候选） | **归属共享层 ⇒ 本探针只登记、不改**（DRIFT **F-15** · Pitfalls **P-HOT-09**）⇒ 探针侧：形状**冻结成三候选集合**（见 **§六·6.4**）· 修法待**收口域（`global`）**裁决 |

⇒ ① 是 AGENTS.md §3.3.3「**跑绿 ≠ 本来就绿**」的教科书形态，**发现者就是本探针**（它起的是**真 jar**）。
⇒ ② 是同一条纪律的**另一半**：「**跑绿 ≠ 本来就绿**」——**探针自己**也得被变异验一遍。
⇒ ③ 是「**判据自己也会退化**」的第三种形态 ⇒ **修法 = 把判据提成自检**（而不是「记得小心」）。
⇒ ④ 是「**正常路径全绿 ⇒ 故障路径也干净**」的**反例** —— 只有**故障注入**才看得见 ⇒ 且**归属在共享层** ⇒ 本域**只登记 + 上报**。

---

## 十、文件清单

| 文件 | 职责 |
|---|---|
| `run.sh` | 主脚本：隔离资源 → 构建两侧 → 编译探针 → 起桩与两侧 → **M1/M2/M3/M4/M5** → 收尾复核 |
| `http_probe.py` | **裸 socket** 的 HTTP 字节探针（原始状态行 + Content-Type + body hex；chunked 会先解块；**跳过所有 1xx**） |
| `http_timeout_probe.py` | **带计时**的 HTTP 探针（M5 用；**直接 import 复用** `http_probe.py` 的收发与解块逻辑 ⇒ 口径不分叉） |
| `http_probe_selftest.py` | **解析器自检**（合成字节流 × 5 类形态）⇒ 每次探针运行都执行（step 3 · 起服务之前） |
| `grpc-wire/HotWireProbe.java` | gRPC wire 原始字节探针（手构 proto3 字节，**不用任何 pb 生成码** ⇒ 同一个 jar 跑两侧） |
| `article-rpc-stub/ArticleRpcStub.java` | 两侧共用的 article-rpc 桩（按 article_id 触发四条 skip 分支） |
| `mutate-parity.sh` | 变异验证（五条变异 ⇒ 证明探针有牙齿） |
| `README.md` | 本文件 |

> ⚠️ **端口段 18221-18232**（follow 占 18201-18209 · like 占 18211-18219）。
> ⚠️ **Nacos 命名空间 `hotprobe`** —— 探针自己建，别的 lane 不受影响。
