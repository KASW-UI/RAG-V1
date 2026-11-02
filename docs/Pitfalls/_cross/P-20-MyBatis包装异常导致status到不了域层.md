# P-20 · MyBatis 把 mapper 调用里的**任何**异常包成 `PersistenceException` ⇒ 共享层翻出的 `status` **到不了域层**

> **一句话**：`DefaultSqlSession` 对 mapper 调用抛出的**每一个**异常都无条件 `catch (Exception e) → ExceptionFactory.wrapException(...)` ⇒ ⇒ 持久层翻好的 `StatusRuntimeException(DEADLINE_EXCEEDED)` **在离开持久层之前就被吃掉一层** ⇒ ⇒ 域层的 `catch (StatusRuntimeException e) { throw e; }` **看不见它** ⇒ ⇒ 落进 `catch (Exception e)` ⇒ ⇒ **被改写成 `INTERNAL`（5001）**。

## 一、现象（本仓实测 · 2026-11-03 · admin 域 · PG 停）

共享层已经把「取连接超时」翻成了正确的 status，日志里**清清楚楚**：

```
Caused by: io.grpc.StatusRuntimeException: DEADLINE_EXCEEDED: connection acquisition timed out at rpc deadline
    at com.rag.common.persistence.DeadlineTranslatingDataSource.translateIfAcquisitionTimeout(...)
    at com.rag.common.persistence.DeadlineTranslatingDataSource.getConnection(...)
```

**但客户端拿到的是 5001，不是 1015**。原因在同一份栈的**上一层**：

```
org.apache.ibatis.exceptions.PersistenceException:
### Error querying database.  Cause: io.grpc.StatusRuntimeException: DEADLINE_EXCEEDED: ...
    at org.apache.ibatis.exceptions.ExceptionFactory.wrapException(ExceptionFactory.java:30)
    at org.apache.ibatis.session.defaults.DefaultSqlSession.selectCursor(DefaultSqlSession.java:129)   ← 这里包了一层
    at org.apache.ibatis.session.defaults.DefaultSqlSession.selectCursor(DefaultSqlSession.java:117)
```

⇒ ⇒ 域层拿到的是 `PersistenceException` ⇒ ⇒ `catch (StatusRuntimeException e)` **不匹配** ⇒ ⇒ 落 `catch (Exception e)` ⇒ ⇒ `Status.INTERNAL` ⇒ ⇒ api 层 `mapRPCStatusToCode(INTERNAL)` ⇒ **5001**。

## 二、为什么它特别难发现

| 坑 | 说明 |
|---|---|
| **翻译层「看起来是好的」** | 日志里明明出现了 `DEADLINE_EXCEEDED` ⇒ 很容易判「翻译器没问题」⇒ **但那只是「翻译发生了」，不是「翻译**到达了**消费者」** |
| **B 侧没有这一层** | B 侧的 gRPC 中间件**原样**把 status 交给 api 层（中间件不做异常包装）⇒ ⇒ 照抄 B 侧的翻译器时，这层包装**根本不在 B 的心智模型里** |
| **域层已经写好了放行分支** | 本仓 46 处 `catch (StatusRuntimeException e) { throw e; }` ⇒ **设计意图明确**是「共享层把 status 送到域层」⇒ ⇒ 域层没毛病，**是共享层少了一环** |
| **仓库自己的文档已写过这条** | `docs/GORM-MP-语义对照.md` 第 157 行：「57014 必须被翻译成 `DEADLINE_EXCEEDED`，且**不能被 `catch (Exception e)` 吞成 INTERNAL**」⇒ ⇒ **这条要求在文档里存在，但没人验证过它成立** |

## 三、判据（**怎么一眼认出它**）

1. 日志里**有** `DEADLINE_EXCEEDED`，**但**客户端拿到 5001 ⇒ ⇒ **先查中间有没有被包装**。
2. 栈顶出现 `org.apache.ibatis.exceptions.ExceptionFactory.wrapException` ⇒ ⇒ **实锤**。
3. `DefaultSqlSession` 的源码实测（MyBatis 3.5.19）：`selectOne / selectList / selectCursor / selectMap / select / insert / update / delete / flushStatements / commit / rollback / getConnection` **全部**是 `catch (Exception e) → wrapException(...)` ⇒ ⇒ **无路可绕**。

## 四、修法（本仓已落地 · 2026-11-03）

**解包点选在 `SqlSessionFactory`**（读源码确定，不是猜的）—— 本仓两条 mapper 路径**都**经过它：

- 常规路径：`SqlSessionTemplate.SqlSessionInterceptor` → `SqlSessionUtils.getSqlSession(factory, …)` → `factory.openSession(execType)`；
- MP 的游标路径：`BaseMapper.selectOne(Wrapper, boolean)` → `MybatisUtils.getSqlSessionFactory(sqlSession)`
  → `SqlSessionTemplate.getSqlSessionFactory()`（**public getter**）→ `factory.openSession()`。

⇒ ⇒ **包装工厂一个 bean 即全覆盖** ⇒ `common/persistence/StatusAwareSqlSession{,Factory}.java`。
⚠️ **不要**改 mapper 代理（会破坏 MP 的 `MybatisUtils.getMapperProxy(proxy)` 的代理身份识别 ⇒ MP 内部元数据查找直接 NPE）。

⚠️ **只解包 `DEADLINE_EXCEEDED`**（不放宽到任意 status）⇒ ⇒ 本仓 46 处放行分支的既有行为**一个都不变** ⇒ **爆炸半径最小**。

## 五、可判别的守门写法（本仓已落地）

⚠️ **只断言「抛了异常」是无效断言** —— 包装层还在时**照样是异常** ⇒ ⇒ 测试**照样绿** ⇒ ⇒ 必须写明**类型**与**码**：

```java
assertThatThrownBy(() -> mapper.xxx())
    .isInstanceOf(StatusRuntimeException.class)            // ← 不是 PersistenceException
    .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
            .isEqualTo(Status.Code.DEADLINE_EXCEEDED));    // ← 码也要钉
```

⇒ 文件：`common/src/test/java/com/rag/common/persistence/DeadlineBudgetPersistencePgTest.java`（真 PG · 6 例）。

## 六、⇒ 同族

- 与 P-12（「变异没变红」＝「那条断言没覆盖到」）同源：**「翻译发生了」≠「翻译到达了消费者」**；
- 与 P-02（「注解存在 ≠ 机制生效」）同源：**「日志里有那个 status」≠「客户端拿到那个码」**；
- 与 P-15（手工装配 MyBatis ⇒ `@Transactional` 静默失效）同源：**MyBatis/Spring 这一层的「装配形态」决定机制是否成立**，而**不报错**。
