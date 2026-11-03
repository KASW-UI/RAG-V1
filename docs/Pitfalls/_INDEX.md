# Pitfalls 索引 —— 踩坑档

> **记什么**：「为什么会踩」—— 框架机制静默失效、绿测试锁死错误答案、基础设施误用。
> **不记什么**：具体行为差异（→ `../DRIFT/`）、签字决策（→ `../Intentional_Deviations/`）。

## 档位结构

```
docs/Pitfalls/
├── _INDEX.md
├── _cross/            # 跨模块踩坑
├── users/             # users 域专属（本域写权面）
└── follow/            # follow 域专属（本域写权面）
```

## 已登记的坑（来自 RAG 形态的实测教训）

| # | 坑 | 一句话 |
|---|---|---|
| P-01 | **测试注入掩盖生产配置缺失** | `grpc.client.*.negotiation-type` 由 `@SpringBootTest(properties=...)` 在测试里注入 ⇒ 生产配置可以一直是错的 ⇒ 测试永远绿 ⇒ 没人发现 |
| P-02 | **注解存在 ≠ 机制生效** | `RpcServerInterceptorFactory` 只产出 Bean，不挂到 `server.intercept` 上 ⇒ 整条拦截链静默失效 |
| P-03 | **自动配置排序悬空** | Boot 4 挪了 `DataSourceAutoConfiguration` 的包名，MP 的 `@AutoConfigureAfter` 引用静默失效 ⇒ MP 自动配置不生效。⚠️ **D14 后本仓是 Boot 3.5 ⇒ 不适用**（MP 的 Boot 3 变体引用 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` ⇒ 引用有效）；**但「跨 jar 的自动配置排序要验」这条教训不变** |
| P-04 | **`@Import` 与 auto-config 抢求值顺序** | `@Import` 那份按 user config 处理（更早求值）⇒ `@ConditionalOnMissingBean` 语义改变 |
| P-05 | **复制模块模板 ⇒ 抄走错误接线** | 新 session 复制现有模块当模板，会连同那份 4~10 个 Bean 不等的 `@Import` 清单一起抄走 |
| P-06 | **中断对阻塞 IO 无效** | Java 的 `Future.cancel(true)` 对 JDBC 驱动 / grpc-java 阻塞 stub / `java.net.http` 均不响应 ⇒ 必须走服务端取消（`setQueryTimeout` → pgjdbc CancelRequest） |
| P-07 | **写错签名比不写好** | Spring Boot 4 / Jackson 3 的 `HttpMessageConverter.read` 签名已变 ⇒ 写错直接编译失败，反而比"照抄旧教程"安全。⚠️ **D14 后本仓是 Boot 3.5 + Jackson 2 ⇒ 签名与 Boot 3 一致**；**但「编译期能抓的错优于运行时静默」这条教训不变** |
| P-08 | **「落地证据」没被机器复算 ⇒ 只是又一处沉默不变量** | 台账里写「三域各含 `wiring/<域>FactProcessLogging`」（原名 `bootstrap/`）⇒ **comment 域实际没有** ⇒ 零编译错误 + 零测试红灯 ⇒ 直到守门脚本首次实跑才暴露 ⇒ 一句没人复算的「证据」与「愿望」在字节层面完全一样 |
| P-09 | **批量替换的三个静默失效** | macOS BSD `sed` **不支持 `\b`** ⇒ 替换零命中（文件名改了、类名没改）· perl 替换串里 `.` 是**字面点** ⇒ 产出 `web/.Xxx` 坏串 · 路径前缀写法不统一 ⇒ 漏网 ⇒ **三次全部零报错** ⇒ 批量改名必须自带「残留=0 / 文件名≡类名 / 引用表↔磁盘」三条守恒断言 |
| P-10 | **断言的「自动消失」≠ 问题不存在** | `return na("契约 B 下不适用")` 看着是保护措施，**实为断言的死亡证明** —— 把「命题在另一形态下不成立」这个**未经论证的假设**写进了代码。A3（拦截链有没有真挂上）与进程形态无关，却被判 N/A ⇒ 连同它本该抓的缺陷一起从门禁里消失 ⇒ 判形态相关要看**命题**，不看**实现形态** |
| P-11 | **按**类型**判缺失的 Bean ⇒ 被同类新增**静默挤掉** | `@ConditionalOnMissingBean` 无参 ⇒ 判据 = `@Bean` 方法的**返回类型** ⇒ 返回类型是**接口**时 ⇒ 容器里多一个同接口 Bean 就把本 Bean 判成「已存在」⇒ **静默不注册**（零报错）⇒ 本仓实测：新增 5 个 `ServerInterceptor` ⇒ **业务观测层从 gRPC 链上消失** ⇒ 只有「把链真的搭出来逐层读层名」才抓得到 ⇒ **凡返回类型是宽泛类型，一律按 bean 名判缺失** |
| P-12 | **「变异没变红」＝「那条断言**没覆盖到**」** | 变异实测时把实现改坏 ⇒ 断言**没红** ⇒ 有两种解释：① 该变异**等价**（可接受）② **那条断言压根没驱动到被改的分支**（🔴 默认先假设这一种）⇒ 本仓实测：把「探针断连 ⇒ 499」改成 500 全绿 ⇒ 读代码才发现**被测的是 panic 分支、被改的是另外两条分支** ⇒ ⇒ 修法 = 给不可驱动的机制加**测试接缝**（函数式接口 ＋ 生产构造器固定真实现 ＋ 包级构造器供测试注入）⇒ 补完**再变异一次**确认变红 ⇒ ⇒ **「变异没变红」不是「变异安全」，是「没覆盖」** |
| P-13 | **配置项写成属性名 / 有键无值 ⇒ 绑不上 ⇒ 进程起不来** | 配置项名与属性名不一致、或 yaml 里只有键没有值 ⇒ Binder 静默不生效 ⇒ 启动即失败 |
| P-14 | **`@RequestBody(required = false)` + 空 body ⇒ `@Valid` 被跳过** | 空 body 进业务层 ⇒ **500**（不是 400）⇒ 校验形同虚设 |
| P-15 | **手工装配 MyBatis 没用 `SpringManagedTransactionFactory`** ⇒ **`@Transactional` 静默失效** | 事务注解在，但事务管理器不是 Spring 托管的 ⇒ 不报错、就是没事务 |
| P-16 | **Boot 3.4+ 嵌套 `@TestConfiguration` 默认不被发现** | 测试配置**静默不注册** ⇒ 测试"绿"但根本没加载配置 |
| P-17 | **合并进程里的「Nacos 自动注册」会拿 web 端口注册** | 同名双实例 ⇒ gRPC 链路断 |
| P-18 | **域还是空壳 ⇒ 没有 `main` ⇒ `repackage` 失败 ⇒ `mvn verify` 恒红** | 报错出现在**打包阶段**（看着像 pom 配错）⇒ 真因是**域未实现** ⇒ 红绿分布只反映「实现到哪一步」，**与代码质量无关** ⇒ ⇒ **红也要做同条件对照**（`git archive HEAD` 基线副本 + **`-fae`**）⇒ 域实现完成前，全仓验收信号用 `mvn -o clean test-compile` / `mvn -o clean test` |
| P-19 | **JWT 是**确定性**的 ⇒ 同秒内同账号登录拿到的 token 逐字节相同 ⇒ 黑名单会「串味」到别的用例** | JWT 载荷 = `{uid, iat, exp}` ⇒ 同密钥 ＋ 同 uid ＋ 同一秒 ⇒ **签出同一个字符串** ⇒ ⇒ 本仓 HTTP 契约测试的登录夹具**每次都用同一个默认 admin 账号** ⇒ ⇒ A 用例把 token 加进黑名单 ⇒ B 用例**再拿同一个 token** ⇒ ⇒ 必然 401 ⇒ ⇒ 表现为**与执行顺序强相关的不稳定失败**（同一份代码上一轮 `clean verify` 16/16 全绿、下一轮红 1 例）⇒ ⇒ **测试夹具隔离缺陷**（不是产品缺陷）⇒ 修法 = 夹具侧让 token 可区分（注入 `iat` 偏移 / 逐用例唯一 uid / 登录后清黑名单）⇒ ⇒ **「红了先按基线自带处理，再按同条件对照复跑定位」**这条纪律在此再次生效 |
| P-20 | **MyBatis 把 mapper 调用里的任何异常包成 `PersistenceException`** | `DefaultSqlSession` 对每个异常都 `catch (Exception e) → wrapException` ⇒ ⇒ 持久层翻好的 `StatusRuntimeException(DEADLINE_EXCEEDED)` **在离开持久层前就被吃掉一层** ⇒ ⇒ 域层的 `catch (StatusRuntimeException e) { throw e; }` **看不见它** ⇒ ⇒ 落 `catch (Exception e)` ⇒ ⇒ **被改写成 5001**（不是 B 侧的 1015）⇒ 解包点选在 `SqlSessionFactory`（两条 mapper 路径都经它） |
| P-21 | **MyBatis 拦截器的 `@Intercepts` 漏签一个方法 ⇒ 那条路径整条绕过** | `Executor.queryCursor` 漏签 ⇒ MP 的 `BaseMapper.selectOne(Wrapper, boolean)`（**游标路径**）**完全不经过翻译器** ⇒ 57014 原样冒到域层 ⇒ 5001；⇒ **签错一个方法 ⇒ 启动期 `PluginException`**（3 参 `Executor.query` 在 MyBatis 3.5.19 里不存在）⇒ **漏签零报错、写错启动炸**，两者都是「零功能性报错」 |
| P-22 | **javadoc 断链有三类，其中一类**工具永远扫不到**；且注释里写的机制**可能压根没实现** | ① 类名已改（`{@link RpcInterceptor}`）② 作用域写错（`{@link #compose(List)}` 指向别的类）③ 🔴 **藏在 `/* */` 里 ⇒ javadoc 工具永远扫不到**（本轮最要命那处：`{@link #newUnaryServerInterceptor}` —— **该方法从无实现**）⇒ ⇒ 工具报「0 错」≠「没有断链」；⇒ ⇒ **注释宣称 A、代码做 B**：注释说用 `ServerCall#getDeadline()` 区分两类超时，而 **grpc-api 1.63.0 的 `ServerCall` 没有这个方法**（`javap` 实测）⇒ ⇒ 注释里的「机制断言」必须能被证伪；⚠️ **改注释格式前先问「有没有机器守门认这个格式」** —— A13 的判据就是 `<pre>\s*\{@code`（Lombok 展开块）⇒ ⇒ 为了消 javadoc 的「未终止的内嵌标记」把 `{@code}` 换成纯 `<pre>` ⇒ ⇒ **A13 从 PASS 24/24 变成 FAIL 2/24**（靠基线对照才发现）⇒ ⇒ 正确解法 = **`{@code}` 块结尾多写一个 `}`**（闭合 inline tag，不属于代码、不渲染）|
| P-23 | **对照探针的【顺序注入】⇒ 读数差混进了「两侧调用起点差」⇒ 假差** | 顺序注入（先发 A、等它返回、再发 B、最后才放行被冻结的依赖）⇒ **A 的调用比 B 早 ≈1 秒** ⇒ ⇒ 放行那一刻**两侧离各自预算到点的距离不同** ⇒ ⇒ 而「那条写落不落库」**由放行那一刻还剩多少预算决定** ⇒ ⇒ **读数必然不同**；⇒ 🔴 **更要命**：这类读数**对修复完全不敏感**（修前修后读数一样）⇒ ⇒ 探针是**假绿**；⇒ **判定式**：① 注入必须对称（同一客户端发两侧 ＋ 同一绝对时刻取消 ＋ **放行时刻相对「调用起点」定义**）② **单点相等只是弱证据** ⇒ 要扫多个偏移、比**翻转点**是否重合 ③ 先跑「只变放行时刻」的机制实验 ④ **真差异数下降 ≠ 修好了**（可能是假差被剔除）|
| **P-USER-01~12** | `users-user` 子域的 **12 条**域内踩坑 | 「只有注释的 YAML 键」解析成**空串**而不是 `null`（全仓启动雷）· in-process server 名字是 **JVM 全局注册**的 · `-pl <module>` 单模块跑测解析不到兄弟模块 · 「测试写错了」与「实现写错了」长得一模一样 · 守门脚本 `ROOT` 有三种形态只有一种对 · `@Transactional` 挂在 **MP mapper 接口的 `default` 方法**上（本仓最易静默失效的形态）· 默认 `mvn clean verify` 是 **fail-fast** ⇒ 失败面被截断 ⇒ 跨树对照会得出**错误结论** · **测试类路径 ≠ 生产 jar 类路径** ⇒ 「`mvn test` 全绿」证不了「fat jar 起得来」· Bean Validation 的**违规集合是 `HashSet`** ⇒ `findFirst()` **不确定** · Python 打 HTTP ⇒ **1xx 中间响应**被当成最终响应 · bash `( cd … && java … ) &` 的 `$!` 是**子 shell 的 PID** ⇒ cleanup 杀不到 java ⇒ 端口残留 · 拿**屏蔽后的规范化文本**去断言**原始值** ⇒ 断言恒 false ⇒ 两侧同时挂却不是行为差异 |
| **P-FOLLOW-01~04** | `follow` 域的 **4 条**域内踩坑 | 下游被**外力 SIGKILL** ⇒ 探针照样绿灯 ⇒ **读数测的是另一个场景**（探针跑着跑着下游被杀掉 ⇒ 后面所有阶段跑在坏环境上 ⇒ 却给出「看起来很干净」的分布）· 等待「服务发现条目回来」的**嵌套重试** ⇒ 下游已死时**死等 7.5 分钟** · 生成器在 **worktree** 里跑 ⇒ 与 main 的差异是**假差异**（**git 不跟踪空目录**）· 变异脚本的备份是**启动时一次性取的** ⇒ 运行期间改靶点文件会被**还原冲掉** |

> 📖 详情 → [`_cross/P-08-落地证据未经机器复算.md`](_cross/P-08-落地证据未经机器复算.md) ·
[`_cross/P-20-MyBatis包装异常导致status到不了域层.md`](_cross/P-20-MyBatis包装异常导致status到不了域层.md) ·
[`_cross/P-21-MyBatis拦截器漏签与写错签名.md`](_cross/P-21-MyBatis拦截器漏签与写错签名.md) ·
[`_cross/P-22-javadoc断链的三类与注释里的假机制断言.md`](_cross/P-22-javadoc断链的三类与注释里的假机制断言.md) ·
[`_cross/P-23-顺序注入的对照探针把调用起点差混进读数.md`](_cross/P-23-顺序注入的对照探针把调用起点差混进读数.md) ·
> [`_cross/P-09-批量替换的三个静默失效.md`](_cross/P-09-批量替换的三个静默失效.md) ·
[`_cross/P-12-变异没变红等于那条断言没覆盖到.md`](_cross/P-12-变异没变红等于那条断言没覆盖到.md) ·
> [`_cross/P-18-域还是空壳导致repackage找不到main方法.md`](_cross/P-18-域还是空壳导致repackage找不到main方法.md) ·
> [`_cross/P-11-按类型判缺失的Bean被同类新增静默挤掉.md`](_cross/P-11-按类型判缺失的Bean被同类新增静默挤掉.md) ·
> [`_cross/P-10-断言的自动消失不等于问题不存在.md`](_cross/P-10-断言的自动消失不等于问题不存在.md)
> [`_cross/P-19-JWT确定性导致黑名单串味到别的用例.md`](_cross/P-19-JWT确定性导致黑名单串味到别的用例.md)
>
> 📌 **域内踩坑（各域写权面 ⇒ 由收口域统一登记索引）** ⇒
> [`users/_user-domain-pitfalls.md`](users/_user-domain-pitfalls.md)（P-USER-01~12 · `users-user` 子域）·
> [`follow/_follow-domain-pitfalls.md`](follow/_follow-domain-pitfalls.md)（P-FOLLOW-01~04 · `follow` 域）

## 纪律

1. **踩坑一旦确认，立刻写档** —— 隔一天细节就丢了。
2. **写清「为什么会踩」而非「怎么修」** —— 修法在代码里，原因才需要留存。
3. **跨模块的坑进 `_cross/`**；域内专属的进域目录。
