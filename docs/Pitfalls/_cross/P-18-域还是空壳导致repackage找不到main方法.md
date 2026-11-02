# P-18 · 域还是空壳 ⇒ **没有 `main` 方法** ⇒ `spring-boot-maven-plugin` 的 `repackage` 失败 ⇒ `mvn verify` **恒红**

> **一句话**：**8 个业务域都还是空壳**（一个 `main` 方法都没有）⇒ 但 pom 里配了 `spring-boot-maven-plugin` ⇒ ⇒ 打包阶段的 `repackage` 找不到 main class ⇒ **构建失败** ⇒ ⇒ **且这个红与「代码写得好不好」无关**。

## 一、现象

```
[ERROR] Failed to execute goal org.springframework.boot:spring-boot-maven-plugin:3.5.11:repackage
        (repackage) on project article-service: Execution repackage of goal
        org.springframework.boot:spring-boot-maven-plugin:3.5.11:repackage failed:
        Unable to find main class -> [Help 1]
```

**8 个域全中**（article · comment · favorite · follow · hot · like · message · task）。

## 二、为什么必须记这条（**它会骗人**）

| 坑 | 说明 |
|---|---|
| **红的位置极具误导性** | 报错出现在 **`package` 阶段的 `repackage` goal** ⇒ 看起来像「jar 打得不对 / 插件配错了」⇒ 人会去查 pom 的 plugin 配置 ⇒ **查不到问题** |
| **真实原因是「域还没实现」** | 根因 = **域内没有 `static void main`** ⇒ 而 `spring-boot-maven-plugin` 的 `repackage` **必须**找到 main class 才能打成可执行 jar ⇒ 域还是空壳 ⇒ 必然失败 |
| **已实现的域不受影响** | `service/users/user` · `service/users/admin` 有 `main`（`UserServiceApplication` / `AdminServiceApplication`）⇒ **BUILD SUCCESS** ⇒ ⇒ 「有的域绿、有的域红」的红绿分布**与代码质量无关**，只与「该域实现到哪一步」有关 |
| **`mvn verify` 因此恒红** | ⇒ **不能**用「`mvn clean verify` 绿」当全仓验收信号（当前形态下它永远红）⇒ 真要验 ⇒ **`mvn -o clean test-compile` 或 `mvn -o clean test`** |

## 三、判据（**怎么区分「主干自带」与「本轮引入」**）

⚠️ **不要**看到 8 个域红就以为是自己改坏的 ⇒ **必须做同条件对照**：

```bash
# 基线纯净副本（不带本轮改动）
git archive HEAD | tar -x -C /tmp/ragv1-base
cd /tmp/ragv1-base && mvn -o clean verify -fae
```

⇒ **两侧失败模块集合完全相同** ⇒ 该红是**基线自带**，不是本轮引入。

> ⚠️ **`-fae`（fail-at-end）是必须的** —— 默认 fail-fast 会在**第一个**失败模块就停 ⇒ 后面全 `SKIPPED` ⇒ 只看到「1 个域红」⇒ **误读成「本轮改红了 1 个域」**（实测基线 8 个域红，默认模式下只暴露 1 个）。

## 四、根因级判据（**不靠读日志**）

```bash
# 哪个域还没有 main？
for d in article comment favorite follow hot like message task; do
  echo "$d: $(grep -rl 'static void main' service/$d/src/main/java/ 2>/dev/null | wc -l)"
done
# 实测：8 个域全部 = 0 ⇒ 与「repackage 失败」一一对应
```

⇒ **失败模块集合 ≡ 无 `main` 方法的模块集合** ⇒ 这就是判据。

## 五、当前形态与本条的关系

- **本条的解法不在本条** —— 要让 `mvn verify` 转绿，得先给 8 个域补 `Application` 主类（属于**各域实现任务**，不是共享层/收口任务能顺手做的）。
- ⇒ **在域实现完成前，全仓验收信号用 `mvn -o clean test-compile` / `mvn -o clean test`**（两者当前**全绿**）。
- ⚠️ **别为了「让 verify 变绿」而去给空壳域硬造一个 `main`** —— 那会造出一个**没有真启动逻辑的假入口** ⇒ 比红更危险（§八「机制可证」）。

## 六、同族

- **P-12**（变异没变红 = 那条断言没覆盖到）—— 同族：**「读数」不等于「结论」**；本条补的是「**红在哪 ⇒ 根因可能完全不在那**」这一面。
- **「绿灯也要做同条件对照」**（假绿灯能活很多轮，因为「红」才触发查因的直觉）—— 本条是它的**镜像**：**红也要做同条件对照**，否则会把**基线自带**的错记成**本轮引入**。

> 📖 关联：AGENTS.md §3.3（机制必须验证实际生效）· §4.2（推进方式与完成信号）· §五.8（守门四规则）
