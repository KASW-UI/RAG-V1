#!/usr/bin/env python3
"""对拍探针的**变异判定器** —— 读探针逐条明细，判「变异是否真的让目标转红」。

用途（`mutate-parity.sh` 调用）：
    变异 ⇒ 跑探针 ⇒ 拿到明细 ⇒ **要求**：
      ① **目标断言转红**：指定的那几条用例必须变成 `DIFF`；
      ② **对照断言仍绿**：另外指定的用例必须仍是 `SAME`
         （⇒ 证明「探针整体没崩」，红的是**被测的那一处**，不是「全红」）；
      ③ 可选 `--min-same N`：SAME 的条数不得少于 N（防止「几乎全红」也算过）。

⚠️ **为什么要这条纪律**（AGENTS §3.3）：「变异后变红」本身**不足以**证明判据有牙齿 ——
    若是**编译失败**导致全红，那也是红的 ⇒ 没有信息量。
    ⇒ 必须「**目标红 ＋ 对照绿**」同时成立。

usage:
    judge_report.py <report> --expect-diff a,b --expect-same c,d [--min-same N]
"""

from __future__ import annotations

import argparse
import re
import sys

CASE_RE = re.compile(r"^### (\S+)\s*$")
VERDICT_RE = re.compile(r"^  => (SAME|DIFF)")


def parse(path: str) -> dict[str, str]:
    verdicts: dict[str, str] = {}
    current: str | None = None
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            m = CASE_RE.match(line)
            if m:
                current = m.group(1)
                continue
            m = VERDICT_RE.match(line)
            if m and current:
                verdicts[current] = m.group(1)
                current = None
    return verdicts


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("report")
    ap.add_argument("--expect-diff", default="", help="必须变成 DIFF 的用例（逗号分隔）")
    ap.add_argument("--expect-same", default="", help="必须仍是 SAME 的用例（逗号分隔）")
    ap.add_argument("--min-same", type=int, default=1, help="SAME 条数下限")
    args = ap.parse_args()

    verdicts = parse(args.report)
    if not verdicts:
        print(f"!! 报告里没解析出任何用例判定（{args.report}）⇒ 探针可能整体崩了")
        return 1

    problems: list[str] = []
    for name in [n for n in args.expect_diff.split(",") if n]:
        got = verdicts.get(name)
        if got != "DIFF":
            problems.append(f"目标用例 {name} 期望 DIFF，实得 {got!r}")
    for name in [n for n in args.expect_same.split(",") if n]:
        got = verdicts.get(name)
        if got != "SAME":
            problems.append(f"对照用例 {name} 期望 SAME，实得 {got!r}")
    same_n = sum(1 for v in verdicts.values() if v == "SAME")
    diff_n = sum(1 for v in verdicts.values() if v == "DIFF")
    if same_n < args.min_same:
        problems.append(f"SAME 条数 {same_n} < 下限 {args.min_same}（⇒ 疑似「整体崩掉」而非「局部转红」）")

    if problems:
        print("!! 判据不成立：")
        for p in problems:
            print(f"   · {p}")
        print(f"   （SAME {same_n} 条 / DIFF {diff_n} 条）")
        return 1
    print(f"✓ 判据成立（SAME {same_n} 条 / DIFF {diff_n} 条）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
