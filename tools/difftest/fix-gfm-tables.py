#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GFM 表格形状修复器 —— 只做**形状**修复，不碰任何断言内容。

三档修法（本脚本自动做的两档）：
  ② 分隔行格数 ≠ 表头格数  ⇒ 重建分隔行，格数对齐表头（**单元格内容全丢，只留连字符**）
  ④ inline code 内裸竖线    ⇒ 转义成 `\\|`（GFM 会把它还原成 `|` ⇒ 显示不变）

⚠️ 本脚本**不碰**这两类（必须人工判断，脚本一律跳过并在报告里点名）：
  ① 缺分隔行  —— 要判断「这一块到底是不是表」
  ③ 数据行格数与表头不符 —— 要判断「该补格还是该并格」

用法：
  python3 tools/difftest/fix-gfm-tables.py --dry-run     # 只报告
  python3 tools/difftest/fix-gfm-tables.py --apply        # 落盘
"""

from __future__ import annotations

import argparse
import importlib.util
import pathlib
import re
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location("gfm", HERE / "check-gfm-tables.py")
gfm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gfm)

SKIP_PREFIX = ("Book/", "速查表/", ".backup/", ".obsidian/")
SKIP_DIR_PARTS = ("node_modules", ".git", "target", ".jdtls-build", "Book", "速查表")


def list_files() -> list[str]:
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z", "*.md"],
                         capture_output=True).stdout.decode("utf-8")
    keep = []
    for f in out.split("\0"):
        if not f or f.startswith(SKIP_PREFIX):
            continue
        if any(p in SKIP_DIR_PARTS for p in pathlib.PurePosixPath(f).parts):
            continue
        keep.append(f)
    return sorted(keep)


def escape_code_pipes(line: str) -> tuple[str, int]:
    """把 inline code span 里的裸竖线转义成 `\\|`。返回 (新行, 转义处数)。"""
    out, last, count = [], 0, 0
    for m in gfm.CODE_SPAN.finditer(line):
        seg = m.group(0)
        if "|" not in seg or "\\|" in seg:
            continue
        out.append(line[last:m.start()])
        out.append(seg.replace("|", "\\|"))
        last = m.end()
        count += 1
    out.append(line[last:])
    return "".join(out), count


def rebuild_separator(orig: str, n_cells: int) -> str:
    """按目标格数重建分隔行；保留原有的对齐冒号（`:---:` / `---:` / `:---`）。"""
    cells = gfm.split_cells(orig)
    cells = [c.strip() for c in cells]
    if len(cells) > n_cells:
        cells = cells[:n_cells]
    while len(cells) < n_cells:
        cells.append("---")
    spaced = " " in orig.strip()
    if spaced:
        return "| " + " | ".join(cells) + " |"
    return "|" + "|".join(cells) + "|"


def fix_file(rel: str, apply: bool) -> list[str]:
    p = ROOT / rel
    lines = p.read_text(encoding="utf-8").split("\n")
    mask = gfm.fence_mask(lines)
    notes: list[str] = []
    i = 0
    while i < len(lines):
        if mask[i] or not lines[i].strip().startswith("|"):
            i += 1
            continue
        block = []
        while i < len(lines) and not mask[i] and lines[i].strip().startswith("|"):
            block.append(i)
            i += 1
        sep_pos = None
        for k, idx in enumerate(block):
            if gfm.is_delimiter_row(gfm.split_cells(lines[idx])):
                sep_pos = k
                break
        if sep_pos is None:
            notes.append(f"行 {block[0]+1}: ① 缺分隔行 ⇒ **跳过**（需人工判断）")
            continue
        sep_idx = block[sep_pos]
        sep_n = len(gfm.split_cells(lines[sep_idx]))
        hdr_n = len(gfm.split_cells(lines[block[sep_pos - 1]])) if sep_pos > 0 else sep_n
        if sep_n != hdr_n:
            new = rebuild_separator(lines[sep_idx], hdr_n)
            notes.append(f"行 {sep_idx+1}: ② 分隔行 {sep_n} 格 ⇒ 重建为 {hdr_n} 格")
            lines[sep_idx] = new
        for idx in block:
            new, n = escape_code_pipes(lines[idx])
            if n:
                notes.append(f"行 {idx+1}: ④ 转义 inline code 内裸竖线 ×{n}")
                lines[idx] = new
    if notes and apply:
        p.write_text("\n".join(lines), encoding="utf-8")
    return notes


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()
    apply = args.apply and not args.dry_run

    total = 0
    touched = 0
    for rel in list_files():
        notes = fix_file(rel, apply)
        if notes:
            touched += 1
            total += len(notes)
            print(f"\n{'🔧' if apply else '🔍'} {rel}")
            for n in notes:
                print(f"     {n}")
    print()
    print("=" * 68)
    print(f"{'已落盘' if apply else '预演'}：{touched} 份文件 / {total} 处形状修复")
    return 0


if __name__ == "__main__":
    sys.exit(main())
