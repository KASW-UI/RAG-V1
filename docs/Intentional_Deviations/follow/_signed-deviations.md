# Intentional_Deviations / follow —— 已签字偏离

> **记什么**：明知与 **B 侧（RAG 仓）** 不同、**经用户签字批准**的差异。
> **⚠️ 是决策不是缺陷**：不重复质疑，**也不得「顺手修正回旧行为」**。
> **核对日期 2026-11-02**（逐行核对 B 侧源文件，非估算）· **依据基准 2026-11-02**：**B 侧为唯一真理**（D16）
>
> **本文件归属**：`service/follow/`（`follow-service`）域。
> ⚠️ **只写自己域**：`common/` 与 `users-common` 的偏离不在本文件（各有其主）。
> ⚠️ **不碰 `_INDEX.md`**（AGENTS.md §五.2：三个 `_INDEX.md` 合并后统一更新）。
>
> **产出坐标**：`align/follow` 工作树（`/Users/pr/Documents/GitHub/RAG-V1.worktrees/follow`）· 基线 commit `ae17325`。
>
> **📌 本文件的条目计数（核对日期 2026-11-02）**：共 **8 条编号**（DEV-FOLLOW-001~008）
> ⇒ 其中 **DEV-FOLLOW-007 已撤销**（登记前提被运行时证据证伪 ⇒ 它其实是 L1 漂移，已修）
> ⇒ ⇒ **实际生效的签字偏离 = 7 条**。

---

## DEV-FOLLOW-001 · 错误契约落点：`service/users/common/` → 域内 `com.rag.follow.contract`（L4 · 表达层）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（2026-11-02 用户拍板：选「落域内」） |
| **分域** | follow 域（`service/follow/`） |
| **偏离类型** | **有意偏离**（L4 · Java 包位置层） |
| **提出 / 签字** | follow 域 lane（2026-11-02）· **用户已拍板**（选「落域内 com.rag.follow.contract」） |

### 偏离内容

B 侧 follow 域的错误码与文案在 **`follow-common` 模块**（`com.rag.follow.follow.common.errmsg`）；
C 侧落**域内** `com.rag.follow.contract.{ErrCodes, ErrMsg}`。

| | B 侧（RAG 仓） | RAG-V1（C） |
|---|---|---|
| 码值 | `follow-common/.../errmsg/FollowErrorCode.java`（枚举 16 个） | `service/follow/.../contract/ErrCodes.java`（常量类 16 个） |
| 文案 | `follow-common/.../errmsg/FollowErrorMessages.java` | `service/follow/.../contract/ErrMsg.java` |
| 业务载体 | `follow-common/.../errmsg/FollowCodeError.java` | `service/follow/.../contract/CodeError.java` |
| gRPC 转换 | `follow-common/.../errmsg/FollowGrpcError.java` | `service/follow/.../contract/GrpcErrorMapper.java` |

### 为什么落域内（**三处证据一致**）

1. **`docs/Mapping_Table/follow/follow.md` §1.1** 给的落点是 `service/users/common/.../ErrCodes.java`，
   但**同一节自己写着**：「⚠️ **文案是英文**（`"service busy"` / `"internal server error"` …）
   ⇒ **逐域隔离，不可跨域统一**」⇒ **该行的落点与其自身的隔离纪律自相矛盾**。
2. **`service/users/common/ErrCodes.java` 现状 = 31 条**（users 域私有码），
   **不含 follow 的任何一条**（5201 / 5202 / 5101 / 5203 / 5204 全无）
   ⇒ 落进去等于**新建一张跨域共享码表** ⇒ 与 AGENTS.md §八「错误码值/文案/语言逐域隔离，
   禁止提取共享基座」直接冲突。
3. **`docs/File_Tree/follow.md`**（由 `tools/gen-file-tree.py` **按实际文件系统生成**）
   已把落点生成为 `com.rag.follow.contract.{ErrCodes,ErrMsg,CodeError,GrpcErrorMapper}`。

### 语义等价论证

- **码值集合逐条一致**：200 / 500 / 1015 / 5001-5008 / 5101 / 5201-5204（16 个），
  **且保持 B 侧的声明顺序**（`CODE_SERVER_BUSY(1015)` 排在 5001 之前 —— B 侧源码就写在那里，
  B 侧的 `FollowErrorCodeContractTest#declarationOrderMatchesGoSourceOrder` 钉死该顺序）。
- **文案表逐条一致**：16 条**全英文**小写短语，与 B 侧 `FollowErrorMessages` 逐条相同。
- **未知码回落一致**：`getMsg(未知码)` → `"FAIL"`（B 侧 `GetErrMsg` 的 `codeMsg[Error]` 分支）。
- **反向查表一致**：msg → code 的反查表排除 200/500、first-write-wins 去重（`putIfAbsent`）。

### 与 B 侧的**可观察差异**（无）

- 码值 / 文案 / 顺序 / 回落 / 反查 —— **全部逐条一致** ⇒ wire 上无任何差异。
- 差异仅在 **Java 包位置与符号名**（`FollowErrorCode` → `ErrCodes` 等）⇒ **不进 wire**。

### ⚠️ 连带动作

- `docs/Mapping_Table/follow/follow.md` §1.1 的**两行落点**（`ErrCodes` / `ErrMsg`）需按本条订正
  ⇒ **本域 lane 不改** `docs/Mapping_Table/`（它是**共享面**？—— 实际它是域专属文件，
  **但** `docs/DRIFT/_INDEX.md` 等三个 `_INDEX.md` 明确不许碰）⇒ 订正由**合并收口执行者**处理。
- `docs/DRIFT/_INDEX.md` 的 follow 行需由合并收口执行者更新（本域不碰）。

---

## DEV-FOLLOW-002 · 错误契约形态：枚举 ⇒ 常量类（+ 4 个类的符号名中性化）（L4 · 表达层）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签** |
| **分域** | follow 域（`service/follow/`） |
| **偏离类型** | **有意偏离**（L4 · Java 符号形态层） |
| **提出 / 签字** | follow 域 lane（2026-11-02）· 待用户签字 |

### 偏离内容

| B 侧 | C 侧 | 偏离点 |
|---|---|---|
| `enum FollowErrorCode`（16 个枚举项，各带大段 javadoc） | `final class ErrCodes`（16 个 `int` 常量） | **枚举 → 常量类** |
| `FollowErrorMessages`（静态工具类） | `ErrMsg` | **改名** |
| `FollowCodeError`（unchecked 异常） | `CodeError` | **改名** |
| `FollowGrpcError`（静态工具类） | `GrpcErrorMapper` | **改名** |

### 为什么

- **枚举 → 常量类**：本仓的域内错误契约形态是「`contract/` 包放码值 + 文案查表两个类」
  （users 域 `service/users/user/.../contract/ErrCodes.java` 先例）⇒ 跟随本仓惯例。
  **可观察差异：无**（两者都只是 `int` 的命名来源）。
- **符号名中性化**：`contract/` 包的**包名 `com.rag.follow.contract` 仍是域内的**
  ⇒ 「逐域隔离」这条硬约束由**包名**承载，不依赖类名自带域标识。
  （与 users 域的 `ErrCodes` / `ErrMsg` 同名 —— **同名的类在不同包里不存在冲突**
  ⇒ users 域是 `com.rag.users.common.ErrCodes`、follow 域是 `com.rag.follow.contract.ErrCodes`
  ⇒ **不构成跨域统一**。）

### 语义等价论证

- 码值集合、大小、顺序、文案表、未知码回落、msg→code 反查 —— **逐条一致**（同 DEV-FOLLOW-001）。
- `CodeError.getMessage()` 的文本格式 `"ErrCode:%d, Errmsg:%s"` —— **逐字一致**（运维 grep 日志依赖它）。
- `CodeError` 的 `@JsonAutoDetect` 关掉继承 getter（只留 `code` / `msg`）—— **逐条一致**。

---

## DEV-FOLLOW-003 · DTO 层：可变 class + 手写必填校验器 ⇒ immutable record + Bean Validation（L4 · 表达层 · 已批准的 L3 降级 D-01 ②）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（属**已批准的 L3 降级** `docs/L3-降级清单.md` D-01 ② 的同族） |
| **分域** | follow 域（`service/follow/`） |
| **偏离类型** | **有意偏离**（L4 · Java 表达形态层） |
| **提出 / 签字** | follow 域 lane（2026-11-02）· 待用户签字 |

### 偏离内容

B 侧 6 个 HTTP DTO 是**可变 class**，用**手写必填校验器**（`GoStyleRequiredFields`）判必填；
C 侧 6 个 DTO 是 **immutable record**（决策 D5），必填改用 **Bean Validation**（`@NotNull`）。

| B 侧 | C 侧 |
|---|---|
| `RelationActionReq`（class + `GoStyleRequiredFields`） | `record RelationActionReq(Long targetId)` + `@NotNull` + `@JsonCreator` 工厂 |
| `FollowListReq`（class，3 字段全 optional） | `record FollowListReq(Long userId, int offset, int limit)` + `@JsonCreator` 工厂 |
| `FollowUserListResp` / `RecommendListResp` / `RecommendUser` / `RelationActionResp` | 同名 record |

### 语义等价论证

- **必填判定**：B 侧的 `GoStyleRequired.field("target_id", targetId)` **只判 null**（空串合法）
  ⇒ C 侧用 `@NotNull`（**不是** `@NotBlank`）⇒ 缺席 / 显式 null 被拒、空串放行 ⇒ **逐条等价**。
- **违规出口**：B 侧解析失败 ⇒ 200 + `{code:5001}`；C 侧 `MethodArgumentNotValidException`
  ⇒ 被 `FollowApiExceptionHandler` 折叠成**同样的** 200 + `{code:5001}` ⇒ **形状一致**。
- **`FollowListReq` 三字段全 optional** ⇒ C 侧**不加任何约束** ⇒ 缺席即零值 ⇒ 逐条等价。
- **JSON 键与形状**：逐条一致（含 `user_ids` 数字数组 / `target_id` 字符串 / 空列表 `null` vs `[]` 的不对称）。

### ⚠️ 已登记的 L3 降级（D-01 ② 同族）

- **必填校验文案**：B 侧文案来自 go-zero mapping 层（`field "x" is not set` / `type mismatch ...`），
  C 侧来自 Jackson / Bean Validation ⇒ **文本不同**。
  ⇒ 但本域**两种文本都不可观测**（body 恒为 `msg:"internal server error"`）⇒ **实际无影响**。
- **显式 null 与缺席的区分**：B 侧对 `target_id` 的「缺席」与「显式 null」是两条不同文本，
  C 侧 `@NotNull` 同样不区分 ⇒ **同样不可观测**。

### ⚠️ `,string` 双向接受的实现形态（**关键**）

B 侧 `,string` 字段的 setter 入参是 `Object`（交给 `GoStyleStringInt64` 判定）。
C 侧 record 的规范构造器参数类型 = 分量类型（`Long`）⇒ **不能**直接用
⇒ 改用**静态工厂 + `@JsonCreator`**，工厂入参声明 `Object`，内部调
`com.rag.common.web.StringInt64.parse(Object, String)`。

⚠️ **若改回 `Long` 分量类型** ⇒ Jackson 会自己做强转 ⇒ `1.5` 被**截断**成 1
⇒ 那正是 B 侧明确拒绝的输入 ⇒ **是实打实的安全漂移**（身份可被伪造）。

---

## DEV-FOLLOW-004 · 域内 Jackson 强转补强（`String → Integer = Fail`）的落点（L4 · 表达层）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签** |
| **分域** | follow 域（`service/follow/`） |
| **偏离类型** | **有意偏离**（L4 · 配置装配位置层） |
| **提出 / 签字** | follow 域 lane（2026-11-02）· 待用户签字 |

### 偏离内容

B 侧有**域内** `FollowGoStyleCoercion`（在共享 `GoStyleCoercion` 之上叠加一条
`LogicalType.Integer + CoercionInputShape.String → Fail`）；C 侧这条补强落在
**域内 `config/WebMvcConfig`** 注册的 `Jackson2ObjectMapperBuilderCustomizer` 上
（共享层 `Coercion` 提供的两条是全局的，**不含**这条）。

### 为什么必须补

- B 侧的 `offset` / `limit` **没有** `,string` ⇒ `{"offset":"10"}` 在 B 侧是
  `type mismatch for field "offset"` ⇒ **解析失败**（⇒ 200 + 5001）。
- 共享层 `com.rag.common.web.Coercion` **刻意保留「String → 整数字段接受」**
  （users 域的 `uid` 带 `,string`，需要这条）⇒ ⇒ **follow 域必须自己叠加第 3 条**。
- 不补的后果：**B 侧拒 / C 侧收** ⇒ 多放行**不会报错** ⇒ **最难发现的一类漂移**。

### 语义等价论证

- 补强只影响**无 `,string` 的整数字段**（本域 = `offset` / `limit`）。
- `,string` 字段（`user_id` / `target_id`）的 Jackson 入口是 `Object` 入参
  ⇒ **没有目标 `LogicalType`** ⇒ 这条规则查不到它们 ⇒ **互不干扰**。
- 装配方式（`Jackson2ObjectMapperBuilderCustomizer` + `postConfigurer`）是 **Boot 3.5 的 API 面**
  （`Jackson2ObjectMapperBuilder` 在 3.5 上**没有** coercion API）⇒ 与共享层 `Coercion` 的
  `applyTo(ObjectMapper)` 路线**同源**。

---

## DEV-FOLLOW-005 · JWT 验签的落点：B 侧 4 类（Filter + 3 支撑）⇒ C 侧收敛进 1 个 `HandlerInterceptor`（L4 · 表达层）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签** |
| **分域** | follow 域（`service/follow/`） |
| **偏离类型** | **有意偏离**（L4 · 组件边界层） |
| **提出 / 签字** | follow 域 lane（2026-11-02）· 待用户签字 |

### 偏离内容

B 侧 `follow-api/auth/` 有 **4 个类**：`FollowJwtAuthPaths` + `FollowJwtSignatureVerifier` +
`FollowJwtVerificationResult` + `GoStyleJwtAuthFilter`（验签在 Servlet Filter 3s 预算**之外**）。
C 侧 `follow` 域的 `interceptor/` 包**只有 2 个空壳**（`FollowJwtAuthInterceptor` +
`FollowBlacklistInterceptor`）⇒ ⇒ **验签能力收敛进 `FollowJwtAuthInterceptor` 内部**
（自包含：自己验签、自己写 claim attribute）。

（⚠️ users 域的对应形态是 `interceptor/` 下 5 个类 —— `JwtAuthPaths` / `JwtSignatureVerifier` /
`JwtVerificationResult` / `JwtAuthFilter` / `JwtAuthInterceptor`。）

### 后果（**必须登记**）

- **验签落回 3s 预算之内**：B 侧（users 域）的链序是
  `原生中间件 → JWT 验签 → observability(3s 计时) → 黑名单 → handler`
  ⇒ **3s 预算不覆盖 JWT 验签**。
  C 侧 follow 域把验签放进 `HandlerInterceptor` ⇒ **验签耗时计入 3s 预算**
  ⇒ ⇒ **B 侧 `DRIFT-USERAPI-009②` 在 follow 域不闭环**。
- 该项在 **users 域已登记**（`DRIFT-USERAPI-009②`）⇒ **follow 域是同款缺口的另一个域**。

### 语义等价论证（**响应形状**）

- 验签失败 ⇒ 抛 `AuthenticationException` ⇒ 被 `FollowApiExceptionHandler` 渲染成
  **401 + 空 body** ⇒ 与 B 侧**逐字一致**。
- 成功 ⇒ claim 的**原始对象**写进 request attribute（键名 `"userId"`，与 B 侧
  `ctx.Value("userId")` 同名）⇒ **不转 long** ⇒ 取值判定属业务层（失败是 5004 而非 401）。
- 未匹配路径不经鉴权：判据用 `handler instanceof HandlerMethod`（B 侧「路由匹配先于鉴权」语义）。

---

## DEV-FOLLOW-006 · 「gRPC 熔断打开」分支的不可达（L4 · D2 直连的连带后果）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签** |
| **分域** | follow 域（`service/follow/`） |
| **偏离类型** | **有意偏离**（L4 · 零消费者代码清理） |
| **提出 / 签字** | follow 域 lane（2026-11-02）· 待用户签字 |

### 偏离内容

B 侧 api 层的 8 个 logic 都捕获「客户端熔断打开」（`GoStyleCircuitOpenException`）并映射成
**1015 + "service busy"**。C 侧 api → 领域是**同进程直连**（D1 + D7）⇒
**没有客户端熔断** ⇒ 该分支**不可达** ⇒ **不保留**。

### 理由

- 保留一个**永远不命中的 catch** = §3.4.1 D-2「零消费者的代码不能算已对齐」。
- 与 users 域的同款处置一致（users 域的 `UserApiService` javadoc 已如此登记）。

### ⚠️ 影响面

- **仅** api 层「调 rpc」的那一步。**出向 `user-rpc` 客户端**（跨域，跨域 gRPC 仍走网络）
  **仍然有熔断** ⇒ 该机制在 `common/` 的 `ProtectionAutoConfiguration` 里，**未删**。
  ⇒ ⇒ 本条**只**指「follow 域内**自己**的 api → 领域调用」这一步。

---

## DEV-FOLLOW-007 · ❌ **已撤销**（2026-11-02）—— 登记前提被运行时证据证伪

| 项 | 内容 |
|---|---|
| **状态** | ❌ **已撤销**（不再是偏离；原登记的**前提不成立**） |
| **分域** | follow 域（`service/follow/`） |
| **原偏离类型** | （曾记为）有意偏离（L4 · 零消费者代码清理） |
| **提出 / 撤销** | follow 域 lane（2026-11-02）· **同轮自查证伪 ⇒ 当轮撤销，未提交用户签字** |

### ❌ 原登记内容（已被撤销）

B 侧 `FollowApiSupport` 有 `rpcError(Throwable)`（调 `BizCodeFromError` 把 gRPC 状态反查成业务码）。
C 侧**不实现**该方法 ⇒ 对应的 `GrpcErrorMapper#bizCodeFromError` 的**反查路径在 api 层无消费者**。

### ❌ 原登记的理由（**其中第 1 条是事实错误**）

- ~~D2 之后**域内不再有 gRPC 往返** ⇒ api 层拿不到 `StatusRuntimeException` ⇒ 该方法无调用点。~~
- ⇒ `GrpcErrorMapper#bizCodeFromError` 仍**保留**（它是**契约层**的完整实现，
  且 `contract/` 是 L1 契约的载体）—— **保留实现 ≠ 保留调用**。

### 🔴 撤销依据（**运行时证据** · 探针 8/8 全通）

原理由第 1 条把「**跨进程往返**」与「**错误载体**」混为一谈了：

1. **D2 直连消掉的是「往返」，不是「错误载体」** ——
   C 侧**领域层仍然抛** `StatusRuntimeException`：
   `FollowDomainService#follow`（`service/follow/src/main/java/com/rag/follow/domain/service/FollowDomainService.java:150-153`）
   → `FollowRpcSupport.rpcError(Status.Code.PERMISSION_DENIED, ErrCodes.ERROR_RELATION_BLOCKED)`
   → `GrpcErrorMapper.grpcError(...)` → `status.asRuntimeException()`。
   这与 B 侧 follow-rpc 的领域层是**同一套错误构造入口**
   （B 侧 `FollowRpcSupport.java:89-92`）⇒ **api 层拿得到 status**。
2. **业务码只能靠文案反查还原** —— 本域 wire 形态是「业务码藏在 description 文案里」
   （`GrpcErrorMapper#grpcError` 用 `ErrMsg.getMsg(bizCode)` 当 description），
   而 5 类业务码（5201 / 5202 / 5101 / 5007 / 5008）对应的 gRPC 标准码
   在兜底映射表里**只能还原出粗粒度的码**（5003 / 5005 / 5002 / 5001）。
3. **⇒ 不反查的后果**：这 5 类业务码**全部塌成 5001** —— 那是**实打实的 L1 行为漂移**，
   不是「零消费者代码」。

### ✅ 撤销后的处置（**已实现**）

- `FollowApiSupport#rpcError(Throwable)` **已实现**（逐条照抄 B 侧 `FollowApiSupport.java:216-219`）：
  `code = GrpcErrorMapper.bizCodeFromError(err)` ⇒ `new BusinessException(code, ErrMsg.getMsg(code))`。
- api 层的 **8 个端点全部**在**领域层调用点**做 `catch (StatusRuntimeException e) → throw support.rpcError(e)`
  —— **位置与 B 侧逐条对称**（B 侧同样是每个 logic 内 catch）。
- **运行时探针**（真实编译产物 + 真实 `GrpcErrorMapper` + 真实 `FollowApiSupport`）：
  5201 / 5202 / 5101 / 5007 / 5008 / 5003 / 5001 + 「文案未命中 ⇒ 兜底映射」**8/8 全通**。
- ⇒ 这条**不是偏离**，是**对齐**；本轮的发现与修复记入
  [`docs/DRIFT/follow/verification-follow-2026-11-02.md`](../../DRIFT/follow/verification-follow-2026-11-02.md) 第 5.5 节。

> 📌 **教训**：登记「零消费者 / 不可达」类偏离前，**必须先确认那条路径真的不可达** ——
> 本条的「不可达」是**从「没有跨进程调用」推出来的**，而不是**从控制流读出来的**
> ⇒ AGENTS.md §3.3.1 第 1 条：「判断某个默认行为是否生效，**必须读到消费该配置/注解的那段控制流**」。

---

## DEV-FOLLOW-008 · 端口：gRPC 端口与观测端口的落点（L3 · 端口层）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签** |
| **分域** | follow 域（`service/follow/`） |
| **偏离类型** | **有意偏离**（L3 · 端口层） |
| **提出 / 签字** | follow 域 lane（2026-11-02）· 待用户签字 |

### 偏离内容

| 项 | B 侧 | C 侧 |
|---|---|---|
| HTTP（api）端口 | **8891** | **8891**（`${FOLLOW_HTTP_PORT:8891}`）✔ 一致 |
| gRPC（rpc）端口 | **8082**（`ListenOn: 0.0.0.0:8082`） | **8082**（`${FOLLOW_GRPC_PORT:8082}`）✔ 一致 |
| 观测端口 | **16065**（B 侧 rpc 的 `DevServer.Port`，独立 `http.ListenAndServe`） | ⚠️ **合并进主进程的 `server.port`**（8891）⇒ **观测端口与业务端口同端口** |

### 理由

- D1 把 api + rpc **合并成一个进程** ⇒ **两个 `ListenOn` 合成一个 Tomcat 端口**
  ⇒ B 侧的「独立观测端口 16065」在 C 侧**没有对应物**（C 侧 Actuator 挂在主端口上）。
- ⇒ B 侧抓取配置的**端口**（16065）须改成 C 侧的**路径**
  （B 侧 `/metrics` → C 侧 `/actuator/prometheus`）。

### ⚠️ 影响

- **端口层**（L3）差异，**非 wire 契约**：业务端口逐条一致。
- 部署侧抓取配置需改**路径**而非端口。

---

> 📌 **本文件只登记 follow 域自己的偏离**。
> `common/` 的偏离见 `docs/Intentional_Deviations/global/`；`users-common` 的见 `docs/Intentional_Deviations/users/`。
> **三个 `_INDEX.md` 合并后统一更新**（AGENTS.md §五.2）⇒ 本文件**不碰**。
