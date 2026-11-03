#!/usr/bin/env python3
"""**台账状态表复算器** —— 把「审核项 / 状态」表里的状态**按行机器复算**。

> **为什么需要它（AGENTS.md §六「状态表纪律」＋ P-08）**：
> 「PASS / INTENTIONALLY_CHANGED / NOT_VERIFIED / FAIL 各多少条」这种汇总数字，
> **手数必然漂**（本仓已发生过一次：手数 24 ⇒ 机器复算当场纠正）。
> ⇒ ⇒ 本脚本把「按行复算」变成**一条命令** ⇒ ⇒ 任何域的台账改完都能立刻复算。

用法：
    python3 tools/recount-ledger-status.py <ledger.md> [--header 关键词] [--expect-total N]

退出码：
    0 = 复算完成，且（若给了 --expect-total）与自报总数一致、且没有未知状态
    1 = 有未知状态（表里出现了词表外的状态词）⇒ **必须先修表**
    2 = 找不到状态表 / 文件不存在

⚠️ **口径**：**只数表里的行**，不看正文里散落的结论文字 ⇒ ⇒ 与「自报总数」必须一致。
"""
import argparse
import re
import sys

# 状态词表：**顺序即优先级**（先判 FAIL，再判 INTENTIONALLY_CHANGED……）
# ⚠️ 为什么先判 FAIL：状态列常写成「FAIL（跨域 · 共享层）」，也常同时带 PASS 字样
#    （例如「**PASS**（C 侧单侧）」与「FAIL（跨域）」并存）⇒ 必须按**最严重**的归类。
STATUS_RULES = [
    ("FAIL", re.compile(r"FAIL")),
    ("INTENTIONALLY_CHANGED", re.compile(r"INTENTIONALLY_CHANGED")),
    ("NOT_VERIFIED", re.compile(r"NOT_VERIFIED")),
    ("PASS", re.compile(r"PASS")),
]


def split_row(line):
    """切表格行 ⇒ 单元格列表（去掉首尾空单元格）。"""
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    return [c for c in cells if c != ""]


def find_table(lines, header_hint):
    """找状态表：表头行同时含「状态」与 header_hint ⇒ 返回 (起始行号, 结束行号)。"""
    start = None
    for i, line in enumerate(lines):
        if not line.startswith("|"):
            continue
        cells = split_row(line)
        if len(cells) >= 2 and "状态" in cells[1] and header_hint in line:
            start = i
            break
    if start is None:
        return None
    end = start
    for j in range(start + 1, len(lines)):
        if not lines[j].startswith("|"):
            break
        end = j
    return start, end


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ledger")
    ap.add_argument("--header", default="审核项", help="表头里用来定位这张表的关键词")
    ap.add_argument("--expect-total", type=int, default=None, help="台账自报的总数（给了就核对）")
    args = ap.parse_args()

    try:
        with open(args.ledger, encoding="utf-8") as fh:
            lines = fh.read().split("\n")
    except OSError as e:
        print("!! 读不到文件：%s" % e)
        return 2

    span = find_table(lines, args.header)
    if span is None:
        print("!! 没找到状态表（表头需含「状态」与 %r）" % args.header)
        return 2
    start, end = span

    counts = {}
    unknown = []
    total = 0
    for line in lines[start + 2:end + 1]:          # 跳过表头 + 分隔行
        cells = split_row(line)
        if len(cells) < 2:
            continue
        total += 1
        status = cells[1]
        for name, rule in STATUS_RULES:
            if rule.search(status):
                counts[name] = counts.get(name, 0) + 1
                break
        else:
            unknown.append((cells[0], status))

    print("—— 状态表复算（%s · 行 %d-%d）——" % (args.ledger, start + 1, end + 1))
    for name, _ in STATUS_RULES:
        print("   %-22s %d" % (name, counts.get(name, 0)))
    print("   %-22s %d" % ("合计", total))
    if unknown:
        print("   ⚠️ 词表外的状态 %d 条（**必须先修表**）：" % len(unknown))
        for item, status in unknown:
            print("      · %s ⇒ %s" % (item[:60], status[:60]))

    if args.expect_total is not None and args.expect_total != total:
        print("   ❌ 自报总数 %d ≠ 复算 %d" % (args.expect_total, total))
        return 1
    if args.expect_total is not None:
        print("   ✅ 自报总数与复算一致（%d）" % total)
    return 1 if unknown else 0


if __name__ == "__main__":
    sys.exit(main())
