#!/usr/bin/env python3
"""
gRPC 级可观测契约 · **三方**双侧活体对拍的比对器 —— favorite 域。

用法：
    python3 compare-grpc.py <expected.txt> <b.txt> <c.txt> [kinds-bc.txt]
    python3 compare-grpc.py --dump <expected.txt> <b.txt> <c.txt>

三方是谁：
    ┌── 第三方锚 ─────────────┐   ┌── B 侧活体 ──────────┐   ┌── C 侧活体 ──────────┐
    │ expected.txt            │   │ favorite-rpc         │   │ favorite-service     │
    │ = Go 真源的冻结渲染值    │   │ （Boot 4.0.8）       │   │ （Boot 3.5.11 · D1）  │
    │ （B 侧仓录 · 本仓只留副本）│   │ grpc :2xxxx          │   │ grpc :2xxxx          │
    └─────────────────────────┘   └──────────────────────┘   └──────────────────────┘

判定分**两段**（两段的基准不同，不能混）：
    ① **锚校验**（Go 冻结锚 ↔ B 侧活体）—— 按 expected.txt **自带**的 kind 列判：
         SAME      —— 两侧**逐字节相同**（code / desc / body / metadata key 集合）
         SAME_CODE —— 两侧 **status code 相同**（语义等价），description 措辞允许不同
         OBS       —— 两侧**必须不同**（已登记的框架/库层差异）⇒ 变成一样反而是回归
         SKIP      —— 显式跳过，理由必须写在 expected.txt 的注释里
       ⇒ 这一段回答的是：「**B 侧现在还等于 Go 真源吗**」。
    ② **BC 校验**（B 侧活体 ↔ C 侧活体）—— 按 kinds-bc.txt 的 kind 列判
       （kinds-bc.txt 是**本仓自撰**的「Java ↔ Java」档位表）。
       ⇒ 这一段回答的是：「**C 侧等于 B 侧吗**」。
       ⚠️ 两段的 kind **语义不同**：锚里的 OBS 是「Go ≠ Java（库级差异）」，
          而 kinds-bc.txt 里的 OBS 是「B ≠ C（已登记的 C 侧偏离）」—— 别混用。

退出码：0 = 全绿；1 = 有差异；2 = 用法/环境错。
"""

import os
import sys

FIELDS = ("code", "desc", "body", "rhdr", "rtrl")


def load(path):
    """读 ``case_id \\t value`` 两列文件 ⇒ {case_id: value}。"""
    out = {}
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t", 1)
        out[parts[0]] = parts[1] if len(parts) > 1 else ""
    return out


def load_kinds(path):
    """读 ``case_id \\t kind \\t value`` 三列文件 ⇒ {case_id: (kind, value)}。"""
    out = {}
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        cols = line.split("\t", 2)
        if len(cols) < 3:
            raise SystemExit("❌ %s 行格式错误（应为 case_id\\tkind\\tvalue）：%s" % (path, line))
        out[cols[0]] = (cols[1], cols[2])
    return out


def field(value, key):
    """取 ``key=value`` 形式里的 value（值本身可能含 '='，故只切第一个）。"""
    for part in value.split("|"):
        eq = part.find("=")
        if eq > 0 and part[:eq] == key:
            return part[eq + 1:]
    return None


def diff_fields(a, b):
    """逐字段列出两个 value 的差异（返回 [(field, a值, b值)]）。"""
    out = []
    for f in FIELDS:
        va = field(a, f)
        vb = field(b, f)
        if va != vb:
            out.append((f, va, vb))
    return out


def judge_pair(left_name, left, right_name, right, kind):
    """按 kind 判一对值 ⇒ (是否通过, 人类可读的失败原因列表)。"""
    problems = []
    if kind == "SKIP":
        return True, []
    if kind == "SAME":
        if left != right:
            for f, va, vb in diff_fields(left, right):
                problems.append("      %s 不同：%s=%s / %s=%s" % (f, left_name, va, right_name, vb))
            if not problems:
                problems.append("      整串不同：%s=%s / %s=%s" % (left_name, left, right_name, right))
        return (not problems), problems
    if kind == "SAME_CODE":
        lc = field(left, "code")
        rc = field(right, "code")
        if lc != rc:
            problems.append("      status code 不同：%s=%s / %s=%s" % (left_name, lc, right_name, rc))
        return (not problems), problems
    if kind == "OBS":
        if left == right:
            problems.append("      OBS 段期望两侧**不同**，但变成一样了 ⇒ 请重评台账")
        return (not problems), problems
    raise SystemExit("❌ 未知的 kind：%s" % kind)


def main():
    args = [a for a in sys.argv[1:] if a != "--dump"]
    dump = "--dump" in sys.argv[1:]
    if len(args) < 3:
        print("用法：compare-grpc.py [--dump] <expected.txt> <b.txt> <c.txt> [kinds-bc.txt]", file=sys.stderr)
        return 2

    exp_path, b_path, c_path = args[0], args[1], args[2]
    kinds_path = args[3] if len(args) > 3 else None

    anchor = load_kinds(exp_path)
    b = load(b_path)
    c = load(c_path)

    if not anchor:
        print("❌ 冻结锚读不到：%s" % exp_path)
        return 2

    if dump:
        print("  %-30s %-14s %-14s %s" % ("case", "B 侧 code", "C 侧 code", "B / C body"))
        for cid, (kind, frozen) in anchor.items():
            bv = b.get(cid, "<缺>")
            cv = c.get(cid, "<缺>")
            print("  %-30s %-14s %-14s" % (cid, field(bv, "code"), field(cv, "code")))
            print("      锚(%s): %s" % (kind, frozen))
            print("      B     : %s" % bv)
            print("      C     : %s" % cv)
        return 0

    # ══════════════════ ① 锚校验（Go 冻结锚 ↔ B 侧活体）══════════════════
    anchor_problems = []
    n_same = n_same_code = n_obs = n_skip = 0
    for cid, (kind, frozen) in anchor.items():
        bv = b.get(cid)
        if bv is None:
            anchor_problems.append("  · %s —— B 侧活体没跑这个 case" % cid)
            continue
        ok, probs = judge_pair("锚", frozen, "B", bv, kind)
        if kind == "SAME":
            n_same += 1
        elif kind == "SAME_CODE":
            n_same_code += 1
        elif kind == "OBS":
            n_obs += 1
        else:
            n_skip += 1
        if not ok:
            anchor_problems.append("  · %s —— 锚校验不通过（kind=%s）\n%s" % (cid, kind, "\n".join(probs)))

    print("  ① 锚校验（Go 冻结锚 ↔ B 侧活体）：SAME %d / SAME_CODE %d / OBS %d / SKIP %d（共 %d 个 case）"
          % (n_same, n_same_code, n_obs, n_skip, len(anchor)))

    # ══════════════════ ② BC 校验（B 侧活体 ↔ C 侧活体）══════════════════
    bc_problems = []
    m_same = m_same_code = m_obs = m_skip = 0
    if not kinds_path or not os.path.exists(kinds_path):
        # 🔴🔴 **缺档位表 ⇒ 判据不完整 ⇒ 必须报错退出，不能「只做①」然后返回 0**：
        #    那会让「BC 校验根本没跑」被读成「BC 校验通过」⇒ **假绿**
        #    （与「变异没生效却被当成判据恒绿」同族的坑）。
        print("❌ 缺「Java ↔ Java」档位表：%s ⇒ **BC 校验（真正的『C == B』那一段）根本没跑**"
              % (kinds_path or "<未指定>"))
        print("   ⇒ 不能把它读成「通过」⇒ 请补齐档位表（或确认这一段是否该跑）")
        return 2

    kinds_bc = load_kinds(kinds_path)
    for cid, (kind, _why) in kinds_bc.items():
        bv = b.get(cid)
        cv = c.get(cid)
        if bv is None or cv is None:
            bc_problems.append("  · %s —— 有一侧没跑这个 case（B=%s C=%s）"
                               % (cid, "有" if bv is not None else "无", "有" if cv is not None else "无"))
            continue
        ok, probs = judge_pair("B", bv, "C", cv, kind)
        if kind == "SAME":
            m_same += 1
        elif kind == "SAME_CODE":
            m_same_code += 1
        elif kind == "OBS":
            m_obs += 1
        else:
            m_skip += 1
        if not ok:
            bc_problems.append("  · %s —— BC 校验不通过（kind=%s）\n%s" % (cid, kind, "\n".join(probs)))

    print("  ② BC 校验（B 侧活体 ↔ C 侧活体）：SAME %d / SAME_CODE %d / OBS %d / SKIP %d（共 %d 个 case）"
          % (m_same, m_same_code, m_obs, m_skip, len(kinds_bc)))

    problems = anchor_problems + bc_problems
    if problems:
        print("❌ 不一致：")
        print("\n".join(problems))
        return 1
    print("✅ favorite 域 gRPC 级可观测契约三方一致")
    print("   （① Go 冻结锚 == B 侧活体；② B 侧活体 == C 侧活体）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
