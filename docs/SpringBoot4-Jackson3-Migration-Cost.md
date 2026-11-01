# SpringBoot4-Jackson3-Migration-Cost.md

> ⛔ **本报告结论已作废（2026-11-02 · D14 工具链回退）**
>
> **本项目现为「全项目 Jackson 2」**：Spring Boot **3.5.11** + `com.fasterxml.jackson.*` **2.19.4**
> ⇒ 业务代码、第三方依赖、运行时 classpath **全部 Jackson 2** ⇒ **classpath 上零 Jackson 3**。
> ⇒ 本报告的「要不要迁 / 迁了多少钱 / 哪些文件要改」**全部不再适用**（Boot 4 时代的评估过程**保留作历史存档**）。
> ⇒ 现行口径 ⇒ [`docs/决策记录.md`](决策记录.md) **D14** · 索引 ⇒ [`docs/README.md`](README.md)。

> **评估对象**：RAG（B 侧 · api/rpc 分离形态，Boot 4.0.8）的 Jackson 2 / 3 混用 → **RAG-V1 全面统一 Jackson 3** 的实际工作量、风险与长期维护成本。
> **核对日期**：2026-11-01 · **性质**：成本评估（只读审计，未改任何 POM / 源码 / 测试 / 配置）。
> **口径**：与 `docs/决策记录.md` §1.0c「抄 RAG 实现」路线一致（L2 以 RAG 为基准，不回真源逐行复核）。
> **配套**：[`SpringBoot4-Jackson3-Jackson2-Inventory.md`](SpringBoot4-Jackson3-Jackson2-Inventory.md) —— **Jackson 2 全量清单（206 个文件逐个列出 + 分类 + 刻意配置并集）**。

---

## 一、最终结论（一页）

| 问 | 答 |
|---|---|
| **全 Jackson 3 技术可行？** | ✅ **可行**。Boot 4.0.8 原生自动配置就是 Jackson 3（`spring-boot-jackson` → `JsonMapper`）；RAG 的 api 侧已有 **19 个主代码文件在用 Jackson 3** ⇒ **仓内自带可抄模板** |
| **能消除业务代码里的 Jackson 2？** | ✅ **能，且成本比预期低**。全量扫描 206 个命中文件（`service/` 范围）⇒ **130 个（63%）零改动**（106 只用注解 + 19 已是 J3 + 5 混合 DTO）；真需要动的是 **76 个**（含 38 个测试）⇒ 其中 5 个是**直接删除** |
| **能消除运行时里的 Jackson 2？** | ⚠️ **基本能，但 MinIO 是唯一的硬阻塞**。JJWT 可换（`jjwt-gson` / `jjwt-orgjson` / 自写 `tools.jackson` `Serializer`）；**MinIO 8.5.17 传递 `jackson-databind:2.18.0`（compile scope）** ⇒ 不排除则 Jackson 2 仍在 classpath，排除则 MinIO 可能 `NoClassDefFoundError` ⇒ **两者可能互斥** |
| **最主要的三个成本来源** | ① **5 份「刻意配置」的并集合并**（`Jackson2ObjectMapperConfiguration` × 5 ⇒ 迁到 Boot `JsonMapper.BuilderCustomizer` 共享 1 份）② **10 处独立 `new ObjectMapper()` 的默认值语义复核**（Jackson 3 `FAIL_ON_TRAILING_TOKENS` 默认 true）③ **38 个测试文件的期望值重算**（sed 改包名改不了期望） |
| **Jackson 迁移增量人天** | **13.5 ~ 27 人天**（低 / 常规 / 高 = 13.5 / 21 / 27）—— MinIO 隔离实验已完成（见 [`MinIO-Jackson2-Isolation-Experiment.md`](MinIO-Jackson2-Isolation-Experiment.md)）⇒ B1 阻塞解除 |
| **更新总工期**（35~50 机械搬运基线上） | **48.5 ~ 77 人天**，**最可能 ~55~60 人天** |
| **是否值得在 RAG-V1 当前阶段执行？** | ✅ **建议执行，但必须挂在「搬 RAG 实现」那一轮里做**（与树搬移的批量 sed 合并）⇒ 边际成本显著低于单独起一轮 |

> ⚠️ **本轮修正了上一版口径**：上一版按「抽样」估 Jackson 增量为 9~21 人天；**全量扫描后修正为 14~28 人天**。差异来自三处此前漏算：38 个测试文件的期望值重算、10 处独立 mapper 的默认值面、2 个 coercion validator 在 Jackson 3 下可能需**重写元素级解析器**（不只是改包名）。

---

## 二、当前混用结构与迁移目标

### 2.1 RAG 的真实分工（脚本全量扫描 1,381 个 `.java` 得出）

| 位置 | Jackson 代 | 关键证据 |
|---|---|---|
| **api 模块（HTTP 入口）** | **3** | 6 个 api 的 `WebMvcConfig` 用 `org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer`（Boot 4 专属）；`LikeGoStyleCoercion` / `CommentGoStyleCoercion` / `MessageGoStyleCoercion` 用 `tools.jackson.databind.cfg.*` |
| **rpc 模块（Kafka/outbox 内部）** | **2** | article / comment / hot / like / task **5 个 rpc** 各一个 `Jackson2ObjectMapperConfiguration` |
| **DTO 注解** | **两代通用** | `com.fasterxml.jackson.annotation` 在 Jackson 3 坐标与包名不变 ⇒ **106 个文件零改动** |
| **common 共享层** | **混** | `communityfact/Wire` · `observability/HttpMiddleware` 用 J2；`GoStyleJsonBodyConverterConfig` 用 J3 |

> **迁移目标**：RAG-V1 是 **api+rpc 合并成 1 进程**（D1）⇒ 照搬混用 = **同一 JVM 两套 Jackson** ⇒ 必须统一。
> **统一到 3 的结构性收益**：5 个手搓 `Jackson2ObjectMapperConfiguration` **直接消失**，由 Boot 4 自动配的 `JsonMapper` 接管（但「刻意配置」必须搬过去，见 B6）。

### 2.2 全量扫描结果（`service/` 206 个命中文件）

| 迁移模式 | 文件 | 主 | 测 | 迁移动作 |
|---|---:|---:|---:|---|
| `J2-ANNOTATION-ONLY` | **106** | 106 | 0 | **零改动** |
| `J2-PLAIN`（Mapper / 注解增强） | **45** | 22 | 23 | 改包名 + **默认值语义复核** |
| `J2-TREE-API`（`JsonNode`/`ObjectNode`） | **20** | 10 | 10 | 改包名 + **字节对拍** |
| `J3-ONLY` | **19** | 14 | 5 | **原样保留** |
| `J2-COERCION` | **6** | 2 | 4 | 改包名 + **coercion 行为适配** |
| `J2-BEAN-CONFIG` | **5** | 5 | 0 | **删除** + 配置并集迁到 Boot |
| `MIXED-J2+J3`（4 个 DTO + 1 测试） | **5** | 4 | 1 | **零改动**（注解 J2 + deserializer J3，两侧都已是目标态） |

**按域**：article 21 · comment 31 · like 41 · users 29 · message 20 · favorite 19 · task 16 · common 11 · hot 10 · follow 8（逐文件见配套清单）。

**两个扫描带来的关键利好（此前抽样未发现）**：
1. **RAG 的 3 个自定义 (de)serializer 全在 api 侧且已是 Jackson 3** ⇒ `GoStyleFloat64Deserializer` / `GoStyleRawStringListDeserializer` / `GoStyleMapStringValueDeserializer` **零改动** ⇒ 「自定义序列化器」迁移面 = **0**。
2. **混合的 5 个文件全是 DTO**（like-api × 3、message-api × 1、favorite-api 测试 × 1）⇒ **零改动** ⇒ 「同一个类里两代 Jackson」的隐患面比预估小得多。

---

## 三、迁移工作量分类表

| 迁移类别 | 数量 | 主要改造 | 人天 |
|---|---:|---|---:|
| **Mapper / JsonMapper** | `ObjectMapper` 82 文件 · `JsonMapper` 34 · **10 处独立 `new ObjectMapper()`** | 包名改；10 处独立 mapper 须复核 Jackson 3 默认值 | 1.0~2.0 |
| **JsonNode / Tree API** | 20 文件（10 主 + 10 测） | 包名改；`JsonNode.toString()` **须字节对拍**（影响 `payload_hash`） | 0.8~1.5 |
| **Coercion** | 6 文件（`LogicalType` 22 · `Coercion*` 16） | 包名改；⚠️ **Jackson 3 只 LogicalType 级生效**，类级无效 | 1.0~2.5 |
| **Serializer / Deserializer** | **0 需改**（3 个自定义全已是 J3） | —— | 0 |
| **Jackson 2 专属注解** | **0** | `ToStringSerializer` 仅出现在 javadoc 且被明确否决（RAG `docs/FileTree/favorite/api.md:319`） | 0 |
| **异常处理** | `JsonProcessingException` 1 · `InvalidFormatException` 2 · `MismatchedInputException` 4 | Jackson 3 异常 **unchecked**（`JacksonException extends RuntimeException`） | 0.3~0.8 |
| **HTTP Converter** | api 侧 19 主 + 5 测 | 合并进程后 HTTP 边界与内部共用 Boot 的 `JsonMapper`；`JacksonJsonHttpMessageConverter`（Framework 7）**已存在** | 0.5~1.5 |
| **公共 JSON 工具** | `GoCompatibleJson`(143 行) · `Wire.MAPPER` · `ProcessLogger` · `AuthorityHandler` · `HttpMiddleware` · `GoFormValues` · task `GoJson` | 见 §四 | 0.8~1.5 |
| **RPC / Kafka / Outbox** | 5 个 TypeHandler（article `JsonbTypeHandler` · message `JsonbMapTypeHandler` · users×2 `JsonTextTypeHandler`）+ 4 个 consumer/publisher | ⚠️ **Kafka 用 `StringSerializer`**（无 Jackson Kafka serializer）⇒ 迁移面只在 TypeHandler 与业务 mapper | 0.5~1.0 |
| **第三方库适配** | JJWT · MinIO | 见 §四·B5 / B6 | 1.0~3.0 |
| **Bean 配置删除与配置并集** | 5 个 `Jackson2ObjectMapperConfiguration` | **删除 5 个类** + 刻意配置并集迁到 1 份 Boot `JsonMapper.BuilderCustomizer` | 1.5~3.0 |
| **测试期望值重算** | 38 个测试文件 | sed 改包名改不了期望值 ⇒ 逐条重算 | 1.5~3.5 |

---

## 四、关键阻塞项

| # | 阻塞项 | 证据 | 处理 | 人天 |
|---|---|---|---|---:|
| **B1** | ~~MinIO 传递 Jackson 2（compile）~~ ⇒ **✅ 已由隔离实验关闭** | [`MinIO-Jackson2-Isolation-Experiment.md`](MinIO-Jackson2-Isolation-Experiment.md)：真实 MinIO 服务端实测，变体 B（排除后）**8/8 绿**；对象读写全流程正常，`ObjectMapper` 不可加载 ⇒ **运行时零 Jackson 2 可达**（限「7 个已验证 API」范围；桶通知/表单直传/SSE-KMS/IAM 凭证仍需 Jackson 2） | **0.2~0.5**（仅剩 `<exclusion>` + 依赖树断言） |
| **B2** | **JJWT `jjwt-jackson:0.13.0` 依赖 `com.fasterxml.jackson.core:jackson-databind`** | Maven Central `jjwt-jackson-0.13.0.pom` | 换 `jjwt-gson` / `jjwt-orgjson`，或自写 `io.jsonwebtoken.io.Serializer`（`tools.jackson.databind.ObjectMapper`） | 0.5~1.0 |
| **B3** | **`FAIL_ON_TRAILING_TOKENS`：Jackson 2 默认 false → Jackson 3 默认 true** | RAG `docs/DRIFT/like/api.md`（DRIFT-LIKEAPI-014）+ `docs/Pitfalls/like/api.md` | 影响 **10 处独立 mapper** + 5 个 TypeHandler + `Wire.MAPPER` 的解析路径 | 0.5~1.0 |
| **B4** | **coercion 语义不等价**：Jackson 3 只 LogicalType 级生效（类级无效）· `1e400` 溢出**不报错**给 `Infinity` | RAG `docs/DRIFT/like/api.md`（DRIFT-LIKEAPI-012）+ `docs/Pitfalls/like/api.md` | `CommentDeliveryValidator` / `LikeDeliveryValidator` 可能需**新增元素级解析器**（≠ 改包名） | 1.0~2.5 |
| **B5** | **`FAIL_ON_UNKNOWN_PROPERTIES` 两处方向相反且都是刻意** | `Wire.java:115`（**true**）vs `Jackson2ObjectMapperConfiguration`（**false**）· RAG `docs/DRIFT/comment/rpc.md` | 迁移后**各自保留**，不得统一 | 0.2~0.5 |
| **B6** | **5 份「刻意配置」须并集化**（实测：关未知字段 5/5 · `LogicalType.Textual` 4/5 · `LogicalType.Boolean` 3/5） | 配套清单 §四 | 合并成 1 份 Boot `JsonMapper.BuilderCustomizer`；**article / task 的差异要先核对是「不需要」还是「漏配」** | 1.5~3.0 |

> **不构成阻塞（已排除）**：`jackson-annotations`（3.x 同坐标同包名）· `registerModule`/`SimpleModule`（**0 处**）· `@JsonSerialize`（**0 处实际使用**）· Kafka Jackson serializer（用 `StringSerializer`）· `MappingJackson2HttpMessageConverter`（spring-web 7.0.9 **仍在**）· 社区事实幂等指纹（走独立 JCS 库 + 原始字节）· 自定义序列化器（**全在 api 侧且已是 J3**）。

---

## 五、测试与验证成本

> §3.3 纪律：**Mockito 单测不装配 Spring 上下文 ⇒ 天然测不到 Bean / Converter 失效** ⇒ 以下按「真容器 / 真字节」计。

| 验证项 | 形态 | 人天 |
|---|---|---:|
| 全量编译 + `mvn -o clean verify`（16 模块） | 基线 | 0.5 |
| **依赖树残留断言**（业务模块不得出现 `com.fasterxml.jackson.core:jackson-databind`；MinIO 模块单独豁免） | 脚本 | 0.3~0.5 |
| **10 域 Spring Context 启动**（真容器；Bean 真进容器、Kafka listener 真进 registry —— Boot 4 把 Kafka 自动配置拆成独立模块，**注解存在≠生效**） | 真容器 | 1.5~2.5 |
| **HTTP 边界真实序列化**（真 Tomcat；禁 MockMvc；断言 `Content-Type`/`nosniff`/状态行/响应体） | 真 Tomcat | 1.0~1.5 |
| **RPC / Kafka / Outbox 往返** + 5 个 TypeHandler 的 JSONB/JSON 列 | 集成 | 0.5~1.0 |
| **`GoCompatibleJson` 字节对拍**（对 B 侧 `GoCompatibleJson` 的基准输出；含浮点/大数/HTML 转义/U+2028） | 对拍 | 0.5~1.0 |
| **Coercion / 异常边界**（复用 DRIFT-LIKEAPI-012/014、DRIFT-LIKE-007 的用例形态，**期望值重算**） | 单测 + 真容器 | 0.5~1.0 |
| **JJWT 签发/验签** + **MinIO put/get** | 集成 | 0.3~0.5 |
| **已有 DRIFT / PITFALL 门禁重跑**（Jackson 相关子集） | 回归 | 0.5~1.0 |
| **合计** | | **4.3~7.0** |

> 与基线的重叠：上表「10 域 Context 启动」「HTTP 边界」与基线里「每域真触发测试」**约 0.5~1 人天重叠，已扣除**。

---

## 六、人天估算

### 6.1 五大类（Jackson 迁移增量，相对「直接搬 RAG」的 35~50 基线）

| 类 | 低 | 常规 | 高 | 主要不确定因素 |
|---|---:|---:|---:|---|
| 1 代码与依赖改造 | 5.1 | 7.7 | 10.3 | 76 个文件的 import 改写是否与树搬移的 sed 合并成功；5 份刻意配置并集化后的行为差异 |
| 2 语义适配 | 1.3 | 2.2 | 3.1 | `FAIL_ON_TRAILING_TOKENS` 影响面；异常 unchecked 后的 catch 语义；`MapperFeature`/`@JsonFormat` 两代差异 |
| 3 测试与验证 | 4.3 | 6.4 | 7.0 | 真容器 10 域启动与 HTTP 边界的既有测试资产可复用度 |
| 4 集成与回归 | 2.5 | 4.0 | 5.5 | **38 个测试文件的期望值重算**；common 共享层改动波及 10 域 |
| 5 不确定性缓冲 | 1.0 | 1.5 | 2.0 | **MinIO 排除后是否仍工作（唯一需实验项）**；`JsonNode.toString()` 字节差异 |
| **Jackson 增量合计** | **13.5** | **21.0** | **27.0** | （含 B1 关闭后的下调） |

### 6.2 重叠扣除（**不重复计算**）

| 重叠项 | 处理 |
|---|---|
| 76 个文件的 **Jackson 包名改写** | ⇒ 已含在「机械搬运」的批量 sed + 编译修错循环内 ⇒ **不额外计**（仅留 0.3~0.8 复核与错定位） |
| 5 个 `Jackson2ObjectMapperConfiguration` 的**删除** | ⇒ **净减代码**（删除动作本身 ~0.1；配置并集化另计于 1 类） |
| 19 个 J3-ONLY + 5 个 MIXED DTO + 106 个注解文件 | ⇒ **零改动**，不计入 |
| `jackson-annotations` 3.x | ⇒ **不删**（Boot 托管）；删了会连带 106 个文件编译失败 |
| C2/C3 与基线「每域真触发测试」 | ⇒ 扣除 **0.5~1 人天** |

### 6.3 更新总工期

| 口径 | 工期 |
|---|---|
| 机械搬运基线（不回真源复核） | 35 ~ 50 人天 |
| ＋ Jackson 3 增量（**全量扫描 + MinIO 实验口径**） | 13.5 ~ 27.0 人天 |
| **更新总工期** | **48.5 ~ 77.0 人天** |
| **最可能** | **~55 ~ 60 人天** |
| 对比上一版抽样口径 | 44 ~ 71 人天 ⇒ **本表取代它**（全量扫描后上修） |

---

## 七、风险

| 级别 | 风险 | 证据 | 缓解 |
|---|---|---|---|
| 🔴 高 | **MinIO 排除 Jackson 2 后不可用 ⇒「运行时零 Jackson 2」与「MinIO 可用」互斥** | minio-8.5.17.pom（compile scope） | 隔离最小实验（putObject/getObject）；若互斥则目标降级为「**业务代码零 Jackson 2 + 运行时仅 MinIO 传递带入**」并登记为签字偏离 |
| 🔴 高 | **`FAIL_ON_TRAILING_TOKENS` 翻面 ⇒ 消息收/不收的判定改变**（消费侧 ⇒ 影响幂等与错误映射） | RAG `docs/DRIFT/like/api.md` DRIFT-LIKEAPI-014 | 逐路径核对 10 处独立 mapper + 5 个 TypeHandler + `Wire.MAPPER`；**真容器验证** |
| 🔴 高 | **coercion 语义不等价 ⇒ 同请求体两侧结果不同**（静默强转 vs 拒；`1e400`→`Infinity`） | RAG `docs/DRIFT/like/api.md` DRIFT-LIKEAPI-012 | 2 个 validator 可能需**重写元素级解析器**（≠ 改包名） |
| 🟡 中 | **`JsonNode.toString()` 字节差异 ⇒ `payload_hash` 变 ⇒ 跨语言幂等键漂移**（社区事实指纹走独立 JCS，**不受影响**） | `GoCompatibleJson.java:56-71` | 与 B 侧 `GoCompatibleJson` 的基准输出逐字节对拍 |
| 🟡 中 | **5 份刻意配置并集化时丢失域差异**（article 无 Textual coercion、task 无 Boolean coercion） | 配套清单 §四 | 迁移前逐域核对「不需要」vs「漏配」 |
| 🟡 中 | **Jackson 3 异常 unchecked ⇒ catch 语义变化** | `JsonProcessingException` 1 · `InvalidFormatException` 2 · `MismatchedInputException` 4 | 逐处复核 catch 范围 |
| 🟡 中 | **Boot 4 `spring-boot-jackson` 只产 Jackson 3 ⇒ 任何残留 Jackson 2 注入点必然启动失败**（RAG 已记过同类坑） | RAG `docs/Pitfalls/like/rpc.md` PIT-LIKE-19 | 10 域逐一启动验证；**不用 Mockito 判存活** |
| 🟡 中 | **38 个测试文件期望值重算 ⇒ 工作量易被低估**（sed 只能改包名） | 全量扫描计数 | 单独排期，不并入「改包名」 |
| 🟢 低 | **误删 `jackson-annotations` 3.x ⇒ 106 个文件编译失败** | 坐标/包名两代通用 | 依赖清单显式标注「annotations 非 Jackson 2」 |
| 🟢 低 | **迁移后维护复杂度上升** | —— | 统一到 Boot 自动配 `JsonMapper` ⇒ 反而**比 RAG 少 5 份手搓 Bean** ⇒ 复杂度**下降** |

---

## 八、是否建议执行

**✅ 建议执行，但必须「挂在搬 RAG 那一轮里」，不要单独起一轮。**

1. **方向正确且成本已封顶**：Boot 4.0.8 原生就是 Jackson 3；RAG api 侧 19 个主代码文件已是 Jackson 3 ⇒ **模板现成**；RAG-V1 的 api+rpc 合并进程**本就不允许两套 Jackson 共存** ⇒ 统一是必答题。
2. **成本结构健康**：增量 13.5~27 人天（常规 ~21），占更新总工期（~55~60）的 **~30~35%**；且 **63% 的 Jackson 命中文件零改动** ⇒ 「改代码」本身很轻，**大头在语义适配与验证**。
3. **挂载时机**：与「搬运」的批量 sed 合并 ⇒ 76 个文件的包名改写几乎零边际成本；单独起一轮成本翻倍。
4. **执行顺序建议**：
   ① ~~B1 MinIO 隔离实验~~ ⇒ **已做**（见 `MinIO-Jackson2-Isolation-Experiment.md`）：运行时零 Jackson 2 **可达**，代价 = 3 条 `<exclusion>` + 1 条依赖树断言；
   ② **B2 JJWT 替换**；
   ③ **B6 刻意配置并集化**：先出「5 份配置的并集表 + 域差异说明」，再写 1 份共享 `JsonMapper.BuilderCustomizer`，删掉 5 个类；
   ④ 按 §三 批量改包名 ⇒ 再按 B3/B4 做语义适配；
   ⑤ 按 §五 跑真容器验证（**先 1 个域打样，再推 10 域**）。
5. **前置裁决（与本报告并行）**：`docs/决策记录.md` 是否新增「全 Jackson 3」决策条目 + AGENTS.md §八 检查清单加「Jackson 版本一致性」一条。

---

## 附录 · 证据出处（复用既有记录，未做无边界扫描）

| 结论 | 出处 |
|---|---|
| Boot 4 JSON 自动配置只产 Jackson 3；无 `spring-boot-starter-json`；加 `spring-boot-jackson` 也解决不了类型不匹配 | RAG `docs/Pitfalls/like/rpc.md` **PIT-LIKE-19**（原 P-03） |
| `FAIL_ON_TRAILING_TOKENS` 默认值翻面 | RAG `docs/DRIFT/like/api.md` **DRIFT-LIKEAPI-014** |
| Jackson 3 coercion 只 LogicalType 级生效（类级无效） | RAG `docs/Pitfalls/like/api.md` + `docs/DRIFT/like/api.md` **DRIFT-LIKEAPI-012** |
| 未知字段策略（`ignoreUnknown=false` 是 no-op） | RAG `docs/DRIFT/like/rpc.md` **DRIFT-LIKE-007** |
| `Wire.MAPPER` 与域侧严格 mapper 方向相反且刻意 | RAG `docs/DRIFT/comment/rpc.md`（`CommentDeliveryValidator` 段） |
| `ToStringSerializer` 两代包名不同 ⇒ 被明确否决 | RAG `docs/FileTree/favorite/api.md:319` |
| 注解包名两代通用（最容易看走眼） | RAG `docs/Pitfalls/like/api.md` |
| 跨语言哈希换库前须双侧字节对拍 | RAG `docs/Pitfalls/like/rpc.md` PIT-LIKE-19「下次怎么防」③ |
| 社区事实指纹与 Jackson 无关 | `service/common/.../communityfact/Wire.java:320-334`（`org.erdtman.jcs` + 原始字节） |
| 5 份刻意配置的并集 | 配套清单 §四（脚本从 5 个 `Jackson2ObjectMapperConfiguration` 提取） |
| 文件级计数 / 模式分类 / 逐文件清单 | 配套清单（脚本全量扫描 1,381 个 `.java`） |
| JJWT / MinIO 依赖坐标 | Maven Central `jjwt-jackson-0.13.0.pom` · `~/.m2/io/minio/minio/8.5.17/minio-8.5.17.pom` |
