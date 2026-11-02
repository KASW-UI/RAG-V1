# P-14 · 「`@RequestBody(required = false)` + 空 body」⇒ **`@Valid` 被跳过** ⇒ `null` 进业务层 ⇒ **500**

> **登记时间**：2026-11-02（`align/users-admin` 工作树 · 写 admin 域的真 Tomcat 契约测试时暴露）
> **性质**：**静默失效**的坑（校验不生效 ⇒ 参数缺失 ⇒ **把「客户端写错」报成「服务端故障」**）
> **发现者**：真 Tomcat 上打一发**不带 body** 的 `POST /admin/v1/login`

---

## 一、形态

```java
@PostMapping("/login")
public LoginResp login(@Valid @RequestBody(required = false) LoginReq req) {
    return adminApiService.login(req);          // ← req 可能是 null
}
```

⇒ **空 body** 时：

1. `AbstractMessageConverterMethodArgumentResolver#readWithMessageConverters`（spring-webmvc 6.2.16）
   ⇒ body 为空 ⇒ 走 `getAdvice().handleEmptyBody(...)` ⇒ **返回 `null`**
2. `RequestResponseBodyMethodProcessor#resolveArgument` ——
   ```java
   if (arg != null) {
       validateIfApplicable(binder, parameter);   // ← arg == null ⇒ **校验整段被跳过**
       ...
   }
   ```
   ⇒ ⇒ **`@Valid` 对 `null` 不生效** ⇒ **缺必填字段这件事没人拦**
3. ⇒ `null` 传进业务层 ⇒ ⇒ **NPE** ⇒ ⇒ 落到兜底 advice ⇒ **HTTP 500**

⇒ ⚠️ **危险之处**：**这不是「少了一条校验」，是「把参数错误变成了服务端故障」**——
500 会进告警、会触发重试、会掩盖真实原因。

---

## 二、实测代价（RAG-V1 · admin 域）

- `POST /admin/v1/login` **不带 body** ⇒ C 侧 **500 + 5001**；
  **B 侧（go-zero v1.10.2）是 400 + text/plain**。
- ⚠️ **B 侧为什么是 400**（实测源码）：
  - `rest/httpx/requests.go:153` `withJsonBody(r) = r.ContentLength > 0 && ContentType 含 application/json`
    ⇒ **空 body ⇒ false**
  - ⇒ `ParseJsonBody` 走 `mapping.UnmarshalJsonMap(nil, v)`
  - ⇒ `core/mapping/unmarshaler.go:1199` `newInitError` ⇒ `field "username" is not set` ⇒ **400**
- ⇒ ⇒ **C 侧此前的 javadoc 理由（「保留 B 侧 body 可以整个缺席的传输层语义」）是错的**：
  **B 侧的「body 缺席」= 零值 struct = 必填字段缺失 = 400**，**不是**「接受空 body」。

---

## 三、判定式（可复用）

1. ⇒ **凡 `@Valid @RequestBody(required = false)` ⇒ 必须回答：「body 为空时，谁来拦？」
   ⇒ ⇒ **没人拦**（`@Valid` 跳过 `null`）⇒ ⇒ 要么改成**必填**（空 body ⇒ `HttpMessageNotReadableException` ⇒ 400），
   **要么**自己在 controller 里把 `null` 归一化成零值实例**再**校验。
2. ⇒ **必填 vs 可空，要按「B 侧这个端点的 req 有没有必填字段」来定**，**不是**按「传输层允不允许 body 缺席」：
   - 有必填字段 ⇒ **必填**（B 侧空 body ⇒ 400）
   - 空 struct ⇒ **可空** + 归一化（B 侧空 body ⇒ 正常受理）
3. ⇒ **同族：`required = false` + 空 body ⇒ `null` ⇒ NPE** ⇒
   ⇒ **任何 `@RequestBody(required = false)` 的端点，都要有一条「空 body」的测试**。

---

## 四、修法（本仓落点）

- `service/users/admin/src/main/java/com/rag/admin/controller/AdminController.java` ——
  **10 个有必填字段的端点**改用**必填** `@RequestBody`（空 body ⇒ `HttpMessageNotReadableException` ⇒ 400 + text/plain）；
  **3 个空 struct 端点**保留 `required = false` + `new XxxReq()` 归一化（B 侧对它们是正常受理）。
- `service/users/admin/src/test/java/com/rag/admin/http/AdminHttpContractRealTomcatTest.java` ——
  `#absentBodyIs400`（空 body ⇒ 400 + text/plain + nosniff + 尾随换行）
  ＋ `#missingRequiredFieldIs400PlainTextWithNosniff`（`{}` ⇒ 同一形状）⇒ **两条路径同形**。

---

## 五、同族

- AGENTS.md §3.3.3「**不得凭注解名称断定机制已启用**」⇒⇒ **本条是它在参数校验上的翻版**：
  `@Valid` **在**参数上 **≠** 校验**在**跑（`null` 时它不跑）。
- 「**『跑绿』≠『本来就绿』**」⇒⇒ 本条说明：**没有「空 body」这条用例时，全绿也照样带着 500**。
