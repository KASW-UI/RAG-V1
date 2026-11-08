#!/usr/bin/env python3
"""**台账状态表复算器** —— 把「审核项 / 状态」表里的状态**按行机器复算**。

> **为什么需要它（AGENTS.md §六「状态表纪律」＋ P-08）**：
> 「PASS / INTENTIONALLY_CHANGED / NOT_VERIFIED / FAIL 各多少条」这种汇总数字，
> **手数必然漂**（本仓已发生过多次：手数 24 ⇒ 机器复算当场纠正；2026-11-09 又抓到
> `article` 域「汇总 77 / 明细 76」的口径混用）。
> ⇒ ⇒ 本脚本把「按行复算」变成**一条命令** ⇒ ⇒ 任何域的台账改完都能立刻复算。

用法：
    python3 tools/recount-ledger-status.py <ledger.md> [--header 关键词]... [--expect-total N]
                                     [--table-line N]... [--state-col N]
                                     [--compare-summary] [--json] [--list-tables]

退出码：
    0 = 复算完成，且（若给了 --expect-total）与自报总数一致、且没有未知状态、且汇总对得上
    1 = 有未知状态（表里出现了词表外的状态词）**或** 与自报汇总表对不上
    2 = 找不到状态表 / 文件不存在

⚠️ **口径**：**只数表里的行**，不看正文里散落的结论文字 ⇒ ⇒ 与「自报总数」必须一致。

---

## 🔴 2026-11-09 重写（为什么旧版会「腐烂」）

旧版按 **「状态在第 2 列」** 定位状态列 ⇒ ⇒ **对本仓现行的台账形状全部失配**
⇒ 实测：连 `common` 台账都报「没找到状态表」⇒ ⇒ **一个**用来防手数的工具自己失效了。

**根因**：各域台账的状态列**列名各不相同** —— `common` §11.2 叫「状态」（index 4）·
`article` §11.2 叫「**验证**」（index 7）· `comment` §二 叫「状态」（index 5）·
`task`/`like`/`message` 又各有自己的形状 ⇒ ⇒ **按「列名 + 位置」定位必然脆**。

⇒ ⇒ **新口径：按【内容】定位状态列** —— 对每张表的每一列，统计该列里有多少个单元格
**含状态词**；命中数最多的那一列即状态列 ⇒ ⇒ **与列名、位置、表的形状全部无关**。

⇒ ⇒ 配套新增 **`--compare-summary`**：自动认出「**状态 | 数量**」形状的**自报汇总表**
（状态词在**第 1 列**且**第 2 列是纯数字**）⇒ ⇒ 把它与明细复算结果**逐状态对照**
⇒ ⇒ **「汇总表与明细表不一致」从此可被机器抓出**（这正是 2026-11-09 在
`article` 域抓到的那类矛盾：明细行已标「✅ 已收口」，汇总表仍写「未闭合」）。

### 🔴 2026-11-09 第二轮修正（同源的两处）

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| ① | `article` 承接表被判定「状态列 = #6（实现）」⇒ 复算出来的是 `✅/🟡/⛔` 分布，不是 `PASS/NOT_VERIFIED` 分布 | **两列都像状态列**（`实现` = ✅/🟡/⛔ · `验证` = PASS/IC/NV）⇒ **纯命中数无法区分** | 状态列选择改为 **三级判据**：① **列名语义**（`验证/状态/判定/结论/结果`）优先 ② **状态种类数**（能同时出现 PASS + NOT_VERIFIED + IC 的列才有信息量）③ **命中数** ⇒ ⇒ 并留 `--state-col N` 人工兜底 |
| ② | 行内若有**空单元格**，该行的状态列会**左移** ⇒ 分类错位 | 切行时把**所有**空单元格都删了（`split_row`）⇒ 内部空位被抹平 | `split_row` 改为**只削首尾空位**、**保留中间空位** ⇒ 列下标恒定 |

⚠️ **「内容定位」是主判据，「列名语义」只是并列状态列之间的破平手** ——
列名**不是必需条件**，只是在「两列都是状态列」时用来挑更贴切的那一列。

### 🔴 2026-11-09 第三轮修正（汇总表对不上 ⇒ 不一定是台账错）

**症状**：对一篇台账跑 `--compare-summary`（不带 `--header`）⇒ 报「汇总≠明细」。

**根因**：一篇台账里往往有**多张**带状态列的表（承接表 + 漂移清单 + 闸门表 + 变异表 …）
⇒ 旧实现把**所有表的状态混算成一个数**再跟汇总表比 ⇒ ⇒ **「实算」被撑大 ⇒ 必报不一致**
⇒ ⇒ **这是工具的假警报，不是台账的矛盾**（实测：article 台账全局跑报「PASS 实算 133」，
   而承接表真值只有 79 —— 多出来的 54 行来自漂移表 / F 表 / 闸门表）。

**修法**：
1. **按表头签名分组** —— 同一张逻辑表被拆成多块（如 article 承接表被拆成 3 块，中间块只有 1 行）
   ⇒ 按表头签名归为**一组**，行数求和。
2. **汇总表只与「行数相等」的那一组对照** —— 汇总表自报的行数就是它描述的那张表的口径
   ⇒ ⇒ 行数对不上 ⇒ **明确报「口径不同 ⇒ 跳过」**，而不是误报「不一致」。

⚠️ **因此**：`--compare-summary` 只会对**能确定对应关系**的汇总表下结论；
口径不同的（例如 comment 台账的「253 守门点」不是本文档任何一张表的行数）
⇒ 工具会明说「跳过」，需要人工核对。
"""
import argparse
import json
import os
import re
import sys

# 状态词表：**顺序即优先级**（先判 FAIL，再判 INTENTIONALLY_CHANGED……）
# ⚠️ 为什么先判 FAIL：状态列常写成「FAIL（跨域 · 共享层）」，也常同时带 PASS 字样
#    （例如「**PASS**（C 侧单侧）」与「FAIL（跨域）」并存）⇒ 必须按**最严重**的归类。
STATUS_RULES = [
    ("FAIL", re.compile(r"FAIL")),
    ("INTENTIONALLY_CHANGED", re.compile(r"INTENTIONALLY_CHANGED")),
    ("NOT_VERIFIED", re.compile(r"NOT_VERIFIED")),
    ("NOT_IMPLEMENTED", re.compile(r"NOT_IMPLEMENTED")),
    ("PARTIAL", re.compile(r"PARTIAL")),
    ("PASS", re.compile(r"PASS")),
    # 符号形态：**✅ / ❌ / ⛔ / ⚠️ / 🟡** 是本仓台账的等价写法
    # ⚠️ 优先级：⛔(有意不建) > ❌(FAIL) > ⚠️/🟡(PARTIAL) > ✅(PASS)
    ("NOT_IMPLEMENTED", re.compile(r"⛔")),
    ("FAIL", re.compile(r"❌")),
    ("PARTIAL", re.compile(r"⚠️|🟡")),
    ("PASS", re.compile(r"✅")),
]

# 汇总表里可能出现、但**不算承接状态**的词（避免把说明文字误判成状态）
# ⚠️ **2026-11-09 修**：原式含裸「共」⇒ **误伤「共·享层」**（本仓大量行文含「共享层」）
#    ⇒ 裸「共」只在**后接数字**时才算命中（「共 253」）；「合计 / 小计 / 总计」不限。
_SUMMARY_ONLY = re.compile(r"合计|小计|总计|共\s*\d")

# **状态列的列名语义词** —— 只在「两列都像状态列」时用来破平手（不是硬依赖）
_STATE_HEADER_HINT = re.compile(r"验证|状态|判定|结论|结果|verdict|status", re.IGNORECASE)

# 汇总表的「自报合计」只由**词表内的状态**累加（`N_A_TO_C` / `E-4 证据类` 这类**不是行数**的项要排除）
_KNOWN_STATUS = {name for name, _ in STATUS_RULES}

# **显式大写状态名** —— 必须**优先于符号形态**：
# ⚠️ 2026-11-09 实测踩到：`⛔ **N_A_TO_C**（…）` 本是一个**独立状态**，
#    却被符号规则归成 `NOT_IMPLEMENTED` ⇒ 汇总表的「自报合计」被算成 255（真值 237）。
_STATUS_TOKEN = re.compile(r"\b[A-Z][A-Z0-9_]{2,}\b")
_EXTRA_STATUS_NAMES = {"BLOCKED", "N_A_TO_C", "FRAMEWORK_DIFFERENCE",
                       "COVERED", "COVERED_BY_EQUIVALENT", "NOT_COVERED"}


def _is_status_token(tok):
    """判断一个大写 token 是不是**状态名**（而不是 `HTTP` / `JSON` / `SQL` 这类普通缩写）。"""
    if tok in _KNOWN_STATUS or tok in _EXTRA_STATUS_NAMES:
        return True
    # 含下划线的**全大写复合词**按状态名处理（如 `COVERED_BY_EQUIVALENT`）
    return "_" in tok and tok.isupper()


def split_row(line):
    """切表格行 ⇒ 单元格列表。

    ⚠️ **只削首尾空位、保留中间空位** —— 否则行内若有空单元格，
    该行的状态列会**左移** ⇒ 分类错位（2026-11-09 实测踩到）。
    """
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    start, end = 0, len(cells)
    while start < end and cells[start] == "":
        start += 1
    while end > start and cells[end - 1] == "":
        end -= 1
    return cells[start:end]


def is_separator(cells):
    return bool(cells) and set("".join(cells)) <= set("-: ")


def classify(cell):
    """把一个状态单元格归到词表里的某一档 ⇒ 命中返回状态名，未命中返回 None。"""
    if _SUMMARY_ONLY.search(cell):
        return None
    # ① **显式大写状态名优先**（`N_A_TO_C` / `BLOCKED` / `FRAMEWORK_DIFFERENCE` …）
    toks = [t for t in _STATUS_TOKEN.findall(cell) if _is_status_token(t)]
    known = [t for t in toks if t in _KNOWN_STATUS]
    if known:
        # 多个已知状态并存时，仍按 STATUS_RULES 的**严重度顺序**取最严重的
        for name, _ in STATUS_RULES:
            if name in known:
                return name
    elif toks:
        return toks[0]
    # ② 关键词 / 符号形态
    for name, rule in STATUS_RULES:
        if rule.search(cell):
            return name
    return None


def parse_tables(lines):
    """把 markdown 切成表：返回 [{'header': [...], 'rows': [[...]], 'start': 行号}]。"""
    tables = []
    i = 0
    n = len(lines)
    while i < n:
        if not lines[i].startswith("|"):
            i += 1
            continue
        start = i
        block = []
        while i < n and lines[i].startswith("|"):
            block.append(lines[i])
            i += 1
        if len(block) < 2:
            continue
        header = split_row(block[0])
        rows = []
        for line in block[1:]:
            cells = split_row(line)
            if is_separator(cells):
                continue
            rows.append(cells)
        if rows:
            tables.append({"header": header, "rows": rows, "start": start + 1})
    return tables


def detect_state_col(table):
    """**按内容**定位状态列 ⇒ 返回列下标；定位不到返回 None。

    判据（三级）：
    ① **列名语义**（`验证/状态/判定/结论/结果`）—— 只在**多列都像状态列**时用来破平手
    ② **状态种类数** —— 能同时出现 PASS + NOT_VERIFIED + IC 的列才有信息量
    ③ **命中数**

    ⚠️ **必须排除「汇总表」**：汇总表的状态词在第 1 列且第 2 列是纯数字
       ⇒ 那种表不是「明细表」，本函数对它返回 None（见 detect_summary_table）。
    """
    header = table["header"]
    rows = table["rows"]
    if detect_summary_table(table) is not None:
        return None
    cands = []
    for idx in range(len(header)):
        hits = 0
        cats = set()
        for cells in rows:
            if idx < len(cells):
                name = classify(cells[idx])
                if name is not None:
                    hits += 1
                    cats.add(name)
        # ⚠️ **最低门槛**：命中数太少 ⇒ 多半是误判（如某列恰好含一个 ✅）
        #    ⇒ **大表**（>3 行）要求 ≥3 个命中；**小表**（≤3 行）只需 ≥1
        #    （2026-11-09 实测：article 承接表被拆成 3 块，中间块**只有 1 行**
        #      ⇒ 旧的「≥3」门槛把整块丢掉 ⇒ 合计少算 1 行 ⇒ 82 ≠ 83）
        if hits >= max(1, len(rows) * 0.5) and (len(rows) <= 3 or hits >= 3):
            cands.append((idx, hits, cats))
    if not cands:
        return None
    cands.sort(key=lambda c: (
        0 if _STATE_HEADER_HINT.search(header[c[0]]) else 1,   # ① 列名语义
        -len(c[2]),                                            # ② 状态种类数
        -c[1],                                                 # ③ 命中数
        c[0],
    ))
    return cands[0][0]


def detect_summary_table(table):
    """认出「状态 | 数量」形状的**自报汇总表** ⇒ 返回 {状态: 数量} 或 None。"""
    header = table["header"]
    if len(header) < 2:
        return None
    if "状态" not in header[0]:
        return None
    counts = {}
    for cells in table["rows"]:
        if len(cells) < 2:
            continue
        name = classify(cells[0])
        if name is None:
            continue
        m = re.search(r"\d+", cells[1])
        if not m:
            continue
        counts[name] = counts.get(name, 0) + int(m.group(0))
    return counts or None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ledger")
    ap.add_argument("--header", action="append", default=None,
                    help="表头里用来定位**明细表**的关键词（可重复；不给 = 全部明细表）")
    ap.add_argument("--table-line", action="append", type=int, default=None,
                    help="只数**起始行号**为该值的表（可重复；用于锁定同一张逻辑表被拆成多块的情况）")
    ap.add_argument("--state-col", type=int, default=None,
                    help="**人工指定**状态列下标（0 基）；不给 = 自动按内容定位")
    ap.add_argument("--expect-total", type=int, default=None, help="台账自报的总数（给了就核对）")
    ap.add_argument("--compare-summary", action="store_true",
                    help="与台账里的**自报汇总表**逐状态对照（不一致 ⇒ EXIT=1）")
    ap.add_argument("--json", action="store_true", help="输出 JSON（供守门脚本消费）")
    ap.add_argument("--list-tables", action="store_true", help="只列出识别到的表（诊断用）")
    args = ap.parse_args()

    try:
        with open(args.ledger, encoding="utf-8") as fh:
            lines = fh.read().split("\n")
    except OSError as e:
        print("!! 读不到文件：%s" % e)
        return 2

    targeted = bool(args.header or args.table_line)

    tables = parse_tables(lines)
    detail_tables, summaries = [], []
    for t in tables:
        s = detect_summary_table(t)
        if s is not None:
            summaries.append((t, s))
            continue
        idx = args.state_col if args.state_col is not None else detect_state_col(t)
        if idx is None:
            continue
        if args.table_line and t["start"] not in args.table_line:
            continue
        if args.header:
            if not any(h in t["header"][0] or any(h in c for c in t["header"]) for h in args.header):
                continue
        detail_tables.append((t, idx))

    if args.list_tables:
        print("—— 识别到的表 ——")
        for t, s in summaries:
            print("  [汇总表] 行 %d · %s" % (t["start"], t["header"][:60]))
        for t, idx in detail_tables:
            print("  [明细表] 行 %d · 状态列=#%d · %s" % (t["start"], idx, t["header"][:60]))
        return 0

    if not detail_tables:
        print("!! 没找到明细状态表（%s）" % (
            "表头需含 %r" % args.header if args.header else "没有任何表的状态列命中率过半"))
        return 2
    if not targeted and not args.json:
        print("   ℹ️ **全局跑（仅诊断）** —— 一篇台账里有多张状态表（承接表 / 漂移清单 / 闸门表 …）"
              "⇒ 计数是**混算** ⇒ 请用 `--header` 或 `--table-line` 锁定目标表再取读数")

    counts = {}
    unknown = []
    total = 0
    per_table = []
    for t, idx in detail_tables:
        tc = {}
        tn = 0
        for cells in t["rows"]:
            if idx >= len(cells):
                continue
            status = cells[idx]
            total += 1
            tn += 1
            name = classify(status)
            if name is None:
                unknown.append((cells[0] if cells else "?", status))
            else:
                counts[name] = counts.get(name, 0) + 1
                tc[name] = tc.get(name, 0) + 1
        per_table.append({"line": t["start"], "rows": tn, "state_col": idx, "counts": tc})

    # ── 与自报汇总表对照 ──
    # ⚠️ **只与「行数相等」的明细组对照** —— 汇总表自报的行数 = 它描述的那张表的口径
    #    ⇒ 行数对不上 ⇒ **口径不同 ⇒ 跳过**（不是台账矛盾）
    groups = {}
    for t, idx in detail_tables:
        key = tuple(t["header"])
        g = groups.setdefault(key, {"rows": 0, "counts": {}, "lines": []})
        g["lines"].append(t["start"])
        for cells in t["rows"]:
            if idx >= len(cells):
                continue
            g["rows"] += 1
            name = classify(cells[idx])
            if name is not None:
                g["counts"][name] = g["counts"].get(name, 0) + 1

    drift = []
    unmatched = []
    for t, s in summaries:
        implied = sum(v for k, v in s.items() if k in _KNOWN_STATUS)
        cands = [g for g in groups.values() if g["rows"] == implied]
        if not cands:
            unmatched.append((t["start"], implied, s))
            continue
        actual = {}
        for g in cands:
            for k, v in g["counts"].items():
                actual[k] = actual.get(k, 0) + v
        for name, reported in s.items():
            if name not in _KNOWN_STATUS:
                continue
            got = actual.get(name, 0)
            if got != reported:
                drift.append((t["start"], name, reported, got))

    result = {
        "ledger": args.ledger,
        "detail_tables": len(detail_tables),
        "total": total,
        "counts": counts,
        "unknown": unknown,
        "per_table": per_table,
        "summary_tables": [{"line": t["start"], "counts": s} for t, s in summaries],
        "drift": [{"line": l, "status": n, "reported": r, "actual": a} for l, n, r, a in drift],
        "unmatched_summaries": [{"line": l, "implied_total": i, "counts": s} for l, i, s in unmatched],
        "groups": [{"rows": g["rows"], "lines": g["lines"], "counts": g["counts"]} for g in groups.values()],
    }

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("—— 状态表复算（%s · %d 张明细表）——" % (args.ledger, len(detail_tables)))
        # ⚠️ **完整普查**（含 0 值）—— 台账引用「机器输出逐字」时，
        #    缺一个 0 会让人以为「没这个状态」而不是「这个状态是 0」
        seen = set()
        for name, _ in STATUS_RULES:
            if name in seen:
                continue
            seen.add(name)
            print("   %-22s %d" % (name, counts.get(name, 0)))
        print("   %-22s %d" % ("合计", total))
        if unknown:
            print("   ⚠️ 词表外的状态 %d 条（**必须先修表**）：" % len(unknown))
            for item, status in unknown:
                print("      · %s ⇒ %s" % (item[:60], status[:60]))
        for t, s in summaries:
            print("   📋 自报汇总表（行 %d）：%s" % (t["start"], s))
        for l, n, r, a in drift:
            print("   ❌ 汇总≠明细：%s 自报 %d · 实算 %d（行 %d）" % (n, r, a, l))
        for l, implied, s in unmatched:
            print("   ⚠️ 汇总表（行 %d · 自报合计 %d）在本文档里**找不到行数相同的明细表** ⇒ 口径不同 ⇒ **跳过**（不是台账矛盾）" % (l, implied))

    # ⚠️ **严格度分档**：**定向跑**（--header / --table-line）⇒ 词表外状态 = **失败**
    #    （目标表的计数不可信 ⇒ 必须先修表）；**全局跑** ⇒ 仅诊断（其它表列形状各异，
    #    必然出现「不像状态」的单元格）⇒ **不判失败**，只提示
    if unknown:
        if targeted:
            return 1
        if not args.json:
            print("   ⚠️ 词表外状态 %d 条来自**其它表**（全局跑仅诊断）⇒ **不判失败**；请用 `--header` / `--table-line` 锁定目标表再判" % len(unknown))
    if drift:
        return 1
    if args.expect_total is not None and args.expect_total != total:
        print("   ❌ 自报总数 %d ≠ 复算 %d" % (args.expect_total, total))
        return 1
    if args.expect_total is not None:
        print("   ✅ 自报总数与复算一致（%d）" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
