# P-22 · javadoc 断链有三类（其中一类**工具永远扫不到**）＋ 注释里写的机制**可能压根没实现**

> **一句话**：`{@link}` 指向不存在的名字 ⇒ javadoc 报「找不到引用」；但**藏在 `/* */` 块注释里的断链，javadoc 工具永远扫不到**（只有 `/** */` 会被解析）⇒ ⇒ 工具报「0 错」不等于「没有断链」；⇒ ⇒ 更狠的是：**注释里描述的机制可能压根没实现** —— 「用了 `ServerCall#getDeadline()` 区分两类超时」这句话里的那个方法，grpc-api **根本没有**。

## 一、javadoc 断链的三类（本仓实测 · 2026-11-03 · `common/` 全量扫）

`common/` 全量扫出 **21 个错误**（20 个「找不到引用」＋ 1 个「@param name 未找到」）＋ 7 个「无效输入」警告。
⇒ **21 个断链分三类**：

| 类 | 形态 | 例子 |
|---|---|---|
| **① 类名/包路径已改** | `{@link RpcInterceptor}` —— B 侧那个 1182 行大类在 C 侧**被拆成了多个类**，没有同名类了 | `RpcRecoverInterceptor:41` · `RpcStatInterceptor:49` · `GoStyleRequestDeadline:129` |
| **② 作用域写错** | `{@link #compose(List)}` 写在 `ProtectionAutoConfiguration` 的类注释里，但 `compose` 在**别的类**（`RpcProtectionChain`）⇒ **`#` 只在当前类里找** | `ProtectionAutoConfiguration:64` · `LimitMode:116`（`{@link #OFF}` 在**类**注释里，而 `OFF` 是**嵌套枚举**的常量 ⇒ 要写 `Mode#OFF`） |
| **③ 🔴 藏在 `/* */` 里** | `DeadlineState.java` 有一段 `/* ... */`（**不是 `/** */`**）的说明文字，里面写 `{@link #newUnaryServerInterceptor}` ⇒ ⇒ **javadoc 工具不解析普通块注释 ⇒ 永远扫不到** | `DeadlineState.java:103` |

⇒ ⚠️ **③ 这一类最危险**：它恰好是**全仓最要命的那处** ——
`DeadlineState` 的那段注释说「实际生效路径：`newUnaryServerInterceptor` 的 `onHalfClose` 在真正跑 handler 的线程上绑定 deadline」
⇒ ⇒ **而 `newUnaryServerInterceptor` 这个方法在 C 侧从无实现**（`git log --all -S` 全仓历史：它**只作为注释出现过**）
⇒ ⇒ 它是**照抄 B 侧时丢了归属**（B 侧那段 `#newUnaryServerInterceptor` 指的是**外层类** `RpcInterceptor` 的成员，不是 `DeadlineState` 的）
⇒ ⇒ **这段注释在教一个不存在的机制** ⇒ ⇒ 读注释的人会以为「SQL 超时取消」已经实现了。

### 判据（怎么不漏）

1. **javadoc 工具只扫 `/** */`** ⇒ ⇒ **`/* */` 里的 `{@link}` 必须人工 grep**（`grep -rn "{@link" --include=*.java` 后**逐条确认所属注释块的开头是不是 `/**`**）。
2. **本仓的 `maven-javadoc-plugin` 离线不可用**（`3.11.3` 不在本地 m2）⇒ ⇒ 用 **JDK 自带 CLI**：
   ```bash
   mvn -o -B -q -pl common dependency:build-classpath \
       -Dmdep.outputFile=/tmp/cp.txt -DincludeScope=compile
   javadoc -Xdoclint:reference -quiet -d /tmp/jd -classpath "$(cat /tmp/cp.txt)" \
       -sourcepath common/src/main/java -subpackages com.rag.common
   ```
   ⇒ ⚠️ **它的 `-quiet` 会把「0 错」藏起来**（没有汇总行）⇒ ⇒ 要**自己 grep 退出码 + 错误行**，别只看「命令跑完了」。
3. **基线对比**：修之前先跑一遍存下**错误数 + 警告数** ⇒ ⇒ 修完复跑 ⇒ ⇒ **「少了几个」才是证据**，而不是「现在没有报错」（基线本身可能就是 0）。

## 二、`{@code}` 的坑（实测 · **且与 A13 守门耦合** ⇒ 两件事必须同时知道才能改对）

> ⚠️ **本节第一版写错了**（写成「`{@code}` 里不能有代码花括号 ⇒ 改用纯 `<pre>`」）⇒ ⇒ **那会把 A13 守门弄红** ⇒ ⇒ 已被下面的正确规则取代。
> ⇒ ⇒ **教训**：**注释格式的修法不能只看 javadoc 工具的读数** ⇒ ⇒ 还要看**机器守门对格式的假设**（这里是 A13 的 `<pre>\s*\{@code`）。

### 正确规则：`{@code}` 块**结尾要多一个 `}`**（那个 `}` **不属于代码**）

javadoc 的 `{@code ...}` 是 **inline tag**：它的闭合是**按花括号嵌套深度**算的 ——
`{@code` 本身占**深度 1**，内容里每出现一个 `{` 加一、每个 `}` 减一 ⇒ ⇒ **深度回到 0 时那个 `}` 就是 tag 的结束**。

⇒ ⇒ 于是**代码里的 class 大括号**会让深度**一直下不来** ⇒ ⇒ **tag 永远不闭合** ⇒ javadoc 报
**「未终止的内嵌标记」** ＋ **「元素未关闭: pre」**。

⇒ ⇒ **本仓的解法（既有约定，全仓 24 个 Lombok 类都这么写）**：**代码块结尾多写一个 `}`** ——
它**闭合的是 inline tag**，**不渲染**（实测：渲染出的 HTML 里代码块以 class 的 `}` 收尾，**没有多余的 `}`**）：

```java
 * <pre>{@code
 * public class ApiResponse {
 *     private int code;
 *     public ApiResponse() { }
 *     public ApiResponse(int code, String msg, Object data) {
 *         this.code = code;
 *     }
 * }
 * }</pre>          ← 这个多出来的 } 闭合的是 {@code} 这个 tag，不是代码
```

### 🔴 为什么**不能**改成纯 `<pre>`：A13 守门认的就是 `<pre>\s*\{@code`

`tools/difftest/check-arch-invariants.py` 的 A13（D18「Lombok 展开块必须机器可判」）判据是：

```python
LOMBOK_EXPANSION_MARKER = "Lombok 展开"
LOMBOK_EXPANSION_CODE = re.compile(r"<pre>\s*\{@code", re.S)
```

⇒ ⇒ **`{@code}` 是 A13 的机器判据的一部分** ⇒ ⇒ 把 `{@code}` 换成纯 `<pre>`
⇒ ⇒ **A13 立刻 FAIL**（「类上有 @Getter / @Setter … 但缺『Lombok 展开』展开块」）⇒ ⇒ **守门红**。

⇒ ⇒ ⚠️ **本轮就是这么踩的**：为了消 javadoc 的「未终止的内嵌标记」，把两处 `{@code}` 改成纯 `<pre>`
⇒ ⇒ javadoc 干净了 ⇒ ⇒ **A13 从 PASS 24/24 变成 FAIL 2/24** ⇒ ⇒ 靠**基线对照**才发现
（基线 `PASS 24/24` / EXIT=0 ⇒ ⇒ 不是我引入的？—— 恰恰相反，**是我引入的**）。

⇒ ⇒ **判定式**：**改注释格式前 ⇒ 先问「有没有机器守门认这个格式」** ⇒ ⇒ 本仓至少 3 个：
① **A13** 认 `<pre>{@code`（Lombok 展开块）· ② **A1–A13 之外**的 `check-docs-vs-tree.py` 认文件树的 `├──` 形状 · ③ `gen-file-tree.py` 认 javadoc 的**首行职责说明**。

### 顺带两个纯 HTML 坑（与 A13 无关，独立成立）

| 写法 | javadoc 报什么 | 解法 |
|---|---|---|
| 纯 `<pre>` 里有一行**以 `@` 开头**（如 `@JsonProperty("issuer")`） | **「未知标记: JsonProperty」** | **行首的 `@` 被当成 javadoc 块标签** ⇒ ⇒ ① 放回 `{@code}` 里（**`{@code}` 内不解析块标签** ⇒ 不用转义）· ② 或写 `&#64;`（纯 `<pre>` 里实体照常解析 ⇒ 渲染仍是 `@`） |
| 代码里有 `&`（如 Go 的 `&s.total`） | **「无效输入：'&s'」** | 转义成 **`&amp;`** |
| `<p>` 没关就接 `<pre>`（块元素） | **「内嵌元素 <b> 中不允许使用块元素: p」** | 先把 `</p>` 补上 |

## 三、🔴 最深的一条：**注释里写的机制可能压根没实现**

本轮顺手撞到三处**「注释宣称 A、代码做 B」**，而且**三处都在讲「机制」**（不是讲历史）：

| 注释宣称 | 代码实际 | 后果 |
|---|---|---|
| `RpcSheddingInterceptor`：「用 `{@link ServerCall#getDeadline()}` 的 `{@link Deadline#isExpired()}` 区分『客户端 deadline 过期』与『服务端自己返回 DEADLINE_EXCEEDED』」 | 判据是 `isCtxDeadlineExpired(closeCode)` —— **只看 `close` 的 status 码**；而 **`io.grpc.ServerCall` 在 grpc-api 1.63.0 里根本没有 `getDeadline()`**（`javap` 实测：只有 `request`/`sendHeaders`/`sendMessage`/`close`/`isCancelled`/`getAttributes`/`getAuthority`/`getMethodDescriptor` 等） | 注释描述的机制**不可能存在** ⇒ 读注释的人以为「客户端 deadline 与服务端超时被区分开了」，实际靠的是**另一个判据** ＋ **一个实测前提**（没有 handler 显式造 `DEADLINE_EXCEEDED`）⇒ 前提一破就分岔 |
| `ObservabilityAutoConfiguration`：「用 Spring 的 `{@link ContentCachingResponseWrapper}` 缓存一份」 | 实现刻意**不用**它（`ObservabilityResponseWriter` 的类注释：「**不能直接用** Spring 的 `ContentCachingResponseWrapper`」—— 它的 `flushBuffer()` 会透传 ⇒ 两个写者 ⇒ 状态码被抢 + `MimeHeaders` 被写坏 ⇒ NPE） | 注释会把人引向一个**已被本仓否决**的写法 |
| `DeadlineState`（**普通块注释里**）：「实际生效路径 = `newUnaryServerInterceptor` 的 `onHalfClose`」 | 该方法**从无实现**；C 侧 gRPC 服务端 listener **只重写了 `onCancel()`** ⇒ ⇒ `DeadlineState` 在 gRPC 路径上**零绑定点** | ⇒ **在途写不被取消 ⇒ 客户端取消了、服务端照样提交**（= **PARITY-006** 的根因） |

### 判据（**注释里的机制断言必须能被证伪**）

1. **注释里出现「用了 X」「通过 X 区分」「实际生效路径 = X」这类措辞** ⇒ ⇒ 必须能回答：**「这段代码真的在跑吗？」**
   ⇒ ⇒ 回答不了 ⇒ ⇒ **注释比没有注释更危险**（它会让人**跳过核实**）。
2. **核实方法 = 读框架源码到消费点**（AGENTS.md §3.3.1）⇒ ⇒ 本轮三处里有两处的破绽都出在**框架 API 上**
   （`ServerCall.getDeadline()` 不存在 · `ContentCachingResponseWrapper` 的语义与本仓需求冲突）
   ⇒ ⇒ **`javap` 一下就能证伪**，比读十遍注释快。
3. **注释断言 ≠ 代码行为** ⇒ ⇒ 凡是**注释与实现冲突**，**先改注释**（AGENTS.md §3.3.3 纪律③：「修漂移先改/删错误断言，再改实现」）。
4. **注释里的「锁定用例 / 守门 / 已验证」也要核** —— 本轮在 `OutboundDeadlineInterceptor` 里发现 **3 个被点名的测试类名在全仓只出现在那段注释里，一个都不存在**
   ⇒ ⇒ 「注释说已锁定」与「实际有网」是两件事 ⇒ ⇒ 已改为「**应该**补」＋ 标明 C 侧现状（**不再宣称已锁定**）。

## 四、可复用的收口动作

1. **先取基线**（错误数 + 警告数 + 逐条清单）⇒ ⇒ 再改 ⇒ ⇒ 复跑 ⇒ ⇒ **对比「少了几个」**。
2. **三组 doclint 分开看**：
   - `-Xdoclint:reference` = **断链**（本轮 21 → **0**）
   - `-Xdoclint:syntax` = **标签/实体**
   - `-Xdoclint:html` = **嵌套标记等风格项**（本仓**全仓 43 条**「不允许使用嵌套标记: `<b>`」是**既有的house style**，不必清）
3. **`{@link}` 只在「能确定一一对应」时用** ⇒ ⇒ 否则用 `{@code X}`（**保留 B 出处信息**）⇒ ⇒ **断链换成错链更糟**。
4. **B 侧（RAG 仓）类名在 C 侧不存在 ⇒ 一律 `{@code}` ＋ 注明 B 出处路径** ⇒ ⇒ 本轮 21 个断链里有 8 个是这一类。
