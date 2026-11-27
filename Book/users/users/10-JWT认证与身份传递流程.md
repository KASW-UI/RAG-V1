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

---

## 11. 从需求到实现：如果让我重新开发「JWT 认证与身份传递」

> **本章与前 10 章的分工**
> 第 1~10 章回答的是「这套代码跑起来时，一个受保护请求的身份**是怎么被确定下来**的」。
> 本章反过来回答：**如果我只拿到一句系统要求，我该怎么一步步把它推导出上面那套机制。**
>
> ⚠️ **本章的对象是「机制」，不是「端点」。**
> 01~09 号文档的对象是「登录 / 注册 / 查询 / 头像」这些**业务**；本章的对象是**所有业务共用的一条链路**（「请求进来 ⇒ 身份被确定 ⇒ 业务代码拿到 uid」）⇒ 因此本章**不**用「某个端点应该返回什么」来推导，而是用「**这套机制必须满足哪些系统要求**」来推导。
>
> 本章**不重复**前面的运行流程，只讲**推导过程**：先定行为 → 再定组件 → 再写代码 → 再验证 → 出问题怎么查 → 需求变了怎么改。

⚠️ **本章没有原始需求文档。** 整个仓库里**不存在**「JWT 认证设计文档 / 鉴权方案」这类文件
⇒ ⇒ 本章的「需求」是**从现有行为反向还原出来的需求模型**，**不是**原开发者真实的需求记录。
⇒ ⇒ 所以本章里凡属「原作者当初为什么这么设计」的判断，一律标【存疑】，不标【事实】。
⇒ ⇒ 这也正是本章存在的意义：**从行为反推需求，再从需求正推实现** —— 如果反推出来的需求推不回现有实现，那说明我漏了某个约束。

### 11.0 本章的证据分级（通篇使用）

| 标记 | 含义 | 本章用它回答什么 |
|---|---|---|
| 【事实】 | 可在**当前仓库源码**里直接确认的行为 | 「它现在**是什么**」——附 `文件:行号` |
| 【契约】 | 可在 **proto / 建表 SQL / 共享层 / B 侧台账**里确认的要求 | 「它**必须**是什么」——附契约出处 |
| 【推导】 | 由前两类推出的设计要求 | 「它**应该**是什么」——**不冒充原作者的意图** |
| 【存疑】 | 现有资料不足以确定 | 明说「不知道」，不编 |

### 11.A 从需求推导业务行为

#### 11.A.1 手上的原始输入，和它到底有多原始

如果我现在只接到一句系统要求，它长这样：

> 「受保护的接口只接受携带合法 token 的请求；业务代码要能拿到『我是谁』。」

这句话**写不出一行代码**，因为它没有回答任何一条**可被测试**的问题：

- 哪些接口是「受保护的」？哪些不是？**谁**来定义这个集合？
- 「合法」包含哪几件事？签名对？没过期？没被拉黑？claim 里有 `userId`？
- 「拿到我是谁」是**在哪一步**拿到的？验签一次，还是每个业务方法各验一次？验签**耗时**算不算进请求的超时预算？
- 鉴权失败时，响应长什么样？HTTP 401？body 是什么形状？
- `userId` 这个 claim **不是数字**（或是 0 / 负数）时，算「鉴权失败」还是「业务失败」？
- 密钥没配的时候，进程该不该起来？

⇒ **第一步不是写代码，是把这句话变成一组可判定的行为。**

#### 11.A.2 推导的七个问题，以及每个问题的答案出处

| # | 我必须先回答的问题 | 本项目里的答案 | 证据等级 | 依据 |
|---|---|---|---|---|
| Q1 / Q2 | 谁发起？输入是什么？ | 匿名或已登录客户端；输入只有 `Authorization: Bearer <token>` 一个头 | 【契约】/【事实】 | `JwtAuthPaths.java:41-43` ＋ `JwtSignatureVerifier.java:71-92` |
| Q3 | 系统要执行哪些动作？ | 判路径 → 验签 → 取 claim → 查黑名单 → 写 `userId` / `jwt_token` attribute | 【事实】 | `interceptor/JwtAuthFilter.java:98-116` ＋ `JwtAuthInterceptor.java:72-126` |
| Q4 | 什么算成功？ | 请求进业务层，且 request attribute 上有 `userId`（**`Long`**） | 【事实】 | `JwtAuthInterceptor.java:101-103` ＋ `IdentityResolver.java:85` |
| Q5 / Q6 | 什么必须拒绝？边界与失败？ | 缺 header / 空 token / 签名错 / 过期 / 在黑名单 ⇒ **HTTP 401**；而 claim 不是数字（**不拒绝**）· `uid<=0`（**不在鉴权层拒绝**）· Redis 故障（**fail-open**）· Filter 未命中（兜底 inline） | 【事实】 | `advice/UserApiExceptionHandler.java:82-87` ＋ 见 11.A.3 |
| Q7 | 哪些行为必须保持？ | public/protected 集合 · HS256 + claim 名 `userId` · 401 的信封形状 · **401 晚于 413** · 密钥 fail-closed | 【契约】 | `common/security/JwtUtil.java:31-48` ＋ `config/UserApiProperties.java:71-84` |

> 🔴 **Q6 里最反直觉的一条**：「claim 不是数字」**不是**鉴权失败 ⇒ 它是**业务层**的判定 ⇒ 后果是 **HTTP 200 + 业务码 1006**，不是 401 ⇒ 依据：`JwtSignatureVerifier.java:106-114`（只打 `warn`，不抛异常）＋ `JwtAuthInterceptor.java:101-103`（不写 `userId` attribute，但**继续往下走**）。

#### 11.A.3 行为清单（这是本章后面所有推导的**唯一输入**）

| 编号 | 行为 | 成功判据 | 失败判据 / 形态 | 证据等级 | 依据 |
|---|---|---|---|---|---|
| **B-01 / B-02** | public / protected 路径划分，且**判据只有一份** | 只有 `register` / `login` 是 public，其余 7 个 protected；Filter 与 Interceptor 调**同一个** `JwtAuthPaths` | 两处各写一份 ⇒ 出现鉴权缝隙 | 【契约】 | `interceptor/JwtAuthPaths.java:34,41-43,50,75-83` |
| **B-03** | **Filter 绝不写响应** | Filter 只写 attribute、然后无条件放行 | Filter 自己写 401 ⇒ 401 会压过 `MaxBytes` 的 413 ⇒ 与 B 侧语义不符 | 【事实】 | `JwtAuthFilter.java:56-69,112-115` |
| **B-04** | 验签必须在 **3s 预算之外** | Filter 在 `DispatcherServlet` **外面** | 验签落回预算内 ⇒ `DRIFT-USERAPI-009②` 不闭环 | 【事实】 | `JwtAuthFilter.java:25-32,72` ＋ `application.yml:76`（`http-timeout: 3s`） |
| **B-05 / B-06** | 失败以**异常对象**跨层传递，Interceptor **原样重抛** | `JwtVerificationResult.failure` 是 `RuntimeException`；`throw result.failure()` | 压成标志位 ⇒ `@ExceptionHandler` 管不到 ⇒ 401 body 退化成空 body；自己 new 一个异常 ⇒ 丢掉原始语义 | 【事实】 | `JwtVerificationResult.java:29-35,46-48` ＋ `JwtAuthInterceptor.java:96-98` |
| **B-07 / B-08** | claim 不可用、`uid<=0` **都不在鉴权层拒绝** | 签名合法就继续往下走；鉴权层只判「签名 + 过期」 | claim 异常只打 `warn`、不写 `userId` attribute；`uid<=0` 交给业务层的 `IdentityResolver` | 【事实】 | `JwtSignatureVerifier.java:105-114` ＋ `JwtAuthInterceptor.java:101-103` ＋ `IdentityResolver.java:238-244,259-269` |
| **B-09** | 两段取 token 的方法**不同** | 验签用 `tokenForSignatureVerification`，黑名单用 `trimBearerPrefix` | 统一它们 = 擅自「修好」真源的 bug ⇒ 制造新漂移 | 【契约】 | `common/security/BearerTokenExtractor.java:74-87,101-105` |
| **B-10** | 黑名单检查 **fail-open** | Redis 故障 ⇒ 放行 | 代价：故障期间已注销的 token 仍能通过 | 【事实】 | `JwtAuthInterceptor.java:135-145` |
| **B-11 / B-12** | `jwt_token` 在**黑名单检查之后**写；`userId` 的值类型必须是 **`Long`** | 注销端点读得到 token；`Long.valueOf(result.uid())` | 顺序反了 ⇒ 已拉黑的 token 也会被写进去；写成 `Integer` ⇒ `instanceof Long` 为 false ⇒ 1006 | 【事实】 | `JwtAuthInterceptor.java:102,111-120` ＋ `IdentityResolver.java:263-267` |
| **B-13** | 密钥 **fail-closed** | 空 / 空串 / 未解析占位符 ⇒ **启动失败** | 「启动成功但验签永远失败」不是可接受形态 | 【事实】 | `config/UserApiProperties.java:71-84` |
| **B-14 / B-15** | 鉴权链**不碰 DB、不生成 ID、不产生指标**；**鉴权层与身份层用「类型」分层，不用「码」分层** | 只有一次 Redis `hasKey`；鉴权失败 ⇒ `AuthenticationException` ⇒ 401，身份不可用 ⇒ `ErrInvalidClaimException` ⇒ 200+1006 | 401 **不产生指标**；同一个码 1006 在两层都可能出现 ⇒ 靠码分流会把两层搅在一起 | 【事实】 | `JwtAuthInterceptor.java:135-145` ＋ `common/exception/AuthenticationException.java:50-54` ＋ `advice/UserApiExceptionHandler.java:63-87` |
| **B-16** | 验签来源**可探针** | request attribute `com.rag.user.jwt.verificationSource` = `filter` / `inline` | 没有它 ⇒ 「验签到底在哪发生」从外部**看不出来** | 【事实】 | `JwtAuthFilter.java:84-90` ＋ `JwtAuthInterceptor.java:89,92` |

> **B-03 与 B-04 放在一起看，是本机制最关键的一对约束**：「验签要移出 3s 预算」⇒ 验签必须待在 `Filter` 里；「Filter 在 `DispatcherServlet` 外面」⇒ 它比 `MaxBytesFilter`（`+8`）**更早** ⇒ ⇒ 所以 Filter **一旦自己写响应**，401 就会压过 413 ⇒ 与 B 侧「413 压过 401」相反 ⇒ 【推导】**「绝不写响应」不是洁癖，是「Filter 位置」的必然推论**。

#### 11.A.4 需求里没写、但动手前必须自己定下来的五件事

1. **【推导】失败必须以「异常对象」的形式跨层传递。**
   Filter 与 Interceptor 在**两个不同的生命周期**里 ⇒ 两者之间只能靠 request attribute 传值；而「失败」若压成 `boolean failed`，Filter 就必须自己写响应 ⇒ `@ExceptionHandler`（只在 `DispatcherServlet` 内生效）就管不到 ⇒ ⇒ 401 的 body 会从「完整信封」退化成空 body ⇒ 推导出 B-05 与 `JwtVerificationResult` 这个类型的存在理由。
2. **【推导】路径判据必须只有一份。**
   两处各写一份 ⇒ 两种缝隙：「Filter 判受保护、Interceptor 判放行」= **鉴权被绕过**；「Filter 判 public、Interceptor 判受保护」= 拿不到验签结果 ⇒ 落兜底 ⇒ 验签退回预算内 ⇒ 推导出 B-02 与 `JwtAuthPaths` 这个**只含静态方法的类**。
3. **【推导】claim 的存在性与取值要推迟到业务层。**
   鉴权层只回答「这个 token 是我签的、还没过期吗」；「里面的身份字段对不对」是**身份层**的事 ⇒ 推导出 B-07/B-08 ⇒ 也就推导出「鉴权层与身份层用**类型**分层」（B-15）而不是用错误码分层。
4. **【推导】黑名单查询必须 fail-open。**
   Redis 挂掉就拦住全部业务 ⇒ 一次基础设施故障升级成全站不可用 ⇒ 代价是「故障期间已注销的 token 仍可用」⇒ 这是一个**安全与可用性的取舍**，必须**显式**选一边并写下来 ⇒ 推导出 B-10。
5. **【推导】密钥必须在**启动期** fail-closed。**
   本模块**既签发**（登录）**又验签**（拦截器）⇒ 空密钥 = 任何人可伪造 user token ⇒ 这不是「用户登录时才发现」的问题，是**进程不该起来**的问题 ⇒ 推导出 B-13。

#### 11.A.5 必须保持不变的既有行为（改代码时的红线）

| 红线 | 为什么不能动 | 依据 |
|---|---|---|
| public 路径集合（只有 register / login）＋ protected 前缀 `/user/v1` | 少一个 public ⇒ 公开端点被拦；多一个 ⇒ 端点裸奔；前缀必须与 `WebMvcConfig` 注册的 `/user/v1/**` 对齐 | 【契约】`JwtAuthPaths.java:41-43,50` ＋ `config/WebMvcConfig.java:42-46` |
| HS256 + claim 名 `userId` + `exp` 语义 | 别的服务 / 客户端会验签它 | 【契约】`common/security/JwtUtil.java:31-48` |
| **Filter 绝不写响应**（⇒ 413 压过 401） | 改了就是新漂移 | 【事实】`JwtAuthFilter.java:56-69` |
| 401 的 body 是**完整信封** | 客户端统一解包 | 【事实】`advice/UserApiExceptionHandler.java:82-87` |
| 「签名合法但 claim 畸形 ⇒ 200 + 1006」 | 与 B 侧 `DRIFT-USERAPI-001` 逐条一致 | 【事实】`JwtSignatureVerifier.java:106-114` |
| 两段取 token 的方法**不统一** | 统一 = 擅自修好真源的 bug | 【契约】`BearerTokenExtractor.java:8-11`（javadoc 明确写「合并即等于擅自修好 B 的 bug」） |
| 黑名单 fail-open ＋ `userId` attribute 的**值类型**是 `Long` | 与 B 侧 `err == nil && exists` 逐条一致；且 `IdentityResolver` 判的就是 `instanceof Long` | 【事实】`JwtAuthInterceptor.java:135-145` ＋ `IdentityResolver.java:263-267` |
| `access-secret` 无默认值且启动期校验 | 空密钥 = 任何人都能伪造 token | 【事实】`config/UserApiProperties.java:71-84` |

#### 11.A.6 从行为清单到状态机

行为清单是**平铺的**，实现需要一个**有序的判定过程**。把它画成状态机：

```mermaid
stateDiagram-v2
    [*] --> 请求进入: 任意 HTTP 请求
    请求进入 --> 判路径: JwtAuthFilter.doFilter
    判路径 --> public放行: isProtected(uri) == false
    判路径 --> 验签: isProtected(uri) == true
    验签 --> 验签失败: 缺 header / 空 token / 签名错 / 过期
    验签 --> 取claim: 验签通过
    取claim --> claim状态: userId 是不是 Number（不是 ⇒ 只 warn，不拒绝）
    验签失败 --> 写attribute: 写 JwtVerificationResult（失败对象也写）
    claim状态 --> 写attribute: 写 JwtVerificationResult（claimUsable 随 claim 是否为 Number）
    写attribute --> 无条件放行: chain.doFilter
    public放行 --> 无条件放行
    无条件放行 --> 判public: JwtAuthInterceptor.preHandle
    判public --> 业务层: isPublic(uri) ⇒ return true
    判public --> 读结果: 非 public
    读结果 --> 兜底inline: attribute 为 null（Filter 未命中 ⇒ 就地验签 + 标 inline）
    读结果 --> 消费结果: attribute 非 null（标 filter）
    兜底inline --> 失败重抛: result.failed()
    消费结果 --> 失败重抛: result.failed()
    失败重抛 --> 失败401: throw result.failure()
    消费结果 --> 查黑名单: claimUsable == true ⇒ 先写 userId attribute；false ⇒ 跳过
    查黑名单 --> 失败401: 命中黑名单
    查黑名单 --> 放行: 未命中或 Redis 故障（fail-open）
    放行 --> 业务层: 写 jwt_token attribute ⇒ return true
    失败401 --> [*]
    业务层 --> [*]
```

> **这张图不是「代码流程图」，是「需求状态机」**——它是 11.B 推导组件时最直接的依据：
> 每个状态要落到哪个组件上、哪个状态需要抛异常、哪个状态需要写哪个 attribute，都由这张图决定。

### 11.B 从业务行为推导组件设计

> 🔴 **本章最容易走偏的地方**：直接抄文件树（Filter / Interceptor / Paths / Verifier / Result……），然后逐个解释职责。
> 那样得到的是「**这套系统有哪些文件**」，不是「**我该写哪些文件、为什么**」。
> 正确的顺序：**行为 → 需要谁来做 → 已有能力能否承担 → 落成什么文件**。

#### 11.B.1 推导的第一问：每个行为要交给谁承担

| 行为 | 承担者 | 为什么是它（而不是别的） |
|---|---|---|
| B-01 / B-02 路径判据 | `JwtAuthPaths`（纯静态类） | 「判据」是**数据**，不是行为 ⇒ 不需要 Spring 管理 ⇒ 一个 final 类 + 两个静态方法就够 |
| B-03 / B-04 不写响应、且必须在预算外 | `JwtAuthFilter`（Servlet `Filter`，`@Order(+6)`） | 要落在 `DispatcherServlet` **外面** ⇒ 只有 Servlet Filter 能做到；且「不写响应」是**位置带来的约束** ⇒ 写在最靠近它的那个类的 javadoc 里 |
| B-05 / B-06 失败载体与重抛 | `JwtVerificationResult`（5 分量 record）＋ `JwtAuthInterceptor.preHandle` | 跨生命周期的**数据载体**只有 record 装得下（要同时装「成功结果」与「失败异常」）；而「抛不抛、抛什么」是**授权**职责 |
| B-07 / B-08 claim 与 uid 的判定 | `JwtSignatureVerifier`（只判「是不是 Number」）+ `IdentityResolver`（判类型与 `uid<=0`） | **分两层** ⇒ 见 11.B.2 的反事实第 4 行 |
| B-09 两段取 token | 共享层 `BearerTokenExtractor` 的**两个**方法 | 「取法」是**协议解析**细节 ⇒ 放共享层；且**两个方法都要留**（B 侧自身不一致） |
| B-10 / B-11 黑名单 fail-open、`jwt_token` 在其后写 | `JwtAuthInterceptor#isBlacklisted` | 「查不查、在哪查」「写不写 attribute」都是**授权段**职责 ⇒ 与 claim 判定分开 |
| B-12 `userId` 的值类型 ＋ B-13 密钥 fail-closed | `JwtAuthInterceptor`（写）＋ `IdentityResolver`（读）；`UserApiProperties` 的 `@PostConstruct` | 属性名与类型是**拦截器 ↔ 解析器之间的契约** ⇒ 收在共享层 `IdentityResolver` 一处（`:85`）；「配置绑定」是 Spring 的事，但「**什么值不允许**」是业务决定 |
| B-15 层次分层 | `AuthenticationException`（extends `BusinessException`）**类型** | Spring 按「最具体」挑 handler ⇒ **类型**天然承载层次 ⇒ 不必靠码 |
| B-16 探针 | `JwtAuthFilter.VERIFICATION_SOURCE_ATTRIBUTE` | 「机制是否在正确的位置生效」**从外部看不出来** ⇒ 必须留一个可观测的出口 |

#### 11.B.2 为什么必须分层：四个「合并掉会怎样」的反事实

| 假设合并 | 会发生什么 | 结论 |
|---|---|---|
| 把验签与授权合并成一个 `HandlerInterceptor` | 验签就落在 `DispatcherServlet` **里面** ⇒ 验签耗时**计入** 3s 预算 ⇒ B 侧 `DRIFT-USERAPI-009②`（验签不计入预算）**不闭环** ⇒ 逐条对齐失败 | ⇒ 「验签」与「授权」必须**跨两个生命周期**分开 |
| Filter 与 Interceptor 各写一份路径判据 | ① 「Filter 判受保护、Interceptor 判放行」⇒ **鉴权被绕过**（安全漏洞）② 「Filter 判 public、Interceptor 判受保护」⇒ 拿不到验签结果 ⇒ 落兜底 ⇒ 验签退回预算内 | ⇒ 判据**只有一份** ⇒ `JwtAuthPaths` |
| 把 claim 的存在性/取值判定也放进鉴权层 | 「签名合法但 `userId` 是字符串」会变成 **401** ⇒ 与 B 侧 `DRIFT-USERAPI-001`（该场景在 B 侧**不是** 401，而是业务层的 type assertion 失败 ⇒ 200 + 1006）**不一致** | ⇒ **层次必须由类型承载**（B-15）⇒ claim 判定留在业务层 |
| 失败压成 `boolean failed`，由 Filter 自己写 401 | `@ExceptionHandler` 只在 `DispatcherServlet` 内生效 ⇒ 管不到 Filter 写的响应 ⇒ 401 的 body 从「完整信封」退化成**空 body** ⇒ 推翻 B 侧的签字项 | ⇒ 失败必须以**异常对象**传递 ⇒ `JwtVerificationResult.failure` 是 `RuntimeException` |

#### 11.B.3 DTO / 配置类 / 判据类：这些「简单文件」是怎么被推导出来的

##### `JwtAuthPaths`（路径判据）

| 推导问题 | 推导过程 | 结论 |
|---|---|---|
| 为什么需要一个专门的类、且是**静态方法**而不是 Spring Bean？ | 判据要被**两处**调用 ⇒ 不能内联在任一处 ⇒ 需要一个**共享的**归属地；且它是**纯数据 + 纯函数**（无状态、无依赖）⇒ 不需要注入 ⇒ 静态方法最省（且能在 `WebMvcConfig` 的注册期就用） | 一个 final 类 + `static boolean isPublic/isProtected` |
| 为什么有**两个**方法而不是一个？ | 两段机制的**提问方式不同**：Filter 问「这条要不要验签」（protected？），Interceptor 问「这条要不要鉴权」（public？）⇒ **一个方法答不了两个问题** | `isPublic` + `isProtected` |
| 为什么 `isPublic` 要在 `isProtected` **之前**判？ | public 是 protected 的**例外** ⇒ 例外先判 ⇒ 否则 `/user/v1/login` 会同时命中前缀 | `:79-81` |
| `PUBLIC_PATHS` 为什么用 `Set`？ | 成员判定是 O(1) 哈希查找；且**集合**形态让「只有这两个」这件事在代码上一眼可见 | `Set.of("/user/v1/register", "/user/v1/login")` |
| `PROTECTED_PREFIX` 为什么是 `/user/v1` **本身 + `/user/v1/` 前缀**？ | `WebMvcConfig` 注册的是 `/user/v1/**` ⇒ 若 `isProtected` 少覆盖 ⇒ Interceptor 拿不到验签结果（落兜底）⇒ 若**多**覆盖 ⇒ 只是多做一次无用验签，**不构成鉴权绕过** ⇒ ⇒ 判据取**超集**是安全侧对齐 | `uri.equals(PREFIX) \|\| uri.startsWith(PREFIX + "/")`（`:82`） |
| 私有构造器为什么要有？ | 工具类不该被实例化 | `private JwtAuthPaths() {}`（`:52-53`） |

> ⚠️ **这个类一旦要改，影响面是全局的**：① `JwtAuthFilter`（`:103`）② `JwtAuthInterceptor`（`:77`）③ `WebMvcConfig` 的注册前缀（`:45`）④ **7 个受保护端点的可达性** ⇒ ⇒ 它是本域**最不该被随手改**的一个文件。

##### `JwtVerificationResult`（验签结果载体）

| 推导问题 | 推导过程 | 结论 |
|---|---|---|
| 为什么需要一个类型、且是 **record**？ | 两段机制之间只能靠 request attribute 传递 ⇒ 需要一个**明确的载体**（不能传裸的 `String`/散装字段）；而它是**数据载体、不含行为** ⇒ record 最省 | 一个 record |
| 为什么是 **5 个**分量？ | ① `uid`：业务层要的身份 ② `claimUsable`：claim 是不是数字（决定要不要写 attribute）③ `signatureToken`：**验签用**的 token ④ `trimmedToken`：**黑名单 key 用**的 token ⑤ `failure`：失败时的异常 ⇒ **少一个都传不全** | 5 个分量 |
| 为什么 `failure` 是 `RuntimeException` 而不是 `Throwable`？ | 它要被 `throw` 出去 ⇒ 必须是** unchecked**（否则方法签名要改，`preHandle` 也不能抛 checked）⇒ `RuntimeException` | `RuntimeException failure` |
| 为什么要两个工厂方法 `ok` / `failed`、以及 `failed()` 方法？ | `failed` 时五个分量被固定成 `(-1L, false, "", "", failure)`（`:47`）⇒ **构造约束收在一处**；`failed()` 让判据也收在一处 ⇒ 将来若失败语义变化，只改这一处 | `ok(...)` / `failed(...)` / `failed()`（`:51-53`） |

##### `UserApiProperties`（配置 + fail-closed）

| 推导问题 | 推导过程 | 结论 |
|---|---|---|
| 为什么要有配置类、且用 `@NestedConfigurationProperty`？ | `access-secret` / `access-expire` 要从 yml / 环境变量绑进来 ⇒ `@ConfigurationProperties` 是标准做法；`user-api.user-auth.access-secret` 是**嵌套段** ⇒ 不加该注解会被当成扁平 key ⇒ **绑定行为会变** | `@ConfigurationProperties(prefix = "user-api")`（`:55`）＋ `@NestedConfigurationProperty`（`:60`） |
| 为什么校验写在 `@PostConstruct` 而不是每次使用时？ | 空密钥是**启动期**问题 ⇒ 在**每次**验签时判，等于让第一个用户当「探针」⇒ 且失败形态会是 401（看起来像客户端问题）⇒ ⇒ **启动就失败** | `@PostConstruct verifyAccessSecret()`（`:71-84`） |
| 判据为什么用 `isBlank()` 而不是 `isEmpty()`？ | 空白串（`"   "`）同样是不可用的密钥 ⇒ `isBlank` 更严 ⇒ 且与后面 `contains("${")` 的思路一致（**判「不可用」而不是判「不等于某个字面量」**） | `secret == null \|\| secret.isBlank()`（`:74`） |
| 为什么还要判「**仍是未解析的占位符**」？ | Spring 的 `ignoreUnresolvablePlaceholders = true` ⇒ 环境变量没设时**不报错**，而是把字面量 `"${USER_JWT_SECRET}"` 原样绑上 ⇒ 只判「null / 空串」**堵不住这条路** | `secret.contains("${")` ⇒ 抛错（`:79-83`） |

#### 11.B.4 框架替我做了什么、我仍然必须自己写什么

| 职责 | 框架承担 | 我必须自己写 |
|---|---|---|
| Filter 链排序 | Spring Boot（`@Order`） | **槽位选多少**（`+6`）与**为什么选它**（夹在 `+3` 与 `+7` 之间） |
| Interceptor 注册 | Spring MVC（`WebMvcConfigurer`） | 注册**哪些路径**（`/user/v1/**`）——注意：这是「在哪跑」，不是「要不要鉴权」 |
| 参数解析 | Spring MVC | 本机制**不参与**参数解析 |
| 异常 → HTTP ＋ handler 选择 | Spring MVC 异常解析 + `@RestControllerAdvice`；Spring 按「最具体」挑 `@ExceptionHandler` | 每种异常对应的**状态码**（`decideStatus`，`advice/UserApiExceptionHandler.java:355`）；以及**用类型承载层次**（B-15）——框架给你这个能力，但**用不用**是业务决定 |
| JWT 解析 / 验签 | jjwt（`Jwts.parser().verifyWith(key).parseSignedClaims(token)`） | **claim 名的选择**（`userId`）、**算法钉死**（HS256）、**密钥补齐**（`JwtUtil.padToHmacKeySize`） |
| 配置绑定 ＋ Redis 访问 ＋ 跨层传值 | Spring Boot（`@ConfigurationProperties`）；Spring Data Redis（`hasKey`）；Servlet `request.setAttribute` | **哪些值不允许**（空 / 占位符）；**故障时的策略**（fail-open，B-10）；**传什么**（`JwtVerificationResult`）+ **attribute 名**（契约） |

> ⚠️ **框架承担 ≠ 业务行为被免除**：jjwt 帮你验签，但**「claim 叫 `userId`」「算法必须是 HS256」是业务决定**；Spring 帮你挑 handler，但**「用类型而不是码来分层」是业务决定**；`@PostConstruct` 帮你做启动期检查，但**「什么样的密钥不允许」是业务决定**。

### 11.C 从空文件推导代码实现

> 本节不讲「现在这个文件里写了什么」（前 10 章已经讲过），
> 讲的是「**从一个空文件开始，我按什么顺序写、每一步凭什么**」。

#### 11.C.1 动手前先钉死的四张清单

| 清单 | 内容（以本机制为例） | 从哪来 |
|---|---|---|
| ① 系统要求清单 ＋ ② 契约清单 | 哪些接口受保护 · 什么叫「合法」· 失败长什么样 · 预算从哪算起；算法、claim 名、attribute 名与类型、public 集合、错误码与状态码 | 11.A.3 + 11.A.5 + 11.A.6 + 11.B.3 |
| ③ 载体清单 | 两段之间传什么 · 失败怎么传 · attribute 写几个 | 11.A.4 |
| ④ 风险清单 | 哪种判据写法会出现「缝隙」· 哪种失败传递方式会让 body 变形 · 哪种配置值等于没配 | 11.B.2 |

#### 11.C.2 `JwtAuthPaths`：从空文件到 84 行

```java
// 步骤 1：判据是纯数据 + 纯函数 ⇒ final 类 + 静态方法（步骤 2-4 的注释即每步的依据）
public final class JwtAuthPaths {
    public static final Set<String> PUBLIC_PATHS = Set.of(              // 步骤 2：契约「只有这两个」
            "/user/v1/register", "/user/v1/login");
    private static final String PROTECTED_PREFIX = "/user/v1";          // 步骤 3：与 /user/v1/** 对齐
    private JwtAuthPaths() { }                                          // 步骤 4：工具类不可实例化

    public static boolean isPublic(String uri) {                        // 步骤 5：两个提问 ⇒ 两个方法
        return uri != null && PUBLIC_PATHS.contains(uri);
    }
    public static boolean isProtected(String uri) {
        if (uri == null) { return false; }
        if (isPublic(uri)) { return false; }                             // 步骤 6：例外先判
        // 步骤 7：取「本身 + 前缀」⇒ 是 /user/v1/** 的超集（少覆盖落兜底，多覆盖只是无用验签）
        return uri.equals(PROTECTED_PREFIX) || uri.startsWith(PROTECTED_PREFIX + "/");
    }
}
```

| 步骤 | 我怎么知道该这么写 |
|---|---|
| 1. 为什么不用 Spring Bean？ | 判据无状态、无依赖 ⇒ 静态方法最省；且 `WebMvcConfig` 注册期就要用 |
| 2. 为什么**只有**这两个？ | 契约（L1）冻结；多一个 ⇒ 那个端点裸奔 |
| 3. 为什么前缀是 `/user/v1` 而不是 `/user/v1/`？ | 要**同时**覆盖 `/user/v1` 本身 ⇒ 用 `equals + startsWith` 两个条件 |
| 5. 为什么 `isPublic` 也要判 null？ | 判据类**不该假设调用方**（Filter 的 `getRequestURI()` 正常不会是 null，但那是事实，不是契约） |
| 7. 为什么取**超集**？ | 少覆盖 ⇒ 落兜底（②不闭环）；多覆盖 ⇒ 只多做一次无用验签，**不构成鉴权绕过** ⇒ 安全侧取超集 |

#### 11.C.3 `JwtVerificationResult`：从空文件到 54 行

```java
public record JwtVerificationResult(
        long uid,                 // 分量 1：claim 的值；不可用时为 -1
        boolean claimUsable,      // 分量 2：claim 是不是数字 ⇒ 决定要不要写 userId attribute
        String signatureToken,    // 分量 3：验签用的 token
        String trimmedToken,      // 分量 4：黑名单 key 用的 token（与分量 3 可以不同）
        RuntimeException failure  // 分量 5：失败时的异常（null = 通过）
) {
    public static JwtVerificationResult ok(long uid, boolean claimUsable,      // 工厂 1：成功
                                           String signatureToken, String trimmedToken) {
        return new JwtVerificationResult(uid, claimUsable, signatureToken, trimmedToken, null);
    }
    public static JwtVerificationResult failed(RuntimeException failure) {     // 工厂 2：失败
        return new JwtVerificationResult(-1L, false, "", "", failure);         // 五个分量固定成无效值
    }
    public boolean failed() { return failure != null; }                       // 判据收在一处
}
```

| 决策 | 依据 |
|---|---|
| 为什么 `failure` 是 `RuntimeException` 而不是自定义异常？ | 它要被 `throw` 出去 ⇒ unchecked ⇒ `preHandle` 不需要声明 ⇒ 且**可以用任何**异常类型装（包括从别处 catch 来的）⇒ 泛化能力更强 |
| 为什么不用 `Optional<RuntimeException>`、也不把 `failed()` 写成静态工具？ | `Optional` 的语义是「可能没有值」，而这里「没有 failure」=「成功」⇒ 语义相反 ⇒ 而且 `Optional` 不该做**字段**；判据收在**载体自己身上** ⇒ 将来若加「降级通过」状态，只改这一处 |
| 为什么 `uid` 用 `long` 而不是 `Long`？ | 「不可用」用 `-1` 表达（与 `claimUsable=false` 配套）⇒ 原始类型够用，且**避免 null 装箱陷阱**（`Long` 的 null 会在拆箱时 NPE） | 

#### 11.C.4 `JwtSignatureVerifier#verify`：四段的顺序是怎么定出来的

| 段 | 代码位置 | 为什么在这个位置 |
|---|---|---|
| **① 头缺失/为空** | `:74-80` | 最廉价的失败 ⇒ 先判 ⇒ 避免后面 `substring` 之类的 NPE |
| **② 剥 Bearer + 空判** | `:85-92` | 剥完才知道「token 是不是空」⇒ 必须在验签之前 |
| **③ 验签名 + 取 claim** | `:96-114` | **验签先于取 claim** ⇒ 没验过签的 payload **不可信** ⇒ 不能先读 claim |
| **④ 第二次剥 Bearer** | `:129-130` | 与③**分开** ⇒ 因为黑名单 key 用的是**另一种取法**（B-09）⇒ 两套取法必须各自独立 |

| 决策 | 依据 |
|---|---|
| ③ 里 `catch (BusinessException e)` 为什么**原样带过去**？ | B 侧 `catch (BusinessException e) { throw e; }` ⇒ 换成 1006 会丢掉原始码 ⇒ 逐条对齐 |
| ③ 里 claim 类型不对为什么**不抛异常**、`uid <= 0` 为什么**不判**？ | claim：go-zero 的 `rest.WithJwt` 只验签名与 exp ⇒ claim 校验属于 identity 层 ⇒ 后果落在业务层 ⇒ HTTP 200 + 1006（B-07）；\`uid<=0\`：那是**业务层**（`IdentityResolver`）的规则（B-08）⇒ 鉴权层越权判 ⇒ 两个端点会用不同的 uid 语义（见 11.D.1） |
| ③ 里为什么用 `instanceof Number` 而不是 `instanceof Long`？ | JSON 数字反序列化成 `Integer` 还是 `Long` 取决于大小 ⇒ 用 `Number` + `longValue()` ⇒ **两种都能吃** ⇒ 而下游写 attribute 时统一转成 `Long`（B-12） |
| ④ 为什么两次剥的结果**可能不同**？ | 验签侧大小写**不敏感**（`tokenForSignatureVerification`），黑名单侧**敏感**（`trimBearerPrefix`）⇒ 非规范写法（如小写 `bearer `）下两者分叉 ⇒ 这是 **B 侧自身的不一致**⇒ 逐条照抄（B-09） |
| key 为什么在**构造器**里算好？ | `Keys.hmacShaKeyFor(...)` 是一次性的密钥派生 ⇒ 每个请求重算是纯浪费 ⇒ 放在构造器里（`:62`）⇒ 且**密钥来源只有一处**（`JwtUtil.padToHmacKeySize`） |

#### 11.C.5 `JwtAuthFilter#doFilter`：为什么「不写响应」是它的全部纪律

```java
@Override
public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) {
    HttpServletRequest request = (HttpServletRequest) req;
    String uri = request.getRequestURI();
    if (JwtAuthPaths.isProtected(uri)) {          // ① 只有 protected 才验签
        JwtVerificationResult result = verifier.verify(request.getHeader("Authorization"), uri);
        request.setAttribute(VERIFICATION_ATTRIBUTE, result);        // ② 结果交给 Interceptor
        request.setAttribute(VERIFICATION_SOURCE_ATTRIBUTE, SOURCE_FILTER);  // ③ 探针：标记来源
        if (result.failed()) {
            log.debug("JWT verification failed (deferred to interceptor) : {}", uri);  // ④ 只记，不拒
        }
    }
    chain.doFilter(req, resp);   // ⑤ 🔴 无条件放行
}
```

| 决策 | 依据 |
|---|---|
| ① 为什么要判路径？ | public 路径**不验签**（省一次无用开销）⇒ 且与 B 侧 `rest.AddRoutes(login, register)` 不带 `WithJwt` 一致 |
| ④ 为什么用 `debug` 而不是 `warn`？ | 验签失败是**预期内的客户端行为**（401 不是服务端故障）⇒ 日志级别要与语义匹配（对比：`JwtSignatureVerifier` 里那些 `warn` 是「更值得注意的异常形状」） |
| ③ 为什么探针写在 Filter 里、而不是只在 Interceptor 里？ | 两处都要写：Filter 写 `filter`，Interceptor 在兜底时改写成 `inline`（`JwtAuthInterceptor.java:89`）⇒ 探针必须表达「**最终**是哪里验的」⇒ 兜底时要**覆盖** |
| ⑤ 为什么**无条件**放行？ | Filter 在 `+6`，`MaxBytesFilter` 在 `+8` ⇒ 若 Filter 自己写 401，**401 会压过 413**；B 侧是 413 压过 401 ⇒ 放行才能让 413 有机会先写 ⇒ **401 由 Interceptor 抛**（在所有 Filter 之后）⇒ 与 B 侧一致（B-03） |

#### 11.C.6 `JwtAuthInterceptor#preHandle`：六步的顺序是怎么定出来的

| 步 | 做什么 | 为什么必须在这个位置 |
|---|---|---|
| 1 | `isPublic(uri)` ⇒ `return true` | public **必须**在第一段放行 ⇒ 否则公开端点会因为「没有 `userId` attribute」在业务层被拒 |
| 2 | 读 `VERIFICATION_ATTRIBUTE`；为 null ⇒ **兜底就地验签** + `warn` + 标 `inline` | 必须**先尝试消费** ⇒ 正常路径下 Filter 已经验过了 ⇒ 这一步只是**取结果**；兜底是**异常路径**（Filter 未命中）⇒ 它不改变安全语义（照样 401） |
| 3 | `result.failed()` ⇒ `throw result.failure()` | 必须在**写 attribute 之前** ⇒ 否则失败请求也会带上 `userId` ⇒ 业务层会以为「有身份」 |
| 4 | `claimUsable` ⇒ 写 `userId` attribute（**`Long`**） | 必须用 `Long.valueOf(...)`（B-12）⇒ 且**只在 claim 可用时**写（B-07） |
| 5 / 6 | 查黑名单 ⇒ 命中则抛；再写 `jwt_token` attribute；`return true` | 黑名单**在**预算内（B 侧链序：JWT → 黑名单 → handler）⇒ 且它必须**在写 `jwt_token` 之前**（B-11）⇒ 放行 ⇒ 进业务层 |

> 🔴 **第 3 步与第 4 步的顺序是本章最重要的一条「顺序即语义」**：如果先写 `userId` 再判失败 ⇒ 一个**验签失败**的请求也会带着 `userId` 走进业务层 ⇒ 业务层会以为「有身份」⇒ 而**鉴权失败本来应该让请求根本进不了业务层** ⇒ 同理，第 5 步（黑名单）必须在第 6 步（写 `jwt_token`）之前 ⇒ 否则**已拉黑的 token 也会被写进 attribute** ⇒ 注销端点可能拿到一个本该被拒的 token。

#### 11.C.7 `IdentityResolver`：两个取 uid 的方法 + 六步判定链

**两个取 uid 的方法**（`service/users/common/src/main/java/com/rag/users/common/IdentityResolver.java`）：

| 方法 | 位置 | 判什么 | 谁在用 |
|---|---|---|---|
| `claimedUID` | `:238-244` | claim 缺失 / 类型不对 / **`uid<=0`** ⇒ 抛 `ErrInvalidClaimException` | `get` / `avatar` / `avatar/select` / `avatar/history`（`UserApiService.java:237,458,522,590`） |
| `claimedUIDAllowNonPositive` | `:259-269` | claim 缺失 / 类型不对 ⇒ 抛；**`uid<=0` 不抛** | `update` / `delete`（`UserApiService.java:305,361`） |

⇒ **两者的差别是行为，不是实现细节** ⇒ 【推导】**不能合并**：合并等于擅自「修好」B 侧的不对称 ⇒ 制造一处新的语义漂移（证据：`IdentityResolver.java:230-232` 的 javadoc 明说「不要合并这两个方法」）。

**六步身份判定链**（`resolveUser` `:294-302` + `verifyUser` `:321-335`）：

| 步 | 判定 | 失败异常 | 为什么在这个位置 |
|---|---|---|---|
| 1 | `claimedUID` 失败 | `ErrInvalidClaimException` | **身份合法性先于一切** ⇒ 拿不到合法 uid ⇒ 后面全部无从谈起 |
| 2 | 查询端口为 null | `ErrIdentityMismatchException` | 「没有查询通道」是**配置/接线问题**，不是「用户不存在」⇒ 必须与第 3 步区分 |
| 3 | 查无此人 | `ErrUserNotFoundException` | **存在性**判定 |
| 4 | 取回的 UID ≠ 请求 UID | `ErrIdentityMismatchException` | **一致性**判定 ⇒ 放在第 3 步之后（没查到就没得比） |
| 5 | `status` 字段**不存在** | `ErrStatusUnavailableException` | proto3 presence：字段「存在」与「值为 0」是**两种状态** ⇒ 必须先判 presence 再判值 |
| 6 | `status != 0` | `ErrUserInactiveException` | **活跃性**判定 ⇒ 必须在第 5 步之后（字段都不存在就没得比值） |

> 🔴 **第 5 步在第 6 步之前，是本章第二条「顺序即语义」**：
> proto3 的 `optional int64 status` 有三种形态 —— **不存在** / **存在且为 0** / **存在且非 0** ⇒ 若先判值再判 presence ⇒ 「字段不存在」会被当成「非 0」⇒ **被封禁** ⇒ 语义完全错 ⇒ 【推导】**presence 是「值」的前置条件**，不是值的替代。

#### 11.C.8 实现顺序（谁先谁后）与每步的完成判据

| 序 | 先做哪一步 | 为什么不能颠倒 | 完成判据 |
|---|---|---|---|
| 1 | **路径判据**（`JwtAuthPaths`） | Filter 与 Interceptor 都依赖它 | 两个方法对 public / protected / 未知路径的判定符合预期 |
| 2 | **验签本体**（`JwtSignatureVerifier`）+ 共享层 `JwtUtil` | 依赖密钥与算法 | 合法 token 通过、坏 token 失败、claim 畸形**不抛异常** |
| 3 | **结果载体**（`JwtVerificationResult`） | Filter 与 Interceptor 都要用 | `ok` / `failed` 两个工厂的 5 个分量取值正确 |
| 4 | **Filter**（验签 + 写 attribute） | 依赖 1/2/3 | protected 路径验签后 attribute 已写好；**Filter 没有写任何响应** |
| 5 | **Interceptor**（消费 + 授权 + 写 attribute） | 依赖 3/4 | 401 是**完整信封**；`userId` 是 `Long`；`jwt_token` 在黑名单之后写 |
| 6 / 7 | **装配**（`WebMvcConfig` 注册 + `UserApiProperties` 绑定）＋ **身份层**（`IdentityResolver`） | 依赖 4/5；身份层依赖 attribute 名与类型 | 两个 bean 都在容器里；`/user/v1/**` 上 interceptor 确实执行；`claimedUID` / `claimedUIDAllowNonPositive` 的差异符合契约 |
| 8 / 9 | **密钥守卫**（`@PostConstruct`）＋ **测试** | 依赖配置绑定；测试依赖全部 | 空 / 空串 / 占位符三种都**启动失败**；测试判据见 11.D |

### 11.D 如何验证实现符合需求

> 🔴 **本章的红线**：**「能编译」「测试通过」「代码看起来没问题」三者都不等于「行为正确」**。
> 而且**机制类**的验证比端点类更难：机制本身**没有返回值**可以断言 ⇒ 必须**造一个探针**把内部状态读出来。

#### 11.D.1 从行为清单推导用例，并对照现有测试

| 行为 | 推导出的用例 | 本项目现状 | 依据 |
|---|---|---|---|
| B-01 | `POST /user/v1/login` 无 token ⇒ **不是 401**（进业务层）；`/user/v1/**` 下的未知路径 ⇒ **401**（拦截器先于 404） | ✅ 已覆盖 | `api/UserApiHttpContractTest.java:132` `publicLoginPathDoesNotRequireJwt` ＋ `:116` `unknownPathInsideUserPrefixIs401` |
| B-02 | 两段判据是否同源 | ⚠️ **未直接覆盖**（当前靠「同一个类」这一结构事实保证） | 依据：`JwtAuthPaths` 被两处调用 |
| B-03 | Filter **绝不写响应** | ⚠️ **未直接覆盖**（可由「401 的 body 是完整信封」间接支持，但 413/401 的优先级没有专门用例） | 依据：`JwtAuthFilter.java:112-115` |
| B-04 | **验签来源 = `filter`**（不是 inline） | ✅ 已覆盖（**探针 Filter** 读 attribute 抄进响应头） | 同上 `:248` `jwtVerificationHappensInFilter`（探针实现 `:338-360`） |
| B-05 / B-06 | 401 的 body 是**完整信封**（`data` 字段存在） | ✅ 已覆盖 | 同上 `:161` `protectedPathWithoutTokenIs401` |
| B-07 | 签名合法但 claim 畸形 ⇒ **HTTP 200 + 1006**（不是 401） | ⚠️ **未覆盖** | 依据：`JwtSignatureVerifier.java:106-114` |
| B-08 / B-12 | `uid=0` 打 `/user/v1/get` ⇒ **200 + 1006**，打 `/user/v1/update` ⇒ **200 + 1003**；`userId` attribute 的值类型是 `Long` | ⚠️ **未覆盖**（HTTP 层；`IdentityResolverTest` 只覆盖了 `IdentityResolver` 本身：`:75` / `:92` / `:66`） | 依据：`IdentityResolver.java:263-267` ＋ `JwtAuthInterceptor.java:102` |
| B-09 / B-10 / B-11 | 小写 `bearer` ⇒ 验签通过 + 打「两段不一致」的 warn；Redis 故障 ⇒ **放行**（fail-open）；命中黑名单 ⇒ 401 且 `jwt_token` **不写** | ⚠️ **未覆盖** | 依据：`JwtAuthInterceptor.java:111-120,121-124,135-145` |
| B-13 | 空 / 空串 / 占位符密钥 ⇒ **启动失败** | ✅ 已覆盖（4 个用例） | `config/UserApiPropertiesSecretGuardTest.java:40,51,61,72` |
| B-14 | 鉴权失败**不产生指标** | ⚠️ **未覆盖** | 依据：`JwtAuthInterceptor` 全文无 `MeterRegistry` |
| B-15 | 「签名合法但用户不存在」⇒ **200 + 1003**（不是 401） | ✅ 已覆盖 | `api/UserApiHttpContractTest.java:265` `validSignatureButUnknownUserIsBusinessError` |
| B-16 | 验签来源可从外部读到 | ✅ 已覆盖 | 同上 `:248` |
| 装配 ＋ 端到端 | Filter 与 Interceptor **两个 bean 都在容器里**；register → login → get（真 PG + 真 bcrypt + 真 JWT） | ✅ 已覆盖 | `UserServiceApplicationTests.java:109` `jwtTwoStageBeansExist` ＋ `api/UserApiHttpContractTest.java:281` `registerLoginGetHappyPath` |

⇒ **诚实的结论**：本机制**最核心**的三条 —— B-02（判据只有一份）、B-03（Filter 绝不写响应）、B-07（claim 畸形不拒绝）——
**都没有测试覆盖**；它们目前只有**源码层面的推导**。
⇒ 而**有覆盖**的那些，多半是「端点级形态」（401 / 405 / 信封三字段），不是机制专属。

#### 11.D.2 三类结论必须分开写

| 类别 | 本机制里的具体内容 | 怎么标注 |
|---|---|---|
| **已运行验证** | B-01、B-04、B-05 / B-06、B-13、B-15、B-16、装配、端到端 | 「真 Tomcat + 真 PG 断言通过」（`api/UserApiHttpContractTest` / `config/UserApiPropertiesSecretGuardTest` / `UserServiceApplicationTests`）＋「纯单元断言通过」（`IdentityResolverTest`） |
| **仅源码推导，未运行** | B-02、B-03、B-07、B-08（HTTP 层）、B-09、B-10、B-11、B-12（写侧）、B-14 | 「源码推导，未运行」 |
| **无法确认** | 3s 预算是否真的**不含**验签耗时（取决于 go-zero 链序这条**外部前提**是否仍然成立）；`DRIFT-USERAPI-009②` 的原始意图；两段取 token 不一致是否是 B 侧的**有意**保留 | 【存疑】 |


#### 11.D.3 怎么验证「机制」，而不只是「返回值」

机制类文档最容易犯的错：**只测「401 是不是 401」**。那只能证明「有东西拒绝了」，**证不了「拒绝发生在了正确的位置、正确的层」**。

| 要验的东西 | 返回值能证明什么 | 真正要查的地方 |
|---|---|---|
| B-04 验签在预算外 / B-03 413 压过 401 | 401 发生了 | **验签来源**：探针 Filter 读 `JwtAuthFilter.VERIFICATION_SOURCE_ATTRIBUTE` ⇒ 必须是 `"filter"`（若是 `"inline"` ⇒ 验签退回预算内，401 照样发生，**但机制没按设计工作**）；再发一个**超大 body + 坏 token** ⇒ 必须是 **413**（若得到 **401** ⇒ Filter 写了响应 ⇒ B-03 被破坏） |
| B-15 层次分界 | 状态码 | 同一类「身份不可用」要打出**两种**结果：claim 畸形 ⇒ 200+1006；`uid=0` 打读端点 ⇒ 200+1006、打写端点 ⇒ 200+1003 ⇒ **层次不同 ⇒ 码与状态码的组合不同** |
| B-12 attribute 类型 | 业务层拿到了 uid | 在业务层读 `request.getAttribute("userId")` 并打印 `getClass()` ⇒ 必须是 `Long` |
| B-10 fail-open | 请求**通过**了 | 关掉 Redis ⇒ 请求仍然通过 + 服务端有 `warn` ⇒ **证明故障不被放大** |
| B-11 `jwt_token` 未写 | —— | 命中黑名单的请求 ⇒ 读 `request.getAttribute("jwt_token")` ⇒ 必须是 **null** |

> 💡 **机制验证的通用手法**：「**在不改变生产代码的前提下，挂一个探针把内部状态读出来**」。
> 本仓已有一个现成的实现范式：`UserApiHttpContractTest` 里的 `JwtVerificationProbeFilter`（`:338-360`）—— 一个 `@TestConfiguration` 里注册的 `FilterRegistrationBean`，`setOrder(HIGHEST_PRECEDENCE + 100)`（排在 `JwtAuthFilter` 之后），把 request attribute 抄进响应头 `X-Jwt-Verification-Source` ⇒ 【推导】**这个探针是本仓最值得复用的测试资产**：任何「机制在不在正确的位置生效」的问题，都应该照它做一个。

#### 11.D.4 怎么验证「错误码与状态码的映射」，而不只是「抛了异常」

正确写法要断言**三件事**：

1. **HTTP 状态码**（鉴权失败 ⇒ **401**；身份层失败 ⇒ **200**）
2. **body 里的业务码**（两者都是 1006 ⇒ **只看码分辨不出来**）
3. **body 的形状**（信封三字段，`data` 存在）

⇒ **关键**：因为 B-15（1006 在两层都出现），**断言业务码是不够的** ⇒ 必须**同时断言 HTTP 状态码**：claim 畸形 ⇒ `status == 200` **且** `code == 1006`；缺 header ⇒ `status == 401` **且** `code == 1006` ⇒ 只写 `code == 1006` 的测试，会让「把 200 改成 401」这种改动**照样绿**。

#### 11.D.5 回归范围：改了这个机制，什么必须重新验

| 改动类型 | 必须回归 |
|---|---|
| 改 `JwtAuthPaths` | **7 个受保护端点的可达性** ＋ B-01 + B-02 + 跨文档 01~09 全部（每个端点都依赖 uid） |
| 改 `JwtAuthFilter` 的 `@Order` | B-03 + B-04（挪到 `+7` 之后 ⇒ 验签落回预算内）＋ B-03（挪到 `+8` 之前 ⇒ 413/401 优先级） |
| 改 `JwtVerificationResult` 的分量 | Filter 与 Interceptor **两侧**同时改 ⇒ B-05 + B-16 + 探针测试 `:248` |
| 改 claim 名 / 算法 | **跨文档**：01（签发）＋ 08/09（取 uid）＋ 06（注销读 `jwt_token`）＋ 12（gRPC 入口也用同一套身份） |
| 改 `IdentityResolver` 的两个方法 | B-08 + **6 个调用点**（`UserApiService.java:237,305,361,458,522,590`）的语义 |
| 改密钥校验 | B-13 + 01（登录签发）—— **签发端与验签端必须同源** |
| 改黑名单策略（fail-open ↔ fail-closed）/ 改两段取 token 的方法 | B-09 + B-10 + B-11 + **跨文档 06**（注销写黑名单 ⇒ 黑名单 key 的读侧与写侧必须同源） |

### 11.E 出现问题时如何定位

> 🔴 **红线**：定位不是「看日志 / 查数据库 / 上 Debug」。
> 定位是**一串有顺序的排除**，每一步都问「**这一步的检查能排除什么**」。
> 下面三个案例都来自本机制**真实存在的机制**（不是虚构的故障剧本）。

#### 11.E.1 案例一：401 一切正常，但探针显示验签来源是 `inline`

| 要素 | 内容 |
|---|---|
| **1. 故障现象** | 客户端行为完全正常（带合法 token 能拿到 200），401 也能正常返回；**但**探针响应头 `X-Jwt-Verification-Source` 的值是 **`inline`** 而不是 `filter` |
| **2. 预期行为** | 验签发生在 `JwtAuthFilter`（`+6`）⇒ 落在 3s 预算**之外** ⇒ B 侧 `DRIFT-USERAPI-009②` 闭环 |
| **3. 实际行为** | 验签发生在 `JwtAuthInterceptor` 的兜底分支 ⇒ 落在 3s 预算**之内** ⇒ 机制**能工作**，但**没有按设计工作** |
| **4. 排查入口** | **不是**看 401（401 是对的），而是看**「Filter 有没有把结果写进 attribute」**：读 `request.getAttribute(JwtAuthFilter.VERIFICATION_ATTRIBUTE)` 是否为 null（null ⇒ Interceptor 落兜底，`JwtAuthInterceptor.java:84`） |
| **5. 检查顺序** | ① attribute 为 null ⇒ **Filter 没写** ⇒ 继续 → ② Filter 没写 ⇒ 它的 `if (JwtAuthPaths.isProtected(uri))` 没进去 ⇒ 查 **`uri` 是什么**（`request.getRequestURI()`，`:102`）—— ⚠️ **这里有一个坑**：若请求 URI 带了**上下文前缀**或做过 **URL 编码/规范化**，`getRequestURI()` 返回的值可能与 `isProtected` 期望的 `/user/v1/...` 不一致 ⇒ ③ 查 `JwtAuthPaths.isProtected(uri)` 对这个具体 uri 返回什么 → ④ 若 `isProtected` 返回 true 而 attribute 仍为 null ⇒ 查 **Filter 有没有被注册**（`@Component` + `@Order`，`:71-72`）—— 它是 `@Component` ⇒ 由组件扫描注册 ⇒ 查扫描路径 → ⑤ 若 `isProtected` 返回 false ⇒ 查 `isProtected` 的三个分支（`:76-82`）：`uri == null`？`isPublic`？前缀不匹配？ |
| **6. 可能原因** | ① **`isProtected(uri)` 与 Interceptor 的 `isPublic(uri)` 对同一个 uri 给出不同答案**（**判据不一致** ⇒ 这正是 `JwtAuthPaths` 类注释警告的第二个缝隙）② Filter **没被注册**（组件扫描路径不对 / 被 `@ConditionalOnXxx` 排除）③ Filter 的 `@Order` 被改到 `+8` 之后（但那样仍会写 attribute ⇒ 不会落 inline）④ **URI 形态**与判据期望不符（上下文路径、编码、尾斜杠）⑤ 有人在 Filter 里加了「public 路径也写 attribute」的逻辑，把失败结果写成了非 null ⇒ 那样 source 会是 `filter` ⇒ **排除** |
| **7. 排除过程** | ① **探针读到 `inline`** ⇒ 直接证明 Interceptor 没拿到 Filter 的结果（`JwtAuthInterceptor.java:89` 写的就是 `SOURCE_INLINE`）⇒ **排除 ③**（Order 不影响「写不写 attribute」）⇒ ② 在 Filter 里把 `uri` 与 `isProtected(uri)` 的返回值打出来 ⇒ 若 `isProtected` 为 false ⇒ **命中 ④/⑤**；若为 true 而 attribute 仍为 null ⇒ **命中 ②** ⇒ ③ 若是 ④：拿这个 uri 手工过一遍 `JwtAuthPaths.isPublic` / `isProtected` ⇒ ④ 若是 ②：查容器里有没有 `JwtAuthFilter` 这个 bean（`UserServiceApplicationTests#jwtTwoStageBeansExist` 断言的是「bean 存在」，可复用） |
| **8. 根因确认** | 判据 = **「Filter 在这个 uri 上到底执行没执行、写没写 attribute」**。<br>⚠️ 注意：**401 正常**这件事**不能**作为「机制工作正常」的证据 ⇒ 它只证明「有人验了签」⇒ ⇒ 要证明「验签在预算外」，**必须**看探针。 |
| **9. 影响范围** | **7 个受保护端点全部**受影响（不是某一个）⇒ 但**功能上不受影响**（401 照常）⇒ 影响的是**超时语义**：验签耗时现在**计入** 3s 预算 ⇒ 慢验签（密钥慢、大 payload）时更可能触发 503 ⇒ **症状会表现为「偶发 503」而不是「401 不对」** ⇒ 这就是为什么这个故障很难被普通用例发现 |
| **10. 修复与验证** | 修判据或注册 ⇒ 回归 **B-01、B-02、B-04** ＋ **探针测试 `jwtVerificationHappensInFilter` 必须重新变绿**（它就是这条的守门用例）＋ 跨文档 01~09（每个端点的 uid 来源） |

#### 11.E.2 案例二：token 签名合法，业务层却报「TOKEN已过期」（1006，HTTP 200）

| 要素 | 内容 |
|---|---|
| **1. 故障现象** | 客户端带一个**自己刚签发**的 token 打受保护端点 ⇒ 收到 **HTTP 200 + `{code:1006, msg:"TOKEN已过期", data:null}`**；但**不是 401** |
| **2. 预期行为** | 合法 token ⇒ 业务层拿到 uid ⇒ 正常 200 |
| **3. 实际行为** | 鉴权层放行了（**没有 401**）⇒ 但业务层拿不到 uid ⇒ 抛 `ErrInvalidClaimException` ⇒ 被 API 层翻成 1006 |
| **4. 排查入口** | **区分「在哪一层失败」** —— 因为 1006 在两层都出现（`AuthenticationException` 与 `ErrInvalidClaimException` 都会走到 1006）。判据 = **HTTP 状态码**：401 ⇒ 鉴权层；**200 ⇒ 身份层** ⇒ ⇒ **本案例是 200 ⇒ 问题在身份层** |
| **5. 检查顺序** | ① 确认是 **200**（不是 401）⇒ 问题在身份层 ⇒ ② 查 `request.getAttribute("userId")` 的**值与类型**：`IdentityResolver.claimedUIDAllowNonPositive` 判的是 `v instanceof Long`（`:264`）⇒ 若 attribute 是 **null** ⇒ 是「claim 不可用」这一支 ⇒ 若 attribute 是 `Integer` ⇒ 类型不对 ⇒ 若 attribute 存在但 `uid<=0` ⇒ 是 `claimedUID` 的第三种情形 ⇒ ③ 回上游查 **`JwtSignatureVerifier` 的 claim 读取**（`:101-104`）：`claim instanceof Number` ⇒ 若不成立 ⇒ **打的是 `warn`**「JWT claim userId missing or wrong type」⇒ **这条 warn 就是根因的指纹** ⇒ ④ 若 claim 确实是 `Number`，查**它是不是被写成了 `Long`**（`:102` 用的是 `Long.valueOf(...)` ⇒ 应当没问题 ⇒ 若不是 ⇒ 说明这一行被改过） |
| **6. 可能原因** | ① **claim 名不匹配**：token 里的 claim 不叫 `userId`（例如签发端写了 `user_id` / `uid`）⇒ `payload.get("userId")` 返回 null ⇒ `claimUsable=false` ⇒ **不写 attribute** ⇒ 业务层失败（**主因**）② claim 值是**字符串**（`"1001"` 而非 `1001`）⇒ `instanceof Number` 为 false ⇒ 同上 ③ `userId` attribute 被写成了 `Integer`（有人改了 `:102`）④ `uid <= 0`：claim 是数字但是 0 或负数 ⇒ 走到 `claimedUID` 的第三种情形（`:240-242`）⇒ 此时 attribute **是写了的**，值是 0/负数 ⇒ 客户端能区分 ①③ 与 ④（①③ attribute 为 null 或类型不对；④ attribute 有值但非正） |
| **7. 排除过程** | ① 看**服务端有没有那条 warn**：「JWT claim userId missing or wrong type」⇒ 若**有** ⇒ 命中 ① 或 ② ⇒ ② **把 token 的 payload 解开看一眼**（本地一步：`echo <token> \| cut -d. -f2 \| base64 -d`）⇒ claim 名是 `userId` 吗？值是数字还是字符串？⇒ **这一步直接给出答案** ⇒ ③ 若 payload 里 `userId` 是一个**数字**、warn 却还在打 ⇒ 说明 `instanceof Number` 没成立 ⇒ 查 jjwt 的反序列化类型 ⇒ ④ 若**没有** warn ⇒ 鉴权层认为 claim 正常 ⇒ 回到 ② 的④分支（`uid<=0`）⇒ 查 attribute 的具体值 |
| **8. 根因确认** | 判据 = **「token payload 里的 claim 名与类型」这两条**。<br>⚠️ 注意「签发端与验签端用同一个 claim 名」是**跨文档**的约定：签发在 `common/security/JwtUtil.java:45`（`.claim("userId", userId)`），验签在 `JwtSignatureVerifier.java:101`（`payload.get("userId")`）⇒ **两处各写了一份字面量** ⇒ 改一处不改另一处 ⇒ **签名照样通过**（签名只保证「没被篡改」，不保证「claim 名对」）⇒ ⇒ 这就是「签名合法但业务失败」这类故障的**结构性来源**。 |
| **9. 影响范围** | **全部 7 个受保护端点**（凡是用了 `claimedUID` 的：get / avatar / avatar/select / avatar/history）⇒ 且**症状完全一样**（200 + 1006）⇒ 会被误判为「客户端 token 过期」⇒ 实际排查成本很高。⚠️ 但 **update / delete 两个端点不同**：它们用 `claimedUIDAllowNonPositive` ⇒ `uid<=0` 这一支不会失败 ⇒ 但「claim 缺失 / 类型不对」那一支仍会失败 |
| **10. 修复与验证** | 修 claim 名或类型 ⇒ 回归 **B-07、B-08、B-12** ＋ **跨文档 01**（签发端）＋ **跨文档 08 / 09**（取 uid 的两个端点）＋ 探针测试（B-16）仍须为 `filter`（因为这次故障**不代表**验签位置有问题 ⇒ 别顺着探针去查，那是**第二条路径**） |

#### 11.E.3 案例三：用户注销了，但他的 token 还能继续用

| 要素 | 内容 |
|---|---|
| **1. 故障现象** | 用户调用 `POST /user/v1/logout` 注销成功；随后用**同一个** token 打受保护端点 ⇒ **仍然通过**（不是 401） |
| **2. 预期行为** | 注销写入黑名单 ⇒ 该 token 后续应被拒（401） |
| **3. 实际行为** | 放行 ⇒ 说明**黑名单查的 key 与注销写的 key 对不上**（或黑名单根本没写进去） |
| **4. 排查入口** | **两处 key 的字面值**：读侧 `JwtAuthInterceptor.java:136`（`BLACKLIST_KEY_PREFIX + token`，前缀在 `:61`）vs 写侧 `UserDomainService.java:773`（`"user:jwt_blacklist:" + req.getToken()`）⇒ **两个字符串必须逐字相同** |
| **5. 检查顺序** | ① 先确认**黑名单里到底有没有那个 key**：`EXISTS user:jwt_blacklist:<token>` ⇒ ①-a **没有** ⇒ 问题在**写侧**（注销没写进去 / 写了别的 key / TTL 到了）⇒ ①-b **有** ⇒ 问题在**读侧**（读的是另一个 key）→ ② 若是 ①-b：把**读侧真正用的 token** 打出来（`result.trimmedToken()`，`JwtAuthInterceptor.java:111`）与**写侧用的 token** 打出来（`req.getToken()`）⇒ **逐字比对** ⇒ ③ 若是 ①-a：查写侧的调用链 —— `UserApiService#logout` 读的是 `httpRequest.getAttribute("jwt_token")`（`:407`）⇒ **这个 attribute 是 `trimmedToken`**（`JwtAuthInterceptor.java:120`）⇒ 若写侧用的确实是同一个串 ⇒ 查 Redis 写入本身（TTL / 序列化 / key 前缀） |
| **6. 可能原因** | ① **两段取 token 的分叉**（**主因**）：客户端用**小写 `bearer `** 发 token ⇒ 验签侧（大小写不敏感）剥掉前缀、验签通过；但黑名单侧（大小写敏感）**没剥** ⇒ 读的 key 是 `user:jwt_blacklist:bearer eyJ...` ⇒ 而注销写的是 `user:jwt_blacklist:eyJ...` ⇒ **两个 key 永远对不上**（**这是真源自身的不一致**，`BearerTokenExtractor.java:94-99` 的 javadoc 明确写了）② 写侧读了**另一个 attribute** ③ Redis TTL 已过期 ④ 黑名单压根没写（写失败被吞） |
| **7. 排除过程** | ① `EXISTS` 查两个 key：**规范写法**的 key 与**小写写法**的 key ⇒ 哪个存在 ⇒ **命中 ①** ② 看服务端有没有打那条 warn：「user blacklist token != jwt token (auth header is not 'Bearer <token>')」（`JwtAuthInterceptor.java:121-124`）⇒ **有这条 warn ⇒ 直接命中 ①**（这条 warn 就是为此准备的）③ 若**没有** warn 且读侧 key 与写侧 key 逐字相同 ⇒ 排除 ①、② ⇒ 查写侧 ④ |
| **8. 根因确认** | 判据 = **「读侧 token 与写侧 token 的逐字比对」+「那条 warn 是否出现」**。<br>⚠️ **不要**在这里「顺手统一两套取法」—— `BearerTokenExtractor.java:8-11` 的 javadoc 明确写了：「两套都必须各自逐字对齐，**合并即等于擅自修好 B 的 bug —— 那会制造一处新的语义漂移**。」⇒ ⇒ 正确处置是**登记**（`docs/Intentional_Deviations/users/` 或 `docs/L3-降级清单.md`），**不是**改代码。 |
| **9. 影响范围** | 影响**所有带黑名单语义的操作** ⇒ 即「注销后 token 仍可用」⇒ 而这**恰恰是注销要解决的事** ⇒ 影响面 = 整个黑名单机制的价值。⚠️ 但影响范围**有条件**：只有**用了非规范 `Bearer ` 写法**的客户端会踩到 ⇒ 规范客户端不受影响 ⇒ ⇒ **排查时第一件事是确认客户端的 header 写法** |
| **10. 修复与验证** | **不修代码**（见第 8 项）⇒ 处置 = ① 在**接口文档/客户端规范**里写明「必须用 `Bearer `（大写 B + 一个空格）」② 在**客户端**统一 header 写法 ⇒ 若一定要在服务端防御 ⇒ 那是**另一条需求**（会改变真源语义 ⇒ 需登记 + 决策）⇒ 回归 **B-09、B-10、B-11** ＋ **跨文档 06**（注销写黑名单）＋ 建议补一条「小写 `bearer` ⇒ 断言服务端打了那条 warn」的用例 |

#### 11.E.4 三类故障的「第一动作」速查

| 症状 | 第一动作 | 为什么先做这个 |
|---|---|---|
| 401 / 1006 说不清是哪一层 | **看 HTTP 状态码**（401 = 鉴权层，200 = 身份层） | 同一个码 1006 在两层都出现 ⇒ 状态码是**唯一**的分层信号 |
| 探针显示 `inline` | **打 `uri` 与 `isProtected(uri)` 的返回值** | attribute 为 null ⇒ Filter 没写 ⇒ 先分「判据不符」与「没注册」 |
| 签名合法但业务层失败 | **解开 token 的 payload 看 claim 名与类型** | 签名只保证「没被篡改」，**不保证「claim 名对」** ⇒ 一眼能看出 |
| 注销后 token 仍可用 | **查 Redis 里那个 key 存不存在 + 看有没有那条 warn** | 「两段取法分叉」会打一条专门的 warn ⇒ 有它就直接命中（**不要**顺手统一两套取法，见 11.E.3 第 8 项） |
| 偶发 503 而不是 401 | **看探针的 source** | 401 正常但验签落回预算内 ⇒ 慢验签时更易触发超时 ⇒ 症状在 503，病根在位置 |
| `userId` 读不出来 | **打 attribute 的值与 `getClass()`** | `instanceof Long` 是硬判据 ⇒ 类型不对与值为 null 是**两个**病因 |

### 11.F 需求变化时如何修改

> 本节的三个场景**刻意分成三类**：
> **F-1** = 当前项目**未实现**、纯**教学假设**（明确标记）；
> **F-2** = 当前项目**未实现**、纯**教学假设**（另一类改动：规则的迁移）；
> **F-3** = **看似相关但不应该改**（说明为什么）。

#### 11.F.1 场景一（教学假设）：新增第 8 个受保护端点

> ⚠️ **当前项目是 7 个受保护端点**（9 个减去 login / register）⇒ 本节是**假设场景**，
> 它的价值在于：**演示「加一个端点」对这套机制的影响面**——**可能比想象中小，也可能比想象大**。

| # | 内容 |
|---|---|
| **1. 原有业务行为** | public = {register, login}；protected = `/user/v1` 前缀下其余全部（`JwtAuthPaths.java:41-43,50,75-83`）⇒ **7 个端点受保护**；`WebMvcConfig` 注册 `/user/v1/**`（`:42-46`） |
| **2. 新需求改变了什么** | 在 `/user/v1` 下新增一个端点，例如 `POST /user/v1/avatar/preview`（**受保护**） |
| **3. 哪些原有行为必须保持不变** | ① public 集合**仍是**那两个（新端点是受保护的 ⇒ 不加进 `PUBLIC_PATHS`）② 现有 7 个端点的行为**逐字不变** ③ 401 的信封形状不变 ④ `userId` attribute 的名与类型不变 ⑤ 探针仍能读到 source |
| **4. 哪些组件需要修改** | ① **新增**：Controller 方法 ② **可能要改**：`JwtAuthPaths`？—— ⚠️ **不需要**！因为 `isProtected` 是**前缀匹配**（`/user/v1` + `/`）⇒ 新端点**自动**被纳入 protected ⇒ **判据不用改** ③ **不需要**改 `WebMvcConfig`（前缀已覆盖）④ **不需要**改 Filter / Interceptor / Verifier ⑤ **可能需要**：API Service 里取 uid 的那一行（照现有 6 个调用点的模式） |
| **5. 为什么这些组件需要修改** | 这是本章最重要的一条结论：**「判据用前缀 + 白名单」的设计，使得「加端点」的机制侧成本接近零** ⇒ 新端点**自动**受保护 ⇒ **不会漏鉴权** ⇒ 反过来，如果当初把判据写成「逐个路径枚举」，每加一个端点就要改一次判据 ⇒ **漏一个 = 裸奔** ⇒ ⇒ 这就是 B-02「判据只有一份」的**真正价值**：不只是「不写两份」，而是「**一处修改，覆盖所有**」 |
| **6. 哪些组件不应该修改** | ① **`JwtAuthPaths.PUBLIC_PATHS`**（新端点不是 public）② `JwtAuthFilter` / `JwtAuthInterceptor` / `JwtSignatureVerifier` / `JwtVerificationResult` / `IdentityResolver`（**一行都不该动**）③ `WebMvcConfig` ④ `UserApiProperties`（除非新端点要独立的密钥） |
| **7. 是否需要新增 DTO / Entity / 字段 / 方法 / 配置 / 约束** | 需要：**新 DTO**、**新 Controller 方法**、**新 API Service 方法**。**不需要**：新鉴权组件、新 attribute、新配置、新 DB 约束、**新 proto message**（若不走 gRPC） |
| **8. 是否影响接口契约 / Proto / DB 结构 / 其它服务** | **影响 HTTP 契约**（新增路径 ⇒ 新增契约 ⇒ 走 L1 流程）。**不影响 proto**（若该端点不出现在 gRPC 接口上）。**不影响 DB**。**不影响其它服务** |
| **9. 是否影响事务 / 幂等 / 并发 / 安全 / 异常处理** | **安全**：🔴 **最大的一条风险是「忘记它需要鉴权」** —— 但本设计下**不存在**这个问题（前缀自动覆盖）⇒ 这正是前缀式判据的价值。**异常处理**：新端点的业务失败走**通用映射**（`NOT_FOUND ⇒ 1003` 等）⇒ 若新端点需要特殊码 ⇒ 照 08 号文档的做法**显式**在 API 层覆盖。**事务 / 幂等 / 并发**：与本机制无关，由端点自身决定。**超时**：新端点的耗时**计入** 3s 预算（验签不算）⇒ 若新端点很慢 ⇒ 走超时 503 ⇒ 与鉴权无关 |
| **10. 合理的修改顺序** | ① 定**路径与 method**（契约）→ ② 写 **DTO** → ③ 写 **Controller**（照现有端点的签名模式：`@Valid @RequestBody XxxReq` + `HttpServletRequest`）→ ④ 写 **API Service**（**第一行**就该是 `IdentityResolver.claimedUID(httpRequest)`）→ ⑤ 写**领域层** → ⑥ 补**测试**：**必须包含「无 token ⇒ 401」与「有 token ⇒ 进业务层」两条** ⇒ ⑦ 验证**探针 source 仍为 `filter`**（新端点没有特殊之处 ⇒ 应当天然成立 ⇒ 若不成立 ⇒ 说明机制被改坏了） |
| **11. 需要验证哪些新行为** | ① 无 token ⇒ **401 + 完整信封** ② 有**坏** token ⇒ 401 ③ 有合法 token ⇒ 进业务层 ④ `userId` attribute 的值是 **`Long`** ⑤ 探针 source == `filter` ⑥ 该端点的业务错误码符合预期 |
| **12. 需要回归哪些旧行为** | ① **B-01**（public 集合没被误改）② **B-04/B-16**（探针仍是 `filter`）③ **B-13**（密钥守卫没被碰）④ **跨文档 01~09**：机制侧**不该有任何回归**（一行没改）⇒ 但**必须显式跑一遍**探针测试与 `UserApiHttpContractTest` 全类 ⇒ 因为「一行没改」是**推理**，不是**证据** |

#### 11.F.2 场景二（教学假设）：把「claim 的存在性与取值」从业务层前移到鉴权层

> ⚠️ 同样是**假设场景**：当前实现**刻意**把 claim 判定留在业务层（B-07/B-08，理由见 B 侧 `DRIFT-USERAPI-001`）。

| # | 内容 |
|---|---|
| **1. 原有行为** | 签名合法但 `userId` 不是数字 ⇒ **不拒绝**、不写 attribute ⇒ 请求进业务层 ⇒ 业务层抛 `ErrInvalidClaimException` ⇒ **HTTP 200 + 1006**；`uid<=0` 同理由业务层判定 |
| **2. 新需求改变了什么** | 安全团队要求：「**身份字段不对的 token 一律 401**」⇒ 把 claim 的存在性/类型/`uid>0` 判定**前移到鉴权层** |
| **3. 哪些原有行为必须保持不变** | ① 401 的**信封形状**不变 ② **签名错 / 过期**仍然是 401（不变）③ 黑名单命中仍然 401（不变）④ **public 路径**仍然放行（不变）⑤ 密钥 fail-closed 不变 ⑥ 探针机制不变 |
| **4. 需要改的组件** | ① `JwtSignatureVerifier`：claim 不是 `Number` 时，**从「只 warn」改成「返回 failed(AuthenticationException)」**（`:106-114`）② `JwtAuthInterceptor`：把 `uid<=0` 的判定加进来（目前**完全不在**鉴权层）③ **业务层的 `IdentityResolver`**：**两个方法都要保留吗？** —— 【推导】鉴权层一旦拒了 `uid<=0` 与「类型不对」，则 `claimedUID` 与 `claimedUIDAllowNonPositive` 的**差别就只剩「谁先判」**⇒ 实际上两者的**可观察差异会消失**（B-08 的那个「读端点 1006 / 写端点 1003」的不对称会消失）④ **测试**：`UserApiHttpContractTest` 里**没有**针对 claim 畸形与 `uid=0` 的 HTTP 用例 ⇒ 需要**新增**（当前是「未覆盖」，见 11.D.1） |
| **5. 为什么这些组件需要修改** | ① 「不拒绝」这个决定**只在** `JwtSignatureVerifier` 的 claim 分支里 ⇒ 改一处即可 ② `uid<=0` 的判定**目前完全不在**鉴权层 ⇒ 要新加 ③ 【推导】**业务层的方法不能顺手删** —— 因为 `IdentityResolver` 是**共享层**（user 子域与 admin 子域各有一个 JWT 拦截器）⇒ admin 侧若还没前移 ⇒ 删掉方法会让 admin 侧编译不过 ⇒ ⇒ **共享层的 API 面要先确认** |
| **6. 不应该改的** | ① **不要**把 claim 判定从 `IdentityResolver` 里删掉（它是共享层，且 gRPC 入口也要用）② **不要**改 `JwtAuthFilter`（它在 Filter 层拒绝会造成「401 早于 413」的语义变化 ⇒ **B-03 被破坏**！）⇒ ⇒ 🔴 **claim 判定若要变成 401，必须在 `JwtAuthInterceptor` 里做**（或让 Filter 写一个 failed 结果、由 Interceptor 抛）⇒ **绝不能**让 Filter 自己写 401 ③ **不要**改黑名单策略 ④ **不要**改两段取 token 的方法 |
| **7. 是否需要新增 DTO / Entity / 字段 / 方法 / 配置 / 约束** | 可能需要：**新的异常码**（若要用一个不同于 1006 的码表达「claim 畸形」）⇒ 那要动 `ErrCodes` / `ErrMsg`（**契约**）。不需要：新 DTO、新 Entity、新列、新配置、新 DB 约束 |
| **8. 是否影响接口契约 / Proto / DB 结构 / 其它服务** | **影响 HTTP 契约**：claim 畸形 / `uid=0` 的响应从 **200 + 1006** 变成 **401** ⇒ 客户端的分支逻辑会变（它可能把 401 当成「重新登录」⇒ **行为上是「重新登录」**，与原来「弹个错」不同）⇒ ⇒ **必须走 L1 变更 + 客户端同步**。**影响 proto**？—— gRPC 入口也走同一个 `IdentityResolver` ⇒ 若前移到鉴权层 ⇒ **gRPC 的错误码形态也会变** ⇒ 🔴 **这是跨协议的影响**，必须一并评估。**不影响 DB** |
| **9. 是否影响事务 / 幂等 / 并发 / 安全 / 异常处理** | **安全**：↑（畸形 token 不再进业务层）⚠️ 但**代价**是「claim 畸形」与「token 无效」对外**同形**（都是 401 + 1006）⇒ 客户端**分不出**「我的 token 过期了」与「服务端的 claim 解析规则变了」⇒ 【推导】**这是可用性与可观测性的取舍**：现在两者可用 HTTP 状态码区分（200 vs 401），改后不能。**异常处理**：层次分界（B-15）被**部分抹平** —— ⚠️ 而 `AuthenticationException` 的 javadoc（`common/exception/AuthenticationException.java:15-18`）明确说「**不能靠业务码区分层次**……**所以层次必须由类型承载**」⇒ 本场景正是要把两层合并 ⇒ ⇒ **要么接受层次合并，要么引入新码**。**幂等 / 并发 / 事务**：无影响 |
| **10. 合理的修改顺序** | ① 先确认**共享层的面**（admin 子域是否也用 `IdentityResolver`）→ ② 定**是否引入新码**（还是复用 1006）→ ③ 定**要不要在 gRPC 侧同样前移**（⇒ 决定是否影响 proto 语义）→ ④ 先在 **`JwtSignatureVerifier`** 上改（让它能表达「claim 不可用 ⇒ failed」）→ ⑤ 再在 **`JwtAuthInterceptor`** 上加 `uid<=0` 判定（**注意：必须在 Filter 之后、抛异常之前**）→ ⑥ **新增测试**：claim 畸形 ⇒ 401；`uid=0` 打读端点 ⇒ 401（不再是 200+1006）→ ⑦ 回归 **跨文档 01 / 03 / 08 / 09 / 12** ⇒ ⑧ **登记偏离**（这会改变与 B 侧的 `DRIFT-USERAPI-001` 语义的一致性） |
| **11. 验证新行为** | ① claim 是字符串 ⇒ **401 + 完整信封** ② claim 缺失 ⇒ 401 ③ `uid=0` ⇒ 401（**所有**端点，包括 update / delete —— 这正是与旧行为的差别）④ 签名错 ⇒ 仍 401 ⑤ 过期 ⇒ 仍 401 ⑥ public 路径 ⇒ 仍放行 ⑦ **探针 source 仍为 `filter`** ⑧ **响应头优先级**：坏 token + 超大 body ⇒ 仍应是 **413**（⇒ 证明没有在 Filter 里写响应） |
| **12. 需要回归哪些旧行为** | B-01、B-03、B-05 / B-06、B-10、B-11、B-13、B-15（**层次分界变了 ⇒ 这条要重新评估**）、B-16 ＋ **跨文档 01**（签发端不受影响，但要确认 claim 写法没变）＋ **跨文档 03 / 08 / 09**（取 uid 的端点）＋ **跨文档 12**（gRPC 入口的身份判定链）＋ **跨文档 06**（注销读 `jwt_token` ⇒ `uid<=0` 的 token 注销会变成 401） |

#### 11.F.3 场景三：看似相关、但**不应该**改的地方

| 看着该改 | 为什么**不该**改 |
|---|---|
| 「把 Filter 和 Interceptor 合成一个，代码更少」 | 验签会落回 3s 预算**之内** ⇒ B 侧 `DRIFT-USERAPI-009②` **不闭环** ⇒ 而且「判据两份」的缝隙问题也会回来（虽然合并后只有一份）—— 真正的代价是**预算语义** |
| 「让 Filter 在验签失败时直接写 401，少一次 attribute 传递」 | Filter 在 `+6`、`MaxBytes` 在 `+8` ⇒ 401 会**压过** 413 ⇒ 与 B 侧相反；且 `@ExceptionHandler` 管不到 Filter 写的响应 ⇒ 401 的 body 会退化成空 body ⇒ **两条红线同时被破坏**（B-03 + B-05） |
| 「把两处取 token 的方法统一成一套」 | `BearerTokenExtractor.java:8-11` 的 javadoc 明确写了：合并 = **擅自修好 B 侧的 bug** = 制造一处新的语义漂移 ⇒ 正确处置是**登记**，不是改代码 |
| 「黑名单改成 fail-closed（Redis 挂了就拒）」 | 与 B 侧 `err == nil && exists` 不一致 ⇒ 且会把一次基础设施故障升级成**全站不可用** ⇒ 除非需求明确要求「安全优先于可用」并接受这条漂移，否则不改（要改就走决策 + 登记） |
| 「把 `uid<=0` 的判定统一掉（两个方法合并成一个）」 | `IdentityResolver.java:230-232` 的 javadoc 明说「不要合并这两个方法」⇒ 合并等于擅自修好 B 侧的不对称 ⇒ **写端点与读端点的码会一起变**（1006 → 1003 或反之）⇒ 跨端点的客户端分支逻辑会变 |
| 「给鉴权失败加一个指标（401 计数）」 | 现在**没有**指标是**事实**，不是缺陷 ⇒ 加指标本身没问题，但它**改变不了行为**；⚠️ 真要加 ⇒ 要注意**不要**因此把「鉴权失败」变成 `error` 级日志（现在是 `warn`/`debug`，因为 401 是预期内的客户端行为） |

#### 11.F.4 修改的四种类型，一句话区分

| 类型 | 判断标准 | 处理方式 |
|---|---|---|
| **需求确实要求改** | 新需求直接点名了它 | 改，并补验证 |
| **为保持原有行为必须同步改** | 不改它，新需求就会**破坏**旧行为 | 一起改，且**在回归清单里显式列出** |
| **看似相关但不该改** | 改了会让**别的**契约漂移 | **不改**，把理由写进台账 / 注释 |
| **项目没实现、仅教学演示** | 现状里根本不存在 | **明确标记为假设**，不得写成「现有功能」 |

### 11.G 本章的边界

| 项 | 说明 |
|---|---|
| **本章能给你什么** | 一条**可复用**的推导链：系统要求 → 行为清单 → 组件 → 代码 → 验证 → 排错 → 变更；外加三条本机制特有的纪律：**「判据只有一份、且用前缀 + 白名单」**、**「失败必须以异常对象跨层传递」**、**「层次由类型承载，不由错误码承载」** |
| **本章给不了什么** | 「原作者当初为什么这么设计」—— 仓库里没有设计记录 ⇒ 这类问题一律【存疑】；也包括「3s 预算是否真的不含验签耗时」这种**依赖外部前提（go-zero 链序）**的判断，以及「Redis 故障期间的真实影响面」这种**需要演练才能回答**的问题 |
| **哪些内容是本机制特有的** | 「Filter 与 Interceptor 两段式」「Filter 绝不写响应」「探针 attribute」「`Long` 类型的 `userId`」「fail-open 的黑名单」「claim 判定留在业务层」—— 这些是**本域鉴权机制**的属性，换一套鉴权方案要重新推导 |
| **哪些内容是通用的** | 「先定行为再定组件」「判据要收在一处，且要覆盖全部入口」「跨层传值要选对载体」「用类型而不是码来分层」「机制必须配探针才能验证」「区分已验证 / 仅推导」—— 这些在别的业务同样成立 |

> 📌 **本篇是整个 users 域的「前置阅读」**：01~09 号文档里的每一个「uid 从哪来」「为什么 401」「为什么这里是 1006 而不是 1003」，答案都在本章的**行为清单**里。
> ⇒ 建议顺序：**先读本章的 11.A.3（行为清单）与 11.A.6（状态机）** → 再回 01~09 逐篇对照 ⇒ 最后读 `../README.md` 的索引。
