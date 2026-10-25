#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 docs/Mapping_Table/ 各域文档的 **A / C 列覆盖率**。

**不信任文档里的自报数字** —— 本脚本从文件系统反向取实际文件清单，
再逐个回查文档正文里是否出现过该文件名 ⇒ 独立于写文档的一方。

判读：
- **C 列缺失** = RAG-V1 有这个 `.java`，但映射文档里找不到 ⇒ **文档漏了位置**（要补）
- **A 列缺失** = Go 有这个 `.go`，但映射文档里找不到 ⇒ **要么补，要么文档里已注明「无对应物（原因）」**

用法：
    python3 tools/check-mapping-coverage.py            # 全部域
    python3 tools/check-mapping-coverage.py hot like    # 指定域
"""
import pathlib, re, sys

ROOT = pathlib.Path("/Users/pr/Documents/GitHub/RAG-V1")
GO   = pathlib.Path("/Users/pr/Documents/java&golang/Sea-RideTheWind_golang")
MT   = ROOT / "docs/Mapping_Table"

# 域 → (RAG-V1 路径, Go 路径, 文档文件)
# ⚠️ 文档按域分目录：`docs/Mapping_Table/<域>/<域>.md`；`common` 是全局共享层 ⇒ 留在目录根。
# ⚠️ **一个域可以有多份文档**（users 域拆成 `_shared.md` + `users-common.md` + `user/` + `admin/`）
#    ⇒ 文档字段支持字符串或元组（元组 = 多份文档拼起来一起查覆盖）。
#
# ℹ️ **打平后的结构（2026-10-26）**：RAG-V1 侧每域**一个** jar 模块（`service/<域>/`），
#    各域的 `*-contract` 模块已并入域模块（错误契约 + 事件 DTO 在 `com.rag.<域>.contract` 包）
#    ⇒ **本表的 RAG-V1 路径不变** —— 它指的是**域的聚合根**，`contract/` 包本来就落在该根之下
#    （rglob 覆盖 ⇒ C 列分母不因打平而变）。
#    Go 侧**未动** ⇒ 两列路径语义逐项保持。
#    ⚠️ 根级 `proto/` 模块**不在本表**：它只有 `.proto`（无手写 Java），pb 类生成到 `target/`
#    ⇒ 被 `java_files()` 的 target 过滤排除 ⇒ proto 契约由 `tools/difftest/check-proto-static.sh` 负责。
DOMAINS = {
    "users":    ("service/users",              "service/user",
                 ("users/_shared.md", "users/users-common.md", "users/user/user.md", "users/admin/admin.md")),
    "common":   ("common",                     "service/common",  "common.md"),
    "article":  ("service/article",            "service/article", "article/article.md"),
    "comment":  ("service/comment",            "service/comment", "comment/comment.md"),
    "favorite": ("service/favorite",           "service/favorite","favorite/favorite.md"),
    "follow":   ("service/follow",             "service/follow",  "follow/follow.md"),
    "hot":      ("service/hot",                "service/hot",     "hot/hot.md"),
    "like":     ("service/like",               "service/like",    "like/like.md"),
    "message":  ("service/message",            "service/message", "message/message.md"),
    "task":     ("service/task",               "service/task",    "task/task.md"),
}

# 「无对应物」的免责措辞（文档若写了这些，说明是有意标注，不算缺失）
EXEMPT_PATTERNS = [
    r"无对应物", r"无 C 落点", r"没有对应", r"刻意为空", r"故意为空",
    r"无 Java 类", r"无 C 列", r"Go 侧无", r"不迁", r"已删除", r"Go 侧不存在",
]

# 映射类型词表（4 个，与 docs/Mapping_Table/README.MD 的格式契约一致）
TYPE_TOKENS = {"1:1", "1:N", "N:1", "C 新增"}


def count_map_rows(text):
    """数「映射行」—— **必须扫全行找类型词，不能固定列下标**。

    ⚠️ **踩过的坑**：`follow` / `like` / `task` 三域存在**比标准表多一列**的表，
    映射类型落在第 5 / 6 / 7 个 `|` 之后而非第 4 个 ⇒ 固定取 `parts[4]` 会**漏算 60 行**
    （实测 811 行会被数成 751 行）。

    判据：非空单元格 ≥ 4（标准映射表是 A|B|C|类型|备注 = 5 列；
    ⚠️ **备注列允许为空** —— 实测 admin 子域有 13 行是 `| A | 同上 | 同上 | 类型 | |`，
    非空格只有 4 个 ⇒ 阈值若取 5 会漏算这 13 行；
    §7.3 的「类型分布表」只有 类型|条数|说明 = 3 列 ⇒ 天然排除），
    且类型词出现在**第 4 列或更靠后**（即它在「类型」列而不是 A 列）。
    """
    n = 0
    for ln in text.splitlines():
        if not ln.startswith("|"):
            continue
        cells = [c.strip() for c in ln.split("|") if c.strip()]
        if len(cells) < 4:
            continue
        for i, c in enumerate(cells):
            if c in TYPE_TOKENS and i >= 3:
                n += 1
                break
    return n


def parse_index_table():
    """解析 `_INDEX.md` 的覆盖度表 ⇒ {域: (行数, C_分子, C_分母, A_分子, A_分母, 映射行)}。

    ⚠️ **不采信它** —— 只用于「与文件系统实测值比对」，不一致即报。
    """
    idx = MT / "_INDEX.md"
    if not idx.exists():
        return {}
    out = {}
    for ln in idx.read_text(encoding="utf-8").splitlines():
        if not ln.startswith("| **"):
            continue
        cells = [c.strip() for c in ln.split("|") if c.strip()]
        if len(cells) < 6:
            continue
        dom = cells[0].strip("*")
        if dom in ("合计", "域"):
            continue
        if dom not in DOMAINS:
            continue

        def num(s):
            s = s.replace(",", "")
            m = re.findall(r"\d+", s)
            return int(m[0]) if m else None

        # 行数格：users 是 "378 + 178 + 215 + 240 = **1,011**" ⇒ 取 "=" 之后的值
        line_cell = cells[2]
        if "=" in line_cell:
            line_cell = line_cell.split("=")[-1]
        lines = num(line_cell)
        c_frac = num(cells[3].split("/")[0])
        c_tot = num(cells[3].split("/")[1]) if "/" in cells[3] else None
        a_frac = num(cells[4].split("/")[0])
        a_tot = num(cells[4].split("/")[1]) if "/" in cells[4] else None
        rows_n = num(cells[5])
        out[dom] = (lines, c_frac, c_tot, a_frac, a_tot, rows_n)
    return out

def java_files(rel):
    """RAG-V1 侧的主代码 `.java`（排除测试与构建产物）。返回 (相对路径, 文件名) 列表。

    ⚠️ **按文件计数，不按文件名去重** —— users 域的 `user/` 与 `admin/` 两个子域下
    存在同名文件（如各自的 `UserController.java`）⇒ 去重会漏计。
    """
    base = ROOT / rel
    if not base.exists():
        return []
    out = []
    for p in base.rglob("*.java"):
        parts = p.parts
        if "test" in parts or "target" in parts:      # src/test/java/... 与 target/...
            continue
        out.append((p.relative_to(base).as_posix(), p.name))
    return sorted(out)


def go_files(rel):
    """Go 侧非测试 `.go`，同样按文件计数。"""
    base = GO / rel
    if not base.exists():
        return []
    out = []
    for p in base.rglob("*.go"):
        if p.name.endswith("_test.go"):
            continue
        out.append((p.relative_to(base).as_posix(), p.name))
    return sorted(out)

def mentions(text, name):
    """文件名是否在文档里出现过（忽略大小写）。"""
    stem = name[:-5] if name.endswith(".java") else name[:-3]
    low = text.lower()
    if name.lower() in low:
        return True
    # 文档可能写成 `XxxEntity`（去后缀）或带路径 —— 用去后缀再试一次
    return stem.lower() in low


def coverage(text, files):
    """按文件计数算出覆盖率。

    同一域内可能有**同名但不同路径**的文件（users 域的 user/ 与 admin/ 各一份）
    ⇒ 不能按文件名去重，否则漏计。判据：
      · 该文件名的**去后缀形式**在文档里出现次数 ≥ 同名文件数 ⇒ 视为已覆盖
      · 或文档里直接写了完整相对路径 ⇒ 视为已覆盖
    """
    groups = {}
    for rel, name in files:
        groups.setdefault(name, []).append(rel)

    covered, missing = 0, []
    low = text.lower()
    for name, rels in groups.items():
        stem = name[:-5] if name.endswith(".java") else name[:-3]
        n = len(rels)
        # 完整相对路径命中也算
        if any(r.lower() in low for r in rels):
            covered += n
            continue
        # 按出现次数判（同名文件要出现 n 次）
        occ = low.count(name.lower()) + low.count(stem.lower())
        if occ >= n:
            covered += n
        else:
            missing.extend(rels)
    return covered, missing

def has_exemption(text, name):
    """文档是否对该文件给了「无对应物」的理由（就近 3 行内）。"""
    stem = (name[:-5] if name.endswith(".java") else name[:-3])
    lines = text.splitlines()
    for i, ln in enumerate(lines):
        if stem.lower() in ln.lower() or name.lower() in ln.lower():
            window = "\n".join(lines[i:i+4])
            if any(re.search(pat, window) for pat in EXEMPT_PATTERNS):
                return True
    return False

def main():
    targets = sys.argv[1:] or list(DOMAINS)
    total_c = total_c_missing = total_a = total_a_missing = 0
    rows = []
    problems = []
    actual = {}          # 域 → (行数, C覆盖, C总数, A覆盖, A总数, 映射行) —— 实测值

    for dom in targets:
        if dom not in DOMAINS:
            print(f"❌ 未知域：{dom}", file=sys.stderr); continue
        v_rel, g_rel, docs = DOMAINS[dom]
        doc_list = (docs,) if isinstance(docs, str) else tuple(docs)
        missing_docs = [d for d in doc_list if not (MT / d).exists()]
        if missing_docs:
            problems.append(f"❌ {dom}: 文档不存在 {missing_docs}")
            rows.append((dom, ",".join(doc_list), "-", "-", "-", "-", "文档缺失"))
            continue
        # ⚠️ **路径不存在 = 域路径表已过时**（目录改名 / 模块被并掉）。
        #    绝不能让它悄悄走 `java_files()==[]` ⇒ 0/0 ⇒ 「100% 覆盖」**假绿**。
        if not (ROOT / v_rel).exists():
            problems.append(f"❌ {dom}: RAG-V1 路径不存在 `{v_rel}`（域路径表与磁盘不符）")
            rows.append((dom, ",".join(doc_list), "-", "-", "-", "-", "路径缺失"))
            continue
        if not (GO / g_rel).exists():
            problems.append(f"❌ {dom}: Go 路径不存在 `{g_rel}`（域路径表与磁盘不符）")
            rows.append((dom, ",".join(doc_list), "-", "-", "-", "-", "路径缺失"))
            continue
        text = "\n".join((MT / d).read_text(encoding="utf-8") for d in doc_list)

        js = java_files(v_rel)
        gs = go_files(g_rel)
        c_covered, c_missing = coverage(text, js)
        a_covered, a_missing = coverage(text, gs)
        # A 列：文档已注明「无对应物（原因）」的不算缺失
        a_missing = [r for r in a_missing if not has_exemption(text, r)]

        total_c += len(js);  total_c_missing += len(c_missing)
        total_a += len(gs);  total_a_missing += len(a_missing)

        c_pct = 100.0 if not js else c_covered*100/len(js)
        a_pct = 100.0 if not gs else a_covered*100/len(gs)
        rows.append((dom, ",".join(doc_list), f"{c_covered}/{len(js)}",
                     f"{c_pct:.0f}%", f"{a_covered}/{len(gs)}",
                     f"{a_pct:.0f}%", "OK" if not c_missing and not a_missing else "有缺口"))
        for r in c_missing:
            problems.append(f"❌ {dom}: C 列缺失 `{r}`")
        for r in a_missing:
            problems.append(f"❌ {dom}: A 列缺失 `{r}`")

        # 实测值：行数 + 映射行（映射行按全行扫类型词，避开多列表的列位陷阱）
        lines = sum(len((MT / d).read_text(encoding="utf-8").splitlines()) for d in doc_list)
        actual[dom] = (lines, c_covered, len(js), a_covered, len(gs), count_map_rows(text))

    print(f"{'域':<10} {'文档':<16} {'C 列':<10} {'覆盖':<7} {'A 列':<10} {'覆盖':<7} 状态")
    print("-" * 74)
    for r in rows:
        print(f"{r[0]:<10} {r[1]:<16} {r[2]:<10} {r[3]:<7} {r[4]:<10} {r[5]:<7} {r[6]}")
    print("-" * 74)
    print(f"{'合计':<10} {'':<16} {f'{total_c-total_c_missing}/{total_c}':<10} "
          f"{'':7} {f'{total_a-total_a_missing}/{total_a}':<10}")

    # ── `_INDEX.md` 自报数字 vs 文件系统实测值 ──────────────────────────
    # 文档里写的「行数 / 覆盖 / 映射行」是**自报数**，会随文档改动漂移
    # （实测踩过：favorite 的行数改了但索引没改 ⇒ 自报数停在旧值）。
    # ⇒ 这里做机器对账，不一致即报缺口。
    idx = parse_index_table()
    drift = []
    for dom, (lines, c_cov, c_tot, a_cov, a_tot, mrows) in actual.items():
        if dom not in idx:
            continue
        il, ic, ict, ia, iat, im = idx[dom]
        for label, got, want in (("行数", il, lines),
                                 ("C 列覆盖", (ic, ict), (c_cov, c_tot)),
                                 ("A 列覆盖", (ia, iat), (a_cov, a_tot)),
                                 ("映射行", im, mrows)):
            if got != want:
                drift.append(f"❌ _INDEX.md {dom} 的「{label}」自报 {got} ≠ 实测 {want}")
    for d in drift:
        problems.append(d)
    if idx:
        print(f"\n_INDEX.md 自报数字对账：{'✅ 全部一致' if not drift else f'❌ {len(drift)} 处漂移'}"
              f"（{len(actual)} 个域 × 4 项）")
    else:
        print("\n_INDEX.md 自报数字对账：跳过（索引不存在）")

    if problems:
        print("\n".join(problems))
    print(f"\nEXIT={'0' if not problems else '1'}  "
          f"C 列 {total_c-total_c_missing}/{total_c} · A 列 {total_a-total_a_missing}/{total_a}")
    return 0 if not problems else 1

if __name__ == "__main__":
    sys.exit(main())
