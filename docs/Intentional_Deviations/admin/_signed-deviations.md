# Intentional_Deviations / admin —— 已登记偏离

> **记什么**：明知与 **B 侧（RAG 仓）** 不同、**已登记待签**的差异。
> **⚠️ 是决策不是缺陷**：不重复质疑，**也不得「顺手修正回旧行为」**。
> **核对日期 2026-11-02**（逐行核对 B 侧与框架源码，非估算）· **依据基准**：**B 侧为唯一真理**（D16）
> **产出工作树**：`align/users-admin` · **基线 commit `e67c7ed`**
> ⚠️ **本文件的 `_INDEX.md` 不在本工作树更新范围**（按 `docs/Worktree.md` §三·1，合并后由主会话统一更新）。

---

## DEV-ADMIN-GRPC-PORT-001 · admin 的 gRPC 端口 8081 → **8082**（部署面）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（部署面差异 · 非行为面） |
| **分域** | admin 域（`service/users/admin/`） |
| **偏离类型** | **有意偏离**（**部署面 / 进程拓扑**，非 L1 wire 契约） |
| **提出 / 签字** | 主控登记（2026-11-02 · `align/users-admin` 工作树） |

### 偏离内容

| 项 | **B 侧（RAG 仓）** | **RAG-V1（C）** |
|---|---|---|
| admin 的 **gRPC 端口** | **8081** | **8082** |
| admin 的 **HTTP 端口** | 8889 | 8889（**逐字一致**） |

⇒ **只动 gRPC 端口**；HTTP 端口不动。

### B 侧出处（逐行核对）

- B 侧 `service/users/admin/admin-rpc/src/main/resources/application.yml` —— `ADMIN_RPC_PORT:8081`
- B 侧 `service/users/user/user-rpc/src/main/resources/application.yml` —— **同样是 8081**
  ⇒ ⇒ **B 侧 users 域两子域撞端口**，靠环境变量在同机并行时错开
  ⇒ B 侧 `Infrastructure.md` §3「同机并行必须错开」即为此而写
- B 侧 admin 的 gRPC 注册**用 gRPC 端口**（不是 web 端口）⇒ `admin-rpc/.../bootstrap/NacosRegistration.java`

### RAG-V1 落点

- `service/users/admin/src/main/resources/application.yml` —— `grpc.server.port: ${ADMIN_GRPC_PORT:8082}`
- `service/users/user/src/main/resources/application.yml` —— `grpc.server.port: ${USER_GRPC_PORT:8081}`（**已占 8081**）
- `service/users/admin/src/main/java/com/rag/admin/wiring/NacosRegistration.java` —— 注册端口仍取 `GrpcServerProperties#getPort()`（= gRPC 口，**不是 web 口** ⇒ 与 B 侧语义一致）

### 为什么必须错开（D1 的直接后果）

**D1（api + rpc 合并成一个进程）之后，users 域只剩 2 个进程**：`user-service` / `admin-service`
⇒ ⇒ 两个进程的 gRPC 端口**必须错开**
⇒ ⇒ `user-service` 已占 **8081** ⇒ ⇒ admin 取 **8082**。

### 语义等价性论证

| 维度 | 是否等价 |
|---|---|
| **gRPC 方法全名 / 消息格式 / 字段号** | ✅ **等价**（端口不进 wire 契约；proto 逐字节一致） |
| **服务注册名** | ✅ **等价**（B 侧 = `admin-rpc`，C 侧 = `admin-rpc` ⇒ **逐字一致**）—— ⚠️ **本行 2026-11-02 已修正**：初版曾写「已随 D1 改为进程身份 `admin-service`」，该前提**已被推翻**（message 域按 B 侧逐字寻址 `discovery:///admin-rpc` ⇒ 注册名不叫 `admin-rpc` ⇒ **寻址落空**）⇒ 现落点见 `DEV-ADMIN-APPNAME-003` |
| **调用方寻址方式** | ✅ 等价（B 侧靠 env 错开；C 侧靠固定端口错开 ⇒ 同一个进程对 ⇒ 同一个端口） |
| **客户端代码** | ✅ 等价（proto stub 不含端口 ⇒ 端口由配置注入） |

⇒ **判定**：**部署面差异，非行为漂移** ⇒ 登记，不「顺手改回 8081」
（**改回 8081 会与 `user-service` 撞端口 ⇒ 直接起不来**）。

---

## DEV-ADMIN-CONTENTTYPE-002 · 3 个空 struct 端点在「非 JSON content-type」下 C 侧 400 / B 侧受理

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（框架差异 · **窄**） |
| **分域** | admin 域（`service/users/admin/`） |
| **偏离类型** | **框架差异**（Spring 的 content-type 协商早于 DTO 校验） |
| **提出 / 签字** | 主控登记（2026-11-02 · `align/users-admin` 工作树） |

### 偏离内容

**只影响 3 个「req 是空 struct」的端点**：`POST /admin/v1/logout`、`POST /admin/v1/getself`、`POST /admin/v1/invite/create`。

| 场景 | **B 侧** | **RAG-V1（C）** |
|---|---|---|
| **body 为空** | ✅ 正常受理（零值 struct，无字段可缺） | ✅ **一致**（`required = false` + `new XxxReq()` 归一化） |
| **body 有内容但 content-type 不是 `application/json`** | ✅ 正常受理（跳过 JSON 解析 ⇒ 零值 struct） | ⚠️ **400 + text/plain**（**本条差异**） |
| **body 是 `application/json` 但必填字段缺失** | ✅ 400 + text/plain（`field "x" is not set`） | ✅ 400 + text/plain（**文本**属已登记降级 D-01） |

### B 侧出处（逐行核对 · go-zero v1.10.2 源码）

- `rest/httpx/requests.go:153-155` ——
  ```go
  func withJsonBody(r *http.Request) bool {
      return r.ContentLength > 0 && strings.Contains(r.Header.Get(header.ContentType), header.ApplicationJson)
  }
  ```
- `rest/httpx/requests.go:145-151` ——
  ```go
  func ParseJsonBody(r *http.Request, v any) error {
      if withJsonBody(r) { ... json.Unmarshal ... }
      return mapping.UnmarshalJsonMap(nil, v)   // ← 非 JSON ⇒ 走这里
  }
  ```
- `core/mapping/unmarshaler.go:1199` —— `newInitError` ⇒ `field %q is not set`（**只在字段缺失时报**）
  ⇒ ⇒ **非 JSON content-type ⇒ 零值 struct ⇒ 空 struct 端点无字段可缺 ⇒ 不报错 ⇒ 正常受理**

⇒ ⚠️ **B 侧完全没有 415 这个状态码**（go-zero 不做 content-type 协商）。

### RAG-V1 落点

- `service/users/admin/src/main/java/com/rag/admin/controller/AdminController.java` ——
  3 个空 struct 端点保留 `@RequestBody(required = false)`；其余 10 个端点用**必填** `@RequestBody`
- `service/users/admin/src/main/java/com/rag/admin/advice/AdminApiExceptionHandler.java` ——
  `handleMediaTypeNotSupported` 由 **415（裸 body）** 改成 **400 + text/plain**
  （⇒ 对**有必填字段的 10 个端点**与 B 侧**完全一致**；⇒ 对这 3 个端点才有本条差异）

### 为什么不能在 C 侧消掉这条差异

C 侧（Spring MVC）的 **content-type 判定发生在「参数解析」阶段**（早于 DTO 校验）：

- `AbstractMessageConverterMethodArgumentResolver#readWithMessageConverters`（spring-webmvc 6.2.16）
  ⇒ 没有任何 `HttpMessageConverter` 能按 `text/plain` 读 `LogoutReq`
  ⇒ ⇒ `body == NO_VALUE` ⇒ ⇒ 直接 `throw new HttpMediaTypeNotSupportedException(...)`
- ⇒ **这条异常在进入 controller 之前就抛了** ⇒ **没有任何机会**把「零值 struct」交给 DTO 校验
- ⇒ ⇒ 要完全对齐 B，唯一的办法是**这 3 个端点根本不接 body**（而 B 侧是「接了但字段为空」）
  ⇒ ⇒ 那样会引入另一处差异（B 侧「malformed JSON ⇒ 400」会变成 C 侧「忽略」）
  ⇒ ⇒ **权衡后选「统一 400」**（对 10/13 个端点完全等价，代价是这 3 个端点的窄差异）

### 语义等价性论证

| 维度 | 是否等价 |
|---|---|
| **正常路径**（JSON body） | ✅ 完全等价 |
| **空 body** | ✅ 完全等价 |
| **JSON 但必填字段缺失** | ✅ 等价（**形状**一致；**文本**属 D-01 降级） |
| **非 JSON content-type** | ⚠️ **本条差异**（3 个端点：B 受理 / C 400） |

⇒ **判定**：**框架差异（窄）** ⇒ 登记。
⚠️ **不得「顺手修回」**：把 415 改回去会让 **10/13** 个端点与 B 侧不一致（更差）。

---

## 附：本域**不属于**偏离的 3 项（避免被误当偏离「修回」）

| 项 | 为什么**不是**偏离 |
|---|---|
| **默认 admin 密码 = `admin`（不读 `admin-rpc.system.default-password`）** | 这是 **B 侧 DRIFT-001 的修法**（B 侧 seeder 用包内常量）⇒ C 侧**逐字承接 ⇒ C ≡ B** ⇒ 不是偏离 |
| **`extra_info` 是 `text` 不是 `jsonb`** | jsonb 会以 **22P05** 拒收含 `U+0000` 的 payload ⇒ **B 侧已改 text** ⇒ C 侧**继承** ⇒ C ≡ B |
| **指标名 `admin_rpc_high_risk_action_logal` 的拼写错误** | B 侧就是 `logal` ⇒ 属 **L1 逐字照抄** ⇒ 修正它才是漂移 |

⇒ 详见本域台账 `docs/DRIFT/users/admin/verification-admin-2026-11-02.md` §六。

---

## DEV-ADMIN-400-TEXT-002 · **400 文本内容**（D-01 族的扩展：body 缺失 / content-type 不匹配）

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（与 `docs/L3-降级清单.md` **D-01 同族**，此处补齐 E-4 暴露的覆盖范围） |
| **分域** | admin 域（`service/users/admin/`） |
| **偏离类型** | **表述差异**（**形状完全一致**，仅 400 的**文本内容**不同） |
| **提出 / 签字** | 主控登记（2026-11-02 · `align/users-admin` 工作树 · E-4 双侧活体对拍后） |

### 偏离内容

| 场景 | B 侧（go-zero 语义 · RAG 仓 Java 实现） | RAG-V1（C 侧） |
|---|---|---|
| **必填字段缺失**（`{}`） | `field "username" is not set` | Hibernate Validator / Spring 的消息 |
| **body 完全缺失** | `field "username" is not set` | `Required request body is missing: …` |
| **content-type 不是 JSON** | `field "username" is not set` | `Required request body is missing: …` |
| **keyword 必填缺失**（`getuserlist`） | `field "keyword" is not set` | `Validation failed for argument [0] …` |

### 形状（**必须一致的部分** —— E-4 逐条实测）

| 项 | 两侧一致？ |
|---|---|
| HTTP 状态码 | ✅ 都是 **400** |
| `Content-Type` | ✅ 都是 `text/plain; charset=utf-8` |
| `X-Content-Type-Options` | ✅ 都是 `nosniff` |
| body 尾随换行 | ✅ 都是 `\n` 结尾 |

⇒ ⇒ **只有文本不同** ⇒ 客户端按状态码分流即可 ⇒ ⇒ **不影响行为等价**。

### B 出处（E-4 活体实测 · 2026-11-02）

- B 侧 `admin-api` 真实进程：4 个场景的 body **逐字相同**（`field "x" is not set`）
  ⇒ ⇒ 源于 go-zero 的 `mapping.unmarshaler.go:1199 newInitError`（字段缺失 ⇒ `field %q is not set`）。
- C 侧：Spring MVC 的 `RequestResponseBodyMethodProcessor` / Hibernate Validator 的原生消息。

### 为什么**不能**强行对齐

B 侧那串文本是 **go-zero 的错误信息**，C 侧要对齐就得**手抄 go-zero 的文案**
⇒ ⇒ 那是「用 Java 复刻 Go 的错误串」，**不是**行为等价，而是**文案对齐**
⇒ ⇒ 而文案对齐恰恰是 AGENTS.md 禁止的方向（**L1 契约里错误文案属于 B 侧基准，
但实现方式应是「同义不同串」而非「逐字抄串」**——B 侧自己也是 Java，它也是用 go-zero 语义产出的）。

### 处置

- ✅ **形状一致即通过**；文本差异**显式登记**（本条 + `docs/L3-降级清单.md` **D-01**）。
- ⚠️ **对拍探针必须显式排除它**（`tools/difftest/parity_admin.py` 的 `shape_only` 模式）
  ⇒ 否则会把**已签字的降级**误判成回归 ⇒ ⇒ 触发「修回旧行为」⇒ ⇒ **降级被静默撤销**。

---

## DEV-ADMIN-APPNAME-003 · `spring.application.name`：`admin-rpc` → **`admin-service`**（**部署面 · 进程名**）

> **性质**：**部署面 / 进程名**（**不是行为面、不是寻址面**）⇒ 🏷 **候选，建议签**
> **登记时间**：2026-11-02（`align/users-admin` 工作树 · 第五阶段「多文件合并 / 架构调整」专项核查时发现）

### 偏离内容

| 项 | B 侧（RAG 仓 · Boot 4） | C 侧（RAG-V1 · Boot 3.5） |
|---|---|---|
| `spring.application.name` | **`admin-rpc`** | **`admin-service`** |
| **Nacos 注册名**（服务发现面） | `admin-rpc` | **`admin-rpc`**（✅ **逐字一致** · §五·5-⑪ 已修） |

⇒ ⇒ **两个轴在本仓刻意分叉**：**进程名**（`spring.application.name`）≠ **服务发现名**（Nacos 注册名）。

### B 侧出处（逐行核对）

- B 侧 `service/users/admin/admin-rpc/src/main/resources/application.yml:4-5`：

  ```yaml
  spring:
    application:
      name: admin-rpc
  ```

- B 侧 `service/users/admin/admin-rpc/src/main/java/com/rag/users/admin/rpc/bootstrap/NacosRegistration.java:88`
  —— `String serviceName = "admin-rpc";`（**硬编码** ⇒ 与上面对齐）。

### RAG-V1 落点

- `service/users/admin/src/main/resources/application.yml:16` ⇒ `name: admin-service`
  （**本仓 artifactId 惯例**：`admin-service` / `user-service` / `message-service` …）。
- `service/users/admin/src/main/java/com/rag/admin/wiring/NacosRegistration.java`
  ⇒ 注册名**读配置** `spring.cloud.nacos.discovery.service`，**默认逐字 = `admin-rpc`**（B 侧值）。

### 为什么**不参与服务发现**（本条不是寻址面）

| 面 | 取值来源 | C 侧取值 | 影响 |
|---|---|---|---|
| **服务发现 / 寻址** | Nacos **注册名** | **`admin-rpc`**（逐字 = B 侧）⇒ ⇒ message 域按 `discovery:///admin-rpc` 寻址**能落到本进程** | ✅ **寻址正常** |
| **进程名 / 指标标签** | `spring.application.name` | `admin-service` | Prometheus 的 `application` 标签 ＋ 观测层默认服务名 |

⇒ ⇒ **兄弟域形状已经确立**：user 域 = `spring.application.name=user-service` ＋ **Nacos 注册名 = `user-rpc`（逐字沿用 B 侧）**
⇒ ⇒ 本仓**两个轴本来就该分叉** ⇒ admin 域保持同款形状。

### 语义等价性论证

- **不影响寻址**：服务发现名 = Nacos 注册名 = `admin-rpc` ⇒ 与 B 侧逐字一致 ⇒ **跨域 gRPC 链路不受影响**。
- **不影响业务行为**：13 个端点的 path / method / 错误码 / wire 载荷**均不含进程名**。
- **不影响可观测语义**：`spring.application.name` 只作为**标签值**出现 ⇒ 改回去只会让标签与 B 侧同名，
  却在**同机多域共存**时与兄弟域的标签口径不一致 ⇒ ⇒ 本仓取 artifactId 惯例口径（与 user 域同款）。

### 处置

- ✅ **显式登记**（本条），避免日后被当成「注册名漂移」而误改（**注册名 ≠ 进程名**，两者已由
  `AdminServiceApplicationTests` 的两条断言分别钉死：注册名 == `admin-rpc`、进程名 == `admin-service`）。
- ⚠️ **`docs/Intentional_Deviations/_INDEX.md` 的索引由主会话统一更新**（本 lane 不碰）。

---

## 本域登记一览（**核对日期 2026-11-02**）

| 编号 | 一句话 | 状态 |
|---|---|---|
| `DEV-ADMIN-GRPC-PORT-001` | admin 的 gRPC 端口 8081 → **8082**（部署面） | 🏷 候选，建议签 |
| `DEV-ADMIN-CONTENTTYPE-002` | 3 个空 struct 端点在「非 JSON content-type」下 C 侧 400 / B 侧受理 | 🏷 候选，建议签 |
| `DEV-ADMIN-400-TEXT-002` | 400 的**文本内容**（D-01 族扩展 · body 缺失 / content-type 不匹配） | 🏷 候选，建议签 |
| `DEV-ADMIN-APPNAME-003` | `spring.application.name`：`admin-rpc` → **`admin-service`**（进程名 · 与 Nacos 注册名**刻意分叉**） | 🏷 候选，建议签 |

> ⚠️ **本表只登记，不升级为「请用户拍板」**（用户 2026-10-25 的明确要求）⇒ 按「🏷 候选，建议签」登记即算交付。
> 📌 **2026-11-02 修正记录**：初版把「Nacos 服务注册名 = `admin-service`」当作 D1 的一部分登记为偏离
> ⇒ **该前提已被推翻**（message 域按 B 侧逐字寻址 `discovery:///admin-rpc`）⇒ **注册名已回到 B 侧逐字值 `admin-rpc`**
> ⇒ **本表里「服务注册名」那一行已改写** ⇒ ⇒ **登记的是「进程名」这一条，不是「注册名」**。
