# 10. JWT 认证与身份传递流程

> 本文是 `service/users/user` 域的**机制类**文档：讲清「一个受保护请求，身份是怎么被确定下来并交给业务代码的」。
> 读法建议：先在具体流程里见过一次 `userId` attribute（见 [`./01-用户登录流程.md`](./01-用户登录流程.md) §4.2），
> 再回来读这篇。

---

## 1. 本次流程的研究范围

我们追踪的是一个**受保护路径**上的请求，从它进入 Servlet 容器、一直到业务代码能拿到「我是谁」为止。

**追踪到哪里为止**：

```
HTTP 请求到达 Tomcat
  → Servlet Filter 链（含 JwtAuthFilter）
  → DispatcherServlet
  → HandlerInterceptor#preHandle（含 JwtAuthInterceptor）
  → Controller 方法拿到 HttpServletRequest
  → 业务代码从 request attribute 读出 uid / token
```

**不追踪什么**：

- **不追踪** JWT 的签发过程（登录端点怎么造 token）—— 那是 [`./01-用户登录流程.md`](./01-用户登录流程.md) §4.11 的内容；
- **不追踪** 业务代码拿到 uid 之后做了什么（查库、改资料等）—— 那是 03~09 号文档；
- **不追踪** 异常被统一处理成什么 HTTP 响应（那是 [`./11-HTTP统一响应与异常处理流程.md`](./11-HTTP统一响应与异常处理流程.md)），
  本文只在必须时指出「最终会落到哪个分支」，不重复展开；
- **不追踪** Redis 黑名单的**写**（注销时写）—— 那是 06 号文档；本文只讲**读**。

**本次流程要回答的核心问题**：

> 客户端只给了一个 `Authorization: Bearer <token>` 头。
> 这个 token 里的 `userId` claim，是**谁**、在**哪一步**、用**哪把密钥**读出来的？
> 又是谁把它交给业务代码的？

---

## 2. 触发场景与输入

### 2.1 一个具体请求

```
POST /user/v1/get HTTP/1.1
Host: localhost:8881
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEwMDEsImlhdCI6...
Content-Length: 2

{}
```

这是一个已登录用户查询自己的资料。9 个端点里，**7 个是受保护的**（只有 `/user/v1/register` 和 `/user/v1/login` 是公开的）。

### 2.2 输入里只有一样东西与身份有关

`Authorization` 头。它里面那个 `eyJ...` 字符串是 JWT，三段式：`header.payload.signature`。
本项目用的是 **HS256**（HMAC-SHA256 对称签名）—— 签发和验签**共用一把密钥**。

payload 解开后大致是：

```json
{
  "userId": 1001,
  "iat": 1762000000,
  "exp": 1764592000
}
```

`userId` 是本项目**唯一**的身份 claim 名。签名密钥、过期秒数分别来自 `user-api.user-auth.access-secret`
与 `user-api.user-auth.access-expire`（见 §4.9）。

### 2.3 一次请求要经过的两道关

| 关卡 | 位置 | 它回答的问题 | 失败时 |
|---|---|---|---|
| **第 1 关：验签** | `JwtAuthFilter`（Servlet Filter） | 「这个 token 是不是我们签的？没过期？」 | 不拒绝，只记录，交给第 2 关 |
| **第 2 关：授权** | `JwtAuthInterceptor`（HandlerInterceptor） | 「这个 token 是不是被拉黑了？claim 能不能用？」 | 抛异常 → HTTP 401 / 业务码 |

**为什么要拆成两关**，是本文最需要理解的设计点，§4.1 展开。

---

## 3. 整体执行路线

```mermaid
flowchart TD
    A["客户端 POST /user/v1/get<br/>Authorization: Bearer eyJ..."] --> B["Tomcat 接收连接"]
    B --> C["Servlet Filter 链<br/>PathNormalizing(+2) → Traceparent(+3) → MaxConns(+4)<br/>→ RouteIsolation(+5) → CpuShedding(+6)"]
    C --> D["JwtAuthFilter.doFilter (+6)<br/>JwtAuthPaths.isProtected(uri) ?"]
    D -->|public 路径| D1["不验签，直接放行"]
    D -->|protected 路径| E["JwtSignatureVerifier.verify<br/>剥 Bearer → 验签名 → 取 userId claim"]
    E --> E1{"验签通过？"}
    E1 -->|否| E2["JwtVerificationResult.failed(AuthenticationException)<br/>写进 request attribute"]
    E1 -->|是| E3["JwtVerificationResult.ok(uid, claimUsable,<br/>signatureToken, trimmedToken)"]
    E2 --> F["chain.doFilter 无条件放行"]
    E3 --> F
    D1 --> F
    F --> G["HttpMiddleware (+7) 起算 3s 预算"]
    G --> H["MaxBytes(+8) / JsonBodyLimit(+9)"]
    H --> I["DispatcherServlet.doDispatch"]
    I --> J["HandlerInterceptor.preHandle<br/>JwtAuthInterceptor"]
    J --> J1{"isPublic(uri) ?"}
    J1 -->|是| K["直接 return true"]
    J1 -->|否| J2{"拿到 Filter 的验签结果？"}
    J2 -->|否| J3["兜底就地验签 + log.warn<br/>source=inline"]
    J2 -->|是| J4["source=filter"]
    J3 --> L{"result.failed() ?"}
    J4 --> L
    L -->|是| M["throw result.failure()<br/>AuthenticationException"]
    L -->|否| N{"claimUsable ?"}
    N -->|是| O["request.setAttribute('userId', Long)"]
    N -->|否| P["不写 userId attribute<br/>（不拒绝）"]
    O --> Q{"trimmedToken 非空 且 在黑名单？"}
    P --> Q
    Q -->|是| R["throw AuthenticationException"]
    Q -->|否| S["request.setAttribute('jwt_token', trimmedToken)"]
    S --> T["preHandle return true"]
    K --> U["Spring MVC 参数解析（Jackson）"]
    T --> U
    U --> V["UserController.getUser"]
    V --> W["UserApiService.getUser<br/>IdentityResolver.claimedUID(request)"]
    W --> X["业务代码拿到 uid"]
```

这张图只是导航图。下面逐段展开，中间每一个判断都会给出源码位置。

---

## 4. 逐步讲解

### 4.1 为什么鉴权要拆成 Filter + Interceptor 两段？

先说结论：**这不是「谁更优雅」的问题，而是「验签耗时不计入 3s 超时预算」的问题。**

`JwtAuthFilter` 的类注释（`JwtAuthFilter.java:17-70`）把这个约束写得很清楚：

- B 侧（真源）用的是 go-zero 的中间件链，链序是
  `原生中间件 → JWT 验签 → observability(3s 计时) → 黑名单 → handler`
  ⇒ **3s 计时器在验签之后起算** ⇒ 验签耗时不算进 3s 预算。
- Java 侧的所有 Servlet `Filter` 都在 `DispatcherServlet` **外面**，`HandlerInterceptor` 在**里面**。
  ⇒ 想让验签落在 3s 计时器**外面**，验签就必须待在 `Filter` 里。

所以职责被切成两半：

| | `JwtAuthFilter` | `JwtAuthInterceptor` |
|---|---|---|
| 类型 | `jakarta.servlet.Filter` | `org.springframework.web.servlet.HandlerInterceptor` |
| 在容器中的位置 | `DispatcherServlet` **外面** | `DispatcherServlet` **里面** |
| 顺序 | `@Order(HIGHEST_PRECEDENCE + 6)`（`JwtAuthFilter.java:72`） | 由 `WebMvcConfig` 注册（`WebMvcConfig.java:42-46`） |
| 干什么 | **验签**：剥 Bearer → 验签名 → 取 claim | **授权**：判 public → 消费验签结果 → 查黑名单 → 写 attribute |
| 干什么**不**干 | 不查 Redis、不写响应 | 不验签（除非兜底） |

```mermaid
flowchart LR
    subgraph F["Filter 链（DispatcherServlet 之外）"]
        F1["JwtAuthFilter.doFilter"] --> F2["JwtSignatureVerifier.verify"]
        F2 --> F3["写 request attribute<br/>com.rag.user.jwt.verification = JwtVerificationResult"]
    end
    subgraph D["DispatcherServlet"]
        subgraph I["HandlerInterceptor（DispatcherServlet 之内）"]
            I1["JwtAuthInterceptor.preHandle"] --> I2["读 JwtVerificationResult"]
            I2 --> I3["写 request attribute<br/>userId / jwt_token"]
        end
        I --> C["Controller → Service"]
    end
    F3 -.->|request attribute 传递| I2
```

#### 🔴 Filter 绝不写响应

`JwtAuthFilter` 有一条例了几层的硬约束（`JwtAuthFilter.java:56-69`）：

> **本 Filter 绝不写响应。**

理由是响应头的**优先级**：

- 本 Filter 在 `+6`，`MaxBytesFilter`（限制请求体大小）在 `+8`。
  如果 Filter 自己在验签失败时写 401，401 就会**压过** `MaxBytes` 应该写的 413。
- B 侧（go-zero）的 `MaxBytes` 是 native middleware，排在验签**之外**（更外层）⇒ B 侧是 **413 压过 401**。

所以 Java 侧的做法是：**Filter 只验签、只把结果放进 request attribute、然后无条件放行**，
401 仍然由 Interceptor 抛 ⇒ 这样 401 发生在所有 Filter 之后 ⇒ 413 仍然压过 401 ⇒ 与 B 侧一致。

看源码就是这一行（`JwtAuthFilter.java:112-115`）：

```java
// 🔴 无条件放行：验签失败也要继续走完 Filter 链 ⇒
//    MaxBytes(+8) 仍有机会写 413（Go 侧 MaxBytes 在验签之外 ⇒ 413 压过 401），
//    最终的 401 由 interceptor 抛（@ExceptionHandler 仍管得到 ⇒ 信封形状不变）。
chain.doFilter(req, resp);
```

还有一个连带后果：**失败必须以「异常对象」的形式传递，不能压成标志位**。
这是 `JwtVerificationResult` 这个 record 存在的全部理由（`JwtVerificationResult.java:11-17`）：

> 若把失败压成标志位、由 Filter 自己写响应，`@ExceptionHandler` 就**管不到**了
> ⇒ 401 的 body 会从「完整信封」退化成空 body ⇒ 推翻 B 侧的签字项。

所以 `JwtVerificationResult` 的第 5 个分量是 `RuntimeException failure`（`JwtVerificationResult.java:29-35`），
由 Interceptor 原样重抛。

### 4.2 Filter 链上的槽位：`+6` 是怎么算出来的

共享层已经在 Filter 链上占了一堆槽位。实测（`common/src/main/java/com/rag/common/` 下各 Filter 的 `@Order`）：

| 槽位 | 类 | 作用 |
|---|---|---|
| `+2` | `web/PathNormalizingFilter.java:78` | 路径规范化 |
| `+3` | `web/TraceparentFilter.java:208` | 起 SERVER span（OpenTelemetry） |
| `+4` | `protection/MaxConnsFilter.java:105` | 最大连接数 |
| `+5` | `autoconfigure/ProtectionAutoConfiguration.java:206` | `RouteIsolationFilter`（熔断，按路由隔离） |
| `+6` | `autoconfigure/ProtectionAutoConfiguration.java:186` | `CpuSheddingFilter`（CPU 降载） |
| **`+6`** | **`interceptor/JwtAuthFilter.java:72`** | **JWT 验签（本文主角）** |
| `+7` | `autoconfigure/ObservabilityAutoConfiguration.java:153` | `HttpMiddleware` —— **3s 预算在这里起算** |
| `+8` | `protection/MaxBytesFilter.java:97` | 请求体大小上限 |
| `+9` | `web/JsonBodyLimitFilter.java:85` | JSON body 限制 |

两条硬约束把 `JwtAuthFilter` 夹在 `[+3, +6]`：

- **下界 `+3`**：`TraceparentFilter` 会起 SERVER span ⇒ 验签必须落在它**之后**，否则验签跑到 span 外（观测面差异）。
- **上界 `+6`**：`HttpMiddleware`（`+7`）在提交 worker 线程之前起算 deadline ⇒ 落在 `+7` 之后 = 验签仍在预算内。

`[+3, +6]` 已被共享层占满 ⇒ 本 Filter 与 CPU 降载（`+6`）**并列**。
并列安全的原因是那个老不变式：**本 Filter 绝不写响应** ⇒ 无论谁先谁后，响应结果完全相同。

### 4.3 Filter 第一步：这条路要不要验签？

`JwtAuthFilter.doFilter` 的第一段（`JwtAuthFilter.java:101-111`）：

```java
HttpServletRequest request = (HttpServletRequest) req;
String uri = request.getRequestURI();
if (JwtAuthPaths.isProtected(uri)) {
    JwtVerificationResult result = verifier.verify(request.getHeader("Authorization"), uri);
    request.setAttribute(VERIFICATION_ATTRIBUTE, result);
    request.setAttribute(VERIFICATION_SOURCE_ATTRIBUTE, SOURCE_FILTER);
    if (result.failed()) {
        // 🔴 只记录，**不写响应**（见类注释「本 Filter 绝不写响应」）。
        log.debug("JWT verification failed (deferred to interceptor) : {}", uri);
    }
}
```

这里有两个 attribute 被写进 request：

| attribute 名 | 常量 | 值 |
|---|---|---|
| `com.rag.user.jwt.verification` | `JwtAuthFilter.VERIFICATION_ATTRIBUTE`（`:78`） | `JwtVerificationResult` 对象 |
| `com.rag.user.jwt.verificationSource` | `JwtAuthFilter.VERIFICATION_SOURCE_ATTRIBUTE`（`:84`） | `"filter"`（`SOURCE_FILTER`，`:87`）或 `"inline"`（`SOURCE_INLINE`，`:90`） |

第二个 attribute 是**给自己人看的探针**：它记录「这次验签到底发生在 Filter 里，还是退回了 Interceptor 的兜底就地验签」。
守门测试 `UserApiHttpContractTest#jwtVerificationHappensInFilter`（`UserApiHttpContractTest.java:202-215`）
就是靠它断言「验签确实在 3s 预算之外」的 —— 它注册了一个 `+100` 的探针 Filter，把这个 attribute 抄进响应头
`X-Jwt-Verification-Source`。

### 4.4 `isProtected` 与 `isPublic`：两份判据为什么必须一模一样

`JwtAuthPaths` 是**唯一**一份路径判据（`JwtAuthPaths.java:34-83`）：

```java
public static final Set<String> PUBLIC_PATHS = Set.of(
        "/user/v1/register", "/user/v1/login"
);

private static final String PROTECTED_PREFIX = "/user/v1";

public static boolean isPublic(String uri) {
    return uri != null && PUBLIC_PATHS.contains(uri);
}

public static boolean isProtected(String uri) {
    if (uri == null) {
        return false;
    }
    if (isPublic(uri)) {
        return false;
    }
    return uri.equals(PROTECTED_PREFIX) || uri.startsWith(PROTECTED_PREFIX + "/");
}
```

注意 `isProtected` 的判定是 `/user/v1` **本身**或 `/user/v1/` 前缀 —— 它是 `WebMvcConfig` 注册的
`/user/v1/**` 的**超集**。类注释（`JwtAuthPaths.java:67-71`）解释了这个方向的选择：

> 少覆盖 ⇒ interceptor 拿不到 Filter 的验签结果（落兜底 ⇒ ② 不闭环）；
> **多覆盖 ⇒ 只会多做一次无用验签，不构成鉴权绕过**（拦截器仍按自己的判据放行）。

**为什么必须是同一份**：如果 Filter 和 Interceptor 各写一份判据，会出现两种缝隙 ——
「Filter 判为受保护、Interceptor 判为放行」= **鉴权被绕过**（安全漏洞）；
「Filter 判为 public、Interceptor 判为受保护」= Interceptor 拿不到 Filter 的结果 ⇒ 落进兜底分支
⇒ 验签退回 3s 预算**之内** ⇒ 一条已知漂移静默不闭环。

**这直接回答了一个初学者最容易混淆的问题**（也是 §5 的核心）：

> `addPathPatterns("/user/v1/**")` 决定的是「拦截器在**哪些路径上执行**」；
> `isPublic(uri)` 决定的是「在当前路径上**要不要鉴权**」。
> **执行了 ≠ 必须验签。**

- `/user/v1/login` 匹配了 `/user/v1/**` ⇒ Interceptor **会执行**；但 `isPublic` 为 true ⇒ 第一段就 `return true` ⇒ 不验签。
- `/user/v1/get` 匹配前缀且不是 public ⇒ 走完整鉴权链。

### 4.5 验签本体：`JwtSignatureVerifier.verify`

代码在 `JwtSignatureVerifier.java:71-131`。拆成四段看。

#### 4.5.1 阶段 1a：`Authorization` 头缺失（`:74-80`）

```java
if (authHeader == null || authHeader.isEmpty()) {
    log.warn("protected path {} without Authorization header", uri);
    return JwtVerificationResult.failed(new AuthenticationException(
            ErrCodes.ERROR_TOKEN_RUNTIME,
            ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME)
    ));
}
```

注意：这里**不是抛异常**，而是**返回一个装着异常对象的失败结果**。
原因见 §4.1 —— 异常必须由 Interceptor 重抛，才能被 `@ExceptionHandler` 接住。

业务码 `ERROR_TOKEN_RUNTIME` = **1006**，文案 **「TOKEN已过期」**（`service/users/common/.../ErrCodes.java:118` / `ErrMsg.java:66`）。
文案叫「已过期」但实际覆盖了「缺 header」「签名错」「空 token」三种情况 —— 这是 B 侧继承下来的口径。

#### 4.5.2 阶段 1b：剥 `Bearer ` 前缀（`:85-92`）

```java
String token = BearerTokenExtractor.tokenForSignatureVerification(authHeader);
if (token.isEmpty()) {
    log.warn("protected path {} with empty Bearer token", uri);
    return JwtVerificationResult.failed(new AuthenticationException(...));
}
```

**这个方法有坑，§5.1 专门讲。**

#### 4.5.3 阶段 1c：验签名 + 取 claim（`:94-125`）

```java
long uid = -1L;
boolean claimUsable = false;
try {
    Jws<Claims> jws = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token);
    Object claim = jws.getPayload().get("userId");
    if (claim instanceof Number) {
        uid = ((Number) claim).longValue();
        claimUsable = true;
        // ⚠️ 这里**不**做 uid <= 0 的拒绝判断 —— 那是业务层（IdentityResolver）的规则。
    } else {
        // 🔴 B 侧 DRIFT-USERAPI-001 已修：**不在鉴权层拒绝畸形 claim**。
        log.warn("JWT claim userId missing or wrong type : {}",
                claim == null ? "null" : claim.getClass());
    }
} catch (BusinessException e) {
    return JwtVerificationResult.failed(e);
} catch (Throwable t) {
    log.warn("JWT verify failed : {}", t.getMessage());
    return JwtVerificationResult.failed(new AuthenticationException(...));
}
```

三点必须说清：

**① `key` 是构造时算好的**（`JwtSignatureVerifier.java:53-63`）：

```java
this.key = Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(properties.getUserAuth().getAccessSecret()));
```

`padToHmacKeySize`（`common/.../JwtUtil.java:102-111`）把密钥补齐到 ≥32 字节（HS256 要求 key ≥ 256 bit），
签发端（`JwtUtil.getToken`）和验签端用的是**同一份**补齐逻辑 ⇒ 两边算出来的 key 一致。
补的是零字节，理由是 RFC 2104：HMAC 内部本来就会把短于分组尺寸的密钥零填充 ⇒ 补零前后 HMAC 结果逐字节相同。

**② claim 的类型容错**：

| claim 实际值 | `claim instanceof Number` | `uid` | `claimUsable` |
|---|---|---|---|
| `1001`（JSON number） | true | `1001` | **true** |
| `"1001"`（JSON string） | false | `-1`（保持初值） | **false** |
| 缺失（`null`） | false | `-1` | **false** |

⚠️ **claim 不对时，本类既不抛异常、也不拒绝请求** —— 只打一条 `warn`。
后果被推迟到业务层（§5.4）。

**③ `uid <= 0` 也不在鉴权层拒绝**。注释写得很直白：

> 这里**不**做 uid <= 0 的拒绝判断 —— 那是业务层（IdentityResolver）的规则。

**④ catch 分成两段**：`BusinessException` 原样带过去（不换成 1006），其它 `Throwable`（含 jjwt 的
`SignatureException` / `ExpiredJwtException`）统一换成 `AuthenticationException(1006)`。

#### 4.5.4 阶段 2：再剥一次 `Bearer `（`:127-130`）

```java
// 黑名单 key 用的 token —— Go 的 CheckBlacklistMiddleware 用的是【TrimPrefix 结果】，
// 与验签用的 token 是**两套取法**（Go 自身如此，畸形 header 下会不同）。
String trimmedToken = BearerTokenExtractor.trimBearerPrefix(authHeader);
return JwtVerificationResult.ok(uid, claimUsable, token, trimmedToken);
```

**同一个 header，被剥了两次，用了两个不同的方法** —— 这是本域最容易看错的地方，§5.1 专门讲。

最后组装出的 `JwtVerificationResult`（`JwtVerificationResult.java:29-35`）有 5 个分量：

```java
public record JwtVerificationResult(
        long uid,
        boolean claimUsable,
        String signatureToken,
        String trimmedToken,
        RuntimeException failure
) { ... }
```

失败时用工厂方法 `failed(...)`（`:46-48`），五个分量被固定成 `(-1L, false, "", "", failure)`。

### 4.6 Interceptor 第一步：public 路径直接放行

`JwtAuthInterceptor.preHandle` 的第一段（`JwtAuthInterceptor.java:74-79`）：

```java
String uri = request.getRequestURI();

// public 路径直接放行（与 Go 端 rest.AddRoutes(login, register) 不带 WithJwt 一致）
if (JwtAuthPaths.isPublic(uri)) {
    return true;
}
```

**public 路径在 Filter 和 Interceptor 两处都放行**：

| | Filter（`JwtAuthFilter.java:103`） | Interceptor（`JwtAuthInterceptor.java:77`） |
|---|---|---|
| `/user/v1/login` | `isProtected` 为 false ⇒ **根本不验签** | `isPublic` 为 true ⇒ **直接 return true** |

两处放行不是冗余，而是必须的：
Filter 侧不验签是**省掉一次无用的验签开销**；
Interceptor 侧放行是**保证公开端点不会被任何鉴权逻辑拦下**。
少任何一处都不行 —— 少了 Filter 侧，公开端点也要验签；少了 Interceptor 侧，
公开端点会因为「没有 `userId` attribute」在业务层被拒。

### 4.7 Interceptor 第二步：消费 Filter 的验签结果（`:81-93`）

```java
JwtVerificationResult result = (JwtVerificationResult) request.getAttribute(
        JwtAuthFilter.VERIFICATION_ATTRIBUTE);
if (result == null) {
    // 🔴 兜底：Filter 未命中（路径判据不一致 / Filter 未被注册）⇒ 就地验签。
    //   行为与 B 侧修复前完全一致（401 照常）⇒ **不会造成鉴权绕过**；
    //   但验签落回 3s 预算**之内** ⇒ 该路径上 ② 未闭环 ⇒ 必须大声记日志。
    log.warn("JWT 验签未命中 filter 缓存 ⇒ 就地验签（DRIFT-USERAPI-009② 在此路径未闭环）: {}", uri);
    request.setAttribute(JwtAuthFilter.VERIFICATION_SOURCE_ATTRIBUTE, JwtAuthFilter.SOURCE_INLINE);
    result = verifier.verify(request.getHeader("Authorization"), uri);
} else {
    request.setAttribute(JwtAuthFilter.VERIFICATION_SOURCE_ATTRIBUTE, JwtAuthFilter.SOURCE_FILTER);
}
```

**兜底分支的语义很重要**：

- **不会造成鉴权绕过** —— 兜底照样走完整的 `verify()` ⇒ 签名错照样 401；
- **但验签落回 3s 预算之内** ⇒ 这是 B 侧一条已知漂移（`DRIFT-USERAPI-009②`）在这个路径上没闭环 ⇒
  所以必须**大声记日志**（`warn` 而不是 `debug`）。

同时把来源标记成 `inline`（`:90`）⇒ 守门测试能从响应头看出「这次不是 Filter 验的」。

正常路径（`result != null`）把来源标成 `filter`（`:92`）。

### 4.8 Interceptor 第三步：失败原样重抛（`:95-98`）

```java
// 验签失败 ⇒ 原样重抛（异常对象由 Filter 产出 ⇒ @ExceptionHandler 仍管得到 ⇒ 401 信封形状不变）
if (result.failed()) {
    throw result.failure();
}
```

`result.failure()` 就是 Filter 里 `new AuthenticationException(1006, "TOKEN已过期")` 那个对象**本身**。

抛出去之后 → Spring MVC 的 `DispatcherServlet` 捕获 → 交给 `@RestControllerAdvice`
`UserApiExceptionHandler#handleAuthentication`（`advice/UserApiExceptionHandler.java:75-80`）⇒ **HTTP 401 + 完整信封**。

### 4.9 Interceptor 第四步：claim 可用才写 `userId` attribute（`:100-103`）

```java
// claim 可用才写 userId attribute（与 Go 的「逐个 claim 塞进 ctx」等价）
if (result.claimUsable()) {
    request.setAttribute(com.rag.users.common.IdentityResolver.CLAIM_ATTRIBUTE, Long.valueOf(result.uid()));
}
```

- attribute 名 = `IdentityResolver.CLAIM_ATTRIBUTE` = **`"userId"`**（`IdentityResolver.java:85`）。
  提成分量的原因：属性名是**拦截器 ↔ 解析器之间的契约**，user 子域和 admin 子域各有一个 JWT 拦截器 ⇒
  属性名若各写一份，就是 B 侧那种「同一规则的第二份实现」。
- value 类型是 **`Long`**（`Long.valueOf(result.uid())`）—— 这不是细节，`IdentityResolver` 校验的就是 `instanceof Long`（§4.11）。

⚠️ **claim 不可用时，这里什么都不写，但请求照样往下走**。
不是拒绝，是「不给你身份，你去业务层自证」。

### 4.10 Interceptor 第五步：查 Redis 黑名单（`:105-124`）

```java
String jwtToken = result.trimmedToken();
if (!jwtToken.isEmpty() && isBlacklisted(jwtToken)) {
    log.warn("token in blacklist, uid={}", result.claimUsable() ? result.uid() : "n/a");
    throw new AuthenticationException(
            ErrCodes.ERROR_TOKEN_RUNTIME,
            ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME)
    );
}

request.setAttribute("jwt_token", jwtToken);
if (!jwtToken.equals(result.signatureToken())) {
    // 与 Go 一致的不一致：非 "Bearer <token>" 规范写法下两段取法不同（Go 自身如此）。保留 warn 便于排查。
    log.warn("user blacklist token != jwt token (auth header is not 'Bearer <token>')");
}
return true;
```

**黑名单查询是 fail-open**（`:135-145`）：

```java
private boolean isBlacklisted(String token) {
    String blacklistKey = BLACKLIST_KEY_PREFIX + token;
    Boolean exists;
    try {
        exists = redisTemplate.hasKey(blacklistKey);
    } catch (Throwable t) {
        log.warn("Redis blacklist check failed : {}", t.getMessage());
        exists = null;
    }
    return exists != null && exists;
}
```

- key = `"user:jwt_blacklist:" + token`（前缀常量在 `:61`）；
- 查的是 `hasKey`（**只判存在，不取值**）；
- **Redis 故障 ⇒ 抛异常 ⇒ 被 catch ⇒ `exists = null` ⇒ `exists != null && exists` 为 false ⇒ 放行**。

这就是 fail-open：**Redis 挂掉的时候，黑名单检查不阻塞业务**。
理由写在类注释里（`JwtAuthInterceptor.java:39`）：与 B 侧 `err == nil && exists` 的行为一致。

⚠️ **安全上的取舍要讲明白**：fail-open 意味着 **Redis 故障期间，已注销的 token 仍能通过鉴权**。
这是继承自真源的行为，不是本仓的疏忽。

**写 `jwt_token` attribute（`:120`）在黑名单检查之后** —— 这就是注销流程能拿到 token 的原因：
`UserApiService#logout` 读的就是这个 attribute（`UserApiService.java:407`）。
而注销写黑名单时用的 key 也是这个 trimmed token（`UserDomainService.java:773`：`"user:jwt_blacklist:" + req.getToken()`）⇒
**写侧与读侧用的是同一套取法** ⇒ key 对得上。

### 4.11 业务侧：两个取 uid 的方法，差别是**行为**

`IdentityResolver`（`service/users/common/src/main/java/com/rag/users/common/IdentityResolver.java`）是**权威身份解析器**。
它有两个取 uid 的方法：

```java
public static long claimedUID(HttpServletRequest request) {
    long uid = claimedUIDAllowNonPositive(request);
    if (uid <= 0) {
        throw new ErrInvalidClaimException(MSG_INVALID_CLAIM);
    }
    return uid;
}

public static long claimedUIDAllowNonPositive(HttpServletRequest request) {
    if (request == null) {
        throw new ErrInvalidClaimException(MSG_INVALID_CLAIM);
    }
    Object v = request.getAttribute(CLAIM_ATTRIBUTE);
    if (!(v instanceof Long)) {
        // claim 缺失 / 类型不对 ⇒ 与 B 的 type assertion 失败同款
        throw new ErrInvalidClaimException(MSG_INVALID_CLAIM);
    }
    return (Long) v;
}
```
（`IdentityResolver.java:240-271`）

**三个判定**：

| 判定 | `claimedUID` | `claimedUIDAllowNonPositive` |
|---|---|---|
| `request == null` | 抛 `ErrInvalidClaimException` | 抛 `ErrInvalidClaimException` |
| attribute **不是 `Long`** | 抛 | 抛 |
| `uid <= 0` | **抛** | **不抛**（原样返回 0 或负数） |

#### 🔴 哪个端点用哪个 —— 不是随意的

grep 全部调用点（`service/users/`）：

| 端点 | 调用位置 | 用的方法 | `uid <= 0` 时 |
|---|---|---|---|
| `getUser` | `UserApiService.java:237` | `claimedUID` | 抛 → 1006 |
| `updateUser` | `UserApiService.java:305` | `claimedUIDAllowNonPositive` | **不抛**，uid=0 透传到领域层 |
| `deleteUser` | `UserApiService.java:361` | `claimedUIDAllowNonPositive` | **不抛**，uid=0 透传到领域层 |
| `uploadAvatar` | `UserApiService.java:458` | `claimedUID` | 抛 → 1006 |
| `selectAvatar` | `UserApiService.java:522` | `claimedUID` | 抛 → 1006 |
| `getAvatarHistory` | `UserApiService.java:590` | `claimedUID` | 抛 → 1006 |

`UserApiService` 里那两条调用点的注释写得很直白（`:303-304`）：

```java
// 🔴 用 claimedUIDAllowNonPositive，**不**做 uid<=0 判定
//   ⇒ uid=0 / 负数照常进领域层（最终由 NOT_FOUND 收敛成 1003）。
```

**两种语义的可见差别**：

| | `uid = 0` 的 token 打 `/user/v1/get` | 打 `/user/v1/update` |
|---|---|---|
| 走 `claimedUID` | 抛 `ErrInvalidClaimException` ⇒ 业务层转 `BusinessException(1006)` ⇒ **HTTP 200 + code 1006** | — |
| 走 `claimedUIDAllowNonPositive` | — | uid=0 进领域层 ⇒ 查不到人 ⇒ **gRPC NOT_FOUND** ⇒ 映射成 **1003**（用户不存在） |

⇒ 同一个「uid 不合法」的 token，在**读**端点得到 **1006**（token 运行时错误），
在**写**端点得到 **1003**（用户不存在）。这不是 bug，是继承自真源的行为差异 ——
`IdentityResolver` 的 javadoc 明说「不要合并这两个方法，合并等于擅自『修好』B 侧的不对称」。

### 4.12 密钥从哪来、为什么启动就会失败

密钥配置（`service/users/user/src/main/resources/application.yml:57-65`）：

```yaml
user-api:
  user-auth:
    access-secret: ${USER_JWT_SECRET}
    access-expire: ${USER_JWT_EXPIRE:2592000}    # 30 天

user-rpc:
  user-auth:
    access-secret: ${USER_JWT_SECRET}
    access-expire: ${USER_JWT_EXPIRE:2592000}
```

两个键都指向**同一个环境变量** ⇒ 不可能只配一边而漏掉另一边。
`access-expire` 有默认值 30 天；`access-secret` **没有默认值**。

绑定由 `UserApiProperties`（`config/UserApiProperties.java:38-44`）完成：

```java
@ConfigurationProperties(prefix = "user-api")
public class UserApiProperties {
    private String name = "user-api";

    @NestedConfigurationProperty
    private UserAuth userAuth = new UserAuth();
    ...
}
```

#### 🔴 两条 fail-closed 检查

`UserApiProperties.java:70-83`：

```java
@PostConstruct
void verifyAccessSecret() {
    String secret = userAuth == null ? null : userAuth.getAccessSecret();
    if (secret == null || secret.isBlank()) {
        throw new IllegalStateException(
                "user-api.user-auth.access-secret 不能为空：请设置环境变量 USER_JWT_SECRET（与 user-rpc 共享同一把签发密钥）"
                        + "—— 本模块既签发又验签，空密钥会让任何人都能伪造 JWT");
    }
    if (secret.contains("${")) {
        throw new IllegalStateException(
                "user-api.user-auth.access-secret 仍是未解析的占位符（" + secret + "）：请设置环境变量 USER_JWT_SECRET"
                        + "—— 占位符字面量是公开在仓里的常量，拿它当密钥 = 密钥公开");
    }
}
```

**为什么必须判「未解析的占位符」这一条**：

Spring 的占位符解析器 `ignoreUnresolvablePlaceholders = true` ⇒ 环境变量 `USER_JWT_SECRET` 没设时，
**不报错**，而是把字面量 `"${USER_JWT_SECRET}"` 原样绑上去。
只判「null / 空串」**堵不住这条路** ⇒ 必须显式拒绝「仍是占位符」的值。

**`@PostConstruct` 什么时候跑**：Spring 容器创建这个 bean、注入完属性之后、**bean 初始化完成之前**。
失败 ⇒ 容器启动失败 ⇒ 进程根本起不来。

⇒ 这就是「**fail-closed**」：不是「启动成功但验签永远失败」，而是**启动就失败**。
理由写在 `UserApiProperties.java:62-69`：本模块**既签发**（登录）**又验签**（拦截器）⇒ 空密钥 = 任何人可伪造 user token。

守门测试：`service/users/user/src/test/java/com/rag/user/config/UserApiPropertiesSecretGuardTest.java`
—— `apiNullSecretFailsFast` / `apiBlankSecretFailsFast` / `apiPlaceholderSecretFailsFast` / `apiValidSecretPasses`。

---

## 5. 重要分支与失败路径

### 5.1 🔴 最容易看错的地方：两段取 token 不一致

同一个 `Authorization` 头，被剥了两次，用了**两个不同的方法**，判定条件**不一样**：

| | `tokenForSignatureVerification` | `trimBearerPrefix` |
|---|---|---|
| 位置 | `common/.../BearerTokenExtractor.java:74-87` | `common/.../BearerTokenExtractor.java:101-105` |
| 用途 | **验签** | **黑名单 key** ＋ `jwt_token` attribute |
| 前缀判定 | `length() > 6` **且** 前 7 字符 `toUpperCase()` 后 == `"BEARER "` | `startsWith("Bearer ")` —— **大小写敏感** |
| 大小写 | **不敏感**（`bearer ` / `BEARER ` 都剥） | **敏感**（只有 `Bearer ` 才剥） |
| 不匹配时 | **原样返回整串** | **原样返回整串** |

源码：

```java
public static String tokenForSignatureVerification(String authHeader) {
    if (authHeader.length() > BEARER_PREFIX.length() - 1
            && BEARER_PREFIX_UPPERCASE.equals(
                    authHeader.substring(0, BEARER_PREFIX.length()).toUpperCase(Locale.ROOT))) {
        return authHeader.substring(BEARER_PREFIX.length());
    }
    return authHeader;
}

public static String trimBearerPrefix(String authHeader) {
    return authHeader.startsWith(BEARER_PREFIX)
            ? authHeader.substring(BEARER_PREFIX.length())
            : authHeader;
}
```

`BEARER_PREFIX = "Bearer "`（`:35`）、`BEARER_PREFIX_UPPERCASE = "BEARER "`（`:45`）。

> 为什么要保留一个大写常量而不是直接拿 `"Bearer "` 去比：比较字面量一侧本来就是大写，
> 被比较的一侧做了 `ToUpper` ⇒ 直接拿 `"Bearer "` 比会**恒为 false** ⇒ 所有规范请求全部误拒为 401。

#### 逐条走一遍各种 header

| `Authorization` 头 | 验签用的 token | 黑名单用的 token | 两者相等？ | 结果 |
|---|---|---|---|---|
| `Bearer eyJ...`（规范） | `eyJ...` | `eyJ...` | ✅ | 正常 |
| `bearer eyJ...`（小写） | `eyJ...` | `bearer eyJ...`（整串） | ❌ | **验签通过**；黑名单查的 key 是 `user:jwt_blacklist:bearer eyJ...` ⇒ 查不到 |
| `BEARER eyJ...` | `eyJ...` | `BEARER eyJ...`（整串） | ❌ | 同上 |
| `Bearer `（正好 7 字符） | `""`（空） | `""` | ✅ | **验签前就失败**（`token.isEmpty()` ⇒ 1006） |
| `Bearer`（6 字符，无空格） | `Bearer`（整串，`length > 6` 不满足） | `Bearer`（整串） | ✅ | 验签失败（不是合法 JWT） |
| `Token eyJ...` | `Token eyJ...`（整串） | `Token eyJ...`（整串） | ✅ | 验签失败 |

**分叉的实际后果**（`JwtAuthInterceptor.java:121-124` 会打一条 `warn`）：

小写 `bearer` 时，**黑名单 key 与注销时写入的 key 对不上** ——
注销写的是 `"user:jwt_blacklist:" + trimmedToken`，而拦截器读的也是 trimmedToken ⇒ 两边一致；
但 trimmedToken 本身是 `"bearer eyJ..."` ⇒ 这个 key **永远不会被写入** ⇒
**用小写 `bearer` 发的请求，注销后仍然能通过黑名单检查**。

这不是本仓的 bug，是**真源自身的不一致**（`BearerTokenExtractor.java:94-99` 的 javadoc 明确写了：
「与 `tokenForSignatureVerification` 的差异是 B 侧 自身的不一致，不是笔误」）。

> ⚠️ **`"Bearer "` 正好 7 字符这条还有一层历史**：`BearerTokenExtractor.java:75-80` 的注释记录了
> 「RAG 侧是 `> 7`，B 侧 是 `> 6`」的分叉，本仓按 **B 侧**（`> 6`）实现。
> 两种写法都让这种畸形头**验签失败** ⇒ 没有安全洞，差别只在「传给验签器的是哪个串」。

#### 为什么不能「顺手统一成一套」

`BearerTokenExtractor.java:8-11` 的注释说得很清楚：

> B 的鉴权链由两个中间件组成，它们对同一个 header 用了**不同**的取法，
> 且这个差异在畸形输入下会真实分叉。两套都必须各自逐字对齐，
> **合并即等于擅自修好 B 的 bug —— 那会制造一处新的语义漂移。**

### 5.2 claim 类型不对：不拒绝，但也不给身份

**触发**：签名合法且未过期，但 payload 里 `userId` 不是数字（是字符串，或干脆没有这个 claim）。

**控制流**：

```mermaid
flowchart TD
    A["JwtSignatureVerifier.verify<br/>claim = payload.get('userId')"] --> B{"claim instanceof Number ?"}
    B -->|否| C["log.warn('JWT claim userId missing or wrong type')<br/>uid 保持 -1，claimUsable = false<br/>**不抛异常**"]
    B -->|是| D["uid = claim.longValue()<br/>claimUsable = true"]
    C --> E["JwtVerificationResult.ok(...)"]
    D --> E
    E --> F["JwtAuthInterceptor.preHandle<br/>result.failed() == false ⇒ 继续"]
    F --> G{"result.claimUsable() ?"}
    G -->|false| H["**不写** userId attribute<br/>请求继续往下走"]
    H --> I["Controller → UserApiService"]
    I --> J["IdentityResolver.claimedUID(request)<br/>attribute 不是 Long ⇒ 抛 ErrInvalidClaimException"]
    J --> K["UserApiService catch ErrInvalidClaimException"]
    K --> L["throw BusinessException(ERROR_TOKEN_RUNTIME = 1006)"]
    L --> M["UserApiExceptionHandler#handleBusiness<br/>decideStatus(1006) = HTTP 200"]
    M --> N["响应：{code:1006, msg:'TOKEN已过期', data:null}"]
    G -->|true| O["写 userId attribute ⇒ 业务层拿到 uid"]
```

**为什么不在鉴权层直接 401**：`JwtAuthInterceptor` 的类注释（`:42-47`）写明了理由 ——
这是 B 侧 `DRIFT-USERAPI-001` 修过的语义：go-zero 的 `rest.WithJwt` 只验签名与 exp，claim 校验属于 identity 层。
⇒ 畸形 claim 的后果发生在业务层 ⇒ **HTTP 200 + 业务码 1006**。

⚠️ **注意黑名单检查仍然会执行**（`JwtSignatureVerifier.java:110-111`）：
「畸形 claim + 已拉黑」在 B 侧也是 401，本类同样先查黑名单 ⇒ 一致。

### 5.3 完整失败分支表

| 触发条件 | 发生在哪一层 | 异常 / 结果 | 最终响应 |
|---|---|---|---|
| `Authorization` 头缺失或为空 | Filter 验签 | `AuthenticationException(1006)` 被装进结果，Interceptor 重抛 | **HTTP 401** + `{code:1006,msg:"TOKEN已过期",data:null}` |
| 剥完 Bearer 后 token 为空 | Filter 验签 | 同上 | HTTP 401 |
| 签名错 / token 过期 | Filter 验签（jjwt 抛异常） | 同上 | HTTP 401 |
| claim 不是数字 | Filter 验签 | **不抛**，不写 `userId` | 业务层 ⇒ HTTP 200 + `code 1006` |
| token 在 Redis 黑名单 | Interceptor | `AuthenticationException(1006)` | HTTP 401 |
| Redis 故障 | Interceptor | **不抛**（fail-open） | 放行，业务继续 |
| `uid <= 0`（claim 是数字但非正） | 业务层 | `claimedUID` 抛 ⇒ 1006；`claimedUIDAllowNonPositive` 不抛 | HTTP 200 + `code 1006` / 或继续到下游 |
| Filter 未命中（attribute 为 null） | Interceptor | 兜底就地验签 + `warn` | 行为不变，但验签回到 3s 预算内 |
| public 路径 | Filter + Interceptor | 都不验签 | 正常进入业务层 |

### 5.4 `userId` attribute 缺失 ⇒ 业务层为什么是 1006 而不是 401

这是「鉴权层」与「业务层」的分界点，**必须记牢**：

| 层 | 判断什么 | 失败形态 |
|---|---|---|
| 鉴权层（Filter + Interceptor） | token 是不是我签的、是不是被拉黑的 | HTTP **401** |
| 身份层（`IdentityResolver`） | claim 在不在、类型对不对、uid 是不是正数 | HTTP **200** + 业务码 **1006** |

同一个码 1006 在两层都可能出现 —— 但**状态码不同**。
`AuthenticationException` 的 javadoc（`common/.../AuthenticationException.java:15-18`）解释了为什么必须用**类型**分层而不是用码分层：

> 更麻烦的是**不能靠业务码区分层次**：1006 在两条路径上都可能出现
> （拦截器的鉴权失败 = 应 401；Service 的 token 失效 = B 侧同样是 envelope + 200）。
> 按 code 分流会把两层重新搅在一起，正是这次缺陷的成因。
> **所以层次必须由类型承载。**

### 5.5 两个 attribute 的完整生命周期

```mermaid
flowchart LR
    subgraph 写入方
        W1["JwtAuthFilter (+6)<br/>写 com.rag.user.jwt.verification<br/>写 com.rag.user.jwt.verificationSource"]
        W2["JwtAuthInterceptor (preHandle)<br/>写 userId（仅 claimUsable=true）<br/>写 jwt_token（无条件）"]
    end
    subgraph 读取方
        R1["IdentityResolver.claimedUID<br/>读 userId"]
        R2["UserApiService#logout<br/>读 jwt_token"]
    end
    W1 -->|"同请求内传递"| W2
    W2 --> R1
    W2 --> R2
```

| attribute | 写入者 | 写入条件 | 读取者 | 值类型 |
|---|---|---|---|---|
| `com.rag.user.jwt.verification` | `JwtAuthFilter` | protected 路径（`isProtected` 为 true） | `JwtAuthInterceptor` | `JwtVerificationResult` |
| `com.rag.user.jwt.verificationSource` | Filter（`filter`）或 Interceptor 兜底（`inline`） | 总是写 | 守门测试的探针 Filter | `String` |
| `userId` | `JwtAuthInterceptor` | **仅** `claimUsable == true` | `IdentityResolver.claimedUID*` | **`Long`** |
| `jwt_token` | `JwtAuthInterceptor` | 总是写（黑名单检查**之后**） | `UserApiService#logout` | `String` |

⚠️ **`userId` 写入失败 ⇒ `jwt_token` 仍然会写**（因为写 `jwt_token` 在黑名单检查之后，与 claim 无关）。
所以「claim 畸形」的请求，注销端点仍能拿到 token 字符串，但业务层一样会因为 `claimedUID` 失败而拒绝。

---

## 6. 数据变化与副作用

### 6.1 副作用清单

| 类别 | 本流程中是否发生 | 说明 |
|---|---|---|
| **数据库读写** | ❌ 不发生 | 鉴权链**完全不碰数据库**。查用户是业务层的事（`IdentityResolver.verifyUser` 或领域层） |
| **Redis 读** | ✅ 发生（protected 路径） | `hasKey("user:jwt_blacklist:" + trimmedToken)`，**只判存在不取值** |
| **Redis 写** | ❌ 不发生 | 写黑名单是注销流程的事（06 号文档） |
| **事务** | ❌ 不发生 | 鉴权链里没有任何 `@Transactional` 方法；也没有数据库连接被打开 |
| **远程调用** | ❌ 不发生 | 无 gRPC / HTTP / Nacos / MinIO 调用。**注意**：后面 `UserApiService` 调 `UserDomainService` 是**同进程 Java 调用**，不是 gRPC 网络请求 |
| **消息** | ❌ 不发生 | 无 Kafka 生产/消费 |
| **日志** | ✅ 发生 | 见下表 |
| **指标** | ❌ **不发生** | 鉴权链里**没有**任何 `MeterRegistry` / `Counter` 调用 ⇒ 401 这类鉴权失败**不产生指标**。指标只在 `UserApiService` 的具体 catch 块里打点（如 `loginRpcError` / `registerRpcError`），鉴权失败不经过那些块 |
| **ID 生成** | ❌ 不发生 | 无 Snowflake 调用（那是注册/头像流程的事） |
| **Token 生成** | ❌ 不发生 | 本流程只**验证** token，不签发。签发在 `UserApiService#login`（01 号文档 §4.11） |

### 6.2 日志明细

| 日志级别 | 类 | 触发 | 内容 |
|---|---|---|---|
| `warn` | `JwtSignatureVerifier` | `Authorization` 头缺失 | `protected path {} without Authorization header` |
| `warn` | `JwtSignatureVerifier` | 剥完 Bearer 后 token 为空 | `protected path {} with empty Bearer token` |
| `warn` | `JwtSignatureVerifier` | claim 缺失 / 类型不对 | `JWT claim userId missing or wrong type : {}` |
| `warn` | `JwtSignatureVerifier` | 验签异常（签名错 / 过期） | `JWT verify failed : {}` |
| `debug` | `JwtAuthFilter` | 验签失败（**只记录，不拒绝**） | `JWT verification failed (deferred to interceptor) : {}` |
| `warn` | `JwtAuthInterceptor` | Filter 未命中 | `JWT 验签未命中 filter 缓存 ⇒ 就地验签（...）: {}` |
| `warn` | `JwtAuthInterceptor` | token 在黑名单 | `token in blacklist, uid={}` |
| `warn` | `JwtAuthInterceptor` | Redis 查询异常 | `Redis blacklist check failed : {}` |
| `warn` | `JwtAuthInterceptor` | 两段 token 不一致 | `user blacklist token != jwt token (auth header is not 'Bearer <token>')` |

⚠️ **鉴权失败在日志里是 `warn` / `debug`，不是 `error`** —— 因为 401 是**预期内的客户端行为**，不是服务端故障。

### 6.3 数据流转（request attribute 的形状）

一次成功的 protected 请求结束后，request 上挂着 4 个 attribute：

```
com.rag.user.jwt.verification          = JwtVerificationResult(
                                            uid = 1001L,
                                            claimUsable = true,
                                            signatureToken = "eyJ...",
                                            trimmedToken = "eyJ...",
                                            failure = null)
com.rag.user.jwt.verificationSource    = "filter"
userId                                  = 1001L          ← Long，不是 Integer
jwt_token                               = "eyJ..."
```

⚠️ **`userId` 的类型必须是 `Long`**：`IdentityResolver.claimedUIDAllowNonPositive` 判的是
`v instanceof Long`（`IdentityResolver.java:266`）⇒ 如果拦截器写成 `Integer`，
`instanceof Long` 为 false ⇒ 会被当成「claim 类型不对」⇒ 1006。
这也是 `JwtAuthInterceptor.java:102` 用 `Long.valueOf(...)` 而不是 `int` 装箱的原因。

---

## 7. 执行结果

### 7.1 成功（protected 路径 + 合法 token）

请求：

```
POST /user/v1/get
Authorization: Bearer eyJ...
Content-Type: application/json

{}
```

响应：

```http
HTTP/1.1 200
Content-Type: application/json

{"code":200,"msg":"OK","data":{"user":{"uid":"1001","score":0,"username":"alice","email":"a@example.com","extra_info":{}},"found":true}}
```

（信封由 `UserApiResponseAdvice` 包装，详见 [`./11-HTTP统一响应与异常处理流程.md`](./11-HTTP统一响应与异常处理流程.md) §4。

⚠️ 顺带记住 `data` 里 `user` 的 wire 形状：`uid` 是**字符串**（`@JsonSerialize(using = ToStringSerializer.class)`）、
`score` 恒为数字（原始 `long`）、`email` 与 `extra_info` 带 `@JsonInclude(NON_NULL)` ⇒ 为 null 时**键会消失**。
见 `dto/user/UserInfo.java`。）

### 7.2 失败 · 缺 Authorization（protected 路径）

```http
HTTP/1.1 401
Content-Type: application/json

{"code":1006,"msg":"TOKEN已过期","data":null}
```

### 7.3 失败 · 签名错

```http
HTTP/1.1 401
Content-Type: application/json

{"code":1006,"msg":"TOKEN已过期","data":null}
```

（与 7.2 **完全同形** —— 客户端分不出「没带 token」和「token 是假的」。这是有意的。）

### 7.4 失败 · claim 不是数字（签名合法）

```http
HTTP/1.1 200
Content-Type: application/json

{"code":1006,"msg":"TOKEN已过期","data":null}
```

⚠️ **注意 HTTP 状态码是 200，不是 401** —— 这是本篇最容易记错的一处。
「token 签名是对的，但里面的身份字段不对」属于**业务层判定**，不属于鉴权层判定。

### 7.5 失败 · token 在黑名单

```http
HTTP/1.1 401
Content-Type: application/json

{"code":1006,"msg":"TOKEN已过期","data":null}
```

### 7.6 失败 · uid = 0 打 `/user/v1/get`（`claimedUID`）

```http
HTTP/1.1 200
Content-Type: application/json

{"code":1006,"msg":"TOKEN已过期","data":null}
```

### 7.7 失败 · uid = 0 打 `/user/v1/update`（`claimedUIDAllowNonPositive`）

uid=0 会透传到领域层 ⇒ 查无此人 ⇒ gRPC `NOT_FOUND` ⇒ 通用映射 ⇒ 1003：

```http
HTTP/1.1 200
Content-Type: application/json

{"code":1003,"msg":"用户不存在","data":null}
```

⇒ **同一个 token、同一个 `code 1006` 语义，在读端点和写端点得到不同的业务码。**

---

## 8. 流程复盘

### 8.1 走过的代码

```
HTTP 请求
  ↓
Tomcat
  ↓
Servlet Filter 链
  ├─ PathNormalizing(+2) → Traceparent(+3) → MaxConns(+4)
  ├─ RouteIsolation(+5) → CpuShedding(+6)
  └─ JwtAuthFilter(+6)                    ← 验签
       ├─ JwtAuthPaths.isProtected(uri)
       ├─ JwtSignatureVerifier.verify()
       │    ├─ BearerTokenExtractor.tokenForSignatureVerification(authHeader)
       │    ├─ Jwts.parser().verifyWith(key).parseSignedClaims(token)
       │    ├─ payload.get("userId") → instanceof Number ?
       │    └─ BearerTokenExtractor.trimBearerPrefix(authHeader)
       └─ request.setAttribute("com.rag.user.jwt.verification", result)
  ↓
HttpMiddleware(+7) 起算 3s 预算
  ↓
MaxBytes(+8) → JsonBodyLimit(+9)
  ↓
DispatcherServlet
  ↓
HandlerInterceptor.preHandle            ← 授权
  ├─ JwtAuthPaths.isPublic(uri) → return true
  ├─ 读 "com.rag.user.jwt.verification"（null ⇒ 兜底就地验签 + warn）
  ├─ result.failed() → throw result.failure()      ← AuthenticationException
  ├─ result.claimUsable() → setAttribute("userId", Long)
  ├─ Redis hasKey("user:jwt_blacklist:" + trimmedToken)  ← fail-open
  └─ setAttribute("jwt_token", trimmedToken)
  ↓
Controller → UserApiService
  ↓
IdentityResolver.claimedUID / claimedUIDAllowNonPositive   ← 读 "userId"
  ↓
业务代码拿到 uid
```

### 8.2 边界结论

1. **Filter 只验签，Interceptor 才授权。** 前者在 `DispatcherServlet` 外面（为了把验签移出 3s 预算），
   后者在里面（黑名单检查要留在预算内）。
2. **Filter 绝不写响应。** 否则 401 会压过 `MaxBytes` 应该写的 413 ⇒ 与真源语义不符。
3. **失败以异常对象传递。** 压成标志位就会让 `@ExceptionHandler` 管不到 ⇒ 401 的 body 会从信封退化成空 body。
4. **`addPathPatterns` 决定「在哪跑」，`isPublic` 决定「要不要鉴权」。** 两者是不同的问题。
5. **public 路径在 Filter 与 Interceptor 两处都放行。** 少任何一处都不行。
6. **claim 的存在性与取值都不在鉴权层拒绝。** 鉴权层只判「签名 + 过期」；claim 畸形的后果是 HTTP 200 + 1006。
7. **`uid <= 0` 也不在鉴权层拒绝。** 那是 identity 层的规则，而且**两个端点用不同规则**。
8. **两段取 token 的方法不同是刻意的。** 统一它们等于擅自「修好」真源的 bug ⇒ 制造新漂移。
9. **黑名单查询 fail-open。** Redis 故障不阻塞业务（代价：故障期间已注销 token 仍可用）。
10. **密钥是 fail-closed 的。** 空串或未解析占位符 ⇒ **启动直接失败**，不是「启动成功但验签永远失败」。

---

## 9. 源码定位与验证方式

### 9.1 源码定位

| 位置 | 文件 : 行 | 内容 |
|---|---|---|
| Filter 验签 | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthFilter.java:98-116` | `doFilter`：protected 才验签、只写 attribute、无条件放行 |
| Filter 顺序 | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthFilter.java:71-72` | `@Component` + `@Order(HIGHEST_PRECEDENCE + 6)` |
| attribute 名 | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthFilter.java:78, 84, 87, 90` | `VERIFICATION_ATTRIBUTE` / `VERIFICATION_SOURCE_ATTRIBUTE` / `SOURCE_FILTER` / `SOURCE_INLINE` |
| Interceptor 授权 | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthInterceptor.java:72-126` | `preHandle` 全流程 |
| 黑名单 fail-open | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthInterceptor.java:135-145` | `isBlacklisted`：异常吞成 `null` ⇒ 放行 |
| 黑名单 key 前缀 | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthInterceptor.java:61` | `"user:jwt_blacklist:"` |
| 验签本体 | `service/users/user/src/main/java/com/rag/user/interceptor/JwtSignatureVerifier.java:71-131` | `verify`：四段流程 |
| claim 类型容错 | `service/users/user/src/main/java/com/rag/user/interceptor/JwtSignatureVerifier.java:101-114` | `instanceof Number` ⇒ `claimUsable` |
| 密钥构造 | `service/users/user/src/main/java/com/rag/user/interceptor/JwtSignatureVerifier.java:53-63` | `Keys.hmacShaKeyFor(padToHmacKeySize(secret))` |
| 验签结果载体 | `service/users/user/src/main/java/com/rag/user/interceptor/JwtVerificationResult.java:29-53` | 5 分量 record + `ok` / `failed` |
| 路径判据 | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthPaths.java:41-43, 50, 60-62, 75-83` | `PUBLIC_PATHS` / `PROTECTED_PREFIX` / `isPublic` / `isProtected` |
| 拦截器注册 | `service/users/user/src/main/java/com/rag/user/config/WebMvcConfig.java:42-46` | `addPathPatterns("/user/v1/**")` |
| Bearer 提取（两套） | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java:74-87, 101-105` | `tokenForSignatureVerification` / `trimBearerPrefix` |
| JWT 签发与补齐 | `common/src/main/java/com/rag/common/security/JwtUtil.java:31-48, 102-111` | `getToken`（HS256 / claim `userId`）/ `padToHmacKeySize` |
| 鉴权异常类型 | `common/src/main/java/com/rag/common/exception/AuthenticationException.java:50-54` | `extends BusinessException` ⇒ 401 |
| 业务异常类型 | `common/src/main/java/com/rag/common/exception/BusinessException.java:33-45` | `code` + `msg` |
| 身份解析器 | `service/users/common/src/main/java/com/rag/users/common/IdentityResolver.java:85, 240-246, 261-271, 296-304, 323-337` | `CLAIM_ATTRIBUTE` / `claimedUID` / `claimedUIDAllowNonPositive` / `resolveUser` / `verifyUser` |
| 5 个 sentinel | `service/users/common/src/main/java/com/rag/users/common/IdentityResolver.java:362-394` | `ErrInvalidClaim` / `ErrUserNotFound` / `ErrIdentityMismatch` / `ErrUserInactive` / `ErrStatusUnavailable` |
| 密钥 fail-closed | `service/users/user/src/main/java/com/rag/user/config/UserApiProperties.java:38, 70-83` | `@ConfigurationProperties("user-api")` / `@PostConstruct verifyAccessSecret` |
| 配置值 | `service/users/user/src/main/resources/application.yml:57-65` | `user-api.user-auth.access-secret` / `access-expire` |
| 端点取 uid | `service/users/user/src/main/java/com/rag/user/service/UserApiService.java:237, 305, 361, 458, 522, 590` | 6 个调用点：哪些用 `claimedUID`、哪些用 `AllowNonPositive` |
| 注销读 token | `service/users/user/src/main/java/com/rag/user/service/UserApiService.java:407` | `httpRequest.getAttribute("jwt_token")` |
| 注销写黑名单 | `service/users/user/src/main/java/com/rag/user/domain/service/UserDomainService.java:773` | `"user:jwt_blacklist:" + req.getToken()` |

### 9.2 怎么自己验证

**真 HTTP 契约测试**：`service/users/user/src/test/java/com/rag/user/api/UserApiHttpContractTest.java`
（**真 Tomcat**，不是 MockMvc）：

| 测试方法 | 验证了什么 |
|---|---|
| `publicLoginPathDoesNotRequireJwt` | public 路径无 `Authorization` 也能走到业务层（**不是 401**）⇒ 证明 Filter 与 Interceptor 两处都放行 |
| `protectedPathWithoutTokenIs401` | protected 路径缺 `Authorization` ⇒ **HTTP 401** + body 里 `code=1006` + `data` 字段存在 |
| `protectedPathWithGarbageTokenIs401` | 带坏签名 ⇒ **HTTP 401** |
| `jwtVerificationHappensInFilter` | 响应头 `X-Jwt-Verification-Source == "filter"` ⇒ **验签确实在 Filter 里**（不是 Interceptor 兜底） |
| `validSignatureButUnknownUserIsBusinessError` | 签名合法但用户不存在 ⇒ **HTTP 200 + code 1003** ⇒ 鉴权层与业务层分界 |
| `unknownPathInsideUserPrefixIs401` | `/user/v1/**` 下的未知路径 ⇒ **401**（拦截器先于 404 触发） |

**密钥守卫测试**：`service/users/user/src/test/java/com/rag/user/config/UserApiPropertiesSecretGuardTest.java`
—— `apiNullSecretFailsFast` / `apiBlankSecretFailsFast` / `apiPlaceholderSecretFailsFast` / `apiValidSecretPasses`。

**装配层测试**：`service/users/user/src/test/java/com/rag/user/UserServiceApplicationTests.java`
—— `jwtTwoStageBeansExist`（`Filter` + `Interceptor` 两个 bean 都在容器里）。

**身份解析器测试**：`service/users/common/src/test/java/com/rag/users/common/IdentityResolverTest.java`
—— `claimedUID` 拒 `uid<=0`、`claimedUIDAllowNonPositive` 不拒、两者都拒「claim 缺失 / 类型不对」。

**手工跑一遍**：

```bash
export USER_JWT_SECRET='<任意至少 32 字节的字符串>'
# 先 POST /user/v1/login 拿到 token，再用它打 protected 端点

# 正常
curl -i -X POST http://localhost:8881/user/v1/get \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -d '{}'

# 缺 Authorization ⇒ 401
curl -i -X POST http://localhost:8881/user/v1/get \
  -H 'Content-Type: application/json' -d '{}'

# 小写 bearer ⇒ 验签能过，但服务端会打 warn（两段取法不一致）
curl -i -X POST http://localhost:8881/user/v1/get \
  -H 'Content-Type: application/json' \
  -H "Authorization: bearer $TOKEN" -d '{}'
```

---

## 10. 交叉引用

- 本域文档索引与学习顺序：[`./README.md`](./README.md)
- JWT **签发**（登录端点怎么造 token）：[`./01-用户登录流程.md`](./01-用户登录流程.md) §4.11
- 统一响应与异常处理（本文的 401 / 1006 最终怎么变成 HTTP 响应）：
  [`./11-HTTP统一响应与异常处理流程.md`](./11-HTTP统一响应与异常处理流程.md) §5
- Redis 黑名单的**写**（注销）：[`./06-用户注销与黑名单流程.md`](./06-用户注销与黑名单流程.md)
- 身份判定链六步（查用户 / 存在性 / UID 一致 / status presence / 活跃）：
  [`./03-用户信息查询流程.md`](./03-用户信息查询流程.md)
