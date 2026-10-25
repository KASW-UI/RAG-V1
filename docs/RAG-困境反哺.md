# RAG 困境反哺 —— 把 RAG 仓库踩过的坑映射进 RAG-V1

> **来源**：`/Users/pr/Documents/java&golang/RAG/docs/`（2026-10-25 全档读取）
> **目的**：RAG 是 RAG-V1 的**前车之鉴**。RAG 已花 3 天、2109 条测试、38 组件台账换来的结论，
> 应当**在 RAG-V1 开始填实现之前就消化掉** —— 否则同样的坑要再踩一遍。
> **口径**：本文只写 **RAG 已实测坐实** 的结论，每条带出处（`文件:行`）或可复现命令。
> **核对日期**：2026-10-25

---

## 〇、RAG 现状快照（一句话）

> **RAG 把「Go → Spring」做成了「Go 的行为用 Spring 的壳重新实现一遍」，
> 于是所有 go-zero 白送的能力都要自己补、补完还要自己证明补对了。**

| 维度 | RAG 实测 |
|---|---|
| 台账规模 | `docs/` 共 **190 个文件 / ~2.5 MB / ~34,000 行** |
| 三档台账 | `DRIFT/` 38 组件 + `global` 收口域 · `Intentional_Deviations/` **49 条已签字 + 14 条候选** · `Pitfalls/` **280 条坑位** |
| 组件覆盖 | 38 组件全覆盖，但 **3 个组件台账 0 字节**（`favorite-api/common/proto` ⇒ 从未对照） |
| 结论分布 | 🔴 `NOT EQUIVALENT`：`article`(2) `like`(2) `message`(2) `users`(4) `favorite-rpc` `service/common`；✅：`comment`(3/4) `follow`(4/4) `hot`(2/3) `message-common/proto` |
| 共享层债务 | `service/common`：**27 条编号未修** + 5 项未编号 + **10 条外部域尾巴** |
| 回归 | 2109 tests / 0 failures / 0 errors / 0 skipped（29 模块） |

⚠️ **「2109 条全绿」不等于「对齐了 2109 条」** —— 这是 RAG 自己 §0 的原文，也是本文存在的理由。

---

## 一、12 条困境 → RAG-V1 的处置

> 每条格式：**RAG 的困境**（实测证据） → **对 RAG-V1 的含义** → **RAG-V1 的处置**。

### F-01 🔴 共享层是**唯一的共享写点** ⇒ 并发吞吐被卡死

| 项 | 内容 |
|---|---|
| **RAG 实测** | `service/common` 被 **16 个组件**依赖。**27 条编号未修 + 5 项未编号 + 10 条外部尾巴**（T-1~T-10）。<br>凡修法落在 `service/common` 的条目，**自动从并发任务降级为串行任务** ⇒ **这才是 10 域并发对齐吞吐被卡住的那一层**（不是「common 漂移条目多」）。<br>出处：`DRIFT/common/alignment-2026-10-23.md:718-731`（§十二·六 结论改写） |
| **含义** | RAG-V1 的 `common/` 已是 `service/common` 的等价物（47 类 / 7 个 auto-config）⇒ **同一个坑在等着** |
| **RAG-V1 处置** | ✅ **D3（starter 化）只解决「少写 `@Import`」，不解决「谁能改 common」**。<br>⇒ 新增 **`common/` 的准入与归属规则**（见 §二·R1），并写进 AGENTS.md |

### F-02 🔴 「跑绿 ≠ 对」—— 绿灯底下有四种空洞

| 空洞 | RAG 实测 | 出处 |
|---|---|---|
| **静默全跳** | `@EnabledIfDockerAvailable` 把 **15 条真 PG 用例**全部 skip（`skipped message="Docker is not available"`），而 Docker **明明可用** —— 根因是 Ryuk 镜像拉不下来，`DockerAvailableDetector` 是 `catch (Throwable) → false`，把「拉不到镜像」和「Docker 不可用」压成同一个 `false` | `DRIFT/_cross/component-coverage-matrix.md:174-251` |
| **计数虚高** | `target/surefire-reports/` 的**陈旧 XML**（测试类改名后旧报告成孤儿）使 XML 求和 = 129，真实执行 = 125，**差 4** | `Pitfalls/_cross/testing-pitfalls.md:55-104` |
| **绿测试锁死错误答案** | `MessageApiMvcPipelineTest#int64AsJsonNumberIsRejected` 把 **RAG 的实际输出**断言成「与 Go 一致」⇒ 修复时**先撞上这条红测试**，且测试名让人误判修复方向 | `Pitfalls/_cross/testing-pitfalls.md:33-52` |
| **变异无效 ⇒ 守门假绿** | 变异打在 `target/classes` 会被 `mvn test` 的资源拷贝**覆盖** ⇒ 必须打在 `src/main/resources` | `Pitfalls/_INDEX.md:12` |

| **含义** | RAG-V1 的守门脚本若不内建这四道防线，**同样会拿到「2109 全绿」的假结论** |
|---|---|
| **RAG-V1 处置** | ⇒ **`tools/difftest/check-arch-invariants.py` 必须内建 4 条硬规则**（见 §二·R2）：① 计数只取回归日志模块级汇总 ② 断言 `skipped=0` ③ 禁止 `@EnabledIfDockerAvailable` ④ 变异必须打在源码侧 |

### F-03 🔴 框架机制静默失效（四类实证 + 一类 Boot 4 专属）

| 机制 | 静默失效形态 | 出处 |
|---|---|---|
| `@Transactional` | self-invocation 不经代理 ⇒ 注解形同虚设，**无任何异常** | `Pitfalls/_cross/framework-mechanism-evidence.md` |
| `@ResponseBodyAdvice` | 连 `@ExceptionHandler` 返回的 `ResponseEntity` 也会被**二次包裹** | 同上 |
| `@TableField(typeHandler=…)` | 只对 MP 自己生成的 SQL 生效；`LambdaUpdateWrapper.set()` 走 `ew.paramNameValuePairs`，**丢掉字段类型信息** | 同上 |
| 配置属性前缀写错 | 落到**不存在的** `@ConfigurationProperties` ⇒ Spring Boot 对未知属性**静默接受** | 同上 |
| **gRPC 拦截器没挂上 server** | `hot-rpc` 缺 `GrpcServerConfigurer` ⇒ **无 span / 无 deadline 传播 / SQL 不注入 queryTimeout**；`@Import` 只保证 Bean 存在，grpc starter 3.1.0 **从不自动注册**用户 `ServerInterceptor` | `DRIFT/hot/rpc.md` 第十二轮；`DRIFT/_cross/open-items.md:18` |

| **RAG-V1 处置** | ✅ AGENTS.md §1.3 已覆盖前四类。<br>⇒ **补第五类**：RAG-V1 的 `common/` 用 `AutoConfiguration.imports`，**启动期必须有一条断言「拦截器真的进了 server 的 intercept 链」**的测试（不是「Bean 存在」）。<br>⇒ **D3 的连带风险**：`@AutoConfiguration` 与 `@Import` 不能并存（AGENTS.md §二 已记）—— RAG 的教训是**两者都存在时，`@Import` 那份按 user config 更早求值**，会改掉 `@ConditionalOnMissingBean` 语义。 |

### F-04 ✅ Flyway 账本撞号 —— **RAG-V1 已天然避开**

| 项 | 内容 |
|---|---|
| **RAG 实测** | `flyway_schema_history` 是 **schema 级**账本，一个 schema 只能有一本账。三个模块同库同 schema ⇒ `message-rpc` 与 `like-rpc` 的 **V1–V3 版本号完全撞车且内容互不相同** ⇒ 任一方先迁移，另一方的表**永远建不出来**，**不报错**。已被实测证伪的绕法：`--spring.flyway.validate-on-migrate=false --spring.flyway.out-of-order=true`（让账本**永久钉在错误版本**上）。<br>出处：`Pitfalls/_cross/flyway-ledger-collision.md` |
| **RAG-V1 现状** | ✅ **RAG-V1 不用 Flyway**（全仓 22 个 pom 零 `flyway` 命中）⇒ 走 `SchemaInitializer` 幂等重放，与 Go `AutoMigrate` 同构 ⇒ **这个坑 RAG-V1 不会有** |
| **但** | ⚠️ RAG-V1 继承的是 `SchemaInitializer` 的**另一颗雷**：Spring `ScriptUtils` 按 `;` 切分，**不支持 PG 美元引号 `$$`** ⇒ **DDL 只能单条语句**；且 `timestamp → timestamptz` 的 `USING` **会重新解释取值** ⇒ 幂等重放时**每启动一次偏移一次，永不收敛**（RAG 实测炸过启动，34 个测试红在启动阶段）。<br>出处：`Pitfalls/_cross/fix-round-lessons.md:71-106`（元教训 9/10） |

### F-05 🔴 GORM → MyBatis-Plus 的语义**不等价**（RAG-V1 同一套 ORM）

> **这是 RAG-V1 最高价值的继承项** —— RAG-V1 用的就是 MyBatis-Plus，下列每条都会原样复现。

| # | Go (GORM) | Java (MP) | 出处 |
|---|---|---|---|
| 1 | `Save`/`Updates` 问「**本次调用有没有赋值**」 | `strictUpdateFill` 问「**字段现在是不是空**」⇒ 读-改-写路径上**永远不生效** | `fix-round-lessons.md:14-17` |
| 2 | 同一个 GORM 语义**需要两种 Java 修法** | `updateById(entity)` → `MetaObjectHandler`；`update(null, wrapper)` → `setSql`（实体为 null，**handler 根本不被调用**）⇒ 判据是**写路径的实体形态**，不是「哪个更地道」 | `fix-round-lessons.md:18-20` |
| 3 | — | 字段初始化值（`= 0L`）+ `callSettersOnNulls` 默认 false ⇒ **NULL 读出来是 `0` 而不是 `null`** ⇒ `if (x != null)` 这类防御写法**永远不触发** | `fix-round-lessons.md:36-45` |
| 4 | — | **MP lambda 与手写 OGNL 是两套解析规则**：`E::isRead` 按字段名，`#{isRead}` 按 JavaBeans 属性名 ⇒ 改字段名会**同时打挂 5 条既有测试** | `fix-round-lessons.md:30-36` |
| 5 | — | `<where>` 在已有 `WHERE` 的基础 SQL 上会生成**两个 `WHERE`** ⇒ PG 语法错；且被更早的 typo 挡在后面 ⇒ **修掉外层才露出内层，一轮只能走一层** | `fix-round-lessons.md:11-13` |

| **RAG-V1 处置** | ⇒ **新增一张「GORM 语义 → MP 语义」对照表**（§二·R3），**作为填实现的强制参考**，而不是让 10 个域各自踩一遍。<br>⇒ §六 检查清单加一条：「**写路径用哪种形态？判据是实体形态，不是习惯**」 |

### F-06 🔴 守门脚本的**排除清单是架构假设** ⇒ 假阳性（与假绿互为镜像）

| 项 | 内容 |
|---|---|
| **RAG 实测** | `check-ddl-static.py` 跑 `users` 域返回 **`EXIT=2`**，报「仅 RAG 有：`flyway_schema_history.*`」⇒ 看着像「RAG 多建了表」。真相：脚本的 `exclude_tables` 逐个列表名（`users` 域没有 Flyway 账本 —— 这个假设**只�� 10-25 前成立**）⇒ **脚本没坏，是它的架构假设过期了**。<br>修法：排除项改**前缀匹配** ⇒ 天然覆盖未来新增的账本表。<br>出处：`Pitfalls/_cross/testing-pitfalls.md:108-141` |
| **RAG-V1 含义** | RAG-V1 已有**同类排除清单**：L3 降级项（`D-01` 错误文案 / `D-02` 熔断内部策略 / `D-03` span 名称 / `D-04` CPU 降载路径）必须在对拍中被显式排除 —— **否则全红 → 误判回归 → 触发修回 → 降级被静默撤销**（AGENTS.md §三 硬规则 3） |
| **RAG-V1 处置** | ⇒ 排除清单**一律用前缀 / 类别匹配**，不逐个枚举。<br>⇒ **每次架构变更后重跑守门并确认「输出形态没变」** —— 这是识别「假设过期」的唯一判据 |

### F-07 🔴 响应已 committed 后 `setHeader` 被静默忽略

| 项 | 内容 |
|---|---|
| **RAG 实测** | 400 响应丢 `Content-Type` ⇒ **4 轮对拍全绿仍漏**。根因：响应已 committed 时 `setHeader` **静默忽略** ⇒ MockMvc **测不到**，必须**真 Tomcat**。<br>出处：`Pitfalls/_INDEX.md`（message 域 36 条）；`tools/difftest/check-plaintext400-headers.sh` 就是为此存在 |
| **RAG-V1 含义** | RAG-V1 的 `common/web/GoStylePlainTextErrors` + `GoStyleRouterErrorResponses` 复刻了 Go 的 400 形状（`text/plain; charset=utf-8` + 裸文本 + 尾换行 + `nosniff`）⇒ **同一层风险** |
| **RAG-V1 处置** | ⇒ HTTP 层的形状断言**必须是真 Tomcat**（`@SpringBootTest(webEnvironment = RANDOM_PORT)`），**MockMvc 不算**。⇒ 列入 §六 检查清单 |

### F-08 🔴 Go 的容器上限**不是常量**

| 项 | 内容 |
|---|---|
| **RAG 实测** | Go `net/http` 的真实上限 = `DefaultMaxHeaderBytes(1<<20)` **+ 4096 bufio slop** ⇒ 配 1MB 会比 Go **严 4096 字节**。双侧活体复测：请求行首个翻转长度 **两侧都是 1052536 字节（差 0）**。<br>出处：`Pitfalls/_INDEX.md:13`、`DRIFT/hot/api.md` 第十三轮 |
| **另一层** | Tomcat `maxParameterCount` 数**总参数个数**（`parameterCount >= limit`），go-zero `httpx/util.go:44-53` 数**非空值个数**（`if len(v)==0 { continue }`）⇒ **2049~10000 段且非空值 ≤2048 时 Go 200 / RAG 5001** ⇒ 跨域待拍板 |
| **RAG-V1 处置** | ⇒ 三个旋钮（`relaxed-query-chars` / `max-parameter-count` / `max-http-request-header-size`）必须**与 Go 逐字对齐**，且**header 上限写 1028KB 不是 1MB**。<br>⇒ RAG-V1 是一域一进程（10 个 api → 10 个 app），**每个 app 都要配** ⇒ 这正是「逐个域抄 3 行 yaml」的重复成本，属 starter 该收的部分 |

### F-09 🔴 proto `package` 归一 ⇒ **gRPC 方法路径变了**

| 项 | 内容 |
|---|---|
| **RAG 实测** | Go 侧遗留名 `pb`；RAG 归一为域名 ⇒ gRPC 方法路径 `/comment.CommentService/*` vs Go 的 `/pb.CommentService/*` ⇒ **wire 层可见**。`like` 域已实现且做过双侧对拍，改包名后 108 例全绿 ⇒ **仓内自产自销时影响为零**，但**跨栈互调会 `UNIMPLEMENTED`**。<br>出处：`Intentional_Deviations/_INDEX.md:118-152` |
| **RAG-V1 现状** | ✅ **RAG-V1 的 10 个 proto 与 Go 侧逐字节一致**（`java_package` 属表达层，Go 专用项 Java 不消费）⇒ **天然避开这个坑** |
| **但** | ⚠️ RAG-V1 的 D2（域内直连，不自我 gRPC 往返）意味着**跨域调用才走 gRPC** ⇒ **跨域 proto 的 `package` 就是 wire 契约** ⇒ **不得归一**。需在实现时把这条钉进**根级 `proto/` 模块**（D7 后 proto 已集中于此）的说明 |

### F-10 🔴 HTTP 契约层的可观察差异（L1 逐字）

RAG 已签字的偏离（RAG-V1 需**逐条独立裁决**，不能照抄）：

| 项 | Go | RAG | 类别 |
|---|---|---|---|
| HTTP path | `/usercenter/v1/user/*` · `/admincenter/v1/admin/*` | `/user/v1/*` · `/admin/v1/*` | **有意偏离**（HUMAN_ONLY 决策） |
| HTTP method | users 域 Get/Post/Put/Delete 混合 | **统一 POST** | **有意偏离** |
| 表名 | `users` · `admins` · `admin_invites` · `user_avatar_history` | `users_user` · `admin_admin` · `admin_invite` · `users_user_avatar_history` | **有意偏离** |
| `OperatorId` | 恒 0 且**不校验** claim | **严格校验**，缺失即拒 | **有意偏离**（RAG 更严） |
| `uid` 解析 | 两套语义（一条静默转 0，一条严格拒） | **统一拒绝** | **有意偏离**（RAG 更严） |
| `affected` | `int64` 无 `,string` ⇒ JSON 数字 | **字符串** | **有意偏离**（wire 契约变更） |
| 499 语义 | 客户端断连 ⇒ 取消在飞 gRPC + 打 `[HTTP] 499` | 断连后**等 2s deadline**、**继续发下游调用**、**无 499 记录** | 🔴 **仍未修**（T-4，全仓**零条** 499 断言） |
| `Content-Type` charset | 成功/错误响应差异 | 缺 `charset` / 缺 `Traceparent` | 🟡 **仍 OPEN**（T-6/T-7/T-8） |
| `MaxConns` 作用域 | **每路由一个** latch | **全局一个** | 🔴 **仍未修**（T-2/T-5） |
| 熔断计数键 | `path.Join(cc.Target(), method)` | `method.getFullMethodName()`（**无 target 前缀**） | 🔴 **仍未修**（T-9） |

> ⚠️ **共性**：「多数是 **RAG 做了更严/更安全的那一侧**」⇒ 对拍看到它们变红，**先确认是不是签字项，不要当新回归去「修」**（RAG `Intentional_Deviations/_INDEX.md:37`）。

### F-11 🔴 DDL 层的三颗雷

| # | 雷 | RAG 实测 |
|---|---|---|
| 1 | **`DEFAULT CURRENT_TIMESTAMP` 残留 14 处** | Go 侧 `column_default = <NULL>`。message 域 6 处**刻意保留并签字**（`SD-2026-10-24-04`：两侧 INSERT 都显式绑定该列 ⇒ 默认永不触发）；**users 域 8 处无签字** ⇒ 属**未签字的已知差异** |
| 2 | **时间列类型** | Go 可空 / RAG `NOT NULL`；`timestamp` vs `timestamptz`（4 张 users 表已修；message 域 6 处 DEFAULT 保留） |
| 3 | **索引** | users 域缺 Go 的 3 条**非唯一**索引（**性能非行为**）；DDL 类型/长度**0 差异** |

### F-12 🔴 台账自身的三个缺陷（**元问题**，RAG-V1 必须一开始就避开）

| # | 缺陷 | RAG 实测 | RAG-V1 处置 |
|---|---|---|---|
| 1 | **编号撞号 ⇒ 编号不可寻址** | `SD-` 族 **6 处撞号**（`SD-2026-10-23-06` 指向 3 个互不相干的条目）；`P-` 族 **22 处撞号** | ⇒ **RAG-V1 的编号一律带域前缀**（`DRIFT-<域>-<NN>` / `SD-<域>-<NN>`），**不得用裸号** |
| 2 | **三档判据被反复重新发明** | RAG 用了三天才定死：「**这条差异要不要「修回去」？要 ⇒ DRIFT；不要（有意）⇒ Intentional_Deviations**」 | ✅ RAG-V1 AGENTS.md §五 已写；但需**补上「框架限制 / 表述差异」不混谈**这一条（RAG `Intentional_Deviations/common/_signed-deviations.md:4`） |
| 3 | **「有台账」≠「已对照」；「已改」≠「已验证」** | `favorite` 域 **3 个组件台账 0 字节**；RAG §0 有 **5 处「状态更正」**逐条推翻自己上一版的结论 | ⇒ RAG-V1 台账**必须带「证据强度」列**（源码级 / 真 PG / 真启动 / 双侧活体对拍），且**状态断言必须带核对日期**，否则按 `UNKNOWN` 处理 |

---

## 二、RAG-V1 的 6 条新增硬规则（写进 AGENTS.md）

> 全部来自上文的 RAG 实测，不是推演。

### R1 · `common/` 的准入与归属规则（对应 F-01）

1. **准入**：`common/` 只放**跨域共用**且**无域归属**的能力。**带域语义的一律不进**（如错误码值/文案/语言、路由前缀、MQ 消息字段 ⇒ §1.1b 逐域隔离）。
2. **归属**：`common/` 的每个子包标**归属域**（如 `communityfact/` ⇒ 归属 `like`+`comment`+`favorite` 三域共管）。
3. **变更串行**：任何落进 `common/` 的修复**必须单写**（RAG 的 `global` 收口域模式）⇒ **RAG-V1 预留 `align/global` worktree**。
4. **影响面登记**：改 `common/` 必须在台账写「**影响哪些域**」。

### R2 · 守门脚本的 4 条硬规则（对应 F-02 / F-06）

1. **测试计数只取回归日志的模块级汇总**，**不取** `target/surefire-reports/TEST-*.xml` 求和。
2. **报「0 failures」时必须同时断言 `skipped = 0`**。
3. **禁止 `@EnabledIfDockerAvailable`**（RAG 实测：它把「拉不到镜像」压成「Docker 不可用」⇒ 静默全跳）。若用 Testcontainers ⇒ **必须配 `RYUK_DISABLED` surefire 注入**。
4. **排除项用前缀 / 类别匹配**，不逐个枚举；**每次架构变更后重跑并确认输出形态没变**。

### R3 · 「GORM 语义 → MyBatis-Plus 语义」对照表（对应 F-05）

> **填实现时强制参考**，不是可选参考。落在 [`docs/L3-降级清单.md`](L3-降级清单.md) 旁边。

| Go (GORM) 写法 | MP 等价写法 | 坑 |
|---|---|---|
| `Save` / `Updates` 显式赋值 | `updateById(entity)` → `MetaObjectHandler` | 读-改-写路径上 `strictUpdateFill` **永不生效**（实体从库里读出来，非 null ⇒ 跳过） |
| `Updates(map)` / 无实体 | `update(null, wrapper)` → `setSql` | 实体为 null ⇒ **`MetaObjectHandler` 根本不会被调用** |
| `Where("x = ?", v)` + 已有 `WHERE` | `LambdaQueryWrapper` | `<where>` 在已有 `WHERE` 上**生成两个 `WHERE`** |
| 字段名 `isRead` | `E::isRead`（MP lambda） | MP lambda 按**字段名**；`#{isRead}`（手写 `@Insert`）按 **JavaBeans 属性名** ⇒ **两套解析规则** |
| 指针字段 `*int64`（可为 nil） | `Long x = 0L` + `callSettersOnNulls=false` | **NULL 读出来是 `0` 不是 `null`** ⇒ `if (x != null)` 永不触发 |

### R4 · 事务边界的三条判据（对应 F-05 / F-03）

1. **`@Transactional` 必须在**独立 Bean**上**（跨 Bean 调用 ⇒ 走代理 ⇒ 真生效）；同 Bean 内 self-invocation ⇒ **注解形同虚设且无任何异常**。
2. **写路径的实体形态决定修法**（`updateById` vs `update(null, wrapper)`），不是「哪个更地道」。
3. **`!= null` 判据前必须先证明 null 真能到**（见 R3 末行）。

### R5 · HTTP 形状断言必须真 Tomcat（对应 F-07）

MockMvc **测不到**「响应已 committed 后 `setHeader` 被静默忽略」。⇒ 凡断言 `Content-Type` / `nosniff` / 状态行 reason phrase / `Traceparent` ⇒ **必须真 Tomcat**（`webEnvironment = RANDOM_PORT`）。

### R6 · DDL 三约束（对应 F-04 / F-11）

1. **`db/*.sql` 只能放单条语句** —— `ScriptUtils` 按 `;` 切分，**不支持 PG 美元引号 `$$`** ⇒ 无块、无 dollar quoting、无存储过程定义。
2. **`timestamptz` 转换必须用 `SET TIME ZONE 'UTC'; … RESET TIME ZONE;` 包裹**（`USING` 会重新解释取值 ⇒ 幂等重放下**永不收敛**）。
3. **`DEFAULT CURRENT_TIMESTAMP` 的取舍按 message 域口径**：两侧 INSERT 都显式绑定该列 ⇒ 默认永不触发 ⇒ **删它是为不可观测的维度冒险** ⇒ 保留并签字，不改。

---

## 三、RAG-V1 **已经做对、因而避开**的坑（4 条）

| # | RAG 的坑 | RAG-V1 的做法 | 状态 |
|---|---|---|---|
| 1 | **Flyway 账本撞号**（schema 级账本，同库同 schema 撞版本号） | **不用 Flyway** ⇒ `SchemaInitializer` 幂等重放（与 Go `AutoMigrate` 同构）⇒ **根因不存在** | ✅ 已避开 |
| 2 | **proto `package` 归一 ⇒ gRPC 方法路径变** | **10 个 proto 与 Go 逐字节一致** ⇒ wire 契约零漂移 | ✅ 已避开 |
| 3 | **手写必填校验器 ⇒ 放弃 Bean Validation 生态** | **D5**：DTO 全 `record` + Bean Validation（`@NotNull`/`@NotBlank`）⇒ 生态可用（代价：错误文案降级，已登记 `D-01`） | ✅ 已避开 |
| 4 | **9 份 `MybatisPlusOrderingBridgeAutoConfiguration` 复制** + **每模块手写 `@Import` 清单** | **D3**：starter 化 + `AutoConfiguration.imports`（7 个 auto-config）⇒ **零 `@Import` 清单** | ✅ 已避开 |

---

## 四、🔴 RAG-V1 **正在复现**的坑（1 条，已实测坐实）

### 🔴 K-1 · Boot 4 把 Kafka 自动配置拆出 `spring-boot-autoconfigure` ⇒ RAG-V1 的 7 个模块命中

**RAG 的两份台账在这里互相矛盾，本节给出实测结论。**

| RAG 的说法 | 出处 | 实测判定 |
|---|---|---|
| 「缺 `spring-boot-kafka` ⇒ **硬启动失败**，实测 `NoSuchBeanDefinitionException: KafkaTemplate`」 | RAG `AGENTS.md` §3.3.3 | ✅ **对（有条件）** |
| 「Boot 4 把 Kafka 自动配置整体搬出 ⇒ `@KafkaListener` **完全静默忽略**」 | RAG `docs/Pitfalls/_INDEX.md:54` | ✅ **也对（有条件）** |

**两者都对，只是症状不同 —— 而这正是危险之处：同一个缺陷有两种表现，取决于代码里有没有注入点。**

**实测证据链（2026-10-25，`javap` + `unzip` 于本地 m2）**：

```bash
# ① Boot 4.0.8 的 spring-boot-autoconfigure 里 kafka 相关类 = 0
unzip -l ~/.m2/repository/org/springframework/boot/spring-boot-autoconfigure/4.0.8/spring-boot-autoconfigure-4.0.8.jar | grep -i kafka
#   → (无输出)

# ② 全部 Kafka 自动配置在独立模块 spring-boot-kafka
unzip -l ~/.m2/repository/org/springframework/boot/spring-boot-kafka/4.0.8/spring-boot-kafka-4.0.8.jar | grep -i autoconfigure
#   → KafkaAutoConfiguration / KafkaAnnotationDrivenConfiguration / ...

# ③ KafkaAutoConfiguration 的 @Import 里带着 KafkaAnnotationDrivenConfiguration
javap -v -cp ~/.m2/.../spring-boot-kafka-4.0.8.jar \
  org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration | grep -A2 'Import'
#   → @Import(value=[KafkaAnnotationDrivenConfiguration, KafkaStreamsAnnotationDrivenConfiguration])

# ④ @KafkaListener 的处理者 KafkaListenerAnnotationBeanPostProcessor 在 spring-kafka（底层库）里，
#    但它是被 KafkaAnnotationDrivenConfiguration 注册成 Bean 的
unzip -l ~/.m2/.../spring-kafka-4.0.7.jar | grep KafkaListenerAnnotationBeanPostProcessor
#   → org/springframework/kafka/annotation/KafkaListenerAnnotationBeanPostProcessor.class  ✅ 在
```

⇒ **完整因果链**：

```
只有 spring-kafka（无 spring-boot-kafka）
  └─► KafkaAutoConfiguration 类不存在（它在 spring-boot-kafka 的 .imports 里）
       └─► @Import(KafkaAnnotationDrivenConfiguration) 不发生
            └─► KafkaListenerAnnotationBeanPostProcessor 永不注册为 Bean
                 └─► 症状 A：@KafkaListener 方法永不扫描 ⇒ 消费者静默不消费（零报错、零日志）
       └─► KafkaTemplate / ConsumerFactory / ProducerFactory Bean 不存在
            └─► 症状 B：任何注入点 ⇒ NoSuchBeanDefinitionException ⇒ 硬启动失败
```

⚠️ **RAG 的 `AGENTS.md` 自己写了纪律**：「**判定时先实测启动，再决定风险等级，不要凭形态推定**」—— 而 RAG 的 `Pitfalls/_INDEX.md` 恰恰做了一次**凭形态推定**（没启动就断言「完全静默忽略」）。两句话不冲突，是因为**它们描述的是不同症状**，但**并列放着就会误导下一个人**。

**RAG-V1 的现状（实测）**：

| 项 | 实测 |
|---|---|
| Boot 版本 | **4.0.8**（与 RAG 同版本 ⇒ 同风险面） |
| 引 `spring-kafka` 的模块 | **7 个**：`like` · `comment` · `message` · `favorite` · `task` · `hot` · `article` |
| 引 `spring-boot-kafka` 的模块 | **0 个** ❌ |
| `common/` starter 里 | **无任何 kafka 依赖** ❌ |
| 当前 `@KafkaListener` | **0 处** ⇒ **尚未引爆**（潜伏态） |

⇒ **一旦有人加第一个 `@KafkaListener`，症状 A（静默不消费）就会发生，且没有任何报错提示。**
⇒ **一旦有人注入 `KafkaTemplate`，症状 B（启动崩溃）会发生。**

**⇒ RAG-V1 的处置（已实施，2026-10-25）**：

1. ✅ **`common/` starter 统一声明 `spring-boot-kafka`（`<optional>true</optional>`，版本由 Boot BOM 管理，不写 `<version>`）** ⇒ 7 个域一次性拿到 `KafkaTemplate` + listener BPP，且**只有一处**（这正是 D3 的价值）。
   *落地*：`common/pom.xml` 新增该依赖 + 注释写明因果链。
2. ✅ **7 个 service 模块的 `spring-kafka` 裸依赖改为 `spring-boot-kafka`**（`spring-boot-kafka` 传递依赖 `spring-kafka`）。
   *落地*：`like` · `comment` · `message` · `favorite` · `task` · `hot` · `article` 各 1 行。
3. ✅ **加进 §六 检查清单**：「引入任何 `spring-*-starter` 能力前，先确认它在 **Boot 4 下仍在 `spring-boot-autoconfigure` 里**；已被拆走的必须显式补对应模块」—— **每次都要查，不能凭记忆**。
   *落地*：`../AGENTS.md` §1.3-6 + §六 检查清单。
4. ⬜ **加一条断言**（进 `check-arch-invariants.py`）：「凡 classpath 有 `spring-kafka` ⇒ 必须同时有 `spring-boot-kafka`」。

**✅ 验证（2026-10-25 实测）**：

```bash
# ① common 的依赖表里出现 spring-boot-kafka
mvn -o -pl service/like/service -am dependency:list | grep kafka
#   → org.springframework.boot:spring-boot-kafka:jar:4.0.8:compile (optional)
#   → org.springframework.kafka:spring-kafka:jar:4.0.7:compile (optional)

# ② 全仓编译
mvn -o -T1C compile
#   → 22/22 SUCCESS，BUILD SUCCESS（protoc 离线可跑）
```

⚠️ **仍然潜伏的部分**：RAG-V1 目前 **`@KafkaListener` 0 处** ⇒ 症状 A（静默不消费）**尚未引爆**，
但只要有人加第一个 listener 而漏了依赖，**零报错**。⇒ 第 4 条断言必须补上。

---

## 五、RAG 自己的台账矛盾（2 条，记录备查）

| # | 矛盾 | 说明 |
|---|---|---|
| 1 | **Boot 4 Kafka 的症状表述分裂**（见 §四 K-1） | RAG `AGENTS.md` 说「硬启动失败」，RAG `Pitfalls/_INDEX.md` 说「完全静默忽略」⇒ **各对一半，都不完整** |
| 2 | **「8 项未决交接」这个数是错的** | RAG `AGENTS.md` §0 写「8 项未决」，实测是 **4 条仍未决 + 2 条未销账**（#2 / #8）⇒ **把「没人看过」当成了「确认未决」**。<br>出处：`DRIFT/common/alignment-2026-10-23.md:666-686` |

⇒ **元教训**：台账里的**计数**与**状态**一样会过期，且**过期得比内容更隐蔽**（内容过期一眼看得见，「8」这个数没人会去核）。⇒ RAG-V1：**任何计数必须带「口径」+「核对日期」**。

---

## 六、待办（按优先级）

| # | 事项 | 类型 | 状态 |
|---|---|---|---|
| 1 | **`common/` 加 `spring-boot-kafka`；7 个 service 模块改依赖** | 代码 | ✅ **已实施 + 已验证**（§四 K-1） |
| 2 | **「GORM 语义 → MP 语义」对照表**（R3） | 文档 | ✅ **已写** → [`GORM-MP-语义对照.md`](GORM-MP-语义对照.md) |
| 3 | **`common/` 准入与归属规则**（R1） | 文档 + AGENTS.md | ✅ **已写**（AGENTS.md §1.4 R1） |
| 4 | **`check-arch-invariants.py` 内建 4 条硬规则**（R2）+ Kafka 依赖断言 | 代码 | ⬜ **待实现**（脚本本身也未实现） |
| 5 | **HTTP 层真 Tomcat 守门**（R5） | 代码 | ⬜ 待实现 |
| 6 | **并发工作流**（worktree + 单写权） | 文档 | ⬜ 待定（RAG 的 `docs/Worktree.md` 可直接抄结构） |

---

## 附：RAG 的 docs 目录导览（想知道细节去哪儿翻）

| 位置 | 作用 | 什么时候看它 |
|---|---|---|
| `docs/DRIFT/_INDEX.md` | 漂移台账总索引（按域 + 覆盖矩阵指针） | 判断某组件对齐到哪一步 |
| `docs/DRIFT/global/_README.md` | **收口域**（跨域 + `service/common` 尾巴）| **共享层归属与串行规则的范本** |
| `docs/DRIFT/_cross/component-coverage-matrix.md` | 38 组件覆盖矩阵 + 证据强度分层 | 排优先级 |
| `docs/DRIFT/_cross/open-items.md` | 已知未修表（每行带核对日期） | 找「还有什么没修」 |
| `docs/DRIFT/common/alignment-2026-10-23.md` §十二 | 共享层真实状态（27 条 + 10 条尾巴 + 循环依赖） | **D3 延伸设计的依据** |
| `docs/Intentional_Deviations/_INDEX.md` | 签字偏离总索引（49+14）+ 编号撞号 | 判「要不要修回去」 |
| `docs/Pitfalls/_INDEX.md` | 280 条坑位索引（15 个编号前缀 + 22 处撞号） | 查某个坑的成因 |
| `docs/Pitfalls/_cross/fix-round-lessons.md` | **11 条元教训**（写测试写出来的） | **最高价值的继承项** |
| `docs/Pitfalls/_cross/testing-pitfalls.md` | 假绿 / 假阳性 / 计数口径 | 写守门脚本前必读 |
| `docs/Pitfalls/_cross/flyway-ledger-collision.md` | Flyway 账本撞号完整坑档 | 选建表机制时 |
| `docs/Worktree.md` | 并发工作流操作手册 | 开并发对齐前 |
| `docs/FileTree/` | 23 份 Go 派生文件树（只读） | 查 Go 侧职责 |
| `docs/MappingTable/` | A/B/C 三列映射（Go / Java 镜像 / Spring 目标） | 查某个 Go 文件对应哪个 Java |
| `docs/HUMAN_ONLY/` | 包级阅读指南（api / rpc 两份） | 第一次进 RAG 的包 |
