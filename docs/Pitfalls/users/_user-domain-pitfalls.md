# `users-user` 子域的坑（本 lane 产出）

> **登记时间**：2026-11-02
> **产出方**：`align/users-user` 工作树（`/Users/pr/Documents/GitHub/RAG-V1.worktrees/users-user`）
> **适用范围**：`service/users/user/`（artifactId `user-service`）
> **归属**：`docs/Pitfalls/users/` = **本域写权面**；`docs/Pitfalls/_INDEX.md` **不归本 lane** ⇒ 需收口域统一合并
> **配套台账**：[`docs/DRIFT/users/verification-users-user-2026-11-02.md`](../../DRIFT/users/verification-users-user-2026-11-02.md)（§11.9 有本文件的逐条摘要）
>
> 📌 **通用方法论不在这里重复**：变异测试的方法论见 [`P-12`](../_cross/P-12-变异没变红等于那条断言没覆盖到.md)；
> 守门脚本的 `ROOT` 陷阱见 [`P-08`](../_cross/P-08-落地证据未经机器复算.md)。

---

## P-USER-01 · 「只有注释的 YAML 键」解析成**空串**而不是 `null` ⇒ 那是全仓的启动雷

**形态**：10 个域的 `application.yml` 都写 `grpc.client:` + 一行注释 ⇒ YAML 解析出来是**空串**（不是 `null`）
⇒ relaxed binding 拿 `String` 转 `Map` ⇒ `ConverterNotFoundException` ⇒ **Spring 上下文起不来**。

**实测代价**：本域是**第一个真启动的域** ⇒ 踩到时别的域还没启动 ⇒ 现场一度误判成「本域配置写错了」。
⇒ 修法：本域写 `grpc.client: {}`（**空 map 必须是 `{}`**，不能是空键）。
⇒ ⚠️ 但 `{}` 只解决**本域** ⇒ 其余 9 个域仍是空键 ⇒ 那些域一启动就挂 ⇒ **登记为跨域项 DRIFT-USER-IMPL-004**（归收口域）。

**判定式**：
1. ⇒ YAML 的「只有注释的键」**不是 `null`，是空串** ⇒ 用 `key:` 表达「没有值」和「空 map」是**两件不同的事**；
2. ⇒ relaxed binding 对 `Map` 字段要求**真 map** ⇒ 要么删键、要么写 `{}`；
3. ⇒ **第一个真启动的域会替全仓踩到它** ⇒ ⇒ 踩到时**必须顺手登记成跨域项**，别只修自己那份。

---

## P-USER-02 · gRPC **in-process server 的名字是 JVM 全局注册**的

**形态**：`grpc.server.in-process-name` 注册进一张**进程级**的全局表 ⇒ 多个 Spring 上下文用**同名** ⇒
`IOException: name already registered` ⇒ **后起的上下文全挂**（且报错与真实原因毫无关联，容易查错方向）。

**判定式**：
1. ⇒ 需要真 gRPC 的测试类**各自指定唯一名字**；
2. ⇒ 其余上下文用 `grpc.server.port=-1` **不起 server** ⇒ 「一个测试类起 in-process」是安全的，
   「多个测试类共享一份配置起 in-process」**不安全**；
3. ⇒ ⇒ 报错栈里出现 `name already registered` ⇒ **先怀疑重名，别怀疑端口**。

---

## P-USER-03 · `-pl <module>` 单模块跑测会解析不到兄弟模块

**形态**：`mvn -pl service/users/user test` ⇒ `Could not find artifact com.rag:proto`
⇒ 本仓的模块依赖**只在 reactor 里解析**。

**判定式**：
1. ⇒ 必须带 **`-am`**；
2. ⇒ 要「只跑本域的测试」就配 `-Dtest=<pattern> -Dsurefire.failIfNoSpecifiedTests=false`；
3. ⇒ ⚠️ **`-DfailIfNoTests=false` 不是 surefire 3.x 的参数名** ⇒ 用错会让 `common` 模块**直接 FAIL**（报错与你的意图完全无关）。

---

## P-USER-04 · 「测试写错了」与「实现写错了」长得一模一样

**实测代价**：本轮 3 次红里，**2 次是期望值按 Go 侧直觉写**（未知路径该 404），
而 **B 侧 Java 的真实行为**是 `500`（兜底 `handleAny` 吃掉 404）与 `401`（`/user/v1/**` 前缀注册）。

**判定式**：
1. ⇒ 红了先问「**B 侧 Java 真的会那样吗**」，再问「实现错在哪」；
2. ⇒ ⇒ **读 B 侧同形代码**比推理快（`WebMvcConfig` 的前缀注册 / `handleAny` 的兜底）；
3. ⇒ ⇒ 顺带把「**B 侧 Go 与 B 侧 Java 自身不一致**」记成**继承项**，别当 C 侧漂移去「修」（D16）。

---

## P-USER-05 · 守门脚本的 `ROOT` 有三种形态，只有一种是对的

**形态**：`gen-file-tree.py` 与 `check-mapping-coverage.py` = **硬编码主工作区**；
`sync-mapping-index.py` = `__file__` 相对（**看着是对的**）⇒ **但它 import 的那个模块里是硬编码** ⇒ ⇒ **间接**量主工作区。

**判定式**：
1. ⇒ 在 worktree 里跑任何仓内脚本前，先确认它**看的是哪棵树**：
   - `grep -n "ROOT" tools/<x>.py` ⇒ 直接硬编码 ⇒ 改指向 worktree（**用 `/tmp` 下的副本，不改仓内脚本**）；
   - `__file__` 相对 ⇒ **再查它 import 的模块里有没有硬编码**（本轮就踩了这个 ⇒ 「看着对」的也会中招）；
2. ⇒ ⇒ **绿灯也要做同条件对照**（本轮：基线纯净副本 users C 列 **106/106 且自报一致 ⇒ EXIT=0**；
   改动后 **109/109 但自报仍 106 ⇒ EXIT=1** ⇒ ⇒ **证明这处漂移是本轮引入的**）。

---

## P-USER-06 · `@Transactional` 挂在 **MyBatis-Plus mapper 接口的 `default` 方法**上 —— 本仓最易静默失效的形态

**形态**：`@Transactional` 写在 mapper 接口的 `default` 方法上（本域 `UserAvatarHistoryMapper` 的
`createAvatarHistory` / `selectAvatarHistory`）。mapper 是 **MyBatis 的 JDK 代理** ⇒ 注解能不能生效取决于三件事：

1. Spring 的 auto-proxy 是否包住了 **`FactoryBean` 产出的** mapper 代理（`MapperFactoryBean.getObject()` 的产物**会**经过 BPP）；
2. 事务 advisor 是否认得**接口 default 方法上**的注解（`AopUtils.getMostSpecificMethod` → `targetClass.getMethod(...)`）；
3. MyBatis 的 `SqlSessionTemplate` 是否参与进已开启的 Spring 事务（`TransactionSynchronizationManager` 上绑的是 `SqlSessionHolder`）。

**三者任一不成立 ⇒ 注解就是装饰品，且零报错、零告警。**

**判定式（本轮实测已验证）**：
1. ⇒ **判据 = 真 DB 约束冲突**：拿 `history_id` 的 `UNIQUE` 让 W3 的 INSERT **真失败**
   ⇒ 断言 **W2 的 UPDATE 不留痕**（旧 history 仍是 `current`）⇒ 这是**真实 DB 错误**（`DuplicateKeyException`），
   回滚与否只能由真实事务语义决定；
2. ⇒ **必须配「同序列成功路径」作非空洞对照** ⇒ 否则「回滚了」可能只是「压根没写」；
3. ⇒ **必须做变异测试**（摘掉注解 ⇒ 判据变红）⇒ 方法论见 [`P-12`](../_cross/P-12-变异没变红等于那条断言没覆盖到.md)；
4. ⇒ ⚠️ **Mockito 桩掉 mapper 方法不算数** —— 桩了就连**真实 SQL 都不发** ⇒ 证不了回滚；
5. ⇒ ⇒ 落点：`docs/DRIFT/users/verification-users-user-2026-11-02.md` §11.7（变异实测）与 §11.8（状态判定）。

---

## P-USER-07 · 默认 `mvn clean verify` 是 **fail-fast** ⇒ 失败面被截断 ⇒ 跨树对照会得出**错误结论**

**形态**：默认构建在**第一个**失败模块就停 ⇒ 后面全部 `SKIPPED`
⇒ 拿它做「同条件对照」会看到「只有 `article-service` 一个域红」⇒ **实际有 9 个域红**。

**判定式**：
1. ⇒ ⇒ 跨树对照**必须加 `-fae`（fail-at-end）** 才能拿到**完整**失败模块集合；
2. ⇒ ⇒ **失败模块集合相同** ⇒ 才是「基线自带」的证据；**集合差一条** ⇒ 那一条正是本轮的贡献；
   （本轮：基线 10 个域全红 → 当前分支 9 个域红 ⇒ 差额 = `user-service` 由红转绿）；
3. ⇒ ⇒ 根因要**代码级取证**（本轮：逐个 `Application` 查 `static void main`），**不能只从报错反推**。

---

## P-USER-08 · **测试类路径 ≠ 生产 jar 类路径** ⇒ 「`mvn test` 全绿」证不了「fat jar 起得来」

**形态**：某依赖被声明成 **`<scope>test</scope>`** ⇒ **只在测试类路径上有**，**不进** `repackage` 出来的 fat jar
⇒ ⇒ 测试全绿（测试类路径带这个类），**生产 jar 起不来**，且**零告警**。

**实测代价（RAG-V1 · users-user · 2026-11-03 双侧活体对拍）**：
本域把 `io.grpc:grpc-inprocess` 设成 test scope ⇒ devh `grpc-client-spring-boot-starter` 3.1.0 的
`GrpcClientAutoConfiguration#shadedNettyGrpcChannelFactory`（`:160`）在**运行期**要这个类
⇒ **fat jar 启动即挂**：

```
NoClassDefFoundError: io/grpc/inprocess/InProcessChannelBuilder
  ⇒ 上下文 refresh 失败 ⇒ 进程起不来
```

⇒ **39 例真触发测试全绿、`skipped=0`、守门 EXIT=0** ⇒ **零信号**。
⇒ 是**双侧活体对拍**（要真起一个 fat jar）才暴露的。

**判定式**：
1. ⇒ **凡「启动期/运行期要某个类」的机制**（auto-config 的 `@ConditionalOnClass` 之外的**硬引用**、
   SPI、反射加载），都要问一句「**这个类在生产 jar 里在不在**」；
2. ⇒ 判据 = **列 jar 内容**（`unzip -l` / `zipfile`）**对比** pom 的 scope，**不是**「测试跑过了」；
3. ⇒ ⇒ **凡本域的依赖有 test scope 且可能被运行期引用** ⇒ 在**启动脚本/探针里加一条 jar 内容自检**
   （本域落在 `tools/difftest/users-user/run.sh`：缺了直接 `die`，**不是起不来才发现**）；
4. ⇒ ⇒ **跨域提醒**：全仓另有 **7 个域同型**（`article`/`comment`/`favorite`/`hot`/`like`/`message`/`task`）
   ⇒ 判据 = `mvn -o -pl service/<域> dependency:list -DincludeScope=runtime` 看 `grpc-inprocess` 在不在
   ⇒ ⚠️ **它们当前被「Application 无 `main`」挡在前面** ⇒ **补 `main` 的那一刻才会暴露** ⇒ **先修这条**。

**⇒ 同族**：与「『跑绿』≠『本来就绿』」「『Bean 存在』≠『机制生效』」同源 —— 本条补的是
「**『测试跑过』≠『生产能起』**」这一面：**测试与产物是两份不同的类路径**。

---

## P-USER-09 · Bean Validation 的**违规集合是 `HashSet`** ⇒ `findFirst()` **不确定** ⇒ 「取第一条违规」必须自己定序

**形态**：`e.getBindingResult().getFieldErrors().stream().findFirst()`
⇒ Hibernate Validator 把所有违规装进 **`HashSet`** ⇒ `getFieldErrors()` 的遍历顺序**取决于 hash**
⇒ ⇒ **同一输入、同一次启动，多次请求可能报出不同的字段**。

**实测代价（RAG-V1 · users-user）**：`POST /user/v1/register` 带 `{}` **连打 6 次**
⇒ 实得 **3 种不同文案**（`password 不能为null` ×4 / `username 不能为null` ×1 / `email 不能为null` ×1）。
⇒ B 侧同样输入连打 6 次**全部相同**（`field "username" is not set`）
⇒ ⇒ **C 侧的 400 响应体不是确定性输出** ⇒ 客户端按文案解析会不稳、回归断言也钉不住。

**判定式**：
1. ⇒ ⇒ **凡「取第一条违规 / 取第一条错误」** ⇒ **必须自己定序**，不能依赖容器的迭代序
   （`HashSet` / `HashMap` 的迭代序**跨运行都可能变**）；
2. ⇒ ⇒ 定序要**对齐 B 侧**的判定序：B 的 `GoStyleRequired` 是**按 DTO 字段声明序逐个判、遇第一个缺失就抛**
   ⇒ C 侧按 **record 声明序**排即可（本域各 record 的声明序**恰好与 B 的判定序逐个一致**）；
3. ⇒ ⇒ **判据**：连打 N 次断言**逐次相同** ＋ 断言**报的是声明序第一个字段**
   （本域：`UserApiHttpContractTest#validationViolationSelectionIsDeterministicAndMatchesB`，连打 8 次）；
4. ⇒ ⚠️ **注意与「文案形态」分开**：字段**选谁**是行为（要对齐 B），
   **文案长什么样**是表达层（可以是已登记的降级）⇒ **两者要分别判、分别登记**。
