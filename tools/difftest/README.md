# difftest —— 双侧对拍与架构符合性守门

> 断言的完整定义 → [`../../docs/架构符合性断言.md`](../../docs/架构符合性断言.md)

## 脚本

| 脚本 | 作用 | 状态 |
|---|---|---|
| `check-arch-invariants.py` | **架构符合性 A1–A17** —— 校验包结构、starter 化、生态优先、immutable DTO、Jackson 口径等约定 | ✅ **2026-10-25 已实现**（`--self-test` 自证 **17/17** 条判据能红） |
| `jackson-key-order/run.sh` | **JSON 键序运行时探针** —— 逐类实测「`@JsonPropertyOrder` 声明序」vs「Jackson 2 自然序」＋**序列化字节**是否一致 | ✅ **2026-11-09 已实现**（`global` 收口域「全用 jackson2」决策的**字节级证据**） |
| `check-proto-static.sh` | proto 契约对照（字段号 / message 名 / RPC 签名） | ⬜ 待实现 |
| `check-ddl-static.py` | DDL 列级对照（类型 / 长度 / 可空性 / DEFAULT / 索引） | ⬜ 待实现 |
| `run-parity.sh` | 双侧语义探针（B 侧 ↔ RAG-V1 同请求矩阵） | ⬜ 待实现 |

### `jackson-key-order/run.sh` 用法

```bash
mvn -o clean test-compile          # 前置：产出各模块 target/classes（探针不自动构建）
bash tools/difftest/jackson-key-order/run.sh
```

⇒ 逐类打印「declared（注解声明序）/ natural（压制注解后的 Jackson 2 自然序）/ honored / bytes」四行
⇒ 末尾给 `=== SUMMARY: total=N consistent=N inconsistent=N byteEq=N byteDiff=N ===`
⇒ **退出码** 0 = 全部一致 · 1 = 有类型不一致 · 2 = 环境未就绪

⚠️ **为什么要运行时而不是静态**：Jackson 对 record 的默认序是**框架行为**
（`MapperFeature.SORT_CREATOR_PROPERTIES_FIRST` + 构造参数序）⇒ 源码上看不出来 ⇒ 只有跑真 Jackson 才算证据（AGENTS.md §3.3）。
⚠️ **「字节一致」≠「注解该删」** —— 字节一致只证明「**今天**删不改字节」；
注解在 C 侧的真正作用是**把顺序显式钉死**（record 组件被重排时，无注解的字节序会**静默漂移**）
⇒ ⇒ 本仓**保留**注解；与 follow 域变异 M1「把注解改成**反序**」（而非「删注解」）的既有口径一致。

### `check-arch-invariants.py` 用法

```bash
python3 tools/difftest/check-arch-invariants.py                # 默认 --contract B（RAG-V1 形态）
python3 tools/difftest/check-arch-invariants.py --contract A    # api/rpc 分离形态（A1/A2 只在此形态下才生效；A3 两形态都有判据）
python3 tools/difftest/check-arch-invariants.py --self-test     # 红灯自检：造违规假树，逐条验证判据会红
python3 tools/difftest/check-arch-invariants.py --only A4 A11   # 只跑指定断言
```

**四种判定状态**（别把「没判」当成「过了」）：

| 状态 | 含义 | 计入退出码 |
|---|---|---|
| `PASS` | 判据成立 | 否 |
| `FAIL` | 判据被违反 —— **漂移** | **是**（`EXIT=1`） |
| `N/A` | 本契约形态下不适用（如契约 B 下的 A1/A2） | 否 |
| `PENDING` | 判据对象**仍是空壳** ⇒ 判据无从施加 | 否，但**必须显式报出** |

⚠️ **`PENDING` 不等于 `PASS`** —— 一个还没写实现的类既不是「过了」也不是「违规」。
落地实现后自动转 `PASS` 或 `FAIL`。


## 退出码约定

- `EXIT=0` —— 全绿
- `EXIT=1` —— 有漂移

## 实现要点（写脚本前先读）

1. **不要用 `mvn dependency:tree`** —— 多模块仓库里每个模块都跑一遍太慢。
   ⇒ **直接解析 POM 文本**（读 `<artifactId>spring-boot-starter-web</artifactId>` 是否出现在 `*-rpc` 的 pom 里），零依赖、毫秒级。
2. **断言必须能红** —— A4 要在 `article-rpc` 的两条哑弹上**红一次**。
   ⇒ 没有会红的断言等于没有断言。
3. **例外必须写在脚本里并附理由** —— 口头豁免不算。
4. **断言绑定契约** —— A1/A2 只适用于 api/rpc 分离形态；合并形态下**不得**把它们改成红灯。⚠️ **A3 是例外**（2026-10-26 · D9）：合并形态下**改写**为「`common/` 有挂载点 ＋ 域内 0 重复」⇒ 契约 B 下它是 **PENDING**（空壳）而非 N/A。
   ⇒ 脚本需支持 `--contract A|B`。
5. **对拍脚本必须显式排除 L3 降级项** —— 否则文案差异导致全红 → 被误判为回归 → 触发「修回旧行为」→ 降级被静默撤销。
   ⇒ 排除清单 → [`../../docs/L3-降级清单.md`](../../docs/L3-降级清单.md)
