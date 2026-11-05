# favorite 域 · **双侧活体对拍**探针（C 侧 RAG-V1 ↔ B 侧 RAG · 第三方锚 = Go 真源冻结字节）

> **一句话**：把「favorite 域 **🔴 未做**双侧活体对拍」变成「**✅ 已验**」——
> **整个 HTTP 面**：路由 / method / JWT 中间件 / 黑名单中间件 / **gRPC 往返（业务码靠 description 反查）** /
> 状态码 / Content-Type / **响应体逐字节** / 完整响应头 key 集合 / **写入副作用**；
> **外加整个 gRPC 面（rpc 对 rpc）**：status code / description / **protobuf 响应字节** /
> 响应头与 trailer 的 metadata key 集合；
> **再加一层「场景轮」**（🟠 **只有 B↔C 双侧 · 无第三方锚**）：**create 成功路径 / 并发 / 超时 / 部分成功 / Redis 故障 / 重复请求（幂等重放）**。

```bash
bash tools/difftest/favorite/run.sh
echo $?   # 0 = 三方一致（HTTP + gRPC 面）+ 场景轮全绿；1 = 有差异；2 = 环境问题

# 只跑 gRPC 面（变异验证用；HTTP 面会先红并 exit ⇒ 不跳过就验不到 gRPC 面的牙齿）
SKIP_HTTP=1 bash tools/difftest/favorite/run.sh

# 只跑场景轮（场景轮变异验证用）
ONLY_SCENARIOS=1 bash tools/difftest/favorite/run.sh
```

---

## 一、它补的是哪个缺口

`docs/DRIFT/favorite/verification-favorite-service-2026-11-04.md`
**§8.2 第 1 条**：「🔴 **本域当前无 E-4 证据** ⇒ §二 / §三 的 ✅ 最高只到 **E-3**」。

⇒ 本探针把这条变成 **E-4**。

**为什么这条链值得单独活体对**（本探针的增量价值）：

> favorite 域的**业务码不在 gRPC status 里，而在 `description` 文案里**
> （Go `favoritecommon.BizCodeFromError` 反查 ⇒ B 侧 `FavoriteGrpcError.bizCodeFromError`
> ⇒ C 侧 `GrpcErrorMapper#bizCodeFromError`）。
> ⇒ 「api 的业务码对不对」这件事，**只有真的把请求从 HTTP 打通到 gRPC 再打回来**才能验。

---

## 二、三方是哪三方

```
        ┌──────────── 第三方锚 ────────────┐   ┌──── B 侧活体 ────┐   ┌──── C 侧活体 ────┐
        │ expected.txt（HTTP 面 · 32 case）  │   │ favorite-api     │   │ favorite-service │
        │ = Go 真源 favorite-api 的        │   │ + favorite-rpc   │   │ （D1 合并进程）  │
        │ **活体渲染字节**（2026-10-30 录） │   │ Boot 4.0.8       │   │ Boot 3.5.11      │
        │ ─────────────────────────────── │   │                  │   │                  │
        │ grpc-wire/expected.txt            │   │                  │   │                  │
        │ = Go 真源 favorite-rpc 的        │   │                  │   │                  │
        │ **gRPC 可观测契约渲染值**（36 case）│   │                  │   │                  │
        └──────────────────────────────────┘   └──────────────────┘   └──────────────────┘
                     │                                │                       │
                     └────────── 逐 case · 逐字段 ────┴───────────────────────┘
```

| 侧 | 进程 | 形态 |
|---|---|---|
| **B** | `favorite-api` + `favorite-rpc` | **两个** `java -jar`（Boot 4.0.8 · api/rpc 分离）· 真 Flyway · 真 PG |
| **C** | `favorite-service` | **一个** `java -jar`（Boot 3.5.11 · D1 合并进程）· 真 Flyway · 真 PG |
| **锚** | `expected.txt` ×2 | Go 真源的**冻结字节**（本仓是 B 侧仓原件的**逐字节副本** + sha256 溯源核对） |

### 🔴 为什么**不跑 Go 真源**（与 B 侧探针的唯一结构性差异）

B 侧那套探针起的是 **Go 真源**（服务发现走 **etcd**）
⇒ **本机没有 `Sea-etcd` 容器**（Nacos / PG / Redis 在跑，etcd 不在）⇒ Go 侧**无法活体**
⇒ 改用 B 侧**已冻结**的 `expected.txt`（Go 字节）作第三方锚。

⇒ ⇒ **三方等价的强度不降**：本探针判 **C == B == Go 字节**
⇒ 与 B 侧原探针判 **Go == B == Go 字节** 在信息量上**等价**
（都是「C 侧的每个字节 == 真源的每个字节」，B 侧只是中间的一层共识）。

---

## 二之二、gRPC 面（rpc 对 rpc）—— **两个面各有自己的 Go 冻结锚**

> **HTTP 面的 JSON 字节一致，推不出 gRPC wire 字节一致**
> （proto 编码 / 字段号 / status description / metadata key 全是另一套面）
> ⇒ ⇒ gRPC 面**单独一轮**三方对拍，**不是** HTTP 面的复述。

### 探针（`grpc-wire/FavoriteWireProbe.java`）

| 设计 | 为什么 |
|---|---|
| **不用任何 pb 生成码**（手构 proto3 字节 + `byte[]` marshaller） | 两侧 pb 包名**不同**（B 侧 `com.rag.favorite.favorite.proto` / C 侧 `com.rag.proto.favorite`）⇒ 用各自的生成类 ⇒ 两侧跑的不是同一段代码 ⇒ 证据被污染。⇒ **同一个 jar 跑两侧** ⇒ 比的是纯 wire 字节 |
| **classpath 从 C 侧 fat jar 的 `BOOT-INF/lib` 解出来** | 「探针量到的是哪一版依赖」本身就会成疑点 ⇒ ⇒ 依赖**唯一来源** = 被测产物 |
| **`forAddress(new InetSocketAddress(host,port))`** | devh 的 `DiscoveryClientResolverFactory#priority()` = 6（> DNS 的 5）⇒ 抢走全局默认 scheme ⇒ 字面地址被当服务名去查 ⇒ 报 `UNAVAILABLE: No servers found` ⇒ **长得像「服务端没起来」**。⇒ 直连地址绕开服务发现 |
| **`fullMethodName` 不带前导斜杠** | grpc-java 的 `generateFullMethodName` = `service + "/" + method`（**不**补斜杠），netty 客户端写 `:path` 时**自己补一个** ⇒ 手写成 `"/favorite.…"` ⇒ 线上变双斜杠 ⇒ grpc-go 只剥一个 ⇒ 误落到「unknown service」分支 ⇒ **措辞被探针自己污染** |
| **矩阵 + 渲染函数逐字对齐 B 侧探针** | 冻结锚（Go 字节）才能**直接**比对 ⇒ 改了渲染 ⇒ 锚立刻失配（这正是「改了就红」） |

### 36 个 case

| 组 | 用例 | 验什么 |
|---|---|---|
| **入参与下游** | c01–c09 | 4 条收藏夹 + 5 条收藏：userId<=0 / folderId<=0 / name 空 / name 空白 / target 空 / target_type 空白 / **user-rpc 缺席 ⇒ INTERNAL 5001** |
| **只读成功** | c10–c18 | 收藏夹列表（**顺序 + 时间毫秒逐字节**）/ 空集合 / 条目列表 / 收藏夹不存在 5201 / 归属不符 5005 |
| **删除 / 改名** | c19–c29 | 入参 5003 / 不存在 5201·5301 / 归属不符 5005 / **同名不写库** |
| **写入组** | c30–c33 | **完整响应头面** / 删收藏夹 / 删收藏（5304 收藏暂不可撤回）/ 改名 |
| **服务面** | c34–c36 | `grpc.health.v1.Health/Check`（两侧都注册 ⇒ SERVING）/ 未知方法 / 未知服务 |

⚠️ **用例顺序 = 副作用顺序** —— 只读全在前、写入全在后。
🔴 **c30 必须排在 c33 之前**（c33 改名 folder 9001 ⇒ 推后 `updated_at` ⇒ c30 的字节每次都变 ⇒ 假红）。
🔴 **gRPC 面之前必须重灌种子** —— HTTP 面的 `w01/w02/w03` 已经改过库了（改名 9002 / 删 7001 / 删 9002）
⇒ ⇒ `run.sh` 在 gRPC 面之前**清空 + 重灌同一份种子** ⇒ 两侧回到同一个起点。

### 🔴 **两段判据**（不是一个 kind 判三方）

| 段 | 判什么 | 档位表 | 档位含义 |
|---|---|---|---|
| ① **锚校验** | Go 冻结锚 ↔ **B 侧活体** | `grpc-wire/expected.txt` 自带的 kind 列 | 「**Go ↔ Java**」：SAME / SAME_CODE（措辞可不同）/ OBS（**必须不同** ⇒ 是已登记的库级差异） |
| ② **BC 校验** | **B 侧活体 ↔ C 侧活体** | `grpc-wire/kinds-bc.txt`（本仓自撰） | 「**Java ↔ Java**」：SAME / SAME_CODE / OBS（**C 侧已登记偏离**）/ SKIP |

> 🔴 **为什么必须分两张表**：同一个 kind 在两段里**含义不同**。
> 锚里的 `c30_response_header_surface` 是 **OBS**（Go ≠ Java）⇒ **Java 侧之间反而应该相等**；
> 拿锚的 OBS 去判 B↔C 会**判反**。
>
> **实测（2026-11-05）**：锚里 c30 的 Go 完整头集合 = `content-type=application/grpc`，
> 而两个 Java 侧 = `content-type=application/grpc,grpc-accept-encoding=gzip,grpc-encoding=identity`
> ⇒ ⇒ 「Go ≠ Java」成立（锚 OBS 段 ✅ 通过）+「Java == Java」成立（BC 段 SAME ✅ 通过）。

**实测读数（2026-11-05）**：

```
① 锚校验（Go 冻结锚 ↔ B 侧活体）：SAME 33 / SAME_CODE 2 / OBS 1 / SKIP 0（共 36 个 case）
② BC 校验（B 侧活体 ↔ C 侧活体）：SAME 36 / SAME_CODE 0 / OBS 0 / SKIP 0（共 36 个 case）
✅ favorite 域 gRPC 级可观测契约三方一致
```

⇒ **C 侧的 gRPC 可观测契约与 B 侧 36/36 逐字节全等**（含 description 文案 = 业务码载体）。

---

## 二之三、场景轮（6 类场景 · **B↔C 双侧活体 · 无第三方锚**）

> **一句话**：把「create 成功路径 / 并发 / 超时 / 部分成功 / Redis 故障 / 重复请求（幂等重放）」从「**只有 E-3**」变成「**有双侧活体证据**」。

### 🔴 为什么必须**另起一轮**（不能靠矩阵推）

| 场景 | 32 case 矩阵为什么**结构性**覆盖不到 |
|---|---|
| **create 成功路径** | 矩阵里 user-rpc **故意缺席** ⇒ `ensureUserExists` 必失败 ⇒ 建夹落「下游不可用」5001 |
| **并发** | 矩阵是**串行**的 ⇒ 碰不到「先查重后插入」的竞态 |
| **超时** | 下游缺席 = **立即失败** ⇒ 压根不触发**出向预算** ⇒ 测不出「两侧预算同不同」 |
| **部分成功** | 矩阵没有「多步写在中途失败」 |
| **Redis 故障** | 矩阵里 Redis 一直在 ⇒ **fail-open 那条分支**没测过 |
| **重复请求（幂等重放）** | 矩阵是**单请求**的 ⇒ ⇒ 同一个请求发两次会得到什么，**测不到**（verification.md §九 明列「幂等和重复请求」且「对拍必须覆盖：重复请求」） |

⇒ ⇒ 场景轮**必须重启两侧**并把下游改指**桩** ⇒ ⇒ 且**下游必须在场** ⇒ ⇒ 与矩阵的「下游缺席」**不能共存**。

### 🔴 判据 = **绝对断言 + 两侧对称（两条都要过）**

> **只比两侧相等是假绿** —— 两侧**都坏**也算相等 ⇒ ⇒ 每个场景都另给一条**绝对断言**（期望的行为是什么）⇒ ⇒ 两侧对称 **AND** 绝对断言 ⇒ 才算过。

### 六个场景的实测读数（2026-11-05）

| 场景 | 故障注入 | 绝对断言 | 两侧对称 | 读数 |
|---|---|---|---|---|
| **S1 · create 成功路径** | 桩 `mode=normal`（下游在场） | biz=**200** · 响应含**正的 folderId** · **库里真落 1 行** | 响应体（id 归一化后）**逐字节相同** | ✅ 两侧 biz=200 · 各落 1 行 · 体逐字节相同 |
| **S2 · 并发** | 8 个**同名**建夹**同时**发起 | 分布 **`200:1,5203:7,`** · 成功体 1 种 · 失败体 1 种 · 库里**恰好 1 行** | 两侧**分布相同** | ✅ 两侧 `200:1,5203:7,` · 各 1 行 |
| **S3 · 超时** | 桩 `mode=slow, slow_ms=5000` | biz ≠ 200 · 耗时桶 **≥ 1000ms** | 两侧**耗时桶相同** + 两侧**业务码相同** | ✅ 耗时桶**两侧相同** `2000-2499` ⇒ **出向上界同值**<br>✅ **业务码两侧同为 `1015`**（`CODE_SERVER_BUSY` · 文案「服务繁忙」逐字一致）⇒ ⇒ 🟠→🟢 **DRIFT-FAV-014 已修**（方向 ② · 台账 §五·5.10） |
| **S4 · 部分成功** | `favorite_fact_outbox` 挂 **BEFORE INSERT 触发器**（两侧各挂）⇒ `POST /item/create` 的**第 2 步**被打断 | 第 1 步写的 item **必须回滚** ⇒ `favorite_item` 行数 **= 0** | 两侧**业务码相同** | ✅ 两侧 biz=5008 · 各 0 行（**无半截状态**）· 恢复轮各恰好 1 行 |
| **S5 · Redis 故障** | `docker stop` 探针**自带**的 Redis（两侧**共用** ⇒ 天然对称） | —— | 两侧 status 与 biz 都相同 | ✅ 两侧 **status=200 / biz=200** ⇒ **fail-open** · 恢复轮正常 |
| **S6 · 重复请求（幂等重放）** | **同一个请求连发两次**（三个子场景各一次） | ① 建夹重放 **200 → 5203**（重名预查）· 库里 `ReplayFolder` **恰好 1 行**<br>② 收藏重放 **200 → 5302**（重复收藏判定）· 库里 `item(90002)` **恰好 1 行**<br>③ **同名改名 200 → 200** 且 **`update_time` 未推进**（⇒ 真·幂等 · 真的没写库） | 两侧**六个业务码全部相同** + **改名响应体逐字节相同** | ✅ 两侧三个子场景全同 · 各**恰好 1 行** · 两侧 `update_time` 均**未推进**（`2026-01-01 19:04:06.789000`） |

> 🔴 **S4 为什么选「写路径」而不是「删路径」**：级联删除要先过 `FavoriteAssertionSchemaResolver`（legacy 分支要 marker 行）⇒ 种子里那两条 item 没有 marker ⇒ **在任何写入之前**就以 5304 失败 ⇒ **断言会空转**（「没留下半截状态」恒真）⇒ ⇒ 改用 `POST /item/create`（写 item + 写 outbox **同一事务**）⇒ ⇒ 真正打到「第 2 步失败」。
> 🔴 **S6 为什么是三个子场景，不是一个**：favorite 域真实存在**三种不同的重复语义** —— ① **重复建夹** ⇒ 第二次撞**重名预查** ⇒ 5203（B `create_favorite_folder_logic.go:75-87`）② **重复收藏** ⇒ 第二次撞**重复收藏判定** ⇒ 5302（B `create_favorite_logic.go:122-134`）③ **同名改名** ⇒ **直接成功、且不写库** ⇒ 200 且 `updated_at` **不动**（B `update_favorite_folder_name_logic.go:91-95`）⇒ ⇒ 前两个是「重复 ⇒ **被拒**且不产生第二条副作用」；**第三个才是真·幂等**（重复 ⇒ 返回同样结果**且无任何副作用**，连 `updated_at` 都不推进）⇒ ⇒ 只测一个都答不出「重复请求」这一行。

### 🟠 证据强度：**只有双侧**，**不得**混称「E-4 三方」

本轮**没有 Go 第三方锚**（本机无 `Sea-etcd` ⇒ Go 真源跑不起来）⇒ ⇒ 这 6 类场景**只有 B↔C 双侧活体**
⇒ ⇒ 强度**低于** 32 case 的「三方」⇒ ⇒ 台账与本 README **都如实标注**。

### ✅ 已知差异登记 = **指纹**，不是豁免

`scenarios/scenarios.py` 的 `KNOWN_DIFFS` 只在 (stage, 字段) **与两侧具体值都逐字相符**时才走 **WARN**；
**值一变 ⇒ 立刻 FAILED** ⇒ ⇒ **它也有牙齿**（不会把「差异变了」静默放过）。

> ✅ **修完 DRIFT-FAV-014（方向 ②）后该表已清空** —— **表结构保留**（注释写明「表是已知差异的指纹，不是豁免」）⇒ ⇒ **现在任何两侧不等都真报红**。

### ✅ 变异验证 —— `mutate-scenarios.sh`（**两轮**）

| 轮 | 变异（**只动 C 侧**） | 期望 | 实测 |
|---|---|---|---|
| **① S2 并发** | `DROP INDEX IF EXISTS uk_user_folder_name` | **红**（EXIT=1）且**精确命中** `S2 C 侧并发结果分布` | ✅ EXIT=1 + 命中该条 |
| ① 额外确认 | —（**只断 B 侧**） | **B 侧断言不得红**（⇒ 证明判据真的在**比两侧**，不是「抓住两侧都坏」） | ✅ B 侧未红 |
| ① 不带变异再跑 | —（**原样重跑**） | **绿**（EXIT=0）**且必须看到** `S2 侧并发结果分布相同` | ✅ EXIT=0 + 看到该标识 |
| **② S6 幂等** | `UPDATE favorite_folder SET name='BetaMutated' WHERE folder_id=9002` | **红**（EXIT=1）且**精确命中** `S6 C 侧 folder 9002 的 update_time 被推进了` | ✅ EXIT=1 + 命中该条 |
| ② 额外确认 | —（**只断 B 侧**） | **B 侧断言不得红** | ✅ B 侧未红 |
| ② 不带变异再跑 | —（**原样重跑**） | **绿**（EXIT=0）**且必须看到** `C 侧 folder 9002 的 update_time 未推进` | ✅ EXIT=0 + 命中该条 |

> 🔴 **只动一侧**：两侧同时坏 ⇒ 只能证明「两侧相等」⇒ 证不了「它在比两侧」。
> 🔴 **必须 `ONLY_SCENARIOS=1`**：变异会让矩阵**先红** ⇒ 抢在前面 `exit` ⇒ 红的就不是被验的那一面 ⇒ 证据不可信。
> 🔴 **「不带变异再跑」还要额外 grep 一个绿色标识**：只断 `EXIT=0` ⇒ 可能「判据根本没跑到」也是 0 ⇒ ⇒ **假绿** ⇒ ⇒ 必须**看到那条断言的绿色输出**才算数。
> 🔴 **② 这轮为什么值得单独配**：它是**唯一**「业务码不变、只有副作用变」的变异 ⇒ ⇒ **六个业务码断言照旧全绿** ⇒ ⇒ 只有「`update_time` 不动」那条能抓住它 ⇒ ⇒ **正好证明那条断言不是恒真**（它在测「有没有写库」，不是「码对不对」）。

---


## 三、矩阵（**32 个 case · 8 组**）

| 组 | 用例 | 验什么 |
|---|---|---|
| **鉴权** | a01–a06 | 无头 / 签名错 / 过期 / 只有前缀 / **小写 bearer** / **被拉黑** |
| **路由** | r01–r03 | 未知路径 404 / 两个 405（method 不匹配）⇒ **404/405 不经鉴权** |
| **解析** | p01–p07 | 非法 JSON / 缺必填 / 类型不对 / 缺 form 参数 / 非数字 / **有 body 但没有 JSON Content-Type** / DELETE 缺 id |
| **业务** | b01–b07 | 穿过 **gRPC 往返**：下游缺席 5001 / 不存在 5201·5301 / 无权限 5005 |
| **只读成功** | s01–s04 | 收藏夹列表 / 条目列表 / 空集合 / 另一个用户的列表（**int64 以字符串出参**、时间戳逐字节） |
| **响应头面** | h01 | 完整响应头 key 集合 |
| **写入** | w01–w03 | 改名 / 删条目（5304 收藏暂不可撤回）/ 删收藏夹 |
| **写入生效验证** | z01 | 写完之后再读一次 ⇒ 验证副作用真的一致 |

⚠️ **用例顺序 = 副作用顺序** —— 只读全在前、写入全在后、最后补一个只读验证写入生效。

### 探针本身（`probe/FavoriteApiParityProbe.java`）

| 设计 | 为什么 |
|---|---|
| **JDK-only 独立程序**（零第三方依赖） | ① B 侧仓**只读** ⇒ 探针只能住 C 侧；② 探针的 classpath **恒为空** ⇒ 不存在「量到的是哪一版依赖」；③ 不受任一侧测试树编译失败影响 |
| **矩阵 + 渲染函数逐字抄自 B 侧探针** | 冻结锚（Go 字节）才能**直接**比对 ⇒ 改了渲染函数 ⇒ 冻结锚立刻失配（这是我们要的「改了就红」） |
| **同一个 `HttpClient` 打两侧** | 换掉客户端，结论就不成立 ⇒ 客户端差异被消掉 ⇒ 只剩服务端差异 |
| **两侧共用同一个 Redis** | 黑名单 key（`user:jwt_blacklist:<token>`）在同一命名空间 ⇒ 顺带验**黑名单 key 的拼法** |
| **两侧两个库（`first_db` / `fav_c_db`）** | 各自的 Flyway 账本 + 各自的种子 ⇒ 互不撞号 |

---

## 四、冻结锚的档位

### 4.1 HTTP 面（锚 = `expected.txt` · 32 case · 五档）

| 档 | 断言 | 用在哪 |
|---|---|---|
| **SAME** | status + Content-Type + body **全等** | 25 条 |
| **SAME_CODE** | status 全等 **且** 业务码全等 | a06（黑名单命中）⇒ 两侧业务码都是 5004，body 形态不同（**已签字** DEV-FAV-API-001） |
| **SAME_BODY** | status 全等 **且** body 全等（**Content-Type 允许不同**） | r02 / r03（405）⇒ B 侧比 Go 多一项 Content-Type（**跨域漂移**，落点在共享面） |
| **SAME_STATUS** | **只**要求 status 全等 | a01–a04（Go 的 401 是**空 body** ⇒ 取不到业务码；形态差异**已签字** DEV-FAV-API-001） |
| **OBS** | 两侧**必须不同** | ⚫ **当前 0 条** —— h01 原为「Go 多一个 W3C `traceparent`」⇒ 🔴 **2026-10-30 已改档 `SAME`**（两侧现在都有）⇒ 改档前会把「B/C 侧少发 traceparent」的回归**静默放过** |

### 4.2 gRPC 面（锚 = `grpc-wire/expected.txt` · 36 case · 三档）

| 档 | 断言 | 用在哪 |
|---|---|---|
| **SAME** | code + desc + body + rhdr + rtrl **全等** | 33 条（锚侧，Go ↔ Java） |
| **SAME_CODE** | **只**要求 status code 相同 | c35 / c36（未知方法 / 未知服务）⇒ Go 的措辞分「method / service」两种，grpc-java **共用一种** ⇒ 属 gRPC 库层措辞 |
| **OBS** | 两侧**必须不同** | c30（完整响应头面）⇒ Java 多 `grpc-accept-encoding` / `grpc-encoding` ⇒ **库级差异**，变成一样反而是回归 |

> ⚠️ **副本会漂** ⇒ `run.sh` 每次运行前核对 **B 侧原件**的 sha256
> ⇒ 漂了就退出 ⇒ ⇒ **不会拿一份陈旧的冻结值判「三方一致」**。
>
> 🔴 **gRPC 面另加第二道核对**（HTTP 面**没有**这一道）：
> 本仓副本的**数据行**必须与 B 侧原件**逐行相同**（`grpc-wire/check-anchor-copy.py`）
> ⇒ ⇒ **改了本仓副本里的冻结值**也会被抓住 ⇒ ⇒ 不会拿「被人改过的冻结值」判「三方一致」。


---

## 五、变异验证（**判据有牙齿**）

### 5.1 HTTP 面 —— `mutate-parity.sh`

变异 C 侧 `FavoriteFolderMapper` 的 `ORDER BY create_time DESC` ⇒ `ASC`
⇒ ⇒ **收藏夹列表顺序反转**（9001 跑到 9002 前面）⇒ ⇒ **只有逐字节比 body 才抓得到**。

| 步骤 | 期望 | 实测 |
|---|---|---|
| 变异后跑 | **红**（EXIT=1）且**精确命中** `s01_list_folders` | ✅ EXIT=1 + 命中该条 |
| 还原后跑 | **绿**（EXIT=0） | ✅ EXIT=0 |
| 退出前 | **净改动 = 0** | ✅ `git diff` 无变异残留 |

> 🔴 **这一轮踩到的两个 harness 缺陷（已修，且值得记住）**：
> ① **变异锚点必须在 javadoc 里唯一** —— `ORDER BY create_time DESC` 这串在 javadoc 里也出现一次
>    ⇒ 只按字符串匹配会命中 **2 次** ⇒ **变异没生效**；
> ② 🔴🔴 **变异失败必须中止** —— 上一版 python 断言失败后脚本**继续跑对拍**，
>    结果「没红」被当成「判据恒绿」⇒ **结论方向完全反了**。
>    ⇒ 现在：锚点命中数 ≠ 1 ⇒ **中止**；且**回读确认**后才继续。

### 5.2 gRPC 面 —— `mutate-grpc-parity.sh`（**同一个变异点，验的是另一套判据**）

| 步骤 | 期望 | 实测 |
|---|---|---|
| 变异后跑（**`SKIP_HTTP=1`**） | **红**（EXIT=1）且命中 `c11_list_folders_ok` **且**红在「BC 校验」段 | ✅ EXIT=1 + 命中该条 + 日志含「BC 校验」 |
| 还原后跑（`SKIP_HTTP=1`） | **绿**（EXIT=0） | ✅ EXIT=0 |
| 退出前 | **净改动 = 0** | ✅ SQL 行已回 `DESC` |

> 🔴 **为什么必须 `SKIP_HTTP=1` 单独跑**：
> 同一个变异**也会让 HTTP 面变红** ⇒ HTTP 面在 `run.sh` 里**先**跑、**先** `exit(1)`
> ⇒ ⇒ 不跳过就**永远走不到** gRPC 面 ⇒ ⇒ **gRPC 面的牙齿就永远没被验过**。
> ⇒ ⇒ 脚本额外断言「日志里出现『BC 校验』字样」⇒ ⇒ 防止「红」其实来自别的原因。


---

## 六、本轮**没有**覆盖的（**证据边界**，必须连着结论一起引用）

| 项 | 状态 | 说明 |
|---|---|---|
| **create 成功路径** | ✅ **已补（场景轮 S1）** | 原为「🔴 未验」⇒ 场景轮把下游**指到桩** ⇒ 两侧 biz=200 · 各落 1 行 · 体逐字节相同 |
| **真源 user-rpc / article-rpc 在场** | ✅ **已补（场景轮）** | 原为「🔴 未验」⇒ 场景轮 S1/S2/S4 都跑在「下游在场」路径上 |
| **Redis 故障 / 变慢** | ✅ **已补（场景轮 S5）** | 原为「🔴 未验」⇒ 停 Redis ⇒ 两侧 status=200 / biz=200 ⇒ **fail-open** |
| **并发 / 超时 / 部分成功 / 重复请求** | ✅ **已补（场景轮 S2/S3/S4/S6）** | 原为「🔴 未验」⇒ 🟠 但**只有双侧、无第三方锚** ⇒ 见「二之三」 |
| **HTTP 面的重复请求（幂等重放）** | ✅ **已补（场景轮 S6）** | 原为「🔴 未验」⇒ 同一请求连发两次 ⇒ 三个子场景（建夹 200→5203 · 收藏 200→5302 · **同名改名 200→200 且 `update_time` 未推进** ⇒ 真·幂等）⇒ 两侧六个业务码 + 改名响应体全同 · 两侧各恰好 1 行 |
| **场景轮的 Go 第三方锚** | ❌ **无** | 本机无 `Sea-etcd` ⇒ Go 真源跑不起来 ⇒ ⇒ 场景轮**只有 B↔C** ⇒ ⇒ **不得**与 32/36 case 混称「三方一致」 |
| **gRPC 帧层原始字节**（HPACK / 帧序 / WINDOW_UPDATE / PING） | 🟡 **有意不比** | 由 **gRPC 传输库**决定（grpc-go vs grpc-java 必然不同）且**客户端不可观测** ⇒ 比它没有意义。⇒ 本探针比的是「帧层之上、消息层之下」的**客户端可观测**那一层（code / description / 消息字节 / metadata key） |
| **Go 侧此刻的行为** | ❌ **不证** | 冻结锚只能证明「与**当时**的 Go 一致」；Go 侧之后若变了，本探针**看不见**（B 侧原件的 sha256 变了会被拦住 ⇒ 那是**换锚**而不是**换行为**） |
| **响应头 `Date` / `Content-Length`** | ⚪ 不比 | `Date` 每次都变 ⇒ 比它必然假红；`Content-Length` 由 body 长度决定（body 已逐字节比）⇒ 冗余 |
| **本仓副本自身被改** | ✅ **已被拦住** | gRPC 面做**两道**核对：① B 侧原件 sha256 ② 本仓副本的**数据行**与原件**逐行相同**（`grpc-wire/check-anchor-copy.py`）⇒ ⇒ **改了副本里的冻结值**也会被抓住（否则 = 假绿） |


---

## 七、复现

```bash
cd /Users/pr/Documents/GitHub/RAG-V1.worktrees/favorite   # align/favorite 工作树
bash tools/difftest/favorite/run.sh                # HTTP 面 + gRPC 面 + 场景轮
SKIP_HTTP=1 bash tools/difftest/favorite/run.sh    # 只跑 gRPC 面
SKIP_SCENARIOS=1 bash tools/difftest/favorite/run.sh    # 只跑 HTTP 面 + gRPC 面
ONLY_SCENARIOS=1 bash tools/difftest/favorite/run.sh     # 只跑场景轮

bash tools/difftest/favorite/mutate-parity.sh        # HTTP 面变异验证（红→绿）
bash tools/difftest/favorite/mutate-grpc-parity.sh   # gRPC 面变异验证（红→绿）
bash tools/difftest/favorite/mutate-scenarios.sh     # 场景轮变异验证（红→绿）
```

⚠️ 需要：**docker**（真 PG + 真 Redis）· **mvn** · **java** · **javac** · **python3**。
⚠️ B 侧仓路径用 `B_REPO=...` 指定（默认 `/Users/pr/Documents/java&golang/RAG`）。
⚠️ 端口全部落在**专用段 21600–21699**（避开 OS 临时端口段 49152–65535 —— Docker 随机发布容器端口就在那一段）
   ⇒ 与本仓其它段（C 侧测试树 22910–22939 / 真 Tomcat 21400–21499 / B 侧自带探针 21400–21599）**都不重叠**。
⚠️ `KEEP_TMP=1` ⇒ 保留临时目录（两侧启动日志 / 两份 TSV 结果）。

### 文件清单

| 文件 | 角色 |
|---|---|
| `run.sh` | 编排：构建两侧产物 → 起真 PG（两个库）+ 真 Redis → 起 3 个真进程 → 灌种子 → **HTTP 面** → **重灌种子** → **gRPC 面** → 两段比对 |
| `probe/FavoriteApiParityProbe.java` | **HTTP 面**探针（JDK-only · classpath 恒为空） |
| `compare.py` | **HTTP 面**三方比对器（Go 锚 ↔ B ↔ C） |
| `expected.txt` | **HTTP 面** Go 冻结锚（= B 侧 `tools/api-full-parity-probe/favorite/expected.txt` 的副本） |
| `grpc-wire/FavoriteWireProbe.java` | **gRPC 面**探针（grpc-java + 裸字节 proto3 · 不用任何 pb 生成码） |
| `grpc-wire/compare-grpc.py` | **gRPC 面**两段比对器（① 锚校验 Go↔B ② BC 校验 B↔C） |
| `grpc-wire/expected.txt` | **gRPC 面** Go 冻结锚（= B 侧 `tools/grpc-contract-probe/favorite/expected.txt` 的副本） |
| `grpc-wire/kinds-bc.txt` | **gRPC 面**「Java ↔ Java」档位表（本仓自撰 · 36 条） |
| `grpc-wire/check-anchor-copy.py` | 冻结锚**副本一致性**核对（逐行比对本仓副本与 B 侧原件） |
| `seed.sql` | 共用种子（= B 侧 `tools/grpc-contract-probe/favorite/seed.sql` 的副本） |
| `mint-jwt.py` | 签 token（两侧共用一把 secret） |
| `mutate-parity.sh` / `mutate-grpc-parity.sh` / `mutate-scenarios.sh` | 三个面的**变异验证**（判据有牙齿） |
| `stub/DownstreamStub.java` | **场景轮的下游桩**（**一个进程**同时供 user-rpc + article-rpc · 行为由**控制文件热切换** `mode=normal\|slow\|notfound` + `slow_ms` ⇒ 每次请求重读 ⇒ **不用重启**） |
| `scenarios/FavoriteScenarioProbe.java` | **场景动作探针**（JDK-only · 与矩阵探针**同一渲染口径** `status=/ct=/biz=/body=`） |
| `scenarios/scenarios.py` | **场景轮编排 + 判据**（绝对断言 + 两侧对称 · 故障注入 · `KNOWN_DIFFS` **指纹式**已知差异登记） |

