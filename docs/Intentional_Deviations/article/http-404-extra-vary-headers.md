# Intentional_Deviations / article · 404 响应多写 2 条 `Vary`（Spring 版本副产物）

> **记什么**：明知与 **B 侧（RAG 仓）** 不同、**经用户签字批准**的差异。
> **⚠️ 是决策不是缺陷**：不重复质疑，**也不得「顺手修正回旧行为」**。
> **核对日期 2026-11-03**（逐行核对 **Spring 源码** ＋ **两侧同路径代码**，非估算）·
> **依据基准 2026-11-02**：**B 侧为唯一真理**（D16）

---

## DEV-ARTICLE-HTTP-002 · 未注册路径（404）的响应头比 B 侧多 2 条 `Vary`

| 项 | 内容 |
|---|---|
| **状态** | 🏷 **候选，建议签**（登记即交付物；「两侧确实不同」的前提已由本文件逐行核对确认） |
| **分域** | article 域（`service/article/`） |
| **偏离类型** | **框架限制**（L4 机制层 · **D14 工具链选型的副产物**） —— 非「有意偏离」，也非「表述差异」 |
| **台账编号** | **D-15**（article 域 DRIFT 台账 `docs/DRIFT/article/verification-article-service-2026-11-03.md` §11.5.2） |
| **提出** | 2026-11-03 会话：闭合 U-3（7 个端点 × 响应头字节面穷举）时实测发现 |

### 偏离内容

**未注册路径**（例如 `GET /v1/no-such-route`）的 404 响应，两侧的 `Vary` 头**条数不同**：

| | **B 侧（RAG 仓 · Boot 4.0.8 / Spring 7.0.10）** | **RAG-V1（C）· Boot 3.5.11 / Spring 6.2.16** |
|---|---|---|
| `Vary` 条数 | **1** 条 —— 只有 `Vary: Origin` | **3** 条 —— `Vary: Origin` ＋ `Vary: Access-Control-Request-Method` ＋ `Vary: Access-Control-Request-Headers` |
| 来源 | 域内 CORS filter 的 `setVaryHeaders`（**无条件**写 `Origin`） | 同上 **＋** Spring `DefaultCorsProcessor.processRequest` **额外**写的 2 条 |
| 其余响应头 | `Content-Type: text/plain;charset=utf-8` · `X-Content-Type-Options: nosniff` · `Content-Length: 19` · body = `"404 page not found\n"` | **逐条相同**（已实测，见「证据」节） |

⚠️ **只影响 404 这一个形态** —— 200 / 400 / 401 三种形态的 `Vary` 都是 **1** 条（两侧相同）⇒
原因见下：只有 404 的**命中 handler 不同**（静态资源处理器 vs 业务 handler）。

### 根因链（逐环可查 · 每环都到行号）

1. **两侧走的是同一条 404 路径** —— 未注册路径被 **Spring 的静态资源处理器**（`ResourceHttpRequestHandler`，匹配 `/**`）命中 ⇒ 资源不存在 ⇒ 抛 `NoResourceFoundException` ⇒ 由异常处理器产出 404：
   - **B 侧**：`service/article/article-api/src/main/java/com/rag/article/article/api/advice/GlobalExceptionHandler.java:280-283`
     （`@ExceptionHandler(NoResourceFoundException.class)` → `GoStyleRouterErrorResponses.notFound(e.getResourcePath())`）
   - **C 侧**：`service/article/src/main/java/com/rag/article/advice/ArticleApiExceptionHandler.java:324-327`
     （`@ExceptionHandler(NoResourceFoundException.class)` → `RouterErrorResponses.notFound(e.getResourcePath())`）
   ⇒ ⇒ **两侧的触发机制同构**，**不是「C 侧多走了一步」**。
2. **静态资源处理器是一个 `CorsConfigurationSource`** ——
   `ResourceHttpRequestHandler implements HttpRequestHandler, EmbeddedValueResolverAware, InitializingBean, CorsConfigurationSource`
   （Spring **6.2.16** `:97` / **7.0.10** `:98`）
   ⇒ `AbstractHandlerMapping.hasCorsConfigurationSource` 里 `handler instanceof CorsConfigurationSource` **为真**
   （**6.2.16** `:645-650` / **7.0.10** `:712-717`）
   ⇒ ⇒ 响应链上**必然**挂上 `CorsInterceptor` ⇒ ⇒ `DefaultCorsProcessor.processRequest(...)` **必然**被调用。
   ⇒ ⚠️ **200 / 400 / 401 命中的是 `HandlerMethod`（不是 `CorsConfigurationSource`）⇒ 这条路径不成立 ⇒ 所以只有 404 多 Vary**。
3. 🔴 **唯一的变量 = Spring 版本** —— `DefaultCorsProcessor.processRequest` 的**首个分支**不同：

   | | Spring **6.2.16**（C 侧） | Spring **7.0.10**（B 侧） |
   |---|---|---|
   | 入口 | `:72` | `:72` |
   | **`config == null` 守卫** | `:103` —— **在写 Vary 之后** ⇒ `config` 为 null 时**仍会写 3 条 Vary** | `:75-80` —— **在写 Vary 之前就 `return`** ⇒ `config` 为 null 时**一条 Vary 都不写** |
   | 写 `Vary: Origin` | `:77` | `:84` |
   | 写 `Vary: Access-Control-Request-Method` | `:80` | `:87` |
   | 写 `Vary: Access-Control-Request-Headers` | `:83` | `:90` |

   ⇒ 静态资源处理器的 `corsConfiguration` **默认是 null**（`ResourceHttpRequestHandler` 的字段只在 `setCorsConfiguration(...)` 时被赋值，
   `afterPropertiesSet()` **不填默认值**）⇒ ⇒ **B 侧在 `:75` 就返回了** ⇒ B 侧 404 只有 CORS filter 写的那 **1** 条 `Vary: Origin`；
   ⇒ ⇒ **C 侧在 `:77/:80/:83` 写满了 3 条** ⇒ ⇒ **差 2 条**。

### 影响面（已核实）

| 项 | 结论 |
|---|---|
| **语义** | ✅ **无害** —— `Vary` 声明「响应随哪些请求头而变」。`Access-Control-Request-Method` / `Access-Control-Request-Headers` 是**预检**请求头，在 404 响应上**本就不参与任何协商** ⇒ 多声明两条**不改变**任何缓存/协商语义（按 RFC 9110 §12.5.3，多列一个不参与变化的请求头只可能让缓存**更保守**） |
| **L1 契约层** | ✅ **零影响** —— proto / 错误码值 / HTTP path / Kafka topic / 表结构 / JWT claims **均未动**；404 的**状态码**与**body 逐字节一致**（19 字节 `"404 page not found\n"`） |
| **影响面** | 🔴 **只有未注册路径的 404** —— 200 / 400 / 401 三个形态的 `Vary` 都是 1 条（**两侧相同**，已实测） |
| **其它域** | ⚠️ **同一形态会出现在其它域**（同样用 Spring Boot + 同样的静态资源处理器 ⇒ 同样命中 `CorsConfigurationSource`）⇒ ⇒ **归 `global` 收口域**（AGENTS.md §五.3 规则 3）—— **本域只登记，不改码** |

### 为什么不修（选项与理由）

| 选项 | 评价 |
|---|---|
| **换 Spring 版本** | ⛔ **已排除** —— D14（工具链 = Jackson 2 + Boot 3.5）是**用户已拍板**的口径 ⇒ 换版本会推翻 D14 |
| **在应用层替换 `CorsProcessor`** | 🔴 **不采纳** —— 需要 `AbstractHandlerMapping#setCorsProcessor` 逐个替换**全局** handler mapping 的处理器 ⇒ 影响面覆盖**整个 app 的预检路径**；而 C 侧的 CORS 本来就**完全由域内 `ArticleCorsFilter` 承担**（`@Order(HIGHEST_PRECEDENCE + 1)`，OPTIONS 直接 204 短路）⇒ `DefaultCorsProcessor` 在 C 侧**除了这 2 条冗余 `Vary` 之外没有任何实际作用** ⇒ 为去掉 2 个冗余头而替换全局 CORS 处理器，**收益远小于风险** |
| **把 `ResourceHttpRequestHandler` 排除出 handler mapping** | 🔴 **不采纳** —— 那是**改路由语义**（未注册路径不再被静态资源处理器命中）⇒ 会牵动 404 的产出路径 ⇒ 属 L2 行为改写 ⇒ 需另走决策流程 |
| **登记为签字偏离** | ✅ **采纳** —— 差异**已被完整归因**到单一变量（Spring 版本），**语义无害**，**L1 零影响**，且修法成本/风险不划算 ⇒ 属「**框架限制**」类登记（`_INDEX.md` 三分类之一） |

### 证据（**两侧同路径** + **源码级** + **运行级**）

| 证据 | 位置 |
|---|---|
| **C 侧实测**：404 的响应头**逐条**为 3 条 `Vary` ＋ 5 条 CORS ＋ `Traceparent` ＋ `X-Content-Type-Options` ＋ `Content-Type` ＋ `Content-Length` ＋ `Date` ＋ `Connection` | `service/article/src/test/java/com/rag/article/http/ArticleHttpEndpointHeaderPlaneRealTomcatTest.java`（真 Tomcat · 裸 socket · **2/2 绿**） |
| **C 侧实测**：200 / 400 / 401 三个形态的 `Vary` **各 1 条** ⇒ 证明「只有 404 多」 | 同上（同一测试类的第二条用例） |
| **B 侧源码级**：404 由 `NoResourceFoundException` ⇒ `GoStyleRouterErrorResponses.notFound` 产出（**与 C 侧同构**） | `service/article/article-api/src/main/java/com/rag/article/article/api/advice/GlobalExceptionHandler.java:280-283` |
| **B 侧 CORS filter 源码级**：**无条件**写 1 条 `Vary: Origin`（OPTIONS 才追加另两条） | `service/article/article-api/src/main/java/com/rag/article/article/api/config/ArticleGoStyleCorsFilter.java:125-131` ⇒ C 侧同形实现 `service/article/src/main/java/com/rag/article/config/ArticleCorsFilter.java:156-162` |
| **Spring 源码级**：6.2.16 与 7.0.10 的 `processRequest` **首个分支不同**（见根因链第 3 环） | `~/.m2/repository/org/springframework/spring-web/{6.2.16,7.0.10}/spring-web-*-sources.jar` → `org/springframework/web/cors/DefaultCorsProcessor.java` |
| **B 侧 Spring 版本** | `service/pom.xml:8` = `spring-boot-starter-parent`；`pom.xml:79` / `:131` = **Spring Boot 4.0.8** ⇒ Spring Framework **7.0.10** |
| **判据有牙齿**（变异探针 **MUT-F**） | 在 `ArticleCorsFilter#setCorsHeaders` 凭空多写 1 个响应头 ⇒ 该测试**两条用例都红**（逐字失败断言见 article DRIFT 台账 §11.6.4）⇒ 还原后**复跑为绿** |

⚠️ **B 侧没有现成断言可引用** —— B 侧 `like-api` 的 `LikeApiRouterErrorRealTomcatTest`（`:130-137`）只断言 404 的 **body / Content-Type / nosniff**，
**不断言 `Vary` 条数** ⇒ ⇒ 「B 侧 404 只有 1 条 Vary」这一条**不是**B 侧现成断言，而是**由 B 侧的 Spring 版本 + B 侧源码路径推导**出来的
⇒ ⇒ 按 §3.3.3「凡写『与 B 侧一致』必须有两侧同时跑的探针」的纪律，本条**不声称**已做 B 侧活体对拍，
**只声称**「两侧的**代码路径同构** + **唯一变量是 Spring 版本** + **该版本源码逐行可查**」。

### ⚠️ 边界声明（不得越界）

- 本条**只覆盖**「未注册路径 404 响应的 `Vary` 条数」这一件事。
- 本条**不豁免** L1 的其它部分：proto 字段号 / 错误码值 / HTTP path / Kafka topic / 表结构 / JWT claims **仍须逐字对齐 B 侧**。
- 本条**不豁免** 200 / 400 / 401 三个形态的响应头 —— 它们的 `Vary` **两侧都是 1 条**，**无豁免**。
- ⚠️ **不得**因为「404 多两条 Vary 无害」就**顺手删掉** 404 用例里的 `Vary` 断言 —— 那是**把已登记的偏离改成「静默接受」**，属台账纪律问题。
- ⚠️ **不得**「顺手统一」成「404 与其它形态一样只写 1 条 Vary」 —— 那是**行为改写**（改的是框架行为），需另走决策流程。
- ⚠️ **不得**在**未跑 B 侧活体探针**的情况下把本条升级成「已双侧对拍一致」。

### 引用

- 台账（本域） → [`../../DRIFT/article/verification-article-service-2026-11-03.md`](../../DRIFT/article/verification-article-service-2026-11-03.md) §11.5.2 **D-15** · §11.6.4.6 · §11.6.5 **U-3**
- 工具链口径（D14） → [`../../../docs/决策记录.md`](../../../docs/决策记录.md) **D14**
- 基准切换（D16） → [`../../../docs/决策记录.md`](../../../docs/决策记录.md) **D16**
- 偏离档索引 → [`../_INDEX.md`](../_INDEX.md)（⚠️ **跨域索引归 `global` 收口域**，本域**未改**）
- **B 侧台账与代码（原始逐条来源下沉处）** → `/Users/pr/Documents/java&golang/RAG` 的 `service/article/article-api/src/main/java/com/rag/article/article/api/{advice/GlobalExceptionHandler.java, config/ArticleGoStyleCorsFilter.java}`
