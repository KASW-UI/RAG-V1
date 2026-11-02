# Lombok 使用规范（D18）

> **决策**（2026-11-02 用户定）：**本项目允许并鼓励使用 Lombok**；但**凡用 Lombok 简化掉的「原本的完整代码」，必须以 `/** */` 注释的形式，原样写在 Lombok 注解的上方**。
> **配套**：[`../AGENTS.md`](../AGENTS.md) §1.0e（政策条目）· [`决策记录.md`](决策记录.md) **D18**（决策依据与代价）· [`架构符合性断言.md`](架构符合性断言.md) **A13**（机器断言）
>
> 📌 **为什么允许**：Lombok 消除的是**纯样板代码**（getter/setter/构造器/log 字段），不改变任何业务语义 ⇒ 属**表达层**（L4）⇒ 不违反 AGENTS.md §三「差异只允许出现在表达层」。
> 📌 **为什么强制留注释**：B 侧（RAG 仓）是唯一 oracle（D16）⇒ **review 的人必须能在不打开 IDE、不跑 delombok 的情况下，看到「这段代码原本长什么样」** ⇒ 否则「Lombok 化」就成了一段**不可对照的黑盒**（正是 §3.1 警告的「拿一面没人校过的镜子照自己」）。

---

## 一、决策内容

| 项 | 结论 |
|---|---|
| **允许吗** | ✅ **允许**（L4 机制层） |
| **鼓励吗** | ✅ **鼓励** —— 能用 Lombok 简化样板代码的，**优先用 Lombok**（§二 逐场景表） |
| **留痕吗** | ✅ **强制** —— **原本的完整代码**以 `/** */` 注释写在**该 Lombok 注解的正上方**（§三） |
| **谁来判** | 🤖 **A13 机器断言**（`tools/difftest/check-arch-invariants.py`）—— 不是靠自觉 |
| **依赖怎么落** | **各模块自带** `lombok`（`provided`）+ 显式 `annotationProcessorPaths`（§五） |

---

## 二、逐场景表：**鼓励 / 谨慎 / 禁止**

> 判据 = **「Lombok 生成的东西会不会改变 B 侧可观察行为」**（§三 的「语义等价论证」同一条线）。

| 场景 | 判定 | 理由 |
|---|---|---|
| **实体类**（`domain/entity/`）的 getter/setter/构造器 | ✅ **鼓励** | 纯样板；B 侧同名实体也这么写 ⇒ 行为等价 |
| **`@Data` 用于实体** | ✅ **鼓励** | 等价于「手写 getter + setter + equals + hashCode + toString」⇒ 展开注释里写全即可 |
| **`@Getter` / `@Setter`** | ✅ **鼓励** | 同上 |
| **`@NoArgsConstructor` / `@AllArgsConstructor` / `@RequiredArgsConstructor`** | ✅ **鼓励** | Jackson 反序列化、MyBatis 构造都常见 |
| **`@Builder`** | ✅ **鼓励**（但见下方注意事项） | 建造器替换「一堆 setter」⇒ 展开注释写全 |
| **配置类**（`@ConfigurationProperties`） | ✅ **鼓励** | setter 绑定是 Spring 标准形态 |
| **`@Slf4j`** | ⚠️ **谨慎** —— **默认不用** | 本仓的日志契约走 `GoStyleLogger`（实现 `org.slf4j.Logger`，保留 B 侧 `call_chain`/`event_id` 字段）⇒ **`@Slf4j` 生成的是普通 SLF4J logger，拿不到那些字段** ⇒ 只有确认不需要 B 侧日志字段时才用 |
| **`@EqualsAndHashCode` / `@ToString`** | ⚠️ **谨慎** | 会影响集合去重 / 日志输出 ⇒ 与 B 侧语义逐条对照后再用 |
| **DTO 层**（`dto/` 下的类型） | ⛔ **禁止** | D5 + **A9**：DTO 必须是 **immutable record**（无 setter）⇒ `@Data` 生成 setter ⇒ **直接违反 A9** |
| **`@Value` / `@Builder` 用于 DTO** | ⛔ **禁止** | 同上 —— DTO 一律用 `record` |
| **`@Data` 用于 `Map`/集合字段的实体** | ⚠️ **谨慎** | `@Data` 生成的 `equals` 含集合字段 ⇒ 与 B 侧 `reflect.DeepEqual` 语义未必一致 ⇒ 对照后再用 |
| **`@Cleanup` / `@SneakyThrows` / `@UtilityClass`** | ⛔ **禁止** | `@SneakyThrows` 吞异常 ⇒ 破坏 B 侧错误契约（AGENTS.md §3.5 `advice`/`exception` 双轨） |

---

## 三、**核心规则**：原本的完整代码写在 Lombok 注解上方

### 3.1 规则原文

> **凡用 Lombok 简化掉的「原本的完整代码」，必须以 `/** */` 注释的形式，原样写在**该 Lombok 注解的正上方**。

### 3.2 为什么这么定

| 理由 | 说明 |
|---|---|
| **可对照** | B 侧是唯一 oracle（D16）⇒ review 的人要在**一个文件里**同时看到「B 出处」＋「本仓最终形态」＋「Lombok 之前的完整代码」⇒ 三者同屏，不需要 IDE 的 "Generate toString" / `delombok` |
| **不可漂移** | AGENTS.md §3.1 明确：**注释写的是实现者视角，可能已过期 ⇒ 以代码为准** ⇒ 若「原本代码」只存在于注释里，它会悄悄过期 ⇒ 所以它**必须与代码同处一个文件、同一次提交** |
| **可机械检查** | 「这个类用了 Lombok 吗？⇒ 它的 javadoc 里有没有原本代码？」—— 这是**可写成脚本的判据**（A13）⇒ 不靠自觉 |

### 3.3 标准写法（**注释写在注解正上方**）

```java
/**
 * <p>共享层的响应信封 —— 对应 B 侧 {@code service/common/.../response/Response.java:14}。
 *
 * <p><b>B 出处</b>：{@code service/common/src/main/java/com/rag/common/response/Response.java:16:3-7}
 * ⇒ 三个 JSON 键与字段顺序逐条一致 ⇒ 语义等价。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * public class ApiResponse {
 *     private int code;
 *     private String msg;
 *     private Object data;
 *
 *     public int getCode() { return code; }
 *     public void setCode(int code) { this.code = code; }
 *     public String getMsg() { return msg; }
 *     public void setMsg(String msg) { this.msg = msg; }
 *     public Object getData() { return data; }
 *     public void setData(Object data) { this.data = data; }
 *
 *     public ApiResponse() { }
 *     public ApiResponse(int code, String msg, Object data) {
 *         this.code = code;
 *         this.msg = msg;
 *         this.data = data;
 *     }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse {
    private int code;
    private String msg;
    private Object data;
}
```

### 3.4 写法细则（**逐条**）

| # | 细则 | 说明 |
|---|---|---|
| **R1** | **位置** = **该 Lombok 注解的正上方** | 不是类的顶部、不是字段上 ⇒ 一眼定位「这注解替代了什么」 |
| **R2** | **形式** = `/** */`（javadoc 块注释） | 不是 `//` 行注释 ⇒ 能被 IDE 的 javadoc 面板与脚本解析 |
| **R3** | **内容** = **完整的**原本代码 | **不许**写「其余字段同理」「getter/setter 略」⇒ A13 会判红 |
| **R4** | **多注解 = 一个合并展开块** | `@Getter @Setter @NoArgsConstructor @AllArgsConstructor` 四个注解 ⇒ **一块**展开代码（写全这四个合起来生成的东西）⇒ 不是一个注解一块 |
| **R5** | 展开块用 `<pre>{@code ...}</pre>` 包裹 | 避免 `<`、`&` 等被 javadoc 当 HTML 解析 |
| **R6** | **保留原有 javadoc** | 展开块是**追加**，不是替换 —— B 出处、语义等价论证（L4 idiomatization 三段式 · AGENTS.md §3.2）**仍须在位** |
| **R7** | 展开代码里**不加**本仓不需要的成员 | 展开的是「Lombok 实际会生成的东西」⇒ 不多不少（如 `@Data` 不生成 setter 于 `final` 字段 ⇒ 展开里就不写那个 setter） |

### 3.5 「语义等价论证」怎么写（AGENTS.md §3.2 三段式 + 本条）

用了 Lombok ⇒ **既**要满足 AGENTS.md §3.2 的三段式（**比 B 侧多做了什么 / 语义还一样吗 / B 出处**），**又**要满足本规范的展开注释规则。

三段式中「语义还一样吗」这一段的**论证材料**，就是 §3.3 的展开块 ⇒ 两者天然合流：

```java
/**
 * <p><b>L4 idiomatization（三段式 · AGENTS.md §3.2）</b>：
 * <ol>
 *   <li><b>比 B 侧多做了什么</b>：仅「样板代码由 Lombok 生成」——
 *       生成物与手写版<b>逐字等价</b>（展开见下）。</li>
 *   <li><b>语义还一样吗</b>：一样 —— 展开块与 B 侧手写代码逐条一致
 *       （字段名、类型、可见性、构造器参数顺序）。</li>
 *   <li><b>B 出处</b>：{@code ...}。</li>
 * </ol>
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code ... }</pre>
 */
```

---

## 四、正反例

### ✅ 正例（`@Data` 用于实体）

```java
/**
 * <p>{@code users_user} 表的 MyBatis-Plus 实体。
 *
 * <p><b>B 出处</b>：{@code service/users/admin/admin-rpc/.../domain/UserEntity.java}
 * ⇒ <b>本类为同构照抄</b>，字段 / 列名 / 类型 / 注解逐条一致。
 *
 * <p><b>Lombok 展开（不用 Lombok 时的完整代码）</b>：
 * <pre>{@code
 * public class UserEntity {
 *     private Long id;
 *     private Long uid;
 *     private String username;
 *     // ...（每个字段一行）
 *
 *     public Long getId() { return id; }
 *     public void setId(Long id) { this.id = id; }
 *     // ...（每个字段的 getter + setter）
 *
 *     @Override
 *     public boolean equals(Object o) {
 *         if (this == o) return true;
 *         if (!(o instanceof UserEntity)) return false;
 *         UserEntity other = (UserEntity) o;
 *         return Objects.equals(id, other.id)
 *             && Objects.equals(uid, other.uid)
 *             // ...（每个字段一行）
 *             && Objects.equals(createTime, other.createTime)
 *             && Objects.equals(updateTime, other.updateTime);
 *     }
 *
 *     @Override
 *     public int hashCode() {
 *         return Objects.hash(id, uid /* ...每个字段 */, updateTime);
 *     }
 *
 *     @Override
 *     public String toString() {
 *         return "UserEntity(id=" + id + ", uid=" + uid /* ... */ + ")";
 *     }
 * }</pre>
 */
@Data
@TableName(value = "users_user", autoResultMap = true)
public class UserEntity {
    // ...
}
```

### ❌ 反例 1：只写「其余同上」

```java
/**
 * <p>Lombok 展开：getter/setter 略。
 */                       // ❌ R3：不完整 ⇒ A13 判红
@Data
public class UserEntity { /* ... */ }
```

### ❌ 反例 2：展开块放在类顶部，与注解分离

```java
/**
 * <p>Lombok 展开（不用 Lombok 时的完整代码）：...
 */
@Data
@TableName("users_user")
public class UserEntity { /* ... */ }
// ❌ 展开块没贴在 @Data 正上方；且 @TableName 也算注解 ⇒ 「该 Lombok 注解」指哪一处的边界不清
```

> ✅ **R1 的精确口径**：展开块紧贴**类上的那一组 Lombok 注解**（`@Data` / `@Getter` / `@Setter` / `@NoArgsConstructor` / `@AllArgsConstructor` / `@Builder` 等），放在**这组注解之前**；其它非 Lombok 注解（`@TableName` / `@TableId` / `@RestController` …）**不参与**本规则。

### ❌ 反例 3：DTO 用 `@Data`

```java
@Data                                   // ❌ 生成 setter ⇒ 违反 A9「DTO 必须 immutable」
public class GetUserTotalLikeResp { ... }
```

---

## 五、依赖落点（**没有依赖 = 编译不过**）

> ⚠️ **实测（2026-11-02）**：本仓 `lombok` 依赖原先只在 `common/`（`provided`）与 `service/users/` 聚合（`optional`）⇒ **其余 8 个业务域的 pom 原本没有 lombok** ⇒ 直接写 `@Data` 会**编译失败**（找不到符号）⇒ 2026-11-02 已补齐。

### 5.1 各模块的标准写法

```xml
<dependency>
    <groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>
    <scope>provided</scope>
</dependency>
```

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

### 5.2 三条纪律

| # | 纪律 | 理由 |
|---|---|---|
| **L1** | **版本不写 `<version>`** | 由 Boot BOM 管（`spring-boot-starter-parent` → `spring-boot-dependencies` 的 `<lombok.version>` = **1.18.42**，实测）⇒ 与 B 侧同款 |
| **L2** | **必须显式配 `annotationProcessorPaths`** | JDK 21 起 javac 对「classpath 上发现注解处理器」告警，**JDK 23+ 默认关闭** ⇒ 只靠 classpath 上的 lombok 迟早**静默失效**（注解不再生成方法 ⇒ 编译期才发现，或更糟：运行期 `NoSuchMethodError`）⇒ **行为与 JDK 版本解耦** |
| **L3** | **scope = `provided`** | lombok 是**编译期**依赖（注解处理器），产物是**已生成的方法** ⇒ 运行期不需要 lombok jar ⇒ 不传递给下游 ⇒ 下游要用自己声明（避免隐式依赖） |

> ⚠️ **显式声明 `annotationProcessorPaths` 的副作用**：classpath 上的**其它**注解处理器会被**忽略**。
> ⇒ 本仓实测**无其它**注解处理器（无 `spring-boot-configuration-processor`、无 MapStruct 等）⇒ 该副作用当前为零。
> ⇒ **若将来引入别的注解处理器，必须把它一并列进 `annotationProcessorPaths`**，否则它会静默失效。

### 5.3 版本与来源

| 项 | 值 |
|---|---|
| **版本** | **1.18.42**（Boot 3.5.11 BOM 管；`mvn help:evaluate -Dexpression=lombok.version` 实测） |
| **B 侧落点** | `service/common/pom.xml`（`provided`）＋ `service/users/pom.xml`（`optional`，聚合给子模块） |
| **本仓落点** | `common/`（`provided`）＋ `service/users/` 聚合（`optional`）＋ **8 个业务域各自的 pom（`provided`）**（2026-11-02 补齐） |

---

## 六、A13 机器断言

| 项 | 内容 |
|---|---|
| **判据** | 静态检查 ⇒ **凡类上出现 Lombok 注解**（`@Data` / `@Getter` / `@Setter` / `@NoArgsConstructor` / `@AllArgsConstructor` / `@RequiredArgsConstructor` / `@Builder` / `@Value` / `@EqualsAndHashCode` / `@ToString` / `@Slf4j` / `@Cleanup` / `@SneakyThrows` / `@UtilityClass`）⇒ 该类的 javadoc **必须**含 **Lombok 展开块** |
| **展开块识别** | javadoc 里出现 `Lombok 展开` 标记 **且** 跟一个 `<pre>{@code` 块 |
| **理由** | 「用 Lombok 就把原本代码写清楚」是**可机械判定**的 ⇒ 不靠自觉 ⇒ 与 §3.1「差异只允许出现在表达层」配套：**表达层可以变，但变的那部分必须看得见** |
| **脚本** | `tools/difftest/check-arch-invariants.py`（与 A1–A12 同级） |
| **落地状态** | ✅ **2026-11-02 已实现** —— `--self-test` 造违规假树验证判据能红 |

---

## 七、Review 时的三问

1. **这个类的 Lombok 展开块在吗？** —— 不在 ⇒ 不可 review（先补注释，别先改代码）。
2. **展开块里的代码，和 B 侧手写版逐条一致吗？** —— 尤其 **JSON 键序**、**构造器参数顺序**、**字段可见性**（`@JsonAutoDetect` 场景）。
3. **这个 Lombok 注解的行为，真的只是样板代码吗？** —— `@Data` 的 `equals` 涉及**全部字段** ⇒ 与 B 侧 `reflect.DeepEqual` 的字段集合是否一致？

---

> 📖 **关联**：AGENTS.md §1.0e（政策）· §3.2（留痕三段式）· §八（检查清单）· [`决策记录.md`](决策记录.md) **D18** · [`架构符合性断言.md`](架构符合性断言.md) **A13** · [`Pitfalls/_INDEX.md`](Pitfalls/_INDEX.md)
