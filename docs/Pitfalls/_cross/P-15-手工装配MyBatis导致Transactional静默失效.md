# P-15 · 「手工装配 MyBatis 没用 `SpringManagedTransactionFactory`」⇒ **`@Transactional` 静默失效**

> **登记时间**：2026-11-02（`align/users-admin` 工作树 · 写 admin 域的真 PostgreSQL 领域测试时暴露）
> **性质**：**静默失效**的坑（**事务边界消失 ⇒ 悲观锁与回滚都测不出来 ⇒ 但业务单测照样绿**）
> **发现者**：4 步原子事务（`AdminCreateAtomicService`）的插入在真 PG 上抛
> `SqlSessionFactory must be using a SpringManagedTransactionFactory in order to use Spring transaction synchronization`

---

## 一、形态

**手工装配 `SqlSessionFactory`（测试里、或非 Spring Boot 场景）时**，MyBatis 的
`Environment` 默认用 `ManagedTransactionFactory`（或随手写的 `JdbcTransactionFactory`）：

```java
cfg.setEnvironment(new Environment("x", new JdbcTransactionFactory(), ds));   // ❌
```

⇒ ⇒ 与 Spring 的 `DataSourceTransactionManager` **不协同**
⇒ ⇒ `SqlSessionTemplate.insert(...)` 抛：

```
org.springframework.dao.TransientDataAccessResourceException:
SqlSessionFactory must be using a SpringManagedTransactionFactory
in order to use Spring transaction synchronization
```

⇒ ⚠️ **为什么这条特别危险**：它**不是**「事务没生效」，而是**直接抛异常** ⇒
⇒ 但如果**测试用的 Mapper 不走 `SqlSessionTemplate`**（例如自己 new 一个 `SqlSession`），
**异常不会出现**，而是**事务边界悄悄消失** ⇒ ⇒ **4 步事务退化成 4 条 auto-commit**
⇒ ⇒ **悲观锁（`SELECT … FOR UPDATE`）失效、回滚失效**，而**测试全绿**。

⇒ ⇒ 正确做法：

```java
cfg.setEnvironment(new Environment("x", new SpringManagedTransactionFactory(), ds));   // ✅
```

⚠️ **包名**：`org.mybatis.spring.transaction.SpringManagedTransactionFactory`
（**不是** `org.mybatis.spring.SpringManagedTransactionFactory`）。

---

## 二、实测代价（RAG-V1 · admin 域）

- admin 的 `createAdmin` 是 **4 步原子事务**（W1 悲观锁 → W2 username 查重 → bcrypt → insert → 标记 invite used），
  **必须落在独立 `@Service` Bean 上**（self-invocation 会让 `@Transactional` 代理失效）。
- 首版测试用 `JdbcTransactionFactory` ⇒ 插入直接抛
  `TransientDataAccessResourceException` ⇒ **4 步事务的 rollback 从未被验证过**。
- ⇒ 修法：`SpringManagedTransactionFactory` ＋ **最小 Spring 上下文**
  （`@EnableTransactionManagement` + `DataSourceTransactionManager` + `SqlSessionTemplate`）
  ⇒ ⇒ **代理是真的** ⇒ `@Transactional` 真生效。

---

## 三、判定式（可复用）

1. ⇒ **凡手工装配 MyBatis ⇒ 必须用 `SpringManagedTransactionFactory`**（只要打算用 Spring 的事务管理）。
2. ⇒ **凡要验「事务边界」⇒ 断言必须落在「事务内部可见、事务外不可见」的那一处**
   ⇒ ⇒ 例如「B 步失败 ⇒ A 步的写入必须回滚」；**只断言「抛了异常」是不够的**。
3. ⇒ **凡 `@Transactional` ⇒ 必须经容器拿 bean** ⇒ 直接 `new` 出来的是**无代理裸对象** ⇒ **注解完全不生效**。
4. ⇒ **自检句式**：「我这条测试里，**回滚**真的被触发过吗？」——没触发过 ⇒ **事务没被验证**。

---

## 四、修法（本仓落点）

- `service/users/admin/src/test/java/com/rag/admin/domain/service/AdminDomainServiceRealPgTest.java` ——
  `SpringManagedTransactionFactory` ＋ 最小 Spring 上下文（`@EnableTransactionManagement` ＋
  `DataSourceTransactionManager` ＋ `SqlSessionTemplate`）⇒ 20 例全部跑在**真 PG + 真 Redis** 上。
- ⚠️ **附带一条**：`MybatisSqlSessionFactoryBean` 在 **MP 3.5.17** 已移出 `mybatis-plus-extension`
  （落到独立的 `mybatis-plus-spring` artifact）⇒ ⇒ 本仓 test classpath 上**没有它**
  ⇒ ⇒ 改用**普通 MyBatis** 的 `SqlSessionFactoryBuilder` ＋ MP 的 `MybatisConfiguration`
  （`addMapper` 仍走 `MybatisMapperRegistry` ⇒ `TableInfoHelper.initTableInfo` 仍会为
  `@TableName(autoResultMap = true)` 装配带 `typeHandler` 的 ResultMap ⇒ **与生产路径同构**）。

---

## 五、同族

- AGENTS.md §3.3.3「**『Bean 存在』≠『机制生效』**」⇒⇒ 本条是它在**事务**上的翻版。
- 「**『跑绿』≠『本来就绿』**」⇒⇒ **事务边界消失时，最典型的表现就是「测试全绿」**。
- P-14（`@Valid` 被跳过）⇒⇒ 同族：**注解在 / 注解不在**都不足以判断「这条机制跑没跑」⇒⇒ **要读消费点 + 要有一条能证伪的用例**。
