#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GFM 表格形状守门 —— 判定「这份 markdown 里的表格，GitHub 上到底渲不渲染」。

> **为什么要有它（真实缺陷驱动，不是洁癖）**
>
> 2026-11-11 · `global` 收口域轮实测：`docs/Mapping_Table/` 全套主表
> **在 GitHub 上根本不渲染** —— 表头 4 格、**分隔行只有 3 格** ⇒ 按 GFM 规范
> （GFM §4.10 Example 200）整块被当普通段落 ⇒ **表内容全部塌成一行文本**。
> 而本仓既有的 `check-mapping-coverage.py` 校验的是**数据行**的列数（必须为 4），
> **不校验分隔行** ⇒ ⇒ 这个洞对现有守门**完全隐形**。
>
> ⚠️ **判据来源 = 实测渲染，不是推测** —— 用 `pandoc -f gfm -t html` 对照实验：
> `| A | B | C | D |` + `|---|---|---|` ⇒ 输出 `<p>…</p>`（**不是表**）；
> 换成 `|---|---|---|---|` ⇒ 输出 `<table>`。
>
> **四类缺陷（本工具逐类判）**
>
> | # | 缺陷 | GFM 后果 |
> |---|---|---|
> | ① | 表块**缺分隔行**（含：被空行切成几块后，续块没有分隔行） | 整块按普通段落渲染 |
> | ② | **分隔行格数 ≠ 表头格数** | 整块按普通段落渲染（GFM §4.10 Ex.200） |
> | ③ | **数据行格数 ≠ 表头格数** | 少的补空、多的**静默丢弃**（Ex.199） |
> | ④ | **inline code 内出现未转义的 `\|`** | 竖线被当列分隔符 ⇒ 该行多切一列（→ 落到 ③） |
>
> **④ 的修法约定**：转义成 `\|`（**code span 内部**也转义）⇒ 与 2026-11-11
> follow 域已落地并验证的写法一致（`docs/Mapping_Table/follow/follow.md` 18 张表全部渲染）。
>
> **用法**
>
> ```bash
> python3 tools/difftest/check-gfm-tables.py                     # 全仓（EXIT=0 才算通过）
> python3 tools/difftest/check-gfm-tables.py --file <路径>       # 只查某份
> python3 tools/difftest/check-gfm-tables.py --self-test         # 自证：造缺陷 ⇒ 必须能红
> ```
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

# ── 扫描范围：仓内所有 markdown（排除工具目录下的样例与外部资料）──────────────
SKIP_PREFIX = (
    "Book/",              # 外部书稿（非本仓技术文档）
    "速查表/",            # Obsidian 速查表（个人笔记）
    ".backup/",           # 历史存档
    ".obsidian/",
)
SKIP_DIR_PARTS = ("node_modules", ".git", "target", ".jdtls-build", "Book", "速查表")

# ── 形状判定 ───────────────────────────────────────────────────────────────
SEP_CELL = re.compile(r"^\s*:?-{1,}:?\s*$")
# inline code span（允许一个反引号或多个反引号成对）
CODE_SPAN = re.compile(r"(?<!`)(`+)(?!`)(.+?)(?<!`)\1(?!`)", re.DOTALL)


def fence_mask(lines: list[str]) -> list[bool]:
    """标出**代码围栏内**的行（``` 或 ~~~ 围起来的整块）。

    ⚠️ **这一步是必须的，不是锦上添花** —— 围栏里的内容**不是 markdown**：
      - `find … | xargs …` 这种 **shell 管道**行以 `|` 开头；
      - 依赖树 `|  \- org.springframework…` 这种**树形输出**行也以 `|` 开头。
    ⇒ 不认围栏 ⇒ 工具把它们当成「一行表格块」⇒ **假红**。
    ⇒ 而假红会**淹没真缺陷**（本工具首次跑就把 6 处围栏内容报成 ① 缺分隔行）。
    """
    mask = [False] * len(lines)
    fence: str | None = None
    for i, ln in enumerate(lines):
        s = ln.strip()
        if fence is None:
            if s.startswith("```") or s.startswith("~~~"):
                fence = s[:3]
                mask[i] = True
        else:
            mask[i] = True
            if s.startswith(fence):
                fence = None
    return mask


def split_cells(line: str) -> list[str]:
    """按 GFM 规则切一行的单元格。

    - 首尾的 `|` 是分隔符，去掉。
    - `\\|` 是**转义竖线**，不算列分隔符（但保留在单元格里，后面要判 inline code 里
      到底是裸竖线还是已转义竖线 —— 所以这里刻意保留反斜杠）。
    """
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|") and not s.endswith("\\|"):
        s = s[:-1]
    cells, cur, i = [], "", 0
    while i < len(s):
        ch = s[i]
        if ch == "\\" and i + 1 < len(s) and s[i + 1] == "|":
            cur += "\\|"      # 保留转义标记
            i += 2
            continue
        if ch == "|":
            cells.append(cur)
            cur = ""
            i += 1
            continue
        cur += ch
        i += 1
    cells.append(cur)
    return cells


def is_delimiter_row(cells: list[str]) -> bool:
    """分隔行 = 每个单元格只含连字符（可带首尾冒号）。"""
    return len(cells) > 0 and all(SEP_CELL.match(c) for c in cells)


def strip_code_spans(text: str) -> str:
    """把 inline code span 的内容挖空（保留其余文本）⇒ 用于「文本里有没有裸竖线」的判定。"""
    return CODE_SPAN.sub(lambda m: " " * len(m.group(0)), text)


def find_bare_pipes_in_code(line: str) -> list[str]:
    """找出 inline code span 里**未转义**的竖线（GFM 会把它当列分隔符）。"""
    hits = []
    for m in CODE_SPAN.finditer(line):
        seg = m.group(0)
        if "|" in seg and "\\|" not in seg:
            hits.append(seg.strip())
    return hits


def scan_text(path_rel: str, text: str) -> list[dict]:
    """扫一份 markdown，返回缺陷列表。每条 = 一个**表块**的一处形状问题。"""
    lines = text.split("\n")
    mask = fence_mask(lines)
    issues: list[dict] = []
    i = 0
    while i < len(lines):
        if mask[i] or not lines[i].strip().startswith("|"):
            i += 1
            continue
        block_start = i
        block: list[tuple[int, str]] = []
        while i < len(lines) and not mask[i] and lines[i].strip().startswith("|"):
            block.append((i + 1, lines[i]))
            i += 1

        sep_pos = None
        for k, (_num, row) in enumerate(block):
            if is_delimiter_row(split_cells(row)):
                sep_pos = k
                break

        if sep_pos is None:
            issues.append({
                "file": path_rel,
                "line": block[0][0],
                "kind": "① 缺分隔行",
                "detail": f"整块 {len(block)} 行不被 GFM 识别为表格（渲染成普通段落）",
            })
            continue

        sep_n = len(split_cells(block[sep_pos][1]))
        header_n = len(split_cells(block[sep_pos - 1][1])) if sep_pos > 0 else sep_n

        if header_n != sep_n:
            issues.append({
                "file": path_rel,
                "line": block[sep_pos][0],
                "kind": "② 分隔行格数 ≠ 表头格数",
                "detail": f"表头 {header_n} 格 vs 分隔行 {sep_n} 格 ⇒ 整表不被识别（GFM §4.10 Ex.200）",
            })

        for num, row in block:
            n = len(split_cells(row))
            if n != sep_n and n != header_n:
                issues.append({
                    "file": path_rel,
                    "line": num,
                    "kind": "③ 数据行格数 ≠ 表头格数",
                    "detail": f"{n} 格 vs 表头 {header_n} 格 ⇒ 多出的格被静默丢弃 / 少的补空（GFM §4.10 Ex.199）",
                })

        for num, row in block:
            for seg in find_bare_pipes_in_code(row):
                issues.append({
                    "file": path_rel,
                    "line": num,
                    "kind": "④ inline code 内裸竖线",
                    "detail": f"{seg[:60]} ⇒ 竖线被当列分隔符（修法：写成 `\\|`）",
                })

    return issues


def iter_markdown_files() -> list[str]:
    out = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files", "-z", "*.md"],
        capture_output=True,
    ).stdout.decode("utf-8")
    files = [f for f in out.split("\0") if f]
    keep = []
    for f in files:
        if f.startswith(SKIP_PREFIX):
            continue
        parts = pathlib.PurePosixPath(f).parts
        if any(p in SKIP_DIR_PARTS for p in parts):
            continue
        keep.append(f)
    return sorted(keep)


def render_check_pandoc(text: str) -> str | None:
    """用 pandoc 的 gfm reader 实测渲染（返回 HTML；没装 pandoc 则 None）。"""
    try:
        r = subprocess.run(
            ["pandoc", "-f", "gfm", "-t", "html"],
            input=text, capture_output=True, text=True, timeout=30,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return None
    if r.returncode != 0:
        return None
    return r.stdout


def self_test() -> int:
    """自证：造 4 类缺陷 ⇒ 工具必须逐类能红；造合规表 ⇒ 必须不报。"""
    cases = [
        ("① 缺分隔行", "| A | B |\n| 1 | 2 |\n", "① 缺分隔行"),
        ("② 分隔行格数不符", "| A | B | C |\n|---|---|\n| 1 | 2 | 3 |\n", "② 分隔行格数 ≠ 表头格数"),
        ("③ 数据行格数不符", "| A | B |\n|---|---|\n| 1 | 2 | 3 |\n", "③ 数据行格数 ≠ 表头格数"),
        ("④ inline code 裸竖线", "| A | B |\n|---|---|\n| `x|y` | 2 |\n", "④ inline code 内裸竖线"),
    ]
    ok = True
    for name, text, expect in cases:
        got = scan_text("<self-test>", text)
        kinds = [g["kind"] for g in got]
        hit = expect in kinds
        print(f"  {'✅' if hit else '❌'} {name} ⇒ {'能红' if hit else '漏检！'}")
        if not hit:
            ok = False
            print(f"      实际报出：{kinds or '（无）'}")

    good = "| A | B |\n|---|---|\n| `x\\|y` | 2 |\n"
    got = scan_text("<self-test>", good)
    hit = len(got) == 0
    print(f"  {'✅' if hit else '❌'} 合规表（含已转义竖线）⇒ {'不报' if hit else '误报：' + str([g['kind'] for g in got])}")
    if not hit:
        ok = False

    # ⚠️ 代码围栏内的行**不是 markdown** —— 不得被当成表格块（假红会淹没真缺陷）
    fenced = "```bash\nfind . -name '*.java' | xargs -n1 basename | sort -u\n```\n"
    got = scan_text("<self-test>", fenced)
    hit = len(got) == 0
    print(f"  {'✅' if hit else '❌'} 代码围栏内的 shell 管道行 ⇒ {'不报' if hit else '误报：' + str([g['kind'] for g in got])}")
    if not hit:
        ok = False

    fenced2 = "```\n+- org.springframework.boot:spring-boot-starter-jackson:jar:4.0.8\n|  \\- tools.jackson.core:jackson-core:jar:3.1.5\n```\n"
    got = scan_text("<self-test>", fenced2)
    hit = len(got) == 0
    print(f"  {'✅' if hit else '❌'} 代码围栏内的依赖树行 ⇒ {'不报' if hit else '误报：' + str([g['kind'] for g in got])}")
    if not hit:
        ok = False

    # 渲染实测（pandoc 在场才跑）
    html = render_check_pandoc(good)
    if html is not None:
        is_table = "<table>" in html
        print(f"  {'✅' if is_table else '❌'} pandoc gfm 实测：合规表 ⇒ 渲染成 <table>（{ '是' if is_table else '否' }）")
        if not is_table:
            ok = False
    else:
        print("  ℹ️  pandoc 不在场 ⇒ 跳过渲染实测（形状判据仍逐类可红）")

    print()
    print("自检通过" if ok else "自检失败")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="GFM 表格形状守门")
    ap.add_argument("--file", help="只查这一份（相对仓根）")
    ap.add_argument("--self-test", action="store_true", help="造缺陷自证判据能红")
    ap.add_argument("--quiet", action="store_true", help="只打汇总")
    args = ap.parse_args()

    if args.self_test:
        print("═══ GFM 表格守门 · 自检 ═══")
        return self_test()

    if args.file:
        files = [args.file]
    else:
        files = iter_markdown_files()

    issues: list[dict] = []
    scanned = 0
    for rel in files:
        p = ROOT / rel
        if not p.exists():
            print(f"⚠️  文件不存在：{rel}")
            continue
        scanned += 1
        issues.extend(scan_text(rel, p.read_text(encoding="utf-8")))

    if not args.quiet and issues:
        by_file: dict[str, list[dict]] = {}
        for it in issues:
            by_file.setdefault(it["file"], []).append(it)
        for rel in sorted(by_file, key=lambda k: -len(by_file[k])):
            print(f"\n🔴 {rel}  ({len(by_file[rel])} 处)")
            for it in by_file[rel]:
                print(f"     行 {it['line']:>5}  {it['kind']:<26} {it['detail']}")

    print()
    print("=" * 68)
    print(f"扫描 {scanned} 份 markdown ⇒ 命中 {len(set(i['file'] for i in issues))} 份 / {len(issues)} 处")
    if issues:
        print("❌ 存在 GFM 表格形状缺陷 ⇒ GitHub 上这些表不会按表格渲染")
        return 1
    print("✅ 全部表格形状合规 ⇒ GitHub 上按表格渲染")
    return 0


if __name__ == "__main__":
    sys.exit(main())
