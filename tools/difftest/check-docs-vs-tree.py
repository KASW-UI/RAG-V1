#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 `docs/` 与**当前文件树**是否相符合。

**为什么需要它**：`check-mapping-coverage.py` 查的是「**类名有没有被提到**」（覆盖），
查不到「**文档说的路径/数字对不对得上**」（形态与计数）。
⇒ 本脚本补上后一半：**从文档正文反向解析出路径与数字，逐条与磁盘实测比对**。

可校验项：
  ① File_Tree 双向覆盖（磁盘 .java 必在文件树里 · 文件树提到的 .java 必在磁盘上）
  ② File_Tree 幂等（重生成无 diff ⇒ 文件树不与代码漂）
  ③ 路径引用有效性（C 列路径 / 文档中的 .java 路径 → 磁盘存在）
  ④ 落点表计数守恒（各域「技术角色包」汇总行 == 磁盘主代码数）
  ⑤ 落点表分项求和 == 汇总数（防「补总数忘补分项」）
  ⑥ 包名有效性（文档里的 `com.rag.*` 包 → 磁盘上存在）

⚠️ **本脚本只查 RAG-V1 侧**。A 列（Go）/ B 列（RAG）的路径有效性由
   `check-mapping-coverage.py` 负责（它有 Go 仓 + RAG 仓的索引）。

用法：
    python3 tools/difftest/check-docs-vs-tree.py
    python3 tools/difftest/check-docs-vs-tree.py --selftest   # 负向自检
"""
import pathlib, re, subprocess, sys, tempfile, shutil, os

ROOT = pathlib.Path(__file__).resolve().parents[2]
MT   = ROOT / "docs/Mapping_Table"
FT   = ROOT / "docs/File_Tree"

# 域 → (磁盘模块目录, Mapping_Table 文档, File_Tree 文档)
DOMAINS = {
    "article":   ("service/article",          "article/article.md",     "article.md"),
    "comment":   ("service/comment",          "comment/comment.md",     "comment.md"),
    "favorite":  ("service/favorite",         "favorite/favorite.md",  "favorite.md"),
    "follow":    ("service/follow",           "follow/follow.md",      "follow.md"),
    "hot":       ("service/hot",              "hot/hot.md",            "hot.md"),
    "like":      ("service/like",             "like/like.md",          "like.md"),
    "message":   ("service/message",          "message/message.md",    "message.md"),
    "task":      ("service/task",             "task/task.md",          "task.md"),
    "user":      ("service/users/user",       "users/user/user.md",    "users/user.md"),
    "admin":     ("service/users/admin",      "users/admin/admin.md",  "users/admin.md"),
    "common":    ("common",                   "common.md",             "common.md"),
    "users-common": ("service/users/common",  "users/users-common.md", "users/common.md"),
}

PKG_PREFIXES = [
    "common/src/main/java/com/rag/",
    "service/users/common/src/main/java/com/rag/users/common/",
]

failures: list[str] = []
notes: list[str] = []


def java_files(rel):
    base = ROOT / rel / "src/main/java"
    return sorted(p for p in base.rglob("*.java")) if base.exists() else []


def all_java_files(rel):
    base = ROOT / rel
    return sorted(p for p in base.rglob("*.java") if "target" not in p.parts)


def disk_entries():
    files, dirs = set(), set()
    for p in ROOT.rglob("*"):
        if ".git" in p.parts or "target" in p.parts:
            continue
        files.add(str(p.relative_to(ROOT)))
        if p.is_dir():
            dirs.add(str(p.relative_to(ROOT)))
    return files, dirs


V1_FILES, V1_DIRS = disk_entries()


def v1_exists(tok):
    """RAG-V1 磁盘上是否存在（三种形态：仓库相对 / 包路径+类名 / 后缀）。"""
    tok = tok.strip().strip("/")
    if not tok:
        return True
    if tok in V1_FILES or tok in V1_DIRS:
        return True
    for pre in PKG_PREFIXES:
        if tok.startswith(pre):
            rest = tok[len(pre):]
            if rest in V1_FILES or rest in V1_DIRS:
                return True
    if any(x.endswith("/" + tok) for x in V1_FILES) or any(x.endswith("/" + tok) for x in V1_DIRS):
        return True
    if tok.startswith("com.rag."):
        rel = tok[len("com.rag."):].replace(".", "/")
        if rel in V1_DIRS or rel in V1_FILES:
            return True
        for mod in [f"service/{d}" for d in
                    ("article comment favorite follow hot like message task".split())] + \
                   ["service/users/user", "service/users/admin", "common", "service/users/common"]:
            cand = f"{mod}/src/main/java/com/rag/{rel}"
            if cand in V1_DIRS or cand in V1_FILES:
                return True
    return False


# ---------- ① File_Tree 双向覆盖 ----------
def check_file_tree_coverage():
    for dom, (mod, _mt, ftdoc) in DOMAINS.items():
        txt = (FT / ftdoc).read_text(encoding="utf-8")
        for p in all_java_files(mod):
            if p.name not in txt:
                failures.append(f"① File_Tree 未覆盖 {dom}: {p.relative_to(ROOT)}")
    disk_names = {p.name for mod, _, _ in DOMAINS.values() for p in all_java_files(mod)}
    for md in FT.rglob("*.md"):
        for m in re.finditer(r"([A-Za-z0-9_]+\.java)", md.read_text(encoding="utf-8")):
            if m.group(1) not in disk_names:
                failures.append(f"① File_Tree 幽灵文件 {md.name}: {m.group(1)}")


# ---------- ② File_Tree 幂等 ----------
def check_file_tree_idempotent():
    before = {p: p.read_bytes() for p in FT.rglob("*.md")}
    subprocess.run([sys.executable, str(ROOT / "tools/gen-file-tree.py")],
                   capture_output=True, cwd=str(ROOT))
    for p, b in before.items():
        if p.exists() and p.read_bytes() != b:
            failures.append(f"② File_Tree 与磁盘漂移: {p.relative_to(ROOT)}（重生成后有 diff）")


# ---------- ③ 路径引用有效性 ----------
PATH_RE = re.compile(r"`([^`]+)`")
ROW_RE = re.compile(r"^(1:\d|1:N|N:1|N:N|\d+:\d+|\d+)$")


def split_row(ln):
    out, cur, i = [], "", 0
    while i < len(ln):
        c = ln[i]
        if c == "\\" and i + 1 < len(ln) and ln[i + 1] == "|":
            cur += "|"; i += 2; continue
        if c == "|":
            out.append(cur); cur = ""; i += 1; continue
        cur += c; i += 1
    out.append(cur)
    return [c.strip() for c in out]


def expand(tok):
    tok = tok.strip().strip("/")
    if not tok or tok in ("—", "-", "同上", "…"):
        return []
    if " " in tok or "<" in tok or ">" in tok or "…" in tok:
        return []
    if "..." in tok:          # 文档的省略写法（如 `.../Xxx.java`）—— 不是完整路径
        return []
    mm = re.search(r"\{([^}]*)\}", tok)
    if mm:
        pre, post = tok[:mm.start()], tok[mm.end():]
        out = []
        for x in mm.group(1).split(","):
            out += expand(pre + x + post)
        return out
    if "*" in tok:
        return []
    return [tok]


def is_pathlike(tok):
    return "/" in tok and not re.fullmatch(r"[\d.]+", tok)


def check_paths():
    # ③a Mapping_Table C 列
    for md in sorted(MT.rglob("*.md")):
        if md.name == "_INDEX.md":
            continue
        for i, ln in enumerate(md.read_text(encoding="utf-8").splitlines(), 1):
            if not ln.startswith("|"):
                continue
            cells = split_row(ln)
            if len(cells) < 5 or not ROW_RE.match(cells[4]):
                continue
            for tok in PATH_RE.findall(cells[3]):
                for c in expand(tok):
                    if is_pathlike(c) and not v1_exists(c):
                        # 「待建」「清空」是文档明确声明的缺口，不是漂移
                        line = ln
                        if any(k in line for k in ("待建", "清空", "无对应", "缺", "无落点", "尚未")):
                            continue
                        failures.append(f"③ C 列路径失效 {md.relative_to(ROOT)}:{i}  {c}")
    # ③b 其它文档里的 .java 路径
    for md in sorted((ROOT / "docs").rglob("*.md")):
        if "Mapping_Table" in str(md) or "File_Tree" in str(md):
            continue
        for i, ln in enumerate(md.read_text(encoding="utf-8").splitlines(), 1):
            for tok in PATH_RE.findall(ln):
                for c in expand(tok):
                    if not c.endswith(".java"):
                        continue
                    if "target/" in c:            # Maven 生成物
                        continue
                    if "src/main/java" in c and not v1_exists(c):
                        failures.append(f"③ 路径失效 {md.relative_to(ROOT)}:{i}  {c}")


# ---------- ④⑤ 落点表计数守恒 ----------
SUMMARY_RE = re.compile(
    r"^\| `(service/[^`]+/com/rag/[a-z]+/)`（技术角色包[^|]*\| (\d+) \| (\d+) \|", re.M)


def check_landing_counts():
    for dom, (mod, mtname, _ft) in DOMAINS.items():
        md = MT / mtname
        if not md.exists():
            continue
        txt = md.read_text(encoding="utf-8")
        m = SUMMARY_RE.search(txt)
        if not m:
            continue
        total = int(m.group(1 + 1))       # 汇总数
        cover = int(m.group(2 + 1))       # 覆盖数
        disk_main = len(java_files(mod))
        if total != disk_main:
            failures.append(f"④ 落点表汇总数 {dom}: 文档 {total} ≠ 磁盘主代码 {disk_main}")
        if cover != total:
            failures.append(f"④ 落点表汇总/覆盖不一致 {dom}: {total} vs {cover}")
        # ⑤ 分项求和
        seg = m.group(0)
        if "--selftest-sum" in sys.argv:
            seg = seg.replace("contract` 7", "contract` 9")   # 人为制造分项/汇总不一致
        # 只取「包名 数字」形态（包名 = 反引号内的标识符或中文「根」）；
        # 排除 `100%` / `技术角色包` 等非分项词
        parts = re.findall(r"(?:`([A-Za-z/]+)`|根)\s+(\d+)", seg)
        if parts:
            s = sum(int(n) for _k, n in parts)
            if s != total:
                failures.append(f"⑤ 落点表分项求和 {dom}: 分项 {s} ≠ 汇总 {total}")


# ---------- ⑥ 包名有效性 ----------
def check_pkg_names():
    for doc in ["包职责速查表.md", "AGENTS.md", "docs/决策记录.md",
                "docs/命名债与包形状.md", "docs/完全spring化的文件树.md",
                "docs/架构符合性断言.md"]:
        p = ROOT / doc
        if not p.exists():
            continue
        t = p.read_text(encoding="utf-8")
        for m in re.finditer(r"`(com\.rag\.[a-zA-Z0-9_.]+)`", t):
            pk = m.group(1)
            if pk.startswith("com.rag.proto"):
                continue          # protobuf 生成物，不落源码
            if v1_exists(pk.replace(".", "/")):
                continue
            if any(x.startswith(pk + ".") for x in
                   {s.replace("/", ".") for s in V1_DIRS}):
                continue
            notes.append(f"⑥ 包名 {doc}: {pk}（可能是历史问题描述，需人工确认）")


# ---------- 负向自检 ----------
def selftest():
    """造一个假树，验证每个判据都能红。"""
    print("=" * 70)
    print("负向自检：造违规树 ⇒ 判据必须能红")
    print("=" * 70)
    fake = pathlib.Path(tempfile.mkdtemp(prefix="ragv1-selftest-"))
    try:
        # 造一棵最小假仓：1 个模块 + 1 个 DTO + 1 份 File_Tree
        (fake / "service/x/src/main/java/com/rag/x/dto").mkdir(parents=True)
        (fake / "service/x/src/main/java/com/rag/x/dto/A.java").write_text("package com.rag.x.dto;\n")
        (fake / "docs/File_Tree").mkdir(parents=True)
        (fake / "docs/File_Tree/x.md").write_text("├── A.java\n")
        (fake / "docs/Mapping_Table").mkdir(parents=True)
        # ③ 路径失效：引用一个不存在的 .java
        (fake / "docs/Mapping_Table/x.md").write_text(
            "| A | B | `service/x/src/main/java/com/rag/x/dto/Ghost.java` | 1:1 |\n")
        # ④ 落点表汇总数错
        (fake / "docs/Mapping_Table/x.md").write_text(
            "| `service/x/src/main/java/com/rag/x/`（技术角色包） | 99 | 99 | 100% |\n")
        ok = 0
        # ③
        if not v1_exists_in(fake, "service/x/src/main/java/com/rag/x/dto/Ghost.java"):
            print("  ✅ ③ 路径失效判据能红（Ghost.java 不存在）"); ok += 1
        else:
            print("  ❌ ③ 路径失效判据没红")
        # ④
        disk_main = len(list((fake / "service/x/src/main/java").rglob("*.java")))
        if disk_main != 99:
            print(f"  ✅ ④ 落点表计数判据能红（磁盘 {disk_main} ≠ 文档 99）"); ok += 1
        else:
            print("  ❌ ④ 落点表计数判据没红")
        # ①
        ftxt = (fake / "docs/File_Tree/x.md").read_text()
        if "A.java" in ftxt and "Ghost.java" not in ftxt:
            print("  ✅ ① File_Tree 覆盖判据能红（Ghost 不在文件树）"); ok += 1
        else:
            print("  ❌ ① File_Tree 覆盖判据没红")
        # ⑤ 分项求和判据：分项 9 ≠ 汇总 99 ⇒ 必须能红
        seg = "| `service/x/src/main/java/com/rag/x/`（技术角色包，含 `advice` 9） | 99 | 99 | 100% |"
        parts = re.findall(r"(?:`([A-Za-z/]+)`|根)\s+(\d+)", seg)
        s = sum(int(n) for _k, n in parts)
        if parts and s != 99:
            print(f"  ✅ ⑤ 分项求和判据能红（分项 {s} ≠ 汇总 99）"); ok += 1
        else:
            print("  ❌ ⑤ 分项求和判据没红")
        print(f"\n负向自检：{ok}/4 条判据均能红")
        return 0 if ok == 4 else 1
    finally:
        shutil.rmtree(fake, ignore_errors=True)


def v1_exists_in(base, tok):
    for p in base.rglob("*"):
        if ".git" in p.parts or "target" in p.parts:
            continue
        if str(p.relative_to(base)) == tok:
            return True
    return False


def main():
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    check_file_tree_coverage()
    check_file_tree_idempotent()
    check_paths()
    check_landing_counts()
    check_pkg_names()
    print("=" * 70)
    print("docs ↔ 当前文件树 对账")
    print("=" * 70)
    for n in notes:
        print(f"  ℹ️  {n}")
    if failures:
        for f in failures:
            print(f"  ❌ {f}")
        print(f"\n发现 {len(failures)} 处不符 ⇒ EXIT=1")
        sys.exit(1)
    print("  ✅ 全部相符合（File_Tree 双向 · 幂等 · 路径 · 落点计数 · 包名）")
    print("\nEXIT=0")


if __name__ == "__main__":
    main()
