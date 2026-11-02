# 14. 管理员 JWT 认证与黑名单流程

## 1. 本次流程的研究范围

这一篇追踪的是：**一个带 `Authorization: Bearer <token>` 的请求，是怎么被判断「能不能进业务代码」的**。

具体追踪范围：

| 追踪 | 不追踪 |
|---|---|
| Servlet Filter 链里 `AdminJwtSignatureFilter` 做了什么 | Tomcat 内置 filter（`CharacterEncodingFilter` 等）的行为 |
| `AdminJwtAuthInterceptor.preHandle` 的每一条分支 | 业务方法内部（登录 / 注销 / 改资料，见 01~13 篇） |
| `AdminJwtSignatureVerifier.verify` 的解析与验签 | JWT 的签发过程（在 `JwtUtil.getToken`，属于 01 篇） |
| `BearerTokenExtractor` 的两套取 token 方式 | user 域的同名类（形状一样，但**判据不同**，见 §4.1） |
| Redis 黑名单的**读** | Redis 黑名单的**写**（在 `AdminDomainService#logout`，见 [06-管理员注销与黑名单流程.md](./06-管理员注销与黑名单流程.md)） |
| 密钥从哪来、为什么缺了会启动失败 | 密钥的加密存储（生产密钥不入仓，本仓只管「非空 + 非占位符」） |

**一个必须先说清的边界**：本流程的终点不是「Controller 被调用」，而是
**「request attribute `userId` 与 `jwt_token` 就位」**。业务代码从这里往下走。

---

## 2. 触发场景与输入

### 2.1 典型请求

```
POST /admin/v1/getself HTTP/1.1
Host: localhost:8889
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9....

{}
```

### 2.2 输入是什么、从哪来

| 输入 | 来源 | 谁消费 |
|---|---|---|
| `request.getRequestURI()` | Servlet 容器 | 判「这条路径要不要鉴权」 |
| `request.getHeader("Authorization")` | 客户端 HTTP 头 | 验签 + 黑名单 key |
| `request.getDispatcherType()` | Servlet 容器 | 判「这次是不是一次真正的业务请求」 |
| `admin-api.admin-auth.access-secret` | `application.yml` → `${ADMIN_JWT_SECRET}` | 构造 HMAC 验签密钥 |

### 2.3 受保护路径到底是哪些

`AdminJwtAuthInterceptor.AdminJwtAuthPaths.PROTECTED_PATHS`（`interceptor/AdminJwtAuthInterceptor.java:319-331`）
枚举了 **11 条完整路径**：

```
/admin/v1/logout
/admin/v1/getself
/admin/v1/update
/admin/v1/updateuser
/admin/v1/delete
/admin/v1/resetuserpassword
/admin/v1/banuser
/admin/v1/unbanuser
/admin/v1/getuser
/admin/v1/getuserlist
/admin/v1/invite/create
```

另外 2 条是 public（`:294-296`）：

```
/admin/v1/login
/admin/v1/create
```

**11 + 2 = 13**，正好等于 `AdminController` 的 13 个 `@PostMapping`。
这条「数量必须相等」的约束被测试钉死：`AdminHttpContractRealTomcatTest#pathSetMatchesControllerMappings`
（`src/test/java/com/rag/admin/http/AdminHttpContractRealTomcatTest.java:186`）会遍历
`RequestMappingHandlerMapping` 里 `AdminController` 的真实映射，与两个常量集合逐条比对 ——
**加端点忘了加进 `PROTECTED_PATHS`，测试立刻红**。

---

## 3. 整体执行路线

```mermaid
flowchart TD
    A["客户端 POST /admin/v1/getself<br/>Authorization: Bearer &lt;token&gt;"] --> B["Tomcat Filter 链"]
    B --> C["AdminJwtSignatureFilter<br/>@Order(HIGHEST_PRECEDENCE + 6)"]
    C --> C1{"DispatcherType == ERROR ?"}
    C1 -->|是| C2["直接 chain.doFilter 放行<br/>（不验签）"]
    C1 -->|否| C3{"isProtected(uri) ?"}
    C3 -->|否（public / 未知路径）| C2
    C3 -->|是| C4["AdminJwtSignatureVerifier.verify<br/>解析 + 验签 + 取 claim"]
    C4 --> C5["写 request attribute<br/>VERIFICATION_ATTRIBUTE<br/>VERIFICATION_SOURCE_ATTRIBUTE = filter"]
    C2 --> D["DispatcherServlet.doDispatch"]
    D --> E["路由匹配 → AdminController#getSelf"]
    E --> F["AdminJwtAuthInterceptor.preHandle"]
    F --> F1{"DispatcherType == ERROR ?"}
    F1 -->|是| F9["return true（放行）"]
    F1 -->|否| F2{"isProtected(uri) ?"}
    F2 -->|否| F9
    F2 -->|是| F3{"attribute VERIFICATION_ATTRIBUTE<br/>是否为 null ?"}
    F3 -->|是（Filter 未命中）| F4["log.warn + 就地验签<br/>source = inline"]
    F3 -->|否| F5["source = filter<br/>消费 Filter 的验签结果"]
    F4 --> F6
    F5 --> F6{"result.failed() ?"}
    F6 -->|是| F7["throw result.failure()<br/>AuthenticationException"]
    F6 -->|否| F8["写 attribute userId = uid"]
    F8 --> F10{"Redis 有 admin:jwt_blacklist:&lt;token&gt; ?"}
    F10 -->|有| F7
    F10 -->|没有 / 查询失败（fail-open）| F11["写 attribute jwt_token"]
    F11 --> G["return true → 业务方法执行"]
    F7 --> H["AdminApiExceptionHandler<br/>@ExceptionHandler(AuthenticationException)"]
    H --> I["HTTP 401 + {#quot;code#quot;:1006,#quot;msg#quot;:#quot;TOKEN已过期#quot;,#quot;data#quot;:null}"]
```

**这张图省略了共享层 filter（`PathNormalizingFilter` / `TraceparentFilter` / `MaxConnsFilter` / `BreakerFilter` / `CpuSheddingFilter` / `HttpMiddlewareFilter` / `MaxBytesFilter` / `JsonBodyLimitFilter`）**，
它们在 `AdminJwtSignatureFilter` 前后，具体槽位见 `AdminJwtSignatureFilter` 的类注释。

---

## 4. 逐步讲解

### 4.1 第一步：拦截器是怎么被挂上去的

涉及文件：`config/WebMvcConfig.java`

```java
@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(jwtAuthInterceptor)
            .addPathPatterns("/admin/v1/**");
}
```

（`config/WebMvcConfig.java:84-88`）

`addPathPatterns("/admin/v1/**")` 只说明一件事：**`/admin/v1/` 开头的请求会走进这个拦截器**。
它**不**说明「这些请求都要鉴权」。

真正的鉴权判据在 `preHandle` 内部。这两个概念必须分开：

- `addPathPatterns()` 决定拦截器**在哪些路径上被执行**；
- `AdminJwtAuthPaths.isProtected(uri)` 决定**这一次要不要做身份验证**。

#### ⚠️ 这里与 user 域不一样（最容易看错的地方之一）

| 域 | 判据 | 源码 |
|---|---|---|
| **admin** | **显式路径集合**（11 条） | `AdminJwtAuthPaths#isProtected` → `PROTECTED_PATHS.contains(uri)`（`interceptor/AdminJwtAuthInterceptor.java:348-350`） |
| **user** | **URI 前缀** | `JwtAuthPaths#isProtected` → `uri.equals("/user/v1") \|\| uri.startsWith("/user/v1/")`（`service/users/user/.../interceptor/JwtAuthPaths.java`） |

⇒ user 域的同款讲解见 [10-JWT认证与身份传递流程.md](../users/10-JWT认证与身份传递流程.md)。

后果非常具体：

- admin 域收到 `POST /admin/v1/not-exist` ⇒ **不在 11 条里 ⇒ 放行** ⇒ 由路由层给 **404**；
- user 域收到 `POST /user/v1/not-exist` ⇒ **命中前缀 ⇒ 走鉴权** ⇒ 没有 token 就是 **401**。

这不是 admin 域「写漏了」，而是有意改成与 B 侧同构（B 侧的 JWT 中间件挂在**已注册的 route** 上，
未知路径根本进不了 route，走的是另一条没有鉴权的 `notFoundHandler` 链）。

改判据这件事在源码里有完整论证，见 `AdminJwtAuthPaths.PROTECTED_PATHS` 的 javadoc
（`interceptor/AdminJwtAuthInterceptor.java:298-318`）。

### 4.2 第二步：`AdminJwtSignatureFilter` —— 只验签、绝不写响应

涉及文件：`interceptor/AdminJwtSignatureFilter.java`

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class AdminJwtSignatureFilter extends OncePerRequestFilter {
```

（`interceptor/AdminJwtSignatureFilter.java:113-115`）

它是 Servlet `Filter`，而 `HandlerInterceptor` 在 `DispatcherServlet` **里面**。
这个位置差是本流程整个设计的起点：**验签被特意放在 Filter 里，是为了让它跑在 3 秒超时预算之外**。

`doFilterInternal` 的完整逻辑（`:141-166`）：

```java
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
    String uri = request.getRequestURI();
    if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ERROR) {
        chain.doFilter(request, response);
        return;
    }
    if (AdminJwtAuthInterceptor.AdminJwtAuthPaths.isProtected(uri)) {
        AdminJwtAuthInterceptor.AdminJwtVerificationResult result =
                AdminJwtSignatureVerifier... verify(request.getHeader("Authorization"), uri);
        request.setAttribute(VERIFICATION_ATTRIBUTE, result);
        request.setAttribute(VERIFICATION_SOURCE_ATTRIBUTE, SOURCE_FILTER);
        if (result.failed()) {
            log.debug("admin JWT verification failed (deferred to interceptor) : {}", uri);
        }
    }
    chain.doFilter(request, response);
}
```

三个要点：

1. **验签失败时它不写 401**，只打一条 `debug` 日志。
   为什么？因为 401 要由 `AdminApiExceptionHandler` 的 `@ExceptionHandler` 产出（这样才有完整信封 `{code,msg,data}`）。
   如果 Filter 自己写响应，`@ExceptionHandler` 就管不到了。
   源码注释里还记了一条更重要的理由：**Filter 排在 `MaxBytesFilter`（+8）之前**，
   Filter 自己写 401 会让 401 压过 413；B 侧是 **413 压过 401** ⇒ 顺序反了就是漂移。

2. **无条件 `chain.doFilter`**（`:165`）：验签失败也要把 Filter 链走完，
   这样后面的 `MaxBytesFilter` 仍有机会写 413。

3. **路径判据与 Interceptor 共用同一份** `AdminJwtAuthPaths.isProtected`。
   如果两段各写一份判据，会出现两个缝隙：
   - Filter 判「受保护」而 Interceptor 判「放行」⇒ **鉴权被绕过**（安全漏洞）；
   - Filter 判「放行」而 Interceptor 判「受保护」⇒ Interceptor 拿不到验签结果 ⇒ 落进兜底分支 ⇒
     验签退回 3 秒预算**之内** ⇒ 预算语义静默失效。

#### 判据的完整分支

```mermaid
flowchart TD
    A["request 进入 preHandle"] --> B{"DispatcherType == ERROR ?"}
    B -->|"是（Tomcat 对 sendError 的二次派发）"| C["放行，不鉴权<br/>⇒ 404 保持 404"]
    B -->|"否"| D{"uri 在 PROTECTED_PATHS（11 条）里？"}
    D -->|"是"| E["完整走：验签 → 写 userId → 查黑名单 → 写 jwt_token"]
    D -->|"否（public 2 条 / 未知路径 / 其它域路径）"| F["放行"]
    F --> G{"这个路径有 handler 吗？"}
    G -->|"有（public 端点）"| H["进 Controller"]
    G -->|"没有（未知路径）"| I["BasicErrorController / NoResourceFoundException<br/>⇒ 404"]
```

#### 两段式的交接（Filter → attribute → Interceptor）

```mermaid
flowchart TD
    A["AdminJwtSignatureFilter.doFilterInternal"] --> B{"isProtected(uri) ?"}
    B -->|"否"| C["不写 attribute<br/>chain.doFilter 放行"]
    B -->|"是"| D["AdminJwtSignatureVerifier.verify<br/>产出 AdminJwtVerificationResult"]
    D --> E["写 attribute<br/>VERIFICATION_ATTRIBUTE = result<br/>VERIFICATION_SOURCE_ATTRIBUTE = 'filter'"]
    E --> F["result.failed() ?"]
    F -->|"是"| G["log.debug（不写响应！）<br/>chain.doFilter 继续"]
    F -->|"否"| H["chain.doFilter 继续"]
    G --> I["DispatcherServlet → 路由匹配"]
    H --> I
    I --> J["AdminJwtAuthInterceptor.preHandle<br/>读 VERIFICATION_ATTRIBUTE"]
    J --> K{"attribute == null ?"}
    K -->|"否"| L["VERIFICATION_SOURCE_ATTRIBUTE = 'filter'<br/>（守门测试据此断言）"]
    K -->|"是（Filter 未命中）"| M["log.warn + 就地验签<br/>VERIFICATION_SOURCE_ATTRIBUTE = 'inline'"]
    L --> N["消费 result"]
    M --> N
```


### 4.3 第三步：`AdminJwtSignatureVerifier.verify` —— 解析、验签、取 claim

涉及文件：`interceptor/AdminJwtSignatureVerifier.java`

#### 密钥是怎么来的

```java
public AdminJwtSignatureVerifier(com.rag.admin.config.AdminApiProperties properties) {
    this.key = Keys.hmacShaKeyFor(
            JwtUtil.padToHmacKeySize(properties.getAdminAuth().getAccessSecret()));
}
```

（`interceptor/AdminJwtSignatureVerifier.java:74-87`）

两件事在这**一行**里：

- `padToHmacKeySize`（`common/.../security/JwtUtil.java:102-111`）把密钥补齐到 **32 字节**。
  jjwt 0.12 强制 HS256 的 key ≥ 256 bit，而本仓的 admin 密钥默认只有 15 字节 ⇒ 不补齐会在 Bean 创建时就抛
  `WeakKeyException`。补的是**零**（`byte[]` 默认初始化为 0），且**签发端 `JwtUtil.getToken`（`:40`）用的是同一个方法**
  ⇒ 两端落在同一份 32 字节 key 上。
- `Keys.hmacShaKeyFor` 把 JCA 算法名钉死成 `HmacSHA256`，与签发端 `new SecretKeySpec(..., "HmacSHA256")` 同族。

#### 验签的三个早期失败点

```java
if (authHeader == null || authHeader.isEmpty()) {
    log.warn("protected admin path {} without Authorization header", uri);
    return ...failed(new AuthenticationException(ERROR_TOKEN_RUNTIME, ErrMsg.getMsg(ERROR_TOKEN_RUNTIME)));
}

String token = BearerTokenExtractor.tokenForSignatureVerification(authHeader);
if (token.isEmpty()) {
    log.warn("protected admin path {} with empty Bearer token", uri);
    return ...failed(new AuthenticationException(...));
}
```

（`interceptor/AdminJwtSignatureVerifier.java:99-116`）

注意：**这两个失败都产出 `AuthenticationException`（业务码 1006），但 HTTP 状态码是 401，不是 200**。
这正是 `AuthenticationException` 存在的唯一理由 —— 它继承 `BusinessException`，
唯一区别就是 advice 会给它 401（见 [15-异常处理与统一响应流程.md](./15-异常处理与统一响应流程.md) §4.3）。

#### claim 的处理：admin 侧与 user 侧**刻意不同**

```java
Object claim = jws.getPayload().get("userId");
if (claim instanceof Number) {
    uid = ((Number) claim).longValue();
} else {
    log.warn("admin JWT claim userId missing or wrong type : {}", ...);
    return ...failed(new AuthenticationException(ERROR_TOKEN_RUNTIME, ...));
}
```

（`interceptor/AdminJwtSignatureVerifier.java:124-136`）

| 域 | claim 缺失 / 类型不对 | 结果 | 源码 |
|---|---|---|---|
| **admin** | **拒绝** | **401** + 业务码 1006 | `AdminJwtSignatureVerifier.java:128-136` |
| **user** | **不拒绝**，只打 warn，继续走黑名单 | **HTTP 200** + 业务码 1006（由业务层 `IdentityResolver.claimedUID` 拒） | `service/users/user/.../JwtSignatureVerifier.java:106-114` |

user 侧的理由是：go-zero 的 `rest.WithJwt` 只验签名 + exp，**claim 的取值属于 identity 层**。
admin 侧保留了「畸形 claim = 401」的语义。

**两域刻意不统一，这是源码里明确写出来的**（`AdminJwtSignatureVerifier.java:128-130`：「本类**不**顺手统一两域」）。

#### ⚠️ 鉴权层不校验 `uid > 0`

`uid <= 0` **不是**鉴权层的规则。它在 identity 层：

```java
public static long claimedUID(HttpServletRequest request) {
    long uid = claimedUIDAllowNonPositive(request);
    if (uid <= 0) {
        throw new ErrInvalidClaimException(MSG_INVALID_CLAIM);
    }
    return uid;
}
```

（`service/users/common/.../IdentityResolver.java:240-246`）

`IdentityResolver.claimedUID` 由 `AdminApiService` 的业务方法调用
（例如 `AdminApiService#getSelf` 的第一行 `AdminApiService.java:371`），
抛出的 `ErrInvalidClaimException` 被 advice 映射成 **HTTP 200 + 业务码 1006**
（`advice/AdminApiExceptionHandler.java:185-190`）。

所以同一个「uid 非法」，在不同层面得到不同形态：
**鉴权层不碰它 → 业务层拒绝 → HTTP 200 + 1006**。

### 4.4 第四步：`BearerTokenExtractor` —— 两套取法（本篇最容易看错的地方）

涉及文件：`common/src/main/java/com/rag/common/security/BearerTokenExtractor.java`

这是本篇**最需要慢下来读**的一段。两个方法长得像，行为完全不同。

#### 方法 A：`tokenForSignatureVerification`（**验签用**）

```java
public static String tokenForSignatureVerification(String authHeader) {
    if (authHeader.length() > BEARER_PREFIX.length() - 1
            && BEARER_PREFIX_UPPERCASE.equals(
                    authHeader.substring(0, BEARER_PREFIX.length()).toUpperCase(Locale.ROOT))) {
        return authHeader.substring(BEARER_PREFIX.length());
    }
    return authHeader;
}
```

（`common/.../security/BearerTokenExtractor.java:74-87`）

三条规则，逐条：

1. **长度判定是 `> 6`**（`BEARER_PREFIX.length() - 1` = 7 - 1）。
   ⇒ `"Bearer "` 正好 7 个字符 ⇒ 满足 `> 6` ⇒ **被剥成空串**。
   ⇒ `"Bearer"` 6 个字符 ⇒ 不满足 ⇒ **原样当 token**。
2. **大小写不敏感**：前 7 个字符 `toUpperCase` 后与 `"BEARER "` 比较 ⇒ `"bearer "` / `"BEARER "` 都剥。
3. **不匹配时原样返回整串**，不报错、不置空。

第 3 条是**安全关键**的。若改成 `split(" ", 2)[1]`，`"XXBearer eyJ..."` 会变成用 `"eyJ..."` 去验签而通过，
而 B 侧是用整串验签必然失败 ⇒ **构成注销绕过**。

#### 方法 B：`trimBearerPrefix`（**黑名单 key 用**）

```java
public static String trimBearerPrefix(String authHeader) {
    return authHeader.startsWith(BEARER_PREFIX)
            ? authHeader.substring(BEARER_PREFIX.length())
            : authHeader;
}
```

（`common/.../security/BearerTokenExtractor.java:101-105`）

- **大小写敏感**的精确前缀 `"Bearer "`（`BEARER_PREFIX` 常量，`:35`）；
- 同样不匹配时原样返回整串。

#### 两套取法的实际后果

| `Authorization` 头 | 验签用的 token | 黑名单查的 key |
|---|---|---|
| `Bearer eyJ...` | `eyJ...` | `admin:jwt_blacklist:eyJ...` |
| `bearer eyJ...` | `eyJ...` | `admin:jwt_blacklist:bearer eyJ...` ⚠️ |
| `XXBearer eyJ...` | `XXBearer eyJ...`（原样） | `admin:jwt_blacklist:XXBearer eyJ...` |
| `Bearer `（只有前缀） | `""`（空）⇒ 401 | —（根本没到这一步） |
| `Bearer`（无空格） | `Bearer`（原样）⇒ 验签失败 | — |

**第二行是关键**：小写 `bearer` 的请求**验签能过**，但**黑名单查的是另一个 key** ⇒ 已拉黑的 token 仍然可用。
源码明确写了这句：「这是 B 侧自身的不一致，本实现原样保留」
（`interceptor/AdminJwtAuthInterceptor.java:214-216`）。

代码里还有一处主动告警：

```java
request.setAttribute("jwt_token", jwtToken);
if (!jwtToken.equals(result.signatureToken())) {
    log.warn("admin blacklist token != jwt token (auth header is not 'Bearer <token>')");
}
```

（`interceptor/AdminJwtAuthInterceptor.java:227-231`）

⇒ **只要你在日志里看到这行 warn，就说明这个客户端的 `Authorization` 头不是规范写法**。

### 4.5 第五步：`preHandle` —— 消费验签结果 + 查黑名单

涉及文件：`interceptor/AdminJwtAuthInterceptor.java`

#### 5.1 ERROR 派发不进鉴权（为什么必须有这条）

```java
if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ERROR) {
    return true;
}
```

（`interceptor/AdminJwtAuthInterceptor.java:146-148`）

这不是随手加的防御。完整因果链（源码 `:126-145` 有完整记录）：

1. Spring Boot 3.5 的 `DispatcherServlet.throwExceptionIfNoHandlerFound` **默认 false**
   ⇒ 路由没命中时不抛异常，而是 `response.sendError(404)`；
2. Tomcat 收到 `sendError` 后做一次 **ERROR 派发** ⇒ 重新进 `DispatcherServlet`；
3. 这一次**有 handler 了** —— `BasicErrorController` 的 `/error` ⇒ `applyPreHandle` **会跑**；
4. 若拦截器此时按 URI 前缀判「受保护」⇒ 抛 `AuthenticationException` ⇒ **404 被顶成 401**。

⇒ 修法：**ERROR 派发一律放行**（它不是一次「业务请求」）。

`AdminJwtSignatureFilter` 里有同款判断（`:148-151`），理由略不同：Filter 本来就「绝不写响应」，
跳过它只是**不产生误导性日志**。

#### 5.2 消费 Filter 的结果 / 兜底就地验签

```java
AdminJwtVerificationResult result = (AdminJwtVerificationResult) request.getAttribute(
        AdminJwtSignatureFilter.VERIFICATION_ATTRIBUTE);
if (result == null) {
    log.warn("admin JWT 验签未命中 filter 缓存 ⇒ 就地验签（DRIFT-USERAPI-009② 在此路径未闭环）: {}", uri);
    request.setAttribute(AdminJwtSignatureFilter.VERIFICATION_SOURCE_ATTRIBUTE,
            AdminJwtSignatureFilter.SOURCE_INLINE);
    result = verifier.verify(request.getHeader("Authorization"), uri);
} else {
    request.setAttribute(AdminJwtSignatureFilter.VERIFICATION_SOURCE_ATTRIBUTE,
            AdminJwtSignatureFilter.SOURCE_FILTER);
}
```

（`interceptor/AdminJwtAuthInterceptor.java:181-194`）

**两个 request attribute**：

| attribute | 值 | 写者 | 读者 |
|---|---|---|---|
| `com.rag.admin.interceptor.jwt.verification` | `AdminJwtVerificationResult` | `AdminJwtSignatureFilter` | `AdminJwtAuthInterceptor` |
| `com.rag.admin.interceptor.jwt.verificationSource` | `"filter"` / `"inline"` | 两处都写 | 守门测试（断言 interceptor 确实消费了 Filter 的结果） |

**兜底分支不会造成鉴权绕过**：就地验签的行为与 Filter 里完全一样，401 照常。
但它意味着**验签落回 3 秒预算之内** ⇒ 该路径上「验签在预算外」这条语义**没有闭环** ⇒ 所以要**大声记日志**（`warn` 级，不是 `debug`）。

#### 5.3 阶段顺序：**先验签、后黑名单**

```java
if (result.failed()) {
    throw result.failure();
}
```

（`interceptor/AdminJwtAuthInterceptor.java:197-199`）

验签失败 ⇒ **立刻抛** ⇒ **根本走不到黑名单查询**。

这带来一个非常具体、非常容易写错测试的可观察差异：

> **一个签名无效、但已经在黑名单里的 token** ⇒
> **验签先失败 ⇒ 401 + 空 body（B 侧）/ 401 + 完整信封（本仓）**，而**黑名单从未被查询**。

源码里把这个差异写得很直白（`interceptor/AdminJwtAuthInterceptor.java:171-176`）：

```
【DRIFT-009 已回退】本类此前实现的是「黑名单先、验签后」⇒ 已改回验签优先。
可观察差异（修复前）：
  一个**签名无效但已在黑名单中**的 token，
    B 侧   → Authorize 先失败 → 401 + 空 body，黑名单**从不查询**
    RAG  → 先打一次 Redis hasKey → 401 + envelope{code:1006}
```

如果哪天有人把这两段调换顺序，就制造了一处**新的 wire 漂移**。

#### 5.4 写 `userId` attribute

```java
request.setAttribute("userId", Long.valueOf(result.uid()));
```

（`interceptor/AdminJwtAuthInterceptor.java:204`）

⚠️ **属性名是字面量 `"userId"`**，不是 `IdentityResolver.CLAIM_ATTRIBUTE` 常量 ——
但两者值**恰好相同**（`service/users/common/.../IdentityResolver.java:85`：`CLAIM_ATTRIBUTE = "userId"`）。
这是「拦截器 ↔ 解析器」之间的契约，两域各写一份字面量是 B 侧那种「同一规则的第二份实现」的温床。

值类型必须是 `Long`：`IdentityResolver.claimedUIDAllowNonPositive` 里是
`if (!(v instanceof Long)) throw new ErrInvalidClaimException(...)` —— **类型不对就是非法 claim**。

#### 5.5 查黑名单（fail-open）

```java
String jwtToken = result.trimmedToken();
if (!jwtToken.isEmpty() && isBlacklisted(jwtToken)) {
    log.warn("admin token in blacklist, uri={}", uri);
    throw new AuthenticationException(
            ErrCodes.ERROR_TOKEN_RUNTIME,
            ErrMsg.getMsg(ErrCodes.ERROR_TOKEN_RUNTIME));
}
```

（`interceptor/AdminJwtAuthInterceptor.java:217-223`）

```java
private boolean isBlacklisted(String token) {
    String blacklistKey = BLACKLIST_KEY_PREFIX + token;
    Boolean exists;
    try {
        exists = redisTemplate.hasKey(blacklistKey);
    } catch (Throwable t) {
        log.warn("admin Redis blacklist check failed : {}", t.getMessage());
        exists = null;
    }
    return exists != null && exists;
}
```

（`interceptor/AdminJwtAuthInterceptor.java:242-252`）

**fail-open 的确切含义**：

| Redis 状态 | `exists` | 结果 |
|---|---|---|
| key 存在 | `true` | **401** |
| key 不存在 | `false` | 放行 |
| **Redis 抛异常** | `null`（异常被吞成 null） | **放行** ⚠️ |

⇒ **Redis 挂掉时，被拉黑的 token 也能继续用**。
这是对齐 B 侧的行为（B：`exists, err := Redis.ExistsCtx(key); if err == nil && exists { 401 }` ——
`err != nil` 时条件为 false ⇒ 放行）。代价是 Redis 故障期间注销能力失效，收益是 Redis 故障不会打挂整个管理域。

**黑名单 key 前缀**：

```java
private static final String BLACKLIST_KEY_PREFIX = "admin:jwt_blacklist:";
```

（`interceptor/AdminJwtAuthInterceptor.java:109`）

⚠️ admin 用 `admin:` 前缀，user 域用 `user:` 前缀 ⇒ **两个域的黑名单互相隔离**。
测试 `AdminHttpContractRealTomcatTest#blacklistedTokenIs401AndUsesAdminScopedKey`（`:344`）
专门断言了这一点：写入 `admin:jwt_blacklist:<token>` 后 401，同时断言 `user:jwt_blacklist:<token>` **不存在**。

写入方在 `AdminDomainService#logout`（`domain/service/AdminDomainService.java:319-342`）：
`stringRedisTemplate.opsForValue().set(key, "banned", Duration.ofSeconds(ttlSeconds))`，
TTL = `admin-rpc.admin-auth.access-expire`（默认 2592000 秒 = 30 天，**与 JWT 有效期对齐**）。

### 4.6 密钥从哪来、为什么缺了就起不来

涉及文件：`config/AdminApiProperties.java` · `config/AdminRpcProperties.java` · `src/main/resources/application.yml`

yml 里**没有默认值**：

```yaml
admin-api:
  admin-auth:
    access-secret: ${ADMIN_JWT_SECRET}
    access-expire: ${ADMIN_JWT_EXPIRE:2592000}    # 30 天
admin-rpc:
  admin-auth:
    access-secret: ${ADMIN_JWT_SECRET}
    access-expire: ${ADMIN_JWT_EXPIRE:2592000}    # 30 天
```

（`service/users/admin/src/main/resources/application.yml:138-145`）

两个配置类各有一道**启动期**守卫：

```java
@PostConstruct
void verifyAccessSecret() {
    String secret = adminAuth == null ? null : adminAuth.getAccessSecret();
    if (secret == null || secret.isBlank()) {
        throw new IllegalStateException(
                "admin-api.admin-auth.access-secret 不能为空：请设置环境变量 ADMIN_JWT_SECRET（与 admin-rpc 共享同一把密钥）"
                        + "—— 本模块既签发又验签，空密钥会让任何人都能伪造 admin JWT（越权）");
    }
    if (secret.contains("${")) {
        throw new IllegalStateException(
                "admin-api.admin-auth.access-secret 仍是未解析的占位符（" + secret + "）：请设置环境变量 ADMIN_JWT_SECRET"
                        + "—— 占位符字面量是公开在仓里的常量，拿它当密钥 = 密钥公开");
    }
}
```

（`config/AdminApiProperties.java:77-90`；`AdminRpcProperties` 的同款在 `:88-101`）

**两条守卫堵的是两条不同的路**：

1. **变量设了但值是空串** ⇒ Spring 的 `PropertyPlaceholderHelper` 把空串当合法值 ⇒ 属性绑成 `""`
   ⇒ 应用带着**空 HMAC 密钥**正常起来 ⇒ **admin token 可伪造（越权）**。
2. **变量没设** ⇒ 绑上去的**不是 `null`，而是占位符字面量** `"${ADMIN_JWT_SECRET}"`
   （`PropertySourcesPlaceholdersResolver` 用 `ignoreUnresolvablePlaceholders = true`）
   ⇒ 只判「null / 空串」**堵不住这条路** ⇒ 必须显式拒绝「仍是占位符」的值。

⇒ 两者都是 **fail-closed**：启动失败，而不是「启动成功但验签永远失败」。

⚠️ **`admin-api` 与 `admin-rpc` 两段都读同一个环境变量** ⇒ 两者必须共享同一把密钥，
否则跨进程 JWT 校验会失败。

> 顺带一提：`admin-rpc.admin-auth.access-secret` 在本仓**没有运行时消费者**
> （admin-rpc 侧只消费 `AccessExpire`）⇒ 它这一段的守卫是**纯 fail-closed 收紧**，不改变任何已存在的取值。

---

## 5. 重要分支与失败路径

### 5.1 判据层分支

| 条件 | 走哪一步 | 客户端可观察结果 |
|---|---|---|
| `DispatcherType == ERROR` | `preHandle` 立刻 `return true`（`:146-148`） | 由错误页 / `BasicErrorController` 决定，**不被鉴权插手** |
| 路径在 `PROTECTED_PATHS` 里 | 完整走验签 + 黑名单 | 200 或 401 |
| 路径是 public（`/login`、`/create`） | 放行 | 由业务决定 |
| 路径**不在** 11 条里的任何 `/admin/v1/**` 路径 | 放行 | **404**（`text/plain` + nosniff + body `"404 page not found\n"`） |
| 路径不在 `/admin/v1/**`（如 `/actuator/health`） | 拦截器**根本不执行** | 200（Actuator 自己的响应） |

### 5.2 验签阶段分支

| 条件 | 产出 | HTTP | body |
|---|---|---|---|
| `Authorization` 头缺失 / 空串 | `AuthenticationException(1006)` | **401** | `{"code":1006,"msg":"TOKEN已过期","data":null}` |
| 剥掉 `Bearer ` 后 token 为空 | 同上 | **401** | 同上 |
| 签名错 / token 过期 | 同上 | **401** | 同上 |
| `claim.userId` 缺失或类型不对 | 同上 | **401** | 同上 |
| 验签通过 | `AdminJwtVerificationResult.ok(uid, signatureToken, trimmedToken)` | — | — |

### 5.3 黑名单阶段分支

| 条件 | 结果 |
|---|---|
| `admin:jwt_blacklist:<trimmedToken>` 存在 | **401** + `{"code":1006,...}`，不写 `jwt_token` attribute，不进业务 |
| 不存在 | 写 `jwt_token` attribute，放行 |
| **Redis 查询抛异常** | **fail-open ⇒ 放行**（只打一条 `warn`） |
| `trimmedToken` 为空串 | 跳过黑名单查询（`!jwtToken.isEmpty()` 短路） |

### 5.4 完整失败路径：从异常产生到最终响应

```mermaid
flowchart TD
    A["AdminJwtSignatureVerifier.verify<br/>产出 failed(...) 结果"] --> B["AdminJwtSignatureFilter<br/>只写 attribute + debug 日志<br/>（不写响应）"]
    B --> C["chain.doFilter 继续"]
    C --> D["DispatcherServlet → 路由匹配"]
    D --> E["AdminJwtAuthInterceptor.preHandle"]
    E --> F["读 VERIFICATION_ATTRIBUTE"]
    F --> G{"result.failed() ?"}
    G -->|是| H["throw result.failure()<br/>= AuthenticationException"]
    G -->|否| I["写 userId attribute"]
    I --> J["黑名单检查"]
    J -->|"命中"| H
    J -->|"未命中 / fail-open"| K["写 jwt_token attribute → 放行"]
    H --> L["Spring MVC 异常解析<br/>找到 @ExceptionHandler(AuthenticationException)"]
    L --> M["AdminApiExceptionHandler#handleAuthentication<br/>response.setStatus(401)"]
    M --> N["ResponseEntity.status(401)<br/>.body(new ApiResponse(1006, msg, null))"]
    N --> O["AdminApiResponseAdvice#beforeBodyWrite<br/>body 已是 ApiResponse ⇒ 不重复包装"]
    O --> P["HTTP 401<br/>Content-Type: application/json<br/>{#quot;code#quot;:1006,#quot;msg#quot;:#quot;TOKEN已过期#quot;,#quot;data#quot;:null}"]
```

**这条链的三个关键点**：

1. 401 **不是** Filter 写的，是 `preHandle` 抛异常、由 advice 产出的 ⇒ body 是**完整信封**；
2. `beforeBodyWrite` 看到 `body instanceof ApiResponse` ⇒ **不再包一层**（否则会变成 `{"code":200,"msg":"OK","data":{"code":1006,...}}`）；
3. Filter 排在 `MaxBytesFilter`（+8）**之前**且**不写响应** ⇒ 请求体超限时 **413 压过 401**（与 B 侧一致）。

---

## 6. 数据变化与副作用

| 维度 | 本流程是否发生 | 说明 |
|---|---|---|
| **数据库** | ❌ 未发生 | 验签与黑名单都不碰 DB |
| **Redis 读** | ✅ 发生（仅受保护路径） | `StringRedisTemplate.hasKey("admin:jwt_blacklist:" + trimmedToken)`；**只读**，不写、不设 TTL |
| **Redis 写** | ❌ 本流程未发生 | 写黑名单在 `AdminDomainService#logout`（见 [06](./06-管理员注销与黑名单流程.md)） |
| **事务** | ❌ 无 | 本流程不接触 MyBatis ⇒ 无事务边界 |
| **远程调用** | ❌ 无 | 纯本地 Servlet + Redis |
| **日志** | ✅ 多条 | `warn`：缺头 / 空 token / 签名错 / 畸形 claim / 黑名单命中 / Redis 故障 / 兜底验签 / 两套 token 不一致；`debug`：Filter 里的验签失败 |
| **指标** | ❌ **本类不埋点** | 源码明确说明：admin 域的 `admin_api_intercept_total` 由 `metrics/AdminApiMetrics` 承载，**埋点在业务层**（B 侧同样在 Service 层） |
| **生成 ID / Token** | ❌ 无 | 验签不签发 token；签发在 `JwtUtil.getToken`（01 篇） |

### 6.1 request attribute 的最终状态

| attribute | 类型 | 值 | 何时写 |
|---|---|---|---|
| `userId` | `Long` | claim 里的管理员 uid | 验签通过后（`:204`） |
| `jwt_token` | `String` | `trimBearerPrefix` 的产物 | 黑名单检查通过后（`:227`） |
| `com.rag.admin.interceptor.jwt.verification` | `AdminJwtVerificationResult` | — | Filter 写 / 兜底时不变 |
| `com.rag.admin.interceptor.jwt.verificationSource` | `String` | `"filter"` 或 `"inline"` | 两处都写 |

### 6.2 失败后哪些操作可能已经发生

- **Redis 查询已经发生**（黑名单检查在验签之后，所以验签失败时 Redis **没被碰**）；
- **日志已经写了**（`warn` / `debug`）；
- **request attribute 可能已经写了一部分**：
  验签失败 ⇒ `userId` / `jwt_token` **都没写**；
  黑名单命中 ⇒ `userId` **已写**、`jwt_token` **没写**。

⇒ 结论：**本流程无副作用残留**，除了日志与一次（或零次）Redis 读。

---

## 7. 执行结果

### 7.1 成功路径

请求：

```
POST /admin/v1/getself
Authorization: Bearer <合法 token>
```

执行完之后：

| attribute | 值 |
|---|---|
| `userId` | `<claim 里的 uid>` |
| `jwt_token` | `<剥掉 "Bearer " 后的 token>` |

业务代码可以正常拿到管理员身份，进入 `AdminController#getSelf`。

### 7.2 缺 token（401）

```
HTTP/1.1 401 Unauthorized
Content-Type: application/json;charset=UTF-8
```

```json
{"code":1006,"msg":"TOKEN已过期","data":null}
```

（`code` = `ErrCodes.ERROR_TOKEN_RUNTIME` = 1006，`msg` = `ErrMsg.getMsg(1006)` = `"TOKEN已过期"`，
`data` = `null` 且**键存在** —— 因为 `ApiResponse` 上不许加 `@JsonInclude(NON_NULL)`）

由 `AdminHttpContractRealTomcatTest#protectedPathWithoutTokenIs401`（`:238`）钉死：
断言 401、业务码 1006、**`body.has("data")` 为真**。

### 7.3 token 在黑名单里（401）

形状与上面**完全一样**（`{"code":1006,"msg":"TOKEN已过期","data":null}` + 401）。
由 `AdminHttpContractRealTomcatTest#blacklistedTokenIs401AndUsesAdminScopedKey`（`:344`）钉死。

### 7.4 未知路径（404，不鉴权）

```
HTTP/1.1 404 Not Found
Content-Type: text/plain;charset=utf-8
X-Content-Type-Options: nosniff
```

```
404 page not found
```

（末尾带一个 `\n`，共 19 字节）

由 `AdminHttpContractRealTomcatTest#unknownPathIs404`（`:369`）**逐字节**钉死。
注意 body 里**没有** `code` 字段 ⇒ 它**不是**信封。

---

## 8. 流程复盘

```text
HTTP 请求
  ↓
Tomcat Filter 链
  ↓
AdminJwtSignatureFilter.doFilterInternal
  ├─ ERROR 派发 ⇒ 放行
  ├─ 非受保护路径 ⇒ 放行
  └─ 受保护路径 ⇒ AdminJwtSignatureVerifier.verify
        ├─ 缺 Authorization 头 ⇒ failed(AuthenticationException)
        ├─ 空 token ⇒ failed(AuthenticationException)
        ├─ 签名错 / 过期 ⇒ failed(AuthenticationException)
        ├─ claim 畸形 ⇒ failed(AuthenticationException)   ← admin 特有
        └─ 通过 ⇒ ok(uid, signatureToken, trimmedToken)
  ↓  （写 VERIFICATION_ATTRIBUTE，失败也不写响应）
DispatcherServlet.doDispatch
  ↓
AdminJwtAuthInterceptor.preHandle
  ├─ ERROR 派发 ⇒ return true
  ├─ 非受保护路径 ⇒ return true
  ├─ attribute 为 null ⇒ 就地验签（warn）
  ├─ result.failed() ⇒ throw result.failure()   ← 黑名单从此不被查询
  ├─ 写 attribute "userId" = uid
  ├─ isBlacklisted(trimmedToken)
  │     ├─ Redis 命中 ⇒ throw AuthenticationException
  │     └─ Redis 故障 ⇒ fail-open 放行
  ├─ 写 attribute "jwt_token" = trimmedToken
  └─ return true
  ↓
AdminController / AdminApiService（业务代码）
```

**边界结论（这才是这一篇真正的价值）**：

1. **判据是「白名单 11 条完整路径」，不是 URI 前缀**。
   未知路径**放行** ⇒ 404。user 域用前缀 ⇒ 401。两域刻意不同。

2. **验签在 Filter、授权在 Interceptor**，靠一个 request attribute 交接。
   两段共用同一份路径判据，否则要么鉴权被绕过，要么预算语义静默失效。

3. **阶段顺序是「先验签、后黑名单」**。
   签名无效 + 已拉黑 ⇒ **401，黑名单从不查询**。

4. **两套取 token 的方式**：验签大小写不敏感、黑名单大小写敏感。
   `bearer <token>` 会导致验签过、黑名单查不到。

5. **鉴权层不校验 `uid > 0`** ⇒ 那是 identity 层（`IdentityResolver.claimedUID`）的规则 ⇒ HTTP 200 + 1006。

6. **畸形 claim 在 admin 侧是 401**，user 侧是 200 + 1006。**两域刻意不统一。**

7. **黑名单 fail-open** ⇒ Redis 故障时拉黑能力失效，但业务不被打挂。

8. **两个 attribute**：`userId`（`Long`）+ `jwt_token`（`String`），属性名 `"userId"` 与
   `IdentityResolver.CLAIM_ATTRIBUTE` 同值。

9. **密钥 fail-closed** ⇒ 空串 / 未解析占位符 ⇒ **启动失败**，不是「启动成功但验签永远失败」。

---

## 9. 源码定位与验证方式

### 9.1 源码定位

| 位置 | 文件 | 关键成员 |
|---|---|---|
| 拦截器注册 | `service/users/admin/src/main/java/com/rag/admin/config/WebMvcConfig.java` | `addInterceptors`（`:84-88`） |
| 鉴权主体 | `service/users/admin/src/main/java/com/rag/admin/interceptor/AdminJwtAuthInterceptor.java` | `preHandle`（`:122`）· `isBlacklisted`（`:242`）· `AdminJwtAuthPaths`（`:291`）· `AdminJwtVerificationResult`（`:371`） |
| 验签 Filter | `service/users/admin/src/main/java/com/rag/admin/interceptor/AdminJwtSignatureFilter.java` | `doFilterInternal`（`:141`）· `VERIFICATION_ATTRIBUTE`（`:120`）· `SOURCE_FILTER`/`SOURCE_INLINE`（`:129`/`:132`） |
| 验签器 | `service/users/admin/src/main/java/com/rag/admin/interceptor/AdminJwtSignatureVerifier.java` | 构造器（`:74`）· `verify`（`:96`） |
| 取 token | `common/src/main/java/com/rag/common/security/BearerTokenExtractor.java` | `tokenForSignatureVerification`（`:74`）· `trimBearerPrefix`（`:101`） |
| JWT 签发 / 补齐 | `common/src/main/java/com/rag/common/security/JwtUtil.java` | `getToken`（`:31`）· `padToHmacKeySize`（`:102`） |
| 异常类型 | `common/src/main/java/com/rag/common/exception/AuthenticationException.java` · `BusinessException.java` | — |
| 密钥配置 | `service/users/admin/src/main/java/com/rag/admin/config/AdminApiProperties.java` · `AdminRpcProperties.java` | `verifyAccessSecret`（`:77` / `:88`） |
| 配置文件 | `service/users/admin/src/main/resources/application.yml` | `admin-api.admin-auth.*`（`:138`）· `admin-rpc.admin-auth.*`（`:142`） |
| 错误码 | `service/users/common/src/main/java/com/rag/users/common/ErrCodes.java` · `ErrMsg.java` | `ERROR_TOKEN_RUNTIME = 1006`（`ErrCodes.java:118`） |
| identity 层 | `service/users/common/src/main/java/com/rag/users/common/IdentityResolver.java` | `claimedUID`（`:240`）· `CLAIM_ATTRIBUTE`（`:85`） |
| 对照：user 域判据 | `service/users/user/src/main/java/com/rag/user/interceptor/JwtAuthPaths.java` | `isProtected`（前缀式） |

### 9.2 验证方式（真实测试，不是推断）

| 测试方法 | 位置 | 钉住什么 |
|---|---|---|
| `pathSetMatchesControllerMappings` | `AdminHttpContractRealTomcatTest.java:186` | 鉴权路径集合 == `AdminController` 的 13 个真实映射 |
| `protectedPathWithoutTokenIs401` | `:238` | 缺 token ⇒ 401 + 完整信封（`data` 键存在）+ 业务码 1006 |
| `protectedPathWithBrokenTokenIs401` | `:248` | 坏签名 ⇒ 401 |
| `protectedPathWithValidTokenIs200` | `:257` | 合法 token ⇒ 200 + `data` 非空 |
| `blacklistedTokenIs401AndUsesAdminScopedKey` | `:344` | 黑名单 ⇒ 401；key 前缀是 `admin:`，且**不**写 `user:` 前缀 |
| `unknownPathIs404` | `:369` | 未知路径 ⇒ 404 + 逐字节的 B 侧形状 + **不进鉴权** |
| `allThirteenEndpointsArePostOnly` | `:207` | 13 个端点全 POST（GET 一律 405） |

测试用的是 **真 Tomcat + 真 PG + 真 Redis**（`@SpringBootTest(webEnvironment = RANDOM_PORT)` +
Testcontainers），不走 MockMvc —— 因为 `Content-Type` 的最终渲染、`nosniff` 头、状态行这些
只有真容器才能测出来。

### 9.3 交叉引用

- **user 域的同款流程**（前缀式判据 + 畸形 claim 不拒绝）⇒
  [10-JWT认证与身份传递流程.md](../users/10-JWT认证与身份传递流程.md)
- 黑名单的**写**（TTL、value、key 拼法）⇒ [06-管理员注销与黑名单流程.md](./06-管理员注销与黑名单流程.md)
- 401 响应体的产出过程 ⇒ [15-异常处理与统一响应流程.md](./15-异常处理与统一响应流程.md) §4.3
- JWT 的**签发**（`JwtUtil.getToken`）⇒ [01-管理员登录流程.md](./01-管理员登录流程.md)
- gRPC 面上没有 JWT 鉴权（`AdminGrpcService` 不做鉴权）⇒ [16-gRPC管理员服务流程.md](./16-gRPC管理员服务流程.md)

### 9.4 本篇的「源码无法确认」项

| 项 | 说明 |
|---|---|
| `OncePerRequestFilter` 在 ASYNC / ERROR 二次派发时不重复执行 | 这是 Spring 6.2 的**框架默认行为**（`shouldNotFilterAsyncDispatch` 默认 `true`），本域 13 个端点全是同步 MVC ⇒ 该差异在本域不可观测。源码注释已明确记录这一点。**真实 Tomcat 下该行为的最终表现未经本仓活体测试单独验证**。 |
| `request.getDispatcherType()` 在 ERROR 二次派发时确实为 `ERROR` | 这是 Servlet 规范行为，本仓的真 Tomcat 契约测试（`unknownPathIs404` ⇒ 404 而不是 401）**间接**证明它成立；没有单独的探针直接断言 `getDispatcherType()` 的返回值。 |
