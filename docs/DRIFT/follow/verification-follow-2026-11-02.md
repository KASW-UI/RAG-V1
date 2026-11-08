# `follow` 域迁移审核台账 —— RAG（B 侧）→ RAG-V1（C 侧）

> **产出坐标**：由 **`align/follow` 工作树**产出
> （`/Users/pr/Documents/GitHub/RAG-V1.worktrees/follow`）· **基线 commit `ae17325`**
> （= 本地 `main` tip；⚠️ 本台账初稿写的是 `c665476`，其间 `main` 多了 2 个 commit
> —— `2458367 add book、lombok`（纯文档 + lombok 规范）与 `ae17325`（users 域 Lombok 化）
> ⇒ **代码等价**（follow 域 0 文件受影响）⇒ 全部读数不受影响 —— 2026-11-02 复核）· **核对日期 2026-11-02**
>
> **本文件归属**：`service/follow/`（`follow-service`）域。
> ⚠️ **只写自己域**：`common/` 与 `users-common` 的台账各有其主，本文件**不代笔**。
> ⚠️ **不碰 `_INDEX.md`**（AGENTS.md §五.2：三个 `_INDEX.md` 合并后统一更新）。
>
> **判定基准**：**D16 · 2026-11-02 用户定** —— **B 侧（RAG 仓）是唯一行为判定基准**。
> ⇒ 本台账的所有「一致 / 不一致」结论，都是**读 B 侧源码**得出的，**不回溯**更早的历史版本。
>
> 📌 **相关文档**：
> 签字偏离 ⇒ [`docs/Intentional_Deviations/follow/_signed-deviations.md`](../../Intentional_Deviations/follow/_signed-deviations.md) ·
> 抄写路线图 ⇒ [`docs/Mapping_Table/follow/follow.md`](../../Mapping_Table/follow/follow.md) ·
> 文件树 ⇒ [`docs/File_Tree/follow.md`](../../File_Tree/follow.md) ·
> Lombok 规范 ⇒ [`docs/Lombok-使用规范.md`](../../Lombok-使用规范.md)

---

## 〇 · 两侧坐标与规模

| 侧 | 仓库 | 路径 | 规模 |
|---|---|---|---|
| **B（RAG，唯一真理源）** | `/Users/pr/Documents/java&golang/RAG` | `service/follow/` | **51** 个主代码 `.java` / **6830** 行（`follow-api` 31/3531 + `follow-rpc` 16/2741 + `follow-common` 4/558）＋ **39** 个测试 `.java` |
| **C（RAG-V1，本仓）** | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/follow` | `service/follow/` | **37** 个主代码 `.java`（D7 打平后）＋ **2** 个 DDL（与 B 侧逐字节一致）＋ **10** 个测试类 / **72** 个测试 ＋ 6 个 support 类 |

### 实际解析版本（**读 classpath 取值，不是 pom 声明值**）

| 项 | B 侧 | C 侧 | 备注 |
|---|---|---|---|
| Spring Boot | **4.x** | **3.5.11** | ⚠️ 跨大版本 ⇒ 抄实现要按 3.5 的 API 面改写（D14） |
| Jackson | **3** | **2.19.4** | ⚠️ 跨大版本 ⇒ D12「全面 Jackson 3」**已作废**（D14 回退） |
| gRPC | go-zero `zrpc` | devh `3.1.0.RELEASE` | ⚠️ devh **只认 `GrpcServerConfigurer`** ⇒ 拦截器漏配 = 静默失效 |
| MyBatis-Plus | 3.5.x | **3.5.17** | |
| Lombok | — | **1.18.42**（Boot BOM 管） | D18：允许 + 鼓励 + 展开注释强制 |
| JDK | — | **21** | |

---

## 一 · B → C 责任承接表（**51 → 37**）

| B 侧分组 | B 文件数 | C 侧落点 | C 文件数 | 承接方式 | 证据强度 |
|---|---|---|---|---|---|
| `follow-common/.../errmsg/` | 4 | `contract/{CodeError,ErrCodes,ErrMsg,GrpcErrorMapper}` | **4** | 1:1（**4 个全改名**；落点见 DEV-FOLLOW-001） | **E-2** |
| `follow-proto/`（`follow.proto`） | 1（生成物） | 根级 `proto/` 模块 | — | 1:1（**仅 `java_package` 一处差异**） | **E-2**（逐字节 diff） |
| `follow-api/.../dto/` | 6 | `dto/{list,recommend,relation}/` | **6** | 1:1（**形态变**：class ⇒ record，见 DEV-FOLLOW-003） | **E-2** |
| `follow-api/.../controller/` | 1 | `controller/FollowController.java` | **1** | 1:1 | **E-2** |
| `follow-api/.../advice/` | 2 | `advice/{FollowApiResponseAdvice,FollowApiExceptionHandler}` | **2** | 1:1（2 个改名） | **E-2** |
| `follow-api/.../interceptor/` | 2 | `interceptor/{FollowJwtAuthInterceptor,FollowBlacklistInterceptor}` | **2** | 1:1 | **E-2** |
| `follow-api/.../auth/` | 4 | （收敛进 `interceptor/FollowJwtAuthInterceptor` 内部） | **0** | **N:1** ⇒ **DEV-FOLLOW-005** | **E-2** |
| `follow-api/.../config/` | 3 | `config/{FollowApiProperties,WebMvcConfig}` + （`FollowGoStyleCoercion` ⇒ 域内 customizer） | **2** | 1:1 ×2 ＋ 1:N（**缺口 #2** ⇒ DEV-FOLLOW-004） | **E-2** |
| `follow-api/.../metrics/` | 1 | `metrics/FollowApiMetrics.java` | **1** | 1:1 | **E-2** |
| `follow-api/.../client/` | 1 | （删 —— D2 域内直连） | **0** | **1:0** ⇒ DEV-FOLLOW-007 | **E-2** |
| `follow-api/.../service/` | 10 | `service/{FollowApiSupport,FollowRelationService,FollowQueryService}` | **3** | 8 个单端点 Service ⇒ **4 写 + 4 读**（Mapping_Table §1.3） | **E-2** |
| `follow-rpc/.../service/` | 5 | `service/{FollowRpcSupport,FollowUserExistenceService,FollowRecommendationService,FollowBlockTransactionService}` + `domain/service/FollowDomainService` | **5** | 1:1（`FollowBlockTransactionService` 独立成 Bean 是**硬约束**） | **E-2** |
| `follow-rpc/.../service/impl/` | 1 | `grpc/FollowGrpcService.java` | **1** | 1:1（改名） | **E-2** |
| `follow-rpc/.../domain/` | 2 | `domain/entity/{FollowRelationEntity,BlockRelationEntity}` | **2** | 1:1（**Lombok 化** + D18 展开块） | **E-3**（字节码等价已核验） |
| `follow-rpc/.../mapper/` | 3 | `mapper/{FollowRelationMapper,BlockRelationMapper,GoStyleLimitOffset}` | **3** | 1:1 | **E-2** |
| `follow-rpc/.../config/` | 1 | `config/FollowRpcProperties.java` | **1** | 1:1 | **E-2** |
| `follow-rpc/.../client/` | 1 | `client/UserRpcClient.java` | **1** | 1:1（**跨域** ⇒ 保留） | **E-2** |
| `follow-rpc/.../bootstrap/` | 1 | `wiring/NacosRegistration.java` | **1** | 1:1（改名） | **E-2** |
| `follow-rpc/.../metrics/` | 1 | `metrics/FollowRpcMetrics.java` | **1** | 1:1 | **E-2** |
| `follow-rpc/.../db/migration/` | 2 | `resources/db/migration/V{1,2}__*.sql` | **2** | 1:1（**逐字节一致**） | **E-2**（diff 零差异） |
| `follow-api/.../FollowApiApplication.java` + `follow-rpc/.../FollowRpcApplication.java` | 2 | `FollowServiceApplication.java` | **1** | **N:1**（D1 合并） | **E-2** |
| `follow-api/.../application.yml` + `follow-rpc/.../application.yml` | 2 | `resources/application.yml` | **1** | **N:1**（D1 合并） | **E-2** |
| **B 侧 `ErrorNotFound`（同名不同物）** | 1 | **不迁** | 0 | B 侧实测**零调用点** ⇒ B、C 两侧均无 | **E-2** |

### 承接汇总

- **B 51 → C 37**：合并掉 14 个（2 个入口 → 1 · 2 个 yml → 1 · 8 个单端点 Service → 2 · 4 个 auth 类 → 1 个内部 · 1 个同域 client → 0 · `ErrorNotFound` → 0）。
- **零遗漏**：C 侧 37 个文件**全部**有 B 侧对应物 ⇒ 覆盖 **37/37（100%）**。

---

## 二 · L1 契约逐条核对（**结论：逐条一致**）

### 2.1 路由契约（8 个端点 · 全 POST）

| 端点 | B 侧（`routes.go:16-64`） | C 侧 | 一致 |
|---|---|---|---|
| `POST /follow/v1/follow` | ✔ | ✔ | ✅ |
| `POST /follow/v1/unfollow` | ✔ | ✔ | ✅ |
| `POST /follow/v1/block` | ✔ | ✔ | ✅ |
| `POST /follow/v1/unblock` | ✔ | ✔ | ✅ |
| `POST /follow/v1/follow/list` | ✔ | ✔ | ✅ |
| `POST /follow/v1/follower/list` | ✔ | ✔ | ✅ |
| `POST /follow/v1/block/list` | ✔ | ✔ | ✅ |
| `POST /follow/v1/recommendations` | ✔ | ✔ | ✅ |

⚠️ **B 侧 8 个 handler 无一例外都调 `httpx.Parse`** ⇒ C 侧 8 个端点**都绑定 body**。

### 2.2 错误码与文案（16 条 · 逐条一致）

| 码 | B 侧文案 | C 侧文案 | 一致 |
|---|---|---|---|
| 200 | `OK` | `OK` | ✅ |
| 500 | `FAIL` | `FAIL` | ✅ |
| 1015 | `service busy` | `service busy` | ✅ |
| 5001 | `internal server error` | `internal server error` | ✅ |
| 5002 | `record not found` | `record not found` | ✅ |
| 5003 | `invalid parameter` | `invalid parameter` | ✅ |
| 5004 | `unauthorized` | `unauthorized` | ✅ |
| 5005 | `forbidden` | `forbidden` | ✅ |
| 5006 | `already exists` | `already exists` | ✅ |
| 5007 | `database query failed` | `database query failed` | ✅ |
| 5008 | `database update failed` | `database update failed` | ✅ |
| 5101 | `user not found` | `user not found` | ✅ |
| 5201 | `self relation is not allowed` | `self relation is not allowed` | ✅ |
| 5202 | `blocked relation exists` | `blocked relation exists` | ✅ |
| 5203 | `follow relation not found` | `follow relation not found` | ✅ |
| 5204 | `block relation not found` | `block relation not found` | ✅ |

- **声明顺序**逐条一致（`1015` 排在 5001 之前 —— B 侧源码就写在那里）。
- **未知码回落**一致（`getMsg(未知)` → `"FAIL"`）。
- **msg→code 反查表**一致（排除 200/500 + first-write-wins）。

### 2.3 三种错误响应形状（follow 域独有）

| 场景 | B 侧形状 | C 侧形状 | 一致 |
|---|---|---|---|
| 业务失败 | **200** + `{code,msg,data:null}` | 同 | ✅ |
| 验签失败 | **401** + **空 body** | 同 | ✅ |
| 黑名单命中 | **401** + `{"code":5004,"msg":"unauthorized"}`（**无 `data`**） | 同 | ✅ |

⚠️ **B 侧注册了 `SetErrorHandler` ⇒ 解析失败也是 200 + 5001**（**不是 400**）——
这与 users 域（400 + text/plain）**不同** ⇒ C 侧逐条照抄 B 侧，**未**照抄 users 域。

### 2.4 DTO 的 JSON 形状（本域最隐蔽的 L1 差异）

| 字段 | B 侧形态 | C 侧形态 | 一致 |
|---|---|---|---|
| `user_ids`（出参） | **JSON 数字数组** `[123]`（**无** `,string`） | 同 | ✅ |
| `target_id`（出参） | **JSON 字符串** `"123"`（**有** `,string`） | 同 | ✅ |
| `user_ids`（空结果） | **`null`**（protobuf 空 repeated ⇒ 字段消失 ⇒ 反序列化得 nil） | 同 | ✅ |
| `users`（空结果） | **`[]`**（api 进程内 `make(...,0,n)` ⇒ 恒非 nil） | 同 | ✅ |
| `user_id` / `target_id`（入参） | 字符串与数字**都接受**（`json.Number` 的 Kind 是 string） | 同（`StringInt64.parse`） | ✅ |
| `offset` / `limit`（入参） | JSON 字符串**被拒**（无 `,string`） | 同（**域内 customizer**，DEV-FOLLOW-004） | ✅ |
| `target_id`（入参） | **必填**（无 `,optional`） | 同（`@NotNull`，DEV-FOLLOW-003） | ✅ |
| `user_id` / `offset` / `limit`（入参） | 全 optional | 同（无约束） | ✅ |

⚠️ **`user_ids` 空 ⇒ `null` 与 `users` 空 ⇒ `[]` 的不对称**是 B 侧的真实行为
（前者隔着一次 protobuf 编解码，后者是 api 进程内新建切片）⇒ C 侧逐条照抄。

### 2.5 proto 契约

`proto/src/main/proto/follow.proto` 与 B 侧 `follow-proto/src/main/proto/follow.proto` 的
**唯一差异是 `option java_package`**（`com.rag.follow.follow.proto` → `com.rag.proto.follow`）⇒
**message 名 / 字段名 / 字段号 / 类型 / rpc 签名逐字节一致**（§3.4 约定允许）。

---

## 三 · L2 业务语义逐条核对（**结论：逐条一致，8 条「不对称」全部保留**）

| # | B 侧行为 | C 侧 | 一致 | ⚠️ 为什么必须保留 |
|---|---|---|---|---|
| 1 | **Unfollow / Unblock 不校验用户存在性** | 同 | ✅ | 「关注不存在的人」被拒，「取消关注不存在的人」照样成功（删 0 行）—— 看着像 bug，但是 B 的实际行为 |
| 2 | **只有 Follow 做拉黑检查**（5202） | 同 | ✅ | 其余 7 个用例都没有这一步 |
| 3 | **Block 不检查「是否已被对方拉黑」** | 同 | ✅ | A 拉黑 B 时即使 B 已拉黑 A，两条 block 行都存在 |
| 4 | **重复关注返回 SUCCESS**（不是 5006） | 同 | ✅ | `uk_follow_pair` + `DO NOTHING` 的组合语义 |
| 5 | **4 个写方法上报 2 个指标**，4 个读方法只上报 1 个 | 同 | ✅ | B 侧各 logic 的 defer 明确区分 |
| 6 | **`ensureUserExists` 三分支**（NotFound→5101 / 其它→**全部折叠 5001** / found=false→5101） | 同 | ✅ | 「不区分下游故障形态」是 B 的既定行为；改进它会让业务码随下游漂移 |
| 7 | **推荐算法 6 条**（直关为空提前返回 / 三重过滤顺序 / `expanded` 初值含 userId / 末层不展开 / 下一层先升序 / offset 越界返空） | 同 | ✅ | 漏掉 `expanded[userId]` ⇒ **算法会把自己推荐给自己** |
| 8 | **候选排序有次关键字**（分数降序 + id 升序） | 同 | ✅ | B 侧 `sort.Slice` 不稳定 ⇒ 次键不可省 |

### 3.1 归属校验的**不对称**（4 个 list 端点）

| 端点 | `resolveUserId` | `requireSelfScope` | 违规码 | 一致 |
|---|---|---|---|---|
| `/follow/list` | ✔ | **✘ 刻意没有** | — | ✅ |
| `/follower/list` | ✔ | **✘ 刻意没有** | — | ✅ |
| `/block/list` | ✔ | ✔ | 5005 | ✅ |
| `/recommendations` | ✔ | ✔ | 5005 | ✅ |

⇒ **关注列表与粉丝列表公开可查，黑名单与推荐私有** ⇒ 统一加校验 = **新增拒绝行为**。

### 3.2 分页归一化的顺序（3 段 · 不可调换）

| 段 | 规则 | 一致 |
|---|---|---|
| 1 | `offset < 0` ⇒ 夹到 0（**不报错**） | ✅ |
| 2 | `limit <= 0` ⇒ 换成 `defaultLimit` | ✅ |
| 3 | `limit > maxLimit` ⇒ 截断到 `maxLimit`（`maxLimit <= 0` ⇒ **整条短路 = 不限制**） | ✅ |

⚠️ 三个 list 端点用 `(20, 100)`，推荐端点用 `(20, **50**)` —— **两个不同的上限**。

### 3.3 推荐算法的三处「下界修正」（不可删）

| 函数 | 规则 | 一致 |
|---|---|---|
| `scoreWeight(maxDepth, depth)` | `maxDepth < 2 ⇒ 3` · `depth < 2 ⇒ 2` · 权重恒 ≥ 10 | ✅ |
| `clampRecommendDepth(depth)` | `depth < 2 ⇒ **3**`（**不是 2**）· `depth > 5 ⇒ 5` | ✅ |
| `clampFanout(fanout)` | `fanout <= 0 ⇒ 50` · `fanout > 200 ⇒ 200`（**硬编码魔数**，B 侧 yaml 无此字段） | ✅ |

---

## 四 · L4 机制层核对

| 项 | B 侧 | C 侧 | 一致 | 备注 |
|---|---|---|---|---|
| **主键生成** | GORM 整数 PK 默认 autoIncrement ⇒ `BIGSERIAL` | `@TableId(type = IdType.AUTO)` + `BIGSERIAL` | ✅ | ⚠️ **不是雪花**（全仓惯例 `ASSIGN_ID` 会引入 B 侧不存在的生成器） |
| **时间戳来源** | GORM `autoCreateTime` / `autoUpdateTime` struct tag ⇒ **应用进程**产生 | 业务代码显式赋值 | ✅ | ⚠️ 不加 UPDATE fill（B 侧**零 UPDATE** ⇒ `update_time` 永不推进） |
| **时间类型** | Go `time.Time` ⇒ `TIMESTAMPTZ` | `OffsetDateTime` | ✅ | `LocalDateTime` 会丢 offset |
| **幂等插入** | GORM `ON CONFLICT DO NOTHING` | `@Insert` + `ON CONFLICT (user_id, target_id) DO NOTHING` | ✅ | MP 的 `insert(entity)` 不支持 ⇒ 必须注解式 |
| **拉黑存在性检查** | **双向 OR** | 同（`countAnyBlock`） | ✅ | 单向即安全漏洞 |
| **分页拼接** | 条件拼接 `LIMIT`/`OFFSET` | `GoStyleLimitOffset` + `${}` 插值 | ✅ | ⚠️ 不能用 `#{}` |
| **事务边界** | GORM `Transaction(func(tx))` | `@Transactional(rollbackFor=Exception.class)` **独立 Bean** | ✅ | ⚠️ 自调用 ⇒ 事务**静默失效** |
| **事务语句顺序** | 先 INSERT block、后 DELETE follow | 同 | ✅ | 反向 ⇒ 加锁顺序相反 ⇒ 潜在死锁 |
| **gRPC 错误出口** | `onError` | `onError`（**不得让异常逃逸**） | ✅ | 逃逸 ⇒ description 被替换 ⇒ **业务文案全丢** ⇒ 业务码无法还原 |
| **业务码还原** | description **文案反查**优先于 gRPC 码 switch | 同（`GrpcErrorMapper`） | ✅ | ⚠️ **反查是唯一途径**（5201/5202/5101/5007/5008 全靠它） |
| **指标桶边界** | prometheus `DefBuckets`（11 个） | `serviceLevelObjectives(...)`（11 个，顺序同） | ✅ | ⚠️ **不可用** `publishPercentileHistogram()`（68 个 HdrHistogram 桶 ⇒ 零重合） |

---

## 五 · 逐项缺口处置（Mapping_Table §6.3 的 5 个缺口）

| 缺口 | 内容 | 处置 | 状态 |
|---|---|---|---|
| **#1** | DDL 无落点（`db/migration/` 仅 `.gitkeep`、pom 无 `flyway-core`） | ✅ **已补**：`V1__create_follow_relation.sql` + `V2__create_block_relation.sql`（**与 B 侧逐字节一致**）＋ pom 已有 `flyway-core` + `flyway-database-postgresql` | **CLOSED** |
| **#2** | 域内 Jackson 强转补强无落点（B 侧有 `FollowGoStyleCoercion`，C 侧无） | ✅ **已补**：域内 `WebMvcConfig` 注册 `Jackson2ObjectMapperBuilderCustomizer`，叠加 `LogicalType.Integer + String → Fail`（⇒ 拒 `{"offset":"10"}`） | **CLOSED**（DEV-FOLLOW-004） |
| **#3** | 配置项无落点（`id-type` / `TimeZone` / `observability.http-timeout` / `grpc.client.user-rpc.negotiation-type` ＋ 2 段业务配置 ＋ 黑名单拦截器未进注册清单） | ✅ **已补**：`application.yml` 逐项补齐 ＋ `WebMvcConfig` 注册**两个**拦截器 | **CLOSED** |
| **#4** | `GoStyleLimitOffset` 无全局等价物 | ✅ **已补**：域内 `mapper/GoStyleLimitOffset.java`（`public final class` ⇒ 跨模块可见） | **CLOSED** |
| **#5** | 用例层双写（api 8 用例 vs rpc 8 用例方法名集合相同） | ✅ **已处置**：api 层 `FollowRelationService` / `FollowQueryService` **只做编排**（身份解析 + proto 构造 + DTO 投影）⇒ **业务规则唯一落在 `FollowDomainService`** | **CLOSED** |

⚠️ **缺口 #5 的验证方式**：api 层的两个 Service 里**搜不到**任何
`validateRelationReq` / `ensureUserExists` / `existsAnyBlock` / `normalizeListReq` 的调用
⇒ 规则确实只落一处。

---

## 五 · 5.5 · 🔴 本轮自查发现并修复的漂移（**7 条** · 均为 L1 行为层 · A~D 首轮 · **F/G/I 为 E-4 对拍轮**）

> ⚠️ 这 6 条**不是**「映射表缺口」，而是**自查与活体对拍阶段读 B 侧源码 / 真跑真进程时发现的「C 侧已落地代码 ≠ B 侧行为」**。
> 按 AGENTS.md §3.3.1（「必须读到消费该配置/注解的那段控制流」）逐条定位，每条都带**消费点行号**。

### 漂移 A · 🔴 `application.yml` 的 `user-auth` 前缀错位 ⇒ **启动必失败**

| 项 | 内容 |
|---|---|
| **B 侧事实** | `follow-api/src/main/resources/application.yml:159-162` = `follow-api:` → `user-auth:` → `access-secret`；`FollowApiProperties` 的注解是 `@ConfigurationProperties(prefix = "follow-api")`（B 侧 `FollowApiProperties.java:47`）⇒ **键名 = `follow-api.user-auth.access-secret`** |
| **C 侧错在哪** | `service/follow/src/main/resources/application.yml` 把它写成了 `follow.user-auth.access-secret` ⇒ `FollowApiProperties`（prefix=`follow-api`）**绑不上** ⇒ `accessSecret=null` ⇒ `@PostConstruct` 的 fail-closed 检查抛 `IllegalStateException` ⇒ **生产启动失败** |
| **注意** | **C 侧的两个 `@ConfigurationProperties` 前缀本身是对的**（`FollowApiProperties`=`follow-api`、`FollowRpcProperties`=`follow`，**与 B 侧逐字一致**）⇒ 错的是 **yml 里的键名**，不是注解 |
| **修法** | 把 `user-auth` 段移到**同级新增的 `follow-api:` 前缀**下 ⇒ 两个前缀在同一份 yml 里**共存**（D1 合并只改部署形态，**不改配置键名**）⇒ `follow-api.user-auth.*` + `follow.list.*` + `follow.recommend.*`，**与 B 侧两个模块的键名逐条一致** |
| **独立发现** | API worker 与测试 worker **各自独立**报出同一处 ⇒ 双向印证（非单方误判） |
| **状态** | ✅ **已修**（`application.yml`）· 证据强度 **E-2**（源码级）+ 结构级核对（`python3 -c "yaml.safe_load(...)"` 实测顶层键树） |

### 漂移 B · 🔴 业务码反查缺失 ⇒ **5 类业务码全部塌成 5001**

| 项 | 内容 |
|---|---|
| **B 侧事实** | B 侧 api 层的**每个 logic 内**都有 `catch (StatusRuntimeException e) { throw support().rpcError(e); }`（如 `FollowService.java:60-64` / `GetFollowListService.java:72-76`）；`rpcError` = `FollowGrpcError.bizCodeFromError(err)` + `FollowErrorMessages.getMsg(code)`（B 侧 `FollowApiSupport.java:216-219`） |
| **反查为何不可省** | 本域 wire 形态 = **业务码藏在 description 文案里**（`GRPCError(grpcCode, bizCode)` = `status.Error(grpcCode, GetErrMsg(bizCode))`）⇒ 5 类业务码对应的 gRPC 标准码（INVALID_ARGUMENT / PERMISSION_DENIED / NOT_FOUND / INTERNAL）在兜底映射表里**只能还原出粗粒度的码** |
| **C 侧错在哪** | api 层**没有** catch ⇒ `StatusRuntimeException` 直接冒到 `FollowApiExceptionHandler#handleRpcStatus` ⇒ 兜底成 **5001** ⇒ **5201 / 5202 / 5101 / 5007 / 5008 全部丢失** |
| **修法** | ① `FollowApiSupport#rpcError(Throwable)` **已实现**（逐条照抄 B 侧）② api 层 **8 个端点全部**在**领域层调用点** catch ⇒ **位置与 B 侧逐条对称** ⇒ 「正常路径走文案反查 / 未捕获落到 handler 兜底」的**两条路径分工与 B 侧一致** |
| **证据** | **运行时探针**（真实编译产物 + 真实 `GrpcErrorMapper` + 真实 `FollowApiSupport`）：5201 / 5202 / 5101 / 5007 / 5008 / 5003 / 5001 + 「文案未命中 ⇒ 兜底映射」**8/8 全通**（PROBE_EXIT=0） |
| **连带处置** | 原 **DEV-FOLLOW-007**（「反查分支不迁」）的**前提被证伪** ⇒ **已撤销** ⇒ 实际生效偏离 **8 → 7 条** |
| **状态** | ✅ **已修** · 证据强度 **E-3**（真实类 + 真实调用链的运行时探针） |

### 漂移 C · 🟠 写路径缺 `requireRpcSuccess` ⇒ **契约不对称**

| 项 | 内容 |
|---|---|
| **B 侧事实** | B 侧 **8/8** 个 api service 都有 `requireRpcSuccess(resp.getCode(), resp.getMsg())`（实测逐文件计数：`BlockService`/`FollowService`/`UnfollowService`/`UnblockService`/`GetFollowListService`/`GetFollowerListService`/`GetBlockListService`/`GetRecommendationsService` 各 1 次） |
| **C 侧错在哪** | 读路径 4 个端点**有**（藏在 `FollowApiSupport#toUserListResp` / `#toRecommendListResp` 内部）⇒ 但**写路径 4 个端点丢弃了 `BaseResp` 返回值** ⇒ **没有这道检查** ⇒ 领域层一旦返回 `code != 200`（不抛异常），api 层会**当成功返回** |
| **修法** | `FollowRelationService` 的 4 个写方法改为接住 `BaseResp` ⇒ `support.requireRpcSuccess(resp.getCode(), resp.getMsg())` ⇒ **与 B 侧逐条对称** |
| **状态** | ✅ **已修** · 证据强度 **E-2**（源码级逐文件计数对照） |

### 漂移 D · 🔴 `spring.flyway.*` 整段落在**顶层** ⇒ **4 项 Flyway 配置静默失效**

| 项 | 内容 |
|---|---|
| **C 侧错在哪** | `application.yml` 把 `flyway:` 段放在**顶层** ⇒ 键名是 `flyway.*` 而非 `spring.flyway.*` ⇒ Spring Boot **完全读不到** ⇒ 下列 4 项**全部静默失效**：① `baseline-on-migrate: true` ② `baseline-version: 0` ③ `table: flyway_schema_history_follow` ④ `locations`（恰好与默认值相同） |
| **后果 1** | **共享 schema 下启动直接失败**：`Found non-empty schema(s) "public" but no schema history table` |
| **后果 2** | 账本退回**默认的 schema 级表名** `flyway_schema_history` ⇒ 与其它域**撞号**（AGENTS.md §六 的核心问题） |
| **后果 3** | `baseline-version: 0` 失效 ⇒ baseline 打在 version 1 ⇒ **V1 的 SQL 从未执行** ⇒ `follow_relation` 永远建不出来 |
| **⚠️ 为什么长期没被测出来（假绿）** | 在**空 schema**上首次迁移完全正常（Flyway 走「非空判定」之外的路径）⇒ **应用能起、测试全绿** ⇒ 只有「**schema 预先非空 + 自己的账本不存在**」这个真实场景才暴露 ⇒ ⇒ 这是**真触发测试**的直接产出 |
| **捕获它的测试** | `FollowFlywayBaselineRealPgTest`（真 PG）：在同容器另开探针库 ⇒ 预置一张**别的 app 的表**（制造 schema 非空）⇒ 不预置 follow 的账本（制造账本不存在）⇒ **让应用自己**跑迁移 ⇒ 断言 `follow_relation` / `block_relation` 建出来 + 账本表名是 `flyway_schema_history_follow` + 账本里 V1/V2 均 success |
| **修法** | 把整段移进 `spring:` 下（缩进 2）⇒ 键名恢复为 `spring.flyway.*` ⇒ 4 项全部生效 |
| **状态** | ✅ **已修** · 证据强度 **E-3**（真 PG + 真 Flyway + 真应用配置） |

### 漂移 F · 🔴 `grpc-inprocess` 被声明成 `test` scope ⇒ **真实进程起不来**（**E-4 对拍轮捕获**）

| 项 | 内容 |
|---|---|
| **现象** | `java -jar service/follow/target/follow-service-1.0-SNAPSHOT.jar` **启动即失败**：<br>`BeanCreationException: Error creating bean with name 'grpcClientBeanPostProcessor': Invocation of init method failed`<br>→ `Caused by: java.lang.NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder`（→ `ClassNotFoundException`） |
| **根因（Maven「最近者优先」）** | ① `net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE` **自身就传递依赖** `io.grpc:grpc-inprocess`（**compile** scope）⇒ 它带 `InProcessChannelFactory`，而那个类的常量池**硬引用** `io.grpc.inprocess.InProcessChannelBuilder`；<br>② `service/follow/pom.xml` **又显式声明**了 `grpc-inprocess` ⇒ **直接声明永远压过传递声明** ⇒ scope 写成 `test` ⇒ **运行时 classpath 上整个 artifact 消失**；<br>③ devh 的 `shadedNettyGrpcChannelFactory` 建 channel 时要链接 `InProcessChannelFactory` ⇒ 运行期抛 `NoClassDefFoundError` ⇒ `UserRpcClient` Bean 创建失败 ⇒ **整个服务起不来**（**硬失败，不是静默失效**） |
| **⚠️ 为什么 71 个单测全绿也没发现** | 测试跑在 **test classpath** 上 ⇒ `grpc-inprocess` **本来就在** ⇒ 缺陷不可见；<br>⇒ 且单测把 gRPC 地址覆盖成 `static://127.0.0.1:1` ⇒ **生产 classpath 与生产地址都没被任何测试跑过**<br>⇒ 这是 AGENTS.md §3.3.3「**跑绿 ≠ 本来就绿**」的**教科书形态**，也是「E-4 不可被单测替代」的实证 |
| **B 侧同款修法（出处）** | B 侧 `service/follow/follow-rpc/pom.xml:231-260` **已把 scope 改成 compile**（并留了整段同款根因注释）⇒ 本条是**照抄 B 侧已定的修法**，不是自创 |
| **修法** | 去掉 `<scope>test</scope>` ⇒ 版本由根 pom 的 `<dependencyManagement>`（`pom.xml:213-215` 的 `grpc-inprocess` + `${grpc.version}`）统一管 ⇒ 与 `grpc-stub`/`-core`/`-netty-shaded` 同源 |
| **证据（修前 / 修后）** | **修前**：E-4 对拍第 3 轮启动失败（`NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder`，日志见 `/tmp/e4-run2.log` 的 `c-service.log` 段）；<br>**修后**：`unzip -l service/follow/target/follow-service-1.0-SNAPSHOT.jar` ⇒ 含 `BOOT-INF/lib/grpc-inprocess-1.63.0.jar` ⇒ 且 E-4 对拍 **21/21 逐字节三方一致**（C 侧进程真实起来并应答） |
| **状态** | ✅ **已修**（`service/follow/pom.xml`）· 证据强度 **E-3**（**真实进程活体**：起不来 → 修好 → 真应答） |

### 漂移 G · 🟠 `RecommendUser` 的 javadoc 推理**与事实不符**（record ≠ class ⇒ 默认序不同）

| 项 | 内容 |
|---|---|
| **C 侧原注释断言** | 「Jackson 在「属性全部显式命名」时按**字母序**排 ⇒ 本类两字段都带 `@JsonProperty` ⇒ 不加干预就输出 `{"mutual_score":20,"target_id":"4"}` ⇒ 与 B 侧字节顺序相反 ⇒ 用 `@JsonPropertyOrder` 显式钉死」 |
| **事实（实测推翻）** | C 侧是 **immutable record**（D5 · A9）⇒ Jackson 对 record 默认按**构造参数（= 声明）序**输出 ⇒ **默认就与 B 侧一致** ⇒ ⚠️ **删掉 `@JsonPropertyOrder`，字节不变**<br>⇒ 原注释里「不加干预 ⇒ 字母序 ⇒ 与 B 侧相反」这条推理**在 record 上不成立** |
| **实测证据** | Jackson **2.19.4** · jshell 三组对照：<br>`@JsonPropertyOrder({"target_id","mutual_score"})` ⇒ `{"target_id":"1004","mutual_score":20}`<br>`@JsonPropertyOrder({"mutual_score","target_id"})` ⇒ `{"mutual_score":20,"target_id":"1004"}` ⇒ **注解对 record 生效**<br>**无注解** ⇒ `{"target_id":"1004","mutual_score":20}` ⇒ **默认 = 声明序，不是字母序** |
| **修法** | javadoc 改写成实测结论：① 区分「B 侧 = class ⇒ 默认字母序 ⇒ 必须靠注解」与「C 侧 = record ⇒ 默认声明序」② 明写本注解在 C 侧的作用是「**把顺序显式钉死、防止组件重排**」而非「修正默认字母序」③ 附三组实测对照 |
| **⚠️ 连带的方法学影响（更重要的一条）** | B 侧变异脚本的 **M1 = 「删掉 `@JsonPropertyOrder`」** ⇒ 在 B 侧（class）**能转红**；<br>⇒ 在 C 侧（record）**转不了红**（删注解字节不变）⇒ **实测 M1 首跑 exit 0**；<br>⇒ ⇒ **变异必须针对被测形态设计，不能照抄** ⇒ 本仓变异 M1 已改成「把注解**改成反序**」⇒ 见 §八·六 的变异证据表 |
| **状态** | ✅ **已修**（`RecommendUser.java` 的 javadoc + 变异脚本 M1 重设计）· 证据强度 **E-3**（Jackson 2.19.4 实测三组对照） |

### 漂移 I · 🔴 下游超时的业务码：C 侧 **5001** vs B 侧 **1015**（**E-4 对拍轮捕获** · 已修）

| 项 | 内容 |
|---|---|
| **现象** | E-4 对拍**场景轮**（8d 阶段1）：把下游 user-rpc **SIGSTOP**（连接可建、无响应）⇒ 请求 follow 列表：<br>**C 侧 = `{"code":5001,"msg":"internal server error"}`**；**B 侧 = `{"code":1015,"msg":"service busy"}`** ⇒ **两侧逐字节不同**<br>⇒ **3 轮复测：C = [5001 5001 5001] · B = [1015 1015 1015]** ⇒ **两侧都稳定 ⇒ 不是竞态 ⇒ 是契约差异**（错误码值属 **L1 契约** ⇒ 按 D16 判 **C 侧错**） |
| **分层定位（决定性实验）** | 同一挂起场景下**直接打两侧自己的 gRPC 端点**（B 侧 follow-rpc :18202 / C 侧 :18205）⇒ **状态码两侧都是 `DEADLINE_EXCEEDED`** ⇒ ⇒ **差异不在 gRPC 层** ⇒ ⇒ 差异**只在 HTTP 层**<br>⚠️ **后续实测的重要限定**：**B 侧 gRPC 层本身非确定** —— 跨运行采样得到过 `DEADLINE_EXCEEDED`（服务端 2s 超时调度器先关 call）**与** `INTERNAL`（领域层折叠后的错误先写出去）⇒ 服务端的 2s 调度器与内层 user-rpc 的 2s 预算**同一瞬间到期** ⇒ ⇒ **gRPC 层不能作契约判据** ⇒ **契约判据取 HTTP 层**（实测 6/6 轮稳定 1015） |
| **根因** | B 侧 `FollowUserExistenceService` 把**一切**非 NOT_FOUND 的下游故障（含超时）折叠成 `INTERNAL + "internal server error"`（5001）；<br>⚠️ **但 B 侧那层折叠在 B 侧从来不可观测** —— 两层各有东西先到期：<br>① **gRPC 层**：服务端 2s 超时调度器先关 call ⇒ 客户端看到 `DEADLINE_EXCEEDED`（**不是**折叠后的 INTERNAL）；<br>② **HTTP 层**：api→rpc 那一跳的 **2s 客户端预算**先到 —— B 侧 `FollowRpcClient` 的 javadoc **明写**：「Go 侧下游挂死时，**2s 的客户端超时先到** ⇒ api 拿到 rpc 错误 ⇒ 返回 200 + 业务码 **1015**（不是 HTTP 503）」⇒ api 层看到 `DEADLINE_EXCEEDED` ⇒ 文案反查未命中 ⇒ gRPC 标准码兜底 ⇒ **default = 1015 service busy**。<br>⇒ ⇒ **B 侧对客户端的承诺是 1015，不是 5001。**<br>⚠️ **C 侧合并进程（D1 + D7）后没有 api→rpc 那一跳** ⇒ 那层折叠**第一次变得可观测** ⇒ HTTP 层拿到被折叠的 INTERNAL ⇒ **5001** ⇒ 与 B 侧逐字节不同 |
| **修法** | `FollowUserExistenceService#ensureUserExists` **单独放过 `DEADLINE_EXCEEDED`**（原样上抛）：<br>① **HTTP 层**：文案反查未命中 ⇒ 标准码兜底 ⇒ `DEADLINE_EXCEEDED` ⇒ **default = 1015** ✔；<br>② **gRPC 层**：**无变化**（服务端 2s 超时调度器先关 call ⇒ 客户端仍是 `DEADLINE_EXCEEDED`）✔；<br>③ **其它下游故障**（`UNAVAILABLE` / `INTERNAL` / `UNKNOWN` / `CANCELLED`）**仍折叠成 5001** ✔（E-4 场景轮阶段3「连接被拒」⇒ 两侧都是 5001 ✔） |
| **⚠️ 为什么这不是「把下游码透传出去」的坏改动** | 折叠**只对超时解除**，其余下游故障形态**仍然折叠** ⇒ 业务码**不会**「随下游故障形态漂移」⇒ 与原注释的告诫不冲突（那条告诫针对的是「**全部**透传」） |
| **测试（真触发 + 变异证据）** | ① `FollowHttpBusinessCodeRealTomcatTest#downstreamTimeoutReturns1015ServiceBusy`：**真 Tomcat + 真 PG + 真 gRPC**（假 user-rpc 真 server 回 `DEADLINE_EXCEEDED`）⇒ 断言 **HTTP 200 + `{code:1015, msg:"service busy"}`**（真 Tomcat，非 MockMvc）；<br>② **变异证据**：临时把该分支摘掉 ⇒ 该用例**精确变红**（`expected: 1015 but was: 5001`）⇒ ⇒ **不是假绿** |
| **状态** | ✅ **已修**（`FollowUserExistenceService.java` + `UserRpcClient.java` + `FakeUserRpcServer.java` + 新增测试）· 证据强度 **E-4**（双侧活体） |
| **🔴 2026-11-03 定位补充：修法是「**形态对齐**」，不是「碰巧对齐」** | 逐行读 B 侧源码 + 新增**阶段 2b 过渡窗口分层采样**后，把上表的根因精确化：<br>① **B 侧领域层是二分支**（`NOT_FOUND → 5101`；**其余一律** `INTERNAL + 5001`）⇒ **B 侧领域层对超时也是给 5001 的** ⇒ ⇒ **B 的 HTTP 层 1015 不是领域语义，是「预算/调度器抢先」的拓扑产物**（api→rpc 多一跳 ⇒ 外层 2s 客户端预算比内层早一个网络跳到期）；<br>② **两侧的错误映射层逐条同型**（`FollowGrpcError.bizCodeFromError` ⇔ `GrpcErrorMapper.bizCodeFromError`：文案反查优先 → 6 码白名单 → default 1015）⇒ ⇒ **码值由「下游失败形态」决定，不由错误码映射决定**：<br>　　· **形态①挂住到预算到期** ⇒ 两侧都 **1015**（阶段1 **3/3 逐字节一致** ＋ gRPC 层 **3/3 状态码一致**）；<br>　　· **形态②快速失败**（连接被拒 / Nacos 条目为空）⇒ 领域层当场折叠 ⇒ 两侧都 **5001**（kill -9 场景两侧 5001 ＋ 过渡窗口 **4 轮同步 5001**，耗时仅 10~38ms）；<br>③ ⇒ ⇒ **C 侧这个多出来的分支，作用是「复现 B 的抢先形态」** —— 若照抄 B 的二分支，C 在形态①会给 5001 而 B 给 1015 ⇒ **分叉** ⇒ ⇒ **该修法必要且正确**；<br>④ ⇒ **历史那一次「过渡态 C=5001 / B=1015」不构成漂移**（两侧同瞬间落在不同形态 ⇒ 基础设施时序的瞬时不同步）⇒ **不判为漂移、不改代码**（详见 **§8.6.7 / §8.6.7.1**） |

---

## 五 · 5.6 · 🟠 跨域共性漂移（**不归本域修** ⇒ 归 `global` 收口域）

### 漂移 E · ✅ **已销账（2026-11-09 · global 收口域实测）** —— 现象**不成立**：生产形态**有** `PrometheusMeterRegistry`，`/actuator/prometheus` **200**

| 项 | 内容 |
|---|---|
| **原记录的现象** | 在**生产形态**的 Spring 上下文里，`MeterRegistry` 类型的 bean 只有 **`simpleMeterRegistry`** ⇒ **没有** `PrometheusMeterRegistry` ⇒ Boot 的 Prometheus 端点**不存在** ⇒ 本域的 Micrometer 指标无法以 Prometheus 文本被 scrape |
| **🔴 现在的结论** | **原现象不成立** —— 生产形态下 `PrometheusMeterRegistry` **在**，`PrometheusScrapeEndpoint` **在**，`GET /actuator/prometheus` 返回 **200** 且 body 是标准 Prometheus 文本（`# HELP` / `# TYPE` 齐备）⇒ ⇒ **不是漂移** ⇒ ⇒ **本条销账**（跨域共性 ⇒ 由 `global` 收口域一次性处置，**零生产代码改动**） |
| **🔴 原记录的两条根因描述**（均已被**证伪**） | ① 「`MetricsAutoConfiguration` 的 `simpleMeterRegistry` 先注册 ⇒ 装配顺序决定结果」⇒ **不成立**：Boot 3.5.11 的 `MetricsAutoConfiguration`（本地 sources jar 逐行读过）**没有** `simpleMeterRegistry` bean（只有 `micrometerClock` / `meterRegistryPostProcessor` / `propertiesMeterFilter` / `meterRegistryCloser`）；且 `prometheusMeterRegistry` 的 `@ConditionalOnMissingBean` **无参数** ⇒ 按**返回类型** `PrometheusMeterRegistry` 推断 ⇒ **`SimpleMeterRegistry`（另一个类型）挡不住它**。<br>② 「`@ConditionalOnEnabledMetricsExport` 不匹配」⇒ **方向对、但归因错**：不匹配的真因**不是**本仓配置把 export 关了，而是 **Boot 测试框架**干的（见下一行） |
| **✅ 真根因（实测 · 逐行读源码 + 运行证据）** | Boot 3.5.11 的 `spring-boot-test-autoconfigure` 里 **`ObservabilityContextCustomizerFactory`** —— 其 javadoc 明写「**globally disables metrics export and tracing in tests**」⇒ ⇒ 它给**测试上下文**注入 `management.defaults.metrics.export.enabled=false`（property source 名 = `test`）⇒ ⇒ `PrometheusMetricsExportAutoConfiguration` 的 `@ConditionalOnEnabledMetricsExport` 不匹配 ⇒ ⇒ 容器里**没有** registry ⇒ ⇒ `/actuator/prometheus` **404**。<br>⇒ ⇒ **该 404 只在 `@SpringBootTest` 形态下出现**；**生产形态**（真启动的 app）里该属性**根本不存在** ⇒ 默认 `true` ⇒ registry 与端点**都在**。<br>⇒ ⇒ 本仓**配置无误**、**共享层无误** ⇒ ⇒ **不需要改任何生产代码** |
| **证据（3 个探针 · 共享层 `common`）** | ① `common/src/test/java/com/rag/common/autoconfigure/PrometheusScrapeEndpointRealTomcatTest.java` —— **生产形态启动**（`SpringApplicationBuilder` 真起真 Tomcat，**不经过** `SpringBootTestContextBootstrapper` ⇒ 绕开上面的定制器）⇒ `GET /actuator/prometheus` = **200** ＋ `# HELP` ＋ `# TYPE`；<br>② `common/src/test/java/com/rag/common/autoconfigure/PrometheusTestContextExportDisabledAnchorTest.java` —— **反证锚点**：`@SpringBootTest` 形态下 `management.defaults.metrics.export.enabled` 断言 = `"false"` ＋ `PrometheusMeterRegistry` bean **为空** ⇒ ⇒ **把 404 的真因钉死**，下一个人不必再重新诊断；<br>③ `common/src/test/java/com/rag/common/autoconfigure/PrometheusMetricsRegistryWiringTest.java` —— 装配层 2 例（Boot 真的自动配置类 ⇒ registry 建得起来；`management.prometheus.metrics.export.enabled=false` ⇒ 不建）<br>⇒ 机器读数：`mvn -o -pl common -am test -Dtest='Prometheus*Test'` ⇒ **Tests run: 4 · Failures 0 · Errors 0 · Skipped 0** |
| **🔴 变异自证（红 ⇒ 还原绿）** | ① 给生产形态探针加 `management.prometheus.metrics.export.enabled=false` ⇒ **404** ⇒ 该用例**精确转红**；② 把锚点断言从「registry 缺失」改成「registry 存在」⇒ **精确转红**；③ 还原后**全绿**（4/4）⇒ ⇒ 证明这 4 例**不是假绿** |
| **⚠️ 效力边界（诚实）** | ① 本条证的是**共享层这条链路**（registry → 端点 → 文本格式）＋ **Boot 的测试框架行为**；**不证**「每个域的生产 yml 都开了暴露面」⇒ 那是各域的配置面（域内面）。<br>② **B 侧对照**：B 侧 go-zero 用 `prometheus.StartAgent` 起**独立端口**的 `/metrics`（路径与端口都不同）⇒ 本仓把抓取路径映射到 Actuator `/actuator/prometheus` 属**表达层映射**（各域 yml 注释已留痕）⇒ ⇒ **本条不声称「与 B 侧路径相同」**，只主张「C 侧自己的抓取面是通的」。<br>③ ⚠️ **本条同时推翻 follow 域既有的一处写法**：`FollowMetricsContractTest` 是在**注入 registry 的测试上下文**里断言桶边界的 ⇒ 那个上下文的 registry 是**测试自己注入的**，与生产形态**不是同一件事** ⇒ ⇒ 桶边界断言的效力**不变**（它测的是主代码那串 `serviceLevelObjectives`），但**不得**用它证明「生产形态的 registry 在」⇒ ⇒ 本条提供的是**缺的那一半证据** |
| **证据强度** | **E-3+**（生产形态真启动 ＋ 真 HTTP ＋ **变异红/还原绿双向**）—— 高于原记录的 E-3 |

### 漂移 H · ✅ **已销账（2026-11-09 · `global` 收口域实算）** —— 「其余 9 个域同款」是**过期断言**；真正要处理的只有 **message 一个域**

| 项 | 内容 |
|---|---|
| **原记录的现象** | 漂移 F 的**同款 pom 形态**在**其余 9 个域**都有：`io.grpc:grpc-inprocess` 被声明成 `<scope>test</scope>` ⇒ 逐条行号 —— `service/like/pom.xml:70` · `service/comment/pom.xml:70` · `service/message/pom.xml:70` · `service/favorite/pom.xml:70` · `service/task/pom.xml:70` · `service/hot/pom.xml:56` · `service/article/pom.xml:79` · `service/users/admin/pom.xml:65` · `service/users/user/pom.xml:65` |
| **🔴 2026-11-09 实算：原记录已过期** | 逐个 pom **只数真正的 `<dependency>` 块**（不是注释里提到的名字）⇒ **10 个域的真实形态**是：<br>· **compile（默认）**：article · follow · hot · like · task · users-user（**6 个**）<br>· **未声明**：comment · users-admin（**2 个**）⇒ ⇒ 与 B 侧 `service/comment/comment-api/pom.xml:159-174` 的「**刻意不声明**」结论**同款**<br>· **runtime**：favorite（**1 个**）⇒ ⇒ 与 B 侧 `service/favorite/favorite-rpc/pom.xml:348-353` 的 `runtime` **逐字同款**（B 侧自己写明「scope 必须是 runtime，不能是 test」）<br>· **test**：message（**1 个**）⇒ ⇒ **B 侧 `service/message/message-rpc/pom.xml` 里根本没有这条声明** ⇒ ⇒ C 侧这条是**模板残留**<br>⇒ ⇒ **原记录「9 个域同款」已不成立** ⇒ ⇒ **真正要处理的只有 message 一个域**（其余 9 个域各自已按 B 侧处置完毕） |
| **✅ 修法（已实施 · 2026-11-09 · `global` 收口域）** | **删掉 `service/message/pom.xml` 里那条 `io.grpc:grpc-inprocess`（`<scope>test</scope>`）的直接声明** ⇒ ⇒ devh 的 `grpc-client-spring-boot-starter` **传递依赖**（compile 作用域）顶上来 ⇒ ⇒ 满足「测试要 in-process server」与「运行期要 `InProcessChannelBuilder`」两个需求 ⇒ ⇒ 与 B 侧 message-rpc 的 pom **同款**、与本仓 comment / users-admin **同款**。<br>⚠️ **为什么不能改回 `runtime`**：B 侧 favorite 用 `runtime` 是 **favorite 域自己的结论**（它要显式钉住）⇒ ⇒ **逐域隔离，不跨域推论** ⇒ ⇒ favorite **保持 `runtime` 不动** |
| **⚠️ 零副作用核对** | 删除的是 **test scope** 的直接声明 ⇒ devh 那份 **compile 传递依赖本来就在 runtime classpath 上** ⇒ ⇒ **fat jar 内容零变化**（不是「加了依赖」）⇒ ⇒ 无需重校 P-18 的 jar 读数 |
| **⚠️ 潜在雷的定性（仍然成立）** | devh 的 `shadedNettyGrpcChannelFactory` 带 **`@Lazy`**（devh 源码 `GrpcClientAutoConfiguration.java:151`）⇒ **只有真正创建 `@GrpcClient` stub 时才会建它**；⇒ 而**全仓真实使用 `@GrpcClient` 的只有 follow 一处** ⇒ ⇒ 其余域**当时**不触发这个 `NoClassDefFoundError` ⇒ ⇒ **当前不是缺陷，是潜在雷** —— 本条修的是**把那颗雷拆掉**（message 域） |
| **证据强度** | **E-2**（10 个 pom **只数真正的 dependency 块** ⇒ 逐域实测 scope）＋ **E-3**（B 侧 `favorite-rpc` / `comment-api` / `message-rpc` 三份 pom **逐行读原文** ⇒ 得出「逐域各自的结论」⇒ **不是套用漂移 F 的修法**） |

### 漂移 J · ✅ **已修（2026-11-09 · `global` 收口域）** —— 共享层的 `DEADLINE_EXCEEDED` **description 文案**已与 B 侧逐字一致

| 项 | 内容 |
|---|---|
| **原记录的现象** | E-4 场景轮阶段1b 的**分层探测**（同一下游挂起场景，直接打两侧自己的 gRPC 端点）⇒ **状态码两侧相同**（`DEADLINE_EXCEEDED`）⇒ **但 description 不同**：<br>**B 侧 = `context deadline exceeded`**（grpc-go `context.DeadlineExceeded.Error()` 的文案）<br>**C 侧 = `rpc deadline exceeded`**（服务端 2s 超时调度器）／ 或 **grpc-java 的客户端侧文案**（形如 `CallOptions deadline exceeded after 1.999979000s. Name resolution delay …` ⇒ **带毫秒数 ⇒ 每次都不同**） |
| **🔴 根因（两侧坐标）** | B 侧 `service/common/src/main/java/com/rag/common/observability/RpcInterceptor.java:179` 的 `GO_DEADLINE_EXCEEDED_DESCRIPTION = "context deadline exceeded"`（`:361-362` 用它关 call）<br>⇒ C 侧 `common/src/main/java/com/rag/common/autoconfigure/ObservabilityAutoConfiguration.java:509-510` **硬编码** `.withDescription("rpc deadline exceeded")` ⇒ **同一机制、两种文案**<br>⇒ ⇒ **2026-11-09 实算补充**：C 侧其实**已有**同款常量 —— `common/observability/RpcHandleRunner.java:61` 的 `GO_DEADLINE_EXCEEDED_DESCRIPTION = "context deadline exceeded"`（且 `common/src/test/java/com/rag/common/observability/RpcHandleRunnerTest.java:111` **已断言**这个文案）⇒ ⇒ 即：**C 侧两条路径里有一条已是 B 侧文案、另一条不是** ⇒ ⇒ 属**同一机制散成两处** |
| **✅ 修法（已实施 · 2026-11-09 · `global` 收口域）** | ① `RpcHandleRunner.GO_DEADLINE_EXCEEDED_DESCRIPTION` 由 **private 提升为 public**（B 侧**只有一个**常量，服务端关 call 与 handler runner 中断 worker **两条路径共用** ⇒ ⇒ C 侧也该只有一个）；<br>② `ObservabilityAutoConfiguration.scheduleTimeout(...)` 改为**复用该常量**（不再硬编码字面量）⇒ ⇒ **两侧 description 逐字一致**；<br>③ **不是「再抄一份字面量」** —— 那样下次又会漂 ⇒ ⇒ 收敛成**一处常量**（与 B 侧结构同形） |
| **⚠️ 为什么不咬本域（仍然成立）** | ① **业务码还原不受影响**：本域的 5 类业务码（5201/5202/5101/5007/5008）靠**业务文案**反查，与超时文案无关；<br>② **HTTP 层不受影响**：api 层用**本地码表**取 msg（`ErrMsg.getMsg(code)`）⇒ 超时场景的 body 恒为 `{"code":1015,"msg":"service busy"}` ⇒ **两侧逐字节相同**（E-4 场景轮实测）；<br>③ 只在 **gRPC status 的 description 字段**上可见 ⇒ 属**框架原生文案**，不是 follow 域的业务契约 ⇒ ⇒ **不构成 L1 违约** |
| **⚠️ 效力边界（诚实）** | ① 本条修的是**服务端预算到点主动关 call**这条路径的 description；**客户端侧 deadline 先赢**时 description 仍是 grpc-java 的原生文案（`CallOptions deadline exceeded after 1.999979000s. …` ⇒ 带毫秒数 ⇒ 每次都不同）⇒ ⇒ **那条仍不可作 wire 逐字节判据**（见上方「判据纪律」）；<br>② 域内另一条**「rpc deadline reached」**（`service/users/user/…/UserDomainService.java:143` · `service/users/admin/…/AdminDomainService.java:213`）是**领域层自查**的路径 ⇒ **与本条不是同一件事** ⇒ ⇒ **不在本条范围**（属 users 域内面）；<br>③ 本条<b>只改文案</b>，**不改状态码、不改时机、不改预算算法** ⇒ ⇒ **业务可观察行为零变化**，变的只有 gRPC status 的 `description` 字段 |
| **证据强度** | **E-2**（B 侧源码逐行：`RpcInterceptor.java:179` / `:362`）＋ **E-3**（C 侧同款常量已存在且已有测试断言：`RpcHandleRunnerTest.java:111`）⇒ ⇒ 修法是「**改成 B 侧已有的那个值**」，**不需要**双侧活体对拍即可判成立 |

---

## 六 · ⚠️ 本域登记的签字偏离（8 条登记 · **1 条已撤销 ⇒ 实际生效 7 条**）

见 [`docs/Intentional_Deviations/follow/_signed-deviations.md`](../../Intentional_Deviations/follow/_signed-deviations.md)。

| 编号 | 摘要 | 类型 |
|---|---|---|
| DEV-FOLLOW-001 | 错误契约落点：`service/users/common/` ⇒ **域内 `com.rag.follow.contract`**（**用户已拍板**） | L4 · 包位置 |
| DEV-FOLLOW-002 | 错误契约形态：枚举 ⇒ 常量类 ＋ 4 个类符号名中性化 | L4 · 符号形态 |
| DEV-FOLLOW-003 | DTO 层：可变 class + 手写校验器 ⇒ record + Bean Validation | L4 · 表达形态（L3 降级 D-01 ② 同族） |
| DEV-FOLLOW-004 | 域内 Jackson 强转补强的落点 | L4 · 配置装配位置 |
| DEV-FOLLOW-005 | JWT 验签：B 侧 4 类 ⇒ **收敛进 1 个 `HandlerInterceptor`**（⇒ 验签落回 3s 预算**之内**） | L4 · 组件边界 |
| DEV-FOLLOW-006 | 「gRPC 熔断打开」分支不可达（D2 直连的连带后果） | L4 · 零消费者清理 |
| ~~DEV-FOLLOW-007~~ | ~~「gRPC → 业务码」反查分支不迁~~ ⇒ ❌ **已撤销**（前提被运行时证据证伪；实为 L1 漂移，已修 ⇒ 见 §5.5 漂移 B） | ~~L4 · 零消费者清理~~ |
| DEV-FOLLOW-008 | 观测端口 16065 ⇒ 合并进主进程端口（**业务端口 8891/8082 逐条一致**） | L3 · 端口 |

---

## 七 · 证据强度汇总

| 级别 | 覆盖 | 说明 |
|---|---|---|
| **E-4**（双侧活体对拍） | **HTTP 契约层：21/21 逐字节三方一致 ＋ gRPC wire 层 13/13 双边一致 ＋ 场景轮 6 类双侧一致** | 🔴 **2026-11-02 已做** —— **C 侧（RAG-V1/Boot 3.5）↔ B 侧（RAG/Boot 4）↔ 冻结的 Go 真源字节** 三方相等（装置 `tools/difftest/follow/`）⇒ **8 个端点的正常/边界/非法路径 + 三种响应形状 + DTO 的 JSON 形态**全部逐字节相同 ⇒ **场景轮**（超时 / 恢复 / 外部依赖失败 / 恢复 / 并发 / 事务回滚）6 类**双侧**逐字节一致 ⇒ 变异 M1~M7 证明探针非假绿 ⇒ 详见 **§八·六**<br>⚠️ **残余**：**Prometheus 抓取面**仍未达 E-4（§11.6）· **gRPC wire 层**已达**双边**一致但**无第三方锚** |
| **E-3**（真中间件 / 真容器） | **持久层 · HTTP 层 · gRPC 层 · Flyway · 上下文装配** | 实体与 B 侧手写版的 **`javap` 公开方法签名逐条 IDENTICAL**（唯一差异 = 包名）· 真 PG（Testcontainers `postgres:16-alpine`）· 真 Redis（`redis:7-alpine`）· 真 Tomcat（`@SpringBootTest(webEnvironment=RANDOM_PORT)`）· 真 gRPC（in-process server ＋ 假 user-rpc 真 server）· 真 Flyway（探针库 + 非空 schema） |
| **E-2**（源码级对照） | **51/51 B 侧类** | 全部**逐个读过**（6830 行）⇒ 逐条抄写 |
| **E-1**（形态级对照） | 配置与装配 | `application.yml` 逐项对照 B 侧两段 yml |

### ⚠️ 三条必读

1. **「E-2 逐条读过」≠「行为已证」** —— 静态对照能证明「代码写成了这样」，**证不了「运行时是这样」**。
2. ✅ **E-4 双侧活体对拍已于 2026-11-02 补齐**（**HTTP 契约层 21/21 逐字节三方一致 ＋ gRPC wire 13/13 双边一致 ＋ 场景轮 6 类双侧一致**）⇒ 本域结论在**这三层**上可读作「**已验证**」；⚠️ **gRPC wire 层无第三方锚**、**指标抓取面**仍读作「**在该证据强度下一致**」。
3. **⚠️「已落地」≠「已实现」** —— 见 §八 的未完成项。

---

## 八 · 完成情况（**核对日期 2026-11-02**）

### 8.1 ✅ 本轮已落地（逐条对应本轮的待补项）

| 项 | 落地形态 | 证据 |
|---|---|---|
| **真触发测试（真 PG + 真 Tomcat + 真 gRPC）** | **10 个测试类 / 72 个测试**：`FollowErrorContractTest`(12) · `FollowGrpcServiceContractTest`(6) · `FollowHttpContractRealTomcatTest`(15) · `FollowHttpBusinessCodeRealTomcatTest`(5) · `FollowMetricsContractTest`(6) · `FollowBlockTransactionRealPgTest`(4) · `FollowDomainServiceRealPgTest`(14) · `FollowFlywayBaselineRealPgTest`(1) · `FollowServiceApplicationTests`(6) · `FollowConcurrencyRealPgTest`(3) ⇒ **Failures 0 · Errors 0 · Skipped 0** | surefire 模块级汇总（**skipped=0** 已同断） |
| **🔴 下游超时的业务码（漂移 I 的守门）** | `FollowHttpBusinessCodeRealTomcatTest#downstreamTimeoutReturns1015ServiceBusy`：**真 Tomcat + 真 gRPC**（假 user-rpc 真 server 回 `DEADLINE_EXCEEDED`）⇒ 断言 **HTTP 200 + `{code:1015, msg:"service busy"}`**（不是 5001）⇒ **含变异证据**（摘掉分支 ⇒ 精确变红 `expected: 1015 but was: 5001`） | 同上 |
| **并发请求场景**（verification.md §九 覆盖项） | `FollowConcurrencyRealPgTest`（3 条）：① **并发重复关注** ⇒ 恰好 1 行 + 全部 code=200（唯一索引 + `ON CONFLICT DO NOTHING` 的物理效果）② **并发拉黑** ⇒ block 恰好 1 行 + **双向** follow 行被事务清干净 + 全部 code=200 ③ **变异证据**：`DROP INDEX uk_follow_pair` ⇒ 并发关注必然出现 **5008** ⇒ 复原后回到全 200 ⇒ ⇒ **证明前两条判据真在判并发语义，不是「跑完没报错」**<br>⚠️ 并发用 `ExecutorService` + `CountDownLatch` **同时起跑**（依次发 N 次根本撞不上唯一索引）· 期望值出处 = B 侧 `FollowRelationMapper`/`BlockRelationMapper` 的 `ON CONFLICT (user_id, target_id) DO NOTHING`（与 C 侧逐字一致） | 同上 |
| **上下文装配自检** | `FollowServiceApplicationTests`（6 条）：上下文能起来（⇒ **漂移 A/D 的守门**）· `@MapperScan` 真生效 · `@GrpcService` 真被扫到 · 两个拦截器都在 · DDL 真被重放 · 实体表名是 B 侧那两个 | 同上 |
| **事务真触发测试** | `FollowBlockTransactionRealPgTest`：**真 UNIQUE 冲突**做失败注入（非 Mockito 桩）＋「同序列成功路径」非空洞对照 ＋ **装配层**断言（事务 advisor 真的包住 bean ⇒ 否则三条全假绿） | 同上 |
| **`user_ids` 空 ⇒ `null`** | `FollowHttpContractRealTomcatTest` 断言 body 为 `{"user_ids":null}`（不是 `[]`） | 同上 |
| **`target_id` 字符串 vs `user_ids` 数字** | 同上类断言两者形态**不一致** | 同上 |
| **`{"offset":"10"}` 被拒** | 同上类断言 **HTTP 200 + `{code:5001}`**（不是 400） | 同上 |
| **黑名单 401 body 无 `data`** | 同上类断言两字段对象 + 逐字节 Content-Type（`application/json;charset=utf-8`，`;` 后无空格） | 同上 |
| **指标桶边界** | `FollowMetricsContractTest` 断言 `_bucket` 的 `le` 集合 **== Go `DefBuckets` 11 个 + `+Inf`**（反向证据：混入 `publishPercentileHistogram` 会多出几十个 HdrHistogram 边界 ⇒ 集合变大 ⇒ 红） | 同上 |
| **Flyway 共享 schema** | `FollowFlywayBaselineRealPgTest`：探针库 + 预置「别的 app 的表」⇒ 断言 V1/V2 **照常执行** + 账本表名是 `flyway_schema_history_follow` + 账本内 V1/V2 均 success ⇒ **本轮捕获了漂移 D** | 同上 |
| **`gen-file-tree.py`** | ✅ 已跑（ROOT 改指本 worktree）⇒ 产物按**实际文件系统**重算 ⇒ `docs/File_Tree/{follow,proto}.md` 更新 ⇒ **域隔离自检：只动了这 2 份**，其余 12 份逐字节不变 | `git status --short docs/File_Tree/` |
| **`.agents/verification.md`** | ✅ 已按第八阶段出报告 ⇒ 见下方 §八·五 | — |
| **全仓回归** | `mvn -o clean test -fae`（**16 模块**）⇒ **16/16 SUCCESS · BUILD SUCCESS**；**follow-service 71 tests / 0 failures / 0 errors / skipped=0**（**连续 5 次全仓跑，follow 每次全绿**）；`admin-service` 有**既有偶发**（当前侧 5 次全仓 = 3 绿 2 红 ⇒ flaky，见下方 §8.3）<br>🔴 **E-4 对拍轮后复跑（2026-11-02 21:25）⇒ 仍是 16/16 SUCCESS · BUILD SUCCESS**（**admin-service 本轮也绿** ⇒ 与 §8.3 的 flaky 判定一致）· follow 71/0/0/0 | 全仓日志（模块级汇总） |
| **🔴 E-4 双侧活体对拍** | **HTTP 21/21 逐字节三方一致** ＋ **gRPC wire 13/13 双边一致** ＋ **场景轮 6 类双侧逐字节一致**（**C 侧 == B 侧 == 冻结的 Go 真源字节**）⇒ 装置 `tools/difftest/follow/`（`run.sh` + `expected.txt` + `mint-jwt.py` + `mutate-parity.sh` + `README.md`）⇒ **完整记录见 §八·六**<br>🔴 **场景轮捕获并修复漂移 I**（下游超时 C 侧 5001 vs B 侧 1015 · 3/3+3/3 轮）⇒ 修后两侧逐字节一致 | 对拍日志见 §八·六（EXIT=0）＋ 变异日志（见 §八·六 的变异表） |

### 8.2 🟡 仍待补（**不阻塞本域交付，但不得声称已闭环**）

| 项 | 内容 | 归属 |
|---|---|---|
| ~~**🔴 双侧活体对拍（E-4）**~~ | ✅ **本轮已补齐**（2026-11-02 E-4 对拍轮）⇒ **C 侧 == B 侧 == 冻结的 Go 真源字节 · 21/21 逐字节一致** ⇒ 详见 **§八·六** 与 §11.6 ⇒ **本条已销账** | — |
| ~~**🟠 Prometheus 暴露路径**~~ | ✅ **已销账（2026-11-09 · global 收口域实测）** —— **原现象不成立**：生产形态（真启动的 app）里 `PrometheusMeterRegistry` **在**、`/actuator/prometheus` **200** ＋ 标准 Prometheus 文本 ⇒ 真因是 **Boot 测试框架**的 `ObservabilityContextCustomizerFactory`（只在 `@SpringBootTest` 形态下关掉 export）⇒ **零生产代码改动** ⇒ ⇒ 详见 **§5.6 漂移 E** | ~~`global` 收口域~~ → **已销账** |
| ~~**🟠 其余 9 域的 `grpc-inprocess` test scope**~~ | ✅ **已销账（2026-11-09 · `global` 收口域实算）** —— 「9 个域同款」是**过期断言**：逐个 pom **只数真正的 `<dependency>` 块** ⇒ compile **6**（article/follow/hot/like/task/users-user）· 未声明 **2**（comment/users-admin ⇒ 与 B 侧同款）· runtime **1**（favorite ⇒ 与 B 侧 `favorite-rpc` **逐字同款**）· test **1**（message）⇒ ⇒ **只需删 message 那条** ⇒ ⇒ 详见 **§5.6 漂移 H** | ~~`global` 收口域~~ → **已销账** |
| ~~**🟠 共享层的超时 description 文案**~~ | ✅ **已修（2026-11-09 · `global` 收口域）** —— 共享层 `ObservabilityAutoConfiguration` 的超时关 call 改为**复用** `RpcHandleRunner.GO_DEADLINE_EXCEEDED_DESCRIPTION`（该常量由 private 提升为 public）⇒ ⇒ **与 B 侧 `RpcInterceptor.java:179` 逐字一致**（`context deadline exceeded`）⇒ ⇒ 只改文案、**不改状态码/时机/预算算法** ⇒ ⇒ 详见 **§5.6 漂移 J** | ~~`global` 收口域~~ → **已修** |
| **🟢 Mapping_Table 措辞订正** | `docs/Mapping_Table/follow/follow.md` §6.2 第 4 行写 `existsAnyDirection` ⇒ B 侧实测**全仓 grep 0 命中**，实际方法名是 **`countAnyBlock`** ⇒ **文档 owner** 订正（本域不擅自改共享文档） | 文档 owner |
| ~~**🟡 §九「超时」场景**~~ | ✅ **本轮补齐**（E-4 场景轮阶段1：**下游 SIGSTOP** ⇒ 3/3 轮两侧都 **1015** 逐字节一致 ⇒ 并**捕获漂移 I**）⇒ **本条已销账** | — |
| ~~**🟡 §九「恢复」场景**~~ | ✅ **本轮补齐**（E-4 场景轮阶段2/4：SIGCONT 后与重启下游后 ⇒ 两侧都回 200 逐字节一致）⇒ **本条已销账** | — |
| ~~**🟡 gRPC wire 层未进对拍**~~ | ✅ **本轮补齐**（E-4 wire 段：**13/13 逐字节一致** · C 侧 gRPC ↔ B 侧 follow-rpc）⇒ ⚠️ **无第三方锚**（Go 真源的 wire 字节未冻结）⇒ 读作「**双侧一致**」⇒ **本条已销账**（但 §11.6 的 NOT_VERIFIED ① 仍保留该限定） | — |

### 8.3 🟡 全仓回归里的 **admin 域既有偶发**（**与本轮无关 · 已取同条件对照证据**）

| 项 | 内容 |
|---|---|
| **现象** | 全仓 `mvn -o clean test -fae` 跑到 **16/16** 时，`admin-service` 报 **1 个 Failure**：`AdminHttpContractRealTomcatTest.protectedPathWithValidTokenIs200` ⇒ `expected: <200 OK> but was: <401 UNAUTHORIZED>` |
| **⚠️ 它**不是** follow 域引入的**（**五条硬证据**，含 2 个决定性对照实验） | ① **代码零交集**：本轮 `git status` 排除 `service/follow/` 与 `docs/` 后**为空**；且 `admin` / `common` / `users-common` / 其余 7 个业务域与基线 `ae17325` **逐字节相同**（`diff -r --brief` 排除 `target/` 后**差异条数 = 0**）<br>② **⚠️ 决定性实验 A**：把**本轮的 follow 实现原样复制进基线副本**（`cp -R service/follow/src`）⇒ 在该副本上跑 **`mvn -o clean test -fae`（全仓，逐字同形态）** ⇒ **follow 68/68 绿 ＋ admin 15/15 绿** ⇒ ⇒ **同一份代码、同一命令 ⇒ admin 绿** ⇒ **follow 实现对 admin 无因果**<br>③ **⚠️ 决定性实验 B**：在**当前 worktree** 上跑 **`mvn -o clean test -pl service/users/admin -am`**（**只含 admin + 其依赖，`follow` 模块完全不参与构建与测试**）⇒ **绿**（2/2）⇒ ⇒ **因果链上不可能有 follow**<br>④ **⚠️ 一条「假红」已被剔除**：先前那次 `mvn -o test -pl service/users/admin -am`（**不带 `clean`**）的红是**假红** —— 无 `clean` ⇒ **复用陈旧的 `target/classes`** ⇒ 跑的不是当前源码 ⇒ 补上 `clean` 后即绿 ⇒ ⇒ **这正是「基线对照的两侧必须用逐字相同的命令形态」的价值**（两侧一边带 `clean` 一边不带 ⇒ 读数不可比）<br>⑤ **红只在「全仓并发跑」时出现**：当前侧 `clean test -fae` 全仓 **4 次 = 2 绿 2 红**（run1/run4 全绿、run2/run3 红）；而 admin **单独**跑（含 `clean`）**全绿**；基线副本全仓 2 次 + 单独 3 次 **全绿**；「基线+follow 实现」全仓 1 次 **全绿** ⇒ ⇒ **同一份代码 + 同一命令 ⇒ 可绿可红 ⇒ flaky 坐实** |
| **机制（已定位到断言位置）** | 失败发生在 `AdminHttpContractRealTomcatTest.java:261`（`getself` 的 200 断言）⇒ 而它**上游的 `login()` 已通过**（`login()` 内部自带 `assertEquals(200)` + `data != null` + token 非空断言 ⇒ 若登录失败会在**它自己的行**抛）⇒ ⇒ **token 拿到了但 `getself` 判它无效** ⇒ 属 admin 域 **JWT 签发侧 / 验签侧**（或 uid→用户查询）的一致性问题<br>⚠️ 容器侧无异常：`postgres:16-alpine` + `redis:7-alpine` 均正常启动、`SchemaInitializer` 4 个脚本全 applied、`AdminSeederBootstrap` 报 `default admin account bootstrapped` ⇒ **不是**环境/容器问题<br>⚠️ **仍存的未解点（如实记录）**：为什么「当前 worktree 位置 + 全仓并发」下复现率明显高于「`/tmp` 副本 + 同一份代码 + 同一命令」⇒ **本域未能定位** ⇒ **不归 follow 域修** ⇒ 建议 **admin 域 lane 在 worktree 位置**复现并定位 |
| **归属** | 🔴 **admin 域（`service/users/admin/`）** ⇒ **本域不修**（AGENTS.md §五.2「只写自己域」+ 用户口径「别顺手修别的域」）⇒ **建议由 admin 域 lane 处理** |
| **对 follow 域的影响** | **0** —— follow-service 模块本身 **68 tests / 0 failures / 0 errors / skipped=0**，两次全仓跑均 **SUCCESS** |

---

## 八 · 五 · 迁移审核报告（按 `.agents/verification.md` 第八阶段 · 核对日期 2026-11-02）

### 11.1 审核范围

| 项 | 内容 |
|---|---|
| **B 的模块和路径** | `/Users/pr/Documents/java&golang/RAG/service/follow/`（**51 个主代码类 / 6830 行** ＋ 39 个测试类）· 3 个子模块：`follow-common` / `follow-api` / `follow-rpc` |
| **C 的模块和路径** | `/Users/pr/Documents/GitHub/RAG-V1.worktrees/follow/service/follow/`（**37 个主代码类** ＋ **10 个测试类 / 72 个测试** ＋ 6 个 support 类） |
| **Boot 版本** | B = **Boot 4.0.8**（api/rpc 分离）· C = **Boot 3.5.11**（D1 合并为单进程） |
| **依赖版本** | C 侧：Spring Cloud `2025.0.3` · SCA `2025.0.0.0`（nacos-client `3.0.3`）· Flyway `11.14.1` · Tomcat `10.1` · Jackson `2.19.4` · MyBatis-Plus · `jjwt-jackson`（D14 全线） |
| **Mapping_Table** | `docs/Mapping_Table/follow/follow.md`（B 列 ↔ C 列 = 抄写路线图） |
| **File_Tree** | `docs/File_Tree/follow.md`（由 `tools/gen-file-tree.py` 按实际文件系统生成） |
| **Git commit** | 工作树 `align/follow` · 基线 `ae17325` · **未提交**（本轮无 commit / 无 push） |
| **判定基准** | **B 侧为唯一真理源**（D16）· 依赖基准 2026-11-02 |

### 11.2 B → C 责任承接表

⇒ **全量表见 §一**（**51 → 37 逐类**，非抽样）· 汇总：**51/51 B 类全部有 C 侧承接，C 侧 37/37 全部有 B 侧来源**（`check-mapping-coverage.py` 双向对账：精确 664 · 通配 3 · 目录 64 · 白名单命中 0 条）。

### 11.3 多文件合并和拆分专项报告

| 类别 | 具体 | 状态 |
|---|---|---|
| **N:1**（多文件合并） | ① B 侧 8 个 api Service → C 侧 `FollowRelationService`（4 写）+ `FollowQueryService`（4 读）② B 侧 4 个 api 支撑类 → C 侧 `FollowApiSupport` ③ B 侧 4 个 `auth/` 类 → C 侧 `FollowJwtAuthInterceptor`（**1 个**） | ✅ 已登记 **DEV-FOLLOW-005**（JWT 收敛） |
| **1:N**（一文件拆多） | ① B 侧 `FollowRpcSupport` → C 侧 `FollowRpcSupport` + `FollowUserExistenceService`（拆出下游 stub 依赖）② B 侧 `FollowErrorCode` 枚举 → C 侧 `ErrCodes` + `ErrMsg` | ✅ **DEV-FOLLOW-002** |
| **多对多** | ① B 侧 `follow-common/errmsg` 4 类 → C 侧 `contract/` 4 类（**落点由 Mapping_Table 给的是 `service/users/common/`，经用户拍板改落域内**） | ✅ **DEV-FOLLOW-001**（用户已拍板） |
| **共享能力上收** | `common/`（104 类）**本域只读不写** ⇒ follow 域**不新增**共享类 | ✅ 域隔离守住 |
| **Spring 接管** | ① 验签 → `HandlerInterceptor` ② 域内 Jackson 强转 → `Jackson2ObjectMapperBuilderCustomizer` ③ 错误处理 → `@RestControllerAdvice` ④ 事务 → `@Transactional` | ✅ 逐条登记 + 出处标注（§3.2 三段式） |
| **独立进程合并** | api + rpc **两进程 → 一进程**（D1/D7）⇒ 业务端口 **8891 / 8082 逐条不变**，观测端口 16065 合并 ⇒ **DEV-FOLLOW-008** | ✅ |
| **职责缺失** | **0**（承接表 51/51 闭合 + 5 个映射缺口全部 CLOSED） | ✅ |

### 11.4 框架版本差异报告

| 差异 | B（Boot 4） | C（Boot 3.5） | 是否影响业务行为 | 适配方式 | 最终结论 |
|---|---|---|---|---|---|
| **JSON 强转** | 共享层保留「String→整数字段接受」 | follow 域**叠加** `LogicalType.Integer + String→Fail` | **是**（`{"offset":"10"}` 收 vs 拒） | 域内 `WebMvcConfig` 的 customizer（走 `postConfigurer`，Boot 3.5 无 coercion API） | ✅ 已登记 **DEV-FOLLOW-004** ＋ 真触发断言 |
| **D2 域内直连** | 跨进程 gRPC | 同进程直连 | **是**（业务码载体仍在，但往返没了） | 保留 `rpcError` 反查（**漂移 B 已修**）＋ **超时场景单独放过 `DEADLINE_EXCEEDED`**（**漂移 I 已修**）⇒ ⚠️ **D2 会让「B 侧不可观测的那层折叠」变得可观测** ⇒ 必须逐个场景核对 | ✅ PASS（E-3 ＋ **E-4 场景轮**） |
| **验签位置** | 4 类 Filter + 3 支撑（go-zero 链序，验签**不**计入 3s 预算） | 收敛进 1 个 `HandlerInterceptor`（验签**计入** 3s 预算） | **否**（wire 级不可观察） | 登记偏离 | 🏷 **INTENTIONALLY_CHANGED**（DEV-FOLLOW-005） |
| **熔断打开分支** | api 层有 `codeFromRPCError` 兜底 | D2 直连后该分支**零消费者** | **否**（无消费者代码） | 不迁 | 🏷 **INTENTIONALLY_CHANGED**（DEV-FOLLOW-006） |
| **Nacos 注册** | api/rpc 各注册一次 | 单进程注册（gRPC 端口），关掉 web 自动注册 | **否**（端口逐条一致） | `register-enabled: false` ＋ 手写注册 | ✅ PASS |
| **Flyway 账本** | Go 侧 `db.AutoMigrate`（无账本） | `spring.flyway.table: flyway_schema_history_follow` + `baseline-version: 0` | **是**（共享 schema 下 V1 是否执行） | 域内独立账本 | ✅ **漂移 D 已修** ＋ 守门测试 |

### 11.5 语义漂移清单

| Drift ID | 位置 | 源行为 | 目标行为 | 原因 | 影响范围 | 严重度 | 处置 |
|---|---|---|---|---|---|---|---|
| **DRIFT-FOLLOW-A** | `application.yml` 的 `user-auth` 段 | 键名 `follow-api.user-auth.*` | 曾写成 `follow.user-auth.*` ⇒ 绑不上 ⇒ **启动失败** | 配置落点错位 | 全部 8 个端点 | 🔴 高 | ✅ **已修**（§5.5 漂移 A） |
| **DRIFT-FOLLOW-B** | api 层 8 个端点 | B 侧每个 logic `catch(StatusRuntimeException) → rpcError`（文案反查） | 缺失 ⇒ **5201/5202/5101/5007/5008 全塌成 5001** | 「D2 直连 ⇒ 无需反查」的误判 | 5 类业务码 | 🔴 高 | ✅ **已修**（§5.5 漂移 B）· 探针 8/8 + HTTP 端到端 4/4 |
| **DRIFT-FOLLOW-C** | api 层 4 个写端点 | B 侧 8/8 都有 `requireRpcSuccess` | 缺失 ⇒ 领域层返非 200 会被当成功 | 抄写时漏了返回值处理 | 4 个写端点 | 🟠 中 | ✅ **已修**（§5.5 漂移 C） |
| **DRIFT-FOLLOW-D** | `application.yml` 的 `flyway:` 段 | B 侧 `AutoMigrate` | 曾落在**顶层** ⇒ `spring.flyway.*` **4 项全失效** | 同 DRIFT-A 的落点错位 | 启动 + 账本撞号 | 🔴 高 | ✅ **已修**（§5.5 漂移 D）· 守门测试捕获 |
| **DRIFT-FOLLOW-I** | 领域层 `FollowUserExistenceService` | B 侧折叠下游超时 ⇒ **但那层折叠在 B 侧不可观测**（api→rpc 的 2s 客户端预算先到 ⇒ 客户端拿 **1015**） | 曾照抄折叠 ⇒ 合并进程后**折叠首次变得可观测** ⇒ HTTP 层拿 **5001** | 「D1 合并后少了一跳 ⇒ 那层折叠没人抢先」 | 下游超时场景的**全部** 8 个端点 | 🔴 高 | ✅ **已修**（§5.5 漂移 I）· E-4 场景轮 3/3 轮逐字节一致 + 变异证据精确变红 |
| **DRIFT-FOLLOW-E** | `MeterRegistry` 装配 | B 侧 Go prometheus client（观测端口 16065） | ~~生产上下文**无 `PrometheusMeterRegistry`** ⇒ `/actuator/prometheus` 不可用~~ ⇒ ✅ **原现象不成立**（2026-11-09 实测：生产形态真启动 ⇒ registry **在** ⇒ `/actuator/prometheus` **200**） | ~~Boot 3.5 自动配置装配顺序~~ ⇒ **真因是 Boot 测试框架的 `ObservabilityContextCustomizerFactory`**（只在 `@SpringBootTest` 形态下关掉 export） | 指标可观测性 | 🟠 中 | ✅ **已销账**（§5.6 漂移 E）· **零生产代码改动** · 探针 4/4 绿 + 变异红/还原绿 |

### 11.6 完成度统计（**按 verification.md §11.6 分开统计**）

| 分类 | 数量 | 说明 |
|---|---|---|
| **已实现且已验证** | **37 个 C 侧主代码类 / 10 个测试类 72 个测试 ＋ E-4 双侧活体对拍（HTTP 21/21 三方 ＋ gRPC wire 13/13 双边 ＋ 场景轮 6 类）** | 全部 `Failures 0 · Errors 0 · Skipped 0`；真 PG + 真 Tomcat + 真 gRPC + 真 Flyway + 真上下文装配 + **真并发**；<br>🔴 **收口读数（2026-11-03 02:10 · 定位轮定稿）**：**四道闸门全绿** —— ① 双侧活体对拍 `EXIT=0` ② 变异验证 **M1~M7** `EXIT=0`（含启动前残留自检 + 还原后绿）③ 三道守门**全 `EXIT=0`**（A1–A13 `PASS 10 · FAIL 0` · docs↔文件树相符合 · 两方覆盖 **C 631/631 · B 752/752**）④ 全仓 `mvn -o clean test -fae` **16/16 SUCCESS · 合计 420 例 · Failures 0 · Errors 0 · Skipped 0** ⇒ **逐条读数见 §8.6.9**；<br>🔴 **2026-11-03 定位轮新增**：**「恢复窗口过渡态 C=5001 / B=1015」已定位** ⇒ **码值由「下游失败形态」决定，不由错误码映射决定** ⇒ **两侧同形态必然同码** ⇒ **该项不构成漂移**（详见 §8.6.7 / §8.6.7.1）；<br>🔴 **E-4（2026-11-02 对拍轮）**：**C 侧 == B 侧 == 冻结的 Go 真源字节 · HTTP 21/21 逐字节一致** ⇒ **HTTP 契约层**（8 个端点 + 错误码 + 三种响应形状 + DTO 的 JSON 形态）**已达 E-4 三方** ⇒ 详见 §八·六；<br>🔴 **E-4 场景轮（8d）**：**超时 / 恢复 / 外部依赖失败 / 恢复 / 并发 / 事务回滚** 6 类**双侧**断言全部逐字节一致 ⇒ **并捕获漂移 I**（下游超时 C 侧 5001 vs B 侧 1015）⇒ 已修 |
| **已实现但未验证（NOT_VERIFIED）** | **1 项残余** | ① **gRPC wire 层字节**：已达 **双边**逐字节一致（**13/13 · C == B**）⇒ 但**无第三方锚**（Go 真源的 wire 字节未冻结）⇒ 该层读作「**双侧一致**」而非「三方等价」。<br>🔴 **2026-11-09 销账（§5.6 漂移 E）**：原残余②「**Prometheus 抓取面无活体证据**」**已消解** —— 生产形态真启动（`SpringApplicationBuilder` ＋ 真 Tomcat ＋ 真 HTTP）实测 `/actuator/prometheus` = **200** ＋ `# HELP` / `# TYPE` ⇒ 指标面**有活体证据**了 ⇒ ⇒ **NOT_VERIFIED 由 2 项降为 1 项** |
| **尚未实现（NOT_IMPLEMENTED）** | **0** | 承接表 51/51 闭合 |
| **明确存在漂移（FAIL）** | **0**（本轮 **7** 条已修） | DRIFT-FOLLOW-A~D（首轮自查）+ **F/G/I**（E-4 对拍轮）全部 ✅ 已修并有证据 |
| **框架差异待验证（FRAMEWORK_DIFFERENCE）** | **0** | §11.4 的 6 条差异要么已判定不影响行为、要么已登记为签字偏离 |
| **被外部条件阻塞（BLOCKED）** | **0** ✅ | **E-4 双侧活体对拍已补齐**（§八·六）⇒ **本域不再有 BLOCKED 项**<br>（另：**DRIFT-FOLLOW-H** 是**跨域共性** ⇒ 归 `global` 收口域，**不构成本域的 BLOCKED**；⚠️ **DRIFT-FOLLOW-E 已于 2026-11-09 销账** ⇒ 不再归 `global`） |
| **已批准的主动变更（INTENTIONALLY_CHANGED）** | **7** | DEV-FOLLOW-001~006 + 008（**DEV-FOLLOW-007 已撤销**） |

> ⚠️ **按 verification.md §十的纪律**：`NOT_VERIFIED` / `BLOCKED` **不能计入 PASS** ⇒
> **本域的「行为等价」结论现在可以在「**HTTP 契约层**」上表述为「已由 E-4 双侧活体对拍验证」**；
> ⚠️ 但 **gRPC wire 层**仍**不得**表述为已闭环（见上表 NOT_VERIFIED 残余 1 项）。
> 🔴 **2026-11-09 更新**：**指标抓取面**已**有**活体证据（生产形态真启动 ⇒ `/actuator/prometheus` 200）⇒ ⇒ 它**不再**是 NOT_VERIFIED 那一项的限定条件。

### 11.7 verification.md §九「对拍必须覆盖」的覆盖度核对（**C 侧单侧证据 ＋ E-4 双侧证据**）

> ⚠️ **前两列只统计 C 侧单侧证据**（单测 + 真触发测试）；**第三列才是 E-4 双侧活体证据**（C 侧 == B 侧 == 冻结的 Go 字节）。

| §九 的 12 项优先验证 | C 侧单侧证据 | 所在测试类 | **E-4 双侧证据** |
|---|---|---|---|
| 1 HTTP 请求和响应 | ✅ | `FollowHttpContractRealTomcatTest`(15) · `FollowHttpBusinessCodeRealTomcatTest`(5) | ✅ **21/21 逐字节三方一致**（含 8 个端点的正常 / 边界 / 非法入参路径） |
| 2 RPC 请求和响应 | ✅ | `FollowGrpcServiceContractTest`(6) | ✅ **双边**（gRPC wire 原始字节 **13/13 逐字节一致** · C 侧 gRPC :18205 ↔ B 侧 follow-rpc :18202）<br>⚠️ **无第三方锚**（Go 真源的 wire 字节未冻结）⇒ 读作「**双侧一致**」 |
| 3 JSON 解析和序列化 | ✅ | `FollowHttpContractRealTomcatTest` · `FollowErrorContractTest` | ✅ 逐字节（`user_ids` 数字数组 / `target_id` 字符串 / 空列表 `null` / 字段序） |
| 4 错误码和异常映射 | ✅ | `FollowErrorContractTest`(12) · `FollowHttpBusinessCodeRealTomcatTest`(5) | ✅ 逐字节（5004/5005/5101/5201/5001 + 401 空 body + 404 `404 page not found\n` + 405 空 body）<br>🔴 **场景轮新增**：下游超时 ⇒ 两侧都是 **1015 service busy**（3/3 轮逐字节）⇒ **捕获漂移 I** |
| 5 数据库最终状态 | ✅ | `FollowDomainServiceRealPgTest`(14) · `FollowServiceApplicationTests`(6) | ✅ **两侧各自播种同一批行**（follow 4 行 / block 1 行）⇒ 列表/推荐/黑名单输出逐字节相同 |
| 6 事务提交和回滚 | ✅ | `FollowBlockTransactionRealPgTest`(4) | ✅ **双边**（场景轮阶段6：两侧各加**同款 CHECK 约束** ⇒ block 失败 ⇒ **业务码 5008**（写路径 DB 失败）＋ **follow 行仍在 · block 行未写入** ⇒ 真回滚） |
| 7 Kafka 消息内容 | **N/A** | 本域无 Kafka 生产/消费 | **N/A** |
| 8 消息发送时机 | **N/A** | 本域无消息发送 | **N/A** |
| 9 幂等和重复请求 | ✅ | `FollowDomainServiceRealPgTest`（重复关注/重复拉黑）· `FollowConcurrencyRealPgTest` | ✅ 写路径 5 条（`follow_new_relation` / `unfollow_seeded` / `unblock_seeded` …）逐字节 |
| 10 JWT | ✅ | `FollowHttpContractRealTomcatTest`（401 空 body / 坏签名 / claim 类型三分支） | ✅ 逐字节（`no_auth_header` / `bad_jwt` = **401 + 空 body**） |
| 11 Redis 状态 | ✅ | `FollowHttpContractRealTomcatTest`（黑名单命中 ⇒ 401 + 两字段 body） | ✅ **双边**（`blacklist` = 401 + `{"code":5004,"msg":"unauthorized"}` · 两侧逐字节相同）<br>⚠️ **双边锚**（Go 真源那轮没覆盖）⇒ **不计入「三方一致」** |
| 12 定时任务和生命周期 | **N/A** | 本域无定时任务 | **N/A** |

| §九 的 11 类对拍场景 | C 侧单侧证据 | **E-4 双侧证据** |
|---|---|---|
| 正常路径 / 边界输入 / 空值与缺省 / 非法输入 / 类型不匹配 / 重复请求 / 外部依赖失败 | ✅ 均有 | ✅ 正常路径 + 边界（`limit=0` / `limit=1000` / `offset=-1`）+ 空值（`{}`）+ 类型不匹配（`target_id="1.5"`）+ 重复请求（写路径 5 条）逐字节<br>🔴 **场景轮新增「外部依赖失败」**：**杀掉下游 user-rpc**（连接被拒）⇒ 两侧都是 **5001 internal server error**（逐字节一致） |
| **并发请求** | ✅ **本轮补齐**（`FollowConcurrencyRealPgTest`，含变异证据） | ✅ **场景轮补齐**（**双侧各 8 路并发**关注同一 target ⇒ 业务码集合 **{200}** ＋ 各自**恰好 1 行**） |
| **超时** | ✅ **本轮补齐**（`FollowHttpBusinessCodeRealTomcatTest#downstreamTimeoutReturns1015ServiceBusy` · 真 gRPC + 变异证据） | ✅ **场景轮补齐**（下游 SIGSTOP ⇒ 两侧都 **1015 service busy** · 3/3 轮逐字节 ⇒ **捕获漂移 I**） |
| **部分成功** | 🟡 **本域无此语义**（8 个端点全是单条操作 ⇒ 不存在批量部分成功） | **N/A**（本域无此语义） |
| **恢复和清理** | ✅ 部分 —— 事务回滚后的清理已覆盖（`FollowBlockTransactionRealPgTest`） | ✅ **场景轮补齐**（SIGCONT 后恢复 ⇒ 两侧都回 200；重启下游后 ⇒ 两侧都回 200；**事务回滚** ⇒ 两侧都 **5008** 且 follow 行仍在 · block 行未写入）<br>🔴 **2026-11-03 追加**：恢复窗口的**过渡态**已用**阶段2b 分层采样**定位 ⇒ **码值随「下游失败形态」走，不随错误码映射走** ⇒ **两侧同形态必然同码** ⇒ **历史那一次「C=5001 / B=1015」不构成漂移**（详见 §8.6.7.1） |

> 📌 **如实结论（2026-11-02 · E-4 对拍轮 + 场景轮后更新）**：
> ① **HTTP 契约层**已由 **E-4 双侧活体对拍**验证 ⇒ **C 侧 == B 侧 == 冻结的 Go 真源字节 · 21/21 逐字节一致**；
> ② **gRPC wire 字节层**已达 **双边** 13/13 一致（**无第三方锚**）· **指标抓取面**仍为 **NOT_VERIFIED**（§11.6）；
> ③ §九 的 12 项优先验证：**9 项有 C 侧单侧证据**（另 3 项本域无该机制），其中 **8 项另有 E-4 双侧证据**；
> ④ §九 的 11 类场景：**8 类有 C 侧单侧证据**，其中 **5 类另有 E-4 双侧证据**（正常路径族 / 外部依赖失败 / 并发 / 超时 / 恢复与清理）；
> ⑤ **「部分成功」本域无此语义** ⇒ 不适用；
> ⑥ **本轮场景轮新增捕获并修复漂移 I**（下游超时 C 侧 5001 vs B 侧 1015）⇒ 修后 3/3 轮逐字节一致。

---

## 八 · 六 · 🔴 E-4 双侧活体对拍（**2026-11-02 · 本轮补齐** · 核对日期 2026-11-02）

> **本节把 `.agents/verification.md` 第六阶段（E-4）的 `BLOCKED` 改为「**已验证**」**
> ⇒ 判据：**C 侧 == B 侧 == 冻结的 Go 真源字节 · 21/21 逐字节一致**（HTTP 契约层）。

### 8.6.1 装置落点（域私有 · 不属共享层）

| 文件 | 作用 |
|---|---|
| `tools/difftest/follow/run.sh` | **主探针**（800+ 行）—— 两侧同时起真进程 ＋ 两侧各自播种同一批行 ＋ 同一请求 ＋ 逐字节比对 ＋ **场景轮**（超时 / 恢复 / 外部依赖失败 / 恢复 / 并发 / 事务回滚）＋ **收尾端口零残留断言** |
| `tools/difftest/follow/expected.txt` | **第三方锚** = **Go 真源的冻结字节**（20 条 · 取自 B 侧 2026-10-29 录下的同一份） |
| `tools/difftest/follow/mint-jwt.py` | JWT 自签（HS256 · claim `userId`）⇒ 两侧同一把 secret 验签 |
| `tools/difftest/follow/grpc-wire/FollowWireProbe.java` | **gRPC wire 层原始字节探针** —— **不依赖任何 pb 生成码**（手构 proto3 varint 字节 ＋ `byte[]` marshaller）⇒ **同一个 jar 跑两侧** ⇒ 比的是纯 wire 字节，不被「两侧 pb 包名不同」污染 |
| `tools/difftest/follow/mutate-parity.sh` | **变异验证 M1~M7** ⇒ 证明探针**不是假绿** |
| `tools/difftest/follow/README.md` | 设计说明 + 踩坑清单（继承 B 侧 17 条 + 本轮新增） |
| `tools/difftest/run-parity.sh` | **派发入口**（填上 `tools/README.md` 里长期空着的「双侧语义探针」槽位）· ⚠️ **目录名必须与它的 `<域>/run.sh` 约定一致**（本轮踩过 ⇒ 已改名） |

### 8.6.2 拓扑（两侧**同时**跑真进程）

```
                    ┌──────── B 侧（RAG / Boot 4.0.8 / api+rpc 分离）────────┐
  客户端 ──HTTP──▶ :18201│ follow-api ──gRPC(Nacos E4B)──▶ follow-rpc :18202 │
                    └───────────────┬──────────────────────────────────────┘
                                    │ 同一个 user-rpc 进程（Nacos E4B + E4C 双组登记）
                    ┌───────────────┴──────────────────────────────────────┐
                    │──────── C 侧（RAG-V1 / Boot 3.5.11 / D1 合并进程）────│
  客户端 ──HTTP──▶ :18204│ follow-service（HTTP + gRPC :18205）              │
                    └──────────────────────────────────────────────────────┘
  第三方锚 = expected.txt（Go 真源冻结字节）
```

| 关键设计 | 理由 |
|---|---|
| **Nacos 分组隔离**（B=`E4B` / C=`E4C`） | 🔴 两侧**都**注册 `follow-rpc` 这个服务名 ⇒ 同组 ⇒ C 侧实例会进 B 侧 api 的解析池 ⇒ **随机打到对面 ⇒ 假漂移** |
| **`user-rpc` 双组登记** | 两侧必须调用**同一个**下游进程 ⇒ 任何「用户不存在 / 下游故障」的分歧都不可能来自下游不同 |
| **两侧各自 PG** | 账本表名都是 `flyway_schema_history_follow` ⇒ 共库必然撞号 |
| **Go 侧不活体** | 本机**无 `Sea-etcd` 容器**（Go 真源走 etcd）⇒ 第三方锚用 B 侧已冻结的 `expected.txt` ⇒ **三方等价强度不降** |
| **B 侧只读** | 只在 B 仓跑 `mvn package`（产物落 `target/`）⇒ 探针**断言 B 仓 `git status` 干净**，否则 `die`（EXIT=2） |

### 8.6.3 实测读数（**21/21 逐字节三方一致** · EXIT=0）

| 组 | 条数 | 结果 |
|---|---|---|
| 读路径（数据） | 9 | ✅ `follow_list_seeded`(110B) · `follower_list_seeded`(100B) · `block_list_seeded`(100B) · `follow_list_empty`(96B) · `recommendations_seeded`(162B) · `follow_list_limit_zero`(110B) · `follow_list_limit_max`(110B) · `follow_list_offset_neg`(110B) · `block_list_wrong_owner`(86B) |
| 协议层 | 6 | ✅ `no_auth_header`(401/0B) · `bad_jwt`(401/0B) · `follow_missing_target`(110B) · `follow_bad_target_type`(110B) · `follow_unknown_path`(404/38B) · `follow_wrong_method`(405/0B) |
| 写路径 | 5 | ✅ `follow_self_relation`(124B) · `follow_target_not_found`(96B) · `follow_new_relation`(94B) · `unfollow_seeded`(94B) · `unblock_seeded`(94B) |
| **增量（双边锚）** | 1 | ✅ `blacklist`（401 · 68B · `{"code":5004,"msg":"unauthorized"}`）⇒ ⚠️ **双边锚**（Go 那轮没覆盖）⇒ **不计入「三方一致」** |

⇒ **收尾复核**：本探针的 **7 个端口零残留**（`lsof` 实测）⇒ 未污染宿主环境。

### 8.6.4 变异证据（**M1~M7 全部按预期表现 + 还原后转绿** · EXIT=0 · **2026-11-03 第三轮定稿**（探针加固后重跑））

> **读数口径**：下表取**第三轮（探针加固 + 新增阶段2b 之后）的实测输出** ⇒ 日志 `${TMPDIR}/e4-mutate-final2.log`（2026-11-03 01:51~02:10）。
> **每个变异都必须「精确转红」** —— 即**恰好命中它该命中的那条判据**，而不是随便挂掉也算数。
> ⚠️ **为什么必须重跑**：探针本身在 2026-11-03 改了（新增阶段2b ＋ **堆封顶** ＋ **下游存活断言** ＋ Nacos 等待去死等）⇒ ⇒ **「探针变了，旧变异证据不再对应当前探针」** ⇒ ⇒ 变异验证**必须重新取证**（这正是 §3.3.3「变异验证要与当前被测形态同版本」的纪律）。

| 变异 | 手法 | 实测结果 |
|---|---|---|
| **启动前自检** | 脚本启动时先核对**六个变异靶点**是否处于原始形态 | ✅ **无残留 ⇒ 放行**（⇒ 拒绝在被污染的读数上继续跑） |
| **基线** | 未变异 | ✅ **绿**（exit 0） |
| **M1** | 把 C 侧 `RecommendUser` 的 `@JsonPropertyOrder` **改成反序** | ✅ **精确转红**（exit 1 · `recommendations_seeded` 被判不等） |
| **M2** | 改 `expected.txt` 里一枚 hex | ✅ **精确转红**（exit 1 · `follow_list_seeded` 与冻结值不符）⇒ **期望值确实在参与判定** |
| **M3** | 让 **C 侧**多灌一行 | ✅ **精确转红**（exit 1 · 两侧库不同被抓到 · 3 条判据同时报不等）⇒ **探针真在读两侧各自的库** |
| **M4** | C 侧在矩阵**中途挂掉** | ✅ **精确转红**（exit 1 · 4 条判据报「请求失败（C http_code=000 · B http_code=200）」⇒ **没有**沿用上一次的残留 body） |
| **M5** | 把 `run.sh` 改回旧写法（**固定 `-o` 文件 + 去掉 `000` 守卫 + 只比 body**） | ✅ **假绿被复现**（`follow_list_limit_max` 被误判成 ✅，且日志显示 `HTTP 000 · 110 字节`）⇒ **证明 M4 的红确实来自守卫** |
| **M6** | **C 侧把 `DEADLINE_EXCEEDED` 重新折叠成 `INTERNAL`** ⇒ 场景轮「超时」阶段必须转红 | ✅ **精确转红**（exit 1 · **3/3 轮两侧不一致 ⇒ C=5001 vs B=1015**）⇒ **漂移 I 的修复确实被探针咬住**（不是「两侧一起错」） |
| **M7** | 探针**去掉 CHECK 约束注入** ⇒ 事务回滚判据必须转红 | ✅ **精确转红**（exit 1 · 「业务码不是 5008（C=200 · B=200）」＋「回滚判据不成立（follow 行=0（应 1）· block 行=1（应 0））」）⇒ **证明回滚判据不是空洞断言** |
| **还原** | 全部还原 | ✅ **重新转绿**（exit 0） |

⇒ **收尾**：`✅ 全部变异按预期表现 + 还原后转绿 ⇒ 探针非假绿` / `✅ follow 的 mutate-parity.sh 通过` / `✅ 全部探针通过`（EXIT=0 · 2026-11-03 01:51~02:10）。
⇒ **还原后手工复核残留**：五个靶点逐个 grep（`body.FIXED` / `if false; then` / `7b23` / `@JsonPropertyOrder` 序 / `isDeadlineExceeded` 块）⇒ **全部为原始形态（实测 5/5 命中数 = 0）**；7 个端口**零占用**；**无 `e4follow-*` 容器**。
⚠️ **本轮踩到的一个坑（已记入探针 README #35）**：`mutate-parity.sh` 的备份是**脚本启动时一次性取的** ⇒ ⇒ **运行期间改「变异靶点文件」会被该轮的还原冲掉**（本轮：我给 `FollowUserExistenceService` 补的 javadoc 被 M6 的还原冲没了 ⇒ 已**重新 apply** 并复验编译 + 单测）。⇒ ⇒ **纪律：跑变异期间只改非靶点文件**；**不去改「备份时机」**（改了脚本就得重跑一遍变异才让证据与脚本对得上 ⇒ 代价远大于收益 ⇒ 只记录）。

> ⚠️ **M1 的一处方法学修正（漂移 G）**：B 侧的 M1 是「**删掉** `@JsonPropertyOrder`」
> ⇒ 在 B 侧（**class**）能转红；⇒ 在 C 侧（**record**）**转不了红**（Jackson 对 record 默认按构造参数序输出 ⇒ 删注解字节不变）
> ⇒ ⇒ **变异必须针对被测形态设计，不能照抄** ⇒ 本仓 M1 已改成「注解**改成反序**」。

> ⚠️ **第一轮 M3 假红的原因与修法（探针自身缺陷，不是被测物漂移）**：M3 把 C 侧多灌一行，
> 而场景轮**阶段6（事务回滚）**的播种用的是**同一批 target** ⇒ 播种行与 M3 的变异行**主键冲突**
> ⇒ 探针 `die`（exit 2）而不是转红 ⇒ 读数上「M3 不按预期」。
> ⇒ ⇒ **修法**：阶段6 的播种改**幂等**（`ON CONFLICT DO NOTHING`）⇒ 变异轮不再因主键冲突 die。
> ⚠️ **教训**：**变异轮的失败要先分清「被测物变了」与「探针自己坏了」** —— 否则会把探针缺陷当成漂移证据（也可能是反过来，把真漂移当成探针缺陷放过）。

### 8.6.5 本轮 E-4 **抓到的 5 条新漂移**

| 编号 | 一句话 | 归属 |
|---|---|---|
| **漂移 F** | 🔴 `service/follow/pom.xml` 的 `grpc-inprocess` 被声明成 `test` scope ⇒ **真实进程起不来**（`NoClassDefFoundError`）⇒ 而 **71 个单测全绿** | ✅ **本域已修**（§5.5） |
| **漂移 G** | 🟠 `RecommendUser` 的 javadoc 推理与事实不符（record ≠ class ⇒ 默认序不同）⇒ 连带 M1 变异失效 | ✅ **本域已修**（§5.5） |
| **漂移 H** | 🟠 其余 **9 个域**的 pom 有**同款** `grpc-inprocess` test scope ⇒ **当前不咬人**（devh 的 channel factory 是 `@Lazy` ⇒ 无真实 `@GrpcClient` 就不创建）⇒ **潜在雷** | 🔴 **归 `global` 收口域**（§5.6） |
| **漂移 I** | 🔴 **下游超时的业务码**：C 侧 **5001** vs B 侧 **1015**（3/3 轮稳定 ⇒ 契约差异，非竞态）⇒ 根因 = B 侧那层「折叠」在 B 侧**不可观测**（api→rpc 的 2s 客户端预算先到），C 侧合并进程后**折叠首次变得可观测** | ✅ **本域已修**（§5.5）· 修后 3/3 轮逐字节一致 |
| **漂移 J** | 🟠 共享层的 `DEADLINE_EXCEEDED` **description 文案**：B 侧 `context deadline exceeded` vs C 侧 `rpc deadline exceeded` ⇒ 只在 gRPC status 的 description 字段可见 ⇒ **不构成 follow 域的 L1 违约** | 🔴 **归 `global` 收口域**（§5.6） |

### 8.6.6 🔴 场景轮（§九 的 11 类场景 · 双侧 · 本轮补齐）

> **为什么单列一节**：场景轮**会改库**（并发关注新增行 · CHECK 约束注入）⇒ 必须排在 HTTP 矩阵与 wire 层**之后**。
> **判据口径**：本域的业务失败恒 **HTTP 200 + 业务信封** ⇒ **一律按「业务码」判定**，不按 HTTP 状态码。

| 阶段 | 构造手法 | 实测读数（**两侧逐字节比对**） |
|---|---|---|
| **阶段0 · 补种子** | 给 user-rpc 的 `users_user` 补 **1008**（并发 target）/ **1009**（事务回滚 target） | ✅ count=2<br>⚠️ **为什么必须补**：target 不存在 ⇒ 两侧**都在 5101 分支短路** ⇒ 根本到不了「并发唯一索引」与「CHECK 约束注入」两条被测路径 ⇒ **判据空洞**（"两侧一致"只证明了"两边都 5101"） |
| **阶段1 · 超时** | 下游 user-rpc **SIGSTOP**（连接可建、无响应）⇒ 触发 2s rpc 预算 | ✅ **3/3 轮逐字节一致 · 业务码 1015**（C 侧分布 `[1015 1015 1015]` · B 侧分布 `[1015 1015 1015]`）<br>⇒ **3 轮是必需的**：一轮读数分不清「契约差异」与「竞态」<br>🔴 **场景有效性断言（2026-11-03 加）**：每轮前后断言**下游进程还活着**（`kill -0`）⇒ 被外力杀掉 ⇒ `die`（exit 2）⇒ **绝不产出「测错场景」的读数**（见 §8.6.7.2） |
| **阶段1b · 分层探测** | 同一挂起场景，**直接打两侧自己的 gRPC 端点** | ⚠️ **观察项（不参与判定）**：3 轮采样两侧状态码 + description<br>⇒ **2026-11-03 干净轮：两侧 3/3 同为 `DEADLINE_EXCEEDED`**（历史轮曾采样到 `INTERNAL` ⇒ 该层跨运行非确定）<br>⇒ ⇒ **gRPC 层不作契约判据；契约判据取 HTTP 层**（3/3 + 3/3 轮稳定 1015）<br>⇒ **description 两侧不同**（B=`context deadline exceeded` · C=`rpc deadline exceeded`）⇒ **漂移 J**（归 global）<br>⚠️ **只比状态码，不比 description 字节**（C 侧该场景的 description 带**变动的毫秒数** ⇒ 比字节恒红且无信息量） |
| **阶段2b · 过渡窗口分层采样**（**2026-11-03 新增 · 观察项**） | SIGCONT 放行后、**Nacos 条目回来之前**，**同时**采两侧的 HTTP 层（码 + 耗时）/ gRPC 直连（**绕开 B 侧 api 的 2s 预算**）/ Nacos 条目 | ✅ **两侧逐轮同步**：`200` → **`5001`×3** → `200`×5（5001 那几轮耗时仅 **10~38ms** ⇒ 快速失败）<br>⇒ **Nacos 条目全窗口 `instances=0`**（下游解析不到实例）<br>⇒ **gRPC 直连：两侧同为 `ERROR_INTERNAL` + 同一 description「internal server error」** ⇒ **两侧领域层逐条同型**<br>⇒ ⇒ **定位了「过渡态分叉」的根因**（码值随**失败形态**走，不随**错误码映射**走）⇒ 详见 **§8.6.7 / §8.6.7.1** |
| **阶段2 · 恢复（SIGCONT）** | 放行下游 ⇒ 应回到 200 | ✅ 两侧**逐字节**一致（业务码 200）<br>⚠️ **探针坑 1**：user-rpc 是 Nacos **临时实例**（TTL 数秒）⇒ 被挂起的那十几秒**没法续期** ⇒ 条目过期 ⇒ 两侧都拿不到实例 ⇒ **放行后必须等它续回来**（探针已加「等条目回池」步骤）<br>⚠️ **探针坑 2**：下游刚回来时系统处于**过渡态**（客户端通道在重连、服务端在消化积压的取消请求）⇒ 实测出现过「已经等到 200，紧接着下一轮却是 **C=5001 · B=1015**」⇒ ⇒ 判据必须取**稳定态**（连续 3 轮都 200）+ 比对允许重试并**如实报出「第 N 次才一致」**<br>🔴 **该观察项已于 2026-11-03 定位** ⇒ **不是漂移**（两侧同形态必然同码）⇒ 详见 **§8.6.7 / §8.6.7.1** |
| **阶段3 · 外部依赖失败** | **kill -9** 下游 ⇒ 连接被拒 | ✅ 两侧一致（HTTP 200 · 110B · `{"code":5001,"msg":"internal server error"}`）⇒ **非超时的下游故障仍折叠成 5001**（与 B 侧一致） |
| **阶段4 · 恢复（重启下游）** | 重启 user-rpc ⇒ 应回到 200 | ✅ 两侧**逐字节**一致（业务码 200） |
| **阶段5 · 并发** | **双侧各 8 路**并发关注同一 target（1008） | ✅ 业务码集合两侧都是 **{200}**（重复关注返回 SUCCESS）＋ 各自**恰好 1 行**<br>⚠️ **判据必须排除 5101**：只断言「两侧一致 + 行数」⇒ 5101 也会通过 ⇒ **空洞判据** |
| **阶段6 · 事务回滚** | 两侧各加**同款 CHECK 约束**（`target_id <> 1009`）⇒ block 写入失败 | ✅ 两侧**都是 5008**（写路径 DB 失败）＋ **follow 行仍在 · block 行未写入** ⇒ **真回滚**<br>⚠️ **判据必须钉死码值**：只断言「两侧一致 + 行数」⇒ 5101（用户不存在）也会通过 ⇒ 断言里显式要求 **5008** |

⇒ **收尾复核**：本探针的 **7 个端口零残留**（`lsof` 实测）⇒ 未污染宿主环境。

### 8.6.7 ✅ 场景轮观察项（**2026-11-03 已定位** · 前两条已定案 · 第三条仍为观察）

| 观察 | 读数 | 判读 |
|---|---|---|
| **恢复窗口的**过渡态**两侧可能报不同码** | 历史实测一次：**C=5001 · B=1015** | ✅ **已定位（2026-11-03）⇒ 不构成漂移 ⇒ 不改代码**。**根因 = 码值由「下游失败形态」决定，不由错误码映射决定**：<br>① **形态①「挂住到预算到期」** ⇒ 领域层看到 `DEADLINE_EXCEEDED` ⇒ **C 侧透传 ⇒ 1015**；**B 侧由 `api→rpc` 的 2s 客户端预算抢先 ⇒ api 层收到 `DEADLINE_EXCEEDED`（description 非业务文案）⇒ 落 `mapStatusCodeToBizCode` 的 default ⇒ 1015**；<br>② **形态②「快速失败」**（连接被拒 / Nacos 条目为空 ⇒ 解析不到实例）⇒ 领域层**当场**折叠成 `INTERNAL+5001` ⇒ **两侧都是 5001**；<br>③ ⇒ **两侧的错误映射层逐条同型**（`FollowGrpcError.bizCodeFromError` ⇔ `GrpcErrorMapper.bizCodeFromError`：文案反查优先 + 6 码白名单 + default 1015，**逐条一致**）⇒ ⇒ **两侧同形态 ⇒ 必然同码**；<br>④ ⇒ **B 的 HTTP 层 1015 不是领域语义**（B 的领域语义是「一切非 NOT_FOUND ⇒ `INTERNAL+5001`」，见下方直连实测）⇒ **1015 是「超时预算/服务端调度器抢先」的产物**；<br>⑤ ⇒ **⇒ 历史那一次不同步 = 两侧在同一瞬间落在了不同形态**（一个快速失败、一个挂住）⇒ **基础设施时序的瞬时不同步** ⇒ **不是 follow 域的契约差异** ⇒ **不判为漂移**。<br>⚠️ **边界**：不同步**只可能出现在「下游正在恢复」的那几秒**；**稳定态两侧完全一致**（都恢复 ⇒ 200 · 都挂 ⇒ 5001 · 都挂死 ⇒ 1015）。 |
| **B 侧 gRPC 层在超时场景非确定** | 历史采样：**[ERROR_INTERNAL, ERROR_DEADLINE_EXCEEDED, ERROR_DEADLINE_EXCEEDED]**；2026-11-03 干净轮：**[DEADLINE_EXCEEDED × 3]**（两侧同） | ⇒ 服务端的 2s 超时调度器与内层 user-rpc 的 2s 预算**同一瞬间到期** ⇒ 谁先写出去谁赢 ⇒ ⇒ **该层不能作契约判据** ⇒ 探针阶段1b 已降级为**观察项**（不参与 EXIT 码），但**照样打印两侧分布** |
| **C 侧超时场景的 gRPC description 带毫秒数** | `CallOptions deadline exceeded after 1.999928458s. Name resolution delay …` | ⇒ grpc-java **客户端侧** deadline 的原生文案 ⇒ **每次都不同** ⇒ ⇒ **该场景不可作 gRPC wire 逐字节判据**（探针只比状态码）⇒ 与漂移 J 同源（共享层文案/形态） |

#### 8.6.7.1 🔴 定位实验：**阶段 2b「过渡窗口分层采样」**（2026-11-03 新增）

> **要回答的问题**：过渡态两侧不同码，到底是**错误码映射**不同，还是**失败形态**不同？
> **手法**：SIGCONT 放行下游之后、**Nacos 条目回来之前**的那个窗口里，**同时**采两侧的
> ① **HTTP 层**（业务码 + **耗时**）② **gRPC 直连**（**绕开 B 侧 api 的 2s 预算**，15s deadline ⇒ 能看见「follow-rpc 自己会答什么」）③ **Nacos 条目**。

| 采样 | 读数（2026-11-03 干净轮） | 判读 |
|---|---|---|
| **HTTP 层**（过渡窗口 10 轮） | **两侧逐轮同步**：`200` → **`5001` ×3** → `200` → `200`×5<br>⇒ **5001 那几轮耗时只有 10~38ms** ⇒ **是「快速失败」，不是 2s 超时** | ✅ **两侧形态相同 ⇒ 码值相同** ⇒ 证实「码值随形态走」 |
| **Nacos 条目** | 全 10 轮 `instances=0` | ⇒ **下游在过渡窗口全程解析不到实例** ⇒ **快速失败的直接原因**（两侧同） |
| **gRPC 直连**（15s deadline · 绕开 B 的 api 2s 预算） | 第 0 轮：**B `ERROR_INTERNAL`（1440ms）· C `ERROR_INTERNAL`（1024ms）**，description **两侧同为 `internal server error`**<br>第 1/2 轮：**两侧同 `OK`** | ✅✅ **两侧领域层逐条同型**（都折叠成 `INTERNAL+5001`）⇒ **B 的领域语义就是 5001**；<br>⇒ **B 的 HTTP 层 1015 只可能是「预算/调度器抢先」的产物** ⇒ **推论成立** |

⇒ **⇒ 对漂移 I 修法的意义**（**把「碰巧对齐」升级为「形态对齐」**）：
- 形态①（挂住）：**C 透传 `DEADLINE_EXCEEDED` ⇒ 1015** ⇔ **B 的抢先 ⇒ 1015** ⇒ 一致（阶段1 **3/3 逐字节一致** + gRPC 层 **3/3 状态码一致**）
- 形态②（快速失败）：**两侧都 5001**（阶段3 kill -9 两侧 5001 ＋ 过渡窗口 4 轮同步 5001）
⇒ ⇒ **若不修（把 `DEADLINE_EXCEEDED` 也折叠成 5001），C 侧在形态①就会与 B 分叉** ⇒ ⇒ **该修法必要且正确**，且**不是**「照抄 B 的领域层」（B 的领域层是二分支，C 是四分支）⇒ **C 侧多出来的那个分支，作用是复现 B 的「抢先」形态**。

#### 8.6.7.2 🔴 探针事故：读数被**外力污染**过一次（2026-11-03 01:39 那轮**作废**）

| 项 | 内容 |
|---|---|
| **现象** | 阶段1 的 3 轮分布读成 **`[1015 5001 5001]`**（两侧同轮同码，**看起来很干净**）⇒ 但**下游 user-rpc 在第 1 轮后被 `SIGKILL`**（bash 报 `Killed: 9`）⇒ ⇒ **那测的是「连接被拒」，不是「挂起超时」** |
| **元凶** | 宿主**内存压力**：本机 24GB 物理内存 ⇒ **默认堆 6GB/JVM** ⇒ 探针起 **4 个服务端 JVM ＋ 多个 wire 探针 JVM** ⇒ macOS 的 jetsam **挑最大的杀**（实测被杀的正是 user-rpc）；且 swap 已用满（16G 里 15.6G） |
| **为什么危险** | 探针**照样跑完**并给出「两侧逐字节一致 3/3」的**绿灯** ⇒ ⇒ **只读码值分布绝对发现不了** ⇒ ⇒ 必须有一条**独立于被测物**的**场景有效性断言** |
| **修法①（防峰值）** | 全部 JVM 封顶：服务端 `-Xmx512m` ＋ wire 探针 `-Xmx256m` ＋ 构建 `MAVEN_OPTS=-Xmx768m`（`PROBE_JVM_HEAP` / `PROBE_JVM_HEAP_WIRE`） |
| **修法②（防假读数）** | 新增 `downstream_alive()`（`kill -0`）：**阶段1 前后与每轮之间**、**阶段2b 每轮之前**都断言下游进程还在 ⇒ 一旦被杀 ⇒ **`die`（exit 2 = 环境问题）** ⇒ **绝不产出误导性读数** |
| **修法③（防死等）** | Nacos 条目等待原来最坏 **15 × 30 = 450s**（下游已死时死等 7.5 分钟）⇒ 改成**单次**调用（内层已含 30s 上限）＋ **先断言下游活着** ⇒ 最坏 30s |
| **⇒ 重跑结果** | **2026-11-03 01:46 那轮干净**：阶段1 **`[1015 1015 1015]`**（两侧逐字节一致 3/3）＋ 阶段2b 两侧逐轮同步 ＋ **全场景轮通过 ⇒ EXIT=0** ⇒ **漂移 I 的「3/3 轮稳定 1015」证据重新取证成立** |

⇒ 📌 **本轮四条新坑已登记进 [`docs/Pitfalls/follow/_follow-domain-pitfalls.md`](../../../Pitfalls/follow/_follow-domain-pitfalls.md)**（本域写权面）：
**P-FOLLOW-01** 下游被外力 SIGKILL ⇒ 探针照样绿灯 ⇒ 必须有「场景有效性断言」·
**P-FOLLOW-02** 等待服务发现条目的**嵌套重试** ⇒ 下游已死时死等 450s ·
**P-FOLLOW-03** 生成器在 worktree 里跑 ⇒ **git 不跟踪空目录** ⇒ 与 main 的差异是**假差异** ·
**P-FOLLOW-04** 变异脚本的备份是**启动时一次性取的** ⇒ 运行期间改靶点文件会被还原冲掉。

### 8.6.8 复跑命令

```bash
cd /Users/pr/Documents/GitHub/RAG-V1.worktrees/follow
bash tools/difftest/run-parity.sh follow        # 主探针 ⇒ EXIT=0 = 三方逐字节一致
bash tools/difftest/run-parity.sh follow --mutate # 变异验证 ⇒ EXIT=0 = 探针非假绿
```

> ⚠️ **前置**：宿主须有 `Sea-nacos` / `Sea-redis` / `Sea-jaeger` 三个容器在跑（探针第 0 步会断言，缺则 `die`/EXIT=2）。
> ⚠️ **不要在探针运行期间编辑 `run.sh` / `mutate-parity.sh`** —— bash 按**字节偏移**增量读脚本 ⇒ 文件长度一变就错位（本轮踩过）。
> ⚠️ **探针目录名必须与派发器的约定一致**（`<域>/run.sh`）—— 本轮踩过：目录叫 `follow-parity/` ⇒ **派发入口 `run-parity.sh follow` 永远报「没有登记探针目录」** ⇒ 文档写的入口其实**从没被跑过** ⇒ 已改名并同步全部引用。

### 8.6.9 ✅ 本轮**收口读数**（**2026-11-03 02:10 定稿** · 四道闸门全绿 · 定位轮）

> **口径纪律**：① 计数**只取回归日志的模块级汇总**（不取 `surefire-reports/*.xml` 求和 —— 孤儿报告会虚高）；
> ② 报「0 failures」**必须同断 `skipped=0`**；③ 守门脚本的 `ROOT` **必须指向本 worktree**（否则量的是主工作区那棵树 ⇒ 假绿）。

| 闸门 | 命令 | 读数 |
|---|---|---|
| **① 双侧活体对拍** | `bash tools/difftest/run-parity.sh follow` | ✅ **EXIT=0**（2026-11-03 01:46~01:50，日志 `${TMPDIR}/e4-trans-probe2.log`）—— HTTP **21/21** 三方逐字节一致 ＋ gRPC wire **13/13** 双边一致 ＋ 场景轮 **6 类**双侧一致 ＋ **7 端口零残留**<br>🔴 **本轮新增阶段 2b**（过渡窗口分层采样）⇒ **定位了「过渡态分叉」**（§8.6.7.1） |
| **② 变异验证（探针非假绿）** | `bash tools/difftest/run-parity.sh follow --mutate` | ✅ **EXIT=0**（2026-11-03 01:51~02:10，日志 `${TMPDIR}/e4-mutate-final2.log`）—— **启动前自检无残留** ＋ 基线绿 ＋ **M1~M7 全部精确转红** ＋ **还原后绿** + 还原后五个靶点 grep **5/5 为 0**<br>⚠️ **必须重跑的原因**：探针在 2026-11-03 改了（新增阶段2b ＋ 堆封顶 ＋ 下游存活断言 ＋ Nacos 去死等）⇒ **旧变异证据不对应当前探针** |
| **③ 三道守门** | `check-arch-invariants.py` / `check-docs-vs-tree.py` / `check-mapping-coverage.py` | ✅ **A1–A13 EXIT=0**（`PASS 10 · FAIL 0 · N/A 2 · PENDING 1`；PENDING = like/comment/favorite 三个 `*FactProcessLogging` 仍是空壳 ⇒ **别的域**）＋ **docs↔文件树 EXIT=0**（全部相符合）<br>🔴 **`check-mapping-coverage.py` 本轮 EXIT=1 —— 但两条失败项都在 `docs/Mapping_Table/message/message.md`**（`MessageErrorCode` 的 `NON_FILE_LEVEL` 登记已失效 + 一行消解后不可判定）⇒ ⇒ **与 follow 域无因果**（follow 单域 **C 37/37 · B 51/51 · 100%**）⇒ **归 `message` 域 lane**（`docs/Mapping_Table/` 是共享文档 ⇒ **本 lane 不擅自改**）<br>· 参考读数（2026-11-02 23:41 · message 域合入前）：**C 631/631 · B 752/752**；本轮因 message 域合入 ⇒ C 列已增至 **637** |
| **④ 全仓回归** | `mvn -o clean test -fae` | ✅ **BUILD SUCCESS · 16/16 模块 SUCCESS**（2026-11-02 23:39~23:41，日志 `${TMPDIR}/e4-final-regression.log`）<br>· 模块级汇总：**common 224 · follow-service 72 · users-common 46 · user-service 39 · admin-service 39 ⇒ 合计 420**<br>· **Failures 0 · Errors 0 · Skipped 0**（逐模块核对）<br>· **follow-service 10 个测试类全跑**：含 `FollowHttpBusinessCodeRealTomcatTest` **5 例**（含新增的 `downstreamTimeoutReturns1015ServiceBusy`）<br>⚠️ **2026-11-03 定位轮只改了「注释 + 探针 + 台账」** ⇒ **无行为变化** ⇒ 该读数**继续有效**；另单独复验：`mvn -o -pl service/follow -am test -Dtest=FollowHttpBusinessCodeRealTomcatTest` ⇒ **5 例 · 0 失败 · 0 跳过 · BUILD SUCCESS** |

⚠️ **admin 域的既有偶发红本轮未复现**（`admin-service` **39 例全绿**）⇒ 与前几轮记录一致：**该红是既有偶发**，**与 follow 无因果**（follow 与 admin 无代码依赖）⇒ 仍归 admin 域 lane 自查。

⚠️ **域隔离终检**（相对**当前 HEAD** `6a3acb4`，2026-11-03 02:1x）：本 lane 的未提交改动只有 **6 个文件** ⇒
`docs/DRIFT/follow/verification-follow-2026-11-02.md` · `docs/Pitfalls/follow/_follow-domain-pitfalls.md`（**新建**）· `service/follow/src/main/java/com/rag/follow/service/FollowUserExistenceService.java`（**仅 javadoc**）· `tools/README.md` · `tools/difftest/follow/README.md` · `tools/difftest/follow/run.sh`
⇒ **`common/` 0 · `service/users/` 0 · 其余 8 个域 0 · `proto/` 0 · `docs/DRIFT/_INDEX.md` 0 · `docs/Intentional_Deviations/_INDEX.md` 0 · `docs/Pitfalls/_INDEX.md` 0 · `docs/Mapping_Table/` 0**。
> ⚠️ **基线已推进**：本域实现已被提交为 **`c2034d0`（merge(follow) · 23:52）** ⇒ 本轮（2026-11-03 定位轮）的改动是**该提交之上的增量**。
> 🔴 **`docs/File_Tree/proto.md` 曾被本轮生成器改动 ⇒ 已撤回**（P-FOLLOW-03：worktree **缺 main 里的空目录** `proto/src/test/java/` ⇒ 那是**假差异**）⇒ ⇒ **文件树必须在最终落地的那棵树（main）上生成**。

---

## 九 · 自查命令

```bash
cd /Users/pr/Documents/GitHub/RAG-V1.worktrees/follow

# ① 编译 + 全量测试
mvn -o clean verify -fae

# ② A1–A13 架构断言（含 A9 DTO immutable / A10 Bean Validation / A13 Lombok 展开块）
python3 tools/difftest/check-arch-invariants.py --self-test
python3 tools/difftest/check-arch-invariants.py

# ③ 两方对照覆盖度
python3 tools/check-mapping-coverage.py

# ④ docs ↔ 文件树对账
python3 tools/difftest/check-docs-vs-tree.py

# ⑤ 文件树重生成（**各 lane 落地完毕后统一跑**）
python3 tools/gen-file-tree.py

# ⑥ 🔴 E-4 双侧活体对拍（C 侧 ↔ B 侧 ↔ 冻结的 Go 字节）
bash tools/difftest/run-parity.sh follow         # ⇒ EXIT=0 = HTTP 21/21 三方 + wire 13/13 双边 + 场景轮 6 类
bash tools/difftest/run-parity.sh follow --mutate # ⇒ EXIT=0 = M1~M7 全按预期（探针非假绿）
```

> ⚠️ **⑥ 的前置**：宿主须有 `Sea-nacos` / `Sea-redis` / `Sea-jaeger` 三个容器在跑
> ⇒ 探针第 0 步会断言，缺则 `die`（EXIT=2，**不静默降级**）。

> ⚠️ **⑥ 的目录名约定**：派发器按 **`<域>/run.sh`** 找探针 ⇒ 目录必须叫 `follow/`。
> ⇒ 本轮踩过：目录叫 `follow-parity/` ⇒ **派发入口永远报「没有登记探针目录」**
> ⇒ 文档写的入口其实**从没被跑过**（已改名 ＋ 同步全部引用）。

> ⚠️ **⑤ 的并发纪律**：`gen-file-tree.py` 与 `check-mapping-coverage.py` 的 `ROOT`
> **硬编码成主工作区绝对路径**（`/Users/pr/Documents/GitHub/RAG-V1`）⇒
> 在 worktree 里直接跑 ⇒ **量的是主工作区那棵树** ⇒ **假绿**。
> ⇒ 跑之前必须把 `ROOT` 改指向本 worktree（或用 `/tmp` 假 ROOT + 软链）。

> 🔴 **本轮实测到的另一个坑：`mvn test`（不带 `clean`）会跑**陈旧的 `target/classes`**
> ⇒ 读数可能**根本不是当前源码的行为**（本轮就遇到：admin 在「无 clean」时红、
> 「带 clean」时绿 ⇒ 那次红是**假红**）。
> ⇒ ⇒ **对照任何读数时，两侧必须用逐字相同的命令形态**（`clean` 与否也要一致），
> 否则「基线也红 / 基线不红」都不能当证据。
> ⇒ ⇒ 判定某域是否引入失败时，**优先跑「只含该域 + 其依赖」的干净命令**，
> 再跑全仓 ⇒ 若两者结论相反，就是并发/资源敏感，不是代码因果。

> ⚠️ **本轮踩到的第三条坑：日志别放 `/tmp`** —— 本机**有别的 lane 在并行跑**，
> 它们的清理动作把本 lane 的 `/tmp/e4-*.log` **整个扫掉**了 ⇒ 正在跑的变异验证读数**当场丢失**。
> ⇒ ⇒ 长跑任务的日志一律放 **`$TMPDIR`**（用户级目录，别的 lane 不扫）或仓内。
> ⇒ ⇒ 同理：**别的 lane 的日志会被本 lane 误当成自己的证据** ⇒ 读数前先看时间戳与文件名。
