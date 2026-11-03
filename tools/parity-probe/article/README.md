# article 域 **B 侧 ↔ C 侧**双侧活体对拍探针

> **落点**：`tools/parity-probe/article/` —— **本仓专属**目录（只被 article 域引用 ⇒ 按
> AGENTS.md §五.3「共享判据 = 依赖度数，不是目录位置」⇒ 不属共享层 ⇒ 本域可写）。
>
> **对照双方**：
> | | 形态 | 产物来源 |
> |---|---|---|
> | **B 侧**（唯一 oracle · 只读） | `article-api`（HTTP 进程）+ `article-rpc`（gRPC 进程）= **两个进程**（Boot 4） | **本地现成镜像** `sea/article-api:latest` / `sea/article-rpc:latest` ⇒ **B 仓一个字节都不改**（连 `target/` 都不写） |
> | **C 侧**（被测） | `article-service` = **一个进程**同起 Tomcat + gRPC（D1 合并 · D2 域内直连） | 本工作树 `mvn -o -pl service/article -am package` 的 fat jar |
>
> **闭合的缺口**：`docs/DRIFT/article/verification-article-service-2026-11-03.md` §11.6.5 **U-1**
> —— 上一轮的探针是「B 侧**期望值移植**到 C 侧」，**证不了「B 侧现在就是这个行为」**；
> 本探针让**两侧同时起服务** ⇒ 同一请求 ⇒ **逐字节**比对 ⇒ 才算 **E-4 双侧活体**。

---

## 1. 用法

```bash
bash tools/parity-probe/article/run.sh             # EXIT=0 = 双侧逐字节一致
bash tools/parity-probe/article/mutate-parity.sh   # 变异验证（三条必须精确转红）
FREEZE=1 bash tools/parity-probe/article/run.sh    # 重建冻结基线 expected.txt
KEEP_WORK=1 bash tools/parity-probe/article/run.sh  # 保留临时目录（默认也保留）
RUN_ID=A bash tools/parity-probe/article/run.sh   # 并发隔离（见 §4）
```

**退出码**（沿用 B 侧探针语义）：

| 码 | 含义 | 何时出现 |
|---|---|---|
| `0` | **双侧逐字节一致**（且与冻结基线一致） | 全部用例两侧 body hex + 状态码 + Content-Type 三者全等 |
| `1` | **有差异** | 两侧同时在跑、但某个用例的字节/状态/头不同 |
| `2` | **环境问题** | 中间件缺失 · 端口被非探针进程占 · 镜像不存在 · 两侧没起来 · 播种失败 |

> 🔴 **「环境问题」与「真差异」严格分开**：`2` 一律**附带原始报错**（容器日志 + psql 原文），
> **绝不**把「没起好」报成「C 侧有缺陷」。

**可覆盖 env**：`B_API_PORT` · `B_RPC_PORT` · `B_RPC_MGMT_PORT` · `C_HTTP_PORT` · `C_GRPC_PORT` ·
`MINIO_PORT` · `NACOS_PORT` · `KAFKA_PORT` · `REDIS_PORT` · `JAEGER_PORT` ·
`JWT_SECRET` · `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` · `B_API_IMAGE` / `B_RPC_IMAGE` ·
`RUN_ID` · `NACOS_NAMESPACE_B` / `NACOS_NAMESPACE_C` · `PORT_OFFSET` / `PG_PORT_OFFSET` ·
`PROBE_LEASE_DIR` / `PROBE_MVN_LOCK_DIR` · `B_SIDE_DOCKER_NETWORK` ·
`PROBE_SKIP_BUILD=1`（复用已有 jar，见 §8-6）· `RAG_MVN_LOCK_HELPER`。

---

## 2. 为什么只比 **HTTP 面**（gRPC 面不可比 —— 拓扑产物，不是行为漂移）

| | B 侧 | C 侧 |
|---|---|---|
| 进程数 | **2**（`article-api` + `article-rpc`） | **1**（Tomcat + gRPC 同进程） |
| api → rpc | **跨进程 gRPC**（`discovery:///article-rpc`） | **域内直连**（D2：直接注入 `ArticleDomainService`） |
| gRPC 注册名 | `article-rpc`（`…/article/rpc/bootstrap/NacosRegistration.java:42`） | `article-rpc`（`…/article/wiring/NacosRegistration.java:82`） |

⇒ 两边的 **gRPC 拓扑不同** ⇒ 对拍**只能落在 HTTP 面**（两边都对外暴露的**同一契约**）。
⇒ **这是 D1/D7 合并带来的拓扑产物，不是行为漂移** —— 本探针**不**把它报成缺陷。

> ⚠️ 由此产生一条**比较面的边界**：C 侧的 HTTP 响应**不经过 gRPC 序列化**，
> 所以「proto 字段默认值 / presence」这类**只有穿过 gRPC 跳才看得见**的差异
> **不在本探针的覆盖范围**（那部分由 proto 契约网 + 组件级测试覆盖）。

---

## 3. 比对面：覆盖了哪些端点 / 哪些响应形态

**两侧路由表逐条对齐**（B 侧 `article-api/…/controller/ArticleController.java:79-237`
⇔ C 侧 `…/controller/ArticleController.java:98-250` ⇒ 7 个端点、路径与方法全同）。

探针比对 **12 个用例**，每个用例比 **三样东西**：**响应体 hex**（`xxd`）· **HTTP 状态码** · **Content-Type**。

| # | 用例 | 端点 | 覆盖的响应形态 |
|---|---|---|---|
| 1 | `list_articles` | `GET /v1/articles` | 成功信封 `{code,msg,data}`；`data.articles[]` **16 个字段**；`total`/`page`/`page_size` |
| 2 | `list_articles_page2` | `GET /v1/articles?page=2&page_size=1` | **分页**参数；越界页 ⇒ `articles:null` |
| 3 | `list_articles_page0` | `GET /v1/articles?page=0` | 非法页码 ⇒ 业务码 **1002**（HTTP 仍 200） |
| 4 | `list_articles_unknown_param` | `GET /v1/articles?foo=bar` | 未知 query 参数**被忽略** |
| 5 | `get_article_legacy` | `GET /v1/article/9007199254740993` | **legacy 分支**（无 revision 指针）⇒ 走 **MinIO 正文回落**；`ext_info={"publication_gap":"legacy_revision_missing"}` |
| 6 | `get_article_revision` | `GET /v1/article/9007199254740994` | **revision 分支** ⇒ 正文取 `revision.markdown`；`ext_info={"published_revision_id":"rev-succ-0001"}`；`update_time` 取 `revision.published_at` |
| 7 | `get_article_missing` | `GET /v1/article/does-not-exist` | 业务码 **1002「文章不存在」** |
| 8 | `get_article_nonnumeric` | `GET /v1/article/abc` | 非数字 id ⇒ 同 1002 |
| 9 | `me_article_noauth` | `GET /v1/me/article/9007199254740994` | **无 Authorization** ⇒ **HTTP 401 + 空 body + 无 Content-Type** |
| 10 | `me_article_auth` | `GET /v1/me/article/9007199254740994`（**带自签 HS256 token**） | **作者视角**完整字段；正文取 **MinIO 对象**（不是 revision）；`ext_info` 为 **null**（与公开视角**不同** ⇒ 两侧必须同样不同） |
| 11 | `me_article_auth_missing` | `GET /v1/me/article/does-not-exist`（带 token） | 带身份时的 1002 |
| 12 | `route_notfound` | `GET /v1/nonexistent` | **HTTP 404 + `text/plain`**（**不走信封**） |

**revision 分支的 4 个独立信号**（种子里 `article` 行与 `article_revision` 行**故意填成不同值**
⇒ 不靠「两侧一致」自证）：

| # | 信号 | legacy 路径 | revision 路径 |
|---|---|---|---|
| S1 | `ext_info` | `{"publication_gap":"legacy_revision_missing"}` | `{"published_revision_id":"rev-succ-0001"}` |
| S2 | `update_time` | `article.updated_at` | **`revision.published_at`** |
| S3 | `content` | **MinIO 对象**正文 | **`revision.markdown`** |
| S4 | `title`/`brief`/`cover_image_url`/`manual_type_tag` | `article` 行 | **`revision` 行** |

---

## 4. 并发隔离（四个向量）

> **为什么要有**：探针要同时起**两整栈** ⇒ 天然要占宿主端口、要在 Nacos 注册服务、
> 要有各自的 Postgres 与 MinIO ⇒ **并发跑两份**时这些「共享面」互相污染
> ⇒ 「跑红」被误读成「C 侧有缺陷」⇒ ⇒ 一类**假红**。
> ⇒ **只改工具面**（本目录）⇒ **产品代码一个字节都没动**。

| 向量 | 怎么实现 | 为什么能隔离 |
|---|---|---|
| **V1 Nacos 命名空间（两个）** | env `NACOS_NAMESPACE_B` / `NACOS_NAMESPACE_C` | 命名空间由**客户端配置**决定（B 侧 `article-rpc/…/application.yml:125` + `article-api/…/application.yml:33`；C 侧 `service/article/…/application.yml` 的 `spring.cloud.nacos.discovery.namespace`）⇒ **不改产品代码**就能换命名空间。🔴 **必须两个**：**两侧注册的服务名都是 `article-rpc`** ⇒ 共用一个命名空间 ⇒ B 的 api 可能选中 C 的实例 ⇒ **假绿** |
| **V2 两侧各自的 Postgres** | 各起一个 PG 容器，端口**阶梯交错**（55570/55572/… 与 55571/55573/…） | 两侧 Flyway 账本表名**本就不同**（B 侧 = 默认 `flyway_schema_history`；C 侧 = `flyway_schema_history_article`）⇒ 但**共用一个 PG** 时后启动者会因 `baseline-on-migrate` **跳过自己的迁移** ⇒ 必须各一个库 |
| **V3 宿主端口块** | **原子租约**（`set -o noclobber` 的 `>` = `O_CREAT\|O_EXCL`） | 端口写死 ⇒ 两份必撞 ⇒ 后一份会踢掉前一份的进程。租约**保证**并发轮次拿到不同端口块；且端口块**先跟「容器已发布的宿主端口」求交**，冲突就换一块（见 §8-3） |
| **V4 构建产物** | `mvn` **全局串行锁** + 每轮把 fat jar **拷进自己的 WORK** | `service/article/target/` 是**同一份** ⇒ 并发写必然互踩。实测 `-Dproject.build.directory` **不生效** ⇒ 只能「持锁构建 + 拷走」 |

可覆盖 env：`RUN_ID` · `NACOS_NAMESPACE_B` / `NACOS_NAMESPACE_C` · `PORT_OFFSET` · `PG_PORT_OFFSET` ·
`PROBE_LEASE_DIR` / `PROBE_MVN_LOCK_DIR` · `KEEP_NACOS_NAMESPACE=1`。

> **默认行为**：`RUN_ID` 为空 ⇒ 命名空间 `probe-b-parity` / `probe-c-parity` · 端口块 0。

---

## 5. 明确**不可比**的面（为什么排除 —— 不是「藏起来」）

| 面 | 为什么不比 | 依据 |
|---|---|---|
| **gRPC 面** | 两侧**拓扑不同**（2 进程 vs 1 进程；跨进程 gRPC vs 域内直连） | §2 |
| **`author_name` 的 RPC 成功支** | **C 侧没有 `UserRpcClient`** ⇒ 该支在 C 侧**根本不存在** ⇒ 拿它去比**必然**不等 | 缺口 **G-2**（已登记签字偏离）· `docs/Mapping_Table/article/article.md` §1.5 G-2 行：「本仓不含 users 域 ⇒ 走 B 侧 `resolveAuthorName` 的**失败分支**（恒回落 `"用户 <id>"`）」。⇒ 探针比的是两侧**同形**的**回落支**（`author_id='0'` ⇒ 两侧都走「uid ≤ 0 ⇒ 直接回落」，**不发 RPC**） |
| **写路径**（`POST /v1/article` · `PUT` · `DELETE` · `POST /v1/upload`） | ① 会**改状态** ⇒ 两侧无法同条件；② 响应含**雪花 ID**（随机）⇒ 不可逐字节；③ 依赖 Kafka 审核 / outbox / 审计链路 | 写路径的等价性由组件级测试 + outbox/契约网覆盖 |
| **`/actuator/*`** | 可观测面，非业务契约 | —— |

> ⚠️ **`author_id='0'` 是本探针唯一一处刻意选值**，三条理由（确定性 / 可比 / 身份闸门）写在
> `run.sh` 的种子注释里。**它不是「挑了个好比较的值」** —— 它把两侧放在**同一个**、
> **已登记**的分支上，并让探针**不发任何跨域 RPC**（否则会触发 B 侧熔断 ⇒ 假红，见 §8-2）。

---

## 6. 基线冻结依据（`expected.txt`）

**格式**：`CASE|<用例名>|<状态码>|<Content-Type>|<响应体 hex>`（12 行）。

**冻结依据**（**不是**把 B 侧探针的 `expected.txt` 当成 C 侧的期望 —— 那是 Go↔RAG 的基线，语义不同）：

1. 基线**只能**在**两侧都实跑过**之后冻结 —— 流程是
   `FREEZE=1 bash run.sh` ⇒ 该轮**先跑完 12 个用例**，**确认两侧逐字节一致**，
   **才**把本轮的字节写成基线；**本轮若有差异则拒绝覆盖**（`run.sh` 里 `FAILED=0` 才 `cp`）。
2. 基线里的每一个 hex 都是**两侧共同的字节**（`B == C` 才写）⇒ 基线 = 「两侧一致」这件事的冻结，
   **不是**「B 侧单方面的期望」。
3. 基线同时冻结**状态码**与 **Content-Type** ⇒ 「业务码 1002 走 HTTP 200」这类
   **状态行契约**也被钉住，不会只看 body 而漏掉状态行漂移。
4. 之后每轮普通跑都要满足 **`B == C == expected.txt`**（三者全等才算绿）。

---

## 7. 变异验证（`mutate-parity.sh`）

> AGENTS.md §3.3.3：探针必须有「**能真正触发它**」的变异，否则无法排除「假绿」。

| 变异 | 手法 | 期望 | 证明了什么 |
|---|---|---|---|
| **M1** | **改 C 侧**数据库的 `title`（播种之后） | 探针 **EXIT=1**，且报差异的用例是**列表 + 详情**那几条 | 「比较面真的读到了 **C 侧**的库」⇒ 若探针其实在跟**自己**比，这一改**不会**被发现 |
| **M2** | **改 B 侧**数据库的 `title` | 探针 **EXIT=1** | 检测**不是单向的**（不是「C 侧特例」） |
| **M3** | 改 `expected.txt` 里一枚 hex（跑完**立刻还原** + sha256 核验） | 探针 **EXIT=1** | 「冻结期望值**确实参与判定**」⇒ 不是「只比两侧、不看基线」的自欺 |

> 🔴 **本脚本不碰任何产品代码**（B 侧探针的 M4 改的是**生产码** `ArticlePublicDomainService.java`）。
> M1/M2 只改**本轮探针自己建的** PG 容器里的数据 ⇒ 容器每轮新建 ⇒ **跑完即消失 ⇒ 自动还原**；
> M3 只改**工具面**的 `expected.txt` ⇒ 自带还原 + sha256 核验。

---

## 8. 踩过的坑（写下来免得下一个人再踩一遍）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 🔴 **两侧都返回 1015「服务繁忙」**（手工阶段） | **MinIO 密钥给错** ⇒ 读对象失败 ⇒ `ArticleExceptions.raw(e)`（**无 gRPC status**）⇒ api 层按 UNKNOWN ⇒ 1015。B 侧 rpc 日志原文：`article-rpc handler failed with a raw error (mapped to UNKNOWN): The request signature we calculated does not match the signature you provided.` | 探针**自带**一个 MinIO（探针本地凭据）⇒ 两边用同一套凭据 ⇒ 密钥错会**同时**错在两边 |
| 2 | 🔴 **探针偶发「有差异」**（跑红一次、再跑绿） | B 侧的 `author_name` 补全会去连 `user-rpc`；探针的隔离命名空间里**没有** `user-rpc` ⇒ 每次都失败 ⇒ 触发共享层 `GoStyleGrpcClientBreakerInterceptor` 的**熔断** ⇒ 熔断打开时抛的是 `GoStyleCircuitOpenException`（**普通 RuntimeException，不是 `StatusRuntimeException`**）⇒ B 侧 `resolveAuthorName` 的 catch **接不住** ⇒ 冒泡 ⇒ **列表端点 1015**，而 C 侧恒 200 | 种子用 `author_id='0'` ⇒ 两侧都走「uid ≤ 0 ⇒ 直接回落」⇒ **不发 RPC** ⇒ 无熔断 ⇒ **确定性**（见 §5） |
| 3 | 🔴 **端口块撞上容器发布口 ⇒ Docker 整个失联** | `lsof -ti tcp:<port>` 在那种情况下返回的是 **Docker Desktop 的 backend 进程** ⇒ 一 `kill -9` ⇒ 所有容器的发布端口一起挂 | 端口块**先跟「容器已发布的宿主端口」求交**，冲突就换一块；`probe_free_port` **只杀命令行带探针标记的进程**，是别人的进程就**只报告、不动它** |
| 4 | 🔴 **Kafka 连不上**（容器内） | `Sea-kafka` 的 `PLAINTEXT` listener 广播的是 **compose DNS 名 `kafka:9092`**（`KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://127.0.0.1:49992`）⇒ **只在 compose 网络里解析得到**；而 `EXTERNAL` 那口广播 `127.0.0.1` ⇒ **只对宿主有效** | **两侧必须分别用各自能解析的那个地址**：B 侧（容器里）`kafka:9092`；C 侧（宿主上）`127.0.0.1:49992`。⇒ **B 侧容器必须挂进 compose 网络**（探针自动从 `Sea-nacos` 探测该网络名） |
| 5 | 🔴 **C 侧启动即 fatal：`Port 18893 was already in use`** | 上一轮的 JVM 还在**优雅关闭** ⇒ 新进程 bind 不了 | 起进程**前**先等端口**空**（探针已内置：先 `probe_free_port` 再轮询端口直到不通） |
| 6 | **构建卡住**（探针排在别的 worker 后面） | `service/article/target/` 是**全仓共享**的 ⇒ 别的 worker 跑 surefire 时持有**全局 mvn 锁** | 探针走**同一把全局锁**（`/tmp/rag-mvn-lock.py`）⇒ 不互踩；⇒ 另给 `PROBE_SKIP_BUILD=1`（复用已有 jar）供**变异轮次**快速连跑 |
| 7 | **`-DskipTests` 不够** | 它只跳过**执行**、仍会**编译**测试代码 ⇒ 工作树里测试源码编译不过时整轮失败 | 改用 **`-Dmaven.test.skip=true`**（连测试编译都不做）—— 探针只要**运行期产物** |
| 8 | 🔴 **播种失败却看不出原因** | psql 的 stderr 被 `>/dev/null 2>&1` 吞了 | 播种**重试 5 次** + **打印 psql 原文** + 重试前先 `TRUNCATE`（避免上一次部分插入撞 duplicate key） |
| 9 | **PG 判据用 `pg_isready` 却仍然连不上** | `pg_isready` 与 `psql` 的连接方式**不一定一致**（实测：`pg_isready` 过了，紧接着 `psql` 仍报 `socket "/var/run/postgresql/.s.PGSQL.5432" failed: No such file`） | 就绪判据改成**直接跑一条真查询**（`psql -c 'SELECT 1'`）⇒ 走 psql 真正会走的那条路 |

---

## 9. 与 B 侧探针（`tools/api-full-parity-probe/article/`）的差异

> B 侧探针比的是「**Go 真源栈 ↔ RAG 栈（B 侧 Java）**」；本探针比的是
> 「**RAG 栈（B 侧 Java）↔ RAG-V1 栈（C 侧 Java）**」⇒ **基线、种子、端口、镜像、编排全部不同**。

| 维度 | B 侧探针 | 本探针 | 为什么 |
|---|---|---|---|
| 比对双方 | Go 真源栈 ↔ B 侧 Java 栈 | **B 侧 Java 栈 ↔ C 侧 Java 栈** | 本任务要闭合的是「C 侧 ↔ B 侧」的活体证据 |
| B 侧怎么起 | **源码构建**（`go build` + 配置副本） | **本地现成镜像** `sea/article-api:latest` / `sea/article-rpc:latest` | ⇒ **B 仓一个字节都不改**（连 `target/` 都不写）；镜像构建于 B 仓 `service/article` 最后一次改动（2026-11-01 21:24）之后 |
| C 侧怎么起 | —— | 工作树 `mvn -o -pl service/article -am package` 的 fat jar（**单进程** Tomcat + gRPC） | C 侧是 D1 合并形态 |
| Nacos 命名空间 | **1 个**（Go 与 RAG 各用各的 etcd 键） | **2 个**（B 侧一个 / C 侧一个） | 🔴 **两侧注册的服务名都是 `article-rpc`** ⇒ 共用一个命名空间会**互相串台** ⇒ 假绿 |
| 隔离向量 | V1 命名空间 / V2 etcd 键前缀 / V3 端口租约 / V4 构建锁 | V1 **两个**命名空间 / V2 **两侧各自的 PG** / V3 端口租约 / V4 构建锁 | **etcd 键前缀**那个向量**不再适用**（Go 不在比较面内）；改为「两侧各自的 PG」（Flyway 账本互不干扰） |
| 对象存储 | 宿主共享 MinIO | **探针自带 MinIO**（探针本地凭据 + 独立端口） | 不污染宿主那套；且**密钥**由探针自己掌握 ⇒ 两侧必然同凭据 |
| 基线 | `expected.txt`（Go↔RAG） | `expected.txt`（**B↔C**，重新冻结） | **B 侧探针的基线不能当 C 侧的期望**（比对面不同） |
| 变异 | M1~M4（M4 改**生产码**） | M1~M3（**全都不碰产品代码**） | 本任务硬约束：**不改 C 侧产品代码** ⇒ 变异改**数据**与**基线** |
| 退出码 | `0` 一致 / `1` 有差异 / `2` 环境问题 | **同** | 沿用 |

---

## 10. 清理

- 退出时（`trap cleanup EXIT INT TERM`）：按**端口反查 PID**（`lsof -nP -iTCP:<port> -sTCP:LISTEN -t`）⇒ 杀掉 C 侧 JVM ⇒ 删本轮 5 个容器 ⇒ 注销两个命名空间里的实例 ⇒ 释放端口租约。
- **任务被外部取消**（`SIGKILL`，`trap` 不跑）时：残留的 C 侧 JVM 会被**下一轮**的端口预检按
  **端口反查**发现并踢掉（只杀命令行带探针标记的进程）；残留容器用 `parity-article-*` 前缀识别。
- 临时目录**保留**（本机 `rm -rf` 会被回收站守卫拦下 ⇒ 宁可留着也不让脚本自杀）。
