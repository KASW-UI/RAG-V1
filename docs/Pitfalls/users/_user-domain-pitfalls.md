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

⇒ **40 例真触发测试全绿、`skipped=0`、守门 EXIT=0** ⇒ **零信号**。
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

---

## P-USER-10 · 对拍探针用 Python 打 HTTP ⇒ **1xx 中间响应**会被当成最终响应（`urllib` 只跳 100）

**形态**：B 侧的 `HttpMiddleware` 有「**1xx 探针行数预算**」机制 ⇒ 会**主动发 1xx 探针字节**
（首次请求实测收到 `103`）⇒ ⇒ Python `urllib` **只跳过 `100 CONTINUE`** ⇒ 把 `103` **当成最终响应**
（空 body + 无 Content-Type）⇒ ⇒ **误判成「两侧不一致」**。

**判定式**：
1. ⇒ ⇒ **「curl 看着是 200、探针看着是 103」并不矛盾** ⇒ 两者都对的 ⇒ 差异在**客户端**不在服务端；
2. ⇒ ⇒ `http.client` **也不行**：读掉 1xx 后 `close()` 会**关掉 socket** ⇒ 下一次 `getresponse()` 失败
   ⇒ ⇒ **改用 `curl`**（天然跳过 1xx，等最终响应）⇒ 且与 follow 域探针同款
   （`curl -s -D hdr -o body -w '%{http_code}|%{content_type}'`）；
3. ⇒ ⇒ **凡用脚本打 HTTP 对拍** ⇒ 先确认服务端有没有 1xx 探针 / `100-continue` / `103 Early Hints` 机制。

---

## P-USER-11 · bash 里 `( cd … && java … ) &` 的 `$!` 是**子 shell 的 PID** ⇒ cleanup 杀不到 java ⇒ 端口残留

**形态**：探针用 `( cd "${B_REPO}" && ENV=… java -jar … ) > log 2>&1 &` 启动两侧 ⇒ `$!` 拿到的是
**子 shell 的 PID**，而 **java 是子 shell 的子进程** ⇒ ⇒ `kill -9 "${PIDS[@]}"` **只杀了子 shell**
⇒ ⇒ **java 活下来** ⇒ ⇒ **端口残留** ⇒ 下一轮跑探针直接 `端口被占用` 退出。

**判定式**：
1. ⇒ ⇒ 子 shell 里用 **`exec java …`** ⇒ 让 java **取代**子 shell ⇒ `$!` 就是 java 的 PID ⇒ cleanup 有效；
2. ⇒ ⇒ 探针收尾后**必须验证端口真的释放了**（`lsof -iTCP:<port> -sTCP:LISTEN`）⇒
   **「探针退出了」不等于「进程退了」**；
3. ⇒ ⇒ 端口段要**独占**且**每轮固定** ⇒ 残留会直接挡住下一轮 ⇒ 报「端口被占用」时先查残留而不是换端口。

---

## P-USER-12 · 拿**屏蔽后的规范化文本**去断言**原始值** ⇒ 断言恒 false ⇒ 两侧同时挂却不是行为差异

**形态**：wire 对拍要把「两侧必然不同的值」（雪花 uid / 服务端生成的历史 id / 写入时刻）**屏蔽掉**
⇒ 规范化文本里它们变成 `<UID>` / `<HISTORY_ID>` / `<TIME>`。
⇒ ⇒ 若**自洽断言**写成「规范化文本里**包含**原始 id」⇒ ⇒ **恒 false**
⇒ ⇒ 第一轮实跑：B 侧 FAIL、C 侧 FAIL ⇒ **看起来像「两侧都不对」**
⇒ ⇒ **实际上两侧行为完全一致** ⇒ 是**断言写错了**（工具错），不是被测对象错。

**判定式**：
1. ⇒ ⇒ **两侧同时 FAIL ⇒ 先怀疑断言，不是先怀疑被测对象** —— 两侧同时坏在同一个点上，
   最可能的解释是「这条断言在两侧都不成立」，而**两侧一致地不成立**恰恰说明**行为一致**；
2. ⇒ ⇒ **断言要比「原始字节」**（`rawBytesField` / `rawStringField` 这类直接读原始 payload 的取值），
   **不能比规范化文本** ⇒ ⇒ 屏蔽规则只作用于**输出给比对器的那一份**，**不作用于断言用的那一份**；
3. ⇒ ⇒ 屏蔽规则要**按字段定位**（`UserInfo.uid` / `AvatarHistoryItem.id` …），
   **不能按「值长得像不像 id」来猜** ⇒ 否则「屏蔽」会连**别处的同名字段**一起吃掉；
4. ⇒ ⇒ **屏蔽只该屏蔽「值」，绝不该屏蔽「presence」** ——
   「B 有这个字段、C 没有」必须**照样红**（`optional` 的 presence 语义就是行为）。

---

## P-USER-13 · 用「有 schema 的反序列化」判 **wire 等价** ⇒ **契约漂移被解码器吃掉** ⇒ 假绿

**形态**：两侧 proto 若字段号不一致，用**各自的生成类**解码对方的响应 ⇒
解码器把「对方多出来的字段」当 **unknown 丢掉** ⇒ ⇒ 两边都「解得出来」⇒ **判成一致** ⇒ **假绿**。
⇒ ⚠️ 这是**「proto 复制忠实 ≠ 消费方式已验」（§3.4）**在**对拍工具层**的具体形态：
**契约漂移不会被运行时报错，只会静默地被解码器吸收**。

**判定式**：
1. ⇒ ⇒ wire 层比对要么用**无 schema 的原始字节**（hex 逐字节比），
   要么用**schema 驱动 + 未知字段照样打印**的自研解码器；
2. ⇒ ⇒ **判据必须能回答「字段号不一致会怎样」** ⇒ 答案是「会显形」才算合格
   （本轮实测：植入 `f6=0` 的 presence 变异 ⇒ **只有 gRPC wire 维度变红**，HTTP 维度真差异仍是 0）；
3. ⇒ ⇒ 探针本身**用同一份 class 打两侧**（B、C 各自的 pb 包名不同 ⇒ 用各自生成类 ⇒ 两侧跑的**不是同一段代码**）
   ⇒ ⇒ 任何「字节一致」的证据都会被「两侧实现不同」污染；
4. ⇒ ⇒ **每个新维度都要配一条变异证据** ⇒ 否则「0 真差异」只说明**没测出问题**，不说明**测得准**。

---

## P-USER-14 · 「表内 `NOT_VERIFIED` 归零」**≠**「元命题闭合」⇒ 别把**表**当**命题**

**形态**：审核台账的**状态表**逐行标状态（PASS / NOT_VERIFIED / FAIL …）⇒
某轮把两行 `NOT_VERIFIED` 都闭合后 ⇒ 表内变成 **0 个 `NOT_VERIFIED`** ⇒
**很容易顺手写成「全部闭合」** ⇒ ⇒ **但「行为与 B 侧完全等价」是**表外**的**元命题**，
它的剩余缺口**不出现在任何一行里**（第三方锚不可得 · 场景轮未做 · deadline/取消未对拍）。

**判定式**：
1. ⇒ ⇒ **表 ≠ 命题** ⇒ 表归零时**必须显式列出表外的剩余缺口**（本轮写进 §12.5），
   否则「表内 0 个」会被读成「命题闭合」；
2. ⇒ ⇒ **状态表的计数必须机器复算**（本轮手数写成 24，脚本复算是 23 ⇒ 当场纠正）⇒
   同款教训见 [`P-08`](../../_cross/P-08-落地证据未经机器复算.md)；
3. ⇒ ⇒ **改表时顺手多插一行**会让「行数」与「计分」对不上（本轮就多插了一行重复的「双侧活体对拍」）
   ⇒ ⇒ 改完必须**再跑一次复算脚本**，别信第一次的手改。

---

## P-USER-15 · 探针里的**调度线程不是 daemon** ⇒ **JVM 不退出** ⇒ 脚本挂在「明明跑完了」的位置

**形态**：wire 探针要「到点取消调用」⇒ 起了一个 `ScheduledExecutorService` 去触发 cancel
⇒ ⇒ **默认线程工厂造出来的是非 daemon 线程** ⇒ ⇒ JVM 的退出条件是「所有非 daemon 线程结束」
⇒ ⇒ 主线程跑完所有用例、`System.exit` 之前，只要那个池子还活着，**JVM 就不会退出**
⇒ ⇒ shell 侧看到的是「进程还在」⇒ 脚本**卡在收尾**（看起来像「探针挂死」）。

**判定式**：
1. ⇒ ⇒ 探针里**任何**为「定时/超时/取消」而起的线程 ⇒ **一律 `setDaemon(true)`**
   （`Executors.newSingleThreadScheduledExecutor(task -> { Thread t = new Thread(task); t.setDaemon(true); return t; })`）；
2. ⇒ ⇒ 收尾**显式 `shutdownNow()`** 线程池，别指望 JVM 帮你收；
3. ⇒ ⇒ **判据**：「用例输出已经打完了，但进程没退出」⇒ **先怀疑非 daemon 线程**，别急着怀疑服务端；
   ⇒ **判别手法**：把调度线程去掉再跑 ⇒ 若立刻退出 ⇒ 就是它；
4. ⇒ ⇒ 顺带：**计时必须打两处** —— `call()` 与 `callCancellable()` 都要测 `elapsed_ms`
   ⇒ ⇒ 否则「是客户端等太久」和「是服务端算得慢」分不开。

---

## P-USER-16 · 「jar 存在 ⇒ 跳过构建」⇒ 探针量到的是**上一次的代码** ⇒ **读数是假的**

**形态**：探针起真进程跑对拍 ⇒ 产物是 fat jar ⇒ 常见写法是「jar 不存在才构建」。
⇒ ⇒ 共享层修了一个 bug、源码已改，**但 target/ 里还是旧 jar** ⇒ ⇒ 探针跑的是**修复前**的代码
⇒ ⇒ 读数「C 侧超时出口是 503」⇒ ⇒ **当场被当成真差异登记** ⇒ ⇒ **假读数**。

**实测代价（RAG-V1 · users-user · 2026-11-03 第三轮）**：
共享层的 DB 超时修复（`6a3acb4`）已进 `main`，但 `service/users/user/target/` 里的 jar 仍是旧的
⇒ ⇒ 第一轮场景轮量出「C 侧 `503 Request Timeout @ 3.00s`」⇒ ⇒ **差点登记成一条新的真差异**
⇒ ⇒ 改成**按源文件 mtime 判陈旧并重建**后 ⇒ 真读数是「C 侧 `200 + 信封 1015`」⇒ ⇒ **两侧一致**。

**判定式**：
1. ⇒ ⇒ 陈旧判定要**跨模块**：C 侧要同时看 `service/users/user/src/main` · `service/users/common/src/main` ·
   **`common/src/main`** · `proto/src/main` ⇒ ⇒ 只看本域源码会漏掉**共享层改动**；
2. ⇒ ⇒ 判据用 `find <src> -type f -newer <jar>` ⇒ **有任一源文件更新 ⇒ 重建**；
3. ⇒ ⇒ **B 侧同样要做**（B 仓是只读参考，但 `target/` 也在 B 仓里 ⇒ 一样会陈旧）；
4. ⇒ ⇒ ⚠️ **更一般的一条**：「产物存在」**不等于**「产物对得上源码」⇒ ⇒
   **任何以产物为观测对象的探针，都必须自带「产物 vs 源码」的新鲜度断言**。

---

## P-USER-17 · 「端口已监听」**≠**「服务已注册」⇒ 启动顺序错了 ⇒ 下游启动即失败

**形态**：B 侧是**双进程**（`user-rpc` + `user-api`）⇒ `user-api` 通过 devh 的 gRPC client
连 `discovery:///user-rpc` ⇒ 而那个 client **启动即连**（`connectOnStartup`）。
⇒ ⇒ `user-rpc` **端口已经开了**、但**还没把实例注册进 Nacos** ⇒ ⇒ 此时起 `user-api`
⇒ ⇒ 它启动即失败：`IllegalStateException: Can't connect to channel user-rpc`。
⇒ ⇒ 现象极具迷惑性：**user-api 的日志里没有自己的配置错误，只有「连不上 user-rpc」**。

**判定式**：
1. ⇒ ⇒ 探针起「下游」之后，**不能只等端口** ⇒ ⇒ 必须**再等一层「注册落地」**
   ⇒ 查 Nacos 的实例列表（`/nacos/v1/ns/instance/list?serviceName=…&groupName=…&namespaceId=public`）
   ⇒ ⇒ **轮询到自己的端口出现在列表里**再起上游；
2. ⇒ ⇒ **端口就绪**与**注册就绪**是两个**独立的就绪条件** ⇒ ⇒ 都要等；
3. ⇒ ⇒ ⚠️ **Nacos 分组必须隔离**（B=`E4UB` · C=`E4UC`）⇒ 两侧注册的 gRPC 服务**同名 `user-rpc`**
   ⇒ 同组时 B 的 api 会**拨到 C 的 rpc** ⇒ ⇒ **对拍对象就错了**（而且是**静默**的）；
4. ⇒ ⇒ 判别手法：**偶发**起不来 ⇒ 先怀疑**注册竞态**（加等待即消失）⇒ 而不是怀疑配置。

---

## P-USER-18 · 任务被**外部取消** ⇒ `trap` 不执行 ⇒ **端口残留** ⇒ 下一轮直接「端口被占用」退出

**形态**：`run.sh` 靠 `trap cleanup EXIT` 杀进程、删容器 ⇒ ⇒ 但**任务被外部杀掉**（超时 / 被 lane 挤掉 / Ctrl-C）
⇒ ⇒ **`trap` 不会执行** ⇒ ⇒ 探针的 java 进程与 PG 容器**活下来** ⇒ ⇒ **端口段被占**
⇒ ⇒ 下一轮探针在起进程阶段就以「端口被占用」退出 ⇒ ⇒ **症状指向错误的地方**（像是本轮配置错了）。

**判定式**：
1. ⇒ ⇒ 清理**不能只依赖 trap** ⇒ ⇒ 启动成功后**按端口反查 PID 记进清理列表**
   （`lsof -nP -iTCP:<port> -sTCP:LISTEN -t`）⇒ ⇒ 即使 trap 没跑，残留也能被下一轮清掉；
2. ⇒ ⇒ 收尾**必须实测端口真的释放了**（`lsof` 复查）⇒ ⇒「探针退出了」**不等于**「进程退了」；
3. ⇒ ⇒ **场景轮会 pause / stop 容器** ⇒ ⇒ cleanup 要**先 `unpause` + `start` 再删**
   ⇒ ⇒ 否则 `rm` 失败时会留下一个**挂着的容器**给下一个 lane；
4. ⇒ ⇒ ⚠️ **并发场景的临时文件也要各自独立** ⇒ 两侧并发写入**同一个** body 文件
   ⇒ ⇒ 互相覆盖 ⇒ ⇒ 读数是**串扰**出来的 ⇒ ⇒ 每侧各自一个文件。
