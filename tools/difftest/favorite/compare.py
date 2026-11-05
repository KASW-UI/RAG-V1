#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""favorite 域 · **三方**活体对拍比对器（Go 冻结字节 ↔ B 侧活体 ↔ C 侧活体）。

**三方是哪三方**

```
        ┌──────────── 第三方锚 ────────────┐   ┌──── B 侧活体 ────┐   ┌──── C 侧活体 ────┐
        │ expected.txt = Go 真源冻结字节    │   │ favorite-api     │   │ favorite-service │
        │ （B 侧仓 2026-10-30 录的 Go 值）   │   │ :2xxxx           │   │ :2xxxx           │
        └───────────────────────────────────┘   └──────────────────┘   └──────────────────┘
                     │                                   │                       │
                     └────────── 逐 case · 逐字段 ──────┴───────────────────────┘
```

**判定档位**（沿用 B 侧探针的五档语义，但判定对象从「Go ↔ RAG」扩成「三方」）：

| 档 | 含义 | 本脚本的判定 |
|---|---|---|
| `SAME` | status + Content-Type + body **全等** | 三方**整串**全等 |
| `SAME_CODE` | status 全等 **且** 业务码全等 | 三方 status 全等 **且** 三方 biz 全等（body 形态允许不同） |
| `SAME_BODY` | status 全等 **且** body 全等（**Content-Type 允许不同**） | 三方 status 全等 **且** 三方 body 全等 |
| `SAME_STATUS` | **只**要求 status 全等 | 三方 status 全等 |
| `OBS` | 两侧**必须不同** | B 侧 ≠ C 侧（当前 0 条：h01 已于 2026-10-30 改档 `SAME`） |
| `SKIP` | 显式跳过（理由写在 expected.txt 的注释里） | 跳过 |

**为什么三方都要比**：只比「B ↔ C」只能证明两侧一致，不能证明**谁对**；
加上 Go 冻结字节这一锚 ⇒ 才能证明「**C 侧的每个字节 == 真源的每个字节**」。

**为什么还要单独报告「B 侧 vs 冻结值」**：它是**基线** ——
若 B 侧已经与冻结值不符 ⇒ 说明「Go 真源变了」或「B 侧回归了」
⇒ ⇒ 此时 C↔B 的一致**没有意义** ⇒ 必须先查 B 侧。

退出码：0 = 全部一致；1 = 有差异。
"""

import sys
import os


def load(path):
    out = {}
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line.strip():
            continue
        parts = line.split("\t", 1)
        out[parts[0]] = parts[1] if len(parts) > 1 else ""
    return out


def fields(v):
    """把渲染串拆成字段：status / ct / body / biz / hkeys。"""
    out = {}
    for part in v.split("|"):
        if "=" in part:
            k, val = part.split("=", 1)
            out[k] = val
    return out


def main():
    if len(sys.argv) != 4:
        sys.stderr.write("usage: compare.py <expected.txt> <b-live.txt> <c-live.txt>\n")
        return 2
    exp_path, b_path, c_path = sys.argv[1], sys.argv[2], sys.argv[3]

    kinds, frozen = {}, {}
    for line in open(exp_path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        cols = line.split("\t", 2)
        if len(cols) < 3:
            print(f"❌ expected.txt 行格式错误：{line}")
            return 1
        kinds[cols[0]] = cols[1]
        frozen[cols[0]] = cols[2]

    b = load(b_path)
    c = load(c_path)

    problems = []
    counts = {"SAME": 0, "SAME_CODE": 0, "SAME_BODY": 0, "SAME_STATUS": 0, "OBS": 0, "SKIP": 0}
    baseline_drift = []

    for cid, kind in kinds.items():
        fv, bv, cv = frozen.get(cid), b.get(cid), c.get(cid)
        if fv is None or bv is None or cv is None:
            missing = []
            if fv is None:
                missing.append("冻结值")
            if bv is None:
                missing.append("B 侧")
            if cv is None:
                missing.append("C 侧")
            problems.append(f"  · {cid} —— 缺：{', '.join(missing)}")
            continue
        if kind == "SKIP":
            counts["SKIP"] += 1
            continue

        ff, bf, cf = fields(fv), fields(bv), fields(cv)

        # ── ① 基线：B 侧 vs 冻结值（Go 真源字节）—— 不符 ⇒ 先查 B 侧 / Go ──
        if kind == "SAME":
            baseline_ok = (bv == fv)
        elif kind == "SAME_CODE":
            baseline_ok = (ff.get("status") == bf.get("status") and ff.get("biz") == bf.get("biz"))
        elif kind == "SAME_BODY":
            baseline_ok = (ff.get("status") == bf.get("status") and ff.get("body") == bf.get("body"))
        elif kind == "SAME_STATUS":
            baseline_ok = (ff.get("status") == bf.get("status"))
        else:
            baseline_ok = True
        if not baseline_ok:
            baseline_drift.append(cid)

        counts[kind] = counts.get(kind, 0) + 1

        # ── ② 主判定：三方按档位比 ──
        if kind == "SAME":
            if not (bv == fv and cv == fv):
                problems.append(
                    f"  · {cid} —— 三方未全等\n"
                    f"      冻结(Go): {fv}\n"
                    f"      B 侧     : {bv}\n"
                    f"      C 侧     : {cv}")
        elif kind == "SAME_CODE":
            if not (ff.get("status") == bf.get("status") == cf.get("status")
                    and ff.get("biz") == bf.get("biz") == cf.get("biz")):
                problems.append(
                    f"  · {cid} —— status 或业务码不一致\n"
                    f"      冻结(Go): {fv}\n"
                    f"      B 侧     : {bv}\n"
                    f"      C 侧     : {cv}")
        elif kind == "SAME_BODY":
            if not (ff.get("status") == bf.get("status") == cf.get("status")
                    and ff.get("body") == bf.get("body") == cf.get("body")):
                problems.append(
                    f"  · {cid} —— status 或 body 不一致（Content-Type 允许不同）\n"
                    f"      冻结(Go): {fv}\n"
                    f"      B 侧     : {bv}\n"
                    f"      C 侧     : {cv}")
        elif kind == "SAME_STATUS":
            if not (ff.get("status") == bf.get("status") == cf.get("status")):
                problems.append(
                    f"  · {cid} —— HTTP 状态码不一致\n"
                    f"      冻结(Go): {fv}\n"
                    f"      B 侧     : {bv}\n"
                    f"      C 侧     : {cv}")
        elif kind == "OBS":
            if bv == cv:
                problems.append(
                    f"  · {cid} —— OBS 段期望两侧不同，但变成一样了 ⇒ 请重评台账")
        else:
            problems.append(f"  · {cid} —— 未知的档位「{kind}」")

    print(f"  SAME {counts['SAME']} 条 / SAME_CODE {counts['SAME_CODE']} 条 / "
          f"SAME_BODY {counts['SAME_BODY']} 条 / SAME_STATUS {counts['SAME_STATUS']} 条 / "
          f"OBS {counts['OBS']} 条 / SKIP {counts['SKIP']} 条（共 {len(kinds)} 个 case）")

    if baseline_drift:
        print(f"\n  ⚠️ 基线告警：B 侧与 Go 冻结值不符的 case = {baseline_drift}")
        print("     ⇒ 说明 **Go 真源的 api 契约变了** 或 **B 侧自身回归了**")
        print("     ⇒ 此时 C↔B 的一致没有意义 ⇒ 请先查 B 侧")

    if problems:
        print("\n❌ 不一致：")
        print("\n".join(problems))
        return 1
    print("✅ 三方一致"
          "（SAME 全等；SAME_CODE 业务码等；SAME_BODY body 等；SAME_STATUS 仅状态码等；OBS 仍不同）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
