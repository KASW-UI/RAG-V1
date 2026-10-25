# difftest —— 双侧对拍与架构符合性守门

> 断言的完整定义 → [`../../docs/架构符合性断言.md`](../../docs/架构符合性断言.md)

## 脚本

| 脚本 | 作用 | 状态 |
|---|---|---|
| `check-arch-invariants.py` | **架构符合性 A1–A12** —— 校验包结构、starter 化、生态优先、immutable DTO 等约定 | ✅ **2026-10-25 已实现**（`--self-test` 自证 12/12 条判据能红） |
| `check-proto-static.sh` | proto 契约对照（字段号 / message 名 / RPC 签名） | ⬜ 待实现 |
| `check-ddl-static.py` | DDL 列级对照（类型 / 长度 / 可空性 / DEFAULT / 索引） | ⬜ 待实现 |
| `run-parity.sh` | 双侧语义探针（Go ↔ RAG-V1 同请求矩阵） | ⬜ 待实现 |

### `check-arch-invariants.py` 用法

```bash
python3 tools/difftest/check-arch-invariants.py                # 默认 --contract B（RAG-V1 形态）
python3 tools/difftest/check-arch-invariants.py --contract A    # api/rpc 分离形态（A1/A2/A3 在此形态下才生效）
python3 tools/difftest/check-arch-invariants.py --self-test     # 红灯自检：造违规假树，逐条验证判据会红
python3 tools/difftest/check-arch-invariants.py --only A4 A11   # 只跑指定断言
```

**四种判定状态**（别把「没判」当成「过了」）：

| 状态 | 含义 | 计入退出码 |
|---|---|---|
| `PASS` | 判据成立 | 否 |
| `FAIL` | 判据被违反 —— **漂移** | **是**（`EXIT=1`） |
| `N/A` | 本契约形态下不适用（如契约 B 下的 A1/A2/A3） | 否 |
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
4. **断言绑定契约** —— A1/A2/A3 只适用于 api/rpc 分离形态；合并形态下**不得**把它们改成红灯。
   ⇒ 脚本需支持 `--contract A|B`。
5. **对拍脚本必须显式排除 L3 降级项** —— 否则文案差异导致全红 → 被误判为回归 → 触发「修回旧行为」→ 降级被静默撤销。
   ⇒ 排除清单 → [`../../docs/L3-降级清单.md`](../../docs/L3-降级清单.md)
