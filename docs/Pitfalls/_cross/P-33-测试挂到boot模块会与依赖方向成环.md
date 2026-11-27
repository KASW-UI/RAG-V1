# P-33 · 「把测试挂到 boot 模块」⇒ **Maven 成环** ⇒ `ProjectCycleException`（**连 test scope 也拒**）

> 档位：跨模块（`_cross/`）· 首次登记 **2026-11-28** · 触发场景：**S2 · 测试类归属重排**

---

## 一、现象

五进程形态下，boot 模块依赖域模块（`content-service` → `article-service`）⇒
计划书 P3 写的是「只把 `@SpringBootTest` 的启动类**指向新的 boot 启动类**」
⇒ ⇒ 域测试要引用 boot 的启动类 ⇒ ⇒ **域模块的 test classpath 必须含 boot 模块**
⇒ ⇒ ⇒ **域模块得依赖 boot 模块**。

🔴 **实测**（2026-11-28 · 在 `service/article/pom.xml` 注入 `content-service` 的 test 依赖后实跑）：

```
[ERROR] The projects in the reactor contain a cyclic reference: Edge between
        'Vertex{label='com.rag:content-service:1.0-SNAPSHOT'}' and
        'Vertex{label='com.rag:article-service:1.0-SNAPSHOT'}' introduces to cycle in the graph
        com.rag:article-service:1.0-SNAPSHOT
     --> com.rag:content-service:1.0-SNAPSHOT
     --> com.rag:article-service:1.0-SNAPSHOT
[ERROR] [Help 1] .../maven/ProjectCycleException.html
```

⚠️ **关键**：**`scope=test` 也一样被拒** ⇒ ⇒ 因为 Maven **建 reactor 图时不看 scope**
⇒ ⇒ **只要有依赖边（哪怕 test），图上就有环**。

---

## 二、根因：这是**拓扑**问题，不是「写法」问题

```
        ┌──────────────────────────┐
        │  boot 模块（进程启动壳）   │
        │  content-service         │
        └───────────┬──────────────┘
                    │ compile 依赖
                    ▼
        ┌──────────────────────────┐
        │  域模块（库）             │
        │  article-service         │
        └──────────────────────────┘
                    ▲
                    │ ✗ 想加 test 依赖 ⇒ 成环
                    │
        ┌───────────┴──────────────┐
        │  boot 模块（同一个）       │
        └──────────────────────────┘
```

⇒ ⇒ **`boot → 域` 是「启动壳依赖被启动的库」** ⇒ ⇒ **方向不可逆**
⇒ ⇒ **凡是要让「被依赖方」反过来看到「依赖方」的方案 ⇒ 一律成环**。

---

## 三、✅ 落地方案：**两套测试，各测一件事**

| 测试套 | 位置 | 测什么 | 启动入口 |
|---|---|---|---|
| **域测试**（283 个） | 留在**域模块** | 「**单域**上下文能不能起」 | **域启动类**（`@Configuration` 降级后仍可独立用） |
| **装配层测试** | **boot 模块** | 「**合并**上下文里域 bean 在不在、他域有没有串进来」 | **boot 启动类** |

⇒ ⇒ **P3 的真实意图（「283 个测试类留在各自域模块 ⇒ 域测试仍可独立跑」）已达成**
⇒ ⇒ 不可实现的只是「指向 boot 启动类」这个**实现手法**
⇒ ⇒ **意图与手段分离** ⇒ 手段换一个，意图不丢。

🔴 **可复用的判据**：**想让 A 模块的测试引用 B 模块的类 ⇒ 先问「B 是否依赖 A」**
⇒ ⇒ 是 ⇒ **成环，做不了** ⇒ ⇒ 此时的正确解法 = **在 B 侧另立一条「合并视角」的测试**
⇒ ⇒ 而不是硬把 A 的测试搬进 B。

---

## 四、⚠️ 元教训

1. **决策文档里的「实现手法」不可实现时 ⇒ 不要卡住整个阶段** ⇒
   ⇒ **回到该决策的「意图」** ⇒ ⇒ 换手法、保意图（并把改动写回决策文档）。
2. **Maven 的 reactor 图不看 scope** ⇒ ⇒ **「加个 test 依赖绕一下」这条路不存在**。
3. 🔴 **实测优于推断**：本条一开始只是「读依赖方向 ⇒ 推断会成环」⇒ ⇒ **实跑一次拿到
   `ProjectCycleException` 的原文**才落定 ⇒ ⇒ 且顺手拿到了**可写进文档的证据**。
   （⚠️ 与 P-09「批量替换的三个静默失效」同款纪律：**实测拿原文，别靠推断下结论**。）
