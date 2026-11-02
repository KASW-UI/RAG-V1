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

## 三、用例矩阵（HTTP 20 例 · **gRPC wire 18 例** · DB 最终态）

### 3.1 HTTP 20 例

| 组 | 用例 | 覆盖 |
|---|---|---|
| 参数校验 | `register {}` · `register 缺 password` · `login {}` · `login 缺 password` | 400 的**状态码 + 结构 + 报哪个字段** |
| 注册 | `register 正常` · `register 重名` | 信封形状 · 业务码 200 / 1001 |
| 登录 | `login 用户不存在` · `login 密码错` | 业务码 1002 |
| 鉴权 | `get 无 token` · `get 坏签名` · `get 合法 token + 不存在 uid` · `get 合法 token + 存在 uid` · `avatar/history` | 401 + 1006 · 200 + 1003 形态 · 信封字段 |
| 路由 | `未知路径 /user/v1 内` · `未知路径 /user/v1 外` · `GET 打 POST 端点` | **B 侧继承差异**：401 / 500+5001 / 405 |
| 写路径 | `update 改用户名` · `update 后 get` | 写入 + 回读 |
| 登出 | `logout` · `logout 后再用 token` | 黑名单生效（401 + 1006） |
| DB | `users_user` + `users_user_avatar_history` 最终态 + 表清单 | §6.4 状态和副作用 |

### 3.2 gRPC wire 18 例（**9 个方法全覆盖**）

> 探针：`grpc-wire/UserWireProbe.java`（**同一份 class 打两侧**）· 比对器：`wire_compare.py`。

| 组 | 用例 | 覆盖 |
|---|---|---|
| 注册 | `wire_register` · `wire_register_dup` | 成功（返回 uid）· `ALREADY_EXISTS` |
| 读用户 | `wire_get_user` · `wire_get_user_missing` | 命中（UserInfo **含** `status`）· `NOT_FOUND` |
| 登录 | `wire_login_ok` · `wire_login_wrong_password` · `wire_login_user_missing` | `status=0/1/1`（**`status=0` 是 proto3 默认值 ⇒ wire 上不出现**） |
| 更新 | `wire_update_user_same_name` · `wire_update_user_rename` | **D-004 不排除自己**（同名 ⇒ `ALREADY_EXISTS`）· **成功路径**（UserInfo **不含** `status`） |
| 登出 | `wire_logout_empty_token` · `wire_logout` | `INVALID_ARGUMENT`「Token不能为空」· **空消息（0 字节）** |
| 头像 | `wire_upload_avatar_1/2` · `wire_get_avatar_history` · `wire_select_avatar` · `wire_select_avatar_empty_id` | 回显 history · **repeated 列表（顺序参与比较）** · `INVALID_ARGUMENT` |
| 删用户 | `wire_delete_user` · `wire_delete_user_missing` | `success=true` · `NOT_FOUND` |

**判据**（`wire_compare.py`）：**只屏蔽「值」，不屏蔽 presence** ⇒
`uid`→`<UID>` · `history_id`→`<HISTORY_ID>` · `create_time`→`<TIME>`
⇒ ⇒ 「B 有这个字段、C 没有」**照样红** ⇒ ⇒ **presence 语义**是被抓的。
⇒ 另有 **7 条同侧自洽断言**（`SELFCHECK`）⇒ 语义靠**每侧自己的因果链自洽**判定，
不靠「两侧 id 恰好一样」。

### 3.3 场景轮（**8 阶段**）—— 并发 / 超时 / 外部依赖失败 / 恢复 ×2 / gRPC deadline / 取消传播

> 探针：`scenarios.py`（**第三轮新增**）⇒ 由 `run.sh` 在**最后**调用。
> ⚠️ **必须排在最后**：它会 **pause / stop 两侧的 PG** 并**改库** ⇒ 前几轮的期望值不受影响。

🔴 **判据 = 两侧对称**（不是「系统恢复」）：两侧**同样**失败 ⇒ 那是**系统级现象** ⇒
**不是 users-user 域的漂移**；但必须**如实报出** ⇒ 绝不让「两边都坏」变成一条无声的假绿
⇒ 所以「恢复轮失败」**不置 FAILED**，但进 `SCEN_WARN` 并在收尾摘要里显眼打出。

🔴 **故障注入必须两侧完全对称**：两侧**各自**的 PG 容器（`e4user-{b,c}-pg-$$`）⇒
挂起 / 停的**只有自己那侧** ⇒ 两侧走**完全同形**的路径 ⇒ 比出来的差异才是**行为差异**。
⚠️ **绝不去碰共享栈的 `Sea-postgres`**（别的 lane 在用）。

| 阶段 | 判据 | 结果（2026-11-03 run6） |
|---|---|---|
| **超时**（PG 冻结 ⇒ HTTP 读路径挂住） | HTTP 出口形态 | ❌ **B = `200 + 信封 1015` @ 2.01s** ↔ **C = `503 + 裸文本 "Request Timeout"` @ 3.00s** ⇒ DRIFT-USER-PARITY-005 |
| **gRPC deadline**（PG 仍冻结） | status | ✅ 两侧 `ERROR_DEADLINE_EXCEEDED`（B @ 2088ms · C @ 2096ms）⇒ **服务端 2s 预算两侧对称** |
| 同上 | description | 🔶 文案形态不同：B `context deadline exceeded` ↔ C `rpc deadline exceeded` ⇒ 客户端可见（C 侧 `common/.../ObservabilityAutoConfiguration.java:299` 硬编码） |
| **取消传播**（客户端 1s 取消） | status | ✅ 两侧 `ERROR_CANCELLED`（`cancel_issued=true`） |
| 同上 | **副作用** | ❌ **B = 0 行** ↔ **C = 1 行** ⇒ DRIFT-USER-PARITY-006 |
| **判别性变体**（取消推迟到 **5s**，必定晚于服务端 2s 预算） | 副作用 | ❌ **同样 B=0 · C=1** ⇒ ⇒ **差异与「客户端取消」无关** ⇒ 归因＝服务端预算到点后有没有把在途写回滚 |
| **恢复**（PG 放行） | 恢复 + 响应 | ✅ 两侧**连续 3 轮**回 200 ⇒ 稳定态 |
| **外部依赖失败**（PG 停 ⇒ 连接被拒） | HTTP 出口 | ✅ **两侧都是 `200 + 信封 1015`**（3/3 轮）⇒ 共享层修复在 users-user 域同样生效 |
| **恢复**（PG 重新 start） | 恢复 + 响应 | ✅ 两侧**连续 3 轮**回 200 |
| **并发**（两侧各 8 路并发 register 同一用户名） | 响应集合 + 行数 | ✅ 两侧响应集合**都是 `{200/200, 200/5001}`** · 各自**恰好 1 行** |

⇒ **探针机器摘要**：`—— 场景轮摘要：❌ 真差异 3 ｜ ⚠️ 对称异常/已知现象 2 ——`
⇒ ⇒ **3 条真差异 = 2 个根因**（第 3 条是第 2 条的**判别性变体** ⇒ 用来分离成因，不是新缺陷）
⇒ ⇒ ⚠️ 2 条 = 恢复轮的 **JSON 键序**（PARITY-003，已登记）
⇒ ⇒ 两条根因均已归因：一条＝签字偏离 **DEV-USER-010** · 一条＝**共享层**跨域。

## 四、本轮实测结论（2026-11-03 · 第三轮加场景轮）

| 口径 | 结果 |
|---|---|
| **HTTP 结构级** | **16/20 一致** · 4 例不一致 = **全是 400 文案形态**（go-zero `field "x" is not set` ↔ Bean Validation `x 不能为null`）⇒ **已登记 D-01 / DEV-USER-002** |
| **HTTP 字节级** | **12/20 一致** · 8 例不一致 = 4 文案 ＋ **4 JSON 键序**（⇒ 共享层，收口域） |
| **gRPC wire** | **18/18 一致 ⇒ 真差异 0**（探针 **EXIT=0**）· 7 条自洽断言全绿 |
| **DB 最终态** | ✅ `users_user` 行逐字段一致 · ✅ `users_user_avatar_history` 一致（2 行 / 1 current / 内容序列一致）· ✅ 表清单一致（4 张） |
| **场景轮** | **8 阶段** ⇒ 探针摘要「❌ 真差异 **3** ｜ ⚠️ 已知现象 **2**」⇒ **3 条 = 2 个根因**（均已归因） |
| B 侧只读 | ✅ 收尾断言 B 仓 `service/` 干净 |

⇒ **本探针抓出并修掉 2 个真缺陷**（详见台账 §十二.3）：
① `grpc-inprocess` 声明成 test scope ⇒ **C 侧 fat jar 起不来**（39 例测试全绿却没暴露）；
② 400 的「报哪个字段」**不确定**（Hibernate Validator 违规集合是 `HashSet`）⇒ 同一输入 6 次出 3 种文案。

⇒ **gRPC wire 维度的变异证据**（证明判据非空洞）：
在 C 侧 `UpdateUserResp` 的 `UserInfo` 上多设一个 `status` ⇒ wire 上多出 `f6=0`
⇒ ⇒ **HTTP 维度真差异仍是 0**（该字段只在 gRPC 响应里），
**只有 gRPC wire 的 `wire_update_user_rename` 变红** ⇒ **EXIT=1**
⇒ ⇒ 还原（**文件备份 + `cp` 回来**，不依赖 git）⇒ 重建 ⇒ 同条件复跑 ⇒ **EXIT=0**。

⇒ **第三轮修掉的 4 处探针自身缺陷**（**不是被测对象** ⇒ 详见坑台账 P-USER-15~18）：
① 调度线程非 daemon ⇒ JVM 不退出 ⇒ 脚本挂住；
② **陈旧 jar 让读数变假**（共享层修复已进 main，jar 还是旧的 ⇒ **差点**把修复前的 503 登记成真差异）；
③ 「端口已监听」≠「服务已注册」⇒ B 侧 user-api 偶发 `Can't connect to channel user-rpc`；
④ 任务被外部取消 ⇒ `trap` 不执行 ⇒ 端口残留 ⇒ 下一轮「端口被占用」。

## 五、已知未覆盖（本探针**不**声称闭合）

- ⚠️ **B 侧 Go 真源**未活体（本机无 `Sea-etcd`）⇒ 第三方锚缺失 ⇒ 本探针是**双侧**（C ↔ B），
  不是 follow 域那种**三方**（C ↔ B ↔ Go 冻结字节）；
- ⚠️ **「DB 完全不可响应」档的 HTTP 出口形态**（B `200+1015@2s` ↔ C `503@3s`）⇒ 已登记为签字偏离 **DEV-USER-010**；
- ⚠️ **取消后在途写是否回滚**（B=0 行 ↔ C=1 行）⇒ **落点在共享层** ⇒ 本 lane 无写权面 ⇒ 归收口域；
  ⇒ 📌 **根因已定位到一行**（代码级归因 · **待运行验证**）：**C 侧 gRPC 拦截器缺 `onHalfClose` 里的
  `DeadlineState.bindAbsoluteNanos(...)`**（B 侧 `RpcInterceptor.java:516-517` 有；C 侧
  `ObservabilityAutoConfiguration.java:253-262` 的 listener **只重写了 `onCancel()`**）
  ⇒ ⇒ handler 线程上 `DeadlineState` 恒空 ⇒ ⇒ `DeadlineQueryTimeoutInjector` 不注入 `setQueryTimeout`
  ⇒ ⇒ **不发 CancelRequest** ⇒ ⇒ 在途写不被取消 ⇒ 提交 ⇒ 1 行（B 侧有绑定 ⇒ CancelRequest ⇒ 回滚 ⇒ 0 行）。
- ⚠️ **gRPC deadline 的 description 文案形态**不同 ⇒ 客户端可见 ⇒ 落点＝共享层 gRPC 拦截器 ⇒ 归收口域。

## 六、跑法与排障

```bash
bash tools/difftest/users-user/run.sh            # 全跑
B_REPO=/path/to/RAG bash tools/difftest/users-user/run.sh
KEEP_WORK=1 bash tools/difftest/users-user/run.sh # 保留临时目录与日志
```

| 症状 | 原因 | 处置 |
|---|---|---|
| `端口 183xx 被占用` | 上一轮残留（**任务被外部取消 ⇒ `trap` 没跑** ⇒ 进程活下来） | `lsof -nP -iTCP:<port> -sTCP:LISTEN -t` 找 PID 杀掉；探针已按端口追 PID，下一轮能自动清 |
| `C 侧 jar 缺 grpc-inprocess` | pom 把 `grpc-inprocess` 设成 test scope | 去掉 `<scope>test</scope>`（**B 侧运行时类路径带这个类**） |
| `B user-rpc 起不来` | B 侧 `USER_JWT_SECRET` / `POSTGRES_PASSWORD` 必填（fail-closed） | 探针已自带 |
| `Can't connect to channel user-rpc` | **user-rpc 端口已开但还没注册进 Nacos** ⇒ devh 的 gRPC client **启动即连** | 探针已加 `nacos_assert_instance` 等注册落地；**不要**先起 user-api |
| `取 token 失败` | 两端口没起 / 用户名冲突 | 看 `${WORK}/*.log` |
| 读数与代码**对不上**（比如改了超时却还是旧行为） | **陈旧 jar**（jar 存在但比源码旧） | 探针已按**源文件 mtime** 判陈旧并重建；手动清 `target/` 亦可 |

> 退出码：`0` = 结构级一致 · `1` = 有结构级不一致 · `2` = 环境问题。
> ⚠️ **场景轮**的失败也算 `1`；但「两侧同样失败」（系统级现象）只进 `SCEN_WARN`，**不置 1** ⇒ ⇒
> **「退出码 0」不等于「场景轮全绿」** ⇒ 收尾摘要里的 `SCEN_WARN` 段要看。
