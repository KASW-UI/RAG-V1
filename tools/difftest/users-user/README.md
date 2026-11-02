# `users-user` 域 · 双侧活体对拍探针（E-5）

> **定位**：B 侧（RAG / Boot 4.0.8）↔ C 侧（RAG-V1 / Boot 3.5.11）**同时起真进程**、**同输入**、**同字节**比对。
> **入口**：`bash tools/difftest/users-user/run.sh`（由 `tools/difftest/run-parity.sh` 派发，`DOMAINS` 已登记 `users-user`）。
> **产出台账**：[`docs/DRIFT/users/verification-users-user-2026-11-02.md`](../../../DRIFT/users/verification-users-user-2026-11-02.md) §十二。

---

## 一、被测两侧

| | B 侧（真源 · 只读） | C 侧（本仓） |
|---|---|---|
| 形态 | **双进程**：`user-api`（HTTP）+ `user-rpc`（gRPC） | **D1 合并单进程** `user-service`（HTTP + gRPC 同进程） |
| 端口（本探针） | API `18301` · RPC `18302` · RPC mgmt `18303` | HTTP `18304` · gRPC `18305` |
| 数据源 | 独立 PG 容器 `e4user-b-pg-$$` / 库 `diff_b` | 独立 PG 容器 `e4user-c-pg-$$` / 库 `diff_c` |
| Nacos 分组 | `E4UB` | `E4UC` |
| 产物 | `$B_REPO/service/users/user/*/target/*.jar` | `service/users/user/target/user-service-1.0-SNAPSHOT.jar` |

> ⚠️ **Nacos 必须分组隔离**：两侧注册的 gRPC 服务**同名**（`user-rpc`）⇒ 同组时
> B 侧 user-api 的 `discovery:///user-rpc` **可能拨到 C 侧的 user-rpc** ⇒ 对拍对象就错了。
> ⚠️ **只走宿主发布口**（Nacos `49848` / Redis `49380`）：Docker Desktop(macOS) 的宿主**不可达容器 IP**。

## 二、判据（**两档**，不混）

| 档 | 归一化内容 | 用途 |
|---|---|---|
| **字节级** | 雪花 uid · history_id · 各自自签 JWT · 时间戳 · 密码哈希 | 抓**全部**字节差（含表达层） |
| **结构级** | 上面 ＋ **JSON 键序** | 判**行为**等价（JSON 对象是无序集合，键序不是契约） |

> ⚠️ **为什么必须两档**：user 域响应带**两侧必然不同**的量（雪花 uid、各自自签的 JWT）⇒ 必须归一化；
> 而 **JSON 键序**是**表达层**差异 ⇒ 单列一档，**不混进行为判定、也不隐藏**。
> ⚠️ **键序为什么单列**：B（Boot 4 / Jackson 3）的 mapper 默认开 `SORT_PROPERTIES_ALPHABETICALLY`
> ⇒ 输出**字母序**；C（Boot 3.5 / Jackson 2.19.4）默认关 ⇒ 输出**声明序**。
> B 侧自己的 javadoc 记录了这条机制（`hot-api/HotArticleCard.java` 的「字段顺序必须显式钉住」段）。
> ⇒ 该差异是**共享层**问题（10 个域同受影响）⇒ 归收口域，见台账 §十二.4。

## 三、用例矩阵（HTTP 20 例 · DB 最终态）

| 组 | 用例 | 覆盖 |
|---|---|---|
| 参数校验 | `register {}` · `register 缺 password` · `login {}` · `login 缺 password` | 400 的**状态码 + 结构 + 报哪个字段** |
| 注册 | `register 正常` · `register 重名` | 信封形状 · 业务码 200 / 1001 |
| 登录 | `login 用户不存在` · `login 密码错` | 业务码 1002 |
| 鉴权 | `get 无 token` · `get 坏签名` · `get 合法 token + 不存在 uid` · `get 合法 token + 存在 uid` · `avatar/history` | 401 + 1006 · 200 + 1003 形态 · 信封字段 |
| 路由 | `未知路径 /user/v1 内` · `未知路径 /user/v1 外` · `GET 打 POST 端点` | **B 侧继承差异**：401 / 500+5001 / 405 |
| 写路径 | `update 改用户名` · `update 后 get` | 写入 + 回读 |
| 登出 | `logout` · `logout 后再用 token` | 黑名单生效（401 + 1006） |
| DB | `users_user` 最终态 + 表清单 | §6.4 状态和副作用 |

## 四、本轮实测结论（2026-11-03）

| 口径 | 结果 |
|---|---|
| **结构级** | **16/20 一致** · 4 例不一致 = **全是 400 文案形态**（go-zero `field "x" is not set` ↔ Bean Validation `x 不能为null`）⇒ **已登记 D-01 / DEV-USER-002** |
| **字节级** | **12/20 一致** · 8 例不一致 = 4 文案 ＋ **4 JSON 键序**（⇒ 共享层，收口域） |
| DB 最终态 | ✅ `users_user` 行逐字段一致 · ✅ 表清单一致（4 张） |
| B 侧只读 | ✅ 收尾断言 B 仓 `service/` 干净 |

⇒ **本探针抓出并修掉 2 个真缺陷**（详见台账 §十二.3）：
① `grpc-inprocess` 声明成 test scope ⇒ **C 侧 fat jar 起不来**（39 例测试全绿却没暴露）；
② 400 的「报哪个字段」**不确定**（Hibernate Validator 违规集合是 `HashSet`）⇒ 同一输入 6 次出 3 种文案。

## 五、已知未覆盖（本探针**不**声称闭合）

- ⚠️ **gRPC wire 逐字节对拍**（9 个方法）**未做** ⇒ 台账里该项仍标 `NOT_VERIFIED`
  （follow 域的 `tools/difftest/follow/grpc-wire/` 有先例可循）；
- ⚠️ **B 侧 Go 真源**未活体（本机无 `Sea-etcd`）⇒ 第三方锚缺失 ⇒ 本探针是**双侧**（C ↔ B），
  不是 follow 域那种**三方**（C ↔ B ↔ Go 冻结字节）；
- ⚠️ **并发 / 超时 / 外部依赖失败 / 恢复**四类场景轮**未做**（follow 域已有 6 类 ⇒ 可借形）。

## 六、跑法与排障

```bash
bash tools/difftest/users-user/run.sh            # 全跑
B_REPO=/path/to/RAG bash tools/difftest/users-user/run.sh
KEEP_WORK=1 bash tools/difftest/users-user/run.sh # 保留临时目录与日志
```

| 症状 | 原因 | 处置 |
|---|---|---|
| `端口 183xx 被占用` | 上一轮残留 | 换端口（env 覆盖）或杀残留进程 |
| `C 侧 jar 缺 grpc-inprocess` | pom 把 `grpc-inprocess` 设成 test scope | 去掉 `<scope>test</scope>`（**B 侧运行时类路径带这个类**） |
| `B user-rpc 起不来` | B 侧 `USER_JWT_SECRET` / `POSTGRES_PASSWORD` 必填（fail-closed） | 探针已自带 |
| `取 token 失败` | 两端口没起 / 用户名冲突 | 看 `${WORK}/*.log` |

> 退出码：`0` = 结构级一致 · `1` = 有结构级不一致 · `2` = 环境问题。
