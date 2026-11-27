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

---

## 10. 从需求到实现：如果让我重新开发「管理员 JWT 认证与黑名单」

> **本章与前 9 章的分工** 第 1~9 章回答「这套代码跑起来时，一个带 `Authorization` 头的请求到底发生了什么」。本章反过来回答：**如果我只拿到一句「管理端接口要做 JWT 鉴权，注销后 token 要失效」，我该怎么一步步把它推导出上面那套代码。** 本章**不重复**前面的运行流程，只讲**推导过程**：先定行为 → 再定组件 → 再写代码 → 再验证 → 出问题怎么查 → 需求变了怎么改。 ⚠️ **本章写的是「机制」，不是「一个业务端点」。** 「管理员登录」是业务端点（见 [01](./01-管理员登录流程.md)）；「JWT 认证 + 黑名单」是**横切所有受保护端点的一套机制**
> ⇒ 本章的推导单位是**机制** ⇒ 行为清单里的每一条都必须**能被 11 个端点共享**。

### ⚠️ 开工前必须知道的一件事：前 9 章描述的源码状态已经变了

这不是「文档过时了所以别信」，而是**本章最有价值的一段推导素材**——它把「需求变化时如何修改」从抽象建议变成了**可查证的事实**。

| 项 | 前 9 章（§2.3 / §4.1 / §5.1）描述的状态 | **当前仓库源码的真实状态** |
|---|---|---|
| 路径判据 | **显式 11 条路径集合** `PROTECTED_PATHS`（原文标 `:319-331`） | **前缀语义** `PROTECTED_PREFIX = "/admin/v1"`（`interceptor/AdminJwtAuthInterceptor.java:333`），`isProtected` 在 `:352-360` |
| 未知路径 | **放行 ⇒ 404** | **先过鉴权**：无 token ⇒ **401 + 信封 1006**；带 token ⇒ **500 + 信封 5001** |

变化发生在提交 `bb42a56`（E-4 双侧活体对拍）。台账完整记着：`docs/DRIFT/users/admin/verification-admin-2026-11-02.md` 的 **A-30** 行，证据强度 **E-4**，状态**已实现 / 已验证**。 ⇒ **本章正文一律以当前源码为准**；凡引用行号，都是**本章写作时实际打开该文件确认过的行号**。 ⇒ 「前 9 章那套显式集合」在 §10.F.1 里被当作**一次真实发生过的需求变更**处理。

### 10.0 本章的证据分级（通篇使用）

| 标记 | 含义 | 本章用它回答什么 |
|---|---|---|
| 【事实】 | 可在**当前仓库源码**里直接确认的行为 | 「它现在**是什么**」——附 `文件:行号` |
| 【契约】 | 可在 **proto / 建表 SQL / 共享层 / DRIFT 台账**里确认的要求 | 「它**必须**是什么」——附契约出处 |
| 【推导】 | 由前两类推出的设计要求 | 「它**应该**是什么」——**不冒充原作者的意图** |
| 【存疑】 | 现有资料不足以确定 | 明说「不知道」，不编 |

⚠️ **本章没有原始需求文档。** 仓库里**不存在**「管理端鉴权 PRD」这类文件 ⇒ ⇒ 本章的「需求」是**从现有行为反向还原出来的需求模型**，**不是**原开发者的需求记录。 ⇒ ⇒ 凡属「原作者当初为什么这么设计」的判断，一律标【存疑】。 ⇒ ⇒ 这正是本章存在的意义：**从行为反推需求，再从需求正推实现**；反推不回来 ⇒ 说明漏了某个约束。

---

### 10.A 从需求推导业务行为

#### 10.A.1 手上的原始输入，和它到底有多原始

如果我现在只接到一句需求：

> 「管理端接口要做 JWT 鉴权。管理员注销之后，他的 token 要失效。」

它**写不出一行代码**，因为它没回答任何一条**可被测试**的问题：「管理端接口」是哪些？登录接口要不要鉴权？token 怎么签、密钥从哪来、密钥没了会怎样？「注销后失效」是当场失效还是 TTL 到期？失效期间 Redis 挂了怎么办？token 里放什么身份、身份非法算不算鉴权失败？鉴权失败的响应是 HTTP 401 还是 HTTP 200 + 业务码？`bearer xxx` 合法吗？

⇒ **第一步不是写代码，是把这句话变成一组可判定的行为。**

#### 10.A.2 推导的八个问题，以及每个问题的答案出处

| # | 我必须先回答的问题 | 本项目里的答案 | 证据等级 | 依据 |
|---|---|---|---|---|
| Q1 | 谁发起？ | 已登录管理员（持 `Authorization: Bearer <token>`） | 【契约】 | `controller/AdminController.java:123` + `:134`~`:206`（13 个端点） |
| Q2 | 哪些路径要鉴权？ | **非 public 且 URI == `/admin/v1` 或以 `/admin/v1/` 开头**；public = `/admin/v1/login`、`/admin/v1/create` | 【事实】 | `interceptor/AdminJwtAuthInterceptor.java:333` · `:300-302` · `:352-360` |
| Q3 | 输入是什么？ | `Authorization` 头一个字符串 | 【事实】 | `interceptor/AdminJwtSignatureVerifier.java:96` |
| Q4 | 要执行哪些动作？ | 判路径 → 验签 → 取 claim → 写 `userId` → 查黑名单 → 写 `jwt_token` | 【事实】 | `interceptor/AdminJwtSignatureFilter.java:158-167` ＋ `interceptor/AdminJwtAuthInterceptor.java:187-237` |
| Q5 | 什么算成功？ | `userId`（`Long`）与 `jwt_token`（`String`）两个 request attribute 就位，`preHandle` 返回 `true` | 【事实】 | `interceptor/AdminJwtAuthInterceptor.java:210` · `:233` · `:238` |
| Q6 | 什么必须拒绝？ | 缺头 / 空 token / 签名错 / 过期 / claim 畸形 / token 在黑名单 | 【事实】 | `interceptor/AdminJwtSignatureVerifier.java:99-104` · `:110-116` · `:137-146` ＋ `interceptor/AdminJwtAuthInterceptor.java:203-205` · `:223-229` |
| Q7 | 边界与失败？ | Redis 故障（fail-open 放行）· ERROR 二次派发（放行）· 未知路径（先过鉴权）· `uid <= 0`（**鉴权层不管**） | 【事实】 | `interceptor/AdminJwtAuthInterceptor.java:248-258` · `:150-152` ＋ `service/users/common/.../IdentityResolver.java:238-243` |
| Q8 | 哪些行为必须保持？ | method/path、401 的**信封形状**、错误码 1006、黑名单前缀 `admin:`、JWT 的算法与 claim 名 | 【契约】 | `docs/DRIFT/users/admin/verification-admin-2026-11-02.md` A-30 行（E-4 已验证） |

> 🔴 **Q7 里「`uid <= 0` 鉴权层不管」是最容易写错的一条**：常规直觉是「uid 非法 ⇒ 直接 401」。但本项目**鉴权层只验「签名 + exp + claim 的类型」**，**不验 claim 的取值范围** —— `uid > 0` 是**业务层**规则（`IdentityResolver.claimedUID`，`service/users/common/.../IdentityResolver.java:238-243`），违反时得到 **HTTP 200 + 业务码 1006**，**不是 401**。依据：鉴权层只判 `claim instanceof Number`（`interceptor/AdminJwtSignatureVerifier.java:125-136`），取到什么数都收下。
> ⇒ **两域刻意不同**（user 域畸形 claim 甚至不拒绝），源码写明「本类**不**顺手统一两域」（`:128-130`）。

#### 10.A.3 行为清单（本章后面所有推导的**唯一输入**）

| 编号 | 行为 | 成功判据 | 失败判据 / 形态 | 证据等级 | 依据 |
|---|---|---|---|---|---|
| **B-01** | 端点形态：13 个 `@PostMapping`，类级前缀 `/admin/v1` | 路由命中 | 非 POST ⇒ **405 裸状态码** | 【契约】 | `controller/AdminController.java:123` · `:134`~`:206` |
| **B-02** | public 路径只有 2 条 | `/login`、`/create` 无 token 也能进业务层 | 缺 token **不**是 401 | 【事实】 | `interceptor/AdminJwtAuthInterceptor.java:300-302` · `:339-341` |
| **B-03** | 受保护判据 = **前缀 + public 白名单** | `/admin/v1/**` 下非 public 的路径都受保护 | `/admin/v1/not-exist` **也**受保护 | 【事实】 | `interceptor/AdminJwtAuthInterceptor.java:333` · `:352-360` |
| **B-04** | 验签在 **Filter** 里做（3s 预算之外） | attribute `…jwt.verification` 被写入 | attribute 为 null ⇒ interceptor 兜底就地验签（`source=inline`） | 【事实】 | `interceptor/AdminJwtSignatureFilter.java:114` · `:158-167` ＋ `interceptor/AdminJwtAuthInterceptor.java:189-196` |
| **B-05** | 缺 `Authorization` 头 / 剥掉 `Bearer ` 后为空串 | — | **401** + `{"code":1006,"msg":"TOKEN已过期","data":null}` | 【事实】 | `interceptor/AdminJwtSignatureVerifier.java:99-104` · `:110-116` |
| **B-06** | 签名错 / token 过期 / `claim.userId` 缺失或类型不对 | — | 同 B-05（B-06 的后半段是 **admin 特有**） | 【事实】 | `interceptor/AdminJwtSignatureVerifier.java:124-136` · `:140-146` |
| **B-09** | 验签通过 | `AdminJwtVerificationResult.ok(uid, signatureToken, trimmedToken)` | — | 【事实】 | `interceptor/AdminJwtAuthInterceptor.java:389-391` ＋ `interceptor/AdminJwtSignatureVerifier.java:150-151` |
| **B-10** | 阶段顺序：**先验签、后黑名单** | — | 签名无效 **且** 已拉黑 ⇒ **401，黑名单一次都不查** | 【事实】 | `interceptor/AdminJwtAuthInterceptor.java:203-205`（抛异常在 `:224` 之前） |
| **B-11** | 黑名单 key = `admin:jwt_blacklist:` + **trimmed** token | key 存在 ⇒ 401 | Redis 抛异常 ⇒ **fail-open 放行** | 【事实】 | `interceptor/AdminJwtAuthInterceptor.java:109` · `:223-229` · `:248-258` |
| **B-12** | 两套取 token 的方式 | 验签用大小写**不**敏感；黑名单用大小写**敏感** | `bearer <token>` ⇒ 验签过、黑名单查不到 | 【事实】 | `common/.../security/BearerTokenExtractor.java:74-87` · `:101-105` |
| **B-13** | 写两个 request attribute | `userId`（`Long`）· `jwt_token`（`String`） | `userId` 必须是 `Long`（不是 `Integer`/`String`） | 【事实】 | `interceptor/AdminJwtAuthInterceptor.java:210` · `:233` ＋ `service/users/common/.../IdentityResolver.java:259-267` |
| **B-14** | 密钥 fail-closed | 密钥非空、非占位符 | 空串 / 仍是 `${…}` ⇒ **启动失败** | 【事实】 | `config/AdminApiProperties.java:88-101` · `config/AdminRpcProperties.java:100-113` |
| **B-15** | 密钥补齐必须两端一致 | 签发与验签落在**同一份 32 字节 key** 上 | 两端各自补 ⇒ 一侧签的 token 另一侧验不过 | 【事实】 | `common/.../security/JwtUtil.java:102-111` · `:40` |

> **B-04 + B-10 是这套机制最吃重的两条**：B-04 说「验签要出 3 秒预算」，B-10 说「验签必须排在黑名单前面」。 ⇒ 合起来 ⇒ **Filter 只做验签、绝不做黑名单、绝不写响应**；一旦 Filter 写响应，413 就会被 401 压过（Filter 排在 `MaxBytesFilter` 之前）⇒ 源码把它写成类级不变式：`interceptor/AdminJwtSignatureFilter.java:95-111`。

#### 10.A.4 需求里没写、但动手前必须自己定下来的五件事

这五件事**不在需求里**，但不定下来，写出来的系统一定会在某处出问题。它们是**从 B-04/B-08/B-10/B-12/B-14 反查出来的**：

1. **【推导】验签与黑名单必须拆成两段，中间用 request attribute 交接。** Filter 在 `DispatcherServlet` **外面**，Interceptor 在**里面**；3 秒预算由 `HttpMiddlewareFilter`（`@Order(+7)`）起算 ⇒ 验签必须落在 `+7` 之前 ⇒ **必须用 Filter**。⇒ 推出 `AdminJwtSignatureFilter` 这个类的存在。 2. **【推导】Filter 失败时不能自己写 401。** 写了 ⇒ ① `@ExceptionHandler` 管不到 ⇒ body 变裸文本不是信封；② Filter 排在 `MaxBytesFilter(+8)` **之前** ⇒ 401 压过 413，而 B 侧是 413 压过 401。⇒ 推出「只写 attribute，不写响应」。 3. **【推导】两段必须共用同一份路径判据。** 不共用 ⇒ ①「Filter 判受保护 / Interceptor 判放行」⇒ **鉴权被绕过**；② 反过来 ⇒ Interceptor 拿不到结果 ⇒ 落进兜底分支 ⇒ **验签退回预算内**，语义静默失效。⇒ 推出 `AdminJwtAuthPaths` 必须是**一个**类，被两处引用。 4. **【推导】签名无效的 token 不应该去查黑名单。** 查了 ⇒ ① 多一次 Redis 往返 ② **多一个可观察差异**（B 侧先失败 ⇒ 黑名单从不被查）。⇒ 推出「抛异常 ⇒ 后续代码不执行」这个顺序。 5. **【推导】Redis 不可用时必须 fail-open，且必须可观测。** fail-closed ⇒ Redis 一挂整个管理域 401（可用性事故）；fail-open ⇒ Redis 挂掉期间注销能力失效（安全性降级，但业务不被打挂）。⇒ 这是**二选一**，必须写进需求。⇒ 推出 `isBlacklisted` 里那个 `catch (Throwable t)`。

#### 10.A.5 必须保持不变的既有行为（改代码时的红线）

| 红线 | 为什么不能动 | 依据 |
|---|---|---|
| 13 个端点的 method 与 path | 客户端按它写死了 | 【契约】`controller/AdminController.java:134`~`:206` |
| 401 的**响应形状**（401 + 三键信封 + `data` 键存在） | 客户端的失败分支挂在「401 + body.code」上 | 【契约】`advice/AdminApiExceptionHandler.java:103-112` ＋ `common/.../contract/ApiResponse.java:60-67` |
| 业务码 1006 = `ERROR_TOKEN_RUNTIME` | 鉴权失败只有一个码 | 【契约】`service/users/common/.../ErrCodes.java:118` |
| 黑名单 key 前缀 `admin:jwt_blacklist:` | 两域黑名单必须隔离 | 【契约】`interceptor/AdminJwtAuthInterceptor.java:109`；隔离由 `http/AdminHttpContractRealTomcatTest.java:411` 钉死 |
| JWT 算法 HS256 + JCA 名 `HmacSHA256` + claim 名 `userId` | 别的服务/客户端会验签它 | 【契约】`common/.../security/JwtUtil.java:40` · `:46` |
| **「签名无效 ⇒ 黑名单不被查询」** | B 侧同款；改了就多一处 wire 漂移 | 【事实】`interceptor/AdminJwtAuthInterceptor.java:203-205` |
| 「Redis 故障 ⇒ 放行」 | 与 B 侧 `err != nil ⇒ 条件为 false` 同形 | 【事实】`interceptor/AdminJwtAuthInterceptor.java:248-258` |
| **两域的畸形 claim 语义不统一**（admin=401 / user=200） | 源码明确写了「不顺手统一两域」 | 【事实】`interceptor/AdminJwtSignatureVerifier.java:128-130` |

#### 10.A.6 从行为清单到状态机

行为清单是**平铺的**，实现需要一个**有序的判定过程**：

```mermaid
stateDiagram-v2
    [*] --> 请求进入: 带 Authorization 头的请求
    请求进入 --> 放行: DispatcherType == ERROR 或 isProtected(uri) == false
    请求进入 --> 验签: isProtected(uri) == true
    验签 --> 失败401: 缺头 / 空串 / 签名错 / claim 畸形
    验签 --> 写userId: 验签通过
    写userId --> 查黑名单: 进入阶段 2
    查黑名单 --> 失败401: key 存在
    查黑名单 --> 写jwt_token: key 不存在 或 Redis 异常（fail-open）
    写jwt_token --> 放行: preHandle 返回 true
    放行 --> [*]
    失败401 --> [*]
```

> **这张图不是「代码流程图」，是「需求状态机」**——它是 10.B 推导组件的依据：每个状态落到哪个组件、哪个状态抛异常、哪个状态写 attribute，都由这张图决定。与 §3 的运行路线图对照着看，能看出**「需求状态」与「执行位置」是两套坐标**。

---

### 10.B 从业务行为推导组件设计

> 🔴 **本章最容易走偏的地方**：直接抄文件树再逐个解释职责。那样得到的是「**这套系统有哪些文件**」，不是「**我该写哪些文件、为什么**」。正确顺序：**行为 → 需要谁来做 → 已有能力能否承担 → 落成什么文件**。

#### 10.B.1 推导的第一问：每个行为要交给谁承担

| 行为 | 承担者 | 为什么是它（而不是别的） |
|---|---|---|
| B-01 路由与 method | `AdminController` | Spring MVC 只认 `@RestController`；路由声明是**协议层**职责 |
| B-03 受保护判据 | `AdminJwtAuthInterceptor.AdminJwtAuthPaths` | **必须只有一份**（10.A.4 第 3 条）⇒ 必须是**一个**可被两处引用的类 |
| B-04 验签在预算外 | `AdminJwtSignatureFilter`（Servlet Filter） | **只有 Filter 在 `DispatcherServlet` 外面** ⇒ 只有 Filter 能出预算 |
| B-05~B-09 验签判定 | `AdminJwtSignatureVerifier` | 验签是**纯计算**（解析 + 验签 + 取 claim），不碰 request、不碰响应 ⇒ 值得独立成一个可单测的类 |
| B-05~B-10 响应形状 | `AdminApiExceptionHandler#handleAuthentication` | 401 必须由 advice 产出 ⇒ 才有完整信封（10.A.4 第 2 条） |
| B-10 阶段顺序 | `AdminJwtAuthInterceptor#preHandle` | 「抛异常就不往下走」是**控制流**语义 ⇒ 必须落在同一个方法里 |
| B-11 黑名单 | `AdminJwtAuthInterceptor#isBlacklisted` | 黑名单**在预算之内**（B 侧是 route 级中间件）⇒ 不能搬到 Filter |
| B-12 两套取法 | `BearerTokenExtractor`（共享层） | 同一规则的**第二份实现**本身就是缺陷来源（DRIFT-010 就是这么来的）⇒ 必须上收到共享层 |
| B-13 写 attribute | `AdminJwtAuthInterceptor` | 写 attribute 与「判定要不要鉴权」是同一次决策的产物 |
| B-14/B-15 密钥守卫与补齐 | `AdminApiProperties` / `AdminRpcProperties` 的 `@PostConstruct` ＋ 共享层 `JwtUtil.padToHmacKeySize` | 守卫必须**早于**服务可用 ⇒ 只能在 bean 初始化阶段；签发端与验签端**必须用同一份补齐实现** ⇒ 必须是 `public` 的单一实现 |

#### 10.B.2 为什么必须拆成两段：三个「合并掉会怎样」的反事实

| 假设合并 | 会发生什么 | 结论 |
|---|---|---|
| 把验签**合并回** `AdminJwtAuthInterceptor` | 验签回到 `DispatcherServlet` **里面** ⇒ 落进 `HttpMiddlewareFilter(+7)` 起算的 3 秒预算 ⇒ **B-04 失效** ⇒ 与 B 侧链序不一致（DRIFT-USERAPI-009②） | ⇒ 验签**必须**独立成 Filter |
| 把黑名单**搬进** Filter | ① 黑名单变成预算**之外**（B 侧黑名单在预算**之内**）② 黑名单命中要写响应 ⇒ **「绝不写响应」这条不变式被打破** ⇒ 401 压过 413 | ⇒ 黑名单**必须留在** Interceptor |
| 把 `AdminJwtAuthPaths` **拆成两份** | ① 两份不一致 ⇒ 一处判「受保护」另一处判「放行」⇒ **鉴权被绕过** ② 判据漂移时**零报错**（两段各自都"正常工作"） | ⇒ 判据**必须只有一份** |

> 🔴 **第三条最危险**：它**不报错**。判据一致时一切正常；哪天有人只改了其中一份，**没有报错、没有启动失败、没有日志异常** —— 只是鉴权悄悄失效了。 ⇒ 所以本项目把判据**收敛到一个类**（`interceptor/AdminJwtAuthInterceptor.java:297`），两段都从它引用（`:158` 与 `:159`）。

#### 10.B.3 简单文件：这些代码量少的文件是怎么被推导出来的

##### `AdminJwtVerificationResult`（record，验签结果载体）

| 推导问题 | 推导过程 | 结论 |
|---|---|---|
| 为什么需要一个「结果类型」？ | 验签有 3 种失败形态 + 1 种成功形态 ⇒ 若用「抛异常 / 返回 null」表达，Filter 就得**自己决定**「失败要不要抛」⇒ 职责外溢 | 需要一个**结果载体** |
| 为什么是 record？ | 它是**只读数据**，没有行为、不需要 setter | `record` |
| 为什么有 4 个分量？ | ① `uid`（写进 `userId`）② `signatureToken`（验签用）③ `trimmedToken`（黑名单用）④ `failure`（失败时的异常） | 4 个分量 |
| 为什么要存**两份** token？ | B 侧 **Authorize 与 CheckBlacklistMiddleware 对同一个 header 用了不同取法** ⇒ 两个 token **可以不同** ⇒ 强行只存一份 ⇒ 必然有一处用错 | 两个 token 分量 |
| 为什么失败时 `uid` 是 `-1L`？ | `long` 基本类型没有 null ⇒ 必须给**不可能是合法 uid 的哨兵值**（合法 uid 必须 > 0） | `long uid = -1L` |
| 为什么 `failure` 是 `RuntimeException`？ | 它要**原样重抛**（`interceptor/AdminJwtAuthInterceptor.java:204`）⇒ 必须非受检 | `RuntimeException` |
| 它怎么被使用？ | Filter 写进 attribute（`interceptor/AdminJwtSignatureFilter.java:161`）⇒ Interceptor 读出（`:187-188`）⇒ 失败就 `throw result.failure()`（`:204`） | 只在两段之间流转 |

> ⚠️ **如果 `failure` 改成 `Exception` 会怎样？** `preHandle` 的签名（`interceptor/AdminJwtAuthInterceptor.java:122`）必须加 `throws Exception` ⇒ Spring MVC 对 `HandlerInterceptor#preHandle` 抛出的**受检**异常没有专门处理路径 ⇒ 异常落到 advice 的 `handleAny` ⇒ **401 变成 500** ⇒ wire 漂移。 ⇒ **类型选择本身就是一个行为决策。**

##### `AdminJwtAuthPaths`（判据类）与 `AdminApiProperties.AdminAuth`（配置嵌套类）

| 推导问题 | 结论 | 依据 |
|---|---|---|
| 判据为什么需要一个专门的类？ | 两段要用同一份判据 ⇒ 它必须能被**两处**引用 ⇒ 不能是某个类的私有方法 | `interceptor/AdminJwtAuthInterceptor.java:297`（public static 嵌套类） |
| public 集合为什么只有 2 条？ | B 侧只有 `login` 与 `create` 两段路由**不挂 JWT**（该类 javadoc `:263-278` 引用了 B 侧 `routes.go` 三段 `AddRoutes`） | 【契约】`interceptor/AdminJwtAuthInterceptor.java:300-302` |
| 判据为什么是**前缀**而不是**枚举**？ | B 侧 Java 实现的 `isProtected` 就是**前缀语义** ⇒ 照抄 | 【事实】`:333` · `:352-360`；台账 A-30（E-4 已验证） |
| 配置为什么要有嵌套类？ | yml 是 `admin-api.admin-auth.access-secret` **两段前缀** ⇒ Java 侧要有两层对象 ⇒ `@NestedConfigurationProperty` 让嵌套对象在 IDE 与元数据里也显示成两段 | `config/AdminApiProperties.java:51` · `:54-55` · `:122-127` |
| 为什么 `accessSecret` 是 `String` / `accessExpire` 是 `long`？ | 前者要变成 HMAC 密钥字节 ⇒ 需**文本形态**；后者是**秒数**（Redis TTL 同单位）⇒ 免得多处换算 | `:125` · `:126` |
| 为什么 `adminAuth` 字段**有默认值** `new AdminAuth()`？ | yml 里**没写** `admin-api` 整段时注入的是**空对象**而不是 null ⇒ `getAdminAuth()` 不会 NPE ⇒ 但守卫仍会因 `accessSecret == null` 而启动失败 | `:54-55` + `:90` |
| 守卫为什么写 `@PostConstruct` 而不是构造器？ | `@ConfigurationProperties` 的绑定发生在**构造之后** ⇒ 构造器里读到的还是 null ⇒ 守卫会误判 | `:88-89` |

#### 10.B.4 框架承担什么 vs 我必须自己写什么

| 职责 | 框架承担 | 我必须自己写 |
|---|---|---|
| Filter 链编排 | Spring Boot 按 `@Order` 排 filter | **槽位**（`+6`）与**不变式**（绝不写响应） |
| 拦截器注册 | `WebMvcConfigurer#addInterceptors`（`config/WebMvcConfig.java:85-88`） | **`addPathPatterns("/admin/v1/**")`** 与 `isProtected` 的**范围差**：前者决定「拦截器在哪跑」，后者决定「要不要鉴权」 |
| JWT 解析与验签 | jjwt 0.12（`parseSignedClaims`） | **claim 取值规则**（本域是「类型不对就 401」，与 user 域不同） |
| HMAC 密钥构造 | `Keys.hmacShaKeyFor` | **补齐规则**（补零到 32 字节）——jjwt 强制 ≥256 bit 而 admin 默认密钥只有 15 字节 |
| request attribute 传递 | Servlet 规范 | **attribute 名**（`"userId"` / `"jwt_token"` / 两个 `com.rag.admin.interceptor.jwt.*`）与**值的类型**（`Long`） |
| Redis 访问 | `StringRedisTemplate#hasKey` | **fail-open 决策**与**吞异常** |
| 401 的响应形状 | Spring MVC 异常解析 + 本项目 `@RestControllerAdvice` | 401 **不**走 `decideStatus`（那是恒 200 的）⇒ 单独一个 handler |

> ⚠️ **框架承担 ≠ 业务行为被免除**：`OncePerRequestFilter` 帮你做「只跑一次」，但**「失败时绝不写响应」是业务决定**；`StringRedisTemplate` 帮你查 key，但**「Redis 挂了要不要拦」是业务决定**；jjwt 帮你验签，但**「claim 类型不对算不算鉴权失败」是业务决定**。

---

### 10.C 从空文件推导代码实现

> 本节不讲「现在这个文件里写了什么」（前 9 章已经讲过），讲的是「**从一个空文件开始，我按什么顺序写、每一步凭什么**」。

#### 10.C.1 动手前先钉死的四张清单

| 清单 | 内容（以本机制为例） | 从哪来 |
|---|---|---|
| ① 协议清单 | method/路径（13 个）、public 集（2 个）、受保护判据（前缀）、401 的响应形状、错误码 | 10.A.3 ＋ 契约 |
| ② 字段清单 | 输入 `Authorization` 头；产出 `userId`(`Long`)、`jwt_token`(`String`)；中间件 `AdminJwtVerificationResult` 的 4 个分量 | 10.B.3 |
| ③ 行为清单 | 每个判定：条件 / 成立时做什么 / 不成立时做什么 | 10.A.3 ＋ 10.A.6 |
| ④ 契约清单 | 不能改的东西 ＋ 改动需要谁拍板 | 10.A.5 |

**没有这四张清单就开写文件，是绝大多数「写完才发现字段不对」的根因。**

#### 10.C.2 `AdminJwtVerificationResult`：从空文件到 22 行

```java
// 步骤 1：确定落点 —— 它要被 Filter 写、被 Interceptor 读 ⇒ 必须双方都够得着
//         ⇒ 做成 AdminJwtAuthInterceptor 的 public static 嵌套类（否则只能 Object + 强转）
// 步骤 2：确定形态 —— 只读数据、无行为 ⇒ record（分量顺序 = 三个载荷 + 一个判决）
public record AdminJwtVerificationResult(
        long uid,                    // 分量 1：claim 取到的管理员 uid；失败时是 -1（不可能是合法 uid）
        String signatureToken,       // 分量 2：验签用的那一份 token（大小写不敏感地剥过 Bearer）
        String trimmedToken,         // 分量 3：黑名单 key 用的那一份 token（大小写敏感地剥过 Bearer）
        RuntimeException failure     // 分量 4：失败时的异常；null == 验签通过
) {
    // 步骤 3：两个工厂方法 —— 让调用点自解释，避免到处 new + 传 null
    public static AdminJwtVerificationResult ok(long uid, String signatureToken, String trimmedToken) {
        return new AdminJwtVerificationResult(uid, signatureToken, trimmedToken, null);
    }

    public static AdminJwtVerificationResult failed(RuntimeException failure) {
        return new AdminJwtVerificationResult(-1L, "", "", failure);
    }

    // 步骤 4：一个谓词 —— 消费点写成 if (result.failed())
    public boolean failed() {
        return failure != null;
    }
}
```

| 步骤 | 我怎么知道该这么写 | 反事实（写错了会怎样） |
|---|---|---|
| 3. `uid` 用 `long` + 失败时 `-1L` | `long` 无 null ⇒ 必须给哨兵值 ⇒ `-1` 与「合法 uid 必须 > 0」不冲突 | 用 `Long` + null ⇒ 每次读都要判 null ⇒ 多一个状态 |
| 3. `failure` 用 `RuntimeException` | 它要**原样重抛**（`interceptor/AdminJwtAuthInterceptor.java:204`）⇒ 必须非受检 | 改成 `Exception` ⇒ `preHandle` 要 `throws` ⇒ Spring MVC 对受检异常没有专门处理 ⇒ **401 变 500** |

#### 10.C.3 `AdminJwtAuthPaths`：从空文件到判据

```java
public static final class AdminJwtAuthPaths {

    // 步骤 1：先写 public 集合 —— 它是「例外」，比「默认规则」更具体 ⇒ 先钉死例外
    public static final Set<String> PUBLIC_PATHS = Set.of(
            "/admin/v1/login", "/admin/v1/create"
    );

    // 步骤 2：再写受保护的「默认范围」 —— 一个前缀常量
    private static final String PROTECTED_PREFIX = "/admin/v1";

    // 步骤 3：判据 = 「非 public」且「落在前缀内」
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
}
```

| 步骤 | 依据 | 反事实 |
|---|---|---|
| 2. 前缀常量**带尾部斜杠判断** | `startsWith(PROTECTED_PREFIX + "/")` ⇒ `/admin/v10/x` **不会**被误判 ⇒ 这是**边界正确性** | 写成 `startsWith(PROTECTED_PREFIX)` ⇒ `/admin/v10/x` 会被拉进鉴权 ⇒ 多余的 401 |
| 3. `uri == null` 判 false；先判 `isPublic` | 判据类**可能被别处复用** ⇒ 防御；public 是**例外** ⇒ 例外优先 | 反过来 ⇒ `/admin/v1/login` 会先被前缀命中 ⇒ **登录接口也要 token** ⇒ 直接锁死系统 |

> 🔴 **「步骤 2 的尾部斜杠」是一个一行代码的边界漏洞**：`startsWith("/admin/v1")` 会把 `/admin/v10/foo`、`/admin/v1x` 一并吞进来。本仓写法是 `startsWith(PROTECTED_PREFIX + "/")` ＋ 单独的 `equals(PROTECTED_PREFIX)` ⇒ **精确覆盖 `/admin/v1` 与 `/admin/v1/...`，不多不少**。

#### 10.C.4 `AdminJwtSignatureFilter.doFilterInternal`：五步，顺序即语义

```java
@Override
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
    String uri = request.getRequestURI();                       // 步骤 1：取 URI（唯一输入）
    if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ERROR) {
        chain.doFilter(request, response);                        // 步骤 2：ERROR 派发直接放行
        return;
    }
    if (AdminJwtAuthInterceptor.AdminJwtAuthPaths.isProtected(uri)) {
        AdminJwtAuthInterceptor.AdminJwtVerificationResult result =  // 步骤 4：验签（失败也写 attribute）
                verifier.verify(request.getHeader("Authorization"), uri);
        request.setAttribute(VERIFICATION_ATTRIBUTE, result);
        request.setAttribute(VERIFICATION_SOURCE_ATTRIBUTE, SOURCE_FILTER);
        if (result.failed()) {
            log.debug("admin JWT verification failed (deferred to interceptor) : {}", uri);  // 🔴 不写响应
        }
    }
    chain.doFilter(request, response);                            // 步骤 5：无条件放行
}
```

| 步骤 | 顺序的依据 | 反事实（换个顺序会怎样） |
|---|---|---|
| 2（ERROR 放行）在 3 之前 | ERROR 派发不是业务请求 ⇒ 鉴权不该插手 | 反过来 ⇒ Tomcat 对 `sendError(413)` 的二次派发会被鉴权拦成 **401**，**413 就消失了** |
| 4（**失败也写 attribute**） | Interceptor 靠 attribute 拿**失败结果**并原样抛出 ⇒ 不写就落进兜底分支（`inline`）⇒ **验签退回预算内** | 失败就不写 ⇒ 每次鉴权失败都走兜底 ⇒ 预算语义静默失效 |
| 5（**无条件** `chain.doFilter`） | 413 必须压过 401 ⇒ 后面的 filter 必须还有机会写响应 | 失败就 return ⇒ `MaxBytesFilter(+8)` 不再执行 ⇒ 请求体超限时**返回 401 而不是 413** ⇒ wire 漂移 |

> 🔴 **步骤 4 与步骤 5 是一对**：合起来是本机制的核心不变式 —— **Filter 只产出「判决」，绝不执行「判决」**。判决由 `AdminJwtAuthInterceptor` 执行（抛异常），响应由 `AdminApiExceptionHandler` 产出。

#### 10.C.5 `AdminJwtAuthInterceptor#preHandle`：七步，顺序即语义

| 步 | 做什么 | 顺序的依据（依赖关系，不是习惯） |
|---|---|---|
| W1 | `uri = request.getRequestURI()` | 后面每一步都要它 |
| W2 | `DispatcherType == ERROR` ⇒ `return true` | **必须最先**：它决定「这一轮要不要做鉴权」⇒ 不该做时后面全部不执行 |
| W3 | `isProtected(uri)` 为 false ⇒ `return true` | 同 W2 |
| W4 | 读 `VERIFICATION_ATTRIBUTE`；为 null ⇒ **就地验签**（`source=inline`，打 `warn`） | 必须在「判定失败」**之前**：失败与否取决于有没有验签结果 |
| W5 | `result.failed()` ⇒ `throw result.failure()` | **必须在黑名单之前**（B-10）⇒ 抛出去就**不会**执行 W6/W7 |
| W6 | 写 `userId` attribute（`Long`） | 必须在 W7 之前：黑名单命中时**已经写了** `userId` ⇒ 这是「失败后副作用残留」的已知形态 |
| W7 | 查黑名单 ⇒ 命中则抛 `AuthenticationException` | 必须在 W5 之后 ⇒ 见 B-10 |
| W8 | 写 `jwt_token` attribute；若两套 token 不等 ⇒ `warn` | 最后写 ⇒ 只有**全部通过**的请求才有 `jwt_token` |

> 🔴 **W5 与 W7 的相对位置是本章最重要的一条「顺序即语义」**：若先查黑名单、后验签，则一个**签名无效但已被拉黑**的 token，客户端会看到「先打了一次 Redis `hasKey`，再 401」；而 B 侧是「验签先失败 ⇒ 黑名单**一次都不查**」。 ⇒ 源码明确记着（`interceptor/AdminJwtAuthInterceptor.java:171-185`），并标注这是 **DRIFT-009 已回退**的旧实现。⇒ **同一段判定代码，换个顺序就是一处 wire 漂移。**

> 🔴 **W4 的兜底分支为什么必须打 `warn` 而不是 `debug`**：兜底本身**不是故障**（行为完全正确，401 照常），但它意味着**「验签在 3 秒预算之外」这条语义在这条路径上没有闭环** ⇒ 它是「**语义降级**」而不是「错误」⇒ 日志级别必须是 `warn`（`interceptor/AdminJwtAuthInterceptor.java:193`）。

#### 10.C.6 `AdminJwtSignatureVerifier#verify`：四个早期失败点

方法的骨架（省略异常构造）：**① 头缺失**（`:99-104`）⇒ failed → **② 剥前缀后为空串**（`:110-116`）⇒ failed → **③ 解析验签 + 取 claim**（`:120-136`，`claim instanceof Number` 才收，否则 failed）→ **④ 算第二套 token**（`:150`）⇒ 返回 `ok`。

| 顺序 | 依据 | 反事实 |
|---|---|---|
| ① 在 ② 之前 | 头都没有 ⇒ 剥前缀无意义 | 反过来 ⇒ `null` 在 `substring` 处 NPE ⇒ 500 而不是 401 |
| ③ 在 ④ 之前 | 失败时**不该**再算第二套 token | 反过来 ⇒ 每次失败都多一次字符串操作 |
| `catch (BusinessException)` 在 `catch (Throwable)` 之前（`:137-146`） | 前者是后者的子类 ⇒ **更具体的在前** | 反过来 ⇒ `BusinessException` 被兜住并**改写成 1006** ⇒ 丢掉原始异常类型 |
| `instanceof Number` 而不是 `instanceof Long`（`:125`） | JWT 里的 JSON 数字被 jjwt 解析成 `Integer`/`Long` 取决于大小 ⇒ 判 `Number` 再 `.longValue()` 才稳 | 判 `instanceof Long` ⇒ 小 uid 的 token 被**判为畸形 claim** ⇒ 401 ⇒ 合法管理员被拒 |

#### 10.C.7 密钥补齐：`JwtUtil.padToHmacKeySize` 为什么必须是 `public` 且两端共用

这是本机制里**唯一一处「看起来像小问题、实际会让整个服务起不来」的推导**。

```java
// 签发端（common/.../security/JwtUtil.java:40）
SecretKeySpec key = new SecretKeySpec(padToHmacKeySize(secretKey), "HmacSHA256");
// 验签端（interceptor/AdminJwtSignatureVerifier.java:86）
this.key = Keys.hmacShaKeyFor(JwtUtil.padToHmacKeySize(properties.getAdminAuth().getAccessSecret()));
// 补齐实现（common/.../security/JwtUtil.java:102-111）：>=32 原样返回；否则 new byte[32] 拷贝前缀 ⇒ 剩余字节天然是 0
```

| 推导问题 | 答案 | 依据 |
|---|---|---|
| 为什么需要补齐？ | jjwt 0.12 强制 HS256 的 key ≥ 256 bit；而 admin 侧默认密钥**只有 15 字节** ⇒ 不补齐 ⇒ **Bean 创建时就抛 `WeakKeyException`** | `interceptor/AdminJwtSignatureVerifier.java:76-83` |
| 补什么？ | **补零**。`new byte[32]` 在 Java 里默认全 0 | `common/.../security/JwtUtil.java:107-110` |
| 为什么补零**不破坏**与 B 侧的互通？ | **RFC 2104 §2 第 2 步**：HMAC 对短于分组尺寸（64 字节）的密钥**自己也会零填充** ⇒ 两种写法补到 64 字节后得到**完全相同的 K′** ⇒ 签名逐字节相同 | `common/.../security/JwtUtil.java:65-83`（含实测 hex 对照与 RFC 2104 链接） |
| 为什么必须 `public`？ | 签发端与验签端**在不同的模块** ⇒ 必须 `public` | `:102`；曾被删成 `private` 导致 admin-api 起不来，已恢复（`:96-98`） |
| 为什么必须**两端共用同一个方法**？ | 两端**各自实现** ⇒ 可能补出的长度/内容不同 ⇒ **一侧签的 token 另一侧验不过** ⇒ 而症状与「token 过期」几乎一样（都是 401）⇒ 极难定位 | `:57-63` |

#### 10.C.8 实现顺序（谁先谁后）与每步的完成判据

| 序 | 先做哪一步 | 为什么不能颠倒 | 完成判据 |
|---|---|---|---|
| 1 | **密钥配置 + 守卫** | 验签器构造器要读密钥 ⇒ 密钥没绑好，构造器就炸 | 缺变量 ⇒ 进程**起不来**（`config/AdminApiProperties.java:91-100`） |
| 2 | **`JwtUtil.padToHmacKeySize`** | 签发与验签都要用 ⇒ 必须先有 | 15 字节密钥 ⇒ 验签器能构造成功 |
| 3 | **`AdminJwtAuthPaths`** | Filter 与 Interceptor 都引用它 | `isProtected("/admin/v1/getself")`==true；`isProtected("/admin/v1/login")`==false |
| 5 | **`AdminJwtSignatureVerifier`** | Filter 依赖它 | 坏 token ⇒ 返回 failed，**不抛** |
| 6 | **`AdminJwtSignatureFilter`** | 依赖 3/4/5 | attribute 被写入，且**没有**任何响应被写 |
| 7 | **`AdminJwtAuthInterceptor`** | 依赖 3/4/6 | 合法 token ⇒ `userId` + `jwt_token` 就位 |
| 9 | **测试** | 依赖全部 | 见 10.D |

---

### 10.D 如何验证实现符合需求

> 🔴 **本章的红线**：**「能编译」「测试通过」「代码看起来没问题」三者都不等于「行为正确」**。验证的对象是 10.A.3 的**行为清单**。

#### 10.D.1 从行为清单推导用例，并对照现有测试

| 行为 | 推导出的用例 | 现状 | 依据（测试方法与所在文件） |
|---|---|---|---|
| B-01 | `GET /admin/v1/getself` ⇒ 405 | ✅ 已覆盖 | `http/AdminHttpContractRealTomcatTest.java:240` `allThirteenEndpointsArePostOnly` |
| B-02 | `/admin/v1/login` 无 token ⇒ 不是 401 | ✅ 已覆盖 | 同文件 `:253` `publicPathsAreNot401` |
| B-03 | 判据 = 前缀 + public 白名单；13 个映射全在前缀内 | ✅ 已覆盖 | 同文件 `:205` `pathSetMatchesControllerMappings` |
| B-03 | **未知路径 `/admin/v1/not-exist` 无 token ⇒ 401** | ✅ 已覆盖 | 同文件 `:419` `unknownPathWithoutTokenIs401` |
| B-03 | **未知路径带 token ⇒ 500 + 信封 5001**（B 侧没有 404 响应面） | ✅ 已覆盖 | 同文件 `:434` `unknownPathWithTokenIs500Envelope` |
| B-04 | 验签来源必须是 `filter`（不是 `inline`） | ⚠️ **admin 域未覆盖**（探针只存在于 user 域） | admin 侧只有兜底分支会打 `warn`（`interceptor/AdminJwtAuthInterceptor.java:193`）；user 域探针在 `service/users/user/src/test/java/com/rag/user/api/UserApiHttpContractTest.java` |
| B-05 | 缺 `Authorization` ⇒ 401 + 信封 + `data` 键存在 | ✅ 已覆盖 | `http/AdminHttpContractRealTomcatTest.java:271` `protectedPathWithoutTokenIs401` |
| B-08 | 畸形 claim ⇒ 401 | ⚠️ **未覆盖** | 依据：`interceptor/AdminJwtSignatureVerifier.java:124-136` |
| B-10 | **签名无效 ⇒ 黑名单一次都不查** | ✅ 已覆盖 | `http/AdminSemanticDriftApiRegressionTest.java:431` `interceptor_checksBlacklistAfterSignatureVerification` · `:448` `interceptor_invalidSignatureRejectedBeforeBlacklistStage` |
| B-11 | 黑名单命中 ⇒ 401；key 前缀是 `admin:`，且**不**写 `user:` 前缀 | ✅ 已覆盖 | `http/AdminHttpContractRealTomcatTest.java:394` `blacklistedTokenIs401AndUsesAdminScopedKey` |
| B-11 | Redis 故障 ⇒ **fail-open 放行** | ✅ 已覆盖 | `http/AdminSemanticDriftApiRegressionTest.java:561` `interceptor_redisFailureIsFailOpen` |
| B-12 | `XXBearer <token>` 不能绕过黑名单 / `bearer`（小写）应当被接受 / 缺头 ⇒ 不查 Redis | ✅ 已覆盖 | 同文件 `:472` · `:490` · `:517` |
| B-13 | 合法 token ⇒ `userId` / `jwt_token` 就位 | ✅ 已覆盖 | 同文件 `:543` `interceptor_validTokenWritesAttributes` |
| B-14 | 缺 `ADMIN_JWT_SECRET` ⇒ 启动失败 | ✅ 已覆盖（反向断言） | `http/AdminHttpContractRealTomcatTest.java:116-126`（注入真实密钥 ⇒ 证明守卫在有正确环境变量时**不会误杀**） |
| B-15 | 同 (secret, iat, expire, uid) ⇒ token 逐字节相同 | ✅ 已覆盖 | 同文件 `:377` `jwtIsDeterministicForSameClaims` |

⇒ **诚实的结论**：**B-04（验签来源探针）、B-06（空串 token）、B-08（畸形 claim）** 这 3 条**当前没有 admin 域的测试覆盖**，只有**源码层面的推导**。

#### 10.D.2 三类结论必须分开写

| 类别 | 本流程里的具体内容 | 怎么标注 |
|---|---|---|
| **仓内已有守门测试**（**本章未运行**） | B-01、B-02、B-03、B-05、B-07、B-10、B-11、B-12、B-13、B-15 | 「仓内有对应断言测试（真 Tomcat + 真 PG + 真 Redis）」（`http/AdminHttpContractRealTomcatTest.java` · `http/AdminSemanticDriftApiRegressionTest.java`）⇒ **只说明「仓里有这条断言」，不代表本章跑过它** |
| **仅源码推导，未运行** | B-04（admin 侧无探针）、B-06、B-08、B-14 的**失败分支** | 「源码推导，未运行」 |
| **无法确认** | 原作者当初**为什么**把验签放 Filter 而不是留在 Interceptor（源码只给了 B 侧链序的**客观事实**，没给决策记录）；`access-expire=2592000`（30 天）是否来自需求 | 【存疑】 |

> ⚠️ **不要把「仅源码推导」写成「测试通过」**。这两类混在一起，下次有人改动实现时就分不清哪些行为**有回归保护**、哪些只是**看起来对**。

#### 10.D.3 怎么验「副作用」，而不只是「返回值」

「接口返回 200」**不**证明鉴权机制做错了。对每条会改变状态的行为，要问**「世界的哪个角落变了」**：

| 行为 | 返回值能证明什么 | 真正要查的地方 |
|---|---|---|
| B-09/B-13 鉴权通过 | 返回了业务数据 | **两个 attribute 的值与类型**：`userId` 必须是 `Long`（不是 `Integer`）⇒ 反推依据是 `IdentityResolver.claimedUIDAllowNonPositive` 的 `instanceof Long` 判据（`service/users/common/.../IdentityResolver.java:259-267`） |
| B-10 验签优先 | 401 | **Redis 里没有新增访问痕迹** ⇒ 无效签名那次请求**没有**产生 `hasKey` 调用 |
| B-11 黑名单 | 401 | **Redis 的 key 空间**：断言 `admin:jwt_blacklist:<token>` 存在、且 `user:jwt_blacklist:<token>` **不存在**（正是 `http/AdminHttpContractRealTomcatTest.java:409-412` 的两条断言） |
| B-15 密钥补齐 | token 能用 | **用签发出来的 token 打受保护端点** ⇒ 200 ⇒ 说明签发端与验签端落在同一份 key 上（这是唯一能证明「补齐一致」的可观察方式） |

B-04 的可观察形态是 `VERIFICATION_SOURCE_ATTRIBUTE` 的值（`"filter"` / `"inline"`）。**admin 域目前没有这个探针**（user 域有）⇒ B-04 在 admin 域目前是**「源码推导，未运行验证」**；补它的成本很低：照 user 域的探针 filter 抄一个即可（不改生产代码）。

#### 10.D.5 怎么验「错误码映射」，而不只是「抛了异常」

常见错误写法：测试只断言「抛了 `AuthenticationException`」⇒ **任何码都算通过**。正确写法要断言**三件事**：① **HTTP 状态码**（本机制的业务失败 ⇒ **401**，不是 200）；② **body 里的业务码**（**1006**）；③ **body 的形状**（**三键信封**，`data` 键必须**存在**且为 `null`）。

⇒ 例：`http/AdminHttpContractRealTomcatTest.java:271` `protectedPathWithoutTokenIs401` 断言的是 `status == 401` **且** `body.code == 1006` **且** `body.has("data")`，**不是**「不是 200 就行」。 ⇒ 特别注意 **401 是这条链上唯一的例外**：业务失败一律 HTTP 200（见 [15](./15-异常处理与统一响应流程.md) §4.3）⇒ 断言 401 时**不能**用「HTTP 200 + 业务码」的通用套路。

#### 10.D.6 回归范围：改了这个机制，什么必须重新验

| 改动类型 | 必须回归 |
|---|---|
| 改 `isProtected` 判据 | B-02、B-03（**连同「未知路径」两条**）＋ 13 个端点全量 |
| 改 `BearerTokenExtractor` 的任一方法 | B-06、B-07、B-12（**两个域共用这个类**） |
| 改 `JwtUtil.getToken` / `padToHmacKeySize` | B-15 ＋ **user 域全量**（同一份 `JwtUtil`） |
| 改 `handleAuthentication` | B-05、B-08、B-11 的响应形状 ＋ [15](./15-异常处理与统一响应流程.md) §7.3 |
| 改 `AdminJwtSignatureFilter` 的 `@Order` | B-04（预算语义）＋ 413/401 的压过关系 |
| 改黑名单 key 前缀 | B-11 ＋ **user 域的隔离断言**（`http/AdminHttpContractRealTomcatTest.java:411`） |

---

### 10.E 出现问题时如何定位

> 🔴 **红线**：定位不是「看日志 / 查数据库 / 上 Debug」。定位是**一串有顺序的排除**，每一步都问「**这一步的检查能排除什么**」。下面三个案例都来自本流程**真实存在的机制**（不是虚构的故障剧本）。

#### 10.E.1 案例一：登录拿到了 token，但下一个受保护端点恒 401

| 要素 | 内容 |
|---|---|
| **1. 故障现象** | `POST /admin/v1/login` 返回 200 + `data.token` 非空；带这个 token 打 `POST /admin/v1/getself` ⇒ **恒 401 + `{"code":1006,...}`** |
| **2. 预期行为** | 同进程签发、同进程验签 ⇒ 200 |
| **3. 实际行为** | 签发成功、验签失败 |
| **4. 排查入口** | **验签的「来源」，不是验签的「结果」**：读 request attribute `VERIFICATION_SOURCE_ATTRIBUTE`（`interceptor/AdminJwtSignatureFilter.java:126`）⇒ 值应是 `"filter"`；若是 `"inline"`，说明验签发生在 Interceptor 的兜底分支里（`:189-196`）⇒ **验签已退回 3 秒预算内**（说明 Filter 那段没生效，但**不解释** 401 本身） |
| **5. 检查顺序** | ① 看来源是 `filter` 还是 `inline`（探针 filter，见 10.D.4）→ ② 若 `inline`：查 Filter 为什么没命中（`isProtected` 判据 / `@Order` / 是否被注册成 bean）→ ③ 若 `filter`：查**签发端与验签端用的密钥是不是同一份**（含**补齐**是否一致）→ ④ 查 token 的 `alg` 头有没有漂成 HS384/HS512 → ⑤ 查 `exp` 与服务器时钟 |
| **6. 可能原因** | ① **密钥不同** ② **密钥补齐不一致**（`JwtUtil.padToHmacKeySize` 必须两侧共用同一份；各写各的 ⇒ 一侧签的 token 另一侧验不过）③ **`alg` 漂移**：jjwt 的 `signWith(Key)` 单参重载**照着 key 的 JCA 算法名选算法** ⇒ 密钥长度变化 ⇒ `header.alg` 跟着变（`common/.../security/JwtUtil.java:23-26`、`:35-40` 明确钉死）④ **过期**：`\|exp - iat\|` 与 `access-expire` 不一致 ⑤ **Filter 没跑**（路径判据把它当 public 放行了） |
| **7. 排除过程** | ① 探针看来源 ⇒ 若是 `filter`，**排除 ⑤**；② **把签发出来的 token 用同一份密钥本地验签**（离线一步）⇒ 验不过 ⇒ 密钥/alg 问题（②③）；验得过 ⇒ 问题在**验签端读到的密钥**与签发端不是同一份 ⇒ 查配置绑定；③ 若验签端读到的是**未补齐**的原始密钥 ⇒ 对上 ② |
| **8. 根因确认** | 判据 = **两侧同源的字节级比较**：签发端实际使用的 key 字节（补齐后）与验签端实际使用的 key 字节（补齐后）必须**逐字节相同**。**不要**用「把 token 解码看看」当判据 —— payload 是 base64 明文，看不出签名用的 key |
| **9. 影响范围** | 整个域的 **11 个受保护端点**全部不可用（不只是 `getself`）⇒ 而且**症状与「token 过期」几乎一样**（都是 401）⇒ 必须靠上面几步分离。若根因在共享层 `JwtUtil`，则 **user 域同时坏**（同一份类） |
| **10. 修复与验证** | 统一密钥来源与补齐实现（共享层 `JwtUtil` 已是单一实现）⇒ 回归 **B-05、B-07、B-09、B-13、B-15** ＋ `http/AdminHttpContractRealTomcatTest.java:290` `protectedPathWithValidTokenIs200`（端到端：登录 → 带 token → 200）；并补一条「验签来源必须是 `filter`」的探针断言，让下一次同类故障**第一眼就能分离** |

#### 10.E.2 案例二：管理员已经注销了，旧 token 却还能继续用

| 要素 | 内容 |
|---|---|
| **1. 故障现象** | 管理员调用 `/admin/v1/logout`（写黑名单）返回 200；**同一个 token** 继续打 `/admin/v1/getself` ⇒ **仍然是 200** |
| **2. 预期行为** | 拉黑后的 token ⇒ 401 + `{"code":1006,...}` |
| **3. 实际行为** | 恒 200 ⇒ 黑名单**没命中** |
| **4. 排查入口** | **黑名单 key 的构造**，不是黑名单服务本身：`interceptor/AdminJwtAuthInterceptor.java:223-224`（`BLACKLIST_KEY_PREFIX + result.trimmedToken()`）与 `:249`（`BLACKLIST_KEY_PREFIX + token`）—— **两处必须拼出同一个字符串** |
| **5. 检查顺序** | ① 读 `result.trimmedToken()` 与 `result.signatureToken()` **是否相等**（`interceptor/AdminJwtSignatureVerifier.java:150-151` 产出的两个分量）→ ② 若不等 ⇒ 客户端的 `Authorization` 头**不是规范写法** → ③ 在 Redis 里 `KEYS admin:jwt_blacklist:*` 看真实 key → ④ 与 `admin:jwt_blacklist:` + `trimmedToken` 逐字符比对 → ⑤ 查前缀是不是写成了 `user:` |
| **6. 可能原因** | ① **客户端用了小写 `bearer `** ⇒ 验签用大小写**不**敏感的取法（`common/.../security/BearerTokenExtractor.java:74-87`）⇒ 验签过；黑名单用大小写**敏感**的取法（`:101-105`）⇒ 查的是 `admin:jwt_blacklist:bearer eyJ...` ⇒ **两个 key 根本不是一个**（源码明确说这是 B 侧自身的不一致，本实现原样保留，`interceptor/AdminJwtAuthInterceptor.java:214-216`）② **前缀写错**（`user:` vs `admin:`）③ **写黑名单的 key 与读黑名单的 key 拼法不同**（写方 `domain/service/AdminDomainService.java:324`，读方 `interceptor/AdminJwtAuthInterceptor.java:249`）④ **写黑名单的 TTL 早于 token 过期** ⇒ key 已过期消失 ⑤ **Redis 主从/集群读到的是另一个库** |
| **7. 排除过程** | ① 直接 `KEYS admin:jwt_blacklist:*` ⇒ **一条都没有** ⇒ 排除 ②③④⑤（说明**写**就没成功）⇒ 转查写路径；② 有一条但 key 的**内容**不是 `trimmedToken` ⇒ 说明是**两套取法分叉**（原因 ①）⇒ 客户端把 header 改成 `Bearer <token>` 立刻复现 ⇒ **根因确认**；③ key 完全一致但仍放行 ⇒ 查 `isBlacklisted` 的返回值路径 ⇒ 注意 `catch (Throwable t)` 会把任何异常吞成 `null` ⇒ **放行**（`interceptor/AdminJwtAuthInterceptor.java:253-257`）⇒ 查那条 `warn: admin Redis blacklist check failed` |
| **8. 根因确认** | 判据 = **Redis 里真实存在的 key 字符串** 与 `admin:jwt_blacklist:` + `trimmedToken` **逐字符相等**。相等却放行 ⇒ 根因在读路径（fail-open 或前缀常量不一致）；不相等 ⇒ 根因在**两套取法分叉** ⇒ 这是**唯一**能分离这两个假设的证据 |
| **9. 影响范围** | **注销能力整体失效** ⇒ 所有「注销后旧 token 仍可用」的审计场景全部失效 ⇒ 影响面覆盖 [06-管理员注销与黑名单流程.md](./06-管理员注销与黑名单流程.md) 与本篇。而「Redis 故障 ⇒ fail-open」是**设计选择**（B 侧同形），不能当 bug 修 |
| **10. 修复与验证** | ① 客户端把 `Authorization` 改成规范的 `Bearer <token>`；② 或在服务端**统一两套取法**（⚠️ 会**制造一处新的语义漂移**，必须走决策流程 —— 见 10.F.4）；③ 回归 **B-11、B-12**（`http/AdminHttpContractRealTomcatTest.java:394` ＋ `http/AdminSemanticDriftApiRegressionTest.java:472`）＋ `:561`（确认 fail-open 没被顺手动过） |

#### 10.E.3 案例三：加了新端点，却忘了把它登记进鉴权判据

> ⚠️ **这个案例在当前源码下已经「不会发生了」** —— 但它**发生过**，而且**原因值得记下来**：旧实现用的是**显式 11 条路径集合**，加端点忘了登记 ⇒ **鉴权被绕过**。现在改成**前缀语义** ⇒ 新端点**自动**受保护 ⇒ 这类漏洞从根上消失。⇒ 这条案例的价值在于：**它演示了「判据的形态」本身就是一条需求**。

| 要素 | 内容 |
|---|---|
| **1. 故障现象** | 新增端点 `/admin/v1/xxx` 后，它**不需要任何 token** 就能被调用 ⇒ 越权 |
| **2. 预期行为** | `/admin/v1/**` 下**除 public 两条外**全部要鉴权 ⇒ 新端点默认受保护 |
| **3. 实际行为** | 放行 ⇒ 未认证请求直达业务层 |
| **4. 排查入口** | **判据本身**：`interceptor/AdminJwtAuthInterceptor.java:352-360` 的 `isProtected`，以及它**当前依赖什么形态** —— 显式集合（易漏）还是前缀（默认安全） |
| **5. 检查顺序** | ① 读 `isProtected` 的实现形态 → ② 若是 `PROTECTED_PATHS.contains(uri)` ⇒ **这就是漏洞现场** → ③ 找出新端点不在集合里的原因（谁加的端点、谁负责登记）→ ④ 查是否有测试守卫这条约束 → ⑤ 查 `AdminController` 的真实映射数与集合数是否相等 |
| **6. 可能原因** | ① **判据形态选成了「枚举」**（默认不包含 ⇒ 默认不受保护 ⇒ **失败方向是「漏保护」**）② 加端点的人与维护判据的人不是同一个人 ③ 没有「端点数 == 集合数」这类自维护守卫 |
| **7. 排除过程** | ① 把新端点路径加进集合 ⇒ 立刻 401 ⇒ **排除 ②**（不是环境问题）⇒ 确认是 ①；② 再把新端点从集合里去掉 ⇒ 又放行 ⇒ **根因确认**；③ 读台账：`docs/DRIFT/users/admin/verification-admin-2026-11-02.md` 的 **F-9** 行记录了这条旧修法（「鉴权判据从『URL 前缀』改成**显式路径集合**」）⇒ 说明**方向被换过一次**；④ 再读 **A-30** 行 ⇒ 现在的形态是**前缀**，证据强度 **E-4** ⇒ **最终态**确定 |
| **8. 根因确认** | 判据 = **判据的失败方向**。「枚举」形态的失败方向是「漏保护」（危险）；「前缀」形态的失败方向是「多保护」（安全，只是多返 401）⇒ **要证明根因，必须证明它是枚举形态** —— 不是靠「我觉得没人登记」，而是**读实现** |
| **9. 影响范围** | 旧形态下，**所有在集合之外、且在 `/admin/v1/**` 之下的路径**都放行 ⇒ 不止一个新端点 ⇒ 影响面 = 整个前缀下的未知路径。这正是 E-4 实测推翻旧结论的原因（台账 §5-⑥ / §5-⑦） |
| **10. 修复与验证** | 修法 = 把判据换成**前缀 + public 白名单**（现已实施，`interceptor/AdminJwtAuthInterceptor.java:333` · `:352-360`）⇒ 回归 **B-02、B-03**（含「未知路径 401 / 500」两条：`http/AdminHttpContractRealTomcatTest.java:419` · `:434`）＋ `pathSetMatchesControllerMappings`（`:205`，它现在断言「13 个映射全在前缀内」＋「public 恰为 2 个」） |

#### 10.E.4 三类故障的「第一动作」速查

| 症状 | 第一动作 | 为什么先做这个 |
|---|---|---|
| 恒 401（登录成功但下一跳 401） | 看**验签来源**（`filter` / `inline`） | 401 的成因跨度太大（密钥 / alg / 过期 / Filter 没跑），先把「验签跑在哪」砍掉一半 |
| 拉黑后旧 token 仍可用 | 在 Redis 里 `KEYS admin:jwt_blacklist:*` 看**真实 key** | 「写没写进去」与「写进去但 key 不一样」是两种根因，只有真实 key 能分离 |
| 新端点不用 token 就能调 | 读 `isProtected` 的**实现形态** | 枚举形态 = 默认不保护（前缀形态 = 默认保护）⇒ 读一眼实现就定性 |

---

### 10.F 需求变化时如何修改

> 本节的场景**刻意分成三类**：**F-1** = 当前项目**已实现**、可以照着讲的真实改动路径；**F-2** = 当前项目**未实现**、纯**教学假设**（明确标记）；**F-3** = **看似相关但不应该改**。

#### 10.F.1 场景一（已实现 · 可照着讲）：路径判据从「显式 11 条集合」改成「前缀 + public 白名单」

> ✅ **这一条是本仓真实发生过的改动**（台账 `docs/DRIFT/users/admin/verification-admin-2026-11-02.md` 的 **F-9** 与 **A-30** 行，证据强度 **E-4**）。它是本机制最好的一份「需求变更」教材：**改的是判据的形态，不是判据的内容**。

| # | 内容 |
|---|---|
| **1. 原有业务行为** | 判据 = 显式 11 条受保护路径的集合；`/admin/v1/**` 下的**未知路径**放行 ⇒ 404（`RouterErrorResponses.notFound` 产出 `"404 page not found\n"`） |
| **2. 新需求改变了什么** | ① 判据改成**前缀语义**（非 public 且 URI == `/admin/v1` 或以 `/admin/v1/` 开头 ⇒ 受保护）② **删掉** `handleNoResourceFound` 分支 ⇒ 未知路径落进通用兜底 ⇒ **500 + 信封 5001** |
| **3. 哪些原有行为必须保持不变** | ① **public 恰为 2 条**（`/login`、`/create`）不变 ② 13 个端点的 method/path 不变 ③ 11 个已知受保护端点**仍然全部要鉴权**（前缀覆盖了它们）④ 401 的形状不变 ⑤ 黑名单前缀不变 ⑥ **验签仍在 Filter、仍在预算外**不变 |
| **4. 哪些组件需要修改** | ① **改**：`interceptor/AdminJwtAuthInterceptor.java` 的 `AdminJwtAuthPaths`（删 `PROTECTED_PATHS`、加 `PROTECTED_PREFIX`、改写 `isProtected`）② **删**：`advice/AdminApiExceptionHandler.java` 的 `@ExceptionHandler(NoResourceFoundException.class)` 分支 ③ **改**：`http/AdminHttpContractRealTomcatTest.java`（判据守卫从「集合逐条比对」改成「全部落在前缀内 + public 恰为 2 个」；`unknownPathIs404` 改成 `unknownPathWithoutTokenIs401` + `unknownPathWithTokenIs500Envelope`）④ **改**：所有引用 `PROTECTED_PATHS` 的文档与台账 |
| **5. 为什么这些组件需要修改** | 判据改了 ⇒ **判据的消费者**（Filter + Interceptor）自动跟着变（它们引的是同一个方法）⇒ 这正是 10.B.2「判据必须只有一份」的红利；404 分支删掉是因为**前缀语义下未知路径会被鉴权先拦一道**，能走到路由层的未知路径**必须**带合法 token ⇒ 此时「路径不存在」在 B 侧就是 500（台账 E-4 实测：带 token ⇒ 500 + 信封 5001） |
| **6. 哪些组件不应该修改** | ① `AdminJwtSignatureVerifier`（验签逻辑与判据无关）② `AdminJwtSignatureFilter`（只改它引用的判据方法，**不需要改结构**）③ `BearerTokenExtractor` / `JwtUtil`（共享层，与判据无关）④ `AdminApiResponseAdvice`（信封逻辑无关）⑤ **user 域的 `JwtAuthPaths`**（它本来就是前缀语义 ⇒ 本次是**向 user 域看齐**，不是反向改 user 域） |
| **7. 是否需要新增 DTO / Entity / 字段 / 方法 / 配置 / 约束** | **不新增任何 DTO / Entity / 表 / 配置**。只**替换**一个私有常量 ＋ **改写**一个静态方法 ＋ **删除**一个 handler 分支 ＋ **改写**两三个测试方法 |
| **8. 是否影响接口契约 / Proto / DB 结构 / 其它服务** | **影响 HTTP 响应面**：未知路径从「404 + text/plain」变成「401 + 信封 1006」或「500 + 信封 5001」⇒ **客户端可观察** ⇒ 走**决策流程**（台账 A-30 记录了 E-4 证据）。**不影响** proto、DB 表结构。**影响其它服务**：任何依赖「打错 admin 路径会得到 404」的调用方要改 |
| **9. 是否影响事务 / 幂等 / 并发 / 安全 / 异常处理** | **安全**：**变严格**（未知路径从放行变成要鉴权）⇒ 关闭了「漏保护」类漏洞。**幂等/并发/事务**：无。**异常处理**：删掉一个 `@ExceptionHandler` ⇒ `NoResourceFoundException` 落进 `handleAny` ⇒ **500 + 5001** ⇒ 与 B 侧同形。⚠️ **不要顺手把 404 分支加回来** —— 那会把「客户端打错路径」重新报成 404 ⇒ 又是漂移 |
| **10. 合理的修改顺序** | ① 先写**活体对拍结论**（E-4：B 侧真实进程的行为）② 再改判据（`AdminJwtAuthPaths`）③ 再删 404 分支 ④ 再改测试（守卫 + 未知路径两条）⑤ 再改文档与台账（`PROTECTED_PATHS` 的引用共 **10+ 处**）⑥ 最后跑全量回归。**顺序的价值**：proto/契约层不动 ⇒ 每一步都能单独验证 |
| **11. 需要验证哪些新行为** | ① 13 个已知端点**仍然全部**要鉴权 ② public 两条仍然放行 ③ `/admin/v1/not-exist` **无 token ⇒ 401 + 信封 1006** ④ `/admin/v1/not-exist` **带 token ⇒ 500 + 信封 5001** ⑤ `GET /foo`（不在前缀下）⇒ 同样 500 + 信封 5001（台账 E-4 实测结论） |
| **12. 需要回归哪些旧行为** | B-01、B-02、B-05、B-07、B-10、B-11、B-12、B-13 ＋ 跨文档 [15](./15-异常处理与统一响应流程.md)（401 与 500 两种信封形状）＋ [17](./17-启动引导与管理员种子流程.md)（密钥 fail-closed 未受影响） |

#### 10.F.2 场景二（教学假设）：把黑名单从 fail-open 改成 fail-closed

> ⚠️ **当前项目是 fail-open**（`interceptor/AdminJwtAuthInterceptor.java:248-258`）⇒ 本节是**假设场景**。它的价值在于：**演示一次「安全语义变更」的影响面有多宽**。

| # | 内容 |
|---|---|
| **1. 原有业务行为** | Redis 故障 ⇒ `hasKey` 抛异常 ⇒ 异常被吞成 `null` ⇒ `exists != null && exists` 为 false ⇒ **放行**（注销能力临时失效，但业务不被打挂） |
| **2. 新需求改了什么** | Redis 故障 ⇒ **一律 401**（fail-closed）⇒ 「拉黑能力」与「业务可用性」绑定 |
| **3. 必须保持不变的** | ① 401 的形状不变 ② 错误码仍是 1006 ③ 黑名单 key 前缀不变 ④ **共享层 `JwtUtil` / `BearerTokenExtractor` 不动** ⑤ **user 域不动**（user 域仍是 fail-open ⇒ 两域会不一致 ⇒ 必须显式登记） |
| **4. 要改哪些组件** | ① **改**：`interceptor/AdminJwtAuthInterceptor.java:248-258`（`isBlacklisted` 的异常分支：`catch` 里改成抛 `AuthenticationException` 或返回 `true`）② **改**：异常类型要选 —— `AuthenticationException`（⇒ 401 + 信封）还是别的 ③ **改**：测试（`http/AdminSemanticDriftApiRegressionTest.java:561` `interceptor_redisFailureIsFailOpen` **会立刻转红**）④ **改**：文档与台账（这是**契约级**变更） |
| **5. 为什么是这些** | ① fail-open/fail-closed 是**同一个方法里的一行控制流**（`exists != null && exists`）② 现有测试**已经钉死了 fail-open** ⇒ 改语义必然改测试 ⇒ 测试是「契约的守门人」，不是「实现的镜子」 |
| **6. 不该改哪些** | ① `AdminJwtSignatureVerifier`（与 Redis 无关）② `AdminJwtSignatureFilter`（同上）③ `AdminApiExceptionHandler`（401 已有现成分支）④ **`admin:` key 前缀** ⑤ **不该在 `AdminDomainService#logout` 侧改**（写侧与读侧的失败语义是**两件事**） |
| **7. 是否需要新增 DTO / Entity / 字段 / 方法 / 配置 / 约束** | **不新增**。若想「可开关」，则需要**新增一个配置项**⇒ 但那会让「一个机制两种语义」⇒ **同一个 key 空间的两种实现** ⇒ 除非需求明确要求，否则**不加** |
| **8. 是否影响契约 / Proto / DB / 其它服务** | **影响 HTTP 契约**（Redis 故障时的状态码）⇒ 走决策流程。**不影响** proto / DB。**影响其它服务**：任何在 Redis 抖动时依赖 admin 域仍可用的调用方 |
| **9. 是否影响事务 / 幂等 / 并发 / 安全 / 异常** | **安全**：**变严**（故障期间旧 token 全部失效 ⇒ 管理员被自己锁在门外 ⇒ 需要一条「应急通道」，否则等于把可用性换成「管理员出局」）⚠️ **这是本场景最大的隐藏成本**。**幂等/并发/事务**：无。**异常**：新引入「Redis 异常 ⇒ 401」的路径 ⇒ 与「Redis 异常 ⇒ 放行」在日志上必须可区分 |
| **10. 修改顺序** | ① 先在需求里写清「Redis 故障时管理员怎么办」② 再改 `isBlacklisted` ③ 再改测试（把 `interceptor_redisFailureIsFailOpen` 改成相反断言，或**保留它作为「旧语义」的回归**并另立新测试）④ 再改文档 ⑤ 最后评估是否需要应急通道 |
| **11. 验证新行为** | ① Redis 挂 ⇒ 401 + 信封 1006 ② Redis 挂 ⇒ **请求体不被读**（401 应在 Filter 早期就产出）③ Redis 恢复 ⇒ 正常放行 ④ 黑名单命中的 token 仍然 401 |
| **12. 回归旧行为** | B-11、B-12、B-13 ＋ **user 域的同款行为**（跨域一致性要显式检查）＋ 启动期（启动不碰 Redis ⇒ 不受影响） |

#### 10.F.3 场景三（教学假设）：改共享层 `JwtUtil` 的签名逻辑

> ⚠️ **这是纯假设场景**（当前 `JwtUtil` 的签名逻辑是钉死的：JCA 名 `HmacSHA256` + `Jwts.SIG.HS256`，`common/.../security/JwtUtil.java:40` · `:46`）。本节要回答的是**机制级改动的影响面**：这个类**不在 admin 域内**，却同时决定 user 域与 admin 域的 token 能不能互相验签。

| # | 内容 |
|---|---|
| **1. 原有行为** | `JwtUtil.getToken` 用 `SecretKeySpec(padToHmacKeySize(secret), "HmacSHA256")` + `signWith(key, Jwts.SIG.HS256)` 签发；`padToHmacKeySize` 是**两端共用**的唯一补齐实现（`:102-111`）；user 域与 admin 域**各自**签发与验签，但**共用这两个方法** |
| **2. 新需求改了什么** | 比如：把密钥长度下限从 32 字节提到 48 字节（HS384），或把 claim 名从 `userId` 改成 `sub` |
| **3. 必须保持不变的** | ① **user 域与 admin 域必须仍能互相验签**（两域共用同一份 `JwtUtil`）② 现有已签发的 token 在过渡期内**仍要能被验签**（⇒ 除非同时换密钥，否则**不能**改补齐规则）③ 401 的形状与 1006 不变 ④ 补齐必须是**补零**（RFC 2104 的互通前提） |
| **4. 要改哪些组件** | ① **改**：`common/.../security/JwtUtil.java`（`getToken` + `padToHmacKeySize`）② **改**：`common/.../security/BearerTokenExtractor.java`（若改动涉及前缀规则）③ **改**：两侧的验签器（`interceptor/AdminJwtSignatureVerifier.java:86` ＋ user 域同名类）④ **改**：签发点（`service/AdminApiService.java` 与 user 域的对应位置）⑤ **改**：所有**已签发 token 的存量**处理策略（换密钥 ⇒ 存量全失效） |
| **5. 为什么是这些** | ① 补齐与签名是**签发端与验签端的对称操作** ⇒ 改一边必须改两边 ② **跨模块** ⇒ admin 域改不动 user 域的调用点 ⇒ 必须走**跨域收口**（本仓的 `global` 收口域）③ 存量 token 的失效不是代码问题而是**部署问题** ⇒ 必须先定「双密钥并存期」还是「一刀切」 |
| **6. 不该改哪些** | ① **不改** `AdminJwtAuthInterceptor` 的判据与黑名单逻辑（与签名无关）② **不改** `admin:` 黑名单前缀 ③ **不改** `ErrCodes` / `ErrMsg`（错误码语义与签名算法无关）④ **不改** proto / DB |
| **7. 是否需要新增 DTO / Entity / 字段 / 方法 / 配置 / 约束** | 若要「双密钥并存期」⇒ **新增配置项**（当前密钥 + 旧密钥列表）＋ **验签器**要能在多个 key 上试。**不新增** DTO / Entity / 表 |
| **8. 是否影响契约 / Proto / DB / 其它服务** | **影响 JWT 的 wire 形状**（`header.alg`、claim 名）⇒ **L1 契约** ⇒ 任何别的服务 / 客户端验签它都要同步。**影响 proto / DB**：否 |
| **9. 是否影响事务 / 幂等 / 并发 / 安全 / 异常** | **安全**：改 claim 名 ⇒ 旧 token 的 claim 取不到 ⇒ 全部 401（**存量会话全灭**）⚠️ **改补齐规则** ⇒ 与 B 侧**不再互通** ⇒ 跨系统调用全断。**并发**：双密钥并存期 ⇒ 验签要多试几个 key ⇒ 耗时略增。**幂等/事务**：无。**异常**：验签失败的表现不变（仍是 401 + 1006）⇒ 客户端看不出来 ⇒ **这也是为什么要在上线前把这件事说清楚** |
| **10. 修改顺序** | ① 先定「存量 token 怎么办」（换密钥 / 双密钥并存 / 强制重登）② 再改共享层 ③ 再改两侧验签器与签发点 ④ 再补跨域契约测试 ⑤ 最后发版（**发版顺序**决定存量是否全灭） |
| **11. 验证新行为** | ① 新签的 token 能被验签 ② **旧** token 在过渡期内仍能验签（若选了并存）③ 改过之后 admin 域与 user 域的 token **仍能互相验签**（跨域用例）④ 签名错仍然 401 + 1006 |
| **12. 回归旧行为** | admin 域 **B-05、B-07、B-09、B-15** ＋ user 域的对应 JWT 用例（**跨文档**：`../users/10-JWT认证与身份传递流程.md`）＋ 登录流程（签发端）＋ **注销流程**（黑名单 key 用的是签发出来的 token ⇒ token 形状变了 ⇒ key 也变了 ⇒ 两端必须同步） |

#### 10.F.4 看似相关、但**不应该**改的地方

| 看着该改 | 为什么**不该**改 |
|---|---|
| 「把验签/黑名单两套取 token 的方式统一成一套」 | B 侧**本身就是两套** ⇒ 统一就是**把 B 侧的 bug 修掉** ⇒ 制造一处**新的**语义漂移。源码明确写「这是 B 侧 自身的不一致，本实现原样保留」（`interceptor/AdminJwtAuthInterceptor.java:214-216`）。要改必须走决策流程 + 双侧活体对拍 |
| 「把 admin 域的畸形 claim 改成和 user 域一样不拒绝」 | admin 侧畸形 claim = 401 是**既有语义**；user 侧是 DRIFT-USERAPI-001 的产物 ⇒ 两域**刻意不统一**（`interceptor/AdminJwtSignatureVerifier.java:128-130`）⇒ 改了就多一处漂移 |
| 「给 `BearerTokenExtractor` 加一个更严格的解析（不匹配就报错）」 | 现在的规则是「不匹配时**原样返回整串**」⇒ 若改成报错，`"XXBearer eyJ..."` 会在**提取阶段**就 401 ⇒ 看起来更安全，但 B 侧是「用整串去验签 ⇒ 必然失败」⇒ **两边的失败原因不同**（提取失败 vs 签名不符）⇒ 描述文本会变 ⇒ wire 漂移 |
| 「把 `JwtUtil` 复制一份到 admin 域，方便改」 | 签发端与验签端**必须**落在同一份 key 上（10.C.7）⇒ 复制即等于制造「两侧补齐不一致」的隐患。共享层就是为此存在的 |
| 「给 `AdminJwtAuthPaths` 加一条「显式列举受保护端点」的白名单，便于审计」 | 这就是**已经被 E-4 推翻的旧形态**（台账 F-9 / A-30）⇒ 会重新引入「漏保护」漏洞 ⇒ 不要走回头路 |

#### 10.F.5 修改的四种类型，一句话区分

| 类型 | 判断标准 | 处理方式 |
|---|---|---|
| **需求确实要求改** | 新需求直接点名了它 | 改，并补验证 |
| **为保持原有行为必须同步改** | 不改它，新需求就会**破坏**旧行为 | 一起改，且**在回归清单里显式列出** |
| **看似相关但不该改** | 改了会让**别的**契约漂移 | **不改**，把理由写进台账/注释 |
| **项目没实现、仅教学演示** | 现状里根本不存在 | **明确标记为假设**，不得写成「现有功能」 |

---

### 10.G 本章的边界

| 项 | 说明 |
|---|---|
| **本章能给你什么** | 一条**可复用**的推导链：需求 → 行为清单 → 组件 → 代码 → 验证 → 排错 → 变更；以及一条**机制级**的判据：**「失败方向」决定判据形态**（枚举 = 默认不保护 ⇒ 危险；前缀 = 默认保护 ⇒ 安全） |
| **本章给不了什么** | 「原作者当初为什么这么设计」——仓库里没有需求文档与设计记录 ⇒ 这类问题一律【存疑】。本章能确认的是**行为**，不是**动机** |
| **哪些内容是本机制特有的** | 「验签在 Filter / 授权在 Interceptor」的两段式（源于 3 秒预算的链序约束）· 「admin 域默认密钥只有 15 字节 ⇒ 必须补零到 32 字节（RFC 2104）」· 「两套取 token 的方式」· 「admin 域畸形 claim = 401、user 域不是」· 「黑名单前缀 `admin:`」 |
| **⚠️ 本章与前 9 章的关系** | 前 9 章描述的**路径判据形态**已被 E-4 活体对拍推翻（显式 11 条集合 ⇒ 前缀语义；未知路径 404 ⇒ 401/500）⇒ **前 9 章的 §2.3、§4.1、§5.1 与当前源码不一致**。本章以**当前源码**为准。改动记录见 `docs/DRIFT/users/admin/verification-admin-2026-11-02.md` 的 F-9 / A-30 行 |

> 📌 **下一章开始，请带着本章的「行为清单」去读**： [15-异常处理与统一响应流程.md](./15-异常处理与统一响应流程.md) 讲的是**另一个公共机制**（信封与异常翻译），它的推导起点不是「一句话需求」，而是**「一次 HTTP 请求的最终响应由谁决定」**。但两篇共享同一条铁律：**异常在哪一层被翻译，就决定了客户端看到什么形状**。
